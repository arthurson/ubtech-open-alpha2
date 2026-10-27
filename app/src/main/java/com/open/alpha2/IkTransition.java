package com.open.alpha2;

/**
 * Gamepad IK 均速過渡的純數學工具（不依賴 Android）。
 *
 * <p>同 Web IK 卡 {@code app-ik.js} 嘅 slider 過渡一致：每格 120ms，
 * 每格最多 10%；由手動／其他路改過嘅實際 pose 出發時，按角度差決定
 * 格數，確保第一格唔會突然大幅跳動。GamepadCenter 只負責狀態機同
 * 送 {@code servo/all}，呢度集中計步數／插值，方便單元測試。</p>
 */
public final class IkTransition {

    private IkTransition() {}

    /** 由 fromPct 行到 toPct 要幾多格（每格 stepPct%；最少 1 格）。 */
    public static int pctSteps(int fromPct, int toPct, int stepPct) {
        int step = Math.max(1, stepPct);
        int d = Math.abs(toPct - fromPct);
        return Math.max(1, (d + step - 1) / step);
    }

    /** 20 軸最大角度差（用嚟估手動 pose → 目標要分幾多格）。 */
    public static int maxPoseDelta(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < 20; i++) {
            int d = Math.abs(b[i] - a[i]);
            if (d > max) max = d;
        }
        return max;
    }

    /**
     * 該 IK 動作每 10% 最大會行幾多度（只計該動作真正控制嘅軸）。
     * 同 app-ik.js {@code ikKindStepDeg()} 同一算法。
     */
    public static int kindStepDeg(String kind, int pct, int[] hold) {
        int p = Math.max(0, Math.min(100, pct));
        int lo = Math.min(90, (p / 10) * 10);
        if (lo == p && p > 0) lo = Math.max(0, p - 10);
        int[] a = IkPose.compute(kind, lo, IkPose.bootPose(), hold);
        int[] b = IkPose.compute(kind, Math.min(100, lo + 10), IkPose.bootPose(), hold);
        int max = 1;
        for (int i = 0; i < 20; i++) {
            if (!IkPose.affects(kind, i)) continue;
            int d = Math.abs(b[i] - a[i]);
            if (d > max) max = d;
        }
        return max;
    }

    /**
     * 由任意實際 pose 均速去目標要幾多格：按最大角度差／該動作每 10%
     * 參考步長，最多 30 格（3.6 秒），避免手動改咗好多時第一格跳。
     */
    public static int manualSteps(int[] from, int[] to, String kind, int pct, int[] hold) {
        int maxDelta = maxPoseDelta(from, to);
        if (maxDelta <= 0) return 1;
        int ref = kindStepDeg(kind, pct, hold);
        int steps = (maxDelta + ref - 1) / ref;
        return Math.max(1, Math.min(30, steps));
    }

    /**
     * 新動作控制嘅軸有冇偏離開機企姿。有＝要用角度差決定格數；
     * 冇＝可以照 slider 每 10% 一格。
     */
    public static boolean needsManualStart(String kind, int[] from, int[] hold) {
        int[] boot = IkPose.bootPose();
        for (int i = 0; i < 20; i++) {
            if (IkPose.affects(kind, i) && from[i] != boot[i]) return true;
        }
        return false;
    }

    /** 線性插值；step=0 回 from，step=steps 回 to。 */
    public static int[] lerp(int[] from, int[] to, int step, int steps) {
        int n = Math.max(1, steps);
        int s = Math.max(0, Math.min(n, step));
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (int) Math.round(from[i] + (to[i] - from[i]) * (double) s / n);
        }
        return out;
    }
}
