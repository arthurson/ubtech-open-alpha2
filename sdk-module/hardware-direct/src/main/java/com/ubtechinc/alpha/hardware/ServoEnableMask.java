package com.ubtechinc.alpha.hardware;

/**
 * 舵機啟用遮罩（全局單例狀態）：每顆 id 1-22 一個 bit（bit0 = id1）。
 *
 * <p>關咗嘅舵機：App 唔會再向佢發任何 TX（cmd05/cmd03/cmd52 嘅位置、trim、改 ID、
 * 升級、各種讀取），亦唔會發 RX 查詢（cmd13/6/0x17/14/33），所以 API／UI／動作播放／
 * 步態／policy 一律繞唔過——閘喺 {@link DirectChestController} 同 App 側
 * ChestQuery 嘅 raw 讀取入口。</p>
 *
 * <p>預設：1-20 開、21-22 關（21/22 係擴展位，通常冇裝）。持久化由 App 層
 * （UbxApi，SharedPreferences）負責，啟動時 {@link #setMask(int)} 灌入。</p>
 *
 * <p>注意：呢個類只負責「唔發」；未有已驗證嘅單舵機卸力（掉扭力）指令，
 * 所以運行中關掉一粒本來有力嘅舵機，佢會保持最後扭力直到斷電。</p>
 */
public final class ServoEnableMask {
    private ServoEnableMask() {
    }

    public static final int COUNT = 22;
    private static final int ALL = (1 << COUNT) - 1;
    /** 預設：1-20 開，21-22 關。 */
    public static final int DEFAULT_MASK = (1 << 20) - 1;

    private static volatile int mask = DEFAULT_MASK;

    public static int getMask() {
        return mask;
    }

    public static void setMask(int m) {
        mask = m & ALL;
    }

    public static boolean isEnabled(int id) {
        if (id < 1 || id > COUNT) return false;
        return (mask & (1 << (id - 1))) != 0;
    }

    /** 1-20 全部啟用？（true 時 cmd03 全幀同以前一模一樣。） */
    public static boolean allCoreEnabled() {
        return (mask & DEFAULT_MASK) == DEFAULT_MASK;
    }

    /** "1,2,3" 之類 CSV → mask；空字串＝全關。非法 id 拋 IllegalArgumentException。 */
    public static int parseIds(String csv) {
        int m = 0;
        if (csv == null) return 0;
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int id;
            try {
                id = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid servo id: " + s);
            }
            if (id < 1 || id > COUNT) throw new IllegalArgumentException("servo id out of range 1-" + COUNT + ": " + id);
            m |= 1 << (id - 1);
        }
        return m;
    }

    public static String toCsv(int m) {
        StringBuilder sb = new StringBuilder();
        for (int id = 1; id <= COUNT; id++) {
            if ((m & (1 << (id - 1))) != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.toString();
    }
}
