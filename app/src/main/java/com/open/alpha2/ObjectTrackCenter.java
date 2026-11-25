package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * 純 Java 物件追蹤：零額外依賴（唔用 TFLite／OpenCV／ML Kit）。
 *
 * 同顏色追蹤（認「顏色」）、人臉追蹤（認「人臉」）嘅分別：呢個認「用戶揀嘅任意物件」。
 * 鏈路：{@link CameraController} 連續預覽 JPEG 幀 → 按 intervalMs 抽樣 → 背景線程
 * downscale 到 160x120（16:9 預覽 → 160x90）→ 灰階 → {@link ObjectTrackLogic.Tracker}
 * 模板匹配（ZSAD，3 尺度，跟丟後全圖重搵）→ 框中心／大小 → 歸一化偏移 dx/dy →
 * 交 {@link WalkFollower}（同顏色／人臉共用）驅動頭部 yaw(19)/pitch(20)，可選行路跟。
 *
 * 揀目標：start／config 帶 bx,by,bw,bh（歸一化 0-1、左上＋大小）＝喺預覽畫面框一個框，
 * 下一個偵測幀即以該框內容做模板。start 唔帶框＝以畫面正中一個框做目標（將物件對正鏡頭
 * 再開）。追蹤中再發 config 帶框＝重新揀目標。
 *
 * 行路跟：coverage＝框面積／全圖（物件愈近愈大），同顏色追蹤一樣用夠近覆蓋率／橫移
 * 最低覆蓋率；決策與 gait 全部喺 {@link WalkFollower}。
 *
 * 互斥：同人臉／顏色追蹤爭同一對頭舵機，互斥放 {@link CameraApi} 層（start 一邊即停
 * 另外兩邊），本類唔識對方存在。status 形狀對齊 face／color
 * （running／faces／fx/fy/fw/fh／confidence／effectiveIntervalMs／config／servo），
 * 前端同一個畫框邏輯食得落。
 */
public final class ObjectTrackCenter {
    private static final String TAG = "ObjectTrackCenter";

    public static final int PAN_ID = WalkFollower.PAN_ID;
    public static final int TILT_ID = WalkFollower.TILT_ID;
    public static final int PAN_MIN = WalkFollower.PAN_MIN;
    public static final int PAN_MAX = WalkFollower.PAN_MAX;
    public static final int TILT_MIN = WalkFollower.TILT_MIN;
    public static final int TILT_MAX = WalkFollower.TILT_MAX;

    public static final int DEFAULT_INTERVAL_MS = 200;
    public static final int DEFAULT_TRACK_INTERVAL_MS = 100;
    public static final int DEFAULT_STEP_DEG = ColorTrackCenter.DEFAULT_STEP_DEG;
    public static final float DEFAULT_DEADBAND = ColorTrackCenter.DEFAULT_DEADBAND;
    public static final int DEFAULT_TIME_MS = ColorTrackCenter.DEFAULT_TIME_MS;
    /** 模板匹配接受門檻（ZSAD 平均絕對差／255；細＝嚴）。 */
    public static final float DEFAULT_MATCH_THRESHOLD = 0.14f;
    public static final float DEFAULT_CLOSE_COVERAGE = ColorTrackCenter.DEFAULT_CLOSE_COVERAGE;
    public static final float DEFAULT_SIDE_MIN_COVERAGE = ColorTrackCenter.DEFAULT_SIDE_MIN_COVERAGE;
    /** 預設目標框（畫面正中）：寬 25%、高 30%。 */
    public static final float DEFAULT_BOX_W = 0.25f;
    public static final float DEFAULT_BOX_H = 0.30f;
    /** 紋理量低過呢個（0-255 平均絕對偏差）當純色／白牆，跟唔穩，status.lowTexture 提示。 */
    public static final float LOW_TEXTURE = 6f;
    public static final int DETECT_W = 160;

    private final CameraController cameraController;
    private final UbxApi ubxApi;
    private final WalkFollower follower;

    private volatile boolean running = false;
    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int trackIntervalMs = DEFAULT_TRACK_INTERVAL_MS;
    private volatile int stepDeg = DEFAULT_STEP_DEG;
    private volatile float deadband = DEFAULT_DEADBAND;
    private volatile int timeMs = DEFAULT_TIME_MS;
    private volatile boolean headFollow = true;
    private volatile boolean autoInterval = true;
    private volatile float matchThreshold = DEFAULT_MATCH_THRESHOLD;
    private volatile boolean adapt = true;

    /** 待套用嘅目標框（HTTP 線程寫、偵測線程讀走並清空）：{x,y,w,h} 歸一化，null＝冇。 */
    private volatile float[] pendingBox = null;

    // ── 狀態／統計 ──
    private volatile long framesSeen = 0;
    private volatile long framesProcessed = 0;
    private volatile long lastDetectMs = 0;
    private volatile int lastFound = 0;
    private volatile float lastFx = -1f;
    private volatile float lastFy = -1f;
    private volatile float lastFw = 0f;
    private volatile float lastFh = 0f;
    private volatile float lastScore = 1f;
    private volatile float lastConfidence = 0f;
    private volatile int lostStreak = 0;
    private volatile long lastCostMs = 0;
    private volatile boolean hasTrack = false;
    private volatile boolean selected = false;
    private volatile boolean reacquired = false;
    private volatile float texture = 0f;
    private volatile String lastError = "";
    private volatile long selectCount = 0;

    // detect 線程獨佔緩衝
    private int[] pxBuf = new int[0];
    private int[] grayBuf = new int[0];
    private final ObjectTrackLogic.Tracker tracker = new ObjectTrackLogic.Tracker();

    private HandlerThread detectThread;
    private Handler detectHandler;
    private final Object subLock = new Object();
    private boolean subscribed = false;
    private volatile long lastSampleMs = 0;
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    public ObjectTrackCenter(CameraController cameraController, UbxApi ubxApi) {
        this.cameraController = cameraController;
        this.ubxApi = ubxApi;
        this.follower = new WalkFollower(ubxApi, DEFAULT_CLOSE_COVERAGE, DEFAULT_SIDE_MIN_COVERAGE);
    }

    public void setActionDirect(ActionDirect actionDirect) { follower.setActionDirect(actionDirect); }

    public void setWalkCenter(WalkCenter walkCenter) { follower.setWalkCenter(walkCenter); }

    public void setFallMonitor(FallMonitor fallMonitor) { follower.setFallMonitor(fallMonitor); }

    private void ensureThreadLocked() {
        if (detectThread == null || !detectThread.isAlive()) {
            detectThread = new HandlerThread("ObjectTrackThread");
            detectThread.start();
            detectHandler = new Handler(detectThread.getLooper());
        }
    }

    private int effectiveIntervalMs() {
        int base = (autoInterval && hasTrack) ? trackIntervalMs : intervalMs;
        return follower.clampIntervalMs(base);
    }

    private static boolean has(Map<String, String> q, String k) {
        String v = q.get(k);
        return v != null && !v.isEmpty();
    }

    /** 從 query 讀可選參數並套用（start/config 共用；缺席即保留現值）。 */
    public void applyConfigFromQuery(Map<String, String> query) {
        if (query == null) return;
        // 先全部驗證落局部變量（非法即拋，現值唔動）→ follower 自己亦係原子 → 最後先賦值。
        final int nIntervalMs = ApiValidator.optionalIntRange(query, "intervalMs", 100, 3000, intervalMs);
        final int nTrackIntervalMs = ApiValidator.optionalIntRange(query, "trackIntervalMs", 100, 3000, trackIntervalMs);
        final int nStepDeg = ApiValidator.optionalIntRange(query, "stepDeg", 1, 30, stepDeg);
        final float nDeadband = ApiValidator.optionalFloatRange(query, "deadband", 0.0, 0.5, deadband);
        final int nTimeMs = ApiValidator.optionalIntRange(query, "timeMs", 20, 2000, timeMs);
        final float nMatchThreshold = ApiValidator.optionalFloatRange(query, "matchThreshold", 0.02, 0.4, matchThreshold);
        final boolean nHeadFollow = has(query, "headFollow")
                ? ApiValidator.optionalBoolean(query, "headFollow", headFollow) : headFollow;
        final boolean nAutoInterval = has(query, "autoInterval")
                ? ApiValidator.optionalBoolean(query, "autoInterval", autoInterval) : autoInterval;
        final boolean nAdapt = has(query, "adapt")
                ? ApiValidator.optionalBoolean(query, "adapt", adapt) : adapt;
        // 目標框：四個齊先算（左上 bx,by＋大小 bw,bh，歸一化）。
        float[] nBox = null;
        if (has(query, "bx") || has(query, "by") || has(query, "bw") || has(query, "bh")) {
            if (!(has(query, "bx") && has(query, "by") && has(query, "bw") && has(query, "bh"))) {
                throw new IllegalArgumentException("bx, by, bw, bh must be given together");
            }
            nBox = new float[] {
                    ApiValidator.optionalFloatRange(query, "bx", 0.0, 1.0, 0f),
                    ApiValidator.optionalFloatRange(query, "by", 0.0, 1.0, 0f),
                    ApiValidator.optionalFloatRange(query, "bw", 0.02, 1.0, 0.1f),
                    ApiValidator.optionalFloatRange(query, "bh", 0.02, 1.0, 0.1f) };
        }
        follower.applyConfig(query); // 本身原子
        intervalMs = nIntervalMs;
        trackIntervalMs = nTrackIntervalMs;
        stepDeg = nStepDeg;
        deadband = nDeadband;
        timeMs = nTimeMs;
        matchThreshold = nMatchThreshold;
        headFollow = nHeadFollow;
        autoInterval = nAutoInterval;
        adapt = nAdapt;
        if (nBox != null) pendingBox = nBox;
    }

    public synchronized HttpServer.ApiResponse start(Map<String, String> query) {
        applyConfigFromQuery(query);
        if (pendingBox == null) {
            // 冇框＝以畫面正中做目標（將物件對正鏡頭再開）。
            pendingBox = new float[] {
                    (1f - DEFAULT_BOX_W) / 2f, (1f - DEFAULT_BOX_H) / 2f,
                    DEFAULT_BOX_W, DEFAULT_BOX_H };
        }
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
        follower.onStart();
        selected = false;
        reacquired = false;
        lastError = "";
        lastFound = 0;
        lastConfidence = 0f;
        lostStreak = 0;
        hasTrack = false;
        running = true;
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
        pendingBox = null;
        follower.onStop();
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

    public synchronized void shutdown() {
        running = false;
        follower.onShutdown();
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
            if (bmp.getWidth() != targetW || bmp.getHeight() != targetH) {
                Bitmap scaled = null;
                try { scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true); }
                catch (Throwable t) { Log.w(TAG, "scale to detect size failed, skip frame", t); }
                if (scaled != null) {
                    try { bmp.recycle(); } catch (Throwable ignored) {}
                    bmp = scaled;
                }
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int n = w * h;
            if (pxBuf.length < n) {
                pxBuf = new int[n];
                grayBuf = new int[n];
            }
            try {
                bmp.getPixels(pxBuf, 0, w, 0, 0, w, h);
            } catch (Throwable t) {
                Log.w(TAG, "getPixels failed", t);
                return;
            }
            ObjectTrackLogic.toGray(pxBuf, n, grayBuf);
            framesProcessed++;

            // 有新框：以今幀該框內容做模板（init 同幀唔再 update，位置即框中心）。
            float[] box = pendingBox;
            if (box != null) {
                pendingBox = null;
                if (tracker.init(grayBuf, w, h, box[0], box[1], box[2], box[3])) {
                    selected = true;
                    selectCount++;
                    lastError = "";
                    texture = tracker.texture();
                    reacquired = false;
                    lostStreak = 0;
                    publishFound(box[0] + box[2] / 2f, box[1] + box[3] / 2f,
                            box[2], box[3], 0f, maxScoreNow());
                    lastCostMs = System.currentTimeMillis() - t0;
                    lastDetectMs = System.currentTimeMillis();
                    return;
                } else {
                    lastError = "box too small or empty";
                    if (!tracker.hasTarget()) {
                        selected = false;
                        lastFound = 0;
                        hasTrack = false;
                    }
                }
            }
            lastDetectMs = System.currentTimeMillis();
            if (!tracker.hasTarget()) {
                lastCostMs = lastDetectMs - t0;
                lastFound = 0;
                hasTrack = false;
                actuate(false, 0f, 0f, 0f);
                return;
            }
            float thr = matchThreshold;
            ObjectTrackLogic.Result r = tracker.update(grayBuf, w, h, thr, adapt);
            lastCostMs = System.currentTimeMillis() - t0;
            if (!r.found) {
                lastFound = 0;
                hasTrack = false;
                lostStreak++;
                lastScore = 1f;
                lastConfidence = 0f;
                actuate(false, 0f, 0f, 0f);
                return;
            }
            lostStreak = 0;
            reacquired = r.reacquired;
            publishFound(r.cx, r.cy, r.w, r.h, r.score, thr);
        } catch (Throwable t) {
            Log.w(TAG, "detectAndTrack failed", t);
        } finally {
            if (bmp != null) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private float maxScoreNow() { return matchThreshold; }

    /** 記低跟到嘅框＋驅動執行層（init 同幀／update 命中共用）。 */
    private void publishFound(float cx, float cy, float bw, float bh, float score, float thr) {
        lastFound = 1;
        lastFx = cx;
        lastFy = cy;
        lastFw = bw;
        lastFh = bh;
        lastScore = score;
        float c = 1f - (thr > 0f ? score / thr : 1f);
        lastConfidence = c < 0f ? 0f : (c > 1f ? 1f : c);
        hasTrack = true;
        float dx = (cx - 0.5f) * 2f;
        float dy = (cy - 0.5f) * 2f;
        if (dx < -1f) dx = -1f;
        if (dx > 1f) dx = 1f;
        if (dy < -1f) dy = -1f;
        if (dy > 1f) dy = 1f;
        actuate(true, dx, dy, bw * bh);
    }

    private void actuate(boolean found, float dx, float dy, float coverage) {
        follower.update(found, dx, dy, found ? coverage : 0f, headFollow, deadband, stepDeg, timeMs);
    }

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** object/track/status 回包本體（start/stop/config 亦回同一形狀；欄位對齊 face／color 以共用畫框）。 */
    public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"running\":").append(running).append(",");
        sb.append("\"detector\":\"object-template-zsad\",");
        sb.append("\"pan\":").append(follower.curPan()).append(",\"tilt\":").append(follower.curTilt()).append(",");
        sb.append("\"faces\":").append(lastFound).append(",");
        sb.append("\"fx\":").append(fmt3(lastFx)).append(",\"fy\":").append(fmt3(lastFy));
        sb.append(",\"fw\":").append(fmt3(lastFw)).append(",\"fh\":").append(fmt3(lastFh));
        sb.append(",\"confidence\":").append(fmt3(lastConfidence)).append(",");
        sb.append("\"score\":").append(fmt3(lastScore)).append(",");
        sb.append("\"selected\":").append(selected).append(",");
        sb.append("\"selectCount\":").append(selectCount).append(",");
        sb.append("\"reacquired\":").append(reacquired).append(",");
        sb.append("\"texture\":").append(fmt3(texture)).append(",");
        sb.append("\"lowTexture\":").append(selected && texture < LOW_TEXTURE).append(",");
        sb.append("\"error\":\"").append(JsonUtil.esc(lastError)).append("\",");
        follower.appendStatusJson(sb);
        sb.append("\"effectiveIntervalMs\":").append(effectiveIntervalMs()).append(",");
        sb.append("\"framesSeen\":").append(framesSeen)
                .append(",\"framesProcessed\":").append(framesProcessed)
                .append(",\"moves\":").append(follower.moves())
                .append(",\"lostStreak\":").append(lostStreak)
                .append(",\"lastDetectMs\":").append(lastDetectMs)
                .append(",\"lastCostMs\":").append(lastCostMs)
                .append(",\"lastMoveMs\":").append(follower.lastMoveMs()).append(",");
        sb.append("\"config\":{");
        sb.append("\"intervalMs\":").append(intervalMs).append(",");
        sb.append("\"stepDeg\":").append(stepDeg).append(",");
        sb.append("\"deadband\":").append(fmt3(deadband)).append(",");
        sb.append("\"timeMs\":").append(timeMs).append(",");
        sb.append("\"headFollow\":").append(headFollow).append(",");
        sb.append("\"autoInterval\":").append(autoInterval).append(",");
        sb.append("\"trackIntervalMs\":").append(trackIntervalMs).append(",");
        sb.append("\"matchThreshold\":").append(fmt3(matchThreshold)).append(",");
        sb.append("\"adapt\":").append(adapt);
        sb.append(",");
        follower.appendConfigJson(sb);
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
