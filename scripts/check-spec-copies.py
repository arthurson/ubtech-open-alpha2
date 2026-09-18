#!/usr/bin/env python3
"""
Web 側 spec copy 一致性檢查 (openapi/ 是單一真相源)。

背景：瀏覽器經同一部機取 spec——`/openapi.yml` (docs UI + apis.yml +
llms.txt)、`/.well-known/openapi.json` (docs 頁連結，AI 發現用)、
`/asyncapi.yml` (apis.yml + llms.txt)。`app/src/main/assets/web/` 下面
那幾份是 `openapi/` 的 copy (另加 `well-known/` 無點版，留下以防出面有用)，
之前全靠人手 sync——試過 asyncapi copy 留下已死的 binder 事件
(asr_result/speech_ready) 而源頭已清，無人發現。

規則：
- yml copy 必須同源頭 byte-identical (手改 copy 即 drift)。
- json copy 必須同源頭 parse 之後 semantic equal (格式轉換，逐 byte 比不到)。

用法:
  python scripts/check-spec-copies.py         # 检查，drift 即 exit 1 (啱 CI 用)
  python scripts/check-spec-copies.py --fix   # 把源頭 byte-copy 蓋落 drift 了的
                                              # yml copy (json 不懂得原 producer
                                              # 格式設定，不碰，只報告)
"""
import difflib
import json
import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
WEB = ROOT / "app" / "src" / "main" / "assets" / "web"
SPEC = ROOT / "openapi"

# (源頭, copy)：yml 逐 byte 比
YML_PAIRS = [
    (SPEC / "open-alpha2-openapi.yml", WEB / "openapi.yml"),
    (SPEC / "open-alpha2-openapi.yml", WEB / ".well-known" / "openapi.yml"),
    (SPEC / "open-alpha2-openapi.yml", WEB / "well-known" / "openapi.yml"),
    (SPEC / "asyncapi.yml", WEB / "asyncapi.yml"),
]

# (源頭 yml, copy json)：parse 之後比
JSON_PAIRS = [
    (SPEC / "open-alpha2-openapi.yml", WEB / ".well-known" / "openapi.json"),
    (SPEC / "open-alpha2-openapi.yml", WEB / "well-known" / "openapi.json"),
]


def check_yml(src: pathlib.Path, dst: pathlib.Path) -> bool:
    a = src.read_bytes()
    if not dst.exists():
        print(f"DIFF {dst.relative_to(ROOT)}: 缺席")
        return False
    b = dst.read_bytes()
    if a == b:
        print(f"OK   {dst.relative_to(ROOT)}")
        return True
    print(f"DIFF {dst.relative_to(ROOT)} (源頭 {src.relative_to(ROOT)}):")
    try:
        diff = difflib.unified_diff(
            a.decode("utf-8").splitlines(), b.decode("utf-8").splitlines(),
            fromfile="spec", tofile="copy", lineterm="", n=1)
        shown = 0
        for line in diff:
            print("    " + line)
            shown += 1
            if shown >= 30:
                print("    ... (截斷)")
                break
    except UnicodeDecodeError:
        print("    (非 UTF-8，不 show diff)")
    return False


def check_json(src: pathlib.Path, dst: pathlib.Path) -> bool:
    want = yaml.safe_load(src.read_text(encoding="utf-8"))
    if not dst.exists():
        print(f"DIFF {dst.relative_to(ROOT)}: 缺席")
        return False
    got = json.loads(dst.read_text(encoding="utf-8"))
    if want == got:
        print(f"OK   {dst.relative_to(ROOT)} (semantic)")
        return True
    print(f"DIFF {dst.relative_to(ROOT)} (semantic，同源頭不等)")
    if isinstance(want, dict) and isinstance(got, dict):
        for k in sorted(set(want) | set(got)):
            if want.get(k) != got.get(k):
                print(f"    頂層 key 不同: {k}")
    return False


def main() -> int:
    fix = "--fix" in sys.argv[1:]
    bad_yml = []
    for src, dst in YML_PAIRS:
        assert src.exists(), f"源頭不見了: {src}"
        if not check_yml(src, dst):
            bad_yml.append((src, dst))
    bad_json = []
    for src, dst in JSON_PAIRS:
        if not check_json(src, dst):
            bad_json.append((src, dst))
    if fix and bad_yml:
        for src, dst in bad_yml:
            dst.write_bytes(src.read_bytes())
            print(f"FIXED {dst.relative_to(ROOT)} <= {src.relative_to(ROOT)}")
        bad_yml = []
    if fix and bad_json:
        print("json drift 不懂得原 producer 格式設定，不自動修，請人手處理")
    if bad_yml or bad_json:
        print(f"FAIL: yml {len(bad_yml)} 處, json {len(bad_json)} 處")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
