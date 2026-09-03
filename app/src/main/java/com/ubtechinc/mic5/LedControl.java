package com.ubtechinc.mic5;

public class LedControl {
    public static native boolean close();

    // 2026-08-25 新增: libhead_led.so 導出表入面有 Java_..._ledSetOFF 呢個符號,
    // 原 demo class 沒有 declare - 疑似是「全部 LED 總開關」ioctl, 用來熄掉
    // ledSetOn(i) 點亮的單顆 LED (pad/wifi 燈實測 dark head/eye 指令熄不掉)。
    public static native boolean ledSetOFF();

    public static native boolean ledSetEye(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetHead(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    // Parameter names match MouthLedData's hardware-verified field semantics
    // (see MouthLedData javadoc for how each position was confirmed) rather than
    // the demo app's original, unverified names - do not rename positionally,
    // the native .so only reads these by position.
    public static native boolean ledSetMouth(int runTime, int breatheSpeedMs, int offDurationMs, int playDurationMs, int effectMode);

    public static native boolean ledSetOn(int i);

    public static native boolean open();

    static {
        System.loadLibrary("head_led");
    }
}