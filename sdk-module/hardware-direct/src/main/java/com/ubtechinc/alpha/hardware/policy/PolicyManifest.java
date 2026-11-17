package com.ubtechinc.alpha.hardware.policy;

/**
 * Policy manifest（{@code manifest.json}，schema 1）嘅欄位＋驗證。JSON 讀取喺 app 層
 * （org.json），呢度只做純 Java 驗證，方便單元測試。
 *
 * <p>學 Microduck「一個 policy.onnx ＋ manifest」嘅包裝，但契約係 Alpha2 自己嘅（見
 * {@link Alpha2PolicyIo}），而且<b>多咗硬件速率檢查</b>：{@code control_hz} 即係 policy 訓練時嘅
 * 決策頻率，policy 永遠按佢自己嘅頻率跑（唔會放慢——放慢係 out-of-distribution）。現有步態引擎
 * 實機送出間隔有 {@link #MIN_PERIOD_MS} 下限（穩定性），所以高頻 policy（例如 URDF repo 嘅
 * 50 Hz walk_forward.onnx）真機運行要用戶明確 {@code fast=true}（實驗，下限
 * {@link #FAST_MIN_PERIOD_MS}）；未 fast 只准 dry-run 排練。fast 模式靠 runner 嘅 overrun
 * 偵測自保：錯過節拍太多即停。</p>
 */
public final class PolicyManifest {
    /**
     * 一般（非 fast）真機運行嘅最短格長。50Hz 實機測試會亂震，改為 200ms（5 Hz）；比步態引擎
     * （GaitWalker.WALK_MIN_UPDATE_MS＝300，另一條路徑，冇改）更激進，仍未喺實機驗證。
     */
    public static final int MIN_PERIOD_MS = 200;
    /** fast 模式（實驗）下限：chest 協議 move 時間下限 20ms（DirectChestController.clampTime）。 */
    public static final int FAST_MIN_PERIOD_MS = 20;

    /** 速度包絡硬上限（°/s）。官方步態峰值 60；URDF 模型 velocity 5 rad/s ≈ 286。 */
    public static final double MAX_SPEED_DPS_CAP = 300;

    public int schema = 1;
    public String name = "";
    public String obs = "stand";          // stand｜walk｜getup（舊格式 43／65）｜unified（統一 68 維，見 rl/alpha2_env.py）
    public String task = "";              // stand｜walk｜getup_back｜getup_front（空＝由 obs 推；getup 必須講明邊面）
    public double holdS = 3.0;            // getup：ref 播完後再撐企幾耐先當完成（訓練 env 嘅 HOLD 150 格×0.02s）
    public String then = "";              // getup 完成後交俾邊個預載 policy（空＝停機，舵機保持企姿）
    public double gaitPeriodS = 1.0;      // unified stand／walk：喂俾 policy 嘅自由運行時鐘週期（訓練 GAIT_PERIOD）
    public double[] cmdMax = {0.05, 0.03, 0.4};   // unified walk：速度指令上限 (vx m/s, vy m/s, wz rad/s)（訓練 cmd_vx/vy/wz）
    public int obsDim;
    public int actionDim = 20;
    public double actScale = 0.3;
    public double controlHz;
    public double moveOverlap = 1.05;
    public String accelAxes = Alpha2PolicyIo.DEFAULT_ACCEL_AXES;
    public String ref = "";               // walk 用，相對 manifest 嘅檔名
    public double maxSpeedDps = 90;
    public int maxRunS = 60;
    public int leaseMs = 5000;
    public double tiltLimitDeg = 40;
    public String sha256 = "";            // policy.onnx 嘅 SHA-256（小寫 hex）；空＝唔驗（只准 dry）
    public String notes = "";

    public int periodMs() { return (int) Math.round(1000.0 / controlHz); }

    /** task 欄位（空＝由 obs 推：stand／walk；getup 冇得推，要明寫）。 */
    public String effectiveTask() { return task == null || task.isEmpty() ? ("getup".equals(obs) || "unified".equals(obs) ? "" : obs) : task; }
    public boolean isUnified() { return "unified".equals(obs); }
    public static final String[] UNIFIED_TASKS = {"stand", "walk", "walk_ref", "getup_back", "getup_front"};

    /** getup 要求起步時躺住嘅方向："back"／"front"；非 getup 回空。 */
    public String lyingSide() {
        String t = effectiveTask();
        return "getup_back".equals(t) ? "back" : "getup_front".equals(t) ? "front" : "";
    }

    public boolean realRunAllowed(String[] reason) { return realRunAllowed(reason, false); }

    /**
     * 呢個 policy 可唔可以真機跑（false 時只准 dry）；原因放 reason[0]。
     * @param fast true＝用戶明確要求實驗性高頻模式（period 可低至 {@link #FAST_MIN_PERIOD_MS}）
     */
    public boolean realRunAllowed(String[] reason, boolean fast) {
        int floor = fast ? FAST_MIN_PERIOD_MS : MIN_PERIOD_MS;
        if (periodMs() < floor) {
            reason[0] = fast
                    ? "control_hz " + controlHz + " too high: period " + periodMs() + " ms < " + FAST_MIN_PERIOD_MS
                            + " ms (chest protocol move-time floor)"
                    : "control_hz " + controlHz + " is a high-rate policy (period " + periodMs() + " ms < "
                            + MIN_PERIOD_MS + " ms stable-gait floor): real run needs fast=true (experimental)";
            return false;
        }
        if (sha256.isEmpty()) {
            reason[0] = "manifest has no onnx_sha256 (unsigned policy: dry-run only)";
            return false;
        }
        reason[0] = "";
        return true;
    }

    /** policy 名＝目錄名：英數開頭，禁止 ".."（防路徑穿越）。 */
    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,47}") && !n.contains("..");
    }

    /** 驗證欄位；唔合法 throw IllegalArgumentException（訊息直接俾 API 用）。 */
    public void validate() {
        if (schema != 1) throw new IllegalArgumentException("unsupported manifest schema " + schema);
        if (!validName(name)) {
            throw new IllegalArgumentException("bad name (start with a letter/digit, then A-Za-z0-9._- , 1-48 chars, no \"..\")");
        }
        if (!"stand".equals(obs) && !"walk".equals(obs) && !"getup".equals(obs) && !"unified".equals(obs)) throw new IllegalArgumentException("obs must be stand|walk|getup|unified");
        int want = "stand".equals(obs) ? Alpha2PolicyIo.OBS_STAND : "unified".equals(obs) ? Alpha2PolicyIo.OBS_UNIFIED : Alpha2PolicyIo.OBS_WALK;
        if (obsDim != want) throw new IllegalArgumentException("obs_dim " + obsDim + " != " + want + " for obs=" + obs);
        if (actionDim != Alpha2PolicyIo.N) throw new IllegalArgumentException("action_dim must be 20");
        if (!(actScale > 0 && actScale <= 0.6)) throw new IllegalArgumentException("act_scale must be in (0,0.6] rad");
        if (!(controlHz > 0 && controlHz <= 100)) throw new IllegalArgumentException("control_hz must be in (0,100]");
        if (!(moveOverlap >= 1.0 && moveOverlap <= 1.5)) throw new IllegalArgumentException("move_overlap must be in [1,1.5]");
        Alpha2PolicyIo.parseAxes(accelAxes);
        String t = effectiveTask();
        boolean getupTask = "getup_back".equals(t) || "getup_front".equals(t);
        if ("unified".equals(obs)) {
            boolean ok = false;
            for (String u : UNIFIED_TASKS) if (u.equals(t)) ok = true;
            if (!ok) throw new IllegalArgumentException("unified policy needs task " + java.util.Arrays.toString(UNIFIED_TASKS));
            if (("walk_ref".equals(t) || getupTask) && (ref == null || ref.isEmpty())) throw new IllegalArgumentException(t + " policy needs ref");
            if (!(gaitPeriodS >= 0.2 && gaitPeriodS <= 10)) throw new IllegalArgumentException("gait_period_s must be 0.2..10");
            if (cmdMax == null || cmdMax.length != 3) throw new IllegalArgumentException("cmd_max needs 3 values");
            for (double c : cmdMax) if (!(c >= 0 && c <= 2)) throw new IllegalArgumentException("cmd_max values must be 0..2");
        } else {
            if (("walk".equals(obs) || "getup".equals(obs)) && (ref == null || ref.isEmpty())) throw new IllegalArgumentException(obs + " policy needs ref");
            if ("getup".equals(obs)) {
                if (!getupTask) throw new IllegalArgumentException("getup policy needs task getup_back|getup_front");
            } else if (!t.equals(obs) && !("walk".equals(obs) && "walk_ref".equals(t))) {
                throw new IllegalArgumentException("task " + t + " does not match obs " + obs);
            }
        }
        if (!(holdS >= 0 && holdS <= 30)) throw new IllegalArgumentException("hold_s must be 0..30");
        if (!then.isEmpty() && !validName(then)) throw new IllegalArgumentException("bad 'then' policy name");
        if (!then.isEmpty() && !getupTask) throw new IllegalArgumentException("'then' only applies to getup policies");
        if (ref != null && !ref.isEmpty() && !ref.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("bad ref filename");
        if (!(maxSpeedDps > 0 && maxSpeedDps <= MAX_SPEED_DPS_CAP)) throw new IllegalArgumentException("max_speed_dps must be in (0," + MAX_SPEED_DPS_CAP + "]");
        if (maxRunS < 1 || maxRunS > 600) throw new IllegalArgumentException("max_run_s must be 1..600");
        if (leaseMs < 500 || leaseMs > 60000) throw new IllegalArgumentException("lease_ms must be 500..60000");
        if (!(tiltLimitDeg >= 10 && tiltLimitDeg <= 60)) throw new IllegalArgumentException("tilt_limit_deg must be 10..60");
        if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("onnx_sha256 must be 64 lowercase hex chars");
    }
}
