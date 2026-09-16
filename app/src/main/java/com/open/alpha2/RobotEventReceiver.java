package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.ubtechinc.alpha.hardware.RobotWire;

/**
 * Receives every documented Alpha2 sensor/event broadcast (see docs/sensors-and-events.md
 * and docs/capabilities.md in the Alpha2OpenSdk repo) and forwards a JSON-ish line to the
 * shared {@link EventBus}, which both the WebSocket log and any local listeners consume.
 *
 * Registered dynamically from MainActivity.onCreate().
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
        // 總入口 log, 記下這個 receiver 實際收到了
        // 哪個 action。
        Log.i(TAG, "onReceive action=" + action);
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    // 這個 action 在這個韌體版本實際上是死 code, 永遠不會觸發, 只是保留做向後
                    // 相容 (以防其他韌體/舊機用回這個 action)。
                    // Extra "key" is a Byte, not an int - see gotchas-and-naming.md.
                    Object key = readAny(intent, "key");
                    EventBus.get().publish("head_key", "{\"keyId\":" + jsonValue(key) + "}");
                    break;
                }
                case "com.ubtechinc.robot.tts_hint_wakeup": {
                    Object hint = readAny(intent, "hint_event");
                    EventBus.get().publish("wakeup", "{\"hintEvent\":" + jsonValue(hint) + "}");
                    // 用戶開口那一刻立刻探測一次雲端連通性
                    // (見 MainActivity.triggerWakeupProbe() 的 comment)。
                    MainActivity.triggerWakeupProbe();
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
                    // 直接 String.valueOf(uuid) 有兩個 bug 導致
                    // 「無法讀取 uuid」:
                    // 1) uuid == null 時 String.valueOf() 回傳字串 "null", 經白名單
                    //    過濾後仍然是 "null", 前端會把 "null" 當成真 ID 顯示;
                    // 2) firmware 經 broadcast 送回來的 SN 很大機會是 byte[] (EEPROM
                    //    ASCII + padding), 而 String.valueOf(byte[]) 只會回傳
                    //    "[B@<hashcode>" 這種 Object.toString, 經白名單後變成一段
                    //    "Bxxxxxxx" 垃圾, 永遠不是真 SN。
                    // 修正: byte[] 用 US-ASCII 解碼 (和 MainActivity.parseRobotUuidFrame
                    // 同一套語義), null 則 fallback 掃描全部 extras 找 plausible 值,
                    // 找不到就 publish {"uuid":null} 讓前端顯示錯誤而不是卡在「查詢中」。
                    String s = decodeUuidExtra(uuid);
                    if ((s == null || s.isEmpty()) && intent.getExtras() != null) {
                        Bundle all = intent.getExtras();
                        for (String key : all.keySet()) {
                            if ("robot_uuid".equals(key)) continue;
                            String cand = decodeUuidExtra(all.get(key));
                            if (cand != null && !cand.isEmpty()) {
                                s = cand;
                                Log.i(TAG, "robot_uuid.info: fell back to extra \"" + key + "\"");
                                break;
                            }
                        }
                    }
                    if (s == null || s.isEmpty()) {
                        Log.w(TAG, "robot_uuid.info: empty uuid (extras="
                                + bundleToJson(intent.getExtras()) + ")");
                        EventBus.get().publish("robot_uuid", "{\"uuid\":null}");
                    } else {
                        EventBus.get().publish("robot_uuid", "{\"uuid\":" + jsonValue(s) + "}");
                    }
                    break;
                }
                case RobotWire.ALPHA_QR_CODE: {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case RobotWire.ALPHA_WIFI_RESULT: {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case RobotWire.ALPHA_BT_CONNECTION: {
                    Object btFlag = readAny(intent, "BT_FLAG");
                    EventBus.get().publish("bt_connection", "{\"btFlag\":" + jsonValue(btFlag) + "}");
                    break;
                }
                case "com.ubtechinc.services.Action.ACTION_STOP": {
                    // 機身側動作播放被外部打斷停止 —— 和
                    // IActionResultListener.onStopActionResult() 不同, 這個是全域廣播,
                    // 不限於自己 call 著那個 playAction() session。
                    EventBus.get().publish("action_stop", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.ROBOT_INTERRUPTED": {
                    // 代表機械人整體被打斷 (通常和 TTS/action 一起停)。
                    EventBus.get().publish("robot_interrupted", "{}");
                    break;
                }
                case RobotWire.CHEST_ACTION: {
                    // sonar 讀數唔經呢度，經下面獨立的
                    // RobotWire.SONAR_DISTANCE_ACTION case；呢度只做輔助
                    // debug (可以看到機身內部 raw command byte 的時序)。
                    //
                    // CHEST_ACTION broadcast 未必每次都轉發給第三方 app（實測收得唔齊），
                    // 這裡直接印每一次收到的完整 raw value，方便對比官方收到幾多次 -109。
                    Log.i(TAG, "CHEST_ACTION received, raw value=" + bundleToJson(intent.getExtras()));
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");

                    // 心口 mute 鍵經同一個 CHEST_ACTION broadcast 送出, extra "value" (byte[]) 裡面
                    // 會有 -111 (0x91)。wire frame f8 8f 08 00 00 91 01 9a ed /
                    // f8 8f 08 00 00 91 00 99 ed。
                    //
                    // 唔假設 -111 喺固定 index，掃描成個陣列；已知樣本 (cmd -115/-111/-109/-128
                    // checksum 分別 0x97/0x9a,0x99/0x9c/0x8c) 唔會同 0x91 撞值，唔會誤觸發。
                    Object rawValue = readAny(intent, "value");
                    if (rawValue instanceof byte[]) {
                        byte[] arr = (byte[]) rawValue;

                        // 心口 mute 鍵分「按下(01)/放開(00)」-
                        // raw wire frame 實測是 f8 8f 08 00 00 91 01 9a ed /
                        // f8 8f 08 00 00 91 00 99 ed, 和 PIR (-109) 一樣 cmd 後面
                        // 那個 byte 就是 sub-value。MainActivity 那邊用這個旗標做
                        // mute LED toggle (cmd=68 [01]=亮/[00]=熄, 真機掃描確認)。
                        // 找到 -111 的 index, 如果不是最後一個 byte 就取下一個 byte
                        // 做 pressed 判斷; 只有 cmd 沒 param 的話保守當 pressed。
                        for (int i = 0; i < arr.length; i++) {
                            if (arr[i] == (byte) -111) {
                                boolean mutePressed = true;
                                if (i + 1 < arr.length) {
                                    mutePressed = arr[i + 1] == 1;
                                }
                                EventBus.get().publish("chest_mute_key",
                                        "{\"pressed\":" + mutePressed + "}");
                                MainActivity.onMuteKeyEvent(mutePressed);
                                break;
                            }
                        }

                        // PIR sensor raw 觸發事件 (cmd=-109 / 0x93, "PIR HUMON DETECT
                        // (1: ENTER)  (0: EXIT)")。
                        //
                        // 1.1.7.3 官方 code 對 -109/-111/-108 無支援，只 log "ches cmd = -109"，
                        // 唔會轉發 pirStatus broadcast；自己 app 直接讀呢個廣播的
                        // raw "value" byte[] 照樣讀得到。
                        //
                        // Sub-value (ENTER=1/EXIT=0) 喺 cmd byte 之後嗰一個 byte。SDK 傳過嚟的
                        // "value" 陣列可能係完整 wire frame (例如
                        // f8 8f 08 00 00 93 01 9c ed) 或者已拆剩 param (例如 {01})，做法係: 找到 -109
                        // 的 index, 如果它不是陣列最後一個, 就取它下一個 byte 做
                        // ENTER/EXIT 判斷; 如果剛好係最後一個 byte, 就冇 sub-value 可取,
                        // triggered 保守當 true。
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
                    // 未經真機驗證 (1.1.7.3 理論上唔會送出)。保留呢個 case 係為咗
                    // 將來換咗支援嘅 firmware 版本，兩條路都
                    // 餵去同一個 "alpha2_pir_state" event, 前端不用理背後走哪條路。
                    // extra
                    // "pirStatus" 是 byte, 1=有人進入, 0=無人離開 - 和 Lynx 的
                    // "com.ubtechinc.services.Action.PIR_STATE" (extra "pirState",
                    // boolean) 是完全不同的 action/extra 名, 不要兩者混淆。用獨立
                    // 的 "alpha2_pir_state" event 名 (不用回 Lynx 那個 "pir_state"),
                    // 讓前端可以分開兩個 backend 各自的 indicator, 不會互相覆蓋。
                    Object pirStatus = readAny(intent, "pirStatus");
                    boolean triggered = toUnsignedByteInt(pirStatus) == 1;
                    EventBus.get().publish("alpha2_pir_state", "{\"triggered\":" + triggered + "}");
                    break;
                }
                case RobotWire.SONAR_DISTANCE_ACTION: {
                    // sonar 讀數經這個獨立 broadcast 送出, extra
                    // 已經是 firmware parse 好的 int (SONAR_DISTANCE_EXTRA =
                    // "sonar_distance"), 不用自己再解 raw wire frame。Demo 自己的
                    // UI (ActionMainActivity$7) 用 intent.getIntExtra(key, 0) 讀,
                    // 0 或負數當「沒讀數/超出範圍」顯示成 "INF" - 這裡跟回同一個假設。
                    // 這個值究竟是不是已經是 cm 未經 100% 證實 (demo 只是直接印出來,
                    // 沒做任何換算), 但 enableSonar() 送出去的 config 第二個 param
                    // byte (40) 看起來像是 cm 門檻, 兩者單位一致的可能性高。
                    int distanceCm = intent.getIntExtra(RobotWire.SONAR_DISTANCE_EXTRA, -1);
                    boolean triggered = distanceCm > 0 && distanceCm <= MainActivity.getSonarThresholdCm();
                    EventBus.get().publish("sonar_obstacle",
                            "{\"distanceCm\":" + distanceCm
                                    + ",\"thresholdCm\":" + MainActivity.getSonarThresholdCm()
                                    + ",\"triggered\":" + triggered + "}");
                    MainActivity.onSonarDistanceReceived(distanceCm, triggered);
                    break;
                }
                // 用來查 speech_SetMIC()/setWakeState()
                // 拿回 mic 這一刻機身有沒有發任何 broadcast 通知這個問題。
                // 呢 6 個 action 名字/extras 看起來和 TTS、ASR、mic 相關
                // 事件有關, 但實際哪個會不會在 setWakeState() 那一刻觸發、payload
                // 實際裝著什麼, 純粹反編譯 bytecode 看不出來 (bytecode 只能看到那個
                // action 字串和 putExtra() 的 key 名/型別, 看不到什麼時候會走到那段
                // code) —— 所以這裡刻意不立刻假設哪個 extra 代表 mic 狀態、不立刻
                // 拿出來做獨立 UI event, 只用同一個 mic_broadcast_debug event
                // 將整個 intent (action + 全部 extras, 用 bundleToJson() 不理型別
                // 全部原樣轉送) 送去 WebSocket log, 等收集到實機觸發的實際 payload
                // 之後, 才選哪幾個真的和 mic ownership 有關、要拆成獨立 event。
                case "com.ubtechinc.services.ABOUT_TTS":
                case "com.ubtechinc.services.ALPHA_SOCKET_ASR_OK":
                case "com.ubtechinc.services.SPEECH_ANGLE_5MIC":
                case "com.ubtechinc.services.LED_ACTION":
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

    /**
     * 把 robot_uuid extra 解成乾淨 SN 字串 (和
     * MainActivity.parseRobotUuidFrame 同一套語義: ASCII 解碼 -> 切掉第一個 \0
     * 之後的 padding -> 白名單只留英數/-/_)。byte[] 走 ASCII 解碼, String 走
     * 同一條清洗路徑, null/空回 null。其他型別 (Integer 等) 用 String.valueOf
     * 再清洗, 避免 "[B@hash" 類垃圾。
     */
    private static String decodeUuidExtra(Object value) {
        if (value == null) {
            return null;
        }
        String s;
        if (value instanceof byte[]) {
            byte[] arr = (byte[]) value;
            try {
                s = new String(arr, java.nio.charset.StandardCharsets.US_ASCII);
            } catch (Exception e) {
                return null;
            }
        } else {
            s = String.valueOf(value);
            if ("null".equals(s)) {
                return null;
            }
        }
        // byte[] 開頭可能有 flag 0x00 (見 MainActivity.parseRobotUuidFrame
        // 的真幀), 要先跳過開頭非法字元再切 \0, 否則 indexOf('\0')=0 會切出空字串。
        int start = 0;
        while (start < s.length()) {
            char c = s.charAt(start);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (ok) break;
            start++;
        }
        s = s.substring(start);
        int cut = s.indexOf('\0');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        s = s.replaceAll("[^A-Za-z0-9\\-_]", "").trim();
        if (s.isEmpty()) return null;
        // 斬尾 (EEPROM 非零殘留, 見 ChestQuery.truncateUuidTail)。
        // broadcast 路徑無 SharedPreferences 寫入長度可用, 傳 -1 行 pattern/大小寫規則。
        return ChestQuery.truncateUuidTail(s, -1);
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
        // byte[] (CHEST_ACTION 的 "value" extra 就是這種) 沒在
        // 上面覆蓋到, fallback 到 String.valueOf(value) 會取 Object.toString()
        // 的預設結果, 也就是 "[B@<hashcode>" 這種完全看不到內容的字串 - 這就是
        // 之前在 Event Log 頁見到 "value":"[B@276adcef" 的原因, 那個陣列內容一直
        // 沒印出過, 讓我們一直靠猜心口 mute 鍵 (-111) 究竟排在陣列哪個 index。
        // 這裡改成印出每個 byte 的 signed decimal 值 (和 logcat "ches cmd = -111"
        // 那種格式一致, 方便直接對比), 用逗號分隔包在 [] 裡面, 不再是普通 JSON
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
