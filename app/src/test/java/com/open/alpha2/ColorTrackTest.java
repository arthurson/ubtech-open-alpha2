package com.open.alpha2;

/**
 * ColorTrackLogic 單元測試 (零依賴，不用 JUnit)。
 *
 * 同 ApiValidatorTest 一樣經 `scripts/test-apivalidator.py` 用純 javac/java 跑
 *（ColorTrackLogic 只用 java.*，連 android.jar 都唔使掂）。
 * `./gradlew assembleDebug` 完全唔受影響 (唔喺 main sourceSet)。
 */
public final class ColorTrackTest {
    private static int passed = 0;
    private static int failed = 0;

    private ColorTrackTest() {}

    private static void eq(float expected, float actual, String name) {
        if (Math.abs(expected - actual) < 0.5f) {
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

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eqNull(Object actual, String name) {
        if (actual == null) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected null");
        }
    }

    public static void main(String[] args) {
        // ── rgbToHsv：三原色＋灰階 ──
        float[] red = ColorTrackLogic.rgbToHsv(255, 0, 0);
        eq(0f, red[0], "hsv.red.h");
        eq(1f, red[1], "hsv.red.s");
        eq(1f, red[2], "hsv.red.v");
        float[] green = ColorTrackLogic.rgbToHsv(0, 255, 0);
        eq(120f, green[0], "hsv.green.h");
        float[] blue = ColorTrackLogic.rgbToHsv(0, 0, 255);
        eq(240f, blue[0], "hsv.blue.h");
        float[] white = ColorTrackLogic.rgbToHsv(255, 255, 255);
        eq(0f, white[1], "hsv.white.s");
        eq(1f, white[2], "hsv.white.v");
        float[] black = ColorTrackLogic.rgbToHsv(0, 0, 0);
        eq(0f, black[2], "hsv.black.v");
        float[] gray = ColorTrackLogic.rgbToHsv(128, 128, 128);
        eq(0f, gray[1], "hsv.gray.s");
        // 非整 hue：黃 (255,255,0) = 60°。
        float[] yellow = ColorTrackLogic.rgbToHsv(255, 255, 0);
        eq(60f, yellow[0], "hsv.yellow.h");

        // ── hueInRange：普通＋wrap＋端點 inclusive ──
        eq(true, ColorTrackLogic.hueInRange(10f, 0f, 30f), "hue.plain.in");
        eq(false, ColorTrackLogic.hueInRange(40f, 0f, 30f), "hue.plain.out");
        eq(true, ColorTrackLogic.hueInRange(0f, 0f, 30f), "hue.plain.loEdge");
        eq(true, ColorTrackLogic.hueInRange(350f, 335f, 25f), "hue.wrap.hi");
        eq(true, ColorTrackLogic.hueInRange(0f, 335f, 25f), "hue.wrap.zero");
        eq(true, ColorTrackLogic.hueInRange(25f, 335f, 25f), "hue.wrap.edge");
        eq(true, ColorTrackLogic.hueInRange(335f, 335f, 25f), "hue.wrap.loEdge");
        eq(false, ColorTrackLogic.hueInRange(30f, 335f, 25f), "hue.wrap.outHi");
        eq(false, ColorTrackLogic.hueInRange(330f, 335f, 25f), "hue.wrap.outLo");

        // ── matchHsv：預設紅色檔 (335-25, s>=0.45, v 0.25-1) ──
        eq(true, ColorTrackLogic.matchHsv(0f, 1f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.red");
        eq(true, ColorTrackLogic.matchHsv(350f, 0.9f, 0.8f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.redWrap");
        eq(false, ColorTrackLogic.matchHsv(120f, 1f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.green");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.2f, 0.9f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.lowSat");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.9f, 0.1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.dark");
        eq(false, ColorTrackLogic.matchHsv(0f, 0f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 1f), "match.white");
        // vMax 收緊去白光：v=1 出局。
        eq(false, ColorTrackLogic.matchHsv(0f, 0.9f, 1f, 335f, 25f, 0.45f, 1f, 0.25f, 0.9f), "match.vMax");
        // sMax 收緊去彩色（白色 preset 用 sMax=0.25）：鮮紅出局，灰白入局。
        eq(false, ColorTrackLogic.matchHsv(0f, 1f, 1f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.red");
        eq(true, ColorTrackLogic.matchHsv(0f, 0.1f, 0.95f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.white");
        eq(false, ColorTrackLogic.matchHsv(0f, 0.1f, 0.4f, 0f, 360f, 0f, 0.25f, 0.6f, 1f), "match.sMax.dark");

        // ── largestBlob：5x5，(1..2,1..2) 2x2 塊＋(4,4) 孤點 ──
        boolean[] mask = new boolean[25];
        mask[1 * 5 + 1] = true;
        mask[1 * 5 + 2] = true;
        mask[2 * 5 + 1] = true;
        mask[2 * 5 + 2] = true;
        mask[4 * 5 + 4] = true;
        ColorTrackLogic.Blob b = ColorTrackLogic.largestBlob(mask, 5, 5, 2);
        eq(4, b.count, "blob.count");
        eq(1, b.x0, "blob.x0");
        eq(2, b.x1, "blob.x1");
        eq(1, b.y0, "blob.y0");
        eq(2, b.y1, "blob.y1");
        eq(6, (int) b.sumX, "blob.sumX");
        eq(6, (int) b.sumY, "blob.sumY");
        // 門檻以上無嘢即 null。
        eqNull(ColorTrackLogic.largestBlob(mask, 5, 5, 5), "blob.minPixels");
        eqNull(ColorTrackLogic.largestBlob(new boolean[25], 5, 5, 1), "blob.empty");
        eqNull(ColorTrackLogic.largestBlob(null, 5, 5, 1), "blob.null");
        // 對角掂點唔連：兩粒斜對點係兩個域，最大 count=1。
        boolean[] diag = new boolean[25];
        diag[0] = true;
        diag[6] = true;
        ColorTrackLogic.Blob d = ColorTrackLogic.largestBlob(diag, 5, 5, 1);
        eq(1, d.count, "blob.diagonal");
        // mask 唔被改寫（caller 重用）：再跑一次結果一樣。
        ColorTrackLogic.Blob b2 = ColorTrackLogic.largestBlob(mask, 5, 5, 2);
        eq(4, b2.count, "blob.reusable");

        // ── walkDecision：偏就轉、置中遠就行前、近就企 ──
        eq(true, "left".equals(ColorTrackLogic.walkDecision(-0.5f, 0.05f, 0.35f, 0.12f)), "walk.left");
        eq(true, "right".equals(ColorTrackLogic.walkDecision(0.5f, 0.05f, 0.35f, 0.12f)), "walk.right");
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(0.1f, 0.05f, 0.35f, 0.12f)), "walk.forward");
        eq(true, "none".equals(ColorTrackLogic.walkDecision(0.1f, 0.2f, 0.35f, 0.12f)), "walk.close");
        // 死區邊界 inclusive：啱好 0.35 唔轉（行前，因為細）。
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(0.35f, 0.05f, 0.35f, 0.12f)), "walk.edgeTurn");
        eq(true, "forward".equals(ColorTrackLogic.walkDecision(-0.35f, 0.05f, 0.35f, 0.12f)), "walk.edgeTurnNeg");
        // 夠近邊界：啱好 0.12 即企。
        eq(true, "none".equals(ColorTrackLogic.walkDecision(0f, 0.12f, 0.35f, 0.12f)), "walk.edgeClose");
        // 偏＋近：轉向優先（對正先好行）。
        eq(true, "left".equals(ColorTrackLogic.walkDecision(-0.6f, 0.5f, 0.35f, 0.12f)), "walk.turnFirst");

        // ── matchMask：同 rgbToHsv＋matchHsv 逐像素一致（隨機 20000 像素＋多組閾值，固定 seed）──
        java.util.Random rnd = new java.util.Random(20260511L);
        float[][] ths = {
            { 335f, 25f, 0.45f, 1.0f, 0.25f, 1.0f },   // 預設紅（wrap）
            { 90f, 150f, 0.30f, 1.0f, 0.20f, 0.95f },  // 綠
            { 0f, 360f, 0.0f, 0.25f, 0.60f, 1.0f },    // 白（低飽和）
        };
        int[] pxs = new int[20000];
        for (int i = 0; i < pxs.length; i++) pxs[i] = rnd.nextInt();
        // 補灰階／純色邊角。
        pxs[0] = 0xFF000000; pxs[1] = 0xFFFFFFFF; pxs[2] = 0xFFFF0000; pxs[3] = 0xFF00FF00;
        pxs[4] = 0xFF0000FF; pxs[5] = 0xFF808080;
        boolean[] mk = new boolean[pxs.length];
        for (int t = 0; t < ths.length; t++) {
            float[] th = ths[t];
            int mm = ColorTrackLogic.matchMask(pxs, pxs.length, mk, th[0], th[1], th[2], th[3], th[4], th[5]);
            int ref = 0;
            boolean same = true;
            for (int i = 0; i < pxs.length; i++) {
                int px = pxs[i];
                float[] hsv = ColorTrackLogic.rgbToHsv((px >> 16) & 0xFF, (px >> 8) & 0xFF, px & 0xFF);
                boolean e = ColorTrackLogic.matchHsv(hsv[0], hsv[1], hsv[2], th[0], th[1], th[2], th[3], th[4], th[5]);
                if (e) ref++;
                if (e != mk[i]) same = false;
            }
            eq(true, same, "matchMask.sameAsRef" + t);
            eq(ref, mm, "matchMask.count" + t);
        }

        // ── pickBlob：Scratch 重用結果同 largestBlob 一致；連續性揀近者 ──
        {
            int w = 20, h = 10;
            boolean[] m2 = new boolean[w * h];
            // 左 blob 6x4=24 像素，右 blob 5x4=20 像素（右較細但 >= 一半）；3x1 雜點。
            for (int y = 2; y < 6; y++) for (int x = 1; x < 7; x++) m2[y * w + x] = true;
            for (int y = 2; y < 6; y++) for (int x = 13; x < 18; x++) m2[y * w + x] = true;
            for (int x = 9; x < 12; x++) m2[8 * w + x] = true;
            ColorTrackLogic.Scratch sc = new ColorTrackLogic.Scratch();
            ColorTrackLogic.Blob big0 = ColorTrackLogic.largestBlob(m2, w, h, 3);
            ColorTrackLogic.Blob big1 = ColorTrackLogic.pickBlob(m2, w, h, 3, sc, 0f, 0f, false);
            ColorTrackLogic.Blob big2 = ColorTrackLogic.pickBlob(m2, w, h, 3, sc, 0f, 0f, false); // 重用
            eq(24, big0.count, "pick.largest");
            eq(true, big1.count == big0.count && big2.count == big0.count && big1.sumX == big2.sumX, "pick.scratchReuse");
            // 上一幀質心喺右邊（fx≈0.77）：右 blob 夠大（20*2>=24）→ 揀右。
            ColorTrackLogic.Blob nearRight = ColorTrackLogic.pickBlob(m2, w, h, 3, sc, 0.77f, 0.4f, true);
            eq(20, nearRight.count, "pick.continuityRight");
            // 上一幀喺左邊：揀左（同最大一致）。
            ColorTrackLogic.Blob nearLeft = ColorTrackLogic.pickBlob(m2, w, h, 3, sc, 0.2f, 0.4f, true);
            eq(24, nearLeft.count, "pick.continuityLeft");
            // 雜點 3 像素唔夠大（3*2<24）：就算上一幀喺雜點嗰邊都唔搶。
            ColorTrackLogic.Blob nearNoise = ColorTrackLogic.pickBlob(m2, w, h, 3, sc, 0.5f, 0.85f, true);
            eq(true, nearNoise.count >= 20, "pick.noiseNotChosen");
            // minPixels 過濾：全部細過即 null。
            eqNull(ColorTrackLogic.pickBlob(m2, w, h, 100, sc, 0f, 0f, false), "pick.allTooSmall");
            // 傳細過 w*h 嘅 mask／null scratch：回 null，唔炸。
            eqNull(ColorTrackLogic.pickBlob(new boolean[3], w, h, 3, sc, 0f, 0f, false), "pick.shortMask");
            eqNull(ColorTrackLogic.pickBlob(m2, w, h, 3, null, 0f, 0f, false), "pick.nullScratch");
            // 兩件大細一樣：揀近上一幀者（唔固定揀先掃到嗰件）。
            boolean[] m3 = new boolean[w * h];
            for (int y = 2; y < 6; y++) for (int x = 1; x < 6; x++) m3[y * w + x] = true;
            for (int y = 2; y < 6; y++) for (int x = 13; x < 18; x++) m3[y * w + x] = true;
            ColorTrackLogic.Blob tieRight = ColorTrackLogic.pickBlob(m3, w, h, 3, sc, 0.8f, 0.4f, true);
            eq(true, tieRight.sumX / tieRight.count > 10, "pick.tieFollowsPrevRight");
            ColorTrackLogic.Blob tieLeft = ColorTrackLogic.pickBlob(m3, w, h, 3, sc, 0.15f, 0.4f, true);
            eq(true, tieLeft.sumX / tieLeft.count < 10, "pick.tieFollowsPrevLeft");
        }

        // ── headOffset：pan 增＝向右＝正；halfFov<=0 保護 ──
        eq(true, Math.abs(ColorTrackLogic.headOffset(120, 120, 30f)) < 1e-6f, "headOffset.home");
        eq(true, Math.abs(ColorTrackLogic.headOffset(150, 120, 30f) - 1.0f) < 1e-6f, "headOffset.rightFullFov");
        eq(true, Math.abs(ColorTrackLogic.headOffset(105, 120, 30f) + 0.5f) < 1e-6f, "headOffset.left");
        eq(true, Math.abs(ColorTrackLogic.headOffset(150, 120, 0f)) < 1e-6f, "headOffset.zeroFov");
        // 頭已向左 30° 但色喺畫面中間：身體方位偏左一個半視角（原本 dx 單睇會當 0）。
        eq(true, ColorTrackLogic.headOffset(90, 120, 30f) + 0f < -0.99f, "headOffset.bodyBearingBug");

        System.out.println("ColorTrackTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
