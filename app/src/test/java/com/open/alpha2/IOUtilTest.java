package com.open.alpha2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * IOUtil.readExactly 單元測試 (零依賴，不用 JUnit)。
 * 重點：len 來自客戶端 Content-Length，唔可以因為 len 大就預先配置大 buffer。
 */
public final class IOUtilTest {
    private static int passed = 0;
    private static int failed = 0;

    private IOUtilTest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    /** 每次 read 最多回 chunk bytes（模擬慢速／分段到達嘅 socket）。 */
    private static final class Trickle extends InputStream {
        private final byte[] data; private final int chunk; private int pos = 0;
        Trickle(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
        @Override public int read() { return pos < data.length ? (data[pos++] & 0xff) : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(len, chunk), data.length - pos);
            System.arraycopy(data, pos, b, off, n); pos += n; return n;
        }
    }

    private static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    public static void main(String[] args) throws Exception {
        // 各種長度（跨 64KiB 起始 buffer 同多次倍增邊界），一次過到達
        int[] sizes = {0, 1, 100, 65535, 65536, 65537, 131072, 131073, 200000, 1000000};
        for (int n : sizes) {
            byte[] d = rnd(n, n);
            check(Arrays.equals(d, IOUtil.readExactly(new ByteArrayInputStream(d), n)), "exact len=" + n);
        }
        // 分段慢速到達（1 byte／7 bytes／4097 bytes 一次）
        for (int chunk : new int[] {1, 7, 4097}) {
            byte[] d = rnd(300000, chunk);
            check(Arrays.equals(d, IOUtil.readExactly(new Trickle(d, chunk), d.length)), "trickle chunk=" + chunk);
        }
        // 資料比 len 短（對方提早斷線）：回實際讀到嘅，唔拋
        byte[] shortData = rnd(1234, 7);
        check(Arrays.equals(shortData, IOUtil.readExactly(new ByteArrayInputStream(shortData), 500000)),
                "short stream returns what arrived");
        // len 比資料短：只讀 len
        byte[] longData = rnd(5000, 8);
        check(Arrays.equals(Arrays.copyOf(longData, 3000),
                IOUtil.readExactly(new ByteArrayInputStream(longData), 3000)), "reads only len bytes");
        // 關鍵：len 極大但幾乎冇資料——唔可以預先配置（舊實作會 OutOfMemoryError）
        byte[] tiny = rnd(10, 9);
        byte[] got = null; Throwable err = null;
        try { got = IOUtil.readExactly(new ByteArrayInputStream(tiny), Integer.MAX_VALUE - 16); }
        catch (Throwable t) { err = t; }
        check(err == null && Arrays.equals(tiny, got), "huge declared len with tiny body does not pre-allocate (err=" + err + ")");

        System.out.println("IOUtilTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
