package com.open.alpha2;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BT 手柄 / 鍵盤輸入中心（A 端直連；手柄／鍵盤映射分開）。
 *
 * 模型（見 README／面板手柄分頁）：BT HID 手柄／鍵盤經本頁掃描／配對／連接
 * 到機器人本機 Android，經 MainActivity.dispatchKeyEvent／
 * onGenericMotionEvent 截獲，認得的鍵按該邊映射執行（撳鍵就郁，離線可用）；
 * 滑鼠類一律吞。直連手柄／直連鍵盤開關分開（{@code gamepad/input/*}，預設
 * 雙開），熄邊只記唔做（見 {@link #recordOnlyRobotKey(int, boolean, String)}）。
 * 配對≠連接，未連上經 {@code gamepad/connect} 主動連，唔要經
 * {@code gamepad/unpair} 解除綁定。（B 端瀏覽器遙控已移除；
 * {@code gamepad/key}／{@code gamepad/axis} 後端保留畀脚本直調。）
 *
 * 兩邊映射：pad 22 鍵／kb 105 鍵，同名獨立值（SharedPreferences 持久化）。
 *
 * 邏輯鍵名（穩定，不隨 Android／瀏覽器 keyCode 變）：
 * pad：up／down／left／right／a／b／x／y／start／select／l1／r1／l2／r2／
 * lx_neg／lx_pos／ly_neg／ly_pos／rx_neg／rx_pos／ry_neg／ry_pos（pad 無 vol 鍵；
 * 手柄音量鍵行系統預設，不經映射）。
 * kb：方向 4 粒＋a-z／0-9／space／enter／tab／backspace／esc／shift／ctrl／alt／
 * caps／vol_up／vol_down／media_play／media_next／media_prev／media_stop／mute／
 * 標點 11 粒／ins／del／home／end／pgup／pgdn／num_0-9／num_div／num_mul／
 * num_sub／num_add／num_dot／num_enter／print／scroll／pause／menu／f1-f12。
 *
 * 動作值格式：
 * none（忽略）／stop（停動作＋蹲下站起回位）／vol_up／vol_down／
 * music_play／music_next／music_prev／music_pause／music_resume／music_toggle／
 * music_stop／music_random／radio_toggle／disco_toggle／filler_toggle（經 AudioCenter）／ring_phone／ring_stop／ring_random／
 * ring:&lt;title&gt;／ring:phone:&lt;title&gt;／ring:notification:&lt;title&gt;（經 RingtoneCenter）／led_off／head:&lt;preset&gt;:&lt;color&gt;／head:random／
 * eye:&lt;preset&gt;:&lt;color&gt;／eye:random／mouth:breathing:&lt;speedMs&gt;／mouth:off（經 LedCenter）／
 * camera_photo／face_toggle／color_toggle（追蹤 toggle）／video_toggle（錄影 toggle）／
 * sonar_toggle／pir_toggle／accel_toggle（傳感器 toggle）（經 CameraApi／VideoCenter／
 * SonarCenter／LedCenter／DeviceStatus）／xiaozhi_toggle（小智連線 toggle，經 XiaozhiBridge）／
 * vosk_toggle／vosk:&lt;id&gt;（Vosk 監聽／切包 toggle，經 VoskApi）／eq:&lt;preset&gt;（EQ toggle，經 AudioCenter）／
 * radio:&lt;uuid或名&gt;（收藏電台直播，經 AudioCenter）／
 * servo&lt;1-22&gt;+／servo&lt;1-22&gt;-（單步 jog，步長 {@link #SERVO_JOG_STEP}，
 * 撳住連走）／action:&lt;fileId或中英文名或xxx.ubx&gt;（經 ActionDirect 搶佔式播放）
 *
 * 搖桿連續量（gamepad/axis）：rx→20 號頭 yaw，ry→19 號頭 pitch，
 * 死區＋150ms 節流＋增量速度式（不跳變），見 {@link #axisResponse}。
 */
public final class GamepadCenter {
    private static final String TAG = "GamepadCenter";

    /** 兩邊代號（HTTP side 參數／內部 side 標記；大小寫不敏感，縮寫 p/k 都認）。 */
    public static final String SIDE_PAD = "pad";
    public static final String SIDE_KB = "kb";

    /**
     * 手柄邊鍵（22；順序即前端下拉框順序）。
     * up/down/left/right＝十字；a/b/x/y＝按鈕；start/select／l1/r1/l2/r2；
     * lx_*／ly_*＝左搖桿四向；rx_*／ry_*＝右搖桿四向（搖桿方向配咗就照映射行，
     * 唔配（none）右桿行返轉頭 legacy，左桿唔郁——同以前一模一樣）。
     */
    public static final String[] PAD_KEYS = {
            "up", "down", "left", "right",
            "a", "b", "x", "y",
            "start", "select",
            "l1", "r1", "l2", "r2",
            "lx_neg", "lx_pos", "ly_neg", "ly_pos",
            "rx_neg", "rx_pos", "ry_neg", "ry_pos"
    };

    /**
     * 鍵盤邊鍵（105；順序即前端下拉框順序；顯示名即鍵名，無前綴）。
     * 方向 4 粒＋a/b/x/y 同手柄邊同名但獨立值（兩邊分開配）；其餘字母數字
     * 原樣小寫＋控制鍵＋標點＋音量＋F1-F12。
     */
    public static final String[] KB_KEYS = {
            "up", "down", "left", "right",
            "a", "b", "x", "y",
            "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "space", "enter", "tab", "backspace", "esc", "shift",
            "ctrl", "alt", "caps",
            "vol_up", "vol_down",
            "media_play", "media_next", "media_prev", "media_stop", "mute",
            "minus", "equals", "lbracket", "rbracket", "backslash",
            "semicolon", "quote", "comma", "period", "slash", "grave",
            "ins", "del", "home", "end", "pgup", "pgdn",
            "num_0", "num_1", "num_2", "num_3", "num_4",
            "num_5", "num_6", "num_7", "num_8", "num_9",
            "num_div", "num_mul", "num_sub", "num_add", "num_dot", "num_enter",
            "print", "scroll", "pause", "menu",
            "f1", "f2", "f3", "f4", "f5", "f6",
            "f7", "f8", "f9", "f10", "f11", "f12"
    };

    /** side 正規化（null／錯即回 pad；認全名＋首字母）。 */
    public static String normalizeSide(String side) {
        if (side == null) return SIDE_PAD;
        String s = side.trim().toLowerCase(java.util.Locale.US);
        if (s.equals(SIDE_KB) || s.equals("k") || s.equals("keyboard")) return SIDE_KB;
        return SIDE_PAD;
    }

    /** 該邊識唔識呢個鍵名。 */
    public static boolean isKnownKey(String side, String key) {
        if (key == null) return false;
        String[] all = SIDE_KB.equals(normalizeSide(side)) ? KB_KEYS : PAD_KEYS;
        for (String k : all) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /** 單步 jog 步長（0-255 servo 單位）＋時間。 */
    public static final int SERVO_JOG_STEP = 8;
    public static final int SERVO_JOG_TIME_MS = 300;
    /** 搖桿死區＋節流＋滿偏每 tick 步長。 */
    private static final float AXIS_DEADZONE = 0.15f;
    private static final long AXIS_MIN_INTERVAL_MS = 150;
    private static final float AXIS_FULL_STEP = 6.0f;

    private static final String PREFS_KEY_MAPPING = "gamepad_mapping_json";

    private final Context appContext;
    private final ActionDirect actionDirect;
    private final UbxApi ubxApi;
    private final UbxPlayer ubxPlayer;
    private final DeviceStatus deviceStatus;
    private final AudioManager audioManager;

    // axis 增量位姿（19/20），null＝尚未初始化（下次用 pose／128 起步）。
    private Integer axisPose19;
    private Integer axisPose20;
    private long lastAxisMs = 0;

    // 最後一次按鍵（status 回顯＋除錯用；taken 供面板分清「做咗」定「只記錄」）。
    private volatile String lastKey = "";
    private volatile String lastAction = "";
    private volatile String lastSource = "";
    private volatile boolean lastPressed = true;
    private volatile boolean lastTaken = false;
    private volatile long lastAtMs = 0;

    private final AudioCenter audioCenter;
    private final RingtoneCenter ringtoneCenter;
    private final LedCenter ledCenter;
    private final SpeechCenter speechCenter;
    private final CameraApi cameraApi;
    private final SonarCenter sonarCenter;
    private final XiaozhiBridge xiaozhiBridge;
    private final VoskApi voskApi;

    public GamepadCenter(Context context, ActionDirect actionDirect, UbxApi ubxApi,
            UbxPlayer ubxPlayer, DeviceStatus deviceStatus, AudioCenter audioCenter,
            RingtoneCenter ringtoneCenter, LedCenter ledCenter,
            SpeechCenter speechCenter, CameraApi cameraApi, SonarCenter sonarCenter,
            XiaozhiBridge xiaozhiBridge, VoskApi voskApi) {
        this.appContext = context.getApplicationContext();
        this.actionDirect = actionDirect;
        this.ubxApi = ubxApi;
        this.ubxPlayer = ubxPlayer;
        this.deviceStatus = deviceStatus;
        this.audioCenter = audioCenter;
        this.ringtoneCenter = ringtoneCenter;
        this.ledCenter = ledCenter;
        this.speechCenter = speechCenter;
        this.cameraApi = cameraApi;
        this.sonarCenter = sonarCenter;
        this.xiaozhiBridge = xiaozhiBridge;
        this.voskApi = voskApi;
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        startRefPosePoll();
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -- 機身直連輸入開關（分邊） -------------------------------------------------
    // 兩邊預設都開（撳鍵就郁）；邊嚇親熄邊——熄咗嗰邊直連一律只記唔做。
    // 刻意唔認舊版單一鍵（gamepad_input_enabled）：實測 install -r 會將 prefs
    // 打回舊 snapshot，舊 false 陰魂不散；新鍵缺席一律當開，用家熄過的
    // （新鍵在）先尊重。舊鍵現已無任何讀寫，只留註解說明，不留常數。
    private static final String PREFS_KEY_INPUT_PAD = "gamepad_input_pad";
    private static final String PREFS_KEY_INPUT_KB = "gamepad_input_kb";

    /**
     * 該邊開關（預設開）。
     * 刻意唔認舊單一鍵（gamepad_input_enabled）：實測 install -r 會將 prefs 打回舊
     * snapshot，舊 false 陰魂不散；新鍵缺席一律當開，用家熄過的（新鍵在）先尊重。
     */
    public synchronized boolean isInputEnabled(String side) {
        try {
            String key = SIDE_KB.equals(normalizeSide(side)) ? PREFS_KEY_INPUT_KB : PREFS_KEY_INPUT_PAD;
            if (prefs().contains(key)) {
                return prefs().getBoolean(key, true);
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    public synchronized void setInputEnabled(String side, boolean on) {
        prefs().edit().putBoolean(SIDE_KB.equals(normalizeSide(side))
                ? PREFS_KEY_INPUT_KB : PREFS_KEY_INPUT_PAD, on).apply();
    }

    public HttpServer.ApiResponse inputGetResponse() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"pad\":" + isInputEnabled(SIDE_PAD)
                + ",\"kb\":" + isInputEnabled(SIDE_KB) + "}");
    }

    public HttpServer.ApiResponse inputSetResponse(Map<String, String> query) {
        boolean on = ApiValidator.requireBoolean(query, "on");
        String side = query.get("side");
        // 唔帶 side＝兩邊一齊set（舊前端兼容）。
        if (side == null || side.trim().isEmpty()) {
            setInputEnabled(SIDE_PAD, on);
            setInputEnabled(SIDE_KB, on);
        } else {
            setInputEnabled(normalizeSide(side), on);
        }
        return inputGetResponse();
    }

    // -- 預設映射（分邊；同名鍵獨立值；user snapshot as factory default，
    // 烘於 2026-10-10：reset／新裝機回呢套；舊機已存值唔受影響） ------------
    public static Map<String, String> defaultPadMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : PAD_KEYS) m.put(k, "none");
        m.put("up", "action:1508999860568");
        m.put("down", "action:1510818256622");
        m.put("left", "action:1464835936046");
        m.put("right", "action:1464835936040");
        m.put("a", "servo20+");
        m.put("b", "servo19+");
        m.put("x", "servo19-");
        m.put("y", "servo20-");
        m.put("start", "action:1510818174706");
        m.put("select", "action:1514366181328");
        m.put("l1", "action:1464835936018");
        m.put("r1", "action:1464835936017");
        m.put("l2", "action:1464835936047");
        m.put("r2", "action:1464835936041");
        m.put("lx_neg", "servo5+");
        m.put("lx_pos", "servo5-");
        m.put("ly_neg", "servo4+");
        m.put("ly_pos", "servo4-");
        m.put("rx_neg", "servo2+");
        m.put("rx_pos", "servo2-");
        m.put("ry_neg", "servo1-");
        m.put("ry_pos", "servo1+");
        return m;
    }

    public static Map<String, String> defaultKbMapping() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : KB_KEYS) m.put(k, "none");
        m.put("up", "action:1508999860568");
        m.put("down", "action:1510818256622");
        m.put("left", "action:1464835936040");
        m.put("right", "action:1464835936035");
        m.put("a", "servo17-");
        m.put("b", "servo20+");
        m.put("x", "servo3-");
        m.put("c", "servo1+");
        m.put("d", "servo1-");
        m.put("e", "servo2+");
        m.put("f", "servo19+");
        m.put("h", "servo19-");
        m.put("i", "servo5+");
        m.put("j", "servo4+");
        m.put("k", "servo6-");
        m.put("l", "servo18+");
        m.put("m", "servo4-");
        m.put("o", "action:1510818054178");
        m.put("q", "action:1509007823124");
        m.put("s", "servo3+");
        m.put("t", "servo20-");
        m.put("u", "servo5-");
        m.put("w", "servo2-");
        m.put("z", "servo17+");
        m.put("1", "xiaozhi_toggle");
        m.put("2", "vosk:vosk-model-small-cn-0.22");
        m.put("3", "vosk:vosk-model-small-en-us-0.15");
        m.put("4", "vosk:vosk-model-small-ja-0.22");
        m.put("5", "radio:fbdf80cd-cb85-45ea-b028-8a43d794d22b");
        m.put("6", "radio:67bb21a5-2933-48f4-9b96-a8cab6cc6c81");
        m.put("7", "sonar_toggle");
        m.put("8", "pir_toggle");
        m.put("9", "accel_toggle");
        m.put("space", "stop");
        m.put("enter", "stop");
        m.put("vol_up", "vol_up");
        m.put("vol_down", "vol_down");
        m.put("media_play", "music_toggle");
        m.put("media_next", "music_next");
        m.put("media_prev", "music_prev");
        m.put("media_stop", "music_stop");
        m.put("comma", "servo6+");
        m.put("period", "servo18-");
        m.put("f2", "disco_toggle");
        m.put("f3", "filler_toggle");
        m.put("f4", "ring_random");
        m.put("f5", "ring_random");
        m.put("f6", "head:random");
        m.put("f7", "eye:random");
        m.put("f8", "mouth:breathing:150");
        m.put("f9", "face_toggle");
        m.put("f10", "color_toggle");
        m.put("f11", "pir_toggle");
        m.put("f12", "accel_toggle");
        return m;
    }

    /**
     * 本地音樂／電台／播歌燈效功能鍵（映射動作值，經 AudioCenter；toggle＝播緊停、
     * 停緊續、無歌播首首；random＝隨機一首；radio_toggle＝停／重播上次電台；
     * disco_toggle＝節奏燈開關；filler_toggle＝播歌隨機動作開關）。
     */
    public static final String[] MUSIC_ACTIONS = {
            "music_play", "music_next", "music_prev", "music_pause", "music_resume",
            "music_toggle", "music_stop", "music_random",
            "radio_toggle", "disco_toggle", "filler_toggle"
    };



    private static final String[] HEAD_PRESETS = {"long", "flash", "breathe", "chase", "dual", "stop"};
    private static final String[] EYE_PRESETS = {"long", "flash", "chase", "dual", "stop"};

    /**
     * 頭／眼／咀 LED 動作值合法性：{@code head:stop}／{@code eye:stop}／
     * {@code mouth:off} 或 {@code head:<preset>:<color>}／
     * {@code eye:<preset>:<color>}／{@code mouth:breathing:<speedMs>}
     * （color 1-7；eye 無 breathe；咀速 100-1000ms，同 HTTP／spec 一致）。
     */
    public static boolean isLedAction(String a) {
        if (a == null) return false;
        String[] parts = a.trim().split(":", -1);
        if (parts.length < 2 || parts.length > 3) return false;
        String part = parts[0];
        String preset = parts[1];
        if ("mouth".equals(part)) {
            if ("off".equals(preset)) return parts.length == 2;
            if (!"breathing".equals(preset) || parts.length != 3) return false;
            try {
                int speed = Integer.parseInt(parts[2].trim());
                return speed >= ApiValidator.LED_MOUTH_SPEED_MIN_MS
                        && speed <= ApiValidator.LED_MOUTH_SPEED_MAX_MS;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        boolean head;
        if ("random".equals(preset)) {
            // head:random／eye:random（咀無）：每次執行即場抽效果＋色（見執行分支）。
            if (!"head".equals(part) && !"eye".equals(part)) return false;
            return parts.length == 2;
        }
        if ("head".equals(part)) head = true;
        else if ("eye".equals(part)) head = false;
        else return false;
        String[] allowed = head ? HEAD_PRESETS : EYE_PRESETS;
        boolean okPreset = false;
        for (String p : allowed) {
            if (p.equals(preset)) {
                okPreset = true;
                break;
            }
        }
        if (!okPreset) return false;
        if ("stop".equals(preset)) return parts.length == 2;
        if (parts.length != 3) return false;
        try {
            int color = Integer.parseInt(parts[2].trim());
            return color >= ApiValidator.LED_COLOR_MIN && color <= ApiValidator.LED_COLOR_MAX;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 相機動作合法性：影相＋人臉／顏色追蹤 toggle＋錄影 toggle（開／停已合併，一鍵足夠）。
     *  face_start／color_start 係舊名，別名保留（讀入即遷移見 loadMapping）。 */
    public static boolean isCameraAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        return "camera_photo".equals(t) || "face_toggle".equals(t)
                || "color_toggle".equals(t) || "video_toggle".equals(t)
                || "face_start".equals(t) || "color_start".equals(t);
    }

    /** 傳感器 toggle 合法性：聲納／PIR／加速度計單鍵開關。 */
    public static boolean isSensorAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        return "sonar_toggle".equals(t) || "pir_toggle".equals(t)
                || "accel_toggle".equals(t);
    }

    /** 語音 toggle 合法性：小智連線／Vosk 監聽單鍵開關／Vosk 切指定語言包。 */
    public static boolean isVoiceAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        if ("xiaozhi_toggle".equals(t) || "vosk_toggle".equals(t)) return true;
        if (t.startsWith("vosk:")) {
            String rest = t.substring(5).trim();
            if (rest.isEmpty()) return false;
            if (rest.contains("/") || rest.contains("\\") || rest.contains("..")) return false;
            return true;
        }
        return false;
    }

    /** EQ preset toggle 合法性：eq:&lt;preset&gt;（同 musicEqSet 同一名單）。 */
    public static boolean isEqAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        if (!t.startsWith("eq:")) return false;
        return MusicEq.isPreset(t.substring(3).trim());
    }

    /** 收藏電台合法性：radio:&lt;uuid或名&gt;（名可含標點，禁換行；toggle 另見 music 種）。 */
    public static boolean isRadioFavAction(String a) {
        if (a == null) return false;
        String t = a.trim();
        if (!t.startsWith("radio:")) return false;
        String rest = t.substring(6).trim();
        if (rest.isEmpty() || rest.contains("\n") || rest.contains("\r")) return false;
        return true;
    }

    /** 鈴聲動作合法性：{@code ring_phone}／{@code ring_stop}／{@code ring_random}／
     *  {@code ring:<title>}（舊式，全表搵）／{@code ring:phone:<title>}／
     *  {@code ring:notification:<title>}（分表搵）。 */
    public static boolean isRingAction(String a) {
        return ringTitleOf(a) != null
                || "ring_phone".equals(a != null ? a.trim() : null)
                || "ring_stop".equals(a != null ? a.trim() : null)
                || "ring_random".equals(a != null ? a.trim() : null);
    }

    /** ring 動作拆 type（phone／notification／null＝舊式全表）。非法回 null。 */
    public static String ringTypeOf(String a) {
        if (a == null) return null;
        String t = a.trim();
        if (t.startsWith("ring:phone:")) return "phone";
        if (t.startsWith("ring:notification:")) return "notification";
        if (t.startsWith("ring:")) return null;
        return null;
    }

    /** ring 動作拆 title（trim 後；空／有換行／唔係 ring 形即回 null）。 */
    public static String ringTitleOf(String a) {
        if (a == null) return null;
        String t = a.trim();
        String title;
        if (t.startsWith("ring:phone:")) {
            title = t.substring("ring:phone:".length());
        } else if (t.startsWith("ring:notification:")) {
            title = t.substring("ring:notification:".length());
        } else if (t.startsWith("ring:")) {
            title = t.substring("ring:".length());
        } else {
            return null;
        }
        title = title.trim();
        if (title.isEmpty() || title.contains("\n") || title.contains("\r")) return null;
        // 舊式要擋掉固定三個（佢哋唔係 title，上面 isRingAction 另行收）。
        if (title.equals("phone") || title.equals("stop") || title.equals("random")) return null;
        return title;
    }

    public static boolean isMusicAction(String a) {
        if (a == null) return false;
        for (String m : MUSIC_ACTIONS) {
            if (m.equals(a)) return true;
        }
        return false;
    }

    /**
     * 動作值合法性：none／stop／vol_up／vol_down／music_*／ring_*／led_*／led_off／
     * camera_photo／face_toggle／color_toggle／video_toggle／sonar_toggle／
     * pir_toggle／accel_toggle／xiaozhi_toggle／vosk_toggle／vosk:&lt;id&gt;／
     * eq:&lt;preset&gt;／radio:&lt;uuid或名&gt;／servo1-22±／action:&lt;非空&gt;。
     * servo 步進覆蓋全部 22 軸（jogServo 通用）。
     */
    public static boolean isValidAction(String action) {
        if (action == null) return false;
        String a = action.trim();
        if (a.equals("none") || a.equals("stop") || a.equals("vol_up") || a.equals("vol_down")) return true;
        if (isMusicAction(a)) return true;
        if (isRingAction(a)) return true;
        if (isLedAction(a)) return true;
        if (isCameraAction(a)) return true;
        if (isSensorAction(a)) return true;
        if (isVoiceAction(a)) return true;
        if (isEqAction(a)) return true;
        if (isRadioFavAction(a)) return true;
        if ("led_off".equals(a)) return true;
        if (isServoJog(a)) return true;
        if (a.startsWith("action:")) {
            String rest = a.substring(7).trim();
            if (rest.isEmpty()) return false;
            if (rest.contains("/") || rest.contains("\\") || rest.contains("..")) return false;
            return true;
        }
        return false;
    }

    /** servo&lt;1-22&gt;± 形（如 servo20+）：軸號＋方向一次驗。 */
    public static boolean isServoJog(String a) {
        if (a == null || a.length() < 7) return false;
        if (!a.startsWith("servo")) return false;
        char last = a.charAt(a.length() - 1);
        if (last != '+' && last != '-') return false;
        try {
            int id = Integer.parseInt(a.substring(5, a.length() - 1));
            return id >= ApiValidator.SERVO_ID_MIN && id <= ApiValidator.SERVO_ID_MAX;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final String PREFS_KEY_MAPPING_KB = "gamepad_kb_mapping_json";
    /**
     * kb 檔版本戳（新預設遷移只做一次：舊檔（遷移前存的 none）升新預設；
     * 之後任何 save 即打戳，之後用家親手設 none 即真 none，唔會再被遷移食咗。
     */
    private static final String PREFS_KEY_KB_MAP_V1 = "gamepad_kb_mapping_v1";

    /** 該邊存檔 key（pad 沿用舊單一鍵，kb 獨立）。 */
    private static String mappingPrefsKey(String side) {
        return SIDE_KB.equals(normalizeSide(side)) ? PREFS_KEY_MAPPING_KB : PREFS_KEY_MAPPING;
    }

    /** 該邊預設（pad／kb 各自）。 */
    public static Map<String, String> defaultSideMapping(String side) {
        return SIDE_KB.equals(normalizeSide(side)) ? defaultKbMapping() : defaultPadMapping();
    }

    /**
     * 新預設追溯鍵（F1-F12 鈴聲、0-9 頭眼燈／咀）：舊存檔呢啲位係 none
     * （當年預設），未遷移過的檔讀嗰陣自動升做新預設；用家自己改過
     * （非 none）一律保留。打戳後（見 PREFS_KEY_KB_MAP_V1）唔再升——
     * 用家親手設 none 即真 none。唔成個 reset——唔郁用家配過嘅嘢。
     */
    private static final java.util.Set<String> UPGRADED_DEFAULT_KEYS = new java.util.HashSet<String>();
    static {
        for (int i = 1; i <= 12; i++) UPGRADED_DEFAULT_KEYS.add("f" + i);
        for (int i = 0; i <= 9; i++) UPGRADED_DEFAULT_KEYS.add(String.valueOf(i));
    }

    /** kb 遷移戳（打咗＝之後存過，用家 none 即真 none）。 */
    private boolean kbMappingMigrated() {
        try {
            return prefs().getBoolean(PREFS_KEY_KB_MAP_V1, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 讀該邊全量映射（存過的覆蓋預設；非法／唔屬呢邊的鍵即掉）。 */
    public synchronized Map<String, String> loadMapping(String side) {
        String norm = normalizeSide(side);
        Map<String, String> out = defaultSideMapping(norm);
        try {
            String json = prefs().getString(mappingPrefsKey(norm), null);
            // kb 首跑：舊混合檔有 kb 屬鍵即搬過嚟做種子（之後行自己檔）。
            if ((json == null || json.isEmpty()) && SIDE_KB.equals(norm)) {
                json = prefs().getString(PREFS_KEY_MAPPING, null);
            }
            if (json == null || json.isEmpty()) return out;
            // 極簡 JSON 解析（只認 {"k":"v",...} 平坦形，不引入 JSON 庫依賴）。
            String t = json.trim();
            if (!t.startsWith("{") || !t.endsWith("}")) return out;
            String inner = t.substring(1, t.length() - 1).trim();
            if (inner.isEmpty()) return out;
            for (String pair : splitTopLevel(inner)) {
                int ci = pair.indexOf(':');
                if (ci < 0) continue;
                String k = unquote(pair.substring(0, ci).trim());
                String v = unquote(pair.substring(ci + 1).trim());
                // 追蹤 toggle 正名遷移（face_stop／face_start→face_toggle，color 同理；兩邊通用）。
                if ("face_stop".equals(v) || "face_start".equals(v)) v = "face_toggle";
                else if ("color_stop".equals(v) || "color_start".equals(v)) v = "color_toggle";
                // dual 舊色碼修正（3=藍綠／4=紅綠／5=紅藍先有意義，其餘出嚟都係藍綠，
                // 見 app-led.js DUAL_COLORS；兩邊通用，歸位做 3）。
                String[] dp = v.split(":", -1);
                if (dp.length == 3 && ("head".equals(dp[0]) || "eye".equals(dp[0]))
                        && "dual".equals(dp[1]) && dp[2].length() == 1) {
                    char dg = dp[2].charAt(0);
                    if (dg < '3' || dg > '5') v = dp[0] + ":dual:3";
                }
                // 唔屬呢邊／舊版殘留（kb_* 等）一律掉，自動遷移。
                if (isKnownKey(norm, k) && isValidAction(v)) {
                    // 新預設追溯：呢批鍵當年預設 none，而家有料——存住 none
                    // 即係用家冇掂過，跟新預設；改過嘅保留。
                    if (SIDE_KB.equals(norm) && "none".equals(v)
                            && !kbMappingMigrated()
                            && UPGRADED_DEFAULT_KEYS.contains(k)) {
                        String nv = defaultKbMapping().get(k);
                        if (nv != null && !"none".equals(nv)) v = nv;
                    }
                    out.put(k, v);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "loadMapping failed, use defaults", e);
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() < 2 || !s.startsWith("\"") || !s.endsWith("\"")) return s;
        String body = s.substring(1, s.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                sb.append(c);
                continue;
            }
            char e = body.charAt(++i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '/': sb.append('/'); break;
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case 'u':
                    if (i + 4 < body.length()) {
                        try {
                            sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ex) {
                            // 跌穿下面，照字面出。
                        }
                    }
                    sb.append('u');
                    break;
                default: sb.append(e); break;
            }
        }
        return sb.toString();
    }

    /** 頂層逗號切（引號內逗號唔切：ring 標題有逗號唔會再切爛存檔）。 */
    private static java.util.List<String> splitTopLevel(String inner) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\' && inStr) {
                cur.append(c);
                esc = true;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
                cur.append(c);
                continue;
            }
            if (c == ',' && !inStr) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static String quote(String s) {
        if (s == null) s = "";
        // 同 JsonUtil.esc 同族（控字元都轉義），同下面 unquote() 對拆，回環唔爛。
        return "\"" + MainActivity.jsonSafe(s) + "\"";
    }

    public synchronized void saveMapping(String side, Map<String, String> mapping) {
        String norm = normalizeSide(side);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!isKnownKey(norm, e.getKey()) || !isValidAction(e.getValue())) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        sb.append('}');
        android.content.SharedPreferences.Editor ed = prefs().edit();
        ed.putString(mappingPrefsKey(norm), sb.toString());
        if (SIDE_KB.equals(norm)) ed.putBoolean(PREFS_KEY_KB_MAP_V1, true);
        ed.apply();
    }

    // -- Android 物理鍵碼 → 邏輯鍵 ------------------------------------------
    // 手柄（BUTTON_*／DPAD_*）＋全鍵盤原樣小寫（a-z／0-9／space…）。
    // 回 null＝本中心不處理（交回系統／其他鏈）。
    public static String keyCodeToLogical(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "select";
            case KeyEvent.KEYCODE_BUTTON_L1: return "l1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "r1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "l2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "r2";
            case KeyEvent.KEYCODE_VOLUME_UP: return "vol_up";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "vol_down";
            // 全鍵盤原樣小寫（a-z／0-9／space…）：撳乜見乜，兩端同一套。
            // a/b/x/y 同手柄鍵共用（同一行映射）；DPAD_CENTER 當 A 用。
            case KeyEvent.KEYCODE_A: return "a";
            case KeyEvent.KEYCODE_B: return "b";
            case KeyEvent.KEYCODE_C: return "c";
            case KeyEvent.KEYCODE_D: return "d";
            case KeyEvent.KEYCODE_E: return "e";
            case KeyEvent.KEYCODE_F: return "f";
            case KeyEvent.KEYCODE_G: return "g";
            case KeyEvent.KEYCODE_H: return "h";
            case KeyEvent.KEYCODE_I: return "i";
            case KeyEvent.KEYCODE_J: return "j";
            case KeyEvent.KEYCODE_K: return "k";
            case KeyEvent.KEYCODE_L: return "l";
            case KeyEvent.KEYCODE_M: return "m";
            case KeyEvent.KEYCODE_N: return "n";
            case KeyEvent.KEYCODE_O: return "o";
            case KeyEvent.KEYCODE_P: return "p";
            case KeyEvent.KEYCODE_Q: return "q";
            case KeyEvent.KEYCODE_R: return "r";
            case KeyEvent.KEYCODE_S: return "s";
            case KeyEvent.KEYCODE_T: return "t";
            case KeyEvent.KEYCODE_U: return "u";
            case KeyEvent.KEYCODE_V: return "v";
            case KeyEvent.KEYCODE_W: return "w";
            case KeyEvent.KEYCODE_X: return "x";
            case KeyEvent.KEYCODE_Y: return "y";
            case KeyEvent.KEYCODE_Z: return "z";
            case KeyEvent.KEYCODE_0: return "0";
            case KeyEvent.KEYCODE_1: return "1";
            case KeyEvent.KEYCODE_2: return "2";
            case KeyEvent.KEYCODE_3: return "3";
            case KeyEvent.KEYCODE_4: return "4";
            case KeyEvent.KEYCODE_5: return "5";
            case KeyEvent.KEYCODE_6: return "6";
            case KeyEvent.KEYCODE_7: return "7";
            case KeyEvent.KEYCODE_8: return "8";
            case KeyEvent.KEYCODE_9: return "9";
            case KeyEvent.KEYCODE_SPACE: return "space";
            case KeyEvent.KEYCODE_ENTER: return "enter";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_ESCAPE: return "esc";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "shift";
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "ctrl";
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return "alt";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "caps";
            // 媒體鍵（Fn 另一面常見嗰幾個；Home/Back/Menu/Search 故意唔掂，留系統）。
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "media_play";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "media_next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "media_prev";
            case KeyEvent.KEYCODE_MEDIA_STOP: return "media_stop";
            case KeyEvent.KEYCODE_MUTE: return "mute";
            case KeyEvent.KEYCODE_MINUS: return "minus";
            case KeyEvent.KEYCODE_EQUALS: return "equals";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "lbracket";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "rbracket";
            case KeyEvent.KEYCODE_BACKSLASH: return "backslash";
            case KeyEvent.KEYCODE_SEMICOLON: return "semicolon";
            case KeyEvent.KEYCODE_APOSTROPHE: return "quote";
            case KeyEvent.KEYCODE_COMMA: return "comma";
            case KeyEvent.KEYCODE_PERIOD: return "period";
            case KeyEvent.KEYCODE_SLASH: return "slash";
            case KeyEvent.KEYCODE_GRAVE: return "grave";
            // 導航區／數字區／系統鍵（全鍵盤覆蓋；HOME/BACK/SEARCH 故意唔掂，留系統）。
            case KeyEvent.KEYCODE_INSERT: return "ins";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "del";
            case KeyEvent.KEYCODE_MOVE_HOME: return "home";
            case KeyEvent.KEYCODE_MOVE_END: return "end";
            case KeyEvent.KEYCODE_PAGE_UP: return "pgup";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "pgdn";
            case KeyEvent.KEYCODE_NUMPAD_0: return "num_0";
            case KeyEvent.KEYCODE_NUMPAD_1: return "num_1";
            case KeyEvent.KEYCODE_NUMPAD_2: return "num_2";
            case KeyEvent.KEYCODE_NUMPAD_3: return "num_3";
            case KeyEvent.KEYCODE_NUMPAD_4: return "num_4";
            case KeyEvent.KEYCODE_NUMPAD_5: return "num_5";
            case KeyEvent.KEYCODE_NUMPAD_6: return "num_6";
            case KeyEvent.KEYCODE_NUMPAD_7: return "num_7";
            case KeyEvent.KEYCODE_NUMPAD_8: return "num_8";
            case KeyEvent.KEYCODE_NUMPAD_9: return "num_9";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return "num_div";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return "num_mul";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return "num_sub";
            case KeyEvent.KEYCODE_NUMPAD_ADD: return "num_add";
            case KeyEvent.KEYCODE_NUMPAD_DOT: return "num_dot";
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "num_enter";
            case KeyEvent.KEYCODE_SYSRQ: return "print";
            case KeyEvent.KEYCODE_SCROLL_LOCK: return "scroll";
            case KeyEvent.KEYCODE_BREAK: return "pause";
            case KeyEvent.KEYCODE_MENU: return "menu";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "a";
            case KeyEvent.KEYCODE_F1: return "f1";
            case KeyEvent.KEYCODE_F2: return "f2";
            case KeyEvent.KEYCODE_F3: return "f3";
            case KeyEvent.KEYCODE_F4: return "f4";
            case KeyEvent.KEYCODE_F5: return "f5";
            case KeyEvent.KEYCODE_F6: return "f6";
            case KeyEvent.KEYCODE_F7: return "f7";
            case KeyEvent.KEYCODE_F8: return "f8";
            case KeyEvent.KEYCODE_F9: return "f9";
            case KeyEvent.KEYCODE_F10: return "f10";
            case KeyEvent.KEYCODE_F11: return "f11";
            case KeyEvent.KEYCODE_F12: return "f12";
            default: return null;
        }
    }

    /**
     * A 端入口：機器人直連 BT／USB HID 的 KeyEvent（down＝pressed true）。
     * side 由調用方按 source 定（鍵盤類→kb，其餘→pad；見 MainActivity）。
     * servo jog 按住連走、放手停（見 start/stopServoRepeat）。
     */
    public boolean handleRobotKey(int keyCode, boolean pressed, int source, String side) {
        String norm = normalizeSide(side);
        String logical = keyCodeToLogical(keyCode);
        if (logical == null || !isKnownKey(norm, logical)) return false;
        // 放開：停連走＋僅記事件，不重複觸發動作（taken＝有無真停到連走）。
        if (!pressed) {
            boolean stopped = stopServoRepeat(norm, logical);
            publishKey(logical, false, "robot", mappedActionOf(norm, logical), stopped);
            return true;
        }
        String action = mappedActionOf(norm, logical);
        boolean taken = executeAction(norm, logical, action);
        // 撳住 servo jog 即排連走（首步上面已行，跟住每 120ms 一步；掣全速）。
        if (taken && isServoJog(action)) startServoRepeat(norm, logical, action, 1f);
        publishKey(logical, true, "robot", action, taken);
        return true;
    }

    /** A 端入口：機器人直連手柄搖桿（MotionEvent 拆出的 -1..1 四軸）。 */
    public void handleRobotAxis(float lx, float ly, float rx, float ry, String source) {
        applyAxis(lx, ly, rx, ry, source != null ? source : "robot");
    }

    // -- 觸控板轉頭（鍵盤一體觸控板專用；真滑鼠唔經呢度）-----------------------
    // 每設備記上次位＋累積量：px 級小郁先儲起，夠 150ms 節流窗即按比例郁頭
    // （dx→20 yaw，dy→頭 pitch，同右搖桿同向；死區 6px，120px 滿偏）。
    // kb 開關閘（touchpad 係鍵盤設備一部分）；事件 gamepad_touch 上 WS。
    private final Map<Integer, float[]> touchLastPos = new LinkedHashMap<>();
    private final Map<Integer, float[]> touchPending = new LinkedHashMap<>();
    private long lastTouchMs = 0;

    private static final float TOUCH_DEADZONE_PX = 6f;
    private static final float TOUCH_FULL_PX = 120f;

    /**
     * @param down true＝落指／新觸點（只記位唔郁）；false＝位移。
     * @return true＝郁咗頭（taken）。
     */
    public synchronized boolean handleRobotTouchpad(int deviceId, float x, float y, boolean down) {
        if (down || !touchLastPos.containsKey(deviceId)) {
            touchLastPos.put(deviceId, new float[]{x, y});
            touchPending.put(deviceId, new float[]{0f, 0f});
            return false;
        }
        float[] last = touchLastPos.get(deviceId);
        if (last == null) {
            touchLastPos.put(deviceId, new float[]{x, y});
            return false;
        }
        float dx = x - last[0];
        float dy = y - last[1];
        last[0] = x;
        last[1] = y;
        if (Math.abs(dx) + Math.abs(dy) < TOUCH_DEADZONE_PX) return false;
        float[] pend = touchPending.get(deviceId);
        if (pend == null) {
            pend = new float[]{0f, 0f};
            touchPending.put(deviceId, pend);
        }
        pend[0] += dx;
        pend[1] += dy;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTouchMs < AXIS_MIN_INTERVAL_MS) return false;
        float nx = Math.max(-1f, Math.min(1f, pend[0] / TOUCH_FULL_PX));
        float ny = Math.max(-1f, Math.min(1f, pend[1] / TOUCH_FULL_PX));
        pend[0] = 0f;
        pend[1] = 0f;
        lastTouchMs = now;
        if (Math.abs(nx) < 0.05f && Math.abs(ny) < 0.05f) return false;
        int base19 = ApiValidator.servoCalHome(19);
        int base20 = ApiValidator.servoCalHome(20);
        Integer cmd19 = commandedAngle(19);
        Integer cmd20 = commandedAngle(20);
        if (cmd19 != null) base19 = cmd19;
        if (cmd20 != null) base20 = cmd20;
        if (axisPose19 != null) base19 = axisPose19;
        if (axisPose20 != null) base20 = axisPose20;
        int target19 = ApiValidator.clampServoAngle(19, Math.round(base19 - ny * AXIS_FULL_STEP));
        int target20 = ApiValidator.clampServoAngle(20, Math.round(base20 + nx * AXIS_FULL_STEP));
        boolean moved = false;
        try {
            if (target19 != base19 && ubxApi.servoSendOneCode(19, target19, 150)
                    == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                axisPose19 = target19;
                moved = true;
            }
            if (target20 != base20 && ubxApi.servoSendOneCode(20, target20, 150)
                    == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                axisPose20 = target20;
                moved = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "touchpad servo failed", t);
        }
        try {
            EventBus.get().publish("gamepad_touch", "{\"dx\":" + Math.round(dx)
                    + ",\"dy\":" + Math.round(dy)
                    + ",\"taken\":" + moved + ",\"source\":\"robot\"}");
        } catch (Throwable t) {
            Log.w(TAG, "publish touch failed", t);
        }
        return moved;
    }

    /**
     * 唯讀記錄（該邊開關熄咗嗰陣行呢度：記該邊映射動作但 taken:false，
     * 唔認得的記 unmapped＋raw code；吞唔吞由調用方定）。
     */
    public void recordOnlyRobotKey(int keyCode, boolean pressed, String side) {
        String norm = normalizeSide(side);
        String logical = keyCodeToLogical(keyCode);
        if (logical == null || !isKnownKey(norm, logical)) {
            handleRobotUnknown(keyCode, pressed, 0);
            return;
        }
        String action = mappedActionOf(norm, logical);
        if (action == null) action = "none";
        if (pressed) {
            lastKey = logical;
            lastAction = action;
            lastSource = "robot";
            lastPressed = true;
            lastTaken = false;
            lastAtMs = System.currentTimeMillis();
        }
        try {
            EventBus.get().publish("gamepad_key", "{\"key\":\"" + MainActivity.jsonSafe(logical) + "\""
                    + ",\"pressed\":" + pressed
                    + ",\"source\":\"robot\""
                    + ",\"action\":\"" + MainActivity.jsonSafe(action) + "\""
                    + ",\"taken\":false}");
        } catch (Throwable t) {
            Log.w(TAG, "recordOnly failed", t);
        }
    }

    /**
     * A 端診斷入口：外接手柄送來本中心唔認得的鍵（映射表無此鍵）。
     * 只記事件（key="unmapped"＋raw code），唔執行、唔吞——調用方繼續交回
     * super，等用家喺 Event Log 見到個 code 之後自行決定使唔使加映射。
     * 回 true 恆（表示「已記錄」，不代表已處理）。
     */
    public boolean handleRobotUnknown(int keyCode, boolean pressed, int source) {
        if (pressed) {
            lastKey = "unmapped";
            lastAction = "code:" + keyCode;
            lastSource = "robot";
            lastPressed = true;
            lastTaken = false;
            lastAtMs = System.currentTimeMillis();
        }
        try {
            EventBus.get().publish("gamepad_key", "{\"key\":\"unmapped\""
                    + ",\"code\":" + keyCode
                    + ",\"pressed\":" + pressed
                    + ",\"source\":\"robot\""
                    + ",\"action\":\"none\",\"taken\":false}");
        } catch (Throwable t) {
            Log.w(TAG, "publishUnknown failed", t);
        }
        return true;
    }

    private String mappedActionOf(String side, String logical) {
        try {
            String v = loadMapping(side).get(logical);
            return v != null ? v : "none";
        } catch (Throwable t) {
            return "none";
        }
    }

    /** toggle 類映射做咗即推新態（頁面掣即時跟，唔使入版重讀；只在成功嗰陣推）。 */
    private void publishToggleState(String json) {
        try {
            EventBus.get().publish("gamepad_state", json);
        } catch (Throwable t) {
            Log.w(TAG, "publish state failed", t);
        }
    }

    private void publishKey(String key, boolean pressed, String source, String action, boolean taken) {
        // 放開（pressed=false）恆 taken:false——唔入 last，免「最後按鍵」永遠停喺 ↑✗。
        // 事件照推（B 端 hold 邏輯／除錯用），顯示層（gamepadLastText＋WS hook）
        // 亦會跳過放開。
        if (pressed) {
            lastKey = key;
            lastAction = action != null ? action : "";
            lastSource = source != null ? source : "";
            lastPressed = true;
            lastTaken = taken;
            lastAtMs = System.currentTimeMillis();
        }
        // 放開事件用傳入值（唔用 last：上次撳可能係另一粒鍵，舊寫法會帶錯 source／action）。
        String es = source != null ? source : lastSource;
        String ea = action != null ? action : lastAction;
        try {
            EventBus.get().publish("gamepad_key", "{\"key\":\"" + MainActivity.jsonSafe(key) + "\""
                    + ",\"pressed\":" + pressed
                    + ",\"source\":\"" + MainActivity.jsonSafe(es) + "\""
                    + ",\"action\":\"" + MainActivity.jsonSafe(ea) + "\""
                    + ",\"taken\":" + taken + "}");
        } catch (Throwable t) {
            Log.w(TAG, "publishKey failed", t);
        }
    }

    // toggle 態（逐鍵：side:key→開緊；ring／led 用。關機／stop-all／對應
    // 全停清掉； natural 播完之類外面郁過要撳兩次先啱返位，認了）。
    private final Map<String, Boolean> toggleStates = new LinkedHashMap<>();

    private synchronized boolean toggleFlip(String side, String key) {
        String tag = side + ":" + key;
        boolean now = !Boolean.TRUE.equals(toggleStates.get(tag));
        if (now) toggleStates.put(tag, Boolean.TRUE);
        else toggleStates.remove(tag);
        return now;
    }

    private synchronized void toggleClear() {
        toggleStates.clear();
    }

    /** 執行單個映射動作（按鍵單發＋axis jog 共用）。回 true＝已處理。 */
    private boolean executeAction(String side, String key, String action) {
        if (action == null) return false;
        String a = action.trim();
        try {
            if (a.equals("none") || a.isEmpty()) return true;
            // 全部停止（同頭頂雙 pad／小智全部停止同語義，另加鈴聲＋三燈）：
            // 動作（＋蹲下站起回位）、TTS、本地音樂、電台、鈴聲、頭／眼／咀燈。
            // 各路獨立 try（邊路炸唔阻其他路），taken 跟動作主體。
            if (a.equals("stop")) {
                UbxErrorCode.API_ERROR_CODE code = actionDirect.stopActionWithRecovery();
                noteActionStarted();
                try {
                    if (speechCenter != null) speechCenter.stopAllSpeechPlayback();
                } catch (Throwable t) {
                    Log.w(TAG, "stopAll speech failed", t);
                }
                try {
                    if (audioCenter != null) {
                        audioCenter.stopLocalMusicPlayback();
                        audioCenter.stopRadioPlayback();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "stopAll audio failed", t);
                }
                try {
                    if (ringtoneCenter != null) ringtoneCenter.stopRingtonePlayback();
                } catch (Throwable t) {
                    Log.w(TAG, "stopAll ringtone failed", t);
                }
                try {
                    allLedsOff();
                } catch (Throwable t) {
                    Log.w(TAG, "stopAll leds failed", t);
                }
                // 全停清 toggle 態（ring／led 下次撳由開做起）。
                toggleClear();
                return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
            }
            if (a.equals("vol_up") || a.equals("vol_down")) {
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            a.equals("vol_up") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI);
                }
                return true;
            }
            if (isServoJog(a)) {
                int id = Integer.parseInt(a.substring(5, a.length() - 1));
                int delta = a.endsWith("+") ? SERVO_JOG_STEP : -SERVO_JOG_STEP;
                return jogServo(id, delta);
            }
            // 本地音樂功能鍵（無歌／無載入即 false，taken 照實）。
            if (isMusicAction(a)) {
                if (audioCenter == null) return false;
                if (a.equals("music_play")) return audioCenter.playFirstLocalMusic() != null;
                if (a.equals("music_next")) return audioCenter.playNextLocalMusic() != null;
                if (a.equals("music_prev")) return audioCenter.playPrevLocalMusic() != null;
                if (a.equals("music_pause")) return audioCenter.pauseLocalMusicNow();
                if (a.equals("music_resume")) return audioCenter.resumeLocalMusicNow();
                if (a.equals("music_toggle")) {
                    try {
                        if (audioCenter.isLocalMusicPlayingNow()) {
                            return audioCenter.pauseLocalMusicNow();
                        }
                        if (!audioCenter.resumeLocalMusicNow()) {
                            return audioCenter.playFirstLocalMusic() != null;
                        }
                        return true;
                    } catch (Throwable t) {
                        Log.w(TAG, "music_toggle failed", t);
                        return false;
                    }
                }
                if (a.equals("music_stop")) {
                    try {
                        audioCenter.stopLocalMusicPlayback();
                        return true;
                    } catch (Throwable t) {
                        Log.w(TAG, "music_stop failed", t);
                        return false;
                    }
                }
                if (a.equals("music_random")) {
                    try {
                        return audioCenter.playRandomLocalMusic() != null;
                    } catch (Throwable t) {
                        Log.w(TAG, "music_random failed", t);
                        return false;
                    }
                }
                if (a.equals("radio_toggle")) {
                    try {
                        return audioCenter.toggleRadio();
                    } catch (Throwable t) {
                        Log.w(TAG, "radio_toggle failed", t);
                        return false;
                    }
                }
                if (a.equals("disco_toggle")) {
                    try {
                        if (audioCenter == null) return false;
                        boolean ok = audioCenter.toggleDisco();
                        if (ok) {
                            publishToggleState("{\"domain\":\"disco\",\"enabled\":"
                                    + audioCenter.isDiscoEnabled() + "}");
                        }
                        return ok;
                    } catch (Throwable t) {
                        Log.w(TAG, "disco_toggle failed", t);
                        return false;
                    }
                }
                if (a.equals("filler_toggle")) {
                    try {
                        if (audioCenter == null) return false;
                        boolean ok = audioCenter.toggleFillerAction();
                        if (ok) {
                            publishToggleState("{\"domain\":\"filler\",\"enabled\":"
                                    + audioCenter.isMusicFillerActionEnabled() + "}");
                        }
                        return ok;
                    } catch (Throwable t) {
                        Log.w(TAG, "filler_toggle failed", t);
                        return false;
                    }
                }
                return false;
            }
            // 系統鈴聲 toggle（逐鍵：撳一下播，再撳一下停；ring_stop 直停＋清態；
            // ring_random 唔 toggle：每次撳都播首新嘅）。
            if (isRingAction(a)) {
                if (ringtoneCenter == null) return false;
                try {
                    if ("ring_stop".equals(a)) {
                        ringtoneCenter.stopRingtonePlayback();
                        toggleClear();
                        return true;
                    }
                    if ("ring_random".equals(a)) {
                        return ringtoneCenter.playRandomRingtoneNow();
                    }
                    if (toggleFlip(side, key)) {
                        if ("ring_phone".equals(a)) {
                            return ringtoneCenter.playDefaultPhoneRingtoneNow();
                        }
                        if ("ring_random".equals(a)) {
                            return ringtoneCenter.playRandomRingtoneNow();
                        }
                        // ring:phone:／ring:notification: 分表搵，舊式 ring: 全表搵。
                        return ringtoneCenter.playRingtoneNow(ringTypeOf(a), ringTitleOf(a));
                    }
                    ringtoneCenter.stopRingtonePlayback();
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "ring action failed: " + a, t);
                    return false;
                }
            }
            // 三燈全熄（同 stop-all 入面嗰套）＋清 led toggle 態。
            if ("led_off".equals(a)) {
                try {
                    allLedsOff();
                    toggleClear();
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "led_off failed", t);
                    return false;
                }
            }
            // 相機：影相直行；人臉／顏色追蹤讀真態 toggle（開緊即停，停緊即開，
            // 兩邊互斥由 CameraApi 保證）；錄影 toggle（錄緊即停，閒置即錄）。
            if (isCameraAction(a)) {
                if (cameraApi == null) return false;
                try {
                    java.util.Map<String, String> empty = new java.util.HashMap<>();
                    HttpServer.ApiResponse resp;
                    if ("camera_photo".equals(a)) {
                        // takePicture 前後幾秒等（AE 收斂＋AF），main thread 經映射撳鍵會
                        // ANR 死（同 vosk switch 一例，見 traces）：放背景做，回 true＝已接受。
                        // 成品落 DCIM，失敗 Log＋唔炸調用方。
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    java.util.Map<String, String> q =
                                            new java.util.HashMap<String, String>();
                                    cameraApi.takePhotoSave(q);
                                } catch (Throwable t) {
                                    Log.w(TAG, "async camera_photo failed", t);
                                }
                            }
                        }, "GamepadPhoto").start();
                        return true;
                    } else if ("face_toggle".equals(a) || "face_start".equals(a)) {
                        resp = cameraApi.isFaceTrackRunning()
                                ? cameraApi.faceTrackStop() : cameraApi.faceTrackStart(empty);
                        // 讀真態推（開人臉即停顏色，CameraApi 保證互斥；HTTP 相機頁經
                        // gamepad_state 即跟，唔使等輪詢）。
                        publishToggleState("{\"domain\":\"facetrack\",\"running\":"
                                + cameraApi.isFaceTrackRunning() + "}");
                        publishToggleState("{\"domain\":\"colortrack\",\"running\":"
                                + cameraApi.isColorTrackRunning() + "}");
                    } else if ("color_toggle".equals(a) || "color_start".equals(a)) {
                        resp = cameraApi.isColorTrackRunning()
                                ? cameraApi.colorTrackStop() : cameraApi.colorTrackStart(empty);
                        publishToggleState("{\"domain\":\"colortrack\",\"running\":"
                                + cameraApi.isColorTrackRunning() + "}");
                        publishToggleState("{\"domain\":\"facetrack\",\"running\":"
                                + cameraApi.isFaceTrackRunning() + "}");
                    } else if ("video_toggle".equals(a)) {
                        return cameraApi.toggleVideoRecording();
                    } else return false;
                    return resp != null && resp.status == 200
                            && resp.body != null && resp.body.contains("\"ok\":true");
                } catch (Throwable t) {
                    Log.w(TAG, "camera action failed: " + a, t);
                    return false;
                }
            }
            // 傳感器 toggle（讀真態翻轉；taken 照實；做咗即推新態畀頁面掣跟）。
            if (isSensorAction(a)) {
                try {
                    if ("sonar_toggle".equals(a)) {
                        if (sonarCenter == null) return false;
                        boolean ok = sonarCenter.toggleSonar();
                        if (ok) {
                            publishToggleState("{\"domain\":\"sonar\",\"thresholdCm\":"
                                    + sonarCenter.getSonarThreshold() + "}");
                        }
                        return ok;
                    }
                    if ("pir_toggle".equals(a)) {
                        if (ledCenter == null) return false;
                        boolean ok = ledCenter.togglePir();
                        if (ok) {
                            publishToggleState("{\"domain\":\"pir\",\"enabled\":"
                                    + ledCenter.isPirEnabled() + "}");
                        }
                        return ok;
                    }
                    if ("accel_toggle".equals(a)) {
                        if (deviceStatus == null) return false;
                        // toggleAccelerometer 主線程係 post 非同步翻（等 latch 即自塞死，
                        // 見 DeviceStatus）：即時讀返係舊態，HTTP 掣永遠慢一拍——推預測態
                        //（同翻轉一對一；硬件唔在 ok=false 唔推）。
                        boolean target = !deviceStatus.isAccelerometerEnabled();
                        boolean ok = deviceStatus.toggleAccelerometer();
                        if (ok) {
                            publishToggleState("{\"domain\":\"accel\",\"enabled\":" + target + "}");
                        }
                        return ok;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sensor action failed: " + a, t);
                    return false;
                }
                return false;
            }
            // 語音 toggle（小智連線／Vosk 監聽／Vosk 切包；taken 照實）。
            if (isVoiceAction(a)) {
                try {
                    if ("xiaozhi_toggle".equals(a)) {
                        return xiaozhiBridge != null && xiaozhiBridge.toggleConnection();
                    }
                    if ("vosk_toggle".equals(a)) {
                        return voskApi != null && voskApi.toggleListening();
                    }
                    if (a.startsWith("vosk:")) {
                        if (voskApi == null) return false;
                        return voskApi.switchModel(a.substring(5).trim());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "voice action failed: " + a, t);
                    return false;
                }
                return false;
            }
            // EQ preset toggle（已係該 preset 即返 normal；taken 照實）。
            if (isEqAction(a)) {
                try {
                    if (audioCenter == null) return false;
                    return audioCenter.toggleEqPreset(a.substring(3).trim());
                } catch (Throwable t) {
                    Log.w(TAG, "eq action failed: " + a, t);
                    return false;
                }
            }
            // 收藏電台（按 uuid／名搵；taken 照實）。
            if (isRadioFavAction(a)) {
                try {
                    if (audioCenter == null) return false;
                    return audioCenter.playFavoriteNow(a.substring(6).trim());
                } catch (Throwable t) {
                    Log.w(TAG, "radio fav failed: " + a, t);
                    return false;
                }
            }
            // 頭／眼／咀 LED toggle（逐鍵：撳一下著效果，再撳一下熄嗰部位；
            // stop/off 值直行唔 flip；random 都唔 flip：每次撳都即抽新嘅）。
            if (isLedAction(a)) {
                if (ledCenter == null) return false;
                try {
                    String[] parts = a.split(":", -1);
                    String which = parts[0];
                    String preset = parts[1];
                    String effColor = null;
                    // random 唔經 toggle（每次撳都即抽新嘅，唔會撳多下熄燈）。
                    boolean noFlip = "random".equals(preset);
                    // random：每次撳即場抽（效果去 stop，dual 色跟 3/4/5；唔經 flip）。
                    if ("random".equals(preset)) {
                        String[] pool = "head".equals(which) ? HEAD_PRESETS : EYE_PRESETS;
                        java.util.List<String> opts = new java.util.ArrayList<String>();
                        for (String p : pool) {
                            if (!"stop".equals(p)) opts.add(p);
                        }
                        if (opts.isEmpty()) return false;
                        java.util.Random rnd = new java.util.Random();
                        preset = opts.get(rnd.nextInt(opts.size()));
                        // dual 得 3 對色（3=藍綠／4=紅綠／5=紅藍，見 app-led.js DUAL_COLORS），其餘 7 色。
                        int maxColor = "dual".equals(preset) ? 3 : 7;
                        effColor = String.valueOf(("dual".equals(preset) ? 3 : 1)
                                + rnd.nextInt(maxColor));
                    }
                    boolean stopping = "stop".equals(preset) || "off".equals(preset);
                    if (!stopping && !noFlip && !toggleFlip(side, key)) {
                        java.util.Map<String, String> qoff = new java.util.HashMap<>();
                        HttpServer.ApiResponse respOff;
                        if ("mouth".equals(which)) {
                            qoff.put("preset", "off");
                            respOff = ledCenter.ledMouthSet(qoff);
                        } else {
                            qoff.put("preset", "stop");
                            respOff = "head".equals(which)
                                    ? ledCenter.ledHeadSet(qoff) : ledCenter.ledEyeSet(qoff);
                        }
                        return respOff != null && respOff.status == 200
                                && respOff.body != null && respOff.body.contains("\"ok\":true");
                    }
                    java.util.Map<String, String> q = new java.util.HashMap<>();
                    HttpServer.ApiResponse resp;
                    if ("mouth".equals(which)) {
                        // 咀只有 breathing＋off 兩式（見 isLedAction）。
                        q.put("preset", "off".equals(preset) ? "off" : "breathing");
                        if (!"off".equals(preset)) q.put("speed", parts[2].trim());
                        resp = ledCenter.ledMouthSet(q);
                    } else {
                        boolean head = "head".equals(which);
                        q.put("preset", preset);
                        if (!"stop".equals(preset)) {
                            q.put("color", effColor != null ? effColor : parts[2].trim());
                            q.put("brightness", "9");
                        }
                        resp = head ? ledCenter.ledHeadSet(q) : ledCenter.ledEyeSet(q);
                    }
                    return resp != null && resp.status == 200
                            && resp.body != null && resp.body.contains("\"ok\":true");
                } catch (Throwable t) {
                    Log.w(TAG, "led action failed: " + a, t);
                    return false;
                }
            }
            if (a.startsWith("action:")) {
                String name = a.substring(7).trim();
                if (name.isEmpty()) return false;
                // 真返回值：缺文件／胸口唔通即 false，面板 taken 照實顯示。
                boolean ok = actionDirect.playActionDirect(name)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
                if (ok) noteActionStarted();
                return ok;
            }
        } catch (Throwable t) {
            Log.w(TAG, "executeAction failed: " + action, t);
            return false;
        }
        return false;
    }

    /** 三燈全熄（頭／眼 stop preset，咀 off；各路獨立吞錯）。 */
    private void allLedsOff() {
        if (ledCenter == null) return;
        try {
            java.util.Map<String, String> q = new java.util.HashMap<>();
            q.put("preset", "stop");
            try {
                ledCenter.ledHeadSet(q);
            } catch (Throwable t) {
                Log.w(TAG, "allLedsOff head failed", t);
            }
            try {
                ledCenter.ledEyeSet(q);
            } catch (Throwable t) {
                Log.w(TAG, "allLedsOff eye failed", t);
            }
            try {
                java.util.Map<String, String> qm = new java.util.HashMap<>();
                qm.put("preset", "off");
                ledCenter.ledMouthSet(qm);
            } catch (Throwable t) {
                Log.w(TAG, "allLedsOff mouth failed", t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "allLedsOff failed", t);
        }
    }

    // -- 舵機長按連走（撳住郁、放手停；0-255 clamp，唔會超）--------------------
    // 按下首步由 executeAction 即行，呢度排之後每 120ms 一步；放手 cancel。
    // key＝side:key，兩邊同名鍵各行各（各停各）。
    private final java.util.concurrent.ScheduledExecutorService servoRepeatPool =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> servoRepeaters =
            new LinkedHashMap<>();
    // 連走偏轉量（tag→0..1；掣 1.0 全速，搖桿跟手指；repeat tick 即時讀，見下）。
    private final Map<String, Float> servoRepeatDefl = new LinkedHashMap<>();
    private static final long SERVO_REPEAT_MS = 120;
    // 連走封頂 40 格（約 5 秒）：放開事件丟失（斷網／USB 跌）都唔會無限走，
    // 同 GestureCenter 音量連發 20 格封頂同例。
    private static final int SERVO_REPEAT_MAX_TICKS = 40;

    /** 撳住連走（defl 偏轉 0..1：掣傳 1.0 全速，搖桿傳當刻偏轉跟速）。 */
    private synchronized void startServoRepeat(String side, String key, String action, float defl) {
        stopServoRepeat(side, key);
        final int id;
        final int baseDelta;
        try {
            id = Integer.parseInt(action.substring(5, action.length() - 1));
            baseDelta = action.endsWith("+") ? SERVO_JOG_STEP : -SERVO_JOG_STEP;
        } catch (Throwable t) {
            Log.w(TAG, "startServoRepeat parse failed: " + action, t);
            return;
        }
        try {
            final String tag = side + ":" + key;
            final String fside = side;
            final String fkey = key;
            servoRepeatDefl.put(tag, Math.max(0f, Math.min(1f, defl)));
            final int[] ticks = new int[1];
            final int[] stillFails = new int[1];
            java.util.concurrent.ScheduledFuture<?> f = servoRepeatPool.scheduleAtFixedRate(
                    new Runnable() {
                        @Override public void run() {
                            if (++ticks[0] > SERVO_REPEAT_MAX_TICKS) {
                                stopServoRepeat(fside, fkey);
                                return;
                            }
                            // 偏轉跟速：步長按當刻偏轉量縮放（半推慢行，推盡全速 8°）。
                            int step = Math.max(1, Math.round(SERVO_JOG_STEP * repeatDeflOf(fside, fkey)));
                            int delta = baseDelta > 0 ? step : -step;
                            // 到頂／送唔出連續 3 次即自己停（唔再頂住限位嗌）。
                            if (jogServo(id, delta)) {
                                stillFails[0] = 0;
                            } else if (++stillFails[0] >= 3) {
                                Log.i(TAG, "servo repeat auto-stop at limit: " + tag);
                                stopServoRepeat(fside, fkey);
                            }
                        }
                    }, SERVO_REPEAT_MS, SERVO_REPEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            servoRepeaters.put(tag, f);
        } catch (Throwable t) {
            Log.w(TAG, "startServoRepeat failed", t);
        }
    }

    private synchronized float repeatDeflOf(String side, String key) {
        Float fd = servoRepeatDefl.get(side + ":" + key);
        return fd == null ? 1f : fd;
    }

    /** 搖桿偏轉跟速（連走緊先有用；無排緊即 no-op，唔留垃圾）。 */
    private synchronized void updateServoRepeatDefl(String side, String key, float defl) {
        if (servoRepeaters.containsKey(side + ":" + key)) {
            servoRepeatDefl.put(side + ":" + key, Math.max(0f, Math.min(1f, defl)));
        }
    }

    /** 停該鍵連走；回 true＝之前真有排緊（release 的 taken 照呢個實數）。 */
    private synchronized boolean stopServoRepeat(String side, String key) {
        try {
            java.util.concurrent.ScheduledFuture<?> f =
                    servoRepeaters.remove(side + ":" + key);
            servoRepeatDefl.remove(side + ":" + key);
            if (f != null) {
                f.cancel(false);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopServoRepeat failed", t);
        }
        return false;
    }

    /**
     * 單軸 jog：以目前命令位姿為基（未知則校準原點起），校準 clamp（見
     * ApiValidator，同 servo tab slider 同一套，唔會頂過機械限位）；
     * 已到頂（target==base）即唔送幀回 false，等連走自己停。
     */
    /** 命令位姿逐軸讀（poseBoxed 逐粒 known，比全量 pose() 可靠得多；
     *  servo/one 郁過即有數，唔使等全量發送）。無即 null。 */
    private Integer commandedAngle(int id) {
        try {
            Integer[] boxed = ubxPlayer.poseBoxed();
            if (boxed != null && id >= 1 && id <= boxed.length) return boxed[id - 1];
        } catch (Throwable ignored) {
        }
        return null;
    }

    private synchronized boolean jogServo(int id, int delta) {
        int base = ApiValidator.servoCalHome(id);
        Integer commanded = commandedAngle(id);
        if (commanded != null) {
            base = commanded;
        } else if (id == 19 && axisPose19 != null) {
            base = axisPose19;
        } else if (id == 20 && axisPose20 != null) {
            base = axisPose20;
        }
        int target = ApiValidator.clampServoAngle(id, base + delta);
        if (target == base) return false;
        boolean sent = false;
        try {
            sent = ubxApi.servoSendOneCode(id, target, SERVO_JOG_TIME_MS)
                    == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (Throwable t) {
            Log.w(TAG, "jogServo failed", t);
        }
        if (sent) {
            if (id == 19) axisPose19 = target;
            if (id == 20) axisPose20 = target;
        }
        return sent;
    }

    // -- HTTP API（管理 UI＋脚本直調經這裡；A 端物理鍵走上面 handleRobotKey）------

    public HttpServer.ApiResponse statusResponse() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        // 藍牙現狀（同 bt/status 一套讀法，這裡內聯免多一次 HTTP）。
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            boolean avail = adapter != null;
            boolean enabled = avail && adapter.isEnabled();
            String name = avail && adapter.getName() != null ? adapter.getName() : "";
            sb.append("\"bt\":{\"available\":").append(avail)
                    .append(",\"enabled\":").append(enabled)
                    .append(",\"name\":\"").append(MainActivity.jsonSafe(name)).append("\"},");
        } catch (Exception e) {
            sb.append("\"bt\":{\"available\":false,\"enabled\":false,\"name\":\"\"},");
        }
        // 已接輸入設備（A 端直連的手柄／鍵盤在這裡見到）。
        sb.append("\"devices\":[");
        try {
            int[] ids = InputDevice.getDeviceIds();
            boolean first = true;
            for (int id : ids) {
                InputDevice dev = null;
                try {
                    dev = InputDevice.getDevice(id);
                } catch (Throwable ignored) {
                }
                if (dev == null) continue;
                int src = 0;
                try {
                    src = dev.getSources();
                } catch (Throwable ignored) {
                }
                boolean gamepad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                boolean keyboard = (src & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                        || (src & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
                if (!gamepad && !keyboard) continue;
                String dname = "";
                try {
                    dname = dev.getName() != null ? dev.getName() : "";
                } catch (Throwable ignored) {
                }
                // 機身自帶唔上版：Virtual（框架虛擬鍵盤）、rk29-keypad（頭頂
                // pad）、C-Media（機身 USB 聲卡自帶音量 HID）——用家只想睇自己
                // 接的手柄／鍵盤。
                if ("Virtual".equals(dname) || dname.contains("rk29-keypad")
                        || dname.startsWith("C-Media")) {
                    continue;
                }
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(id)
                        .append(",\"name\":\"").append(MainActivity.jsonSafe(dname)).append("\"")
                        .append(",\"gamepad\":").append(gamepad)
                        .append(",\"keyboard\":").append(keyboard).append("}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "status devices failed", t);
        }
        sb.append("],");
        // 兩邊映射（pad／kb 獨立）＋最後事件。
        sb.append("\"mapping\":{\"pad\":").append(mappingJson(loadMapping(SIDE_PAD)));
        sb.append(",\"kb\":").append(mappingJson(loadMapping(SIDE_KB))).append("},");
        sb.append("\"last\":{\"key\":\"").append(MainActivity.jsonSafe(lastKey)).append("\"")
                .append(",\"action\":\"").append(MainActivity.jsonSafe(lastAction)).append("\"")
                .append(",\"source\":\"").append(MainActivity.jsonSafe(lastSource)).append("\"")
                .append(",\"pressed\":").append(lastPressed)
                .append(",\"taken\":").append(lastTaken)
                .append(",\"atMs\":").append(lastAtMs).append("}}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /** 平坦映射 JSON（{"k":"v",...}，供 status/config 共用）。 */
    private static String mappingJson(Map<String, String> mapping) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    public HttpServer.ApiResponse configGetResponse(Map<String, String> query) {
        String side = normalizeSide(query.get("side"));
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"side\":\"" + side
                + "\",\"mapping\":" + mappingJson(loadMapping(side)) + "}");
    }

    /** gamepad/config/set：side＋key＋action 單鍵改（reset=1 回該邊預設）。 */
    public HttpServer.ApiResponse configSetResponse(Map<String, String> query) {
        String side = normalizeSide(query.get("side"));
        String reset = query.get("reset");
        if ("1".equals(reset) || "true".equalsIgnoreCase(reset)) {
            saveMapping(side, defaultSideMapping(side));
            if (SIDE_PAD.equals(side)) {
                invalidatePadCache();
                synchronized (this) {
                    stickActive.clear();
                }
            }
            return configGetResponse(query);
        }
        String key = query.get("key");
        String action = query.get("action");
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: key");
        }
        if (action == null) {
            throw new IllegalArgumentException("missing required parameter: action");
        }
        key = key.trim();
        action = action.trim();
        if (!isKnownKey(side, key)) {
            String[] all = SIDE_KB.equals(side) ? KB_KEYS : PAD_KEYS;
            throw new IllegalArgumentException("parameter 'key' (side=" + side + ") must be one of "
                    + java.util.Arrays.toString(all) + ", got: " + key);
        }
        if (!isValidAction(action)) {
            throw new IllegalArgumentException("parameter 'action' must be none/stop/vol_up/vol_down/"
                    + "music_play/music_next/music_prev/music_pause/music_resume/music_toggle/music_stop/music_random/radio_toggle/disco_toggle/filler_toggle/"
                    + "ring_phone/ring_stop/ring_random/ring:<title>/ring:phone:<title>/ring:notification:<title>/"
                    + "head:<preset>:<color>/head:random/eye:<preset>:<color>/eye:random/"
                    + "mouth:breathing:<speedMs>/mouth:off/led_off/"
                    + "camera_photo/face_toggle/color_toggle/video_toggle/"
                    + "sonar_toggle/pir_toggle/accel_toggle/"
                    + "xiaozhi_toggle/vosk_toggle/vosk:<id>/eq:<preset>/radio:<uuid或名>/"
                    + "servo1-22+/servo1-22-/action:<id>, got: " + action);
        }
        Map<String, String> mapping = loadMapping(side);
        mapping.put(key, action);
        saveMapping(side, mapping);
        if (SIDE_PAD.equals(side)) {
            invalidatePadCache();
            // 搖桿沿態一併清：新映射即時生效，唔使等舊回中先重觸發；
            // 兼治併發流量頂住 wasActive 令沿永遠撞唔正。
            synchronized (this) {
                stickActive.clear();
            }
        }
        return configGetResponse(query);
    }

    /** gamepad/key：注入按鍵（side 唔帶就按 source 猜：keyboard→kb，其餘 pad）。 */
    public HttpServer.ApiResponse keyResponse(Map<String, String> query) {
        String key = ApiValidator.require(query, "key");
        key = key.trim();
        // 兼容 Android 數字鍵碼直送（部分自研前端會送 19/20/…）：先轉邏輯名。
        if (key.matches("-?\\d+")) {
            try {
                String logical = keyCodeToLogical(Integer.parseInt(key));
                if (logical != null) key = logical;
            } catch (NumberFormatException ignored) {
            }
        }
        String source = query.get("source");
        if (source == null || source.trim().isEmpty()) source = "browser";
        source = source.trim();
        if (source.length() > 32) source = source.substring(0, 32);
        String side = query.get("side");
        if (side == null || side.trim().isEmpty()) {
            String sl = source.toLowerCase(java.util.Locale.US);
            side = sl.contains("keyboard") ? SIDE_KB : SIDE_PAD;
        }
        side = normalizeSide(side);
        if (!isKnownKey(side, key)) {
            String[] all = SIDE_KB.equals(side) ? KB_KEYS : PAD_KEYS;
            throw new IllegalArgumentException("parameter 'key' (side=" + side + ") must be one of "
                    + java.util.Arrays.toString(all) + ", got: " + key);
        }
        boolean pressed = ApiValidator.optionalBoolean(query, "pressed", true);
        // action=down/up 別名（同 pressed 二選一，action 優先）。
        String act = query.get("action");
        if (act != null && !act.trim().isEmpty()) {
            String al = act.trim().toLowerCase(java.util.Locale.US);
            if (al.equals("down")) pressed = true;
            else if (al.equals("up")) pressed = false;
            else throw new IllegalArgumentException("parameter 'action' must be one of [down, up], got: " + act);
        }
        String mapped = mappedActionOf(side, key);
        boolean taken = false;
        // 放開：停連走＋不上動作（避免按住連發播兩次），但事件照記、前端燈照跟。
        if (pressed) {
            taken = executeAction(side, key, mapped);
            if (taken && isServoJog(mapped)) startServoRepeat(side, key, mapped, 1f);
        } else {
            taken = stopServoRepeat(side, key);
        }
        publishKey(key, pressed, source, mapped, taken);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"side\":\"" + side + "\""
                + ",\"key\":\"" + MainActivity.jsonSafe(key) + "\""
                + ",\"pressed\":" + pressed
                + ",\"action\":\"" + MainActivity.jsonSafe(mapped) + "\""
                + ",\"taken\":" + taken + "}");
    }

    /** gamepad/axis：搖桿連續量（-1..1，死區＋節流，驅頭 19/20）。 */
    public HttpServer.ApiResponse axisResponse(Map<String, String> query) {
        float lx = ApiValidator.optionalFloat(query, "lx", 0f);
        float ly = ApiValidator.optionalFloat(query, "ly", 0f);
        float rx = ApiValidator.optionalFloat(query, "rx", 0f);
        float ry = ApiValidator.optionalFloat(query, "ry", 0f);
        for (float v : new float[]{lx, ly, rx, ry}) {
            if (!(v >= -1.0f && v <= 1.0f)) {
                throw new IllegalArgumentException("axis must be between -1 and 1");
            }
        }
        String source = query.get("source");
        if (source == null || source.trim().isEmpty()) source = "browser";
        source = source.trim();
        if (source.length() > 32) source = source.substring(0, 32);
        boolean moved = applyAxis(lx, ly, rx, ry, source);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"moved\":" + moved + "}");
    }

    /** 搖桿方向映射觸發閾值（死區 0.15 以上、過半先當撳落去，免誤觸）。 */
    private static final float STICK_MAP_THRESHOLD = 0.5f;

    /** 搖桿方向沿態（dirKey→撳緊；回中即刪，下次再出先重發）。 */
    private final Map<String, Boolean> stickActive = new LinkedHashMap<>();

    // -- 搖桿回中（郁過嘅舵機返動作尾位）----------------------------------------
    // 回中位＝最後播完嘅動作尾姿（poseBoxed merge；未知軸唔郁，唔係一律 120）。
    // 世代閘：回中任務排嗰陣記 gen，之後郁過（新沿／legacy 郁）即舊任務作廢。
    private final Integer[] refPose = new Integer[20];
    private volatile boolean refPrevPlaying = false;
    private volatile java.util.concurrent.ScheduledFuture<?> refPollFuture;
    private final java.util.Set<Integer> legacyTouched = new java.util.LinkedHashSet<>();
    private long axisGen = 0;
    // 各軸今次最大偏轉（回中速度跟佢：慢去慢返快去快返；返完清）。
    private final java.util.Map<String, Float> axisLastDefl = new java.util.LinkedHashMap<>();

    /** pad 映射短緩存（axis 高頻，唔每幀 parse；config/set 即失效）。 */
    private Map<String, String> cachedPadMap;
    private long cachedPadMapAt = 0;

    private synchronized Map<String, String> padMappingCached() {
        // 單調鐘：部機 wall clock 會跳（NTP／RTC），currentTimeMillis 量間隔
        // 會死（跳返轉頭即永遠早退，見 axis throttle 誤殺回值）。
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedPadMap == null || now - cachedPadMapAt > 1000) {
            cachedPadMap = loadMapping(SIDE_PAD);
            cachedPadMapAt = now;
        }
        return cachedPadMap;
    }

    private synchronized void invalidatePadCache() {
        cachedPadMap = null;
    }

    private static boolean isMappedDir(Map<String, String> padMap, String dirKey) {
        if (padMap == null) return false;
        String v = padMap.get(dirKey);
        return v != null && !"none".equals(v);
    }

    // -- 動作尾位＋回中 helpers ------------------------------------------------
    /** 動作尾位常駐輕輪詢（250ms 睇 isPlaying 真→假；播完 400ms 後收尾）。 */
    private void startRefPosePoll() {
        try {
            refPollFuture = servoRepeatPool.scheduleAtFixedRate(new Runnable() {
                @Override public void run() { refPosePollTick(); }
            }, 1000, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "ref poll start failed", t);
        }
    }

    private void refPosePollTick() {
        try {
            boolean playing = false;
            try {
                playing = actionDirect != null && actionDirect.isPlaying();
            } catch (Throwable ignored) {
            }
            if (refPrevPlaying && !playing) {
                try {
                    servoRepeatPool.schedule(new Runnable() {
                        @Override public void run() { snapshotRefPose(); }
                    }, 400, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    snapshotRefPose();
                }
            }
            refPrevPlaying = playing;
        } catch (Throwable t) {
            Log.w(TAG, "ref poll tick failed", t);
        }
    }

    /** 動作尾姿收尾（播緊即重武裝等下次；只 merge 已知軸）。 */
    private void snapshotRefPose() {
        try {
            if (actionDirect != null) {
                boolean playing = false;
                try { playing = actionDirect.isPlaying(); } catch (Throwable ignored) { }
                if (playing) {
                    refPrevPlaying = true;
                    return;
                }
            }
            Integer[] boxed = null;
            try { boxed = ubxPlayer == null ? null : ubxPlayer.poseBoxed(); } catch (Throwable ignored) { }
            if (boxed == null) return;
            int n = 0;
            synchronized (this) {
                for (int i = 0; i < 20 && i < boxed.length; i++) {
                    if (boxed[i] != null) { refPose[i] = boxed[i]; n++; }
                }
            }
            Log.i(TAG, "ref pose snapshot: " + n + "/20 known");
        } catch (Throwable t) {
            Log.w(TAG, "ref snapshot failed", t);
        }
    }

    /** 手柄播／停動作起手即武裝（短動作唔夠一次 poll 都捉到）。 */
    public void noteActionStarted() {
        refPrevPlaying = true;
    }

    private synchronized long axisGenNow() { return axisGen; }

    private static String axisOfDir(String dirKey) {
        if (dirKey == null) return null;
        if (dirKey.startsWith("lx_")) return "lx";
        if (dirKey.startsWith("ly_")) return "ly";
        if (dirKey.startsWith("rx_")) return "rx";
        if (dirKey.startsWith("ry_")) return "ry";
        return null;
    }

    private boolean axisSettled(String axis) {
        if (axis == null) return false;
        synchronized (this) {
            return !Boolean.TRUE.equals(stickActive.get(axis + "_neg"))
                    && !Boolean.TRUE.equals(stickActive.get(axis + "_pos"));
        }
    }

    private static Integer servoIdOfAction(String a) {
        if (a == null) return null;
        String t = a.trim();
        if (!isServoJog(t)) return null;
        try {
            return Integer.parseInt(t.substring(5, t.length() - 1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 某軸回中：該軸配咗嘅 servo 掣返動作尾位（世代閘＋pool 非同步，唔塞掣事件）。
     *  legacy 19/20 唔經呢度（行下面全中分支，免打孖送）。 */
    private void maybeRecenterAxis(String dirKey, Map<String, String> padMap) {
        String axis = axisOfDir(dirKey);
        if (axis == null || !axisSettled(axis)) return;
        Integer id = null;
        try {
            String mapped = padMap != null ? padMap.get(dirKey) : null;
            id = servoIdOfAction(mapped);
        } catch (Throwable ignored) {
        }
        if (id == null) return;
        java.util.Map<String, java.util.List<Integer>> perAxis =
                new java.util.LinkedHashMap<>();
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        ids.add(id);
        perAxis.put(axis, ids);
        recenterAsync(axisGenNow(), perAxis);
    }

    private void recenterAsync(final long g,
            final java.util.Map<String, java.util.List<Integer>> perAxis) {
        final java.util.Map<String, Integer> steps = new java.util.LinkedHashMap<>();
        synchronized (this) {
            for (String axis : perAxis.keySet()) {
                Float d = axisLastDefl.remove(axis);
                float defl = d == null ? 1f : d;
                steps.put(axis, Math.max(1, Math.round(SERVO_JOG_STEP * defl)));
            }
        }
        try {
            servoRepeatPool.execute(new Runnable() {
                @Override public void run() {
                    if (g != axisGenNow()) return;
                    for (java.util.Map.Entry<String, java.util.List<Integer>> e : perAxis.entrySet()) {
                        Integer st = steps.get(e.getKey());
                        int step = st == null ? SERVO_JOG_STEP : st;
                        for (Integer id : e.getValue()) recenterServo(id, step);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "recenter post failed", t);
        }
    }

    /** 單軸返尾位（未知即唔郁；clamp；19/20 順手更 axisPose）。
     *  時間按去程速度折返（step/120ms）：慢去慢返，快去快返。 */
    private void recenterServo(int id, int step) {
        if (id < 1 || id > 20) return;
        Integer target;
        synchronized (this) {
            target = refPose[id - 1];
        }
        if (target == null) return;
        try {
            int clamped = ApiValidator.clampServoAngle(id, target);
            Integer cur = commandedAngle(id);
            if (cur == null && (id == 19 || id == 20)) {
                cur = id == 19 ? axisPose19 : axisPose20;
            }
            int timeMs = SERVO_JOG_TIME_MS;
            if (cur != null) {
                int dist = Math.abs(clamped - cur);
                if (dist == 0) return;
                double perSec = Math.max(1, step) / 0.12;
                timeMs = (int) Math.max(120, Math.min(5000, Math.round(dist / perSec * 1000)));
            }
            if (ubxApi.servoSendOneCode(id, clamped, timeMs)
                    == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                if (id == 19) axisPose19 = clamped;
                if (id == 20) axisPose20 = clamped;
                Log.i(TAG, "axis recenter #" + id + " -> " + clamped + " in " + timeMs + "ms");
            }
        } catch (Throwable t) {
            Log.w(TAG, "recenter failed #" + id, t);
        }
    }

    /** 軸偏轉 max 追蹤（郁緊先記；回零唔洗，recenter 嗰陣 consume）。 */
    private void updateAxisDefl(String axis, float v) {
        float m = Math.min(1f, Math.abs(v));
        if (m < AXIS_DEADZONE) return;
        Float old = axisLastDefl.get(axis);
        if (old == null || m > old) axisLastDefl.put(axis, m);
    }

    /**
     * 單柄向沿處理：出沿（未撳→撳過半）即行映射（servo 值撳住連走），
     * 回中即停＋記放開事件。回 true＝呢下有嘢做咗（taken）。
     */
    private boolean handleStickDir(String dirKey, boolean active,
            Map<String, String> padMap, String source, float defl) {
        boolean wasActive;
        synchronized (this) {
            wasActive = Boolean.TRUE.equals(stickActive.get(dirKey));
        }
        if (active && !wasActive) {
            synchronized (this) {
                stickActive.put(dirKey, Boolean.TRUE);
                axisGen++;
            }
            String mapped = padMap != null ? padMap.get(dirKey) : null;
            if (mapped == null || "none".equals(mapped)) return false;
            boolean taken = executeAction(SIDE_PAD, dirKey, mapped);
            if (taken && isServoJog(mapped)) startServoRepeat(SIDE_PAD, dirKey, mapped, defl);
            publishKey(dirKey, true, source, mapped, taken);
            return taken;
        }
        if (active && wasActive) {
            // 撳住唔放偏轉變：連走步長即時跟手指（半推即慢，見 repeat tick）。
            updateServoRepeatDefl(SIDE_PAD, dirKey, defl);
            return false;
        }
        if (!active && wasActive) {
            synchronized (this) {
                stickActive.remove(dirKey);
            }
            boolean stopped = stopServoRepeat(SIDE_PAD, dirKey);
            String mapped = padMap != null ? padMap.get(dirKey) : null;
            publishKey(dirKey, false, source, mapped != null ? mapped : "none", stopped);
            maybeRecenterAxis(dirKey, padMap);
            return stopped;
        }
        return false;
    }

    /**
     * 搖桿落盤（pad 邊；左右柄八向皆可映射）。
     * - 配咗的方向：出沿行映射（servo 值連走，回中停）；未配：右桿行返轉頭
     *   legacy（rx→20 yaw，ry→19 pitch），左桿唔郁。
     * - 成組四向全 none 即同以前一模一樣（legacy 全開）。
     * 節流內唔出聲唔驅動（沿觸發照即時推 key 事件）。回 true＝有驅動（legacy／映射任一）。
     */
    private synchronized boolean applyAxis(float lx, float ly, float rx, float ry, String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        Map<String, String> padMap = padMappingCached();
        updateAxisDefl("lx", lx);
        updateAxisDefl("ly", ly);
        updateAxisDefl("rx", rx);
        updateAxisDefl("ry", ry);
        boolean fired = false;
        fired |= handleStickDir("lx_neg", lx < -STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(lx)));
        fired |= handleStickDir("lx_pos", lx > STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(lx)));
        fired |= handleStickDir("ly_neg", ly < -STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(ly)));
        fired |= handleStickDir("ly_pos", ly > STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(ly)));
        fired |= handleStickDir("rx_neg", rx < -STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(rx)));
        fired |= handleStickDir("rx_pos", rx > STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(rx)));
        fired |= handleStickDir("ry_neg", ry < -STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(ry)));
        fired |= handleStickDir("ry_pos", ry > STICK_MAP_THRESHOLD, padMap, source, Math.min(1f, Math.abs(ry)));
        // legacy 轉頭：該軸兩向都未配先行（配咗就讓路，等連走／映射做）。
        float useRx = rx;
        float useRy = ry;
        if (isMappedDir(padMap, "rx_neg") || isMappedDir(padMap, "rx_pos")) useRx = 0f;
        if (isMappedDir(padMap, "ry_neg") || isMappedDir(padMap, "ry_pos")) useRy = 0f;
        float dzRx = Math.abs(useRx) < AXIS_DEADZONE ? 0f : useRx;
        float dzRy = Math.abs(useRy) < AXIS_DEADZONE ? 0f : useRy;
        if (dzRx != 0f || dzRy != 0f) {
            if (dzRx != 0f) legacyTouched.add(20);
            if (dzRy != 0f) legacyTouched.add(19);
            axisGen++;
        }
        // legacy 回中：全中＋無沿郁緊＋郁過→返尾位（唔經下面節流，
        // release 零位撞節流會 return false 跳過）。
        if (Math.abs(rx) < AXIS_DEADZONE && Math.abs(ry) < AXIS_DEADZONE
                && stickActive.isEmpty() && !legacyTouched.isEmpty()) {
            final long g = axisGen;
            java.util.Map<String, java.util.List<Integer>> perAxis =
                    new java.util.LinkedHashMap<>();
            if (legacyTouched.remove(Integer.valueOf(20))) {
                java.util.List<Integer> l = new java.util.ArrayList<>();
                l.add(20);
                perAxis.put("rx", l);
            }
            if (legacyTouched.remove(Integer.valueOf(19))) {
                java.util.List<Integer> l = new java.util.ArrayList<>();
                l.add(19);
                perAxis.put("ry", l);
            }
            if (!perAxis.isEmpty()) recenterAsync(g, perAxis);
        }
        if (now - lastAxisMs < AXIS_MIN_INTERVAL_MS) return false;
        lastAxisMs = now;
        // 沿事件上面已即時推；呢度節流後先推連續量（免高頻 Motion／脚本洗 WS＋Event Log）。
        try {
            EventBus.get().publish("gamepad_axis", "{\"lx\":" + lx + ",\"ly\":" + ly
                    + ",\"rx\":" + rx + ",\"ry\":" + ry
                    + ",\"source\":\"" + MainActivity.jsonSafe(source) + "\"}");
        } catch (Throwable t) {
            Log.w(TAG, "publish axis failed", t);
        }
        // 基位：上次 axis 位＞逐軸命令位姿＞校準原點；落盤校準 clamp。
        int base19 = ApiValidator.servoCalHome(19);
        int base20 = ApiValidator.servoCalHome(20);
        Integer cmd19 = commandedAngle(19);
        Integer cmd20 = commandedAngle(20);
        if (cmd19 != null) base19 = cmd19;
        if (cmd20 != null) base20 = cmd20;
        if (axisPose19 != null) base19 = axisPose19;
        if (axisPose20 != null) base20 = axisPose20;
        int target19 = ApiValidator.clampServoAngle(19, Math.round(base19 - dzRy * AXIS_FULL_STEP));
        int target20 = ApiValidator.clampServoAngle(20, Math.round(base20 + dzRx * AXIS_FULL_STEP));
        boolean moved = false;
        try {
            if (target19 != base19) {
                if (ubxApi.servoSendOneCode(19, target19, 150)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    axisPose19 = target19;
                    moved = true;
                }
            }
            if (target20 != base20) {
                if (ubxApi.servoSendOneCode(20, target20, 150)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    axisPose20 = target20;
                    moved = true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyAxis servo failed", t);
        }
        return moved || fired;
    }

    /** gamepad/bt：管理 UI 的藍牙開關（經 DeviceStatus 同一套 enable/disable）。 */
    public HttpServer.ApiResponse btResponse(Map<String, String> query) {
        boolean on = ApiValidator.requireBoolean(query, "on");
        boolean ok = deviceStatus != null && deviceStatus.setBluetoothEnabled(on);
        return HttpServer.ApiResponse.ok("{\"ok\":" + ok + ",\"enabled\":" + on + "}");
    }

    // -- BT 掃描／配對（A 端直連 HID 在 HTML 配對用；API 22 classic 足夠，
    // 使唔到 BLE location 嗰套——嗰個係 API 23+ 先要，呢部 5.1 無此要求）----

    /** 掃到的周邊（address→快照，掃一次清一次；bonded 另經 adapter 讀現狀）。 */
    private final Map<String, String> foundBtName = new LinkedHashMap<>();
    private final Map<String, Integer> foundBtBond = new LinkedHashMap<>();
    private final Map<String, String> foundBtKind = new LinkedHashMap<>();

    /**
     * HID 判別（掃描列表只留鍵盤／手柄；鼠標一律唔出，電腦／手錶之類唔上版）。
     * 主判 BluetoothClass：major 必須 PERIPHERAL(0x500)，minor 認 keyboard
     * (0x40，連鍵鼠一體都當鍵盤留)／joystick+gamepad(0x0C) 位；純 pointing
     * (0x80 無 0x40) 即掉；class 報 0／錯的後備名猜（keyboard/gamepad/joy/
     * pad/controller，避開 DESKTOP、ThinkPad 之類誤中；mouse/trackpad 名
     * 唔收）。回 null＝唔顯示。
     */
    private static String hidKind(android.bluetooth.BluetoothDevice dev) {
        String name = "";
        try {
            if (dev.getName() != null) name = dev.getName();
        } catch (Throwable ignored) {
        }
        try {
            android.bluetooth.BluetoothClass cls = dev.getBluetoothClass();
            if (cls != null) {
                int full = cls.getDeviceClass();
                int major = full & 0x1F00;
                int minor = full & 0x00FC;
                if (major == 0x0500) {
                    if ((minor & 0x40) != 0) return "keyboard";
                    if ((minor & 0x0C) != 0) return "gamepad";
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String nl = name.toLowerCase(java.util.Locale.US);
            if (nl.contains("keyboard") || nl.contains("keychron")
                    || nl.matches(".*[^a-z]kb[^a-z].*") || nl.equals("kb")) return "keyboard";
            if (nl.contains("gamepad") || nl.contains("controller")
                    || nl.contains("joystick") || nl.contains("joy-con")
                    || nl.contains("8bitdo")) return "gamepad";
            if (nl.matches(".*\\bpad\\b.*")) return "gamepad";
        } catch (Throwable ignored) {
        }
        return null;
    }
    private BroadcastReceiver btScanReceiver;
    private boolean btReceiverRegistered = false;

    /** onDestroy 共用：停舵機連走池＋反註冊掃描 receiver＋清觸控板暫存。 */
    public synchronized void shutdown() {
        try {
            for (java.util.concurrent.ScheduledFuture<?> f : servoRepeaters.values()) {
                try {
                    if (f != null) f.cancel(false);
                } catch (Throwable ignored) {
                }
            }
            servoRepeaters.clear();
            servoRepeatPool.shutdownNow();
        } catch (Throwable ignored) {
        }
        touchLastPos.clear();
        touchPending.clear();
        if (btReceiverRegistered && btScanReceiver != null) {
            try {
                appContext.unregisterReceiver(btScanReceiver);
            } catch (Throwable ignored) {
            }
            btReceiverRegistered = false;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isDiscovering()) {
                try {
                    adapter.cancelDiscovery();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        Object proxy = hidHostProxy;
        hidHostProxy = null;
        if (proxy instanceof android.bluetooth.BluetoothProfile) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                // HID_HOST=4（hidden 常量，直寫數字，見 connectResponse）。
                if (adapter != null) adapter.closeProfileProxy(4,
                        (android.bluetooth.BluetoothProfile) proxy);
            } catch (Throwable ignored) {
            }
        }
    }

    private synchronized void ensureBtReceiver() {
        if (btReceiverRegistered) return;
        btScanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                try {
                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        BluetoothDevice dev =
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (dev == null || dev.getAddress() == null) return;
                        // 非 HID（電腦／手錶……）唔入列表。
                        String kind = hidKind(dev);
                        if (kind == null) return;
                        String addr = dev.getAddress();
                        String name = dev.getName() != null ? dev.getName() : "";
                        synchronized (GamepadCenter.this) {
                            foundBtName.put(addr, name);
                            foundBtKind.put(addr, kind);
                            try {
                                foundBtBond.put(addr, dev.getBondState());
                            } catch (Throwable ignored) {
                            }
                        }
                        EventBus.get().publish("gamepad_bt",
                                "{\"event\":\"found\",\"address\":\""
                                + MainActivity.jsonSafe(addr) + "\",\"name\":\""
                                + MainActivity.jsonSafe(name) + "\",\"kind\":\"" + kind + "\"}");
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                        EventBus.get().publish("gamepad_bt", "{\"event\":\"scan_done\"}");
                    } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                        BluetoothDevice dev =
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (dev == null || dev.getAddress() == null) return;
                        int st = BluetoothDevice.BOND_NONE;
                        try {
                            st = dev.getBondState();
                        } catch (Throwable ignored) {
                        }
                        synchronized (GamepadCenter.this) {
                            foundBtBond.put(dev.getAddress(), st);
                        }
                        EventBus.get().publish("gamepad_bt",
                                "{\"event\":\"bond\",\"address\":\""
                                + MainActivity.jsonSafe(dev.getAddress()) + "\",\"bond\":\""
                                + bondName(st) + "\"}");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "bt receiver failed", t);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            appContext.registerReceiver(btScanReceiver, f);
            btReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "bt receiver register failed", t);
        }
    }

    private static String bondName(int st) {
        if (st == BluetoothDevice.BOND_BONDED) return "bonded";
        if (st == BluetoothDevice.BOND_BONDING) return "bonding";
        return "none";
    }

    private static boolean isMacAddress(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() != 17) return false;
        for (int i = 0; i < 17; i++) {
            char c = t.charAt(i);
            if (i % 3 == 2) {
                if (c != ':') return false;
            } else if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** gamepad/scan：開掃（冪等，掃緊即只回現狀）＋回已掃到＋已綁定兩張表。 */
    public HttpServer.ApiResponse scanResponse() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return HttpServer.ApiResponse.error("no bluetooth adapter");
        if (!adapter.isEnabled()) {
            return HttpServer.ApiResponse.error("bluetooth off, turn it on first (gamepad/bt?on=true)");
        }
        ensureBtReceiver();
        boolean already;
        synchronized (this) {
            already = adapter.isDiscovering();
            if (!already) {
                foundBtName.clear();
                foundBtBond.clear();
                foundBtKind.clear();
            }
        }
        boolean started = already;
        if (!already) {
            try {
                started = adapter.startDiscovery();
            } catch (SecurityException se) {
                return HttpServer.ApiResponse.error("bluetooth permission denied");
            } catch (Throwable t) {
                return HttpServer.ApiResponse.error(String.valueOf(t.getMessage()));
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"scanning\":");
        boolean scanningNow = false;
        try {
            scanningNow = adapter.isDiscovering();
        } catch (Throwable ignored) {
        }
        sb.append(scanningNow).append(",\"started\":").append(started).append(",\"found\":[");
        synchronized (this) {
            boolean first = true;
            for (Map.Entry<String, String> e : foundBtName.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                String addr = e.getKey();
                Integer bs = foundBtBond.get(addr);
                String kd = foundBtKind.get(addr);
                sb.append("{\"address\":\"").append(MainActivity.jsonSafe(addr)).append("\"")
                        .append(",\"name\":\"").append(MainActivity.jsonSafe(e.getValue())).append("\"")
                        .append(",\"kind\":\"").append(kd != null ? kd : "gamepad").append("\"")
                        .append(",\"bond\":\"").append(bs != null ? bondName(bs) : "none").append("\"}");
            }
        }
        sb.append("],\"bonded\":[");
        try {
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            boolean first = true;
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (d == null || d.getAddress() == null) continue;
                    // bonded 都一樣只留 HID。
                    String kd = hidKind(d);
                    if (kd == null) continue;
                    if (!first) sb.append(',');
                    first = false;
                    String nm = d.getName() != null ? d.getName() : "";
                    sb.append("{\"address\":\"").append(MainActivity.jsonSafe(d.getAddress())).append("\"")
                            .append(",\"name\":\"").append(MainActivity.jsonSafe(nm)).append("\"")
                            .append(",\"kind\":\"").append(kd).append("\"")
                            .append(",\"bond\":\"").append(bondName(d.getBondState())).append("\"}");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "bonded list failed", t);
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    /** HID 重連用 proxy 緩存（connect 完唔關，留住等系統自己掉線重連都仲識路）。 */
    private volatile Object hidHostProxy;

    /** 閂多餘 HID proxy（併發 connect 輸嗰個用；shutdown 嗰套保留原樣）。 */
    private static void closeHidHostProxy(Object proxy) {
        if (!(proxy instanceof android.bluetooth.BluetoothProfile)) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            // HID_HOST=4（hidden 常量，直寫數字，見 connectResponse）。
            if (adapter != null) adapter.closeProfileProxy(4,
                    (android.bluetooth.BluetoothProfile) proxy);
        } catch (Throwable ignored) {
        }
    }

    /**
     * gamepad/connect：對已綁定設備發起 HID 連接（bonded 但 input 列表無佢嗰陣用）。
     * HID_HOST 係 hidden API（API 22 無公開），經 reflection 行：
     * getProfileProxy(HID_HOST=4) → proxy.connect(device)。拿唔到／行唔到即回
     * 明確錯誤（叫用家鍵盤端重按連接鍵或開關一次電源），唔靜默吞。
     * HTTP worker 線程內同步等 proxy 回調（最多 5 秒），唔阻塞主線程。
     */
    public HttpServer.ApiResponse connectResponse(Map<String, String> query) {
        String address = ApiValidator.require(query, "address").trim();
        if (!isMacAddress(address)) {
            throw new IllegalArgumentException(
                    "parameter 'address' must be a bluetooth MAC like AA:BB:CC:DD:EE:FF, got: " + address);
        }
        // getRemoteDevice 舊 stack 只認大寫（細寫即掟 IllegalArgumentException）：驗完即轉大寫。
        address = address.toUpperCase(java.util.Locale.US);
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return HttpServer.ApiResponse.error("no bluetooth adapter");
        if (!adapter.isEnabled()) {
            return HttpServer.ApiResponse.error("bluetooth off, turn it on first (gamepad/bt?on=true)");
        }
        final android.bluetooth.BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(address);
        } catch (Throwable t) {
            throw new IllegalArgumentException("unknown bluetooth device: " + address);
        }
        int bs = android.bluetooth.BluetoothDevice.BOND_NONE;
        try {
            bs = dev.getBondState();
        } catch (Throwable ignored) {
        }
        if (bs != android.bluetooth.BluetoothDevice.BOND_BONDED) {
            return HttpServer.ApiResponse.error("not bonded, pair first (gamepad/pair?address=" + address + ")");
        }
        // 掃緊會拖住 HID 連接，先停掃。
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Throwable ignored) {
        }
        Object proxy = hidHostProxy;
        if (proxy == null) {
            synchronized (this) {
                // 雙重檢查：排緊隊嗰陣有人拿完即用佢個，唔各自拿（leak listener）。
                proxy = hidHostProxy;
            }
        }
        if (proxy == null) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final Object[] holder = new Object[1];
            android.bluetooth.BluetoothProfile.ServiceListener listener =
                    new android.bluetooth.BluetoothProfile.ServiceListener() {
                        @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile p) {
                            holder[0] = p;
                            latch.countDown();
                        }
                        @Override public void onServiceDisconnected(int profile) {
                            latch.countDown();
                        }
                    };
            boolean requested;
            try {
                // HID_HOST=4（hidden 常量，直寫數字免編譯依賴 hidden 符號）。
                requested = adapter.getProfileProxy(appContext, listener, 4);
            } catch (Throwable t) {
                return HttpServer.ApiResponse.error("hid profile unavailable: " + t.getMessage());
            }
            if (!requested) return HttpServer.ApiResponse.error("hid profile proxy refused");
            try {
                if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    return HttpServer.ApiResponse.error("hid profile timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HttpServer.ApiResponse.error("interrupted");
            }
            proxy = holder[0];
            if (proxy == null) return HttpServer.ApiResponse.error("hid profile unavailable");
            synchronized (this) {
                // CAS 認領：等緊嗰陣有人拿完即用佢個，自己呢個即閂（唔 leak）。
                if (hidHostProxy != null) {
                    closeHidHostProxy(proxy);
                    proxy = hidHostProxy;
                } else {
                    hidHostProxy = proxy;
                }
            }
        }
        boolean started;
        try {
            java.lang.reflect.Method m = proxy.getClass().getMethod("connect",
                    android.bluetooth.BluetoothDevice.class);
            Object r = m.invoke(proxy, dev);
            started = r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            // proxy 可能已死（藍牙開關過）：掟咗緩存，下次重拿，唔使等 shutdown 先復原。
            if (proxy == hidHostProxy) hidHostProxy = null;
            return HttpServer.ApiResponse.error("hid connect failed: " + t.getMessage());
        }
        EventBus.get().publish("gamepad_bt", "{\"event\":\"hid_connect\",\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\",\"started\":" + started + "}");
        // 連上要幾秒：前端 5 秒後重查 devices（見 app-gamepad.js）。
        return HttpServer.ApiResponse.ok("{\"ok\":" + started + ",\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
    }

    /**
     * gamepad/unpair：解除綁定（連緊 HID 先斷線再 removeBond，hidden reflection）。
     * 未綁定即直接回 bonded:false；行唔到反射即回明確錯誤。
     */
    public HttpServer.ApiResponse unpairResponse(Map<String, String> query) {
        String address = ApiValidator.require(query, "address").trim();
        if (!isMacAddress(address)) {
            throw new IllegalArgumentException(
                    "parameter 'address' must be a bluetooth MAC like AA:BB:CC:DD:EE:FF, got: " + address);
        }
        // getRemoteDevice 舊 stack 只認大寫（細寫即掟 IllegalArgumentException）：驗完即轉大寫。
        address = address.toUpperCase(java.util.Locale.US);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return HttpServer.ApiResponse.error("no bluetooth adapter");
        if (!adapter.isEnabled()) {
            return HttpServer.ApiResponse.error("bluetooth off, turn it on first (gamepad/bt?on=true)");
        }
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(address);
        } catch (Throwable t) {
            throw new IllegalArgumentException("unknown bluetooth device: " + address);
        }
        int bs = BluetoothDevice.BOND_NONE;
        try {
            bs = dev.getBondState();
        } catch (Throwable ignored) {
        }
        if (bs == BluetoothDevice.BOND_NONE) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bonded\":false,\"address\":\""
                    + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
        }
        boolean removed;
        // 連緊 HID 嗰陣 removeBond 好多時回 false：先經緩存 proxy 斷 HID
        //（best-effort，冇 proxy 就跳過），等半秒畀斷線落地先解綁。
        try {
            Object proxy = hidHostProxy;
            if (proxy != null) {
                java.lang.reflect.Method dm = proxy.getClass().getMethod("disconnect",
                        android.bluetooth.BluetoothDevice.class);
                dm.invoke(proxy, dev);
                Thread.sleep(500);
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method m = dev.getClass().getMethod("removeBond");
            Object r = m.invoke(dev);
            removed = r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return HttpServer.ApiResponse.error("unpair failed: " + t.getMessage());
        }
        EventBus.get().publish("gamepad_bt", "{\"event\":\"unbond\",\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\",\"started\":" + removed + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":" + removed + ",\"bonded\":false,\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
    }

    /** gamepad/pair：對指定 MAC 發起綁定（createBond，配對碼／確認在對端做）。 */
    public HttpServer.ApiResponse pairResponse(Map<String, String> query) {
        String address = ApiValidator.require(query, "address").trim();
        if (!isMacAddress(address)) {
            throw new IllegalArgumentException(
                    "parameter 'address' must be a bluetooth MAC like AA:BB:CC:DD:EE:FF, got: " + address);
        }
        // getRemoteDevice 舊 stack 只認大寫（細寫即掟 IllegalArgumentException）：驗完即轉大寫。
        address = address.toUpperCase(java.util.Locale.US);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return HttpServer.ApiResponse.error("no bluetooth adapter");
        if (!adapter.isEnabled()) {
            return HttpServer.ApiResponse.error("bluetooth off, turn it on first (gamepad/bt?on=true)");
        }
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(address);
        } catch (Throwable t) {
            throw new IllegalArgumentException("unknown bluetooth device: " + address);
        }
        int bs = BluetoothDevice.BOND_NONE;
        try {
            bs = dev.getBondState();
        } catch (Throwable ignored) {
        }
        if (bs == BluetoothDevice.BOND_BONDED) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bonded\":true,\"address\":\""
                    + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
        }
        if (bs == BluetoothDevice.BOND_BONDING) {
            // 配對進行中：唔重打 createBond（會回 false 嚇親人），等 bond 事件／重掃確認。
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"bonded\":false,\"address\":\""
                    + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
        }
        ensureBtReceiver();
        // 配對期間停掃（classic 共用同一副天線，掃緊會拖慢／打斷 bonding）。
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Throwable ignored) {
        }
        boolean started;
        try {
            started = dev.createBond();
        } catch (SecurityException se) {
            return HttpServer.ApiResponse.error("bluetooth permission denied");
        } catch (Throwable t) {
            return HttpServer.ApiResponse.error(String.valueOf(t.getMessage()));
        }
        EventBus.get().publish("gamepad_bt", "{\"event\":\"bond\",\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\",\"bond\":\"bonding\"}");
        return HttpServer.ApiResponse.ok("{\"ok\":" + started + ",\"bonded\":false,\"address\":\""
                + MainActivity.jsonSafe(dev.getAddress()) + "\"}");
    }
}
