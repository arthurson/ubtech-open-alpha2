package com.ubtechinc.alpha.jni;

import android.util.Log;

/**
 * 3.002 版 libhead_led.so 的 JNI 橋（/dev/led_eye，ioctl 與 1.1.7.3 同 ABI：
 * On=0x4c00 / OFF=0x4c01 / Eye=0x4c02 / Head=0x4c03 / Mouth=0x4c04，均 _IOW('L')，
 * 反匯編逐個核對過）。
 *
 * <p>包名+方法簽名必須與 .so 導出符號逐字對應（由 3.002 APK classes2.dex 抽出，
 * 自研 DexSig 解析確認）：
 * {@code Java_com_ubtechinc_alpha_jni_LedControl_open/close/ledSetOFF/ledSetOn/
 * ledSetEye/ledSetHead/ledSetMouth}，全部 {@code public static native}。</p>
 *
 * <p>與舊 {@code com.ubtechinc.mic5.LedControl} 的差別只有兩處：
 * 包名不同；{@code ledSetOFF(I)} 多一個 int（反匯編證實該參數只進 log，不落硬件，
 * 傳 0 即可）。其餘簽名逐字相同。</p>
 *
 * <p>返回值：淨係 ledSet* 反轉（native 成功回 0/false，失敗回 0xF2/true——
 * 反匯編證實 ioctl 成功即回 0，見 MouthLedData:95-103）；open()/close() 唔反轉，
 * 照字面用（DirectLedController.callLedReflect 嘅 `if(!open)fail` 就係咁解）。</p>
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

    /** arg 僅 native log 用（反匯編：mov r3,r4 後只傳給 __android_log_print），傳 0。 */
    public static native boolean ledSetOFF(int dummy);

    public static native boolean ledSetOn(int i);

    public static native boolean ledSetEye(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetHead(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8);

    public static native boolean ledSetMouth(int runTime, int breatheSpeedMs, int offDurationMs, int playDurationMs, int effectMode);
}
