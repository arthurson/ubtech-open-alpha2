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
     * native 按鍵回調。原始碼先打 log + 上報（與 /sdcard/keyjnilog.txt 對照），
     * 再按 0x5a..0x5f 显式狀態表合成 gesture（不複用 poll 路徑的 value 語義）。
     */
    @Override
    public void onNativeCallback(int code) {
        Log.i(TAG, "native key 0x" + Integer.toHexString(code));
        Listener l = listener;
        if (l != null) {
            try {
                l.onHeadKeyNative(code);
            } catch (Throwable t) {
                Log.w(TAG, "onHeadKeyNative failed", t);
            }
        }
        onNativeKey(code);
    }

    /**
     * native 回調解碼：實測碼形如 {@code 0x5A01/0x5B01/0x5E01…}，
     * 即舊 broadcast 格式 {@code (eventCode<<8)|0x01}（native log 另帶序號，如
     * {@code key:0x5f01,47}）。native 侧已做完按住/合成（native 有 keycunt 狀態），
     * 每個回調即一個完整 gesture 事件，高 8 bit 右移即得 0x5a..0x5f；
     * 此處只同步 minus/plus/both 狀態（與 poll 路徑共用，供後續合成參考）並直發。
     */
    private synchronized void onNativeKey(int code) {
        int eventCode = (code >> 8) & 0xFF;
        switch (eventCode) {
            case KEY_MINUS:
                minusDown = true;
                emitGesture(KEY_MINUS);
                break;
            case KEY_MINUS_UP:
                minusDown = false;
                emitGesture(KEY_MINUS_UP);
                break;
            case KEY_PLUS:
                plusDown = true;
                emitGesture(KEY_PLUS);
                break;
            case KEY_PLUS_UP:
                plusDown = false;
                emitGesture(KEY_PLUS_UP);
                break;
            case KEY_BOTH:
                minusDown = true;
                plusDown = true;
                bothReported = true;
                emitGesture(KEY_BOTH);
                break;
            case KEY_BOTH_UP:
                minusDown = false;
                plusDown = false;
                bothReported = false;
                emitGesture(KEY_BOTH_UP);
                break;
            default:
                Log.d(TAG, "native key unmapped 0x" + Integer.toHexString(code));
                break;
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
                try { in.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            if (running.get()) Log.w(TAG, "poll loop ended: " + e.getMessage());
        }
        running.set(false);
    }

    private void onKey(int code, int value) {
        Log.d(TAG, "key code=0x" + Integer.toHexString(code) + " value=" + value);
        Listener l = listener;
        if (l != null) {
            try {
                l.onHeadKey(code, value);
            } catch (Throwable t) {
                Log.w(TAG, "onHeadKey failed", t);
            }
        }
        boolean pressed = value == 1;
        boolean released = value == 0;
        if (!pressed && !released) return; // value==2 連發忽略（音量連發由 startVolumeRepeat 處理）

        // 驅動直報 0x5b/0x5d/0x5e/0x5f 這類合成碼：沿用舊語義直發
        // （value==0 的合成碼若出現則忽略，避免與下面 0x5a/0x5c 路徑雙發）。
        if (code == KEY_MINUS_UP || code == KEY_PLUS_UP || code == KEY_BOTH || code == KEY_BOTH_UP) {
            if (pressed) emitGesture(code);
            syncBothState(code, pressed);
            return;
        }
        if (code == KEY_MINUS || code == KEY_PLUS) {
            if (pressed) {
                if (code == KEY_MINUS) minusDown = true; else plusDown = true;
                if (minusDown && plusDown && !bothReported) {
                    bothReported = true;
                    emitGesture(KEY_BOTH);
                } else if (!bothReported) {
                    emitGesture(code);
                }
            } else {
                if (code == KEY_MINUS) minusDown = false; else plusDown = false;
                if (bothReported && !minusDown && !plusDown) {
                    bothReported = false;
                    emitGesture(KEY_BOTH_UP);
                } else if (!bothReported) {
                    emitGesture(code == KEY_MINUS ? KEY_MINUS_UP : KEY_PLUS_UP);
                } else if (!minusDown || !plusDown) {
                    // 雙按中先鬆開一顆：先報雙鍵放開，再報剩下一顆的按下態由其重發時處理
                    bothReported = false;
                    emitGesture(KEY_BOTH_UP);
                }
            }
            return;
        }
        // 其他鍵（如 USB 音频的 0x71-0x73）只記 log，不進 gesture 管道
    }

    private void syncBothState(int code, boolean pressed) {
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
