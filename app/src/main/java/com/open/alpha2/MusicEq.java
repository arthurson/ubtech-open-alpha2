package com.open.alpha2;

/**
 * 音樂 EQ 純算法／查表（零依賴：只用 java.*，唔掂 android.*）。
 *
 * 同 ColorTrackLogic 一樣獨立一檔：AudioCenter 拖住成個播歌 closure，
 * `scripts/test-apivalidator.py` 純 javac runner 編唔到；呢檔 MusicEqTest 直接測。
 *
 * Preset 表係 5-band 參考（常見 60/230/910/3600/14000Hz，dB 整數）：
 * 值係通用樂感經驗值（唔係量度標準），部機 band 數唔係 5 就線性 resample。
 */
public final class MusicEq {
    private MusicEq() {}

    public static final String NORMAL = "normal";
    public static final String CLASSICAL = "classical";
    public static final String DANCE = "dance";
    public static final String ROCK = "rock";
    public static final String JAZZ = "jazz";
    public static final String POP = "pop";
    public static final String BASS = "bass";
    public static final String TREBLE = "treble";
    public static final String CUSTOM = "custom";

    public static String[] presetNames() {
        return new String[] { NORMAL, CLASSICAL, DANCE, ROCK, JAZZ, POP, BASS, TREBLE, CUSTOM };
    }

    public static boolean isPreset(String name) {
        if (name == null) return false;
        for (String p : presetNames()) {
            if (p.equals(name)) return true;
        }
        return false;
    }

    /** Preset dB 表（5-band；custom 無表，回 null——用家存嘅 levels）。 */
    public static int[] presetDb(String name) {
        if (name == null) return null;
        if (name.equals(NORMAL)) return new int[] { 0, 0, 0, 0, 0 };
        if (name.equals(CLASSICAL)) return new int[] { 4, 3, -1, 3, 5 };
        if (name.equals(DANCE)) return new int[] { 5, 3, 0, 1, 4 };
        if (name.equals(ROCK)) return new int[] { 5, 3, -2, 3, 5 };
        if (name.equals(JAZZ)) return new int[] { 3, 2, 1, 3, 4 };
        if (name.equals(POP)) return new int[] { -1, 2, 4, 2, -1 };
        if (name.equals(BASS)) return new int[] { 6, 5, 2, 0, 0 };
        if (name.equals(TREBLE)) return new int[] { 0, 0, 2, 5, 6 };
        return null;
    }

    /**
     * dB 表 resample 到目標 band 數（線性插值＋四捨五入；同數即 clone）。
     * 部機唔係 5-band（如 3／6／7）照播到，唔使為咗 EQ 寫死 band 數。
     */
    public static int[] resampleDb(int[] srcDb, int nBands) {
        if (srcDb == null || srcDb.length == 0 || nBands <= 0) return null;
        if (nBands == 1) {
            int sum = 0;
            for (int v : srcDb) sum += v;
            return new int[] { Math.round((float) sum / srcDb.length) };
        }
        if (srcDb.length == nBands) return srcDb.clone();
        int[] out = new int[nBands];
        for (int i = 0; i < nBands; i++) {
            float pos = (float) i * (srcDb.length - 1) / (nBands - 1);
            int lo = (int) pos;
            int hi = Math.min(srcDb.length - 1, lo + 1);
            float frac = pos - lo;
            out[i] = Math.round(srcDb[lo] * (1f - frac) + srcDb[hi] * frac);
        }
        return out;
    }

    /**
     * 解析前端 levels 參數（逗號分隔 mB 整數，如 "0,300,-200,100,0"）。
     * 唔啱即拋 IllegalArgumentException（handleApi 照收，同 zoom 嗰啲一致）。
     */
    public static int[] parseLevelsMb(String csv, int minCount, int maxCount) {
        if (csv == null || csv.trim().isEmpty()) {
            throw new IllegalArgumentException("parameter 'levels' must be comma-separated integers");
        }
        String[] parts = csv.trim().split(",");
        if (parts.length < minCount || parts.length > maxCount) {
            throw new IllegalArgumentException("parameter 'levels' must have " + minCount
                    + "-" + maxCount + " values, got: " + parts.length);
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'levels' must be comma-separated integers, got: " + csv);
            }
        }
        return out;
    }

    public static int clampMb(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** dB 整數 ↔ mB（前端推桿行 dB，後端 effect 行 mB）。 */
    public static int dbToMb(int db) { return db * 100; }
}
