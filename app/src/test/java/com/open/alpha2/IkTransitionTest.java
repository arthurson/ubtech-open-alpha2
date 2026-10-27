package com.open.alpha2;

/**
 * IkTransition 單元測試（零依賴，不用 JUnit）。
 *
 * <p>守住 Gamepad IK 由實際 pose 均速過渡嘅數學：每步 10%／120ms、
 * 手動 pose 按角度差分格、轉動作時新動作唔控制嘅軸（例如 2/5）保持
 * 切換一刻嘅值。執行：{@code python scripts/test-apivalidator.py}。</p>
 */
public final class IkTransitionTest {
    private static int passed = 0;
    private static int failed = 0;

    private IkTransitionTest() {}

    private static void eq(int expected, int actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int[] expected, int[] actual, String name) {
        boolean ok = expected.length == actual.length;
        if (ok) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) { ok = false; break; }
            }
        }
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + java.util.Arrays.toString(expected)
                    + " actual=" + java.util.Arrays.toString(actual));
        }
    }

    private static int[] clone(int[] a) { return a.clone(); }

    public static void main(String[] args) {
        int[] boot = IkPose.bootPose();

        // 百分比步數：0→100 = 10 格；0→10 = 1 格；100→90 = 1 格。
        eq(10, IkTransition.pctSteps(0, 100, 10), "pctSteps 0->100");
        eq(1, IkTransition.pctSteps(0, 10, 10), "pctSteps 0->10");
        eq(1, IkTransition.pctSteps(100, 90, 10), "pctSteps 100->90");
        eq(5, IkTransition.pctSteps(50, 0, 10), "pctSteps 50->0");

        // 插值：0 回 from、steps 回 to，中間值喺兩者之間。
        int[] squat100 = IkPose.compute("squat", 100, boot);
        eq(boot, IkTransition.lerp(boot, squat100, 0, 10), "lerp step0");
        eq(squat100, IkTransition.lerp(boot, squat100, 10, 10), "lerp step10");
        int[] half = IkTransition.lerp(boot, squat100, 5, 10);
        int lo8 = Math.min(boot[7], squat100[7]);
        int hi8 = Math.max(boot[7], squat100[7]);
        eq(true, half[7] >= lo8 && half[7] <= hi8, "lerp mid s8 between");

        // 手動 pose（蹲下 100）→ 蹲下 10：一定要多過一格，逐格角度差唔好大過參考步長。
        int[] squat10 = IkPose.compute("squat", 10, boot);
        int manualSteps = IkTransition.manualSteps(squat100, squat10, "squat", 10, squat100);
        eq(true, manualSteps > 1, "manualSteps squat100->10 > 1");
        int ref = IkTransition.kindStepDeg("squat", 10, squat100);
        int[] prevPose = squat100;
        boolean allWithinRef = true;
        for (int s = 1; s <= manualSteps; s++) {
            int[] p = IkTransition.lerp(squat100, squat10, s, manualSteps);
            if (IkTransition.maxPoseDelta(prevPose, p) > ref) allWithinRef = false;
            prevPose = p;
        }
        eq(true, allWithinRef, "manualSteps each tick <= ref");
        eq(squat10, prevPose, "manualSteps final pose");

        // 由 boot 開始嘅正常 10% 步：一格就夠。
        eq(1, IkTransition.pctSteps(0, 10, 10), "normal 10pct one step");
        eq(false, IkTransition.needsManualStart("squat", boot, boot), "boot needsManual false");
        eq(true, IkTransition.needsManualStart("squat", squat100, squat100), "squat100 needsManual true");

        // 蹲式 100 → 蹲下：回 0% 時 2/5 要凍結喺蹲式實際值，腳回 boot。
        int[] ysquat100 = IkPose.compute("ysquat", 100, boot);
        int[] hold = clone(ysquat100);
        int[] returnPose = IkPose.compute("ysquat", 0, boot, hold);
        for (int i = 0; i < 20; i++) {
            if (!IkPose.affects("squat", i) || !IkPose.affects("ysquat", i)) {
                returnPose[i] = hold[i];
            }
        }
        eq(ysquat100[1], returnPose[1], "switch return keeps servo2");
        eq(ysquat100[4], returnPose[4], "switch return keeps servo5");
        eq(boot[7], returnPose[7], "switch return legs to boot s8");
        eq(boot[12], returnPose[12], "switch return legs to boot s13");
        eq(true, IkTransition.manualSteps(ysquat100, returnPose, "ysquat", 0, hold) >= 1,
                "switch return steps >= 1");

        // 蹲下 10% 目標：2/5 仍然保持蹲式實際值，腳去蹲下 10%。
        int[] squat10FromYsquat = IkPose.compute("squat", 10, boot, hold);
        for (int i = 0; i < 20; i++) {
            if (!IkPose.affects("squat", i)) squat10FromYsquat[i] = hold[i];
        }
        eq(ysquat100[1], squat10FromYsquat[1], "switch target keeps servo2");
        eq(ysquat100[4], squat10FromYsquat[4], "switch target keeps servo5");
        eq(true, squat10FromYsquat[7] != boot[7], "switch target moves s8");

        // 左前／右前一字馬都經同一條均速過渡（fsplitEnd solver 路徑）。
        for (String fk : new String[]{"fsplit", "fsplit2"}) {
            int[] f10 = IkPose.compute(fk, 10, boot);
            int[] f100 = IkPose.compute(fk, 100, boot);
            eq(true, IkTransition.kindStepDeg(fk, 10, boot) > 0,
                    fk + " kindStepDeg > 0");
            eq(true, IkTransition.manualSteps(f100, f10, fk, 10, f100) > 1,
                    fk + " manualSteps > 1");
        }

        // 逐格插值唔會跳過參考步長（轉動作 phase 1）。
        int steps = IkTransition.manualSteps(ysquat100, returnPose, "ysquat", 0, hold);
        int refYs = IkTransition.kindStepDeg("ysquat", 0, hold);
        boolean ysWithin = true;
        int[] last = ysquat100;
        for (int s = 1; s <= steps; s++) {
            int[] p = IkTransition.lerp(ysquat100, returnPose, s, steps);
            if (IkTransition.maxPoseDelta(last, p) > refYs) ysWithin = false;
            last = p;
        }
        eq(true, ysWithin, "switch return each tick <= ref");
        eq(returnPose, last, "switch return final pose");

        System.out.println("IkTransitionTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
