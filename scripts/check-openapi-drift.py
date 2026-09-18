#!/usr/bin/env python3
"""
檢查 code ↔ spec 漂移：確保 handleApi/handleSystemApi/handleDirectApi/
handleXiaozhiApi（前三者在 ApiDispatcher，後者在 XiaozhiBridge）的 case
名稱皆在 openapi 中有對應。
用法: python scripts/check-openapi-drift.py
回傳 0=無漂移, 1=有漂移 (適合 CI)。

2026-09 重寫：舊版將四個 handler 的 case name 全部丟入同一個扁平
set 才比對 spec，只對 handleDirectApi 做 "direct/" 前綴映射——
handleSystemApi 的 case（如 "discover"、"music/seek"）沒有補 "system/"
前綴、handleXiaozhiApi 的 case（如 "connect"、"activation_status"）
沒有補 "xiaozhi/" 前綴，同時 handleApi 同 handleDirectApi 有同名 bare
case（如 "servo/one"）時，扁平 set 會將兩個 handler 的 case 混在一起
處理，其中一個因此被錯誤覆蓋。這些缺口本身不會令 CI fail（漏報，
不是報大），但會生成大量假 "extra" 令人手核對的信心下降，所以這裡
改為逐個 handler 分開抽取 case、各自補回正確 namespace 前綴，先合併
去同 spec 比對。同時新增 handleUpload/handleStream (MainActivity,
"xxx".equals(path) if/else 鏈，不是 switch case) 的獨立抽取——舊版
完全沒有掃這兩個 method，令 "upload/*"、"stream/*" 一律被當漏報「extra」。
"""
import pathlib, re, yaml, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
DISP = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "ApiDispatcher.java"
XZ = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "XiaozhiBridge.java"
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"

spec = yaml.safe_load(open(SPEC, encoding='utf-8'))
# spec paths 一律 "/api/..." 或 "/upload/..." / "/stream/..." 或 "/", "/blockly.html"；
# 去除 "/api/" 前綴、"/upload"、"/stream" 前面的斜線，方便同 case name（沒有 "api/" 前綴）比較。
spec_norm = set()
for p in spec['paths'].keys():
    q = p.lstrip('/')
    if q.startswith('api/'):
        spec_norm.add(q[len('api/'):])
    elif q.startswith('upload/') or q.startswith('stream/'):
        spec_norm.add(q)
    # "" (根 "/") 同 "blockly.html" 沒有對應 case，跳過。


def extract_method_body(text: str, method_name: str) -> str:
    """找 `<method_name>(...)  {` 到下一個同縮排 public/private 方法或檔案尾的內容。"""
    m = re.search(
        r'(?:private|public)\s+HttpServer\.ApiResponse\s+' + re.escape(method_name)
        + r'\(.*?\)\s*\{(.*?)(?:\n    (?:private|public) |\n\}\s*$)',
        text, re.DOTALL)
    if not m:
        raise SystemExit(f"❌ method body not found: {method_name}() — drift script 同 code 結構脫節，請人手檢查")
    return m.group(1)


def cases_in(body: str) -> set:
    return set(re.findall(r'case "([^"]+)"', body))


disp_text = DISP.read_text(encoding='utf-8')
xz_text = XZ.read_text(encoding='utf-8')
main_text = MAIN.read_text(encoding='utf-8')

# 逐個 handler 分開抽取，各自加回 spec 對應的 namespace 前綴：
# - handleApi: case name 已經同 spec "api/*" 剝皮後一致，沒有前綴。
# - handleSystemApi: spec 是 "api/system/*"，這裡 case 沒有 "system/"，要補。
# - handleDirectApi: spec 是 "api/direct/*"，這裡 case 沒有 "direct/"，要補。
# - handleXiaozhiApi: spec 是 "api/xiaozhi/*"，這裡 case 沒有 "xiaozhi/"，要補。
handlers = [
    ("handleApi", disp_text, ""),
    ("handleSystemApi", disp_text, "system/"),
    ("handleDirectApi", disp_text, "direct/"),
    ("handleXiaozhiApi", xz_text, "xiaozhi/"),
]

# 少數 handleApi 內的 case 沒有 "/"（例如 "status"），要人手白名單先當 API path，
# 其餘沒有 "/" 的 case（preset 值好似 "flash"/"breathe" 等）不算。這個白名單
# 僅用在 handleApi（prefix==""）：其餘三個 handler 的 case 一經補正確
# namespace 前綴（"system/"、"direct/"、"xiaozhi/"）已經天然帶 "/"，不應該
# 再套用這個為 handleApi 度身訂造的白名單（曾經令 "discover"、"connect" 等
# 裸名 case 被錯誤篩走，變成假 "extra"）。
BARE_SINGLETONS = {"status", "supported", "mic/start", "mic/stop"}

api_cases = set()
per_handler_cases = {}
for name, text, prefix in handlers:
    body = extract_method_body(text, name)
    raw = cases_in(body)
    if prefix == "":
        kept = set(c for c in raw if '/' in c or c in BARE_SINGLETONS)
    else:
        # 有 prefix 的 handler：僅濾走真是不是 path 的裸值（例如某啲 case
        # 本身是參數枚舉，不是 endpoint）——這類 handler 底下暫時未見過，
        # 保留全部裸名，加了 prefix 之後一律當 path 看待，交給後面同 spec
        # 比對的步驟決定是否漂移。
        kept = raw
    prefixed = set(prefix + c for c in kept)
    per_handler_cases[name] = prefixed
    api_cases |= prefixed

# handleUpload/handleStream 在 MainActivity 用 "xxx".equals(path) 的 if/else
# 鏈做路由 (不是 switch case)，同上面四個 handler 結構不同，要獨立抽取。
# spec 裡面這兩組 path 是 "upload/audio"、"stream/camera" 這種扁平命名
# (沒有 "api/" 前綴)，對應 MainActivity 這兩個 method 裡面比較的字面值。
def extract_equals_branches(text: str, method_name: str) -> set:
    m = re.search(
        r'(?:private|public)\s+\S+\s+' + re.escape(method_name)
        + r'\(.*?\)[^{]*\{(.*?)(?:\n    (?:private|public) |\n\}\s*$)',
        text, re.DOTALL)
    if not m:
        raise SystemExit(f"❌ method body not found: {method_name}() — drift script 同 code 結構脫節，請人手檢查")
    return set(re.findall(r'"([^"]+)"\.equals\(path\)', m.group(1)))


upload_cases = set('upload/' + c for c in extract_equals_branches(main_text, "handleUpload"))
stream_cases = set('stream/' + c for c in extract_equals_branches(main_text, "handleStream"))
per_handler_cases["handleUpload"] = upload_cases
per_handler_cases["handleStream"] = stream_cases
api_cases |= upload_cases | stream_cases

missing = sorted(api_cases - spec_norm)
extra = sorted(spec_norm - api_cases)

print(f"spec paths (api/upload/stream): {len(spec_norm)}  code api_cases: {len(api_cases)}")
for name, _, prefix in handlers:
    print(f"  {name}: {len(per_handler_cases[name])} cases (prefix '{prefix}')")
print(f"  handleUpload: {len(per_handler_cases['handleUpload'])} cases (prefix 'upload/')")
print(f"  handleStream: {len(per_handler_cases['handleStream'])} cases (prefix 'stream/')")

if missing:
    print("❌ missing in spec (code has, spec lacks):")
    for x in missing:
        print(f"  - {x}")
else:
    print("✅ no missing in spec")

if extra:
    print("❌ extra in spec (spec has, code lacks):")
    for x in extra:
        print(f"  - {x}")
else:
    print("✅ no extra")

if missing or extra:
    sys.exit(1)
sys.exit(0)
