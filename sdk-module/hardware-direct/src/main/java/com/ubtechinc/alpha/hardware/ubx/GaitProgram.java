package com.ubtechinc.alpha.hardware.ubx;

/**
 * 「按住連行」步態數據（純 Java，不含 Android 依賴，方便 javac 單元測試）。
 *
 * <p>由 {@code scripts/ubx_to_gait.py} 從 .ubx 抽出（見 assets/gait/walk_fwd.json）：
 * <ul>
 *   <li>{@code leadIn}：起步段（由企定姿勢沉入起步姿勢），按下後行一次；</li>
 *   <li>{@code cycle}：步態循環，按住期間首尾無縫重覆；</li>
 *   <li>{@code home}：企定姿勢，放手後由當前姿勢收返呢度。</li>
 * </ul>
 * 每格時長（ms）為 1.0x 時嘅值；{@link GaitWalker} 按速度倍率縮放。</p>
 */
public final class GaitProgram {
    public static final int AXES = 20;
    public static final int MIN_FRAME_MS = 20;
    public static final int MAX_FRAME_MS = 30000;

    public final int[][] leadIn;
    public final int[] leadInMs;
    public final int[][] cycle;
    public final int[] cycleMs;
    public final int[] home;
    /** 收步：按偏差大細 × msPerUnit，夾入 [minMs, maxMs]。 */
    public final int blendMsPerUnit;
    public final int blendMinMs;
    public final int blendMaxMs;
    /** 起步第一格由實際姿勢過去嘅最慢限速（ms／單位），防急扯。 */
    public final int entryMsPerUnit;
    /** 步態每格最快限速（ms／單位），防高倍速令舵機追唔上。 */
    public final int minMsPerUnit;

    public GaitProgram(int[][] leadIn, int[] leadInMs, int[][] cycle, int[] cycleMs,
            int[] home, int blendMsPerUnit, int blendMinMs, int blendMaxMs,
            int entryMsPerUnit, int minMsPerUnit) {
        check(leadIn, leadInMs, "leadIn", 0);
        check(cycle, cycleMs, "cycle", 2);
        checkPose(home, "home");
        if (blendMsPerUnit < 1 || blendMinMs < MIN_FRAME_MS || blendMaxMs < blendMinMs
                || blendMaxMs > MAX_FRAME_MS) {
            throw new IllegalArgumentException("bad blend params");
        }
        if (entryMsPerUnit < 1 || minMsPerUnit < 0) {
            throw new IllegalArgumentException("bad limit params");
        }
        this.leadIn = copy(leadIn);
        this.leadInMs = leadInMs.clone();
        this.cycle = copy(cycle);
        this.cycleMs = cycleMs.clone();
        this.home = home.clone();
        this.blendMsPerUnit = blendMsPerUnit;
        this.blendMinMs = blendMinMs;
        this.blendMaxMs = blendMaxMs;
        this.entryMsPerUnit = entryMsPerUnit;
        this.minMsPerUnit = minMsPerUnit;
    }

    private static void check(int[][] frames, int[] ms, String what, int minFrames) {
        if (frames == null || ms == null || frames.length != ms.length
                || frames.length < minFrames) {
            throw new IllegalArgumentException(what + ": frames/ms mismatch or too few");
        }
        for (int i = 0; i < frames.length; i++) {
            checkPose(frames[i], what + "[" + i + "]");
            if (ms[i] < MIN_FRAME_MS || ms[i] > MAX_FRAME_MS) {
                throw new IllegalArgumentException(what + "[" + i + "] ms out of range: " + ms[i]);
            }
        }
    }

    private static void checkPose(int[] pose, String what) {
        if (pose == null || pose.length != AXES) {
            throw new IllegalArgumentException(what + ": need " + AXES + " axes");
        }
        for (int v : pose) {
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException(what + ": angle out of 0-255: " + v);
            }
        }
    }

    private static int[][] copy(int[][] src) {
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) out[i] = src[i].clone();
        return out;
    }

    /** 兩個姿勢逐軸最大絕對差（servo 單位）。 */
    public static int maxDelta(int[] a, int[] b) {
        int m = 0;
        for (int i = 0; i < AXES; i++) {
            int d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }
}
