package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 面板 token 認證（預設啟用：首次啟動自動產生 token，見 {@link #ensureInitialized}；
 * 用戶主動清除＝停用）。
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

    /** 已做過「預設啟用」初始化（之後用戶主動清除 token＝停用，唔會再自動啟用）。 */
    public static final String PREF_PANEL_AUTH_INIT = "panel_auth_initialized";

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

    /** 錯誤 token 猜測節流（按來源 IP，見 AuthThrottle）。非 final：測試可換成假時鐘版本。 */
    static volatile AuthThrottle throttle = new AuthThrottle(AuthThrottle.SYSTEM_CLOCK);

    private static String ipOf(Map<String, String> headers) {
        String v = headers == null ? null : headers.get(HttpServer.HDR_REMOTE_ADDR);
        return v == null ? "" : v;
    }

    /** 該來源被鎖就回 429（唔驗證 token），否則 null。 */
    private static HttpServer.ApiResponse lockedResponse(Map<String, String> headers) {
        long ms = throttle.lockedForMs(ipOf(headers));
        return ms > 0 ? HttpServer.ApiResponse.tooManyRequests(ms) : null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 未啟用（未設 token）即全開，不閘。 */
    /**
     * 預設啟用：必須喺 HttpServer.start() 之前呼叫。第一次（從未初始化過）而且冇 token 就自動產生
     * 一個，由機身畫面顯示（見 MainActivity）；已有 token 保留；用戶之後主動清除＝停用，唔會被
     * 重新啟用。token 同旗標用同一個 commit 寫入，唔會出現「旗標已寫但 token 未寫」。
     * 回傳 true＝認證狀態可靠（已初始化，或今次成功持久化）；false＝寫入／讀取失敗，token 未能保證落盤，
     * 呼叫方<b>唔可以</b>開控制 server（否則空 token 會被 requireAuth 當成「未啟用」而全開）。
     */
    public static synchronized boolean ensureInitialized(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            if (p.getBoolean(PREF_PANEL_AUTH_INIT, false)) return true;
            String existing = p.getString(PREF_PANEL_TOKEN, "");
            SharedPreferences.Editor ed = p.edit();
            boolean generated = PanelToken.needsAutoEnable(false, existing);
            if (generated) {
                ed.putString(PREF_PANEL_TOKEN, PanelToken.generate(new java.security.SecureRandom()));
            }
            ed.putBoolean(PREF_PANEL_AUTH_INIT, true);
            if (!ed.commit()) {
                Log.w("PanelAuth", "ensureInitialized: commit failed, panel auth state unchanged");
                return false;
            }
            Log.i("PanelAuth", generated ? "panel token auto-generated (shown on device screen)"
                    : "panel token already set, kept");
            return true;
        } catch (Exception e) {
            Log.w("PanelAuth", "ensureInitialized failed: " + e.getMessage());
            return false;
        }
    }

    /** 機身畫面顯示用：而家嘅 token，冇（停用）或讀唔到回空字串。唔好 log。 */
    public static String currentTokenOrEmpty(Context ctx) {
        try {
            String s = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

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
            // 讀唔到 token 狀態：fail-closed。若當成「未啟用」會令已設 token 嘅面板喺 prefs 異常時失守。
            return HttpServer.ApiResponse.unauthorized("panel auth state unavailable");
        }
        if (stored == null || stored.isEmpty()) return null;
        String bearer = bearerFromHeaders(headers);
        String q = candidateFromQuery(query);
        String cand = bearer != null ? bearer : q;
        if (cand == null) {
            // 冇帶 token 唔係猜測：唔罰、唔查鎖（正常用戶未輸入 token 前嘅請求會落呢度）。
            return HttpServer.ApiResponse.unauthorized("panel token required");
        }
        // 被鎖嘅來源連正確 token 都唔驗證，否則攻擊者鎖住期間仍然可以繼續猜。
        HttpServer.ApiResponse locked = lockedResponse(headers);
        if (locked != null) return locked;
        String ip = ipOf(headers);
        if (bearer != null && q != null && !constantEquals(bearer, q)) {
            throttle.recordFailure(ip, bearer);
            throttle.recordFailure(ip, q);
            return HttpServer.ApiResponse.unauthorized("invalid panel token");
        }
        if (!constantEquals(cand, stored)) {
            throttle.recordFailure(ip, cand);
            return HttpServer.ApiResponse.unauthorized("invalid panel token");
        }
        throttle.recordSuccess(ip);
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
        String bearer = bearerFromHeaders(headers);
        if (cur == null && bearer == null) return false; // 冇帶任何 token：唔係猜測，唔罰
        boolean ok = (cur != null && constantEquals(cur, stored))
                || (bearer != null && constantEquals(bearer, stored));
        String ip = ipOf(headers);
        if (ok) {
            throttle.recordSuccess(ip);
        } else {
            if (cur != null) throttle.recordFailure(ip, cur);
            if (bearer != null) throttle.recordFailure(ip, bearer);
        }
        return ok;
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
            HttpServer.ApiResponse locked = lockedResponse(headers);
            if (locked != null) return locked;
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
        HttpServer.ApiResponse locked = lockedResponse(headers);
        if (locked != null) return locked;
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
            if (cand != null) {
                HttpServer.ApiResponse locked = lockedResponse(headers);
                if (locked != null) return locked;
                valid = constantEquals(cand, stored);
                if (valid) throttle.recordSuccess(ipOf(headers));
                else throttle.recordFailure(ipOf(headers), cand);
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + ",\"valid\":" + valid + "}");
    }

    /** 舊簽名（query only），轉調新簽名。 */
    public static HttpServer.ApiResponse verify(Context ctx, Map<String, String> query) {
        return verify(ctx, query, null);
    }
}
