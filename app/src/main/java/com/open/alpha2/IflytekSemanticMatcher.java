package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 中文語意配對引擎 - 由「悠聊」(UbtechIflytekMix, com.ubtech.iflytekmix) 呢個原廠 APK
 * 反編譯還原返嚟嘅本地問法/答案/動作對照表, 目的係完全取代悠聊, 等 OpenAlpha2 自己都
 * 識得「聽到一句話 -> 決定講咩 + 做咩動作」, 唔使再靠悠聊個 package。
 *
 * 資料來源: 悠聊 APK 嘅 assets/local_semantic (一份 XLS 問法對照表, 850 條問法) 經
 * androguard 反編譯 + 人手核對 202_actions_classified.txt 之後轉出嚟嘅
 * assets/iflytek/iflytek_semantic_zh.json - 詳見對話 history。每條記錄:
 *   q      - 用戶問法 (例如 "跳舞", "你好嗎")
 *   a      - 候選答案句 (0~3句, 隨機揀一句做 TTS; 部分 FUNCTION 類冇答案句)
 *   type   - ACTION (有動作) | FUNCTION (系統操作, 例如音量/連線) | CHAT (純寒暄, 冇動作)
 *   op     - 悠聊原本嘅 operation 代號 (例如 "DANCE"), ACTION/FUNCTION 先有
 *   slot   - 方向/情緒等參數 (例如 "LEFT"), 得部分 ACTION 有
 *   actionId - 已經由 operation+slot 對應好嘅、202 動作清單入面嘅真實 action id
 *              (即係 xiaozhi_actions.json 嗰 202 個入面其中一個), 得 ACTION 先有。
 *              兩種特殊值: "__RANDOM__" (TFBOY 呢類冇固定動作, 202 個入面隨便揀一個
 *              - 見 resolveRandomActionId()); "__RANDOM_CATEGORY__<key>" (2026-08 新增
 *              - 用戶講到分類名但冇講出具體邊個動作, 例如「跳舞」「玩下瑜伽」, 要喺
 *              嗰個分類入面隨機揀一個 - 見 resolveCategoryRandomActionId())
 *
 * 分類 random (__RANDOM_CATEGORY__): 202 個動作本身分成 5 大類 17 個子分類 (見用戶
 * 提供嘅 202_actions_classified.txt), 目標係「講到具體動作名就直接做嗰個, 淨係講到
 * 分類名 (例如「瑜伽」「跳舞」「講故事」) 就喺嗰類入面隨機揀一個」, 三層 fallback:
 * 具體動作名 (原有 32 operation) > 子分類 (例如 DANCE_KIDS) > 大分類 (例如
 * DANCE_ANY)。分類 -> action id pool 嘅對照表存喺
 * assets/iflytek/action_category_pools.json, 由 loadCategoryPools() 讀入。
 *
 * 呢個 class 淨係負責「文字 -> 配對結果」, 唔負責執行 TTS/動作 - 跟返呢個 project
 * 一貫嘅分層方式 (好似 resolveActionId() 淨係負責解析、唔負責 call
 * robot.action_PlayActionName() 咁), 執行嗰步留俾呼叫方 (MainActivity 嘅
 * onServerCallBack) 做, 方便測試同重用。
 *
 * 只做中文 - 英文 ASR 引擎嗰邊未有語料, 到有嗰日應該開一個獨立嘅
 * IflytekSemanticMatcherEn 用另一份 iflytek_semantic_en.json, 唔喺呢個 class 度加
 * language 參數 (因為機身 ASR 引擎本身一次淨係揀到一種語言, 兩個 matcher 唔會同時用)。
 *
 * Zero-third-party-dependency: 淨係用 org.json (Android 內建), 冇額外 library。
 */
public class IflytekSemanticMatcher {
    private static final String TAG = "IflytekSemanticMatcher";
    private static final String ASSET_PATH = "iflytek/iflytek_semantic_zh.json";
    private static final String ASSET_PATH_CATEGORIES = "iflytek/action_category_pools.json";
    private static final String RANDOM_CATEGORY_PREFIX = "__RANDOM_CATEGORY__";

    /** 配對結果。type 同 MainActivity 已有嘅 asr_result event 格式對齊,
     *  answer/actionId 可能係 null (例如 CHAT 類冇 actionId, 部分 FUNCTION 冇 answer)。 */
    public static final class MatchResult {
        public final String question;   // 命中嘅原始問法 (debug 用)
        public final String type;       // "ACTION" | "FUNCTION" | "CHAT"
        public final String operation;  // 悠聊 operation 代號, CHAT 類係 null
        public final String slot;       // 方向/情緒 slot, 冇就係 null
        public final String answer;     // 隨機揀一句嘅回覆句, 冇答案句就係 null
        public final String actionId;   // 202動作清單入面嘅 action id, 冇動作就係 null

        MatchResult(String question, String type, String operation, String slot,
                    String answer, String actionId) {
            this.question = question;
            this.type = type;
            this.operation = operation;
            this.slot = slot;
            this.answer = answer;
            this.actionId = actionId;
        }
    }

    /** 內部記錄, 對應 iflytek_semantic_zh.json 入面每一行。 */
    private static final class Entry {
        String q;
        String[] answers;
        String type;
        String op;
        String slot;
        String actionId;
    }

    private final Context appContext;
    private final Random random = new Random();

    /** Lazily loaded on first match() call, cached afterwards - 850 條記錄喺呢部機
     *  (API 22) 全部揀住喺記憶體都係得幾十 KB parsed 狀態, 完全負擔得起, 冇必要每次
     *  match 都重新讀 assets。同 loadXiaozhiActions() 嘅 cache 風格一致。 */
    private List<Entry> cache;

    /** Lazily loaded cache of action_category_pools.json: category key (例如
     *  "DANCE_KIDS") -> 該分類全部 action id。同 cache 一致嘅 lazy-load 風格,
     *  淨係有 __RANDOM_CATEGORY__ 命中先會讀, 唔會拖慢正常 match() 流程。 */
    private java.util.Map<String, List<String>> categoryPoolsCache;

    public IflytekSemanticMatcher(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 由 assets 讀入 + parse 850 條記錄。讀取/parse 失敗就回傳空 list (唔會拋出),
     *  同 loadXiaozhiActions() 一致嘅「唔崩潰、log 一次」哲學。 */
    private synchronized List<Entry> load() {
        if (cache != null) return cache;
        List<Entry> result = new ArrayList<>();
        try (InputStream in = appContext.getAssets().open(ASSET_PATH)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
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
            Log.w(TAG, "load: failed to load assets/" + ASSET_PATH + ": " + e);
        }
        cache = result;
        return result;
    }

    /** 2026-08 新增: 完全搵唔到問法對應嗰陣嘅 fallback 回應 - 5 句、各自配唔同
     *  (已驗證冇聲效) 動作, match() 入面隨機揀一句, 等機械人聽唔明都有反應, 唔會
     *  啞口。呢個推翻咗之前「寧可清楚話搵唔到, 都唔亂噏」嘅決定 - 用戶已經明確
     *  要求加返呢層, 5 句本身語感係「請再講一次/講清楚啲」, 唔係亂噏一個唔相關嘅
     *  回覆, 所以唔算違反嗰個原則嘅精神。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "唔好意思，我聽唔明", "可以再講一次嗎", "我未太明白，可以講清楚啲嗎",
            "這個我還不太懂", "可以換個講法嗎",
    };
    private static final String[] FALLBACK_ACTION_IDS = {
            "1464835936013", // 搖頭
            "1464835936026", // 思考
            "1464835936043", // 眨眼
            "1509000313549", // 賣萌
            "1464835936087", // 點頭
    };

    /** 將一句 ASR 辨識出嚟嘅中文文字, 對照 850 條問法, 揾出最貼近嘅一條。
     *  跟 resolveActionId() 一樣嘅「由緊至鬆」三層做法, 有邊層命中就即刻用嗰層:
     *   1. 完全相等 (去頭尾空白)
     *   2. 命中問法完全包含喺輸入入面 (輸入夾雜其他字, 例如「阿爾法你好嗎」包含住
     *      問法「你好嗎」) - 揀當中最長嗰條問法, 减少短問法誤中夾喺長句入面嘅情況
     *   3. 輸入完全包含喺命中問法入面 (ASR 漏咗尾, 例如輸入「你好」、問法係
     *      「你好嗎」) - 都係揀最長嗰條問法
     *  三層都搵唔到就唔再回傳 null - 隨機揀一句 FALLBACK_QUESTIONS 做「聽唔明」
     *  嘅回應, 保證用戶講嘅嘢喺問法庫入面搵唔到都仲有反應, 唔會啞口。空白輸入
     *  (text 為 null 或者淨係得空白字元) 就真係冇嘢好答, 依然回傳 null。 */
    public MatchResult match(String text) {
        List<Entry> entries = load();
        String q = text == null ? "" : text.trim();
        if (q.isEmpty()) return null;
        if (entries.isEmpty()) return fallback();

        // 1) 完全相等
        for (Entry e : entries) {
            if (q.equals(e.q)) return toResult(e);
        }

        // 2) 問法完全包含喺輸入入面 (揀最長嗰條, 减少短問法誤中)
        Entry best = null;
        for (Entry e : entries) {
            if (!e.q.isEmpty() && q.contains(e.q)) {
                if (best == null || e.q.length() > best.q.length()) best = e;
            }
        }
        if (best != null) return toResult(best);

        // 3) 輸入完全包含喺問法入面 (ASR 漏字/縮短, 揀最長嗰條問法)
        for (Entry e : entries) {
            if (!e.q.isEmpty() && e.q.contains(q)) {
                if (best == null || e.q.length() > best.q.length()) best = e;
            }
        }
        if (best != null) return toResult(best);

        return fallback();
    }

    /** 隨機揀一句 FALLBACK_QUESTIONS/FALLBACK_ACTION_IDS, 包裝做 MatchResult。
     *  type 用 "CHAT" (純粹回應, 唔屬於任何 operation), operation/slot 係 null。 */
    private MatchResult fallback() {
        int i = random.nextInt(FALLBACK_QUESTIONS.length);
        return new MatchResult(FALLBACK_QUESTIONS[i], "CHAT", null, null,
                FALLBACK_QUESTIONS[i], FALLBACK_ACTION_IDS[i]);
    }

    private MatchResult toResult(Entry e) {
        String answer = null;
        if (e.answers != null && e.answers.length > 0) {
            answer = e.answers[random.nextInt(e.answers.length)];
        }
        String actionId = e.actionId;
        if ("__RANDOM__".equals(actionId)) {
            // TFBOY 呢類 operation 喺原廠 850 條問法入面冇對應固定動作 (悠聊本身淨係
            // 純聊天處理), 跟返用戶決定: 隨機揀一個 202 動作清單入面嘅動作。實際隨機
            // 邏輯留俾呼叫方做 (MainActivity 已經有 loadXiaozhiActions(), 呢個 class
            // 唔重複讀多一次 202 動作清單), 呢度回傳 "__RANDOM__" 標記俾呼叫方識別。
        }
        return new MatchResult(e.q, e.type, e.op, e.slot, answer, actionId);
    }

    /** debug/量度用: 已載入幾多條記錄。 */
    public int size() {
        return load().size();
    }

    /** 由 assets/iflytek/action_category_pools.json 讀入 17 個分類 -> action id
     *  pool 嘅對照表。讀取/parse 失敗就回傳空 map (唔會拋出), 同 load() 一致嘅
     *  「唔崩潰、log 一次」哲學。 */
    private synchronized java.util.Map<String, List<String>> loadCategoryPools() {
        if (categoryPoolsCache != null) return categoryPoolsCache;
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        try (InputStream in = appContext.getAssets().open(ASSET_PATH_CATEGORIES)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            JSONObject root = new JSONObject(buf.toString("UTF-8"));
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
            Log.w(TAG, "loadCategoryPools: failed to load assets/" + ASSET_PATH_CATEGORIES + ": " + e);
        }
        categoryPoolsCache = result;
        return result;
    }

    /** 若 actionId 係 "__RANDOM_CATEGORY__<key>" 格式, 解析出 <key> 並喺對應分類
     *  嘅 action id pool 入面隨機揀一個, 回傳真正嘅 action id; 唔係呢個格式就原樣
     *  回傳。搵唔到對應分類、或者分類係空 pool, 回傳 null (呼叫方要自行 fallback,
     *  例如 resolveRandomActionId() 隨機動作池)。
     *
     *  呼叫方 (MainActivity.handleIflytekSemanticText()) 應該喺攞到 MatchResult
     *  之後、真正 call robot.action_PlayActionName() 之前, 用呢個方法將
     *  actionId 解析做真實可播放嘅 id - 同 "__RANDOM__" 標記 (見
     *  resolveRandomActionId()) 屬於同一種「延遲到執行時先揀」嘅設計, 但呢個係
     *  分類限定嘅隨機, 唔係全部 202 個隨便揀。 */
    public String resolveCategoryRandomActionId(String actionId) {
        if (actionId == null || !actionId.startsWith(RANDOM_CATEGORY_PREFIX)) {
            return actionId;
        }
        String key = actionId.substring(RANDOM_CATEGORY_PREFIX.length());
        List<String> pool = loadCategoryPools().get(key);
        if (pool == null || pool.isEmpty()) {
            Log.w(TAG, "resolveCategoryRandomActionId: empty/missing pool for category " + key);
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
