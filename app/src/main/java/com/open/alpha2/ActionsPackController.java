package com.open.alpha2;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * 動作包下載＋替換（實驗 tab 動作包卡用）。
 *
 * <p>固定單一 URL（allowlist，不收任意 URL，防 SSRF）：
 * {@code https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/actions.zip}。
 * 流程：後端直落 zip 到 sdcard 頂層 → 舊 {@code /sdcard/actions} 改名
 * {@code /sdcard/actions-backup}（舊 backup 先成個刪走）→ unzip 到 sdcard
 * （staging 先解再搬，兼容 zip 內有／無頂層 {@code actions/} 兩種打法）→
 * 驗收 {@code actions/actionInfo.txt} → 清 {@link ActionDirect} 緩存。
 *
 * <p>狀態機同 {@link VoskController} 下載那套一致（idle／downloading／unzipping／
 * done／error／cancelled），前端 poll {@code action/pack/status}（或聽
 * {@code actions_pack} event）顯示進度。heavy 工自己開 thread，public 方法
 * thread-safe。
 */
public final class ActionsPackController {
    private static final String TAG = "ActionsPackController";

    /** 固定下載連結（唯一合法來源；要換 release 先改這裡＋spec 描述）。 */
    public static final String PACK_URL =
            "https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/actions.zip";

    private static final String ACTIONS_DIR_NAME = "actions";
    private static final String BACKUP_DIR_NAME = "actions-backup";
    private static final String ZIP_TMP_NAME = "actions.zip.tmp";
    private static final String ZIP_NAME = "actions.zip";
    private static final String STAGING_DIR_NAME = "actions-tmp";

    private final Context appContext;
    private final ActionDirect actionDirect;

    // 下載狀態機（同 VoskController DL 那套獨立：下載中其他功能照行，不干擾）。
    private volatile String packState = "idle";
    private volatile int packProgress = -1; // 0-100，-1＝未知（unzip 中／長度不明）
    private volatile long packBytes = 0;
    private volatile long packTotal = -1;
    private volatile String packError;
    private volatile boolean packCancel = false;
    private Thread packThread;

    public ActionsPackController(Context context, ActionDirect actionDirect) {
        this.appContext = context.getApplicationContext();
        this.actionDirect = actionDirect;
    }

    /** 下載根＝sdcard 頂層（同 VoskController.downloadRoot 一致）。 */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                // exists 但不肯定寫得入也照樣嘗試（舊機 canWrite 誤報），寫不入後面會再錯。
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** 開始下載＋替換。return null＝已開始，否則即時錯誤字串。 */
    public synchronized String startDownload() {
        if ("downloading".equals(packState) || "unzipping".equals(packState)) {
            return "already downloading (" + packProgress + "%)";
        }
        // 單通道：Vosk 模型下載緊就唔開得（見 DownloadGate，實驗 tab 資源下載卡）。
        if (!DownloadGate.tryAcquire("actions", "actions.zip")) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        packState = "downloading";
        packProgress = 0;
        packBytes = 0;
        packTotal = -1;
        packError = null;
        packCancel = false;
        publishPack();
        packThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload();
            }
        }, "ActionsPackDownload");
        packThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(packState) && !"unzipping".equals(packState)) {
            return "not downloading";
        }
        packCancel = true;
        return null;
    }

    public void shutdown() {
        if ("downloading".equals(packState) || "unzipping".equals(packState)) packCancel = true;
    }

    /** action/pack/status endpoint 用（透傳 JSON，不經 ApiResponse 包多層）。 */
    public String statusJson() {
        String st;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = packState;
            prog = packProgress;
            bytes = packBytes;
            total = packTotal;
            err = packError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        sb.append(",\"url\":\"").append(escape(PACK_URL)).append('"');
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishPack() {
        String st;
        int prog;
        String err;
        synchronized (this) {
            st = packState;
            prog = packProgress;
            err = packError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("actions_pack", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setPack(String st, int prog, String err) {
        synchronized (this) {
            packState = st;
            packProgress = prog;
            packError = err;
        }
        publishPack();
    }

    private void runDownload() {
        File root = downloadRoot();
        File zipTmp = new File(root, ZIP_TMP_NAME);
        File zipDone = new File(root, ZIP_NAME);
        File staging = new File(root, STAGING_DIR_NAME);
        File actionsDir = new File(root, ACTIONS_DIR_NAME);
        File backupDir = new File(root, BACKUP_DIR_NAME);
        // 是否已經將舊 actions 搬去 backup（失敗還原用）。
        boolean renamedToBackup = false;
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            // --- 下載 ---
            java.net.HttpURLConnection conn = null;
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                java.net.URL url = new java.net.URL(PACK_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                try {
                    XiaozhiTrustAllSsl.applyTrustAll(conn);
                } catch (Throwable ignore) {
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "OpenAlpha2");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                // 用 int 版 getContentLength（API 1 已有）：actions.zip 遠細過 2GB；
                // getContentLengthLong 要 API 24+，這個 APK 要行 API 21/22，
                // 直接 call 會拋 NoSuchMethodError（同 vosk 熔斷保 API 19 同一類）。
                long total = -1;
                try {
                    total = conn.getContentLength();
                } catch (Throwable ignore) {
                }
                synchronized (this) {
                    packTotal = total;
                }
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(zipTmp);
                byte[] buf = new byte[32768];
                long got = 0;
                int n;
                long lastPub = 0;
                while ((n = in.read(buf)) != -1) {
                    synchronized (this) {
                        if (packCancel) throw new java.io.IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (this) {
                        packBytes = got;
                        if (total > 0) packProgress = (int) Math.min(100, got * 100 / total);
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPub > 500) {
                        lastPub = now;
                        publishPack();
                    }
                }
                try {
                    out.flush();
                } catch (Throwable ignore) {
                }
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignore) {
                }
                try {
                    if (out != null) out.close();
                } catch (Throwable ignore) {
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
            synchronized (this) {
                if (packCancel) throw new java.io.IOException("cancelled");
            }
            // --- unzip（staging 先解再搬，兼容兩種 zip 打法）---
            setPack("unzipping", 100, null);
            try {
                zipTmp.renameTo(zipDone);
            } catch (Throwable ignore) {
            }
            File src = zipDone.exists() ? zipDone : zipTmp;
            // staging 清場重建。
            deleteRecursive(staging);
            try {
                staging.mkdirs();
            } catch (Throwable ignore) {
            }
            unzipToDir(src, staging);
            // 驗 staging：有頂層 actions/ 就用佢，否則 staging 本身要有 actionInfo.txt。
            File nested = new File(staging, ACTIONS_DIR_NAME);
            File srcDir;
            if (nested.isDirectory()
                    && new File(nested, "actionInfo.txt").isFile()) {
                srcDir = nested;
            } else if (new File(staging, "actionInfo.txt").isFile()) {
                srcDir = staging;
            } else {
                throw new java.io.IOException("unzip ok but actionInfo.txt not found"
                        + " (need actions/actionInfo.txt in zip)");
            }
            // 舊 actions 改名 backup（舊 backup 先成個刪走；改名失敗即死，不再 unzip 覆蓋）。
            if (actionsDir.exists()) {
                deleteRecursive(backupDir);
                boolean renamed;
                try {
                    renamed = actionsDir.renameTo(backupDir);
                } catch (Throwable e) {
                    renamed = false;
                }
                if (!renamed) {
                    throw new java.io.IOException("cannot backup old actions"
                            + " (rename actions -> actions-backup failed)");
                }
                renamedToBackup = true;
            } else {
                // 舊 actions 本來就無，殘留 backup 照留，不刪（唔關今次事）。
                renamedToBackup = false;
            }
            // staging 搬去 actions。
            boolean moved;
            try {
                if (srcDir == staging) {
                    moved = staging.renameTo(actionsDir);
                    if (!moved) {
                        // rename 跨卷失敗就逐個搬（舊機 sdcard 偶有此問題）。
                        moved = moveDirContents(staging, actionsDir);
                        deleteRecursive(staging);
                    }
                } else {
                    moved = nested.renameTo(actionsDir);
                    if (!moved) {
                        moved = moveDirContents(nested, actionsDir);
                    }
                    deleteRecursive(staging);
                }
            } catch (Throwable e) {
                moved = false;
            }
            if (!moved || !new File(actionsDir, "actionInfo.txt").isFile()) {
                throw new java.io.IOException("cannot install new actions"
                        + " (move staging -> actions failed)");
            }
            try {
                src.delete();
            } catch (Throwable ignore) {
            }
            try {
                if (zipTmp.exists()) zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // 新包生效：清 ActionDirect 緩存（下次 action/list 即讀新 actionInfo.txt）。
            try {
                if (actionDirect != null) actionDirect.invalidateCache();
            } catch (Throwable e) {
                Log.w(TAG, "invalidateCache failed", e);
            }
            setPack("done", 100, null);
            DownloadGate.release("actions");
            Log.i(TAG, "actions pack downloaded+installed");
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = packCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "actions pack download failed: " + msg, e);
            try {
                zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // 失敗還原：已經搬走舊 actions 但新包爛／搬唔到，就搬返 backup 轉頭，
            // 免動作 tab 無嘢播（backup 本身搬唔返就如實報錯）。
            if (renamedToBackup) {
                try {
                    if (!actionsDir.exists()
                            || !new File(actionsDir, "actionInfo.txt").isFile()) {
                        deleteRecursive(actionsDir);
                        if (!backupDir.renameTo(actionsDir)) {
                            msg = msg + " (rollback failed: cannot restore actions-backup)";
                        } else {
                            msg = msg + " (rolled back to old actions)";
                            renamedToBackup = false;
                        }
                    }
                } catch (Throwable re) {
                    msg = msg + " (rollback failed)";
                }
            }
            try {
                deleteRecursive(staging);
            } catch (Throwable ignore) {
            }
            DownloadGate.release("actions");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setPack("cancelled", packProgress, "cancelled");
            } else {
                setPack("error", packProgress, msg);
            }
        }
    }

    /** staging 搬唔到（rename 跨卷失敗）先逐個搬：建目錄＋rename／copy。 */
    private static boolean moveDirContents(File from, File to) {
        try {
            if (!to.exists() && !to.mkdirs()) return false;
            File[] fs;
            try {
                fs = from.listFiles();
            } catch (Throwable t) {
                return false;
            }
            if (fs == null) return false;
            for (File c : fs) {
                File dest = new File(to, c.getName());
                if (c.isDirectory()) {
                    if (!moveDirContents(c, dest)) return false;
                } else {
                    boolean ok = false;
                    try {
                        ok = c.renameTo(dest);
                    } catch (Throwable ignore) {
                        ok = false;
                    }
                    if (!ok) {
                        // rename 唔到就 copy＋刪（同卷內應該唔會行到呢度）。
                        java.io.InputStream in = null;
                        java.io.OutputStream out = null;
                        try {
                            in = new java.io.BufferedInputStream(new java.io.FileInputStream(c));
                            out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dest));
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                            out.flush();
                            ok = true;
                        } catch (Throwable ignore) {
                            ok = false;
                        } finally {
                            try {
                                if (in != null) in.close();
                            } catch (Throwable ignore) {
                            }
                            try {
                                if (out != null) out.close();
                            } catch (Throwable ignore) {
                            }
                        }
                        if (ok) {
                            try {
                                c.delete();
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                    if (!ok) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Throwable t) {
            fs = null;
        }
        if (fs != null) {
            for (File c : fs) {
                try {
                    if (c.isDirectory()) deleteRecursive(c);
                    else c.delete();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    /** zip-slip safe：entry 必須解到 dir 之內，否則跳過。 */
    private void unzipToDir(File zip, File dir) throws java.io.IOException {
        String dirCanon = dir.getCanonicalPath();
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zis.getNextEntry()) != null) {
                synchronized (this) {
                    if (packCancel) throw new java.io.IOException("cancelled");
                }
                String name = e.getName();
                // 擋絕對路徑／.. 跳出（外來 zip 不信）。
                File f = new File(dir, name);
                String canon = f.getCanonicalPath();
                if (!canon.equals(dirCanon) && !canon.startsWith(dirCanon + File.separator)) {
                    try {
                        zis.closeEntry();
                    } catch (Throwable ignore) {
                    }
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.OutputStream o = null;
                    try {
                        o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            synchronized (this) {
                                if (packCancel) throw new java.io.IOException("cancelled");
                            }
                            o.write(buf, 0, n);
                        }
                        o.flush();
                    } finally {
                        try {
                            if (o != null) o.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
                try {
                    zis.closeEntry();
                } catch (Throwable ignore) {
                }
            }
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
