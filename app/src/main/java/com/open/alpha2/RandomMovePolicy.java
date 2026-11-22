package com.open.alpha2;

/**
 * 離線對話「隨機動作」開關判斷（純 java.*，方便單元測試；見 SemanticCenter）。
 *
 * 只有 type=CHAT（閒聊／聽唔明亂答）帶嘅 __RANDOM__ 受開關管；
 * 非 CHAT（例如 ACTION 類個別 __RANDOM__）係用戶明講要做嘅，唔受影響。
 */
final class RandomMovePolicy {
    private RandomMovePolicy() {}

    /** true＝要抑制隨機動作（只講嘢唔郁）。 */
    static boolean suppress(String type, boolean enabled) {
        return "CHAT".equals(type) && !enabled;
    }
}
