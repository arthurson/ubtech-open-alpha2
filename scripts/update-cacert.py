#!/usr/bin/env python3
"""
更新內建 CA bundle (app/src/main/resources/com/open/alpha2/cacert.pem)。

BundledCaSsl 用呢個檔做受控信任錨（同系統 CA 取聯集），令舊 Android 機（系統 CA 過時）
都可以做嚴格 TLS 驗證而唔使 trust-all。建議每半年／發版前更新一次。

來源（二選一）：
  python scripts/update-cacert.py                          # 下載 https://curl.se/ca/cacert.pem (Mozilla 根證書抽取)
  python scripts/update-cacert.py /etc/ssl/certs/ca-certificates.crt   # 用本機 Mozilla bundle (例如 Debian/Ubuntu)

寫入前檢查：證書數量下限、必須包含幾個關鍵根（ISRG Root X1、DigiCert Global Root G2、
GlobalSign Root CA、USERTrust RSA），否則拒絕覆蓋（避免下載到壞檔就悄悄弄壞 TLS）。
更新後請跑 scripts/test-apivalidator.py（TlsPolicyTest 會驗證 bundle 內容）。
"""
import datetime
import pathlib
import re
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "app" / "src" / "main" / "resources" / "com" / "open" / "alpha2" / "cacert.pem"
URL = "https://curl.se/ca/cacert.pem"
MIN_CERTS = 100
MUST_HAVE = ["ISRG Root X1", "DigiCert Global Root G2", "GlobalSign Root CA", "USERTrust RSA"]


def main() -> int:
    if len(sys.argv) > 1:
        src = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
        origin = str(sys.argv[1])
    else:
        with urllib.request.urlopen(URL, timeout=60) as r:
            src = r.read().decode("utf-8", errors="replace")
        origin = URL
    blocks = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", src, re.S)
    if len(blocks) < MIN_CERTS:
        print(f"拒絕：只有 {len(blocks)} 張證書（需要 >= {MIN_CERTS}）")
        return 1
    subjects = []
    for b in blocks:
        r = subprocess.run(["openssl", "x509", "-noout", "-subject"], input=b,
                           capture_output=True, text=True)
        if r.returncode != 0:
            print("拒絕：有證書 openssl 解析唔到")
            return 1
        subjects.append(r.stdout)
    for name in MUST_HAVE:
        if not any(name in s for s in subjects):
            print(f"拒絕：缺少關鍵根 {name}")
            return 1
    hdr = (
        "# Open Alpha2 內建 CA bundle（BundledCaSsl 嘅受控信任錨，同系統 CA 取聯集）。\n"
        f"# 來源：Mozilla NSS 根證書（{origin}，共 {len(blocks)} 張），更新日期 {datetime.date.today()}。\n"
        "# 授權：Mozilla CA 證書資料屬 MPL-2.0。\n"
        "# 目的：Android 5.1 等舊機系統 CA store 已過時，唔使退回 trust-all 都可以做嚴格 TLS 驗證。\n"
        "# 更新：python scripts/update-cacert.py\n"
    )
    DEST.write_text(hdr + "\n".join(blocks) + "\n", encoding="utf-8")
    print(f"OK {len(blocks)} 張 -> {DEST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
