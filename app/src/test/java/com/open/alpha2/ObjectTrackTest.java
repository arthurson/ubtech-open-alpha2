package com.open.alpha2;

/**
 * ObjectTrackLogic 單元測試 (零依賴，不用 JUnit)。
 *
 * 同 ColorTrackTest 一樣經 `scripts/test-apivalidator.py` 用純 javac/java 跑
 *（ObjectTrackLogic 只用 java.*）。合成場景：160x90 偽隨機背景＋一塊有紋理嘅目標貼片。
 */
public final class ObjectTrackTest {
    private static int passed = 0;
    private static int failed = 0;

    private ObjectTrackTest() {}

    private static final int W = 160;
    private static final int H = 90;
    private static final int PW = 24;
    private static final int PH = 20;

    private static void check(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xFF;
    }

    /** 背景：低頻起伏＋細噪，唔會同貼片混淆。 */
    private static int bg(int x, int y) {
        int base = 90 + (int) (30 * Math.sin(x / 11.0) + 25 * Math.cos(y / 7.0));
        return clamp(base + (hash(x, y, 1) % 12) - 6);
    }

    /** 貼片紋理（歸一化座標 u,v∈[0,1)）：棋盤＋斜紋，對比高。 */
    private static int patch(float u, float v) {
        int cu = (int) (u * 6);
        int cv = (int) (v * 5);
        int chk = ((cu + cv) & 1) == 0 ? 220 : 40;
        if (((int) (u * 24) + (int) (v * 20)) % 7 == 0) chk = 128;
        return chk;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 渲染一幀：貼片左上 (px,py)、尺度 s、亮度偏移 bright；present=false 則冇貼片。 */
    private static int[] frame(float px, float py, float s, int bright, boolean present) {
        int[] g = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                g[y * W + x] = clamp(bg(x, y) + bright);
            }
        }
        if (present) {
            int pw = Math.round(PW * s);
            int ph = Math.round(PH * s);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int gx = (int) px + x;
                    int gy = (int) py + y;
                    if (gx < 0 || gy < 0 || gx >= W || gy >= H) continue;
                    g[gy * W + gx] = clamp(patch((x + 0.5f) / pw, (y + 0.5f) / ph) + bright);
                }
            }
        }
        return g;
    }

    private static ObjectTrackLogic.Tracker newTracker(float px, float py) {
        ObjectTrackLogic.Tracker t = new ObjectTrackLogic.Tracker();
        boolean ok = t.init(frame(px, py, 1f, 0, true), W, H,
                px / W, py / H, (float) PW / W, (float) PH / H);
        check(ok, "init ok");
        return t;
    }

    public static void main(String[] args) {
        // 1. 基本：貼片位移 (+10,+5) 即跟到
        {
            ObjectTrackLogic.Tracker t = newTracker(40, 30);
            check(t.texture() > 20f, "texture high");
            ObjectTrackLogic.Result r = t.update(frame(50, 35, 1f, 0, true), W, H, 0.14f, true);
            check(r.found, "move found");
            check(Math.abs(r.cx * W - (50 + PW / 2f)) <= 2f, "move cx " + r.cx * W);
            check(Math.abs(r.cy * H - (35 + PH / 2f)) <= 2f, "move cy " + r.cy * H);
            check(r.score < 0.08f, "move score " + r.score);
        }
        // 2. 連續移動（每幀 +4px）跟 15 幀
        {
            ObjectTrackLogic.Tracker t = newTracker(20, 40);
            boolean all = true;
            float lastCx = 0;
            for (int i = 1; i <= 15; i++) {
                float px = 20 + 4 * i;
                ObjectTrackLogic.Result r = t.update(frame(px, 40, 1f, 0, true), W, H, 0.14f, true);
                if (!r.found || Math.abs(r.cx * W - (px + PW / 2f)) > 3f) all = false;
                lastCx = r.cx * W;
            }
            check(all, "continuous track " + lastCx);
        }
        // 3. 整體變亮（+35）仍跟到（去平均值）
        {
            ObjectTrackLogic.Tracker t = newTracker(60, 30);
            ObjectTrackLogic.Result r = t.update(frame(64, 32, 1f, 35, true), W, H, 0.14f, true);
            check(r.found, "bright found");
            check(Math.abs(r.cx * W - (64 + PW / 2f)) <= 2f, "bright cx");
        }
        // 4. 變大：貼片 ×1.11 → 跟到且框變大
        {
            ObjectTrackLogic.Tracker t = newTracker(50, 30);
            float s = 1f;
            ObjectTrackLogic.Result r = null;
            for (int i = 0; i < 4; i++) {
                s *= 1.11f;
                float cx = 50 + PW / 2f;
                float cy = 30 + PH / 2f;
                r = t.update(frame(cx - PW * s / 2f, cy - PH * s / 2f, s, 0, true),
                        W, H, 0.16f, true);
            }
            check(r != null && r.found, "scale found");
            check(r != null && r.w * W > PW * 1.25f, "scale grew " + (r == null ? -1 : r.w * W));
        }
        // 5. 目標消失 → 跟丟；再喺遠處出現 → 全圖重新搵返
        {
            ObjectTrackLogic.Tracker t = newTracker(30, 30);
            ObjectTrackLogic.Result r = t.update(frame(0, 0, 1f, 0, false), W, H, 0.14f, true);
            check(!r.found, "gone not found");
            check(t.isLost(), "lost flag");
            r = t.update(frame(110, 55, 1f, 0, true), W, H, 0.14f, true);
            check(r.found && r.reacquired, "reacquired");
            check(Math.abs(r.cx * W - (110 + PW / 2f)) <= 3f, "reacq cx " + r.cx * W);
            check(!t.isLost(), "lost cleared");
        }
        // 5b. 偵測圖尺寸變咗（解像度換咗）：2x 放大幀仍跟到，位置歸一化後一致
        {
            ObjectTrackLogic.Tracker t = newTracker(40, 30);
            int[] small = frame(44, 32, 1f, 0, true);
            int W2 = W * 2, H2 = H * 2;
            int[] big = new int[W2 * H2];
            for (int y = 0; y < H2; y++) {
                for (int x = 0; x < W2; x++) big[y * W2 + x] = small[(y / 2) * W + (x / 2)];
            }
            ObjectTrackLogic.Result r = t.update(big, W2, H2, 0.14f, true);
            check(r.found, "resize found");
            check(Math.abs(r.cx - (44 + PW / 2f) / W) < 0.03f, "resize cx " + r.cx);
            check(Math.abs(r.cy - (32 + PH / 2f) / H) < 0.04f, "resize cy " + r.cy);
        }
        // 6. 框太細拒絕；未 init 即 update 回 not found
        {
            ObjectTrackLogic.Tracker t = new ObjectTrackLogic.Tracker();
            check(!t.init(frame(0, 0, 1f, 0, false), W, H, 0.1f, 0.1f, 0.02f, 0.02f), "tiny rejected");
            check(!t.update(frame(0, 0, 1f, 0, false), W, H, 0.14f, true).found, "no target");
        }
        // 7. 純色模板 texture 低（用嚟提示用戶）
        {
            int[] flat = new int[W * H];
            java.util.Arrays.fill(flat, 100);
            ObjectTrackLogic.Tracker t = new ObjectTrackLogic.Tracker();
            t.init(flat, W, H, 0.3f, 0.3f, 0.2f, 0.2f);
            check(t.texture() < 1f, "flat texture low");
        }
        // 8. toGray／integral
        {
            int[] argb = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};
            int[] g = new int[4];
            ObjectTrackLogic.toGray(argb, 4, g);
            check(g[0] == 76 && g[1] == 149 && g[2] == 28 && g[3] == 255, "toGray " + g[0] + "," + g[1] + "," + g[2] + "," + g[3]);
            int[] a = {1, 2, 3, 4};
            long[] ii = new long[9];
            ObjectTrackLogic.buildIntegral(a, 2, 2, ii);
            check(ii[8] == 10 && ii[4] == 1 && ii[5] == 3 && ii[7] == 4, "integral");
        }

        System.out.println("ObjectTrackTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
