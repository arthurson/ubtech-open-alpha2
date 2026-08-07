package com.ubtechinc.mic5;

public class LedControl {
    public static native boolean close();

    public static native boolean ledSetEye(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetHead(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetMouth(int runtime, int mode, int bright, int uptime, int downtime);

    public static native boolean ledSetOn(int i);

    public static native boolean open();

    static {
        System.loadLibrary("head_led");
    }
}