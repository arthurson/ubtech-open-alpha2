package com.open.lynx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Receives every documented機身 sensor/event broadcast 並轉發做 JSON-ish 一行俾
 * shared {@link EventBus}, 畀 WebSocket log 同任何 local listener 消費。
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
                case RobotWireConstants.ALPHA_QR_CODE: {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case RobotWireConstants.ALPHA_WIFI_RESULT: {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case RobotWireConstants.ALPHA_BT_CONNECTION: {
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
                case RobotWireConstants.CHEST_ACTION: {
                    // 2026-08 更新: 呢個 App 之前一度誤以為呢個全域 broadcast 帶住
                    // sonar (超聲波避障) 讀數 - 反編譯官方 UBTech alpha2demo.apk
                    // (firmware 1.1.1.14) 之後證實呢個假設錯咗 (demo 自己嗰個
                    // receiver 淨係將 extra "value" 包做 packet 之後 log 做 debug,
                    // 完全冇用嚟顯示 sonar 距離), 而且 Lynx 呢部機根本冇心口超聲波
                    // 感應硬件, 相關 sonar_obstacle 事件/LED 邏輯已經喺 2026-08
                    // 死 code 清理移除。淨低嘅 chest_broadcast_debug 純粹保留做
                    // debug (可以睇到機身內部 raw command byte 嘅時序), 心口 mute
                    // 鍵掃描（下面）仍然生效。
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
                    // 其他包裝) - 一開始用 bytes[0] 判斷單一 byte 位置嘅做法睇落唔夠
                    // 穩陣。
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
                    }
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
