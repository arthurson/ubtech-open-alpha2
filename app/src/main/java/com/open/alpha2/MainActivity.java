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
import com.ubtechinc.mic5.LedControl;

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
    /** 自訂小智 server 設定 - 開關開了才用 PREF_XIAOZHI_OTA_URL, 關了就跟回
     *  XiaozhiOtaClient.DEFAULT_OTA_URL (官方 api.tenclass.net)。見
     *  handleXiaozhiApi() 的 "ota_config/get"/"ota_config/set" case 和
     *  runXiaozhiActivationFlow() 怎麼讀這個設定。 */
    private static final String PREF_XIAOZHI_OTA_CUSTOM_ENABLED = "xiaozhi_ota_custom_enabled";
    private static final String PREF_XIAOZHI_OTA_URL = "xiaozhi_ota_url";
    // 2026-08 新增: 自架 server 未必跟足官方協議形狀 (OTA response 夾著
    // websocket url/token 一起送回來) - 有些自架方案要用戶自己手動填這幾樣東西。
    // 全部留空 = 跟回自動流程 (由 OTA response 拿); 有填就用來覆寫對應的自動值。
    // 只有在 PREF_XIAOZHI_OTA_CUSTOM_ENABLED 開了的時候才讀這幾個, 和 OTA URL
    // 本身一起收在同一個「自訂小智 server」開關底下。
    private static final String PREF_XIAOZHI_WS_URL_OVERRIDE = "xiaozhi_ws_url_override";
    private static final String PREF_XIAOZHI_DEVICE_ID_OVERRIDE = "xiaozhi_device_id_override";
    private static final String PREF_XIAOZHI_TOKEN_OVERRIDE = "xiaozhi_token_override";
    private static final String PREF_XIAOZHI_DEVICE_ID = "xiaozhi_device_id";
    private static final String PREF_MUSIC_FILLER_ACTION_ENABLED = "music_filler_action_enabled";
    private static final String PREF_MUSIC_EQ_PRESET = "music_eq_preset";
    // 2026-08 新增: MCP tool 個別 enable/disable 設定。總開關預設 true (保持現有
    // 行為 - 已經在用的人不應該因為這個功能上線而工具突然全部消失)。
    // disabled tool 清單預設空 (也就是全部 enabled), 用逗號分隔的 tool name 儲存
    // 在同一個 SharedPreferences, 用 name 不用 index 是因為 tool 清單本身會隨版本
    // 增減, index 會漂移, name 才是穩定的 identity。
    private static final String PREF_XIAOZHI_MCP_ENABLED = "xiaozhi_mcp_enabled";
    // 見 xiaozhiTtsEngine field 的 javadoc。
    private static final String PREF_XIAOZHI_TTS_ENGINE = "xiaozhi_tts_engine";
    private static final String PREF_XIAOZHI_MCP_DISABLED_TOOLS = "xiaozhi_mcp_disabled_tools";
    /** 官方 xiaozhi-esp32 firmware 寫死用的 vision/explain endpoint (esp32_camera.cc
     *  Explain() 實作) - 這個 URL 不會經 OTA check_version 的回應帶回來 (見
     *  runXiaozhiActivationFlow() 的 comment: response 只有 activation/websocket
     *  兩個 block), 所以要獨立一個設定。自訂 server 開著的時候如果沒填這個, 就跟回
     *  官方這個 - 很多自架 server 都沒實作 vision explain, 這種情況下 take_photo
     *  call 出去會收到 404/連不到, self.camera.take_photo 的 case 會將這個原因
     *  告訴 LLM 知道, 而不是靜靜地假裝成功。
     *
     *  2026-08 修正: 之前這裡寫死用 https://, 但實測用 https:// 撞到 HTTP 404
     *  (即使 xiaozhi.me console 側已經開通了 vision/camera 服務也一樣) - 對照
     *  官方 esp32_camera.cc 的 source (SetExplainUrl/Explain() 實作) 和 GitHub
     *  issue #708 的實機 log, 官方 firmware 打的其實是 http:// (不加密), 不是
     *  https://: "Opening HTTP connection to http://api.xiaozhi.me/mcp/vision/explain"
     *  低於這個 scheme 的路由在 server 側可能和 https:// 不是同一個 virtual
     *  host/根本沒 mapping, 所以之前一直 404。這裡跟回官方實際用的 scheme。 */
    /** Fallback vision/explain URL, only used when the server hasn't (yet) told us
     *  its real one via the "initialize" MCP request's params.capabilities.vision
     *  (see XiaozhiClient.getVisionUrl()'s comment for the full story - that's the
     *  authoritative source; this constant is a last-resort default for the case
     *  where take_photo is somehow called before any "initialize" has been
     *  received). 不保證對 - 純粹一個合理猜測的底線值, 不應該是主要路徑。
     *
     *  2026-08 修正: 之前這裡用 http://api.xiaozhi.me/... - 反編譯一個用戶提供、
     *  實測拍照成功的第三方 apk (package com.huihongcloud.xiaozhi) 的
     *  classes.dex, 證實它 OTA 用的其實是 https://api.tenclass.net/xiaozhi/ota/
     *  (和 DEFAULT_OTA_URL 一致) - api.xiaozhi.me 這個 domain 根本沒有
     *  /mcp/vision/explain 這條路由, 一直 404 和 console 側有沒有開通 vision 服務
     *  完全無關。改跟回 api.tenclass.net, scheme 跟回 DEFAULT_OTA_URL 一致的
     *  https。 */
    private static final String DEFAULT_VISION_URL = "https://api.tenclass.net/xiaozhi/mcp/vision/explain";
    private static final String PREF_XIAOZHI_VISION_URL = "xiaozhi_vision_url";
    /** 相機解析度 (用戶指定) - take_photo 特意用小於一般 camera/snapshot 預覽的
     *  解析度, 因為這張照片只是要上傳去 vision explain 給 LLM 「看」, 不是給人單獨
     *  看的照片, 小一點可以讓上傳/處理快一點, 也夠 LLM 辨識到大致內容。 */
    private static final int XIAOZHI_PHOTO_WIDTH = 480;
    private static final int XIAOZHI_PHOTO_HEIGHT = 360;

    // 2026-08 新增: 用戶要求所有「停止」入口 (action/stop HTTP endpoint,
    // self.robot.stop_action MCP tool, 小智面板「⏹ 全部停止」/拍頭都經這兩個
    // 之一) 停掉現在正在播放的動作之後, 補播回「蹲下站起」這個動作做回位 - 停掉
    // 不應該留下機身在一個中途/不端正的姿勢。id 來自
    // blockly-actions-data.js/xiaozhi_actions.json 現有記錄的「蹲下站起」
    // (nameCn: 蹲下站起, nameEn: squat down up)。
    private static final String STOP_RECOVERY_ACTION_ID = "1510818174706";

    // 2026-08 新增: 記住最近一次 self.camera.take_photo 拿到的 "async, 未完成"
    // uuid (見 xiaozhiVisionExplainRequest() 的 comment) - 給之後 LLM (GPT-5)
    // 主動再發的 "self.camera.image_to_text" tools/call 用來核對/取回真正描述。
    // 只記最新一個 (單一 device, 沒有並行 take_photo 的需要) - 用完/逾時後應
    // 清成 null, 避免舊 uuid 混進新一次 call。
    private volatile String lastPendingPhotoUuid;

    private Alpha2RobotApi robot;
    private HttpServer httpServer;
    // 小智 (XiaoZhi) AI 對話 - 獨立於機械人 AIDL 之外的 client-side WebSocket
    // 連線, 連出去 xiaozhi.me。單一 instance, 在 onCreate() 才建立 (要用
    // getSharedPreferences() 取/生成 device id, field initializer 那時 Activity
    // context 未必 ready), 由 handleXiaozhiApi() 開關
    // (見 handleXiaozhiApi() 的 javadoc)。
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
    // 小智 tab 的 TTS 輸出引擎選擇 - "xiaozhi" (預設) 也就是維持原本行為 (server
    // 送 opus 過來, XiaozhiAudioController 解碼播放); 選
    // "iflytek"/"nuance"/"android" 就完全靜音那段 opus (見
    // xiaozhiClient.setAudioSink() 裡面對這個 field 的判斷), 改用本地
    // speech/tts 這條路 (和 speech tab 的 speakTts() 用著同一個 API) 逐句
    // 讀出小智回覆 - 觸發時機是 xiaozhi_tts 的 "sentence_start" (對話氣泡本身
    // 也是用這個顯示; xiaozhi_llm 的 data.text 其實是表情 emoji, 不是對話內容,
    // 不可以用來讀), 前端用隊列排著逐句讀完才讀下一句 (見
    // xiaozhiEnqueueTts()/xiaozhiProcessTtsQueue() javadoc)。用
    // SharedPreferences 持久化 (和 PREF_XIAOZHI_MCP_ENABLED 等其他小智設定
    // 一致的做法), 跨重啟記得住選了哪個。
    private volatile String xiaozhiTtsEngine = "xiaozhi";
    /** Tracks consecutive unexpected-disconnect reconnect attempts for
     *  xiaozhiScheduleReconnect()'s backoff - reset to 0 on any successful (re)connect
     *  (see runXiaozhiActivationFlow()'s success path) so a stable connection later
     *  doesn't inherit a long delay from an earlier flaky period. */
    private final java.util.concurrent.atomic.AtomicInteger xiaozhiReconnectAttempts =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 2026-08 新增: 修「連不到、很快自己斷線、用戶心急狂按連線鍵」這個 bug -
     *  根源是 xiaozhiScheduleReconnect() 意外斷線之後有 5 秒 backoff delay,
     *  這 5 秒裡面 xiaozhiActivationStatus 還停留在斷線前那個值 (通常是
     *  CONNECTED), 不在 "connect" case 的 guard 擋著的 stage 名單裡面, 用戶如果
     *  在這 5 秒內按「連線」就會通過 guard、額外起多一條 runXiaozhiActivationFlow
     *  thread - 和 5 秒後真正觸發的自動重連 thread 同時運行, 兩條互相踩
     *  xiaozhiActivationStatus/xiaozhiClient 的狀態, 讓連線更加不穩定、越按
     *  越糟。單靠 xiaozhiActivationStatus 的 stage 判斷不夠, 因為由「決定要
     *  起 thread」到「thread 真正設回那個 stage」中間有時間差, 這個窗口裡面
     *  判斷會判錯。用這個獨立的 AtomicBoolean 做 compareAndSet 原子操作,
     *  保證整個 app 任何時候最多只有一條 runXiaozhiActivationFlow 在跑著 -
     *  三個起 thread 的入口 (connect case / auto_mode case /
     *  xiaozhiScheduleReconnect 的 delayed runnable) 都要經這個 gate,
     *  runXiaozhiActivationFlow() 本身在 finally 釋放。 */
    private final java.util.concurrent.atomic.AtomicBoolean xiaozhiActivationInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private RobotEventReceiver dynamicReceiver;
    private BroadcastReceiver batteryReceiver;

    // -- WiFi 指示燈 (2026-08-25) -----------------------------------------------
    // 開機預設 wifi 燈長着紅色; WiFi 一連上就轉藍燈, 斷開就轉返紅燈。真機掃描確認
    // ledSetOn(12) = wifi 藍燈, ledSetOn(13) = wifi 紅燈。ledSetOn 係累加式,
    // 所以每次切換都先 ledSetOFF() 清場再點目標顏色, 避免紅藍齊着。同 pad 燈共用
    // 同一條 burst 重試路徑 (裝置會同 alpha2services 打交)。
    private static final int WIFI_LED_INDEX_BLUE = 12;
    private static final int WIFI_LED_INDEX_RED = 13;
    private BroadcastReceiver wifiLedReceiver;
    private BroadcastReceiver panelUrlReceiver;
    private TextView panelLinkView;
    private String currentPanelUrl;
    private final CameraController cameraController = new CameraController();
    private final AudioController audioController = new AudioController();
    private final AudioPlaybackController audioPlaybackController = new AudioPlaybackController();
    private final MusicController musicController = new MusicController();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private EventBus.Listener gestureListener;
    private Runnable volumeRepeater;

    // -- Pad (+/-) 實體鍵指示燈 (2026-08-25) -----------------------------------
    // headboard v1.1 上 alpha2services v1.0 協議不合, 按 +/- 時 MCU 不再自己點燈,
    // 要我們經 /dev/led_eye (LedControl JNI) 補回。真機掃描確認:
    //   ledSetOn(14) = volume- 燈, ledSetOn(16) = volume+ 燈, ledSetOn(12) = wifi 藍燈。
    // ledSetOn 是累加式 (連續 call 兩個 index 兩顆都會亮); ledSetOFF() 熄掉這些
    // 單顆 LED 但不影響頭/眼環燈。
    //
    // 實測單發一條 ledSetOn 有時會靜靜地失敗 (原因未明, 疑似 alpha2services 那個
    // 假熄燈循環間中搶贏), 所以策略是「快速連發」: 按住期間每 PAD_LED_INTERVAL_MS
    // 補發一次組合, 一旦成功燈就會維持住; 放手後連發幾次 ledSetOFF 確保熄到。
    // 不用任何「prime+等待」序列 - 不需要, 也是之前反應慢的原因。
    private static final int PAD_LED_INDEX_MINUS = 14;
    private static final int PAD_LED_INDEX_PLUS = 16;
    private static final long PAD_LED_INTERVAL_MS = 80;
    private static final int PAD_LED_OFF_RETRIES = 4;
    private final java.util.concurrent.ExecutorService padLedExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean padMinusHeld = false;
    private volatile boolean padPlusHeld = false;
    private volatile boolean padLedWorkerRunning = false;

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
    private static final long MIC_HOLD_ENFORCER_INTERVAL_MS = 2000;

    /** true = XiaoZhi (小智) 語音對話現在持有著 mic 擁有權 (releaseMicForAudioIo()
     *  已經 call 了, AudioRecord 已經開著)。獨立於 micHeldByApp (Speech/Mic tab 專用) -
     *  兩個功能各自拿放, 互不影響, 見 stopXiaozhiMic() 的 comment。前端靠
     *  XIAOZHI_MIC_STATE_EVENT 反映這個狀態做綠/灰燈號 (見 index.html
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

    /** 2026-08 新增: 離線文法辨識 (iFlytek local BNF grammar) 模式現在開不開。
     *  開了之後, 機身 alpha2services 會用 engine_type=local + APK 裡面的
     *  assets/asr/common.jet 離線資源做本地文法辨識 (完全不用上網), 辨識結果
     *  經 grammar listener 這條路徑回來。同時 onServerCallBack() 那條正常聽寫
     *  路徑會被 gate 住 - 因為 mSpeechServiceUtil 和 mAsrServiceUtil 是兩個
     *  獨立 binding, firmware 有機會將同一句結果派給兩邊, 如果兩邊都各自
     *  觸發語意配對 + TTS, 就會重複答兩次 (2026-08 移除舊 grammar endpoints
     *  那時見過的問題)。只有 grammar listener 一條路徑會觸發回應。 */
    private volatile boolean offlineGrammarActive = false;

    /** 2026-08 新增: 最後一次 speech/init_grammar 的機身構建結果 - errorCode==0
     *  才算成功。speech/start_grammar 會用它做 gate: 文法未構建成功就開始辨識,
     *  機身會因為沒有本地 grammar 而將所有語音跌落雲端聽寫 fallback, 離線時變成
     *  「說什麼都是網路錯誤」(實測 logcat: 10114/20002), 所以這裡早一步擋住。 */
    private volatile boolean lastGrammarBuildOk = false;

    /** 2026-08 新增: 「自動跟網路切換」開關 - 開了的話, 沒網路時自動入離線文法
     *  模式, 有網路時自動退出來走回雲端聽寫。偏好存 SharedPreferences (共用
     *  頂頭那個 PREFS_NAME), 預設開。 */
    public static final String PREF_OFFLINE_AUTO = "offline_grammar_auto";
    private volatile boolean offlineGrammarAutoSwitch = true;
    /** 離線文法構建中/剛構建完, 等著自動開始辨識的 pending flag - 由
     *  grammar init callback 成功之後接手做 start。 */
    private volatile boolean pendingOfflineEnable = false;
    /** 2026-08 新增: init_grammar 進行中的防重入鎖 - 開機那時 speech_ready
     *  和 connectivity_change 兩個觸發可以幾乎同時到達, 疊兩次 buildGrammar
     *  會讓 firmware destroyASR 再重建, 打壞剛起好的辨識 session (實測:
     *  離線模式開了但完全沒反應)。 */
    private volatile boolean grammarInitInFlight = false;
    /** 最後一次模式切換時間 (ms) - 防止網路飄忽讓模式不停翻轉 (每次翻轉都
     *  會 stop/start 文法, 中間那段說話是沒反應的)。 */
    private volatile long lastModeSwitchMs = 0;
    private static final long MODE_SWITCH_MIN_INTERVAL_MS = 15000;

    /** 2026-08 新增: 上次由 Radio Browser API (radio-browser.info) 搜到的電台結果
     *  cache - 給 self.media.play_radio/audio/radio/play 用「上一次
     *  self.media.search_radio 找到的結果裡面選一個」這個 flow (見
     *  searchRadioStations()/resolveRadioStation() 的 javadoc), 不是一份固定的
     *  本地清單 (這台機不再內建任何寫死的電台, 全部經這個 API 動態找)。 */
    private volatile java.util.List<org.json.JSONObject> lastRadioSearchResults;

    /** Bearer token from the most recent successful runXiaozhiActivationFlow() -
     *  reused for the vision/explain HTTP call (self.camera.take_photo tool, see
     *  xiaozhiVisionExplain()) since that endpoint uses the same Device-Id/Client-Id/
     *  Authorization headers as the WebSocket connection itself, not a separate
     *  credential. null until the first successful connect. */
    private volatile String xiaozhiAccessToken;
    // 2026-08 新增: 之前這裡的 comment 已經說「和 WebSocket 連接一樣的
    // Device-Id/Client-Id/Authorization headers」, 但 xiaozhiVisionExplainRequest()
    // 實際沒送 Client-Id header - 反編譯一個用戶提供、實測拍照成功的第三方 apk
    // (package com.huihongcloud.xiaozhi) 的 vision explain 實現, 證實它真的有送
    // 這個 header (invoke-virtual v3, v2, LA/i;->f("Client-Id", XiaoZhi.a0)), 對應
    // 就是連接 WebSocket 那時用的同一個 client_id。runXiaozhiActivationFlow() 之前
    // 每次都用 java.util.UUID.randomUUID() 生成一個新 clientId 傳給
    // XiazhiOtaClient 建構, 但沒存下來給之後的 vision request 讀 - 這個 field 就是
    // 用來補這個缺口。
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

    // PIR alert cue - "Heaven" 是 Android 內建系統鈴聲標題, 和 STOP_CUE/SHUTTER_CUE
    // 一樣做法 (lazy lookup by title, cache 住那個 content:// Uri)。播放時機見
    // registerAlpha2PirAlertListener() - alpha2_pir_state broadcast
    // (RobotEventReceiver.java) 一到 triggered=true 就立刻播, triggered=false 立刻停
    // (跟 sonar 的 purple LED 一樣, 不等整首歌播完)。
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
    // 現在是不是正在播 - 由 startXiaozhiMicHoldEnforcer()/startMicHoldEnforcer()
    // 用來決定要不要跳過這一輪 speech_SetMIC(true)。背景: 兩條 mic-hold
    // enforcer thread 每 MIC_HOLD_ENFORCER_INTERVAL_MS (2 秒) 就會無條件搶一次
    // mic, 一句超過 2 秒才讀完的句子播到一半就被 speech_SetMIC(true) 打斷
    // (真機 logcat 見過 "ttsGenerationFinished ... success = false" 接著立刻
    // "setWakeState onWake:true") - Android system TTS 不經這個 AIDL 通道,
    // 不會撞到, 所以之前只有 iflytek/nuance 斷斷續續, android 沒事。
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
    // 2026-08 新增: 真實胸口/頭部 MCU 韌體版本查詢 (CHEST_READ_VERSION 51 / 0x33)
    // 透過 IAlpha2SerialPortService.sendCommand(51) 發送，MCU 回覆的完整 wire frame
    // (F8 8F len 01 00 33 payload sum ED) 經 onListenSerialPortRcvData / HeaderRcvData
    // 回調送回。這組 latch/raw/len 供 queryChestFirmwareVersion() 同步阻塞等待使用
    // (HttpServer worker thread，非主 thread)，onReceive 回調一到就 countDown。
    private volatile CountDownLatch chestVersionLatch;
    private volatile byte[] chestVersionRaw;
    private volatile int chestVersionLen;
    private volatile CountDownLatch headerVersionLatch;
    private volatile byte[] headerVersionRaw;
    private volatile int headerVersionLen;
    // 2026-08 新增: 胸口升級狀態 (48/49/50 協議，見 ag_chess/com/ubtechinc/h/a/a$b.java)
    // 單例升級線程，升級中 chestUpgradeInProgress=true，進度 0-100，前端經 EventBus chest_upgrade_progress / chest_upgrade_done 輪詢
    private volatile boolean chestUpgradeInProgress = false;
    private volatile int chestUpgradeProgress = 0;
    private volatile int chestUpgradeTotalPages = 0;
    private volatile int chestUpgradeCurrentPage = 0;
    private volatile String chestUpgradeStatus = "idle";
    private volatile CountDownLatch chestUpgradeLatch;
    private volatile byte chestUpgradeExpectedCmd = 0;
    private volatile int chestUpgradeAckStatus = -1;
    private volatile Thread chestUpgradeThread;
    // 2026-08 新增: listTools() (見 xiaozhiMcpBridge()) 每次被 call 都會存下一份
    // 完整、未過濾的 tool 清單到這裡 - 給 "mcp_tools/list" HTTP endpoint (MCP 設定
    // card 用) 讀, 讓這個 card 可以顯示全部 tool 連同已 disable 的那些。初始為 null
    // (未連過 XiaoZhi/未收過 tools/list 之前), HTTP handler 要處理這個情況 (fallback
    // 直接 call 一次 listTools() 逼它起回一份清單, 因為這個 card 應該在用戶未連接之前
    // 也看得到有哪些 tool 可以 enable/disable)。
    private volatile org.json.JSONArray lastFullMcpToolList = null;

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
                if (!m.xiaozhiClient.isOpen()) {
                    return;
                }
                String text = triggered
                        ? "[系統事件] PIR 人體感應器偵測到有人在附近。"
                        : "[系統事件] PIR 人體感應器偵測不到人在附近了。";
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

    // listAndroidTtsLanguages() 的 legacy fallback (SVOX Pico 沒實作
    // getVoices(), IPC 層直接 throw "NullPointerException: collection ==
    // null" - 不是回空 collection, 是完全沒實作) 用的 blocking 狀態, 見
    // checkTtsDataSyncLegacy()/onActivityResult() javadoc。
    private final Object ttsDataCheckLock = new Object();
    private CountDownLatch ttsDataCheckLatch;
    private volatile ArrayList<String> ttsDataCheckResult;
    private static final int TTS_DATA_CHECK_REQUEST_CODE = 0x7454; // "T T" leetspeak-ish, 只是要一個穩定、未用過的 code

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - matches the web UI slider's
    // default (0-5000 range, default 0).
    private static final int TTS_MOUTH_LED_SPEED = 0;

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
        m.applyObstacleIndicator(triggered);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        installCrashRestartHandler();

        registerDynamicReceiver();
        registerBatteryReceiver();
        registerWifiLedReceiver();
        registerGestureController();
        registerConnectivityReceiver();
        // 讀返「自動跟網絡切換」偏好 (預設開) - speech_ready 之後會即刻按目前
        // 網路狀態套用一次, 開機時如果已經離線的話也會自動進入離線文法模式。
        offlineGrammarAutoSwitch = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_OFFLINE_AUTO, true);
        initRobot();
        xiaozhiClient = new XiaozhiClient(getXiaozhiDeviceId());
        // 見 xiaozhiTtsEngine field 的 javadoc - 讀取上次選定的 TTS 引擎, 如果沒有存過
        // 就用預設值 "xiaozhi" (原本行為, 不靜音)。
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
        // 是 AIDL call (經由 robot.waitHeaderReady() 等待 header serial ready), 不能像
        // 上面 ALPHA_SET_CHARGE_PLAY 那樣直接在 postDelayed 的 UI thread 上呼叫 (會
        // block UI thread), 所以這裡用獨立的 background thread 執行, 時機跟上面
        // charge-play 那個 3s delay 一致的理由 (等 alpha2services 剛啟動時有時間
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
        // string 在這個韌體版本已經找不到任何 sendBroadcast 出處, 實際上是死
        // code。依然保留 filter + RobotEventReceiver 那個 case, 純粹做向後
        // 相容 (以防其他韌體/舊機用到這個 action), 但這台機器不會再觸發。
        filter.addAction("com.ubtechinc.key");
        filter.addAction("com.ubtechinc.services.SPEECH_DIRECTION");
        filter.addAction("com.ubtechinc.robot.tts_hint_wakeup");
        filter.addAction("come.ubt.alpha2.gesture");
        filter.addAction("com.ubtechinc.robot_uuid.info");
        filter.addAction(StaticValue.ALPHA_QR_CODE);
        filter.addAction(StaticValue.ALPHA_WIFI_RESULT);
        filter.addAction(StaticValue.ALPHA_BT_CONNECTION);
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
        filter.addAction(StaticValue.CHEST_ACTION);
        // 2026-08 新增: ⚠️ 未經真機驗證 (見 RobotEventReceiver 這個 case 的
        // comment) - 反編譯官方 alpha2services 3.0.0.2 APK 反推出來的 PIR 通知
        // broadcast, 只有在 SecurityCameraUtil 監控開關開啟的時候才會發出。
        filter.addAction("com.ubtech.securityCamera.pirStatus");
        // 官方 alpha2demo.apk (firmware 1.1.1.14) 反編譯確認: sonar 讀數是經由這個
        // 獨立 broadcast 送出, extra 已經是 parse 好的 int, 不需要自己再解 raw
        // wire frame。見 StaticValue.SONAR_DISTANCE_ACTION 的 comment。
        filter.addAction(StaticValue.SONAR_DISTANCE_ACTION);
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
                padMinusHeld = true;
                padLedUpdate();
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                padMinusHeld = false;
                stopVolumeRepeat();
                padLedUpdate();
                break;
            case 0x5c: // "+" pressed: start repeating volume-up
                padPlusHeld = true;
                padLedUpdate();
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                padPlusHeld = false;
                stopVolumeRepeat();
                padLedUpdate();
                break;
            case 0x5e: // both pressed (raw gesture code 94, decimal) - 全部停止:
                       // 用戶要求將總停鍵的效果搬到這顆實體鍵上, 之前這裡只有
                       // action_StopAction(), 現在跟小智面板那顆「⏹ 全部停止」
                       // 按鈕 (xiaozhiStopAll(), 見 app-xiaozhi.js) 看齊, 一次
                       // 停止動作/小智說話/本地音樂/電台這四樣東西。
                padMinusHeld = true;
                padPlusHeld = true;
                padLedUpdate();
                stopVolumeRepeat(); // in case one pad was already held down
                playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                if (robot != null) {
                    robot.action_StopAction();
                    // 見 "action/stop" endpoint 那段 comment - 停止之後補一個
                    // 「蹲下站起」做回位, 和 HTTP API/self.robot.stop_action 那邊的
                    // 行為保持一致 - 之前這裡漏了這一步。
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
                padMinusHeld = false;
                padPlusHeld = false;
                padLedUpdate();
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }

    /**
     * 2026-08-25: 按 +/- pad 時點亮對應的指示燈 (headboard v1.1, alpha2services
     * 不會自動點亮)。單發 ledSetOn 偶爾會靜悄悄地失敗, 所以用「worker loop 快速連發」:
     * 按住期間每 PAD_LED_INTERVAL_MS 重發一次目前的組合 (累加式, 兩顆一起按兩顆都會亮),
     * 放開之後連發 PAD_LED_OFF_RETRIES 次 ledSetOFF 確保能熄滅。單線程 worker,
     * 如果已經在執行就不會重複啟動第二條。
     */
    private void padLedUpdate() {
        if (padLedWorkerRunning) {
            return;
        }
        padLedWorkerRunning = true;
        padLedExecutor.execute(() -> {
            try {
                while (padMinusHeld || padPlusHeld) {
                    assertPadLedsComboBurst();
                    Thread.sleep(PAD_LED_INTERVAL_MS);
                }
                for (int i = 0; i < PAD_LED_OFF_RETRIES; i++) {
                    assertPadLedsOffBurst();
                    Thread.sleep(PAD_LED_INTERVAL_MS);
                    if (!padMinusHeld && !padPlusHeld) {
                        continue;
                    }
                    break; // released again mid-shutdown - hand control back to the loop
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                padLedWorkerRunning = false;
            }
        });
    }

    /** Retry count for one burst of open() attempts on /dev/led_eye. */
    private static final int PAD_LED_OPEN_ATTEMPTS = 10;
    /** Gap between open() attempts inside one burst (ms). */
    private static final long PAD_LED_RETRY_GAP_MS = 40;

    /**
     * One burst: keep trying LedControl.open() until the device actually opens
     * (alpha2services' fake-off loop opens/closes it every ~0.8-2s, so our open()
     * intermittently loses the race), then assert the held combo and close.
     * Returns true if a session ran; false if every attempt failed to open.
     */
    private boolean assertPadLedsComboBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "pad LED open() threw", t);
            }
            if (openOk) {
                try {
                    if (padMinusHeld) {
                        LedControl.ledSetOn(PAD_LED_INDEX_MINUS);
                    }
                    if (padPlusHeld) {
                        LedControl.ledSetOn(PAD_LED_INDEX_PLUS);
                    }
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (attempt > 1) {
                    Log.d(TAG, "pad LED device opened on attempt " + attempt);
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "pad LED open() failed " + PAD_LED_OPEN_ATTEMPTS
                + "x in a row (device busy?)");
        return false;
    }

    /**
     * Same burst pattern but asserting ledSetOFF() instead of the held combo -
     * used after release so the pads go dark even if we have to wait out a race.
     */
    private boolean assertPadLedsOffBurst() {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "pad LED open() threw (off)", t);
            }
            if (openOk) {
                try {
                    LedControl.ledSetOFF();
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "pad LED off: open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
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

    // 2026-08 新增 (修 bug): 之前 playRingtoneUri() 每次都開一個全新、完全沒有留下
    // reference 的 MediaPlayer, fire-and-forget, 播完/出錯後自己 release —— 這個
    // 做法有兩個問題: (1) 使用者在鈴聲還沒播完之前多次按下「播放」(或者 Blockly
    // 的「範例 5」多次執行), 就會有多個 MediaPlayer 同時各自播放, 聲音疊在
    // 一起, 聽起來像是「停不下來一直響」; (2) 完全沒有任何方法可以從外部 (前端「停止播放」
    // 按鈕) 中斷它, 一定要等整首歌/鈴聲自然播完。修法: 用這個 field 記住「目前正在播放
    // 的那個」MediaPlayer, 每次開新的之前先停掉舊的, 並且加入
    // audio/ringtones/stop 這個 endpoint 讓前端隨時可以中斷。
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

    // 2026-08 新增: 本地音樂播放 (自訂放在 /mnt/internal_sd/music/ 的音樂檔, 不是
    // RingtoneManager 那些系統鈴聲) - 沿用 currentRingtonePlayer 完全相同的 pattern
    // (獨立一個 field, 不共用 currentRingtonePlayer, 因為兩者應該可以互不影響地
    // 各自停止/播放, 例如播放音樂期間都可以獨立播放一個系統提示音), 同樣用
    // STREAM_MUSIC + prepareAsync() + 播完自動 release()。
    private static final java.io.File LOCAL_MUSIC_DIR = new java.io.File("/mnt/internal_sd/music");
    private static final java.util.Set<String> LOCAL_MUSIC_EXTENSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("mp3", "wav", "ogg", "m4a", "flac"));

    private android.media.MediaPlayer currentMusicPlayer;

    /** 目前正在播放 (或正在 prepare) 的本地音樂檔名 (含副檔名), null = 沒有 -
     *  純粹提供給 audio/local_music/status 這個新 endpoint 顯示用, 不影響播放邏輯
     *  本身。和 currentRadioStationName 一樣的想法 - 播放狀態本身只要看
     *  currentMusicPlayer 就夠了, 這個 field 只是為了讓 UI 不用自己另外記住選了
     *  哪個檔名。*/
    private volatile String currentMusicTrackName;

    /** 播放中的本地音樂用的 equalizer, 綁定 currentMusicPlayer 的 audio session -
     *  跟隨 currentMusicPlayer 的生命週期, 換歌/停歌時都要即時 release() 這個
     *  (見 stopLocalMusicPlaybackLocked()), 不可以留著跨 session 使用, 因為
     *  Equalizer 綁定的 audio session id 一旦 MediaPlayer release() 之後就不再
     *  對應任何東西, 之後的 setEnabled()/usePreset() call 會拋出
     *  IllegalStateException。 */
    private android.media.audiofx.Equalizer musicEqualizer;

    /** 用戶上次選擇的 equalizer preset index (由 SharedPreferences 讀出來, 開機/換歌
     *  時都沿用這個) - -1 = 沒選過/用「無」(flat, 不做任何調整)。*/
    private int musicEqPresetIndex = -1;

    // -- Audio Spectrum (2026-08 v2 新增) --------------------------------------
    // 用 android.media.audiofx.Visualizer 綁定 currentMusicPlayer 的 audio session
    // (和 musicEqualizer 同一條 session), 開啟 FFT 擷取, 將取得的頻譜壓縮成
    // MUSIC_SPECTRUM_BANDS 條 band, 提供給 audio/local_music/spectrum endpoint 輪詢,
    // 前端 canvas 畫 bar。生命週期完全跟隨 MediaPlayer: playLocalMusicFile() prepare
    // 時建立, stop/completion/error 時 release。
    private static final int MUSIC_SPECTRUM_BANDS = 24;
    private android.media.audiofx.Visualizer musicVisualizer;
    /** 最近一次 FFT 算出來的頻譜 (0-255 x MUSIC_SPECTRUM_BANDS 條)。volatile 就夠 -
     *  每個 element 獨立讀寫, 前端拿到稍微過時的一幀完全無所謂。 */
    private final int[] musicSpectrumBands = new int[MUSIC_SPECTRUM_BANDS];
    /** FFT bin -> band 的對照表, 第一次收到 FFT 數據時才建立 (需要知道 samplingRate)。 */
    private int[] musicSpectrumBinMap = null;

    /** FFT raw bytes (re0,im0,re1,im1,... 交錯排列) -> MUSIC_SPECTRUM_BANDS 條
     *  magnitude, 用 log 頻率分佈 (低頻窄高頻闊, 貼近聽感) + 輕微增益補償高頻
     *  (音樂能量天生集中在低頻, 不補償的話只有前幾條會動)。*/
    private void updateMusicSpectrumFromFft(byte[] fft, int samplingRate) {
        if (fft == null || fft.length < 4) return;
        if (musicSpectrumBinMap == null) {
            buildMusicSpectrumBinMap(samplingRate, fft.length / 2);
            if (musicSpectrumBinMap == null) return;
        }
        int bins = fft.length / 2;
        for (int b = 0; b < MUSIC_SPECTRUM_BANDS; b++) {
            int from = musicSpectrumBinMap[b];
            int to = musicSpectrumBinMap[b + 1];
            if (to <= from) { to = from + 1; }
            double peak = 0;
            for (int i = from; i < to && i < bins; i++) {
                double re = fft[2 * i];
                double im = fft[2 * i + 1];
                double mag = Math.sqrt(re * re + im * im);
                if (mag > peak) peak = mag;
            }
            // 高頻補償: 第 b 條 band 乘 (1 + b/BANDS*1.5); clamp 0-255。
            double scaled = peak * (1.0 + 1.5 * b / MUSIC_SPECTRUM_BANDS) * 0.6;
            int v = (int) Math.min(255, scaled);
            musicSpectrumBands[b] = v;
        }
    }

    /** 用 log 刻度起「band index -> FFT bin 範圍」對照表, 範圍大約 40Hz - 12kHz。 */
    private void buildMusicSpectrumBinMap(int samplingRate, int binCount) {
        if (samplingRate <= 0 || binCount <= 0) return;
        double minFreq = 40.0;
        double maxFreq = Math.min(12000.0, samplingRate / 2.0);
        musicSpectrumBinMap = new int[MUSIC_SPECTRUM_BANDS + 1];
        for (int b = 0; b <= MUSIC_SPECTRUM_BANDS; b++) {
            double frac = Math.pow((double) b / MUSIC_SPECTRUM_BANDS, 2.0); // 近似 log 分佈
            double freq = minFreq * Math.pow(maxFreq / minFreq, frac);
            int bin = (int) Math.round(freq / samplingRate * binCount * 2.0);
            musicSpectrumBinMap[b] = Math.max(0, Math.min(binCount - 1, bin));
        }
        // 保證單調遞增, 避免某些 band 沒有 bin 可用。
        for (int b = 1; b <= MUSIC_SPECTRUM_BANDS; b++) {
            if (musicSpectrumBinMap[b] <= musicSpectrumBinMap[b - 1]) {
                musicSpectrumBinMap[b] = musicSpectrumBinMap[b - 1] + 1;
            }
        }
    }

    private void setupMusicVisualizerLocked(android.media.MediaPlayer mp) {
        releaseMusicVisualizerLocked();
        try {
            android.media.audiofx.Visualizer v =
                    new android.media.audiofx.Visualizer(mp.getAudioSessionId());
            int[] range = android.media.audiofx.Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range != null ? range[1] : 1024);
            v.setDataCaptureListener(
                    new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(
                                android.media.audiofx.Visualizer visualizer,
                                byte[] waveform, int samplingRate) {
                            // 不需要 waveform, 只要 FFT。
                        }

                        @Override
                        public void onFftDataCapture(
                                android.media.audiofx.Visualizer visualizer,
                                byte[] fft, int samplingRate) {
                            updateMusicSpectrumFromFft(fft, samplingRate);
                        }
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                    false /* waveform */, true /* fft */);
            v.setEnabled(true);
            musicVisualizer = v;
        } catch (Throwable t) {
            // Visualizer 這個 effect 一樣不保證每台機器都有 - 沒有就沒有 spectrum 顯示,
            // 不要因此拖累整首歌播不了。
            Log.w(TAG, "Visualizer unavailable on this device", t);
            musicVisualizer = null;
        }
    }

    private void releaseMusicVisualizerLocked() {
        if (musicVisualizer != null) {
            try {
                musicVisualizer.setEnabled(false);
                musicVisualizer.release();
            } catch (Exception ignored) {
            }
            musicVisualizer = null;
        }
        java.util.Arrays.fill(musicSpectrumBands, 0);
    }

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

    /** 2026-08 更新 (用戶要求「本地播歌時, random 動作應該要不停動, 直到整首歌播完」):
     *  之前只有在 onPrepared (真正開始播放的那一刻) 動一次就算, 現在改成用這個固定
     *  間隔不斷重複觸發 triggerRandomFillerAction(), 直到整首歌播完/被叫停為止。
     *  用固定間隔 (而不是「等動作做完再動下一個」) 的原因是: AIDL 沒有提供任何
     *  查詢「一個 action 什麼時候做完」的方法 (見 AIDL_REFERENCE.md, action_PlayActionName
     *  只是 fire-and-forget), 沒辦法準確知道上一個動作多久才做完, 所以選一個
     *  保守的固定 cadence, 對絕大部分動作長度來說都足夠做完那個動作再開始
     *  下一個, 不會不斷打斷上一個尚未做完的動作。 */
    private static final long MUSIC_FILLER_ACTION_INTERVAL_MS = 3500;

    /** 目前正在執行的「播歌隨機動作」循環 Runnable, null = 沒有在執行 - 用來讓
     *  stopLocalMusicPlaybackLocked() 用 mainHandler.removeCallbacks() 準確停止
     *  這個循環, 不用靠猜。 */
    private Runnable musicFillerActionLoop;

    /** 啟動「播歌期間不斷動隨機動作」的循環 - 每 MUSIC_FILLER_ACTION_INTERVAL_MS
     *  觸發一次 triggerRandomFillerAction(), 再重新 schedule 自己, 直到
     *  boundPlayer 不再是 currentMusicPlayer (也就是整首歌已經播完/被叫停/被第二首歌
     *  取代了) 才停止。用 mainHandler (Looper.getMainLooper()) 排程, 和
     *  reassertHeadEyeLed() 一致的做法 - 這個 method 本身只是 postDelayed, 沒有做
     *  blocking call, 不用擔心阻塞 main thread; 真正的動作播放
     *  (在 triggerRandomFillerAction() 裡面) 一直都是開獨立 thread 做 AIDL call。 */
    /** 播歌隨機動作開關 - 讀取 SharedPreferences, 預設 true (保持之前還沒有開關按鈕之前
     *  的行為: 一直都會動)。讓 audio/local_music/filler_action/get、
     *  startMusicFillerActionLoop()、playLocalMusicFile() 一起用同一個讀法,
     *  用戶隨時可以在 UI 上切換, 不用讓正在播放的歌也要重新播放才生效 - 下一個
     *  loop tick (或下一次播歌) 就會反映新設定。*/
    private boolean isMusicFillerActionEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_MUSIC_FILLER_ACTION_ENABLED, true);
    }

    private void startMusicFillerActionLoop(final android.media.MediaPlayer boundPlayer) {
        Runnable loop = new Runnable() {
            @Override
            public void run() {
                synchronized (MainActivity.this) {
                    if (currentMusicPlayer != boundPlayer) {
                        // 整首歌已經播完/被叫停/被第二首歌取代了 - 這個循環
                        // 對應的播放已經不再有效, 不再重新 schedule, 自然結束。
                        return;
                    }
                }
                // 2026-08 新增: 開關 - 用戶隨時可以在音樂 tab 切換「random 動作」
                // 這個開關, 每次 tick 都即時讀取最新值, 不用等下一次播歌才生效。
                // 關閉時只是跳過「動一下」這個動作, loop 本身仍然繼續 schedule
                // 下去 (讓用戶隨時開啟都能立即恢復, 不用 stop/replay 那首歌)。
                if (isMusicFillerActionEnabled()) {
                    triggerRandomFillerAction();
                }
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

    /** 停止 startMusicFillerActionLoop() 開始的循環 (如果有的話) - 供
     *  stopLocalMusicPlaybackLocked() 呼叫, 也供 onCompletion/onError 這兩個
     *  listener 呼叫 (整首歌自然播完/播壞都應該立即停止動作, 不用等到下一次
     *  loop tick 才發現 currentMusicPlayer 已經不對才罷手)。 */
    private void stopMusicFillerActionLoop() {
        if (musicFillerActionLoop != null) {
            mainHandler.removeCallbacks(musicFillerActionLoop);
            musicFillerActionLoop = null;
        }
        stopSharedFillerLoop();
    }

    // 共用隨機動作循環 — 本地與電台共用同一開關與同一節奏，兩者任一在播即觸發
    private Runnable sharedFillerLoop;
    private synchronized void startSharedFillerLoop() {
        if (sharedFillerLoop != null) return;
        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                boolean hasActivePlayer = false;
                synchronized (MainActivity.this) {
                    if (currentMusicPlayer != null || currentRadioPlayer != null) hasActivePlayer = true;
                }
                if (hasActivePlayer && isMusicFillerActionEnabled()) {
                    triggerRandomFillerAction();
                }
                synchronized (MainActivity.this) {
                    // 用 this 而非 loop 變數，避免「variable loop might not have been initialized」編譯錯誤
                    if (sharedFillerLoop == this && hasActivePlayer) {
                        mainHandler.postDelayed(this, MUSIC_FILLER_ACTION_INTERVAL_MS);
                    } else {
                        sharedFillerLoop = null;
                    }
                }
            }
        };
        sharedFillerLoop = loop;
        mainHandler.postDelayed(loop, MUSIC_FILLER_ACTION_INTERVAL_MS);
    }
    private synchronized void stopSharedFillerLoop() {
        if (sharedFillerLoop != null) {
            mainHandler.removeCallbacks(sharedFillerLoop);
            sharedFillerLoop = null;
        }
    }
    private synchronized void stopSharedFillerLoopIfIdle() {
        if (currentMusicPlayer == null && currentRadioPlayer == null) {
            stopSharedFillerLoop();
        }
    }

    /** Plays a local music file - same STREAM_MUSIC/prepareAsync()/auto-release shape as
     *  playRingtoneUri(), kept as a separate method (rather than generalising both into
     *  one) so a future change to one playback path can't accidentally affect the
     *  other. Stops whatever local music track was previously playing first.
     *
     *  2026-08 更新: 在真正開始播放的那一刻 (onPreparedListener 裡面, 而不是
     *  prepareAsync() 的 request 一發出就做) 順便啟動
     *  startMusicFillerActionLoop() - 用戶要求「播歌時要不停動, 直到整首歌播
     *  完」, 見那個 method 的 javadoc。刻意放在 onPrepared 裡面 (真正 start()
     *  之後) 而不是這個 method 一開頭就做: 如果檔案根本播不了 (loss/corrupt,
     *  prepareAsync 觸發 onError), 不應該仍然先動了那個動作, 「動作」應該與
     *  「真的有歌聲」同步, 而不是與「這個 method 被呼叫了」同步。 */
    private synchronized void playLocalMusicFile(java.io.File file) {
        stopLocalMusicPlaybackLocked();
        // 共用播放器：播本地時停掉電台，避免兩路同時出聲
        stopRadioPlaybackLocked();
        if (file == null || !file.exists()) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                mp.start();
                setupMusicEqualizerLocked(mp);
                setupMusicVisualizerLocked(mp);
                startMusicFillerActionLoop(mp);
                startSharedFillerLoop();
            });
            player.setOnCompletionListener(mp -> {
                synchronized (MainActivity.this) {
                    stopMusicFillerActionLoop();
                    stopSharedFillerLoopIfIdle();
                    // 若電台仍在播，保留共用 EQ/頻譜給電台
                    if (currentRadioPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                        currentMusicTrackName = null;
                    }
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (MainActivity.this) {
                    stopMusicFillerActionLoop();
                    stopSharedFillerLoopIfIdle();
                    if (currentRadioPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                        currentMusicTrackName = null;
                    }
                }
                return true;
            });
            currentMusicPlayer = player;
            currentMusicTrackName = file.getName();
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to play local music file " + file, e);
        }
    }

    /** 幫 mp (剛 prepared/start() 的那個 currentMusicPlayer) 建立一個新的
     *  Equalizer, 綁定它的 audio session, 再套用用戶上次選擇的 preset (由
     *  SharedPreferences 讀取, 沒選過就維持 flat/不處理)。每首新歌都要重新建立
     *  一個新的 Equalizer instance - Equalizer 綁死在建立當下的 audio session id,
     *  不可以跨 MediaPlayer 重複使用。這個 method 假設 caller 已經在
     *  synchronized(MainActivity.this) 區塊裡面 (onPrepared callback 本身沒有
     *  持有這個 lock, 所以用 "Locked" 命名提醒: 這個 method 期望自己執行當下沒有第二條
     *  thread 同時在修改 currentMusicPlayer/musicEqualizer)。*/
    private void setupMusicEqualizerLocked(android.media.MediaPlayer mp) {
        try {
            android.media.audiofx.Equalizer eq = new android.media.audiofx.Equalizer(0, mp.getAudioSessionId());
            eq.setEnabled(true);
            musicEqualizer = eq;
            int savedPreset = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getInt(PREF_MUSIC_EQ_PRESET, -1);
            if (savedPreset >= 0 && savedPreset < eq.getNumberOfPresets()) {
                try {
                    eq.usePreset((short) savedPreset);
                    musicEqPresetIndex = savedPreset;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply saved EQ preset " + savedPreset, e);
                }
            }
        } catch (Exception e) {
            // Equalizer 這個 audio effect 不保證每台機器都有 (視乎廠商有沒有實作對應
            // 的 effect engine) - 建不起來就當作沒有這個功能, 不應該因此拖累整首歌播不了。
            Log.w(TAG, "Equalizer unavailable on this device", e);
            musicEqualizer = null;
        }
    }

    private void releaseMusicEqualizerLocked() {
        if (musicEqualizer != null) {
            try {
                musicEqualizer.release();
            } catch (Exception ignored) {
            }
            musicEqualizer = null;
        }
    }

    /** 2026-08 新增: 停止「小智說話/回覆」這一種播放 - 抽出來做共用 method, 供
     *  handleApi() 的 "speech/stop" HTTP endpoint 和 onGestureCode() 的 0x5e
     *  (雙鍵齊按, 也就是「94 鍵」) 一起使用。停止機身本地 TTS (Nuance/iflytek,
     *  robot.speech_StopTTS())、Android TTS、和小智語音回覆的音訊
     *  (XiaozhiAudioController, WebSocket 收 Opus frame -> 解碼 -> AudioTrack,
     *  詳見 XiaozhiAudioController.onIncomingOpusFrame()/stopPlayback() 的
     *  javadoc) - 這三條是完全獨立的播放管道, 停一條不會連帶讓另一條也停, 之前
     *  用戶回報「停不了小智說話」就是因為漏了 XiaozhiAudioController 這條路。 */
    private void stopAllSpeechPlayback() {
        if (robot != null) {
            robot.speech_StopTTS();
        }
        lastSpeechStopAtMs = System.currentTimeMillis();
        robotTtsSpeaking = false; // 見 robotTtsSpeaking field javadoc - 手動/總停鍵停止時都要立即放行 mic enforcer
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
        stopMusicFillerActionLoop();
        stopSharedFillerLoopIfIdle();
        // 共用 EQ/頻譜：若電台仍在播，保留給電台
        if (currentRadioPlayer == null) {
            releaseMusicEqualizerLocked();
            releaseMusicVisualizerLocked();
        }
        if (currentMusicPlayer != null) {
            try {
                currentMusicPlayer.stop();
            } catch (Exception e) {
                // 見 stopRingtonePlaybackLocked() 的 comment - prepareAsync() 中途
                // race 可能引發 IllegalStateException, release() 一樣照做, 吞掉就好。
            }
            try {
                currentMusicPlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentMusicPlayer = null;
            currentMusicTrackName = null;
        }
    }

    // 2026-08 新增: FM/網路電台播放 (經由 Radio Browser API, radio-browser.info,
    // 動態搜尋全世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 的
    // javadoc) - 獨立一個 field/一套 method, 不和 currentMusicPlayer (本地檔案)
    // 共用, 理由和 currentMusicPlayer 不和 currentRingtonePlayer 共用一樣: 三種播放
    // 應該可以互不影響地各自播放/停止 (例如轉台時不應該連帶讓本地音樂也要停)。和
    // 本地音樂/鈴聲最大的差別: 這裡的 data source 是網路 URL, prepareAsync() 依賴
    // 網路連線, 比本地檔案更容易因為網路問題觸發 onError - 這是
    // playRadioStream() 特意保留 onErrorListener 做事 (清除 currentRadioPlayer)
    // 的原因, 讓下一次「轉台」不會撞到一個已經失效但沒清掉的 reference。
    private android.media.MediaPlayer currentRadioPlayer;

    /** 目前正在播放的電台 Radio Browser stationuuid, null = 沒有播放 - 純粹提供給
     *  audio/radio/status 這個 HTTP endpoint 顯示用, 不影響播放邏輯本身。 */
    private volatile String currentRadioStationId;

    /** 目前正在播放的電台名 (Radio Browser 的 "name") - 和 currentRadioStationId 一起
     *  存, 純粹提供給 audio/radio/status 直接顯示用, 不用為了取得名稱再打一次 API。 */
    private volatile String currentRadioStationName;

    /** 播放一個電台的直播串流 - 和 playLocalMusicFile()/playRingtoneUri() 一樣的
     *  STREAM_MUSIC/prepareAsync()/auto-release 形狀, 但這裡 setDataSource() 收的
     *  是網路 URL (Radio Browser struct 的 "url_resolved" - 官方文件建議使用這個
     *  而不是 "url": url_resolved 已經解析過 playlist/HTTP redirect, 不需要這台機器自己
     *  再會解析 .pls/.m3u, 對一個沒有 yt-dlp 這類工具的 Android 5.1 App 來說很關鍵),
     *  所以 prepareAsync() 要靠網路連線才能讓串流真正開始 buffer - 這個 method
     *  只負責觸發, 不 block caller 等網路, 由 onPreparedListener 在真正取得資料、
     *  可以開始播放的那一刻才 start()。播歌時順便動一下的 triggerRandomFillerAction()
     *  (見 playLocalMusicFile() javadoc) 這裡沒有加 - 電台可以連續播好幾個小時, 不像
     *  一首歌那麼短, 不應該只因為「剛轉了台」就動一次, 和「播放時要看起來
     *  生動」這個原意不搭。 */
    private synchronized void playRadioStream(org.json.JSONObject station) {
        stopRadioPlaybackLocked();
        // 共用播放器：播電台時停掉本地音樂，避免兩路同時出聲
        stopLocalMusicPlaybackLocked();
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
        final String resolvedUrl = url;
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                mp.start();
                // 共用 EQ/頻譜/隨機動作 — 與本地音樂同一套
                setupMusicEqualizerLocked(mp);
                setupMusicVisualizerLocked(mp);
                startSharedFillerLoop();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (MainActivity.this) {
                    mp.release();
                    if (currentRadioPlayer == mp) {
                        currentRadioPlayer = null;
                        currentRadioStationId = null;
                        currentRadioStationName = null;
                    }
                    // 電台出錯時若本地也沒在播，才釋放共用資源
                    if (currentMusicPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    stopSharedFillerLoopIfIdle();
                }
                Log.w(TAG, "Radio stream playback error: what=" + what + " extra=" + extra
                        + " url=" + resolvedUrl);
                return true;
            });
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
            }
            try {
                currentRadioPlayer.release();
            } catch (Exception e) {
            }
            currentRadioPlayer = null;
        }
        currentRadioStationId = null;
        currentRadioStationName = null;
        // 共用 EQ/頻譜/隨機動作：若本地仍在播，保留
        if (currentMusicPlayer == null) {
            releaseMusicEqualizerLocked();
            releaseMusicVisualizerLocked();
        }
        stopSharedFillerLoopIfIdle();
    }

    // 2026-08 更新 (修 bug): findRingtoneByTitle() 之前每次呼叫都 `new
    // RingtoneManager(this)`, 用完立刻拋棄那個 object, 但 Android 官方文件明確說明
    // RingtoneManager.getCursor() 每次取得的是*同一個*底層 cursor, 不應該由
    // 使用者自己 close() —— 它的生命週期本身是跟著 RingtoneManager instance
    // 走的, 如果沒有用 RingtoneManager(Activity) 這個會自動與 activity 生命週期綁定
    // 的 constructor (這裡用的是 RingtoneManager(Context), 沒有自動綁定), 就要自己
    // 保住這個 RingtoneManager instance, 不要用完即丟, 否則底層的 cursor 沒人釋放,
    // 一直洩漏 (實測 logcat 看到 CursorWindowAllocationException, # Open Cursors
    // 累積到 991 個, 就是這個 bug 導致的)。修法: 用 rmType (TYPE_RINGTONE /
    // TYPE_NOTIFICATION) 做 key, 快取住那兩個 RingtoneManager instance,
    // 整個 app 生命週期裡只 new 一次, 之後所有呼叫都取快取的那個來重用
    // (RingtoneManager.getCursor() 內部自己會 requery(), 不需要我們手動 refresh)。
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

    /**
     * 2026-08-25: WiFi 狀態 → wifi 指示燈。連上轉藍 (ledSetOn(12)), 斷開轉返紅
     * (ledSetOn(13))。切換前先 ledSetOFF() 清場 (ledSetOn 是累加式)。註冊當下
     * 立即檢查一次現狀, 處理「app 開啟之前已經連上/斷線」的情況。
     */
    private void registerWifiLedReceiver() {
        wifiLedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                android.net.NetworkInfo info =
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info == null || info.getType() != android.net.ConnectivityManager.TYPE_WIFI) {
                    return;
                }
                applyWifiLed(info.isConnected());
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(wifiLedReceiver, filter);

        // App 啟動時按當前狀態即刻設好。
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        if (cm != null) {
            android.net.NetworkInfo ni = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI);
            connected = ni != null && ni.isConnected();
        }
        final boolean connectedNow = connected;
        padLedExecutor.execute(() -> applyWifiLedInternal(connectedNow));
    }

    /** WiFi 燈狀態切換入口 - 排給 pad LED 單線程 executor 執行。 */
    private void applyWifiLed(boolean connected) {
        padLedExecutor.execute(() -> applyWifiLedInternal(connected));
    }

    /**
     * 實際切換: 先 ledSetOFF() 清走舊色, 等 100ms, 再點目標顏色。兩步都係 burst
     * 重試式, 同 alpha2services 搭 /dev/led_eye 輸贏都最終會成。
     */
    private void applyWifiLedInternal(boolean connected) {
        try {
            assertPadLedsOffBurst();
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        assertSingleLedBurst(connected ? WIFI_LED_INDEX_BLUE : WIFI_LED_INDEX_RED);
    }

    /** One burst: retry open()/dev/led_eye until it opens, light a single LED index. */
    private boolean assertSingleLedBurst(int index) {
        for (int attempt = 1; attempt <= PAD_LED_OPEN_ATTEMPTS; attempt++) {
            boolean openOk = false;
            try {
                openOk = LedControl.open();
            } catch (Throwable t) {
                Log.w(TAG, "wifi LED open() threw", t);
            }
            if (openOk) {
                try {
                    LedControl.ledSetOn(index);
                } finally {
                    try {
                        LedControl.close();
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            try {
                Thread.sleep(PAD_LED_RETRY_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "wifi LED open() failed " + PAD_LED_OPEN_ATTEMPTS + "x in a row");
        return false;
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
                // 捕捉頭部 MCU 版本回覆 (HEADER_READ_VERSION 51)，供 queryHeaderFirmwareVersion() 同步等待
                if (headerVersionLatch != null && headerVersionLatch.getCount() > 0) {
                    boolean isVer = isVersionFrame(bytes, len, StaticValue.HEADER_READ_VERSION);
                    boolean isFallback = false;
                    if (!isVer) {
                        boolean isSonarAck = (len == 2 && bytes[0] == 4 && bytes[1] == 0);
                        if (len >= 1 && !isSonarAck) isFallback = true;
                    }
                    if (isVer || isFallback) {
                        headerVersionRaw = java.util.Arrays.copyOf(bytes, Math.min(len, bytes.length));
                        headerVersionLen = len;
                        headerVersionLatch.countDown();
                    }
                }
            }

            @Override
            public void onListenSerialPortRcvData(byte[] bytes, int len) {
                String hex = toHex(bytes, len);
                EventBus.get().publish("chest_rcv", "{\"hex\":\"" + hex + "\"}");
                handleChestObstacleFrame(bytes, len);
                // 優先處理升級 ACK (48/49/50) — h.a.a.java:96
                if (chestUpgradeLatch != null && chestUpgradeLatch.getCount() > 0 && len >= 1) {
                    byte cmd = bytes[0];
                    if (cmd == chestUpgradeExpectedCmd) {
                        // 49 需檢查第二字節狀態：0=成功，非0=重試
                        if (cmd == 49) {
                            chestUpgradeAckStatus = (len >= 2 ? (bytes[1] & 0xFF) : 0);
                        } else {
                            chestUpgradeAckStatus = 0;
                        }
                        chestUpgradeLatch.countDown();
                        return;
                    }
                }
                // 捕捉胸口 MCU 版本回覆 (CHEST_READ_VERSION 51)，供 queryChestFirmwareVersion() 同步等待
                if (chestVersionLatch != null && chestVersionLatch.getCount() > 0) {
                    boolean isVer = isVersionFrame(bytes, len, StaticValue.CHEST_READ_VERSION);
                    boolean isFallback = false;
                    if (!isVer) {
                        boolean isSonarAck = (len == 2 && bytes[0] == 4 && bytes[1] == 0);
                        boolean isObstacle = (len >= 2 && bytes[0] == (byte) -127);
                        if (len >= 1 && !isSonarAck && !isObstacle) {
                            isFallback = true;
                        }
                    }
                    if (isVer || isFallback) {
                        chestVersionRaw = java.util.Arrays.copyOf(bytes, Math.min(len, bytes.length));
                        chestVersionLen = len;
                        chestVersionLatch.countDown();
                    }
                }
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
                // 2026-08 新增: 離線文法模式開啟的時候, 這條聽寫路徑要讓路 -
                // mSpeechServiceUtil 和 mAsrServiceUtil 是兩個獨立 binding, 同一句
                // 辨識結果有可能兩邊都收到, 如果這裡照樣 publish asr_result (前端會
                // triggerIflytekSimulate), 加上 grammar_result 那邊又觸發一次, 就
                // 會重複配對/重複 TTS。離線模式下 grammar listener 才是唯一觸發
                // 語意配對的入口, 這裡只 log 不 publish。
                if (offlineGrammarActive) {
                    Log.d(TAG, "onServerCallBack suppressed while offline grammar mode active: " + text);
                    return;
                }
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
                // 「聽寫識別」(dictation, type:0) 不同 - onServerCallBack() 這裡
                // 收到的 text 不是純文字, 而是機身 iFlytek SDK 尚未解析的原始 JSON
                // 字串, 例如 {"text":"你的爸爸是谁啊","rc":4} (rc = 識別結果的
                // confidence/類型代碼, 這裡沒有用到, 只抽取 text field)。之前這個
                // 未解析的 JSON 字串會直接:
                //   1) 塞進 asr_result 的 text field, 導致對話界面 user 氣泡顯示
                //      整句 raw JSON 而不是純文字;
                //   2) 送去 IflytekSemanticMatcher.match(), 因為 match() 有做
                //      q.contains(e.q) 的 fuzzy 包含匹配, 這個 JSON 字串很可能
                //      「剛好」包含問法庫裡某條短問法作為子字串而配對中 (例如
                //      實測見到 {"text":"我煮的不开心","rc":4} 撞中「不开心」),
                //      但正常情況下 (問法沒有在 JSON 字串裡面剛好出現作為子字串)
                //      就什麼都配對不中, 對話界面看不到任何 assistant 回覆。
                // 這裡先抽出真正的 text field (抽不到就當原文處理, 保持與
                // type:0 聽寫路徑一致的 fallback 行為), 才送去下面的
                // asr_result/parseLocalResult/handleIflytekSemanticText。
                //
                // 2026-08 修正: extractGrammarResultText() 只會抽取 iFlytek JSON
                // 格式 {"text":"...","rc":4}，對於 Nuance 的
                // "Local_Result:rule:QA action:QA_CHATTING tag:How do you do"
                // 格式會原樣返回整句 raw string，導致對話界面顯示整句
                // Local_Result 而不只是 tag 後面的辨識文字。這裡加多一層判斷：
                // 如果是 Local_Result 格式，用 fieldBetween() 抽取 tag: 後面的文字。
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
                // 處理所有 5 種輸入方法的語意配對 + TTS + 動作,
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
                // 2026-08 新增: 「自我打斷」由用戶可選的 checkbox 改為恆常開啟 -
                // UI 那個選擇按鈕已經移除 (見 index.html), 這裡在 speech 引擎 ready
                // 的當下主動開啟一次, 不用讓用戶手動選擇。speech/self_interrupt endpoint
                // 保留 (Blockly 積木 alpha_speech_self_interrupt 仲用緊), 淨係
                // UI 主開關拎走。
                robot.speech_setSelfInterrupt(true);
                // 2026-08 新增: speech service 就緒之後即刻套用一次「自動跟網絡
                // 切換」- 開機時如果已經離線, 這裡就會自動進入離線文法模式,
                // 不用等人按鍵或者等下一次網路狀態變化。探測是 blocking call,
                // 放到背景 thread; 同時開啟 30 秒週期的 watchdog probe。
                startOfflineWatchdog();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean online = hasRealInternet();
                        lastProbeOnline = online;
                        applyConnectivityMode(online, "speech_ready");
                    }
                }, "conn-probe-boot").start();
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

    /** TTS 之後要等多久才播動作, 沿用悠聊 RobotActionBusiness.startBusiness() 反編譯
     *  出來的原本時序 (先 TTS, sleep 200ms, 才播動作 - 兩者是分開、非同步的 AIDL
     *  call, 只靠這個 sleep 頂住, 沒有等 TTS 真的播完才動)。用戶已確認沿用悠聊原本
     *  這樣做, 不改成等 TTS 播完才動。 */
    private static final int IFLYTEK_TTS_TO_ACTION_DELAY_MS = 200;

    /** 判斷一句輸入文字是否應該用中文 matcher 處理: 有任何 CJK 統一表意文字 (漢字)
     *  就當中文, 完全沒有就當英文。2026-08 特意選這個做法, 不依靠 speech/set_asr_engine
     *  那個手動語言設定, 因為 iFlytek 引擎本身可能自動偵測用戶說的是什麼語言, 只看辨識
     *  出來的文字內容本身最可靠。中英文夾雜的句子 (例如 "跳個 dance") 會因為有漢字而
     *  當中文 - 這是刻意的簡化, 不追求完美的語言偵測, 對這個用途已經夠準確。 */
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

    /** IflytekSemanticMatcherEn.MatchResult -> IflytekSemanticMatcher.MatchResult 的
     *  薄轉接層。兩個 class 的 MatchResult 結構完全一樣 (question/type/operation/
     *  slot/answer/actionId), 但屬於不同 class 的 nested type, Java 不會自動把它們
     *  當成同一個型別 - 這個 method 純粹做欄位複製, 讓 handleIflytekSemanticText() 的
     *  下半部分 (publish event、TTS/動作執行) 不用為中英文分別多寫一份。 */
    private static IflytekSemanticMatcher.MatchResult toZhResult(IflytekSemanticMatcherEn.MatchResult en) {
        if (en == null) return null;
        return new IflytekSemanticMatcher.MatchResult(
                en.question, en.type, en.operation, en.slot, en.answer, en.actionId);
    }

    /** 將一句文字 (可能是 iFlytek 引擎真正辨識到的, 也可能是 speech/iflytek_simulate
     *  這個 endpoint 用來測試的打字輸入) 對照 1000 條問法配對, 命中就執行悠聊原本的
     *  「先 TTS、再隔 200ms 播動作」流程。找不到就什麼都不做 (不是錯誤 - 用戶說的話不在
     *  那 1000 條裡面是很正常的事, 靜靜地不回應好過亂回一個不相關的回覆), 回傳 null。
     *
     *  中英文用哪個 matcher 由 looksChinese() 判斷 - 有漢字用 IflytekSemanticMatcher
     *  (中文, iflytek_semantic_zh.json), 沒有就用 IflytekSemanticMatcherEn (英文,
     *  iflytek_semantic_en.json)。兩個 class 結構一致、資料獨立, 不會互相影響。
     *
     *  回傳 MatchResult (而不是 void) 是為了讓 speech/iflytek_simulate 這個 endpoint 用來
     *  即時告訴前端「有沒有配對中」, publishEvent=false 那個 overload 不會再經由 EventBus
     *  多 publish 一次 (前端 sendSpeechChatText() 已經即時用 HTTP response 顯示)。
     *
     *  TTS/動作執行本身依然在獨立 thread 上做 AIDL blocking call, 不在呼叫者的
     *  thread (可能是 HTTP worker thread) 上直接做 - 和 triggerRandomFillerAction()
     *  一致的安全做法。 */
    private IflytekSemanticMatcher.MatchResult handleIflytekSemanticText(final String text) {
        return handleIflytekSemanticText(text, true);
    }

    private IflytekSemanticMatcher.MatchResult handleIflytekSemanticText(final String text,
                                                                         final boolean publishEvent) {
        final boolean chinese = looksChinese(text);
        if (chinese) {
            if (iflytekMatcher == null) return null; // onCreate() 尚未執行完 (理論上不會, 保險)
        } else {
            if (iflytekMatcherEn == null) return null;
        }

        final IflytekSemanticMatcher.MatchResult result = chinese
                ? iflytekMatcher.match(text)
                : toZhResult(iflytekMatcherEn.match(text));
        if (result == null) {
            return null; // 找不到對應問法 - 靜靜地不做事, 不算錯誤
        }
        if (publishEvent) {
            EventBus.get().publish("iflytek_match",
                    "{\"question\":\"" + jsonSafe(result.question) + "\","
                            + "\"type\":\"" + jsonSafe(result.type) + "\","
                            + "\"operation\":\"" + jsonSafe(result.operation) + "\","
                            + "\"answer\":\"" + jsonSafe(result.answer) + "\","
                            + "\"actionId\":\"" + jsonSafe(result.actionId) + "\"}");
        }

        // ASR 這邊固定用 iFlytek engine (不再用 Nuance 做 ASR) - iFlytek 一個 engine
        // 就能辨識中文和英文, 用戶已經確認不會再切回 Nuance 做 ASR。TTS 這邊則跟隨
        // 辨識出來的語言選擇對應的 TTS engine 讀出答案: 中文答案用 "zh_cn" (走 iFlytek
        // TTS), 英文答案用 "en_us" (走 Nuance TTS, 這個 project 一貫的做法 - 見
        // "Fixed to Nuance/en_us" 那個 self.robot.speak MCP tool 附近的 comment)。
        // 也就是說 ASR 和 TTS 用的 engine 不是同一個, 這裡純粹是依語言選擇音質較好的
        // TTS engine, 和 ASR engine 無關。
        final String ttsLang = chinese ? "zh_cn" : "en_us";
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (result.answer != null && !result.answer.isEmpty()) {
                    // 2026-08 新增: 之前這裡漏了嘴部 LED - 語音 tab 的對話界面
                    // (iFlytek 語意配對) 說話時嘴部 LED 完全沒有反應, 和其他 TTS
                    // 入口 (本地 speech/tts、小智) 已有的嘴部 LED 同步效果不一致。
                    // 這裡跟隨其他入口 (見 speakActivationCode()/handleApi()
                    // "speech/tts" 那個 case) 一致的 pattern: 開始前點亮, 如果
                    // speech_startTTS 本身立即失敗就自己熄掉 - 成功的話不用
                    // 自己熄, 全域唯一那個 onServerPlayEnd callback (AIDL 初始化
                    // 時 register, 不論哪裡觸發的 TTS 播完都會被呼叫) 會負責熄燈。
                    startMouthLedForTts();
                    UbxErrorCode.API_ERROR_CODE ttsCode =
                            robot.speech_startTTS(ttsLang, result.answer, null);
                    if (!isOk(ttsCode)) {
                        stopMouthLedForTts();
                    }
                }
                if (result.actionId == null) {
                    return; // CHAT 類或部分 FUNCTION 類沒有對應動作, TTS 完就結束
                }
                try {
                    Thread.sleep(IFLYTEK_TTS_TO_ACTION_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String actionId = result.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // 2026-08 新增: 用戶說到分類名 (例如「跳舞」/"Dance for me") 但沒有
                    // 說出具體是哪個動作 - 在 202 動作清單的對應分類 (例如
                    // DANCE_KIDS/YOGA_ANY) 裡面隨機選一個。和下面 "__RANDOM__"
                    // (完全不限分類, 202 個隨便選) 不同, 這是分類限定的隨機。中英文
                    // matcher 共用同一份 action_category_pools.json, 哪個 instance
                    // 呼叫結果都一樣, 只是依 chinese 這個 flag 選擇對應的 instance。
                    actionId = chinese
                            ? iflytekMatcher.resolveCategoryRandomActionId(actionId)
                            : iflytekMatcherEn.resolveCategoryRandomActionId(actionId);
                } else if ("__RANDOM__".equals(actionId)) {
                    // TFBOY 這類 operation 在原廠問法裡沒有固定動作 - 沿用
                    // triggerRandomFillerAction() 已有的隨機動作池 (202 個動作裡
                    // 「隨機短/長」開頭的那批, 專門用來做這種「動一下讓它生動一點」的效果)。
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
    // 2026-08 新增: 純粹用來目視確認 RobotEventReceiver 那個 CHEST_ACTION case 有沒有
    // 真的收到胸口 mute 鍵 (chest cmd = -111) 的 broadcast - 這不是最終功能,
    // 純粹一個「有沒有反應」的測試訊號 (見 RobotEventReceiver 那個 case 的 comment)。
    // 官方 firmware 這顆鍵本身完全沒有連任何 LED, 這裡的紫燈完全是這個專案自己加的,
    // 和 sonar obstacle 用的是同一個 setHeadEyeLedLong(5, 9) helper (5=紫,
    // 9=最光, 見 applyObstacleIndicator() 個 comment)。
    private volatile boolean chestMuteKeyLedOn = false;

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

    // -- 心口 mute 鍵 LED (chest cmd=68) ------------------------------------------
    // 2026-08-25 新增: headboard v1.1 + 舊版 alpha2services 之下按 mute 鍵 MCU 不會
    // 自己點燈, 我們在這裡補上: 按下一下 → toggle 燈 (亮=muted 視覺狀態), 放開不理。
    private static final byte CHEST_MUTE_LED_CMD = 68; // 0x44, 實機掃描確認
    private volatile boolean chestMuteLedOn = false;
    // 2026-08-25 實機 log 發現每次按鍵送出去的全部是 68[00] - 也就是 press 事件重複
    // 觸發導致 toggle 兩次又變回原狀。加 400ms 防抖: 太接近的第二次 press 當作同一次。
    private static final long MUTE_PRESS_DEBOUNCE_MS = 400;
    private final java.util.concurrent.atomic.AtomicLong lastMutePressMs =
            new java.util.concurrent.atomic.AtomicLong(0);

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
        m.toggleChestMuteLed();
    }

    private void toggleChestMuteLed() {
        long now = android.os.SystemClock.elapsedRealtime();
        long last = lastMutePressMs.get();
        if (now - last < MUTE_PRESS_DEBOUNCE_MS) {
            Log.d(TAG, "mute press debounced (gap " + (now - last) + "ms)");
            return;
        }
        lastMutePressMs.set(now);
        // 2026-08 v2: mute 鍵改做「小智開關」- 撳一下連線 (燈着 = 已連接),
        // 再撳一下斷線 (燈熄)。LED 由實際連線事件驅動 (見 runXiaozhiActivationFlow()
        // 個 connected hook / DisconnectListener / activation error hook), 呢度
        // 按下當下的 send 只是即時的視覺反應, 之後會被真實狀態 hook 校正。
        final boolean wasOpen = xiaozhiClient.isOpen();
        padLedExecutor.execute(() -> {
            if (wasOpen) {
                // 斷線 - 和 handleXiaozhiApi 的 "disconnect" case 一致的清理順序。
                xiaozhiAutoMode.set(false);
                xiaozhiReconnectAttempts.set(0);
                stopXiaozhiMic();
                stopMouthLedForTts();
                cancelHeadLedReassert();
                cancelEyeLedReassert();
                xiaozhiClient.disconnect();
                Log.i(TAG, "mute key -> xiaozhi DISCONNECT");
            } else {
                // 連線 - 同 "connect" case 一致: 搶 activation gate, 背景行
                // OTA/activation flow; 完成後 CONNECTED hook 會再確認 LED。
                // 2026-08 v2 修正: 和小智 UI 那個開關看齊 - 開關的語意是「連線並
                // 隨時語音對話」, 連線完成後 auto_mode 會立即 startXiaozhiMic()
                // 取得 mic (見 runXiaozhiActivationFlow() 的 CONNECTED branch 和
                // "auto_mode" case)。之前漏了 set auto_mode, 導致只連了線
                // 卻沒拿到 mic, 這顆鍵等於沒用。
                xiaozhiAutoMode.set(true);
                if (xiaozhiActivationInFlight.compareAndSet(false, true)) {
                    xiaozhiActivationStatus.set(XiaozhiActivationStatus.checking());
                    final String deviceId = getXiaozhiDeviceId();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            runXiaozhiActivationFlow(deviceId);
                        }
                    }, "XiaozhiActivationThread").start();
                    Log.i(TAG, "mute key -> xiaozhi CONNECT (activation started, auto_mode on)");
                } else {
                    Log.i(TAG, "mute key -> xiaozhi connect skipped (activation already in flight)");
                }
            }
        });
        setChestMuteLed(!wasOpen);
    }

    /** 設定 mute LED (小智連線指示) - 連發六次確保在 chest 匯流排壅塞的情況下也生效。 */
    private void setChestMuteLed(final boolean on) {
        chestMuteLedOn = on;
        padLedExecutor.execute(() -> {
            try {
                for (int i = 0; i < 6; i++) {
                    sendChestMuteLedImage(on);
                    if (i < 5) {
                        Thread.sleep(i == 0 ? 100 : (i < 3 ? 150 : 250));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 砌 F8 8F 08 00 00 44 <data> <sum> ED 幀經 chest_sendRawData 送出。 */
    private void sendChestMuteLedImage(boolean on) {
        byte data = (byte) (on ? 1 : 0);
        int sum = (8 + (CHEST_MUTE_LED_CMD & 0xFF) + (data & 0xFF)) & 0xFF;
        byte[] frame = {(byte) 0xF8, (byte) 0x8F, 0x08, 0x00, 0x00,
                CHEST_MUTE_LED_CMD, data, (byte) sum, (byte) 0xED};
        try {
            UbxErrorCode.API_ERROR_CODE code = robot.chest_sendRawData(frame);
            Log.i(TAG, "mute LED " + (on ? "ON" : "OFF") + " -> " + code.name());
        } catch (Throwable t) {
            Log.w(TAG, "sendChestMuteLedImage failed", t);
        }
    }

    // -- Alpha2 PIR 警示反應 (LED+鈴聲) ----------------------------------------------
    // 2026-08-15 新增: 監聽獨立的 "alpha2_pir_state" event (見 RobotEventReceiver
    // 的 CHEST_ACTION case 裡面 alpha2_pir_state 那段 comment), 用 Alpha2 backend
    // 的 LED API 觸發 LED/鈴聲。
    //
    // 實機已確認: PIR raw 事件 (chest cmd=-109, "PIR HUMON DETECT") 會正常觸發 (見
    // logcat_2026-08-15_12-06-19.txt) - 這台機器 (1.1.7.3) 底層 chest MCU 硬體本身
    // 能做 PIR, 只是之前 1.1.7.3 這個 Android apk 版本沒有程式碼處理這個 case, 已在
    // RobotEventReceiver 補上。
    //
    // LED 部分: 眼/頭 5-mic LED 長亮紅燈 (setHeadEyeLedLong(1, 9)), 顏色代碼 1=紅,
    // 已在 "led/head/set" case 上面那段 comment 經實機確認過 (color: 1=紅 2=綠 3=藍
    // 4=黃 5=紫 6=青 7=白)。
    //
    // 2026-08-15 實機測試 (PIR sample test) 確認: 這台機器頭板的 5-mic head/eye LED
    // 對 PIR 警示反應是有效的 (眼/頭會亮紅燈), 不像之前 applyObstacleIndicator()/
    // registerChestMuteKeyTestListener() 遇到的情況 (header_ledSetHead5Mic/
    // header_ledSetEye5Mic 全部 preset 都回 API_ERROR_FAILED) - 兩者用的是不同
    // AIDL 方法/參數組合, 不能直接假設「一個不行全部都不行」。所以 PIR 警示只
    // 走這一條路, 沒有再加 mouth LED breathing 做 fallback, 嘴部不用閃, 和鈴聲一起
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
                // onEvent() 在 main thread 執行 - AIDL/JNI LED call 搬到 background
                // thread, 不要用主執行緒, 和專案一貫做法一致 (見
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
            // 中途關掉開關也要立即熄掉目前亮著的燈/停止正在播的聲音, 不只是不再對之後的
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
            return; // 開關關閉 - 不理會新觸發 (但已經亮著的仍然可以經由
                     // setPirAlertEnabledAlpha2(false) 熄掉)。
        }
        if (triggered == alpha2PirAlertActive) {
            return; // 避免每次重複收到同一個狀態的事件都重新送一次 LED/聲音, 和
                     // onSonarDistanceReceived() 一致的做法。
        }
        alpha2PirAlertActive = triggered;
        // 2026-08 新增: 用戶提出一個關鍵盲點 - 這個 PIR 警示 (獨立網頁「PIR 測試」
        // 開關 alpha2PirAlertEnabled 控制, 原意純粹供用戶在 web UI 上自己測試 PIR
        // 感應器有沒有反應) 和 XiaoZhi 常開對話期間的 self.robot.led_set_head/
        // led_set_eye MCP tool, 兩者完全獨立、互不知情, 但用的是同一份 head/eye
        // LED 硬體資源。如果兩者同時觸發, reassertHeadEyeLed() 那個持續補發的
        // thread 會不斷和這裡的 setHeadEyeLedLong()/header_stop5MicEarLED() 互相
        // 干擾, 導致 LED 看起來不斷閃爍/跳色, 這就是用戶說的「頭部 LED 仍然和其他 code
        // 衝突」的其中一種病灶 (另一種是 alpha2services 內部熄燈循環, 已經在
        // reassertHeadEyeLed() javadoc 處理)。這裡讓 PIR 警示觸發／解除的當下都
        // 取消 XiaoZhi 那邊的持續補發, 讓這個「用戶主動開啟的 PIR 測試」
        // 優先勝出, 不會兩份 code 同時不斷寫入同一個硬體。
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
            // 2026-08-15 更新: 實機已確認這台機器頭板的 5-mic head/eye LED 對 PIR
            // 警示反應有效 (眼/頭會亮紅燈), 不再需要 mouth LED 做 fallback -
            // 呢個 try/catch 純粹保留做保護, 防止呢句 AIDL call 出意外時累到成個
            // listener thread 死埋。
            Log.w(TAG, "applyAlpha2PirLedAndSound: 5-mic head/eye LED path failed", t);
        }
        if (triggered) {
            playPirAlertCue(); // lazy-lookup 好的 "Heaven" 鈴聲, 見 playPirAlertCue()
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


    /** 列出目前 androidTts 綁定的那個 engine 支援的所有語言/國家變體, 供
     *  speech/tts_languages endpoint 使用 (選了 engine=android 才顯示語言選擇)。
     *  getVoices() (API 21+) 做主要來源, 得到空清單才退回
     *  ACTION_CHECK_TTS_DATA legacy fallback - SVOX Pico 完全沒有實作
     *  getVoices(), IPC 層直接 throw "NullPointerException: collection ==
     *  null" (不是回傳空 collection, 已經用 try/catch 接住不會 crash, 但結果是空
     *  清單), Google TTS 則用 getVoices() 取得完整清單, 不需要走 legacy 這條路。
     *
     *  用 getVoices() 而不是 ACTION_CHECK_TTS_DATA 做主要來源的原因: 這台機器沒有
     *  Google Play Store, Google TTS 的 ACTION_CHECK_TTS_DATA 只能答出出廠
     *  內建的那一個國家變體 (中文只有 zh-TW, 英文只有 en-US) - getVoices() 直接問
     *  engine 自己完整的 voice metadata, 不受這個限制。 */
    private List<TtsLanguageOption> listAndroidTtsLanguages(Locale displayLocale) {
        List<TtsLanguageOption> viaVoices = checkTtsDataViaGetVoices(displayLocale);
        if (!viaVoices.isEmpty()) {
            return viaVoices;
        }
        // getVoices() 得到空清單 (engine 未 ready、丟出 exception 被接住、
        // 或者根本沒實作) - 不要就這樣把空清單給用戶, 退回舊方法再試一次。
        return checkTtsDataSyncLegacy(displayLocale);
    }

    /** 用 TextToSpeech.getVoices() 窮舉目前 androidTts 綁定的那個 engine 支援的所有
     *  voice/語言變體 - 見 listAndroidTtsLanguages() javadoc 解釋為何選這個
     *  API 做主要來源。 */
    private List<TtsLanguageOption> checkTtsDataViaGetVoices(Locale displayLocale) {
        if (androidTts == null) {
            return new ArrayList<>();
        }
        Set<Voice> voices;
        try {
            voices = androidTts.getVoices();
        } catch (Exception e) {
            // user-confirmed 有 OEM engine 會在這裡 throw NPE/IllegalStateException
            // 而不是正常回傳 null - 當作沒有資料處理, 退回 legacy 方法。
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
     *  自己「文字轉語音輸出」設定畫面建立「已安裝」清單所用的 intent 一樣。Result
     *  extras 用 lang-COUNTRY-variant 3 個字母 ISO code (例如 "eng-USA"), 不是
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
            checkIntent.setPackage(enginePkg); // 指定該 engine, 不是「隨便哪個應用程式搶到就用哪個」
            startActivityForResult(checkIntent, TTS_DATA_CHECK_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CHECK_TTS_DATA launch failed for engine=" + enginePkg, e);
            return new ArrayList<>();
        }
        try {
            // 3 秒對一個正常應該即時、不涉及網路/磁碟 IO 的本機查詢來說已經很夠 - 超過
            // 還沒回應就代表有問題 (engine 沒有回應), 應該回傳空清單給 caller, 不應該
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
            // 去除第 4 段開始的任何 variant 後綴 (不是 Locale 的 country, 也
            // 不是 toLanguageTag() 任何位置會放置的 engine-specific variant 標籤)。
            String[] parts = voice.split("-");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            // 兩段都是 ISO-639-2/ISO-3166-1 ALPHA-3 (3 個字母), 例如 "eng"/"USA" -
            // user-confirmed 實機 bug: new Locale("eng").toLanguageTag() 不會變回
            // "en" 這樣 (只有 2 個字母時才會)。Locale 的 constructor 完全不會將 3 個字母
            // 的 ISO code 轉成 2 個字母的對應版本 - 它只是把傳入的字串原樣存起來,
            // 所以 toLanguageTag() 之前會直接漏出原始的 3 字母 code ("ara",
            // "ben", "eng", ...), 而不是正確的 BCP-47 tag。iso3ToIso1Language()/
            // iso3ToIso1Country() 就是在做這個轉換, 靠 Locale.getAvailableLocales()
            // 反查, 因為 Locale 本身沒有「3 個字母轉 2 個字母」的直接 API。
            String lang2 = iso3ToIso1Language(parts[0]);
            if (lang2 == null) {
                // 不是一個有 2 個字母對應版本的 3 個字母 ISO-639-2 code -
                // user-confirmed 真實 case: "yue" (粵語) 根本沒有 ISO-639-1 2 個
                // 字母 code, 所以 iso3ToIso1Language("yue") 合理地回傳 null, 之前
                // 這裡會直接 "continue" (跳過整個 entry), 悄悄地漏掉了粵語, 雖然
                // Google TTS 確實裝了 (logcat 看到 "Download of yue-hk started"/
                // "Download yue-hk Success true")。BCP-47 (和 Java 的 Locale)
                // 都接受 3 個字母的 primary language subtag 直接使用 (IANA 的
                // language subtag registry 本身有列出 "yue" 作為合法 primary
                // subtag) - 所以退回用 3 個字母 code 原樣, 不要去掉整個語言。
                lang2 = parts[0];
            }
            String country2 = null;
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                country2 = iso3ToIso1Country(parts[1]);
                if (country2 == null) {
                    // 和上面語言那句一樣的道理 - 保留原本 3 個字母 country code
                    // 比去掉好 (雖然不是 ISO-3166-1 alpha-2, 但仍然有意義)。
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
     *  code 到 ISO-639-1 2 個字母 code 的反查表, 因為 java.util.Locale 沒有這個方向
     *  的直接 API - 只有正向的 Locale.getISO3Language() (由一個已經是 2 個字母
     *  的 Locale 出發)。用 Locale.getAvailableLocales() (這個 JVM 支援的全部
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
                    // 用 containsKey()+put() 而不是 putIfAbsent() - user-confirmed
                    // 真機 crash: 呢部機 Android 版本早過 API 24 (Nougat),
                    // Map.putIfAbsent() 係 default method, 淨係 API 24 開始先有
                    // (呢個 app 自己個 minSdkVersion 係 19) - call 落去會 throw
                    // NoSuchMethodError 令成個 app 死埋。containsKey()+put() 用
                    // pre-Java-8/pre-API-24 都支援的 Map method 做出同樣「keep the
                    // first mapping seen」的效果。
                    if (lang3 != null && !lang3.isEmpty() && !map.containsKey(lang3)) {
                        map.put(lang3, lang2);
                    }
                } catch (Exception ignored) {
                    // 有部分 Locale 會在這裡 throw MissingResourceException - 只是
                    // 代表那一個貢獻不了映射, 不是要中止建立整個表的理由。
                }
            }
            iso3LanguageMap = map;
        }
        return map.get(iso3);
    }

    /** 和 iso3ToIso1Language() 想法一樣, 但是轉 ISO-3166-1 alpha-3 國家 code
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
                    // 見上面 iso3ToIso1Language() 為何不用 putIfAbsent()。
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

    /** langTag 傳回給 speak(text, langTag)/setLanguage(), displayName 是提供給 UI 顯示
     *  的名稱 - 在 server 端經由 Locale.getDisplayName() 建立, 不用讓前端自己維護一份
     *  tag->name 對照表。 */
    private static final class TtsLanguageOption {
        final String langTag;
        final String displayName;
        TtsLanguageOption(String langTag, String displayName) {
            this.langTag = langTag;
            this.displayName = displayName;
        }
    }

    /** 列出機身已安裝的全部 Android TTS 引擎 package name (已排序) - 供
     *  speech/tts_engines endpoint 使用, 讓 speech tab 的 Android 選項可以選擇哪個
     *  引擎發音。用一個 throwaway TextToSpeech instance 取得這個裝置層面的清單,
     *  不綁定目前使用中的 androidTts field - getEngines() 本身不是
     *  engine-specific, 不用等 androidTtsReady 才能查詢, 用 live 的
     *  androidTts 反而有可能取得「舊 engine 時捕捉到」的過時清單。 */
    private List<String> listAndroidTtsEngines() {
        List<String> result = new ArrayList<>();
        TextToSpeech probe = null;
        try {
            final CountDownLatch initLatch = new CountDownLatch(1);
            probe = new TextToSpeech(this, status -> initLatch.countDown());
            // getEngines() 本身不需要 init 完成 (不是 engine-specific), 但稍等一下
            // 避免和 constructor 自己的 async setup 互相衝突 (部分 OEM engine 見過)。
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
                // 和 robot-side TTS 的 onServerPlayEnd 一致, publish tts_end
                // 讓前端知道這句讀完了 - 小智 tab 選了本地引擎的時候靠這個 event
                // 排隊讀多句回覆 (見 xiaozhiTtsQueue 相關 comment)。isEnd 固定
                // true, Android TTS 沒有對應 onServerPlayEnd 的 isEnd 語意, 這裡
                // 沒有對應的 false case。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }

            @Override
            public void onError(String utteranceId) {
                stopMouthLedForTts();
                // 出錯也要 publish, 不然前端的 queue 會卡在那裡等一個永遠不會來
                // 的 tts_end, 之後所有排隊的句子都讀不到。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }
        });
        androidTts = created;
    }

    /** 接住 checkTtsDataSyncLegacy() 發出的 ACTION_CHECK_TTS_DATA 結果。只
     *  處理這個 app 自己認得的 requestCode, 其他一律交回給 super (雖然目前這個
     *  app 沒有其他地方用 startActivityForResult(), 但這是基本禮貌, 不應該
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
            // disconnect() 內部現在會做 sendCloseFrame() (socket write, 完成
            // WebSocket close handshake, 見 XiaozhiClient 的 case 0x8 的
            // comment)。onDestroy() 保證在 main thread 執行, Android 對 main
            // thread 做網路 I/O 的限制不會因為「這個 write 很快」就豁免 - 實機
            // 證實會拋出 NetworkOnMainThreadException, 導致 onDestroy() 本身拋出
            // uncaught exception, 造成整個 activity destroy 失敗、app crash
            // (見 logcat FATAL EXCEPTION: main / "Unable to destroy activity")。
            // 這裡將 disconnect() 移到背景 thread 執行 - onDestroy() 不用等它做完
            // (fire-and-forget, app 反正就要結束了, close frame 送不送得到都不影響
            // 用戶體驗), 只需要避免在 main thread 直接觸發網路 write。
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
        if (wifiLedReceiver != null) {
            try {
                unregisterReceiver(wifiLedReceiver);
                wifiLedReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (panelUrlReceiver != null) {
            try {
                unregisterReceiver(panelUrlReceiver);
                panelUrlReceiver = null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (offlineWatchdogThread != null) {
            offlineWatchdogThread.quitSafely();
            offlineWatchdogThread = null;
        }
        cameraController.shutdown();
        audioController.shutdown();
        audioPlaybackController.shutdown();
        stopRingtonePlayback();
        // 2026-08 新增: 之前這裡沒有呼叫 stopLocalMusicPlayback()/stopRadioPlayback() -
        // onDestroy() 就算執行了也不會釋放正在播放的 currentMusicPlayer/currentRadioPlayer,
        // 一直以來都是個 leak (MediaPlayer native resource 沒有 release())。加入
        // Equalizer (musicEqualizer, 跟隨 currentMusicPlayer 的生命週期) 之後這個
        // 缺口更需要補上: Equalizer 綁定的 audio session 如果連 app 結束都不釋放,
        // 留下的 native effect engine 資源就更難追蹤。沿用 stopRingtonePlayback()
        // 一樣的做法, 在這裡一併全部停止。
        stopLocalMusicPlayback();
        stopRadioPlayback();
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
                String p = query.get("path");
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
                String msStr = query.get("ms");
                int ms;
                try {
                    ms = Integer.parseInt(msStr);
                } catch (Exception e) {
                    return HttpServer.ApiResponse.error("ms must be an integer");
                }
                String err = musicController.seekTo(ms);
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "music/volume": {
                String pctStr = query.get("percent");
                int pct;
                try {
                    pct = Integer.parseInt(pctStr);
                } catch (Exception e) {
                    return HttpServer.ApiResponse.error("percent must be an integer");
                }
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
                // 2026-08 修正: 之前這裡的 comment 說「主流自架 server 只需要 OTA
                // URL, websocket url/token 由 OTA response 一併送回, 不開放獨立
                // 欄位」- 但實測發現不是所有自架方案都能依照這個協議形狀傳回足夠資訊,
                // 用戶手上的 server 需要手動填寫 websocket 地址、MAC/Device-Id、
                // token 才連得上。現在這三個都開放做可選 override: 留空就繼續走
                // 原本「只有 OTA URL, 其餘自動」那條路; 有填就用來覆蓋
                // runXiaozhiActivationFlow() 裡對應的自動值 (見該處 comment)。
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

            // 2026-08 新增: MCP 設定 card 用的三個 endpoint。
            //
            // mcp_tools/list 回傳全部 tool (含已 disable 的, 讓用戶可以按按鈕重新
            // enable), 一併附上每個 tool 目前的 enabled 狀態。和官方 xiaozhi.me console
            // 那邊的「MCP接入點」是完全不同的東西 (那個是給第三方外部工具反過來連進小智
            // 使用的獨立 websocket 端口, 和這台機器自己內建、經由 xiaozhiMcpBridge() 暴露
            // 給遠端 LLM 的 tool 無關, 不應該混為一談)。
            //
            // mcp_config/get 取得總開關和逐一 tool 的 enabled 狀態; mcp_config/set
            // 寫入總開關或單一 tool 的 enabled 狀態 - listTools()/callTool()
            // (見 xiaozhiMcpBridge()) 會即時反映這裡的改動, 不用重新連線 XiaoZhi。
            case "mcp_tools/list": {
                org.json.JSONArray fullList = lastFullMcpToolList;
                if (fullList == null) {
                    // 未連過 XiaoZhi/未收過任何 tools/list request - 個 card 應該
                    // 讓用戶在還沒連線之前也能看到有哪些 tool 可以 enable/disable, 所以
                    // 這裡強制執行一次 listTools() 建立清單 (side effect 會存到
                    // lastFullMcpToolList, 下次不用再強制)。
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
                    result.put("ok", true);
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
                // 兩種用法, 依 query 帶的參數而定:
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

            // 見 xiaozhiTtsEngine field 的 javadoc。engine 值: "xiaozhi" (預設,
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
                if (!xiaozhiActivationInFlight.compareAndSet(false, true)) {
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
                cancelHeadLedReassert(); // 斷開連線就沒必要再持續補發 head/eye LED, 結束
                cancelEyeLedReassert();
                xiaozhiClient.disconnect();
                // 2026-08 v2: mute 鍵 LED = 小智連線指示燈 - web UI 斷線都要熄燈。
                setChestMuteLed(false);
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
                        // 見 xiaozhiActivationInFlight field javadoc: 用
                        // compareAndSet 原子操作來判斷並保留這個 gate, 不再依靠
                        // xiaozhiActivationStatus 的 stage (判斷和啟動 thread 之間
                        // 有時間差, 會漏掉另一條 thread 剛啟動但還沒來得及 set stage
                        // 的那個窗口期)。
                        if (xiaozhiActivationInFlight.compareAndSet(false, true)) {
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

    /** 抽取自 "send_text" HTTP case 的共用邏輯 - 送一句文字進 XiaoZhi 對話, 就像
     *  用戶打字一樣。這個方法本身有阻塞式操作 (Thread.sleep + 阻塞式 WebSocket
     *  send), **呼叫這個方法的 thread 一定要是一條可以阻塞的獨立 worker
     *  thread** (例如 HTTP handler thread, 或刻意開的背景 thread) - 絕對不可以
     *  在 BroadcastReceiver.onReceive()、UI thread, 或任何有時限的 callback
     *  裡直接呼叫, 否則會撞上 Android 的 broadcast timeout / ANR 機制。
     *  (2026-08 曾經在一個粗心的版本裡, 在 onPirStateReceived() 這個
     *  BroadcastReceiver callback 裡直接呼叫了這個方法沒有包多層 thread, 導致
     *  PIR 密集 broadcast 時連環阻塞, 實機實測直接 hold 死整個 system 連 adb
     *  都沒有反應 - 現在 onPirStateReceived() 已經改用獨立 thread 包住才呼叫
     *  這個方法, 這段 comment 記下那次教訓, 提醒之後不要再犯。)
     *
     *  送成功就回傳 null, 失敗就回傳錯誤訊息 (不拋 exception, 讓 caller 自己決定
     *  要不要讓用戶看到 / 要不要 log)。
     *
     *  2026-08: 之前這裡一度以為長文字要自己切段才能送出, 因為官方 xiaozhi.me 對
     *  沒標記的 "detect" 訊息會拒絕長文字 (錯誤訊息 "detect is only for wake
     *  words, do not send long texts")。反編譯一個第三方 apk 之後找到根本修法:
     *  送出的訊息要多附上一個 "source":"text" 和 "session_id" 欄位 (見
     *  XiaozhiClient.sendListenDetectText() javadoc 完整說明) - 加上這兩個欄位
     *  之後 server 不會再誤把這當成 wake-word 事件來驗證長度, 所以這裡不用切段,
     *  一次送完就好。
     *
     *  2026-08 再修正 (實機證實的第二層問題): 加了 source/session_id 之後長度
     *  限制不再撞到了, 但打字輸入依然完全沒反應 (沒有 STT/LLM/TTS 回應) - 對照
     *  logcat 才發現原因: 小智常開開啟時 mic 一直開著、持續 send Opus
     *  binary frame 上去 server (XiaoZhi capture level check 一直有數值,
     *  micActive/micHeld 都是 true), 打字那句 detect JSON message 就在這股持續
     *  的 audio stream 中途插入送出 - server 側極可能把 mic 錄到的背景聲音當成
     *  「主要輸入」, 打字那句被 audio stream 蓋過/觸發衝突判斷, 兩者都沒有被正常
     *  處理。這裡在送 detect 之前暫停 mic capture (不用斷開整個 XiaoZhi 連線,
     *  只是停止送 audio frame), 讓 detect message 在那一刻是唯一的輸入, 送完
     *  之後如果小智常開仍然開著就重新開啟 mic (沿用
     *  startXiaozhiMic()/stopXiaozhiMic() 已有的 mic 生命週期管理)。
     *
     *  2026-08 第三次: 前兩層修法都沒解決「長打字對白仍然不行」- 這仍然是
     *  尚未確診的開放問題, 沒有 logcat 可以看實際 server 回了什麼, 不應該再猜第四種
     *  寫法。這個方法保持之前確認過方向正確的寫法, 沒有再改動送出邏輯本身, 等有
     *  真機 log 先再處理。 */
    private String xiaozhiSendDetectTextSafely(String text) {
        if (!xiaozhiClient.isOpen()) {
            return "not connected";
        }
        boolean micWasActive = xiaozhiAudioController.isCapturing();
        if (micWasActive) {
            stopXiaozhiMic();
            // 給一點時間等 stopCapture() 真正停止、最後幾個 in-flight 的
            // audio frame 送完, 再送 detect message, 減少兩條 stream 交錯
            // 的機會。
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
            // 2026-08 新增: 實測發現「打字完全送出去了 (server 沒報錯, {"ok":true}),
            // 但 LLM 完全沒反應」- 對照 logcat 才找到: 之前這裡送完 detect 立即就
            // startXiaozhiMic(), 中間只相隔幾百毫秒就又送了一個
            // {"type":"listen","state":"start","mode":"auto"} - 兩個連續的 listen
            // state 轉換之間沒有給足時間讓 server 先處理完前一個, 很可能導致 server 側
            // 把 session 重置了/取消了剛送出的那個 detect 的處理, 才再開始一個
            // 新（空）的聆聽 session, 讓文字訊息無聲無息地被蓋過 - 和
            // reassertHeadEyeLed() 提到的「兩個連續 listen 轉換之間沒讓夠時間」是
            // 同一種問題的另一個病徵。這裡多給 300ms 緩衝再重開 mic, 讓 server
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
        // 2026-08 修正: 之前這裡直接開 XiaozhiAudioController 的 AudioRecord, 完全沒有
        // 取得 mic 擁有權 - alpha2services 自己的 wake-word 引擎一直持續佔用麥克風,
        // 這台機器的音訊 HAL 又不支援多個 process 同時開啟 mic input, 所以之前的
        // AudioRecord.startRecording() 實質上一直收不到聲音。這裡和 handleMicStream()
        // (Speech/Mic tab 那個獨立 mic 串流) 一樣, 用 releaseMicForAudioIo() 先取得
        // mic 擁有權 (speech_SetMIC(true) + 300ms sleep 避開 race - 見
        // releaseMicForAudioIo() javadoc), 先至真正開 AudioRecord。
        releaseMicForAudioIo();
        try {
            xiaozhiClient.sendListenStart();
        } catch (java.io.IOException e) {
            robot.speech_SetMIC(false); // 硬體都還沒開就立即放棄, 將 mic 還給機器人
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
        // "adev_open_input_stream:channel is not support" - AudioRecord 的 Java
        // 層 state 照樣顯示 STATE_INITIALIZED (騙過 startCapture() 裡的
        // check), 但底層 HAL 實際上開啟 input stream 失敗, 導致 .read() 收不到真正
        // 的聲音, 送去 XiaoZhi server 的是靜音/垃圾 frame, 使語音對話完全沒反應。
        // 這裡加一個短 sleep, 等 output stream 的 HAL 初始化完全 settle 才開始
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
        // Mic 擁有權和硬體都成功取得 - 通知前端將燈號轉綠 (見 index.html
        // #xiaozhiMicLed / app-xiaozhi.js 的 xiaozhi_mic_state 事件處理)。
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
            // 將 mic 還給機器人自己的 wake-word 引擎 - false = "把麥克風交還給機器人"
            // (和 handleMicStream() finally 段的寫法一致)。不理會 Mic tab 那個
            // micHeldByApp 開關狀態 - 兩者是獨立用途 (XiaoZhi 語音對話 vs
            // Mic tab 手動持有), 誰都不應該蓋過對方的意圖: 如果用戶在 Mic
            // tab 另外持有 mic, XiaoZhi 這裡也只是老實地歸還自己拿的那份, 沒有額外多還一次
            // 的副作用 (speech_SetMIC(false) 是 idempotent 的狀態設定, 不是計數器)。
            robot.speech_SetMIC(false);
            xiaozhiMicHeld = false;
            EventBus.get().publish(XIAOZHI_MIC_STATE_EVENT, "{\"held\":false}");
        }
    }

    /** 和 startMicHoldEnforcer() (Mic tab 專用) 對應的 XiaoZhi 版本 - 背景 thread
     *  持續每 MIC_HOLD_ENFORCER_INTERVAL_MS 重新呼叫一次 speech_SetMIC(true),
     *  防止 firmware 內部從旁奪回 mic (見 startMicHoldEnforcer() javadoc 的原因)
     *  在小智語音對話進行的那段時間也不會被悄悄搶走。獨立於 Mic tab 那條
     *  enforcer thread, 因為兩者的生命週期不同 (這個跟隨 xiaozhiMicHeld, 不跟
     *  micHeldByApp)。 */
    private void startXiaozhiMicHoldEnforcer() {
        if (xiaozhiMicHoldEnforcerThread != null) return;
        xiaozhiMicHoldEnforced = true;
        xiaozhiMicHoldEnforcerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (xiaozhiMicHoldEnforced && !Thread.currentThread().isInterrupted()) {
                    // 見 robotTtsSpeaking field javadoc - 機身 robot-side TTS
                    // (iflytek/nuance) 正在播放就跳過這一輪, 不要用
                    // speech_SetMIC(true) 打斷它。跳過也不會讓 mic 太久沒人持有:
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
        // 2026-08 新增: 這個 try/finally 包住整個 method body, 保證不論裡面
        // 用哪種方式 exit (正常 return、下面那個 try/catch 接住的 exception、
        // 或是某些完全接不住的 Throwable), xiaozhiActivationInFlight 這個 gate
        // 一定會被釋放 - 釋放不了的話整個 app 會永久鎖死在「activation already
        // in progress」, 比之前的 bug 更糟。見 xiaozhiActivationInFlight field
        // 的 javadoc 解釋整套機制為何要這樣做。
        try {
            // 自訂 server 開關 (見 PREF_XIAOZHI_OTA_CUSTOM_ENABLED/PREF_XIAOZHI_OTA_URL) -
            // 開啟就用自己填的 OTA URL, 關閉則沿用官方 xiaozhi.me 預設。OTA endpoint 一般
            // 已經足夠切換成自架 server (check_version 回應通常會一併附上真正的 websocket
            // url/token 送回), 但不是所有自架方案都能依照這個協議形狀 - 2026-08 新增了
            // wsUrl/deviceId/token 三個可選 override (PREF_XIAOZHI_WS_URL_OVERRIDE
            // 等), 留空就繼續用 OTA response/自動產生的那個值, 有填就用來覆蓋, 應付需要
            // 手動配置的自架 server。
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean customEnabled = prefs.getBoolean(PREF_XIAOZHI_OTA_CUSTOM_ENABLED, false);
            String otaUrl = customEnabled
                ? prefs.getString(PREF_XIAOZHI_OTA_URL, XiaozhiOtaClient.DEFAULT_OTA_URL)
                : XiaozhiOtaClient.DEFAULT_OTA_URL;
            String wsUrlOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_WS_URL_OVERRIDE, "") : "";
            String deviceIdOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_DEVICE_ID_OVERRIDE, "") : "";
            String tokenOverride = customEnabled ? prefs.getString(PREF_XIAOZHI_TOKEN_OVERRIDE, "") : "";
            // deviceId override 要在 OTA client 建構之前就決定 - Device-Id header
            // 從 OTA check_version 的 request 開始就要用同一個值 (和 WebSocket 那邊一致,
            // 見 getXiaozhiDeviceId() 的 comment), 不只是影響最終 connect() 那一下。
            // 用一個新的 final 變數來裝最終值 (而不是重新賦值 method 的 deviceId
            // parameter 本身) - 這個 method 尾段的匿名類 (DisconnectListener) 有
            // capture deviceId, capture 到的 local variable 一定要是 effectively
            // final, 重新賦值會導致這個 method 編譯不過。
            final String effectiveDeviceId = deviceIdOverride.isEmpty() ? deviceId : deviceIdOverride;
            // 2026-08 新增: 存下這個 session 用的 clientId, 讓 xiaozhiVisionExplain()
            // 可以送回同一個 Client-Id header (見 xiaozhiClientId field 的 comment)。
            final String effectiveClientId = java.util.UUID.randomUUID().toString();
            xiaozhiClientId = effectiveClientId;
            XiaozhiOtaClient ota = new XiaozhiOtaClient(otaUrl,
                effectiveDeviceId, effectiveClientId);
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
                // polling 傳去前端, 完全沒有經過 EventBus - 導致在 WebSocket event log
                // (WebSocketServer 訂閱 EventBus 再 fan-out 到所有已連線的瀏覽器
                // tab) 上完全看不到, 用戶反映「只有聲音, 連 websocket 都沒顯示」。
                // 這句讓配對碼也經由正常的 EventBus -> WebSocketServer -> 前端
                // event log 路徑推送一次, 和 HTTP polling 途徑並存 (兩者不衝突,
                // 前端 xiaozhiShowActivationCode() 那個 xiaozhiLastShownActivationCode
                // 防重複邏輯是獨立處理 HTTP polling 那邊, 不會受這個新 event 影響)。
                EventBus.get().publish("xiaozhi_activation",
                        "{\"code\":\"" + jsonSafe(code) + "\",\"message\":\""
                                + jsonSafe(message != null ? message : "") + "\"}");
                // 2026-08: 之前用戶要求取消機身 TTS 讀配對碼, 改為單純靠界面顯示 -
                // 但實測發現沒有 TTS 讀出來之後配對經常失敗 (實機 logcat 顯示配對碼
                // 出來之後短時間內就 "Read timed out"), 用戶反映需要機身讀出來才
                // 有足夠反應時間去手機/電腦打開 xiaozhi.me 輸入。現在加回這個
                // call。真正導致配對容易 timeout 的根源其實在
                // XiazhiOtaClient.pollActivation() 的單次 HTTP request timeout
                // (10 秒) 太短、一撞到就導致整個輪詢直接失敗那個 bug, 已經在那邊
                // 修正 (暫時性網路錯誤現在會重試, 不會立即放棄) - 但機身讀出配對碼
                // 本身也是一個用戶想要的獨立功能, 兩者都保留。
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
                    // 選了本地 TTS 引擎 (見 xiaozhiTtsEngine field javadoc) 就
                    // 完全靜音這條 cloud opus 聲軌 - 只是不 forward 到
                    // XiaozhiAudioController, decode/AudioTrack pipeline 本身
                    // 沒有改, 一切回 "xiaozhi" 就立即恢復原本行為。
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
            // 2026-08 新增: 嘴部 LED 同步 - 沿用本地 TTS 已有的
            // startMouthLedForTts()/stopMouthLedForTts() (MouthLedData breathing 效果),
            // 但這裡要對應 XiaoZhi 自己那套 tts state (start/sentence_start/stop, 見
            // websocket.md 和實測 logcat), 不是本地 TTS 那個單次 speech_startTTS。
            // "start" = 這句/這段回應開始播 -> 點亮; "sentence_start" 純粹是分句
            // (同一段回應裡面, 中途不停) -> 不用理會, 燈應該一直亮到整段答案講完;
            // "stop" = 整段回應播完 -> 熄燈。沿用 xiaozhiAutoMode/mic-restart 那個
            // 同一個 case 分支, 熄燈和重新聆聽是同一時機發生, 沒有額外 race。
            xiaozhiClient.setTtsStateListener(new XiaozhiClient.TtsStateListener() {
                @Override
                public void onTtsState(String stateValue) {
                    if ("start".equals(stateValue)) {
                        startMouthLedForTts();
                        // 2026-08 修正: 用戶要求「random 動作要和 tts 一起發生, 而不是
                        // 講完才做」- 之前錯放在 "stop" (整段回應播完) 才觸發, 用戶
                        // 看到的是機器人站定不動聽完整句才動, 不是想要的「講話時
                        // 同時動作」效果。現在改在這裡 ("start", 這一輪開始講話的那一刻)
                        // 就立即觸發, 讓動作和說話大致同步發生。實際執行邏輯
                        // 搬到了 triggerRandomFillerAction() (見 javadoc) - 播放本地
                        // 音樂 (self.media.play_music) 現在也用同一個 helper 做出
                        // 一樣的「動一下讓它看起來生動一點」效果。
                        triggerRandomFillerAction();
                    } else if ("stop".equals(stateValue)) {
                        stopMouthLedForTts();
                        if (xiaozhiAutoMode.get()) {
                            startXiaozhiMic();
                        }
                    }
                }
            });
            // 2026-08 新增: 實測發現 server 會在對話中途主動 send WebSocket close
            // frame 斷開連線 (原因未明, 見 XiaozhiClient 的 case 0x8 新加的
            // describeCloseFrame() log, 等下次實機測試可以查到實際 close code) -
            // 之前這個情況沒有處理, 用戶會看到「開關仍然開著」但實際已經斷線、mic
            // capture 都停了, 完全沒有任何提示, 看起來像是「講了話但小智完全沒反應」。
            // 現在小智常開開啟時, 意外斷線會自動嘗試重連, 不用讓用戶自己發現並手動
            // 關開開關。見 xiaozhiScheduleReconnect() 的 comment 解釋如何防止狂重試。
            xiaozhiClient.setDisconnectListener(new XiaozhiClient.DisconnectListener() {
                @Override
                public void onUnexpectedDisconnect() {
                    // 2026-08 v2: mute 鍵 LED = 小智連線指示燈, 斷線就熄。
                    setChestMuteLed(false);
                    // 2026-08 新增: 意外斷線可能發生在 TTS 播放中途 (也就是
                    // 收到 "start" 但還沒收到對應的 "stop"), 嘴部 LED 會停留在點亮的
                    // breathing 狀態, 沒有任何東西會再觸發熄滅它 - 這裡保證斷線一定會
                    // 熄掉燈, 不論之前有沒有成功收到 "stop"。
                    stopMouthLedForTts();
                    // 2026-08 修正: 之前這裡沒有立即將 xiaozhiActivationStatus
                    // reset - 斷線之後它會停留在斷線前的值 (通常是 CONNECTED),
                    // 一直留到 xiaozhiScheduleReconnect() 的 5 秒 backoff delay
                    // 過了、真正重連 thread 啟動時才被更新。這 5 秒窗口期裡
                    // UI 顯示「連接失敗」但 xiaozhiActivationInFlight gate 還沒鎖住
                    // (自動重連 thread 尚未啟動), 用戶心急按下「連線」會通過 guard、
                    // 和 5 秒後的自動重連 thread 撞在一起 (見 xiaozhiActivationInFlight
                    // field javadoc) - 這就是「連不上、很快斷線、越按越糟」這個
                    // bug 的根源。現在一斷線就立即 set 為 idle(), 讓 UI/guard
                    // 即時反映真實狀態, 不留下這個誤導性的窗口期。
                    xiaozhiActivationStatus.set(XiaozhiActivationStatus.idle());
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
            // 2026-08 v2: mute 鍵 LED = 小智連線指示燈, 真正連上才亮 (按鍵當下
            // 只是即時反應, 這裡才是權威狀態)。
            setChestMuteLed(true);
            // 連接成功, 重置重試計數 - 下次意外斷線才從 0 開始計算 backoff, 不會
            // 因為之前重試過就跳到長 delay (見 xiaozhiScheduleReconnect() 的
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
        } catch (Throwable e) {
            // 2026-08 修正: 之前呢度淨係 catch IOException, 但呢個 try 區塊入面
            // (尤其是 xiaozhiClient.connect() 那句) 一旦拋出非 IOException 的
            // exception (例如 RuntimeException/NullPointerException, WebSocket
            // handshake 或 URL parse 階段常見), 就不會被這個 catch 接住 -
            // 背景 activation thread 會直接掛掉, 但 xiaozhiActivationStatus
            // 永遠停留在 CHECKING/AWAITING_CODE/POLLING/CONNECTING 其中一個中途
            // stage, 之後任何一次按「小智」開關都會立即被 "connect" case 的
            // guard 擋住說「activation already in progress」, 要重啟整個 app
            // 才能解決。現在用 catch (Throwable e) 兜到底 (連 Error 都涵蓋,
            // 不只是 Exception), 保證這個 try 區塊一有任何失敗, stage 一定會
            // 退回 ERROR, 不會再卡死在中途 stage。
            Log.w("MainActivity", "XiaoZhi activation flow failed: " + e.getMessage());
            xiaozhiActivationStatus.set(XiaozhiActivationStatus.error(
                    e.getMessage() != null ? e.getMessage() : e.toString()));
            // 2026-08 v2: activation 失敗 (例如 TLS 證書/網絡問題) - mute LED 熄返,
            // 不要留下「假連線」燈號。
            setChestMuteLed(false);
        } finally {
            // 見這個 method 開頭那個 try 和 xiaozhiActivationInFlight field 的
            // javadoc: 不論上面如何 exit, 這個 gate 一定會被釋放, 下次 connect
            // (手動撳掣或者自動重連) 先可以再次通過。
            xiaozhiActivationInFlight.set(false);
        }
    }

    /** 小智常開開啟時, WebSocket 意外斷線 (見 XiaozhiClient.DisconnectListener)
     *  就自動嘗試重連, 用戶不用自己發現開關已經名存實亡才手動關開一次。
     *
     *  Exponential backoff (5s, 10s, 20s, 最多封頂 60s) 加最多 MAX_RECONNECT_ATTEMPTS
     *  次數上限, 而不是一見到斷線就立即狂重試: 如果斷線原因是伺服器端持續性問題
     *  (例如 token 失效、伺服器維護), 無限制地重試只會不斷再取得新 activation code
     *  (可能重新觸發配對流程) 和浪費電量/流量, 對用戶完全沒幫助; 加了上限之後,
     *  重試完都連不上就停止, 保留 xiaozhiActivationStatus 的 error 狀態讓用戶看到
     *  發生了什麼事, 好過默默不斷重試下去。用戶隨時可以手動關開開關重新嘗試,
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
        // thread 的 Handler - postDelayed() 只做到「延遲幾秒才執行」, 這個
        // Runnable 本身依然是在 main thread (Looper.loop()) 上跑, 不會自動跳去
        // 背景 thread。runXiaozhiActivationFlow() 裡面 checkVersion() 會做 HTTPS
        // POST (XiaozhiOtaClient.postJsonWithStatus()), 在 main thread 做網路
        // I/O 會立即拋出 NetworkOnMainThreadException, 導致整個 app crash - 實機
        // 證實: v34 修好重連判斷邏輯之後, 重連終於開始真正觸發, 就立即
        // 暴露了這個一直潛伏著、之前因為重連從未真正執行過而沒撞到的 bug (stacktrace
        // 見 MainActivity$34.run() -> runXiaozhiActivationFlow() ->
        // XiaozhiOtaClient.checkVersion())。這裡將實際工作 (runXiaozhiActivationFlow)
        // 移到一個獨立背景 thread, mainHandler.postDelayed() 只用來做延遲計時,
        // 不再在 Runnable 裡直接做網路 call。
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 用戶可能在這段 delay 期間自己手動關掉了開關 - 這種情況下不應該
                // 重連, 尊重用戶的意圖。
                if (!xiaozhiAutoMode.get()) return;
                // 用戶可能在這 5 秒 delay 期間自己手動按了「連線」, 已經有另一條
                // runXiaozhiActivationFlow thread 在執行 (見 xiaozhiActivationInFlight
                // field javadoc) - 這種情況這條自動重連就不應該再啟動多一條, 交給
                // 用戶手動那次去做就夠。
                if (!xiaozhiActivationInFlight.compareAndSet(false, true)) return;
                xiaozhiActivationStatus.set(XiaozhiActivationStatus.checking());
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
     *  "機器人自己讀出來" behavior the person asked for, so they don't need to look at
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
        String text = "配對碼是 " + spoken + "。請去 xiaozhi 點 me 輸入這個碼。再說一次，配對碼是 " + spoken + "。";
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
    /** Lazily loads + parses assets/web/xiaozhi_actions.json (202 個動作, 由用戶提供的
     *  202_actions_classified.txt 轉出來的) - each entry has "id", "nameCn", "nameEn".
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

    /** Radio Browser (radio-browser.info) 的其中一個 API 主機 - 官方文件建議客戶端
     *  對 "all.api.radio-browser.info" 做 DNS 解析再從多個鏡像之間挑選, 但這台機器沒有
     *  DNS SRV/多鏡像 failover 的需求 (一台家用機器人, 不是高流量服務), 直接用
     *  官方文件範例裡出現的 de1 這個固定主機就已經足夠, 保持程式碼簡單。 */
    private static final String RADIO_BROWSER_API_HOST = "http://de1.api.radio-browser.info";

    /** 官方文件要求每個 request 都帶一個有意義的 User-Agent (格式 appname/version),
     *  讓他們知道哪些 app 在用這個服務 - 這裡老實地帶上這個 project 的名字。 */
    private static final String RADIO_BROWSER_USER_AGENT = "OpenAlpha2/1.0";

    /** 用 Radio Browser 的 "Advanced station search" endpoint
     *  (/json/stations/search) 動態搜尋全世界電台 - 這個 API 完全公開、免費、不需要
     *  API key, 資料來自電台自己申報給這個公開 directory 的串流位址 (不是擷取
     *  受保護內容那種), 詳見官方文件 docs.radio-browser.info。
     *
     *  參數選擇 (2026-08 更新, 用戶回報「電台... 只選地方選電台也出現問題,
     *  和格式無關」之後查 logcat 確認、加強):
     *  - order=votes&reverse=true: 最多人投好的電台排在前面, 有助於過濾掉死台/垃圾台
     *  - hidebroken=true: 不顯示 Radio Browser 定期健康檢查已知播不了的台
     *  - codec=MP3: 只要 MP3 - Android 5.1 的 MediaPlayer 對 MP3 支援最穩定,
     *    某些台用的 codec (AAC+ 變種、OGG 等) 在這個 API level 未必個個都播得了
     *  - is_https=false: 只要串流位址本身是 http (不是 https) 的台 - 這個才是
     *    用戶回報問題的真正根源 (見下面 "真正根源" 段落), 和選哪個地方/哪個
     *    電台無關, 每一次 search_radio/play_radio call 都是同一個 exception。
     *
     *  真正根源 (2026-08 用 logcat 確認): 之前用戶回報「收音機要驗證, 用不了」
     *  以為是播放格式問題所以加了 codec=MP3, 但現在憑實際 logcat 看到的
     *  exception 是 java.security.cert.CertPathValidatorException: Trust
     *  anchor for certification path not found - 這是 Android 5.1 (2015 年
     *  出廠) 的系統 CA store 沒有收錄現代 CA/certificate chain, 而且 Android 5.1
     *  無法 OTA 更新系統 CA store, 所以連 https 握手都過不了, 完全和選哪個電台
     *  無關: (1) 這個 API 本身 (RADIO_BROWSER_API_HOST) 已經改用 http 避開了
     *  問題; (2) 但 station 的 "url_resolved" 播放位址本身也可能是 https,
     *  MediaPlayer 播放 https 串流一樣走 Android 系統的 TLS 堆疊
     *  (android.security.net.config.RootTrustManager), 一樣會撞上同一個
     *  trust anchor 問題 - 所以這裡連搜尋結果都要選 is_https=false, 才能讓
     *  「找到的台」和「播得了的台」一致, 而不是搜尋 API 沒中招、實際播放又中招。
     *
     *  HLS (.m3u8 分段串流, 舊版 MediaPlayer 支援不穩定、部分還需要
     *  session/token) 這個特徵沒有直接開放做 API 參數, 在 resolveRadioStation()
     *  裡、播放之前檢查 station 的 "hls" 欄位來過濾掉 (見該 method 的 javadoc)。
     *
     *  沒有把整套 API filter (country/language/tag 等) 暴露給 LLM, 保持
     *  self.media.search_radio 的 schema 簡單、只要一個 query 就夠 - 這沿用
     *  self.media.play_music 用 fuzzy match 不用一大堆 filter 參數的同一套
     *  「LLM 用自然語言, 不用懂 API 細節」設計原則。query 直接餵給 "name" 這個
     *  參數 (Radio Browser 的 name 搜尋本身就是不分大小寫的 substring, 不用這台機器
     *  自己再做 fuzzy match)。在獨立 thread (由 HttpServer 的
     *  newCachedThreadPool 保證, 每個 HTTP request 已經在自己的 thread) 上執行
     *  blocking HttpURLConnection, 不在 UI thread 做, 安全性和
     *  xiaozhiVisionExplainRequest() 一致。 */
    private java.util.List<org.json.JSONObject> searchRadioStations(String query, int limit)
            throws java.io.IOException, org.json.JSONException {
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String urlStr = RADIO_BROWSER_API_HOST + "/json/stations/search?name=" + encodedQuery
                + "&order=random&reverse=true&hidebroken=true"
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

    /** 尋找一個人類語言的電台名 - 先在 lastRadioSearchResults (最近一次
     *  self.media.search_radio/self.media.play_radio 觸發的搜尋結果) 裡做精確/
     *  substring 比對, 找不到才把這個 query 本身當成一個新的搜尋詞、再打一次
     *  Radio Browser API。這樣設計的原因: (1) LLM 常常會先 search_radio 取得
     *  幾個候選再由用戶或自己選一個名, 這種情況應該從已有的結果裡選,
     *  不應該重新打 API (慢、也可能因為 order=votes 的隨機性選到別的台); (2) 如果
     *  LLM 或用戶直接只說一個電台名 (例如 "播BBC")、之前又沒搜過, 這個
     *  method 也應該自己處理好, 不用逼 LLM 一定要分兩步做。找不到就回傳 null -
     *  和 resolveActionId()/resolveLocalMusicFile() 一致的「信心不足就說找不到,
     *  不亂猜」原則。 */
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

        // Cache 裡找不到 (或者根本沒搜過) - 把這個 query 當成新搜尋詞, 打一次
        // Radio Browser, 選第一個不是 HLS 的結果 (見 searchRadioStations()
        // javadoc: HLS 在舊版 MediaPlayer 支援不穩定, 直接跳過, 不盲目選
        // fresh.get(0) - 如果 list 裡只有 HLS 台, 就寧願全部選過都選
        // 不到、退而求其次選 fresh.get(0), 好過什麼都播不了)。
        java.util.List<org.json.JSONObject> fresh = searchRadioStations(q, 30);
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

    /** 在獨立 thread 上選一個隨機動作 (resolveRandomActionId()) 並播放, fire-and
     *  -forget、不理會成功失敗、不 block caller - 抽出來做共用 helper, 供 TTS
     *  "start" event (setTtsStateListener() 那段) 和 self.media.play_music 一起使用,
     *  兩者想要的是完全同一種「動一下讓機器人看起來生動一點」效果, 沒必要各自開一份
     *  幾乎一樣的 new Thread(...) { ... }.start()。不在 WebSocket read loop
     *  thread/HTTP worker thread 上直接呼叫 AIDL blocking call, 和
     *  reassertHeadEyeLed() 一致的安全做法。 */
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

    /** MCP tool enable/disable 設定的讀寫 helper - 逗號分隔的 disabled tool name
     *  清單, 存在 PREFS_NAME 這個共用 SharedPreferences (和 OTA custom 設定用
     *  同一個, 不另開一個 file)。isMcpToolEnabled() 供 listTools()/callTool()
     *  共用: listTools() 用來過濾哪些 tool 出現在回應中, callTool() 用來擋下一個
     *  已經 disabled 但 LLM 手上還持有舊 tool 清單、嘗試照樣呼叫的情況
     *  (單靠 listTools() 側過濾不夠, LLM 快取了上一次的清單就繞得過去)。
     *  2026-08 更新: UI 側移除了「開放 MCP 工具給小智使用」總開關 - 這台機器現在
     *  永遠對外暴露 MCP 工具 (逐項 enable/disable 不變), isMcpEnabled() 恆常
     *  回傳 true。PREF_XIAOZHI_MCP_ENABLED 這個 pref key 保留在常數和
     *  mcp_config/set 的寫入路徑裡沒有拆掉, 純粹是為了相容舊有經由 query string
     *  直接打 API 的呼叫方式, 但不會再影響實際行為。 */
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
        // 只影響下一次 start(), 不會影響目前正在使用的其他 session (見
        // CameraController 的 requestedWidth/Height javadoc)。
        cameraController.setRequestedResolution(XIAOZHI_PHOTO_WIDTH, XIAOZHI_PHOTO_HEIGHT);
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return XiaozhiVisionResult.fail("camera start failed: " + started.error);
        }
        byte[] jpeg;
        try {
            // 2026-08 修正 (真正根源): 之前這裡用 waitForStableFrame() 取得 preview
            // stream 的 frame (見 CameraController 開頭段 comment - 這個 class 本身是
            // "continuous webcam-style streaming, NOT single-shot photos" 設計)。反編譯
            // 一個用戶提供、實測上傳成功的第三方 apk 之後發現: 它送去 server 的是用真正的
            // 單張拍攝 (CameraX ImageCapture, busy-wait 等待完成 callback), 不是 preview
            // frame - preview frame 沒有經過相機 HAL 完整的單張 AE/AF/降噪 pipeline。
            // 用戶已核實過 server 端存下的相片解析度都對 (480x360), 所以差異在於 capture
            // 方式本身, 不是 output size, 改用 CameraController.takePhoto() (Camera1
            // legacy API 的 camera.takePicture(), 見該 method javadoc) 做真正的單張
            // 拍攝, 取代 waitForStableFrame() 這個「等夠幀數迴避過渡期」的
            // workaround - takePicture() 本身已經是硬體執行的單張拍攝流程。
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
        // 2026-08 修正 (真正根源): 之前這裡的優先順序是「自訂設定 -> 寫死常數」,
        // 完全沒考慮 server 在 "initialize" MCP request 裡會附上真正的 vision
        // url/token (見 XiaozhiClient.getVisionUrl() 的 comment, 和官方
        // mcp-protocol.md 原文 "initialize" 章節) - 這個才是 404 的真正根源, 之前
        // 幾輪改的 scheme/domain 都是捕風捉影。現在優先順序改為: server 在
        // initialize 時告訴我們的 (最新鮮、最權威) -> 用戶手動填的自訂設定
        // (如果啟用了自訂 server 又沒收到 server 提供的 url) -> 寫死的
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
    // 2026-08 新增: 判斷一個字串「看起來像不像」真正的 UUID (標準格式:
    // 8-4-4-4-12 個 hex 字符, 用 "-" 分隔, 例如 vision/explain response 的
    // "776e1db5-092a-4045-9334-17ca15cfc781") - 用在 self.camera.image_to_text
    // 那個 case, 篩掉 LLM 沒讀取真 uuid、自己填了個佔位符字面值 (實測見過
    // "placeholder") 的情況, 見該 case 的 comment。刻意用寬鬆的 regex match
    // (不只是死板檢查是否等於 "placeholder"), 因為 LLM 用哪個字眼做佔位符本身
    // 不受控, 「格式對就信」好過「和已知字面值逐個比對」。
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
                        // 也是空的 - 這次沒有 message 可以再 relay 下去 (沒有下一層
                        // tool 可以呼叫), 直接把完整 raw response 當成
                        // error 帶回給 LLM/開發者, 讓看 logcat 的 "image_to_text
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
            // Object) overload 不會真的 throw (value 本身沒問題), 但它們簽名有
            // 宣告 throws JSONException, 純粹補上這個 catch 通過 javac 的 checked
            // exception 檢查, 不代表這裡預期會撞到。
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
     *  2026-08 修正: 反編譯一個用戶提供、實測拍照成功的第三方 apk (package
     *  com.huihongcloud.xiaozhi) 的實際 multipart 組裝邏輯 (Lcom/huihongcloud/
     *  xiaozhi/D;->a bytecode), 發現兩個之前這裡沒跟上的細節:
     *  (1) 它送出的 Client-Id header 之前完全沒有加 (這裡之前的 comment 早就說了
     *      「和 WebSocket 一樣的 Device-Id/Client-Id/Authorization」但實際沒有做);
     *  (2) 它的 multipart body 開頭多了一個 "type" part, 值是 "multipart" (在
     *      "question" part 之前) - 這個沒有出現在官方 esp32_camera.cc 文件化的欄位裡
     *      提到, 但實測的 apk 確實有加, 保守起見跟隨, 避免現在依賴中的 server
     *      side 有隱藏檢查依賴呢個欄位。 */
    private XiaozhiVisionResult xiaozhiVisionExplainRequest(String urlStr, String deviceId,
            String clientId,
            String accessToken, byte[] jpeg, String question) throws java.io.IOException {
        // 2026-08 修正: 之前用動態 "----OpenAlpha2Boundary<timestamp>" boundary -
        // 反編譯用戶提供、實測上傳成功的第三方 apk (package com.huihongcloud.xiaozhi)
        // 之後發現, 它的 multipart body 結構 (type/question/file 三個 part, field
        // name、"camera.jpg" filename) 和這裡已經一致, 但它用的是一個固定字串
        // boundary "----ESP32_CAMERA_BOUNDARY" - 這正是官方 esp32-camera.cc
        // firmware 用的 boundary, 這個第三方 apk 特意完全遵照官方寫死這個字串, 不是隨機
        // 生成。用戶已核實同一個帳戶/官方 server 用第三方 apk 一直成功, 我們一直撞到
        // server 說「請呼叫 image_to_text」這個 fallback - 兩者 request body 結構
        // 一致的情況下, 這個 boundary 是目前找到的唯一實質差異, 懷疑 server 側的
        // multipart parser 或前置關卡對這個固定字串有特殊 / 白名單處理, 用來識別
        // 「這是合法的相機上傳」, 動態 boundary 反而被判去了一條 fallback 路徑。
        // 沿用這個固定字串, 不再自己動態生成。
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
                // firmware 本身打同一條 URL 是可行的 (見 GitHub issue #708 的實測
                // log), 所以 404 不是 URL 打錯, 而是這個帳戶/agent 在 xiaozhi.me
                // console 裡尚未開通 vision/camera 這個 MCP 服務 - 沒開通的帳戶,
                // api.xiaozhi.me 這邊的 routing 層面根本沒有這條路由, 對所有 request
                // 都是 404, 不會有更詳細的「未授權」訊息。這裡把這個已知原因直接
                // 告訴 LLM/用戶, 不用下次再從零開始查一次。
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
                // MCP tool 回應的 text 一直是空字串 - 也就是 json.optBoolean("success")
                // 走到 true 那邊, 但 json.optString("text","") 拿不到東西。之前一直沒有
                // log 印出完整 raw response body, 只會說「是否 success」, 不知道
                // server 實際還有哪些欄位。這次印出來, 下次一 fail/text 空就可以直接
                // 對照真正的 server JSON 結構來修, 不用再靠猜。
                android.util.Log.i("XiaozhiVision", "vision/explain raw response: " + responseText);
                if (json.optBoolean("success", false)) {
                    String text = json.optString("text", "");
                    if (text.isEmpty()) {
                        // "text" 這層拿不到, 試幾種常見的巢狀結構 fallback -
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
                        // 2026-08 新增 (真正根源): 實測 (4 次) 得出的真正 server 行為 -
                        // 帳戶用 GPT-5 做 LLM provider 時, vision/explain 不會立即回覆
                        // description, 而是回應
                        // {"success":true,"uuid":"...","message":"Please call the
                        // tool `image_to_text` to explain the image, then reply to
                        // the user"} - 也就是說這個 explain 是異步的, 真正描述要由 LLM
                        // agent 自己在對話裡主動再發一次 MCP tools/call 去呼叫
                        // "image_to_text" 這個 tool (未在官方 mcp-protocol.md 記載,
                        // 屬於 xiaozhi.me console 這個特定 agent/GPT-5 組合才有的行為)
                        // 才能取得。之前這裡把 text 空字串直接當成功 (見上面
                        // XiaozhiVisionResult.ok(text)), 使 LLM 收到的 MCP result 是
                        // 完全空白的 text, 完全沒提示它要再呼叫哪個 tool, 對話就此
                        // 卡死, 4 次都是這個 pattern。修正: 這種情況不算失敗, 把
                        // server 的 "message" (LLM 看得懂的指示) 原文當成這次
                        // self.camera.take_photo 的 result 文字傳回給 LLM - 讓 LLM
                        // 自己讀到這句話, 主動再發 tools/call 去呼叫
                        // "image_to_text" (device 這邊已加入這個 tool 的
                        // 註冊/處理, 見 buildMcpToolsList() 和 callTool() 的
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

                // 2026-08 修正: 之前 "name" 要求 LLM 傳回 self.robot.list_actions 的
                // id (一串沒有語意的 timestamp 數字), 但實測小智完全不遵守這個指示,
                // 純粹憑印象亂編一個 id (見下面 play_action tool description 的
                // 詳細 comment)。現在 "name" 改為接受人類可讀的中文/英文動作名,
                // 由 callTool 的 self.robot.play_action case 做 fuzzy match 轉成真正
                // id - 這裡不再需要把 202 個 id 全塞進 enum。
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
                // self.robot.list_actions 的 id (一串沒有語意的 timestamp 數字), 但
                // 實測小智完全不遵守這個指示 - 它從來沒呼叫過 list_actions, 純粹憑
                // "印象" 亂編一個 id (實測見過叫它舉左手, 它傳了 "1464835936031",
                // 實際是「向後走」那個 id - 完全動錯了)。這不是 enum 沒約束合法值的
                // 問題 (enum 確保了傳過來的一定是真實存在的檔案, 不會再撞上
                // "開不了檔案" 那種崩潰), 而是 LLM 面對一堆完全沒語意的純數字 id,
                // 根本記不住哪個 id 對應哪個動作, 就算 description 再怎麼強調
                // "call list_actions first" 也沒用。
                //
                // 現在做法: "name" 改為接受人類可讀的中文或英文動作名 (例如
                // "舉左手" 或 "take left hand"), 由這裡 (callTool 的
                // self.robot.play_action case) 做 fuzzy match 轉成真正的 id 再傳給
                // action_PlayActionName() - 詳見 resolveActionId()。LLM 不用再記
                // id, 只要說出自己生成的語意名就好, 大幅減低選錯的機會。
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

                // 2026-08 新增: 骨架先行, 選哪個動作只靠隨機 (見
                // resolveRandomActionId()), 還沒做任何 emotion-to-action 對應 - 那部分
                // 之後再做。這個 tool 存在的意義是讓 LLM 自己判斷「這一刻適不適合
                // 加個動作看起來生動一點」, 不是跟著 emotion 字段機械式觸發 (每句對話
                // 都附帶 emotion 字段, 如果 client 側看到就自動播放, 會太頻繁太吵) - 主導
                // 權留在 LLM 側, 由它自己決定何時呼叫。
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
                // 這個 bridge 已有的 pattern (schema 用 org.json 組建, 執行時直接呼叫
                // robot.xxx() 的 AIDL wrapper, 有 waitXxxReady() 就跟現有 HTTP API
                // case 一樣加上) - 詳細參數含義/已驗證行為見 AIDL_REFERENCE.md 和
                // handleApi() 裡對應的 "servo/*"、"led/*"、"pir/*" case (這些 MCP
                // tool 純粹是那些 case 的薄包裝, 沒有重複定義邏輯)。

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

                // 沿用官方 xiaozhi-esp32 firmware 的 self.camera.take_photo 命名/協議
                // 形狀 (見 esp32_camera.cc 的 Explain() 實作): 拍一張相, 用 multipart
                // HTTP POST 去 vision/explain endpoint (JPEG + question), server 回傳
                // {"success":true,"text":"..."} 的圖片描述文字, 由 LLM 讀出來。相片
                // 不會經由 MCP JSONRPC result 直接塞入 image content (這個 xiaozhi 協議
                // 不支援) - explain 完全在 device <-> vision endpoint 之間進行, MCP tool
                // 只取回一段描述文字。解析度固定 480x360 (用戶指定, 比官方範例的
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
                // 實測 (見 xiaozhiVisionExplainRequest() 的詳細 comment) 不會立即
                // 回覆相片描述, 而是先回一個 {"success":true,"uuid":"...",
                // "message":"Please call the tool `image_to_text` ..."} - 也就是說
                // server 期望 LLM 自己懂得再發一次 MCP tools/call 去呼叫這個
                // "image_to_text" tool 才能拿到真正描述。之前 device 這邊沒有註冊過
                // 這個 tool, 使 GPT-5 就算依指示想呼叫也沒有這個 tool 可以呼叫,
                // 對話卡死, 4 次都是這個情況。這個 tool 名/形狀屬於 xiaozhi.me
                // console 這個特定 agent/GPT-5 組合才有的非官方行為 (官方
                // mcp-protocol.md 完全沒記載), 沿用 server 訊息原文用的名字
                // "image_to_text", 掛在 self.camera 底下和 take_photo 同一個
                // namespace。inputSchema 沒有強制要求 uuid (LLM 可能會/不會帶),
                // device 這邊會用 lastPendingPhotoUuid 做 fallback 核對, 見
                // callTool() 的 "self.camera.image_to_text" case。
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
                // listLocalMusicFiles()/resolveLocalMusicFile() 的 javadoc) - 沿用
                // self.robot.play_action 那套「人類語言名 + fuzzy match」做法, 不用
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
                // searchRadioStations()/resolveRadioStation() 的 javadoc, 這台機器
                // 不再內建任何寫死的電台清單) - self.media.list_radio 換成了
                // self.media.search_radio (搜尋型 API 拿不到「全部」電台, 只
                // 「search_radio 先取得候選、play_radio 再選播」這個 flow 才合理)。
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
                // 2026-08 新增: MCP 設定 card 的 enable/disable 在這裡一次性生效 -
                // 整個 tools array 已經全部組好了 (上面全部 tools.put(...)), 這裡過濾
                // 一次就夠, 不用逐個 tools.put() 前面加 if, 減少改動、不用擔心漏了
                // 哪一個。總開關關閉就回傳完全空的 tools array (等於告訴 LLM「這台
                // 機器現在沒有任何工具」); 開啟就逐一取得個別 tool 的 enabled 狀態
                // 過濾。見 isMcpToolEnabled()/getMcpDisabledToolNames() 的 comment。
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
                // 2026-08 新增: MCP 設定 card 要顯示全部 tool (含已經 disable 的
                // ), 讓用戶可以按按鈕重新 enable - 但上面 filteredTools 已經是
                // 過濾完的, 傳給 XiaoZhi server 的那份不會再帶著 disabled 的 tool。
                // 這裡把未過濾的完整版本 (tools, 建好全部 tool 的原始 array) 存下
                // 做 instance field, 讓 "mcp_tools/list" 這個 HTTP endpoint (純粹供
                // 前端 card 顯示用) 可以獨立讀到完整清單, 不用搬動/複製這整段
                // 建立 tools array 的邏輯。
                lastFullMcpToolList = tools;
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("tools", filteredTools);
                return result;
            }

            @Override
            public org.json.JSONObject callTool(String name, org.json.JSONObject arguments) throws org.json.JSONException {
                boolean isError = false;
                String resultText = "";
                // 2026-08 新增: 單靠 listTools() 側過濾不夠 - LLM 可能還拿著上一次
                // (disable 之前) 取得的 tool 清單, 照樣試著呼叫一個現在已經 disabled
                // 的 tool name, 這裡多做一重防護。和 listTools() 用同一套
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
                            // 2026-08 修正: 小智傳過來的是人類語言的動作名 (中文/英文,
                            // 不再是要它自己記住的 id, 見 listTools() 的
                            // self.robot.play_action description comment) - 這裡做
                            // fuzzy match 找出真正對應機身檔案的 id, 再傳給
                            // action_PlayActionName()。找不到就直接告訴 LLM 哪個名
                            // 找不到, 讓它有機會呼叫 self.robot.list_actions 再試,
                            // 而不是盲目把 LLM 編的名直接傳給 AIDL (會撞回
                            // "raise_left_hand" 那種開不了檔案的老問題)。
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
                            // 見 "action/stop" endpoint 那段 comment - 停止之後補一個
                            // 「蹲下站起」做回位, 和 HTTP API 那邊行為保持一致。
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
                        // 薄包裝, 邏輯全部委託給 handleApi() 已有的 "servo/*"、
                        // "led/*"、"pir/*" case 使用的那些 Alpha2RobotApi 方法, 見
                        // AIDL_REFERENCE.md 相關章節和 handleApi() 的 comment 取得完整
                        // 已驗證行為/參數語意, 這裡不重複解釋。
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
                                cancelHeadLedReassert(); // 讓持續補發的 background thread 停止, 不要再蓋過用戶想要的「熄燈」
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
                                // 「stop ear led」邏輯本身會持續循環用自己的固定參數
                                // 蓋掉我們設定的顏色, 這裡要持續補發直到用戶下一次改指令
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
                                cancelEyeLedReassert(); // 讓持續補發的 background thread 停止, 不要再蓋過用戶想要的「熄燈」
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
                            // 見 buildMcpToolsList() 這個 tool 定義那段 comment 和
                            // xiaozhiVisionExplainRequest() 裡 "vision/explain is async"
                            // 那段 comment。實測 (2026-08) 不只是 GPT-5, Qwen 3.6 一樣會
                            // 撞到這個 async flow, 更正一下 comment - 不是哪個 LLM provider
                            // 才有的行為, 看起來是 xiaozhi.me console 目前整個 vision/explain
                            // 後端行為, 和用哪個 model 無關。
                            String uuid = arguments.optString("uuid", "");
                            // 2026-08 新增 (真正根源): 用戶提供的 console 截圖 + logcat 顯示
                            // LLM 一直都有帶 uuid 過來, 但帶的是字面值 "placeholder"
                            // (也就是 LLM 沒有真正讀取之前 take_photo response 裡的
                            // uuid, 純粹把 inputSchema 的 "uuid" 這個字, 當成一個
                            // 佔位符字面值填了進去) - 之前只有 check uuid.isEmpty() 這個
                            // fallback 條件, "placeholder" 不是空字串, 完全沒觸發到, 就
                            // 拿著這個假 uuid 去打 image_to_text, 難怪 server 500。改用
                            // 一個寬鬆的「看起來像不像真 UUID」檢查 (標準 UUID: 8-4-4-4-12
                            // 個 hex 字符, 用 "-" 分隔) - 不像就當 LLM 沒帶真的 uuid,
                            // 一樣 fallback 用 device 自己記下的 lastPendingPhotoUuid,
                            // 不理會 LLM 說的字面值是什麼 (無論是 "placeholder"、空字串,
                            // 或是之後可能出現的其他佔位符寫法都一樣處理)。
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
                                // 2026-08 新增 (暫時 fallback): 這個 image_to_text 的
                                // 真正 request payload 格式尚未經 xiaozhi.me 官方證實
                                // (見 xiaozhiFetchImageToText() javadoc), 實測撞到
                                // HTTP 500。在官方 protocol 尚未確認之前, 不要把
                                // "image_to_text returned HTTP 500: ..." 這類技術性
                                // error 原文當成 isError:true 帶給 LLM - 這樣會讓 LLM
                                // 讀出很突兀的技術錯誤給用戶聽。改為 isError:false
                                // + 一句自然說法, 讓對話至少有合理回應, 不會斷崖式
                                // 失敗。原始 error 已經有 log (見 xiaozhiFetchImageToText()
                                // 裡的 "image_to_text raw response" log), 留給
                                // 之後對照 payload 格式用, 不用靠這句 resultText。
                                Log.w("XiaozhiVision", "image_to_text follow-up failed, "
                                        + "using fallback reply: " + imgResult.error);
                                resultText = "拍到照片了，不過現在還看不到照片裡面的內容，晚點可能才答得出來。";
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
                        // audio/local_music/* 那幾個 HTTP endpoint 共用同一批 method),
                        // 不在這裡重複實現。
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
                        // 那幾個 HTTP endpoint 共用同一批 method), 不在這裡重複實現。
                        // searchRadioStations()/resolveRadioStation() 拋出的
                        // IOException/JSONException (網路逾時、Radio Browser
                        // 服務暫時不穩定等) 由外層那個 try/catch (Exception e) 接住,
                        // 不用在這裡重複處理。
                        case "self.media.search_radio": {
                            String searchQuery = arguments.optString("query", "");
                            if (searchQuery.isEmpty()) {
                                isError = true;
                                resultText = "missing required argument: query";
                                break;
                            }
                            java.util.List<org.json.JSONObject> found =
                                    searchRadioStations(searchQuery, 30);
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

    // -- CPU 使用率 (2026-08 v2 新增, /api/status 用) -----------------------------
    // 讀 /proc/stat 第一行 (user/nice/system/idle/iowait/irq/softirq/steal),
    // 同上次取樣計 delta -> 使用率 %。兩次 call 至少隔 CPU_SAMPLE_MIN_GAP_MS 先
    // 會重新取樣, 中間重複 poll 就回用上一次計算好的值 - 不用每次都等夠窗口。
    private static final long CPU_SAMPLE_MIN_GAP_MS = 500;
    private final Object cpuSampleLock = new Object();
    private long[] lastCpuTick;      // [0]=總 ticks, [1]=idle+iowait ticks
    private long lastCpuTickAtMs = 0;
    private double lastCpuPercent = -1;

    /** 回傳 "cpuPercent":<value> JSON 片段; 尚未有足夠數據時回傳 null。 */
    private String cpuUsageJson() {
        synchronized (cpuSampleLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            long[] cur = readCpuTicks();
            if (cur == null) {
                return "\"cpuPercent\":null";
            }
            boolean haveGap = lastCpuTick != null && (now - lastCpuTickAtMs) >= CPU_SAMPLE_MIN_GAP_MS;
            if (lastCpuTick == null) {
                // 第一次 call: 存基準, 等一個短窗口再取第二次, 等第一次就有值。
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastCpuTick = cur;
                    lastCpuTickAtMs = now;
                    return "\"cpuPercent\":null";
                }
                long[] cur2 = readCpuTicks();
                if (cur2 != null) {
                    lastCpuPercent = computeCpuPercent(cur, cur2);
                }
                lastCpuTick = cur2 != null ? cur2 : cur;
                lastCpuTickAtMs = android.os.SystemClock.elapsedRealtime();
            } else if (haveGap) {
                lastCpuPercent = computeCpuPercent(lastCpuTick, cur);
                lastCpuTick = cur;
                lastCpuTickAtMs = now;
            }
            // else: 間隔未夠, 沿用 lastCpuPercent。
            if (lastCpuPercent < 0) {
                return "\"cpuPercent\":null";
            }
            return "\"cpuPercent\":" + String.format(java.util.Locale.US, "%.1f",
                    Math.max(0.0, Math.min(100.0, lastCpuPercent)));
        }
    }

    /** 兩個取樣點之間的使用率 (%) = (totalDelta - idleDelta) / totalDelta。 */
    private static double computeCpuPercent(long[] from, long[] to) {
        long totalDelta = to[0] - from[0];
        long idleDelta = to[1] - from[1];
        if (totalDelta <= 0) return -1;
        return (double) (totalDelta - idleDelta) * 100.0 / (double) totalDelta;
    }

    /** 讀 /proc/stat 第一行, 回 {總ticks, idle(+iowait)ticks}, 失敗回 null。 */
    private static long[] readCpuTicks() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            String line = reader.readLine(); // "cpu  user nice system idle iowait irq softirq steal ..."
            if (line == null || !line.startsWith("cpu")) return null;
            String[] parts = line.trim().split("\\s+");
            long total = 0;
            long idle = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4) idle += v;              // idle
                if (i == 5) idle += v;              // iowait 都算閒置
            }
            return new long[]{total, idle};
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
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
                        + "\"androidTtsReady\":" + androidTtsReady + ","
                        + cpuUsageJson() + "}");

            case "chest/version": {
                // 只回 chest MCU 真實韌體版本 (sendCommand 51)
                long timeoutMs = 1500;
                try { timeoutMs = Long.parseLong(queryOrDefault(query, "timeout", "1500")); } catch (Exception ignored) {}
                String v = queryChestFirmwareVersion(timeoutMs);
                if (v != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(v) + "\"}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"version\":\"not found\"}");
                }
            }
            case "chest/upgrade": {
                // 觸發胸口升級：讀 /sdcard/AlphaII_CHEST_kernel.bin 經 48/49/50 協議升級
                String err = startChestUpgrade();
                if (err == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"started\":true}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(err) + "\"}");
                }
            }
            case "chest/upgrade/status": {
                return HttpServer.ApiResponse.ok("{\"ok\":true," + getChestUpgradeStatusJson().substring(1));
            }
            case "chest/upgrade/resume": {
                int from = 0;
                try { from = Integer.parseInt(queryOrDefault(query, "from", "0")); } catch (Exception ignored) {}
                String err = startChestUpgradeFrom(from);
                if (err == null) return HttpServer.ApiResponse.ok("{\"ok\":true,\"resumed\":true,\"from\":"+from+"}");
                else return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(err) + "\"}");
            }
            case "chest/upgrade/abort": {
                resetChestUpgradeState();
                chestUpgradeInProgress = false;
                chestUpgradeStatus = "aborted";
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"aborted\":true}");
            }
            case "chest/page": {
                // 調試：讀指定頁 offset 的 32B hex，用於定位 170 頁這類點
                int page = 0;
                try { page = Integer.parseInt(require(query, "page")); } catch (Exception e) { return HttpServer.ApiResponse.error("page required"); }
                java.io.File f = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
                if (!f.exists()) return HttpServer.ApiResponse.error("file not found");
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    long skip = (long)page * 128L;
                    long s = 0;
                    while (s < skip) { long n = in.skip(skip - s); if (n<=0) break; s+=n; }
                    byte[] buf = new byte[128];
                    int n = in.read(buf);
                    if (n <= 0) return HttpServer.ApiResponse.error("page out of range");
                    String hex = toHex(buf, n);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"page\":"+page+",\"offset\":"+skip+",\"hex\":\""+hex+"\"}");
                } catch (Exception e) { return HttpServer.ApiResponse.error(String.valueOf(e.getMessage())); }
            }


            // -- Actions --------------------------------------------------------------
            case "action/list":
                return actionList();
            case "action/play":
                return codeResponse(robot.action_PlayActionName(require(query, "name")));
            case "action/stop": {
                // 2026-08 新增: 用戶要求「停止」要連帶做返「蹲下站起」呢個回位動作
                // (action_StopAction() 本身純粹截停正在播放的動作, 不會自動站回
                // 安全站立姿勢) - 停止之後主動播放 STOP_RECOVERY_ACTION_ID
                // (蹲下站起) 做回位。停止本身的 result code 照舊做回傳值 (回位動作
                // 播不播得了, 不應該影響「停止」這個操作本身是否算成功)。
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
                    // 語言選擇 - lang 是 speech/tts_languages 回傳的 BCP-47
                    // tag (例如 "zh-HK"/"en-US"), null/留空就沿用 engine 目前
                    // 已經生效的語言, 不強行切換。LANG_MISSING_DATA/
                    // LANG_NOT_SUPPORTED 都是負數, 只有 engine 真的接受了才
                    // 繼續讀, 否則報錯回去, 不要悄悄用原本的語言讀 (不是用戶
                    // 要求的結果)。
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
            // 兩個 AIDL engine 沒有語言參數選擇, lang 已經由 engine 本身固定死,
            // 見下面 speech/tts 的 android 分支)。ui_lang ("zh"/"en") 控制的是
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

            // Android TTS 引擎選擇 - 機身可能裝了不只一個系統 TTS 引擎 (例如出廠
            // 內建 + Google TTS + SVOX Pico), 這三個 endpoint 供 speech tab 選擇
            // speech/tts 的 engine=android 分支實際用哪個發音, 不涉及 Nuance/
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
                // 這裡的 "ok" 只是說已經觸發了切換, 不代表立即可以講話, 前端
                // 應該延遲少少先再 poll speech/cur_tts_engine。
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            case "speech/cur_tts_engine":
                return HttpServer.ApiResponse.ok(
                        "{\"ok\":true,\"engine\":\"" + jsonSafe(androidTtsEnginePkg) + "\"}");

            case "speech/set_mic": {
                boolean wake = Boolean.parseBoolean(require(query, "wake"));
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
                // 2026-08 新增: 試驗性的「重置」入口。實測 (logcat_2026-08-27_05-39-04.txt)
                // 證實: 按了上面 speech/set_asr_engine 之後, 機身系統進程
                // (com.ubtechinc.alpha2services) 裡的 TTS session 就會啞掉 —— HTTP
                // 層仍然回傳 200 API_ERROR_SUCCEED, 但完全看不到 SpeechServiceImpl/
                // IflytekTTS/onTTsStart 這些 log, 一直要重開機才能恢復正常。
                //
                // 這個 endpoint 呼叫 AIDL transaction #12 stopSpeechAndEnterIdleMode(),
                // 看能不能喚醒那個死掉的 session, 不用讓整台機器重開機。特意用
                // Alpha2RobotApi.speech_resetToIdle() (走 generic alias binding), 而不是
                // 走那條已經壞死的 direct-engine binding。尚未在實機驗證過這個方法是否
                // 真的解決得了問題, 純粹依 AIDL 方法名和用途做的合理推測 —— 如果無效,
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
                // 2026-08 修正: 這是整個 API 裡觸發 ASR 最主要的入口 (見
                // AIDL_REFERENCE_ALPHA2.md 1.1 - startSpeechNoWakeup 才是真正可靠的「開始聆聽」
                // 方法), 但之前一直沒有像 speech/inject/speech/stop_inject 那樣加上
                // speechReady gate。也就是說剛切換完 ASR engine (speechReady 短暫變成
                // false, 等待 onSpeechInitSuccess) 的時候撞上按這個 endpoint, 會拿到
                // 和 speech/inject 那種一樣含糊的 API_ERROR_NOT_INIT, 而不是清晰的
                // 錯誤訊息。現在補上這個 gate, 和 speech/inject 看齊。
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
                // 2026-08 新增: 之前這裡沒有 speechReady gate, 如果剛切換完 engine
                // (speechReady 短暫變回 false, 等待 onSpeechInitSuccess callback)
                // 就直接進到 SDK call, 很有可能兩個 util 都還是 null, 拿到含糊的
                // API_ERROR_NOT_INIT, 而不是像 speech/reset 那樣清晰的錯誤訊息。
                // 現在加上這個 gate, 和 speech/init_grammar 那種做法看齊。
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_language) before calling speech/inject.");
                }
                return codeResponse(robot.speech_startRecognized(require(query, "text")));
            case "speech/iflytek_simulate":
                // 2026-08 新增: "打字當作自己說了這句" - 直接把輸入文字當成 iFlytek
                // 引擎已經辨識完的結果, 送去 handleIflytekSemanticText() 做 1000 條
                // 問法配對 (中英文各 1000 條, 依輸入文字有沒有漢字自動判斷用哪份 - 見
                // looksChinese()), 命中就立即執行悠聊原本的「TTS200ms動作」流程。
                // 和 speech/inject 不同: 這裡不經任何機身 AIDL (不靠
                // speech_startRecognized()/onSpeech() 這條 "不確定會不會真的觸發辨識"
                // 的路), 純粹是本地 JSON 配對 + 直接呼叫 robot.speech_startTTS()/
                // robot.action_PlayActionName(), 所以不需要 speechReady gate, 只
                // 需要 robot 本身已經 initRobot() 完成 (onCreate() 一開始就做了)。
                // response 即時告訴前端有沒有配對到 (matched/question/type/
                // operation/answer/actionId), 不用等 WebSocket event - 方便對話
                // 界面直接顯示配對結果, 不用一直等 EventBus。
                //
                // 2026-08 新增: match() 現在找不到問法也會回傳一個「聽不懂」的
                // fallback 回應 (不再是 null), 所以 matched:false 分支現在只
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
            // 2026-08 重新加入 speech/init_grammar、speech/start_grammar、
            // speech/stop_grammar 三個 endpoint (2026-08 之前曾經因為「同一句話
            // 經 grammar_result 同 asr_result 兩條路徑各自觸發語意配對, 重複答兩次」
            // 而全線移除)。現在重新設計過:
            //
            // 1. 開啟離線文法模式之後 (speech/start_grammar), onServerCallBack()
            //    那條聽寫路徑會被 offlineGrammarActive flag gate 住 - 只有 grammar
            //    listener 一條路徑會觸發語意配對 + TTS + 動作, 徹底解決重複回應。
            // 2. 反編譯 alpha2services (v1.1.7.3.20) 證實: iFlytek 引擎實作
            //    (com.ubtechinc.speechmanager.a.a) 本身就有完整的本地文法支援 -
            //    initSpeechGrammar() 收到 BNF 字串之後用 engine_type=local +
            //    assets/asr/common.jet (APK 自帶離線資源) 執行 buildGrammar("bnf",...),
            //    startSpeechGrammar() 用 mix 模式啟動 (連上網走雲端, 離線自動退回
            //    local_grammar="call" 本地文法), 辨識全程不用網路。這就是讓
            //    iFlytek 離線可用的正確做法。
            // 3. BNF 格式是 iFlytek IAMVERSION 1.1.0 (#BNF+IAMVERSION 開頭,
            //    !slot 宣告, <grammarstart> 做 root rule)。格式錯的話 buildGrammar
            //    會經 GrammarListener 回錯誤碼, grammar_init event 會帶埋 errorCode。
            case "speech/get_default_grammar":
                return getDefaultGrammar();
            case "speech/init_grammar": {
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_language) before calling speech/init_grammar.");
                }
                String bnf = queryOrDefault(query, "bnf", "");
                if (bnf.isEmpty()) {
                    bnf = readDefaultGrammarAsset();
                    if (bnf == null) {
                        return HttpServer.ApiResponse.error(
                                "No 'bnf' param given and assets/iflytek/default_grammar.bnf unreadable");
                    }
                }
                return codeResponse(doInitGrammar(bnf));
            }
            case "speech/start_grammar": {
                if (!speechReady) {
                    return HttpServer.ApiResponse.error(
                            "Speech API not ready yet - wait for the \"speech_ready\" event "
                                    + "(e.g. right after speech/set_language) before calling speech/start_grammar.");
                }
                // 2026-08 新增: 文法尚未構建成功 (或者根本沒 init 過) 就不允許開始 -
                // 這個狀態下機身會把所有語音退回雲端 fallback, 離線時全部變成網路
                // 錯誤 (10114/20002), 用戶會以為離線功能壞了。要求先 init 成功。
                if (!lastGrammarBuildOk) {
                    return HttpServer.ApiResponse.error(
                            "Grammar not built yet (or last build failed with error 23300 = wrong "
                                    + "BNF format). Press 'Init grammar' first and wait for a "
                                    + "grammar_init event with errorCode 0. Correct format: "
                                    + "'#BNF+IAT 1.0 UTF-8;' header + !grammar/!slot/!start "
                                    + "directives - see the default template.");
                }
                return codeResponse(doStartGrammar());
            }
            case "speech/stop_grammar": {
                return codeResponse(doStopGrammar());
            }
            case "speech/offline_auto_switch": {
                // 2026-08 新增: 自動跟網路切換開關。沒有 on 參數 = 查詢現狀;
                // 有 on=true/false = 設定 (寫入 SharedPreferences, 重啟 App 都記得),
                // 設定完即刻按目前網絡狀態套用一次。
                String onParam = queryOrDefault(query, "on", "");
                if (!onParam.isEmpty()) {
                    boolean on = Boolean.parseBoolean(onParam);
                    offlineGrammarAutoSwitch = on;
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(PREF_OFFLINE_AUTO, on).commit();
                    // 立即在背景 probe 一次並套用 - 不用等下一個 30 秒週期。
                    // join 最多 10 秒等探測完才回應, 讓回應的 connected/offlineActive
                    // 是新鮮結果而不是上一輪的殘值。
                    Thread probeThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean online = hasRealInternet();
                            lastProbeOnline = online;
                            applyConnectivityMode(online, "toggle");
                        }
                    }, "conn-probe-toggle");
                    probeThread.start();
                    try {
                        probeThread.join(10000);
                    } catch (InterruptedException ignored) {
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"auto\":"
                        + offlineGrammarAutoSwitch + ",\"connected\":" + lastProbeOnline
                        + ",\"offlineActive\":" + offlineGrammarActive + "}");
            }
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
            // RobotEventReceiver/registerAlpha2PirAlertListener 的 comment)。
            case "pir/set": {
                robot.waitChestReady(3000);
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                return codeResponseReady(robot.chest_setPirSensorEnabled(enabled), robot.isChestReady());
            }

            /** 2026-08-15 新增: 獨立於 pir/set 呢個感應器硬件開關本身, 純粹控制
             *  「偵測到人就閃紅燈/響鈴」這個警示反應要不要開。已在實機確認 PIR 事件
             *  本身 (cmd=-109, "PIR HUMON DETECT") 會正常觸發 (見 RobotEventReceiver
             *  的 CHEST_ACTION case 裡面 alpha2_pir_state 那段 comment) - 這個
             *  endpoint 就是讓前端選擇要不要對這個事件有反應。 */
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

            case "debug/jni/led": {
                // 2026-08-25 新增: 直接試 /dev/led_eye 這個 JNI driver 的各個 native
                // function - 這塊 5-mic 板上眼/頭/嘴部 LED 全部走這條路, 兩顆 pad 燈
                // 很可能也是同一個 driver 另一個 ioctl (例如尚未用過的 ledSetOn(i))。
                // func=on&i=N -> ledSetOn(N); func=eye/head&a1..a8 -> 對應 setter。
                String func = queryOrDefault(query, "func", "");
                if ("off".equals(func)) {
                    boolean openOk = LedControl.open();
                    boolean r = LedControl.ledSetOFF();
                    LedControl.close();
                    Log.i(TAG, "ledSetOFF open=" + openOk + " raw=" + r);
                    return HttpServer.ApiResponse.ok(
                            "{\"open\":" + openOk + ",\"raw\":" + r + "}");
                }
                boolean openOk = LedControl.open();
                try {
                    if ("on".equals(func)) {
                        int i = Integer.parseInt(queryOrDefault(query, "i", "0"));
                        boolean r = LedControl.ledSetOn(i);
                        Log.i(TAG, "ledSetOn(" + i + ") open=" + openOk + " raw=" + r);
                        return HttpServer.ApiResponse.ok(
                                "{\"open\":" + openOk + ",\"raw\":" + r + "}");
                    }
                    int[] a = new int[8];
                    for (int k = 0; k < 8; k++) {
                        a[k] = Integer.parseInt(queryOrDefault(query, "a" + (k + 1), "0"));
                    }
                    boolean r;
                    if ("eye".equals(func)) {
                        r = LedControl.ledSetEye(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    } else if ("head".equals(func)) {
                        r = LedControl.ledSetHead(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
                    } else {
                        return HttpServer.ApiResponse.error("func must be on/eye/head");
                    }
                    Log.i(TAG, "ledSet" + func + " open=" + openOk
                            + " raw=" + r + " args=" + java.util.Arrays.toString(a));
                    return HttpServer.ApiResponse.ok("{\"open\":" + openOk
                            + ",\"raw\":" + r + ",\"args\":"
                            + java.util.Arrays.toString(a).replace(" ", "") + "}");
                } finally {
                    LedControl.close();
                }
            }

            case "debug/serial/send": {
                // 2026-08-25 新增: raw serial 發送測試端點, 用來反推音量鍵 LED 和
                // 胸口 mute 鍵 LED 的控制指令 (headboard v1.1 上 alpha2services v1.0
                // 協議不合, 只要它一動作 MCU 就不再自動點燈, 要自己 app 補上)。port=head
                // 走 header_sendRawData (ttyS3), port=chest 走 chest_sendRawData
                // (ttyS1); hex 是完整 wire frame (f8 ... ed), 我們在 PC 側組好再送出。
                String port = queryOrDefault(query, "port", "head");
                byte[] data = parseHexBytes(require(query, "hex"));
                UbxErrorCode.API_ERROR_CODE code = "chest".equals(port)
                        ? robot.chest_sendRawData(data)
                        : robot.header_sendRawData(data);
                Log.i(TAG, "debug/serial/send port=" + port + " hex=" + toHex(data, data.length)
                        + " -> " + code.name());
                return HttpServer.ApiResponse.ok("{\"ok\":"
                        + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + ",\"code\":\""
                        + code.name() + "\"}");
            }

            // -- Head / misc ---------------------------------------------------------------
            case "head/noise":
                return codeResponse(robot.header_setNoise(Boolean.parseBoolean(require(query, "on"))));
            case "misc/request_uuid":
                robot.requestRobotUUID();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            case "misc/set_uuid": {
                // 2026-08 v2 新增: 更改機械人 ID (chest EEPROM SN 欄位)。格式由
                // 實機逆向 + 實測確認: cmd=54 (0x36), payload = 新 SN 的 ASCII bytes
                // (寫幾多個 byte 就幾多個, 其餘補 0), wire frame
                // F8 8F <7+n> 00 00 36 <sn...> <sum> ED, sum=(len+0x36+Σsn)&0xFF。
                // 寫入後即刻 requestUUID 讀返驗證 (robot_uuid event 經 WS 更新 UI)。
                //
                // 2026-08 v3: 曾經誤以為亂碼尾巴代表 EEPROM 定長 32 bytes 沒有被完
                // 全覆寫, 一度改成把整個 payload padding 到 32 bytes 才寫 —— 這個
                // 方向錯了, 已經用實機 logcat 推翻: hex dump (CHEST_READ_SID_EEPROM
                // 回應幀 "f8 8f 28 01 00 37 00 42 41 ... 00 00...00 3c ed") 顯示
                // 讀出來的 payload 本身很乾淨 —— [flag byte] + 17 bytes SN ASCII +
                // 0x00 padding, 完全沒有非零垃圾。之所以那行 firmware 自己的 Java log
                // "serialNumber=BAF006UBT10000377<方塊亂碼>" 只是 logcat/String 把
                // 尾隨的 \0 null byte 渲染成不可見方塊字元的顯示效果, 不代表
                // EEPROM 真的有垃圾殘留。RobotEventReceiver.java 讀取時已經用
                // indexOf('\0') 切掉這些 padding, 不需要也不應該在寫入那邊自己
                // padding 到某個定長 —— 太長的 payload (例如 32 bytes) 反而會讓
                // firmware 把 len byte 也當大了, 讀出來的欄位長度也跟著變,
                // 造成完全不同的殘留問題 (見專案內部事故記錄:「全域清零反而有
                // 害」)。因此這裡保持 v2 原本的 [len byte]+SN, 沒有 terminator
                // 沒有 padding 的寫法, 這才是經實機驗證過的正確格式。
                String v = require(query, "value");
                byte[] sn = v.getBytes(StandardCharsets.US_ASCII);
                if (sn.length < 1 || sn.length > 31) {
                    return HttpServer.ApiResponse.ok(
                            "{\"ok\":false,\"error\":\"id must be 1-31 ascii chars\"}");
                }
                // payload 格式實測確認是 [長度byte] + SN ASCII bytes — 沒有
                // terminator 沒有 padding! 讀取幾個 byte 是依這個 len byte 決定 (正常機
                // 讀出來是乾乾淨淨 N 字元 + firmware 自己 EEPROM 欄位的 0x00
                // padding, 不會有非零尾隨 bytes)。
                // checksum 包 LEN byte (7 + payload 總長) + cmd + Σpayload。
                byte[] payload = new byte[sn.length + 1];
                payload[0] = (byte) sn.length;
                System.arraycopy(sn, 0, payload, 1, sn.length);
                int sum = (7 + payload.length + 54) & 0xFF;
                for (byte b : payload) sum = (sum + (b & 0xFF)) & 0xFF;
                byte[] frame = new byte[payload.length + 9];
                frame[0] = (byte) 0xF8;
                frame[1] = (byte) 0x8F;
                frame[2] = (byte) (7 + payload.length);
                frame[3] = 0x00;
                frame[4] = 0x00;
                frame[5] = 54;
                System.arraycopy(payload, 0, frame, 6, payload.length);
                frame[6 + payload.length] = (byte) sum;
                frame[7 + payload.length] = (byte) 0xED;
                UbxErrorCode.API_ERROR_CODE code = robot.chest_sendRawData(frame);
                Log.i(TAG, "set_uuid -> " + v + " (" + sn.length + "B) " + code.name());
                robot.requestRobotUUID();
                return HttpServer.ApiResponse.ok(
                        "{\"ok\":" + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + "}");
            }

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
            case "camera/snapshot_save": {
                // 齊 9 檔影相並存入 Android：可選 w/h，未提供則用當前 preview 解像度；存至 /sdcard/DCIM/Alpha2
                String wStr = query.get("w");
                String hStr = query.get("h");
                int reqW = 0, reqH = 0;
                boolean hasSize = false;
                if (wStr != null && hStr != null) {
                    try { reqW = Integer.parseInt(wStr); reqH = Integer.parseInt(hStr); hasSize = true; } catch (Exception ignored) {}
                }
                int prevW = cameraController.getPreviewWidth();
                int prevH = cameraController.getPreviewHeight();
                if (hasSize) {
                    cameraController.setRequestedResolution(reqW, reqH);
                    cameraController.forceStopAndWait(3000);
                }
                CameraController.StartResult started = cameraController.start(8000);
                if (started.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(started.error) + "\"}");
                }
                CameraController.Frame frame = waitForFrame(cameraController, 3000);
                if (frame == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"timed out waiting for frame\"}");
                }
                try {
                    java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
                    if (!dir.exists()) dir.mkdirs();
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
                    String name = "alpha2_" + frame.jpeg.length + "_" + cameraController.getPreviewWidth() + "x" + cameraController.getPreviewHeight() + "_" + ts + ".jpg";
                    // 若有指定尺寸，用指定尺寸命名更直觀
                    if (hasSize) name = "alpha2_" + reqW + "x" + reqH + "_" + ts + ".jpg";
                    java.io.File outFile = new java.io.File(dir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(frame.jpeg); }
                    // 同時觸發媒體掃描，讓相簿即時可見
                    try { sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
                    // 恢復之前解像度（若有切換）
                    if (hasSize && (prevW != reqW || prevH != reqH)) {
                        cameraController.setRequestedResolution(prevW, prevH);
                        cameraController.forceStopAndWait(2000);
                        // 不自動重開，讓前端按需再開，避免長時間佔用
                    }
                    String b64 = android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + cameraController.getPreviewWidth() + ",\"height\":" + cameraController.getPreviewHeight() + "}");
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(e.getMessage()) + "\"}");
                }
            }
            case "camera/take_photo_save": {
                // 真正單張拍攝（picture 尺寸，經 Camera.takePicture 完整 ISP），存入 Android
                String wStr = query.get("w");
                String hStr = query.get("h");
                int reqW = 0, reqH = 0;
                boolean hasSize = false;
                if (wStr != null && hStr != null) {
                    try { reqW = Integer.parseInt(wStr); reqH = Integer.parseInt(hStr); hasSize = true; } catch (Exception ignored) {}
                }
                // 若未指定，用最大 picture 尺寸
                if (!hasSize) { reqW = 4208; reqH = 3120; }
                CameraController.StartResult started = cameraController.start(8000);
                if (started.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(started.error) + "\"}");
                }
                CameraController.PhotoResult photo = cameraController.takePhoto(reqW, reqH, 8000);
                if (photo.error != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(photo.error) + "\"}");
                }
                try {
                    java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
                    if (!dir.exists()) dir.mkdirs();
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
                    String name = "alpha2_pic_" + reqW + "x" + reqH + "_" + ts + ".jpg";
                    java.io.File outFile = new java.io.File(dir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(photo.jpeg); }
                    try { sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
                    String b64 = android.util.Base64.encodeToString(photo.jpeg, android.util.Base64.NO_WRAP);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + reqW + ",\"height\":" + reqH + ",\"bytes\":" + photo.jpeg.length + "}");
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + jsonSafe(e.getMessage()) + "\"}");
                }
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
            case "camera/fps":
                double fps = cameraController.getFps();
                String fpsStr = String.format(java.util.Locale.US, "%.1f", fps);
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"fps\":" + fpsStr + ",\"streaming\":" + cameraController.isStreaming() + "}");
            case "camera/supported_sizes": {
                java.util.List<android.hardware.Camera.Size> preview = cameraController.getSupportedPreviewSizesSync(4000);
                java.util.List<android.hardware.Camera.Size> picture = cameraController.getSupportedPictureSizesSync(4000);
                java.util.List<int[]> fpsRanges = cameraController.getSupportedPreviewFpsRangesSync(4000);
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"preview\":[");
                if (preview != null) {
                    boolean first = true;
                    for (android.hardware.Camera.Size s : preview) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
                    }
                }
                sb.append("],\"picture\":[");
                if (picture != null) {
                    boolean first = true;
                    for (android.hardware.Camera.Size s : picture) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
                    }
                }
                sb.append("],\"fpsRanges\":[");
                if (fpsRanges != null) {
                    boolean first = true;
                    for (int[] r : fpsRanges) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\"").append(r[0]/1000.0).append("-").append(r[1]/1000.0).append("\"");
                    }
                }
                sb.append("],\"current\":\"").append(cameraController.getPreviewWidth()).append("x").append(cameraController.getPreviewHeight()).append("\"}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
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
                // 2026-08 更新 (修 bug): 改用 getCachedRingtoneManager() 不再每次
                // new RingtoneManager 用完即丟 —— 見 findRingtoneByTitle() 上面
                // 那個 cache function 的 javadoc, 這裡是同一種 cursor 洩漏, 一起修。
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

            // 2026-08 新增: 用 title 查找鈴聲, 不再用 audio/ringtones/list 的 numbered
            // index (見上面 findRingtoneByTitle() 的 javadoc: cursor position 不保證
            // 跨機一致, 因為 RingtoneManager 內部排序邏輯不一定和 adb content query
            // 手動加 --sort 那個排序一樣)。Blockly 頁面現在內嵌一份靜態 title 清單
            // (由實機 adb content query 執行一次抓回來, 見 blockly-actions-data.js
            // 旁邊的 blockly-ringtone-data.js), 選了 title 直接送這個 API, 沿用
            // findRingtoneByTitle() 這個已經被 playStopCue()/playShutterCue() 使用、
            // 驗證過穩健的「查 title 轉 Uri」機制, 完全不用理會 index 排序這個問題。
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

            // 2026-08 新增: 停止目前正在播放的系統鈴聲/通知聲 (play / play_by_title 兩個
            // endpoint 播放的那個), 對應 Blockly「範例 5」的「停止播放」按鈕。
            case "audio/ringtones/stop": {
                stopRingtonePlayback();
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // -- Local music (/mnt/internal_sd/music/): 用戶自己放在機身的音樂檔,
            // 和上面 audio/ringtones/* 那些系統鈴聲是兩回事, 各自獨立一套 endpoint/
            // MediaPlayer, 詳見 listLocalMusicFiles()/playLocalMusicFile() 的
            // javadoc。"list" 沒有 index (檔案清單會隨用戶自己增減歌曲而變, 不像
            // ringtone 那些系統清單那麼穩定), "play" 直接用檔名 (含副檔名) 選取。
            case "audio/local_music/list": {
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
                boolean first = true;
                for (java.io.File f : listLocalMusicFiles()) {
                    if (!first) sb.append(",");
                    first = false;
                    // 2026-08 新增 sizeBytes - 供音樂 tab 的檔案清單顯示檔案大小用,
                    // 舊有的語音/小智呼叫路徑 (resolveLocalMusicFile 只看 "name")
                    // 不受這個新加欄位影響, 純粹多加一個 key。
                    sb.append("{\"name\":\"").append(jsonSafe(f.getName())).append("\",")
                            .append("\"sizeBytes\":").append(f.length()).append("}");
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

            // 2026-08 新增: 供瀏覽器音樂 tab 用的播放狀態/進度/音量 endpoint -
            // 之前這一套 local_music 純粹供小智語音/AI tool call 使用, 進度
            // 條 UI 用不到。這幾個 endpoint 沒有改動任何播放邏輯本身, 只是供前端
            // 讀/寫 currentMusicPlayer 已有的狀態。
            case "audio/local_music/status": {
                synchronized (this) {
                    android.media.MediaPlayer mp = currentMusicPlayer;
                    if (mp == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":true,\"hasTrack\":false,"
                                + "\"playing\":false,\"positionMs\":0,\"durationMs\":0,\"name\":null}");
                    }
                    boolean playing = false;
                    int pos = 0;
                    int dur = 0;
                    try {
                        playing = mp.isPlaying();
                        pos = mp.getCurrentPosition();
                        dur = mp.getDuration();
                    } catch (Exception e) {
                        // MediaPlayer 在 prepareAsync() 尚未完成的那段窗口呼叫這幾個
                        // getter 會拋出 IllegalStateException - 當「尚未準備好」, 退回
                        // 使用預設值 0/false, 不算真正錯誤。
                    }
                    String name = currentMusicTrackName;
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"hasTrack\":true,"
                            + "\"playing\":" + playing + ","
                            + "\"positionMs\":" + pos + ","
                            + "\"durationMs\":" + dur + ","
                            + "\"name\":" + (name != null ? "\"" + jsonSafe(name) + "\"" : "null") + "}");
                }
            }
            case "audio/local_music/seek": {
                String msStr = require(query, "ms");
                int ms;
                try {
                    ms = Integer.parseInt(msStr);
                } catch (NumberFormatException e) {
                    return HttpServer.ApiResponse.error("ms must be an integer");
                }
                synchronized (this) {
                    if (currentMusicPlayer == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
                    }
                    try {
                        currentMusicPlayer.seekTo(ms);
                    } catch (Exception e) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                                + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/local_music/volume": {
                String pctStr = require(query, "percent");
                int pct;
                try {
                    pct = Integer.parseInt(pctStr);
                } catch (NumberFormatException e) {
                    return HttpServer.ApiResponse.error("percent must be an integer");
                }
                float v = Math.max(0, Math.min(100, pct)) / 100f;
                synchronized (this) {
                    if (currentMusicPlayer == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
                    }
                    try {
                        currentMusicPlayer.setVolume(v, v);
                    } catch (Exception e) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                                + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 v2 新增: audio spectrum - 回傳最近一次 FFT 算出的頻譜
            // (MUSIC_SPECTRUM_BANDS 條, 每條 0-255), 前端 ~100ms 輪詢一次畫 bar。
            // 沒播歌/Visualizer 建不起來就全部回傳 0。
            case "audio/local_music/spectrum": {
                StringBuilder sbSpec = new StringBuilder("{\"ok\":true,\"bands\":[");
                synchronized (this) {
                    for (int i = 0; i < MUSIC_SPECTRUM_BANDS; i++) {
                        if (i > 0) sbSpec.append(",");
                        sbSpec.append(musicSpectrumBands[i]);
                    }
                }
                sbSpec.append("]}");
                return HttpServer.ApiResponse.ok(sbSpec.toString());
            }

            // 2026-08 v2 新增: 真・暫停/恢復 - MediaPlayer.pause() 之後個播放位置
            // 一直記住, 之後 start() 就從那裡繼續, 不用從頭播放。之前前端用
            // "stop 當 pause" 的變通法, 恢復時整首歌從頭來, 用戶投訴過這一點。
            // 注意: pause/resume 都不會動到 musicFillerActionLoop - 暫停期間那個 loop
            // 仍在執行 (triggerRandomFillerAction() 有它自己「沒在播就不動」的
            // 判斷), 沿用原本播歌期間的行為。
            case "audio/local_music/pause": {
                synchronized (this) {
                    if (currentMusicPlayer == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
                    }
                    try {
                        currentMusicPlayer.pause();
                    } catch (Exception e) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                                + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "audio/local_music/resume": {
                synchronized (this) {
                    if (currentMusicPlayer == null) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
                    }
                    try {
                        currentMusicPlayer.start();
                    } catch (Exception e) {
                        return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                                + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 新增: Equalizer presets - 用返 android.media.audiofx.Equalizer
            // 自己的 preset 清單 (由裝置/廠商決定有多少個、叫什麼名, 例如 "Normal"、
            // "Classical"、"Rock" 等, 不是這個 app 自己定義的一套), 保證和這台機器
            // 實際安裝的 audio effect engine 一致, 不會出現選了個 UI 名但
            // usePreset() 對不上的情況。沒播歌 (musicEqualizer 尚未建立) 也要給出
            // 清單 (建一個臨時 Equalizer 取得清單再立即放掉), 讓用戶還沒播歌也能看到
            // 有咩 preset 可以揀。
            case "audio/local_music/eq/presets": {
                android.media.audiofx.Equalizer temp = null;
                try {
                    temp = new android.media.audiofx.Equalizer(0, 0);
                    short numPresets = temp.getNumberOfPresets();
                    StringBuilder sbEq = new StringBuilder("{\"ok\":true,\"presets\":[");
                    for (short i = 0; i < numPresets; i++) {
                        if (i > 0) sbEq.append(",");
                        sbEq.append("{\"index\":").append(i).append(",\"name\":\"")
                                .append(jsonSafe(temp.getPresetName(i))).append("\"}");
                    }
                    sbEq.append("],\"current\":").append(musicEqPresetIndex).append("}");
                    return HttpServer.ApiResponse.ok(sbEq.toString());
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"presets\":[],\"current\":-1,"
                            + "\"unavailable\":true}");
                } finally {
                    if (temp != null) {
                        try {
                            temp.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            case "audio/local_music/eq/set": {
                String idxStr = require(query, "index");
                int idx;
                try {
                    idx = Integer.parseInt(idxStr);
                } catch (NumberFormatException e) {
                    return HttpServer.ApiResponse.error("index must be an integer");
                }
                // 存下選擇 (不理會現在是否正在播放), 等下一首歌開始播時
                // setupMusicEqualizerLocked() 都會跟返呢個 preset。
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_MUSIC_EQ_PRESET, idx).apply();
                synchronized (this) {
                    musicEqPresetIndex = idx;
                    if (musicEqualizer != null) {
                        try {
                            if (idx >= 0 && idx < musicEqualizer.getNumberOfPresets()) {
                                musicEqualizer.usePreset((short) idx);
                            }
                        } catch (Exception e) {
                            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                                    + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                        }
                    }
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }

            // 2026-08 新增: 「播歌隨機動作」開關 - 用戶要求可以自己開關, 之前呢個
            // 行為一直都是跟著有沒有正在播歌自動開/關, 沒有獨立開關按鈕。預設 true
            // (和 isMusicFillerActionEnabled() 尚未讀過設定時的預設值一致, 保持之前
            // 行為)。
            case "audio/local_music/filler_action/get": {
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":"
                        + isMusicFillerActionEnabled() + "}");
            }
            case "audio/local_music/filler_action/set": {
                boolean enabled = "true".equals(query.get("enabled"));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_MUSIC_FILLER_ACTION_ENABLED, enabled).apply();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
            }

            // -- FM/網絡電台 (經 Radio Browser API, radio-browser.info, 動態搜全
            // 世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 的
            // javadoc, 這台機器不再內建任何寫死的電台清單) - "search" 對應
            // self.media.search_radio, "play" 用 resolveRadioStation() 做人類
            // 語言名比對 (先比對 lastRadioSearchResults, 比對不到就直接當新搜尋詞打
            // API)。多加一個 "status" 供前端面板顯示「目前正在播哪個台」用 (電台沒有
            // 檔名那麼直觀, 用戶自己按「轉台」之後有需要知道結果)。這兩個 endpoint
            // 內部會打網路, 和 MCP tool 那邊不同 (那邊有外層 try/catch(Exception)
            // 包住整個 switch), handleApi() 沒有, 所以這裡自己要包一層 try/catch
            // 把 IOException/JSONException 轉成正常的 {"ok":false,...} 回應,
            // 不可以讓 exception 直接飛出 handleApi()。
            case "audio/radio/search": {
                String q = require(query, "query");
                try {
                    java.util.List<org.json.JSONObject> found = searchRadioStations(q, 30);
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
            case "audio/radio/play_url": {
                String url = require(query, "url");
                String nameHint = query.get("name");
                if (url == null || url.trim().isEmpty()) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"url is required\"}");
                }
                try {
                    // 2026-08 新增: 供前端直連 radio-browser.info fallback 用 — 瀏覽器自己
                    // fetch 完搜尋結果 (繞過機械人本身 DNS/無外網問題看列表), 再將選中台的
                    // url_resolved 直接送來此 endpoint 播放, 不再經 resolveRadioStation()
                    // 重新打一次 Radio Browser API (那步在機械人無外網時必定失敗)。
                    org.json.JSONObject station = new org.json.JSONObject();
                    station.put("url_resolved", url);
                    station.put("url", url);
                    station.put("name", nameHint != null ? nameHint : url);
                    station.put("stationuuid", "frontend-" + System.currentTimeMillis());
                    playRadioStream(station);
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                            + jsonSafe(station.optString("name")) + "\"}");
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + jsonSafe("radio play_url failed: " + e.getMessage()) + "\"}");
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
            // 2026-08 新增。這個 config 檔控制機身開機時的 wake word/ASR 語言/預設對話
            // app (見 AIDL_REFERENCE_ALPHA2.md「引擎選擇」段落) —— 實測證實 (見 log) 改了這個
            // 檔案、重開機之後, wake word 真的會跟著轉。
            //
            // 兩個關鍵限制, 這組 API 圍繞這兩點設計:
            // 1. 這是外部儲存的普通檔案 (/sdcard, 不是 app 私有目錄), targetSdkVersion 22
            //    不用 runtime permission, manifest 已有 WRITE_EXTERNAL_STORAGE, 讀寫本身
            //    沒有障礙。
            // 2. 改完必須重開機才生效 (實測: alpha2services 只在開機時讀一次, 沒有監聽
            //    檔案改動), 所以 set 這個 endpoint 只負責寫檔, 不會假裝「即時生效」；
            //    重開機要用戶自己另外選擇「reboot after set」或之後手動用 service_config/reboot。
            //
            // 只支援兩個 preset (cn/en), 兩個都是機身出廠內建的原裝 default config
            // (分別對應 aaservice_config.json 和 service_config.json 這兩份出廠檔案),
            // 一字不改照抄, 不是自己組出來的組合——兩個都是原廠已知安全的設定, 所以
            // 不設「還原」按鈕, 也不做寫入前備份 (兩個 preset 之間可以隨時互相切換,
            // 沒有「損壞」這個概念)。
            case "service_config/get":
                return serviceConfigGet();
            case "service_config/set": {
                String preset = require(query, "preset");
                boolean reboot = Boolean.parseBoolean(queryOrDefault(query, "reboot", "false"));
                return serviceConfigSet(preset, reboot);
            }
            case "service_config/reboot":
                // 獨立出來做一個 endpoint, 讓用戶可以「set 完先看看寫對了沒, 之後再
                // reboot」, 不一定要一步到位。
                return systemReboot();

            // -- Alice talk server 假 endpoint (/api/alice/talkServer) -------------------
            //
            // 2026-08 新增。原廠 alice_Server (service_config.json 裡的那個欄位) 寫死指向
            // 一個內部開發機 IP (http://10.10.1.54:8081/programd/talkServer?), 在外面連不
            // 到。實測拆解 alpha2services 證實: ASR 識別本身是 local (.bnf 語法比對, 不用
            // 上網), 但識別完之後的「取得對話回應」步驟會打一條 HttpURLConnection 去
            // alice_Server (com.ubtechinc.alpha2ctrlapp.network.c.c.a()), connectTimeout
            // 10 秒; 打不通整個對話流程就卡在那裡沒反應, 看起來好像 iFlytek 整套都停擺,
            // 其實只是這一步卡住。
            //
            // 這個 endpoint 就是供 *_openalpha2_offline preset (見下面 ALICE_OFFLINE_*)
            // 用的假後端: 對應的 preset 把 alice_Server 改指向
            // "http://127.0.0.1:8888/api/alice/talkServer?", 讓這條 HTTP call 打得通,
            // 使流程不再卡死。Request 格式 (form-urlencoded, 由
            // com.ubtechinc.alpha2ctrlapp.network.c.c.a() 組裝) 已拆解確認:
            //   appType=...&requestKey=...&requestTime=...&serviceVersion=...
            //   &systemLanguage=...&content=<識別到的文字>
            // Response 格式尚未拆到實際 schema (原廠那條 link 一直打不通, 沒 log 過真正
            // response) —— 現在只需要讓 HTTP round-trip 成功不拋出 exception, 使下游
            // 不再卡死; response body 是否真的被原廠 code 解析、解析失敗會如何, 都尚未驗證,
            // 純粹先做到「打得通」這一步。plain text 對應 talkServer 這類 AIML/ALICE 協議
            // 常見的裸文字回覆格式。
            case "alice/talkServer":
                return aliceTalkServer(body);

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown endpoint: " + path + "\"}");
        }
    }

    // -- Service config (/sdcard/actions/service_config.json + .txt) ------------------
    //
    // 這個檔案控制機身開機時的 wake word / ASR 語言 / 預設對話 app。實測確認 (見對話
    // history 的 log): 覆蓋這個檔案 + 重開機, wake word 真的會跟著轉。中文／英文兩個
    // preset 都是機身原本出廠內建的兩組 default config (分別對應 aaservice_config.json
    // 和 service_config.json 這兩份出廠檔案), 一字不改照抄, 不是自己組出來的組合 ——
    // 兩個都是原廠已知安全的設定, 所以不設「還原」按鈕, 也不做寫入前備份 (兩個 preset
    // 之間可以隨時互相切換, 沒有「損壞」這個概念)。

    private static final String SERVICE_CONFIG_DIR = "/sdcard/actions";
    private static final String SERVICE_CONFIG_JSON = SERVICE_CONFIG_DIR + "/service_config.json";
    private static final String SERVICE_CONFIG_TXT = SERVICE_CONFIG_DIR + "/service_config.txt";

    /** 中文組: 出廠原裝 aaservice_config.json 內容, 一字不改。wake word「你好 阿爾法」
     *  (CN_WAKEUP_NIHAO_ALPHA), default_App 沿用原廠的 iflytekmix。 */
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
     *  (EN_WAKEUP_HELLO_ALPHA_THREE), default_App 沿用原廠的 alphaenglishchat。 */
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

    /** 2026-08 更新 (混合版): default_App 指返 OpenAlpha2 自己; 四個 server link
     *  入面淨係 alice_Server 繼續指去 OpenAlpha2 個假 endpoint - 因為原廠值係
     *  內部開發機 IP (10.10.1.54), 外部永遠連不上, 識別完取得對話回應那步會卡
     *  10 秒。其餘三個 (web/develop/xmpp) 實測原廠伺服器 2026 年仍然有反應,
     *  沿用原廠值反而更好:
     *  - web_Server: firmware 每次開機都強制將這個欄位改寫成 https://, 本機
     *    http server 沒有 TLS 必定失敗; 沿用原廠 https link 就沒有這個問題。
     *  - develop_Server/xmpp_Server: 原廠仍在運作, 開機檢查/xmpp 連線取得真回應;
     *    離線時照樣連不上, 無影響。
     *  離線文法辨識與對答完全不經這些 link (見 applyConnectivityMode/
     *  doStartGrammar 那邊 comment)。 */
    private static final String CN_OPENALPHA2_OFFLINE_PRESET_JSON = "{"
            + "\"alice_Server\":\"http://127.0.0.1:8888/api/alice/talkServer?\","
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

    /** 2026-08 更新: 同 CN_OPENALPHA2_OFFLINE_PRESET_JSON 同一套混合改法 -
     *  alice_Server 留本機假 endpoint (原廠死 IP), web/develop/xmpp 用返原廠
     *  (實測仍然有反應), 見該處註解。 */
    private static final String EN_OPENALPHA2_OFFLINE_PRESET_JSON = "{"
            + "\"asr_Language\":\"en_us\","
            + "\"default_App\":\"com.open.alpha2\","
            + "\"isBusiness\":false,"
            + "\"isOpenDebugLog\":true,"
            + "\"isOpenInfoLog\":true,"
            + "\"web_Server\":\"http://services.ubtrobot.com/ubx/\","
            + "\"develop_Server\":\"http://dev.ubtrobot.com/opencenter/app/accesscheckapp\","
            + "\"alice_Server\":\"http://127.0.0.1:8888/api/alice/talkServer?\","
            + "\"xmpp_Server\":\"services.ubtrobot.com\","
            + "\"wakeup_word\":\"EN_WAKEUP_HELLO_ALPHA_THREE\","
            + "\"wakeup_threshold_mic5\":25"
            + "}";

    /** alice_Server 假後端。Request body 是 form-urlencoded (由 alpha2services 的
     *  com.ubtechinc.alpha2ctrlapp.network.c.c.a() 組裝), 欄位: appType/requestKey/
     *  requestTime/serviceVersion/systemLanguage/content。只讀取 content 出來做 log
     *  方便對著實機 debug 查看「機身識別到的文字有沒有送到這裡」, 回應內容目前是
     *  hardcode 的固定句子 —— 想接上真正智能回覆 (例如轉發去 LLM API) 就是在這個
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
        // TODO: 這句是 placeholder。想真的有智能回覆, 在這裡轉發 content 去自己選擇的
        // LLM/對話服務, 拿回來做 response body。現在只求「HTTP round-trip 打得通」。
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

    /** preset = "cn" | "en" | "cn_openalpha2_offline" | "en_openalpha2_offline"。
     *  前兩個是機身出廠內建的原裝 default config, 一字不改照抄 (UI 上顯示為「備份」);
     *  後兩個是 2026-08 新增, default_App 指向 OpenAlpha2 自己 (com.open.alpha2),
     *  並且將 alice_Server/web_Server/develop_Server/xmpp_Server 全部改指向
     *  OpenAlpha2 自己的 8888 server, 讓 wake word 觸發之後直接 launch OpenAlpha2、
     *  完全脫離外部連線。四個都不設「還原」按鈕、也不做寫入前備份——隨時可以互相
     *  切換, 沒有「損壞」這個概念。寫入對應的 JSON + 精簡 TXT 版本, 兩個檔案要同步。 */
    private HttpServer.ApiResponse serviceConfigSet(String preset, boolean reboot) {
        String json;
        if ("cn".equals(preset)) {
            json = CN_PRESET_JSON;
        } else if ("en".equals(preset)) {
            json = EN_PRESET_JSON;
        } else if ("cn_openalpha2_offline".equals(preset)) {
            json = CN_OPENALPHA2_OFFLINE_PRESET_JSON;
        } else if ("en_openalpha2_offline".equals(preset)) {
            json = EN_OPENALPHA2_OFFLINE_PRESET_JSON;
        } else {
            return HttpServer.ApiResponse.error("preset must be 'cn', 'en', "
                    + "'cn_openalpha2_offline' or 'en_openalpha2_offline'");
        }

        org.json.JSONObject obj;
        String asrLanguage;
        String defaultApp;
        try {
            obj = new org.json.JSONObject(json);
            asrLanguage = obj.getString("asr_Language");
            defaultApp = obj.getString("default_App");
        } catch (org.json.JSONException e) {
            // 這兩個 preset 是常數, 不應該解析失敗——如果發生, 一定是這個 class 裡
            // 手寫錯了, 不是用家輸入問題。
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

    /** 觸發機身重開機。實測證實 service_config.json 只在開機時讀一次, 沒有 runtime
     *  監聽, 所以這是讓新 config 生效的必經步驟 - 不提供任何「不用重開機」的
     *  替代方案, 因為沒實測過有第二條路。 */
    private HttpServer.ApiResponse systemReboot() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return HttpServer.ApiResponse.error("PowerManager unavailable");
            }
            pm.reboot("robotpanel_service_config_change");
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"rebooting\":true}");
        } catch (SecurityException e) {
            // REBOOT permission 在很多機身/ROM 只給 system app 用, 第三方 app (即使
            // 有 manifest 聲明) 都可能在這裡被 SecurityException 拒絕 - 這是
            // 意料之內的失敗模式, 不是 bug, 前端應該提示用戶手動長按電源鍵重開機。
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
            return handleMusicUpload(query, body);
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

    /** 2026-08 新增: 本地音樂 tab 的拖放上傳功能 - 瀏覽器把檔案內容原封不動 POST
     *  到這個 endpoint (?name=<原本檔名>), 寫入 LOCAL_MUSIC_DIR。檔名只做
     *  sanitizeUploadFilename() (去掉路徑分隔符/上層目錄嘗試), 不做內容檢查
     *  (例如是否真的是一個有效的音訊檔) - 沿用 listLocalMusicFiles() 一致的原則:
     *  只看副檔名, 真正播不播得了留給 MediaPlayer.prepareAsync() 時自然
     *  onError, 不在這裡重複做判斷。副檔名要在 LOCAL_MUSIC_EXTENSIONS 裡面才
     *  收 (避免用呢個 endpoint 上載任意檔案類型到機身)。如果 LOCAL_MUSIC_DIR
     *  仲未存在 (第一次用呢個功能), 順手 mkdirs()。 */
    private HttpServer.ApiResponse handleMusicUpload(Map<String, String> query, byte[] body) {
        String rawName = query.get("name");
        if (rawName == null || rawName.trim().isEmpty()) {
            return HttpServer.ApiResponse.error("name query parameter is required");
        }
        String safeName = sanitizeUploadFilename(rawName);
        if (safeName.isEmpty()) {
            return HttpServer.ApiResponse.error("invalid file name");
        }
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 && dot < safeName.length() - 1
                ? safeName.substring(dot + 1).toLowerCase(java.util.Locale.US) : "";
        if (!LOCAL_MUSIC_EXTENSIONS.contains(ext)) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"unsupported file type: ."
                    + jsonSafe(ext) + "\"}");
        }
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.error("empty file body");
        }
        try {
            if (!LOCAL_MUSIC_DIR.exists() && !LOCAL_MUSIC_DIR.mkdirs()) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"failed to create music folder\"}");
            }
            java.io.File dest = new java.io.File(LOCAL_MUSIC_DIR, safeName);
            // 避免撞名覆蓋另一首已經存在的歌 - 自動加 " (2)"/" (3)" 這類尾綴,
            // 和瀏覽器下載檔案撞名那種做法一致, 用戶預期不會「悄悄蓋掉舊檔」。
            dest = uniqueFileFor(dest);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(body);
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"name\":\""
                    + jsonSafe(dest.getName()) + "\",\"sizeBytes\":" + body.length + "}");
        } catch (Exception e) {
            Log.w(TAG, "Music upload failed for " + safeName, e);
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + jsonSafe(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /** 只保留檔名本身的最後一截 (new File(name).getName() 已經剝掉任何
     *  "../"/"/" 這類路徑成分), 再去掉頭尾的空白, 保證寫入 LOCAL_MUSIC_DIR
     *  的結果一定在這個資料夾裡面, 不會因為用戶 (或惡意請求) 在檔名中夾帶
     *  路徑分隔符而寫到第二個資料夾度。*/
    private static String sanitizeUploadFilename(String rawName) {
        String base = new java.io.File(rawName.trim()).getName();
        return base.trim();
    }

    /** 如果 candidate 已經存在, 在副檔名前面加 " (2)"、" (3)"... 直到找到一個
     *  未用過的檔名為止, 保證上傳永遠不會覆蓋一首已經存在的歌。*/
    private static java.io.File uniqueFileFor(java.io.File candidate) {
        if (!candidate.exists()) return candidate;
        String name = candidate.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        java.io.File parent = candidate.getParentFile();
        int n = 2;
        java.io.File next;
        do {
            next = new java.io.File(parent, base + " (" + n + ")" + ext);
            n++;
        } while (next.exists());
        return next;
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
     *  「亮一秒又熄了」/「開兩次又停了」- 對照 logcat 找到真正機制: 不只是
     *  releaseMicForAudioIo() javadoc 提到的「setWakeState() 觸發 broadcast 熄燈」
     *  那麼簡單, 而是 alpha2services 內部 AlphaMainSeviceImpl 的 "stop ear led"
     *  邏輯本身**不是真的熄掉了 LED**, 而是內部照樣呼叫多次
     *  header_ledSetHead5Mic(color=3,brightness=2,...,p5=400,p6=9000,p8=2) 這組
     *  固定參數去做「熄燈」效果 (也就是設定成一個很暗的顏色/圖案, 不是真正斷電) -
     *  而這個內部熄燈邏輯**持續循環運作**, 密度很高 (實測相隔只有 0.8 秒左右
     *  就再來一次), 只要小智常開對話還開著就不會停。之前的做法 (在這裡單次補發
     *  2 秒就結束) 追不上這個持續循環的頻率, 2 秒過了之後又打回原形。
     *
     *  現在改成「持續生效直到用戶下一次改指令為止」: 每次 led_set_head/
     *  led_set_eye 被呼叫, 就開一條長駐 background thread, 用
     *  headLedReassertGeneration/eyeLedReassertGeneration 這兩個 generation
     *  counter 分別做 head/eye 獨立的取消機制 - 新一次呼叫 (無論是新顏色還是
     *  preset=stop) 都會讓 generation 數字進位, 舊的那條 thread 見到自己的
     *  generation 已經過時就會自行停止, 保證同一時間只有一條 thread 在
     *  持續補發, 不會愈開愈多。preset=stop 那個 case (header_stop5MicEarLED())
     *  只需要讓 generation 進位使舊的補發 thread 停止, 不需要自己再開新
     *  thread。
     *
     *  2026-08 再修正: 用戶實測 300ms 的補發間隔仍然「和其他 code 相撞」- 對照
     *  logcat 發現內部熄燈循環大約每 2 秒觸發一次, 300ms 的間隔理應大部分時間
     *  都能贏過它, 但兩種顏色交替出現在肉眼看來仍然構成明顯閃爍。這個「熄燈循環」
     *  本身沒辦法完全消除 (只要小智 auto-mode 開著就會持續運作), 只能縮短
     *  「熄了未補發回來」那段空隙的長度來減少肉眼可見的閃爍程度。把補發間隔由
     *  300ms 縮短到 80ms - AIDL call 本身很快, 2 秒週期裡補發 25 次左右都不會
     *  構成負擔, 但空隙短很多, 閃爍會沒那麼明顯。 */
    private static final long LED_REASSERT_INTERVAL_MS = 80;
    private final java.util.concurrent.atomic.AtomicLong headLedReassertGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong eyeLedReassertGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 讓目前生效中的 head/eye LED 持續補發 thread (如果有) 在下一個補發週期
     *  自行停止, 不開新 thread 補回 - preset=stop 那個 case 用這個。 */
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
                        return; // 補發期間又有新一次呼叫, 或用戶呼叫了 stop, 讓位給它
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

    /** 2026-08 新增: 這台機器 (head board / firmware 1.1.1.14) 的
     *  header_ledSetHead5Mic/header_ledSetEye5Mic 實測全部 preset 都回傳
     *  API_ERROR_FAILED (bindReady:true, 也就是不是尚未 ready, 是機身真的不支援/
     *  沒實作 - 看起來這個機頭不是 5-mic variant, 或這個 firmware 沒實作這兩個
     *  AIDL 方法)。Mouth LED (MouthLedData, 直接 JNI 不經 AIDL) 則實測正常。
     *
     *  這個方法把 obstacle-triggered 的 LED 指示同時發到兩條路: 5-mic
     *  head/eye (setHeadEyeLedLong) 照舊保留 - 在支援的機/firmware 上會亮紫燈,
     *  在這台機器上頂多是 API_ERROR_FAILED、沒有視覺效果、但不會拋出例外中斷流程;
     *  同時也閃爍 mouth LED 做 fallback, 保證這台機器都看得到反應。兩條路獨立 try/catch,
     *  其中一條失敗不會擋住另一條。 */
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
     *  2026-08 更新: 實機 (firmware 1.1.1.14) 證實這個 0x81 幀假設完全沒撞中 -
     *  sonar 讀數根本不會經 IAlpha2SerialPortService 的 AIDL rcv callback 送達,
     *  onListenSerialPortRcvData() 只收到 app 自己送出 chest_configureSonar()
     *  那個 config command 的 2-byte ack "04 00"。中途一度誤以為 sonar 讀數會
     *  經由 "com.ubtechinc.services.chest" (StaticValue.CHEST_ACTION) 這個全域
     *  broadcast 重新發送, 但反編譯官方 UBTech alpha2demo.apk 之後證實這也
     *  是錯的 - CHEST_ACTION 官方 demo 自己也只是用來 log 機身內部 raw command
     *  byte (見 RobotEventReceiver 的 CHEST_ACTION case), 不是 sonar 讀數。
     *  真正的 sonar 讀數是經由另一個獨立、之前完全沒診斷到的 broadcast action
     *  "com.ubtechinc.sonar.distance" (StaticValue.SONAR_DISTANCE_ACTION) 送出,
     *  extra 已經是 parse 好的 int (key "sonar_distance",
     *  StaticValue.SONAR_DISTANCE_EXTRA), 不需要自己再解 raw wire frame - 見
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
        setHeadEyeLedLong(2, 9); // 綠燈長開 - 正在聽機器人說話, 一定要在上面那行之後才呼叫,
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

    // 2026-08 (已淘汰): 之前用 waitForStableFrame() 跳過幾幀來迴避 preview frame
    // 過渡期問題 (AE/AF 未收斂) - 反編譯用戶提供、實測成功的第三方 apk 之後發現真正
    // 根源是 capture 方式本身 (preview frame vs 真正單張拍攝), 已改用
    // CameraController.takePhoto() (真正 camera.takePicture()), 見
    // xiaozhiTakePhotoAndExplain() 那段 comment。這個「跳幀」workaround 已不再使用,
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
        // 實際有 ~140 個檔。可能是 (a) latch timeout, onGetActionList 沒有在 5s 內
        // callback, list 保持 null, 或 (b) 機身確實有 callback 回 list, 但每行
        // < 4 欄, 全部被下面的 "row.size() < 4" 跳過。這兩種情況分開 log 才能分辨
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

    /** 2026-08 新增: onServerCallBack() 收到的 raw 字串, 在「語法識別」(grammar,
     *  logcat type:1) 路徑底下是一個未解析的 iFlytek JSON, 例如
     *  {"text":"你的爸爸是谁啊","rc":4}, 而不是純文字 (純文字是「聽寫識別」
     *  dictation, type:0, 那條路徑才有的格式)。這個 method 判斷輸入是否這種
     *  JSON 格式, 是的話就抽出 text field, 不是 (或 parse 失敗/text field
     *  不存在) 就原封不動退回原字串, 使 type:0 路徑和 "Local_Result:..." 路徑
     *  完全不受影響。*/
    private static String extractGrammarResultText(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return raw; // 不是 JSON 格式 (例如 "Local_Result:..." 或純文字聽寫結果), 原樣返回
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("text")) {
                return obj.getString("text");
            }
            // 2026-08 新增: 離線本地文法 (engine_type=local buildGrammar bnf) 的
            // 結果格式沒有 top-level text field! 實測 payload (WS capture):
            //   {"sn":1,"ls":true,"ws":[{"slot":"<phrase>","cw":[{"w":"你叫什么名字",
            //    "id":65535,"sc":0,"gm":0}]}],"sc":51}
            // 識別到的字在 ws[].cw[].w 裡 (cw 是候選, 第一個是最高分)。逐個 ws 取
            // 第一個非空的 cw[0].w 直接串接 (中文不加空格), 使對話界面/語意配對
            // 取得乾淨文字。
            org.json.JSONArray wsArr = obj.optJSONArray("ws");
            if (wsArr != null && wsArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wsArr.length(); i++) {
                    org.json.JSONObject wsItem = wsArr.getJSONObject(i);
                    org.json.JSONArray cw = wsItem.optJSONArray("cw");
                    if (cw == null || cw.length() == 0) continue;
                    String word = cw.getJSONObject(0).optString("w", "");
                    if (word != null && !word.isEmpty()) {
                        sb.append(word);
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (JSONException e) {
            // parse 不到就當它不是這種格式, 原樣返回 - 避免因為格式猜錯而搞壞
            // 其他沒問題的 ASR 路徑
        }
        return raw;
    }

    /** 2026-08 最終版: 預設文法是一份預先在 PC 上做好的靜態檔案
     *  (assets/iflytek/default_grammar.bnf: 中文 1212 句 (q0-q12) + greet
     *  slot 裡的 hello/hi 兩個英文字, 全繁體, 無重複, 已剔除乘數表)。來源 =
     *  語意庫 + 悠聊原裝 call.bnf 合併轉換, App 不再做任何運行時生成/解析/
     *  簡繁轉換, 淨係讀檔。
     *
     *  2026-08 移除: 曾經試過加 3000 個英文常用字 (e0-e29 slot) 撐英文離線
     *  覆蓋率, 但訊飛官方文檔明文「离线命令词只支持中文普通话，暂不支持英文」
     *  ——已反編譯確認 common.jet 聲學模型沒有英文音素, 連字符/串接等 BNF 花招
     *  都試過, 只有單字偶爾因為發音像某個中文音才「僥倖」被識別到, 不穩定也沒有
     *  實際離線英文句子辨識能力。3000 個詞塞進 grammar 只會拖慢 build 速度
     *  和增加與中文詞的聲學碰撞機會, 對真正想要的中文識別率有害無益, 所以
     *  全部剔除。離線英文需求已改用 Nuance 內建文法或未來的第三方引擎
     *  (Vosk) 方案, 不再在這個 iFlytek BNF grammar 上勉強。 */
    private String readDefaultGrammarAsset() {
        try {
            java.io.InputStream in = getAssets().open("iflytek/default_grammar.bnf");
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "readDefaultGrammarAsset failed: " + e.getMessage());
            return null;
        }
    }

    /** 2026-08 新增: 將預設 BNF 文法原樣 (JSON string) 回傳給前端, 讓 textarea
     *  有內容可顯示、用戶可以直接改完再 init_grammar。 */
    private HttpServer.ApiResponse getDefaultGrammar() {
        String bnf = readDefaultGrammarAsset();
        if (bnf == null) {
            return HttpServer.ApiResponse.error("assets/iflytek/default_grammar.bnf unreadable");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"bnf\":\"" + jsonSafe(bnf) + "\"}");
    }

    // -- 離線文法模式: 共用內部方法 + 自動跟網絡切換 ---------------------------------
    //
    // 2026-08 新增。原本淨係得 HTTP endpoint 直接叫 robot.speech_*Grammar();
    // 現在抽出三個內部方法 (doInitGrammar/doStartGrammar/doStopGrammar), 供
    // 「自動跟網路切換」和 HTTP endpoint 兩邊共用。自動切換規則 (開啟
    // offlineGrammarAutoSwitch 才生效):
    //   沒網路 → 確保 iFlytek binding → 文法未構建就先構建 → 構建成功立即 start
    //   有網路 → 離線模式開著的話就 stop, 回到雲端聽寫 (自由講話)
    // 狀態變化會 publish "offline_mode" event 供前端 UI 更新。

    private boolean isNetworkConnected() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** 2026-08 新增: 真正的「雲端聽寫能不能用」探測。isNetworkConnected() 只是
     *  反映 WiFi link 狀態 - 連著一個沒有後備網路的手機 hotspot 時照樣回報
     *  connected, 但實際上不了網。而且單純「有網際網路」也不夠: 如果網路
     *  封鎖了訊飛伺服器, 雲端聽寫照樣全部網路錯誤 (實測 logcat: 10114/20002)
     *  - 這種情況對語音來說應該當成離線走本地文法。
     *
     *  探測目標是反編譯 alpha2services 找到的、機身 MSC 實際使用的雲端主域:
     *  SpeechUtility init 字串 "appid=56652373" +
     *  "server_url=http://ubtek.openspeech.cn/index.htm", 另加 openspeech 主域
     *  和舊版 voicecloud.cn 做 fallback。任一 TCP handshake 通過 = 當作 online。
     *  Blocking call (最長 ~7.5s), 只供背景 thread 呼叫。 */
    private static boolean hasRealInternet() {
        // 第一個目標用反編譯找到的 server_url host; 另外加上 IP 直連 fallback -
        // 手機數據底下 DNS 有時慢/斷斷續續, hostname 解析失敗不代表這條路真的不通。
        String[][] targets = {
                {"ubtek.openspeech.cn", "80"},
                {"openspeech.cn", "80"},
                {"voicecloud.cn", "443"},
                {"121.37.220.137", "80"} // ubtek.openspeech.cn 的 IP (2026-08 實測), 免 DNS
        };
        for (String[] t : targets) {
            try {
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(t[0], Integer.parseInt(t[1])), 2500);
                s.close();
                return true;
            } catch (Exception e) {
                android.util.Log.d(TAG, "probe " + t[0] + ":" + t[1] + " fail: "
                        + e.getClass().getSimpleName());
            }
        }
        return false;
    }

    /** 最近一次探測結果 - 開機預設樂觀當有網, 第一次 probe 之後就會校正。 */
    private volatile boolean lastProbeOnline = true;
    /** 探測用 HandlerThread - 一定要背景 thread! 之前用 MainLooper, probe 的
     *  TCP connect 全部即刻彈 NetworkOnMainThreadException, 令 watchdog 永遠
     *  以為離線 (2026-08 實測 bug)。 */
    private android.os.HandlerThread offlineWatchdogThread;
    private android.os.Handler offlineWatchdogHandler;
    private boolean offlineWatchdogStarted = false;

    /** 週期性探測迴路 (30 秒一次)。CONNECTIVITY_ACTION 只在 WiFi link 層面
     *  變化時才會發送 - hotspot 的後備網路 (行動數據) 開關根本不會觸發任何廣播,
     *  所以單靠 receiver 不夠, 要自己定時 probe 才能偵測到「WiFi 沒變但上不了
     *  網」這種狀態。
     *
     *  2026-08 加防抖動: 實測手機數據底下對訊飛雲的 TCP probe 結果會飄忽
     *  (時通時不通), 單次結果就轉模式會讓指示燈/語音模式不停跳動。現在要
     *  連續 2 次同方向的結果才真的切換 (PROBE_CONFIRM_N)。 */
    private static final int PROBE_CONFIRM_N = 2;
    private int offlineProbeDownCount = 0;
    private int offlineProbeUpCount = 0;

    private final Runnable offlineProbeLoop = new Runnable() {
        @Override
        public void run() {
            try {
                final boolean online = hasRealInternet();
                if (online != lastProbeOnline) {
                    if (online) {
                        offlineProbeUpCount++;
                        offlineProbeDownCount = 0;
                    } else {
                        offlineProbeDownCount++;
                        offlineProbeUpCount = 0;
                    }
                    Log.i(TAG, "offline watchdog: internet " + (online ? "UP" : "DOWN")
                            + " (" + (online ? offlineProbeUpCount : offlineProbeDownCount)
                            + "/" + PROBE_CONFIRM_N + ")");
                    if ((online && offlineProbeUpCount >= PROBE_CONFIRM_N)
                            || (!online && offlineProbeDownCount >= PROBE_CONFIRM_N)) {
                        lastProbeOnline = online;
                        offlineProbeUpCount = 0;
                        offlineProbeDownCount = 0;
                        applyConnectivityMode(online, "probe");
                    }
                } else {
                    // 同現狀一致 - 清晒兩邊計數
                    offlineProbeUpCount = 0;
                    offlineProbeDownCount = 0;
                }
            } catch (Exception e) {
                Log.w(TAG, "offline watchdog error: " + e.getMessage());
            }
            offlineWatchdogHandler.postDelayed(this, 30000);
        }
    };

    private void startOfflineWatchdog() {
        if (offlineWatchdogStarted) return;
        offlineWatchdogStarted = true;
        offlineWatchdogThread = new android.os.HandlerThread("OfflineProbe");
        offlineWatchdogThread.start();
        offlineWatchdogHandler = new android.os.Handler(offlineWatchdogThread.getLooper());
        offlineWatchdogHandler.postDelayed(offlineProbeLoop, 8000);
    }

    /** 2026-08 新增: 「從第一句對答就知道是否離線」- 喚醒詞觸發的當下 (用戶開口)
     *  立即探測一次雲端連通性。單次結果即時生效, 不用等 30 秒 watchdog 或
     *  2 次確認 - 用戶實際開口那一刻的證據最可信, 而且探測 (~1-7s) 和講話+
     *  辨識並行, 機器人回答時模式已經和現實一致。由 RobotEventReceiver 的
     *  tts_hint_wakeup case 叫。 */
    public static void triggerWakeupProbe() {
        final MainActivity inst = sInstance;
        if (inst == null || !inst.offlineGrammarAutoSwitch || !inst.speechReady
                || inst.offlineWatchdogHandler == null) {
            return;
        }
        inst.offlineWatchdogHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean online = hasRealInternet();
                    if (online != inst.lastProbeOnline) {
                        Log.i(TAG, "wakeup probe: internet " + (online ? "UP" : "DOWN")
                                + " -> switching mode now");
                        inst.lastProbeOnline = online;
                        inst.offlineProbeUpCount = 0;
                        inst.offlineProbeDownCount = 0;
                        inst.applyConnectivityMode(online, "wakeup");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "wakeup probe error: " + e.getMessage());
                }
            }
        });
    }

    /** 自動切換的入口 - 網路狀態變化或 App 啟動 (speech_ready 之後) 都會執行。
     *
     *  2026-08 修正 (實測網路飄忽的教訓):
     *  - 轉「離線」即時生效 (挽救講不了話的情況, 代價低)
     *  - 轉「雲端」要 MODE_SWITCH_MIN_INTERVAL_MS 內沒有再翻轉才執行, 避免
     *    stop/start 文法循環使中間那段時間講話完全沒反應
     *  - 只有 lastGrammarBuildOk==false 時才重新構建; 已經構建過就直接
     *    startGrammar, 不要無謂地 destroyASR。 */
    private void applyConnectivityMode(boolean connected, String reason) {
        if (!offlineGrammarAutoSwitch || !speechReady) {
            return;
        }
        Log.i(TAG, "applyConnectivityMode(" + connected + ", " + reason + ")"
                + " offlineActive=" + offlineGrammarActive
                + " lastGrammarBuildOk=" + lastGrammarBuildOk);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!connected) {
            if (offlineGrammarActive || grammarInitInFlight) {
                return; // 已經在離線模式/已經構建中, 不用重複開啟
            }
            // 確保 ASR binding 走 iFlytek (zh_cn), 這個 call 對已綁定的情況無害
            try {
                robot.speech_setRecognizedLanguage("zh_cn");
            } catch (Exception e) {
                Log.w(TAG, "setRecognizedLanguage failed during auto switch: " + e.getMessage());
            }
            if (!lastGrammarBuildOk) {
                // 未構建過/上次失敗 - 用預設文法構建, 成功之後 callback 會接手 start
                pendingOfflineEnable = true;
                String bnf = readDefaultGrammarAsset();
                if (bnf != null) {
                    UbxErrorCode.API_ERROR_CODE code = doInitGrammar(bnf);
                    Log.i(TAG, "auto init grammar -> " + code);
                } else {
                    pendingOfflineEnable = false;
                    Log.w(TAG, "auto init grammar: default asset unreadable");
                }
            } else {
                UbxErrorCode.API_ERROR_CODE code = doStartGrammar();
                Log.i(TAG, "auto start grammar -> " + code);
                if (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    lastModeSwitchMs = now;
                    publishOfflineMode(true, reason);
                }
                // start 失敗: 不要立即重構建 - 等下個 watchdog 週期再試, 避免疊 build
            }
        } else {
            // 轉雲端: 加冷卻期 - 如果 15 秒內剛切換過模式, 很可能是網路
            // 飄忽, 不要跟著翻轉 (stop/start 文法成本高, 講什麼都沒反應更糟)
            if (offlineGrammarActive && now - lastModeSwitchMs < MODE_SWITCH_MIN_INTERVAL_MS) {
                Log.i(TAG, "online but within cooldown (" + (now - lastModeSwitchMs)
                        + "ms) - keeping offline grammar mode");
                return;
            }
            pendingOfflineEnable = false;
            if (offlineGrammarActive) {
                UbxErrorCode.API_ERROR_CODE code = doStopGrammar();
                Log.i(TAG, "auto stop grammar -> " + code);
                lastModeSwitchMs = now;
                publishOfflineMode(false, reason);
            }
        }
    }

    private void publishOfflineMode(boolean active, String reason) {
        EventBus.get().publish("offline_mode",
                "{\"active\":" + active
                        + ",\"connected\":" + lastProbeOnline
                        + ",\"reason\":\"" + jsonSafe(reason) + "\"}");
    }

    /** 初始化 (構建) 本地文法。結果係 async - grammar_init event/callback 收貨,
     *  errorCode==0 先算數 (lastGrammarBuildOk)。
     *  2026-08 加防重入鎖: 構建進行中再叫呢個 method 會直接略過 - firmware
     *  每次都 destroyASR 重建, 疊 build 會打壞剛建好的辨識 session。 */
    private UbxErrorCode.API_ERROR_CODE doInitGrammar(final String bnf) {
        if (grammarInitInFlight) {
            Log.i(TAG, "doInitGrammar skipped - already in flight");
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        }
        grammarInitInFlight = true;
        lastGrammarBuildOk = false;
        return robot.speech_initGrammar(bnf,
                new com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener() {
                    @Override
                    public void speechGrammarInitCallback(String grammarId, int errorCode) {
                        Log.i(TAG, "initGrammar callback: grammarId=" + grammarId
                                + " errorCode=" + errorCode);
                        if (errorCode == 0) {
                            lastGrammarBuildOk = true;
                        }
                        EventBus.get().publish("grammar_init",
                                "{\"grammarId\":\"" + jsonSafe(grammarId == null ? "" : grammarId)
                                        + "\",\"errorCode\":" + errorCode + "}");
                        // 自動切換: 構建成功而又有 pending start 就接手開始辨識
                        if (errorCode == 0 && pendingOfflineEnable && offlineGrammarAutoSwitch) {
                            pendingOfflineEnable = false;
                            UbxErrorCode.API_ERROR_CODE startCode = doStartGrammar();
                            Log.i(TAG, "pending auto start grammar -> " + startCode);
                            if (startCode == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                                lastModeSwitchMs = android.os.SystemClock.elapsedRealtime();
                                publishOfflineMode(true, "auto");
                            }
                        }
                        grammarInitInFlight = false;
                    }
                });
    }

    private UbxErrorCode.API_ERROR_CODE doStartGrammar() {
        offlineGrammarActive = true;
        UbxErrorCode.API_ERROR_CODE startCode = robot.speech_startGrammar(
                new com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener() {
                    @Override
                    public void onSpeechGrammarResult(int type, String result) {
                        // type: firmware SpeechManager d.a(int,String) 那邊
                        // "语法识别成功:<result> type:<n>" 的同一個 int -
                        // type=1 是辨識文字結果 (iFlytek JSON {"text":..,"rc":..}),
                        // 其他 type 是 focus/state 類訊號, 原樣轉發給前端查看。
                        String text = extractGrammarResultText(result);
                        EventBus.get().publish("grammar_result",
                                "{\"type\":" + type
                                        + ",\"raw\":\"" + jsonSafe(result == null ? "" : result)
                                        + "\",\"text\":\"" + jsonSafe(text == null ? "" : text) + "\"}");
                    }

                    @Override
                    public void onSpeechGrammarError(int errorCode) {
                        Log.w(TAG, "startGrammar onError: " + errorCode);
                        EventBus.get().publish("grammar_error",
                                "{\"errorCode\":" + errorCode + "}");
                    }
                });
        if (startCode != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            // SDK 層面立即失敗 (例如未 bind) 就不要進入離線模式, 等 asr_result
            // 路徑照常運作。
            offlineGrammarActive = false;
        }
        return startCode;
    }

    private UbxErrorCode.API_ERROR_CODE doStopGrammar() {
        offlineGrammarActive = false;
        pendingOfflineEnable = false;
        return robot.speech_stopGrammar();
    }

    /** 監察網路連線狀態 - CONNECTIVITY_ACTION 在 API 22 (這台機器) 仍是標準做法。
     *  收到廣播就在背景 thread 做真正網路探測再 applyConnectivityMode() - 探測
     *  是 blocking call (TCP connect), 不可以放到 main thread。 */
    private final android.content.BroadcastReceiver connectivityReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean online = hasRealInternet();
                            lastProbeOnline = online;
                            applyConnectivityMode(online, "connectivity_change");
                        }
                    }, "conn-probe").start();
                }
            };

    private void registerConnectivityReceiver() {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityReceiver, filter);
    }

    // -- 真實 MCU 韌體版本查詢 (2026-08 新增 chest/head) ---------------------------
    /**
     * 判斷一段 raw serial 回調是否為版本幀 (CHEST_READ_VERSION / HEADER_READ_VERSION 51)。
     * 標準 wire 格式: F8 8F len 01/00 00 33 payload sum ED，其中 33h=51。
     * 為兼容多次連幀或 SDK 預剝 header 的情況，掃描整段 bytes 內任何 F8 8F 窗口。
     */
    private static boolean isVersionFrame(byte[] bytes, int len, byte expectedCmd) {
        if (bytes == null || len < 8) return false;
        int n = Math.min(len, bytes.length);
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (i + 5 >= n) continue;
                if (bytes[i + 5] == expectedCmd) {
                    // 進一步確認：len byte 與實際長度大致相符 (7+payloadLen)
                    // 不強校驗 checksum，避免韌體差異導致誤判
                    return true;
                }
            }
        }
        // 兼容 SDK 已剝頭只剩 payload 的極端情況：單字節就是 cmd 的回顯
        // 此分支由外層 fallback 邏輯處理，這裡只認標準幀
        return false;
    }

    /**
     * 從版本幀中抽出 payload 並解碼為可讀字串。
     * 1) 若為標準 F8 8F 幀，payload = bytes[6 .. 6+payloadLen-1], payloadLen = (lenByte &0xFF)-7
     * 2) 若非標準幀（fallback），整段 bytes 即 payload
     * 解碼策略：先嘗試 ASCII 打印字符，若全為可打印則直接返回；否則返回點分十進制 (例如 1.18.3)
     * 或 hex 兜底。
     */
    private static String parseVersionFrame(byte[] bytes, int len) {
        if (bytes == null || len <= 0) return null;
        int n = Math.min(len, bytes.length);
        byte[] payload = null;
        int payloadLen = 0;
        // 嘗試按標準幀解析
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (bytes[i + 5] == StaticValue.CHEST_READ_VERSION || bytes[i + 5] == StaticValue.HEADER_READ_VERSION) {
                    int lenByte = bytes[i + 2] & 0xFF;
                    int pl = lenByte - 7;
                    if (pl < 0) pl = 0;
                    if (i + 6 + pl <= n) {
                        payload = new byte[pl];
                        System.arraycopy(bytes, i + 6, payload, 0, pl);
                        payloadLen = pl;
                        break;
                    }
                }
            }
        }
        if (payload == null) {
            // Fallback：整段即 payload（SDK 可能已拆掉 header）
            // 但若開頭仍是 F8 8F 則跳過 header 嘗試最後一次剝離
            if (n >= 6 && (bytes[0] & 0xFF) == 0xF8 && (bytes[1] & 0xFF) == 0x8F) {
                int lenByte = bytes[2] & 0xFF;
                int pl = lenByte - 7;
                if (pl > 0 && 6 + pl <= n) {
                    payload = new byte[pl];
                    System.arraycopy(bytes, 6, payload, 0, pl);
                    payloadLen = pl;
                } else {
                    payload = java.util.Arrays.copyOf(bytes, n);
                    payloadLen = n;
                }
            } else {
                payload = java.util.Arrays.copyOf(bytes, n);
                payloadLen = n;
            }
        }
        if (payloadLen == 0) return "(empty payload)";
        // 去掉尾部 0x00 padding
        int trim = payloadLen;
        while (trim > 0 && payload[trim - 1] == 0) trim--;
        if (trim == 0) return toHex(payload, payloadLen);
        // 先嘗試直接全可打印
        boolean allPrintable = true;
        for (int i = 0; i < trim; i++) {
            int b = payload[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) { allPrintable = false; break; }
        }
        if (allPrintable) {
            String s = new String(payload, 0, trim, StandardCharsets.US_ASCII).trim();
            s = s.replaceAll("[^A-Za-z0-9._\\-]", "");
            if (!s.isEmpty()) return s;
        }
        // 兼容真機實測：payload 開頭夾帶 cmd(0x33) + length(0x00) 等非打印前綴
        // 掃描最長可打印連續段（例如 "ALPHA2Q-CHEST-B-V352-171031"）
        int bestStart = -1, bestLen = 0, curStart = -1;
        for (int i = 0; i <= trim; i++) {
            boolean printable = i < trim && (payload[i] & 0xFF) >= 0x20 && (payload[i] & 0xFF) <= 0x7E;
            if (printable) {
                if (curStart == -1) curStart = i;
            } else {
                if (curStart != -1) {
                    int curLen = i - curStart;
                    if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
                    curStart = -1;
                }
            }
        }
        if (bestLen >= 3) {
            String s = new String(payload, bestStart, bestLen, StandardCharsets.US_ASCII).trim();
            s = s.replaceAll("[^A-Za-z0-9._\\-]", "");
            // 若最長段看起來像版本（含 V 或 - 或 . 或 ALPHA），直接返回
            if (s.length() >= 3 && (s.contains("V") || s.contains("-") || s.contains(".") || s.contains("ALPHA"))) {
                return s;
            }
            if (s.length() >= 4) return s;
        }
        // 二進制版本號：常見為 3-4 bytes 各為 major/minor/patch/build
        if (trim <= 8) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trim; i++) {
                if (i > 0) sb.append('.');
                sb.append(payload[i] & 0xFF);
            }
            return sb.toString() + " (hex:" + toHex(payload, trim) + ")";
        }
        // 兜底：返回過濾後的 ASCII + hex 對照，方便日後診斷
        String filtered = new String(payload, 0, trim, StandardCharsets.US_ASCII).replaceAll("[^\\x20-\\x7E]", "").trim();
        if (!filtered.isEmpty() && filtered.length() >= 4) return filtered;
        return toHex(payload, trim);
    }

    /**
     * 同步阻塞查詢胸口 MCU 真實韌體版本。
     * 必須在非主 thread 調用 (HttpServer worker thread)，否則 waitForInitComplete 會立刻返回。
     * @param timeoutMs 最多等幾耐 (建議 1500-2000ms)
     * @return 解碼後版本字串，失敗回 null
     */
    private String queryChestFirmwareVersion(long timeoutMs) {
        if (robot == null) return null;
        // 若尚未 ready，嘗試等一下 (此方法已保證不在主 thread)
        if (!robot.isChestReady()) {
            robot.waitChestReady(Math.min(timeoutMs, 2000));
            if (!robot.isChestReady()) {
                Log.w(TAG, "queryChestFirmwareVersion: chest not ready");
                return null;
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        chestVersionLatch = latch;
        chestVersionRaw = null;
        chestVersionLen = 0;
        UbxErrorCode.API_ERROR_CODE code = robot.chest_readFirmwareVersion();
        Log.i(TAG, "chest_readFirmwareVersion send -> " + code);
        if (code != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            chestVersionLatch = null;
            // Fallback：嘗試用 raw 幀直接發送 (F8 8F 07 00 00 33 3A ED)
            try {
                byte[] rawFrame = new byte[]{(byte)0xF8,(byte)0x8F,0x07,0x00,0x00,0x33,0x3A,(byte)0xED};
                CountDownLatch latch2 = new CountDownLatch(1);
                chestVersionLatch = latch2;
                UbxErrorCode.API_ERROR_CODE code2 = robot.chest_sendRawData(rawFrame);
                Log.i(TAG, "chest_sendRawData fallback send -> " + code2);
                if (code2 == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    boolean ok2 = latch2.await(timeoutMs, TimeUnit.MILLISECONDS);
                    if (ok2 && chestVersionRaw != null) {
                        String v = parseVersionFrame(chestVersionRaw, chestVersionLen);
                        Log.i(TAG, "chest version (raw fallback) raw=" + toHex(chestVersionRaw,chestVersionLen) + " parsed=" + v);
                        return v;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "chest raw fallback failed", e);
            } finally {
                chestVersionLatch = null;
            }
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "queryChestFirmwareVersion timeout " + timeoutMs + "ms, try raw fallback");
                // timeout 仍無回覆，補一次 raw 幀再等半個週期
                chestVersionLatch = null;
                try {
                    byte[] rawFrame = new byte[]{(byte)0xF8,(byte)0x8F,0x07,0x00,0x00,0x33,0x3A,(byte)0xED};
                    CountDownLatch latch2 = new CountDownLatch(1);
                    chestVersionLatch = latch2;
                    chestVersionRaw = null; chestVersionLen = 0;
                    UbxErrorCode.API_ERROR_CODE code2 = robot.chest_sendRawData(rawFrame);
                    Log.i(TAG, "chest timeout raw fallback send -> " + code2);
                    if (code2 == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                        boolean ok2 = latch2.await(Math.max(800, timeoutMs/2), TimeUnit.MILLISECONDS);
                        if (ok2 && chestVersionRaw != null) {
                            String v2 = parseVersionFrame(chestVersionRaw, chestVersionLen);
                            Log.i(TAG, "chest version (timeout raw fallback) raw=" + toHex(chestVersionRaw,chestVersionLen) + " parsed=" + v2);
                            return v2;
                        }
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "chest timeout raw fallback failed", e2);
                } finally {
                    chestVersionLatch = null;
                }
                return null;
            }
            if (chestVersionRaw == null) {
                Log.w(TAG, "queryChestFirmwareVersion latch counted but raw==null");
                return null;
            }
            String v = parseVersionFrame(chestVersionRaw, chestVersionLen);
            Log.i(TAG, "chest version raw=" + toHex(chestVersionRaw, chestVersionLen) + " parsed=" + v);
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            chestVersionLatch = null;
        }
    }

    private String queryHeaderFirmwareVersion(long timeoutMs) {
        if (robot == null) return null;
        if (!robot.isHeaderReady()) {
            robot.waitHeaderReady(Math.min(timeoutMs, 2000));
            if (!robot.isHeaderReady()) {
                Log.w(TAG, "queryHeaderFirmwareVersion: header not ready");
                return null;
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        headerVersionLatch = latch;
        headerVersionRaw = null;
        headerVersionLen = 0;
        UbxErrorCode.API_ERROR_CODE code = robot.header_readFirmwareVersion();
        Log.i(TAG, "header_readFirmwareVersion send -> " + code);
        if (code != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            headerVersionLatch = null;
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok || headerVersionRaw == null) return null;
            String v = parseVersionFrame(headerVersionRaw, headerVersionLen);
            Log.i(TAG, "header version raw=" + toHex(headerVersionRaw, headerVersionLen) + " parsed=" + v);
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            headerVersionLatch = null;
        }
    }

    // -- 胸口升級實作 (48/49/50，鏡像 alpha2services h.a.a$b) ---------------------------
    private int getBatteryPercentForUpgrade() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent b = registerReceiver(null, f);
            if (b == null) return -1;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            if (charging) return 100;
            if (level < 0 || scale <= 0) return -1;
            return (level * 100) / scale;
        } catch (Exception e) { return -1; }
    }

    private boolean isPowerEnoughForUpgrade() {
        int pct = getBatteryPercentForUpgrade();
        return pct < 0 || pct >= 50; // 未知時放行，已知需 >=50，與 AlphaMainSeviceImpl.java:13 MIN_UPDATE_POWER 一致
    }

    private byte[] md5OfFile(java.io.File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return md.digest();
    }

    private boolean waitForChestAck(byte expectedCmd, long timeoutMs) {
        chestUpgradeExpectedCmd = expectedCmd;
        chestUpgradeAckStatus = -1;
        CountDownLatch latch = new CountDownLatch(1);
        chestUpgradeLatch = latch;
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "chest upgrade ack timeout cmd=" + expectedCmd + " raw=" + (chestVersionRaw!=null?toHex(chestVersionRaw,chestVersionLen):"null"));
                // 超時後印最近一次 chest_rcv 原始幀以便診斷 170 頁這類數據校驗失敗
                return false;
            }
            if (expectedCmd == 49 && chestUpgradeAckStatus != 0) {
                Log.w(TAG, "chest page ack status=" + chestUpgradeAckStatus + " (page data may be rejected, check offset " + (chestUpgradeCurrentPage*128) + ")");
                return false;
            }
            return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { chestUpgradeLatch = null; }
    }

    private void resetChestUpgradeState() {
        try {
            chestUpgradeLatch = null;
            chestVersionLatch = null;
            Thread.sleep(400);
        } catch (Exception ignored) {}
    }

    /** 真正升級線程：48(檔長)->49*2048頁(128B)->50(MD5)，鏡像 h.a.a$b:63，加入重啟後首頁即失敗的復位 */
    private void doChestUpgradeFrom(final java.io.File file, final int startPage) {
        final int fileLen = (int) file.length();
        final int totalPages = (fileLen + 127) / 128;
        chestUpgradeTotalPages = totalPages;
        chestUpgradeCurrentPage = 0;
        chestUpgradeProgress = 0;
        chestUpgradeStatus = "start";
        EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"start\",\"progress\":0,\"total\":"+totalPages+"}");
        Log.i(TAG, "chest upgrade start len=" + fileLen + " pages=" + totalPages);
        // 起始前強制復位，避免重啟後首頁即 01 失敗（殘留升級態）
        resetChestUpgradeState();
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        try {
            if (!robot.isChestReady()) {
                robot.waitChestReady(5000);
                if (!robot.isChestReady()) { throw new Exception("chest not ready"); }
            }
            // 48 START — 若連續3次仍 01，嘗試先發 END 清狀態再重試
            chestUpgradeStatus = "sending start";
            boolean startOk = false;
            for (int retry = 0; retry < 3; retry++) {
                UbxErrorCode.API_ERROR_CODE c = robot.chest_startUpdate(fileLen);
                if (c != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)48, 5000)) { startOk = true; break; }
                if (retry == 1) { Log.w(TAG, "start retry with reset"); resetChestUpgradeState(); try{Thread.sleep(600);}catch(Exception ignored){} }
            }
            if (!startOk) throw new Exception("start ack timeout");
            Thread.sleep(150); // 原廠線程無連發，給 MCU 準備
            chestUpgradeStatus = "sending pages";
            // 49 PAGES — 每頁間 30ms 間隔，避免連發撞上心跳 8d 幀；失敗頁會完整印 hex 供定位 170 頁這類點
            // 若 startPage>0，跳過前面已成功的頁（斷點續傳，解決 170 頁後重試首頁即 01）
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                // 先跳過 startPage*128 字節
                if (startPage > 0) {
                    long toSkip = (long) startPage * 128L;
                    long skipped = 0;
                    while (skipped < toSkip) {
                        long n = in.skip(toSkip - skipped);
                        if (n <= 0) break;
                        skipped += n;
                    }
                    Log.i(TAG, "resume from page " + startPage + " skipped=" + skipped);
                }
                byte[] pageBuf = new byte[128];
                int pageIdx = startPage;
                int read;
                while ((read = in.read(pageBuf, 0, 128)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new Exception("interrupted");
                    byte[] sendBuf = java.util.Arrays.copyOf(pageBuf, read);
                    boolean pageOk = false;
                    for (int retry = 0; retry < 3; retry++) {
                        UbxErrorCode.API_ERROR_CODE c = robot.chest_updatePage(sendBuf, read);
                        if (c != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) { Thread.sleep(300); continue; }
                        if (waitForChestAck((byte)49, 4000)) { pageOk = true; break; }
                        Log.w(TAG, "page " + pageIdx + " retry " + retry + " dataHead=" + toHex(sendBuf, Math.min(16,read)));
                        Thread.sleep(200);
                    }
                    if (!pageOk) {
                        Log.e(TAG, "page " + pageIdx + " failed data=" + toHex(sendBuf, Math.min(32,read)) + " offset=" + (pageIdx*128));
                        throw new Exception("page " + pageIdx + " failed after 3 retries");
                    }
                    pageIdx++;
                    chestUpgradeCurrentPage = pageIdx;
                    chestUpgradeProgress = (pageIdx * 100) / totalPages;
                    EventBus.get().publish("chest_upgrade_progress",
                        "{\"state\":\"page\",\"page\":"+pageIdx+",\"total\":"+totalPages+",\"progress\":"+chestUpgradeProgress+"}");
                    if (pageIdx % 20 == 0) Log.i(TAG, "chest page " + pageIdx + "/" + totalPages + " " + chestUpgradeProgress + "%");
                    Thread.sleep(30);
                }
            }
            // 50 END (MD5)
            chestUpgradeStatus = "sending end";
            byte[] md5 = md5OfFile(file);
            Log.i(TAG, "chest upgrade md5 " + toHex(md5, md5.length));
            boolean endOk = false;
            for (int retry = 0; retry < 3; retry++) {
                UbxErrorCode.API_ERROR_CODE c = robot.chest_endUpdate(md5);
                if (c != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) { Thread.sleep(500); continue; }
                if (waitForChestAck((byte)50, 5000)) { endOk = true; break; }
            }
            if (!endOk) throw new Exception("end ack timeout");
            chestUpgradeProgress = 100;
            chestUpgradeStatus = "success";
            EventBus.get().publish("chest_upgrade_done", "{\"ok\":true,\"progress\":100}");
            Log.i(TAG, "chest upgrade success");
            // 成功後由用戶手動重啟或自動重啟（alpha2services 原流程會重啟）
            EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"success\",\"progress\":100}");
        } catch (Exception e) {
            chestUpgradeStatus = "failed: " + e.getMessage();
            Log.w(TAG, "chest upgrade failed", e);
            EventBus.get().publish("chest_upgrade_done", "{\"ok\":false,\"error\":\"" + jsonSafe(e.getMessage()) + "\"}");
            EventBus.get().publish("chest_upgrade_progress", "{\"state\":\"failed\",\"error\":\"" + jsonSafe(e.getMessage()) + "\"}");
        } finally {
            chestUpgradeInProgress = false;
            chestUpgradeThread = null;
        }
    }

    public synchronized String startChestUpgrade() { return startChestUpgradeFrom(0); }
    public synchronized String startChestUpgradeFrom(int startPage) {
        if (chestUpgradeInProgress) return "already running";
        java.io.File f = new java.io.File("/sdcard/AlphaII_CHEST_kernel.bin");
        if (!f.exists()) return "file not found: /sdcard/AlphaII_CHEST_kernel.bin";
        if (f.length() != 262144) Log.w(TAG, "chest file size unusual: " + f.length());
        if (!isPowerEnoughForUpgrade()) {
            int pct = getBatteryPercentForUpgrade();
            return "power not enough (" + pct + "%), need >=50%";
        }
        if (!robot.isChestReady()) {
            resetChestUpgradeState();
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            robot.waitChestReady(4000);
            if (!robot.isChestReady()) return "chest not ready";
        }
        if (chestUpgradeStatus.startsWith("failed")) {
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            resetChestUpgradeState();
        }
        chestUpgradeInProgress = true;
        chestUpgradeProgress = startPage * 100 / ((int)(f.length()+127)/128);
        chestUpgradeCurrentPage = startPage;
        chestUpgradeStatus = "starting from " + startPage;
        final int sp = startPage;
        chestUpgradeThread = new Thread(new Runnable() { @Override public void run() { doChestUpgradeFrom(f, sp); } }, "ChestUpgrade");
        chestUpgradeThread.start();
        return null;
    }
    // 兼容舊的 doChestUpgrade(File) 轉調
    private void doChestUpgrade(final java.io.File file) { doChestUpgradeFrom(file, 0); }

    public String getChestUpgradeStatusJson() {
        return "{\"inProgress\":" + chestUpgradeInProgress + ",\"progress\":" + chestUpgradeProgress
            + ",\"currentPage\":" + chestUpgradeCurrentPage + ",\"totalPages\":" + chestUpgradeTotalPages
            + ",\"status\":\"" + jsonSafe(chestUpgradeStatus) + "\"}";
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

    /** Parses "f8 8f 08 ..." style hex (spaces/colons optional, case-insensitive) back
     *  into raw bytes for the debug/serial/send endpoint. Returns empty array on junk. */
    private static byte[] parseHexBytes(String hex) {
        String cleaned = hex.replaceAll("[^0-9a-fA-F]", "");
        int n = cleaned.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
