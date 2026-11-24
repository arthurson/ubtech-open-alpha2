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

    /** hue（0-360）；灰階（delta=0）報 0。rgbToHsv 同 matchMask 共用，確保兩條路逐像素一致。 */
    private static float hueOf(float rf, float gf, float bf, float max, float delta) {
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
        return h;
    }

    /** RGB(0-255) → HSV：｛h 0-360，s 0-1，v 0-1｝。灰階（delta=0）h 照報 0。 */
    public static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h = hueOf(rf, gf, bf, max, delta);
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

    /**
     * 逐像素 HSV 閾值（零分配版）：px 係 ARGB int[]（只用 RGB），結果寫入 mask[0..n)，回命中數。
     * 同 {@link #rgbToHsv}＋{@link #matchHsv} 逐像素一致（單測以隨機像素對照）；
     * 取代 Center 之前每像素 new float[3]（160x90 即每幀 14400 次分配）。
     */
    public static int matchMask(int[] px, int n, boolean[] mask,
            float hMin, float hMax, float sMin, float sMax, float vMin, float vMax) {
        int matches = 0;
        for (int i = 0; i < n; i++) {
            int p = px[i];
            float rf = ((p >> 16) & 0xFF) / 255f;
            float gf = ((p >> 8) & 0xFF) / 255f;
            float bf = (p & 0xFF) / 255f;
            float max = Math.max(rf, Math.max(gf, bf));
            float min = Math.min(rf, Math.min(gf, bf));
            float delta = max - min;
            float h = hueOf(rf, gf, bf, max, delta);
            float s = (max <= 0f) ? 0f : delta / max;
            boolean ok = hueInRange(h, hMin, hMax) && s >= sMin && s <= sMax && max >= vMin && max <= vMax;
            mask[i] = ok;
            if (ok) matches++;
        }
        return matches;
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

    /** flood fill 暫存（visited／stack），detect 線程重用，免每幀 new 兩個 w*h 陣列。非線程安全。 */
    public static final class Scratch {
        boolean[] visited = new boolean[0];
        int[] stack = new int[0];

        void ensure(int n) {
            if (visited.length < n) {
                visited = new boolean[n];
                stack = new int[n];
            }
        }
    }

    /**
     * 回最大 blob；全部細過 minPixels 即回 null（當掃唔到）。
     * mask 唔會被改寫（visited 另開，caller 個 mask 可重用）。
     */
    public static Blob largestBlob(boolean[] mask, int w, int h, int minPixels) {
        return pickBlob(mask, w, h, minPixels, new Scratch(), 0f, 0f, false);
    }

    /**
     * 揀 blob（有連續性）：最大者 best；若 hasPrev 而場內有第二件夠大（count>=best 一半）
     * 嘅 blob 質心更近上一幀質心（prevFx／prevFy，歸一化 0-1），揀較近者——
     * 兩件同色物件大細相若時唔會逐幀左右跳。唔夠大嘅細 blob 一律唔搶（免被雜點牽走）。
     * sc 可重用（detect 線程每幀傳同一個），mask 唔會被改寫。
     */
    public static Blob pickBlob(boolean[] mask, int w, int h, int minPixels, Scratch sc,
            float prevFx, float prevFy, boolean hasPrev) {
        if (mask == null || sc == null || w <= 0 || h <= 0 || mask.length < w * h) return null;
        int total = w * h;
        sc.ensure(total);
        boolean[] visited = sc.visited;
        int[] stack = sc.stack;
        java.util.Arrays.fill(visited, 0, total, false);
        Blob best = null;
        java.util.ArrayList<Blob> kept = null;
        for (int i = 0; i < total; i++) {
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
            if (count >= minPixels) {
                Blob b = new Blob();
                b.count = count;
                b.sumX = sumX;
                b.sumY = sumY;
                b.x0 = x0;
                b.y0 = y0;
                b.x1 = x1;
                b.y1 = y1;
                if (best == null || count > best.count) best = b;
                if (hasPrev) {
                    if (kept == null) kept = new java.util.ArrayList<Blob>();
                    kept.add(b);
                }
            }
        }
        if (best == null || kept == null || kept.size() < 2) return best;
        Blob pick = best;
        float bestD = dist2(best, w, h, prevFx, prevFy);
        for (Blob b : kept) {
            if (b.count * 2 < best.count) continue; // 細過最大者一半：唔搶
            float d = dist2(b, w, h, prevFx, prevFy);
            if (d < bestD) {
                pick = b;
                bestD = d;
            }
        }
        return pick;
    }

    private static float dist2(Blob b, int w, int h, float fx, float fy) {
        float cx = ((float) b.sumX / b.count + 0.5f) / w;
        float cy = ((float) b.sumY / b.count + 0.5f) / h;
        float dx = cx - fx;
        float dy = cy - fy;
        return dx * dx + dy * dy;
    }

    /**
     * 頭 yaw 偏離 home 嘅量，折算成同 dx 同尺度（1＝半個鏡頭視角）。
     * halfFovDeg＝鏡頭水平半視角（度），並假設 1 舵機單位≈1°；pan 增＝向右＝dx 正
     *（同 Center 追頭「dx>0 → pan 增」一致）。身體方位＝dx＋headOffset：頭追色後色已被拉返
     * 畫面中間（dx≈0），淨睇 dx 永遠唔知身體要轉。
     */
    public static float headOffset(float pan, int panHome, float halfFovDeg) {
        if (halfFovDeg <= 0f) return 0f;
        return (pan - panHome) / halfFovDeg;
    }

    /**
     * 行路跟決策（純函數，可單測）：回 "left"／"right"／"forward"／"none"。
     * 偏得多先轉向（對正先好行，唔係越行越偏），置中但細（遠）就行前一步，
     * 夠大（近）就企定（淨個頭繼續瞄）。
     */
    public static String walkDecision(float dx, float coverage, float turnDb, float closeCoverage) {
        if (Math.abs(dx) > turnDb) return dx < 0 ? "left" : "right";
        if (coverage < closeCoverage) return "forward";
        return "none";
    }
}
