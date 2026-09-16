package com.open.alpha2;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * 2026-09 新增：Vosk 離線 ASR（語音 tab）。機身已無 iFlytek/Nuance，用 Vosk
 * （alphacephei/vosk-android AAR，armeabi-v7a native，API 21+）頂上。
 *
 * <p>Model 唔跟 app（~65MB 粒）：放 sdcard，呢度掃描自動偵測（user 要求）。
 * 掃描根：{@link Environment#getExternalStorageDirectory()}＋{@code /mnt/internal_sd}
 * 頂層目錄，有 {@code am/final.mdl} 就算一個可用 model（id＝目錄名）。
 *
 * <p>流程：{@link #scanModels()} 列出 → {@link #loadModel(String)}（背景 thread，
 * 幾秒＋~200MB RAM，一次只駐留一個）→ {@link #startListening()}（SpeechService
 * 自己開 AudioRecord，16kHz）→ partial 經 {@code asr_partial} event 即時顯示，
 * final 經沿用嘅 {@code asr_result} event（前端同打字模擬同一條：user 氣泡＋
 * 語意配對＋Android TTS）。
 *
 * <p>限定文法：load 嗰陣用問法庫起 grammar（中文問法轉簡體先對得上普通話
 * acoustic 輸出，見 SimplifiedToTraditional.toSimplified），命中率遠高過開放
 * 式。起唔到就跌返無約束 Recognizer（唔死）。
 *
 * <p>所有 public 方法 thread-safe；heavy 工（load）自己開 thread，callback
 * 經 listener 返（SpeechService 嗰啲喺 main thread，要輕手）。
 */
public final class VoskController {
    private static final String TAG = "VoskController";
    public static final float SAMPLE_RATE = 16000.0f;
    private static final String PREFS_NAME = "robotpanel";
    private static final String PREF_MODEL_ID = "vosk_model_id";
    private static final String PREF_EP_MODE = "vosk_ep_mode";
    private static final String PREF_EP_T_START = "vosk_ep_t_start";
    private static final String PREF_EP_T_END = "vosk_ep_t_end";
    private static final String PREF_EP_T_MAX = "vosk_ep_t_max";

    /** 掃描到嘅一個可用 model（langHint＝中文名，langEn＝英文名，前端跟 UI 語言揀顯示）。 */
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

    private volatile State state = State.IDLE;
    private volatile String modelId;
    private volatile String lastError;

    // -- 模型下載 (2026-09 新增：實驗 tab 下載卡，後端直落 zip＋unzip) --
    // 官方 small 模型 catalog（2026-09 對照 alphacephei.com/vosk/models 實頁執：
    // 英文 small 係 0.15 版，之前寫死嘅 en-us-0.22 根本唔存在，已改啱）。
    // {modelId, url, sizeMb}：sizeMb 係官頁標示（約數，等用戶判斷流量）。
    // 大粒（1G 級）唔收：部機 RAM 頂唔順。固定 allowlist，唔收任意 URL（防 SSRF）。
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
    // 下載狀態機（同上面 load/listen State 獨立：下載中主 State 照舊 IDLE，唔干擾）。
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
    private String grammarJson; // 建好嗰陣記低，start 先 new Recognizer
    private boolean stripSpaces; // 中文 char-grammar 吐字之間有空格，發佈前要搣走
    // endpointer 調校（-1/NaN＝跟 library 預設，persist 跨重啟）。語義見 vosk_api.h：
    // mode 0=預設 1=短 2=長 3=很長；delays 要三個一齊 set 先有效。
    private int epMode = -1;
    private float epTStart = Float.NaN;
    private float epTEnd = Float.NaN;
    private float epTMax = Float.NaN;

    public VoskController(Context context, SemanticMatcherZh zh,
            SemanticMatcherEn en) {
        this.appContext = context.getApplicationContext();
        this.matcherZh = zh;
        this.matcherEn = en;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable ignore) {
        }
        // 上次揀過嘅 model（如果仲喺度）背景自動載入，開頁即 ready。
        final String saved = prefs().getString(PREF_MODEL_ID, null);
        if (saved != null && findModelDir(saved) != null) {
            loadModel(saved);
        }
        // endpointer 上次調校一併讀返。
        try {
            epMode = prefs().getInt(PREF_EP_MODE, -1);
            epTStart = prefs().getFloat(PREF_EP_T_START, Float.NaN);
            epTEnd = prefs().getFloat(PREF_EP_T_END, Float.NaN);
            epTMax = prefs().getFloat(PREF_EP_T_MAX, Float.NaN);
        } catch (Throwable ignore) {
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -- 掃描 ---------------------------------------------------------------

    /** 掃 sdcard 頂層，搵有 am/final.mdl 嘅目錄。快，邊條 thread call 都得。
     *  純檔案 IO，唔掂 org.vosk，API 19 都用得（static 係刻意嘅，等
     *  vosk/models endpoint 喺 controller 未起（19 機）都列到表）。 */
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
                File mdl = new File(new File(d, "am"), "final.mdl");
                if (!mdl.isFile()) continue;
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

    /** 由 model id parse 語言段 (vosk-model[-small]-xx-...，xx 係語言碼)，
     *  查全表。之前用 contains("cn"/"en") 撞，日文 (ja) 顯示未知就係咁嚟，
     *  其他 small model (ru/fr/de/...) 全部一樣。 */
    private static String guessLang(String id) {
        String lang = null;
        String sub = null;
        String[] parts = id.toLowerCase(java.util.Locale.US).split("-");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("model") || parts[i].equals("small")) {
                // 跳過 "model"/"small" 呢啲固定前綴 (如 vosk-model-small-cn-0.22，
                // 語言段係 "small" 後面嗰格，唔係 "model" 後面)。
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
        if (lang == null) return "未知";
        if (lang.equals("en") && "in".equals(sub)) return "印度英文";
        if (lang.equals("ar") && "tn".equals(sub)) return "突尼西亞阿拉伯文";
        String name = LANG_NAMES.get(lang);
        return name != null ? name : "未知 (" + lang + ")";
    }

    private static final java.util.Map<String, String> LANG_NAMES = buildLangNames();

    private static java.util.Map<String, String> buildLangNames() {
        // Vosk 官方 models 頁 35 個 small 全覆蓋 (code → 中文名)。
        String[][] pairs = {
            {"en", "英文"}, {"cn", "中文"}, {"zh", "中文"},
            {"ja", "日文"}, {"ru", "俄文"}, {"fr", "法文"}, {"de", "德文"},
            {"es", "西班牙文"}, {"pt", "葡萄牙文"}, {"tr", "土耳其文"},
            {"vn", "越南文"}, {"vi", "越南文"}, {"it", "意大利文"},
            {"nl", "荷蘭文"}, {"ca", "加泰隆尼亞文"}, {"ar", "阿拉伯文"},
            {"fa", "波斯文"}, {"tl", "菲律賓文"}, {"uk", "烏克蘭文"},
            {"kz", "哈薩克文"}, {"sv", "瑞典文"}, {"eo", "世界語"},
            {"hi", "印地文"}, {"cs", "捷克文"}, {"pl", "波蘭文"},
            {"uz", "烏茲別克文"}, {"ko", "韓文"}, {"br", "布列塔尼文"},
            {"gu", "古吉拉特文"}, {"tg", "塔吉克文"}, {"te", "泰盧固文"},
            {"ky", "吉爾吉斯文"}, {"ka", "格魯吉亞文"},
        };
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String[] p : pairs) m.put(p[0], p[1]);
        return m;
    }

    /** guessLang 嘅英文版（同一個 parse，前端 uiLang＝en 嗰陣顯示；catalog/models
     *  帶 langEn，舊客淨讀 lang 唔受影響）。 */
    private static String guessLangEn(String id) {
        String lang = null;
        String sub = null;
        String[] parts = id.toLowerCase(java.util.Locale.US).split("-");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("model") || parts[i].equals("small")) {
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
        if (lang == null) return "unknown";
        if (lang.equals("en") && "in".equals(sub)) return "Indian English";
        if (lang.equals("ar") && "tn".equals(sub)) return "Tunisian Arabic";
        String name = LANG_NAMES_EN.get(lang);
        return name != null ? name : "unknown (" + lang + ")";
    }

    private static final java.util.Map<String, String> LANG_NAMES_EN = buildLangNamesEn();

    private static java.util.Map<String, String> buildLangNamesEn() {
        // 同 LANG_NAMES 一一對應（code → 英文名）。
        String[][] pairs = {
            {"en", "English"}, {"cn", "Chinese"}, {"zh", "Chinese"},
            {"ja", "Japanese"}, {"ru", "Russian"}, {"fr", "French"}, {"de", "German"},
            {"es", "Spanish"}, {"pt", "Portuguese"}, {"tr", "Turkish"},
            {"vn", "Vietnamese"}, {"vi", "Vietnamese"}, {"it", "Italian"},
            {"nl", "Dutch"}, {"ca", "Catalan"}, {"ar", "Arabic"},
            {"fa", "Persian"}, {"tl", "Filipino"}, {"uk", "Ukrainian"},
            {"kz", "Kazakh"}, {"sv", "Swedish"}, {"eo", "Esperanto"},
            {"hi", "Hindi"}, {"cs", "Czech"}, {"pl", "Polish"},
            {"uz", "Uzbek"}, {"ko", "Korean"}, {"br", "Breton"},
            {"gu", "Gujarati"}, {"tg", "Tajik"}, {"te", "Telugu"},
            {"ky", "Kyrgyz"}, {"ka", "Georgian"},
        };
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String[] p : pairs) m.put(p[0], p[1]);
        return m;
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

    /** 背景載入 model（幾秒）。return null＝已開始載入，否則係即時錯誤。
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
                        // 載入期間被 unload／load 過就掉咗呢個
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
                    prefs().edit().putString(PREF_MODEL_ID, id).apply();
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

    /** 起限定文法（問法庫）；唔得就回 null 用開放式。絕不 throw。
     *  中文要逐字空格（"你好嗎"→"你 好 嗎"）：small-cn 嘅 words.txt 係字級，
     *  成句當一個 entry 會全部 OOV 被 ignore（logcat 實證）。輸出嗰陣認返
     *  stripSpaces 搣走空格。英文 keep 原樣（本身係 word 級）。 */
    private String buildGrammar(String id) {
        try {
            List<String> phrases;
            String lower = id.toLowerCase(java.util.Locale.US);
            boolean cn = lower.contains("cn") || lower.contains("zh")
                    || lower.contains("mandarin");
            if (cn) {
                phrases = new ArrayList<>();
                for (String q : matcherZh.questions()) {
                    String s = SimplifiedToTraditional.toSimplified(q);
                    if (s != null && !s.isEmpty()) phrases.add(spaced(s));
                }
                stripSpaces = true;
            } else if (lower.contains("en")) {
                phrases = matcherEn.questions();
                stripSpaces = false;
            } else {
                stripSpaces = false;
                return null;
            }
            if (phrases.isEmpty()) {
                stripSpaces = false;
                return null;
            }
            JSONArray arr = new JSONArray();
            arr.put("[unk]");
            for (String p : phrases) arr.put(p);
            return arr.toString();
        } catch (Throwable e) {
            Log.w(TAG, "buildGrammar failed, open vocab", e);
            stripSpaces = false;
            return null;
        }
    }

    /** "你好嗎？" → "你 好 嗎 ？"（Vosk 中文 char 級文法用，空白先係分隔符）。
     *  標點/拉丁/數字原樣保留：small-cn words.txt 無嘅字，Vosk 起文法嗰陣會
     *  逐個 ignore（logcat "Ignoring word"），唔影響其他字嘅約束，唔使自己
     *  預先過濾（亂濾反而可能濾走 acoustic 認得嘅字，例如數字）。 */
    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }

    /** 掉咗個 model（唔清 prefs 記住嘅選擇）。 */
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
     * 開始聆聽。return null＝已開始，否則係錯誤字串（model 未載入／已在聽／mic 開唔到）。
     * 結果經 asr_partial（即時）／asr_result（成句，沿用舊管線：氣泡＋語意配對＋TTS）。
     */
    public synchronized String startListening() {
        if (state == State.LISTENING) return null;
        if (state != State.READY || model == null) {
            return state == State.LOADING ? "model loading, try later" : "load a model first";
        }
        // mic 預檢：部機唔支援 16kHz capture 就唔好開（開咗都係垃圾/[unk]）。
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
        try {
            if (grammarJson != null) {
                Recognizer g;
                try {
                    g = new Recognizer(model, SAMPLE_RATE, grammarJson);
                } catch (Throwable e) {
                    Log.w(TAG, "grammar recognizer failed, fallback open vocab", e);
                    g = new Recognizer(model, SAMPLE_RATE);
                }
                rec = g;
            } else {
                rec = new Recognizer(model, SAMPLE_RATE);
            }
            applyEndpointer(rec);
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
                    String text = transcript(hypothesis, "text");
                    if (text != null && !text.isEmpty()) {
                        // 斷症用：log 低認到咩（EventBus 只帶去前端，logcat 睇唔到內容）。
                        Log.i(TAG, "final: " + text);
                        EventBus.get().publish("asr_result",
                                "{\"text\":\"" + escape(text) + "\"}");
                    }
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    String text = transcript(hypothesis, "text");
                    if (text != null && !text.isEmpty()) {
                        EventBus.get().publish("asr_result",
                                "{\"text\":\"" + escape(text) + "\"}");
                    }
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

    /** endpointer 調校＋persist。mode -1＝跟預設；delays 要三個一齊俾先有效
     *  （跟 vosk_api.h），唔齊就三個都當預設。return null＝ok。 */
    public synchronized String setEndpointer(int mode, float tStart, float tEnd, float tMax) {
        if (mode < -1 || mode > 3) return "mode must be -1..3";
        float[] vs = new float[]{tStart, tEnd, tMax};
        for (int i = 0; i < vs.length; i++) {
            if (!Float.isNaN(vs[i]) && !(vs[i] > 0 && vs[i] < 600)) {
                return "delays must be 0..600 or unset";
            }
        }
        boolean delaysOk = !Float.isNaN(tStart) && !Float.isNaN(tEnd) && !Float.isNaN(tMax);
        epMode = mode;
        if (delaysOk) {
            epTStart = tStart;
            epTEnd = tEnd;
            epTMax = tMax;
        } else {
            epTStart = Float.NaN;
            epTEnd = Float.NaN;
            epTMax = Float.NaN;
        }
        try {
            prefs().edit().putInt(PREF_EP_MODE, epMode)
                    .putFloat(PREF_EP_T_START, epTStart)
                    .putFloat(PREF_EP_T_END, epTEnd)
                    .putFloat(PREF_EP_T_MAX, epTMax).apply();
        } catch (Throwable ignore) {
        }
        if (recognizer != null) applyEndpointer(recognizer);
        Log.i(TAG, "endpointer set mode=" + epMode
                + (delaysOk ? (" t=" + epTStart + "/" + epTEnd + "/" + epTMax) : " delays=default"));
        return null;
    }

    public synchronized int getEpMode() {
        return epMode;
    }

    public synchronized float getEpTEnd() {
        return epTEnd;
    }

    private void applyEndpointer(Recognizer rec) {
        if (rec == null) return;
        try {
            if (epMode >= 0) rec.setEndpointerMode(epMode);
        } catch (Throwable e) {
            Log.w(TAG, "setEndpointerMode failed", e);
        }
        try {
            if (!Float.isNaN(epTStart) && !Float.isNaN(epTEnd) && !Float.isNaN(epTMax)) {
                rec.setEndpointerDelays(epTStart, epTEnd, epTMax);
            }
        } catch (Throwable e) {
            Log.w(TAG, "setEndpointerDelays failed", e);
        }
    }

    /** TTS 播音嗰陣 pause 返（唔放 mic，淨係唔 decode），播完 resume。
     *  唔在聽就 no-op。 */
    public synchronized void setPaused(boolean paused) {
        if (speechService != null && state == State.LISTENING) {
            try {
                speechService.setPause(paused);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 咪測試：開 1 秒錄音計 RMS/Peak (dBFS)，幫用戶判斷係唔係收得細。
     *  同 SpeechService 同一個源 (VOICE_RECOGNITION) 同格式。聽緊嗰陣唔做
     *  （單 input HAL 容唔落第二個 recorder）——先㩒停止。
     *  回完整 JSON（成功 {"ok":true,...}，失敗 {"ok":false,"error":...}）。 */
    public String micTestJson() {
        synchronized (this) {
            if (state == State.LISTENING) {
                return "{\"ok\":false,\"error\":\"stop listening first (mic busy)\"}";
            }
        }
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                return "{\"ok\":false,\"error\":\"mic does not support 16kHz mono PCM\"}";
            }
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 32000));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                return "{\"ok\":false,\"error\":\"AudioRecord not initialized\"}";
            }
            rec.startRecording();
            short[] buf = new short[16000];
            int got = 0;
            long deadline = SystemClock.elapsedRealtime() + 2500;
            while (got < buf.length && SystemClock.elapsedRealtime() < deadline) {
                int n = rec.read(buf, got, buf.length - got);
                if (n < 0) break;
                if (n > 0) got += n;
            }
            try {
                rec.stop();
            } catch (Throwable ignore) {
            }
            if (got < 1600) {
                return "{\"ok\":false,\"error\":\"too few samples: " + got + "\"}";
            }
            double sum = 0;
            double peak = 0;
            for (int i = 0; i < got; i++) {
                double v = buf[i] / 32768.0;
                sum += v * v;
                double a = Math.abs(v);
                if (a > peak) peak = a;
            }
            double rmsDb = 20 * Math.log10(Math.sqrt(sum / got) + 1e-9);
            double peakDb = 20 * Math.log10(peak + 1e-9);
            return "{\"ok\":true"
                    + ",\"rmsDb\":" + String.format(java.util.Locale.US, "%.1f", rmsDb)
                    + ",\"peakDb\":" + String.format(java.util.Locale.US, "%.1f", peakDb)
                    + ",\"samples\":" + got + "}";
        } catch (Throwable e) {
            Log.w(TAG, "micTest failed", e);
            return "{\"ok\":false,\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}";
        } finally {
            if (rec != null) {
                try {
                    rec.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    public synchronized String stopListening() {
        stopLocked();
        if (state == State.LISTENING || model != null) {
            state = model != null ? State.READY : State.IDLE;
        }
        // server 側停 (如讓咪俾小智) 都要推 event，前端先會熄燈。
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

    /** optText＋中文 char 文法空格清理（stripSpaces 開嗰陣）＋[unk] 過濾。
     *  [unk]（大小寫唔拘）代表 Vosk 認唔到——唔發佈，唔係會觸發語意配對亂答
     *  （fallback 隨機答案＋可能隨機動作）。partial 同 final 共用。 */
    private String transcript(String json, String key) {
        String s = optText(json, key);
        if (s != null && stripSpaces) {
            s = s.replace(" ", "").replace("\t", "").trim();
        }
        if (s == null || s.isEmpty() || "[unk]".equalsIgnoreCase(s)) return null;
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
    // 前端流程：實驗 tab 下載卡 → vosk/catalog 列出全部可下載（已下載唔 show）→
    // vosk/download?model=<id> 起背景 thread → 前端 poll vosk/download_status
    // （或聽 vosk_download event）顯示進度 → done 後自動 refresh＋load
    // （呢度做埋 auto-load，省一 round trip）。

    /** modelId→{modelId,url}。唔喺 catalog 就回 null（由上層轉做 400）。 */
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

    /** 實驗 tab 下載卡用：全部可下載＋已下載旗（前端已下載唔顯示）。
     *  純檔案 IO（downloaded 判定），邊條 thread call 都得。 */
    public static String catalogJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"catalog\":[");
        boolean first = true;
        for (String[] e : DL_CATALOG) {
            if (!first) sb.append(',');
            first = false;
            String id = e[0];
            sb.append("{\"id\":\"").append(escape(id)).append('"');
            sb.append(",\"lang\":\"").append(escape(guessLang(id))).append('"');
            sb.append(",\"langEn\":\"").append(escape(guessLangEn(id))).append('"');
            sb.append(",\"sizeMb\":").append(e[1]);
            sb.append(",\"downloaded\":").append(findModelDir(id) != null);
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
                // exists 但唔肯定寫得入都照試（舊機 canWrite 誤報），寫唔入後面會再錯。
                return ext;
            } catch (Throwable ignore) {
            }
        }
        return new File("/mnt/internal_sd");
    }

    /** 開始下載＋unzip。modelId 必須喺 catalog（見 downloadTarget）。
     *  return null＝已開始，否則即時錯誤字串。 */
    public synchronized String startDownload(String modelId) {
        String[] target = downloadTarget(modelId);
        if (target == null) return "unknown downloadable model: " + modelId;
        if ("downloading".equals(dlState) || "unzipping".equals(dlState)) {
            return "already downloading (" + dlModel + " " + dlProgress + "%)";
        }
        final String url = target[1];
        // 已有就唔好重落（scan 同 loadModel 共用判定：有 am/final.mdl 即算）。
        if (findModelDir(modelId) != null) return "already exists: " + modelId;
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

    /** download_status endpoint 用（透傳 JSON，唔經 ApiResponse 包多層）。 */
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
        try {
            try {
                root.mkdirs();
            } catch (Throwable ignore) {
            }
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
                // getContentLengthLong 要 API 24+，呢個 APK 要行 API 21/22（見 manifest），
                // 直接 call 會 NoSuchMethodError 炒（同 vosk 熔斷保 API 19 同一類）。
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
            // --- unzip（自己解，唔依賴系統 unzip binary）---
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
            // 驗收：要有 am/final.mdl 先算數（zip 損壞／路徑唔啱即錯）。
            if (findModelDir(modelId) == null) {
                throw new java.io.IOException("unzip ok but model not found: " + modelId
                        + "/am/final.mdl missing");
            }
            setDl("done", 100, null);
            Log.i(TAG, "model downloaded+unzipped: " + modelId);
            // 顺手自動載入（省前端一 round trip；失敗唔當下載失敗，狀態照 done）。
            try {
                String err = loadModel(modelId);
                if (err != null) Log.w(TAG, "auto-load after download failed: " + err);
            } catch (Throwable e) {
                Log.w(TAG, "auto-load after download threw", e);
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
            // zip 損壞先清，唔郁已存在嘅舊 model 目錄。
            if (cancelled || "cancelled".equalsIgnoreCase(msg)) {
                setDl("cancelled", dlProgress, "cancelled");
            } else {
                setDl("error", dlProgress, msg);
            }
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
                // 擋絕對路徑／.. 跳出（官方包唔會有，但唔信外來 zip）。
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
