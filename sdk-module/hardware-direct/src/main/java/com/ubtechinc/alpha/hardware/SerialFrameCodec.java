package com.ubtechinc.alpha.hardware;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 胸/頭 MCU 的線協議編解碼（pure-direct 下唯一的組幀/解幀實現）。
 * 真實線格式（2026-09-06 官方 PC 動作編輯器 logcat 實測，651 個 CHEST 幀逐條驗過）：
 * <pre>
 *   F8 8F LEN SRC DST CMD PARAM... SUM ED
 *   SRC=05 DST=00（官方 App 發出的胸命令一律此頭；MCU 回覆同頭；
 *   MCU 主動事件如 0x80/0x91 用 00 00 頭）
 *   LEN = 7 + PARAM字節數，總幀長 = LEN+1
 *   SUM = (LEN + SRC + DST + CMD + ΣPARAM) &amp; 0xFF
 * </pre>
 * 證據：官方單舵機 {@code f8 8f 0c 05 00 05 03 00 7b 00 64 f8 ed}
 * （12+5+0+5+3+0+123+0+100=248=0xF8 ✓，唔計 SRC 會得 0xF3 ✗）；
 * 讀回覆 {@code f8 8f 09 05 00 05 00 03 16 ed}（9+5+0+5+0+3=22=0x16 ✓）；
 * 舊 00 00 頭 MCU 照食（set_uuid／version fallback／mute 鍵實證），
 * 但為同官方逐 byte 一致，發送一律用 05 00；舊 00 00 幀計法
 * （SRC+DST=0，SUM 不變）自然兼容。
 */
public final class SerialFrameCodec {
    private SerialFrameCodec() {}

    private static final byte FRAME_HEAD_0 = (byte) 0xF8;
    private static final byte FRAME_HEAD_1 = (byte) 0x8F;
    private static final byte FRAME_TAIL   = (byte) 0xED;
    /** 官方 App 發送頭：SRC=05 DST=00（2026-09-06 官方 tuner 實測）。 */
    private static final byte FRAME_SRC = 0x05;
    private static final byte FRAME_DST = 0x00;
    /** MCU 主動事件頭（0x80 心跳/0x91 mute 鍵等）：00 00，接收兼容用。 */
    private static final byte EVENT_SRC = 0x00;

    /**
     * 編一個完整待發送幀（長式，官方 App 同款 05 00 頭）。
     * @param cmd  來自 StaticValue 的 command byte (如 52=CHEST_SET_ALL_ANGLE, 5=CHES_CMD_MOTORANGLE)
     * @param param 可為 null/空
     */
    public static byte[] encode(byte cmd, byte[] param) {
        int plen = param == null ? 0 : param.length;
        int len = 7 + plen; // LEN = 7 + PARAM字節數（總幀長 = LEN+1）
        byte[] frame = new byte[3 + 2 + 1 + plen + 1 + 1]; // F8 8F LEN SRC DST CMD PARAM SUM ED
        int i = 0;
        frame[i++] = FRAME_HEAD_0;
        frame[i++] = FRAME_HEAD_1;
        frame[i++] = (byte) (len & 0xFF);
        frame[i++] = FRAME_SRC;
        frame[i++] = FRAME_DST;
        frame[i++] = cmd;
        if (plen > 0) {
            System.arraycopy(param, 0, frame, i, plen);
            i += plen;
        }
        // checksum = (LEN + SRC + DST + CMD + ΣPARAM) & 0xFF
        int sum = (len & 0xFF) + (FRAME_SRC & 0xFF) + (FRAME_DST & 0xFF) + (cmd & 0xFF);
        if (param != null) for (byte b : param) sum += (b & 0xFF);
        frame[i++] = (byte) (sum & 0xFF);
        frame[i++] = FRAME_TAIL;
        return frame;
    }

    /**
     * 嘗試從緩衝區解一幀，返回 {frameBytes, consumed}，不夠一幀返回 null。
     * 長式優先（F8 8F LEN SRC DST CMD ... SUM ED，總長 LEN+1；SRC/DST 認
     * 05 00（官方 App／MCU 回覆）或 00 00（MCU 主動事件／舊手寫幀））；
     * 長式對不上時回退認舊短式（F8 8F LEN CMD ... SUM ED），保證都能解。
     */
    public static DecodeResult tryDecode(byte[] buf, int offset, int available) {
        if (available < 5) return null;
        int start = -1;
        for (int j = offset; j < offset + available - 1; j++) {
            if (buf[j] == FRAME_HEAD_0 && buf[j+1] == FRAME_HEAD_1) { start = j; break; }
        }
        if (start < 0) return null;
        if (start + 4 >= offset + available) return null;
        int len = buf[start+2] & 0xFF;
        // 長式優先：SRC DST 頭（05 00 官方式／00 00 事件式）
        if (buf[start+4] == 0 && (buf[start+3] == FRAME_SRC || buf[start+3] == EVENT_SRC)) {
            int total = len + 1; // F8 8F LEN(3) + SRC DST CMD PARAM(len-7) SUM ED = len+1
            if (total < 8) return new DecodeResult(null, (start - offset) + 1);
            if (start + total > offset + available) return null;
            if (buf[start + total - 1] != FRAME_TAIL) {
                return new DecodeResult(null, (start - offset) + 1);
            }
            int plen = len - 7;
            int sum = len + (buf[start+3] & 0xFF) + (buf[start+4] & 0xFF) + (buf[start+5] & 0xFF);
            for (int k = 0; k < plen; k++) sum += buf[start+6+k] & 0xFF;
            if ((sum & 0xFF) != (buf[start+total-2] & 0xFF)) {
                return new DecodeResult(null, (start - offset) + 1);
            }
            byte[] frame = Arrays.copyOfRange(buf, start, start+total);
            return new DecodeResult(frame, (start - offset) + total);
        }
        // 短式回退
        int total = 2 + 1 + len + 1 + 1; // head(2) + len + payload(len) + checksum + tail
        if (start + total > offset + available) return null;
        if (buf[start + total - 1] != FRAME_TAIL) return null;
        int sum = len & 0xFF;
        for (int k = 0; k < len; k++) sum += buf[start+3+k] & 0xFF;
        if ((sum & 0xFF) != (buf[start+total-2] & 0xFF)) {
            // checksum 錯，跳過這個頭繼續找
            return new DecodeResult(null, (start - offset) + 1);
        }
        byte[] frame = Arrays.copyOfRange(buf, start, start+total);
        return new DecodeResult(frame, (start - offset) + total);
    }

    public static final class DecodeResult {
        public final byte[] frame; // null 表示跳過
        public final int consumed;
        DecodeResult(byte[] frame, int consumed) { this.frame = frame; this.consumed = consumed; }
    }

    /** 工具：把 byte[] 列印成 logcat 風格的 hex，如 f8 8f 08 00 00 91 01 9a ed */
    public static String toHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x ", v & 0xFF));
        return sb.toString().trim();
    }
}
