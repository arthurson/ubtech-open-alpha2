package com.open.alpha2;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 記憶體洪水回歸測試 (零依賴)：必須以 {@code -Xmx64m} 執行（scripts/test-apivalidator.py 已經帶）。
 * 一條只送 header、聲稱巨大長度嘅連線，唔可以令伺服器預先配置該長度：
 *   A) HTTP Content-Length: 33554431 × 20 條（舊 IOUtil.readExactly 會 new byte[32MB]）；
 *   B) WebSocket frame 聲稱 1MB × 100 條（舊 WebSocketServer 會 new byte[1MB]）。
 * 判斷：worker thread 冇 OutOfMemoryError、heap 用量細、洪水後正常請求仍然得。
 * 對照：舊代碼（git HEAD）喺呢個測試入面會有大量 OOM。
 */
public final class HttpFloodE2ETest {
    private static int passed = 0;
    private static int failed = 0;
    private static final AtomicInteger oom = new AtomicInteger();

    private HttpFloodE2ETest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    private static long usedMb() {
        System.gc();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    private static String get(String raw) throws Exception {
        Socket s = new Socket("127.0.0.1", HttpServer.PORT);
        s.setSoTimeout(3000);
        s.getOutputStream().write(raw.getBytes("ISO-8859-1"));
        s.getOutputStream().flush();
        String line;
        try {
            byte[] b = new byte[256];
            int n = s.getInputStream().read(b);
            line = n > 0 ? new String(b, 0, n, "ISO-8859-1").split("\r\n")[0] : "<closed>";
        } catch (SocketTimeoutException e) {
            line = "<timeout>";
        }
        s.close();
        return line;
    }

    private static Socket ws() throws Exception {
        Socket s = new Socket("127.0.0.1", HttpServer.PORT);
        s.setSoTimeout(3000);
        s.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes());
        s.getOutputStream().flush();
        InputStream in = s.getInputStream();
        StringBuilder sb = new StringBuilder();
        int c;
        while (!sb.toString().endsWith("\r\n\r\n") && (c = in.read()) >= 0) sb.append((char) c);
        if (!sb.toString().startsWith("HTTP/1.1 101")) throw new IOException("websocket upgrade failed: " + sb);
        return s;
    }

    private static boolean pingPong() throws Exception {
        Socket s = ws();
        try {
            OutputStream o = s.getOutputStream();
            byte[] mask = {1, 2, 3, 4}, pl = "abcd".getBytes(), m = new byte[4];
            for (int i = 0; i < 4; i++) m[i] = (byte) (pl[i] ^ mask[i % 4]);
            o.write(0x89);
            o.write(0x80 | 4);
            o.write(mask);
            o.write(m);
            o.flush();
            StringBuilder got = new StringBuilder();
            long end = System.currentTimeMillis() + 2000;
            InputStream in = s.getInputStream();
            byte[] b = new byte[256];
            try {
                while (System.currentTimeMillis() < end) {
                    int n = in.read(b);
                    if (n < 0) break;
                    got.append(new String(b, 0, n, "ISO-8859-1"));
                    if (got.indexOf("\u008a\u0004abcd") >= 0) return true;
                }
            } catch (SocketTimeoutException ignore) {
            }
            return got.indexOf("\u008a\u0004abcd") >= 0;
        } finally {
            s.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (e instanceof OutOfMemoryError) oom.incrementAndGet();
            }
        });
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        check(maxMb <= 96, "run with -Xmx64m so the flood is large relative to the heap (max heap=" + maxMb + " MB)");

        HttpServer srv = new HttpServer(new AssetManager(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, java.util.Map<String, String> query, String method,
                    String body, java.util.Map<String, String> headers) {
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
        }, null, null);
        srv.start();
        Thread.sleep(300);
        try {
            // A) 20 × Content-Length: 33554431，一個 body byte 都唔送
            List<Socket> held = new ArrayList<Socket>();
            for (int i = 0; i < 20; i++) {
                Socket s = new Socket("127.0.0.1", HttpServer.PORT);
                held.add(s);
                s.getOutputStream().write("POST /api/x HTTP/1.1\r\nHost: x\r\nContent-Length: 33554431\r\n\r\n".getBytes());
                s.getOutputStream().flush();
                Thread.sleep(30);
            }
            Thread.sleep(800);
            long used = usedMb();
            check(oom.get() == 0, "A: no OutOfMemoryError in server threads (count=" + oom.get() + ")");
            check(used < maxMb / 2, "A: heap stays small with 20 header-only 32MB claims (" + used + " / " + maxMb + " MB)");
            check(get("GET /api/ping HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"),
                    "A: normal request still works after the flood");
            for (Socket s : held) s.close();
            held.clear();
            Thread.sleep(500);

            // B) 100 × WebSocket frame 聲稱 1MB，mask 之後一個 payload byte 都唔送
            check(pingPong(), "B: websocket ping/pong works before the flood");
            for (int i = 0; i < 100; i++) {
                Socket s = ws();
                held.add(s);
                OutputStream o = s.getOutputStream();
                o.write(0x82);
                o.write(0x80 | 127);
                long len = 1024 * 1024;
                for (int k = 7; k >= 0; k--) o.write((int) ((len >> (8 * k)) & 0xFF));
                o.write(new byte[] {9, 9, 9, 9});
                o.flush();
                Thread.sleep(10);
            }
            Thread.sleep(800);
            used = usedMb();
            check(oom.get() == 0, "B: no OutOfMemoryError in server threads (count=" + oom.get() + ")");
            check(used < maxMb / 2, "B: heap stays small with 100 half-sent 1MB frames (" + used + " / " + maxMb + " MB)");
            check(pingPong(), "B: websocket ping/pong works after the flood");
            for (Socket s : held) s.close();
        } finally {
            srv.stop();
        }
        System.out.println("HttpFloodE2ETest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
