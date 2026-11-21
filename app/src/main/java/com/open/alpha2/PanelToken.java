package com.open.alpha2;

import java.security.SecureRandom;

/**
 * 面板 token 嘅產生／啟用規則（純 java.*，可單元測試；儲存由 PanelAuth 負責）。
 *
 * <p>預設啟用：機械人冇螢幕輸入，所以 token 由機身畫面顯示，瀏覽器鎖屏浮層輸入一次即記住。
 * 格式 {@code XXXXX-XXXXX}：10 個字元 × 5 bit ＝ 50 bit，字元集剔走易混淆嘅 0/1/O/I，
 * 符合 ApiValidator.checkPanelTokenFormat（8-64，A-Za-z0-9 - _）。大小寫敏感（全大寫）。</p>
 */
final class PanelToken {
    /** 32 個字元（2-9 ＋ A-Z 去 I、O）。長度必須係 32，index 先可以用 {@code & 31} 而無 modulo bias。 */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final int GROUP_LEN = 5;
    static final int GROUPS = 2;

    private PanelToken() {
    }

    static String generate(SecureRandom rnd) {
        byte[] b = new byte[GROUP_LEN * GROUPS];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length + GROUPS - 1);
        for (int i = 0; i < b.length; i++) {
            if (i > 0 && i % GROUP_LEN == 0) sb.append('-');
            sb.append(ALPHABET.charAt(b[i] & 31));
        }
        return sb.toString();
    }

    /**
     * 只有「從未初始化過」而且「而家冇 token」先自動啟用。
     * 已初始化＝用戶之後主動清除咗 token（停用），要尊重，唔可以下次啟動又自動鎖返。
     * 已有 token（用戶自己設過）一律保留。
     */
    static boolean needsAutoEnable(boolean initialized, String existingToken) {
        return !initialized && (existingToken == null || existingToken.trim().isEmpty());
    }
}
