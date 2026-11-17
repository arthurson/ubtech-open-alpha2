package com.ubtechinc.alpha.hardware.policy;

/**
 * Alpha2 policy 嘅輸入／輸出契約（純數學，無 Android 依賴，可單元測試）。
 *
 * <p>同 ubtech-alpha2-urdf 嘅 {@code rl/alpha2_env.py} 一致：
 * <ul>
 *   <li>關節順序＝servo ID 1..20（即 {@code config/servo_map.yaml} 順序）。</li>
 *   <li>{@code servo_deg = degrees(q) + servo_at_q0}，{@code q = radians(servo_deg - servo_at_q0)}
 *       （所有關節 sign=1，軸方向已喺 URDF axis 內）。</li>
 *   <li>預設姿勢 q0 ＝ {@code radians(q_init_deg)}（＝官方開機企姿，servo＝InitAngle）。</li>
 *   <li>stand obs(43)＝[ q-q0 (20) | 上一個 action (20) | 重力方向（base 座標，企直＝(0,0,-1)）(3) ]。</li>
 *   <li>walk obs(65)＝stand obs ＋ [ ref_q-q0 (20) | sin,cos(步態相位) (2) ]。</li>
 *   <li>stand 控制＝{@code q0 + act_scale*clip(a,-1,1)}；walk 殘差控制＝{@code ref_q + act_scale*clip(a,-1,1)}；
 *       再 clamp 入校準範圍。</li>
 * </ul>
 * 常量由 servo_map.yaml 抄入，{@code Alpha2PolicyIoTest} 有一個對 yaml 嘅交叉檢查，改一邊要改另一邊。</p>
 */
public final class Alpha2PolicyIo {
    private Alpha2PolicyIo() { }

    public static final int N = 20;
    public static final int OBS_STAND = 43;
    public static final int OBS_WALK = 65;

    /** servo_map.yaml q_init_deg，servo ID 1..20。 */
    static final int[] Q_INIT_DEG = {
            0, -90, 0, 0, 90, 0, 1, -35, -45, 15,
            1, -1, 35, 45, -15, 1, 0, 0, 0, -15};
    /** servo_map.yaml servo_at_q0（q=0 時嘅 servo 角度），servo ID 1..20。 */
    static final int[] SERVO_AT_Q0 = {
            120, 210, 120, 120, 30, 120, 119, 100, 190, 120,
            119, 121, 140, 50, 120, 119, 120, 120, 120, 135};

    /** 預設姿勢 q0（rad）。 */
    public static double[] qDefault() {
        double[] q = new double[N];
        for (int i = 0; i < N; i++) q[i] = Math.toRadians(Q_INIT_DEG[i]);
        return q;
    }

    /** 預設姿勢嘅 servo 角度（＝官方 InitAngle）。 */
    public static int[] servoDefault() {
        int[] s = new int[N];
        for (int i = 0; i < N; i++) s[i] = Q_INIT_DEG[i] + SERVO_AT_Q0[i];
        return s;
    }

    public static double[] servoToQ(int[] servo) {
        if (servo == null || servo.length < N) throw new IllegalArgumentException("need 20 servo angles");
        double[] q = new double[N];
        for (int i = 0; i < N; i++) q[i] = Math.toRadians(servo[i] - SERVO_AT_Q0[i]);
        return q;
    }

    /** q（rad）→ servo 整數角度（四捨五入，clamp 入 [lo,hi]；lo/hi 長度 ≥20，servo ID 1..20）。 */
    public static int[] qToServo(double[] q, int[] lo, int[] hi) {
        int[] s = new int[N];
        for (int i = 0; i < N; i++) {
            long v = Math.round(Math.toDegrees(q[i]) + SERVO_AT_Q0[i]);
            if (v < lo[i]) v = lo[i];
            if (v > hi[i]) v = hi[i];
            s[i] = (int) v;
        }
        return s;
    }

    /**
     * 加速度計軸映射預設（sensor → head 座標 x 前／y 左／z 上），由 fall recovery 與 policy 共用。
     * 實機驗證：機身前後方向為 sensor y 軸（+y＝趴住／front，-y＝仰躺／back），左右方向為 sensor x，
     * 因此 head x 取 sensor -y、head y 取 sensor +x、head z 取 sensor +z（右手系）。
     * 舊預設 {@code +x+y+z} 有誤（前後與左右互換）。
     */
    public static final String DEFAULT_ACCEL_AXES = "-y+x+z";

    /** 舊的（有誤）預設值；已保存的 manifest／設定若出現此值，視為預設並自動改用 {@link #DEFAULT_ACCEL_AXES}。 */
    public static final String LEGACY_ACCEL_AXES = "+x+y+z";

    /**
     * 解析加速度計軸對應，例如 "-y+x+z"（預設，見 {@link #DEFAULT_ACCEL_AXES}）。三個記號依序代表 head 座標系的
     * x(前)、y(左)、z(上) 分別取自 sensor 嘅邊個軸同正負。回 [±(1..3)]×3。
     */
    public static int[] parseAxes(String s) {
        if (s == null || s.isEmpty()) s = DEFAULT_ACCEL_AXES;
        int[] m = new int[3];
        int k = 0;
        for (int i = 0; i + 1 < s.length() && k < 3; i += 2) {
            char sg = s.charAt(i), ax = Character.toLowerCase(s.charAt(i + 1));
            int sign = sg == '-' ? -1 : (sg == '+' ? 1 : 0);
            int idx = ax == 'x' ? 1 : (ax == 'y' ? 2 : (ax == 'z' ? 3 : 0));
            if (sign == 0 || idx == 0) throw new IllegalArgumentException("bad accel_axes: " + s);
            m[k++] = sign * idx;
        }
        if (k != 3 || s.length() != 6) throw new IllegalArgumentException("accel_axes must look like +x+y+z: " + s);
        boolean[] used = new boolean[4];
        for (int v : m) {
            if (used[Math.abs(v)]) throw new IllegalArgumentException("accel_axes repeats an axis: " + s);
            used[Math.abs(v)] = true;
        }
        return m;
    }

    /**
     * 由加速度計（Android TYPE_ACCELEROMETER，m/s²，靜止平放時 z≈+9.8＝比力向上）估計
     * 重力方向喺 base 座標嘅單位向量（企直＝(0,0,-1)）。
     *
     * <p>步驟：sensor→head 座標（axes 對應）→ 重力方向 g_head＝-a/|a| → 經頭部關節轉入 base：
     * head_yaw 軸 (0,0,-1)、head_pitch 軸 (0,1,0)（URDF：base→head_yaw_link→head_pitch_link，
     * 兩個 joint 原點無旋轉），即 R_base←head ＝ Rz(-qYaw)·Ry(qPitch)。</p>
     *
     * <p>注意：URDF 假設 accel_link 軸同頭部座標系對齊，<b>未喺實機驗證</b>；行動中嘅線加速度亦會
     * 污染 a（模擬入面係理想重力）。回 null＝讀數無效（|a| 不在 [5,15] m/s²，例如撞擊／自由落體）。</p>
     */
    public static double[] gravityBase(double[] a, int[] axes, double qYaw, double qPitch) {
        if (a == null || a.length < 3) return null;
        double mag = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        if (!(mag >= 5.0 && mag <= 15.0)) return null;
        double[] h = new double[3];
        for (int i = 0; i < 3; i++) {
            int m = axes[i];
            double v = a[Math.abs(m) - 1] * (m < 0 ? -1 : 1);
            h[i] = -v / mag;                       // 比力反向＝重力方向
        }
        // g_base = Rz(-qYaw) * Ry(qPitch) * g_head
        double cp = Math.cos(qPitch), sp = Math.sin(qPitch);
        double x1 = cp * h[0] + sp * h[2], y1 = h[1], z1 = -sp * h[0] + cp * h[2];
        double cy = Math.cos(-qYaw), sy = Math.sin(-qYaw);
        return new double[] {cy * x1 - sy * y1, sy * x1 + cy * y1, z1};
    }

    /** 軀幹傾角（rad）：重力方向同 (0,0,-1) 嘅夾角。 */
    public static double tiltRad(double[] g) {
        double c = -g[2];
        if (c > 1) c = 1;
        if (c < -1) c = -1;
        return Math.acos(c);
    }

    public static float[] obsStand(double[] q, float[] prevAct, double[] g) {
        double[] q0 = qDefault();
        float[] o = new float[OBS_STAND];
        for (int i = 0; i < N; i++) o[i] = (float) (q[i] - q0[i]);
        for (int i = 0; i < N; i++) o[N + i] = prevAct[i];
        o[40] = (float) g[0];
        o[41] = (float) g[1];
        o[42] = (float) g[2];
        return o;
    }

    /** phase＜0 表示起步段（sin/cos 為 0，同 alpha2_env.py 一致）。 */
    public static float[] obsWalk(double[] q, float[] prevAct, double[] g, double[] refQ, double phase) {
        float[] s = obsStand(q, prevAct, g);
        float[] o = new float[OBS_WALK];
        System.arraycopy(s, 0, o, 0, OBS_STAND);
        double[] q0 = qDefault();
        for (int i = 0; i < N; i++) o[OBS_STAND + i] = (float) (refQ[i] - q0[i]);
        o[63] = phase < 0 ? 0f : (float) Math.sin(phase);
        o[64] = phase < 0 ? 0f : (float) Math.cos(phase);
        return o;
    }

    /**
     * get-up obs（65）＝stand obs（43）＋ (ref_q − q0)（20）＋ [min(t/T,1), t>=T ? 1 : 0]
     * （同 ubtech-alpha2-urdf rl/alpha2_env.py 嘅 GetUpEnv._gobs 一致；t＝ref 格位置，T＝ref 總格數）。
     */
    public static float[] obsGetUp(double[] q, float[] prevAct, double[] g, double[] refQ, double progress, boolean over) {
        float[] s = obsStand(q, prevAct, g);
        float[] o = new float[OBS_WALK];
        System.arraycopy(s, 0, o, 0, OBS_STAND);
        double[] q0 = qDefault();
        for (int i = 0; i < N; i++) o[OBS_STAND + i] = (float) (refQ[i] - q0[i]);
        o[63] = (float) Math.min(Math.max(progress, 0.0), 1.0);
        o[64] = over ? 1f : 0f;
        return o;
    }

    /** 統一 obs 長度（ubtech-alpha2-urdf rl/alpha2_env.py：全部 task 共用）。 */
    public static final int OBS_UNIFIED = 68;

    /**
     * 統一 obs（68）：[0:20] q−q0，[20:40] prev action，[40:43] 重力，[43:46] 速度指令 (vx m/s, vy m/s, wz rad/s)，
     * [46:48] phase 特徵，[48:68] 基準目標 − q0（stand／walk＝0；walk_ref／get-up＝ref−q0）。
     */
    public static float[] obsUnified(double[] q, float[] prevAct, double[] g, double[] cmd, double ph0, double ph1, double[] target) {
        float[] s = obsStand(q, prevAct, g);
        float[] o = new float[OBS_UNIFIED];
        System.arraycopy(s, 0, o, 0, OBS_STAND);
        if (cmd != null) for (int i = 0; i < 3; i++) o[OBS_STAND + i] = (float) cmd[i];
        o[46] = (float) ph0;
        o[47] = (float) ph1;
        double[] q0 = qDefault();
        for (int i = 0; i < N; i++) o[48 + i] = (float) (target[i] - q0[i]);
        return o;
    }

    /** 統一格式嘅目標 q：基準 ＋ delta（弧度；policy 嘅第二個輸出，＝SCALE×action）。 */
    public static double[] targetQDelta(double[] base, float[] delta) {
        double[] t = new double[N];
        for (int i = 0; i < N; i++) t[i] = base[i] + delta[i];
        return t;
    }

    /** 躺低方向：重力喺機身座標嘅 x 分量主導。GetUpEnv 嘅起始姿勢：back＝(-1,0,0)，front＝(+1,0,0)。 */
    public static final String LYING_BACK = "back", LYING_FRONT = "front";
    public static final double LYING_X_MIN = 0.8;      // |g_x| ≥ 0.8（離躺平 ≤ ~37°）先當躺低

    /** @return "back"／"front"（躺低）；否則 ""（企住、側躺或唔明）。 */
    public static String lyingKind(double[] g) {
        if (g == null || g.length < 3) return "";
        if (g[0] <= -LYING_X_MIN) return LYING_BACK;
        if (g[0] >= LYING_X_MIN) return LYING_FRONT;
        return "";
    }

    /** 由 action（已 clip 到 [-1,1]）算目標 q。base＝q0（stand）或 ref_q（walk／get-up 殘差）。 */
    public static double[] targetQ(double[] base, float[] action, double actScale) {
        double[] t = new double[N];
        for (int i = 0; i < N; i++) {
            double a = action[i];
            if (a > 1) a = 1;
            if (a < -1) a = -1;
            t[i] = base[i] + actScale * a;
        }
        return t;
    }
}
