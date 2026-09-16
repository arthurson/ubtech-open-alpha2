package com.open.alpha2;

import java.util.Arrays;
import java.util.Map;

/**
 * 輕量 OpenAPI 驅動的參數校驗層 (1+2 方案的第1層)。
 *
 * 對應 {@code openapi/open-alpha2-openapi.yml} 的 parameters / schema 定義，
 * 將舊 {@code MainActivity.require / queryOrDefault / Integer.parseInt / enum 檢查}
 * 這些散在各 handle*Api case 的重複 boilerplate 集中到一處。
 *
 * 設計原則：
 *  - 零額外依賴 (只用 Map/String)，符合此專案 "Android framework + JDK only" 政策 (見 HttpServer.java:34)。
 *  - API 22 相容 (不用 Map.getOrDefault)。
 *  - 拋 IllegalArgumentException，交由 handleApi 外層 catch 統一轉成 {"ok":false,"error":...} (沿用現有 require 的行為)。
 *  -  不做重量級 JSON Schema 校驗，僅做本專案實際需要的：必填、整數、整數範圍、枚舉、boolean。
 *
 * 用法示例 (替換前 vs 後)：
 *   // 舊:
 *   byte id = Byte.parseByte(require(query,"id"));
 *   int angle = Integer.parseInt(require(query,"angle"));
 *   // 新:
 *   int id = ApiValidator.requireIntRange(query,"id",1,20);
 *   int angle = ApiValidator.requireInt(query,"angle");
 *
 *   // 舊:
 *   String preset = queryOrDefault(query,"preset","long");
 *   switch(preset){ case "flash": ... default: ...}
 *   if(!Arrays.asList("long","flash","breathe"...).contains(preset)) error
 *   // 新:
 *   String preset = ApiValidator.optionalEnum(query,"preset",new String[]{"long","flash","breathe","chase","dual","stop"},"long");
 */
public final class ApiValidator {
    private ApiValidator() {}

    /** servo 範圍單一來源（同 openapi spec／MCP schema／McpToolsGenerated 約束一致）：
     *  id 1-20、angle 0-255、time 20-32767ms。之前 UbxApi／ApiDispatcher／
     *  XiaozhiBridge 各寫裸 literal，改漏一處即前後端驗證唔一致，收斂到呢度。 */
    public static final int SERVO_ID_MIN = 1;
    public static final int SERVO_ID_MAX = 20;
    /** 全機舵機粒數（servo/all CSV 長度、loop 上限）。 */
    public static final int SERVO_COUNT = 20;
    public static final int SERVO_ANGLE_MIN = 0;
    public static final int SERVO_ANGLE_MAX = 255;
    public static final int SERVO_TIME_MIN_MS = 20;
    public static final int SERVO_TIME_MAX_MS = 32767;

    // ── 內部共用解析（全部 trim；訊息形狀不變）─────────────────────
    private static int parseIntOrThrow(String key, String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    private static long parseLongOrThrow(String key, String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    private static int rangeCheck(String key, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between " + min + " and " + max + ", got: " + v);
        }
        return v;
    }

    private static float parseFloatOrThrow(String key, String v) {
        try {
            return Float.parseFloat(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, got: " + v);
        }
    }

    private static double parseDoubleOrThrow(String key, String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, got: " + v);
        }
    }

    private static Boolean parseBoolOrNull(String key, String v) {
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return Boolean.FALSE;
        throw new IllegalArgumentException("parameter '" + key + "' must be true/false, got: " + v);
    }

    private static String checkEnumOrThrow(String key, String v, String[] allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("parameter '" + key + "' must be one of " + Arrays.toString(allowed) + ", got: " + v);
    }

    // ── 基礎 ────────────────────────────────────────────────────────
    public static String require(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return v;
    }

    public static String optional(Map<String, String> q, String key, String defaultValue) {
        String v = q.get(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    // ── 整數 ────────────────────────────────────────────────────────
    // 全部 trim（URL 傳 " 90 " 唔應該 400）。
    public static int requireInt(Map<String, String> q, String key) {
        return parseIntOrThrow(key, require(q, key));
    }

    public static int optionalInt(Map<String, String> q, String key, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseIntOrThrow(key, v);
    }

    public static int requireIntRange(Map<String, String> q, String key, int min, int max) {
        return rangeCheck(key, requireInt(q, key), min, max);
    }

    /** 可選整數三態（缺席/空字串回 null；有值超限即 400；camera w/h 配對用）。 */
    public static Integer optionalIntegerRange(Map<String, String> q, String key, int min, int max) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return Integer.valueOf(rangeCheck(key, parseIntOrThrow(key, v), min, max));
    }

    public static int optionalIntRange(Map<String, String> q, String key, int min, int max, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return rangeCheck(key, parseIntOrThrow(key, v), min, max);
    }

    public static long requireLong(Map<String, String> q, String key) {
        return parseLongOrThrow(key, require(q, key));
    }

    public static long optionalLong(Map<String, String> q, String key, long defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseLongOrThrow(key, v);
    }

    // ── 浮點 (vosk/endpointer t_*, ubx/speed value) ──────────────────
    public static float requireFloat(Map<String, String> q, String key) {
        return parseFloatOrThrow(key, require(q, key));
    }

    public static float optionalFloat(Map<String, String> q, String key, float defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseFloatOrThrow(key, v);
    }

    public static double requireDouble(Map<String, String> q, String key) {
        return parseDoubleOrThrow(key, require(q, key));
    }

    public static double optionalDouble(Map<String, String> q, String key, double defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseDoubleOrThrow(key, v);
    }

    // ── Nullable (缺席/空字串回 null, 有值則嚴格解析, 非法拋錯) ──────
    // 用於「兩個可選參數要一齊俾先有效」(camera w/h) 同「有參數=設定, 無=查詢」
    // (speech/offline_auto_switch on) 這類三態邏輯, 取代 query.get() + 手動判空。
    public static Integer optionalInteger(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return Integer.valueOf(parseIntOrThrow(key, v));
    }

    public static Boolean optionalBooleanObject(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return parseBoolOrNull(key, v.trim());
    }

    public static String optionalNullable(Map<String, String> q, String key) {
        String v = q.get(key);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    // ── 枚舉 ────────────────────────────────────────────────────────
    public static String requireEnum(Map<String, String> q, String key, String[] allowed) {
        return checkEnumOrThrow(key, require(q, key), allowed);
    }

    public static String optionalEnum(Map<String, String> q, String key, String[] allowed, String defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return checkEnumOrThrow(key, v, allowed);
    }

    public static String optionalEnumNullable(Map<String, String> q, String key, String[] allowed) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        return checkEnumOrThrow(key, v, allowed);
    }

    // ── Boolean ────────────────────────────────────────────────────
    public static boolean requireBoolean(Map<String, String> q, String key) {
        return parseBoolOrNull(key, require(q, key).trim()).booleanValue();
    }

    public static boolean optionalBoolean(Map<String, String> q, String key, boolean defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        return parseBoolOrNull(key, v.trim()).booleanValue();
    }

    // ── 常用組合 (對應 openapi 的具體 constraints) ─────────────────
    public static int requireColor(Map<String, String> q) {
        return requireIntRange(q, "color", 1, 7);
    }

    public static int requireBrightness(Map<String, String> q) {
        return requireIntRange(q, "brightness", 1, 9);
    }

    public static String requireLedHeadPreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"long","flash","breathe","chase","dual","stop"}, "long");
    }

    public static String requireLedEyePreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"long","flash","chase","dual","stop"}, "long");
    }

    public static String requireMouthPreset(Map<String, String> q) {
        return optionalEnum(q, "preset", new String[]{"breathing","off"}, "breathing");
    }

    public static int requireMouthSpeed(Map<String, String> q) {
        return optionalIntRange(q, "speed", 0, 5000, 0);
    }

    /** nuance/iflytek 已死 (機身無 alpha2services,
     *  RobotStub.speech_startTTS 恆回 NOT_INIT)。呢兩個值仍然留喺允許 list - openapi spec (speech/tts)
     *  明確承諾 engine=nuance/iflytek「照收但必定回 NOT_INIT」，唔係
     *  invalid-parameter 錯誤；收窄呢個 list 會令傳呢兩個值嘅 caller 由「誠實話你個引擎已死」變成
     *  「話你個參數本身唔合法」，係 API contract 嘅行為改變，唔係單純刪死
     *  code，所以刻意唔收窄。 */
    /** TTS 引擎：只准 android（機身無 alpha2services，binder 已死）。 */
    public static String requireSpeechEngine(Map<String, String> q) {
        return optionalEnum(q, "engine", new String[]{"android"}, "android");
    }

    public static String optionalRingtoneType(Map<String, String> q) {
        return optionalEnum(q, "type", new String[]{"ringtone","notification"}, "ringtone");
    }

    public static String optionalSerialPort(Map<String, String> q) {
        return optionalEnum(q, "port", new String[]{"head","chest"}, "head");
    }

    public static String optionalUiLang(Map<String, String> q) {
        return optionalEnum(q, "ui_lang", new String[]{"zh","en"}, "zh");
    }

    public static String requireDebugLedFunc(Map<String, String> q) {
        return requireEnum(q, "func", new String[]{"off","on","eye","head"});
    }

    public static String requireXiaozhiTtsEngine(Map<String, String> q) {
        return requireEnum(q, "engine", new String[]{"xiaozhi","android"});
    }

    /** 開機語音模式三選一（見 XiaozhiConfig boot_voice/get|set，實驗 tab 卡）。 */
    public static String requireBootVoiceMode(Map<String, String> q) {
        return requireEnum(q, "mode", new String[]{"off","xiaozhi","vosk"});
    }

    /** ubx/speed value: 0.5|0.67|1|1.5|2 (見 openapi enum + UbxPlayer.setSpeed)。 */
    public static float requireUbxSpeed(Map<String, String> q) {
        return parseUbxSpeedValue(require(q, "value"));
    }

    /** 共用字串版 (供 ubxSpeedResponse 這類已抽出 query 的 helper覆用, 同一套 enum)。 */
    public static float parseUbxSpeedValue(String v) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: value");
        }
        float f;
        try {
            f = Float.parseFloat(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
        }
        float[] allowed = new float[]{0.5f, 0.67f, 1.0f, 1.5f, 2.0f};
        for (float a : allowed) {
            if (Math.abs(f - a) < 0.001f) return a;
        }
        throw new IllegalArgumentException("parameter 'value' must be one of [0.5, 0.67, 1, 1.5, 2], got: " + v);
    }

    /** 實驗 tab 面板 token：8–64 字元 URL-safe（英數/-/_，見 PanelAuth）。 */
    public static void checkPanelTokenFormat(String token) {
        if (token == null) {
            throw new IllegalArgumentException("missing required parameter: token");
        }
        String v = token.trim();
        if (v.length() < 8 || v.length() > 64 || !v.matches("[A-Za-z0-9\\-_]+")) {
            throw new IllegalArgumentException("token must be 8-64 chars of A-Za-z0-9/-/_");
        }
    }

    /** misc/set_uuid value: 1-31 ASCII 字元 (見 openapi minLength/maxLength)。 */
    public static String requireUuidValue(Map<String, String> q) {
        String v = require(q, "value");
        if (v.length() < 1 || v.length() > 31) {
            throw new IllegalArgumentException("parameter 'value' must be 1-31 ascii chars, got length: " + v.length());
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("parameter 'value' must be 1-31 ascii chars, got non-ascii at index " + i);
            }
        }
        return v;
    }

    public static int requireVolumePercent(Map<String, String> q) {
        return requireIntRange(q, "percent", 0, 100);
    }

    public static int requireVolumePercent(Map<String, String> q, String key) {
        return requireIntRange(q, key, 0, 100);
    }

    public static int optionalVoskEndpointerMode(Map<String, String> q) {
        return optionalIntRange(q, "mode", -1, 3, -1);
    }

    public static int[] requireAngles20(Map<String, String> q) {
        String csv = require(q, "angles");
        String[] parts = csv.split(",");
        if (parts.length != SERVO_COUNT) {
            throw new IllegalArgumentException("parameter 'angles' must have exactly " + SERVO_COUNT + " comma-separated values, got " + parts.length);
        }
        int[] out = new int[SERVO_COUNT];
        for (int i = 0; i < SERVO_COUNT; i++) {
            // 逐粒 0-255（同 spec/MCP schema；同 servo/one 一致，防靜默截 byte wrap 落舵機）。
            int v;
            try {
                v = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be integer, got: " + parts[i]);
            }
            if (v < SERVO_ANGLE_MIN || v > SERVO_ANGLE_MAX) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be between 0 and 255, got: " + v);
            }
            out[i] = v;
        }
        return out;
    }

    // ── 回應 helpers (統一 {"ok":...} 形狀) ────────────────────────
    public static HttpServer.ApiResponse ok(String jsonBody) {
        return HttpServer.ApiResponse.ok(jsonBody);
    }

    public static HttpServer.ApiResponse okTrue() {
        return HttpServer.ApiResponse.okTrue();
    }

    public static HttpServer.ApiResponse error(String msg) {
        return HttpServer.ApiResponse.error(msg);
    }
}
