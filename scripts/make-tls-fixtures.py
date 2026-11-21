#!/usr/bin/env python3
"""
生成 TlsPolicyTest 用嘅測試證書 fixtures (app/src/test/resources/com/open/alpha2/tls/)。

fixtures 已 commit，平時唔使跑；只係證書有需要重做先跑（需要 `pip install cryptography`）。
內容：
  ca.pem                測試 CA（只喺測試入面當信任錨，絕不會出現喺正式 bundle）
  server-ok.p12         CA 簽發，SAN = IP:127.0.0.1 / DNS:localhost，有效 100 年
  server-wrongname.p12  CA 簽發，但 SAN 只有 other.example（hostname 唔符）
  server-expired.p12    CA 簽發，已過期（2020-01 ~ 2020-02）
  server-selfsigned.p12 自簽（唔係由測試 CA 簽發）
全部 p12 密碼：changeit（3DES/SHA1 PBE，舊／新 JDK 都讀到）。
"""
import datetime
import ipaddress
import pathlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/test/resources/com/open/alpha2/tls"
PW = b"changeit"
D = datetime.datetime


def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def name(cn):
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def build(subject, subject_key, issuer, issuer_key, not_before, not_after, san=None, ca=False):
    b = (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer)
         .public_key(subject_key.public_key()).serial_number(x509.random_serial_number())
         .not_valid_before(not_before).not_valid_after(not_after)
         .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    if ca:
        b = b.add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True,
                                          content_commitment=False, key_encipherment=False,
                                          data_encipherment=False, key_agreement=False,
                                          encipher_only=False, decipher_only=False), critical=True)
    else:
        b = b.add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
    if san:
        b = b.add_extension(x509.SubjectAlternativeName(san), critical=False)
    return b.sign(issuer_key, hashes.SHA256())


def p12(path, k, cert, chain=()):
    enc = (serialization.PrivateFormat.PKCS12.encryption_builder()
           .kdf_rounds(2048).key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
           .hmac_hash(hashes.SHA1()).build(PW))
    path.write_bytes(pkcs12.serialize_key_and_certificates(b"server", k, cert, list(chain), enc))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    start, far = D(2020, 1, 1), D(2120, 1, 1)
    ca_key = key()
    ca = build(name("Open Alpha2 Test CA"), ca_key, name("Open Alpha2 Test CA"), ca_key, start, far, ca=True)
    (OUT / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    local = [x509.IPAddress(ipaddress.ip_address("127.0.0.1")), x509.DNSName("localhost")]

    k = key()
    p12(OUT / "server-ok.p12", k, build(name("localhost"), k, ca.subject, ca_key, start, far, local), [ca])
    k = key()
    p12(OUT / "server-wrongname.p12", k,
        build(name("other.example"), k, ca.subject, ca_key, start, far, [x509.DNSName("other.example")]), [ca])
    k = key()
    p12(OUT / "server-expired.p12", k,
        build(name("localhost"), k, ca.subject, ca_key, D(2020, 1, 1), D(2020, 2, 1), local), [ca])
    k = key()
    ss = build(name("localhost"), k, name("localhost"), k, start, far, local)
    p12(OUT / "server-selfsigned.p12", k, ss)
    print("fixtures written to", OUT)


if __name__ == "__main__":
    main()
