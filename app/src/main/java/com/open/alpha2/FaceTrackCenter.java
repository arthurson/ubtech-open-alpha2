package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * Android 內置人臉追蹤：零額外依賴，只用 framework 自帶的
 * {@code android.media.FaceDetector}（API 1 起就有，API 19/22 安全，
 * 不用 Play Services、不下載模型，符合本專案 offline／framework-only 政策）。
 *
 * 鏈路：{@link CameraController} 連續預覽 JPEG 幀 → 本類按 intervalMs 抽樣 →
 * 背景線程 downscale 解碼（4:3 預覽 → 320x240，16:9 → 320x180，RGB_565，
 * FaceDetector 硬性要求 RGB_565 且寬為偶數）→ findFaces 取最大一張 → 歸一化偏移
 * dx/dy → 死區＋比例步進驅動頭部 yaw(19)/pitch(20) 跟人（cmd05 單舵機，經 {@link UbxApi}，
 * 位姿追踪照記，天然不干擾其餘 19 軸）。
 *
 * ROI 跟隨（roiEnabled，預設開）：全圖命中一次後，之後只裁上次臉框 2 倍大細框
 * （≥96px、唔夠全圖 8 成先裁）直接檢，唔放大像素——舊實測放大檢 recall 跌到 2%，
 * 直接檢先保 recall。ROI 單幀 ~20-30ms，320x240 長開 10fps 靠佢；連續 3 次掃唔到
 * 即回全圖。舵機指令另有 150ms 最小間隔，10fps 下唔 hammer 胸板串口。
 *
 * 安全／性能要點：
 * - 預設 600ms 檢一次（RK3288 上 320x180 FaceDetector 約 50-150ms），相機線程只做
 *   節流判斷＋post，絕不阻塞（見 CameraController.onPreviewFrame 註解）。
 * - 每次最多走一步 stepDeg（預設 5°），並 clamp 到前端同一份校準表
 *   （19: 75-165，20: 105-155，home 120，見 app-core.js SERVO_CALIBRATION），
 *   不會頂機械限位；播動作（UbxPlayer）時請先停追蹤，兩邊同搶 19/20 會打架。
 *   實機已驗證方向無誤，故不設反轉開關。
 * - 人臉 WS log 已 mute（實機要求）：一律唔經 EventBus，只留 HTTP status 輪詢＋前端 overlay；
 *   狀態經 face/track/status 輪詢。
 */
public final class FaceTrackCenter {
    private static final String TAG = "FaceTrackCenter";

    /** 頭部舵機：19 yaw（左右）、20 pitch（上下），範圍與前端校準表一致。 */
    public static final int PAN_ID = 19;
    public static final int TILT_ID = 20;
    public static final int PAN_MIN = 75;
    public static final int PAN_MAX = 165;
    public static final int PAN_HOME = 120;
    public static final int TILT_MIN = 105;
    public static final int TILT_MAX = 155;
    public static final int TILT_HOME = 120;

    public static final int DEFAULT_INTERVAL_MS = 500;
    public static final int DEFAULT_STEP_DEG = 17;
    public static final float DEFAULT_DEADBAND = 0.10f;
    public static final int DEFAULT_TIME_MS = 400;
    public static final int DEFAULT_MAX_FACES = 3;

    private final CameraController cameraController;
    private final UbxApi ubxApi;

    // ── 可調參數（volatile，HTTP 線程寫、檢測線程讀）──
    private volatile boolean running = false;
    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int stepDeg = DEFAULT_STEP_DEG;
    private volatile float deadband = DEFAULT_DEADBAND;
    private volatile int timeMs = DEFAULT_TIME_MS;
    private volatile int maxFaces = DEFAULT_MAX_FACES;
    /** 追頭總開關（實機要求：取代舊 pan/tilt 分軸開關）：關＝只偵測＋畫框，頭不動；開＝19/20 齊跟。 */
    private volatile boolean headFollow = true;
    /** ROI 跟隨開關：開＝檢到一次後只掃塊面附近細框（單幀 ~20-30ms，320x240 長開 10fps 用）；
     *  關＝每幀全圖掃（慢但穩）。連續掃唔到超限即回全圖，見 ROI_MISS_LIMIT。 */
    private volatile boolean roiEnabled = true;
    /** 自動間隔（預設開）：無臉／全圖搜用 intervalMs（慢，慳 CPU），ROI 跟到臉用
     *  trackIntervalMs（快，跟得貼）；關＝一律用 intervalMs（舊手動行為）。 */
    private volatile boolean autoInterval = true;
    private volatile int trackIntervalMs = 100;

    // ── 狀態／統計 ──
    private volatile int curPan = PAN_HOME;
    private volatile int curTilt = TILT_HOME;
    private volatile long framesSeen = 0;
    private volatile long framesProcessed = 0;
    private volatile long moves = 0;
    private volatile long lastDetectMs = 0;
    private volatile int lastFaces = 0;
    private volatile float lastFx = -1f;
    private volatile float lastFy = -1f;
    private volatile float lastFw = 0f;
    private volatile float lastFh = 0f;
    private volatile float lastConfidence = 0f;
    private volatile long lastMoveMs = 0;
    private volatile int lostStreak = 0;
    /** 上一幀檢測耗時 ms（decode＋detect；調 fps 上限用嘅實測依據）。 */
    private volatile long lastCostMs = 0;

    private HandlerThread detectThread;
    private Handler detectHandler;
    private final Object subLock = new Object();
    private boolean subscribed = false;
    private volatile long lastSampleMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** FaceDetector 按（寬，高，cap）快取：native 初始化唔平，唔好每幀 new。
     *  只經檢測 worker 線程讀寫，天然單線程，唔使同步。 */
    private FaceDetector cachedDetector;
    private int cachedW = -1;
    private int cachedH = -1;
    private int cachedCap = -1;
    /** ROI 專用第二快取（ROI 尺寸隨塊面郁而變，同主快取分開免互相踢走）。 */
    private FaceDetector cachedRoiDetector;
    private int cachedRoiW = -1;
    private int cachedRoiH = -1;
    // ── ROI 跟隨狀態（roiHasTrack 會被相機線程讀（自動間隔用），其餘只經 worker 線程讀寫）──
    /** 上次全圖命中嘅臉框（detect 圖歸一化），ROI 裁框基準。 */
    private volatile boolean roiHasTrack = false;
    private float roiFx = 0.5f;
    private float roiFy = 0.5f;
    private float roiFw = 0.3f;
    private float roiFh = 0.4f;
    private int roiMiss = 0;
    /** 本幀係咪行緊 ROI（event／status 照報，調參可見）。 */
    private volatile boolean roiTracking = false;
    /** 連續幾次 ROI 掃唔到就退回全圖（塊面可能走出框）。 */
    private static final int ROI_MISS_LIMIT = 3;
    /** ROI 框＝上次臉框放大幾多倍（直接檢，唔放大像素——舊實測放大檢 recall 跌到 2%）。 */
    private static final float ROI_EXPAND = 2.0f;
    /** ROI 細過呢個就唔裁（遠距離細面要全圖上下文，直接行全圖）。 */
    private static final int ROI_MIN_PX = 96;
    /** ROI 大到接近全圖就唔裁（慳唔到幾多，直接行全圖）。 */
    private static final float ROI_MAX_FRACTION = 0.8f;
    /** 舵機指令最小間隔 ms：10fps 下唔好每幀 hammer 胸板串口（見 UbxApi 連讀間隔註解）。 */
    private static final long SERVO_MIN_GAP_MS = 150;
    private volatile long lastServoSendMs = 0;

    private final CameraController.FrameListener sampler = new CameraController.FrameListener() {
        @Override public void onFrame(CameraController.Frame frame) {
            if (!running) return;
            framesSeen++;
            long now = System.currentTimeMillis();
            // 節流：相機線程只做時間判斷，檢測本體 post 去背景線程。
            // 舊 FaceDetectCenter 實機教訓：RK3288 持續高負荷會系統層重啟（無 tombstone），
            // 故無臉／全圖搜用慢速 intervalMs，ROI 跟到臉先用快速 trackIntervalMs
            // （autoInterval 關即一律慢速）；inFlight 防止檢測慢過間隔時 post 堆積。
            // （inner class 直讀 outer volatile／private 方法，合法。）
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
                    try { detectAndFollow(jpeg); }
                    finally { inFlight.set(false); }
                }
            });
        }
    };

    public FaceTrackCenter(CameraController cameraController, UbxApi ubxApi) {
        this.cameraController = cameraController;
        this.ubxApi = ubxApi;
    }

    private void ensureThreadLocked() {
        if (detectThread == null || !detectThread.isAlive()) {
            detectThread = new HandlerThread("FaceTrackThread");
            detectThread.start();
            detectHandler = new Handler(detectThread.getLooper());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** 當前生效間隔（sampler 同 status 共用；自動開＝跟到臉用快速，否則用慢速）。 */
    private int effectiveIntervalMs() {
        if (autoInterval && roiHasTrack) return trackIntervalMs;
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
        maxFaces = ApiValidator.optionalIntRange(query, "maxFaces", 1, 5, maxFaces);
        if (query.containsKey("headFollow") && query.get("headFollow") != null
                && !query.get("headFollow").isEmpty()) {
            headFollow = ApiValidator.optionalBoolean(query, "headFollow", headFollow);
        }
        if (query.containsKey("roiEnabled") && query.get("roiEnabled") != null
                && !query.get("roiEnabled").isEmpty()) {
            roiEnabled = ApiValidator.optionalBoolean(query, "roiEnabled", roiEnabled);
            if (!roiEnabled) { roiHasTrack = false; roiMiss = 0; roiTracking = false; }
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
        roiHasTrack = false;
        roiMiss = 0;
        roiTracking = false;
        // 人臉 WS log 已 mute（實機要求）：啟停／每幀一律唔經 EventBus，只留 HTTP status 輪詢。
        return HttpServer.ApiResponse.ok(statusJson());
    }

    public synchronized HttpServer.ApiResponse stop() {
        running = false;
        roiTracking = false;
        roiHasTrack = false;
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

    /** 硬件／軟件人臉偵測能力探測（只讀參數，不開追蹤迴圈）。
     *  hardwareMaxFaces>0 ＝ HAL 支持 Camera.startFaceDetection（零 CPU 全速路，可做 10+fps）；
     *  softwareAvailable ＝ framework FaceDetector 可 instantiate（現行軟件路）。 */
    public HttpServer.ApiResponse probe() {
        int hw = -1;
        try { hw = cameraController.getMaxNumDetectedFacesSync(4000); }
        catch (Throwable t) { Log.w(TAG, "hw probe failed", t); }
        boolean sw = false;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
            FaceDetector fd = new FaceDetector(320, 240, 1);
            sw = (fd != null);
        } catch (Throwable t) { sw = false; }
        finally {
            if (bmp != null) { try { bmp.recycle(); } catch (Throwable ignored) {} }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"hardwareMaxFaces\":").append(hw).append(",");
        sb.append("\"hardwareSupported\":").append(hw > 0).append(",");
        sb.append("\"softwareAvailable\":").append(sw).append(",");
        sb.append("\"detector\":\"android.media.FaceDetector\",");
        sb.append("\"previewWidth\":").append(cameraController.getPreviewWidth()).append(",");
        sb.append("\"previewHeight\":").append(cameraController.getPreviewHeight());
        sb.append("}");
        return HttpServer.ApiResponse.ok(sb.toString());
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

    private void detectAndFollow(byte[] jpeg) {
        if (!running || jpeg == null || jpeg.length == 0) return;
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            int prevW = cameraController.getPreviewWidth();
            int prevH = cameraController.getPreviewHeight();
            // 偵測輸入尺寸：寬鎖 320（舊實測上限，大過 recall 跌到 0%），高跟預覽比例——
            // 4:3（800x600 等）→ 320x240，16:9（1280x720 等）→ 320x180，唔 squash、
            // 唔 crop，塊面不變形。先 inSampleSize 粗解（2 的冪，解完 ≥目標即停）再
            // createScaledBitmap 精縮到目標，一步到位。
            boolean fourThree = Math.abs(prevW * 3 - prevH * 4)
                    <= Math.abs(prevW * 9 - prevH * 16);
            int targetW = 320;
            int targetH = fourThree ? 240 : 180;
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
            // FaceDetector 硬性要求 RGB_565。
            if (bmp.getConfig() != Bitmap.Config.RGB_565) {
                Bitmap converted = null;
                try { converted = bmp.copy(Bitmap.Config.RGB_565, false); }
                catch (Throwable t) { Log.w(TAG, "RGB_565 convert failed", t); }
                bmp.recycle();
                bmp = converted;
                if (bmp == null) return;
            }
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            // 精縮到目標尺寸（順手保證偶數寬，FaceDetector 硬性要求）。
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
            // 寬須偶數（奇數裁 1px，免 findFaces 拋錯；正常經上面精縮已係偶數，呢度純 failsafe）。
            if ((bw & 1) == 1 && bw > 2) {
                Bitmap cropped = null;
                try { cropped = Bitmap.createBitmap(bmp, 0, 0, bw - 1, bh); }
                catch (Throwable t) { Log.w(TAG, "even-crop failed", t); }
                if (cropped != null) {
                    bmp.recycle();
                    bmp = cropped;
                    bw = bmp.getWidth();
                    bh = bmp.getHeight();
                }
            }
            // ── ROI 裁框：有上次臉框＋開關開，先算 ROI 矩形 ──
            Bitmap work = bmp;
            boolean inRoi = false;
            int roiLeft = 0;
            int roiTop = 0;
            int roiW = bw;
            int roiH = bh;
            if (roiEnabled && roiHasTrack) {
                float cx = roiFx * bw;
                float cy = roiFy * bh;
                float rw = Math.max(roiFw * bw * ROI_EXPAND, ROI_MIN_PX);
                float rh = Math.max(roiFh * bh * ROI_EXPAND, ROI_MIN_PX);
                if (rw * rh < bw * bh * ROI_MAX_FRACTION) {
                    int left = Math.round(cx - rw / 2);
                    int top = Math.round(cy - rh / 2);
                    int right = Math.round(cx + rw / 2);
                    int bottom = Math.round(cy + rh / 2);
                    if (left < 0) { right -= left; left = 0; }
                    if (top < 0) { bottom -= top; top = 0; }
                    if (right > bw) { left -= right - bw; right = bw; }
                    if (bottom > bh) { top -= bottom - bh; bottom = bh; }
                    if (left < 0) left = 0;
                    if (top < 0) top = 0;
                    int cw = right - left;
                    int ch = bottom - top;
                    if (cw >= ROI_MIN_PX && ch >= ROI_MIN_PX && cw > 0 && ch > 0) {
                        // 偶數對齊（FaceDetector 寬要求偶數）。
                        if ((cw & 1) == 1) { if (left + cw < bw) cw++; else { left++; cw--; } }
                        if (cw >= ROI_MIN_PX && ch >= ROI_MIN_PX && left + cw <= bw && top + ch <= bh) {
                            Bitmap cropped = null;
                            try { cropped = Bitmap.createBitmap(bmp, left, top, cw, ch); }
                            catch (Throwable t) { Log.w(TAG, "roi crop failed", t); }
                            if (cropped != null) {
                                work = cropped;
                                inRoi = true;
                                roiLeft = left;
                                roiTop = top;
                                roiW = cw;
                                roiH = ch;
                            }
                        }
                    }
                }
            }
            roiTracking = inRoi;
            int ww = work.getWidth();
            int wh = work.getHeight();
            // ROI 只跟一張（追緊嗰張），cap=1 更快；全圖先用 maxFaces。
            int cap = inRoi ? 1 : Math.max(1, Math.min(5, maxFaces));
            FaceDetector fd;
            if (inRoi) {
                if (cachedRoiDetector == null || ww != cachedRoiW || wh != cachedRoiH) {
                    try {
                        cachedRoiDetector = new FaceDetector(ww, wh, 1);
                    } catch (Throwable t) {
                        Log.w(TAG, "ROI FaceDetector ctor failed", t);
                        try { work.recycle(); } catch (Throwable ignored) {}
                        return;
                    }
                    cachedRoiW = ww;
                    cachedRoiH = wh;
                }
                fd = cachedRoiDetector;
            } else {
                if (cachedDetector == null || bw != cachedW || bh != cachedH || cap != cachedCap) {
                    try {
                        cachedDetector = new FaceDetector(bw, bh, cap);
                    } catch (Throwable t) {
                        Log.w(TAG, "FaceDetector ctor failed", t);
                        return;
                    }
                    cachedW = bw;
                    cachedH = bh;
                    cachedCap = cap;
                }
                fd = cachedDetector;
            }
            FaceDetector.Face[] faces = new FaceDetector.Face[cap];
            int found;
            try {
                found = fd.findFaces(work, faces);
            } catch (Throwable t) {
                Log.w(TAG, "findFaces failed", t);
                if (inRoi) { try { work.recycle(); } catch (Throwable ignored) {} }
                return;
            }
            // ROI 框要回收（全圖嗰張 bmp 留返 finally 收）。
            if (inRoi) { try { work.recycle(); } catch (Throwable ignored) {} }
            framesProcessed++;
            lastDetectMs = System.currentTimeMillis();
            lastCostMs = lastDetectMs - t0;
            if (found <= 0) {
                lastFaces = 0;
                lostStreak++;
                if (inRoi) {
                    roiMiss++;
                    if (roiMiss >= ROI_MISS_LIMIT) { roiHasTrack = false; roiMiss = 0; }
                }
                return;
            }
            // 取眼睛距離最大（最大張）且 confidence 較高者。
            FaceDetector.Face best = null;
            float bestScore = -1f;
            for (int i = 0; i < found; i++) {
                FaceDetector.Face f = faces[i];
                if (f == null) continue;
                float score = f.eyesDistance() * (0.5f + f.confidence());
                if (score > bestScore) { bestScore = score; best = f; }
            }
            if (best == null) {
                lastFaces = 0;
                lostStreak++;
                return;
            }
            PointF mid = new PointF();
            best.getMidPoint(mid);
            float eyes = best.eyesDistance();
            float conf = best.confidence();
            // 過濾極低 confidence 誤檢（FaceDetector confidence 約 0-1）。
            if (conf < 0.15f) {
                lastFaces = 0;
                lostStreak++;
                if (inRoi) {
                    roiMiss++;
                    if (roiMiss >= ROI_MISS_LIMIT) { roiHasTrack = false; roiMiss = 0; }
                }
                return;
            }
            // ROI 座標映射返全圖（detect 圖像素；eyes 本身已經係像素，無需縮放——裁框唔放大）。
            float fullMx = inRoi ? roiLeft + mid.x : mid.x;
            float fullMy = inRoi ? roiTop + mid.y : mid.y;
            float fx = fullMx / Math.max(1, bw);
            float fy = fullMy / Math.max(1, bh);
            // 人臉框估算：寬 ≈ eyes*2.2，高等比放大（overlay 用，歸一化）。
            float fw = (eyes * 2.2f) / Math.max(1, bw);
            float fh = fw * bw / Math.max(1, bh) * 1.25f;
            if (fw > 1f) fw = 1f;
            if (fh > 1f) fh = 1f;
            lastFaces = found;
            lastFx = fx;
            lastFy = fy;
            lastFw = fw;
            lastFh = fh;
            lastConfidence = conf;
            lostStreak = 0;
            // ROI 軌更新：命中即以今次框為下次基準（跟得贴）；掃唔到嗰陣上面已累積 roiMiss。
            roiHasTrack = true;
            roiMiss = 0;
            roiFx = Math.max(0f, Math.min(1f, fx));
            roiFy = Math.max(0f, Math.min(1f, fy));
            roiFw = Math.max(0.05f, Math.min(1f, fw));
            roiFh = Math.max(0.05f, Math.min(1f, fh));

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
            // 追頭總開關：關＝只偵測＋畫框，兩軸都不動（調試／展示用人臉框時用）。
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
            // 只有角度真變＋同上次發送隔夠 SERVO_MIN_GAP_MS 先發舵機：
            // 10fps 下唔好每幀 hammer 胸板串口（見 UbxApi 連讀間隔註解）。
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
            Log.w(TAG, "detectAndFollow failed", t);
        } finally {
            if (bmp != null) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** face/track/status 回包本體（start/stop/config 亦回同一形狀，前端一次解析）。 */
    public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"running\":").append(running).append(",");
        sb.append("\"detector\":\"android.media.FaceDetector\",");
        sb.append("\"pan\":").append(curPan).append(",\"tilt\":").append(curTilt).append(",");
        sb.append("\"faces\":").append(lastFaces).append(",");
        sb.append("\"fx\":").append(fmt3(lastFx)).append(",\"fy\":").append(fmt3(lastFy));
        sb.append(",\"fw\":").append(fmt3(lastFw)).append(",\"fh\":").append(fmt3(lastFh));
        sb.append(",\"confidence\":").append(fmt3(lastConfidence)).append(",");
        sb.append("\"roiTracking\":").append(roiTracking).append(",");
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
        sb.append("\"maxFaces\":").append(maxFaces).append(",");
        sb.append("\"headFollow\":").append(headFollow).append(",");
        sb.append("\"roiEnabled\":").append(roiEnabled).append(",");
        sb.append("\"autoInterval\":").append(autoInterval).append(",");
        sb.append("\"trackIntervalMs\":").append(trackIntervalMs);
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
