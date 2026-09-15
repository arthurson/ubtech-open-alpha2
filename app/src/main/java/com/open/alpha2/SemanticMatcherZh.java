package com.open.alpha2;

import android.content.Context;

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
 * assets/iflytek/action_category_pools.json, 由 SemanticMatcherBase 讀入。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式 (就像 resolveActionId() 只負責解析、不負責 call
 * robot.action_PlayActionName() 那樣), 執行那一步留給呼叫方 (SemanticCenter 的
 * handleIflytekSemanticText) 做, 方便測試和重用。
 *
 * 只做中文 - 英文 ASR 引擎那邊已經另有 SemanticMatcherEn 用另一份
 * iflytek_semantic_en.json, 不在這個 class 裡加 language 參數 (因為機身 ASR 引擎
 * 本身一次只能選到一種語言, 兩個 matcher 不會同時用)。載入/比對/分類 random 的實際
 * 邏輯 (和英文版逐字相同) 全部在 SemanticMatcherBase, 這裡只提供中文專屬的
 * TAG/assets 路徑/fallback 問法組 (2026-09 抽出共用 base 前, 這個 class 和
 * SemanticMatcherEn 是兩份幾乎逐字重複的完整實作)。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建, 經 base class), 沒額外
 * library。
 *
 * 命名備註: 2026-09 之前呢個 class 叫 IflytekSemanticMatcher - 個名純粹歷史原因
 * (問法資料最初由 iFlytek APK 反編譯還原), 同機身已經永久唔再用嘅 Nuance/iFlytek
 * binder TTS/ASR 引擎完全冇關係, 淨係個名容易誤導。改名做 SemanticMatcherZh
 * 消除呢個誤導 (功能行為完全不變)。
 */
public class SemanticMatcherZh extends SemanticMatcherBase {
    private static final String TAG = "SemanticMatcherZh";
    private static final String ASSET_PATH = "iflytek/iflytek_semantic_zh.json";

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

    public SemanticMatcherZh(Context context) {
        super(context, TAG, ASSET_PATH, FALLBACK_QUESTIONS, FALLBACK_ACTION_IDS);
    }
}
