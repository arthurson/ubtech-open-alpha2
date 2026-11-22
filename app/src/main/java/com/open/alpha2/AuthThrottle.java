package com.open.alpha2;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 面板 token 猜測節流：按「來源 IP」累計錯誤，超過免費額度後指數鎖定。
 *
 * <p>點解按 IP：全局節流會令攻擊者一直試就鎖死正常用家；按 IP 就只罰到嗰個來源。被鎖嘅 IP
 * 連<b>正確</b> token 都唔驗證（PanelAuth 喺比對之前先查鎖），否則攻擊者鎖住期間仍然可以繼續猜。
 * 冇帶 token 嘅請求唔係猜測，唔計（PanelAuth 只喺「帶咗 token 而且錯」先 recordFailure）。</p>
 *
 * <p>只計「不同嘅」錯誤 token：同一個錯誤 token 重複出現唔係新猜測（舊 token 留喺瀏覽器 localStorage
 * 嘅分頁開頁時會並行發幾十個 API，全部帶住同一個舊 token，唔可以因此鎖住正常用家）；攻擊者每次猜測
 * 必然唔同，照計。每個 IP 只記最近 MAX_RECENT 個，超過 MAX_CANDIDATE_LEN 字元嘅 candidate（不可能係
 * 有效 token）一律照計，記憶體有界。</p>
 *
 * <p>錯誤計數 FREE_FAILURES 次內免費（正常用家打錯幾次、或者舊分頁用舊 token 輪詢都唔會被鎖），
 * 之後每多一次錯誤鎖 BASE_LOCK_MS × 2^n，上限 MAX_LOCK_MS；MAX_LOCK_MS 刻意唔高：50 bit token
 * 每分鐘一次猜測已經完全唔實際，而用家改 token 後最多等一分鐘。FORGET_MS 冇失敗就忘記該 IP。
 * 純 java.*，注入 Clock 方便單元測試（AuthThrottleTest）。</p>
 */
final class AuthThrottle {
    interface Clock {
        long now();
    }

    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    static final int FREE_FAILURES = 10;
    static final long BASE_LOCK_MS = 5000;
    static final long MAX_LOCK_MS = 60000;
    static final long FORGET_MS = 10 * 60000;
    static final int MAX_TRACKED = 512;
    /** 每個 IP 記住最近咁多個「已計過嘅錯誤 token」。 */
    static final int MAX_RECENT = 32;
    /** token 格式上限（ApiValidator.checkPanelTokenFormat：8-64）；更長嘅 candidate 不可能有效。 */
    static final int MAX_CANDIDATE_LEN = 64;
    private static final String UNKNOWN = "?";

    private static final class State {
        int count;
        long lastFailure;
        long blockedUntil;
        LinkedHashSet<String> recent;
    }

    private final Clock clock;
    private final Map<String, State> states = new HashMap<String, State>();

    AuthThrottle(Clock clock) {
        this.clock = clock;
    }

    private static String key(String ip) {
        return ip == null || ip.isEmpty() ? UNKNOWN : ip;
    }

    /** @return 仲要鎖幾多毫秒（&gt;0＝被鎖，唔好驗證 token）；0＝冇鎖。 */
    synchronized long lockedForMs(String ip) {
        if (states.isEmpty()) return 0;
        String k = key(ip);
        State s = states.get(k);
        if (s == null) return 0;
        long now = clock.now();
        if (now - s.lastFailure > FORGET_MS) {
            states.remove(k);
            return 0;
        }
        return s.blockedUntil > now ? s.blockedUntil - now : 0;
    }

    /** 唔知／唔提供 candidate：一律計一次（保守）。 */
    synchronized void recordFailure(String ip) {
        recordFailure(ip, null);
    }

    /**
     * @param candidate 呢次猜嘅錯誤 token；已經計過嘅同一個值唔再累計（見類別說明）。
     */
    synchronized void recordFailure(String ip, String candidate) {
        long now = clock.now();
        String k = key(ip);
        State s = states.get(k);
        if (s != null && now - s.lastFailure > FORGET_MS) s = null;
        boolean trackable = candidate != null && candidate.length() <= MAX_CANDIDATE_LEN;
        if (s != null && trackable && s.recent != null && s.recent.contains(candidate)) {
            return; // 同一個舊／錯 token 重複（例如分頁輪詢）：唔係新猜測
        }
        if (s == null) {
            makeRoom(now);
            s = new State();
            states.put(k, s);
        }
        if (trackable) {
            if (s.recent == null) s.recent = new LinkedHashSet<String>();
            if (s.recent.size() >= MAX_RECENT) {
                Iterator<String> it = s.recent.iterator();
                it.next();
                it.remove();
            }
            s.recent.add(candidate);
        }
        s.count++;
        s.lastFailure = now;
        if (s.count > FREE_FAILURES) {
            int over = s.count - FREE_FAILURES; // 1,2,3,...
            long lock = BASE_LOCK_MS << Math.min(over - 1, 16);
            if (lock > MAX_LOCK_MS || lock <= 0) lock = MAX_LOCK_MS;
            s.blockedUntil = now + lock;
        }
    }

    /** 驗證成功：清走該 IP 嘅紀錄（熱路徑：冇紀錄時唔做嘢）。 */
    synchronized void recordSuccess(String ip) {
        if (states.isEmpty()) return;
        states.remove(key(ip));
    }

    synchronized int trackedCount() {
        return states.size();
    }

    /** 測試用：該 IP 目前累計嘅錯誤數。 */
    synchronized int failureCount(String ip) {
        State s = states.get(key(ip));
        return s == null ? 0 : s.count;
    }

    /** 容量上限：先清過期，仲滿就剔走最舊嗰個（攻擊者轉 IP 都唔可以食爆記憶體）。 */
    private void makeRoom(long now) {
        if (states.size() < MAX_TRACKED) return;
        for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext();) {
            if (now - it.next().getValue().lastFailure > FORGET_MS) it.remove();
        }
        if (states.size() < MAX_TRACKED) return;
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<String, State> e : states.entrySet()) {
            if (e.getValue().lastFailure < oldestAt) {
                oldestAt = e.getValue().lastFailure;
                oldest = e.getKey();
            }
        }
        if (oldest != null) states.remove(oldest);
    }
}
