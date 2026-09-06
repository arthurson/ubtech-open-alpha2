#!/usr/bin/env python3
"""
檢查 api-client.js 每個 wrapper 打去嘅 URL 真係有後端接。

Caller 對後端路由 (見 MainActivity dispatch + app-core.js helper)：
  api('X')        -> /api/alpha2/X   -> handleApi case X
  sysApi('X')     -> /api/system/X   -> handleSystemApi case X
  directApi('X')  -> /api/direct/X   -> handleDirectApi case X
  xiaozhiApi('X') -> /api/xiaozhi/X  -> XiaozhiBridge.handleXiaozhiApi case X

之前試過 generator 語無倫次出 `api('system/...')` → 打去
/api/alpha2/system/... → handleApi 404，而 drift check 睇唔到
(佢只對 code case ↔ spec)。呢個 script 補返 client → server 呢段。

用法: python scripts/check-api-client-routes.py (啱 CI 用)
回傳 0=全對，1=有錯。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"
XZ = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "XiaozhiBridge.java"
DISP = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "ApiDispatcher.java"
CLIENT = ROOT / "app" / "src" / "main" / "assets" / "web" / "api-client.js"

text = MAIN.read_text(encoding="utf-8")
xztext = XZ.read_text(encoding="utf-8")
disptext = DISP.read_text(encoding="utf-8")


def handler_cases(name: str, src: str = text, tail: str = r"\n    (?:private|public) ") -> set:
    """抽某 handle*Api() 方法體內全部 case "..." (裸路徑)。"""
    m = re.search(
        r"(?:private|public) HttpServer\.ApiResponse " + name + r"\(.*?\)\s*\{(.*?)" + tail,
        src, re.DOTALL)
    assert m, f"{name} body not found"
    return set(re.findall(r'case "([^"]+)"', m.group(1)))


cases = {
    # handleApi 成段搬咗去 ApiDispatcher (檔尾，最後一個 method)。
    "api": handler_cases("handleApi", disptext, tail=r"\n\}\s*$"),
    # handleSystemApi/directApi 跟埋搬過去 (system 在中間，direct 喺檔尾)。
    "sysApi": handler_cases("handleSystemApi", disptext),
    "directApi": handler_cases("handleDirectApi", disptext, tail=r"\n\}\s*$"),
    "xiaozhiApi": handler_cases("handleXiaozhiApi", xztext),
}

client = CLIENT.read_text(encoding="utf-8")
calls = re.findall(r"return (api|sysApi|directApi|xiaozhiApi)\('([^']+)', params\)", client)
assert calls, "api-client.js 內一個 wrapper call 都搵唔到"

bad = []
for caller, rel in calls:
    if rel not in cases[caller]:
        bad.append(f"{caller}('{rel}') 無對應後端 case")

print(f"client calls: {len(calls)}  api={sum(1 for c, _ in calls if c == 'api')} "
      f"sys={sum(1 for c, _ in calls if c == 'sysApi')} "
      f"direct={sum(1 for c, _ in calls if c == 'directApi')} "
      f"xiaozhi={sum(1 for c, _ in calls if c == 'xiaozhiApi')}")
if bad:
    print("❌ 誤路由 (client 有，後端無)：")
    for b in bad:
        print(f"  - {b}")
    sys.exit(1)
print("✅ 全部 wrapper 路由正確")
