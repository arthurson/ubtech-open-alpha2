package com.open.alpha2;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.RobotWire;import com.ubtechinc.alpha.jni.LedControl;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.HeadKeyPoller;
import com.ubtechinc.alpha.hardware.LocalAlpha2Services;
import com.ubtechinc.alpha.hardware.MouthLedData;
import com.ubtechinc.alpha.hardware.ubx.UbxFile;
import com.ubtechinc.alpha.hardware.ubx.UbxParser;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Single-activity host for the Open Alpha2 robot panel.
 *
 * Owns the one {@link RobotStub} instance for the process (2026-09 起同
 * Alpha2OpenSdk 脫鉤：機身無 alpha2services，所有舊 binder 調用誠實失敗；
 * 真正行硬件經 HardwareDirectManager／DirectLedController／Android 原生 API),
 * initialises every sub-system and answers every "/api/..." HTTP call from
 * {@link HttpServer}. All asynchronous hardware callbacks are pushed to
 * {@link EventBus} so the browser panel's WebSocket log updates live.
 *
 * The activity itself shows minimal on-device status (IP:port, init state) since the
 * robot has no practical on-screen use for this tool - the HTML control panel at
 * http://<robot-ip>:8888/ is the actual UI.
 */
public class MainActivity extends Activity implements XiaozhiBridge.HostState, ApiDispatcher.Host {
    private static final String TAG = "MainActivity";

    static final String PREFS_NAME = "robotpanel";
    // 2026-09: 小智 OTA/MCP/TTS/auto-connect prefs key 搬咗去 XiaozhiConfig
    // (拆 god object 第五刀)， publicly 讀寫經嗰邊。
    // (本地音樂 prefs key 已搬去 AudioCenter。)
    // (MCP/TTS prefs key 已搬去 XiaozhiConfig。)
    // (TTS 卡語言 pref 搬咗去 TtsCenter。)
    // (MCP disabled-tools / auto-connect prefs key 已搬去 XiaozhiConfig。)

    // 2026-09: 一鍵全停回位動作 id 搬咗去 ActionDirect.STOP_RECOVERY_ACTION_ID
    // (蹲下站起；停止語義不變，見 actionDirect.stopActionWithRecovery())。

    private RobotStub robot;
    private LocalAlpha2Services localServices;
    // 動作配樂由 UbxPlayer 内 voice 线负责（a/j/a/o 官方语义：同 clock 并行、
    // 槽位起播、b*timeBase 自停、切帧打断），獨立於 currentMusicPlayer/currentRadioPlayer，
    // 唔經 filler 循環/EQ/頻譜。
    private final UbxPlayer ubxPlayer = new UbxPlayer();
    // 2026-09: 動作直驅層 (actionInfo 尋址/播放/停止/回位) 搬咗去 ActionDirect，
    // ubx 直播/單舵機搬咗去 UbxApi；三個共用上面同一個 ubxPlayer 實例
    // (servo 讀寫仲喺呢度直接用)。
    private ActionDirect actionDirect;
    private UbxApi ubxApi;
    private final HeadKeyPoller headKeyPoller = new HeadKeyPoller();
    private HttpServer httpServer;
    private RobotEventReceiver dynamicReceiver;

    // -- WiFi 指示燈 (2026-08-25) -----------------------------------------------
    // (wifi 燈成組搬咗去 LedCenter：12/13 映射、receiver、apply 三式、burst。)
    private BroadcastReceiver panelUrlReceiver;
    private TextView panelLinkView;
    private String currentPanelUrl;
    private final CameraController cameraController = new CameraController();
    private final AudioController audioController = new AudioController();
    private final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();
    private final MusicController musicController = new MusicController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable volumeRepeater;
    private AudioManager audioManager;

    // (Pad 燈成組搬咗去 LedCenter：executor/postPadLed/旗標/worker/burst。)

    /** true = 用戶在 TTS tab 按了「釋放麥克風給 App」，想長期持有 mic 給 app 用，
     *  沒按回「交回麥克風給機器人」之前不算完。見 handleMicStream() finally 段的
     *  用法 - Mic Listen 的 stream 斷開不應該在這個狀態是 true 的時候將 mic
     *  還給機械人，否則「釋放」狀態會被 Mic Listen 的斷線清掉，讓用戶要不斷
     *  重新按「釋放麥克風給 App」。 */
    private volatile boolean micHeldByApp = false;

    /** true = 用戶開了「持續搶 mic」這個選項 (mic card 那顆 checkbox)。和
     *  micHeldByApp 不同 - micHeldByApp 只是記住「現在這個狀態是不是 app 持有」,
     *  這個 flag 是說「就算 firmware 自己內部側面拿回了 (例如 setWakeState
     *  這個 call 本身在 firmware bytecode 裡面會順便觸發 IflytekWakeUp5mic.
     *  startRecording() 這個 side effect - 不是用戶自己按了「交回」), 都要
     *  自動再搶一次回來」。見 micHoldEnforcer 這條背景 thread。 */
    private volatile boolean micHoldEnforced = false;
    private Thread micHoldEnforcerThread;
    static final long MIC_HOLD_ENFORCER_INTERVAL_MS = 2000;

    // (xiaozhi_actions.json catalog 搬咗去 ActionDirect。)
    /** 2026-08 新增: 完全取代悠聊 APK (com.ubtech.iflytekmix) 用的中文語意配對引擎
     *  實例。在 onCreate() 建立一次 (只持有 Context, 不碰 AIDL, 沒有初始化順序問題),
     *  真正的 1000 條資料就到 handleIflytekSemanticText() 第一次被叫才讀 assets - 見
     *  IflytekSemanticMatcher 本身的 lazy-load 設計。 */
    private IflytekSemanticMatcher iflytekMatcher;

    /** 2026-08 新增: 完全取代 AlphaEnglishChat APK
     *  (com.ubtechinc.alphaenglishchat) 用的英文語意配對引擎實例, 和 iflytekMatcher
     *  屬於同一套機制、獨立資料 (1000 條英文問法, 見 IflytekSemanticMatcherEn)。
     *  哪句用哪個 matcher 由 handleIflytekSemanticText() 根據輸入文字有沒有 CJK 漢字
     *  判斷 - 不靠 speech/set_asr_engine 的語言設定, 因為 iFlytek 引擎本身可能自動
     *  偵測語言, 靠內容判斷更可靠。 */
    private IflytekSemanticMatcherEn iflytekMatcherEn;

    /** 2026-09 新增: Vosk 離線 ASR controller (語音 tab)。單例，onCreate 起，
     *  onDestroy 停。Model 放 sdcard 自動偵測，見 VoskController。 */
    private VoskController vosk;

    // (電台搜尋 cache 搬咗去 AudioCenter。)

    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
    // 2026-09: 系統鈴聲層 (停止/快門/PIR 提示音 + 共用播放器 + 查表快取)
    // 搬咗去 RingtoneCenter (拆 god object 第七刀)，呢度淨係留個 instance。
    private RingtoneCenter ringtoneCenter;

    // 2026-09 刪除: speechReady field - 無 ASR，舊 binder speech service 永遠
    // ready 不了（唯一設 true 嘅舊 initOver 已刪），恆 false 無意義。

    // speech/stop -> speech/tts race guard.
    //
    // speech_StopTTS() (AIDL onStopPlay) is fire-and-forget: the call returns as
    // soon as the binder transaction is queued, but the robot side's audio
    // teardown (tearing down the current Nuance/iFlytek playback session) happens
    // asynchronously after that. If speech/tts starts a new TTS session while that
    // teardown is still in flight, Nuance's SpeakerPlayerSink can throw an
    // IllegalStateException that kills the TTS session until the robot reboots.
    //
    // Fix: record the wall-clock time of the last speech/stop, and have speech/tts
    // block (on the HTTP worker thread only - safe because HttpServer uses
    // newCachedThreadPool, so this never stalls other requests) until at least
    // STOP_TO_TTS_MIN_GAP_MS has elapsed since that stop. 400ms was enough headroom
    // in testing for the teardown to finish without being long enough to feel like
    // a UI stall for a normal stop-then-speak flow.
    static final long STOP_TO_TTS_MIN_GAP_MS = 400;
    private volatile long lastSpeechStopAtMs = 0L;
    // 追蹤機身 robot-side TTS (nuance/iflytek, 經 robot.speech_startTTS() 走)
    // 現在是不是正在播 - 由 startXiaozhiMicHoldEnforcer()/startMicHoldEnforcer()
    // 用來決定要不要跳過這一輪 speech_SetMIC(true)。背景: 兩條 mic-hold
    // enforcer thread 每 MIC_HOLD_ENFORCER_INTERVAL_MS (2 秒) 就會無條件搶一次
    // mic, 一句超過 2 秒才讀完的句子播到一半就被 speech_SetMIC(true) 打斷
    // (真機 logcat 見過 "ttsGenerationFinished ... success = false" 接著立刻
    // "setWakeState onWake:true") - Android system TTS 不經這個 AIDL 通道,
    // 不會撞到, 所以之前只有 iflytek/nuance 斷斷續續, android 沒事。
    private volatile boolean robotTtsSpeaking = false;

    // Chest sonar trigger threshold in cm, as last set via servo/sonar. Assumption
    // (unverified on real hardware): chest_configureSonar()'s distance byte IS the
    // threshold in cm directly (0-100 fits a single byte with room to spare) - kept
    // here purely so the obstacle-triggered purple-LED logic below knows what
    // threshold is currently active, and so the front-end chart can draw it as a
    // reference line against live sonar readings.
    private volatile int sonarThresholdCm = 30;
    private volatile boolean sonarLedActive = false;
    // 2026-08 新增: onSonarDistanceReceived() 之前只是用來判斷 triggered 有沒有改變
    // (驅動 LED), 沒有存下實際讀數本身 - XiaoZhi MCP tool (self.sensors.get_sonar)
    // 要給 LLM 隨時查詢「現在距離多少」, 不只「有沒有觸發」, 所以這裡加一個 cache
    // 著最新讀數的 field。-1 代表「未收過任何讀數」, 和真實距離 (恆為非負) 區分開,
    // 給 MCP tool 可以告訴 LLM 這是「未有數據」而不是「距離 0cm」。
    private volatile int lastSonarDistanceCm = -1;
    // 2026-08 新增: 和 lastSonarDistanceCm 同一個目的 - PIR 事件之前只是即時
    // publish 去 EventBus (見 RobotEventReceiver 的 "com.ubtechinc.key"/-109 case),
    // 沒存下最新狀態給 MCP tool 隨時查詢。-1 = 未收過任何 PIR 事件, 0 = 上次收到
    // 的是 EXIT (沒人), 1 = 上次收到的是 ENTER (有人) - 用 int 不用 boolean 來
    // 保留「未有數據」這個第三種狀態, 和 lastSonarDistanceCm 用 -1 的原因一樣。
    private volatile int lastPirTriggeredState = -1;
    // 2026-09: 胸口版本/UUID 同步查詢成組搬咗去 ChestQuery (第一刀拆 god
    // object)——latch/raw/len 狀態、幀解析、阻塞查詢全部喺嗰邊，呢度淨係留個 instance。
    // 2026-09 刪除: headerVersionLatch/Raw/Len (唯一讀者 queryHeaderFirmwareVersion
    // 無 caller，一併刪除)。
    private ChestQuery chestQuery;
    // 2026-09: 胸口升級成組 (48/49/50 狀態+線程+ACK) 搬咗去 ChestUpgrade
    // (拆 god object 第四刀)，呢度淨係留個 instance。
    private ChestUpgrade chestUpgrade;

    // 2026-08 新增: 用戶要求「如果有其他動作要做, 就只做其他動作」- 之前純粹
    // 靠 self.robot.play_random_action 的 tool description 勸 LLM 自己選優先順序,
    // 但實測發現 LLM 有時整段對話一次都不 call play_random_action (可能覺得每輪
    // 都有其他事情做, 或者純粹沒去用), 結果機械人站定完全不動, 用戶看起來好像
    // 「random 動作完全沒了」。之前試過用一個 flag 追蹤著「這一輪有沒有 LLM 自己
    // call 過動作類 tool」, 沒有就在 TTS "stop" (回應播完) 才補一個 random action -
    // 但用戶其後糾正: random 動作應該和 TTS 一起做 (也就是開始說話那一刻就動), 不是
    // 「說完才做」, 所以這個做法已經改在 TTS "start" 事件那裡直接觸發 (見
    // setTtsStateListener() 那段), 不再靠這個 flag 判斷「這一輪有沒有其他動作」 -
    // 拿掉了這個字段和相關的 set 語句 (曾經在 play_action/stop_action/
    // play_random_action 三個 case 出現過), 因為現在這個時機邏輯已經不需要它。

    /** RobotEventReceiver 的 "alpha2_pir_state" publish 之後順手 call 這個, 讓
     *  self.sensors.get_pir MCP tool 可以讀到最新狀態, 不用自己另外訂閱
     *  EventBus。沒 instance 就靜靜地不做事 (和 onSonarDistanceReceived() 一致的
     *  處理)。
     *
     *  ⚠️ 這個方法是在 RobotEventReceiver (一個 BroadcastReceiver) 的
     *  onReceive() 裡面直接被 call, 也就是說這個方法本身、和它叫的任何東西, 都
     *  **一定不可以有阻塞式操作** (Thread.sleep、網路 IO、等等) - BroadcastReceiver.
     *  onReceive() 有嚴格時限 (通常十秒內要返回), 密集的 PIR broadcast 一波接一波
     *  的時候, 阻塞邏輯會連環卡住, 輕則觸發 ANR, 重則 (2026-08 一次粗心的版本
     *  真機實測證實) 直接 hold 死整個 system 連 adb 都沒反應。所以這裡只做
     *  最輕的 field 寫入, 任何要送 WebSocket 訊息的耗時邏輯都必須包多一層獨立
     *  thread 才可以做 (見下面 new Thread(...).start())。 */
    static void onPirStateReceived(final boolean triggered) {
        final MainActivity m = sInstance;
        if (m == null) {
            return;
        }
        int newState = triggered ? 1 : 0;
        if (newState == m.lastPirTriggeredState) {
            return; // 狀態沒變, 不重複推播 (和 sonar 的 dedup pattern 一致)
        }
        m.lastPirTriggeredState = newState;
        // 2026-08 新增: 用戶要求「不是叫一次做一次, 而是只要 PIR 開了, 每次
        // broadcast 回報有不同都要有反應」- 也就是要事件驅動、主動告訴小智知道,
        // 不是只給 LLM 隨時查詢。這段一定要包在獨立 thread 裡才可以做
        // (xiaozhiSendDetectTextSafely() 裡面有 Thread.sleep + 阻塞式 WebSocket
        // send, 原因見上面 class javadoc 段的慘痛教訓), 保持 onReceive() 本身
        // 立刻返回, 不會阻住這個 broadcast dispatch。
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (m.xiaozhiBridge == null || !m.xiaozhiBridge.isConnected()) {
                    return;
                }
                String text = triggered
                        ? "[系統事件] PIR 人體感應器偵測到有人在附近。"
                        : "[系統事件] PIR 人體感應器偵測不到人在附近了。";
                String err = m.xiaozhiBridge.sendDetectText(text);
                if (err != null) {
                    android.util.Log.w("XiaozhiPir", "failed to push PIR event to XiaoZhi: " + err);
                }
            }
        }, "XiaozhiPirEventPush").start();
    }

    // 2026-09: Android TTS 層 (引擎綁定/讀出/語言表) 搬咗去 TtsCenter
    // (拆 god object 第九刀)，呢度淨係留個 instance。
    private TtsCenter ttsCenter;

    // (TTS 嘴燈 bracket 搬咗去 LedCenter。)
    private LedCenter ledCenter;
    // 2026-09: 語意配對 + 裝置狀態搬咗去 SemanticCenter / DeviceStatus。
    private SemanticCenter semanticCenter;
    private DeviceStatus deviceStatus;
    // 2026-09: 小智包搬咗去 XiaozhiBridge (整包：HTTP API/mic/activation/vision/MCP/mute 鍵)，
    // 呢度淨係留個 instance (client/audio/config 由佢擁有)。
    private XiaozhiBridge xiaozhiBridge;
    // 2026-09: /api/alpha2/* dispatcher 成段搬咗去 ApiDispatcher (switch＋shaping)，
    // 呢度淨係留個 instance；跨域缺口經下面 Host override 調返嚟。
    private ApiDispatcher apiDispatcher;
    // 2026-09: 離線文法包搬咗去 GrammarCenter，呢度淨係留個 instance。
    private GrammarCenter grammarCenter;

    // -- XiaozhiBridge.HostState (宿主縫)：留低未搬嘅 TTS/sonar state，一行一個。 --
    @Override public boolean isRobotTtsSpeaking() { return robotTtsSpeaking; }
    @Override public long getLastSpeechStopAtMs() { return lastSpeechStopAtMs; }
    @Override public int getSonarDistanceCm() { return lastSonarDistanceCm; }
    @Override public int getSonarThreshold() { return sonarThresholdCm; }
    @Override public int getPirTriggeredState() { return lastPirTriggeredState; }
    @Override public void applySonarThreshold(int distanceCm) {
        sonarThresholdCm = distanceCm;
        sonarLedActive = false; // threshold changed - next frame decides fresh, don't carry over stale LED state
    }
    // 2026-09: 相機拍照層搬咗去 CameraApi，呢度淨係留個 instance。
    private CameraApi cameraApi;
    // 2026-09: Vosk 離線 ASR endpoint 層搬喷去 VoskApi (dispatcher Phase 1 第一刀)，嚺度淨係留個 instance.
    private VoskApi voskApi;

    // 2026-08 新增: RobotEventReceiver 沒有 constructor/field 拿到 outer
    // MainActivity instance (它一直只經 EventBus 靜態方法送 event, 不認識
    // MainActivity 本身), 但 sonar_obstacle 的 LED 指示邏輯 (applyObstacleIndicator,
    // sonarThresholdCm) 全部是 instance-level, 靠著 robot 這個 AIDL 連線。加一個
    // static instance reference, 在 onCreate/onDestroy set/clear, 讓
    // RobotEventReceiver 可以經 MainActivity.getSonarThresholdCm() /
    // MainActivity.onSonarDistanceReceived() 這兩個 static bridge 方法接回
    // instance 邏輯, 而不用將 RobotEventReceiver 的 constructor 簽名擴大 (這樣會
    // 影響到整個 registerDynamicReceiver() 的 new RobotEventReceiver() call 位)。
    private static volatile MainActivity sInstance;

    /** SONAR_DISTANCE_ACTION 觸發的 broadcast 未到之前, RobotEventReceiver 都要知道
     *  現在的門檻才計得到 "triggered"。沒 instance (例如 Activity 未起好/已destroy
     *  中間那段窗口) 就當沒門檻, 不會誤判 triggered。 */
    static int getSonarThresholdCm() {
        MainActivity m = sInstance;
        return m != null ? m.sonarThresholdCm : 30;
    }

    /** RobotEventReceiver 收到 SONAR_DISTANCE_ACTION 之後的入口, 負責將
     *  distanceCm/triggered 接到 applyObstacleIndicator() (5-mic + mouth LED
     *  雙路徑, 見該方法 javadoc)。和 handleChestObstacleFrame() 一樣, 只在
     *  triggered 狀態實際改變那一刻才重新驅動 LED, 避免每秒 ~1 幀的重複讀數不斷
     *  重送同一個 LED command。 */
    static void onSonarDistanceReceived(int distanceCm, boolean triggered) {
        MainActivity m = sInstance;
        if (m == null) {
            return;
        }
        m.lastSonarDistanceCm = distanceCm;
        if (triggered == m.sonarLedActive) {
            return;
        }
        m.sonarLedActive = triggered;
        m.ledCenter.applyObstacleIndicator(triggered);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        installCrashRestartHandler();

        // LedCenter/RingtoneCenter 喺呢度起（唔等 initRobot），因為下面
        // registerWifiLedReceiver() 即刻就要用。兩個都淨係要 Context/
        // mainHandler，無其他依賴，提早建構行為不變。
        ringtoneCenter = new RingtoneCenter(this);
        ledCenter = new LedCenter(this, mainHandler, ringtoneCenter);
        registerDynamicReceiver();
        ledCenter.registerWifiLedReceiver();
        registerGestureController();
        // pure-direct: 头顶 +/- pad 改由 HeadKeyPoller 直读 /dev/input/event0，
        // 旧 come.ubt.alpha2.gesture broadcast 已随 alpha2services 消失。
        // 仍 publish 同格式 EventBus "gesture" 事件，后续走既有 onGestureCode 管道。
        try { headKeyPoller.start(); } catch (Throwable t) { Log.w(TAG, "headKeyPoller start failed", t); }
        initRobot();
        // pure-direct：胸 /dev/ttyS1 + 头 /dev/ttyS3 + libhead_led.so JNI，
        // 机身已无 alpha2services，无 binder fallback，失败直接报错。
        localServices = new LocalAlpha2Services(this);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean direct = localServices.start();
                Log.i(TAG, "LocalAlpha2Services direct=" + direct + " (pure-direct, no alpha2services fallback)");
                // 用戶要求：開app自動做一次蹲下站起（伸展筋骨），只在直驅就緒先播。
                if (direct) {
                    if (actionDirect.playActionDirect(ActionDirect.STOP_RECOVERY_ACTION_ID)
                            != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                        Log.w(TAG, "startup squat not started: " + ubxPlayer.lastError());
                    }
                }
            }
        }, "LocalServicesInit").start();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { xiaozhiBridge.maybeAutoConnect("startup"); }
        }, 15000);
        iflytekMatcher = new IflytekSemanticMatcher(this);
        iflytekMatcherEn = new IflytekSemanticMatcherEn(this);
        // 2026-09: Vosk 熔斷 —— vosk-android minSdk 21，API 19 機（呢個 APK 要
        // 裝到 4.4）絕對唔可以掂 org.vosk.*（native/JNA 即炒）。19 機 vosk
        // 維持 null，所有 vosk/* endpoint 經 VoskApi.voskOrError() 回清晰錯誤。
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                vosk = new VoskController(this, iflytekMatcher, iflytekMatcherEn);
            } catch (Throwable e) {
                Log.w(TAG, "VoskController init failed", e);
            }
        } else {
            Log.i(TAG, "Vosk disabled: need API 21+, this device is API "
                    + android.os.Build.VERSION.SDK_INT);
        }
        // Android TTS 層喺 TtsCenter 建構 (vosk 之後起，等 listener 嘅
        // vosk pause/resume 有嘢掂)。null = 用機身目前預設引擎，同以前一樣。
        ttsCenter = new TtsCenter(this, vosk);
        ttsCenter.initAndroidTts(null);
        semanticCenter = new SemanticCenter(iflytekMatcher, iflytekMatcherEn, actionDirect, ttsCenter);
        deviceStatus = new DeviceStatus(this, mainHandler, actionDirect, ubxPlayer, ttsCenter);
        // sticky broadcast 註冊時機唔敏感；原 onCreate 開頭喰次 register 搬团度 (起好先叫得)。
        deviceStatus.registerBatteryReceiver();
        cameraApi = new CameraApi(this, cameraController, ringtoneCenter);
        // 小智包 (HTTP API/mic/activation/vision/MCP/mute 鍵開關)：collaborator 齊喺呢度起。
        // xiaozhiClient/xiaozhiAudioController/xiaozhiConfig 由佢擁有 (原 onCreate 頭段嗰兩次建構搬入 ctor)。
        // 起喺 voskApi 之前——voskStart() 後開搶 mic 要經佢。
        xiaozhiBridge = new XiaozhiBridge(this, mainHandler, actionDirect, audioCenter,
                robot, ttsCenter, vosk, cameraController, ledCenter, this);
        grammarCenter = new GrammarCenter(this, robot, xiaozhiBridge);
        // sticky broadcast，註冊即刻有現狀；原 onCreate 開頭嗰次 register 搬嚟呢度 (起好先叫得)。
        grammarCenter.registerConnectivityReceiver();
        voskApi = new VoskApi(vosk, xiaozhiBridge);
        // dispatcher 包晒上面全部 controller (+robot/audioPlayback/this 做 Host)。
        // 放最尾——要等齊所有 collaborator (上面 voskApi 最遲)。
        apiDispatcher = new ApiDispatcher(this, this, this, actionDirect, ubxApi, chestQuery,
                chestUpgrade, ttsCenter, voskApi, ledCenter, semanticCenter, deviceStatus,
                cameraApi, audioCenter, ringtoneCenter, audioPlaybackController, robot, grammarCenter);

        // Plain HTTP only. TLS/HTTPS was tried (self-signed cert) to make getUserMedia()
        // available for the walkie-talkie mic feature, but browsers on this device
        // repeatedly rejected new TLS connections after the very first page load with
        // "SSLHandshakeException: Handshake failed / certificate unknown" (see logcat
        // from 2017-01-01 session) - each new WebSocket/keep-alive connection re-runs
        // the TLS handshake and the self-signed cert's trust exception did not reliably
        // carry over, so the WebSocket feed (accel, uuid, wakeup, etc.) dropped
        // intermittently even though the HTTP API calls themselves succeeded. Rather
        // than fight browser cert-trust behavior, TLS support was removed outright
        // (2026-08: TlsSupport.java/SelfSignedCert.java deleted, HttpServer's TLS
        // constructor overload removed) - walkie-talkie (which needs a secure context)
        // stays permanently disabled in the UI (see app-mic.js) and everything else works
        // reliably over plain HTTP/WS.
        String ip = getWifiIp();

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
                // "/api/alpha2/..." goes to the original Alpha2RobotApi dispatch
                // (handleApi, unchanged below). "/api/system/..." is a small namespace
                // for things not tied to the robot SDK itself.
                if (path.startsWith("direct/")) {
                    return handleDirectApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("alpha2/")) {
                    return apiDispatcher.handleApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return handleSystemApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("xiaozhi/")) {
                    return xiaozhiBridge.handleXiaozhiApi(path.substring(8), query, method, body);
                }
                // Back-compat: requests with no backend prefix (older cached browser
                // tab) fall through to the Alpha2 dispatch.
                return apiDispatcher.handleApi(path, query, method, body);
            }
        }, new HttpServer.StreamHandler() {
            @Override
            public void handle(String path, Map<String, String> query, java.net.Socket socket) throws java.io.IOException {
                handleStream(path, query, socket);
            }
        }, new HttpServer.RawUploadHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, byte[] body) {
                return handleUpload(path, query, body);
            }
        });
        httpServer.start();
        String scheme = "http";

        // The on-device screen does NOT mirror the HTML control panel via WebView -
        // that path had unreliable CSS/JS rendering on this device's WebView build (blank/
        // broken layout, buttons stuck disabled). Per this class's original design intent,
        // the HTML panel at http://<robot-ip>:8888/ is the actual UI; the on-device
        // screen is just a native status readout telling the user where to point a browser.
        // 修正：之前 panelUrl/linkView 係 final 局部變量，轉 hotspot/WiFi 後永遠顯示舊 IP；現改為成員變量並隨網絡變化自動更新
        currentPanelUrl = scheme + "://" + ip + ":" + HttpServer.PORT + "/";
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setTextSize(16);
        titleView.setText("Open Alpha2\n\nOpen in a browser on the same network:");
        root.addView(titleView);

        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        int topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        linkRow.setPadding(0, topMargin, 0, topMargin);

        panelLinkView = new TextView(this);
        panelLinkView.setText(currentPanelUrl);
        panelLinkView.setTextSize(16);
        panelLinkView.setTextColor(Color.parseColor("#3b7dff"));
        panelLinkView.setPaintFlags(panelLinkView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        panelLinkView.setLayoutParams(linkParams);

        Button copyBtn = new Button(this);
        copyBtn.setText("Copy");
        View.OnClickListener copyAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String urlToCopy = currentPanelUrl != null ? currentPanelUrl : ("http://" + getWifiIp() + ":" + HttpServer.PORT + "/");
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Alpha2 panel URL", urlToCopy));
                    Toast.makeText(MainActivity.this, "Copied: " + urlToCopy, Toast.LENGTH_SHORT).show();
                }
            }
        };
        panelLinkView.setOnClickListener(copyAction);
        copyBtn.setOnClickListener(copyAction);

        linkRow.addView(panelLinkView);
        linkRow.addView(copyBtn);
        root.addView(linkRow);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);

        Log.i(TAG, "Open Alpha2 - reachable at " + scheme + "://" + ip
                + ":" + HttpServer.PORT + "/ from any browser on the same network");
        registerPanelUrlReceiver();

        // pure-direct: 「頭部降噪」預設常開，經 DirectHeadController 直發 /dev/ttyS3，
        // 不再經 robot.waitHeaderReady() / alpha2services binder。
        // 仍用獨立 background thread（localServices.start() 本身都係 async）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 等直驅串口就緒（最多 5s，每 100ms poll 一次）
                    for (int i = 0; i < 50; i++) {
                        try {
                            if (HardwareDirectManager.get(MainActivity.this).head().isAvailable()) break;
                        } catch (Exception ignore) {}
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                    boolean ok = HardwareDirectManager.get(MainActivity.this).head().setNoiseReduction(true);
                    if (!ok) Log.w(TAG, "HeadNoiseDefaultInit: direct send failed (head not ready?)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to enable default head noise reduction", e);
                }
            }
        }, "HeadNoiseDefaultInit").start();
    }

    private void registerDynamicReceiver() {
        dynamicReceiver = new RobotEventReceiver();
        IntentFilter filter = new IntentFilter();
        // 2026-08 更新: 反編譯 alpha2services_base 3.0.0.2 全個 APK, 搜晒所有
        // sendBroadcast() call site 逐個核對 —— "com.ubtechinc.key" 呢個 action
        // string 在這個韌體版本已經找不到任何 sendBroadcast 出處, 實際上是死
        // code。依然保留 filter + RobotEventReceiver 那個 case, 純粹做向後
        // 相容 (以防其他韌體/舊機用到這個 action), 但這台機器不會再觸發。
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.services.SPEECH_DIRECTION");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction(RobotWire.ALPHA_QR_CODE);
        filter.addAction(RobotWire.ALPHA_WIFI_RESULT);
        filter.addAction(RobotWire.ALPHA_BT_CONNECTION);
        // 2026-08 新增 (2個): 反編譯 alpha2services_base 3.0.0.2 整個 APK 找到的
        // sendBroadcast() 出處, 之前這個 App 完全沒有 register, 詳見各自的
        // RobotEventReceiver case comment。
        filter.addAction("com.ubtechinc.services.Action.ACTION_STOP");
        filter.addAction("com.ubtechinc.services.Action.ROBOT_INTERRUPTED");
        // 2026-08 新增: 實機 (firmware 1.1.1.14) 證實 sonar 讀數不會經由
        // IAlpha2SerialPortService.onListenSerialPortRcvData() 送達 - app 自己
        // registerSerialPortRcvListener() 只收到 config command 的 2-byte ack
        // "04 00"。CHEST_ACTION 這個 broadcast 也收得到, 但反編譯官方
        // alpha2demo.apk 之後證實它只是印機身內部 raw command byte 做 debug log
        // (getmCmd()), 不是真正的 sonar 讀數路徑。真正生效的是下面獨立的
        // SONAR_DISTANCE_ACTION - 保留 CHEST_ACTION filter 純粹做輔助 debug 用
        // (RobotEventReceiver 那個 case 依然會 dump 它的 extras, 對照兩條路徑
        // 的時序有用), 不再指望它是主要事件來源。
        filter.addAction(RobotWire.CHEST_ACTION);
        // 2026-08 新增: ⚠️ 未經真機驗證 (見 RobotEventReceiver 這個 case 的
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 反推出來的 PIR 通知
        // broadcast, 只有在 SecurityCameraUtil 監控開關開啟的時候才會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
        // 官方 alpha2demo.apk (firmware 1.1.1.14) 反編譯確認: sonar 讀數是經由這個
        // 獨立 broadcast 送出, extra 已經是 parse 好的 int, 不需要自己再解 raw
        // wire frame。見 RobotWire.SONAR_DISTANCE_ACTION 的 comment。
        filter.addAction(RobotWire.SONAR_DISTANCE_ACTION);
        // 2026-08 新增 (8個): 用來查「speech_SetMIC() 拿回 mic 會不會有 broadcast
        // 通知」這個問題, 反編譯 Alpha2Services-v1.1.7.3.20-5mic.apk 整個 APK 找到
        // 的 sendBroadcast() 出處 (speechmanager.d.*/AlphaMainSeviceImpl 這兩個
        // class), 之前這個 App 完全沒有 register。特意連語意未確定的也全部先
        // register, 經 mic_broadcast_debug event 轉送到 WebSocket log (見
        // RobotEventReceiver 這幾個 case comment) - 目的是收集實際 payload,
        // 看完再決定哪幾個和 mic ownership 真的有關、要不要正式做成獨立 event/
        // 更新 UI 指示燈, 在未驗證之前不假設這個名字看起來像什麼意思就是什麼意思。
        filter.addAction("com.ubtechinc.services.ABOUT_TTS");
        filter.addAction("com.ubtechinc.services.ALPHA_SOCKET_ASR_OK");
        filter.addAction("com.ubtechinc.services.SPEECH_ANGLE_5MIC");
        filter.addAction("com.ubtechinc.services.LED_ACTION");
        filter.addAction("com.ubtechinc.services.IFLY_OFFLINE_CMD");
        filter.addAction("com.ubtechinc.services.NUANCE_OFFLINE_CMD");
        filter.addAction("com.ubtechinc.services.POWER_SAVE");
        filter.addAction("com.ubtechinc.services.ALPHA_NOTIFY_POWER");
        registerReceiver(dynamicReceiver, filter);
    }

    /**
     * Reacts to the head touch-pad "gestures" broadcast via {@code come.ubt.alpha2.gesture}.
     *
     * These are NOT documented in the SDK (docs/sensors-and-events.md only lists the raw
     * `come.ubt.alpha2.gesture` action/extra name, not what values it carries) - the values
     * below were captured from a real robot's WebSocket event log:
     *
     *   "-" pad pressed  -> 23041 (0x5a01)      "-" pad released -> 23297 (0x5b01)
     *   "+" pad pressed  -> 23553 (0x5c01)      "+" pad released -> 23809 (0x5d01)
     *   both pressed     -> 24065 (0x5e01, high byte 94 decimal)   both released -> 24321 (0x5f01)
     *
     * Every value's low byte is 0x01; the high byte (0x5a-0x5f, 90-95) is a distinct,
     * sequential event code for each of the 6 press/release combinations - i.e. this
     * extra carries a compound (eventCode << 8 | 0x01) value here, not the plain
     * "direction" the field name suggests. Mapped to: "-"/"+" press-and-hold repeats
     * volume down/up every VOLUME_REPEAT_INTERVAL_MS until release; pressing both (high
     * byte 94, decimal) triggers a full stop-everything (action/speech/local music/
     * radio - see stopAllSpeechPlayback()/onGestureCode()'s 0x5e case), matching the
     * XiaoZhi panel's "⏹ 全部停止" button; releasing both does nothing extra.
     */
    /**
     * Installs a default uncaught-exception handler so any crash anywhere in this
     * process schedules a restart instead of leaving the robot's control panel dead
     * until someone physically walks over and re-launches the app.
     *
     * Approach: on an uncaught exception, use AlarmManager.setExact() (not just posting
     * a delayed Handler task - a crashing/dying process won't reliably run that) to fire
     * a fresh MainActivity launch ~1.5s from now, chain to whatever the previous default
     * handler was (so ADB/Play-style crash logging still sees the exception), then kill
     * this process outright. Restarting a *process* that's already in a broken state via
     * in-place recovery is unreliable; a full relaunch is the robust option here.
     *
     * (No SCHEDULE_EXACT_ALARM permission is needed for setExact() here: that's only
     * required starting targetSdkVersion 31, and this app targets 22.)
     */
    private void installCrashRestartHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final Context appContext = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                Log.e(TAG, "Uncaught exception - scheduling restart", throwable);
                Intent restartIntent = new Intent(appContext, MainActivity.class);
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        appContext, 0, restartIntent,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                            android.os.SystemClock.elapsedRealtime() + 1500, pendingIntent);
                }
            } catch (Exception schedulingFailure) {
                // If even scheduling the restart fails, fall through to the previous
                // handler / process death below rather than losing the crash entirely.
                Log.e(TAG, "Failed to schedule crash restart", schedulingFailure);
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void registerGestureController() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // HeadKeyPoller 已搬入 hardware-direct module：经 Listener 直连，
        // 不再绕 EventBus "gesture" 事件（旧 direction 解析一并删除）。
        // head_key/head_key_native 照旧转送 EventBus，供 WebSocket log 备查。
        headKeyPoller.setListener(new HeadKeyPoller.Listener() {
            @Override public void onGesture(int eventCode) {
                mainHandler.post(() -> onGestureCode(eventCode));
            }
            @Override public void onHeadKey(int code, int value) {
                EventBus.get().publish("head_key", "{\"code\":" + code + ",\"value\":" + value + "}");
            }
            @Override public void onHeadKeyNative(int code) {
                EventBus.get().publish("head_key_native", "{\"code\":" + code + "}");
            }
        });
    }

    private void onGestureCode(int code) {
        switch (code) {
            case 0x5a: // "-" pressed: start repeating volume-down
                ledCenter.setPadMinusHeld(true);
                ledCenter.padLedUpdate();
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                ledCenter.setPadMinusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5c: // "+" pressed: start repeating volume-up
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                ledCenter.setPadPlusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5e: // both pressed (raw gesture code 94, decimal) - 全部停止:
                       // 用戶要求將總停鍵的效果搬到這顆實體鍵上, 之前這裡只有
                       // action_StopAction(), 現在跟小智面板那顆「⏹ 全部停止」
                       // 按鈕 (xiaozhiStopAll(), 見 app-xiaozhi.js) 看齊, 一次
                       // 停止動作/小智說話/本地音樂/電台這四樣東西。
                ledCenter.setPadMinusHeld(true);
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                stopVolumeRepeat(); // in case one pad was already held down
                ringtoneCenter.playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                // pure-direct：一键全停（动作截停+蹲下站起回位，含拍头双 pad 触发），
                // 与 HTTP action/stop 同语义。旧 robot.action_* 已无服务承载。
                actionDirect.stopActionWithRecovery();
                stopAllSpeechPlayback();
                audioCenter.stopLocalMusicPlayback();
                audioCenter.stopRadioPlayback();
                break;
            case 0x5f: // both released: nothing further to do
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }

    // (Pad 燈成組搬咗去 LedCenter。)
    // (停止/快門提示音 + 共用播放器搬咗去 RingtoneCenter。)

    // (提示音/共用播放器搬咗去 RingtoneCenter。)
    // 2026-09: 本地音樂 + 電台播放中心搬咗去 AudioCenter (拆 god object 第六刀)——
    // 播放器/EQ/頻譜/filler 循環/搜尋/上載全部喺嗰邊，呢度淨係留個 instance。
    private AudioCenter audioCenter;

    // (播歌 filler 循環搬咗去 AudioCenter。)
    // (本地播歌/EQ 搬咗去 AudioCenter。)
    /** 2026-08 新增: 停止「小智說話/回覆」這一種播放 - 抽出來做共用 method, 供
     *  handleApi() 的 "speech/stop" HTTP endpoint 和 onGestureCode() 的 0x5e
     *  (雙鍵齊按, 也就是「94 鍵」) 一起使用。停止 Android TTS 和小智語音回覆
     *  的音訊 (XiaozhiAudioController, WebSocket 收 Opus frame -> 解碼 ->
     *  AudioTrack, 詳見 XiaozhiAudioController.onIncomingOpusFrame()/
     *  stopPlayback() 的 javadoc) - 互相獨立的播放管道, 停一條不會連帶讓另一條
     *  也停, 之前用戶回報「停不了小智說話」就是因為漏了 XiaozhiAudioController
     *  這條路。2026-09: 機身本地 TTS (Nuance/iflytek) 已隨 alpha2services 移除，
     *  無嘢要停，舊 robot.speech_StopTTS() call 拎走。 */
    private void stopAllSpeechPlayback() {
        lastSpeechStopAtMs = System.currentTimeMillis();
        robotTtsSpeaking = false; // 見 robotTtsSpeaking field javadoc - 手動/總停鍵停止時都要立即放行 mic enforcer
        ttsCenter.stop();
        // 2026-09: 手動全部停止都要 resume Vosk（上面 UtteranceProgressListener
        // 嘅 onDone 唔一定會嚟）。
        if (vosk != null) {
            try {
                vosk.setPaused(false);
            } catch (Throwable ignore) {
            }
        }
        xiaozhiBridge.stopSpeechPlayback();
        LedCenter.stopMouthLedForTts();
    }

    // (本地音樂停止/電台播放器搬咗去 AudioCenter。)
    // (查表快取搬咗去 RingtoneCenter，連上面成段 cursor 洩漏註解一齊。)
    /**
     * Starts (or restarts) a repeating volume step every VOLUME_REPEAT_INTERVAL_MS,
     * simulating press-and-hold behaviour on top of AudioManager's single-step API.
     *
     * FLAG_PLAY_SOUND makes Android play its own built-in volume-change sound on each
     * real step - the same sound a hardware volume key produces - so there's no need
     * for a separately synthesized beep here; it only actually sounds on ticks where
     * the stream truly moved (Android itself no-ops silently once at min/max).
     */
    private void startVolumeRepeat(boolean up) {
        stopVolumeRepeat();
        volumeRepeater = new Runnable() {
            @Override
            public void run() {
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                }
                mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
            }
        };
        mainHandler.post(volumeRepeater);
    }

    private void stopVolumeRepeat() {
        if (volumeRepeater != null) {
            mainHandler.removeCallbacks(volumeRepeater);
            volumeRepeater = null;
        }
    }

    /**
     * 2026-08-25: WiFi 狀態 → wifi 指示燈 (2026-09 三態: wifi 熄=熄燈,
     * wifi 開但未連=紅 13, 連上 AP=藍 12)。註冊當下立即檢查一次現狀,
     * 處理「app 開啟之前已經連上/斷線」的情況。
     */
    // (wifi 燈成組搬咗去 LedCenter：register/apply 三式/burst。)

    private void initRobot() {
        // 2026-09: 脫離 Alpha2OpenSdk —— robot 係 RobotStub 純本地 no-op facade
        // (機身無 alpha2services, 舊 binder 調用全部誠實失敗, 見 RobotStub)。
        // 舊匿名子類的三個 onListenSerialPort* AIDL 回調在 pure-direct 下永不
        // 觸發, 已經成段刪除; chest/head 回幀只走下面的 wireDirectFrameListeners()。
        // 舊 if(false) initSpeechApi 整塊 (約 100 行回調) 一併刪除。
        robot = new RobotStub(this);
        chestQuery = new ChestQuery(this, robot);
        actionDirect = new ActionDirect(this, ubxPlayer);
        ubxApi = new UbxApi(this, ubxPlayer, actionDirect);
        chestUpgrade = new ChestUpgrade(this, chestQuery);
        // (ringtoneCenter/ledCenter 已喺 onCreate 頭段起好，見上面。)
        audioCenter = new AudioCenter(this, ubxPlayer, actionDirect, mainHandler);
        EventBus.get().publish("authorize", "{\"code\":1,\"info\":\"have offline authority\"}");
        Log.i(TAG, "Authorize result: 1 have offline authority");

        // pure-direct: 机身已无 com.ubtechinc.alpha2services，不再做任何 bindService。
        // 胸/头串口帧由 HardwareDirectManager 经 DirectSerialPort 直接推送，
        // 见 wireDirectFrameListeners()。
        wireDirectFrameListeners();

        // 2026-09: 脫離 Alpha2OpenSdk —— 舊 binder initSpeechApi 整塊
        // （約 100 行 asr_result/tts_end/speech_ready 回調，包喺 if(false))
        // 已經成段刪除：機身無 alpha2services，永遠唔會執行。

        ubxApi.registerWakeupDirectionListener();
        registerChestMuteKeyTestListener();
        registerAlpha2PirAlertListener();
    }

    // -- pure-direct frame wiring -----------------------------------------------
    //
    // 胸/头 MCU 回帧經 HardwareDirectManager 直收：DirectSerialPort 送出的是完整
    // wire 帧（F8 8F ... ED，回复含 00 00 头），先經 stripSerialFrame() 剥到
    // payload 层（bytes[0] 即 cmd）再走 latch/EventBus 逻辑。对外发布的
    // chest_rcv/head_rcv 事件用完整帧 hex（信息更多，前端事件 Log 照常显示）。
    private void wireDirectFrameListeners() {
        try {
            HardwareDirectManager dm = HardwareDirectManager.get(this);
            dm.chest().setFrameListener(new com.ubtechinc.alpha.hardware.DirectSerialPort.OnFrameListener() {
                @Override public void onFrame(byte[] frame) { onDirectChestFrame(frame); }
            });
            dm.head().setFrameListener(new com.ubtechinc.alpha.hardware.DirectSerialPort.OnFrameListener() {
                @Override public void onFrame(byte[] frame) { onDirectHeadFrame(frame); }
            });
            Log.i(TAG, "wireDirectFrameListeners: direct chest/head listeners attached (pure-direct)");
        } catch (Throwable t) {
            Log.w(TAG, "wireDirectFrameListeners failed", t);
        }
    }

    /**
     * 把完整 wire 帧剥到 payload 层（bytes[0] 即 cmd，与旧 AIDL 回调格式一致）。
     * 兼容长式（F8 8F LEN 00 00 CMD PAYLOAD SUM ED，MCU 回复用此式）和短式
     * （F8 8F LEN CMD PAYLOAD SUM ED，本 app 发出的式样）；找不到帧头返回 null。
     */
    static byte[] stripSerialFrame(byte[] frame) {
        if (frame == null) return null;
        int n = frame.length;
        for (int i = 0; i + 5 < n; i++) {
            if ((frame[i] & 0xFF) == 0xF8 && (frame[i + 1] & 0xFF) == 0x8F) {
                int lenByte = frame[i + 2] & 0xFF;
                // 长式：[i+3],[i+4] 为 00 00，cmd 在 i+5
                if (frame[i + 3] == 0 && frame[i + 4] == 0) {
                    int pl = lenByte - 7;
                    if (pl < 0) pl = 0;
                    if (i + 6 + pl > n) pl = Math.max(0, n - (i + 6));
                    byte[] out = new byte[1 + pl];
                    out[0] = frame[i + 5];
                    if (pl > 0) System.arraycopy(frame, i + 6, out, 1, pl);
                    return out;
                }
                // 短式：cmd 在 i+3
                int pl = lenByte - 1;
                if (pl < 0) pl = 0;
                if (i + 4 + pl > n) pl = Math.max(0, n - (i + 4));
                byte[] out = new byte[1 + pl];
                out[0] = frame[i + 3];
                if (pl > 0) System.arraycopy(frame, i + 4, out, 1, pl);
                return out;
            }
        }
        return null;
    }

    private void onDirectHeadFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        EventBus.get().publish("head_rcv", "{\"hex\":\"" + toHex(frame, frame.length) + "\"}");
        // 2026-09: 頭版本 latch 已刪 (queryHeaderFirmwareVersion 無 caller) -
        // 頭幀淨係 publish，不再做任何 latch。
    }

    private void onDirectChestFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        byte[] payload = stripSerialFrame(frame);
        if (payload == null) payload = frame;
        // 2026-09: 心跳靜音 - cmd 0x8B(-117, ~1Hz telemetry) 同 0x8D(-115, 5s
        // heartbeat) 唔再 publish chest_rcv 上 WebSocket (Event Log 洗版, 見
        // logcat 定量: 5 分鐘 361 幀幾乎全部係呢兩種)。其他 cmd (UUID 回覆 0x37、
        // PIR 0x93 等) 照舊發布; -109 PIR 采集/轉發邏輯喺下面完全唔郁。
        // logcat 嘅 DirectSerialPort RX hex 照樣保留, 要睇 raw 幀去嗰度睇。
        boolean noisyHeartbeat = payload.length >= 1
                && (payload[0] == (byte) 0x8B || payload[0] == (byte) 0x8D);
        if (!noisyHeartbeat) {
            EventBus.get().publish("chest_rcv", "{\"hex\":\"" + toHex(frame, frame.length) + "\"}");
        }
        int plen = payload.length;
        handleChestObstacleFrame(payload, plen);
        // pure-direct: 心口 mute 键 (-111/0x91) 与 PIR (-109/0x93) 直接从串口帧来。
        // 旧路径（CHEST_ACTION broadcast 由 alpha2services 转发）已随 APK 移除而消失，
        // 这里按旧 RobotEventReceiver 同一套语义直推：sub-value 1=按下/进入，0=放开/离开。
        if (plen >= 1) {
            if (payload[0] == (byte) -111) {
                boolean pressed = plen < 2 || payload[1] == 1;
                EventBus.get().publish("chest_mute_key", "{\"pressed\":" + pressed + "}");
                try { onMuteKeyEvent(pressed); } catch (Throwable t) { Log.w(TAG, "onMuteKeyEvent failed", t); }
            } else if (payload[0] == (byte) -109) {
                boolean pirTriggered = plen < 2 || payload[1] == 1;
                EventBus.get().publish("alpha2_pir_state", "{\"triggered\":" + pirTriggered + "}");
                try { onPirStateReceived(pirTriggered); } catch (Throwable t) { Log.w(TAG, "onPirStateReceived failed", t); }
            }
        }
        // 优先处理升级 ACK (48/49/50)，交給 ChestUpgrade 認領。
        if (chestUpgrade.onAckFrame(payload, plen)) return;
        // 版本/UUID 回覆 latch 交給 ChestQuery 認領 (升級 ACK 上面已優先處理)。
        if (chestQuery.onFrame(frame, payload, plen)) return;
    }

    // -- pure-direct 状态/发送 helpers（取代 robot.waitChestReady/isChestReady 等 binder 语义） --
    private boolean directChestReady() {
        try { return HardwareDirectManager.get(this).chest().isAvailable(); }
        catch (Exception e) { return false; }
    }

    private boolean directHeaderReady() {
        try { return HardwareDirectManager.get(this).head().isAvailable(); }
        catch (Exception e) { return false; }
    }

    static UbxErrorCode.API_ERROR_CODE directCode(boolean ok) {
        return ok ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
                : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    // 2026-09 刪除: Local_Result 解析 (LOCAL_RESULT_PREFIX/fieldBetween) -
    // 唯一 caller (舊 binder onServerCallBack) 已隨脫鉤刪除。

    // -- iFlytek 語意配對: 完全取代悠聊 APK (com.ubtech.iflytekmix) -------------------
    //
    // 悠聊 APK 反編譯還原出來的完整 pipeline (見對話 history) 是:
    //   機身 ASR 辨識完一句話 -> JsonResultParse 解析成 operation+slots
    //     -> RobotActionBusiness.startBusiness(): TTS(200ms sleep)Action
    // OpenAlpha2 已經有自己的 robot.speech_startTTS()/robot.action_PlayActionName(),
    // 不需要悠聊那層 RobotHandle wrapper, 只需要搬「文字 -> operation/答案/動作」
    // 這層語意配對 (IflytekSemanticMatcher, 由悠聊 assets/local_semantic 那 850 條
    // 問法還原) 以及這個時序。
    //
    // 掛在哪裡: 不再掛在 onServerCallBack() (見上面 2026-08 移除那個 comment) - 前端
    // 統一經由 speech/iflytek_simulate 觸發, 讓所有輸入方法 (真人說話/打字模擬) 都走
    // 同一條路, 避免重複 TTS。中英文由 looksChinese() 判斷, 只看輸入文字內容,
    // 不理會 ASR engine 目前設定的是哪種語言。

    // (語意配對成組搬咗去 SemanticCenter：looksChinese/toZhResult/handle、
    // IFLYTEK delay 常數；單參數 overload 零調用，一併刪除。)

    // (喚醒轉頭成組搬咗去 UbxApi.registerWakeupDirectionListener()。)

    // -- 心口 mute 鍵 (-111) 測試: 撳一下紫燈長開, 再撳一下熄燈 ------------------------
    // 2026-08 新增: 純粹用來目視確認 RobotEventReceiver 那個 CHEST_ACTION case 有沒有
    // 真的收到胸口 mute 鍵 (chest cmd = -111) 的 broadcast - 這不是最終功能,
    // 純粹一個「有沒有反應」的測試訊號 (見 RobotEventReceiver 那個 case 的 comment)。
    // 官方 firmware 這顆鍵本身完全沒有連任何 LED, 這裡的紫燈完全是這個專案自己加的,
    // 和 sonar obstacle 用的是同一個 setHeadEyeLedLong(5, 9) helper (5=紫,
    // 9=最光, 見 applyObstacleIndicator() 個 comment)。
    // (2026-09: chestMuteKeyLedOn field 已刪 - 純寫入、從無讀取。注意同
    // setChestMuteLed() 用的 chestMuteLedOn 係兩個 field，嗰個仲用緊。)

    private void registerChestMuteKeyTestListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains("\"type\":\"chest_mute_key\"")) {
                    return;
                }
                // 2026-08-25: 之前這裡是紫燈測試 (head/eye 5-mic LED toggle), 現在
                // 換成真正的 mute 燈 - 實機掃描確認 chest serial cmd=68 (0x44):
                // data [01]=點亮, [00]=熄滅 (wire frame F8 8F 08 00 00 44 <d> <sum> ED,
                // sum=(8+0x44+d)&0xFF)。onMuteKeyEvent(pressed) 由 RobotEventReceiver
                // 在收到 -111 broadcast 的當下直接呼叫 (按下=true/放開=false),
                // 這個 listener 只負責轉發事件給前端 Event Log。
            }
        });
    }

    /** RobotEventReceiver 收到胸口 mute 鍵 (-111) broadcast 時直接呼叫。
     *  pressed=true (按下) 就 toggle mute LED; pressed=false (放開) 不理。 */
    public static void onMuteKeyEvent(final boolean pressed) {
        if (!pressed) {
            return;
        }
        final MainActivity m = sInstance;
        if (m == null) {
            return;
        }
        m.xiaozhiBridge.toggleChestMuteLed();
    }

    // (PIR 警示旗標 + 反應搬咗去 LedCenter；下面淨返 EventBus 接線同開關入口。)

    private void registerAlpha2PirAlertListener() {
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
                        ledCenter.applyAlpha2PirLedAndSound(triggered);
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

    // (PIR 提示音搬咗去 RingtoneCenter.playPirAlertCue()。)
    // (TTS 語言表/legacy fallback/iso3/引擎表/init/讀出成組搬咗去 TtsCenter。)
    /** 接住 TtsCenter.checkTtsDataSyncLegacy() 發出的 ACTION_CHECK_TTS_DATA 結果。只
     *  處理這個 app 自己認得的 requestCode, 其他一律交回給 super (雖然目前這個
     *  app 沒有其他地方用 startActivityForResult(), 但這是基本禮貌, 不應該
     *  吞晒所有 requestCode)。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TtsCenter.TTS_DATA_CHECK_REQUEST_CODE) {
            ttsCenter.onTtsDataResult(data);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        stopVolumeRepeat();
        ubxPlayer.stopVoice();
        stopMicHoldEnforcer();
        micHeldByApp = false;
        deviceStatus.setAccelerometerEnabled(false);
        ttsCenter.shutdown();
        headKeyPoller.setListener(null);
        try { headKeyPoller.stop(); } catch (Throwable ignored) {}
        if (localServices != null) {
            try { localServices.stop(); } catch (Throwable ignored) {}
        }
        xiaozhiBridge.shutdown();
        if (httpServer != null) {
            httpServer.stop();
        }
        if (robot != null) {
            robot.releaseApi();
        }
        if (vosk != null) {
            try {
                vosk.shutdown();
            } catch (Throwable ignore) {
            }
        }
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        deviceStatus.unregisterBatteryReceiver();
        ledCenter.unregisterWifiLedReceiver();
        if (panelUrlReceiver != null) {
            try {
                unregisterReceiver(panelUrlReceiver);
                panelUrlReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        grammarCenter.unregisterConnectivityReceiver();
        // 2026-09: offline watchdog thread 已成組移除，無嘢要 quit。
        cameraController.shutdown();
        audioController.shutdown();
        audioPlaybackController.shutdown();
        ledCenter.shutdown();
        ringtoneCenter.stopRingtonePlayback();
        // 2026-08 新增: 之前這裡沒有呼叫 stopLocalMusicPlayback()/stopRadioPlayback() -
        // onDestroy() 就算執行了也不會釋放正在播放的 currentMusicPlayer/currentRadioPlayer,
        // 一直以來都是個 leak (MediaPlayer native resource 沒有 release())。加入
        // Equalizer (musicEqualizer, 跟隨 currentMusicPlayer 的生命週期) 之後這個
        // 缺口更需要補上: Equalizer 綁定的 audio session 如果連 app 結束都不釋放,
        // 留下的 native effect engine 資源就更難追蹤。沿用 stopRingtonePlayback()
        // 一樣的做法, 在這裡一併全部停止。
        audioCenter.stopLocalMusicPlayback();
        audioCenter.stopRadioPlayback();
    }

    private String getWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ipInt = wm.getConnectionInfo().getIpAddress();
            String wifiIp = Formatter.formatIpAddress(ipInt);
            if (wifiIp != null && !wifiIp.equals("0.0.0.0") && !wifiIp.isEmpty()) {
                return wifiIp;
            }
            // 热点 AP 模式或未連接作 STA 時，WifiManager 回 0.0.0.0；改列舉網卡找 site-local
            try {
                java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    java.net.NetworkInterface intf = en.nextElement();
                    java.util.Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String host = addr.getHostAddress();
                            if (host != null && (host.startsWith("192.168.") || host.startsWith("10."))) {
                                return host;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return wifiIp != null ? wifiIp : "<device-ip>";
        } catch (Exception e) {
            return "<device-ip>";
        }
    }

    private void updatePanelUrlDisplay() {
        final String newIp = getWifiIp();
        final String newUrl = "http://" + newIp + ":" + HttpServer.PORT + "/";
        currentPanelUrl = newUrl;
        if (panelLinkView != null) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    panelLinkView.setText(newUrl);
                }
            });
        }
        Log.i(TAG, "Panel URL updated to " + newUrl + " (ip=" + newIp + ")");
    }

    private void registerPanelUrlReceiver() {
        panelUrlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)
                        || WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)
                        || android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                        || "android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {
                    // 延時 500ms 等 DHCP 完成取到新 IP
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() { updatePanelUrlDisplay(); }
                    }, 700);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        registerReceiver(panelUrlReceiver, filter);
    }

    // -- API dispatch ----------------------------------------------------------------

    /**
     * Routes "/api/<name>" calls to the matching Alpha2RobotApi method. Runs on an
     * HttpServer worker thread (not the main thread) - every SDK call used here is safe
     * to invoke off the main thread (the *ServiceUtil classes only marshal Binder calls),
     * matching how the SDK's own AGENTS.md describes bind/call safety.
     */
    /**
     * Small namespace ("/api/system/...") for things not tied to the robot AIDL
     * surface itself.
     */
    private HttpServer.ApiResponse handleSystemApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            // 一野搜齊機器資料（lynx 年代 sys/* 七連發的 pure-direct 版，一個回包齊晒，
            // 慢 query 各 1.5s 上限）。電池版本字串本機胸固件無此命令，如實缺席；
            // 電量/充電走 Android 系統廣播。
            case "discover": {
                String appVer = "?";
                try {
                    appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                String chestFw;
                try {
                    chestFw = chestQuery.queryFirmwareVersion(1500);
                } catch (Exception e) {
                    chestFw = null;
                }
                String chestUuid;
                try {
                    chestUuid = chestQuery.queryRobotUuid(1500);
                } catch (Exception e) {
                    chestUuid = null;
                }
                int[] pose = ubxPlayer.pose();
                StringBuilder sb = new StringBuilder("{\"ok\":true,");
                sb.append("\"app\":{\"package\":\"").append(jsonSafe(getPackageName())).append("\",")
                        .append("\"version\":\"").append(jsonSafe(appVer)).append("\",")
                        .append("\"panel\":\"http://").append(jsonSafe(getWifiIp())).append(":")
                        .append(HttpServer.PORT).append("/\"},");
                sb.append("\"robot\":{\"chestFw\":").append(chestFw != null ? "\"" + jsonSafe(chestFw) + "\"" : "null").append(",")
                        .append("\"chestUuid\":").append(chestUuid != null ? "\"" + jsonSafe(chestUuid) + "\"" : "null").append(",")
                        .append("\"chestAvailable\":").append(directChestReady()).append(",")
                        .append("\"headerAvailable\":").append(directHeaderReady()).append("},");
                sb.append("\"power\":{\"level\":").append(deviceStatus.getBatteryLevel()).append(",")
                        .append("\"scale\":").append(deviceStatus.getBatteryScale()).append(",")
                        .append("\"charging\":").append(deviceStatus.isBatteryCharging()).append(",")
                        .append("\"status\":\"").append(jsonSafe(deviceStatus.getBatteryStatus())).append("\"},");
                sb.append("\"sensors\":{\"sonarCm\":").append(lastSonarDistanceCm).append(",")
                        .append("\"sonarThresholdCm\":").append(sonarThresholdCm).append(",")
                        .append("\"pir\":").append(lastPirTriggeredState).append("},");
                sb.append("\"servo\":{\"poseKnown\":").append(pose != null);
                if (pose != null) {
                    sb.append(",\"angles\":[");
                    for (int i = 0; i < 20; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(pose[i]);
                    }
                    sb.append("]");
                }
                sb.append("}}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            // ---------------- 本地音樂播放 ----------------
            // "/api/system/music/..." - 播放機身 SD 卡裡面 (/sdcard/Music 等) 已有的
            // 音樂檔, 經由 MusicController (standard android.media.MediaPlayer,
            // STREAM_MUSIC 由機器人喇叭輸出) 播放, 和 AIDL 機器人 API 完全無關,
            // 所以放在 system 這個 namespace 底下, 和 camera/audio-testtone 那類
            // 純硬體功能看齊。

            case "music/list": {
                java.util.List<MusicController.Track> tracks = musicController.listTracks();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"tracks\":[");
                for (int i = 0; i < tracks.size(); i++) {
                    if (i > 0) sb.append(",");
                    MusicController.Track t = tracks.get(i);
                    sb.append("{\"path\":\"").append(jsonSafe(t.path)).append("\",")
                      .append("\"name\":\"").append(jsonSafe(t.name)).append("\",")
                      .append("\"sizeBytes\":").append(t.sizeBytes).append("}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "music/play": {
                String p = ApiValidator.require(query, "path");
                String err = musicController.play(p);
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/pause": {
                String err = musicController.pause();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/resume": {
                String err = musicController.resume();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/stop": {
                String err = musicController.stop();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/seek": {
                int ms = ApiValidator.requireInt(query, "ms");
                String err = musicController.seekTo(ms);
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/volume": {
                int pct = ApiValidator.requireVolumePercent(query, "percent");
                String err = musicController.setVolume(pct);
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/status": {
                MusicController.Status s = musicController.status();
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"hasTrack\":" + s.hasTrack + ","
                        + "\"playing\":" + s.playing + ","
                        + "\"prepared\":" + s.prepared + ","
                        + "\"path\":" + (s.path != null ? "\"" + jsonSafe(s.path) + "\"" : "null") + ","
                        + "\"positionMs\":" + s.positionMs + ","
                        + "\"durationMs\":" + s.durationMs + "}");
            }

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown system endpoint: " + path + "\"}");
        }
    }

    private HttpServer.ApiResponse handleDirectApi(String path, Map<String, String> query, String method, String body) {
        if (localServices == null) {
            return HttpServer.ApiResponse.error("direct not initialized");
        }
        switch (path) {
            case "status": {
                boolean direct = localServices.isDirectActive();
                boolean chest = false, head = false;
                try { chest = localServices.isDirectActive() && com.ubtechinc.alpha.hardware.HardwareDirectManager.get(this).chest().isAvailable(); } catch (Exception ignore) {}
                try { head = com.ubtechinc.alpha.hardware.HardwareDirectManager.get(this).head().isAvailable(); } catch (Exception ignore) {}
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"direct\":" + direct + ",\"chest\":" + chest + ",\"head\":" + head + "}");
            }
            case "servo/one": {
                int id = ApiValidator.requireIntRange(query, "id", 1, 20);
                int angle = ApiValidator.requireInt(query, "angle");
                int time = ApiValidator.optionalInt(query, "time", 500);
                // cmd05 在本机固件有 ACK 无动作，改走 cmd03 全帧（servoSendOne 内处理）。
                return ubxApi.servoSendOne(id, angle, time);
            }
            case "servo/all": {
                int[] arr = ApiValidator.requireAngles20(query);
                for (int i = 0; i < 20; i++) arr[i] &= 0xFF;
                int time = ApiValidator.optionalInt(query, "time", 500);
                // setAllServos 内部已转 cmd03（cmd52 有 ACK 无动作）。
                boolean sent = HardwareDirectManager.get(this).chest().setAllServos(arr, (short) time);
                if (!sent) return HttpServer.ApiResponse.error("direct not ready");
                ubxPlayer.notePose(arr);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "sonar/config": {
                int cm = ApiValidator.requireInt(query, "distance");
                boolean ok = com.ubtechinc.alpha.hardware.HardwareDirectManager.get(this).chest().configureSonar(cm);
                return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
            }
            case "led/head": {
                int color = ApiValidator.optionalInt(query, "color", 3);
                Integer modeOpt = ApiValidator.optionalInteger(query, "mode");
                int mode = modeOpt != null ? modeOpt.intValue() : 0;
                boolean ok = localServices.ledHead(color);
                // also try direct with mode
                if (modeOpt != null) ok = com.ubtechinc.alpha.hardware.DirectLedController.setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, mode);
                return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
            }
            case "led/off": {
                boolean ok = localServices.ledOff();
                return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
            }
            case "led/mouth": {
                int sp = ApiValidator.optionalInt(query, "breathe", 500);
                boolean ok = localServices.ledMouthBreathe(sp);
                return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
            }
            // Ubx 直播（与 /api/alpha2/ubx/* 同 helper；抢占式：播新自动停旧）。
            case "ubx/list":
                return ubxApi.ubxListResponse();
            case "ubx/play":
                return ubxApi.ubxPlayResponse(ApiValidator.optionalNullable(query, "name"),
                        ApiValidator.optionalNullable(query, "path"));
            case "ubx/speed":
                return ubxApi.ubxSpeedResponse(ApiValidator.require(query, "value"));
            case "ubx/stop":
                return ubxApi.ubxStopResponse();
            case "ubx/status":
                return ubxApi.ubxStatusResponse();
            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown direct endpoint: " + path + "\"}");
        }
    }

    // ---------------- 小智 (XiaoZhi) AI 對話 ----------------
    //
    // "/api/xiaozhi/..." namespace - AI對話 doesn't belong to the robot's own AIDL
    // surface. See XiaozhiClient's class javadoc for the overall protocol/phase-1-scope
    // explanation.

    // (xiaozhi_actions catalog + resolveActionId 搬咗去 ActionDirect，MCP 經嗰邊用。)

    /** Radio Browser (radio-browser.info) 的其中一個 API 主機 - 官方文件建議客戶端
     *  對 "all.api.radio-browser.info" 做 DNS 解析再從多個鏡像之間挑選, 但這台機器沒有
     *  DNS SRV/多鏡像 failover 的需求 (一台家用機器人, 不是高流量服務), 直接用
     *  官方文件範例裡出現的 de1 這個固定主機就已經足夠, 保持程式碼簡單。 */
    // (電台搜尋/比對搬咗去 AudioCenter。)
    // (隨機動作池搬咗去 ActionDirect；triggerRandomFillerAction() 搬咗去 AudioCenter。)
    // 2026-09: MCP 開關 helper (isMcpEnabled/getMcpDisabledToolNames/
    // isMcpToolEnabled) 搬咗去 XiaozhiConfig，bridge 經 xiaozhiConfig.* 用。
    /** Reads an InputStream fully into a UTF-8 string - mirrors XiaozhiOtaClient's own
     *  readFully() (same need, this class just doesn't share that one since it's
     *  private there). Used by xiaozhiVisionExplainRequest()'s response handling. */
    static String readFully(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // -- handleApi 缺口 (TTS/mic/grammar core 未搬)：dispatcher 經 Host 調返嚟，
    // core 搬埋嗰陣跟埋走。 --
    @Override public HttpServer.ApiResponse handleSpeechTts(Map<String, String> query) {
        String text = ApiValidator.require(query, "text");
        String engine = ApiValidator.requireSpeechEngine(query);
        if ("android".equals(engine)) {
            String ttsErr = ttsCenter.speakPanelTts(text, ApiValidator.optional(query, "lang", ""));
            if (ttsErr != null) return HttpServer.ApiResponse.error(ttsErr);
            return HttpServer.ApiResponse.ok("{\"ok\":true}");
        }
        String voice = "iflytek".equals(engine) ? ApiValidator.optionalNullable(query, "voice") : null; // may be null
        String lang = "iflytek".equals(engine) ? "zh_cn" : "en_us"; // no language picker; engine implies it
        // See STOP_TO_TTS_MIN_GAP_MS above: if speech/stop just ran, give the
        // robot side's async audio teardown a minimum window to finish before
        // starting a new AIDL TTS session, to avoid crashing the Nuance TTS
        // session. Runs on this HTTP worker thread only (newCachedThreadPool),
        // so it never blocks other in-flight requests.
        long sinceStopMs = System.currentTimeMillis() - lastSpeechStopAtMs;
        if (sinceStopMs >= 0 && sinceStopMs < STOP_TO_TTS_MIN_GAP_MS) {
            try {
                Thread.sleep(STOP_TO_TTS_MIN_GAP_MS - sinceStopMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LedCenter.startMouthLedForTts();
        UbxErrorCode.API_ERROR_CODE res = robot.speech_startTTS(lang, text, voice);
        if (!isOk(res)) {
            // speech_startTTS failed synchronously - onServerPlayEnd will never
            // fire for this attempt, so nothing will turn the mouth LED back off
            // unless we do it here.
            LedCenter.stopMouthLedForTts();
        } else {
            // 見 robotTtsSpeaking field javadoc - 觸發成功先算「開始
            // 播緊」, onServerPlayEnd 會揭返做 false。
            robotTtsSpeaking = true;
        }
        return codeResponse(res);
    }
    @Override public HttpServer.ApiResponse handleSpeechStop() {
        stopAllSpeechPlayback();
        return codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);
    }
    @Override public HttpServer.ApiResponse handleSetMic(Map<String, String> query) {
        boolean wake = ApiValidator.requireBoolean(query, "wake");
        robot.speech_SetMIC(wake);
        // 記住這個狀態, 讓 handleMicStream() 斷線時知道用戶是否透過 TTS
        // tab 主動要求長期持有 mic - 見 micHeldByApp 的 field javadoc。
        micHeldByApp = wake;
        // 用戶手動交還給機器人 (wake=false) 就自動關閉「持續搶佔 mic」,
        // 不然 enforcer 兩秒之後又會把 mic 搶回來, 用戶的「交還」動作
        // 會看起來像沒效果一樣, 很令人困惑。
        if (!wake && micHoldEnforced) {
            stopMicHoldEnforcer();
        }
        EventBus.get().publish("mic_state",
                "{\"held\":" + micHeldByApp + ",\"keepHeld\":" + micHoldEnforced + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"held\":" + micHeldByApp
                + ",\"keepHeld\":" + micHoldEnforced + "}");
    }
    @Override public HttpServer.ApiResponse handleSetMicKeepHeld(Map<String, String> query) {
        boolean keep = ApiValidator.requireBoolean(query, "keep");
        if (keep) {
            startMicHoldEnforcer();
        } else {
            stopMicHoldEnforcer();
        }
        EventBus.get().publish("mic_state",
                "{\"held\":" + micHeldByApp + ",\"keepHeld\":" + micHoldEnforced + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"held\":" + micHeldByApp
                + ",\"keepHeld\":" + micHoldEnforced + "}");
    }

    // -- Camera streaming (MJPEG over "/stream/camera") -------------------------------

    private static final String MJPEG_BOUNDARY = "alpha2testpanelframe";

    /**
     * Serves the live camera feed as "multipart/x-mixed-replace" MJPEG - the format
     * every browser's plain &lt;img src="..."&gt; already knows how to render as a live
     * video-like feed with zero client-side JS, which is why this is a stream/ HTTP
     * route rather than a WebSocket: an &lt;img&gt; tag can't speak WebSocket, but it can
     * point straight at a URL that never stops responding.
     *
     * Runs on an HttpServer worker thread and blocks for as long as the client stays
     * connected, same as WebSocketServer.Connection.readLoop() does for "/ws" - both
     * rely on the pool's cached-thread-per-connection model rather than needing NIO.
     */
    /** Handles POST /upload/audio: raw PCM bytes (16kHz mono 16-bit, matching
     *  AudioPlaybackController's format - see AudioPlaybackController.SAMPLE_RATE_HZ
     *  and app-mic.js's TALK_TARGET_SAMPLE_RATE; 2026-08 改返 16kHz - 當初落 8kHz
     *  只是為了同步已經永久停用的 walkie-talkie, 這個理由現在不存在) from the
     *  browser's mic, queued for playback.
     *  Playback must already be running (audio/play/start) - this does not implicitly
     *  start it, so a stray upload after the user has stopped talking doesn't
     *  re-open the speaker session on its own. */
    private HttpServer.ApiResponse handleUpload(String path, Map<String, String> query, byte[] body) {
        if ("audio".equals(path)) {
            audioPlaybackController.enqueuePcm(body);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bytes\":" + body.length + "}");
        }
        if ("music".equals(path)) {
            return audioCenter.handleMusicUpload(query, body);
        }
        if ("chest".equals(path)) {
            return handleChestUpload(query, body);
        }
        return HttpServer.ApiResponse.error("Unknown upload path: " + path);
    }

    /** 胸板固件上載 - 接收 256KB 的 ALPHA2Q-CHEST-*.bin，寫入 /sdcard/AlphaII_CHEST_kernel.bin */
    private HttpServer.ApiResponse handleChestUpload(Map<String, String> query, byte[] body) {
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.error("empty file body");
        }
        if (body.length != 256 * 1024) {
            // 仍允許寫入，但提示大小不正確
            android.util.Log.w(TAG, "Chest upload size mismatch: " + body.length + " bytes, expected 262144");
        }
        try {
            java.io.File dest = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(body);
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + dest.getAbsolutePath() + "\",\"sizeBytes\":" + body.length + "}");
        } catch (Exception e) {
            android.util.Log.w(TAG, "Chest upload failed", e);
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    // (音樂上載搬咗去 AudioCenter.handleMusicUpload。)
    private void handleStream(String path, Map<String, String> query, java.net.Socket socket) throws java.io.IOException {
        if ("camera".equals(path)) {
            handleCameraStream(socket);
        } else if ("mic".equals(path)) {
            handleMicStream(socket);
        } else {
            byte[] msg = ("Not found: /stream/" + path).getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
        }
    }

    private void handleCameraStream(java.net.Socket socket) throws java.io.IOException {
        CameraController.StartResult started = cameraController.start(8000);
        java.io.OutputStream out = socket.getOutputStream();
        if (started.error != null) {
            byte[] msg = ("Camera unavailable: " + started.error).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // BlockingQueue rather than writing directly from onFrame(): onFrame() runs on
        // CameraController's own camera thread and must return immediately (it's also
        // fanning the same frame out to every other connected stream client) - it must
        // not block on this connection's socket write, which can stall arbitrarily long
        // on a slow/stuck client. capacity 1 + offer-that-drops-the-oldest keeps this
        // socket's writer thread always working from the newest frame rather than
        // buffering up a backlog if the network can't keep up with 30fps.
        final java.util.concurrent.ArrayBlockingQueue<CameraController.Frame> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        CameraController.FrameListener listener = new CameraController.FrameListener() {
            @Override
            public void onFrame(CameraController.Frame frame) {
                queue.poll(); // drop whatever stale frame was waiting, if any
                queue.offer(frame);
            }
        };
        cameraController.subscribe(listener);
        try {
            while (true) {
                CameraController.Frame frame;
                try {
                    frame = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) {
                    // No frame in 10s - camera likely died; stop rather than hold the
                    // connection (and the pool thread) open forever with a frozen image.
                    break;
                }
                out.write(("--" + MJPEG_BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.jpeg.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame.jpeg);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); // each part must reach the client promptly, not batch up
            }
        } finally {
            cameraController.unsubscribe(listener);
            // Only actually releases the camera once every other stream client (if any)
            // has also disconnected - see CameraController.stopIfIdle() javadoc.
            cameraController.stopIfIdle();
        }
    }

    // Each part is a complete, independently-decodable WAV file. multipart/mixed (not
    // x-mixed-replace, which specifically means "each part replaces the last" - fine
    // for MJPEG video frames but wrong for audio chunks that should all play in
    // sequence) is the correct MIME semantics here, though the client-side JS still
    // parses the boundary manually since fetch()+ReadableStream is used rather than
    // relying on any browser-native multipart handling.
    private static final String MIC_BOUNDARY = "opensdktestpanelaudio";

    // (setHeadEyeLedLong/applyObstacleIndicator 搬咗去 LedCenter。)
    /** Parses raw chest-serial receive frames looking for CHES_SEND_OBSTACLE (command
     *  byte -127 / 0x81, per Alpha2RobotApi#chest_configureSonar javadoc), which the
     *  chest board sends unprompted once servo/sonar has configured a trigger distance.
     *  ASSUMPTION (unverified on real hardware, needs confirming from a logged frame):
     *  bytes[0] is the command byte and bytes[1] is param[0], mirroring the symmetric
     *  layout sendCommand() uses on the way out (cmd byte + param array). If real
     *  frames turn out to carry a different header/offset, only this method needs
     *  adjusting - the purple-LED behaviour and "sonar_obstacle" event stay the same.
     *  On trigger (param[0] != 0): solid purple (color=5) head+eye LEDs, brightness 9.
     *  On clear (param[0] == 0): LEDs turned off. Also published as "sonar_obstacle" so
     *  the front-end chart can plot live triggered/clear state against the threshold
     *  line set via servo/sonar.
     *
     *  2026-08 更新: 實機 (firmware 1.1.1.14) 證實這個 0x81 幀假設完全沒撞中 -
     *  sonar 讀數根本不會經 IAlpha2SerialPortService 的 AIDL rcv callback 送達,
     *  onListenSerialPortRcvData() 只收到 app 自己送出 chest_configureSonar()
     *  那個 config command 的 2-byte ack "04 00"。中途一度誤以為 sonar 讀數會
     *  經由 "com.ubtechinc.services.chest" (RobotWire.CHEST_ACTION) 這個全域
     *  broadcast 重新發送, 但反編譯官方 UBTech alpha2demo.apk 之後證實這也
     *  是錯的 - CHEST_ACTION 官方 demo 自己也只是用來 log 機身內部 raw command
     *  byte (見 RobotEventReceiver 的 CHEST_ACTION case), 不是 sonar 讀數。
     *  真正的 sonar 讀數是經由另一個獨立、之前完全沒診斷到的 broadcast action
     *  "com.ubtechinc.sonar.distance" (RobotWire.SONAR_DISTANCE_ACTION) 送出,
     *  extra 已經是 parse 好的 int (key "sonar_distance",
     *  RobotWire.SONAR_DISTANCE_EXTRA), 不需要自己再解 raw wire frame - 見
     *  RobotEventReceiver 的 SONAR_DISTANCE_ACTION case 和
     *  MainActivity#onSonarDistanceReceived()。而且就算 0x81 幀真的經由 AIDL
     *  path 送達, 實測 raw wire frame 也是 "f8 8f 0a 00 00 8b eb 04 81 05 ed" -
     *  0x81 出現在幀中間 (index 8), 不是 bytes[0], 所以這裡原本的
     *  offset 假設連框架格式都對不上, 不只是「這台機器不走這條路」那麼簡單。
     *  這個方法連同它的 0x81 假設保留不刪 - 留給其他機身/firmware 版本,
     *  如果真的會送出 0x81 開頭的 AIDL rcv 幀, 這條路徑才有意義；在這台機器上它
     *  單純不會撞到 (cmd 恆等於 4, 在 "cmd != -127" 那行提早 return), 不影響
     *  真正生效的那條 SONAR_DISTANCE_ACTION 路徑。 */
    private void handleChestObstacleFrame(byte[] bytes, int len) {
        if (bytes == null || len < 2) {
            return;
        }
        int cmd = bytes[0]; // signed byte compare against -127 on purpose - CHES_SEND_OBSTACLE is negative
        if (cmd != -127) {
            return;
        }
        boolean triggered = bytes[1] != 0;
        EventBus.get().publish("sonar_obstacle",
                "{\"triggered\":" + triggered + ",\"thresholdCm\":" + sonarThresholdCm + "}");
        if (triggered == sonarLedActive) {
            return; // avoid re-sending the same LED state on every repeated frame
        }
        sonarLedActive = triggered;
        ledCenter.applyObstacleIndicator(triggered);
    }

    /**
     * Releases alpha2services' hold on the shared audio hardware before this app opens
     * its own AudioRecord/AudioTrack. alpha2services' own speech/wakeup engine
     * (IflyteckASR5mic) holds the mic input open continuously for wake-word detection,
     * and this hardware's audio HAL (AudioHardwareTiny) does not support concurrent
     * input/output streams from multiple processes - confirmed from logcat on both
     * sides: mic recording failed outright with "status -38" (AudioPolicyManager:
     * "startInput failed: other input already started"), and AudioTrack construction
     * for speaker playback failed with state=0/STATE_UNINITIALIZED while
     * alpha2services' own audio pipeline was active. speech_SetMIC(true) is the release
     * call - true means "release the mic/audio hardware to this app" (matching the
     * Speech tab's manual "釋放麥克風給 App" button), not "false".
     *
     * setWakeState() dispatches asynchronously (an AIDL call into alpha2services, which
     * itself does a sendBroadcast internally per logcat) - it does not block until the
     * hardware is actually free. The short sleep here is what actually avoids the
     * rejection race, not just calling speech_SetMIC() alone.
     *
     * IMPORTANT side effect confirmed from logcat (2026-08-23 session): alpha2services'
     * own AlphaMainSeviceImpl reacts to this same setWakeState(true) call by internally
     * broadcasting LED_ACTION control_type:2 ("stop ear led"), turning the head/eye LED
     * back off - entirely outside this app's control, and racing against whatever LED
     * state the browser had just asked for (e.g. the green "listening" cue - see
     * app-mic.js's setListenLed()). Depending on scheduling this broadcast could land
     * either before or after this app's own LED call, which is why the green LED "有時
     * 亮,有時不亮" (sometimes lit, sometimes not) - a pure race, not a code bug in the
     * LED call itself. The fix is ordering: setHeadEyeLedLong() below is called from
     * handleMicStream() only *after* this method (and its sleep) returns, guaranteeing
     * this app's LED command is always the last one sent and therefore always wins the
     * race, rather than leaving the browser to fire its own LED call at roughly the
     * same time speech_SetMIC(true) is dispatched from the client side.
     */
    private void releaseMicForAudioIo() {
        robot.speech_SetMIC(true);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 持續搶 mic 背景 thread - 見 micHoldEnforced 個 field javadoc。每
     *  MIC_HOLD_ENFORCER_INTERVAL_MS 就重新 call 一次 speech_SetMIC(true),
     *  確保就算 firmware 內部從旁奪回了 mic (例如 setWakeState 本身在
     *  firmware bytecode 裡會順便觸發 IflytekWakeUp5mic.startRecording()
     *  這個 side effect - 見 AIDL_REFERENCE_ALPHA2.md「⚠️ 重要行為」段), app
     *  都會很快搶回來, 不用等用戶自己發現麥克風靜音了才手動再按一次。
     *
     *  用獨立 thread + sleep 而不是靠 handleMicStream() 的 loop, 是因為兩者
     *  用途不同: handleMicStream() 只在有人真的開啟 /stream/mic 才執行, 而
     *  這個 enforcer 是只要用戶在 mic card 開啟了「持續搶佔 mic」開關, 就算沒人
     *  開著 mic stream 也要生效 (例如只想用 TTS, 但不想讓機器人自己的
     *  wake-word 引擎不時搶返支 mic)。 */
    private void startMicHoldEnforcer() {
        if (micHoldEnforcerThread != null) return;
        micHoldEnforced = true;
        micHoldEnforcerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (micHoldEnforced && !Thread.currentThread().isInterrupted()) {
                    // 和 startXiaozhiMicHoldEnforcer() 一樣的原因 (見
                    // robotTtsSpeaking field javadoc) - 機身 robot-side TTS
                    // 正在播放就跳過這一輪, 不要打斷它。
                    if (micHeldByApp && !robotTtsSpeaking) {
                        robot.speech_SetMIC(true);
                    }
                    try {
                        Thread.sleep(MIC_HOLD_ENFORCER_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "MicHoldEnforcer");
        micHoldEnforcerThread.start();
    }

    private void stopMicHoldEnforcer() {
        micHoldEnforced = false;
        if (micHoldEnforcerThread != null) {
            micHoldEnforcerThread.interrupt();
            micHoldEnforcerThread = null;
        }
    }

    private void handleMicStream(java.net.Socket socket) throws java.io.IOException {
        releaseMicForAudioIo();
        ledCenter.setHeadEyeLedLong(2, 9); // 綠燈長開 - 正在聽機器人說話, 一定要在上面那行之後才呼叫,
                                 // 見 releaseMicForAudioIo() javadoc 解釋為何順序很重要

        AudioController.StartResult started = audioController.start(5000);
        java.io.OutputStream out = socket.getOutputStream();
        if (started.error != null) {
            byte[] msg = ("Mic unavailable: " + started.error).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/mixed; boundary=" + MIC_BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // Capacity 2 rather than camera's 1: audio chunks must all be delivered in
        // order (dropping one produces an audible gap/glitch, unlike a skipped video
        // frame which is imperceptible), so this queue absorbs a little jitter instead
        // of discarding outright. Kept deliberately short (~1s at CHUNK_MS=500) since
        // this is meant to feel like a live walkie-talkie - a large buffer would trade
        // away responsiveness for smoothness, and if the client falls this far behind
        // something is already wrong (dead connection, GC pause) where further
        // buffering would just add latency without fixing the underlying stall.
        final java.util.concurrent.ArrayBlockingQueue<AudioController.Chunk> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
        AudioController.ChunkListener listener = new AudioController.ChunkListener() {
            @Override
            public void onChunk(AudioController.Chunk chunk) {
                if (!queue.offer(chunk)) {
                    queue.poll(); // drop the oldest to make room, keep chunks in order
                    queue.offer(chunk);
                }
            }
        };
        audioController.subscribe(listener);
        try {
            while (true) {
                AudioController.Chunk chunk;
                try {
                    // 2026-08 修正 (用家要求): 之前呢度用 poll(10, SECONDS), 10 秒
                    // 拿不到 chunk 就當「mic 死了」自動 break, 接著下面的 finally
                    // 就會 speech_SetMIC(false) 主動把 mic 還給機器人 —— 但用家
                    // 想要的是「只有用家自己按停才還機, 不理會有沒有聲音都不應該自動
                    // 還」。改用沒有 timeout 的 take(), 只是阻塞式等待下一個 chunk,
                    // 不會因為靜音就自行斷開。stream connection 本身斷了
                    // (用家關掉瀏覽器分頁/收起 tab) 會由下面 out.write() 拋出
                    // IOException 讓 loop 自然跳出, 不用靠這裡的逾時判斷。
                    //
                    // Trade-off: 如果 AudioController.readLoop() 本身真的故障
                    // (AudioRecord.read() 持續讀錯, 見 AudioController 那邊 n<0
                    // 那段), readLoop() 會自己 release 掉 AudioRecord 並停止, 但
                    // 不會再有新 chunk 送進來, 這裡的 take() 會永久阻塞, 這條 HTTP
                    // thread 唯一釋放方法是用家自己在瀏覽器裡按「停止聽」
                    // (讓 fetch abort, socket close, out.write() 才會拋出 IOException
                    // 讓 loop 跳出)。這是刻意換來的代價 - 為了完全消除「靜音
                    // 就自動還機」這個不想要的行為, 不會再有任何逾時自動釋放。
                    chunk = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                out.write(("--" + MIC_BOUNDARY + "\r\n"
                        + "Content-Type: audio/wav\r\n"
                        + "Content-Length: " + chunk.wav.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(chunk.wav);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            audioController.unsubscribe(listener);
            boolean wasLastListener = audioController.hasNoListeners();
            audioController.stopIfIdle();
            if (wasLastListener) {
                // Give the mic back to alpha2services' own wake-word engine now that
                // nobody is listening to the mic stream - otherwise voice wakeup would
                // stay silently disabled until someone went to the Speech tab and
                // manually re-enabled it, same as it used to require to enable it.
                // false = "交還麥克風給機器人" (hand back to the robot), matching
                // setMic(false) in app-speech.js - true is the opposite, "release to app".
                //
                // 例外: 如果用家在 TTS tab 按了「釋放麥克風給 App」(micHeldByApp),
                // 就代表他想長期由 app 持有 mic - 這個 stream 斷開 (背景化分頁/
                // 網路短暫中斷都會觸發這個 finally) 不應該把 mic 悄悄還給機器人,
                // 否則個「釋放」狀態就會被呢度無聲蓋走, 要用家自己再撳一次先頂到住。
                if (!micHeldByApp) {
                    robot.speech_SetMIC(false);
                }
                // Safety net: turn the green "listening" LED back off here too, not
                // just relying on the browser's stopMicListen() sending preset=stop -
                // if this stream connection just drops (backgrounded tab, network
                // blip, browser closed) rather than being stopped via the button, the
                // browser-side call never happens and the LED would otherwise stay
                // stuck on indefinitely. pure-direct: 经 JNI 直关。
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        }
    }

    // (waitForFrame 搬咗去 CameraApi。)
    // 2026-08 (已淘汰): 之前用 waitForStableFrame() 跳過幾幀來迴避 preview frame
    // 過渡期問題 (AE/AF 未收斂) - 反編譯用戶提供、實測成功的第三方 apk 之後發現真正
    // 根源是 capture 方式本身 (preview frame vs 真正單張拍攝), 已改用
    // CameraController.takePhoto() (真正 camera.takePicture()), 見
    // xiaozhiTakePhotoAndExplain() 那段 comment。這個「跳幀」workaround 已不再使用,
    // 已移除, 避免留低死 code 同令人誤會依然係現行做法。

    // (wifi/bt 狀態搬咗去 DeviceStatus。)
    // 2026-09 移除: 舊 binder actionList() (經 robot.action_getActionList 等
    // 5s latch)——機身已無 alpha2services，只會回 NOT_INIT。action/list 一律行
    // ActionDirect.actionListDirect() (讀 actionInfo.txt + UbxPlayer)。
    // 2026-09: 動作檔尋址層 (ACTION_DIR/INFO + loadActionInfo + resolveActionFile)
    // 搬咗去 ActionDirect (拆 god object 第二刀)，以下淨返 ubx/servo 共用實現。
    // -- Ubx 直播共用实现（/api/direct/ubx/* 与 /api/alpha2/ubx/* 同调；
    // 抢占式：播新动作自动停旧动作，与原厂 playActionName 打断语义一致）--
    // 2026-09: ubx 直播共用實現 (list/play/speed/stop/status) 同單舵機 cmd03 化
    // (servoSendOne/Code) 搬咗去 UbxApi (拆 god object 第三刀)。
    // 2026-09 移除: servoSendAll()——零調用 (direct servo/all 已內聯同一邏輯)。
    // 2026-09: actionListDirect / actionPlayDirect / playActionDirect /
    // stopActionWithRecovery 搬咗去 ActionDirect (拆 god object 第二刀)。
    // 动作配乐已并入 UbxPlayer 内 voice 线（a/j/a/o 官方语义），此处不再另起 MediaPlayer。
    // 配乐寻址规则见 UbxPlayer.resolveVoiceFile：ubx去扩展名/music名，缺省退回目录首首 mp3。
    // 2026-09: 舊 require()/queryOrDefault() 已全量遷移至 ApiValidator, 此處不再保留
    // (Map.getOrDefault 在 API 22 會 NoSuchMethodError, 一律經 ApiValidator.optional()
    // 取代; 空字串同缺席一樣回 default, 非法值拋 IllegalArgumentException → 400)。
    // (TTS 嘴燈 bracket 搬咗去 LedCenter.start/stopMouthLedForTts()。)
    static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    /**
     * Same as codeResponse but also reports whether the underlying chest/header
     * direct serial port was actually open (directChestReady()/directHeaderReady())
     * at the time of the call. pure-direct: no AIDL bind exists any more;
     * API_ERROR_SUCCEED means the frame was written to /dev/ttyS1/S3.
     */
    static HttpServer.ApiResponse codeResponseReady(UbxErrorCode.API_ERROR_CODE code, boolean ready) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code
                + "\",\"bindReady\":" + ready + "}");
    }

    static String jsonSafe(String s) {
        if (s == null) return "";
        // 2026-08 修正: 之前只 escape 反斜線和雙引號, 沒處理換行/回車/tab -
        // XiaozhiOtaClient 的 server 回應的 activationMessage 實測證實會帶著
        // literal "\n" (實機 logcat 看到 "xiaozhi.me" 後面直接斷行), 送入
        // EventBus.publish() 組出來的 JSON string 裡如果有未 escape 的真正換行
        // 字元在語法上是非法的 (JSON string 不允許有 literal newline) - 前端
        // JSON.parse() 會直接拋錯, 使整個 event 落入 catch 變成 type:"raw",
        // 使 "xiaozhi_activation" 這個 type 永遠比對不中, 界面對應的顯示邏輯
        // (xiaozhiShowActivationCode()) 完全不會觸發 - 這才是「websocket log
        // 看到東西, 但界面沒顯示」的真正成因。
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** RobotEventReceiver tts_hint_wakeup 交俾 GrammarCenter (wakeup probe)。 */
    public static void triggerWakeupProbe() {
        MainActivity m = sInstance;
        if (m == null || m.grammarCenter == null) {
            return;
        }
        m.grammarCenter.triggerWakeupProbe();
    }

    // 2026-09: 真實 MCU 韌體版本/UUID 查詢成組搬咗去 ChestQuery (拆 god object
    // 第一刀)——以下淨返個位，邏輯一字不改喺嗰邊。
    // 2026-09 刪除: queryHeaderFirmwareVersion() - 無 caller (頭版本無 endpoint、
    // 前端無入口)。胸板 queryChestFirmwareVersion() 保留。

    // -- 胸口升級實作 (48/49/50，鏡像 alpha2services h.a.a$b) ---------------------------
    // 2026-09: 胸升級實裝 (電量/MD5/ACK/升級線程/啟動/狀態) 搬咗去 ChestUpgrade
    // (拆 god object 第四刀)。
    /** Formats raw serial bytes as space-separated uppercase hex, matching the format
     *  used by the upstream SDK's HelloAlpha example for the same callbacks. */
    static String toHex(byte[] bytes, int len) {
        if (bytes == null || len <= 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(len * 3);
        int n = Math.min(len, bytes.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", bytes[i] & 0xFF));
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    // (parseHexBytes 搬咗去 LedCenter，debug/serial/send 專用。)
}
