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
    /**
     * cmd12 trim 寫入 EEPROM 嘅 ACK 實測好慢：官方 PC tuner log 見到首個
     * ACK 約 2 秒、整批 20 軸可拖到 7–8 秒。單粒寫入唔好再用 250ms 密集
     * 重試（會同 EEPROM 写入互相踩），一次過等夠。
     */
    private static final long SERVO_TRIM_ACK_TIMEOUT_MS = 7000;
    /** 官方校準流程：全組 cmd12 送完後等 EEPROM，再送 cmd05 回標準姿勢。 */
    private static final long SERVO_TRIM_SETTLE_MS = 7000;
    /** 官方 home 係逐粒 cmd05（log 09:56:37-38），唔係 cmd03；留少少 gap 畀胸板轉發。 */
    private static final long SERVO_HOME_GAP_MS = 50;

    private final Context appContext;
    private final UbxPlayer ubxPlayer;
    private final ActionDirect actionDirect;
    private final ChestQuery chestQuery;
    /** 連續拖 slider 會連發 ubx/speed；序列化 stop+restart，免兩個請求互相截停。 */
    private final Object speedLock = new Object();

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
        synchronized (speedLock) {
            float f = ApiValidator.parseUbxSpeedValue(v);
            if (!ubxPlayer.setSpeed(f)) {
                throw new IllegalArgumentException("parameter 'value' must be between 0.5 and 2.0, got: " + v);
            }
            // 正在播時即時生效：用新速度由頭重播同一文件（内部快照隔离，旧计划安全交接）。
            boolean restarted = false;
            java.io.File last = actionDirect.getLastPlayedFile();
            if (ubxPlayer.isPlaying() && !ubxPlayer.isWalking() && last != null && last.isFile()) {
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

    /**
     * 開機企姿／START 握手未完成之前，拒絕會令舵機郁嘅請求。
     * 避免機械人死機 reboot 後，舊瀏覽器 tab 嘅 IK 動畫／還原表單值
     * 將上一次 pose 當成新指令，一開機就「自己做返之前既動作」。
     */
    private static HttpServer.ApiResponse bootServoBlocked() {
        if (MainActivity.isBootServoReady()) return null;
        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"booting\"}");
    }

    public HttpServer.ApiResponse servoOneResponse(Map<String, String> query) {
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
        // pure-direct: 單舵機經 cmd05 直發。
        // 可選 trim 參數——有帶就**先**經 cmd12 寫入 chest EEPROM，再送 cmd05。
        // 官方 PC tuner 係 trim 先、movement 後；掉轉會令新 trim 未 apply 就
        // 用舊校準行，或者行完才 apply 變成雙重偏移。
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
            if (trim < ApiValidator.SERVO_TRIM_MIN || trim > ApiValidator.SERVO_TRIM_MAX) {
                throw new IllegalArgumentException("parameter 'trim' must be within ["
                        + ApiValidator.SERVO_TRIM_MIN + "," + ApiValidator.SERVO_TRIM_MAX + "]");
            }
        }
        Boolean written = null;
        if (trim != null) {
            // 先寫 trim 並等 EEPROM/ACK（最長 7 秒），之後嘅 cmd05 一定會用新校準。
            written = writeServoTrimLive(id, trim);
        }
        boolean ok = servoSendOneCode(id, angle, time) == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        if (!ok || trim == null) {
            return MainActivity.sentReadyResponse(ok, directChestReady());
        }
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
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
        int[] angles = ApiValidator.requireAngles20(query);
        int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 1000);
        // setAllServos 按版本：2-mic 官方 cmd52／5-mic cmd03。
        boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(angles, (short) time);
        if (sent) ubxPlayer.notePose(angles);
        return MainActivity.sentReadyResponse(sent, directChestReady());
    }

    /**
     * 官方 ServoCalibration 1.0.0.4 校準流程（Desktop log 實測）：
     * 1) 一次過送全部有效 cmd12 trim（唔逐粒等 ACK）；
     * 2) 等 EEPROM 寫入／ACK 沉澱（官方約 7 秒）；
     * 3) 再送 cmd05 全部舵機回標準姿勢，等新 offset 以 home 位呈現。
     *
     * <p>request: trims=20 或 22 個 comma-separated signed trim，空格代表
     * 該軸跳過（未掃描／無回授）；time=回 home 動作時間（預設 2000ms）。
     * 呢個 endpoint 只寫已掃描嘅軸，唔會用 0 覆蓋未讀到嘅出廠 trim。</p>
     */
    public HttpServer.ApiResponse servoCalibrateResponse(Map<String, String> query) {
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
        Integer[] trims = ApiValidator.optionalTrims20or22(query);
        if (trims == null) {
            throw new IllegalArgumentException("parameter 'trims' is required");
        }
        int time = ApiValidator.optionalIntRange(query, "time",
                ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 2000);
        int expected = 0;
        for (Integer t : trims) if (t != null) expected++;
        if (expected == 0) {
            throw new IllegalArgumentException("parameter 'trims' must contain at least one value");
        }
        HardwareDirectManager dm = HardwareDirectManager.get(appContext);
        if (!dm.chest().isAvailable()) {
            return HttpServer.ApiResponse.error("direct not ready");
        }
        StringBuilder writtenIds = new StringBuilder("[");
        StringBuilder failedIds = new StringBuilder("[");
        int sentCount = 0, failedCount = 0;
        for (int i = 0; i < trims.length; i++) {
            Integer t = trims[i];
            if (t == null) continue;
            boolean sent = dm.chest().writeServoTrim((byte) (i + 1), t.intValue());
            if (sent) {
                if (sentCount > 0) writtenIds.append(',');
                writtenIds.append(i + 1);
                sentCount++;
            } else {
                if (failedCount > 0) failedIds.append(',');
                failedIds.append(i + 1);
                failedCount++;
            }
        }
        if (sentCount == 0) {
            return HttpServer.ApiResponse.error("trim write failed (direct not ready)");
        }
        // 官方 tuner 喺呢個位等約 7 秒（log 2026-09-07 09:56:30→09:56:37）。
        try {
            Thread.sleep(SERVO_TRIM_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int[] homes = new int[ApiValidator.SERVO_COUNT];
        for (int i = 0; i < homes.length; i++) homes[i] = ApiValidator.servoCalHome(i + 1);
        // 官方 log 係逐粒 cmd05（09:56:37-38），唔係一次過 cmd03/52。
        // cmd03/52 唔會令新寫入嘅 trim 即時反映到物理角度；cmd05 才會。
        int homeSentCount = 0;
        for (int i = 0; i < homes.length; i++) {
            if (dm.chest().setSingleServo((byte) (i + 1), homes[i], (short) time)) homeSentCount++;
            if (i + 1 < homes.length) {
                try {
                    Thread.sleep(SERVO_HOME_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        boolean homeSent = homeSentCount == homes.length;
        Log.i(TAG, "servoCalibrate trims=" + expected + " sent=" + sentCount
                + " failed=" + failedCount + " homeCmd05=" + homeSentCount + "/" + homes.length);
        if (homeSent) ubxPlayer.notePose(homes);
        StringBuilder sb = new StringBuilder("{\"ok\":").append(homeSent);
        sb.append(",\"code\":\"").append(homeSent ? "API_ERROR_SUCCEED" : "API_ERROR_FAILED").append('"');
        sb.append(",\"bindReady\":").append(directChestReady());
        sb.append(",\"trimCount\":").append(expected);
        sb.append(",\"trimSent\":").append(sentCount);
        sb.append(",\"trimWritten\":").append(writtenIds).append(']');
        sb.append(",\"trimFailed\":").append(failedIds).append(']');
        sb.append(",\"homeSent\":").append(homeSent);
        sb.append(",\"homeSentCount\":").append(homeSentCount);
        sb.append(",\"time\":").append(time);
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
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

    /** 22 連讀粒間隔 100ms（servoReadAll／servoAngleAll 逐字一樣；被打斷回 false 給 caller break）。 */
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
        // 22 連讀 trim（官方 tuner 節奏：逐顆約十幾 ms 間隔）。
        // trims[i] = 該軸 chest 存住的偏差原值，讀不到那顆記 null 並列入 failed。
        // 注意：這裡不是絕對角度，不要拿去填 angle 輸入格。
        // 逐顆之間一律等 100ms（成功失敗都等）。
        Integer[] trims = new Integer[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            Integer v = readServoLive(i);
            trims[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"trims\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(trims[i] != null ? trims[i] : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAngleResponse(Map<String, String> query) {
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
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
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
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

    public HttpServer.ApiResponse servoBackAngleResponse(Map<String, String> query) {
        // 單舵機回讀角度實際讀取 (cmd 23 / 0x17，PC 工具「讀角度 6」同款)。
        // 同 servo/one 同單位。注意不是 servo/read 那個 trim/偏差。
        // 讀不到（超時）如實報 ok:false，不編 0。
        // 注意：0x17 讀會唔會鬆力未知（0x06 會）——首版純讀唔寫回，實測睇住部機先。
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        Integer angle = readServoBackLive(idInt);
        if (angle == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + idInt
                + ",\"angle\":" + angle + "}");
    }

    public HttpServer.ApiResponse servoModifyIdResponse(java.util.Map<String, String> query) {
        HttpServer.ApiResponse boot = bootServoBlocked();
        if (boot != null) return boot;
        int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        int newId = ApiValidator.requireIntRange(query, "newId", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        if (id == newId) {
            throw new IllegalArgumentException("parameter 'newId' must differ from 'id', got: " + newId);
        }
        if (chestQuery == null || !directChestReady()) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id
                    + ",\"newId\":" + newId + ",\"error\":\"direct not ready\"}");
        }
        Boolean ack = chestQuery.modifyServoId(id, newId, 800);
        if (ack != null && ack) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"id\":" + id + ",\"newId\":" + newId + ",\"ack\":true}");
        }
        if (ack != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id + ",\"newId\":" + newId
                    + ",\"ack\":false,\"error\":\"servo NAK (no feedback for id " + id + ")\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + id + ",\"newId\":" + newId
                + ",\"error\":\"timeout (sent, no chest ack; verify by moving new ID)\"}");
    }

    /**
     * Best-effort parse of a cmd14 reply payload into a version string.
     * Layout is UNVERIFIED (first real-device reads decide): skip payload[0]
     * (cmd echo), trim trailing 0x00, then longest printable ASCII run.
     * Returns null when nothing string-like is found (caller still has rawHex).
     */
    static String parseMotorVersion(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        int n = payload.length;
        int end = n;
        while (end > 1 && payload[end - 1] == 0) end--;
        if (end <= 1) return null;
        int bestStart = -1, bestLen = 0, curStart = -1;
        for (int i = 1; i <= end; i++) {
            boolean printable = i < end && (payload[i] & 0xFF) >= 0x20 && (payload[i] & 0xFF) <= 0x7E;
            if (printable) {
                if (curStart == -1) curStart = i;
            } else if (curStart != -1) {
                if (i - curStart > bestLen) { bestLen = i - curStart; bestStart = curStart; }
                curStart = -1;
            }
        }
        // Single stray printable bytes (e.g. #1 sample "0e 00 01 30 17 04 06"
        // where 0x30='0' is likely binary, not text) must not become fake
        // versions: require at least 2 consecutive printable chars.
        if (bestLen < 2) return null;
        try {
            return new String(payload, bestStart, bestLen, java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** cmd14 short error frame [0e, 01, id] = servo no feedback (same convention
     *  as cmd13 read / cmd12 trim error frames; seen on 5/6 faulty, 17/18 finger,
     *  21/22 absent servos). Real-device verified 2026-10-12. */
    static boolean isMotorVersionError(byte[] payload) {
        return payload != null && payload.length == 3 && (payload[0] & 0xFF) == 14 && payload[1] == 1;
    }

    /** Servo model from version-field byte0 (user-confirmed ground truth,
     *  real-device 2026-10-13: all 8kg servos reply B0=0x53, all 20kg B0=0x30).
     *  8kg = [2,3,5,6,17,18,19,20]; 20kg = [1,4,7-16]; 21/22 unknown (absent).
     *  Returns "8kg"/"20kg", or null when undecodable (incl. error frames). */
    static String motorModel(byte[] payload) {
        if (payload == null || payload.length < 4 || isMotorVersionError(payload)) return null;
        int b0 = payload[3] & 0xFF;
        if (b0 == 0x53) return "8kg";
        if (b0 == 0x30) return "20kg";
        return null;
    }

    /** Hex of the version field (bytes after cmd/status/id), shown until the
     *  4-byte layout (e.g. "30 17 04 06") is decoded against official data. */
    static String motorVersionFieldHex(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < payload.length; i++) {
            if (i > 3) sb.append(' ');
            int v = payload[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    static String toHexBytes(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            int v = b[i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    public HttpServer.ApiResponse servoVersionResponse(java.util.Map<String, String> query) {
        // Single servo firmware version (chest cmd14 live query, pure read).
        // Reply layout unverified: response carries both parsed version (best
        // effort, may be null) and rawHex (authoritative until verified).
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(idInt, 500);
        if (raw == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        if (isMotorVersionError(raw)) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"rawHex\":\"" + toHexBytes(raw) + "\""
                    + ",\"error\":\"no feedback (servo error frame - faulty/disconnected servo)\"}");
        }
        String ver = parseMotorVersion(raw);
        if (ver == null || ver.isEmpty()) ver = motorVersionFieldHex(raw);
        String model = motorModel(raw);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":" + idInt);
        sb.append(",\"rawHex\":\"").append(toHexBytes(raw)).append("\"");
        sb.append(",\"model\":").append(model != null ? ("\"" + model + "\"") : "null");
        if (ver != null && !ver.isEmpty()) sb.append(",\"version\":\"").append(MainActivity.jsonSafe(ver)).append("\"");
        else sb.append(",\"version\":null");
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoVersionAllResponse() {
        // One-click read of all 22 servo firmware versions (cmd14, pure read).
        // First pass single attempt per servo (500ms) with 100ms gaps, then one
        // retry pass over failed ids only; persistent failures go into failed
        // with null slots (same shape as servo/read-all).
        String[] versions = new String[ApiValidator.SERVO_COUNT];
        String[] raws = new String[ApiValidator.SERVO_COUNT];
        String[] models = new String[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(i, 500);
            if (raw == null || isMotorVersionError(raw)) {
                versions[i - 1] = null;
                raws[i - 1] = raw != null ? toHexBytes(raw) : null;
                if (!firstFail) failed.append(',');
                failed.append(i);
                firstFail = false;
            } else {
                String ver = parseMotorVersion(raw);
                versions[i - 1] = (ver != null && !ver.isEmpty()) ? ver : motorVersionFieldHex(raw);
                raws[i - 1] = toHexBytes(raw);
                models[i - 1] = motorModel(raw);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
            // One retry pass over failed ids only (servo bus reads are flaky:
            // 12/13/14/16 answered one scan and error-framed the next, real-device
            // 2026-10-13). Keeps the common all-good case at single-pass speed.
            java.util.ArrayList<Integer> retryIds = new java.util.ArrayList<Integer>();
            for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                if (versions[i - 1] == null) retryIds.add(i);
            }
            if (!retryIds.isEmpty()) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int id : retryIds) {
                    if (Thread.currentThread().isInterrupted()) break;
                    byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorVersionRaw(id, 500);
                    if (raw != null && !isMotorVersionError(raw)) {
                        String v2 = parseMotorVersion(raw);
                        versions[id - 1] = (v2 != null && !v2.isEmpty()) ? v2 : motorVersionFieldHex(raw);
                        raws[id - 1] = toHexBytes(raw);
                        models[id - 1] = motorModel(raw);
                    }
                }
                failed = new StringBuilder("[");
                firstFail = true;
                for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                    if (versions[i - 1] == null) {
                        if (!firstFail) failed.append(',');
                        failed.append(i);
                        firstFail = false;
                    }
                }
            }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"versions\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(versions[i] != null ? ("\"" + MainActivity.jsonSafe(versions[i]) + "\"") : "null");
        }
        sb.append("],\"rawHex\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(raws[i] != null ? ("\"" + raws[i] + "\"") : "null");
        }
        sb.append("],\"models\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(models[i] != null ? ("\"" + models[i] + "\"") : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoHardwareResponse(java.util.Map<String, String> query) {
        // Single servo hardware info (chest cmd33 live query, pure read).
        // Reply layout unverified: hw is best-effort (may be null), rawHex authoritative.
        int idInt = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(idInt, 500);
        if (raw == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"error\":\"no feedback (timeout)\"}");
        }
        if (raw.length == 3 && raw[1] == 1) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"id\":" + idInt
                    + ",\"rawHex\":\"" + toHexBytes(raw) + "\""
                    + ",\"error\":\"no feedback (servo error frame - unsupported or faulty servo)\"}");
        }
        String hw = parseMotorVersion(raw);
        if (hw == null || hw.isEmpty()) hw = motorVersionFieldHex(raw);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":" + idInt);
        sb.append(",\"rawHex\":\"").append(toHexBytes(raw)).append("\"");
        if (hw != null && !hw.isEmpty()) sb.append(",\"hw\":\"").append(MainActivity.jsonSafe(hw)).append("\"");
        else sb.append(",\"hw\":null");
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoHardwareAllResponse() {
        // One-click read of all 22 servo hardware infos (cmd33, pure read).
        // First pass single attempt per servo (500ms) with 100ms gaps, then one
        // retry pass over failed ids only.
        String[] hws = new String[ApiValidator.SERVO_COUNT];
        String[] raws = new String[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(i, 500);
            if (raw == null) {
                hws[i - 1] = null;
                raws[i - 1] = null;
                if (!firstFail) failed.append(',');
                failed.append(i);
                firstFail = false;
            } else {
                String hw = parseMotorVersion(raw);
                hws[i - 1] = (hw != null && !hw.isEmpty()) ? hw : motorVersionFieldHex(raw);
                raws[i - 1] = toHexBytes(raw);
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        java.util.ArrayList<Integer> retryIds = new java.util.ArrayList<Integer>();
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            if (hws[i - 1] == null) retryIds.add(i);
        }
        if (!retryIds.isEmpty()) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int id : retryIds) {
                if (Thread.currentThread().isInterrupted()) break;
                byte[] raw = (chestQuery == null) ? null : chestQuery.queryMotorHardwareRaw(id, 500);
                if (raw != null) {
                    String hw = parseMotorVersion(raw);
                    hws[id - 1] = (hw != null && !hw.isEmpty()) ? hw : motorVersionFieldHex(raw);
                    raws[id - 1] = toHexBytes(raw);
                }
            }
            failed = new StringBuilder("[");
            firstFail = true;
            for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
                if (hws[i - 1] == null) {
                    if (!firstFail) failed.append(',');
                    failed.append(i);
                    firstFail = false;
                }
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"hws\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(hws[i] != null ? ("\"" + MainActivity.jsonSafe(hws[i]) + "\"") : "null");
        }
        sb.append("],\"rawHex\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(raws[i] != null ? ("\"" + raws[i] + "\"") : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoBackAngleAllResponse() {
        // 22 連讀回讀角度 (cmd 23 / 0x17)：逐顆單發、無間隔、唔重試、唔寫回。
        // 實測 20 粒 230ms 全中（16 有數＋5/6/17/18 短 error 幀），唔使等；
        // 真機 2026-10-14 證實 21/22 都有回授（51/5），故連 21/22 一齊讀。
        // 讀不到那顆記 null 入 failed。
        // 讀取時請固定好機械人（0x17 會唔會鬆力未知，首版純讀）。
        final int n = ApiValidator.SERVO_COUNT;
        Integer[] angles = new Integer[n];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= n; i++) {
            Integer v = (chestQuery == null) ? null : chestQuery.queryServoBackAngle(i, 250);
            angles[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"angles\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(angles[i] != null ? angles[i] : "null");
        }
        sb.append("],\"failed\":").append(failed).append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse servoAngleAllResponse() {
        // 22 連讀＋逐顆即寫回（每粒讀寫原子，粒與粒之間隔 100ms）。
        // 背景：純連讀（不寫回）已被兩部機證實會逐顆弄垮出力；但單粒讀＋即寫回
        // 已證實無鬆無動，故連讀版都是同一個原子操作逐顆做。讀不到那顆不寫回、
        // 記 null 入 failed。未知能否全程站穩——實測中。
        Integer[] angles = new Integer[ApiValidator.SERVO_COUNT];
        boolean[] restored = new boolean[ApiValidator.SERVO_COUNT];
        StringBuilder failed = new StringBuilder("[");
        boolean firstFail = true;
        for (int i = 1; i <= ApiValidator.SERVO_COUNT; i++) {
            Integer v = readServoAbsLive(i);
            angles[i - 1] = v;
            if (v == null) {
                firstFail = appendFailed(failed, firstFail, i);
            } else {
                restored[i - 1] = servoSendOneCode(i, v, 20)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
            }
            if (i < ApiValidator.SERVO_COUNT) {
                if (!sleepBetweenServos()) break;
            }
        }
        failed.append(']');
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"live\":true,\"angles\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(angles[i] != null ? angles[i] : "null");
        }
        sb.append("],\"restored\":[");
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
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

    /**
     * 帶重試的單粒回讀角度實際讀取（cmd 23 / 0x17，3 次 × 250ms；重試之間隔
     * 100ms，同連讀同級，不要密 hammer 胸板）。
     */
    private Integer readServoBackLive(final int id) {
        return retryServoLive(100, new ServoRetry<Integer>() {
            @Override public Integer attempt() { return chestQuery.queryServoBackAngle(id, 250); }
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
     * 單粒 trim 寫入：官方 cmd12 ACK 實測 2–8 秒，唔可以再用 250ms 密集
     * 重試（會同 EEPROM 寫入互相踩）。呢度一次過等夠；true/false = MCU
     * ACK 語意，null = 超時／發送失敗（可能其實已寫入，前端要重掃驗證）。
     */
    private Boolean writeServoTrimLive(final int id, final int trim) {
        if (chestQuery == null || !directChestReady()) return null;
        return chestQuery.writeServoTrim(id, trim, SERVO_TRIM_ACK_TIMEOUT_MS);
    }
}


