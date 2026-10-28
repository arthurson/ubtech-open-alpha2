package com.ubtechinc.alpha.hardware.ubx;

/**
 * 按住連行引擎（純 Java；由 {@link UbxPlayer#startWalk} 放入 play 線程執行，
 * 所以原有 {@code ubxPlayer.stop()} 搶佔／{@code isPlaying()} 判斷全部自動適用）。
 *
 * <pre>
 * 起步 leadIn（行一次）→ 步態 cycle（首尾重覆，直到放手／租約過期）→ 收步（回 home）
 * </pre>
 *
 * <ul>
 *   <li><b>放手即停</b>：{@link #release()} 喚醒等待，馬上由「當前指令姿勢」按偏差大細
 *       線性收返 home（servo 由實際位置接手，唔會突跳）；收步時間 =
 *       偏差 × blendMsPerUnit，夾入 [blendMinMs, blendMaxMs]。</li>
 *   <li><b>變速</b>：{@link #setSpeed} 即時生效，逐格縮放時長（平滑過渡，唔會一格跳）；
 *       範圍 0.5–2.0（同 UbxPlayer 一致）。另有每格最快限速 minMsPerUnit 保護舵機。</li>
 *   <li><b>租約（lease）</b>：leaseMs &gt; 0 時，超過該時間未 {@link #beat()} 視為放手
 *       （遠端 key-up 遺失／網頁死機兜底）；leaseMs == 0 即無租約，只靠 release。</li>
 *   <li><b>硬停</b>：線程被 interrupt（{@code UbxPlayer.stop()}）即時結束，唔送收步，
 *       舵機保持末位姿——同播 .ubx 時 stop 語義一致。</li>
 *   <li><b>送出失敗</b>連續 5 次即放棄（串口閂咗），唔會死循環。</li>
 * </ul>
 */
public final class GaitWalker implements Runnable {
    /** 送一格：20 軸角度＋move 時間（ms）。回 true＝已送出。 */
    public interface Sender {
        boolean send(int[] angles20, int timeMs);
    }

    public static final float MIN_SPEED = 0.5f;
    public static final float MAX_SPEED = 2.0f;

    public static final int PHASE_IDLE = 0;
    public static final int PHASE_LEAD_IN = 1;
    public static final int PHASE_WALKING = 2;
    public static final int PHASE_STANDING = 3;
    public static final int PHASE_DONE = 4;

    private static final int MAX_SEND_FAILS = 5;
    private static final int STAND_SEND_TRIES = 3;
    private static final long WAIT_SLICE_MS = 40;
    private static final float SPEED_SMOOTH = 0.5f;

    private final GaitProgram prog;
    private final Sender sender;
    private final int[] startPose;
    private final Object lock = new Object();

    private volatile float targetSpeed;
    private volatile long leaseMs;
    private volatile long lastBeatMs;
    private volatile boolean releaseReq;
    private volatile int phase = PHASE_IDLE;
    private volatile float effSpeed;
    private volatile int cycles;
    private volatile int frameIdx = -1;
    private volatile String endReason = "";

    // 只喺 walker 線程讀寫
    private int[] cmdPose;
    private int[] prevPose;
    private boolean sentAny;
    private int sendFails;

    /**
     * @param startPose 已知嘅當前指令姿勢（20 軸）；未知傳 null（當 home）
     * @param speed     起步速度倍率（夾入 0.5–2.0）
     * @param leaseMs   0＝無租約；&gt;0＝超時未 beat 即自動放手
     */
    public GaitWalker(GaitProgram prog, Sender sender, int[] startPose, float speed, long leaseMs) {
        if (prog == null || sender == null) throw new IllegalArgumentException("null arg");
        this.prog = prog;
        this.sender = sender;
        this.startPose = (startPose != null && startPose.length == GaitProgram.AXES)
                ? startPose.clone() : null;
        this.targetSpeed = clampSpeed(speed);
        this.effSpeed = this.targetSpeed;
        this.leaseMs = Math.max(0L, leaseMs);
        this.lastBeatMs = nowMs();
    }

    // ---- 外部控制（任何線程）------------------------------------------------

    public void setSpeed(float s) { targetSpeed = clampSpeed(s); }

    public void setLeaseMs(long ms) { leaseMs = Math.max(0L, ms); }

    /** 租約續期（按住期間遠端定時叫）。 */
    public void beat() { lastBeatMs = nowMs(); }

    /** 放手：喚醒等待、即刻收步。可重覆叫。 */
    public void release() {
        synchronized (lock) {
            releaseReq = true;
            lock.notifyAll();
        }
    }

    public boolean isReleased() { return releaseReq; }

    /** 已放手／收步中／已完（此時新一次 startWalk 可先截停再重起）。 */
    public boolean isFinishing() { return releaseReq || phase >= PHASE_STANDING; }

    public int phase() { return phase; }

    public float speed() { return effSpeed; }

    public float targetSpeed() { return targetSpeed; }

    public int cycles() { return cycles; }

    public int frameIndex() { return frameIdx; }

    public String endReason() { return endReason; }

    // ---- 線程體 --------------------------------------------------------------

    @Override
    public void run() {
        try {
            walk();
        } catch (InterruptedException e) {
            endReason = "stopped";
        } finally {
            phase = PHASE_DONE;
        }
    }

    private void walk() throws InterruptedException {
        effSpeed = clampSpeed(targetSpeed);
        cmdPose = startPose != null ? startPose : prog.home;
        prevPose = cmdPose;
        long next = nowMs();

        // 1) 起步
        phase = PHASE_LEAD_IN;
        for (int i = 0; i < prog.leadIn.length && !shouldRelease(); i++) {
            int dur = scaled(prog.leadInMs[i], effSpeed);
            if (i == 0) {
                int guard = clampMs((long) GaitProgram.maxDelta(cmdPose, prog.leadIn[0])
                        * prog.entryMsPerUnit);
                if (guard > dur) dur = guard;
            }
            if (!emit(prog.leadIn[i], dur)) return;
            next = advance(next, dur);
            if (!waitUntil(next)) break;
        }

        // 2) 步態循環
        phase = PHASE_WALKING;
        int n = prog.cycle.length;
        int i = 0;
        while (!shouldRelease()) {
            effSpeed = smooth(effSpeed, clampSpeed(targetSpeed));
            int dur = scaled(prog.cycleMs[i], effSpeed);
            int guard = clampMs((long) GaitProgram.maxDelta(cmdPose, prog.cycle[i])
                    * prog.minMsPerUnit);
            if (guard > dur) dur = guard;
            if (!emit(prog.cycle[i], dur)) return;
            frameIdx = i;
            next = advance(next, dur);
            if (!waitUntil(next)) break;
            if (++i == n) {
                i = 0;
                cycles++;
            }
        }
        if (endReason.isEmpty()) endReason = releaseReq ? "released" : "lease";

        // 3) 收步：由當前指令姿勢回 home（冇送過任何嘢就唔使收）
        phase = PHASE_STANDING;
        if (!sentAny) return;
        int dev = Math.max(GaitProgram.maxDelta(cmdPose, prog.home),
                GaitProgram.maxDelta(prevPose, prog.home));
        int t = (int) Math.max(prog.blendMinMs,
                Math.min(prog.blendMaxMs, (long) dev * prog.blendMsPerUnit));
        boolean ok = false;
        for (int a = 0; a < STAND_SEND_TRIES && !ok; a++) {
            ok = sender.send(prog.home.clone(), t);
        }
        if (!ok) {
            endReason = endReason + "+stand-failed";
            return;
        }
        cmdPose = prog.home;
        sleepQuiet(nowMs() + t);
    }

    // ---- 內部 ----------------------------------------------------------------

    private boolean emit(int[] pose, int dur) {
        boolean ok = sender.send(pose.clone(), dur);
        if (ok) {
            sendFails = 0;
            sentAny = true;
            prevPose = cmdPose;
            cmdPose = pose;
            return true;
        }
        if (++sendFails >= MAX_SEND_FAILS) {
            endReason = "send-failed";
            return false;
        }
        return true; // 偶發失敗：照走節拍，下一格再送
    }

    private boolean shouldRelease() {
        if (releaseReq) return true;
        long l = leaseMs;
        return l > 0 && nowMs() - lastBeatMs > l;
    }

    /** 等到 deadline；放手／租約過期回 false。被 interrupt 即掟 InterruptedException。 */
    private boolean waitUntil(long deadline) throws InterruptedException {
        while (true) {
            if (shouldRelease()) return false;
            long rem = deadline - nowMs();
            if (rem <= 0) return true;
            synchronized (lock) {
                if (releaseReq) return false;
                lock.wait(Math.min(rem, WAIT_SLICE_MS));
            }
        }
    }

    /** 收步期間等舵機行完：忽略放手，只認 interrupt。 */
    private void sleepQuiet(long deadline) throws InterruptedException {
        while (true) {
            long rem = deadline - nowMs();
            if (rem <= 0) return;
            synchronized (lock) {
                lock.wait(Math.min(rem, WAIT_SLICE_MS));
            }
        }
    }

    /** 下一格到期時刻：延遲 &lt;= 一格內自動追返，超過就重新對時（唔連發補拍）。 */
    private static long advance(long next, int dur) {
        long n = next + dur;
        long now = nowMs();
        return n < now ? now : n;
    }

    private static float smooth(float cur, float target) {
        float d = target - cur;
        if (Math.abs(d) < 0.01f) return target;
        return cur + d * SPEED_SMOOTH;
    }

    private static int scaled(int ms, float speed) {
        return clampMs(Math.round(ms / (double) speed));
    }

    private static int clampMs(long ms) {
        if (ms < GaitProgram.MIN_FRAME_MS) return GaitProgram.MIN_FRAME_MS;
        if (ms > GaitProgram.MAX_FRAME_MS) return GaitProgram.MAX_FRAME_MS;
        return (int) ms;
    }

    public static float clampSpeed(float s) {
        if (Float.isNaN(s)) return 1.0f;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, s));
    }

    private static long nowMs() { return System.nanoTime() / 1000000L; }
}
