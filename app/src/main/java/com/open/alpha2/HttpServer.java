package com.open.alpha2;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A minimal, dependency-free HTTP/1.1 server built directly on java.net.ServerSocket -
 * deliberately no NanoHTTPD or other library, to match the SDK's own "Android framework +
 * JDK only" dependency policy.
 *
 * Responsibilities:
 *  - Serves static files (the HTML/JS/CSS control panel) out of assets/web/.
 *  - Dispatches "/api/*" requests to a pluggable {@link ApiHandler} (implemented by
 *    MainActivity, which owns the Alpha2RobotApi instance).
 *  - Detects a WebSocket upgrade request on "/ws" and hands the raw socket off to
 *    {@link WebSocketServer} for the RFC 6455 handshake and framing.
 *  - Dispatches "/stream/*" requests to a pluggable {@link StreamHandler} that owns the
 *    raw socket for the connection's whole lifetime (used by the MJPEG camera feed,
 *    which is a single long-lived response, not a request/response pair).
 *
 * One thread-per-connection via a cached thread pool; traffic on this panel is
 * low-volume/interactive so this is simpler and more robust than NIO here. A streaming
 * connection just holds one pool thread for as long as the client stays connected -
 * fine at this panel's expected scale of a handful of browser tabs, not fine at
 * internet scale, which is out of scope for a LAN test tool.
 */
public class HttpServer implements Runnable {
    private static final String TAG = "HttpServer";
    public static final int PORT = 8888;

    /**
     * For endpoints that need the raw POST body bytes rather than a UTF-8-decoded
     * String - currently just the walkie-talkie audio upload, where the body is
     * arbitrary binary PCM and UTF-8 decoding (as ApiHandler.handle()'s body param
     * does) would corrupt byte sequences that aren't valid UTF-8. Returns the
     * ApiResponse to send back, same as ApiHandler.
     */
    public interface RawUploadHandler {
        ApiResponse handle(String path, Map<String, String> query, byte[] body);
    }

    /** Implemented by MainActivity to answer "/api/..." calls with a JSON (or plain) body. */
    public interface ApiHandler {
        /** Returns the raw response body; contentType should usually be "application/json". */
        ApiResponse handle(String path, Map<String, String> query, String method, String body);
    }

    /**
     * Implemented by MainActivity to serve "/stream/..." requests that don't fit the
     * single-response ApiHandler model - the handler receives the still-open socket and
     * owns writing to it (headers included) until the client disconnects or the stream
     * ends on its own. Mirrors how WebSocketServer.handleUpgrade() owns the socket for
     * "/ws", just without the WebSocket framing/handshake.
     */
    public interface StreamHandler {
        void handle(String path, Map<String, String> query, java.net.Socket socket) throws IOException;
    }

    public static final class ApiResponse {
        public final int status;
        public final String contentType;
        public final String body;

        public ApiResponse(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public static ApiResponse ok(String json) {
            return new ApiResponse(200, "application/json; charset=utf-8", json);
        }

        public static ApiResponse error(String message) {
            return new ApiResponse(500, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    private final AssetManager assets;
    private final ApiHandler apiHandler;
    private final StreamHandler streamHandler;
    private final RawUploadHandler rawUploadHandler;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;
    private volatile IOException bindError;
    private final CountDownLatch bindLatch = new CountDownLatch(1);

    /**
     * 2026-08: TLS support (self-signed cert, see the now-deleted TlsSupport.java/
     * SelfSignedCert.java) was removed outright rather than kept as a dead optional
     * path - browsers on this device repeatedly rejected new TLS connections after the
     * very first page load ("SSLHandshakeException: certificate unknown"), and the
     * walkie-talkie mic feature that TLS existed for is permanently disabled in the UI
     * anyway (see app-mic.js). This is now the only constructor - plain HTTP only.
     */
    public HttpServer(AssetManager assets, ApiHandler apiHandler, StreamHandler streamHandler,
            RawUploadHandler rawUploadHandler) {
        this.assets = assets;
        this.apiHandler = apiHandler;
        this.streamHandler = streamHandler;
        this.rawUploadHandler = rawUploadHandler;
    }

    /**
     * Blocks until the listening socket is actually bound (or failed to bind) before
     * returning, instead of just submitting the accept-loop to the thread pool and
     * returning immediately. MainActivity's on-device WebView calls loadUrl() on
     * 127.0.0.1 right after this returns - without this wait, that first page load can
     * race the ServerSocket constructor running on the pool thread and fail with
     * connection-refused, especially on a slower first boot.
     */
    public void start() {
        running = true;
        pool.execute(this);
        try {
            bindLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (bindError != null) {
            Log.e(TAG, "Server failed to bind port " + PORT, bindError);
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "Listening on port " + PORT + " (plain HTTP)");
            bindLatch.countDown();
            while (running) {
                final Socket client = serverSocket.accept();
                // Capture the remote address HERE, immediately after accept() and
                // before any TLS handshake happens - not inside handleClient()'s catch
                // block. logcat_2026-07-30_05-28-17.txt showed that approach printing
                // "remote=null" for every single SSLHandshakeException: once a TLS
                // handshake fails, the underlying socket has typically already been
                // torn down by the SSL layer by the time the exception propagates back
                // out, and Socket.getRemoteSocketAddress() returns null for a closed
                // socket per its own contract - so logging it after the fact was
                // useless for exactly the failures we most wanted to identify. Right
                // after accept(), the socket is freshly connected and this is reliable.
                final String remoteAddr = String.valueOf(client.getRemoteSocketAddress());
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client, remoteAddr);
                    }
                });
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Server socket error", e);
            }
            bindError = e;
            bindLatch.countDown(); // unblock start() even on failure - don't hang the caller
        }
    }
    private void handleClient(Socket socket, String remoteAddr) {
        try {
            socket.setTcpNoDelay(true);
            // Keep-alive loop: serve as many requests as the client sends on this same
            // TCP/TLS connection instead of closing after one. This matters a lot more
            // here than on a plain-HTTP server: over TLS with a self-signed cert, every
            // *new* connection needs its own TLS handshake, and a browser's "I accept
            // this untrusted cert" decision from clicking through the initial "not
            // private" warning does not reliably cover every subsequent background
            // connection the same tab opens (fetch()/XHR pooling, the WebSocket
            // upgrade, ...) - some browsers re-validate those and reject outright with
            // no prompt at all (surfaces as SSLHandshakeException / "certificate_unknown"
            // server-side, exactly what shows up in logcat as repeated "handleClient
            // error" entries clustered around API calls). Reusing one already-accepted
            // connection for as many requests as possible avoids needing those extra
            // handshakes in the first place. Loop ends when the client sends "Connection:
            // close", disconnects, or a request can't be parsed.
            boolean keepAlive = true;
            while (keepAlive) {
                keepAlive = handleOneRequest(socket);
            }
        } catch (Exception e) {
            // SSLHandshakeException here ("certificate unknown" / "sslv3 alert
            // certificate unknown") is expected background noise from any TLS client
            // that opens a raw connection to this self-signed HTTPS server without
            // ever accepting the "not private" warning first (e.g. a background
            // reconnect attempt, a health-check/scanner tool, or a browser tab that
            // was never manually clicked through) - see the HTTPS section in
            // README.md. It is not a crash: the exception is fully caught here and
            // the socket is always closed in the finally block below regardless, so
            // this does not leak threads or sockets even if it happens hundreds of
            // times. remoteAddr is captured by the caller immediately after accept()
            // (see run()) rather than here - by the time a TLS handshake failure
            // reaches this catch block, the SSL layer has typically already torn the
            // underlying socket down, and socket.getRemoteSocketAddress() reliably
            // returns null for a closed socket at that point (confirmed from
            // logcat_2026-07-30_05-28-17.txt: every single occurrence logged
            // "remote=null" under the old approach, making that diagnostic useless).
            Log.e(TAG, "handleClient error (remote=" + remoteAddr + ")", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Serves exactly one HTTP request off an already-open (and, for TLS, already
     *  handshaken) socket. Returns true if the connection should stay open for another
     *  request (keep-alive), false if it should be closed - either because the client
     *  asked for that (Connection: close, or an HTTP/1.0 request with no keep-alive
     *  header), a WebSocket upgrade/stream/upload response already owns or closed the
     *  socket itself, or the client disconnected (readHttpLine returned null: no bytes
     *  at all for a fresh request line, i.e. a clean EOF between requests). */
    private boolean handleOneRequest(Socket socket) throws IOException {
        // Deliberately NOT wrapped in a BufferedReader: BufferedReader.readLine() fills
        // its own internal buffer eagerly and can silently consume bytes past the header
        // block (e.g. the client's first WebSocket frame, sent right after the upgrade
        // handshake). Since WebSocketServer needs the *exact* stream position where the
        // headers end, headers are read one byte at a time directly off the raw socket
        // stream instead.
        InputStream rawIn = socket.getInputStream();

        String requestLine = readHttpLine(rawIn);
        if (requestLine == null || requestLine.isEmpty()) {
            return false; // clean EOF between requests, or an empty line - close.
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return false;
        }
        String method = parts[0];
        String fullPath = parts[1];

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readHttpLine(rawIn)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }
        }

        // The client can explicitly ask this particular connection to close after this
        // response (still fully valid HTTP/1.1); honour that instead of always looping.
        String connectionHeader = headers.get("connection");
        boolean clientWantsClose = connectionHeader != null && connectionHeader.equalsIgnoreCase("close");

        // WebSocket upgrade goes to a completely separate framed protocol. rawIn is
        // guaranteed to be positioned exactly at the first byte after the blank line
        // terminating the headers, since nothing above touched a BufferedReader.
        String upgrade = headers.get("upgrade");
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            WebSocketServer.handleUpgrade(socket, rawIn, headers);
            return false; // WebSocketServer owns the socket lifecycle from here on.
        }

        String path = fullPath;
        String queryString = "";
        int qIdx = fullPath.indexOf('?');
        if (qIdx >= 0) {
            path = fullPath.substring(0, qIdx);
            queryString = fullPath.substring(qIdx + 1);
        }
        Map<String, String> query = parseQuery(queryString);

        // Streaming responses (MJPEG camera feed) own the socket from here on, same
        // as the WebSocket upgrade above - no Content-Length response is possible
        // for a feed of unknown/infinite length, so this can't go through the normal
        // ApiResponse/writeResponse path at all.
        if (path.startsWith("/stream/") && streamHandler != null) {
            Log.i(TAG, "Stream request: " + method + " " + path);
            try {
                streamHandler.handle(path.substring(8), query, socket);
            } catch (IOException e) {
                // Expected, routine noise, not a bug: fires every time a browser tab
                // navigates away, refreshes, or is closed while an MJPEG/mic stream is
                // still writing to it (typically surfaces as "Broken pipe"/EPIPE) -
                // confirmed from logcat_2026-07-27 that the stream handler's own
                // finally block (see handleCameraStream()/handleMicStream()) already
                // unsubscribes and releases the camera/mic correctly whenever this
                // fires, so there is nothing left to clean up here. Logged at debug
                // rather than info so it doesn't read like a warning worth chasing.
                Log.d(TAG, "Stream connection closed: " + e.getMessage());
            }
            return false; // streamHandler owns the socket lifecycle from here on.
        }

        byte[] rawBody = new byte[0];
        String lenStr = headers.get("content-length");
        if (lenStr != null) {
            int len;
            try {
                len = Integer.parseInt(lenStr.trim());
            } catch (NumberFormatException e) {
                // Malformed Content-Length header - can't trust anything about the
                // body that follows (or even how much of it there is), so close the
                // connection instead of trying to guess/recover.
                return false;
            }
            // 2026-08 新增: 之前這裡沒有上限, len 直接來自客戶端的 Content-Length 這個
            // header, 一個惡意或者損壞的請求 (例如 Content-Length: 2000000000) 會讓
            // `new byte[len]` 立刻拋 OutOfMemoryError —— OOM Error 不是 Exception,
            // handleClient() 那個 catch (Exception e) 接不住, 這個 pool thread 會直接
            // 死掉, connection 也不會 close。這個上限要夠大不能誤傷正常請求 (最大
            // 的正常 body 是 /upload/audio 那種 walkie-talkie PCM chunk, 看
            // AudioController/app-mic.js 都是幾十 KB 級別), 但要小於任何合理的單一
            // request body, 32MB 留有幾百倍餘裕。
            final int MAX_BODY_BYTES = 32 * 1024 * 1024;
            if (len < 0 || len > MAX_BODY_BYTES) {
                Log.w(TAG, "Rejecting request with Content-Length=" + len
                        + " (limit " + MAX_BODY_BYTES + ")");
                return false;
            }
            rawBody = new byte[len];
            int readTotal = 0;
            while (readTotal < len) {
                int n = rawIn.read(rawBody, readTotal, len - readTotal);
                if (n < 0) break;
                readTotal += n;
            }
            if (readTotal < len) {
                rawBody = java.util.Arrays.copyOf(rawBody, readTotal);
            }
        }

        OutputStream out = socket.getOutputStream();

        if (path.startsWith("/upload/") && rawUploadHandler != null) {
            Log.i(TAG, "Upload request: " + method + " " + path + " (" + rawBody.length + " bytes)");
            ApiResponse resp;
            try {
                resp = rawUploadHandler.handle(path.substring(8), query, rawBody);
            } catch (Exception e) {
                Log.e(TAG, "Upload handler threw", e);
                resp = ApiResponse.error("Upload handler error: " + e.getMessage());
            }
            writeResponse(out, resp.status, resp.contentType, resp.body.getBytes(StandardCharsets.UTF_8), !clientWantsClose);
            return !clientWantsClose;
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);

        if (path.startsWith("/api/")) {
            Log.i(TAG, "API request: " + method + " " + path + (queryString.isEmpty() ? "" : "?" + queryString));
            ApiResponse resp;
            try {
                resp = apiHandler.handle(path.substring(5), query, method, body);
                Log.i(TAG, "API response [" + resp.status + "]: " + path + " -> " + resp.body);
            } catch (Exception e) {
                Log.e(TAG, "API handler error for " + path, e);
                resp = ApiResponse.error(String.valueOf(e.getMessage()));
            }
            writeResponse(out, resp.status, resp.contentType, resp.body.getBytes(StandardCharsets.UTF_8), !clientWantsClose);
        } else {
            serveStatic(out, path, !clientWantsClose);
        }
        return !clientWantsClose;
    }

    private void serveStatic(OutputStream out, String path, boolean keepAlive) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        String assetPath = "web" + path;
        try (InputStream is = assets.open(assetPath)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            // No Cache-Control header used to be sent at all, which left every browser/
            // WebView free to apply its own heuristic caching to these .js/.css/.html
            // files - user-confirmed symptom: after updating this web UI (new APK
            // install), the browser kept serving an old cached copy of app-log.js, so
            // WebSocket events the new backend genuinely sent (visible in the raw event
            // log, which is itself driven by JS that happened to be unchanged) never
            // reached the *new* per-servo readout handler because that handler's code
            // wasn't in the stale cached file yet.
            // This control panel is always served fresh off the device's own assets/
            // (never a CDN, never meant to be offline-cached), so there is no upside to
            // caching it and real downside (silently stale UI logic after every
            // update) - explicitly forbid caching for every static response.
            writeResponse(out, 200, mimeType(path), buffer.toByteArray(), keepAlive, true);
        } catch (IOException notFound) {
            byte[] msg = ("Not found: " + path).getBytes(StandardCharsets.UTF_8);
            writeResponse(out, 404, "text/plain; charset=utf-8", msg, keepAlive, true);
        }
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body, boolean keepAlive)
            throws IOException {
        writeResponse(out, status, contentType, body, keepAlive, false);
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body, boolean keepAlive, boolean noCache)
            throws IOException {
        String statusText = status == 200 ? "OK" : status == 404 ? "Not Found" : "Error";
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        if (noCache) {
            // See serveStatic()'s javadoc comment above for why - covers index.html and
            // every app-*.js/style.css it references, plus the 404 body for completeness.
            header.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
            header.append("Pragma: no-cache\r\n");
        }
        header.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * Reads one CRLF- or LF-terminated line directly off the raw socket stream, one byte
     * at a time, without any internal read-ahead buffering. Slower than BufferedReader but
     * leaves the stream positioned exactly where the line ended - required so a following
     * WebSocket upgrade sees the correct first frame byte. Returns null on EOF with no data
     * read yet.
     */
    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(128);
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                lineBuf.write(b);
            }
        }
        if (!sawAny) {
            return null;
        }
        return new String(lineBuf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new HashMap<>();
        if (qs == null || qs.isEmpty()) {
            return map;
        }
        for (String pair : qs.split("&")) {
            int idx = pair.indexOf('=');
            try {
                if (idx >= 0) {
                    String k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    map.put(k, v);
                } else {
                    map.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
        return map;
    }
}
