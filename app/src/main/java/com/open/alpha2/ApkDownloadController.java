package com.open.alpha2;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * APK 下載（實驗 tab「資源下載」卡 Google TTS 區用）。
 *
 * <p>固定單一 URL（allowlist，不收任意 URL，防 SSRF）：
 * Google TTS 指定版本。後端直落 apk 到 sdcard 頂層，落完即開系統
 * 安裝器等人手確認（經 scrcpy mouse 撳到；部機無觸屏）。
 *
 * <p>開唔到安裝器都唔當下載失敗（檔已落好，照樣可用 adb 裝）。
 *
 * <p>同 Vosk／動作包共用單一下載通道（見 {@link DownloadGate}），每次只准
 * 一樣下載緊。狀態機：idle／downloading／done／error／cancelled。
 * heavy 工自己開 thread，public 方法 thread-safe。
 */
public final class ApkDownloadController {
    private static final String TAG = "ApkDownloadController";

    /** 固定下載連結（唯一合法來源；要換版先改這裡＋spec 描述）。 */
    public static final String APK_URL =
            "https://github.com/arthurson/ubtech-open-alpha2/releases/download/beta/com.google.android.tts_24.9.361717975-210316629.apk";

    private final Context appContext;

    private volatile String apkState = "idle";
    private volatile int apkProgress = -1; // 0-100，-1＝未知（長度不明）
    private volatile long apkBytes = 0;
    private volatile long apkTotal = -1;
    private volatile String apkError;
    private volatile boolean apkCancel = false;
    private Thread apkThread;

    public ApkDownloadController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** URL 尾段檔名（allowlist 固定，唔使防 traversal 都照擋 ../）。 */
    static String apkFileName() {
        String u = APK_URL;
        int i = u.lastIndexOf('/');
        String name = (i >= 0 && i + 1 < u.length()) ? u.substring(i + 1) : "download.apk";
        if (name.indexOf("..") >= 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || !name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
            return "download.apk";
        }
        return name;
    }

    /** 下載根＝sdcard 頂層（同 Vosk／動作包一致）。 */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** 開始下載。return null＝已開始，否則即時錯誤字串。 */
    public synchronized String startDownload() {
        if ("downloading".equals(apkState)) {
            return "already downloading (" + apkProgress + "%)";
        }
        // 單通道：Vosk／動作包下載緊就唔開得。
        if (!DownloadGate.tryAcquire("apk", apkFileName())) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        apkState = "downloading";
        apkProgress = 0;
        apkBytes = 0;
        apkTotal = -1;
        apkError = null;
        apkCancel = false;
        publishApk();
        apkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload();
            }
        }, "ApkDownload");
        apkThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(apkState)) {
            return "not downloading";
        }
        apkCancel = true;
        return null;
    }

    public void shutdown() {
        if ("downloading".equals(apkState)) apkCancel = true;
    }

    /** apk/status endpoint 用（透傳 JSON，不經 ApiResponse 包多層）。 */
    public String statusJson() {
        String st;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = apkState;
            prog = apkProgress;
            bytes = apkBytes;
            total = apkTotal;
            err = apkError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        sb.append(",\"url\":\"").append(escape(APK_URL)).append('"');
        sb.append(",\"file\":\"").append(escape(apkFileName())).append('"');
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishApk() {
        String st;
        int prog;
        String err;
        synchronized (this) {
            st = apkState;
            prog = apkProgress;
            err = apkError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("apk", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setApk(String st, int prog, String err) {
        synchronized (this) {
            apkState = st;
            apkProgress = prog;
            apkError = err;
        }
        publishApk();
    }

    private void runDownload() {
        File root = downloadRoot();
        File tmp = new File(root, apkFileName() + ".tmp");
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            downloadToTmp(root, tmp);
            synchronized (this) {
                if (apkCancel) throw new java.io.IOException("cancelled");
            }
            // 落完即開系統安裝器等人手確認（開唔到唔當失敗，檔已落好）。
            openSystemInstaller(new File(root, apkFileName()));
            setApk("done", 100, null);
            DownloadGate.release("apk");
            Log.i(TAG, "apk downloaded: /sdcard/" + apkFileName());
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = apkCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "apk download failed: " + msg, e);
            try {
                tmp.delete();
            } catch (Throwable ignore) {
            }
            DownloadGate.release("apk");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setApk("cancelled", apkProgress, "cancelled");
            } else {
                setApk("error", apkProgress, msg);
            }
        }
    }

    /** 下載本體（同 Vosk／動作包同一套：trust-all＋超時＋逐 0.5s 推進度）。 */
    private void downloadToTmp(File root, File tmp) throws java.io.IOException {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            java.net.URL url = new java.net.URL(APK_URL);
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
            // int 版 getContentLength（API 1 已有；apk 遠細過 2GB，
            // getContentLengthLong 要 API 24+，呢個 APK 要行 API 21/22）。
            long total = -1;
            try {
                total = conn.getContentLength();
            } catch (Throwable ignore) {
            }
            synchronized (this) {
                apkTotal = total;
            }
            in = conn.getInputStream();
            out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[32768];
            long got = 0;
            int n;
            long lastPub = 0;
            while ((n = in.read(buf)) != -1) {
                synchronized (this) {
                    if (apkCancel) throw new java.io.IOException("cancelled");
                }
                out.write(buf, 0, n);
                got += n;
                synchronized (this) {
                    apkBytes = got;
                    if (total > 0) apkProgress = (int) Math.min(100, got * 100 / total);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastPub > 500) {
                    lastPub = now;
                    publishApk();
                }
            }
            try {
                out.flush();
            } catch (Throwable ignore) {
            }
            try {
                out.close();
                out = null;
            } catch (Throwable ignore) {
                out = null;
            }
            if (tmp.length() <= 0) throw new java.io.IOException("empty download");
            File done = new File(root, apkFileName());
            try {
                done.delete();
            } catch (Throwable ignore) {
            }
            if (!tmp.renameTo(done)) {
                throw new java.io.IOException("cannot move apk into place");
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
    }

    /** 落完開系統安裝器等人手確認（經 scrcpy mouse 撳到；部機無觸屏）。
     *  開唔到都唔當下載失敗（檔已落好，照樣可用 adb 裝）。絕不 throw。 */
    private void openSystemInstaller(File apk) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(android.net.Uri.fromFile(apk),
                    "application/vnd.android.package-archive");
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            Log.i(TAG, "system installer opened for " + apk.getName());
        } catch (Throwable e) {
            Log.w(TAG, "open system installer failed (install via adb instead)", e);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
