package com.open.alpha2;


import android.util.Log;

import java.util.Map;

/**
 * 本地語意配對膠水：對話語言→matcher（未設就文字判斷）＋TTS/動作執行。
 *
 * FUNCTION 真執行（2026-09 起不再得把口）：
 * stopall=跟雙鍵總停（動作+TTS+音樂/電台，不停 mic）；
 * volumeup/down=行一格系統音量（經 AudioCenter，同 +/- pad）；
 * bton/btoff=開關藍牙（經 DeviceStatus）；
 * wifion/wifioff=開關無線網路（經 DeviceStatus）；
 * reboot/shutdown=無 REBOOT 權限，只 TTS 提示手動操作；
 * takepicture=高清存檔（經 CameraApi，不報路徑）；
 * musicplay/next/prev=本地音樂首首/上首/下首（經 AudioCenter，檔名排序循環）。
 * 新增 collaborator 經 setter 後補（避開 MainActivity onCreate 建構順序 cycle）。
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
    private final SemanticMatcherDe semanticMatcherDe;
    private final SemanticMatcherIt semanticMatcherIt;
    private final SemanticMatcherPt semanticMatcherPt;
    private final SemanticMatcherKo semanticMatcherKo;
    private final SemanticMatcherRu semanticMatcherRu;
    private final ActionDirect actionDirect;
    private final TtsCenter ttsCenter;

    /** FUNCTION 真執行 collaborator（setter 後補，見上；未補就 null-guard 跳過副作用、照 TTS）。 */
    private volatile AudioCenter audioCenter;
    private volatile DeviceStatus deviceStatus;
    private volatile CameraApi cameraApi;
    private volatile GestureCenter.Host speechStopHost;

    public void setAudioCenter(AudioCenter c) { audioCenter = c; }
    public void setDeviceStatus(DeviceStatus d) { deviceStatus = d; }
    public void setCameraApi(CameraApi c) { cameraApi = c; }
    public void setSpeechStopHost(GestureCenter.Host h) { speechStopHost = h; }

    /** 對話語言（zh/en/es/fr/ja/de/it/pt/ko/ru；null＝未設）：vosk/load 換 model 當時經 setDialogueLang()
     *  傳入。vosk 什麼話就對什麼 matcher；十個以外（setDialogueLang 唔收，
     *  入唔到 dialogueLang）一律行英文兜底（下面 "zh".equals 以外就係呢條規則）。 */
    private volatile String dialogueLang;

    /** 對話語言設定（VoskApi 經 vosk/load 換 model 當時傳入；僅收 zh/en/es/fr/ja/de/it/pt/ko/ru）。
     *  TTS 不綁這個——用什麼 TTS 全由用家決定，這裡僅決定用哪個 matcher，
     *  答案讀什麼 locale 跟主語言（見 handleSemanticMatch）。 */
    public void setDialogueLang(String lang) {
        if ("zh".equals(lang) || "en".equals(lang) || "es".equals(lang) || "fr".equals(lang) || "ja".equals(lang)
                || "de".equals(lang) || "it".equals(lang) || "pt".equals(lang) || "ko".equals(lang) || "ru".equals(lang)) dialogueLang = lang;
    }

    public SemanticCenter(SemanticMatcherZh semanticMatcherZh,
            SemanticMatcherEn semanticMatcherEn, SemanticMatcherEs semanticMatcherEs,
            SemanticMatcherFr semanticMatcherFr, SemanticMatcherJa semanticMatcherJa,
            SemanticMatcherDe semanticMatcherDe, SemanticMatcherIt semanticMatcherIt,
            SemanticMatcherPt semanticMatcherPt, SemanticMatcherKo semanticMatcherKo,
            SemanticMatcherRu semanticMatcherRu,
            ActionDirect actionDirect, TtsCenter ttsCenter) {
        this.semanticMatcherZh = semanticMatcherZh;
        this.semanticMatcherEn = semanticMatcherEn;
        this.semanticMatcherEs = semanticMatcherEs;
        this.semanticMatcherFr = semanticMatcherFr;
        this.semanticMatcherJa = semanticMatcherJa;
        this.semanticMatcherDe = semanticMatcherDe;
        this.semanticMatcherIt = semanticMatcherIt;
        this.semanticMatcherPt = semanticMatcherPt;
        this.semanticMatcherKo = semanticMatcherKo;
        this.semanticMatcherRu = semanticMatcherRu;
        this.actionDirect = actionDirect;
        this.ttsCenter = ttsCenter;
    }

    /** 判斷一句輸入文字是否應該用中文 matcher 處理: 有任何 CJK 統一表意文字 (漢字)
     *  就當中文, 完全沒有就當英文。現在降做後備：僅對話語言未設（開機預設、
     *  從來沒有 set 過）當時用；一設了就跟設定行（見 handleSemanticMatch），
     *  不再單靠文字。中英文夾雜的句子
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
     *  SemanticMatcherZh/En/Es/Fr/Ja/De/It/Pt/Ko/Ru 結構一致、資料獨立, 不會互相影響），只用主那個，
     *  唔試其他語言，唔搶走，唔兜底。
     *  未設就沿用 looksChinese()（有漢字行中文 semantic_zh.json，
     *  沒有就行英文 semantic_en.json；西文／法文／日文／德文／意大利文／葡萄牙文／韓文／俄文一定要經設定先入到）。
     *
     *  骨架語言（問法庫／fallback 空）match() 回 null：主 matcher 係骨架就全程靜音，
     *  唔會用別家 fallback 亂答。
     *
     *  TTS locale 跟主語言（不是跟輸入文字），嘴 LED 同動作流程不變。
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
        // 主語言：有設跟設定（zh/en/es/fr/ja/de/it/pt/ko/ru），未設就沿用 looksChinese（有漢字中文、冇就英文；
        // 西文／法文／日文／德文／意大利文／葡萄牙文／韓文／俄文一定要經設定先入到）。只用主那個 matcher，
        // 唔試其他語言：主唔中就用主嗰個 fallback，唔會跨語言搶走／兜底。
        final String lang;
        if (dialogueLang != null) {
            lang = dialogueLang;
        } else {
            lang = textChinese ? "zh" : "en";
        }
        final SemanticMatcherBase mainMatcher;
        final String hitLang;
        if ("ru".equals(lang)) {
            mainMatcher = semanticMatcherRu;
            hitLang = "ru";
        } else if ("ko".equals(lang)) {
            mainMatcher = semanticMatcherKo;
            hitLang = "ko";
        } else if ("pt".equals(lang)) {
            mainMatcher = semanticMatcherPt;
            hitLang = "pt";
        } else if ("it".equals(lang)) {
            mainMatcher = semanticMatcherIt;
            hitLang = "it";
        } else if ("de".equals(lang)) {
            mainMatcher = semanticMatcherDe;
            hitLang = "de";
        } else if ("ja".equals(lang)) {
            mainMatcher = semanticMatcherJa;
            hitLang = "ja";
        } else if ("fr".equals(lang)) {
            mainMatcher = semanticMatcherFr;
            hitLang = "fr";
        } else if ("es".equals(lang)) {
            mainMatcher = semanticMatcherEs;
            hitLang = "es";
        } else if ("en".equals(lang)) {
            mainMatcher = semanticMatcherEn;
            hitLang = "en";
        } else {
            mainMatcher = semanticMatcherZh;
            hitLang = "zh";
        }

        SemanticMatcherBase.MatchResult result = mainMatcher != null ? mainMatcher.match(text) : null;
        if (result == null) {
            return null; // 空白輸入／骨架空庫 - 悄悄地不做事, 不算錯誤
        }
        // 裡面條 thread 捉不到 reassigned 過的 result，用 final 影子。
        final SemanticMatcherBase.MatchResult finalResult = result;
        // 主語言：TTS locale＋分類隨機都跟它（final 下來給下面條 thread 用）。
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
        // TTS locale 跟主語言（西文／法文／日文／德文／意大利文／葡萄牙文／韓文／俄文答案用對應語言讀，否則口音會好怪）。
        final java.util.Locale ttsLocale;
        if ("ru".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ru");
        } else if ("ko".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("ko");
        } else if ("pt".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("pt");
        } else if ("it".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("it");
        } else if ("de".equals(hitLangFinal)) {
            ttsLocale = new java.util.Locale("de");
        } else if ("ja".equals(hitLangFinal)) {
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
                // FUNCTION 真執行：副作用先行（同 TTS/動作流程同 thread，背景線程安全；
                // camera takePhoto 最長 8s，絕不可搬去主線程）。
                // ttsOverride 非 null 就用佢代替 JSON 靜態答案（失敗/動態歌名用，跟命中語言）。
                String ttsOverride = runFunctionSideEffect(finalResult, hitLangFinal);
                // 先 resolve 做真實 action id（分類隨機/__RANDOM__ 本來播嗰刻先解，
                // 家下播之前就要知有無聲，所以提早解；解唔到 null 就當無動作行）。
                String actionId = finalResult.actionId;
                if (actionId != null && actionId.startsWith("__RANDOM_CATEGORY__")) {
                    // 用戶說到分類名 (例如「跳舞」/"Dance for me") 但沒有
                    // 說出具體是哪個動作 - 在 202 動作清單的對應分類 (例如
                    // DANCE_KIDS/YOGA_ANY) 裡面隨機選一個。和下面 "__RANDOM__"
                    // (完全不限分類, 202 個隨便選) 不同, 這是分類限定的隨機。中英文
                    // matcher 共用同一份 action_category_pools.json, 哪個 instance
                    // 呼叫結果都一樣, 直接用主 matcher。
                    final SemanticMatcherBase hitMatcher = mainMatcher;
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
                String speakText = (ttsOverride != null) ? ttsOverride : ttsAnswer;
                if (speakText != null && !speakText.isEmpty()) {
                    LedCenter.startMouthLedForTts();
                    if (!ttsCenter.speakAndroidTts(speakText, ttsLocale)) {
                        LedCenter.stopMouthLedForTts();
                    }
                }
                if (actionId == null) {
                    return; // resolve 唔到動作（隨機池空）或部分 FUNCTION 類沒動作，TTS 完就結束
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

    /** FUNCTION op 副作用（背景線程調用）。回 null=用 JSON 靜態答案照 TTS；
     *  回非 null=改播呢句（失敗提示/動態歌名，跟 lang 命中語言）。
     *  非 FUNCTION 或未知 op 一律回 null。
     *  reboot/shutdown 刻意無副作用（無 REBOOT 權限，答案本身已係手動提示）。 */
    private String runFunctionSideEffect(SemanticMatcherBase.MatchResult r, String lang) {
        if (r == null || !"FUNCTION".equals(r.type) || r.operation == null) return null;
        String op = r.operation;
        try {
            if ("volumeup".equals(op)) {
                if (audioCenter != null) audioCenter.adjustSystemVolume(true);
                return null;
            }
            if ("volumedown".equals(op)) {
                if (audioCenter != null) audioCenter.adjustSystemVolume(false);
                return null;
            }
            if ("stopall".equals(op)) {
                // 跟雙鍵總停：先截停各路，再由外層 TTS 播「停晒」答案（唔會自宮，
                // 因為 stop 在 speak 之前行）。
                try { actionDirect.stopActionWithRecovery(); } catch (Exception e) {
                    Log.w(TAG, "stopall: stopAction failed", e);
                }
                try {
                    if (speechStopHost != null) speechStopHost.stopAllSpeech();
                    else ttsCenter.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stopall: stopSpeech failed", e);
                }
                try {
                    if (audioCenter != null) {
                        audioCenter.stopLocalMusicPlayback();
                        audioCenter.stopRadioPlayback();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "stopall: stopMusic failed", e);
                }
                return null;
            }
            if ("bton".equals(op) || "btoff".equals(op)) {
                boolean wantOn = "bton".equals(op);
                if (deviceStatus == null) return null;
                boolean ok = deviceStatus.setBluetoothEnabled(wantOn);
                if (ok) return null;
                return functionText(lang, wantOn ? "btOnFail" : "btOffFail", null);
            }
            if ("wifion".equals(op) || "wifioff".equals(op)) {
                boolean wantOn = "wifion".equals(op);
                if (deviceStatus == null) return null;
                boolean ok = deviceStatus.setWifiEnabled(wantOn);
                if (ok) return null;
                return functionText(lang, wantOn ? "wifiOnFail" : "wifiOffFail", null);
            }
            if ("takepicture".equals(op)) {
                if (cameraApi == null) return functionText(lang, "camNotReady", null);
                try {
                    HttpServer.ApiResponse resp = cameraApi.takePhotoSave(
                            new java.util.HashMap<String, String>());
                    String body = resp != null ? resp.body : "";
                    if (body != null && body.contains("\"ok\":true")) return null; // 成功播 JSON 靜態「已拍好」
                    return functionText(lang, "camFail", null);
                } catch (Exception e) {
                    Log.w(TAG, "takepicture failed", e);
                    return functionText(lang, "camFail", null);
                }
            }
            if ("musicplay".equals(op) || "musicnext".equals(op) || "musicprev".equals(op)) {
                if (audioCenter == null) return functionText(lang, "musicNotReady", null);
                String name;
                if ("musicnext".equals(op)) name = audioCenter.playNextLocalMusic();
                else if ("musicprev".equals(op)) name = audioCenter.playPrevLocalMusic();
                else name = audioCenter.playFirstLocalMusic();
                if (name == null) return functionText(lang, "musicEmpty", null);
                String base = name;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                return functionText(lang, "musicNow", base);
            }
        } catch (Exception e) {
            Log.w(TAG, "runFunctionSideEffect op=" + op + " failed", e);
        }
        return null;
    }

    /** FUNCTION 失敗/動態提示多語言（key 見上；musicNow 的 name 係去副檔名歌名）。
     *  未知語言兜底英文。 */
    private static String functionText(String lang, String key, String name) {
        if ("es".equals(lang)) {
            if ("btOnFail".equals(key)) return "Lo siento, no pude activar el Bluetooth.";
            if ("btOffFail".equals(key)) return "Lo siento, no pude desactivar el Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Lo siento, no pude activar la red Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Lo siento, no pude desactivar la red Wi-Fi.";
            if ("camNotReady".equals(key)) return "Lo siento, la cámara no está lista.";
            if ("camFail".equals(key)) return "Lo siento, no pude tomar la foto.";
            if ("musicNotReady".equals(key)) return "Lo siento, la música no está lista.";
            if ("musicEmpty".equals(key)) return "No hay canciones en la carpeta de música.";
            if ("musicNow".equals(key)) return "De acuerdo, reproduciendo " + name + ".";
        } else if ("fr".equals(lang)) {
            if ("btOnFail".equals(key)) return "Désolé, impossible d'activer le Bluetooth.";
            if ("btOffFail".equals(key)) return "Désolé, impossible de désactiver le Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Désolé, impossible d'activer le Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Désolé, impossible de désactiver le Wi-Fi.";
            if ("camNotReady".equals(key)) return "Désolé, l'appareil photo n'est pas prêt.";
            if ("camFail".equals(key)) return "Désolé, la photo a échoué.";
            if ("musicNotReady".equals(key)) return "Désolé, la musique n'est pas prête.";
            if ("musicEmpty".equals(key)) return "Il n'y a aucune chanson dans le dossier de musique.";
            if ("musicNow".equals(key)) return "D'accord, je joue " + name + ".";
        } else if ("ja".equals(lang)) {
            if ("btOnFail".equals(key)) return "すみません、Bluetoothをオンにできませんでした。";
            if ("btOffFail".equals(key)) return "すみません、Bluetoothをオフにできませんでした。";
            if ("wifiOnFail".equals(key)) return "すみません、Wi-Fiをオンにできませんでした。";
            if ("wifiOffFail".equals(key)) return "すみません、Wi-Fiをオフにできませんでした。";
            if ("camNotReady".equals(key)) return "すみません、カメラの準備ができていません。";
            if ("camFail".equals(key)) return "すみません、撮影に失敗しました。";
            if ("musicNotReady".equals(key)) return "すみません、音楽の準備ができていません。";
            if ("musicEmpty".equals(key)) return "音楽フォルダに曲がありません。";
            if ("musicNow".equals(key)) return "わかりました、「" + name + "」を再生します。";
        } else if ("de".equals(lang)) {
            if ("btOnFail".equals(key)) return "Leider konnte ich Bluetooth nicht einschalten.";
            if ("btOffFail".equals(key)) return "Leider konnte ich Bluetooth nicht ausschalten.";
            if ("wifiOnFail".equals(key)) return "Leider konnte ich WLAN nicht einschalten.";
            if ("wifiOffFail".equals(key)) return "Leider konnte ich WLAN nicht ausschalten.";
            if ("camNotReady".equals(key)) return "Leider ist die Kamera nicht bereit.";
            if ("camFail".equals(key)) return "Leider hat das Foto nicht geklappt.";
            if ("musicNotReady".equals(key)) return "Leider ist die Musik nicht bereit.";
            if ("musicEmpty".equals(key)) return "Im Musikordner sind keine Lieder.";
            if ("musicNow".equals(key)) return "Verstanden, ich spiele jetzt " + name + ".";
        } else if ("it".equals(lang)) {
            if ("btOnFail".equals(key)) return "Mi dispiace, non riesco ad attivare il Bluetooth.";
            if ("btOffFail".equals(key)) return "Mi dispiace, non riesco a disattivare il Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Mi dispiace, non riesco ad attivare il Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Mi dispiace, non riesco a disattivare il Wi-Fi.";
            if ("camNotReady".equals(key)) return "Mi dispiace, la fotocamera non è pronta.";
            if ("camFail".equals(key)) return "Mi dispiace, la foto non è riuscita.";
            if ("musicNotReady".equals(key)) return "Mi dispiace, la musica non è pronta.";
            if ("musicEmpty".equals(key)) return "Non ci sono canzoni nella cartella della musica.";
            if ("musicNow".equals(key)) return "Va bene, riproduco " + name + ".";
        } else if ("pt".equals(lang)) {
            if ("btOnFail".equals(key)) return "Desculpe, não consegui ativar o Bluetooth.";
            if ("btOffFail".equals(key)) return "Desculpe, não consegui desativar o Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Desculpe, não consegui ativar o Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Desculpe, não consegui desativar o Wi-Fi.";
            if ("camNotReady".equals(key)) return "Desculpe, a câmera não está pronta.";
            if ("camFail".equals(key)) return "Desculpe, não consegui tirar a foto.";
            if ("musicNotReady".equals(key)) return "Desculpe, a música não está pronta.";
            if ("musicEmpty".equals(key)) return "Não há músicas na pasta de música.";
            if ("musicNow".equals(key)) return "Está bem, tocando " + name + ".";
        } else if ("ko".equals(lang)) {
            if ("btOnFail".equals(key)) return "죄송합니다, 블루투스를 켤 수 없습니다.";
            if ("btOffFail".equals(key)) return "죄송합니다, 블루투스를 끌 수 없습니다.";
            if ("wifiOnFail".equals(key)) return "죄송합니다, 와이파이를 켤 수 없습니다.";
            if ("wifiOffFail".equals(key)) return "죄송합니다, 와이파이를 끌 수 없습니다.";
            if ("camNotReady".equals(key)) return "죄송합니다, 카메라가 준비되지 않았습니다.";
            if ("camFail".equals(key)) return "죄송합니다, 사진 촬영에 실패했습니다.";
            if ("musicNotReady".equals(key)) return "죄송합니다, 음악이 준비되지 않았습니다.";
            if ("musicEmpty".equals(key)) return "음악 폴더에 노래가 없습니다.";
            if ("musicNow".equals(key)) return "알겠습니다, " + name + " 재생합니다.";
        } else if ("ru".equals(lang)) {
            if ("btOnFail".equals(key)) return "Извините, не получается включить Bluetooth.";
            if ("btOffFail".equals(key)) return "Извините, не получается выключить Bluetooth.";
            if ("wifiOnFail".equals(key)) return "Извините, не получается включить Wi-Fi.";
            if ("wifiOffFail".equals(key)) return "Извините, не получается выключить Wi-Fi.";
            if ("camNotReady".equals(key)) return "Извините, камера не готова.";
            if ("camFail".equals(key)) return "Извините, не получилось сделать снимок.";
            if ("musicNotReady".equals(key)) return "Извините, музыка не готова.";
            if ("musicEmpty".equals(key)) return "В папке с музыкой нет песен.";
            if ("musicNow".equals(key)) return "Хорошо, сейчас играет " + name + ".";
        } else if ("zh".equals(lang)) {
            if ("btOnFail".equals(key)) return "抱歉，無法打開藍牙。";
            if ("btOffFail".equals(key)) return "抱歉，無法關閉藍牙。";
            if ("wifiOnFail".equals(key)) return "抱歉，無法打開無線網路。";
            if ("wifiOffFail".equals(key)) return "抱歉，無法關閉無線網路。";
            if ("camNotReady".equals(key)) return "抱歉，相機尚未準備就緒。";
            if ("camFail".equals(key)) return "抱歉，照片拍攝失敗。";
            if ("musicNotReady".equals(key)) return "抱歉，音樂尚未準備就緒。";
            if ("musicEmpty".equals(key)) return "音樂資料夾中沒有歌曲。";
            if ("musicNow".equals(key)) return "好的，正在播放" + name + "。";
        }
        // en + 兜底
        if ("btOnFail".equals(key)) return "Sorry, I couldn't turn on Bluetooth.";
        if ("btOffFail".equals(key)) return "Sorry, I couldn't turn off Bluetooth.";
        if ("wifiOnFail".equals(key)) return "Sorry, I couldn't turn on Wi-Fi.";
        if ("wifiOffFail".equals(key)) return "Sorry, I couldn't turn off Wi-Fi.";
        if ("camNotReady".equals(key)) return "Sorry, the camera isn't ready.";
        if ("camFail".equals(key)) return "Sorry, I couldn't take the picture.";
        if ("musicNotReady".equals(key)) return "Sorry, the music player isn't ready.";
        if ("musicEmpty".equals(key)) return "There are no songs in the music folder.";
        if ("musicNow".equals(key)) return "Okay, now playing " + name + ".";
        return null;
    }

    // "打字當作自己說了這句" - 直接把輸入文字當成語音引擎
    // 已經辨識完的結果, 送去 handleSemanticMatch() 做問法庫
    // 問法配對 (只用主語言嗰份, 有設對話語言就跟設定，沒有就依輸入文字
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


