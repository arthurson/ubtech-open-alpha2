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
 * 中英西法日德意葡韓俄語意配對引擎的共用底層 - SemanticMatcherZh (中文)、
 * SemanticMatcherEn (英文)、SemanticMatcherEs (西文)、SemanticMatcherFr (法文)、
 * SemanticMatcherJa (日文)、SemanticMatcherDe (德文)、SemanticMatcherIt (意大利文)、
 * SemanticMatcherPt (葡萄牙文)、SemanticMatcherKo (韓文)、SemanticMatcherRu (俄文) 除了 TAG、assets 檔名、fallback 問法/動作組
 * 之外，載入/比對/分類 random 這幾層邏輯完全一致，2026-09 抽這一層共用 base
 * class，避免兩份逐字重複要同步改。子類只需要在 constructor 提供三樣東西：
 * log tag、問法 json 的 assets 路徑、"聽不懂" fallback 組 (問法句 + 對應
 * action id, 長度要相等)。
 *
 * MatchResult/Entry 都搬這裡做共用型別 - 之前中英文各自的 nested MatchResult
 * 結構一樣但屬於不同 class, SemanticCenter 要用 toZhResult() 手動轉接; 現在
 * 兩個子類共用同一個 SemanticMatcherBase.MatchResult, 呼叫方不用再轉。
 *
 * 資料來源/分類 random 機制等背景見 SemanticMatcherZh 的 class javadoc,
 * 不在這裡重複。
 *
 *  命名備註: 這幾個 class (這個 base + SemanticMatcherZh/En/Es/Fr/Ja/De/It/Pt/Ko/Ru) 2026-09 之前叫
 * IflytekSemanticMatcher(Base/En) - 個名純粹歷史原因 (問法資料最初由 iFlytek
 * APK 反編譯還原), 同機身已經永久不再用的 Nuance/iFlytek binder TTS/ASR 引擎
 * 完全沒有關係, 僅個名容易誤導 (這個功能本身是純本地 JSON 配對, 不經任何外部
 * 引擎)。2026-09 改名為 SemanticMatcher* 消除這個誤導。
 */
public abstract class SemanticMatcherBase {
    private static final String ASSET_PATH_CATEGORIES = "semantic/action_category_pools.json";
    private static final String RANDOM_CATEGORY_PREFIX = "__RANDOM_CATEGORY__";

    /** 十語共用的「聽不懂」fallback 動作組（5 個已驗證沒聲效動作；各子類之前各自複製同一份）。 */
    protected static final String[] DEFAULT_FALLBACK_ACTION_IDS = {
            "1464835936013", // 搖頭 / Shake head
            "1464835936026", // 思考 / Thinking
            "1464835936043", // 眨眼 / Wink
            "1509000313549", // 賣萌 / Cute
            "1464835936087", // 點頭 / Nod
    };

    /** 配對結果。type 和 MainActivity 已有的 asr_result event 格式對齊,
     *  answer/actionId 可能是 null (例如 CHAT 類沒 actionId, 部分 FUNCTION 沒 answer)。
     *  matched＝真命中問法庫／false＝fallback 亂答（SemanticCenter 跨語言兜底用：
     *  主 matcher 不中先試另一個，兩個都不中就用主那個 fallback）。
     *  matchLayer＝命中邊一層（0 精確／1 包含／2 反包含／3 模糊／4 fallback）——
     *  SemanticCenter 十語鏈用嚟排先後：強匹配（精確/包含/反包含）贏過別家嘅
     *  模糊（例如西文 "Aplaude" 精確中西文組，唔畀英文 "applaud" 模糊搶走）。 */
    public static final class MatchResult {
        public final String question;   // 命中的原始問法 (debug 用)
        public final String type;       // "ACTION" | "FUNCTION" | "CHAT"
        public final String operation;  // 悠聊 operation 代號, CHAT 類是 null
        public final String slot;       // 方向/情緒 slot, 沒有就是 null
        public final String answer;     // 隨機選一句的回覆句, 沒答案句就是 null
        public final String actionId;   // 202動作清單裡面的 action id, 沒動作就是 null
        public final boolean matched;   // 真命中／fallback
        public final int matchLayer;    // 0 精確／1 包含／2 反包含／3 模糊／4 fallback

        public MatchResult(String question, String type, String operation, String slot,
                    String answer, String actionId, boolean matched, int matchLayer) {
            this.question = question;
            this.type = type;
            this.operation = operation;
            this.slot = slot;
            this.answer = answer;
            this.actionId = actionId;
            this.matched = matched;
            this.matchLayer = matchLayer;
        }
    }

    /** 內部記錄, 對應問法 json 裡面每一行。normQ 是比對用正規化問法
     *  (見 normalizeForMatch, 載入時一次算好, match() 不用每次重算)。 */
    private static final class Entry {
        String q;
        String normQ;
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
     *  @param assetPath 問法 json 的 assets 路徑 (例如 "semantic/semantic_zh.json")
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
     *  和 loadXiaozhiActions() 一致的「不崩潰、log 一次」哲學。
     *
     *  支援兩種格式 (2026-09 v2 起):
     *   v1 legacy - 頂層 JSONArray, 每個 object 是一條問法:
     *     [{"q":"跳舞","a":[...],"type":"ACTION","op":"DANCE",...}, ...]
     *   v2 grouped - 頂層 JSONObject, intents 陣列, 每個 intent 帶多條問法
     *     (同 intent 共用同一組 a/type/op/slot/actionId, 免重複):
     *     {"version":2,"lang":"zh","intents":[
     *       {"id":"action.dance","type":"ACTION","op":"DANCE",
     *        "actionId":"...","a":[...],"qs":["跳舞","跳個舞",...]}, ...]}
     *  v2 的問法鍵接受 "qs" (array) / "q" (array 或 string) / "patterns" (array,
     *  將來多語言共用一檔時的別名) - 三者都沒有就跳過該 intent。v1 檔案不需改動,
     *  新語言一律用 v2。 */
    private synchronized List<Entry> load() {
        if (cache != null) return cache;
        List<Entry> result = new ArrayList<>();
        try {
            String raw = readAssetString(assetPath).trim();
            if (raw.startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Entry e = parseEntry(o, o.optString("q"));
                    if (e != null) result.add(e);
                }
            } else {
                JSONObject root = new JSONObject(raw);
                JSONArray intents = root.optJSONArray("intents");
                if (intents == null) intents = new JSONArray();
                for (int i = 0; i < intents.length(); i++) {
                    JSONObject o = intents.getJSONObject(i);
                    for (String q : parseQuestions(o)) {
                        Entry e = parseEntry(o, q);
                        if (e != null) result.add(e);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(tag, "load: failed to load assets/" + assetPath + ": " + e);
        }
        cache = result;
        return result;
    }

    /** v2 intent object 抽出全部問法: "qs" array 優先, 其次 "q" (array 或 string),
     *  再次 "patterns" array。全部沒有/全空就回傳空 list (呼叫方跳過)。 */
    private static List<String> parseQuestions(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray qs = o.optJSONArray("qs");
        if (qs == null) qs = o.optJSONArray("patterns");
        if (qs != null) {
            for (int i = 0; i < qs.length(); i++) {
                String q = qs.optString(i, null);
                if (q != null && !q.isEmpty()) out.add(q);
            }
            return out;
        }
        JSONArray qArr = o.optJSONArray("q");
        if (qArr != null) {
            for (int i = 0; i < qArr.length(); i++) {
                String q = qArr.optString(i, null);
                if (q != null && !q.isEmpty()) out.add(q);
            }
            return out;
        }
        String single = o.optString("q", null);
        if (single != null && !single.isEmpty()) out.add(single);
        return out;
    }

    /** 由一個問法 object + 一條問法字串建成 Entry。q 為空就回傳 null (跳過)。 */
    private static Entry parseEntry(JSONObject o, String q) {
        if (q == null || q.isEmpty()) return null;
        Entry e = new Entry();
        e.q = q;
        e.normQ = normalizeForMatch(q);
        e.type = o.optString("type");
        e.op = o.has("op") ? o.optString("op") : null;
        e.slot = o.has("slot") ? o.optString("slot") : null;
        e.actionId = o.has("actionId") ? o.optString("actionId") : null;
        JSONArray aArr = o.optJSONArray("a");
        if (aArr != null && aArr.length() > 0) {
            e.answers = new String[aArr.length()];
            for (int j = 0; j < aArr.length(); j++) {
                e.answers[j] = aArr.optString(j, null);
            }
        } else {
            e.answers = null;
        }
        return e;
    }

    /** 將一句 ASR 辨識出來的文字, 對照問法庫, 找出最貼近的一條。「由嚴到寬」
     *  四層做法, 有哪層命中就立刻用那層:
     *   1. 完全相等 (正規化後)
     *   2. 命中問法完全包含在輸入裡面 (輸入夾雜其他字, 例如「阿爾法你好嗎」包含著
     *      問法「你好嗎」) - 選當中最長那條問法, 減少短問法誤中夾在長句裡面的情況
     *   3. 輸入完全包含在命中問法裡面 (ASR 漏了尾, 例如輸入「你好」、問法是
     *      「你好嗎」) - 都是選最長那條問法
     *   4. 模糊配對 (2026-09 新增, 專捉 ASR 同音錯別字/增減一字, 例如「跳武吧」
     *      對「跳舞吧」) - OSA 編輯距離, 要求 d*2 < maxlen (嚴格過半相似),
     *      過短 (maxlen<3) 不模糊以免誤中 (例如「跳樓」不會中「跳舞」)。
     *      距離相同取檔案順序首條 (ACTION 意圖排先, 見 semantic_zh.json)。
     *  四層都找不到就不再回傳 null - 隨機選一句 fallbackQuestions 做「聽不懂」
     *  的回應, 保證用戶說的話在問法庫裡面找不到都還有反應, 不會啞口。空白輸入
     *  (text 為 null 或者只有空白字元) 就真的沒東西好答, 依然回傳 null。
     *
     *  輸入文字先經 SimplifiedToTraditional.toTraditional() normalize 做繁體,
     *  再經 normalizeForMatch() 去標點/空白/拉丁大小階 (ASR/Vosk/打字輸入的
     *  「TFBOYS!」「tfboys」「你好嗎?」不應因標點大小階而配對不中) 才比對 -
     *  兩層轉換只影響「用來比對」的 q, 不改動 MatchResult.question (依然是 e.q
     *  的原文) - answer/actionId 一律來自 database 本身。 */
    public MatchResult match(String text) {
        List<Entry> entries = load();
        String raw = text == null ? "" : SimplifiedToTraditional.toTraditional(text.trim());
        if (raw.isEmpty()) return null;
        if (entries.isEmpty()) return fallback();
        String q = normalizeForMatch(raw);
        if (q.isEmpty()) return fallback(); // 全是標點/空白 - 當聽不懂, 不靜音

        // 1) 完全相等
        for (Entry e : entries) {
            if (q.equals(e.normQ)) return toResult(e, 0);
        }

        // 2) 問法完全包含在輸入裡面 (選最長那條, 減少短問法誤中)
        Entry best = null;
        for (Entry e : entries) {
            if (!e.normQ.isEmpty() && q.contains(e.normQ)) {
                if (best == null || e.normQ.length() > best.normQ.length()) best = e;
            }
        }
        if (best != null) return toResult(best, 1);

        // 3) 輸入完全包含在問法裡面 (ASR 漏字/縮短, 選最長那條問法)
        for (Entry e : entries) {
            if (!e.normQ.isEmpty() && e.normQ.contains(q)) {
                if (best == null || e.normQ.length() > best.normQ.length()) best = e;
            }
        }
        if (best != null) return toResult(best, 2);

        // 4) 模糊配對: ASR 同音錯別字/增減一字/相鄰換位。距離最小者勝。
        best = null;
        int bestD = Integer.MAX_VALUE;
        for (Entry e : entries) {
            String nq = e.normQ;
            if (nq == null || nq.isEmpty()) continue;
            int ml = Math.max(q.length(), nq.length());
            if (ml < 3) continue;
            if (Math.abs(q.length() - nq.length()) > (ml - 1) / 2) continue;
            int d = osaDistance(q, nq);
            if (d * 2 < ml && d < bestD) {
                best = e;
                bestD = d;
            }
        }
        if (best != null) return toResult(best, 3);

        return fallback();
    }

    /** 比對用正規化: 去頭尾空白、拉丁轉小寫、拉丁重音拆掉 (NFD 去 Mn, 例如
     *  í→i、ñ→n - Vosk 西文輸出同打字成日唔帶重音, 兩邊一齊拆先對得上;
     *  中英文件本身冇重音字, 對佢哋零影響)、去掉所有標點/符號/空白
     *  (Unicode P/S 類 + 空白, 含全形？。！，、；：「」『』《》…—)。
     *  中英數字不受影響 (TFBOYS/OK/0.01公分照認)。null 回 ""。 */
    static String normalizeForMatch(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        // NFD 拆重音 (e + ́), Mn 類即掉 - 只影響拉丁字母, CJK 照過。
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}", "");
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            int type = Character.getType(c);
            if (type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.MATH_SYMBOL
                    || type == Character.CURRENCY_SYMBOL
                    || type == Character.MODIFIER_SYMBOL
                    || type == Character.OTHER_SYMBOL
                    || type == Character.SURROGATE
                    || type == Character.CONTROL
                    || type == Character.FORMAT) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Restricted OSA 編輯距離 (相鄰換位計 1, 其餘增刪改各計 1)。
     *  問法最長十餘字, 全 DP 完全負擔得起 (只在首三層不中才呼叫)。 */
    static int osaDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        int[] prev2 = null;
        int[] prev = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            int[] cur = new int[lb + 1];
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(prev[j - 1] + cost,
                        Math.min(cur[j - 1] + 1, prev[j] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
            }
            prev2 = prev;
            prev = cur;
        }
        return prev[lb];
    }

    /** 隨機選一句 fallbackQuestions/fallbackActionIds, 包裝做 MatchResult。
     *  type 用 "CHAT" (純粹回應, 不屬於任何 operation), operation/slot 是 null。
     *  空池 (骨架語言未填內容) 就回 null - 呼叫方當靜音處理, 唔 crash
     *  (否則 random.nextInt(0) 即拋 IllegalArgumentException)。 */
    private MatchResult fallback() {
        if (fallbackQuestions.length == 0 || fallbackActionIds.length == 0) return null;
        int i = random.nextInt(fallbackQuestions.length);
        return new MatchResult(fallbackQuestions[i], "CHAT", null, null,
                fallbackQuestions[i], fallbackActionIds[i], false, 4);
    }

    private MatchResult toResult(Entry e, int layer) {
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
        return new MatchResult(e.q, e.type, e.op, e.slot, answer, actionId, true, layer);
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

    /** 由 assets/semantic/action_category_pools.json 讀入 17 個分類 -> action id
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

    /** 兩份 load 共用的 assets 讀檔（經 IOUtil，不再各自寫 4k loop）。 */
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
     *  呼叫方 (SemanticCenter.handleSemanticMatch()) 應該在拿到 MatchResult
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


