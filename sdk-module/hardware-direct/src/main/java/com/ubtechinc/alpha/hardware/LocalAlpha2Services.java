package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 1.1.7.3 這一代 alpha2services 的最小本地替代（pure-direct）。
 * 不複製 3.002 的 9991 個類，只實現對 open-alpha2 真正重要的胸/頭直驅：
 *   - DirectSerialPort (胸 /dev/ttyS1，頭 /dev/ttyS3)
 *   - 5-mic LED 的 JNI 分支（DirectLedController）
 *
 * <p>機身已無 com.ubtechinc.alpha2services，無 binder fallback：
 * start() 失敗則調用方直接報錯。</p>
 */
public final class LocalAlpha2Services {
    private static final String TAG = "LocalAlpha2Services";
    private final HardwareDirectManager direct;

    public LocalAlpha2Services(Context ctx) {
        this.direct = HardwareDirectManager.get(ctx);
    }

    /** 在 MainActivity.onCreate 的後臺線程調用 */
    public boolean start() {
        boolean ok = direct.tryEnableDirect();
        Log.i(TAG, "LocalAlpha2Services start direct=" + ok);
        return ok;
    }

    public void stop() { direct.release(); }

    public boolean isDirectActive() { return direct.isDirectAvailable(); }

    // 胸口直驅快捷（2026-09-09：各查各板，唔好查「任一板」——頭板獨活唔代表胸掂）
    public boolean chestSetSingle(byte id, int angle, short time) {
        if (!direct.chest().isAvailable()) return false;
        return direct.chest().setSingleServo(id, angle, time);
    }

    public boolean chestSetAll(int[] angles20, short time) {
        if (!direct.chest().isAvailable()) return false;
        return direct.chest().setAllServos(angles20, time);
    }

    public boolean chestSonar(int cm) {
        if (!direct.chest().isAvailable()) return false;
        return direct.chest().configureSonar(cm);
    }

    // 頭直驅
    public boolean headNoise(boolean open) {
        if (!direct.head().isAvailable()) return false;
        return direct.head().setNoiseReduction(open);
    }

    // LED 走 JNI，不依賴串口是否可用
    public boolean ledHead(int color) { return DirectLedController.headSolid(color); }
    public boolean ledEye(int color)  { return DirectLedController.setEye5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0); }
    public boolean ledMouthBreathe(int speed) { return DirectLedController.setMouth(Integer.MAX_VALUE, speed, 0, Integer.MAX_VALUE, 1); }
    public boolean ledOff() { return DirectLedController.clear(); }
}
