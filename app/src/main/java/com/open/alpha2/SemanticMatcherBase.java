package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 中英文語意配對引擎的共用底層 - SemanticMatcherZh (中文) 和
 * SemanticMatcherEn (英文) 除咗 TAG、assets 檔名、fallback 問法/動作組
 * 之外，載入/比對/分類 random 呢幾層邏輯完全一致，2026-09 抽呢層共用 base
 * class，避免兩份逐字重複要同步改。子類只需要喺 constructor 提供三樣嘢：
 * log tag、問法 json 的 assets 路徑、"聽不懂" fallback 組 (問法句 + 對應
 * action id, 長度要相等)。
 *
 * MatchResult/Entry 都搬呢度做共用型別 - 之前中英文各自的 nested MatchResult
 * 結構一樣但屬於不同 class, SemanticCenter 要用 toZhResult() 手動轉接; 現在
 * 兩個子類共用同一個 SemanticMatcherBase.MatchResult, 呼叫方不用再轉。
 *
 * 資料來源/分類 random 機制等背景見 SemanticMatcherZh 的 class javadoc,
 * 不在這裡重複。
 *
 * 命名備註: 呢三個 class (呢個 base + SemanticMatcherZh/En) 2026-09 之前叫
 * IflytekSemanticMatcher(Base/En) - 個名純粹歷史原因 (問法資料最初由 iFlytek
 * APK 反編譯還原), 同機身已經永久唔再用嘅 Nuance/iFlytek binder TTS/ASR 引擎
 * 完全冇關係, 淨係個名容易誤導 (呢個功能本身係純本地 JSON 配對, 唔經任何外部
 * 引擎)。2026-09 改名做 SemanticMatcher* 消除呢個誤導。
 */
public abstract class SemanticMatcherBase {
    private static final String ASSET_PATH_CATEGORIES = "iflytek/action_category_pools.json";
    private static final String RANDOM_CATEGORY_PREFIX = "__RANDOM_CATEGORY__";

    /** 中英文共用嘅「聽不懂」fallback 動作組（5 個已驗證沒聲效動作；兩子類之前各自複製同一份）。 */
    protected static final String[] DEFAULT_FALLBACK_ACTION_IDS = {
            "1464835936013", // 搖頭 / Shake head
            "1464835936026", // 思考 / Thinking
            "1464835936043", // 眨眼 / Wink
            "1509000313549", // 賣萌 / Cute
            "1464835936087", // 點頭 / Nod
    };

    /** 配對結果。type 和 MainActivity 已有的 asr_result event 格式對齊,
     *  answer/actionId 可能是 null (例如 CHAT 類沒 actionId, 部分 FUNCTION 沒 answer)。 */
    public static final class MatchResult {
        public final String question;   // 命中的原始問法 (debug 用)
        public final String type;       // "ACTION" | "FUNCTION" | "CHAT"
        public final String operation;  // 悠聊 operation 代號, CHAT 類是 null
        public final String slot;       // 方向/情緒 slot, 沒有就是 null
        public final String answer;     // 隨機選一句的回覆句, 沒答案句就是 null
        public final String actionId;   // 202動作清單裡面的 action id, 沒動作就是 null

        public MatchResult(String question, String type, String operation, String slot,
                    String answer, String actionId) {
            this.question = question;
            this.type = type;
            this.operation = operation;
            this.slot = slot;
            this.answer = answer;
            this.actionId = actionId;
        }
    }

    /** 內部記錄, 對應問法 json 裡面每一行。 */
    private static final class Entry {
        String q;
        String[] answers;
        String type;
        String op;
        String slot;
        String actionId;
    }

    private final String tag;
    private final String assetPath;
    private final String[] fallbackQuestions;
    private final String[] fallbackActionIds;

    protected final Context appContext;
    protected final Random random = new Random();

    /** Lazily loaded on first match() call, cached afterwards - 全部載入在記憶體
     *  都只是幾十 KB parsed 狀態, 完全負擔得起, 沒必要每次 match 都重新讀
     *  assets。和 loadXiaozhiActions() 的 cache 風格一致。 */
    private List<Entry> cache;

    /** Lazily loaded cache of action_category_pools.json: category key (例如
     *  "DANCE_KIDS") -> 該分類全部 action id。和 cache 一致的 lazy-load 風格,
     *  只有 __RANDOM_CATEGORY__ 命中才會讀, 不會拖慢正常 match() 流程。 */
    private java.util.Map<String, List<String>> categoryPoolsCache;

    /** @param tag 子類專屬的 Log tag
     *  @param assetPath 問法 json 的 assets 路徑 (例如 "iflytek/iflytek_semantic_zh.json")
     *  @param fallbackQuestions "聽不懂" 的候選回應句
     *  @param fallbackActionIds 對應 fallbackQuestions 每一句的動作 id (長度要相等,
     *         下標一一對應) */
    protected SemanticMatcherBase(Context context, String tag, String assetPath,
            String[] fallbackQuestions, String[] fallbackActionIds) {
        this.appContext = context.getApplicationContext();
        this.tag = tag;
        this.assetPath = assetPath;
        this.fallbackQuestions = fallbackQuestions;
        this.fallbackActionIds = fallbackActionIds;
    }

    /** 由 assets 讀入 + parse 問法記錄。讀取/parse 失敗就回傳空 list (不會拋出),
     *  和 loadXiaozhiActions() 一致的「不崩潰、log 一次」哲學。 */
    private synchronized List<Entry> load() {
        if (cache != null) return cache;
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(readAssetString(assetPath));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.q = o.optString("q");
                e.type = o.optString("type");
                e.op = o.has("op") ? o.optString("op") : null;
                e.slot = o.has("slot") ? o.optString("slot") : null;
                e.actionId = o.has("actionId") ? o.optString("actionId") : null;
                JSONArray aArr = o.optJSONArray("a");
                if (aArr != null && aArr.length() > 0) {
                    e.answers = new String[aArr.length()];
                    for (int j = 0; j < aArr.length(); j++) {
                        e.answers[j] = aArr.getString(j);
                    }
                } else {
                    e.answers = null;
                }
                result.add(e);
            }
        } catch (Exception e) {
            Log.w(tag, "load: failed to load assets/" + assetPath + ": " + e);
        }
        cache = result;
        return result;
    }

    /** 將一句 ASR 辨識出來的文字, 對照問法庫, 找出最貼近的一條。「由緊至鬆」
     *  三層做法, 有哪層命中就立刻用那層:
     *   1. 完全相等 (去頭尾空白)
     *   2. 命中問法完全包含在輸入裡面 (輸入夾雜其他字, 例如「阿爾法你好嗎」包含著
     *      問法「你好嗎」) - 選當中最長那條問法, 減少短問法誤中夾在長句裡面的情況
     *   3. 輸入完全包含在命中問法裡面 (ASR 漏了尾, 例如輸入「你好」、問法是
     *      「你好嗎」) - 都是選最長那條問法
     *  三層都找不到就不再回傳 null - 隨機選一句 fallbackQuestions 做「聽不懂」
     *  的回應, 保證用戶說的話在問法庫裡面找不到都還有反應, 不會啞口。空白輸入
     *  (text 為 null 或者只有空白字元) 就真的沒東西好答, 依然回傳 null。
     *
     *  輸入文字先經 SimplifiedToTraditional.toTraditional() normalize 做繁體再
     *  比對 - online iFlytek ASR 引擎輸出的是簡體中文, 但兩份 database 全部是
     *  書面繁體中文, 不 normalize 的話簡體輸入會完全 match 不中任何問法。這層
     *  轉換只影響「用來比對」的 q, 不改動 MatchResult.question (依然是 e.q 的
     *  原文) - answer/actionId 一律來自 database 本身。 */
    public MatchResult match(String text) {
        List<Entry> entries = load();
        String q = text == null ? "" : SimplifiedToTraditional.toTraditional(text.trim());
        if (q.isEmpty()) return null;
        if (entries.isEmpty()) return fallback();

        // 1) 完全相等
        for (Entry e : entries) {
            if (q.equals(e.q)) return toResult(e);
        }

        // 2) 問法完全包含在輸入裡面 (選最長那條, 減少短問法誤中)
        Entry best = null;
        for (Entry e : entries) {
            if (!e.q.isEmpty() && q.contains(e.q)) {
                if (best == null || e.q.length() > best.q.length()) best = e;
            }
        }
        if (best != null) return toResult(best);

        // 3) 輸入完全包含在問法裡面 (ASR 漏字/縮短, 選最長那條問法)
        for (Entry e : entries) {
            if (!e.q.isEmpty() && e.q.contains(q)) {
                if (best == null || e.q.length() > best.q.length()) best = e;
            }
        }
        if (best != null) return toResult(best);

        return fallback();
    }

    /** 隨機選一句 fallbackQuestions/fallbackActionIds, 包裝做 MatchResult。
     *  type 用 "CHAT" (純粹回應, 不屬於任何 operation), operation/slot 是 null。 */
    private MatchResult fallback() {
        int i = random.nextInt(fallbackQuestions.length);
        return new MatchResult(fallbackQuestions[i], "CHAT", null, null,
                fallbackQuestions[i], fallbackActionIds[i]);
    }

    private MatchResult toResult(Entry e) {
        String answer = null;
        if (e.answers != null && e.answers.length > 0) {
            answer = e.answers[random.nextInt(e.answers.length)];
        }
        String actionId = e.actionId;
        if ("__RANDOM__".equals(actionId)) {
            // 這類 operation 在問法庫裡面沒對應固定動作, 跟著用戶決定: 隨機選一個
            // 202 動作清單裡面的動作。實際隨機邏輯留給呼叫方做 (MainActivity 已經
            // 有 loadXiaozhiActions(), 這個 class 不重複讀多一次 202 動作清單),
            // 這裡回傳 "__RANDOM__" 標記給呼叫方識別。
        }
        return new MatchResult(e.q, e.type, e.op, e.slot, answer, actionId);
    }

    /** debug/量度用: 已載入多少條記錄。 */
    public int size() {
        return load().size();
    }

    /** Vosk 限定文法用: 全部問法原文 (去重、去空)。 */
    public synchronized java.util.List<String> questions() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Entry e : load()) {
            if (e.q != null && !e.q.isEmpty()) set.add(e.q);
        }
        return new java.util.ArrayList<>(set);
    }

    /** 由 assets/iflytek/action_category_pools.json 讀入 17 個分類 -> action id
     *  pool 的對照表 (中英文共用同一份)。讀取/parse 失敗就回傳空 map (不會拋出),
     *  和 load() 一致的「不崩潰、log 一次」哲學。 */
    private synchronized java.util.Map<String, List<String>> loadCategoryPools() {
        if (categoryPoolsCache != null) return categoryPoolsCache;
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        try {
            JSONObject root = new JSONObject(readAssetString(ASSET_PATH_CATEGORIES));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray idsArr = root.getJSONArray(key);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < idsArr.length(); i++) {
                    ids.add(idsArr.getString(i));
                }
                result.put(key, ids);
            }
        } catch (Exception e) {
            Log.w(tag, "loadCategoryPools: failed to load assets/" + ASSET_PATH_CATEGORIES + ": " + e);
        }
        categoryPoolsCache = result;
        return result;
    }

    /** 兩份 load 共用嘅 assets 讀檔（經 IOUtil，唔再各自寫 4k loop）。 */
    private String readAssetString(String path) throws java.io.IOException {
        InputStream in = null;
        try {
            in = appContext.getAssets().open(path);
            return IOUtil.readFully(in);
        } finally {
            if (in != null) {
                try { in.close(); } catch (java.io.IOException ignored) {}
            }
        }
    }

    /** 若 actionId 是 "__RANDOM_CATEGORY__<key>" 格式, 解析出 <key> 並在對應分類
     *  的 action id pool 裡面隨機選一個, 回傳真正的 action id; 不是這個格式就原樣
     *  回傳。找不到對應分類、或者分類是空 pool, 回傳 null (呼叫方要自行 fallback,
     *  例如 resolveRandomActionId() 隨機動作池)。
     *
     *  呼叫方 (SemanticCenter.handleIflytekSemanticText()) 應該在拿到 MatchResult
     *  之後、真正 call robot.action_PlayActionName() 之前, 用這個方法將
     *  actionId 解析成真實可播放的 id - 和 "__RANDOM__" 標記屬於同一種「延遲到
     *  執行時才選」的設計, 但這是分類限定的隨機, 不是全部 202 個隨便選。 */
    public String resolveCategoryRandomActionId(String actionId) {
        if (actionId == null || !actionId.startsWith(RANDOM_CATEGORY_PREFIX)) {
            return actionId;
        }
        String key = actionId.substring(RANDOM_CATEGORY_PREFIX.length());
        List<String> pool = loadCategoryPools().get(key);
        if (pool == null || pool.isEmpty()) {
            Log.w(tag, "resolveCategoryRandomActionId: empty/missing pool for category " + key);
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
