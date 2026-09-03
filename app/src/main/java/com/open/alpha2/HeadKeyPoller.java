package com.open.alpha2;

import android.util.Log;

import com.ubtechinc.alpha.jni.headkey.HeadKeyMgr;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 头顶 +/- pad（pure-direct）。
 *
 * <p>3.002 路径（优先）：{@code libhead_key_mgr.so} native 线程读
 * {@code /dev/input/event*}（rk29-keypad），经
 * {@code HeadKeyMgr.onNativeCallback(int)} 回调本类，
 * 由 {@link #onNativeKey(int)} 按 0x5a..0x5f 合成 gesture。
 * 与 Java 直读互斥（双 reader 会分流事件丢键），native 起得来就不开 poll 线程。</p>
 *
 * <p>回退：native 任一步失败（.so 缺失/Init 失败）则沿用旧 Java 直读
 * {@code /dev/input/event0}（实测即 rk29-keypad，777 可读），按旧格式原样 publish
 * EventBus "gesture" 事件，后续音量/volume LED/stop-all 全走既有
 * {@code onGestureCode()} 管道，零改动。</p>
 *
 * <p>Linux input_event（32-bit ARM，小端，共 16 字节）：
 * {@code struct timeval(8) + type u16 + code u16 + value s32}。
 * 只处理 EV_KEY（type=1）。code 映射兼容两种驱动行为：
 * <ul>
 *   <li>若驱动直接报 0x5a..0x5f 六个码（旧 native 回调值）：value==1 按下时直发该码；
 *       value==0 抬起时，0x5a→0x5b、0x5c→0x5d，其余直发（旧语义：0x5b="-"放开等）。</li>
 *   <li>另跟踪 minus/plus 按住状态，两键同按时合成 0x5e（双键按下），双双放开后合成
 *       0x5f（双键放开），与旧 6 码表一致。</li>
 * </ul>
 * 所有原始键事件另以 "head_key" 事件（含 code/value）送 WebSocket log，方便核对。
 * native 回调另以 "head_key_native" 事件送原始码；native 自身亦写
 * {@code /sdcard/keyjnilog.txt}，可对照。</p>
 */
public class HeadKeyPoller extends HeadKeyMgr {
    private static final String TAG = "HeadKeyPoller";

    static final String EVENT_NODE = "/dev/input/event0";

    // 旧 gesture 码（见 AIDL_REFERENCE 第7章 + MainActivity.onGestureCode）
    private static final int KEY_MINUS = 0x5a;
    private static final int KEY_MINUS_UP = 0x5b;
    private static final int KEY_PLUS = 0x5c;
    private static final int KEY_PLUS_UP = 0x5d;
    private static final int KEY_BOTH = 0x5e;
    private static final int KEY_BOTH_UP = 0x5f;

    private static final int EV_KEY = 1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    private boolean minusDown = false;
    private boolean plusDown = false;
    private boolean bothReported = false;
    private boolean nativeActive = false;

    public synchronized void start() {
        if (running.get()) return;
        // 3.002 native 优先（与 Java 直读互斥，起得来就不开 poll 线程）。
        // 注意：反汇编证实 native Init() 成功失败一律回 0（结尾 moveq r0,#0），
        // 不可用返回值判断；照原装顺序调，成败看回调/“nativeRun” log。
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
     * native 按键回调。原始码先打 log + publish（与 /sdcard/keyjnilog.txt 对照），
     * 再按 0x5a..0x5f 显式状态表合成 gesture（不复用 poll 路径的 value 语义）。
     */
    @Override
    public void onNativeCallback(int code) {
        Log.i(TAG, "native key 0x" + Integer.toHexString(code));
        try {
            EventBus.get().publish("head_key_native", "{\"code\":" + code + "}");
        } catch (Throwable t) {
            Log.w(TAG, "publish head_key_native failed", t);
        }
        onNativeKey(code);
    }

    /**
     * native 回调解码：实测码形如 {@code 0x5A01/0x5B01/0x5E01…}，
     * 即旧 broadcast 格式 {@code (eventCode<<8)|0x01}（native log 另带序号，如
     * {@code key:0x5f01,47}）。native 侧已做完按住/合成（native 有 keycunt 状态），
     * 每个回调即一个完整 gesture 事件，高 8 bit 右移即得 0x5a..0x5f；
     * 此处只同步 minus/plus/both 状态（与 poll 路径共用，供后续合成参考）并直发。
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
                    // 非按键事件（SYN 等）忽略
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
        EventBus.get().publish("head_key", "{\"code\":" + code + ",\"value\":" + value + "}");
        boolean pressed = value == 1;
        boolean released = value == 0;
        if (!pressed && !released) return; // value==2 连发忽略（音量连发由 startVolumeRepeat 处理）

        // 驱动直报 0x5b/0x5d/0x5e/0x5f 这类合成码：沿用旧语义直发
        // （value==0 的合成码若出现则忽略，避免与下面 0x5a/0x5c 路径双发）。
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
                    // 双按中先松开一颗：先报双键放开，再报剩下一颗的按下态由其重发时处理
                    bothReported = false;
                    emitGesture(KEY_BOTH_UP);
                }
            }
            return;
        }
        // 其他键（如 USB 音频的 0x71-0x73）只记 log，不进 gesture 管道
    }

    private void syncBothState(int code, boolean pressed) {
        if (code == KEY_MINUS || code == KEY_MINUS_UP) minusDown = pressed && code == KEY_MINUS;
        else if (code == KEY_PLUS || code == KEY_PLUS_UP) plusDown = pressed && code == KEY_PLUS;
        else if (code == KEY_BOTH) { bothReported = pressed; if (pressed) { minusDown = true; plusDown = true; } }
        else if (code == KEY_BOTH_UP && !pressed) { bothReported = false; minusDown = false; plusDown = false; }
    }

    /** 按旧 broadcast 格式原样 publish：direction=(eventCode<<8)|0x01。 */
    private void emitGesture(int eventCode) {
        int raw = (eventCode << 8) | 0x01;
        Log.i(TAG, "gesture 0x" + Integer.toHexString(eventCode));
        try {
            EventBus.get().publish("gesture", "{\"direction\":" + raw + "}");
        } catch (Throwable t) {
            Log.w(TAG, "emitGesture failed", t);
        }
    }
}
