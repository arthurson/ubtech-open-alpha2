package com.open.alpha2;

/**
 * RandomMovePolicy 單元測試 (零依賴，不用 JUnit)，經 scripts/test-apivalidator.py 跑。
 * 鎖死語意：只有 CHAT 受「隨機動作」開關管，其餘類型照做。
 */
public final class RandomMovePolicyTest {
    private static int passed = 0;
    private static int failed = 0;

    private RandomMovePolicyTest() {}

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        // CHAT：開＝照做（唔抑制），關＝抑制
        eq(false, RandomMovePolicy.suppress("CHAT", true), "chat.on");
        eq(true, RandomMovePolicy.suppress("CHAT", false), "chat.off");
        // 非 CHAT：不論開關都唔抑制（用戶明講要做）
        eq(false, RandomMovePolicy.suppress("ACTION", true), "action.on");
        eq(false, RandomMovePolicy.suppress("ACTION", false), "action.off");
        eq(false, RandomMovePolicy.suppress("FUNCTION", false), "function.off");
        // 邊界：null／空／大小寫唔同都唔當 CHAT
        eq(false, RandomMovePolicy.suppress(null, false), "null.off");
        eq(false, RandomMovePolicy.suppress("", false), "empty.off");
        eq(false, RandomMovePolicy.suppress("chat", false), "lowercase.off");

        System.out.println("RandomMovePolicyTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
