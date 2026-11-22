package android.util;

/** 測試用 stub（WebSocketServer 計 Sec-WebSocket-Accept 用）。 */
public final class Base64 {
    public static final int NO_WRAP = 2;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
