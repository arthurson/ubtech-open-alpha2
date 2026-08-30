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
 * 中文語意配對引擎 - 由「悠聊」(UbtechIflytekMix, com.ubtech.iflytekmix) 這個原廠 APK
 * 反編譯還原回來的本地問法/答案/動作對照表, 目的是完全取代悠聊, 讓 OpenAlpha2 自己都
 * 認得「聽到一句話 -> 決定說什麼 + 做什麼動作」, 不用再靠悠聊那個 package。
 *
 * 資料來源: 悠聊 APK 的 assets/local_semantic (一份 XLS 問法對照表, 850 條問法) 經
 * androguard 反編譯 + 人手核對 202_actions_classified.txt 之後轉出來的
 * assets/iflytek/iflytek_semantic_zh.json - 詳見對話 history。每條記錄:
 *   q      - 用戶問法 (例如 "跳舞", "你好嗎")
 *   a      - 候選答案句 (0~3句, 隨機選一句做 TTS; 部分 FUNCTION 類沒答案句)
 *   type   - ACTION (有動作) | FUNCTION (系統操作, 例如音量/連線) | CHAT (純寒暄, 沒動作)
 *   op     - 悠聊原本的 operation 代號 (例如 "DANCE"), ACTION/FUNCTION 才有
 *   slot   - 方向/情緒等參數 (例如 "LEFT"), 只有部分 ACTION 有
 *   actionId - 已經由 operation+slot 對應好的、202 動作清單裡面的真實 action id
 *              (也就是 xiaozhi_actions.json 那 202 個裡面其中一個), 只有 ACTION 才有。
 *              兩種特殊值: "__RANDOM__" (TFBOY 這類沒固定動作, 202 個裡面隨便選一個
 *              - 見 resolveRandomActionId()); "__RANDOM_CATEGORY__<key>" (2026-08 新增
 *              - 用戶說到分類名但沒說出具體哪個動作, 例如「跳舞」「玩個瑜伽」, 要在
 *              那個分類裡面隨機選一個 - 見 resolveCategoryRandomActionId())
 *
 * 分類 random (__RANDOM_CATEGORY__): 202 個動作本身分成 5 大類 17 個子分類 (見用戶
 * 提供的 202_actions_classified.txt), 目標是「說到具體動作名就直接做那個, 只說到
 * 分類名 (例如「瑜伽」「跳舞」「講故事」) 就在那類裡面隨機選一個」, 三層 fallback:
 * 具體動作名 (原有 32 operation) > 子分類 (例如 DANCE_KIDS) > 大分類 (例如
 * DANCE_ANY)。分類 -> action id pool 的對照表存在
 * assets/iflytek/action_category_pools.json, 由 loadCategoryPools() 讀入。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式 (就像 resolveActionId() 只負責解析、不負責 call
 * robot.action_PlayActionName() 那樣), 執行那一步留給呼叫方 (MainActivity 的
 * onServerCallBack) 做, 方便測試和重用。
 *
 * 只做中文 - 英文 ASR 引擎那邊已經另有 IflytekSemanticMatcherEn 用另一份
 * iflytek_semantic_en.json, 不在這個 class 裡加 language 參數 (因為機身 ASR 引擎
 * 本身一次只能選到一種語言, 兩個 matcher 不會同時用)。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建), 沒額外 library。
 */
public class IflytekSemanticMatcher {
    private static final String TAG = "IflytekSemanticMatcher";
    private static final String ASSET_PATH = "iflytek/iflytek_semantic_zh.json";
    private static final String ASSET_PATH_CATEGORIES = "iflytek/action_category_pools.json";
    private static final String RANDOM_CATEGORY_PREFIX = "__RANDOM_CATEGORY__";

    /** 配對結果。type 和 MainActivity 已有的 asr_result event 格式對齊,
     *  answer/actionId 可能是 null (例如 CHAT 類沒 actionId, 部分 FUNCTION 沒 answer)。 */
    public static final class MatchResult {
        public final String question;   // 命中的原始問法 (debug 用)
        public final String type;       // "ACTION" | "FUNCTION" | "CHAT"
        public final String operation;  // 悠聊 operation 代號, CHAT 類是 null
        public final String slot;       // 方向/情緒 slot, 沒有就是 null
        public final String answer;     // 隨機選一句的回覆句, 沒答案句就是 null
        public final String actionId;   // 202動作清單裡面的 action id, 沒動作就是 null

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

    /** 內部記錄, 對應 iflytek_semantic_zh.json 裡面每一行。 */
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

    /** Lazily loaded on first match() call, cached afterwards - 850 條記錄在這台機
     *  (API 22) 全部載入在記憶體都只是幾十 KB parsed 狀態, 完全負擔得起, 沒必要每次
     *  match 都重新讀 assets。和 loadXiaozhiActions() 的 cache 風格一致。 */
    private List<Entry> cache;

    /** Lazily loaded cache of action_category_pools.json: category key (例如
     *  "DANCE_KIDS") -> 該分類全部 action id。和 cache 一致的 lazy-load 風格,
     *  只有 __RANDOM_CATEGORY__ 命中才會讀, 不會拖慢正常 match() 流程。 */
    private java.util.Map<String, List<String>> categoryPoolsCache;

    public IflytekSemanticMatcher(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 由 assets 讀入 + parse 850 條記錄。讀取/parse 失敗就回傳空 list (不會拋出),
     *  和 loadXiaozhiActions() 一致的「不崩潰、log 一次」哲學。 */
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

    /** 2026-08 新增: 完全找不到問法對應那陣的 fallback 回應 - 5 句、各自配不同
     *  (已驗證沒聲效) 動作, match() 裡面隨機選一句, 讓機械人聽不懂都有反應, 不會
     *  啞口。這個推翻了之前「寧可清楚說找不到, 也不亂講」的決定 - 用戶已經明確
     *  要求加回這層, 5 句本身語感是「請再說一次/說清楚一點」, 不是亂講一個不相關的
     *  回覆, 所以不算違反那個原則的精神。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "不好意思，我聽不懂", "可以再說一次嗎", "我還不太明白，可以說清楚一點嗎",
            "這個我還不太懂", "可以換個說法嗎",
    };
    private static final String[] FALLBACK_ACTION_IDS = {
            "1464835936013", // 搖頭
            "1464835936026", // 思考
            "1464835936043", // 眨眼
            "1509000313549", // 賣萌
            "1464835936087", // 點頭
    };

    /** 將一句 ASR 辨識出來的中文文字, 對照 850 條問法, 找出最貼近的一條。
     *  跟 resolveActionId() 一樣的「由緊至鬆」三層做法, 有哪層命中就立刻用那層:
     *   1. 完全相等 (去頭尾空白)
     *   2. 命中問法完全包含在輸入裡面 (輸入夾雜其他字, 例如「阿爾法你好嗎」包含著
     *      問法「你好嗎」) - 選當中最長那條問法, 減少短問法誤中夾在長句裡面的情況
     *   3. 輸入完全包含在命中問法裡面 (ASR 漏了尾, 例如輸入「你好」、問法是
     *      「你好嗎」) - 都是選最長那條問法
     *  三層都找不到就不再回傳 null - 隨機選一句 FALLBACK_QUESTIONS 做「聽不懂」
     *  的回應, 保證用戶說的話在問法庫裡面找不到都還有反應, 不會啞口。空白輸入
     *  (text 為 null 或者只有空白字元) 就真的沒東西好答, 依然回傳 null。
     *
     *  2026-08 修正: 輸入文字先經 SimplifiedToTraditional.toTraditional() normalize
     *  做繁體再比對 - online iFlytek ASR 引擎輸出的是簡體中文 (例如
     *  "你的爸爸是谁啊"), 但這個 database (iflytek_semantic_zh.json) 全部是書面
     *  繁體中文, 不 normalize 的話簡體輸入會完全 match 不中任何問法。這層轉換只
     *  影響「用來比對」的 q, 不改動 MatchResult.question (依然是 e.q 的繁體原文) -
     *  answer/actionId 一律來自 database 本身, 保證輸出永遠是繁體。 */
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

    /** 隨機選一句 FALLBACK_QUESTIONS/FALLBACK_ACTION_IDS, 包裝做 MatchResult。
     *  type 用 "CHAT" (純粹回應, 不屬於任何 operation), operation/slot 是 null。 */
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
            // TFBOY 這類 operation 在原廠 850 條問法裡面沒對應固定動作 (悠聊本身只
            // 純聊天處理), 跟著用戶決定: 隨機選一個 202 動作清單裡面的動作。實際隨機
            // 邏輯留給呼叫方做 (MainActivity 已經有 loadXiaozhiActions(), 這個 class
            // 不重複讀多一次 202 動作清單), 這裡回傳 "__RANDOM__" 標記給呼叫方識別。
        }
        return new MatchResult(e.q, e.type, e.op, e.slot, answer, actionId);
    }

    /** debug/量度用: 已載入多少條記錄。 */
    public int size() {
        return load().size();
    }

    /** 由 assets/iflytek/action_category_pools.json 讀入 17 個分類 -> action id
     *  pool 的對照表。讀取/parse 失敗就回傳空 map (不會拋出), 和 load() 一致的
     *  「不崩潰、log 一次」哲學。 */
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

    /** 若 actionId 是 "__RANDOM_CATEGORY__<key>" 格式, 解析出 <key> 並在對應分類
     *  的 action id pool 裡面隨機選一個, 回傳真正的 action id; 不是這個格式就原樣
     *  回傳。找不到對應分類、或者分類是空 pool, 回傳 null (呼叫方要自行 fallback,
     *  例如 resolveRandomActionId() 隨機動作池)。
     *
     *  呼叫方 (MainActivity.handleIflytekSemanticText()) 應該在拿到 MatchResult
     *  之後、真正 call robot.action_PlayActionName() 之前, 用這個方法將
     *  actionId 解析成真實可播放的 id - 和 "__RANDOM__" 標記 (見
     *  resolveRandomActionId()) 屬於同一種「延遲到執行時才選」的設計, 但這是
     *  分類限定的隨機, 不是全部 202 個隨便選。 */
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
