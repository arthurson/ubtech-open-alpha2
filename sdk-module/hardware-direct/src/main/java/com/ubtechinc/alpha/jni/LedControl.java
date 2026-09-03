package com.ubtechinc.alpha.jni;

import android.util.Log;

/**
 * 3.002 版 libhead_led.so 的 JNI 桥（/dev/led_eye，ioctl 与 1.1.7.3 同 ABI：
 * On=0x4c00 / OFF=0x4c01 / Eye=0x4c02 / Head=0x4c03 / Mouth=0x4c04，均 _IOW('L')，
 * 反汇编逐个核对过）。
 *
 * <p>包名+方法签名必须与 .so 导出符号逐字对应（由 3.002 APK classes2.dex 抽出，
 * 自研 DexSig 解析确认）：
 * {@code Java_com_ubtechinc_alpha_jni_LedControl_open/close/ledSetOFF/ledSetOn/
 * ledSetEye/ledSetHead/ledSetMouth}，全部 {@code public static native}。</p>
 *
 * <p>与旧 {@code com.ubtechinc.mic5.LedControl} 的差别只有两处：
 * 包名不同；{@code ledSetOFF(I)} 多一个 int（反汇编证实该参数只进 log，不落硬件，
 * 传 0 即可）。其余签名逐字相同。</p>
 *
 * <p>返回值沿用旧约定（native 成功回 0/false，失败回 0xF2/true，见 DirectLedController）。</p>
 */
public class LedControl {
    private static final String TAG = "LedControl3002";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("head_led");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary head_led failed: " + t.getMessage());
        }
    }

    public static native boolean open();

    public static native boolean close();

    /** arg 仅 native log 用（反汇编：mov r3,r4 后只传给 __android_log_print），传 0。 */
    public static native boolean ledSetOFF(int dummy);

    public static native boolean ledSetOn(int i);

    public static native boolean ledSetEye(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetHead(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetMouth(int runTime, int breatheSpeedMs, int offDurationMs, int playDurationMs, int effectMode);
}
