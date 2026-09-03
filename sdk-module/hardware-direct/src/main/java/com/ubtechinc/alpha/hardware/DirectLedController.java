package com.ubtechinc.alpha.hardware;

import android.util.Log;

/**
 * 5-mic 头/眼/嘴 LED 的 JNI 直驱。
 * 完全绕过 alpha2services 的 AIDL，直接走 libhead_led.so 的 ioctl。
 * 3.002 版（/dev/led_eye，ioctl 号与 1.1.7.3 逐个相同：On=0x4c00/OFF=0x4c01/
 * Eye=0x4c02/Head=0x4c03/Mouth=0x4c04，反汇编核对过）。
 * 已在 open-alpha2 的 MouthLedData 验证过。
 *
 * <p>为避免 sdk-module 硬件层与 app 层的循环依赖，这里用反射调用
 * {@code com.ubtechinc.alpha.jni.LedControl}（3.002 原装包名；旧
 * {@code com.ubtechinc.mic5.LedControl} 保留文件但不再引用）。
 * 这样 hardware-direct 编译期不依赖 app，运行时通过自身 dex 找到类。</p>
 */
public final class DirectLedController {
    private static final String TAG = "DirectLedController";
    private static final String LED_CTRL = "com.ubtechinc.alpha.jni.LedControl";

    private DirectLedController() {}

    // 复用 AIDL_REFERENCE 3.1 的已验证参数表
    // p1 color 1红2绿3蓝4黄5紫6青7白, p2 亮度1-9, p5/p6 时序, p7 runTime, p8 mode
    public static boolean setHead5Mic(int color, int bright, int p5, int p6, int runTime, int mode) {
        return callLedReflect("ledSetHead", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{color, bright, color, color, p5, p6, runTime, mode}, "head color=" + color + " mode=" + mode);
    }

    public static boolean setEye5Mic(int color, int bright, int p5, int p6, int runTime, int mode) {
        return callLedReflect("ledSetEye", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{color, bright, color, color, p5, p6, runTime, mode}, "eye color=" + color + " mode=" + mode);
    }

    public static boolean setMouth(int runTime, int breathe, int off, int play, int mode) {
        return callLedReflect("ledSetMouth", new Class[]{int.class,int.class,int.class,int.class,int.class},
                new Object[]{runTime, breathe, off, play, mode}, "mouth mode=" + mode);
    }

    public static boolean setOn(int index) {
        return callLedReflect("ledSetOn", new Class[]{int.class}, new Object[]{index}, "ledSetOn " + index);
    }

    public static boolean setOff() {
        // 3.002 签名 ledSetOFF(I)：反汇编证实该 int 只进 log（mov r3,r4 → __android_log_print），不落硬件，传 0。
        return callLedReflect("ledSetOFF", new Class[]{int.class}, new Object[]{0}, "ledSetOFF");
    }

    public static boolean openAnd(boolean r) { return r; } // 占位

    private static boolean callLedReflect(String method, Class<?>[] types, Object[] args, String desc) {
        try {
            Class<?> cls = Class.forName(LED_CTRL);
            Object openRes = cls.getMethod("open").invoke(null);
            if (openRes instanceof Boolean && !(Boolean) openRes) {
                Log.w(TAG, "LedControl.open() failed for " + desc);
                return false;
            }
            boolean raw = false;
            try {
                Object r = cls.getMethod(method, types).invoke(null, args);
                raw = r instanceof Boolean ? (Boolean) r : false;
            } finally {
                try { cls.getMethod("close").invoke(null); } catch (Exception ignore) {}
            }
            // libhead_led.so 的返回值是反的：ioctl 成功返回 0(false)，失败返回 0xF2(true)，见 MouthLedData:100
            boolean ok = !raw;
            Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + desc + " raw=" + raw);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "LED exception " + desc + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * 全参数直驱（p1..p8 原样透传）。MainActivity 原来经 binder 调
     * header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, MAX, p8) /
     * header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, MAX, p8)，
     * pure-direct 下 p3/p4 保持原值，行为不变。
     */
    public static boolean setHead5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        return callLedReflect("ledSetHead", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
    }

    public static boolean setEye5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        return callLedReflect("ledSetEye", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
    }

    // 预设：与 MainActivity 的 translateColor 等价
    public static boolean headBreathing(int color) {
        return setHead5Mic(color, 9, 5, 20, Integer.MAX_VALUE, 1);
    }
    public static boolean headSolid(int color) {
        return setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }
    public static boolean clear() { return setOff(); }

    /**
     * pure-direct 下取代 AIDL stop5MicEarLED / stop5MicEyeLED。
     * app 侧 LibControl 没有独立的 stop5Mic native，对应 AIDL 在机身侧最终也是关断
     * 同一条 5-mic 通路，这里统一走 ledSetOFF，与 clear() 同义，保留两个名字只是
     * 为了让 MainActivity 的 stop preset 语义与原来一致。
     */
    public static boolean stopHead5Mic() { return setOff(); }
    public static boolean stopEye5Mic() { return setOff(); }
}
