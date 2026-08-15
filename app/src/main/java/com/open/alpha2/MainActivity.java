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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "MainActivity";

    private LynxController lynxController;
    private HttpServer httpServer;
    private RobotEventReceiver dynamicReceiver;
    private BroadcastReceiver batteryReceiver;
    private final CameraController cameraController = new CameraController();
    private final AudioController audioController = new AudioController();
    private final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private EventBus.Listener gestureListener;
    private Runnable volumeRepeater;

    /** true = 用戶喺 TTS tab 撳咗「釋放麥克風俾 App」，想長期持有 mic 俾 app 用，
     *  未撳返「交返麥克風俾機器人」之前唔算完。見 handleMicStream() finally 段嘅
     *  用法 - Mic Listen 個 stream 斷開唔應該喺呢個狀態係 true 嘅時候將 mic
     *  還俾機械人，否則個「釋放」狀態會被 Mic Listen 嘅斷線清埋，令用戶要不斷
     *  重新撳「釋放麥克風俾 App」。 */
    private volatile boolean micHeldByApp = false;

    // -- Accelerometer (IMU): standard Android SensorManager, NOT the UBTECH AIDL SDK -
    // see docs/capabilities.md "IMU / accelerometer" in the Alpha2OpenSdk repo and the
    // HelloAlpha example (examples/HelloAlpha), which reads it the same way. The robot's
    // only real motion sensor; readings are gravity-relative (tilt), not true dynamic
    // acceleration. Off by default - only registered while at least one browser tab has
    // it toggled on via the "accelerator/set" endpoint below, so idle sessions don't pay
    // for sensor callbacks/WebSocket traffic nobody is watching.
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private volatile boolean accelerometerEnabled = false;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;

    // Android system TTS (a third engine option alongside the robot's own Nuance/
    // iFlytek, used directly rather than via ISpeechInterface). No voice selection -
    // voice choice is only meaningful for iFlytek's named voices.
    // volatile: Lynx's speech/set_tts_engine handler (see LynxController.AndroidTtsHandler
    // wiring below) reassigns this from an HTTP worker thread when switching engines, and
    // it's read from other worker threads on every speech/tts call - a plain field could
    // let one thread see a stale/half-published reference.
    private volatile TextToSpeech androidTts;
    private volatile boolean androidTtsReady = false;
    private volatile String androidTtsEnginePkg = ""; // package of the engine androidTts is currently bound to

    private volatile int lastBatteryLevel = -1;
    private volatile int lastBatteryScale = -1;
    private volatile boolean lastBatteryCharging = false;
    private volatile String lastBatteryStatus = "unknown";

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - matches the web UI slider's
    // default (0-5000 range, default 0).
    private static final int TTS_MOUTH_LED_SPEED = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashRestartHandler();

        registerDynamicReceiver();
        registerBatteryReceiver();
        registerGestureController();
        // Lynx (3.0.0.2): LynxRobotApi needs no explicit
        // init/bind step (every subsystem binder is fetched lazily on first use), so
        // constructing this here is cheap and doesn't block onCreate().
        //
        // Camera/audio-testtone/audio-volume calls are plain Android hardware access,
        // not implemented in AIDL - same physical camera/mic/speaker
        // regardless of which robot SDK is selected - so LynxController defers those
        // to the shared hardware handler (this method reference isn't invoked until the first actual
        // matching request, well after onCreate() finishes, so it's safe to bind here
        // even before cameraController/audioPlaybackController exist yet).
        // Lynx UI's TTS tab uses Android's own system TTS only (no robot-side engine
        // picker) - the handler below just forwards to androidTts, the very same
        // instance Alpha2's engine=android option uses, constructed right after this.
        // Safe to wire up here even though androidTts isn't assigned until the next
        // statement: speak()/stop() below only run later, in response to an actual
        // HTTP request, by which point onCreate() (and thus this assignment) has long
        // finished.
        lynxController = new LynxController(this, new LynxController.SharedHardwareHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
                return handleSharedHardware(path, query, method, body);
            }
        }, new LynxController.AndroidTtsHandler() {
            @Override
            public boolean speak(String text, String langTag) {
                if (androidTts == null || !androidTtsReady) {
                    return false;
                }
                if (langTag != null && !langTag.isEmpty()) {
                    Locale locale = Locale.forLanguageTag(langTag);
                    int result = androidTts.setLanguage(locale);
                    // LANG_MISSING_DATA / LANG_NOT_SUPPORTED are both negative - only
                    // proceed to speak if the engine actually accepted the language,
                    // otherwise the utterance would silently fall back to whatever
                    // language was already active, which the caller didn't ask for.
                    if (result < TextToSpeech.LANG_AVAILABLE) {
                        return false;
                    }
                }
                // 2026-08 新增: 之前 Lynx tab 嘅 TTS 完全冇同咀部呼吸燈同步 - 對比
                // Alpha2 個 speech/tts (MainActivity 嗰個 case) 一早已經有
                // startMouthLedForTts()/stopMouthLedForTts() 包住個 speak() call。
                // 跟返 Alpha2 個做法: 開口講嘢前先開返個呼吸燈效果 (MouthLedData 呢個
                // JNI path 同機身 AIDL 完全獨立, 兩邊 backend 都用得 - 見
                // MouthLedData 個 class javadoc), 等聲一開始就見到燈同步郁。呢個
                // utteranceId ("lynx_tts") 已經喺 initAndroidTts() 嗰個共用
                // UtteranceProgressListener.onDone()/onError() 入面, 講完/出錯都會
                // call stopMouthLedForTts() (唔分邊個 utteranceId, 兩個 tab 共用同一個
                // listener) - 所以呢度淨係要負責「開始」嗰邊, 收尾已經有人做。
                startMouthLedForTts();
                androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lynx_tts");
                return true;
            }

            @Override
            public void stop() {
                if (androidTts != null) {
                    androidTts.stop();
                }
                // 用戶主動撳「停止」冇保證會觸發 onDone/onError (視乎 TTS engine 實
                // 作), 同 Alpha2 個 speech/stop case 一樣, 主動停個 mouth LED, 唔淨係
                // 靠 UtteranceProgressListener。
                stopMouthLedForTts();
            }

            @Override
            public List<LynxController.TtsLanguageOption> listLanguages(String uiLang) {
                if (androidTts == null || !androidTtsReady) {
                    return new ArrayList<>();
                }
                // checkTtsDataSync() 揀方法有分先後 - 見佢自己個 method comment:
                //  - getVoices() (API 21+) 做主要來源: 直接問 engine 自己嘅完整
                //    voice metadata, 唔靠任何手寫語言表, engine 有幾多個國家變體就
                //    吐幾多個。2026-08 user-confirmed 呢部機冇 Google Play Store,
                //    令 Google TTS 嘅 ACTION_CHECK_TTS_DATA 淨係答到出廠內建嗰一
                //    個國家變體 (中文得 zh-TW, 英文得 en-US) - getVoices() 唔受呢
                //    個限制。
                //  - ACTION_CHECK_TTS_DATA (EXTRA_AVAILABLE_VOICES) 做 fallback,
                //    畀 API 19/20 (冇 getVoices()) 嘅裝置, 或者 getVoices() 回埋
                //    空清單嗰陣用 (見 checkTtsDataSyncLegacy() 嘅 comment - 呢個
                //    仍然係 SVOX Pico 呢類冇實作 getVoices() 或者實作咗但回空嘅
                //    engine 嘅安全網, 佢哋嘅 getAvailableLanguages()/
                //    isLanguageAvailable() 都證實唔可靠, 但 ACTION_CHECK_TTS_DATA
                //    喺 Pico 度用得)。
                Locale displayLocale = "en".equals(uiLang) ? Locale.ENGLISH : Locale.TRADITIONAL_CHINESE;
                return checkTtsDataSync(displayLocale);
            }

            @Override
            public List<String> listEngines() {
                // getEngines() works off a throwaway TextToSpeech instance rather than
                // the live androidTts field on purpose - it's a static-ish device-wide
                // list (which engine packages are installed), not something that
                // depends on which engine is currently selected, so it doesn't need
                // androidTtsReady to be true first. A fresh instance also avoids ever
                // returning a stale list captured back when a *different* engine was
                // bound.
                List<String> result = new ArrayList<>();
                TextToSpeech probe = null;
                try {
                    final CountDownLatch initLatch = new CountDownLatch(1);
                    probe = new TextToSpeech(MainActivity.this, status -> initLatch.countDown());
                    // getEngines() itself doesn't require init to finish (it's not
                    // engine-specific), but waiting briefly avoids racing the very
                    // first call against the constructor's own async setup on some
                    // OEM engine implementations.
                    try {
                        initLatch.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    List<TextToSpeech.EngineInfo> engines = probe.getEngines();
                    if (engines != null) {
                        Set<String> pkgs = new TreeSet<>();
                        for (TextToSpeech.EngineInfo e : engines) {
                            pkgs.add(e.name);
                        }
                        result.addAll(pkgs);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "androidTts.getEngines failed", e);
                } finally {
                    if (probe != null) {
                        probe.shutdown();
                    }
                }
                return result;
            }

            @Override
            public boolean setEngine(String enginePackage) {
                if (enginePackage == null || enginePackage.isEmpty()) {
                    return false;
                }
                initAndroidTts(enginePackage);
                return true;
            }

            @Override
            public String currentEngine() {
                return androidTtsEnginePkg;
            }
        }, new LynxController.PirAlertHandler() {
            @Override
            public void setEnabled(boolean enabled) {
                // PIR alert LED/sound reaction - no-op without Alpha2 backend.
            }
        });
        // Constructs (or re-constructs, when switching engines - see setEngine() above)
        // androidTts. Pulled out of onCreate()'s inline block into its own method so
        // speech/set_tts_engine can call it again later without duplicating the
        // OnInitListener/UtteranceProgressListener wiring.
        initAndroidTts(null); // null = device's current default engine, same as before

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
                if (path.startsWith("lynx/")) {
                    return lynxController.handle(path.substring(5), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return handleSystemApi(path.substring(7), query, method, body);
                }
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown endpoint: " + path + "\"}");
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
        final String panelUrl = scheme + "://" + ip + ":" + HttpServer.PORT + "/";
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setTextSize(16);
        titleView.setText("Open Alpha2\n\nOpen in a browser on the same network:");
        root.addView(titleView);

        // Tappable URL row: tapping the link itself, or the dedicated Copy button,
        // both copy the panel URL to the clipboard so the user doesn't have to
        // retype a long http://<ip>:8888/ address by hand on the robot's own screen.
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        int topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        linkRow.setPadding(0, topMargin, 0, topMargin);

        final TextView linkView = new TextView(this);
        linkView.setText(panelUrl);
        linkView.setTextSize(16);
        linkView.setTextColor(Color.parseColor("#3b7dff"));
        linkView.setPaintFlags(linkView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        linkView.setLayoutParams(linkParams);

        Button copyBtn = new Button(this);
        copyBtn.setText("Copy");
        View.OnClickListener copyAction = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Alpha2 panel URL", panelUrl));
                    Toast.makeText(MainActivity.this, "Copied: " + panelUrl, Toast.LENGTH_SHORT).show();
                }
            }
        };
        linkView.setOnClickListener(copyAction);
        copyBtn.setOnClickListener(copyAction);

        linkRow.addView(linkView);
        linkRow.addView(copyBtn);
        root.addView(linkRow);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);

        Log.i(TAG, "Open Alpha2 - reachable at " + scheme + "://" + ip
                + ":" + HttpServer.PORT + "/ from any browser on the same network");

        // Charge-and-play defaults to ON (user preference). Sent as a delayed broadcast
        // rather than immediately here because ALPHA_SET_CHARGE_PLAY has no AIDL
        // "ready" wait method to hook into (unlike chest/header serial's
        // waitChestReady()/waitHeaderReady()) - alpha2services needs a moment after
        // process start to be listening for this broadcast at all. 3s chosen to match
        // the waitChestReady/waitHeaderReady timeout used elsewhere in this file.
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent("com.ubtechinc.alpha_set_charge_play");
                i.putExtra("open_charge_play", true);
                sendBroadcast(i);
            }
        }, 3000);
    }

    private void registerDynamicReceiver() {
        dynamicReceiver = new RobotEventReceiver();
        IntentFilter filter = new IntentFilter();
        // 2026-08 更新: 反編譯 alpha2services_base 3.0.0.2 全個 APK, 搜晒所有
        // sendBroadcast() call site 逐個核對 (詳見 AIDL_GUIDE_LYNX.md 「未使用/未接收
        // 嘅 broadcast」一節) —— "com.ubtechinc.key" 呢個 action string 喺呢個
        // 韌體版本已經搵唔到任何 sendBroadcast 出處, 已經被下面
        // "com.ubtechinc.services.header" 完全取代 (HeadkeyManager, lynx 專用
        // package, 用 int extra "value" 代替原本嘅 Byte extra "key")。依然保留
        // filter + RobotEventReceiver 嗰個 case, 純粹做向後相容 (以防其他韌體/
        // 舊機用返呢個 action), 但呢部機唔會再觸發。
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.services.SPEECH_DIRECTION");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction("com.ubtechinc.alpha_qrcode");
        filter.addAction("com.ubtechinc.alpha_wifi_result");
        filter.addAction("com.ubtechinc.alpha_bt_connection");
        // Lynx PIR 狀態通知 (見 RobotEventReceiver 呢個 case 嘅 comment) - 反編譯
        // companion_v17_signed.apk 搵到嘅 action string, 唔喺 StaticValue 度 (呢個
        // App 之前冇引用過)。
        filter.addAction("com.ubtechinc.services.Action.PIR_STATE");
        // 2026-08 新增 (4個): 反編譯 alpha2services_base 3.0.0.2 全個 APK 搵到嘅
        // sendBroadcast() 出處, 之前呢個 App 完全冇 register, 詳見
        // AIDL_GUIDE_LYNX.md「未使用/未接收嘅 broadcast」一節同各自嘅 RobotEventReceiver
        // case comment。
        filter.addAction("com.ubtechinc.services.header");
        filter.addAction("com.ubtechinc.services.Action.ACTION_STOP");
        filter.addAction("com.ubtechinc.services.Action.ROBOT_INTERRUPTED");
        filter.addAction("com.ubtechinc.services.stoptts");
        // 2026-08 新增: 實機 (firmware 1.1.1.14) 證實 sonar 讀數唔會經
        // IAlpha2SerialPortService.onListenSerialPortRcvData() 送到 - app 自己
        // registerSerialPortRcvListener() 淨係收到 config command 嘅 2-byte ack
        // "04 00"。CHEST_ACTION 呢個 broadcast 都收到, 但反編譯官方
        // alpha2demo.apk 後證實佢淨係印機身內部 raw command byte 做 debug log
        // (getmCmd()), 唔係真正嘅 sonar 讀數路徑。真正生效嘅係下面獨立嘅
        // SONAR_DISTANCE_ACTION - 保留 CHEST_ACTION filter 純粹做輔助 debug 用
        // (RobotEventReceiver 個 case 依然會 dump 佢嘅 extras, 對比返兩條路徑
        // 嘅時序有用), 唔再指望佢係主要事件來源。
        filter.addAction("com.ubtechinc.services.Action.CHEST_ACTION");
        // 2026-08 新增: ⚠️ 未經真機驗證 (見 RobotEventReceiver 呢個 case 嘅
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 逆出嚟嘅 PIR 通知
        // broadcast, 淨係喺 SecurityCameraUtil 監控開關開緊嗰陣先會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
        // 官方 alpha2demo.apk (firmware 1.1.1.14) 反編譯確認: sonar 讀數經呢個
        // 獨立 broadcast 送出, extra 已經係 parse 好嘅 int, 唔使自己再解 raw
        // wire frame。見 StaticValue.SONAR_DISTANCE_ACTION 個 comment。
        filter.addAction("com.ubtechinc.services.Action.SONAR_DISTANCE");
        // 2026-08 新增 (8個): 用嚟查「speech_SetMIC() 攞返 mic 會唔會有 broadcast
        // 通知」呢條問題, 反編譯 Alpha2Services-v1.1.7.3.20-5mic.apk 全個 APK 搵到
        // 嘅 sendBroadcast() 出處 (speechmanager.d.*/AlphaMainSeviceImpl 呢兩個
        // class), 之前呢個 App 完全冇 register。特登連語意未確定嘅都全部先
        // register 埋、經 mic_broadcast_debug event 轉送去 WebSocket log (見
        // RobotEventReceiver 呢幾個 case comment) - 目的係收集實際 payload,
        // 睇完先決定邊幾個同 mic ownership 真係有關、要唔要正式做成獨立 event/
        // 更新 UI 指示燈, 唔喺未驗證之前就假設個名啱啱好似就係咩意思。
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
     *   both pressed     -> 24065 (0x5e01)      both released    -> 24321 (0x5f01)
     *
     * Every value's low byte is 0x01; the high byte (0x5a-0x5f, 90-95) is a distinct,
     * sequential event code for each of the 6 press/release combinations - i.e. this
     * extra carries a compound (eventCode << 8 | 0x01) value here, not the plain
     * "direction" the field name suggests. Mapped to: "-"/"+" press-and-hold repeats
     * volume down/up every VOLUME_REPEAT_INTERVAL_MS until release; pressing both stops
     * the current action (releasing both does nothing extra).
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
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometerSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        gestureListener = line -> {
            if (!line.contains("\"type\":\"gesture\"")) {
                return;
            }
            int code = parseGestureEventCode(line);
            if (code < 0) {
                return;
            }
            mainHandler.post(() -> onGestureCode(code));
        };
        EventBus.get().subscribe(gestureListener);
    }

    /** Pulls the raw "direction" int out of a gesture EventBus line and returns its
     *  high byte (the event code), or -1 if the line couldn't be parsed. */
    private static int parseGestureEventCode(String line) {
        int idx = line.indexOf("\"direction\":");
        if (idx < 0) {
            return -1;
        }
        int start = idx + "\"direction\":".length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            int raw = Integer.parseInt(line.substring(start, end));
            return (raw >> 8) & 0xFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void onGestureCode(int code) {
        switch (code) {
            case 0x5a: // "-" pressed: start repeating volume-down
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                stopVolumeRepeat();
                break;
            case 0x5c: // "+" pressed: start repeating volume-up
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                stopVolumeRepeat();
                break;
            case 0x5e: // both pressed: stop the current action
                stopVolumeRepeat(); // in case one pad was already held down
                break;
            case 0x5f: // both released: nothing further to do
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }

    /**
     * Plays the "Sirrah" system ringtone as the camera shutter cue, out of the robot's
     * own speaker (this Activity runs on the robot's onboard Android system, not the
     * phone/browser controlling it - see robotpanel README) rather than synthesizing a
     * sound in the browser. Same lazy-lookup-by-title-then-cache approach as
     * playStopCue()/STOP_CUE_RINGTONE_TITLE above - title is the only stable way to
     * name a specific built-in system sound across devices/Android versions.
     */
    private static final String SHUTTER_CUE_RINGTONE_TITLE = "Sirrah";
    private android.net.Uri shutterCueUri;
    private boolean shutterCueLookupDone;
    private void playShutterCue() {
        if (!shutterCueLookupDone) {
            shutterCueUri = findRingtoneByTitle(SHUTTER_CUE_RINGTONE_TITLE);
            shutterCueLookupDone = true;
            if (shutterCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + SHUTTER_CUE_RINGTONE_TITLE
                        + "\" - shutter cue will be skipped");
            }
        }
        playRingtoneUri(shutterCueUri);
    }

    // 2026-08 新增 (修 bug): 之前 playRingtoneUri() 每次都開一個全新、完全冇留低
    // reference 嘅 MediaPlayer, fire-and-forget, 播完/出錯先自己 release —— 呢個
    // 做法有兩個問題: (1) 用家喺個 ringtone 未播完之前撳多次「播放」(或者 Blockly
    // 個「例子 5」撳多過一次執行), 就會有多個 MediaPlayer 同時各自播緊, 聲音疊埋
    // 一齊, 聽落好似「唔停咁響」; (2) 完全冇任何方法可以由外面 (前端「停止播放」
    // 掣) 中斷佢, 一定要等成首歌/鈴聲自然播完。修法: 用呢個 field 記住「依家播緊
    // 嗰個」MediaPlayer, 每次開新嘅之前先停舊嗰個, 並且加返
    // audio/ringtones/stop 呢個 endpoint 俾前端隨時中斷。
    private android.media.MediaPlayer currentRingtonePlayer;

    /** Shared playback: STREAM_MUSIC (see playStopCue()'s javadoc for why not a plain
     *  Ringtone.play()). Stops/releases whatever ringtone was previously playing before
     *  starting the new one, and keeps a reference so audio/ringtones/stop (or the next
     *  call to this method) can interrupt it early instead of only ever letting it run
     *  to completion. No-ops silently if uri is null (title lookup found nothing on this
     *  device). */
    private synchronized void playRingtoneUri(android.net.Uri uri) {
        stopRingtonePlaybackLocked();
        if (uri == null) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, uri);
            player.setOnPreparedListener(android.media.MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                synchronized (MainActivity.this) {
                    mp.release();
                    if (currentRingtonePlayer == mp) {
                        currentRingtonePlayer = null;
                    }
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (MainActivity.this) {
                    mp.release();
                    if (currentRingtonePlayer == mp) {
                        currentRingtonePlayer = null;
                    }
                }
                return true;
            });
            currentRingtonePlayer = player;
            player.prepareAsync(); // don't block the main thread; starts once ready
        } catch (Exception e) {
            Log.w(TAG, "Failed to play ringtone cue " + uri, e);
        }
    }

    /** Stops whatever ringtone/notification-sound MediaPlayer is currently playing (if
     *  any) and releases it. Safe to call when nothing is playing - simply no-ops.
     *  Must hold the same lock as playRingtoneUri() so a stop() can never race a
     *  concurrent start(); callers already inside a `synchronized(this)` block (i.e.
     *  playRingtoneUri() itself) should call the *Locked variant instead of re-entering. */
    private synchronized void stopRingtonePlayback() {
        stopRingtonePlaybackLocked();
    }

    private void stopRingtonePlaybackLocked() {
        if (currentRingtonePlayer != null) {
            try {
                currentRingtonePlayer.stop();
            } catch (Exception e) {
                // MediaPlayer.stop() throws IllegalStateException if called from certain
                // states (e.g. still in the middle of prepareAsync()'s Prepared callback
                // race) - release()  still happens below either way, so this is safe to
                // swallow.
            }
            try {
                currentRingtonePlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentRingtonePlayer = null;
        }
    }

    // 2026-08 更新 (修 bug): findRingtoneByTitle() 之前每次 call 都 `new
    // RingtoneManager(this)`, 用完即刻拋棄個 object, 但 Android 官方文件明確話
    // RingtoneManager.getCursor() 每次攞返嘅係*同一個*底層 cursor, 唔應該由
    // 使用者自己 close() —— 佢嘅生命週期本身係跟住個 RingtoneManager instance
    // 走, 如果冇用 RingtoneManager(Activity) 呢個會自動同 activity 生命週期綁定
    // 嘅 constructor (呢度用緊 RingtoneManager(Context), 冇自動綁定), 就要自己
    // 保住個 RingtoneManager instance 唔好整咗即棄, 否則個底層 cursor 冇人釋放,
    // 一直漏 (實測 logcat 見到 CursorWindowAllocationException, # Open Cursors
    // 累積到 991 個, 就係呢個 bug 導致)。修法: 用 rmType (TYPE_RINGTONE /
    // TYPE_NOTIFICATION) 做 key, cache 住得返嗰兩個 RingtoneManager instance,
    // 成個 app 生命週期入面淨係 new 一次, 之後全部 call 都攞返 cache 嗰個嚟重用
    // (RingtoneManager.getCursor() 內部自己會 requery(), 唔使我哋手動 refresh)。
    private final java.util.Map<Integer, android.media.RingtoneManager> ringtoneManagerCache = new java.util.HashMap<>();

    private synchronized android.media.RingtoneManager getCachedRingtoneManager(int rmType) {
        android.media.RingtoneManager cached = ringtoneManagerCache.get(rmType);
        if (cached != null) return cached;
        android.media.RingtoneManager manager = new android.media.RingtoneManager(this);
        manager.setType(rmType);
        ringtoneManagerCache.put(rmType, manager);
        return manager;
    }

    /** Scans every ringtone RingtoneManager knows about (notifications + ringtones)
     *  for one whose title matches exactly (case-insensitive), returning its Uri, or
     *  null if none match. Title is the only stable way to name a specific built-in
     *  system sound - resource IDs/file paths vary by OEM and Android version. */
    private android.net.Uri findRingtoneByTitle(String title) {
        return findRingtoneByTitle(title, android.media.RingtoneManager.TYPE_ALL);
    }

    /** Same as findRingtoneByTitle(String) but restricted to a single RingtoneManager
     *  type (TYPE_RINGTONE / TYPE_NOTIFICATION) - used by "audio/ringtones/play_by_title"
     *  so a phone-ringtone lookup can never accidentally match a notification sound (or
     *  vice versa) that happens to share the same title. Uses getCachedRingtoneManager()
     *  (see its javadoc) instead of `new RingtoneManager(this)` per call - the previous
     *  per-call instantiation leaked a Cursor every time this ran, since nothing ever
     *  released it (Android's RingtoneManager has no close()/release() of its own to call). */
    private android.net.Uri findRingtoneByTitle(String title, int rmType) {
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.database.Cursor cursor = manager.getCursor();
        int position = 0;
        while (cursor.moveToNext()) {
            String candidateTitle = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
            if (title.equalsIgnoreCase(candidateTitle)) {
                // getRingtoneUri() takes the cursor POSITION (0-based row index within
                // this RingtoneManager's result set), not a raw content-provider id -
                // Cursor has no getUri(); this is the correct API for it.
                return manager.getRingtoneUri(position);
            }
            position++;
        }
        return null;
    }

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
     * Turns the accelerometer feed on/off. Safe to call repeatedly - a no-op if already
     * in the requested state. registerListener()/unregisterListener() must run on a
     * thread with a Looper (per SensorManager's contract) - both are called here on the
     * main thread, matching how registerGestureController() sets sensorManager up in
     * onCreate().
     */
    private synchronized void setAccelerometerEnabled(boolean enabled) {
        if (sensorManager == null || accelerometerSensor == null) {
            accelerometerEnabled = false;
            return;
        }
        if (enabled == accelerometerEnabled) {
            return;
        }
        if (enabled) {
            // SENSOR_DELAY_NORMAL, not _UI: verified on hardware in the Alpha2OpenSdk
            // HelloAlpha example (see docs/capabilities.md "IMU / accelerometer") - the
            // RK3288's gsensor driver reliably delivers events at this rate. _UI was
            // observed to register successfully but never actually deliver events.
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(this, accelerometerSensor);
        }
        accelerometerEnabled = enabled;
    }

    // -- SensorEventListener (accelerometer only - see setAccelerometerEnabled()) -------
    private long lastAccelLogMs = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        // Rate-limited (every ~2s) rather than per-sample: confirms whether the sensor
        // itself is actually delivering events at all, without flooding logcat - a
        // normal accelerometer at SENSOR_DELAY_NORMAL fires far more often than that.
        long now = System.currentTimeMillis();
        if (now - lastAccelLogMs > 2000) {
            lastAccelLogMs = now;
            Log.i(TAG, "onSensorChanged firing: x=" + event.values[0]
                    + " y=" + event.values[1] + " z=" + event.values[2]);
        }
        // Published as-is (m/s^2, gravity-relative - see docs/capabilities.md). The
        // browser-side chart/UI is responsible for any smoothing/scaling it wants.
        EventBus.get().publish("accel", "{\"x\":" + event.values[0]
                + ",\"y\":" + event.values[1]
                + ",\"z\":" + event.values[2] + "}");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action needed - the Alpha2's accelerometer accuracy is not meaningfully
        // actionable here (see docs/capabilities.md).
    }

    /**
     * Battery/charging is NOT available through the robot SDK - the chest board does stream it on the serial link
     * (CHEST_SEND_POWER), but the SDK never surfaces a getter for it. The documented,
     * reliable path for an on-robot app is the standard Android battery intent instead.
     * ACTION_BATTERY_CHANGED is a sticky broadcast, so this also fires immediately with
     * the current state upon registration.
     */
    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                lastBatteryLevel = level;
                lastBatteryScale = scale;
                lastBatteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING) || plugged != 0;
                lastBatteryStatus = batteryStatusName(status);
                EventBus.get().publish("battery", "{\"level\":" + level + ",\"scale\":" + scale
                        + ",\"charging\":" + lastBatteryCharging + ",\"status\":\"" + lastBatteryStatus + "\"}");
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private static String batteryStatusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            default: return "unknown";
        }
    }

    /**
     * Small backend-agnostic namespace ("/api/system/...") used by the browser to know
     * which robot firmware family it's talking to. This app doesn't auto-detect which
     * AIDL service is actually present on the robot (the two never coexist on one
     * device - see README) - the browser just remembers the user's choice (localStorage)
     * and this endpoint exists purely so a fresh tab / another device on the same LAN
     * can ask "what did this robot last say it is" for a sane default.
     */
    private HttpServer.ApiResponse handleSystemApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "backend/get":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\"lynx\"}");
            case "backend/set":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\"lynx\"}");
            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown system endpoint: " + path + "\"}");
        }
    }

    /** Shared hardware endpoints (camera, audio, accelerometer) that are identical
     *  regardless of which AIDL backend is active. */
    private HttpServer.ApiResponse handleSharedHardware(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "camera/snapshot": {
                CameraController.StartResult started = cameraController.start(8000);
                if (started.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe(started.error) + "\"}");
                }
                CameraController.Frame frame = waitForFrame(cameraController, 3000);
                if (frame == null) {
                    return HttpServer.ApiResponse.ok(
                            "{\"ok\":false,\"error\":\"timed out waiting for a preview frame\"}");
                }
                String b64 = android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"jpegBase64\":\"" + b64 + "\"}");
            }
            case "camera/shutter_sound":
                playShutterCue();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            case "camera/info":
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"previewWidth\":" + cameraController.getPreviewWidth() + ","
                        + "\"previewHeight\":" + cameraController.getPreviewHeight() + "}");
            case "camera/resolution": {
                int w = Integer.parseInt(require(query, "w"));
                int h = Integer.parseInt(require(query, "h"));
                cameraController.setRequestedResolution(w, h);
                cameraController.forceStopAndWait(3000);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"requestedWidth\":" + w
                        + ",\"requestedHeight\":" + h + "}");
            }
            case "audio/testtone": {
                AudioPlaybackController.StartResult result =
                        audioPlaybackController.playTestTone(3000);
                if (result.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe(result.error) + "\"}");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/diagnose": {
                String sweep = audioPlaybackController.diagnoseAudioTrack(10000);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"results\":\""
                        + jsonSafe(sweep).replace("\n", "\\n") + "\"}");
            }
            case "audio/play/start": {
                AudioPlaybackController.StartResult result = audioPlaybackController.start(3000);
                if (result.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe(result.error) + "\"}");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/play/stop":
                audioPlaybackController.stop();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            case "audio/volume/get": {
                int max = audioManager != null
                        ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 0;
                int cur = audioManager != null
                        ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"volume\":" + cur
                        + ",\"max\":" + max + "}");
            }
            case "audio/volume/set": {
                if (audioManager == null) {
                    return HttpServer.ApiResponse.error("AudioManager not available");
                }
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int vol = Integer.parseInt(require(query, "level"));
                vol = Math.max(0, Math.min(max, vol));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"volume\":" + vol + ",\"max\":" + max + "}");
            }
            case "accelerometer/set": {
                final boolean on = Boolean.parseBoolean(require(query, "on"));
                final CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setAccelerometerEnabled(on);
                        latch.countDown();
                    }
                });
                try {
                    latch.await(2000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (on && accelerometerSensor == null) {
                    return HttpServer.ApiResponse.ok(
                            "{\"ok\":false,\"error\":\"no accelerometer sensor available on this device\"}");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled + "}");
            }
            case "accelerometer/get":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled
                        + ",\"available\":" + (accelerometerSensor != null) + "}");
            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown shared hardware endpoint: " + path + "\"}");
        }
    }

    private String getWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ipInt = wm.getConnectionInfo().getIpAddress();
            return Formatter.formatIpAddress(ipInt);
        } catch (Exception e) {
            return "<device-ip>";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVolumeRepeat();
        micHeldByApp = false;
        setAccelerometerEnabled(false);
        TextToSpeech tts = androidTts;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (gestureListener != null) {
            EventBus.get().unsubscribe(gestureListener);
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        cameraController.shutdown();
        audioController.shutdown();
        audioPlaybackController.shutdown();
        stopRingtonePlayback();
    }

    /** (Re)binds androidTts to a specific TTS engine and wires up the same
     *  OnInitListener/UtteranceProgressListener behaviour every time - called once from
     *  onCreate() with enginePackage=null (device default) and again from
     *  LynxController.AndroidTtsHandler#setEngine() whenever the Lynx UI switches
     *  engines. */
    private void initAndroidTts(String enginePackage) {
        TextToSpeech old = androidTts;
        androidTtsReady = false;
        if (old != null) {
            old.stop();
            old.shutdown();
        }
        final TextToSpeech[] holder = new TextToSpeech[1];
        TextToSpeech.OnInitListener initListener = status -> {
            androidTtsReady = (status == TextToSpeech.SUCCESS);
            if (androidTtsReady) {
                androidTtsEnginePkg = (enginePackage != null && !enginePackage.isEmpty())
                        ? enginePackage
                        : (holder[0] != null ? holder[0].getDefaultEngine() : "");
            } else {
                Log.e(TAG, "Android TTS init failed, status=" + status + ", engine="
                        + (enginePackage != null ? enginePackage : "(default)"));
            }
        };
        TextToSpeech created = (enginePackage != null && !enginePackage.isEmpty())
                ? new TextToSpeech(this, initListener, enginePackage)
                : new TextToSpeech(this, initListener);
        holder[0] = created;
        created.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                stopMouthLedForTts();
            }

            @Override
            public void onError(String utteranceId) {
                stopMouthLedForTts();
            }
        });
        androidTts = created;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TTS_DATA_CHECK_REQUEST_CODE) {
            CountDownLatch latch;
            synchronized (ttsDataCheckLock) {
                latch = ttsDataCheckLatch;
                ttsDataCheckResult = (data != null)
                        ? data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES)
                        : null;
            }
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private final Object ttsDataCheckLock = new Object();
    private CountDownLatch ttsDataCheckLatch;
    private volatile ArrayList<String> ttsDataCheckResult;
    private static final int TTS_DATA_CHECK_REQUEST_CODE = 0x7454;

    private List<LynxController.TtsLanguageOption> checkTtsDataSync(Locale displayLocale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<LynxController.TtsLanguageOption> viaVoices = checkTtsDataViaGetVoices(displayLocale);
            if (!viaVoices.isEmpty()) {
                return viaVoices;
            }
        }
        return checkTtsDataSyncLegacy(displayLocale);
    }

    private List<LynxController.TtsLanguageOption> checkTtsDataViaGetVoices(Locale displayLocale) {
        if (androidTts == null) {
            return new ArrayList<>();
        }
        Set<Voice> voices;
        try {
            voices = androidTts.getVoices();
        } catch (Exception e) {
            Log.e(TAG, "androidTts.getVoices() failed", e);
            return new ArrayList<>();
        }
        if (voices == null || voices.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, LynxController.TtsLanguageOption> options = new HashMap<>();
        for (Voice voice : voices) {
            Locale locale = voice.getLocale();
            if (locale == null) continue;
            String tag = locale.toLanguageTag();
            if (tag == null || tag.isEmpty() || "und".equals(tag)) continue;
            if (options.containsKey(tag)) continue;
            String displayName = locale.getDisplayName(displayLocale);
            if (displayName == null || displayName.isEmpty() || displayName.equals(tag)) {
                displayName = tag;
            }
            options.put(tag, new LynxController.TtsLanguageOption(tag, displayName));
        }
        List<LynxController.TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<LynxController.TtsLanguageOption>() {
            @Override
            public int compare(LynxController.TtsLanguageOption a, LynxController.TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    private List<LynxController.TtsLanguageOption> checkTtsDataSyncLegacy(Locale displayLocale) {
        String enginePkg = androidTtsEnginePkg;
        if (enginePkg == null || enginePkg.isEmpty()) {
            return new ArrayList<>();
        }
        CountDownLatch latch;
        synchronized (ttsDataCheckLock) {
            latch = new CountDownLatch(1);
            ttsDataCheckLatch = latch;
            ttsDataCheckResult = null;
        }
        try {
            Intent checkIntent = new Intent();
            checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            checkIntent.setPackage(enginePkg);
            startActivityForResult(checkIntent, TTS_DATA_CHECK_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CHECK_TTS_DATA launch failed for engine=" + enginePkg, e);
            return new ArrayList<>();
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.e(TAG, "ACTION_CHECK_TTS_DATA timed out for engine=" + enginePkg);
                return new ArrayList<>();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
        ArrayList<String> raw = ttsDataCheckResult;
        if (raw == null) {
            return new ArrayList<>();
        }
        Map<String, LynxController.TtsLanguageOption> options = new HashMap<>();
        for (String voice : raw) {
            String[] parts = voice.split("-");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            String lang2 = iso3ToIso1Language(parts[0]);
            if (lang2 == null) {
                lang2 = parts[0];
            }
            String country2 = null;
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                country2 = iso3ToIso1Country(parts[1]);
                if (country2 == null) {
                    country2 = parts[1];
                }
            }
            Locale locale = (country2 != null) ? new Locale(lang2, country2) : new Locale(lang2);
            String tag = locale.toLanguageTag();
            if (options.containsKey(tag)) continue;
            String displayName = locale.getDisplayName(displayLocale);
            if (displayName == null || displayName.isEmpty() || displayName.equals(tag)) {
                displayName = tag;
            }
            options.put(tag, new LynxController.TtsLanguageOption(tag, displayName));
        }
        List<LynxController.TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<LynxController.TtsLanguageOption>() {
            @Override
            public int compare(LynxController.TtsLanguageOption a, LynxController.TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    private static volatile Map<String, String> iso3LanguageMap;
    private static volatile Map<String, String> iso3CountryMap;

    private static String iso3ToIso1Language(String iso3) {
        Map<String, String> map = iso3LanguageMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String lang2 = l.getLanguage();
                if (lang2.isEmpty()) continue;
                try {
                    String lang3 = l.getISO3Language();
                    if (lang3 != null && !lang3.isEmpty() && !map.containsKey(lang3)) {
                        map.put(lang3, lang2);
                    }
                } catch (Exception ignored) {
                }
            }
            iso3LanguageMap = map;
        }
        return map.get(iso3);
    }

    private static String iso3ToIso1Country(String iso3) {
        Map<String, String> map = iso3CountryMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String country2 = l.getCountry();
                if (country2.isEmpty()) continue;
                try {
                    String country3 = l.getISO3Country();
                    if (country3 != null && !country3.isEmpty() && !map.containsKey(country3)) {
                        map.put(country3, country2);
                    }
                } catch (Exception ignored) {
                }
            }
            iso3CountryMap = map;
        }
        return map.get(iso3);
    }


    // -- Service config (/sdcard/actions/service_config.json + .txt) ------------------
    //
    // 呢個檔案控制機身開機時嘅 wake word / ASR 語言 / 預設對話 app。實測確認 (見對話
    // history 嘅 log): 覆蓋呢個檔案 + 重開機，wake word 真係會跟住轉。中文／英文兩個
    // preset 都係機身原本出廠內置嘅兩組 default config (分別對應 aaservice_config.json
    // 同 service_config.json 呢兩份出廠檔案), 一字不改地照抄, 唔係自己砌出嚟嘅組合 ——
    // 兩個都係原廠已知安全嘅設定, 所以唔設「還原」掣, 亦都唔做寫入前備份 (兩個 preset
    // 之間可以隨時互相切換, 冇「損壞」呢個概念)。

    private static final String SERVICE_CONFIG_DIR = "/sdcard/actions";
    private static final String SERVICE_CONFIG_JSON = SERVICE_CONFIG_DIR + "/service_config.json";
    private static final String SERVICE_CONFIG_TXT = SERVICE_CONFIG_DIR + "/service_config.txt";

    /** 中文組: 出廠原裝 aaservice_config.json 內容, 一字不改。wake word「你好 阿爾法」
     *  (CN_WAKEUP_NIHAO_ALPHA), default_App 用返原廠嘅 iflytekmix。 */
    private static final String CN_PRESET_JSON = "{"
            + "\"alice_Server\":\"http://10.10.1.54:8081/programd/talkServer?\","
            + "\"asr_Language\":\"zh_cn\","
            + "\"default_App\":\"com.ubtech.iflytekmix\","
            + "\"develop_Server\":\"http://dev.ubtrobot.com/opencenter/app/accesscheckapp\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"wakeup_threshold_mic5\":25,"
            + "\"wakeup_word\":\"CN_WAKEUP_NIHAO_ALPHA\","
            + "\"web_Server\":\"https://services.ubtrobot.com/ubx/\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\""
            + "}";

    /** 英文組: 出廠原裝 service_config.json 內容, 一字不改。wake word「Hello Alpha」
     *  (EN_WAKEUP_HELLO_ALPHA_THREE), default_App 用返原廠嘅 alphaenglishchat。 */
    private static final String EN_PRESET_JSON = "{"
            + "\"asr_Language\":\"en_us\","
            + "\"default_App\":\"com.ubtechinc.alphaenglishchat\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"web_Server\":\"http://services.ubtrobot.com/ubx/\","
            + "\"develop_Server\":\"http://dev.ubtrobot.com/opencenter/app/accesscheckapp\","
            + "\"alice_Server\":\"http://10.10.1.54:8081/programd/talkServer?\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\","
            + "\"wakeup_word\":\"EN_WAKEUP_HELLO_ALPHA_THREE\","
            + "\"wakeup_threshold_mic5\":25"
            + "}";

    /** 讀返 service_config.json 現有內容。 */
    private HttpServer.ApiResponse serviceConfigGet() {
        String current;
        try {
            current = readFileUtf8(SERVICE_CONFIG_JSON);
        } catch (java.io.IOException e) {
            return HttpServer.ApiResponse.error("Cannot read " + SERVICE_CONFIG_JSON + ": " + e.getMessage());
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"current\":" + current + "}");
    }

    /** preset = "cn" | "en"。兩個都係機身出廠內置嘅原裝 default config, 一字不改
     *  照抄，唔設「還原」掣、亦唔做寫入前備份——兩個 preset 之間隨時可以互相
     *  切換，冇「損壞」呢個概念。寫入對應嘅 JSON + 精簡 TXT 版本, 兩個檔案要同步。 */
    private HttpServer.ApiResponse serviceConfigSet(String preset, boolean reboot) {
        String json;
        if ("cn".equals(preset)) {
            json = CN_PRESET_JSON;
        } else if ("en".equals(preset)) {
            json = EN_PRESET_JSON;
        } else {
            return HttpServer.ApiResponse.error("preset must be 'cn' or 'en'");
        }

        org.json.JSONObject obj;
        String asrLanguage;
        String defaultApp;
        try {
            obj = new org.json.JSONObject(json);
            asrLanguage = obj.getString("asr_Language");
            defaultApp = obj.getString("default_App");
        } catch (org.json.JSONException e) {
            // 呢兩個 preset 係常數, 唔應該解析失敗——如果發生, 一定係呢個 class 入面
            // 手寫錯咗, 唔係用家輸入問題。
            return HttpServer.ApiResponse.error("Internal preset JSON malformed: " + e.getMessage());
        }

        String txt = asrLanguage + "\n" + defaultApp + "\n";
        try {
            writeFileUtf8(SERVICE_CONFIG_JSON, json);
            writeFileUtf8(SERVICE_CONFIG_TXT, txt);
        } catch (java.io.IOException e) {
            return HttpServer.ApiResponse.error("Write failed: " + e.getMessage());
        }

        String rebootNote;
        if (reboot) {
            HttpServer.ApiResponse rebootResult = systemReboot();
            rebootNote = rebootResult.status == 200
                    ? "\"rebooting\":true"
                    : "\"rebooting\":false,\"rebootError\":\"" + jsonSafe(rebootResult.body) + "\"";
        } else {
            rebootNote = "\"rebooting\":false";
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"written\":true,"
                + "\"note\":\"config written but firmware only reads this file at boot - "
                + "reboot required for it to take effect\"," + rebootNote + "}");
    }

    /** 觸發機身重開機。實測證實 service_config.json 淨係開機嗰陣讀一次, 冇 runtime
     *  監聽, 所以呢個係令新 config 生效嘅必經步驟 - 唔提供任何「唔使重開機」嘅
     *  代替方案, 因為冇實測過有第二條路。 */
    private HttpServer.ApiResponse systemReboot() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return HttpServer.ApiResponse.error("PowerManager unavailable");
            }
            pm.reboot("robotpanel_service_config_change");
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"rebooting\":true}");
        } catch (SecurityException e) {
            // REBOOT permission 喺好多機身/ROM 淨係俾 system app 用, 第三方 app (即使
            // 有 manifest 聲明) 都可能會喺呢度俾 SecurityException 拒絕 - 呢個係
            // 意料之內嘅失敗模式, 唔係 bug, 前端應該提示用戶手動長按電源鍵重開機。
            return HttpServer.ApiResponse.error(
                    "REBOOT permission denied by system (common on locked-down firmware) - "
                            + "please power-cycle the robot manually for the config change to take effect: "
                            + e.getMessage());
        }
    }

    private static String readFileUtf8(String path) throws java.io.IOException {
        java.io.File f = new java.io.File(path);
        byte[] bytes = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(bytes, "UTF-8");
    }

    private static void writeFileUtf8(String path, String content) throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(path);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
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
    /** Handles POST /upload/audio: raw PCM bytes (8kHz mono 16-bit, matching
     *  AudioPlaybackController's format - see AudioPlaybackController.SAMPLE_RATE_HZ
     *  and app-mic.js's TALK_TARGET_SAMPLE_RATE; lowered from 16kHz to 8kHz by request,
     *  2026-08) from the browser's mic, queued for playback.
     *  Playback must already be running (audio/play/start) - this does not implicitly
     *  start it, so a stray upload after the user has stopped talking doesn't
     *  re-open the speaker session on its own. */
    private HttpServer.ApiResponse handleUpload(String path, Map<String, String> query, byte[] body) {
        if ("audio".equals(path)) {
            audioPlaybackController.enqueuePcm(body);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bytes\":" + body.length + "}");
        }
        return HttpServer.ApiResponse.error("Unknown upload path: " + path);
    }

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

    private void handleMicStream(java.net.Socket socket) throws java.io.IOException {

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
                    // 攞唔到 chunk 就當「mic 死咗」自動 break, 跟住落面個 finally
                    // 就會 speech_SetMIC(false) 主動將 mic 還俾機械人 —— 但用家
                    // 想要嘅係「淨係用家自己撳停先還機, 唔理有冇聲音都唔應該自動
                    // 還」。改用冇 timeout 嘅 take(), 淨係阻塞式等下一個 chunk,
                    // 唔會因為靜音就自行斷開。個 stream connection 本身斷咗
                    // (用家關咗瀏覽器分頁/收咗個 tab) 會由落面 out.write() 拋
                    // IOException 嚟令個 loop 自然跳出, 唔使靠呢度嘅逾時判斷。
                    //
                    // Trade-off: 如果 AudioController.readLoop() 本身真係故障
                    // (AudioRecord.read() 持續讀錯, 見 AudioController 嗰邊 n<0
                    // 嗰段), readLoop() 會自己 release 咗個 AudioRecord 停低, 但
                    // 唔會再有新 chunk 送入嚟, 呢度個 take() 會永久阻塞, 呢條 HTTP
                    // thread 唯一釋放方法係用家自己喺瀏覽器度撳「停止聽」
                    // (令 fetch abort, socket close, out.write() 先會拋 IOException
                    // 令個 loop 跳出)。呢個係刻意換嚟嘅代價 - 為咗完全消除「靜音
                    // 就自動還機」呢個唔想要嘅行為, 唔會再有任何逾時自動釋放。
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
            audioController.stopIfIdle();
        }
    }

    /** Polls CameraController.getLastFrame() until a frame newer than "none yet"
     *  appears, for the single-shot camera/snapshot endpoint. */
    private static CameraController.Frame waitForFrame(CameraController controller, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            CameraController.Frame frame = controller.getLastFrame();
            if (frame != null) {
                return frame;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return controller.getLastFrame();
    }

    private HttpServer.ApiResponse wifiStatus() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            boolean enabled = wm.isWifiEnabled();
            String ssid = "";
            int ipInt = 0;
            if (wm.getConnectionInfo() != null) {
                ssid = wm.getConnectionInfo().getSSID();
                ipInt = wm.getConnectionInfo().getIpAddress();
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled
                    + ",\"ssid\":\"" + jsonSafe(ssid) + "\",\"ip\":\""
                    + Formatter.formatIpAddress(ipInt) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }

    private HttpServer.ApiResponse btStatus() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":false}");
            }
            boolean enabled = adapter.isEnabled();
            String name = adapter.getName();
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":true,\"enabled\":" + enabled
                    + ",\"name\":\"" + jsonSafe(name) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }

    private static String require(Map<String, String> query, String key) {
        String v = query.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return v;
    }

    /**
     * Map.getOrDefault() is a Java 8 default method added to the java.util.Map
     * *interface* only in API 24 (Android 7.0). The robot runs Android 5.1 (API 22),
     * whose core-libart.jar Map interface predates it, so calling query.getOrDefault(...)
     * throws NoSuchMethodError at runtime even though it compiles fine (desugaring
     * rewrites lambdas/language sugar, not missing platform API surface). Use this
     * instead of Map.getOrDefault anywhere query params need a fallback value.
     */
    /**
     * Falls back to defaultValue both when the key is absent (v == null) AND when it's
     * present but empty (v.isEmpty()) - e.g. a query string ending in "...&mode=" with
     * no value after the "=", which a number input left blank in the web UI can send.
     * Originally only checked for null; a real request (led/mouth/set?mode=&...) hit
     * the empty-string gap and reached Integer.parseInt(""), throwing
     * NumberFormatException and 500-ing the handler (see logcat_recording_2026-07-03,
     * MainActivity.java:848). Every endpoint that wraps this in Integer.parseInt(...)
     * shares the same fix now, not just led/mouth/set.
     */
    private static String queryOrDefault(Map<String, String> query, String key, String defaultValue) {
        String v = query.get(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    /**
     * Starts the mouth LED breathing effect for the duration of a TTS utterance. Called
     * right after kicking off speech (both robot-side speech_startTTS and Android
     * system TTS), paired with stopMouthLedForTts() called when that speech actually
     * finishes (onServerPlayEnd for robot TTS; UtteranceProgressListener.onDone/onError
     * for Android TTS - see androidTts setup in onCreate).
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
    private static void startMouthLedForTts() {
        MouthLedData.breathing(TTS_MOUTH_LED_SPEED).apply();
    }

    private static void stopMouthLedForTts() {
        MouthLedData.off().apply();
    }

    private static String jsonSafe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Formats raw serial bytes as space-separated uppercase hex, matching the format
     *  used by the upstream SDK's HelloAlpha example for the same callbacks. */
    private static String toHex(byte[] bytes, int len) {
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
}
