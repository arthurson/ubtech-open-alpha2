package android_serialport_api;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 1.1.7.3 原裝 {@code android_serialport_api.SerialPort} 嘅最小副本。
 *
 * <p>2-mic（Android 4.4.2 / Exynos）用官方 APK 內嘅
 * {@code libserial_port_2mic.so}（原檔名 libserial_port.so，重新命名避免同
 * 5-mic 自製 libserial_port.so 撞名）。類名／package／native 簽名必須同
 * .so 導出符號一致：{@code Java_android_serialport_api_SerialPort_open/close}。
 * 權限 chmod 由 {@code DirectSerialPort.ensure2MicAccess()} 喺 open 前處理。</p>
 */
public class SerialPort {
    private static final String TAG = "SerialPort2Mic";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("serial_port_2mic");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary serial_port_2mic failed: " + t.getMessage());
        }
    }

    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    public SerialPort(File device, int baudrate, int flags) throws SecurityException, IOException {
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) throw new IOException("native open failed for " + device);
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    public FileDescriptor getFD() {
        return mFd;
    }

    private static native FileDescriptor open(String path, int baudrate, int flags);

    public native void close();
}
