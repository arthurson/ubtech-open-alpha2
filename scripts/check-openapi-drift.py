#!/usr/bin/env python3
"""
檢查 code ↔ spec 漂移：確保 handleApi/handleSystemApi/handleDirectApi/
handleXiaozhiApi（前三者在 ApiDispatcher，後者在 XiaozhiBridge）的 case
名稱皆在 openapi 中有對應。
用法: python scripts/check-openapi-drift.py
回傳 0=無漂移, 1=有漂移 (適合 CI)。

2026-09 重寫：舊版將四個 handler 的 case name 全部丟入同一個扁平
set 才比對 spec，只對 handleDirectApi 做 "direct/" 前綴映射——
handleSystemApi 的 case（如 "discover"、"music/seek"）冇補 "system/"
前綴、handleXiaozhiApi 的 case（如 "connect"、"activation_status"）
冇補 "xiaozhi/" 前綴，同時 handleApi 同 handleDirectApi 有同名 bare
case（如 "servo/one"）時，扁平 set 會將兩個 handler 的 case 混埋一齊
處理，其中一個因此被錯誤覆蓋。呢啲缺口本身唔會令 CI fail（漏報，
唔係報大），但會生成大量假 "extra" 令人手核對嘅信心下降，所以呢度
改為逐個 handler 分開抽取 case、各自補返正確 namespace 前綴，先合併
去同 spec 比對。同時新增 handleUpload/handleStream (MainActivity,
"xxx".equals(path) if/else 鏈，唔係 switch case) 嘅獨立抽取——舊版
完全冇掃呢兩個 method，令 "upload/*"、"stream/*" 一律被當漏報「extra」。
"""
import pathlib, re, yaml, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
DISP = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "ApiDispatcher.java"
XZ = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "XiaozhiBridge.java"
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"

spec = yaml.safe_load(open(SPEC, encoding='utf-8'))
# spec paths 一律 "/api/..." 或 "/upload/..." / "/stream/..." 或 "/", "/blockly.html"；
# 剝走 "/api/" 前綴、"/upload"、"/stream" 前面嘅斜線，方便同 case name（冇 "api/" 前綴）比較。
spec_norm = set()
for p in spec['paths'].keys():
    q = p.lstrip('/')
    if q.startswith('api/'):
        spec_norm.add(q[len('api/'):])
    elif q.startswith('upload/') or q.startswith('stream/'):
        spec_norm.add(q)
    # "" (根 "/") 同 "blockly.html" 冇對應 case，跳過。


def extract_method_body(text: str, method_name: str) -> str:
    """搵 `<method_name>(...)  {` 到下一個同縮排 public/private 方法或檔案尾嘅內容。"""
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

# 逐個 handler 分開抽取，各自加返 spec 對應嘅 namespace 前綴：
# - handleApi: case name 已經同 spec "api/*" 剝皮後一致，冇前綴。
# - handleSystemApi: spec 係 "api/system/*"，呢度 case 冇 "system/"，要補。
# - handleDirectApi: spec 係 "api/direct/*"，呢度 case 冇 "direct/"，要補。
# - handleXiaozhiApi: spec 係 "api/xiaozhi/*"，呢度 case 冇 "xiaozhi/"，要補。
handlers = [
    ("handleApi", disp_text, ""),
    ("handleSystemApi", disp_text, "system/"),
    ("handleDirectApi", disp_text, "direct/"),
    ("handleXiaozhiApi", xz_text, "xiaozhi/"),
]

# 少數 handleApi 內嘅 case 冇 "/"（例如 "status"），要人手白名單先當 API path，
# 其餘冇 "/" 嘅 case（preset 值好似 "flash"/"breathe" 等）唔算。呢個白名單
# 淨係用喺 handleApi（prefix==""）：其餘三個 handler 嘅 case 一經補正確
# namespace 前綴（"system/"、"direct/"、"xiaozhi/"）已經天然帶 "/"，唔應該
# 再套用呢個為 handleApi 度身訂造嘅白名單（曾經令 "discover"、"connect" 等
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
        # 有 prefix 嘅 handler：淨係濾走真係唔係 path 嘅裸值（例如某啲 case
        # 本身係參數枚舉，唔係 endpoint）——呢類 handler 底下暫時未見過，
        # 保留全部裸名，加咗 prefix 之後一律當 path 睇待，交畀後面同 spec
        # 比對嘅步驟決定是否漂移。
        kept = raw
    prefixed = set(prefix + c for c in kept)
    per_handler_cases[name] = prefixed
    api_cases |= prefixed

# handleUpload/handleStream 喺 MainActivity 用 "xxx".equals(path) 嘅 if/else
# 鏈做路由 (唔係 switch case)，同上面四個 handler 結構唔同，要獨立抽取。
# spec 入面呢兩組 path 係 "upload/audio"、"stream/camera" 呢種扁平命名
# (冇 "api/" 前綴)，對應 MainActivity 呢兩個 method 入面比較嘅字面值。
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
