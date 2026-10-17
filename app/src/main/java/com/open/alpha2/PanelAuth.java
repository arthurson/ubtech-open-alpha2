package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 實驗 tab 面板 token 認證（opt-in，預設關閉＝全開，保持舊行為）。
 *
 * 範圍：整個面板上鎖，不再只閘實驗 tab 高危寫：
 * - 保護：全部 {@code /api/*}（alpha2／direct／system／xiaozhi——含讀操作、
 *   其他 tab、Blockly 用那些 endpoint）、全部 {@code /upload/*}（chest／music／
 *   audio 上載）、{@code /stream/*}（相機／咪串流）同 {@code /ws}（事件推送）。
 *   {@code /api/*}／{@code /upload/*} 經 {@link #isOpenApi}／handleUpload 由
 *   MainActivity 中央閘口統一檢查；{@code /stream/*} 經 handleStream 同一個
 *   requireAuth 檢查；{@code /ws} 升級前經 HttpServer WsAuthGate 同一個檢查。
 * - 開放：{@code system/auth/*}（status／set／clear／verify，解鎖入口，不是鎖了
 *   無從解）、靜態頁（index.html／JS／CSS／spec——瀏覽器載入時帶不到 token，
 *   閘了連解鎖頁都開不到）。
 *
 * 儲存：同一個 {@code robotpanel} SharedPreferences（同 xiaozhi token override 同一做法），
 * key {@link #PREF_PANEL_TOKEN}，空字串＝未啟用。格式 {@code [A-Za-z0-9-_]{8,64}}（URL-safe，
 * 手打都得；前端「隨機」按鈕會產生 32 hex）。前端記在 localStorage（同一個 browser＋
 * 同一個面板地址跨 tab 共用，見 app-core.js panelTokenGet）。
 *
 * 傳遞（優先次序）：{@code Authorization: Bearer <token>}（主，/api／upload 經
 * fetch automatically 帶，不入 URL／logcat；見 app-core.js panelAuthHeaders）；
 * query {@code panel_token}（fallback——{@code <img src=/stream/camera>} 同
 * {@code new WebSocket()} 設不到 header，只能夠帶 query，見 app-camera.js／
 * app-log.js）。{@code /api/*} 前端一律行 header，不再將 token 放 URL。
 * 舊 {@code ?token=} 別名已移除：同 xiaozhi {@code ota_config/set} 個業務
 * {@code token}（OTA token override）撞名，同一個 key 塞唔到兩粒值；
 * curl 請用 {@code ?panel_token=} 或 {@code -H "Authorization: Bearer ..."}。
 * 兩者都經 HttpServer 脫敏（query 見 redactQuery，header 永不落 log），不入 logcat。
 *
 * 改 token 防鎖死：啟用中再 set／clear 一律要舊值證明（query {@code current}
 * 或 Bearer header），不是同網段任何人都可以覆寫 token 踢走物主。
 * verify/status 永遠開放（不是無 token 解不到鎖）。
 *
 * 零額外依賴（Android framework + JDK only）：比對用 MessageDigest.isEqual 常數時間，
 * 不用任何第三方庫。
 */
public final class PanelAuth {
    private PanelAuth() {}

    /** SharedPreferences key（同 MainActivity.PREFS_NAME 同一個檔案）。 */
    public static final String PREF_PANEL_TOKEN = "panel_auth_token";

    /** 受保護 endpoint 認證 query key（只給設不到 header 的 img／WebSocket 用；/api 一律行 header）。 */
    public static final String QUERY_KEY = "panel_token";
    /**
     * 舊別名（已停用，留常數等舊註解／spec 對得上，不再驗收）。
     * 之前受保護 endpoint 收 {@code ?token=} 方便 curl 手打，但同 xiaozhi
     * ota_config/set 業務 {@code token} 撞名——現一律用 panel_token／Bearer。
     */
    public static final String QUERY_ALIAS = "token";
    /** 改 token／清 token 時驗舊值用的 query key（log 脫敏見 HttpServer.redactQuery）。 */
    public static final String QUERY_CURRENT = "current";
    /** Bearer header key（HttpServer 傳入的 headers map key 已轉小寫）。 */
    public static final String HEADER_AUTHORIZATION = "authorization";
    /** Bearer 前綴（大小寫不計，後面一格空格）。 */
    public static final String BEARER_PREFIX = "bearer ";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 未啟用（未設 token）即全開，不閘。 */
    public static boolean isEnabled(Context ctx) {
        try {
            String s = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
            return s != null && !s.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * MainActivity.handle() 中央閘口用：path 是未剝 prefix 那個
     *（"alpha2/…"／"xiaozhi/…"／"system/…"；舊快取裸路徑 fallthrough 都認）。
     *
     * 整個面板上鎖：僅 auth/* 開放（解鎖入口），其餘 /api/* 一律要經
     * requireAuth（未啟用即放行）。靜態頁不經 handle()，不用列；
     * ws／stream 各自經 WsAuthGate／handleStream 同一個 requireAuth 檢查。
     */
    public static boolean isOpenApi(String path) {
        return "system/auth/status".equals(path) || "auth/status".equals(path)
                || "system/auth/set".equals(path) || "auth/set".equals(path)
                || "system/auth/clear".equals(path) || "auth/clear".equals(path)
                || "system/auth/verify".equals(path) || "auth/verify".equals(path);
    }

    /** 格式：8–64 字元 URL-safe（英數/-/_）。不合即拋 IllegalArgumentException → 400。 */
    public static void checkFormat(String token) {
        ApiValidator.checkPanelTokenFormat(token);
    }

    static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Header 帶的 Bearer 候選值（大小寫不計 "Bearer " 前綴，前後空白不計）。
     * headers key 已由 HttpServer 轉小寫，這裡大小寫雙認防直接傳入。
     */
    static String bearerFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        String v = headers.get(HEADER_AUTHORIZATION);
        if (v == null) v = headers.get("Authorization");
        String t = trimOrNull(v);
        if (t == null) return null;
        if (t.length() > BEARER_PREFIX.length()
                && t.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return trimOrNull(t.substring(BEARER_PREFIX.length()));
        }
        return null;
    }

    /** query 帶的候選值（只收 panel_token；舊 token 別名已停用，見類註解）。 */
    static String candidateFromQuery(Map<String, String> query) {
        if (query == null) return null;
        return trimOrNull(query.get(QUERY_KEY));
    }

    /**
     * 受保護 endpoint 閘口：未啟用或對得上即回 null（放行），否則回 401 ApiResponse。
     * 缺 token 同錯 token 分兩句，方便前端分「未解鎖」同「解錯」。
     * Header 同 query 同時有但唔同值即 401（fail closed，防混淆）。
     */
    public static HttpServer.ApiResponse requireAuth(Context ctx, Map<String, String> query,
            Map<String, String> headers) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored == null || stored.isEmpty()) return null;
        String bearer = bearerFromHeaders(headers);
        String q = candidateFromQuery(query);
        if (bearer != null && q != null && !constantEquals(bearer, q)) {
            return HttpServer.ApiResponse.unauthorized("invalid panel token");
        }
        String cand = bearer != null ? bearer : q;
        if (cand == null) {
            return HttpServer.ApiResponse.unauthorized("panel token required");
        }
        if (!constantEquals(cand, stored)) {
            return HttpServer.ApiResponse.unauthorized("invalid panel token");
        }
        return null;
    }

    /** 舊簽名（query only）：stream／ws 以外的內部調用／單元測試用，header 視作無。 */
    public static HttpServer.ApiResponse requireAuth(Context ctx, Map<String, String> query) {
        return requireAuth(ctx, query, null);
    }

    /** 舊值證明（set／clear 用）：query current 或 Bearer header 對上即 true。 */
    static boolean currentMatches(String stored, Map<String, String> query,
            Map<String, String> headers) {
        if (stored == null || stored.isEmpty()) return false;
        String cur = query != null ? trimOrNull(query.get(QUERY_CURRENT)) : null;
        if (cur != null && constantEquals(cur, stored)) return true;
        String bearer = bearerFromHeaders(headers);
        return bearer != null && constantEquals(bearer, stored);
    }

    // -- system/auth/* endpoints（ApiDispatcher.handleSystemApi 轉調，永遠開放） --

    public static HttpServer.ApiResponse status(Context ctx) {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + isEnabled(ctx) + "}");
    }

    /**
     * 設定／更改 token：新值經 query {@code token}（舊前端／spec 沿用）或
     * {@code panel_token}（新，同閘口 key 一致）二選一，必填，格式見 checkFormat；
     * 已啟用時另要舊值證明（query {@code current} 或 Bearer header，防同網段人
     * 亂覆寫踢走物主）。未啟用時直接設；成功即啟用。回 {"ok":true,"enabled":true}。
     * headers 傳 null 即只驗 query（舊調用位兼容）。
     */
    public static HttpServer.ApiResponse set(Context ctx, Map<String, String> query,
            Map<String, String> headers) {
        String v = query != null ? query.get(QUERY_ALIAS) : null;
        if (v == null || v.isEmpty() || v.trim().isEmpty()) {
            v = query != null ? query.get(QUERY_KEY) : null;
        }
        checkFormat(v);
        String next = v.trim();
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored != null && !stored.isEmpty()) {
            if (!currentMatches(stored, query, headers)) {
                return HttpServer.ApiResponse.unauthorized("current panel token required to change it");
            }
        }
        try {
            prefs(ctx).edit().putString(PREF_PANEL_TOKEN, next).apply();
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("failed to store panel token: " + e.getMessage());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":true}");
    }

    /** 舊簽名（query only），轉調新簽名。 */
    public static HttpServer.ApiResponse set(Context ctx, Map<String, String> query) {
        return set(ctx, query, null);
    }

    /**
     * 清除 token（停用認證）：未啟用即冪等回 ok；已啟用要舊值證明
     *（query {@code current} 或 Bearer header）。
     * 回 {"ok":true,"enabled":false}。
     */
    public static HttpServer.ApiResponse clear(Context ctx, Map<String, String> query,
            Map<String, String> headers) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored == null || stored.isEmpty()) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":false}");
        }
        if (!currentMatches(stored, query, headers)) {
            return HttpServer.ApiResponse.unauthorized("current panel token required to clear it");
        }
        try {
            prefs(ctx).edit().remove(PREF_PANEL_TOKEN).apply();
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("failed to clear panel token: " + e.getMessage());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":false}");
    }

    /** 舊簽名（query only），轉調新簽名。 */
    public static HttpServer.ApiResponse clear(Context ctx, Map<String, String> query) {
        return clear(ctx, query, null);
    }

    /**
     * 校驗候選值（前端「解鎖」按鈕用）：永遠回 200，結果在 valid（不用 401，
     * 等前端不用靠 parse error 字串分 success/fail）。
     * 未啟用回 {"ok":true,"enabled":false,"valid":false}。
     * 候選來源：Bearer header 或 query panel_token（同 requireAuth）。
     */
    public static HttpServer.ApiResponse verify(Context ctx, Map<String, String> query,
            Map<String, String> headers) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        boolean enabled = stored != null && !stored.isEmpty();
        boolean valid = false;
        if (enabled) {
            String bearer = bearerFromHeaders(headers);
            String q = candidateFromQuery(query);
            String cand = bearer != null ? bearer : q;
            valid = cand != null && constantEquals(cand, stored);
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + ",\"valid\":" + valid + "}");
    }

    /** 舊簽名（query only），轉調新簽名。 */
    public static HttpServer.ApiResponse verify(Context ctx, Map<String, String> query) {
        return verify(ctx, query, null);
    }
}
