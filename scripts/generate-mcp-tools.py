#!/usr/bin/env python3
"""
Generate MCP tool definitions from OpenAPI spec + sync table (v2 全自動生成).

來源:
  openapi/open-alpha2-openapi.yml (參數約束: type/enum/minimum/maximum/default)
  openapi/mcp-openapi-sync.yml     (工具清單: description/alias/required/mcp_only)

輸出:
  app/src/main/java/com/open/alpha2/McpToolsGenerated.java
  - buildTools() 回傳 JSONArray，每個元素 {name, description, inputSchema}
  - XiaozhiBridge.xiaozhiMcpBridge().listTools() 直接調用，不再手寫 org.json
  - callTool() 分發邏輯維持手寫（fuzzy match / 硬件直驅無法由 spec 推導）

用法:
  python scripts/generate-mcp-tools.py           # 寫檔
  python scripts/generate-mcp-tools.py --check    # CI 用：比對已提交檔，drift 即 exit 1
"""
import argparse
import pathlib
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "openapi" / "open-alpha2-openapi.yml"
SYNC = ROOT / "openapi" / "mcp-openapi-sync.yml"
OUT = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2" / "McpToolsGenerated.java"

VALID_TYPES = {"string", "integer", "boolean", "number"}


def resolve_params(params, spec):
    out = []
    for p in params or []:
        if "$ref" in p:
            ref = p["$ref"]
            parts = ref.lstrip("#/").split("/")
            cur = spec
            for part in parts:
                cur = cur[part]
            out.append(cur)
        else:
            out.append(p)
    return out


def java_str(s: str) -> str:
    # Java 雙引號字串字面值 escaping（檔本身 UTF-8，中文原樣保留）
    return '"' + (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", '\\n" +\n        "')
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    ) + '"'


def java_literal(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if v.is_integer():
            return str(int(v))
        return repr(v)
    return java_str(str(v))


def load_spec_op(spec, path, method):
    ops = spec.get("paths", {}).get(path)
    if ops is None:
        raise SystemExit(f"❌ sync 指到不存在的 openapi path: {path}")
    op = ops.get(method.lower())
    if op is None:
        avail = [k for k in ops.keys() if isinstance(k, str)]
        raise SystemExit(f"❌ {path} 無 method {method}（spec 有: {avail}）")
    return op


def build_model():
    with open(SPEC, encoding="utf-8") as f:
        spec = yaml.safe_load(f)
    with open(SYNC, encoding="utf-8") as f:
        sync = yaml.safe_load(f)
    if str(sync.get("sync_version", "")).split(".")[0] != "2":
        raise SystemExit(f"❌ mcp-openapi-sync.yml sync_version 須為 2.x（自動生成用），現為 {sync.get('sync_version')}")
    mappings = sync.get("mappings", [])
    seen = set()
    tools = []
    for m in mappings:
        name = m["mcp"]
        if name in seen:
            raise SystemExit(f"❌ 重複 mcp 名: {name}")
        seen.add(name)
        openapi_path = m.get("openapi")
        method = (m.get("method") or "GET").lower()
        desc = (m.get("description") or "").strip()
        if not desc:
            raise SystemExit(f"❌ {name} 缺 description（LLM-facing 文案須收於 sync 檔）")
        props = []  # list of {key,type,desc,enum,minimum,maximum,default,required}
        required = []
        if openapi_path:
            op = load_spec_op(spec, openapi_path, method)
            spec_params = {str(p["name"]): p for p in resolve_params(op.get("parameters", []), spec)}
            whitelist = m.get("params", []) or []
            wl_openapi_names = set()
            for e in whitelist:
                mcp_n = e["mcp"]
                o_n = e.get("openapi", mcp_n)
                wl_openapi_names.add(o_n)
                if o_n not in spec_params:
                    raise SystemExit(f"❌ {name}: openapi 參數 '{o_n}' 不在 spec {openapi_path} 內（spec 改名？）")
                sp = spec_params[o_n]
                schema = sp.get("schema", {}) or {}
                spec_type = schema.get("type", "string")
                # type：sync 可覆寫，否則繼承 spec；兩者並存而不同即報錯
                sync_type = e.get("type")
                eff_type = sync_type or spec_type
                if sync_type and sync_type != spec_type:
                    raise SystemExit(
                        f"❌ {name}.{mcp_n}: sync type {sync_type} ≠ spec type {spec_type}（spec 改型別？先更新 sync）")
                if eff_type not in VALID_TYPES:
                    raise SystemExit(f"❌ {name}.{mcp_n}: 不支援 type {eff_type}")
                eff_desc = e.get("desc", sp.get("description", "") or "")
                # enum/minimum/maximum/default：sync 覆寫優先，否則繼承 spec
                eff_enum = e.get("enum", schema.get("enum"))
                eff_min = e.get("minimum", schema.get("minimum"))
                eff_max = e.get("maximum", schema.get("maximum"))
                eff_default = e.get("default", schema.get("default", None))
                is_req = bool(e.get("required", False))
                props.append({
                    "key": mcp_n, "type": eff_type,
                    "desc": str(eff_desc or ""),
                    "enum": list(eff_enum) if eff_enum is not None else None,
                    "minimum": eff_min, "maximum": eff_max, "default": eff_default,
                })
                if is_req:
                    required.append(mcp_n)
            # exclude 必須指到 spec 真有、且白名單無的參數（否則 stale）
            for ex in (m.get("exclude") or []):
                if ex not in spec_params:
                    raise SystemExit(f"❌ {name}: exclude '{ex}' 不在 spec {openapi_path} 內（已刪除？更新 sync）")
                if ex in wl_openapi_names:
                    raise SystemExit(f"❌ {name}: exclude '{ex}' 同時在 params 白名單內")
            # spec 有、白名單無、exclude 亦無 = 遺漏，報錯逼表態（加白名單或加 exclude）
            uncovered = [k for k in spec_params.keys()
                         if k not in wl_openapi_names and k not in set(m.get("exclude") or [])]
            if uncovered:
                raise SystemExit(
                    f"❌ {name}: spec {openapi_path} 新增參數 {uncovered} 未在 sync 表態（加 params 或 exclude）")
        else:
            # openapi:null：params 須全為 mcp_only 式（無 openapi 對應）或空
            for e in (m.get("params") or []):
                if "openapi" in e:
                    raise SystemExit(f"❌ {name}: openapi:null 但 params 列了 openapi 對應 {e}")
                mcp_n = e["mcp"]
                eff_type = e.get("type", "string")
                if eff_type not in VALID_TYPES:
                    raise SystemExit(f"❌ {name}.{mcp_n}: 不支援 type {eff_type}")
                props.append({
                    "key": mcp_n, "type": eff_type,
                    "desc": str(e.get("desc", "") or ""),
                    "enum": list(e["enum"]) if e.get("enum") is not None else None,
                    "minimum": e.get("minimum"), "maximum": e.get("maximum"),
                    "default": e.get("default", None),
                })
                if bool(e.get("required", False)):
                    required.append(mcp_n)
        # mcp_only：一律 MCP 獨有
        seen_keys = {p["key"] for p in props}
        for e in (m.get("mcp_only") or []):
            mcp_n = e["mcp"]
            if mcp_n in seen_keys:
                raise SystemExit(f"❌ {name}: mcp_only '{mcp_n}' 同 params 重名")
            seen_keys.add(mcp_n)
            eff_type = e.get("type", "string")
            if eff_type not in VALID_TYPES:
                raise SystemExit(f"❌ {name}.{mcp_n}: 不支援 type {eff_type}")
            props.append({
                "key": mcp_n, "type": eff_type,
                "desc": str(e.get("desc", "") or ""),
                "enum": list(e["enum"]) if e.get("enum") is not None else None,
                "minimum": e.get("minimum"), "maximum": e.get("maximum"),
                "default": e.get("default", None),
            })
            if bool(e.get("required", False)):
                required.append(mcp_n)
        tools.append({"name": name, "description": desc, "props": props, "required": required,
                      "openapi": openapi_path, "method": method.upper()})
    return tools


def render_java(tools) -> str:
    L = []
    L.append("package com.open.alpha2;")
    L.append("")
    L.append("import org.json.JSONArray;")
    L.append("import org.json.JSONException;")
    L.append("import org.json.JSONObject;")
    L.append("")
    L.append("/**")
    L.append(" * MCP tool 定義（全部自動生成，切勿手改）。")
    L.append(" *")
    L.append(" * 來源：openapi/open-alpha2-openapi.yml（參數約束）＋")
    L.append(" * openapi/mcp-openapi-sync.yml（工具清單/description/alias/required）。")
    L.append(" * 再生：python scripts/generate-mcp-tools.py")
    L.append(" *")
    L.append(" * 約定：")
    L.append(" * - inputSchema 參數約束（type/enum/minimum/maximum/default）繼承 spec，")
    L.append(" *   與 ApiValidator.java 同一套規則；spec 加參數而 sync 未表態會生成失敗")
    L.append(" *   （見 generator 的 uncovered 檢查），逼兩邊對齊。")
    L.append(" * - alias（time_ms→time、enabled→on、distance_cm→distance、speed_ms→speed）")
    L.append(" *   在此只暴露 MCP 名；callTool() 側讀 MCP 名（見 XiaozhiBridge）。")
    L.append(" * - LED color/brightness 在 MCP 為 optional（preset=stop 免填，callTool 執行")
    L.append(" *   required_if），與舊手寫行為一致；spec 側維持必填。")
    L.append(" */")
    L.append("public final class McpToolsGenerated {")
    L.append("    private McpToolsGenerated() {}")
    L.append("")
    L.append(f"    public static final int TOOL_COUNT = {len(tools)};")
    L.append("")
    L.append("    public static JSONArray buildTools() throws JSONException {")
    L.append("        JSONArray tools = new JSONArray();")
    for i, t in enumerate(tools):
        L.append("")
        src = f" ({t['openapi']} {t['method']})" if t["openapi"] else " (no HTTP mapping)"
        L.append(f"        // {i + 1}. {t['name']}{src}")
        L.append("        {")
        L.append(f"            JSONObject t = new JSONObject();")
        L.append(f"            t.put({java_str('name')}, {java_str(t['name'])});")
        L.append(f"            t.put({java_str('description')}, {java_str(t['description'])});")
        L.append(f"            JSONObject s = new JSONObject();")
        L.append(f"            s.put({java_str('type')}, {java_str('object')});")
        L.append(f"            JSONObject props = new JSONObject();")
        for p in t["props"]:
            L.append(f"            {{")
            L.append(f"                JSONObject p = new JSONObject();")
            L.append(f"                p.put({java_str('type')}, {java_str(p['type'])});")
            if p["desc"]:
                L.append(f"                p.put({java_str('description')}, {java_str(p['desc'])});")
            if p["enum"] is not None:
                # build: new JSONArray().put("a").put("b") / .put(1) for numbers
                chain = "new org.json.JSONArray()"
                for v in p["enum"]:
                    chain += f".put({java_literal(v)})"
                L.append(f"                p.put({java_str('enum')}, {chain});")
            if p["minimum"] is not None:
                L.append(f"                p.put({java_str('minimum')}, {java_literal(p['minimum'])});")
            if p["maximum"] is not None:
                L.append(f"                p.put({java_str('maximum')}, {java_literal(p['maximum'])});")
            if p["default"] is not None:
                L.append(f"                p.put({java_str('default')}, {java_literal(p['default'])});")
            L.append(f"                props.put({java_str(p['key'])}, p);")
            L.append(f"            }}")
        L.append(f"            s.put({java_str('properties')}, props);")
        if t["required"]:
            chain = "new org.json.JSONArray()"
            for r in t["required"]:
                chain += f".put({java_str(r)})"
            L.append(f"            s.put({java_str('required')}, {chain});")
        L.append(f"            t.put({java_str('inputSchema')}, s);")
        L.append(f"            tools.put(t);")
        L.append("        }")
    L.append("        // 2026-09-09：再生漏改 TOOL_COUNT 即靜默錯，runtime 斷言釘死。")
    L.append("        if (tools.length() != TOOL_COUNT) throw new IllegalStateException(")
    L.append("                \"TOOL_COUNT=\" + TOOL_COUNT + \" but built \" + tools.length());")
    L.append("        return tools;")
    L.append("    }")
    L.append("}")
    return "\n".join(L) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只比對，不寫檔（CI 用）")
    args = ap.parse_args()
    tools = build_model()
    text = render_java(tools)
    if args.check:
        if not OUT.exists():
            print(f"❌ 缺少生成檔 {OUT.relative_to(ROOT)}，先跑 python scripts/generate-mcp-tools.py")
            return 1
        cur = OUT.read_text(encoding="utf-8").replace("\r\n", "\n")
        if cur != text:
            import difflib
            print("❌ McpToolsGenerated.java drift —— 跑 python scripts/generate-mcp-tools.py 後再 commit：")
            for line in difflib.unified_diff(cur.splitlines(), text.splitlines(),
                                             "committed", "regenerated", lineterm=""):
                print(line)
            return 1
        print(f"✅ MCP tools 一致（{len(tools)} tools 全部由 openapi+sync 生成）")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"Wrote {OUT.relative_to(ROOT)} with {len(tools)} tools")
    return 0


if __name__ == "__main__":
    sys.exit(main())
