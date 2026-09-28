package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人臉偵測層 — 只用 Android framework 內置嘅 {@link FaceDetector}。
 *
 * <p>點解係內置而唔係 TFLite / OpenCV / ML Kit：{@code android.media.FaceDetector} 由 API 1
 * 就有，喺 framework 入面（android-25 android.jar 2711 個 public class 入面，佢係唯一一個
 * 視覺偵測 class），所以 APK 增量 0、唔使 aar、唔使 native lib、唔使 AndroidX、唔使郁
 * build.gradle —— 呢個 repo 刻意 useAndroidX=false + minSdk 19 + --offline build，加任何
 * ML runtime 都會撞爛其中一條。
 *
 * <p>訊號比想像中多：{@code Face} 有 public 嘅 {@code getMidPoint(PointF)}（傳入 reusable
 * PointF，零分配）、{@code eyesDistance()}（粗略距離）同 {@code pose(EULER_X/Y/Z)}（head
 * 姿態），所以位置／距離／對方有冇轉開個頭全部有得用，唔使另外跑一個 pose 模型。
 *
 * <p><b>執行模型</b>：{@link #start(long)} 訂閱 CameraController 嘅 MJPEG 前幀，放喺一個
 * 容量 1 嘅 mailbox（丟舊留新），由一條專用 worker thread 逐幀 decode + 偵測。偵測<b>唔會</b>
 * 行喺 CameraController 嘅相機 thread 上 —— 嗰個 thread 仲要 fan-out 俾其他串流 client，
 * 一 block 就會拖慢成條 MJPEG（見 CameraController class 頭嘅說明同 handleCameraStream()
 * 用同一個 mailbox 嘅原因）。mailbox 容量 1 令偵測自然被自己嘅耗時 rate-limit：跑得慢就
 * 一直攞最新嗰幀，唔會堆積。
 *
 * <p><b>座標</b>：AOSP FaceDetector 內部會縮圖，所以對外一律同時俾 normalized（x,y 喺
 * 0..1）同 raw pixel，方便前端免記得解析度、亦方便 debug。頭 10 次結果會 Log.i 出嚟，等
 * 可以喺實機 logcat 對住畫面校準座標空間（唔好信 platform 唔使校準）。
 */
public final class FaceDetectCenter {
    private static final String TAG = "FaceDetect";

    /**
     * 偵測器實際睇嘅輸入尺寸 —— <b>4:3 320x240</b>。
     *
     * <p>相機 sensor 係 4:3（最大拍攝 4208x3120 就係 3:4），而 preview 開 16:9，所以
     * 先 crop 一個 4:3 視窗出嚟先至餵偵測器，令塊面保持自然比例。
     * {@link #fourThreeBox} 只 crop、唔 squash，所以唔論 driver 係 crop 定 squash 咗個
     * sensor，比例都一定還原。
     *
     * <p>實機量測（A17, 2026-09, 同一個被試）畀咗一個硬結論：
     * <b>輸入唔可以大過 320 級</b> —— 640x360 明明有正常大小嘅塊面（eyeDist 42），recall
     * 竟然係 0%，而 320 級 eyeDist 46 有 64%。即 FaceDetector 內部有輸入尺寸上限，
     * 唔係愈大愈好。w/h 上限因此鎖 320。
     *
     * <p>另外量到：塊面越大 recall 越好（eyeDist 21 → 33%，46 → 64%），正常行為。
     *
     * <p>4:3 vs 16:9 本身仲未有統計顯著差異（45 次 poll、53% vs 64%，差距喺抽樣誤差
     * 之內），所以兩者都可以用，預設跟返 sensor 嘅 4:3。face/start?w=&h=&crop=&roi=
     * 做成 run-time 可調，就係為咗隨時喺真機再量。
     */
    private static final int DEFAULT_DETECT_W = 320;
    private static final int DEFAULT_DETECT_H = 240;
    private static final int DETECT_MIN = 96;
    private static final int DETECT_MAX = 320;   // 640 級實測 recall 0%，唔好開放
    /**
     * decode 用嘅長邊。取 640（1280x720 源頭 → inSampleSize=2 → 640x360）。
     * decode 好平（大約 20ms），而真正花時間嘅係 detect，所以 decode 解得細啲、留返
     * 空間俾 ROI／crop，總成本仍然遠低過「解大圖再 detect」。
     */
    private static final int DECODE_MAX_DIM = 640;
    /**
     * ROI 裁切範圍（decode 圖嘅像素），縮放到偵測輸入尺寸。預設<b>關</b>：實測 ROI 放大
     * 令 eyeDist 由 21 升到 37，偵測率由 33% 跌到 2%，所以唔係幫手。仍然保留做可選
     * （face/start?roi=1），因為極遠距離嘅細面先可能需要。
     */
    private static final int ROI_W = 240;
    private static final int ROI_H = 180;
    /** 連續幾次 ROI 內搵唔到就退回做一次全畫面搜尋（塊面可能走咗出 ROI）。 */
    private static final int ROI_MISS_LIMIT = 2;
    /** 短邊細過咁就唔試（decode 成功但無意義，浪費時間）。 */
    private static final int MIN_DIM = 64;
    private static final int MAX_FACES = 5;
    /** 人臉大約幾多個「眼距」闊/高 —— 由 eyeDistance 推算個粗略 box 俾前端畫框用，
     *  唔係真實 bbox。
     *
     *  <p><b>eyeDistance 唔係距離。</b>佢係 {@code Face.eyesDistance()} 嘅原始輸出：
     * 兩眼喺分析圖上相隔幾多 pixel。冇任何單位。數字越大 = 塊面喺畫面越大（通常企得越近），
     * 但同一距離下體型大嘅人會明顯高過體型細嘅人，所以當距離用係唔準嘅。 */
    private static final float FACE_SIZE_IN_EYE_DISTANCES = 2.0f;
    /** 前幾次結果 log 出嚟俾實機校準用。 */
    private static final int CALIBRATION_LOGS = 10;

    /**
     * 目標 duty cycle：每幀之後瞓到「工作時間只佔週期嘅呢個比例」。
     *
     * <p><b>唔可以攞走。</b>2026-09 實機測試：唔節流嘅話 loop 會平坦行到 ~22 fps
     * （detectMs 46），部 RK3288 喺咩嘅持續負荷下<b>直接重啟</b>——logcat crash buffer
     * 係空嘅、冇 tombstone，所以唔係 app crash，係系統層 reset（供電 brownout 或者
     * vendor firmware watchdog）。所以呢個係「機械人唔會因為俾人查下有冇人面就死機」嘅
     * 保證，唔係效能微調。
     *
     * <p>用 duty 而唔係固定週期，係因為一幀耗時會隨設定浮動（實測 53ms 去到 78ms），
     * 固定週期會令實際負荷比例寬幅波動。用 {@code face/start?duty=} 就係為咗喺真機
     * 由低至高慢慢搵返部機企得穩嘅上限。
     *
     * <p><b>實測搵到嘅安全上限係 0.45</b>（約 6.6 fps，50 秒 soak 無重啟）。0.55 嗰次
     * 測試期間偵測迴圈重啟咗、analyzed 計數器歸零，所以 0.55 當無驗證過，唔開放做預設。
     * 過度調高嘅代價係實機重啟甚至 app 被殺，遠大過多幾 fps 嘅價值。
     */
    private static final float DEFAULT_TARGET_DUTY = 0.45f;
    private static final float MIN_DUTY = 0.05f;
    private static final float MAX_DUTY = 0.60f;
    /** 就算 duty 開到好高都唔會快過呢個週期（太密冇意思，反而製造 load spike）。 */
    private static final long MIN_PERIOD_MS = 60;
    /**
     * 冇人輪詢 {@code camera/face} 超過呢個時間就自動熄掉偵測。偵測成本應該只喺有人
     * 真正喺度睇個畫面時先付；前端每 250ms 輪詢一次，所以正常使用永遠唔會觸發。
     * 冇呢個的話用戶閂咗分頁個 loop 就會空轉落去,長期空轉正正就係上面重啟嘅成因。
     */
    private static final long IDLE_STOP_MS = 30000;

    private static final List<DetectedFace> NO_FACES = Collections.emptyList();

    private final CameraController cameraController;
    /** 容量 1 + 丟舊留新：偵測慢過影格率時只保留最新一幀，唔會無限堆積（同一個做法見
     *  CameraApi.handleCameraStream()）。 */
    private final ArrayBlockingQueue<byte[]> inbox = new ArrayBlockingQueue<>(1);
    private final AtomicLong framesSeen = new AtomicLong();
    private final AtomicLong analyzedCount = new AtomicLong();
    /** 只喺 worker thread 用，唔使同步。 */
    private final PointF reusableMid = new PointF();

    private CameraController.FrameListener listener;
    private Thread worker;
    private volatile boolean running;

    private FaceDetector cachedDetector;
    private int cachedW;
    private int cachedH;

    private volatile Snapshot latest = Snapshot.none();
    private volatile String lastError;
    private volatile long lastActivityMs;

    // ROI 追蹤狀態（淨係 worker thread 寫／讀，唔使 volatile）：
    // 上一個目標喺 4:3 視窗入面嘅 normalized 位置。預設關（roiEnabled=false），
    // 實測證明放大塊面只會令 recall 變差。
    private boolean roiValid;
    private float roiNx, roiNy;
    private int roiMisses;

    // 可調參數（淨係 start() 時寫，之後淨係讀，所以唔使 volatile）：
    private int detectW = DEFAULT_DETECT_W;
    private int detectH = DEFAULT_DETECT_H;
    /** 預設開：跟返 sensor 嘅 4:3，令偵測器見到自然比例嘅臉。 */
    private boolean useFourThreeCrop = true;
    /** 預設關：實測 ROI 放大令 eyeDist 21→37 而 recall 由 33% 跌到 2%。 */
    private boolean roiEnabled = false;
    /** 目標 duty cycle（見 DEFAULT_TARGET_DUTY），face/start?duty= 可調。 */
    private volatile float targetDuty = DEFAULT_TARGET_DUTY;

    /**
     * 距離估算嘅校準係數：{@code distanceM ≈ calibK / eyeDistancePx}。
     * eyeDistance 同距離成反比（塊面越大越近），但個比例常數取決於鏡頭 FOV、感光尺寸同
     * **被試者本身的面形**，所以唔可以用一個通用常數 —— 咁就變成假精確度。改成單點校準：
     * 用戶講一次「我而家企喺 X 米」，記低嗰一刻嘅 eyeDistance，反推 K。0 = 未校準。
     */
    private volatile double calibK;
    /** 最近 N 個 eyeDistance，用嚟取中位數做校準（單一幀太唔穩）。 */
    private final double[] recentEye = new double[12];
    private int recentEyeCount;
    private int recentEyePos;

    public FaceDetectCenter(CameraController cameraController) {
        this.cameraController = cameraController;
    }

    // ---------------- result types ----------------

    /** 單一人臉結果。x/y 已經 normalize 成 0..1（相對原串流畫面，唔受 preview 解像度影響），
     *  px/py 係分析用 bitmap 上嘅 raw pixel（調試用）。 */
    public static final class DetectedFace {
        public final float x, y, eyeDistance, confidence;
        public final float yaw, pitch, roll;
        public final int px, py, boxW, boxH;

        DetectedFace(float x, float y, int px, int py, float eyeDistance, int boxW, int boxH,
                     float confidence, float yaw, float pitch, float roll) {
            this.x = x;
            this.y = y;
            this.px = px;
            this.py = py;
            this.eyeDistance = eyeDistance;
            this.boxW = boxW;
            this.boxH = boxH;
            this.confidence = confidence;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
        }
    }

    /** 一次偵測嘅完整結果（immutable 快照，讀寫唔會黐住）。 */
    public static final class Snapshot {
        public final int count;
        public final long atMs;
        public final long detectMs;
        public final int srcW, srcH;
        public final List<DetectedFace> faces;
        public final String error;

        Snapshot(int count, long atMs, long detectMs, int srcW, int srcH,
                 List<DetectedFace> faces, String error) {
            this.count = count;
            this.atMs = atMs;
            this.detectMs = detectMs;
            this.srcW = srcW;
            this.srcH = srcH;
            this.faces = faces == null ? NO_FACES : faces;
            this.error = error;
        }

        static Snapshot none() {
            return new Snapshot(-1, 0L, 0L, 0, 0, NO_FACES, null);
        }
    }

    // ---------------- lifecycle ----------------

    public boolean isRunning() {
        return running;
    }

    /** 開相機（如未開）+ 訂閱前幀 + 起 worker。回 error 字串表示失敗，成功回 null。
     *
     *  <p>detect 輸入尺寸 / 4:3 crop / ROI 三個開關做成 run-time 可調，係因為實機量到
     *  FaceDetector 對輸入大小非常敏感（同一個被試：320 級 33%、640 級 0%），而甜點位
     *  置要靠量。所以面板可以即場掃描，唔使每個組合 build 一次。 */
    public String start(long cameraStartTimeoutMs) {
        return start(cameraStartTimeoutMs, DEFAULT_DETECT_W, DEFAULT_DETECT_H, true, false);
    }

    public String start(long cameraStartTimeoutMs, int w, int h, boolean fourThreeCrop, boolean roi) {
        return start(cameraStartTimeoutMs, w, h, fourThreeCrop, roi, DEFAULT_TARGET_DUTY);
    }

    public String start(long cameraStartTimeoutMs, int w, int h, boolean fourThreeCrop, boolean roi,
                        float duty) {
        if (running) return null;
        int ww = Math.max(DETECT_MIN, Math.min(DETECT_MAX, w));
        int hh = Math.max(DETECT_MIN, Math.min(DETECT_MAX, h));
        CameraController.StartResult started = cameraController.start(cameraStartTimeoutMs);
        if (started.error != null) return started.error;
        running = true;
        framesSeen.set(0);
        analyzedCount.set(0);
        lastError = null;
        lastActivityMs = System.currentTimeMillis();
        detectW = ww;
        detectH = hh;
        useFourThreeCrop = fourThreeCrop;
        roiEnabled = roi;
        targetDuty = Math.max(MIN_DUTY, Math.min(MAX_DUTY, duty));
        roiValid = false;
        roiMisses = 0;
        listener = new CameraController.FrameListener() {
            @Override
            public void onFrame(CameraController.Frame frame) {
                if (!running) return;
                inbox.poll(); // 丟走舊嘅
                inbox.offer(frame.jpeg);
            }
        };
        cameraController.subscribe(listener);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "FaceDetectWorker");
        worker.start();
        Log.i(TAG, "start: detect input " + detectW + "x" + detectH + " crop4x3=" + useFourThreeCrop
                + " roi=" + roiEnabled + " decode<=" + DECODE_MAX_DIM
                + " max=" + MAX_FACES + " duty=" + targetDuty
                + " calibK=" + (calibK > 0 ? String.valueOf(calibK) : "uncalibrated"));
        return null;
    }

    /** 取消訂閱 + 停 worker。冇其他串流 client 嘅話會順手放咗相機（同 stopIfIdle 嘅語意）。 */
    public void stop() {
        shutdown();
        cameraController.stopIfIdle();
    }

    /** 收尾（取消訂閱 + 清 running）。分開 {@link #stop()} 係因為 worker thread 自己
     *  idle 熄機嗰陣唔應該順手叫 stopIfIdle —— 嗰個係「用戶主動停」先需要嘅動作。 */
    private void shutdown() {
        if (!running) return;
        running = false;
        CameraController.FrameListener l = listener;
        listener = null;
        if (l != null) cameraController.unsubscribe(l);
        Thread w = worker;
        worker = null;
        if (w != null && w != Thread.currentThread()) w.interrupt();
    }

    /** 由 camera/face 輪詢端點叫,證明「真係有人喺度睇」。 */
    public void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    // ---------------- distance calibration ----------------

    public double calibK() {
        return calibK;
    }

    /** 校準係咪喺同一個偵測幾何下取得（見 CameraApi.restoreCalibrationIfCompatible）。 */
    public boolean geometryMatches(int w, int h, boolean crop, boolean roi) {
        return w == detectW && h == detectH && crop == useFourThreeCrop && roi == roiEnabled;
    }

    public int detectW() { return detectW; }
    public int detectH() { return detectH; }
    public boolean fourThreeCrop() { return useFourThreeCrop; }
    public boolean roi() { return roiEnabled; }

    public void setCalibK(double k) {
        this.calibK = k;
    }

    /**
     * 用「用戶報知嘅真實距離」反推比例常數。eyeDistance 同距離成反比，所以
     * {@code k = distanceM * eyeDistancePx}。
     *
     * <p>用中位數而唔係單一幀：眼距每幀都會因 head pose / 眨眼 / 表情擺動，中位數穩陣得多。
     * 樣本唔夠就回 null，寧願叫用戶等陣再校準，都唔好俾一個亂數當基準。
     */
    public Double calibrate(double distanceM) {
        double median;
        synchronized (recentEye) {
            if (recentEyeCount < 4) return null;
            double[] copy = new double[recentEyeCount];
            System.arraycopy(recentEye, 0, copy, 0, recentEyeCount);
            java.util.Arrays.sort(copy);
            median = copy[copy.length / 2];
        }
        if (median <= 0) return null;
        calibK = distanceM * median;
        return Double.valueOf(calibK);
    }

    public Snapshot latest() {
        return latest;
    }

    public long analyzedCount() {
        return analyzedCount.get();
    }

    // ---------------- worker ----------------

    private void runLoop() {
        int calibrationLeft = CALIBRATION_LOGS;
        while (running) {
            if (lastActivityMs != 0 && System.currentTimeMillis() - lastActivityMs > IDLE_STOP_MS) {
                // 冇人輪詢 = 冇人喺度睇,熄咗佢。長期空轉就係令部機重啟嘅成因。
                Log.i(TAG, "idle " + (IDLE_STOP_MS / 1000) + "s, auto-stopping detection");
                shutdown();
                break;
            }
            byte[] jpeg;
            try {
                jpeg = inbox.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (jpeg == null) continue;
            long frameAtMs = System.currentTimeMillis();
            framesSeen.incrementAndGet();
            try {
                Snapshot s = analyze(jpeg, calibrationLeft);
                calibrationLeft = s.count > 0 ? Math.max(0, calibrationLeft - 1) : calibrationLeft;
                analyzedCount.incrementAndGet();
                lastError = s.error;
                latest = s;
            } catch (Throwable t) {
                // OEM 有可能剝咗 FaceDetector 嘅 native 實作，或者 bitmap 對佢嚟講唔啱。
                // 呢度一定要吞住：一次 throw 唔應該停低成個 loop（否則前端只見到永久 freeze）。
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                latest = new Snapshot(0, System.currentTimeMillis(), 0, 0, 0, NO_FACES, lastError);
                Log.w(TAG, "detect failed, stopping detection loop", t);
                shutdown();
                break;
            }
            // 節流：按實際工作時間計 duty cycle，補返 sleep。mailbox 容量 1 令呢個變成
            // 真正嘅節流（丟中間幀），唔係排隊。
            long busyMs = System.currentTimeMillis() - frameAtMs;
            long periodMs = (long) Math.ceil(busyMs / Math.max(MIN_DUTY, targetDuty));
            if (periodMs < MIN_PERIOD_MS) periodMs = MIN_PERIOD_MS;
            long sleepMs = periodMs - busyMs;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        inbox.clear();
        latest = Snapshot.none();
    }

    /**
     * 由任意尺寸嘅 decode 圖抽一個 4:3 視窗（只 crop，唔 squash），再喺視窗入面按需
     * 裁 ROI，最後縮到 {@link #DETECT_W}x{@link #DETECT_H} 餵偵測器。
     *
     * <p>分兩層 crop 係為咗「保持比例」同「放大塊面」兩件事可以各自控制：
     * 4:3 視窗保證 FaceDetector 見到嘅係自然比例嘅臉（sensor 係 4:3，但 preview 可能係
     * 16:9，driver 已經拉過一次）；ROI 係喺 4:3 視窗入面再收細，等塊面佔畫面多啲，
     * 而輸入維度維持喺實測好用嘅 320x240。
     */
    private Snapshot analyze(byte[] jpeg, int calibrationLeft) {
        long t0 = System.nanoTime();
        Bitmap decoded = decodeForCrop(jpeg);
        if (decoded == null) {
            return new Snapshot(0, System.currentTimeMillis(), 0, 0, 0, NO_FACES,
                    "BitmapFactory returned null for the preview JPEG");
        }
        Bitmap view = null;
        try {
            int decW = decoded.getWidth();
            int decH = decoded.getHeight();
            if (decW < MIN_DIM || decH < MIN_DIM) {
                return new Snapshot(0, System.currentTimeMillis(), elapsedMs(t0), decW, decH,
                        NO_FACES, "decoded bitmap too small: " + decW + "x" + decH);
            }
            // 1) 4:3 視窗（置中 crop）；關咗就用成幅 decode 圖
            int baseX, baseY, baseW, baseH;
            if (useFourThreeCrop) {
                int[] box4x3 = fourThreeBox(decW, decH);
                baseX = box4x3[0]; baseY = box4x3[1]; baseW = box4x3[2]; baseH = box4x3[3];
            } else {
                baseX = 0; baseY = 0; baseW = decW; baseH = decH;
            }

            // 2) ROI 視窗（預設關；量到放大塊面只會令 recall 變差）
            boolean usedRoi = false;
            int winX = 0, winY = 0, winW = baseW, winH = baseH;
            if (roiEnabled && roiValid) {
                int rw = Math.min(ROI_W, baseW);
                int rh = Math.min(ROI_H, baseH);
                int cx = baseX + (int) (roiNx * baseW) - rw / 2;
                int cy = baseY + (int) (roiNy * baseH) - rh / 2;
                int clampedX = Math.max(baseX, Math.min(cx, baseX + baseW - rw));
                int clampedY = Math.max(baseY, Math.min(cy, baseY + baseH - rh));
                if (rw < baseW || rh < baseH) {
                    winX = clampedX;
                    winY = clampedY;
                    winW = rw;
                    winH = rh;
                    usedRoi = true;
                }
            }

            // 3) 縮到偵測器尺寸。注意 createScaledBitmap 喺尺寸相同時會原樣返回入面個
            //    物件，所以 recycle 前一定要確認唔係同一個 reference。
            Bitmap crop = null;
            if (winW != decW || winH != decH) {
                crop = Bitmap.createBitmap(decoded, winX, winY, winW, winH);
            } else {
                crop = decoded;
            }
            view = Bitmap.createScaledBitmap(crop, detectW, detectH, true);
            if (view == crop) view = null; // 尺寸一模一樣，createScaledBitmap 原樣返回

            FaceDetector.Face[] out = new FaceDetector.Face[MAX_FACES];
            int n = detectorFor(detectW, detectH).findFaces(view, out);
            float sx = (float) winW / detectW;
            float sy = (float) winH / detectH;
            List<DetectedFace> list = new ArrayList<DetectedFace>(n > 0 ? n : 0);
            for (int i = 0; i < n; i++) {
                FaceDetector.Face f = out[i];
                if (f == null) continue;
                out[i].getMidPoint(reusableMid);
                // 返去原畫面（decode 圖）座標，再 normalize 成 0-1
                float fullX = winX + reusableMid.x * sx;
                float fullY = winY + reusableMid.y * sy;
                float eyeFull = f.eyesDistance() * sx;
                int box = Math.round(eyeFull * FACE_SIZE_IN_EYE_DISTANCES);
                list.add(new DetectedFace(
                        decW == 0 ? 0f : fullX / decW,
                        decH == 0 ? 0f : fullY / decH,
                        Math.round(fullX), Math.round(fullY),
                        eyeFull, box, box,
                        f.confidence(),
                        f.pose(FaceDetector.Face.EULER_X),
                        f.pose(FaceDetector.Face.EULER_Y),
                        f.pose(FaceDetector.Face.EULER_Z)));
            }

            // 4) 更新 ROI 狀態：搵到就收窄去新位置，搵唔到就退返做全畫面搜尋
            if (!list.isEmpty()) {
                DetectedFace first = list.get(0);
                roiNx = first.x;
                roiNy = first.y;
                roiValid = true;
                roiMisses = 0;
                // 只記第一個（最主要嗰個）目標，校準取中位數就夠，唔使被多張臉稀釋。
                synchronized (recentEye) {
                    recentEye[recentEyePos] = first.eyeDistance;
                    recentEyePos = (recentEyePos + 1) % recentEye.length;
                    if (recentEyeCount < recentEye.length) recentEyeCount++;
                }
            } else if (usedRoi) {
                roiMisses++;
                if (roiMisses > ROI_MISS_LIMIT) roiValid = false;
            }

            long ms = elapsedMs(t0);
            if (calibrationLeft > 0 && !list.isEmpty()) {
                DetectedFace d = list.get(0);
                // 故意 log raw pixel + eyeDistance：實機對住畫面睇一次就知座標空間有冇縮過。
                Log.i(TAG, String.format(Locale.US,
                        "calib dec=%dx%d win=%dx%d@(%d,%d) roi=%s mid=(%d,%d) eyeDist=%.1f conf=%.2f",
                        decW, decH, winW, winH, winX, winY, usedRoi ? "y" : "n",
                        d.px, d.py, d.eyeDistance, d.confidence));
            }
            return new Snapshot(list.size(), System.currentTimeMillis(), ms, detectW, detectH,
                    list, null);
        } finally {
            if (view != null && view != decoded && !view.isRecycled()) view.recycle();
            if (!decoded.isRecycled()) decoded.recycle();
        }
    }

    /**
     * 喺 (w x h) 入面搵一個最大嘅 4:3 視窗（置中）。源頭寬過 4:3 就切走兩邊，高過就切
     * 上下。**只 crop，唔會 squash** —— 所以無論 driver 係將 4:3 sensor crop 成 16:9 定
     * 係 squash 成 16:9，呢度都一定攞返自然比例。
     */
    private static int[] fourThreeBox(int w, int h) {
        int bw = w, bh = h;
        if (w * 3 > h * 4) {
            bw = h * 4 / 3;
        } else if (w * 3 < h * 4) {
            bh = w * 3 / 4;
        }
        if (bw < 1) bw = 1;
        if (bh < 1) bh = 1;
        return new int[] { (w - bw) / 2, (h - bh) / 2, bw, bh };
    }

    private static long elapsedMs(long t0Nanos) {
        return (System.nanoTime() - t0Nanos) / 1000000L;
    }

    /** 兩 pass decode：先用 inJustDecodeBounds 讀頭（唔佔記憶體）計 inSampleSize，再實際
     *  decode。1280x720 -> sample 2 -> 640x360。留 640 係為咗 ROI 裁切之後仲有像素放大。
     *  RGB_565 減半記憶體亦係 detector 想要嘅格式（唔使 ARGB）。 */
    private Bitmap decodeForCrop(byte[] jpeg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= DECODE_MAX_DIM
                || bounds.outHeight / (sample * 2) >= DECODE_MAX_DIM) {
            sample *= 2;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    /** FaceDetector 建構好平，但每幀 new 都係多餘；同尺寸就重用（尺寸變就換一個）。 */
    private FaceDetector detectorFor(int w, int h) {
        FaceDetector d = cachedDetector;
        if (d == null || cachedW != w || cachedH != h) {
            d = new FaceDetector(w, h, MAX_FACES);
            cachedDetector = d;
            cachedW = w;
            cachedH = h;
        }
        return d;
    }
}
