package com.open.alpha2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SafeUnzip 單元測試 (零依賴，不用 JUnit)：zip-slip、entry 數量／單檔／總量上限、
 * 真正嘅壓縮炸彈、被跳過 entry 都計大小、可用空間保護、取消、半截檔清理。
 */
public final class SafeUnzipTest {
    private static int passed = 0;
    private static int failed = 0;
    private static final long MB = 1024L * 1024L;

    private SafeUnzipTest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    /** 一個 zip entry：data＝null 代表目錄；zeros>0 代表 zeros 個 0 byte（壓縮率極高）。 */
    private static final class E {
        final String name; final byte[] data; final long zeros;
        E(String name, byte[] data) { this.name = name; this.data = data; this.zeros = 0; }
        E(String name, long zeros) { this.name = name; this.data = null; this.zeros = zeros; }
    }

    private static final java.util.List<File> TMP = new java.util.ArrayList<File>();

    private static File tmpDir() throws IOException {
        File d = Files.createTempDirectory("safeunzip").toFile();
        TMP.add(d);
        return d;
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    private static File makeZip(File where, E... es) throws IOException {
        File z = new File(where, "t.zip");
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(z));
        try {
            byte[] chunk = new byte[1 << 20];
            for (E e : es) {
                zo.putNextEntry(new ZipEntry(e.name));
                if (e.zeros > 0) {
                    for (long left = e.zeros; left > 0; left -= chunk.length) {
                        zo.write(chunk, 0, (int) Math.min(chunk.length, left));
                    }
                } else if (e.data != null) {
                    zo.write(e.data);
                }
                zo.closeEntry();
            }
        } finally { zo.close(); }
        return z;
    }

    private static long dirBytes(File d) {
        long t = 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        for (File f : fs) t += f.isDirectory() ? dirBytes(f) : f.length();
        return t;
    }

    private static final SafeUnzip.Space PLENTY = new SafeUnzip.Space() {
        @Override public long usableBytes(File dir) { return Long.MAX_VALUE / 2; }
    };

    /** 回傳 IOException 訊息；冇拋＝null。 */
    private static String fail(File zip, File dest, SafeUnzip.Limits l, SafeUnzip.Cancel c, SafeUnzip.Space s) {
        try { SafeUnzip.extract(zip, dest, l, c, s); return null; }
        catch (IOException e) { return String.valueOf(e.getMessage()); }
    }

    public static void main(String[] args) throws Exception {
        SafeUnzip.Limits roomy = new SafeUnzip.Limits(1000, 100 * MB, 50 * MB, 1 * MB);

        // 1. 正常解壓：巢狀目錄、空檔、目錄 entry
        File w = tmpDir(), out = new File(w, "out"); out.mkdirs();
        byte[] a = "hello".getBytes("UTF-8"), b = new byte[70000]; Arrays.fill(b, (byte) 7);
        File z = makeZip(w, new E("actions/", (byte[]) null), new E("actions/a.txt", a),
                new E("actions/sub/deep/b.bin", b), new E("actions/empty.txt", new byte[0]));
        check(fail(z, out, roomy, null, PLENTY) == null, "normal zip extracts without error");
        check(new File(out, "actions/a.txt").length() == 5 && new File(out, "actions/sub/deep/b.bin").length() == 70000
                && new File(out, "actions/empty.txt").length() == 0 && new File(out, "actions").isDirectory(),
                "normal zip: files, nested dirs and empty file present");
        check(Arrays.equals(b, Files.readAllBytes(new File(out, "actions/sub/deep/b.bin").toPath())), "normal zip: content intact");

        // 2. zip-slip：逃出目標目錄嘅 entry 被跳過，其餘照解
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("../evil1.txt", a), new E("a/../../evil2.txt", a), new E("ok.txt", a));
        check(fail(z, out, roomy, null, PLENTY) == null, "zip-slip entries do not abort extraction");
        check(!new File(w, "evil1.txt").exists() && !new File(w, "evil2.txt").exists()
                && !new File(w.getParentFile(), "evil1.txt").exists() && !new File(w.getParentFile(), "evil2.txt").exists(),
                "zip-slip: nothing written outside the target dir");
        check(new File(out, "ok.txt").exists(), "zip-slip: legit entry still extracted");

        // 3. entry 數量上限
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        E[] many = new E[11]; for (int i = 0; i < many.length; i++) many[i] = new E("f" + i + ".txt", a);
        z = makeZip(w, many);
        String m = fail(z, out, new SafeUnzip.Limits(10, 100 * MB, 50 * MB, 1 * MB), null, PLENTY);
        check(m != null && m.contains("too many entries"), "entry count limit enforced: " + m);

        // 4. 單一檔案上限：超限即停，半截檔被刪
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("big.bin", 3 * MB));
        m = fail(z, out, new SafeUnzip.Limits(100, 100 * MB, 1 * MB, 1 * MB), null, PLENTY);
        check(m != null && m.contains("entry too large"), "per-entry limit enforced: " + m);
        check(!new File(out, "big.bin").exists(), "per-entry limit: partial file deleted");

        // 5. 總量上限（多個小於單檔上限嘅檔案）
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("a.bin", 400000L), new E("b.bin", 400000L), new E("c.bin", 400000L));
        m = fail(z, out, new SafeUnzip.Limits(100, 1 * MB, 50 * MB, 1 * MB), null, PLENTY);
        check(m != null && m.contains("too large when extracted"), "total size limit enforced: " + m);
        check(dirBytes(out) <= 1 * MB, "total limit: disk usage stays within the limit (" + dirBytes(out) + ")");

        // 6. 真正嘅壓縮炸彈：100MB 零值壓到好細，總量上限 10MB 要即刻擋住
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("bomb.bin", 100 * MB));
        check(z.length() < 1 * MB, "fixture is a real bomb: 100 MB of zeros compresses to " + z.length() + " bytes");
        long t0 = System.currentTimeMillis();
        m = fail(z, out, new SafeUnzip.Limits(100, 10 * MB, 500 * MB, 1 * MB), null, PLENTY);
        check(m != null && m.contains("too large when extracted"), "bomb stopped by total limit: " + m);
        check(dirBytes(out) <= 10 * MB, "bomb: nothing near 100 MB reached the disk (" + dirBytes(out) + ")");
        check(System.currentTimeMillis() - t0 < 5000, "bomb stopped quickly");

        // 7. 被跳過（zip-slip）嘅 entry 解壓出嚟嘅 byte 都計入上限
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("../skipped.bin", 5 * MB));
        m = fail(z, out, new SafeUnzip.Limits(100, 1 * MB, 50 * MB, 1 * MB), null, PLENTY);
        check(m != null && m.contains("too large when extracted"), "skipped entries count towards the total: " + m);

        // 8. 可用空間：開始前唔夠
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("a.txt", a));
        SafeUnzip.Space tiny = new SafeUnzip.Space() { @Override public long usableBytes(File d) { return 10 * MB; } };
        m = fail(z, out, new SafeUnzip.Limits(100, 100 * MB, 50 * MB, 128 * MB), null, tiny);
        check(m != null && m.contains("not enough free space"), "refuses to start without free space: " + m);
        check(dirBytes(out) == 0, "no free space: nothing written");

        // 9. 可用空間：寫入途中跌穿保留量
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("grow.bin", 12 * MB));
        final int[] calls = {0};
        SafeUnzip.Space shrinking = new SafeUnzip.Space() {
            @Override public long usableBytes(File d) { return calls[0]++ == 0 ? 1000 * MB : 100 * MB; }
        };
        m = fail(z, out, new SafeUnzip.Limits(100, 100 * MB, 50 * MB, 128 * MB), null, shrinking);
        check(m != null && m.contains("not enough free space"), "stops when free space drops below the reserve: " + m);
        check(!new File(out, "grow.bin").exists(), "free-space stop: partial file deleted");

        // 10. 取消：訊息必須係 "cancelled"（controller 靠佢分辨取消同錯誤），半截檔被刪
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        z = makeZip(w, new E("c.bin", 6 * MB));
        final int[] polls = {0};
        SafeUnzip.Cancel cancelLater = new SafeUnzip.Cancel() { @Override public boolean isCancelled() { return ++polls[0] > 5; } };
        m = fail(z, out, roomy, cancelLater, PLENTY);
        check("cancelled".equals(m), "cancel surfaces as exactly 'cancelled': " + m);
        check(!new File(out, "c.bin").exists(), "cancel: partial file deleted");

        // 11. 空 zip／只有目錄：唔拋
        w = tmpDir(); out = new File(w, "out"); out.mkdirs();
        check(fail(makeZip(w, new E("d1/", (byte[]) null), new E("d1/d2/", (byte[]) null)), out, roomy, null, PLENTY) == null
                && new File(out, "d1/d2").isDirectory(), "directory-only zip extracts");

        // 12. 正式上限必須遠高於真實動作包（379 entries／71 MB／最大單檔 2.1 MB，2026-11 實測）
        check(SafeUnzip.ACTIONS.maxEntries >= 379 * 10 && SafeUnzip.ACTIONS.maxTotalBytes >= 71 * MB * 5
                && SafeUnzip.ACTIONS.maxEntryBytes >= 2 * MB * 20, "production ACTIONS limits leave >= 5x headroom over the real pack");
        check(SafeUnzip.VOSK.maxTotalBytes >= 300 * MB, "production VOSK limits are generous enough for real models");

        for (File d : TMP) deleteTree(d); // 含 100MB 炸彈 fixture，唔好留喺 CI 機器
        System.out.println("SafeUnzipTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
