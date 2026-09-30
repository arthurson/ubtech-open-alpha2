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
        registerVolumeChangeReceiver();
    }

    /** onDestroy 共用：停 pad LED worker＋meter worker＋反註冊音量廣播。 */
    public void shutdown() {
        padLedExecutor.shutdownNow();
        volumeMeterExecutor.shutdownNow();
        try {
            if (volumeChangeReceiver != null) {
                appContext.unregisterReceiver(volumeChangeReceiver);
            }
        } catch (Throwable ignore) {
        }
        volumeChangeReceiver = null;
    }

    // 任何路改系統音量（實體 V+/V-、HTML 音量 slider、Android 設定）都會出呢個
    // 廣播——呢度聽住，條綠燈 bar 乜路改都跟到，唔使逐個 call 位勾。
    private BroadcastReceiver volumeChangeReceiver = null;

    private void registerVolumeChangeReceiver() {
        try {
            volumeChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null) return;
                        // 常數逐字寫：呢個 SDK 冇 AudioManager.EXTRA_VOLUME_STREAM_TYPE
                        int stream = intent.getIntExtra(
                                "android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                        if (stream != android.media.AudioManager.STREAM_MUSIC) return;
                        showVolumeMeter();
                        // 反向同步：乜路改音量（HTML slider／頭頂 +/- 鍵／語音大細聲）
                        // 都會出呢個 broadcast，順手 publish 個 volume_changed 上 WS，
                        // 等 HTML 兩邊 slider 即時跟返（正向 HTML→機械人係經
                        // /api/audio/volume/set 回包即時同步；見 app-log.js
                        // appendLog＋app-servo.js onVolumeChangedEvent）。
                        // 純讀 AudioManager＋組 string，無阻塞，onReceive 時限內搞掂。
                        try {
                            android.media.AudioManager am = (android.media.AudioManager)
                                    appContext.getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) {
                                int vol = am.getStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC);
                                int max = am.getStreamMaxVolume(
                                        android.media.AudioManager.STREAM_MUSIC);
                                EventBus.get().publish("volume_changed",
                                        "{\"volume\":" + vol + ",\"max\":" + max + "}");
                            }
                        } catch (Throwable ignore) {
                        }
                    } catch (Throwable ignore) {
                    }
                }
            };
            appContext.registerReceiver(volumeChangeReceiver,
                    new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        } catch (Throwable t) {
            Log.w(TAG, "volume receiver register failed", t);
            volumeChangeReceiver = null;
        }
    }

    // -- Pad (+/-) 實體鍵指示燈 -----------------------------------------------
    // 真機掃描確認: ledSetOn(14) = volume- 燈, ledSetOn(16) = volume+ 燈。
    // firmware 不會自亮，按住期間由 app 連發補燈，放手補 OFF；
    // wifi 燈 (12/13) firmware 不會自己著，繼續手動（三態：熄/紅/藍）。
    private static final int PAD_LED_INDEX_MINUS = 14;
    private static final int PAD_LED_INDEX_PLUS = 16;
    private static final long PAD_LED_INTERVAL_MS = 80;
    // alpha2services 已移除，無人再搶 /dev/led_eye，重試只防偶發打不開。
    private static final int PAD_LED_OPEN_ATTEMPTS = 3;
    private final java.util.concurrent.ExecutorService padLedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // 音量計專用單線程：唔可以同上面 padLedExecutor 共用——pad worker 喺㩒住
    // 期間 loop 唔放（80ms 一轉），共用嗰陣 meter update 會排隊等到放手先郁
    // （實機驗明：長撳連減嗰陣頭綠燈唔跟，放手先一次過變）。分開之後兩邊經
    // DirectLedController 全局鎖排先後，唔會打架。
    private final java.util.concurrent.ExecutorService volumeMeterExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** meter 更新專用 post（同 postPadLed 一樣吞 RejectedExecutionException）。 */
    public void postVolumeMeter(Runnable r) {
        try {
            volumeMeterExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** onDestroy() 會 shutdownNow() 上面條 executor，但 wifi receiver
     *  之前 postDelayed 了的 runnable (1200ms) 還會在之後照開，當時再排就撞上
     *  RejectedExecutionException 崩潰在 main thread——app
     *  收緊皮當時掉了個 LED 更新是正確行為，吞了它。
     *  公開是因為 mute 鍵小智開關／mute LED 發送都借這條單線程做背景執行。 */
    public void postPadLed(Runnable r) {
        try {
            padLedExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private volatile boolean padMinusHeld = false;
    private volatile boolean padPlusHeld = false;
    private volatile boolean padLedWorkerRunning = false;
    // pad 實體鍵狀態世代：每次 setPad*Held 即 +1。GestureCenter 雙鍵總停後
    // 1.5s 嘅 failsafe 熄燈，淨係喺世代冇郁過（期間冇新撳掣）先清——唔係會
    // 熄咗人哋跟住㩒住嗰粒燈（見 stopAllViaPads）。
    private final java.util.concurrent.atomic.AtomicInteger padHeldEpoch =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 手勢層設定實體鍵狀態 (0x5a-0x5f)，跟著即刻調 padLedUpdate()。 */
    public void setPadMinusHeld(boolean held) {
        padMinusHeld = held;
        padHeldEpoch.incrementAndGet();
    }

    public void setPadPlusHeld(boolean held) {
        padPlusHeld = held;
        padHeldEpoch.incrementAndGet();
    }

    /** 讀世代（failsafe 熄燈用；見上）。 */
    public int getPadHeldEpoch() {
        return padHeldEpoch.get();
    }

    /**
     * V+/V- 綠色音量計（用戶要求）：讀 STREAM_MUSIC 即時音量，0-4 粒綠燈
     * 顯示喺頭（能用只有 1-4，見上面位圖）。level 0 即全滅，滿格即 4 粒。
     * 每次撳掣即更，留低唔還原（disco／LED 頁／下次撳掣會自然覆寫）。
     * 行 pad 單線程，同 pad 燈 burst 排隊，唔會同 JNI 打架。
     */
    private static final int VOLUME_LED_GREEN = 2;
    private static final int VOLUME_LED_BRIGHTNESS = 9;

    public void showVolumeMeter() {
        postVolumeMeter(() -> {
            try {
                android.media.AudioManager am = (android.media.AudioManager)
                        appContext.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                int level;
                int max;
                try {
                    level = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                } catch (Throwable t) {
                    return;
                }
                int count;
                if (level <= 0 || max <= 0) {
                    count = 0;
                } else {
                    count = Math.min(4, Math.max(1, (int) Math.round(level * 4.0 / max)));
                }
                int p4;
                int p3;
                switch (count) {
                    case 1: p4 = 1; p3 = 16; break;
                    case 2: p4 = 3; p3 = 24; break;
                    case 3: p4 = 7; p3 = 28; break;
                    case 4: p4 = 15; p3 = 30; break;
                    default: p4 = 0; p3 = 0; break;
                }
                DirectLedController.setHead5MicRaw(VOLUME_LED_GREEN, VOLUME_LED_BRIGHTNESS,
                        p3, p4, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * 按住 +/- firmware 不會自亮 14/16，所以按住期間繼續由
     * app 主動點亮；放手後補 ledSetOFF 清場。單線程 worker，正在跑不重入。
     */
    public synchronized void padLedUpdate() {
        // check-then-set 要原子——兩個線程同撳嗰陣唔好開多條 worker 打架。
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
                            // 轉組合（例如兩粒㩒住放開一粒）先全局熄一次——
                            // 硬件無逐粒熄，放開嗰粒唔清會著到放晒手。
                            if (lastCombo != -1 && !assertPadLedsOffBurst()) {
                                Log.w(TAG, "pad combo-change off-burst failed");
                            }
                            assertPadLedsComboBurst();
                            lastCombo = combo;
                        }
                        Thread.sleep(PAD_LED_INTERVAL_MS);
                    }
                    if (!assertPadLedsOffBurst()) {
                        Log.w(TAG, "pad release off-burst failed, pads may stay lit");
                    }
                    if (!padMinusHeld && !padPlusHeld) break;
                    // 熄燈途中又按過：回到 loop，不交棒
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

    /** pad LED open 重試間隔（combo／off／wifi 三個 burst loop 逐字一樣）。 */
    private static boolean sleepPadRetryGap() {
        try {
            Thread.sleep(PAD_LED_RETRY_GAP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * 按住期間點亮組合 burst：open 到就按 padMinusHeld/padPlusHeld 點 14/16
     *（累加式，兩顆齊按兩顆都著），打不開就重試。
     */
    private boolean assertPadLedsComboBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            final boolean[] openOk = {false};
            // open→ioctl→close 包入全局驅動鎖：/dev/led_eye 頂唔順併發 open，
            // 同 disco 高頻打燈撞即有機會硬 hang。重試 sleep 留喺鎖出面。
            DirectLedController.runExclusive(() -> {
                try {
                    openOk[0] = LedControl.open();
                } catch (Throwable t) {
                    Log.w(TAG, "pad LED open() threw", t);
                }
                if (openOk[0]) {
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
                }
            });
            if (openOk[0]) {
                if (attempt > 1) {
                    Log.d(TAG, "pad LED device opened on attempt " + attempt);
                }
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "pad LED open() failed " + PAD_LED_OPEN_ATTEMPTS
                + "x in a row (device busy?)");
        return false;
    }

    /**
     * 放手後熄燈 burst：全域 OFF（ledSetOFF 係「一鑊清晒」，頭/眼/pad/wifi/咀
     * 全部死）之後即刻補返應該留低嘅燈。
     *
     * 實機 logcat 捉到嘅原 bug：呢度以前淨係打 OFF 再補個咀，head/eye/pad/wifi
     * 就永久黑——實機係撳一次 V+/V- 放開就見到成部機燈爆一鑊再淨返個咀，
     * disco 跳舞途中被踩到就成段唔見。wifi 尤其慘：applyWifiLedInternal 每隔
     * 幾百 ms 又補返 wifi 一次，於是 OFF→補 wifi 无限 ping-pong。
     *
     * 而家用 runBatch（OFF + 補燈同一個 open，中間零黑場），跟 ledPadSet 嗰套
     * 一致。keepPads=false：實體鍵放手就係嗰粒(s)要滅，唔應該照 mirror 補返。
     */
    private boolean assertPadLedsOffBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(6);
            ops.add(DirectLedController.LedOp.off());
            // keepPads 睇播歌指示燈：佢著嘅時候，實體鍵放手唔應該連佢一齊熄。
            appendRestoreOps(ops, false, null);
            if (DirectLedController.runBatch(ops)) {
                // 全局熄連 pad 試燈態一齊清（硬件現實：兩粒一齊死，mirror 唔呃人）。
                // 旗操作同 ledPadSet 揸同一把鎖，唔好兩邊同時改。
                synchronized (padToggleLock) {
                    padMinusLit = false;
                    padPlusLit = false;
                }
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "pad LED off: batch failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    /**
     * 全域 OFF 之後要補返嘅燈（頭 → 眼 → pad → wifi），一個接一個加落去。
     *
     * @param keepPads  false = 實體音量鍵放手，pad 兩粒都唔補（要佢哋熄）。
     * @param wifiOverride null = 照 wifiLedState 補；"red"/"blue" = 補呢隻新色；
     *                     "off" = 唔補 wifi（ledWifiSet(color=off) 用）。
     * 頭/眼只喺真係著過先補（DirectLedController.lastHeadParams 未打過會回 null）。
     *
     * pad/wifi 嘅 mirror 一定要揸住 padToggleLock 讀：ledPadSet 同一個全域 OFF
     * 補燈都係喺呢把鎖入面做。唔鎖就會讀到半舊半新——實機見過連續轉 wifi 色
     * （每轉一次都全域 OFF 一次）撞埋 pad 試燈，補燈時 pad 旗未更新，
     * 嗰粒 v-/v+ 就被 OFF 咗又唔補返，變成間中熄。
     * 鎖係可重入，ledPadSetLocked 本身已揸住，巢狀安全。
     */
    private void appendRestoreOps(java.util.List<DirectLedController.LedOp> ops,
                                  boolean keepPads, String wifiOverride) {
        int[] h = DirectLedController.lastHeadParams();
        if (h != null) {
            ops.add(DirectLedController.LedOp.head(h[0], h[1], h[2], h[3],
                    Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0));
        }
        int[] e = DirectLedController.lastEyeParams();
        if (e != null) {
            ops.add(DirectLedController.LedOp.eye(e[0], e[1], e[2], e[3],
                    Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0));
        }
        synchronized (padToggleLock) {
            if (keepPads) {
                if (padMinusLit) ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_MINUS));
                if (padPlusLit) ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_PLUS));
            }
            String w = wifiOverride != null ? wifiOverride : wifiLedState;
            if ("blue".equals(w)) {
                ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_BLUE));
            } else if ("red".equals(w)) {
                ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_RED));
            }
        }
    }

    /**
     * 節奏燈（disco）嘅系統燈：**跟頭/眼/咀同一個 batch、同一個 disco job
     * 一齊著一齊熄**，唔好另外開一套狀態機（用戶要求）。
     *
     * 呢度**寫入** padMinusLit/padPlusLit（即係當成「撳咗個掣」），所以之後
     * 用家手動改 pad 就真係改到、唔會彈返——同 wifi/mute 一致。呢個係修正
     * 之前 pad 嘅 API 講大話：`led/pad/set?on=false` 回 ok 但粒燈即刻彈返
     * （實測 minus=false 送完即刻 true）。disco 係「開燈嗰刻設一次」，
     * 唔係成首歌鎖住用家隻手。
     *
     * @param on true = 著緊：pad v-(14)/v+(16) + wifi 紅(13)。
     *            false = 熄：pad 兩粒都熄，wifi 校返真實連線態。
     */
    public java.util.List<DirectLedController.LedOp> discoSystemLightsOps(boolean on) {
        java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(5);
        if (on) {
            // 只加唔減：pad 由熄變著同 wifi 轉紅都係累加式，唔使全域 OFF。
            padMinusLit = true;
            padPlusLit = true;
            ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_MINUS));
            ops.add(DirectLedController.LedOp.on(PAD_LED_INDEX_PLUS));
            ops.add(DirectLedController.LedOp.on(WIFI_LED_INDEX_RED));
            wifiLedState = "red";
        } else {
            // 熄：pad 唔補（跟 disco 一齊熄）；wifi 校返真實連線態（通咗就藍）。
            padMinusLit = false;
            padPlusLit = false;
            appendRestoreOps(ops, false, realWifiColor());
            String real = realWifiColor();
            wifiLedState = real;
        }
        return ops;
    }

    /** 現查 wifi 開關制＋連線態，出 "blue"/"red"/"off"。 */
    private String realWifiColor() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return "off";
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "red";
            android.net.NetworkInfo ni =
                    cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
            return (ni != null && ni.isConnected()) ? "blue" : "red";
        } catch (Throwable t) {
            return "blue";
        }
    }

    // -- WiFi 燈 (12 藍=有網 / 13 紅=無網) --------------------------------------
    private static final int WIFI_LED_INDEX_BLUE = 12;
    private static final int WIFI_LED_INDEX_RED = 13;
    private BroadcastReceiver wifiLedReceiver;
    private Runnable wifiLedReapply;

    public void registerWifiLedReceiver() {
        // 重入保護：唔好 register 兩次漏掉第一個 receiver。
        if (wifiLedReceiver != null) return;
        wifiLedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                // 收斂：關閉 wifi 時 DISABLED 同 disconnected 兩個 broadcast 先後不定，
                // 先到的若判了紅，後到的熄燈蓋過；反之亦然。1.2s 後按權威狀態重設一次，
                // 保證最終一致（單線程 executor 保序，debounce 防堆積）。
                if (wifiLedReapply != null) mainHandler.removeCallbacks(wifiLedReapply);
                wifiLedReapply = new Runnable() {
                    @Override public void run() { applyWifiLed(); }
                };
                mainHandler.postDelayed(wifiLedReapply, 1200);
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    // 開關本身：關了即熄燈；其他狀態轉 query 最新為準。
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
                // 不重查（重查有 race：broadcast 到了但 ConnectivityManager
                // 還是舊值，會凍結在紅燈，實機見過）。但開關制要現查——關閉 wifi
                // 時 DISABLED 同 disconnected 兩個 broadcast 先後到，後者若
                // 當 wifi 還開著就會重新點亮紅燈蓋過熄燈。
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
        if (wifiLedReapply != null) {
            mainHandler.removeCallbacks(wifiLedReapply);
            wifiLedReapply = null;
        }
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

    /** 現查 wifi 開關制（裹 try/catch，查不到當開著，由連線態決定）。 */
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
        postPadLed(() -> applyWifiLedSync());
    }

    /** 上面嘅同步本體——ledPadSet 獨立熄嗰陣要即刻同一步做埋，唔經 executor
     *  排隊（否則同補燈搶鎖次序亂）。 */
    private void applyWifiLedSync() {
        final boolean wifiOn;
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
        applyWifiLedInternal(wifiOn, conn);
    }

    /**
     * 實際切換: 先 ledSetOFF() 清除舊色, 等 100ms, 再點目標顏色
     * （wifi 關閉就不點亮）。兩步都是 burst 重試式。
     */
    /** Wi-Fi 燈試燈＋mirror 用嘅最後態（red/blue/off）。applyWifiLedInternal
     *  同 ledWifiSet 都會更新；LED tab 頭燈第 5 粒跟呢個畫（硬件就係咁）。 */
    private volatile String wifiLedState = "off";

    private void applyWifiLedInternal(boolean wifiOn, boolean connected) {
        String want = !wifiOn ? "off" : (connected ? "blue" : "red");
        // 冇變色就一個 ioctl 都唔使打。實機 logcat 見過舊 path 隔幾百 ms 就
        // 「全域 OFF → sleep 100ms → 淨返個 wifi」咁 ping-pong，仲要成部機
        // 黑 100ms，disco 頭/眼跳舞被踩到就成段唔見。加呢個閘之後靜晒。
        synchronized (padToggleLock) {
            if (want.equals(wifiLedState)) return;
            if (!wifiOn) {
                // 熄＝全域 OFF（會連頭/眼一齊清）。跟返原本語義。
                if (assertPadLedsOffBurst()) wifiLedState = "off";
                return;
            }
            // 紅↔藍淨係打 ledSetOn(12/13)，唔使先 OFF（見 ledWifiSet 註解）。
            if (assertSingleLedBurst(connected ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED)) {
                wifiLedState = want;
            }
        }
    }

    /** One burst: retry open()/dev/led_eye until it opens, light a single LED index.
     *  open→ioctl→close 入全局驅動鎖，唔好同 disco 撞 open（見上）。 */
    private boolean assertSingleLedBurst(int index) {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            final boolean[] openOk = {false};
            DirectLedController.runExclusive(() -> {
                try {
                    openOk[0] = LedControl.open();
                } catch (Throwable t) {
                    Log.w(TAG, "wifi LED open() threw", t);
                }
                if (openOk[0]) {
                    try {
                        LedControl.ledSetOn(index);
                    } finally {
                        try {
                            LedControl.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
            if (openOk[0]) {
                return true;
            }
            if (!sleepPadRetryGap()) return false;
        }
        Log.w(TAG, "wifi LED open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
    }

    // -- Alpha2 PIR 警示反應 (LED+鈴聲) ----------------------------------------------
    // 監聽獨立的 "alpha2_pir_state" event, 用 Alpha2 backend
    // 的 LED API 觸發 LED/鈴聲。
    //
    // PIR raw 事件 (chest cmd=-109, "PIR HUMON DETECT") 會正常觸發 -
    // 這台機器底層 chest MCU 硬體本身能做 PIR。
    //
    // LED 部分: 眼/頭 5-mic LED 長亮紅燈 (setHeadEyeLedLong(1, 9)), 顏色代碼 1=紅,
    // 見下面 LEDs 一節 color 表 (1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白)。
    //
    // 這台機器頭板的 5-mic head/eye LED
    // 對 PIR 警示反應是有效的 (眼/頭會亮紅燈)。PIR 警示只
    // 走這一條路, 沒有再加 mouth LED breathing 做 fallback, 嘴部不用閃, 和鈴聲一起
    // 僅眼/頭長著紅燈。
    private volatile boolean alpha2PirAlertActive = false;
    // 獨立於 pir/set 感應器硬件開關本身 - 預設關, 使用者要自己選開才會有 LED/聲反應,
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
        // setHeadEyeLedLong()/stop——後到者胜，無需取消任何东西。
        try {
            if (triggered) {
                setHeadEyeLedLong(1, 9); // 1 = 紅 (red), 9 = 最光
            } else {
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        } catch (Throwable t) {
            // 5-mic head/eye LED 路徑失敗時記 warning（PIR 警示只走這條路，不加 mouth fallback）。
            Log.w(TAG, "applyAlpha2PirLedAndSound: 5-mic head/eye LED path failed", t);
        }
        if (triggered) {
            ringtoneCenter.playPirAlertCue(); // lazy-lookup 好的 "Heaven" 鈴聲
        } else {
            ringtoneCenter.stopRingtonePlayback();
        }
    }

    // -- PIR 事件接線 --
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
     *  JSON line (no-JSON-library style, matching the rest of this project). */
    private static Boolean extractPirTriggered(String line) {
        String key = "\"triggered\":";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        if (line.startsWith("true", start)) return true;
        if (line.startsWith("false", start)) return false;
        return null;
    }

    // directChestReady() 薄 delegate（實現見 DirectProbes，同下面 headerReady() 一對）。
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    // 真機已確認 cmd=72 開關生效, PIR 觸發正常。
    public HttpServer.ApiResponse pirSetResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        boolean sent = HardwareDirectManager.get(appContext).chest().setPirEnabled(enabled);
        return MainActivity.sentReadyResponse(sent, directChestReady());
    }

    /** 獨立於 pir/set 這個感應器硬件開關本身, 純粹控制
     *  「偵測到人就閃紅燈/響鈴」這個警示反應要不要開。已確認 PIR 事件
     *  本身 (cmd=-109, "PIR HUMON DETECT") 會正常觸發 - 這個
     *  endpoint 就是讓前端選擇要不要對這個事件有反應。 */
    public HttpServer.ApiResponse pirAlertEnabledResponse(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "on");
        setPirAlertEnabled(enabled);
        return HttpServer.ApiResponse.okTrue();
    }

    /** Same "long" (solid, always-on) LED effect as led/head/set & led/eye/set's
     *  preset=long, but callable directly server-side without an HTTP round-trip.
     *  pure-direct 下单发即稳住。 */
    public void setHeadEyeLedLong(int color, int brightness) {
        DirectLedController.setHead5MicRaw(color, brightness, 31, 31, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        DirectLedController.setEye5MicRaw(color, brightness, 255, 255, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }

    /** 這台機器 (head board / firmware 1.1.1.14) 的 obstacle LED 指示：
     *  頭/眼經 DirectLedController（pure-direct JNI，同 LED tab／disco 同一路，
     *  實機識著）長著紫燈，同時閃嘴燈做多重提示。兩條路獨立 try/catch，
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
        return DirectProbes.isHeadReady(appContext);
    }

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - 0 = 最快呼吸（實機確認有光），
    // 同 LED tab 條 slider 無關（嗰條 100-1000、預設 150，只管手動 led/mouth/set）。
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
        mouthTtsActive = true;
        MouthLedData.breathing(TTS_MOUTH_LED_SPEED).apply();
    }

    public static void stopMouthLedForTts() {
        mouthTtsActive = false;
        MouthLedData.off().apply();
    }

    /** TTS 播緊唔播緊（嘴係咪佢嘅）——disco 推嘴之前要讓路，唔好同佢打架。 */
    private static volatile boolean mouthTtsActive = false;

    public static boolean isMouthTtsActive() {
        return mouthTtsActive;
    }

    // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
    // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
    //   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
    //   brightness: 1 (dimmest) .. 9 (brightest)
    //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
    //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
    // 頭部左右各 5 粒 LED 位置圖（實機試位確認）：
    //   P4＝左邊（正序）：滅=0，開1=1，開2=2，開3=4，開4=8，開5=16；
    //     開1,2=3，開1,2,3=7，開1,2,3,4=15，全開=31。
    //   P3＝右邊（反序）：滅=0，開1=16，開2=8，開3=4，開4=2，開5=1；
    //     開1,2=24，開1,2,3=28，開1,2,3,4=30，全開=31。
    //   注意：左右第 5 粒畀 wifi 搶走咗（閂唔到），實際能用只有 1-4：
    //     左＝P4=15，右＝P3=30（disco 就係用呢組）。
    //   V+/V- 綠色音量計（用戶要求）：最大聲 4 粒綠燈、無聲全滅，
    //   見下面 showVolumeMeter()，GestureCenter 每次調音量都更一次。
    public HttpServer.ApiResponse ledHeadSet(Map<String, String> query) {
        // pure-direct: 5-mic 经 libhead_led.so JNI 直驱（DirectLedController），不再经 binder。
        String preset = ApiValidator.requireLedHeadPreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopHead5Mic();
            return MainActivity.sentReadyResponse(stopped, headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        // 速度（ms，50-500）：flash p5/p6、chase/dual p5、breathe 按比例；
        // 唔帶＝沿用各 preset 經典值（Blockly／舊客無影響）。
        int speed = ApiValidator.optionalIntRange(query, "speed", 50, 500, -1);
        int p5, p6, p8;
        switch (preset) {
            case "flash":   p5 = speed < 0 ? 100 : speed; p6 = speed < 0 ? 100 : speed; p8 = 0; break;
            case "breathe": p5 = speed < 0 ? 5 : Math.max(1, speed / 20); p6 = speed < 0 ? 20 : speed / 5; p8 = 1; break;
            case "chase":   p5 = speed < 0 ? 100 : speed; p6 = 0; p8 = 3; break;
            case "dual":    p5 = speed < 0 ? 500 : speed; p6 = 0; p8 = 5; break;
            case "long":
            default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        // 逐粒試燈：preset 跟住而家著緊嘅 mask（唔帶＝全開，兼容舊客/Blockly）。
        int p3 = ApiValidator.optionalIntRange(query, "p3", 0, 31, 31);
        int p4 = ApiValidator.optionalIntRange(query, "p4", 0, 31, 31);
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, p3, p4, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    public HttpServer.ApiResponse ledEyeSet(Map<String, String> query) {
        String preset = ApiValidator.requireLedEyePreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopEye5Mic();
            return MainActivity.sentReadyResponse(stopped, headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        // 速度（ms，50-500）：flash p5/p6、chase/dual p5；唔帶＝經典值。
        int speed = ApiValidator.optionalIntRange(query, "speed", 50, 500, -1);
        int p5, p6, p8;
        switch (preset) {
            case "flash": p5 = speed < 0 ? 100 : speed; p6 = speed < 0 ? 100 : speed; p8 = 0; break;
            case "chase": p5 = speed < 0 ? 100 : speed; p6 = 0; p8 = 1; break;
            case "dual":  p5 = speed < 0 ? 500 : speed; p6 = 0; p8 = 3; break;
            case "long":
            default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        // 逐粒試燈：preset 跟住而家著緊嘅 mask（唔帶＝全開，兼容舊客/Blockly）。
        int p3 = ApiValidator.optionalIntRange(query, "p3", 0, 255, 255);
        int p4 = ApiValidator.optionalIntRange(query, "p4", 0, 255, 255);
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, p3, p4, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    // 眼部每邊 8 粒 LED 位置圖（實機試位確認）：P3＝左眼，P4＝右眼；
    // 順時針排：12點=1號，3點=3號，6點=5號，9點=7號。
    // bit 對位：開1=16，開2=8，開3=4，開4=2，開5=1，開6=128，開7=64，開8=32；
    //   開1,2=24，開1,2,3=28，開1-4=30，開1-5=31，
    //   開1-6=159，開1-7=223，全開=255。
    // 平時 255/255 全開。

    /** 逐粒試燈用：直接寫 p3/p4 mask（長開 MAX，同 long preset 同形）。
     *  頭：P4＝左 5 粒（正序 bit 1,2,4,8,16），P3＝右 5 粒（反序 16,8,4,2,1）；
     *  第 5 粒係 wifi 位（閂唔實），見上面位置圖。 */
    public HttpServer.ApiResponse ledHeadRaw(Map<String, String> query) {
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p3 = ApiValidator.requireIntRange(query, "p3", 0, 31);
        int p4 = ApiValidator.requireIntRange(query, "p4", 0, 31);
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, p3, p4,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    /** 逐粒試燈用：直接寫 p3/p4 mask（長開 MAX）。眼：P3＝左眼，P4＝右眼，
     *  每邊 8 粒 ring（bit 見上面位置圖，全開＝255）。 */
    public HttpServer.ApiResponse ledEyeRaw(Map<String, String> query) {
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p3 = ApiValidator.requireIntRange(query, "p3", 0, 255);
        int p4 = ApiValidator.requireIntRange(query, "p4", 0, 255);
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, p3, p4,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        return MainActivity.sentReadyResponse(sent, headerReady());
    }

    /** 逐粒試燈 mirror 用：回 DirectLedController 記低嘅最後燈態（硬件 write-only
     *  讀唔返）。未打過＝-1，熄咗＝0。 */
    public HttpServer.ApiResponse ledStateGet() {
        String s = DirectLedController.lastStateJson();
        // 搭埋 pad 試燈態＋wifi 燈態：
        // s 尾係 ...}}，strip 走最尾個 outer }，攝 "pad" 同 "wifi" 入去，
        // 最尾補返一個 }（唔係兩個！多一個 JSON 即爛，之前就係咁瀨嘢）。
        // 防呆：s 空/太短/唔係 } 收尾（上游改格式嗰陣）即回原字串，唔好掟
        // StringIndexOutOfBoundsException 變 500。
        if (s == null || s.length() < 2 || s.charAt(s.length() - 1) != '}') {
            return HttpServer.ApiResponse.ok(s == null ? "{\"ok\":false}" : s);
        }
        String extra = ",\"pad\":{\"minus\":" + padMinusLit
                + ",\"plus\":" + padPlusLit + "}"
                + ",\"wifi\":\"" + wifiLedState + "\"}";
        return HttpServer.ApiResponse.ok(s.substring(0, s.length() - 1) + extra);
    }

    // -- 系統燈試燈（wifi／pad／mute）------------------------------------------
    // wifi 燈 firmware 唔會自己著，由 applyWifiLedInternal 按連線態手動
    // （紅 13／藍 12）；呢度手動逼紅／藍／熄——下次 wifi 狀態變嗰陣自動
    // 校返正，試燈用途 fire-and-forget（同 preset 掣一樣）。
    public HttpServer.ApiResponse ledWifiSet(Map<String, String> query) {
        String color = ApiValidator.requireWifiLedColor(query);
        boolean ok;
        if ("off".equals(color)) {
            // 熄＝全局 OFF（會連頭/眼一齊清，同 applyWifiLedInternal 同一語義）。
            ok = assertPadLedsOffBurst();
            if (ok) wifiLedState = "off";
        } else {
            // 紅↔藍淨係打一個 ledSetOn(12/13) 就得，唔使先全域 OFF——實機
            // 確認 ledSetOn 自己會換走上一隻色。（2026-09 試過改用
            // 「OFF + 補頭/眼/pad + 點新色」個 batch，結果每轉一次色就成版
            // 燈爆一鑊，v-/v+ 隔住就見到熄，user 投訴。唔好行嗰條路。）
            ok = assertSingleLedBurst("blue".equals(color) ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED);
            if (ok) wifiLedState = color;
        }
        return HttpServer.ApiResponse.okBool(ok);
    }

    /** 音量 -/+ pad 燈（ledSetOn 14／16）試燈：撳一下開，再撳一下熄。
     *  硬件無逐粒熄——熄嗰陣全局 OFF 之後逐樣補返（另一粒 pad → wifi →
     *  頭 → 眼，long 形），肉眼睇淨係呢粒熄。disco 行緊嗰陣下一個 push
     *  （≤50ms）自然覆寫；LED tab 動畫 preset 會變長開（要閃返撳多次）。
     *  on 唔帶＝toggle。實體鍵按住嗰陣 worker 會搶（放手全局熄，
     *  mirror 照清，見 assertPadLedsOffBurst）。 */
    private volatile boolean padMinusLit = false;
    private volatile boolean padPlusLit = false;

    /** 胸口 mute 燈嘅控制權喺 XiaozhiBridge（小智連線態）。呢度淨係一個
     *  callback：節奏燈同「已連線」燈共用一盞實體燈，所以淨係報「呢次係
     *  因為節奏燈而定/唔定」，真正點邊個色由嗰邊 OR 埋小智狀態。 */
    public interface MuteLedSink {
        void applyMuteLed(boolean litByDisco);
    }

    private volatile MuteLedSink muteLedSink = null;

    public void setMuteLedSink(MuteLedSink s) {
        this.muteLedSink = s;
    }

    /** 節奏燈一齊著埋胸口 mute 燈／一齊熄。跟 disco job 同一個時機叫，
     *  唔係另外一套狀態機。 */
    public void discoMuteLed(boolean litByDisco) {
        MuteLedSink s = muteLedSink;
        if (s == null) return;
        try {
            s.applyMuteLed(litByDisco);
        } catch (Throwable t) {
            Log.w(TAG, "disco mute led failed", t);
        }
    }
    // pad toggle 讀改寫鎖：連撳快嗰陣兩個 request 會同時讀到同一個舊旗，
    // 一齊著，結果應該熄變著（之前「時得時唔得」就係咁嚟）。成個 toggle
    // 揸住把鎖做（連驅動 ops），排隊慢慢嚟，一次一個。
    private final Object padToggleLock = new Object();

    public HttpServer.ApiResponse ledPadSet(Map<String, String> query) {
        synchronized (padToggleLock) {
            return ledPadSetLocked(query);
        }
    }

    private HttpServer.ApiResponse ledPadSetLocked(Map<String, String> query) {
        String key = ApiValidator.requirePadKey(query);
        boolean minus = "minus".equals(key);
        String onStr = query.get("on");
        boolean on = (onStr == null || onStr.isEmpty())
                ? !(minus ? padMinusLit : padPlusLit)
                : ApiValidator.requireBoolean(query, "on");
        boolean ok;
        if (on) {
            ok = assertSingleLedBurst(minus ? PAD_LED_INDEX_MINUS : PAD_LED_INDEX_PLUS);
            if (ok) {
                if (minus) padMinusLit = true; else padPlusLit = true;
            }
        } else {
            // 熄邊粒就留返另一粒：OFF 係「一鑊清晒」，所以要補返。
            // 補幾粒用「實際應否著」（手動 OR 播歌指示燈）去判。
            // 注意：手動旗只可以改「今次撳嗰粒」，唔可以順手把對面粒嘅手動旗
            // 覆寫成有效值——咁樣會污染手動狀態（之前搞到歌停咗之後，另一粒
            // pad 永遠熄唔到）。對面粒原狀照留，appendRestoreOps 自然識補。
            if (minus) padMinusLit = false; else padPlusLit = false;
            // 一批過：OFF → 頭 → 眼 → 保留嘅 pad → wifi，一次 open，
            // 中間零停頓——逐個打要幾百 ms，肉眼見到成組閃一閃。
            java.util.List<DirectLedController.LedOp> ops = new java.util.ArrayList<>(6);
            ops.add(DirectLedController.LedOp.off());
            appendRestoreOps(ops, true, null);
            ok = DirectLedController.runBatch(ops);
            if (!ok) {
                // 衰咗（多數第一下 OFF 都打唔開）：response 話失敗，用家會再撳。
                Log.w(TAG, "pad set off failed");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":" + ok
                + ",\"minus\":" + padMinusLit
                + ",\"plus\":" + padPlusLit + "}");
    }

    /** 胸口 mute 燈（chest cmd 68）試燈：純粹點燈，唔掂小智連線。
     *  注意：小智連線／斷線、實體 mute 鍵會按真實狀態改寫（同撳掣無關），
     *  所以呢度 fire-and-forget，唔入 mirror。
     *  幀格式照抄 XiaozhiBridge（F8 8F 08 00 00 44 data sum ED），
     *  單發一次（之前連發三次，WebSocket 洗三行 chest_rcv，用家嫌煩）。 */
    public HttpServer.ApiResponse ledMuteSet(Map<String, String> query) {
        boolean on = ApiValidator.requireBoolean(query, "on");
        byte data = (byte) (on ? 1 : 0);
        int sum = (8 + 68 + (data & 0xFF)) & 0xFF;
        byte[] frame = {(byte) 0xF8, (byte) 0x8F, 0x08, 0x00, 0x00,
                (byte) 68, data, (byte) sum, (byte) 0xED};
        boolean sent = false;
        try {
            sent = HardwareDirectManager.get(appContext).chest().sendRaw(frame);
        } catch (Throwable t) {
            Log.w(TAG, "mute LED send failed", t);
        }
        return HttpServer.ApiResponse.okBool(sent);
    }

    // NOTE: unlike led/head/set and led/eye/set above, this does NOT go through
    // Alpha2RobotApi/AIDL at all - there is no AIDL "mouth LED" method. It goes
    // through MouthLedData, which since beta6 delegates to DirectLedController
    // (DRIVER_LOCK) - serialized with every other /dev/led_eye call, because the
    // old driver wedges hard on concurrent open() (disco mouth + head/eye racing
    // hung the whole robot with no ANR and dead adb). See MouthLedData's
    // javadoc for the confirmed field semantics.
    //
    // Simplified to the two effects confirmed usable on this hardware: a
    // breathing effect (speed adjustable, 100-1000ms) and off. effectMode values
    // other than 1 produced no light in testing, so there's no third "always
    // solid, no breathing" preset here - see README for what was tried. Also
    // triggered automatically around TTS start/end - see startMouthLedForTts()/
    // stopMouthLedForTts() above and their call sites in speech/tts,
    // onServerPlayEnd, and the Android TTS UtteranceProgressListener.
    public HttpServer.ApiResponse ledMouthSet(Map<String, String> query) {
        String mouthPreset = ApiValidator.requireMouthPreset(query);
        if ("off".equals(mouthPreset)) {
            boolean ok = MouthLedData.off().apply();
            return HttpServer.ApiResponse.okBool(ok);
        }
        int speed = ApiValidator.requireMouthSpeed(query);
        boolean ok = MouthLedData.breathing(speed).apply();
        return HttpServer.ApiResponse.okBool(ok);
    }

    public HttpServer.ApiResponse debugJniLed(Map<String, String> query) {
        // 直接試 /dev/led_eye 這個 JNI driver 的各個 native
        // function（眼/頭/嘴/pad 全部走呢條路）。
        // 很可能也是同一個 driver 另一個 ioctl (例如尚未用過的 ledSetOn(i))。
        // func=on&i=N -> ledSetOn(N); func=eye/head&a1..a8 -> 對應 setter。
        String func = ApiValidator.requireDebugLedFunc(query);
        // 同埋照舊排全局驅動鎖：唔好喺 disco 高頻打燈途中另起 open。
        if ("off".equals(func)) {
            final boolean[] res = new boolean[2];
            DirectLedController.runExclusive(() -> {
                res[0] = LedControl.open();
                res[1] = LedControl.ledSetOFF(0);
                LedControl.close();
            });
            // OFF 會連咀一齊清，補返（之前呼吸緊先補）。
            DirectLedController.restoreMouthAfterOff();
            // raw 反轉慣例：false＝ioctl 成功。之前一律 ok:true，連 open 失敗都報成功。
            boolean offOk = res[0] && !res[1];
            Log.i(TAG, "ledSetOFF open=" + res[0] + " raw=" + res[1]);
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":" + offOk + ",\"open\":" + res[0] + ",\"raw\":" + res[1] + "}");
        }
        final boolean[] openOk = new boolean[1];
        final String[] rbody = new String[1];
        try {
            DirectLedController.runExclusive(() -> {
                openOk[0] = LedControl.open();
                if (!openOk[0]) {
                    // 打唔開就唔好掂 ioctl（之前照打，close 白跑，仲要回 ok:true 呃人）。
                    rbody[0] = "{\"ok\":false,\"open\":false}";
                    return;
                }
                try {
                    if ("on".equals(func)) {
                        int i = ApiValidator.requireIntRange(query, "i", 0, 255);
                        boolean r = LedControl.ledSetOn(i);
                        Log.i(TAG, "ledSetOn(" + i + ") open=" + openOk[0] + " raw=" + r);
                        rbody[0] = "{\"ok\":" + !r + ",\"open\":" + openOk[0] + ",\"raw\":" + r + "}";
                        return;
                    }
                    int[] a = new int[8];
                    for (int k = 0; k < 8; k++) {
                        a[k] = ApiValidator.requireIntRange(query, "a" + (k + 1), 0, 255);
                    }
                    boolean r;
                    if ("eye".equals(func)) {
                        r = LedControl.ledSetEye(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    } else {
                        r = LedControl.ledSetHead(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    }
                    Log.i(TAG, "ledSet" + func + " open=" + openOk[0]
                            + " raw=" + r + " args=" + java.util.Arrays.toString(a));
                    rbody[0] = "{\"ok\":" + !r + ",\"open\":" + openOk[0]
                            + ",\"raw\":" + r + ",\"args\":"
                            + java.util.Arrays.toString(a).replace(" ", "") + "}";
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            // runExclusive 掟（少見）／參數超範圍：唔好 NPE 回 500，照回 ok:false。
            Log.w(TAG, "debugJniLed failed", t);
            return HttpServer.ApiResponse.ok("{\"ok\":false}");
        }
        // runExclusive 掟之前 rbody 未賦值嗰陣上面 catch 接住，而家一定非 null。
        return HttpServer.ApiResponse.ok(rbody[0] != null ? rbody[0] : "{\"ok\":false}");
    }

    public HttpServer.ApiResponse debugSerialSend(Map<String, String> query) {
        // raw serial 發送測試端點, 用來反推音量鍵 LED 和
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

    // -- MCP tools (XiaozhiBridge callTool switch 轉調) --

    /** self.robot.led_set_head 本體 (XiaozhiBridge 轉調)。
     *  pure-direct: 经 JNI 直驱，单发即稳住。
     *  同 HTTP 一套驗證（preset 枚舉＋color 1-7＋brightness 1-9），不合即報錯，
     *  不靜默 clamp／不靜默當 long（之前錯值直落 JNI／錯 preset 靜默變 long）。 */
    public SonarCenter.McpResult mcpLedSetHead(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "long");
        if (!"long".equals(preset) && !"flash".equals(preset) && !"breathe".equals(preset)
                && !"chase".equals(preset) && !"dual".equals(preset) && !"stop".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [long, flash, breathe, chase, dual, stop], got: " + preset);
        }
        UbxErrorCode.API_ERROR_CODE code;
        if ("stop".equals(preset)) {
            code = MainActivity.directCode(DirectLedController.stopHead5Mic());
        } else {
            if (!arguments.has("color") || !arguments.has("brightness")) {
                return SonarCenter.McpResult.err("color and brightness are required unless preset=stop");
            }
            int color = arguments.optInt("color");
            int brightness = arguments.optInt("brightness");
            if (color < ApiValidator.LED_COLOR_MIN || color > ApiValidator.LED_COLOR_MAX) {
                return SonarCenter.McpResult.err("color must be between "
                        + ApiValidator.LED_COLOR_MIN + " and " + ApiValidator.LED_COLOR_MAX
                        + ", got: " + color);
            }
            if (brightness < ApiValidator.LED_BRIGHTNESS_MIN
                    || brightness > ApiValidator.LED_BRIGHTNESS_MAX) {
                return SonarCenter.McpResult.err("brightness must be between "
                        + ApiValidator.LED_BRIGHTNESS_MIN + " and " + ApiValidator.LED_BRIGHTNESS_MAX
                        + ", got: " + brightness);
            }
            int p5, p6, p8;
            switch (preset) {
                case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
                case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
                case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
                case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
                case "long":
                default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
            }
            code = MainActivity.directCode(DirectLedController.setHead5MicRaw(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8));
        }
        boolean hReady = headerReady();
        return new SonarCenter.McpResult(!MainActivity.isOk(code) || !hReady,
                String.valueOf(code) + " (headerReady=" + hReady + ")");
    }

    /** self.robot.led_set_eye 本體 (XiaozhiBridge 轉調)。pure-direct: 经 JNI 直驱。
     *  同 HTTP 一套驗證——眼燈無 breathe（之前 "breathe" 靜默變 long，同 schema
     *  講嘅唔啱），錯 preset 即報錯。 */
    public SonarCenter.McpResult mcpLedSetEye(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "long");
        if (!"long".equals(preset) && !"flash".equals(preset) && !"chase".equals(preset)
                && !"dual".equals(preset) && !"stop".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [long, flash, chase, dual, stop]"
                            + " (eye has no breathe), got: " + preset);
        }
        UbxErrorCode.API_ERROR_CODE code;
        if ("stop".equals(preset)) {
            code = MainActivity.directCode(DirectLedController.stopEye5Mic());
        } else {
            if (!arguments.has("color") || !arguments.has("brightness")) {
                return SonarCenter.McpResult.err("color and brightness are required unless preset=stop");
            }
            int color = arguments.optInt("color");
            int brightness = arguments.optInt("brightness");
            if (color < ApiValidator.LED_COLOR_MIN || color > ApiValidator.LED_COLOR_MAX) {
                return SonarCenter.McpResult.err("color must be between "
                        + ApiValidator.LED_COLOR_MIN + " and " + ApiValidator.LED_COLOR_MAX
                        + ", got: " + color);
            }
            if (brightness < ApiValidator.LED_BRIGHTNESS_MIN
                    || brightness > ApiValidator.LED_BRIGHTNESS_MAX) {
                return SonarCenter.McpResult.err("brightness must be between "
                        + ApiValidator.LED_BRIGHTNESS_MIN + " and " + ApiValidator.LED_BRIGHTNESS_MAX
                        + ", got: " + brightness);
            }
            int p5, p6, p8;
            switch (preset) {
                case "flash": p5 = 100; p6 = 100; p8 = 0; break;
                case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
                case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
                case "long":
                default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
            }
            code = MainActivity.directCode(DirectLedController.setEye5MicRaw(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8));
        }
        boolean eReady = headerReady();
        return new SonarCenter.McpResult(!MainActivity.isOk(code) || !eReady,
                String.valueOf(code) + " (headerReady=" + eReady + ")");
    }

    /** self.robot.led_set_mouth 本體 (XiaozhiBridge 轉調)。
     *  同 HTTP 一套驗證（preset 枚舉＋speed 100-1000）：之前打錯 preset
     *  （如 "flash"）靜默變 breathing，speed 超範圍直落——家下即報錯。 */
    public SonarCenter.McpResult mcpLedSetMouth(org.json.JSONObject arguments) {
        String preset = arguments.optString("preset", "breathing");
        if (!"breathing".equals(preset) && !"off".equals(preset)) {
            return SonarCenter.McpResult.err(
                    "preset must be one of [breathing, off], got: " + preset);
        }
        boolean ok;
        if ("off".equals(preset)) {
            ok = MouthLedData.off().apply();
        } else {
            // 唔帶默認 150，同 HTTP requireMouthSpeed 睇齊（之前默認 0，
            // 但 0 出範圍次次都錯，唔帶即錯）。
            int speedMs = arguments.optInt("speed_ms", 150);
            if (speedMs < ApiValidator.LED_MOUTH_SPEED_MIN_MS
                    || speedMs > ApiValidator.LED_MOUTH_SPEED_MAX_MS) {
                return SonarCenter.McpResult.err("speed_ms must be between "
                        + ApiValidator.LED_MOUTH_SPEED_MIN_MS + " and "
                        + ApiValidator.LED_MOUTH_SPEED_MAX_MS + ", got: " + speedMs);
            }
            ok = MouthLedData.breathing(speedMs).apply();
        }
        return new SonarCenter.McpResult(!ok, "ok=" + ok);
    }
}



