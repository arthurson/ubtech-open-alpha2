package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;

/**
 * Ubx 直播 + 單舵機直驅共用實現。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第三刀)：/api/direct/ubx/* 與
 * /api/alpha2/ubx/* 兩套路由調同一批 helper，加上 servo 單顆 cmd03 化，
 * 邏輯一字不改搬過嚟。和 ActionDirect 一樣，共用 MainActivity 傳入的同一個
 * UbxPlayer 實例；最近播放檔經 ActionDirect 存取。
 * 2026-09 dispatcher Phase 1 第二刀加：servo/one、servo/all、servo/read、
 * servo/read-all 4 個 handleApi case body 搬入 (servo/sonar 留低——threshold
 * state 同 sonar event/bridge 共用)。
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

    // -- Wakeup direction -> servo 19 (head) turn -------------------------------------
    // RobotEventReceiver already listens for the com.ubtechinc.services.SPEECH_DIRECTION
    // broadcast and publishes it to EventBus as {"type":"speech_direction",...,
    // "data":{"absoluteAngle":N}} (N already unsigned 0-255, see RobotEventReceiver).
    // This subscribes to that same EventBus feed - rather than adding a second
    // BroadcastReceiver - and does the actual servo turn. Per docs/sensors-and-events.md,
    // servo 19 is the head-yaw servo; on this unit its safe range is [75,165] with
    // home=120=facing forward. Mapping is 1:1, just clamped into that range.
    private static final String SPEECH_DIRECTION_MARKER = "\"type\":\"speech_direction\"";
    private static final int SERVO_HEAD_ID = 19;
    private static final int SERVO_HEAD_MIN = 75;
    private static final int SERVO_HEAD_MAX = 165;
    private static final short SERVO_TURN_TIME_MS = 500;

    public void registerWakeupDirectionListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains(SPEECH_DIRECTION_MARKER)) {
                    return;
                }
                final Integer angle = extractAbsoluteAngle(line);
                if (angle == null) {
                    return;
                }
                // onEvent() runs on the main thread. pure-direct 下直发无需等待，
                // 仍放 background thread 避免阻塞 EventBus 分发。
                // 注：SPEECH_DIRECTION 广播本身由旧 alpha2services 发出，机身无此 APK
                // 后此监听自然不再触发，保留仅作兼容。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int servoAngle = clampServoAngle(angle);
                        HardwareDirectManager.get(appContext).chest().setSingleServo((byte) SERVO_HEAD_ID, servoAngle, SERVO_TURN_TIME_MS);
                    }
                }).start();
            }
        });
    }

    /** Pulls the integer after "absoluteAngle":  out of an EventBus-published JSON line,
     *  without pulling in a JSON library (matching the rest of this file's style). */
    private static Integer extractAbsoluteAngle(String line) {
        String key = "\"absoluteAngle\":";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return null;
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampServoAngle(int angle) {
        if (angle < SERVO_HEAD_MIN) return SERVO_HEAD_MIN;
        if (angle > SERVO_HEAD_MAX) return SERVO_HEAD_MAX;
        return angle;
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

    // -- Servo endpoint 回應層 (2026-09 dispatcher Phase 1 第二刀由 handleApi 搬入) --
    // directChestReady() 內聯：同 MainActivity 版一字不差，經 appContext 唔使 Activity。
    private boolean directChestReady() {
        try { return HardwareDirectManager.get(appContext).chest().isAvailable(); }
        catch (Exception e) { return false; }
    }

    public HttpServer.ApiResponse servoOneResponse(Map<String, String> query) {
        // pure-direct: cmd05 在本机固件有 ACK 无动作，改走 cmd03 全帧。
        int id = ApiValidator.requireIntRange(query, "id", 1, 20);
        int angle = ApiValidator.requireInt(query, "angle");
        int time = ApiValidator.optionalInt(query, "time", 1000);
        boolean ok = servoSendOneCode(id, angle, time) == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        return MainActivity.codeResponseReady(MainActivity.directCode(ok), directChestReady());
    }

    public HttpServer.ApiResponse servoAllResponse(Map<String, String> query) {
        int[] angles = ApiValidator.requireAngles20(query);
        int time = ApiValidator.optionalInt(query, "time", 1000);
        // setAllServos 内部已转 cmd03（cmd52 有 ACK 无动作）。
        boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(angles, (short) time);
        if (sent) ubxPlayer.notePose(angles);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), directChestReady());
    }

    public HttpServer.ApiResponse servoReadResponse(Map<String, String> query) {
        // 命令位姿追踪值：本机胸 cmd13 回包恒定（跳舞途中亦不变），无实时回授；
        // tuner 要的是“当前摆位”，命令位姿即正确语义。未知如实报，不编 0。
        int idInt = ApiValidator.requireIntRange(query, "id", 1, 20);
        int[] pose = ubxPlayer.pose();
        if (pose == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"pose unknown (play any action first)\",\"known\":false}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + pose[idInt - 1] + ",\"offset\":" + pose[idInt - 1]
                + ",\"known\":true}");
    }

    public HttpServer.ApiResponse servoReadAllResponse() {
        int[] pose = ubxPlayer.pose();
        if (pose == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"known\":false,"
                    + "\"error\":\"pose unknown (play any action first)\"}");
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"known\":true,\"angles\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(pose[i]);
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }
}
