package com.example.t01;

import android.util.Log;

import com.ubtechinc.alpha.hardware.HeadKeyPoller;

/**
 * 1.1.7.3（未混淆版）2-mic 頭頂 +/- 鍵 JNI 橋。
 *
 * <p>類名、package、native 方法簽名必須同 {@code libNativeClassJni.so}
 * 導出符號逐字一致：{@code Java_com_example_t01_nativeClass_Init/Add/
 * nativeInit/nativeThreadStart/nativeThreadStop}。原廠
 * {@code com.alpha2.gesture.GestureService} 就係 new 呢個 class →
 * {@code Init() → nativeInit() → nativeThreadStart()}；native 線程讀
 * {@code /dev/input/event*}（rk29-keypad）之後回調
 * {@link #onNativeCallback(int)}，碼形如 {@code 0x3c01/0x3d01/0x4201}
 * （舊制 60/61/66）。</p>
 *
 * <p>原廠喺 callback 入面 sendBroadcast；openalpha2 直接轉交
 * {@link HeadKeyPoller}，行同一套 gesture 合成／去重。</p>
 */
public class nativeClass {
    private static final String TAG = "NativeClassJni";

    private static volatile HeadKeyPoller sPoller;

    static {
        System.loadLibrary("NativeClassJni");
    }

    public static void setPoller(HeadKeyPoller poller) {
        sPoller = poller;
    }

    public native boolean Init();

    public native int Add(int a, int b);

    public native void nativeInit();

    public native void nativeThreadStart();

    public native void nativeThreadStop();

    /** native 線程回調（碼值高 byte = 舊制 key code，低 byte = 0x01）。 */
    public void onNativeCallback(int var) {
        HeadKeyPoller p = sPoller;
        if (p != null) {
            p.onNativeClassCallback(var);
        } else {
            Log.w(TAG, "onNativeCallback with no poller: 0x" + Integer.toHexString(var));
        }
    }
}
