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
 * <p>app 層、獨立於 policy 會話嘅看門狗。開啟後會自己開加速度計、喺背景每 100ms 睇一次機身
 * 傾角；確認穩定躺喺 back／front 之後，直接播放機身內建動作 {@code BackStand}（仰躺）／
 * {@code FrontStand}（趴住）。所以就算只係播緊內建動作、行緊路、或者根本冇 policy session，
 * 都可以自動起身，**唔需要 ONNX policy**。</p>
 *
 * <p>安全設計：</p>
 * <ul>
 *   <li>預設關閉，開啟狀態記喺 SharedPreferences（重開 app 會繼續生效）。</li>
 *   <li>一定要先見到企直 2 秒先武裝；開啟功能／開機嗰刻如果已經躺低，唔會即刻郁。</li>
 *   <li>躺穩 {@code dwell_ms}（預設 1500ms）先開始，側躺或者未穩定就唔郁。</li>
 *   <li>每次跌倒最多 {@code max_attempts}（預設 2）次；動作播唔到即刻停，唔會無限重試。</li>
 *   <li>有 policy 正在運行就保持 DISARMED／放棄今次起身，避免同 {@code PolicyRunner} 自己嘅
 *       auto-recover 搶控制。</li>
 * </ul>
 *
 * <p>行路跟（顏色／人臉追蹤）經 {@link FallMonitor} 接入：開咗行路跟就 {@link #acquire()} 臨時確保
 * 呢個看門狗開住（唔寫 SharedPreferences，全部 {@link #release()} 後還原），並讀 {@link #fallLevel()}
 * 知道機身有冇跌低；起身動作仍然由呢度播，行路跟只負責停晒指令同起身後自動繼續。</p>
 *
 * <p>加速度計軸映射已按實機修正（預設 {@code -y+x+z}：sensor y 為前後方向，+y 趴住，-y 仰躺）；BackStand／FrontStand 動作尚未在硬件上確認；
 * 軸映射錯誤會令機身站立時也被判定為跌倒，因此預設關閉。實機測試前請吊住或扶住機身，並先檢查
 * {@code fall/status} 中的 {@code tiltDeg}／{@code side} 是否正確。</p>
 */
public final class FallRecoveryCenter implements FallMonitor {
    private static final String TAG = "FallRecovery";
    private static final String PREFS = "fall_recovery";
    private static final String KEY_ON = "on";
    private static final String KEY_DWELL = "dwell_ms";
    private static final String KEY_MAX = "max_attempts";
    private static final String KEY_AXES = "axes";

    private static final long POLL_MS = 100;
    private static final long ACCEL_MAX_AGE_MS = 500;
    /** 起身動作剛開始時 UbxPlayer 可能未咁快 mark playing；呢段時間當佢運行緊，避免誤重試。 */
    private static final long RECOVERY_START_GRACE_MS = 1500;

    private final Context appContext;
    private final DeviceStatus deviceStatus;
    private final UbxPlayer ubxPlayer;
    private final ActionDirect actionDirect;
    private final PolicyCenter policyCenter;
    private final SharedPreferences prefs;
    private final FallRecoveryDetector detector;

    private volatile boolean enabled;
    private volatile String axesOverride = "";
    private volatile int recoveries;
    private volatile String lastAction = "";
    private volatile long recoveryStartedAtMs = -1;
    private volatile boolean manualRecovery;
    private volatile String lastError = "";
    private volatile String lastEvent = "idle";
    private volatile int generation;
    private volatile Thread worker;
    /** 行路跟臨時要求開住嘅計數（acquire／release）；sessionEnabled＝呢個開關係因為行路跟先開。 */
    private int sessionHolds;
    private boolean sessionEnabled;

    public FallRecoveryCenter(Context context, DeviceStatus deviceStatus, UbxPlayer ubxPlayer,
                              ActionDirect actionDirect, PolicyCenter policyCenter) {
        this.appContext = context.getApplicationContext();
        this.deviceStatus = deviceStatus;
        this.ubxPlayer = ubxPlayer;
        this.actionDirect = actionDirect;
        this.policyCenter = policyCenter;
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        FallRecoveryDetector.Config c = new FallRecoveryDetector.Config();
        c.dwellMs = clamp(prefs.getInt(KEY_DWELL, (int) c.dwellMs), 500, 10000);
        c.maxAttempts = clamp(prefs.getInt(KEY_MAX, c.maxAttempts), 1, 5);
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
        if (on == enabled) {
            // 用戶明確要求開（可能只係行路跟臨時開住）：記落偏好，行路跟 release 後唔好關。
            if (on) prefs.edit().putBoolean(KEY_ON, true).apply();
            return null;
        }
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

    // -- FallMonitor（行路跟用）---------------------------------------------

    /**
     * FALL_OK＝看門狗開住、已武裝（見過企直）、加速度計有數據；FALL_DOWN＝跌低／起身中／起身失敗
     *（FALLEN／RECOVERING／FAILED）；其餘（未開、未武裝、睇唔到加速度計）＝FALL_NOT_READY：
     * 唔識偵測跌倒，行路跟唔會行。
     */
    @Override
    public int fallLevel() {
        if (!enabled || worker == null) return ColorTrackPlanner.FALL_NOT_READY;
        switch (detector.state()) {
            case FALLEN:
            case RECOVERING:
            case FAILED:
                return ColorTrackPlanner.FALL_DOWN;
            case ARMED:
                return deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS) != null
                        ? ColorTrackPlanner.FALL_OK : ColorTrackPlanner.FALL_NOT_READY;
            default:
                return ColorTrackPlanner.FALL_NOT_READY;
        }
    }

    @Override
    public String fallState() {
        if (!enabled || worker == null) return "off";
        FallRecoveryDetector.State st = detector.state();
        if (st == FallRecoveryDetector.State.ARMED && deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS) == null) {
            return "noAccel";
        }
        return st.name().toLowerCase(Locale.US);
    }

    /** 行路跟期間確保看門狗開住（可重入計數，唔寫 SharedPreferences）。 */
    @Override
    public synchronized String acquire() {
        if (!enabled && !deviceStatus.hasAccelerometer()) {
            return "no accelerometer sensor on this device";
        }
        sessionHolds++;
        if (!enabled) {
            enabled = true;
            sessionEnabled = true;
            recoveries = 0;
            lastError = "";
            lastEvent = "enabled (walk follow)";
            detector.reset();
            ensureAccel();
            startWorker();
            publish();
            Log.i(TAG, "fall recovery enabled for walk follow (not persisted)");
        }
        return null;
    }

    /** 最後一個 release：如果看門狗只係因為行路跟先開（偏好冇開），就關返。 */
    @Override
    public synchronized void release() {
        if (sessionHolds > 0) sessionHolds--;
        if (sessionHolds == 0 && sessionEnabled) {
            sessionEnabled = false;
            if (!prefs.getBoolean(KEY_ON, false)) {
                enabled = false;
                lastEvent = "disabled (walk follow ended)";
                stopWorker();
                publish();
                Log.i(TAG, "fall recovery disabled again (walk follow ended)");
            }
        }
    }

    // -- HTTP ----------------------------------------------------------------

    public HttpServer.ApiResponse statusResponse() {
        FallRecoveryDetector.Config c = detector.config();
        String err = detector.lastError();
        if (err.isEmpty()) err = lastError;
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"enabled\":").append(enabled);
        sb.append(",\"walkFollowHolds\":").append(sessionHolds);
        sb.append(",\"state\":\"").append(detector.state().name()).append('"');
        sb.append(",\"side\":\"").append(JsonUtil.esc(detector.side())).append('"');
        sb.append(",\"tiltDeg\":").append(num(detector.tiltDeg()));
        sb.append(",\"recoveries\":").append(recoveries);
        sb.append(",\"attempts\":").append(detector.attempts());
        sb.append(",\"maxAttempts\":").append(c.maxAttempts);
        sb.append(",\"dwellMs\":").append(c.dwellMs);
        sb.append(",\"axes\":\"").append(JsonUtil.esc(effectiveAxes())).append('"');
        sb.append(",\"axesOverride\":\"").append(JsonUtil.esc(axesOverride)).append('"');
        sb.append(",\"accelAvailable\":").append(deviceStatus.hasAccelerometer());
        sb.append(",\"accelEnabled\":").append(deviceStatus.isAccelerometerEnabled());
        sb.append(",\"accelValid\":").append(deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS) != null);
        sb.append(",\"policyRunning\":").append(policyCenter.policyRunning());
        sb.append(",\"actionPlaying\":").append(actionDirect.isPlaying());
        sb.append(",\"backStandAvailable\":").append(actionDirect.hasAction("BackStand"));
        sb.append(",\"frontStandAvailable\":").append(actionDirect.hasAction("FrontStand"));
        sb.append(",\"lastAction\":\"").append(JsonUtil.esc(lastAction)).append('"');
        sb.append(",\"lastEvent\":\"").append(JsonUtil.esc(detector.lastEvent())).append('"');
        sb.append(",\"lastError\":\"").append(JsonUtil.esc(err)).append('"');
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /**
     * fall/set：{@code on}、{@code dwell_ms}（500–10000）、{@code max_attempts}（1–5）、
     * {@code axes}（留空則使用預設 {@code -y+x+z}，sensor y 為前後方向：+y 趴住，-y 仰躺）。全部為選填；未傳入則保持現值。
     */
    public synchronized HttpServer.ApiResponse setResponse(Map<String, String> query) {
        boolean on = ApiValidator.optionalBoolean(query, "on", enabled);
        int dwell = ApiValidator.optionalIntRange(query, "dwell_ms", 500, 10000, (int) detector.config().dwellMs);
        int max = ApiValidator.optionalIntRange(query, "max_attempts", 1, 5, detector.config().maxAttempts);
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
        axesOverride = ax;
        prefs.edit()
                .putInt(KEY_DWELL, dwell)
                .putInt(KEY_MAX, max)
                .putString(KEY_AXES, ax)
                .apply();

        String err = setEnabledInternal(on);
        if (err != null) return HttpServer.ApiResponse.badRequest(err);
        publish();
        return statusResponse();
    }

    /**
     * fall/recover：手動即刻起身。{@code side=back|front} 可選（唔傳就用偵測到嘅最後一面）。
     * 就算看門狗關閉都用得，方便扶正／測試 BackStand／FrontStand 動作。
     */
    public HttpServer.ApiResponse recoverResponse(Map<String, String> query) {
        String side = ApiValidator.optionalEnum(query, "side", new String[] {"back", "front"}, detector.side());
        if (side == null || side.isEmpty()) {
            return HttpServer.ApiResponse.badRequest("not lying on back/front (side unknown); give side=back or side=front");
        }
        String err = startGetUpNow(side, false);
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
        if (a == null || a.isEmpty()) a = FallRecoveryDetector.DEFAULT_AXES;
        return a;
    }

    private String startGetUpNow(String side, boolean auto) {
        String action = FallRecoveryDetector.getUpAction(side);
        String err = null;
        if (action.isEmpty()) {
            err = "not lying on back/front (side unknown); give side=back or side=front";
        } else if (!actionDirect.hasAction(action)) {
            err = action + " action not found in /sdcard/actions";
        } else if (actionDirect.playActionDirect(action)
                != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            err = "cannot start " + action + " action";
        }
        if (err == null) {
            recoveries++;
            recoveryStartedAtMs = SystemClock.elapsedRealtime();
            manualRecovery = !auto;
            lastAction = action;
            lastError = "";
            lastEvent = auto ? "auto recovery started (" + action + ")" : "manual recovery started (" + action + ")";
            Log.i(TAG, lastEvent);
        } else {
            manualRecovery = false;
            lastError = err;
            lastEvent = (auto ? "auto" : "manual") + " recovery failed";
            Log.w(TAG, "get-up " + side + " not started: " + err);
        }
        publish();
        return err;
    }

    /** RECOVERING 期間：起身動作係咪仲運行緊（開頭俾少少 grace 等 UbxPlayer mark playing）。 */
    private boolean recoveryActionActive(long nowMs) {
        if (actionDirect.isPlaying()) return true;
        return recoveryStartedAtMs > 0 && nowMs - recoveryStartedAtMs < RECOVERY_START_GRACE_MS;
    }

    private void loop(int gen) {
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
                // 起身途中如果有 policy 開始控制：放棄今次起身（PolicyCenter 會停咗我哋個動作），
                // 等 policy 完結、機身企返直先重新武裝。
                if (policyRunning && detector.state() == FallRecoveryDetector.State.RECOVERING) {
                    detector.fail("policy started; watchdog gave way");
                }
                // 手動 fall/recover 嘅動作播完（或超過 grace）就清旗；避免之後嘅普通動作
                // （例如跳舞）令 detector 長期 DISARMED。
                if (manualRecovery && !actionDirect.isPlaying()
                        && now - recoveryStartedAtMs >= RECOVERY_START_GRACE_MS) {
                    manualRecovery = false;
                }
                boolean actionActive = manualRecovery
                        || (detector.state() == FallRecoveryDetector.State.RECOVERING
                            && recoveryActionActive(now));
                // 起身動作播緊（自動或手動）／有 policy 控制：非 RECOVERING 時 detector 保持
                // DISARMED，避免搶控制或者手動 fall/recover 之後再自動重複播。
                boolean busy = actionActive
                        || (policyRunning && detector.state() != FallRecoveryDetector.State.RECOVERING);
                FallRecoveryDetector.Event ev = detector.update(now, gravity, busy);
                if (ev == FallRecoveryDetector.Event.START_RECOVERY) {
                    String side = detector.side();
                    String err = startGetUpNow(side, true);
                    detector.onRecoveryStarted(err == null, err);
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
