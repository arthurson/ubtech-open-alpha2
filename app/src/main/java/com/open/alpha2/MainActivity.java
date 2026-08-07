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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.constant.CustomLanguage;
import com.ubtechinc.constant.LanguageType;
import com.ubtechinc.constant.StaticValue;

import java.util.ArrayList;
import java.util.Map;
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

    private Alpha2RobotApi robot;
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

    private volatile boolean speechReady = false;
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
    private TextToSpeech androidTts;
    private volatile boolean androidTtsReady = false;

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
        initRobot();
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
        lynxController = new LynxController(this, this::handleApi);
        androidTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                androidTtsReady = (status == TextToSpeech.SUCCESS);
                if (!androidTtsReady) {
                    // status == LANG_MISSING_DATA/ERROR usually means this Android build has
                    // no system TTS engine installed at all (common on robot firmware images
                    // that ship only the robot's own Nuance/iFlytek speech services) - not
                    // something this app can fix without bundling a TTS engine APK.
                    Log.e(TAG, "Android TTS init failed, status=" + status
                            + " (likely no system TTS engine installed on this device)");
                }
            }
        });
        // Unlike onServerPlayEnd (robot-side TTS), Android system TTS reports per-
        // utterance completion only through this listener, not through onInit - needed
        // to know when to stop the mouth LED breathing effect started in speech/tts's
        // engine=android branch. "panel_tts" is the utteranceId passed to speak() there;
        // onStart/onDone/onError all fire on this same id since QUEUE_FLUSH means only
        // one utterance is ever in flight from this app at a time.
        androidTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // no-op: the mouth LED is already started in speech/tts right before
                // speak() is called, not here, so it lights up without waiting for this
                // callback's round-trip.
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

        // Plain HTTP only. TLS/HTTPS was tried (self-signed cert) to make getUserMedia()
        // available for the walkie-talkie mic feature, but browsers on this device
        // repeatedly rejected new TLS connections after the very first page load with
        // "SSLHandshakeException: Handshake failed / certificate unknown" (see logcat
        // from 2017-01-01 session) - each new WebSocket/keep-alive connection re-runs
        // the TLS handshake and the self-signed cert's trust exception did not reliably
        // carry over, so the WebSocket feed (accel, uuid, wakeup, etc.) dropped
        // intermittently even though the HTTP API calls themselves succeeded. Rather
        // than fight browser cert-trust behavior, TLS is disabled outright: walkie-talkie
        // (which needs a secure context) is disabled in the UI - see app.js - and
        // everything else works reliably over plain HTTP/WS.
        String ip = getWifiIp();
        javax.net.ssl.SSLServerSocketFactory tlsFactory = null;

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
        }, tlsFactory);
        httpServer.start();
        String scheme = httpServer.isTlsActive() ? "https" : "http";

        // The on-device screen does NOT mirror the HTML control panel via WebView -
        // that path had unreliable CSS/JS rendering on this device's WebView build (blank/
        // broken layout, buttons stuck disabled). Per this class's original design intent,
        // the HTML panel at http(s)://<robot-ip>:8888/ is the actual UI; the on-device
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
        // retype a long https://<ip>:8888/ address by hand on the robot's own screen.
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
        if (httpServer.isTlsActive()) {
            footerText.append("(Self-signed cert - browser will warn \"not private\";\n")
                    .append("click Advanced -> Proceed. Needed for the mic/\n")
                    .append("walkie-talkie feature to work.)\n\n");
        }
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
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.services.SPEECH_DIRECTION");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction(StaticValue.ALPHA_QR_CODE);
        filter.addAction(StaticValue.ALPHA_WIFI_RESULT);
        filter.addAction(StaticValue.ALPHA_BT_CONNECTION);
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
        };

        robot.initActionApi(new AlphaActionClientListener() {
            @Override
            public void onActionStop(String strActionFileName) {
                EventBus.get().publish("action_stop", "{\"name\":\"" + jsonSafe(strActionFileName) + "\"}");
            }
        });

        robot.initChestSerialApi();
        robot.initHeaderSerialApi();

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVolumeRepeat();
        setAccelerometerEnabled(false);
        if (androidTts != null) {
            androidTts.stop();
            androidTts.shutdown();
        }
        if (gestureListener != null) {
            EventBus.get().unsubscribe(gestureListener);
        }
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
            case "action/stop":
                return codeResponse(robot.action_StopAction());

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
                if (androidTts != null) {
                    androidTts.stop();
                }
                stopMouthLedForTts();
                return codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);
            case "speech/set_mic":
                robot.speech_SetMIC(Boolean.parseBoolean(require(query, "wake")));
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
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
                return codeResponse(robot.speech_startRecognized(require(query, "text")));
            case "speech/stop_inject":
                // Companion to speech/inject (onStopSpeech). Untested, same caveats.
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
                Log.i(TAG, "###### BUILD MARKER v2026-06-30-diag - audio/testtone hit ######");
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
            // app (見 AIDL_REFERENCE.md「引擎選擇」段落) —— 實測證實 (見 log) 改咗呢個
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
    /** Handles POST /upload/audio: raw PCM bytes (16kHz mono 16-bit, matching
     *  AudioPlaybackController's format) from the browser's mic, queued for playback.
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
     *  line set via servo/sonar. */
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
        if (triggered) {
            setHeadEyeLedLong(5, 9); // 5 = 紫 (purple), see led/head/set color-code comment
        } else {
            robot.header_stop5MicEarLED();
            robot.header_stop5MicEyeLED();
        }
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
     * app.js's setListenLed()). Depending on scheduling this broadcast could land
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
                    chunk = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null) {
                    break; // no audio in 10s - mic likely died
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
                // setMic(false) in app.js - true is the opposite, "release to app".
                robot.speech_SetMIC(false);
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
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"actions\":[");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ArrayList<String> row = list.get(i);
                if (row.size() < 4) continue;
                if (i > 0) sb.append(',');
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
