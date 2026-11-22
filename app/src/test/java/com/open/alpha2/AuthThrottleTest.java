package com.open.alpha2;

/**
 * AuthThrottle 單元測試 (零依賴，不用 JUnit)：用假時鐘驗證免費額度、指數鎖定、上限、
 * 成功重置、IP 互相獨立、閒置遺忘、容量上限。
 */
public final class AuthThrottleTest {
    private static int passed = 0;
    private static int failed = 0;

    private AuthThrottleTest() {}

    private static void check(boolean ok, String name) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + name); }
    }

    private static final class FakeClock implements AuthThrottle.Clock {
        long t = 1_000_000;
        @Override public long now() { return t; }
    }

    private static void fail(AuthThrottle th, String ip, int n) {
        for (int i = 0; i < n; i++) th.recordFailure(ip);
    }

    public static void main(String[] args) {
        final int free = AuthThrottle.FREE_FAILURES;
        final long base = AuthThrottle.BASE_LOCK_MS, max = AuthThrottle.MAX_LOCK_MS;

        // 1. 免費額度內唔鎖
        FakeClock c = new FakeClock(); AuthThrottle th = new AuthThrottle(c);
        check(th.lockedForMs("1.2.3.4") == 0, "unknown ip is not locked");
        fail(th, "1.2.3.4", free);
        check(th.lockedForMs("1.2.3.4") == 0, free + " failures are free (typos / stale tabs)");

        // 2. 超過額度：鎖 5s，之後倍增，上限 MAX
        th.recordFailure("1.2.3.4");
        check(th.lockedForMs("1.2.3.4") == base, "failure #" + (free + 1) + " locks for " + base + " ms");
        c.t += base - 1;
        check(th.lockedForMs("1.2.3.4") == 1, "lock counts down");
        c.t += 1;
        check(th.lockedForMs("1.2.3.4") == 0, "lock expires");
        th.recordFailure("1.2.3.4");
        check(th.lockedForMs("1.2.3.4") == base * 2, "next failure doubles the lock");
        c.t += base * 2;
        th.recordFailure("1.2.3.4");
        check(th.lockedForMs("1.2.3.4") == base * 4, "and doubles again");
        for (int i = 0; i < 30; i++) { c.t += max + 1; th.recordFailure("1.2.3.4"); }
        check(th.lockedForMs("1.2.3.4") == max, "lock is capped at " + max + " ms (no overflow after many failures)");

        // 3. 成功重置
        th.recordSuccess("1.2.3.4");
        check(th.lockedForMs("1.2.3.4") == 0, "success clears the lock");
        fail(th, "1.2.3.4", free);
        check(th.lockedForMs("1.2.3.4") == 0, "after success the free allowance is back");

        // 4. IP 互相獨立：A 被鎖唔影響 B
        c = new FakeClock(); th = new AuthThrottle(c);
        fail(th, "10.0.0.1", free + 3);
        check(th.lockedForMs("10.0.0.1") > 0, "attacker ip is locked");
        check(th.lockedForMs("10.0.0.2") == 0, "a different ip is unaffected (no global lockout)");
        th.recordSuccess("10.0.0.2");
        check(th.lockedForMs("10.0.0.1") > 0, "someone else's success does not clear the attacker's lock");

        // 5. 閒置遺忘
        c = new FakeClock(); th = new AuthThrottle(c);
        fail(th, "9.9.9.9", free + 1);
        check(th.lockedForMs("9.9.9.9") > 0, "locked before idle period");
        c.t += AuthThrottle.FORGET_MS + 1;
        check(th.lockedForMs("9.9.9.9") == 0, "idle ip is forgotten");
        fail(th, "9.9.9.9", free);
        check(th.lockedForMs("9.9.9.9") == 0, "forgotten ip starts from a clean count");

        // 6. 未知／空 IP 共用一個桶（唔會因為 null 而繞過）
        c = new FakeClock(); th = new AuthThrottle(c);
        fail(th, null, free + 1);
        check(th.lockedForMs("") > 0 && th.lockedForMs(null) > 0, "null and empty ip share one bucket");

        // 7. 容量上限：轉 IP 都唔可以食爆記憶體，而且最新嘅 IP 仍然被追蹤
        c = new FakeClock(); th = new AuthThrottle(c);
        for (int i = 0; i < AuthThrottle.MAX_TRACKED * 3; i++) { c.t += 1; th.recordFailure("ip-" + i); }
        check(th.trackedCount() <= AuthThrottle.MAX_TRACKED, "tracked ips are bounded (" + th.trackedCount() + ")");
        String latest = "ip-" + (AuthThrottle.MAX_TRACKED * 3 - 1);
        fail(th, latest, free);
        check(th.lockedForMs(latest) > 0, "the most recent ip is still tracked and gets locked");

        // 8. 熱路徑：冇紀錄時 recordSuccess 唔出事
        th = new AuthThrottle(new FakeClock());
        th.recordSuccess("1.1.1.1");
        check(th.trackedCount() == 0, "recordSuccess on an empty throttle is a no-op");

        // 9. 只計「不同嘅」錯誤 token：舊 token 嘅分頁輪詢唔會鎖住正常用家
        c = new FakeClock(); th = new AuthThrottle(c);
        for (int i = 0; i < 5000; i++) th.recordFailure("5.5.5.5", "STALE-TOKEN-1");
        check(th.lockedForMs("5.5.5.5") == 0 && th.failureCount("5.5.5.5") == 1,
                "5000 repeats of the same stale token count once and never lock (count=" + th.failureCount("5.5.5.5") + ")");
        for (int i = 0; i < free; i++) th.recordFailure("5.5.5.5", "guess-" + i);
        check(th.lockedForMs("5.5.5.5") > 0, "distinct wrong tokens still lock (an attacker's guesses are all different)");
        // 老用家場景：舊 token 狂發 + 打錯幾次新 token 之後打啱，照樣可以用
        th = new AuthThrottle(new FakeClock());
        for (int i = 0; i < 300; i++) th.recordFailure("6.6.6.6", "STALE");
        th.recordFailure("6.6.6.6", "typo-1"); th.recordFailure("6.6.6.6", "typo-2");
        check(th.lockedForMs("6.6.6.6") == 0, "stale spam plus a couple of typos does not lock the legitimate user");

        // 10. 淘汰：最近 MAX_RECENT 個之外嘅舊值再出現會重新計（記憶體有界，攻擊者唔可以靠循環避開）
        th = new AuthThrottle(new FakeClock());
        th.recordFailure("7.7.7.7", "c0");
        th.recordFailure("7.7.7.7", "c0");
        check(th.failureCount("7.7.7.7") == 1, "a repeated candidate is ignored");
        for (int i = 1; i < AuthThrottle.MAX_RECENT; i++) th.recordFailure("7.7.7.7", "c" + i);
        check(th.failureCount("7.7.7.7") == AuthThrottle.MAX_RECENT, "MAX_RECENT distinct candidates remembered");
        th.recordFailure("7.7.7.7", "c" + AuthThrottle.MAX_RECENT); // 擠走 c0
        th.recordFailure("7.7.7.7", "c0");                          // 已被淘汰 → 重新計
        check(th.failureCount("7.7.7.7") == AuthThrottle.MAX_RECENT + 2, "an evicted candidate counts again (got " + th.failureCount("7.7.7.7") + ")");

        // 11. 超過 token 長度上限嘅 candidate 不可能有效：一律照計，亦唔佔記憶體
        th = new AuthThrottle(new FakeClock());
        StringBuilder longTok = new StringBuilder();
        for (int i = 0; i < AuthThrottle.MAX_CANDIDATE_LEN + 1; i++) longTok.append('a');
        for (int i = 0; i < free + 1; i++) th.recordFailure("8.8.8.8", longTok.toString());
        check(th.lockedForMs("8.8.8.8") > 0, "over-long candidates are never deduplicated");

        // 12. 舊 API（冇 candidate）保守：每次都計
        th = new AuthThrottle(new FakeClock());
        for (int i = 0; i < free + 1; i++) th.recordFailure("4.4.4.4");
        check(th.lockedForMs("4.4.4.4") > 0, "recordFailure(ip) without a candidate always counts");

        // 13. 成功清走「已計過」紀錄，之後同一個錯值重新計
        th = new AuthThrottle(new FakeClock());
        th.recordFailure("3.3.3.3", "dup"); th.recordSuccess("3.3.3.3");
        th.recordFailure("3.3.3.3", "dup");
        check(th.failureCount("3.3.3.3") == 1, "success wipes the remembered candidates");

        System.out.println("AuthThrottleTest: passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
