package com.ubtechinc.alpha.hardware.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * OnnxMlp／Alpha2PolicyIo／PolicyManifest／PolicyRunner 單元測試（零依賴 main() runner，
 * 同 GaitWalkerTest 一樣經 scripts/test-policy.py 用 javac/java 執行）。
 *
 * <p>args[0]＝由 {@code scripts/policy_tool.py make-fixtures} 生成嘅 fixtures 目錄
 * （含 onnxruntime 對照向量，所以每次跑都係同真 ORT 比較）。時間斷言留足寬容。</p>
 */
public final class PolicyRunnerTest {
    private static int passed = 0;
    private static int failed = 0;
    private static Path fx;

    private PolicyRunnerTest() { }

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) System.out.println("  expected=" + expected + " actual=" + actual);
        check(ok, name);
    }

    private static byte[] bytes(String f) throws Exception { return Files.readAllBytes(fx.resolve(f)); }

    private static final int[] LO = {5, 50, 55, 5, 30, 55, 100, 20, 35, 35, 100, 40, 20, 10, 25, 50, 95, 95, 75, 105};
    private static final int[] HI = {235, 210, 185, 235, 190, 185, 200, 220, 230, 215, 190, 140, 220, 205, 205, 140, 125, 125, 165, 155};
    private static final double[] UPRIGHT = {0, 0, 9.81};

    // ---- fakes ----------------------------------------------------------------

    private static class Sink implements PolicyRunner.Sender {
        final List<int[]> sent = new ArrayList<int[]>();
        final List<Integer> ms = new ArrayList<Integer>();
        volatile boolean fail;
        @Override public boolean send(int[] a, int t) {
            if (fail) return false;
            synchronized (sent) { sent.add(a.clone()); ms.add(t); }
            return true;
        }
        int n() { synchronized (sent) { return sent.size(); } }
        int[] last() { synchronized (sent) { return sent.get(sent.size() - 1); } }
    }

    private static PolicyRunner.Config cfg(int period) {
        PolicyRunner.Config c = new PolicyRunner.Config();
        c.periodMs = period; c.lo = LO; c.hi = HI; c.leaseMs = 0; c.maxRunMs = 5000;
        return c;
    }

    private static PolicyRunner make(String model, PolicyRunner.Config c, Sink s, final double[][] accel,
            final int[] pose) throws Exception {
        final int[] idx = {0};
        return new PolicyRunner(OnnxMlp.load(bytes(model)), c, s, new PolicyRunner.AccelSource() {
            @Override public double[] accel() {
                int i = Math.min(idx[0]++, accel.length - 1);
                return accel[i];
            }
        }, new PolicyRunner.PoseSource() {
            @Override public int[] pose() { return pose == null ? null : pose.clone(); }
        });
    }

    private static PolicyRunner.AccelSource fixed(final double[] a) {
        return new PolicyRunner.AccelSource() { @Override public double[] accel() { return a; } };
    }

    private static PolicyRunner.PoseSource poseOf(final int[] p) {
        return new PolicyRunner.PoseSource() { @Override public int[] pose() { return p == null ? null : p.clone(); } };
    }

    /** 喺獨立線程跑，等到結束或 timeout。 */
    private static void runFor(PolicyRunner r, long timeoutMs) throws Exception {
        Thread t = new Thread(r);
        t.start();
        t.join(timeoutMs);
        if (t.isAlive()) { t.interrupt(); t.join(2000); }
    }

    // ---- ONNX parity ----------------------------------------------------------

    private static void parity(String onnx, String vec) throws Exception {
        OnnxMlp m = OnnxMlp.load(bytes(onnx));
        List<String> L = Files.readAllLines(fx.resolve(vec));
        String[] h = L.get(0).split(" ");
        int din = Integer.parseInt(h[0]), dout = Integer.parseInt(h[1]), n = Integer.parseInt(h[2]);
        eq(din, m.inputDim(), onnx + " input dim");
        eq(dout, m.outputDim(), onnx + " output dim");
        double worst = 0;
        for (int i = 0; i < n; i++) {
            String[] xs = L.get(1 + 2 * i).split(" "), ys = L.get(2 + 2 * i).split(" ");
            float[] x = new float[din];
            for (int j = 0; j < din; j++) x[j] = Float.parseFloat(xs[j]);
            float[] y = m.run(x);
            for (int j = 0; j < dout; j++) worst = Math.max(worst, Math.abs(y[j] - Float.parseFloat(ys[j])));
        }
        System.out.println("  " + onnx + " parity vs onnxruntime: max |err| = " + worst);
        check(worst < 2e-5, onnx + " parity < 2e-5");
    }

    private static void testOnnx() throws Exception {
        parity("walk_forward.onnx", "walk.txt");
        parity("syn.onnx", "syn.txt");
        try {
            OnnxMlp.load(bytes("bad_op.onnx"));
            check(false, "bad op rejected");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("Softmax"), "bad op names the op: " + e.getMessage());
        }
        try { OnnxMlp.load(new byte[] {1, 2, 3, 4}); check(false, "garbage rejected"); }
        catch (IllegalArgumentException e) { check(true, "garbage rejected"); }
        try { OnnxMlp.load(bytes("syn.onnx")).run(new float[3]); check(false, "wrong obs length rejected"); }
        catch (IllegalArgumentException e) { check(true, "wrong obs length rejected"); }
        // 截斷檔案唔可以 crash 成其他例外
        byte[] full = bytes("walk_forward.onnx");
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);
        try { OnnxMlp.load(cut); check(false, "truncated model rejected"); }
        catch (IllegalArgumentException e) { check(true, "truncated model rejected"); }
        catch (RuntimeException e) { check(false, "truncated model threw " + e.getClass().getSimpleName()); }
    }

    // ---- contract math ----------------------------------------------------------

    private static void testIo() {
        int[] d = Alpha2PolicyIo.servoDefault();
        double[] q = Alpha2PolicyIo.servoToQ(d);
        double[] q0 = Alpha2PolicyIo.qDefault();
        double e = 0;
        for (int i = 0; i < 20; i++) e = Math.max(e, Math.abs(q[i] - q0[i]));
        check(e < 1e-12, "servoToQ(servoDefault) == qDefault");
        int[] back = Alpha2PolicyIo.qToServo(q, LO, HI);
        check(java.util.Arrays.equals(d, back), "qToServo roundtrip");
        // 官方 InitAngle：右肩 roll 120、右膝 145、左髖 pitch 175、頭 pitch 120
        eq(120, d[1], "init servo 2 = 120");
        eq(145, d[8], "init servo 9 = 145");
        eq(175, d[12], "init servo 13 = 175");
        eq(120, d[19], "init servo 20 = 120");
        // clamp 入校準範圍
        double[] far = q0.clone();
        far[2] = 10;
        eq(HI[2], Alpha2PolicyIo.qToServo(far, LO, HI)[2], "qToServo clamps high");
        far[2] = -10;
        eq(LO[2], Alpha2PolicyIo.qToServo(far, LO, HI)[2], "qToServo clamps low");

        int[] ax = Alpha2PolicyIo.parseAxes("+x+y+z");
        double[] g = Alpha2PolicyIo.gravityBase(UPRIGHT, ax, 0, 0);
        check(Math.abs(g[0]) < 1e-9 && Math.abs(g[1]) < 1e-9 && Math.abs(g[2] + 1) < 1e-9, "upright gravity = (0,0,-1)");
        // 獨立推導：身體前傾 theta（Ry(theta)），頭 yaw/pitch 為 (yaw,pitch)
        double[][] cases = {{30, 0, 0}, {30, 20, 0}, {-15, 0, 25}, {10, -20, 40}, {0, 30, -45}};
        for (double[] c : cases) {
            double th = Math.toRadians(c[0]), pitch = Math.toRadians(c[1]), yaw = Math.toRadians(c[2]);
            double[] gb = {Math.sin(th), 0, -Math.cos(th)};                       // R_body^T (0,0,-1), R_body=Ry(th)
            double[][] Rh = mul(rz(-yaw), ry(pitch));                             // base<-head 頭部相對 base
            double[] gh = mulv(transpose(Rh), gb);                                // head 座標重力
            double[] a = {-gh[0] * 9.81, -gh[1] * 9.81, -gh[2] * 9.81};           // 比力＝-g
            double[] got = Alpha2PolicyIo.gravityBase(a, ax, yaw, pitch);
            double err = Math.abs(got[0] - gb[0]) + Math.abs(got[1] - gb[1]) + Math.abs(got[2] - gb[2]);
            check(err < 1e-9, "gravityBase body pitch " + c[0] + " head p/y " + c[1] + "/" + c[2] + " err=" + err);
            check(Math.abs(Alpha2PolicyIo.tiltRad(got) - Math.abs(th)) < 1e-9, "tilt = body pitch " + c[0]);
        }
        check(Alpha2PolicyIo.gravityBase(new double[] {0, 0, 0.5}, ax, 0, 0) == null, "free-fall reading rejected");
        check(Alpha2PolicyIo.gravityBase(new double[] {0, 0, 40}, ax, 0, 0) == null, "shock reading rejected");
        // 軸重映射：sensor x 向上
        double[] gm = Alpha2PolicyIo.gravityBase(new double[] {9.81, 0, 0}, Alpha2PolicyIo.parseAxes("+z+y-x"), 0, 0);
        // head_z = -sensor_x? "+z+y-x": head x<-sensor z, head y<-sensor y, head z<- -sensor x ⇒ az=-9.81 ⇒ g_z=+1（倒轉）
        check(Math.abs(gm[2] - 1) < 1e-9, "axis remap applied");
        for (String bad : new String[] {"+x+x+z", "xyz", "+x+y", "+a+y+z", "+x+y+z+x"}) {
            try { Alpha2PolicyIo.parseAxes(bad); check(false, "bad axes " + bad); }
            catch (IllegalArgumentException ex) { check(true, "bad axes rejected " + bad); }
        }
        // obs 佈局
        float[] prev = new float[20];
        prev[7] = 0.25f;
        float[] o = Alpha2PolicyIo.obsStand(q0, prev, new double[] {0.1, 0.2, -0.9});
        eq(43, o.length, "stand obs length");
        check(o[0] == 0f && o[20 + 7] == 0.25f && o[40] == 0.1f && o[42] == -0.9f, "stand obs layout");
        double[] refq = q0.clone();
        refq[0] += 0.5;
        float[] w = Alpha2PolicyIo.obsWalk(q0, prev, new double[] {0, 0, -1}, refq, Math.PI / 2);
        check(w.length == 65 && Math.abs(w[43] - 0.5f) < 1e-6 && Math.abs(w[63] - 1f) < 1e-6 && Math.abs(w[64]) < 1e-6, "walk obs layout");
        float[] w0 = Alpha2PolicyIo.obsWalk(q0, prev, new double[] {0, 0, -1}, refq, -1);
        check(w0[63] == 0f && w0[64] == 0f, "walk start-up phase zeros");
    }

    private static double[][] rz(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}}; }
    private static double[][] ry(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}}; }
    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }
    private static double[][] transpose(double[][] a) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = a[j][i];
        return r;
    }
    private static double[] mulv(double[][] a, double[] v) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) for (int k = 0; k < 3; k++) r[i] += a[i][k] * v[k];
        return r;
    }

    // ---- manifest ----------------------------------------------------------------

    private static PolicyManifest man() {
        PolicyManifest m = new PolicyManifest();
        m.name = "t"; m.obsDim = 43; m.controlHz = 2.5;
        m.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        return m;
    }

    private static void expectBad(PolicyManifest m, String what) {
        try { m.validate(); check(false, "manifest rejects " + what); }
        catch (IllegalArgumentException e) { check(true, "manifest rejects " + what); }
    }

    private static void testManifest() {
        PolicyManifest m = man();
        m.validate();
        String[] why = new String[1];
        check(m.realRunAllowed(why), "2.5 Hz policy may run on hardware");
        eq(400, m.periodMs(), "period of 2.5 Hz");
        m.controlHz = 50;
        m.validate();
        check(!m.realRunAllowed(why) && why[0].contains("fast=true"), "50 Hz policy needs fast=true: " + why[0]);
        check(m.realRunAllowed(why, true), "50 Hz policy allowed with fast=true");
        eq(20, m.periodMs(), "period of 50 Hz");
        m.controlHz = 100;
        m.validate();
        check(!m.realRunAllowed(why, true) && why[0].contains("20 ms"), "100 Hz refused even with fast: " + why[0]);
        eq(200, PolicyManifest.MIN_PERIOD_MS, "normal floor is 200 ms");
        m = man(); m.controlHz = 5;
        check(m.realRunAllowed(why), "200 ms (5 Hz) runs without fast");
        m = man(); m.controlHz = 4;
        check(m.realRunAllowed(why), "250 ms (4 Hz) runs without fast");
        m = man(); m.controlHz = 1000.0 / 300;
        check(m.realRunAllowed(why), "300 ms is allowed");
        m = man(); m.controlHz = 5.1;
        check(!m.realRunAllowed(why), "196 ms needs fast");
        check(m.realRunAllowed(why, true), "196 ms ok with fast");
        m = man(); m.sha256 = "";
        m.validate();
        check(!m.realRunAllowed(why), "unsigned policy is dry-run only");
        m = man(); m.name = "../x"; expectBad(m, "path-ish name");
        m = man(); m.name = ".hidden"; expectBad(m, "dot-leading name");
        m = man(); m.name = "a..b"; expectBad(m, "name containing ..");
        m = man(); m.name = "a/b"; expectBad(m, "name with slash");
        m = man(); m.name = ""; expectBad(m, "empty name");
        check(PolicyManifest.validName("alpha2-stand_v1.2"), "normal name accepted");
        m = man(); m.obs = "run"; expectBad(m, "unknown obs");
        m = man(); m.obsDim = 65; expectBad(m, "obs_dim mismatch");
        m = man(); m.obs = "walk"; m.obsDim = 65; expectBad(m, "walk without ref");
        m = man(); m.actScale = 2; expectBad(m, "huge act_scale");
        m = man(); m.moveOverlap = 3; expectBad(m, "huge overlap");
        m = man(); m.maxSpeedDps = 500; expectBad(m, "huge speed");
        m = man(); m.maxSpeedDps = 250; m.validate(); check(true, "250 deg/s accepted");
        m = man(); m.accelAxes = "+x+x+z"; expectBad(m, "bad axes");
        m = man(); m.sha256 = "ABC"; expectBad(m, "bad sha");
        m = man(); m.schema = 2; expectBad(m, "schema 2");
    }

    // ---- runner -----------------------------------------------------------------

    private static int expectedServo(int i, double b, double scale) {
        long v = Math.round(Math.toDegrees(Math.toRadians(Alpha2PolicyIo.Q_INIT_DEG[i]) + scale * b) + Alpha2PolicyIo.SERVO_AT_Q0[i]);
        return (int) Math.max(LO[i], Math.min(HI[i], v));
    }

    private static void testRunner() throws Exception {
        int[] def = Alpha2PolicyIo.servoDefault();

        // 1) zero action：停喺預設姿勢；move 時間＝period×overlap
        Sink s = new Sink();
        PolicyRunner.Config c = cfg(20); c.maxRunMs = 300;
        PolicyRunner r = make("zero43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        eq("max_run", r.endReason(), "zero: ends by max_run");
        check(s.n() >= 5, "zero: sent several frames (" + s.n() + ")");
        check(java.util.Arrays.equals(def, s.last()), "zero: holds default stance");
        eq(Integer.valueOf(21), s.ms.get(0), "move time = period*overlap");
        check(!r.isRunning(), "zero: not running after end");

        // 2) const action：落喺 q0+0.3*B 對應嘅 servo（速度包絡放寬）
        double[] B = new double[20];
        String[] bs = new String(Files.readAllBytes(fx.resolve("const43.txt")), "UTF-8").trim().split(" ");
        for (int i = 0; i < 20; i++) B[i] = Float.parseFloat(bs[i]);
        s = new Sink();
        c = cfg(20); c.maxRunMs = 400; c.maxSpeedDps = 100000;
        r = make("const43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        int[] last = s.last();
        boolean allOk = true;
        for (int i = 0; i < 20; i++) {
            int want = expectedServo(i, (float) B[i], 0.3);
            if (last[i] != want) { allOk = false; System.out.println("  axis " + (i + 1) + " got " + last[i] + " want " + want); }
        }
        check(allOk, "const: servo targets = q0 + 0.3*B");
        check(Math.abs(r.lastAction()[1] - (float) B[1]) < 1e-6, "const: lastAction exposed");

        // 3) 速度包絡＋clip：action 5.0 會 clip 成 1；每格每軸變化 ≤ floor(90°/s × move)
        s = new Sink();
        c = cfg(100); c.maxRunMs = 700;           // move 105 ms → 9.45° → 9°/格
        r = make("big43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 4000);
        int prevMax = 0;
        boolean envOk = true, moved = false;
        int[] p = def;
        for (int k = 0; k < s.n(); k++) {
            int[] cmd;
            synchronized (s.sent) { cmd = s.sent.get(k); }
            for (int i = 0; i < 20; i++) {
                int d = Math.abs(cmd[i] - p[i]);
                if (d > 9) envOk = false;
                if (d > prevMax) prevMax = d;
                if (d > 0) moved = true;
            }
            p = cmd;
        }
        check(envOk, "envelope: per-step delta <= 9 deg (max seen " + prevMax + ")");
        check(moved, "envelope: it does move toward the target");
        for (int i = 0; i < 20; i++) {
            int tgt = (int) Math.max(LO[i], Math.min(HI[i], Math.round(Math.toDegrees(Math.toRadians(Alpha2PolicyIo.Q_INIT_DEG[i]) + 0.3) + Alpha2PolicyIo.SERVO_AT_Q0[i])));
            if (Math.abs(p[i] - def[i]) > Math.abs(tgt - def[i])) { envOk = false; }
        }
        check(envOk, "action clip: never overshoots q0+0.3 rad");
        for (int[] cmd : s.sent) for (int i = 0; i < 20; i++) check(cmd[i] >= LO[i] && cmd[i] <= HI[i], "in calibrated range");

        // 3b) fast（50Hz）：move 21ms，speed 200°/s ⇒ floor(4.2)=4°/格；到位後唔再動
        s = new Sink();
        c = cfg(20); c.maxRunMs = 500; c.maxSpeedDps = 200;
        r = make("big43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 4000);
        boolean ok4 = true;
        p = def;
        for (int k = 0; k < s.n(); k++) {
            int[] cmd;
            synchronized (s.sent) { cmd = s.sent.get(k); }
            for (int i = 0; i < 20; i++) if (Math.abs(cmd[i] - p[i]) > 4) ok4 = false;
            p = cmd;
        }
        check(ok4, "fast: per-step delta <= 4 deg at 200 deg/s, 21 ms");
        check(s.n() >= 15, "fast: ~50 Hz pacing sends many frames (" + s.n() + ")");
        eq(Integer.valueOf(21), s.ms.get(0), "fast: move time 21 ms");
        check(r.maxStepUs() > 0 && r.lastInferUs() > 0, "timing metrics recorded");
        eq("max_run", r.endReason(), "fast: normal pacing does not trip overrun (overruns=" + r.overruns() + ")");

        // 3c) overrun：送出慢過 period（30ms vs 10ms）⇒ 視窗內遲到太多即停
        final Sink slow = new Sink() {
            @Override public boolean send(int[] a, int tm) {
                try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.send(a, tm);
            }
        };
        c = cfg(10); c.maxRunMs = 5000; c.overrunWindow = 10; c.overrunLimit = 5;
        r = make("zero43.onnx", c, slow, new double[][] {UPRIGHT}, def);
        runFor(r, 4000);
        eq("overrun", r.endReason(), "slow sender trips overrun");
        check(r.overruns() >= 5 && slow.n() <= 12, "overrun stops quickly (sent " + slow.n() + ")");
        check(r.maxSendUs() >= 25000, "maxSendUs tracks slow send (" + r.maxSendUs() + ")");

        // 4) 租約
        s = new Sink();
        c = cfg(20); c.leaseMs = 150; c.maxRunMs = 5000;
        r = make("zero43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        eq("lease", r.endReason(), "lease expiry stops");
        // beat 續期
        s = new Sink();
        c = cfg(20); c.leaseMs = 150; c.maxRunMs = 600;
        final PolicyRunner rb = make("zero43.onnx", c, s, new double[][] {UPRIGHT}, def);
        Thread tb = new Thread(rb); tb.start();
        for (int k = 0; k < 10; k++) { Thread.sleep(50); rb.beat(); }
        tb.join(3000);
        eq("max_run", rb.endReason(), "beat keeps the lease alive");

        // 5) 傾倒：60° 前傾
        s = new Sink();
        // 預設姿勢頭 pitch ≠ 0（servo 20 = 120 ⇒ q = -15°），頭 frame 相對 base 傾 (body θ + head pitch)：
        // 身體前傾 θ=60° ⇒ 頭 frame 傾 θ+p；a = -g_head*9.81，g_head=(sin(θ+p),0,-cos(θ+p))
        double th = Math.toRadians(60), hp = Alpha2PolicyIo.servoToQ(def)[19], tot = th + hp;
        check(Math.abs(Math.toDegrees(hp) + 15) < 1e-9, "default head pitch is -15 deg");
        r = make("zero43.onnx", cfg(20), s, new double[][] {{-9.81 * Math.sin(tot), 0, 9.81 * Math.cos(tot)}}, def);
        runFor(r, 3000);
        eq("fall", r.endReason(), "tilt 60 deg => fall");
        eq(0, s.n(), "fall: nothing sent");
        check(Math.abs(r.tiltDeg() - 60) < 1e-6, "fall: tilt reported");

        // 6) 重力無效
        s = new Sink();
        r = make("zero43.onnx", cfg(20), s, new double[][] {null}, def);
        runFor(r, 3000);
        eq("gravity_invalid", r.endReason(), "no accel at start stops");
        eq(0, s.n(), "gravity_invalid: nothing sent");
        // 單次 glitch 容忍
        s = new Sink();
        c = cfg(20); c.maxRunMs = 250;
        r = make("zero43.onnx", c, s, new double[][] {UPRIGHT, null, UPRIGHT}, def);
        runFor(r, 3000);
        eq("max_run", r.endReason(), "single bad accel sample tolerated");
        // 連續壞讀數
        s = new Sink();
        r = make("zero43.onnx", cfg(20), s, new double[][] {UPRIGHT, null, null, null, null}, def);
        runFor(r, 3000);
        eq("gravity_invalid", r.endReason(), "3 consecutive bad samples stop");

        // 7) NaN action
        s = new Sink();
        r = make("nan43.onnx", cfg(20), s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        eq("bad_action", r.endReason(), "NaN action stops");
        eq(0, s.n(), "NaN: nothing sent");

        // 8) 位姿未知
        s = new Sink();
        r = make("zero43.onnx", cfg(20), s, new double[][] {UPRIGHT}, null);
        runFor(r, 3000);
        eq("pose_unknown", r.endReason(), "unknown pose refuses to start");
        eq(0, s.n(), "pose_unknown: nothing sent");

        // 9) 送出失敗
        s = new Sink(); s.fail = true;
        r = make("zero43.onnx", cfg(20), s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        eq("send_failed", r.endReason(), "5 send failures stop");

        // 10) interrupt＝硬停
        s = new Sink();
        c = cfg(20); c.maxRunMs = 60000;
        r = make("zero43.onnx", c, s, new double[][] {UPRIGHT}, def);
        Thread t = new Thread(r); t.start();
        Thread.sleep(150);
        check(r.isRunning(), "running before interrupt");
        t.interrupt(); t.join(3000);
        eq("stopped", r.endReason(), "interrupt => stopped");
        check(!r.isRunning(), "not running after interrupt");

        // 11) dry：唔送，但照推進
        s = new Sink();
        c = cfg(20); c.dry = true; c.maxRunMs = 250; c.maxSpeedDps = 100000;
        r = make("const43.onnx", c, s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        eq(0, s.n(), "dry: nothing sent");
        check(r.steps() >= 5, "dry: steps advance (" + r.steps() + ")");
        check(r.lastServo()[1] == expectedServo(1, (float) B[1], 0.3), "dry: servo target computed");

        // 12) walk：需要 ref；有 ref 就跑
        PolicyRunner.Config w = cfg(20);
        w.obsDim = 65; w.actScale = 0.2;
        try { make("walkconst65.onnx", w, new Sink(), new double[][] {UPRIGHT}, def); check(false, "walk w/o ref rejected"); }
        catch (IllegalArgumentException e) { check(true, "walk w/o ref rejected"); }
        double[][] rq = new double[8][];
        for (int i = 0; i < 8; i++) rq[i] = Alpha2PolicyIo.qDefault();
        w.ref = new PolicyRunner.Ref(rq, 2, 6); w.maxRunMs = 250;
        s = new Sink();
        r = make("walkconst65.onnx", w, s, new double[][] {UPRIGHT}, def);
        runFor(r, 3000);
        check(s.n() >= 3 && java.util.Arrays.equals(def, s.last()), "walk: holds ref (=default) with zero residual");
        try { new PolicyRunner.Ref(rq, 5, 5); check(false, "bad ref loop rejected"); }
        catch (IllegalArgumentException e) { check(true, "bad ref loop rejected"); }

        // 12b) ref 隨真實時間推進＋線性內插
        double[][] rr = new double[10][];
        for (int i = 0; i < 10; i++) { rr[i] = new double[20]; rr[i][0] = i; }
        PolicyRunner.Ref rf = new PolicyRunner.Ref(rr, 2, 6, 0.02);
        check(Math.abs(rf.at(0)[0]) < 1e-12 && Math.abs(rf.at(2.5)[0] - 2.5) < 1e-9, "ref interpolates inside loop");
        check(Math.abs(rf.at(5.5)[0] - 3.5) < 1e-9, "ref interpolates across the loop seam (5 -> 2)");
        check(Math.abs(rf.at(6.0)[0] - 2.0) < 1e-9 && Math.abs(rf.at(7.25)[0] - 3.25) < 1e-9, "ref wraps after loopB");
        check(Math.abs(rf.at(1.5)[0] - 1.5) < 1e-9, "ref lead-in is linear");
        check(Math.abs(rf.framesPerStep(20) - 1.0) < 1e-12, "50 Hz + dt 0.02 = 1 frame/step (old behaviour)");
        check(Math.abs(rf.framesPerStep(250) - 12.5) < 1e-12, "250 ms + dt 0.02 = 12.5 frames/step");
        check(Math.abs(rf.framesPerStep(200) - 10.0) < 1e-12, "200 ms + dt 0.02 = 10 frames/step");
        check(Math.abs(new PolicyRunner.Ref(rr, 2, 6, 0.25).framesPerStep(250) - 1.0) < 1e-12, "250 ms + dt 0.25 = 1 frame/step");
        check(Math.abs(rf.phase(2)) < 1e-12 && Math.abs(rf.phase(4) - Math.PI) < 1e-12 && rf.phase(1) < 0, "ref phase");
        try { new PolicyRunner.Ref(rr, 2, 6, 0); check(false, "bad ref dt rejected"); }
        catch (IllegalArgumentException e) { check(true, "bad ref dt rejected"); }

        // 13) obs 維度唔對
        try { make("zero43.onnx", w, new Sink(), new double[][] {UPRIGHT}, def); check(false, "obs_dim mismatch rejected"); }
        catch (IllegalArgumentException e) { check(true, "obs_dim mismatch rejected"); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("usage: PolicyRunnerTest <fixtures-dir>"); System.exit(2); }
        fx = Paths.get(args[0]);
        testOnnx();
        testIo();
        testManifest();
        testRunner();
        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
