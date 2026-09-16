package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

import java.util.Map;

/**
 * Sonar＋PIR sensors 包：threshold state、讀數 cache、事件入口、紫燈指示、
 * servo/sonar 端點、MCP sensors 4 tool 本體。
 *
 * 2026-09 由 MainActivity＋ApiDispatcher 抽出 (Sonar 第一刀)：sonarThresholdCm／
 * sonarLedActive／lastSonarDistanceCm／lastPirTriggeredState、onPirStateReceived()、
 * onSonarDistanceReceived()、handleChestObstacleFrame()、servo/sonar case，
 * 邏輯一字不改搬過嚟（機械改寫只限：codeResponseReady/directCode 加 MainActivity.
 * 前綴、directChestReady 內聯經 appContext——同 UbxApi/LedCenter/ChestQuery／
 * ApiDispatcher 一樣做法）。
 * 2026-09 MCP 收斂 (Sonar 第二刀)：XiaozhiBridge callTool switch 嗰 4 個
 * self.sensors.* case 本體搬入下面 mcp*()（isError＋resultText 經 McpResult
 * 帶返出去，validation 先行、硬件直發 verbatim）。
 * 擁有關係：
 * - MainActivity 只留：static 縫（getSonarThresholdCm／onSonarDistanceReceived／
 *   onPirStateReceived 轉交呢度，簽名不變）、implements XiaozhiBridge.HostState
 *   其中 sonar 四法轉交呢度、接線（onCreate 建構＋setUplink、ApiDispatcher 直調）。
 * - ApiDispatcher servo/sonar case 經下面 servoSonarResponse() 直調（ctor 尾加
 *   param，慣例）。
 * - XiaozhiBridge MCP 4 tool 經下面 mcp*() 直調（ctor 尾加 param，慣例）。
 * - RobotEventReceiver 零改動（繼續叫 MainActivity static 縫）。
 *
 * 縫設計（同 TTS core 第一刀一樣題目）：
 * - RobotEventReceiver 無 ctor／field 拎 outer instance（registerDynamicReceiver
 *   嗰次 new 唔郁），onDirectChestFrame 區間凍結（有人同時改緊 serial frame 解析，
 *   05 00 頭＋壞舵機短幀——嗰區一隻字唔郁，call site 行緊嘅 static 簽名不變）。
 *   所以 MainActivity 繼續做 static＋HostState 提供者，全部轉交呢度
 *   （null-guard：onCreate 同一 thread 先後建構；destroy 窗口預設 30／no-op 安全）。
 * - 斷 cycle（MCP 收斂）：呢度建構只收 (Context, LedCenter)，起喺 xiaozhiBridge
 *   之前；PIR 推送要嘅 XiaozhiBridge 經 setUplink() 後補（同一個 onCreate
 *   thread；未補前嘅 PIR edge 照存 state、push 跳過——窗口得幾行，比以前
 *   registerDynamicReceiver→sonarCenter 更窄）。XiaozhiBridge 反過來經 ctor
 *   拎呢度（final，唔使 guard）——類層面互指（Java 合法），施工順序經 setter
 *   解開，運行時無循環初始化問題。
 *
 * 已驗／跟進：
 * - PIR 真觸發 2026-09-07 機側驗過：pir/set?on=true 開 sensor 後，胸前揮手
 *   收到交替 93 01／93 00 真幀（checksum 啱），pir -1→0 行咗成條 delegate 鏈；
 *   未開 sensor 就係靜默（reinstall 後預設閂）。小智 push 未驗（要上線）。
 *   離線可驗：system/discover sensors 三值、servo/sonar 缺參 400。
 * - MCP 4 tool 本體已收斂入下面 mcp*()；E2E 要上線先驗到（callTool 經小智
 *   websocket 觸發，無 HTTP 直調路；寫路徑唔打真值）。
 * - direct/sonar/config（DirectApi）直發 configureSonar 唔經 applySonarThreshold，
 *   threshold cache 會滯後——舊行為照搬，唔喺呢刀改（改即係行為變更）。
 */
public final class SonarCenter {

    private final Context appContext;
    private final LedCenter ledCenter;
    // PIR 事件推送經 isConnected＋sendDetectText——經 setUplink() 後補（斷 cycle
    // 用，見上面縫設計；volatile 保證 onCreate thread 寫入對 broadcast／HTTP
    // thread 可見）。未補前 null：state 照存，push 跳過。
    private volatile XiaozhiBridge uplink;

    public SonarCenter(Context context, LedCenter ledCenter) {
        this.appContext = context.getApplicationContext();
        this.ledCenter = ledCenter;
    }

    /** onCreate 接線用：補 PIR 推送 uplink（必喺 xiaozhiBridge 建構之後、同一個
     *  thread；httpServer 起之前一定到，request 入唔到嚟先嘅窗口）。 */
    public void setUplink(XiaozhiBridge uplink) {
        this.uplink = uplink;
    }

    // directChestReady() 內聯：經 appContext 唔使 Activity（同 ApiDispatcher
    // 一樣做法；原 MainActivity 私有版 2026-09 刪，零調用）。
    private boolean directChestReady() {
        try { return HardwareDirectManager.get(appContext).chest().isAvailable(); }
        catch (Exception e) { return false; }
    }

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
     *  的時候, 阻塞邏輯會連環卡住, 輕則觸發 ANR, 重則 (2026-08 一次粗心的版本
     *  真機實測證實) 直接 hold 死整個 system 連 adb 都沒反應。所以這裡只做
     *  最輕的 field 寫入, 任何要送 WebSocket 訊息的耗時邏輯都必須包多一層獨立
     *  thread 才可以做 (見下面 new Thread(...).start())。 */
    public void onPirStateReceived(final boolean triggered) {
        int newState = triggered ? 1 : 0;
        if (newState == lastPirTriggeredState) {
            return; // 狀態沒變, 不重複推播 (和 sonar 的 dedup pattern 一致)
        }
        lastPirTriggeredState = newState;
        // 2026-08 新增: 用戶要求「不是叫一次做一次, 而是只要 PIR 開了, 每次
        // broadcast 回報有不同都要有反應」- 也就是要事件驅動、主動告訴小智知道,
        // 不是只給 LLM 隨時查詢。這段一定要包在獨立 thread 裡才可以做
        // (xiaozhiSendDetectTextSafely() 裡面有 Thread.sleep + 阻塞式 WebSocket
        // send, 原因見上面 class javadoc 段的慘痛教訓), 保持 onReceive() 本身
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
     *  2026-08 更新: 實機 (firmware 1.1.1.14) 證實這個 0x81 幀假設完全沒撞中 -
     *  sonar 讀數根本不會經 IAlpha2SerialPortService 的 AIDL rcv callback 送達,
     *  onListenSerialPortRcvData() 只收到 app 自己送出 chest_configureSonar()
     *  那個 config command 的 2-byte ack "04 00"。中途一度誤以為 sonar 讀數會
     *  經由 "com.ubtechinc.services.chest" (RobotWire.CHEST_ACTION) 這個全域
     *  broadcast 重新發送, 但反編譯官方 UBTech alpha2demo.apk 之後證實這也
     *  是錯的 - CHEST_ACTION 官方 demo 自己也只是用來 log 機身內部 raw command
     *  byte (見 RobotEventReceiver 的 CHEST_ACTION case), 不是 sonar 讀數。
     *  真正的 sonar 讀數是經由另一個獨立、之前完全沒診斷到的 broadcast action
     *  "com.ubtechinc.sonar.distance" (RobotWire.SONAR_DISTANCE_ACTION) 送出,
     *  extra 已經是 parse 好的 int (key "sonar_distance",
     *  RobotWire.SONAR_DISTANCE_EXTRA), 不需要自己再解 raw wire frame - 見
     *  RobotEventReceiver 的 SONAR_DISTANCE_ACTION case 和
     *  SonarCenter#onSonarDistanceReceived()。而且就算 0x81 幀真的經由 AIDL
     *  path 送達, 實測 raw wire frame 也是 "f8 8f 0a 00 00 8b eb 04 81 05 ed" -
     *  0x81 出現在幀中間 (index 8), 不是 bytes[0], 所以這裡原本的
     *  offset 假設連框架格式都對不上, 不只是「這台機器不走這條路」那麼簡單。
     *  這個方法連同它的 0x81 假設保留不刪 - 留給其他機身/firmware 版本,
     *  如果真的會送出 0x81 開頭的 AIDL rcv 幀, 這條路徑才有意義；在這台機器上它
     *  單純不會撞到 (cmd 恆等於 4, 在 "cmd != -127" 那行提早 return), 不影響
     *  真正生效的那條 SONAR_DISTANCE_ACTION 路徑。 */
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

    // -- MCP tools (XiaozhiBridge callTool switch 轉調；2026-09 MCP 收斂，
    // case 本體逐字搬入，isError＋resultText 經下面 McpResult 帶返出去) --

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
        // 2026-09-09：同 servo/sonar HTTP 一套（0-100），唔啱即報錯。
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
