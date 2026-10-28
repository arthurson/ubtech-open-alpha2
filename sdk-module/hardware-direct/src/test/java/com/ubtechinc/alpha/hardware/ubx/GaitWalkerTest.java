package com.ubtechinc.alpha.hardware.ubx;

import java.util.ArrayList;
import java.util.List;

/**
 * GaitProgram／GaitWalker 單元測試（零依賴，同 SerialFrameCodecTest 一樣 main() runner，
 * 經 scripts/test-gaitwalker.py 用 javac/java 直接編譯執行；GaitWalker 只掂 java.*）。
 *
 * 用即時時鐘＋極短格長（20-60ms），所以量時間嘅斷言全部留足寬容，唔會喺慢機器上誤紅。
 */
public final class GaitWalkerTest {
    private static int passed = 0;
    private static int failed = 0;

    private GaitWalkerTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            System.out.println("  expected=" + expected + " actual=" + actual);
        }
        check(ok, name);
    }

    // ---- fake sender ---------------------------------------------------------

    private static final class Rec {
        final int[] a;
        final int ms;
        final long at;
        Rec(int[] a, int ms, long at) { this.a = a; this.ms = ms; this.at = at; }
    }

    private static final class FakeSender implements GaitWalker.Sender {
        final List<Rec> sent = new ArrayList<Rec>();
        volatile boolean fail;
        @Override public boolean send(int[] angles, int timeMs) {
            if (fail) return false;
            synchronized (sent) {
                sent.add(new Rec(angles.clone(), timeMs, System.nanoTime() / 1000000L));
            }
            return true;
        }
        int size() { synchronized (sent) { return sent.size(); } }
        Rec get(int i) { synchronized (sent) { return sent.get(i); } }
        Rec last() { synchronized (sent) { return sent.get(sent.size() - 1); } }
    }

    private static int[] pose(int v0) {
        int[] p = new int[20];
        for (int i = 0; i < 20; i++) p[i] = 120;
        p[0] = v0;
        return p;
    }

    /** leadIn 2 格（100/110）、cycle 4 格（100,110,125,115），home 全 120。 */
    private static GaitProgram prog(int frameMs) {
        return new GaitProgram(
                new int[][]{pose(100), pose(110)}, new int[]{frameMs, frameMs},
                new int[][]{pose(100), pose(110), pose(125), pose(115)},
                new int[]{frameMs, frameMs, frameMs, frameMs},
                pose(120), 5, 40, 200, 3, 0);
    }

    /** 無 leadIn、cycle 4 格（100,110,125,115），著地格＝第 2 格（pose 125），home 全 120。 */
    private static GaitProgram progStand(int frameMs, int[] stand) {
        return new GaitProgram(
                new int[0][], new int[0],
                new int[][]{pose(100), pose(110), pose(125), pose(115)},
                new int[]{frameMs, frameMs, frameMs, frameMs},
                pose(120), 5, 40, 200, 3, 0, stand);
    }

    private static Thread start(GaitWalker w) {
        Thread t = new Thread(w, "test-walker");
        t.start();
        return t;
    }

    private static boolean waitFor(java.util.concurrent.Callable<Boolean> c, long timeoutMs)
            throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (c.call()) return true;
            Thread.sleep(5);
        }
        return c.call();
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static long nowMs() { return System.nanoTime() / 1000000L; }

    public static void main(String[] args) throws Exception {
        // ---- GaitProgram 驗證 ----
        boolean threw = false;
        try {
            new GaitProgram(new int[0][], new int[0], new int[][]{pose(1)}, new int[]{40},
                    pose(120), 5, 40, 200, 3, 0);
        } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "program: cycle < 2 frames rejected");
        threw = false;
        try {
            int[] bad = pose(120);
            bad[3] = 300;
            new GaitProgram(new int[0][], new int[0],
                    new int[][]{pose(1), bad}, new int[]{40, 40}, pose(120), 5, 40, 200, 3, 0);
        } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "program: angle > 255 rejected");
        threw = false;
        try {
            new GaitProgram(new int[0][], new int[0],
                    new int[][]{pose(1), pose(2)}, new int[]{40, 5}, pose(120), 5, 40, 200, 3, 0);
        } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "program: ms < 20 rejected");
        eq(30, GaitProgram.maxDelta(pose(90), pose(120)), "maxDelta");

        // ---- 速度夾限 ----
        eq(2.0f, GaitWalker.clampSpeed(10f), "clampSpeed max");
        eq(0.5f, GaitWalker.clampSpeed(0.01f), "clampSpeed min");
        eq(1.0f, GaitWalker.clampSpeed(Float.NaN), "clampSpeed NaN -> 1");

        // ---- 1) 次序：起步 → 循環（含 wrap）→ 放手收步 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(40), fs, null, 1.0f, 0);
            Thread t = start(w);
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() { return w.cycles() >= 1 && w.phase() == GaitWalker.PHASE_WALKING; }
            }, 3000), "sequence: reaches walking + completes a cycle");
            w.release();
            t.join(3000);
            check(!t.isAlive(), "sequence: thread ends after release");
            eq(GaitWalker.PHASE_DONE, w.phase(), "sequence: phase DONE");
            eq(100, fs.get(0).a[0], "sequence: lead-in frame 0");
            eq(110, fs.get(1).a[0], "sequence: lead-in frame 1");
            int[] exp = {100, 110, 125, 115};
            boolean order = true;
            for (int i = 0; i < 6 && 2 + i < fs.size() - 1; i++) {
                if (fs.get(2 + i).a[0] != exp[i % 4]) order = false;
            }
            check(order, "sequence: cycle order + wrap");
            check(fs.size() >= 8, "sequence: sent >= 8 frames (got " + fs.size() + ")");
            Rec last = fs.last();
            eq(120, last.a[0], "sequence: last frame is home");
            check(last.ms >= 40 && last.ms <= 200, "sequence: blend time in [min,max] (" + last.ms + ")");
            eq("released", w.endReason(), "sequence: endReason released");
        }

        // ---- 2) 放手延遲：大格（600ms）中途放手要即刻收步 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(600), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(150);                       // 喺第一格等待中
            long t0 = nowMs();
            w.release();
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() { return w.phase() >= GaitWalker.PHASE_STANDING; }
            }, 1000), "release: enters standing");
            // 收步指令要喺放手後極短時間內送出（唔使等完 600ms）
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() { return true; }
            }, 1), "noop");
            Thread.sleep(30);
            Rec last = fs.last();
            check(last.a[0] == 120, "release: home sent");
            check(last.at - t0 < 150, "release: home sent within 150ms of release (" + (last.at - t0) + ")");
            t.interrupt();
            t.join(2000);
        }

        // ---- 3) 硬停：interrupt 唔送收步 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(40), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(150);
            t.interrupt();
            t.join(2000);
            check(!t.isAlive(), "hard stop: thread ends");
            int n = fs.size();
            check(n > 0 && fs.last().a[0] != 120 || n > 0, "hard stop: sent something");
            boolean homeSent = false;
            for (int i = 0; i < n; i++) if (fs.get(i).a[0] == 120) homeSent = true;
            check(!homeSent, "hard stop: no home blend");
            eq("stopped", w.endReason(), "hard stop: endReason");
        }

        // ---- 4) 租約：冇 beat 自動放手；有 beat 持續 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(30), fs, null, 1.0f, 120);
            Thread t = start(w);
            long end = nowMs() + 400;
            while (nowMs() < end) { w.beat(); Thread.sleep(25); }
            check(t.isAlive() && w.phase() <= GaitWalker.PHASE_WALKING, "lease: alive while beating");
            // 停 beat → 120ms 後自動放手＋收步
            t.join(2000);
            check(!t.isAlive(), "lease: ends after beats stop");
            eq("lease", w.endReason(), "lease: endReason");
            eq(120, fs.last().a[0], "lease: home sent");
        }

        // ---- 5) 變速（1x→2x 格時長約減半；0.5x 約加倍）----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(60), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(400);
            w.setSpeed(2.0f);
            Thread.sleep(500);
            Rec fast = null;
            synchronized (fs.sent) {
                for (int i = fs.sent.size() - 1; i >= 0; i--) {
                    if (fs.sent.get(i).a[0] != 120) { fast = fs.sent.get(i); break; }
                }
            }
            check(fast != null && fast.ms <= 36, "speed 2x: frame time ~30ms (" + (fast == null ? -1 : fast.ms) + ")");
            w.setSpeed(0.5f);
            Thread.sleep(700);
            Rec slow = fs.last();
            check(slow.ms >= 100, "speed 0.5x: frame time ~120ms (" + slow.ms + ")");
            w.release();
            t.join(3000);
            check(!t.isAlive(), "speed: ends");
        }

        // ---- 6) 起步第一格：離 home 好遠要慢過限速 ----
        {
            FakeSender fs = new FakeSender();
            GaitWalker w = new GaitWalker(prog(40), fs, pose(20), 1.0f, 0);   // 差 80 → 80*3=240ms
            Thread t = start(w);
            Thread.sleep(80);
            check(fs.size() >= 1 && fs.get(0).ms >= 240, "entry guard: first frame >= 240ms ("
                    + (fs.size() > 0 ? fs.get(0).ms : -1) + ")");
            w.release();
            t.interrupt();
            t.join(2000);
        }

        // ---- 7) 未開始已放手：乜都唔送 ----
        {
            FakeSender fs = new FakeSender();
            GaitWalker w = new GaitWalker(prog(40), fs, null, 1.0f, 0);
            w.release();
            Thread t = start(w);
            t.join(2000);
            eq(0, fs.size(), "release-before-start: nothing sent");
            check(!t.isAlive(), "release-before-start: thread ends");
        }

        // ---- 8) 連續送失敗：放棄，唔死循環 ----
        {
            FakeSender fs = new FakeSender();
            fs.fail = true;
            GaitWalker w = new GaitWalker(prog(30), fs, null, 1.0f, 0);
            Thread t = start(w);
            t.join(3000);
            check(!t.isAlive(), "send-fail: thread gives up");
            eq("send-failed", w.endReason(), "send-fail: endReason");
            eq(0, fs.size(), "send-fail: nothing recorded");
        }

        // ---- 9) 收步時間 = 偏差 × msPerUnit（夾 [min,max]）----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(prog(40), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(250);
            w.release();
            t.join(3000);
            int n = fs.size();
            Rec home = fs.get(n - 1);
            Rec a = fs.get(n - 2);
            Rec b = n >= 3 ? fs.get(n - 3) : a;
            int dev = Math.max(GaitProgram.maxDelta(a.a, pose(120)), GaitProgram.maxDelta(b.a, pose(120)));
            int expect = Math.max(40, Math.min(200, dev * 5));
            eq(expect, home.ms, "blend: time = clamp(dev*5)");
        }

        // ---- 10) 收步期間 interrupt（再起步搶佔）即停 ----
        {
            FakeSender fs = new FakeSender();
            GaitProgram slowBlend = new GaitProgram(
                    new int[0][], new int[0],
                    new int[][]{pose(60), pose(180)}, new int[]{40, 40},
                    pose(120), 20, 400, 3000, 3, 0);
            final GaitWalker w = new GaitWalker(slowBlend, fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(150);
            w.release();
            check(waitFor(new java.util.concurrent.Callable<Boolean>() {
                public Boolean call() { return w.phase() == GaitWalker.PHASE_STANDING; }
            }, 1000), "stand-down: phase STANDING");
            check(w.isFinishing(), "stand-down: isFinishing");
            long t0 = nowMs();
            t.interrupt();
            t.join(1000);
            check(!t.isAlive() && nowMs() - t0 < 500, "stand-down: interrupt ends quickly");
        }

        // ---- 11) 著地收步格：放手後行到著地格先收步 ----
        {
            boolean bad = false;
            try { progStand(40, new int[]{9}); } catch (IllegalArgumentException e) { bad = true; }
            check(bad, "standFrames: out-of-range index rejected");
            check(progStand(40, new int[]{2}).isStandFrame(2) && !progStand(40, new int[]{2}).isStandFrame(1),
                    "standFrames: isStandFrame");
            check(prog(40).standFrames.length == 0, "standFrames: old 10-arg ctor = none");

            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(progStand(100, new int[]{2}), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(130);                       // 行緊循環中途
            w.release();
            t.join(3000);
            check(!t.isAlive(), "stand-frame: thread ends");
            int n = fs.size();
            Rec home = fs.get(n - 1);
            Rec lastCycle = fs.get(n - 2);
            eq(120, home.a[0], "stand-frame: last is home");
            eq(125, lastCycle.a[0], "stand-frame: frame before home is the stand frame");
            eq("released", w.endReason(), "stand-frame: endReason released");
            // 順序仍然係循環順序（每格係上一格嘅下一格）
            int[] cyc = {100, 110, 125, 115};
            boolean order = true;
            for (int i = 1; i < n - 1; i++) {
                int prev = indexOf(cyc, fs.get(i - 1).a[0]);
                int cur = indexOf(cyc, fs.get(i).a[0]);
                if (prev < 0 || cur < 0 || cur != (prev + 1) % 4) order = false;
            }
            check(order, "stand-frame: cycle order kept while finishing");
        }

        // ---- 12) 租約過期：唔等著地格，即刻收步 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(progStand(600, new int[]{2}), fs, null, 1.0f, 100);
            long t0 = nowMs();
            Thread t = start(w);
            t.join(3000);
            check(!t.isAlive(), "lease+stand: thread ends");
            eq("lease", w.endReason(), "lease+stand: endReason lease");
            int n = fs.size();
            eq(120, fs.get(n - 1).a[0], "lease+stand: home sent");
            check(n == 2 && fs.get(0).a[0] == 100, "lease+stand: no extra cycle frames (n=" + n + ")");
            check(nowMs() - t0 < 1500, "lease+stand: ended well before reaching stand frame");
        }

        // ---- 13) 著地收步期間硬停：唔送收步 ----
        {
            FakeSender fs = new FakeSender();
            final GaitWalker w = new GaitWalker(progStand(400, new int[]{2}), fs, null, 1.0f, 0);
            Thread t = start(w);
            Thread.sleep(150);
            w.release();
            Thread.sleep(20);
            t.interrupt();
            t.join(2000);
            check(!t.isAlive(), "stand-frame hard stop: thread ends");
            boolean homeSent = false;
            for (int i = 0; i < fs.size(); i++) if (fs.get(i).a[0] == 120) homeSent = true;
            check(!homeSent, "stand-frame hard stop: no home blend");
            eq("stopped", w.endReason(), "stand-frame hard stop: endReason");
        }

        System.out.println("GaitWalkerTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
