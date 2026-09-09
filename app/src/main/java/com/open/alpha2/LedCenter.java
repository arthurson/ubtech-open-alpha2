package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.MouthLedData;
import com.ubtechinc.alpha.jni.LedControl;

import java.util.Map;

/**
 * LED 層：5-mic 頭/眼、嘴部、底層 JNI/serial 除錯端點。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第八刀)：5 個 endpoint
 * case body (led/head/set、led/eye/set、led/mouth/set、debug/jni/led、
 * debug/serial/send) + TTS 嘴燈 bracket + hex 解析，邏輯一字不改搬過嚟。
 * Pad 燈 (手勢用，物理先驗到)、wifi 燈 (要切 wifi 先驗到)、PIR/避障指示
 * 留喺 MainActivity —— 遠程驗唔到嘅唔郁。只需要 Context
 * (頭串口 ready check)；嘴燈／5-mic／JNI 本身全部 static 直驅。
 * 2026-09 dispatcher Phase 1 第二刀加：pir/set、pir/alert_enabled
 * 2 個 handleApi case body 搬入；小件拼盤加埋 PIR 事件接線
 * (registerAlpha2PirAlertListener，警示反應本身早已喺呢度)。
 */
public final class LedCenter {
    private static final String TAG = "LedCenter";

    private final Context appContext;
    private final Handler mainHandler;
    private final RingtoneCenter ringtoneCenter;

    public LedCenter(Context context, Handler mainHandler, RingtoneCenter ringtoneCenter) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.ringtoneCenter = ringtoneCenter;
    }

    /** onDestroy 共用：停 pad LED worker（下面 shutdownNow 嗰段搬過嚟）。 */
    public void shutdown() {
        padLedExecutor.shutdownNow();
    }

    // -- Pad (+/-) 實體鍵指示燈 -----------------------------------------------
    // 真機掃描確認: ledSetOn(14) = volume- 燈, ledSetOn(16) = volume+ 燈。
    // 2026-09 A/B 驗證：firmware 唔會自亮，按住期間由 app 連發補燈，放手補 OFF；
    // wifi 燈 (12/13) firmware 唔會自己著，繼續手動（三態：熄/紅/藍）。
    // （舊註：1.1.7.3 .so 年代註解保留作 mapping 參考。）
    private static final int PAD_LED_INDEX_MINUS = 14;
    private static final int PAD_LED_INDEX_PLUS = 16;
    private static final long PAD_LED_INTERVAL_MS = 80;
    // 2026-09：alpha2services 已移除，無人再搶 /dev/led_eye，重試只防偶發打唔開。
    private static final int PAD_LED_OPEN_ATTEMPTS = 3;
    private final java.util.concurrent.ExecutorService padLedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 2026-09: onDestroy() 會 shutdownNow() 上面條 executor，但 wifi receiver
     *  之前 postDelayed 咗嘅 runnable (1200ms) 仲會喺之後照開，嗰陣再排就撞上
     *  RejectedExecutionException 炒喺 main thread (實機 logcat 見過)——app
     *  收緊皮嗰陣掉咗個 LED 更新係正確行為，吞咗佢。
     *  公開係因為 mute 鍵小智開關／mute LED 發送都借呢條單線程做背景執行
     *  (沿用舊安排，唔另開 thread 打亂排序)。 */
    public void postPadLed(Runnable r) {
        try {
            padLedExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private volatile boolean padMinusHeld = false;
    private volatile boolean padPlusHeld = false;
    private volatile boolean padLedWorkerRunning = false;

    /** 手勢層設定實體鍵狀態 (0x5a-0x5f)，跟住即刻調 padLedUpdate()。 */
    public void setPadMinusHeld(boolean held) {
        padMinusHeld = held;
    }

    public void setPadPlusHeld(boolean held) {
        padPlusHeld = held;
    }

    /**
     * 2026-09 A/B 驗證結論：撳住 +/- firmware 唔會自亮 14/16（press-ON 刪除後實測
     * 全暗；早前 suppressed build 見到著燈未能重現，不可依賴），所以按住期間繼續由
     * app 主動點亮；放手後補 ledSetOFF 清場。單線程 worker，跑緊唔重入。
     */
    public void padLedUpdate() {
        if (padLedWorkerRunning) {
            return;
        }
        padLedWorkerRunning = true;
        postPadLed(() -> {
            int lastCombo = -1; // bit0=minus bit1=plus；只在組合變化先補發（無人搶燈）
            try {
                for (;;) {
                    while (padMinusHeld || padPlusHeld) {
                        int combo = (padMinusHeld ? 1 : 0) | (padPlusHeld ? 2 : 0);
                        if (combo != lastCombo) {
                            assertPadLedsComboBurst();
                            lastCombo = combo;
                        }
                        Thread.sleep(PAD_LED_INTERVAL_MS);
                    }
                    assertPadLedsOffBurst();
                    if (!padMinusHeld && !padPlusHeld) break;
                    // 熄燈途中又撳過：兜返去 loop，唔交棒
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                padLedWorkerRunning = false;
            }
        });
    }

    /** Gap between open() attempts inside one burst (ms). */
    private static final long PAD_LED_RETRY_GAP_MS = 40;

    /**
     * 按住期間點亮組合 burst：open 到就按 padMinusHeld/padPlusHeld 點 14/16
     *（累加式，兩顆齊撳兩顆都著），打唔開就重試。
     */
    private boolean assertPadLedsComboBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "pad LED open() threw", t);
            }
            if (openOk) {
                try {
                    if (padMinusHeld) {
                        LedControl.ledSetOn(PAD_LED_INDEX_MINUS);
                    }
                    if (padPlusHeld) {
                        LedControl.ledSetOn(PAD_LED_INDEX_PLUS);
                    }
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (attempt > 1) {
                    Log.d(TAG, "pad LED device opened on attempt " + attempt);
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "pad LED open() failed " + PAD_LED_OPEN_ATTEMPTS
                + "x in a row (device busy?)");
        return false;
    }

    /**
     * 放手後熄燈 burst：連發 ledSetOFF（已熄即 no-op）。
     * 注意 ledSetOFF 會連 wifi 12/13 一起清，wifi 燈由 applyWifiLed 在狀態變化時重設。
     */

    /**
     * Same burst pattern but asserting ledSetOFF() instead of the held combo -
     * used after release so the pads go dark even if we have to wait out a race.
     */
    private boolean assertPadLedsOffBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "pad LED open() threw (off)", t);
            }
            if (openOk) {
                try {
                    LedControl.ledSetOFF(0);
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "pad LED off: open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    // -- WiFi 燈 (12 藍=有網 / 13 紅=無網) --------------------------------------
    private static final int WIFI_LED_INDEX_BLUE = 12;
    private static final int WIFI_LED_INDEX_RED = 13;
    private BroadcastReceiver wifiLedReceiver;
    private Runnable wifiLedReapply;

    public void registerWifiLedReceiver() {
        wifiLedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                // 收斂：熄 wifi 時 DISABLED 同 disconnected 兩個 broadcast 先後不定，
                // 先到的若判了紅，後到的熄燈蓋過；反之亦然。1.2s 後按權威狀態重設一次，
                // 保證最終一致（單線程 executor 保序，debounce 防堆積）。
                if (wifiLedReapply != null) mainHandler.removeCallbacks(wifiLedReapply);
                wifiLedReapply = new Runnable() {
                    @Override public void run() { applyWifiLed(); }
                };
                mainHandler.postDelayed(wifiLedReapply, 1200);
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    // 開關掣本身：熄了即熄燈；其他狀態轉 query 最新為準。
                    int st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                    if (st == WifiManager.WIFI_STATE_DISABLED
                            || st == WifiManager.WIFI_STATE_DISABLING) {
                        applyWifiLedState(false, false);
                    } else {
                        applyWifiLed();
                    }
                    return;
                }
                // 連線變化：intent 自帶的 NetworkInfo 即權威新狀態，直接用，
                // 唔重查（重查有 race：broadcast 到咗但 ConnectivityManager
                // 仲係舊值，會凍結喺紅燈，實機見過）。但開關制要現查——熄 wifi
                // 時 DISABLED 同 disconnected 兩個 broadcast 先後到，後者若
                // 當 wifi 仲開住就會點返紅燈蓋過熄燈。
                android.net.NetworkInfo info = intent != null ? intent
                        .getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO) : null;
                if (info != null && info.getType()
                        == android.net.ConnectivityManager.TYPE_WIFI) {
                    boolean onNow = isWifiEnabledNow();
                    applyWifiLedState(onNow, onNow && info.isConnected());
                } else {
                    applyWifiLed();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        appContext.registerReceiver(wifiLedReceiver, filter);

        // App 啟動時按當前狀態即刻設好。
        applyWifiLed();
    }

    public void unregisterWifiLedReceiver() {
        if (wifiLedReceiver != null) {
            try {
                appContext.unregisterReceiver(wifiLedReceiver);
                wifiLedReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** WiFi 燈狀態切換入口（已知名確狀態版） - 排給 pad LED 單線程 executor 執行。 */
    private void applyWifiLedState(final boolean wifiOn, final boolean connected) {
        postPadLed(() -> applyWifiLedInternal(wifiOn, connected));
    }

    /** 現查 wifi 開關制（裹 try/catch，查唔到當開住，由連線態決定）。 */
    private boolean isWifiEnabledNow() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            return wm == null || wm.isWifiEnabled();
        } catch (Exception e) {
            Log.w(TAG, "wifi enabled check failed", e);
            return true;
        }
    }

    /** WiFi 燈狀態切換入口（現查版：開機/開關變化時用） - 排給 pad LED 單線程 executor 執行。 */
    private void applyWifiLed() {
        final boolean wifiOn;
        final boolean connected;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            wifiOn = wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            Log.w(TAG, "wifi enabled check failed", e);
            return;
        }
        boolean conn = false;
        if (wifiOn) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.NetworkInfo ni =
                            cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
                    conn = ni != null && ni.isConnected();
                }
            } catch (Exception e) {
                Log.w(TAG, "wifi connected check failed", e);
            }
        }
        final boolean connectedNow = conn;
        postPadLed(() -> applyWifiLedInternal(wifiOn, connectedNow));
    }

    /**
     * 實際切換: 先 ledSetOFF() 清走舊色, 等 100ms, 再點目標顏色
     * （wifi 熄就唔點）。兩步都係 burst 重試式。
     */
    private void applyWifiLedInternal(boolean wifiOn, boolean connected) {
        try {
            assertPadLedsOffBurst();
            if (!wifiOn) return;
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        assertSingleLedBurst(connected ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED);
    }

    /** One burst: retry open()/dev/led_eye until it opens, light a single LED index. */
    private boolean assertSingleLedBurst(int index) {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "wifi LED open() threw", t);
            }
            if (openOk) {
                try {
                    LedControl.ledSetOn(index);
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "wifi LED open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    // -- Alpha2 PIR 警示反應 (LED+鈴聲) ----------------------------------------------
    // 2026-08-15 新增: 監聽獨立的 "alpha2_pir_state" event, 用 Alpha2 backend
    // 的 LED API 觸發 LED/鈴聲。
    //
    // 實機已確認: PIR raw 事件 (chest cmd=-109, "PIR HUMON DETECT") 會正常觸發 -
    // 這台機器底層 chest MCU 硬體本身能做 PIR。
    //
    // LED 部分: 眼/頭 5-mic LED 長亮紅燈 (setHeadEyeLedLong(1, 9)), 顏色代碼 1=紅,
    // 已在 "led/head/set" case 上面那段 comment 經實機確認過 (color: 1=紅 2=綠 3=藍
    // 4=黃 5=紫 6=青 7=白)。
    //
    // 2026-08-15 實機測試 (PIR sample test) 確認: 這台機器頭板的 5-mic head/eye LED
    // 對 PIR 警示反應是有效的 (眼/頭會亮紅燈), 不像之前 applyObstacleIndicator()/
    // registerChestMuteKeyTestListener() 遇到的情況 - 兩者用的是不同
    // AIDL 方法/參數組合, 不能直接假設「一個不行全部都不行」。所以 PIR 警示只
    // 走這一條路, 沒有再加 mouth LED breathing 做 fallback, 嘴部不用閃, 和鈴聲一起
    // 淨係眼/頭長著紅燈。
    private volatile boolean alpha2PirAlertActive = false;
    // 獨立於 pir/set 感應器硬件開關本身 - 預設關, 使用者要自己揀開先會有 LED/聲反應,
    // 避免一開機就無啦啦閃紅燈/響鈴。
    private volatile boolean alpha2PirAlertEnabled = false;

    /** 由 pir/alert_enabled endpoint (pirAlertEnabledResponse) 轉調。 */
    public void setPirAlertEnabled(boolean enabled) {
        alpha2PirAlertEnabled = enabled;
        if (!enabled && alpha2PirAlertActive) {
            // 中途關掉開關也要立即熄掉目前亮著的燈/停止正在播的聲音, 不只是不再對之後的
            // 事件有反應。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    applyAlpha2PirLedAndSound(false);
                }
            }).start();
        }
    }

    public synchronized void applyAlpha2PirLedAndSound(boolean triggered) {
        if (!alpha2PirAlertEnabled && triggered) {
            return; // 開關關閉 - 不理會新觸發 (但已經亮著的仍然可以經由
                     // setPirAlertEnabled(false) 熄掉)。
        }
        if (triggered == alpha2PirAlertActive) {
            return; // 避免每次重複收到同一個狀態的事件都重新送一次 LED/聲音, 和
                     // onSonarDistanceReceived() 一致的做法。
        }
        alpha2PirAlertActive = triggered;
        // PIR 警示（獨立網頁「PIR 測試」開關 alpha2PirAlertEnabled 控制）直接單發
        // setHeadEyeLedLong()/stop——抢灯的补发线程与 alpha2services 熄灯循环都已
        // 移除，后到者胜，无需取消任何东西。
        try {
            if (triggered) {
                setHeadEyeLedLong(1, 9); // 1 = 紅 (red), 9 = 最光
            } else {
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        } catch (Throwable t) {
            // 2026-08-15 更新: 實機已確認這台機器頭板的 5-mic head/eye LED 對 PIR
            // 警示反應有效 (眼/頭會亮紅燈), 不像之前 applyObstacleIndicator()/
            // registerChestMuteKeyTestListener() 遇到的情況 (header_ledSetHead5Mic/
            // header_ledSetEye5Mic 全部 preset 都回 API_ERROR_FAILED) - 兩者用的是不同
            // AIDL 方法/參數組合, 不能直接假設「一個不行全部都不行」。所以 PIR 警示只
            // 走這一條路, 沒有再加 mouth LED breathing 做 fallback, 嘴部不用閃, 和鈴聲一起
            // 淨係眼/頭長著紅燈。
            Log.w(TAG, "applyAlpha2PirLedAndSound: 5-mic head/eye LED path failed", t);
        }
        if (triggered) {
            ringtoneCenter.playPirAlertCue(); // lazy-lookup 好的 "Heaven" 鈴聲
        } else {
            ringtoneCenter.stopRingtonePlayback();
        }
    }

    // -- PIR 事件接線 (2026-09 小件拼盤由 MainActivity.registerAlpha2PirAlertListener
    // 搬入；警示反應本體 applyAlpha2PirLedAndSound 早已喺呢度) --
    public void registerAlpha2PirAlertListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains("\"type\":\"alpha2_pir_state\"")) {
                    return;
                }
                final Boolean triggered = extractPirTriggered(line);
                if (triggered == null) {
                    return;
                }
                // onEvent() 在 main thread 執行 - AIDL/JNI LED call 搬到 background
                // thread, 不要用主執行緒, 和專案一貫做法一致 (見
                // registerPirAlertListener()/registerChestMuteKeyTestListener())。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        applyAlpha2PirLedAndSound(triggered);
                    }
                }).start();
            }
        });
    }

    /** Pulls the boolean after "triggered":  out of an EventBus-published "pir_state"
     *  JSON line, matching extractAbsoluteAngle()'s no-JSON-library style. */
    private static Boolean extractPirTriggered(String line) {
        String key = "\"triggered\":";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        if (line.startsWith("true", start)) return true;
        if (line.startsWith("false", start)) return false;
        return null;
    }

    // directChestReady() 內聯：經 appContext
    // 唔使 Activity (同上面 headerReady() 一樣形狀；原 MainActivity 私有版 2026-09 刪)。
    private boolean directChestReady() {
        try {
            return HardwareDirectManager.get(appContext).chest().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    // 2026-08-15 更新: 真機已確認 cmd=72 開關生效, PIR 觸發正常 (見
    // RobotEventReceiver/registerAlpha2PirAlertListener 的 comment)。
    public HttpServer.ApiResponse pirSetResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        boolean sent = HardwareDirectManager.get(appContext).chest().setPirEnabled(enabled);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), directChestReady());
    }

    /** 2026-08-15 新增: 獨立於 pir/set 呢個感應器硬件開關本身, 純粹控制
     *  「偵測到人就閃紅燈/響鈴」這個警示反應要不要開。已在實機確認 PIR 事件
     *  本身 (cmd=-109, "PIR HUMON DETECT") 會正常觸發 (見 RobotEventReceiver
     *  的 CHEST_ACTION case 裡面 alpha2_pir_state 那段 comment) - 這個
     *  endpoint 就是讓前端選擇要不要對這個事件有反應。 */
    public HttpServer.ApiResponse pirAlertEnabledResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        setPirAlertEnabled(enabled);
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    /** Same "long" (solid, always-on) LED effect as led/head/set & led/eye/set's
     *  preset=long, but callable directly server-side without an HTTP round-trip.
     *  抢灯时代（alpha2services 内部熄灯循环持续覆写）的补发线程已随 APK 移除而删除，
     *  pure-direct 下单发即稳住。 */
    public void setHeadEyeLedLong(int color, int brightness) {
        DirectLedController.setHead5MicRaw(color, brightness, 31, 31, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        DirectLedController.setEye5MicRaw(color, brightness, 255, 255, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }

    /** 2026-08 新增: 這台機器 (head board / firmware 1.1.1.14) 的
     *  header_ledSetHead5Mic/header_ledSetEye5Mic 實測全部 preset 都回傳
     *  API_ERROR_FAILED (bindReady:true, 也就是不是尚未 ready, 是機身真的不支援/
     *  沒實作 - 看起來這個機頭不是 5-mic variant, 或這個 firmware 沒實作這兩個
     *  AIDL 方法)。Mouth LED (MouthLedData, 直接 JNI 不經 AIDL) 則實測正常。
     *
     *  這個方法把 obstacle-triggered 的 LED 指示同時發到兩條路: 5-mic
     *  head/eye (setHeadEyeLedLong) 照舊保留 - 在支援的機/firmware 上會亮紫燈,
     *  在這台機器上頂多是 API_ERROR_FAILED、沒有視覺效果、但不會拋出例外中斷流程;
     *  同時也閃爍 mouth LED 做 fallback, 保證這台機器都看得到反應。兩條路獨立 try/catch,
     *  其中一條失敗不會擋住另一條。 */
    public void applyObstacleIndicator(boolean triggered) {
        try {
            if (triggered) {
                setHeadEyeLedLong(5, 9); // 5 = 紫 (purple), see led/head/set color-code comment
            } else {
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyObstacleIndicator: 5-mic head/eye LED path failed (known unsupported on this head board, see MouthLedData javadoc)", t);
        }
        try {
            if (triggered) {
                MouthLedData.breathing(150).apply(); // fast breathing = obstacle-near cue
            } else {
                MouthLedData.off().apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyObstacleIndicator: mouth LED fallback failed", t);
        }
    }

    private boolean headerReady() {
        try {
            return HardwareDirectManager.get(appContext).head().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - matches the web UI slider's
    // default (0-5000 range, default 0).
    private static final int TTS_MOUTH_LED_SPEED = 0;

    /**
     * Starts the mouth LED breathing effect for the duration of a TTS utterance. Called
     * right after kicking off speech (both robot-side speech_startTTS and Android
     * system TTS), paired with stopMouthLedForTts() called when that speech actually
     * finishes (onServerPlayEnd for robot TTS; UtteranceProgressListener.onDone/onError
     * for Android TTS - see androidTts setup in MainActivity.onCreate).
     *
     * Note this can't be timed to the utterance's real length in advance: neither
     * speech_startTTS nor Android TextToSpeech.speak() reports how long the resulting
     * audio will be before/while it's produced (the robot's TTS engine synthesizes and
     * plays it internally; length depends on synthesis the caller doesn't control), so
     * "flash the mouth for exactly N seconds" is implemented as bracket-and-release
     * around the actual speech rather than a precomputed fixed duration -
     * MouthLedData.breathing() is left running (playDurationMs=MAX) until the
     * corresponding stop call arrives from whichever completion signal fires.
     */
    public static void startMouthLedForTts() {
        MouthLedData.breathing(TTS_MOUTH_LED_SPEED).apply();
    }

    public static void stopMouthLedForTts() {
        MouthLedData.off().apply();
    }

    // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
    // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
    //   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
    //   brightness: 1 (dimmest) .. 9 (brightest)
    //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
    //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
    public HttpServer.ApiResponse ledHeadSet(Map<String, String> query) {
        // pure-direct: 5-mic 经 libhead_led.so JNI 直驱（DirectLedController），不再经 binder。
        String preset = ApiValidator.requireLedHeadPreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopHead5Mic();
            return MainActivity.codeResponseReady(MainActivity.directCode(stopped), headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p5, p6, p8;
        switch (preset) {
            case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
            case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
            case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
            case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
            case "long":
            default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), headerReady());
    }

    public HttpServer.ApiResponse ledEyeSet(Map<String, String> query) {
        String preset = ApiValidator.requireLedEyePreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopEye5Mic();
            return MainActivity.codeResponseReady(MainActivity.directCode(stopped), headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p5, p6, p8;
        switch (preset) {
            case "flash": p5 = 100; p6 = 100; p8 = 0; break;
            case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
            case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
            case "long":
            default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), headerReady());
    }

    // NOTE: unlike led/head/set and led/eye/set above, this does NOT go through
    // Alpha2RobotApi/AIDL at all - there is no AIDL "mouth LED" method. It calls
    // com.ubtechinc.alpha.jni.LedControl directly (a native JNI class backed by
    // libhead_led.so 3.002), a completely separate control path found in a different
    // demo app, not gated by isHeaderReady()/waitHeaderReady() since it has
    // nothing to do with the header serial AIDL bind. See MouthLedData's
    // javadoc for the confirmed field semantics and the same-device-contention
    // caveat before relying on this alongside led/head/set or led/eye/set.
    //
    // Simplified to the two effects confirmed usable on this hardware: a
    // breathing effect (speed adjustable, 0-5000ms) and off. effectMode values
    // other than 1 produced no light in testing, so there's no third "always
    // solid, no breathing" preset here - see README for what was tried. Also
    // triggered automatically around TTS start/end - see startMouthLedForTts()/
    // stopMouthLedForTts() above and their call sites in speech/tts,
    // onServerPlayEnd, and the Android TTS UtteranceProgressListener.
    public HttpServer.ApiResponse ledMouthSet(Map<String, String> query) {
        String mouthPreset = ApiValidator.requireMouthPreset(query);
        if ("off".equals(mouthPreset)) {
            boolean ok = MouthLedData.off().apply();
            return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
        }
        int speed = ApiValidator.requireMouthSpeed(query);
        boolean ok = MouthLedData.breathing(speed).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
    }

    public HttpServer.ApiResponse debugJniLed(Map<String, String> query) {
        // 2026-08-25 新增: 直接試 /dev/led_eye 這個 JNI driver 的各個 native
        // function - 這塊 5-mic 板上眼/頭/嘴部 LED 全部走這條路, 兩顆 pad 燈
        // 很可能也是同一個 driver 另一個 ioctl (例如尚未用過的 ledSetOn(i))。
        // func=on&i=N -> ledSetOn(N); func=eye/head&a1..a8 -> 對應 setter。
        String func = ApiValidator.requireDebugLedFunc(query);
        if ("off".equals(func)) {
            boolean openOk = LedControl.open();
            boolean r = LedControl.ledSetOFF(0);
            LedControl.close();
            Log.i(TAG, "ledSetOFF open=" + openOk + " raw=" + r);
            return HttpServer.ApiResponse.ok(
                    "{\"open\":" + openOk + ",\"raw\":" + r + "}");
        }
        boolean openOk = LedControl.open();
        try {
            if ("on".equals(func)) {
                int i = ApiValidator.optionalInt(query, "i", 0);
                boolean r = LedControl.ledSetOn(i);
                Log.i(TAG, "ledSetOn(" + i + ") open=" + openOk + " raw=" + r);
                return HttpServer.ApiResponse.ok(
                        "{\"open\":" + openOk + ",\"raw\":" + r + "}");
            }
            int[] a = new int[8];
            for (int k = 0; k < 8; k++) {
                a[k] = ApiValidator.optionalInt(query, "a" + (k + 1), 0);
            }
            boolean r;
            if ("eye".equals(func)) {
                r = LedControl.ledSetEye(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            } else {
                r = LedControl.ledSetHead(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            }
            Log.i(TAG, "ledSet" + func + " open=" + openOk
                    + " raw=" + r + " args=" + java.util.Arrays.toString(a));
            return HttpServer.ApiResponse.ok("{\"open\":" + openOk
                    + ",\"raw\":" + r + ",\"args\":"
                    + java.util.Arrays.toString(a).replace(" ", "") + "}");
        } finally {
            LedControl.close();
        }
    }

    public HttpServer.ApiResponse debugSerialSend(Map<String, String> query) {
        // 2026-08-25 新增: raw serial 發送測試端點, 用來反推音量鍵 LED 和
        // 胸口 mute 鍵 LED 的控制指令 (headboard v1.1 上 alpha2services v1.0
        // 協議不合, 只要它一動作 MCU 就不再自動點燈, 要自己 app 補上)。port=head
        // 走 header_sendRawData (ttyS3), port=chest 走 chest_sendRawData
        // (ttyS1); hex 是完整 wire frame (f8 ... ed), 我們在 PC 側組好再送出。
        String port = ApiValidator.optionalSerialPort(query);
        byte[] data = parseHexBytes(ApiValidator.require(query, "hex"));
        // pure-direct: 经 DirectSerialPort.sendRaw 透传完整 wire 帧。
        boolean sent = "chest".equals(port)
                ? HardwareDirectManager.get(appContext).chest().sendRaw(data)
                : HardwareDirectManager.get(appContext).head().sendRaw(data);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        Log.i(TAG, "debug/serial/send port=" + port + " hex=" + MainActivity.toHex(data, data.length)
                + " -> " + code.name());
        return HttpServer.ApiResponse.ok("{\"ok\":"
                + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + ",\"code\":\""
                + code.name() + "\"}");
    }

    /** Parses "f8 8f 08 ..." style hex (spaces/colons optional, case-insensitive) back
     *  into raw bytes for the debug/serial/send endpoint. Returns empty array on junk. */
    private static byte[] parseHexBytes(String hex) {
        String cleaned = hex.replaceAll("[^0-9a-fA-F]", "");
        int n = cleaned.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
