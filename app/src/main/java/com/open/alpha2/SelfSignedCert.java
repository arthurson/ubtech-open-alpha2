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
 * The certificate/private key are persisted to two files under the app's private
 * storage directory (see loadOrGenerate()) so the same key pair and certificate survive
 * app restarts as long as the common name (the robot's LAN IP) hasn't changed. This
 * matters because a browser's "I accepted this self-signed cert" trust decision is
 * keyed off the certificate itself (its public key / fingerprint), not just the origin
 * hostname - regenerating a brand new key pair and cert on every single app launch (the
 * previous behaviour) meant every restart invalidated every browser's prior "Proceed
 * anyway" click, forcing the user to click through the warning again and again even
 * though nothing about the panel's identity had actually changed. If the IP *has*
 * changed since the cached cert was written, a fresh cert is generated for the new IP
 * (the SAN must match the actual current address or hostname verification fails
 * regardless of persistence).
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
    // Plain filenames (no path separators) so callers just need to pass a directory -
    // see loadOrGenerate(). PKCS#8 for the key (the standard unencrypted private-key
    // DER format, directly consumable by KeyFactory.generatePrivate() with no ASN.1
    // wrapper of our own to invent) and raw X.509 DER for the cert (what
    // CertificateFactory.generateCertificate() and cert.getEncoded() already agree on).
    private static final String KEY_FILENAME = "alpha2_tls_key.der";
    private static final String CERT_FILENAME = "alpha2_tls_cert.der";

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private SelfSignedCert(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /** Loads a previously-persisted cert/key pair from cacheDir if one exists, its CN
     *  still matches commonName, and it isn't expired - otherwise generates a fresh one
     *  and writes it to cacheDir for next time. cacheDir should be the app's private
     *  storage (e.g. context.getFilesDir()) so no other app can read the private key.
     *  Any failure while reading/parsing a cached file (corrupt file, unexpected
     *  format, permission issue, etc.) is treated the same as "no cache yet" - falls
     *  through to generating fresh rather than propagating the read failure, since a
     *  missing cache is a normal, fully-recoverable state for this method and the whole
     *  point of caching is a browser-convenience optimisation, not a hard requirement. */
    static SelfSignedCert loadOrGenerate(java.io.File cacheDir, String commonName) throws Exception {
        java.io.File keyFile = new java.io.File(cacheDir, KEY_FILENAME);
        java.io.File certFile = new java.io.File(cacheDir, CERT_FILENAME);

        if (keyFile.exists() && certFile.exists()) {
            try {
                SelfSignedCert cached = loadFrom(keyFile, certFile);
                if (matchesAndStillValid(cached.certificate, commonName)) {
                    Log.i(TAG, "Reusing cached self-signed cert: CN=" + commonName
                            + ", serial=" + cached.certificate.getSerialNumber());
                    return cached;
                }
                Log.i(TAG, "Cached cert no longer matches (CN changed or expired) - regenerating");
            } catch (Exception e) {
                Log.i(TAG, "Cached cert unreadable, regenerating: " + e);
            }
        }

        SelfSignedCert fresh = generate(commonName);
        try {
            saveTo(fresh, keyFile, certFile);
        } catch (Exception e) {
            // Not fatal: fresh is already usable in memory for this run, it just won't
            // survive to the next launch. Surface it in logcat so a persistent failure
            // to persist (e.g. storage full) is at least visible, without taking down
            // TLS setup over what is purely a convenience feature.
            Log.e(TAG, "Failed to persist generated cert for next launch (will regenerate again next time)", e);
        }
        return fresh;
    }

    private static boolean matchesAndStillValid(X509Certificate cert, String commonName) {
        String actualCn = cert.getSubjectX500Principal().getName();
        // getName() returns e.g. "CN=192.168.1.50" - compare against what rdnSequence()
        // would produce for commonName so a cached cert for a since-changed IP is
        // correctly rejected rather than silently reused with a now-wrong SAN.
        if (!actualCn.equals("CN=" + commonName)) return false;
        try {
            cert.checkValidity();
        } catch (Exception e) {
            return false;
        }
        // getBasicConstraints() returns -1 if the certificate has no basicConstraints
        // extension at all (as opposed to >= 0 for "is/isn't a CA"). A cert persisted to
        // disk by an older build of this class - before buildBasicConstraintsExtension()
        // (and the other 3 mandatory extensions) existed - would still pass the CN/
        // validity checks above, since neither of those looks at extensions. Reusing
        // such a cert causes every modern browser/conscrypt TLS stack to reject the
        // handshake outright with a "certificate_unknown" alert and no "Proceed anyway"
        // prompt at all - the exact 100%-failure pattern this was written to catch.
        // Forcing a fresh cert here is the fix for a robot that already has a stale
        // pre-extensions cert cached in getFilesDir() from before this class was fixed.
        if (cert.getBasicConstraints() == -1) {
            Log.i(TAG, "Cached cert missing basicConstraints extension (pre-fix cert) - regenerating");
            return false;
        }
        // A cert generated by the previous 20-year-past/20-year-future scheme has a
        // ~40-year total validity span and is still sitting in getFilesDir() on any
        // device that already ran that version - checkValidity() alone would happily
        // accept it (it's not literally expired), so it would keep being reused forever
        // and keep failing the TLS handshake against newer conscrypt/BoringSSL builds
        // that reject overlong-lived leaf certs. Reject anything with a validity span
        // longer than ~3 years (comfortably above the current 825-day/~27-month scheme)
        // so a stale long-lived cert gets regenerated exactly once, here.
        long spanMillis = cert.getNotAfter().getTime() - cert.getNotBefore().getTime();
        long threeYearsMillis = 3L * 365 * 24 * 60 * 60 * 1000;
        if (spanMillis > threeYearsMillis) {
            Log.i(TAG, "Cached cert has an overlong validity span (pre-fix cert) - regenerating");
            return false;
        }
        return true;
    }

    private static SelfSignedCert loadFrom(java.io.File keyFile, java.io.File certFile) throws Exception {
        byte[] keyBytes = readAllBytes(keyFile);
        byte[] certBytes = readAllBytes(certFile);

        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certBytes));

        return new SelfSignedCert(privateKey, certificate);
    }

    private static void saveTo(SelfSignedCert cert, java.io.File keyFile, java.io.File certFile) throws Exception {
        // PKCS8EncodedKeySpec is exactly what PrivateKey.getEncoded() already returns
        // for an RSA key from this provider (standard PKCS#8 DER) - no extra wrapping
        // needed, this is the same format loadFrom() feeds straight back into
        // PKCS8EncodedKeySpec above.
        writeAllBytes(keyFile, cert.privateKey.getEncoded());
        writeAllBytes(certFile, cert.certificate.getEncoded());
    }

    private static byte[] readAllBytes(java.io.File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static void writeAllBytes(java.io.File f, byte[] data) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
        }
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
        // Validity window: 1 day in the past (clock-skew slack for a robot with no NTP/
        // no user-facing clock UI) to 825 days in the future (~= 27 months, matching the
        // longest self-signed/leaf lifetime modern TLS stacks still accept without
        // complaint - Apple's and Chrome's own certificate-transparency/lifetime rules
        // cap trusted leaf certs around here). A prior version of this code used a
        // 20-year-past/20-year-future window; that produced a validity period spanning
        // ~40 years (e.g. 2006..2046), and newer conscrypt/BoringSSL builds enforce a
        // hard maximum certificate lifetime even for self-signed leaf certs - such a
        // certificate gets rejected during the TLS handshake itself (certificate_unknown
        // alert, no "proceed anyway" prompt), which is exactly the 100%-failure pattern
        // seen even after basicConstraints/keyUsage/EKU were already correctly added.
        // 1 day of past slack is still ample for a device with a merely-wrong-by-hours
        // clock; it does not need decades of margin to serve that purpose.
        Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
        Date notAfter = new Date(now + 825L * 24 * 60 * 60 * 1000);
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
        // version [0] EXPLICIT INTEGER { v3(2) }  -- v3 是必須嘅, 因為 extensions (下面
        // 嘅 subjectAltName 等) 只喺 v3 先存在; v1 證書冇 extensions 呢個概念。
        byte[] version = contextTag(0, integer(BigInteger.valueOf(2)));

        byte[] serialDer = integer(serial);
        byte[] signatureAlgId = sequence(concat(oid(OID_SHA256_WITH_RSA), NULL));
        byte[] issuer = rdnSequence(commonName);
        byte[] validity = sequence(concat(utcTime(notBefore), utcTime(notAfter)));
        byte[] subject = issuer; // self-signed: subject == issuer
        byte[] subjectPublicKeyInfo = buildSubjectPublicKeyInfo(publicKey);
        // extensions [3] EXPLICIT SEQUENCE OF Extension.
        //
        // 四個 extension 逐個都係之前實測撞過嘅坑, 缺一唔可:
        //  1. subjectAltName - 現代瀏覽器 (Chrome 58+/Firefox/Safari 全部) 喺做 hostname
        //     verification 嗰陣完全唔理 CN 欄位, 只認 SAN。冇呢個 extension 會被硬性拒絕
        //     (連「不受信任, 要唔要 Proceed?」嗰個警告都唔會出)。
        //  2. basicConstraints (critical, CA:FALSE) - 話明呢張係一張 end-entity 證書, 唔係
        //     CA 證書, 唔可以用嚟簽發其他證書。冇呢個 extension, 部分嚴格嘅 TLS 實現
        //     (包括較新嘅 Android conscrypt/BoringSSL) 會拒絕接受, 表現為 handshake 期間
        //     直接送 certificate_unknown alert (SSL3_READ_BYTES 呢層見到嘅 alert 46) 拒絕
        //     連線, 而唔會俾用戶「Proceed anyway」嘅機會 —— 呢個正正係 logcat 見到嘅
        //     100% 連續失敗 pattern (唔係 hostname 唔啱, 係證書結構本身唔完整)。
        //  3. keyUsage (critical, digitalSignature + keyEncipherment) - 話明呢條 key 可以
        //     用嚟做簽名同做 RSA key exchange, TLS handshake 兩者都用到。
        //  4. extendedKeyUsage (serverAuth) - 話明呢張證書嘅用途係「TLS server 身份驗證」;
        //     冇呢個 EKU, 部分 client 會當呢張證書「唔知係咪巖用嚟做呢件事」, 一樣可能
        //     直接拒絕。
        byte[] extensions = contextTag(3, sequence(concat(
                buildSubjectAltNameExtension(commonName),
                buildBasicConstraintsExtension(),
                buildKeyUsageExtension(),
                buildExtendedKeyUsageExtension()
        )));

        return sequence(concat(version, serialDer, signatureAlgId, issuer, validity,
                subject, subjectPublicKeyInfo, extensions));
    }

    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_EXTENDED_KEY_USAGE = "2.5.29.37";
    private static final String OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final byte[] BOOLEAN_TRUE = new byte[]{0x01, 0x01, (byte) 0xFF};

    /** Extension ::= SEQUENCE { extnID OID, critical BOOLEAN DEFAULT FALSE, extnValue OCTET STRING } */
    private static byte[] buildExtension(String oidDotted, boolean critical, byte[] extnValueContent) {
        byte[] extnValue = tlv(0x04, extnValueContent);
        byte[] parts = critical
                ? concat(oid(oidDotted), BOOLEAN_TRUE, extnValue)
                : concat(oid(oidDotted), extnValue);
        return sequence(parts);
    }

    /** basicConstraints ::= SEQUENCE { cA BOOLEAN DEFAULT FALSE }  -- omit cA entirely since
     *  we want it FALSE (the default), critical=true per RFC 5280 recommendation for
     *  end-entity certs so a non-CA-aware verifier can't be tricked into trusting this
     *  cert to sign other certs. */
    private static byte[] buildBasicConstraintsExtension() {
        return buildExtension(OID_BASIC_CONSTRAINTS, true, sequence(new byte[0]));
    }

    /** keyUsage ::= BIT STRING { digitalSignature(0), keyEncipherment(2) }.
     *  Bit 0 (digitalSignature, MSB of first content byte) covers signing the TLS
     *  handshake; bit 2 (keyEncipherment) covers RSA key-exchange cipher suites. Encoded
     *  as a single content byte 0xA0 = 1010_0000 (bit0=1 digitalSignature, bit1=0
     *  nonRepudiation, bit2=1 keyEncipherment), with 3 unused trailing bits noted as 0
     *  per the DER minimal-encoding rule (drop trailing zero bits, so "5 unused" would
     *  be wrong here since bit2 is set - 3 unused bits after the last significant one). */
    private static byte[] buildKeyUsageExtension() {
        byte[] bits = bitString(new byte[]{(byte) 0xA0});
        return buildExtension(OID_KEY_USAGE, true, bits);
    }

    /** extendedKeyUsage ::= SEQUENCE OF KeyPurposeId (OID); just id-kp-serverAuth here -
     *  this cert is only ever used as a TLS *server* cert, never as a client cert. */
    private static byte[] buildExtendedKeyUsageExtension() {
        return buildExtension(OID_EXTENDED_KEY_USAGE, false, sequence(oid(OID_SERVER_AUTH)));
    }

    /** Builds the subjectAltName Extension entry, covering the actual commonName
     *  (typically the robot's current LAN IP) plus "localhost"/127.0.0.1/::1 so the panel
     *  also works when opened via a loopback address (e.g. testing directly on-device). */
    private static byte[] buildSubjectAltNameExtension(String commonName) throws Exception {
        List<byte[]> generalNames = new ArrayList<>();
        addGeneralName(generalNames, commonName);
        addGeneralName(generalNames, "localhost");
        addGeneralName(generalNames, "127.0.0.1");
        addGeneralName(generalNames, "::1");

        byte[] concatenatedNames = concat(generalNames.toArray(new byte[0][]));
        byte[] generalNamesSeq = sequence(concatenatedNames); // GeneralNames ::= SEQUENCE OF GeneralName
        return buildExtension(OID_SUBJECT_ALT_NAME, false, generalNamesSeq);
    }

    /** Appends one GeneralName for the given host: iPAddress [7] (4 raw bytes) if it
     *  parses as an IPv4 literal, otherwise dNSName [2] IA5String. IPv6 literals and
     *  anything else that isn't a bare dotted-quad falls back to dNSName - browsers
     *  accept a dNSName SAN entry that happens to look like an address literal fine for
     *  this self-signed/LAN-only use case, and skipping IPv6 octet-encoding here avoids
     *  extra parsing complexity for a case that shouldn't come up on this panel. */
    private static void addGeneralName(List<byte[]> out, String host) {
        byte[] ipv4 = tryParseIpv4(host);
        if (ipv4 != null) {
            out.add(tlv(0x87, ipv4)); // [7] IMPLICIT OCTET STRING (context tag 7 = iPAddress)
        } else {
            out.add(tlv(0x82, host.getBytes(java.nio.charset.StandardCharsets.US_ASCII))); // [2] IMPLICIT IA5String (context tag 2 = dNSName)
        }
    }

    /** Returns the 4 raw address bytes if `host` is a plain IPv4 dotted-quad (e.g.
     *  "192.168.1.50"), otherwise null. Deliberately not using InetAddress here: that
     *  class does DNS resolution for non-literal input, which is both slow and
     *  pointless when all this needs is "is this string already 4 dot-separated
     *  0-255 numbers". */
    private static byte[] tryParseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            for (int c = 0; c < parts[i].length(); c++) {
                if (!Character.isDigit(parts[i].charAt(c))) return null;
            }
            int v;
            try {
                v = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            result[i] = (byte) v;
        }
        return result;
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
