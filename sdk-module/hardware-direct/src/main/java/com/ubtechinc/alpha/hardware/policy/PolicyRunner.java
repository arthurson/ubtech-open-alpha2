package com.ubtechinc.alpha.hardware.policy;

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
    }

    private final OnnxMlp model;
    private final Config cfg;
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

    public PolicyRunner(OnnxMlp model, Config cfg, Sender sender, AccelSource accel, PoseSource pose) {
        if (model == null || cfg == null || sender == null || accel == null || pose == null) {
            throw new IllegalArgumentException("null arg");
        }
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
        if (cfg.obsDim != Alpha2PolicyIo.OBS_STAND && cfg.obsDim != Alpha2PolicyIo.OBS_WALK) {
            throw new IllegalArgumentException("unsupported obs_dim " + cfg.obsDim + " (43 or 65)");
        }
        if (cfg.periodMs < 1) throw new IllegalArgumentException("period_ms < 1");
        this.model = model; this.cfg = cfg; this.sender = sender; this.accel = accel; this.poseSrc = pose;
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
    public Config config() { return cfg; }

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

    private String loop(long t0) throws InterruptedException {
        int[] cur = poseSrc.pose();
        if (cur == null) return "pose_unknown";
        cur = cur.clone();
        float[] prev = new float[Alpha2PolicyIo.N];
        double[] q0 = Alpha2PolicyIo.qDefault();
        double[] gLast = null;
        int badG = 0, sendFails = 0, t = 0, fallRun = 0;
        synchronized (this) { traceCount = 0; }
        int moveMs = Math.max(20, Math.round(cfg.periodMs * cfg.moveOverlap));
        double maxStepDeg = cfg.maxSpeedDps * moveMs / 1000.0;
        // walk：ref 隨真實時間推進（period/dt 格／步），唔係固定一格——period 同 ref.dt 唔同都對得上。
        double framesPerStep = cfg.ref != null ? cfg.ref.framesPerStep(cfg.periodMs) : 1.0;
        long next = System.currentTimeMillis();
        boolean[] lateRing = new boolean[Math.max(1, cfg.overrunWindow)];
        int ringIdx = 0, lateInWindow = 0;
        while (true) {
            long s0 = System.nanoTime();
            if (Thread.interrupted()) throw new InterruptedException();
            long now = System.currentTimeMillis();
            if (now - t0 > cfg.maxRunMs) return "max_run";
            if (cfg.leaseMs > 0 && now - lastBeatMs > cfg.leaseMs) return "lease";

            double[] q = Alpha2PolicyIo.servoToQ(cur);
            double[] aRaw = accel.accel();
            double[] g = Alpha2PolicyIo.gravityBase(aRaw, cfg.axes, q[18], q[19]);
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
            if (tilt > cfg.tiltLimitRad) { if (t == 0 || ++fallRun >= cfg.fallConsecutive) return "fall"; }
            else fallRun = 0;

            float[] obs = cfg.obsDim == Alpha2PolicyIo.OBS_WALK
                    ? Alpha2PolicyIo.obsWalk(q, prev, g, cfg.ref.at(t * framesPerStep), cfg.ref.phase(t * framesPerStep))
                    : Alpha2PolicyIo.obsStand(q, prev, g);
            long i0 = System.nanoTime();
            float[] act = model.run(obs);
            lastInferUs = (System.nanoTime() - i0) / 1000;
            for (int i = 0; i < act.length; i++) {
                if (Float.isNaN(act[i]) || Float.isInfinite(act[i])) return "bad_action";
                if (act[i] > 1f) act[i] = 1f;
                if (act[i] < -1f) act[i] = -1f;
            }
            float maxAct = 0f;
            for (float x : act) maxAct = Math.max(maxAct, Math.abs(x));
            row[8] = maxAct;
            double[] base = cfg.obsDim == Alpha2PolicyIo.OBS_WALK ? cfg.ref.at(t * framesPerStep) : q0;
            int[] want = Alpha2PolicyIo.qToServo(Alpha2PolicyIo.targetQ(base, act, cfg.actScale), cfg.lo, cfg.hi);
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
            steps = t;

            long stepUs = (System.nanoTime() - s0) / 1000;
            row[10] = stepUs / 1000f;
            if (stepUs > maxStepUs) maxStepUs = stepUs;

            next += cfg.periodMs;
            long wait = next - System.currentTimeMillis();
            // 遲到＝比預定時間晚過半個 period（高頻下代表算／送唔切）；視窗內太多即停。
            boolean late = wait < -(cfg.periodMs / 2);
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
