package com.open.alpha2;

import android.content.Context;

/**
 * 西班牙文語意配對引擎 - 和 SemanticMatcherZh (中文版)／SemanticMatcherEn
 * (英文版) 同一套語意配對機制, 專門處理西班牙文。
 *
 * 資料來源: assets/semantic/semantic_es.json (v2 grouped 格式, 同中英文版同一個
 * schema - 見 SemanticMatcherBase.load())。165 個 CHAT intent：5 組基礎寒暄
 * (每組 5 答) ＋ 160 組主題重組 (es.t.001-160，每組日常問法＋5 答案，
 * 由舊 41 主題組答案切開重組，湊整掉 3 句)。每個 intent 內:
 *   qs       - 用戶問法 (例如 "Hola", "Fiesta", "Háblame de amor")
 *   a        - 候選答案句 (隨機選一句做 TTS)
 *   type     - 全部 CHAT（第一版唔接動作，全部用已驗證 silent filler action id）
 *   actionId - filler 動作 id（思考／開心／贊同等silent 動作，同 TTS 一齊播唔打架；
 *              有聲判斷照行 SemanticCenter／ActionDirect.isSoundAction() 同一套）。
 *
 * 這個 class 只負責「文字 -> 配對結果」, 不負責執行 TTS/動作 - 跟著這個 project
 * 一貫的分層方式，執行那一步留給呼叫方 (SemanticCenter 的 handleSemanticMatch)
 * 做, 方便測試和重用。
 *
 * 只做西班牙文 - 和中英文版各自獨立, 沒在這個 class 裡加 language 參數
 * (因為機身 ASR 引擎本身一次只能選到一種語言, matcher 不會同時用）。
 * SemanticCenter.handleSemanticMatch() 根據對話語言／文字判斷結果, 選用哪一個。
 * 載入/比對/分類 random 的實際邏輯全部在 SemanticMatcherBase, 這裡只提供
 * 西班牙文專屬的 TAG/assets 路徑/fallback 問法組。
 *
 * Zero-third-party-dependency: 只用 org.json (Android 內建, 經 base class), 沒額外
 * library。
 */
public class SemanticMatcherEs extends SemanticMatcherBase {
    private static final String TAG = "SemanticMatcherEs";
    private static final String ASSET_PATH = "semantic/semantic_es.json";

    /** 完全找不到問法對應那陣的 fallback 回應 - 5 句西班牙文,
     *  動作沿用中英文共用的 DEFAULT_FALLBACK_ACTION_IDS (silent),
     *  match() 裡面隨機選一句, 讓機械人聽不懂都有反應, 不會啞口。 */
    private static final String[] FALLBACK_QUESTIONS = {
            "Perdona, no te he entendido", "¿Puedes repetirlo, por favor?",
            "No estoy seguro de haberte entendido", "¿Puedes decirlo de otra forma?",
            "No te he entendido bien",
    };

    public SemanticMatcherEs(Context context) {
        super(context, TAG, ASSET_PATH, FALLBACK_QUESTIONS, DEFAULT_FALLBACK_ACTION_IDS);
    }
}
