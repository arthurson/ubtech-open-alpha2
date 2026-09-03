package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 对上层 (MainActivity/HttpServer) 暴露的统一入口。pure-direct 模式：
 * 所有胸/头硬件只走 /dev/ttyS1 + /dev/ttyS3 + libhead_led.so JNI，
 * 不再 bind com.ubtechinc.alpha2services（机身已无此 APK，无 fallback）。
 *
 * <p>实测本机 ttyS1/ttyS3 为 777，普通应用也可直接 open；若 open 失败则
 * isDirectAvailable()=false，调用方直接报错，不再尝试任何 binder 回退。</p>
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

    /** 尝试打开直驱串口，返回是否成功。应在 MainActivity.onCreate 的后台线程调用。 */
    public synchronized boolean tryEnableDirect() {
        boolean c = chest.open();
        boolean h = head.open();
        // 头板失败不影响胸板舵机，任一成功就算部分可用
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

    /** 便捷：舵机直驱发送（pure-direct，无 binder 回退，失败直接返回 false）。 */
    public boolean chestSendSingle(byte id, int angle, short time) {
        if (!isDirectAvailable()) return false;
        return chest.setSingleServo(id, angle, time);
    }
}
