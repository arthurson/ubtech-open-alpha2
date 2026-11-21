package com.open.alpha2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 受控信任錨 TLS：<b>系統 CA ∪ 內建 Mozilla CA bundle</b>，並用平台預設嘅嚴格 hostname 驗證。
 *
 * <p>點解要有：Android 5.1 等舊機系統 CA store 係出廠 snapshot，唔收錄新根（例如 ISRG Root X1），
 * 以前個解法係 trust-all（見 XiaozhiTrustAllSsl），令下載／上傳完全冇身份驗證。呢個 class 用 app
 * 內建嘅 Mozilla 根證書（src/main/resources/com/open/alpha2/cacert.pem，由
 * scripts/update-cacert.py 更新）做額外信任錨：舊機都可以做真正嘅憑證鏈驗證，錯憑證、過期憑證、
 * hostname 唔符一律失敗。系統 CA 照樣信任，所以新機亦唔會因為 bundle 較舊而驗證失敗。</p>
 *
 * <p>只做驗證，唔做 pinning／revocation；bundle 過期只會令「新 CA 簽發」嘅網站失敗（fail-closed），
 * 唔會令驗證變鬆。純 java.*（唔掂 android），可 JVM 單元測試（TlsPolicyTest）。</p>
 */
final class BundledCaSsl {
    /** classpath 資源（AGP 會將 src/main/resources 打入 APK）。 */
    static final String BUNDLE_RESOURCE = "/com/open/alpha2/cacert.pem";
    /** 內建 bundle 至少要載入咁多張，否則當 bundle 壞咗（fail-closed）。 */
    static final int MIN_BUNDLED_CERTS = 50;

    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private static volatile SSLSocketFactory cached;

    private BundledCaSsl() {
    }

    /** 正式用嘅 factory：系統 CA ∪ 內建 bundle。Lazy＋cache（載入 ~120 張 PEM 唔算慳，唔使每次連線重做）。 */
    static SSLSocketFactory socketFactory() throws IOException {
        SSLSocketFactory f = cached;
        if (f != null) return f;
        synchronized (BundledCaSsl.class) {
            if (cached == null) {
                InputStream in = BundledCaSsl.class.getResourceAsStream(BUNDLE_RESOURCE);
                if (in == null) throw new IOException("bundled CA resource missing: " + BUNDLE_RESOURCE);
                try {
                    cached = buildFactory(in, true, MIN_BUNDLED_CERTS);
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignore) {
                    }
                }
            }
            return cached;
        }
    }

    /**
     * 幫連線裝上嚴格 TLS。唔係 HTTPS 即拋（唔會靜默降級做明文）；唔設 HostnameVerifier，
     * 用平台預設嘅嚴格驗證；任何設定失敗都拋 IOException，由呼叫方當下載失敗處理。
     */
    static void apply(HttpURLConnection conn) throws IOException {
        applyWith(conn, socketFactory());
    }

    static void applyWith(HttpURLConnection conn, SSLSocketFactory factory) throws IOException {
        if (!(conn instanceof HttpsURLConnection)) {
            throw new IOException("refusing non-HTTPS connection");
        }
        ((HttpsURLConnection) conn).setSSLSocketFactory(factory);
    }

    /**
     * @param pem           PEM 證書（可夾住註解文字；只讀 BEGIN/END CERTIFICATE 區塊）
     * @param includeSystem true＝同系統 CA 取聯集；false＝只信 pem（測試／模擬「冇系統 CA」嘅舊機）
     * @param minCerts      成功解析嘅證書下限，唔夠即拋（bundle 壞咗唔可以靜默變成空信任錨）
     */
    static SSLSocketFactory buildFactory(InputStream pem, boolean includeSystem, int minCerts)
            throws IOException {
        try {
            KeyStore anchors = loadAnchors(pem, minCerts);
            X509TrustManager bundled = trustManagerFor(anchors);
            X509TrustManager system = includeSystem ? trustManagerFor(null) : null;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new CompositeTrustManager(system, bundled)},
                    new SecureRandom());
            return ctx.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS trust setup failed: " + e, e);
        }
    }

    /** 載入 bundle 內所有證書（測試用：檢查內容）。 */
    static List<X509Certificate> loadCertificates(InputStream pem) throws IOException {
        String text = readAll(pem);
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int from = 0;
            while (true) {
                int b = text.indexOf(BEGIN, from);
                if (b < 0) break;
                int e = text.indexOf(END, b);
                if (e < 0) break;
                e += END.length();
                String block = text.substring(b, e);
                from = e;
                try {
                    // 逐張交畀 provider（唔自己 Base64 decode：java.util.Base64 要 API 26）。
                    Certificate c = cf.generateCertificate(
                            new ByteArrayInputStream(block.getBytes("US-ASCII")));
                    if (c instanceof X509Certificate) out.add((X509Certificate) c);
                } catch (CertificateException skip) {
                    // 個別證書 provider 唔支援（例如罕見簽名算法）：跳過，總數不足先當失敗。
                }
            }
        } catch (CertificateException e) {
            throw new IOException("X.509 factory unavailable: " + e, e);
        }
        return out;
    }

    private static KeyStore loadAnchors(InputStream pem, int minCerts)
            throws IOException, GeneralSecurityException {
        List<X509Certificate> certs = loadCertificates(pem);
        if (certs.size() < minCerts) {
            throw new IOException("CA bundle has only " + certs.size() + " usable certs (need >= " + minCerts + ")");
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (int i = 0; i < certs.size(); i++) {
            ks.setCertificateEntry("bundled-ca-" + i, certs.get(i));
        }
        return ks;
    }

    private static X509TrustManager trustManagerFor(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks); // null＝平台系統 CA
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("no X509TrustManager");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 18);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("ISO-8859-1"); // 區塊係 ASCII；註解（UTF-8）亂碼都無所謂
    }

    /** 任何一邊信任就通過（信任錨聯集）；兩邊都唔信先失敗，並附上裝置時鐘（時鐘錯係舊機常見死因）。 */
    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system; // 可為 null
        private final X509TrustManager bundled;

        CompositeTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException systemErr = null;
            if (system != null) {
                try {
                    system.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    systemErr = e;
                }
            }
            try {
                bundled.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                CertificateException out = new CertificateException(
                        e.getMessage() + " [device clock: " + new java.util.Date() + "]", e);
                if (systemErr != null) out.addSuppressed(systemErr);
                throw out;
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            bundled.checkClientTrusted(chain, authType); // 唔使 client auth，只係補齊介面
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = bundled.getAcceptedIssuers();
            X509Certificate[] s = system == null ? new X509Certificate[0] : system.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[a.length + s.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(s, 0, all, a.length, s.length);
            return all;
        }
    }
}
