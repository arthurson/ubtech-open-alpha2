# OpenAPI 1+2 simplification

> Language: English (this file) | [繁體中文](README_zh-Hant.md)

This directory is the single source of truth for `open-alpha2`'s OpenAPI,
implementing the lightweight "1+2" simplification:

## 1. Backend lightweight validation layer (zero dependencies)

`app/src/main/java/com/open/alpha2/ApiValidator.java`

- Wraps `require()` / `queryOrDefault()` / `Integer.parseInt()` / `enum` / `range` checks
- Mirrors the `parameters` definitions in `openapi/open-alpha2-openapi.yml`
- Example: old `Byte.parseByte(require(query,"id"))` → new `ApiValidator.requireIntRange(query,"id",1,22)`
- Throws `IllegalArgumentException`, converted by the outer layer into `{ok:false,error:...}` — behaviour unchanged

Usage: every `case` in `MainActivity.java`'s `handleApi()` / `handleSystemApi()` /
`handleDirectApi()` / `handleXiaozhiApi()` (plus `handleUpload()` / `handleStream()`)
must read parameters via `ApiValidator.*` — no changes to `HttpServer` needed
(`IllegalArgumentException` is converted to `400 {ok:false,error}` by the outer layer).

## 2. Frontend typed JS client (auto-generated)

- Source: `openapi/open-alpha2-openapi.yml`
- Generator: `scripts/generate-api-client.py` → `app/src/main/assets/web/api-client.js`
- Contents: 199 typed wrappers like `Alpha2Api.servoOne({id,angle,time})` with built-in
  `assertEnum` / `assertRange` (mirroring the backend validation)
- Routing: the caller is picked automatically from the OpenAPI path prefix — `api()`
  (`/api/alpha2/*`), `sysApi()` (`/api/system/*`), `directApi()` (`/api/direct/*`),
  `xiaozhiApi()` (`/api/xiaozhi/*`); calling `api('system/...')` directly 404s and
  `scripts/check-api-client-routes.py` blocks it
- Dependency: `api()` from `app-core.js` (loaded immediately before in `index.html`;
  `blockly.html` uses the same-named helper from `blockly-page.js`, which must likewise
  load before `api-client.js`)

```js
// old
api("servo/one", {id:1, angle:90, time:1000})

// new (type checks + self-documenting)
Alpha2Api.servoOne({id:1, angle:90, time:1000})
Alpha2Api.speechTts({text:"hello", engine:"android"})
Alpha2Api.ledHeadSet({color:1, brightness:9, preset:"breathe"})
```

Regenerate:

```bash
python scripts/generate-api-client.py
```

## 2+ MCP alignment (all auto-generated)

`openapi/mcp-openapi-sync.yml` (v2) is the MCP declaration source: the `description`,
aliases (`time_ms→time`, `enabled→on`, `distance_cm→distance`, `speed_ms→speed`),
MCP-side `required`, MCP-only parameters (`take_photo.question`, `image_to_text.uuid`)
and deliberately unexposed spec parameters (`exclude`: servo `trim`, speech
`engine/voice/lang`) for all 22 tools (`self.robot.*` / `self.sensors.*` /
`self.camera.*` / `self.media.*`). Parameter constraints
(`type/enum/minimum/maximum/default`) are always inherited from the matching path in
`open-alpha2-openapi.yml` — the same rules as `ApiValidator`.

```bash
python scripts/generate-mcp-tools.py          # writes app/.../McpToolsGenerated.java
python scripts/generate-mcp-tools.py --check  # for CI: drift fails the build
```

`XiaozhiBridge.xiaozhiMcpBridge().listTools()` only calls
`McpToolsGenerated.buildTools()` and then applies the enable/disable filter — no more
hand-written `inputSchema`; `callTool()` dispatch stays hand-written (fuzzy match /
hardware-direct calls cannot be derived from the spec).
A spec parameter the sync file is silent about (neither whitelisted nor `exclude`d)
fails generation, forcing both sides to stay aligned.

## Verify

```bash
python -c "import yaml; yaml.safe_load(open('openapi/open-alpha2-openapi.yml'))"
python scripts/generate-api-client.py  # 199 functions
```

License: GPL-3.0-only (https://github.com/arthurson/ubtech-open-alpha2)
