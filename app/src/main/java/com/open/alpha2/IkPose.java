package com.open.alpha2;

/**
 * 腳部 IK 動作位姿（蹲下／蹲式／左右一字馬／左前／右前一字馬）——Java 版。
 *
 * <p>同 {@code app/src/main/assets/web/app-ik.js} 一字不差咁移植：同一組
 * URDF 連桿參數、校準 offset、ankle 補償、damped least-squares 解，
 * 連 multi-start 同 clamp 次序都照跟。Web IK 卡同手柄／鍵盤映射共用同一套
 * 結果，所以兩邊做同一個百分比，落到舵機嘅 20 軸位姿應該一樣。</p>
 *
 * <p>0% base 係官方開機 robotStandup 企姿（{@link #BOOT_POSE}），唔再跟
 * 舵機格／現時位姿走：pct 0 回開機企姿，100 到已測試嘅最終 pose；五種動作
 * 互斥，但一律由同一個 base 計，所以中途轉動作唔會越做越歪。</p>
 *
 * <p>每個動作只控制相關舵機：蹲下只郁腳 7-16；蹲式／左右一字馬郁腳
 * 7-16 加肩 roll 2/5；左前／右前一字馬郁腳 7-16 加肩 pitch 1/4（雙臂
 * 前後擺）。頭 19/20、手 17/18、肘 3/6 唔屬於任何 IK 動作，
 * {@link #compute(String, int, int[], int[])} 會用目前實際值保留佢哋。</p>
 */
public final class IkPose {

    private IkPose() {}

    private static final double D2R = Math.PI / 180.0;
    private static final double SPLIT_MAX_DEG = 80.0;

    // 左右腳連桿（同 app-ik.js IK_LEG_CHAINS）：servo 1-22、joint origin、
    // 旋轉軸、校準 offset。index 跟 servo id - 1。
    private static final int[] R_SERVO = {7, 8, 9, 10, 11};
    private static final double[][] R_ORIGIN = {
            {1.52588e-8, -0.0353277, 0.00188553},
            {0.00972791, -0.00802065, -0.0438724},
            {-0.0117411, 0, -0.0525105},
            {0.00000532532, 0, -0.0591983},
            {0.00200788, 0.0168256, -0.0369769}
    };
    private static final double[][] R_AXIS = {
            {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, -1, 0}, {1, 0, 0}
    };
    private static final int[] R_OFFSET = {-119, -100, -190, -120, -119};
    private static final double[] R_SOLE_OFF = {0.000213, -0.016948, -0.027969};

    private static final int[] L_SERVO = {12, 13, 14, 15, 16};
    private static final double[][] L_ORIGIN = {
            {7.62939e-9, 0.0353277, 0.00188553},
            {0.00972792, 0.00802064, -0.0438723},
            {-0.0117411, 1.39965e-9, -0.0525105},
            {0.00000535583, -6.38468e-13, -0.0593814},
            {0.00200786, -0.0168256, -0.0367939}
    };
    private static final double[][] L_AXIS = {
            {-1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 1, 0}, {1, 0, 0}
    };
    private static final int[] L_OFFSET = {-121, -140, -50, -120, -119};
    private static final double[] L_SOLE_OFF = {0.000213, 0.016948, -0.027969};

    // ankle 補償用嘅有效 offset（同 IK_OFF）。
    private static final int OFF_8 = -100;
    private static final int OFF_9 = -190;
    private static final int OFF_10 = -120;
    private static final int OFF_13 = -140;
    private static final int OFF_14 = -50;
    private static final int OFF_15 = -120;

    private static final int SPLIT_ARM_R = 140;
    private static final int SPLIT_ARM_L = 100;
    private static final int YSQUAT_ARM_R = 180;
    private static final int YSQUAT_ARM_L = 60;
    private static final double FSPLIT_FWD = 0.078;
    private static final double FSPLIT_BACK = 0.087;
    private static final double FSPLIT_DZ_F = 0.0695;
    private static final double FSPLIT_DZ_B = 0.0365;
    private static final int[] FSPLIT_SEED_R = {109, 125};
    private static final int[] FSPLIT_SEED_L = {191, 10};
    private static final int[] FSPLIT2_SEED_R = {49, 230};
    private static final int[] FSPLIT2_SEED_L = {131, 115};
    private static final int FSPLIT_ARM_1 = 90;
    private static final int FSPLIT_ARM_4 = 70;
    private static final int FSPLIT2_ARM_1 = 170;
    private static final int FSPLIT2_ARM_4 = 150;
    private static final int[] SQUAT_SEED_R = {26, 40};
    private static final int[] SQUAT_SEED_L = {214, 200};

    /**
     * 官方「蹲下」最終 20 軸 pose（實機已測）。IK 只會用 7-16 號腳嘅值；
     * 3/6 號肘部值唔屬於蹲下動作，會保持目前值。
     */
    private static final int[] SQUAT_POSE = {
            120, 205, 150, 120, 35, 90, 120, 26, 40, 205,
            120, 120, 214, 200, 35, 120, 120, 120, 120, 120
    };

    /**
     * 官方「蹲式」（action 1509004868066 最深格）20 軸 pose：
     * 深蹲、雙臂左右張開、腳掌貼地（同 app-ik.js IK_YSQUAT_POSE）。
     * 肩 roll 2/5 目標係 180／60（同左右一字馬 140／100 唔同）。
     * IK 只會用 2/5（肩 roll）同 7-16（腳）嘅值，其他軸保持目前值。
     */
    private static final int[] YSQUAT_POSE = {
            120, YSQUAT_ARM_R, 120, 120, YSQUAT_ARM_L, 120, 145, 29, 55, 187,
            145, 88, 216, 182, 55, 89, 120, 120, 120, 120
    };

    /**
     * 開機 robotStandup 20 軸企姿（raw 0-255；同
     * {@code ActionDirect.OFFICIAL_BOOT_STANDUP_POSE} 同 app-ik.js
     * {@code IK_BOOT_POSE} 一致）。#19 官方原文 250 已改為 120（app 安全範圍）；
     * 如果將來 base 有超出範圍嘅值，{@link #clampPose} 會按 base 本身放寬，唔會再擴大。
     */
    public static final int[] BOOT_POSE = {
            120, 205, 120, 120, 35, 120, 120, 63, 145, 135,
            120, 120, 177, 95, 105, 120, 120, 120, 120, 120
    };

    /** 開機企姿副本（IK 0% base；避免 caller 改到常數）。 */
    public static int[] bootPose() {
        return BOOT_POSE.clone();
    }

    // ---- 3D 小工具（只做腳鏈） ----

    private static double[][] identity3() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    private static double[][] rotAxis(double[] ax, double q) {
        double n = Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]);
        double x = ax[0] / n, y = ax[1] / n, z = ax[2] / n;
        double c = Math.cos(q), s = Math.sin(q), C = 1 - c;
        return new double[][]{
                {c + x * x * C, x * y * C - z * s, x * z * C + y * s},
                {y * x * C + z * s, c + y * y * C, y * z * C - x * s},
                {z * x * C - y * s, z * y * C + x * s, c + z * z * C}
        };
    }

    private static double[] mDot(double[][] R, double[] v) {
        return new double[]{
                R[0][0] * v[0] + R[0][1] * v[1] + R[0][2] * v[2],
                R[1][0] * v[0] + R[1][1] * v[1] + R[1][2] * v[2],
                R[2][0] * v[0] + R[2][1] * v[1] + R[2][2] * v[2]
        };
    }

    private static double[][] mMul(double[][] A, double[][] B) {
        double[][] C = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += A[i][k] * B[k][j];
                C[i][j] = s;
            }
        }
        return C;
    }

    private static double[] vAdd(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] vSub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double vNorm(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    /** API 19 安全版 Double.isFinite（Android 舊 API 冇呢個 method）。 */
    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** 腳掌（ankle_roll link 再落 sole offset）位置；pose 為 20 軸度數。 */
    private static double[] sole(double[] pose, boolean right) {
        int[] servos = right ? R_SERVO : L_SERVO;
        double[][] origins = right ? R_ORIGIN : L_ORIGIN;
        double[][] axes = right ? R_AXIS : L_AXIS;
        int[] offsets = right ? R_OFFSET : L_OFFSET;
        double[] soleOff = right ? R_SOLE_OFF : L_SOLE_OFF;
        double[][] R = identity3();
        double[] p = {0, 0, 0};
        for (int i = 0; i < servos.length; i++) {
            p = vAdd(p, mDot(R, origins[i]));
            R = mMul(R, rotAxis(axes[i],
                    (pose[servos[i] - 1] + offsets[i]) * D2R));
        }
        return vAdd(p, mDot(R, soleOff));
    }

    private static double legPitch(double[] a, boolean right) {
        if (right) {
            return a[7] + OFF_8 - a[8] - OFF_9 - a[9] - OFF_10;
        }
        return -(a[12] + OFF_13) + a[13] + OFF_14 + a[14] + OFF_15;
    }

    private static double squatAnkle(double[] a, boolean right, double pitch) {
        if (right) {
            return a[7] - a[8] + OFF_8 - OFF_9 - OFF_10 - pitch;
        }
        return a[12] - a[13] + pitch + OFF_13 - OFF_14 - OFF_15;
    }

    private static int[] rangeOf(int id) {
        return new int[]{ApiValidator.servoCalMin(id), ApiValidator.servoCalMax(id)};
    }

    private static final class Result {
        double[] pose;
        double err;
        Result(double[] pose, double err) {
            this.pose = pose;
            this.err = err;
        }
    }

    /**
     * Damped least-squares IK core（同 app-ik.js ikSolveCore）。
     * anklePitch 唔係 NaN 即做蹲下嘅 ankle 補償（prep）。
     */
    private static Result solveCore(double[] a0, double[] target, boolean right,
                                    int[] servos, int iters,
                                    double anklePitch, boolean prep) {
        double[] a = a0.clone();
        double[] e = effAt(a, right, anklePitch, prep);
        double errM = vNorm(vSub(target, e));
        double lam = 1e-3;
        int max = iters > 0 ? iters : 16;
        for (int it = 0; it < max && errM > 0.002; it++) {
            double[] err = {target[0] - e[0], target[1] - e[1], target[2] - e[2]};
            double[][] J = new double[servos.length][3];
            double h = 0.01;
            for (int i = 0; i < servos.length; i++) {
                double[] ap = a.clone();
                ap[servos[i] - 1] += h / D2R;
                double[] ep = effAt(ap, right, anklePitch, prep);
                J[i][0] = (ep[0] - e[0]) / h;
                J[i][1] = (ep[1] - e[1]) / h;
                J[i][2] = (ep[2] - e[2]) / h;
            }
            double[][] A = new double[3][3];
            for (int i = 0; i < servos.length; i++) {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) A[r][c] += J[i][r] * J[i][c];
                }
            }
            A[0][0] += lam;
            A[1][1] += lam;
            A[2][2] += lam;
            double det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1])
                    - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
                    + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
            if (det == 0 || !finite(det)) break;
            double[][] inv = {
                    {(A[1][1] * A[2][2] - A[1][2] * A[2][1]) / det,
                            (A[0][2] * A[2][1] - A[0][1] * A[2][2]) / det,
                            (A[0][1] * A[1][2] - A[0][2] * A[1][1]) / det},
                    {(A[1][2] * A[2][0] - A[1][0] * A[2][2]) / det,
                            (A[0][0] * A[2][2] - A[0][2] * A[2][0]) / det,
                            (A[0][2] * A[1][0] - A[0][0] * A[1][2]) / det},
                    {(A[1][0] * A[2][1] - A[1][1] * A[2][0]) / det,
                            (A[0][1] * A[2][0] - A[0][0] * A[2][1]) / det,
                            (A[0][0] * A[1][1] - A[0][1] * A[1][0]) / det}
            };
            double[] y = new double[3];
            for (int r = 0; r < 3; r++) {
                y[r] = inv[r][0] * err[0] + inv[r][1] * err[1] + inv[r][2] * err[2];
            }
            double[] trial = a.clone();
            for (int i = 0; i < servos.length; i++) {
                double dq = J[i][0] * y[0] + J[i][1] * y[1] + J[i][2] * y[2];
                if (dq > 0.17) dq = 0.17;
                if (dq < -0.17) dq = -0.17;
                int s = servos[i];
                int[] lim = rangeOf(s);
                double v = a[s - 1] + dq / D2R;
                if (v < lim[0]) v = lim[0];
                if (v > lim[1]) v = lim[1];
                trial[s - 1] = v;
            }
            double[] et = effAt(trial, right, anklePitch, prep);
            double errT = vNorm(vSub(target, et));
            if (errT < errM) {
                a = trial;
                e = et;
                errM = errT;
                lam = Math.max(1e-5, lam * 0.5);
            } else {
                lam = Math.min(0.2, lam * 4);
            }
        }
        return new Result(a, errM);
    }

    private static double[] effAt(double[] arr, boolean right,
                                  double anklePitch, boolean prep) {
        double[] aa = arr;
        if (prep) {
            aa = arr.clone();
            aa[right ? 9 : 14] = squatAnkle(aa, right, anklePitch);
        }
        return sole(aa, right);
    }

    /** IK solve + multi-start（同 ikSolveChain）；只將關節 servo round 成整數。 */
    private static double[] solveChain(double[] start, double[] target, boolean right,
                                       int[] servos, int iters,
                                       double anklePitch, boolean prep) {
        double[] a = start.clone();
        Result best = solveCore(a, target, right, servos, iters, anklePitch, prep);
        if (best.err > 0.006) {
            for (int ci = 0; ci < servos.length; ci++) {
                int[] deltas = {12, -12};
                for (int di = 0; di < deltas.length; di++) {
                    double[] a2 = a.clone();
                    int s = servos[ci];
                    int[] lim = rangeOf(s);
                    a2[s - 1] = Math.max(lim[0], Math.min(lim[1], a2[s - 1] + deltas[di]));
                    Result r = solveCore(a2, target, right, servos, iters, anklePitch, prep);
                    if (r.err < best.err) best = r;
                }
            }
        }
        double[] out = best.pose.clone();
        for (int i = 0; i < servos.length; i++) {
            int s = servos[i];
            out[s - 1] = Math.round(out[s - 1]);
        }
        return out;
    }

    private static double[] squatSolve(double[] a, boolean right,
                                       double pitchR, double pitchL,
                                       double[] target, int[] seed) {
        int[] servos = right ? new int[]{8, 9} : new int[]{13, 14};
        double[] a2 = a.clone();
        if (right) {
            a2[7] = seed[0];
            a2[8] = seed[1];
        } else {
            a2[12] = seed[0];
            a2[13] = seed[1];
        }
        a2 = solveChain(a2, target, right, servos, 30,
                right ? pitchR : pitchL, true);
        int dep = right ? 10 : 15;
        int[] rg = rangeOf(dep);
        a2[dep - 1] = squatAnkle(a2, right, right ? pitchR : pitchL);
        if (a2[dep - 1] < rg[0]) a2[dep - 1] = rg[0];
        if (a2[dep - 1] > rg[1]) a2[dep - 1] = rg[1];
        a2[dep - 1] = Math.round(a2[dep - 1]);
        return a2;
    }

    private static final class FbEnd {
        double s8, s9, s13, s14, pitchR, pitchL;
    }

    /** 舊 API／已存手柄映射嘅 fb 當左前。 */
    private static String normalizeKind(String kind) {
        return "fb".equals(kind) ? "fsplit" : kind;
    }

    private static boolean isFSplit2(String kind) {
        return "fsplit2".equals(normalizeKind(kind));
    }

    /**
     * 左前／右前一字馬最終 pose（同 ubx-player.html 嘅 ikFSplitCapture／
     * ikFSplit2Capture）。fsplit＝左腳前、右腳後；fsplit2 係鏡像。
     */
    private static FbEnd fsplitEnd(double[] base, String kind) {
        boolean rightFwd = isFSplit2(kind);
        double[] br = sole(base, true);
        double[] bl = sole(base, false);
        FbEnd end = new FbEnd();
        end.pitchR = legPitch(base, true);
        end.pitchL = legPitch(base, false);
        double[] e = base.clone();
        double[] rTarget = rightFwd
                ? new double[]{br[0] + FSPLIT_FWD, br[1], br[2] + FSPLIT_DZ_F}
                : new double[]{br[0] - FSPLIT_BACK, br[1], br[2] + FSPLIT_DZ_B};
        double[] lTarget = rightFwd
                ? new double[]{bl[0] - FSPLIT_BACK, bl[1], bl[2] + FSPLIT_DZ_B}
                : new double[]{bl[0] + FSPLIT_FWD, bl[1], bl[2] + FSPLIT_DZ_F};
        e = squatSolve(e, true, end.pitchR, end.pitchL, rTarget,
                rightFwd ? FSPLIT2_SEED_R : FSPLIT_SEED_R);
        e = squatSolve(e, false, end.pitchR, end.pitchL, lTarget,
                rightFwd ? FSPLIT2_SEED_L : FSPLIT_SEED_L);
        end.s8 = e[7];
        end.s9 = e[8];
        end.s13 = e[12];
        end.s14 = e[13];
        return end;
    }

    private static double splitMaxD(double[] base) {
        int[] rH = rangeOf(7), rA = rangeOf(11), lH = rangeOf(12), lA = rangeOf(16);
        return Math.max(0, Math.min(SPLIT_MAX_DEG,
                Math.min(Math.min(rH[1] - base[6], rA[1] - base[10]),
                        Math.min(base[11] - lH[0], base[15] - lA[0]))));
    }

    private static int[] clampPose(double[] pose, int[] base) {
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) {
            double v = pose[i];
            if (!finite(v)) v = ApiValidator.servoCalHome(i + 1);
            int lo = ApiValidator.servoCalMin(i + 1);
            int hi = ApiValidator.servoCalMax(i + 1);
            // base 可能有超出 app 手動安全範圍嘅值（例如將來改 boot pose）。
            // 只放寬到 base 本身，唔會再擴大（同 app-ik.js ikClampPose）。
            if (base != null && i < base.length) {
                if (base[i] < lo) lo = base[i];
                if (base[i] > hi) hi = base[i];
            }
            int r = (int) Math.round(v);
            if (r < lo) r = lo;
            if (r > hi) r = hi;
            out[i] = r;
        }
        return out;
    }

    private static int[] intOf(double[] a) {
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) out[i] = (int) Math.round(a[i]);
        return out;
    }

    private static double[] toDouble(int[] base) {
        double[] out = new double[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (base != null && i < base.length)
                    ? base[i] : ApiValidator.servoCalHome(i + 1);
        }
        return out;
    }

    // ---- 五種動作（同 app-ik.js ikPoseSquat／ikPoseYSquat／ikPoseLr／ikPoseFb） ----

    /**
     * 呢個 IK 動作會唔會控制 index（0-based）嗰粒舵機？
     * 腳 7-16（index 6-15）全部動作都會控制；蹲式／左右一字馬
     * 另外控制 2/5（index 1/4）肩 roll；左前／右前另外控制
     * 1/4（index 0/3）肩 pitch 前後擺。其他軸完全唔郁。
     */
    public static boolean affects(String kind, int index) {
        kind = normalizeKind(kind);
        if (index >= 6 && index <= 15) return true;
        if (index == 1 || index == 4) {
            return "ysquat".equals(kind) || "lr".equals(kind);
        }
        if (index == 0 || index == 3) {
            return "fsplit".equals(kind) || "fsplit2".equals(kind);
        }
        return false;
    }

    private static int[] poseSquat(double[] base, int pct) {
        double p = pct / 100.0;
        double[] out = base.clone();
        for (int i = 6; i < 16; i++) {
            out[i] = base[i] + (SQUAT_POSE[i] - base[i]) * p;
        }
        return clampPose(out, intOf(base));
    }

    private static int[] poseYSquat(double[] base, int pct) {
        double p = pct / 100.0;
        double[] out = base.clone();
        int[] axes = {1, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        for (int j = 0; j < axes.length; j++) {
            int i = axes[j];
            out[i] = base[i] + (YSQUAT_POSE[i] - base[i]) * p;
        }
        return clampPose(out, intOf(base));
    }

    private static int[] poseLr(double[] base, int pct) {
        double p = pct / 100.0;
        double[] out = base.clone();
        double d = splitMaxD(base) * p;
        out[6] = base[6] + d;    // #7  right hip roll
        out[10] = base[10] + d;  // #11 right ankle roll
        out[11] = base[11] - d;  // #12 left hip roll
        out[15] = base[15] - d;  // #16 left ankle roll
        out[1] = base[1] + (SPLIT_ARM_R - base[1]) * p; // #2 right shoulder roll
        out[4] = base[4] + (SPLIT_ARM_L - base[4]) * p; // #5 left shoulder roll
        return clampPose(out, intOf(base));
    }

    private static int[] poseFb(double[] base, int pct, FbEnd end, String kind) {
        double p = pct / 100.0;
        double[] out = base.clone();
        boolean rightFwd = isFSplit2(kind);
        int arm1 = rightFwd ? FSPLIT2_ARM_1 : FSPLIT_ARM_1;
        int arm4 = rightFwd ? FSPLIT2_ARM_4 : FSPLIT_ARM_4;
        out[0] = Math.round(base[0] + (arm1 - base[0]) * p); // #1 right shoulder pitch
        out[3] = Math.round(base[3] + (arm4 - base[3]) * p); // #4 left shoulder pitch
        out[7] = Math.round(base[7] + (end.s8 - base[7]) * p);   // #8  right hip pitch
        out[8] = Math.round(base[8] + (end.s9 - base[8]) * p);   // #9  right knee
        out[12] = Math.round(base[12] + (end.s13 - base[12]) * p); // #13 left hip pitch
        out[13] = Math.round(base[13] + (end.s14 - base[13]) * p); // #14 left knee
        double r10 = squatAnkle(out, true, end.pitchR);
        double l15 = squatAnkle(out, false, end.pitchL);
        int[] rg10 = rangeOf(10), rg15 = rangeOf(15);
        out[9] = Math.round(Math.max(rg10[0], Math.min(rg10[1], r10)));  // #10 right ankle pitch
        out[14] = Math.round(Math.max(rg15[0], Math.min(rg15[1], l15))); // #15 left ankle pitch
        return clampPose(out, intOf(base));
    }

    /**
     * 計 20 軸 IK 目標位姿。
     *
     * @param kind "squat"／"ysquat"／"lr"／"fsplit"／"fsplit2"
     *             （"fb" 係 fsplit 嘅相容別名；其他一律回 base）
     * @param pct  0–100（0＝base）
     * @param base 20 軸 base pose（1–20 號舵機）
     */
    public static int[] compute(String kind, int pct, int[] base) {
        kind = normalizeKind(kind);
        int p = Math.max(0, Math.min(100, pct));
        double[] b = toDouble(base);
        if (p <= 0) return clampPose(b, base);
        if ("squat".equals(kind)) return poseSquat(b, p);
        if ("ysquat".equals(kind)) return poseYSquat(b, p);
        if ("lr".equals(kind)) return poseLr(b, p);
        if ("fsplit".equals(kind) || "fsplit2".equals(kind)) {
            return poseFb(b, p, fsplitEnd(b, kind), kind);
        }
        return clampPose(b, base);
    }

    /**
     * 同 {@link #compute(String, int, int[])} 一樣，但 IK 動作唔控制嘅軸
     * 用 {@code current} 目前實際值，唔會拉返 base。用嚟做緊 IK 時保留
     * 頭／手／肩 pitch／肘等無關舵機。
     *
     * @param current 20 軸目前值（可為 null；null 即同 3-arg 版一樣）
     */
    public static int[] compute(String kind, int pct, int[] base, int[] current) {
        int[] pose = compute(kind, pct, base);
        if (current == null || !isKind(kind)) return pose;
        for (int i = 0; i < 20 && i < current.length; i++) {
            if (!affects(kind, i)) pose[i] = current[i];
        }
        return clampPose(toDouble(pose), base);
    }

    /** 動作名：squat／ysquat／lr／fsplit／fsplit2（fb 為 fsplit 別名）。 */
    public static boolean isKind(String kind) {
        kind = normalizeKind(kind);
        return "squat".equals(kind) || "ysquat".equals(kind)
                || "lr".equals(kind) || "fsplit".equals(kind)
                || "fsplit2".equals(kind);
    }
}
