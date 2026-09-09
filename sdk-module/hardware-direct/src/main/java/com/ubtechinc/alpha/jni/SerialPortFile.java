package com.ubtechinc.alpha.jni;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * libserial_port.so 的 JNI 橋（pure-direct 用）。
 *
 * <p>包名+類名+方法簽名必須與 .so 導出的符號逐字對應：
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_open} /
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_close}（已用 NDK readelf 驗證）。
 * 反編譯確認 native open(path, baudrate, flags) 內部走 termios 配 115200，
 * 這正是純 File 路徑缺的那一步（實測 ttyS1 預設 9600，不配 baud 發什麼 MCU 都不認）。</p>
 *
 * <p>用法與經典 android-serialport-api 一致；DirectSerialPort 經反射調用本類，
 * 避免 hardware-direct 編譯期寫死依賴。</p>
 */
public class SerialPortFile implements java.io.Closeable {
    private static final String TAG = "SerialPortFile";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("serial_port");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary serial_port failed: " + t.getMessage());
        }
    }

    /** .so 是否已載入；調用方開串口前可預檢（同 LedControl/HeadKeyMgr 睇齊）。 */
    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    public SerialPortFile(File device, int baudrate, int flags) throws SecurityException, IOException {
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) {
            Log.e(TAG, "native open returned null fd for " + device);
            throw new IOException("native open failed for " + device);
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    /** 供 DirectSerialPort 反射取 fd（File 包裝用）。 */
    public FileDescriptor getFD() {
        return mFd;
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    private static native FileDescriptor open(String path, int baudrate, int flags);

    /**
     * 關閉 native fd（同時滿足 {@link java.io.Closeable}——throws 窄過 interface 得）。
     * 注意唔係冪等：同一個 fd 閂兩次有誤關復用號風險；擁有權歸 DirectSerialPort
     *（JNI 路徑只行呢度一次，見 DirectSerialPort.closeQuietly），唔好喺外面亂調。
     */
    public native void close();
}
