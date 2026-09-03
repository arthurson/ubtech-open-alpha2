package com.ubtechinc.alpha.hardware;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 胸/头 MCU 的线协议编解码（pure-direct 下唯一的组帧/解帧实现）。
 * 真实线格式（多处实机证据互相印证）：
 * <pre>
 *   F8 8F LEN 00 00 CMD PARAM... SUM ED
 *   LEN = 7 + PARAM字节数
 *   SUM = (LEN + CMD + ΣPARAM) &amp; 0xFF（00 00 不计入）
 * </pre>
 * 证据：set_uuid 手写帧 {@code F8 8F <7+n> 00 00 36 <sn...> <sum> ED}（实机逆向确认）、
 * chest version fallback {@code F8 8F 07 00 00 33 3A ED}（7+51=58=0x3A ✓）、
 * mute 键 {@code f8 8f 08 00 00 91 01 9a ed}（8+0x91+1=0x19A→0x9A ✓）、
 * parseVersionFrame 按 [i+5]==cmd 解析。旧短式（无 00 00）MCU 不认——2026-09
 * 实测短式 servo TX 发得出但舵机不动，故改回长式。
 */
public final class SerialFrameCodec {
    private SerialFrameCodec() {}

    private static final byte FRAME_HEAD_0 = (byte) 0xF8;
    private static final byte FRAME_HEAD_1 = (byte) 0x8F;
    private static final byte FRAME_TAIL   = (byte) 0xED;

    /**
     * 编一个完整待发送帧（长式，MCU 唯一接受的格式）。
     * @param cmd  来自 StaticValue 的 command byte (如 52=CHEST_SET_ALL_ANGLE, 5=CHES_CMD_MOTORANGLE)
     * @param param 可为 null/空
     */
    public static byte[] encode(byte cmd, byte[] param) {
        int plen = param == null ? 0 : param.length;
        int len = 7 + plen; // LEN = 7 + PARAM字节数（总帧长 = LEN+1）
        byte[] frame = new byte[3 + 2 + 1 + plen + 1 + 1]; // F8 8F LEN 00 00 CMD PARAM SUM ED
        int i = 0;
        frame[i++] = FRAME_HEAD_0;
        frame[i++] = FRAME_HEAD_1;
        frame[i++] = (byte) (len & 0xFF);
        frame[i++] = 0x00;
        frame[i++] = 0x00;
        frame[i++] = cmd;
        if (plen > 0) {
            System.arraycopy(param, 0, frame, i, plen);
            i += plen;
        }
        // checksum = (LEN + CMD + ΣPARAM) & 0xFF（00 00 不计入）
        int sum = (len & 0xFF) + (cmd & 0xFF);
        if (param != null) for (byte b : param) sum += (b & 0xFF);
        frame[i++] = (byte) (sum & 0xFF);
        frame[i++] = FRAME_TAIL;
        return frame;
    }

    /**
     * 尝试从缓冲区解一帧，返回 {frameBytes, consumed}，不够一帧返回 null。
     * 主认长式（F8 8F LEN 00 00 CMD ... SUM ED，总长 LEN+1）；长式对不上时
     * 回退认旧短式（F8 8F LEN CMD ... SUM ED），保证两种回复都能解。
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
        // 长式优先：00 00 头
        if (buf[start+3] == 0 && buf[start+4] == 0) {
            int total = len + 1; // F8 8F LEN(3) + 00 00 CMD PARAM(len-7) SUM ED = len+1
            if (total < 8) return new DecodeResult(null, (start - offset) + 1);
            if (start + total > offset + available) return null;
            if (buf[start + total - 1] != FRAME_TAIL) {
                return new DecodeResult(null, (start - offset) + 1);
            }
            int plen = len - 7;
            int sum = len + (buf[start+5] & 0xFF);
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
            // checksum 错，跳过这个头继续找
            return new DecodeResult(null, (start - offset) + 1);
        }
        byte[] frame = Arrays.copyOfRange(buf, start, start+total);
        return new DecodeResult(frame, (start - offset) + total);
    }

    public static final class DecodeResult {
        public final byte[] frame; // null 表示跳过
        public final int consumed;
        DecodeResult(byte[] frame, int consumed) { this.frame = frame; this.consumed = consumed; }
    }

    /** 工具：把 byte[] 打印成 logcat 风格的 hex，如 f8 8f 08 00 00 91 01 9a ed */
    public static String toHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x ", v & 0xFF));
        return sb.toString().trim();
    }
}
