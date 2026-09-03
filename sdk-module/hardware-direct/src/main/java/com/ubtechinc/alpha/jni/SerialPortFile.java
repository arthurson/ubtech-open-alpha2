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
 * libserial_port.so 的 JNI 桥（pure-direct 用）。
 *
 * <p>包名+类名+方法签名必须与 .so 导出的符号逐字对应：
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_open} /
 * {@code Java_com_ubtechinc_alpha_jni_SerialPortFile_close}（已用 NDK readelf 验证）。
 * 反编译确认 native open(path, baudrate, flags) 内部走 termios 配 115200，
 * 这正是纯 File 路径缺的那一步（实测 ttyS1 默认 9600，不配 baud 发什么 MCU 都不认）。</p>
 *
 * <p>用法与经典 android-serialport-api 一致；DirectSerialPort 经反射调用本类，
 * 避免 hardware-direct 编译期写死依赖。</p>
 */
public class SerialPortFile {
    private static final String TAG = "SerialPortFile";

    static {
        try {
            System.loadLibrary("serial_port");
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary serial_port failed: " + t.getMessage());
        }
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

    /** 供 DirectSerialPort 反射取 fd（File 包装用）。 */
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

    public native void close();
}
