package com.open.alpha2;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * PanelToken 單元測試 (零依賴，不用 JUnit)。
 * 同 ApiValidatorTest 一樣經 scripts/test-apivalidator.py 以 android.jar 編譯執行
 * (要引用 ApiValidator.checkPanelTokenFormat 驗證產生嘅 token 符合現有格式規則)。
 */
public final class PanelTokenTest {
    private static int passed = 0;
    private static int failed = 0;

    private PanelTokenTest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    /** 固定輸出 bytes 嘅 SecureRandom（驗證字元映射／負 byte 處理）。 */
    private static final class Fixed extends SecureRandom {
        private final byte[] data;
        Fixed(byte[] data) { this.data = data; }
        @Override public void nextBytes(byte[] out) { System.arraycopy(data, 0, out, 0, out.length); }
    }

    public static void main(String[] args) {
        String a = PanelToken.ALPHABET;
        check(a.length() == 32, "alphabet has 32 chars (needed for '& 31' without modulo bias)");
        Set<Character> uniq = new HashSet<Character>();
        for (char c : a.toCharArray()) uniq.add(c);
        check(uniq.size() == 32, "alphabet chars are unique");
        check(a.indexOf('0') < 0 && a.indexOf('1') < 0 && a.indexOf('O') < 0 && a.indexOf('I') < 0,
                "no confusable 0/1/O/I");

        // 格式、符合現有 token 規則、無重複
        SecureRandom rnd = new SecureRandom();
        Set<String> seen = new HashSet<String>();
        boolean allFormat = true, allValid = true;
        int[] counts = new int[32];
        for (int i = 0; i < 4000; i++) {
            String t = PanelToken.generate(rnd);
            seen.add(t);
            if (!t.matches("[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}")) allFormat = false;
            try { ApiValidator.checkPanelTokenFormat(t); } catch (IllegalArgumentException e) { allValid = false; }
            for (char c : t.toCharArray()) { int ix = a.indexOf(c); if (ix >= 0) counts[ix]++; }
        }
        check(allFormat, "generated tokens match XXXXX-XXXXX with the safe alphabet");
        check(allValid, "generated tokens pass ApiValidator.checkPanelTokenFormat");
        check(seen.size() == 4000, "4000 generated tokens are all distinct (got " + seen.size() + ")");
        boolean allCharsUsed = true;
        for (int c : counts) if (c == 0) allCharsUsed = false;
        check(allCharsUsed, "every alphabet char is used (sanity on distribution)");

        // 映射：byte & 31，負 byte 亦要有效（0xFF -> 31 -> 'Z'；0x20 -> 0 -> '2'）
        byte[] fixed = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        check(("" + a.charAt(0) + a.charAt(1) + a.charAt(2) + a.charAt(3) + a.charAt(4) + "-"
                + a.charAt(5) + a.charAt(6) + a.charAt(7) + a.charAt(8) + a.charAt(9))
                .equals(PanelToken.generate(new Fixed(fixed))), "byte -> alphabet mapping, hyphen after 5");
        byte[] hi = new byte[10]; java.util.Arrays.fill(hi, (byte) 0xFF);
        check("ZZZZZ-ZZZZZ".equals(PanelToken.generate(new Fixed(hi))), "negative bytes map via & 31");
        byte[] mid = new byte[10]; java.util.Arrays.fill(mid, (byte) 0x20);
        check("22222-22222".equals(PanelToken.generate(new Fixed(mid))), "high bits are ignored (0x20 -> index 0)");

        // 預設啟用規則
        check(PanelToken.needsAutoEnable(false, null), "fresh + null token -> auto enable");
        check(PanelToken.needsAutoEnable(false, ""), "fresh + empty token -> auto enable");
        check(PanelToken.needsAutoEnable(false, "   "), "fresh + blank token -> auto enable");
        check(!PanelToken.needsAutoEnable(false, "MYTOKEN-1234"), "existing user token is kept");
        check(!PanelToken.needsAutoEnable(true, null), "initialized + no token (user disabled) -> stays disabled");
        check(!PanelToken.needsAutoEnable(true, ""), "initialized + empty token -> stays disabled");
        check(!PanelToken.needsAutoEnable(true, "x"), "initialized + token -> nothing to do");

        System.out.println("PanelTokenTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
