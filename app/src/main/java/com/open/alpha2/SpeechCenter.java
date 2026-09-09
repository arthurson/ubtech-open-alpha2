package com.open.alpha2;

import java.util.Map;

/**
 * TTS orchestration 包：speech/tts＋speech/stop＋0x5e 總停鍵共用嘅 stopAllSpeech。
 *
 * 2026-09 由 MainActivity 抽出 (TTS core 第一刀)：STOP_TO_TTS_MIN_GAP_MS、
 * lastSpeechStopAtMs、robotTtsSpeaking、handleSpeechTts()、handleSpeechStop()、
 * stopAllSpeechPlayback()，邏輯一字不改搬過嚟（機械改寫只限：isOk/codeResponse
 * 加 MainActivity. 前綴）。
 * 擁有關係：
 * - MainActivity 只留：implements XiaozhiBridge.HostState 其中 TTS 兩法轉交呢度
 *   （delegate，見下面 mic 路設計）、接線（onCreate 建構、router 轉發經
 *   ApiDispatcher.Host 直調）、implements GestureCenter.Host 轉交呢度。
 * - ApiDispatcher.Host 由呢度直實現（MainActivity 唔再 implements）。
 *
 * mic 路設計（掂 mic 所以要講清楚）：
 * - MicCenter 同 XiaozhiBridge 兩條 mic-hold enforcer 繼續經
 *   XiaozhiBridge.HostState.isRobotTtsSpeaking() 讀旗——唔改佢哋 ctor
 *   （兩條 enforcer 已 E2E 驗過後開者得，唔郁接線）。
 * - MainActivity 繼續做 HostState 提供者，TTS 兩法
 *   （isRobotTtsSpeaking/getLastSpeechStopAtMs）轉交呢度（null-guard：
 *   onCreate 同一 thread 先後建構，enforcer 未起，預設 false/0 安全）。
 * - 咁樣 SpeechCenter→XiaozhiBridge 係單向（經 stopSpeechPlayback 停小智嗰條
 *   管道），XiaozhiBridge→SpeechCenter 唔經 ctor（經 MainActivity delegate），
 *   無循環依賴。sonar 四法將來由 SonarCenter 收返，到時 HostState 再拆。
 *
 * 現狀備註（行為照搬，唔改）：
 * - robot.speech_startTTS() 現時恆回 NOT_INIT（pure-direct 無 binder，見
 *   RobotStub），robotTtsSpeaking 實際恆 false（只喺成功先設 true）；flag
 *   保留唔刪——enforcer 讀緊，binder 翻生/直驅 TTS 接上嗰陣要返。
 * - XiaozhiBridge 內兩個 gap 引用（speakActivationCode/self.robot.speak MCP
 *   tool）繼續留喺嗰邊，const 改經呢度
 *   （SpeechCenter.STOP_TO_TTS_MIN_GAP_MS），時戳經 HostState 讀。
 *   2026-09 已收斂：MCP self.robot.speak 嗰段 gap＋mouth-LED＋
 *   speech_startTTS 同下面 handleSpeechTts 非 android 分支共用
 *   speakRobotTts(text, lang, voice)。雙向引用係咁斷的：方法係 static，
 *   時戳經參數傳入——XiaozhiBridge 側經已有嘅 HostState
 *   getLastSpeechStopAtMs()（MainActivity 轉交緊呢度同一個 field）讀，
 *   所以唔使 XiaozhiBridge 拎 SpeechCenter instance。
 */
public final class SpeechCenter implements ApiDispatcher.Host, GestureCenter.Host {

    // speech/stop -> speech/tts race guard.
    //
    // Historical rationale (AIDL era, kept as ordering hygiene): speech_StopTTS()
    // (AIDL onStopPlay) was fire-and-forget - the call returned as soon as the
    // binder transaction was queued, but the robot side's audio teardown (tearing
    // down the current Nuance/iFlytek playback session) happened asynchronously
    // after that. Starting a new TTS session mid-teardown could throw (Nuance's
    // SpeakerPlayerSink IllegalStateException) and kill TTS until reboot.
    // alpha2services 已移除，robot 側 path 家下即時失敗，呢個 gap 無嘢要等；
    // 保留（唔刪）：開銷最多 400ms，萬一將來直驅 TTS 接上，排序保護已經喺度。
    //
    // Fix: record the wall-clock time of the last speech/stop, and have speech/tts
    // block (on the HTTP worker thread only - safe because HttpServer uses
    // newCachedThreadPool, so this never stalls other requests) until at least
    // STOP_TO_TTS_MIN_GAP_MS has elapsed since that stop. 400ms was enough headroom
    // in testing for the teardown to finish without being long enough to feel like
    // a UI stall for a normal stop-then-speak flow.
    static final long STOP_TO_TTS_MIN_GAP_MS = 400;
    private volatile long lastSpeechStopAtMs = 0L;
    // 追蹤機身 robot-side TTS (nuance/iflytek, 經 robot.speech_startTTS() 走)
    // 現在是不是正在播 - 由 startXiaozhiMicHoldEnforcer()/startMicHoldEnforcer()
    // 用來決定要不要跳過這一輪 speech_SetMIC(true)。背景: 兩條 mic-hold
    // enforcer thread 每 MIC_HOLD_ENFORCER_INTERVAL_MS (2 秒) 就會無條件搶一次
    // mic, 一句超過 2 秒才讀完的句子播到一半就被 speech_SetMIC(true) 打斷
    // (真機 logcat 見過 "ttsGenerationFinished ... success = false" 接著立刻
    // "setWakeState onWake:true") - Android system TTS 不經這個 AIDL 通道,
    // 不會撞到, 所以之前只有 iflytek/nuance 斷斷續續, android 沒事。
    private volatile boolean robotTtsSpeaking = false;

    private final RobotStub robot;
    private final TtsCenter ttsCenter;
    private final VoskController vosk; // 可 null：API 19 機起唔到 Vosk（見 MainActivity.onCreate 熔斷）
    private final XiaozhiBridge xiaozhiBridge; // 經 stopSpeechPlayback() 停小智嗰條播放管道

    public SpeechCenter(RobotStub robot, TtsCenter ttsCenter, VoskController vosk,
            XiaozhiBridge xiaozhiBridge) {
        this.robot = robot;
        this.ttsCenter = ttsCenter;
        this.vosk = vosk;
        this.xiaozhiBridge = xiaozhiBridge;
    }

    /** XiaozhiBridge.HostState 轉交用：enforcer 讀緊播緊旗。 */
    public boolean isRobotTtsSpeaking() { return robotTtsSpeaking; }

    /** XiaozhiBridge.HostState 轉交用：gap 計時讀上次 stop。 */
    public long getLastSpeechStopAtMs() { return lastSpeechStopAtMs; }

    /** 2026-08 新增: 停止「小智說話/回覆」這一種播放 - 抽出來做共用 method, 供
     *  handleApi() 的 "speech/stop" HTTP endpoint 和 onGestureCode() 的 0x5e
     *  (雙鍵齊按, 也就是「94 鍵」) 一起使用。停止 Android TTS 和小智語音回覆
     *  的音訊 (XiaozhiAudioController, WebSocket 收 Opus frame -> 解碼 ->
     *  AudioTrack, 詳見 XiaozhiAudioController.onIncomingOpusFrame()/
     *  stopPlayback() 的 javadoc) - 互相獨立的播放管道, 停一條不會連帶讓另一條
     *  也停, 之前用戶回報「停不了小智說話」就是因為漏了 XiaozhiAudioController
     *  這條路。2026-09: 機身本地 TTS (Nuance/iflytek) 已隨 alpha2services 移除，
     *  無嘢要停，舊 robot.speech_StopTTS() call 拎走。 */
    public void stopAllSpeechPlayback() {
        lastSpeechStopAtMs = System.currentTimeMillis();
        robotTtsSpeaking = false; // 見 robotTtsSpeaking field javadoc - 手動/總停鍵停止時都要立即放行 mic enforcer
        ttsCenter.stop();
        // 2026-09: 手動全部停止都要 resume Vosk（上面 UtteranceProgressListener
        // 嘅 onDone 唔一定會嚟）。
        if (vosk != null) {
            try {
                vosk.setPaused(false);
            } catch (Throwable ignore) {
            }
        }
        xiaozhiBridge.stopSpeechPlayback();
        LedCenter.stopMouthLedForTts();
    }

    /** robot-side TTS 發聲共用入口：gap＋mouth-LED bracket＋speech_startTTS＋
     *  失敗熄燈。static 故 XiaozhiBridge.MCP self.robot.speak 可直接調用，
     *  時戳由調用方傳入（本尊傳自己個 field；MCP 經 HostState 讀同一個值），
     *  不產生 SpeechCenter↔XiaozhiBridge instance 雙向引用。
     *  語義同收斂前兩邊一字不差（連失敗先熄燈、成功先由調用方標 robotTtsSpeaking
     *  都保留喺調用方）。 */
    static UbxErrorCode.API_ERROR_CODE speakRobotTts(RobotStub robot, long lastStopAtMs,
                                                     String text, String lang, String voice) {
        long sinceStopMs = System.currentTimeMillis() - lastStopAtMs;
        if (sinceStopMs >= 0 && sinceStopMs < STOP_TO_TTS_MIN_GAP_MS) {
            try {
                Thread.sleep(STOP_TO_TTS_MIN_GAP_MS - sinceStopMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        LedCenter.startMouthLedForTts();
        UbxErrorCode.API_ERROR_CODE code = robot.speech_startTTS(lang, text, voice);
        if (!MainActivity.isOk(code)) {
            LedCenter.stopMouthLedForTts();
        }
        return code;
    }

    // -- ApiDispatcher.Host (speech/tts、speech/stop) --
    @Override public HttpServer.ApiResponse handleSpeechTts(Map<String, String> query) {
        String text = ApiValidator.require(query, "text");
        String engine = ApiValidator.requireSpeechEngine(query);
        if ("android".equals(engine)) {
            String ttsErr = ttsCenter.speakPanelTts(text, ApiValidator.optional(query, "lang", ""));
            if (ttsErr != null) return HttpServer.ApiResponse.error(ttsErr);
            return HttpServer.ApiResponse.ok("{\"ok\":true}");
        }
        String voice = "iflytek".equals(engine) ? ApiValidator.optionalNullable(query, "voice") : null; // may be null
        String lang = "iflytek".equals(engine) ? "zh_cn" : "en_us"; // no language picker; engine implies it
        // See STOP_TO_TTS_MIN_GAP_MS above: if speech/stop just ran, keep a
        // minimum window before starting a new robot-side TTS session (ordering
        // gap＋mouth-LED bracket＋speech_startTTS＋失敗熄燈全部喺
        // speakRobotTts() 入面（同 MCP 共用；sleep 行喺 HTTP worker thread，
        // newCachedThreadPool，唔塞其他 request）。呢度淨係收尾：
        // speech_startTTS failed synchronously 的話 onServerPlayEnd 永遠唔會
        // fire，熄燈已由 speakRobotTts() 做；成功先標 robotTtsSpeaking。
        UbxErrorCode.API_ERROR_CODE res = speakRobotTts(robot, lastSpeechStopAtMs, text, lang, voice);
        if (MainActivity.isOk(res)) {
            // 見 robotTtsSpeaking field javadoc - 觸發成功先算「開始
            // 播緊」, onServerPlayEnd 會揭返做 false。
            robotTtsSpeaking = true;
        }
        return MainActivity.codeResponse(res);
    }

    @Override public HttpServer.ApiResponse handleSpeechStop() {
        stopAllSpeechPlayback();
        return MainActivity.codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);
    }

    // -- GestureCenter.Host (0x5e 總停鍵)：經 stopAllSpeechPlayback（TTS orchestration）。 --
    @Override public void stopAllSpeech() { stopAllSpeechPlayback(); }
}
