package com.open.alpha2;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Generates a self-signed RSA/X.509v3 certificate + private key entirely by hand, using
 * only java.security.* primitives that have been present since API 1 - no
 * java.security.cert certificate-*generation* API exists on Android (the platform can
 * only parse certificates, not create them), and no third-party crypto library
 * (BouncyCastle etc.) is available per this project's "SDK + JDK/Android framework
 * only" dependency policy (see build.gradle / SDK module comments).
 *
 * This exists purely to let HttpServer listen with TLS so that the browser origin
 * becomes a "secure context" - required for navigator.mediaDevices.getUserMedia() to
 * exist at all (see AudioPlaybackController / app.js's startTalk() comments on why the
 * walkie-talkie mic capture silently has no getUserMedia on a plain http:// origin).
 * The certificate is regenerated fresh in memory every time the app starts and is never
 * written to disk - there is no need for it to persist across launches, and keeping the
 * private key in memory only avoids having to think about on-disk key storage/security
 * at all.
 *
 * Implementation notes:
 *  - DER/ASN.1 is hand-encoded (SEQUENCE/SET/INTEGER/BIT STRING/OCTET STRING/OID/
 *    UTCTime/context-tag wrappers) - there is no ASN.1 encoder in the Android/JDK
 *    standard library either, only a *decoder* (used indirectly by
 *    CertificateFactory.generateCertificate() at the bottom of this file to parse the
 *    bytes this class just built, confirming they're well-formed).
 *  - Signature algorithm: SHA256withRSA (rsaEncryption OID, universally accepted by
 *    Android's TLS stack including on API 19-22).
 *  - Validity window is deliberately wide (from 20 years in the past to 20 years in the
 *    future) so an inaccurate system clock on the robot (no NTP / no user-facing clock
 *    UI) can never put "now" outside the certificate's validity period - a clock-skew
 *    NotBefore/NotAfter failure would be a much more confusing failure mode for this
 *    panel's purpose than the certificate being trivially long-lived.
 */
final class SelfSignedCert {
    private static final String TAG = "SelfSignedCert";

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private SelfSignedCert(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /** Generates a fresh self-signed cert for the given CN (typically the robot's LAN IP
     *  or "localhost"/"alpha2panel" - the browser will show a warning regardless of what
     *  CN is used, since the whole point is that it's self-signed / untrusted, so the
     *  exact value only matters cosmetically in the browser's cert-details view). */
    static SelfSignedCert generate(String commonName) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair keyPair = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 20L * 365 * 24 * 60 * 60 * 1000);
        Date notAfter = new Date(now + 20L * 365 * 24 * 60 * 60 * 1000);
        BigInteger serial = new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE);

        byte[] tbsCert = buildTbsCertificate(keyPair.getPublic(), commonName, serial, notBefore, notAfter);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCert);
        byte[] signatureBytes = signer.sign();

        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        byte[] sigAlgId = sequence(concat(oid(OID_SHA256_WITH_RSA), NULL));
        byte[] sigBitString = bitString(signatureBytes);
        byte[] certDer = sequence(concat(tbsCert, sigAlgId, sigBitString));

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certDer));
        // Round-tripping through CertificateFactory both hands back a usable
        // X509Certificate object for the KeyStore below AND acts as a self-check that
        // the hand-rolled DER above actually parses as well-formed X.509 - if the
        // encoding were wrong this line would throw CertificateException instead of an
        // obscure TLS handshake failure much later.
        Log.i(TAG, "Generated self-signed cert: CN=" + commonName
                + ", serial=" + serial + ", valid " + notBefore + " .. " + notAfter);
        return new SelfSignedCert(keyPair.getPrivate(), cert);
    }

    // ---- TBSCertificate construction -------------------------------------------------

    private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";
    private static final String OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
    private static final String OID_COMMON_NAME = "2.5.4.3";
    private static final byte[] NULL = new byte[]{0x05, 0x00};

    private static byte[] buildTbsCertificate(PublicKey publicKey, String commonName,
            BigInteger serial, Date notBefore, Date notAfter) throws Exception {
        // version [0] EXPLICIT INTEGER { v3(2) }
        byte[] version = contextTag(0, integer(BigInteger.valueOf(2)));

        byte[] serialDer = integer(serial);
        byte[] signatureAlgId = sequence(concat(oid(OID_SHA256_WITH_RSA), NULL));
        byte[] issuer = rdnSequence(commonName);
        byte[] validity = sequence(concat(utcTime(notBefore), utcTime(notAfter)));
        byte[] subject = issuer; // self-signed: subject == issuer
        byte[] subjectPublicKeyInfo = buildSubjectPublicKeyInfo(publicKey);

        return sequence(concat(version, serialDer, signatureAlgId, issuer, validity,
                subject, subjectPublicKeyInfo));
    }

    private static byte[] buildSubjectPublicKeyInfo(PublicKey publicKey) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPublicKeySpec spec = kf.getKeySpec(publicKey, RSAPublicKeySpec.class);
        byte[] rsaPubKeySeq = sequence(concat(
                integer(spec.getModulus()), integer(spec.getPublicExponent())));
        byte[] algId = sequence(concat(oid(OID_RSA_ENCRYPTION), NULL));
        byte[] pubKeyBitString = bitString(rsaPubKeySeq);
        return sequence(concat(algId, pubKeyBitString));
    }

    private static byte[] rdnSequence(String commonName) {
        // RDNSequence ::= SEQUENCE OF RelativeDistinguishedName (each a SET of AttributeTypeAndValue)
        byte[] cnAttr = sequence(concat(oid(OID_COMMON_NAME), utf8String(commonName)));
        byte[] rdn = set(cnAttr);
        return sequence(rdn);
    }

    // ---- Minimal hand-rolled DER/ASN.1 encoding --------------------------------------

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
            return;
        }
        List<Integer> bytes = new ArrayList<>();
        int n = len;
        while (n > 0) {
            bytes.add(0, n & 0xFF);
            n >>>= 8;
        }
        out.write(0x80 | bytes.size());
        for (int b : bytes) out.write(b);
    }

    private static byte[] sequence(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] set(byte[] content) {
        return tlv(0x31, content);
    }

    private static byte[] contextTag(int tagNumber, byte[] content) {
        return tlv(0xA0 | (tagNumber & 0x1F), content);
    }

    private static byte[] integer(BigInteger value) {
        byte[] raw = value.toByteArray(); // already two's-complement, minimal-length per BigInteger contract
        return tlv(0x02, raw);
    }

    private static byte[] bitString(byte[] content) {
        byte[] withUnusedBitsPrefix = new byte[content.length + 1];
        withUnusedBitsPrefix[0] = 0x00; // 0 unused bits
        System.arraycopy(content, 0, withUnusedBitsPrefix, 1, content.length);
        return tlv(0x03, withUnusedBitsPrefix);
    }

    private static byte[] utf8String(String s) {
        return tlv(0x0C, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] utcTime(Date date) {
        // UTCTime, format YYMMDDHHmmssZ, always UTC per X.509 profile.
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return tlv(0x17, fmt.format(date).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]);
        writeOidArc(body, first);
        for (int i = 2; i < parts.length; i++) {
            writeOidArc(body, Integer.parseInt(parts[i]));
        }
        return tlv(0x06, body.toByteArray());
    }

    private static void writeOidArc(ByteArrayOutputStream out, int value) {
        // Base-128, most-significant-first, all but the last byte having the high bit set.
        int mask = 0x7F;
        List<Integer> groups = new ArrayList<>();
        groups.add(value & mask);
        value >>>= 7;
        while (value > 0) {
            groups.add(0, (value & mask) | 0x80);
            value >>>= 7;
        }
        for (int g : groups) out.write(g);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
