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

    // 2026-08 新增: 見下面 handleUpgrade() 的 comment - 用來偵測「半開」殭屍
    // connection。10 秒 SO_TIMEOUT, 逾時就送 ping 探一探, 連續兩次 (共 20 秒)
    // 都沒有任何回應 (包括 pong) 才當死, 不會誤殺一條正常但剛好沒 event 可送、
    // 也沒 ping/pong 往來的正常 idle connection (瀏覽器還會定期送 ping 過來,
    // readLoop() 收到會立刻 answer pong, 一樣算「有收到東西」, 不會被計入逾時)。
    private static final int IDLE_TIMEOUT_MS = 10000;
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 2;

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

            // 2026-08 新增: 手機瀏覽器背景/鎖屏/切換網路的時候, TCP connection 可能
            // 變成「半開」— 對面 (瀏覽器) 已經不再理這條 connection, 但沒送 FIN/RST
            // 過來, 讓下面 readLoop() 的 in.read() 永遠 block 下去, finally 的
            // unsubscribe() 永遠沒機會執行到。結果: client 用回 connectWs() 的
            // 3 秒重連機制開多條新 connection, 但舊那條殭屍 connection 的 listener
            // 一直留在 EventBus, 讓同一個 event (例如 asr_result) 經好幾個 listener
            // 各自送去前端, 觸發前端 triggerIflytekSimulate() 好幾次 -> 同一句話
            // TTS 講好幾次。(用戶回報: 語音tab, ASR重複兩次TTS。實測 logcat 見到
            // publish() 一直印 "5 listener(s) subscribed", 但整個 session 只有
            // "WebSocket upgrade accepted" 兩次、"closed normally" 一次 - 證明有
            // 3~4 條是讀不到 close 事件的殭屍 connection。)
            //
            // 修法: 幫這個 socket 設 SO_TIMEOUT, 讓 in.read() 不會永遠 block, 逾時就
            // 送一個 WebSocket ping frame 探一探這條 connection 還在不在; 連續兩次
            // (即總共 idle 夠 2×IDLE_TIMEOUT_MS) 都沒收到任何東西 (連 pong 都沒) 就
            // 當它死了, 主動關閉, 讓 finally 的 unsubscribe() 一定會執行到。
            socket.setSoTimeout(IDLE_TIMEOUT_MS);

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
                Log.i(TAG, "WebSocket closed normally after " + (System.currentTimeMillis() - startMs) + "ms"
                        + " (listeners now " + (EventBus.get().listenerCount() - 1) + ")");
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
            int consecutiveTimeouts = 0;
            while (open) {
                int b0;
                try {
                    b0 = in.read();
                } catch (java.net.SocketTimeoutException e) {
                    // SO_TIMEOUT 到 - 未必代表這條 connection 死了 (也可能純粹是
                    // 沒 event 可送、瀏覽器又沒送 ping 過來的正常 idle 狀態),
                    // 主動送一個 ping 探一探。如果這條 connection 還活著,
                    // 瀏覽器的 WebSocket 實現會自動回 pong, 下一次 loop 再進來
                    // 就會在下面收到、reset 計數器。連續 timeout 達
                    // MAX_CONSECUTIVE_TIMEOUTS 次都探不到任何回應, 才當死。
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                        Log.i(TAG, "WebSocket idle timeout x" + consecutiveTimeouts
                                + " with no response - treating as dead connection");
                        return;
                    }
                    try {
                        sendFrame((byte) 0x89, new byte[0]); // ping
                    } catch (IOException sendFailed) {
                        // send 都失敗, 這條 connection 一定死了, 不用等下一round timeout。
                        return;
                    }
                    continue;
                }
                if (b0 == -1) return;
                consecutiveTimeouts = 0;
                int b1 = in.read();
                if (b1 == -1) return;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                // 2026-08 修正: 之前這兩個分支裡面的 in.read() 完全沒檢查 -1 (EOF) ——
                // 如果連線剛好在讀 16-bit/64-bit length 的時候斷了, `-1 & 0xFF` 會
                // 變成 255, 靜靜地拿到一個錯誤的 length 值而不是被發現是 EOF, 接著
                // 下去可能用著一個垃圾 length 去讀 payload, 有機會卡死或者讀入垃圾
                // 資料。現在改用 readByteOrThrow(), 一旦撞到 EOF 就立刻拋
                // IOException, 交回 handleUpgrade() 那一層現有的 catch (IOException e)
                // 接住, 和一般連線中斷沒差別地結束這個 loop。
                if (len == 126) {
                    len = (readByteOrThrow(in) << 8) | readByteOrThrow(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByteOrThrow(in);
                    }
                }

                // 2026-08 新增: 之前這裡沒對 len 做任何上限檢查 —— 一個惡意 client
                // 可以送一個 opcode=127 (64-bit length) 的 frame header, 聲稱 payload
                // 有幾 GB, `new byte[(int) len]` 就算 len cast 到 int 沒溢出都可以立刻
                // 讓這條 pool thread 拋 OutOfMemoryError (Error, 接不住)。這個 panel
                // 從頭到尾都不期望瀏覽器送任何有意義的 data frame 回來 (見上面
                // class javadoc), 所以上限可以定得多保守都行 - 1MB 已經遠超任何
                // 這個 panel 會用到的入站 frame (ping/pong payload 通常只有幾個 byte)。
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
