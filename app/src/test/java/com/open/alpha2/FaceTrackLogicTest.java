package com.open.alpha2;

/**
 * FaceTrackLogic 單元測試（零依賴，不用 JUnit；同 ColorTrackTest 一樣經
 * scripts/test-apivalidator.py 用純 javac/java 跑）。
 */
public final class FaceTrackLogicTest {
    private static int passed = 0;
    private static int failed = 0;

    private FaceTrackLogicTest() {}

    private static void ok(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    public static void main(String[] args) {
        // ── pickIndex ──
        float[] x = { 0.2f, 0.8f, 0.5f };
        float[] y = { 0.5f, 0.5f, 0.5f };
        // 冇臉／全部無效分數。
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 1f, 1f, 1f }, 0, 0f, 0f, false) == -1, "pick.none");
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 0f, -1f, 0f }, 3, 0f, 0f, false) == -1, "pick.allInvalid");
        ok(FaceTrackLogic.pickIndex(null, y, new float[] { 1f }, 1, 0f, 0f, false) == -1, "pick.nullArray");
        // 冇 prev：最高分。
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 10f, 14f, 6f }, 3, 0f, 0f, false) == 1, "pick.highestNoPrev");
        // 有 prev：兩張分數相若 → 揀近上一幀嗰張（唔逐幀跳）。
        float[] sc = { 12f, 13f, 1f };
        ok(FaceTrackLogic.pickIndex(x, y, sc, 3, 0.2f, 0.5f, true) == 0, "pick.continuityLeft");
        ok(FaceTrackLogic.pickIndex(x, y, sc, 3, 0.8f, 0.5f, true) == 1, "pick.continuityRight");
        // 分數低過最高分一半嘅唔搶（就算最近 prev）。
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 5f, 14f, 1f }, 3, 0.2f, 0.5f, true) == 1, "pick.lowScoreDoesNotSteal");
        // 邊界：剛好一半（scores*2 == best）照算候選。
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 7f, 14f, 1f }, 3, 0.2f, 0.5f, true) == 0, "pick.exactlyHalfIsCandidate");
        // n 大過陣列長度：唔炸，只睇有效範圍。
        ok(FaceTrackLogic.pickIndex(new float[] { 0.5f }, new float[] { 0.5f }, new float[] { 3f }, 9, 0f, 0f, true) == 0, "pick.nBeyondLength");
        // 無效分數（<=0）嘅臉就算最近 prev 都唔揀。
        ok(FaceTrackLogic.pickIndex(x, y, new float[] { 0f, 10f, 0f }, 3, 0.2f, 0.5f, true) == 1, "pick.invalidNeverChosen");
        // 單張臉：永遠揀佢。
        ok(FaceTrackLogic.pickIndex(new float[] { 0.1f }, new float[] { 0.9f }, new float[] { 2f }, 1, 0.9f, 0.1f, true) == 0, "pick.single");

        // ── faceCoverage ──
        ok(near(FaceTrackLogic.faceCoverage(0.2f, 0.4f), 0.08f), "cov.product");
        ok(near(FaceTrackLogic.faceCoverage(-1f, 0.4f), 0f), "cov.negativeWidth");
        ok(near(FaceTrackLogic.faceCoverage(2f, 2f), 1f), "cov.clampedTo1");
        // 框未封頂時面積 ∝ 眼距²（距離代理：越近越大，約同距離平方成反比）。
        float bw = 320f;
        float bh = 180f;
        float e1 = 20f;
        float e2 = 40f;
        float fw1 = e1 * 2.2f / bw;
        float fh1 = fw1 * bw / bh * 1.25f;
        float fw2 = e2 * 2.2f / bw;
        float fh2 = fw2 * bw / bh * 1.25f;
        ok(near(FaceTrackLogic.faceCoverage(fw2, fh2) / FaceTrackLogic.faceCoverage(fw1, fh1), 4f), "cov.scalesWithEyesSquared");
        // 預設 closeCoverage 0.09 對應 16:9（320x180）眼距約 29 px（推算：約 0.6 m；未實測）。
        float e = 29f;
        float fw = e * 2.2f / bw;
        float fh = fw * bw / bh * 1.25f;
        float cov = FaceTrackLogic.faceCoverage(fw, fh);
        ok(cov > 0.085f && cov < 0.095f, "cov.defaultCloseMatchesAbout29pxEyes(" + cov + ")");

        // ROI 邊長量化：向上對齊 32 倍數、夾 cap；只升唔降；相近嘅邊長落同一級（令 FaceDetector 快取命中）。
        ok(near(FaceTrackLogic.quantUp(96f, 32, 320), 96f), "quant.exactMultipleUnchanged");
        ok(near(FaceTrackLogic.quantUp(97f, 32, 320), 128f), "quant.roundsUp");
        ok(near(FaceTrackLogic.quantUp(300f, 32, 180), 180f), "quant.capped");
        ok(near(FaceTrackLogic.quantUp(100f, 32, 320), FaceTrackLogic.quantUp(127f, 32, 320)), "quant.nearbyShareLevel");
        ok(FaceTrackLogic.quantUp(150.3f, 32, 320) >= 150.3f, "quant.neverShrinks");
        ok(near(FaceTrackLogic.quantUp(150f, 1, 320), 150f), "quant.q1Noop");

        System.out.println("FaceTrackLogicTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
