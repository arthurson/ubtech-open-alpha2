package com.open.alpha2;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 下載完整性檢查（SHA-256）。
 *
 * <p>傳輸層已經係嚴格 TLS（BundledCaSsl，內建 Mozilla CA 令舊機都驗到），呢度係第二道防線
 * （defense in depth）：固定來源嘅檔案再用「寫死喺 APK 入面嘅 hash」驗證——hash 唔經下載通道，
 * 就算來源本身被入侵或者 TLS 驗證出事都改唔到。</p>
 *
 * <p>純 java.*，唔掂 android，可直接 javac 單元測試（見 DownloadVerifyTest）。</p>
 */
final class DownloadVerify {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DownloadVerify() {
    }

    /** @return 檔案嘅 SHA-256（小寫 hex，64 字元）。 */
    static String sha256Hex(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable: " + e.getMessage());
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
            }
        }
        byte[] d = md.digest();
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            out[2 * i] = HEX[(d[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[d[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * 落完、改名前呼叫。hash 唔符（或者冇提供 expected）就拋 IOException——fail-closed，
     * 呼叫方照現有流程刪 tmp 並報 error。
     */
    static void requireSha256(File f, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.length() != 64) {
            throw new IOException("no valid pinned sha256 for " + f.getName());
        }
        String got = sha256Hex(f);
        if (!expectedHex.equalsIgnoreCase(got)) {
            throw new IOException("sha256 mismatch (expected " + expectedHex.substring(0, 12)
                    + "…, got " + got.substring(0, 12) + "…)");
        }
    }
}
