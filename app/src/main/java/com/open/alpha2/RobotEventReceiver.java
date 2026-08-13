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
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    // 2026-08: 反編譯 alpha2services_base 3.0.0.2 全個 APK, 搵唔到
                    // 任何 sendBroadcast("com.ubtechinc.key") 嘅出處 —— 呢個 action
                    // 喺呢個韌體版本已經被下面 "com.ubtechinc.services.header" 完全
                    // 取代 (HeadkeyManager, lynx 專用 package)。呢個 case 喺呢部機
                    // 上面實際上係死 code, 永遠唔會觸發, 淨係保留做向後相容。詳見
                    // AIDL_GUIDE_LYNX.md「未使用/未接收嘅 broadcast」一節。
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
                case "com.ubtechinc.services.Action.PIR_STATE": {
                    // 2026-08 新增, Lynx 專用: 反編譯 companion_v17_signed.apk 搵到嘅
                    // 官方 SDK (com.ubtechinc.alpha.sdk.AlphaRobotApi$RobotReceiver)
                    // 監聽緊呢個 action, 對應 PirStateListener.onState(boolean) - 呢個
                    // 先係機身真正、持續會 fire 嘅「PIR 偵測到人/冇人」通知, 唔經任何
                    // AIDL binder listener (同 ISysService.setPIRSensor() 嘅
                    // IRemotePIRSensorOperationResultListener.onPIRSensorOpResult() 完全
                    // 係兩件事 - 後者反編譯 alpha2services_base 3.0.0.2 確認咗機身側
                    // SysServiceImpl.setPIRSensor() 冇將個 listener 轉發落去, 永遠唔會
                    // fire, 見 LynxController.java 「sys/pir」個 case 嘅 comment)。
                    // Extra 名 "pirState" 抄自 v17 反編譯結果。
                    Object pirState = readAny(intent, "pirState");
                    boolean triggered = Boolean.TRUE.equals(pirState);
                    EventBus.get().publish("pir_state", "{\"triggered\":" + triggered + "}");
                    break;
                }
                case "com.ubtechinc.services.header": {
                    // 2026-08 新增, Lynx 專用: 反編譯 alpha2services_base 3.0.0.2
                    // 搵到, HeadkeyManager$2/$3 (com.ubtechinc.alpha.jni.headkey.lynx
                    // package) 喺機頭實體掣連按/長按 (音量加/減) 嗰陣發出。Extra
                    // "value" 係 int, 反編譯確認嘅實際數值: 4 = 連按 (音量 +1),
                    // 5 = 長按 (音量 x0.5, 見 SoundVolumesUtils.mulVolume) —— 呢兩個
                    // 數值同 "com.ubtechinc.key" 嗰個 (已死) Byte extra "key" 冇關係,
                    // 唔好混淆。詳見 AIDL_GUIDE_LYNX.md「未使用/未接收嘅 broadcast」一節。
                    Object value = readAny(intent, "value");
                    EventBus.get().publish("header_key", "{\"value\":" + jsonValue(value) + "}");
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
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");
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
                case "com.ubtechinc.services.stoptts": {
                    // 2026-08 新增, Lynx 專用: 反編譯確認, HeadkeyManager.
                    // backFormKeyOnDown() 發出, 冇 extra —— 按機頭實體掣其中一粒掣
                    // 順帶觸發嘅 stop-TTS 信號, 獨立於你自己 call 嘅 speech/stop API,
                    // 純粹通知你 TTS 已經俾機身自己停咗, UI 應該同步返個播放狀態。
                    EventBus.get().publish("stop_tts", "{}");
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
