package com.open.alpha2;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 輕量 OpenAPI 驅動的參數校驗層 (1+2 方案的第1層)。
 *
 * 對應 {@code openapi/open-alpha2-openapi.yml} 的 parameters / schema 定義，
 * 將 {@code MainActivity.require / queryOrDefault / Integer.parseInt / enum 檢查}
 * 這些散在 155 個 case 的重複 boilerplate 集中到一處。
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
    public static int requireInt(Map<String, String> q, String key) {
        String v = require(q, key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be an integer, got: " + v);
        }
    }

    public static int optionalInt(Map<String, String> q, String key, int defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
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

    public static String optionalEnumNullable(Map<String, String> q, String key, String[] allowed) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return null;
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

    public static boolean optionalBoolean(Map<String, String> q, String key, boolean defaultValue) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) return defaultValue;
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

    public static String requireSpeechEngine(Map<String, String> q) {
        return optionalEnum(q, "engine", new String[]{"nuance","iflytek","android"}, "nuance");
    }

    public static int[] requireAngles20(Map<String, String> q) {
        String csv = require(q, "angles");
        String[] parts = csv.split(",");
        if (parts.length != 20) {
            throw new IllegalArgumentException("parameter 'angles' must have exactly 20 comma-separated values, got " + parts.length);
        }
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parameter 'angles' element " + (i+1) + " must be integer, got: " + parts[i]);
            }
        }
        return out;
    }

    // ── 回應 helpers (統一 {"ok":...} 形狀) ────────────────────────
    public static HttpServer.ApiResponse ok(String jsonBody) {
        return HttpServer.ApiResponse.ok(jsonBody);
    }

    public static HttpServer.ApiResponse okTrue() {
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public static HttpServer.ApiResponse error(String msg) {
        return HttpServer.ApiResponse.error(msg);
    }

    // ── 範例重構 (註釋保留，供對照) ────────────────────────────────
    // 以下為 MainActivity 中可直接替換的示例，保留作文件參考，不會被調用：
    //
    // case "servo/one": {
    //   // 舊: byte id = Byte.parseByte(require(query,"id")); int angle = Integer.parseInt(require(query,"angle")); short time = Short.parseShort(queryOrDefault(query,"time","1000"));
    //   // 新: int id = ApiValidator.requireIntRange(query,"id",1,20); int angle = ApiValidator.requireInt(query,"angle"); int time = ApiValidator.optionalInt(query,"time",1000);
    //   // return codeResponseReady(robot.chest_SendOneFreeAngle((byte)id, angle, (short)time), robot.isChestReady());
    // }
    //
    // case "led/head/set": {
    //   // 舊: int color = Integer.parseInt(require(query,"color")); int brightness = Integer.parseInt(require(query,"brightness")); String preset = queryOrDefault(query,"preset","long"); switch(preset)...
    //   // 新: int color = ApiValidator.requireColor(query); int brightness = ApiValidator.requireBrightness(query); String preset = ApiValidator.requireLedHeadPreset(query);
    // }
}
