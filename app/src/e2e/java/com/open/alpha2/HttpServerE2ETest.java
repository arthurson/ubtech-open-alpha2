package com.open.alpha2;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HttpServer 真 socket 整合測試 (零依賴，不用 JUnit)：
 * request line 長度上限、來源 IP 注入（客戶端偽造會被覆蓋）、429＋Retry-After 格式、同時連線上限。
 * 見 app/src/e2e/README.md（用 stub 嘅 android.*，由 scripts/test-apivalidator.py 執行）。
 */
public final class HttpServerE2ETest {
    private static int passed = 0;
    private static int failed = 0;

    private HttpServerE2ETest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    private static String request(String raw) throws Exception {
        Socket s = new Socket("127.0.0.1", HttpServer.PORT);
        s.setSoTimeout(3000);
        s.getOutputStream().write(raw.getBytes("ISO-8859-1"));
        s.getOutputStream().flush();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = s.getInputStream().read(b)) > 0) bo.write(b, 0, n);
        } catch (SocketTimeoutException e) {
            bo.write("<<TIMEOUT>>".getBytes());
        } catch (SocketException e) {
            bo.write("<<RESET>>".getBytes());
        }
        s.close();
        return bo.toString("ISO-8859-1");
    }

    public static void main(String[] args) throws Exception {
        final AtomicInteger handled = new AtomicInteger();
        final AtomicReference<String> seenIp = new AtomicReference<String>("<none>");
        HttpServer srv = new HttpServer(new AssetManager(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, java.util.Map<String, String> query, String method,
                    String body, java.util.Map<String, String> headers) {
                handled.incrementAndGet();
                seenIp.set(String.valueOf(headers.get(HttpServer.HDR_REMOTE_ADDR)));
                if (path.equals("throttled")) return HttpServer.ApiResponse.tooManyRequests(3500);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
        }, null, null);
        srv.start();
        Thread.sleep(300);
        try {
            // --- 正常請求 ---
            String r = request("GET /api/ping HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check(r.startsWith("HTTP/1.1 200") && handled.get() == 1, "normal GET -> 200 handled");

            // --- request line 上限：8000 字元照常處理；9000 字元唔交畀 handler ---
            StringBuilder p8000 = new StringBuilder("/api/");
            while (p8000.length() < 7900) p8000.append('a');
            int before = handled.get();
            r = request("GET " + p8000 + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check(r.startsWith("HTTP/1.1 200") && handled.get() == before + 1, "~8000-char request line is still served");
            StringBuilder p9000 = new StringBuilder("/api/");
            while (p9000.length() < 9000) p9000.append('a');
            before = handled.get();
            r = request("GET " + p9000 + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check(handled.get() == before && !r.startsWith("HTTP/1.1 200"),
                    "9000-char request line is rejected and never reaches the handler");

            // --- 來源 IP 注入：以 socket 為準，客戶端偽造（含大小寫變體）會被覆蓋 ---
            request("GET /api/ping HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check("127.0.0.1".equals(seenIp.get()), "server injects the real socket ip (got " + seenIp.get() + ")");
            request("GET /api/ping HTTP/1.1\r\nHost: x\r\nX-Remote-Addr: 6.6.6.6\r\nx-REMOTE-addr: 7.7.7.7\r\n"
                    + "Connection: close\r\n\r\n");
            check("127.0.0.1".equals(seenIp.get()), "client-forged x-remote-addr is overwritten (got " + seenIp.get() + ")");

            // --- 429 格式 ---
            r = request("GET /api/throttled HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check(r.startsWith("HTTP/1.1 429 Too Many Requests"), "429 status line: " + r.split("\r\n")[0]);
            check(r.contains("Retry-After: 4\r\n"), "Retry-After rounds 3500 ms up to 4 s");
            check(r.contains("too many failed attempts, retry in 4s"), "429 JSON body says how long to wait");

            // --- method 限制：/api、/upload 只收 GET／POST，其他回 405＋Allow，唔交畀 handler ---
            before = handled.get();
            for (String m : new String[] {"PUT", "DELETE", "PATCH", "HEAD"}) {
                r = request(m + " /api/ping HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                check(r.startsWith("HTTP/1.1 405 Method Not Allowed") && r.contains("Allow: GET, POST, OPTIONS\r\n"),
                        m + " /api/ -> 405 with Allow header: " + r.split("\r\n")[0]);
            }
            check(handled.get() == before, "405 requests never reach the api handler");
            r = request("POST /api/ping HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            check(r.startsWith("HTTP/1.1 200"), "POST /api/ still accepted");

            // --- body 上限：按 endpoint 收細，超限回 413（唔交畀 handler）---
            check(HttpServer.maxBodyFor("/api/x") == 256 * 1024, "api body limit 256KB");
            check(HttpServer.maxBodyFor("/upload/servo-fw") == 64 * 1024, "servo-fw body limit 64KB");
            check(HttpServer.maxBodyFor("/upload/audio") == 256 * 1024, "audio body limit 256KB");
            check(HttpServer.maxBodyFor("/upload/chest") == 1024 * 1024, "chest body limit 1MB");
            check(HttpServer.maxBodyFor("/upload/policy") == 12 * 1024 * 1024, "policy body limit 12MB");
            check(HttpServer.maxBodyFor("/upload/music") == 32 * 1024 * 1024, "music body limit 32MB");
            before = handled.get();
            r = request("POST /api/x HTTP/1.1\r\nHost: x\r\nContent-Length: 300000\r\nConnection: close\r\n\r\n");
            check(r.startsWith("HTTP/1.1 413 Payload Too Large"), "300000B body on /api -> 413: " + r.split("\r\n")[0]);
            check(handled.get() == before, "413 request never reaches the api handler");

            // --- 全域 in-flight body 預算（64MB）：兩個 32MB 上載佔滿，第三個回 503，釋放後恢復 ---
            String bigUpload = "POST /upload/music?name=a.mp3 HTTP/1.1\r\nHost: x\r\nContent-Length: 33554432\r\n\r\n";
            Socket h1 = new Socket("127.0.0.1", HttpServer.PORT);
            Socket h2 = new Socket("127.0.0.1", HttpServer.PORT);
            h1.getOutputStream().write(bigUpload.getBytes("ISO-8859-1")); h1.getOutputStream().flush();
            h2.getOutputStream().write(bigUpload.getBytes("ISO-8859-1")); h2.getOutputStream().flush();
            Thread.sleep(500);
            r = request(bigUpload + "");
            check(r.startsWith("HTTP/1.1 503 Service Unavailable"), "3rd concurrent 32MB upload -> 503: " + r.split("\r\n")[0]);
            h1.close(); h2.close();
            Thread.sleep(800);
            r = request(bigUpload + "");
            check(!r.startsWith("HTTP/1.1 503"), "budget released after holders disconnect (got " + r.split("\r\n")[0] + ")");

            // --- 同時連線上限：滿咗之後新連線即刻被關，釋放後恢復 ---
            Field counterF = HttpServer.class.getDeclaredField("activeClients");
            counterF.setAccessible(true);
            AtomicInteger counter = (AtomicInteger) counterF.get(srv);
            int cap = HttpServer.MAX_CONCURRENT_CLIENTS;
            Thread.sleep(500);
            List<Socket> held = new ArrayList<Socket>();
            for (int i = 0; i < cap; i++) {
                held.add(new Socket("127.0.0.1", HttpServer.PORT));
                Thread.sleep(3);
            }
            Thread.sleep(300);
            check(counter.get() == cap, "active == cap after " + cap + " idle connections (got " + counter.get() + ")");
            Socket over = new Socket("127.0.0.1", HttpServer.PORT);
            over.setSoTimeout(1500);
            int rv;
            try { rv = over.getInputStream().read(); } catch (SocketTimeoutException e) { rv = -999; } catch (SocketException e) { rv = -1; }
            check(rv == -1, "connection #" + (cap + 1) + " is closed immediately (read=" + rv + ")");
            check(counter.get() == cap, "a rejected connection does not leak the counter (got " + counter.get() + ")");
            over.close();
            held.remove(0).close();
            Thread.sleep(500);
            check(counter.get() == cap - 1, "slot is released when a client disconnects (got " + counter.get() + ")");
            Socket again = new Socket("127.0.0.1", HttpServer.PORT);
            again.setSoTimeout(1500);
            try { rv = again.getInputStream().read(); } catch (SocketTimeoutException e) { rv = -999; } catch (SocketException e) { rv = -1; }
            check(rv == -999, "a new connection is accepted again below the cap (read=" + rv + ")");
            again.close();
            for (Socket s : held) s.close();
        } finally {
            srv.stop();
        }
        System.out.println("HttpServerE2ETest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
