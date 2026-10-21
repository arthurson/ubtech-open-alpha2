package com.ubtechinc.alpha.hardware;

import android.os.SystemClock;
import android.util.Log;

import com.ubtechinc.alpha.jni.headkey.HeadKeyMgr;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 頭頂 +/- pad：5-mic 用 {@code libhead_key_mgr.so}；2-mic 用 1.1.7.3
 * {@code libNativeClassJni.so}（{@link #start()} 按 {@link HardwareVersion} 分流）。
 *
 * <p>native 線程讀 {@code /dev/input/event*}（rk29-keypad），5-mic 碼形如
 * {@code 0x5A01/0x5B01/0x5E01…}，2-mic 碼形如 {@code 0x3C01/0x3D01/0x4201…}
 * （即舊 broadcast 格式 {@code (eventCode<<8)|0x01}），由
 * {@link #onNativeKey(int)} 按 0x5a..0x5f／舊制 60/61/66 合成 gesture。
 * 回調來自 native 回調線程，非主線程。</p>
 *
 * <p>另支援舊制原始碼 60/61/66（1.x/2.x 世代 libNativeClassJni；同一粒
 * 3.002 lib 喺非 Lynx 機種／舊觸控板亦可能派呢套）——60/61＝短按、冇 down/up
 * 邊沿，合成 press+release click（v−／v＋）；66（interrupt）＝雙鍵總停。
 * 64/65（長按，官方語意係 QR 連接模式 toggle／未處理）本 app 冇等價嘢，
 * 唔映射。見 {@link #onNativeKey(int)}。</p>
 *
 * <p>收斂狀態機（吞重複／stray、雙鍵合成、去重）見下面各 locked 方法。
 * 注意：本類冇 Java 後備直讀——native 起唔嚟（.so 缺失／Init 失敗）就係
 * 起唔嚟，pad 全死，唔會靜靜跌落另一套語意（2026-09-30 決定，有問題直接
 * 睇 log 修，唔要兩套行為）。</p>
 *
 * <p>所有 native 回調原始碼另經 {@link Listener#onHeadKeyNative(int)} 上報
 * （調用方可轉送 WebSocket log 備查；native 自身亦寫
 * {@code /sdcard/keyjnilog.txt}，可對照）。</p>
 */
public class HeadKeyPoller extends HeadKeyMgr {
    private static final String TAG = "HeadKeyPoller";

    /** 上報接口（調用方實現）。 */
    public interface Listener {
        /** 合成 gesture 碼（0x5a..0x5f，見 AIDL_REFERENCE 第7章）。 */
        void onGesture(int eventCode);
        /** native 回調原始碼。 */
        void onHeadKeyNative(int code);
    }

    // 舊 gesture 碼（見 AIDL_REFERENCE 第7章 + GestureCenter.onGestureCode）
    private static final int KEY_MINUS = 0x5a;
    private static final int KEY_MINUS_UP = 0x5b;
    private static final int KEY_PLUS = 0x5c;
    private static final int KEY_PLUS_UP = 0x5d;
    private static final int KEY_BOTH = 0x5e;
    private static final int KEY_BOTH_UP = 0x5f;

    // 舊制原始碼（高 byte；1.x/2.x 世代 libNativeClassJni 同 3.0.0.2 官方
    // 非 Lynx fallback 共用呢套）：冇 press/lift 邊沿、冇 release，一粒碼
    // 代表成下動作。只有 60/61/66 喺本 app 有完整對應（音量−／音量＋／
    // 總停）；64/65（長按）官方語意係 QR 連接模式 toggle／未處理，唔映射。
    private static final int KEY_OLD_FORMOR_SHORT = 60;
    private static final int KEY_OLD_BACK_SHORT = 61;
    private static final int KEY_OLD_INTERRUPT = 66;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;
    // 2026-09-10 刪：startNativeWatchdog（6s 冇回調當死）——keyjnilog.txt 實證
    // 誤殺健康 native。起得就信佢長命，唔使定時查。

    private boolean minusDown = false;
    private boolean plusDown = false;
    private boolean bothReported = false;
    // 雙鍵旗來源：true＝上次由 synthetic 0x5e01 直設（唔係 raw 㩒出嚟）。
    // driver 長㩒唔會重發 down（3.4s hold 實證得一對），所以 raw down 到而
    // 旗已升，要分真 hold（吞，防萬一 flood）定 synthetic 殘留（當新㩒，防
    // stuck 吃單）。raw 邊到先清。
    private boolean bothSynthetic = false;
    private boolean nativeActive = false;
    // alpha2_10002 (2-mic, Android 4.4.2) 行 1.1.7.3 嘅 libNativeClassJni，
    // 唔用 libhead_key_mgr（見 start() 分支）。
    private com.example.t01.nativeClass nativeClass;
    private boolean nativeClassActive = false;
    /**
     * 舊制 click 嘅 press→release 間隔（ms）：一撳即著 pad LED（14/16，LedCenter
     * worker 80ms 一 tick）＋行一格音量，隔呢個時間先放 release 熄燈／收連發。
     * 必須 < GestureCenter 連發首格（300ms），先唔會多行一格。
     */
    static final long CLICK_RELEASE_DELAY_MS = 150;
    private java.util.Timer clickTimer;
    /**
     * 同一個雙撳去重窗（ms，uptime 計）。driver 會將同一吓雙撳報幾次
     * （raw 重疊＋遲到嘅 synthetic 0x5e01，中間重隔住 raw 放手）——窗內
     * 一律當同一下，唔報第二次（唔係總停開兩次）。代價：1 秒內真係撳多
     * 次雙撳，第二下會吞（正常人做唔到咁快，接受）。
     */
    private static final long DOUBLE_DEDUP_MS = 1000;
    private long lastBothFireMs = -1;

    public void setListener(Listener l) { listener = l; }

    public synchronized void start() {
        if (running.get()) return;
        // alpha2_10002 行 1.1.7.3 原裝 2-mic 路徑：libNativeClassJni.so。
        // 5-mic (Lynx) 先繼續用 libhead_key_mgr.so。
        if (HardwareVersion.is2Mic()) {
            startNativeClass();
            return;
        }
        // 與 Java 直讀互斥寫死喺度：native 起得來就係唯一 reader，唔開第二條。
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
                Log.w(TAG, "native head_key failed, no fallback: " + t.getMessage());
            }
        } else {
            Log.w(TAG, "head_key_mgr.so not loaded, head keys unavailable (no fallback)");
        }
    }

    private void startNativeClass() {
        try {
            nativeClass = new com.example.t01.nativeClass();
            com.example.t01.nativeClass.setPoller(this);
            running.set(true);
            // 原廠 GestureService.onCreate 次序：Init → nativeInit → nativeThreadStart。
            boolean initRet = nativeClass.Init();
            Log.i(TAG, "nativeClass.Init() returned " + initRet);
            nativeClass.nativeInit();
            nativeClass.nativeThreadStart();
            nativeClassActive = true;
            Log.i(TAG, "nativeClass head_key thread active (1.1.7.3 2-mic)");
        } catch (Throwable t) {
            running.set(false);
            nativeClassActive = false;
            com.example.t01.nativeClass.setPoller(null);
            Log.w(TAG, "nativeClass head_key failed, no fallback: " + t.getMessage());
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (nativeClassActive) {
            nativeClassActive = false;
            try {
                if (nativeClass != null) nativeClass.nativeThreadStop();
            } catch (Throwable t) {
                Log.w(TAG, "nativeClass nativeThreadStop failed: " + t.getMessage());
            }
            com.example.t01.nativeClass.setPoller(null);
        }
        if (nativeActive) {
            nativeActive = false;
            try {
                nativeThreadStop();
            } catch (Throwable t) {
                Log.w(TAG, "nativeThreadStop failed: " + t.getMessage());
            }
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

    /** libNativeClassJni（alpha2_10002）回調；碼值同舊制 60/61/66 一樣。 */
    public void onNativeClassCallback(int code) {
        if (!running.get()) return;
        Log.i(TAG, "nativeClass key 0x" + Integer.toHexString(code));
        onNativeKey(code);
    }

    /**
     * native 回調解碼：實測碼形如 {@code 0x5A01/0x5B01/0x5E01…}，
     * 即舊 broadcast 格式 {@code (eventCode<<8)|0x01}。
     * 0x5a..0x5d 行收斂狀態機（防雙重派發）；0x5e01/0x5f01 信 native 但唔
     * 重複報（raw 重疊報過就吞 synthetic 果粒；狀態全清先吞 0x5f01）。
     * 舊制 60/61 另走 click 合成、66＝總停（見各 case）；64/65 唔映射。
     *
     * package-private：等 unit test（scripts/test-headkeypoller.py）直接餵碼。
     */
    void onNativeKey(int code) {
        int eventCode = (code >> 8) & 0xFF;
        boolean publish = false;
        boolean minusClickRelease = false;
        boolean plusClickRelease = false;
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
                    // 信 native，但同一個雙撳窗內唔開第二次火（raw 重疊報過、
                    // 或者頭先先報過，呢粒係遲到 echo——連旗都唔掂，等佢好似
                    // 冇嚟過；唔係總停開兩次＋殘留假旗吃下一單）。
                    // 開火先 set 旗（等後續 raw 放手配對），並記低係 synthetic
                    // 嚟（raw 到先清，見 pressLocked/releaseLocked）。
                    if (tryFireBothLocked(tmp)) {
                        minusDown = true;
                        plusDown = true;
                        bothSynthetic = true;
                    }
                    publish = true;
                    break;
                case KEY_BOTH_UP:
                    // 同理：已經清晒就唔好再報一次 0x5f（免得多一鑊全局熄燈 burst）。
                    if (bothReported || minusDown || plusDown) {
                        minusDown = false;
                        plusDown = false;
                        bothReported = false;
                        bothSynthetic = false;
                        tmp.add(KEY_BOTH_UP);
                    }
                    publish = true;
                    break;
                case KEY_OLD_FORMOR_SHORT:
                    // 舊制 click 模型：一粒碼＝成下短按（driver 冇 release
                    // 邊沿）——即行 press（著 LED 14＜「−」＞＋行一格），release
                    // 隔 CLICK_RELEASE_DELAY_MS 先放：LED worker 有時間著燈，
                    // 連發首格（300ms）之前已收，唔會多行一格。
                    // 只有 60/61/66 有完整對應；64/65（長按，官方=QR 模式
                    // toggle／未處理）唔映射，跌落 default log。
                    tmp.add(KEY_MINUS);
                    minusClickRelease = true;
                    publish = true;
                    break;
                case KEY_OLD_BACK_SHORT:
                    // 同上（LED 16＜「＋」＞）。
                    tmp.add(KEY_PLUS);
                    plusClickRelease = true;
                    publish = true;
                    break;
                case KEY_OLD_INTERRUPT:
                    // interrupt＝總停，同 0x5e01 一條路：食同一個 1 秒去重窗
                    // （driver 重複報唔會開兩次火）；假旗交俾
                    // pressLocked/releaseLocked 嘅 synthetic 殘留清理。
                    if (tryFireBothLocked(tmp)) {
                        minusDown = true;
                        plusDown = true;
                        bothSynthetic = true;
                    }
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
        // press 出咗先排 release（Timer 最長 150ms 後到，次序保證）。
        if (minusClickRelease) scheduleClickRelease(KEY_MINUS_UP);
        if (plusClickRelease) scheduleClickRelease(KEY_PLUS_UP);
    }

    /**
     * 排舊制 click 嘅 release：press 已行咗一格／著咗燈，隔
     * {@link #CLICK_RELEASE_DELAY_MS} 先放。daemon Timer（唔阻 process 退出），
     * 唔 cancel——stop 後殘留一粒 release 最多 150ms 就到，listener 已清空
     * 就自然 no-op（GestureCenter.shutdown 先 setListener(null) 再 stop）。
     * package-private 等 unit test 可以覆寫捕捉（唔使等真時間）。
     */
    void scheduleClickRelease(final int eventCode) {
        final java.util.Timer t;
        synchronized (this) {
            if (clickTimer == null) {
                clickTimer = new java.util.Timer("headkey-click", true);
            }
            t = clickTimer;
        }
        t.schedule(new java.util.TimerTask() {
            @Override public void run() {
                emitGesture(eventCode);
            }
        }, CLICK_RELEASE_DELAY_MS);
    }

    /** 報雙撳（0x5e），同一個雙撳窗內只報一次（見 DOUBLE_DEDUP_MS）。
     *  回 true＝開咗火（調用方先 set 旗）；false＝echo，乜都唔掂。 */
    private boolean tryFireBothLocked(java.util.List<Integer> gestures) {
        long now = SystemClock.uptimeMillis();
        if (lastBothFireMs >= 0 && now - lastBothFireMs <= DOUBLE_DEDUP_MS) return false;
        lastBothFireMs = now;
        bothReported = true;
        gestures.add(KEY_BOTH);
        return true;
    }

    /** 鎖內 raw 按下。driver 長㩒唔重發 down（3.4s hold 實證得一對），所以
     *  down 到而旗已升，得兩種可能：真 hold 緊嘅重複（吞，防萬一 flood）vs
     *  synthetic 殘留旗（當新㩒，連另一粒假旗一齊清，防 stuck 吃單）。 */
    private boolean pressLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if ((isMinus ? minusDown : plusDown) && !(bothReported && bothSynthetic)) {
            return false;
        }
        // 新 edge（或殘留清理）：呢粒㩒落係真；synthetic 嚟嘅旗（包括另一粒）
        // 冇 raw 證據支持，一律作廢。
        if (bothSynthetic) {
            minusDown = false;
            plusDown = false;
            bothReported = false;
            bothSynthetic = false;
        }
        if (isMinus) minusDown = true; else plusDown = true;
        if (minusDown && plusDown) {
            tryFireBothLocked(gestures); // 重疊中唔報單鍵，等去重判
        } else {
            // 冇重疊就唔可能仲有 live 雙撳：bothReported 係殘留即清，照報單鍵。
            bothReported = false;
            gestures.add(isMinus ? KEY_MINUS : KEY_PLUS);
        }
        return true;
    }

    /** 鎖內 raw 放手：冇㩒過嘅 stray up 吞咗佢。synthetic 殘留喺度斷（另一粒
     *  假旗一齊清），但呢粒自己照正常程序報——行到呢度即係 driver 真係送咗
     *  個 up 嚟（冇 up 就冇 release）。 */
    private boolean releaseLocked(boolean isMinus, java.util.List<Integer> gestures) {
        if (bothSynthetic) {
            bothSynthetic = false;
            bothReported = false;
            if (isMinus) plusDown = false; else minusDown = false;
        }
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
