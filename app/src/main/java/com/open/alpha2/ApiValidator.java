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
    // 2026-09-09：全部 trim（之前 requireInt/optionalInt 唔 trim，optional 系
    // 同 float 系 trim，前後唔一致；URL 傳 " 90 " 唔應該 400）。
    public static int requireInt(Map<String, String> q, String key) {
        String v = require(q, key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    public static int optionalInt(Map<String, String> q, String key, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    public static int requireIntRange(Map<String, String> q, String key, int min, int max) {
        int v = requireInt(q, key);
        if (v < min || v > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between " + min + " and " + max + ", got: " + v);
        }
        return v;
    }

    /** 可選整數三態（缺席/空字串回 null；有值超限即 400；camera w/h 配對用）。 */
    public static Integer optionalIntegerRange(Map<String, String> q, String key, int min, int max) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        int iv;
        try {
            iv = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
        if (iv < min || iv > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between " + min + " and " + max + ", got: " + iv);
        }
        return Integer.valueOf(iv);
    }

    public static int optionalIntRange(Map<String, String> q, String key, int min, int max, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        int iv;
        try {
            iv = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
        if (iv < min || iv > max) {
            throw new IllegalArgumentException("parameter '" + key + "' must be between " + min + " and " + max + ", got: " + iv);
        }
        return iv;
    }

    public static long optionalLong(Map<String, String> q, String key, long defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    // ── 浮點 (vosk/endpointer t_*, ubx/speed value) ──────────────────
    public static float optionalFloat(Map<String, String> q, String key, float defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Float.parseFloat(v.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, got: " + v);
        }
    }

    // ── Nullable (缺席/空字串回 null, 有值則嚴格解析, 非法拋錯) ──────
    // 用於「兩個可選參數要一齊俾先有效」(camera w/h) 同「有參數=設定, 無=查詢」
    // (speech/offline_auto_switch on) 這類三態邏輯, 取代 query.get() + 手動判空。
    public static Integer optionalInteger(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        try {
            return Integer.valueOf(Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    public static Boolean optionalBooleanObject(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return Boolean.FALSE;
        throw new IllegalArgumentException("parameter '" + key + "' must be true/false, got: " + v);
    }

    public static String optionalNullable(Map<String, String> q, String key) {
        String v = q.get(key);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    // ── 枚舉 ────────────────────────────────────────────────────────
    public static String requireEnum(Map<String, String> q, String key, String[] allowed) {
        String v = require(q, key);
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("parameter '" + key + "' must be one of " + Arrays.toString(allowed) + ", got: " + v);
    }

    public static String optionalEnum(Map<String, String> q, String key, String[] allowed, String defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("parameter '" + key + "' must be one of " + Arrays.toString(allowed) + ", got: " + v);
    }

    // ── Boolean ────────────────────────────────────────────────────
    public static boolean requireBoolean(Map<String, String> q, String key) {
        String v = require(q, key);
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return true;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return false;
        throw new IllegalArgumentException("parameter '" + key + "' must be true/false, got: " + v);
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

    /** 2026-09: nuance/iflytek 已死 (機身無 alpha2services,
     *  RobotStub.speech_startTTS 恆回 NOT_INIT), handleSpeechTts() 已經收窄做
     *  恆行 android 路徑。呢兩個值仍然留喺允許 list - openapi spec (speech/tts)
     *  明確承諾 engine=nuance/iflytek「照收但必定回 NOT_INIT」，唔係
     *  invalid-parameter 錯誤；收窄呢個 list 會令傳呢兩個值嘅 caller (可能仲有
     *  舊存檔/第三方 tool description 靠住舊 spec) 由「誠實話你個引擎已死」變成
     *  「話你個參數本身唔合法」，係 API contract 嘅行為改變，唔係單純刪死
     *  code，所以刻意唔跟住 handleSpeechTts() 一齊收窄。 */
    /** 2026-09: nuance/iflytek 已經永久唔再用 (機身無 alpha2services, binder
     *  已死), 只准 android。 */
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
        if (parts.length != 20) {
            throw new IllegalArgumentException("parameter 'angles' must have exactly 20 comma-separated values, got " + parts.length);
        }
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) {
            // 2026-09-09：逐粒 0-255（同 spec/MCP schema；之前唔驗，999 會靜默
            // 截 byte wrap 落舵機，同 servo/one 唔一致）。
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be integer, got: " + parts[i]);
            }
            if (out[i] < 0 || out[i] > 255) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be between 0 and 255, got: " + out[i]);
            }
        }
        return out;
    }

    // ── 回應 helpers (統一 {"ok":...} 形狀) ────────────────────────
    public static HttpServer.ApiResponse ok(String jsonBody) {
        return HttpServer.ApiResponse.ok(jsonBody);
    }

    public static HttpServer.ApiResponse error(String msg) {
        return HttpServer.ApiResponse.error(msg);
    }

}
