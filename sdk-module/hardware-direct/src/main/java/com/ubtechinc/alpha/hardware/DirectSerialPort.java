package com.ubtechinc.alpha.hardware;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 不依賴 alpha2services 的直驅串口（pure-direct 下唯一的胸/頭控制通路）。
 *
 * <p>打開順序（依次嘗試主/備/備2設備）：
 * 1) JNI 路徑：{@code com.ubtechinc.alpha.jni.SerialPortFile}（libserial_port.so，
 *    native 內走 termios 配好 115200 8N1 raw）。此類與 .so 同模塊打包，
 *    Class.forName 一般都能找到；找不到才走 2)。
 * 2) 純 File 路徑：FileInputStream/FileOutputStream 直開，之後用 busybox stty
 *    把波特率改成 115200（實測係統預設 9600，不改 MCU 收到的全是亂碼——2026-09
 *    實測 servo TX 發得出但舵機不動的根因之一）。</p>
 *
 * <p>本機 ttyS1/ttyS3 為 777，普通應用也可 open；失敗則 isAvailable()=false，
 * 調用方直接報錯，無任何 binder 回退。</p>
 */
public final class DirectSerialPort {
    private static final String TAG = "DirectSerialPort";

    public interface OnFrameListener {
        void onFrame(byte[] frame);
    }

    private final String primaryPath;
    private final String altPath;
    private final String altPath2;
    private final int baudrate;

    private volatile InputStream input;
    private volatile OutputStream output;
    private Object serialPortFileObj; // JNI 路徑時持有，供 close() 用
    private volatile String openedPath;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArraySet<OnFrameListener> listeners = new CopyOnWriteArraySet<>();
    // 跨實例獨佔：胸/頭 ALT2 同指 /dev/ttyS0，兩邊齊開會分流幀互搶。
    // 開咗嘅 path 記低，另一邊見到就跳過試下一個。
    private static final java.util.Set<String> sOpenedPaths =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 該 path 係咪已經被另一個 port 攞咗（獨佔檢查用）。 */
    private static boolean claimPath(String path) {
        synchronized (sOpenedPaths) {
            if (sOpenedPaths.contains(path)) return false;
            sOpenedPaths.add(path);
            return true;
        }
    }

    private static void releasePath(String path) {
        if (path == null) return;
        synchronized (sOpenedPaths) { sOpenedPaths.remove(path); }
    }

    public DirectSerialPort(String primaryPath, String altPath, int baudrate) {
        this(primaryPath, altPath, null, baudrate);
    }

    public DirectSerialPort(String primaryPath, String altPath, String altPath2, int baudrate) {
        this.primaryPath = primaryPath;
        this.altPath = altPath;
        this.altPath2 = altPath2;
        this.baudrate = baudrate;
    }

    public static DirectSerialPort forChest() {
        if (HardwareVersion.is2Mic()) {
            // 1.1.7.3 官方 AlphaSerialPortServices 先讀 ro.front.serial，
            // 冇先用 /dev/ttySAC0；RK 板後備 ttyS1/ttyS0。
            String primary = HardwareVersion.getProperty("ro.front.serial",
                    DirectSerialConfig.CHEST_TTY_2MIC);
            // primary 已經係 ttySAC0 就唔好重複試；後備保留 5-mic ttyS1 同舊 RK ttyS0。
            String alt = DirectSerialConfig.CHEST_TTY_2MIC.equals(primary)
                    ? DirectSerialConfig.CHEST_TTY
                    : DirectSerialConfig.CHEST_TTY_2MIC;
            Log.i(TAG, "2mic chest serial: " + primary + " (ro.front.serial), fallback "
                    + alt + "," + DirectSerialConfig.CHEST_TTY_ALT2);
            return new DirectSerialPort(primary, alt,
                    DirectSerialConfig.CHEST_TTY_ALT2, DirectSerialConfig.BAUDRATE);
        }
        return new DirectSerialPort(DirectSerialConfig.CHEST_TTY, DirectSerialConfig.CHEST_TTY_ALT, DirectSerialConfig.CHEST_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    public static DirectSerialPort forHead() {
        if (HardwareVersion.is2Mic()) {
            // 1.1.7.3 官方 2-mic 路徑；RK 板後備 ttyS3。ttyS0 同胸板共用，
            // 唔加落 head，免得搶錯 port。
            return new DirectSerialPort(DirectSerialConfig.HEAD_TTY_2MIC,
                    DirectSerialConfig.HEAD_TTY, null,
                    DirectSerialConfig.BAUDRATE);
        }
        return new DirectSerialPort(DirectSerialConfig.HEAD_TTY, DirectSerialConfig.HEAD_TTY_ALT, DirectSerialConfig.HEAD_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    public void addListener(OnFrameListener l) { listeners.add(l); }

    public synchronized boolean open() {
        if (running.get()) return true;
        // 1) JNI 路徑（自帶 termios 配 baud）
        if (tryOpenViaJni(primaryPath) || tryOpenViaJni(altPath) || (altPath2 != null && tryOpenViaJni(altPath2))) {
            startReader();
            return true;
        }
        // 2) 純 File 路徑 + stty 配 baud
        if (tryOpenViaFile(primaryPath) || tryOpenViaFile(altPath) || (altPath2 != null && tryOpenViaFile(altPath2))) {
            startReader();
            return true;
        }
        Log.w(TAG, "open failed for all " + primaryPath + "," + altPath + "," + altPath2 + " (pure-direct, no fallback)");
        return false;
    }

    private boolean tryOpenViaJni(String path) {
        if (path == null) return false;
        File dev = new File(path);
        if (!dev.exists()) {
            Log.d(TAG, "device not found: " + path);
            return false;
        }
        ensure2MicAccess(path);
        // 2-mic 先用 1.1.7.3 原裝 android_serialport_api.SerialPort（官方
        // libserial_port_2mic.so，Android 4.4.2 實機相容）；失敗再試 5-mic
        // 自製 SerialPortFile。
        if (HardwareVersion.is2Mic()
                && tryOpenViaClass(path, dev, "android_serialport_api.SerialPort")) {
            return true;
        }
        return tryOpenViaClass(path, dev, "com.ubtechinc.alpha.jni.SerialPortFile");
    }

    private boolean tryOpenViaClass(String path, File dev, String className) {
        try {
            Class<?> cls = Class.forName(className);
            Object obj = cls.getConstructor(File.class, int.class, int.class)
                    .newInstance(dev, baudrate, 0);
            InputStream in;
            OutputStream out;
            try {
                in = (InputStream) cls.getMethod("getInputStream").invoke(obj);
                out = (OutputStream) cls.getMethod("getOutputStream").invoke(obj);
            } catch (NoSuchMethodException nsme) {
                Log.d(TAG, className + " has no stream getters: " + nsme.getMessage());
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            if (in == null || out == null) {
                Log.d(TAG, "JNI open returned null stream for " + path);
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            if (!claimPath(path)) {
                Log.w(TAG, path + " already held by another port, skipping (獨佔，防分流幀)");
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            this.input = in;
            this.output = out;
            this.serialPortFileObj = obj;
            this.openedPath = path;
            Log.i(TAG, "opened via JNI " + className + ": " + path + " baud=" + baudrate);
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, className + " not found, fallback: " + e.getMessage());
        } catch (Throwable t) {
            // UnsatisfiedLinkError/NoSuchMethodError（Error 非 Exception）唔接會
            // 穿透撞死調用線程；一律當開唔到，試下一個。
            Log.d(TAG, "JNI open failed for " + path + " (" + className + ", "
                    + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        return false;
    }

    private boolean tryOpenViaFile(String path) {
        if (path == null) return false;
        File dev = new File(path);
        if (!dev.exists()) {
            Log.d(TAG, "device not found: " + path);
            return false;
        }
        ensure2MicAccess(path);
        if (!claimPath(path)) {
            Log.w(TAG, path + " already held by another port, skipping (獨佔，防分流幀)");
            return false;
        }
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(dev);
            FileOutputStream fout;
            try {
                fout = new FileOutputStream(dev);
            } catch (Exception e) {
                // 第二步炸咗要閂返第一個 fd，唔係漏。
                try { fin.close(); } catch (Exception ignore) {}
                fin = null;
                throw e;
            }
            this.input = fin;
            this.output = fout;
            this.serialPortFileObj = null;
            this.openedPath = path;
            // 純 Java 開不出 termios，必須用 stty 把波特率從預設 9600 改到 115200，
            // 否則 MCU 侧全是亂碼（2026-09 實測教訓）。busybox 自帶 stty。
            // 改唔到就當開唔到：留喺 9600 嘅 port 只會靜默發亂碼（舵機唔郁、
            // 回覆全爛），比誠實失敗更難查——寧願 isAvailable()=false 等重試。
            if (!configureBaudStty(path, baudrate)) {
                Log.w(TAG, "File path stty 配 baud 失敗（port 會留喺 9600 發亂碼），放棄 " + path);
                try { fin.close(); } catch (Exception ignore) {}
                try { fout.close(); } catch (Exception ignore) {}
                this.input = null;
                this.output = null;
                this.openedPath = null;
                releasePath(path);
                return false;
            }
            Log.i(TAG, "opened via File: " + path);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "File open failed for " + path + " (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            releasePath(path);
            closeQuietly();
            return false;
        }
    }

    /**
     * 2-mic（Android 4.4.2 / Exynos）嘅 /dev/ttySAC* 未必係 777：普通 app
     * 可能無 rw。官方 1.1.7.3 android_serialport_api.SerialPort 喺 canRead/
     * canWrite 失敗時會經 su chmod 666；呢度照做（先試直接 chmod，再試 su）。
     * 5-mic 唔行，避免無謂 su。
     */
    private static void ensure2MicAccess(String path) {
        if (!HardwareVersion.is2Mic()) return;
        File f = new File(path);
        if (f.canRead() && f.canWrite()) return;
        runQuietly(new String[]{"chmod", "666", path}, 2000);
        if (f.canRead() && f.canWrite()) {
            Log.i(TAG, "2mic chmod ok (direct): " + path);
            return;
        }
        // 官方 SerialPort 同款：echo "chmod 666 ..." | su。
        // 4.4.2 嘅 su 可能喺 /system/bin 或 /system/xbin，PATH 未必搵到。
        String[] suPaths = {"/system/bin/su", "/system/xbin/su", "su"};
        for (String su : suPaths) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(su);
                OutputStream os = p.getOutputStream();
                os.write(("chmod 666 " + path + "\nexit\n").getBytes());
                os.flush();
                os.close();
                waitForProcess(p, 3000);
                if (f.canRead() && f.canWrite()) {
                    Log.i(TAG, "2mic chmod ok (" + su + "): " + path);
                    break;
                }
            } catch (Throwable t) {
                Log.d(TAG, "2mic su chmod failed (" + su + ") for " + path + ": " + t.getMessage());
            } finally {
                if (p != null) p.destroy();
            }
        }
        Log.i(TAG, "2mic chmod " + path + " read=" + f.canRead() + " write=" + f.canWrite());
    }

    private static void runQuietly(String[] cmd, long timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            waitForProcess(p, timeoutMs);
        } catch (Throwable t) {
            Log.d(TAG, "2mic chmod exec failed: " + t.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 等子進程，逾時 destroy（su 可能等授權而唔返）。 */
    private static boolean waitForProcess(Process p, long timeoutMs) {
        final Process proc = p;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { proc.waitFor(); } catch (InterruptedException ignored) {}
            }
        }, "serial-chmod-wait");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            p.destroy();
            return false;
        }
        return true;
    }

    /** 用 busybox stty 配波特率+8N1 raw（File 路徑專用，JNI 路徑 native 已配好）。
     *  回 true = 已讀回確認 baudrate 生效；false = 兩條 stty 都配唔到（調用方必須
     *  當開 port 失敗，唔可以留喺 9600 發亂碼）。 */
    private static boolean configureBaudStty(String path, int baudrate) {
        String[] cmds = {
                "busybox stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
                "stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
        };
        for (String cmd : cmds) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + "; stty -F " + path + " 2>&1 | head -1"});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                try {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append(' ');
                    // stdout 讀完都要排乾 stderr，唔係子進程寫滿 pipe 會卡死 waitFor。
                    java.io.InputStream err = p.getErrorStream();
                    try {
                        byte[] drain = new byte[256];
                        while (err.available() > 0 && err.read(drain) > 0) { /* 掉棄 */ }
                    } catch (Exception ignore) {}
                    p.waitFor();
                    Log.i(TAG, "stty " + path + " [" + cmd.split(" ")[0] + "] -> " + sb.toString().trim());
                    if (sb.toString().contains(String.valueOf(baudrate))) return true; // 讀回確認配好先算成功
                } finally {
                    try { r.close(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.d(TAG, "stty attempt failed (" + cmd.split(" ")[0] + "): " + e.getMessage());
            } finally {
                if (p != null) p.destroy();
            }
        }
        return false;
    }

    public boolean isAvailable() { return running.get() && input != null && output != null; }

    public synchronized boolean send(byte cmd, byte[] param) {
        OutputStream out = output;
        if (!isAvailable() || out == null) return false;
        try {
            byte[] frame = SerialFrameCodec.encode(cmd, param);
            out.write(frame);
            out.flush();
            Log.d(TAG, "TX(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // encode() 參數超限（plen>248）等：唔好送壞幀落 MCU，直接失敗。
            Log.w(TAG, "send rejected (" + e.getMessage() + ")");
            return false;
        }
    }

    /** 升級幀直發（官方 upgrade APK 同款 ID/INDEX 頭，見 SerialFrameCodec.encodeUpgrade；
     *  servo/LED 等 LAP 流量行上面 send()，唔好混用）。 */
    public synchronized boolean sendUpgrade(byte id, byte index, byte cmd, byte[] param) {
        OutputStream out = output;
        if (!isAvailable() || out == null) return false;
        try {
            byte[] frame = SerialFrameCodec.encodeUpgrade(id, index, cmd, param);
            out.write(frame);
            out.flush();
            Log.d(TAG, "TX-UPG(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "sendUpgrade failed: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "sendUpgrade rejected (" + e.getMessage() + ")");
            return false;
        }
    }

    /** 透傳原始幀 (繞過 encode)，用於 set_uuid/version fallback 等手寫長式幀 */
    public synchronized boolean sendRaw(byte[] rawFrame) {
        OutputStream out = output;
        if (!isAvailable() || out == null || rawFrame == null) return false;
        try {
            out.write(rawFrame);
            out.flush();
            Log.d(TAG, "TX-RAW(" + openedPath + ") " + SerialFrameCodec.toHex(rawFrame));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sendRaw failed: " + e.getMessage());
            return false;
        }
    }

    private void startReader() {
        running.set(true);
        readerThread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[4096];
                int pos = 0;
                while (running.get()) {
                    try {
                        InputStream in = input;
                        if (in == null) { Thread.sleep(50); continue; }
                        int n = in.read(buf, pos, buf.length - pos);
                        if (n <= 0) { Thread.sleep(10); continue; }
                        int avail = pos + n;
                        int off = 0;
                        while (off < avail) {
                            SerialFrameCodec.DecodeResult r = SerialFrameCodec.tryDecode(buf, off, avail - off);
                            if (r == null) break; // 不夠一幀
                            if (r.frame != null) {
                                for (OnFrameListener l : listeners) {
                                    try { l.onFrame(r.frame); } catch (Exception ignore) {}
                                }
                                logRxFrame(r.frame);
                            }
                            off += r.consumed;
                        }
                        // 未消費的殘餘搬到頭部
                        if (off > 0 && off < avail) {
                            System.arraycopy(buf, off, buf, 0, avail - off);
                            pos = avail - off;
                        } else if (off >= avail) {
                            pos = 0;
                        } else {
                            pos = avail;
                            if (pos >= buf.length - 256) {
                                Log.w(TAG, "reader dropping " + pos + "B garbage (no F8 8F head)");
                                pos = 0; // 防溢出，丟棄
                            }
                        }
                    } catch (Exception e) {
                        if (running.get()) Log.w(TAG, "reader error: " + e.getMessage());
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        }, "DirectSerial-" + new File(openedPath != null ? openedPath : primaryPath).getName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private int heartbeatSuppressed = 0;
    private long heartbeatLastLogMs = 0;

    /** 收幀 log：心跳（0x8B/0x8D）逐幀印會洗 logcat（實測 0x8D 洪水），呢度淨係
     *  唔印，dispatch 照行（上唔上 WebSocket 由 MainActivity.onDirectChestFrame
     *  決定，嗰邊一樣濾咗呢兩隻）。60 秒報一次數，等人知串口仲生勾勾。 */
    private void logRxFrame(byte[] frame) {
        if (isHeartbeat(frame)) {
            heartbeatSuppressed++;
            long now = System.currentTimeMillis();
            if (now - heartbeatLastLogMs > 60000) {
                heartbeatLastLogMs = now;
                Log.d(TAG, "RX(" + openedPath + ") heartbeat 0x8B/0x8D suppressed x" + heartbeatSuppressed);
            }
            return;
        }
        Log.d(TAG, "RX(" + openedPath + ") " + SerialFrameCodec.toHex(frame));
    }

    /** 長式 F8 8F LEN SRC DST CMD…：SRC 05/00、DST 00、CMD 8B/8D 即心跳。 */
    private static boolean isHeartbeat(byte[] frame) {
        if (frame == null || frame.length < 8) return false;
        if ((frame[0] & 0xFF) != 0xF8 || (frame[1] & 0xFF) != 0x8F) return false;
        int src = frame[3] & 0xFF;
        if (frame[4] != 0 || (src != 0x05 && src != 0x00)) return false;
        int cmd = frame[5] & 0xFF;
        return cmd == 0x8B || cmd == 0x8D;
    }

    public synchronized void close() {
        running.set(false);
        // 先閂 stream 鬆開 blocking read（FileInputStream.read 唔食 interrupt，
        // 唔閂就 join 實超時留孤兒線程），再 join。
        closeQuietly();
        if (readerThread != null) { try { readerThread.interrupt(); readerThread.join(500); } catch (InterruptedException ignore) {} readerThread = null; }
    }

    private void closeQuietly() {
        // JNI 路徑：input/output 係 SerialPortFile 包住同一個 native fd 嘅 view，
        // 逐個 close 再加 native close = double-close（fd 號復用會誤關別人文件）。
        // 呢條路只行 native close 一次。File 路徑：fin/fout 係兩個獨立 fd，兩個都要閂。
        if (serialPortFileObj != null) {
            try { serialPortFileObj.getClass().getMethod("close").invoke(serialPortFileObj); } catch (Exception ignore) {}
        } else {
            try { if (input != null) input.close(); } catch (IOException ignore) {}
            try { if (output != null) output.close(); } catch (IOException ignore) {}
        }
        releasePath(openedPath);
        input = null; output = null; serialPortFileObj = null; openedPath = null;
    }
}
