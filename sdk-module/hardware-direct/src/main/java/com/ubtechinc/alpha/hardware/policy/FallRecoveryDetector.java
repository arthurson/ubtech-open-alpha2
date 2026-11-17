package com.ubtechinc.alpha.hardware.policy;

/**
 * 跌倒自動起身嘅純狀態機（無 Android 依賴，可單元測試）。
 *
 * <p>輸入係平滑後嘅加速度計讀數，經 {@link Alpha2PolicyIo#gravityBase} 轉做機身座標重力方向，
 * 再用 tilt 同 {@link Alpha2PolicyIo#lyingKind} 判斷企直／仰躺／趴住。起身用機身內建動作
 * （{@link #getUpAction(String)}：仰躺＝BackStand、趴住＝FrontStand），唔使 ONNX policy。狀態：</p>
 *
 * <pre>
 * DISARMED --企直 armMs--> ARMED --傾角過 fallTiltDeg 持續 fallConfirmMs--> FALLEN
 * FALLEN --躺穩 dwellMs--> RECOVERING（回傳 START_RECOVERY，由外面播放起身動作）
 * RECOVERING --企返直 recoverUprightMs--> ARMED
 * RECOVERING --動作完但仍然躺住，仲有次數--> FALLEN（再等 dwellMs 重試）
 * 任何狀態 --企返直 armMs--> ARMED（FAILED 亦靠呢條路重新武裝）
 * </pre>
 *
 * <p>安全規則：</p>
 * <ul>
 *   <li>一開始一定係 DISARMED：起身／開啟功能嗰刻如果已經躺低，唔會自動起身——要先見到企直
 *       armMs 先武裝。呢條規則避免「一開功能就由企姿亂啟動」同「起身途中自己再觸發」。</li>
 *   <li>側躺（唔係 back／front）唔會亂郁：等 settleGiveUpMs 後轉 FAILED，等用戶扶返直先重新武裝。</li>
 *   <li>外面有 policy 正在運行（唔係今次自動起身嗰個）就保持 DISARMED／重置，避免同
 *       {@code PolicyRunner} 自己嘅 auto-recover 搶控制。</li>
 *   <li>自動起身最多 maxAttempts 次；用晒或者啟動失敗就 FAILED，唔會無限重試。</li>
 * </ul>
 */
public final class FallRecoveryDetector {

    public enum State { DISARMED, ARMED, FALLEN, RECOVERING, FAILED }

    /** 跌倒偵測預設軸映射：與 policy 共用 {@link Alpha2PolicyIo#DEFAULT_ACCEL_AXES}（sensor y 為前後方向，+y 趴住，-y 仰躺）。 */
    public static final String DEFAULT_AXES = Alpha2PolicyIo.DEFAULT_ACCEL_AXES;

    /** update() 回傳：NONE＝冇事；START_RECOVERY＝而家應該播放對應 side 嘅起身動作。 */
    public enum Event { NONE, START_RECOVERY }

    public static final class Config {
        /** 傾角超過呢個值先當「跌落中」（度）。 */
        public double fallTiltDeg = 55.0;
        /** 傾角低過呢個值先當企返直（度）。 */
        public double armTiltDeg = 25.0;
        /** 要連續企直幾耐先武裝／重新武裝。 */
        public long armMs = 2000L;
        /** 傾角過 fallTiltDeg 要持續幾耐先確認係跌（過濾行路／動作嘅一瞬尖峰）。 */
        public long fallConfirmMs = 300L;
        /** 躺低（back／front）要穩定幾耐先啟動 getup（過濾跌落途中嘅過渡姿勢）。 */
        public long dwellMs = 1500L;
        /** 側躺／未穩定躺平超過呢個時間就放棄（唔郁舵機）。 */
        public long settleGiveUpMs = 10000L;
        /** 單次起身動作最長等幾耐（動作卡住唔完都唔會無限等）。 */
        public long recoverTimeoutMs = 30000L;
        /** getup 後要企直幾耐先當成功。 */
        public long recoverUprightMs = 800L;
        /** 每次跌倒最多自動起身幾多次（1–5）。 */
        public int maxAttempts = 2;

        public Config copy() {
            Config c = new Config();
            c.fallTiltDeg = fallTiltDeg;
            c.armTiltDeg = armTiltDeg;
            c.armMs = armMs;
            c.fallConfirmMs = fallConfirmMs;
            c.dwellMs = dwellMs;
            c.settleGiveUpMs = settleGiveUpMs;
            c.recoverTimeoutMs = recoverTimeoutMs;
            c.recoverUprightMs = recoverUprightMs;
            c.maxAttempts = maxAttempts;
            return c;
        }

        public void validate() {
            if (!(armTiltDeg >= 5.0 && armTiltDeg < fallTiltDeg && fallTiltDeg <= 85.0)) {
                throw new IllegalArgumentException("tilt thresholds must be 5 <= arm < fall <= 85 degrees");
            }
            if (armMs < 0 || fallConfirmMs < 0 || dwellMs < 0 || recoverUprightMs < 0) {
                throw new IllegalArgumentException("millisecond timings must be >= 0");
            }
            if (settleGiveUpMs <= 0 || recoverTimeoutMs <= 0) {
                throw new IllegalArgumentException("settle_give_up_ms and recover_timeout_ms must be > 0");
            }
            if (maxAttempts < 1 || maxAttempts > 5) {
                throw new IllegalArgumentException("maxAttempts must be 1..5");
            }
        }
    }

    /** 躺低方向對應嘅內建起身動作 id：仰躺（back）→ BackStand，趴住（front）→ FrontStand。 */
    public static String getUpAction(String side) {
        if (Alpha2PolicyIo.LYING_BACK.equals(side)) return "BackStand";
        if (Alpha2PolicyIo.LYING_FRONT.equals(side)) return "FrontStand";
        return "";
    }

    private Config cfg;
    private State state = State.DISARMED;
    private long uprightSince = -1;
    private long tiltSince = -1;
    private long lyingSince = -1;
    private long fallenSince = -1;
    private long recoverSince = -1;
    private String side = "";
    private int attempts;
    private boolean awaitingStart;
    private String lastError = "";
    private String lastEvent = "idle";
    private double tiltDeg = Double.NaN;

    public FallRecoveryDetector(Config config) {
        setConfig(config);
    }

    public synchronized Config config() {
        return cfg.copy();
    }

    /** 改設定會重置狀態機（安全：改完一定要重新見到企直先會再武裝）。 */
    public synchronized void setConfig(Config config) {
        if (config == null) throw new IllegalArgumentException("config required");
        Config c = config.copy();
        c.validate();
        cfg = c;
        reset();
    }

    /** 手動清除錯誤／重新武裝；要再見到企直 armMs 先會變 ARMED。 */
    public synchronized void reset() {
        state = State.DISARMED;
        uprightSince = -1;
        tiltSince = -1;
        lyingSince = -1;
        fallenSince = -1;
        recoverSince = -1;
        side = "";
        attempts = 0;
        awaitingStart = false;
        lastError = "";
        lastEvent = "reset";
    }

    public synchronized State state() { return state; }
    public synchronized String side() { return side; }
    public synchronized double tiltDeg() { return tiltDeg; }
    public synchronized int attempts() { return attempts; }
    public synchronized String lastError() { return lastError; }
    public synchronized String lastEvent() { return lastEvent; }
    public synchronized boolean awaitingStart() { return awaitingStart; }

    /**
     * 餵一個 sample。
     *
     * @param nowMs      單調時鐘（elapsedRealtime 或測試用時間）
     * @param gravity    機身座標重力方向（單位向量，企直＝(0,0,-1)）；null＝加速度讀數無效
     * @param getupRunning RECOVERING 期間＝今次起身動作仲運行緊；其他狀態傳 true 代表外面有
     *        policy 控制緊或者起身動作已經播緊（會保持 DISARMED，避免搶控制）
     * @return {@link Event#START_RECOVERY} 代表外面應該播放 {@link #getUpAction(String)}
     *         對應嘅動作，之後必須 call {@link #onRecoveryStarted(boolean, String)} 報告結果。
     */
    public synchronized Event update(long nowMs, double[] gravity, boolean getupRunning) {
        // 外面有 policy 控制緊／起身動作播緊：保持 DISARMED，避免搶控制或者重複播放。
        // RECOVERING 係今次自動起身自己開嘅動作，要繼續睇到佢完成。
        if (getupRunning && state != State.RECOVERING) {
            if (state != State.DISARMED) {
                resetInternal("busy");
            }
            return Event.NONE;
        }

        boolean valid = gravity != null && gravity.length >= 3;
        if (valid) {
            tiltDeg = Math.toDegrees(Alpha2PolicyIo.tiltRad(gravity));
        }

        switch (state) {
            case DISARMED:
                if (!valid) return Event.NONE;
                if (tiltDeg <= cfg.armTiltDeg) {
                    if (uprightSince < 0) uprightSince = nowMs;
                    if (nowMs - uprightSince >= cfg.armMs) {
                        state = State.ARMED;
                        attempts = 0;
                        side = "";
                        lastError = "";
                        lastEvent = "armed";
                    }
                } else {
                    uprightSince = -1;
                }
                return Event.NONE;

            case ARMED:
                if (!valid) return Event.NONE;
                if (tiltDeg >= cfg.fallTiltDeg) {
                    if (tiltSince < 0) tiltSince = nowMs;
                    if (nowMs - tiltSince >= cfg.fallConfirmMs) {
                        state = State.FALLEN;
                        fallenSince = nowMs;
                        lyingSince = -1;
                        side = "";
                        attempts = 0;
                        lastEvent = "fallen";
                    }
                } else {
                    tiltSince = -1;
                }
                return Event.NONE;

            case FALLEN: {
                if (!valid) return Event.NONE;
                String lying = Alpha2PolicyIo.lyingKind(gravity);
                if (lying.isEmpty()) {
                    lyingSince = -1;
                    side = "";
                    if (nowMs - fallenSince >= cfg.settleGiveUpMs) {
                        failInternal("not lying on back/front (side-lying or not settled)");
                        return Event.NONE;
                    }
                    return Event.NONE;
                }
                if (!lying.equals(side)) {
                    side = lying;
                    lyingSince = nowMs;
                }
                if (nowMs - lyingSince < cfg.dwellMs) return Event.NONE;
                if (attempts >= cfg.maxAttempts) {
                    failInternal("max get-up attempts used");
                    return Event.NONE;
                }
                attempts++;
                awaitingStart = true;
                recoverSince = nowMs;
                state = State.RECOVERING;
                lastEvent = "recovery requested";
                return Event.START_RECOVERY;
            }

            case RECOVERING: {
                if (!valid) {
                    // 讀數暫時無效：唔好當「動作完咗仍然躺住」而重試；等 timeout 自保。
                    if (getupRunning && nowMs - recoverSince >= cfg.recoverTimeoutMs) {
                        failInternal("get-up timed out");
                    }
                    return Event.NONE;
                }
                // 先睇係咪已經企返直（起身動作可能已經完成）。
                boolean upright = tiltDeg <= cfg.armTiltDeg;
                if (upright) {
                    if (uprightSince < 0) uprightSince = nowMs;
                    if (nowMs - uprightSince >= cfg.recoverUprightMs) {
                        state = State.ARMED;
                        side = "";
                        attempts = 0;
                        awaitingStart = false;
                        lastError = "";
                        lastEvent = "recovered";
                        return Event.NONE;
                    }
                } else {
                    uprightSince = -1;
                }
                if (awaitingStart) return Event.NONE;  // 等外面報告啟動結果
                if (getupRunning) {
                    if (nowMs - recoverSince >= cfg.recoverTimeoutMs) {
                        failInternal("get-up timed out");
                    }
                    return Event.NONE;
                }
                // 起身動作已完結但仲未企直：仲有次數就返 FALLEN 再等 dwellMs 重試。
                if (upright) return Event.NONE;        // 已經企直，只係未夠 recoverUprightMs
                if (attempts < cfg.maxAttempts) {
                    state = State.FALLEN;
                    fallenSince = nowMs;
                    lyingSince = -1;
                    side = "";
                    lastEvent = "retry";
                } else {
                    failInternal("get-up did not bring the robot upright");
                }
                return Event.NONE;
            }

            case FAILED:
                if (!valid) return Event.NONE;
                if (tiltDeg <= cfg.armTiltDeg) {
                    if (uprightSince < 0) uprightSince = nowMs;
                    if (nowMs - uprightSince >= cfg.armMs) {
                        state = State.ARMED;
                        attempts = 0;
                        side = "";
                        lastError = "";
                        lastEvent = "rearmed";
                    }
                } else {
                    uprightSince = -1;
                }
                return Event.NONE;

            default:
                return Event.NONE;
        }
    }

    /** 外面啟動起身動作後報告結果；失敗會轉 FAILED（唔會無限重試）。 */
    public synchronized void onRecoveryStarted(boolean ok, String error) {
        if (state != State.RECOVERING) return;
        awaitingStart = false;
        if (ok) {
            lastError = "";
            lastEvent = "recovery started";
        } else {
            failInternal("cannot start get-up: " + (error == null || error.isEmpty() ? "unknown" : error));
        }
    }

    /** 外部判定失敗（例如起身動作播唔到）：轉 FAILED，等企返直先重新武裝。 */
    public synchronized void fail(String error) {
        failInternal(error == null || error.isEmpty() ? "failed" : error);
    }

    private void failInternal(String error) {
        state = State.FAILED;
        awaitingStart = false;
        uprightSince = -1;
        lastError = error;
        lastEvent = "failed";
    }

    private void resetInternal(String why) {
        state = State.DISARMED;
        uprightSince = -1;
        tiltSince = -1;
        lyingSince = -1;
        fallenSince = -1;
        recoverSince = -1;
        side = "";
        attempts = 0;
        awaitingStart = false;
        lastError = "";
        lastEvent = why;
    }
}
