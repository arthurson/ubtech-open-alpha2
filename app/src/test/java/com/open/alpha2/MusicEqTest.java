package com.open.alpha2;

/**
 * MusicEq 單元測試 (零依賴，不用 JUnit)。
 *
 * 同 ApiValidatorTest／ColorTrackTest 一樣經 `scripts/test-apivalidator.py`
 * 純 javac/java 跑。`./gradlew assembleDebug` 完全唔受影響。
 */
public final class MusicEqTest {
    private static int passed = 0;
    private static int failed = 0;

    private MusicEqTest() {}

    private static void eq(int expected, int actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(boolean expected, boolean actual, String name) {
        if (expected == actual) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eqArr(int[] expected, int[] actual, String name) {
        boolean ok = expected != null && actual != null && expected.length == actual.length;
        if (ok) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) { ok = false; break; }
            }
        }
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + arrStr(expected) + " actual=" + arrStr(actual));
        }
    }

    private static String arrStr(int[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        return sb.append("]").toString();
    }

    private static void throwsIllegal(Runnable r, String name) {
        try {
            r.run();
            failed++;
            System.out.println("FAIL " + name + ": expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            passed++;
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + ": wrong throwable " + t);
        }
    }

    public static void main(String[] args) {
        // ── preset 表 ──
        eq(true, MusicEq.isPreset("rock"), "preset.known");
        eq(true, MusicEq.isPreset("custom"), "preset.custom");
        eq(false, MusicEq.isPreset("metal"), "preset.unknown");
        eq(false, MusicEq.isPreset(null), "preset.null");
        eq(false, MusicEq.isPreset("Rock"), "preset.case");
        eqArr(new int[] { 0, 0, 0, 0, 0 }, MusicEq.presetDb("normal"), "table.normal");
        eq(5, MusicEq.presetDb("rock").length, "table.len");
        eq(true, MusicEq.presetDb("custom") == null, "table.customNull");
        eq(true, MusicEq.presetDb("nope") == null, "table.unknownNull");
        // 表唔可以俾出面改（回 clone）：改完再攞要一樣。
        int[] a = MusicEq.presetDb("bass");
        a[0] = 999;
        eq(6, MusicEq.presetDb("bass")[0], "table.clone");

        // ── resample ──
        eqArr(new int[] { 0, 0, 0, 0, 0 }, MusicEq.resampleDb(new int[] { 0, 0, 0, 0, 0 }, 5), "rs.identity");
        int[] rsId = MusicEq.resampleDb(new int[] { 1, 2, 3, 4, 5 }, 5);
        eq(true, rsId != null && rsId.length == 5 && rsId[2] == 3, "rs.sameLen");
        // 5→3：頭尾照抄，中間插值。
        eqArr(new int[] { 0, 10, 20 }, MusicEq.resampleDb(new int[] { 0, 5, 10, 15, 20 }, 3), "rs.down");
        // 5→7 首尾保。
        int[] up = MusicEq.resampleDb(new int[] { 0, 0, 0, 0, 10 }, 7);
        eq(7, up.length, "rs.upLen");
        eq(0, up[0], "rs.upFirst");
        eq(10, up[6], "rs.upLast");
        // 1 band＝平均。
        eqArr(new int[] { 3 }, MusicEq.resampleDb(new int[] { 1, 2, 3, 4, 5 }, 1), "rs.one");
        eq(true, MusicEq.resampleDb(null, 5) == null, "rs.null");
        eq(true, MusicEq.resampleDb(new int[] { 1 }, 0) == null, "rs.zero");

        // ── parse ──
        eqArr(new int[] { 0, 300, -200 }, MusicEq.parseLevelsMb("0,300,-200", 1, 16), "parse.ok");
        eqArr(new int[] { 0, 300 }, MusicEq.parseLevelsMb(" 0 , 300 ", 1, 16), "parse.spaces");
        throwsIllegal(new Runnable() { public void run() { MusicEq.parseLevelsMb(null, 1, 16); } }, "parse.null");
        throwsIllegal(new Runnable() { public void run() { MusicEq.parseLevelsMb("", 1, 16); } }, "parse.empty");
        throwsIllegal(new Runnable() { public void run() { MusicEq.parseLevelsMb("0,1,2,3,4,5", 5, 5); } }, "parse.count");
        throwsIllegal(new Runnable() { public void run() { MusicEq.parseLevelsMb("0,x,2", 1, 16); } }, "parse.nan");

        // ── clamp／換算 ──
        eq(-1500, MusicEq.clampMb(-9999, -1500, 1500), "clamp.lo");
        eq(1500, MusicEq.clampMb(9999, -1500, 1500), "clamp.hi");
        eq(300, MusicEq.clampMb(300, -1500, 1500), "clamp.mid");
        eq(300, MusicEq.dbToMb(3), "db.mb");
        eq(-1500, MusicEq.dbToMb(-15), "db.neg");

        System.out.println("MusicEqTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
