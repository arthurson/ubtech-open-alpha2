package com.open.alpha2;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.Map;

/**
 * 胸口固件升級 (48/49/50 協議，逐 byte 對過官方 upgrade APK 反彙編：
 * EmbeddedModelUpgradeTask sendData/sendPageData/getCommandParam/sendUpgradeEnd
 *＋Alpha2SerialProtocol parseData/toByteBuffer＋handlePacket，不再係估)。
 *
 * 胸串口經 appContext 拿 HardwareDirectManager；
 * 查詢側 latch 共用 ChestQuery (升級開始前要一齊清)。
 * 進度經 EventBus chest_upgrade_progress / chest_upgrade_done 發布，
 * 前端輪詢 chest/upgrade/status。另帶 chest/page 調試讀頁
 * (chestPageResponse)——讀同一個升級鏡像檔。
 */
public final class ChestUpgrade {
    private static final String TAG = "ChestUpgrade";

    /** 升級用固件檔（上載／燒錄同一路徑，見 handleChestUpload／startChestUpgradeFrom）。 */
    public static final String FIRMWARE_PATH = "/sdcard/AlphaII_CHEST_kernel.bin";
    /** 完整 flash 鏡像大小（2048 頁 × 128B；上載閘＋啟動檢查同一數值，唔啱即拒，唔好燒半檔變磚）。 */
    public static final long FIRMWARE_SIZE = 262144L;
    /** V352 staging app-update 小鏡像上限（0x0801F000..0x0803B000 共 0x1C000＝114688 bytes；此值或以下走 staging 更新，超限 MCU 經 48 回覆拒收）。 */
    public static final long FIRMWARE_SMALL_MAX = 0x1C000L;
    public static final int FIRMWARE_PAGES = 2048;
    public static final int PAGE_SIZE = 128;

    /** 合法固件大小：256KB 全鏡像，或 1..114688 bytes 小鏡像（見 FIRMWARE_SMALL_MAX）。 */
    public static boolean isAcceptedFirmwareSize(long len) {
        return len == FIRMWARE_SIZE || (len > 0 && len <= FIRMWARE_SMALL_MAX);
    }

    // 胸口升級狀態 (48/49/50 協議，見 ag_chess/com/ubtechinc/h/a/a$b.java)
    // 單例升級線程，升級中 chestUpgradeInProgress=true，進度 0-100，前端經 EventBus chest_upgrade_progress / chest_upgrade_done 輪詢
    private volatile boolean chestUpgradeInProgress = false;
    private volatile int chestUpgradeProgress = 0;
    private volatile int chestUpgradeTotalPages = 0;
    private volatile int chestUpgradeCurrentPage = 0;
    private volatile String chestUpgradeStatus = "idle";
    private volatile CountDownLatch chestUpgradeLatch;
    private volatile byte chestUpgradeExpectedCmd = 0;
    private volatile int chestUpgradeExpectedIndex = -1;
    private volatile int chestUpgradeAckStatus = -1;
    private volatile Thread chestUpgradeThread;
    /** 會話 index（官方 getUnusedIndex 同款：1..127 滾動，0 永不用；回覆按 index 配對）。 */
    private int upgradeIndex = 0;
    private volatile boolean upgradeFirstReplyLogged = false;

    private synchronized int nextUpgradeIndex() {
        upgradeIndex++;
        if (upgradeIndex < 1 || upgradeIndex > 127) upgradeIndex = 1;
        return upgradeIndex;
    }

    private final Context appContext;
    private final ChestQuery chestQuery;

    public ChestUpgrade(Context context, ChestQuery chestQuery) {
        this.appContext = context.getApplicationContext();
        this.chestQuery = chestQuery;
    }

    /** Delegates to ChestQuery.chestReady(). */
    private boolean chestReady() {
        return chestQuery.chestReady();
    }

    /**
     * 認領升級 ACK 幀 (48/49/50)，由 MainActivity.onDirectChestFrame 最優先調用
     * (升級 ACK > UUID latch > 版本 latch，順序不變)。
     * 官方語義（upgrade APK handlePacket＋findTargetPacket＋sendCommand 反彙編實證）：
     * 回覆 [ID INDEX CMD PARAM...]，配對要 (id,cmd,index) 三元齊（INDEX 係 1..127
     * 請求 tag，唔係頁號）；PARAM[0]==0 先係接受（!=0 即拒收，官方自動重發——同
     * servo/cmd13 那套 [cmd 00 ...]=OK 一致，我哋舊 code 呢度本來就啱，唔使改）。
     * 上次假 success 嘅真正根因：請求誤用 ID=5／INDEX=0（05 00 LAP 頭）行咗 LAP
     * parser＋END status 冇驗——而家請求行返 ID=1＋滾動 INDEX，回覆嚴格配對，
     * 唔啱一律唔認帳（大聲超時失敗，好過靜靜報 success）。
     * @param frame 完整原始幀（取 ID／回聲 index／status 用；MainActivity 原樣傳入，呢度唔郁解析區）
     * @param payload 已剝頭 payload（短式判 cmd 用）
     * @return true = 已認領／已消耗 (調用方應直接 return；唔啱配對都照吞，唔好漏落 ChestQuery 誤食）。
     */
    public boolean onAckFrame(byte[] frame, byte[] payload, int plen) {
        // local copy + null-check（abort 會置 null，check-then 用 race 會 NPE）。
        java.util.concurrent.CountDownLatch latch = chestUpgradeLatch;
        if (latch == null || latch.getCount() <= 0) return false;
        // 官方幀优先：由原始幀直接解析（唔經 stripSerialFrame——佢唔識 ID=01 長式，
        // 會將升級回覆誤判做短式，payload[0] 變 0x01 乜都認唔到）。
        int[] reply = parseUpgradeReply(frame);
        if (reply != null) {
            if (reply[0] != 48 && reply[0] != 49 && reply[0] != 50) return false;
            // 48/49/50 一律消耗（唔好漏落 ChestQuery 版本 fallback 誤食）；係咪等緊嗰粒另計。
            if (reply[0] != (chestUpgradeExpectedCmd & 0xFF)
                    || reply[3] != 1
                    || reply[1] != chestUpgradeExpectedIndex) {
                Log.w(TAG, "chest upgrade ack mismatch want(cmd=" + (chestUpgradeExpectedCmd & 0xFF)
                        + ",id=1,index=" + chestUpgradeExpectedIndex + ") got(cmd=" + reply[0]
                        + ",id=" + reply[3] + ",index=" + reply[1] + ",status=" + reply[2] + ") (consumed)");
                return true;
            }
            chestUpgradeAckStatus = reply[2];
            // 診斷用：START／END／首個 page 回覆印原文（驗 ID／INDEX 回聲形狀，落機對官方用；平時唔洗版）。
            if (reply[0] == 48 || reply[0] == 50 || !upgradeFirstReplyLogged) {
                upgradeFirstReplyLogged = true;
                Log.i(TAG, "chest upgrade ack cmd=" + reply[0] + " id=" + reply[3] + " index=" + reply[1]
                        + " status=" + reply[2] + " raw=" + MainActivity.toHex(frame, frame.length));
            }
            latch.countDown();
            return true;
        }
        // 後備：非長式幀先用已剝頭 payload 判 cmd；48/49/50 照消耗但唔認帳
        //（短式本無 index，認唔到回聲；等超時大聲失敗，好過靜認錯）。
        if (plen < 1) return false;
        byte cmd = payload[0];
        if (cmd != 48 && cmd != 49 && cmd != 50) return false;
        Log.w(TAG, "chest upgrade ack non-long-form cmd=" + (cmd & 0xFF) + " (consumed, no credit)");
        return true;
    }

    /** 由原始幀解析官方升級回覆 [ID INDEX CMD PARAM...]，回 {cmd, index, status, id}
     *  （status＝首 param byte，無 param 即 0＝接受；官方 param==null 照收）。
     *  唔係完整長式幀回 null。 */
    private static int[] parseUpgradeReply(byte[] frame) {
        if (frame == null) return null;
        int n = frame.length;
        for (int i = 0; i + 5 < n; i++) {
            if ((frame[i] & 0xFF) == 0xF8 && (frame[i + 1] & 0xFF) == 0x8F) {
                int len = frame[i + 2] & 0xFF;
                if (i + len + 1 > n) continue; // 唔係完整幀，試下一個 F8 8F
                if (frame[i + len] != (byte) 0xED) continue; // 尾錯，唔係長式
                int plen = len - 7;
                if (plen < 0) continue;
                int id = frame[i + 3] & 0xFF;
                int index = frame[i + 4] & 0xFF;
                int cmd = frame[i + 5] & 0xFF;
                int status = (plen >= 1) ? (frame[i + 6] & 0xFF) : 0;
                return new int[]{cmd, index, status, id};
            }
        }
        return null;
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
            // 正在充電都要看實際電量（升級途中拔電掉電即變磚）；電量讀不到先當 100% 放行。
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

    private boolean waitForChestAck(byte expectedCmd, int expectedIndex, long timeoutMs) {
        chestUpgradeExpectedCmd = expectedCmd;
        chestUpgradeExpectedIndex = expectedIndex;
        chestUpgradeAckStatus = -1;
        CountDownLatch latch = new CountDownLatch(1);
        chestUpgradeLatch = latch;
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            // 不是自己這代 latch（abort/retry 清過）即不要再行，
            // 回 false 等上層 throw 退出（不是 abort 早醒會當成功繼續燒）。
            if (chestUpgradeLatch != latch) return false;
            if (!ok) {
                byte[] lastVer = chestQuery.getLastVersionRaw();
                Log.w(TAG, "chest upgrade ack timeout cmd=" + expectedCmd + " raw=" + (lastVer!=null?MainActivity.toHex(lastVer,lastVer.length):"null"));
                // 超時後印最近一次 chest_rcv 原始幀以便診斷 170 頁這類數據校驗失敗
                return false;
            }
            if (expectedCmd == 48 && chestUpgradeAckStatus != 0) {
                Log.w(TAG, "chest START rejected status=" + chestUpgradeAckStatus + " (V352 staging accepts <= 114688 bytes)");
                return false;
            }
            if (expectedCmd == 49 && chestUpgradeAckStatus != 0) {
                Log.w(TAG, "chest page ack status=" + chestUpgradeAckStatus + " (page data may be rejected, check offset " + (chestUpgradeCurrentPage*128) + ")");
                return false;
            }
            if (expectedCmd == 50 && chestUpgradeAckStatus != 0) {
                Log.w(TAG, "chest END rejected status=" + chestUpgradeAckStatus + " (md5/apply failed: bytes staged but NOT applied)");
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
        // countDown 叫醒 waitForChestAck（否則等足整個 timeout 先醒，中止不即時）。
        java.util.concurrent.CountDownLatch latch = chestUpgradeLatch;
        chestUpgradeLatch = null;
        if (latch != null) {
            while (latch.getCount() > 0) latch.countDown();
        }
        resetState();
        chestUpgradeInProgress = false;
        chestUpgradeStatus = "aborted";
    }

    /** 真正升級線程：48(4B 長度)->49*N頁([長度16]+128B)->50(16B MD5)，ID=1＋INDEX 1..127 滾動，
     *  回覆 status==0 先接受（官方 upgrade APK 反彙編同款）；加入重啟後首頁即失敗的復位。
     *  固件側機制（V352 靜態分析實證）：48 擦暫存區 0x0801F000（上限 0x1C000，拒收回 1/2）；
     *  49 逐 word 寫入＋讀回驗證＋餵 MD5；50 對 MD5，並睇最後 24 byte——有 `ChestLar2s`
     *  即寫旗標 0x5AA55AA5 到 0x0803B000，bootloader 下次上電先搬去 app 區。
     *  所以燒完必須成部機斷電重開先會生效，淨 reboot Android（胸板一路有電）係唔夠嘅；
     *  面板成功提示已寫明呢點（chest_upgrade_done_power）。 */
    private void doChestUpgradeFrom(final java.io.File file, final int startPage) {
        final int fileLen = (int) file.length();
        final int totalPages = (fileLen + 127) / 128;
        chestUpgradeTotalPages = totalPages;
        chestUpgradeCurrentPage = 0;
        chestUpgradeProgress = 0;
        chestUpgradeStatus = "start";
        upgradeFirstReplyLogged = false;
        EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"start\",\"progress\":0,\"total\":"+totalPages+"}");
        Log.i(TAG, "chest upgrade start len=" + fileLen + " pages=" + totalPages);
        // 起始前強制復位，避免重啟後首頁即 01 失敗（殘留升級態）
        resetState();
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        try {
            // pure-direct: 升级经 /dev/ttyS1 直发 48/49/50。
            if (!chestReady()) { throw new Exception("chest not ready (pure-direct)"); }
            // 48 START — 若連續3次仍拒收，嘗試先發 END 清狀態再重試。
            // 每次嘗試用新 index（官方每次 sendCommand 都攞新 index；遲到舊回覆唔會認錯）。
            chestUpgradeStatus = "sending start";
            boolean startOk = false;
            for (int retry = 0; retry < 3; retry++) {
                int idx = nextUpgradeIndex();
                boolean s = HardwareDirectManager.get(appContext).chest().startUpdate(fileLen, idx);
                if (!s) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)48, idx, 5000)) { startOk = true; break; }
                if (retry == 1) { Log.w(TAG, "start retry with reset"); resetState(); try{Thread.sleep(600);}catch(Exception ignored){} }
            }
            if (!startOk) throw new Exception("start rejected or ack timeout");
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
                        int idx = nextUpgradeIndex();
                        boolean s = HardwareDirectManager.get(appContext).chest().updatePage(sendBuf, read, idx);
                        if (!s) { Thread.sleep(300); continue; }
                        if (waitForChestAck((byte)49, idx, 4000)) { pageOk = true; break; }
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
                int idx = nextUpgradeIndex();
                boolean s = HardwareDirectManager.get(appContext).chest().endUpdate(md5, idx);
                if (!s) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)50, idx, 5000)) { endOk = true; break; }
            }
            if (!endOk) throw new Exception("end rejected or ack timeout");
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
        java.io.File f = new java.io.File(FIRMWARE_PATH);
        if (!f.exists()) return "file not found: " + FIRMWARE_PATH;
        // 硬驗大小：256KB 全鏡像或 1..114688 小鏡像先收；其他（adb 誤 push／上載損毀）直接拒，唔好照燒（會變磚）。
        if (!isAcceptedFirmwareSize(f.length())) {
            return "bad firmware size (" + f.length() + " bytes), need 262144 or 1.." + FIRMWARE_SMALL_MAX;
        }
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
    public String getChestUpgradeStatusJson() {
        // 順手帶機上固件檔資訊（前端升級前驗檔用：存在＋合法大小先畀燒）。
        java.io.File f = new java.io.File(FIRMWARE_PATH);
        boolean fileExists = f.isFile();
        long fileSize = fileExists ? f.length() : 0;
        return "{\"inProgress\":" + chestUpgradeInProgress + ",\"progress\":" + chestUpgradeProgress
            + ",\"currentPage\":" + chestUpgradeCurrentPage + ",\"totalPages\":" + chestUpgradeTotalPages
            + ",\"status\":\"" + MainActivity.jsonSafe(chestUpgradeStatus) + "\""
            + ",\"fileExists\":" + fileExists
            + ",\"fileSizeBytes\":" + fileSize
            + ",\"filePages\":" + ((fileSize + PAGE_SIZE - 1) / PAGE_SIZE)
            + ",\"wantSizeBytes\":" + FIRMWARE_SIZE
            + ",\"smallMaxBytes\":" + FIRMWARE_SMALL_MAX + "}";
    }

    // 調試：讀指定頁 offset 的 32B hex，用於定位 170 頁這類點
    public HttpServer.ApiResponse chestPageResponse(Map<String, String> query) {
        int page = ApiValidator.requireInt(query, "page");
        java.io.File f = new java.io.File(FIRMWARE_PATH);
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

    /** 胸板固件上載 - 接收 256KB 全鏡像或 1..114688 小鏡像（ALPHA2Q-CHEST-*.bin），寫入 /sdcard/AlphaII_CHEST_kernel.bin。 */
    public HttpServer.ApiResponse handleChestUpload(Map<String, String> query, byte[] body) {
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.badRequest("empty file body");
        }
        // 大小不合直接 400 不寫入（壞 bin 留在這裡、下次升級拿錯檔即變磚；
        // 下次升級拿錯檔即變磚；magic 無文件記載不驗，靠升級時 MCU ACK 把關）。
        // V352 app-update 小鏡像：1..114688 bytes（staging 0x0801F000..0x0803B000）；舊 256KB 全鏡像仍接受。
        if (!isAcceptedFirmwareSize(body.length)) {
            return HttpServer.ApiResponse.badRequest("chest firmware must be " + FIRMWARE_SIZE + " bytes or 1.." + FIRMWARE_SMALL_MAX + " bytes, got " + body.length);
        }
        try {
            java.io.File dest = new java.io.File(FIRMWARE_PATH);
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


