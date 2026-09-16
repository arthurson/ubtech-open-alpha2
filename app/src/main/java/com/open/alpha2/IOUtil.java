package com.open.alpha2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stream 讀取共用實現。
 *
 * 之前 {@link MainActivity#readFully}、{@code XiaozhiOtaClient.readFully}、
 * {@link HttpServer} 嘅靜態檔案讀取 loop 係同一個 4k/8k chunk 迴圈各自複製。
 * 收斂到呢度，行為不變。
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

    /** 讀足 len bytes（短咗就回實際讀到嘅，唔拋）。 */
    public static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int readTotal = 0;
        while (readTotal < len) {
            int n = in.read(out, readTotal, len - readTotal);
            if (n < 0) break;
            readTotal += n;
        }
        if (readTotal < len) return java.util.Arrays.copyOf(out, readTotal);
        return out;
    }
}
