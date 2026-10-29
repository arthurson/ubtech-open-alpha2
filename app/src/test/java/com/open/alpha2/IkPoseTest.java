package com.open.alpha2;

/**
 * IkPose 單元測試（零依賴，不用 JUnit）。
 *
 * <p>期望值由 {@code app/src/main/assets/web/app-ik.js} 同一組參數直接跑出嚟
 * （Node harness），所以呢個 test 同時守住 Java 移植同 web IK 卡唔會走樣。
 * 執行：{@code python scripts/test-apivalidator.py}。</p>
 */
public final class IkPoseTest {
    private static int passed = 0;
    private static int failed = 0;

    private IkPoseTest() {}

    private static int[] pose(String csv) {
        String[] p = csv.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
        return a;
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

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        int[][] bases = {
                pose("120,120,120,120,120,120,120,65,145,140,120,120,175,95,100,120,120,120,120,120"),
                pose("120,205,150,120,35,90,120,26,40,205,120,120,214,200,35,120,120,120,120,120"),
                pose("110,190,140,130,50,100,130,80,150,140,110,110,190,90,110,120,120,120,120,120")
        };

        // home base
        eq(pose("120,120,120,120,120,120,120,26,40,205,120,120,214,200,35,120,120,120,120,120"),
                IkPose.compute("squat", 100, bases[0]), "home.100.squat");
        eq(pose("120,140,120,120,100,120,190,65,145,140,190,50,175,95,100,50,120,120,120,120"),
                IkPose.compute("lr", 100, bases[0]), "home.100.lr");
        eq(pose("90,120,120,70,120,120,120,110,131,199,120,120,191,10,201,120,120,120,120,120"),
                IkPose.compute("fsplit", 100, bases[0]), "home.100.fsplit");
        eq(pose("170,120,120,150,120,120,120,49,230,39,120,120,130,109,41,120,120,120,120,120"),
                IkPose.compute("fsplit2", 100, bases[0]), "home.100.fsplit2");
        eq(pose("120,120,120,120,120,120,120,46,93,173,120,120,195,148,68,120,120,120,120,120"),
                IkPose.compute("squat", 50, bases[0]), "home.50.squat");
        eq(pose("120,150,120,120,90,120,133,47,100,164,133,104,196,139,78,105,120,120,120,120"),
                IkPose.compute("ysquat", 50, bases[0]), "home.50.ysquat");
        eq(pose("120,180,120,120,60,120,145,29,55,187,145,88,216,182,55,89,120,120,120,120"),
                IkPose.compute("ysquat", 100, bases[0]), "home.100.ysquat");
        eq(pose("120,130,120,120,110,120,155,65,145,140,155,85,175,95,100,85,120,120,120,120"),
                IkPose.compute("lr", 50, bases[0]), "home.50.lr");
        eq(pose("105,120,120,95,120,120,120,88,138,170,120,120,183,53,150,120,120,120,120,120"),
                IkPose.compute("fsplit", 50, bases[0]), "home.50.fsplit");
        eq(pose("145,120,120,135,120,120,120,57,188,89,120,120,153,102,71,120,120,120,120,120"),
                IkPose.compute("fsplit2", 50, bases[0]), "home.50.fsplit2");

        // stand base（官方蹲下最終 pose）
        eq(pose("120,205,150,120,35,90,120,26,40,205,120,120,214,200,35,120,120,120,120,120"),
                IkPose.compute("squat", 100, bases[1]), "stand.100.squat");
        eq(pose("120,140,150,120,100,90,190,26,40,205,190,50,214,200,35,50,120,120,120,120"),
                IkPose.compute("lr", 100, bases[1]), "stand.100.lr");
        eq(pose("90,205,150,70,35,90,120,142,115,215,120,120,220,10,205,120,120,120,120,120"),
                IkPose.compute("fsplit", 100, bases[1]), "stand.100.fsplit");
        eq(pose("170,205,150,150,35,90,120,20,230,35,120,120,98,125,25,120,120,120,120,120"),
                IkPose.compute("fsplit2", 100, bases[1]), "stand.100.fsplit2");
        eq(pose("120,193,150,120,48,90,133,28,48,196,133,104,215,191,45,105,120,120,120,120"),
                IkPose.compute("ysquat", 50, bases[1]), "stand.50.ysquat");
        eq(pose("120,180,150,120,60,90,145,29,55,187,145,88,216,182,55,89,120,120,120,120"),
                IkPose.compute("ysquat", 100, bases[1]), "stand.100.ysquat");
        eq(pose("105,205,150,95,35,90,120,84,78,215,120,120,217,105,133,120,120,120,120,120"),
                IkPose.compute("fsplit", 50, bases[1]), "stand.50.fsplit");
        eq(pose("145,205,150,135,35,90,120,23,135,107,120,120,156,163,25,120,120,120,120,120"),
                IkPose.compute("fsplit2", 50, bases[1]), "stand.50.fsplit2");

        // 任意 base（蹲下只郁腳，其他軸保持該 base）
        eq(pose("110,190,140,130,50,100,120,26,40,205,120,120,214,200,35,120,120,120,120,120"),
                IkPose.compute("squat", 100, bases[2]), "odd.100.squat");
        eq(pose("110,140,140,130,100,100,200,80,150,140,180,40,190,90,110,50,120,120,120,120"),
                IkPose.compute("lr", 100, bases[2]), "odd.100.lr");
        eq(pose("90,190,140,70,50,100,130,141,176,175,110,110,220,50,180,120,120,120,120,120"),
                IkPose.compute("fsplit", 100, bases[2]), "odd.100.fsplit");
        eq(pose("170,190,140,150,50,100,130,57,230,37,110,110,168,154,25,120,120,120,120,120"),
                IkPose.compute("fsplit2", 100, bases[2]), "odd.100.fsplit2");
        eq(pose("110,190,140,130,50,100,125,53,95,173,115,115,202,145,73,120,120,120,120,120"),
                IkPose.compute("squat", 50, bases[2]), "odd.50.squat");
        eq(pose("110,185,140,130,55,100,138,55,103,164,128,99,203,136,83,105,120,120,120,120"),
                IkPose.compute("ysquat", 50, bases[2]), "odd.50.ysquat");
        eq(pose("110,180,140,130,60,100,145,29,55,187,145,88,216,182,55,89,120,120,120,120"),
                IkPose.compute("ysquat", 100, bases[2]), "odd.100.ysquat");
        eq(pose("110,165,140,130,75,100,165,80,150,140,145,75,190,90,110,85,120,120,120,120"),
                IkPose.compute("lr", 50, bases[2]), "odd.50.lr");
        eq(pose("100,190,140,100,50,100,130,111,163,158,110,110,205,70,145,120,120,120,120,120"),
                IkPose.compute("fsplit", 50, bases[2]), "odd.50.fsplit");
        eq(pose("140,190,140,140,50,100,130,69,190,89,110,110,179,122,67,120,120,120,120,120"),
                IkPose.compute("fsplit2", 50, bases[2]), "odd.50.fsplit2");

        // 開機企姿（#19 官方原文 250 已改為 120，喺 app 安全範圍內）。
        int[] boot = IkPose.bootPose();
        eq(pose("120,205,120,120,35,120,120,63,145,135,120,120,177,95,105,120,120,120,120,120"),
                boot, "boot.pose");
        eq(pose("120,205,120,120,35,120,120,26,40,205,120,120,214,200,35,120,120,120,120,120"),
                IkPose.compute("squat", 100, boot), "boot.100.squat");
        eq(pose("120,140,120,120,100,120,190,63,145,135,190,50,177,95,105,50,120,120,120,120"),
                IkPose.compute("lr", 100, boot), "boot.100.lr");
        eq(pose("90,205,120,70,35,120,120,106,126,197,120,120,192,10,205,120,120,120,120,120"),
                IkPose.compute("fsplit", 100, boot), "boot.100.fsplit");
        eq(pose("170,205,120,150,35,120,120,48,230,35,120,120,134,114,43,120,120,120,120,120"),
                IkPose.compute("fsplit2", 100, boot), "boot.100.fsplit2");
        eq(pose("120,205,120,120,35,120,120,45,93,170,120,120,196,148,70,120,120,120,120,120"),
                IkPose.compute("squat", 50, boot), "boot.50.squat");
        eq(pose("120,180,120,120,60,120,145,29,55,187,145,88,216,182,55,89,120,120,120,120"),
                IkPose.compute("ysquat", 100, boot), "boot.100.ysquat");
        eq(pose("120,193,120,120,48,120,133,46,100,161,133,104,197,139,80,105,120,120,120,120"),
                IkPose.compute("ysquat", 50, boot), "boot.50.ysquat");
        eq(boot, IkPose.compute("ysquat", 0, boot), "boot.0.ysquat");
        eq(pose("120,173,120,120,68,120,155,63,145,135,155,85,177,95,105,85,120,120,120,120"),
                IkPose.compute("lr", 50, boot), "boot.50.lr");
        eq(pose("105,205,120,95,35,120,120,85,136,166,120,120,185,53,155,120,120,120,120,120"),
                IkPose.compute("fsplit", 50, boot), "boot.50.fsplit");
        eq(pose("145,205,120,135,35,120,120,56,188,85,120,120,156,105,74,120,120,120,120,120"),
                IkPose.compute("fsplit2", 50, boot), "boot.50.fsplit2");
        eq(boot, IkPose.compute("squat", 0, boot), "boot.pct0");

        // base 有超出 app 安全範圍嘅值時，clampPose 只放寬到 base 本身。
        int[] wide = boot.clone();
        wide[18] = 250;
        eq(pose("120,205,120,120,35,120,120,63,145,135,120,120,177,95,105,120,120,120,250,120"),
                IkPose.compute("squat", 0, wide), "wide.pct0");

        // pct 0／未知動作：原 base；pct 超界 clamp。
        eq(bases[0], IkPose.compute("squat", 0, bases[0]), "pct0.base");
        eq(bases[0], IkPose.compute("bogus", 100, bases[0]), "unknown.base");
        eq(IkPose.compute("squat", 100, bases[0]),
                IkPose.compute("squat", 999, bases[0]), "pctHigh.clamp");

        // 五個動作嘅 100% 目標：蹲式 2/5=180/60，左右 2/5=140/100，
        // 左前 1/4=90/70，右前 1/4=170/150。
        int[] ys100 = IkPose.compute("ysquat", 100, boot);
        int[] lr100 = IkPose.compute("lr", 100, boot);
        int[] fs100 = IkPose.compute("fsplit", 100, boot);
        int[] fs2_100 = IkPose.compute("fsplit2", 100, boot);
        eq(180, ys100[1], "ysquat.servo2.100");
        eq(60, ys100[4], "ysquat.servo5.100");
        eq(140, lr100[1], "lr.servo2.100");
        eq(100, lr100[4], "lr.servo5.100");
        eq(90, fs100[0], "fsplit.servo1.100");
        eq(70, fs100[3], "fsplit.servo4.100");
        eq(170, fs2_100[0], "fsplit2.servo1.100");
        eq(150, fs2_100[3], "fsplit2.servo4.100");

        // 前後一字馬二合一：-100＝左前、+100＝右前、0＝企姿。
        eq(fs100, IkPose.computeFb(-100, boot), "fb.-100.left");
        eq(fs2_100, IkPose.computeFb(100, boot), "fb.+100.right");
        eq(IkPose.compute("fsplit", 50, boot), IkPose.computeFb(-50, boot),
                "fb.-50.left");
        eq(IkPose.compute("fsplit2", 50, boot), IkPose.computeFb(50, boot),
                "fb.+50.right");
        eq(boot, IkPose.computeFb(0, boot), "fb.0.boot");
        eq(fs100, IkPose.computeSigned("fb", -100, boot, null), "fb.signed.-100");
        eq(fs2_100, IkPose.computeSigned("fb", 100, boot, null), "fb.signed.+100");

        // 4-arg compute：IK 唔控制嘅軸用 current，唔會拉返 base。
        int[] current = boot.clone();
        current[18] = 150; // 19 號頭
        current[2] = 160;  // 3 號肘
        current[1] = 150;  // 2 號肩 roll
        int[] curSquat = IkPose.compute("squat", 100, boot, current);
        eq(150, curSquat[18], "current.squat.head");
        eq(160, curSquat[2], "current.squat.elbow3");
        eq(150, curSquat[1], "current.squat.servo2");
        eq(26, curSquat[7], "current.squat.legs");
        int[] curYSquat = IkPose.compute("ysquat", 100, boot, current);
        eq(150, curYSquat[18], "current.ysquat.head");
        eq(160, curYSquat[2], "current.ysquat.elbow3");
        eq(180, curYSquat[1], "current.ysquat.servo2");
        int[] curFsplit = IkPose.compute("fsplit", 100, boot, current);
        eq(150, curFsplit[18], "current.fsplit.head");
        eq(150, curFsplit[1], "current.fsplit.servo2");
        eq(90, curFsplit[0], "current.fsplit.servo1");
        eq(70, curFsplit[3], "current.fsplit.servo4");
        int[] curFsplit2 = IkPose.compute("fsplit2", 100, boot, current);
        eq(150, curFsplit2[1], "current.fsplit2.servo2");
        eq(170, curFsplit2[0], "current.fsplit2.servo1");
        eq(150, curFsplit2[3], "current.fsplit2.servo4");
        int[] curFb0 = IkPose.computeSigned("fb", 0, boot, current);
        eq(150, curFb0[18], "current.fb0.head");
        eq(150, curFb0[1], "current.fb0.servo2");
        eq(boot[7], curFb0[7], "current.fb0.legs");
        int[] curZero = IkPose.compute("squat", 0, boot, current);
        eq(150, curZero[18], "current.squat0.head");
        eq(150, curZero[1], "current.squat0.servo2");
        eq(boot[7], curZero[7], "current.squat0.legs");

        // 受影響軸 mask
        eq(true, IkPose.affects("squat", 6), "affects.squat.leg");
        eq(false, IkPose.affects("squat", 1), "affects.squat.servo2");
        eq(true, IkPose.affects("ysquat", 1), "affects.ysquat.servo2");
        eq(true, IkPose.affects("lr", 4), "affects.lr.servo5");
        eq(false, IkPose.affects("fsplit", 1), "affects.fsplit.servo2");
        eq(true, IkPose.affects("fsplit", 0), "affects.fsplit.servo1");
        eq(true, IkPose.affects("fsplit2", 3), "affects.fsplit2.servo4");
        eq(true, IkPose.affects("fb", 0), "affects.fb.alias.servo1");

        // 動作名驗證（fb 係 fsplit 別名）
        eq(true, IkPose.isKind("squat"), "kind.squat");
        eq(true, IkPose.isKind("ysquat"), "kind.ysquat");
        eq(true, IkPose.isKind("lr"), "kind.lr");
        eq(true, IkPose.isKind("fsplit"), "kind.fsplit");
        eq(true, IkPose.isKind("fsplit2"), "kind.fsplit2");
        eq(true, IkPose.isKind("fb"), "kind.fb.alias");
        eq(false, IkPose.isKind("base"), "kind.base");
        eq(false, IkPose.isKind("bogus"), "kind.bogus");
        eq(IkPose.compute("fsplit", 100, boot), IkPose.compute("fb", 100, boot),
                "kind.fb.alias.pose");

        System.out.println("IkPoseTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
