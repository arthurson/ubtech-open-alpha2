package com.open.alpha2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stream 讀取共用實現。
 *
 * 之前 {@link MainActivity#readFully}、{@code XiaozhiOtaClient.readFully}、
 * {@link HttpServer} 的靜態檔案讀取 loop 是同一個 4k/8k chunk 迴圈各自複製。
 * 收斂到這裡，行為不變。
 */
public final class IOUtil {
    private IOUtil() {}

    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    public static String readFully(InputStream in) throws IOException {
        return new String(readAllBytes(in), StandardCharsets.UTF_8);
    }

    /** 帶上限版：超限即掟 IOException，免客戶端 Content-Length 亂報 OOM。 */
    public static byte[] readAllBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) throw new IOException("body too large (limit " + maxBytes + ")");
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** 讀足 len bytes（短了就回實際讀到的，不拋）。 */
    public static byte[] readExactly(InputStream in, int len) throws IOException {
        // 唔預先 new byte[len]：HttpServer 傳入嘅 len 來自客戶端 Content-Length，攻擊者淨係送
        // header 就可以令我哋每條連線配置高達 32MB（幾條就 OOM，而 OutOfMemoryError 又唔係
        // Exception，handleClient 接唔到）。由細 buffer 開始，隨資料實際到達先倍增（上限 len）。
        byte[] out = new byte[Math.min(len, 64 * 1024)];
        int readTotal = 0;
        while (readTotal < len) {
            if (readTotal == out.length) {
                out = java.util.Arrays.copyOf(out, (int) Math.min((long) out.length * 2, (long) len));
            }
            int n = in.read(out, readTotal, out.length - readTotal);
            if (n < 0) break;
            readTotal += n;
        }
        if (readTotal < len) return java.util.Arrays.copyOf(out, readTotal);
        return out;
    }
}

