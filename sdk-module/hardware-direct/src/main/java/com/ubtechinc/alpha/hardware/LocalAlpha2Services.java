package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 1.1.7.3 这一代 alpha2services 的最小本地替代（pure-direct）。
 * 不复制 3.002 的 9991 个类，只实现对 open-alpha2 真正重要的胸/头直驱：
 *   - DirectSerialPort (胸 /dev/ttyS1，头 /dev/ttyS3)
 *   - 5-mic LED 的 JNI 分支（DirectLedController）
 *
 * <p>机身已无 com.ubtechinc.alpha2services，无 binder fallback：
 * start() 失败则调用方直接报错。</p>
 */
public final class LocalAlpha2Services {
    private static final String TAG = "LocalAlpha2Services";
    private final HardwareDirectManager direct;

    public LocalAlpha2Services(Context ctx) {
        this.direct = HardwareDirectManager.get(ctx);
    }

    /** 在 MainActivity.onCreate 的后台线程调用 */
    public boolean start() {
        boolean ok = direct.tryEnableDirect();
        Log.i(TAG, "LocalAlpha2Services start direct=" + ok);
        return ok;
    }

    public void stop() { direct.release(); }

    public boolean isDirectActive() { return direct.isDirectAvailable(); }

    // 胸口直驱快捷
    public boolean chestSetSingle(byte id, int angle, short time) {
        if (!isDirectActive()) return false;
        return direct.chest().setSingleServo(id, angle, time);
    }

    public boolean chestSetAll(int[] angles20, short time) {
        if (!isDirectActive()) return false;
        return direct.chest().setAllServos(angles20, time);
    }

    public boolean chestSonar(int cm) {
        if (!isDirectActive()) return false;
        return direct.chest().configureSonar(cm);
    }

    // 头直驱
    public boolean headNoise(boolean open) {
        if (!isDirectActive()) return false;
        return direct.head().setNoiseReduction(open);
    }

    // LED 走 JNI，不依赖串口是否可用
    public boolean ledHead(int color) { return DirectLedController.headSolid(color); }
    public boolean ledEye(int color)  { return DirectLedController.setEye5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0); }
    public boolean ledMouthBreathe(int speed) { return DirectLedController.setMouth(Integer.MAX_VALUE, speed, 0, Integer.MAX_VALUE, 1); }
    public boolean ledOff() { return DirectLedController.clear(); }
}
