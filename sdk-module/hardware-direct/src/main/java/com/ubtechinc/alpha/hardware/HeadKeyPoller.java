package com.ubtechinc.alpha.hardware;

import android.util.Log;

import com.ubtechinc.alpha.jni.headkey.HeadKeyMgr;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 頭頂 +/- pad（pure-direct）。
 *
 * <p>3.002 路徑（優先）：{@code libhead_key_mgr.so} native 線程讀
 * {@code /dev/input/event*}（rk29-keypad），經
 * {@code HeadKeyMgr.onNativeCallback(int)} 回調本類，
 * 由 {@link #onNativeKey(int)} 按 0x5a..0x5f 合成 gesture。
 * 與 Java 直讀互斥（雙 reader 會分流事件丟鍵），native 起得來就不開 poll 線程。</p>
 *
 * <p>回退：native 任一步失敗（.so 缺失/Init 失敗）則沿用舊 Java 直讀
 * {@code /dev/input/event0}（實測即 rk29-keypad，777 可讀），按舊格式經
 * {@link Listener#onGesture(int)} 上報，調用方（如 MainActivity）自行決定
 * 線程派發（如 mainHandler.post 到 onGestureCode 管道）。本模塊不依賴
 * app 侧 EventBus。</p>
 *
 * <p>Linux input_event（32-bit ARM，小端，共 16 字節）：
 * {@code struct timeval(8) + type u16 + code u16 + value s32}。
 * 只處理 EV_KEY（type=1）。code 映射兼容兩種驅動行為：
 * <ul>
 *   <li>若驅動直接報 0x5a..0x5f 六個碼（舊 native 回調值）：value==1 按下時直發該碼；
 *       value==0 抬起時，0x5a→0x5b、0x5c→0x5d，其餘直發（舊語義：0x5b="-"放開等）。</li>
 *   <li>另跟踪 minus/plus 按住狀態，兩鍵同按時合成 0x5e（雙鍵按下），雙雙放開後合成
 *       0x5f（雙鍵放開），與舊 6 碼表一致。</li>
 * </ul>
 * 所有原始鍵事件另經 {@link Listener#onHeadKey(int, int)} /
 * {@link Listener#onHeadKeyNative(int)} 上報（調用方可轉送 WebSocket log 備查；
 * native 自身亦寫 {@code /sdcard/keyjnilog.txt}，可對照）。回調來自 poll 線程或
 * native 回調線程，非主線程。</p>
 */
public class HeadKeyPoller extends HeadKeyMgr {
    private static final String TAG = "HeadKeyPoller";

    /** 上報接口（調用方實現；native/直讀兩路共用）。 */
    public interface Listener {
        /** 合成 gesture 碼（0x5a..0x5f，見 AIDL_REFERENCE 第7章）。 */
        void onGesture(int eventCode);
        /** 原始鍵事件（code/value，EV_KEY 語義）。 */
        void onHeadKey(int code, int value);
        /** native 回調原始碼。 */
        void onHeadKeyNative(int code);
    }

    private static final String EVENT_NODE = "/dev/input/event0";

    // 舊 gesture 碼（見 AIDL_REFERENCE 第7章 + MainActivity.onGestureCode）
    private static final int KEY_MINUS = 0x5a;
    private static final int KEY_MINUS_UP = 0x5b;
    private static final int KEY_PLUS = 0x5c;
    private static final int KEY_PLUS_UP = 0x5d;
    private static final int KEY_BOTH = 0x5e;
    private static final int KEY_BOTH_UP = 0x5f;

    private static final int EV_KEY = 1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    private Thread thread;
    private volatile InputStream pollInput; // Java poll 用嘅 event0 流（stop 閂佢先叫得醒 blocking read）
    // 2026-09-10 刪：startNativeWatchdog（6s 冇回調當死）——keyjnilog.txt 實證
    // 誤殺健康 native（16:19:02 起、16:19:08 被 watchdog stop；開機頭幾秒冇人
    // 㩒掣好正常）。用返舊版方法：起得就信佢長命，唔使定時查。

    private boolean minusDown = false;
    private boolean plusDown = false;
    private boolean bothReported = false;
    private boolean nativeActive = false;

    public void setListener(Listener l) { listener = l; }

    public synchronized void start() {
        if (running.get()) return;
        // 3.002 native 優先（與 Java 直讀互斥，起得來就不開 poll 線程）。
        // 注意：反匯編證實 native Init() 成功失敗一律回 0（結尾 moveq r0,#0），
        // 不可用返回值判斷；照原裝順序調，成敗看回調/“nativeRun” log。
        if (HeadKeyMgr.isLibLoaded()) {
            try {
                boolean initRet = Init();
                Log.i(TAG, "HeadKeyMgr.Init() returned " + initRet + " (ignored, always false)");
                nativeInit();
                nativeThreadStart();
                nativeActive = true;
                running.set(true);
                Log.i(TAG, "native head_key thread active (3.002 libhead_key_mgr.so)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "native head_key failed, fallback to Java poll: " + t.getMessage());
            }
        } else {
            Log.w(TAG, "head_key_mgr.so not loaded, fallback to Java poll");
        }
        startJavaPoll();
    }

    public synchronized void stop() {
        running.set(false);
        // 閂 event0 流先叫得醒 blocking read（interrupt 叫唔醒），再 join。
        InputStream pin = pollInput;
        pollInput = null;
        if (pin != null) {
            try { pin.close(); } catch (Exception ignore) {}
        }
        if (nativeActive) {
            nativeActive = false;
            try {
                nativeThreadStop();
            } catch (Throwable t) {
                Log.w(TAG, "nativeThreadStop failed: " + t.getMessage());
            }
        }
        if (thread != null) {
            try { thread.interrupt(); thread.join(500); } catch (InterruptedException ignore) {}
            thread = null;
        }
    }

    /**
     * native 按鍵回調。原始碼先打 log（與 /sdcard/keyjnilog.txt 對照），
     * 上報＋手勢一律經下面 onNativeKey 嘅收斂狀態機（唔好喺呢度直報，
     * 否則 chatter/重複會雙重上報）。
     */
    @Override
    public void onNativeCallback(int code) {
        if (!running.get()) return; // stop 後殘留回調唔投遞
        Log.i(TAG, "native key 0x" + Integer.toHexString(code));
        onNativeKey(code);
    }

    /**
     * native 回調解碼：實測碼形如 {@code 0x5A01/0x5B01/0x5E01…}，
     * 即舊 broadcast 格式 {@code (eventCode<<8)|0x01}。
     * 2026-09-10 v2：0x5a..0x5d 行收斂狀態機（同 poll 共用，防雙重派發）；
     * 0x5e01/0x5f01 信 native（driver 識得合成雙㩒——keyjnilog 16:47:05 實測
     * 雙㩒淨係嚟呢隻、唔帶 raw 前綴；T15 放寬條件成立）。chatter 0x5e 誤觸
     * 總停嘅風險照舊碼接受（舊碼一樣無條件收，用戶實證冇事）；頂唔順即 revert。
     */
    private void onNativeKey(int code) {
        int eventCode = (code >> 8) & 0xFF;
        // 同 poll onKey 同一把鎖；emit/上報放鎖外（理由同上）。
        boolean publish = false;
        java.util.List<Integer> tmp = new java.util.ArrayList<Integer>(1);
        synchronized (this) {
            switch (eventCode) {
                case KEY_MINUS:
                    publish = pressLocked(true, tmp);
                    break;
                case KEY_MINUS_UP:
                    publish = releaseLocked(true, tmp);
                    break;
                case KEY_PLUS:
                    publish = pressLocked(false, tmp);
                    break;
                case KEY_PLUS_UP:
                    publish = releaseLocked(false, tmp);
                    break;
                case KEY_BOTH:
                    // 信：連 raw 狀態都照 set（等後續 raw 唔會撞；見 pressLocked
                    // 開頭清殘留 both 態）。
                    minusDown = true;
                    plusDown = true;
                    bothReported = true;
                    tmp.add(KEY_BOTH);
                    publish = true;
                    break;
                case KEY_BOTH_UP:
                    minusDown = false;
                    plusDown = false;
                    bothReported = false;
                    tmp.add(KEY_BOTH_UP);
                    publish = true;
                    break;
                default:
                    Log.d(TAG, "native key unmapped 0x" + Integer.toHexString(code));
                    break;
            }
        }
        if (publish) {
            Listener l = listener;
            if (l != null) {
                try {
                    l.onHeadKeyNative(code);
                } catch (Throwable t) {
                    Log.w(TAG, "onHeadKeyNative failed", t);
                }
            }
        }
        for (int i = 0; i < tmp.size(); i++) emitGesture(tmp.get(i));
    }

    private void startJavaPoll() {
        File f = new File(EVENT_NODE);
        if (!f.exists()) {
            Log.w(TAG, EVENT_NODE + " not found, head keys unavailable (pure-direct)");
            return;
        }
        running.set(true);
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "HeadKeyPoll");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "polling " + EVENT_NODE + " (pure-direct, no gesture broadcast needed)");
    }

    private void loop() {
        byte[] ev = new byte[16];
        try {
            InputStream in = new FileInputStream(EVENT_NODE);
            pollInput = in; // 存 field，stop() 閂佢叫醒 blocking read
            try {
                while (running.get()) {
                    int got = 0;
                    while (got < 16) {
                        int n;
                        try {
                            n = in.read(ev, got, 16 - got);
                        } catch (Exception e) {
                            Log.w(TAG, "read error: " + e.getMessage());
                            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                            break;
                        }
                        if (n < 0) {
                            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                            break;
                        }
                        got += n;
                    }
                    if (got < 16) continue;
                    int type = (ev[8] & 0xFF) | ((ev[9] & 0xFF) << 8);
                    int code = (ev[10] & 0xFF) | ((ev[11] & 0xFF) << 8);
                    int value = (ev[12] & 0xFF) | ((ev[13] & 0xFF) << 8)
                            | ((ev[14] & 0xFF) << 16) | (ev[15] << 24);
                    if (type == EV_KEY) onKey(code, value);
                    // 非按鍵事件（SYN 等）忽略
                }
            } finally {
                pollInput = null;
                try { in.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            if (running.get()) Log.w(TAG, "poll loop ended: " + e.getMessage());
        }
        running.set(false);
    }

    private int chatterSuppressed = 0;
    private long chatterLastLogMs = 0;

    private void onKey(int code, int value) {
        boolean pressed = value == 1;
        boolean released = value == 0;
        if (!pressed && !released) {
            // value==2 連發：照舊轉送 raw（備查），唔進手勢管線。
            Log.d(TAG, "key code=0x" + Integer.toHexString(code) + " value=" + value);
            notifyHeadKey(code, value);
            return;
        }
        // 2026-09-10 click 模型（logcat timestamp 實證）：driver 㩒落去即送
        // 成個 raw (down,up) click（相隔 <10ms），放手冇 signal、hold 睇唔到、
        // 周圍重有合成碼 chatter。一個 tap＝2 句係硬件真相，唔係 bug。
        // 呢度只保證：chatter 零輸出、重複/stray 事件吞埋；長按/雙鍵語意搬去
        // GestureCenter（即行一格＋500ms 雙擊窗）實現。
        boolean publish;
        java.util.List<Integer> gestures = new java.util.ArrayList<Integer>(2);
        synchronized (this) {
            if (isSynthetic(code)) {
                publish = onSyntheticLocked(code, pressed, gestures);
            } else {
                publish = onKeyLocked(code, pressed, gestures);
            }
        }
        if (!publish && gestures.isEmpty()) {
            // 吞咗嘅 chatter 唔逐句打 log（之前每粒一句，logcat 洗版），60s 報數。
            chatterSuppressed++;
            long now = System.currentTimeMillis();
            if (now - chatterLastLogMs > 60000) {
                chatterLastLogMs = now;
                Log.d(TAG, "headkey chatter suppressed x" + chatterSuppressed);
            }
            return;
        }
        Log.d(TAG, "key code=0x" + Integer.toHexString(code) + " value=" + value);
        if (publish) notifyHeadKey(code, value);
        for (int i = 0; i < gestures.size(); i++) emitGesture(gestures.get(i));
    }

    private static boolean isSynthetic(int code) {
        return code == KEY_MINUS_UP || code == KEY_PLUS_UP || code == KEY_BOTH || code == KEY_BOTH_UP;
    }

    private void notifyHeadKey(int code, int value) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onHeadKey(code, value);
            } catch (Throwable t) {
                Log.w(TAG, "onHeadKey failed", t);
            }
        }
    }

    /** 鎖內 raw 狀態機（0x5a/0x5c；回 boolean＝使唔使 publish head_key）。
     *  得真 transition 先行（重複 down/stray up 一律吞）。其他碼（如 USB 音频
     *  0x71-0x73）照舊轉送，唔進 gesture 管道。 */
    private boolean onKeyLocked(int code, boolean pressed,
                               java.util.List<Integer> gestures) {
        if (code == KEY_MINUS) return pressed ? pressLocked(true, gestures) : releaseLocked(true, gestures);
        if (code == KEY_PLUS) return pressed ? pressLocked(false, gestures) : releaseLocked(false, gestures);
        return true;
    }

    /** 鎖內合成碼（驅動直報 0x5b/0x5d/0x5e/0x5f）。
     *  2026-09-10 click 模型：呢啲全部係 chatter（codes 求其嚟，幾秒後都仲有），
     *  0x5b/0x5d 成日唔理（唔 publish、唔 emit、唔掂 state）；0x5e/0x5f 保留
     *  狀態門檻（冇 raw 雙㩒狀態就吞）。真事件（raw click）經下面 raw 路徑。 */
    private boolean onSyntheticLocked(int code, boolean pressed,
                                      java.util.List<Integer> gestures) {
        if (code == KEY_MINUS_UP || code == KEY_PLUS_UP) return false;
        if (pressed) {
            if (code == KEY_BOTH) {
                if (minusDown && plusDown && !bothReported) {
                    bothReported = true;
                    gestures.add(KEY_BOTH);
                    return true;
                }
                return false;
            }
            if (code == KEY_BOTH_UP) {
                if (!bothReported) return false;
                bothReported = false;
                minusDown = false;
                plusDown = false;
                gestures.add(KEY_BOTH_UP);
                return true;
            }
            return false;
        }
        // value==0：0x5e/0x5f 先 sync（郁到 state 先 publish；chatter 嗰陣
        // flags 本來就清，自動靜默）。
        boolean b0 = bothReported;
        syncBothStateLocked(code, false);
        return bothReported != b0;
    }

    /** 鎖內 raw 按下：重複 down 吞咗佢。 */
    private boolean pressLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if (isMinus ? minusDown : plusDown) return false;
        if (isMinus) minusDown = true; else plusDown = true;
        if (minusDown && plusDown && !bothReported) {
            bothReported = true;
            gestures.add(KEY_BOTH);
        } else if (!bothReported) {
            gestures.add(isMinus ? KEY_MINUS : KEY_PLUS);
        }
        return true;
    }

    /** 鎖內 raw 放手（合成碼 pressed 經呢度借路，語意一致）：冇㩒過嘅 stray up 吞咗佢。 */
    private boolean releaseLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if (isMinus ? !minusDown : !plusDown) return false;
        if (isMinus) minusDown = false; else plusDown = false;
        if (bothReported && !minusDown && !plusDown) {
            bothReported = false;
            gestures.add(KEY_BOTH_UP);
        } else if (!bothReported) {
            gestures.add(isMinus ? KEY_MINUS_UP : KEY_PLUS_UP);
        } else if (!minusDown || !plusDown) {
            // 雙按中先鬆開一顆：先報雙鍵放開，再報剩下一顆的按下態由其重發時處理
            bothReported = false;
            gestures.add(KEY_BOTH_UP);
        }
        return true;
    }
    /** 鎖內調用（onKeyLocked/onNativeKey 嘅 synchronized 塊入面）。 */
    private void syncBothStateLocked(int code, boolean pressed) {
        if (code == KEY_MINUS || code == KEY_MINUS_UP) minusDown = pressed && code == KEY_MINUS;
        else if (code == KEY_PLUS || code == KEY_PLUS_UP) plusDown = pressed && code == KEY_PLUS;
        else if (code == KEY_BOTH) { bothReported = pressed; if (pressed) { minusDown = true; plusDown = true; } }
        else if (code == KEY_BOTH_UP && !pressed) { bothReported = false; minusDown = false; plusDown = false; }
    }

    /** 合成 gesture 上報（舊 broadcast direction=(eventCode<<8)|0x01 語義由調用方按需还原）。 */
    private void emitGesture(int eventCode) {
        Log.i(TAG, "gesture 0x" + Integer.toHexString(eventCode));
        Listener l = listener;
        if (l != null) {
            try {
                l.onGesture(eventCode);
            } catch (Throwable t) {
                Log.w(TAG, "onGesture failed", t);
            }
        }
    }
}
