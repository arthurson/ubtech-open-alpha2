package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * 純 Java 顏色 blob 追蹤：零額外依賴（唔用 TFLite／OpenCV／ML Kit，用戶定案）。
 *
 * 鏈路：{@link CameraController} 連續預覽 JPEG 幀 → 本類按 intervalMs 抽樣 →
 * 背景線程 downscale 到 160x120（16:9 預覽 → 160x90）→ 逐像素 RGB→HSV 閾值
 * （hue wrap-aware，預設紅色 335°-25°）→ 最大連通 blob（flood fill）取質心 →
 * 歸一化偏移 dx/dy → 死區＋比例步進驅動頭部 yaw(19)/pitch(20) 跟色
 * （cmd05 單舵機，經 {@link UbxApi}）。
 *
 * 點解唔使 ROI（同 FaceTrackCenter 分叉之處）：160x120 全圖逐像素 HSV＋flood fill
 * 喺 RK3288 上係個位數 ms，直接每幀全圖掃，ROI 省唔到嘢，反而多套狀態要維護。
 * 預設 200ms 掃一次（CPU 幾乎零感），跟到色自動轉 100ms（autoInterval）。
 *
 * 互斥：同 FaceTrackCenter 爭同一對頭舵機，兩邊唔可以齊行——互斥放
 * {@link CameraApi} 層（start 一邊即停另一邊），本類唔識對方存在。
 * 人臉 WS 同理 mute：一律唔經 EventBus，只留 HTTP status 輪詢＋前端 overlay。
 * status 形狀刻意同 face/track/status 對齊（running／fx/fy/fw/fh／confidence／
 * effectiveIntervalMs／config／servo），前端同一個畫框邏輯食得落。
 */
public final class ColorTrackCenter {
    private static final String TAG = "ColorTrackCenter";

    /** 頭部舵機：19 yaw（左右）、20 pitch（上下），範圍與前端校準表一致（見 app-core.js SERVO_CALIBRATION）。 */
    public static final int PAN_ID = 19;
    public static final int TILT_ID = 20;
    public static final int PAN_MIN = 75;
    public static final int PAN_MAX = 165;
    public static final int PAN_HOME = 120;
    public static final int TILT_MIN = 105;
    public static final int TILT_MAX = 155;
    public static final int TILT_HOME = 120;

    public static final int DEFAULT_INTERVAL_MS = 200;
    public static final int DEFAULT_TRACK_INTERVAL_MS = 100;
    public static final int DEFAULT_STEP_DEG = 17;
    public static final float DEFAULT_DEADBAND = 0.10f;
    public static final int DEFAULT_TIME_MS = 400;
    /** 預設跟紅色：hue 335°→25°（wrap）、飽和＋明度門檻去背影／白光。 */
    public static final float DEFAULT_H_MIN = 335f;
    public static final float DEFAULT_H_MAX = 25f;
    public static final float DEFAULT_S_MIN = 0.45f;
    /** 飽和度上限（預設 1.0 即唔限；白色 preset 用 0.25 去彩色，只留低飽和白／灰白）。 */
    public static final float DEFAULT_S_MAX = 1.0f;
    public static final float DEFAULT_V_MIN = 0.25f;
    public static final float DEFAULT_V_MAX = 1.0f;
    public static final int DEFAULT_MIN_PIXELS = 80;
    /** 偵測圖寬鎖 160（高跟預覽比例：4:3→120，16:9→90），全圖掃都係個位數 ms。 */
    public static final int DETECT_W = 160;

    private final CameraController cameraController;
    private final UbxApi ubxApi;

    // ── 可調參數（volatile，HTTP 線程寫、檢測線程讀）──
    private volatile boolean running = false;
    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int trackIntervalMs = DEFAULT_TRACK_INTERVAL_MS;
    private volatile int stepDeg = DEFAULT_STEP_DEG;
    private volatile float deadband = DEFAULT_DEADBAND;
    private volatile int timeMs = DEFAULT_TIME_MS;
    /** 追頭總開關：關＝只偵測＋畫框，頭不動；開＝19/20 齊跟。 */
    private volatile boolean headFollow = true;
    /** 自動間隔（預設開）：掃唔到用 intervalMs（慢），跟到色用 trackIntervalMs（快）；關＝一律慢速。 */
    private volatile boolean autoInterval = true;
    private volatile float hMin = DEFAULT_H_MIN;
    private volatile float hMax = DEFAULT_H_MAX;
    private volatile float sMin = DEFAULT_S_MIN;
    private volatile float sMax = DEFAULT_S_MAX;
    private volatile float vMin = DEFAULT_V_MIN;
    private volatile float vMax = DEFAULT_V_MAX;
    private volatile int minPixels = DEFAULT_MIN_PIXELS;

    // ── 狀態／統計 ──
    private volatile int curPan = PAN_HOME;
    private volatile int curTilt = TILT_HOME;
    private volatile long framesSeen = 0;
    private volatile long framesProcessed = 0;
    private volatile long moves = 0;
    private volatile long lastDetectMs = 0;
    private volatile int lastFound = 0;
    private volatile float lastFx = -1f;
    private volatile float lastFy = -1f;
    private volatile float lastFw = 0f;
    private volatile float lastFh = 0f;
    /** 質心 blob 覆蓋率（blob 像素／全圖像素，0-1，overlay confidence 用）。 */
    private volatile float lastCoverage = 0f;
    private volatile int lastPixels = 0;
    private volatile int lastMatches = 0;
    private volatile long lastMoveMs = 0;
    private volatile int lostStreak = 0;
    /** 上一幀檢測耗時 ms（decode＋HSV＋flood fill）。 */
    private volatile long lastCostMs = 0;
    /** 有無跟緊色（自動間隔用）：命中即 true，掃唔到即 false（唔似人臉 ROI 咁要撐 3 次——全圖掃無跟丟成本）。 */
    private volatile boolean hasTrack = false;

    private HandlerThread detectThread;
    private Handler detectHandler;
    private final Object subLock = new Object();
    private boolean subscribed = false;
    private volatile long lastSampleMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 舵機指令最小間隔 ms（同 FaceTrackCenter 一致，唔 hammer 胸板串口）。 */
    private static final long SERVO_MIN_GAP_MS = 150;
    private volatile long lastServoSendMs = 0;

    private final CameraController.FrameListener sampler = new CameraController.FrameListener() {
        @Override public void onFrame(CameraController.Frame frame) {
            if (!running) return;
            framesSeen++;
            long now = System.currentTimeMillis();
            long eff = effectiveIntervalMs();
            if (now - lastSampleMs < eff) return;
            if (inFlight.get()) return;
            lastSampleMs = now;
            final byte[] jpeg = frame.jpeg;
            Handler h;
            synchronized (subLock) { h = detectHandler; }
            if (h == null) return;
            inFlight.set(true);
            h.post(new Runnable() {
                @Override public void run() {
                    try { detectAndTrack(jpeg); }
                    finally { inFlight.set(false); }
                }
            });
        }
    };

    public ColorTrackCenter(CameraController cameraController, UbxApi ubxApi) {
        this.cameraController = cameraController;
        this.ubxApi = ubxApi;
    }

    // ── 純算法（RGB→HSV／hue 區間／最大 blob）見 ColorTrackLogic（零依賴獨立檔，
    // 直接單元測試；呢度唔重複實現）──
    private void ensureThreadLocked() {
        if (detectThread == null || !detectThread.isAlive()) {
            detectThread = new HandlerThread("ColorTrackThread");
            detectThread.start();
            detectHandler = new Handler(detectThread.getLooper());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** 當前生效間隔（sampler 同 status 共用；自動開＝跟到色用快速，否則用慢速）。 */
    private int effectiveIntervalMs() {
        if (autoInterval && hasTrack) return trackIntervalMs;
        return intervalMs;
    }

    /** 從 query 讀可選追蹤參數並套用（start/config 共用；缺席即保留現值）。 */
    public void applyConfigFromQuery(Map<String, String> query) {
        if (query == null) return;
        intervalMs = ApiValidator.optionalIntRange(query, "intervalMs", 100, 3000, intervalMs);
        trackIntervalMs = ApiValidator.optionalIntRange(query, "trackIntervalMs", 100, 3000, trackIntervalMs);
        stepDeg = ApiValidator.optionalIntRange(query, "stepDeg", 1, 30, stepDeg);
        deadband = ApiValidator.optionalFloatRange(query, "deadband", 0.0, 0.5, deadband);
        timeMs = ApiValidator.optionalIntRange(query, "timeMs", 20, 2000, timeMs);
        hMin = ApiValidator.optionalFloatRange(query, "hMin", 0.0, 360.0, hMin);
        hMax = ApiValidator.optionalFloatRange(query, "hMax", 0.0, 360.0, hMax);
        sMin = ApiValidator.optionalFloatRange(query, "sMin", 0.0, 1.0, sMin);
        sMax = ApiValidator.optionalFloatRange(query, "sMax", 0.0, 1.0, sMax);
        vMin = ApiValidator.optionalFloatRange(query, "vMin", 0.0, 1.0, vMin);
        vMax = ApiValidator.optionalFloatRange(query, "vMax", 0.0, 1.0, vMax);
        minPixels = ApiValidator.optionalIntRange(query, "minPixels", 10, 20000, minPixels);
        if (query.containsKey("headFollow") && query.get("headFollow") != null
                && !query.get("headFollow").isEmpty()) {
            headFollow = ApiValidator.optionalBoolean(query, "headFollow", headFollow);
        }
        if (query.containsKey("autoInterval") && query.get("autoInterval") != null
                && !query.get("autoInterval").isEmpty()) {
            autoInterval = ApiValidator.optionalBoolean(query, "autoInterval", autoInterval);
        }
    }

    /** 啟動追蹤：先確保相機開流，再訂閱抽樣。HTTP worker 線程調用（會 block 開相機）。 */
    public synchronized HttpServer.ApiResponse start(Map<String, String> query) {
        applyConfigFromQuery(query);
        CameraController.StartResult sr = cameraController.start(8000);
        if (sr.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + JsonUtil.esc(sr.error) + "\"}");
        }
        synchronized (subLock) {
            ensureThreadLocked();
            if (!subscribed) {
                cameraController.subscribe(sampler);
                subscribed = true;
            }
            lastSampleMs = 0;
        }
        running = true;
        lostStreak = 0;
        hasTrack = false;
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public synchronized HttpServer.ApiResponse stop() {
        running = false;
        hasTrack = false;
        synchronized (subLock) {
            if (subscribed) {
                try { cameraController.unsubscribe(sampler); } catch (Throwable ignored) {}
                subscribed = false;
            }
        }
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public HttpServer.ApiResponse config(Map<String, String> query) {
        applyConfigFromQuery(query);
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public HttpServer.ApiResponse status() {
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public boolean isRunning() { return running; }

    /** 放開相機訂閱＋停背景線程（MainActivity.onDestroy 用）。 */
    public synchronized void shutdown() {
        running = false;
        synchronized (subLock) {
            if (subscribed) {
                try { cameraController.unsubscribe(sampler); } catch (Throwable ignored) {}
                subscribed = false;
            }
        }
        if (detectThread != null) {
            try { detectThread.quitSafely(); } catch (Throwable ignored) {}
            detectThread = null;
            detectHandler = null;
        }
    }

    private void detectAndTrack(byte[] jpeg) {
        if (!running || jpeg == null || jpeg.length == 0) return;
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            int prevW = cameraController.getPreviewWidth();
            int prevH = cameraController.getPreviewHeight();
            // 偵測輸入：寬鎖 160，高跟預覽比例（4:3→160x120，16:9→160x90），
            // 唔 squash 唔 crop。先 inSampleSize 粗解再精縮，同 FaceTrackCenter 同手法。
            boolean fourThree = Math.abs(prevW * 3 - prevH * 4)
                    <= Math.abs(prevW * 9 - prevH * 16);
            int targetW = DETECT_W;
            int targetH = fourThree ? 120 : 90;
            int sample = 1;
            while ((prevW / (sample * 2)) >= targetW && (prevH / (sample * 2)) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sample;
            try {
                bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            } catch (OutOfMemoryError oom) {
                Log.w(TAG, "decode OOM, skip frame");
                return;
            }
            if (bmp == null) return;
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw != targetW || bh != targetH) {
                Bitmap scaled = null;
                try { scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true); }
                catch (Throwable t) { Log.w(TAG, "scale to detect size failed, skip frame", t); }
                if (scaled != null) {
                    try { bmp.recycle(); } catch (Throwable ignored) {}
                    bmp = scaled;
                    bw = bmp.getWidth();
                    bh = bmp.getHeight();
                }
            }
            int w = bw;
            int h = bh;
            int n = w * h;
            int[] px = new int[n];
            try {
                bmp.getPixels(px, 0, w, 0, 0, w, h);
            } catch (Throwable t) {
                Log.w(TAG, "getPixels failed", t);
                return;
            }
            // 逐像素 HSV 閾值（讀 snapshot 參數，免掃一半被 config 改一半）。
            float qHMin = hMin;
            float qHMax = hMax;
            float qSMin = sMin;
            float qSMax = sMax;
            float qVMin = vMin;
            float qVMax = vMax;
            boolean[] mask = new boolean[n];
            int matches = 0;
            for (int i = 0; i < n; i++) {
                int p = px[i];
                float[] hsv = ColorTrackLogic.rgbToHsv((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                if (ColorTrackLogic.matchHsv(hsv[0], hsv[1], hsv[2], qHMin, qHMax, qSMin, qSMax, qVMin, qVMax)) {
                    mask[i] = true;
                    matches++;
                }
            }
            px = null;
            framesProcessed++;
            lastDetectMs = System.currentTimeMillis();
            lastCostMs = lastDetectMs - t0;
            lastMatches = matches;
            ColorTrackLogic.Blob best = (matches > 0) ? ColorTrackLogic.largestBlob(mask, w, h, minPixels) : null;
            mask = null;
            if (best == null) {
                lastFound = 0;
                hasTrack = false;
                lostStreak++;
                return;
            }
            // 質心＋bbox（歸一化，overlay 同 face 同一套座標）。
            float fx = ((float) best.sumX / best.count + 0.5f) / w;
            float fy = ((float) best.sumY / best.count + 0.5f) / h;
            float fw = (float) (best.x1 - best.x0 + 1) / w;
            float fh = (float) (best.y1 - best.y0 + 1) / h;
            if (fw > 1f) fw = 1f;
            if (fh > 1f) fh = 1f;
            lastFound = 1;
            lastFx = fx;
            lastFy = fy;
            lastFw = fw;
            lastFh = fh;
            lastCoverage = (float) best.count / Math.max(1, n);
            lastPixels = best.count;
            lostStreak = 0;
            hasTrack = true;

            float dx = (fx - 0.5f) * 2f; // -1 左 … +1 右
            float dy = (fy - 0.5f) * 2f; // -1 上 … +1 下
            if (dx < -1f) dx = -1f;
            if (dx > 1f) dx = 1f;
            if (dy < -1f) dy = -1f;
            if (dy > 1f) dy = 1f;

            boolean moved = false;
            int newPan = curPan;
            int newTilt = curTilt;
            float db = deadband;
            // 追頭總開關：關＝只偵測＋畫框，兩軸都不動。
            if (headFollow && Math.abs(dx) > db) {
                float mag = (Math.abs(dx) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dx < 0) delta = -delta;
                newPan = clamp(curPan + delta, PAN_MIN, PAN_MAX);
            }
            if (headFollow && Math.abs(dy) > db) {
                float mag = (Math.abs(dy) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dy < 0) delta = -delta;
                newTilt = clamp(curTilt + delta, TILT_MIN, TILT_MAX);
            }
            // 只有角度真變＋同上次發送隔夠 SERVO_MIN_GAP_MS 先發舵機（同 FaceTrackCenter 一致）。
            long nowMs = System.currentTimeMillis();
            boolean servoDue = nowMs - lastServoSendMs >= SERVO_MIN_GAP_MS;
            if (headFollow && servoDue && newPan != curPan) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(PAN_ID, newPan, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curPan = newPan;
                    moved = true;
                }
            }
            if (headFollow && servoDue && newTilt != curTilt) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(TILT_ID, newTilt, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curTilt = newTilt;
                    moved = true;
                }
            }
            if (moved) {
                moves++;
                lastMoveMs = nowMs;
                lastServoSendMs = nowMs;
            }
        } catch (Throwable t) {
            Log.w(TAG, "detectAndTrack failed", t);
        } finally {
            if (bmp != null) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** color/track/status 回包本體（start/stop/config 亦回同一形狀，前端一次解析；欄位對齊 face 以便共用畫框）。 */
    public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"running\":").append(running).append(",");
        sb.append("\"detector\":\"color-blob-hsv\",");
        sb.append("\"pan\":").append(curPan).append(",\"tilt\":").append(curTilt).append(",");
        sb.append("\"faces\":").append(lastFound).append(",");
        sb.append("\"fx\":").append(fmt3(lastFx)).append(",\"fy\":").append(fmt3(lastFy));
        sb.append(",\"fw\":").append(fmt3(lastFw)).append(",\"fh\":").append(fmt3(lastFh));
        sb.append(",\"confidence\":").append(fmt3(lastCoverage)).append(",");
        sb.append("\"pixels\":").append(lastPixels).append(",");
        sb.append("\"matches\":").append(lastMatches).append(",");
        sb.append("\"effectiveIntervalMs\":").append(effectiveIntervalMs()).append(",");
        sb.append("\"framesSeen\":").append(framesSeen)
                .append(",\"framesProcessed\":").append(framesProcessed)
                .append(",\"moves\":").append(moves)
                .append(",\"lostStreak\":").append(lostStreak)
                .append(",\"lastDetectMs\":").append(lastDetectMs)
                .append(",\"lastCostMs\":").append(lastCostMs)
                .append(",\"lastMoveMs\":").append(lastMoveMs).append(",");
        sb.append("\"config\":{");
        sb.append("\"intervalMs\":").append(intervalMs).append(",");
        sb.append("\"stepDeg\":").append(stepDeg).append(",");
        sb.append("\"deadband\":").append(fmt3(deadband)).append(",");
        sb.append("\"timeMs\":").append(timeMs).append(",");
        sb.append("\"headFollow\":").append(headFollow).append(",");
        sb.append("\"autoInterval\":").append(autoInterval).append(",");
        sb.append("\"trackIntervalMs\":").append(trackIntervalMs).append(",");
        sb.append("\"hMin\":").append(fmt3(hMin)).append(",");
        sb.append("\"hMax\":").append(fmt3(hMax)).append(",");
        sb.append("\"sMin\":").append(fmt3(sMin)).append(",");
        sb.append("\"sMax\":").append(fmt3(sMax)).append(",");
        sb.append("\"vMin\":").append(fmt3(vMin)).append(",");
        sb.append("\"vMax\":").append(fmt3(vMax)).append(",");
        sb.append("\"minPixels\":").append(minPixels);
        sb.append("},");
        sb.append("\"servo\":{\"panId\":").append(PAN_ID)
                .append(",\"tiltId\":").append(TILT_ID)
                .append(",\"panRange\":[").append(PAN_MIN).append(",").append(PAN_MAX).append("]")
                .append(",\"tiltRange\":[").append(TILT_MIN).append(",").append(TILT_MAX).append("]")
                .append(",\"streaming\":").append(cameraController.isStreaming());
        sb.append("}}");
        return sb.toString();
    }
}
