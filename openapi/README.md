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
- 內容：`Alpha2Api.servoOne({id,angle,time})` 等 132 個 typed wrapper，內建 `assertEnum` / `assertRange`（鏡像後端校驗）
- 路由：按 OpenAPI path 前綴自動揀 caller——`api()`（`/api/alpha2/*`）、`sysApi()`（`/api/system/*`）、`directApi()`（`/api/direct/*`）、`xiaozhiApi()`（`/api/xiaozhi/*`）；直接用 `api('system/...')` 會 404，`scripts/check-api-client-routes.py` 會擋
- 依賴：`app-core.js` 的 `api()`（`index.html` 緊接其後載入；`blockly.html` 用 `blockly-page.js` 提供嘅同名 helper，一樣要載喺 `api-client.js` 之前）

```js
// 舊
api("servo/one", {id:1, angle:90, time:1000})

// 新 (型別檢查 + 自動文件)
Alpha2Api.servoOne({id:1, angle:90, time:1000})
Alpha2Api.speechTts({text:"你好", engine:"iflytek"})
Alpha2Api.ledHeadSet({color:1, brightness:9, preset:"breathe"})
```

再生：

```bash
python scripts/generate-api-client.py
```

## 2+ MCP 對齊

`openapi/mcp-openapi-sync.yml` 記錄 23 個 MCP tool（`self.robot.*` / `self.sensors.*` / `self.camera.*` / `self.media.*`）共 22 條 OpenAPI 參數映射（`play_random_action` 無 HTTP 對應），確保 LLM 經 MCP 與 HTTP 直調走同一套 `ApiValidator` 規則。未來可由 openapi 自動生成 `xiaozhiMcpBridge()` 的 `inputSchema`。

## 驗證

```bash
python -c "import yaml; yaml.safe_load(open('openapi/open-alpha2-openapi.yml'))"
python scripts/generate-api-client.py  # 132 functions
```

License: GPL-3.0-only (https://github.com/arthurson/ubtech-open-alpha2)
