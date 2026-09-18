package com.open.alpha2;

import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Hand-rolled RFC 6455 WebSocket *client* that talks to the official 小智 (XiaoZhi)
 * cloud service (xiaozhi.me) - the mirror-image role of {@link WebSocketServer}, which
 * only ever accepts incoming connections from the browser control panel. This class
 * dials *out* to a remote server instead.
 *
 * Protocol reference: https://github.com/78/xiaozhi-esp32/blob/main/docs/websocket.md
 * and https://github.com/78/xiaozhi-esp32/blob/main/docs/mcp-protocol.md - this project
 * has no ESP32 hardware, but the wire protocol between "device" and xiaozhi.me is
 * transport-agnostic, so this robot can speak it directly as the "device" role.
 *
 * PHASE 1 SCOPE: this only implements the JSON text-frame side of the protocol -
 * handshake ("hello"), and dispatching STT/LLM/TTS/MCP/system/alert messages to
 * {@link EventBus}. It deliberately does NOT send/receive binary Opus audio frames yet
 * (see isAudioSupported()/XiaozhiController's "supported" endpoint).
 *
 * Matches this codebase's zero-third-party-dependency policy: only java.net.Socket +
 * javax.net.ssl (both part of the Android framework) and org.json (bundled with
 * Android). No OkHttp/Java-WebSocket/etc.
 */
public class XiaozhiClient {
    private static final String TAG = "XiaozhiClient";
    private static final int PROTOCOL_VERSION = 1;
    private static final int HELLO_TIMEOUT_MS = 10_000;

    /** Pushed to {@link EventBus} under these types as messages arrive/state changes. */
    public static final String EVT_STATE = "xiaozhi_state";     // connecting/connected/disconnected/error
    public static final String EVT_STT = "xiaozhi_stt";
    public static final String EVT_LLM = "xiaozhi_llm";
    public static final String EVT_TTS = "xiaozhi_tts";
    public static final String EVT_MCP = "xiaozhi_mcp";
    public static final String EVT_SYSTEM = "xiaozhi_system";
    public static final String EVT_ALERT = "xiaozhi_alert";
    public static final String EVT_CUSTOM = "xiaozhi_custom";

    /** Implemented by whoever owns the AIDL robot backend, so incoming MCP
     *  tools/list and tools/call requests can be answered without XiaozhiClient itself
     *  knowing anything about Alpha2RobotApi/LynxRobotApi. Wired up by MainActivity. */
    public interface McpBridge {
        /** Returns the JSON-RPC 2.0 "result" object (as a JSONObject) for a tools/list
         *  request. XiaozhiClient wraps this in the envelope and session_id itself. */
        JSONObject listTools() throws JSONException;

        /** Executes one tool call and returns the JSON-RPC 2.0 "result" object
         *  (content/isError shape, see mcp-protocol.md). Must not throw for
         *  "tool failed" - encode failure in isError instead; throwing here becomes a
         *  JSON-RPC "error" envelope reserved for protocol-level problems. */
        JSONObject callTool(String name, JSONObject arguments) throws JSONException;
    }

    /** PHASE 2: implemented by MainActivity (backed by XiaozhiAudioController), so
     *  XiaozhiClient can hand off incoming Opus binary frames without knowing anything
     *  about AudioRecord/AudioTrack/the Opus JNI layer - mirrors the McpBridge
     *  delegation pattern above. Called on the read-loop thread; must not block. */
    public interface AudioSink {
        void onIncomingOpusFrame(byte[] opusData);
    }

    /** PHASE 4 (小智常開/auto mode): notifies MainActivity of every server->device TTS
     *  state transition ("start"/"stop"/"sentence_start"), so it can implement the
     *  "auto-continue" behaviour xiaozhi-esp32's own firmware does locally - see
     *  websocket.md's "Speaking -> Idle" transition: "Server sends
     *  {"type":"tts","state":"stop"}. When auto-continue is enabled the device
     *  transitions back to Listening; otherwise it returns to Idle." That decision is
     *  made *device-side*, not by the server, so this robot has to replicate it itself:
     *  when auto mode is on and TTS finishes, re-issue mic/start so the conversation
     *  keeps going without the person pressing the mic button again. Called on the
     *  read-loop thread; must not block. stateValue is the raw "state" string
     *  ("start"/"stop"/"sentence_start"). */
    public interface TtsStateListener {
        void onTtsState(String stateValue);
    }

    /** Fired when the read loop exits because of an *unexpected* drop - i.e. the same
     *  condition that already triggers the EVT_STATE "disconnected"/"connection lost"
     *  publish below (see readLoop()'s finally block's "wasOpen" check), just exposed
     *  as a direct callback too so MainActivity can drive reconnection without having
     *  to separately subscribe to EventBus and pattern-match its JSON. Not called for
     *  a user-initiated disconnect() - see disconnect()'s own early exit before this
     *  would fire. Called on the read-loop thread; must not block. */
    public interface DisconnectListener {
        void onUnexpectedDisconnect();
    }

    private final String deviceId;
    private final String clientId;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile McpBridge mcpBridge;
    private volatile AudioSink audioSink;
    private volatile TtsStateListener ttsStateListener;
    private volatile DisconnectListener disconnectListener;
    private volatile Socket socket;
    private volatile OutputStream out;
    // vision explain 的真正 URL/token 不是寫死常數 - 對照官方
    // mcp-protocol.md 原文 ("initialize" 章節), server 送過來的 "initialize" request
    // 的 params.capabilities.vision 裡面夾著 device 應該用來 POST 照片的
    // url/token, 每次 session 都可能不同 (自架 server 實測回報的 vision url
    // 就是那個 server 自己的本地地址, 每個部署都不同)。這兩個 field 由 handleMcpMessage()
    // 的 case "initialize" 填, 給 MainActivity 的 xiaozhiTakePhotoAndExplain() 讀
    // (getVisionUrl()/getVisionToken()) - 沒收過 initialize request (例如用戶還沒
    // 連接過就試 take_photo) 就是 null, caller 要 fallback 用回自己那個
    // DEFAULT_VISION_URL 常數。
    private volatile String sessionId;
    private volatile boolean open = false;
    private volatile String visionUrl;
    private volatile String visionToken;

    public XiaozhiClient(String deviceId) {
        this.deviceId = deviceId;
        // Client-Id must persist across reconnects within a process lifetime but the
        // protocol doc treats it as "reset when NVS is erased or firmware re-flashed" -
        // this robot has no NVS equivalent, so a fresh UUID per XiaozhiClient instance
        // (i.e. per app process launch) is the closest analogue.
        this.clientId = UUID.randomUUID().toString();
    }

    public void setMcpBridge(McpBridge bridge) {
        this.mcpBridge = bridge;
    }

    public void setAudioSink(AudioSink sink) {
        this.audioSink = sink;
    }

    public void setTtsStateListener(TtsStateListener listener) {
        this.ttsStateListener = listener;
    }

    public void setDisconnectListener(DisconnectListener listener) {
        this.disconnectListener = listener;
    }

    public boolean isOpen() {
        return open;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Server-provided vision explain endpoint from the most recent "initialize" MCP
     *  request's params.capabilities.vision (see handleMcpMessage()'s case
     *  "initialize" for where these get set). Null if no initialize request has been
     *  received yet in this connection (e.g. server hasn't reached MCP setup, or this
     *  server variant doesn't advertise a vision capability at all) - caller should
     *  fall back to a hardcoded default in that case rather than failing outright. */
    public String getVisionUrl() {
        return visionUrl;
    }

    public String getVisionToken() {
        return visionToken;
    }

    /** Runtime capability check for the audio (Opus) half of this feature, kept here
     *  rather than in MainActivity so both the "/api/xiaozhi/supported" endpoint and any
     *  future audio-path code guard against the exact same condition. NDK builds of
     *  libopus in wide circulation today (e.g. theeasiestway/android-opus-codec) target
     *  a minimum runtime of API 21 (Lollipop) or newer - see NDK r23+ release notes,
     *  which dropped KitKat (API 19/20) support outright. This app's minSdkVersion stays
     *  19 so it still *installs* on Android 4.4 hardware, but the Opus-dependent audio
     *  session must not even attempt to load the native library below API 21, or it
     *  risks UnsatisfiedLinkError/dlopen failures rather than a clean "not supported"
     *  response. Text-only XiaoZhi chat (this Phase 1 client) has no such restriction -
     *  it's pure Java/JSON over a plain socket. */
    public static boolean isAudioSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /**
     * Opens the connection: TCP/TLS connect, HTTP Upgrade handshake, send "hello", wait
     * for the server's "hello" reply, then hand off to a background read loop. Returns
     * once the handshake either succeeds or fails/times out - callers get a definite
     * connected/not-connected answer rather than firing into the void.
     *
     * @param wsUrl        e.g. "wss://api.xiaozhi.me/v1/" - the official console
     *                     (https://xiaozhi.me/console/) shows the exact endpoint + token
     *                     for a given device registration.
     * @param accessToken  sent as "Authorization: Bearer <token>".
     */
    public synchronized void connect(String wsUrl, String accessToken) throws IOException {
        if (open) {
            throw new IOException("already connected (call disconnect() first)");
        }
        if (!connecting.compareAndSet(false, true)) {
            throw new IOException("connect already in progress");
        }
        try {
            EventBus.get().publish(EVT_STATE, "{\"state\":\"connecting\"}");
            ParsedUrl url = ParsedUrl.parse(wsUrl);

            NetLog.out("xiaozhi-ws", wsUrl);
            Socket rawSocket = new Socket(url.host, url.port);
            rawSocket.setTcpNoDelay(true);
            if (url.secure) {
                // 用 XiaozhiTrustAllSsl 的 factory 代替
                // SSLSocketFactory.getDefault() - 後者跟系統 CA store, 在部分
                // Android 5.1 (API 22) 機上會撞
                // java.security.cert.CertPathValidatorException: Trust anchor
                // for certification path not found (詳見 XiaozhiTrustAllSsl
                // 的 class javadoc)。
                socket = XiaozhiTrustAllSsl.getTrustAllSocketFactory()
                        .createSocket(rawSocket, url.host, url.port, true);
            } else {
                socket = rawSocket;
            }

            String wsKey = generateWebSocketKey();
            OutputStream rawOut = socket.getOutputStream();
            InputStream rawIn = socket.getInputStream();

            String request = "GET " + url.pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: " + url.host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Authorization: Bearer " + accessToken + "\r\n"
                    + "Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                    + "Device-Id: " + deviceId + "\r\n"
                    + "Client-Id: " + clientId + "\r\n"
                    + "\r\n";
            rawOut.write(request.getBytes(StandardCharsets.UTF_8));
            rawOut.flush();

            readHttpUpgradeResponse(rawIn, wsKey);
            out = rawOut;
            open = true;

            sendJson(buildHelloMessage());

            // Block the caller until the server's own "hello" arrives (or times out),
            // same contract xiaozhi-esp32's device firmware uses (10s default) - see
            // websocket.md "If no valid hello arrives within the timeout ... the
            // connection is considered failed". Everything after this point (STT/LLM/
            // TTS/MCP dispatch) happens asynchronously on the read loop thread.
            final Object helloLock = new Object();
            final boolean[] helloReceived = {false};
            final String[] helloError = {null};

            Thread readThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(rawIn, helloLock, helloReceived, helloError);
                }
            }, "XiaozhiClient-read");
            readThread.setDaemon(true);

            // Holding helloLock across both starting the read thread and the initial
            // wait() call closes a race where the read thread could receive the
            // server's hello and call notifyAll() *before* this thread ever reaches
            // wait() - a notify with nobody waiting yet is simply lost, which would
            // otherwise make this thread block for the full HELLO_TIMEOUT_MS even
            // though the hello genuinely arrived in time. Looping on the condition
            // (rather than a single if+wait) additionally guards against spurious
            // wakeups, which wait() is permitted to produce without a real notify.
            synchronized (helloLock) {
                readThread.start();
                long deadline = System.currentTimeMillis() + HELLO_TIMEOUT_MS;
                while (!helloReceived[0] && helloError[0] == null) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        helloLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!helloReceived[0]) {
                closeQuietly();
                String reason = helloError[0] != null ? helloError[0] : "timed out waiting for server hello";
                EventBus.get().publish(EVT_STATE, "{\"state\":\"error\",\"message\":\"" + jsonEscape(reason) + "\"}");
                throw new IOException(reason);
            }

            EventBus.get().publish(EVT_STATE, "{\"state\":\"connected\",\"sessionId\":\""
                    + jsonEscape(String.valueOf(sessionId)) + "\"}");
            Log.i(TAG, "Connected, session_id=" + sessionId);
        } catch (IOException e) {
            closeQuietly();
            EventBus.get().publish(EVT_STATE, "{\"state\":\"error\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}");
            throw e;
        } finally {
            connecting.set(false);
        }
    }

    public synchronized void disconnect() {
        if (!open) return;
        try {
            sendCloseFrame();
        } catch (IOException ignored) {
        }
        closeQuietly();
        EventBus.get().publish(EVT_STATE, "{\"state\":\"disconnected\"}");
    }

    /** Sends a device->server text message that's already-valid JSON (caller builds it
     *  with org.json). Used both internally (hello) and by MainActivity for
     *  "listen"/"abort" control messages the browser UI triggers. */
    public void sendJson(JSONObject msg) throws IOException {
        OutputStream o = out;
        if (o == null || !open) {
            throw new IOException("not connected");
        }
        // "listen"/"abort" 這類控制訊息量很少 (每次對話一兩個), 不會 flood log。
        Log.i(TAG, "Sending: " + msg.toString());
        byte[] payload = msg.toString().getBytes(StandardCharsets.UTF_8);
        sendFrame(o, (byte) 0x1, payload); // text frame, masked (client->server requirement)
    }

    /** PHASE 2: sends one device->server binary (Opus) audio frame - called from
     *  XiaozhiAudioController's capture thread via the EncodedFrameSink callback, not
     *  from the read-loop thread, so sendFrame()'s own synchronized block (see Frame
     *  I/O section below) is what keeps this safe to interleave with sendJson() calls
     *  happening concurrently from HTTP-request threads (e.g. a browser-triggered
     *  "listen stop" arriving mid-utterance). */
    public void sendAudioFrame(byte[] opusData) throws IOException {
        OutputStream o = out;
        if (o == null || !open) {
            throw new IOException("not connected");
        }
        sendFrame(o, (byte) 0x2, opusData); // binary frame, masked
    }

    /** Tells the server the device is about to start streaming mic audio - required
     *  before the server will treat incoming binary frames as a new utterance rather
     *  than stray data (see websocket.md's "listen" message).
     *
     *  用 mode="auto": 根據 websocket.md 6.3 節 auto-mode state diagram 和
     *  第 9 節 example message flow: 送了 listen start 之後 device 只管
     *  streaming binary Opus frames, 完全不用自己送 listen stop - server 側自己做
     *  VAD (語音活動偵測), 偵測到一句話完了就自動觸發 STT 並送回 stt/llm/tts 訊息,
     *  完事之後還會自動由 kDeviceStateSpeaking 轉回 kDeviceStateListening (若
     *  auto-continue), 這才是最貼近這台機「不用按鍵、開了那個 toggle 就可以持續
     *  對話」這個用戶體驗的正確 mode。
     *  (manual mode 是設計給「按住鍵才錄、放手就立刻送 listen stop」用的，
     *  無自動斷句，不合這裡。) */
    public void sendListenStart() throws IOException {
        sendListenControl("start", "auto");
    }

    public void sendListenStop() throws IOException {
        sendListenControl("stop", null);
    }

    /** PHASE 4 (text input): sends a piece of typed text as if it were a recognized
     *  utterance, using the device->server "Wake Word Detected" message shape from
     *  websocket.md section 4.1 #4 - {"type":"listen","state":"detect","text":"..."}.
     *
     *  This is a deliberate protocol repurposing, not a documented text-input channel:
     *  the official protocol has no dedicated "type the question" message. "detect" is
     *  specced for a device's *local* wake-word engine reporting what it heard before
     *  the server takes over: {@code https://github.com/78/xiaozhi-esp32/blob/main/docs/websocket.md}
     *  section 4.1 #4 documents it as "Sent by the device when the local wake word
     *  detector fires" with an example carrying free-form text ("Hi XiaoZhi").
     *
     *  長文字要帶 "source":"text" (非官方文檔欄位, 第三方實作有):
     *  server 用它分辨 wake-word 短句定打字完整句, 無就會套「應該好短」驗證
     *  ("detect is only for wake words")。
     *
     *  完整格式還要帶 "session_id" (同 sendMcpEnvelope() pattern,
     *  sessionId 由 hello 拿到): 無會被當匿名事件跌回預設驗證規則。 */
    public void sendListenDetectText(String text) throws IOException {
        try {
            JSONObject msg = new JSONObject();
            if (sessionId != null) msg.put("session_id", sessionId);
            msg.put("type", "listen");
            msg.put("state", "detect");
            msg.put("text", text);
            msg.put("source", "text");
            sendJson(msg);
        } catch (JSONException e) {
            throw new IOException("failed to build listen-detect message", e);
        }
    }

    /** listen 三件套共用裝配（start/stop/detect-text 除外各自加料）。 */
    private void sendListenControl(String state, String mode) throws IOException {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "listen");
            msg.put("state", state);
            if (mode != null) msg.put("mode", mode);
            sendJson(msg);
        } catch (JSONException e) {
            throw new IOException("failed to build listen-" + state + " message", e);
        }
    }

    // ---------------- Handshake ----------------

    /** Builds the device->server "hello" handshake message. Declared as throwing
     *  IOException (not JSONException) even though the JSONException this could in
     *  theory raise from JSONObject.put() never actually happens in practice - every
     *  key/value here is a hardcoded literal, not parsed from untrusted input - so
     *  there's nothing meaningful a caller could do differently for a JSONException
     *  vs any other connect-time IOException. */
    private JSONObject buildHelloMessage() throws IOException {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", PROTOCOL_VERSION);
            // "response_mode":"auto" (官方 websocket.md 無文檔, 但實測行得通的
            // 第三方實作有帶；server 可能用它判斷 device 是否支援文字輸入，
            // 無就 detect 文字訊息完全無回應)。
            hello.put("response_mode", "auto");
            JSONObject features = new JSONObject();
            features.put("mcp", true);
            hello.put("features", features);
            hello.put("transport", "websocket");
            JSONObject audioParams = new JSONObject();
            // Declared here to match the protocol's expected hello shape even though
            // Phase 1 doesn't stream audio yet - the server only needs this to negotiate
            // parameters for when audio starts flowing, and omitting it entirely risks
            // servers that assume audio_params is always present.
            audioParams.put("format", "opus");
            audioParams.put("sample_rate", 16000);
            audioParams.put("channels", 1);
            audioParams.put("frame_duration", 60);
            hello.put("audio_params", audioParams);
            return hello;
        } catch (JSONException e) {
            throw new IOException("failed to build hello message", e);
        }
    }

    private void readHttpUpgradeResponse(InputStream in, String expectedKey) throws IOException {
        StringBuilder headerText = new StringBuilder();
        int consecutiveNewlines = 0;
        // Read byte-by-byte until the blank line ("\r\n\r\n") that ends the HTTP
        // response headers - mirrors the caution in WebSocketServer.readByteOrThrow()
        // about not trusting -1/EOF mid-parse.
        while (consecutiveNewlines < 4) {
            int b = in.read();
            if (b == -1) throw new IOException("connection closed during HTTP upgrade handshake");
            headerText.append((char) b);
            if (b == '\r' || b == '\n') {
                consecutiveNewlines++;
            } else {
                consecutiveNewlines = 0;
            }
            if (headerText.length() > 16384) {
                throw new IOException("HTTP upgrade response headers too large");
            }
        }
        String response = headerText.toString();
        String firstLine = response.substring(0, response.indexOf("\r\n"));
        if (!firstLine.contains("101")) {
            throw new IOException("server rejected WebSocket upgrade: " + firstLine.trim());
        }
        String acceptHeader = extractHeader(response, "sec-websocket-accept");
        String expectedAccept = computeAccept(expectedKey);
        if (acceptHeader == null || !acceptHeader.trim().equals(expectedAccept)) {
            throw new IOException("Sec-WebSocket-Accept mismatch (possible proxy/protocol issue)");
        }
    }

    private static String extractHeader(String rawHeaders, String nameLower) {
        for (String line : rawHeaders.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(java.util.Locale.US).equals(nameLower)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    // ---------------- Read loop (background thread) ----------------

    private void readLoop(InputStream in, Object helloLock, boolean[] helloReceived, String[] helloError) {
        // 獨立記下「這次跳出 loop 是不是因為收到 server 的 close frame
        // (0x8)」, 用獨立旗標分辨 server 主動 close 同用戶 disconnect。
        final boolean[] serverClosed = {false};
        try {
            readLoopBody: while (open) {
                Frame frame = readFrame(in);
                if (frame == null) break; // EOF/closed

                switch (frame.opcode) {
                    case 0x1: // text
                        handleTextMessage(new String(frame.payload, StandardCharsets.UTF_8),
                                helloLock, helloReceived);
                        break;
                    case 0x2: { // binary (Opus audio)
                        // PHASE 2: hands the raw Opus payload to whatever AudioSink
                        // MainActivity wired up (see setAudioSink()) - XiaozhiClient
                        // itself has no audio/codec knowledge, matching how MCP tool
                        // calls are delegated to McpBridge rather than handled inline.
                        AudioSink sink = audioSink;
                        if (sink != null) {
                            sink.onIncomingOpusFrame(frame.payload);
                        }
                        break;
                    }
                    case 0x8: // close
                        // close frame payload 通常帶 2-byte close code (可選再加
                        // UTF-8 reason, 見 RFC 6455 §5.5.1) - 盡量解讀出來幫診斷。
                        Log.w(TAG, "Server sent WebSocket close frame" + describeCloseFrame(frame.payload));
                        // 收到 close 要回一個 close 完成雙向 handshake (RFC 6455),
                        // 再用 labeled break 立刻跳出 read loop (不是只跳 switch),
                        // 等 finally 可以立刻執行 (觸發重連)。
                        //
                        // 用獨立 serverClosed 旗標記「server 主動 close」，
                        // 不靠 open 共用旗標 (同用戶 disconnect 分辨)。
                        serverClosed[0] = true;
                        try {
                            sendFrame(out, (byte) 0x8, new byte[0]);
                        } catch (IOException ignored) {
                            // 對方可能已經全關了這個 socket - 送不到也沒關係, 反正
                            // 已經跳出去做 cleanup 了。
                        }
                        open = false;
                        break readLoopBody;
                    case 0x9: // ping -> pong
                        try {
                            sendFrame(out, (byte) 0xA, frame.payload);
                        } catch (IOException e) {
                            open = false;
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (open) {
                Log.i(TAG, "Read loop ended: " + e.getMessage());
            }
        } finally {
            // "open 還未被改過 (真正意外, 例如 IOException) 或者 serverClosed"
            // 兩種都算意外斷線，觸發重連。用戶自己 disconnect() 不經這條路。
            boolean wasUnexpected = open || serverClosed[0];
            open = false;
            closeQuietly();
            synchronized (helloLock) {
                if (!helloReceived[0]) {
                    helloError[0] = "connection closed before server hello";
                    helloLock.notifyAll();
                }
            }
            if (wasUnexpected) {
                // Only fires for an *unexpected* drop - disconnect() already publishes
                // its own "disconnected" state and calls closeQuietly() itself, so this
                // avoids a duplicate/misleading event on a user-initiated close.
                EventBus.get().publish(EVT_STATE, "{\"state\":\"disconnected\",\"reason\":\"connection lost\"}");
                DisconnectListener listener = disconnectListener;
                if (listener != null) {
                    listener.onUnexpectedDisconnect();
                }
            }
        }
    }

    /** Best-effort decode of a WebSocket close frame's payload for logging (RFC 6455
     *  §5.5.1: optional 2-byte big-endian close code, optionally followed by a UTF-8
     *  reason string). Returns an empty string if the payload is absent/malformed -
     *  this exists purely to make "why did the server close on us" diagnosable from
     *  logcat, not to drive any behavioural decision, so it deliberately never throws. */
    private static String describeCloseFrame(byte[] payload) {
        if (payload == null || payload.length < 2) return "";
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        String reason = "";
        if (payload.length > 2) {
            try {
                reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Malformed UTF-8 in the reason - still report the code, just without
                // a reason string.
            }
        }
        return " (code=" + code + (reason.isEmpty() ? "" : ", reason=\"" + reason + "\"") + ")";
    }

    private void handleTextMessage(String json, Object helloLock, boolean[] helloReceived) {
        JSONObject msg;
        try {
            msg = new JSONObject(json);
        } catch (JSONException e) {
            Log.w(TAG, "Malformed JSON from server, ignoring: " + e.getMessage());
            return;
        }
        String type = msg.optString("type", null);
        if (type == null) {
            Log.w(TAG, "Message missing 'type', ignoring: " + json);
            return;
        }

        if (!helloReceived[0] && "hello".equals(type) && "websocket".equals(msg.optString("transport"))) {
            sessionId = msg.optString("session_id", null);
            synchronized (helloLock) {
                helloReceived[0] = true;
                helloLock.notifyAll();
            }
            return;
        }

        switch (type) {
            case "stt":
                // 區分 server 完全沒收到 audio / 收到但辨識不到 / 辨識到但 llm/tts 無跟上。
                Log.i(TAG, "STT result: " + msg.optString("text"));
                EventBus.get().publish(EVT_STT, "{\"text\":\"" + jsonEscape(msg.optString("text")) + "\"}");
                break;
            case "llm":
                Log.i(TAG, "LLM response: emotion=" + msg.optString("emotion") + " text=" + msg.optString("text"));
                EventBus.get().publish(EVT_LLM, "{\"emotion\":\"" + jsonEscape(msg.optString("emotion"))
                        + "\",\"text\":\"" + jsonEscape(msg.optString("text")) + "\"}");
                break;
            case "tts": {
                String ttsState = msg.optString("state");
                Log.i(TAG, "TTS state: " + ttsState + " text=" + msg.optString("text"));
                EventBus.get().publish(EVT_TTS, "{\"state\":\"" + jsonEscape(ttsState)
                        + "\",\"text\":\"" + jsonEscape(msg.optString("text")) + "\"}");
                TtsStateListener listener = ttsStateListener;
                if (listener != null) {
                    listener.onTtsState(ttsState);
                }
                break;
            }
            case "mcp":
                handleMcpMessage(msg.optJSONObject("payload"));
                break;
            case "system":
                EventBus.get().publish(EVT_SYSTEM, "{\"command\":\"" + jsonEscape(msg.optString("command")) + "\"}");
                break;
            case "alert":
                EventBus.get().publish(EVT_ALERT, "{\"status\":\"" + jsonEscape(msg.optString("status"))
                        + "\",\"message\":\"" + jsonEscape(msg.optString("message"))
                        + "\",\"emotion\":\"" + jsonEscape(msg.optString("emotion")) + "\"}");
                break;
            case "custom":
                EventBus.get().publish(EVT_CUSTOM, String.valueOf(msg.opt("payload")));
                break;
            default:
                Log.i(TAG, "Unhandled message type '" + type + "': " + json);
                break;
        }
    }

    // ---------------- MCP (tool discovery / invocation) ----------------

    /** Server->device MCP payload is JSON-RPC 2.0. Only "initialize", "tools/list" and
     *  "tools/call" are meaningful for this robot (see mcp-protocol.md); anything else
     *  gets a generic JSON-RPC "method not found" error reply so the server's own
     *  request/response bookkeeping doesn't hang waiting for an id that never answers. */
    private void handleMcpMessage(JSONObject payload) {
        if (payload == null) return;
        EventBus.get().publish(EVT_MCP, "{\"direction\":\"in\",\"payload\":" + payload.toString() + "}");
        // Unlike EventBus's own publish() log (rate-limited to once per ~2s, meant for
        // "is anyone listening" diagnostics, not payload content), this logs every
        // single incoming MCP message at full detail - MCP traffic volume is low
        // enough (a handful of messages per session, not a firehose) that this doesn't
        // risk flooding logcat, and having the actual method/id/params in each line is
        // what's needed to diagnose retry/duplicate-request patterns from the server.
        Log.i(TAG, "MCP in: " + payload.toString());

        String method = payload.optString("method", null);
        if (method == null) {
            // A response to a device->server request we sent - Phase 1 never sends any
            // device-initiated MCP requests, so there's nothing to correlate this
            // against yet. Logged via the EVT_MCP publish above.
            return;
        }
        Object idRaw = payload.opt("id");

        try {
            JSONObject result;
            switch (method) {
                case "initialize": {
                    // 官方 mcp-protocol.md: backend->device initialize 的
                    // params.capabilities.vision 帶 device POST 照片做 explain 的
                    // url/token, 存下來給 takePhotoAndExplain 用。
                    JSONObject initParams = payload.optJSONObject("params");
                    JSONObject initCapabilities = initParams != null
                            ? initParams.optJSONObject("capabilities") : null;
                    JSONObject visionCap = initCapabilities != null
                            ? initCapabilities.optJSONObject("vision") : null;
                    if (visionCap != null) {
                        String url = visionCap.optString("url", null);
                        String token = visionCap.optString("token", null);
                        if (url != null && !url.isEmpty()) {
                            visionUrl = url;
                            visionToken = token;
                            Log.i(TAG, "Server-provided vision explain URL: " + url);
                        }
                    }
                    result = new JSONObject();
                    JSONObject serverInfo = new JSONObject();
                    serverInfo.put("name", "open-alpha2");
                    serverInfo.put("version", "1.0");
                    result.put("protocolVersion", "2024-11-05");
                    result.put("serverInfo", serverInfo);
                    // Must be {"tools": {}} (an object with a "tools" key), not an
                    // empty object - matches the shape shown in mcp-protocol.md's own
                    // initialize-response example. An empty capabilities object could
                    // read as "this device doesn't support tools at all", which would
                    // be a plausible explanation for a server that then keeps retrying
                    // tools/list expecting a capabilities-advertised response it never
                    // got.
                    JSONObject capabilities = new JSONObject();
                    capabilities.put("tools", new JSONObject());
                    result.put("capabilities", capabilities);
                    sendMcpResult(idRaw, result);
                    break;
                }
                case "tools/list": {
                    McpBridge bridge = mcpBridge;
                    if (bridge == null) {
                        sendMcpError(idRaw, -32000, "robot backend not ready");
                        break;
                    }
                    result = bridge.listTools();
                    sendMcpResult(idRaw, result);
                    break;
                }
                case "tools/call": {
                    McpBridge bridge = mcpBridge;
                    if (bridge == null) {
                        sendMcpError(idRaw, -32000, "robot backend not ready");
                        break;
                    }
                    JSONObject params = payload.optJSONObject("params");
                    String toolName = params != null ? params.optString("name", "") : "";
                    JSONObject arguments = params != null ? params.optJSONObject("arguments") : null;
                    if (arguments == null) arguments = new JSONObject();
                    // 耗時 tool (如 take_photo + vision round trip 可阻塞 1-2s)
                    // 不可以直接在 readLoop thread 同步做，否則阻塞期間回不到
                    // ping/收不到其他 message，底層 socket 會被判死
                    // ("SSLProtocolException: bad write retry")。搬去獨立
                    // background thread，readLoop 繼續讀下一個 frame。
                    // sendMcpResult()/sendMcpError() 經 synchronized sendFrame()，
                    // 跨 thread 本身 thread-safe。
                    final Object toolCallId = idRaw;
                    final String finalToolName = toolName;
                    final JSONObject finalArguments = arguments;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONObject toolResult = bridge.callTool(finalToolName, finalArguments);
                                sendMcpResult(toolCallId, toolResult);
                            } catch (Exception e) {
                                Log.e(TAG, "MCP tools/call '" + finalToolName
                                        + "' failed on background thread", e);
                                try {
                                    sendMcpError(toolCallId, -32603, "internal error: " + e.getMessage());
                                } catch (Exception inner) {
                                    Log.e(TAG, "Failed to send MCP error reply "
                                            + "for '" + finalToolName + "'", inner);
                                }
                            }
                        }
                    }, "XiaozhiMcpToolCall").start();
                    break;
                }
                default:
                    sendMcpError(idRaw, -32601, "method not found: " + method);
                    break;
            }
        } catch (Exception e) {
            // Any unexpected failure while building/executing the response still gets a
            // JSON-RPC error reply rather than silently dropping the request - a
            // dropped id leaves the server's own pending-request bookkeeping hanging.
            Log.e(TAG, "MCP dispatch error for method '" + method + "'", e);
            try {
                sendMcpError(idRaw, -32603, "internal error: " + e.getMessage());
            } catch (Exception inner) {
                Log.e(TAG, "Failed to send MCP error reply", inner);
            }
        }
    }

    private void sendMcpResult(Object id, JSONObject result) throws JSONException, IOException {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        putIdMatchingType(payload, id);
        payload.put("result", result);
        sendMcpEnvelope(payload);
    }

    private void sendMcpError(Object id, int code, String message) throws JSONException, IOException {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        putIdMatchingType(payload, id);
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        payload.put("error", error);
        sendMcpEnvelope(payload);
    }

    /** JSON-RPC ids are typically numeric but the spec allows string ids too - forward
     *  whatever type the server sent rather than assuming int, since org.json's put()
     *  overloads aren't interchangeable (an int id round-tripped as a String would break
     *  strict-typed correlation on some server implementations). */
    private static void putIdMatchingType(JSONObject payload, Object id) throws JSONException {
        if (id instanceof Integer || id instanceof Long) {
            payload.put("id", ((Number) id).longValue());
        } else if (id != null) {
            payload.put("id", id.toString());
        }
        // id == null (a JSON-RPC notification, no reply expected) - omit the field.
    }

    private void sendMcpEnvelope(JSONObject payload) throws JSONException, IOException {
        JSONObject envelope = new JSONObject();
        if (sessionId != null) envelope.put("session_id", sessionId);
        envelope.put("type", "mcp");
        envelope.put("payload", payload);
        Log.i(TAG, "MCP out: " + payload.toString());
        EventBus.get().publish(EVT_MCP, "{\"direction\":\"out\",\"payload\":" + payload.toString() + "}");
        sendJson(envelope);
    }

    // ---------------- Frame I/O ----------------

    private static final class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** Reads one server->client frame. Server frames are never masked (masking is a
     *  client-to-server-only requirement in RFC 6455), matching what WebSocketServer's
     *  own readLoop() assumes for the reverse direction. Fragmented frames (fin=false)
     *  are not expected from xiaozhi.me's JSON/audio messages in practice and are not
     *  reassembled here - same simplification WebSocketServer makes for browser->server. */
    private Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = readByteOrThrow(in);

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        if (len == 126) {
            len = (readByteOrThrow(in) << 8) | readByteOrThrow(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByteOrThrow(in);
            }
        }

        final long MAX_FRAME_PAYLOAD_BYTES = 8L * 1024 * 1024; // generous vs WebSocketServer's
        // 1MB cap - a single Opus-carrying TTS text frame is tiny, but this cap also
        // protects Phase 1 against a malformed/hostile response before the audio path
        // exists, so it stays conservative rather than unbounded.
        if (len < 0 || len > MAX_FRAME_PAYLOAD_BYTES) {
            throw new IOException("frame payload too large: " + len);
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(mask, read, 4 - read);
                if (n < 0) throw new IOException("EOF while reading frame mask");
                read += n;
            }
        }

        byte[] payload = new byte[(int) len];
        int read = 0;
        while (read < len) {
            int n = in.read(payload, read, (int) len - read);
            if (n < 0) throw new IOException("EOF while reading frame payload");
            read += n;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return new Frame(opcode, payload);
    }

    private static int readByteOrThrow(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("Unexpected EOF while reading WebSocket frame header");
        return b;
    }

    /** Sends one client->server frame. Unlike WebSocketServer.sendFrame() (server->
     *  client, never masked), RFC 6455 requires every client->server frame be masked
     *  with a fresh random 32-bit key. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private synchronized void sendFrame(OutputStream o, byte opcodeByte, byte[] payload) throws IOException {
        if (o == null) throw new IOException("not connected");
        byte firstByte = (byte) (0x80 | opcodeByte); // FIN + opcode
        int len = payload.length;
        o.write(firstByte);
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        if (len < 126) {
            o.write(0x80 | len); // MASK bit set + length
        } else if (len <= 0xFFFF) {
            o.write(0x80 | 126);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        } else {
            o.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                o.write((int) ((((long) len) >> (8 * i)) & 0xFF));
            }
        }
        o.write(mask);
        byte[] maskedPayload = new byte[len];
        for (int i = 0; i < len; i++) {
            maskedPayload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        o.write(maskedPayload);
        o.flush();
    }

    private void sendCloseFrame() throws IOException {
        OutputStream o = out;
        if (o != null) {
            sendFrame(o, (byte) 0x8, new byte[0]);
        }
    }

    private void closeQuietly() {
        open = false;
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
    }

    // ---------------- Handshake key derivation ----------------

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static String generateWebSocketKey() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
    }

    private static String computeAccept(String clientKey) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    // ---------------- URL parsing ----------------

    /** Minimal ws:// / wss:// URL parser - java.net.URI understands these schemes fine
     *  for component extraction, but doesn't know their default ports, so that part is
     *  filled in manually. */
    private static final class ParsedUrl {
        final String host;
        final int port;
        final boolean secure;
        final String pathAndQuery;

        private ParsedUrl(String host, int port, boolean secure, String pathAndQuery) {
            this.host = host;
            this.port = port;
            this.secure = secure;
            this.pathAndQuery = pathAndQuery;
        }

        static ParsedUrl parse(String wsUrl) throws IOException {
            try {
                URI uri = new URI(wsUrl);
                String scheme = uri.getScheme();
                if (scheme == null) throw new IOException("URL missing scheme (expected ws:// or wss://): " + wsUrl);
                boolean secure;
                if (scheme.equalsIgnoreCase("wss")) {
                    secure = true;
                } else if (scheme.equalsIgnoreCase("ws")) {
                    secure = false;
                } else {
                    throw new IOException("unsupported scheme '" + scheme + "' (expected ws:// or wss://)");
                }
                String host = uri.getHost();
                if (host == null) throw new IOException("URL missing host: " + wsUrl);
                int port = uri.getPort();
                if (port == -1) port = secure ? 443 : 80;
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                String query = uri.getRawQuery();
                String pathAndQuery = query != null ? path + "?" + query : path;
                return new ParsedUrl(host, port, secure, pathAndQuery);
            } catch (URISyntaxException e) {
                throw new IOException("malformed WebSocket URL: " + wsUrl, e);
            }
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}


