#!/usr/bin/env python3
"""
檢查 code ↔ spec 漂移：確保 MainActivity handleApi/handleSystemApi/handleXiaozhiApi 的 case 名稱皆在 openapi 中有對應。
用法: python scripts/check-openapi-drift.py
回傳 0=無漂移, 1=有漂移 (適合 CI)。
"""
import pathlib, re, yaml, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "MainActivity.java"

spec = yaml.safe_load(open(SPEC, encoding='utf-8'))
spec_paths = set(p.lstrip('/') for p in spec['paths'].keys())
# spec_api stripped for comparison: api/xiaozhi/mcp_config/get -> xiaozhi/mcp_config/get, api/servo/one -> servo/one
# But MainActivity's xiaozhi cases are without prefix (mcp_config/get). So we need two views:
spec_stripped_all = set()
for p in spec_paths:
    if p.startswith('api/'):
        spec_stripped_all.add(p[4:])
    elif p.startswith('upload/') or p.startswith('stream/'):
        spec_stripped_all.add(p)
# Also add bare system/xiaozhi variants: for spec's system/music/list, also consider music/list as bare? No, system is namespace, so keep as is.

text = MAIN.read_text(encoding='utf-8')
# Find all case "x": include those inside handleApi/handleSystemApi/handleXiaozhiApi
cases = set(re.findall(r'case "([^"]+)"', text))
# Filter to API-like: contains / or known singletons
api_cases = set(c for c in cases if '/' in c or c in ['status','supported','mic/start','mic/stop'])

# Build expected mapping: for xiaozhi namespace, spec has xiaozhi/mcp_config/get but code has mcp_config/get
# So we consider code's mcp_config/get should match spec's xiaozhi/mcp_config/get
# Create expanded spec set that includes both with and without prefix for comparison
expanded_spec = set(spec_stripped_all)
for s in list(spec_stripped_all):
    if s.startswith('xiaozhi/'):
        expanded_spec.add(s[len('xiaozhi/'):])
    if s.startswith('system/'):
        expanded_spec.add(s[len('system/'):])

missing = sorted(api_cases - expanded_spec)
# extra is spec with prefix stripped but not in code (excluding special system/xiaozhi prefix stripping already handled)
extra = sorted(expanded_spec - api_cases - set(['', 'ws', 'blockly.html']))
# Filter out known non-API cases like flash/breathe etc (those are preset values, not api paths) - but our filter already excludes them because they don't contain /
# However preset case values like "flash" would be in cases but not api_cases (no /), so already excluded

print(f"spec paths: {len(spec_stripped_all)}  code api_cases: {len(api_cases)}")
if missing:
    print("❌ missing in spec (code has, spec lacks):")
    for m in missing: print(f"  - {m}")
else:
    print("✅ no missing in spec")
if extra:
    # only show extra that are api-like and not just system/xiaozhi stripped duplicates
    # Deduplicate to original spec paths for readability
    print("ℹ extra in spec (spec has, code maybe lacks or namespace):")
    for e in extra[:20]: print(f"  - {e}")
else:
    print("✅ no extra")
if missing:
    sys.exit(1)
sys.exit(0)
