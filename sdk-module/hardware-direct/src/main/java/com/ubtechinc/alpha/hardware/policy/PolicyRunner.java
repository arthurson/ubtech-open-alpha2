package com.ubtechinc.alpha.hardware.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Policy 控制迴圈（純 Java；由 {@code UbxPlayer.startTask} 放入 play 線程執行，所以原有
 * {@code ubxPlayer.stop()} 搶佔／{@code isPlaying()} 判斷自動適用，同 {@code GaitWalker} 一樣）。
 *
 * <p>每個 period：讀重力 → 砌 obs → {@link OnnxMlp#run} → 目標 q → 速度包絡 → 送 servo。
 * Policy 輸出<b>只係目標 waypoint</b>：舵機自己喺 move 時間內補間（move＝period×overlap），
 * 唔係 Microduck 嗰種每 20ms 直接位置閉環——實機 50Hz 測試會亂震，一般運行下限 200ms，見 {@code docs/onnx-policy.md}。</p>
 *
 * <p>安全（全部喺呢度，policy 輸出唔可信）：
 * <ul>
 *   <li>目標 clamp 入校準範圍；非有限數值（NaN/Inf）即停（{@code bad_action}）。</li>
 *   <li>速度包絡：每格每軸變化 ≤ maxSpeedDps × move時間，防第一步由開機姿勢直接跳去 policy 目標。</li>
 *   <li>傾角連續 {@code fallConsecutive}（預設 3）格超過 {@code tiltLimitRad}（同訓練 env 嘅 fallen 準則
 *       40° 一致）即停（{@code fall}），舵機保持末位姿。</li>
 *   <li>重力讀數連續失效 {@code maxBadGravity} 次即停（{@code gravity_invalid}）。</li>
 *   <li>租約：超過 leaseMs 冇 {@link #beat()} 即停（{@code lease}）；上限 maxRunMs（{@code max_run}）。</li>
 *   <li>送出連續失敗 5 次即停（{@code send_failed}）；線程被 interrupt＝硬停（{@code stopped}）。</li>
 *   <li>節拍：最近 {@code overrunWindow} 格內遲到（遲過 period/2）達 {@code overrunLimit} 格即停
 *       （{@code overrun}）——高頻（50Hz）下算或送唔切，繼續跑只會令 policy 喺錯嘅時序下控制機身。</li>
 * </ul>
 * 任何停止都係「舵機保持末位姿」，唔會自動收步（同 {@code ubx/stop} 語義一致）。</p>
 *
 * <p>get-up policy（{@link Config#getup}）：由躺低企返起身。起步前（真機）要確認真係躺住喺啱嘅一面（否則 {@code not_lying}，
 * 唔郁舵機）；先用速度包絡擺去 ref 第 0 格（≥1 秒，同訓練 SETTLE 一致），之後 ref 唔循環、播完維持末格；ref 播緊期間唔查跌倒，
 * 播完後再撐 {@code hold_s} 秒先完成：有 {@code then} 就交俾嗰個預載 policy，否則 {@code done} 停機（舵機保持企姿）。
 * 播完後仍然傾倒（傾角連續超標）照舊 {@code fall}，等於「冇企起身」。</p>
 *
 * <p>跌倒自動起身（opt-in，{@link #enableAutoRecover}）：跌低並穩定躺住就切去對應嘅 get-up policy，完成後按 {@code then} 交接。</p>
 *
 * <p>多 policy 預載＋即時切換（Microduck 式 hot-swap）：runner 持有一組 {@link Slot}（已載入嘅 policy，
 * 各自帶自己嘅 {@link Config}：period／act_scale／obs 類型／ref／速度包絡），同一時間只有一個 slot 控制機身。
 * {@link #requestSwitch} 喺下一格開頭生效（唔打斷進行中嘅格）：換入新 slot 嘅 period／move 時間／速度包絡，
 * walk ref 由頭（起步段）開始，而 {@code prev action} 會由上一格嘅目標 q 換算成新 policy 嘅 action 單位
 * （{@link #convertPrev}），令新 policy 見到同上一個目標一致嘅「上一個動作」。Lease／max_run／overrun 等會話級
 * 設定沿用起步 slot。</p>
 */
public final class PolicyRunner implements Runnable {

    public interface Sender { boolean send(int[] angles20, int timeMs); }

    /** 回加速度計 [x,y,z]（m/s²，已平滑）；讀數過期／冇 sensor 回 null。 */
    public interface AccelSource { double[] accel(); }

    /** 當前命令位姿（20 軸 servo 角度）；未知回 null。 */
    public interface PoseSource { int[] pose(); }

    /** walk 殘差 policy 用嘅參考動作（rl/ref/*.json 嘅 q，rad，每行 20）。 */
    public static final class Ref {
        final double[][] q;
        final int loopA, loopB;
        final double dtSec;                  // ref 每格時間（ref.json 嘅 dt，URDF 嘅係 0.02）
        public Ref(double[][] q, int loopA, int loopB) { this(q, loopA, loopB, 0.02); }
        public Ref(double[][] q, int loopA, int loopB, double dtSec) {
            if (q == null || q.length == 0) throw new IllegalArgumentException("empty ref");
            if (loopA < 0 || loopB <= loopA || loopB > q.length) throw new IllegalArgumentException("bad ref loop");
            for (double[] r : q) if (r.length != Alpha2PolicyIo.N) throw new IllegalArgumentException("ref row != 20");
            if (!(dtSec >= 0.001 && dtSec <= 1.0)) throw new IllegalArgumentException("bad ref dt " + dtSec);
            this.q = q; this.loopA = loopA; this.loopB = loopB; this.dtSec = dtSec;
        }
        /** 每個 policy 步 ref 前進幾多格（period/dt；50Hz＋dt 0.02＝1，250ms＝12.5）。 */
        double framesPerStep(int periodMs) { return periodMs / (dtSec * 1000.0); }
        /** 位置 pos（格，可帶小數）嘅 ref；loop 段循環，格間線性內插。 */
        double[] at(double pos) {
            int len = loopB - loopA;
            if (pos >= loopB) pos = loopA + ((pos - loopA) % len);
            int i = (int) Math.floor(pos);
            double f = pos - i;
            if (f < 1e-9) return q[i];
            int j = i + 1 < loopB ? i + 1 : loopA;
            double[] a = q[i], b = q[j], o = new double[Alpha2PolicyIo.N];
            for (int k = 0; k < o.length; k++) o[k] = a[k] + f * (b[k] - a[k]);
            return o;
        }
        double phase(double pos) { return pos >= loopA ? 2 * Math.PI * (pos - loopA) / (loopB - loopA) : -1; }
        /** get-up 用（唔循環）：位置 pos 嘅 ref，格間線性內插，超出末格就維持末格姿勢（同 GetUpEnv.ref_q 一致）。 */
        double[] atHold(double pos) {
            int last = q.length - 1;
            if (pos <= 0) return q[0];
            if (pos >= last) return q[last];
            int i = (int) Math.floor(pos);
            double f = pos - i;
            if (f < 1e-9) return q[i];
            double[] a = q[i], b = q[i + 1], o = new double[Alpha2PolicyIo.N];
            for (int k = 0; k < o.length; k++) o[k] = a[k] + f * (b[k] - a[k]);
            return o;
        }
        /** ref 總格數 T。 */
        public int frames() { return q.length; }
        public double dtSec() { return dtSec; }
    }

    /** 設定（由 manifest 轉入）。 */
    public static final class Config {
        public int periodMs = 400;
        public float moveOverlap = 1.05f;
        public double actScale = 0.3;
        public int obsDim = Alpha2PolicyIo.OBS_STAND;
        public int[] axes = {1, 2, 3};
        public int[] lo, hi;                       // servo ID 1..20 校準範圍
        public double maxSpeedDps = 90.0;          // 官方步態峰值 60°/s
        public double tiltLimitRad = Math.toRadians(40);
        public int maxBadGravity = 3;
        public long leaseMs = 5000;
        public long maxRunMs = 60000;
        public boolean dry = false;                // true＝只計算唔送 servo
        public int overrunWindow = 50;             // 最近幾多格內計遲到
        public int overrunLimit = 10;              // 遲到格數達呢個值即停（"overrun"）
        public int fallConsecutive = 3;            // 連續幾多格傾角超標先當 fall（濾走單次尖峰）
        public Ref ref;                            // obsDim==65 先用
        // 統一格式（obs 68，ONNX 兩個輸出 action＋delta；target＝基準＋delta，見 Alpha2PolicyIo.obsUnified）
        public boolean unified = false;
        public String task = "";                   // stand｜walk｜walk_ref｜getup_back｜getup_front
        public double gaitPeriodS = 1.0;           // stand／walk 嘅自由運行時鐘週期（秒）
        public double[] cmdMax = {0.05, 0.03, 0.4};   // walk 嘅速度指令上限
        public double getupEndFrames = 0;          // get-up「ref 完」喺 ref 格數嘅位置（0＝ref 總格數；統一格式＝T×ctrl_dt/ref.dt）
        // get-up（由躺低企返起身；ref 唔循環，obs 尾二格係 [progress, over]）
        public boolean getup = false;
        public String lying = "";                  // 要求起步時躺住嘅方向 "back"／"front"（真機先查；dry 唔查）
        public double holdS = 3.0;                 // ref 播完後再撐幾耐先當完成
        public String then = "";                   // 完成後交俾嘅預載 slot 名（空＝停機："done"）
    }

    /** get-up 起步前先慢慢擺去 ref 第 0 格（同訓練 env 嘅 SETTLE 一致：先喺地上守 1 秒），最長等咁耐。 */
    static final long GETUP_SETTLE_MIN_MS = 1000, GETUP_SETTLE_MAX_MS = 6000;

    /** 一個已載入嘅 policy（名＋模型＋佢自己嘅設定）。同一 runner 內 name 唯一。 */
    public static final class Slot {
        public final String name;
        public final OnnxMlp model;
        public final Config cfg;
        public Slot(String name, OnnxMlp model, Config cfg) {
            if (name == null || name.isEmpty() || model == null || cfg == null) throw new IllegalArgumentException("null arg");
            this.name = name; this.model = model; this.cfg = cfg;
        }
    }

    static final String DEFAULT_SLOT = "policy";
    private static final int SWITCH_LOG_N = 16;

    private final CopyOnWriteArrayList<Slot> slots = new CopyOnWriteArrayList<Slot>();
    private volatile Slot active;                  // 而家控制機身嘅 slot
    private volatile String pendingSwitch;         // 下一格開頭要換入嘅 slot 名（null＝冇）
    private volatile int switches;                 // 已生效嘅切換次數
    private volatile int periodOverride;           // 決策週期覆寫（ms；0＝用各 policy 自己嘅 period）
    // 跌倒自動起身（opt-in）：跌低後唔再行 policy／唔送 servo，穩定躺住 dwell 毫秒先切去對應嘅 get-up slot
    private volatile String recoverBack, recoverFront;
    private volatile int recoverMax = 0;               // 每次運行最多自動起身幾多次（0＝冇啟用）
    private volatile long recoverDwellMs = 1500, recoverGiveUpMs = 10000;
    private volatile int recoveries;
    private volatile boolean fallen;
    private volatile int effPeriodMs;              // 而家實際用緊嘅決策週期
    /** 覆寫週期嘅合法範圍（ms）：下限＝chest 協議做得到嘅最短 move（20），上限 1000；面板／API 再收窄。 */
    public static final int OVERRIDE_MIN_MS = 20, OVERRIDE_MAX_MS = 1000;
    private final List<String> switchLog = new ArrayList<String>();   // 最近 SWITCH_LOG_N 次："step:from>to"
    private final Config cfg;                      // 會話級設定（lease／max_run／dry／overrun…），取自起步 slot
    private final Sender sender;
    private final AccelSource accel;
    private final PoseSource poseSrc;

    private volatile long lastBeatMs;
    private volatile boolean running;
    private volatile String endReason = "";
    private volatile int steps;
    private volatile double tiltDeg;
    private volatile float[] lastAction = new float[Alpha2PolicyIo.N];
    private volatile int[] lastServo = new int[Alpha2PolicyIo.N];
    private volatile long lastInferUs;
    // 診斷 trace：最近 TRACE_N 步，每步 11 欄（見 TRACE_COLS）；停低後俾面板顯示／複製。
    public static final int TRACE_N = 64;
    public static final String[] TRACE_COLS = {"step", "tiltDeg", "gx", "gy", "gz", "ax", "ay", "az", "maxAct", "maxDeltaDeg", "stepMs"};
    private final float[][] trace = new float[TRACE_N][TRACE_COLS.length];
    private int traceCount = 0;
    private volatile int overruns;          // 累計遲到格數（遲過 period/2）
    private volatile long maxStepUs;        // 單格（推論＋送出）最長耗時
    private volatile long maxSendUs;        // 單次送出最長耗時

    /** 單 policy（舊 API）：等同只有一個叫 {@value #DEFAULT_SLOT} 嘅 slot。 */
    public PolicyRunner(OnnxMlp model, Config cfg, Sender sender, AccelSource accel, PoseSource pose) {
        this(singleSlot(model, cfg), DEFAULT_SLOT, sender, accel, pose);
    }

    private static List<Slot> singleSlot(OnnxMlp model, Config cfg) {
        if (model == null || cfg == null) throw new IllegalArgumentException("null arg");
        List<Slot> l = new ArrayList<Slot>();
        l.add(new Slot(DEFAULT_SLOT, model, cfg));
        return l;
    }

    /** 多 policy：{@code init} 係預載嘅 slot（名唯一），{@code activeName} 係起步嗰個。 */
    public PolicyRunner(List<Slot> init, String activeName, Sender sender, AccelSource accel, PoseSource pose) {
        if (init == null || init.isEmpty() || activeName == null || sender == null || accel == null || pose == null) {
            throw new IllegalArgumentException("null arg");
        }
        Slot start = null;
        for (Slot sl : init) {
            checkSlot(sl);
            for (Slot o : slots) if (o.name.equals(sl.name)) throw new IllegalArgumentException("duplicate slot " + sl.name);
            slots.add(sl);
            if (sl.name.equals(activeName)) start = sl;
        }
        if (start == null) throw new IllegalArgumentException("active slot not preloaded: " + activeName);
        this.active = start;
        this.cfg = start.cfg;
        this.effPeriodMs = start.cfg.periodMs;
        this.sender = sender; this.accel = accel; this.poseSrc = pose;
    }

    /** 每個 slot 嘅設定驗證（同舊單 policy 構造器一致）。 */
    private static void checkSlot(Slot sl) {
        Config cfg = sl.cfg;
        OnnxMlp model = sl.model;
        if (cfg.lo == null || cfg.hi == null || cfg.lo.length < 20 || cfg.hi.length < 20) {
            throw new IllegalArgumentException("need servo limits for 20 axes");
        }
        if (cfg.obsDim != model.inputDim()) {
            throw new IllegalArgumentException("config obs_dim " + cfg.obsDim + " != model input " + model.inputDim());
        }
        if (model.outputDim() != Alpha2PolicyIo.N) {
            throw new IllegalArgumentException("model output " + model.outputDim() + " != 20 actions");
        }
        if (cfg.obsDim == Alpha2PolicyIo.OBS_WALK && cfg.ref == null) {
            throw new IllegalArgumentException("walk policy (obs 65) needs a ref");
        }
        if (cfg.getup && !cfg.unified && cfg.obsDim != Alpha2PolicyIo.OBS_WALK) throw new IllegalArgumentException("getup policy needs obs 65");
        if (cfg.getup && !(cfg.holdS >= 0)) throw new IllegalArgumentException("hold_s < 0");
        if (cfg.unified) {
            if (cfg.obsDim != Alpha2PolicyIo.OBS_UNIFIED) throw new IllegalArgumentException("unified policy needs obs 68");
            if (!model.hasOutput("action") || !model.hasOutput("delta")
                    || model.outputDim("action") != Alpha2PolicyIo.N || model.outputDim("delta") != Alpha2PolicyIo.N) {
                throw new IllegalArgumentException("unified policy needs outputs 'action' and 'delta' (20 each)");
            }
            boolean refTask = "walk_ref".equals(cfg.task) || cfg.getup;
            if (refTask && cfg.ref == null) throw new IllegalArgumentException(cfg.task + " policy needs a ref");
            if ("walk_ref".equals(cfg.task) && cfg.getup) throw new IllegalArgumentException("walk_ref is not a get-up task");
            if (cfg.cmdMax == null || cfg.cmdMax.length != 3) throw new IllegalArgumentException("cmd_max needs 3 values");
            if (!(cfg.gaitPeriodS > 0)) throw new IllegalArgumentException("gait_period_s must be > 0");
        } else if (cfg.obsDim != Alpha2PolicyIo.OBS_STAND && cfg.obsDim != Alpha2PolicyIo.OBS_WALK) {
            throw new IllegalArgumentException("unsupported obs_dim " + cfg.obsDim + " (43 or 65; 68 = unified)");
        }
        if (cfg.periodMs < 1) throw new IllegalArgumentException("period_ms < 1");
    }

    // -- 多 policy：預載／切換 ------------------------------------------------

    private Slot find(String name) {
        for (Slot s : slots) if (s.name.equals(name)) return s;
        return null;
    }

    /** 而家控制機身嘅 slot 名。 */
    public String activeName() { return active.name; }
    /** 已排隊、下一格先生效嘅切換目標（冇＝null）。 */
    public String pendingSwitch() { return pendingSwitch; }
    /** 已生效嘅切換次數。 */
    public int switches() { return switches; }
    /** 預載咗嘅 slot 名（載入次序）。 */
    public List<String> slotNames() {
        List<String> n = new ArrayList<String>();
        for (Slot s : slots) n.add(s.name);
        return n;
    }
    /** 最近嘅切換記錄（舊→新），每項 "step:from>to"。 */
    public synchronized List<String> switchLog() { return new ArrayList<String>(switchLog); }

    /**
     * 要求喺下一格開頭切換去已預載嘅 slot。回空字串＝已接受（切去而家嗰個＝冇事做）；否則係拒絕原因。
     * 唔會喺呢度打斷進行中嘅格——由控制線程喺格與格之間換入，所以任何線程都可以安全呼叫。
     */
    public String requestSwitch(String name) {
        if (name == null || find(name) == null) return "policy not preloaded in this run: " + name;
        if (!running) return "not running";
        pendingSwitch = name.equals(active.name) ? null : name;
        return "";
    }

    /**
     * 決策週期覆寫（ms）：套用於所有 slot，下一格開頭生效，運行前後都可以設。0＝清除（用各 policy 自己嘅 period）。
     * Ref 仍然按真實時間前進（每步 period/dt 格），所以只係「幾耐決定一次」變，動作軌跡嘅牆鐘速度唔變；
     * move 時間、速度包絡（°/步）、settle 步數同遲到判定都跟新週期。回空字串＝接受，否則係原因。
     */
    public String requestPeriod(int ms) {
        if (ms != 0 && (ms < OVERRIDE_MIN_MS || ms > OVERRIDE_MAX_MS)) {
            return "period must be 0 (policy's own) or " + OVERRIDE_MIN_MS + ".." + OVERRIDE_MAX_MS + " ms";
        }
        periodOverride = ms;
        return "";
    }
    public int periodOverrideMs() { return periodOverride; }

    /**
     * 啟用「跌倒自動起身」：行（非 get-up）policy 時如果跌低（傾角連續超標）並且穩定躺住（back／front）{@code dwell} 毫秒，
     * 就切去對應嘅 get-up slot（{@code backSlot}／{@code frontSlot}，至少一個，要係已預載、{@code getup}、躺邊面要啱）；
     * get-up 完成後照 manifest 嘅 {@code then} 交接。跌低期間唔行 policy、唔送 servo（舵機保持末位姿）。
     * 每次運行最多 {@code max} 次（get-up 後仍然傾倒都計），用晒或者冇對應 slot／躺側面／等咗 giveUp 都照舊 {@code fall} 停機。
     * 起步嗰格就已經傾倒仍然即停（唔係「跌」而係一開始就倒咗）。預設唔啟用——見 docs（加速度計軸映射未驗證時，
     * 誤判跌倒會喺企住時啟動起身）。
     */
    public synchronized void enableAutoRecover(String backSlot, String frontSlot, int max) {
        if (max < 1) throw new IllegalArgumentException("recover max must be >= 1");
        if (backSlot == null && frontSlot == null) throw new IllegalArgumentException("auto recover needs a getup slot");
        checkRecoverSlot(backSlot, Alpha2PolicyIo.LYING_BACK);
        checkRecoverSlot(frontSlot, Alpha2PolicyIo.LYING_FRONT);
        recoverBack = backSlot; recoverFront = frontSlot; recoverMax = max;
    }

    private void checkRecoverSlot(String name, String side) {
        if (name == null) return;
        Slot sl = find(name);
        if (sl == null) throw new IllegalArgumentException("recover slot not preloaded: " + name);
        if (!sl.cfg.getup || !side.equals(sl.cfg.lying)) throw new IllegalArgumentException("slot " + name + " is not a getup_" + side + " policy");
    }

    /** 自動起身嘅時間參數（ms）：dwell＝穩定躺住幾耐先起身；giveUp＝跌低後等到幾耐仲未能起身就停機。 */
    public void setRecoverTiming(long dwellMs, long giveUpMs) {
        if (dwellMs < 0 || giveUpMs < dwellMs) throw new IllegalArgumentException("bad recover timing");
        recoverDwellMs = dwellMs; recoverGiveUpMs = giveUpMs;
    }
    public boolean autoRecover() { return recoverMax > 0; }
    /** 已自動起身（含重試）幾多次。 */
    public int recoveries() { return recoveries; }
    /** 而家係咪處於「跌低等起身」狀態（唔行 policy）。 */
    public boolean fallen() { return fallen; }
    /** 而家實際用緊嘅決策週期（ms）。 */
    public int effectivePeriodMs() { return effPeriodMs; }
    private int effPeriod(Config c) { int o = periodOverride; return o > 0 ? o : c.periodMs; }

    /** 加入（或取代同名但未被用緊嘅）slot；而家用緊／排緊切換嘅唔准取代，throw IllegalStateException。 */
    public synchronized void putSlot(Slot sl) {
        checkSlot(sl);
        Slot old = find(sl.name);
        if (old != null) {
            if (old == active || sl.name.equals(pendingSwitch)) throw new IllegalStateException("slot in use: " + sl.name);
            slots.remove(old);
        }
        slots.add(sl);
    }

    /** 移除 slot；而家用緊／排緊切換嘅唔准，throw IllegalStateException。冇呢個 slot 回 false。 */
    public synchronized boolean removeSlot(String name) {
        Slot old = find(name);
        if (old == null) return false;
        if (old == active || name.equals(pendingSwitch)) throw new IllegalStateException("slot in use: " + name);
        slots.remove(old);
        return true;
    }

    /**
     * 切換時嘅「上一個動作」換算：新 policy 要見到同上一個目標 q 一致嘅 prev action，即
     * {@code (lastTarget - newBase) / newActScale}，clip 到 [-1,1]（超出範圍＝新 policy 夠唔到嗰個目標）。
     */
    static float[] convertPrev(double[] lastTarget, double[] newBase, double newActScale) {
        float[] p = new float[Alpha2PolicyIo.N];
        if (lastTarget == null) return p;
        for (int i = 0; i < p.length; i++) {
            double a = (lastTarget[i] - newBase[i]) / newActScale;
            if (a > 1) a = 1;
            if (a < -1) a = -1;
            p[i] = (float) a;
        }
        return p;
    }

    public void beat() { lastBeatMs = System.currentTimeMillis(); }

    /**
     * 起線程前先標記「運行中」：否則 startTask 返回後、線程真正跑起前嘅一刻 isRunning() 係 false，
     * 面板會以為冇運行而唔啟動心跳（租約 5 秒後過期＝「行咗幾步就停」嘅成因之一）。
     * startTask 失敗要 {@link #cancelStart()}。
     */
    public void markStarting() { endReason = ""; lastBeatMs = System.currentTimeMillis(); running = true; }

    public void cancelStart() { running = false; }
    public boolean isRunning() { return running; }
    public String endReason() { return endReason; }
    public int steps() { return steps; }
    public double tiltDeg() { return tiltDeg; }
    public float[] lastAction() { return lastAction.clone(); }
    public int[] lastServo() { return lastServo.clone(); }
    public long lastInferUs() { return lastInferUs; }
    public int overruns() { return overruns; }

    /** 最近（最多 TRACE_N）步嘅診斷記錄，由舊到新；非有限值＝NaN。 */
    public synchronized float[][] trace() {
        int n = Math.min(traceCount, TRACE_N);
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) out[i] = trace[(traceCount - n + i) % TRACE_N].clone();
        return out;
    }

    private synchronized float[] traceRow() {
        float[] r = trace[traceCount % TRACE_N];
        java.util.Arrays.fill(r, Float.NaN);
        traceCount++;
        return r;
    }
    public long maxStepUs() { return maxStepUs; }
    public long maxSendUs() { return maxSendUs; }
    public Config config() { return active.cfg; }

    @Override
    public void run() {
        running = true;
        endReason = "";
        long t0 = System.currentTimeMillis();
        lastBeatMs = t0;
        try {
            endReason = loop(t0);
        } catch (InterruptedException e) {
            endReason = "stopped";
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            endReason = "error: " + e;
        } finally {
            running = false;
        }
    }

    /** policy 嘅殘差基準：walk＝ref 位置 pos 嘅 q，stand＝預設姿勢 q0。 */
    private static double[] baseOf(Config c, double pos, double[] q0) {
        if (c.getup) return c.ref.atHold(pos);
        return refLoop(c) ? c.ref.at(pos) : q0;
    }

    /** 統一 obs：phase 特徵按 task（stand／walk＝自由時鐘；walk_ref＝ref 循環相位；get-up＝進度）。 */
    private float[] unifiedObs(Config c, double[] q, float[] prev, double[] g, double pos, double clockS, double[] q0) {
        double p0, p1;
        if (c.getup) {
            double end = getupEnd(c);
            p0 = Math.min(pos / end, 1.0);
            p1 = pos >= end ? 1.0 : 0.0;
        } else if (refLoop(c)) {
            double ph = c.ref.phase(pos);                    // 未入循環窗口＝-1
            p0 = ph < 0 ? 0.0 : Math.sin(ph);
            p1 = ph < 0 ? 0.0 : Math.cos(ph);
        } else {
            double ph = 2 * Math.PI * clockS / c.gaitPeriodS;
            p0 = Math.sin(ph);
            p1 = Math.cos(ph);
        }
        return Alpha2PolicyIo.obsUnified(q, prev, g, commandFor(c), p0, p1, baseOf(c, pos, q0));
    }

    /** 基準目標跟循環 ref：舊格式 walk（obs 65）／統一 walk_ref。 */
    private static boolean refLoop(Config c) {
        return c.unified ? "walk_ref".equals(c.task) : (!c.getup && c.obsDim == Alpha2PolicyIo.OBS_WALK);
    }

    /** get-up 嘅「ref 完」位置（ref 格）：統一格式＝T 個控制步；舊格式＝ref 總格數。 */
    private static double getupEnd(Config c) { return c.getupEndFrames > 0 ? c.getupEndFrames : c.ref.frames(); }

    // -- 速度指令（統一格式 walk）：視乎 setCommand 嘅時間，過咗 timeout 自動歸零（防止客戶端死咗仲一路行）
    private volatile double[] cmdRaw = new double[3];
    private volatile long cmdAtMs = 0;
    private volatile long cmdTimeoutMs = 1500;

    /** 設定速度指令（vx m/s 向前、vy m/s 向左、wz rad/s 逆時針）；各軸按而家 policy 嘅 cmd_max 截斷，要持續更新（見 timeout）。 */
    public void setCommand(double vx, double vy, double wz) {
        if (Double.isNaN(vx) || Double.isNaN(vy) || Double.isNaN(wz)) throw new IllegalArgumentException("command is NaN");
        cmdRaw = new double[] {vx, vy, wz};
        cmdAtMs = System.currentTimeMillis();
    }
    public void setCommandTimeoutMs(long ms) {
        if (ms < 100) throw new IllegalArgumentException("command timeout < 100 ms");
        cmdTimeoutMs = ms;
    }
    public long commandTimeoutMs() { return cmdTimeoutMs; }

    /** 而家實際用嘅指令：只有統一格式 walk 用；過期歸零；按該 policy 嘅 cmd_max 截斷。 */
    public double[] command() { return commandFor(active.cfg); }

    private double[] commandFor(Config c) {
        double[] z = new double[3];
        if (!c.unified || !"walk".equals(c.task)) return z;
        if (System.currentTimeMillis() - cmdAtMs > cmdTimeoutMs) return z;
        double[] r = cmdRaw;
        for (int i = 0; i < 3; i++) z[i] = Math.max(-c.cmdMax[i], Math.min(c.cmdMax[i], r[i]));
        return z;
    }

    private String loop(long t0) throws InterruptedException {
        int[] cur = poseSrc.pose();
        if (cur == null) return "pose_unknown";
        cur = cur.clone();
        float[] prev = new float[Alpha2PolicyIo.N];
        double[] q0 = Alpha2PolicyIo.qDefault();
        double[] gLast = null;
        int badG = 0, sendFails = 0, t = 0, fallRun = 0;
        synchronized (this) { traceCount = 0; }
        Slot sl = active;
        Config sc = sl.cfg;                   // 而家 slot 嘅設定（切換時換）
        int curP = effPeriod(sc);              // 而家嘅決策週期（可被覆寫，運行中可改）
        effPeriodMs = curP;
        int moveMs = Math.max(20, Math.round(curP * sc.moveOverlap));
        double maxStepDeg = sc.maxSpeedDps * moveMs / 1000.0;
        // walk：ref 隨真實時間推進（period/dt 格／步），唔係固定一格——period 同 ref.dt 唔同都對得上。
        double framesPerStep = sc.ref != null ? sc.ref.framesPerStep(curP) : 1.0;
        double refPos = 0.0;                  // ref 位置（格）：逐步累加，運行中改週期都唔會跳
        double clockS = 0.0;                  // 統一格式 stand／walk 嘅自由運行時鐘（秒，真實時間）
        int ts = 0;                           // 呢個 slot 入面嘅步數（ref 位置用；切換時歸零）
        boolean settling = sc.getup;          // get-up：先擺去 ref 第 0 格（唔行 policy、ref 唔前進）
        int settleSteps = 0;
        boolean restartSame = false;          // 自動起身重試：同一個 get-up slot 由頭再嚟
        String lyingNow = "";                 // 跌低等起身：而家穩定緊嘅躺法同開始時間
        long lyingSince = 0, fallenSince = 0;
        double[] lastTarget = null;           // 上一格 policy 嘅目標 q（切換時換算 prev action）
        long next = System.currentTimeMillis();
        boolean[] lateRing = new boolean[Math.max(1, cfg.overrunWindow)];
        int ringIdx = 0, lateInWindow = 0;
        while (true) {
            long s0 = System.nanoTime();
            if (Thread.interrupted()) throw new InterruptedException();
            long now = System.currentTimeMillis();
            if (now - t0 > cfg.maxRunMs) return "max_run";
            if (cfg.leaseMs > 0 && now - lastBeatMs > cfg.leaseMs) return "lease";

            // 切換：喺格開頭生效（上一格嘅 move 已經送出，唔會打斷）。
            String pend = pendingSwitch;
            if (pend != null) {
                pendingSwitch = null;
                Slot ns = find(pend);
                if (ns != null && (ns != sl || restartSame)) {
                    restartSame = false;
                    Config nc = ns.cfg;
                    prev = convertPrev(lastTarget, baseOf(nc, 0, q0), nc.actScale);
                    String from = sl.name;
                    sl = ns; sc = nc; active = ns;
                    curP = effPeriod(sc); effPeriodMs = curP;
                    moveMs = Math.max(20, Math.round(curP * sc.moveOverlap));
                    maxStepDeg = sc.maxSpeedDps * moveMs / 1000.0;
                    framesPerStep = sc.ref != null ? sc.ref.framesPerStep(curP) : 1.0;
                    ts = 0; refPos = 0.0; clockS = 0.0;
                    settling = nc.getup; settleSteps = 0;
                    fallRun = 0;
                    lastTarget = null;
                    next = now;                                   // period 變咗：重設節拍同遲到統計
                    java.util.Arrays.fill(lateRing, false);
                    lateInWindow = 0;
                    switches++;
                    synchronized (this) {
                        if (switchLog.size() >= SWITCH_LOG_N) switchLog.remove(0);
                        switchLog.add(t + ":" + from + ">" + ns.name);
                    }
                }
            }

            // 決策週期被改（覆寫／清除）：下一格開頭生效；ref 位置係累加嘅，所以軌跡連續。
            int wantP = effPeriod(sc);
            if (wantP != curP) {
                curP = wantP; effPeriodMs = curP;
                moveMs = Math.max(20, Math.round(curP * sc.moveOverlap));
                maxStepDeg = sc.maxSpeedDps * moveMs / 1000.0;
                framesPerStep = sc.ref != null ? sc.ref.framesPerStep(curP) : 1.0;
                next = now;
                java.util.Arrays.fill(lateRing, false);
                lateInWindow = 0;
            }

            // get-up：ref 位置（settle 期間釘喺第 0 格）；ref 播完再撐 hold_s 就完成。
            double pos = settling ? 0.0 : refPos;
            if (sc.getup && !settling) {
                double total = getupEnd(sc) + sc.holdS / sc.ref.dtSec();
                if (pos >= total) {
                    Slot nx = sc.then.isEmpty() ? null : find(sc.then);
                    if (nx != null && nx != sl) { pendingSwitch = nx.name; continue; }   // 交俾 then（下一輪開頭換入）
                    return "done";
                }
            }

            double[] q = Alpha2PolicyIo.servoToQ(cur);
            double[] aRaw = accel.accel();
            double[] g = Alpha2PolicyIo.gravityBase(aRaw, sc.axes, q[18], q[19]);
            float[] row = traceRow();
            row[0] = t;
            if (aRaw != null) { row[5] = (float) aRaw[0]; row[6] = (float) aRaw[1]; row[7] = (float) aRaw[2]; }
            if (g != null) { row[2] = (float) g[0]; row[3] = (float) g[1]; row[4] = (float) g[2]; }
            if (g == null) {
                if (++badG >= cfg.maxBadGravity || gLast == null) return "gravity_invalid";
                g = gLast;
            } else {
                badG = 0;
                gLast = g;
            }
            double tilt = Alpha2PolicyIo.tiltRad(g);
            tiltDeg = Math.toDegrees(tilt);
            row[1] = (float) tiltDeg;
            // 連續 fallConsecutive 格超標先停：單次尖峰（行路時嘅線加速度）唔算跌。
            // 起步嗰格（t==0）已經超標＝開始時已經倒／歪咗，唔准郁，即停。
            // get-up：起步已躺低，ref 播緊期間唔查跌（訓練 env 都唔因跌倒終止）；起步前（真機）要確認真係躺住喺啱嘅一面。
            boolean guRun = sc.getup && pos < getupEnd(sc);
            if (sc.getup && ts == 0 && settleSteps == 0 && !cfg.dry && !sc.lying.equals(Alpha2PolicyIo.lyingKind(g))) return "not_lying";
            if (guRun) {
                fallRun = 0;
            } else if (tilt > sc.tiltLimitRad) {
                if (t == 0) return "fall";
                if (++fallRun >= cfg.fallConsecutive && !fallen) {
                    if (recoverMax <= 0 || recoveries >= recoverMax) return "fall";
                    fallen = true; fallenSince = System.currentTimeMillis(); lyingNow = ""; lyingSince = 0;
                }
            } else {
                fallRun = 0;
                fallen = false;                    // 自己企返起身／俾人扶正：恢復行 policy
            }
            if (fallen) {
                // 跌低等起身：唔行 policy、唔送 servo；穩定躺住（back／front）dwell 毫秒先切去對應 get-up slot。
                long fn = System.currentTimeMillis();
                String ly = Alpha2PolicyIo.lyingKind(g);
                if (ly.isEmpty()) { lyingNow = ""; lyingSince = 0; }
                else if (!ly.equals(lyingNow)) { lyingNow = ly; lyingSince = fn; }
                else if (fn - lyingSince >= recoverDwellMs) {
                    String target = Alpha2PolicyIo.LYING_BACK.equals(ly) ? recoverBack : recoverFront;
                    if (target == null || find(target) == null || recoveries >= recoverMax) return "fall";
                    recoveries++;
                    fallen = false; fallRun = 0;
                    restartSame = target.equals(sl.name);
                    pendingSwitch = target;
                    next = fn;
                    continue;                      // 下一輪開頭換入（同手動 switch 同一條路）
                }
                if (fn - fallenSince >= recoverGiveUpMs) return "fall";
                t++; steps = t;
                next += curP;
                long w = next - System.currentTimeMillis();
                if (w > 0) Thread.sleep(w); else next = System.currentTimeMillis();
                continue;
            }

            float[] obs = sc.unified
                    ? unifiedObs(sc, q, prev, g, pos, clockS, q0)
                    : sc.getup
                    ? Alpha2PolicyIo.obsGetUp(q, prev, g, sc.ref.atHold(pos), pos / getupEnd(sc), pos >= getupEnd(sc))
                    : sc.obsDim == Alpha2PolicyIo.OBS_WALK
                    ? Alpha2PolicyIo.obsWalk(q, prev, g, sc.ref.at(pos), sc.ref.phase(pos))
                    : Alpha2PolicyIo.obsStand(q, prev, g);
            long i0 = System.nanoTime();
            float[] act, delta = null;
            if (settling) {                                   // settle＝零殘差，直接守 ref[0]
                act = new float[Alpha2PolicyIo.N];
                if (sc.unified) delta = new float[Alpha2PolicyIo.N];
            } else if (sc.unified) {
                Map<String, float[]> outs = sl.model.runAll(obs);
                act = outs.get("action");
                delta = outs.get("delta");
                for (float d : delta) if (Float.isNaN(d) || Float.isInfinite(d)) return "bad_action";
            } else {
                act = sl.model.run(obs);
            }
            lastInferUs = (System.nanoTime() - i0) / 1000;
            for (int i = 0; i < act.length; i++) {
                if (Float.isNaN(act[i]) || Float.isInfinite(act[i])) return "bad_action";
                if (act[i] > 1f) act[i] = 1f;
                if (act[i] < -1f) act[i] = -1f;
            }
            float maxAct = 0f;
            for (float x : act) maxAct = Math.max(maxAct, Math.abs(x));
            row[8] = maxAct;
            double[] base = baseOf(sc, pos, q0);
            double[] tq = sc.unified ? Alpha2PolicyIo.targetQDelta(base, delta) : Alpha2PolicyIo.targetQ(base, act, sc.actScale);
            lastTarget = tq;
            int[] want = Alpha2PolicyIo.qToServo(tq, sc.lo, sc.hi);
            int[] cmd = new int[Alpha2PolicyIo.N];
            for (int i = 0; i < cmd.length; i++) {
                long d = want[i] - cur[i];
                long lim = (long) Math.floor(maxStepDeg);
                if (d > lim) d = lim;
                if (d < -lim) d = -lim;
                cmd[i] = (int) (cur[i] + d);
            }
            int md = 0;
            for (int i = 0; i < cmd.length; i++) md = Math.max(md, Math.abs(cmd[i] - cur[i]));
            row[9] = md;
            lastAction = act;
            lastServo = cmd.clone();
            if (!cfg.dry) {
                long d0 = System.nanoTime();
                boolean okSend = sender.send(cmd, moveMs);
                long dus = (System.nanoTime() - d0) / 1000;
                if (dus > maxSendUs) maxSendUs = dus;
                if (okSend) {
                    sendFails = 0;
                } else if (++sendFails >= 5) {
                    return "send_failed";
                }
            }
            // dry＝rehearsal：假設舵機照命令到位，令 obs 繼續推進；真送出就用命令位姿（dead reckoning）。
            cur = cmd;
            prev = act;
            t++;
            if (settling) {
                settleSteps++;
                long ms = (long) settleSteps * curP;
                boolean reached = true;
                for (int i = 0; i < cmd.length; i++) if (Math.abs(cmd[i] - want[i]) > 1) reached = false;
                if ((ms >= GETUP_SETTLE_MIN_MS && reached) || ms >= GETUP_SETTLE_MAX_MS) settling = false;
            } else {
                ts++;
                refPos += framesPerStep;
                clockS += curP / 1000.0;
            }
            steps = t;

            long stepUs = (System.nanoTime() - s0) / 1000;
            row[10] = stepUs / 1000f;
            if (stepUs > maxStepUs) maxStepUs = stepUs;

            next += curP;
            long wait = next - System.currentTimeMillis();
            // 遲到＝比預定時間晚過半個 period（高頻下代表算／送唔切）；視窗內太多即停。
            boolean late = wait < -(curP / 2);
            if (lateRing[ringIdx]) lateInWindow--;
            lateRing[ringIdx] = late;
            if (late) { lateInWindow++; overruns++; }
            ringIdx = (ringIdx + 1) % lateRing.length;
            if (lateInWindow >= cfg.overrunLimit) return "overrun";
            if (wait > 0) Thread.sleep(wait);
            else next = System.currentTimeMillis();          // 遲咗唔追拍（避免連發兩格）
        }
    }
}
