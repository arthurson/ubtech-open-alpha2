package com.open.alpha2;

import android.util.Log;

import java.security.KeyStore;
import java.security.cert.Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Wraps a freshly-generated {@link SelfSignedCert} into an in-memory PKCS12 KeyStore and
 * builds an SSLContext/SSLServerSocketFactory from it, so HttpServer can listen with TLS.
 *
 * Why this exists at all: navigator.mediaDevices.getUserMedia() (the walkie-talkie
 * feature's browser-mic capture, see app.js's startTalk()) simply does not exist on a
 * plain http://<lan-ip>:8888/ origin - browsers only expose it in "secure contexts"
 * (https:// or localhost). Serving the panel over https:// with a self-signed cert
 * makes the origin secure enough for getUserMedia to appear, at the cost of the browser
 * showing a one-time "connection not private" warning the user has to click through
 * (unavoidable for any self-signed cert - there is no CA-trusted alternative for a LAN
 * IP with no real domain name).
 *
 * KeyStore is never persisted to a file - "" password, in-memory only, rebuilt fresh
 * each time the app starts alongside a new SelfSignedCert. Simpler than reasoning about
 * securing an on-disk keystore file/password for a LAN test tool where the cert's
 * identity claim is cosmetic anyway.
 */
final class TlsSupport {
    private static final String TAG = "TlsSupport";
    private static final char[] KEYSTORE_PASSWORD = "alpha2panel".toCharArray();

    private TlsSupport() {
    }

    /** Builds an SSLServerSocketFactory around a freshly generated self-signed cert for
     *  the given common name (typically the robot's LAN IP - see MainActivity, which
     *  discovers this before starting HttpServer). Throws on any crypto/keystore
     *  failure; callers should fall back to plain HTTP rather than fail the whole app if
     *  this doesn't work on some device (see HttpServer's constructor). */
    static SSLServerSocketFactory buildServerSocketFactory(String commonName) throws Exception {
        SelfSignedCert cert = SelfSignedCert.generate(commonName);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null); // start empty, in-memory
        keyStore.setKeyEntry("alpha2panel", cert.privateKey, KEYSTORE_PASSWORD,
                new Certificate[]{cert.certificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new java.security.SecureRandom());

        Log.i(TAG, "TLS context ready (self-signed cert, CN=" + commonName + ")");
        return sslContext.getServerSocketFactory();
    }
}
