# OpenAPI 1+2 簡化方案

本目錄為 `open-alpha2` 的 OpenAPI 單一真相源，實作「1+2」輕量簡化：

## 1. 後端輕量校驗層 (零依賴)

`app/src/main/java/com/open/alpha2/ApiValidator.java`

- 封裝 `require()` / `queryOrDefault()` / `Integer.parseInt()` / `enum` / `range` 檢查
- 對應 `openapi/open-alpha2-openapi.yml` 的 `parameters` 定義
- 例：舊 `Byte.parseByte(require(query,"id"))` → 新 `ApiValidator.requireIntRange(query,"id",1,20)`
- 拋 `IllegalArgumentException` 交外層統一轉 `{ok:false,error:...}`，保持原有行為

用法：`MainActivity.java` 內 `handleApi()` / `handleSystemApi()` / `handleDirectApi()` / `handleXiaozhiApi()` 的全部 `case` 一律經 `ApiValidator.*` 取參，無需改 `HttpServer`（`IllegalArgumentException` 由外層統一轉 `400 {ok:false,error}`）。

## 2. 前端 Typed JS Client (自動生成)

- 源：`openapi/open-alpha2-openapi.yml`
- 生成器：`scripts/generate-api-client.py` → `app/src/main/assets/web/api-client.js`
- 內容：`Alpha2Api.servoOne({id,angle,time})` 等 152 個 typed wrapper，內建 `assertEnum` / `assertRange`（鏡像後端校驗）
- 路由：按 OpenAPI path 前綴自動揀 caller——`api()`（`/api/alpha2/*`）、`sysApi()`（`/api/system/*`）、`directApi()`（`/api/direct/*`）、`xiaozhiApi()`（`/api/xiaozhi/*`）；直接用 `api('system/...')` 會 404，`scripts/check-api-client-routes.py` 會擋
- 依賴：`app-core.js` 的 `api()`（`index.html` 緊接其後載入；`blockly.html` 用 `blockly-page.js` 提供嘅同名 helper，一樣要載喺 `api-client.js` 之前）

```js
// 舊
api("servo/one", {id:1, angle:90, time:1000})

// 新 (型別檢查 + 自動文件)
Alpha2Api.servoOne({id:1, angle:90, time:1000})
Alpha2Api.speechTts({text:"你好", engine:"android"})
Alpha2Api.ledHeadSet({color:1, brightness:9, preset:"breathe"})
```

再生：

```bash
python scripts/generate-api-client.py
```

## 2+ MCP 對齊（全部自動生成）

`openapi/mcp-openapi-sync.yml`（v2）係 MCP 聲明源：22 個 tool（`self.robot.*` /
`self.sensors.*` / `self.camera.*` / `self.media.*`）嘅 `description`、alias
（`time_ms→time`、`enabled→on`、`distance_cm→distance`、`speed_ms→speed`）、
MCP 版 `required`、MCP 獨有參數（`take_photo.question`、`image_to_text.uuid`）、
刻意唔暴露嘅 spec 參數（`exclude`：servo `trim`、speech `engine/voice/lang`）。
參數約束（`type/enum/minimum/maximum/default`）一律繼承
`open-alpha2-openapi.yml` 對應 path，與 `ApiValidator` 同一套規則。

```bash
python scripts/generate-mcp-tools.py          # 寫 app/.../McpToolsGenerated.java
python scripts/generate-mcp-tools.py --check  # CI 用：drift 即 fail
```

`XiaozhiBridge.xiaozhiMcpBridge().listTools()` 只調用
`McpToolsGenerated.buildTools()` 再做 enable/disable 過濾，不再手寫
`inputSchema`；`callTool()` 分發維持手寫（fuzzy match／硬件直驅無法由 spec 推導）。
spec 加參數而 sync 未表態（加白名單或加 `exclude`）會生成失敗，逼兩邊對齊。

## 驗證

```bash
python -c "import yaml; yaml.safe_load(open('openapi/open-alpha2-openapi.yml'))"
python scripts/generate-api-client.py  # 152 functions
```

License: GPL-3.0-only (https://github.com/arthurson/ubtech-open-alpha2)
