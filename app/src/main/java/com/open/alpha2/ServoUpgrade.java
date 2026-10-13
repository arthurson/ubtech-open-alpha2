package com.open.alpha2;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.RobotWire;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 舵機韌體升級：胸板命令 16 開始／17 資料頁，結果由上行 134 回報。
 *
 * RE 依據：
 * - 官方 upgrade APK（2.003）反彙編：ChestCmds 數值（16／17／134 齊，同命令表一致）；
 *   embedded 升級引擎通用形（START＝[4B BE 長度]、PAGE 逐頁 ACK＋error≠0 即停、
 *   電量 ≥50 閘、per-page 重試）。但該 APK 無舵機升級任務，cmd16／17 逐 byte
 *   未有官方實現可對。
 * - 白牌 mega8 舵機 bootloader 反彙編：bus 層 FD DF … ED 幀、64-byte flash 頁、
 *   112 頁＝7168B app 區、命令 1＝寫頁／2＝查詢、67-byte 加總校驗。
 *
 * Chest 層格式（推斷，命令表信心「中」，真機未驗證——START 接受即逐步證實）：
 *   16 START [id][len BE32]，回覆 status 0＝接受（胸板 cmd48 同款＋id 行頭）。
 *   17 PAGE [id][0x01][seqHi][seqLo][64B]，回覆 status 0＝繼續（每頁 3 次重試）。
 *   上行 134 [status][id]，status 0＝成功（等 20 秒）。
 *
 * 硬閘（絕不妥協）：
 * - 固件檔必須恰好 7168 bytes（112×64）；其他大小一律拒（防半燒變磚）。
 * - 電量未知放行、已知需 ≥50（同官方 ConditionUtil.checkCondition(true, 50, -1)）。
 * - 一次只燒一粒；燒緊唔開得第二轉；可 abort（abort 唔保證舵機回復，只停後續頁）。
 *
 * 進度經 EventBus servo_upgrade_progress／servo_upgrade_done 發布，
 * 前端輪詢 servo/upgrade/status。檔案上載見 handleServoFwUpload。
 */
public final class ServoUpgrade {
    private static final String TAG = "ServoUpgrade";

    /** 升級用固件檔（上載／燒錄同一路徑，見 handleServoFwUpload／startServoUpgrade）。 */
    public static final String FIRMWARE_PATH = "/sdcard/AlphaII_SERVO_fw.bin";
    /** 唯一接受嘅固件大小：112 頁 × 64B（mega8 app 區實證）。 */
    public static final int FIRMWARE_SIZE = 7168;
    /** 每頁 64B（舵機 bootloader flash 頁實證）。 */
    public static final int PAGE_SIZE = 64;
    /** 總頁數 112（7168／64）。 */
    public static final int TOTAL_PAGES = 112;

    private final Context appContext;
    private final ChestQuery chestQuery;

    private volatile boolean upgradeInProgress = false;
    private volatile int upgradeProgress = 0;
    private volatile int upgradeTotalPages = TOTAL_PAGES;
    private volatile int upgradeCurrentPage = 0;
    private volatile int upgradeServoId = -1;
    private volatile String upgradeStatus = "idle";
    private volatile Thread upgradeThread;

    // 16/17 逐命令 ACK latch（同 ChestQuery 嗰套 cmd latch 一致：payload[0]==cmd）。
    private volatile CountDownLatch ackLatch;
    private volatile int ackExpectedCmd = -1;
    private volatile int ackStatus = -1;
    // 134 結果 latch。
    private volatile CountDownLatch resultLatch;
    private volatile int resultStatus = -1;
    private volatile int resultId = -1;
    private volatile String resultHex = null;

    public ServoUpgrade(Context context, ChestQuery chestQuery) {
        this.appContext = context.getApplicationContext();
        this.chestQuery = chestQuery;
    }

    /** Delegates to ChestQuery.chestReady(). */
    private boolean chestReady() {
        return chestQuery.chestReady();
    }

    /**
     * 認領 16／17 ACK 幀同 134 結果幀，由 MainActivity.onDirectChestFrame
     * 喺升級 ACK 之後、ChestQuery 之前調用（134 唔認領會跌入版本 fallback 誤食）。
     * @return true = 已認領／已消耗。
     */
    public boolean onFrame(byte[] frame, byte[] payload, int plen) {
        CountDownLatch latch = ackLatch;
        if (latch != null && latch.getCount() > 0 && plen >= 2
                && (payload[0] & 0xFF) == (ackExpectedCmd & 0xFF)
                && (ackExpectedCmd == 16 || ackExpectedCmd == 17)) {
            ackStatus = payload[1] & 0xFF;
            latch.countDown();
            return true;
        }
        if (plen >= 1 && (payload[0] & 0xFF) == RobotWire.CHEST_EVENT_MOTOR_UPGRADE) {
            resultStatus = plen >= 2 ? payload[1] & 0xFF : -1;
            resultId = plen >= 3 ? payload[2] & 0xFF : -1;
            resultHex = MainActivity.toHex(payload, plen);
            Log.i(TAG, "motor upgrade result status=" + resultStatus + " id=" + resultId + " raw=" + resultHex);
            EventBus.get().publish("servo_upgrade_result",
                    "{\"status\":" + resultStatus + ",\"id\":" + resultId + "}");
            CountDownLatch rlatch = resultLatch;
            if (rlatch != null && rlatch.getCount() > 0) rlatch.countDown();
            return true;
        }
        return false;
    }

    private boolean waitForServoAck(int expectedCmd, long timeoutMs) {
        ackExpectedCmd = expectedCmd;
        ackStatus = -1;
        CountDownLatch latch = new CountDownLatch(1);
        ackLatch = latch;
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (ackLatch != latch) return false;
            if (!ok) {
                Log.w(TAG, "servo upgrade ack timeout cmd=" + expectedCmd);
                return false;
            }
            if (ackStatus != 0) {
                Log.w(TAG, "servo upgrade cmd " + expectedCmd + " rejected status=" + ackStatus);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            ackLatch = null;
        }
    }

    private boolean waitForResult(long timeoutMs) {
        resultStatus = -1;
        resultId = -1;
        resultHex = null;
        CountDownLatch latch = new CountDownLatch(1);
        resultLatch = latch;
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (resultLatch != latch) return false;
            if (!ok) {
                Log.w(TAG, "servo upgrade result timeout (" + timeoutMs + "ms, waiting uplink 134)");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            resultLatch = null;
        }
    }

    private void resetState() {
        try {
            ackLatch = null;
            resultLatch = null;
            chestQuery.reset();
            Thread.sleep(400);
        } catch (Exception ignored) {}
    }

    /** 中止升級（servo/upgrade/abort）：停後續頁＋清狀態。注意：已寫入舵機 flash 嘅頁唔會回滾。 */
    public synchronized void abort() {
        CountDownLatch latch = ackLatch;
        ackLatch = null;
        if (latch != null) {
            while (latch.getCount() > 0) latch.countDown();
        }
        CountDownLatch rlatch = resultLatch;
        resultLatch = null;
        if (rlatch != null) {
            while (rlatch.getCount() > 0) rlatch.countDown();
        }
        resetState();
        upgradeInProgress = false;
        upgradeStatus = "aborted";
    }

    /** 真正升級線程：16([id][len BE32])->17*112([id][01][seq][64B])->等 134。 */
    private void doServoUpgradeFrom(final int servoId, final java.io.File file) {
        final int fileLen = (int) file.length();
        upgradeServoId = servoId;
        upgradeTotalPages = TOTAL_PAGES;
        upgradeCurrentPage = 0;
        upgradeProgress = 0;
        upgradeStatus = "start";
        EventBus.get().publish("servo_upgrade_progress",
                "{\"state\":\"start\",\"progress\":0,\"id\":" + servoId + ",\"total\":" + TOTAL_PAGES + "}");
        Log.i(TAG, "servo upgrade start id=" + servoId + " len=" + fileLen);
        resetState();
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        try {
            if (!chestReady()) { throw new Exception("chest not ready (pure-direct)"); }
            // 16 START（推斷格式 [id][len BE32]；拒收即停，乜都未燒）。
            upgradeStatus = "sending start";
            boolean startOk = false;
            for (int retry = 0; retry < 3; retry++) {
                boolean s = HardwareDirectManager.get(appContext).chest().startMotorUpdate((byte) servoId, fileLen);
                if (!s) { Thread.sleep(500); continue; }
                if (waitForServoAck(16, 3000)) { startOk = true; break; }
                Thread.sleep(300);
            }
            if (!startOk) throw new Exception("start rejected or ack timeout (cmd16 format unverified - see logcat)");
            Thread.sleep(150);
            upgradeStatus = "sending pages";
            // 17 PAGES — 112 頁 × 64B，每頁 3 次重試，頁間 30ms。
            byte[] fileBytes = new byte[fileLen];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < fileLen) {
                    int n = in.read(fileBytes, off, fileLen - off);
                    if (n <= 0) break;
                    off += n;
                }
                if (off != fileLen) throw new Exception("short read file");
            }
            for (int page = 0; page < TOTAL_PAGES; page++) {
                if (Thread.currentThread().isInterrupted()) throw new Exception("interrupted");
                byte[] pageData = java.util.Arrays.copyOfRange(fileBytes, page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
                boolean pageOk = false;
                for (int retry = 0; retry < 3; retry++) {
                    boolean s = HardwareDirectManager.get(appContext).chest()
                            .updateMotorPage((byte) servoId, page, pageData);
                    if (!s) { Thread.sleep(300); continue; }
                    if (waitForServoAck(17, 2000)) { pageOk = true; break; }
                    Log.w(TAG, "servo page " + page + " retry " + retry);
                    Thread.sleep(200);
                }
                if (!pageOk) {
                    throw new Exception("page " + page + " failed after 3 retries (flash may be partial - DO NOT power off, retry full upgrade)");
                }
                upgradeCurrentPage = page + 1;
                upgradeProgress = (upgradeCurrentPage * 100) / TOTAL_PAGES;
                EventBus.get().publish("servo_upgrade_progress",
                        "{\"state\":\"page\",\"page\":" + upgradeCurrentPage + ",\"total\":" + TOTAL_PAGES
                                + ",\"progress\":" + upgradeProgress + ",\"id\":" + servoId + "}");
                if (upgradeCurrentPage % 20 == 0) Log.i(TAG, "servo page " + upgradeCurrentPage + "/" + TOTAL_PAGES);
                Thread.sleep(30);
            }
            // 等上行 134 結果。
            upgradeStatus = "waiting result";
            if (!waitForResult(20000)) {
                throw new Exception("no uplink 134 result within 20s (pages all ACKed; verify with version read)");
            }
            if (resultStatus != 0 || resultId != servoId) {
                throw new Exception("servo reported failure status=" + resultStatus + " id=" + resultId + " raw=" + resultHex);
            }
            upgradeProgress = 100;
            upgradeStatus = "success";
            EventBus.get().publish("servo_upgrade_done", "{\"ok\":true,\"progress\":100,\"id\":" + servoId + "}");
            EventBus.get().publish("servo_upgrade_progress",
                    "{\"state\":\"success\",\"progress\":100,\"id\":" + servoId + "}");
            Log.i(TAG, "servo upgrade success id=" + servoId);
        } catch (Exception e) {
            upgradeStatus = "failed: " + e.getMessage();
            Log.w(TAG, "servo upgrade failed", e);
            EventBus.get().publish("servo_upgrade_done",
                    "{\"ok\":false,\"id\":" + servoId + ",\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
            EventBus.get().publish("servo_upgrade_progress",
                    "{\"state\":\"failed\",\"id\":" + servoId + ",\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        } finally {
            upgradeInProgress = false;
            upgradeThread = null;
        }
    }

    public synchronized String startServoUpgrade(int servoId) {
        if (upgradeInProgress) return "already running";
        if (servoId < ApiValidator.SERVO_ID_MIN || servoId > ApiValidator.SERVO_ID_MAX) {
            return "servo id must be 1-22";
        }
        java.io.File f = new java.io.File(FIRMWARE_PATH);
        if (!f.isFile()) return "file not found: " + FIRMWARE_PATH + " (upload first)";
        // 硬閘：恰好 7168 bytes 先收（112×64；其他即半燒變磚，直接拒）。
        if (f.length() != FIRMWARE_SIZE) {
            return "bad firmware size " + f.length() + " bytes, must be exactly " + FIRMWARE_SIZE + " (112x64)";
        }
        if (!isPowerEnoughForUpgrade()) {
            return "power not enough, need >=50%";
        }
        if (!chestReady()) {
            resetState();
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            if (!chestReady()) return "chest not ready (pure-direct)";
        }
        upgradeInProgress = true;
        upgradeProgress = 0;
        upgradeCurrentPage = 0;
        upgradeStatus = "starting id " + servoId;
        final int id = servoId;
        upgradeThread = new Thread(new Runnable() { @Override public void run() { doServoUpgradeFrom(id, new java.io.File(FIRMWARE_PATH)); } }, "ServoUpgrade");
        upgradeThread.start();
        return null;
    }

    public String getServoUpgradeStatusJson() {
        java.io.File f = new java.io.File(FIRMWARE_PATH);
        boolean fileExists = f.isFile();
        long fileSize = fileExists ? f.length() : 0;
        return "{\"inProgress\":" + upgradeInProgress + ",\"progress\":" + upgradeProgress
            + ",\"currentPage\":" + upgradeCurrentPage + ",\"totalPages\":" + upgradeTotalPages
            + ",\"servoId\":" + upgradeServoId
            + ",\"status\":\"" + MainActivity.jsonSafe(upgradeStatus) + "\""
            + ",\"fileExists\":" + fileExists
            + ",\"fileSizeBytes\":" + fileSize
            + ",\"wantSizeBytes\":" + FIRMWARE_SIZE + "}";
    }

    public HttpServer.ApiResponse servoUpgradeResponse(Map<String, String> query) {
        int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
        String err = startServoUpgrade(id);
        if (err == null) return HttpServer.ApiResponse.ok("{\"ok\":true,\"started\":true,\"id\":" + id + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(err) + "\"}");
    }

    public HttpServer.ApiResponse servoUpgradeStatusResponse() {
        return HttpServer.ApiResponse.ok("{\"ok\":true," + getServoUpgradeStatusJson().substring(1));
    }

    /** 舵機固件上載 — 只收恰好 7168 bytes app 鏡像（無 bootloader），寫入 /sdcard/AlphaII_SERVO_fw.bin。 */
    public HttpServer.ApiResponse handleServoFwUpload(Map<String, String> query, byte[] body) {
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.badRequest("empty file body");
        }
        if (body.length != FIRMWARE_SIZE) {
            return HttpServer.ApiResponse.badRequest("bad firmware size " + body.length
                + " bytes, must be exactly " + FIRMWARE_SIZE + " (app image without bootloader, 112x64)");
        }
        try {
            java.io.File dest = new java.io.File(FIRMWARE_PATH);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(body);
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + dest.getAbsolutePath() + "\",\"sizeBytes\":" + body.length + "}");
        } catch (Exception e) {
            Log.w(TAG, "Servo fw upload failed", e);
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /** 電量閘（同 ChestUpgrade 規則：未知放行，已知需 ≥50）。 */
    private boolean isPowerEnoughForUpgrade() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent b = appContext.registerReceiver(null, f);
            if (b == null) return true;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return true;
            return (level * 100) / scale >= 50;
        } catch (Exception e) { return true; }
    }
}
