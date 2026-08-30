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
        // 2026-08 新增 (診斷用): 見 CHEST_ACTION case 裡面那段解釋 PIR 事件不穩定
        // 觸發的 comment - 這裡加一句總入口 log, 記下這個 receiver 實際收到了
        // 哪個 action, 讓下次可以完全掌握這個 receiver 的 onReceive() 有沒有被
        // Android 系統真正 call 到、頻率是怎樣, 不用只靠個別 case 裡面的 debug
        // event 間接推斷。
        Log.i(TAG, "onReceive action=" + action);
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    // 2026-08: 反編譯 alpha2services_base 3.0.0.2 整個 APK, 找不到
                    // 任何 sendBroadcast("com.ubtechinc.key") 的出處 —— 這個 action
                    // 在這個韌體版本實際上是死 code, 永遠不會觸發, 只是保留做向後
                    // 相容 (以防其他韌體/舊機用回這個 action)。
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
                    // 2026-08 新增: 用戶開口那一刻立刻探測一次雲端連通性, 讓
                    // 「雲端聽寫/離線文法」自動切換在第一句對答就和現實同步
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
                    // 2026-08 v2 修正: SN 欄位是定長, 尾部會有 padding, 直接進
                    // JSON 會變成控制字元讓瀏覽器 JSON.parse 爆炸, UI 永遠停在
                    // 「查詢中」。切掉第一個 \0 之後的東西才 publish。
                    //
                    // 2026-08 v4 修正: 單靠 indexOf('\0') 不夠 —— 真機 logcat
                    // (CHEST_READ_SID_EEPROM 官方 firmware log) 觀察到讀回來的
                    // 尾段部分情況下不是乾淨的 0x00 padding, 有非零垃圾殘留 (舊
                    // SN 較長時, 新 SN 較短的情況下), \0 cut 完全蓋不到這些垃圾
                    // byte, 讓 UI 顯示變成一堆方塊/亂碼字元。SN 本身的合法字元
                    // 集只有英數/-/_ (見 misc/set_uuid 的輸入驗證), 所以這裡加多
                    // 一層白名單過濾, 只保留這些合法字元, 不理殘留垃圾 byte
                    // 實際數值是什麼, 保證顯示和 QR code 都乾淨。
                    String s = String.valueOf(uuid);
                    int cut = s.indexOf('\0');
                    if (cut >= 0) {
                        s = s.substring(0, cut);
                    }
                    s = s.replaceAll("[^A-Za-z0-9\\-_]", "");
                    EventBus.get().publish("robot_uuid", "{\"uuid\":" + jsonValue(s.trim()) + "}");
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
                    // 沒有 extra。代表機身側動作播放被外部打斷停止 —— 和
                    // IActionResultListener.onStopActionResult() 不同, 這個是全域廣播,
                    // 不限於自己 call 著那個 playAction() session。
                    EventBus.get().publish("action_stop", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.ROBOT_INTERRUPTED": {
                    // 2026-08 新增: 反編譯確認, AlphaUtils.sendInterruptIntent() 發出,
                    // 沒有 extra。代表機械人整體被打斷 (通常和 TTS/action 一起停)。
                    EventBus.get().publish("robot_interrupted", "{}");
                    break;
                }
                case StaticValue.CHEST_ACTION: {
                    // 2026-08 更新: 之前假設 sonar 讀數經這個全域 broadcast 送 -
                    // 反編譯官方 UBTech alpha2demo.apk (firmware 1.1.1.14) 之後證實
                    // 這個假設錯了。Demo 自己的 receiver (ActionMainActivity$6)
                    // 對這個 action 只是將 extra "value" (byte[]) 包成
                    // Alpha2ProtocolPacket 之後 Log.d 那個 getmCmd() 做 debug, 完全沒
                    // 用來顯示 sonar 距離。真正的 sonar 事件是下面獨立的
                    // StaticValue.SONAR_DISTANCE_ACTION case, 保留這裡純粹做輔助
                    // debug (可以看到機身內部 raw command byte 的時序), 不再指望
                    // 它是 sonar 的來源。
                    //
                    // 2026-08 新增 (診斷用): 用戶反映 PIR 事件推播完全沒反應 - 對照
                    // logcat 才發現一個之前完全未察覺的盲點: 官方 AlphaMainSeviceImpl
                    // (pid 990) 自己的 log 顯示它持續收到 "ches cmd = -109" (PIR)
                    // 很多次, 但我們自己這個 RobotEventReceiver 全程 log 顯示一直都
                    // 只收到過一次 CHEST_ACTION (還是 -115, 不是 -109) - 也就是說
                    // 我們自己的 receiver 實際上沒穩定收到這個 broadcast, 之前
                    // comment 講的「已在真機確認會正常觸發」這個結論可能只在
                    // 某一次特定測試環境才對, 不是穩定行為。之前只靠
                    // chest_broadcast_debug 這個 EventBus event 做 debug, 沒直接
                    // 印到 logcat, 讓這個問題一直沒被發現。這裡加一句直接的
                    // Log.i, 印下每一次這個 receiver 真正收到 CHEST_ACTION 的完整
                    // raw value, 讓下次可以直接對比「官方 AlphaMainSeviceImpl 收到
                    // 多少次 -109」和「我們自己這個 receiver 實際收到多少次、是什麼
                    // cmd 值」, 才可以確診是不是這個 broadcast 本身有 gate/rate-limit
                    // 讓不是每次都轉發給第三方 app。
                    Log.i(TAG, "CHEST_ACTION received, raw value=" + bundleToJson(intent.getExtras()));
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");

                    // 2026-08 新增: 心口 mute 鍵測試 - 反編譯官方 alpha2services
                    // 3.0.0.2 APK (AlphaMainSeviceImpl$15.onReceive() 的
                    // sparse-switch) 確認, 心口 mute 鍵按下去會經這個同一個
                    // CHEST_ACTION broadcast 送出, extra "value" (byte[]) 裡面
                    // 會有 -111 (0x91) 這個 byte。這台機兩份提供了的 logcat 都
                    // 見過這個值 (firmware 側 "ches cmd = -111", raw wire frame
                    // f8 8f 08 00 00 91 01 9a ed / f8 8f 08 00 00 91 00 99 ed),
                    // 已經確認會實際觸發, 不像 chest_setPirSensorEnabled() 那個
                    // cmd=72 那樣只是反編譯推斷。
                    //
                    // 2026-08-14 更新: 之前用 ((byte[])rawValue)[0] == -111 (只
                    // 看陣列第一個 byte) 在真機測試完全沒反應。原因: logcat 沒印
                    // 下 bundleToJson(intent.getExtras()) 的實際內容, 沒辦法 100%
                    // 確認 Android SDK 傳過來的 "value" extra 陣列, 那個 -111 (0x91)
                    // 這個 byte 究竟排在陣列哪個 index (SDK 可能有剝掉/不剝掉
                    // firmware wire frame 的 f8 8f 08 00 00 這段 header, 或者還有
                    // 其他包裝) - 對比 handleChestObstacleFrame() 用 bytes[0] 判斷
                    // sonar (-127) 那個做法, 兩個 callback 未必用著同一種 trim 方式。
                    // 為避免再靠猜 index 錯一次, 這裡改為掃描整個陣列, 不理位置,
                    // 只要陣列裡面出現過 -111 就當按了。已核對這台機兩份 logcat
                    // 見過的全部 raw wire frame (cmd -115/-111/-109/-128 對應的
                    // checksum byte 分別是 0x97/0x9a,0x99/0x9c/0x8c), 沒有一個和
                    // 0x91 撞值, 所以掃描全陣列在這些已知樣本裡面不會誤觸發。
                    Object rawValue = readAny(intent, "value");
                    if (rawValue instanceof byte[]) {
                        byte[] arr = (byte[]) rawValue;

                        // 2026-08-25 新增: 心口 mute 鍵現在會分「按下(01)/放開(00)」-
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

                        // 2026-08-14 新增: PIR sensor raw 觸發事件 (cmd=-109 / 0x93,
                        // 反編譯 alpha2services 1.2.10.5 APK 的 AlphaMainSeviceImpl$15.
                        // onReceive() sparse-switch 確認, log 字串 "PIR HUMON DETECT
                        // (1: ENTER)  (0: EXIT)")。
                        //
                        // 這台機 (1.1.7.3) 和 1.2.10.5 用著完全同一塊 chest 主板/MCU
                        // (Arthur 已用 Lynx 3.0.0.2 確認硬體正常, logcat 見過 cmd=-109
                        // raw byte 出現) - 差別純粹在 1.1.7.3 這個 Android apk 版本的
                        // AlphaMainSeviceImpl$14.onReceive() 對 -109/-111/-108 這幾個
                        // case 完全沒支援 (反編譯 1.1.7.3 apk 的 sparse-switch-payload
                        // 逐個核對過, 這三個值在 1.1.7.3 不存在, 只有在 1.2.10.5 才新增),
                        // 所以 MCU 送出來的 raw event 在官方 code 一律跌到 default 分支
                        // 只 log 一句 "ches cmd = -109" 就完, 不會轉發成
                        // com.ubtech.securityCamera.pirStatus broadcast (這個轉發還要
                        // 通過 SecurityCameraUtil.isMonitoringEnabled() gate, 而
                        // SecurityCameraUtil 這個 class 在 1.1.7.3 根本不存在)。
                        //
                        // 心口 mute 鍵 (-111) 已經證實: 只要自己 app 直接讀這個廣播的
                        // raw "value" byte[], 不靠官方 code 認不認得這個 case, 一樣讀得到。
                        // 這裡用回同一個做法去讀 PIR。已在真機確認會正常觸發
                        // (logcat_2026-08-15_12-06-19.txt 見到 chest cmd=-109
                        // 持續觸發)。
                        //
                        // Sub-value (ENTER=1/EXIT=0) 位置: 反編譯結果是
                        // Alpha2ProtocolPacket.e() (param array) 的 index 0, 也就是
                        // cmd byte 之後那一個 byte。因為未證實這台機的 SDK 傳過來的
                        // "value" 陣列, 是未拆解的完整 wire frame (例如
                        // f8 8f 08 00 00 93 01 9c ed) 還是已經拆掉 header/checksum
                        // 只有 param (例如 {01}), 這裡做法是: 找到 -109 這個 byte
                        // 的 index, 如果它不是陣列最後一個, 就取它下一個 byte 做
                        // ENTER/EXIT 判斷; 如果它剛好是最後一個 byte (也就是已拆解
                        // 只有 cmd 沒 param 的情況), 就沒 sub-value 可取, 只發
                        // 「偵測到事件」這個訊號, triggered 保守地當 true (收到這個
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
                    // 2026-08 新增: ⚠️ 未經真機驗證 (這個 gate 本身在 1.1.7.3 一直
                    // 沒 fire 過 - 見 CHEST_ACTION case 裡面新加那段直接掃描 -109 的
                    // comment, 已經證實 SecurityCameraUtil 這個 class 在 1.1.7.3
                    // 根本不存在, 這個 broadcast 理論上不會被送出)。保留這個 case
                    // 純粹是萬一將來換了支援這個 gate 的 firmware 版本, 兩條路都
                    // 餵去同一個 "alpha2_pir_state" event, 前端不用理背後走哪條路。
                    // 反編譯官方 alpha2services 3.0.0.2 APK 逆出來的 PIR 通知
                    // broadcast (AlphaMainSeviceImpl 在 CHEST_ACTION 的 default 分支
                    // 找到 PIR raw byte 之後, 如果 SecurityCameraUtil.
                    // isMonitoringEnabled() 開著, 才會轉發這個獨立 broadcast)。extra
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
                case StaticValue.SONAR_DISTANCE_ACTION: {
                    // 2026-08 新增: 反編譯官方 UBTech alpha2demo.apk (firmware
                    // 1.1.1.14) 確認 - sonar 讀數真正經這個獨立 broadcast 送出, extra
                    // 已經是 firmware parse 好的 int (SONAR_DISTANCE_EXTRA =
                    // "sonar_distance"), 不用自己再解 raw wire frame。Demo 自己的
                    // UI (ActionMainActivity$7) 用 intent.getIntExtra(key, 0) 讀,
                    // 0 或負數當「沒讀數/超出範圍」顯示成 "INF" - 這裡跟回同一個假設。
                    // 這個值究竟是不是已經是 cm 未經 100% 證實 (demo 只是直接印出來,
                    // 沒做任何換算), 但 enableSonar() 送出去的 config 第二個 param
                    // byte (40) 看起來像是 cm 門檻, 兩者單位一致的可能性高。
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
                // filter.addAction() comment): 用來查 speech_SetMIC()/setWakeState()
                // 拿回 mic 這一刻機身有沒有發任何 broadcast 通知這個問題。反編譯找到
                // 這 8 個 action 都由 SpeechServiceImpl/SpeechManager
                // (com.ubtechinc.speechmanager.d/b package) 或者
                // AlphaMainSeviceImpl 發出, 名字/extras 看起來和 TTS、ASR、mic 相關
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
        // 2026-08-14 修正: byte[] (CHEST_ACTION 的 "value" extra 就是這種) 沒在
        // 上面覆蓋到, fallback 到底 String.valueOf(value) 會取 Object.toString()
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
