package com.open.alpha2;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.Map;

/**
 * 胸口固件升級 (48/49/50 協議，鏡像 alpha2services h.a.a$b)。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第四刀)：升級狀態、
 * ACK 等待、升級線程、電量/MD5 幫手原本全部係 MainActivity 私有成員，
 * 搬過嚟邏輯不變。胸串口經 appContext 攞 HardwareDirectManager；
 * 查詢側 latch 共用 ChestQuery (升級開始前要一齊清)。
 * 進度經 EventBus chest_upgrade_progress / chest_upgrade_done 發布，
 * 前端輪詢 chest/upgrade/status。
 * 2026-09 dispatcher Phase 1 第六刀加：chest/page 调試讀頁
 * (chestPageResponse) 搬入——讀同一個升級鏡像檔。
 */
public final class ChestUpgrade {
    private static final String TAG = "ChestUpgrade";

    // 2026-08 新增: 胸口升級狀態 (48/49/50 協議，見 ag_chess/com/ubtechinc/h/a/a$b.java)
    // 單例升級線程，升級中 chestUpgradeInProgress=true，進度 0-100，前端經 EventBus chest_upgrade_progress / chest_upgrade_done 輪詢
    private volatile boolean chestUpgradeInProgress = false;
    private volatile int chestUpgradeProgress = 0;
    private volatile int chestUpgradeTotalPages = 0;
    private volatile int chestUpgradeCurrentPage = 0;
    private volatile String chestUpgradeStatus = "idle";
    private volatile CountDownLatch chestUpgradeLatch;
    private volatile byte chestUpgradeExpectedCmd = 0;
    private volatile int chestUpgradeAckStatus = -1;
    private volatile Thread chestUpgradeThread;

    private final Context appContext;
    private final ChestQuery chestQuery;

    public ChestUpgrade(Context context, ChestQuery chestQuery) {
        this.appContext = context.getApplicationContext();
        this.chestQuery = chestQuery;
    }

    private boolean chestReady() {
        try {
            return HardwareDirectManager.get(appContext).chest().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 認領升級 ACK 幀 (48/49/50)，由 MainActivity.onDirectChestFrame 最優先調用
     * (升級 ACK > UUID latch > 版本 latch，順序不變)。
     * @return true = 已認領 (調用方應直接 return)。
     */
    public boolean onAckFrame(byte[] payload, int plen) {
        // 2026-09-09：local copy + null-check（abort 會置 null，check-then用 race 會 NPE）。
        java.util.concurrent.CountDownLatch latch = chestUpgradeLatch;
        if (latch == null || latch.getCount() <= 0 || plen < 1) return false;
        byte cmd = payload[0];
        if (cmd == chestUpgradeExpectedCmd) {
            if (cmd == 49) {
                chestUpgradeAckStatus = (plen >= 2 ? (payload[1] & 0xFF) : 0);
            } else {
                chestUpgradeAckStatus = 0;
            }
            latch.countDown();
            return true;
        }
        return false;
    }

    private int getBatteryPercentForUpgrade() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent b = appContext.registerReceiver(null, f);
            if (b == null) return -1;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level < 0 || scale <= 0) return charging ? 100 : -1;
            // 2026-09-09：充緊電都要睇實際電量（之前充緊即回 100% 繞過 50% 門檻，
            // 升級途中拔電掉電即變磚）；電量讀唔到先當 100% 放行。
            return (level * 100) / scale;
        } catch (Exception e) { return -1; }
    }

    private boolean isPowerEnoughForUpgrade() {
        int pct = getBatteryPercentForUpgrade();
        return pct < 0 || pct >= 50; // 未知時放行，已知需 >=50，與 AlphaMainSeviceImpl.java:13 MIN_UPDATE_POWER 一致
    }

    private byte[] md5OfFile(java.io.File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return md.digest();
    }

    private boolean waitForChestAck(byte expectedCmd, long timeoutMs) {
        chestUpgradeExpectedCmd = expectedCmd;
        chestUpgradeAckStatus = -1;
        CountDownLatch latch = new CountDownLatch(1);
        chestUpgradeLatch = latch;
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            // 2026-09-09：唔係自己呢代 latch（abort/retry 清過）即係唔好再行，
            // 回 false 等上層 throw 退出（唔係 abort 早醒會當成功繼續燒）。
            if (chestUpgradeLatch != latch) return false;
            if (!ok) {
                byte[] lastVer = chestQuery.getLastVersionRaw();
                Log.w(TAG, "chest upgrade ack timeout cmd=" + expectedCmd + " raw=" + (lastVer!=null?MainActivity.toHex(lastVer,lastVer.length):"null"));
                // 超時後印最近一次 chest_rcv 原始幀以便診斷 170 頁這類數據校驗失敗
                return false;
            }
            if (expectedCmd == 49 && chestUpgradeAckStatus != 0) {
                Log.w(TAG, "chest page ack status=" + chestUpgradeAckStatus + " (page data may be rejected, check offset " + (chestUpgradeCurrentPage*128) + ")");
                return false;
            }
            return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { chestUpgradeLatch = null; }
    }

    private void resetState() {
        try {
            chestUpgradeLatch = null;
            chestQuery.reset();
            Thread.sleep(400);
        } catch (Exception ignored) {}
    }

    /** 中止升級 (chest/upgrade/abort)：清狀態 + 標 aborted，和以前三行 inline 一致。 */
    public synchronized void abort() {
        // 2026-09-09：countDown 叫醒 waitForChestAck（之前唔叫，等足成個 timeout
        // 先醒，中止唔即時）。
        java.util.concurrent.CountDownLatch latch = chestUpgradeLatch;
        chestUpgradeLatch = null;
        if (latch != null) {
            while (latch.getCount() > 0) latch.countDown();
        }
        resetState();
        chestUpgradeInProgress = false;
        chestUpgradeStatus = "aborted";
    }

    /** 真正升級線程：48(檔長)->49*2048頁(128B)->50(MD5)，鏡像 h.a.a$b:63，加入重啟後首頁即失敗的復位 */
    private void doChestUpgradeFrom(final java.io.File file, final int startPage) {
        final int fileLen = (int) file.length();
        final int totalPages = (fileLen + 127) / 128;
        chestUpgradeTotalPages = totalPages;
        chestUpgradeCurrentPage = 0;
        chestUpgradeProgress = 0;
        chestUpgradeStatus = "start";
        EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"start\",\"progress\":0,\"total\":"+totalPages+"}");
        Log.i(TAG, "chest upgrade start len=" + fileLen + " pages=" + totalPages);
        // 起始前強制復位，避免重啟後首頁即 01 失敗（殘留升級態）
        resetState();
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        try {
            // pure-direct: 升级经 /dev/ttyS1 直发 48/49/50。
            if (!chestReady()) { throw new Exception("chest not ready (pure-direct)"); }
            // 48 START — 若連續3次仍 01，嘗試先發 END 清狀態再重試
            chestUpgradeStatus = "sending start";
            boolean startOk = false;
            for (int retry = 0; retry < 3; retry++) {
                boolean s = HardwareDirectManager.get(appContext).chest().startUpdate(fileLen);
                if (!s) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)48, 5000)) { startOk = true; break; }
                if (retry == 1) { Log.w(TAG, "start retry with reset"); resetState(); try{Thread.sleep(600);}catch(Exception ignored){} }
            }
            if (!startOk) throw new Exception("start ack timeout");
            Thread.sleep(150); // 原廠線程無連發，給 MCU 準備
            chestUpgradeStatus = "sending pages";
            // 49 PAGES — 每頁間 30ms 間隔，避免連發撞上心跳 8d 幀；失敗頁會完整印 hex 供定位 170 頁這類點
            // 若 startPage>0，跳過前面已成功的頁（斷點續傳，解決 170 頁後重試首頁即 01）
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                // 先跳過 startPage*128 字節
                if (startPage > 0) {
                    long toSkip = (long) startPage * 128L;
                    long skipped = 0;
                    while (skipped < toSkip) {
                        long n = in.skip(toSkip - skipped);
                        if (n <= 0) break;
                        skipped += n;
                    }
                    Log.i(TAG, "resume from page " + startPage + " skipped=" + skipped);
                }
                byte[] pageBuf = new byte[128];
                int pageIdx = startPage;
                int read;
                while ((read = in.read(pageBuf, 0, 128)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new Exception("interrupted");
                    byte[] sendBuf = java.util.Arrays.copyOf(pageBuf, read);
                    boolean pageOk = false;
                    for (int retry = 0; retry < 3; retry++) {
                        boolean s = HardwareDirectManager.get(appContext).chest().updatePage(sendBuf, read);
                        if (!s) { Thread.sleep(300); continue; }
                        if (waitForChestAck((byte)49, 4000)) { pageOk = true; break; }
                        Log.w(TAG, "page " + pageIdx + " retry " + retry + " dataHead=" + MainActivity.toHex(sendBuf, Math.min(16,read)));
                        Thread.sleep(200);
                    }
                    if (!pageOk) {
                        Log.e(TAG, "page " + pageIdx + " failed data=" + MainActivity.toHex(sendBuf, Math.min(32,read)) + " offset=" + (pageIdx*128));
                        throw new Exception("page " + pageIdx + " failed after 3 retries");
                    }
                    pageIdx++;
                    chestUpgradeCurrentPage = pageIdx;
                    chestUpgradeProgress = (pageIdx * 100) / totalPages;
                    EventBus.get().publish("chest_upgrade_progress",
                        "{\"state\":\"page\",\"page\":"+pageIdx+",\"total\":"+totalPages+",\"progress\":"+chestUpgradeProgress+"}");
                    if (pageIdx % 20 == 0) Log.i(TAG, "chest page " + pageIdx + "/" + totalPages + " " + chestUpgradeProgress + "%");
                    Thread.sleep(30);
                }
            }
            // 50 END (MD5)
            chestUpgradeStatus = "sending end";
            byte[] md5 = md5OfFile(file);
            Log.i(TAG, "chest upgrade md5 " + MainActivity.toHex(md5, md5.length));
            boolean endOk = false;
            for (int retry = 0; retry < 3; retry++) {
                boolean s = HardwareDirectManager.get(appContext).chest().endUpdate(md5);
                if (!s) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)50, 5000)) { endOk = true; break; }
            }
            if (!endOk) throw new Exception("end ack timeout");
            chestUpgradeProgress = 100;
            chestUpgradeStatus = "success";
            EventBus.get().publish("chest_upgrade_done", "{\"ok\":true,\"progress\":100}");
            Log.i(TAG, "chest upgrade success");
            // 成功後由用戶手動重啟或自動重啟（alpha2services 原流程會重啟）
            EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"success\",\"progress\":100}");
        } catch (Exception e) {
            chestUpgradeStatus = "failed: " + e.getMessage();
            Log.w(TAG, "chest upgrade failed", e);
            EventBus.get().publish("chest_upgrade_done", "{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
            EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"failed\",\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        } finally {
            chestUpgradeInProgress = false;
            chestUpgradeThread = null;
        }
    }

    public synchronized String startChestUpgrade() { return startChestUpgradeFrom(0); }
    public synchronized String startChestUpgradeFrom(int startPage) {
        if (chestUpgradeInProgress) return "already running";
        java.io.File f = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
        if (!f.exists()) return "file not found: /sdcard/AlphaII_CHEST_kernel.bin";
        if (f.length() != 262144) Log.w(TAG, "chest file size unusual: " + f.length());
        if (!isPowerEnoughForUpgrade()) {
            int pct = getBatteryPercentForUpgrade();
            return "power not enough (" + pct + "%), need >=50%";
        }
        // pure-direct: 就绪即直驱串口可用，不再 waitChestReady()/binder。
        if (!chestReady()) {
            resetState();
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            if (!chestReady()) return "chest not ready (pure-direct)";
        }
        if (chestUpgradeStatus.startsWith("failed")) {
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            resetState();
        }
        chestUpgradeInProgress = true;
        chestUpgradeProgress = startPage * 100 / ((int)(f.length()+127)/128);
        chestUpgradeCurrentPage = startPage;
        chestUpgradeStatus = "starting from " + startPage;
        final int sp = startPage;
        chestUpgradeThread = new Thread(new Runnable() { @Override public void run() { doChestUpgradeFrom(f, sp); } }, "ChestUpgrade");
        chestUpgradeThread.start();
        return null;
    }
// (2026-09: 兼容舊 doChestUpgrade(File) 轉調已刪 - 全部 caller 直接用
// doChestUpgradeFrom(file, startPage)。)

    public String getChestUpgradeStatusJson() {
        return "{\"inProgress\":" + chestUpgradeInProgress + ",\"progress\":" + chestUpgradeProgress
            + ",\"currentPage\":" + chestUpgradeCurrentPage + ",\"totalPages\":" + chestUpgradeTotalPages
            + ",\"status\":\"" + MainActivity.jsonSafe(chestUpgradeStatus) + "\"}";
    }

    // 調試：讀指定頁 offset 的 32B hex，用於定位 170 頁這類點
    // (2026-09 dispatcher Phase 1 第六刀由 handleApi chest/page 搬入)。
    public HttpServer.ApiResponse chestPageResponse(Map<String, String> query) {
        int page = ApiValidator.requireInt(query, "page");
        java.io.File f = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
        if (!f.exists()) return HttpServer.ApiResponse.error("file not found");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            long skip = (long)page * 128L;
            long s = 0;
            while (s < skip) { long n = in.skip(skip - s); if (n<=0) break; s+=n; }
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n <= 0) return HttpServer.ApiResponse.error("page out of range");
            String hex = MainActivity.toHex(buf, n);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"page\":"+page+",\"offset\":"+skip+",\"hex\":\""+hex+"\"}");
        } catch (Exception e) { return HttpServer.ApiResponse.error(String.valueOf(e.getMessage())); }
    }

    /** 胸板固件上載 - 接收 256KB 的 ALPHA2Q-CHEST-*.bin，寫入 /sdcard/AlphaII_CHEST_kernel.bin
     *  (2026-09 小件拼盤由 MainActivity.handleChestUpload 搬入——升級鏡像入口歸升級層)。 */
    public HttpServer.ApiResponse handleChestUpload(Map<String, String> query, byte[] body) {
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.badRequest("empty file body");
        }
        // 2026-09-09：大細唔啱直接 400 唔寫入（之前只 warn 照寫，壞 bin 會留喺度，
        // 下次升級攞錯檔即變磚；magic 無文件記載唔驗，靠升級時 MCU ACK 把關）。
        if (body.length != 256 * 1024) {
            return HttpServer.ApiResponse.badRequest("chest firmware must be 262144 bytes, got " + body.length);
        }
        try {
            java.io.File dest = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(body);
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + dest.getAbsolutePath() + "\",\"sizeBytes\":" + body.length + "}");
        } catch (Exception e) {
            Log.w(TAG, "Chest upload failed", e);
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
        }
    }
}
