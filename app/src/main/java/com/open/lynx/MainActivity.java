package com.open.lynx;

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

import com.ubtechinc.lynxrobot.LynxRobotApi;

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

/**
 * Single-activity host for the Alpha2OpenSdk test panel.
 *
 * Owns the one {@link LynxRobotApi} instance for the process (via {@link LynxController}),
 * and answers every "/api/..." HTTP call from {@link HttpServer} by routing it to the
 * matching handler. All asynchronous SDK callbacks (TTS end, action stop, ASR/grammar
 * results) are pushed to {@link EventBus} so the browser panel's WebSocket log updates
 * live.
 *
 * The activity itself shows minimal on-device status (IP:port, init state) since the
 * robot has no practical on-screen use for this tool - the HTML control panel at
 * http://<robot-ip>:8888/ is the actual UI.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "MainActivity";

    private static final String PREFS_NAME = "robotpanel";

    private LynxController lynxController;
    private HttpServer httpServer;
    private RobotEventReceiver dynamicReceiver;
    private BroadcastReceiver batteryReceiver;
    private final CameraController cameraController = new CameraController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private EventBus.Listener gestureListener;
    private Runnable volumeRepeater;

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
    private static final String STOP_CUE_RINGTONE_TITLE = "Proxima";
    private android.net.Uri stopCueUri; // resolved lazily, cached after the first lookup
    private boolean stopCueLookupDone = false;
    // Camera shutter cue (played on the robot's own speaker, not the browser) - see
    // takePhoto()/HttpServer "camera/shutter_sound". "Sirrah" is a built-in Android
    // system ringtone title, matched the same lazy/cached-by-title way as the
    // Proxima stop cue above.
    private static final String SHUTTER_CUE_RINGTONE_TITLE = "Sirrah";
    private android.net.Uri shutterCueUri;
    private boolean shutterCueLookupDone = false;

    // Lynx PIR alert cue - "Heaven" 係 Android 內置系統鈴聲標題, 同 STOP_CUE/SHUTTER_CUE
    // 一樣做法 (lazy lookup by title, cache 埋個 content:// Uri)。播放時機見
    // registerPirAlertListener() - PIR_STATE broadcast (RobotEventReceiver.java) 一到
    // triggered=true 就即刻播, triggered=false 即刻停 (即停即播, 唔等成首歌播完)。
    private static final String PIR_ALERT_RINGTONE_TITLE = "Heaven";
    private android.net.Uri pirAlertUri;
    private boolean pirAlertLookupDone = false;
    // 獨立一個 LynxRobotApi instance, 淨係俾 registerPirAlertListener() 用嚟操控頭/眼
    // LED - 冇用返 lynxController 入面嗰個 (private field, 冇曝露), 但呢個做法完全冇
    // 額外開銷: LynxRobotApi constructor 本身唔做任何 binder bind (見 LynxController
    // 建構嗰句 comment), 淨係每個 subsystem 第一次用先 lazy fetch, 開幾多個 instance
    // 都可以共存。
    private LynxRobotApi pirLedRobot;

    private volatile boolean speechReady = false;

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
    private static final long STOP_TO_TTS_MIN_GAP_MS = 400;
    private volatile long lastSpeechStopAtMs = 0L;

    // 2026-08 新增: 記低而家 speech binding 實際綁緊邊個 engine
    // ("nuance" 或 "iflytek")。開機 initSpeechApi() 一開始用通用
    // ALPHA_SPEECH_MAIN_SERVER action, 呢個 action 喺呢部機實測落嚟一直
    // route 去 Nuance (見 speech/init_grammar 落面嘅反編譯結論), 所以預設
    // 值係 "nuance"。set_asr_engine 成功切換之後會更新呢個值。
    //
    // 存在意義: 反編譯 Alpha2Services-v1.1.7.3.20 嘅 classes.dex 證實咗
    // Lcom/ubtechinc/nuance/speech/NuanceServiceImpl 入面 initSpeechGrammar()
    // 同 startSpeechGrammar() 兩個 method body 淨係一句 return-void ——完全
    // 未實作嘅空 stub, call 落去唔會拋錯, 但實際上乜都唔會發生。反而
    // Lcom/ubtechinc/iflytek/speech/IflytekServiceImpl 嘅同名 method 有真身
    // 實作, 會真正 delegate 去 com.iflytek.cloud.SpeechRecognizer 建立
    // recognizer。即係話 grammar 呢組 API 淨係喺 iFlytek binding 之下先有
    // 用, 喺 Nuance binding 之下 call 咗都係得個桔——用呢個 field 喺
    // init_grammar/start_grammar 入口擋住呢個必然落空嘅 call, 直接話俾
    // 用家知要先切去 iFlytek, 好過等到冇反應先自己估。
    private volatile String currentAsrEngine = "nuance";
    private volatile int lastBatteryLevel = -1;
    private volatile int lastBatteryScale = -1;
    private volatile boolean lastBatteryCharging = false;
    private volatile String lastBatteryStatus = "unknown";

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
        registerPirAlertListener();
        registerChestMuteKeyTestListener();
        // LynxRobotApi 唔使明確 init/bind 步驟 (每個 subsystem 嘅 binder 都係第一次
        // 用先 lazy fetch), 所以喺呢度起 LynxController 開銷好細, onCreate() 唔會被
        // 卡住。
        //
        // Camera/audio-testtone/audio-volume 呢類 call 純粹係 Android 硬件存取,
        // 唔經任何 AIDL backend - LynxController 會將呢啲轉俾
        // handleSharedHardwareApi() (呢個 method reference 要到真係有 matching
        // request 先會被 invoke, 遠遲過 onCreate() 完結, 所以喺呢度綁定係安全嘅,
        // 就算 cameraController 呢陣都仲未起好)。
        // Lynx UI 嘅 TTS tab 淨係用 Android 自己嘅系統 TTS (冇機身側 engine 揀擇) -
        // 落面個 handler 直接轉發去 androidTts, 同下面即刻起嗰個 instance 係同一個。
        // 呢度綁定嗰陣 androidTts 都仲未 assign, 都係安全嘅: speak()/stop() 淨係喺
        // 之後真正有 HTTP request 先會行到, 嗰時 onCreate() (連埋呢句 assignment)
        // 早就完成咗。
        lynxController = new LynxController(this, this::handleSharedHardwareApi, new LynxController.AndroidTtsHandler() {
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
                setPirAlertEnabled(enabled);
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
        // constructor overload removed) - the walkie-talkie feature that TLS existed
        // for has since been removed entirely (frontend and backend, including
        // AudioController.java/AudioPlaybackController.java and every audio/testtone,
        // audio/diagnose, audio/play/*, /upload/audio endpoint) and everything else
        // works reliably over plain HTTP/WS.
        String ip = getWifiIp();

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
                // 呢個 app 淨係支援 Lynx 一個 backend: "/api/lynx/..." 交俾
                // LynxController; "/api/system/..." 係 backend-agnostic 嘅細
                // namespace; 冇任何前綴嘅 legacy request (舊 cache 咗嘅瀏覽器分頁)
                // fallback 去 handleSharedHardwareApi() (camera/audio/wifi/bt/
                // accelerometer 呢類純硬件 endpoint)。
                if (path.startsWith("lynx/")) {
                    return lynxController.handle(path.substring(5), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return handleSystemApi(path.substring(7), query, method, body);
                }
                return handleSharedHardwareApi(path, query, method, body);
            }
        }, new HttpServer.StreamHandler() {
            @Override
            public void handle(String path, Map<String, String> query, java.net.Socket socket) throws java.io.IOException {
                handleStream(path, query, socket);
            }
        }, null); // RawUploadHandler: 冇任何 /upload/* endpoint 用緊 (walkie-talkie 嘅
                   // /upload/audio 已經連同前端一齊移除) - HttpServer 對 null 有
                   // guard (path.startsWith("/upload/") && rawUploadHandler != null)。
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
        titleView.setText("OpenLynx\n\nOpen in a browser on the same network:");
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

        Log.i(TAG, "OpenLynx - reachable at " + scheme + "://" + ip
                + ":" + HttpServer.PORT + "/ from any browser on the same network");
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
        filter.addAction(RobotWireConstants.ALPHA_QR_CODE);
        filter.addAction(RobotWireConstants.ALPHA_WIFI_RESULT);
        filter.addAction(RobotWireConstants.ALPHA_BT_CONNECTION);
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
        // 2026-08 新增: 心口 mute 鍵測試 (見 registerChestMuteKeyTestListener()/
        // RobotEventReceiver 個 CHEST_ACTION case) 靠住呢個 broadcast, extra
        // "value" (byte[]) 入面出現 -111 (0x91) 就代表撳咗。呢個 filter 保留純粹
        // 因為呢個測試功能仲用緊, 唔係為咗任何 sonar/避障相關嘅嘢 (呢部機冇心口
        // 超聲波感應硬件, 相關舊 code 已經喺 2026-08 死 code 清理移除)。
        filter.addAction(RobotWireConstants.CHEST_ACTION);
        // 2026-08 新增: ⚠️ 未經真機驗證 (見 RobotEventReceiver 呢個 case 嘅
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 逆出嚟嘅 PIR 通知
        // broadcast, 淨係喺 SecurityCameraUtil 監控開關開緊嗰陣先會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
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
                playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                new LynxRobotApi(getApplicationContext()).action_stopAction(
                        new com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener.Stub() {
                            @Override
                            public void onPlayActionResult(int code, int progress) {
                            }

                            @Override
                            public void onStopActionResult(int code) {
                            }
                        });
                break;
            case 0x5f: // both released: nothing further to do
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }

    /**
     * Plays the "Proxima" system ringtone as the "stop" cue, on STREAM_MUSIC so its
     * loudness tracks the same media volume that +/- control - not the notification/
     * ring volume a plain Ringtone.play() would follow instead.
     *
     * Ringtone/RingtoneManager.getRingtone() always plays on the ringtone's own stream
     * type (TYPE_NOTIFICATION -> STREAM_NOTIFICATION), which can't be overridden - so
     * this resolves "Proxima" to a content:// Uri via RingtoneManager (matching by
     * title, since that's the only stable way to name a specific built-in system sound),
     * cached after the first lookup, and plays that Uri through a plain MediaPlayer with
     * setAudioStreamType(STREAM_MUSIC) instead, which does follow the stream we set.
     */
    private void playStopCue() {
        if (!stopCueLookupDone) {
            stopCueUri = findRingtoneByTitle(STOP_CUE_RINGTONE_TITLE);
            stopCueLookupDone = true;
            if (stopCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + STOP_CUE_RINGTONE_TITLE
                        + "\" - stop cue will be skipped");
            }
        }
        playRingtoneUri(stopCueUri);
    }

    /**
     * Plays the "Sirrah" system ringtone as the camera shutter cue, out of the robot's
     * own speaker (this Activity runs on the robot's onboard Android system, not the
     * phone/browser controlling it - see robotpanel README) rather than synthesizing a
     * sound in the browser. Same lazy-lookup-by-title-then-cache approach as
     * playStopCue()/STOP_CUE_RINGTONE_TITLE above - title is the only stable way to
     * name a specific built-in system sound across devices/Android versions.
     */
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
    // 做法有兩個問題: (1) 短時間內連續觸發 (例如連續影相觸發快門聲), 就會有多個
    // MediaPlayer 同時各自播緊, 聲音疊埋一齊, 聽落好似「唔停咁響」; (2) 完全冇
    // 任何方法可以中途停低佢, 一定要等成首歌/鈴聲自然播完。修法: 用呢個 field
    // 記住「依家播緊嗰個」MediaPlayer, 每次開新嘅之前先停舊嗰個, 並且透過
    // stopRingtonePlayback() 喺其他情況 (例如切換動作、App 銷毀) 隨時中斷。
    private android.media.MediaPlayer currentRingtonePlayer;

    /** Shared playback: STREAM_MUSIC (see playStopCue()'s javadoc for why not a plain
     *  Ringtone.play()). Stops/releases whatever ringtone was previously playing before
     *  starting the new one, and keeps a reference so stopRingtonePlayback() (or the
     *  next call to this method) can interrupt it early instead of only ever letting it
     *  run to completion. No-ops silently if uri is null (title lookup found nothing on
     *  this device). */
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
     *  type (TYPE_RINGTONE / TYPE_NOTIFICATION) - lets a lookup be scoped to avoid
     *  accidentally matching a sound of the wrong type that happens to share the same
     *  title. Uses getCachedRingtoneManager() (see its javadoc) instead of
     *  `new RingtoneManager(this)` per call - the previous per-call instantiation
     *  leaked a Cursor every time this ran, since nothing ever released it (Android's
     *  RingtoneManager has no close()/release() of its own to call). */
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
     * Battery/charging is NOT available through Alpha2RobotApi (see capabilities.md
     * "Battery and charging") - the chest board does stream it on the serial link
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

    // -- Lynx PIR alert: 頭/眼 LED 長開紅燈 + Heaven 鈴聲 --------------------------------
    // RobotEventReceiver.java 監聽 com.ubtechinc.services.Action.PIR_STATE 呢個 broadcast
    // (機身真身 PIR 偵測通知, 唔經 AIDL - 詳見 docs/AIDL_GUIDE_LYNX.md「5. Sys」章節同
    // RobotEventReceiver 嗰個 case 嘅 comment), 發返 EventBus 嘅 "pir_state" event。
    // 呢度同 registerWakeupDirectionListener() 一樣, 訂閱返嗰個 event feed (唔係再開
    // 多一個 BroadcastReceiver)。triggered=true 一到即刻長開 (常亮, 唔閃) 紅色頭+眼
    // LED, 同時播 Heaven 鈴聲; triggered=false 一到即刻熄燈同停聲 (唔再轉綠燈 - 淨係
    // 熄, 因為紅燈係「警示」, 冇偵測嗰陣唔需要另一個常亮顏色標示狀態) - 唔等成首鈴聲
    // 播完, 即停即停嘅做法。呢個反應受 pirAlertEnabled 呢個獨立開關控制 (見 index.html
    // 「PIR 感應器」card 嘅「警示反應」toggle/lynxSetPirAlertEnabled()) - 同
    // 「sys/pir」呢個感應器硬件開關本身係兩件事: 就算冇開呢個 toggle, PIR_STATE
    // broadcast 都會繼續收到同轉發去前端, 淨係唔會觸發 LED/聲。
    //
    // 2026-08 由「長閃」改為「長開」: 用返 turnOnEye/turnOnHead (常亮, 已喺
    // app-lynx.js LED tab 實測 confirm 嘅簡單 call) 代替 turnOnEyeFlash/
    // turnOnHeadFlash (閃爍, p1-p3 時序參數喺 app-lynx.js 嗰段 comment 都標明「未核
    // 實」) - PIR 警示唔再閃, 一觸發就長開紅燈直到 triggered=false 為止。
    //
    // 2026-08 修 bug (crash): 之前四個 led_turnOnXxx()/led_turnOnXxxFlash() call 全部
    // 傳咗 null 做個 IRemoteLedOperationResultListener - 同 ISysService.setPIRSensor()
    // (機身側完全唔用個 listener, 傳 null 冇問題) 唔同, LedServiceProxy$BinderStub
    // 呢個 subsystem 機身側真身會直接 call listener.onLedOpResult(...), 冇做 null
    // check, 傳 null 會令機身 system app (com.ubtechinc.alpha2services) 自己拋
    // NullPointerException crash, 再觸發 Android 嘅 provider-dependency kill 連累
    // 我哋成個 App 一齊死 (見 logcat: LedServiceProxy$BinderStub$14.a 果句 NPE, 跟住
    // ActivityManagerService "Killing ...: depends on provider ... in dying proc
    // com.ubtechinc.alpha2services")。修法: 用返 noopLedListener() 呢個真實、乜都
    // 唔做嘅 Stub instance, 唔再傳 null。
    private static final String PIR_STATE_MARKER = "\"type\":\"pir_state\"";
    // LedColor: RED=1 (見 docs/AIDL_GUIDE_LYNX.md 附錄)。
    private static final int PIR_LED_COLOR_RED = 1;
    // 光度用返 app-lynx.js LED tab 已實測 confirm 嘅慣用預設值 (見 app-lynx.js
    // 「p0=顏色(1-7), p1=光暗(1-9)」嗰段 comment) - 開盡(9) 比較顯眼, 用嚟做警示。
    private static final int PIR_LED_BRIGHTNESS = 9;

    private volatile boolean pirAlertActive = false;
    // 「警示反應」開關 - 獨立於 sys/pir 感應器硬件開關, 見上面段大 comment。預設關,
    // 使用者要自己揀開先會有 LED/聲反應, 避免一開機就無啦啦閃紅燈/響鈴。
    private volatile boolean pirAlertEnabled = false;

    private void registerPirAlertListener() {
        pirLedRobot = new LynxRobotApi(getApplicationContext());
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains(PIR_STATE_MARKER)) {
                    return;
                }
                final Boolean triggered = extractPirTriggered(line);
                if (triggered == null) {
                    return;
                }
                // onEvent() 喺 main thread 行 (broadcast receiver 預設咁 dispatch,
                // EventBus.publish() 又係同步喺 publisher 條 thread call 晒啲 listener) -
                // 同 registerWakeupDirectionListener() 一樣, AIDL/MediaPlayer call 搬去
                // background thread 做, 唔好用主線程。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        applyPirLedAndSound(triggered);
                    }
                }).start();
            }
        });
    }

    // -- 心口 mute 鍵 (-111) 測試: 撳一下紫燈長開, 再撳一下熄燈 ------------------------
    // 2026-08 新增: 純粹用嚟目視確認 RobotEventReceiver 個 CHEST_ACTION case 有冇
    // 真係收到心口 mute 鍵 (chest cmd = -111) 嘅 broadcast - 呢個唔係最終功能,
    // 純粹一個「有冇反應」嘅測試訊號 (見 RobotEventReceiver 嗰個 case 嘅 comment)。
    // 官方 firmware 呢粒鍵本身完全冇連任何 LED, 呢度嘅紫燈完全係呢個專案自己加,
    // 見 applyPurpleLedIndicator() 個 comment。
    private volatile boolean chestMuteKeyLedOn = false;

    private void registerChestMuteKeyTestListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains("\"type\":\"chest_mute_key\"")) {
                    return;
                }
                // onEvent() 喺 main thread 行 (見 registerPirAlertListener() 同一句
                // comment 嘅解釋) - AIDL LED call 搬去 background thread, 唔好用
                // 主線程, 同專案一貫做法一致。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        chestMuteKeyLedOn = !chestMuteKeyLedOn;
                        try {
                            if (chestMuteKeyLedOn) {
                                applyPurpleLedIndicator(true);
                            } else {
                                applyPurpleLedIndicator(false);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "registerChestMuteKeyTestListener: LED path failed", t);
                        }
                    }
                }).start();
            }
        });
    }

    /** Toggled from "sys/pir_alert_enabled" - see LynxController's case for this. */
    void setPirAlertEnabled(boolean enabled) {
        pirAlertEnabled = enabled;
        if (!enabled && pirAlertActive) {
            // Switching the alert off mid-trigger should also clear whatever's
            // currently lit/playing, not just stop reacting to future events.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    applyPirLedAndSound(false);
                }
            }).start();
        }
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

    /** A real (non-null) no-op IRemoteLedOperationResultListener.Stub - see the "修 bug
     *  (crash)" note above this section for why passing null here isn't safe. */
    private static com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener noopLedListener() {
        return new com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener.Stub() {
            @Override
            public void onLedOpResult(int code, int extra) {
                // Intentionally empty - this alert doesn't need the op-result callback,
                // it just must not be null (see crash note above).
            }
        };
    }

    private synchronized void applyPirLedAndSound(boolean triggered) {
        if (!pirAlertEnabled && triggered) {
            return; // alert switched off - ignore new triggers (but still let an
                     // already-active alert be cleared via setPirAlertEnabled(false)).
        }
        if (triggered == pirAlertActive) {
            return; // avoid re-sending the same LED/sound state on every repeated event
        }
        pirAlertActive = triggered;
        if (triggered) {
            pirLedRobot.led_turnOnEye(PIR_LED_COLOR_RED, noopLedListener());
            pirLedRobot.led_turnOnHead(PIR_LED_COLOR_RED, PIR_LED_BRIGHTNESS, noopLedListener());
            playPirAlertCue();
        } else {
            pirLedRobot.led_turnOffEye(noopLedListener());
            pirLedRobot.led_turnOffHead(noopLedListener());
            stopRingtonePlayback();
        }
    }

    /** Plays the "Heaven" system ringtone as the PIR trigger alert - same lazy
     *  lookup-by-title-then-cache approach as playStopCue()/playShutterCue() (see
     *  playStopCue()'s javadoc for why title lookup + STREAM_MUSIC via playRingtoneUri()
     *  instead of a plain Ringtone.play()). */
    private void playPirAlertCue() {
        if (!pirAlertLookupDone) {
            pirAlertUri = findRingtoneByTitle(PIR_ALERT_RINGTONE_TITLE);
            pirAlertLookupDone = true;
            if (pirAlertUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + PIR_ALERT_RINGTONE_TITLE
                        + "\" - PIR alert cue will be skipped");
            }
        }
        playRingtoneUri(pirAlertUri);
    }

    /** Fires TextToSpeech.Engine.ACTION_CHECK_TTS_DATA at whichever engine androidTts is
     *  currently bound to, and blocks (with a timeout) for the result - this is the same
     *  intent Android's own "文字轉語音輸出 > Pico TTS" settings screen uses to build
     *  its "已安裝" list (see AndroidTtsHandler#listLanguages() javadoc for why the two
     *  alternatives tried before this one were both wrong on this device's Pico). Result
     *  extras use lang-COUNTRY-variant with 3-letter ISO codes (e.g. "eng-USA"), not
     *  BCP-47 - new Locale(lang, country).toLanguageTag() normalises that correctly since
     *  java.util.Locale accepts either 2- or 3-letter ISO codes on construction.
     *  Wrapped as a blocking call (via CountDownLatch + onActivityResult(), see the
     *  ttsDataCheck* fields below) purely so AndroidTtsHandler#listLanguages() can stay a
     *  synchronous interface method like the rest of AndroidTtsHandler, matching how
     *  listEngines() already blocks briefly on its own probe TextToSpeech's onInit. */
    private final Object ttsDataCheckLock = new Object();
    private CountDownLatch ttsDataCheckLatch;
    private volatile ArrayList<String> ttsDataCheckResult;
    private static final int TTS_DATA_CHECK_REQUEST_CODE = 0x7454; // "T T" leetspeak-ish, just needs to be a stable unused code

    private List<LynxController.TtsLanguageOption> checkTtsDataSync(Locale displayLocale) {
        // 2026-08 改法: 之前呢度淨係靠 ACTION_CHECK_TTS_DATA (EXTRA_AVAILABLE_VOICES),
        // user-confirmed 實測發現喺呢部機 (冇 Google Play Store) 度, 呢個查詢對 Google
        // TTS 嚟講每種語言就淨係報一個「內建」國家變體 (例如中文淨係 zh-TW, 冇 zh-CN/
        // zh-HK; 英文淨係 en-US, 冇 en-GB/en-AU) —— logcat 證實原因: Google TTS 嘅
        // voice pack 一般要經 Google Play Services 動態下載 (superpacks/), 冇 Play
        // Store 就攞唔到, ACTION_CHECK_TTS_DATA 就淨係答返 APK 出廠內建、免下載嗰批
        // voice。呢個唔係呢個 method 個 parse 邏輯錯, 係嗰個查詢方式本身喺呢部機度嘅
        // 資料源頭就係咁少。
        //
        // 改用 TextToSpeech.getVoices() (API 21+, Voice 呢個 class 本身就係
        // android.speech.tts.Voice) 做主要來源 —— 呢個唔係 ACTION_CHECK_TTS_DATA
        // 嗰種「已下載內容」snapshot, 而係直接問緊 engine 自己識嘅完整 voice
        // metadata (包括未下載、需要網絡先播到嘅 voice, 用
        // Voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        // 分辨), 唔使靠任何手寫嘅語言/國家清單去補 —— engine 本身有幾多個國家變體就
        // 吐幾多個出嚟, 唔會漏, 亦唔會因為 app 冇更新緊一個手寫表而過時。
        //
        // minSdkVersion 19 (API 19) 令呢個 method 唔可以無條件淨係用 getVoices() -
        // API 19/20 嘅裝置 (TextToSpeech 冇 getVoices()) 要跌返去舊嘅
        // ACTION_CHECK_TTS_DATA 做法, 見 checkTtsDataSyncLegacy()。呢部機本身係
        // Android 5.1.1 (API 22, 見 logcat "Device: [UBTECH] UBTECH alpha2 (Android
        // 5.1.1)"), 用得到 getVoices()。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<LynxController.TtsLanguageOption> viaVoices = checkTtsDataViaGetVoices(displayLocale);
            if (!viaVoices.isEmpty()) {
                return viaVoices;
            }
            // getVoices() 得出嚟空清單 (例如 engine 未 ready、或者呢個 engine 根本冇
            // 實作 getVoices(), 有啲舊 OEM engine 雖然 API level 夠但方法係空實作) -
            // 唔好就咁畀個空清單用戶, 跌落去舊方法試多次, 好過乜都冇。
        }
        return checkTtsDataSyncLegacy(displayLocale);
    }

    /** 用 TextToSpeech.getVoices() 窮舉現時 androidTts 綁緊嗰個 engine 識嘅所有
     *  voice/語言變體, 見 checkTtsDataSync() 頭段 comment 解釋點解揀呢個 API 做主要
     *  來源。同 checkTtsDataSyncLegacy() 唔同, 呢個唔使 startActivityForResult 咁重
     *  (getVoices() 係 TextToSpeech 實例本身嘅同步 method, 唔使等 onActivityResult
     *  callback), 亦唔會夾雜住 Pico 呢類冇 Play Store 依賴嘅 engine 嘅 quirk。 */
    private List<LynxController.TtsLanguageOption> checkTtsDataViaGetVoices(Locale displayLocale) {
        if (androidTts == null) {
            return new ArrayList<>();
        }
        Set<Voice> voices;
        try {
            voices = androidTts.getVoices();
        } catch (Exception e) {
            // user-confirmed 有 OEM engine 會喺呢度 throw NPE/IllegalStateException
            // 而唔係好地地回傳 null - 當冇資料處理, 跌返去 legacy 方法。
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
            checkIntent.setPackage(enginePkg); // target the specific engine, not "whichever app wins"
            startActivityForResult(checkIntent, TTS_DATA_CHECK_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CHECK_TTS_DATA launch failed for engine=" + enginePkg, e);
            return new ArrayList<>();
        }
        try {
            // 3s is generous for what's normally an instant, on-device lookup with no
            // network/disk work - if it's still not back by then, something's wrong
            // (engine not responding) and the caller should just get an empty list
            // rather than hang the HTTP request indefinitely.
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
        // Keyed by tag (not a Set<String>) so duplicate voice entries collapse to one
        // option per language, same as before - but now carrying the display name
        // alongside, not just the tag.
        Map<String, LynxController.TtsLanguageOption> options = new HashMap<>();
        for (String voice : raw) {
            // "eng" or "eng-USA" or "eng-USA-FEMALE" - split, keep just lang[-country],
            // drop any variant suffix (a 4th part or beyond isn't a Locale country and
            // toLanguageTag() has no slot for arbitrary engine-specific variant labels).
            String[] parts = voice.split("-");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            // Both parts are ISO-639-2/ISO-3166-1 ALPHA-3 (3-letter), e.g. "eng"/"USA" -
            // user-confirmed bug on real hardware: new Locale("eng").toLanguageTag() does
            // NOT come back as "en" the way it would from a 2-letter code. Locale's
            // constructor does not translate 3-letter ISO codes to their 2-letter
            // equivalents at all - it just stores whatever string it's given more or
            // less verbatim, so toLanguageTag() was leaking the raw 3-letter codes
            // straight into the dropdown ("ara", "ben", "eng", ...) instead of proper
            // BCP-47 tags. iso3ToIso1Language()/iso3ToIso1Country() below do the actual
            // translation via reverse lookup against Locale.getAvailableLocales(), since
            // there's no direct "3-letter to 2-letter" API on Locale itself.
            String lang2 = iso3ToIso1Language(parts[0]);
            if (lang2 == null) {
                // Unrecognised as a 3-letter ISO-639-2 code with a 2-letter
                // equivalent - user-confirmed real case: "yue" (Cantonese) has no
                // ISO-639-1 2-letter code at all, so iso3ToIso1Language("yue")
                // legitimately returns null, and this used to just "continue" (skip
                // the whole entry), silently dropping Cantonese from the list even
                // though Google TTS genuinely had it installed (visible in logcat:
                // "Download of yue-hk started" / "Download yue-hk Success true").
                // BCP-47 (and Java's Locale) both accept 3-letter primary language
                // subtags directly for exactly this situation (IANA's language
                // subtag registry lists "yue" itself as a valid primary subtag) - so
                // fall back to using the 3-letter code as-is rather than dropping the
                // language. new Locale("yue","HK").toLanguageTag() correctly yields
                // "yue-HK".
                lang2 = parts[0];
            }
            String country2 = null;
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                country2 = iso3ToIso1Country(parts[1]);
                if (country2 == null) {
                    // Same reasoning as the language fallback above - keep the raw
                    // 3-letter country code rather than dropping it, since Locale/
                    // BCP-47 both accept a 3-letter region subtag too (it just won't
                    // be an ISO-3166-1 alpha-2 code, but it's still meaningful).
                    country2 = parts[1];
                }
            }
            Locale locale = (country2 != null) ? new Locale(lang2, country2) : new Locale(lang2);
            String tag = locale.toLanguageTag();
            if (options.containsKey(tag)) continue;
            // getDisplayName(displayLocale) is exactly what Android's own "設定 > 語言"
            // picker uses to build human-readable names (see the screenshot: "中文
            // (中國)", "丹麥文 (丹麥)" etc are this API's own output, not a hand-picked
            // label) - user-confirmed on real hardware that a hand-maintained JS-side
            // tag->name table (the previous approach) covers maybe 40 languages out of
            // Google TTS's 60+ and silently leaves the rest showing as a raw code like
            // "ne"/"si"/"sk". Locale's own display-name machinery has the full data set
            // built in, so nothing gets missed and there's no table to keep in sync as
            // engines add more languages. displayLocale is TRADITIONAL_CHINESE or
            // ENGLISH depending on the Lynx UI's own language toggle (see
            // AndroidTtsHandler#listLanguages(uiLang)) - an earlier version hardcoded
            // SIMPLIFIED_CHINESE by mistake and produced simplified strings ("丹麦文",
            // "乌克兰文") inconsistent with this app's Traditional-Chinese UI.
            String displayName = locale.getDisplayName(displayLocale);
            if (displayName == null || displayName.isEmpty() || displayName.equals(tag)) {
                // getDisplayName() falls back to returning the tag itself when it has
                // no translation at all for a given subtag combination - extremely
                // rare (would need a language Java's own Locale data doesn't know
                // about by any name), but better to fall back to the raw tag visibly
                // than show an empty label.
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

    /** Lazily builds (once, cached in the static field) a reverse lookup from ISO-639-2
     *  3-letter language code to ISO-639-1 2-letter code, since java.util.Locale has no
     *  direct API for that direction - only the forward Locale.getISO3Language() from an
     *  already-2-letter Locale. Built off Locale.getAvailableLocales() (every Locale this
     *  JVM knows about), which covers the standard language set far more completely than
     *  hand-maintaining a table here would. */
    private static String iso3ToIso1Language(String iso3) {
        Map<String, String> map = iso3LanguageMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String lang2 = l.getLanguage();
                if (lang2.isEmpty()) continue;
                try {
                    String lang3 = l.getISO3Language();
                    // containsKey()+put() instead of putIfAbsent() - user-confirmed
                    // crash on real hardware: this device's Android version predates
                    // API 24 (Nougat), and Map.putIfAbsent() is a default method that
                    // only exists on the Map interface from API 24 onward (this app's
                    // own minSdkVersion is 19) - calling it threw NoSuchMethodError and
                    // took the whole app down. containsKey()+put() is the same "keep
                    // the first mapping seen" behaviour using only pre-Java-8/pre-API-24
                    // Map methods.
                    if (lang3 != null && !lang3.isEmpty() && !map.containsKey(lang3)) {
                        map.put(lang3, lang2);
                    }
                } catch (Exception ignored) {
                    // A handful of Locales throw MissingResourceException here - just
                    // means that particular one can't contribute a mapping, not a
                    // reason to abort building the rest of the table.
                }
            }
            iso3LanguageMap = map;
        }
        return map.get(iso3);
    }

    /** Same idea as iso3ToIso1Language() but for ISO-3166-1 alpha-3 country codes
     *  (e.g. "USA" -> "US"). */
    private static String iso3ToIso1Country(String iso3) {
        Map<String, String> map = iso3CountryMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String country2 = l.getCountry();
                if (country2.isEmpty()) continue;
                try {
                    String country3 = l.getISO3Country();
                    // See iso3ToIso1Language() above for why this isn't putIfAbsent().
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

    /** (Re)binds androidTts to a specific TTS engine and wires up the same
     *  OnInitListener/UtteranceProgressListener behaviour every time - called once from
     *  onCreate() with enginePackage=null (device default) and again from
     *  LynxController.AndroidTtsHandler#setEngine() whenever the Lynx UI switches
     *  engines. The old instance (if any) is stopped and shut down first, since Android
     *  has no API to rebind an existing TextToSpeech to a different engine in place -
     *  switching means tearing down and constructing a fresh one bound to the new
     *  engine's Service. androidTtsReady is set false for the duration of the rebind so
     *  speak() calls that land mid-switch fail fast (see AndroidTtsHandler#speak())
     *  instead of silently going to whichever instance happened to still be assigned. */
    private void initAndroidTts(String enginePackage) {
        TextToSpeech old = androidTts;
        androidTtsReady = false;
        if (old != null) {
            old.stop();
            old.shutdown();
        }
        // Holder so initListener can reference the instance being constructed even if
        // onInit() fires synchronously (before the constructor returns and "created"/
        // the androidTts field get assigned) - some OEM engines do call back inline on
        // failure rather than always posting asynchronously.
        final TextToSpeech[] holder = new TextToSpeech[1];
        TextToSpeech.OnInitListener initListener = status -> {
            androidTtsReady = (status == TextToSpeech.SUCCESS);
            if (androidTtsReady) {
                // Use the REQUESTED enginePackage, not getDefaultEngine() - user-
                // confirmed bug on real hardware: getDefaultEngine() reports the
                // device's system-wide default TTS engine (a Settings-level concept),
                // NOT "which engine this particular TextToSpeech instance is bound
                // to". After switching to Pico via the 3-arg constructor below,
                // getDefaultEngine() kept reporting com.google.android.tts (the
                // system default, unchanged) - so androidTtsEnginePkg silently stayed
                // wrong after every switch, and checkTtsDataSync() went on querying
                // the OLD engine's languages while the UI showed the NEW engine's name
                // (visible in logcat: ACTION_CHECK_TTS_DATA fired with
                // cmp=.../CheckVoiceData targeting com.google.android.tts right after
                // switching to com.svox.pico). If enginePackage is null (device-default
                // request, e.g. the very first init in onCreate()), fall back to
                // getDefaultEngine() since there's no explicit request to trust instead.
                androidTtsEnginePkg = (enginePackage != null && !enginePackage.isEmpty())
                        ? enginePackage
                        : (holder[0] != null ? holder[0].getDefaultEngine() : "");
            } else {
                // status == LANG_MISSING_DATA/ERROR usually means this engine has no
                // usable voice data on this device, or (if enginePackage was invalid)
                // the package doesn't exist / isn't a TTS engine - either way, this app
                // can't fix that without bundling engine/voice data itself.
                Log.e(TAG, "Android TTS init failed, status=" + status + ", engine="
                        + (enginePackage != null ? enginePackage : "(default)"));
            }
        };
        TextToSpeech created = (enginePackage != null && !enginePackage.isEmpty())
                ? new TextToSpeech(this, initListener, enginePackage)
                : new TextToSpeech(this, initListener);
        holder[0] = created;
        // Unlike onServerPlayEnd (robot-side TTS), Android system TTS reports per-
        // utterance completion only through this listener, not through onInit - needed
        // to know when to stop the mouth LED breathing effect started in speech/tts's
        // engine=android branch. "panel_tts"/"lynx_tts" are the utteranceIds passed to
        // speak() at their respective call sites; onStart/onDone/onError all fire on
        // whichever id is currently in flight since QUEUE_FLUSH means only one
        // utterance is ever in flight from this app at a time.
        created.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // no-op: the mouth LED is already started right before speak() is
                // called, not here, so it lights up without waiting for this callback's
                // round-trip.
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
    protected void onDestroy() {
        super.onDestroy();
        stopVolumeRepeat();
        setAccelerometerEnabled(false);
        TextToSpeech tts = androidTts; // snapshot - see initAndroidTts() javadoc on why
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
        stopRingtonePlayback();
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

    // -- API dispatch ----------------------------------------------------------------

    /**
     * Small namespace ("/api/system/...") kept for front-end backward compatibility.
     * This app now only supports the Lynx backend, so "backend/get" always reports
     * "lynx" and "backend/set" is a no-op success (avoids breaking any cached browser
     * tab that still calls it on load).
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

    /**
     * Answers plain-Android hardware endpoints ("camera/...", "accelerometer/...")
     * that go through neither LynxRobotApi nor any AIDL backend - the same physical
     * camera/accelerometer exist on this hardware regardless of firmware. Reached
     * either directly (no-prefix legacy path) or via LynxController's
     * sharedHardware fallback (see isSharedHardwarePath() there). Runs on an
     * HttpServer worker thread.
     */
    private HttpServer.ApiResponse handleSharedHardwareApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            // -- Head / misc ---------------------------------------------------------------

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
            // Plays the "Sirrah" shutter cue out of the robot's own speaker (see
            // playShutterCue() javadoc) - called by the browser right after a
            // successful camera/snapshot, instead of synthesizing a click sound in
            // the browser itself.
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
                // Block until the camera is genuinely released before answering - see
                // forceStopAndWait()'s javadoc for why stopIfIdle() alone isn't enough
                // here (it doesn't guarantee timing, just that it *will* close once idle).
                cameraController.forceStopAndWait(3000);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"requestedWidth\":" + w
                        + ",\"requestedHeight\":" + h + "}");
            }

            case "accelerometer/set": {
                final boolean on = Boolean.parseBoolean(require(query, "on"));
                // registerListener()/unregisterListener() must run on the thread that
                // owns sensorManager's Looper (the main thread here) - this handler
                // itself runs on an HttpServer worker thread, so hop over via mainHandler
                // and wait for it to actually apply before answering.
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
                        "{\"ok\":false,\"error\":\"unknown endpoint: " + path + "\"}");
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
    private void handleStream(String path, Map<String, String> query, java.net.Socket socket) throws java.io.IOException {
        if ("camera".equals(path)) {
            handleCameraStream(socket);
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

    /** Purple LED colour code, reused from Alpha2's old 5-mic obstacle-warning LED
     *  path for LynxRobotApi.led_turnOnEye/Head - see applyPurpleLedIndicator(). */
    private static final int OBSTACLE_LED_COLOR_PURPLE = 5;

    /** Solid purple eye+head LED while triggered, off otherwise - currently used only
     *  by registerChestMuteKeyTestListener() as a visual "did the chest mute-key
     *  broadcast actually fire" test signal (see that method's comment; the chest
     *  mute key itself has no LED of its own on stock firmware). Uses the same
     *  LynxRobotApi.led_turnOnEye/led_turnOnHead pair (and noopLedListener()) as the
     *  PIR alert path in applyPirLedAndSound(), just with a different colour, so the
     *  two features never fight over the LED hardware using different APIs. Mouth LED
     *  breathing is layered on top as an always-visible fallback in case the head
     *  board doesn't support the eye/head 5-mic-style LEDs on a given unit
     *  (MouthLedData is plain JNI, not AIDL, so it doesn't depend on whichever LED
     *  subsystem the eye/head call above resolves to). */
    private void applyPurpleLedIndicator(boolean triggered) {
        try {
            if (pirLedRobot == null) {
                pirLedRobot = new LynxRobotApi(getApplicationContext());
            }
            if (triggered) {
                pirLedRobot.led_turnOnEye(OBSTACLE_LED_COLOR_PURPLE, noopLedListener());
                pirLedRobot.led_turnOnHead(OBSTACLE_LED_COLOR_PURPLE, PIR_LED_BRIGHTNESS, noopLedListener());
            } else {
                pirLedRobot.led_turnOffEye(noopLedListener());
                pirLedRobot.led_turnOffHead(noopLedListener());
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyPurpleLedIndicator: eye/head LED path failed", t);
        }
        try {
            if (triggered) {
                MouthLedData.breathing(150).apply(); // fast breathing = "triggered" cue
            } else {
                MouthLedData.off().apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyPurpleLedIndicator: mouth LED fallback failed", t);
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
