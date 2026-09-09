package com.open.alpha2;

import android.util.Log;

import java.util.Map;

/**
 * 本地語意配對膠水：中英 matcher 二揀一 + TTS/動作執行。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object)：handleIflytekSemanticText、
 * looksChinese、toZhResult 原本全部係 MainActivity 私有成員，搬過嚟邏輯不改。
 * Matcher 實例由 MainActivity 起好傳入 (Vosk 都要用同一份)；播動作經
 * ActionDirect，讀答案經 TtsCenter。 speech/iflytek_simulate endpoint
 * 經呢度一次驗晒成條鏈。
 * 2026-09 dispatcher Phase 1 第六刀加：speech/iflytek_simulate response
 * (iflytekSimulateResponse) 搬入。
 */
public final class SemanticCenter {
    private static final String TAG = "SemanticCenter";

    /** TTS 之後要等多久才播動作, 沿用悠聊 RobotActionBusiness.startBusiness() 反編譯
     *  出來的原本時序 (先 TTS, sleep 200ms, 才播動作 - 兩者是分開、非同步的 AIDL
     *  call, 只靠這個 sleep 頂住, 沒有等 TTS 真的播完才動)。用戶已確認沿用悠聊原本
     *  這樣做, 不改成等 TTS 播完才動。 */
    private static final int IFLYTEK_TTS_TO_ACTION_DELAY_MS = 200;

    private final IflytekSemanticMatcher iflytekMatcher;
    private final IflytekSemanticMatcherEn iflytekMatcherEn;
    private final ActionDirect actionDirect;
    private final TtsCenter ttsCenter;

    public SemanticCenter(IflytekSemanticMatcher iflytekMatcher,
            IflytekSemanticMatcherEn iflytekMatcherEn,
            ActionDirect actionDirect, TtsCenter ttsCenter) {
        this.iflytekMatcher = iflytekMatcher;
        this.iflytekMatcherEn = iflytekMatcherEn;
        this.actionDirect = actionDirect;
        this.ttsCenter = ttsCenter;
    }

    /** 判斷一句輸入文字是否應該用中文 matcher 處理: 有任何 CJK 統一表意文字 (漢字)
     *  就當中文, 完全沒有就當英文。2026-08 特意選這個做法, 不依靠 speech/set_asr_engine
     *  那個手動語言設定, 因為 iFlytek 引擎本身可能自動偵測用戶說的是什麼語言, 只看辨識
     *  出來的文字內容本身最可靠。中英文夾雜的句子 (例如 "跳個 dance") 會因為有漢字而
     *  當中文 - 這是刻意的簡化, 不追求完美的語言偵測, 對這個用途已經夠準確。 */
    private static boolean looksChinese(String text) {
        if (text == null) return false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** IflytekSemanticMatcherEn.MatchResult -> IflytekSemanticMatcher.MatchResult 的
     *  薄轉接層。兩個 class 的 MatchResult 結構完全一樣 (question/type/operation/
     *  slot/answer/actionId), 但屬於不同 class 的 nested type, Java 不會自動把它們
     *  當成同一個型別 - 這個 method 純粹做欄位複製, 讓 handleIflytekSemanticText() 的
     *  下半部分 (publish event、TTS/動作執行) 不用為中英文分別多寫一份。 */
    private static IflytekSemanticMatcher.MatchResult toZhResult(IflytekSemanticMatcherEn.MatchResult en) {
        if (en == null) return null;
        return new IflytekSemanticMatcher.MatchResult(
                en.question, en.type, en.operation, en.slot, en.answer, en.actionId);
    }

    /** 將一句文字 (可能是 iFlytek 引擎真正辨識到的, 也可能是 speech/iflytek_simulate
     *  這個 endpoint 用來測試的打字輸入) 對照 1000 條問法配對, 命中就執行悠聊原本的
     *  「先 TTS、再隔 200ms 播動作」流程。找不到就什麼都不做 (不是錯誤 - 用戶說的話不在
     *  那 1000 條裡面是很正常的事, 靜靜地不回應好過亂回一個不相關的回覆), 回傳 null。
     *
     *  中英文用哪個 matcher 由 looksChinese() 判斷 - 有漢字用 IflytekSemanticMatcher
     *  (中文, iflytek_semantic_zh.json), 沒有就用 IflytekSemanticMatcherEn (英文,
     *  iflytek_semantic_en.json)。兩個 class 結構一致、資料獨立, 不會互相影響。
     *
     *  回傳 MatchResult (而不是 void) 是為了讓 speech/iflytek_simulate 這個 endpoint 用來
     *  即時告訴前端「有沒有配對中」, publishEvent=false 那個用法不會再經由 EventBus
     *  多 publish 一次 (前端 sendSpeechChatText() 已經即時用 HTTP response 顯示)。
     *
     *  TTS/動作執行本身依然在獨立 thread 上做 AIDL blocking call, 不在呼叫者的
     *  thread (可能是 HTTP worker thread) 上直接做 - 和 triggerRandomFillerAction()
     *  一致的安全做法。 */
    public IflytekSemanticMatcher.MatchResult handleIflytekSemanticText(final String text,
                                                                         final boolean publishEvent) {
        final boolean chinese = looksChinese(text);
        if (chinese) {
            if (iflytekMatcher == null) return null; // onCreate() 尚未執行完 (理論上不會, 保險)
        } else {
            if (iflytekMatcherEn == null) return null;
        }

        final IflytekSemanticMatcher.MatchResult result = chinese
                ? iflytekMatcher.match(text)
                : toZhResult(iflytekMatcherEn.match(text));
        if (result == null) {
            return null; // 找不到對應問法 - 靜靜地不做事, 不算錯誤
        }
        if (publishEvent) {
            EventBus.get().publish("iflytek_match",
                    "{\"question\":\"" + MainActivity.jsonSafe(result.question) + "\","
                            + "\"type\":\"" + MainActivity.jsonSafe(result.type) + "\","
                            + "\"operation\":\"" + MainActivity.jsonSafe(result.operation) + "\","
                            + "\"answer\":\"" + MainActivity.jsonSafe(result.answer) + "\","
                            + "\"actionId\":\"" + MainActivity.jsonSafe(result.actionId) + "\"}");
        }

        // 2026-09 更新: 機身已無 iFlytek/Nuance (無 alpha2services),
        // robot.speech_startTTS() 只會回 NOT_INIT 全程靜音。語意配對答案改行
        // Android 內置 TTS (同 speech/tts engine=android 分支同一部機), 依答案
        // 語言揀 locale。嘴 LED 熄燈靠 Android TTS 個 UtteranceProgressListener
        // (見 TtsCenter.initAndroidTts), 唔使自己熄。
        final String ttsAnswer = result.answer;
        final java.util.Locale ttsLocale =
                chinese ? java.util.Locale.SIMPLIFIED_CHINESE : java.util.Locale.ENGLISH;
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (ttsAnswer != null && !ttsAnswer.isEmpty()) {
                    LedCenter.startMouthLedForTts();
                    if (!ttsCenter.speakAndroidTts(ttsAnswer, ttsLocale)) {
                        LedCenter.stopMouthLedForTts();
                    }
                }
                if (result.actionId == null) {
                    return; // CHAT 類或部分 FUNCTION 類沒有對應動作, TTS 完就結束
                }
                try {
                    Thread.sleep(IFLYTEK_TTS_TO_ACTION_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String actionId = result.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // 2026-08 新增: 用戶說到分類名 (例如「跳舞」/"Dance for me") 但沒有
                    // 說出具體是哪個動作 - 在 202 動作清單的對應分類 (例如
                    // DANCE_KIDS/YOGA_ANY) 裡面隨機選一個。和下面 "__RANDOM__"
                    // (完全不限分類, 202 個隨便選) 不同, 這是分類限定的隨機。中英文
                    // matcher 共用同一份 action_category_pools.json, 哪個 instance
                    // 呼叫結果都一樣, 只是依 chinese 這個 flag 選擇對應的 instance。
                    actionId = chinese
                            ? iflytekMatcher.resolveCategoryRandomActionId(actionId)
                            : iflytekMatcherEn.resolveCategoryRandomActionId(actionId);
                } else if ("__RANDOM__".equals(actionId)) {
                    // TFBOY 這類 operation 在原廠問法裡沒有固定動作 - 沿用
                    // triggerRandomFillerAction() 已有的隨機動作池 (202 個動作裡
                    // 「隨機短/長」開頭的那批, 專門用來做這種「動一下讓它生動一點」的效果)。
                    actionId = actionDirect.resolveRandomActionId();
                }
                if (actionId != null) {
                    actionDirect.playActionDirect(actionId); // pure-direct：旧 AIDL 已无服务承载
                }
            }
        }, "IflytekSemanticAction").start();
        return result;
    }

    // 2026-08 新增: "打字當作自己說了這句" - 直接把輸入文字當成 iFlytek
    // 引擎已經辨識完的結果, 送去 handleIflytekSemanticText() 做 1000 條
    // 問法配對 (中英文各 1000 條, 依輸入文字有沒有漢字自動判斷用哪份 - 見
    // looksChinese()), 命中就立即執行悠聊原本的「TTS200ms動作」流程。
    // 和 speech/inject 不同: 這裡不經任何機身 AIDL (不靠
    // speech_startRecognized()/onSpeech() 這條 "不確定會不會真的觸發辨識"
    // 的路), 純粹是本地 JSON 配對 + 直接呼叫 robot.speech_startTTS()/
    // robot.action_PlayActionName(), 所以不需要 speechReady gate, 只
    // 需要 robot 本身已經 initRobot() 完成 (onCreate() 一開始就做了)。
    // response 即時告訴前端有沒有配對到 (matched/question/type/
    // operation/answer/actionId), 不用等 WebSocket event - 方便對話
    // 界面直接顯示配對結果, 不用一直等 EventBus。
    //
    // 2026-08 新增: match() 現在找不到問法也會回傳一個「聽不懂」的
    // fallback 回應 (不再是 null), 所以 matched:false 分支現在只
    // 剩返「輸入係空白字串」呢種 edge case 先會行到。
    // (2026-09 dispatcher Phase 1 第六刀由 handleApi speech/iflytek_simulate 搬入。)
    public HttpServer.ApiResponse iflytekSimulateResponse(Map<String, String> query) {
        String simText = ApiValidator.require(query, "text");
        IflytekSemanticMatcher.MatchResult simResult = handleIflytekSemanticText(simText, false);
        if (simResult == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":true,\"matched\":false,\"input\":\"" + MainActivity.jsonSafe(simText) + "\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"matched\":true,"
                + "\"input\":\"" + MainActivity.jsonSafe(simText) + "\","
                + "\"question\":\"" + MainActivity.jsonSafe(simResult.question) + "\","
                + "\"type\":\"" + MainActivity.jsonSafe(simResult.type) + "\","
                + "\"operation\":\"" + MainActivity.jsonSafe(simResult.operation) + "\","
                + "\"answer\":\"" + MainActivity.jsonSafe(simResult.answer) + "\","
                + "\"actionId\":\"" + MainActivity.jsonSafe(simResult.actionId) + "\"}");
    }
}
