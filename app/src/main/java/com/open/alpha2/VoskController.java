package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vosk 離線 ASR（語音 tab）。機身已無 iFlytek/Nuance，用 Vosk
 * （alphacephei/vosk-android AAR，armeabi-v7a native，API 21+）頂上。
 *
 * <p>Model 不跟 app（每個約 65MB）：放 sdcard，這裡掃描自動偵測（user 要求）。
 * 掃描根：{@link Environment#getExternalStorageDirectory()}＋{@code /mnt/internal_sd}
 * 頂層目錄，有 {@code am/final.mdl} 就算一個可用 model（id＝目錄名）。
 *
 * <p>流程：{@link #scanModels()} 列出 → {@link #loadModel(String)}（背景 thread，
 * 幾秒＋~200MB RAM，一次只駐留一個）→ {@link #startListening()}（SpeechService
 * 自己開 AudioRecord，16kHz）→ partial 經 {@code asr_partial} event 即時顯示，
 * final 經沿用的 {@code asr_result} event（前端同打字模擬同一條：user 氣泡＋
 * 語意配對＋Android TTS）。
 *
 * <p>開放式辨識（open vocab）：唔起限定文法，直接無約束 Recognizer。
 *  幻聽只靠三樣硬編碼：conf 閘定值 0.01／TTS 時停 decode／前端單字唔出氣泡。
 *
 * <p>所有 public 方法 thread-safe；heavy 工（load）自己開 thread，callback
 * 經 listener 返回（SpeechService 那些在 main thread，要輕手）。
 */
public final class VoskController {
    private static final String TAG = "VoskController";
    public static final float SAMPLE_RATE = 16000.0f;
    private static final String PREFS_NAME = "robotpanel";
    private static final String PREF_MODEL_ID = "vosk_model_id";
    /** 對話語言（zh/en/es/fr/ja/de/it/pt/ko/ru）：loadModel 成功時記下，開機還原＋status 顯示用。 */
    private static final String PREF_DIALOGUE_LANG = "vosk_dialogue_lang";

    /** 掃描到的一個可用 model（langHint＝中文名，langEn＝英文名，前端跟 UI 語言選顯示）。 */
    public static final class VoskModelInfo {
        public final String id;
        public final String path;
        public final String langHint;
        public final String langEn;
        public final long sizeBytes;
        VoskModelInfo(String id, String path, String langHint, String langEn, long sizeBytes) {
            this.id = id;
            this.path = path;
            this.langHint = langHint;
            this.langEn = langEn;
            this.sizeBytes = sizeBytes;
        }
    }

    public enum State { IDLE, LOADING, READY, LISTENING, ERROR }

    private final Context appContext;
    private final SemanticMatcherZh matcherZh;

    private final SemanticMatcherEn matcherEn;

    private final SemanticMatcherEs matcherEs;

    private final SemanticMatcherFr matcherFr;

    private final SemanticMatcherJa matcherJa;

    private final SemanticMatcherDe matcherDe;

    private final SemanticMatcherIt matcherIt;

    private final SemanticMatcherPt matcherPt;

    private final SemanticMatcherKo matcherKo;

    private final SemanticMatcherRu matcherRu;
    private volatile State state = State.IDLE;
    private volatile String modelId;
    private volatile String lastError;

    // -- 模型下載 (實驗 tab 下載卡，後端直落 zip＋unzip) --
    // 官方 small 模型 catalog（對照 alphacephei.com/vosk/models）：
    // {modelId, url, sizeMb}：sizeMb 是官頁標示（約數，給用戶判斷流量）。
    // 大體積（1G 級）不收：部機 RAM 頂不住。固定 allowlist，不收任意 URL（防 SSRF）。
    private static final String DL_BASE = "https://alphacephei.com/vosk/models/";
    private static final String[][] DL_CATALOG = {
        {"vosk-model-small-cn-0.22", "42"},
        {"vosk-model-small-en-us-0.15", "40"},
        {"vosk-model-small-en-in-0.4", "36"},
        {"vosk-model-small-ru-0.22", "45"},
        {"vosk-model-small-fr-0.22", "41"},
        {"vosk-model-small-de-0.15", "45"},
        {"vosk-model-small-es-0.42", "39"},
        {"vosk-model-small-pt-0.3", "31"},
        {"vosk-model-small-tr-0.3", "35"},
        {"vosk-model-small-vn-0.4", "32"},
        {"vosk-model-small-it-0.22", "48"},
        {"vosk-model-small-nl-0.22", "39"},
        {"vosk-model-small-ca-0.4", "42"},
        {"vosk-model-small-fa-0.42", "53"},
        {"vosk-model-small-ja-0.22", "48"},
        {"vosk-model-small-eo-0.42", "42"},
        {"vosk-model-small-hi-0.22", "42"},
        {"vosk-model-small-cs-0.4-rhasspy", "44"},
        {"vosk-model-small-pl-0.22", "50"},
        {"vosk-model-small-uz-0.22", "49"},
        {"vosk-model-small-ko-0.22", "82"},
        {"vosk-model-br-0.8", "70"},
        {"vosk-model-small-gu-0.42", "100"},
        {"vosk-model-small-tg-0.22", "50"},
        {"vosk-model-small-te-0.42", "58"},
        {"vosk-model-small-ky-0.42", "49"},
        {"vosk-model-small-ka-0.42", "45"},
        {"vosk-model-small-kz-0.42", "58"},
        {"vosk-model-small-uk-v3-nano", "73"},
    };
    // 下載狀態機（同上面 load/listen State 獨立：下載中主 State 照舊 IDLE，不干擾）。
    // idle＝未開始／完成後重置前；downloading／unzipping 進行中；done／error／cancelled 終態。
    private volatile String dlState = "idle";
    private volatile String dlLang; // "cn"/"en"
    private volatile String dlModel; // 完整目錄名
    private volatile int dlProgress = -1; // 0-100，-1＝未知（unzip 中／長度不明）
    private volatile long dlBytes = 0;
    private volatile long dlTotal = -1;
    private volatile String dlError;
    private volatile boolean dlCancel = false;
    private Thread dlThread;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private String grammarJson; // 恆 null（開放式辨識，保留欄位免改 start 流程）
    private boolean stripSpaces; // 恆 false（舊中文 char-grammar 先用）

    /** final 平均 word conf 低過呢個就當底噪幻聽掉咗佢 (唔發佈、
     *  唔入配對；logcat 照留 "low-conf final dropped")。
     *  -1＝未知 (舊 lib／無 result 陣列) 照放行，不誤殺。
     *  定值 0.01：幾乎全放行，只擋極低分垃圾。 */
    private static final double MIN_FINAL_CONF = 0.01;

    public VoskController(Context context, SemanticMatcherZh zh,
            SemanticMatcherEn en, SemanticMatcherEs es, SemanticMatcherFr fr, SemanticMatcherJa ja,
            SemanticMatcherDe de, SemanticMatcherIt it, SemanticMatcherPt pt,
            SemanticMatcherKo ko, SemanticMatcherRu ru) {
        this.appContext = context.getApplicationContext();
        this.matcherZh = zh;
        this.matcherEn = en;
        this.matcherEs = es;
        this.matcherFr = fr;
        this.matcherJa = ja;
        this.matcherDe = de;
        this.matcherIt = it;
        this.matcherPt = pt;
        this.matcherKo = ko;
        this.matcherRu = ru;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable ignore) {
        }
        // 上次選過的 model（如果還在）背景自動載入，開啟頁面即 ready。
        final String saved = prefs().getString(PREF_MODEL_ID, null);
        if (saved != null && findModelDir(saved) != null) {
            loadModel(saved);
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -- 掃描 ---------------------------------------------------------------

    /** 掃 sdcard 頂層找模型目錄（標準版 am/final.mdl，或官方扁平包頂層
     *  final.mdl——例 pt-0.3 出廠即扁平，libvosk 原生讀得到，之前驗收卡太嚴
     *  反而將之下載判失敗）。快，哪條 thread call 都得。純檔案 IO，不碰
     *  org.vosk，API 19 都用得（static 是刻意的，等 vosk/models endpoint
     *  在 controller 未起（19 機）都列到表）。 */
    public static List<VoskModelInfo> scanModels() {
        Set<String> roots = new LinkedHashSet<>();
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) roots.add(ext.getCanonicalPath());
        } catch (Exception ignore) {
        }
        roots.add("/mnt/internal_sd");
        List<VoskModelInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String root : roots) {
            File[] dirs;
            try {
                dirs = new File(root).listFiles();
            } catch (Exception e) {
                continue;
            }
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                if (!isModelDir(d)) continue;
                String canon;
                try {
                    canon = d.getCanonicalPath();
                } catch (Exception e) {
                    canon = d.getAbsolutePath();
                }
                if (!seen.add(canon)) continue;
                out.add(new VoskModelInfo(d.getName(), canon, guessLang(d.getName()),
                        guessLangEn(d.getName()), dirSize(d)));
            }
        }
        return out;
    }

    /** 目錄算不算可用模型：標準版（am/final.mdl）或官方扁平版（頂層 final.mdl）。 */
    private static boolean isModelDir(File d) {
        if (new File(new File(d, "am"), "final.mdl").isFile()) return true;
        return new File(d, "final.mdl").isFile();
    }

    /** 由 model id parse 語言段 (vosk-model[-small]-xx-...，xx 是語言碼)，
     *  查全表。parse 段兩語版共用（之前 guessLang／guessLangEn 各複製一份）。 */
    private static String[] parseLangSub(String id) {
        String lang = null;
        String sub = null;
        String[] parts = id.toLowerCase(java.util.Locale.US).split("-");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("model") || parts[i].equals("small")) {
                // 跳過 "model"/"small" 這些固定前綴 (如 vosk-model-small-cn-0.22，
                // 語言段是 "small" 後面那格，不是 "model" 後面)。
                int j = i + 1;
                while (j < parts.length
                        && (parts[j].equals("model") || parts[j].equals("small"))) {
                    j++;
                }
                if (j < parts.length) lang = parts[j];
                if (j + 1 < parts.length && parts[j + 1].matches("[a-z]{2}")) {
                    sub = parts[j + 1];
                }
                break;
            }
        }
        return new String[]{lang, sub};
    }

    private static String guessLang(String id) {
        String[] ls = parseLangSub(id);
        String lang = ls[0];
        String sub = ls[1];
        if (lang == null) return "未知";
        if (lang.equals("en") && "in".equals(sub)) return "印度英文";
        if (lang.equals("ar") && "tn".equals(sub)) return "突尼西亞阿拉伯文";
        String name = LANG_NAMES.get(lang);
        return name != null ? name : "未知 (" + lang + ")";
    }

    private static final java.util.Map<String, String> LANG_NAMES = buildLangNames(false);
    private static final java.util.Map<String, String> LANG_NAMES_EN = buildLangNames(true);

    /** 中英兩表同一個 code 表一次建成（之前兩個 builder 各複製同一份 pairs）。 */
    private static java.util.Map<String, String> buildLangNames(boolean english) {
        // Vosk 官方 models 頁 35 個 small 全覆蓋 (code → 中文名／英文名)。
        String[][] pairs = {
            {"en", "英文", "English"}, {"cn", "中文", "Chinese"}, {"zh", "中文", "Chinese"},
            {"ja", "日文", "Japanese"}, {"ru", "俄文", "Russian"},
            {"fr", "法文", "French"}, {"de", "德文", "German"},
            {"es", "西班牙文", "Spanish"}, {"pt", "葡萄牙文", "Portuguese"},
            {"tr", "土耳其文", "Turkish"},
            {"vn", "越南文", "Vietnamese"}, {"vi", "越南文", "Vietnamese"},
            {"it", "意大利文", "Italian"},
            {"nl", "荷蘭文", "Dutch"}, {"ca", "加泰隆尼亞文", "Catalan"},
            {"ar", "阿拉伯文", "Arabic"},
            {"fa", "波斯文", "Persian"}, {"tl", "菲律賓文", "Filipino"},
            {"uk", "烏克蘭文", "Ukrainian"},
            {"kz", "哈薩克文", "Kazakh"}, {"sv", "瑞典文", "Swedish"},
            {"eo", "世界語", "Esperanto"},
            {"hi", "印地文", "Hindi"}, {"cs", "捷克文", "Czech"},
            {"pl", "波蘭文", "Polish"},
            {"uz", "烏茲別克文", "Uzbek"}, {"ko", "韓文", "Korean"},
            {"br", "布列塔尼文", "Breton"},
            {"gu", "古吉拉特文", "Gujarati"}, {"tg", "塔吉克文", "Tajik"},
            {"te", "泰盧固文", "Telugu"},
            {"ky", "吉爾吉斯文", "Kyrgyz"}, {"ka", "格魯吉亞文", "Georgian"},
        };
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String[] p : pairs) m.put(p[0], english ? p[2] : p[1]);
        return m;
    }

    /** guessLang 的英文版（同一個 parse，前端 uiLang＝en 時顯示；catalog/models
     *  帶 langEn，舊客只讀 lang 不受影響）。 */
    private static String guessLangEn(String id) {
        String[] ls = parseLangSub(id);
        String lang = ls[0];
        String sub = ls[1];
        if (lang == null) return "unknown";
        if (lang.equals("en") && "in".equals(sub)) return "Indian English";
        if (lang.equals("ar") && "tn".equals(sub)) return "Tunisian Arabic";
        String name = LANG_NAMES_EN.get(lang);
        return name != null ? name : "unknown (" + lang + ")";
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception e) {
            return 0;
        }
        if (files == null) return 0;
        for (File f : files) {
            try {
                total += f.isDirectory() ? dirSize(f) : f.length();
            } catch (Exception ignore) {
            }
        }
        return total;
    }

    private static String findModelDir(String id) {
        for (VoskModelInfo m : scanModels()) {
            if (m.id.equals(id)) return m.path;
        }
        return null;
    }

    // -- 載入 -----------------------------------------------------------------

    public synchronized State getState() {
        return state;
    }

    public synchronized String getModelId() {
        return modelId;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized boolean isListening() {
        return state == State.LISTENING;
    }

    private void setState(State s, String model, String err) {
        synchronized (this) {
            state = s;
            if (model != null) modelId = model;
            lastError = err;
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"" + s.name().toLowerCase(java.util.Locale.US) + "\""
                        + ",\"model\":" + (modelId == null ? "null"
                                : "\"" + modelId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        + (err == null ? "" : ",\"message\":\""
                                + err.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                        + "}");
    }

    /** 背景載入 model（幾秒）。return null＝已開始載入，否則是即時錯誤。
     *  進度／結果經 vosk_state event＋status 查。 */
    public String loadModel(final String id) {
        final String path = findModelDir(id);
        if (path == null) {
            setState(State.ERROR, null, "model not found on sdcard: " + id);
            return "model not found on sdcard: " + id;
        }
        synchronized (this) {
            if (state == State.LOADING) return "already loading";
            stopLocked();
            closeModelLocked();
            state = State.LOADING;
            lastError = null;
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"loading\",\"model\":\"" + id.replace("\"", "\\\"") + "\"}");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Model m = new Model(path);
                    String grammar = buildGrammar(id);
                    synchronized (VoskController.this) {
                        // 載入期間被 unload／load 過就丟掉這個
                        if (state != State.LOADING) {
                            try {
                                m.close();
                            } catch (Throwable ignore) {
                            }
                            return;
                        }
                        model = m;
                        grammarJson = grammar;
                    }
                    android.content.SharedPreferences.Editor ed = prefs().edit();
                    ed.putString(PREF_MODEL_ID, id);
                    String mappedLang = langOfModelId(id);
                    if (mappedLang != null) {
                        // model 即語言：換 model 一併記下對話語言，等 status／前端不用估。
                        ed.putString(PREF_DIALOGUE_LANG, mappedLang);
                    }
                    ed.apply();
                    Log.i(TAG, "model loaded: " + id
                            + (grammar != null ? " (grammar constrained)" : " (open vocab)"));
                    setState(State.READY, id, null);
                } catch (Throwable e) {
                    Log.w(TAG, "loadModel failed: " + id, e);
                    setState(State.ERROR, null, "load failed: " + e.getMessage());
                }
            }
        }, "VoskLoad").start();
        return null;
    }

    /** model id → 對話語言（zh/en/es/fr/ja/de/it/pt/ko/ru；認不到回 null）。同 buildGrammar 之前內聯
     *  那串 contains 逐字一樣，抽出來等 vosk/load 同步配對語言都用同一套。
     *  注意 "es" 本身太易撞（test/best 都有 es），一定要加邊界先認
     *  （spanish/español/-es-/es- 開頭/-es 結尾/成個就係 es）。
     *  "fr" 一樣太易撞（from 都有 fr），同樣要加邊界先認。
     *  "ja" 都要加邊界先認（major/january 都有 ja）。
     *  新五語一樣：de 易撞（model/made 都有 de）、it 易撞（with/city 都有 it）、
     *  pt 易撞（empty 都有 pt）、ko/ru 都要加邊界，先認全名再認邊界 code。 */
    static String langOfModelId(String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(java.util.Locale.US);
        if (lower.contains("cn") || lower.contains("zh") || lower.contains("mandarin")) return "zh";
        if (lower.contains("spanish") || lower.contains("espa")) return "es";
        if (lower.contains("-es-") || lower.startsWith("es-") || lower.endsWith("-es")
                || lower.endsWith("_es") || lower.equals("es")) return "es";
        if (lower.contains("french") || lower.contains("fran")) return "fr";
        if (lower.contains("-fr-") || lower.startsWith("fr-") || lower.endsWith("-fr")
                || lower.endsWith("_fr") || lower.equals("fr")) return "fr";
        if (lower.contains("japanese") || lower.contains("japan")) return "ja";
        if (lower.contains("-ja-") || lower.startsWith("ja-") || lower.endsWith("-ja")
                || lower.endsWith("_ja") || lower.equals("ja")) return "ja";
        if (lower.contains("german") || lower.contains("deutsch")) return "de";
        if (lower.contains("-de-") || lower.startsWith("de-") || lower.endsWith("-de")
                || lower.endsWith("_de") || lower.equals("de")) return "de";
        if (lower.contains("italian") || lower.contains("italiano")) return "it";
        if (lower.contains("-it-") || lower.startsWith("it-") || lower.endsWith("-it")
                || lower.endsWith("_it") || lower.equals("it")) return "it";
        if (lower.contains("portuguese") || lower.contains("portugues")) return "pt";
        if (lower.contains("-pt-") || lower.startsWith("pt-") || lower.endsWith("-pt")
                || lower.endsWith("_pt") || lower.equals("pt")) return "pt";
        if (lower.contains("korean") || lower.contains("korea")) return "ko";
        if (lower.contains("-ko-") || lower.startsWith("ko-") || lower.endsWith("-ko")
                || lower.endsWith("_ko") || lower.equals("ko")) return "ko";
        if (lower.contains("russian")) return "ru";
        if (lower.contains("-ru-") || lower.startsWith("ru-") || lower.endsWith("-ru")
                || lower.endsWith("_ru") || lower.equals("ru")) return "ru";
        if (lower.contains("en")) return "en";
        return null;
    }

    /** 目前對話語言（zh/en/es/fr/ja/de/it/pt/ko/ru）：上次 load 記下的；未記過就由目前 model 推斷（都沒有就 zh）。
     *  語言跟 model 行——模型鍵就是語言鍵，不用用家另外選。 */
    public String getDialogueLang() {
        try {
            String v = prefs().getString(PREF_DIALOGUE_LANG, null);
            if ("zh".equals(v) || "en".equals(v) || "es".equals(v) || "fr".equals(v) || "ja".equals(v)
                    || "de".equals(v) || "it".equals(v) || "pt".equals(v) || "ko".equals(v) || "ru".equals(v)) return v;
            String m = langOfModelId(modelId);
            if (m != null) return m;
        } catch (Throwable ignore) {}
        return "zh";
    }

    /** 起限定文法已移除：而家一律開放式辨識（open vocab），直接回 null。絕不 throw。 */
    private String buildGrammar(String id) {
        stripSpaces = false;
        return null;
    }

    /** 丟掉個 model（不清 prefs 記住的選擇）。 */
    public synchronized void unload() {
        stopLocked();
        closeModelLocked();
        state = State.IDLE;
        modelId = null;
        lastError = null;
        EventBus.get().publish("vosk_state", "{\"state\":\"idle\",\"model\":null}");
    }

    private void closeModelLocked() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
        if (model != null) {
            try {
                model.close();
            } catch (Throwable ignore) {
            }
            model = null;
        }
        grammarJson = null;
    }

    // -- 聆聽 -----------------------------------------------------------------

    /**
     * 開始聆聽。return null＝已開始，否則是錯誤字串（model 未載入／已在聽／mic 開不到）。
     * 結果經 asr_partial（即時）／asr_result（成句，沿用舊管線：氣泡＋語意配對＋TTS）。
     */
    public synchronized String startListening() {
        if (state == State.LISTENING) return null;
        return startListeningLocked();
    }

    /** 開始聆聽內部實作（完整問法文法）。必須在 synchronized 內／LISTENING 檢查之後 call。 */
    private synchronized String startListeningLocked() {
        if (state != State.READY || model == null) {
            return state == State.LOADING ? "model loading, try later" : "load a model first";
        }
        // mic 預檢：部機不支援 16kHz capture 就不要開（開了都是垃圾/[unk]）。
        // 同 SpeechService 內部同一個參數（MONO／PCM16）。
        try {
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                return "mic does not support 16kHz mono PCM (minBuf=" + minBuf + ")";
            }
            Log.i(TAG, "mic 16kHz check ok, minBuf=" + minBuf);
        } catch (Throwable e) {
            Log.w(TAG, "mic check failed", e);
            return "mic check failed: " + e.getMessage();
        }
        Recognizer rec = null;
        SpeechService service = null;
        String activeGrammar = grammarJson;
        try {
            if (activeGrammar != null) {
                Recognizer g;
                try {
                    g = new Recognizer(model, SAMPLE_RATE, activeGrammar);
                } catch (Throwable e) {
                    Log.w(TAG, "grammar recognizer failed, fallback open vocab", e);
                    g = new Recognizer(model, SAMPLE_RATE);
                }
                rec = g;
            } else {
                rec = new Recognizer(model, SAMPLE_RATE);
            }
            // word 置信度：final JSON 先有 result/conf 陣列，先做到噪音閘
            // (見 onVoskFinal；底噪幻聽通常低分，真人說話高分)。舊版 lib 無
            // 呢個 method 就當無事 (fail-open，下面 avgWordConf 回 -1 照放行)。
            try {
                rec.setWords(true);
            } catch (Throwable ignore) {
            }
            // endpointer 調校已移除：Recognizer 一律用 library 預設（之前跟 persist 偏好）。
            service = new SpeechService(rec, SAMPLE_RATE);
            service.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    String text = transcript(hypothesis, "partial");
                    if (text != null && !text.isEmpty()) {
                        EventBus.get().publish("asr_partial",
                                "{\"text\":\"" + escape(text) + "\"}");
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    onVoskFinal(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    onVoskFinal(hypothesis);
                }

                @Override
                public void onError(Exception e) {
                    Log.w(TAG, "vosk error", e);
                    synchronized (VoskController.this) {
                        if (state == State.LISTENING) {
                            state = State.READY;
                        }
                    }
                    setState(getState(), null,
                            e == null ? "unknown mic error" : String.valueOf(e.getMessage()));
                }

                @Override
                public void onTimeout() {
                    stopListening();
                }
            });
            recognizer = rec;
            speechService = service;
            state = State.LISTENING;
            lastError = null;
        } catch (Throwable e) {
            Log.w(TAG, "startListening failed", e);
            if (service != null) {
                try {
                    service.shutdown();
                } catch (Throwable ignore) {
                }
            } else if (rec != null) {
                try {
                    rec.close();
                } catch (Throwable ignore) {
                }
            }
            return "mic start failed: " + e.getMessage();
        }
        EventBus.get().publish("vosk_state",
                "{\"state\":\"listening\",\"model\":\"" + escape(modelId == null ? "" : modelId) + "\"}");
        return null;
    }

    /** 成句結果：沿用舊管線（氣泡＋語意配對＋TTS）。
     *  跑在 SpeechService listener thread（main）。絕不 throw。
     *  認可度閘定值 0.01 - 平均 word conf 低過即當底噪掉咗
     *  (唔發佈唔配對；未知 conf 回 -1 照放行)。 */
    private void onVoskFinal(String hypothesis) {
        try {
            String text = transcript(hypothesis, "text");
            if (text == null || text.isEmpty()) return;
            double conf = avgWordConf(hypothesis);
            if (conf >= 0 && conf < MIN_FINAL_CONF) {
                Log.i(TAG, "low-conf final dropped (" + conf + "): " + text);
                return;
            }
            // 斷症用：log 下認到什麼＋幾多分（EventBus 只帶去前端，logcat 看不到內容）。
            Log.i(TAG, "final: " + text + " conf="
                    + (conf < 0 ? "?" : String.format("%.2f", conf)));
            EventBus.get().publish("asr_result",
                    "{\"text\":\"" + escape(text) + "\"}");
        } catch (Throwable e) {
            Log.w(TAG, "onVoskFinal failed", e);
        }
    }

    /** final JSON result 陣列平均 word conf；無 result 陣列／解析失敗
     *  回 -1 (未知，呼叫方照放行)。 */
    private static double avgWordConf(String json) {
        if (json == null) return -1;
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("result");
            if (arr == null || arr.length() == 0) return -1;
            double sum = 0;
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                double c = w.optDouble("conf", -1);
                if (c >= 0) {
                    sum += c;
                    n++;
                }
            }
            return n == 0 ? -1 : sum / n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** TTS 播音時 pause 回來（不放 mic，只是不 decode），播完 resume。
     *  不在聽就 no-op。 */
    public synchronized void setPaused(boolean paused) {
        if (speechService != null && state == State.LISTENING) {
            try {
                speechService.setPause(paused);
            } catch (Throwable ignore) {
            }
        }
    }

    /** TTS 開始播：即 pause decode (同 setPaused(true)）。 */
    public synchronized void onTtsStarted() {
        setPaused(true);
    }

    /** TTS 播完：即 resume decode。 */
    public void onTtsFinished() {
        synchronized (VoskController.this) {
            setPaused(false);
        }
    }

    public synchronized String stopListening() {
        stopLocked();
        if (state == State.LISTENING || model != null) {
            state = model != null ? State.READY : State.IDLE;
        }
        // server 側停 (如讓 mic 給小智) 都要推 event，前端才會關燈。
        EventBus.get().publish("vosk_state",
                "{\"state\":\"" + state.name().toLowerCase(java.util.Locale.US) + "\""
                        + ",\"model\":" + (modelId == null ? "null" : "\"" + escape(modelId) + "\"")
                        + "}");
        return null;
    }

    private void stopLocked() {
        if (speechService != null) {
            try {
                speechService.stop();
            } catch (Throwable ignore) {
            }
            try {
                speechService.shutdown();
            } catch (Throwable ignore) {
            }
            speechService = null;
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
    }

    public synchronized void shutdown() {
        if ("downloading".equals(dlState) || "unzipping".equals(dlState)) dlCancel = true;
        stopLocked();
        closeModelLocked();
        state = State.IDLE;
    }

    /** optText＋trim。空即回 null 唔發佈。partial 同 final 共用。 */
    private String transcript(String json, String key) {
        String s = optText(json, key);
        if (s != null) {
            s = s.trim();
        }
        if (s == null || s.isEmpty()) return null;
        return s;
    }

    private static String optText(String json, String key) {
        if (json == null) return null;
        try {
            String s = new JSONObject(json).optString(key, "");
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // -- 模型下載＋自動 unzip -------------------------------------------------
    // 前端流程：實驗 tab 下載卡 → vosk/catalog 列出全部可下載（已下載不顯示）→
    // vosk/download?model=<id> 起背景 thread → 前端 poll vosk/download_status
    // （或聽 vosk_download event）顯示進度 → done 後自動 refresh＋load
    // （這裡順便 auto-load，省一 round trip）。

    /** modelId→{modelId,url}。不在 catalog 就回 null（由上層轉做 400）。 */
    public static String[] downloadTarget(String modelId) {
        if (modelId == null) return null;
        for (String[] e : DL_CATALOG) {
            if (e[0].equals(modelId)) return new String[]{e[0], DL_BASE + e[0] + ".zip"};
        }
        return null;
    }

    public static boolean isDownloadable(String modelId) {
        return downloadTarget(modelId) != null;
    }

    /** 實驗 tab 下載卡用：全部可下載＋已下載旗＋對話旗。
     *  dialogue＝有對認 matcher、可對話（十語；同 SemanticCenter 規則一致，
     *  未有 matcher 的語言一律 false）。純檔案 IO＋常數判斷，哪條 thread call 都得。 */
    public static String catalogJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"catalog\":[");
        boolean first = true;
        for (String[] e : DL_CATALOG) {
            if (!first) sb.append(',');
            first = false;
            String id = e[0];
            String ml = langOfModelId(id);
            sb.append("{\"id\":\"").append(escape(id)).append('"');
            sb.append(",\"lang\":\"").append(escape(guessLang(id))).append('"');
            sb.append(",\"langEn\":\"").append(escape(guessLangEn(id))).append('"');
            sb.append(",\"sizeMb\":").append(e[1]);
            sb.append(",\"downloaded\":").append(findModelDir(id) != null);
            sb.append(",\"dialogue\":").append("zh".equals(ml) || "en".equals(ml) || "es".equals(ml) || "fr".equals(ml) || "ja".equals(ml)
                    || "de".equals(ml) || "it".equals(ml) || "pt".equals(ml) || "ko".equals(ml) || "ru".equals(ml));
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    /** 下載根＝scanModels 第一個可寫根（同掃描一致，unzip 完頂層即見到 model 目錄）。 */
    private static File downloadRoot() {
        File ext = null;
        try {
            ext = Environment.getExternalStorageDirectory();
        } catch (Throwable ignore) {
        }
        if (ext != null) {
            try {
                if (ext.exists() ? ext.canWrite() : ext.mkdirs()) return ext;
                // exists 但不肯定寫得入也照樣嘗試（舊機 canWrite 誤報），寫不入後面會再錯。
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** 開始下載＋unzip。modelId 必須在 catalog（見 downloadTarget）。
     *  return null＝已開始，否則即時錯誤字串。 */
    public synchronized String startDownload(String modelId) {
        String[] target = downloadTarget(modelId);
        if (target == null) return "unknown downloadable model: " + modelId;
        if ("downloading".equals(dlState) || "unzipping".equals(dlState)) {
            return "already downloading (" + dlModel + " " + dlProgress + "%)";
        }
        // 單通道：動作包下載緊就唔開得（見 DownloadGate，實驗 tab 資源下載卡）。
        if (!DownloadGate.tryAcquire("vosk", modelId)) {
            return "another download in progress (" + DownloadGate.describe() + ")";
        }
        final String url = target[1];
        // 已有就不要重落（scan 同 loadModel 共用判定：見 isModelDir）。
        // 狀態順手撥 done——之前失敗殘留的 error 訊息不用再留。
        if (findModelDir(modelId) != null) {
            dlModel = modelId;
            dlLang = guessLang(modelId);
            dlBytes = 0;
            dlTotal = 0;
            setDl("done", 100, null);
            publishDl();
            return "already exists: " + modelId;
        }
        dlState = "downloading";
        dlLang = guessLang(modelId);
        dlModel = modelId;
        dlProgress = 0;
        dlBytes = 0;
        dlTotal = -1;
        dlError = null;
        dlCancel = false;
        publishDl();
        dlThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload(url, modelId);
            }
        }, "VoskDownload");
        dlThread.start();
        return null;
    }

    public synchronized String cancelDownload() {
        if (!"downloading".equals(dlState) && !"unzipping".equals(dlState)) {
            return "not downloading";
        }
        dlCancel = true;
        return null;
    }

    /** download_status endpoint 用（透傳 JSON，不經 ApiResponse 包多層）。 */
    public String downloadStatusJson() {
        String st;
        String lang;
        String mid;
        int prog;
        long bytes;
        long total;
        String err;
        synchronized (this) {
            st = dlState;
            lang = dlLang;
            mid = dlModel;
            prog = dlProgress;
            bytes = dlBytes;
            total = dlTotal;
            err = dlError;
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"lang\":").append(lang == null ? "null" : "\"" + escape(lang) + "\"");
        sb.append(",\"model\":").append(mid == null ? "null" : "\"" + escape(mid) + "\"");
        sb.append(",\"progress\":").append(prog);
        sb.append(",\"bytes\":").append(bytes);
        sb.append(",\"total\":").append(total);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private void publishDl() {
        String st;
        String lang;
        String mid;
        int prog;
        String err;
        synchronized (this) {
            st = dlState;
            lang = dlLang;
            mid = dlModel;
            prog = dlProgress;
            err = dlError;
        }
        StringBuilder sb = new StringBuilder("{\"state\":\"");
        sb.append(escape(st)).append('"');
        sb.append(",\"lang\":").append(lang == null ? "null" : "\"" + escape(lang) + "\"");
        sb.append(",\"model\":").append(mid == null ? "null" : "\"" + escape(mid) + "\"");
        sb.append(",\"progress\":").append(prog);
        if (err != null) sb.append(",\"message\":\"").append(escape(err)).append('"');
        sb.append('}');
        try {
            EventBus.get().publish("vosk_download", sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private void setDl(String st, int prog, String err) {
        synchronized (this) {
            dlState = st;
            dlProgress = prog;
            dlError = err;
        }
        publishDl();
    }

    private void runDownload(String urlStr, String modelId) {
        File root = downloadRoot();
        File zipTmp = new File(root, modelId + ".zip.tmp");
        File zipDone = new File(root, modelId + ".zip");
        java.util.Set<String> beforeDl = new java.util.HashSet<>();
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
            // 快照：之後新增的頂層目錄都是今次 unzip 落的，失敗／取消即清除，
            // 免每次重試都在 sdcard 留下一份垃圾。
            beforeDl = listDirNames(root);
            // --- 下載 ---
            java.net.HttpURLConnection conn = null;
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            try {
                java.net.URL url = new java.net.URL(urlStr);
                conn = (java.net.HttpURLConnection) url.openConnection();
                try {
                    XiaozhiTrustAllSsl.applyTrustAll(conn);
                } catch (Throwable ignore) {
                }
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "OpenAlpha2");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                // 用 int 版 getContentLength（API 1 已有）：model zip ~50MB 遠細過 2GB；
                // getContentLengthLong 要 API 24+，這個 APK 要行 API 21/22（見 manifest），
                // 直接 call 會拋 NoSuchMethodError（同 vosk 熔斷保 API 19 同一類）。
                long total = -1;
                try {
                    total = conn.getContentLength();
                } catch (Throwable ignore) {
                }
                synchronized (this) {
                    dlTotal = total;
                }
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(zipTmp);
                byte[] buf = new byte[32768];
                long got = 0;
                int n;
                long lastPub = 0;
                while ((n = in.read(buf)) != -1) {
                    synchronized (this) {
                        if (dlCancel) throw new java.io.IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    got += n;
                    synchronized (this) {
                        dlBytes = got;
                        if (total > 0) dlProgress = (int) Math.min(100, got * 100 / total);
                    }
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastPub > 500) {
                        lastPub = now;
                        publishDl();
                    }
                }
                try {
                    out.flush();
                } catch (Throwable ignore) {
                }
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignore) {
                }
                try {
                    if (out != null) out.close();
                } catch (Throwable ignore) {
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
            synchronized (this) {
                if (dlCancel) throw new java.io.IOException("cancelled");
            }
            // --- unzip（自己解，不依賴系統 unzip binary）---
            setDl("unzipping", 100, null);
            try {
                zipTmp.renameTo(zipDone);
            } catch (Throwable ignore) {
            }
            File src = zipDone.exists() ? zipDone : zipTmp;
            unzipToRoot(src, root);
            try {
                src.delete();
            } catch (Throwable ignore) {
            }
            try {
                if (zipTmp.exists()) zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // 驗收：解完要有模型目錄才算數（標準 am/final.mdl 或官方扁平包
            // 頂層 final.mdl，見 isModelDir）。不收貨就連 zip 帶今次新增目錄
            // 一齊清除——舊的 model 目錄（快照之前已存在）一律不碰。
            if (findModelDir(modelId) == null) {
                cleanupNewDirs(root, beforeDl);
                try {
                    src.delete();
                } catch (Throwable ignore) {
                }
                throw new java.io.IOException("unzip ok but model not found: " + modelId
                        + " (need am/final.mdl or top-level final.mdl)");
            }
            setDl("done", 100, null);
            DownloadGate.release("vosk");
            Log.i(TAG, "model downloaded+unzipped: " + modelId);
            // 順手自動載入（省前端一 round trip；失敗不當下載失敗，狀態照 done）。
            // 用家正在聽就讓路——loadModel 會停 mic 斷 session，等用家自己切過去。
            boolean listeningNow;
            synchronized (this) {
                listeningNow = state == State.LISTENING;
            }
            if (listeningNow) {
                Log.i(TAG, "skip auto-load after download (user listening): " + modelId);
            } else {
                try {
                    String err = loadModel(modelId);
                    if (err != null) Log.w(TAG, "auto-load after download failed: " + err);
                } catch (Throwable e) {
                    Log.w(TAG, "auto-load after download threw", e);
                }
            }
        } catch (Throwable e) {
            boolean cancelled;
            synchronized (this) {
                cancelled = dlCancel;
            }
            String msg = String.valueOf(e.getMessage());
            Log.w(TAG, "download failed: " + modelId + ": " + msg, e);
            try {
                zipTmp.delete();
            } catch (Throwable ignore) {
            }
            // 失敗／取消都清場：刪走今次 unzip 新增的目錄（zip 損壞／中途取消
            // 留下的半包），快照之前已存在的目錄一律不碰。
            cleanupNewDirs(root, beforeDl);
            DownloadGate.release("vosk");
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setDl("cancelled", dlProgress, "cancelled");
            } else {
                setDl("error", dlProgress, msg);
            }
        }
    }

    /** 列出 root 下頂層目錄（canonical path 集，下載前快照用）。 */
    private static java.util.Set<String> listDirNames(File root) {
        java.util.Set<String> s = new java.util.HashSet<>();
        File[] fs;
        try {
            fs = root.listFiles();
        } catch (Throwable t) {
            return s;
        }
        if (fs == null) return s;
        for (File f : fs) {
            try {
                if (f.isDirectory()) s.add(f.getCanonicalPath());
            } catch (Throwable ignore) {
            }
        }
        return s;
    }

    /** 刪走不在快照內的頂層目錄（今次 unzip 落的；用家舊檔一律不碰）。 */
    private static void cleanupNewDirs(File root, java.util.Set<String> before) {
        File[] fs;
        try {
            fs = root.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            try {
                if (f.isDirectory() && !before.contains(f.getCanonicalPath())) {
                    deleteRecursive(f);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Throwable t) {
            fs = null;
        }
        if (fs != null) {
            for (File c : fs) {
                try {
                    if (c.isDirectory()) deleteRecursive(c);
                    else c.delete();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    /** zip-slip safe：entry 必須解到 root 之內，否則跳過。 */
    private void unzipToRoot(File zip, File root) throws java.io.IOException {
        String rootCanon = root.getCanonicalPath();
        java.util.zip.ZipInputStream zis = null;
        try {
            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zis.getNextEntry()) != null) {
                synchronized (this) {
                    if (dlCancel) throw new java.io.IOException("cancelled");
                }
                String name = e.getName();
                // 擋絕對路徑／.. 跳出（官方包不會有，但不信外來 zip）。
                File f = new File(root, name);
                String canon = f.getCanonicalPath();
                if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                    try {
                        zis.closeEntry();
                    } catch (Throwable ignore) {
                    }
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    java.io.OutputStream o = null;
                    try {
                        o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            synchronized (this) {
                                if (dlCancel) throw new java.io.IOException("cancelled");
                            }
                            o.write(buf, 0, n);
                        }
                        o.flush();
                    } finally {
                        try {
                            if (o != null) o.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
                try {
                    zis.closeEntry();
                } catch (Throwable ignore) {
                }
            }
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Throwable ignore) {
            }
        }
    }
}



