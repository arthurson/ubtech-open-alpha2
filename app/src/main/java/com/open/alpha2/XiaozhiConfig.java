package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 小智設定層：OTA 自架 server / MCP 開關 / TTS 引擎 / 自動連接。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第五刀)：8 個
 * handleXiaozhiApi case (ota_config/get|set、mcp_config/get|set、
 * tts_config/get|set、auto_connect/get|set) + prefs key + 細 helper，
 * 邏輯一字不改搬過嚟。留低喺 MainActivity 的繼續經呢度讀寫：
 * activation 流程 / vision (OTA 設定)、MCP bridge (開關判斷)、
 * TTS 隊列 (引擎選擇)、開機 auto-connect。
 */
public final class XiaozhiConfig {
    private static final String TAG = "XiaozhiConfig";

    /** 自訂小智 server 設定 - 開關開了才用 PREF_XIAOZHI_OTA_URL, 關了就跟回
     *  XiaozhiOtaClient.DEFAULT_OTA_URL (官方 api.tenclass.net)。見
     *  handleXiaozhiApi() 的 "ota_config/get"/"ota_config/set" case 和
     *  runXiaozhiActivationFlow() 怎麼讀這個設定。 */
    public static final String PREF_XIAOZHI_OTA_CUSTOM_ENABLED = "xiaozhi_ota_custom_enabled";
    public static final String PREF_XIAOZHI_OTA_URL = "xiaozhi_ota_url";
    // 2026-08 新增: 自架 server 未必跟足官方協議形狀 (OTA response 夾著
    // websocket url/token 一起送回來) - 有些自架方案要用戶自己手動填這幾樣東西。
    // 全部留空 = 跟回自動流程 (由 OTA response 拿); 有填就用來覆寫對應的自動值。
    // 只有在 PREF_XIAOZHI_OTA_CUSTOM_ENABLED 開了的時候才讀這幾個, 和 OTA URL
    // 本身一起收在同一個「自訂小智 server」開關底下。
    public static final String PREF_XIAOZHI_WS_URL_OVERRIDE = "xiaozhi_ws_url_override";
    public static final String PREF_XIAOZHI_DEVICE_ID_OVERRIDE = "xiaozhi_device_id_override";
    public static final String PREF_XIAOZHI_TOKEN_OVERRIDE = "xiaozhi_token_override";
    // 2026-08 新增: MCP tool 個別 enable/disable 設定。總開關預設 true (保持現有
    // 行為 - 已經在用的人不應該因為這個功能上線而工具突然全部消失)。
    // disabled tool 清單預設空 (也就是全部 enabled), 用逗號分隔的 tool name 儲存
    // 在同一個 SharedPreferences, 用 name 不用 index 是因為 tool 清單本身會隨版本
    // 增減, index 會漂移, name 才是穩定的 identity。
    public static final String PREF_XIAOZHI_MCP_ENABLED = "xiaozhi_mcp_enabled";
    public static final String PREF_XIAOZHI_MCP_DISABLED_TOOLS = "xiaozhi_mcp_disabled_tools";
    // 見 getTtsEngine() 的 javadoc。
    public static final String PREF_XIAOZHI_TTS_ENGINE = "xiaozhi_tts_engine";
    /** 開app自動連接小智（小智tab開關，預設關；見 auto_connect/get|set）。 */
    public static final String PREF_XIAOZHI_AUTO_CONNECT = "xiaozhi_auto_connect";

    /** OTA 自架設定快照 (activation / vision 讀一次攞齊，唔使逐個 key 查)。 */
    public static final class OtaConfig {
        public final boolean customEnabled;
        public final String otaUrl;
        public final String wsUrl;
        public final String deviceId;
        public final String token;
        OtaConfig(boolean customEnabled, String otaUrl, String wsUrl, String deviceId, String token) {
            this.customEnabled = customEnabled;
            this.otaUrl = otaUrl;
            this.wsUrl = wsUrl;
            this.deviceId = deviceId;
            this.token = token;
        }
    }

    private final Context appContext;
    // 小智 tab 的 TTS 輸出引擎選擇 - "xiaozhi" (預設) 也就是維持原本行為 (server
    // 送 opus 過來, XiaozhiAudioController 解碼播放); 選 "android" 就完全靜音
    // 那段 opus, 改用本地 speech/tts (Android 內置) 逐句讀出小智回覆。
    // 用 SharedPreferences 持久化, 跨重啟記得住選了哪個。
    // 2026-09: 舊值 "iflytek"/"nuance" 已移除 (機身已無此兩引擎, 選中只會靜音) -
    // 開機讀到舊值會遷移到 "xiaozhi", tts_config/set 會直接拒收舊值。
    private volatile String xiaozhiTtsEngine = "xiaozhi";

    public XiaozhiConfig(Context context) {
        this.appContext = context.getApplicationContext();
        // 讀取上次選定的 TTS 引擎, 如果沒有存過就用預設值 "xiaozhi" (原本行為,
        // 不靜音)。舊版本存落的 "iflytek"/"nuance" 已無對應引擎, 一律遷移到
        // "xiaozhi" 並寫返落去, 唔係隊列會經 speech/tts 打去死 binder 全程靜音。
        xiaozhiTtsEngine = prefs().getString(PREF_XIAOZHI_TTS_ENGINE, "xiaozhi");
        if (!"xiaozhi".equals(xiaozhiTtsEngine) && !"android".equals(xiaozhiTtsEngine)) {
            Log.i(TAG, "migrate legacy xiaozhiTtsEngine " + xiaozhiTtsEngine + " -> xiaozhi");
            xiaozhiTtsEngine = "xiaozhi";
            prefs().edit().putString(PREF_XIAOZHI_TTS_ENGINE, "xiaozhi").apply();
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getTtsEngine() {
        return xiaozhiTtsEngine;
    }

    public OtaConfig getOtaConfig() {
        SharedPreferences p = prefs();
        boolean customEnabled = p.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        return new OtaConfig(
                customEnabled,
                customEnabled ? p.getString(PREF_XIAOZHI_OTA_URL, XiaozhiOtaClient.DEFAULT_OTA_URL)
                        : XiaozhiOtaClient.DEFAULT_OTA_URL,
                customEnabled ? p.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "") : "",
                customEnabled ? p.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "") : "",
                customEnabled ? p.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "") : "");
    }

    public boolean isOtaCustomEnabled() {
        try {
            return prefs().getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAutoConnectEnabled() {
        try {
            return prefs().getBoolean(PREF_XIAOZHI_AUTO_CONNECT, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** MCP tool enable/disable 設定的讀寫 helper - 逗號分隔的 disabled tool name
     *  清單。isMcpToolEnabled() 供 listTools()/callTool() 共用: listTools() 用來
     *  過濾哪些 tool 出現在回應中, callTool() 用來擋下一個已經 disabled 但 LLM
     *  手上還持有舊 tool 清單、嘗試照樣呼叫的情況。
     *  2026-08 更新: UI 側移除了「開放 MCP 工具給小智使用」總開關 - 這台機器現在
     *  永遠對外暴露 MCP 工具 (逐項 enable/disable 不變), isMcpEnabled() 恆常
     *  回傳 true。PREF_XIAOZHI_MCP_ENABLED 這個 pref key 保留在常數和
     *  mcp_config/set 的寫入路徑裡沒有拆掉, 純粹是為了相容舊有經由 query string
     *  直接打 API 的呼叫方式, 但不會再影響實際行為。 */
    public boolean isMcpEnabled() {
        return true;
    }

    public Set<String> getMcpDisabledToolNames() {
        String csv = prefs().getString(PREF_XIAOZHI_MCP_DISABLED_TOOLS, "");
        Set<String> disabled = new HashSet<>();
        if (!csv.isEmpty()) {
            for (String name : csv.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) disabled.add(trimmed);
            }
        }
        return disabled;
    }

    public boolean isMcpToolEnabled(String toolName, Set<String> disabledNames) {
        return isMcpEnabled() && !disabledNames.contains(toolName);
    }

    public static boolean isMacShaped(String s) {
        return s != null && s.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$");
    }

    public HttpServer.ApiResponse otaConfigGet() {
        SharedPreferences p = prefs();
        boolean customEnabled = p.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        String customUrl = p.getString(PREF_XIAOZHI_OTA_URL, "");
        String wsUrlOverride = p.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "");
        String deviceIdOverride = p.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "");
        String tokenOverride = p.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "");
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"customEnabled\":" + customEnabled + ","
                + "\"customUrl\":\"" + MainActivity.jsonSafe(customUrl) + "\","
                + "\"defaultUrl\":\"" + MainActivity.jsonSafe(XiaozhiOtaClient.DEFAULT_OTA_URL) + "\","
                + "\"wsUrlOverride\":\"" + MainActivity.jsonSafe(wsUrlOverride) + "\","
                + "\"deviceIdOverride\":\"" + MainActivity.jsonSafe(deviceIdOverride) + "\","
                + "\"tokenOverride\":\"" + MainActivity.jsonSafe(tokenOverride) + "\"}");
    }

    public HttpServer.ApiResponse otaConfigSet(Map<String, String> query, boolean connected) {
        // 2026-08 修正: 之前這裡的 comment 說「主流自架 server 只需要 OTA
        // URL, websocket url/token 由 OTA response 一併送回, 不開放獨立
        // 欄位」- 但實測發現不是所有自架方案都能依照這個協議形狀傳回足夠資訊,
        // 用戶手上的 server 需要手動填寫 websocket 地址、MAC/Device-Id、
        // token 才連得上。現在這三個都開放做可選 override: 留空就繼續走
        // 原本「只有 OTA URL, 其餘自動」那條路; 有填就用來覆蓋
        // runXiaozhiActivationFlow() 裡對應的自動值 (見該處 comment)。
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        String url = ApiValidator.optionalNullable(query, "url");
        String wsUrlOverride = ApiValidator.optionalNullable(query, "wsUrl");
        String deviceIdOverride = ApiValidator.optionalNullable(query, "deviceId");
        String tokenOverride = ApiValidator.optionalNullable(query, "token");
        if (enabled) {
            if (url == null) {
                throw new IllegalArgumentException("url is required when enabled=true");
            }
            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("url must start with http:// or https://");
            }
            if (wsUrlOverride != null) {
                String trimmed = wsUrlOverride.trim();
                if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
                    throw new IllegalArgumentException("wsUrl must start with ws:// or wss://");
                }
            }
            if (deviceIdOverride != null && !isMacShaped(deviceIdOverride.trim())) {
                throw new IllegalArgumentException(
                        "deviceId must look like a MAC address, e.g. aa:bb:cc:dd:ee:ff");
            }
            if (connected) {
                return HttpServer.ApiResponse.error("disconnect from XiaoZhi first before changing the server");
            }
        }
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, enabled);
        if (url != null) editor.putString(PREF_XIAOZHI_OTA_URL, url);
        if (wsUrlOverride != null) editor.putString(PREF_XIAOZHI_WS_URL_OVERRIDE, wsUrlOverride.trim());
        if (deviceIdOverride != null) editor.putString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, deviceIdOverride.trim());
        if (tokenOverride != null) editor.putString(PREF_XIAOZHI_TOKEN_OVERRIDE, tokenOverride.trim());
        editor.apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse mcpConfigGet() {
        Set<String> disabledNames = getMcpDisabledToolNames();
        org.json.JSONArray disabledArr = new org.json.JSONArray();
        for (String n : disabledNames) disabledArr.put(n);
        try {
            org.json.JSONObject result = new org.json.JSONObject();
            result.put("ok", true);
            result.put("mcpEnabled", isMcpEnabled());
            result.put("disabledTools", disabledArr);
            return HttpServer.ApiResponse.ok(result.toString());
        } catch (org.json.JSONException e) {
            return HttpServer.ApiResponse.error("failed to build config: " + e.getMessage());
        }
    }

    public HttpServer.ApiResponse mcpConfigSet(Map<String, String> query) {
        // 兩種用法, 依 query 帶的參數而定:
        //   ?enabled=true|false                  -> 設總開關
        //   ?tool=<name>&enabled=true|false       -> 設單一 tool
        String toolName = ApiValidator.optionalNullable(query, "tool");
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        SharedPreferences.Editor mcpEditor = prefs().edit();
        if (toolName == null || toolName.isEmpty()) {
            mcpEditor.putBoolean(PREF_XIAOZHI_MCP_ENABLED, enabled);
        } else {
            Set<String> disabledNames = getMcpDisabledToolNames();
            if (enabled) {
                disabledNames.remove(toolName);
            } else {
                disabledNames.add(toolName);
            }
            mcpEditor.putString(PREF_XIAOZHI_MCP_DISABLED_TOOLS,
                    TextUtils.join(",", disabledNames));
        }
        mcpEditor.apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 見 getTtsEngine() 的 javadoc。engine 值: "xiaozhi" (預設,
    // server 送 opus 播放) | "android" (靜音 opus, 改用本地 Android TTS
    // 讀出)。2026-09: "iflytek"/"nuance" 已移除, 直接拒收。
    public HttpServer.ApiResponse ttsConfigGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\""
                + MainActivity.jsonSafe(xiaozhiTtsEngine) + "\"}");
    }

    public HttpServer.ApiResponse ttsConfigSet(Map<String, String> query) {
        String engine = ApiValidator.requireXiaozhiTtsEngine(query);
        xiaozhiTtsEngine = engine;
        prefs().edit().putString(PREF_XIAOZHI_TTS_ENGINE, engine).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\"" + MainActivity.jsonSafe(engine) + "\"}");
    }

    public HttpServer.ApiResponse autoConnectGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + isAutoConnectEnabled() + "}");
    }

    public HttpServer.ApiResponse autoConnectSet(Map<String, String> query) {
        boolean autoConn = ApiValidator.requireBoolean(query, "enabled");
        prefs().edit().putBoolean(PREF_XIAOZHI_AUTO_CONNECT, autoConn).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + autoConn + "}");
    }
}
