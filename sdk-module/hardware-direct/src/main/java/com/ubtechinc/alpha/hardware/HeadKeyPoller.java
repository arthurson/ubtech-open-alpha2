package com.ubtechinc.alpha.hardware;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 頭頂 +/- pad（pure-direct，純 Java 直讀；不依賴任何 prebuilt .so）。
 *
 * <p>直讀 {@code /dev/input/event0}（實測即 rk29-keypad，777 可讀），解析
 * Linux input_event（32-bit ARM，小端，共 16 字節：
 * {@code struct timeval(8) + type u16 + code u16 + value s32}），只處理
 * EV_KEY（type=1）。調用方（如 GestureCenter）經 {@link Listener} 收 gesture，
 * 自行決定線程派發。本模塊不依賴 app 侧 EventBus。</p>
 *
 * <p>click 模型（logcat timestamp 實證）：driver 㩒落去即送成個 raw
 * (down,up) click（相隔 &lt;10ms），放手冇 signal、hold 睇唔到。一個 tap＝2 句
 * 係硬件真相，唔係 bug。單鍵（0x5a/0x5b/0x5c/0x5d）即到即報，唔等人；雙鍵靠
 * {@link #DOUBLE_WINDOW_MS} 配對窗合成——第二粒喺第一粒起計窗內㩒落就報
 * 0x5e（代替第二下單鍵，第一下單鍵已報，音量會先行一格再總停），兩邊放晒後報
 * 0x5f。每對時間戳只配對一次，連續交替撳唔會連環觸發。驅動直報嘅合成碼
 * （0x5b/0x5d/0x5e/0x5f，多數係 chatter，codes 求其嚟、幾秒後都仲有）一律過
 * 閘：0x5e 得喺兩邊重疊㩒住、或者兩邊先後腳（窗內）先信，其餘吞埋；0x5b/0x5d
 * 直報永遠唔理（真放手經下面 raw (code,0) 路徑）。所有吞咗嘅 chatter 60s 報數，
 * 唔逐句洗版。</p>
 *
 * <p>原始鍵事件經 {@link Listener#onHeadKey(int, int)} 上報（調用方可轉送
 * WebSocket log 備查）。回調來自 poll 線程，非主線程。</p>
 */
public class HeadKeyPoller {
    private static final String TAG = "HeadKeyPoller";

    /** 上報接口（調用方實現）。 */
    public interface Listener {
        /** 合成 gesture 碼（0x5a..0x5f，見 AIDL_REFERENCE 第7章）。 */
        void onGesture(int eventCode);
        /** 原始鍵事件（code/value，EV_KEY 語義）。 */
        void onHeadKey(int code, int value);
    }

    private static final String EVENT_NODE = "/dev/input/event0";

    // 舊 gesture 碼（見 AIDL_REFERENCE 第7章 + GestureCenter.onGestureCode）
    private static final int KEY_MINUS = 0x5a;
    private static final int KEY_MINUS_UP = 0x5b;
    private static final int KEY_PLUS = 0x5c;
    private static final int KEY_PLUS_UP = 0x5d;
    private static final int KEY_BOTH = 0x5e;
    private static final int KEY_BOTH_UP = 0x5f;

    private static final int EV_KEY = 1;

    /**
     * 雙鍵配對窗（ms，uptime 計）。兩指齊撳通常相差 &lt;200ms，但預留慢手
     * （一粒㩒完再㩒第二粒）放寬到 600ms。交替撳 -/+ 微調音量撞入窗會誤觸
     * 總停——正常調音量係同粒連撳，唔會中；因為誤觸代價係成個停晒，唔好再大。
     * 實機交替撳成日誤停就調細，慢手雙撳難食就調大。
     */
    static final int DOUBLE_WINDOW_MS = 600;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    private Thread thread;
    private volatile InputStream pollInput; // stop 閂佢先叫得醒 blocking read（interrupt 叫唔醒）

    private boolean minusDown = false;
    private boolean plusDown = false;
    private boolean bothReported = false;
    // 最近一次真 transition 按下（uptime ms；-1＝未配對／已消耗，每對只用一次）
    private long lastMinusPressMs = -1;
    private long lastPlusPressMs = -1;

    public void setListener(Listener l) { listener = l; }

    public synchronized void start() {
        if (running.get()) return;
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
        if (thread != null) {
            try { thread.interrupt(); thread.join(500); } catch (InterruptedException ignore) {}
            thread = null;
        }
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

    /** 鎖內合成碼（驅動直報 0x5b/0x5d/0x5e/0x5f，實證多數係 chatter）。
     *  0x5b/0x5d 直報成日唔理（唔 publish、唔 emit、唔掂 state；真放手經 raw
     *  (0x5a/0x5c,0) 路徑）。0x5e 直報：兩邊重疊㩒住、或者兩邊先後腳（窗內、
     *  驅動自己識合成雙撳嗰款）先信；0x5f 直報：得雙鍵已報先收。其餘一律吞。 */
    private boolean onSyntheticLocked(int code, boolean pressed,
                                      java.util.List<Integer> gestures) {
        if (code == KEY_MINUS_UP || code == KEY_PLUS_UP) return false;
        long now = SystemClock.uptimeMillis();
        if (pressed) {
            if (code == KEY_BOTH) {
                if (minusDown && plusDown && !bothReported) {
                    emitBothLocked(gestures);
                    return true;
                }
                if (!bothReported && windowPairedLocked(now)) {
                    emitBothLocked(gestures);
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

    /** 兩邊先後腳（窗內各㩒過一次，未配對）即配成雙撳。 */
    private boolean windowPairedLocked(long now) {
        return lastMinusPressMs >= 0 && lastPlusPressMs >= 0
                && now - lastMinusPressMs <= DOUBLE_WINDOW_MS
                && now - lastPlusPressMs <= DOUBLE_WINDOW_MS;
    }

    /** 鎖內報雙撳：時間戳即時作廢（每對只配一次，唔連環觸發）。 */
    private void emitBothLocked(java.util.List<Integer> gestures) {
        bothReported = true;
        lastMinusPressMs = -1;
        lastPlusPressMs = -1;
        gestures.add(KEY_BOTH);
    }

    /** 鎖內 raw 按下：重複 down 吞咗佢。第二粒喺窗內跟到就報 0x5e 代替單鍵
     *  （第一下單鍵已即時報過，唔等人——音量反應唔為等雙撳而遲）。 */
    private boolean pressLocked(boolean isMinus, java.util.List<Integer> gestures) {
        long now = SystemClock.uptimeMillis();
        if (isMinus ? minusDown : plusDown) return false;
        if (isMinus) { minusDown = true; lastMinusPressMs = now; }
        else { plusDown = true; lastPlusPressMs = now; }
        if (minusDown && plusDown && !bothReported) {
            emitBothLocked(gestures);
        } else if (!bothReported) {
            long lastOther = isMinus ? lastPlusPressMs : lastMinusPressMs;
            if (lastOther >= 0 && now - lastOther <= DOUBLE_WINDOW_MS) {
                emitBothLocked(gestures);
            } else {
                gestures.add(isMinus ? KEY_MINUS : KEY_PLUS);
            }
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
    /** 鎖內調用（onKeyLocked/onSyntheticLocked 嘅 synchronized 塊入面）。 */
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
