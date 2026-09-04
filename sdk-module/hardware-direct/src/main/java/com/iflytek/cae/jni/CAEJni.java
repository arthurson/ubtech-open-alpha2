package com.iflytek.cae.jni;

import android.util.Log;

/**
 * iFlytek CAE 麦克风阵列引擎 JNI 桥（1.1.7.3 原装 {@code three_wakeup_cae.so}）。
 *
 * <p>包名+方法签名必须与 .so 导出符号逐字对应（由 1.1.7.3 APK 反汇编核对；
 * {@code CAENew} 为双 overload 共用同一 C 符号）：
 * {@code Java_com_iflytek_cae_jni_CAEJni_CAEAudioWrite/CAEDestroy/CAEExtract16K/
 * CAENew/CAEReset/CAESetRealBeam/CAESetWParam/DebugLog}。</p>
 *
 * <p>回调约定（.so 内 log 字串作证）：native 在 userdata 对象上按名查找
 * {@code ivwCb(IIFII[C)V} 与 {@code audioCb([BIII)V} 并回调；
 * 对应官方文档 {@code cae_ivw_cb(angle, beam, keyword, power, score)}，
 * angle 即声源方位角。userdata 传调用方自己的回调对象即可，不必经
 * 原装 {@code CAEEngine}。</p>
 */
public class CAEJni {
    private static final String TAG = "CAEJni";

    static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("three_wakeup_cae");
            sLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary three_wakeup_cae failed: " + t.getMessage());
        }
    }

    /** .so 是否已载入；未载入时一切 native 调用都会抛 UnsatisfiedLinkError。 */
    public static boolean isLoaded() {
        return sLoaded;
    }

    private static int sWakeUpTypeEngin = 0;

    /** 原装纯 Java 状态位（非 native），保留同签名以兼容。 */
    public static void setWakeUpTypeEngin(int t) {
        sWakeUpTypeEngin = t;
    }

    /** 原装纯 Java 状态位（非 native，private 同签名）。 */
    private static int getWakeUpType() {
        return sWakeUpTypeEngin;
    }

    private final int mMode;

    /** 原装构造 {@code <init>(I)V}（纯 Java，存参而已）。 */
    public CAEJni(int mode) {
        mMode = mode;
    }

    public static native int CAENew(String resPath, int i2,
            String s3, String s4, String s5, Object callback);

    public static native int CAENew(String resPath,
            String s2, String s3, String s4, Object callback);

    public static native int CAEAudioWrite(int handle, byte[] data, int len);

    public static native int CAEDestroy(int handle);

    public static native int CAEExtract16K(byte[] in, int a, int b, byte[] out, Object o);

    public static native int CAEReset(int handle);

    public static native int CAESetRealBeam(int handle, int beam);

    public static native int CAESetWParam(int handle, byte[] p1, byte[] p2);

    public static native void DebugLog(boolean on);
}
