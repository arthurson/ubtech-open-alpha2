package com.ubtechinc.alpha.hardware;

import android.util.Log;

/**
 * 5-mic 頭/眼/嘴 LED 的 JNI 直驅。
 * 完全繞過 alpha2services 的 AIDL，直接走 libhead_led.so 的 ioctl。
 * 3.002 版（/dev/led_eye，ioctl 號與 1.1.7.3 逐個相同：On=0x4c00/OFF=0x4c01/
 * Eye=0x4c02/Head=0x4c03/Mouth=0x4c04，反匯編核對過）。
 * 已在 open-alpha2 的 MouthLedData 驗證過。
 *
 * <p>為避免 sdk-module 硬件層與 app 層的循環依賴，這裡用反射調用
 * {@code com.ubtechinc.alpha.jni.LedControl}（3.002 原裝包名；舊
 * {@code com.ubtechinc.mic5.LedControl} 保留文件但不再引用）。
 * 這樣 hardware-direct 編譯期不依賴 app，執行時通過自身 dex 找到類。</p>
 */
public final class DirectLedController {
    private static final String TAG = "DirectLedController";
    private static final String LED_CTRL = "com.ubtechinc.alpha.jni.LedControl";

    private DirectLedController() {}

    // 複用 AIDL_REFERENCE 3.1 的已驗證參數表
    // p1 color 1紅2綠3藍4黄5紫6青7白, p2 亮度1-9, p5/p6 時序, p7 runTime, p8 mode
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
        // 3.002 簽名 ledSetOFF(I)：反匯編證實該 int 只進 log（mov r3,r4 → __android_log_print），不落硬件，傳 0。
        return callLedReflect("ledSetOFF", new Class[]{int.class}, new Object[]{0}, "ledSetOFF");
    }

    public static boolean openAnd(boolean r) { return r; } // 佔位

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
            // libhead_led.so 的返回值是反的：ioctl 成功返回 0(false)，失敗返回 0xF2(true)，見 MouthLedData:100
            boolean ok = !raw;
            Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + desc + " raw=" + raw);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "LED exception " + desc + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * 全參數直驅（p1..p8 原樣透傳）。MainActivity 原來經 binder 調
     * header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, MAX, p8) /
     * header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, MAX, p8)，
     * pure-direct 下 p3/p4 保持原值，行為不變。
     */
    public static boolean setHead5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        return callLedReflect("ledSetHead", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
    }

    public static boolean setEye5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        return callLedReflect("ledSetEye", new Class[]{int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class},
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
    }

    // 預設：color 沿用 1=紅…7=白編碼（見 LedCenter）
    public static boolean headBreathing(int color) {
        return setHead5Mic(color, 9, 5, 20, Integer.MAX_VALUE, 1);
    }
    public static boolean headSolid(int color) {
        return setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }
    public static boolean clear() { return setOff(); }

    /**
     * pure-direct 下取代 AIDL stop5MicEarLED / stop5MicEyeLED。
     * app 侧 LibControl 沒有獨立的 stop5Mic native，對應 AIDL 在機身侧最終也是關斷
     * 同一條 5-mic 通路，這裡統一走 ledSetOFF，與 clear() 同義，保留兩個名字只是
     * 為了讓 LedCenter 的 stop preset 語義與原來一致。
     */
    public static boolean stopHead5Mic() { return setOff(); }
    public static boolean stopEye5Mic() { return setOff(); }
}
