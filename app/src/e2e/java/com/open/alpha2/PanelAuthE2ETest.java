package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PanelAuth 行為測試 (零依賴，不用 JUnit)，用 in-memory 假 SharedPreferences＋假時鐘：
 *  1) 預設啟用：首次啟動自動產生 token；已有 token 保留；用戶停用後重啟唔會被重新啟用；
 *     寫入失敗唔 crash；啟用後匿名 auth/set 唔可以搶 token。
 *  2) token 猜測節流：按 IP、只計不同嘅錯誤 token、被鎖嘅 IP 連正確 token 都唔驗證、
 *     verify／set／clear 全部係猜測 oracle，舊 token 分頁輪詢唔會令用戶被鎖。
 */
public final class PanelAuthE2ETest {
    private static int passed = 0;
    private static int failed = 0;
    private static final long[] NOW = {5_000_000L};

    private PanelAuthE2ETest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<String, Object>();
        boolean commitOk = true;

        @Override public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> staged = new HashMap<String, Object>();
                final Set<String> removed = new HashSet<String>();
                @Override public Editor putString(String k, String v) { staged.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { staged.put(k, v); return this; }
                @Override public Editor remove(String k) { removed.add(k); return this; }
                @Override public boolean commit() { if (!commitOk) return false; apply(); return true; }
                @Override public void apply() { for (String r : removed) m.remove(r); m.putAll(staged); }
            };
        }
    }

    private static Context ctxOf(final FakePrefs p) {
        return new Context() {
            @Override public SharedPreferences getSharedPreferences(String n, int mode) { return p; }
        };
    }

    private static Map<String, String> hdr(String ip, String bearer) {
        Map<String, String> h = new HashMap<String, String>();
        if (ip != null) h.put(HttpServer.HDR_REMOTE_ADDR, ip);
        if (bearer != null) h.put("authorization", "Bearer " + bearer);
        return h;
    }

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static int st(HttpServer.ApiResponse r) { return r == null ? 0 : r.status; }

    public static void main(String[] args) {
        Pattern fmt = Pattern.compile("[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}");
        final int free = AuthThrottle.FREE_FAILURES;

        // ================= 1. 預設啟用 =================
        PanelAuth.throttle = new AuthThrottle(AuthThrottle.SYSTEM_CLOCK);
        FakePrefs p = new FakePrefs();
        Context c = ctxOf(p);
        check(!PanelAuth.isEnabled(c), "fresh: not enabled before init");
        PanelAuth.ensureInitialized(c);
        String tok = PanelAuth.currentTokenOrEmpty(c);
        check(fmt.matcher(tok).matches(), "fresh: token generated in XXXXX-XXXXX format (" + tok + ")");
        check(PanelAuth.isEnabled(c), "fresh: enabled");
        check(Boolean.TRUE.equals(p.m.get(PanelAuth.PREF_PANEL_AUTH_INIT)), "fresh: init flag set");
        check(st(PanelAuth.requireAuth(c, q(), new HashMap<String, String>())) == 401, "no credentials -> 401");
        check(PanelAuth.requireAuth(c, q(), hdr(null, tok)) == null, "correct bearer -> allowed");
        check(st(PanelAuth.requireAuth(c, q(), hdr(null, "WRONG-TOKEN"))) == 401, "wrong bearer -> 401");
        check(PanelAuth.requireAuth(c, q("panel_token", tok), new HashMap<String, String>()) == null, "?panel_token= -> allowed");
        check(!PanelAuth.isOpenApi("system/get_battery"), "normal api is gated");
        check(PanelAuth.isOpenApi("system/auth/status") && PanelAuth.isOpenApi("system/auth/verify"),
                "system/auth/* stays open for the unlock overlay");

        PanelAuth.ensureInitialized(c);
        check(tok.equals(PanelAuth.currentTokenOrEmpty(c)), "second ensureInitialized keeps the same token");

        HttpServer.ApiResponse hijack = PanelAuth.set(c, q("token", "HIJACKED-9999"), new HashMap<String, String>());
        check(st(hijack) == 401 && tok.equals(PanelAuth.currentTokenOrEmpty(c)), "anonymous auth/set cannot take over");
        HttpServer.ApiResponse okSet = PanelAuth.set(c, q("token", "MINE-ONLY-77", "current", tok), new HashMap<String, String>());
        check(st(okSet) == 200 && "MINE-ONLY-77".equals(PanelAuth.currentTokenOrEmpty(c)), "auth/set with the current token works");

        HttpServer.ApiResponse clr = PanelAuth.clear(c, q(), hdr(null, "MINE-ONLY-77"));
        check(st(clr) == 200 && !PanelAuth.isEnabled(c), "clear with the current token disables");
        PanelAuth.ensureInitialized(c);
        check(PanelAuth.currentTokenOrEmpty(c).isEmpty() && !PanelAuth.isEnabled(c), "'restart' does not silently re-enable");
        check(PanelAuth.requireAuth(c, q(), new HashMap<String, String>()) == null, "disabled -> open (the user's explicit choice)");

        FakePrefs p2 = new FakePrefs();
        p2.m.put(PanelAuth.PREF_PANEL_TOKEN, "OLD-USER-TOKEN");
        Context c2 = ctxOf(p2);
        PanelAuth.ensureInitialized(c2);
        check("OLD-USER-TOKEN".equals(PanelAuth.currentTokenOrEmpty(c2))
                && Boolean.TRUE.equals(p2.m.get(PanelAuth.PREF_PANEL_AUTH_INIT)), "existing user token is kept, flag set");

        FakePrefs p3 = new FakePrefs();
        p3.commitOk = false;
        Context c3 = ctxOf(p3);
        PanelAuth.ensureInitialized(c3);
        check(p3.m.isEmpty() && !PanelAuth.isEnabled(c3), "failed commit leaves state untouched, no crash");
        p3.commitOk = true;
        PanelAuth.ensureInitialized(c3);
        check(fmt.matcher(PanelAuth.currentTokenOrEmpty(c3)).matches(), "retry after a failed commit generates the token");

        FakePrefs p4 = new FakePrefs();
        Context c4 = ctxOf(p4);
        PanelAuth.ensureInitialized(c4);
        check(!PanelAuth.currentTokenOrEmpty(c4).equals(PanelAuth.currentTokenOrEmpty(c3)), "independent installs get different tokens");

        // ================= 2. token 猜測節流（假時鐘）=================
        PanelAuth.throttle = new AuthThrottle(new AuthThrottle.Clock() { @Override public long now() { return NOW[0]; } });
        FakePrefs pp = new FakePrefs();
        Context cc = ctxOf(pp);
        PanelAuth.ensureInitialized(cc);
        String T = PanelAuth.currentTokenOrEmpty(cc);
        String A = "10.0.0.1", B = "10.0.0.2";

        for (int i = 0; i < free; i++) {
            check(st(PanelAuth.requireAuth(cc, q(), hdr(A, "WRONG" + i))) == 401, "wrong #" + i + " -> 401");
        }
        check(PanelAuth.requireAuth(cc, q(), hdr(A, T)) == null, "still allowed with the right token inside the free allowance");
        for (int i = 0; i < free + 1; i++) PanelAuth.requireAuth(cc, q(), hdr(A, "BAD" + i));
        HttpServer.ApiResponse r = PanelAuth.requireAuth(cc, q(), hdr(A, T));
        check(st(r) == 429, "after the allowance the ip is locked: even the CORRECT token gets 429 (got " + st(r) + ")");
        check(r != null && r.headers != null && "5".equals(r.headers.get("Retry-After")), "429 carries Retry-After: 5");
        check(PanelAuth.requireAuth(cc, q(), hdr(B, T)) == null, "a different ip with the right token is unaffected");

        check(st(PanelAuth.verify(cc, q(), hdr(A, T))) == 429, "verify is blocked for a locked ip");
        HttpServer.ApiResponse s = PanelAuth.set(cc, q("token", "NEW-TOKEN-7777", "current", T), hdr(A, null));
        check(st(s) == 429 && T.equals(PanelAuth.currentTokenOrEmpty(cc)), "set is blocked for a locked ip, token unchanged");
        HttpServer.ApiResponse cl = PanelAuth.clear(cc, q("current", T), hdr(A, null));
        check(st(cl) == 429 && PanelAuth.isEnabled(cc), "clear is blocked for a locked ip, auth stays enabled");

        check(st(PanelAuth.requireAuth(cc, q(), hdr(A, null))) == 401, "a credential-less request is a plain 401 (not a guess)");
        for (int i = 0; i < 200; i++) PanelAuth.requireAuth(cc, q(), hdr("10.0.0.3", null));
        check(PanelAuth.requireAuth(cc, q(), hdr("10.0.0.3", T)) == null, "200 token-less requests never lock an ip");

        NOW[0] += 6000;
        check(PanelAuth.requireAuth(cc, q(), hdr(A, T)) == null, "after the lock expires the right token works again");
        for (int i = 0; i < free; i++) PanelAuth.requireAuth(cc, q(), hdr(A, "AGAIN" + i));
        check(PanelAuth.requireAuth(cc, q(), hdr(A, T)) == null, "success reset the counter (free allowance restored)");

        String C = "10.0.0.4";
        for (int i = 0; i < free + 1; i++) PanelAuth.verify(cc, q("panel_token", "V" + i), hdr(C, null));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(C, T))) == 429, "wrong tokens sent to verify count towards the lock");
        String D = "10.0.0.5";
        for (int i = 0; i < free + 1; i++) PanelAuth.set(cc, q("token", "SOME-NEW-TOKEN", "current", "X" + i), hdr(D, null));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(D, T))) == 429, "wrong 'current' sent to set counts towards the lock");
        String E = "10.0.0.6";
        for (int i = 0; i < free + 1; i++) PanelAuth.clear(cc, q("current", "Y" + i), hdr(E, null));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(E, T))) == 429, "wrong 'current' sent to clear counts towards the lock");
        check(T.equals(PanelAuth.currentTokenOrEmpty(cc)), "the token never changed during all of the above");

        for (int i = 0; i < free + 1; i++) PanelAuth.requireAuth(cc, q(), hdr(null, "NOIP" + i));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(null, T))) == 429, "requests without an ip header share one throttled bucket");

        // 真實場景：舊 token 留喺瀏覽器，開頁時並行狂發 API（全部同一個舊 token），之後輸入新 token 解鎖
        String F = "10.0.0.9";
        for (int i = 0; i < 500; i++) PanelAuth.requireAuth(cc, q(), hdr(F, "OLD-STALE-TOKEN"));
        PanelAuth.verify(cc, q(), hdr(F, "OLD-STALE-TOKEN"));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(F, "OLD-STALE-TOKEN"))) == 401, "stale token spam still just gets 401 (never 429)");
        HttpServer.ApiResponse unlock = PanelAuth.set(cc, q("token", T, "current", T), hdr(F, null));
        check(st(unlock) == 200, "after 500 stale-token requests the user can still unlock with the real token (got " + st(unlock) + ")");
        check(PanelAuth.requireAuth(cc, q(), hdr(F, T)) == null, "and the real token then works");
        for (int i = 0; i < free + 1; i++) PanelAuth.requireAuth(cc, q(), hdr(F, "RANDOM-GUESS-" + i));
        check(st(PanelAuth.requireAuth(cc, q(), hdr(F, T))) == 429, "distinct guesses from the same ip are still throttled");

        System.out.println("PanelAuthE2ETest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
