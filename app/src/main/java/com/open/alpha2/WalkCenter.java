package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.GaitProgram;
import com.ubtechinc.alpha.hardware.ubx.GaitWalker;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * 「按住向前行／向後行」：步態來自 assets/gait/walk_fwd.json、walk_back.json（由
 * scripts/ubx_to_gait.py 從 open-1508999860568-8step-smooth6.ubx 生成；後行＝前行步態
 * 時間反轉），引擎見 {@link GaitWalker}。
 *
 * <p>方向：{@link #hold(float)} ＝向前；{@link #hold(float, boolean)} 可揀向後。行緊
 * 其中一個方向時要求相反方向，會先硬停（舵機保持末位姿）再由當前姿勢起步（起步第一格
 * 有限速，唔會急扯）。</p>
 *
 * <p>語義：按住（{@link #hold}）＝起步後循環行；放手（{@link #release}）＝由當前姿勢
 * 線性收返企定姿勢然後停；{@code stopNow}＝硬停（舵機保持末位姿，同 ubx/stop 一致）。
 * 速度 1.0–2.0 倍（1.0 ＝ .ubx 原速），行緊時即時生效。步態送出間隔有 300ms 實機下限
 * （{@link GaitWalker#WALK_MIN_UPDATE_MS}，快過會唔穩），所以 &gt;1.33x 會受下限保護、
 * 唔會再加快。</p>
 *
 * <p>安全：行路由租約守護——超過 {@link #LEASE_MS} 冇續期（{@link #hold} 再叫）即自動放手，
 * 所以網頁死機／key-up 遺失／藍牙斷線唔會一路行落去。普通動作播放經
 * {@code ubxPlayer.stop()} 搶佔，行路亦因此自動被截停。</p>
 */
public final class WalkCenter {
    private static final String TAG = "WalkCenter";
    public static final String ASSET = "gait/walk_fwd.json";
    public static final String ASSET_BACK = "gait/walk_back.json";
    /** 租約：本機按鍵／面板每 ~120–250ms 續期，超過就當放手。 */
    public static final long LEASE_MS = 700;
    public static final float DEFAULT_SPEED = 1.0f;

    private final Context appContext;
    private final UbxPlayer ubxPlayer;

    private volatile GaitProgram program;
    private volatile GaitProgram programBack;
    private volatile String loadError = "";
    private volatile String loadErrorBack = "";
    private volatile float baseSpeed = DEFAULT_SPEED;
    private volatile String lastError = "";

    public WalkCenter(Context context, UbxPlayer ubxPlayer) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
    }

    // -- 步態載入 ------------------------------------------------------------

    private synchronized GaitProgram program() {
        if (program != null) return program;
        try {
            program = parse(new JSONObject(readAsset(ASSET)));
            loadError = "";
        } catch (Exception e) {
            loadError = String.valueOf(e.getMessage());
            Log.w(TAG, "gait load failed", e);
        }
        return program;
    }

    private synchronized GaitProgram programBack() {
        if (programBack != null) return programBack;
        try {
            programBack = parse(new JSONObject(readAsset(ASSET_BACK)));
            loadErrorBack = "";
        } catch (Exception e) {
            loadErrorBack = String.valueOf(e.getMessage());
            Log.w(TAG, "gait(back) load failed", e);
        }
        return programBack;
    }

    private String readAsset(String name) throws Exception {
        InputStream in = appContext.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static GaitProgram parse(JSONObject o) throws Exception {
        JSONObject lead = o.getJSONObject("leadIn");
        JSONObject cyc = o.getJSONObject("cycle");
        JSONObject blend = o.getJSONObject("blend");
        JSONObject lim = o.getJSONObject("limits");
        int[][] lf = frames(lead.getJSONArray("frames"));
        int[][] cf = frames(cyc.getJSONArray("frames"));
        int[] home = pose(o.getJSONArray("home"));
        return new GaitProgram(lf, ints(lead.getJSONArray("ms")), cf, ints(cyc.getJSONArray("ms")),
                home, blend.getInt("msPerUnit"), blend.getInt("minMs"), blend.getInt("maxMs"),
                lim.getInt("entryMsPerUnit"), lim.getInt("minMsPerUnit"));
    }

    private static int[][] frames(JSONArray a) throws Exception {
        int[][] out = new int[a.length()][];
        for (int i = 0; i < out.length; i++) out[i] = pose(a.getJSONArray(i));
        return out;
    }

    /** 20 軸，逐軸 clamp 入校準範圍（防資料檔錯誤扯壞舵機）。 */
    private static int[] pose(JSONArray a) throws Exception {
        int[] p = new int[a.length()];
        for (int i = 0; i < p.length; i++) {
            p[i] = ApiValidator.clampServoAngle(i + 1, a.getInt(i));
        }
        return p;
    }

    private static int[] ints(JSONArray a) throws Exception {
        int[] p = new int[a.length()];
        for (int i = 0; i < p.length; i++) p[i] = a.getInt(i);
        return p;
    }

    // -- 控制 ----------------------------------------------------------------

    public String lastError() { return lastError; }

    public float baseSpeed() { return baseSpeed; }

    /** 預設（未受搖桿調節）速度，1.0–2.0。 */
    public void setBaseSpeed(float s) {
        baseSpeed = GaitWalker.clampSpeed(s);
    }

    /**
     * 按住（向前）：未行就起步，行緊就續租約＋更新速度。
     * @return true＝行緊（或已起步）；false＝起唔到（lastError 有原因）
     */
    public boolean hold(float speed) {
        return hold(speed, false);
    }

    /**
     * 按住：未行就起步，行緊同方向就續租約＋更新速度；行緊相反方向就先硬停再起步。
     * @param back true＝向後行；false＝向前行
     * @return true＝行緊（或已起步）；false＝起唔到（lastError 有原因）
     */
    public synchronized boolean hold(float speed, boolean back) {
        if (!MainActivity.isBootServoReady()) {
            lastError = "booting";
            return false;
        }
        float sp = GaitWalker.clampSpeed(speed);
        String want = back ? UbxPlayer.WALK_BACK : UbxPlayer.WALK_FWD;
        if (ubxPlayer.isWalking() && !ubxPlayer.isWalkFinishing()) {
            if (want.equals(ubxPlayer.walkName())) {
                ubxPlayer.setWalkSpeed(sp);
                ubxPlayer.beatWalk();
                return true;
            }
            // 相反方向：硬停（舵機保持末位姿），下面由當前指令姿勢重新起步。
            ubxPlayer.stop();
        }
        GaitProgram prog = back ? programBack() : program();
        if (prog == null) {
            lastError = "gait not loaded: " + (back ? loadErrorBack : loadError);
            return false;
        }
        // 普通動作播放中：搶佔（同 playActionDirect 一致）。
        if (ubxPlayer.isPlaying() && !ubxPlayer.isWalking()) ubxPlayer.stop();
        // 若正收步（已放手）：startWalk 內部會截停再起。
        boolean ok = ubxPlayer.startWalk(prog, HardwareDirectManager.get(appContext).chest(),
                sp, LEASE_MS, want);
        lastError = ok ? "" : ubxPlayer.lastError();
        return ok;
    }

    /** 速度續更（行緊先有效）。 */
    public void setSpeed(float s) {
        if (ubxPlayer.isWalking()) ubxPlayer.setWalkSpeed(GaitWalker.clampSpeed(s));
    }

    /** 租約續期。 */
    public void beat() {
        if (ubxPlayer.isWalking()) ubxPlayer.beatWalk();
    }

    /** 放手：收步回企定（線程自己完結）。 */
    public void release() {
        if (ubxPlayer.isWalking()) ubxPlayer.releaseWalk();
    }

    /** 硬停：即刻截停，舵機保持末位姿。 */
    public void stopNow() {
        if (ubxPlayer.isWalking()) ubxPlayer.stop();
    }

    public boolean isWalking() { return ubxPlayer.isWalking(); }

    /** 正喺指定方向行（未放手）？back＝true 問向後，false 問向前。 */
    public boolean isWalking(boolean back) {
        return ubxPlayer.isWalking() && !ubxPlayer.isWalkFinishing()
                && (back ? UbxPlayer.WALK_BACK : UbxPlayer.WALK_FWD).equals(ubxPlayer.walkName());
    }

    // -- HTTP ----------------------------------------------------------------

    private static float speedParam(Map<String, String> q, String name, float def) {
        float v = ApiValidator.optionalFloat(q, name, def);
        if (Float.isNaN(v) || v < GaitWalker.MIN_SPEED || v > GaitWalker.MAX_SPEED) {
            throw new IllegalArgumentException("parameter '" + name + "' must be between "
                    + GaitWalker.MIN_SPEED + " and " + GaitWalker.MAX_SPEED + ", got: " + q.get(name));
        }
        return v;
    }

    /**
     * walk/start：起步或續租約（面板按住期間每 ~250ms 再叫一次，同時帶最新 speed）。
     * {@code dir=fwd|back}（預設 fwd）；行緊相反方向會先硬停再起步。
     */
    public HttpServer.ApiResponse startResponse(Map<String, String> query) {
        float sp = speedParam(query, "speed", baseSpeed);
        boolean back = "back".equals(
                ApiValidator.optionalEnum(query, "dir", new String[] {"fwd", "back"}, "fwd"));
        if (!MainActivity.isBootServoReady()) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"booting\"}");
        }
        if (!hold(sp, back)) {
            return HttpServer.ApiResponse.error("cannot start walk: " + lastError);
        }
        return statusResponse();
    }

    /** walk/stop：預設優雅收步；hard=true 即刻硬停。 */
    public HttpServer.ApiResponse stopResponse(Map<String, String> query) {
        if (ApiValidator.optionalBoolean(query, "hard", false)) stopNow();
        else release();
        return statusResponse();
    }

    /** walk/speed：設預設速度（同時即時套用於行緊嘅路）。 */
    public HttpServer.ApiResponse speedResponse(Map<String, String> query) {
        float sp = speedParam(query, "value", baseSpeed);
        setBaseSpeed(sp);
        setSpeed(sp);
        return statusResponse();
    }

    public HttpServer.ApiResponse statusResponse() {
        return HttpServer.ApiResponse.ok("{\"ok\":true," + ubxPlayer.walkStatusJson()
                + ",\"defaultSpeed\":" + baseSpeed
                + ",\"leaseMs\":" + LEASE_MS
                + ",\"loaded\":" + (program != null)
                + ",\"loadedBack\":" + (programBack != null)
                + ",\"error\":\"" + JsonUtil.esc(lastError.isEmpty() ? loadError : lastError) + "\"}");
    }
}
