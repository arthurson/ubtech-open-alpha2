package com.open.alpha2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

/**
 * BundledCaSsl／SecureDownload 單元測試 (零依賴，不用 JUnit)。
 * 用 JDK 內置 HttpsServer＋測試證書 (src/test/resources/com/open/alpha2/tls/，scripts/make-tls-fixtures.py 生成)
 * 做真 TLS 握手：錯憑證／過期／hostname 唔符／自簽都必須失敗，redirect 白名單逐項驗證。
 */
public final class TlsPolicyTest {
    private static int passed = 0;
    private static int failed = 0;
    private static final String RES = "/com/open/alpha2/tls/";
    private static final AtomicInteger finalHits = new AtomicInteger();

    private TlsPolicyTest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    private static InputStream res(String name) throws IOException {
        InputStream in = TlsPolicyTest.class.getResourceAsStream(RES + name);
        if (in == null) throw new IOException("test resource missing: " + name);
        return in;
    }

    // ---- 本地 HTTPS 伺服器 ----
    private static HttpsServer server(String p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = res(p12);
        try { ks.load(in, "changeit".toCharArray()); } finally { in.close(); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        final HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ctx));
        final int port = s.getAddress().getPort();
        s.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange x) throws IOException {
                String path = x.getRequestURI().getPath();
                String loc = null; int code = 200; String body = "ok";
                if (path.equals("/final")) { finalHits.incrementAndGet(); body = "final"; }
                else if (path.equals("/redir-rel")) { code = 302; loc = "/final"; }
                else if (path.equals("/redir-other-host")) { code = 302; loc = "https://localhost:" + port + "/final"; }
                else if (path.equals("/redir-http")) { code = 302; loc = "http://127.0.0.1:" + port + "/final"; }
                else if (path.equals("/redir-port")) { code = 302; loc = "https://127.0.0.1:" + (port + 1) + "/final"; }
                else if (path.equals("/redir-userinfo")) { code = 302; loc = "https://user@127.0.0.1:" + port + "/final"; }
                else if (path.equals("/redir-noloc")) { code = 302; }
                else if (path.equals("/loop")) { code = 302; loc = "/loop"; }
                else if (path.equals("/missing")) { code = 404; body = "nope"; }
                if (loc != null) x.getResponseHeaders().add("Location", loc);
                byte[] b = body.getBytes("UTF-8");
                x.sendResponseHeaders(code, code == 302 ? -1 : b.length);
                if (code != 302) { x.getResponseBody().write(b); }
                x.close();
            }
        });
        s.start();
        return s;
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    /** 直接用 BundledCaSsl.applyWith 做一次 HTTPS GET（只測 TLS 驗證，唔經 SecureDownload）。 */
    private static String tlsGet(String url, SSLSocketFactory f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            BundledCaSsl.applyWith(c, f);
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            return readBody(c);
        } finally { c.disconnect(); }
    }

    private static Throwable tlsFailure(String url, SSLSocketFactory f) {
        try { tlsGet(url, f); return null; } catch (Throwable t) { return t; }
    }

    private static boolean hasCause(Throwable t, Class<?> k) {
        for (Throwable c = t; c != null; c = c.getCause()) if (k.isInstance(c)) return true;
        return false;
    }

    private static String openFailure(String url, SSLSocketFactory f, int port) {
        try {
            HttpURLConnection c = SecureDownload.open(url, new String[] {"127.0.0.1"}, 5000, 5000, "t", f, port);
            c.disconnect();
            return null;
        } catch (IOException e) { return String.valueOf(e.getMessage()); }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. 內建 bundle ----------
        InputStream bin = BundledCaSsl.class.getResourceAsStream(BundledCaSsl.BUNDLE_RESOURCE);
        check(bin != null, "bundled CA resource is on the classpath");
        List<X509Certificate> certs = BundledCaSsl.loadCertificates(bin);
        check(certs.size() >= 100, "bundle has >= 100 certs (got " + certs.size() + ")");
        StringBuilder subjects = new StringBuilder();
        for (X509Certificate c : certs) subjects.append(c.getSubjectX500Principal().getName()).append('\n');
        for (String must : new String[] {"ISRG Root X1", "DigiCert Global Root G2", "GlobalSign", "USERTrust RSA"}) {
            check(subjects.indexOf(must) >= 0, "bundle contains root: " + must);
        }
        boolean emptyRejected = false, garbageRejected = false;
        try { BundledCaSsl.buildFactory(new ByteArrayInputStream(new byte[0]), false, 50); } catch (IOException e) { emptyRejected = true; }
        try { BundledCaSsl.buildFactory(new ByteArrayInputStream("not a pem".getBytes()), false, 1); } catch (IOException e) { garbageRejected = true; }
        check(emptyRejected, "empty bundle is rejected (fail-closed, never an empty trust set)");
        check(garbageRejected, "garbage bundle is rejected");
        InputStream bin2 = BundledCaSsl.class.getResourceAsStream(BundledCaSsl.BUNDLE_RESOURCE);
        check(BundledCaSsl.buildFactory(bin2, false, BundledCaSsl.MIN_BUNDLED_CERTS) != null, "production bundle builds a factory");

        // ---------- 2. TLS 驗證（真握手）----------
        SSLSocketFactory testCa = BundledCaSsl.buildFactory(res("ca.pem"), false, 1);
        HttpsServer ok = server("server-ok.p12"), wrong = server("server-wrongname.p12"),
                expired = server("server-expired.p12"), self = server("server-selfsigned.p12");
        try {
            String okUrl = "https://127.0.0.1:" + ok.getAddress().getPort() + "/";
            check("ok".equals(tlsGet(okUrl, testCa)), "trusted CA + matching SAN -> handshake ok, body ok");

            Throwable t = tlsFailure("https://127.0.0.1:" + self.getAddress().getPort() + "/", testCa);
            check(t instanceof SSLException, "self-signed (untrusted) cert is rejected: " + t);

            t = tlsFailure("https://127.0.0.1:" + wrong.getAddress().getPort() + "/", testCa);
            check(t instanceof SSLException, "hostname mismatch is rejected: " + t);

            t = tlsFailure("https://127.0.0.1:" + expired.getAddress().getPort() + "/", testCa);
            check(t instanceof SSLException && hasCause(t, CertificateExpiredException.class),
                    "expired cert is rejected (CertificateExpiredException in cause chain): " + t);

            // 正式配置（系統 CA ∪ 內建 Mozilla bundle）唔信測試 CA：中間人換證會失敗
            t = tlsFailure(okUrl, BundledCaSsl.socketFactory());
            check(t instanceof SSLException, "production trust set rejects a cert from an unknown CA: " + t);

            // 唔係 HTTPS 一律拒絕，唔會靜默降級
            boolean httpRejected = false;
            try { BundledCaSsl.apply((HttpURLConnection) new URL("http://127.0.0.1:1/").openConnection()); }
            catch (IOException e) { httpRejected = true; }
            check(httpRejected, "apply() refuses a plain-HTTP connection");

            // ---------- 3. checkAllowed 表（正式：443）----------
            String[] gh = SecureDownload.GITHUB_RELEASE_HOSTS;
            String[][] table = {
                {"https://github.com/a/b/releases/download/x/y.apk", "ok"},
                {"https://release-assets.githubusercontent.com/x?y=1", "ok"},
                {"https://objects.githubusercontent.com/x", "ok"},
                {"https://GITHUB.com/x", "ok"},
                {"https://github.com:443/x", "ok"},
                {"http://github.com/x", "no"},
                {"https://github.com:8443/x", "no"},
                {"https://evil.com/x", "no"},
                {"https://github.com.evil.com/x", "no"},
                {"https://notgithub.com/x", "no"},
                {"https://xgithubusercontent.com/x", "no"},
                {"https://github.com@evil.com/x", "no"},
                {"https://user:pw@github.com/x", "no"},
                {"ftp://github.com/x", "no"},
            };
            for (String[] row : table) {
                boolean allowed;
                try { SecureDownload.checkAllowed(new URL(row[0]), gh); allowed = true; } catch (IOException e) { allowed = false; }
                check(allowed == row[1].equals("ok"), "allowlist " + row[0] + " -> " + row[1]);
            }
            boolean voskOk, voskBad;
            try { SecureDownload.checkAllowed(new URL("https://alphacephei.com/vosk/models/x.zip"), SecureDownload.VOSK_HOSTS); voskOk = true; } catch (IOException e) { voskOk = false; }
            try { SecureDownload.checkAllowed(new URL("https://github.com/x"), SecureDownload.VOSK_HOSTS); voskBad = false; } catch (IOException e) { voskBad = true; }
            check(voskOk && voskBad, "vosk allowlist: alphacephei.com only");

            // ---------- 4. redirect 行為（真伺服器）----------
            int p = ok.getAddress().getPort();
            String base = "https://127.0.0.1:" + p;
            HttpURLConnection c = SecureDownload.open(base + "/", new String[] {"127.0.0.1"}, 5000, 5000, "t", testCa, p);
            check(c.getResponseCode() == 200 && "ok".equals(readBody(c)), "open: plain 200");
            c.disconnect();
            finalHits.set(0);
            c = SecureDownload.open(base + "/redir-rel", new String[] {"127.0.0.1"}, 5000, 5000, "t", testCa, p);
            check(c.getResponseCode() == 200 && "final".equals(readBody(c)) && finalHits.get() == 1,
                    "open: relative redirect is followed and re-checked");
            c.disconnect();
            c = SecureDownload.open(base + "/missing", new String[] {"127.0.0.1"}, 5000, 5000, "t", testCa, p);
            check(c.getResponseCode() == 404, "open: non-2xx returned to caller (caller checks the code)");
            c.disconnect();

            finalHits.set(0);
            String m;
            m = openFailure(base + "/redir-other-host", testCa, p);
            check(m != null && m.contains("host not allowed"), "redirect to non-allowlisted host rejected: " + m);
            m = openFailure(base + "/redir-http", testCa, p);
            check(m != null && m.contains("non-HTTPS"), "redirect https->http rejected: " + m);
            m = openFailure(base + "/redir-port", testCa, p);
            check(m != null && m.contains("port not allowed"), "redirect to another port rejected: " + m);
            m = openFailure(base + "/redir-userinfo", testCa, p);
            check(m != null && m.contains("userinfo"), "redirect with userinfo rejected: " + m);
            m = openFailure(base + "/redir-noloc", testCa, p);
            check(m != null && m.contains("without Location"), "redirect without Location rejected: " + m);
            m = openFailure(base + "/loop", testCa, p);
            check(m != null && m.contains("too many redirects"), "redirect loop stops after " + SecureDownload.MAX_REDIRECTS + " hops: " + m);
            check(finalHits.get() == 0, "rejected redirects never reached the target (finalHits=" + finalHits.get() + ")");
            // 第一跳就唔喺白名單：連都唔連
            m = openFailure("https://127.0.0.1:" + p + "/", testCa, p + 1);
            check(m != null && m.contains("port not allowed"), "initial URL on a disallowed port rejected: " + m);
            // TLS 層失敗要傳出去（唔係被吞咗）
            m = openFailure("https://127.0.0.1:" + self.getAddress().getPort() + "/", testCa, self.getAddress().getPort());
            check(m != null, "open: TLS verification failure surfaces as IOException: " + m);
        } finally {
            ok.stop(0); wrong.stop(0); expired.stop(0); self.stop(0);
        }

        System.out.println("TlsPolicyTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
