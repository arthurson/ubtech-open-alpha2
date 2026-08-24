package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.ubtechinc.constant.StaticValue;

/**
 * Receives every documented Alpha2 sensor/event broadcast (see docs/sensors-and-events.md
 * and docs/capabilities.md in the Alpha2OpenSdk repo) and forwards a JSON-ish line to the
 * shared {@link EventBus}, which both the WebSocket log and any local listeners consume.
 *
 * Registered dynamically from MainActivity.onCreate(). (It used to ALSO be declared as a
 * static &lt;receiver&gt; in AndroidManifest.xml "in addition to" this - that duplicate
 * registration meant every broadcast fired both instances and every event was published
 * to {@link EventBus} twice, showing up twice in the Event Log. Removed; see the
 * manifest's comment at the same spot.)
 *
 * IMPORTANT lesson from a real device: docs/capabilities.md documents
 * "getstureDirection" as a String extra, but on real hardware it arrives as an Integer,
 * and Intent.getStringExtra() throws ClassCastException on a type mismatch rather than
 * returning null. That exception was silently swallowing the whole gesture event. Every
 * extra read below now goes through {@link #readAny}, which tries the extra as every
 * primitive Bundle type Android supports and never throws - so a future doc/reality
 * mismatch degrades to an unlabeled raw value instead of dropping the event.
 */
public class RobotEventReceiver extends BroadcastReceiver {
    private static final String TAG = "RobotEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        // 2026-08 新增 (診斷用): 見 CHEST_ACTION case 入面嗰段解釋 PIR 事件唔穩定
        // 觸發嘅 comment - 呢度加一句總入口 log, 記低呢個 receiver 實際收到咗
        // 邊個 action, 等下次可以完全掌握呢個 receiver 嘅 onReceive() 有冇被
        // Android 系統真正 call 到、頻率係點, 唔使淨係靠個別 case 入面嘅 debug
        // event 間接推斷。
        Log.i(TAG, "onReceive action=" + action);
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    // 2026-08: 反編譯 alpha2services_base 3.0.0.2 全個 APK, 搵唔到
                    // 任何 sendBroadcast("com.ubtechinc.key") 嘅出處 —— 呢個 action
                    // 喺呢個韌體版本實際上係死 code, 永遠唔會觸發, 淨係保留做向後
                    // 相容 (以防其他韌體/舊機用返呢個 action)。
                    // Extra "key" is a Byte, not an int - see gotchas-and-naming.md.
                    Object key = readAny(intent, "key");
                    EventBus.get().publish("head_key", "{\"keyId\":" + jsonValue(key) + "}");
                    break;
                }
                case "com.ubtechinc.services.SPEECH_DIRECTION": {
                    Object angle = readAny(intent, "absoluteAngle");
                    int unsigned = toUnsignedByteInt(angle);
                    EventBus.get().publish("speech_direction", "{\"absoluteAngle\":" + unsigned + "}");
                    break;
                }
                case "com.ubtechinc.robot.tts_hint_wakeup": {
                    Object hint = readAny(intent, "hint_event");
                    EventBus.get().publish("wakeup", "{\"hintEvent\":" + jsonValue(hint) + "}");
                    break;
                }
                case "come.ubt.alpha2.gesture": {
                    // Documented as String; observed as Integer on real hardware - see
                    // class javadoc. readAny() handles either without throwing.
                    Object direction = readAny(intent, "getstureDirection");
                    EventBus.get().publish("gesture", "{\"direction\":" + jsonValue(direction) + "}");
                    break;
                }
                case "com.ubtechinc.robot_uuid.info": {
                    Object uuid = readAny(intent, "robot_uuid");
                    EventBus.get().publish("robot_uuid", "{\"uuid\":" + jsonValue(uuid) + "}");
                    break;
                }
                case StaticValue.ALPHA_QR_CODE: {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case StaticValue.ALPHA_WIFI_RESULT: {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case StaticValue.ALPHA_BT_CONNECTION: {
                    Object btFlag = readAny(intent, "BT_FLAG");
                    EventBus.get().publish("bt_connection", "{\"btFlag\":" + jsonValue(btFlag) + "}");
                    break;
                }
                case "com.ubtechinc.services.Action.ACTION_STOP": {
                    // 2026-08 新增: 反編譯確認, AlphaUtils.sendActionStopIntent() 發出,
                    // 冇 extra。代表機身側動作播放被外部打斷停止 —— 同
                    // IActionResultListener.onStopActionResult() 唔同, 呢個係全域廣播,
                    // 唔限於你自己 call 緊嗰個 playAction() session。
                    EventBus.get().publish("action_stop", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.ROBOT_INTERRUPTED": {
                    // 2026-08 新增: 反編譯確認, AlphaUtils.sendInterruptIntent() 發出,
                    // 冇 extra。代表機械人整體被打斷 (通常同 TTS/action 一齊停)。
                    EventBus.get().publish("robot_interrupted", "{}");
                    break;
                }
                case StaticValue.CHEST_ACTION: {
                    // 2026-08 更新: 之前假設 sonar 讀數經呢個全域 broadcast 送 -
                    // 反編譯官方 UBTech alpha2demo.apk (firmware 1.1.1.14) 之後證實
                    // 呢個假設錯咗。Demo 自己個 receiver (ActionMainActivity$6)
                    // 對呢個 action 淨係將 extra "value" (byte[]) 包做
                    // Alpha2ProtocolPacket 之後 Log.d 個 getmCmd() 做 debug, 完全冇
                    // 用嚟顯示 sonar 距離。真正嘅 sonar 事件係下面獨立嘅
                    // StaticValue.SONAR_DISTANCE_ACTION case, 保留呢度純粹做輔助
                    // debug (可以睇到機身內部 raw command byte 嘅時序), 唔再指望
                    // 佢係 sonar 嘅來源。
                    //
                    // 2026-08 新增 (診斷用): 用戶反映 PIR 事件推播完全冇反應 - 對照
                    // logcat 先發現一個之前完全未察覺嘅盲點: 官方 AlphaMainSeviceImpl
                    // (pid 990) 自己嘅 log 顯示佢持續收到 "ches cmd = -109" (PIR)
                    // 好多次, 但我哋自己呢個 RobotEventReceiver 全程 log 顯示成日都
                    // 淨係收到過一次 CHEST_ACTION (仲要係 -115, 唔係 -109) - 即係話
                    // 我哋自己嘅 receiver 實際上冇穩定咁收到呢個 broadcast, 之前
                    // comment 講嘅「已喺真機確認會正常觸發」呢個結論可能只喺
                    // 某一次特定測試環境先啱, 唔係穩定行為。之前淨係靠
                    // chest_broadcast_debug 呢個 EventBus event 做 debug, 冇直接
                    // 印落 logcat, 令呢個問題一直冇被發現。呢度加一句直接嘅
                    // Log.i, 印低每一次呢個 receiver 真正收到 CHEST_ACTION 嘅完整
                    // raw value, 等下次可以直接對比「官方 AlphaMainSeviceImpl 收到
                    // 幾多次 -109」同「我哋自己個 receiver 實際收到幾多次、係咩
                    // cmd值」, 先可以確診係咪呢個 broadcast 本身有 gate/rate-limit
                    // 令唔係次次都轉發俾第三方 app。
                    Log.i(TAG, "CHEST_ACTION received, raw value=" + bundleToJson(intent.getExtras()));
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");

                    // 2026-08 新增: 心口 mute 鍵測試 - 反編譯官方 alpha2services
                    // 3.0.0.2 APK (AlphaMainSeviceImpl$15.onReceive() 嘅
                    // sparse-switch) 確認, 心口 mute 鍵撳落去會經呢個同一個
                    // CHEST_ACTION broadcast 送出, extra "value" (byte[]) 入面
                    // 會有 -111 (0x91) 呢個 byte。呢部機兩份提供咗嘅 logcat 都
                    // 見過呢個值 (firmware 側 "ches cmd = -111", raw wire frame
                    // f8 8f 08 00 00 91 01 9a ed / f8 8f 08 00 00 91 00 99 ed),
                    // 已經確認會實際觸發, 唔似 chest_setPirSensorEnabled() 嗰個
                    // cmd=72 咁淨係反編譯推斷。
                    //
                    // 2026-08-14 更新: 之前用 ((byte[])rawValue)[0] == -111 (淨係
                    // 睇陣列第一個 byte) 喺真機測試完全冇反應。原因: logcat 冇印
                    // 低 bundleToJson(intent.getExtras()) 嘅實際內容, 冇辦法 100%
                    // 確認 Android SDK 傳落嚟嘅 "value" extra 陣列, 個 -111 (0x91)
                    // 呢個 byte 究竟排喺陣列邊個 index (SDK 可能有剝走/唔剝走
                    // firmware wire frame 嘅 f8 8f 08 00 00 呢段 header, 或者仲有
                    // 其他包裝) - 對比 handleChestObstacleFrame() 用 bytes[0] 判斷
                    // sonar (-127) 個做法, 兩個 callback 未必用緊同一種 trim 方式。
                    // 為避免再靠估 index 錯一次, 呢度改為掃描成個陣列, 唔理位置,
                    // 只要陣列入面出現過 -111 就當撳咗。已核對呢部機兩份 logcat
                    // 見過嘅全部 raw wire frame (cmd -115/-111/-109/-128 對應嘅
                    // checksum byte 分別係 0x97/0x9a,0x99/0x9c/0x8c), 冇一個同
                    // 0x91 撞值, 所以掃描全陣列喺呢啲已知樣本入面唔會誤觸發。
                    Object rawValue = readAny(intent, "value");
                    if (rawValue instanceof byte[]) {
                        byte[] arr = (byte[]) rawValue;
                        for (byte b : arr) {
                            if (b == (byte) -111) {
                                EventBus.get().publish("chest_mute_key", "{}");
                                break;
                            }
                        }

                        // 2026-08-14 新增: PIR sensor raw 觸發事件 (cmd=-109 / 0x93,
                        // 反編譯 alpha2services 1.2.10.5 APK 嘅 AlphaMainSeviceImpl$15.
                        // onReceive() sparse-switch 確認, log 字串 "PIR HUMON DETECT
                        // (1: ENTER)  (0: EXIT)")。
                        //
                        // 呢部機 (1.1.7.3) 同 1.2.10.5 用緊完全同一塊 chest 主板/MCU
                        // (Arthur 已用 Lynx 3.0.0.2 確認硬件正常, logcat 見過 cmd=-109
                        // raw byte 出現) - 差別純粹喺 1.1.7.3 呢個 Android apk 版本嘅
                        // AlphaMainSeviceImpl$14.onReceive() 對 -109/-111/-108 呢幾個
                        // case 完全冇支援 (反編譯 1.1.7.3 apk 嘅 sparse-switch-payload
                        // 逐個核對過, 呢三個值喺 1.1.7.3 唔存在, 只有喺 1.2.10.5 先新增),
                        // 所以 MCU 送出嚟嘅 raw event 喺官方 code 一律跌落 default 分支
                        // 净係 log 一句 "ches cmd = -109" 就完, 唔會轉發做
                        // com.ubtech.securityCamera.pirStatus broadcast (呢個轉發仲要
                        // 通過 SecurityCameraUtil.isMonitoringEnabled() gate, 而
                        // SecurityCameraUtil 呢個 class 喺 1.1.7.3 根本唔存在)。
                        //
                        // 心口 mute 鍵 (-111) 已經證實: 只要自己 app 直接讀呢個廣播嘅
                        // raw "value" byte[], 唔靠官方 code 識唔識個 case, 一樣讀到。
                        // 呢度用返同一個做法去讀 PIR。已喺真機確認會正常觸發
                        // (logcat_2026-08-15_12-06-19.txt 見到 chest cmd=-109
                        // 持續觸發)。
                        //
                        // Sub-value (ENTER=1/EXIT=0) 位置: 反編譯結果係
                        // Alpha2ProtocolPacket.e() (param array) 嘅 index 0, 即係
                        // cmd byte 之後嗰一個 byte。因為未證實呢部機嘅 SDK 傳落嚟嘅
                        // "value" 陣列, 係未拆解嘅完整 wire frame (例如
                        // f8 8f 08 00 00 93 01 9c ed) 定係已經拆走 header/checksum
                        // 淨係得 param (例如 {01}), 呢度做法係: 搵到 -109 呢個 byte
                        // 嘅 index, 如果佢唔係陣列最後一個, 就攞佢下一個 byte 做
                        // ENTER/EXIT 判斷; 如果佢啱啱好係最後一個 byte (即係已拆解
                        // 淨係得 cmd 冇 param 嘅情況), 就冇 sub-value 可攞, 只發
                        // "偵測到事件" 呢個訊號, triggered 保守咁當 true (收到呢個
                        // case 本身已經代表有事件發生)。
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] == (byte) -109) {
                                boolean pirTriggered = true;
                                if (i + 1 < arr.length) {
                                    pirTriggered = arr[i + 1] == 1;
                                }
                                EventBus.get().publish("alpha2_pir_state",
                                        "{\"triggered\":" + pirTriggered + "}");
                                MainActivity.onPirStateReceived(pirTriggered);
                                break;
                            }
                        }
                    }
                    break;
                }
                case "com.ubtech.securityCamera.pirStatus": {
                    // 2026-08 新增: ⚠️ 未經真機驗證 (呢個 gate 本身喺 1.1.7.3 一直
                    // 未 fire 過 - 見 CHEST_ACTION case 入面新加嗰段直接掃描 -109 嘅
                    // comment, 已經證實 SecurityCameraUtil 呢個 class 喺 1.1.7.3
                    // 根本唔存在, 呢個 broadcast 理論上唔會被送出)。保留呢個 case
                    // 純粹係萬一將來換咗支援呢個 gate 嘅 firmware 版本, 兩條路都
                    // 餵去同一個 "alpha2_pir_state" event, 前端唔使理背後行邊條路。
                    // 反編譯官方 alpha2services 3.0.0.2 APK 逆出嚟嘅 PIR 通知
                    // broadcast (AlphaMainSeviceImpl 喺 CHEST_ACTION 嘅 default 分支
                    // 揾到 PIR raw byte 之後, 如果 SecurityCameraUtil.
                    // isMonitoringEnabled() 開緊, 先會轉發呢個獨立 broadcast)。extra
                    // "pirStatus" 係 byte, 1=有人進入, 0=無人離開 - 同 Lynx 個
                    // "com.ubtechinc.services.Action.PIR_STATE" (extra "pirState",
                    // boolean) 係完全唔同嘅 action/extra 名, 唔好兩者混淆。
                    // 兩者混淆。用獨立嘅 "alpha2_pir_state" event 名 (唔用返 Lynx
                    // 嗰個 "pir_state"), 等前端可以分開兩個 backend 各自嘅 indicator,
                    // 唔會互相覆蓋。
                    Object pirStatus = readAny(intent, "pirStatus");
                    boolean triggered = toUnsignedByteInt(pirStatus) == 1;
                    EventBus.get().publish("alpha2_pir_state", "{\"triggered\":" + triggered + "}");
                    break;
                }
                case StaticValue.SONAR_DISTANCE_ACTION: {
                    // 2026-08 新增: 反編譯官方 UBTech alpha2demo.apk (firmware
                    // 1.1.1.14) 確認 - sonar 讀數真正經呢個獨立 broadcast 送出, extra
                    // 已經係 firmware parse 好嘅 int (SONAR_DISTANCE_EXTRA =
                    // "sonar_distance"), 唔使自己再解 raw wire frame。Demo 自己個
                    // UI (ActionMainActivity$7) 用 intent.getIntExtra(key, 0) 讀,
                    // 0 或負數當「冇讀數/超出範圍」顯示做 "INF" - 呢度跟返同一個假設。
                    // 呢個值究竟係咪已經係 cm 未經 100% 證實 (demo 只係直接印出嚟,
                    // 冇做任何換算), 但 enableSonar() 送出去嘅 config 第二個 param
                    // byte (40) 睇落好似係 cm 門檻, 兩者單位一致嘅可能性高。
                    int distanceCm = intent.getIntExtra(StaticValue.SONAR_DISTANCE_EXTRA, -1);
                    boolean triggered = distanceCm > 0 && distanceCm <= MainActivity.getSonarThresholdCm();
                    EventBus.get().publish("sonar_obstacle",
                            "{\"distanceCm\":" + distanceCm
                                    + ",\"thresholdCm\":" + MainActivity.getSonarThresholdCm()
                                    + ",\"triggered\":" + triggered + "}");
                    MainActivity.onSonarDistanceReceived(distanceCm, triggered);
                    break;
                }
                // 2026-08 新增 (8個, 見 MainActivity.registerDynamicReceiver() 對應
                // filter.addAction() comment): 用嚟查 speech_SetMIC()/setWakeState()
                // 攞返 mic 呢一刻機身有冇發任何 broadcast 通知呢個問題。反編譯搵到
                // 呢 8 個 action 都由 SpeechServiceImpl/SpeechManager
                // (com.ubtechinc.speechmanager.d/b package) 或者
                // AlphaMainSeviceImpl 發出, 個名/extras 睇落同 TTS、ASR、mic 相關
                // 事件有關, 但實際邊個會唔會喺 setWakeState() 嗰一刻觸發、payload
                // 實際裝住咩, 純粹反編譯 bytecode 睇唔出嚟 (bytecode 淨係睇到個
                // action 字串同 putExtra() 嘅 key 名/型別, 睇唔到幾時會行到嗰段
                // code) —— 所以呢度刻意唔即刻假設邊個 extra 代表 mic 狀態、唔即刻
                // 攞出嚟做獨立 UI event, 淨係用同一個 mic_broadcast_debug event
                // 將成個 intent (action + 全部 extras, 用 bundleToJson() 唔理型別
                // 全部原樣轉送) 送去 WebSocket log, 等收集到實機觸發嘅實際 payload
                // 之後, 先揀邊幾個真係同 mic ownership 有關、要拆做獨立 event。
                case "com.ubtechinc.services.ABOUT_TTS":
                case "com.ubtechinc.services.ALPHA_SOCKET_ASR_OK":
                case "com.ubtechinc.services.SPEECH_ANGLE_5MIC":
                case "com.ubtechinc.services.LED_ACTION":
                case "com.ubtechinc.services.IFLY_OFFLINE_CMD":
                case "com.ubtechinc.services.NUANCE_OFFLINE_CMD":
                case "com.ubtechinc.services.POWER_SAVE":
                case "com.ubtechinc.services.ALPHA_NOTIFY_POWER": {
                    EventBus.get().publish("mic_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");
                    break;
                }
                default:
                    Log.d(TAG, "Unhandled action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error for " + action, e);
        }
    }

    /**
     * Reads a Bundle extra without knowing its real type ahead of time. Tries the common
     * primitive wrapper types Android's Bundle supports for a single extra key, in an
     * order that costs nothing on a miss (Bundle.get() itself never throws - it's the
     * *typed* getters like getStringExtra() that throw ClassCastException on a mismatch).
     * Falls back to Bundle.get() (returns Object, works for any type) if a caller needs
     * something outside that set.
     */
    private static Object readAny(Intent intent, String key) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        return extras.get(key); // Bundle.get() is untyped and never throws ClassCastException.
    }

    private static int toUnsignedByteInt(Object value) {
        if (value instanceof Byte) {
            int v = (Byte) value;
            return v < 0 ? v + 256 : v;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    /** Renders any extra value as a JSON literal: quoted string, bare number/boolean, or null. */
    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte || value instanceof Double
                || value instanceof Float) {
            return String.valueOf(value);
        }
        // 2026-08-14 修正: byte[] (CHEST_ACTION 嘅 "value" extra 就係呢種) 冇喺
        // 上面覆蓋到, fallback 去底 String.valueOf(value) 會攞 Object.toString()
        // 嘅預設結果, 即係 "[B@<hashcode>" 呢種完全睇唔到內容嘅字串 - 呢個就係
        // 之前喺 Event Log 頁見到 "value":"[B@276adcef" 嘅原因, 個陣列內容一路
        // 冇印出過, 令到我哋一路靠估心口 mute 鍵 (-111) 究竟排喺陣列邊個 index。
        // 呢度改做印晒每個 byte 嘅 signed decimal 值 (同 logcat "ches cmd = -111"
        // 果種格式一致, 方便直接對比), 用逗號分隔包喺 [] 入面, 唔再係普通 JSON
        // 字串。
        if (value instanceof byte[]) {
            byte[] arr = (byte[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonValue(arr[i]));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + safe(String.valueOf(value)) + "\"";
    }

    /** Dumps every extra in a Bundle as a flat JSON object of stringified values, for
     *  broadcasts whose exact payload shape isn't pinned down upstream. */
    private static String bundleToJson(Bundle extras) {
        if (extras == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : extras.keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(safe(key)).append("\":").append(jsonValue(extras.get(key)));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
