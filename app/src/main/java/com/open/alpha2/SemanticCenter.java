package com.open.alpha2;


import android.util.Log;

import java.util.Map;

/**
 * 本地語意配對膠水：對話語言→matcher（未設就文字判斷）＋TTS/動作執行。
 */
public final class SemanticCenter {

    private static final String TAG = "SemanticCenter";

    /** TTS 之後要等多久才播動作, 沿用悠聊 RobotActionBusiness.startBusiness() 反編譯
     *  出來的原本時序 (先 TTS, sleep 200ms, 才播動作 - 兩者是分開、非同步的 AIDL
     *  call, 只靠這個 sleep 頂住, 沒有等 TTS 真的播完才動)。用戶已確認沿用悠聊原本
     *  這樣做, 不改成等 TTS 播完才動。 */
    private static final int SEMANTIC_TTS_TO_ACTION_DELAY_MS = 200;

    private final SemanticMatcherZh semanticMatcherZh;
    private final SemanticMatcherEn semanticMatcherEn;
    private final SemanticMatcherEs semanticMatcherEs;
    private final SemanticMatcherFr semanticMatcherFr;
    private final SemanticMatcherJa semanticMatcherJa;
    private final ActionDirect actionDirect;
    private final TtsCenter ttsCenter;

    /** 對話語言（zh/en/es/fr/ja；null＝未設）：vosk/load 換 model 當時經 setDialogueLang()
     *  傳入。vosk 什麼話就對什麼 matcher；沒有對認 matcher 的語言一律行英文（下面
     *  "zh".equals 就是這條規則——將來加的語言未有 matcher 之前都是這樣）。 */
    private volatile String dialogueLang;

    /** 對話語言設定（VoskApi 經 vosk/load 換 model 當時傳入；僅收 zh/en/es/fr/ja）。
     *  TTS 不綁這個——用什麼 TTS 全由用家決定，這裡僅決定用哪個 matcher，
     *  答案讀什麼 locale 跟命中那邊（見 handleSemanticMatch）。 */
    public void setDialogueLang(String lang) {
        if ("zh".equals(lang) || "en".equals(lang) || "es".equals(lang) || "fr".equals(lang) || "ja".equals(lang)) dialogueLang = lang;
    }

    public SemanticCenter(SemanticMatcherZh semanticMatcherZh,
            SemanticMatcherEn semanticMatcherEn, SemanticMatcherEs semanticMatcherEs,
            SemanticMatcherFr semanticMatcherFr, SemanticMatcherJa semanticMatcherJa,
            ActionDirect actionDirect, TtsCenter ttsCenter) {
        this.semanticMatcherZh = semanticMatcherZh;
        this.semanticMatcherEn = semanticMatcherEn;
        this.semanticMatcherEs = semanticMatcherEs;
        this.semanticMatcherFr = semanticMatcherFr;
        this.semanticMatcherJa = semanticMatcherJa;
        this.actionDirect = actionDirect;
        this.ttsCenter = ttsCenter;
    }

    /** 判斷一句輸入文字是否應該用中文 matcher 處理: 有任何 CJK 統一表意文字 (漢字)
     *  就當中文, 完全沒有就當英文。現在降做後備：僅對話語言未設（開機預設、
     *  從來沒有 set 過）當時用；一設了就跟設定行，主 matcher 不中先試另一個
     *  （見 handleSemanticMatch），不再單靠文字。中英文夾雜的句子
     *  (例如 "跳個 dance") 會因為有漢字而當中文 - 這是刻意的簡化,
     *  不追求完美的語言偵測, 對這個用途已經夠準確。 */
    private static boolean looksChinese(String text) {
        if (text == null) return false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 將一句文字 (可能是語音引擎真正辨識到的, 也可能是 speech/semantic_simulate
     *  這個 endpoint 用來測試的打字輸入) 對照 1000 條問法配對, 命中就執行悠聊原本的
     *  「先 TTS、再隔 200ms 播動作」流程。找不到就什麼都不做 (不是錯誤 - 用戶說的話不在
     *  那 1000 條裡面是很正常的事, 悄悄地不回應好過亂回一個不相關的回覆), 回傳 null。
     *
     *  用哪個 matcher：有設對話語言就跟設定（vosk 什麼話就對什麼 matcher；
     *  SemanticMatcherZh/En/Es/Fr/Ja 結構一致、資料獨立, 不會互相影響），主那個不中
     *  先試另外四個（兜底直接打 model 鍵換了 model、或者混合輸入那些 case）；
     *  五個都不中就用主那個 fallback（同以前單試一個效果一樣，不會播兩次）。
     *  未設就沿用 looksChinese()（有漢字行中文 semantic_zh.json，
     *  沒有就行英文 semantic_en.json；西文／法文／日文一定要經設定先入到）。
     *
     *  TTS locale 跟命中那邊（不是跟輸入文字），嘴 LED 同動作流程不變。
     *
     *  回傳 MatchResult (而不是 void) 是為了讓 speech/semantic_simulate 這個 endpoint 用來
     *  即時告訴前端「有沒有配對中」, publishEvent=false 那個用法不會再經由 EventBus
     *  多 publish 一次 (前端 sendSpeechChatText() 已經即時用 HTTP response 顯示)。
     *
     *  TTS/動作執行本身依然在獨立 thread 上做 AIDL blocking call, 不在呼叫者的
     *  thread (可能是 HTTP worker thread) 上直接做 - 和 triggerRandomFillerAction()
     *  一致的安全做法。 */
    public SemanticMatcherZh.MatchResult handleSemanticMatch(final String text,
                                                                         final boolean publishEvent) {
        final boolean textChinese = looksChinese(text);
        // 主語言：有設跟設定（zh/en/es/fr/ja），未設就沿用 looksChinese（有漢字中文、冇就英文；
        // 西文／法文／日文一定要經設定先入到——冇漢字嘅拉丁輸入靠下面五個試晒先中）。
        final String lang;
        if (dialogueLang != null) {
            lang = dialogueLang;
        } else {
            lang = textChinese ? "zh" : "en";
        }
        final SemanticMatcherBase mZh = semanticMatcherZh;
        final SemanticMatcherBase mEn = semanticMatcherEn;
        final SemanticMatcherBase mEs = semanticMatcherEs;
        final SemanticMatcherBase mFr = semanticMatcherFr;
        final SemanticMatcherBase mJa = semanticMatcherJa;
        final String[] order;
        final SemanticMatcherBase[] chain;
        if ("ja".equals(lang)) {
            order = new String[]{"ja", "zh", "en", "es", "fr"};
            chain = new SemanticMatcherBase[]{mJa, mZh, mEn, mEs, mFr};
        } else if ("fr".equals(lang)) {
            order = new String[]{"fr", "zh", "en", "es", "ja"};
            chain = new SemanticMatcherBase[]{mFr, mZh, mEn, mEs, mJa};
        } else if ("es".equals(lang)) {
            order = new String[]{"es", "zh", "en", "fr", "ja"};
            chain = new SemanticMatcherBase[]{mEs, mZh, mEn, mFr, mJa};
        } else if ("en".equals(lang)) {
            order = new String[]{"en", "zh", "es", "fr", "ja"};
            chain = new SemanticMatcherBase[]{mEn, mZh, mEs, mFr, mJa};
        } else {
            order = new String[]{"zh", "en", "es", "fr", "ja"};
            chain = new SemanticMatcherBase[]{mZh, mEn, mEs, mFr, mJa};
        }

        SemanticMatcherBase.MatchResult result = chain[0] != null ? chain[0].match(text) : null;
        String hitLang = order[0];
        if (result != null && result.matched && result.matchLayer > 2) {
            // 主 matcher 只係模糊撞中（例如無設語言、拉丁輸入英文行先，
            // 西文 "Aplaude" 會畀英文 "applaud" 模糊搶走）——先睇埋另外四個
            // 有冇強匹配（精確/包含/反包含），有就用佢，冇先用主嗰個模糊。
            for (int k = 1; k < chain.length; k++) {
                if (chain[k] == null || chain[k] == chain[0]) continue;
                SemanticMatcherBase.MatchResult r = chain[k].match(text);
                if (r != null && r.matched && r.matchLayer <= 2) {
                    result = r;
                    hitLang = order[k];
                    break;
                }
            }
        }
        if (result != null && !result.matched) {
            // 主 matcher 聽唔明，先試埋另外四個（中就用佢；都唔中就用主嗰個 fallback，
            // 同以前中英互試效果一致，只係加多三關）。
            for (int k = 1; k < chain.length; k++) {
                if (chain[k] == null || chain[k] == chain[0]) continue;
                SemanticMatcherBase.MatchResult r = chain[k].match(text);
                if (r != null && r.matched) {
                    result = r;
                    hitLang = order[k];
                    break;
                }
            }
        }
        if (result == null) {
            return null; // 空白輸入 - 悄悄地不做事, 不算錯誤
        }
        // 裡面條 thread 捉不到 reassigned 過的 result，用 final 影子。
        final SemanticMatcherBase.MatchResult finalResult = result;
        // 命中那邊（主／兜底）：TTS locale＋分類隨機都跟它（final 下來給下面條 thread 用）。
        final String hitLangFinal = hitLang;
        if (publishEvent) {
            EventBus.get().publish("semantic_match",
                    "{\"question\":\"" + MainActivity.jsonSafe(result.question) + "\","
                            + "\"type\":\"" + MainActivity.jsonSafe(result.type) + "\","
                            + "\"operation\":\"" + MainActivity.jsonSafe(result.operation) + "\","
                            + "\"answer\":\"" + MainActivity.jsonSafe(result.answer) + "\","
                            + "\"actionId\":\"" + MainActivity.jsonSafe(result.actionId) + "\"}");
        }

        // 機身已無 iFlytek/Nuance (無 alpha2services),
        // robot.speech_startTTS() 只會回 NOT_INIT 全程靜音。語意配對答案改行
        // Android 內置 TTS (同 speech/tts engine=android 分支同一部機), 依答案
        // 語言選 locale。嘴 LED 熄燈靠 Android TTS 個 UtteranceProgressListener
        // (見 TtsCenter.initAndroidTts), 不用自動熄滅。
        final String ttsAnswer = finalResult.answer;
        // TTS locale 跟命中那邊的語言（西文／法文／日文答案用對應語言讀，否則口音會好怪）。
        final java.util.Locale ttsLocale;
        if ("ja".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ja");
        } else if ("fr".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("fr");
        } else if ("es".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("es");
        } else if ("en".equals(hitLangFinal)) {
            ttsLocale = java.util.Locale.ENGLISH;
        } else {
            ttsLocale = java.util.Locale.SIMPLIFIED_CHINESE;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 先 resolve 做真實 action id（分類隨機/__RANDOM__ 本來播嗰刻先解，
                // 家下播之前就要知有無聲，所以提早解；解唔到 null 就當無動作行）。
                String actionId = finalResult.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // 用戶說到分類名 (例如「跳舞」/"Dance for me") 但沒有
                    // 說出具體是哪個動作 - 在 202 動作清單的對應分類 (例如
                    // DANCE_KIDS/YOGA_ANY) 裡面隨機選一個。和下面 "__RANDOM__"
                    // (完全不限分類, 202 個隨便選) 不同, 這是分類限定的隨機。中英文
                    // matcher 共用同一份 action_category_pools.json, 哪個 instance
                    // 呼叫結果都一樣, 只是依命中那邊選對應的 instance。
                    final SemanticMatcherBase hitMatcher;
                    if ("ja".equals(hitLangFinal)) {
                        hitMatcher = semanticMatcherJa;
                    } else if ("fr".equals(hitLangFinal)) {
                        hitMatcher = semanticMatcherFr;
                    } else if ("es".equals(hitLangFinal)) {
                        hitMatcher = semanticMatcherEs;
                    } else if ("en".equals(hitLangFinal)) {
                        hitMatcher = semanticMatcherEn;
                    } else {
                        hitMatcher = semanticMatcherZh;
                    }
                    actionId = hitMatcher != null
                            ? hitMatcher.resolveCategoryRandomActionId(actionId) : null;
                } else if ("__RANDOM__".equals(actionId)) {
                    // TFBOY 這類 operation 在原廠問法裡沒有固定動作 - 沿用
                    // triggerRandomFillerAction() 已有的隨機動作池 (202 個動作裡
                    // 「隨機短/長」開頭的那批, 專門用來做這種「動一下讓它生動一點」的效果)。
                    actionId = actionDirect.resolveRandomActionId();
                }
                // 有聲動作：成句 TTS 對白唔講（免同動作本身嘅音效搶喇叭），
                // 直接播動作就算。注意 simulate response 照回 answer 文字
                //（前端氣泡顯示用），淨係唔出聲。
                if (actionId != null && ActionDirect.isSoundAction(actionId)) {
                    Log.i(TAG, "sound action " + actionId + ", suppressing TTS answer");
                    actionDirect.playActionDirect(actionId); // pure-direct：旧 AIDL 已无服务承载
                    return;
                }
                if (ttsAnswer != null && !ttsAnswer.isEmpty()) {
                    LedCenter.startMouthLedForTts();
                    if (!ttsCenter.speakAndroidTts(ttsAnswer, ttsLocale)) {
                        LedCenter.stopMouthLedForTts();
                    }
                }
                if (actionId == null) {
                    return; // CHAT 類或部分 FUNCTION 類沒有對應動作, TTS 完就結束
                }
                try {
                    Thread.sleep(SEMANTIC_TTS_TO_ACTION_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                actionDirect.playActionDirect(actionId); // pure-direct：旧 AIDL 已无服务承载
            }
        }, "SemanticMatchAction").start();
        return result;
    }

    // "打字當作自己說了這句" - 直接把輸入文字當成語音引擎
    // 已經辨識完的結果, 送去 handleSemanticMatch() 做問法庫
    // 問法配對 (中英西法日五份, 有設對話語言就跟設定，沒有就依輸入文字
    // 有沒有漢字自動判斷用哪份 - 見 handleSemanticMatch), 命中就立即執行
    // 悠聊原本的「TTS200ms動作」流程。
    // 和 speech/inject 不同: 這裡不經任何機身 AIDL (不靠
    // speech_startRecognized()/onSpeech() 這條 "不確定會不會真的觸發辨識"
    // 的路), 純粹是本地 JSON 配對 + 直接呼叫 robot.speech_startTTS()/
    // robot.action_PlayActionName(), 所以不需要 speechReady gate, 只
    // 需要 robot 本身已經 initRobot() 完成 (onCreate() 一開始就做了)。
    // response 即時告訴前端有沒有配對到 (matched/question/type/
    // operation/answer/actionId), 不用等 WebSocket event - 方便對話
    // 界面直接顯示配對結果, 不用一直等 EventBus。
    //
    // match() 找不到問法會回傳一個「聽不懂」的
    // fallback 回應 (不再是 null), 所以 matched:false 分支只
    // 剩「輸入是空白字串」這種 edge case 才會行到。
    public HttpServer.ApiResponse semanticSimulateResponse(Map<String, String> query) {
        String simText = ApiValidator.require(query, "text");
        SemanticMatcherZh.MatchResult simResult = handleSemanticMatch(simText, false);
        if (simResult == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":true,\"matched\":false,\"fallback\":false,\"input\":\"" + MainActivity.jsonSafe(simText) + "\"}");
        }
        // fallback:true＝聽唔明亂答（台詞池＋隨機動作；TTS/動作照行）。
        // matched 維持 true（沿用舊語義：有嘢答就算 matched），前端靠呢個旗
        // 做短句靜音窗（見 app-log.js），唔影響對話顯示。
        boolean isFallback = !simResult.matched;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"matched\":true,"
                + "\"fallback\":" + (isFallback ? "true" : "false") + ","
                + "\"input\":\"" + MainActivity.jsonSafe(simText) + "\","
                + "\"question\":\"" + MainActivity.jsonSafe(simResult.question) + "\","
                + "\"type\":\"" + MainActivity.jsonSafe(simResult.type) + "\","
                + "\"operation\":\"" + MainActivity.jsonSafe(simResult.operation) + "\","
                + "\"answer\":\"" + MainActivity.jsonSafe(simResult.answer) + "\","
                + "\"actionId\":\"" + MainActivity.jsonSafe(simResult.actionId) + "\"}");
    }
}


