package com.open.alpha2;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.SSLSocketFactory;

/**
 * 固定來源下載嘅連線 helper：嚴格 TLS（BundledCaSsl）＋「手動」跟 redirect，
 * 每一跳都重新檢查 scheme／port／host 白名單。
 *
 * <p>取代之前各下載器自己寫嘅 trust-all＋{@code setInstanceFollowRedirects(true)}（HttpURLConnection
 * 自動跟隨任何 https 跳轉，亦冇 host 限制）；亦唔再有 {@code catch (Throwable ignore)} 吞 TLS 設定錯誤——
 * 設定失敗就直接拋 IOException，下載失敗。純 java.*，可 JVM 單元測試（TlsPolicyTest）。</p>
 */
final class SecureDownload {
    /** GitHub release：github.com → release-assets.githubusercontent.com（實測單一跳）。 */
    static final String[] GITHUB_RELEASE_HOSTS = {"github.com", "githubusercontent.com"};
    /** Vosk 模型（固定 allowlist，見 VoskController.DL_BASE）。 */
    static final String[] VOSK_HOSTS = {"alphacephei.com"};

    static final int MAX_REDIRECTS = 5;
    static final int HTTPS_PORT = 443;

    private SecureDownload() {
    }

    /** 回傳已經收到 response header 嘅連線（未讀 body）；呼叫方照舊檢查 getResponseCode()。 */
    static HttpURLConnection open(String url, String[] allowedHosts, int connectMs, int readMs,
            String userAgent) throws IOException {
        return open(url, allowedHosts, connectMs, readMs, userAgent, null);
    }

    /** @param factory null＝正式（BundledCaSsl.socketFactory()）；非 null 只供測試注入。 */
    static HttpURLConnection open(String url, String[] allowedHosts, int connectMs, int readMs,
            String userAgent, SSLSocketFactory factory) throws IOException {
        return open(url, allowedHosts, connectMs, readMs, userAgent, factory, HTTPS_PORT);
    }

    /** @param port 允許嘅 port（正式固定 443；測試用本地伺服器先會改）。 */
    static HttpURLConnection open(String url, String[] allowedHosts, int connectMs, int readMs,
            String userAgent, SSLSocketFactory factory, int port) throws IOException {
        URL cur = new URL(url);
        for (int hop = 0; ; hop++) {
            checkAllowed(cur, allowedHosts, port);
            HttpURLConnection c = (HttpURLConnection) cur.openConnection();
            try {
                if (factory != null) BundledCaSsl.applyWith(c, factory);
                else BundledCaSsl.apply(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setRequestProperty("User-Agent", userAgent);
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) throw new IOException("redirect without Location");
                    if (hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
                    cur = new URL(cur, loc); // 相對 Location 都支援；下一圈會重新檢查
                    continue;
                }
                return c;
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
        }
    }

    /** 必須 https、port 443（或預設）、冇 userinfo、host 等於白名單項或係其子網域。 */
    static void checkAllowed(URL u, String[] allowedHosts) throws IOException {
        checkAllowed(u, allowedHosts, HTTPS_PORT);
    }

    static void checkAllowed(URL u, String[] allowedHosts, int port) throws IOException {
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException("non-HTTPS URL rejected: " + u.getProtocol() + "://" + u.getHost());
        }
        int effective = u.getPort() == -1 ? HTTPS_PORT : u.getPort();
        if (effective != port) {
            throw new IOException("port not allowed: " + u.getHost() + ":" + effective);
        }
        if (u.getUserInfo() != null) {
            throw new IOException("URL with userinfo rejected: " + u.getHost());
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) throw new IOException("URL without host rejected");
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return;
        }
        throw new IOException("host not allowed: " + host);
    }
}
