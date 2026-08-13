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

import com.ubtechinc.alpha2ctrlapp.network.action.ClientAuthorizeListener;
import com.ubtechinc.alpha2robot.Alpha2RobotApi;
import com.ubtechinc.alpha2robot.constant.UbxErrorCode;
import com.ubtechinc.alpha2serverlib.aidlinterface.ASRRecord;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishOfflineUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IReplaySpeechCallback;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.lynxrobot.LynxRobotApi;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.constant.CustomLanguage;
import com.ubtechinc.constant.LanguageType;
import com.ubtechinc.constant.StaticValue;

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
 * Owns the one {@link Alpha2RobotApi} instance for the process, initialises every
 * sub-system the SDK exposes (action, chest serial, header serial, speech), and answers
 * every "/api/..." HTTP call from {@link HttpServer} by invoking the matching SDK method.
 * All asynchronous SDK callbacks (TTS end, action stop, ASR/grammar results) are pushed to
 * {@link EventBus} so the browser panel's WebSocket log updates live.
 *
 * The activity itself shows minimal on-device status (IP:port, init state) since the
 * robot has no practical on-screen use for this tool - the HTML control panel at
 * http://<robot-ip>:8888/ is the actual UI.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "MainActivity";
    private static final String APP_KEY = "222B998EDFA5FAD7FCE78678FB9F2521";

    // Which AIDL backend this robot was last told it is ("alpha2" 1.1.7.3 or "lynx"
    // 3.0.0.2) - see handleSystemApi(). Purely informational for the browser's default
    // tab selection; both Alpha2RobotApi and LynxController are always constructed and
    // reachable regardless of this value (see onCreate()), since the app itself never
    // gates which endpoints are callable - only the on-robot AIDL service being absent
    // does that, naturally, per call.
    private static final String PREFS_NAME = "robotpanel";
    private static final String PREF_BACKEND = "backend";
    private static final String DEFAULT_BACKEND = "alpha2";
    private static final String PREF_XIAOZHI_DEVICE_ID = "xiaozhi_device_id";

    private Alpha2RobotApi robot;
    private LynxController lynxController;
    private HttpServer httpServer;
    // 小智 (XiaoZhi) AI 對話 - 獨立於 alpha2/lynx AIDL 之外嘅 client-side WebSocket
    // 連線, 連出去 xiaozhi.me。單一 instance, 喺 onCreate() 先建立 (要用
    // getSharedPreferences() 攞/生成 device id, field initializer 嗰陣 Activity
    // context 未必 ready), 由 handleXiaozhiApi() 開關, 唔跟隨 backend 切換
    // (見 handleXiaozhiApi() 嘅 javadoc)。
    private XiaozhiClient xiaozhiClient;
    // PHASE 2: mic-capture-encode + decode-playback for XiaoZhi voice chat - separate
    // instance from audioController/audioPlaybackController below (different sample
    // rate/purpose/lifecycle, see XiaozhiAudioController's class javadoc). Constructed
    // eagerly (no Activity context needed, unlike xiaozhiClient) but only ever
    // start()ed from handleXiaozhiApi()'s "mic/start", gated on
    // XiaozhiClient.isAudioSupported().
    private final XiaozhiAudioController xiaozhiAudioController = new XiaozhiAudioController();
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

    /** true = 用戶開咗「持續搶 mic」呢個選項 (mic card 嗰粒 checkbox)。同
     *  micHeldByApp 唔同 - micHeldByApp 淨係記住「而家個狀態係咪 app 持有」,
     *  呢個 flag 就係話「就算 firmware 自己內部側面攞返咗 (例如 setWakeState
     *  呢個 call 本身喺 firmware bytecode 入面會順便觸發 IflytekWakeUp5mic.
     *  startRecording() 呢個 side effect - 唔係用戶自己撳咗「交返」), 都要
     *  自動再搶一次返嚟」。見 micHoldEnforcer 呢條背景 thread。 */
    private volatile boolean micHoldEnforced = false;
    private Thread micHoldEnforcerThread;
    private static final long MIC_HOLD_ENFORCER_INTERVAL_MS = 2000;

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
    // triggered=true 就即刻播, triggered=false 即刻停 (跟 sonar 個 purple LED 一樣, 唔
    // 等成首歌播完)。
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

    // Chest sonar trigger threshold in cm, as last set via servo/sonar. Assumption
    // (unverified on real hardware): chest_configureSonar()'s distance byte IS the
    // threshold in cm directly (0-100 fits a single byte with room to spare) - kept
    // here purely so the obstacle-triggered purple-LED logic below knows what
    // threshold is currently active, and so the front-end chart can draw it as a
    // reference line against live sonar readings.
    private volatile int sonarThresholdCm = 30;
    private volatile boolean sonarLedActive = false;

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

    // 2026-08 新增: RobotEventReceiver 冇 constructor/field 攞到 outer
    // MainActivity instance (佢一直淨係經 EventBus 靜態方法送 event, 唔識
    // MainActivity 本身), 但 sonar_obstacle 嘅 LED 指示邏輯 (applyObstacleIndicator,
    // sonarThresholdCm) 全部係 instance-level, 靠住 robot 呢個 AIDL 連線。加一個
    // static instance reference, 喺 onCreate/onDestroy set/clear, 等
    // RobotEventReceiver 可以經 MainActivity.getSonarThresholdCm() /
    // MainActivity.onSonarDistanceReceived() 呢兩個 static bridge 方法接駁返去
    // instance 邏輯, 而唔使將 RobotEventReceiver 個 constructor 簽名擴大 (咁樣會
    // 影響埋成個 registerDynamicReceiver() 個 new RobotEventReceiver() call 位)。
    private static volatile MainActivity sInstance;

    /** SONAR_DISTANCE_ACTION 觸發嘅 broadcast 未到之前, RobotEventReceiver 都要知
     *  依家個門檻先計到 "triggered"。冇 instance (例如 Activity 未起好/已destroy
     *  中間嗰段窗口) 就當冇門檻, 唔會誤判 triggered。 */
    static int getSonarThresholdCm() {
        MainActivity m = sInstance;
        return m != null ? m.sonarThresholdCm : 30;
    }

    /** RobotEventReceiver 收到 SONAR_DISTANCE_ACTION 之後嘅入口, 負責將
     *  distanceCm/triggered 接駁去 applyObstacleIndicator() (5-mic + mouth LED
     *  雙路徑, 見該方法 javadoc)。同 handleChestObstacleFrame() 一樣, 只喺
     *  triggered 狀態實際改變嗰下先重新驅動 LED, 避免每秒 ~1 幀嘅重複讀數不斷
     *  重送同一個 LED command。 */
    static void onSonarDistanceReceived(int distanceCm, boolean triggered) {
        MainActivity m = sInstance;
        if (m == null) {
            return;
        }
        if (triggered == m.sonarLedActive) {
            return;
        }
        m.sonarLedActive = triggered;
        m.applyObstacleIndicator(triggered);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        installCrashRestartHandler();

        registerDynamicReceiver();
        registerBatteryReceiver();
        registerGestureController();
        initRobot();
        xiaozhiClient = new XiaozhiClient(getXiaozhiDeviceId());
        // Lynx (3.0.0.2) side: unlike Alpha2RobotApi, LynxRobotApi needs no explicit
        // init/bind step (every subsystem binder is fetched lazily on first use), so
        // constructing this here is cheap and doesn't block onCreate() even on a robot
        // that's actually running Alpha2 firmware - it simply never resolves any
        // binder until/unless the browser UI is switched to the Lynx tab and calls it.
        //
        // Camera/audio-testtone/audio-volume calls are plain Android hardware access,
        // not implemented in either AIDL backend - same physical camera/mic/speaker
        // regardless of which robot SDK is selected - so LynxController defers those
        // to handleApi() (this method reference isn't invoked until the first actual
        // matching request, well after onCreate() finishes, so it's safe to bind here
        // even before cameraController/audioPlaybackController exist yet).
        // Lynx UI's TTS tab uses Android's own system TTS only (no robot-side engine
        // picker) - the handler below just forwards to androidTts, the very same
        // instance Alpha2's engine=android option uses, constructed right after this.
        // Safe to wire up here even though androidTts isn't assigned until the next
        // statement: speak()/stop() below only run later, in response to an actual
        // HTTP request, by which point onCreate() (and thus this assignment) has long
        // finished.
        lynxController = new LynxController(this, this::handleApi, new LynxController.AndroidTtsHandler() {
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
        // constructor overload removed) - walkie-talkie (which needs a secure context)
        // stays permanently disabled in the UI (see app-mic.js) and everything else works
        // reliably over plain HTTP/WS.
        String ip = getWifiIp();

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
                // Two independent backends share one HTTP server: "/api/alpha2/..." goes
                // to the original Alpha2RobotApi dispatch (handleApi, unchanged below),
                // "/api/lynx/..." goes to LynxController. "/api/system/..." is a small
                // backend-agnostic namespace (currently just which backend the browser
                // has selected) that isn't tied to either SDK.
                if (path.startsWith("alpha2/")) {
                    return handleApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("lynx/")) {
                    return lynxController.handle(path.substring(5), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return handleSystemApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("xiaozhi/")) {
                    return handleXiaozhiApi(path.substring(8), query, method, body);
                }
                // Back-compat: requests with no backend prefix (older cached browser
                // tab, or a client that hasn't picked a backend yet) fall through to
                // the original Alpha2 dispatch, matching this app's pre-Lynx behaviour.
                return handleApi(path, query, method, body);
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

        TextView footerView = new TextView(this);
        footerView.setTextSize(16);
        StringBuilder footerText = new StringBuilder();
        footerText.append("SDK version: 3.0.0.1");
        footerView.setText(footerText.toString());
        root.addView(footerView);

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
                Intent i = new Intent(StaticValue.ALPHA_SET_CHARGE_PLAY);
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
        filter.addAction(StaticValue.ALPHA_QR_CODE);
        filter.addAction(StaticValue.ALPHA_WIFI_RESULT);
        filter.addAction(StaticValue.ALPHA_BT_CONNECTION);
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
        filter.addAction(StaticValue.CHEST_ACTION);
        // 官方 alpha2demo.apk (firmware 1.1.1.14) 反編譯確認: sonar 讀數經呢個
        // 獨立 broadcast 送出, extra 已經係 parse 好嘅 int, 唔使自己再解 raw
        // wire frame。見 StaticValue.SONAR_DISTANCE_ACTION 個 comment。
        filter.addAction(StaticValue.SONAR_DISTANCE_ACTION);
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
                playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                if (robot != null) {
                    robot.action_StopAction();
                }
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

    private void initRobot() {
        // Subclassed (anonymous) rather than constructed plain, so the two
        // onListenSerialPort*RcvData() callbacks below are reachable. Alpha2RobotApi's
        // own default implementation of both is an empty no-op - the upstream SDK's own
        // HelloAlpha example overrides them the same way specifically to see whatever
        // raw bytes the chest/head boards send back (acks, error codes, sensor frames).
        // Every sendCommand() call in this app up to now was fire-and-forget with no
        // visibility into whether the head/chest board responded at all; these two
        // overrides close that blind spot by surfacing the raw hex to the Event Log.
        robot = new Alpha2RobotApi(this, APP_KEY, new ClientAuthorizeListener() {
            @Override
            public void onResult(int code, String info) {
                EventBus.get().publish("authorize", "{\"code\":" + code + ",\"info\":\"" + info + "\"}");
                Log.i(TAG, "Authorize result: " + code + " " + info);
            }
        }) {
            @Override
            public void onListenSerialPortHeaderRcvData(byte[] bytes, int len) {
                String hex = toHex(bytes, len);
                EventBus.get().publish("head_rcv", "{\"hex\":\"" + hex + "\"}");
            }

            @Override
            public void onListenSerialPortRcvData(byte[] bytes, int len) {
                String hex = toHex(bytes, len);
                EventBus.get().publish("chest_rcv", "{\"hex\":\"" + hex + "\"}");
                handleChestObstacleFrame(bytes, len);
            }

            @Override
            public void onListenBlueToothSerialPortRcvData(byte[] bytes, int len) {
                String hex = toHex(bytes, len);
                EventBus.get().publish("bt_rcv", "{\"hex\":\"" + hex + "\"}");
            }
        };

        robot.initActionApi(new AlphaActionClientListener() {
            @Override
            public void onActionStop(String strActionFileName) {
                EventBus.get().publish("action_stop", "{\"name\":\"" + jsonSafe(strActionFileName) + "\"}");
            }
        });

        robot.initChestSerialApi();
        robot.initHeaderSerialApi();
        robot.initBlueToothSerialApi();

        robot.initSpeechApi(new IAlpha2RobotClientListener() {
            @Override
            public void onServerCallBack(String text) {
                // Built-in ASR results arrive here, typically formatted as
                // "Local_Result:rule:... action:... tag:...". This is the robot's own
                // Nuance recogniser (wake word "hello alpha", hardware-gated - see
                // Alpha2OpenSdk-main HelloAlpha example) doing recognition AND intent
                // classification together; there is no separate NLU step for this path.
                // speech_understandText()/onTextUnderstand (used by the manual NLU tab)
                // is a different AIDL entry point that returns in ~1ms with no callback
                // firing on this firmware - it does not appear to reach a real engine,
                // matching HelloAlpha's own note that speech_initGrammar "compiles but
                // never reaches the active engine". This Local_Result path is the only
                // one confirmed working end-to-end.
                // NOTE: wakeup direction is NOT parsed here - it arrives via the separate
                // com.ubtechinc.services.SPEECH_DIRECTION broadcast, handled in
                // RobotEventReceiver, which is where the servo-19 turn is triggered.
                EventBus.get().publish("asr_result", "{\"text\":\"" + jsonSafe(text) + "\"}");
                String parsed = parseLocalResult(text);
                if (parsed != null) {
                    EventBus.get().publish("asr_intent", parsed);
                }
            }

            @Override
            public void onServerPlayEnd(boolean isEnd) {
                stopMouthLedForTts();
                EventBus.get().publish("tts_end", "{\"isEnd\":" + isEnd + "}");
            }
        }, new Alpha2SpeechMainServiceUtil.ISpeechInitInterface() {
            @Override
            public void initOver() {
                speechReady = true;
                EventBus.get().publish("speech_ready", "{\"ready\":true}");
            }
        }, CustomLanguage.DEFAULT_LANGUAGE);

        registerWakeupDirectionListener();
        registerPirAlertListener();
    }

    // -- Local_Result parsing (rule/action/tag intent classification) ------------------
    //
    // Format confirmed by Alpha2OpenSdk-main's HelloAlpha example:
    //   "Local_Result:rule:<RULE> action:<ACTION> tag:<recognised text>"
    // e.g. "Local_Result:rule:QA action:QA_Age tag:how old are you"
    // rule/action come from the robot's own on-device Nuance grammar - not something
    // this app defines or can extend (custom grammar via speech_initGrammar was tried
    // upstream and confirmed not to reach the active engine).
    private static final String LOCAL_RESULT_PREFIX = "Local_Result";

    private static String parseLocalResult(String s) {
        if (s == null || !s.startsWith(LOCAL_RESULT_PREFIX)) {
            return null;
        }
        String rule = fieldBetween(s, "rule:", " action:");
        String action = fieldBetween(s, "action:", " tag:");
        String tag = fieldBetween(s, "tag:", null);
        return "{\"rule\":\"" + jsonSafe(rule) + "\",\"action\":\"" + jsonSafe(action)
                + "\",\"tag\":\"" + jsonSafe(tag) + "\"}";
    }

    /** Extracts the substring between two markers. If end is null, reads to the end of
     *  the string. Returns "" (not null) if start marker isn't found, matching the
     *  permissive style HelloAlpha uses for this same parsing. */
    private static String fieldBetween(String s, String startMarker, String endMarker) {
        int i = s.indexOf(startMarker);
        if (i < 0) {
            return "";
        }
        int start = i + startMarker.length();
        int end = (endMarker == null) ? s.length() : s.indexOf(endMarker, start);
        if (end < 0) {
            end = s.length();
        }
        return s.substring(start, end).trim();
    }

    // -- Wakeup direction -> servo 19 (head) turn -------------------------------------
    // RobotEventReceiver already listens for the com.ubtechinc.services.SPEECH_DIRECTION
    // broadcast and publishes it to EventBus as {"type":"speech_direction",...,
    // "data":{"absoluteAngle":N}} (N already unsigned 0-255, see RobotEventReceiver).
    // This subscribes to that same EventBus feed - rather than adding a second
    // BroadcastReceiver - and does the actual servo turn. Per docs/sensors-and-events.md,
    // servo 19 is the head-yaw servo; on this unit its safe range is [75,165] with
    // home=120=facing forward. Mapping is 1:1, just clamped into that range.
    private static final String SPEECH_DIRECTION_MARKER = "\"type\":\"speech_direction\"";
    private static final int SERVO_HEAD_ID = 19;
    private static final int SERVO_HEAD_MIN = 75;
    private static final int SERVO_HEAD_MAX = 165;
    private static final short SERVO_TURN_TIME_MS = 500;

    private void registerWakeupDirectionListener() {
        EventBus.get().subscribe(new EventBus.Listener() {
            @Override
            public void onEvent(String line) {
                if (!line.contains(SPEECH_DIRECTION_MARKER)) {
                    return;
                }
                final Integer angle = extractAbsoluteAngle(line);
                if (angle == null) {
                    return;
                }
                // onEvent() runs on the main thread (broadcast receivers dispatch there by
                // default, and EventBus.publish() calls listeners synchronously from the
                // publisher's thread). waitChestReady()'s own javadoc requires a background
                // thread - its main-thread guard otherwise makes it a silent no-op.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int servoAngle = clampServoAngle(angle);
                        robot.waitChestReady(1000);
                        robot.chest_SendOneFreeAngle((byte) SERVO_HEAD_ID, servoAngle, SERVO_TURN_TIME_MS);
                    }
                }).start();
            }
        });
    }

    /** Pulls the integer after "absoluteAngle":  out of an EventBus-published JSON line,
     *  without pulling in a JSON library (matching the rest of this file's style). */
    private static Integer extractAbsoluteAngle(String line) {
        String key = "\"absoluteAngle\":";
        int i = line.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return null;
        try {
            return Integer.parseInt(line.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampServoAngle(int angle) {
        if (angle < SERVO_HEAD_MIN) return SERVO_HEAD_MIN;
        if (angle > SERVO_HEAD_MAX) return SERVO_HEAD_MAX;
        return angle;
    }

    // -- Lynx PIR alert: 頭/眼 LED 長開紅燈 + Heaven 鈴聲 --------------------------------
    // RobotEventReceiver.java 監聽 com.ubtechinc.services.Action.PIR_STATE 呢個 broadcast
    // (機身真身 PIR 偵測通知, 唔經 AIDL - 詳見 docs/AIDL_GUIDE_LYNX.md「5. Sys」章節同
    // RobotEventReceiver 嗰個 case 嘅 comment), 發返 EventBus 嘅 "pir_state" event。
    // 呢度同 registerWakeupDirectionListener() 一樣, 訂閱返嗰個 event feed (唔係再開
    // 多一個 BroadcastReceiver)。triggered=true 一到即刻長開 (常亮, 唔閃) 紅色頭+眼
    // LED, 同時播 Heaven 鈴聲; triggered=false 一到即刻熄燈同停聲 (唔再轉綠燈 - 淨係
    // 熄, 因為紅燈係「警示」, 冇偵測嗰陣唔需要另一個常亮顏色標示狀態) - 唔等成首鈴聲
    // 播完, 跟 sonar 個 purple LED (setHeadEyeLedLong()/handleChestObstacleFrame())
    // 一樣即停即停嘅做法。呢個反應受 pirAlertEnabled 呢個獨立開關控制 (見 index.html
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
        if (sInstance == this) {
            sInstance = null;
        }
        stopVolumeRepeat();
        stopMicHoldEnforcer();
        micHeldByApp = false;
        setAccelerometerEnabled(false);
        TextToSpeech tts = androidTts; // snapshot - see initAndroidTts() javadoc on why
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (gestureListener != null) {
            EventBus.get().unsubscribe(gestureListener);
        }
        if (xiaozhiClient != null) {
            xiaozhiClient.disconnect();
        }
        xiaozhiAudioController.shutdown();
        if (httpServer != null) {
            httpServer.stop();
        }
        if (robot != null) {
            robot.releaseApi();
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
     * Routes "/api/<name>" calls to the matching Alpha2RobotApi method. Runs on an
     * HttpServer worker thread (not the main thread) - every SDK call used here is safe
     * to invoke off the main thread (the *ServiceUtil classes only marshal Binder calls),
     * matching how the SDK's own AGENTS.md describes bind/call safety.
     */
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
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\""
                        + jsonSafe(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .getString(PREF_BACKEND, DEFAULT_BACKEND)) + "\"}");
            case "backend/set": {
                String backend = require(query, "backend");
                if (!"alpha2".equals(backend) && !"lynx".equals(backend)) {
                    return HttpServer.ApiResponse.error("backend must be 'alpha2' or 'lynx'");
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_BACKEND, backend).apply();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\"" + backend + "\"}");
            }
            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown system endpoint: " + path + "\"}");
        }
    }

    // ---------------- 小智 (XiaoZhi) AI 對話 ----------------
    //
    // Backend-agnostic namespace ("/api/xiaozhi/...") - same reasoning as "system/":
    // AI對話 doesn't belong to either Alpha2 or Lynx's own AIDL surface, and its MCP
    // tool bridge (see xiaozhiMcpBridge() below) reads whichever backend is currently
    // selected (PREF_BACKEND) at call time, rather than needing its own separate
    // connection state per backend. See XiaozhiClient's class javadoc for the overall
    // protocol/phase-1-scope explanation.
    private HttpServer.ApiResponse handleXiaozhiApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "supported":
                // Reported separately from "connected" state so the browser UI can grey
                // out/hide the audio (Opus) controls specifically without also hiding
                // text-only chat, once Phase 2 adds the audio path. Phase 1 has no audio
                // path yet, so audioSupported here is purely advisory for the UI to
                // pre-render around, not yet backed by an actual codec.
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"sdkInt\":" + Build.VERSION.SDK_INT + ","
                        + "\"audioSupported\":" + XiaozhiClient.isAudioSupported() + "}");

            case "status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"connected\":" + xiaozhiClient.isOpen() + ","
                        + "\"sessionId\":" + (xiaozhiClient.getSessionId() != null
                                ? "\"" + jsonSafe(xiaozhiClient.getSessionId()) + "\"" : "null") + "}");

            case "connect": {
                String wsUrl = require(query, "url");
                String token = require(query, "token");
                if (xiaozhiClient.isOpen()) {
                    return HttpServer.ApiResponse.error("already connected - call xiaozhi/disconnect first");
                }
                xiaozhiClient.setMcpBridge(xiaozhiMcpBridge());
                // PHASE 2: wires XiaozhiAudioController as the sink for incoming Opus
                // binary frames - set here (not just once at construction) so a
                // reconnect after disconnect() re-establishes the sink cleanly rather
                // than depending on it having survived from a previous session.
                xiaozhiClient.setAudioSink(new XiaozhiClient.AudioSink() {
                    @Override
                    public void onIncomingOpusFrame(byte[] opusData) {
                        xiaozhiAudioController.onIncomingOpusFrame(opusData);
                    }
                });
                // Connects synchronously on this request-handling thread (HttpServer
                // already runs each request on its own pool thread - see
                // Executors.newCachedThreadPool() in HttpServer - so this doesn't block
                // any other in-flight request) and blocks up to XiaozhiClient's own
                // 10s hello-timeout before answering, so the browser gets a definite
                // connected/failed result instead of having to poll xiaozhi/status.
                try {
                    xiaozhiClient.connect(wsUrl, token);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"sessionId\":\""
                            + jsonSafe(xiaozhiClient.getSessionId()) + "\"}");
                } catch (java.io.IOException e) {
                    return HttpServer.ApiResponse.error("connect failed: " + e.getMessage());
                }
            }

            case "disconnect":
                // Tear down any in-progress mic/speaker session first - an open
                // XiaozhiAudioController capture thread holding the mic across a
                // WebSocket disconnect would otherwise leak the mic open with nowhere
                // for encoded frames to go (sendAudioFrame() would just throw
                // "not connected" repeatedly until the next mic/stop).
                xiaozhiAudioController.stopCapture();
                xiaozhiAudioController.stopPlayback();
                xiaozhiClient.disconnect();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");

            case "mic/start": {
                if (!XiaozhiClient.isAudioSupported()) {
                    return HttpServer.ApiResponse.error("voice chat is not supported on this Android version (requires 5.0/API 21+)");
                }
                if (!xiaozhiClient.isOpen()) {
                    return HttpServer.ApiResponse.error("not connected - call xiaozhi/connect first");
                }
                try {
                    xiaozhiClient.sendListenStart();
                } catch (java.io.IOException e) {
                    return HttpServer.ApiResponse.error("failed to signal listen-start: " + e.getMessage());
                }
                // Playback is started alongside capture (not lazily on first incoming
                // frame) so the AudioTrack is already open and prebuffering by the time
                // the server's reply audio starts arriving - opening it reactively on
                // the first onIncomingOpusFrame() would add a full AudioTrack-init
                // delay (which AudioPlaybackController's own findings show can matter)
                // to the very start of the robot's reply.
                XiaozhiAudioController.StartResult playbackResult =
                        xiaozhiAudioController.startPlayback(5000);
                if (playbackResult.error != null) {
                    return HttpServer.ApiResponse.error("failed to start playback: " + playbackResult.error);
                }
                XiaozhiAudioController.StartResult captureResult =
                        xiaozhiAudioController.startCapture(new XiaozhiAudioController.EncodedFrameSink() {
                            @Override
                            public void onEncodedFrame(byte[] opusData) throws java.io.IOException {
                                xiaozhiClient.sendAudioFrame(opusData);
                            }
                        }, 5000);
                if (captureResult.error != null) {
                    xiaozhiAudioController.stopPlayback();
                    return HttpServer.ApiResponse.error("failed to start mic capture: " + captureResult.error);
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "mic/stop": {
                xiaozhiAudioController.stopCapture();
                xiaozhiAudioController.stopPlayback();
                if (xiaozhiClient.isOpen()) {
                    try {
                        xiaozhiClient.sendListenStop();
                    } catch (java.io.IOException e) {
                        // Not fatal - the mic/AudioTrack are already released above
                        // regardless of whether this final courtesy message reaches
                        // the server (e.g. the connection may have just dropped).
                        Log.w("MainActivity", "Failed to signal listen-stop: " + e.getMessage());
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown xiaozhi endpoint: " + path + "\"}");
        }
    }

    /** Stable per-install device identifier for the "Device-Id" handshake header -
     *  xiaozhi-esp32's own firmware uses the device's MAC address here, but this robot
     *  has no single canonical MAC exposed at the Android app layer worth depending on
     *  (WiFi MAC is unreliable/randomized on modern Android and this app targets
     *  API 19+), so a random UUID generated once and persisted in SharedPreferences is
     *  used instead - it just needs to be *stable across app restarts*, not tied to any
     *  particular piece of hardware. */
    private String getXiaozhiDeviceId() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existing = prefs.getString(PREF_XIAOZHI_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String generated = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(PREF_XIAOZHI_DEVICE_ID, generated).apply();
        return generated;
    }

    /** Builds the MCP bridge XiaozhiClient uses to answer tools/list and tools/call.
     *  Reads PREF_BACKEND at call time (not cached) so a backend switch takes effect on
     *  the very next MCP request without needing to reconnect the XiaoZhi session.
     *
     *  PHASE 1 SCOPE: exposes a deliberately small, safe starter set of tools
     *  (play a named action, stop action playback, speak via TTS) rather than the full
     *  AIDL surface from AIDL_REFERENCE.md - MCP tool calls originate from a remote LLM
     *  the operator doesn't directly control turn-by-turn, so starting narrow and
     *  expanding later (once real usage patterns are seen) is safer than exposing
     *  everything (LED raw params, serial port raw commands, etc.) up front. */
    private XiaozhiClient.McpBridge xiaozhiMcpBridge() {
        return new XiaozhiClient.McpBridge() {
            @Override
            public org.json.JSONObject listTools() throws org.json.JSONException {
                org.json.JSONArray tools = new org.json.JSONArray();

                org.json.JSONObject playAction = new org.json.JSONObject();
                playAction.put("name", "self.robot.play_action");
                playAction.put("description", "Play a named built-in robot action/animation.");
                org.json.JSONObject playActionSchema = new org.json.JSONObject();
                playActionSchema.put("type", "object");
                org.json.JSONObject playActionProps = new org.json.JSONObject();
                org.json.JSONObject nameProp = new org.json.JSONObject();
                nameProp.put("type", "string");
                nameProp.put("description", "Action name as returned by the robot's action list.");
                playActionProps.put("name", nameProp);
                playActionSchema.put("properties", playActionProps);
                playActionSchema.put("required", new org.json.JSONArray().put("name"));
                playAction.put("inputSchema", playActionSchema);
                tools.put(playAction);

                org.json.JSONObject stopAction = new org.json.JSONObject();
                stopAction.put("name", "self.robot.stop_action");
                stopAction.put("description", "Stop whatever action is currently playing.");
                org.json.JSONObject stopActionSchema = new org.json.JSONObject();
                stopActionSchema.put("type", "object");
                stopActionSchema.put("properties", new org.json.JSONObject());
                stopAction.put("inputSchema", stopActionSchema);
                tools.put(stopAction);

                org.json.JSONObject speak = new org.json.JSONObject();
                speak.put("name", "self.robot.speak");
                speak.put("description", "Speak a short phrase out loud through the robot's TTS.");
                org.json.JSONObject speakSchema = new org.json.JSONObject();
                speakSchema.put("type", "object");
                org.json.JSONObject speakProps = new org.json.JSONObject();
                org.json.JSONObject textProp = new org.json.JSONObject();
                textProp.put("type", "string");
                textProp.put("description", "Text to speak.");
                speakProps.put("text", textProp);
                speakSchema.put("properties", speakProps);
                speakSchema.put("required", new org.json.JSONArray().put("text"));
                speak.put("inputSchema", speakSchema);
                tools.put(speak);

                org.json.JSONObject result = new org.json.JSONObject();
                result.put("tools", tools);
                result.put("nextCursor", "");
                return result;
            }

            @Override
            public org.json.JSONObject callTool(String name, org.json.JSONObject arguments) throws org.json.JSONException {
                boolean isError = false;
                String resultText = "";
                try {
                    switch (name) {
                        case "self.robot.play_action": {
                            String actionName = arguments.optString("name", "");
                            if (actionName.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: name";
                                break;
                            }
                            UbxErrorCode.API_ERROR_CODE code = robot.action_PlayActionName(actionName);
                            isError = !isOk(code);
                            resultText = String.valueOf(code);
                            break;
                        }
                        case "self.robot.stop_action": {
                            UbxErrorCode.API_ERROR_CODE code = robot.action_StopAction();
                            isError = !isOk(code);
                            resultText = String.valueOf(code);
                            break;
                        }
                        case "self.robot.speak": {
                            String text = arguments.optString("text", "");
                            if (text.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: text";
                                break;
                            }
                            // Mirrors the "speech/tts" HTTP endpoint below (handleApi()) -
                            // same STOP_TO_TTS_MIN_GAP_MS race guard against a just-issued
                            // speech/stop, same mouth-LED bracket, same 3-arg
                            // speech_startTTS(lang, text, voice) signature (Alpha2RobotApi
                            // exposes no high-priority/interrupting TTS variant, so this
                            // shares the low-priority entry point the rest of the app uses).
                            // Fixed to Nuance/en_us rather than reading an "engine" query
                            // param (no query string here, this is an MCP tool call) -
                            // consistent with defaulting away from iFlytek's per-call voice
                            // picker, which has no equivalent argument in this tool's schema.
                            long sinceStopMs = System.currentTimeMillis() - lastSpeechStopAtMs;
                            if (sinceStopMs >= 0 && sinceStopMs < STOP_TO_TTS_MIN_GAP_MS) {
                                try {
                                    Thread.sleep(STOP_TO_TTS_MIN_GAP_MS - sinceStopMs);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            startMouthLedForTts();
                            UbxErrorCode.API_ERROR_CODE code = robot.speech_startTTS("en_us", text, null);
                            if (!isOk(code)) {
                                stopMouthLedForTts();
                            }
                            isError = !isOk(code);
                            resultText = String.valueOf(code);
                            break;
                        }
                        default:
                            isError = true;
                            resultText = "unknown tool: " + name;
                            break;
                    }
                } catch (Exception e) {
                    isError = true;
                    resultText = "tool execution threw: " + e.getMessage();
                }

                org.json.JSONArray content = new org.json.JSONArray();
                org.json.JSONObject textBlock = new org.json.JSONObject();
                textBlock.put("type", "text");
                textBlock.put("text", resultText);
                content.put(textBlock);

                org.json.JSONObject result = new org.json.JSONObject();
                result.put("content", content);
                result.put("isError", isError);
                return result;
            }
        };
    }

    private HttpServer.ApiResponse handleApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"sdkVersion\":\"" + Alpha2RobotApi.getSdkVersion() + "\","
                        + "\"chestAvailable\":" + isOk(robot.isChestAvailable()) + ","
                        + "\"headerAvailable\":" + isOk(robot.isHeaderAvailable()) + ","
                        + "\"speechReady\":" + speechReady + ","
                        + "\"currentAsrEngine\":\"" + currentAsrEngine + "\","
                        + "\"androidTtsReady\":" + androidTtsReady + "}");

            // -- Actions --------------------------------------------------------------
            case "action/list":
                return actionList();
            case "action/play":
                return codeResponse(robot.action_PlayActionName(require(query, "name")));
            case "action/play_file":
                return codeResponse(robot.action_PlayActionFile(require(query, "file")));
            case "action/stop":
                return codeResponse(robot.action_StopAction());
            case "action/disable":
                return codeResponse(robot.action_DisableActionPlay(Boolean.parseBoolean(require(query, "disable"))));
            case "action/is_actioning":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"isActioning\":" + robot.action_IsActioning() + "}");
            case "action/trigger_event": {
                // nEventType/param semantics are unverified against real hardware (see
                // AIDL_REFERENCE_ALPHA2.md) - this endpoint just passes the caller's values
                // through as-is rather than assuming any interpretation. param is
                // optional and taken as base64 (matching this codebase's existing
                // convention for raw bytes over the query string, e.g. camera JPEG
                // frames); omitted param sends an empty byte array.
                int eventType = Integer.parseInt(require(query, "event_type"));
                String paramB64 = queryOrDefault(query, "param_base64", "");
                byte[] param = paramB64.isEmpty()
                        ? new byte[0]
                        : android.util.Base64.decode(paramB64, android.util.Base64.DEFAULT);
                return codeResponse(robot.action_TriggerEventHandler(eventType, param));
            }

            // -- Speech / TTS -----------------------------------------------------------
            // engine: nuance | iflytek | android. voice only applies to iflytek (its
            // named voices - catherine/john/xiaofeng/xiaoyan); nuance and android use
            // their own respective default voice, no selection exposed.
            //
            // All robot-side speech goes through the single generic "SpeechServices"
            // binding (robot.speech_startTTS). This firmware only ever routes that alias
            // to one underlying engine, so which engine actually speaks is fixed by the
            // robot itself, not by this dropdown - the "engine" query param only steers
            // the language/voice hint passed to that same engine. Multi-engine direct
            // binding (Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER /
            // ALPHA_IFLYTEK_SPEECH_MAIN_SERVER) was tried and reverted: it broke playback
            // entirely, including for the engine that worked fine through the generic
            // binding alone.
            case "speech/tts": {
                String text = require(query, "text");
                String engine = queryOrDefault(query, "engine", "nuance");
                if ("android".equals(engine)) {
                    if (androidTts == null || !androidTtsReady) {
                        return HttpServer.ApiResponse.error("Android TTS not ready");
                    }
                    startMouthLedForTts();
                    androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "panel_tts");
                    return HttpServer.ApiResponse.ok("{\"ok\":true}");
                }
                String voice = "iflytek".equals(engine) ? query.get("voice") : null; // may be null
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
                startMouthLedForTts();
                UbxErrorCode.API_ERROR_CODE res = robot.speech_startTTS(lang, text, voice);
                if (!isOk(res)) {
                    // speech_startTTS failed synchronously - onServerPlayEnd will never
                    // fire for this attempt, so nothing will turn the mouth LED back off
                    // unless we do it here.
                    stopMouthLedForTts();
                }
                return codeResponse(res);
            }
            case "speech/stop":
                robot.speech_StopTTS();
                lastSpeechStopAtMs = System.currentTimeMillis();
                if (androidTts != null) {
                    androidTts.stop();
                }
                stopMouthLedForTts();
                return codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);
            case "speech/set_mic": {
                boolean wake = Boolean.parseBoolean(require(query, "wake"));
                robot.speech_SetMIC(wake);
                // 記住呢個狀態，等 handleMicStream() 斷線時知道用戶係咪透過 TTS
                // tab 主動要求長期持有 mic - 見 micHeldByApp 個 field javadoc。
                micHeldByApp = wake;
                // 用戶手動交返俾機械人 (wake=false) 就自動閂埋「持續搶 mic」,
                // 唔係就 enforcer 兩秒之後又會將 mic 搶返嚟, 用戶個「交返」動作
                // 會好似冇效咁樣, 好confusing。
                if (!wake && micHoldEnforced) {
                    stopMicHoldEnforcer();
                }
                EventBus.get().publish("mic_state",
                        "{\"held\":" + micHeldByApp + ",\"keepHeld\":" + micHoldEnforced + "}");
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"held\":" + micHeldByApp
                        + ",\"keepHeld\":" + micHoldEnforced + "}");
            }
            case "speech/set_mic_keep_held": {
                boolean keep = Boolean.parseBoolean(require(query, "keep"));
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
            case "speech/set_asr_engine": {
                // 2026-08 新增: 之前 ASR 只有一條路 —— 一開機用通用嘅
                // ALPHA_SPEECH_MAIN_SERVER 別名綁定, 由機身韌體自己決定實際
                // route 去 Nuance 定 iFlytek, app 冇得指定。之前得出「iFlytek
                // 唔係 active engine」呢個結論, 其實只係喺 TTS 側測試過 (英文
                // 母語測試者, 冇用中文/iFlytek 專屬 grammar 試過), 從未喺
                // ASR (initSpeechApi 呢條 receive listener 路徑) 度用直接
                // binding 試過。而家用 speech_switchEngine() 做 runtime
                // 重新綁定, 等呢邊都可以直接指定 Nuance/iFlytek 嚟試, 唔再
                // 假設淨係得 Nuance work。
                //
                // engine=nuance -> ALPHA_NUANCE_SPEECH_MAIN_SERVER
                // engine=iflytek -> ALPHA_IFLYTEK_SPEECH_MAIN_SERVER
                // 語言提示同 engine 對應 (iflytek -> zh_cn, nuance -> en_us),
                // 但呢個提示本身係 advisory, 唔保證真係切換到嗰種語言嘅
                // grammar —— 呢點都係之前未經 iFlytek 直接測試證實嘅假設,
                // 而家淨係跟返 speech/tts 個既有慣例做預設, 實際會唔會
                // work 要重新綁定後實測 asr_result 先知。
                String engine = queryOrDefault(query, "engine", "nuance");
                String action = "iflytek".equals(engine)
                        ? Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER
                        : Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER;
                CustomLanguage lang = "iflytek".equals(engine) ? CustomLanguage.CHINESE_MANDARIN : CustomLanguage.UNITED_STATES_ENGLISH;
                speechReady = false;
                EventBus.get().publish("speech_ready", "{\"ready\":false}");
                boolean accepted = robot.speech_switchEngine(new Alpha2SpeechMainServiceUtil.ISpeechInitInterface() {
                    @Override
                    public void initOver() {
                        speechReady = true;
                        currentAsrEngine = engine;
                        EventBus.get().publish("speech_ready", "{\"ready\":true}");
                        EventBus.get().publish("asr_engine_switched", "{\"engine\":\"" + engine + "\"}");
                    }
                }, lang, action);
                if (!accepted) {
                    // mRobotClient 未設低 (即係未曾成功 initSpeechApi() 過) -
                    // 呢個情況理論上唔會發生 (initSpeechApi 喺 onServiceStart
                    // 已經行咗), 但保守處理返
                    return HttpServer.ApiResponse.error("Speech API not yet initialised - restart app");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\"" + engine + "\"}");
            }
            case "speech/reset": {
                // 2026-08 新增: 試驗性嘅「重置」入口。實測 (logcat_2026-08-27_05-39-04.txt)
                // 證實: 撳咗上面 speech/set_asr_engine 之後, 機身系統進程
                // (com.ubtechinc.alpha2services) 入面嘅 TTS session 就會啞 —— HTTP
                // 層仍然回 200 API_ERROR_SUCCEED, 但完全冇再見到 SpeechServiceImpl/
                // IflytekTTS/onTTsStart 呢啲 log, 一直要重開機先返到正常。
                //
                // 呢個 endpoint call AIDL transaction #12 stopSpeechAndEnterIdleMode(),
                // 睇下叫唔叫得返個死咗嘅 session, 唔使成部機重開機。特登用
                // Alpha2RobotApi.speech_resetToIdle() (行 generic alias binding), 唔係
                // 行嗰條已經壞死嘅 direct-engine binding。未喺真機驗證過呢個方法係咪
                // 真係解決到問題, 純粹跟 AIDL 方法名同用途做嘅合理推測 —— 如果冇效,
                // 都仲係要重開機。
                boolean ok = robot.speech_resetToIdle();
                return ok
                        ? HttpServer.ApiResponse.ok("{\"ok\":true}")
                        : HttpServer.ApiResponse.error("Speech API not yet initialised - restart app");
            }
            case "speech/start_asr":
                // Starts recognition directly - doesn't require the mic-array hardware to
                // detect its own wake word first (unlike speech/set_mic, which only claims/
                // releases mic ownership and never itself starts listening). Results still
                // arrive as the usual "asr_result" WebSocket event.
                //
                // 2026-08 修正: 呢個係整個 API 入面觸發 ASR 最主要嘅入口 (見
                // AIDL_REFERENCE_ALPHA2.md 1.1 - startSpeechNoWakeup 先係真正可靠嘅「開始聆聽」
                // 方法), 但之前一直冇好似 speech/inject/speech/stop_inject 咁加
                // speechReady gate。即係話啱啱切換完 ASR engine (speechReady 短暫變
                // false, 等緊 onSpeechInitSuccess) 嗰陣撞正撳呢個 endpoint, 會攞到
                // 同 speech/inject 講嗰種一樣含糊嘅 API_ERROR_NOT_INIT, 而唔係清晰嘅
                // 錯誤訊息。而家補返個 gate, 同 speech/inject 睇齊。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_asr_engine) before calling speech/start_asr.");
                }
                return codeResponse(robot.speech_startSpeechNoWakeup());
            case "speech/set_voice":
                return codeResponse(robot.speech_setVoiceName(require(query, "name")));
            case "speech/set_language":
                return codeResponse(robot.speech_setRecognizedLanguage(require(query, "lang")));
            case "speech/self_interrupt":
                return codeResponse(robot.speech_setSelfInterrupt(Boolean.parseBoolean(require(query, "on"))));
            case "speech/understand":
                // Pure NLU: sends text straight to the robot's semantic-understanding
                // engine, bypassing ASR/microphone entirely. Result (or error) arrives
                // asynchronously via the "text_understand" WebSocket event below - this
                // call itself only reports whether the request was accepted.
                return codeResponse(robot.speech_understandText(require(query, "text"),
                        new IAlpha2RobotTextUnderstandListener() {
                            @Override
                            public void onAlpha2UnderStandTextResult(String result) {
                                EventBus.get().publish("text_understand",
                                        "{\"ok\":true,\"result\":\"" + jsonSafe(result) + "\"}");
                            }

                            @Override
                            public void onAlpha2UnderStandError(int errorCode) {
                                EventBus.get().publish("text_understand",
                                        "{\"ok\":false,\"errorCode\":" + errorCode + "}");
                            }
                        }));
            case "speech/inject":
                // "Pretend I heard this" - injects text via the AIDL onSpeech() dictation
                // path (Alpha2RobotApi.speech_startRecognized(), marked @Deprecated
                // upstream with no logged reason found). Untested on this firmware: may
                // reach the same local Nuance grammar that real speech does (in which
                // case a QA_* phrase from the reference list below would trigger a
                // Local_Result the normal way, on the EXISTING "asr_result"/"asr_intent"
                // events - no new event added here on purpose), or may be as dead as
                // speech_understandText. This call only reports whether the SDK accepted
                // the request, not whether recognition actually fired.
                //
                // 2026-08 新增: 之前呢度冇 speechReady gate，如果啱啱切換完 engine
                // (speechReady 短暫變返 false，等緊 onSpeechInitSuccess callback)
                // 就直接落去 SDK call，好大機會兩個 util 都仲係 null，攞到含糊嘅
                // API_ERROR_NOT_INIT，而唔係好似 speech/reset 咁清晰嘅錯誤訊息。
                // 而家加返個 gate，同 speech/init_grammar 嗰種做法睇齊。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_asr_engine) before calling speech/inject.");
                }
                return codeResponse(robot.speech_startRecognized(require(query, "text")));
            case "speech/stop_inject":
                // Companion to speech/inject (onStopSpeech). Untested, same caveats.
                // 同上，加返 speechReady gate。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event before calling "
                                    + "speech/stop_inject.");
                }
                return codeResponse(robot.speech_stopRecognized());
            case "speech/init_grammar":
                // initSpeechGrammar() - sets up a restricted-vocabulary grammar.
                //
                // 2026-08 修正: 之前呢個 comment 講「confirmed NOT to reach the active
                // engine on this firmware family」——呢個結論唔完整。反編譯
                // Alpha2Services-v1.1.7.3.20 嘅 classes.dex 證實咗真相係:
                // NuanceServiceImpl.initSpeechGrammar()/startSpeechGrammar() 兩個都係
                // 完全未實作嘅空 stub (method body 淨係一句 return-void), 但
                // IflytekServiceImpl 嘅同名 method 有真身實作, 會真正建立
                // com.iflytek.cloud.SpeechRecognizer。即係話之前試呢個 API 冇反應,
                // 唔係「呢個機身用唔到 grammar」, 而係當時個 speech binding 一直行緊
                // Nuance (通用 ALPHA_SPEECH_MAIN_SERVER action 喺呢部機實測落嚟預設
                // route 去 Nuance), 撞正個空 stub。而家加返呢個 guard: 如果而家未切去
                // iFlytek, 直接擋住唔俾個必然落空嘅 call 發生, 話俾用家知要先
                // speech/set_asr_engine?engine=iflytek。
                //
                // strGrammar 嘅期望格式 (JSON? 純文字詞彙表? 逗號分隔?) 喺
                // IflytekServiceImpl 反編譯出嚟嘅 code 見到淨係被存做一個字串, 再傳入
                // SpeechRecognizer 嘅初始化流程, 確實嘅語法格式未反查到, 呢度暫時
                // 原樣透傳畀你哋自己試。
                // Init completion (with a grammarId or error code) arrives async via the
                // "grammar_init" WebSocket event below.
                if (!"iflytek".equals(currentAsrEngine)) {
                    return HttpServer.ApiResponse.error(
                            "Grammar recognition is a no-op stub under the Nuance binding "
                                    + "(confirmed via decompilation - NuanceServiceImpl's grammar methods are "
                                    + "empty). Call speech/set_asr_engine?engine=iflytek first, wait for "
                                    + "speech_ready, then retry.");
                }
                return codeResponse(robot.speech_initGrammar(require(query, "grammar"),
                        new IAlpha2SpeechGrammarInitListener() {
                            @Override
                            public void speechGrammarInitCallback(String grammarId, int errorCode) {
                                EventBus.get().publish("grammar_init",
                                        "{\"grammarId\":\"" + jsonSafe(grammarId) + "\",\"errorCode\":" + errorCode + "}");
                            }
                        }));
            case "speech/start_grammar":
                // startSpeechGrammar() - begins grammar-restricted recognition using
                // whatever was set up by init_grammar. Results/errors arrive async via
                // the "grammar_result" WebSocket event below.
                //
                // 同 init_grammar 一樣嘅 guard 理由: Nuance 呢邊係空 stub。
                if (!"iflytek".equals(currentAsrEngine)) {
                    return HttpServer.ApiResponse.error(
                            "Grammar recognition is a no-op stub under the Nuance binding. "
                                    + "Call speech/set_asr_engine?engine=iflytek first, wait for speech_ready, "
                                    + "then retry.");
                }
                return codeResponse(robot.speech_startGrammar(new IAlpha2SpeechGrammarListener() {
                    @Override
                    public void onSpeechGrammarResult(int type, String result) {
                        EventBus.get().publish("grammar_result",
                                "{\"ok\":true,\"type\":" + type + ",\"result\":\"" + jsonSafe(result) + "\"}");
                    }

                    @Override
                    public void onSpeechGrammarError(int errorCode) {
                        EventBus.get().publish("grammar_result",
                                "{\"ok\":false,\"errorCode\":" + errorCode + "}");
                    }
                }));
            case "speech/stop_grammar":
                return codeResponse(robot.speech_stopGrammar());
            case "speech/register_english_understand":
                // Registers for online English NLU results (ISpeechInterface #15).
                // Unverified against real hardware - what triggers a result, and
                // under what conditions, is unknown; this just wires the callback
                // through to the "english_understand" WebSocket event.
                return codeResponse(robot.speech_onEnglishUnderstand(new IAlphaEnglishUnderstandListener.Stub() {
                    @Override
                    public void onAlpha2EnglishUnderstandResult(String strResult) {
                        EventBus.get().publish("english_understand",
                                "{\"result\":\"" + jsonSafe(strResult) + "\"}");
                    }
                }));
            case "speech/register_english_offline_understand":
                // Offline counterpart of the above (ISpeechInterface #16). Same
                // caveats apply.
                return codeResponse(robot.speech_setEnglishOfflineListener(new IAlphaEnglishOfflineUnderstandListener.Stub() {
                    @Override
                    public void onAlpha2EnglishOfflineUnderstandResult(String strResult) {
                        EventBus.get().publish("english_understand_offline",
                                "{\"result\":\"" + jsonSafe(strResult) + "\"}");
                    }
                }));
            case "speech/register_replay_content":
                // Registers for replayed ASR history records (ISpeechInterface #22,
                // see AIDL_REFERENCE_ALPHA2.md 1.7 for ASRRecord's field provenance).
                // extra1/extra2 are forwarded as-is under their placeholder names -
                // their real semantics are unconfirmed on this hardware.
                return codeResponse(robot.speech_registerReplayContentListener(new IReplaySpeechCallback.Stub() {
                    @Override
                    public void onRelpayContent(ASRRecord record) {
                        EventBus.get().publish("asr_replay", "{"
                                + "\"recordId\":\"" + jsonSafe(record.getRecordId()) + "\","
                                + "\"msgLanguage\":\"" + jsonSafe(record.getMsgLanguage()) + "\","
                                + "\"content\":\"" + jsonSafe(record.getContent()) + "\","
                                + "\"contentLinks\":\"" + jsonSafe(record.getContentLinks()) + "\","
                                + "\"labelId\":" + record.getLabelId() + ","
                                + "\"extra1\":\"" + jsonSafe(record.getExtra1()) + "\","
                                + "\"extra2\":\"" + jsonSafe(record.getExtra2()) + "\""
                                + "}");
                    }
                }));

            // -- Servos -----------------------------------------------------------------
            case "servo/one": {
                robot.waitChestReady(3000);
                byte id = Byte.parseByte(require(query, "id"));
                int angle = Integer.parseInt(require(query, "angle"));
                short time = Short.parseShort(queryOrDefault(query, "time", "1000"));
                return codeResponseReady(robot.chest_SendOneFreeAngle(id, angle, time), robot.isChestReady());
            }
            case "servo/all": {
                robot.waitChestReady(3000);
                String anglesCsv = require(query, "angles"); // 20 comma-separated ints
                short time = Short.parseShort(queryOrDefault(query, "time", "1000"));
                String[] parts = anglesCsv.split(",");
                int[] angles = new int[20];
                for (int i = 0; i < 20 && i < parts.length; i++) {
                    angles[i] = Integer.parseInt(parts[i].trim());
                }
                return codeResponseReady(robot.chest_SendFreeAngle(angles, time), robot.isChestReady());
            }
            case "servo/sonar": {
                robot.waitChestReady(3000);
                int distanceCm = Integer.parseInt(require(query, "distance"));
                sonarThresholdCm = distanceCm;
                sonarLedActive = false; // threshold changed - next frame decides fresh, don't carry over stale LED state
                return codeResponseReady(robot.chest_configureSonar(distanceCm), robot.isChestReady());
            }

            // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
            // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
            //   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
            //   brightness: 1 (dimmest) .. 9 (brightest)
            //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
            //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
            case "led/head/set": {
                robot.waitHeaderReady(3000);
                String preset = queryOrDefault(query, "preset", "long");
                if ("stop".equals(preset)) {
                    return codeResponseReady(robot.header_stop5MicEarLED(), robot.isHeaderReady());
                }
                int color = Integer.parseInt(require(query, "color"));
                int brightness = Integer.parseInt(require(query, "brightness"));
                int p5, p6, p8;
                switch (preset) {
                    case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
                    case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
                    case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
                    case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
                    case "long":
                    default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
                }
                return codeResponseReady(
                        robot.header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8),
                        robot.isHeaderReady());
            }
            case "led/eye/set": {
                robot.waitHeaderReady(3000);
                String preset = queryOrDefault(query, "preset", "long");
                if ("stop".equals(preset)) {
                    return codeResponseReady(robot.header_stop5MicEyeLED(), robot.isHeaderReady());
                }
                int color = Integer.parseInt(require(query, "color"));
                int brightness = Integer.parseInt(require(query, "brightness"));
                int p5, p6, p8;
                switch (preset) {
                    case "flash": p5 = 100; p6 = 100; p8 = 0; break;
                    case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
                    case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
                    case "long":
                    default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
                }
                return codeResponseReady(
                        robot.header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8),
                        robot.isHeaderReady());
            }
            // NOTE: unlike led/head/set and led/eye/set above, this does NOT go through
            // Alpha2RobotApi/AIDL at all - there is no AIDL "mouth LED" method. It calls
            // com.ubtechinc.mic5.LedControl directly (a native JNI class backed by
            // libhead_led.so), a completely separate control path found in a different
            // demo app, not gated by isHeaderReady()/waitHeaderReady() since it has
            // nothing to do with the header serial AIDL bind. See MouthLedData's
            // javadoc for the confirmed field semantics and the same-device-contention
            // caveat before relying on this alongside led/head/set or led/eye/set.
            //
            // Simplified to the two effects confirmed usable on this hardware: a
            // breathing effect (speed adjustable, 0-5000ms) and off. effectMode values
            // other than 1 produced no light in testing, so there's no third "always
            // solid, no breathing" preset here - see README for what was tried. Also
            // triggered automatically around TTS start/end - see startMouthLedForTts()/
            // stopMouthLedForTts() below and their call sites in speech/tts,
            // onServerPlayEnd, and the Android TTS UtteranceProgressListener.
            case "led/mouth/set": {
                if ("off".equals(queryOrDefault(query, "preset", ""))) {
                    boolean ok = MouthLedData.off().apply();
                    return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
                }
                int speed = Integer.parseInt(queryOrDefault(query, "speed", "0"));
                boolean ok = MouthLedData.breathing(speed).apply();
                return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
            }

            // -- Head / misc ---------------------------------------------------------------
            case "head/noise":
                return codeResponse(robot.header_setNoise(Boolean.parseBoolean(require(query, "on"))));
            case "misc/request_uuid":
                robot.requestRobotUUID();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");

            // -- Serial: raw AIDL passthrough (chest/head sendRawData bypass sendCommand's
            // frame encapsulation entirely; Bluetooth serial is a separate, Bluetooth-backed
            // link with no chest/head hardware behind it - see AIDL_REFERENCE_ALPHA2.md 3.1/3.3).
            // Every payload here is base64 (this codebase's existing convention for raw
            // bytes over the query string, e.g. camera JPEG frames / action/trigger_event).
            case "serial/chest/send_raw":
                return codeResponse(robot.chest_sendRawData(
                        android.util.Base64.decode(require(query, "data_base64"), android.util.Base64.DEFAULT)));
            case "serial/header/send_raw":
                return codeResponse(robot.header_sendRawData(
                        android.util.Base64.decode(require(query, "data_base64"), android.util.Base64.DEFAULT)));
            case "serial/header/serial_number": {
                String serial = robot.header_getRobotSerialNumber();
                return HttpServer.ApiResponse.ok("{\"ok\":" + (serial != null)
                        + ",\"serialNumber\":\"" + jsonSafe(serial) + "\"}");
            }
            case "bluetooth/send_command": {
                byte cmd = Byte.parseByte(require(query, "cmd"));
                String paramB64 = queryOrDefault(query, "param_base64", "");
                byte[] param = paramB64.isEmpty()
                        ? new byte[0]
                        : android.util.Base64.decode(paramB64, android.util.Base64.DEFAULT);
                return codeResponse(robot.bluetooth_sendCommand(cmd, param));
            }
            case "bluetooth/send_at":
                return codeResponse(robot.bluetooth_sendATCMD(require(query, "cmd")));

            // -- Camera: standard Android legacy Camera API, not SDK-gated (see
            // CameraController for the front/back index quirk on this hardware). The
            // live feed itself is served at GET /stream/camera (see handleStream()) as
            // MJPEG, not through this JSON api/ path - a continuous multipart response
            // doesn't fit the single-JSON-body ApiResponse shape. This single-frame
            // snapshot endpoint just starts the camera (if it isn't already streaming)
            // and returns whatever the most recent preview frame is, for callers that
            // want one still image rather than opening the stream. -----------------------
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

            // -- Walkie-talkie: browser mic -> robot speaker. See AudioPlaybackController's
            // javadoc - whether the speaker is reachable via a standard AudioTrack at all
            // is unverified; this test-tone endpoint exists to answer that on the physical
            // unit before relying on the real streaming path (POST /upload/audio) below.
            case "audio/testtone": {
                releaseMicForAudioIo();
                AudioPlaybackController.StartResult result =
                        audioPlaybackController.playTestTone(3000);
                if (result.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe(result.error) + "\"}");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/diagnose": {
                releaseMicForAudioIo();
                String sweep = audioPlaybackController.diagnoseAudioTrack(10000);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"results\":\""
                        + jsonSafe(sweep).replace("\n", "\\n") + "\"}");
            }
            case "audio/play/start": {
                releaseMicForAudioIo();
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

            // -- System ringtones/notification sounds: exposes every ringtone Android
            // knows about (via RingtoneManager, same mechanism findRingtoneByTitle()
            // above already uses to look up "Proxima"/"Sirrah" by name) as a numbered
            // list, so the Blockly page can offer a dropdown without hardcoding titles
            // that vary by OEM/Android version. "list" returns titles+type; "play"
            // takes the numbered index back and plays it through the same STREAM_MUSIC
            // MediaPlayer path as playRingtoneUri() (so it follows the media volume
            // slider, not the separate ringer/notification volume). -------------------
            case "audio/ringtones/list": {
                String type = queryOrDefault(query, "type", "ringtone");
                int rmType = "notification".equals(type)
                        ? android.media.RingtoneManager.TYPE_NOTIFICATION
                        : android.media.RingtoneManager.TYPE_RINGTONE;
                // 2026-08 更新 (修 bug): 改用 getCachedRingtoneManager() 唔再逐次
                // new RingtoneManager 即用即棄 —— 見 findRingtoneByTitle() 上面
                // 嗰個 cache function 嘅 javadoc, 呢度係同一種 cursor 洩漏, 一齊修。
                android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
                android.database.Cursor cursor = manager.getCursor();
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"type\":\"" + jsonSafe(type) + "\",\"sounds\":[");
                int position = 0;
                boolean first = true;
                while (cursor.moveToNext()) {
                    String title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"index\":").append(position).append(",\"title\":\"")
                            .append(jsonSafe(title == null ? "" : title)).append("\"}");
                    position++;
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            case "audio/ringtones/play": {
                String type = queryOrDefault(query, "type", "ringtone");
                int index = Integer.parseInt(require(query, "index"));
                int rmType = "notification".equals(type)
                        ? android.media.RingtoneManager.TYPE_NOTIFICATION
                        : android.media.RingtoneManager.TYPE_RINGTONE;
                // 2026-08 更新 (修 bug): 同上, 改用 cached manager。
                android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
                android.net.Uri uri;
                try {
                    uri = manager.getRingtoneUri(index);
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"invalid index\"}");
                }
                if (uri == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
                }
                playRingtoneUri(uri);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 新增: 用 title 揾鈴聲, 唔再用 audio/ringtones/list 個 numbered
            // index (見上面 findRingtoneByTitle() 嘅 javadoc: cursor position 唔保證
            // 跨機一致, 因為 RingtoneManager 內部排序邏輯唔一定同 adb content query
            // 手動加 --sort 果個排序一樣)。Blockly 頁依家內嵌一份靜態 title 清單
            // (由實機 adb content query 走一次抓返嚟, 見 blockly-actions-data.js
            // 隔籬嘅 blockly-ringtone-data.js), 揀咗個 title 直接送呢個 API, 用返
            // findRingtoneByTitle() 呢個已經俾 playStopCue()/playShutterCue() 用緊、
            // 驗證過穩陣嘅「查 title 過 Uri」機制, 完全唔使理 index 排序呢個問題。
            case "audio/ringtones/play_by_title": {
                String type = queryOrDefault(query, "type", "ringtone");
                String title = require(query, "title");
                int rmType = "notification".equals(type)
                        ? android.media.RingtoneManager.TYPE_NOTIFICATION
                        : android.media.RingtoneManager.TYPE_RINGTONE;
                android.net.Uri uri = findRingtoneByTitle(title, rmType);
                if (uri == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
                }
                playRingtoneUri(uri);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 新增: 停止依家播緊嘅系統鈴聲/通知聲 (play / play_by_title 兩個
            // endpoint 播嗰個), 對應 Blockly「例子 5」個「停止播放」掣。
            case "audio/ringtones/stop": {
                stopRingtonePlayback();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // -- Media volume: STREAM_MUSIC, same stream the +/- gesture buttons and
            // the walkie-talkie/TTS playback all use (see registerGestureController()/
            // startVolumeRepeat() above) - so this slider and the physical +/- pads
            // stay in sync with each other. -------------------------------------------
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

            // -- Battery/charging: NOT in Alpha2RobotApi (see capabilities.md); read via
            // the standard Android BatteryManager broadcast this Activity already listens
            // for and caches. ------------------------------------------------------------
            case "battery/status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"level\":" + lastBatteryLevel + ","
                        + "\"scale\":" + lastBatteryScale + ","
                        + "\"charging\":" + lastBatteryCharging + ","
                        + "\"status\":\"" + lastBatteryStatus + "\"}");

            // -- Wi-Fi / Bluetooth: standard Android framework, not SDK-gated. -----------
            case "wifi/status":
                return wifiStatus();
            case "bt/status":
                return btStatus();

            // -- Robot-service broadcasts with simple boolean extras. --------------------
            case "misc/charge_play": {
                boolean open = Boolean.parseBoolean(require(query, "open"));
                Intent i = new Intent(StaticValue.ALPHA_SET_CHARGE_PLAY);
                i.putExtra("open_charge_play", open);
                sendBroadcast(i);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "misc/power_save": {
                boolean save = Boolean.parseBoolean(require(query, "save"));
                Intent i = new Intent(StaticValue.ALPHA_SEND_POWER_SAVE);
                i.putExtra("should_save_power", save);
                sendBroadcast(i);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // -- Accelerometer (IMU): standard Android SensorManager, not SDK-gated -
            // see setAccelerometerEnabled()/onSensorChanged() above. Readings stream out
            // as "accel" WebSocket events while enabled, not through this JSON response. -
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

            // -- Service config (/sdcard/actions/service_config.{json,txt}) -------------
            //
            // 2026-08 新增。呢個 config 檔控制緊機身開機時嘅 wake word/ASR 語言/預設對話
            // app (見 AIDL_REFERENCE_ALPHA2.md「引擎選擇」段落) —— 實測證實 (見 log) 改咗呢個
            // 檔案、重開機之後，wake word 真係會跟住轉。
            //
            // 兩個關鍵限制，呢組 API 圍住嚟設計:
            // 1. 呢個係外部儲存嘅普通檔案 (/sdcard, 唔係 app 私有目錄), targetSdkVersion 22
            //    唔使 runtime permission, manifest 已有 WRITE_EXTERNAL_STORAGE, 讀寫本身
            //    冇障礙。
            // 2. 改完必須重開機先生效 (實測: alpha2services 只喺開機嗰陣讀一次, 冇監聽緊
            //    檔案改動), 所以 set 呢個 endpoint 淨係負責寫檔, 唔會嘗試呃人話「即時生效」；
            //    重開機要用戶自己另外揀「reboot after set」或者之後手動用 service_config/reboot。
            //
            // 淨係支援兩個 preset (cn/en)，兩個都係機身出廠內置嘅原裝 default config
            // (分別對應 aaservice_config.json 同 service_config.json 呢兩份出廠檔案)，
            // 一字不改地照抄，唔係自己砌出嚟嘅組合——兩個都係原廠已知安全嘅設定，所以
            // 唔設「還原」掣，亦都唔做寫入前備份 (兩個 preset 之間可以隨時互相切換，
            // 冇「損壞」呢個概念)。
            case "service_config/get":
                return serviceConfigGet();
            case "service_config/set": {
                String preset = require(query, "preset");
                boolean reboot = Boolean.parseBoolean(queryOrDefault(query, "reboot", "false"));
                return serviceConfigSet(preset, reboot);
            }
            case "service_config/reboot":
                // 獨立出嚟做一個 endpoint, 等用戶可以「set 完先睇下寫啱未, 之後先至
                // reboot」，唔一定要一步到位。
                return systemReboot();

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown endpoint: " + path + "\"}");
        }
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

    /** Same "long" (solid, always-on) LED effect as led/head/set & led/eye/set's
     *  preset=long, but callable directly server-side without an HTTP round-trip.
     *  Used by releaseMicForAudioIo() to set the mic-listening cue LED *after*
     *  speech_SetMIC(true) has actually taken effect - see that method's javadoc for
     *  why ordering here matters (alpha2services' own setWakeState(true) broadcasts
     *  a LED_ACTION that turns the ear LED back off as a side effect, racing against
     *  whatever this app just set). */
    private void setHeadEyeLedLong(int color, int brightness) {
        robot.waitHeaderReady(3000);
        robot.header_ledSetHead5Mic(color, brightness, 31, 31, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        robot.header_ledSetEye5Mic(color, brightness, 255, 255, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }

    /** 2026-08 新增: 呢部機 (head board / firmware 1.1.1.14) 嘅
     *  header_ledSetHead5Mic/header_ledSetEye5Mic 實測全部 preset 都回
     *  API_ERROR_FAILED (bindReady:true, 即係唔係未 ready, 係機身真係唔支援/
     *  冇實作 - 睇落呢個機頭唔係 5-mic variant, 或者呢個 firmware 冇實作呢兩個
     *  AIDL 方法)。Mouth LED (MouthLedData, 直接 JNI 唔經 AIDL) 就實測正常。
     *
     *  呢個方法將 obstacle-triggered 嘅 LED 指示同時發去兩條路: 5-mic
     *  head/eye (setHeadEyeLedLong) 照舊保留 - 喺支援嘅機/firmware 上會着紫燈,
     *  喺呢部機上頂多係 API_ERROR_FAILED、冇視覺效果、但唔會拋例外中斷流程;
     *  同時亦閃 mouth LED 做 fallback, 保證呢部機都見到嘢。兩條路獨立 try/catch,
     *  其中一條失敗唔會擋另一條。 */
    private void applyObstacleIndicator(boolean triggered) {
        try {
            if (triggered) {
                setHeadEyeLedLong(5, 9); // 5 = 紫 (purple), see led/head/set color-code comment
            } else {
                robot.header_stop5MicEarLED();
                robot.header_stop5MicEyeLED();
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
     *  2026-08 更新: 實機 (firmware 1.1.1.14) 證實呢個 0x81 幀假設完全冇撞中 -
     *  sonar 讀數根本唔會經 IAlpha2SerialPortService 嘅 AIDL rcv callback 送到,
     *  onListenSerialPortRcvData() 淨係收到 app 自己送出 chest_configureSonar()
     *  嗰個 config command 嘅 2-byte ack "04 00"。中途一度誤以為 sonar 讀數會
     *  經 "com.ubtechinc.services.chest" (StaticValue.CHEST_ACTION) 呢個全域
     *  broadcast 重新發送, 但反編譯官方 UBTech alpha2demo.apk 之後證實呢個都
     *  係錯 - CHEST_ACTION 官方 demo 自己都淨係用嚟 log 機身內部 raw command
     *  byte (見 RobotEventReceiver 個 CHEST_ACTION case), 唔係 sonar 讀數。
     *  真正嘅 sonar 讀數係經另一個獨立、之前完全冇診斷到嘅 broadcast action
     *  "com.ubtechinc.sonar.distance" (StaticValue.SONAR_DISTANCE_ACTION) 送出,
     *  extra 已經係 parse 好嘅 int (key "sonar_distance",
     *  StaticValue.SONAR_DISTANCE_EXTRA), 唔使自己再解 raw wire frame - 見
     *  RobotEventReceiver 嗰個 SONAR_DISTANCE_ACTION case 同
     *  MainActivity#onSonarDistanceReceived()。而且就算 0x81 幀真係經 AIDL
     *  path 到, 實測 raw wire frame 都係 "f8 8f 0a 00 00 8b eb 04 81 05 ed" -
     *  0x81 出現喺幀中間 (index 8), 唔係 bytes[0], 所以呢度原本嘅
     *  offset 假設連框架格式都對唔上, 唔止係「呢部機唔行呢條路」咁簡單。
     *  呢個方法連同佢個 0x81 假設保留低唔刪 - 留返俾第啲機身/firmware 版本,
     *  如果真係會送 0x81-開頭嘅 AIDL rcv 幀, 呢條路徑先有意義；喺呢部機上佢
     *  單純唔會撞到 (cmd 恒等於 4, 喺 "cmd != -127" 嗰行提早 return), 唔影響
     *  真正生效嗰條 SONAR_DISTANCE_ACTION 路徑。 */
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
        applyObstacleIndicator(triggered);
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
     * Speech tab's manual "釋放麥克風俾 App" button), not "false".
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
     * 著,有時唔著" (sometimes lit, sometimes not) - a pure race, not a code bug in the
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
     *  確保就算 firmware 內部側面攞返咗 mic (例如 setWakeState 本身喺
     *  firmware bytecode 入面會順便觸發 IflytekWakeUp5mic.startRecording()
     *  呢個 side effect - 見 AIDL_REFERENCE_ALPHA2.md「⚠️ 重要行為」段), app
     *  都會好快搶返嚟, 唔使等用戶自己發現支 mic 靜咗先手動再撳一次。
     *
     *  用獨立 thread + sleep 而唔係靠 handleMicStream() 個 loop, 係因為兩者
     *  用途唔同: handleMicStream() 淨係喺有人真係開緊 /stream/mic 先行, 而
     *  呢個 enforcer 係只要用戶喺 mic card 開咗個「持續搶 mic」掣, 就算冇人
     *  開緊 mic stream 都要生效 (例如淨係想用 TTS, 但唔想俾機械人自己嘅
     *  wake-word 引擎不時搶返支 mic)。 */
    private void startMicHoldEnforcer() {
        if (micHoldEnforcerThread != null) return;
        micHoldEnforced = true;
        micHoldEnforcerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (micHoldEnforced && !Thread.currentThread().isInterrupted()) {
                    if (micHeldByApp) {
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
        setHeadEyeLedLong(2, 9); // 綠燈長開 - 聽緊機械人講嘢, 一定要喺上面果行之後先叫,
                                 // 見 releaseMicForAudioIo() javadoc 解釋點解順序好重要

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
            boolean wasLastListener = audioController.hasNoListeners();
            audioController.stopIfIdle();
            if (wasLastListener) {
                // Give the mic back to alpha2services' own wake-word engine now that
                // nobody is listening to the mic stream - otherwise voice wakeup would
                // stay silently disabled until someone went to the Speech tab and
                // manually re-enabled it, same as it used to require to enable it.
                // false = "交返麥克風俾機器人" (hand back to the robot), matching
                // setMic(false) in app-speech.js - true is the opposite, "release to app".
                //
                // 例外: 如果用家喺 TTS tab 撳咗「釋放麥克風俾 App」(micHeldByApp),
                // 就代表佢想長期由 app 持有 mic - 呢個 stream 斷開 (背景化分頁/
                // 網絡短暫中斷都會觸發呢個 finally) 唔應該將 mic 靜靜哋還俾機械人,
                // 否則個「釋放」狀態就會被呢度無聲蓋走, 要用家自己再撳一次先頂到住。
                if (!micHeldByApp) {
                    robot.speech_SetMIC(false);
                }
                // Safety net: turn the green "listening" LED back off here too, not
                // just relying on the browser's stopMicListen() sending preset=stop -
                // if this stream connection just drops (backgrounded tab, network
                // blip, browser closed) rather than being stopped via the button, the
                // browser-side call never happens and the LED would otherwise stay
                // stuck on indefinitely.
                robot.waitHeaderReady(3000);
                robot.header_stop5MicEarLED();
                robot.header_stop5MicEyeLED();
            }
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

    private HttpServer.ApiResponse actionList() {
        // action_getActionList is asynchronous (Binder round-trip); block this worker
        // thread briefly with a latch-style wait rather than making the HTTP layer async.
        final Object[] resultHolder = new Object[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        UbxErrorCode.API_ERROR_CODE started = robot.action_getActionList(new IAlpha2ActionListListener() {
            @Override
            public void onGetActionList(ArrayList<ArrayList<String>> list) {
                resultHolder[0] = list;
                latch.countDown();
            }
        });

        if (started != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            return codeResponse(started);
        }
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        @SuppressWarnings("unchecked")
        ArrayList<ArrayList<String>> list = (ArrayList<ArrayList<String>>) resultHolder[0];
        // 2026-08 debug: action/list 響應空 actions[] 但機身 /sdcard/actions/*.ubx
        // 實際有 ~140 個檔。可能係 (a) latch timeout, onGetActionList 冇喺 5s 內
        // callback, list 保持 null, 或者 (b) 機身確實有 callback 返 list, 但每行
        // < 4 欄, 全部俾下面嘅 "row.size() < 4" 跳晒。呢兩種情況分開 log 先分辨到
        // 邊個先係真正原因。
        if (list == null) {
            Log.w(TAG, "actionList: onGetActionList did not complete within 5s latch (list == null)");
        } else {
            Log.d(TAG, "actionList: got " + list.size() + " row(s)");
            for (int i = 0; i < list.size(); i++) {
                ArrayList<String> row = list.get(i);
                if (row.size() < 4) {
                    Log.w(TAG, "actionList: row " + i + " skipped, size=" + row.size()
                            + " content=" + row);
                }
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"actions\":[");
        if (list != null) {
            // Bug fix: "if (i > 0) sb.append(',')" used the *list index* as the
            // "already emitted something" check. When an earlier row is skipped
            // (row.size() < 4, see above), the first row that IS emitted still has
            // i > 0 and gets a leading comma anyway -> malformed JSON "[,{...}".
            // Track whether anything has actually been appended instead.
            boolean firstEmitted = true;
            for (int i = 0; i < list.size(); i++) {
                ArrayList<String> row = list.get(i);
                if (row.size() < 4) continue;
                if (!firstEmitted) sb.append(',');
                firstEmitted = false;
                sb.append("{\"id\":\"").append(jsonSafe(row.get(0))).append("\",")
                        .append("\"type\":\"").append(jsonSafe(row.get(1))).append("\",")
                        .append("\"nameCn\":\"").append(jsonSafe(row.get(2))).append("\",")
                        .append("\"nameEn\":\"").append(jsonSafe(row.get(3))).append("\"}");
            }
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
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

    private static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    private static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    /**
     * Same as codeResponse but also reports whether the underlying chest/header AIDL
     * bind had actually completed (isChestReady()/isHeaderReady()) at the time of the
     * call - not just that the *ServiceUtil object was constructed. See
     * Alpha2RobotApi.waitChestReady/waitHeaderReady javadoc for why this distinction
     * matters: API_ERROR_SUCCEED alone doesn't guarantee the command reached the robot.
     */
    private static HttpServer.ApiResponse codeResponseReady(UbxErrorCode.API_ERROR_CODE code, boolean ready) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code
                + "\",\"bindReady\":" + ready + "}");
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
