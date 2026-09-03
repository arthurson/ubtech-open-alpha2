package com.ubtechinc.alpha.hardware;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
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

    private InputStream input;
    private OutputStream output;
    private FileDescriptor fd;
    private Object serialPortFileObj; // JNI 路徑時持有，供 close() 用
    private String openedPath;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArraySet<OnFrameListener> listeners = new CopyOnWriteArraySet<>();

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
        return new DirectSerialPort(DirectSerialConfig.CHEST_TTY, DirectSerialConfig.CHEST_TTY_ALT, DirectSerialConfig.CHEST_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    public static DirectSerialPort forHead() {
        return new DirectSerialPort(DirectSerialConfig.HEAD_TTY, DirectSerialConfig.HEAD_TTY_ALT, DirectSerialConfig.HEAD_TTY_ALT2, DirectSerialConfig.BAUDRATE);
    }

    public void addListener(OnFrameListener l) { listeners.add(l); }
    public void removeListener(OnFrameListener l) { listeners.remove(l); }
    public String getOpenedPath() { return openedPath; }

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
        try {
            Class<?> cls = Class.forName("com.ubtechinc.alpha.jni.SerialPortFile");
            Object obj = cls.getConstructor(File.class, int.class, int.class)
                    .newInstance(dev, baudrate, 0);
            InputStream in;
            OutputStream out;
            FileDescriptor jfd = null;
            try {
                in = (InputStream) cls.getMethod("getInputStream").invoke(obj);
                out = (OutputStream) cls.getMethod("getOutputStream").invoke(obj);
            } catch (NoSuchMethodException nsme) {
                Log.d(TAG, "SerialPortFile has no stream getters: " + nsme.getMessage());
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            if (in == null || out == null) {
                Log.d(TAG, "JNI open returned null stream for " + path);
                try { cls.getMethod("close").invoke(obj); } catch (Exception ignore) {}
                return false;
            }
            try {
                try {
                    jfd = (FileDescriptor) cls.getMethod("getFD").invoke(obj);
                } catch (NoSuchMethodException nsme2) {
                    java.lang.reflect.Field f = cls.getDeclaredField("mFd");
                    f.setAccessible(true);
                    jfd = (FileDescriptor) f.get(obj);
                }
            } catch (Exception ignore) { /* fd 可選，有則存，無亦可 */ }
            this.input = in;
            this.output = out;
            this.fd = jfd;
            this.serialPortFileObj = obj;
            this.openedPath = path;
            Log.i(TAG, "opened via JNI SerialPortFile: " + path + " baud=" + baudrate);
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "SerialPortFile class not found, fallback to File: " + e.getMessage());
        } catch (Exception e) {
            Log.d(TAG, "JNI open failed for " + path + ": " + e);
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
        try {
            FileInputStream fin = new FileInputStream(dev);
            FileOutputStream fout = new FileOutputStream(dev);
            this.input = fin;
            this.output = fout;
            try { this.fd = fin.getFD(); } catch (Exception ignore) {}
            this.serialPortFileObj = null;
            this.openedPath = path;
            // 純 Java 開不出 termios，必須用 stty 把波特率從預設 9600 改到 115200，
            // 否則 MCU 侧全是亂碼（2026-09 實測教訓）。busybox 自帶 stty。
            configureBaudStty(path, baudrate);
            Log.i(TAG, "opened via File: " + path);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "File open failed for " + path + ": " + e.getMessage());
            closeQuietly();
            return false;
        }
    }

    /** 用 busybox stty 配波特率+8N1 raw（File 路徑專用，JNI 路徑 native 已配好）。 */
    private static void configureBaudStty(String path, int baudrate) {
        String[] cmds = {
                "busybox stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
                "stty -F " + path + " " + baudrate + " cs8 -cstopb -parenb raw -echo",
        };
        for (String cmd : cmds) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + "; busybox stty -F " + path + " 2>&1 | head -1"});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append(' ');
                p.waitFor();
                Log.i(TAG, "stty " + path + " [" + cmd.split(" ")[0] + "] -> " + sb.toString().trim());
                if (sb.toString().contains(String.valueOf(baudrate))) return; // 配好了就不用試下一條
            } catch (Exception e) {
                Log.d(TAG, "stty attempt failed (" + cmd.split(" ")[0] + "): " + e.getMessage());
            }
        }
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
                                Log.d(TAG, "RX(" + openedPath + ") " + SerialFrameCodec.toHex(r.frame));
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
                            if (pos >= buf.length - 256) pos = 0; // 防溢出，丟棄
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

    public synchronized void close() {
        running.set(false);
        if (readerThread != null) { try { readerThread.interrupt(); readerThread.join(500); } catch (InterruptedException ignore) {} readerThread = null; }
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (input != null) input.close(); } catch (IOException ignore) {}
        // output 關閉會連帶關 fd；input/output 同 fd 時關一次即可，但分開 try 更穩
        try { if (output != null) output.close(); } catch (IOException ignore) {}
        if (serialPortFileObj != null) {
            try { serialPortFileObj.getClass().getMethod("close").invoke(serialPortFileObj); } catch (Exception ignore) {}
        }
        input = null; output = null; fd = null; serialPortFileObj = null; openedPath = null;
    }
}
