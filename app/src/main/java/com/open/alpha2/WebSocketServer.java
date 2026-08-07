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

                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (in.read() & 0xFF);
                    }
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
