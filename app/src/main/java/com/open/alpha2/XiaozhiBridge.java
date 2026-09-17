package com.open.alpha2;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.Map;

/**
 * 小智 AI 語音對話包：HTTP API (handleXiaozhiApi)、mic 生命週期＋hold enforcer、
 * OTA/activation＋重連、vision/explain、MCP bridge (listTools/callTool)、
 * mute 鍵開關＋連線指示燈。
 *
 * 擁有關係：
 * - MainActivity 只留：implements HostState（TTS 兩法＋sonar 四法，轉交正在
 *   SpeechCenter／SonarCenter）、接線
 *   （onCreate 建構、onDestroy shutdown()、router 轉發、PIR/mute key 兩個硬件入口）。
 *   這裡 sonar state 不經 HostState——MCP sensors 4 tool
 *   經下面 sonarCenter 直調 SonarCenter。
 * - xiaozhiClient/xiaozhiAudioController/xiaozhiConfig 全部由這裡擁有。
 * - stopAllSpeechPlayback() 在 SpeechCenter（TTS core，跨域
 *   orchestration），經 stopSpeechPlayback() 碰 xiaozhi 那條播放管道。
 *
 * 線程：HTTP handler thread 可阻塞；activation/reconnect 自開
 * 背景 thread；mic hold enforcer 獨立 thread；mainHandler 只做延遲計時。
 *
 * 實機驗證限制（無穩定上網）：connect 後的成功路徑（語音對話、vision、
 * callTool 硬件工具）驗不到，要有外網時補驗。離線可驗：status/
 * activation_status/mcp_tools/list/validation error 路徑＋開機不崩潰。
 */
public final class XiaozhiBridge {
    private static final String TAG = "XiaozhiBridge";

    /**
     * 宿主縫：TTS state（enforcer＋gap 計時經這裡讀）。sonar state 不經這裡——
     * MCP sensors 4 tool 經上面 sonarCenter 直調 SonarCenter。
     * 由 MainActivity 實現。
     */
    public interface HostState {
        boolean isRobotTtsSpeaking();
        long getLastSpeechStopAtMs();
        int getSonarDistanceCm();
        int getSonarThreshold();
        int getPirTriggeredState();
        void applySonarThreshold(int distanceCm);
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final ActionDirect actionDirect;
    private final AudioCenter audioCenter;
    private final RobotStub robot;
    private final TtsCenter ttsCenter;
    private final VoskController vosk;
    private final CameraController cameraController;
    private final LedCenter ledCenter;
    private final HostState hostState;
    // sensors 4 tool 經這裡直調 (放最尾，慣例)。
    private final SonarCenter sonarCenter;
    private XiaozhiConfig xiaozhiConfig;

    public XiaozhiBridge(Context context, Handler mainHandler, ActionDirect actionDirect,
            AudioCenter audioCenter, RobotStub robot, TtsCenter ttsCenter, VoskController vosk,
            CameraController cameraController, LedCenter ledCenter, HostState hostState,
            SonarCenter sonarCenter) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.actionDirect = actionDirect;
        this.audioCenter = audioCenter;
        this.robot = robot;
        this.ttsCenter = ttsCenter;
        this.vosk = vosk;
        this.cameraController = cameraController;
        this.ledCenter = ledCenter;
        this.hostState = hostState;
        this.sonarCenter = sonarCenter;
        // 小智設定層 (含 TTS 引擎讀取+舊值遷移) 在這裡建構。
        this.xiaozhiConfig = new XiaozhiConfig(context);
        // device id 讀 prefs。
        this.xiaozhiClient = new XiaozhiClient(getXiaozhiDeviceId());
    }

    /** onDestroy 共用：斷線＋停 audio。 */
    // guard，重複調用不要每次都 new thread。
    private final java.util.concurrent.atomic.AtomicBoolean shutdownGuard =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public void shutdown() {
        if (!shutdownGuard.compareAndSet(false, true)) return;
        if (xiaozhiClient != null) {
            // disconnect() 內部會做 sendCloseFrame() (socket write, 完成
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
    }

    /** stopAllSpeechPlayback() 經這裡停小智那條播放管道。 */
    public void stopSpeechPlayback() {
        xiaozhiAudioController.stopPlayback();
    }

    /** PIR 入口用：連線先至值得送 detect text。 */
    public boolean isConnected() {
        return xiaozhiClient != null && xiaozhiClient.isOpen();
    }

    /** 同 MainActivity.releaseMicForAudioIo() 一字不差的副本 (speech_SetMIC(true) +
     *  300ms) - startXiaozhiMic 專用。 */
    private void releaseMicForAudioIo() {
        robot.speech_SetMIC(true);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // directChestReady 薄 delegate（實現見 DirectProbes）。
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    // -- 斷線／連線共用核（三處同一個順序，抽出來免改漏一處）--

    /** 斷線清理共用核：關閉 autoMode、停 mic、關閉嘴燈、（可選）斷 socket。
     *  胸燈由各 call site 自己處理（mute 鍵行 setChestMuteLed(!wasOpen)，其餘行 false），
     *  這裡不含，等時序同以前逐字一樣。 */
    private void teardownSession(boolean disconnectSocket) {
        xiaozhiAutoMode.set(false);
        xiaozhiReconnectAttempts.set(0);
        stopXiaozhiMic();
        LedCenter.stopMouthLedForTts();
        if (disconnectSocket) xiaozhiClient.disconnect();
    }

    /** activation gate 已搶到之後的尾段：設 checking、取 deviceId、背景起 flow。
     *  調用前必須已 compareAndSet(false, true) 成功（三處：mute 鍵／connect／auto_mode）。 */
    private void launchActivationFlow() {
        launchActivationFlow("XiaozhiActivationThread", null);
    }

    /** 同上，但 thread 名／deviceId 可指明（auto-connect／reconnect 保留各自 thread 名；
     *  reconnect 沿用傳入 deviceId，不重讀）。 */
    private void launchActivationFlow(String threadName, String deviceId) {
        xiaozhiActivationStatus.set(XiaozhiActivationStatus.checking());
        final String id = deviceId != null ? deviceId : getXiaozhiDeviceId();
        new Thread(new Runnable() {
            @Override
            public void run() {
                runXiaozhiActivationFlow(id);
            }
        }, threadName).start();
    }

    /** speech/stop -> 新 TTS race guard：等上次 stop 起碼
     *  SpeechCenter.STOP_TO_TTS_MIN_GAP_MS 先開始播，否則 Nuance teardown 未完成
     *  會拋 IllegalStateException（見那個 const 的 comment）。
     *  speakActivationCode() 同 self.robot.speak MCP tool 共用（之前兩份逐字一樣）。 */
    private void awaitTtsGap() {
        long sinceStopMs = System.currentTimeMillis() - hostState.getLastSpeechStopAtMs();
        if (sinceStopMs >= 0 && sinceStopMs < SpeechCenter.STOP_TO_TTS_MIN_GAP_MS) {
            try {
                Thread.sleep(SpeechCenter.STOP_TO_TTS_MIN_GAP_MS - sinceStopMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -- MCP tools: self.robot.servo_set_one/all 留在 XiaozhiBridge,
    // 沒有搬去 UbxApi/ActionDirect: UbxApi.servoSendOneCode()
    // 底層邏輯看來一樣, 但它對超範圍輸入是靜默 clamp, 這裡是刻意
    // 要求明確報錯、不靜默 clamp, 跟 servoSendOneCode() 共用會令這個已驗證的
    // 行為分別消失, 所以保留獨立實現。 --

    /** self.robot.servo_set_one/all 共用的 time_ms 範圍驗：正確返回 null，錯誤返回 err
     *  McpResult（兩個 servo tool 共用）。範圍經 ApiValidator（同 servo/one HTTP、
     *  spec/MCP 單一來源）；error 字面由常數砌出，同舊字面逐字一樣。 */
    private static SonarCenter.McpResult checkServoTimeMs(int timeMs) {
        if (timeMs < ApiValidator.SERVO_TIME_MIN_MS || timeMs > ApiValidator.SERVO_TIME_MAX_MS) {
            return SonarCenter.McpResult.err("time_ms must be between "
                    + ApiValidator.SERVO_TIME_MIN_MS + " and " + ApiValidator.SERVO_TIME_MAX_MS
                    + ", got: " + timeMs);
        }
        return null;
    }

    /** self.robot.servo_set_one/all 共用尾巴：直發結果＋chest 就緒打包（之前兩份僅變量名不同）。 */
    private SonarCenter.McpResult directServoResult(boolean sent, boolean ready) {
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        return new SonarCenter.McpResult(!MainActivity.isOk(code) || !ready,
                String.valueOf(code) + " (chestReady=" + ready + ")");
    }

    /** self.robot.servo_set_one 本體。
     *  pure-direct: 经 /dev/ttyS1 直发。
     *  同 servo/one HTTP 一套範圍（id 1-20、angle 0-255、
     *  time 20-32767），不合即報錯，不靜默 clamp。 */
    private SonarCenter.McpResult mcpServoSetOne(org.json.JSONObject arguments) {
        int mcpId = arguments.optInt("id", -1);
        if (!arguments.has("angle")) {
            return SonarCenter.McpResult.err("angle is required");
        }
        int angle = arguments.optInt("angle");
        int timeMs = arguments.optInt("time_ms", 1000);
        if (mcpId < ApiValidator.SERVO_ID_MIN || mcpId > ApiValidator.SERVO_ID_MAX) {
            return SonarCenter.McpResult.err("id must be between "
                    + ApiValidator.SERVO_ID_MIN + " and " + ApiValidator.SERVO_ID_MAX + ", got: " + mcpId);
        }
        if (angle < ApiValidator.SERVO_ANGLE_MIN || angle > ApiValidator.SERVO_ANGLE_MAX) {
            return SonarCenter.McpResult.err("angle must be between "
                    + ApiValidator.SERVO_ANGLE_MIN + " and " + ApiValidator.SERVO_ANGLE_MAX + ", got: " + angle);
        }
        SonarCenter.McpResult timeErr = checkServoTimeMs(timeMs);
        if (timeErr != null) return timeErr;
        boolean sent = HardwareDirectManager.get(appContext).chest().setSingleServo((byte) mcpId, angle, (short) timeMs);
        return directServoResult(sent, directChestReady());
    }

    /** self.robot.servo_set_all 本體。pure-direct: 经 /dev/ttyS1 直发，无需等待。 */
    private SonarCenter.McpResult mcpServoSetAll(org.json.JSONObject arguments) {
        String anglesCsv = arguments.optString("angles", "");
        if (anglesCsv.isEmpty()) {
            return SonarCenter.McpResult.err("angles is required (20 comma-separated integers)");
        }
        String[] parts = anglesCsv.split(",");
        // 要恰好 20 個、逐個 0-255，不合直接報錯。
        if (parts.length != ApiValidator.SERVO_COUNT) {
            return SonarCenter.McpResult.err("angles must have exactly " + ApiValidator.SERVO_COUNT + " comma-separated values, got " + parts.length);
        }
        int[] angles = new int[ApiValidator.SERVO_COUNT];
        for (int i = 0; i < ApiValidator.SERVO_COUNT; i++) {
            int av;
            try {
                av = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException nfe) {
                return SonarCenter.McpResult.err("angles element " + (i + 1) + " must be integer, got: " + parts[i]);
            }
            if (av < ApiValidator.SERVO_ANGLE_MIN || av > ApiValidator.SERVO_ANGLE_MAX) {
                return SonarCenter.McpResult.err("angles element " + (i + 1) + " must be between "
                        + ApiValidator.SERVO_ANGLE_MIN + " and " + ApiValidator.SERVO_ANGLE_MAX + ", got: " + av);
            }
            angles[i] = av;
        }
        int timeMs = arguments.optInt("time_ms", 1000);
        SonarCenter.McpResult timeErr = checkServoTimeMs(timeMs);
        if (timeErr != null) return timeErr;
        // pure-direct: 经 /dev/ttyS1 直发。
        boolean sentAll = HardwareDirectManager.get(appContext).chest().setAllServos(angles, (short) timeMs);
        return directServoResult(sentAll, directChestReady());
    }

    private static final String PREF_XIAOZHI_DEVICE_ID = "xiaozhi_device_id";

    /** 官方 xiaozhi-esp32 firmware 寫死用的 vision/explain endpoint (esp32_camera.cc
     *  Explain() 實作) - 這個 URL 不會經 OTA check_version 的回應帶回來 (見
     *  runXiaozhiActivationFlow() 的 comment: response 只有 activation/websocket
     *  兩個 block), 所以要獨立一個設定。自訂 server 開著的時候如果沒填這個, 就跟回
     *  官方這個 - 很多自架 server 都沒實作 vision explain, 這種情況下 take_photo
     *  call 出去會收到 404/連不到, self.camera.take_photo 的 case 會將這個原因
     *  告訴 LLM 知道, 而不是默默假裝成功。
     *
     * 官方 esp32_camera.cc (SetExplainUrl/Explain()) 同 GitHub issue #708
     *  實機 log 顯示官方 firmware 打的是 http:// (不加密):
     *  "Opening HTTP connection to http://api.xiaozhi.me/mcp/vision/explain"，
     *  不同 scheme 在 server 側可能是不同 virtual host/沒有 mapping，會 404。 */
    /** Fallback vision/explain URL, only used when the server hasn't (yet) told us
     *  its real one via the "initialize" MCP request's params.capabilities.vision
     *  (see XiaozhiClient.getVisionUrl()'s comment for the full story - that's the
     *  authoritative source; this constant is a last-resort default for the case
     *  where take_photo is somehow called before any "initialize" has been
     *  received). 不保證對 - 純粹一個合理猜測的底線值, 不應該是主要路徑。
     *
     * 反編譯實測拍照成功的第三方 apk (package com.huihongcloud.xiaozhi)
     *  證實 OTA 用的是 https://api.tenclass.net/xiaozhi/ota/
     *  (和 DEFAULT_OTA_URL 一致)；api.xiaozhi.me 沒有 /mcp/vision/explain 路由，
     *  所以跟 api.tenclass.net，scheme 跟 DEFAULT_OTA_URL 一致用 https。 */
    private static final String DEFAULT_VISION_URL = "https://api.tenclass.net/xiaozhi/mcp/vision/explain";
    private static final String PREF_XIAOZHI_VISION_URL = "xiaozhi_vision_url";
    /** 相機解析度 (用戶指定) - take_photo 特意用小於一般 camera/snapshot 預覽的
     *  解析度, 因為這張照片只是要上傳去 vision explain 給 LLM 「看」, 不是給人單獨
     *  看的照片, 小一點可以讓上傳/處理快一點, 也夠 LLM 辨識到大致內容。 */
    private static final int XIAOZHI_PHOTO_WIDTH = 480;
    private static final int XIAOZHI_PHOTO_HEIGHT = 360;

    // 記住最近一次 self.camera.take_photo 拿到的 "async, 未完成"
    // uuid (見 xiaozhiVisionExplainRequest() 的 comment) - 給之後 LLM
    // 主動再發的 "self.camera.image_to_text" tools/call 用來核對/取回真正描述。
    // 只記最新一個 (單一 device, 沒有並行 take_photo 的需要) - 用完/逾時後應
    // 清成 null, 避免舊 uuid 混進新一次 call。
    private volatile String lastPendingPhotoUuid;

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

    /** Tracks consecutive unexpected-disconnect reconnect attempts for
     *  xiaozhiScheduleReconnect()'s backoff - reset to 0 on any successful (re)connect
     *  (see runXiaozhiActivationFlow()'s success path) so a stable connection later
     *  doesn't inherit a long delay from an earlier flaky period. */
    private final java.util.concurrent.atomic.AtomicInteger xiaozhiReconnectAttempts =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** xiaozhiScheduleReconnect() 意外斷線之後有 5 秒 backoff delay,
     *  這 5 秒裡面 xiaozhiActivationStatus 還停留在斷線前那個值 (通常是
     *  CONNECTED), 不在 "connect" case 的 guard 擋著的 stage 名單裡面, 用戶如果
     *  在這 5 秒內按「連線」就會通過 guard、額外啟動多一條 runXiaozhiActivationFlow
     *  thread - 和 5 秒後真正觸發的自動重連 thread 同時運行, 兩條互相干擾
     *  xiaozhiActivationStatus/xiaozhiClient 的狀態, 讓連線更加不穩定、越來越
     *  糟。單靠 xiaozhiActivationStatus 的 stage 判斷不夠, 因為由「決定要
     *  起 thread」到「thread 真正設回那個 stage」中間有時間差, 這個窗口裡面
     *  判斷會判錯。用這個獨立的 AtomicBoolean 做 compareAndSet 原子操作,
     *  保證整個 app 任何時候最多只有一條 runXiaozhiActivationFlow 在執行 -
     *  三個起 thread 的入口 (connect case / auto_mode case /
     *  xiaozhiScheduleReconnect 的 delayed runnable) 都要經這個 gate,
     *  runXiaozhiActivationFlow() 本身在 finally 釋放。 */
    private final java.util.concurrent.atomic.AtomicBoolean xiaozhiActivationInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /** Bearer token from the most recent successful runXiaozhiActivationFlow() -
     *  reused for the vision/explain HTTP call (self.camera.take_photo tool, see
     *  xiaozhiVisionExplain()) since that endpoint uses the same Device-Id/Client-Id/
     *  Authorization headers as the WebSocket connection itself, not a separate
     *  credential. null until the first successful connect. */
    private volatile String xiaozhiAccessToken;
    // vision/explain 要送同 WebSocket 連接一樣的 Device-Id/Client-Id/Authorization
    // headers；反編譯實測成功的第三方 apk 證實有送 Client-Id (連接 WebSocket
    // 那個 client_id)。這個 field 保存 session 用的 clientId，供 vision request 讀。
    private volatile String xiaozhiClientId;

    // listTools() (見 xiaozhiMcpBridge()) 每次被 call 都會存下一份
    // 完整、未過濾的 tool 清單到這裡 - 給 "mcp_tools/list" HTTP endpoint (MCP 設定
    // card 用) 讀, 讓這個 card 可以顯示全部 tool 連同已 disable 的那些。初始為 null
    // (未連過 XiaoZhi/未收過 tools/list 之前), HTTP handler 要處理這個情況 (fallback
    // 直接 call 一次 listTools() 逼它起回一份清單, 因為這個 card 應該在用戶未連接之前
    // 也看得到有哪些 tool 可以 enable/disable)。
    private volatile org.json.JSONArray lastFullMcpToolList = null;

    // -- 心口 mute 鍵 LED (chest cmd=68) ------------------------------------------
    // headboard v1.1 + 舊版 alpha2services 之下按 mute 鍵 MCU 不會
    // 自己點燈, 我們在這裡補上: 按一下 → toggle 燈 (亮=muted 視覺狀態), 放開不理。
    private static final byte CHEST_MUTE_LED_CMD = 68; // 0x44, 實機掃描確認
    private volatile boolean chestMuteLedOn = false;
    // 實機 log：每次按鍵送出去的全部是 68[00] - press 事件重複
    // 觸發導致 toggle 兩次又變回原狀。加 400ms 防抖: 太接近的第二次 press 當作同一次。
    private static final long MUTE_PRESS_DEBOUNCE_MS = 400;
    private final java.util.concurrent.atomic.AtomicLong lastMutePressMs =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 胸口 mute 鍵 (-111) 硬件入口本體。
     *  pressed=true (按下) 就 toggle mute LED (小智開關); pressed=false (放開)
     *  不理。static 縫留在 MainActivity.onMuteKeyEvent (RobotEventReceiver／
     *  frozen onDirectChestFrame 經那裡進入，簽名不變)。 */
    public void onMuteKeyEvent(final boolean pressed) {
        if (!pressed) {
            return;
        }
        toggleChestMuteLed();
    }

    public void toggleChestMuteLed() {
        long now = android.os.SystemClock.elapsedRealtime();
        long last = lastMutePressMs.get();
        if (now - last < MUTE_PRESS_DEBOUNCE_MS) {
            Log.d(TAG, "mute press debounced (gap " + (now - last) + "ms)");
            return;
        }
        lastMutePressMs.set(now);
        // mute 鍵是「小智開關」- 按一下連線 (燈亮 = 已連接),
        // 再按一下斷線 (燈滅)。LED 由實際連線事件驅動 (見 runXiaozhiActivationFlow()
        // 的 connected hook / DisconnectListener / activation error hook), 這裡
        // 按下瞬間的 send 只是即時的視覺反應, 之後會被真實狀態 hook 校正。
        final boolean wasOpen = xiaozhiClient.isOpen();
        ledCenter.postPadLed(() -> {
            if (wasOpen) {
                // 斷線 - 和 handleXiaozhiApi 的 "disconnect" case 一致的清理順序。
                teardownSession(true);
                Log.i(TAG, "mute key -> xiaozhi DISCONNECT");
            } else {
                // 連線 - 同 "connect" case 一致: 搶 activation gate, 背景執行
                // OTA/activation flow; 完成後 CONNECTED hook 會再確認 LED。
                // 開關語意是「連線並隨時語音對話」，連線完成後 auto_mode 會立即
                // startXiaozhiMic() 取得 mic (見 runXiaozhiActivationFlow() 的
                // CONNECTED branch 和 "auto_mode" case)。
                xiaozhiAutoMode.set(true);
                if (xiaozhiActivationInFlight.compareAndSet(false, true)) {
                    launchActivationFlow();
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
        ledCenter.postPadLed(() -> {
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
            // pure-direct: 经 /dev/ttyS1 直发。
            boolean sent = HardwareDirectManager.get(appContext).chest().sendRaw(frame);
            Log.i(TAG, "mute LED " + (on ? "ON" : "OFF") + " -> " + MainActivity.directCode(sent).name());
        } catch (Throwable t) {
            Log.w(TAG, "sendChestMuteLedImage failed", t);
        }
    }

    public HttpServer.ApiResponse handleXiaozhiApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            case "supported":
                // Reported separately from "connected" state so the browser UI can grey
                // out/hide the audio (Opus) controls specifically without also hiding
                // text-only chat.
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
                                ? "\"" + MainActivity.jsonSafe(xiaozhiClient.getSessionId()) + "\"" : "null") + "}");

            case "activation_status": {
                XiaozhiActivationStatus s = xiaozhiActivationStatus.get();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"stage\":\"").append(s.stageJson()).append("\"");
                if (s.activationCode != null) sb.append(",\"code\":\"").append(MainActivity.jsonSafe(s.activationCode)).append("\"");
                if (s.activationMessage != null) sb.append(",\"message\":\"").append(MainActivity.jsonSafe(s.activationMessage)).append("\"");
                if (s.errorMessage != null) sb.append(",\"error\":\"").append(MainActivity.jsonSafe(s.errorMessage)).append("\"");
                if (s.sessionId != null) sb.append(",\"sessionId\":\"").append(MainActivity.jsonSafe(s.sessionId)).append("\"");
                sb.append("}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "ota_config/get":
                return xiaozhiConfig.otaConfigGet();

            case "ota_config/set":
                return xiaozhiConfig.otaConfigSet(query, xiaozhiClient.isOpen());

            // MCP 設定 card 用的三個 endpoint。
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
                try {
                    if (fullList == null) {
                        // 未連過 XiaoZhi/未收過任何 tools/list request - 個 card 應該
                        // 讓用戶在還沒連線之前也能看到有哪些 tool 可以 enable/disable, 所以
                        // 這裡強制執行一次 listTools() 建立清單 (side effect 會存到
                        // lastFullMcpToolList, 下次不用再強制)。
                        xiaozhiMcpBridge().listTools();
                        fullList = lastFullMcpToolList;
                    }
                    java.util.Set<String> disabledNames = xiaozhiConfig.getMcpDisabledToolNames();
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
                    result.put("mcpEnabled", xiaozhiConfig.isMcpEnabled());
                    return HttpServer.ApiResponse.ok(result.toString());
                } catch (org.json.JSONException e) {
                    return HttpServer.ApiResponse.error("failed to build tool list: " + e.getMessage());
                }
            }

            case "mcp_config/get":
                return xiaozhiConfig.mcpConfigGet();

            case "mcp_config/set":
                return xiaozhiConfig.mcpConfigSet(query);

            case "tts_config/get":
                return xiaozhiConfig.ttsConfigGet();

            case "tts_config/set":
                return xiaozhiConfig.ttsConfigSet(query);

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
                launchActivationFlow();
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
                teardownSession(true);
                // mute 鍵 LED = 小智連線指示燈 - web UI 斷線都要關燈。
                setChestMuteLed(false);
                return HttpServer.ApiResponse.okTrue();

            case "mic/start":
                return startXiaozhiMic();

            case "mic/stop": {
                stopXiaozhiMic();
                return HttpServer.ApiResponse.okTrue();
            }

            case "auto_mode": {
                // 用 requireBoolean（前端/MCP 傳真 boolean 即 "true"/"false"）。
                boolean enabled = ApiValidator.requireBoolean(query, "enabled");
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
                            launchActivationFlow();
                        }
                    } else if (!xiaozhiAudioController.isCapturing()) {
                        startXiaozhiMic();
                    }
                } else {
                    stopXiaozhiMic();
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
            }
            case "boot_voice/get":
                return xiaozhiConfig.bootVoiceGet();

            case "boot_voice/set":
                return xiaozhiConfig.bootVoiceSet(query);

            case "send_text": {
                String text = ApiValidator.require(query, "text");
                if (!xiaozhiClient.isOpen()) {
                    return HttpServer.ApiResponse.error("not connected - call xiaozhi/connect first");
                }
                // Typed text bypasses the mic entirely - no startCapture()/Opus
                // involved, so this works even on devices where isAudioSupported() is
                // false (see XiaozhiClient.isAudioSupported()'s API-21 gate, which only
                // applies to the Opus mic/speaker path, not plain JSON text messages).
                String sendError = sendDetectText(text);
                if (sendError != null) {
                    return HttpServer.ApiResponse.error("failed to send text: " + sendError);
                }
                return HttpServer.ApiResponse.okTrue();
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
     *  (PIR 密集 broadcast 時連環阻塞會卡死整個 system 連 adb 都沒有反應，
     *  所以 onPirStateReceived() 要用獨立 thread 包住才呼叫這個方法。)
     *
     *  送成功就回傳 null, 失敗就回傳錯誤訊息 (不拋 exception, 讓 caller 自己決定
     *  要不要讓用戶看到 / 要不要 log)。
     *
     * 官方 xiaozhi.me 對沒有標記的 "detect" 訊息會拒絕長文字 (錯誤 "detect is only
     *  for wake words, do not send long texts")；送出訊息要多附 "source":"text"
     *  同 "session_id" (見 XiaozhiClient.sendListenDetectText())，server 就不會
     *  誤當 wake-word 驗長度，所以不用切段，一次送完。
     *
     *  小智常開時 mic 一直開著持續送 Opus binary frame，detect JSON 在 audio
     *  micActive/micHeld 都是 true), 打字那句 detect JSON message 就在這股持續
     *  的 audio stream 中途插入送出 - server 側極可能把 mic 錄到的背景聲音當成
     *  「主要輸入」, 打字那句被 audio stream 蓋過/觸發衝突判斷, 兩者都沒有被正常
     *  處理。這裡在送 detect 之前暫停 mic capture (不用斷開整個 XiaoZhi 連線,
     *  只是停止送 audio frame), 讓 detect message 在那一刻是唯一的輸入, 送完
     *  之後如果小智常開仍然開著就重新開啟 mic (沿用
     *  startXiaozhiMic()/stopXiaozhiMic() 已有的 mic 生命週期管理)。
     *
     *  長打字對白仍是開放問題，未確診，等真機 log 先處理。 */
    public String sendDetectText(String text) {
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
            if (micWasActive && xiaozhiAutoMode.get()
                    && (vosk == null || !vosk.isListening())) {
                startXiaozhiMic();
            }
            return e.getMessage();
        }
        if (micWasActive && xiaozhiAutoMode.get()) {
            // 送完 detect 即重開 mic 會在幾百毫秒內再送 {"type":"listen","state":"start",
            // "mode":"auto"}，兩個連續 listen 轉換之間沒有足夠時間給 server 處理前一個，
            // 可能重置 session 蓋過文字訊息；所以多給 300ms 緩衝，等 server 處理完 detect.
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 後開者得 mic：這段時間 vosk 搶了 mic 就不回搶（等用戶手動 mic/start）。
            if (vosk == null || !vosk.isListening()) {
                startXiaozhiMic();
            }
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
        // alpha2services wake-word 引擎持續佔用 mic，這部機 HAL 不支援多 process
        // 同時開 mic input，直接開 AudioRecord 會收不到聲。同 handleMicStream()
        // (Speech/Mic tab 獨立 mic 串流) 一樣，先用 releaseMicForAudioIo() 拿
        // mic 擁有權 (speech_SetMIC(true) + 300ms 避 race)，先開 AudioRecord。
        releaseMicForAudioIo();
        try {
            xiaozhiClient.sendListenStart();
        } catch (java.io.IOException e) {
            robot.speech_SetMIC(false); // 硬體都還沒開就立即放棄, 將 mic 還給機器人
            return HttpServer.ApiResponse.error("failed to signal listen-start: " + e.getMessage());
        }
        // 後開者得 mic (單 input HAL)：vosk 的 recorder 一日不停，這裡開
        // playback/capture 就撞 HAL（開不到／讀垃圾／整條 recognizer thread 崩潰
        // FATAL，見實測）。先停 vosk 再開自己，同 VoskApi.voskStart 經
        // yieldMicToVosk 停小智對稱。不自動重開——vosk_state event 會通知前端
        // 轉灰燈，用戶手動回去按開始。
        if (vosk != null && vosk.isListening()) {
            vosk.stopListening();
            Log.i(TAG, "vosk stopped to yield mic to xiaozhi");
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
        // AudioHardwareTiny 剛開完 AudioTrack (output) 的 pthread 未 settle 即開
        // AudioRecord (input)，會撞 "adev_open_input_stream:channel is not support"；
        // Java 層 STATE_INITIALIZED 照過 check，但底層 HAL 開 input 失敗，.read()
        // 收不到真聲，送去 server 是靜音/垃圾。所以加短 sleep，等 output HAL settle
        // 先開 input，避開 race。
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
            if (vosk != null) {
                try {
                    vosk.setPaused(false);
                } catch (Throwable ignore) {
                }
            }
            robot.speech_SetMIC(false);
            return HttpServer.ApiResponse.error("failed to start mic capture: " + captureResult.error);
        }
        // Mic 擁有權和硬體都成功取得 - 通知前端將燈號轉綠 (見 index.html
        // #xiaozhiMicLed / app-xiaozhi.js 的 xiaozhi_mic_state 事件處理)。
        xiaozhiMicHeld = true;
        startXiaozhiMicHoldEnforcer();
        EventBus.get().publish(XIAOZHI_MIC_STATE_EVENT, "{\"held\":true}");
        return HttpServer.ApiResponse.okTrue();
    }

    /** Stops the mic capture + playback pair - shared by the "mic/stop" HTTP endpoint
     *  and "auto_mode" turning off. See startXiaozhiMic()'s javadoc for why this is
     *  factored out. */
    private void stopXiaozhiMic() {
        stopXiaozhiMicHoldEnforcer();
        xiaozhiAudioController.stopCapture();
        xiaozhiAudioController.stopPlayback();
        // 小智 session 完了，Vosk 那邊如果已暫停就 resume
        // (播 opus／TTS 的時候 pause 了)。不自動重開聆聽——要開用戶自己按。
        if (vosk != null) {
            try {
                vosk.setPaused(false);
            } catch (Throwable ignore) {
            }
        }
        if (xiaozhiClient.isOpen()) {
            try {
                xiaozhiClient.sendListenStop();
            } catch (java.io.IOException e) {
                // Not fatal - the mic/AudioTrack are already released above
                // regardless of whether this final courtesy message reaches the
                // server (e.g. the connection may have just dropped).
                Log.w(TAG, "Failed to signal listen-stop: " + e.getMessage());
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

    /** 後開者得 mic 的 vosk 方向：vosk/start 調用（見 VoskApi.voskStart）。
     *  小智開著（連線 and/or 拿著 mic）就整條 session 踢斷——不止停 mic，
     *  同 "disconnect" case / mute 鍵斷線同一個清理順序（關閉 autoMode、停 mic、
     *  關閉嘴燈、斷線、關閉胸燈），等 vosk 先開到 recorder（單 input HAL）。
     *  同 startXiaozhiMic() 停 vosk 對稱。不自動幫小智重開（對稱那邊停完
     *  vosk 都不自動重開，要開用戶自己按 mute 鍵/auto_mode）。
     *  順序重要：先關 autoMode 再斷線——DisconnectListener 靠它決定是否
     *  自動重連；用戶主動 disconnect() 本身不會觸發 listener（見 XiaozhiClient）。
     *  沒有開著、沒有拿著、autoMode 又沒開就立刻返回（平時 vosk 啟停零額外開銷）。 */

    public void yieldMicToVosk() {
        boolean open = xiaozhiClient.isOpen();
        if (!open && !xiaozhiMicHeld && !xiaozhiAudioController.isCapturing() && !xiaozhiAutoMode.get()) return;
        teardownSession(open);
        if (open) {
            Log.i(TAG, "vosk start -> xiaozhi DISCONNECT (mic yielded)");
        }
        setChestMuteLed(false);
    }

    /** 和 startMicHoldEnforcer() (Mic tab 專用) 對應的 XiaoZhi 版本 - 背景 thread
     *  持續每 MainActivity.MIC_HOLD_ENFORCER_INTERVAL_MS 重新呼叫一次 speech_SetMIC(true),
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
                    // 見 SpeechCenter robotTtsSpeaking field javadoc - 機身 robot-side TTS
                    // (iflytek/nuance) 正在播放就跳過這一輪, 不要用
                    // speech_SetMIC(true) 打斷它。跳過也不會讓 mic 太久沒人持有:
                    // 下一個 tick (MainActivity.MIC_HOLD_ENFORCER_INTERVAL_MS 之後) 會再檢查
                    // 一次, TTS 讀完 (onServerPlayEnd 改回 false) 就會搶回。
                    if (xiaozhiMicHeld && !hostState.isRobotTtsSpeaking()) {
                        robot.speech_SetMIC(true);
                    }
                    try {
                        Thread.sleep(MainActivity.MIC_HOLD_ENFORCER_INTERVAL_MS);
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
        // 這個 try/finally 包住整個 method body，保證無論如何 exit，
        // xiaozhiActivationInFlight 這個 gate 一定會釋放——釋放不到會永久鎖死
        // 在「activation already in progress」。見 field javadoc。
        try {
            // 自訂 server 開關 (見 PREF_XIAOZHI_OTA_CUSTOM_ENABLED/PREF_XIAOZHI_OTA_URL) -
            // 開啟就用自己填的 OTA URL, 關閉則沿用官方 xiaozhi.me 預設。OTA endpoint 一般
            // 已經足夠切換成自架 server (check_version 回應通常會一併附上真正的 websocket
            // url/token 送回), 但不是所有自架方案都能依照這個協議形狀 - 有
            // wsUrl/deviceId/token 三個可選 override (PREF_XIAOZHI_WS_URL_OVERRIDE
            // 等), 留空就繼續用 OTA response/自動產生的那個值, 有填就用來覆蓋, 應付需要
            // 手動配置的自架 server。
            XiaozhiConfig.OtaConfig otaCfg = xiaozhiConfig.getOtaConfig();
            String otaUrl = otaCfg.otaUrl;
            String wsUrlOverride = otaCfg.wsUrl;
            String deviceIdOverride = otaCfg.deviceId;
            String tokenOverride = otaCfg.token;
            // deviceId override 要在 OTA client 建構之前就決定 - Device-Id header
            // 從 OTA check_version 的 request 開始就要用同一個值 (和 WebSocket 那邊一致,
            // 見 getXiaozhiDeviceId() 的 comment), 不只是影響最終 connect() 那一下。
            // 用一個新的 final 變數來裝最終值 (而不是重新賦值 method 的 deviceId
            // parameter 本身) - 這個 method 尾段的匿名類 (DisconnectListener) 有
            // capture deviceId, capture 到的 local variable 一定要是 effectively
            // final, 重新賦值會導致這個 method 編譯不過。
            final String effectiveDeviceId = deviceIdOverride.isEmpty() ? deviceId : deviceIdOverride;
            // 存下這個 session 用的 clientId，給 xiaozhiVisionExplain()
            // 送回同一個 Client-Id header (見 xiaozhiClientId field)。
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
                // 配對碼經 EventBus -> WebSocketServer -> 前端 event log 推送一次，
                // 同 HTTP polling 並存 (不衝突，前端防重複邏輯獨立處理 polling 那邊)。
                EventBus.get().publish("xiaozhi_activation",
                        "{\"code\":\"" + MainActivity.jsonSafe(code) + "\",\"message\":\""
                                + MainActivity.jsonSafe(message != null ? message : "") + "\"}");
                // 機身讀出配對碼，給用戶有足夠反應時間打開 xiaozhi.me 輸入。
                speakActivationCode(code);

                xiaozhiActivationStatus.set(XiaozhiActivationStatus.polling(code, message));
                XiaozhiOtaClient.ActivationResult activationResult = ota.pollActivation(
                        checkResult.activationChallenge, checkResult.activationTimeoutMs,
                        new XiaozhiOtaClient.PollCallback() {
                            @Override
                            public void onPoll(int attemptNumber) {
                                Log.i(TAG, "XiaoZhi activation poll #" + attemptNumber);
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
                    // 選了本地 TTS 引擎 (見 XiaozhiConfig.getTtsEngine()) 就
                    // 完全靜音這條 cloud opus 聲軌 - 只是不 forward 到
                    // XiaozhiAudioController, decode/AudioTrack pipeline 本身
                    // 沒有改, 一切回 "xiaozhi" 就立即恢復原本行為。
                    if (!"xiaozhi".equals(xiaozhiConfig.getTtsEngine())) {
                        return;
                    }
                    xiaozhiAudioController.onIncomingOpusFrame(opusData);
                }
            });
            // PHASE 4 (小智常開/auto mode): re-wired on every (re)connect for the same
            // reason as setAudioSink() above - see XiaozhiClient.TtsStateListener's
            // javadoc for what this drives.
            // 嘴部 LED 同步 - 沿用本地 TTS 已有的
            // startMouthLedForTts()/stopMouthLedForTts() (MouthLedData breathing 效果),
            // 但這裡要對應 XiaoZhi 自己那套 tts state (start/sentence_start/stop, 見
            // websocket.md 和實測 logcat), 不是本地 TTS 那個單次 speech_startTTS。
            // "start" = 這句/這段回應開始播 -> 點亮; "sentence_start" 純粹是分句
                            // (同一段回應裡面, 中途不停) -> 不用理會, 燈應該一直亮到整段答案說完;
                            // "stop" = 整段回應播完 -> 關燈。沿用 xiaozhiAutoMode/mic-restart 那個
                            // 同一個 case 分支, 關燈和重新聆聽是同一時機發生, 沒有額外 race。
            xiaozhiClient.setTtsStateListener(new XiaozhiClient.TtsStateListener() {
                @Override
                public void onTtsState(String stateValue) {
                    if ("start".equals(stateValue)) {
                        LedCenter.startMouthLedForTts();
                        // random 動作要同 tts 一齊發生，不是說完再做：在 "start"
                        // (開講那刻) 即觸發，等動作同說話同步。實際邏輯在
                        // AudioCenter.triggerRandomFillerAction()，播本地音樂都用同一個 helper。
                        audioCenter.triggerRandomFillerAction();
                    } else if ("stop".equals(stateValue)) {
                        LedCenter.stopMouthLedForTts();
                        if (xiaozhiAutoMode.get()) {
                            // 後開者得 mic：vosk 搶了 mic 就不回搶（等用戶手動 mic/start)。
                            if (vosk == null || !vosk.isListening()) {
                                startXiaozhiMic();
                            }
                        }
                    }
                }
            });
            // server 可能在對話中途主動 send WebSocket close frame 斷線
            // (見 XiaozhiClient case 0x8 describeCloseFrame() log 查 close code)。
            // 小智常開時意外斷線會自動重連，見 xiaozhiScheduleReconnect() backoff。
            xiaozhiClient.setDisconnectListener(new XiaozhiClient.DisconnectListener() {
                @Override
                public void onUnexpectedDisconnect() {
                    // mute 鍵 LED = 小智連線指示燈，斷線就關燈。
                    setChestMuteLed(false);
                    // 意外斷線可能發生在 TTS 播放中途 (收到 "start" 未收到 "stop")，
                    // 嘴部 LED 會停留在點亮 breathing 狀態，所以斷線一定關燈。
                    LedCenter.stopMouthLedForTts();
                    // 一斷線即 set 為 idle()，等 UI/guard 即時反映真實狀態——否則會停留
                    // 在斷線前的 CONNECTED，直到 5 秒 backoff 後重連 thread 才更新，
                    // 中間手動按「連線」會同自動重連撞在一起 (見 field javadoc)。
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
            // mute 鍵 LED = 小智連線指示燈，真正連上才亮 (按鍵當下只是即時反應，
            // 這裡才是權威狀態)。
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
            // catch (Throwable) 兜底 (不止 IOException——handshake/URL parse 等
            // RuntimeException 都包含)，保證任何失敗 stage 都退回 ERROR，不卡死中途。
            Log.w(TAG, "XiaoZhi activation flow failed: " + e.getMessage());
            xiaozhiActivationStatus.set(XiaozhiActivationStatus.error(
                    e.getMessage() != null ? e.getMessage() : e.toString()));
            // activation 失敗 (例如 TLS 證書/網絡問題)，mute LED 關閉，不留「假連線」燈號。
            setChestMuteLed(false);
        } finally {
            // 見這個 method 開頭那個 try 和 xiaozhiActivationInFlight field 的
            // javadoc: 不論上面如何 exit, 這個 gate 一定會被釋放, 下次 connect
            // (手動按鍵或者自動重連) 先可以再次通過。
            xiaozhiActivationInFlight.set(false);
        }
    }

    /**
     * 開機語音模式分派（實驗 tab「開機語音」卡三選一，開機延遲任務唯一入口）。
     * off＝什麼都不做；xiaozhi＝沿用 maybeAutoConnect；vosk＝等 model READY
     * 即時啟動聆聽（背景 thread，不阻塞開機）。冪等：模式不合／已在聽直接返回。
     */
    public void maybeBootVoice(final String why) {
        final String mode = xiaozhiConfig.getBootVoiceMode();
        if (XiaozhiConfig.BOOT_VOICE_XIAOZHI.equals(mode)) {
            maybeAutoConnect(why);
            return;
        }
        if (!XiaozhiConfig.BOOT_VOICE_VOSK.equals(mode)) return;
        if (vosk == null) {
            Log.w(TAG, "boot voice vosk skipped: need API 21+");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // Model 載入中（VoskController 建構時已在背景載入上次那粒）就等，
                // 等到 READY 即啟動；中途轉換了模式／load 失敗了／久等不 ready 就結束。
                for (int i = 0; i < 45; i++) {
                    if (!XiaozhiConfig.BOOT_VOICE_VOSK.equals(xiaozhiConfig.getBootVoiceMode())) {
                        Log.i(TAG, "boot voice vosk cancelled (mode changed)");
                        return;
                    }
                    VoskController.State st = vosk.getState();
                    if (st == VoskController.State.READY) break;
                    if (st == VoskController.State.LISTENING) return;
                    if (st == VoskController.State.ERROR) {
                        Log.w(TAG, "boot voice vosk skipped: " + vosk.getLastError());
                        return;
                    }
                    if (st == VoskController.State.IDLE) {
                        // 沒有載入中、沒有載好：不用等，直接結束（再等也不會變 READY）。
                        if (VoskController.scanModels().isEmpty()) {
                            Log.w(TAG, "boot voice vosk skipped: no model on sdcard");
                        } else {
                            Log.w(TAG, "boot voice vosk skipped: no model loaded"
                                    + " (pick one in 語音 tab first)");
                        }
                        return;
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (vosk.getState() != VoskController.State.READY) {
                    Log.w(TAG, "boot voice vosk skipped: model not ready in time");
                    return;
                }
                // 同 VoskApi.voskStart 同順序：先讓小智讓 mic（開機正常時是 no-op），再起聽。
                yieldMicToVosk();
                String err = vosk.startListening();
                if (err != null) {
                    Log.w(TAG, "boot voice vosk start failed: " + err);
                } else {
                    Log.i(TAG, "boot voice vosk listening (" + why + ")");
                }
            }
        }, "BootVoiceVosk").start();
    }

    /**
     * 開app自動連接小智（開機語音模式＝xiaozhi 時才執行，見 maybeBootVoice；
     * 開關沒開/已連線/已有 activation 在執行都直接返回，由開機延遲任務同
     * connectivity 恢復兩處觸發，不會重複連。
     */
    public void maybeAutoConnect(String why) {
        if (!xiaozhiConfig.isAutoConnectEnabled()) return;
        if (xiaozhiClient == null || xiaozhiClient.isOpen()) return;
        if (!xiaozhiActivationInFlight.compareAndSet(false, true)) return;
        Log.i(TAG, "xiaozhi auto-connect (" + why + ")");
        launchActivationFlow("XiaozhiAutoConnect", null);
    }

    /** 小智常開開啟時, WebSocket 意外斷線 (見 XiaozhiClient.DisconnectListener)
     *  就自動嘗試重連, 用戶不用自己發現開關已經名存實亡才手動關開一次。
     *
     *  Exponential backoff (5s, 10s, 20s, 最多封頂 60s) 加最多 MAX_RECONNECT_ATTEMPTS
     *  次數上限, 而不是一見到斷線就立即狂重試: 如果斷線原因是伺服器端持續性問題
     *  (例如 token 失效、伺服器維護), 無限制地重試只會不斷再取得新 activation code
     *  (可能重新觸發配對流程) 和浪費電量/流量, 對用戶完全沒幫助; 加了上限之後,
     *  重試完都連不上就停止, 保留 xiaozhiActivationStatus 的 error 狀態讓用戶看到
     *  發生了什麼事, 勝過默默不斷重試下去。用戶隨時可以手動關開開關重新嘗試,
     *  重新開始個 backoff (見 runXiaozhiActivationFlow() 連接成功會 reset
     *  xiaozhiReconnectAttempts)。 */
    private void xiaozhiScheduleReconnect(final String deviceId) {
        final int attempt = xiaozhiReconnectAttempts.incrementAndGet();
        final int maxAttempts = 5;
        if (attempt > maxAttempts) {
                Log.w(TAG, "XiaoZhi reconnect: giving up after " + maxAttempts
                    + " attempts - leave 小智 off/on to retry manually");
            return;
        }
        long delayMs = Math.min(5000L * (1L << (attempt - 1)), 60000L);
            Log.i(TAG, "XiaoZhi reconnect: attempt " + attempt + "/" + maxAttempts
                + " in " + delayMs + "ms");
        // mainHandler 綁住 main thread——postDelayed() 只是延遲，Runnable 本身
        // 仍然在 main thread 跑；runXiaozhiActivationFlow() 會做 HTTPS POST，
        // 主 thread 做網絡 I/O 會拋 NetworkOnMainThreadException。所以實際工作
        // 放獨立背景 thread，mainHandler 只做延遲計時。
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
                launchActivationFlow("xiaozhi-reconnect", deviceId);
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
     *  ("一 二 三 四 五" for Chinese TTS), 經實測各 TTS 引擎都會逐字讀出，
     *  不會讀成一整個大數字。Reads the message twice
     *  with a pause, matching how a person might naturally repeat something they want
     *  written down. Mirrors the existing "speech/tts" endpoint's
     *  SpeechCenter.STOP_TO_TTS_MIN_GAP_MS race guard and mouth-LED bracket (see handleApi() below)
     *  since this runs from a background thread, not through that HTTP endpoint. */
    /** 讀出小智配對碼：行 Android 內置 TTS (同小智頁選 "Android"
     *  同一條路)——配對時刻未連 server，用不到雲端聲，只可以用本地讀。
     *  機身 TTS 無 alpha2services 會靜音，所以不用。
     *  讀不到 (engine 未 ready) 就只靠前端顯示個碼 (xiaozhi_activation event)。 */
    private void speakActivationCode(String code) {
        if (code == null || code.isEmpty()) return;
        StringBuilder spoken = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (i > 0) spoken.append(' ');
            spoken.append(c);
        }
        String text = "配對碼是 " + spoken + "。請去 xiaozhi 點 me 輸入這個碼。再說一次，配對碼是 " + spoken + "。";
        awaitTtsGap();
        LedCenter.startMouthLedForTts();
        if (!ttsCenter.speakAndroidTts(text, java.util.Locale.SIMPLIFIED_CHINESE)) {
            LedCenter.stopMouthLedForTts();
            Log.w(TAG, "Failed to speak XiaoZhi activation code via Android TTS");
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

        android.content.SharedPreferences prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString(PREF_XIAOZHI_DEVICE_ID, null);
        if (existing != null && XiaozhiConfig.isMacShaped(existing)) {
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
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
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

    /** resolveVisionEndpoint() 回包：vision/explain 用哪條 URL＋哪個 token。 */
    private static final class VisionEndpoint {
        final String url;
        final String token;
        VisionEndpoint(String url, String token) { this.url = url; this.token = token; }
    }

    /** vision url 優先順序：server 在 "initialize" 附上的 (最新鮮、最權威，
     *  見 XiaozhiClient.getVisionUrl() 同官方 mcp-protocol.md) -> 用戶自訂設定
     *  (開啟自訂 server 又沒收到 server url) -> DEFAULT_VISION_URL (最後保險)。
     *  xiaozhiTakePhotoAndExplain() 同 xiaozhiFetchImageToText() 共用（之前兩份逐字一樣）。 */
    private VisionEndpoint resolveVisionEndpoint() {
        android.content.SharedPreferences prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String serverProvidedUrl = xiaozhiClient.getVisionUrl();
        if (serverProvidedUrl != null && !serverProvidedUrl.isEmpty()) {
            return new VisionEndpoint(serverProvidedUrl, xiaozhiClient.getVisionToken());
        }
        String visionUrl = xiaozhiConfig.isOtaCustomEnabled()
                ? prefs.getString(PREF_XIAOZHI_VISION_URL, DEFAULT_VISION_URL)
                : DEFAULT_VISION_URL;
        if (visionUrl == null || visionUrl.trim().isEmpty()) {
            visionUrl = DEFAULT_VISION_URL;
        }
        return new VisionEndpoint(visionUrl, xiaozhiAccessToken);
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
        // 沿用 XiaoZhi 語音對話同一個 cameraController 實例 (整個 app 只有一個相機
        // 硬件, camera/snapshot 這類其他功能都共用它) - setRequestedResolution()
        // 只影響下一次 start(), 不會影響目前正在使用的其他 session (見
        // CameraController 的 requestedWidth/Height javadoc)。
        cameraController.setRequestedResolution(XIAOZHI_PHOTO_WIDTH, XIAOZHI_PHOTO_HEIGHT);
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return XiaozhiVisionResult.fail("camera start failed: " + started.error);
        }
        byte[] jpeg;
        try {
            // 用 CameraController.takePhoto() (Camera1 takePicture()) 做真正單張拍攝——
            // preview frame 沒有經過 HAL 完整單張 AE/AF/降噪 pipeline。
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

        // vision url＋token 經 resolveVisionEndpoint()（server 附上 -> 用戶自訂 -> 保底）。
        VisionEndpoint endpoint = resolveVisionEndpoint();
        String visionUrl = endpoint.url;
        String token = endpoint.token;

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
    // 判斷字串是否像真 UUID (8-4-4-4-12 hex 用 "-" 分隔)——用在
    // self.camera.image_to_text，篩走 LLM 填佔位符字面值 (如 "placeholder")
    // 的情況。刻意寬鬆 regex：格式正確即採信，勝過逐個字面值比對。
    private static final java.util.regex.Pattern UUID_LIKE_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static boolean isLikelyUuid(String s) {
        return s != null && UUID_LIKE_PATTERN.matcher(s).matches();
    }

    private XiaozhiVisionResult xiaozhiFetchImageToText(String uuid) {
        // vision url＋token 經 resolveVisionEndpoint()（同 take_photo 共用）。
        VisionEndpoint endpoint = resolveVisionEndpoint();
        String visionUrl = endpoint.url;
        String token = endpoint.token;
        String deviceId = getXiaozhiDeviceId();

        try {
            org.json.JSONObject payloadJson = new org.json.JSONObject();
            payloadJson.put("type", "image_to_text");
            payloadJson.put("uuid", uuid);
            byte[] payload = payloadJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            VisionHttpResult res = postVisionRequest(visionUrl, deviceId, xiaozhiClientId, token,
                    "application/json", payload);
            int status = res.status;
            String responseText = res.text;
            Log.i("XiaozhiVision", "image_to_text raw response (uuid=" + uuid + ", status="
                    + status + "): " + responseText);

            if (status < 200 || status >= 300) {
                return XiaozhiVisionResult.fail("image_to_text returned HTTP " + status + ": "
                        + head200(responseText));
            }
            try {
                org.json.JSONObject json = new org.json.JSONObject(responseText);
                if (json.optBoolean("success", false)) {
                    String text = extractVisionText(json);
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
                        + head200(responseText));
            }
        } catch (java.io.IOException e) {
            return XiaozhiVisionResult.fail("image_to_text request failed: " + e.getMessage());
        } catch (org.json.JSONException e) {
            // 理論上 payloadJson.put("type",...)/put("uuid",...) 這兩個 put(String,
            // Object) overload 不會真的 throw (value 本身沒問題), 但它們簽名有
            // 宣告 throws JSONException, 純粹補上這個 catch 通過 javac 的 checked
            // exception 檢查, 不代表這裡預期會撞到。
            return XiaozhiVisionResult.fail("image_to_text failed building request JSON: " + e.getMessage());
        }
    }

    /** postVisionRequest() 回包：HTTP status＋全文 responseText。 */
    private static final class VisionHttpResult {
        final int status;
        final String text;
        VisionHttpResult(int status, String text) { this.status = status; this.text = text; }
    }

    /** vision/explain POST 共用骨架：開連接、Device-Id/Client-Id/Authorization
     *  headers、connect/read timeouts、fixed-length 寫 payload、讀 status＋全文。
     *  multipart explain 同 JSON image_to_text 之前逐字一樣（除 Content-Type，
     *  同 explain 版多了一個 os.flush()——close() 本身會 flush，行為一致）。
     *  logging 留在 caller（兩邊 log 字面不同）。 */
    private static VisionHttpResult postVisionRequest(String urlStr, String deviceId, String clientId,
            String accessToken, String contentType, byte[] payload) throws java.io.IOException {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            NetLog.out("xiaozhi-vision", urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
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
            java.io.InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? MainActivity.readFully(is) : "";
            return new VisionHttpResult(status, responseText);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** vision 回包 text 抽取："text" 拿不到就試 result.text／data.text 幾種常見巢狀。
     *  未經證實哪個對、純粹碰運氣，主要靠 caller 印 raw response 先真正確診。
     *  image_to_text 同 explain 共用（之前兩份逐字一樣）。 */
    private static String extractVisionText(org.json.JSONObject json) {
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
        return text;
    }

    /** vision error 回包頭 200 字（不將整個 response 塞落 error string）。 */
    private static String head200(String s) {
        return s.substring(0, Math.min(200, s.length()));
    }

    /** Multipart POST to the vision/explain endpoint - mirrors XiaozhiOtaClient's
     *  postJsonWithStatus() (same Device-Id/Client-Id header convention, same
     *  zero-third-party HttpURLConnection style), but a
     *  multipart body instead of JSON since this carries binary JPEG data - see
     *  esp32_camera.cc's Explain() for the request shape being matched: a "question"
     *  text field alongside a "file" field holding the JPEG.
     *
     *  multipart body 開頭多一個 "type" part (值 "multipart"，在 "question" part 之前)，
     *  同送 Client-Id header（同 WebSocket 一樣）。 */
    private XiaozhiVisionResult xiaozhiVisionExplainRequest(String urlStr, String deviceId,
            String clientId,
            String accessToken, byte[] jpeg, String question) throws java.io.IOException {
        // boundary 用固定字串 "----ESP32_CAMERA_BOUNDARY" (官方 esp32-camera.cc 同款)，
        // 不自己動態生成。
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

        VisionHttpResult res = postVisionRequest(urlStr, deviceId, clientId, accessToken,
                "multipart/form-data; boundary=" + boundary, payload);
        int status = res.status;
        String responseText = res.text;
        if (status == 404) {
            // 404 不是 URL 打錯，多數是這個帳戶/agent 在 xiaozhi.me
            // console 未開通 vision/camera MCP 服務。
            return XiaozhiVisionResult.fail("vision/explain returned HTTP 404 - this usually "
                    + "means the vision/camera MCP service has not been enabled for this "
                    + "device/agent in the xiaozhi.me console (look for \"MCP 接入點\" / "
                    + "\"MCP Services\" / vision settings there), not a URL problem.");
        }
        if (status < 200 || status >= 300) {
            return XiaozhiVisionResult.fail("vision/explain returned HTTP " + status + ": "
                    + head200(responseText));
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(responseText);
            // 印 raw response 方便對照 server JSON 結構。
            // 轉 Log.d＋截 300 字（image 描述加 token/uuid 可以好長）。
            android.util.Log.d("XiaozhiVision", "vision/explain raw response: "
                    + (responseText.length() > 300 ? responseText.substring(0, 300) + "…(" + responseText.length() + "B)" : responseText));
            if (json.optBoolean("success", false)) {
                String text = extractVisionText(json);
                    if (text.isEmpty()) {
                        // vision/explain 是異步：成功但無 text 時回 {"success":true,"uuid":"...",
                        // "message":"Please call the tool `image_to_text`..."}，真正描述要 LLM
                        // 再發 tools/call 問 "image_to_text" 先拿到。這裡將 server 的 "message"
                        // 原文傳回給 LLM，等它主動再問。
                    String uuid = json.optString("uuid", null);
                    String message = json.optString("message", null);
                    if (uuid != null && !uuid.isEmpty() && message != null && !message.isEmpty()) {
                        Log.i("XiaozhiVision", "vision/explain is async (uuid=" + ChestQuery.maskUuid(uuid)
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
                    + head200(responseText));
        }
    }

    /** Builds the MCP bridge XiaozhiClient uses to answer tools/list and tools/call.
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
                // 全部自動生成：tool 定義由 scripts/generate-mcp-tools.py
                // 讀 openapi/open-alpha2-openapi.yml + openapi/mcp-openapi-sync.yml
                // 生成 McpToolsGenerated.buildTools()，此處不再手寫 inputSchema。
                // callTool() 分發維持手寫（fuzzy match / 硬件直驅無法由 spec 推導）。
                org.json.JSONArray tools = McpToolsGenerated.buildTools();

                // Bug fix: "nextCursor":"" was always present, and the
                // xiaozhi.me server treats *presence* of nextCursor as "there is a next
                // page" regardless of its value being empty - it immediately re-issues
                // tools/list with that cursor, which this bridge answered identically
                // every time -> infinite tools/list loop, and the session never reaches tools/call, so no
                // action ever plays. The full tool set fits in a single page, so
                // nextCursor must be omitted entirely here to signal "no more pages".
                //
                // MCP 設定 card 的 enable/disable 在這裡一次性生效 -
                // tools 已由 McpToolsGenerated.buildTools() 起好，
                // 這裡過濾一次就夠, 不用逐個 tool 加 if, 減少改動、不用擔心漏了
                // 哪一個。總開關關閉就回傳完全空的 tools array (等於告訴 LLM「這台
                // 機器現在沒有任何工具」); 開啟就逐一取得個別 tool 的 enabled 狀態
                // 過濾。見 XiaozhiConfig isMcpToolEnabled()/getMcpDisabledToolNames() 的 comment。
                org.json.JSONArray filteredTools = new org.json.JSONArray();
                if (xiaozhiConfig.isMcpEnabled()) {
                    java.util.Set<String> disabledNames = xiaozhiConfig.getMcpDisabledToolNames();
                    for (int i = 0; i < tools.length(); i++) {
                        org.json.JSONObject tool = tools.getJSONObject(i);
                        if (!disabledNames.contains(tool.optString("name"))) {
                            filteredTools.put(tool);
                        }
                    }
                }
                // MCP 設定 card 要顯示全部 tool (含已經 disable 的
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
                // 單靠 listTools() 側過濾不夠 - LLM 可能還拿著上一次
                // (disable 之前) 取得的 tool 清單, 照樣試著呼叫一個現在已經 disabled
                // 的 tool name, 這裡多做一重防護。和 listTools() 用同一套
                // XiaozhiConfig isMcpEnabled()/getMcpDisabledToolNames() 邏輯, 保證兩邊判斷一致。
                if (!xiaozhiConfig.isMcpToolEnabled(name, xiaozhiConfig.getMcpDisabledToolNames())) {
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
                    // 純轉發 case（下面 19 個）只填 r，switch 後統一 unpack 成
                    // isError/resultText（之前每 case 3 行逐字一樣）；take_photo／
                    // image_to_text／speak／default 自行填，不經 r。
                    SonarCenter.McpResult r = null;
                    switch (name) {
                        case "self.robot.list_actions":
                            r = actionDirect.mcpListActions();
                            break;
                        case "self.robot.play_action":
                            r = actionDirect.mcpPlayAction(arguments);
                            break;
                        case "self.robot.stop_action":
                            r = actionDirect.mcpStopAction();
                            break;
                        case "self.robot.play_random_action":
                            r = actionDirect.mcpPlayRandomAction();
                            break;

                        // -- Hardware control: servo/LED/PIR/sonar -----------------------
                        // 薄包裝, 邏輯全部委託給 handleApi() 已有的 "servo/*"、
                        // "led/*"、"pir/*" case 使用的那些 Alpha2RobotApi 方法, 見
                        // AIDL_REFERENCE.md 相關章節和 handleApi() 的 comment 取得完整
                        // 已驗證行為/參數語意, 這裡不重複解釋。
                        case "self.robot.servo_set_one":
                            r = mcpServoSetOne(arguments);
                            break;
                        case "self.robot.servo_set_all":
                            r = mcpServoSetAll(arguments);
                            break;
                        case "self.robot.led_set_head":
                            r = ledCenter.mcpLedSetHead(arguments);
                            break;
                        case "self.robot.led_set_eye":
                            r = ledCenter.mcpLedSetEye(arguments);
                            break;
                        case "self.robot.led_set_mouth":
                            r = ledCenter.mcpLedSetMouth(arguments);
                            break;
                        // sensors 4 tool 本體在 SonarCenter；薄 delegate。
                        case "self.sensors.get_pir":
                            r = sonarCenter.mcpGetPir();
                            break;
                        case "self.sensors.set_pir_enabled":
                            r = sonarCenter.mcpSetPirEnabled(arguments);
                            break;
                        case "self.sensors.get_sonar":
                            r = sonarCenter.mcpGetSonar();
                            break;
                        case "self.sensors.set_sonar_threshold":
                            r = sonarCenter.mcpSetSonarThreshold(arguments);
                            break;

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
                            // 那段 comment。vision/explain 後端行為和用哪個 model 無關。
                            String uuid = arguments.optString("uuid", "");
                            // LLM 有時帶佔位符字面值 (如 "placeholder") 而不是真 uuid，
                            // 用寬鬆 UUID 格式檢查篩走，fallback 用 device 記低的 lastPendingPhotoUuid。
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
                                // image_to_text 格式未定，失敗時回自然說法不回技術 error，
                                // 原始 error 已有 log。
                                Log.w("XiaozhiVision", "image_to_text follow-up failed, "
                                        + "using fallback reply: " + imgResult.error);
                                resultText = "拍到照片了，不過現在還看不到照片裡面的內容，晚點可能才答得出來。";
                            } else {
                                resultText = imgResult.text;
                                lastPendingPhotoUuid = null; // 用完即清, 避免舊 uuid 混入新的
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
                            // same SpeechCenter.STOP_TO_TTS_MIN_GAP_MS race guard against a just-issued
                            // speech/stop, same mouth-LED bracket, same 3-arg
                            // speech_startTTS(lang, text, voice) signature (Alpha2RobotApi
                            // exposes no high-priority/interrupting TTS variant, so this
                            // shares the low-priority entry point the rest of the app uses).
                            // Fixed to Nuance/en_us rather than reading an "engine" query
                            // param (no query string here, this is an MCP tool call) -
                            // consistent with defaulting away from iFlytek's per-call voice
                            // picker, which has no equivalent argument in this tool's schema.
                            awaitTtsGap();
                            LedCenter.startMouthLedForTts();
                            UbxErrorCode.API_ERROR_CODE code = robot.speech_startTTS("en_us", text, null);
                            if (!MainActivity.isOk(code)) {
                                LedCenter.stopMouthLedForTts();
                            }
                            isError = !MainActivity.isOk(code);
                            resultText = String.valueOf(code);
                            break;
                        }

                        // -- Local music/FM radio: 薄包裝, 邏輯全部委托給 AudioCenter
                        // mcp*() (跟 audio/local_music/*、audio/radio/* 那幾個 HTTP
                        // endpoint 共用同一批底層 method), 不在這裡重複實現。
                        case "self.media.list_music":
                            r = audioCenter.mcpListMusic();
                            break;
                        case "self.media.play_music":
                            r = audioCenter.mcpPlayMusic(arguments);
                            break;
                        case "self.media.stop_music":
                            r = audioCenter.mcpStopMusic();
                            break;
                        case "self.media.search_radio":
                            r = audioCenter.mcpSearchRadio(arguments);
                            break;
                        case "self.media.play_radio":
                            r = audioCenter.mcpPlayRadio(arguments);
                            break;
                        case "self.media.stop_radio":
                            r = audioCenter.mcpStopRadio();
                            break;
                        default:
                            isError = true;
                            resultText = "unknown tool: " + name;
                            break;
                    }
                    if (r != null) {
                        isError = r.isError;
                        resultText = r.resultText;
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

}
