package com.open.alpha2;

/**
 * JSON 字串轉義 + 極輕量組裝 helper，取代之前散喺各處嘅手寫轉義。
 *
 * 之前 {@link MainActivity#jsonSafe} 同 {@link HttpServer.ApiResponse} 嘅
 * {@code esc()} 係同一套轉義各自複製一份（comment 寫明「唔直接引用免循環」）。
 * 而家單一實現放呢度，兩邊都 delegate 過嚟，行為不變。
 *
 * 零依賴、API 22 相容（唔用 String.join / Map.getOrDefault）。
 */
public final class JsonUtil {
    private JsonUtil() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** JSON string 內容轉義（唔包外層引號）。null 回 ""。 */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        sb.append(HEX[(c >>> 12) & 0xF]);
                        sb.append(HEX[(c >>> 8) & 0xF]);
                        sb.append(HEX[(c >>> 4) & 0xF]);
                        sb.append(HEX[c & 0xF]);
                    } else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /** 加外層引號：{@code " + esc(s) + "}。 */
    public static String quote(String s) {
        return "\"" + esc(s) + "\"";
    }

    /** nullable 字串值：null 回 {@code null}，否則回帶引號。 */
    public static String quoteOrNull(String s) {
        return s != null ? quote(s) : "null";
    }

    /** {@code {"ok":true}} / {@code {"ok":false,"error":"..."}} 快捷。 */
    public static String okTrue() {
        return "{\"ok\":true}";
    }

    public static String okFalse(String error) {
        return "{\"ok\":false,\"error\":\"" + esc(String.valueOf(error)) + "\"}";
    }

    /** 超長字串截斷（log／NetLog 共用）：超限加 "..."。 */
    public static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
