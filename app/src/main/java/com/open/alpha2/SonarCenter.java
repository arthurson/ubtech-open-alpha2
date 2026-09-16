package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.Map;

/**
 * Sonar＋PIR sensors 包：threshold state、讀數 cache、事件入口、紫燈指示、
 * servo/sonar 端點、MCP sensors 4 tool 本體。
 */
public final class SonarCenter {

    private final Context appContext;
    private final LedCenter ledCenter;
    // PIR 事件推送經 isConnected＋sendDetectText——經 setUplink() 後補
    // （volatile 保證 onCreate thread 寫入對 broadcast／HTTP
    // thread 可見）。未補前 null：state 照存，push 跳過。
    private volatile XiaozhiBridge uplink;

    public SonarCenter(Context context, LedCenter ledCenter) {
        this.appContext = context.getApplicationContext();
        this.ledCenter = ledCenter;
    }

    /** onCreate 接線用：補 PIR 推送 uplink。 */
    public void setUplink(XiaozhiBridge uplink) {
        this.uplink = uplink;
    }

    // directChestReady() 薄 delegate（實現見 DirectProbes）。
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    // Chest sonar trigger threshold in cm, as last set via servo/sonar. Assumption
    // (unverified on real hardware): chest_configureSonar()'s distance byte IS the
    // threshold in cm directly (0-100 fits a single byte with room to spare) - kept
    // here purely so the obstacle-triggered purple-LED logic below knows what
    // threshold is currently active, and so the front-end chart can draw it as a
    // reference line against live sonar readings.
    private volatile int sonarThresholdCm = 30;
    private volatile boolean sonarLedActive = false;
    // onSonarDistanceReceived() 之前只是用來判斷 triggered 有沒有改變
    // (驅動 LED), 沒有存下實際讀數本身 - XiaoZhi MCP tool (self.sensors.get_sonar)
    // 要給 LLM 隨時查詢「現在距離多少」, 不只「有沒有觸發」, 所以這裡加一個 cache
    // 著最新讀數的 field。-1 代表「未收過任何讀數」, 和真實距離 (恆為非負) 區分開,
    // 給 MCP tool 可以告訴 LLM 這是「未有數據」而不是「距離 0cm」。
    private volatile int lastSonarDistanceCm = -1;
    // 和 lastSonarDistanceCm 同一個目的 - PIR 事件之前只是即時
    // publish 去 EventBus (見 RobotEventReceiver 的 "com.ubtechinc.key"/-109 case),
    // 沒存下最新狀態給 MCP tool 隨時查詢。-1 = 未收過任何 PIR 事件, 0 = 上次收到
    // 的是 EXIT (沒人), 1 = 上次收到的是 ENTER (有人) - 用 int 不用 boolean 來
    // 保留「未有數據」這個第三種狀態, 和 lastSonarDistanceCm 用 -1 的原因一樣。
    private volatile int lastPirTriggeredState = -1;

    /** XiaozhiBridge.HostState／static 縫轉交用：讀 threshold。 */
    public int getSonarThreshold() { return sonarThresholdCm; }

    /** XiaozhiBridge.HostState／servo/sonar 轉交用：寫 threshold（兼清 stale LED state）。 */
    public void applySonarThreshold(int distanceCm) {
        sonarThresholdCm = distanceCm;
        sonarLedActive = false; // threshold changed - next frame decides fresh, don't carry over stale LED state
    }

    /** XiaozhiBridge.HostState 轉交用：讀最新 sonar 距離（-1＝未收過讀數）。 */
    public int getSonarDistanceCm() { return lastSonarDistanceCm; }

    /** XiaozhiBridge.HostState 轉交用：讀最新 PIR 狀態（-1＝未收過事件）。 */
    public int getPirTriggeredState() { return lastPirTriggeredState; }

    /** RobotEventReceiver 收到 SONAR_DISTANCE_ACTION 之後的入口, 負責將
     *  distanceCm/triggered 接到 applyObstacleIndicator() (5-mic + mouth LED
     *  雙路徑, 見該方法 javadoc)。和 handleChestObstacleFrame() 一樣, 只在
     *  triggered 狀態實際改變那一刻才重新驅動 LED, 避免每秒 ~1 幀的重複讀數不斷
     *  重送同一個 LED command。 */
    public void onSonarDistanceReceived(int distanceCm, boolean triggered) {
        lastSonarDistanceCm = distanceCm;
        if (triggered == sonarLedActive) {
            return;
        }
        sonarLedActive = triggered;
        ledCenter.applyObstacleIndicator(triggered);
    }

    /** RobotEventReceiver 的 "alpha2_pir_state" publish 之後順手 call 這個, 讓
     *  self.sensors.get_pir MCP tool 可以讀到最新狀態, 不用自己另外訂閱
     *  EventBus。
     *
     *  ⚠️ 這個方法是在 RobotEventReceiver (一個 BroadcastReceiver) 的
     *  onReceive() 裡面直接被 call, 也就是說這個方法本身、和它叫的任何東西, 都
     *  **一定不可以有阻塞式操作** (Thread.sleep、網路 IO、等等) - BroadcastReceiver.
     *  onReceive() 有嚴格時限 (通常十秒內要返回), 密集的 PIR broadcast 一波接一波
     *  的時候, 阻塞邏輯會連環卡住, 輕則觸發 ANR, 重則直接 hold 死整個 system 連 adb 都沒反應。所以這裡只做
     *  最輕的 field 寫入, 任何要送 WebSocket 訊息的耗時邏輯都必須包多一層獨立
     *  thread 才可以做 (見下面 new Thread(...).start())。 */
    public void onPirStateReceived(final boolean triggered) {
        int newState = triggered ? 1 : 0;
        if (newState == lastPirTriggeredState) {
            return; // 狀態沒變, 不重複推播 (和 sonar 的 dedup pattern 一致)
        }
        lastPirTriggeredState = newState;
        // 用戶要求「不是叫一次做一次, 而是只要 PIR 開了, 每次
        // broadcast 回報有不同都要有反應」- 也就是要事件驅動、主動告訴小智知道,
        // 不是只給 LLM 隨時查詢。這段一定要包在獨立 thread 裡才可以做
        // (xiaozhiSendDetectTextSafely() 裡面有 Thread.sleep + 阻塞式 WebSocket
        // send), 保持 onReceive() 本身
        // 立刻返回, 不會阻住這個 broadcast dispatch。
        new Thread(new Runnable() {
            @Override
            public void run() {
                final XiaozhiBridge up = uplink; // volatile 一次讀快照，未 setUplink 跳過
                if (up == null || !up.isConnected()) {
                    return;
                }
                String text = triggered
                        ? "[系統事件] PIR 人體感應器偵測到有人在附近。"
                        : "[系統事件] PIR 人體感應器偵測不到人在附近了。";
                String err = up.sendDetectText(text);
                if (err != null) {
                    android.util.Log.w("XiaozhiPir", "failed to push PIR event to XiaoZhi: " + err);
                }
            }
        }, "XiaozhiPirEventPush").start();
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
     *  結論：呢台機 sonar 讀數經獨立 broadcast "com.ubtechinc.sonar.distance"
     *  (RobotWire.SONAR_DISTANCE_ACTION，extra 係 parse 好嘅 int "sonar_distance")，
     *  唔經呢條 AIDL rcv 路。呢個方法留給其他機身/firmware 版本；
     *  呢台機 cmd 恆等於 4 會提早 return，唔影響真正生效嗰條路。 */
    public void handleChestObstacleFrame(byte[] bytes, int len) {
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

    // -- /api/alpha2/servo/sonar (ApiDispatcher 轉調；threshold state 經上面讀寫) --
    public HttpServer.ApiResponse servoSonarResponse(Map<String, String> query) {
        int distanceCm = ApiValidator.requireIntRange(query, "distance", 0, 100);
        applySonarThreshold(distanceCm);
        boolean sent = HardwareDirectManager.get(appContext).chest().configureSonar(distanceCm);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), directChestReady());
    }

    // -- MCP tools (XiaozhiBridge callTool switch 轉調) --

    /** MCP tool 共用回包：對應 XiaozhiBridge callTool switch 嗰兩個 local
     *  (isError/resultText)，經下面 mcp*() 帶返出去。 */
    public static final class McpResult {
        public final boolean isError;
        public final String resultText;
        McpResult(boolean isError, String resultText) {
            this.isError = isError;
            this.resultText = resultText;
        }
        static McpResult ok(String resultText) { return new McpResult(false, resultText); }
        static McpResult err(String resultText) { return new McpResult(true, resultText); }
    }

    /** self.sensors.get_pir 本體 (XiaozhiBridge 轉調)。純讀，唔掂硬件。 */
    public McpResult mcpGetPir() {
        int state = getPirTriggeredState();
        String stateStr = state < 0 ? "unknown" : (state == 1 ? "triggered" : "clear");
        return McpResult.ok("{\"state\":\"" + stateStr + "\"}");
    }

    /** self.sensors.set_pir_enabled 本體 (XiaozhiBridge 轉調)。
     *  pure-direct: 经 /dev/ttyS1 直发 cmd 72。 */
    public McpResult mcpSetPirEnabled(org.json.JSONObject arguments) {
        if (!arguments.has("enabled")) {
            return McpResult.err("enabled is required");
        }
        boolean enabled = arguments.optBoolean("enabled");
        // pure-direct: 经 /dev/ttyS1 直发 cmd 72。
        boolean sent = HardwareDirectManager.get(appContext).chest().setPirEnabled(enabled);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        boolean ready = directChestReady();
        return new McpResult(!MainActivity.isOk(code) || !ready,
                String.valueOf(code) + " (chestReady=" + ready + ")");
    }

    /** self.sensors.get_sonar 本體 (XiaozhiBridge 轉調)。純讀，唔掂硬件。 */
    public McpResult mcpGetSonar() {
        return McpResult.ok("{\"distance_cm\":" + getSonarDistanceCm()
                + ",\"threshold_cm\":" + getSonarThreshold() + "}");
    }

    /** self.sensors.set_sonar_threshold 本體 (XiaozhiBridge 轉調)。
     *  pure-direct: 经 /dev/ttyS1 直发 cmd 4。 */
    public McpResult mcpSetSonarThreshold(org.json.JSONObject arguments) {
        if (!arguments.has("distance_cm")) {
            return McpResult.err("distance_cm is required");
        }
        int distanceCm = arguments.optInt("distance_cm");
        // 同 servo/sonar HTTP 一套（0-100），唔啱即報錯。
        if (distanceCm < 0 || distanceCm > 100) {
            return McpResult.err("distance_cm must be between 0 and 100, got: " + distanceCm);
        }
        applySonarThreshold(distanceCm);
        // pure-direct: 经 /dev/ttyS1 直发 cmd 4。
        boolean sent = HardwareDirectManager.get(appContext).chest().configureSonar(distanceCm);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        boolean ready = directChestReady();
        return new McpResult(!MainActivity.isOk(code) || !ready,
                String.valueOf(code) + " (chestReady=" + ready + ")");
    }
}
