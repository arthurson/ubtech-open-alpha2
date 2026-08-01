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
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.constant.CustomLanguage;
import com.ubtechinc.constant.LanguageType;
import com.ubtechinc.constant.StaticValue;

import java.util.ArrayList;
import java.util.Map;
import java.nio.charset.StandardCharsets;

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
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final String APP_KEY = "222B998EDFA5FAD7FCE78678FB9F2521";

    private Alpha2RobotApi robot;
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
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
    private static final String STOP_CUE_RINGTONE_TITLE = "Proxima";
    private android.net.Uri stopCueUri; // resolved lazily, cached after the first lookup
    private boolean stopCueLookupDone = false;

    private volatile boolean speechReady = false;
    private volatile int lastBatteryLevel = -1;
    private volatile int lastBatteryScale = -1;
    private volatile boolean lastBatteryCharging = false;
    private volatile String lastBatteryStatus = "unknown";

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

        // Start the HTTP/WebSocket server FIRST - the on-device status text below reads
        // the Wi-Fi IP to display, and other devices' browsers rely on it being up.
        //
        // TLS is attempted before plain HTTP: navigator.mediaDevices.getUserMedia() (the
        // walkie-talkie mic feature - see app.js's startTalk()) is only exposed by
        // browsers on a "secure context" (https:// or localhost), and this panel is
        // neither by default. A self-signed cert makes the origin secure enough for
        // getUserMedia to exist, at the cost of a one-time "connection not private"
        // browser warning the user has to click through - unavoidable for a LAN IP with
        // no real domain/CA-trusted cert. If cert generation or SSLContext setup fails
        // for any reason (e.g. some device-specific provider quirk), this falls back to
        // plain HTTP rather than taking down the whole panel over one feature.
        String ip = getWifiIp();
        javax.net.ssl.SSLServerSocketFactory tlsFactory = null;
        try {
            tlsFactory = TlsSupport.buildServerSocketFactory(ip);
        } catch (Exception e) {
            Log.e(TAG, "TLS setup failed, falling back to plain HTTP - "
                    + "getUserMedia()/mic walkie-talkie will not work in the browser", e);
        }

        httpServer = new HttpServer(getAssets(), new HttpServer.ApiHandler() {
            @Override
            public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
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
        if (stopCueUri == null) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, stopCueUri);
            player.setOnPreparedListener(android.media.MediaPlayer::start);
            player.setOnCompletionListener(android.media.MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                return true;
            });
            player.prepareAsync(); // don't block the main thread; starts once ready
        } catch (Exception e) {
            Log.w(TAG, "Failed to play Proxima stop cue", e);
        }
    }

    /** Scans every ringtone RingtoneManager knows about (notifications + ringtones)
     *  for one whose title matches exactly (case-insensitive), returning its Uri, or
     *  null if none match. Title is the only stable way to name a specific built-in
     *  system sound - resource IDs/file paths vary by OEM and Android version. */
    private android.net.Uri findRingtoneByTitle(String title) {
        android.media.RingtoneManager manager = new android.media.RingtoneManager(this);
        manager.setType(android.media.RingtoneManager.TYPE_ALL);
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
                // "Local_Result:rule:... action:... tag:...".
                // NOTE: wakeup direction is NOT parsed here - it arrives via the separate
                // com.ubtechinc.services.SPEECH_DIRECTION broadcast, handled in
                // RobotEventReceiver, which is where the servo-19 turn is triggered.
                EventBus.get().publish("asr_result", "{\"text\":\"" + jsonSafe(text) + "\"}");
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
    private HttpServer.ApiResponse handleApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"sdkVersion\":\"" + Alpha2RobotApi.getSdkVersion() + "\","
                        + "\"chestAvailable\":" + isOk(robot.isChestAvailable()) + ","
                        + "\"headerAvailable\":" + isOk(robot.isHeaderAvailable()) + ","
                        + "\"speechReady\":" + speechReady + ","
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
            case "servo/sonar":
                robot.waitChestReady(3000);
                return codeResponseReady(robot.chest_configureSonar(Integer.parseInt(require(query, "distance"))), robot.isChestReady());

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

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown endpoint: " + path + "\"}");
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
