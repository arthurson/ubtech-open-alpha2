package com.ubtechinc.alpha.jni.headkey;

import android.util.Log;

/**
 * 3.002 版 libhead_key_mgr.so 的 JNI 桥（rk29-keypad，/dev/input/event*）。
 *
 * <p>类名+方法签名必须与 .so 导出符号逐字对应（由 3.002 APK classes2.dex 抽出，
 * 自研 DexSig 解析确认）：
 * {@code Java_com_ubtechinc_alpha_jni_headkey_HeadKeyMgr_Init/Add/nativeInit/
 * nativeThreadStart/nativeThreadStop}（均为 public 实例 native），外加
 * 原装同包私有桩 {@code convertHeadKeyEvent(I)[B / newConvertHeadKeyEvent(I)V /
 * publishEvent(B)V}（native 可能 GetMethodID，直接缺席会导致 native 线程静默死，
 * 故保留同签名桩；调用即打 log 供核对）。</p>
 *
 * <p>回调：native 按键线程调 {@code onNativeCallback(I)}（public 实例方法，原装本意由
 * 子类/本类实现转换后 publish）。本类空实现，app 侧 HeadKeyPoller 继承并 override，
 * 把码值喂进既有 gesture 合成管线。native 另写 {@code /sdcard/keyjnilog.txt}
 * 调试日志，可对照原始码。</p>
 *
 * <p>时序（经验）：{@code new → Init() → nativeInit() → nativeThreadStart()}；
 * 停用 {@code nativeThreadStop()}。{@code Add(II)} 暂不调用（用途未明，疑为
 * 注册额外事件节点；默认扫描已覆盖 rk29-keypad）。任一步失败抛错，调用方回退
 * Java 直读 /dev/input/event0。</p>
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

    /** .so 是否已载入；未载入时一切 native 调用都会抛 UnsatisfiedLinkError。 */
    public static boolean isLibLoaded() {
        return sLibLoaded;
    }

    public native int Add(int a, int b);

    public native boolean Init();

    public native void nativeInit();

    public native void nativeThreadStart();

    public native void nativeThreadStop();

    /** native 按键线程回调（子类 override；默认空实现）。 */
    public void onNativeCallback(int code) {
    }

    // ---- 原装同签名私有桩（防 native GetMethodID 落空；被调即 log） ----
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
