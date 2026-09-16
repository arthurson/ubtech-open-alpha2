package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 實驗 tab 面板 token 認證（opt-in，預設關閉＝全開，保持舊行為）。
 *
 * 範圍（2026-09-15 起：成個面板上鎖，不再只閘實驗 tab 高危寫）：
 * - 保護：全部 {@code /api/*}（alpha2／direct／system／xiaozhi——含讀操作、
 *   其他 tab、Blockly 用嗰啲 endpoint）同全部 {@code /upload/*}（chest／music／
 *   audio 上載）。全部經 {@link #isOpenApi}／handleUpload 由 MainActivity
 *   中央閘口統一檢查。
 * - 開放：{@code system/auth/*}（status／set／clear／verify，解鎖入口，唔係鎖咗
 *   無從解）、靜態頁（index.html／JS／CSS／spec——瀏覽器載入時帶唔到 token，
 *   閘咗連解鎖頁都開唔到）、{@code /ws}（事件推送）、{@code /stream/*}
 *   （影音流）——同 README 警告一致。
 *
 * 儲存：同一個 {@code robotpanel} SharedPreferences（同 xiaozhi token override 同一做法），
 * key {@link #PREF_PANEL_TOKEN}，空字串＝未啟用。格式 {@code [A-Za-z0-9-_]{8,64}}（URL-safe，
 * 手打都得；前端「隨機」掣會產生 32 hex）。前端記喺 localStorage（同一個 browser＋
 * 同一個面板地址跨 tab 共用，見 app-core.js panelTokenGet）。
 *
 * 傳遞：query {@code panel_token}（主，前端自動帶；同 xiaozhi ota {@code token} 撞名所以另起 key，
 * 見 app-core.js withPanelToken），受保護 endpoint 另收 {@code token} 別名方便 curl 手打。
 * 兩者都經 HttpServer.redactQuery 脫敏（panel_token 含 "token"，current 另有規則），唔入 logcat。
 *
 * 改 token 防鎖死：啟用中再 set／clear 一律要 {@code current} 舊值，唔係同網段任何人都可以
 * 覆寫 token 踢走物主。verify/status 永遠開放（唔係無 token 解唔到鎖）。
 *
 * 零額外依賴（Android framework + JDK only）：比對用 MessageDigest.isEqual 常數時間，
 * 唔用任何第三方庫。
 */
public final class PanelAuth {
    private PanelAuth() {}

    /** SharedPreferences key（同 MainActivity.PREFS_NAME 同一個檔案）。 */
    public static final String PREF_PANEL_TOKEN = "panel_auth_token";

    /** 受保護 endpoint 認證 query key（前端自動帶，見 withPanelToken）。 */
    public static final String QUERY_KEY = "panel_token";
    /** 同上別名（curl 手打方便；受保護 endpoint＋auth/verify 先收）。 */
    public static final String QUERY_ALIAS = "token";
    /** 改 token／清 token 時驗舊值用嘅 query key（log 脫敏見 HttpServer.redactQuery）。 */
    public static final String QUERY_CURRENT = "current";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 未啟用（未設 token）即全開，唔閘。 */
    public static boolean isEnabled(Context ctx) {
        try {
            String s = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
            return s != null && !s.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * MainActivity.handle() 中央閘口用：path 係未剝 prefix 嗰個
     *（"alpha2/…"／"xiaozhi/…"／"system/…"；舊快取裸路徑 fallthrough 都認）。
     *
     * 成個面板上鎖：淨係 auth/* 開放（解鎖入口），其餘 /api/* 一律要經
     * requireAuth（未啟用即放行）。靜態頁／ws／stream 唔經 handle()，唔使列。
     */
    public static boolean isOpenApi(String path) {
        return "system/auth/status".equals(path) || "auth/status".equals(path)
                || "system/auth/set".equals(path) || "auth/set".equals(path)
                || "system/auth/clear".equals(path) || "auth/clear".equals(path)
                || "system/auth/verify".equals(path) || "auth/verify".equals(path);
    }

    /** 格式：8–64 字元 URL-safe（英數/-/_）。唔啱即拋 IllegalArgumentException → 400。 */
    public static void checkFormat(String token) {
        ApiValidator.checkPanelTokenFormat(token);
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    /** query 帶嘅候選值（panel_token 主，token 別名；前後空白唔計）。 */
    static String candidateFromQuery(Map<String, String> query) {
        if (query == null) return null;
        String v = query.get(QUERY_KEY);
        if (v == null || v.isEmpty()) v = query.get(QUERY_ALIAS);
        if (v == null || v.isEmpty()) return null;
        return v.trim();
    }

    /**
     * 受保護 endpoint 閘口：未啟用或對得上即回 null（放行），否則回 401 ApiResponse。
     * 缺 token 同錯 token 分兩句，方便前端分「未解鎖」同「解錯」。
     */
    public static HttpServer.ApiResponse requireAuth(Context ctx, Map<String, String> query) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored == null || stored.isEmpty()) return null;
        String cand = candidateFromQuery(query);
        if (cand == null) {
            return HttpServer.ApiResponse.unauthorized("panel token required");
        }
        if (!constantEquals(cand, stored)) {
            return HttpServer.ApiResponse.unauthorized("invalid panel token");
        }
        return null;
    }

    // -- system/auth/* endpoints（ApiDispatcher.handleSystemApi 轉調，永遠開放） --

    public static HttpServer.ApiResponse status(Context ctx) {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + isEnabled(ctx) + "}");
    }

    /**
     * 設定／更改 token：query {@code token}=新值（必填，格式見 checkFormat），
     * 已啟用時另要 {@code current}=舊值（防同網段人亂覆寫踢走物主）。
     * 未啟用時直接設；成功即啟用。回 {"ok":true,"enabled":true}。
     */
    public static HttpServer.ApiResponse set(Context ctx, Map<String, String> query) {
        String v = query != null ? query.get(QUERY_ALIAS) : null;
        checkFormat(v);
        String next = v.trim();
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored != null && !stored.isEmpty()) {
            String cur = query.get(QUERY_CURRENT);
            if (cur == null || !constantEquals(cur.trim(), stored)) {
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

    /**
     * 清除 token（停用認證）：未啟用即冪等回 ok；已啟用要 {@code current}=舊值。
     * 回 {"ok":true,"enabled":false}。
     */
    public static HttpServer.ApiResponse clear(Context ctx, Map<String, String> query) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        if (stored == null || stored.isEmpty()) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":false}");
        }
        String cur = query != null ? query.get(QUERY_CURRENT) : null;
        if (cur == null || !constantEquals(cur.trim(), stored)) {
            return HttpServer.ApiResponse.unauthorized("current panel token required to clear it");
        }
        try {
            prefs(ctx).edit().remove(PREF_PANEL_TOKEN).apply();
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("failed to clear panel token: " + e.getMessage());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":false}");
    }

    /**
     * 校驗候選值（前端「解鎖」掣用）：永遠回 200，結果喺 valid（唔用 401，
     * 等前端唔使靠 parse error 字串分 success/fail）。
     * 未啟用回 {"ok":true,"enabled":false,"valid":false}。
     */
    public static HttpServer.ApiResponse verify(Context ctx, Map<String, String> query) {
        String stored;
        try {
            stored = prefs(ctx).getString(PREF_PANEL_TOKEN, "");
        } catch (Exception e) {
            stored = "";
        }
        boolean enabled = stored != null && !stored.isEmpty();
        boolean valid = false;
        if (enabled) {
            String cand = candidateFromQuery(query);
            valid = cand != null && constantEquals(cand, stored);
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + ",\"valid\":" + valid + "}");
    }
}
