package com.open.alpha2;

/**
 * 顏色追蹤「行路跟」決策狀態機（純 Java：只用 java.*，唔掂 android.*／項目類，可直接單測＋模擬）。
 *
 * <p>{@link ColorTrackCenter} 每個偵測幀組一個 {@link Input}，呼 {@link #step}，
 * 攞返一個 {@link Command} 去執行（播 .ubx 一步、WalkCenter hold／release）。
 * 所有「幾時郁、郁邊個」嘅判斷都喺呢度，Center 只負責量度同執行。
 *
 * <h3>身體方位</h3>
 * bearing ＝ dx ＋ headOffset。頭追色後色被拉返畫面中間（dx≈0），淨睇 dx 身體永遠唔會轉；
 * 加返頭 yaw 偏離 home 先係色相對身體嘅真方位（見 {@link ColorTrackLogic#headOffset}）。
 *
 * <h3>動作分級（偏差由細到大）</h3>
 * <pre>
 * |b| &gt; bigTurnDb            大轉
 * |b| &gt; turnDb               轉（細）
 * |b| &gt; bigSideDb（夠近）    大橫移（3 步）
 * |b| &gt; sideDb（夠近）        橫移（1 步）；sideDb=0 即關
 * coverage &gt;= backCoverage    後退（backCoverage=0 即關）
 * 未夠近                      前進
 * 其餘                        企定
 * </pre>
 * 橫移唔轉身，保持朝向；色塊太細（太遠）橫移一步改變唔到方位，所以要 coverage&gt;=sideMinCoverage。
 *
 * <h3>防抖／防誤觸</h3>
 * <ul>
 * <li>確認幀：同一決策連續 {@link #CONFIRM_FRAMES} 幀先出手（單幀假 blob 唔會觸發成步）。</li>
 * <li>近距離滯後：coverage 去到 closeCoverage 即「夠近」，要跌到 closeCoverage×{@link #NEAR_HYST} 以下先再前進。</li>
 * <li>步後安定：動作／收步完結後 {@link #SETTLE_MS} 內唔決策（機身仲晃，質心唔準）。</li>
 * <li>出手後 {@link #CMD_GUARD_MS} 內視為忙（播放狀態更新有延遲）。</li>
 * </ul>
 *
 * <h3>跌倒保護</h3>
 * Input.fall 來自跌倒看門狗（{@link FallMonitor}）：{@link #FALL_OK}＝已武裝（見過企直）；
 * {@link #FALL_NOT_READY}＝未武裝／睇唔到加速度計——唔識偵測跌倒就唔行；{@link #FALL_DOWN}＝跌低／起身中／起身失敗。
 * 非 OK 嗰陣唔發任何新指令（行緊步態就收步），等看門狗播起身動作；返到 OK 後多等
 * {@link #POST_FALL_SETTLE_MS} 先自動繼續跟。{@link #FALL_WINDOW_MS} 內跌夠 maxFalls 次就
 * 回 {@link #GIVE_UP}（放棄，唔再行；Center 關掉行路跟，要人手重新開）。
 *
 * <h3>gait 引擎（WalkCenter 連續步態）</h3>
 * 前進／後退改成 hold（每幀續租＋變速）／release（平滑收步）：夠近即 release，
 * 偏出 turnDb 連續確認後 release，掃唔到連續 {@link #LOST_RELEASE_FRAMES} 幀 release
 *（唔續租 1.5 秒租約亦會自動收步）。起步後 {@link #GAIT_GRACE_MS} 內唔因方位 release
 *（頭仲喺度被步態拉返 home，dx 未必係身體方位）。步態唔識轉向，所以仍然係「停→轉→行」。
 */
public final class ColorTrackPlanner {

    // ── 命令類型 ──
    public static final String NONE = "none";
    /** gait 前進／後退：每幀都發（續租＋變速）。 */
    public static final String HOLD_FWD = "hold_fwd";
    public static final String HOLD_BACK = "hold_back";
    /** gait 平滑收步（ubx 引擎冇呢個）。 */
    public static final String RELEASE = "release";
    /** ubx 引擎前進／後退一步（播 walkFwd／walkBack）。 */
    public static final String STEP_FWD = "step_fwd";
    public static final String STEP_BACK = "step_back";
    public static final String TURN_L = "turn_left";
    public static final String TURN_R = "turn_right";
    public static final String TURN_L_BIG = "turn_left_big";
    public static final String TURN_R_BIG = "turn_right_big";
    public static final String SIDE_L = "side_left";
    public static final String SIDE_R = "side_right";
    public static final String SIDE_L_BIG = "side_left_big";
    public static final String SIDE_R_BIG = "side_right_big";

    /** 跌倒太多次：放棄行路跟（Center 收到即關 walkFollow）。 */
    public static final String GIVE_UP = "give_up";

    // ── 跌倒保護（Center 由 FallMonitor 讀出）──
    public static final int FALL_OK = 0;
    public static final int FALL_NOT_READY = 1;
    public static final int FALL_DOWN = 2;
    /** 起身後（返到 OK）多等幾耐先自動繼續：機身未必穩、頭仲未返正。 */
    public static final long POST_FALL_SETTLE_MS = 1500;
    /** 計跌倒次數嘅時間窗。 */
    public static final long FALL_WINDOW_MS = 120000;

    // ── 步態狀態（Center 由 WalkCenter 讀出）──
    public static final int WALK_NONE = 0;
    public static final int WALK_FWD = 1;
    public static final int WALK_BACK = 2;
    /** 收步中（release 後平滑返企定）。 */
    public static final int WALK_FINISHING = 3;

    // ── 常量（唔開 API：調錯會令機器抖，要改請連單測一齊改）──
    public static final int CONFIRM_FRAMES = 2;
    public static final long SETTLE_MS = 300;
    public static final long CMD_GUARD_MS = 400;
    public static final float NEAR_HYST = 0.75f;
    public static final float BACK_HYST = 0.85f;
    public static final long GAIT_GRACE_MS = 1200;
    public static final int LOST_RELEASE_FRAMES = 3;
    public static final int SEARCH_LOST_FRAMES = 6;
    /** 頭搜索：掃唔到連續幾多幀先開始掃；最多掃幾多幀（~7 s@150ms）就回 home 停低，唔無止境掃。 */
    public static final int HEAD_SEARCH_START_FRAMES = 3;
    public static final int HEAD_SEARCH_MAX_FRAMES = 50;
    /** 低於呢個 |bearing| 唔更新「最後見到嘅方向」（免中線附近亂跳）。 */
    private static final float SIDE_EPS = 0.05f;

    /** 參數快照（Center 每幀由 volatile 欄位組一份，免決策中途被 config 改一半）。 */
    public static final class Params {
        public float turnDb = 0.35f;
        public float bigTurnDb = 0.9f;
        public float sideDb = 0.15f;
        public float bigSideDb = 0.25f;
        public float sideMinCoverage = 0.04f;
        public float closeCoverage = 0.12f;
        /** 0＝唔後退。 */
        public float backCoverage = 0f;
        /** gait 前進最高速倍率（1.0-2.0；遠時用呢個，近時降到 1.0）。 */
        public float walkSpeed = 1.0f;
        /** true＝gait（WalkCenter hold／release），false＝ubx（播一步）。 */
        public boolean gait = true;
        /** 掃唔到時身體朝最後見到嘅方向轉幾多步（0＝唔搜）。 */
        public int searchSteps = 0;
        /** 跌倒保護開關（false＝唔睇 Input.fall，兼唔限制次數）。 */
        public boolean fallGuard = true;
        /** FALL_WINDOW_MS 內跌夠幾多次就放棄（1-10）。 */
        public int maxFalls = 3;

        /** 令門檻互相一致（big>=normal、backCoverage 必須明顯大過 closeCoverage）。 */
        void normalize() {
            if (bigTurnDb < turnDb) bigTurnDb = turnDb;
            if (bigSideDb < sideDb) bigSideDb = sideDb;
            if (walkSpeed < 1f) walkSpeed = 1f;
            if (walkSpeed > 2f) walkSpeed = 2f;
            if (backCoverage > 0f && backCoverage < closeCoverage * 1.3f) backCoverage = closeCoverage * 1.3f;
            if (searchSteps < 0) searchSteps = 0;
            if (maxFalls < 1) maxFalls = 1;
            if (maxFalls > 10) maxFalls = 10;
        }
    }

    /** 每幀輸入。 */
    public static final class Input {
        public boolean found;
        /** 畫面內偏移 -1 左 … +1 右。 */
        public float dx;
        /** 頭偏離 home（已折算成 dx 尺度，見 ColorTrackLogic#headOffset）。 */
        public float headOffset;
        /** blob 覆蓋率（found=false 時忽略）。 */
        public float coverage;
        public int walkState = WALK_NONE;
        /** 非步態嘅動作（.ubx 轉向／橫移／其他）播緊。 */
        public boolean otherPlaying;
        /** 跌倒看門狗狀態（FALL_*；fallGuard=false 時忽略）。 */
        public int fall = FALL_OK;
    }

    /** 輸出命令。 */
    public static final class Command {
        public String type = NONE;
        /** hold 用：步態速度倍率。 */
        public float speed = 1f;

        Command(String type) { this.type = type; }

        @Override public String toString() { return type; }
    }

    // ── 狀態 ──
    private int lostStreak = 0;
    private int lastSide = 0;
    private int searchDone = 0;
    private boolean near = false;
    private String pendKey = "";
    private int pendCnt = 0;
    private int pendRelease = 0;
    private long lastBusyMs = -1000000L;
    private long lastCmdMs = -1000000L;
    private long walkStartMs = -1000000L;
    private String lastType = NONE;
    private int sweepDir = 0;
    private int sweepFrames = 0;
    private int prevFall = FALL_OK;
    private final long[] fallTimes = new long[16];
    private int fallRing = 0;
    private int totalFalls = 0;
    private boolean gaveUp = false;

    public ColorTrackPlanner() {
        reset(); // 初始化 fallTimes 等
    }

    public synchronized void reset() {
        prevFall = FALL_OK;
        fallRing = 0;
        totalFalls = 0;
        gaveUp = false;
        for (int i = 0; i < fallTimes.length; i++) fallTimes[i] = Long.MIN_VALUE;
        sweepDir = 0;
        sweepFrames = 0;
        lostStreak = 0;
        lastSide = 0;
        searchDone = 0;
        near = false;
        pendKey = "";
        pendCnt = 0;
        pendRelease = 0;
        lastBusyMs = -1000000L;
        lastCmdMs = -1000000L;
        walkStartMs = -1000000L;
        lastType = NONE;
    }

    /** 最後見到目標嘅方向：-1 左、+1 右、0 未見過（Center 頭搜索用）。 */
    public synchronized int lastSide() { return lastSide; }

    /** 累計跌倒次數（reset 後重計；status 用）。 */
    public synchronized int totalFalls() { return totalFalls; }

    /** 上一個發出嘅命令類型（status 用）。 */
    public synchronized String lastCommand() { return lastType; }

    /**
     * 頭搜索：每個 .ubx／步態動作都會將頭拉返 home，大角度轉向後目標可能跌出 ±視角
     *（頭唔再企喺原本角度）。掃唔到連續 {@link #HEAD_SEARCH_START_FRAMES} 幀後，頭朝最後見到嘅方向
     * 逐幀掃（到端就掉頭），最多 {@link #HEAD_SEARCH_MAX_FRAMES} 幀，之後回 home 停低。
     * 淨係喺頭有空（Center 保證 headBusy=false 先調）嗰幀計數，所以 13 s 嘅步唔會蝕咗掃描窗口。
     *
     * @return 下一個頭 yaw；等於 curPan 即唔使發。
     */
    public synchronized int headSearchPan(int curPan, int step, int panMin, int panMax, int panHome) {
        if (lostStreak < HEAD_SEARCH_START_FRAMES) return curPan;
        sweepFrames++;
        if (sweepFrames > HEAD_SEARCH_MAX_FRAMES) return panHome;
        if (sweepDir == 0) sweepDir = lastSide < 0 ? -1 : 1;
        int np = curPan + sweepDir * Math.max(1, step);
        if (np >= panMax) { np = panMax; sweepDir = -1; }
        else if (np <= panMin) { np = panMin; sweepDir = 1; }
        return np;
    }

    public synchronized Command step(Input in, Params p, long now) {
        p.normalize();
        float b = in.found ? in.dx + in.headOffset : 0f;
        float a = Math.abs(b);
        if (in.found) {
            lostStreak = 0;
            if (a > SIDE_EPS) lastSide = b < 0f ? -1 : 1;
            searchDone = 0;
            sweepDir = 0;
            sweepFrames = 0;
            if (in.coverage >= p.closeCoverage) near = true;
            else if (in.coverage < p.closeCoverage * NEAR_HYST) near = false;
        } else {
            lostStreak++;
        }

        // 放棄之後唔再郁（Center 會關 walkFollow；呢度兜底）。
        if (gaveUp) return out(NONE, 1f);

        // 跌倒保護：非 OK 唔發任何新指令（行緊步態就收步）；返到 OK 後多等 POST_FALL_SETTLE_MS。
        if (p.fallGuard) {
            if (in.fall == FALL_DOWN && prevFall != FALL_DOWN) {
                totalFalls++;
                fallTimes[fallRing] = now;
                fallRing = (fallRing + 1) % fallTimes.length;
                int recent = 0;
                for (long t : fallTimes) {
                    if (t != Long.MIN_VALUE && now - t <= FALL_WINDOW_MS) recent++;
                }
                if (recent >= p.maxFalls) {
                    gaveUp = true;
                    prevFall = in.fall;
                    clearPending();
                    return out(GIVE_UP, 1f);
                }
            }
            boolean wasNotOk = prevFall != FALL_OK;
            prevFall = in.fall;
            if (in.fall != FALL_OK) {
                lastBusyMs = now;
                clearPending();
                if (in.walkState == WALK_FWD || in.walkState == WALK_BACK) return out(RELEASE, 1f);
                return out(NONE, 1f);
            }
            if (wasNotOk) {
                // 剛剛返正常：延長 settle，等機身同頭穩定先繼續。
                lastBusyMs = now + (POST_FALL_SETTLE_MS - SETTLE_MS);
                clearPending();
            }
        }

        // 收步中／其他動作播緊：唔決策，記低忙碌時間（之後先有 settle）。
        if (in.otherPlaying || in.walkState == WALK_FINISHING) {
            lastBusyMs = now;
            clearPending();
            return out(NONE, 1f);
        }
        if (in.walkState == WALK_FWD || in.walkState == WALK_BACK) {
            lastBusyMs = now;
            return walking(in, p, now, a);
        }

        // 閒置：步後安定＋出手後保護期。
        if (now - lastBusyMs < SETTLE_MS || now - lastCmdMs < CMD_GUARD_MS) {
            clearPending();
            return out(NONE, 1f);
        }

        String raw = NONE;
        if (!in.found) {
            if (p.searchSteps > 0 && searchDone < p.searchSteps
                    && lostStreak >= SEARCH_LOST_FRAMES && lastSide != 0) {
                raw = lastSide < 0 ? TURN_L : TURN_R;
            }
        } else if (a > p.bigTurnDb) {
            raw = b < 0f ? TURN_L_BIG : TURN_R_BIG;
        } else if (a > p.turnDb) {
            raw = b < 0f ? TURN_L : TURN_R;
        } else if (p.sideDb > 0f && in.coverage >= p.sideMinCoverage && a > p.bigSideDb) {
            raw = b < 0f ? SIDE_L_BIG : SIDE_R_BIG;
        } else if (p.sideDb > 0f && in.coverage >= p.sideMinCoverage && a > p.sideDb) {
            raw = b < 0f ? SIDE_L : SIDE_R;
        } else if (p.backCoverage > 0f && in.coverage >= p.backCoverage) {
            raw = p.gait ? HOLD_BACK : STEP_BACK;
        } else if (!near) {
            raw = p.gait ? HOLD_FWD : STEP_FWD;
        }

        if (NONE.equals(raw)) {
            clearPending();
            return out(NONE, 1f);
        }
        // 確認：同一「家族」（大／細轉、大／細橫移視為同一）連續 CONFIRM_FRAMES 幀先出手。
        String key = family(raw);
        if (key.equals(pendKey)) pendCnt++;
        else { pendKey = key; pendCnt = 1; }
        if (pendCnt < CONFIRM_FRAMES) return out(NONE, 1f);

        clearPending();
        lastCmdMs = now;
        if (!in.found) searchDone++;
        float speed = 1f;
        if (HOLD_FWD.equals(raw)) {
            walkStartMs = now;
            speed = fwdSpeed(in.coverage, p);
        } else if (HOLD_BACK.equals(raw)) {
            walkStartMs = now;
        }
        return out(raw, speed);
    }

    /** 步態行緊（前進／後退）：續租或者放手。 */
    private Command walking(Input in, Params p, long now, float a) {
        boolean back = in.walkState == WALK_BACK;
        if (!in.found) {
            pendRelease = 0;
            if (lostStreak >= LOST_RELEASE_FRAMES) return out(RELEASE, 1f);
            return hold(back, 1f);
        }
        if (!back) {
            if (in.coverage >= p.closeCoverage) return out(RELEASE, 1f); // 夠近：即放，唔等確認
        } else if (p.backCoverage <= 0f || in.coverage < p.backCoverage * BACK_HYST) {
            return out(RELEASE, 1f);
        }
        boolean grace = now - walkStartMs < GAIT_GRACE_MS;
        if (!grace && a > p.turnDb) {
            if (++pendRelease >= CONFIRM_FRAMES) return out(RELEASE, 1f);
            return hold(back, back ? 1f : fwdSpeed(in.coverage, p));
        }
        pendRelease = 0;
        return hold(back, back ? 1f : fwdSpeed(in.coverage, p));
    }

    private Command hold(boolean back, float speed) {
        return out(back ? HOLD_BACK : HOLD_FWD, speed);
    }

    /** 前進速度：遠（coverage 小）用 walkSpeed，近降到 1.0。 */
    private static float fwdSpeed(float coverage, Params p) {
        if (p.walkSpeed <= 1f || p.closeCoverage <= 0f) return 1f;
        float far = 1f - coverage / p.closeCoverage;
        if (far < 0f) far = 0f;
        if (far > 1f) far = 1f;
        return 1f + (p.walkSpeed - 1f) * far;
    }

    private static String family(String raw) {
        return raw.endsWith("_big") ? raw.substring(0, raw.length() - 4) : raw;
    }

    private void clearPending() {
        pendKey = "";
        pendCnt = 0;
        pendRelease = 0;
    }

    private Command out(String type, float speed) {
        Command c = new Command(type);
        c.speed = speed;
        if (!NONE.equals(type)) lastType = type; // status 只記最後一個真命令
        return c;
    }
}
