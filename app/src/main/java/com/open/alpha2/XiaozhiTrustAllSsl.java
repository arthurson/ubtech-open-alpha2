package com.open.alpha2;

import android.util.Log;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 小智 (XiaoZhi) 專用的「全信任」SSL helper - 只給 XiaozhiClient (WebSocket) 和
 * XiaozhiOtaClient (OTA/認證碼 HTTP) 兩個類用, 不影響這個 app 裡面其他用
 * HttpsURLConnection/SSLSocketFactory 的地方 (例如 Radio Browser、camera vision)。
 *
 * 背景 (2026-08): Android 5.1 (API 22, 2015 年出廠) 的系統 CA store 是出廠時的
 * snapshot, 沒得 OTA 更新, 不同機因應廠商 build 時間/有沒有自己 patch 過,
 * 內建的根憑證清單可以完全不一樣 - 這不是「4.4 中招、5.1 一定不會中招」
 * 這樣和 Android 版本掛勾, 純粹是哪台機出廠那刻的 CA store 有沒有收錄
 * xiaozhi.me/api.tenclass.net 現在用的那條憑證鏈的根。沒收錄那台機連
 * TLS handshake 都過不了, 會撞上
 * java.security.cert.CertPathValidatorException: Trust anchor for
 * certification path not found (android.security.net.config.RootTrustManager
 * 拋出)。
 *
 * 這個 class 提供一個永遠信任任何憑證鏈的 TrustManager + 一個永遠接受任何
 * hostname 的 HostnameVerifier, 讓小智連接 (OTA 拿認證碼 + WebSocket) 在
 * 這種舊機/CA store 不齊的情況下都連得到。代價: 這兩條連線的 TLS 層不再
 * 防中間人攻擊 - 對一個連官方 xiaozhi.me/Tenclass 帳戶的家用機械人來說,
 * 這個取捨是為了在 API 22 沒得補 CA store 的硬限制下都用得到這個功能,
 * 但不應該逼全域套用到這個 app 其他本身在驗證的 https 連線上。
 */
final class XiaozhiTrustAllSsl {
    private static final String TAG = "XiaozhiTrustAllSsl";

    private XiaozhiTrustAllSsl() {}

    /** 永遠信任任何憑證鏈的 TrustManager - checkClientTrusted/checkServerTrusted
     *  兩個都特意留空 (也就是「什麼都不查, 一定通過」), getAcceptedIssuers()
     *  按 javax.net.ssl.X509TrustManager 官方文件建議、在這種「不限制發行者」
     *  的用法下回傳空 array。 */
    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 特意留空: 不驗證, 全部信任。
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 特意留空: 不驗證, 全部信任。
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /** 永遠接受任何 hostname - 因為連憑證鏈都不驗證, 驗 hostname 對不上
     *  那張不可信的證也沒意義, 保持和 checkServerTrusted() 一致的取捨。 */
    static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            return true;
        }
    };

    private static volatile SSLSocketFactory cachedFactory;

    /** 取回一個已經裝了 TRUST_ALL_MANAGER 的 SSLSocketFactory - 給
     *  XiaozhiClient.connect() 包裝 raw socket 用, 代替
     *  SSLSocketFactory.getDefault() (跟系統 CA store, 在這個場景會撞
     *  CertPathValidatorException)。Lazy + cache: SSLContext.init() 不算
     *  重, 但沒必要每次連接都重新起一個。 */
    static SSLSocketFactory getTrustAllSocketFactory() {
        SSLSocketFactory factory = cachedFactory;
        if (factory != null) return factory;
        synchronized (XiaozhiTrustAllSsl.class) {
            if (cachedFactory == null) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
                    cachedFactory = ctx.getSocketFactory();
                } catch (NoSuchAlgorithmException | KeyManagementException e) {
                    // 理論上不應該發生 (TLS 這個 algorithm 名和 SSLContext API 在
                    // 所有 Android 版本都有) - 出事就 fallback 用回系統 default,
                    // 至少行為退回「未改之前」, 不會讓 app 直接 crash 掉。
                    Log.e(TAG, "建立 trust-all SSLContext 失敗, fallback 用回系統 default", e);
                    cachedFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                }
            }
            return cachedFactory;
        }
    }

    /** 幫 HttpsURLConnection 裝上 trust-all 的 SSLSocketFactory +
     *  HostnameVerifier - 給 XiaozhiOtaClient 在 openConnection() 拿到的
     *  connection 是 HttpsURLConnection 的時候調用。如果傳入的不是
     *  HttpsURLConnection (例如萬一 URL 打錯成了 http://), 什麼都不做,
     *  不會拋 exception。 */
    static void applyTrustAll(java.net.HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
            httpsConn.setHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
        }
    }
}
