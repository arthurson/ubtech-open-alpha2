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
 * 現狀備註：
 * - 2026-09: nuance/iflytek 已經永久唔再用 (機身無 alpha2services, binder
 *   speech_startTTS 已死), 已經徹底移除 - requireSpeechEngine() 只准 "android",
 *   handleSpeechTts() 恆行 android 一條路, RobotStub 依賴同 constructor 個
 *   robot 參數一併刪走。ApiValidatorTest.java 舊有斷言
 *   requireSpeechEngine("iflytek")=="iflytek" 嗰條連帶要更新做拋
 *   IllegalArgumentException (見該檔案)。
 * - robotTtsSpeaking 而家恆 false（觸發 true 嘅唯一路徑已經冇咗）；flag 機制
 *   本身保留唔刪——兩條 mic-hold enforcer 仍然讀緊呢個 flag, 將來如果直驅 TTS
 *   接上要用返。
 * - XiaozhiBridge 內兩個 gap 引用（speakActivationCode/self.robot.speak MCP
 *   tool）繼續留喺嗰邊，const 改經呢度
 *   （SpeechCenter.STOP_TO_TTS_MIN_GAP_MS），時戳經 HostState 讀。
 */
public final class SpeechCenter implements ApiDispatcher.Host, GestureCenter.Host {

    // speech/stop -> speech/tts race guard.
    //
    // speech_StopTTS() (AIDL onStopPlay) is fire-and-forget: the call returns as
    // soon as the binder transaction is queued, but the robot side's audio
    // teardown (tearing down the current Nuance/iFlytek playback session) happens
    // asynchronously after that. If speech/tts starts a new TTS session while that
    // teardown is still in flight, Nuance's SpeakerPlayerSink can throw an
    // IllegalStateException that kills the TTS session until the robot reboots.
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

    private final TtsCenter ttsCenter;
    private final VoskController vosk; // 可 null：API 19 機起唔到 Vosk（見 MainActivity.onCreate 熔斷）
    private final XiaozhiBridge xiaozhiBridge; // 經 stopSpeechPlayback() 停小智嗰條播放管道

    // 2026-09: constructor 原本仲收 RobotStub robot 呢個參數, 淨係俾
    // handleSpeechTts() 果段已刪嘅 nuance/iflytek 分支用嚟 call
    // robot.speech_startTTS() —— 果段拎走之後, robot 喺呢個 class 冇任何
    // method 再用到, 一併刪走 (MainActivity.java 個 new SpeechCenter(...)
    // call site 已經跟手改咗)。
    public SpeechCenter(TtsCenter ttsCenter, VoskController vosk,
            XiaozhiBridge xiaozhiBridge) {
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

    // -- ApiDispatcher.Host (speech/tts、speech/stop) --
    // 2026-09: nuance/iflytek 已經永久唔再用 (機身無 alpha2services, binder 已死),
    // requireSpeechEngine() 已經收窄做只准 "android" (見佢個 comment), 呢度
    // 之前保留住嘅 android-vs-非-android 分支跟住拎走 - engine 而家恆係 "android",
    // 個 if 判斷已經冇意義 (只會行到 true 嗰邊)。
    @Override public HttpServer.ApiResponse handleSpeechTts(Map<String, String> query) {
        String text = ApiValidator.require(query, "text");
        ApiValidator.requireSpeechEngine(query); // 保留做 validation (未來若加返其他 engine 值時仍要驗)
        String ttsErr = ttsCenter.speakPanelTts(text, ApiValidator.optional(query, "lang", ""),
                ApiValidator.optional(query, "voice", ""));
        if (ttsErr != null) return HttpServer.ApiResponse.error(ttsErr);
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    @Override public HttpServer.ApiResponse handleSpeechStop() {
        stopAllSpeechPlayback();
        return MainActivity.codeResponse(UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED);
    }

    // -- GestureCenter.Host (0x5e 總停鍵)：經 stopAllSpeechPlayback（TTS orchestration）。 --
    @Override public void stopAllSpeech() { stopAllSpeechPlayback(); }
}
