package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.ubtechinc.alpha.hardware.policy.Alpha2PolicyIo;
import com.ubtechinc.alpha.hardware.policy.FallRecoveryDetector;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 跌倒自己起身看門狗（fall/*）。
 *
 * <p>同 {@code PolicyCenter} 嘅 {@code auto_recover} 唔同：呢個係 app 層、獨立於 policy 會話嘅
 * 看門狗。開啟後會自己開加速度計、喺背景每 100ms 睇一次機身傾角；確認穩定躺喺 back／front
 * 之後，直接啟動預載嘅 {@code getup_back}／{@code getup_front} policy。所以就算只係播緊內建
 * 動作、行緊路、或者根本冇 policy session，都可以自動起身（前提係 getup policy 已經
 * {@code policy/load} 入預載組）。</p>
 *
 * <p>安全設計：</p>
 * <ul>
 *   <li>預設關閉，開啟狀態記喺 SharedPreferences（重開 app 會繼續生效）。</li>
 *   <li>一定要先見到企直 2 秒先武裝；開啟功能／開機嗰刻如果已經躺低，唔會即刻郁。</li>
 *   <li>躺穩 {@code dwell_ms}（預設 1500ms）先開始，側躺或者未穩定就唔郁。</li>
 *   <li>每次跌倒最多 {@code max_attempts}（預設 2）次；啟動失敗即刻停，唔會無限重試。</li>
 *   <li>有 policy 正在運行（包括 PolicyCenter 自己嘅 auto_recover）就保持 DISARMED，避免搶控制。
 *       自動起身自己開嗰個 getup policy 例外，會幫佢續租約。</li>
 *   <li>真機運行 getup policy 要合資格（sha256／selftest）；50 Hz getup policy 要 {@code fast=true}。</li>
 * </ul>
 *
 * <p><b>未經實機驗證</b>：加速度計軸映射同 getup policy 一樣仲未喺硬件確認；軸映射錯會令
 * 企住都當跌低。所以預設關閉，真機試之前請吊住／扶住機身，先睇 {@code fall/status} 嘅
 * {@code tiltDeg}／{@code side} 啱唔啱。</p>
 */
public final class FallRecoveryCenter {
    private static final String TAG = "FallRecovery";
    private static final String PREFS = "fall_recovery";
    private static final String KEY_ON = "on";
    private static final String KEY_DWELL = "dwell_ms";
    private static final String KEY_MAX = "max_attempts";
    private static final String KEY_FAST = "fast";
    private static final String KEY_AXES = "axes";

    private static final long POLL_MS = 100;
    private static final long ACCEL_MAX_AGE_MS = 500;
    private static final long BEAT_MS = 1000;

    private final Context appContext;
    private final DeviceStatus deviceStatus;
    private final UbxPlayer ubxPlayer;
    private final PolicyCenter policyCenter;
    private final SharedPreferences prefs;
    private final FallRecoveryDetector detector;

    private volatile boolean enabled;
    private volatile boolean fast;
    private volatile String axesOverride = "";
    private volatile int recoveries;
    private volatile String lastError = "";
    private volatile String lastEvent = "idle";
    private volatile int generation;
    private Thread worker;

    public FallRecoveryCenter(Context context, DeviceStatus deviceStatus, UbxPlayer ubxPlayer,
                              PolicyCenter policyCenter) {
        this.appContext = context.getApplicationContext();
        this.deviceStatus = deviceStatus;
        this.ubxPlayer = ubxPlayer;
        this.policyCenter = policyCenter;
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        FallRecoveryDetector.Config c = new FallRecoveryDetector.Config();
        c.dwellMs = clamp(prefs.getInt(KEY_DWELL, (int) c.dwellMs), 500, 10000);
        c.maxAttempts = clamp(prefs.getInt(KEY_MAX, c.maxAttempts), 1, 5);
        this.fast = prefs.getBoolean(KEY_FAST, false);
        String ax = prefs.getString(KEY_AXES, "");
        this.axesOverride = validAxesOrEmpty(ax);
        this.detector = new FallRecoveryDetector(c);
        this.enabled = prefs.getBoolean(KEY_ON, false);
        if (this.enabled) {
            if (!deviceStatus.hasAccelerometer()) {
                this.enabled = false;
                this.lastError = "no accelerometer sensor on this device";
                prefs.edit().putBoolean(KEY_ON, false).apply();
                Log.w(TAG, "fall recovery disabled: no accelerometer");
            } else {
                startWorker();
                Log.i(TAG, "fall recovery auto-started from preferences");
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String validAxesOrEmpty(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            Alpha2PolicyIo.parseAxes(s);
            return s;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "ignoring bad accel axes '" + s + "': " + e.getMessage());
            return "";
        }
    }

    // -- 開關／設定 ----------------------------------------------------------

    private synchronized void startWorker() {
        final int gen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(gen); }
        }, "FallRecovery");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private synchronized void stopWorker() {
        generation++;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    /** onDestroy 用：停止背景 thread，但保留 SharedPreferences 嘅開啟狀態（下次啟動自動恢復）。 */
    public synchronized void shutdown() {
        enabled = false;
        stopWorker();
    }

    private synchronized String setEnabledInternal(boolean on) {
        if (on == enabled) return null;
        if (on && !deviceStatus.hasAccelerometer()) {
            return "no accelerometer sensor on this device";
        }
        enabled = on;
        if (on) {
            recoveries = 0;
            lastError = "";
            lastEvent = "enabled";
            detector.reset();
            ensureAccel();
            startWorker();
        } else {
            lastEvent = "disabled";
            stopWorker();
        }
        prefs.edit().putBoolean(KEY_ON, enabled).apply();
        publish();
        Log.i(TAG, "fall recovery " + (on ? "enabled" : "disabled"));
        return null;
    }

    private void ensureAccel() {
        if (!deviceStatus.hasAccelerometer() || deviceStatus.isAccelerometerEnabled()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deviceStatus.setAccelerometerEnabled(true);
        } else {
            Map<String, String> q = new HashMap<String, String>();
            q.put("on", "true");
            deviceStatus.accelerometerSet(q);
        }
    }

    // -- HTTP ----------------------------------------------------------------

    public HttpServer.ApiResponse statusResponse() {
        FallRecoveryDetector.Config c = detector.config();
        String err = detector.lastError();
        if (err.isEmpty()) err = lastError;
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"enabled\":").append(enabled);
        sb.append(",\"state\":\"").append(detector.state().name()).append('"');
        sb.append(",\"side\":\"").append(JsonUtil.esc(detector.side())).append('"');
        sb.append(",\"tiltDeg\":").append(num(detector.tiltDeg()));
        sb.append(",\"recoveries\":").append(recoveries);
        sb.append(",\"attempts\":").append(detector.attempts());
        sb.append(",\"maxAttempts\":").append(c.maxAttempts);
        sb.append(",\"dwellMs\":").append(c.dwellMs);
        sb.append(",\"fast\":").append(fast);
        sb.append(",\"axes\":\"").append(JsonUtil.esc(effectiveAxes())).append('"');
        sb.append(",\"axesOverride\":\"").append(JsonUtil.esc(axesOverride)).append('"');
        sb.append(",\"accelAvailable\":").append(deviceStatus.hasAccelerometer());
        sb.append(",\"accelEnabled\":").append(deviceStatus.isAccelerometerEnabled());
        sb.append(",\"accelValid\":").append(deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS) != null);
        sb.append(",\"policyRunning\":").append(policyCenter.policyRunning());
        sb.append(",\"getupBack\":\"").append(JsonUtil.esc(policyCenter.getUpPolicyName("back"))).append('"');
        sb.append(",\"getupFront\":\"").append(JsonUtil.esc(policyCenter.getUpPolicyName("front"))).append('"');
        sb.append(",\"getupBackNeedsFast\":").append(policyCenter.getUpNeedsFast("back"));
        sb.append(",\"getupFrontNeedsFast\":").append(policyCenter.getUpNeedsFast("front"));
        sb.append(",\"lastEvent\":\"").append(JsonUtil.esc(detector.lastEvent())).append('"');
        sb.append(",\"lastError\":\"").append(JsonUtil.esc(err)).append('"');
        sb.append(",\"lastPolicyEnd\":\"").append(JsonUtil.esc(policyCenter.lastEndReason())).append('"');
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /**
     * fall/set：{@code on}、{@code dwell_ms}（500–10000）、{@code max_attempts}（1–5）、
     * {@code fast}、{@code axes}（空＝自動用 getup policy manifest 嘅 accel_axes）。
     * 全部 optional；唔傳就保持現值。
     */
    public synchronized HttpServer.ApiResponse setResponse(Map<String, String> query) {
        boolean on = ApiValidator.optionalBoolean(query, "on", enabled);
        int dwell = ApiValidator.optionalIntRange(query, "dwell_ms", 500, 10000, (int) detector.config().dwellMs);
        int max = ApiValidator.optionalIntRange(query, "max_attempts", 1, 5, detector.config().maxAttempts);
        boolean f = ApiValidator.optionalBoolean(query, "fast", fast);
        String ax = ApiValidator.optional(query, "axes", axesOverride);
        if (!ax.isEmpty()) {
            try {
                Alpha2PolicyIo.parseAxes(ax);
            } catch (IllegalArgumentException e) {
                return HttpServer.ApiResponse.badRequest(e.getMessage());
            }
        }

        FallRecoveryDetector.Config c = detector.config();
        boolean detectorChanged = c.dwellMs != dwell || c.maxAttempts != max;
        if (detectorChanged) {
            c.dwellMs = dwell;
            c.maxAttempts = max;
            try {
                detector.setConfig(c);
            } catch (IllegalArgumentException e) {
                return HttpServer.ApiResponse.badRequest(e.getMessage());
            }
            lastEvent = "config";
        }
        fast = f;
        axesOverride = ax;
        prefs.edit()
                .putInt(KEY_DWELL, dwell)
                .putInt(KEY_MAX, max)
                .putBoolean(KEY_FAST, f)
                .putString(KEY_AXES, ax)
                .apply();

        String err = setEnabledInternal(on);
        if (err != null) return HttpServer.ApiResponse.badRequest(err);
        publish();
        return statusResponse();
    }

    /**
     * fall/recover：手動即刻起身。{@code side=back|front} 可選（唔傳就用偵測到嘅最後一面）；
     * {@code fast} 可選（覆寫本次啟動）。就算看門狗關閉都用得，方便扶正／測試 getup policy。
     */
    public HttpServer.ApiResponse recoverResponse(Map<String, String> query) {
        String side = ApiValidator.optionalEnum(query, "side", new String[] {"back", "front"}, detector.side());
        boolean f = ApiValidator.optionalBoolean(query, "fast", fast);
        if (side == null || side.isEmpty()) {
            return HttpServer.ApiResponse.badRequest("not lying on back/front (side unknown); give side=back or side=front");
        }
        String err = startGetUpNow(side, f, false);
        if (err != null) return HttpServer.ApiResponse.badRequest(err);
        return statusResponse();
    }

    /** fall/reset：清除 FAILED／錯誤，重新由 DISARMED 開始（要再見到企直先會武裝）。 */
    public synchronized HttpServer.ApiResponse resetResponse() {
        detector.reset();
        lastError = "";
        lastEvent = "reset";
        publish();
        return statusResponse();
    }

    // -- 內部 ----------------------------------------------------------------

    private String effectiveAxes() {
        String a = axesOverride;
        if (a == null || a.isEmpty()) a = policyCenter.getUpAxesAuto();
        if (a == null || a.isEmpty()) a = "+x+y+z";
        return a;
    }

    private String startGetUpNow(String side, boolean fastMode, boolean auto) {
        String err = policyCenter.startGetUp(side, fastMode);
        if (err == null) {
            recoveries++;
            lastError = "";
            lastEvent = auto ? "auto recovery started (" + side + ")" : "manual recovery started (" + side + ")";
            Log.i(TAG, lastEvent);
        } else {
            lastError = err;
            lastEvent = (auto ? "auto" : "manual") + " recovery failed";
            Log.w(TAG, "get-up " + side + " not started: " + err);
        }
        publish();
        return err;
    }

    private void loop(int gen) {
        long lastBeat = 0;
        String lastState = "";
        String lastPublishedEvent = "";
        while (enabled && gen == generation) {
            try {
                ensureAccel();
                long now = SystemClock.elapsedRealtime();

                double[] a = deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS);
                double[] gravity = null;
                if (a != null) {
                    double qYaw = 0, qPitch = 0;
                    int[] pose = ubxPlayer.pose();
                    if (pose != null && pose.length >= 20) {
                        try {
                            double[] q = Alpha2PolicyIo.servoToQ(pose);
                            qYaw = q[18];
                            qPitch = q[19];
                        } catch (RuntimeException ignore) {
                            // 位姿唔完整：當頭部 0/0，寧願照用重力方向都唔好完全停偵測。
                        }
                    }
                    try {
                        gravity = Alpha2PolicyIo.gravityBase(a,
                                Alpha2PolicyIo.parseAxes(effectiveAxes()), qYaw, qPitch);
                    } catch (IllegalArgumentException e) {
                        lastError = "bad accel axes: " + e.getMessage();
                        gravity = null;
                    }
                }

                boolean policyRunning = policyCenter.policyRunning();
                FallRecoveryDetector.Event ev = detector.update(now, gravity, policyRunning);
                if (ev == FallRecoveryDetector.Event.START_RECOVERY) {
                    String side = detector.side();
                    String err = startGetUpNow(side, fast, true);
                    detector.onRecoveryStarted(err == null, err);
                }

                // 自動起身自己開嘅 getup policy：幫佢續租約（manifest lease 預設 5 秒）。
                // 一旦企返直（detector 離開 RECOVERING）就唔再續，等 policy 自己完／lease 停，
                // 唔會留一個冇人看住嘅 policy 長跑。
                if (detector.state() == FallRecoveryDetector.State.RECOVERING
                        && policyCenter.policyRunning()) {
                    if (now - lastBeat >= BEAT_MS) {
                        policyCenter.beatPolicy();
                        lastBeat = now;
                    }
                } else {
                    lastBeat = 0;
                }

                // getup policy 完咗但仲未企直：把 runner 嘅結束原因寫入 status；not_lying 直接
                // fail（軸映射錯／面數錯，重試同一面只會浪費次數）。
                if (detector.state() == FallRecoveryDetector.State.RECOVERING
                        && !detector.awaitingStart() && !policyCenter.policyRunning()) {
                    String end = policyCenter.lastEndReason();
                    if ("not_lying".equals(end)) {
                        detector.fail("get-up refused: not lying on the expected side ("
                                + detector.side() + "); check accel_axes / fall/status side");
                    }
                }

                String st = detector.state().name();
                String evText = detector.lastEvent();
                if (!st.equals(lastState) || !evText.equals(lastPublishedEvent)) {
                    lastState = st;
                    lastPublishedEvent = evText;
                    publish();
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                lastError = "watchdog error: " + t;
                Log.w(TAG, "fall watchdog loop error", t);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void publish() {
        try {
            EventBus.get().publish("fall_recovery", statusResponse().body);
        } catch (Throwable t) {
            Log.w(TAG, "publish failed", t);
        }
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        return String.format(Locale.US, "%.1f", v);
    }
}
