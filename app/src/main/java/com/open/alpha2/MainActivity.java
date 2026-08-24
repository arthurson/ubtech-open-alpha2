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
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
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
import org.json.JSONException;
import org.json.JSONObject;
import com.open.alpha2.iflytektest.IflytekOfflineTest;

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

    private static final String PREFS_NAME = "robotpanel";
    /** 自訂小智 server 設定 - 開關開咗先用 PREF_XIAOZHI_OTA_URL, 閂咗就跟返
     *  XiaozhiOtaClient.DEFAULT_OTA_URL (官方 api.tenclass.net)。見
     *  handleXiaozhiApi() 嘅 "ota_config/get"/"ota_config/set" case 同
     *  runXiaozhiActivationFlow() 點讀呢個設定。 */
    private static final String PREF_XIAOZHI_OTA_CUSTOM_ENABLED = "xiaozhi_ota_custom_enabled";
    private static final String PREF_XIAOZHI_OTA_URL = "xiaozhi_ota_url";
    // 2026-08 新增: 自架 server 未必跟足官方協議形狀 (OTA response 冧埋
    // websocket url/token 一齊送返嚟) - 有啲自架方案要用戶自己手動填呢幾樣嘢。
    // 全部留空 = 跟返自動流程 (OTA response 度攞); 有填就用嚟覆寫對應嘅自動值。
    // 只喺 PREF_XIAOZHI_OTA_CUSTOM_ENABLED 開咗嗰陣先讀呢幾個, 同 OTA URL
    // 本身一齊收埋喺同一個「自訂小智 server」開關底下。
    private static final String PREF_XIAOZHI_WS_URL_OVERRIDE = "xiaozhi_ws_url_override";
    private static final String PREF_XIAOZHI_DEVICE_ID_OVERRIDE = "xiaozhi_device_id_override";
    private static final String PREF_XIAOZHI_TOKEN_OVERRIDE = "xiaozhi_token_override";
    private static final String PREF_XIAOZHI_DEVICE_ID = "xiaozhi_device_id";
    // 2026-08 新增: MCP tool 個別 enable/disable 設定。總開關預設 true (保持現有
    // 行為 - 已經喺用嘅人唔應該因為呢個功能上線而啲工具突然全部消失)。
    // disabled tool 清單預設空 (即係全部 enabled), 用逗號分隔嘅 tool name 儲存
    // 喺同一個 SharedPreferences, 用 name 唔用 index 係因為 tool 清單本身會隨版本
    // 增減, index 會漂移, name 先係穩定嘅 identity。
    private static final String PREF_XIAOZHI_MCP_ENABLED = "xiaozhi_mcp_enabled";
    // 見 xiaozhiTtsEngine field 嘅 javadoc。
    private static final String PREF_XIAOZHI_TTS_ENGINE = "xiaozhi_tts_engine";
    private static final String PREF_XIAOZHI_MCP_DISABLED_TOOLS = "xiaozhi_mcp_disabled_tools";
    /** 官方 xiaozhi-esp32 firmware 寫死用嘅 vision/explain endpoint (esp32_camera.cc
     *  Explain() 實作) - 呢個 URL 唔會經 OTA check_version 嘅回應帶返嚟 (見
     *  runXiaozhiActivationFlow() 嘅 comment: response 淨係有 activation/websocket
     *  兩個 block), 所以要獨立一個設定。自訂 server 開住嗰陣如果冇填呢個, 就跟返
     *  官方呢個 - 好多自架 server 都冇實作 vision explain, 呢種情況下 take_photo
     *  call 出去會收到 404/連唔到, self.camera.take_photo 嘅 case 會將呢個原因
     *  話俾 LLM 知, 而唔係靜靜哋扮成功。
     *
     *  2026-08 修正: 之前呢度寫死用 https://, 但實測用 https:// 撞到 HTTP 404
     *  (即使 xiaozhi.me console 側已經開通咗 vision/camera 服務都一樣) - 對照
     *  官方 esp32_camera.cc 個 source (SetExplainUrl/Explain() 實作) 同 GitHub
     *  issue #708 嘅實機 log, 官方 firmware 打嘅其實係 http:// (唔加密), 唔係
     *  https://: "Opening HTTP connection to http://api.xiaozhi.me/mcp/vision/explain"
     *  低於呢個 scheme 嘅路由喺 server 側可能同 https:// 唔係同一個 virtual
     *  host/根本冇 mapping, 所以之前一直 404。呢度跟返官方實際用緊嘅 scheme。 */
    /** Fallback vision/explain URL, only used when the server hasn't (yet) told us
     *  its real one via the "initialize" MCP request's params.capabilities.vision
     *  (see XiaozhiClient.getVisionUrl()'s comment for the full story - that's the
     *  authoritative source; this constant is a last-resort default for the case
     *  where take_photo is somehow called before any "initialize" has been
     *  received). 唔保證啱 - 純粹一個合理猜測嘅底線值, 唔應該係主要路徑。
     *
     *  2026-08 修正: 之前呢度用 http://api.xiaozhi.me/... - 反編譯一個用戶提供、
     *  實測影相成功嘅第三方 apk (package com.huihongcloud.xiaozhi) 嘅
     *  classes.dex, 證實佢 OTA 用嘅其實係 https://api.tenclass.net/xiaozhi/ota/
     *  (同 DEFAULT_OTA_URL 一致) - api.xiaozhi.me 呢個 domain 根本冇
     *  /mcp/vision/explain 呢條路由, 一路 404 同 console 側有冇開通 vision 服務
     *  完全無關。改跟返 api.tenclass.net, scheme 跟返 DEFAULT_OTA_URL 一致嘅
     *  https。 */
    private static final String DEFAULT_VISION_URL = "https://api.tenclass.net/xiaozhi/mcp/vision/explain";
    private static final String PREF_XIAOZHI_VISION_URL = "xiaozhi_vision_url";
    /** 相機解像度 (用戶指定) - take_photo 特登用細過一般 camera/snapshot 預覽嘅
     *  解像度, 因為呢張相淨係要上傳去 vision explain 俾 LLM 「睇」, 唔係俾人單獨
     *  睇嘅相片, 細啲可以令上傳/處理快啲, 都夠 LLM 辨識到大致內容。 */
    private static final int XIAOZHI_PHOTO_WIDTH = 480;
    private static final int XIAOZHI_PHOTO_HEIGHT = 360;

    // 2026-08 新增: 用戶要求所有「停止」入口 (action/stop HTTP endpoint,
    // self.robot.stop_action MCP tool, 小智面板「⏹ 全部停止」/拍頭都經呢兩個
    // 之一) 停低現正播緊嘅動作之後, 補播返「蹲下站起」呢個動作做回位 - 停低
    // 唔應該留低機身喺一個中途/唔企定嘅姿勢。id 嚟自
    // blockly-actions-data.js/xiaozhi_actions.json 現有記錄嘅「蹲下站起」
    // (nameCn: 蹲下站起, nameEn: squat down up)。
    private static final String STOP_RECOVERY_ACTION_ID = "1510818174706";

    // 2026-08 新增: 記住最近一次 self.camera.take_photo 拎到嘅 "async, 未完成"
    // uuid (見 xiaozhiVisionExplainRequest() 嘅 comment) - 俾之後 LLM (GPT-5)
    // 主動再發嘅 "self.camera.image_to_text" tools/call 用嚟核對/攞返真正描述。
    // 淨係記最新一個 (單一 device, 冇並行 take_photo 嘅需要) - 用完/逾時後應
    // 清返做 null, 避免舊 uuid 谷落去新一次 call。
    private volatile String lastPendingPhotoUuid;

    private Alpha2RobotApi robot;
    private HttpServer httpServer;
    // 小智 (XiaoZhi) AI 對話 - 獨立於機械人 AIDL 之外嘅 client-side WebSocket
    // 連線, 連出去 xiaozhi.me。單一 instance, 喺 onCreate() 先建立 (要用
    // getSharedPreferences() 攞/生成 device id, field initializer 嗰陣 Activity
    // context 未必 ready), 由 handleXiaozhiApi() 開關
    // (見 handleXiaozhiApi() 嘅 javadoc)。
    private XiaozhiClient xiaozhiClient;
    // PHASE 2: mic-capture-encode + decode-playback for XiaoZhi voice chat - separate
    // instance from audioController/audioPlaybackController below (different sample
    // rate/purpose/lifecycle, see XiaozhiAudioController's class javadoc). Constructed
    // eagerly (no Activity context needed, unlike xiaozhiClient) but only ever
    // start()ed from handleXiaozhiApi()'s "mic/start", gated on
    // XiaozhiClient.isAudioSupported().
    private final XiaozhiAudioController xiaozhiAudioController = new XiaozhiAudioController();
    // PHASE 3: tracks the OTA/device-activation flow (check_version -> speak code ->
    // poll activate -> hand off to XiaozhiClient.connect()) that must run *before*
    // XiaozhiClient's WebSocket connects for a not-yet-bound device. See
    // XiaozhiOtaClient's class javadoc for why this step exists at all. A single
    // in-flight activation at a time is enough for this app's UI (one "連接" button);
    // AtomicReference gives handleXiaozhiApi's "connect"/"activation_status" endpoints
    // a consistent snapshot to read without needing a separate lock.
    private final java.util.concurrent.atomic.AtomicReference<XiaozhiActivationStatus> xiaozhiActivationStatus =
            new java.util.concurrent.atomic.AtomicReference<>(XiaozhiActivationStatus.idle());
    // PHASE 4 (小智常開/auto mode): when true, MainActivity keeps the mic listening
    // continuously - re-issuing mic/start every time TTS playback finishes (see
    // XiaozhiClient.TtsStateListener's javadoc for why this has to be driven
    // device-side) - instead of requiring the person to press the mic button before
    // every utterance. Toggled by "xiaozhi/auto_mode"; also drives auto-connect (see
    // that endpoint) so switching this on from a cold/disconnected state is a single
    // action rather than "connect, wait, then separately press mic".
    private final java.util.concurrent.atomic.AtomicBoolean xiaozhiAutoMode =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // 小智 tab 嘅 TTS 輸出引擎揀擇 - "xiaozhi" (預設) 即係維持原本行為 (server
    // 送 opus 過嚟, XiaozhiAudioController 解碼播放); 揀
    // "iflytek"/"nuance"/"android" 就完全靜音嗰段 opus (見
    // xiaozhiClient.setAudioSink() 入面對呢個 field 嘅判斷), 改用本地
    // speech/tts 呢條路 (同 speech tab 個 speakTts() 用緊嗰個 API 一樣) 逐句
    // 讀出小智回覆 - 觸發時機係 xiaozhi_tts 嘅 "sentence_start" (對話氣泡本身
    // 都係用呢個顯示; xiaozhi_llm 個 data.text 其實係表情 emoji, 唔係對話內容,
    // 唔可以用嚟讀), 前端用隊列排住逐句讀晒先讀下一句 (見
    // xiaozhiEnqueueTts()/xiaozhiProcessTtsQueue() javadoc)。用
    // SharedPreferences 持久化 (同 PREF_XIAOZHI_MCP_ENABLED 等其他小智設定
    // 一致嘅做法), 跨重啟記得住揀咗邊個。
    private volatile String xiaozhiTtsEngine = "xiaozhi";
    /** Tracks consecutive unexpected-disconnect reconnect attempts for
     *  xiaozhiScheduleReconnect()'s backoff - reset to 0 on any successful (re)connect
     *  (see runXiaozhiActivationFlow()'s success path) so a stable connection later
     *  doesn't inherit a long delay from an earlier flaky period. */
    private final java.util.concurrent.atomic.AtomicInteger xiaozhiReconnectAttempts =
            new java.util.concurrent.atomic.AtomicInteger(0);
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

    /** true = XiaoZhi (小智) 語音對話而家持有緊 mic 擁有權 (releaseMicForAudioIo()
     *  已經 call 咗, AudioRecord 已經開緊)。獨立過 micHeldByApp (Speech/Mic tab 專用) -
     *  兩個功能各自攞放, 互不影響, 見 stopXiaozhiMic() 嘅 comment。前端靠
     *  XIAOZHI_MIC_STATE_EVENT 反映呢個狀態做綠/灰燈號 (見 index.html
     *  #xiaozhiMicLed / app-xiaozhi.js)。 */
    private volatile boolean xiaozhiMicHeld = false;
    private volatile boolean xiaozhiMicHoldEnforced = false;
    private Thread xiaozhiMicHoldEnforcerThread;
    /** Published on EventBus whenever xiaozhiMicHeld changes - payload
     *  {"held":true/false}, consumed by app-xiaozhi.js to drive the mic LED. */
    private static final String XIAOZHI_MIC_STATE_EVENT = "xiaozhi_mic_state";

    /** Cached parse of assets/web/xiaozhi_actions.json (202 動作, id/nameCn/nameEn) -
     *  loaded once lazily on first use (see loadXiaozhiActions()) rather than at
     *  onCreate(), since it's only needed if/when the XiaoZhi tab's play_action tool
     *  schema is actually requested. null until first load attempt; an empty (but
     *  non-null) list means the load was attempted and failed/produced nothing, which
     *  is distinguished from "not yet loaded" so a broken assets file doesn't retry
     *  the parse on every single tools/list call. */
    private volatile java.util.List<org.json.JSONObject> xiaozhiActionsCache;

    /** 2026-08 新增: 完全取代悠聊 APK (com.ubtech.iflytekmix) 用嘅中文語意配對引擎
     *  實例。喺 onCreate() 建立一次 (淨係持有 Context, 唔碰 AIDL, 冇初始化順序問題),
     *  真正嘅 1000 條資料就到 handleIflytekSemanticText() 第一次被叫先讀 assets - 見
     *  IflytekSemanticMatcher 本身嘅 lazy-load 設計。 */
    private IflytekSemanticMatcher iflytekMatcher;

    /** 2026-08 新增: 完全取代 AlphaEnglishChat APK
     *  (com.ubtechinc.alphaenglishchat) 用嘅英文語意配對引擎實例, 同 iflytekMatcher
     *  屬於同一套機制、獨立資料 (1000 條英文問法, 見 IflytekSemanticMatcherEn)。
     *  邊句用邊個 matcher 由 handleIflytekSemanticText() 根據輸入文字有冇 CJK 漢字
     *  判斷 - 唔靠 speech/set_asr_engine 嘅語言設定, 因為 iFlytek 引擎本身可能自動
     *  偵測語言, 靠內容判斷更可靠。 */
    private IflytekSemanticMatcherEn iflytekMatcherEn;

    /** 2026-08 新增: 上次由 Radio Browser API (radio-browser.info) 搜到嘅電台結果
     *  cache - 俾 self.media.play_radio/audio/radio/play 用「上一次
     *  self.media.search_radio 搵到嘅結果入面揀一個」呢個 flow (見
     *  searchRadioStations()/resolveRadioStation() 嘅 javadoc), 唔係一份固定嘅
     *  本地清單 (呢部機唔再內置任何寫死嘅電台, 全部經呢個 API 動態搵)。 */
    private volatile java.util.List<org.json.JSONObject> lastRadioSearchResults;

    /** Bearer token from the most recent successful runXiaozhiActivationFlow() -
     *  reused for the vision/explain HTTP call (self.camera.take_photo tool, see
     *  xiaozhiVisionExplain()) since that endpoint uses the same Device-Id/Client-Id/
     *  Authorization headers as the WebSocket connection itself, not a separate
     *  credential. null until the first successful connect. */
    private volatile String xiaozhiAccessToken;
    // 2026-08 新增: 之前呢度嘅 comment 已經話「同 WebSocket 連接一樣嘅
    // Device-Id/Client-Id/Authorization headers」, 但 xiaozhiVisionExplainRequest()
    // 實際冇送 Client-Id header - 反編譯一個用戶提供、實測影相成功嘅第三方 apk
    // (package com.huihongcloud.xiaozhi) 嘅 vision explain 實現, 證實佢真係有送
    // 呢個 header (invoke-virtual v3, v2, LA/i;->f("Client-Id", XiaoZhi.a0)), 對應
    // 就係連接 WebSocket 果陣用嘅同一個 client_id。runXiaozhiActivationFlow() 之前
    // 每次都用 java.util.UUID.randomUUID() 生成一個新 clientId 傳落
    // XiazhiOtaClient 建構, 但冇存低俾之後嘅 vision request 讀 - 呢個 field 就係
    // 用嚟補呢個缺口。
    private volatile String xiaozhiClientId;

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

    // PIR alert cue - "Heaven" 係 Android 內置系統鈴聲標題, 同 STOP_CUE/SHUTTER_CUE
    // 一樣做法 (lazy lookup by title, cache 埋個 content:// Uri)。播放時機見
    // registerAlpha2PirAlertListener() - alpha2_pir_state broadcast
    // (RobotEventReceiver.java) 一到 triggered=true 就即刻播, triggered=false 即刻停
    // (跟 sonar 個 purple LED 一樣, 唔等成首歌播完)。
    private static final String PIR_ALERT_RINGTONE_TITLE = "Heaven";
    private android.net.Uri pirAlertUri;
    private boolean pirAlertLookupDone = false;

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
    // 追蹤機身 robot-side TTS (nuance/iflytek, 經 robot.speech_startTTS() 走)
    // 而家係咪正播緊嘢 - 由 startXiaozhiMicHoldEnforcer()/startMicHoldEnforcer()
    // 用嚟決定要唔要跳過呢一輪 speech_SetMIC(true)。背景: 兩條 mic-hold
    // enforcer thread 每 MIC_HOLD_ENFORCER_INTERVAL_MS (2 秒) 就會無條件搶一次
    // mic, 一句超過 2 秒先讀完嘅句子播到一半就俾 speech_SetMIC(true) 打斷
    // (真機 logcat 見過 "ttsGenerationFinished ... success = false" 跟住即刻
    // "setWakeState onWake:true") - Android system TTS 唔經呢個 AIDL 通道,
    // 唔會撞到, 所以之前只有 iflytek/nuance 斷斷續續, android 冇事。
    private volatile boolean robotTtsSpeaking = false;

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
    // 2026-08 新增: onSonarDistanceReceived() 之前淨係用嚟判斷 triggered 有冇改變
    // (驅動 LED), 冇存低實際讀數本身 - XiaoZhi MCP tool (self.sensors.get_sonar)
    // 要俾 LLM 隨時查詢「而家距離幾多」, 唔止「有冇觸發」, 所以呢度加一個 cache
    // 住最新讀數嘅 field。-1 代表「未收過任何讀數」, 同真實距離 (恆為非負) 區分開,
    // 俾 MCP tool 可以話俾 LLM 知呢個係「未有數據」而唔係「距離 0cm」。
    private volatile int lastSonarDistanceCm = -1;
    // 2026-08 新增: 同 lastSonarDistanceCm 同一個目的 - PIR 事件之前淨係即時
    // publish 去 EventBus (見 RobotEventReceiver 個 "com.ubtechinc.key"/-109 case),
    // 冇存低最新狀態俾 MCP tool 隨時查詢。-1 = 未收過任何 PIR 事件, 0 = 上次收到
    // 嘅係 EXIT (冇人), 1 = 上次收到嘅係 ENTER (有人) - 用 int 唔用 boolean 嚟
    // 保留「未有數據」呢個第三種狀態, 同 lastSonarDistanceCm 用 -1 嘅原因一樣。
    private volatile int lastPirTriggeredState = -1;
    // 2026-08 新增: listTools() (見 xiaozhiMcpBridge()) 每次被 call 都會存低一份
    // 完整、未過濾嘅 tool 清單落呢度 - 俾 "mcp_tools/list" HTTP endpoint (MCP 設定
    // card 用) 讀, 等個 card 可以顯示全部 tool 連同已 disable 嗰啲。初始為 null
    // (未連過 XiaoZhi/未收過 tools/list 之前), HTTP handler 要處理呢個情況 (fallback
    // 直接 call 一次 listTools() 逼佢起返份清單, 因為個 card 應該喺用戶未連接之前
    // 都睇到有咩 tool 可以 enable/disable)。
    private volatile org.json.JSONArray lastFullMcpToolList = null;

    // 2026-08 新增: 用戶要求「如果有其他動作要做, 就淨係做其他動作」- 之前純粹
    // 靠 self.robot.play_random_action 個 tool description 勸 LLM 自己揀優先次序,
    // 但實測發現 LLM 有時成段對話一次都唔 call play_random_action (可能覺得每輪
    // 都有其他嘢做, 或者純粹冧咗嘴唔用), 結果機械人企定定完全唔郁, 用戶睇落好似
    // 「random 動作完全無咗」。之前試過用一個 flag 追蹤緊「呢一輪有冇 LLM 自己
    // call 過動作類 tool」, 冇就喺 TTS "stop" (回應播完) 先補一個 random action -
    // 但用戶其後糾正: random 動作應該同 TTS 一齊做 (即係開始講嘢嗰刻就郁), 唔係
    // 「講完先做」, 所以呢個做法已經改喺 TTS "start" 事件度直接觸發 (見
    // setTtsStateListener() 嗰段), 唔再靠呢個 flag 判斷「呢一輪有冇其他動作」 -
    // 拎走咗呢個字段同相關嘅 set 語句 (曾經喺 play_action/stop_action/
    // play_random_action 三個 case 度出現過), 因為而家個時機邏輯已經唔需要佢。

    /** RobotEventReceiver 個 "alpha2_pir_state" publish 之後順手 call 呢個, 等
     *  self.sensors.get_pir MCP tool 可以讀到最新狀態, 唔使自己另外訂閱
     *  EventBus。冇 instance 就靜靜哋唔做嘢 (同 onSonarDistanceReceived() 一致嘅
     *  處理)。
     *
     *  ⚠️ 呢個方法係喺 RobotEventReceiver (一個 BroadcastReceiver) 嘅
     *  onReceive() 入面直接被 call, 即係話呢個方法本身、同佢叫嘅任何嘢, 都
     *  **一定唔可以有阻塞式操作** (Thread.sleep、網絡 IO、等等) - BroadcastReceiver.
     *  onReceive() 有嚴格時限 (通常十秒內要返回), 密集嘅 PIR broadcast 一浪接一浪
     *  嗰陣, 阻塞邏輯會連環咁卡住, 輕則觸發 ANR, 重則 (2026-08 一次粗心嘅版本
     *  真機實測證實) 直情 hold 死成個 system 連 adb 都冇反應。所以呢度淨係做
     *  最平嘅 field 寫入, 任何要送 WebSocket 訊息嘅耗時邏輯都必須包多一層獨立
     *  thread 先可以做 (見下面 new Thread(...).start())。 */
    static void onPirStateReceived(final boolean triggered) {
        final MainActivity m = sInstance;
        if (m == null) {
            return;
        }
        int newState = triggered ? 1 : 0;
        if (newState == m.lastPirTriggeredState) {
            return; // 狀態冇變, 唔重複推播 (同 sonar 個 dedup pattern 一致)
        }
        m.lastPirTriggeredState = newState;
        // 2026-08 新增: 用戶要求「唔係叫一次做一次, 而係只要 PIR 開左, 每次
        // broadcast 回報有唔同都要有反應」- 即係要事件驅動、主動話俾小智知,
        // 唔係淨係俾 LLM 隨時查詢。呢段一定要包喺獨立 thread 度先可以做
        // (xiaozhiSendDetectTextSafely() 入面有 Thread.sleep + 阻塞式 WebSocket
        // send, 原因見上面 class javadoc 段嘅慘痛教訓), 保持 onReceive() 本身
        // 即刻返回, 唔會阻住個 broadcast dispatch。
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!m.xiaozhiClient.isOpen()) {
                    return;
                }
                String text = triggered
                        ? "[系統事件] PIR 人體感應器偵測到有人喺附近。"
                        : "[系統事件] PIR 人體感應器偵測唔到人喺附近喇。";
                String err = m.xiaozhiSendDetectTextSafely(text);
                if (err != null) {
                    android.util.Log.w("XiaozhiPir", "failed to push PIR event to XiaoZhi: " + err);
                }
            }
        }, "XiaozhiPirEventPush").start();
    }

    // Android system TTS (a third engine option alongside the robot's own Nuance/
    // iFlytek, used directly rather than via ISpeechInterface). No voice selection -
    // voice choice is only meaningful for iFlytek's named voices.
    // volatile: initAndroidTts() reassigns this from an HTTP worker thread when
    // switching engines, and it's read from other worker threads on every speech/tts
    // call - a plain field could let one thread see a stale/half-published reference.
    private volatile TextToSpeech androidTts;
    private volatile boolean androidTtsReady = false;
    private volatile String androidTtsEnginePkg = ""; // package of the engine androidTts is currently bound to

    // listAndroidTtsLanguages() 嘅 legacy fallback (SVOX Pico 冇實作
    // getVoices(), IPC 層直接 throw "NullPointerException: collection ==
    // null" - 唔係回空 collection, 係完全冇實作) 用嘅 blocking 狀態, 見
    // checkTtsDataSyncLegacy()/onActivityResult() javadoc。
    private final Object ttsDataCheckLock = new Object();
    private CountDownLatch ttsDataCheckLatch;
    private volatile ArrayList<String> ttsDataCheckResult;
    private static final int TTS_DATA_CHECK_REQUEST_CODE = 0x7454; // "T T" leetspeak-ish, 淨係要係一個穩定、未用過嘅 code

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
        m.lastSonarDistanceCm = distanceCm;
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
        // 見 xiaozhiTtsEngine field 嘅 javadoc - 讀返上次揀低嘅 TTS 引擎, 冇存過
        // 就用預設值 "xiaozhi" (原本行為, 唔靜音)。
        xiaozhiTtsEngine = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_XIAOZHI_TTS_ENGINE, "xiaozhi");
        iflytekMatcher = new IflytekSemanticMatcher(this);
        iflytekMatcherEn = new IflytekSemanticMatcherEn(this);
        // Constructs (or re-constructs, when switching engines) androidTts. Pulled out
        // of onCreate()'s inline block into its own method so speech/set_tts_engine can
        // call it again later without duplicating the OnInitListener/
        // UtteranceProgressListener wiring.
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
                // "/api/alpha2/..." goes to the original Alpha2RobotApi dispatch
                // (handleApi, unchanged below). "/api/system/..." is a small namespace
                // for things not tied to the robot SDK itself.
                if (path.startsWith("alpha2/")) {
                    return handleApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("system/")) {
                    return handleSystemApi(path.substring(7), query, method, body);
                }
                if (path.startsWith("xiaozhi/")) {
                    return handleXiaozhiApi(path.substring(8), query, method, body);
                }
                // Back-compat: requests with no backend prefix (older cached browser
                // tab) fall through to the Alpha2 dispatch.
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

        // 2026-08 修正: 「頭部降噪」toggle 由 UI 移除, 預設常開 - header_setNoise()
        // 係 AIDL call (經 robot.waitHeaderReady() 等 header serial ready), 唔可以好似
        // 上面 ALPHA_SET_CHARGE_PLAY 噉直接喺 postDelayed 嘅 UI thread 度 call (會
        // block UI thread), 所以呢度用獨立 background thread 執行, 時機跟返上面
        // charge-play 嗰個 3s delay 一致嘅理據 (等 alpha2services 啱啱起身嗰陣有時間
        // 準備好)。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    robot.waitHeaderReady(3000);
                    robot.header_setNoise(true);
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
        // string 喺呢個韌體版本已經搵唔到任何 sendBroadcast 出處, 實際上係死
        // code。依然保留 filter + RobotEventReceiver 嗰個 case, 純粹做向後
        // 相容 (以防其他韌體/舊機用返呢個 action), 但呢部機唔會再觸發。
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.services.SPEECH_DIRECTION");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction(StaticValue.ALPHA_QR_CODE);
        filter.addAction(StaticValue.ALPHA_WIFI_RESULT);
        filter.addAction(StaticValue.ALPHA_BT_CONNECTION);
        // 2026-08 新增 (2個): 反編譯 alpha2services_base 3.0.0.2 全個 APK 搵到嘅
        // sendBroadcast() 出處, 之前呢個 App 完全冇 register, 詳見各自嘅
        // RobotEventReceiver case comment。
        filter.addAction("com.ubtechinc.services.Action.ACTION_STOP");
        filter.addAction("com.ubtechinc.services.Action.ROBOT_INTERRUPTED");
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
        // 2026-08 新增: ⚠️ 未經真機驗證 (見 RobotEventReceiver 呢個 case 嘅
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 逆出嚟嘅 PIR 通知
        // broadcast, 淨係喺 SecurityCameraUtil 監控開關開緊嗰陣先會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
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
            case 0x5e: // both pressed (raw gesture code 94, decimal) - 全部停止:
                       // 用戶要求將總停鍵嘅效果搬呢粒實體鍵度, 之前呢度淨係
                       // action_StopAction(), 而家同小智面板嗰粒「⏹ 全部停止」
                       // 掣 (xiaozhiStopAll(), 見 app-xiaozhi.js) 睇齊, 一次過
                       // 停埋動作/小智講嘢/本地音樂/電台四樣嘢。
                stopVolumeRepeat(); // in case one pad was already held down
                playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                if (robot != null) {
                    robot.action_StopAction();
                    // 見 "action/stop" endpoint 嗰段 comment - 停低之後補一個
                    // 「蹲下站起」做回位, 同 HTTP API/self.robot.stop_action 嗰邊
                    // 行為保持一致 - 之前呢度漏咗呢一步。
                    try {
                        robot.action_PlayActionName(STOP_RECOVERY_ACTION_ID);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to play recovery action after "
                                + "gesture-triggered stop-all", e);
                    }
                }
                stopAllSpeechPlayback();
                stopLocalMusicPlayback();
                stopRadioPlayback();
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

    // 2026-08 新增: 本地音樂播放 (自訂放喺 /mnt/internal_sd/music/ 嘅音樂檔, 唔係
    // RingtoneManager 嗰啲系統鈴聲) - 跟返 currentRingtonePlayer 完全同一個 pattern
    // (獨立一個 field, 唔共用 currentRingtonePlayer, 因為兩者應該可以互不影響咁
    // 各自停/播, 例如播緊音樂期間都可以獨立播一個系統提示音), 一樣用
    // STREAM_MUSIC + prepareAsync() + 播完自動 release()。
    private static final java.io.File LOCAL_MUSIC_DIR = new java.io.File("/mnt/internal_sd/music");
    private static final java.util.Set<String> LOCAL_MUSIC_EXTENSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("mp3", "wav", "ogg", "m4a", "flac"));

    private android.media.MediaPlayer currentMusicPlayer;

    /** Lists every playable audio file directly inside LOCAL_MUSIC_DIR (non-recursive -
     *  keeps this predictable for a small hand-managed folder rather than silently
     *  picking up files buried in sub-folders). Filters by extension only (see
     *  LOCAL_MUSIC_EXTENSIONS) since there's no MediaStore index guaranteed for a
     *  manually-copied folder on API 22. Returns an empty list (never null) if the
     *  folder doesn't exist or isn't readable - callers must treat that as a normal
     *  "no music" case, not a bug. Sorted by filename for a stable, predictable order
     *  across calls (directory listing order is otherwise filesystem-dependent). */
    private java.util.List<java.io.File> listLocalMusicFiles() {
        java.util.List<java.io.File> result = new java.util.ArrayList<>();
        java.io.File[] files = LOCAL_MUSIC_DIR.listFiles();
        if (files == null) return result;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) continue;
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
            if (LOCAL_MUSIC_EXTENSIONS.contains(ext)) result.add(f);
        }
        java.util.Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    /** Resolves a human-supplied song name/query to an actual file in LOCAL_MUSIC_DIR -
     *  mirrors resolveActionId()'s three-tier match (exact filename incl. extension,
     *  then exact match against the filename without extension, then substring either
     *  direction) so the XiaoZhi LLM can just say a song's (approximate) name instead
     *  of needing to know the exact on-disk filename/extension. Returns null if nothing
     *  matches closely enough, same "don't guess" philosophy as resolveActionId(). */
    private java.io.File resolveLocalMusicFile(String query) {
        java.util.List<java.io.File> files = listLocalMusicFiles();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || files.isEmpty()) return null;

        for (java.io.File f : files) {
            if (q.equals(f.getName())) return f;
        }
        String qLower = q.toLowerCase(java.util.Locale.US);
        for (java.io.File f : files) {
            String base = stripExtension(f.getName());
            if (qLower.equals(base.toLowerCase(java.util.Locale.US))) return f;
        }
        for (java.io.File f : files) {
            String baseLower = stripExtension(f.getName()).toLowerCase(java.util.Locale.US);
            if (baseLower.contains(qLower) || qLower.contains(baseLower)) return f;
        }
        return null;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    /** 2026-08 更新 (用戶要求「本地播歌, random 動作應該係不停郁, 直至首歌播完」):
     *  之前淨係喺 onPrepared (真正開始播嗰刻) 郁一次就算, 而家改成用呢個固定
     *  間隔不斷重複觸發 triggerRandomFillerAction(), 直到首歌完/俾人叫停為止。
     *  用固定間隔 (而唔係「等個動作做完先郁下一個」) 嘅原因: AIDL 冇提供任何
     *  查「一個 action 幾時做完」嘅方法 (見 AIDL_REFERENCE.md, action_PlayActionName
     *  只係 fire-and-forget), 冇辦法準確知道上一個動作幾耐先做完, 所以揀一個
     *  保守嘅固定 cadence, 對絕大部份動作長度嚟講都夠時間做完個動作先再開始
     *  下一個, 唔會不斷打斷緊上一個未做完嘅動作。 */
    private static final long MUSIC_FILLER_ACTION_INTERVAL_MS = 3500;

    /** 現正行緊嘅「播歌隨機動作」循環 Runnable, null = 冇行緊 - 用嚟俾
     *  stopLocalMusicPlaybackLocked() 用 mainHandler.removeCallbacks() 準確停低
     *  呢個循環, 唔會靠估。 */
    private Runnable musicFillerActionLoop;

    /** 啟動「播歌期間不斷郁隨機動作」嘅循環 - 每 MUSIC_FILLER_ACTION_INTERVAL_MS
     *  觸發一次 triggerRandomFillerAction(), 再重新 schedule 自己, 直至
     *  boundPlayer 唔再係 currentMusicPlayer (即係首歌已經完/俾人叫停/俾第二首歌
     *  取代咗) 先停低。用 mainHandler (Looper.getMainLooper()) 排程, 同
     *  reassertHeadEyeLed() 一致嘅做法 - 呢個 method 本身淨係 postDelayed, 冇做
     *  blocking call, 唔使擔心阻塞 main thread; 真正嘅動作播放
     *  (triggerRandomFillerAction() 入面) 一路都係開緊獨立 thread 做 AIDL call。 */
    private void startMusicFillerActionLoop(final android.media.MediaPlayer boundPlayer) {
        Runnable loop = new Runnable() {
            @Override
            public void run() {
                synchronized (MainActivity.this) {
                    if (currentMusicPlayer != boundPlayer) {
                        // 首歌已經播完/俾人叫停/俾第二首歌取代咗 - 呢個循環
                        // 對應嘅播放已經唔再有效, 唔再重新 schedule, 自然完結。
                        return;
                    }
                }
                triggerRandomFillerAction();
                synchronized (MainActivity.this) {
                    if (currentMusicPlayer == boundPlayer && musicFillerActionLoop != null) {
                        mainHandler.postDelayed(musicFillerActionLoop, MUSIC_FILLER_ACTION_INTERVAL_MS);
                    }
                }
            }
        };
        musicFillerActionLoop = loop;
        mainHandler.postDelayed(loop, MUSIC_FILLER_ACTION_INTERVAL_MS);
    }

    /** 停低 startMusicFillerActionLoop() 開始嘅循環 (如果有嘅話) - 俾
     *  stopLocalMusicPlaybackLocked() call, 亦都俾 onCompletion/onError 嗰兩個
     *  listener call (首歌自然播完/播壞都應該即刻停低郁動, 唔使等落一次
     *  loop tick 先發現 currentMusicPlayer 已經唔啱先罷手)。 */
    private void stopMusicFillerActionLoop() {
        if (musicFillerActionLoop != null) {
            mainHandler.removeCallbacks(musicFillerActionLoop);
            musicFillerActionLoop = null;
        }
    }

    /** Plays a local music file - same STREAM_MUSIC/prepareAsync()/auto-release shape as
     *  playRingtoneUri(), kept as a separate method (rather than generalising both into
     *  one) so a future change to one playback path can't accidentally affect the
     *  other. Stops whatever local music track was previously playing first.
     *
     *  2026-08 更新: 開始真正播放嗰一刻 (onPreparedListener 入面, 唔係
     *  prepareAsync() 個 request 一發出就做) 順便啟動
     *  startMusicFillerActionLoop() - 用戶要求「播歌嗰陣要不停郁, 直至首歌播
     *  完」, 見嗰個 method 嘅 javadoc。刻意擺喺 onPrepared 入面 (真正 start()
     *  之後) 而唔係呢個 method 一開頭就做: 如果個檔案根本播唔到 (loss/corrupt,
     *  prepareAsync 觸發 onError), 唔應該仍然郁咗個動作先, 個「動作」應該同
     *  「真係有歌聲」同步, 唔係同「呢個 method 被 call 咗」同步。 */
    private synchronized void playLocalMusicFile(java.io.File file) {
        stopLocalMusicPlaybackLocked();
        if (file == null || !file.exists()) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                mp.start();
                startMusicFillerActionLoop(mp);
            });
            player.setOnCompletionListener(mp -> {
                synchronized (MainActivity.this) {
                    stopMusicFillerActionLoop();
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                    }
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (MainActivity.this) {
                    stopMusicFillerActionLoop();
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                    }
                }
                return true;
            });
            currentMusicPlayer = player;
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to play local music file " + file, e);
        }
    }

    /** 2026-08 新增: 停低「小智講嘢/回覆」呢一種播放 - 抽出嚟做共用 method, 俾
     *  handleApi() 嘅 "speech/stop" HTTP endpoint 同 onGestureCode() 嘅 0x5e
     *  (雙掣齊撳, 即係「94 鍵」) 一齊用。停埋機身本地 TTS (Nuance/iflytek,
     *  robot.speech_StopTTS())、Android TTS、同小智語音回覆嘅音訊
     *  (XiaozhiAudioController, WebSocket 收 Opus frame -> 解碼 -> AudioTrack,
     *  詳見 XiaozhiAudioController.onIncomingOpusFrame()/stopPlayback() 嘅
     *  javadoc) - 呢三條係完全獨立嘅播放管道, 停一條唔會累到第二條停, 之前
     *  用戶回報「停唔到小智講嘢」就係因為漏咗 XiaozhiAudioController 呢條路。 */
    private void stopAllSpeechPlayback() {
        if (robot != null) {
            robot.speech_StopTTS();
        }
        lastSpeechStopAtMs = System.currentTimeMillis();
        robotTtsSpeaking = false; // 見 robotTtsSpeaking field javadoc - 手動/總停鍵停咗都要即刻放行 mic enforcer
        if (androidTts != null) {
            androidTts.stop();
        }
        xiaozhiAudioController.stopPlayback();
        stopMouthLedForTts();
    }

    private synchronized void stopLocalMusicPlayback() {
        stopLocalMusicPlaybackLocked();
    }

    private void stopLocalMusicPlaybackLocked() {
        // 2026-08 新增: 手動停歌 (用戶撳 stop/總停鍵) 都應該即刻停低
        // startMusicFillerActionLoop() 嗰個循環, 唔使等落一次 loop tick 先發現
        // currentMusicPlayer 已經唔啱先罷手 - 最多會遲多三個幾秒先停到郁動,
        // 用戶體驗上唔啱「撳咗停就即刻停」嘅預期。
        stopMusicFillerActionLoop();
        if (currentMusicPlayer != null) {
            try {
                currentMusicPlayer.stop();
            } catch (Exception e) {
                // 見 stopRingtonePlaybackLocked() 個 comment - prepareAsync() 中途
                // race 可能引發 IllegalStateException, release() 一樣照做, 吞咗就得。
            }
            try {
                currentMusicPlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentMusicPlayer = null;
        }
    }

    // 2026-08 新增: FM/網絡電台播放 (經 Radio Browser API, radio-browser.info,
    // 動態搜全世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 嘅
    // javadoc) - 獨立一個 field/一套 method, 唔同 currentMusicPlayer (本地檔案)
    // 共用, 理由同 currentMusicPlayer 唔同 currentRingtonePlayer 一樣: 三種播放
    // 應該可以互不影響咁各自播/停 (例如轉緊台嗰陣唔應該累到本地音樂都要停)。同
    // 本地音樂/鈴聲最大分別: 呢度個 data source 係網絡 URL, prepareAsync() 依賴緊
    // 網絡連線, 比本地檔案更容易因為網絡問題觸發 onError - 呢個係
    // playRadioStream() 特登保留 onErrorListener 有做嘢 (清 currentRadioPlayer)
    // 嘅原因, 等下一次 "轉台" 唔會撞到一個已經死咗但冇清走嘅 reference。
    private android.media.MediaPlayer currentRadioPlayer;

    /** 現正播緊嘅電台 Radio Browser stationuuid, null = 冇播緊 - 純粹俾
     *  audio/radio/status 呢個 HTTP endpoint 顯示用, 唔影響播放邏輯本身。 */
    private volatile String currentRadioStationId;

    /** 現正播緊嘅電台名 (Radio Browser 嘅 "name") - 同 currentRadioStationId 一齊
     *  存, 純粹俾 audio/radio/status 直接顯示用, 唔使為咗攞返個名再打一次 API。 */
    private volatile String currentRadioStationName;

    /** 播放一個電台嘅直播串流 - 同 playLocalMusicFile()/playRingtoneUri() 一樣嘅
     *  STREAM_MUSIC/prepareAsync()/auto-release 形狀, 但呢度 setDataSource() 收嘅
     *  係網絡 URL (Radio Browser struct 嘅 "url_resolved" - 官方文件建議用呢個
     *  唔係 "url": url_resolved 已經解析咗 playlist/HTTP redirect, 唔使呢部機自己
     *  再識 parse .pls/.m3u, 對一個冇 yt-dlp 呢類工具嘅 Android 5.1 App 嚟講關鍵),
     *  所以 prepareAsync() 要靠網絡連線先攞到串流真正開始 buffer - 呢個 method
     *  淨係負責觸發, 唔 block caller 等網絡, 由 onPreparedListener 喺真正攞到嘢、
     *  可以開始播嗰刻先 start()。播歌嗰陣順便郁一下嘅 triggerRandomFillerAction()
     *  (見 playLocalMusicFile() javadoc) 呢度冇加 - 電台可以連續播幾個鐘, 唔似
     *  一首歌咁短, 唔應該淨係因為「啱啱轉咗台」就郁一次, 同「播緊嘢嗰陣要睇落
     *  生動」呢個原意唔夾。 */
    private synchronized void playRadioStream(org.json.JSONObject station) {
        stopRadioPlaybackLocked();
        if (station == null) {
            return;
        }
        String url = station.optString("url_resolved", "");
        if (url.isEmpty()) {
            url = station.optString("url", "");
        }
        if (url.isEmpty()) {
            return;
        }
        // 2026-08 修正 (compile error: "local variables referenced from a lambda
        // expression must be final or effectively final") - url 上面因為
        // url_resolved/url 兩個欄位嘅 fallback 邏輯被重新賦值咗一次, 唔再係
        // effectively final, 但下面 setOnErrorListener 個 lambda 要用佢嚟寫
        // log。開多一個唯讀嘅 final 變量專俾 lambda 用, 唔改動上面嘅 fallback
        // 邏輯本身。
        final String resolvedUrl = url;
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(url);
            player.setOnPreparedListener(android.media.MediaPlayer::start);
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (MainActivity.this) {
                    mp.release();
                    if (currentRadioPlayer == mp) {
                        currentRadioPlayer = null;
                        currentRadioStationId = null;
                        currentRadioStationName = null;
                    }
                }
                Log.w(TAG, "Radio stream playback error: what=" + what + " extra=" + extra
                        + " url=" + resolvedUrl);
                return true;
            });
            // 冇設 OnCompletionListener - 電台直播理論上唔會自然「播完」(唔似
            // 本地檔案/鈴聲咁有固定長度), 如果串流中途斷埋, MediaPlayer 會經
            // onError 嗰條路反映, 唔會經 onCompletion。
            currentRadioPlayer = player;
            currentRadioStationId = station.optString("stationuuid");
            currentRadioStationName = station.optString("name");
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to play radio stream " + url, e);
        }
    }

    private synchronized void stopRadioPlayback() {
        stopRadioPlaybackLocked();
    }

    private void stopRadioPlaybackLocked() {
        if (currentRadioPlayer != null) {
            try {
                currentRadioPlayer.stop();
            } catch (Exception e) {
                // 見 stopLocalMusicPlaybackLocked() 個 comment - 同一種 race, 吞咗就得。
            }
            try {
                currentRadioPlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentRadioPlayer = null;
        }
        currentRadioStationId = null;
        currentRadioStationName = null;
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
                // speech_understandText() is a different AIDL entry point that returns
                // in ~1ms with no callback firing on this firmware - it does not appear
                // to reach a real engine,
                // matching HelloAlpha's own note that speech_initGrammar "compiles but
                // never reaches the active engine". This Local_Result path is the only
                // one confirmed working end-to-end.
                // NOTE: wakeup direction is NOT parsed here - it arrives via the separate
                // com.ubtechinc.services.SPEECH_DIRECTION broadcast, handled in
                // RobotEventReceiver, which is where the servo-19 turn is triggered.
                // 2026-08 修正: 「語法識別」(grammar recognition, logcat 見
                // SpeechManager 印 "语法识别成功:...type:1") 呢條 ASR 路徑同
                // 「聽寫識別」(dictation, type:0) 唔同 - onServerCallBack() 呢度
                // 收到嘅 text 唔係純文字, 而係機身 iFlytek SDK 未解構嘅原始 JSON
                // 字串, 例如 {"text":"你的爸爸是谁啊","rc":4} (rc = 識別結果嘅
                // confidence/類型代碼, 呢度冇用到, 淨係抽 text field)。之前呢個
                // 未解構嘅 JSON 字串會直接:
                //   1) 塞落 asr_result 嘅 text field, 令對話界面 user 氣泡顯示
                //      成句 raw JSON 而唔係純文字;
                //   2) 送去 IflytekSemanticMatcher.match(), 因為 match() 有做
                //      q.contains(e.q) 嘅 fuzzy 包含匹配, 個 JSON 字串好可能
                //      「碰巧」包含到問法庫入面某條短問法做子字串而match中 (例如
                //      實測見到 {"text":"我煮的不开心","rc":4} 撞中「不开心」),
                //      但正常情況下 (問法冇喺個 JSON 字串裡面湊巧出現做子字串)
                //      就乜都match唔中, 對話界面睇唔到任何 assistant 回覆。
                // 呢度先抽出返真正嘅 text field (抽唔到就當原文處理, 保持同
                // type:0 聽寫路徑一致嘅 fallback 行為), 先至送去下面嘅
                // asr_result/parseLocalResult/handleIflytekSemanticText。
                //
                // 2026-08 修正: extractGrammarResultText() 只識抽 iFlytek JSON
                // 格式 {"text":"...","rc":4}，對於 Nuance 嘅
                // "Local_Result:rule:QA action:QA_CHATTING tag:How do you do"
                // 格式會原樣返回成句 raw string，令對話界面顯示成句
                // Local_Result 而唔係淨係 tag 後面嘅辨識文字。呢度加多層判斷：
                // 如果係 Local_Result 格式，用 fieldBetween() 抽 tag: 後面嘅文字。
                String rawText = extractGrammarResultText(text);
                final String recognizedText;
                if (rawText != null && rawText.startsWith(LOCAL_RESULT_PREFIX)) {
                    recognizedText = fieldBetween(rawText, "tag:", null);
                } else {
                    recognizedText = rawText;
                }
                EventBus.get().publish("asr_result", "{\"text\":\"" + jsonSafe(recognizedText) + "\"}");
                // 2026-08 移除 handleIflytekSemanticText() 呢個 call:
                // 前端已經統一用 triggerIflytekSimulate() (speech/iflytek_simulate)
                // 處理所有 5 種輸入方法嘅語意配對 + TTS + 動作,
                // 如果後端都做就會雙重 TTS 播兩次。
            }

            @Override
            public void onServerPlayEnd(boolean isEnd) {
                stopMouthLedForTts();
                robotTtsSpeaking = false;
                EventBus.get().publish("tts_end", "{\"isEnd\":" + isEnd + "}");
            }
        }, new Alpha2SpeechMainServiceUtil.ISpeechInitInterface() {
            @Override
            public void initOver() {
                speechReady = true;
                EventBus.get().publish("speech_ready", "{\"ready\":true}");
                // 2026-08 新增: 「自我打斷」由用戶可揀嘅 checkbox 改做恆常開 -
                // UI 個揀擇掣已經移除 (見 index.html), 呢度喺 speech 引擎 ready
                // 嗰刻主動開一次, 唔使用戶手動揀。speech/self_interrupt endpoint
                // 保留 (Blockly 積木 alpha_speech_self_interrupt 仲用緊), 淨係
                // UI 主開關拎走。
                robot.speech_setSelfInterrupt(true);
            }
        }, CustomLanguage.DEFAULT_LANGUAGE);

        registerWakeupDirectionListener();
        registerChestMuteKeyTestListener();
        registerAlpha2PirAlertListener();
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

    // -- iFlytek 語意配對: 完全取代悠聊 APK (com.ubtech.iflytekmix) -------------------
    //
    // 悠聊 APK 反編譯還原返嚟嘅完整 pipeline (見對話 history) 係:
    //   機身 ASR 辨識完一句話 -> JsonResultParse 解析做 operation+slots
    //     -> RobotActionBusiness.startBusiness(): TTS(200ms sleep)Action
    // OpenAlpha2 已經有自己嘅 robot.speech_startTTS()/robot.action_PlayActionName(),
    // 唔需要悠聊嗰層 RobotHandle wrapper, 淨係需要搬「文字 -> operation/答案/動作」
    // 呢層語意配對 (IflytekSemanticMatcher, 由悠聊 assets/local_semantic 嗰 850 條
    // 問法還原) 同埋呢個時序。
    //
    // 掛喺邊: 唔再掛喺 onServerCallBack() (見上面 2026-08 移除嗰個 comment) - 前端
    // 統一經 speech/iflytek_simulate 觸發, 令所有輸入方法 (真人講嘢/打字模擬) 都行
    // 同一條路, 避免雙重 TTS。中英文由 looksChinese() 判斷, 淨係睇輸入文字內容,
    // 唔理個 ASR engine 而家 set 緊邊種語言。

    /** TTS 之後等幾耐先播動作, 跟返悠聊 RobotActionBusiness.startBusiness() 反編譯
     *  出嚟嘅原本時序 (先 TTS, sleep 200ms, 先至播動作 - 兩者係分開、非同步嘅 AIDL
     *  call, 淨係靠呢個 sleep 頂住, 冇等 TTS 真係播完先郁)。用戶已確認跟返悠聊原本
     *  咁做, 唔改做等 TTS 播完先郁。 */
    private static final int IFLYTEK_TTS_TO_ACTION_DELAY_MS = 200;

    /** 判斷一句輸入文字係咪應該用中文 matcher 處理: 有任何 CJK 統一表意文字 (漢字)
     *  就當中文, 完全冇就當英文。2026-08 特登揀呢個做法, 唔靠 speech/set_asr_engine
     *  嗰個手動語言設定, 因為 iFlytek 引擎本身可能自動偵測用戶講緊咩語言, 淨係睇辨識
     *  出嚟嘅文字內容本身最可靠。中英文夾雜嘅句子 (例如 "跳個 dance") 會因為有漢字而
     *  當中文 - 呢個係刻意嘅簡化, 唔追求完美嘅語言偵測, 對呢個用途已經夠準。 */
    private static boolean looksChinese(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    /** IflytekSemanticMatcherEn.MatchResult -> IflytekSemanticMatcher.MatchResult 嘅
     *  薄轉接層。兩個 class 嘅 MatchResult 結構完全一樣 (question/type/operation/
     *  slot/answer/actionId), 但屬於唔同 class 嘅 nested type, Java 唔會自動當佢哋
     *  係同一個型別 - 呢個 method 純粹做欄位複製, 等 handleIflytekSemanticText() 嘅
     *  下半部分 (publish event、TTS/動作執行) 唔使為中英文分別寫多一份。 */
    private static IflytekSemanticMatcher.MatchResult toZhResult(IflytekSemanticMatcherEn.MatchResult en) {
        if (en == null) return null;
        return new IflytekSemanticMatcher.MatchResult(
                en.question, en.type, en.operation, en.slot, en.answer, en.actionId);
    }

    /** 將一句文字 (可能係 iFlytek 引擎真正辨識到嘅, 亦可能係 speech/iflytek_simulate
     *  呢個 endpoint 用嚟測試嘅打字輸入) 對照 1000 條問法配對, 命中就做返悠聊原本嘅
     *  「先 TTS、再隔 200ms 播動作」流程。搵唔到就乜都唔做 (唔係錯誤 - 用戶講嘅嘢唔喺
     *  嗰 1000 條入面係好正常嘅事, 靜靜哋唔回應好過亂噏一個唔相關嘅回覆), 回傳 null。
     *
     *  中英文用邊個 matcher 由 looksChinese() 判斷 - 有漢字用 IflytekSemanticMatcher
     *  (中文, iflytek_semantic_zh.json), 冇就用 IflytekSemanticMatcherEn (英文,
     *  iflytek_semantic_en.json)。兩個 class 結構一致、資料獨立, 唔會互相影響。
     *
     *  回傳 MatchResult (而唔係 void) 係俾 speech/iflytek_simulate 呢個 endpoint 用嚟
     *  即時話俾前端知「配對中未」, publishEvent=false 個 overload 唔會再經 EventBus
     *  publish 多一次 (前端 sendSpeechChatText() 已經即時用 HTTP response 顯示)。
     *
     *  TTS/動作執行本身依然喺獨立 thread 度做 AIDL blocking call, 唔喺呼叫者嘅
     *  thread (可能係 HTTP worker thread) 度直接做 - 同 triggerRandomFillerAction()
     *  一致嘅安全做法。 */
    private IflytekSemanticMatcher.MatchResult handleIflytekSemanticText(final String text) {
        return handleIflytekSemanticText(text, true);
    }

    private IflytekSemanticMatcher.MatchResult handleIflytekSemanticText(final String text,
                                                                         final boolean publishEvent) {
        final boolean chinese = looksChinese(text);
        if (chinese) {
            if (iflytekMatcher == null) return null; // onCreate() 未行完 (理論上唔會, 保險)
        } else {
            if (iflytekMatcherEn == null) return null;
        }

        final IflytekSemanticMatcher.MatchResult result = chinese
                ? iflytekMatcher.match(text)
                : toZhResult(iflytekMatcherEn.match(text));
        if (result == null) {
            return null; // 搵唔到對應問法 - 靜靜哋唔做嘢, 唔算錯誤
        }
        if (publishEvent) {
            EventBus.get().publish("iflytek_match",
                    "{\"question\":\"" + jsonSafe(result.question) + "\","
                            + "\"type\":\"" + jsonSafe(result.type) + "\","
                            + "\"operation\":\"" + jsonSafe(result.operation) + "\","
                            + "\"answer\":\"" + jsonSafe(result.answer) + "\","
                            + "\"actionId\":\"" + jsonSafe(result.actionId) + "\"}");
        }

        // ASR 呢邊固定用 iFlytek engine (唔再用 Nuance 做 ASR) - iFlytek 一個 engine
        // 就識辨識中文同英文, 用戶已經確認唔會再切返 Nuance 做 ASR。TTS 呢邊就跟返
        // 辨識出嚟嘅語言揀返啱嘅 TTS engine 讀出答案: 中文答案用 "zh_cn" (行 iFlytek
        // TTS), 英文答案用 "en_us" (行 Nuance TTS, 呢個 project 一貫做法 - 見
        // "Fixed to Nuance/en_us" 嗰個 self.robot.speak MCP tool 附近嘅 comment)。
        // 即係話 ASR 同 TTS 用緊嘅 engine 唔係同一個, 呢度純粹係按語言揀返把聲靚嘅
        // TTS engine, 唔關 ASR engine 事。
        final String ttsLang = chinese ? "zh_cn" : "en_us";
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (result.answer != null && !result.answer.isEmpty()) {
                    robot.speech_startTTS(ttsLang, result.answer, null);
                }
                if (result.actionId == null) {
                    return; // CHAT 類或者部分 FUNCTION 類冇對應動作, TTS 完就完
                }
                try {
                    Thread.sleep(IFLYTEK_TTS_TO_ACTION_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String actionId = result.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // 2026-08 新增: 用戶講到分類名 (例如「跳舞」/"Dance for me") 但冇
                    // 講出具體邊個動作 - 喺 202 動作清單嘅對應分類 (例如
                    // DANCE_KIDS/YOGA_ANY) 入面隨機揀一個。同下面 "__RANDOM__"
                    // (完全唔限分類, 202 個隨便揀) 唔同, 呢個係分類限定嘅隨機。中英文
                    // matcher 共用同一份 action_category_pools.json, 邊個 instance
                    // call 結果都一樣, 淨係跟返 chinese 呢個 flag 揀返啱嘅 instance。
                    actionId = chinese
                            ? iflytekMatcher.resolveCategoryRandomActionId(actionId)
                            : iflytekMatcherEn.resolveCategoryRandomActionId(actionId);
                } else if ("__RANDOM__".equals(actionId)) {
                    // TFBOY 呢類 operation 喺原廠問法入面冇固定動作 - 跟返
                    // triggerRandomFillerAction() 已有嘅隨機動作池 (202 動作入面
                    // 「隨機短/長」開頭嗰批, 專門用嚟做呢種「郁下等佢生動啲」效果)。
                    actionId = resolveRandomActionId();
                }
                if (actionId != null) {
                    robot.action_PlayActionName(actionId);
                }
            }
        }, "IflytekSemanticAction").start();
        return result;
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

    // -- 心口 mute 鍵 (-111) 測試: 撳一下紫燈長開, 再撳一下熄燈 ------------------------
    // 2026-08 新增: 純粹用嚟目視確認 RobotEventReceiver 個 CHEST_ACTION case 有冇
    // 真係收到心口 mute 鍵 (chest cmd = -111) 嘅 broadcast - 呢個唔係最終功能,
    // 純粹一個「有冇反應」嘅測試訊號 (見 RobotEventReceiver 嗰個 case 嘅 comment)。
    // 官方 firmware 呢粒鍵本身完全冇連任何 LED, 呢度嘅紫燈完全係呢個專案自己加,
    // 同 sonar obstacle 用嘅係同一個 setHeadEyeLedLong(5, 9) helper (5=紫,
    // 9=最光, 見 applyObstacleIndicator() 個 comment)。
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
                                setHeadEyeLedLong(5, 9); // 5 = 紫 (purple)
                            } else {
                                robot.header_stop5MicEarLED();
                                robot.header_stop5MicEyeLED();
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "registerChestMuteKeyTestListener: 5-mic head/eye LED path failed", t);
                        }
                    }
                }).start();
            }
        });
    }

    // -- Alpha2 PIR 警示反應 (LED+鈴聲) ----------------------------------------------
    // 2026-08-15 新增: 監聽獨立嘅 "alpha2_pir_state" event (見 RobotEventReceiver
    // 個 CHEST_ACTION case 入面 alpha2_pir_state 嗰段 comment), 用 Alpha2 backend
    // 嘅 LED API 觸發 LED/鈴聲。
    //
    // 真機已確認: PIR raw 事件 (chest cmd=-109, "PIR HUMON DETECT") 會正常觸發 (見
    // logcat_2026-08-15_12-06-19.txt) - 呢部機 (1.1.7.3) 底層 chest MCU 硬件本身
    // 識做 PIR, 淨係之前 1.1.7.3 呢個 Android apk 版本冇代碼處理呢個 case, 已喺
    // RobotEventReceiver 補返。
    //
    // LED 部分: 眼/頭 5-mic LED 長著紅燈 (setHeadEyeLedLong(1, 9)), 顏色代碼 1=紅,
    // 已喺 "led/head/set" case 上面嗰段 comment 真機確認過 (color: 1=紅 2=綠 3=藍
    // 4=黃 5=紫 6=青 7=白)。
    //
    // 2026-08-15 真機測試 (PIR sample test) 確認: 呢部機頭板嘅 5-mic head/eye LED
    // 對 PIR 警示反應係有效嘅 (眼/頭會著紅燈), 唔似之前 applyObstacleIndicator()/
    // registerChestMuteKeyTestListener() 撞到嘅情況 (header_ledSetHead5Mic/
    // header_ledSetEye5Mic 全部 preset 都回 API_ERROR_FAILED) - 兩者用嘅係唔同
    // AIDL 方法/參數組合, 唔可以直接假設「一個唔得全部都唔得」。所以 PIR 警示淨係
    // 用呢一條路, 冇再加 mouth LED breathing 做 fallback, 咀唔使閃, 同鈴聲一齊
    // 淨係眼/頭長著紅燈。

    private volatile boolean alpha2PirAlertActive = false;
    // 獨立於 pir/set 感應器硬件開關本身 - 預設關, 使用者要自己揀開先會有 LED/聲反應,
    // 避免一開機就無啦啦閃紅燈/響鈴。
    private volatile boolean alpha2PirAlertEnabled = false;

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
                // onEvent() 喺 main thread 行 - AIDL/JNI LED call 搬去 background
                // thread, 唔好用主線程, 同專案一貫做法一致 (見
                // registerPirAlertListener()/registerChestMuteKeyTestListener())。
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        applyAlpha2PirLedAndSound(triggered);
                    }
                }).start();
            }
        });
    }

    /** Toggled from "pir/alert_enabled" - see the case for this above. */
    void setPirAlertEnabledAlpha2(boolean enabled) {
        alpha2PirAlertEnabled = enabled;
        if (!enabled && alpha2PirAlertActive) {
            // 中途關咗個開關都要即刻熄返而家亮緊嘅燈/停緊嘅聲, 唔止係唔再對之後嘅
            // 事件有反應。
            new Thread(new Runnable() {
                @Override
                public void run() {
                    applyAlpha2PirLedAndSound(false);
                }
            }).start();
        }
    }

    private synchronized void applyAlpha2PirLedAndSound(boolean triggered) {
        if (!alpha2PirAlertEnabled && triggered) {
            return; // 開關閂咗 - 唔理新觸發 (但已經亮緊嗰個仍然可以經
                     // setPirAlertEnabledAlpha2(false) 熄返)。
        }
        if (triggered == alpha2PirAlertActive) {
            return; // 避免每次重複收到同一個狀態嘅事件都重新送一次 LED/聲, 同
                     // onSonarDistanceReceived() 一致嘅做法。
        }
        alpha2PirAlertActive = triggered;
        // 2026-08 新增: 用戶提出一個關鍵盲點 - 呢個 PIR 警示 (獨立網頁「PIR 測試」
        // 開關 alpha2PirAlertEnabled 控制, 原意純粹俾用戶喺 web UI 度自己測試 PIR
        // 感應器有冇反應) 同 XiaoZhi 常開對話期間嘅 self.robot.led_set_head/
        // led_set_eye MCP tool, 兩者完全獨立、互不知情, 但用緊同一份 head/eye
        // LED 硬件資源。如果兩者同時觸發, reassertHeadEyeLed() 嗰個持續補發
        // thread 會不斷同呢度嘅 setHeadEyeLedLong()/header_stop5MicEarLED() 打
        // 交, 令個 LED 睇落不斷閃/跳色, 就係用戶講嘅「頭LED 仍然同其他 code
        // 相撞」嘅其中一種病灶 (另一種係 alpha2services 內部熄燈循環, 已經喺
        // reassertHeadEyeLed() javadoc 處理)。呢度令 PIR 警示觸發／解除嗰刻都
        // cancel 咗 XiaoZhi 嗰邊嘅持續補發, 等呢個「用戶主動開咗嘅 PIR 測試」
        // 優先贏, 唔會兩份 code 同時不斷寫緊同一個硬件。
        cancelHeadLedReassert();
        cancelEyeLedReassert();
        try {
            if (triggered) {
                setHeadEyeLedLong(1, 9); // 1 = 紅 (red), 9 = 最光
            } else {
                robot.header_stop5MicEarLED();
                robot.header_stop5MicEyeLED();
            }
        } catch (Throwable t) {
            // 2026-08-15 更新: 真機已確認呢部機頭板嘅 5-mic head/eye LED 對 PIR
            // 警示反應有效 (眼/頭會著紅燈), 唔再需要 mouth LED 做 fallback -
            // 呢個 try/catch 純粹保留做保護, 防止呢句 AIDL call 出意外時累到成個
            // listener thread 死埋。
            Log.w(TAG, "applyAlpha2PirLedAndSound: 5-mic head/eye LED path failed", t);
        }
        if (triggered) {
            playPirAlertCue(); // lazy-lookup 好嘅 "Heaven" 鈴聲, 見 playPirAlertCue()
        } else {
            stopRingtonePlayback();
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


    /** 列出而家 androidTts 綁緊嗰個 engine 識嘅所有語言/國家變體, 由
     *  speech/tts_languages endpoint 用 (揀咗 engine=android 先顯示語言揀擇)。
     *  getVoices() (API 21+) 做主要來源, 得出空清單先跌落去
     *  ACTION_CHECK_TTS_DATA legacy fallback - SVOX Pico 完全冇實作
     *  getVoices(), IPC 層直接 throw "NullPointerException: collection ==
     *  null" (唔係回空 collection, 已經俾 try/catch 接住冇 crash, 但結果係空
     *  清單), Google TTS 就用 getVoices() 攞到完整清單, 唔使行 legacy 呢條路。
     *
     *  用 getVoices() 而唔係 ACTION_CHECK_TTS_DATA 做主要來源嘅原因: 呢部機冇
     *  Google Play Store, Google TTS 嘅 ACTION_CHECK_TTS_DATA 淨係答到出廠
     *  內建嗰一個國家變體 (中文得 zh-TW, 英文得 en-US) - getVoices() 直接問
     *  engine 自己嘅完整 voice metadata, 唔受呢個限制。 */
    private List<TtsLanguageOption> listAndroidTtsLanguages(Locale displayLocale) {
        List<TtsLanguageOption> viaVoices = checkTtsDataViaGetVoices(displayLocale);
        if (!viaVoices.isEmpty()) {
            return viaVoices;
        }
        // getVoices() 得出嚟空清單 (engine 未 ready、throw 咗 exception 俾接住、
        // 或者根本冇實作) - 唔好就咁畀個空清單用戶, 跌落去舊方法試多次。
        return checkTtsDataSyncLegacy(displayLocale);
    }

    /** 用 TextToSpeech.getVoices() 窮舉現時 androidTts 綁緊嗰個 engine 識嘅所有
     *  voice/語言變體 - 見 listAndroidTtsLanguages() javadoc 解釋點解揀呢個
     *  API 做主要來源。 */
    private List<TtsLanguageOption> checkTtsDataViaGetVoices(Locale displayLocale) {
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
        Map<String, TtsLanguageOption> options = new HashMap<>();
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
            options.put(tag, new TtsLanguageOption(tag, displayName));
        }
        List<TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<TtsLanguageOption>() {
            @Override
            public int compare(TtsLanguageOption a, TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    /** Fires TextToSpeech.Engine.ACTION_CHECK_TTS_DATA at whichever engine androidTts
     *  is currently bound to, and blocks (with a timeout) for the result -同 Android
     *  自己「文字轉語音輸出」設定畫面起「已安裝」清單用緊嗰個 intent 一樣。Result
     *  extras 用 lang-COUNTRY-variant 3 個字母 ISO code (例如 "eng-USA"), 唔係
     *  BCP-47 - iso3ToIso1Language()/iso3ToIso1Country() 轉做 2 個字母先起
     *  Locale。 */
    private List<TtsLanguageOption> checkTtsDataSyncLegacy(Locale displayLocale) {
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
            checkIntent.setPackage(enginePkg); // 指定嗰個 engine, 唔係「隨便邊個應用程式贏咗就用邊個」
            startActivityForResult(checkIntent, TTS_DATA_CHECK_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CHECK_TTS_DATA launch failed for engine=" + enginePkg, e);
            return new ArrayList<>();
        }
        try {
            // 3 秒對一個正常應該即時、冇網絡/磁碟 IO 嘅本機查詢嚟講已經好夠 - 過咗
            // 仲未返就即係有嘢唔妥 (engine 冇回應), 應該回空清單俾 caller, 唔應該
            // 令個 HTTP request 無限期卡住。
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
        Map<String, TtsLanguageOption> options = new HashMap<>();
        for (String voice : raw) {
            // "eng" 或 "eng-USA" 或 "eng-USA-FEMALE" - 拆開, 淨係要 lang[-country],
            // 拎走第 4 段開始嘅任何 variant 後綴 (唔係 Locale 嘅 country, 亦
            // toLanguageTag() 冇任何位置擺任意 engine-specific variant 標籤)。
            String[] parts = voice.split("-");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            // 兩段都係 ISO-639-2/ISO-3166-1 ALPHA-3 (3 個字母), 例如 "eng"/"USA" -
            // user-confirmed 真機 bug: new Locale("eng").toLanguageTag() 唔會變返
            // "en" 咁 (2 個字母個陣先會)。Locale 個 constructor 完全唔會將 3 個字母
            // ISO code 轉做 2 個字母嘅對應版本 - 佢淨係將俾佢嘅字串照原樣存低,
            // 所以 toLanguageTag() 之前會直接漏晒啲 raw 3 字母 code 出嚟 ("ara",
            // "ben", "eng", ...), 而唔係啱嘅 BCP-47 tag。iso3ToIso1Language()/
            // iso3ToIso1Country() 就做緊呢個轉換, 靠 Locale.getAvailableLocales()
            // 反查, 因為 Locale 本身冇「3 個字母轉 2 個字母」嘅直接 API。
            String lang2 = iso3ToIso1Language(parts[0]);
            if (lang2 == null) {
                // 認唔到係一個有 2 個字母對應版本嘅 3 個字母 ISO-639-2 code -
                // user-confirmed 真實 case: "yue" (粵語) 根本冇 ISO-639-1 2 個
                // 字母 code, 所以 iso3ToIso1Language("yue") 合理咁回 null, 之前
                // 呢度會直接 "continue" (跳過成個 entry), 靜雞雞漏咗粵語, 雖然
                // Google TTS 真係裝咗 (logcat 見到 "Download of yue-hk started"/
                // "Download yue-hk Success true")。BCP-47 (同 Java 嘅 Locale)
                // 都接受 3 個字母嘅 primary language subtag 直接用 (IANA 嘅
                // language subtag registry 本身有列 "yue" 做合法 primary
                // subtag) - 所以跌返去用 3 個字母 code 原樣, 唔好拎走成個語言。
                lang2 = parts[0];
            }
            String country2 = null;
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                country2 = iso3ToIso1Country(parts[1]);
                if (country2 == null) {
                    // 同上面語言果句一樣嘅道理 - 保留原本 3 個字母 country code
                    // 好過拎走 (雖然唔係 ISO-3166-1 alpha-2, 但仍然有意義)。
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
            options.put(tag, new TtsLanguageOption(tag, displayName));
        }
        List<TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<TtsLanguageOption>() {
            @Override
            public int compare(TtsLanguageOption a, TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    private static volatile Map<String, String> iso3LanguageMap;
    private static volatile Map<String, String> iso3CountryMap;

    /** Lazily builds (一次過, cache 落 static field) 一個由 ISO-639-2 3 個字母語言
     *  code 去 ISO-639-1 2 個字母 code 嘅反查表, 因為 java.util.Locale 冇呢個方向
     *  嘅直接 API - 淨係有正向嘅 Locale.getISO3Language() (由一個已經係 2 個字母
     *  嘅 Locale 出發)。用 Locale.getAvailableLocales() (呢個 JVM 識嘅全部
     *  Locale) 起, 覆蓋範圍遠比手寫一個表齊全。 */
    private static String iso3ToIso1Language(String iso3) {
        Map<String, String> map = iso3LanguageMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String lang2 = l.getLanguage();
                if (lang2.isEmpty()) continue;
                try {
                    String lang3 = l.getISO3Language();
                    // 用 containsKey()+put() 而唔係 putIfAbsent() - user-confirmed
                    // 真機 crash: 呢部機 Android 版本早過 API 24 (Nougat),
                    // Map.putIfAbsent() 係 default method, 淨係 API 24 開始先有
                    // (呢個 app 自己個 minSdkVersion 係 19) - call 落去會 throw
                    // NoSuchMethodError 令成個 app 死埋。containsKey()+put() 用
                    // pre-Java-8/pre-API-24 都有嘅 Map method 做返一樣「keep the
                    // first mapping seen」嘅效果。
                    if (lang3 != null && !lang3.isEmpty() && !map.containsKey(lang3)) {
                        map.put(lang3, lang2);
                    }
                } catch (Exception ignored) {
                    // 有部份 Locale 會喺呢度 throw MissingResourceException - 淨係
                    // 代表嗰一個貢獻唔到映射, 唔係要中止起成個表嘅理由。
                }
            }
            iso3LanguageMap = map;
        }
        return map.get(iso3);
    }

    /** 同 iso3ToIso1Language() 諗法一樣, 但係轉 ISO-3166-1 alpha-3 國家 code
     *  (例如 "USA" -> "US")。 */
    private static String iso3ToIso1Country(String iso3) {
        Map<String, String> map = iso3CountryMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String country2 = l.getCountry();
                if (country2.isEmpty()) continue;
                try {
                    String country3 = l.getISO3Country();
                    // 見上面 iso3ToIso1Language() 點解唔用 putIfAbsent()。
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

    /** langTag 傳返俾 speak(text, langTag)/setLanguage(), displayName 係俾 UI 顯示
     *  嘅名 - 喺 server 端經 Locale.getDisplayName() 起, 唔使前端自己維護一份
     *  tag->name 對照表。 */
    private static final class TtsLanguageOption {
        final String langTag;
        final String displayName;
        TtsLanguageOption(String langTag, String displayName) {
            this.langTag = langTag;
            this.displayName = displayName;
        }
    }

    /** 列出機身裝咗嘅全部 Android TTS 引擎 package name (排咗序) - 由
     *  speech/tts_engines endpoint 用, 等 speech tab 嘅 Android 選項可以揀邊個
     *  引擎講。用一個 throwaway TextToSpeech instance 攞呢個裝置層面嘅清單,
     *  唔綁住現正用緊嗰個 androidTts field - getEngines() 本身唔係
     *  engine-specific, 唔使等 androidTtsReady 先可以問, 用返 live 嘅
     *  androidTts 反而有機會攞到「舊 engine 嗰陣捕捉低」嘅過時清單。 */
    private List<String> listAndroidTtsEngines() {
        List<String> result = new ArrayList<>();
        TextToSpeech probe = null;
        try {
            final CountDownLatch initLatch = new CountDownLatch(1);
            probe = new TextToSpeech(this, status -> initLatch.countDown());
            // getEngines() 本身唔需要 init 完成 (唔係 engine-specific), 但等一下
            // 避免同 constructor 自己嗰個 async setup 撞頭 (部份 OEM engine 見過)。
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

    /** (Re)binds androidTts to a specific TTS engine and wires up the same
     *  OnInitListener/UtteranceProgressListener behaviour every time - called once from
     *  onCreate() with enginePackage=null (device default) and again from
     *  speech/set_tts_engine whenever the user switches engines. The old instance
     *  (if any) is stopped and shut down first, since Android
     *  has no API to rebind an existing TextToSpeech to a different engine in place -
     *  switching means tearing down and constructing a fresh one bound to the new
     *  engine's Service. androidTtsReady is set false for the duration of the rebind so
     *  speak() calls that land mid-switch fail fast (see the "speech/tts" case below)
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
        // engine=android branch. "panel_tts" is the utteranceId passed to speak() there;
        // onStart/onDone/onError all fire on whichever id is currently in flight since
        // QUEUE_FLUSH means only one utterance is ever in flight from this app at a time.
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
                // 同 robot-side TTS 嘅 onServerPlayEnd 一致, publish tts_end
                // 俾前端知道呢句讀完 - 小智 tab 揀咗本地引擎嗰陣靠呢個 event
                // 排隊讀多句回覆 (見 xiaozhiTtsQueue 相關 comment)。isEnd 固定
                // true, Android TTS 冇對應 onServerPlayEnd 個 isEnd 語義, 呢度
                // 冇對應嘅 false case。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }

            @Override
            public void onError(String utteranceId) {
                stopMouthLedForTts();
                // 出錯都要 publish, 唔係前端個 queue 會卡死喺度等一個永遠唔會嚟
                // 嘅 tts_end, 之後全部排緊隊嘅句子都讀唔到。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }
        });
        androidTts = created;
    }

    /** 接住 checkTtsDataSyncLegacy() 發出嘅 ACTION_CHECK_TTS_DATA 結果。淨係
     *  處理呢個 app 自己識嘅 requestCode, 其他一律交返俾 super (雖然目前呢個
     *  app 冇第二個地方用 startActivityForResult(), 但呢個係基本禮貌, 唔應該
     *  吞晒所有 requestCode)。 */
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
            // 2026-08 修 crash: 之前呢度直接 (同步) call disconnect(), 但
            // disconnect() 內部而家會做 sendCloseFrame() (socket write, 完成
            // WebSocket close handshake, 見 XiaozhiClient 個 case 0x8 嘅
            // comment)。onDestroy() 保證喺 main thread 執行, Android 對 main
            // thread 做網絡 I/O 嘅限制唔會因為「呢個 write 好快」就豁免 - 真機
            // 證實會擲 NetworkOnMainThreadException, 令 onDestroy() 本身拋
            // uncaught exception, 導致成個 activity destroy 失敗、app crash
            // (見 logcat FATAL EXCEPTION: main / "Unable to destroy activity")。
            // 呢度將 disconnect() 挪去背景 thread 行 - onDestroy() 唔使等佢做完
            // (fire-and-forget, app 反正就嚟收工, close frame 送唔送到都唔影響
            // 用戶體驗), 淨係要避免喺 main thread 直接觸發網絡 write。
            final XiaozhiClient clientToClose = xiaozhiClient;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    clientToClose.disconnect();
                }
            }, "xiaozhi-destroy-disconnect").start();
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
     * Small namespace ("/api/system/...") for things not tied to the robot AIDL
     * surface itself.
     */
    private HttpServer.ApiResponse handleSystemApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown system endpoint: " + path + "\"}");
        }
    }

    // ---------------- 小智 (XiaoZhi) AI 對話 ----------------
    //
    // "/api/xiaozhi/..." namespace - AI對話 doesn't belong to the robot's own AIDL
    // surface. See XiaozhiClient's class javadoc for the overall protocol/phase-1-scope
    // explanation.
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
                        + "\"autoMode\":" + xiaozhiAutoMode.get() + ","
                        + "\"micActive\":" + xiaozhiAudioController.isCapturing() + ","
                        + "\"micHeld\":" + xiaozhiMicHeld + ","
                        + "\"sessionId\":" + (xiaozhiClient.getSessionId() != null
                                ? "\"" + jsonSafe(xiaozhiClient.getSessionId()) + "\"" : "null") + "}");

            case "activation_status": {
                XiaozhiActivationStatus s = xiaozhiActivationStatus.get();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"stage\":\"").append(s.stageJson()).append("\"");
                if (s.activationCode != null) sb.append(",\"code\":\"").append(jsonSafe(s.activationCode)).append("\"");
                if (s.activationMessage != null) sb.append(",\"message\":\"").append(jsonSafe(s.activationMessage)).append("\"");
                if (s.errorMessage != null) sb.append(",\"error\":\"").append(jsonSafe(s.errorMessage)).append("\"");
                if (s.sessionId != null) sb.append(",\"sessionId\":\"").append(jsonSafe(s.sessionId)).append("\"");
                sb.append("}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "ota_config/get": {
                android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean customEnabled = prefs.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
                String customUrl = prefs.getString(PREF_XIAOZHI_OTA_URL, "");
                String wsUrlOverride = prefs.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "");
                String deviceIdOverride = prefs.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "");
                String tokenOverride = prefs.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "");
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"customEnabled\":" + customEnabled + ","
                        + "\"customUrl\":\"" + jsonSafe(customUrl) + "\","
                        + "\"defaultUrl\":\"" + jsonSafe(XiaozhiOtaClient.DEFAULT_OTA_URL) + "\","
                        + "\"wsUrlOverride\":\"" + jsonSafe(wsUrlOverride) + "\","
                        + "\"deviceIdOverride\":\"" + jsonSafe(deviceIdOverride) + "\","
                        + "\"tokenOverride\":\"" + jsonSafe(tokenOverride) + "\"}");
            }

            case "ota_config/set": {
                // 2026-08 修正: 之前呢度嘅 comment 講「主流自架 server 淨係要 OTA
                // URL, websocket url/token 由 OTA response 夾埋送返嚟, 唔開放獨立
                // 欄位」- 但實測發現唔係全部自架方案都跟足呢個協議形狀返足夠資訊,
                // 用戶手上嘅 server 需要手動填 websocket 地址、MAC/Device-Id、
                // token 先連得到。依家呢三個都開放做可選 override: 留空就繼續行
                // 返原本「淨係 OTA URL, 其餘自動」嗰條路; 有填就用嚟蓋走
                // runXiaozhiActivationFlow() 入面對應嘅自動值 (見嗰邊 comment)。
                boolean enabled = "true".equals(query.get("enabled"));
                String url = query.get("url");
                String wsUrlOverride = query.get("wsUrl");
                String deviceIdOverride = query.get("deviceId");
                String tokenOverride = query.get("token");
                if (enabled) {
                    if (url == null || url.trim().isEmpty()) {
                        return HttpServer.ApiResponse.error("url is required when enabled=true");
                    }
                    url = url.trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return HttpServer.ApiResponse.error("url must start with http:// or https://");
                    }
                    if (wsUrlOverride != null && !wsUrlOverride.trim().isEmpty()) {
                        String trimmed = wsUrlOverride.trim();
                        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
                            return HttpServer.ApiResponse.error("wsUrl must start with ws:// or wss://");
                        }
                    }
                    if (deviceIdOverride != null && !deviceIdOverride.trim().isEmpty()
                            && !isMacShaped(deviceIdOverride.trim())) {
                        return HttpServer.ApiResponse.error(
                                "deviceId must look like a MAC address, e.g. aa:bb:cc:dd:ee:ff");
                    }
                    if (xiaozhiClient.isOpen()) {
                        return HttpServer.ApiResponse.error("disconnect from XiaoZhi first before changing the server");
                    }
                }
                android.content.SharedPreferences.Editor editor =
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.putBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, enabled);
                if (url != null) editor.putString(PREF_XIAOZHI_OTA_URL, url);
                if (wsUrlOverride != null) editor.putString(PREF_XIAOZHI_WS_URL_OVERRIDE, wsUrlOverride.trim());
                if (deviceIdOverride != null) editor.putString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, deviceIdOverride.trim());
                if (tokenOverride != null) editor.putString(PREF_XIAOZHI_TOKEN_OVERRIDE, tokenOverride.trim());
                editor.apply();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 新增: MCP 設定 card 用嘅三個 endpoint。
            //
            // mcp_tools/list 回傳全部 tool (連同已 disable 嗰啲, 等用戶撳返個掣
            // enable), 夾埋每個 tool 目前嘅 enabled 狀態。同官方 xiaozhi.me console
            // 側「MCP接入點」係完全唔同嘅嘢 (嗰個係俾第三方外部工具反過嚟連入小智
            // 用嘅獨立 websocket 端口, 同呢部機自己內建、經 xiaozhiMcpBridge() 暴露
            // 俾遠端 LLM 嘅 tool 冇關係, 唔應該撈埋一齊)。
            //
            // mcp_config/get 攞總開關同逐個 tool 嘅 enabled 狀態; mcp_config/set
            // 寫返總開關或者單一 tool 嘅 enabled 狀態 - listTools()/callTool()
            // (見 xiaozhiMcpBridge()) 會即時反映呢度嘅改動, 唔使重連 XiaoZhi。
            case "mcp_tools/list": {
                org.json.JSONArray fullList = lastFullMcpToolList;
                if (fullList == null) {
                    // 未連過 XiaoZhi/未收過任何 tools/list request - 個 card 應該
                    // 喺用戶未連接之前都睇到有咩 tool 可以 enable/disable, 所以
                    // 呢度逼一次 listTools() 起返份清單 (side effect 會存低落
                    // lastFullMcpToolList, 下次唔使再逼)。
                    try {
                        xiaozhiMcpBridge().listTools();
                    } catch (org.json.JSONException e) {
                        return HttpServer.ApiResponse.error("failed to build tool list: " + e.getMessage());
                    }
                    fullList = lastFullMcpToolList;
                }
                java.util.Set<String> disabledNames = getMcpDisabledToolNames();
                try {
                    org.json.JSONArray toolsWithState = new org.json.JSONArray();
                    for (int i = 0; i < fullList.length(); i++) {
                        org.json.JSONObject tool = fullList.getJSONObject(i);
                        org.json.JSONObject withState = new org.json.JSONObject(tool.toString());
                        withState.put("enabled", !disabledNames.contains(tool.optString("name")));
                        toolsWithState.put(withState);
                    }
                    org.json.JSONObject result = new org.json.JSONObject();
                    result.put("tools", toolsWithState);
                    result.put("mcpEnabled", isMcpEnabled());
                    return HttpServer.ApiResponse.ok(result.toString());
                } catch (org.json.JSONException e) {
                    return HttpServer.ApiResponse.error("failed to build tool list: " + e.getMessage());
                }
            }

            case "mcp_config/get": {
                java.util.Set<String> disabledNames = getMcpDisabledToolNames();
                org.json.JSONArray disabledArr = new org.json.JSONArray();
                for (String n : disabledNames) disabledArr.put(n);
                try {
                    org.json.JSONObject result = new org.json.JSONObject();
                    result.put("ok", true);
                    result.put("mcpEnabled", isMcpEnabled());
                    result.put("disabledTools", disabledArr);
                    return HttpServer.ApiResponse.ok(result.toString());
                } catch (org.json.JSONException e) {
                    return HttpServer.ApiResponse.error("failed to build config: " + e.getMessage());
                }
            }

            case "mcp_config/set": {
                // 兩種用法, 睇 query 帶咩參數:
                //   ?enabled=true|false                  -> 設總開關
                //   ?tool=<name>&enabled=true|false       -> 設單一 tool
                String toolName = query.get("tool");
                String enabledStr = query.get("enabled");
                if (enabledStr == null) {
                    return HttpServer.ApiResponse.error("enabled is required");
                }
                boolean enabled = "true".equals(enabledStr);
                android.content.SharedPreferences.Editor mcpEditor =
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                if (toolName == null || toolName.isEmpty()) {
                    mcpEditor.putBoolean(PREF_XIAOZHI_MCP_ENABLED, enabled);
                } else {
                    java.util.Set<String> disabledNames = getMcpDisabledToolNames();
                    if (enabled) {
                        disabledNames.remove(toolName);
                    } else {
                        disabledNames.add(toolName);
                    }
                    mcpEditor.putString(PREF_XIAOZHI_MCP_DISABLED_TOOLS,
                            android.text.TextUtils.join(",", disabledNames));
                }
                mcpEditor.apply();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 見 xiaozhiTtsEngine field 嘅 javadoc。engine 值: "xiaozhi" (預設,
            // server 送 opus 播放) | "iflytek" | "nuance" | "android" (三者皆
            // 改用本地 speech/tts 讀出, 靜音 opus)。
            case "tts_config/get":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\""
                        + jsonSafe(xiaozhiTtsEngine) + "\"}");

            case "tts_config/set": {
                String engine = require(query, "engine");
                if (!"xiaozhi".equals(engine) && !"iflytek".equals(engine)
                        && !"nuance".equals(engine) && !"android".equals(engine)) {
                    return HttpServer.ApiResponse.error(
                            "engine must be one of: xiaozhi, iflytek, nuance, android");
                }
                xiaozhiTtsEngine = engine;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_XIAOZHI_TTS_ENGINE, engine)
                        .apply();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\"" + jsonSafe(engine) + "\"}");
            }

            case "connect": {
                if (xiaozhiClient.isOpen()) {
                    return HttpServer.ApiResponse.error("already connected - call xiaozhi/disconnect first");
                }
                XiaozhiActivationStatus current = xiaozhiActivationStatus.get();
                if (current.stage == XiaozhiActivationStatus.Stage.CHECKING
                        || current.stage == XiaozhiActivationStatus.Stage.AWAITING_CODE
                        || current.stage == XiaozhiActivationStatus.Stage.POLLING
                        || current.stage == XiaozhiActivationStatus.Stage.CONNECTING) {
                    return HttpServer.ApiResponse.error("activation already in progress - check xiaozhi/activation_status");
                }
                // PHASE 3: the OTA/device-activation handshake (see XiaozhiOtaClient's
                // class javadoc) can take anywhere from a couple seconds (already
                // bound - checkVersion() alone) to however long it takes the person to
                // walk over to a browser and type a code into xiaozhi.me (activation
                // polling) - far too long to hold this HTTP request open. So unlike
                // Phase 1/2's synchronous xiaozhiClient.connect(), this kicks off a
                // background thread and returns immediately; the browser is expected
                // to poll "xiaozhi/activation_status" to follow progress (see
                // XiaozhiActivationStatus's class javadoc for why polling rather than
                // an EventBus push).
                xiaozhiActivationStatus.set(XiaozhiActivationStatus.checking());
                final String deviceId = getXiaozhiDeviceId();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runXiaozhiActivationFlow(deviceId);
                    }
                }, "XiaozhiActivationThread").start();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"message\":\"activation started - poll xiaozhi/activation_status\"}");
            }

            case "disconnect":
                // Tear down any in-progress mic/speaker session first - an open
                // XiaozhiAudioController capture thread holding the mic across a
                // WebSocket disconnect would otherwise leak the mic open with nowhere
                // for encoded frames to go (sendAudioFrame() would just throw
                // "not connected" repeatedly until the next mic/stop). Also clears
                // auto_mode - otherwise a subsequent xiaozhi/connect would immediately
                // re-trigger mic/start per the "auto_mode" case's connect-completion
                // logic, surprising someone who explicitly asked to disconnect.
                // Uses stopXiaozhiMic() (not just stopCapture()/stopPlayback() directly)
                // so mic ownership is actually handed back to alpha2services'
                // wake-word engine (speech_SetMIC(false)) and the mic LED/hold-enforcer
                // thread are torn down too - see stopXiaozhiMic()'s javadoc.
                xiaozhiAutoMode.set(false);
                xiaozhiReconnectAttempts.set(0);
                stopXiaozhiMic();
                stopMouthLedForTts();
                cancelHeadLedReassert(); // 斷開連線就冇必要再持續補發 head/eye LED, 收工
                cancelEyeLedReassert();
                xiaozhiClient.disconnect();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");

            case "mic/start":
                return startXiaozhiMic();

            case "mic/stop": {
                stopXiaozhiMic();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "auto_mode": {
                String enabledStr = require(query, "enabled");
                boolean enabled = "true".equalsIgnoreCase(enabledStr) || "1".equals(enabledStr);
                xiaozhiAutoMode.set(enabled);
                if (enabled) {
                    // Auto-connect + auto-mic in one action - if a session isn't
                    // already open/activating, kick off the same activation flow the
                    // "connect" endpoint uses; mic/start happens once that finishes
                    // (see runXiaozhiActivationFlow()'s CONNECTED branch) rather than
                    // racing it here.
                    if (!xiaozhiClient.isOpen()) {
                        XiaozhiActivationStatus current = xiaozhiActivationStatus.get();
                        boolean activationInFlight = current.stage == XiaozhiActivationStatus.Stage.CHECKING
                                || current.stage == XiaozhiActivationStatus.Stage.AWAITING_CODE
                                || current.stage == XiaozhiActivationStatus.Stage.POLLING
                                || current.stage == XiaozhiActivationStatus.Stage.CONNECTING;
                        if (!activationInFlight) {
                            xiaozhiActivationStatus.set(XiaozhiActivationStatus.checking());
                            final String deviceId = getXiaozhiDeviceId();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    runXiaozhiActivationFlow(deviceId);
                                }
                            }, "XiaozhiActivationThread").start();
                        }
                    } else if (!xiaozhiAudioController.isCapturing()) {
                        startXiaozhiMic();
                    }
                } else {
                    stopXiaozhiMic();
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
            }

            case "send_text": {
                String text = require(query, "text");
                if (!xiaozhiClient.isOpen()) {
                    return HttpServer.ApiResponse.error("not connected - call xiaozhi/connect first");
                }
                // Typed text bypasses the mic entirely - no startCapture()/Opus
                // involved, so this works even on devices where isAudioSupported() is
                // false (see XiaozhiClient.isAudioSupported()'s API-21 gate, which only
                // applies to the Opus mic/speaker path, not plain JSON text messages).
                String sendError = xiaozhiSendDetectTextSafely(text);
                if (sendError != null) {
                    return HttpServer.ApiResponse.error("failed to send text: " + sendError);
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown xiaozhi endpoint: " + path + "\"}");
        }
    }

    /** 抽出自 "send_text" HTTP case 嘅共用邏輯 - 送一句文字入 XiaoZhi 對話, 好似
     *  用戶打字咁。呢個方法本身有阻塞式操作 (Thread.sleep + 阻塞式 WebSocket
     *  send), **call 呢個方法嘅 thread 一定要係一條可以阻塞嘅獨立 worker
     *  thread** (例如 HTTP handler thread, 或者刻意開嘅背景 thread) - 絕對唔可以
     *  喺 BroadcastReceiver.onReceive()、UI thread, 或者任何有時限嘅 callback
     *  入面直接 call, 否則會撞正 Android 嘅 broadcast timeout / ANR 機制。
     *  (2026-08 曾經喺一個粗心嘅版本度, 喺 onPirStateReceived() 呢個
     *  BroadcastReceiver callback 入面直接 call 咗呢個方法冇包多層 thread, 令
     *  PIR 密集 broadcast 嗰陣連環阻塞, 真機實測直情 hold 死成個 system 連 adb
     *  都冇反應 - 依家 onPirStateReceived() 已經改用獨立 thread 包住先至 call
     *  呢個方法, 呢段 comment 記低嗰次教訓, 提醒之後唔好再犯。)
     *
     *  送成功就回傳 null, 失敗就回傳錯誤訊息 (唔拋 exception, 等 caller 自己決定
     *  要唔要俾用戶睇到 / 要唔要 log)。
     *
     *  2026-08: 之前呢度一度以為長文字要自己切段先送得, 因為官方 xiaozhi.me 對
     *  冇標記嘅 "detect" 訊息會拒絕長文字 (錯誤訊息 "detect is only for wake
     *  words, do not send long texts")。反編譯一個第三方 apk 之後搵到根本修法:
     *  送嘅訊息要夾多一個 "source":"text" 同 "session_id" 欄位 (見
     *  XiaozhiClient.sendListenDetectText() javadoc 完整說明) - 加返呢兩個欄位
     *  之後 server 唔會再誤當呢個係 wake-word 事件嚟驗證長度, 所以呢度唔使切段,
     *  一次過送晒就得。
     *
     *  2026-08 再修正 (真機證實嘅第二層問題): 加咗 source/session_id 之後長度
     *  限制係冇再撞到, 但打字輸入依然完全冇反應 (冇 STT/LLM/TTS 回應) - 對照
     *  logcat 先發現原因: 小智常開開住嗰陣 mic 一直開住、持續 send 緊 Opus
     *  binary frame 上去 server (XiaoZhi capture level check 一路有數值,
     *  micActive/micHeld 都係 true), 打字嗰句 detect JSON message 就喺呢股持續
     *  嘅 audio stream 中途插入送出 - server 側極可能將 mic 錄到嘅背景聲音當做
     *  「主要輸入」, 打字嗰句被 audio stream 蓋咗/觸發衝突判斷, 兩者都冇被正常
     *  處理。呢度喺送 detect 之前暫停返 mic capture (唔使斷開成個 XiaoZhi 連線,
     *  淨係停緊送 audio frame), 等個 detect message 係嗰一刻唯一嘅輸入, 送完
     *  之後如果小智常開仲開住就重新開返 mic (跟返
     *  startXiaozhiMic()/stopXiaozhiMic() 已有嘅 mic 生命週期管理)。
     *
     *  2026-08 第三次: 前兩層修法都冇解決「長打字對白仍然唔得」- 呢個仍然係
     *  未確診嘅開放問題, 冇 logcat 可以睇實際 server 回咗啲乜, 唔應該再猜第四種
     *  寫法。呢個方法保持返之前確認過方向啱嘅寫法, 冇再改動送出邏輯本身, 等有
     *  真機 log 先再處理。 */
    private String xiaozhiSendDetectTextSafely(String text) {
        if (!xiaozhiClient.isOpen()) {
            return "not connected";
        }
        boolean micWasActive = xiaozhiAudioController.isCapturing();
        if (micWasActive) {
            stopXiaozhiMic();
            // 俾少少時間等 stopCapture() 真正停咗、最後幾個 in-flight 嘅
            // audio frame 送晒, 先送 detect message, 減少兩條 stream 交錯
            // 嘅機會。
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            xiaozhiClient.sendListenDetectText(text);
        } catch (java.io.IOException e) {
            if (micWasActive && xiaozhiAutoMode.get()) {
                startXiaozhiMic();
            }
            return e.getMessage();
        }
        if (micWasActive && xiaozhiAutoMode.get()) {
            // 2026-08 新增: 實測發現「打字完全送到 (server 冇報錯, {"ok":true}),
            // 但 LLM 完全冇反應」- 對照 logcat 搵到: 之前呢度送完 detect 即刻就
            // startXiaozhiMic(), 中間淨係相隔幾百毫秒就再送咗一個
            // {"type":"listen","state":"start","mode":"auto"} - 兩個連續嘅 listen
            // state 轉換之間冇俾夠時間俾 server 處理完先一個, 好可能令 server 側
            // 將個 session 重置咗/取消咗啱啱先送嗰個 detect 嘅處理, 先再開始一個
            // 新（空）嘅聆聽 session, 令個文字訊息無聲無息咁被蓋過 - 同
            // reassertHeadEyeLed() 講嘅「兩個連續 listen 轉換之間冇讓夠時間」係
            // 同一種問題嘅另一個病徵。呢度俾多 300ms 緩衝先重開 mic, 等 server
            // 有機會先處理完個 detect message。 */
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startXiaozhiMic();
        }
        return null;
    }

    /** Starts the mic capture + playback pair - shared by the "mic/start" HTTP
     *  endpoint, "auto_mode" turning on against an already-connected session, and the
     *  TTS-stop auto-continue path (see XiaozhiClient.TtsStateListener's javadoc). All
     *  three need the exact same start sequence and error handling, so this is the one
     *  place it's implemented. */
    private HttpServer.ApiResponse startXiaozhiMic() {
        if (!XiaozhiClient.isAudioSupported()) {
            return HttpServer.ApiResponse.error("voice chat is not supported on this Android version (requires 5.0/API 21+)");
        }
        if (!xiaozhiClient.isOpen()) {
            return HttpServer.ApiResponse.error("not connected - call xiaozhi/connect first");
        }
        // 2026-08 修正: 之前呢度直接開 XiaozhiAudioController 嘅 AudioRecord, 完全冇
        // 攞返 mic 擁有權 - alpha2services 自己嘅 wake-word 引擎一直持續攞住支 mic,
        // 呢部機嘅音訊 HAL 又唔支援多個 process 同時開 mic input, 所以之前個
        // AudioRecord.startRecording() 實質上一直攞唔到聲。呢度同 handleMicStream()
        // (Speech/Mic tab 嗰個獨立 mic 串流) 一樣, 用 releaseMicForAudioIo() 先攞返
        // mic 擁有權 (speech_SetMIC(true) + 300ms sleep 避開 race - 見
        // releaseMicForAudioIo() javadoc), 先至真正開 AudioRecord。
        releaseMicForAudioIo();
        try {
            xiaozhiClient.sendListenStart();
        } catch (java.io.IOException e) {
            robot.speech_SetMIC(false); // 攞硬件都未開就即刻放棄, 將 mic 還返俾機械人
            return HttpServer.ApiResponse.error("failed to signal listen-start: " + e.getMessage());
        }
        // Playback is started alongside capture (not lazily on first incoming frame)
        // so the AudioTrack is already open and prebuffering by the time the server's
        // reply audio starts arriving - opening it reactively on the first
        // onIncomingOpusFrame() would add a full AudioTrack-init delay (which
        // AudioPlaybackController's own findings show can matter) to the very start of
        // the robot's reply.
        XiaozhiAudioController.StartResult playbackResult =
                xiaozhiAudioController.startPlayback(5000);
        if (playbackResult.error != null) {
            robot.speech_SetMIC(false);
            return HttpServer.ApiResponse.error("failed to start playback: " + playbackResult.error);
        }
        // 2026-08 修正: 呢度之前即刻跟住開 startCapture(), 但 logcat 顯示
        // AudioHardwareTiny 岩岩開完 AudioTrack (output) 個 pthread 仲未 settle
        // 就即刻去開 AudioRecord (input), 會撞到
        // "adev_open_input_stream:channel is not support" - AudioRecord 嘅 Java
        // 層 state 照樣顯示 STATE_INITIALIZED (呃到 startCapture() 入面嗰個
        // check), 但底層 HAL 實際上開input stream 失敗, 導致 .read() 攞唔到真正
        // 嘅聲, 送去 XiaoZhi server 嘅係靜音/垃圾 frame, 令語音對話完全冇反應。
        // 呢度加一個短 sleep, 等 output stream 嘅 HAL 初始化完全 settle 先至開
        // input, 避免 output/input 開得太貼撞到呢個 race。
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            robot.speech_SetMIC(false);
            return HttpServer.ApiResponse.error("failed to start mic capture: " + captureResult.error);
        }
        // Mic 擁有權同硬件都成功攞到 - 通知前端將燈號轉綠 (見 index.html
        // #xiaozhiMicLed / app-xiaozhi.js 嘅 xiaozhi_mic_state 事件處理)。
        xiaozhiMicHeld = true;
        startXiaozhiMicHoldEnforcer();
        EventBus.get().publish(XIAOZHI_MIC_STATE_EVENT, "{\"held\":true}");
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    /** Stops the mic capture + playback pair - shared by the "mic/stop" HTTP endpoint
     *  and "auto_mode" turning off. See startXiaozhiMic()'s javadoc for why this is
     *  factored out. */
    private void stopXiaozhiMic() {
        stopXiaozhiMicHoldEnforcer();
        xiaozhiAudioController.stopCapture();
        xiaozhiAudioController.stopPlayback();
        if (xiaozhiClient.isOpen()) {
            try {
                xiaozhiClient.sendListenStop();
            } catch (java.io.IOException e) {
                // Not fatal - the mic/AudioTrack are already released above
                // regardless of whether this final courtesy message reaches the
                // server (e.g. the connection may have just dropped).
                Log.w("MainActivity", "Failed to signal listen-stop: " + e.getMessage());
            }
        }
        if (xiaozhiMicHeld) {
            // 還返 mic 俾機械人自己嘅 wake-word 引擎 - false = "交返麥克風俾機器人"
            // (同 handleMicStream() finally 段嘅寫法一致)。唔理 Mic tab 嗰個
            // micHeldByApp 開關狀態 - 兩個係獨立用途 (XiaoZhi 語音對話 vs
            // Mic tab 手動持有), 邊個都唔應該蓋走對方嘅意圖: 如果用戶喺 Mic
            // tab 另外攞緊 mic, XiaoZhi 呢度都係老實咁還返自己攞嗰份, 冇額外還多次
            // 嘅副作用 (speech_SetMIC(false) 係 idempotent 嘅狀態設定, 唔係計數器)。
            robot.speech_SetMIC(false);
            xiaozhiMicHeld = false;
            EventBus.get().publish(XIAOZHI_MIC_STATE_EVENT, "{\"held\":false}");
        }
    }

    /** 同 startMicHoldEnforcer() (Mic tab 專用) 對應嘅 XiaoZhi 版本 - 背景 thread
     *  持續每 MIC_HOLD_ENFORCER_INTERVAL_MS 重新 call 一次 speech_SetMIC(true),
     *  防止 firmware 內部側面攞返 mic (見 startMicHoldEnforcer() javadoc 嘅原因)
     *  喺小智語音對話進行緊嗰段時間都唔會被靜靜哋搶走。獨立過 Mic tab 嗰條
     *  enforcer thread, 因為兩者嘅生命週期唔同 (呢個跟住 xiaozhiMicHeld, 唔跟
     *  micHeldByApp)。 */
    private void startXiaozhiMicHoldEnforcer() {
        if (xiaozhiMicHoldEnforcerThread != null) return;
        xiaozhiMicHoldEnforced = true;
        xiaozhiMicHoldEnforcerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (xiaozhiMicHoldEnforced && !Thread.currentThread().isInterrupted()) {
                    // 見 robotTtsSpeaking field javadoc - 機身 robot-side TTS
                    // (iflytek/nuance) 正播緊嘢就跳過呢一輪, 唔好用
                    // speech_SetMIC(true) 打斷佢。跳過都唔會令 mic 冇人攞住太耐:
                    // 下一個 tick (MIC_HOLD_ENFORCER_INTERVAL_MS 之後) 會再檢查
                    // 一次, TTS 讀完 (onServerPlayEnd 揭返 false) 就會搶返。
                    if (xiaozhiMicHeld && !robotTtsSpeaking) {
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
        }, "XiaozhiMicHoldEnforcer");
        xiaozhiMicHoldEnforcerThread.start();
    }

    private void stopXiaozhiMicHoldEnforcer() {
        xiaozhiMicHoldEnforced = false;
        if (xiaozhiMicHoldEnforcerThread != null) {
            xiaozhiMicHoldEnforcerThread.interrupt();
            xiaozhiMicHoldEnforcerThread = null;
        }
    }

    /** PHASE 3: runs the full OTA/activation handshake on a background thread (started
     *  from handleXiaozhiApi's "connect" case), publishing progress into
     *  xiaozhiActivationStatus at each stage so "xiaozhi/activation_status" polls can
     *  follow along. On success, hands off to the existing XiaozhiClient.connect() path
     *  exactly as Phase 1/2 did - this method's only job is to arrive at a real
     *  websocket url/token, not to duplicate XiaozhiClient's own connection logic. */
    private void runXiaozhiActivationFlow(String deviceId) {
        // 自訂 server 開關 (見 PREF_XIAOZHI_OTA_CUSTOM_ENABLED/PREF_XIAOZHI_OTA_URL) -
        // 開咗就用自己填嘅 OTA URL, 閂咗跟返官方 xiaozhi.me 預設。OTA endpoint 一般
        // 已經足夠切換成自架 server (check_version 回應通常會夾埋真正嘅 websocket
        // url/token 送返嚟), 但唔係全部自架方案都跟足呢個協議形狀 - 2026-08 新增
        // 咗 wsUrl/deviceId/token 三個可選 override (PREF_XIAOZHI_WS_URL_OVERRIDE
        // 等), 留空就繼續用 OTA response/自動產生嗰個值, 有填就用嚟蓋走, 應付要
        // 手動配置嘅自架 server。
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean customEnabled = prefs.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
        String otaUrl = customEnabled
                ? prefs.getString(PREF_XIAOZHI_OTA_URL, XiaozhiOtaClient.DEFAULT_OTA_URL)
                : XiaozhiOtaClient.DEFAULT_OTA_URL;
        String wsUrlOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "") : "";
        String deviceIdOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "") : "";
        String tokenOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "") : "";
        // deviceId override 要喺 OTA client 建構之前就決定咗 - Device-Id header
        // 由 OTA check_version 個 request 開始就要用同一個值 (同 WebSocket 嗰邊一致,
        // 見 getXiaozhiDeviceId() 嘅 comment), 唔係淨係影響最終 connect() 嗰下。
        // 用一個新嘅 final 變數嚟裝最終值 (而唔係重新賦值 method 個 deviceId
        // parameter 本身) - 呢個 method 尾段嘅匿名類 (DisconnectListener) 有
        // capture 住 deviceId, capture 咗嘅 local variable 一定要係 effectively
        // final, 重新賦值會令個 method 編譯唔到。
        final String effectiveDeviceId = deviceIdOverride.isEmpty() ? deviceId : deviceIdOverride;
        // 2026-08 新增: 存低呢個 session 用緊嘅 clientId, 等 xiaozhiVisionExplain()
        // 可以送返同一個 Client-Id header (見 xiaozhiClientId field 嘅 comment)。
        final String effectiveClientId = java.util.UUID.randomUUID().toString();
        xiaozhiClientId = effectiveClientId;
        XiaozhiOtaClient ota = new XiaozhiOtaClient(otaUrl,
                effectiveDeviceId, effectiveClientId);
        try {
            XiaozhiOtaClient.CheckVersionResult checkResult = ota.checkVersion();

            String wsUrl;
            String wsToken;
            if (!checkResult.needsActivation) {
                wsUrl = checkResult.websocketUrl;
                wsToken = checkResult.websocketToken;
            } else {
                String code = checkResult.activationCode;
                String message = checkResult.activationMessage;
                xiaozhiActivationStatus.set(XiaozhiActivationStatus.awaitingCode(code, message));
                // 2026-08 修正: 之前呢個配對碼淨係經 xiaozhi/activation_status HTTP
                // polling 傳去前端, 完全冇經 EventBus - 令佢喺 WebSocket event log
                // (WebSocketServer 訂閱 EventBus 再 fan-out 落所有已連接嘅瀏覽器
                // tab) 度完全睇唔到, 用戶反映「淨係得聲音, 連 websocket 都無顯示」。
                // 呢句令配對碼都經返正常嘅 EventBus -> WebSocketServer -> 前端
                // event log 路徑推送一次, 同 HTTP polling 途徑並存 (兩者唔衝突,
                // 前端 xiaozhiShowActivationCode() 嗰個 xiaozhiLastShownActivationCode
                // 防重複邏輯係獨立處理 HTTP polling 嗰邊, 唔會受呢個新 event 影響)。
                EventBus.get().publish("xiaozhi_activation",
                        "{\"code\":\"" + jsonSafe(code) + "\",\"message\":\""
                                + jsonSafe(message != null ? message : "") + "\"}");
                // 2026-08: 之前用戶要求取消機身 TTS 讀配對碼, 改為單純靠界面顯示 -
                // 但實測發現冇 TTS 讀出嚟之後配對經常失敗 (真機 logcat 顯示配對碼
                // 出咗之後短時間內就 "Read timed out"), 用戶反映需要機身讀出嚟先
                // 有足夠反應時間去手機/電腦打開 xiaozhi.me 輸入。而家加返呢個
                // call。真正令配對容易 timeout 嘅根源其實喺
                // XiazhiOtaClient.pollActivation() 個單次 HTTP request timeout
                // (10 秒) 太短、一撞到就令成個輪詢直接失敗嗰個 bug, 已經喺嗰邊
                // 修正 (暫時性網絡錯誤而家會重試, 唔會即刻放棄) - 但機身讀出配對碼
                // 本身都係一個用戶想要嘅獨立功能, 兩者都保留。
                speakActivationCode(code);

                xiaozhiActivationStatus.set(XiaozhiActivationStatus.polling(code, message));
                XiaozhiOtaClient.ActivationResult activationResult = ota.pollActivation(
                        checkResult.activationChallenge, checkResult.activationTimeoutMs,
                        new XiaozhiOtaClient.PollCallback() {
                            @Override
                            public void onPoll(int attemptNumber) {
                                Log.i("MainActivity", "XiaoZhi activation poll #" + attemptNumber);
                            }
                        });
                wsUrl = activationResult.websocketUrl;
                wsToken = activationResult.websocketToken;
            }
            if (!wsUrlOverride.isEmpty()) {
                wsUrl = wsUrlOverride;
            }
            if (!tokenOverride.isEmpty()) {
                wsToken = tokenOverride;
            }

            xiaozhiActivationStatus.set(XiaozhiActivationStatus.connecting());
            xiaozhiClient.setMcpBridge(xiaozhiMcpBridge());
            // PHASE 2: wires XiaozhiAudioController as the sink for incoming Opus
            // binary frames - set here (not just once at construction) so a reconnect
            // after disconnect() re-establishes the sink cleanly rather than depending
            // on it having survived from a previous session.
            xiaozhiClient.setAudioSink(new XiaozhiClient.AudioSink() {
                @Override
                public void onIncomingOpusFrame(byte[] opusData) {
                    // 揀咗本地 TTS 引擎 (見 xiaozhiTtsEngine field javadoc) 就
                    // 完全靜音呢條 cloud opus 聲軌 - 淨係唔 forward 去
                    // XiaozhiAudioController, decode/AudioTrack pipeline 本身
                    // 冇改, 一切返去 "xiaozhi" 就即刻恢復原本行為。
                    if (!"xiaozhi".equals(xiaozhiTtsEngine)) {
                        return;
                    }
                    xiaozhiAudioController.onIncomingOpusFrame(opusData);
                }
            });
            // PHASE 4 (小智常開/auto mode): re-wired on every (re)connect for the same
            // reason as setAudioSink() above - see XiaozhiClient.TtsStateListener's
            // javadoc for what this drives.
            //
            // 2026-08 新增: 咀 LED 同步 - 跟返本地 TTS 已有嘅
            // startMouthLedForTts()/stopMouthLedForTts() (MouthLedData breathing 效果),
            // 但呢度要對應 XiaoZhi 自己嗰套 tts state (start/sentence_start/stop, 見
            // websocket.md 同實測 logcat), 唔係本地 TTS 嗰個單次 speech_startTTS。
            // "start" = 呢句/呢段回應開始播 -> 開燈; "sentence_start" 純粹係分咗句
            // (同一段回應入面, 中途唔停) -> 唔使理, 燈應該一路開住直到成段答案講完;
            // "stop" = 成段回應播完 -> 熄燈。用返 xiaozhiAutoMode/mic-restart 嗰個
            // 同一個 case 分支, 熄燈同重新聽係同一個時機發生, 冇額外 race。
            xiaozhiClient.setTtsStateListener(new XiaozhiClient.TtsStateListener() {
                @Override
                public void onTtsState(String stateValue) {
                    if ("start".equals(stateValue)) {
                        startMouthLedForTts();
                        // 2026-08 修正: 用戶要求「random 動作要同 tts 一齊做, 唔係
                        // 講完先做」- 之前錯咗擺喺 "stop" (成段回應播完) 先觸發, 用戶
                        // 見到嘅係機械人企定定聽完成句先郁, 唔係想要嘅「講緊嘢嗰陣
                        // 郁動」效果。依家改喺呢度 ("start", 呢一輪開始講嘢嗰一刻)
                        // 就即刻觸發, 令個動作同把口講嘢大致同步發生。實際執行邏輯
                        // 搬咗去 triggerRandomFillerAction() (見 javadoc) - 播本地
                        // 音樂 (self.media.play_music) 而家都用返同一個 helper 做
                        // 埋一樣嘅「郁下等睇落生動啲」效果。
                        triggerRandomFillerAction();
                    } else if ("stop".equals(stateValue)) {
                        stopMouthLedForTts();
                        if (xiaozhiAutoMode.get()) {
                            startXiaozhiMic();
                        }
                    }
                }
            });
            // 2026-08 新增: 實測發現 server 會喺對話中途主動 send WebSocket close
            // frame 斷開連接 (原因未明, 見 XiaozhiClient 個 case 0x8 新加嘅
            // describeCloseFrame() log, 等下次實機測試可以查到實際 close code) -
            // 之前呢個情況冇處理, 用戶會見到「開關仲係開住」但實際已經斷咗線、mic
            // capture 都停埋, 完全冇任何提示, 睇落好似「講咗嘢但小智完全冇反應」。
            // 依家小智常開開住嗰陣, 意外斷線會自動嘗試重連, 唔使用戶自己發現同手動
            // 閂開個開關。見 xiaozhiScheduleReconnect() 嘅 comment 解釋點防止狂重試。
            xiaozhiClient.setDisconnectListener(new XiaozhiClient.DisconnectListener() {
                @Override
                public void onUnexpectedDisconnect() {
                    // 2026-08 新增: 意外斷線可能發生喺 TTS 播緊嗰段中途 (即係
                    // 收咗 "start" 但未收到對應嘅 "stop"), 咀 LED 會停留喺開住嘅
                    // breathing 狀態, 冇任何嘢會再觸發熄佢 - 呢度保證斷線一定會
                    // 熄返個燈, 唔理之前有冇成功收到 "stop"。
                    stopMouthLedForTts();
                    if (xiaozhiAutoMode.get()) {
                        xiaozhiScheduleReconnect(effectiveDeviceId);
                    }
                }
            });
            xiaozhiClient.connect(wsUrl, wsToken);
            // self.camera.take_photo (xiaozhiTakePhotoAndExplain()) reuses this same
            // bearer token for the vision/explain HTTP call - see that method's
            // comment for why (same auth domain as the WebSocket connection).
            xiaozhiAccessToken = wsToken;
            xiaozhiActivationStatus.set(XiaozhiActivationStatus.connected(xiaozhiClient.getSessionId()));
            // 連接成功, reset 返重試計數 - 下次意外斷線先由 0 開始計 backoff, 唔會
            // 因為之前有過重試就跳去長 delay (見 xiaozhiScheduleReconnect() 嘅
            // comment)。
            xiaozhiReconnectAttempts.set(0);
            if (xiaozhiAutoMode.get()) {
                // 小智常開 was already on when this activation flow was kicked off
                // (see handleXiaozhiApi's "auto_mode" case) - now that the session is
                // actually connected, start listening immediately rather than waiting
                // for the first TTS-stop event, which won't exist yet on a fresh
                // connection.
                startXiaozhiMic();
            }
        } catch (java.io.IOException e) {
            Log.w("MainActivity", "XiaoZhi activation flow failed: " + e.getMessage());
            xiaozhiActivationStatus.set(XiaozhiActivationStatus.error(e.getMessage()));
        }
    }

    /** 小智常開開住嗰陣, WebSocket 意外斷咗線 (見 XiaozhiClient.DisconnectListener)
     *  就自動嘗試重連, 用戶唔使自己發現個開關已經名存實亡再手動閂開一次。
     *
     *  Exponential backoff (5s, 10s, 20s, 最多封頂 60s) 加最多 MAX_RECONNECT_ATTEMPTS
     *  次數上限, 而唔係見到斷線就即刻狂重試: 如果斷線原因係伺服器端持續性問題
     *  (例如 token 失效、伺服器維護), 冇限制咁重試只會不斷再攞新 activation code
     *  (可能重新觸發配對流程) 同浪費電量/流量, 對用戶完全冇幫助; 加咗上限之後,
     *  重試晒都連唔返就停低, 保留返 xiaozhiActivationStatus 嘅 error 狀態俾用戶睇到
     *  發生咗咩事, 好過默默不斷重試落去。用戶隨時可以手動閂開個開關重新嘗試,
     *  重新開始個 backoff (見 runXiaozhiActivationFlow() 連接成功會 reset
     *  xiaozhiReconnectAttempts)。 */
    private void xiaozhiScheduleReconnect(final String deviceId) {
        final int attempt = xiaozhiReconnectAttempts.incrementAndGet();
        final int maxAttempts = 5;
        if (attempt > maxAttempts) {
            Log.w("MainActivity", "XiaoZhi reconnect: giving up after " + maxAttempts
                    + " attempts - leave 小智 off/on to retry manually");
            return;
        }
        long delayMs = Math.min(5000L * (1L << (attempt - 1)), 60000L);
        Log.i("MainActivity", "XiaoZhi reconnect: attempt " + attempt + "/" + maxAttempts
                + " in " + delayMs + "ms");
        // 2026-08 修 crash: 之前呢度 mainHandler.postDelayed() 個 Runnable 入面
        // 直接 call runXiaozhiActivationFlow(), 但 mainHandler 係綁住 main
        // thread 嘅 Handler - postDelayed() 淨係做到「延遲幾多秒先執行」, 個
        // Runnable 本身依然係喺 main thread (Looper.loop()) 度跑, 唔會自動走去
        // 背景 thread。runXiaozhiActivationFlow() 入面 checkVersion() 會做 HTTPS
        // POST (XiaozhiOtaClient.postJsonWithStatus()), 喺 main thread 做網絡
        // I/O 會即刻擲 NetworkOnMainThreadException, 令成個 app crash - 真機
        // 證實: v34 修好咗重連判斷邏輯之後, 重連終於開始真正觸發, 就立即
        // 暴露咗呢個一直潛伏緊、之前因為重連從未真正執行過而冇撞到嘅 bug (stacktrace
        // 見 MainActivity$34.run() -> runXiaozhiActivationFlow() ->
        // XiaozhiOtaClient.checkVersion())。呢度將實際工作 (runXiaozhiActivationFlow)
        // 挪去一個獨立背景 thread, mainHandler.postDelayed() 淨係用嚟做延遲計時,
        // 唔再喺個 Runnable 度直接做網絡 call。
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 用戶可能喺呢段 delay 期間自己手動閂咗個開關 - 呢種情況下唔應該
                // 重連, 尊重用戶嘅意圖。
                if (!xiaozhiAutoMode.get()) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runXiaozhiActivationFlow(deviceId);
                    }
                }, "xiaozhi-reconnect").start();
            }
        }, delayMs);
    }

    /** Speaks the activation code out loud through the robot's own TTS - this is the
     *  "機械人自己讀出嚟" behavior the person asked for, so they don't need to look at
     *  the browser control panel (which may not even be open yet on a first-time setup)
     *  to find the code. Digit-by-digit with pauses would be more reliably understood
     *  than reading "12345" as the number "twelve thousand three hundred forty-five",
     *  but Alpha2RobotApi's TTS has no SSML/digit-mode control exposed - see
     *  AIDL_REFERENCE.md's ISpeechInterface notes, which document no such parameter -
     *  so this spells the digits out with spaces in the text itself
     *  ("一 二 三 四 五" for Chinese TTS), which both iFlytek and Nuance reliably read
     *  as individual digits rather than a single large number. Reads the message twice
     *  with a pause, matching how a person might naturally repeat something they want
     *  written down. Mirrors the existing "speech/tts" endpoint's
     *  STOP_TO_TTS_MIN_GAP_MS race guard and mouth-LED bracket (see handleApi() below)
     *  since this runs from a background thread, not through that HTTP endpoint. */
    private void speakActivationCode(String code) {
        if (code == null || code.isEmpty()) return;
        StringBuilder spoken = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (i > 0) spoken.append(' ');
            spoken.append(c);
        }
        String text = "配對碼係 " + spoken + "。請去 xiaozhi 點 me 輸入呢個碼。再講一次，配對碼係 " + spoken + "。";
        long sinceStopMs = System.currentTimeMillis() - lastSpeechStopAtMs;
        if (sinceStopMs >= 0 && sinceStopMs < STOP_TO_TTS_MIN_GAP_MS) {
            try {
                Thread.sleep(STOP_TO_TTS_MIN_GAP_MS - sinceStopMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        startMouthLedForTts();
        UbxErrorCode.API_ERROR_CODE ttsCode = robot.speech_startTTS("zh_cn", text, null);
        if (!isOk(ttsCode)) {
            stopMouthLedForTts();
            Log.w("MainActivity", "Failed to speak XiaoZhi activation code: " + ttsCode);
        }
        // Not awaited synchronously (unlike the HTTP "speech/tts" endpoint, which
        // returns as soon as playback is *requested*, not finished) - this method
        // itself also returns as soon as playback is requested; the activation flow
        // continues into pollActivation() immediately rather than blocking on TTS
        // playback completion, since there's no strict ordering requirement between
        // "finished speaking" and "started polling the server".
    }

    /** Stable per-install device identifier for the "Device-Id" handshake header -
     *  xiaozhi-esp32's own firmware uses the device's WiFi MAC address here, and
     *  unlike an arbitrary opaque token, the xiaozhi.me OTA server actually validates
     *  this header's *format* server-side (confirmed by a real "Invalid MAC address"
     *  HTTP 400 rejection when this was first tried as a plain UUID string) - so
     *  whatever this returns must look like a MAC address (six colon-separated hex
     *  byte pairs), not just be unique/stable.
     *
     *  Tries the device's real WiFi MAC first (this app targets down to API 19, and
     *  WifiInfo.getMacAddress() only started being locked to the placeholder
     *  "02:00:00:00:00:00" from API 23/Android 6.0 onward for privacy - on this
     *  robot's actual API 22 hardware it should still return the genuine address).
     *  Falls back to a synthetic-but-stable MAC-shaped value derived from a persisted
     *  UUID when the real MAC is unavailable or comes back as that known placeholder -
     *  same "just needs to be stable across app restarts" reasoning as before, just
     *  reshaped to pass the server's format check. The locally-administered bit (0x02
     *  in the first octet) is set on the synthetic address, matching the IEEE
     *  convention for non-hardware-assigned MACs and avoiding any (extremely unlikely
     *  but needless) collision with a real vendor-assigned address space. */
    private String getXiaozhiDeviceId() {
        String realMac = getWifiMacAddress();
        if (realMac != null) {
            return realMac;
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existing = prefs.getString(PREF_XIAOZHI_DEVICE_ID, null);
        if (existing != null && isMacShaped(existing)) {
            return existing;
        }
        String generated = syntheticMacFromUuid(java.util.UUID.randomUUID());
        prefs.edit().putString(PREF_XIAOZHI_DEVICE_ID, generated).apply();
        return generated;
    }

    /** Returns the device's real WiFi MAC address if available and not the
     *  known Android-6.0+ privacy placeholder, otherwise null. */
    private String getWifiMacAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return null;
            String mac = wm.getConnectionInfo().getMacAddress();
            if (mac == null || mac.isEmpty() || "02:00:00:00:00:00".equals(mac)) {
                return null;
            }
            return mac;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMacShaped(String s) {
        return s != null && s.matches("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$");
    }

    private static String syntheticMacFromUuid(java.util.UUID uuid) {
        byte[] bytes = new byte[6];
        long msb = uuid.getMostSignificantBits();
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        bytes[0] = (byte) (bytes[0] | 0x02); // set locally-administered bit
        bytes[0] = (byte) (bytes[0] & ~0x01); // clear multicast bit
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(java.util.Locale.US, "%02x", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /** Builds the MCP bridge XiaozhiClient uses to answer tools/list and tools/call.
     *
     *  PHASE 1 SCOPE: exposes a deliberately small, safe starter set of tools
     *  (play a named action, stop action playback, speak via TTS) rather than the full
     *  AIDL surface from AIDL_REFERENCE.md - MCP tool calls originate from a remote LLM
     *  the operator doesn't directly control turn-by-turn, so starting narrow and
     *  expanding later (once real usage patterns are seen) is safer than exposing
     *  everything (LED raw params, serial port raw commands, etc.) up front. */
    /** Lazily loads + parses assets/web/xiaozhi_actions.json (202 動作, 由用戶提供嘅
     *  202_actions_classified.txt 轉出嚟) - each entry has "id", "nameCn", "nameEn".
     *  "id" is confirmed to be the exact on-device action filename minus the ".ubx"
     *  extension (e.g. id "1464835936031" -> /mnt/internal_sd/actions/1464835936031.ubx),
     *  which is what action_PlayActionName()/AlphaActionServiceUtil.playActionName()
     *  actually needs - see xiaozhiMcpBridge()'s play_action tool schema for how this
     *  is exposed to the LLM. Returns an empty list (never null) on any read/parse
     *  failure, logging the reason once rather than crashing tools/list. */
    private java.util.List<org.json.JSONObject> loadXiaozhiActions() {
        java.util.List<org.json.JSONObject> cached = xiaozhiActionsCache;
        if (cached != null) return cached;
        java.util.List<org.json.JSONObject> result = new java.util.ArrayList<>();
        try (java.io.InputStream in = getAssets().open("web/xiaozhi_actions.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            org.json.JSONArray arr = new org.json.JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadXiaozhiActions: failed to load assets/web/xiaozhi_actions.json: " + e);
        }
        xiaozhiActionsCache = result;
        return result;
    }

    /** Resolves a human-supplied action name (Chinese or English, as passed by the
     *  XiaoZhi LLM to self.robot.play_action) to the actual on-device action id from
     *  xiaozhi_actions.json. Tried in order, first match wins:
     *  1. Exact id match (in case the caller *does* pass a raw id - still valid).
     *  2. Exact match against nameCn or nameEn (case-insensitive for nameEn).
     *  3. Substring match either direction (query contains the action name, or the
     *     action name contains the query) - handles the LLM paraphrasing slightly,
     *     catching the common case of extra/missing words around a name that
     *     otherwise matches exactly.
     *  Returns null if nothing matches closely enough - deliberately does not fall
     *  back to a "best guess" at low confidence, since a wrong action executing on
     *  physical hardware is worse than a clear "not found" the LLM can react to (see
     *  the "raise_left_hand" bug this whole mechanism exists to prevent). */
    private String resolveActionId(String query) {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        String q = query.trim();
        if (q.isEmpty()) return null;

        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("id"))) return a.optString("id");
        }
        for (org.json.JSONObject a : actions) {
            if (q.equals(a.optString("nameCn"))
                    || q.equalsIgnoreCase(a.optString("nameEn"))) {
                return a.optString("id");
            }
        }
        String qLower = q.toLowerCase(java.util.Locale.US);
        for (org.json.JSONObject a : actions) {
            String cn = a.optString("nameCn");
            String en = a.optString("nameEn").toLowerCase(java.util.Locale.US);
            if ((!cn.isEmpty() && (q.contains(cn) || cn.contains(q)))
                    || (!en.isEmpty() && (qLower.contains(en) || en.contains(qLower)))) {
                return a.optString("id");
            }
        }
        return null;
    }

    /** Radio Browser (radio-browser.info) 嘅其中一個 API 主機 - 官方文件建議客戶端
     *  對 "all.api.radio-browser.info" 做 DNS 解析再喺多個鏡像之間揀, 但呢部機冇
     *  DNS SRV/多鏡像 failover 嘅需要 (一個家用機械人, 唔係高流量服務), 直接用
     *  官方文件範例入面出現嘅 de1 呢個固定主機已經足夠, 保持代碼簡單。 */
    private static final String RADIO_BROWSER_API_HOST = "http://de1.api.radio-browser.info";

    /** 官方文件要求每個 request 都帶一個有意義嘅 User-Agent (格式 appname/version),
     *  等佢哋知道邊啲 app 用緊呢個服務 - 呢度老實咁帶返呢個 project 嘅名。 */
    private static final String RADIO_BROWSER_USER_AGENT = "OpenAlpha2/1.0";

    /** 用 Radio Browser 嘅 "Advanced station search" endpoint
     *  (/json/stations/search) 動態搜全世界電台 - 呢個 API 完全公開、免費、唔使
     *  API key, 資料嚟自電台自己申報俾呢個公開 directory 嘅串流位址 (唔係擷取
     *  受保護內容嗰種), 詳見官方文件 docs.radio-browser.info。
     *
     *  參數揀擇 (2026-08 更新, 用戶回報「電台... 只選地方選電台也出現問題,
     *  唔關格式事」之後查 logcat 確認、加強):
     *  - order=votes&reverse=true: 最多人投好嘅電台排先, 幫手過濾走死台/垃圾台
     *  - hidebroken=true: 唔顯示 Radio Browser 定期健康檢查已知播唔到嘅台
     *  - codec=MP3: 淨係要 MP3 - Android 5.1 嘅 MediaPlayer 對 MP3 支援最穩定,
     *    某啲台用嘅 codec (AAC+ 變種、OGG 等) 喺呢個 API level 未必個個都播到
     *  - is_https=false: 淨係要串流位址本身係 http (唔係 https) 嘅台 - 呢個先係
     *    用戶回報問題嘅真正根源 (見下面 "真正根源" 段落), 唔係揀邊個地方/邊個
     *    電台嘅事, 每一次 search_radio/play_radio call 都係同一個 exception。
     *
     *  真正根源 (2026-08 用 logcat 確認): 之前用戶回報「收音機要驗証, 用唔到」
     *  以為係播放格式問題所以加咗 codec=MP3, 但依家憑實際 logcat 見到嘅
     *  exception 係 java.security.cert.CertPathValidatorException: Trust
     *  anchor for certification path not found - 呢個係 Android 5.1 (2015 年
     *  出廠) 嘅系統 CA store 冇收錄現代 CA/certificate chain, 而且 Android 5.1
     *  冇得 OTA 更新系統 CA store, 所以連 https 握手都過唔到, 完全同揀邊個電台
     *  無關: (1) 呢個 API 本身 (RADIO_BROWSER_API_HOST) 已經改用返 http 避開咗
     *  問題; (2) 但 station 個 "url_resolved" 播放位址本身都可能係 https,
     *  MediaPlayer 播 https 串流一樣行 Android 系統嘅 TLS 棧
     *  (android.security.net.config.RootTrustManager), 一樣會撞正同一個
     *  trust anchor 問題 - 所以呢度連搜尋結果都要揀 is_https=false, 先至令
     *  「搵到嘅台」同「播到嘅台」一致, 唔係得個搜尋 API 唔中招、實際播放又中招。
     *
     *  HLS (.m3u8 分段串流, 舊版 MediaPlayer 支援唔穩定、部份仲要
     *  session/token) 呢個特徵冇直接開放做 API 參數, 用 resolveRadioStation()
     *  度、播之前檢查 station 個 "hls" 欄位嚟隔走 (見嗰個 method 嘅 javadoc)。
     *
     *  冇暴露晒 API 成套 filter (country/language/tag 等) 俾 LLM, 保持
     *  self.media.search_radio 個 schema 簡單、淨係一個 query 就夠 - 呢個跟返
     *  self.media.play_music 用 fuzzy match 唔用一大堆 filter 參數嘅同一套
     *  「LLM 用自然語言, 唔使識 API 細節」設計原則。query 直接餵俾 "name" 呢個
     *  參數 (Radio Browser 嘅 name 搜尋本身就係 substring 唔分大小寫, 唔使呢部機
     *  自己再做 fuzzy match)。喺獨立 thread (由 HttpServer 嘅
     *  newCachedThreadPool 保證, 每個 HTTP request 已經喺自己 thread) 度行
     *  blocking HttpURLConnection, 唔喺 UI thread 做, 安全性同
     *  xiaozhiVisionExplainRequest() 一致。 */
    private java.util.List<org.json.JSONObject> searchRadioStations(String query, int limit)
            throws java.io.IOException, org.json.JSONException {
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String urlStr = RADIO_BROWSER_API_HOST + "/json/stations/search?name=" + encodedQuery
                + "&order=votes&reverse=true&hidebroken=true&is_https=false"
                + "&limit=" + limit;

        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", RADIO_BROWSER_USER_AGENT);

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? readFully(is) : "";
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Radio Browser search returned HTTP " + status);
            }
            org.json.JSONArray arr = new org.json.JSONArray(responseText);
            java.util.List<org.json.JSONObject> result = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 揾一個人類語言嘅電台名 - 先喺 lastRadioSearchResults (最近一次
     *  self.media.search_radio/self.media.play_radio 觸發嘅搜尋結果) 度做精確/
     *  substring 比對, 搵唔到先當呢個 query 本身係一個新嘅搜尋詞、再打一次
     *  Radio Browser API。噉樣設計嘅原因: (1) LLM 好多時會先 search_radio 攞
     *  幾個候選再由用戶或者自己揀一個名, 呢種情況應該喺已經有嘅結果度揀,
     *  唔應該重新打 API (慢、亦都可能因為 order=votes 隨機性揀到第啲台); (2) 如果
     *  LLM 或者用戶直接淨係話一個電台名 (例如 "播BBC")、之前又未搜過, 呢個
     *  method 都應該自己搞掂, 唔使逼 LLM 一定要分兩步做。搵唔到就回傳 null -
     *  同 resolveActionId()/resolveLocalMusicFile() 一致嘅「唔夠信心就話搵唔到,
     *  唔亂估」原則。 */
    private org.json.JSONObject resolveRadioStation(String query) throws java.io.IOException,
            org.json.JSONException {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return null;

        java.util.List<org.json.JSONObject> cached = lastRadioSearchResults;
        if (cached != null) {
            for (org.json.JSONObject s : cached) {
                if (q.equals(s.optString("stationuuid"))) return s;
            }
            for (org.json.JSONObject s : cached) {
                if (q.equalsIgnoreCase(s.optString("name"))) return s;
            }
            String qLower = q.toLowerCase(java.util.Locale.US);
            for (org.json.JSONObject s : cached) {
                String nameLower = s.optString("name").toLowerCase(java.util.Locale.US);
                if (!nameLower.isEmpty()
                        && (qLower.contains(nameLower) || nameLower.contains(qLower))) {
                    return s;
                }
            }
        }

        // Cache 度搵唔到 (或者根本未搜過) - 當呢個 query 係新搜尋詞, 打一次
        // Radio Browser, 揀返第一個唔係 HLS 嘅結果 (見 searchRadioStations()
        // javadoc: HLS 喺舊版 MediaPlayer 支援唔穩定, 直接跳過, 唔盲目揀
        // fresh.get(0) - 如果個 list 入面淨係得 HLS 台, 就寧願全部揀晒都揀
        // 唔到、退而求其次揀返 fresh.get(0), 好過乜都播唔到)。
        java.util.List<org.json.JSONObject> fresh = searchRadioStations(q, 10);
        lastRadioSearchResults = fresh;
        for (org.json.JSONObject s : fresh) {
            if (s.optInt("hls", 0) == 0) {
                return s;
            }
        }
        return fresh.isEmpty() ? null : fresh.get(0);
    }

    /** Picks a random id from the "隨機短/隨機長" action group in
     *  xiaozhi_actions.json - these are the robot's own pre-recorded filler-movement
     *  actions (20 of them: 隨機短1-10、隨機長1-10, with a couple of duplicate ids for
     *  the same name e.g. two "隨機短2" entries - both are valid, harmless to include
     *  twice in the pool), meant for exactly this "play something to look alive"
     *  use case rather than reacting to any specific emotion/content. Matched by
     *  nameCn prefix rather than a hardcoded id list so this keeps working if
     *  xiaozhi_actions.json is regenerated from a different 202_actions_classified.txt
     *  with different ids. Returns null (never throws) if the group is empty for any
     *  reason - caller must handle that as a normal "nothing to play" case, not a bug. */
    private String resolveRandomActionId() {
        java.util.List<org.json.JSONObject> actions = loadXiaozhiActions();
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (org.json.JSONObject a : actions) {
            String cn = a.optString("nameCn");
            if (cn.startsWith("隨機短") || cn.startsWith("隨機長")) {
                pool.add(a.optString("id"));
            }
        }
        if (pool.isEmpty()) return null;
        return pool.get(new java.util.Random().nextInt(pool.size()));
    }

    /** 喺獨立 thread 度揀一個隨機動作 (resolveRandomActionId()) 並播放, fire-and
     *  -forget、唔理成功失敗、唔 block caller - 抽出嚟做共用 helper, 俾 TTS
     *  "start" event (setTtsStateListener() 嗰段) 同 self.media.play_music 一齊用,
     *  兩者想要嘅係完全同一種「郁下等機械人睇落生動啲」效果, 冇必要各自開一份
     *  幾乎一樣嘅 new Thread(...) { ... }.start()。唔喺 WebSocket read loop
     *  thread/HTTP worker thread 度直接 call AIDL blocking call, 同
     *  reassertHeadEyeLed() 一致嘅安全做法。 */
    private void triggerRandomFillerAction() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String randomId = resolveRandomActionId();
                if (randomId != null) {
                    robot.action_PlayActionName(randomId);
                }
            }
        }, "XiaozhiAutoRandomAction").start();
    }

    /** MCP tool enable/disable 設定嘅讀寫 helper - 逗號分隔嘅 disabled tool name
     *  清單, 存喺 PREFS_NAME 呢個共用 SharedPreferences (同 OTA custom 設定用返
     *  同一個, 唔另開一個 file)。isMcpToolEnabled() 俾 listTools()/callTool()
     *  共用: listTools() 用嚟過濾邊啲 tool 出現喺回應, callTool() 用嚟擋一個
     *  已經 disabled 但 LLM 手上仲持有緊舊 tool 清單、嘗試照樣 call 嘅情況
     *  (單靠 listTools() 側過濾唔夠, LLM cache 咗上一次嘅清單就繞得過)。
     *  2026-08 更新: UI 側拎走咗「開放 MCP 工具俾小智使用」總開關 - 呢部機依家
     *  永遠對外暴露 MCP 工具 (逐項 enable/disable 唔變), isMcpEnabled() 恆常
     *  回傳 true。PREF_XIAOZHI_MCP_ENABLED 呢個 pref key 保留喺常數同
     *  mcp_config/set 嘅寫入路徑度冇拆走, 純粹係為咗兼容舊有經 query string
     *  直接打 API 嘅呼叫方式, 但唔會再影響實際行為。 */
    private boolean isMcpEnabled() {
        return true;
    }

    private java.util.Set<String> getMcpDisabledToolNames() {
        String csv = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_XIAOZHI_MCP_DISABLED_TOOLS, "");
        java.util.Set<String> disabled = new java.util.HashSet<>();
        if (!csv.isEmpty()) {
            for (String name : csv.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) disabled.add(trimmed);
            }
        }
        return disabled;
    }

    private boolean isMcpToolEnabled(String toolName, java.util.Set<String> disabledNames) {
        return isMcpEnabled() && !disabledNames.contains(toolName);
    }

    /** Reads an InputStream fully into a UTF-8 string - mirrors XiaozhiOtaClient's own
     *  readFully() (same need, this class just doesn't share that one since it's
     *  private there). Used by xiaozhiVisionExplainRequest()'s response handling. */
    private static String readFully(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Result of xiaozhiTakePhotoAndExplain() - exactly one of text/error is set. */
    private static final class XiaozhiVisionResult {
        final String text;
        final String error;
        private XiaozhiVisionResult(String text, String error) {
            this.text = text;
            this.error = error;
        }
        static XiaozhiVisionResult ok(String text) { return new XiaozhiVisionResult(text, null); }
        static XiaozhiVisionResult fail(String error) { return new XiaozhiVisionResult(null, error); }
    }

    /** Backs the self.camera.take_photo MCP tool: captures one frame from the robot's
     *  camera at XIAOZHI_PHOTO_WIDTH x XIAOZHI_PHOTO_HEIGHT, then POSTs it (multipart,
     *  matching the official xiaozhi-esp32 firmware's Explain() request shape) to the
     *  vision/explain endpoint, returning the description text the server sends back.
     *  Runs synchronously on the MCP tool-call thread (already off the WebSocket
     *  read-loop thread per callTool()'s own threading, matching how other blocking
     *  robot actions in this switch behave) - camera capture + HTTP round-trip can take
     *  a few seconds, which is acceptable for a tool call the LLM is explicitly waiting
     *  on. */
    private XiaozhiVisionResult xiaozhiTakePhotoAndExplain(String question) {
        // 用返 XiaoZhi 語音對話同一個 cameraController 實例 (成個 app 淨係一個相機
        // 硬件, camera/snapshot 呢類其他功能都共用緊佢) - setRequestedResolution()
        // 淨係影響下一次 start(), 唔會影響緊喺度用緊嘅其他 session (見
        // CameraController 嘅 requestedWidth/Height javadoc)。
        cameraController.setRequestedResolution(XIAOZHI_PHOTO_WIDTH, XIAOZHI_PHOTO_HEIGHT);
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return XiaozhiVisionResult.fail("camera start failed: " + started.error);
        }
        byte[] jpeg;
        try {
            // 2026-08 修正 (真正根源): 之前呢度用 waitForStableFrame() 攞 preview
            // stream 嘅 frame (見 CameraController 頭段 comment - 呢個 class 本身係
            // "continuous webcam-style streaming, NOT single-shot photos" 設計)。反編譯
            // 一個用戶提供、實測上傳成功嘅第三方 apk 之後發現: 佢送去 server 嘅係用真正
            // 單張拍攝 (CameraX ImageCapture, busy-wait 住完成 callback), 唔係 preview
            // frame - preview frame 冇經過相機 HAL 完整嘅單張 AE/AF/降噪 pipeline。
            // 用戶已核實過 server 端存低嘅相解像度都啱 (480x360), 所以差異在於 capture
            // 方式本身, 唔係 output size, 改用 CameraController.takePhoto() (Camera1
            // legacy API 嘅 camera.takePicture(), 見該 method javadoc) 嚟做真正嘅單張
            // 拍攝, 取代返 waitForStableFrame() 呢個「等夠幀數迴避過渡期」嘅
            // workaround - takePicture() 本身已經係硬件執行緊嘅單張拍攝流程。
            CameraController.PhotoResult photoResult =
                    cameraController.takePhoto(XIAOZHI_PHOTO_WIDTH, XIAOZHI_PHOTO_HEIGHT, 8000);
            if (photoResult.error != null) {
                return XiaozhiVisionResult.fail("camera takePicture failed: " + photoResult.error);
            }
            jpeg = photoResult.jpeg;
        } finally {
            cameraController.stopIfIdle();
        }
        if (jpeg == null) {
            return XiaozhiVisionResult.fail("takePicture() returned no photo data");
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 2026-08 修正 (真正根源): 之前呢度嘅優先序係「自訂設定 -> 寫死常數」,
        // 完全冇考慮 server 喺 "initialize" MCP request 度會夾住真正嘅 vision
        // url/token (見 XiaozhiClient.getVisionUrl() 嘅 comment, 同官方
        // mcp-protocol.md 原文 "initialize" 章節) - 呢個先係 404 嘅真正根源, 之前
        // 幾輪改嘅 scheme/domain 都係捕風捉影。而家優先序改為: server 喺
        // initialize 度話俾我哋知嘅 (最新鮮、最權威) -> 用戶手動填嘅自訂設定
        // (如果啟用咗自訂 server 又冇收到 server 提供嘅 url) -> 寫死嘅
        // DEFAULT_VISION_URL (最後保險, 例如連都未連過就試 take_photo)。
        String serverProvidedUrl = xiaozhiClient.getVisionUrl();
        String visionUrl;
        String token;
        if (serverProvidedUrl != null && !serverProvidedUrl.isEmpty()) {
            visionUrl = serverProvidedUrl;
            token = xiaozhiClient.getVisionToken();
        } else {
            visionUrl = prefs.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false)
                    ? prefs.getString(PREF_XIAOZHI_VISION_URL, DEFAULT_VISION_URL)
                    : DEFAULT_VISION_URL;
            if (visionUrl == null || visionUrl.trim().isEmpty()) {
                visionUrl = DEFAULT_VISION_URL;
            }
            token = xiaozhiAccessToken;
        }

        String deviceId = getXiaozhiDeviceId();
        try {
            return xiaozhiVisionExplainRequest(visionUrl, deviceId, xiaozhiClientId, token, jpeg, question);
        } catch (java.io.IOException e) {
            return XiaozhiVisionResult.fail("vision/explain request failed: " + e.getMessage());
        }
    }

    /** Backs the self.camera.image_to_text MCP tool - see that tool's definition in
     *  buildMcpToolsList() and the "vision/explain is async" comment in
     *  xiaozhiVisionExplainRequest() for the full story. Re-POSTs to the same
     *  vision/explain endpoint (same headers/auth as the original photo upload) but with
     *  a small JSON body carrying just the uuid instead of a fresh multipart JPEG upload,
     *  on the theory that the uuid is how the server matches this follow-up call back to
     *  the photo it already has stored. This exact request shape is NOT documented
     *  anywhere (see the async comment) - it's this codebase's best guess given the
     *  server's own wording ("call the tool `image_to_text`... using the uuid"), so the
     *  raw response is logged in full for correcting the shape if this guess is wrong. */
    // 2026-08 新增: 判斷一個字串「睇落似唔似」真正嘅 UUID (標準格式:
    // 8-4-4-4-12 個 hex 字符, 用 "-" 分隔, 例如 vision/explain response 個
    // "776e1db5-092a-4045-9334-17ca15cfc781") - 用喺 self.camera.image_to_text
    // 個 case, 篩走 LLM 冇讀返真 uuid、自己填咗個佔位符字面值 (實測見過
    // "placeholder") 嘅情況, 見該 case 嘅 comment。刻意用寬鬆嘅 regex match
    // (唔淨係死 check 等於 "placeholder"), 因為 LLM 用邊個字眼做佔位符本身
    // 唔受控, 「格式啱先信」好過「同已知字面值逐個比對」。
    private static final java.util.regex.Pattern UUID_LIKE_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static boolean isLikelyUuid(String s) {
        return s != null && UUID_LIKE_PATTERN.matcher(s).matches();
    }

    private XiaozhiVisionResult xiaozhiFetchImageToText(String uuid) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverProvidedUrl = xiaozhiClient.getVisionUrl();
        String visionUrl;
        String token;
        if (serverProvidedUrl != null && !serverProvidedUrl.isEmpty()) {
            visionUrl = serverProvidedUrl;
            token = xiaozhiClient.getVisionToken();
        } else {
            visionUrl = prefs.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false)
                    ? prefs.getString(PREF_XIAOZHI_VISION_URL, DEFAULT_VISION_URL)
                    : DEFAULT_VISION_URL;
            if (visionUrl == null || visionUrl.trim().isEmpty()) {
                visionUrl = DEFAULT_VISION_URL;
            }
            token = xiaozhiAccessToken;
        }
        String deviceId = getXiaozhiDeviceId();

        java.net.HttpURLConnection conn = null;
        try {
            org.json.JSONObject payloadJson = new org.json.JSONObject();
            payloadJson.put("type", "image_to_text");
            payloadJson.put("uuid", uuid);
            byte[] payload = payloadJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            java.net.URL url = new java.net.URL(visionUrl);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Device-Id", deviceId);
            if (xiaozhiClientId != null && !xiaozhiClientId.isEmpty()) {
                conn.setRequestProperty("Client-Id", xiaozhiClientId);
            }
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            java.io.InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? readFully(is) : "";
            Log.i("XiaozhiVision", "image_to_text raw response (uuid=" + uuid + ", status="
                    + status + "): " + responseText);

            if (status < 200 || status >= 300) {
                return XiaozhiVisionResult.fail("image_to_text returned HTTP " + status + ": "
                        + responseText.substring(0, Math.min(200, responseText.length())));
            }
            try {
                org.json.JSONObject json = new org.json.JSONObject(responseText);
                if (json.optBoolean("success", false)) {
                    String text = json.optString("text", "");
                    if (text.isEmpty()) {
                        org.json.JSONObject nestedResult = json.optJSONObject("result");
                        if (nestedResult != null) {
                            text = nestedResult.optString("text", "");
                        }
                        if (text.isEmpty()) {
                            org.json.JSONObject nestedData = json.optJSONObject("data");
                            if (nestedData != null) {
                                text = nestedData.optString("text", "");
                            }
                        }
                    }
                    if (text.isEmpty()) {
                        // 又係空 - 呢次冇 message 可以再 relay 落去 (冇再下一層
                        // tool 可以 call 落去), 直接將完整 raw response 當成
                        // error 帶返俾 LLM/開發者, 等睇 logcat 個 "image_to_text
                        // raw response" 個 log 可以直接對照真正欄位。
                        return XiaozhiVisionResult.fail(
                                "image_to_text succeeded but returned no text; raw: " + responseText);
                    }
                    return XiaozhiVisionResult.ok(text);
                }
                return XiaozhiVisionResult.fail(json.optString("message",
                        "image_to_text reported failure with no message"));
            } catch (org.json.JSONException e) {
                return XiaozhiVisionResult.fail("image_to_text returned non-JSON response: "
                        + responseText.substring(0, Math.min(200, responseText.length())));
            }
        } catch (java.io.IOException e) {
            return XiaozhiVisionResult.fail("image_to_text request failed: " + e.getMessage());
        } catch (org.json.JSONException e) {
            // 理論上 payloadJson.put("type",...)/put("uuid",...) 呢兩個 put(String,
            // Object) overload 唔會真係 throw (value 本身冇問題), 但佢哋簽名有
            // 宣告 throws JSONException, 純粹補返個 catch 過 javac 嘅 checked
            // exception 檢查, 唔係話呢度預期會撞到。
            return XiaozhiVisionResult.fail("image_to_text failed building request JSON: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Multipart POST to the vision/explain endpoint - mirrors XiaozhiOtaClient's
     *  postJsonWithStatus() (same Device-Id/Client-Id header convention, same
     *  zero-third-party HttpURLConnection style, see that method's comment for why
     *  Device-Id is this robot's persisted UUID rather than a real WiFi MAC), but a
     *  multipart body instead of JSON since this carries binary JPEG data - see
     *  esp32_camera.cc's Explain() for the request shape being matched: a "question"
     *  text field alongside a "file" field holding the JPEG.
     *
     *  2026-08 修正: 反編譯一個用戶提供、實測影相成功嘅第三方 apk (package
     *  com.huihongcloud.xiaozhi) 嘅實際 multipart 組裝邏輯 (Lcom/huihongcloud/
     *  xiaozhi/D;->a bytecode), 發現兩個之前呢度冇跟嘅細節:
     *  (1) 佢送嘅 Client-Id header 之前完全冇加 (呢度之前個 comment 早就講咗
     *      「同 WebSocket 一樣嘅 Device-Id/Client-Id/Authorization」但實際冇做);
     *  (2) 佢個 multipart body 開頭多咗一個 "type" part, 值係 "multipart" (喺
     *      "question" part 之前) - 呢個唔喺官方 esp32_camera.cc 文檔化嘅欄位入面
     *      提到, 但實測嘅 apk 確實有加, 保守起見跟返, 避免依家依賴緊嘅 server
     *      side 有隱藏檢查依賴呢個欄位。 */
    private XiaozhiVisionResult xiaozhiVisionExplainRequest(String urlStr, String deviceId,
            String clientId,
            String accessToken, byte[] jpeg, String question) throws java.io.IOException {
        // 2026-08 修正: 之前用動態 "----OpenAlpha2Boundary<timestamp>" boundary -
        // 反編譯用戶提供、實測上傳成功嘅第三方 apk (package com.huihongcloud.xiaozhi)
        // 之後發現, 佢個 multipart body 結構 (type/question/file 三個 part, field
        // name、"camera.jpg" filename) 同呢度已經一致, 但佢用嘅係一個固定字串
        // boundary "----ESP32_CAMERA_BOUNDARY" - 呢個正正係官方 esp32-camera.cc
        // firmware 用嘅 boundary, 呢個第三方 apk 特登跟足官方寫死呢個字串, 唔係隨機
        // 生成。用戶已核實同一個帳戶/官方 server 用第三方 apk 一路成功, 我哋一路撞到
        // server 話「請 call image_to_text」呢個 fallback - 兩者 request body 結構
        // 一致之下, 呢個 boundary 係暫時搵到嘅唯一實質差異, 懷疑 server 側嘅
        // multipart parser 或者前置關卡對呢個固定字串有特殊 / 白名單處理, 用嚟識別
        // 「呢個係合法嘅相機上傳」, 動態 boundary 反而被判去咗一條 fallback 路徑。
        // 跟返呢個固定字串, 唔再自己動態生成。
        String boundary = "----ESP32_CAMERA_BOUNDARY";
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.io.Writer w = new java.io.OutputStreamWriter(body, java.nio.charset.StandardCharsets.UTF_8);

        w.write("--" + boundary + "\r\n");
        w.write("Content-Disposition: form-data; name=\"type\"\r\n\r\n");
        w.write("multipart");
        w.write("\r\n");
        w.write("--" + boundary + "\r\n");
        w.write("Content-Disposition: form-data; name=\"question\"\r\n\r\n");
        w.write(question == null ? "" : question);
        w.write("\r\n");
        w.flush();

        body.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write("Content-Type: image/jpeg\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(jpeg);
        body.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] payload = body.toByteArray();

        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Device-Id", deviceId);
            if (clientId != null && !clientId.isEmpty()) {
                conn.setRequestProperty("Client-Id", clientId);
            }
            if (accessToken != null && !accessToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            conn.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? readFully(is) : "";
            if (status == 404) {
                // 2026-08 新增: 實測用官方 xiaozhi.me 撞過呢個情況 - 官方 esp32
                // firmware 本身打緊同一條 URL 係得嘅 (見 GitHub issue #708 嘅實測
                // log), 所以 404 唔係 URL 打錯, 而係呢個帳戶/agent 喺 xiaozhi.me
                // console 度未開通 vision/camera 呢個 MCP 服務 - 冇開通嘅帳戶,
                // api.xiaozhi.me 呢邊嘅 routing 層面根本冇呢條路由, 對所有 request
                // 都係 404, 唔會有更詳細嘅「未授權」訊息。呢度將呢個已知原因直接
                // 話俾 LLM/用戶知, 唔使下次再由零開始查一次。
                return XiaozhiVisionResult.fail("vision/explain returned HTTP 404 - this usually "
                        + "means the vision/camera MCP service has not been enabled for this "
                        + "device/agent in the xiaozhi.me console (look for \"MCP 接入點\" / "
                        + "\"MCP Services\" / vision settings there), not a URL problem.");
            }
            if (status < 200 || status >= 300) {
                return XiaozhiVisionResult.fail("vision/explain returned HTTP " + status + ": "
                        + responseText.substring(0, Math.min(200, responseText.length())));
            }
            try {
                org.json.JSONObject json = new org.json.JSONObject(responseText);
                // 2026-08 新增 (診斷用): 實測 status 200 + isError:false, 但最終
                // MCP tool 回應嘅 text 一直係空字串 - 即係 json.optBoolean("success")
                // 行到 true 嗰邊, 但 json.optString("text","") 攞唔到嘢。之前一直冇
                // log 印低完整 raw response body, 淨係識講「係咪 success」, 唔知
                // server 實際仲有咩欄位。今次印低嚟, 下次一 fail/text 空就可以直接
                // 對照真正嘅 server JSON 結構嚟修, 唔使再靠估。
                android.util.Log.i("XiaozhiVision", "vision/explain raw response: " + responseText);
                if (json.optBoolean("success", false)) {
                    String text = json.optString("text", "");
                    if (text.isEmpty()) {
                        // "text" 呢層攞唔到, 試下幾種常見嘅巢狀結構 fallback -
                        // 未經證實邊個啱, 純粹碰運氣, 主要靠上面條 log 先真正確診。
                        org.json.JSONObject nestedResult = json.optJSONObject("result");
                        if (nestedResult != null) {
                            text = nestedResult.optString("text", "");
                        }
                        if (text.isEmpty()) {
                            org.json.JSONObject nestedData = json.optJSONObject("data");
                            if (nestedData != null) {
                                text = nestedData.optString("text", "");
                            }
                        }
                    }
                    if (text.isEmpty()) {
                        // 2026-08 新增 (真正根源): 實測 (4 次) 得出嘅真正 server 行為 -
                        // 帳戶用 GPT-5 做 LLM provider 時, vision/explain 唔會即刻答返
                        // description, 而係response
                        // {"success":true,"uuid":"...","message":"Please call the
                        // tool `image_to_text` to explain the image, then reply to
                        // the user"} - 即係話呢個 explain 係異步嘅, 真正描述要由 LLM
                        // agent 自己喺對話入面主動再發一次 MCP tools/call 去 call
                        // "image_to_text" 呢個 tool (未喺官方 mcp-protocol.md 記載,
                        // 屬於 xiaozhi.me console 呢個特定 agent/GPT-5 組合先有嘅行為)
                        // 先攞到。之前呢度將 text 空字串直接當成功 (見上面
                        // XiaozhiVisionResult.ok(text)), 令 LLM 收到嘅 MCP result 係
                        // 完全空白嘅 text, 完全冇提示佢要再 call 邊個 tool, 對話就此
                        // 卡死, 4 次都係呢個 pattern。修正: 呢種情況唔算失敗, 將
                        // server 個 "message" (LLM 睇得明嘅指示) 原文當成呢次
                        // self.camera.take_photo 嘅 result 文字傳返俾 LLM - 等 LLM
                        // 自己讀到呢句嘢, 主動再發 tools/call 去 call
                        // "image_to_text" (device 呢邊已加返呢個 tool 嘅
                        // 註冊/處理, 見 buildMcpToolsList() 同 callTool() 嘅
                        // "self.camera.image_to_text" case)。
                        String uuid = json.optString("uuid", null);
                        String message = json.optString("message", null);
                        if (uuid != null && !uuid.isEmpty() && message != null && !message.isEmpty()) {
                            Log.i("XiaozhiVision", "vision/explain is async (uuid=" + uuid
                                    + ") - relaying server's own instruction text to the LLM "
                                    + "instead of an empty result");
                            lastPendingPhotoUuid = uuid;
                            return XiaozhiVisionResult.ok(message);
                        }
                    }
                    return XiaozhiVisionResult.ok(text);
                }
                return XiaozhiVisionResult.fail(json.optString("message",
                        "vision/explain reported failure with no message"));
            } catch (org.json.JSONException e) {
                return XiaozhiVisionResult.fail("vision/explain returned non-JSON response: "
                        + responseText.substring(0, Math.min(200, responseText.length())));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private XiaozhiClient.McpBridge xiaozhiMcpBridge() {
        return new XiaozhiClient.McpBridge() {
            @Override
            public org.json.JSONObject listTools() throws org.json.JSONException {
                org.json.JSONArray tools = new org.json.JSONArray();

                // 2026-08 修正: 之前 "name" 要求 LLM 傳返 self.robot.list_actions 嘅
                // id (一串冇語意嘅 timestamp 數字), 但實測小智完全唔跟呢個指示,
                // 純粹靠印象亂噏一個 id (見落面 play_action tool description 嘅
                // 詳細 comment)。現在 "name" 改為接受人類可讀嘅中文/英文動作名,
                // 由 callTool 嘅 self.robot.play_action case 做 fuzzy match 轉做真正
                // id - 呢度唔再需要將 202 個 id 塞晒落 enum。
                org.json.JSONObject listActions = new org.json.JSONObject();
                listActions.put("name", "self.robot.list_actions");
                listActions.put("description", "List all built-in robot actions with their id, "
                        + "Chinese name, and English name. Useful for browsing what actions "
                        + "exist, but self.robot.play_action can now be called directly with a "
                        + "Chinese or English action name (fuzzy-matched server-side) - you do "
                        + "not need to call this first just to play a known action.");
                org.json.JSONObject listActionsSchema = new org.json.JSONObject();
                listActionsSchema.put("type", "object");
                listActionsSchema.put("properties", new org.json.JSONObject());
                listActions.put("inputSchema", listActionsSchema);
                tools.put(listActions);

                // 2026-08 修正 (實測發現): 之前 "name" 要求 LLM 一定要傳返
                // self.robot.list_actions 嘅 id (一串冇語意嘅 timestamp 數字), 但
                // 實測小智完全唔跟呢個指示 - 佢從來冇 call 過 list_actions, 純粹靠
                // "印象" 亂噏一個 id (實測見過叫佢郁左手, 佢傳咗 "1464835936031",
                // 實際係「向後走」嗰個 id - 郁錯晒)。呢個唔係 enum 冇約束住合法值嘅
                // 問題 (enum 確保咗傳落嚟嘅一定係真實存在嘅檔案, 唔會再撞
                // "開唔到檔案" 嗰種崩潰), 而係 LLM 對住一堆完全冇語意嘅純數字 id,
                // 根本記唔住邊個 id 對應邊個動作, 就算 description 幾強調
                // "call list_actions first" 都冇用。
                //
                // 現在做法: "name" 改為接受人類可讀嘅中文或英文動作名 (例如
                // "舉左手" 或 "take left hand"), 由呢度 (callTool 嘅
                // self.robot.play_action case) 做 fuzzy match 轉做真正嘅 id 先傳落
                // action_PlayActionName() - 詳見 resolveActionId()。LLM 唔使再記
                // id, 只要講返個佢自己生成緊嘅語意名就得, 大幅減低揀錯嘅機會。
                org.json.JSONObject playAction = new org.json.JSONObject();
                playAction.put("name", "self.robot.play_action");
                playAction.put("description", "Play a named built-in robot action/animation. "
                        + "Pass the action's Chinese or English name in natural language (e.g. "
                        + "\"舉左手\" or \"take left hand\", \"跳舞\" or \"dance\") - it will be "
                        + "matched against the robot's actual action list automatically. Only "
                        + "actions that exist in the robot's list can actually play, so if in "
                        + "doubt call self.robot.list_actions to see exact names first.");
                org.json.JSONObject playActionSchema = new org.json.JSONObject();
                playActionSchema.put("type", "object");
                org.json.JSONObject playActionProps = new org.json.JSONObject();
                org.json.JSONObject nameProp = new org.json.JSONObject();
                nameProp.put("type", "string");
                nameProp.put("description", "Chinese or English action name, e.g. \"舉左手\" or \"take left hand\".");
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

                // 2026-08 新增: 骨架先行, 揀邊個動作淨係靠隨機 (見
                // resolveRandomActionId()), 未做任何 emotion-to-action 對應 - 嗰部分
                // 遲啲先做。呢個 tool 存在嘅意義係俾 LLM 自己判斷「呢一刻適唔適合
                // 加個動作睇落生動啲」, 唔係跟住 emotion 字段機械式觸發 (每句對話
                // 都夾 emotion 字段, 如果 client 側見到就自動播, 會太密太吵) - 主導
                // 權留喺 LLM 側, 由佢自己決定幾時 call。
                org.json.JSONObject playRandomAction = new org.json.JSONObject();
                playRandomAction.put("name", "self.robot.play_random_action");
                playRandomAction.put("description", "Play a random filler movement to make the "
                        + "robot look more alive/expressive - use this when it feels natural to "
                        + "add a bit of physical animation, not necessarily tied to any specific "
                        + "emotion or reply content. Takes no arguments. IMPORTANT ordering rule: "
                        + "if the user's request also calls for a specific action via "
                        + "self.robot.play_action (e.g. they asked you to wave, dance, nod, etc.), "
                        + "call that specific action instead of (not in addition to) this random "
                        + "one for this turn - only reach for play_random_action when there is no "
                        + "other action already planned for this reply.");
                org.json.JSONObject playRandomActionSchema = new org.json.JSONObject();
                playRandomActionSchema.put("type", "object");
                playRandomActionSchema.put("properties", new org.json.JSONObject());
                playRandomAction.put("inputSchema", playRandomActionSchema);
                tools.put(playRandomAction);

                // 2026-08 新增: 全套硬件控制 MCP tools (servo/LED/PIR/sonar), 跟返
                // 呢個 bridge 已有嘅 pattern (schema 用 org.json 砌, 執行時直接 call
                // robot.xxx() 個 AIDL wrapper, 有 waitXxxReady() 就跟現有 HTTP API
                // case 一樣加埋) - 詳細參數含義/已驗證行為見 AIDL_REFERENCE.md 同
                // handleApi() 入面對應嘅 "servo/*"、"led/*"、"pir/*" case (呢啲 MCP
                // tool 純粹係嗰啲 case 嘅薄包裝, 冇重複定義邏輯)。

                org.json.JSONObject servoOne = new org.json.JSONObject();
                servoOne.put("name", "self.robot.servo_set_one");
                servoOne.put("description", "Move a single servo to an angle. Servo ids and their "
                        + "valid angle ranges are specific to this robot's build - if unsure, use "
                        + "small movements first.");
                org.json.JSONObject servoOneSchema = new org.json.JSONObject();
                servoOneSchema.put("type", "object");
                org.json.JSONObject servoOneProps = new org.json.JSONObject();
                servoOneProps.put("id", new org.json.JSONObject().put("type", "integer")
                        .put("description", "Servo id (1-20)."));
                servoOneProps.put("angle", new org.json.JSONObject().put("type", "integer")
                        .put("description", "Target angle in degrees."));
                servoOneProps.put("time_ms", new org.json.JSONObject().put("type", "integer")
                        .put("description", "Movement duration in milliseconds. Default 1000."));
                servoOneSchema.put("properties", servoOneProps);
                servoOneSchema.put("required", new org.json.JSONArray().put("id").put("angle"));
                servoOne.put("inputSchema", servoOneSchema);
                tools.put(servoOne);

                org.json.JSONObject servoAll = new org.json.JSONObject();
                servoAll.put("name", "self.robot.servo_set_all");
                servoAll.put("description", "Move all 20 servos at once to a full-body pose. "
                        + "angles must have exactly 20 comma-separated integers, one per servo id "
                        + "in order.");
                org.json.JSONObject servoAllSchema = new org.json.JSONObject();
                servoAllSchema.put("type", "object");
                org.json.JSONObject servoAllProps = new org.json.JSONObject();
                servoAllProps.put("angles", new org.json.JSONObject().put("type", "string")
                        .put("description", "20 comma-separated angle values, e.g. \"0,0,0,...\"."));
                servoAllProps.put("time_ms", new org.json.JSONObject().put("type", "integer")
                        .put("description", "Movement duration in milliseconds. Default 1000."));
                servoAllSchema.put("properties", servoAllProps);
                servoAllSchema.put("required", new org.json.JSONArray().put("angles"));
                servoAll.put("inputSchema", servoAllSchema);
                tools.put(servoAll);

                org.json.JSONObject ledHead = new org.json.JSONObject();
                ledHead.put("name", "self.robot.led_set_head");
                ledHead.put("description", "Set the head 5-mic LED ring. color: 1=red 2=green "
                        + "3=blue 4=yellow 5=purple 6=cyan 7=white. brightness: 1 (dimmest) to 9 "
                        + "(brightest). preset: \"long\" (solid), \"flash\", \"breathe\", \"chase\", "
                        + "\"dual\", or \"stop\" (turns the ring off - color/brightness ignored).");
                org.json.JSONObject ledHeadSchema = new org.json.JSONObject();
                ledHeadSchema.put("type", "object");
                org.json.JSONObject ledHeadProps = new org.json.JSONObject();
                ledHeadProps.put("preset", new org.json.JSONObject().put("type", "string")
                        .put("enum", new org.json.JSONArray()
                                .put("long").put("flash").put("breathe").put("chase").put("dual").put("stop"))
                        .put("description", "Effect preset. Default \"long\"."));
                ledHeadProps.put("color", new org.json.JSONObject().put("type", "integer")
                        .put("description", "1-7, required unless preset=stop."));
                ledHeadProps.put("brightness", new org.json.JSONObject().put("type", "integer")
                        .put("description", "1-9, required unless preset=stop."));
                ledHeadSchema.put("properties", ledHeadProps);
                ledHead.put("inputSchema", ledHeadSchema);
                tools.put(ledHead);

                org.json.JSONObject ledEye = new org.json.JSONObject();
                ledEye.put("name", "self.robot.led_set_eye");
                ledEye.put("description", "Set the eye 5-mic LED ring. Same color/brightness/preset "
                        + "semantics as self.robot.led_set_head (preset \"breathe\" is not available "
                        + "for the eye ring - only \"long\", \"flash\", \"chase\", \"dual\", \"stop\").");
                org.json.JSONObject ledEyeSchema = new org.json.JSONObject();
                ledEyeSchema.put("type", "object");
                org.json.JSONObject ledEyeProps = new org.json.JSONObject();
                ledEyeProps.put("preset", new org.json.JSONObject().put("type", "string")
                        .put("enum", new org.json.JSONArray()
                                .put("long").put("flash").put("chase").put("dual").put("stop"))
                        .put("description", "Effect preset. Default \"long\"."));
                ledEyeProps.put("color", new org.json.JSONObject().put("type", "integer")
                        .put("description", "1-7, required unless preset=stop."));
                ledEyeProps.put("brightness", new org.json.JSONObject().put("type", "integer")
                        .put("description", "1-9, required unless preset=stop."));
                ledEyeSchema.put("properties", ledEyeProps);
                ledEye.put("inputSchema", ledEyeSchema);
                tools.put(ledEye);

                org.json.JSONObject ledMouth = new org.json.JSONObject();
                ledMouth.put("name", "self.robot.led_set_mouth");
                ledMouth.put("description", "Set the mouth LED. preset \"breathing\" pulses at the "
                        + "given speed (0-5000ms, 0=fastest); preset \"off\" turns it off. Note: "
                        + "this is driven automatically during XiaoZhi TTS playback, so calling it "
                        + "manually mid-conversation may fight with that.");
                org.json.JSONObject ledMouthSchema = new org.json.JSONObject();
                ledMouthSchema.put("type", "object");
                org.json.JSONObject ledMouthProps = new org.json.JSONObject();
                ledMouthProps.put("preset", new org.json.JSONObject().put("type", "string")
                        .put("enum", new org.json.JSONArray().put("breathing").put("off"))
                        .put("description", "Default \"breathing\"."));
                ledMouthProps.put("speed_ms", new org.json.JSONObject().put("type", "integer")
                        .put("description", "Breathing speed 0-5000ms, only used when preset=breathing. Default 0."));
                ledMouthSchema.put("properties", ledMouthProps);
                ledMouth.put("inputSchema", ledMouthSchema);
                tools.put(ledMouth);

                org.json.JSONObject pirGet = new org.json.JSONObject();
                pirGet.put("name", "self.sensors.get_pir");
                pirGet.put("description", "Read the last known PIR motion-sensor state (whether "
                        + "someone was last detected entering/present nearby). This is the most "
                        + "recently received event, not a live poll - if the sensor is disabled or "
                        + "no event has arrived yet, state will be \"unknown\".");
                org.json.JSONObject pirGetSchema = new org.json.JSONObject();
                pirGetSchema.put("type", "object");
                pirGetSchema.put("properties", new org.json.JSONObject());
                pirGet.put("inputSchema", pirGetSchema);
                tools.put(pirGet);

                org.json.JSONObject pirSet = new org.json.JSONObject();
                pirSet.put("name", "self.sensors.set_pir_enabled");
                pirSet.put("description", "Turn the PIR motion sensor hardware on or off.");
                org.json.JSONObject pirSetSchema = new org.json.JSONObject();
                pirSetSchema.put("type", "object");
                org.json.JSONObject pirSetProps = new org.json.JSONObject();
                pirSetProps.put("enabled", new org.json.JSONObject().put("type", "boolean"));
                pirSetSchema.put("properties", pirSetProps);
                pirSetSchema.put("required", new org.json.JSONArray().put("enabled"));
                pirSet.put("inputSchema", pirSetSchema);
                tools.put(pirSet);

                org.json.JSONObject sonarGet = new org.json.JSONObject();
                sonarGet.put("name", "self.sensors.get_sonar");
                sonarGet.put("description", "Read the last known ultrasonic sonar distance reading "
                        + "(centimeters) and the currently configured trigger threshold. This is the "
                        + "most recently received reading, not a live poll - if no reading has "
                        + "arrived yet, distance_cm will be -1.");
                org.json.JSONObject sonarGetSchema = new org.json.JSONObject();
                sonarGetSchema.put("type", "object");
                sonarGetSchema.put("properties", new org.json.JSONObject());
                sonarGet.put("inputSchema", sonarGetSchema);
                tools.put(sonarGet);

                org.json.JSONObject sonarSet = new org.json.JSONObject();
                sonarSet.put("name", "self.sensors.set_sonar_threshold");
                sonarSet.put("description", "Configure the sonar obstacle-trigger distance "
                        + "threshold in centimeters.");
                org.json.JSONObject sonarSetSchema = new org.json.JSONObject();
                sonarSetSchema.put("type", "object");
                org.json.JSONObject sonarSetProps = new org.json.JSONObject();
                sonarSetProps.put("distance_cm", new org.json.JSONObject().put("type", "integer"));
                sonarSetSchema.put("properties", sonarSetProps);
                sonarSetSchema.put("required", new org.json.JSONArray().put("distance_cm"));
                sonarSet.put("inputSchema", sonarSetSchema);
                tools.put(sonarSet);

                // 跟返官方 xiaozhi-esp32 firmware 嘅 self.camera.take_photo 命名/協議
                // 形狀 (見 esp32_camera.cc 嘅 Explain() 實作): 影一張相, 用 multipart
                // HTTP POST 去 vision/explain endpoint (JPEG + question), server 回
                // {"success":true,"text":"..."} 嘅圖片描述文字, 由 LLM 讀出嚟。相片
                // 唔會經 MCP JSONRPC result 直接塞 image content (呢個 xiaozhi 協議
                // 冇支援) - explain 完全喺 device <-> vision endpoint 之間做, MCP tool
                // 淨係拎返段描述文字。解像度固定 480x360 (用戶指定, 細過官方範例嘅
                // 640x480, 換取更快上傳/處理), 見 CAMERA_PHOTO_WIDTH/HEIGHT 同
                // xiaozhiVisionExplain()。
                org.json.JSONObject takePhoto = new org.json.JSONObject();
                takePhoto.put("name", "self.camera.take_photo");
                takePhoto.put("description", "Take a photo with the robot's camera and get a "
                        + "description of what it sees. Optionally pass a specific question to "
                        + "focus the description on (e.g. \"how many people are there\"), "
                        + "otherwise a general description is returned.");
                org.json.JSONObject takePhotoSchema = new org.json.JSONObject();
                takePhotoSchema.put("type", "object");
                org.json.JSONObject takePhotoProps = new org.json.JSONObject();
                org.json.JSONObject questionProp = new org.json.JSONObject();
                questionProp.put("type", "string");
                questionProp.put("description", "Optional question to focus the photo description on.");
                takePhotoProps.put("question", questionProp);
                takePhotoSchema.put("properties", takePhotoProps);
                takePhoto.put("inputSchema", takePhotoSchema);
                tools.put(takePhoto);

                // 2026-08 新增: 帳戶用 GPT-5 做 LLM provider 時, vision/explain
                // 實測 (見 xiaozhiVisionExplainRequest() 嘅詳細 comment) 唔會即刻
                // 答返相片描述, 而係先回一個 {"success":true,"uuid":"...",
                // "message":"Please call the tool `image_to_text` ..."} - 即係
                // server 期望 LLM 自己識再發一次 MCP tools/call 去 call 呢個
                // "image_to_text" tool 先攞到真正描述。之前 device 呢邊冇註冊過
                // 呢個 tool, 令 GPT-5 就算跟指示想 call 都冇呢個 tool 可以 call,
                // 對話卡死, 4 次都係呢個情況。呢個 tool 名/形狀屬於 xiaozhi.me
                // console 呢個特定 agent/GPT-5 組合先有嘅非官方行為 (官方
                // mcp-protocol.md 完全冇記載), 跟返 server 訊息原文用嘅名
                // "image_to_text", 掛喺 self.camera 底下同 take_photo 同一
                // namespace。inputSchema 冇強制要求 uuid (LLM 可能會/唔會帶),
                // device 呢邊會用返 lastPendingPhotoUuid 做 fallback 核對, 見
                // callTool() 嘅 "self.camera.image_to_text" case。
                org.json.JSONObject imageToText = new org.json.JSONObject();
                imageToText.put("name", "self.camera.image_to_text");
                imageToText.put("description", "Get the text description for a photo previously "
                        + "captured via self.camera.take_photo. Call this after take_photo tells "
                        + "you to, using the uuid it gave you.");
                org.json.JSONObject imageToTextSchema = new org.json.JSONObject();
                imageToTextSchema.put("type", "object");
                org.json.JSONObject imageToTextProps = new org.json.JSONObject();
                org.json.JSONObject uuidProp = new org.json.JSONObject();
                uuidProp.put("type", "string");
                uuidProp.put("description", "The uuid returned by self.camera.take_photo.");
                imageToTextProps.put("uuid", uuidProp);
                imageToTextSchema.put("properties", imageToTextProps);
                imageToText.put("inputSchema", imageToTextSchema);
                tools.put(imageToText);

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

                // 2026-08 新增: 本地音樂播放 (/mnt/internal_sd/music/, 見
                // listLocalMusicFiles()/resolveLocalMusicFile() 嘅 javadoc) - 跟返
                // self.robot.play_action 嗰套「人類語言名 + fuzzy match」做法, 唔使
                // LLM 記實際檔名/副檔名。
                org.json.JSONObject listMusic = new org.json.JSONObject();
                listMusic.put("name", "self.media.list_music");
                listMusic.put("description", "List all local music files available to play "
                        + "on the robot.");
                org.json.JSONObject listMusicSchema = new org.json.JSONObject();
                listMusicSchema.put("type", "object");
                listMusicSchema.put("properties", new org.json.JSONObject());
                listMusic.put("inputSchema", listMusicSchema);
                tools.put(listMusic);

                org.json.JSONObject playMusic = new org.json.JSONObject();
                playMusic.put("name", "self.media.play_music");
                playMusic.put("description", "Play a local music file on the robot. Pass the "
                        + "song name in natural language (it will be fuzzy-matched against the "
                        + "actual filenames) - call self.media.list_music first if unsure what "
                        + "is available.");
                org.json.JSONObject playMusicSchema = new org.json.JSONObject();
                playMusicSchema.put("type", "object");
                org.json.JSONObject playMusicProps = new org.json.JSONObject();
                org.json.JSONObject musicNameProp = new org.json.JSONObject();
                musicNameProp.put("type", "string");
                musicNameProp.put("description", "Song name or filename to play (fuzzy-matched).");
                playMusicProps.put("name", musicNameProp);
                playMusicSchema.put("properties", playMusicProps);
                playMusicSchema.put("required", new org.json.JSONArray().put("name"));
                playMusic.put("inputSchema", playMusicSchema);
                tools.put(playMusic);

                org.json.JSONObject stopMusic = new org.json.JSONObject();
                stopMusic.put("name", "self.media.stop_music");
                stopMusic.put("description", "Stop whatever local music track is currently playing.");
                org.json.JSONObject stopMusicSchema = new org.json.JSONObject();
                stopMusicSchema.put("type", "object");
                stopMusicSchema.put("properties", new org.json.JSONObject());
                stopMusic.put("inputSchema", stopMusicSchema);
                tools.put(stopMusic);

                // 2026-08 更新: FM/網絡電台 (經 Radio Browser API,
                // radio-browser.info, 動態搜全世界公開電台 - 見
                // searchRadioStations()/resolveRadioStation() 嘅 javadoc, 呢部機
                // 唔再內置任何寫死嘅電台清單) - self.media.list_radio 換咗做
                // self.media.search_radio (搜尋型 API 攞唔到「全部」電台, 淨係
                // 「search_radio先攞候選、play_radio再揀播」呢個 flow 先合理)。
                org.json.JSONObject searchRadio = new org.json.JSONObject();
                searchRadio.put("name", "self.media.search_radio");
                searchRadio.put("description", "Search for live FM/internet radio stations from "
                        + "around the world (station name, e.g. a city, country, broadcaster or "
                        + "genre). Returns a list of matching stations - call "
                        + "self.media.play_radio with one of the returned names afterwards to "
                        + "actually play it.");
                org.json.JSONObject searchRadioSchema = new org.json.JSONObject();
                searchRadioSchema.put("type", "object");
                org.json.JSONObject searchRadioProps = new org.json.JSONObject();
                org.json.JSONObject searchRadioQueryProp = new org.json.JSONObject();
                searchRadioQueryProp.put("type", "string");
                searchRadioQueryProp.put("description", "Search text, e.g. \"BBC\", \"jazz\", \"Tokyo\", \"香港電台\".");
                searchRadioProps.put("query", searchRadioQueryProp);
                searchRadioSchema.put("properties", searchRadioProps);
                searchRadioSchema.put("required", new org.json.JSONArray().put("query"));
                searchRadio.put("inputSchema", searchRadioSchema);
                tools.put(searchRadio);

                org.json.JSONObject playRadio = new org.json.JSONObject();
                playRadio.put("name", "self.media.play_radio");
                playRadio.put("description", "Play (or switch to) a live FM/internet radio "
                        + "station on the robot. Pass a station name in natural language - if it "
                        + "matches one of the stations returned by a previous "
                        + "self.media.search_radio call, that exact station is played; "
                        + "otherwise this will search for it directly. Switching straight to a "
                        + "different station is fine, no need to call self.media.stop_radio first.");
                org.json.JSONObject playRadioSchema = new org.json.JSONObject();
                playRadioSchema.put("type", "object");
                org.json.JSONObject playRadioProps = new org.json.JSONObject();
                org.json.JSONObject radioNameProp = new org.json.JSONObject();
                radioNameProp.put("type", "string");
                radioNameProp.put("description", "Station name, e.g. \"BBC World Service\" or \"香港電台第一台\".");
                playRadioProps.put("name", radioNameProp);
                playRadioSchema.put("properties", playRadioProps);
                playRadioSchema.put("required", new org.json.JSONArray().put("name"));
                playRadio.put("inputSchema", playRadioSchema);
                tools.put(playRadio);

                org.json.JSONObject stopRadio = new org.json.JSONObject();
                stopRadio.put("name", "self.media.stop_radio");
                stopRadio.put("description", "Stop whatever radio station is currently playing.");
                org.json.JSONObject stopRadioSchema = new org.json.JSONObject();
                stopRadioSchema.put("type", "object");
                stopRadioSchema.put("properties", new org.json.JSONObject());
                stopRadio.put("inputSchema", stopRadioSchema);
                tools.put(stopRadio);

                // Bug fix (2026-08): "nextCursor":"" was always present, and the
                // xiaozhi.me server treats *presence* of nextCursor as "there is a next
                // page" regardless of its value being empty - it immediately re-issues
                // tools/list with that cursor, which this bridge answered identically
                // every time -> infinite tools/list loop (observed 1300+ times per
                // session in logcat), and the session never reaches tools/call, so no
                // action ever plays. The full tool set fits in a single page, so
                // nextCursor must be omitted entirely here to signal "no more pages".
                //
                // 2026-08 新增: MCP 設定 card 嘅 enable/disable 喺呢度一次性生效 -
                // 成個 tools array 已經砌晒晒 (上面全部 tools.put(...)), 呢度過濾
                // 一次就夠, 唔使逐個 tools.put() 前面加 if, 減少改動、唔使擔心漏咗
                // 邊個。總開關閂咗就回傳完全空嘅 tools array (等如話俾 LLM 知「呢部
                // 機依家冇任何工具」); 開住就逐個攞返個別 tool 嘅 enabled 狀態
                // 過濾。見 isMcpToolEnabled()/getMcpDisabledToolNames() 嘅 comment。
                org.json.JSONArray filteredTools = new org.json.JSONArray();
                if (isMcpEnabled()) {
                    java.util.Set<String> disabledNames = getMcpDisabledToolNames();
                    for (int i = 0; i < tools.length(); i++) {
                        org.json.JSONObject tool = tools.getJSONObject(i);
                        if (!disabledNames.contains(tool.optString("name"))) {
                            filteredTools.put(tool);
                        }
                    }
                }
                // 2026-08 新增: MCP 設定 card 要顯示全部 tool (連同已經 disable 咗
                // 嘅), 等用戶可以撳返個掣 enable 返 - 但上面 filteredTools 已經係
                // 過濾完先, 傳俾 XiaoZhi server 嗰份唔會再帶住 disabled 嘅 tool。
                // 呢度將未過濾嘅完整版本 (tools, 起好晒全部 tool 嘅原始 array) 存低
                // 做 instance field, 等 "mcp_tools/list" 呢個 HTTP endpoint (純粹俾
                // 前端 card 顯示用) 可以獨立讀到完整清單, 唔使搬動/複製呢成段
                // 起 tools array 嘅邏輯。
                lastFullMcpToolList = tools;
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("tools", filteredTools);
                return result;
            }

            @Override
            public org.json.JSONObject callTool(String name, org.json.JSONObject arguments) throws org.json.JSONException {
                boolean isError = false;
                String resultText = "";
                // 2026-08 新增: 單靠 listTools() 側過濾唔夠 - LLM 可能仲拎住上一次
                // (disable 之前) 攞到嘅 tool 清單, 照樣試 call 一個而家已經 disabled
                // 嘅 tool name, 呢度做多一重擋。同 listTools() 用返同一套
                // isMcpEnabled()/getMcpDisabledToolNames() 邏輯, 保證兩邊判斷一致。
                if (!isMcpToolEnabled(name, getMcpDisabledToolNames())) {
                    org.json.JSONArray disabledContent = new org.json.JSONArray();
                    org.json.JSONObject disabledBlock = new org.json.JSONObject();
                    disabledBlock.put("type", "text");
                    disabledBlock.put("text", "tool \"" + name + "\" is currently disabled on this device");
                    disabledContent.put(disabledBlock);
                    org.json.JSONObject disabledResult = new org.json.JSONObject();
                    disabledResult.put("content", disabledContent);
                    disabledResult.put("isError", true);
                    return disabledResult;
                }
                try {
                    switch (name) {
                        case "self.robot.list_actions": {
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (org.json.JSONObject a : loadXiaozhiActions()) {
                                arr.put(a);
                            }
                            resultText = arr.toString();
                            break;
                        }
                        case "self.robot.play_action": {
                            String actionName = arguments.optString("name", "");
                            if (actionName.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: name";
                                break;
                            }
                            // 2026-08 修正: 小智傳落嚟嘅係人類語言嘅動作名 (中文/英文,
                            // 唔再係要佢自己記住嘅 id, 見 listTools() 嘅
                            // self.robot.play_action description comment) - 呢度做
                            // fuzzy match 揾返真正對應機身檔案嘅 id, 先傳落
                            // action_PlayActionName()。搵唔到就直接話俾 LLM 知邊個名
                            // 揾唔到, 等佢有機會 call self.robot.list_actions 再試,
                            // 而唔係盲目將 LLM 作嘅名直接傳落 AIDL (會撞返
                            // "raise_left_hand" 嗰種開唔到檔案嘅老問題)。
                            String resolvedId = resolveActionId(actionName);
                            if (resolvedId == null) {
                                isError = true;
                                resultText = "no action found matching \"" + actionName
                                        + "\" - call self.robot.list_actions to see valid names";
                                break;
                            }
                            UbxErrorCode.API_ERROR_CODE code = robot.action_PlayActionName(resolvedId);
                            isError = !isOk(code);
                            resultText = String.valueOf(code) + " (matched \"" + actionName
                                    + "\" -> id " + resolvedId + ")";
                            break;
                        }
                        case "self.robot.stop_action": {
                            // 見 "action/stop" endpoint 嗰段 comment - 停低之後補一個
                            // 「蹲下站起」做回位, 同 HTTP API 嗰邊行為保持一致。
                            UbxErrorCode.API_ERROR_CODE code = robot.action_StopAction();
                            try {
                                robot.action_PlayActionName(STOP_RECOVERY_ACTION_ID);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to play recovery action after "
                                        + "self.robot.stop_action", e);
                            }
                            isError = !isOk(code);
                            resultText = String.valueOf(code);
                            break;
                        }
                        case "self.robot.play_random_action": {
                            String randomId = resolveRandomActionId();
                            if (randomId == null) {
                                isError = true;
                                resultText = "no random-movement actions available";
                                break;
                            }
                            UbxErrorCode.API_ERROR_CODE code = robot.action_PlayActionName(randomId);
                            isError = !isOk(code);
                            resultText = String.valueOf(code) + " (played random action id " + randomId + ")";
                            break;
                        }

                        // -- Hardware control: servo/LED/PIR/sonar -----------------------
                        // 薄包裝, 邏輯全部委托返 handleApi() 已有嘅 "servo/*"、
                        // "led/*"、"pir/*" case 用緊嗰啲 Alpha2RobotApi 方法, 見
                        // AIDL_REFERENCE.md 相關章節同 handleApi() 個 comment 攞完整
                        // 已驗證行為/參數語意, 呢度唔重複解釋。
                        case "self.robot.servo_set_one": {
                            robot.waitChestReady(3000);
                            byte id = (byte) arguments.optInt("id", -1);
                            if (!arguments.has("angle")) {
                                isError = true;
                                resultText = "angle is required";
                                break;
                            }
                            int angle = arguments.optInt("angle");
                            short timeMs = (short) arguments.optInt("time_ms", 1000);
                            UbxErrorCode.API_ERROR_CODE code = robot.chest_SendOneFreeAngle(id, angle, timeMs);
                            isError = !isOk(code) || !robot.isChestReady();
                            resultText = String.valueOf(code) + " (chestReady=" + robot.isChestReady() + ")";
                            break;
                        }
                        case "self.robot.servo_set_all": {
                            robot.waitChestReady(3000);
                            String anglesCsv = arguments.optString("angles", "");
                            if (anglesCsv.isEmpty()) {
                                isError = true;
                                resultText = "angles is required (20 comma-separated integers)";
                                break;
                            }
                            String[] parts = anglesCsv.split(",");
                            int[] angles = new int[20];
                            for (int i = 0; i < 20 && i < parts.length; i++) {
                                angles[i] = Integer.parseInt(parts[i].trim());
                            }
                            short timeMs = (short) arguments.optInt("time_ms", 1000);
                            UbxErrorCode.API_ERROR_CODE code = robot.chest_SendFreeAngle(angles, timeMs);
                            isError = !isOk(code) || !robot.isChestReady();
                            resultText = String.valueOf(code) + " (chestReady=" + robot.isChestReady() + ")";
                            break;
                        }
                        case "self.robot.led_set_head": {
                            robot.waitHeaderReady(3000);
                            String preset = arguments.optString("preset", "long");
                            UbxErrorCode.API_ERROR_CODE code;
                            if ("stop".equals(preset)) {
                                cancelHeadLedReassert(); // 令持續補發嘅 background thread 停低, 唔好再打贏用戶想要嘅「熄燈」
                                code = robot.header_stop5MicEarLED();
                            } else {
                                if (!arguments.has("color") || !arguments.has("brightness")) {
                                    isError = true;
                                    resultText = "color and brightness are required unless preset=stop";
                                    break;
                                }
                                int color = arguments.optInt("color");
                                int brightness = arguments.optInt("brightness");
                                int p5, p6, p8;
                                switch (preset) {
                                    case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
                                    case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
                                    case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
                                    case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
                                    case "long":
                                    default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
                                }
                                code = robot.header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8);
                                // 見 reassertHeadEyeLed() javadoc - alpha2services 內部
                                // 「stop ear led」邏輯本身會持續循環咁用自己嘅固定參數
                                // 蓋走我哋 set 嘅顏色, 呢度要持續補發直到用戶下一次改指令
                                // 為止先真正企得住著住。
                                reassertHeadEyeLed(false, color, brightness, p5, p6, p8);
                            }
                            isError = !isOk(code) || !robot.isHeaderReady();
                            resultText = String.valueOf(code) + " (headerReady=" + robot.isHeaderReady() + ")";
                            break;
                        }
                        case "self.robot.led_set_eye": {
                            robot.waitHeaderReady(3000);
                            String preset = arguments.optString("preset", "long");
                            UbxErrorCode.API_ERROR_CODE code;
                            if ("stop".equals(preset)) {
                                cancelEyeLedReassert(); // 令持續補發嘅 background thread 停低, 唔好再打贏用戶想要嘅「熄燈」
                                code = robot.header_stop5MicEyeLED();
                            } else {
                                if (!arguments.has("color") || !arguments.has("brightness")) {
                                    isError = true;
                                    resultText = "color and brightness are required unless preset=stop";
                                    break;
                                }
                                int color = arguments.optInt("color");
                                int brightness = arguments.optInt("brightness");
                                int p5, p6, p8;
                                switch (preset) {
                                    case "flash": p5 = 100; p6 = 100; p8 = 0; break;
                                    case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
                                    case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
                                    case "long":
                                    default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
                                }
                                code = robot.header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8);
                                // 見 reassertHeadEyeLed() javadoc - 同 led_set_head 一樣要
                                // 持續補發先企得住。
                                reassertHeadEyeLed(true, color, brightness, p5, p6, p8);
                            }
                            isError = !isOk(code) || !robot.isHeaderReady();
                            resultText = String.valueOf(code) + " (headerReady=" + robot.isHeaderReady() + ")";
                            break;
                        }
                        case "self.robot.led_set_mouth": {
                            String preset = arguments.optString("preset", "breathing");
                            boolean ok;
                            if ("off".equals(preset)) {
                                ok = MouthLedData.off().apply();
                            } else {
                                int speedMs = arguments.optInt("speed_ms", 0);
                                ok = MouthLedData.breathing(speedMs).apply();
                            }
                            isError = !ok;
                            resultText = "ok=" + ok;
                            break;
                        }
                        case "self.sensors.get_pir": {
                            int state = lastPirTriggeredState;
                            String stateStr = state < 0 ? "unknown" : (state == 1 ? "triggered" : "clear");
                            resultText = "{\"state\":\"" + stateStr + "\"}";
                            break;
                        }
                        case "self.sensors.set_pir_enabled": {
                            robot.waitChestReady(3000);
                            if (!arguments.has("enabled")) {
                                isError = true;
                                resultText = "enabled is required";
                                break;
                            }
                            boolean enabled = arguments.optBoolean("enabled");
                            UbxErrorCode.API_ERROR_CODE code = robot.chest_setPirSensorEnabled(enabled);
                            isError = !isOk(code) || !robot.isChestReady();
                            resultText = String.valueOf(code) + " (chestReady=" + robot.isChestReady() + ")";
                            break;
                        }
                        case "self.sensors.get_sonar": {
                            resultText = "{\"distance_cm\":" + lastSonarDistanceCm
                                    + ",\"threshold_cm\":" + sonarThresholdCm + "}";
                            break;
                        }
                        case "self.sensors.set_sonar_threshold": {
                            robot.waitChestReady(3000);
                            if (!arguments.has("distance_cm")) {
                                isError = true;
                                resultText = "distance_cm is required";
                                break;
                            }
                            int distanceCm = arguments.optInt("distance_cm");
                            sonarThresholdCm = distanceCm;
                            sonarLedActive = false; // threshold changed - next frame decides fresh
                            UbxErrorCode.API_ERROR_CODE code = robot.chest_configureSonar(distanceCm);
                            isError = !isOk(code) || !robot.isChestReady();
                            resultText = String.valueOf(code) + " (chestReady=" + robot.isChestReady() + ")";
                            break;
                        }

                        case "self.camera.take_photo": {
                            String question = arguments.optString("question", "");
                            XiaozhiVisionResult visionResult = xiaozhiTakePhotoAndExplain(question);
                            if (visionResult.error != null) {
                                isError = true;
                                resultText = visionResult.error;
                            } else {
                                resultText = visionResult.text;
                            }
                            break;
                        }
                        case "self.camera.image_to_text": {
                            // 見 buildMcpToolsList() 呢個 tool 定義嗰段 comment 同
                            // xiaozhiVisionExplainRequest() 入面 "vision/explain is async"
                            // 嗰段 comment。實測 (2026-08) 唔淨係 GPT-5, Qwen 3.6 一樣會
                            // 撞到呢個 async flow, 更正返 comment - 唔係邊個 LLM provider
                            // 先有嘅行為, 睇嚟係 xiaozhi.me console 依家成個 vision/explain
                            // 後端行為, 同邊個 model 冇關。
                            String uuid = arguments.optString("uuid", "");
                            // 2026-08 新增 (真正根源): 用戶提供嘅 console 截圖 + logcat 顯示
                            // LLM 一路都有帶 uuid 落嚟, 但帶嘅係字面值 "placeholder"
                            // (即係 LLM 冇真正讀返之前 take_photo response 入面嘅
                            // uuid, 純粹將個 inputSchema 嘅 "uuid" 呢個字, 當成一個
                            // 佔位符字面值填咗落去) - 之前淨係 check uuid.isEmpty() 呢個
                            // fallback 條件, "placeholder" 唔係空字串, 完全冇觸發到, 就
                            // 攞住呢個假 uuid 去打 image_to_text, 梗係 server 500。改用
                            // 一個寬鬆嘅「睇落似唔似真 UUID」檢查 (標準 UUID: 8-4-4-4-12
                            // 個 hex 字符, 用 "-" 分隔) - 唔似就當 LLM 冇帶真嘅 uuid,
                            // 一樣 fallback 用返 device 自己記低嘅 lastPendingPhotoUuid,
                            // 唔理 LLM 講嘅字面值係咩 (無論係 "placeholder"、空字串,
                            // 定係之後可能出現嘅其他佔位符寫法都一樣處理)。
                            if (!isLikelyUuid(uuid)) {
                                uuid = lastPendingPhotoUuid;
                            }
                            if (uuid == null || uuid.isEmpty()) {
                                isError = true;
                                resultText = "no pending photo uuid to look up "
                                        + "(call self.camera.take_photo first)";
                                break;
                            }
                            XiaozhiVisionResult imgResult = xiaozhiFetchImageToText(uuid);
                            if (imgResult.error != null) {
                                // 2026-08 新增 (暫時 fallback): 呢個 image_to_text 嘅
                                // 真正 request payload 格式未經 xiaozhi.me 官方證實
                                // (見 xiaozhiFetchImageToText() javadoc), 實測撞到
                                // HTTP 500。喺官方 protocol 未確認之前, 唔好將
                                // "image_to_text returned HTTP 500: ..." 呢類技術性
                                // error 原文當成 isError:true 帶俾 LLM - 噉會令 LLM
                                // 讀出好突兀嘅技術錯誤俾用戶聽。改為 isError:false
                                // + 一句自然講法, 等對話至少有合理回應, 唔會斷崖式
                                // 失敗。原始 error 已經有 log (見 xiaozhiFetchImageToText()
                                // 入面 "image_to_text raw response" 個 log), 留返俾
                                // 之後對照 payload 格式用, 唔使靠呢句 resultText。
                                Log.w("XiaozhiVision", "image_to_text follow-up failed, "
                                        + "using fallback reply: " + imgResult.error);
                                resultText = "拍到相喇，不過而家仲睇唔到相入面嘅內容，遲啲可能先答到你。";
                            } else {
                                resultText = imgResult.text;
                                lastPendingPhotoUuid = null; // 用完即清, 避免舊 uuid 谷落去
                            }
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

                        // -- Local music playback: 薄包裝, 邏輯全部委托返
                        // listLocalMusicFiles()/resolveLocalMusicFile()/
                        // playLocalMusicFile()/stopLocalMusicPlayback() (跟
                        // audio/local_music/* 嗰幾個 HTTP endpoint 共用同一批 method),
                        // 唔喺呢度重複實現。
                        case "self.media.list_music": {
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (java.io.File f : listLocalMusicFiles()) {
                                arr.put(f.getName());
                            }
                            resultText = arr.toString();
                            break;
                        }
                        case "self.media.play_music": {
                            String musicName = arguments.optString("name", "");
                            if (musicName.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: name";
                                break;
                            }
                            java.io.File resolved = resolveLocalMusicFile(musicName);
                            if (resolved == null) {
                                isError = true;
                                resultText = "no music file found matching \"" + musicName
                                        + "\" - call self.media.list_music to see available files";
                                break;
                            }
                            playLocalMusicFile(resolved);
                            resultText = "now playing \"" + resolved.getName() + "\"";
                            break;
                        }
                        case "self.media.stop_music": {
                            stopLocalMusicPlayback();
                            resultText = "ok";
                            break;
                        }

                        // -- FM/網絡電台 (Radio Browser API): 薄包裝, 邏輯全部委托返
                        // searchRadioStations()/resolveRadioStation()/
                        // playRadioStream()/stopRadioPlayback() (跟 audio/radio/*
                        // 嗰幾個 HTTP endpoint 共用同一批 method), 唔喺呢度重複實現。
                        // searchRadioStations()/resolveRadioStation() 拋出嘅
                        // IOException/JSONException (網絡逾時、Radio Browser
                        // 服務暫時唔穩定等) 由外層嗰個 try/catch (Exception e) 接住,
                        // 唔使呢度重複處理。
                        case "self.media.search_radio": {
                            String searchQuery = arguments.optString("query", "");
                            if (searchQuery.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: query";
                                break;
                            }
                            java.util.List<org.json.JSONObject> found =
                                    searchRadioStations(searchQuery, 10);
                            lastRadioSearchResults = found;
                            if (found.isEmpty()) {
                                resultText = "no radio stations found matching \"" + searchQuery + "\"";
                                break;
                            }
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (org.json.JSONObject s : found) {
                                String country = s.optString("country");
                                String label = s.optString("name")
                                        + (country.isEmpty() ? "" : " (" + country + ")");
                                arr.put(label);
                            }
                            resultText = arr.toString();
                            break;
                        }
                        case "self.media.play_radio": {
                            String stationName = arguments.optString("name", "");
                            if (stationName.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: name";
                                break;
                            }
                            org.json.JSONObject resolvedStation = resolveRadioStation(stationName);
                            if (resolvedStation == null) {
                                isError = true;
                                resultText = "no radio station found matching \"" + stationName + "\"";
                                break;
                            }
                            playRadioStream(resolvedStation);
                            resultText = "now playing \"" + resolvedStation.optString("name") + "\"";
                            break;
                        }
                        case "self.media.stop_radio": {
                            stopRadioPlayback();
                            resultText = "ok";
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
                String appVer = "?";
                try {
                    appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {}
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"appVersion\":\"" + appVer + "\","
                        + "\"chestAvailable\":" + isOk(robot.isChestAvailable()) + ","
                        + "\"headerAvailable\":" + isOk(robot.isHeaderAvailable()) + ","
                        + "\"speechReady\":" + speechReady + ","
                        + "\"androidTtsReady\":" + androidTtsReady + "}");

            // -- Actions --------------------------------------------------------------
            case "action/list":
                return actionList();
            case "action/play":
                return codeResponse(robot.action_PlayActionName(require(query, "name")));
            case "action/stop": {
                // 2026-08 新增: 用戶要求「停止」要連帶做返「蹲下站起」呢個回位動作
                // (action_StopAction() 本身純粹截停緊播緊嘅動作, 唔會自動企返做返
                // 安全企立姿勢) - 停低之後主動 play 返 STOP_RECOVERY_ACTION_ID
                // (蹲下站起) 做回位。停低本身嘅 result code 照舊做返回值 (回位動作
                // 播唔播到, 唔應該影響「停止」呢個操作本身係咪算成功)。
                UbxErrorCode.API_ERROR_CODE stopCode = robot.action_StopAction();
                try {
                    robot.action_PlayActionName(STOP_RECOVERY_ACTION_ID);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to play recovery action after action/stop", e);
                }
                return codeResponse(stopCode);
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
                    // 語言揀擇 - lang 係 speech/tts_languages 返嚟嗰個 BCP-47
                    // tag (例如 "zh-HK"/"en-US"), null/留空就沿用 engine 而家
                    // 已經生效嗰個語言, 唔強行切換。LANG_MISSING_DATA/
                    // LANG_NOT_SUPPORTED 都係負數, 淨係 engine 真係接受咗先
                    // 繼續讀, 否則報錯返去, 唔好靜雞雞用緊嗰個語言讀 (用戶
                    // 冇要求嘅結果)。
                    String lang = query.get("lang");
                    if (lang != null && !lang.isEmpty()) {
                        Locale locale = Locale.forLanguageTag(lang);
                        int result = androidTts.setLanguage(locale);
                        if (result < TextToSpeech.LANG_AVAILABLE) {
                            return HttpServer.ApiResponse.error(
                                    "Android TTS engine does not support language: " + lang);
                        }
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
                } else {
                    // 見 robotTtsSpeaking field javadoc - 觸發成功先算「開始
                    // 播緊」, onServerPlayEnd 會揭返做 false。
                    robotTtsSpeaking = true;
                }
                return codeResponse(res);
            }
            case "speech/stop":
                stopAllSpeechPlayback();
                return codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);

            // Android TTS 語言揀擇 - 淨係 engine=android 用得 (Nuance/iFlytek
            // 兩個 AIDL engine 冇語言參數揀擇, lang 已經由 engine 本身固定死,
            // 見下面 speech/tts 個 android 分支)。ui_lang ("zh"/"en") 控制返嚟嘅
            // displayName 用邊種語言顯示。
            case "speech/tts_languages": {
                boolean english = "en".equals(query.get("ui_lang"));
                List<TtsLanguageOption> langs = listAndroidTtsLanguages(
                        english ? Locale.ENGLISH : Locale.TRADITIONAL_CHINESE);
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"languages\":[");
                for (int i = 0; i < langs.size(); i++) {
                    if (i > 0) sb.append(',');
                    TtsLanguageOption opt = langs.get(i);
                    sb.append("{\"tag\":\"").append(jsonSafe(opt.langTag))
                      .append("\",\"name\":\"").append(jsonSafe(opt.displayName)).append("\"}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            // Android TTS 引擎揀擇 - 機身可能裝咗多過一個系統 TTS 引擎 (例如出廠
            // 內建 + Google TTS + SVOX Pico), 呢三個 endpoint 俾 speech tab 揀
            // speech/tts 個 engine=android 分支實際用邊個講, 唔涉及 Nuance/
            // iFlytek。
            case "speech/tts_engines": {
                List<String> engines = listAndroidTtsEngines();
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"engines\":[");
                for (int i = 0; i < engines.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(jsonSafe(engines.get(i))).append('"');
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "speech/set_tts_engine": {
                String enginePkg = require(query, "engine");
                initAndroidTts(enginePkg);
                // 呢個切換本身係 async (initAndroidTts() 拆舊起新一個
                // TextToSpeech instance, 再等 OnInitListener 先真正 ready) -
                // 呢度嘅 "ok" 淨係話已經觸發咗切換, 唔代表即刻可以講嘢, 前端
                // 應該延遲少少先再 poll speech/cur_tts_engine。
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "speech/cur_tts_engine":
                return HttpServer.ApiResponse.ok(
                        "{\"ok\":true,\"engine\":\"" + jsonSafe(androidTtsEnginePkg) + "\"}");

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
                                    + "(e.g. right after speech/set_language) before calling speech/start_asr.");
                }
                return codeResponse(robot.speech_startSpeechNoWakeup());
            case "speech/set_voice":
                return codeResponse(robot.speech_setVoiceName(require(query, "name")));
            case "speech/set_language": {
                String lang = require(query, "lang");
                return codeResponse(robot.speech_setRecognizedLanguage(lang));
            }
            case "speech/self_interrupt":
                return codeResponse(robot.speech_setSelfInterrupt(Boolean.parseBoolean(require(query, "on"))));
            case "speech/inject":
                // "Pretend I heard this" - injects text via the AIDL onSpeech() dictation
                // path (Alpha2RobotApi.speech_startRecognized(), marked @Deprecated
                // upstream with no logged reason found). Untested on this firmware: may
                // reach the same local Nuance grammar that real speech does (in which
                // case a QA_* phrase from the reference list below would trigger a
                // Local_Result the normal way, on the EXISTING "asr_result" event - no
                // new event added here on purpose), or may be dead. This call only
                // reports whether the SDK accepted the request, not whether recognition
                // actually fired.
                //
                // 2026-08 新增: 之前呢度冇 speechReady gate，如果啱啱切換完 engine
                // (speechReady 短暫變返 false，等緊 onSpeechInitSuccess callback)
                // 就直接落去 SDK call，好大機會兩個 util 都仲係 null，攞到含糊嘅
                // API_ERROR_NOT_INIT，而唔係好似 speech/reset 咁清晰嘅錯誤訊息。
                // 而家加返個 gate，同 speech/init_grammar 嗰種做法睇齊。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_language) before calling speech/inject.");
                }
                return codeResponse(robot.speech_startRecognized(require(query, "text")));
            case "speech/iflytek_simulate":
                // 2026-08 新增: "打字當自己講咗呢句" - 直接將輸入文字當做 iFlytek
                // 引擎已經辨識完嘅結果, 送去 handleIflytekSemanticText() 做 1000 條
                // 問法配對 (中英文各 1000 條, 由輸入文字有冇漢字自動判斷用邊份 - 見
                // looksChinese()), 命中就即刻做返悠聊原本嘅「TTS200ms動作」流程。
                // 同 speech/inject 唔同: 呢個唔經任何機身 AIDL (唔靠
                // speech_startRecognized()/onSpeech() 呢條 "未知會唔會真係觸發辨識"
                // 嘅路), 純粹係本地 JSON 配對 + 直接 call robot.speech_startTTS()/
                // robot.action_PlayActionName(), 所以唔需要 speechReady gate, 淨係
                // 需要 robot 本身已經 initRobot() 完 (onCreate() 一開始就做咗)。
                // response 即時話俾前端知有冇配對到 (matched/question/type/
                // operation/answer/actionId), 唔使等 WebSocket event - 方便對話
                // 界面直接顯示配對結果, 唔使成日等 EventBus。
                //
                // 2026-08 新增: match() 而家搵唔到問法都會回傳一個「聽唔明」嘅
                // fallback 回應 (唔再係 null), 所以 matched:false 分支而家淨係
                // 剩返「輸入係空白字串」呢種 edge case 先會行到。
                {
                    String simText = require(query, "text");
                    IflytekSemanticMatcher.MatchResult simResult = handleIflytekSemanticText(simText, false);
                    if (simResult == null) {
                        return HttpServer.ApiResponse.ok(
                                "{\"ok\":true,\"matched\":false,\"input\":\"" + jsonSafe(simText) + "\"}");
                    }
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"matched\":true,"
                            + "\"input\":\"" + jsonSafe(simText) + "\","
                            + "\"question\":\"" + jsonSafe(simResult.question) + "\","
                            + "\"type\":\"" + jsonSafe(simResult.type) + "\","
                            + "\"operation\":\"" + jsonSafe(simResult.operation) + "\","
                            + "\"answer\":\"" + jsonSafe(simResult.answer) + "\","
                            + "\"actionId\":\"" + jsonSafe(simResult.actionId) + "\"}");
                }
            case "speech/stop_inject":
                // Companion to speech/inject (onStopSpeech). Untested, same caveats.
                // 同上，加返 speechReady gate。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event before calling "
                                    + "speech/stop_inject.");
                }
                return codeResponse(robot.speech_stopRecognized());
            // 2026-08 移除 speech/init_grammar、speech/start_grammar、
            // speech/stop_grammar 呢三個 endpoint (連同對應嘅 grammar_init/
            // grammar_result WebSocket event) - 前端 UI tile/按鈕已經全部拆走
            // (見 index.html/app-speech.js/app-log.js 對應 comment), 而家喺
            // 後端呢層都直接冚埋個入口, 確保 IAlpha2SpeechGrammarListener 呢個
            // AIDL callback 永遠唔會有機會被 register, 唔會再同 onServerCallBack()
            // 嗰條正常聽寫路徑 (asr_result) 各自獨立觸發語意配對 + TTS, 造成
            // 同一句話重複配對/講兩次。ASR 而家只得 asr_result 一條路徑會觸發
            // 語意配對/TTS/動作。
            // 呢三個 endpoint 對應嘅 AIDL method (speech_initGrammar/
            // speech_startGrammar/speech_stopGrammar) 喺 Alpha2RobotApi 依然
            // 保留 (SDK 本身嘅公開 API 面, 唔屬於呢個 app 嘅責任範圍), 淨係呢度
            // 唔再有任何 HTTP endpoint 可以觸發到佢哋。
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

            // 2026-08-15 更新: 真機已確認 cmd=72 開關生效, PIR 觸發正常 (見
            // RobotEventReceiver/registerAlpha2PirAlertListener 嘅 comment)。
            case "pir/set": {
                robot.waitChestReady(3000);
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                return codeResponseReady(robot.chest_setPirSensorEnabled(enabled), robot.isChestReady());
            }

            /** 2026-08-15 新增: 獨立於 pir/set 呢個感應器硬件開關本身, 純粹控制
             *  「偵測到人就閃紅燈/響鈴」呢個警示反應開唔開。已喺真機確認 PIR 事件
             *  本身 (cmd=-109, "PIR HUMON DETECT") 會正常觸發 (見 RobotEventReceiver
             *  個 CHEST_ACTION case 入面 alpha2_pir_state 嗰段 comment) - 呢個
             *  endpoint 就係俾前端揀要唔要對呢個事件有反應。 */
            case "pir/alert_enabled": {
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                setPirAlertEnabledAlpha2(enabled);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
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

            // -- Local music (/mnt/internal_sd/music/): 用戶自己放喺機身嘅音樂檔,
            // 同上面 audio/ringtones/* 嗰啲系統鈴聲係兩回事, 各自獨立一套 endpoint/
            // MediaPlayer, 詳見 listLocalMusicFiles()/playLocalMusicFile() 嘅
            // javadoc。"list" 冇 index (檔案清單會隨用戶自己加/減歌而變, 唔似
            // ringtone 嗰啲系統清單咁穩定), "play" 直接用檔名 (連副檔名) 揀。
            case "audio/local_music/list": {
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
                boolean first = true;
                for (java.io.File f : listLocalMusicFiles()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"name\":\"").append(jsonSafe(f.getName())).append("\"}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            case "audio/local_music/play": {
                String name = require(query, "name");
                java.io.File resolved = resolveLocalMusicFile(name);
                if (resolved == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"file not found\"}");
                }
                playLocalMusicFile(resolved);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                        + jsonSafe(resolved.getName()) + "\"}");
            }
            case "audio/local_music/stop": {
                stopLocalMusicPlayback();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // -- FM/網絡電台 (經 Radio Browser API, radio-browser.info, 動態搜全
            // 世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 嘅
            // javadoc, 呢部機唔再內置任何寫死嘅電台清單) - "search" 對應
            // self.media.search_radio, "play" 用 resolveRadioStation() 做人類
            // 語言名比對 (先撞 lastRadioSearchResults, 撞唔到就直接當新搜尋詞打
            // API)。加多一個 "status" 俾前端面板顯示「而家播緊邊個台」用 (電台冇
            // 檔名咁直觀, 用戶自己撳「轉台」之後有需要知道結果)。呢兩個 endpoint
            // 內部會打網絡, 同 MCP tool 嗰邊唔同 (嗰邊有外層 try/catch(Exception)
            // 包住成個 switch), handleApi() 冇, 所以呢度自己要包一層 try/catch
            // 將 IOException/JSONException 轉做正常嘅 {"ok":false,...} 回應,
            // 唔可以令個 exception 直接飛出 handleApi()。
            case "audio/radio/search": {
                String q = require(query, "query");
                try {
                    java.util.List<org.json.JSONObject> found = searchRadioStations(q, 10);
                    lastRadioSearchResults = found;
                    StringBuilder sb = new StringBuilder("{\"ok\":true,\"stations\":[");
                    boolean first = true;
                    for (org.json.JSONObject s : found) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"name\":\"").append(jsonSafe(s.optString("name")))
                                .append("\",\"country\":\"").append(jsonSafe(s.optString("country")))
                                .append("\"}");
                    }
                    sb.append("]}");
                    return HttpServer.ApiResponse.ok(sb.toString());
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe("radio search failed: " + e.getMessage()) + "\"}");
                }
            }
            case "audio/radio/play": {
                String name = require(query, "name");
                try {
                    org.json.JSONObject resolved = resolveRadioStation(name);
                    if (resolved == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"station not found\"}");
                    }
                    playRadioStream(resolved);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                            + jsonSafe(resolved.optString("name")) + "\"}");
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe("radio search failed: " + e.getMessage()) + "\"}");
                }
            }
            case "audio/radio/stop": {
                stopRadioPlayback();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/radio/status": {
                String id = currentRadioStationId;
                if (id == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":false}");
                }
                String currentName = currentRadioStationName;
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":true,\"id\":\""
                        + jsonSafe(id) + "\",\"name\":\""
                        + jsonSafe(currentName == null ? "" : currentName) + "\"}");
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

            // -- Alice talk server 假 endpoint (/api/alice/talkServer) -------------------
            //
            // 2026-08 新增。原廠 alice_Server (service_config.json 入面嗰個欄位) 寫死指去
            // 一個內部開發機 IP (http://10.10.1.54:8081/programd/talkServer?)，喺出面連唔
            // 到。實測拆解 alpha2services 證實: ASR 識別本身係 local (.bnf 語法比對，唔使
            // 上網)，但識別完之後個「攞對話回應」步驟會打一條 HttpURLConnection 去
            // alice_Server (com.ubtechinc.alpha2ctrlapp.network.c.c.a())，connectTimeout
            // 10 秒；打唔通就成個對話流程卡喺度冇反應，睇落好似 iFlytek 成套嘢都停擺，
            // 其實只係呢一步卡住。
            //
            // 呢個 endpoint 就係俾 *_openalpha2_offline preset (見下面 ALICE_OFFLINE_*)
            // 用嘅假後端: 對應個 preset 將 alice_Server 改指去
            // "http://127.0.0.1:8888/api/alice/talkServer?"，等呢條 HTTP call 打得通，
            // 令個流程唔再卡死。Request 格式 (form-urlencoded, 由
            // com.ubtechinc.alpha2ctrlapp.network.c.c.a() 組裝) 已拆解確認:
            //   appType=...&requestKey=...&requestTime=...&serviceVersion=...
            //   &systemLanguage=...&content=<識別到嘅文字>
            // Response 格式未拆到實際 schema (原廠條 link 一直打唔通，冇 log 過真正
            // response) —— 依家淨係要令個 HTTP round-trip 成功唔拋 exception，令下游
            // 唔再卡死；response body 係咪真係俾原廠 code 解析、解析失敗會點, 都仲未驗證，
            // 純粹「打得通」呢一步先。plain text 對應 talkServer 呢類 AIML/ALICE 協議
            // 常見嘅裸文字回覆格式。
            case "alice/talkServer":
                return aliceTalkServer(body);

            // -- iFlytek offline 隔離測試 (/api/iflytektest/*) ----------------------------
            //
            // 2026-08 新增。獨立於 alice_Server/config.json 呢一整條線之外嘅測試: 喺
            // open-alpha2 自己個 process 度，用抽自原裝 Alpha2Services.apk 嘅
            // com.iflytek.cloud/.msc/.common/.speech (app/libs/iflytek-msc-core.jar) +
            // 原裝、未 patch 過嘅 libmsc.so (app/src/main/jniLibs/armeabi-v7a/)，起一份
            // 完全獨立、唔靠 alpha2services.apk 起唔起緊嘅 SpeechRecognizer instance，
            // engine_type 寫死做 local。
            //
            // 用法: init -> start -> (講嘢) -> log 睇結果 -> stop。斷網情況下重複呢個
            // 流程，睇 onResult() 攞返嘅文字係咪空白 (Arthur 已確認呢個係佢想要嘅
            // 判準，唔需要理個 log 入面揀咗邊個 engine_mode)。
            //
            // 已知缺口 (未驗證，見 IflytekOfflineTest.java class-level javadoc 詳細版):
            //   - login 用嘅 appid 參數呢度冇齊 (未抄 com.ubtechinc.iflytek.speech.a.a
            //     嗰組完整 login 邏輯，個 class 屬於 com.ubtechinc.* package)
            //   - 呢份 code 未經過任何實機編譯/執行測試
            case "iflytektest/init":
                IflytekOfflineTest.clearLog();
                IflytekOfflineTest.init(getApplicationContext());
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"status\":\"init called, see /api/iflytektest/log\"}");
            case "iflytektest/start":
                IflytekOfflineTest.startListening(getApplicationContext());
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"status\":\"startListening called, see /api/iflytektest/log\"}");
            case "iflytektest/stop":
                IflytekOfflineTest.stop();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"status\":\"stopped\"}");
            case "iflytektest/destroy":
                IflytekOfflineTest.destroy();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"status\":\"destroyed\"}");
            case "iflytektest/log": {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String line : IflytekOfflineTest.getLog()) {
                    arr.put(line);
                }
                org.json.JSONObject wrapper = new org.json.JSONObject();
                try {
                    wrapper.put("ok", true);
                    wrapper.put("lines", arr);
                } catch (org.json.JSONException e) {
                    return HttpServer.ApiResponse.error("json build failed: " + e);
                }
                return HttpServer.ApiResponse.ok(wrapper.toString());
            }

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

    /** 2026-08 新增: 同 CN_PRESET_JSON 完全一樣, 淨係 default_App 由原廠嘅
     *  com.ubtech.iflytekmix 改做 OpenAlpha2 自己 (com.open.alpha2)。等機身開機
     *  聽到中文 wake word 之後, launch OpenAlpha2 本身, 唔再 launch 悠聊。用途:
     *  完全取代悠聊 APK, 由 OpenAlpha2 自己做 ASR 辨識完之後嘅語意理解/TTS/動作。 */
    private static final String CN_OPENALPHA2_PRESET_JSON = "{"
            + "\"alice_Server\":\"http://10.10.1.54:8081/programd/talkServer?\","
            + "\"asr_Language\":\"zh_cn\","
            + "\"default_App\":\"com.open.alpha2\","
            + "\"develop_Server\":\"http://dev.ubtrobot.com/opencenter/app/accesscheckapp\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"wakeup_threshold_mic5\":25,"
            + "\"wakeup_word\":\"CN_WAKEUP_NIHAO_ALPHA\","
            + "\"web_Server\":\"https://services.ubtrobot.com/ubx/\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\""
            + "}";

    /** 2026-08 新增: 同 EN_PRESET_JSON 完全一樣, 淨係 default_App 由原廠嘅
     *  com.ubtechinc.alphaenglishchat 改做 OpenAlpha2 自己。 */
    private static final String EN_OPENALPHA2_PRESET_JSON = "{"
            + "\"asr_Language\":\"en_us\","
            + "\"default_App\":\"com.open.alpha2\","
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

    /** 2026-08 新增: 同 CN_OPENALPHA2_PRESET_JSON 完全一樣, 淨係 alice_Server/web_Server/
     *  develop_Server 由原廠死 link 改指去 open-alpha2 自己個 HttpServer
     *  (http://127.0.0.1:8888/...)。用途: 令識別完之後嘅「攞對話回應」HTTP call
     *  (com.ubtechinc.alpha2ctrlapp.network.c.c.a()) 打得通 aliceTalkServer() 呢個假
     *  endpoint，唔再因為打去死 link 而卡死。未驗證: alpha2services 個 HTTP client 用嘅
     *  係 org.apache.http.impl.client.DefaultHttpClient (web_Server 個 path)
     *  同 java.net.HttpURLConnection (alice_Server 個 path)，兩者對 "connection refused"
     *  同 "拎到 response 但格式睇唔明" 嘅容忍度可能唔同，即使呢個 preset 令 HTTP round-trip
     *  成功，都唔保證下游解析唔會再有第二層卡點——依家未實機測試過。 */
    private static final String CN_OPENALPHA2_OFFLINE_PRESET_JSON = "{"
            + "\"alice_Server\":\"http://127.0.0.1:8888/api/alice/talkServer?\","
            + "\"asr_Language\":\"zh_cn\","
            + "\"default_App\":\"com.open.alpha2\","
            + "\"develop_Server\":\"http://127.0.0.1:8888/api/alice/devAccessCheck\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"wakeup_threshold_mic5\":25,"
            + "\"wakeup_word\":\"CN_WAKEUP_NIHAO_ALPHA\","
            + "\"web_Server\":\"http://127.0.0.1:8888/api/alice/ubx/\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\""
            + "}";

    /** 2026-08 新增: 同 EN_OPENALPHA2_PRESET_JSON 完全一樣, alice_Server/web_Server/
     *  develop_Server 改法同 CN_OPENALPHA2_OFFLINE_PRESET_JSON 一樣, 見嗰邊註解。 */
    private static final String EN_OPENALPHA2_OFFLINE_PRESET_JSON = "{"
            + "\"asr_Language\":\"en_us\","
            + "\"default_App\":\"com.open.alpha2\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"web_Server\":\"http://127.0.0.1:8888/api/alice/ubx/\","
            + "\"develop_Server\":\"http://127.0.0.1:8888/api/alice/devAccessCheck\","
            + "\"alice_Server\":\"http://127.0.0.1:8888/api/alice/talkServer?\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\","
            + "\"wakeup_word\":\"EN_WAKEUP_HELLO_ALPHA_THREE\","
            + "\"wakeup_threshold_mic5\":25"
            + "}";

    /** alice_Server 假後端。Request body 係 form-urlencoded (由 alpha2services 嘅
     *  com.ubtechinc.alpha2ctrlapp.network.c.c.a() 組裝)，欄位: appType/requestKey/
     *  requestTime/serviceVersion/systemLanguage/content。淨係讀 content 出嚟做 log
     *  方便你對住實機 debug 睇「機身識別到嘅文字有冇送到呢度」，回應內容依家係
     *  hardcode 嘅固定句子 —— 想接返真正智能回覆 (例如轉發去 LLM API) 就係喺呢個
     *  method 度加。 */
    private HttpServer.ApiResponse aliceTalkServer(String body) {
        String content = "";
        if (body != null) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String key = pair.substring(0, eq);
                if ("content".equals(key)) {
                    try {
                        content = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (java.io.UnsupportedEncodingException e) {
                        content = pair.substring(eq + 1);
                    }
                    break;
                }
            }
        }
        Log.i(TAG, "aliceTalkServer received content=" + content);
        // TODO: 呢句係 placeholder。想真係有智能回覆，喺呢度轉發 content 去你自己揀嘅
        // LLM/對話服務，攞返嚟做 response body。依家淨係求「HTTP round-trip 打得通」。
        return new HttpServer.ApiResponse(200, "text/plain; charset=utf-8", "OK");
    }

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

    /** preset = "cn" | "en" | "cn_openalpha2" | "en_openalpha2"。前兩個係機身出廠
     *  內置嘅原裝 default config, 一字不改照抄；後兩個係 2026-08 新增, 同原裝完全
     *  一樣但 default_App 指返 OpenAlpha2 自己 (com.open.alpha2), 等 wake word
     *  觸發之後直接 launch OpenAlpha2, 唔再 launch 悠聊/alphaenglishchat。四個都
     *  唔設「還原」掣、亦唔做寫入前備份——隨時可以互相切換, 冇「損壞」呢個概念。
     *  寫入對應嘅 JSON + 精簡 TXT 版本, 兩個檔案要同步。 */
    private HttpServer.ApiResponse serviceConfigSet(String preset, boolean reboot) {
        String json;
        if ("cn".equals(preset)) {
            json = CN_PRESET_JSON;
        } else if ("en".equals(preset)) {
            json = EN_PRESET_JSON;
        } else if ("cn_openalpha2".equals(preset)) {
            json = CN_OPENALPHA2_PRESET_JSON;
        } else if ("en_openalpha2".equals(preset)) {
            json = EN_OPENALPHA2_PRESET_JSON;
        } else if ("cn_openalpha2_offline".equals(preset)) {
            json = CN_OPENALPHA2_OFFLINE_PRESET_JSON;
        } else if ("en_openalpha2_offline".equals(preset)) {
            json = EN_OPENALPHA2_OFFLINE_PRESET_JSON;
        } else {
            return HttpServer.ApiResponse.error("preset must be 'cn', 'en', 'cn_openalpha2', "
                    + "'en_openalpha2', 'cn_openalpha2_offline' or 'en_openalpha2_offline'");
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
     *  AudioPlaybackController's format - see AudioPlaybackController.SAMPLE_RATE_HZ
     *  and app-mic.js's TALK_TARGET_SAMPLE_RATE; 2026-08 改返 16kHz - 當初落 8kHz
     *  淨係為咗同步已經永久停用嘅 walkie-talkie, 呢個理由而家唔存在) from the
     *  browser's mic, queued for playback.
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

    /** 2026-08 新增: 用戶實測 self.robot.led_set_head/led_set_eye 呢兩個 MCP tool
     *  「著到1秒又熄左」/「開兩次又停左」- 對照 logcat 搵到真正機制: 唔淨係
     *  releaseMicForAudioIo() javadoc 講嘅「setWakeState() 觸發 broadcast 熄燈」
     *  咁簡單, 而係 alpha2services 內部 AlphaMainSeviceImpl 個 "stop ear led"
     *  邏輯本身**唔係真係熄咗個 LED**, 而係內部照樣 call 多次
     *  header_ledSetHead5Mic(color=3,brightness=2,...,p5=400,p6=9000,p8=2) 呢組
     *  固定參數去做「熄燈」效果 (即係set做一個好暗嘅顏色/圖案, 唔係真正斷電) -
     *  而呢個內部熄燈邏輯**持續循環運作**, 密度好高 (實測相隔淨係 0.8 秒左右
     *  就再嚟一次), 只要小智常開對話仲開住就唔會停。之前嘅做法 (喺呢度單次補發
     *  2 秒就收工) 追唔切呢個持續循環嘅頻率, 2 秒過咗之後又打番輸。
     *
     *  依家改做「持續生效直到用戶下一次改指令為止」: 每次 led_set_head/
     *  led_set_eye 被 call, 就開一條長駐 background thread, 用
     *  headLedReassertGeneration/eyeLedReassertGeneration 呢兩個 generation
     *  counter 分別做 head/eye 獨立嘅取消機制 - 新一次 call (無論係新顏色定係
     *  preset=stop) 都會令 generation 數字進位, 舊嗰條 thread 見到自己個
     *  generation 已經過時就會自行停止, 保證同一時間淨係得一條 thread 喺度
     *  持續補發緊, 唔會愈開愈多。preset=stop 嗰個 case (header_stop5MicEarLED())
     *  淨係要令 generation 進位令舊嘅補發 thread 停低, 唔需要自己再開新
     *  thread。
     *
     *  2026-08 再修正: 用戶實測 300ms 嘅補發間隔仍然「同其他 code 相撞」- 對照
     *  logcat 發現內部熄燈循環大約每 2 秒觸發一次, 300ms 嘅間隔理應大部分時間
     *  都贏返, 但兩種顏色交替出現喺肉眼睇落仍然構成明顯閃爍。呢個「熄燈循環」
     *  本身冇辦法完全消除 (只要小智 auto-mode 開住就會持續運作), 淨係可以縮短
     *  「熄咗未補發返」嗰段空隙嘅長度嚟減少肉眼可見嘅閃爍程度。將補發間隔由
     *  300ms 縮短去 80ms - AIDL call 本身好快, 2 秒週期入面補發 25 次左右都唔會
     *  構成負擔, 但空隙短好多, 閃爍會冇咁明顯。 */
    private static final long LED_REASSERT_INTERVAL_MS = 80;
    private final java.util.concurrent.atomic.AtomicLong headLedReassertGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong eyeLedReassertGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 令目前生效緊嘅 head/eye LED 持續補發 thread (如果有) 喺下一個補發週期
     *  自行停止, 唔開新 thread 補返 - preset=stop 個 case 用呢個。 */
    private void cancelHeadLedReassert() {
        headLedReassertGeneration.incrementAndGet();
    }

    private void cancelEyeLedReassert() {
        eyeLedReassertGeneration.incrementAndGet();
    }

    private void reassertHeadEyeLed(final boolean isEye, final int color, final int brightness,
            final int p5, final int p6, final int p8) {
        final java.util.concurrent.atomic.AtomicLong genCounter =
                isEye ? eyeLedReassertGeneration : headLedReassertGeneration;
        final long myGeneration = genCounter.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (genCounter.get() == myGeneration) {
                    try {
                        Thread.sleep(LED_REASSERT_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (genCounter.get() != myGeneration) {
                        return; // 補發期間又有新一次 call, 或者用戶 call 咗 stop, 讓位俾佢
                    }
                    if (isEye) {
                        robot.header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8);
                    } else {
                        robot.header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8);
                    }
                }
            }
        }, "XiaozhiLedReassert").start();
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
                    // 同 startXiaozhiMicHoldEnforcer() 一樣嘅原因 (見
                    // robotTtsSpeaking field javadoc) - 機身 robot-side TTS
                    // 正播緊嘢就跳過呢一輪, 唔好打斷佢。
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

    // 2026-08 (已淘汰): 之前用 waitForStableFrame() 跳過幾幀嚟迴避 preview frame
    // 過渡期問題 (AE/AF 未收斂) - 反編譯用戶提供、實測成功嘅第三方 apk 之後發現真正
    // 根源係 capture 方式本身 (preview frame vs 真正單張拍攝), 已改用
    // CameraController.takePhoto() (真正 camera.takePicture()), 見
    // xiaozhiTakePhotoAndExplain() 嗰段 comment。呢個「跳幀」workaround 冇再被用,
    // 已移除, 避免留低死 code 同令人誤會依然係現行做法。

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
        // 2026-08 修正: 之前淨係 escape 反斜線同雙引號, 冇處理換行/回車/tab -
        // XiaozhiOtaClient 個 server 回應嘅 activationMessage 實測證實會帶住
        // literal "\n" (真機 logcat 見到 "xiaozhi.me" 後面直接斷咗行), 送入
        // EventBus.publish() 組出嚟嘅 JSON string 入面如果有未 escape 嘅真正換行
        // 字元係語法上非法 (JSON string 唔准有 literal newline) - 前端
        // JSON.parse() 會直接拋錯, 令成個 event 跌入 catch 變成 type:"raw",
        // 令 "xiaozhi_activation" 呢個 type 永遠比對唔中, 界面對應嘅顯示邏輯
        // (xiaozhiShowActivationCode()) 完全唔會觸發 - 呢個先係「websocket log
        // 見到啲嘢, 但界面無顯示」嘅真正成因。
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** 2026-08 新增: onServerCallBack() 收到嘅 raw 字串, 喺「語法識別」(grammar,
     *  logcat type:1) 路徑底下係一個未解構嘅 iFlytek JSON, 例如
     *  {"text":"你的爸爸是谁啊","rc":4}, 而唔係純文字 (純文字係「聽寫識別」
     *  dictation, type:0, 嗰條路徑先有嘅格式)。呢個 method 判斷輸入係咪呢種
     *  JSON 格式, 係就抽返 text field 出嚟, 唔係 (或者 parse 失敗/text field
     *  唔存在) 就原封不動退返原字串, 令 type:0 路徑同 "Local_Result:..." 路徑
     *  完全唔受影響。*/
    private static String extractGrammarResultText(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return raw; // 唔係 JSON 格式 (例如 "Local_Result:..." 或純文字聽寫結果), 原樣返回
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("text")) {
                return obj.getString("text");
            }
        } catch (JSONException e) {
            // parse 唔到就當佢唔係呢種格式, 原樣返回 - 避免因為格式估錯而搞衰
            // 其他冇問題嘅 ASR 路徑
        }
        return raw;
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
