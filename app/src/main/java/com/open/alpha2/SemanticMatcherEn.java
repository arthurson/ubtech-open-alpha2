package com.open.alpha2;

import android.content.Context;

/**
 * 英文語意配對引擎 - 和 SemanticMatcherZh (中文版) 屬於同一套「完全取代悠聊/
 * AlphaEnglishChat」的語意配對機制, 但這個 class 專門處理英文。
 *
 * 資料來源: assets/iflytek/iflytek_semantic_en.json, 1000 條英文問法/答案/動作記錄。
 * 這份資料不是悠聊/AlphaEnglishChat 逐字拆出來或者翻譯來的 - 反編譯確認了
 * AlphaEnglishChat 主要靠 Api.ai (Dialogflow V1) 雲端 NLU, 而那個 API 已經在 2020 年
 * 3 月正式關閉, 英文語料沒得直接沿用。這 1000 句是跟著中文版 iflytek_semantic_zh.json
 * 的結構和動作對照表 (operation/actionId/分類 pool 全部一致, 已驗證 202 動作清單裡面
 * 沒撞聲效), 用道地英文重新創作的問法/答案 - 詳見對話 history。每條記錄:
 *   q      - 用戶問法 (例如 "Dance for me", "How old are you")
 *   a      - 候選答案句 (5句, 隨機選一句做 TTS; 部分 FUNCTION 類沒答案句)
 *   type   - ACTION (有動作) | FUNCTION (系統操作, 例如音量/連線) | CHAT (純寒暄, 沒動作)
 *   op     - operation 代號 (例如 "DANCE"), 和中文版一致, ACTION/FUNCTION 才有
 *   slot   - 方向/情緒等參數 (例如 "LEFT"), 只有部分 ACTION 有
 *   actionId - 已經由 operation+slot 對應好的、202 動作清單裡面的真實 action id
 *              (也就是 xiaozhi_actions.json 那 202 個裡面其中一個), 只有 ACTION 才有。
 *              兩種特殊值: "__RANDOM__" (TFBOY 這類沒固定動作, 202 個裡面隨便選一個
 *              - 見 resolveRandomActionId()); "__RANDOM_CATEGORY__<key>" (2026-08 新增
 *              - 用戶說到分類名但沒說出具體哪個動作, 例如 "Dance for me" "Do some yoga",
 *              要在那個分類裡面隨機選一個 - 見 resolveCategoryRandomActionId())
 *
 * 分類 random (__RANDOM_CATEGORY__): 和中文版共用同一份
 * assets/iflytek/action_category_pools.json (17 個分類, 已排除全部有聲效的動作),
 * 三層 fallback: 具體動作名 (原有 32 operation) > 子分類 (例如 DANCE_KIDS) > 大分類
 * (例如 DANCE_ANY)。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式 (就像 resolveActionId() 只負責解析、不負責 call
 * robot.action_PlayActionName() 那樣), 執行那一步留給呼叫方 (SemanticCenter 的
 * handleIflytekSemanticText) 做, 方便測試和重用。
 *
 * 只做英文 - 和 SemanticMatcherZh (中文版) 各自獨立, 沒在這個 class 裡加
 * language 參數 (因為機身 ASR 引擎本身一次只能選到一種語言, 兩個 matcher 不會同時用)。
 * SemanticCenter.handleIflytekSemanticText() 根據 looksChinese() 判斷結果, 選用哪一個。
 * 載入/比對/分類 random 的實際邏輯 (和中文版逐字相同) 全部在
 * SemanticMatcherBase, 這裡只提供英文專屬的 TAG/assets 路徑/fallback 問法組
 * (2026-09 抽出共用 base 前, 這個 class 和 SemanticMatcherZh 是兩份幾乎逐字
 * 重複的完整實作; MatchResult 現在也是共用同一個型別, SemanticCenter 不用再
 * toZhResult() 手動轉接)。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建, 經 base class), 沒額外
 * library。
 *
 * 命名備註: 2026-09 之前呢個 class 叫 IflytekSemanticMatcherEn - 個名純粹歷史原因
 * (中文版問法資料最初由 iFlytek APK 反編譯還原, 英文版跟同一套結構), 同機身已經
 * 永久唔再用嘅 Nuance/iFlytek binder TTS/ASR 引擎完全冇關係, 淨係個名容易誤導。
 * 改名做 SemanticMatcherEn 消除呢個誤導 (功能行為完全不變)。
 */
public class SemanticMatcherEn extends SemanticMatcherBase {
    private static final String TAG = "SemanticMatcherEn";
    private static final String ASSET_PATH = "iflytek/iflytek_semantic_en.json";

    /** 2026-08 新增: 完全找不到問法對應那陣的 fallback 回應 - 5 句、各自配不同
     *  (已驗證沒聲效) 動作, match() 裡面隨機選一句, 讓機械人聽不懂都有反應, 不會
     *  啞口。跟中文版 (SemanticMatcherZh) 一致的設計, 動作 id 也刻意一樣,
     *  讓中英文兩種語言的「聽不懂」表現一致。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "Sorry, I didn't quite catch that", "Could you say that again",
            "I'm not sure I understood that", "Can you try saying it differently",
            "I didn't quite get that",
    };

    public SemanticMatcherEn(Context context) {
        super(context, TAG, ASSET_PATH, FALLBACK_QUESTIONS, DEFAULT_FALLBACK_ACTION_IDS);
    }
}
