package com.open.alpha2;

/**
 * 人臉追蹤純算法（只用 java.*，唔掂 android.*，可直接單測）：
 * 多張臉揀邊張（有連續性）、臉框面積作距離代理（coverage）。
 *
 * 之前每幀只揀「眼距×(0.5+confidence)」最高嗰張，兩個人企埋一齊、分數相若時會逐幀左右跳，
 * 行路跟時等於身體方位逐幀翻轉。
 */
public final class FaceTrackLogic {
    private FaceTrackLogic() {}

    /**
     * 揀臉：最高分者 best；若 hasPrev 而另有一張分數 >= best 一半、質心更近上一幀質心
     *（prevX／prevY，歸一化 0-1），揀較近者。分數低過一半嘅唔搶（免被誤檢牽走）。
     *
     * @param xs     各臉中點 x（歸一化 0-1）
     * @param ys     各臉中點 y（歸一化 0-1）
     * @param scores 各臉分數（<=0 當無效）
     * @param n      有效長度
     * @return 揀中嘅 index；冇有效臉回 -1
     */
    public static int pickIndex(float[] xs, float[] ys, float[] scores, int n,
            float prevX, float prevY, boolean hasPrev) {
        if (xs == null || ys == null || scores == null) return -1;
        int len = Math.min(n, Math.min(xs.length, Math.min(ys.length, scores.length)));
        int best = -1;
        for (int i = 0; i < len; i++) {
            if (scores[i] <= 0f) continue;
            if (best < 0 || scores[i] > scores[best]) best = i;
        }
        if (best < 0 || !hasPrev) return best;
        int pick = best;
        float bestD = dist2(xs[best], ys[best], prevX, prevY);
        for (int i = 0; i < len; i++) {
            if (i == best || scores[i] <= 0f) continue;
            if (scores[i] * 2f < scores[best]) continue; // 低過最高分一半：唔搶
            float d = dist2(xs[i], ys[i], prevX, prevY);
            if (d < bestD) {
                pick = i;
                bestD = d;
            }
        }
        return pick;
    }

    private static float dist2(float x, float y, float px, float py) {
        float dx = x - px;
        float dy = y - py;
        return dx * dx + dy * dy;
    }

    /**
     * 臉框面積（歸一化 0-1）＝估算臉框寬×高（FaceTrackCenter 嘅 fw、fh）。
     * 當作行路跟嘅「覆蓋率」：越近越大、框未封頂前約同距離平方成反比（寬∝眼距，高等比）。
     * 近到 fh 封頂 1.0 之後唔再線性（16:9 約眼距 >= 0.2×圖寬、4:3 約 0.27×）——呢個範圍已經遠過夠近門檻。
     */
    public static float faceCoverage(float fw, float fh) {
        if (fw < 0f) fw = 0f;
        if (fh < 0f) fh = 0f;
        if (fw > 1f) fw = 1f;
        if (fh > 1f) fh = 1f;
        return fw * fh;
    }
}
