package com.open.alpha2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * DownloadVerify 單元測試 (零依賴，不用 JUnit)。
 *
 * 同 MusicEqTest 一樣經 `scripts/test-apivalidator.py` 純 javac/java 跑，
 * 唔掂 android.jar。
 */
public final class DownloadVerifyTest {
    private static int passed = 0;
    private static int failed = 0;

    private DownloadVerifyTest() {}

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(String expected, String actual, String name) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static File write(byte[] data) throws IOException {
        File f = File.createTempFile("dlverify", ".bin");
        f.deleteOnExit();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        return f;
    }

    private static boolean rejects(File f, String expected) {
        try {
            DownloadVerify.requireSha256(f, expected);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        // NIST 標準向量
        File empty = write(new byte[0]);
        eq("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DownloadVerify.sha256Hex(empty), "sha256 empty");
        File abc = write("abc".getBytes("US-ASCII"));
        String abcHex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        eq(abcHex, DownloadVerify.sha256Hex(abc), "sha256 abc");
        // 跨多個 64 KiB buffer（1,000,000 個 'a'）
        byte[] big = new byte[1000000];
        java.util.Arrays.fill(big, (byte) 'a');
        File bigF = write(big);
        eq("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
                DownloadVerify.sha256Hex(bigF), "sha256 1M x 'a' (multi-buffer)");

        // requireSha256：符合（大小寫不拘）放行；其餘一律拒絕（fail-closed）
        check(!rejects(abc, abcHex), "matching hash accepted");
        check(!rejects(abc, abcHex.toUpperCase()), "matching hash accepted (uppercase)");
        check(rejects(abc, "0" + abcHex.substring(1)), "one-nibble difference rejected");
        check(rejects(abc, null), "null expected rejected");
        check(rejects(abc, ""), "empty expected rejected");
        check(rejects(abc, abcHex.substring(0, 63)), "truncated expected rejected");
        check(rejects(new File("/nonexistent/dlverify.bin"), abcHex), "missing file rejected");

        System.out.println("DownloadVerifyTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
