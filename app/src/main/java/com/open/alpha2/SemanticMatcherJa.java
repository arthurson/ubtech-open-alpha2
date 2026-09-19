package com.open.alpha2;

import android.content.Context;

/**
 * 日文語意配對引擎 - 和 SemanticMatcherZh (中文版)／SemanticMatcherEn
 * (英文版)／SemanticMatcherEs (西班牙文版)／SemanticMatcherFr (法文版)
 * 同一套語意配對機制, 專門處理日文。
 *
 * 資料來源: assets/semantic/semantic_ja.json (v2 grouped 格式, 同其他語言同一個
 * schema - 見 SemanticMatcherBase.load())。85 ACTION (同西版同 op/actionId,
 * 通用日文答案 5 句＋jp800.txt 精選對白／歌詞／俚語按意配對，英文・中文句已剔除)
 * ＋200 日常 CHAT (ja.daily.001-200，每組日常問法＋口語答案 5 句，生活話題，
 * 中文版覆蓋為參照)。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式，執行那一步留給呼叫方 (SemanticCenter 的 handleSemanticMatch)
 * 做, 方便測試和重用。
 *
 * 只做日文 - 和其他語言版各自獨立, 沒在這個 class 裡加 language 參數
 * (因為機身 ASR 引擎本身一次只能選到一種語言, matcher 不會同時用）。
 * SemanticCenter.handleSemanticMatch() 根據對話語言／文字判斷結果, 選用哪一個。
 * 載入/比對/分類 random 的實際邏輯全部在 SemanticMatcherBase, 這裡只提供
 * 日文專屬的 TAG/assets 路徑/fallback 問法組。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建, 經 base class), 沒額外
 * library。
 */
public class SemanticMatcherJa extends SemanticMatcherBase {
    private static final String TAG = "SemanticMatcherJa";
    private static final String ASSET_PATH = "semantic/semantic_ja.json";

    /** 完全找不到問法對應那陣的 fallback 回應 - 5 句日文,
     *  動作沿用各語言共用的 DEFAULT_FALLBACK_ACTION_IDS (silent),
     *  match() 裡面隨機選一句, 讓機械人聽不懂都有反應, 不會啞口。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "すみません、よく聞き取れませんでした", "もう一度言ってもらえますか？",
            "よく分かりませんでした", "別の言い方で言ってもらえますか？",
            "うまく聞き取れませんでした",
    };

    public SemanticMatcherJa(Context context) {
        super(context, TAG, ASSET_PATH, FALLBACK_QUESTIONS, DEFAULT_FALLBACK_ACTION_IDS);
    }
}
