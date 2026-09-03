package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 對上層 (MainActivity/HttpServer) 暴露的統一入口。pure-direct 模式：
 * 所有胸/頭硬件只走 /dev/ttyS1 + /dev/ttyS3 + libhead_led.so JNI，
 * 不再 bind com.ubtechinc.alpha2services（機身已無此 APK，無 fallback）。
 *
 * <p>實測本機 ttyS1/ttyS3 為 777，普通應用也可直接 open；若 open 失敗則
 * isDirectAvailable()=false，調用方直接報錯，不再嘗試任何 binder 回退。</p>
 */
public final class HardwareDirectManager {
    private static final String TAG = "HardwareDirectManager";
    private static HardwareDirectManager sInstance;

    private final DirectChestController chest;
    private final DirectHeadController head;
    private boolean directReady = false;

    private HardwareDirectManager(Context ctx) {
        DirectSerialPort chestPort = DirectSerialPort.forChest();
        DirectSerialPort headPort = DirectSerialPort.forHead();
        this.chest = new DirectChestController(chestPort);
        this.head  = new DirectHeadController(headPort);
    }

    public static synchronized HardwareDirectManager get(Context ctx) {
        if (sInstance == null) sInstance = new HardwareDirectManager(ctx.getApplicationContext());
        return sInstance;
    }

    /** 嘗試打開直驅串口，返回是否成功。應在 MainActivity.onCreate 的後臺線程調用。 */
    public synchronized boolean tryEnableDirect() {
        boolean c = chest.open();
        boolean h = head.open();
        // 頭板失敗不影響胸板舵機，任一成功就算部分可用
        directReady = c || h;
        Log.i(TAG, "tryEnableDirect chest=" + c + " head=" + h + " directReady=" + directReady);
        if (!directReady) {
            chest.close();
            head.close();
        }
        return directReady;
    }

    public boolean isDirectAvailable() { return directReady && (chest.isAvailable() || head.isAvailable()); }

    public DirectChestController chest() { return chest; }
    public DirectHeadController head() { return head; }

    public void release() {
        chest.close();
        head.close();
        directReady = false;
    }

    /** 便捷：舵機直驅發送（pure-direct，無 binder 回退，失敗直接返回 false）。 */
    public boolean chestSendSingle(byte id, int angle, short time) {
        if (!isDirectAvailable()) return false;
        return chest.setSingleServo(id, angle, time);
    }
}
