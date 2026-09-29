package com.open.alpha2;

/**
 * 顏色追蹤純算法（零依賴：只用 java.*，唔掂 android.*／項目類）。
 *
 * 點解獨立一檔：{@link ColorTrackCenter} 拖住 CameraController／UbxApi
 *（後者再拖 com.ubtechinc 外部 AAR），`scripts/test-apivalidator.py`
 * 純 javac runner 編唔到咁重嘅 closure；呢檔只用 java.*，ColorTrackTest
 * 直接測，中間唔經任何 stub。Center 側直接調用，唔包多層 wrapper。
 */
public final class ColorTrackLogic {
    private ColorTrackLogic() {}

    /** RGB(0-255) → HSV：｛h 0-360，s 0-1，v 0-1｝。灰階（delta=0）h 照報 0。 */
    public static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h = 0f;
        if (delta > 0f) {
            if (max == rf) {
                h = 60f * (((gf - bf) / delta) % 6f);
            } else if (max == gf) {
                h = 60f * (((bf - rf) / delta) + 2f);
            } else {
                h = 60f * (((rf - gf) / delta) + 4f);
            }
            if (h < 0f) h += 360f;
        }
        float s = (max <= 0f) ? 0f : delta / max;
        return new float[] { h, s, max };
    }

    /** Hue 區間判斷（wrap-aware：hMin>hMax 即橫跨 0°，如紅色 335-25）。端點 inclusive。 */
    public static boolean hueInRange(float h, float hMin, float hMax) {
        if (hMin <= hMax) return h >= hMin && h <= hMax;
        return h >= hMin || h <= hMax;
    }

    public static boolean matchHsv(float h, float s, float v,
            float hMin, float hMax, float sMin, float sMax, float vMin, float vMax) {
        return hueInRange(h, hMin, hMax) && s >= sMin && s <= sMax && v >= vMin && v <= vMax;
    }

    /** 最大連通 blob（4-鄰接 flood fill，iterative，免 recursion 爆 stack）。 */
    public static final class Blob {
        public int count;
        public long sumX;
        public long sumY;
        /** inclusive bbox。 */
        public int x0;
        public int y0;
        public int x1;
        public int y1;
    }

    /**
     * 回最大 blob；全部細過 minPixels 即回 null（當掃唔到）。
     * mask 唔會被改寫（visited 另開，caller 個 mask 可重用）。
     */
    public static Blob largestBlob(boolean[] mask, int w, int h, int minPixels) {
        if (mask == null || mask.length < w * h || w <= 0 || h <= 0) return null;
        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];
        Blob best = null;
        for (int i = 0; i < w * h; i++) {
            if (!mask[i] || visited[i]) continue;
            // 新連通域：flood fill 量 count＋質心＋bbox。
            int count = 0;
            long sumX = 0;
            long sumY = 0;
            int x0 = w;
            int y0 = h;
            int x1 = -1;
            int y1 = -1;
            int sp = 0;
            stack[sp++] = i;
            visited[i] = true;
            while (sp > 0) {
                int cur = stack[--sp];
                int cx = cur % w;
                int cy = cur / w;
                count++;
                sumX += cx;
                sumY += cy;
                if (cx < x0) x0 = cx;
                if (cx > x1) x1 = cx;
                if (cy < y0) y0 = cy;
                if (cy > y1) y1 = cy;
                // 4-鄰接（對角唔連：斜掂一點嘅兩舊色唔當同一舊，少啲誤合併）。
                if (cx > 0) {
                    int n = cur - 1;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cx + 1 < w) {
                    int n = cur + 1;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cy > 0) {
                    int n = cur - w;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
                if (cy + 1 < h) {
                    int n = cur + w;
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n; }
                }
            }
            if (count >= minPixels && (best == null || count > best.count)) {
                Blob b = new Blob();
                b.count = count;
                b.sumX = sumX;
                b.sumY = sumY;
                b.x0 = x0;
                b.y0 = y0;
                b.x1 = x1;
                b.y1 = y1;
                best = b;
            }
        }
        return best;
    }
}
