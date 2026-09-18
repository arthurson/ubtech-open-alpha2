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

    /** 只准 /sdcard/actions 內 .ubx（防 LAN 經 path/name 讀任意檔）。 */
    private static boolean isAllowedUbxFile(java.io.File f) {
        if (f == null) return false;
        try {
            String target = f.getCanonicalPath();
            String base = new java.io.File("/sdcard/actions").getCanonicalPath();
            if (target.equals(base)) return false;
            if (!target.startsWith(base + java.io.File.separator)) return false;
            return target.toLowerCase(java.util.Locale.US).endsWith(".ubx");
        } catch (Exception e) {
            return false;
        }
    }

    public HttpServer.ApiResponse ubxPlayResponse(String name, String p) {
        java.io.File f;
        if (p != null) {
            f = new java.io.File(p);
            if (!isAllowedUbxFile(f)) return HttpServer.ApiResponse.error("path must be a .ubx under /sdcard/actions");
        } else if (name != null) {
            // name 只准是檔名，不准帶路徑（../../、/、\ 一律拒）。
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return HttpServer.ApiResponse.error("invalid name");
            }
            f = new java.io.File("/sdcard/actions/" + name);
            if (!isAllowedUbxFile(f)) return HttpServer.ApiResponse.error("invalid name");
        } else return HttpServer.ApiResponse.error("name or path required");
        if (!f.isFile()) return HttpServer.ApiResponse.error("not found");
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
        // 正在播時即時生效：用新速度由頭重播同一文件（内部快照隔离，旧计划安全交接）。
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
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse ubxStatusResponse() {
        return HttpServer.ApiResponse.ok(ubxPlayer.statusJson());
    }

    // -- Servo 單舵機直發（cmd05）--------------------------------------------------
    // 官方 PC tuner 實測證實 cmd05
    //（05 00 頭）正常驅動本機舵機，用戶目視確認。舊 cmd03 全幀寫法有安全問題：
    // 它用位姿追踪（dead reckoning）補齊其餘 19 軸，追踪值一過時（重啟/跳舞後）
    // 就會一次過將 19 粒舵機扯去錯位姿——即「一寫入就發狂」。cmd05 只動目標
    // 一粒，其他軸完全不碰，天然安全；亦不再需要 pose 已知先動得。
    /** 單舵機經 cmd05 直發；只在串口不可用時 FAILED。 */
    public UbxErrorCode.API_ERROR_CODE servoSendOneCode(int id, int angle, int timeMs) {
        if (id < ApiValidator.SERVO_ID_MIN || id > ApiValidator.SERVO_ID_MAX) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        if (angle < ApiValidator.SERVO_ANGLE_MIN) angle = ApiValidator.SERVO_ANGLE_MIN;
        if (angle > ApiValidator.SERVO_ANGLE_MAX) angle = ApiValidator.SERVO_ANGLE_MAX;
        if (timeMs < ApiValidator.SERVO_TIME_MIN_MS) timeMs = ApiValidator.SERVO_TIME_MIN_MS;
        boolean sent = HardwareDirectManager.get(appContext).chest()
                .setSingleServo((byte) id, angle, (short) timeMs);
        if (!sent) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        // 位姿追踪：記下這一軸命令值（逐軸 known；命令位姿榜／commanded 回讀用）。
        ubxPlayer.notePoseOne(id, angle & 0xFF);
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    public HttpServer.ApiResponse servoSendOne(int id, int angle, int timeMs) {
        UbxErrorCode.API_ERROR_CODE code = servoSendOneCode(id, angle, timeMs);
        if (code != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            return HttpServer.ApiResponse.error("direct not ready");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"angle\":" + (angle & 0xFF) + "}");
    }

    // -- Servo endpoint 回應層 --
    // directChestReady() 薄 delegate（實現見 DirectProbes）。
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    public HttpServer.ApiResponse servoOneResponse(Map<String, String> query) {
        // pure-direct: 單舵機經 cmd05 直發。
        // 可選 trim 參數——有帶就接著經 cmd12 寫入 chest EEPROM
        //（官方 tuner 同款持久化；掉電保持，亂寫會改出廠校準，用戶明確先好用）。
        int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        // 跟 spec/MCP（angle 0-255，time 20-32767）顯式驗，
        // 超限即 400。
        int angle = ApiValidator.requireIntRange(query, "angle", ApiValidator.SERVO_ANGLE_MIN, ApiValidator.SERVO_ANGLE_MAX);
        int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 1000);
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
            return MainActivity.sentReadyResponse(ok, directChestReady());
        }
        Boolean written = writeServoTrimLive(id, trim);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"code\":\"API_ERROR_SUCCEED\"");
        sb.append(",\"bindReady\":").append(directChestReady());
        sb.append(",\"id\":").append(id).append(",\"angle\":").append(angle & 0xFF);
        sb.append(",\"trim\":").append(trim);
        // written==null（回覆被官方 service 搶食完）不等於
        // 寫失敗——id2 實測零 ACK 照存入。報 unknown 不報失敗，叫前端重新掃描驗證。
        sb.append(",\"trimWritten\":").append(written != null && written);
        sb.append(",\"trimUnknown\":").append(written == null);
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAllResponse(Map<String, String> query) {
        int[] angles = ApiValidator.requireAngles20(query);
        int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 1000);
        // setAllServos 内部已转 cmd03（cmd52 有 ACK 无动作）。
        boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(angles, (short) time);
        if (sent) ubxPlayer.notePose(angles);
        return MainActivity.sentReadyResponse(sent, directChestReady());
    }

    public HttpServer.ApiResponse servoReadResponse(Map<String, String> query) {
        // 單粒 live 實際讀取 (cmd 13)：官方 PC tuner 同款問法。注意回的是
        // chest 存住的 trim（偏差，官方角:偏 = 1:3），<b>不是</b>絕對角度——8 號
        // 例子：位姿 65 不變，橫跨幾次動作都是讀回 -33（見官方 session logcat
        // 比較）。trim 即 offset 原值，前端照 show，不用再減 home。
        // 讀不到（超時／舵機回 01 error，如本機 5/6 號硬件壞；17/18 號手指
        // 天生無回授）如實報 ok:false，不編 0。
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer live = readServoLive(idInt);
        Integer commanded = ubxPlayer.poseBoxed()[idInt - 1];
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
                + ",\"known\":" + (commanded != null) + "}");
    }

    /** 20 連讀粒間隔 100ms（servoReadAll／servoAngleAll 逐字一樣；被打斷回 false 給 caller break）。 */
    private static boolean sleepBetweenServos() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return true;
    }

    /** 20 連讀 failed 串接（兩處逐字一樣）：逗號分隔＋記位。恆回 false，caller 掟返入 firstFail。 */
    private static boolean appendFailed(StringBuilder failed, boolean firstFail, int i) {
        if (!firstFail) failed.append(',');
        failed.append(i);
        return false;
    }

    public HttpServer.ApiResponse servoReadAllResponse() {
        // 20 連讀 trim（官方 tuner 節奏：逐顆約十幾 ms 間隔）。
        // trims[i] = 該軸 chest 存住的偏差原值，讀不到那顆記 null 並列入 failed。
        // 注意：這裡不是絕對角度，不要拿去填 angle 輸入格。
        // 逐顆之間一律等 100ms（成功失敗都等）。
        Integer[] trims = new Integer[20];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= 20; i++) {
            Integer v = readServoLive(i);
            trims[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            }
            if (i < 20) {
                if (!sleepBetweenServos()) break;
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

    public HttpServer.ApiResponse servoAngleResponse(Map<String, String> query) {
        // 單舵機絕對角度實際讀取 (cmd 6)。同 servo/one 同單位，
        // 跟位實測誤差約 1°——注意不是 servo/read 那個 trim/偏差。
        // 讀不到（超時）如實報 ok:false，不編 0。
        // 注意：讀本身會令那顆鬆力（firmware 行為，兩部機證實），要重新上力就行
        // servo/angle-restore（讀寫原子）或隨便動它一動。
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoAbsLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + "}");
    }

    public HttpServer.ApiResponse servoAngleRestoreResponse(Map<String, String> query) {
        // 讀＋即寫回原子操作。cmd6 讀會鬆開那顆（見上），
        // 這裡讀到即用 servo/one（cmd05）寫回同一個位上力，全程後端內完成、
        // 不經瀏覽器來回——鬆力窗口得幾十毫秒，跌都未跌得切，肉眼不覺動。
        // time 用最細 20ms：純粹為快趣上力，不是為動。
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoAbsLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        boolean restored = servoSendOneCode(idInt, angle, 20)
                == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + ",\"restored\":" + restored + "}");
    }

    public HttpServer.ApiResponse servoAngleAllResponse() {
        // 20 連讀＋逐顆即寫回（每粒讀寫原子，粒與粒之間隔 100ms）。
        // 背景：純連讀（不寫回）已被兩部機證實會逐顆弄垮出力；但單粒讀＋即寫回
        // 已證實無鬆無動，故連讀版都是同一個原子操作逐顆做。讀不到那顆不寫回、
        // 記 null 入 failed。未知能否全程站穩——實測中。
        Integer[] angles = new Integer[20];
        boolean[] restored = new boolean[20];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= 20; i++) {
            Integer v = readServoAbsLive(i);
            angles[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            } else {
                restored[i - 1] = servoSendOneCode(i, v, 20)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
            }
            if (i < 20) {
                if (!sleepBetweenServos()) break;
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"angles\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(angles[i] != null ? angles[i] : "null");
        }
        sb.append("],\"restored\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(restored[i]);
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /**
     * 帶重試的單粒絕對角度實際讀取（3 次 × 250ms；重試之間隔 100ms，同連讀同級，
     * 不要密 hammer 胸板——見 servoAngleAllResponse 事故 comment）。
     */
    private Integer readServoAbsLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoAbsAngle(id, 250); }
        });
    }

    /** 重試骨架共用形（上面三個 live* 方法之前逐字一樣，僅 query call／重試間隔不同；
     *  3 次、attempt==2 即停、打斷回 null，全部保留）。成功回值，全部超時／發送失敗回 null。 */
    private interface ServoRetry<T> { T attempt(); }

    private <T> T retryServoLive(int sleepMs, ServoRetry<T> op) {
        if (chestQuery == null || !directChestReady()) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            T v = op.attempt();
            if (v != null) return v;
            if (attempt == 2) break;
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    /**
     * 帶重試的單粒實際讀取。機身還有官方 alpha2services 還佔用 ttyS1，回覆 bytes
     * 會被搶食，偶發超時屬預期之內，故重試 3 次（官方 ACK 約 10-15ms，
     * 250ms timeout 好闊綽；重試之間隔 100ms，不要密 hammer 胸板）。
     */
    private Integer readServoLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoAngle(id, 250); }
        });
    }

    /**
     * 帶重試的 trim 寫入（3 次 × 250ms，與實際讀取同級——官方 service 爭食回覆
     * bytes 時超時常見）。回 TRUE/FALSE（ACK 語意）；全部超時／發送失敗回 null。
     */
    private Boolean writeServoTrimLive(final int id, final int trim) {
        return retryServoLive(30, new ServoRetry<Boolean>() {
            @Override public Boolean attempt() { return chestQuery.writeServoTrim(id, trim, 250); }
        });
    }
}


