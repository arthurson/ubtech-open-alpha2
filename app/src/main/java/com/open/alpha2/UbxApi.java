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
 * /api/alpha2/ubx/* 兩套路由調同一批 helper，加上 servo 單顆 cmd05 直發，
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
    private final ChestQuery chestQuery;

    public UbxApi(Context context, UbxPlayer ubxPlayer, ActionDirect actionDirect, ChestQuery chestQuery) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
        this.actionDirect = actionDirect;
        this.chestQuery = chestQuery;
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

    // -- Servo 單舵機直發（cmd05）--------------------------------------------------
    // 2026-09-06 由 cmd03 全幀改回 cmd05 單發：官方 PC tuner 實測證實 cmd05
    //（05 00 頭）正常驅動本機舵機，用戶目視確認。舊 cmd03 全幀寫法有安全問題：
    // 它用位姿追踪（dead reckoning）補齊其餘 19 軸，追踪值一過時（重啟/跳舞後）
    // 就會一次過將 19 粒舵機扯去錯位姿——即「一寫入就發狂」。cmd05 只郁目標
    // 一粒，其他軸完全唔掂，天然安全；亦唔再需要 pose 已知先郁得。
    /** 單舵機經 cmd05 直發；只在串口不可用時 FAILED。 */
    public UbxErrorCode.API_ERROR_CODE servoSendOneCode(int id, int angle, int timeMs) {
        if (id < 1 || id > 20) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        if (angle < 0) angle = 0;
        if (angle > 255) angle = 255;
        if (timeMs < 20) timeMs = 20;
        boolean sent = HardwareDirectManager.get(appContext).chest()
                .setSingleServo((byte) id, angle, (short) timeMs);
        if (!sent) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        // 位姿追踪有值就順手更新嗰一軸（ keeping 其餘 19 軸），未知就唔編造。
        int[] cur = ubxPlayer.pose();
        if (cur != null) {
            cur[id - 1] = angle & 0xFF;
            ubxPlayer.notePose(cur);
        }
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
            return HttpServer.ApiResponse.error("direct not ready");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"angle\":" + (angle & 0xFF) + "}");
    }

    // -- Servo endpoint 回應層 (2026-09 dispatcher Phase 1 第二刀由 handleApi 搬入) --
    // directChestReady() 內聯：經 appContext 唔使 Activity（各 center 自帶副本；
    // 原 MainActivity 私有版 2026-09 刪，零調用）。
    private boolean directChestReady() {
        try { return HardwareDirectManager.get(appContext).chest().isAvailable(); }
        catch (Exception e) { return false; }
    }

    public HttpServer.ApiResponse servoOneResponse(Map<String, String> query) {
        // pure-direct: 單舵機經 cmd05 直發（2026-09-06 官方 tuner 實測可郁，用戶目視確認）。
        // 2026-09-06 晚加：可選 trim 參數——有帶就接著經 cmd12 寫入 chest EEPROM
        //（官方 tuner 同款持久化；掉電保持，亂寫會改出廠校準，用戶明確先好用）。
        int id = ApiValidator.requireIntRange(query, "id", 1, 20);
        int angle = ApiValidator.requireInt(query, "angle");
        int time = ApiValidator.optionalInt(query, "time", 1000);
        Integer trim = null;
        if (query.containsKey("trim") && query.get("trim") != null && !query.get("trim").isEmpty()) {
            try {
                trim = Integer.parseInt(query.get("trim").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'trim' must be an integer");
            }
            if (trim < -1000 || trim > 1000) {
                throw new IllegalArgumentException("parameter 'trim' must be within [-1000,1000]");
            }
        }
        boolean ok = servoSendOneCode(id, angle, time) == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        if (!ok || trim == null) {
            return MainActivity.codeResponseReady(MainActivity.directCode(ok), directChestReady());
        }
        Boolean written = writeServoTrimLive(id, trim);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\"");
        sb.append(",\"bindReady\":").append(directChestReady());
        sb.append(",\"id\":").append(id).append(",\"angle\":").append(angle & 0xFF);
        sb.append(",\"trim\":").append(trim);
        sb.append(",\"trimWritten\":").append(written != null && written);
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
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
        // 2026-09-06 改行 live 實讀 (cmd 13)：官方 PC tuner 同款問法。注意回的是
        // chest 存住的 trim（偏差，官方角:偏 = 1:3），<b>不是</b>絕對角度——8 號
        // 例子：位姿 65 不變，橫跨幾次動作都係讀返 -33（見官方 session logcat
        // 比較）。trim 即 offset 原值，前端照 show，唔使再減 home。
        // 讀唔到（超時／舵機回 01 error，如本機 5/6 號硬件壞；17/18 號手指
        // 天生無回授）如實報 ok:false，不編 0。
        int idInt = ApiValidator.requireIntRange(query, "id", 1, 20);
        Integer live = readServoLive(idInt);
        int[] pose = ubxPlayer.pose();
        Integer commanded = (pose != null) ? pose[idInt - 1] : null;
        if (live == null) {
            StringBuilder sb = new StringBuilder("{\"ok\":false,\"id\":" + idInt + ",\"live\":false");
            sb.append(",\"commanded\":").append(commanded != null ? commanded : "null");
            sb.append(",\"known\":").append(commanded != null);
            sb.append(",\"error\":\"no feedback (timeout or faulty servo)\"}");
            return HttpServer.ApiResponse.ok(sb.toString());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"trim\":" + live + ",\"live\":true"
                + ",\"commanded\":" + (commanded != null ? commanded : "null")
                + ",\"known\":true}");
    }

    public HttpServer.ApiResponse servoReadAllResponse() {
        // 2026-09-06 改行 20 連讀 trim（官方 tuner 節奏：逐粒約十幾 ms 間隔）。
        // trims[i] = 該軸 chest 存住的偏差原值，讀唔到嗰粒記 null 並列入 failed。
        // 注意：呢度唔係絕對角度，唔好攞去填 angle 輸入格。
        Integer[] trims = new Integer[20];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= 20; i++) {
            Integer v = readServoLive(i);
            trims[i - 1] = v;
            if (v == null) {
                if (!firstFail) failed.append(',');
                failed.append(i);
                firstFail = false;
            } else {
                try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"trims\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(trims[i] != null ? trims[i] : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /**
     * 帶重試的單粒實讀。機身仲有官方 alpha2services 同揸 ttyS1，回覆 bytes
     * 會被搶食，偶發超時屬預期之內，故重試 3 次（官方 ACK 約 10-15ms，
     * 250ms timeout 好闊綽）。
     */
    private Integer readServoLive(int id) {
        if (chestQuery == null || !directChestReady()) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer v = chestQuery.queryServoAngle(id, 250);
            if (v != null) return v;
            if (attempt == 2) break;
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    /**
     * 帶重試的 trim 寫入（3 次 × 250ms，與實讀同級——官方 service 爭食回覆
     * bytes 時超時常見）。回 TRUE/FALSE（ACK 語意）；全部超時／發送失敗回 null。
     */
    private Boolean writeServoTrimLive(int id, int trim) {
        if (chestQuery == null || !directChestReady()) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean r = chestQuery.writeServoTrim(id, trim, 250);
            if (r != null) return r;
            if (attempt == 2) break;
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }
}
