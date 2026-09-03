package com.ubtechinc.alpha.jni.headkey;

import android.util.Log;

/**
 * 3.002 版 libhead_key_mgr.so 的 JNI 橋（rk29-keypad，/dev/input/event*）。
 *
 * <p>類名+方法簽名必須與 .so 導出符號逐字對應（由 3.002 APK classes2.dex 抽出，
 * 自研 DexSig 解析確認）：
 * {@code Java_com_ubtechinc_alpha_jni_headkey_HeadKeyMgr_Init/Add/nativeInit/
 * nativeThreadStart/nativeThreadStop}（均為 public 實例 native），外加
 * 原裝同包私有樁 {@code convertHeadKeyEvent(I)[B / newConvertHeadKeyEvent(I)V /
 * publishEvent(B)V}（native 可能 GetMethodID，直接缺席會導致 native 線程靜默死，
 * 故保留同簽名樁；調用即打 log 供核對）。</p>
 *
 * <p>回調：native 按鍵線程調 {@code onNativeCallback(I)}（public 實例方法，原裝本意由
 * 子類/本類實現轉換後 publish）。本類空實現，app 侧 HeadKeyPoller 繼承並 override，
 * 把碼值喂進既有 gesture 合成管線。native 另寫 {@code /sdcard/keyjnilog.txt}
 * 調試日志，可對照原始碼。</p>
 *
 * <p>時序（經驗）：{@code new → Init() → nativeInit() → nativeThreadStart()}；
 * 停用 {@code nativeThreadStop()}。{@code Add(II)} 暫不調用（用途未明，疑為
 * 注冊額外事件節點；預設掃描已覆蓋 rk29-keypad）。任一步失敗拋錯，調用方回退
 * Java 直讀 /dev/input/event0。</p>
 */
public class HeadKeyMgr {
    private static final String TAG = "HeadKeyMgr";

    static boolean sLibLoaded = false;

    static {
        try {
            System.loadLibrary("head_key_mgr");
            sLibLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "loadLibrary head_key_mgr failed: " + t.getMessage());
        }
    }

    /** .so 是否已載入；未載入時一切 native 調用都會拋 UnsatisfiedLinkError。 */
    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    public native int Add(int a, int b);

    public native boolean Init();

    public native void nativeInit();

    public native void nativeThreadStart();

    public native void nativeThreadStop();

    /** native 按鍵線程回調（子類 override；預設空實現）。 */
    public void onNativeCallback(int code) {
    }

    // ---- 原裝同簽名私有樁（防 native GetMethodID 落空；被調即 log） ----
    @SuppressWarnings("unused")
    private byte[] convertHeadKeyEvent(int code) {
        Log.d(TAG, "convertHeadKeyEvent(" + code + ") stub hit");
        return null;
    }

    @SuppressWarnings("unused")
    private void newConvertHeadKeyEvent(int code) {
        Log.d(TAG, "newConvertHeadKeyEvent(" + code + ") stub hit");
    }

    @SuppressWarnings("unused")
    private void publishEvent(byte b) {
        Log.d(TAG, "publishEvent(" + b + ") stub hit");
    }
}
