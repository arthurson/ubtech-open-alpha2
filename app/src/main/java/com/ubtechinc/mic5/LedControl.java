package com.ubtechinc.mic5;

public class LedControl {
    public static native boolean close();

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