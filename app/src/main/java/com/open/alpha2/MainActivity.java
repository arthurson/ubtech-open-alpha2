package com.open.alpha2;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ubtechinc.alpha.hardware.RobotWire;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.LocalAlpha2Services;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * Single-activity host for the Open Alpha2 robot panel.
 *
 * Owns the one {@link RobotStub} instance for the process (同
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
public class MainActivity extends Activity implements XiaozhiBridge.HostState, GestureCenter.Host {
    private static final String TAG = "MainActivity";

    static final String PREFS_NAME = "robotpanel";

    // (蹲下站起；停止語義不變，見 actionDirect.stopActionWithRecovery())。

    private RobotStub robot;
    private LocalAlpha2Services localServices;
    // 動作配樂由 UbxPlayer 内 voice 线负责（a/j/a/o 官方语义：同 clock 并行、
    // 槽位起播、b*timeBase 自停、切帧打断），獨立於 currentMusicPlayer/currentRadioPlayer，
    // 唔經 filler 循環/EQ/頻譜。
    private final UbxPlayer ubxPlayer = new UbxPlayer();
    // 三個共用上面同一個 ubxPlayer 實例 (servo 讀寫仲喺呢度直接用)。
    private ActionDirect actionDirect;
    private UbxApi ubxApi;
    private HttpServer httpServer;
    private RobotEventReceiver dynamicReceiver;

    // -- WiFi 指示燈 -----------------------------------------------
    private BroadcastReceiver panelUrlReceiver;
    private TextView panelLinkView;
    private String currentPanelUrl;
    private final CameraController cameraController = new CameraController();
    private final AudioController audioController = new AudioController();
    private final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();
    private MicCenter micCenter;
    private GestureCenter gestureCenter;
    private final MusicController musicController = new MusicController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 開機 15s 後試播開機語音。field 化方便 onDestroy removeCallbacks。 */
    private final Runnable bootVoiceRunnable = new Runnable() {
        @Override public void run() {
            if (sInstance == null || xiaozhiBridge == null) return;
            xiaozhiBridge.maybeBootVoice("startup");
        }
    };

    static final long MIC_HOLD_ENFORCER_INTERVAL_MS = 2000;

    /** 完全取代悠聊 APK (com.ubtech.iflytekmix) 用的中文語意配對引擎
     *  實例。在 onCreate() 建立一次 (只持有 Context, 不碰 AIDL, 沒有初始化順序問題),
     *  真正的 1000 條資料就到 handleIflytekSemanticText() 第一次被叫才讀 assets - 見
     *  SemanticMatcherZh 本身的 lazy-load 設計。 */
    private SemanticMatcherZh semanticMatcherZh;

    /** 完全取代 AlphaEnglishChat APK
     *  (com.ubtechinc.alphaenglishchat) 用的英文語意配對引擎實例, 和 semanticMatcherZh
     *  屬於同一套機制、獨立資料 (1000 條英文問法, 見 SemanticMatcherEn)。
     *  哪句用哪個 matcher 由 handleIflytekSemanticText() 根據輸入文字有沒有 CJK 漢字
     *  判斷 - 不靠 speech/set_asr_engine 的語言設定, 因為 iFlytek 引擎本身可能自動
     *  偵測語言, 靠內容判斷更可靠。 */
    private SemanticMatcherEn semanticMatcherEn;

    /** Vosk 離線 ASR controller (語音 tab)。單例，onCreate 起，
     *  onDestroy 停。Model 放 sdcard 自動偵測，見 VoskController。 */
    private VoskController vosk;

    private RingtoneCenter ringtoneCenter;

    private ChestQuery chestQuery;
    private ChestUpgrade chestUpgrade;

    /** PIR 事件 static 縫 (RobotEventReceiver／onDirectChestFrame 入口，簽名不變)：
     *  轉交 SonarCenter；sInstance／sonarCenter 任一
     *  null 即 no-op——onCreate 同一 thread 先後建構（sonarCenter 遲過
     *  registerDynamicReceiver），起動嗰幾 ms 內嘅 PIR edge 會跌咗，行為同其他
     *  center 嘅 null-guard 一致。非阻塞約束見 SonarCenter.onPirStateReceived。 */
    static void onPirStateReceived(final boolean triggered) {
        final MainActivity m = sInstance;
        if (m == null || m.sonarCenter == null) {
            return;
        }
        m.sonarCenter.onPirStateReceived(triggered);
    }

    private TtsCenter ttsCenter;

    private LedCenter ledCenter;
    private SemanticCenter semanticCenter;
    private DeviceStatus deviceStatus;
    private XiaozhiBridge xiaozhiBridge;
    private ApiDispatcher apiDispatcher;
    private GrammarCenter grammarCenter;
    private SpeechCenter speechCenter;
    private SonarCenter sonarCenter;

    // -- XiaozhiBridge.HostState (宿主縫)：TTS 兩法轉交 SpeechCenter，sonar 四法
    // 轉交 SonarCenter（delegate＋null-guard；MCP 4 tool 經呢度照讀）。 --
    @Override public boolean isRobotTtsSpeaking() { return speechCenter != null && speechCenter.isRobotTtsSpeaking(); }
    @Override public long getLastSpeechStopAtMs() { return speechCenter != null ? speechCenter.getLastSpeechStopAtMs() : 0L; }
    @Override public int getSonarDistanceCm() { return sonarCenter != null ? sonarCenter.getSonarDistanceCm() : -1; }
    @Override public int getSonarThreshold() { return sonarCenter != null ? sonarCenter.getSonarThreshold() : 30; }
    @Override public int getPirTriggeredState() { return sonarCenter != null ? sonarCenter.getPirTriggeredState() : -1; }
    @Override public void applySonarThreshold(int distanceCm) {
        if (sonarCenter != null) sonarCenter.applySonarThreshold(distanceCm);
    }
    private CameraApi cameraApi;
    private VoskApi voskApi;

    // RobotEventReceiver 沒有 constructor/field 拿到 outer
    // MainActivity instance (它一直只經 EventBus 靜態方法送 event, 不認識
    // MainActivity 本身), 但 sonar_obstacle 的 LED 指示邏輯 (applyObstacleIndicator,
    // sonarThresholdCm) 全部是 instance-level, 靠著 robot 這個 AIDL 連線。加一個
    // static instance reference, 在 onCreate/onDestroy set/clear, 讓
    // RobotEventReceiver 可以經 MainActivity.getSonarThresholdCm() /
    // MainActivity.onSonarDistanceReceived() 這兩個 static bridge 方法接回
    // instance 邏輯 (static 簽名不變轉交),
    // 而不用將 RobotEventReceiver 的 constructor 簽名擴大 (這樣會
    // 影響到整個 registerDynamicReceiver() 的 new RobotEventReceiver() call 位)。
    private static volatile MainActivity sInstance;

    /** SONAR_DISTANCE_ACTION 觸發的 broadcast 未到之前, RobotEventReceiver 都要知道
     *  現在的門檻才計得到 "triggered"。沒 instance／sonarCenter (例如 Activity
     *  未起好/已destroy 中間那段窗口) 就當沒門檻, 不會誤判 triggered。 */
    static int getSonarThresholdCm() {
        MainActivity m = sInstance;
        return (m != null && m.sonarCenter != null) ? m.sonarCenter.getSonarThreshold() : 30;
    }

    /** RobotEventReceiver 收到 SONAR_DISTANCE_ACTION 之後的入口 (正本喺
     *  SonarCenter#onSonarDistanceReceived)：轉交；
     *  沒 instance／sonarCenter 就靜靜地不做事。 */
    static void onSonarDistanceReceived(int distanceCm, boolean triggered) {
        MainActivity m = sInstance;
        if (m == null || m.sonarCenter == null) {
            return;
        }
        m.sonarCenter.onSonarDistanceReceived(distanceCm, triggered);
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
        mainHandler.postDelayed(bootVoiceRunnable, 15000);
        semanticMatcherZh = new SemanticMatcherZh(this);
        semanticMatcherEn = new SemanticMatcherEn(this);
        // Vosk 熔斷 —— vosk-android minSdk 21，API 19 機（呢個 APK 要
        // 裝到 4.4）絕對唔可以掂 org.vosk.*（native/JNA 即炒）。19 機 vosk
        // 維持 null，所有 vosk/* endpoint 經 VoskApi.voskOrError() 回清晰錯誤。
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                vosk = new VoskController(this, semanticMatcherZh, semanticMatcherEn);
            } catch (Throwable e) {
                Log.w(TAG, "VoskController init failed", e);
            }
        } else {
            Log.i(TAG, "Vosk disabled: need API 21+, this device is API "
                    + android.os.Build.VERSION.SDK_INT);
        }
        // Android TTS 層喺 TtsCenter 建構 (vosk 之後起，等 listener 嘅
        // vosk pause/resume 有嘢掂)。null = 用機身目前預設引擎。
        ttsCenter = new TtsCenter(this, vosk);
        ttsCenter.initAndroidTts(null);
        semanticCenter = new SemanticCenter(semanticMatcherZh, semanticMatcherEn, actionDirect, ttsCenter);
        deviceStatus = new DeviceStatus(this, mainHandler, actionDirect, ubxPlayer, ttsCenter);
        // sticky broadcast 註冊時機唔敏感。
        deviceStatus.registerBatteryReceiver();
        cameraApi = new CameraApi(this, cameraController, ringtoneCenter);
        // Sonar＋PIR sensors 包：淨要 ledCenter (紫燈指示)，喺 xiaozhiBridge 之前起——
        // MCP sensors 4 tool 經 ctor 拎佢 (斷 cycle：PIR 推送
        // uplink 經下面 setUplink 後補，見 SonarCenter javadoc 縫設計)。
        sonarCenter = new SonarCenter(this, ledCenter);
        // 小智包 (HTTP API/mic/activation/vision/MCP/mute 鍵開關)：collaborator 齊喺呢度起。
        // xiaozhiClient/xiaozhiAudioController/xiaozhiConfig 由佢擁有。
        // 起喺 voskApi 之前——voskStart() 後開搶 mic 要經佢。
        // (sonarCenter 放最尾傳入；呢度起好先叫得。)
        xiaozhiBridge = new XiaozhiBridge(this, mainHandler, actionDirect, audioCenter,
                robot, ttsCenter, vosk, cameraController, ledCenter, this, sonarCenter);
        // PIR 推送 uplink 後補 (同一個 onCreate thread，httpServer 起之前一定到；
        // 未補前嘅 PIR edge 照存 state、push 跳過)。
        sonarCenter.setUplink(xiaozhiBridge);
        micCenter = new MicCenter(robot, ledCenter, audioController, audioPlaybackController, this);
        // (apiDispatcher 嗰次一齊傳入；呢度起好先叫得。)
        // 手勢包 (head pad + 音量連發 + 雙鍵總停)：actionDirect/audioCenter 齊喺呢度起 (initRobot 之後)。
        gestureCenter = new GestureCenter(this, mainHandler, ledCenter, ringtoneCenter,
                actionDirect, audioCenter, this);
        gestureCenter.start();
        grammarCenter = new GrammarCenter(this, robot, xiaozhiBridge);
        // sticky broadcast，註冊即刻有現狀 (起好先叫得)。
        grammarCenter.registerConnectivityReceiver();
        voskApi = new VoskApi(vosk, xiaozhiBridge);
        // TTS orchestration 包 (speech/tts＋stop＋總停)：要 xiaozhiBridge
        // (經 stopSpeechPlayback 停小智管道)，放 voskApi 之後、dispatcher 之前。
        speechCenter = new SpeechCenter(ttsCenter, vosk, xiaozhiBridge);
        // (sonarCenter 已喺上面 xiaozhiBridge 之前起好；
        // dispatcher 放最尾。)
        // dispatcher 包晒上面全部 controller (+speechCenter 做 Host；sensorState
        // 繼續經 this——TTS／sonar 全部轉交緊對應 center；servo/sonar 直調
        // sonarCenter)。
        // 放最尾——要等齊所有 collaborator (上面 speechCenter 最遲)。
        apiDispatcher = new ApiDispatcher(this, speechCenter, this, actionDirect, ubxApi, chestQuery,
                chestUpgrade, ttsCenter, voskApi, ledCenter, semanticCenter, deviceStatus,
                cameraApi, audioCenter, ringtoneCenter, micCenter, robot, grammarCenter,
                ubxPlayer, musicController, localServices, sonarCenter);

        // Plain HTTP only. TLS/HTTPS was tried (self-signed cert) to make getUserMedia()
        // available for the walkie-talkie mic feature, but browsers on this device
        // repeatedly rejected new TLS connections after the very first page load with
        // "SSLHandshakeException: Handshake failed / certificate unknown" (see logcat
        // from 2017-01-01 session) - each new WebSocket/keep-alive connection re-runs
        // the TLS handshake and the self-signed cert's trust exception did not reliably
        // carry over, so the WebSocket feed (accel, uuid, wakeup, etc.) dropped
        // intermittently even though the HTTP API calls themselves succeeded. Rather
        // than fight browser cert-trust behavior, TLS support was removed outright
        // - walkie-talkie (which needs a secure context)
        // stays permanently disabled in the UI (see app-mic.js) and everything else works
        // reliably over plain HTTP/WS.
        String ip = deviceStatus.getWifiIp();

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
                // 實驗 tab 面板 token 中央閘口（見 PanelAuth：opt-in，預設關＝全開；
                // 啟用後成個面板上鎖：全部 /api/* 都要 token，淨 system/auth/*
                //（解鎖入口）開放；靜態頁／ws／stream 唔經呢度，維持開放）。
                if (!PanelAuth.isOpenApi(path)) {
                    HttpServer.ApiResponse gate = PanelAuth.requireAuth(MainActivity.this, query);
                    if (gate != null) return gate;
                }
                // "/api/alpha2/..." goes to the original Alpha2RobotApi dispatch
                // (handleApi, unchanged below). "/api/system/..." is a small namespace
                // for things not tied to the robot SDK itself.
                if (path.startsWith("direct/")) {
                    return apiDispatcher.handleDirectApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("alpha2/")) {
                    return apiDispatcher.handleApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return apiDispatcher.handleSystemApi(path.substring(7), query, method, body);
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
        // panelUrl/linkView 為成員變量並隨網絡變化自動更新
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
                String urlToCopy = currentPanelUrl != null ? currentPanelUrl : ("http://" + deviceStatus.getWifiIp() + ":" + HttpServer.PORT + "/");
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
        // 反編譯 alpha2services_base 3.0.0.2 全個 APK, 搜晒所有
        // sendBroadcast() call site 逐個核對 —— "com.ubtechinc.key" 呢個 action
        // string 在這個韌體版本已經找不到任何 sendBroadcast 出處, 實際上是死
        // code。依然保留 filter + RobotEventReceiver 那個 case, 純粹做向後
        // 相容 (以防其他韌體/舊機用到這個 action), 但這台機器不會再觸發。
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction(RobotWire.ALPHA_QR_CODE);
        filter.addAction(RobotWire.ALPHA_WIFI_RESULT);
        filter.addAction(RobotWire.ALPHA_BT_CONNECTION);
        // 反編譯 alpha2services_base 3.0.0.2 整個 APK 找到的 sendBroadcast() 出處，詳見各自的
        // RobotEventReceiver case comment。
        filter.addAction("com.ubtechinc.services.Action.ACTION_STOP");
        filter.addAction("com.ubtechinc.services.Action.ROBOT_INTERRUPTED");
        // 實機 (firmware 1.1.1.14) 證實 sonar 讀數不會經由
        // IAlpha2SerialPortService.onListenSerialPortRcvData() 送達 - app 自己
        // registerSerialPortRcvListener() 只收到 config command 的 2-byte ack
        // "04 00"。CHEST_ACTION 這個 broadcast 也收得到, 但反編譯官方
        // alpha2demo.apk 之後證實它只是印機身內部 raw command byte 做 debug log
        // (getmCmd()), 不是真正的 sonar 讀數路徑。真正生效的是下面獨立的
        // SONAR_DISTANCE_ACTION - 保留 CHEST_ACTION filter 純粹做輔助 debug 用
        // (RobotEventReceiver 那個 case 依然會 dump 它的 extras, 對照兩條路徑
        // 的時序有用), 不再指望它是主要事件來源。
        filter.addAction(RobotWire.CHEST_ACTION);
        // ⚠️ 未經真機驗證 (見 RobotEventReceiver 這個 case 的
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 反推出來的 PIR 通知
        // broadcast, 只有在 SecurityCameraUtil 監控開關開啟的時候才會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
        // 官方 alpha2demo.apk (firmware 1.1.1.14) 反編譯確認: sonar 讀數是經由這個
        // 獨立 broadcast 送出, extra 已經是 parse 好的 int, 不需要自己再解 raw
        // wire frame。見 RobotWire.SONAR_DISTANCE_ACTION 的 comment。
        filter.addAction(RobotWire.SONAR_DISTANCE_ACTION);
        // 用來查「speech_SetMIC() 拿回 mic 會不會有 broadcast 通知」這個問題, 反編譯
        // Alpha2Services-v1.1.7.3.20-5mic.apk 整個 APK 找到的 sendBroadcast()
        // 出處 (speechmanager.d.*/AlphaMainSeviceImpl 這兩個 class)。特意連語意未確定的也全部先 register, 經
        // mic_broadcast_debug event 轉送到 WebSocket log (見 RobotEventReceiver
        // 這幾個 case comment) - 目的是收集實際 payload, 看完再決定哪幾個和 mic
        // ownership 真的有關、要不要正式做成獨立 event/更新 UI 指示燈, 在未驗證之前
        // 不假設這個名字看起來像什麼意思就是什麼意思。
        filter.addAction("com.ubtechinc.services.ABOUT_TTS");
        filter.addAction("com.ubtechinc.services.ALPHA_SOCKET_ASR_OK");
        filter.addAction("com.ubtechinc.services.SPEECH_ANGLE_5MIC");
        filter.addAction("com.ubtechinc.services.LED_ACTION");
        filter.addAction("com.ubtechinc.services.POWER_SAVE");
        filter.addAction("com.ubtechinc.services.ALPHA_NOTIFY_POWER");
        registerReceiver(dynamicReceiver, filter);
    }

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
                        // IMMUTABLE：target 22 而家唔使，但升上 31+
                        // 無呢個 flag 即 crash。static final int 會 inline 落 dex，
                        // 舊機 runtime 照行無影響。
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
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

    private AudioCenter audioCenter;

    /**
     * WiFi 狀態 → wifi 指示燈 (三態: wifi 熄=熄燈,
     * wifi 開但未連=紅 13, 連上 AP=藍 12)。註冊當下立即檢查一次現狀,
     * 處理「app 開啟之前已經連上/斷線」的情況。
     */

    private void initRobot() {
        // 脫離 Alpha2OpenSdk —— robot 係 RobotStub 純本地 no-op facade
        // (機身無 alpha2services, 舊 binder 調用全部誠實失敗, 見 RobotStub)。
        // chest/head 回幀只走下面的 wireDirectFrameListeners()。
        robot = new RobotStub(this);
        chestQuery = new ChestQuery(this, robot);
        actionDirect = new ActionDirect(this, ubxPlayer);
        ubxApi = new UbxApi(this, ubxPlayer, actionDirect, chestQuery);
        chestUpgrade = new ChestUpgrade(this, chestQuery);
        // (ringtoneCenter/ledCenter 已喺 onCreate 頭段起好，見上面。)
        audioCenter = new AudioCenter(this, ubxPlayer, actionDirect, mainHandler);
        EventBus.get().publish("authorize", "{\"code\":1,\"info\":\"have offline authority\"}");
        Log.i(TAG, "Authorize result: 1 have offline authority");

        // pure-direct: 机身已无 com.ubtechinc.alpha2services，不再做任何 bindService。
        // 胸/头串口帧由 HardwareDirectManager 经 DirectSerialPort 直接推送，
        // 见 wireDirectFrameListeners()。
        wireDirectFrameListeners();

        // chest_mute_key 事件經 EventBus 照常上 WebSocket。
        ledCenter.registerAlpha2PirAlertListener();
    }

    // -- pure-direct frame wiring -----------------------------------------------
    //
    // 胸/头 MCU 回帧經 HardwareDirectManager 直收：DirectSerialPort 送出的是完整
    // wire 帧（F8 8F ... ED，官方 05 00 头或 MCU 事件 00 00 头），先經 stripSerialFrame()
    // 剥到 payload 层（bytes[0] 即 cmd）再走 latch/EventBus 逻辑。对外发布的
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
     * 兼容长式（F8 8F LEN SRC DST CMD PAYLOAD SUM ED；SRC DST = 05 00 官方式
     * 或 00 00 事件式，cmd 在 i+5）和短式（F8 8F LEN CMD PAYLOAD SUM ED，
     * 旧兼容）；找不到帧头返回 null。
     * 注：cmd 13 读舵机在坏舵机（如本机 5/6 号）上回短 error 帧
     * （payload [0x0d, 0x01, id]，无角度值），调用方须按 plen/首字节 status
     * 区分 [00 id hi lo] 正常回覆，不可当角度解析。
     */
    static byte[] stripSerialFrame(byte[] frame) {
        if (frame == null) return null;
        int n = frame.length;
        for (int i = 0; i + 5 < n; i++) {
            if ((frame[i] & 0xFF) == 0xF8 && (frame[i + 1] & 0xFF) == 0x8F) {
                int lenByte = frame[i + 2] & 0xFF;
                // 长式：[i+3] 为 05（官方）或 00（事件），[i+4] 为 00，cmd 在 i+5
                if (frame[i + 4] == 0 && (frame[i + 3] == 0x05 || frame[i + 3] == 0)) {
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
        // 頭幀淨係 publish，不再做任何 latch。
    }

    private void onDirectChestFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        byte[] payload = stripSerialFrame(frame);
        if (payload == null) payload = frame;
        // 心跳靜音 - cmd 0x8B(-117, ~1Hz telemetry) 同 0x8D(-115, 5s
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

    // -- pure-direct 共用回包 helper (directCode／codeResponse／codeResponseReady／
    // jsonSafe／toHex／readFully 留喺度，各 center 經 MainActivity. 直用) --
    static UbxErrorCode.API_ERROR_CODE directCode(boolean ok) {
        return ok ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
                : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    // -- iFlytek 語意配對: 完全取代悠聊 APK (com.ubtech.iflytekmix) -------------------
    //
    // 悠聊 APK 反編譯還原出來的完整 pipeline (見對話 history) 是:
    //   機身 ASR 辨識完一句話 -> JsonResultParse 解析成 operation+slots
    //     -> RobotActionBusiness.startBusiness(): TTS(200ms sleep)Action
    // OpenAlpha2 已經有自己的 robot.speech_startTTS()/robot.action_PlayActionName(),
    // 不需要悠聊那層 RobotHandle wrapper, 只需要搬「文字 -> operation/答案/動作」
    // 這層語意配對 (SemanticMatcherZh, 由悠聊 assets/local_semantic 那 850 條
    // 問法還原) 以及這個時序。
    //
    // 掛在哪裡: 前端統一經由 speech/iflytek_simulate 觸發, 讓所有輸入方法 (真人說話/打字模擬) 都走
    // 同一條路, 避免重複 TTS。中英文由 looksChinese() 判斷, 只看輸入文字內容,
    // 不理會 ASR engine 目前設定的是哪種語言。

    // static 縫留喺度 (frozen onDirectChestFrame／RobotEventReceiver 經呢度入，簽名不變)。
    /** RobotEventReceiver／onDirectChestFrame 收到胸口 mute 鍵 (-111) 時直接呼叫
     *  (簽名不變)：轉交 XiaozhiBridge；sInstance／xiaozhiBridge 任一 null 即 no-op。 */
    public static void onMuteKeyEvent(final boolean pressed) {
        final MainActivity m = sInstance;
        if (m == null || m.xiaozhiBridge == null) {
            return;
        }
        m.xiaozhiBridge.onMuteKeyEvent(pressed);
    }

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
        // 逐個 null-guard（onCreate 中途炸/早退再 destroy）。
        mainHandler.removeCallbacks(bootVoiceRunnable);
        if (gestureCenter != null) gestureCenter.shutdown();
        if (ubxPlayer != null) ubxPlayer.stopVoice();
        if (micCenter != null) micCenter.shutdownMicHold();
        if (deviceStatus != null) deviceStatus.setAccelerometerEnabled(false);
        if (ttsCenter != null) ttsCenter.shutdown();
        if (localServices != null) {
            try { localServices.stop(); } catch (Throwable ignored) {}
        }
        if (xiaozhiBridge != null) xiaozhiBridge.shutdown();
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
        if (deviceStatus != null) deviceStatus.unregisterBatteryReceiver();
        if (ledCenter != null) ledCenter.unregisterWifiLedReceiver();
        if (panelUrlReceiver != null) {
            try {
                unregisterReceiver(panelUrlReceiver);
                panelUrlReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (grammarCenter != null) grammarCenter.unregisterConnectivityReceiver();
        if (cameraController != null) cameraController.shutdown();
        if (audioController != null) audioController.shutdown();
        if (audioPlaybackController != null) audioPlaybackController.shutdown();
        if (ledCenter != null) ledCenter.shutdown();
        if (ringtoneCenter != null) ringtoneCenter.stopRingtonePlayback();
        // 之前這裡沒有呼叫 stopLocalMusicPlayback()/stopRadioPlayback() -
        // onDestroy() 就算執行了也不會釋放正在播放的 currentMusicPlayer/currentRadioPlayer,
        // 一直以來都是個 leak (MediaPlayer native resource 沒有 release())。加入
        // Equalizer (musicEqualizer, 跟隨 currentMusicPlayer 的生命週期) 之後這個
        // 缺口更需要補上: Equalizer 綁定的 audio session 如果連 app 結束都不釋放,
        // 留下的 native effect engine 資源就更難追蹤。沿用 stopRingtonePlayback()
        // 一樣的做法, 在這裡一併全部停止。
        if (audioCenter != null) {
            audioCenter.stopLocalMusicPlayback();
            audioCenter.stopRadioPlayback();
        }
    }

    private void updatePanelUrlDisplay() {
        final String newIp = deviceStatus.getWifiIp();
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

    // ---------------- 小智 (XiaoZhi) AI 對話 ----------------
    //
    // "/api/xiaozhi/..." namespace - AI對話 doesn't belong to the robot's own AIDL
    // surface. See XiaozhiClient's class javadoc for the overall protocol/phase-1-scope
    // explanation.

    /** Radio Browser (radio-browser.info) 的其中一個 API 主機 - 官方文件建議客戶端
     *  對 "all.api.radio-browser.info" 做 DNS 解析再從多個鏡像之間挑選, 但這台機器沒有
     *  DNS SRV/多鏡像 failover 的需求 (一台家用機器人, 不是高流量服務), 直接用
     *  官方文件範例裡出現的 de1 這個固定主機就已經足夠, 保持程式碼簡單。 */
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

    // -- handleApi 缺口 (mic/grammar core 未搬)：dispatcher 經 Host 調返嚟，
    // core 搬埋嗰陣跟埋走。 --

    // -- GestureCenter.Host (0x5e 總停鍵)：轉交 SpeechCenter (TTS orchestration)。 --
    @Override public void stopAllSpeech() { if (speechCenter != null) speechCenter.stopAllSpeech(); }

    /** Handles POST /upload/audio: raw PCM bytes (16kHz mono 16-bit, matching
     *  AudioPlaybackController's format - see AudioPlaybackController.SAMPLE_RATE_HZ
     *  and app-mic.js's TALK_TARGET_SAMPLE_RATE) from the
     *  browser's mic, queued for playback.
     *  Playback must already be running (audio/play/start) - this does not implicitly
     *  start it, so a stray upload after the user has stopped talking doesn't
     *  re-open the speaker session on its own. */
    private HttpServer.ApiResponse handleUpload(String path, Map<String, String> query, byte[] body) {
        // 成個面板上鎖：啟用中全部上載（chest／music／audio）都要 token
        //（見 PanelAuth.requireAuth；未啟用即放行）。
        HttpServer.ApiResponse gate = PanelAuth.requireAuth(this, query);
        if (gate != null) return gate;
        if ("audio".equals(path)) {
            audioPlaybackController.enqueuePcm(body);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bytes\":" + body.length + "}");
        }
        if ("music".equals(path)) {
            return audioCenter.handleMusicUpload(query, body);
        }
        // 胸固件上載閘口喺上面 handleUpload 入口統一做（成個面板上鎖）；
        // 保留 "chest".equals(path) 字面比對，唔經 helper——check-openapi-drift.py
        // 靠呢個字面抽 upload 路由，轉彎即誤報 missing）。
        if ("chest".equals(path)) {
            return chestUpgrade.handleChestUpload(query, body);
        }
        return HttpServer.ApiResponse.error("Unknown upload path: " + path);
    }

    private void handleStream(String path, Map<String, String> query, java.net.Socket socket) throws java.io.IOException {
        if ("camera".equals(path)) {
            cameraApi.handleCameraStream(socket);
        } else if ("mic".equals(path)) {
            micCenter.handleMicStream(socket);
        } else {
            byte[] msg = ("Not found: /stream/" + path).getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream out = socket.getOutputStream();
            // 補 Content-Type＋CORS，同其他回應睇齊。
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + msg.length
                    + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
        }
    }

    // 呢度留薄 shim 唔直改 call site，因為 onDirectChestFrame 區間凍結（有人同時改緊 serial frame 解析，嗰區一隻字唔郁）。
    private void handleChestObstacleFrame(byte[] bytes, int len) {
        if (sonarCenter != null) sonarCenter.handleChestObstacleFrame(bytes, len);
    }

    // action/list 一律行 ActionDirect.actionListDirect() (讀 actionInfo.txt + UbxPlayer)。
    // 以下淨返 ubx/servo 共用實現。
    // -- Ubx 直播共用实现（/api/direct/ubx/* 与 /api/alpha2/ubx/* 同调；
    // 抢占式：播新动作自动停旧动作，与原厂 playActionName 打断语义一致）--
    // 动作配乐已并入 UbxPlayer 内 voice 线（a/j/a/o 官方语义），此处不再另起 MediaPlayer。
    // 配乐寻址规则见 UbxPlayer.resolveVoiceFile：ubx去扩展名/music名，缺省退回目录首首 mp3。
    // (Map.getOrDefault 在 API 22 會 NoSuchMethodError, 一律經 ApiValidator.optional()
    // 取代; 空字串同缺席一樣回 default, 非法值拋 IllegalArgumentException → 400)。
    static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    /**
     * Same as codeResponse but also reports whether the underlying chest/header
     * direct serial port was actually open (caller 經自己內聯嘅
     * directChestReady()/directHeaderReady() 傳入 ready，見各 center)
     * at the time of the call. pure-direct: no AIDL bind exists any more;
     * API_ERROR_SUCCEED means the frame was written to /dev/ttyS1/S3.
     */
    static HttpServer.ApiResponse codeResponseReady(UbxErrorCode.API_ERROR_CODE code, boolean ready) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code
                + "\",\"bindReady\":" + ready + "}");
    }

    static String jsonSafe(String s) {
        if (s == null) return "";
        // XiaozhiOtaClient 的 server 回應的 activationMessage 實測證實會帶著
        // literal "\n" (實機 logcat 看到 "xiaozhi.me" 後面直接斷行), 送入
        // EventBus.publish() 組出來的 JSON string 裡如果有未 escape 的真正換行
        // 字元在語法上是非法的 (JSON string 不允許有 literal newline) - 前端
        // JSON.parse() 會直接拋錯, 使整個 event 落入 catch 變成 type:"raw",
        // 使 "xiaozhi_activation" 這個 type 永遠比對不中, 界面對應的顯示邏輯
        // (xiaozhiShowActivationCode()) 完全不會觸發 - 這才是「websocket log
        // 看到東西, 但界面沒顯示」的真正成因。
        // \b \f + 其餘 C0 控制字元（U+XXXX 形），同 HttpServer
        // ApiResponse 轉義睇齊。
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /** RobotEventReceiver tts_hint_wakeup 交俾 GrammarCenter (wakeup probe)。 */
    public static void triggerWakeupProbe() {
        MainActivity m = sInstance;
        if (m == null || m.grammarCenter == null) {
            return;
        }
        m.grammarCenter.triggerWakeupProbe();
    }

    // 胸板 queryChestFirmwareVersion() 保留。

    // -- 胸口升級實作 (48/49/50，鏡像 alpha2services h.a.a$b) ---------------------------
    /** Formats raw serial bytes as space-separated uppercase hex, matching the format
     *  used by the upstream SDK's HelloAlpha example for the same callbacks.
     *  手寫 hex 表（chest 幀路徑高頻，慳 GC）。 */
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    static String toHex(byte[] bytes, int len) {
        if (bytes == null || len <= 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder(len * 3);
        int n = Math.min(len, bytes.length);
        for (int i = 0; i < n; i++) {
            int v = bytes[i] & 0xFF;
            sb.append(HEX_UPPER[v >>> 4]).append(HEX_UPPER[v & 0x0F]);
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
