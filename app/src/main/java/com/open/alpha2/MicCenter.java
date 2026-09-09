package com.open.alpha2;

import com.ubtechinc.alpha.hardware.DirectLedController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Mic 包：持有旗＋搶佔 enforcer、releaseMic、/stream/mic、set_mic 端點、
 * walkie testtone/diagnose/play。
 *
 * 2026-09 由 MainActivity＋ApiDispatcher 整包搬出：
 * - MainActivity 嗰邊：mic fields＋enforcer＋releaseMic＋handleMicStream＋
 *   set_mic 兩個 override，邏輯一字不改搬過嚟
 * - ApiDispatcher 嗰邊：walkie 4 case（audioPlaybackController 經傳入同一個；
 *   原來嗰個 releaseMic 私有副本刪咗，用呢度正本）
 * TTS 播放旗經 hostState (XiaozhiBridge.HostState.isRobotTtsSpeaking) 讀——
 * 正本喺 SpeechCenter (TTS core 第一刀)，MainActivity 轉交；
 * MIC_HOLD_ENFORCER_INTERVAL_MS 沿用 MainActivity 同一個
 * const（小智嗰條 enforcer 共用緊）。
 * audioController/audioPlaybackController 實例由 MainActivity 擁有
 * （lifecycle：onDestroy shutdown），呢度借用（同 UbxPlayer 一樣安排）。
 */
public final class MicCenter {
    private final RobotStub robot;
    private final LedCenter ledCenter;
    private final AudioController audioController;
    private final AudioPlaybackController audioPlaybackController;
    private final XiaozhiBridge.HostState hostState;

    public MicCenter(RobotStub robot, LedCenter ledCenter, AudioController audioController,
            AudioPlaybackController audioPlaybackController, XiaozhiBridge.HostState hostState) {
        this.robot = robot;
        this.ledCenter = ledCenter;
        this.audioController = audioController;
        this.audioPlaybackController = audioPlaybackController;
        this.hostState = hostState;
    }

    /** onDestroy 共用：停搶 mic thread＋清持有旗 (原 onDestroy 兩行)。 */
    public void shutdownMicHold() {
        stopMicHoldEnforcer();
        micHeldByApp = false;
    }

    /** true = 用戶在 TTS tab 按了「釋放麥克風給 App」，想長期持有 mic 給 app 用，
     *  沒按回「交回麥克風給機器人」之前不算完。見 handleMicStream() finally 段的
     *  用法 - Mic Listen 的 stream 斷開不應該在這個狀態是 true 的時候將 mic
     *  還給機械人，否則「釋放」狀態會被 Mic Listen 的斷線清掉，讓用戶要不斷
     *  重新按「釋放麥克風給 App」。 */
    private volatile boolean micHeldByApp = false;

    /** true = 用戶開了「持續搶 mic」這個選項 (mic card 那顆 checkbox)。和
     *  micHeldByApp 不同 - micHeldByApp 只是記住「現在這個狀態是不是 app 持有」,
     *  這個 flag 是說就算有嘢從旁拿回 mic 都要自動再搶一次 (當年係 firmware
     *  側 IflytekWakeUp5mic side effect；家下無已知競爭者，保留做保險)。
     *  見 micHoldEnforcer 這條背景 thread。 */
    private volatile boolean micHoldEnforced = false;
    private Thread micHoldEnforcerThread;
    // Each part is a complete, independently-decodable WAV file. multipart/mixed (not
    // x-mixed-replace, which specifically means "each part replaces the last" - fine
    // for MJPEG video frames but wrong for audio chunks that should all play in
    // sequence) is the correct MIME semantics here, though the client-side JS still
    // parses the boundary manually since fetch()+ReadableStream is used rather than
    // relying on any browser-native multipart handling.
    private static final String MIC_BOUNDARY = "opensdktestpanelaudio";
    /**
     * Mic 持有權交接（歷史背景見下）。家下機身已無 alpha2services——無競爭者
     * 長期揸住 mic，robot.speech_SetMIC(true) 亦只係 RobotStub 即回 true 嘅
     * no-op；呢個 300ms sleep＋enforcer 保留做零成本保險。硬件知識保留：
     * 這台機的 audio HAL (AudioHardwareTiny) 不支援多 process 並發 input／
     * output（當年 logcat 實證 "status -38"），所以本 App 內部唔同 mic 用途
     * 之間（小智／Vosk／mic-test／stream）依然要後開者得、互斥。
     *
     * （舊機制，僅供考古：alpha2services 自家 speech/wakeup 引擎
     * (IflyteckASR5mic) 長期開住 mic 做 wake-word，setWakeState() 經 AIDL
     * 叫佢放手（唔 block，要靠呢個 sleep 避 race）。2026-08-23 實證過嘅
     * LED race（AlphaMainSeviceImpl 收到 setWakeState 就熄耳燈，同綠色
     * listening 燈鬥快）已隨 APK 移除而消失。setHeadEyeLedLong() 照舊喺
     * handleMicStream() 調用（開綠色 listening 燈）。 */
    private void releaseMicForAudioIo() {
        robot.speech_SetMIC(true);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    /** 持續搶 mic 背景 thread - 見 micHoldEnforced 個 field javadoc。每
     *  MIC_HOLD_ENFORCER_INTERVAL_MS 就重新 call 一次 speech_SetMIC(true),
     *  確保就算 firmware 內部從旁奪回了 mic (例如 setWakeState 本身在
     *  firmware bytecode 裡會順便觸發 IflytekWakeUp5mic.startRecording()
     *  這個 side effect - 見 AIDL_REFERENCE_ALPHA2.md「⚠️ 重要行為」段), app
     *  都會很快搶回來, 不用等用戶自己發現麥克風靜音了才手動再按一次。
     *
     *  用獨立 thread + sleep 而不是靠 handleMicStream() 的 loop, 是因為兩者
     *  用途不同: handleMicStream() 只在有人真的開啟 /stream/mic 才執行, 而
     *  這個 enforcer 是只要用戶在 mic card 開啟了「持續搶佔 mic」開關, 就算沒人
     *  開著 mic stream 也要生效 (例如只想用 TTS, 但不想讓機器人自己的
     *  wake-word 引擎不時搶返支 mic)。 */
    private void startMicHoldEnforcer() {
        if (micHoldEnforcerThread != null) return;
        micHoldEnforced = true;
        micHoldEnforcerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (micHoldEnforced && !Thread.currentThread().isInterrupted()) {
                    // 和 startXiaozhiMicHoldEnforcer() 一樣的原因 (見
                    // 見 SpeechCenter robotTtsSpeaking field javadoc) - 機身 robot-side TTS
                    // 正在播放就跳過這一輪, 不要打斷它。
                    if (micHeldByApp && !hostState.isRobotTtsSpeaking()) {
                        robot.speech_SetMIC(true);
                    }
                    try {
                        Thread.sleep(MainActivity.MIC_HOLD_ENFORCER_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "MicHoldEnforcer");
        micHoldEnforcerThread.start();
    }

    private void stopMicHoldEnforcer() {
        micHoldEnforced = false;
        if (micHoldEnforcerThread != null) {
            micHoldEnforcerThread.interrupt();
            micHoldEnforcerThread = null;
        }
    }
    public void handleMicStream(java.net.Socket socket) throws java.io.IOException {
        releaseMicForAudioIo();
        ledCenter.setHeadEyeLedLong(2, 9); // 綠燈長開 - 正在聽機器人說話, 一定要在上面那行之後才呼叫,
                                 // 見 releaseMicForAudioIo() javadoc 解釋為何順序很重要

        AudioController.StartResult started = audioController.start(5000);
        java.io.OutputStream out = socket.getOutputStream();
        if (started.error != null) {
            byte[] msg = ("Mic unavailable: " + started.error).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/mixed; boundary=" + MIC_BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // Capacity 2 rather than camera's 1: audio chunks must all be delivered in
        // order (dropping one produces an audible gap/glitch, unlike a skipped video
        // frame which is imperceptible), so this queue absorbs a little jitter instead
        // of discarding outright. Kept deliberately short (~1s at CHUNK_MS=500) since
        // this is meant to feel like a live walkie-talkie - a large buffer would trade
        // away responsiveness for smoothness, and if the client falls this far behind
        // something is already wrong (dead connection, GC pause) where further
        // buffering would just add latency without fixing the underlying stall.
        final java.util.concurrent.ArrayBlockingQueue<AudioController.Chunk> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
        AudioController.ChunkListener listener = new AudioController.ChunkListener() {
            @Override
            public void onChunk(AudioController.Chunk chunk) {
                if (!queue.offer(chunk)) {
                    queue.poll(); // drop the oldest to make room, keep chunks in order
                    queue.offer(chunk);
                }
            }
        };
        audioController.subscribe(listener);
        try {
            while (true) {
                AudioController.Chunk chunk;
                try {
                    // 2026-08 修正 (用家要求): 之前呢度用 poll(10, SECONDS), 10 秒
                    // 拿不到 chunk 就當「mic 死了」自動 break, 接著下面的 finally
                    // 就會 speech_SetMIC(false) 主動把 mic 還給機器人 —— 但用家
                    // 想要的是「只有用家自己按停才還機, 不理會有沒有聲音都不應該自動
                    // 還」。改用沒有 timeout 的 take(), 只是阻塞式等待下一個 chunk,
                    // 不會因為靜音就自行斷開。stream connection 本身斷了
                    // (用家關掉瀏覽器分頁/收起 tab) 會由下面 out.write() 拋出
                    // IOException 讓 loop 自然跳出, 不用靠這裡的逾時判斷。
                    //
                    // Trade-off: 如果 AudioController.readLoop() 本身真的故障
                    // (AudioRecord.read() 持續讀錯, 見 AudioController 那邊 n<0
                    // 那段), readLoop() 會自己 release 掉 AudioRecord 並停止, 但
                    // 不會再有新 chunk 送進來, 這裡的 take() 會永久阻塞, 這條 HTTP
                    // thread 唯一釋放方法是用家自己在瀏覽器裡按「停止聽」
                    // (讓 fetch abort, socket close, out.write() 才會拋出 IOException
                    // 讓 loop 跳出)。這是刻意換來的代價 - 為了完全消除「靜音
                    // 就自動還機」這個不想要的行為, 不會再有任何逾時自動釋放。
                    chunk = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                out.write(("--" + MIC_BOUNDARY + "\r\n"
                        + "Content-Type: audio/wav\r\n"
                        + "Content-Length: " + chunk.wav.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(chunk.wav);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            audioController.unsubscribe(listener);
            boolean wasLastListener = audioController.hasNoListeners();
            audioController.stopIfIdle();
            if (wasLastListener) {
                // Give the mic back to alpha2services' own wake-word engine now that
                // nobody is listening to the mic stream - otherwise voice wakeup would
                // stay silently disabled until someone went to the Speech tab and
                // manually re-enabled it, same as it used to require to enable it.
                // false = "交還麥克風給機器人" (hand back to the robot), matching
                // setMic(false) in app-speech.js - true is the opposite, "release to app".
                //
                // 例外: 如果用家在 TTS tab 按了「釋放麥克風給 App」(micHeldByApp),
                // 就代表他想長期由 app 持有 mic - 這個 stream 斷開 (背景化分頁/
                // 網路短暫中斷都會觸發這個 finally) 不應該把 mic 悄悄還給機器人,
                // 否則個「釋放」狀態就會被呢度無聲蓋走, 要用家自己再撳一次先頂到住。
                if (!micHeldByApp) {
                    robot.speech_SetMIC(false);
                }
                // Safety net: turn the green "listening" LED back off here too, not
                // just relying on the browser's stopMicListen() sending preset=stop -
                // if this stream connection just drops (backgrounded tab, network
                // blip, browser closed) rather than being stopped via the button, the
                // browser-side call never happens and the LED would otherwise stay
                // stuck on indefinitely. pure-direct: 经 JNI 直关。
                DirectLedController.stopHead5Mic();
                DirectLedController.stopEye5Mic();
            }
        }
    }
    public HttpServer.ApiResponse setMic(Map<String, String> query) {
        boolean wake = ApiValidator.requireBoolean(query, "wake");
        robot.speech_SetMIC(wake);
        // 記住這個狀態, 讓 handleMicStream() 斷線時知道用戶是否透過 TTS
        // tab 主動要求長期持有 mic - 見 micHeldByApp 的 field javadoc。
        micHeldByApp = wake;
        // 用戶手動交還給機器人 (wake=false) 就自動關閉「持續搶佔 mic」,
        // 不然 enforcer 兩秒之後又會把 mic 搶回來, 用戶的「交還」動作
        // 會看起來像沒效果一樣, 很令人困惑。
        if (!wake && micHoldEnforced) {
            stopMicHoldEnforcer();
        }
        EventBus.get().publish("mic_state",
                "{\"held\":" + micHeldByApp + ",\"keepHeld\":" + micHoldEnforced + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"held\":" + micHeldByApp
                + ",\"keepHeld\":" + micHoldEnforced + "}");
    }
    public HttpServer.ApiResponse setMicKeepHeld(Map<String, String> query) {
        boolean keep = ApiValidator.requireBoolean(query, "keep");
        if (keep) {
            startMicHoldEnforcer();
        } else {
            stopMicHoldEnforcer();
        }
        EventBus.get().publish("mic_state",
                "{\"held\":" + micHeldByApp + ",\"keepHeld\":" + micHoldEnforced + "}");
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"held\":" + micHeldByApp
                + ",\"keepHeld\":" + micHoldEnforced + "}");
    }
    // -- Walkie-talkie: browser mic -> robot speaker test endpoints.
    // (2026-09 MicIo 由 ApiDispatcher 搬入；同下面 releaseMicForAudioIo 共用 hold 逻辑。)
    public HttpServer.ApiResponse testTone() {
        releaseMicForAudioIo();
        AudioPlaybackController.StartResult result =
                audioPlaybackController.playTestTone(3000);
        if (result.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe(result.error) + "\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }
    public HttpServer.ApiResponse diagnoseAudio() {
        releaseMicForAudioIo();
        String sweep = audioPlaybackController.diagnoseAudioTrack(10000);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"results\":\""
                + MainActivity.jsonSafe(sweep).replace("\n", "\\n") + "\"}");
    }
    public HttpServer.ApiResponse playStart() {
        releaseMicForAudioIo();
        AudioPlaybackController.StartResult result = audioPlaybackController.start(3000);
        if (result.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe(result.error) + "\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }
    public HttpServer.ApiResponse playStop() {
        audioPlaybackController.stop();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }
}
