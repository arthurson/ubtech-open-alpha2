package com.open.alpha2;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Hand-rolled RFC 6455 WebSocket server: handshake (Sec-WebSocket-Accept derivation) plus
 * minimal text-frame send/receive. No library - matches the SDK's own zero-third-party-
 * dependency policy. Used only to push a live event log (head-key presses, ASR results,
 * action lifecycle, wakeup, gesture, ...) from {@link EventBus} to the browser control
 * panel; the panel never needs binary frames or fragmentation, so this intentionally
 * doesn't implement the full spec (no ping/pong, no fragmented messages, no extensions).
 */
public class WebSocketServer {
    private static final String TAG = "WebSocketServer";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static void handleUpgrade(Socket socket, InputStream rawIn, Map<String, String> headers) {
        try {
            String key = headers.get("sec-websocket-key");
            if (key == null) {
                socket.close();
                return;
            }
            String accept = computeAccept(key);

            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            final Connection conn = new Connection(socket, out);
            Log.i(TAG, "WebSocket upgrade accepted (remote=" + socket.getRemoteSocketAddress() + ")");
            EventBus.Listener listener = new EventBus.Listener() {
                @Override
                public void onEvent(String line) {
                    conn.sendText(line);
                }
            };
            EventBus.get().subscribe(listener);
            try {
                conn.sendText("{\"type\":\"connected\",\"time\":\"\",\"data\":{\"msg\":\"ws connected\"}}");
                long startMs = System.currentTimeMillis();
                conn.readLoop(rawIn);
                Log.i(TAG, "WebSocket closed normally after " + (System.currentTimeMillis() - startMs) + "ms");
            } finally {
                EventBus.get().unsubscribe(listener);
            }
        } catch (IOException e) {
            Log.i(TAG, "WebSocket connection closed: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String computeAccept(String clientKey) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    /** One live WebSocket connection: owns the raw frame I/O for its lifetime. */
    private static final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private volatile boolean open = true;

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        /** Reads client frames until close/EOF. The panel never sends meaningful data
         *  frames back, but we must still parse and discard them (and answer pings)
         *  to keep the connection and buffers correct. */
        void readLoop(InputStream in) throws IOException {
            while (open) {
                int b0 = in.read();
                if (b0 == -1) return;
                int b1 = in.read();
                if (b1 == -1) return;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                // 2026-08 修正: 之前呢兩個分支入面嘅 in.read() 完全冇檢查 -1 (EOF) ——
                // 如果連線啱啱好喺讀緊 16-bit/64-bit length 嗰陣斷咗, `-1 & 0xFF` 會
                // 變成 255, 靜靜哋攞到一個錯誤嘅 length 值而唔係俾人發現到係 EOF, 跟住
                // 落去可能用住一個垃圾 length 去讀 payload, 有機會卡死或者讀入垃圾
                // 資料。而家改用 readByteOrThrow(), 一旦撞到 EOF 就即刻拋
                // IOException, 俾返 handleUpgrade() 嗰層現有嘅 catch (IOException e)
                // 接住, 同一般連線中斷冇分別噉樣結束呢個 loop。
                if (len == 126) {
                    len = (readByteOrThrow(in) << 8) | readByteOrThrow(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByteOrThrow(in);
                    }
                }

                // 2026-08 新增: 之前呢度冇對 len 做任何上限檢查 —— 一個惡意 client
                // 可以送一個 opcode=127 (64-bit length) 嘅 frame header, 聲稱 payload
                // 有幾 GB, `new byte[(int) len]` 就算 len cast 落 int 冇溢出都可以即刻
                // 令呢條 pool thread 拋 OutOfMemoryError (Error, 接唔到)。呢個 panel
                // 由頭到尾都唔期望瀏覽器送任何有意義嘅 data frame 返嚟 (見上面
                // class javadoc), 所以上限可以定得幾保守都得 - 1MB 已經遠超任何
                // 呢個 panel 會用到嘅入站 frame (ping/pong payload 通常得幾個 byte)。
                final long MAX_FRAME_PAYLOAD_BYTES = 1024 * 1024;
                if (len < 0 || len > MAX_FRAME_PAYLOAD_BYTES) {
                    Log.w(TAG, "Rejecting WebSocket frame with payload length " + len
                            + " (limit " + MAX_FRAME_PAYLOAD_BYTES + ")");
                    return;
                }

                byte[] mask = new byte[4];
                if (masked) {
                    int read = 0;
                    while (read < 4) {
                        int n = in.read(mask, read, 4 - read);
                        if (n < 0) return;
                        read += n;
                    }
                }

                byte[] payload = new byte[(int) len];
                int read = 0;
                while (read < len) {
                    int n = in.read(payload, read, (int) len - read);
                    if (n < 0) return;
                    read += n;
                }
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }

                switch (opcode) {
                    case 0x8: // close
                        open = false;
                        return;
                    case 0x9: // ping -> pong
                        sendFrame((byte) 0x8A, payload);
                        break;
                    default:
                        // text/binary/continuation from the client: not used by this panel.
                        break;
                }
                if (!fin) {
                    // Fragmented frames from the client aren't expected from this panel's
                    // JS; ignore continuation complexity rather than mis-parse it.
                }
            }
        }

        /** Reads exactly one byte, throwing IOException on EOF instead of silently
         *  returning -1 (which, if used directly in a bit-shift expression, becomes
         *  0xFF and looks like valid data rather than a closed connection). */
        private static int readByteOrThrow(InputStream in) throws IOException {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Unexpected EOF while reading WebSocket frame header");
            }
            return b;
        }

        void sendText(String text) {
            if (!open) return;
            try {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                sendFrame((byte) 0x81, payload); // FIN + text opcode, unmasked (server->client)
            } catch (IOException e) {
                open = false;
            }
        }

        private synchronized void sendFrame(byte firstByte, byte[] payload) throws IOException {
            int len = payload.length;
            out.write(firstByte);
            if (len < 126) {
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(126);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((len >> (8 * i)) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }
}
