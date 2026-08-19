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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

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
 * (see AIDL_REFERENCE.md-style caution: audio needs libopus, which has its own
 * Android-4-compatibility question to resolve separately - see
 * isAudioSupported()/XiaozhiController's "supported" endpoint). Standing this up first,
 * text-only, makes the connect/handshake/dispatch plumbing independently testable before
 * adding the audio codec layer on top.
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
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile McpBridge mcpBridge;
    private volatile AudioSink audioSink;
    private volatile TtsStateListener ttsStateListener;
    private volatile DisconnectListener disconnectListener;
    private volatile Socket socket;
    private volatile OutputStream out;
    // 2026-08 新增: vision explain 嘅真正 URL/token 唔係一個寫死嘅常數 - 對照官方
    // mcp-protocol.md 原文 ("initialize" 章節), server 送嚟嘅 "initialize" request
    // 個 params.capabilities.vision 入面先夾住 device 應該用嚟 POST 相片嘅
    // url/token, 每次 session 都可能唔同 (見 xinnan-tech/xiaozhi-esp32-server 一個
    // 自架 server 嘅實測 log, 佢報嘅 vision url 就係嗰個 server 自己嘅本地地址,
    // 每個部署都唔同, 冇一個放諸四海皆準嘅寫死值)。呢兩個 field 由 handleMcpMessage()
    // 嘅 case "initialize" 填, 俾 MainActivity 嘅 xiaozhiTakePhotoAndExplain() 讀
    // (getVisionUrl()/getVisionToken()) - 冇收過 initialize request (例如用戶未曾
    // 連接過就試 take_photo) 就係 null, caller 要 fallback 用返自己嗰個
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

            Socket rawSocket = new Socket(url.host, url.port);
            rawSocket.setTcpNoDelay(true);
            if (url.secure) {
                socket = ((SSLSocketFactory) SSLSocketFactory.getDefault())
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
        // 2026-08 診斷: 之前呢度冇 log, 令人查唔到 "listen start" 呢類控制訊息
        // 究竟有冇真係送咗出去 (同送咗啲乜 - 例如 mode/state 個值有冇打錯)。
        // "listen"/"abort" 呢類控制訊息量好少 (每次對話一兩個), 唔會 flood log。
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
     *  2026-08 修正: 之前呢度用 mode="manual", 但對照官方 xiaozhi-esp32 repo 嘅
     *  websocket.md 先發現 "manual" 呢個 mode 喺 device state diagram (6.4 節)
     *  入面係設計俾「撳住掣先錄、放手就即刻送 listen stop」呢種交互用嘅 - Idle ->
     *  Listening 靠用戶主動觸發 start, Listening -> Idle 亦都係靠用戶主動觸發 stop,
     *  中間唔存在 server 自動斷句呢回事。之前個實現冇對應嘅「講完自動送 stop」邏輯
     *  (mic 開咗就一直錄、一直當自己聽緊, 直到用戶自己撳熄先送 stop), 同 manual
     *  mode 嘅協議設計唔夾, 導致 server 一直等緊一個永遠唔嚟嘅 stop 訊號, 完全唔會
     *  觸發 STT - 呢個先係之前「講嘢完全冇反應」嘅真正原因 (mic 錄音本身、Opus
     *  encode、WebSocket send 呢幾層之前已經逐一驗證過冇問題)。
     *
     *  改用 mode="auto" 之後, 根據同一份文件 6.3 節嘅 auto-mode state diagram 同
     *  第 9 節嘅 example message flow: 送咗 listen start 之後 device 淨係管住
     *  streaming binary Opus frames, 完全唔使自己送 listen stop - server 側自己做
     *  VAD (語音活動偵測), 偵測到一句嘢完咗就自動觸發 STT 並送返 stt/llm/tts 訊息,
     *  完事之後仲會自動由 kDeviceStateSpeaking 轉返去 kDeviceStateListening (若
     *  auto-continue), 呢個先係最貼近呢隻機「唔使撳掣、開咗個 toggle 就可以持續
     *  對話」呢個用戶體驗嘅正確 mode。 */
    public void sendListenStart() throws IOException {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "listen");
            msg.put("state", "start");
            msg.put("mode", "auto");
            sendJson(msg);
        } catch (JSONException e) {
            throw new IOException("failed to build listen-start message", e);
        }
    }

    public void sendListenStop() throws IOException {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "listen");
            msg.put("state", "stop");
            sendJson(msg);
        } catch (JSONException e) {
            throw new IOException("failed to build listen-stop message", e);
        }
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
     *  2026-08 修正: 實測用官方 xiaozhi.me 撞到 server 主動拒絕長文字輸入 - 錯誤
     *  訊息 "detect is only for wake words, do not send long texts", 連 20 字都
     *  觸發。反編譯一個第三方「小智AI 安卓5.1 MCP修復版」apk (用戶提供, 佢打字輸入
     *  完全冇呢個長度限制) 嘅 classes.dex, 搵到佢送嘅其實係
     *  {"type":"listen","state":"detect","text":"...","source":"text"} - 多咗
     *  一個之前呢度冇加嘅 "source":"text" 欄位。呢個唔係官方 websocket.md 文檔化
     *  嘅欄位 (文檔淨係提到 type/state/text 三個), 但 server 側顯然會睇呢個欄位
     *  嚟分辨「呢個 detect 事件係嚟自本地 wake-word 引擎聽到嘅短句」定係「用戶
     *  打字輸入嘅完整句子」- 冇呢個標記, server 就將佢當做 wake-word 事件, 套用
     *  「應該好短」嗰條驗證規則。
     *
     *  2026-08 再修正 (加咗 source 之後實測仍然撞長度限制): 用 androguard 直接
     *  反編譯埋嗰個 apk 個 XiaoZhi.f(String) method 嘅 bytecode (唔淨係睇字串,
     *  睇實際點樣砌條 message), 先發現完整格式其實係
     *  {"session_id":"<值>","type":"listen","state":"detect","text":"...",
     *  "source":"text"} - 開頭仲有一個之前完全冇留意到嘅 "session_id" 欄位!
     *  之前呢度加咗 source 但冇加 session_id, 送出去嘅訊息冇夾住 session_id,
     *  server 側好可能因為攞唔到對應嘅 session context, 將呢個 message 當做一個
     *  匿名/唔完整嘅事件處理, 退返去用預設嘅「wake word 應該好短」驗證規則。
     *  依家補返呢個欄位 - 跟返 sendMcpEnvelope() 已有嘅 pattern (sessionId 由
     *  handleMcpMessage() 嗰邊 hello 訊息解析時攞到, 存喺呢個 class 嘅 field)。 */
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

    // ---------------- Handshake ----------------

    /** Builds the device->server "hello" handshake message. Declared as throwing
     *  IOException (not JSONException) even though the JSONException this could in
     *  theory raise from JSONObject.put() never actually happens in practice - every
     *  key/value here is a hardcoded literal, not parsed from untrusted input - so
     *  there's nothing meaningful a caller could do differently for a JSONException
     *  vs any other connect-time IOException. Wrapping it here keeps JSONException
     *  out of connect()'s method signature/catch clause entirely. */
    private JSONObject buildHelloMessage() throws IOException {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", PROTOCOL_VERSION);
            // 2026-08 新增: 反編譯一個用戶提供、實測打字輸入正常嘅第三方 apk
            // (package com.huihongcloud.xiaozhi) 嘅 hello message 組裝邏輯
            // (MainActivity.D() bytecode), 發現佢送嘅 hello 多咗一個之前呢度冇加
            // 嘅欄位: "response_mode":"auto" (喺 version 之後、features 之前)。
            // 官方 websocket.md 文檔冇提呢個欄位, 但可能就係 server 側判斷
            // 「呢個 device 支援文字輸入」定係「淨係支援語音」嘅其中一個依據 -
            // 冇呢個欄位, server 可能行緊一個預設/舊版行為, 令 detect 類型嘅文字
            // message 完全冇被處理 (真機 logcat 顯示打字訊息送到, HTTP 200, 但
            // server 完全冇回應任何 STT/LLM/TTS)。加返呢個欄位, 跟返實測行得通
            // 嘅 apk 一致。
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
        // 2026-08 新增: 獨立記低「呢次跳出 loop 係咪因為收到 server 嘅 close frame
        // (0x8)」, 唔再靠 finally block 嗰句 "boolean wasOpen = open" 去判斷 - 見
        // 落面 case 0x8 嘅 comment 解釋點解 wasOpen 呢個做法有 bug。
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
                        // 2026-08 修正: 之前呢度完全冇 log, 令 server 主動 close 連接
                        // (例如因為 timeout、驗證失效、或者伺服器端錯誤) 完全冇痕跡
                        // 可查 - 用戶反映「講嘢冇反應」, 追查落去先發現 mic capture
                        // loop 其實有觸發, 但 sendAudioFrame() 中途開始報 "not
                        // connected", 即係 WebSocket 喺對話中途俾 server 主動 close
                        // 咗, 但之前完全冇留低log解釋原因。close frame 嘅 payload 通常
                        // 帶住 2-byte close code (可選再加 UTF-8 reason string), 見
                        // RFC 6455 §5.5.1 - 呢度盡量解讀出嚟幫手診斷, 解讀唔到都好過
                        // 完全冇 log。
                        Log.w(TAG, "Server sent WebSocket close frame" + describeCloseFrame(frame.payload));
                        // 2026-08 再修正 (實測發現嘅第二層 bug): 之前呢度淨係設
                        // open=false, 冧咗個 while(open) 下次先會檢查, 但唔會令 loop
                        // 即刻跳出 - 個 loop 會繼續行去下一次 readFrame(in), 而
                        // readFrame() 本身可能一路 block 住等緊下一個永遠唔會到嘅
                        // frame (RFC 6455 要求收到 close frame 後回應返一個 close
                        // frame 先完成雙向 close handshake, 之前呢度冇回應, 令 socket
                        // 冇被正確關閉)。結果: sendAudioFrame() 已經開始報 "not
                        // connected" (證明 open 已經係 false), 但 readLoop() 嘅
                        // finally block (負責觸發 onUnexpectedDisconnect() ->
                        // 自動重連) 永遠行唔到, 令自動重連完全冇啟動過 - 呢個就係
                        // 「小智講咗拜拜之後就再無辦法語音通話, 要關重開先得」嘅
                        // 根本原因。而家呢度回應一個 close frame 完成 handshake,
                        // 再用 labeled break 即刻跳出成個 read loop (唔淨係跳出
                        // switch), 等 finally block 可以即刻執行。
                        //
                        // 2026-08 再再修正 (實測發現嘅第三層 bug): 上面呢個 labeled
                        // break 修法本身令 loop 成功跳出咗, 但跳出前呢度自己搶先將
                        // open 設做 false, 令 finally block 嗰句
                        // "boolean wasOpen = open" 攞到嘅係 false (因為 open 已經俾
                        // 呢度改咗), 於是 "if (wasOpen) { ...觸發重連... }" 嗰個
                        // condition 都判斷做 false, 完全跳過咗重連 - 同用戶自己 call
                        // disconnect() (先設 open=false 先關 socket) 嘅情況變到冇得
                        // 分辨。而家改用獨立嘅 serverClosed 旗標嚟標記「呢次係
                        // server 主動 close」, 唔再靠 open 呢個俾多個地方共用嘅
                        // 旗標做判斷。
                        serverClosed[0] = true;
                        try {
                            sendFrame(out, (byte) 0x8, new byte[0]);
                        } catch (IOException ignored) {
                            // 對方可能已經全關咗個 socket - 送唔到都冇所謂, 反正
                            // 已經跳緊出去做 cleanup。
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
            // 2026-08 修正: 之前呢度係 "boolean wasOpen = open" (喺 case 0x8 已經
            // 自己搶先將 open 設做 false 之後先讀), 而家改為
            // "open 仲未被搶先改過 (真正意外, 例如 IOException) 又或者係
            // serverClosed 呢個獨立旗標" - 兩種情況都算「意外斷線」, 應該觸發
            // onUnexpectedDisconnect() -> 自動重連。用戶自己 call disconnect()
            // 嗰種情況唔會經過呢個 read loop 嘅 case 0x8/serverClosed 呢條路,
            // 見 disconnect() 嘅實現。
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
     *  logcat (see the "2026-08 修正" comment at the case 0x8 call site), not to drive
     *  any behavioural decision, so it deliberately never throws. */
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
                // 2026-08 診斷: 之前呢度冇 log, 令人查唔到 server 側嘅語音辨識(STT)
                // 究竟有冇收到/辨識到任何嘢 - 「講嘢完全冇反應」呢個症狀, 冇呢句 log
                // 就分唔清係 server 完全冇收到 audio, 定係收到但辨識唔到內容, 定係
                // 辨識到但之後 llm/tts 冇跟住嚟。
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
                    // 2026-08 新增: 官方 mcp-protocol.md 明確咗 (Direction:
                    // backend -> device) 呢個 request 嘅 params.capabilities.vision
                    // 入面帶住 device 應該用嚟 POST 相片做 explain 嘅 url/token -
                    // 之前呢度完全冇讀呢部分, MainActivity 嗰邊一直靠自己寫死嘅
                    // DEFAULT_VISION_URL 常數, 撞咗 404 都摸唔到方向, 因為根本
                    // 揀錯咗個 domain (見 DEFAULT_VISION_URL 嘅 comment 完整
                    // 排查過程)。真正做法係由呢度攞返 server 話俾我哋知嘅真實
                    // url/token, 存低嚟俾 xiaozhiTakePhotoAndExplain() 用。
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
                    result = bridge.callTool(toolName, arguments);
                    sendMcpResult(idRaw, result);
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
    private synchronized void sendFrame(OutputStream o, byte opcodeByte, byte[] payload) throws IOException {
        if (o == null) throw new IOException("not connected");
        byte firstByte = (byte) (0x80 | opcodeByte); // FIN + opcode
        int len = payload.length;
        o.write(firstByte);
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
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
        new SecureRandom().nextBytes(nonce);
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
