package com.ubtechinc.alpha.hardware;

import android.content.Context;
import android.util.Log;

/**
 * 對上層 (MainActivity/HttpServer) 暴露的統一入口。pure-direct 模式：
 * 不再 bind com.ubtechinc.alpha2services（機身已無此 APK，無 fallback）。
 * 10005 胸/頭走 /dev/ttyS1 + /dev/ttyS3 + libhead_led.so JNI；
 * 10002 走 /dev/ttySAC0 + /dev/ttySAC1 + 頭板 MCU 串口 LED。
 *
 * <p>實測本機 ttyS1/ttyS3 為 777，普通應用也可直接 open；若 open 失敗則
 * isDirectAvailable()=false，調用方直接報錯，不再嘗試任何 binder 回退。</p>
 */
public final class HardwareDirectManager {
    private static final String TAG = "HardwareDirectManager";
    private static HardwareDirectManager sInstance;

    private final DirectChestController chest;
    private final DirectHeadController head;
    private volatile boolean directReady = false; // isDirectAvailable() 無鎖讀，要 volatile
    private volatile boolean head2MicInited = false;

    private HardwareDirectManager(Context ctx) {
        DirectSerialPort chestPort = DirectSerialPort.forChest();
        DirectSerialPort headPort = DirectSerialPort.forHead();
        this.chest = new DirectChestController(chestPort);
        this.head  = new DirectHeadController(headPort);
    }

    public static synchronized HardwareDirectManager get(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx is null");
        if (sInstance == null) sInstance = new HardwareDirectManager(ctx.getApplicationContext());
        return sInstance;
    }

    /** 畀唔持有 Context 嘅硬件層（DirectLedController）攞現有 instance；未建立回 null。 */
    static HardwareDirectManager peek() {
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
        } else if (h && HardwareVersion.is2Mic() && !head2MicInited) {
            // 官方 2-mic 開機流程：頭板串口通之後先送 cmd5 + cmd20[1]。
            // 5-mic 唔行呢段；失敗下輪 tryEnableDirect 再試。
            head2MicInited = head.init2Mic();
        }
        return directReady;
    }

    public boolean isDirectAvailable() { return directReady && (chest.isAvailable() || head.isAvailable()); }

    public DirectChestController chest() { return chest; }
    public DirectHeadController head() { return head; }

    public void release() {
        synchronized (this) {
            chest.close();
            head.close();
            directReady = false;
            head2MicInited = false;
        }
    }

}
