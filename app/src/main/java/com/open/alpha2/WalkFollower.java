package com.open.alpha2;

import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * 追蹤「行路跟」共用執行層：{@link ColorTrackCenter}（顏色）同 {@link FaceTrackCenter}（人臉）
 * 各自偵測，每個偵測幀調一次 {@link #update}，其餘（頭追蹤、頭位置估計、頭搜索、規劃、
 * WalkCenter 連續步態／.ubx 執行、行路參數同狀態輸出）全部喺度，兩邊行為一致。
 *
 * <p>決策本體喺純 Java 嘅 {@link ColorTrackPlanner}（名保留，已唔限於顏色）；
 * 本類負責量度播放狀態、發舵機、執行命令。
 *
 * <h3>頭位置模型</h3>
 * 每個步態／.ubx 動作每格都命令頭 19/20 去 home，所以起步即當頭喺 {@link #HOME_PULL_MS} 內返 home，
 * 行路期間唔發頭指令（會同動作搶）。相機幀係頭仲行緊嗰陣拍嘅，dx 係相對「實際」頭位置，
 * 所以用「命令值＋servo 行進時間」線性插值估計實際位置，再折算成身體方位。
 *
 * <h3>跌倒保護</h3>
 * 行路跟開住（fallRecover，預設 true）就經 {@link FallMonitor} 臨時確保跌倒看門狗開住
 *（唔寫偏好設定，結束即還原），並每幀讀佢嘅狀態：未武裝（未見過企直）／睇唔到加速度計就唔行；
 * 跌低、起身中即收步並停晒所有指令（頭亦唔郁），由 {@link FallRecoveryCenter} 播 BackStand／FrontStand
 * 起身；起身企穩後多等 {@link ColorTrackPlanner#POST_FALL_SETTLE_MS} 自動繼續跟；
 * {@link ColorTrackPlanner#FALL_WINDOW_MS} 內跌夠 maxFalls 次就放棄（關 walkFollow，要人手重開）。
 *
 * <h3>距離代理（coverage）</h3>
 * 由 caller 提供，須「越近越大、約同距離平方成反比」：顏色用色塊／全圖；人臉用估算臉框面積。
 * 兩者尺度唔同，所以 closeCoverage／sideMinCoverage 預設值由 caller 經建構子傳入。
 *
 * <p>線程：update 由 caller 嘅偵測線程調（單線程）；config／start／stop 由 HTTP 線程調——
 * 參數全部 volatile，planner 自帶 synchronized。
 */
public final class WalkFollower {
    private static final String TAG = "WalkFollower";

    public static final int PAN_ID = 19;
    public static final int TILT_ID = 20;
    public static final int PAN_MIN = 75;
    public static final int PAN_MAX = 165;
    public static final int PAN_HOME = 120;
    public static final int TILT_MIN = 105;
    public static final int TILT_MAX = 155;
    public static final int TILT_HOME = 120;

    public static final boolean DEFAULT_WALK_FOLLOW = false;
    public static final float DEFAULT_TURN_DB = 0.35f;
    public static final String DEFAULT_WALK_FWD = "1508999860568"; // 前進
    public static final String DEFAULT_WALK_LEFT = "1464835936047"; // 左轉
    public static final String DEFAULT_WALK_RIGHT = "1464835936041"; // 右轉
    /** 大轉（3.0／3.3 s，擺幅較大；每步轉幾多度未量，轉得細過普通轉可用 walkLeftBig／walkRightBig 對調）。 */
    public static final String DEFAULT_WALK_LEFT_BIG = "1508999477476"; // 向左轉
    public static final String DEFAULT_WALK_RIGHT_BIG = "1508999731737"; // 右轉
    /** 橫移（右移 40／左移 46，同 GamepadCenter 手掣左右一致；一步 2.75 s）。 */
    public static final String DEFAULT_WALK_SIDE_LEFT = "1464835936046"; // 左移
    public static final String DEFAULT_WALK_SIDE_RIGHT = "1464835936040"; // 右移
    /** 大橫移：同橫移同一個姿勢連續重複 3 次（4.85／4.55 s）。 */
    public static final String DEFAULT_WALK_SIDE_LEFT_BIG = "1508999547240"; // 向左移動
    public static final String DEFAULT_WALK_SIDE_RIGHT_BIG = "1508999801028"; // 右移
    /** ubx 引擎後退（16.65 s；gait 引擎用 walk_back.json 連續步態，唔用呢個）。 */
    public static final String DEFAULT_WALK_BACK = "1510818256622"; // 後退
    /** 行路引擎：gait＝WalkCenter 連續步態做前進後退（可隨時平滑收步）；ubx＝舊式播一步 .ubx（13 s 盲行）。 */
    public static final String DEFAULT_WALK_ENGINE = "gait";
    /** 橫移死區（bearing 超過先橫移，須細過 turnDb 先有效；0＝關閉橫移）。 */
    public static final float DEFAULT_SIDE_DB = 0.15f;
    /** 大橫移死區（須 >= sideDb 同細過 turnDb 先有效）。 */
    public static final float DEFAULT_BIG_SIDE_DB = 0.25f;
    /** 大轉死區（bearing 單位，1＝半個鏡頭視角；頭偏移計埋所以可以大過 1）。 */
    public static final float DEFAULT_BIG_TURN_DB = 0.9f;
    /** 後退覆蓋率（0＝唔後退；開咗會自動校正到 >= closeCoverage×1.3）。 */
    public static final float DEFAULT_BACK_COVERAGE = 0f;
    /** 鏡頭水平半視角（度；未量，假設 30°，且假設 1 舵機單位≈1°——實測後請校正）。 */
    public static final float DEFAULT_HALF_FOV_DEG = 30f;
    /** gait 前進最高速倍率（遠時用，近時降到 1.0）；預設 1.0＝恆速。 */
    public static final float DEFAULT_WALK_SPEED = 1.0f;
    /** 掃唔到時身體朝最後見到方向轉幾多步（0＝唔搜，預設；盲轉有風險故 opt-in）。 */
    public static final int DEFAULT_SEARCH_STEPS = 0;
    /** 掃唔到時頭朝最後見到方向掃（只喺行路跟＋頭追蹤開住時有效；最多 50 幀然後回 home）。 */
    public static final boolean DEFAULT_HEAD_SEARCH = true;
    /** 行路跟期間確保跌倒看門狗開住，並按佢嘅狀態暫停／繼續（false＝唔保護，跌低都照發命令）。 */
    public static final boolean DEFAULT_FALL_RECOVER = true;
    /** 2 分鐘內跌夠幾多次就放棄行路跟（1-10）。 */
    public static final int DEFAULT_MAX_FALLS = 3;
    /** 步態／.ubx 起步後頭被拉返 home 嘅時間（頭位置估計用）。 */
    private static final int HOME_PULL_MS = 1000;
    /** gait 行緊時偵測間隔上限（須細過 WalkCenter 租約 1500 ms，否則每次都過期＝起停起停）。 */
    public static final int MAX_WALK_INTERVAL_MS = 500;
    /** 舵機指令最小間隔 ms：10fps 下唔好每幀 hammer 胸板串口（見 UbxApi 連讀間隔註解）。 */
    private static final long SERVO_MIN_GAP_MS = 150;

    private final UbxApi ubxApi;
    private final ColorTrackPlanner planner = new ColorTrackPlanner();
    private volatile ActionDirect actionDirect;
    private volatile WalkCenter walkCenter;

    // ── 行路參數（volatile，HTTP 線程寫、偵測線程讀）──
    private volatile boolean walkFollow = DEFAULT_WALK_FOLLOW;
    private volatile float turnDb = DEFAULT_TURN_DB;
    private volatile float closeCoverage;
    private volatile float sideMinCoverage;
    private volatile String walkFwd = DEFAULT_WALK_FWD;
    private volatile String walkLeft = DEFAULT_WALK_LEFT;
    private volatile String walkRight = DEFAULT_WALK_RIGHT;
    private volatile String walkLeftBig = DEFAULT_WALK_LEFT_BIG;
    private volatile String walkRightBig = DEFAULT_WALK_RIGHT_BIG;
    private volatile String walkSideLeft = DEFAULT_WALK_SIDE_LEFT;
    private volatile String walkSideRight = DEFAULT_WALK_SIDE_RIGHT;
    private volatile String walkSideLeftBig = DEFAULT_WALK_SIDE_LEFT_BIG;
    private volatile String walkSideRightBig = DEFAULT_WALK_SIDE_RIGHT_BIG;
    private volatile String walkBack = DEFAULT_WALK_BACK;
    private volatile String walkEngine = DEFAULT_WALK_ENGINE;
    private volatile float sideDb = DEFAULT_SIDE_DB;
    private volatile float bigSideDb = DEFAULT_BIG_SIDE_DB;
    private volatile float bigTurnDb = DEFAULT_BIG_TURN_DB;
    private volatile float backCoverage = DEFAULT_BACK_COVERAGE;
    private volatile float halfFovDeg = DEFAULT_HALF_FOV_DEG;
    private volatile float walkSpeed = DEFAULT_WALK_SPEED;
    private volatile int searchSteps = DEFAULT_SEARCH_STEPS;
    private volatile boolean headSearch = DEFAULT_HEAD_SEARCH;
    private volatile boolean fallRecover = DEFAULT_FALL_RECOVER;
    private volatile int maxFalls = DEFAULT_MAX_FALLS;

    // ── 跌倒保護狀態 ──
    private volatile FallMonitor fallMonitor;
    private final Object fallLock = new Object();
    /** Center 運行中（onStart～onStop）。 */
    private volatile boolean active = false;
    /** 已向 FallMonitor acquire（要對應 release）。 */
    private boolean fallHeld = false;
    private volatile String fallError = "";
    private volatile String fallStop = "";
    private int prevFall = ColorTrackPlanner.FALL_OK;

    // ── 狀態 ──
    private volatile int curPan = PAN_HOME;
    private volatile int curTilt = TILT_HOME;
    /** 頭 yaw 位置估計：命令值 curPan＋servo 行進時間（由 panFrom 線性行去 curPan，歷時 panMoveMs）。 */
    private volatile double panFrom = PAN_HOME;
    private volatile long panSentMs = 0;
    private volatile int panMoveMs = 400;
    private volatile long lastServoSendMs = 0;
    private volatile long moves = 0;
    private volatile long lastMoveMs = 0;
    private volatile String lastWalk = "";
    private volatile long walkMoves = 0;
    private volatile String lastDecision = "";
    /** 最近一幀嘅步態狀態（ColorTrackPlanner.WALK_*）；walkingNow＝前進／後退行緊（未收步）。 */
    private volatile int walkStateNow = ColorTrackPlanner.WALK_NONE;
    private volatile boolean walkingNow = false;
    /** update() 同 onStop／onShutdown／walkFollow 切換互斥：stop 返回後唔會有在途一幀再起步。 */
    private final Object updateLock = new Object();

    /**
     * @param closeCoverage   「夠近」覆蓋率預設（顏色 0.12；人臉見 FaceTrackCenter）
     * @param sideMinCoverage 橫移最低覆蓋率預設
     */
    public WalkFollower(UbxApi ubxApi, float closeCoverage, float sideMinCoverage) {
        this.ubxApi = ubxApi;
        this.closeCoverage = closeCoverage;
        this.sideMinCoverage = sideMinCoverage;
    }

    public void setActionDirect(ActionDirect actionDirect) { this.actionDirect = actionDirect; }

    /** MainActivity 接線用：後補 WalkCenter（比 Center 起得遲；未注入時 gait 引擎退返 ubx 引擎）。 */
    public void setWalkCenter(WalkCenter walkCenter) { this.walkCenter = walkCenter; }

    /** MainActivity 接線用：後補跌倒看門狗（起得比 Center 遲；未注入且 fallRecover 開住＝唔識偵測跌倒，唔行）。 */
    public void setFallMonitor(FallMonitor fallMonitor) {
        synchronized (fallLock) {
            if (fallHeld && this.fallMonitor != null) {
                try { this.fallMonitor.release(); } catch (Throwable ignored) {}
                fallHeld = false;
            }
            this.fallMonitor = fallMonitor;
        }
        syncFallHold();
    }

    public boolean isFollow() { return walkFollow; }
    public int curPan() { return curPan; }
    public int curTilt() { return curTilt; }
    public long moves() { return moves; }
    public long lastMoveMs() { return lastMoveMs; }
    public boolean walkingNow() { return walkingNow; }

    /** 當前生效間隔：行路 gait 行緊要靠每幀續租（租約 1500 ms），間隔過慢會每次過期＝起停起停。 */
    public int clampIntervalMs(int base) {
        return (walkingNow && base > MAX_WALK_INTERVAL_MS) ? MAX_WALK_INTERVAL_MS : base;
    }

    // ───────────────────────── config ─────────────────────────

    /** 行路動作名驗收：非空、≤64 字、唔准帶路徑（/ \ ..）；唔合格即保留現值。 */
    private static String optionalActionName(Map<String, String> query, String key, String current) {
        if (query == null) return current;
        String v = query.get(key);
        if (v == null || v.isEmpty()) return current;
        v = v.trim();
        if (v.isEmpty() || v.length() > 64 || v.contains("/") || v.contains("\\") || v.contains("..")) {
            return current;
        }
        return v;
    }

    private static boolean has(Map<String, String> q, String k) {
        return q.containsKey(k) && q.get(k) != null && !q.get(k).isEmpty();
    }

    /** 讀行路相關可選參數並套用（缺席即保留現值；非法值由 ApiValidator 拋 IllegalArgumentException）。 */
    public void applyConfig(Map<String, String> query) {
        if (query == null) return;
        // 兩段式：先將所有參數驗證／解析落局部變量（任何一個非法即拋，現值一個都唔改），
        // 全部過晒先一次過套用——之前逐個賦值，後面嘅參數非法會令前面嘅已經生效（半套用）。
        final float nTurnDb = ApiValidator.optionalFloatRange(query, "turnDb", 0.0, 0.8, turnDb);
        final float nCloseCoverage = ApiValidator.optionalFloatRange(query, "closeCoverage", 0.02, 0.6, closeCoverage);
        final String nWalkFwd = optionalActionName(query, "walkFwd", walkFwd);
        final String nWalkLeft = optionalActionName(query, "walkLeft", walkLeft);
        final String nWalkRight = optionalActionName(query, "walkRight", walkRight);
        final String nWalkLeftBig = optionalActionName(query, "walkLeftBig", walkLeftBig);
        final String nWalkRightBig = optionalActionName(query, "walkRightBig", walkRightBig);
        final String nWalkSideLeft = optionalActionName(query, "walkSideLeft", walkSideLeft);
        final String nWalkSideRight = optionalActionName(query, "walkSideRight", walkSideRight);
        final String nWalkSideLeftBig = optionalActionName(query, "walkSideLeftBig", walkSideLeftBig);
        final String nWalkSideRightBig = optionalActionName(query, "walkSideRightBig", walkSideRightBig);
        final String nWalkBack = optionalActionName(query, "walkBack", walkBack);
        final String nWalkEngine = ApiValidator.optionalEnum(query, "walkEngine", new String[] {"gait", "ubx"}, walkEngine);
        final float nSideDb = ApiValidator.optionalFloatRange(query, "sideDb", 0.0, 0.8, sideDb);
        final float nBigSideDb = ApiValidator.optionalFloatRange(query, "bigSideDb", 0.0, 0.8, bigSideDb);
        final float nSideMinCoverage = ApiValidator.optionalFloatRange(query, "sideMinCoverage", 0.0, 0.6, sideMinCoverage);
        final float nBigTurnDb = ApiValidator.optionalFloatRange(query, "bigTurnDb", 0.1, 2.0, bigTurnDb);
        final float nBackCoverage = ApiValidator.optionalFloatRange(query, "backCoverage", 0.0, 0.9, backCoverage);
        final float nHalfFovDeg = ApiValidator.optionalFloatRange(query, "halfFovDeg", 10.0, 60.0, halfFovDeg);
        final float nWalkSpeed = ApiValidator.optionalFloatRange(query, "walkSpeed", 1.0, 2.0, walkSpeed);
        final int nSearchSteps = ApiValidator.optionalIntRange(query, "searchSteps", 0, 12, searchSteps);
        final boolean nHeadSearch = has(query, "headSearch")
                ? ApiValidator.optionalBoolean(query, "headSearch", headSearch) : headSearch;
        final boolean nFallRecover = has(query, "fallRecover")
                ? ApiValidator.optionalBoolean(query, "fallRecover", fallRecover) : fallRecover;
        final int nMaxFalls = ApiValidator.optionalIntRange(query, "maxFalls", 1, 10, maxFalls);
        final boolean hasWalkFollow = has(query, "walkFollow");
        final boolean nWalkFollow = hasWalkFollow
                ? ApiValidator.optionalBoolean(query, "walkFollow", walkFollow) : walkFollow;

        turnDb = nTurnDb;
        closeCoverage = nCloseCoverage;
        walkFwd = nWalkFwd;
        walkLeft = nWalkLeft;
        walkRight = nWalkRight;
        walkLeftBig = nWalkLeftBig;
        walkRightBig = nWalkRightBig;
        walkSideLeft = nWalkSideLeft;
        walkSideRight = nWalkSideRight;
        walkSideLeftBig = nWalkSideLeftBig;
        walkSideRightBig = nWalkSideRightBig;
        walkBack = nWalkBack;
        walkEngine = nWalkEngine;
        sideDb = nSideDb;
        bigSideDb = nBigSideDb;
        sideMinCoverage = nSideMinCoverage;
        bigTurnDb = nBigTurnDb;
        backCoverage = nBackCoverage;
        halfFovDeg = nHalfFovDeg;
        walkSpeed = nWalkSpeed;
        searchSteps = nSearchSteps;
        headSearch = nHeadSearch;
        fallRecover = nFallRecover;
        maxFalls = nMaxFalls;
        if (hasWalkFollow) {
            boolean was = walkFollow;
            // 同 update() 互斥：等在途一幀做完先切，之後嘅幀見到嘅已經係新狀態
            //（planner 亦唔係線程安全，reset 同 step 唔可以齊行）。
            synchronized (updateLock) {
                walkFollow = nWalkFollow;
                if (was != nWalkFollow) planner.reset();
                // 重新開（包括跌太多次放棄之後人手重開）：跌倒計數同放棄原因清零。
                if (!was && nWalkFollow) fallStop = "";
            }
            // 中途關行路跟：gait 行緊就平滑收步（唔使等租約過期）。
            if (was && !nWalkFollow) releaseGaitIfWalking();
        }
        syncFallHold();
    }

    /**
     * 行路跟＋fallRecover＋Center 運行中就向 FallMonitor acquire（臨時確保看門狗開住），否則 release。
     * 由 HTTP 線程（config／start／stop）或放棄時由偵測線程調；acquire 失敗只記 fallError
     *（之後 fallLevel 會係 NOT_READY，所以唔會行）。
     */
    private void syncFallHold() {
        synchronized (fallLock) {
            FallMonitor fm = fallMonitor;
            boolean want = active && walkFollow && fallRecover && fm != null;
            if (want && !fallHeld) {
                String err;
                try {
                    err = fm.acquire();
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                if (err == null) {
                    fallHeld = true;
                    fallError = "";
                } else {
                    fallError = err;
                }
            } else if (!want && fallHeld) {
                try { fm.release(); } catch (Throwable ignored) {}
                fallHeld = false;
            }
        }
    }

    // ───────────────────────── lifecycle ─────────────────────────

    /** Center.start：決策狀態清零；頭位置估計由現時命令值起步。 */
    public void onStart() {
        active = true;
        prevFall = ColorTrackPlanner.FALL_OK;
        planner.reset();
        lastDecision = "";
        walkStateNow = ColorTrackPlanner.WALK_NONE;
        walkingNow = false;
        panFrom = curPan;
        panSentMs = 0;
        lastWalk = "";
        syncFallHold();
    }

    /**
     * Center.stop：行路跟開住就收步——gait 行緊平滑收步（回企定，唔使蹲下站起）；
     * 播緊 .ubx 步就截停＋蹲下站起回穩（唔係部機會保持行路中途姿勢企喺度）。
     * 行路跟冇開時唔掂（例如手柄行緊）。
     */
    public void onStop() {
        // 先擋住之後嘅 update()（並等在途一幀做完）：否則 stop 完仲有一幀用舊狀態起步，
        // ubx 引擎會盲行 13 s。
        final boolean wasFollow = walkFollow;
        synchronized (updateLock) {
            active = false;
            planner.reset();
            // 行路跟只係單節有效：停咗即復位（status.config.walkFollow 亦會變 false），
            // 下次 start 要人手重新開，唔會一開波就因為上次忘記關而行。
            walkFollow = false;
        }
        walkingNow = false;
        walkStateNow = ColorTrackPlanner.WALK_NONE;
        // 跌低／起身中唔掂任何動作：stopActionWithRecovery 會截停起身動作＋蹲下站起，會破壞起身。
        FallMonitor fm = fallMonitor;
        boolean down = fallRecover && fm != null && fm.fallLevel() == ColorTrackPlanner.FALL_DOWN;
        if (wasFollow && !down) {
            WalkCenter wc = walkCenter;
            ActionDirect ad = actionDirect;
            try {
                if (wc != null && wc.isWalking()) {
                    wc.release();
                } else if (ad != null && ad.isPlaying()) {
                    ad.stopActionWithRecovery();
                }
            } catch (Throwable ignored) {}
        }
        syncFallHold();
    }

    /** Center.shutdown：gait 行緊就放手（租約亦會兜底）。 */
    public void onShutdown() {
        final boolean wasFollow = walkFollow;
        synchronized (updateLock) {
            active = false;
            walkFollow = false;
        }
        FallMonitor fm = fallMonitor;
        boolean down = fallRecover && fm != null && fm.fallLevel() == ColorTrackPlanner.FALL_DOWN;
        if (wasFollow && !down) releaseGaitIfWalking();
        syncFallHold();
    }

    /** gait 行緊就放手收步；唔掂普通 .ubx。best-effort。 */
    private void releaseGaitIfWalking() {
        WalkCenter wc = walkCenter;
        if (wc == null) return;
        try {
            if (wc.isWalking()) wc.release();
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── head model ─────────────────────────

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /** 頭 yaw 估計位置（見 panFrom 說明）。 */
    private double estimatedPan(long nowMs) {
        double f = (nowMs - panSentMs) / (double) Math.max(1, panMoveMs);
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        return panFrom + (curPan - panFrom) * f;
    }

    /** 記低頭 yaw 新命令（先固定估計起點，再切去新目標）。 */
    private void commandPan(int newPan, int moveMs, long nowMs) {
        panFrom = estimatedPan(nowMs);
        curPan = newPan;
        panSentMs = nowMs;
        panMoveMs = moveMs;
    }

    /** 步態／.ubx 每格都命令頭 19/20 去 home（≈120/120）：起步即當頭會喺 HOME_PULL_MS 內返 home。 */
    private void pullHeadHome(long nowMs) {
        panFrom = estimatedPan(nowMs);
        curPan = PAN_HOME;
        curTilt = TILT_HOME;
        panSentMs = nowMs;
        panMoveMs = HOME_PULL_MS;
    }

    // ───────────────────────── planner glue ─────────────────────────

    private ColorTrackPlanner.Params plannerParams(boolean gait) {
        ColorTrackPlanner.Params pp = new ColorTrackPlanner.Params();
        pp.turnDb = turnDb;
        pp.bigTurnDb = bigTurnDb;
        pp.sideDb = sideDb;
        pp.bigSideDb = bigSideDb;
        pp.sideMinCoverage = sideMinCoverage;
        pp.closeCoverage = closeCoverage;
        pp.backCoverage = backCoverage;
        pp.walkSpeed = walkSpeed;
        pp.gait = gait;
        pp.searchSteps = searchSteps;
        pp.fallGuard = fallRecover;
        pp.maxFalls = maxFalls;
        return pp;
    }

    private static String orElse(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /** 規劃器命令 → .ubx 檔（大轉／大橫移缺檔就退返普通版）。 */
    private String fileFor(String type) {
        switch (type) {
            case ColorTrackPlanner.STEP_FWD: return walkFwd;
            case ColorTrackPlanner.STEP_BACK: return walkBack;
            case ColorTrackPlanner.TURN_L: return walkLeft;
            case ColorTrackPlanner.TURN_R: return walkRight;
            case ColorTrackPlanner.TURN_L_BIG: return orElse(walkLeftBig, walkLeft);
            case ColorTrackPlanner.TURN_R_BIG: return orElse(walkRightBig, walkRight);
            case ColorTrackPlanner.SIDE_L: return walkSideLeft;
            case ColorTrackPlanner.SIDE_R: return walkSideRight;
            case ColorTrackPlanner.SIDE_L_BIG: return orElse(walkSideLeftBig, walkSideLeft);
            case ColorTrackPlanner.SIDE_R_BIG: return orElse(walkSideRightBig, walkSideRight);
            default: return null;
        }
    }

    /**
     * 執行規劃器命令。回 true＝真係起咗步／步態（頭會被拉返 home，本幀唔再掃頭）。
     * hold 每幀都叫（續租＋變速），只有由靜止起步先算「起咗」。
     */
    private boolean execute(ColorTrackPlanner.Command cmd, ActionDirect ad, WalkCenter wc, long nowMs) {
        String t = cmd.type;
        if (ColorTrackPlanner.NONE.equals(t)) return false;
        try {
            if (ColorTrackPlanner.RELEASE.equals(t)) {
                if (wc != null) wc.release();
                return false;
            }
            if (ColorTrackPlanner.HOLD_FWD.equals(t) || ColorTrackPlanner.HOLD_BACK.equals(t)) {
                if (wc == null) return false;
                boolean back = ColorTrackPlanner.HOLD_BACK.equals(t);
                boolean was = wc.isWalking(back);
                if (!wc.hold(cmd.speed, back)) {
                    Log.w(TAG, "gait hold failed: " + wc.lastError());
                    return false;
                }
                if (was) return false;
                lastWalk = back ? "walk:back" : "walk:fwd";
                walkMoves++;
                pullHeadHome(nowMs);
                return true;
            }
            String file = fileFor(t);
            if (file == null || ad == null) return false;
            if (ad.playActionDirect(file) == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                lastWalk = file;
                walkMoves++;
                pullHeadHome(nowMs);
                return true;
            }
            Log.w(TAG, "walk step not started: " + file);
        } catch (Throwable th) {
            Log.w(TAG, "walk command failed: " + t, th);
        }
        return false;
    }

    // ───────────────────────── per-frame update ─────────────────────────

    /**
     * 每個偵測幀調一次：量播放狀態 → 頭追（見到）／頭搜索（掃唔到）→ 行路跟（規劃＋執行）。
     * 頭偏移要喺本幀頭指令之前量（dx 係相對拍幀時嗰個頭位置）。
     *
     * @param found     呢幀有冇見到目標
     * @param dx        -1 左 … +1 右（見到先有意義）
     * @param dy        -1 上 … +1 下
     * @param coverage  距離代理（越近越大；掃唔到忽略）
     * @param headFollow／deadband／stepDeg／timeMs  caller 嘅頭追蹤設定
     */
    public void update(boolean found, float dx, float dy, float coverage,
            boolean headFollow, float deadband, int stepDeg, int timeMs) {
        synchronized (updateLock) {
            if (!active) return; // 已 stop／未 start：在途幀唔再出手（頭／步態都唔掂）
            updateLocked(found, dx, dy, coverage, headFollow, deadband, stepDeg, timeMs);
        }
    }

    private void updateLocked(boolean found, float dx, float dy, float coverage,
            boolean headFollow, float deadband, int stepDeg, int timeMs) {
        final long nowMs = System.currentTimeMillis();
        final ActionDirect ad = actionDirect;
        final WalkCenter wc = walkCenter;
        final boolean follow = walkFollow;
        final boolean gait = wc != null && "gait".equals(walkEngine);

        // ① 播放／步態狀態。ActionDirect.isPlaying() 其實係 ubxPlayer.isPlaying()，步態行緊都係 true，
        // 所以要用 WalkCenter 分返「步態」同「其他動作」；收步中（已 release）＝ FINISHING。
        int walkState = ColorTrackPlanner.WALK_NONE;
        boolean anyPlaying = false;
        if (follow) {
            if (wc != null) {
                try {
                    if (wc.isWalking(false)) walkState = ColorTrackPlanner.WALK_FWD;
                    else if (wc.isWalking(true)) walkState = ColorTrackPlanner.WALK_BACK;
                    else if (wc.isWalking()) walkState = ColorTrackPlanner.WALK_FINISHING;
                } catch (Throwable ignored) {}
            }
            if (ad != null) {
                try { anyPlaying = ad.isPlaying(); } catch (Throwable ignored) {}
            }
        }
        final boolean walking = walkState != ColorTrackPlanner.WALK_NONE;
        final boolean otherPlaying = anyPlaying && !walking;
        // 跌倒狀態：未接線／睇唔到＝NOT_READY（唔行）；跌低／起身中＝DOWN（停晒，等看門狗起身）。
        int fall = ColorTrackPlanner.FALL_OK;
        if (follow && fallRecover) {
            FallMonitor fm = fallMonitor;
            fall = (fm == null) ? ColorTrackPlanner.FALL_NOT_READY : fm.fallLevel();
        }
        final boolean down = fall == ColorTrackPlanner.FALL_DOWN;
        // 起身動作（BackStand／FrontStand）每格都命令頭去 home：返正常嗰刻當頭已經喺 home。
        if (prevFall == ColorTrackPlanner.FALL_DOWN && !down) pullHeadHome(nowMs);
        prevFall = fall;
        // 行路跟播緊任何動作／步態／跌低嗰陣唔發頭舵機：每格都命令頭去 home，cmd05 同佢搶會打架。
        final boolean headBusy = follow && (anyPlaying || walking || down);
        walkStateNow = walkState;
        walkingNow = walkState == ColorTrackPlanner.WALK_FWD || walkState == ColorTrackPlanner.WALK_BACK;

        // ② 頭偏移（折算成 dx 尺度）：先於本幀頭指令量。
        final float headOffset = ColorTrackLogic.headOffset(
                (float) estimatedPan(nowMs), PAN_HOME, halfFovDeg);

        boolean moved = false;
        final boolean servoDue = nowMs - lastServoSendMs >= SERVO_MIN_GAP_MS;

        // ③ 頭追：死區＋比例步進（追頭總開關關＝只偵測，頭不動）。
        if (found && headFollow) {
            int newPan = curPan;
            int newTilt = curTilt;
            float db = deadband;
            if (Math.abs(dx) > db) {
                float mag = (Math.abs(dx) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dx < 0) delta = -delta;
                newPan = clamp(curPan + delta, PAN_MIN, PAN_MAX);
            }
            if (Math.abs(dy) > db) {
                float mag = (Math.abs(dy) - db) / Math.max(0.001f, 1f - db);
                int delta = Math.max(1, Math.round(mag * stepDeg));
                if (dy < 0) delta = -delta;
                newTilt = clamp(curTilt + delta, TILT_MIN, TILT_MAX);
            }
            // 只有角度真變＋同上次發送隔夠 SERVO_MIN_GAP_MS 先發舵機（同 FaceTrackCenter 一致）。
            if (!headBusy && servoDue && newPan != curPan) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(PAN_ID, newPan, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    commandPan(newPan, timeMs, nowMs);
                    moved = true;
                }
            }
            if (!headBusy && servoDue && newTilt != curTilt) {
                if (ubxApi != null
                        && ubxApi.servoSendOneCode(TILT_ID, newTilt, timeMs)
                        == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    curTilt = newTilt;
                    moved = true;
                }
            }
        }

        // ④ 行路跟：規劃＋執行（決策全部喺 ColorTrackPlanner）。
        boolean started = false;
        if (follow) {
            ColorTrackPlanner.Input in = new ColorTrackPlanner.Input();
            in.found = found;
            in.dx = dx;
            in.headOffset = headOffset;
            in.coverage = found ? coverage : 0f;
            in.walkState = walkState;
            in.otherPlaying = otherPlaying;
            in.fall = fall;
            ColorTrackPlanner.Command cmd = planner.step(in, plannerParams(gait), nowMs);
            lastDecision = planner.lastCommand();
            if (ColorTrackPlanner.GIVE_UP.equals(cmd.type)) {
                // 跌太多次：放棄行路跟（唔再發任何命令；要人手重新開 walkFollow）。
                fallStop = "too many falls (" + planner.totalFalls() + " within "
                        + (ColorTrackPlanner.FALL_WINDOW_MS / 1000) + " s); walkFollow turned off";
                Log.w(TAG, fallStop);
                walkFollow = false;
                releaseGaitIfWalking();
                syncFallHold();
            } else {
                started = execute(cmd, ad, wc, nowMs);
            }
        }

        // ⑤ 頭搜索：每個步態／.ubx 動作都將頭拉返 home，大角度轉向後目標可能跌出視角；
        // 掃唔到就頭朝最後見到嘅方向掃（有限幀數，之後回 home）。淨係行路跟＋頭追蹤開住先做。
        if (!found && follow && headFollow && headSearch && !headBusy && !started
                && servoDue && ubxApi != null) {
            int np = planner.headSearchPan(curPan, Math.max(1, stepDeg / 2), PAN_MIN, PAN_MAX, PAN_HOME);
            if (np != curPan
                    && ubxApi.servoSendOneCode(PAN_ID, np, timeMs)
                    == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                commandPan(np, timeMs, nowMs);
                moved = true;
            }
        }

        if (moved) {
            moves++;
            lastMoveMs = nowMs;
            lastServoSendMs = nowMs;
        }
    }

    // ───────────────────────── status / config JSON ─────────────────────────

    private static String fmt3(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /**
     * status 頂層行路欄位（尾帶逗號，caller 之後接其他欄位）：
     * lastWalk, walkMoves, decision, walkState, engine, ubxPlaying。
     */
    public void appendStatusJson(StringBuilder sb) {
        sb.append("\"lastWalk\":\"").append(JsonUtil.esc(lastWalk)).append("\",");
        sb.append("\"walkMoves\":").append(walkMoves).append(",");
        sb.append("\"decision\":\"").append(JsonUtil.esc(lastDecision)).append("\",");
        String ws = walkStateNow == ColorTrackPlanner.WALK_FWD ? "fwd"
                : walkStateNow == ColorTrackPlanner.WALK_BACK ? "back"
                : walkStateNow == ColorTrackPlanner.WALK_FINISHING ? "finishing" : "idle";
        sb.append("\"walkState\":\"").append(ws).append("\",");
        sb.append("\"engine\":\"").append(walkCenter != null && "gait".equals(walkEngine) ? "gait" : "ubx").append("\",");
        FallMonitor fm = fallMonitor;
        String fallText = !fallRecover ? "off" : (fm == null ? "unavailable" : fm.fallState());
        sb.append("\"fall\":\"").append(JsonUtil.esc(fallText)).append("\",");
        sb.append("\"falls\":").append(planner.totalFalls()).append(",");
        sb.append("\"fallStop\":\"").append(JsonUtil.esc(fallStop)).append("\",");
        sb.append("\"fallError\":\"").append(JsonUtil.esc(fallError)).append("\",");
        ActionDirect ad = actionDirect;
        boolean ubxPlaying = false;
        if (ad != null) {
            try { ubxPlaying = ad.isPlaying(); } catch (Throwable ignored) {}
        }
        sb.append("\"ubxPlaying\":").append(ubxPlaying).append(",");
    }

    /** config 內行路欄位（頭尾冇逗號；caller 自己喺前面加逗號）。 */
    public void appendConfigJson(StringBuilder sb) {
        sb.append("\"walkFollow\":").append(walkFollow).append(",");
        sb.append("\"turnDb\":").append(fmt3(turnDb)).append(",");
        sb.append("\"closeCoverage\":").append(fmt3(closeCoverage)).append(",");
        sb.append("\"walkFwd\":\"").append(JsonUtil.esc(walkFwd)).append("\",");
        sb.append("\"walkLeft\":\"").append(JsonUtil.esc(walkLeft)).append("\",");
        sb.append("\"walkRight\":\"").append(JsonUtil.esc(walkRight)).append("\",");
        sb.append("\"walkLeftBig\":\"").append(JsonUtil.esc(walkLeftBig)).append("\",");
        sb.append("\"walkRightBig\":\"").append(JsonUtil.esc(walkRightBig)).append("\",");
        sb.append("\"walkSideLeft\":\"").append(JsonUtil.esc(walkSideLeft)).append("\",");
        sb.append("\"walkSideRight\":\"").append(JsonUtil.esc(walkSideRight)).append("\",");
        sb.append("\"walkSideLeftBig\":\"").append(JsonUtil.esc(walkSideLeftBig)).append("\",");
        sb.append("\"walkSideRightBig\":\"").append(JsonUtil.esc(walkSideRightBig)).append("\",");
        sb.append("\"walkBack\":\"").append(JsonUtil.esc(walkBack)).append("\",");
        sb.append("\"walkEngine\":\"").append(JsonUtil.esc(walkEngine)).append("\",");
        sb.append("\"sideDb\":").append(fmt3(sideDb)).append(",");
        sb.append("\"bigSideDb\":").append(fmt3(bigSideDb)).append(",");
        sb.append("\"sideMinCoverage\":").append(fmt3(sideMinCoverage)).append(",");
        sb.append("\"bigTurnDb\":").append(fmt3(bigTurnDb)).append(",");
        sb.append("\"backCoverage\":").append(fmt3(backCoverage)).append(",");
        sb.append("\"halfFovDeg\":").append(fmt3(halfFovDeg)).append(",");
        sb.append("\"walkSpeed\":").append(fmt3(walkSpeed)).append(",");
        sb.append("\"searchSteps\":").append(searchSteps).append(",");
        sb.append("\"fallRecover\":").append(fallRecover).append(",");
        sb.append("\"maxFalls\":").append(maxFalls).append(",");
        sb.append("\"headSearch\":").append(headSearch);
    }
}
