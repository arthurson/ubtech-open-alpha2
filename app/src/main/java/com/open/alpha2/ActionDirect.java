package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 內建動作直驅：actionInfo.txt + UbxPlayer。
 */
public final class ActionDirect {
    private static final String TAG = "ActionDirect";

    /** 一鍵全停之後補播嘅「蹲下站起」回位動作。 */
    public static final String STOP_RECOVERY_ACTION_ID = "1510818174706";

    // actionInfo.txt 行格式（GBK 编码）：<fileId>##<nameCn>##<nameEn>##<type>，
    // 与旧 AIDL getActionList 行顺序不同（彼为 id/type/nameCn/nameEn），此处重排，
    // 前端收到的 JSON 形状与以前完全一致，app-actions.js 无需改动。
    private static final String ACTION_DIR = "/sdcard/actions";
    private static final String ACTION_INFO = "/sdcard/actions/actionInfo.txt";

    private final Context appContext;
    private final UbxPlayer ubxPlayer;

    private List<String[]> actionInfoCache; // 每项 [fileId, nameCn, nameEn, type]
    /** Cached parse of assets/web/xiaozhi_actions.json (202 動作, id/nameCn/nameEn) -
     *  loaded once lazily on first use rather than at onCreate(), since it's only
     *  needed if/when the XiaoZhi tab's play_action tool schema is actually requested.
     *  null until first load attempt; an empty (but non-null) list means the load was
     *  attempted and failed/produced nothing. */
    private volatile java.util.List<org.json.JSONObject> xiaozhiActionsCache;
    private volatile java.io.File lastPlayedFile;

    public ActionDirect(Context context, UbxPlayer ubxPlayer) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
    }

    /** 最後一次播緊/播過嘅檔 (ubx/speed 播緊重播用；可 null)。 */
    public java.io.File getLastPlayedFile() {
        return lastPlayedFile;
    }

    public void setLastPlayedFile(java.io.File f) {
        lastPlayedFile = f;
    }

    private synchronized List<String[]> loadActionInfo() {
        if (actionInfoCache != null) return actionInfoCache;
        List<String[]> out = new ArrayList<>();
        try {
            java.io.File f = new java.io.File(ACTION_INFO);
            // 先驗大細（唔存在/空/超過 1MB 直接當空表）。
            long flen = f.length();
            if (!f.isFile() || flen <= 0 || flen > 1024L * 1024L) {
                Log.w(TAG, "loadActionInfo skip unusual file len=" + flen);
                actionInfoCache = out;
                return out;
            }
            byte[] data = new byte[(int) flen];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                // 用實際讀到嘅 bytes（短讀會有 NUL 混入；UTF-8 strict 唔得先 fallback GBK）。
                String text = decodeActionInfo(data, off);
                for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split("##", -1);
                if (cols.length < 4) continue;
                out.add(new String[]{cols[0].trim(), cols[1].trim(), cols[2].trim(), cols[3].trim()});
                }
            } finally {
                try { in.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "loadActionInfo failed", e);
        }
        actionInfoCache = out;
        return out;
    }

    /** actionInfo.txt 解碼：UTF-8 strict 得就用佢，唔得（GBK 中文）先 fallback
     *  GBK。new String(bytes,"UTF-8") 從來唔掟錯（爛 byte 變 U+FFFD），所以要用
     *  REPORT 嘅 CharsetDecoder 先分得出。 */
    private static String decodeActionInfo(byte[] data, int len) throws Exception {
        try {
            java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8").newDecoder();
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(data, 0, len)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(data, 0, len, "GBK");
        }
    }

    /** 讀 assets/web/xiaozhi_actions.json 做 catalog (MCP fuzzy、隨機池共用)。 */
    public java.util.List<org.json.JSONObject> loadXiaozhiActions() {
        java.util.List<org.json.JSONObject> cached = xiaozhiActionsCache;
        if (cached != null) return cached;
        java.util.List<org.json.JSONObject> result = new java.util.ArrayList<>();
        try (java.io.InputStream in = appContext.getAssets().open("web/xiaozhi_actions.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            org.json.JSONArray arr = new org.json.JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadXiaozhiActions: failed to load assets/web/xiaozhi_actions.json: " + e);
        }
        xiaozhiActionsCache = result;
        return result;
    }

    /** Picks a random id from the "隨機短/隨機長" action group in
     *  xiaozhi_actions.json - these are the robot's own pre-recorded filler-movement
     *  actions (20 of them: 隨機短1-10、隨機長1-10, with a couple of duplicate ids for
     *  the same name e.g. two "隨機短2" entries - both are valid, harmless to include
     *  twice in the pool), meant for exactly this "play something to look alive"
     *  use case rather than reacting to any specific emotion/content. Matched by
     *  nameCn prefix rather than a hardcoded id list so this keeps working if
     *  xiaozhi_actions.json is regenerated from a different 202_actions_classified.txt
     *  with different ids. Returns null (never throws) if the group is empty for any
     *  reason - caller must handle that as a normal "nothing to play" case, not a bug. */
    public String resolveRandomActionId() {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (org.json.JSONObject a : actions) {
            String cn = a.optString("nameCn");
            if (cn.startsWith("隨機短") || cn.startsWith("隨機長")) {
                pool.add(a.optString("id"));
            }
        }
        if (pool.isEmpty()) return null;
        return pool.get(new java.util.Random().nextInt(pool.size()));
    }

    /** Resolves a human-supplied action name (Chinese or English, as passed by the
     *  XiaoZhi LLM to self.robot.play_action) to the actual on-device action id from
     *  xiaozhi_actions.json. Tried in order, first match wins:
     *  1. Exact id match (in case the caller *does* pass a raw id - still valid).
     *  2. Exact match against nameCn or nameEn (case-insensitive for nameEn).
     *  3. Substring match either direction (query contains the action name, or the
     *     action name contains the query) - handles the LLM paraphrasing slightly,
     *     catching the common case of extra/missing words around a name that
     *     otherwise matches exactly.
     *  Returns null if nothing matches closely enough - deliberately does not fall
     *  back to a "best guess" at low confidence, since a wrong action executing on
     *  physical hardware is worse than a clear "not found" the LLM can react to (see
     *  the "raise_left_hand" bug this whole mechanism exists to prevent). */
    public String resolveActionId(String query) {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        if (query == null) return null;
        String q = query.trim();
        if (q.isEmpty()) return null;

        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("id"))) return a.optString("id");
        }
        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("nameCn"))
                    || q.equalsIgnoreCase(a.optString("nameEn"))) {
                return a.optString("id");
            }
        }
        String qLower = q.toLowerCase(java.util.Locale.US);
        for (org.json.JSONObject a : actions) {
            String cn = a.optString("nameCn");
            String en = a.optString("nameEn").toLowerCase(java.util.Locale.US);
            if ((!cn.isEmpty() && (q.contains(cn) || cn.contains(q)))
                    || (!en.isEmpty() && (qLower.contains(en) || en.contains(qLower)))) {
                return a.optString("id");
            }
        }
        return null;
    }

    /** 动作名/ID 解析：fileId > nameEn > nameCn，另支持同目錄 xxx.ubx。
     *  帶 / 嘅路徑只准 /sdcard/actions 內 .ubx（canonical 鎖死）。 */
    private java.io.File resolveActionFile(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.endsWith(".ubx")) {
            java.io.File direct = n.indexOf('/') >= 0 || n.indexOf('\\') >= 0
                    ? new java.io.File(n) : new java.io.File(ACTION_DIR + "/" + n);
            try {
                String target = direct.getCanonicalPath();
                String base = new java.io.File(ACTION_DIR).getCanonicalPath();
                if (!target.equals(base) && target.startsWith(base + java.io.File.separator)
                        && target.toLowerCase(java.util.Locale.US).endsWith(".ubx")
                        && direct.isFile()) {
                    return direct;
                }
            } catch (Exception ignore) {}
            if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return null;
        }
        List<String[]> info = loadActionInfo();
        String id = null;
        for (String[] r : info) {
            if (r[0].equals(n)) { id = r[0]; break; }
        }
        if (id == null) {
            for (String[] r : info) {
                if (r[2].equalsIgnoreCase(n)) { id = r[0]; break; }
            }
        }
        if (id == null) {
            for (String[] r : info) {
                if (r[1].equals(n)) { id = r[0]; break; }
            }
        }
        if (id == null) return null;
        java.io.File f = new java.io.File(ACTION_DIR + "/" + id + ".ubx");
        return f.isFile() ? f : null;
    }

    public HttpServer.ApiResponse actionListDirect() {
        List<String[]> info = loadActionInfo();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"actions\":[");
        boolean first = true;
        for (String[] r : info) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(MainActivity.jsonSafe(r[0])).append("\",")
                    .append("\"type\":\"").append(MainActivity.jsonSafe(r[3])).append("\",")
                    .append("\"nameCn\":\"").append(MainActivity.jsonSafe(r[1])).append("\",")
                    .append("\"nameEn\":\"").append(MainActivity.jsonSafe(r[2])).append("\"}");
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse actionPlayDirect(String name) {
        java.io.File f = resolveActionFile(name);
        if (f == null) return HttpServer.ApiResponse.error("unknown action: " + name);
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return HttpServer.ApiResponse.error("chest not available");
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("parse failed: " + e.getMessage());
        }
        ubxPlayer.stop(); // 抢占：播新自动停旧
        if (!ubxPlayer.play(ubx, f.getName(), dm.chest(), f)) {
            return HttpServer.ApiResponse.error("cannot start: " + ubxPlayer.lastError());
        }
        lastPlayedFile = f;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\",\"name\":\""
                + MainActivity.jsonSafe(f.getName()) + "\",\"total\":" + ubxPlayer.total() + "}");
    }

    /**
     * 内部共用：pure-direct 播指定动作（fileId/中英文名/xxx.ubx 皆可），抢占式——
     * 先停当前再播，与原厂 playActionName 打断语义一致。供手势总停、MCP tool、
     * 语义动作、随机 filler 共用，HTTP action/play 另有「播緊先報錯」守卫故不经此。
     */
    public UbxErrorCode.API_ERROR_CODE playActionDirect(String nameOrId) {
        java.io.File f = resolveActionFile(nameOrId);
        if (f == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            Log.w(TAG, "playActionDirect parse failed " + f, e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
        ubxPlayer.stop();
        if (!ubxPlayer.play(ubx, f.getName(), dm.chest(), f)) {
            Log.w(TAG, "playActionDirect not started: " + ubxPlayer.lastError());
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
        lastPlayedFile = f;
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    /**
     * 内部共用：一键全停（动作部分）——截停 UbxPlayer 后补播 STOP_RECOVERY_ACTION_ID
     * 蹲下站起回位。回位播唔播到唔影响返回值。供 0x5e 手势（含拍头双 pad）、
     * MCP stop_action、HTTP action/stop 共用。
     */
    public UbxErrorCode.API_ERROR_CODE stopActionWithRecovery() {
        ubxPlayer.stop();
        try {
            UbxErrorCode.API_ERROR_CODE rec = playActionDirect(STOP_RECOVERY_ACTION_ID);
            if (rec != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                Log.w(TAG, "recovery not started: " + ubxPlayer.lastError());
            }
        } catch (Exception e) {
            Log.w(TAG, "recovery play failed", e);
        }
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    // -- MCP tools (XiaozhiBridge callTool switch 轉調) --

    /** self.robot.list_actions 本體 (XiaozhiBridge 轉調)。純讀, 唔掂硬件。 */
    public SonarCenter.McpResult mcpListActions() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (org.json.JSONObject a : loadXiaozhiActions()) {
            arr.put(a);
        }
        return SonarCenter.McpResult.ok(arr.toString());
    }

    /** self.robot.play_action 本體 (XiaozhiBridge 轉調)。
     *  小智傳過來的是人類語言的動作名 (中文/英文)，這裡做 fuzzy match 找出真正對應機身檔案的 id。 */
    public SonarCenter.McpResult mcpPlayAction(org.json.JSONObject arguments) {
        String actionName = arguments.optString("name", "");
        if (actionName.isEmpty()) {
            return SonarCenter.McpResult.err("missing required argument: name");
        }
        String resolvedId = resolveActionId(actionName);
        if (resolvedId == null) {
            return SonarCenter.McpResult.err("no action found matching \"" + actionName
                    + "\" - call self.robot.list_actions to see valid names");
        }
        UbxErrorCode.API_ERROR_CODE code = playActionDirect(resolvedId);
        return new SonarCenter.McpResult(!MainActivity.isOk(code),
                String.valueOf(code) + " (matched \"" + actionName + "\" -> id " + resolvedId + ")");
    }

    /** self.robot.stop_action 本體 (XiaozhiBridge 轉調)。
     *  pure-direct：一键全停+蹲下站起回位，和 HTTP action/stop 同语义。 */
    public SonarCenter.McpResult mcpStopAction() {
        UbxErrorCode.API_ERROR_CODE code = stopActionWithRecovery();
        return new SonarCenter.McpResult(!MainActivity.isOk(code), String.valueOf(code));
    }

    /** self.robot.play_random_action 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpPlayRandomAction() {
        String randomId = resolveRandomActionId();
        if (randomId == null) {
            return SonarCenter.McpResult.err("no random-movement actions available");
        }
        UbxErrorCode.API_ERROR_CODE code = playActionDirect(randomId);
        return new SonarCenter.McpResult(!MainActivity.isOk(code),
                String.valueOf(code) + " (played random action id " + randomId + ")");
    }
}
