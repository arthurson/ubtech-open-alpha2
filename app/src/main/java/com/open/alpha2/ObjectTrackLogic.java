package com.open.alpha2;

/**
 * 物件追蹤純算法（零依賴：只用 java.*，唔掂 android.*／項目類）。
 *
 * 做法：用戶喺預覽畫面框一個目標 → 抽成固定格點嘅灰階模板（去平均值，抗整體明暗變化）→
 * 每幀喺上一幀位置附近搜索（3 個尺度：×0.9／×1.0／×1.11，跟物件遠近）用
 * ZSAD（zero-mean sum of absolute differences）搵最似嗰格；窗口平均值用 integral image
 * O(1) 取。搵唔到（分數高過門檻）就當跟丟，之後改全圖搵（門檻收緊，避免亂鎖）。
 * 跟到之後模板按細學習率慢慢更新（物件轉面、光影變都撐到；分數好先更新，防漂移）。
 *
 * 點解獨立一檔：同 {@link ColorTrackLogic} 一樣，{@link ObjectTrackCenter} 拖住
 * CameraController／UbxApi，javac 純 runner 編唔到咁重嘅 closure；呢檔只用 java.*，
 * ObjectTrackTest 直接測。
 */
public final class ObjectTrackLogic {
    private ObjectTrackLogic() {}

    /** 模板格點上限（每邊）；格點愈多愈準但愈慢，16x16=256 點每位置。 */
    public static final int GRID_MAX = 16;
    public static final int GRID_MIN = 6;
    /** 窗口最細邊（偵測圖像素）：細過呢個冇紋理可言，拒絕。 */
    public static final int MIN_WINDOW_PX = 8;
    /** 尺度候選（相對現尺寸）。 */
    private static final float[] SCALES = {0.9f, 1.0f, 1.11f};
    /** 累積尺度相對初始框嘅上下限。 */
    private static final float SCALE_MIN = 0.35f;
    private static final float SCALE_MAX = 3.0f;
    /** 模板學習率（分數夠好先學）。 */
    private static final float ADAPT_RATE = 0.08f;

    /** 單次 update 結果（歸一化座標，原點左上；同 face／color 同一套）。 */
    public static final class Result {
        public boolean found;
        /** 框中心與大小（0-1）。 */
        public float cx, cy, w, h;
        /** ZSAD 平均絕對差／255（0 完全一樣 … 越大越唔似）。 */
        public float score;
        /** 今幀係咪全圖重新搵返（跟丟後第一次搵到）。 */
        public boolean reacquired;
    }

    /** 追蹤器狀態；單線程使用（偵測線程獨佔）。 */
    public static final class Tracker {
        private float[] tpl = new float[0];   // 去平均值嘅模板格點（gw*gh）
        private int gw, gh;
        private float tplMad;                 // 模板平均絕對偏差（0-255，紋理量）
        private float cx, cy, bw, bh;         // 現位置／大小（偵測圖像素）
        private float baseW, baseH;           // 初始框大小
        private boolean has;
        private boolean lost;
        /** 位置／大小所用嘅偵測圖尺寸（預覽比例變咗，例如 4:3↔16:9 換解像度，要換算）。 */
        private int frameW, frameH;
        private long[] ii = new long[0];
        private float[] samp = new float[GRID_MAX * GRID_MAX];

        public boolean hasTarget() { return has; }
        public boolean isLost() { return lost; }
        /** 模板紋理量（0-255 平均絕對偏差）；太低＝白牆／純色，跟唔穩。 */
        public float texture() { return tplMad; }

        public void clear() {
            has = false;
            lost = false;
        }

        /**
         * 以歸一化框 (nx,ny,nw,nh)（左上＋大小，0-1）設定目標。
         * 回 false＝框太細／出界太多，冇設成。
         */
        public boolean init(int[] gray, int w, int h, float nx, float ny, float nw, float nh) {
            if (gray == null || w < 8 || h < 8) return false;
            float x0 = clampF(nx, 0f, 1f) * w;
            float y0 = clampF(ny, 0f, 1f) * h;
            float x1 = clampF(nx + nw, 0f, 1f) * w;
            float y1 = clampF(ny + nh, 0f, 1f) * h;
            float fw = x1 - x0;
            float fh = y1 - y0;
            if (fw < MIN_WINDOW_PX || fh < MIN_WINDOW_PX) return false;
            gw = clampI(Math.round(fw / 2f), GRID_MIN, GRID_MAX);
            gh = clampI(Math.round(fh / 2f), GRID_MIN, GRID_MAX);
            if (tpl.length < gw * gh) tpl = new float[gw * gh];
            ensureIntegral(w, h);
            buildIntegral(gray, w, h, ii);
            sampleWindow(gray, w, h, x0, y0, fw, fh, samp);
            storeTemplate(samp);
            cx = (x0 + x1) * 0.5f;
            cy = (y0 + y1) * 0.5f;
            bw = fw;
            bh = fh;
            baseW = fw;
            baseH = fh;
            frameW = w;
            frameH = h;
            has = true;
            lost = false;
            return true;
        }

        /**
         * 處理一幀灰階圖。maxScore＝接受門檻（ZSAD 平均絕對差／255，預設 0.14）。
         * adapt＝跟到嗰陣係咪慢慢更新模板。
         */
        public Result update(int[] gray, int w, int h, float maxScore, boolean adapt) {
            Result r = new Result();
            if (!has || gray == null) return r;
            if (w != frameW || h != frameH) {
                // 偵測圖尺寸變咗：位置／大小按比例換算（模板係歸一化格點，唔受影響）。
                float sx = (float) w / frameW;
                float sy = (float) h / frameH;
                cx *= sx;
                cy *= sy;
                bw *= sx;
                bh *= sy;
                baseW *= sx;
                baseH *= sy;
                frameW = w;
                frameH = h;
            }
            ensureIntegral(w, h);
            buildIntegral(gray, w, h, ii);

            Cand best;
            boolean reacq = false;
            if (!lost) {
                float rx = Math.max(16f, bw * 1.0f);
                float ry = Math.max(12f, bh * 1.0f);
                best = search(gray, w, h, cx - rx, cx + rx, cy - ry, cy + ry, 1, true);
                if (best == null || best.score > maxScore) {
                    // 局部搵唔到：即刻試一次全圖（物件移動快過搜索半徑），門檻收緊。
                    Cand g = globalSearch(gray, w, h);
                    if (g != null && g.score <= maxScore * 0.7f) {
                        best = g;
                        reacq = true;
                    } else {
                        best = null;
                    }
                }
            } else {
                Cand g = globalSearch(gray, w, h);
                best = (g != null && g.score <= maxScore * 0.7f) ? g : null;
                reacq = best != null;
            }

            if (best == null) {
                lost = true;
                return r; // found=false，位置保留最後已知（Center 決定點用）
            }
            lost = false;
            cx = best.cx;
            cy = best.cy;
            bw = best.w;
            bh = best.h;
            if (adapt && best.score <= maxScore * 0.5f) {
                sampleWindow(gray, w, h, cx - bw * 0.5f, cy - bh * 0.5f, bw, bh, samp);
                float mean = 0f;
                int n = gw * gh;
                for (int i = 0; i < n; i++) mean += samp[i];
                mean /= n;
                for (int i = 0; i < n; i++) {
                    tpl[i] = (1f - ADAPT_RATE) * tpl[i] + ADAPT_RATE * (samp[i] - mean);
                }
            }
            r.found = true;
            r.cx = cx / w;
            r.cy = cy / h;
            r.w = Math.min(1f, bw / w);
            r.h = Math.min(1f, bh / h);
            r.score = best.score;
            r.reacquired = reacq;
            return r;
        }

        // ── 內部 ──
        private static final class Cand {
            float cx, cy, w, h, score;
        }

        /**
         * 喺中心範圍 [cxMin,cxMax]x[cyMin,cyMax] 內以 step 為步長，對每個尺度做 ZSAD 搜索。
         * local＝true 用現尺寸為基準（尺度連續），否則亦以現尺寸為基準（全圖都唔跳尺度）。
         */
        private Cand search(int[] gray, int w, int h,
                            float cxMin, float cxMax, float cyMin, float cyMax,
                            int step, boolean local) {
            Cand best = null;
            float bestSum = Float.MAX_VALUE;
            int n = gw * gh;
            for (int si = 0; si < SCALES.length; si++) {
                float nw = bw * SCALES[si];
                float nh = bh * SCALES[si];
                float rel = nw / baseW;
                if (rel < SCALE_MIN || rel > SCALE_MAX) continue;
                if (nw < MIN_WINDOW_PX || nh < MIN_WINDOW_PX || nw > w || nh > h) continue;
                int xa = (int) Math.floor(cxMin - nw * 0.5f);
                int xb = (int) Math.ceil(cxMax - nw * 0.5f);
                int ya = (int) Math.floor(cyMin - nh * 0.5f);
                int yb = (int) Math.ceil(cyMax - nh * 0.5f);
                if (xa < 0) xa = 0;
                if (ya < 0) ya = 0;
                int maxX = (int) Math.floor(w - nw);
                int maxY = (int) Math.floor(h - nh);
                if (xb > maxX) xb = maxX;
                if (yb > maxY) yb = maxY;
                float stepX = nw / gw;
                float stepY = nh / gh;
                int wi = Math.max(1, Math.round(nw));
                int hi = Math.max(1, Math.round(nh));
                float area = (float) wi * hi;
                for (int y = ya; y <= yb; y += step) {
                    for (int x = xa; x <= xb; x += step) {
                        float mean = windowMean(x, y, wi, hi, w, h, area);
                        float sum = 0f;
                        int k = 0;
                        boolean cut = false;
                        for (int j = 0; j < gh && !cut; j++) {
                            int py = y + (int) ((j + 0.5f) * stepY);
                            int row = py * w;
                            for (int i = 0; i < gw; i++) {
                                int px = x + (int) ((i + 0.5f) * stepX);
                                float d = tpl[k] - (gray[row + px] - mean);
                                sum += d < 0f ? -d : d;
                                k++;
                            }
                            if (sum >= bestSum) cut = true; // 早退：已經輸咗
                        }
                        if (cut) continue;
                        if (sum < bestSum) {
                            bestSum = sum;
                            if (best == null) best = new Cand();
                            best.cx = x + nw * 0.5f;
                            best.cy = y + nh * 0.5f;
                            best.w = nw;
                            best.h = nh;
                            best.score = sum / n / 255f;
                        }
                    }
                }
            }
            return best;
        }

        /** 全圖粗搜（step 2）＋喺最佳點附近 step 1 精修（粗搜會錯位 1px，唔精修分數會偏高）。 */
        private Cand globalSearch(int[] gray, int w, int h) {
            Cand g = search(gray, w, h, 0f, w, 0f, h, 2, false);
            if (g == null) return null;
            float sw = bw;
            float sh = bh;
            bw = g.w;
            bh = g.h;
            Cand f = search(gray, w, h, g.cx - 2f, g.cx + 2f, g.cy - 2f, g.cy + 2f, 1, true);
            bw = sw;
            bh = sh;
            return (f != null && f.score <= g.score) ? f : g;
        }

        private float windowMean(int x, int y, int wi, int hi, int w, int h, float area) {
            int x2 = Math.min(w, x + wi);
            int y2 = Math.min(h, y + hi);
            int W = w + 1;
            long s = ii[y2 * W + x2] - ii[y * W + x2] - ii[y2 * W + x] + ii[y * W + x];
            int aw = x2 - x;
            int ah = y2 - y;
            float a = (aw == wi && ah == hi) ? area : (float) Math.max(1, aw * ah);
            return s / a;
        }

        private void ensureIntegral(int w, int h) {
            int need = (w + 1) * (h + 1);
            if (ii.length < need) ii = new long[need];
        }

        private void storeTemplate(float[] s) {
            int n = gw * gh;
            float mean = 0f;
            for (int i = 0; i < n; i++) mean += s[i];
            mean /= n;
            float mad = 0f;
            for (int i = 0; i < n; i++) {
                float v = s[i] - mean;
                tpl[i] = v;
                mad += v < 0f ? -v : v;
            }
            tplMad = mad / n;
        }

        /** 將窗口（左上 x0,y0，大小 fw×fh，偵測圖像素）取 gw×gh 格點樣本。 */
        private void sampleWindow(int[] gray, int w, int h, float x0, float y0,
                                  float fw, float fh, float[] out) {
            float stepX = fw / gw;
            float stepY = fh / gh;
            int k = 0;
            for (int j = 0; j < gh; j++) {
                int py = clampI((int) (y0 + (j + 0.5f) * stepY), 0, h - 1);
                for (int i = 0; i < gw; i++) {
                    int px = clampI((int) (x0 + (i + 0.5f) * stepX), 0, w - 1);
                    out[k++] = gray[py * w + px];
                }
            }
        }
    }

    /** ARGB → 灰階（整數，0-255）：(77R+150G+29B)>>8。 */
    public static void toGray(int[] argb, int n, int[] gray) {
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (77 * r + 150 * g + 29 * b) >> 8;
        }
    }

    /** Summed-area table，大小 (w+1)*(h+1)。 */
    static void buildIntegral(int[] gray, int w, int h, long[] ii) {
        int W = w + 1;
        for (int x = 0; x <= w; x++) ii[x] = 0;
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            ii[(y + 1) * W] = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                ii[(y + 1) * W + x + 1] = ii[y * W + x + 1] + rowSum;
            }
        }
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
