package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

/**
 * Ubx 直播 + 單舵機直驅共用實現。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第三刀)：/api/direct/ubx/* 與
 * /api/alpha2/ubx/* 兩套路由調同一批 helper，加上 servo 單顆 cmd03 化，
 * 邏輯一字不改搬過嚟。和 ActionDirect 一樣，共用 MainActivity 傳入的同一個
 * UbxPlayer 實例 (servo 讀寫仲喺嗰邊直接用)；最近播放檔經 ActionDirect 存取。
 */
public final class UbxApi {
    private static final String TAG = "UbxApi";

    private final Context appContext;
    private final UbxPlayer ubxPlayer;
    private final ActionDirect actionDirect;

    public UbxApi(Context context, UbxPlayer ubxPlayer, ActionDirect actionDirect) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
        this.actionDirect = actionDirect;
    }

    public HttpServer.ApiResponse ubxListResponse() {
        java.io.File dir = new java.io.File("/sdcard/actions");
        String[] names = dir.list();
        if (names == null) return HttpServer.ApiResponse.error("no /sdcard/actions");
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
        boolean first = true;
        for (String n : names) {
            java.io.File f = new java.io.File(dir, n);
            if (!f.isFile()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(MainActivity.jsonSafe(n)).append("\",\"size\":").append(f.length()).append('}');
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse ubxPlayResponse(String name, String p) {
        java.io.File f;
        if (p != null) f = new java.io.File(p);
        else if (name != null) f = new java.io.File("/sdcard/actions/" + name);
        else return HttpServer.ApiResponse.error("name or path required");
        if (!f.isFile()) return HttpServer.ApiResponse.error("not found: " + f.getPath());
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) return HttpServer.ApiResponse.error("chest not available");
        UbxFile ubx;
        try {
            ubx = UbxParser.parseFile(f);
        } catch (Exception e) {
            return HttpServer.ApiResponse.error("parse failed: " + e.getMessage());
        }
        ubxPlayer.stop(); // 抢占：停旧播新
        boolean started = ubxPlayer.play(ubx, f.getName(), dm.chest(), f);
        if (!started) return HttpServer.ApiResponse.error("cannot start: " + ubxPlayer.lastError());
        actionDirect.setLastPlayedFile(f);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"name\":\"" + MainActivity.jsonSafe(f.getName())
                + "\",\"total\":" + ubxPlayer.total() + "}");
    }

    public HttpServer.ApiResponse ubxSpeedResponse(String v) {
        float f = ApiValidator.parseUbxSpeedValue(v);
        if (!ubxPlayer.setSpeed(f)) {
            throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
        }
        // 播緊時即時生效：用新速度由頭重播同一文件（内部快照隔离，旧计划安全交接）。
        boolean restarted = false;
        java.io.File last = actionDirect.getLastPlayedFile();
        if (ubxPlayer.isPlaying() && last != null && last.isFile()) {
            try {
                UbxFile rubx = UbxParser.parseFile(last);
                HardwareDirectManager rdm = HardwareDirectManager.get(appContext);
                if (rdm.chest().isAvailable()) {
                    ubxPlayer.stop();
                    restarted = ubxPlayer.play(rubx, last.getName(), rdm.chest(), last);
                    if (restarted) actionDirect.setLastPlayedFile(last);
                }
            } catch (Exception e) {
                Log.w(TAG, "speed restart failed", e);
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\",\"speed\":" + ubxPlayer.getSpeed()
                + ",\"restarted\":" + restarted + "}");
    }

    public HttpServer.ApiResponse ubxStopResponse() {
        ubxPlayer.stop();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse ubxStatusResponse() {
        return HttpServer.ApiResponse.ok(ubxPlayer.statusJson());
    }

    // -- Servo 命令位姿（cmd03 化）--------------------------------------------------
    // 本机胸固件只执行 cmd 3：单舵机 = 全帧改一轴后整帧发；读角 = 命令位姿追踪
    // （ServoPoseTracker，开机未动过则 unknown，绝不编造）。
    /** 单舵机经 cmd03 全帧发送；返回 code（pose unknown 时 FAILED，调用方各自组 JSON）。 */
    public UbxErrorCode.API_ERROR_CODE servoSendOneCode(int id, int angle, int timeMs) {
        int[] cur = ubxPlayer.pose();
        if (cur == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        cur[id - 1] = angle & 0xFF;
        boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(cur, (short) timeMs);
        if (!sent) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        ubxPlayer.notePose(cur);
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    public HttpServer.ApiResponse servoSendOne(int id, int angle, int timeMs) {
        UbxErrorCode.API_ERROR_CODE code = servoSendOneCode(id, angle, timeMs);
        if (code != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            boolean known = ubxPlayer.poseKnown();
            return HttpServer.ApiResponse.error(known ? "direct not ready"
                    : "pose unknown (play any action first, or send full pose via servo/all)");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"angle\":" + (angle & 0xFF) + "}");
    }
}
