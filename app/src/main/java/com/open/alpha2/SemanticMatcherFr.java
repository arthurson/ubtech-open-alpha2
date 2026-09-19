package com.open.alpha2;

import android.content.Context;

/**
 * 法文語意配對引擎 - 和 SemanticMatcherZh (中文版)／SemanticMatcherEn
 * (英文版)／SemanticMatcherEs (西班牙文版) 同一套語意配對機制, 專門處理法文。
 *
 * 資料來源: assets/semantic/semantic_fr.json (v2 grouped 格式, 同其他語言同一個
 * schema - 見 SemanticMatcherBase.load())。85 ACTION (同西版同 op/actionId,
 * 通用法文答案 5 句) ＋ 160 CHAT (電影對白／歌詞／名人名言／潮語各 40 組,
 * 每組日常法文問法＋5 個原生法文答案，800 句來自 france800.txt；id 係
 * fr.mov/fr.son/fr.cit/fr.arg)。問法全部本地日常口語寫成，唔係翻譯。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式，執行那一步留給呼叫方 (SemanticCenter 的 handleSemanticMatch)
 * 做, 方便測試和重用。
 *
 * 只做法文 - 和其他語言版各自獨立, 沒在這個 class 裡加 language 參數
 * (因為機身 ASR 引擎本身一次只能選到一種語言, matcher 不會同時用）。
 * SemanticCenter.handleSemanticMatch() 根據對話語言／文字判斷結果, 選用哪一個。
 * 載入/比對/分類 random 的實際邏輯全部在 SemanticMatcherBase, 這裡只提供
 * 法文專屬的 TAG/assets 路徑/fallback 問法組。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建, 經 base class), 沒額外
 * library。
 */
public class SemanticMatcherFr extends SemanticMatcherBase {
    private static final String TAG = "SemanticMatcherFr";
    private static final String ASSET_PATH = "semantic/semantic_fr.json";

    /** 完全找不到問法對應那陣的 fallback 回應 - 5 句法文,
     *  動作沿用中英文共用的 DEFAULT_FALLBACK_ACTION_IDS (silent),
     *  match() 裡面隨機選一句, 讓機械人聽不懂都有反應, 不會啞口。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "Pardon, je n'ai pas compris", "Peux-tu répéter, s'il te plaît ?",
            "Je ne suis pas sûr d'avoir compris", "Peux-tu le dire autrement ?",
            "Je n'ai pas bien compris",
    };

    public SemanticMatcherFr(Context context) {
        super(context, TAG, ASSET_PATH, FALLBACK_QUESTIONS, DEFAULT_FALLBACK_ACTION_IDS);
    }
}
