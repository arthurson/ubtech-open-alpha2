package com.open.alpha2;

/**
 * ColorTrackPlanner 單元測試＋閉環模擬 (零依賴，不用 JUnit)。
 *
 * 同 ColorTrackTest 一樣經 `scripts/test-apivalidator.py` 用純 javac/java 跑
 *（ColorTrackPlanner／ColorTrackLogic 只用 java.*）。
 *
 * 閉環模擬係「玩具機器人」：幾何（位置／朝向／頭 yaw）、相機（視角／覆蓋率）、
 * 步態租約／收步、.ubx 播放時長同每步位移都係假設數值，**唔係實測**。
 * 用途係揪邏輯 bug（抖動、盲行、撞標、播步同步態互撞），唔係預測真機表現；
 * 真機門檻（turnDb／sideDb／halfFovDeg）要自己量。
 */
public final class ColorTrackPlannerTest {
    private static int passed = 0;
    private static int failed = 0;

    private ColorTrackPlannerTest() {}

    private static void ok(boolean cond, String name) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eqs(String expected, String actual, String name) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    // ── helpers ──
    private static ColorTrackPlanner.Params params() {
        return new ColorTrackPlanner.Params();
    }

    private static ColorTrackPlanner.Input in(boolean found, float dx, float head, float cov, int walk, boolean other) {
        ColorTrackPlanner.Input i = new ColorTrackPlanner.Input();
        i.found = found;
        i.dx = dx;
        i.headOffset = head;
        i.coverage = cov;
        i.walkState = walk;
        i.otherPlaying = other;
        return i;
    }

    private static ColorTrackPlanner.Input idle(float dx, float head, float cov) {
        return in(true, dx, head, cov, ColorTrackPlanner.WALK_NONE, false);
    }

    private static final long T0 = 100000L;
    private static final long DT = 150L;

    /** 連續餵 n 幀同一輸入，回最後一幀命令類型。 */
    private static String feed(ColorTrackPlanner pl, ColorTrackPlanner.Input i, ColorTrackPlanner.Params p,
            long start, int n) {
        String last = "none";
        for (int k = 0; k < n; k++) {
            last = pl.step(i, p, start + k * DT).type;
        }
        return last;
    }

    public static void main(String[] args) {
        unitTests();
        fallUnitTests();
        simulationTests();
        fallSimulationTests();
        System.out.println("ColorTrackPlannerTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ═══════════════════ 單元測試 ═══════════════════
    private static void unitTests() {
        ColorTrackPlanner.Params p = params();

        // ① 身體方位：頭已向左 30°（headOffset=-1），色喺畫面中間 → 身體要轉左（原本淨睇 dx 會當 0）。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            ColorTrackPlanner.Input i = idle(0f, -1.0f, 0.06f);
            eqs("none", pl.step(i, p, T0).type, "bearing.headLeft.firstFrameConfirm");
            eqs("turn_left_big", pl.step(i, p, T0 + DT).type, "bearing.headLeft.big");
        }
        // 頭右 15°（+0.5）＋畫面偏右 0.1 ＝ 0.6 → 右轉（普通）。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("turn_right", feed(pl, idle(0.1f, 0.5f, 0.06f), p, T0, 2), "bearing.headRight.turn");
        }
        // 頭偏左＋畫面偏右互相抵消：身體已對正 → 唔轉，遠就行前。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl, idle(0.4f, -0.4f, 0.06f), p, T0, 2), "bearing.cancel.forward");
        }

        // ② 確認幀：單幀假 blob 唔出手；中間斷一幀重新計。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("none", pl.step(idle(0f, 0f, 0.05f), p, T0).type, "confirm.single");
            eqs("none", pl.step(idle(0.6f, 0f, 0.05f), p, T0 + DT).type, "confirm.changed.resets");
        }
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(idle(0f, 0f, 0.05f), p, T0);
            pl.step(idle(0.6f, 0f, 0.05f), p, T0 + DT); // 打斷
            eqs("none", pl.step(idle(0f, 0f, 0.05f), p, T0 + 2 * DT).type, "confirm.afterBreak.first");
            eqs("hold_fwd", pl.step(idle(0f, 0f, 0.05f), p, T0 + 3 * DT).type, "confirm.afterBreak.second");
        }

        // ③ 大／細轉同方向。
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            String small = feed(pl, idle(0.5f * sgn, 0f, 0.05f), p, T0, 2);
            eqs(sgn < 0 ? "turn_left" : "turn_right", small, "tier.turn" + sgn);
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            String big = feed(pl2, idle(1.0f * sgn, 0f, 0.05f), p, T0, 2);
            eqs(sgn < 0 ? "turn_left_big" : "turn_right_big", big, "tier.turnBig" + sgn);
        }
        // 大細轉之間跳動（邊界）都算同一家族，照樣 2 幀確認出手。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(idle(0.88f, 0f, 0.05f), p, T0);
            String t = pl.step(idle(0.92f, 0f, 0.05f), p, T0 + DT).type;
            eqs("turn_right_big", t, "tier.familyConfirm");
        }

        // ④ 橫移：細帶、大帶、太遠唔橫移、sideDb=0 關閉、邊界 inclusive。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("side_right", feed(pl, idle(0.20f, 0f, 0.06f), p, T0, 2), "side.small");
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            eqs("side_left_big", feed(pl2, idle(-0.30f, 0f, 0.06f), p, T0, 2), "side.big");
            ColorTrackPlanner pl3 = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl3, idle(0.30f, 0f, 0.02f), p, T0, 2), "side.tooFarForward");
            ColorTrackPlanner.Params off = params();
            off.sideDb = 0f;
            ColorTrackPlanner pl4 = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl4, idle(0.30f, 0f, 0.06f), off, T0, 2), "side.disabled");
            ColorTrackPlanner pl5 = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl5, idle(0.15f, 0f, 0.06f), p, T0, 2), "side.edgeSideDb");
            ColorTrackPlanner pl6 = new ColorTrackPlanner();
            eqs("side_right_big", feed(pl6, idle(0.35f, 0f, 0.06f), p, T0, 2), "side.edgeTurnDb");
            // 夠近（coverage 大過 closeCoverage）細偏差照橫移，唔係企定。
            ColorTrackPlanner pl7 = new ColorTrackPlanner();
            eqs("side_right", feed(pl7, idle(0.2f, 0f, 0.30f), p, T0, 2), "side.nearStillSide");
            // 橫移門檻自動校正：bigSideDb<sideDb 當同值。
            ColorTrackPlanner.Params bad = params();
            bad.sideDb = 0.3f;
            bad.bigSideDb = 0.1f;
            ColorTrackPlanner pl8 = new ColorTrackPlanner();
            eqs("side_right_big", feed(pl8, idle(0.32f, 0f, 0.06f), bad, T0, 2), "side.normalizeBig");
        }

        // ⑤ 近距離滯後：>=0.12 企定；0.10（仍喺 0.09-0.12 帶內）維持企定；<0.09 先再前進。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("none", feed(pl, idle(0f, 0f, 0.13f), p, T0, 3), "near.close.none");
            eqs("none", feed(pl, idle(0f, 0f, 0.10f), p, T0 + 5 * DT, 3), "near.hyst.stillNear");
            eqs("hold_fwd", feed(pl, idle(0f, 0f, 0.08f), p, T0 + 10 * DT, 2), "near.hyst.resume");
            // 從來冇近過：0.10 係「未夠近」→ 前進。
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl2, idle(0f, 0f, 0.10f), p, T0, 2), "near.neverNear.forward");
        }

        // ⑥ 後退：預設關；開咗（gait／ubx）；門檻自動校正。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("none", feed(pl, idle(0f, 0f, 0.5f), p, T0, 3), "back.disabledByDefault");
            ColorTrackPlanner.Params bp = params();
            bp.backCoverage = 0.4f;
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            eqs("hold_back", feed(pl2, idle(0f, 0f, 0.5f), bp, T0, 2), "back.gait");
            ColorTrackPlanner.Params bu = params();
            bu.backCoverage = 0.4f;
            bu.gait = false;
            ColorTrackPlanner pl3 = new ColorTrackPlanner();
            eqs("step_back", feed(pl3, idle(0f, 0f, 0.5f), bu, T0, 2), "back.ubx");
            ColorTrackPlanner.Params bl = params();
            bl.backCoverage = 0.13f; // 低過 close×1.3 → 校正成 0.156
            ColorTrackPlanner pl4 = new ColorTrackPlanner();
            eqs("none", feed(pl4, idle(0f, 0f, 0.14f), bl, T0, 3), "back.normalizedAboveClose");
        }

        // ⑦ ubx 引擎：前進係 step_fwd。
        {
            ColorTrackPlanner.Params u = params();
            u.gait = false;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("step_fwd", feed(pl, idle(0f, 0f, 0.05f), u, T0, 2), "ubx.forwardStep");
        }

        // ⑧ 步後安定＋出手保護＋忙碌：播緊／收步中唔決策；完結後 300ms 內唔決策。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            long t = T0;
            eqs("none", pl.step(in(true, 0.5f, 0f, 0.05f, ColorTrackPlanner.WALK_NONE, true), p, t).type, "busy.otherPlaying");
            eqs("none", pl.step(in(true, 0.5f, 0f, 0.05f, ColorTrackPlanner.WALK_FINISHING, false), p, t + 100).type, "busy.finishing");
            // 剛完結：settle 內（<300ms）連續餵都 none。
            eqs("none", feed(pl, idle(0.5f, 0f, 0.05f), p, t + 200, 2), "settle.within");
            // 過咗 settle：先累計確認幀。
            long t2 = t + 100 + 300 + 1;
            eqs("none", pl.step(idle(0.5f, 0f, 0.05f), p, t2).type, "settle.after.first");
            eqs("turn_right", pl.step(idle(0.5f, 0f, 0.05f), p, t2 + DT).type, "settle.after.second");
            // 出手後 400ms 內保護：紙面狀態仲係閒置都唔再出命令。
            eqs("none", feed(pl, idle(0.5f, 0f, 0.05f), p, t2 + DT + 50, 2), "cmdGuard.blocks");
        }

        // ⑨ gait 行緊：續租、夠近即放、掃唔到放、偏出確認放、起步寬限、速度。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            // 先經閒置出手，建立 walkStartMs。
            long t = T0;
            feed(pl, idle(0f, 0f, 0.05f), p, t, 2); // hold_fwd issued at t+DT
            long tw = t + 2 * DT; // 之後 walking 狀態
            ColorTrackPlanner.Input w = in(true, 0f, 0f, 0.05f, ColorTrackPlanner.WALK_FWD, false);
            eqs("hold_fwd", pl.step(w, p, tw).type, "gait.hold.continues");
            // 夠近：即放（唔等確認）。
            eqs("release", pl.step(in(true, 0f, 0f, 0.12f, ColorTrackPlanner.WALK_FWD, false), p, tw + DT).type, "gait.close.release");
        }
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            feed(pl, idle(0f, 0f, 0.05f), p, T0, 2);
            long tw = T0 + 2 * DT;
            // 起步寬限 1200ms 內：就算偏出都唔因方位放。
            ColorTrackPlanner.Input off = in(true, 0.6f, 0f, 0.05f, ColorTrackPlanner.WALK_FWD, false);
            eqs("hold_fwd", pl.step(off, p, tw).type, "gait.grace.ignoresBearing1");
            eqs("hold_fwd", pl.step(off, p, tw + DT).type, "gait.grace.ignoresBearing2");
            // 寬限後：連續 2 幀偏出先放。
            long ta = T0 + DT + 1300;
            eqs("hold_fwd", pl.step(off, p, ta).type, "gait.offCenter.firstFrame");
            eqs("release", pl.step(off, p, ta + DT).type, "gait.offCenter.secondFrame");
            // 中間回正一幀：重新計。
            ColorTrackPlanner.Input ctr = in(true, 0f, 0f, 0.05f, ColorTrackPlanner.WALK_FWD, false);
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            feed(pl2, idle(0f, 0f, 0.05f), p, T0, 2);
            pl2.step(off, p, ta);
            pl2.step(ctr, p, ta + DT);
            eqs("hold_fwd", pl2.step(off, p, ta + 2 * DT).type, "gait.offCenter.resetByRecenter");
        }
        {
            // 掃唔到：前 2 幀照續租（保租約），第 3 幀放。
            ColorTrackPlanner pl = new ColorTrackPlanner();
            feed(pl, idle(0f, 0f, 0.05f), p, T0, 2);
            long tw = T0 + 2 * DT;
            ColorTrackPlanner.Input lost = in(false, 0f, 0f, 0f, ColorTrackPlanner.WALK_FWD, false);
            eqs("hold_fwd", pl.step(lost, p, tw).type, "gait.lost.1");
            eqs("hold_fwd", pl.step(lost, p, tw + DT).type, "gait.lost.2");
            eqs("release", pl.step(lost, p, tw + 2 * DT).type, "gait.lost.3.release");
        }
        {
            // 後退行緊：coverage 跌穿 back×0.85 即放。
            ColorTrackPlanner.Params bp = params();
            bp.backCoverage = 0.4f;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            feed(pl, idle(0f, 0f, 0.5f), bp, T0, 2);
            long tw = T0 + 2 * DT;
            eqs("hold_back", pl.step(in(true, 0f, 0f, 0.45f, ColorTrackPlanner.WALK_BACK, false), bp, tw).type, "gait.back.hold");
            eqs("release", pl.step(in(true, 0f, 0f, 0.33f, ColorTrackPlanner.WALK_BACK, false), bp, tw + DT).type, "gait.back.release");
        }
        {
            // 速度：遠＝walkSpeed、近＝1.0、中間線性；walkSpeed=1 永遠 1。
            ColorTrackPlanner.Params sp = params();
            sp.walkSpeed = 1.3f;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            feed(pl, idle(0f, 0f, 0.01f), sp, T0, 1);
            ColorTrackPlanner.Command far = pl.step(idle(0f, 0f, 0.0f), sp, T0 + DT);
            ok(far.type.equals("hold_fwd") && Math.abs(far.speed - 1.3f) < 1e-4f, "speed.far=max");
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            feed(pl2, idle(0f, 0f, 0.06f), sp, T0, 1);
            ColorTrackPlanner.Command mid = pl2.step(idle(0f, 0f, 0.06f), sp, T0 + DT);
            ok(mid.type.equals("hold_fwd") && Math.abs(mid.speed - 1.15f) < 1e-4f, "speed.mid=linear");
            ColorTrackPlanner pl3 = new ColorTrackPlanner();
            ColorTrackPlanner.Command dflt = null;
            pl3.step(idle(0f, 0f, 0.0f), params(), T0);
            dflt = pl3.step(idle(0f, 0f, 0.0f), params(), T0 + DT);
            ok(dflt.type.equals("hold_fwd") && Math.abs(dflt.speed - 1.0f) < 1e-4f, "speed.defaultIs1");
            // walkSpeed 超範圍被夾。
            ColorTrackPlanner.Params hi = params();
            hi.walkSpeed = 5f;
            ColorTrackPlanner pl4 = new ColorTrackPlanner();
            pl4.step(idle(0f, 0f, 0.0f), hi, T0);
            ColorTrackPlanner.Command c4 = pl4.step(idle(0f, 0f, 0.0f), hi, T0 + DT);
            ok(Math.abs(c4.speed - 2.0f) < 1e-4f, "speed.clampedTo2");
        }

        // ⑩ 搜索：掃唔到連續 6 幀先轉，朝最後見到方向，最多 searchSteps 步；重見目標重置。
        {
            ColorTrackPlanner.Params sp = params();
            sp.searchSteps = 2;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            // 見過目標喺左邊（bearing=-0.2，<turnDb，所以先行前；只係記低方向）。
            pl.step(idle(-0.2f, 0f, 0.13f), sp, T0); // 近：none，亦記低 lastSide=-1
            ColorTrackPlanner.Input lost = in(false, 0f, 0f, 0f, ColorTrackPlanner.WALK_NONE, false);
            String r = "none";
            long t = T0 + DT;
            int firstAt = -1;
            for (int k = 0; k < 12; k++) {
                r = pl.step(lost, sp, t + k * DT).type;
                if (!r.equals("none") && firstAt < 0) firstAt = k;
            }
            ok(firstAt >= 5 && firstAt <= 7, "search.startsAfterSixLost firstAt=" + firstAt);
            // 重新模擬以驗證方向＋步數。
            ColorTrackPlanner pl2 = new ColorTrackPlanner();
            pl2.step(idle(-0.2f, 0f, 0.13f), sp, T0);
            int turns = 0;
            long tt = T0 + DT;
            boolean allLeft = true;
            for (int k = 0; k < 80; k++) {
                ColorTrackPlanner.Command c = pl2.step(lost, sp, tt);
                tt += DT;
                if (c.type.startsWith("turn_")) {
                    turns++;
                    if (!c.type.equals("turn_left")) allLeft = false;
                    // 模擬轉完（忙→完結，隔 >settle）。
                    pl2.step(in(false, 0f, 0f, 0f, ColorTrackPlanner.WALK_NONE, true), sp, tt);
                    tt += 1000;
                }
            }
            ok(turns == 2 && allLeft, "search.twoStepsLeftOnly turns=" + turns);
            // 預設 searchSteps=0：永不搜。
            ColorTrackPlanner pl3 = new ColorTrackPlanner();
            pl3.step(idle(-0.2f, 0f, 0.13f), params(), T0);
            String any = "none";
            for (int k = 0; k < 40; k++) {
                String x = pl3.step(lost, params(), T0 + DT + k * DT).type;
                if (!x.equals("none")) any = x;
            }
            eqs("none", any, "search.offByDefault");
            // 從來冇見過（lastSide=0）：唔搜（唔知轉邊邊）。
            ColorTrackPlanner pl4 = new ColorTrackPlanner();
            String any4 = "none";
            for (int k = 0; k < 40; k++) {
                String x = pl4.step(lost, sp, T0 + k * DT).type;
                if (!x.equals("none")) any4 = x;
            }
            eqs("none", any4, "search.neverSeenNoSearch");
        }

        // ⑪ reset／lastCommand／lastSide。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(idle(0.5f, 0f, 0.05f), p, T0);
            pl.step(idle(0.5f, 0f, 0.05f), p, T0 + DT);
            eqs("turn_right", pl.lastCommand(), "state.lastCommand");
            ok(pl.lastSide() == 1, "state.lastSide.right");
            pl.reset();
            eqs("none", pl.lastCommand(), "state.reset.lastCommand");
            ok(pl.lastSide() == 0, "state.reset.lastSide");
            // reset 後 settle／guard 清零：可即刻決策（冇殘留忙碌）。
            eqs("none", pl.step(idle(0.5f, 0f, 0.05f), p, T0 + 2 * DT).type, "state.reset.first");
            eqs("turn_right", pl.step(idle(0.5f, 0f, 0.05f), p, T0 + 3 * DT).type, "state.reset.second");
        }
    }

    private static ColorTrackPlanner.Input inFall(int fall, int walk, boolean other, float dx, float cov) {
        ColorTrackPlanner.Input i = in(true, dx, 0f, cov, walk, other);
        i.fall = fall;
        return i;
    }

    private static void fallUnitTests() {
        ColorTrackPlanner.Params p = params();
        final int OK = ColorTrackPlanner.FALL_OK;
        final int NR = ColorTrackPlanner.FALL_NOT_READY;
        final int DN = ColorTrackPlanner.FALL_DOWN;

        // ① 未武裝：想行都唔行（唔識偵測跌倒就唔郁）。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("none", feed(pl, inFall(NR, ColorTrackPlanner.WALK_NONE, false, 0f, 0.05f), p, T0, 6), "fall.notReady.noStart");
            eqs("none", feed(pl, inFall(NR, ColorTrackPlanner.WALK_NONE, false, 0.6f, 0.05f), p, T0 + 6 * DT, 6), "fall.notReady.noTurn");
        }
        // ② 返到 OK 之後要多等 POST_FALL_SETTLE_MS，先累計確認幀。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            long t = T0;
            pl.step(inFall(NR, ColorTrackPlanner.WALK_NONE, false, 0f, 0.05f), p, t);
            long ok0 = t + 1000; // 武裝
            ColorTrackPlanner.Input okIn = inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.05f);
            // 1.4 秒內（<1500）全部 none。
            String any = "none";
            for (long k = 0; k <= 1400; k += 150) {
                String r = pl.step(okIn, p, ok0 + k).type;
                if (!r.equals("none")) any = r;
            }
            eqs("none", any, "fall.settleAfterArm.within1.4s");
            // 過咗 1.5 秒：先 2 幀確認。
            long after = ok0 + 1500 + 1;
            eqs("none", pl.step(okIn, p, after).type, "fall.settleAfterArm.firstConfirm");
            eqs("hold_fwd", pl.step(okIn, p, after + DT).type, "fall.settleAfterArm.thenStarts");
        }
        // ③ 行緊步態時跌倒：即刻 release；之後唔再發命令；收步中唔 release 多次。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            feed(pl, idle(0f, 0f, 0.05f), p, T0, 2); // 起步 hold_fwd
            long tw = T0 + 2 * DT;
            eqs("release", pl.step(inFall(DN, ColorTrackPlanner.WALK_FWD, false, 0f, 0.05f), p, tw).type, "fall.down.releasesGait");
            eqs("none", pl.step(inFall(DN, ColorTrackPlanner.WALK_FINISHING, false, 0f, 0.05f), p, tw + DT).type, "fall.down.finishingNoCmd");
            eqs("none", pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, true, 0f, 0.05f), p, tw + 2 * DT).type, "fall.down.getUpPlayingNoCmd");
            ok(pl.totalFalls() == 1, "fall.down.countedOnce(" + pl.totalFalls() + ")");
            // DOWN 持續幾幀仍然只算一次。
            pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.05f), p, tw + 3 * DT);
            ok(pl.totalFalls() == 1, "fall.down.stillOneFall");
        }
        // ④ 閒置時跌倒：待決嘅確認幀作廢；起身後要多等先繼續。
        {
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0.6f, 0.05f), p, T0); // 轉向確認第 1 幀
            pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0.6f, 0.05f), p, T0 + DT);
            long up = T0 + 10000;
            ColorTrackPlanner.Input okIn = inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0.6f, 0.05f);
            eqs("none", pl.step(okIn, p, up).type, "fall.afterRecovery.noImmediate");
            eqs("none", pl.step(okIn, p, up + 1000).type, "fall.afterRecovery.stillSettling");
            long after = up + 1501;
            eqs("none", pl.step(okIn, p, after).type, "fall.afterRecovery.firstConfirm");
            eqs("turn_right", pl.step(okIn, p, after + DT).type, "fall.afterRecovery.resumes");
        }
        // ⑤ fallGuard=false：完全唔睇 fall。
        {
            ColorTrackPlanner.Params off = params();
            off.fallGuard = false;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            eqs("hold_fwd", feed(pl, inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.05f), off, T0, 2), "fall.guardOff.ignoresDown");
            ok(pl.totalFalls() == 0, "fall.guardOff.noCount");
        }
        // ⑥ 跌夠 maxFalls 次放棄（窗口內）；放棄後唔再郁；reset 清返。
        {
            ColorTrackPlanner.Params g = params();
            g.maxFalls = 3;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            long t = T0;
            String last = "none";
            for (int f = 0; f < 3; f++) {
                pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, t);
                last = pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, t + DT).type;
                if (f < 2) eqs("none", last, "fall.giveUp.notYet" + f);
                t += 20000;
            }
            eqs("give_up", last, "fall.giveUp.third");
            eqs("none", pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0.6f, 0.05f), g, t + 50000).type, "fall.giveUp.sticky");
            pl.reset();
            ok(pl.totalFalls() == 0, "fall.giveUp.resetClears");
        }
        // ⑦ 跌倒隔得耐（超過 120 s 窗口）唔累計。
        {
            ColorTrackPlanner.Params g = params();
            g.maxFalls = 2;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, T0);
            pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, T0 + DT);
            long later = T0 + ColorTrackPlanner.FALL_WINDOW_MS + 5000;
            pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, later);
            String r = pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, later + DT).type;
            eqs("none", r, "fall.window.oldFallExpired");
            // 窗口內第二次就放棄。
            pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, later + 10000);
            r = pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, later + 10000 + DT).type;
            eqs("give_up", r, "fall.window.twoWithinWindow");
        }
        // ⑧ maxFalls 範圍自動校正（0→1：第一跌即放棄；99→10）。
        {
            ColorTrackPlanner.Params g = params();
            g.maxFalls = 0;
            ColorTrackPlanner pl = new ColorTrackPlanner();
            pl.step(inFall(OK, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, T0);
            eqs("give_up", pl.step(inFall(DN, ColorTrackPlanner.WALK_NONE, false, 0f, 0.2f), g, T0 + DT).type, "fall.maxFalls.clampedTo1");
        }
    }

    // ═══════════════════ 閉環模擬（玩具模型）═══════════════════
    /** 單次模擬結果。 */
    private static final class Result {
        boolean reached;
        double reachedAtS = -1;
        double minDist = 1e9;
        int turns, sides, holdStarts, releases, ubxSteps, backs;
        int violations;
        int unsafeHolds;
        int headSwaps;
        int falls;
        boolean gaveUp;
        /** 非 OK 跌倒狀態下發出咗「唔係 none／release／give_up」嘅命令（唔准）。 */
        int commandsWhileDown;
        long firstCommandMs = -1;
        double finalDist;
        double finalBearingDeg;
    }

    private static final class Sim {
        // 配置
        final boolean gait;
        final boolean headFollow;
        boolean headSearch = true;
        final float halfFovDeg = 30f;
        final ColorTrackPlanner.Params pp;
        final java.util.Random rnd;
        // 世界
        double x = 0, y = 0, th = 0;     // 機器人（m, m, rad，CCW 正）
        double tx, ty;                   // 目標
        boolean targetVisibleToWorld = true;
        // 頭（度，右正；相對身體）
        double psiDeg = 0;
        int curPan = 120;
        double panFrom = 120;
        long sentMs = 0;
        int moveMs = 400;
        long lastServoMs = -1000;
        boolean pulledHome = false;
        // 步態
        int gaitState = 0;               // 0 無、1 行緊、2 收步中
        boolean gaitBack = false;
        long gaitStartT = 0, lastHoldT = 0, finishT = 0;
        float gaitSpeed = 1f;
        // ubx
        double ubxLeftMs = 0;
        String ubxType = "";
        double ubxTotalMs = 0;
        double ubxTurnDeg = 0, ubxLatM = 0, ubxFwdM = 0;
        // 跌倒注入（相對模擬開始嘅 ms）；起身流程：躺低 LYING_MS → 起身動作 GETUP_MS → 返正常
        long[] fallAtMs = new long[0];
        int fallIdx = 0;
        int fallPhase = 0;               // 0 正常、1 躺低、2 起身動作播緊
        long phaseUntil = 0;
        long notReadyMs = 0;             // 開頭幾耐未武裝（NOT_READY）
        static final long LYING_MS = 3000, GETUP_MS = 5000;
        long startT = 1000;
        // 規劃器
        final ColorTrackPlanner pl = new ColorTrackPlanner();
        final Result r = new Result();
        // 假設嘅物理常數
        static final double GAIT_V = 0.04;      // m/s（speed 1.0）
        static final long LEAD_IN_MS = 2000;
        static final long LEASE_MS = 1500;
        static final long FINISH_MS = 1500;
        static final double K_COV = 0.03;       // coverage = K/d^2（d=0.5 m 即 0.12）
        static final double TURN_NORMAL_DEG = 18, TURN_BIG_DEG = 30;
        static final double LEFT_WEAKNESS = 0.8; // 左轉效果較弱（左右唔對稱）
        static final double SIDE_STEP_M = 0.07;

        Sim(boolean gait, boolean headFollow, ColorTrackPlanner.Params pp, long seed, double tx, double ty, double th0) {
            this.gait = gait;
            this.headFollow = headFollow;
            this.pp = pp;
            this.pp.gait = gait;
            this.rnd = new java.util.Random(seed);
            this.tx = tx;
            this.ty = ty;
            this.th = th0;
            // 起始：頭已經追住目標（追蹤進行中，最多 ±45°＝PAN 範圍），否則目標一開始喺鏡頭外。
            if (headFollow) {
                double b = Math.toDegrees(bearingRightRad());
                psiDeg = Math.max(-45, Math.min(45, b));
                curPan = 120 + (int) Math.round(psiDeg);
                panFrom = curPan;
                psiDeg = curPan - 120;
            }
        }

        double dist() { return Math.hypot(tx - x, ty - y); }

        double bearingRightRad() {
            double a = Math.atan2(ty - y, tx - x) - th;
            while (a > Math.PI) a -= 2 * Math.PI;
            while (a < -Math.PI) a += 2 * Math.PI;
            return -a;
        }

        double estPan(long now) {
            double f = (now - sentMs) / (double) moveMs;
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            return panFrom + (curPan - panFrom) * f;
        }

        void actionStarted(long now) {
            // 步態／.ubx 每格都命令頭去 home（Center 同樣假設）。
            panFrom = estPan(now);
            curPan = 120;
            sentMs = now;
            moveMs = 1000;
            pulledHome = true;
        }

        boolean headBusy() { return gaitState != 0 || ubxLeftMs > 0; }

        /** 物理推進 dt ms。 */
        void plant(long now, long dtMs) {
            double dt = dtMs / 1000.0;
            // 跌倒／起身流程
            if (fallPhase == 0 && fallIdx < fallAtMs.length && now >= startT + fallAtMs[fallIdx]) {
                fallIdx++;
                fallPhase = 1;
                phaseUntil = now + LYING_MS;
                gaitState = 0;
                ubxLeftMs = 0;
                th += Math.toRadians((rnd.nextDouble() - 0.5) * 40); // 跌完朝向亂咗
                r.falls++;
            } else if (fallPhase == 1 && now >= phaseUntil) {
                fallPhase = 2;
                phaseUntil = now + GETUP_MS;
            } else if (fallPhase == 2 && now >= phaseUntil) {
                fallPhase = 0;
                actionStarted(now); // 起身動作收尾：頭喺 home
            }
            // 頭動力學（一階）：目標 = 命令位置；被拉返 home 時較慢。
            double tau = pulledHome ? 0.35 : 0.15;
            double target = curPan - 120;
            psiDeg += (target - psiDeg) * (1 - Math.exp(-dt / tau));
            // 租約：冇續租超過 LEASE_MS 自動收步。
            if (gaitState == 1 && now - lastHoldT > LEASE_MS) {
                gaitState = 2;
                finishT = now;
            }
            if (gaitState == 1 && now - gaitStartT >= LEAD_IN_MS) {
                double v = GAIT_V * gaitSpeed * (gaitBack ? -1 : 1);
                x += Math.cos(th) * v * dt;
                y += Math.sin(th) * v * dt;
            } else if (gaitState == 2) {
                double v = GAIT_V * gaitSpeed * 0.3 * (gaitBack ? -1 : 1);
                if (now - gaitStartT >= LEAD_IN_MS) {
                    x += Math.cos(th) * v * dt;
                    y += Math.sin(th) * v * dt;
                }
                if (now - finishT >= FINISH_MS) {
                    gaitState = 0;
                    pulledHome = false;
                }
            }
            if (ubxLeftMs > 0) {
                double used = Math.min(ubxLeftMs, dtMs);
                double frac = used / ubxTotalMs;
                th += Math.toRadians(ubxTurnDeg) * frac;
                x += Math.cos(th + Math.PI / 2) * ubxLatM * frac + Math.cos(th) * ubxFwdM * frac;
                y += Math.sin(th + Math.PI / 2) * ubxLatM * frac + Math.sin(th) * ubxFwdM * frac;
                ubxLeftMs -= used;
                if (ubxLeftMs <= 0) {
                    ubxLeftMs = 0;
                    if (gaitState == 0) pulledHome = false;
                }
            }
            double d = dist();
            if (d < r.minDist) r.minDist = d;
        }

        void startUbx(String type, long now) {
            if (gaitState != 0) r.violations++; // 步態行緊／收步中唔可以播 .ubx
            if (fallPhase != 0) r.violations++; // 跌低／起身中唔可以播 .ubx
            if (ubxLeftMs > 0) r.violations++;  // 唔可以疊
            double noise = 0.85 + 0.3 * rnd.nextDouble();
            ubxType = type;
            ubxTurnDeg = 0;
            ubxLatM = 0;
            ubxFwdM = 0;
            switch (type) {
                case ColorTrackPlanner.TURN_L:
                    ubxTotalMs = 4250; ubxTurnDeg = +TURN_NORMAL_DEG * LEFT_WEAKNESS * noise; r.turns++; break;
                case ColorTrackPlanner.TURN_R:
                    ubxTotalMs = 4250; ubxTurnDeg = -TURN_NORMAL_DEG * noise; r.turns++; break;
                case ColorTrackPlanner.TURN_L_BIG:
                    ubxTotalMs = 3000; ubxTurnDeg = +TURN_BIG_DEG * LEFT_WEAKNESS * noise; r.turns++; break;
                case ColorTrackPlanner.TURN_R_BIG:
                    ubxTotalMs = 3300; ubxTurnDeg = -TURN_BIG_DEG * noise; r.turns++; break;
                case ColorTrackPlanner.SIDE_L:
                    ubxTotalMs = 2750; ubxLatM = +SIDE_STEP_M * noise; r.sides++; break;
                case ColorTrackPlanner.SIDE_R:
                    ubxTotalMs = 2750; ubxLatM = -SIDE_STEP_M * noise; r.sides++; break;
                case ColorTrackPlanner.SIDE_L_BIG:
                    ubxTotalMs = 4850; ubxLatM = +3 * SIDE_STEP_M * noise; r.sides++; break;
                case ColorTrackPlanner.SIDE_R_BIG:
                    ubxTotalMs = 4550; ubxLatM = -3 * SIDE_STEP_M * noise; r.sides++; break;
                case ColorTrackPlanner.STEP_FWD:
                    ubxTotalMs = 13350; ubxFwdM = 0.30 * noise; r.ubxSteps++; break;
                case ColorTrackPlanner.STEP_BACK:
                    ubxTotalMs = 16650; ubxFwdM = -0.30 * noise; r.backs++; break;
                default:
                    throw new IllegalStateException("unknown ubx " + type);
            }
            ubxLeftMs = ubxTotalMs;
            actionStarted(now);
        }

        /** 一個偵測幀：相機 → 頭追蹤 → 規劃 → 執行。 */
        void frame(long now) {
            // 相機
            double d = dist();
            boolean found = false;
            float dx = 0f;
            float cov = 0f;
            if (targetVisibleToWorld && d > 0.15) {
                double angleDeg = Math.toDegrees(bearingRightRad()) - psiDeg;
                double dxc = angleDeg / halfFovDeg;
                cov = (float) Math.min(0.9, K_COV / (d * d));
                if (Math.abs(dxc) <= 1.0 && cov >= 0.0055f) {
                    found = true;
                    dx = (float) dxc;
                }
            }
            // headOffset 先於本幀頭指令算（Center 同樣次序）。
            float headOff = ColorTrackLogic.headOffset((float) estPan(now), 120, halfFovDeg);

            // 播放狀態
            int walkState = ColorTrackPlanner.WALK_NONE;
            if (gaitState == 1) walkState = gaitBack ? ColorTrackPlanner.WALK_BACK : ColorTrackPlanner.WALK_FWD;
            else if (gaitState == 2) walkState = ColorTrackPlanner.WALK_FINISHING;
            boolean otherPlaying = ubxLeftMs > 0 || fallPhase == 2;

            // 頭追蹤（同 Center：死區 0.10、stepDeg 17、tilt 唔模擬）。
            if (headFollow && found && !headBusy() && Math.abs(dx) > 0.10f && now - lastServoMs >= 150) {
                float mag = (Math.abs(dx) - 0.10f) / 0.90f;
                int delta = Math.max(1, Math.round(mag * 17));
                if (dx < 0) delta = -delta;
                int np = Math.max(75, Math.min(165, curPan + delta));
                if (np != curPan) {
                    panFrom = estPan(now);
                    curPan = np;
                    sentMs = now;
                    moveMs = 400;
                    pulledHome = false;
                    lastServoMs = now;
                }
            }

            ColorTrackPlanner.Input in = new ColorTrackPlanner.Input();
            in.found = found;
            in.dx = dx;
            in.headOffset = headOff;
            in.coverage = found ? cov : 0f;
            in.walkState = walkState;
            in.otherPlaying = otherPlaying;
            in.fall = fallPhase != 0 ? ColorTrackPlanner.FALL_DOWN
                    : (now - startT < notReadyMs ? ColorTrackPlanner.FALL_NOT_READY : ColorTrackPlanner.FALL_OK);
            ColorTrackPlanner.Command c = pl.step(in, pp, now);
            if (c.type.equals(ColorTrackPlanner.GIVE_UP)) {
                r.gaveUp = true;
            } else if (in.fall != ColorTrackPlanner.FALL_OK && !c.type.equals(ColorTrackPlanner.NONE)
                    && !c.type.equals(ColorTrackPlanner.RELEASE)) {
                r.commandsWhileDown++;
            }
            if (!c.type.equals(ColorTrackPlanner.NONE) && !c.type.equals(ColorTrackPlanner.RELEASE)
                    && !c.type.equals(ColorTrackPlanner.GIVE_UP) && r.firstCommandMs < 0) {
                r.firstCommandMs = now - startT;
            }

            // 頭搜索（同 Center：行路跟＋頭追蹤開住、掃唔到、頭有空）。
            if (headFollow && headSearch && !found && !headBusy() && now - lastServoMs >= 150) {
                int np = pl.headSearchPan(curPan, 8, 75, 165, 120);
                if (np != curPan) {
                    panFrom = estPan(now);
                    curPan = np;
                    sentMs = now;
                    moveMs = 400;
                    pulledHome = false;
                    lastServoMs = now;
                }
            }

            switch (c.type) {
                case ColorTrackPlanner.NONE:
                case ColorTrackPlanner.GIVE_UP:
                    break;
                case ColorTrackPlanner.HOLD_FWD:
                case ColorTrackPlanner.HOLD_BACK: {
                    boolean back = c.type.equals(ColorTrackPlanner.HOLD_BACK);
                    if (gaitState == 0) {
                        if (!found) r.unsafeHolds++; // 唔識見目標就起步＝盲行
                        gaitState = 1;
                        gaitBack = back;
                        gaitStartT = now;
                        r.holdStarts++;
                        actionStarted(now);
                    } else if (gaitState == 1 && gaitBack != back) {
                        r.violations++; // 唔可以邊行邊轉方向
                    }
                    if (gaitState == 1) {
                        lastHoldT = now;
                        gaitSpeed = c.speed;
                    }
                    break;
                }
                case ColorTrackPlanner.RELEASE:
                    if (gaitState == 1) {
                        gaitState = 2;
                        finishT = now;
                        r.releases++;
                    }
                    break;
                default:
                    startUbx(c.type, now);
            }
        }

        /** 跑 maxS 秒；回結果。 */
        Result run(double maxS, double stableS) {
            long now = 1000;
            long end = now + (long) (maxS * 1000);
            long stableStart = -1;
            while (now < end) {
                plant(now, 150);
                frame(now);
                now += 150;
                // 抵達判定：夠近＋置中＋閒置，並維持 stableS 秒。
                boolean idleNow = gaitState == 0 && ubxLeftMs <= 0;
                double d = dist();
                double bDeg = Math.toDegrees(bearingRightRad());
                boolean good = idleNow && K_COV / (d * d) >= pp.closeCoverage * ColorTrackPlanner.NEAR_HYST // 近距離滯後帶內都算夠近
                        && Math.abs(bDeg) <= pp.turnDb * halfFovDeg * 1.5;
                if (good) {
                    if (stableStart < 0) stableStart = now;
                    if ((now - stableStart) / 1000.0 >= stableS) {
                        r.reached = true;
                        r.reachedAtS = (now - 1000) / 1000.0;
                        break;
                    }
                } else {
                    stableStart = -1;
                }
            }
            r.finalDist = dist();
            r.finalBearingDeg = Math.toDegrees(bearingRightRad());
            return r;
        }
    }

    private static Result runScenario(boolean gait, boolean head, double tx, double ty, double th0, long seed,
            ColorTrackPlanner.Params pp) {
        Sim s = new Sim(gait, head, pp, seed, tx, ty, th0);
        return s.run(400, 3.0);
    }

    private static Sim fallSim(long seed, double tx, double ty, ColorTrackPlanner.Params pp, long[] falls, long notReady) {
        Sim s = new Sim(true, true, pp, seed, tx, ty, 0.0);
        s.fallAtMs = falls;
        s.notReadyMs = notReady;
        return s;
    }

    private static void fallSimulationTests() {
        // ① 行路途中跌 2 次（窗口內 <3）：收步、唔亂發命令、起身後自動繼續並到達。
        for (long seed = 1; seed <= 5; seed++) {
            Sim s = fallSim(seed, 2.0, 0.0, params(), new long[] { 12000, 30000 }, 0);
            Result r = s.run(500, 3.0);
            String tag = "sim.fall.recovers.seed" + seed;
            ok(r.falls == 2, tag + ".fellTwice(" + r.falls + ")");
            ok(!r.gaveUp, tag + ".notGaveUp");
            ok(r.commandsWhileDown == 0, tag + ".noCommandsWhileDown(" + r.commandsWhileDown + ")");
            ok(r.violations == 0, tag + ".noViolations(" + r.violations + ")");
            ok(r.reached, tag + ".stillReaches(d=" + String.format(java.util.Locale.US, "%.2f", r.finalDist) + ")");
        }
        // ② 2 分鐘內跌 3 次：第 3 次放棄，之後完全唔郁。
        {
            Sim s = fallSim(3, 2.0, 0.0, params(), new long[] { 20000, 50000, 80000 }, 0);
            Result r = s.run(300, 3.0);
            ok(r.gaveUp, "sim.fall.giveUp.gaveUp");
            ok(!r.reached, "sim.fall.giveUp.notReached");
            ok(r.commandsWhileDown == 0 && r.violations == 0, "sim.fall.giveUp.safe");
            double x0 = s.x, y0 = s.y;
            long now = 1000 + 300000;
            for (int k = 0; k < 100; k++) { s.plant(now, 150); s.frame(now); now += 150; }
            ok(Math.hypot(s.x - x0, s.y - y0) < 1e-9, "sim.fall.giveUp.staysStill");
        }
        // ③ 跌倒隔得耐（>120 s 窗口）唔累計：3 次但每次相隔 >120 s 唔放棄。
        {
            Sim s = fallSim(4, 2.2, 0.0, params(), new long[] { 20000, 150000, 280000 }, 0);
            // 永不判「到達」（stableS 極大），跑足 400 s，令三次跌倒都發生（包括到達之後）。
            Result r = s.run(400, 1e9);
            ok(r.falls == 3 && !r.gaveUp, "sim.fall.window.farApartNoGiveUp(falls=" + r.falls + ",gaveUp=" + r.gaveUp + ")");
            ok(r.commandsWhileDown == 0 && r.violations == 0, "sim.fall.window.safe");
        }
        // ④ 開頭未武裝（NOT_READY 6 s）：6 s 內冇任何命令，武裝後（再多等 1.5 s）先行，最終到達。
        {
            Sim s = fallSim(5, 2.0, 0.0, params(), new long[0], 6000);
            Result r = s.run(400, 3.0);
            ok(r.firstCommandMs >= 6000 + ColorTrackPlanner.POST_FALL_SETTLE_MS - 200,
                    "sim.fall.notReady.noCommandBeforeArmed(first=" + r.firstCommandMs + ")");
            ok(r.reached && r.violations == 0, "sim.fall.notReady.thenReaches");
        }
        // ⑤ fallGuard=false：唔受 NOT_READY 限制（開頭即行）。
        {
            ColorTrackPlanner.Params pg = params();
            pg.fallGuard = false;
            Sim s = fallSim(6, 2.0, 0.0, pg, new long[0], 6000);
            Result r = s.run(400, 3.0);
            ok(r.firstCommandMs >= 0 && r.firstCommandMs < 3000, "sim.fall.guardOff.startsImmediately(first=" + r.firstCommandMs + ")");
        }
        // ⑥ ubx 引擎一樣受保護（跌倒中唔播步）。
        for (long seed = 1; seed <= 3; seed++) {
            ColorTrackPlanner.Params pu = params();
            Sim s = new Sim(false, true, pu, seed, 1.6, 0.0, 0.0);
            s.fallAtMs = new long[] { 6000 };
            Result r = s.run(500, 3.0);
            ok(r.falls == 1 && r.commandsWhileDown == 0 && r.violations == 0, "sim.fall.ubx.safe.seed" + seed + "(falls=" + r.falls + ")");
        }
    }

    private static void simulationTests() {
        // 多個初始條件 × 多個 seed，gait 同 ubx 引擎各跑一次。
        double[][] starts = {
            // tx, ty, th0(rad)
            { 2.0, 0.0, 0.0 },                                   // 正前 2.0 m
            { 1.8 * Math.cos(Math.toRadians(-40)), 1.8 * Math.sin(Math.toRadians(-40)), 0.0 }, // 右 40° 1.8 m
            { 1.5 * Math.cos(Math.toRadians(70)), 1.5 * Math.sin(Math.toRadians(70)), 0.0 },   // 左 70° 1.5 m（頭＋視角內）
            { 0.9, 0.25, 0.0 },                                  // 近＋偏左
            { 1.2, -0.6, 0.0 },                                  // 偏右＋中距
        };
        String[] names = { "ahead2m", "right40", "left70", "near+offset", "right+mid" };
        int ubxReached = 0;
        int ubxTotal = 0;
        for (int g = 0; g < 2; g++) {
            boolean gait = g == 0;
            String eng = gait ? "gait" : "ubx";
            for (int si = 0; si < starts.length; si++) {
                for (long seed = 1; seed <= 5; seed++) {
                    ColorTrackPlanner.Params pp = params();
                    Result r = runScenario(gait, true, starts[si][0], starts[si][1], starts[si][2], seed, pp);
                    String tag = "sim." + eng + "." + names[si] + ".seed" + seed;
                    String info = "final d=" + String.format(java.util.Locale.US, "%.2f", r.finalDist)
                            + " b=" + String.format(java.util.Locale.US, "%.1f", r.finalBearingDeg) + "deg turns=" + r.turns
                            + " sides=" + r.sides + " holds=" + r.holdStarts + " steps=" + r.ubxSteps;
                    // 兩種引擎都要：唔播步同步態互撞、唔盲行起步、轉向次數有界（唔抖）。
                    ok(r.violations == 0, tag + ".noViolations(" + r.violations + ")");
                    ok(r.unsafeHolds == 0, tag + ".noBlindStart(" + r.unsafeHolds + ")");
                    ok(r.turns <= 10, tag + ".turnsBounded(" + r.turns + ")");
                    if (gait) {
                        // gait：一定要收斂，release 後收步少量漂移，唔可以撞落目標
                        //（d=0.5 m 即「夠近」，容 0.35 m 以內為撞）。
                        ok(r.reached, tag + ".reached(" + info + ")");
                        ok(r.minDist >= 0.35, tag + ".noCollision(min=" + String.format(java.util.Locale.US, "%.2f", r.minDist) + ")");
                    } else {
                        // ubx：13 s 盲行步（假設每步 0.30 m）喺近標處會衝過頭，到達率只記錄。
                        ubxTotal++;
                        if (r.reached) ubxReached++;
                    }
                }
            }
        }
        System.out.println("  [sim] ubx engine reached " + ubxReached + "/" + ubxTotal
                + " scenarios (blind 13 s steps overshoot; gait engine must reach all)");

        // ubx 引擎同 gait 比：13 s 盲行步會喺 0.9 m 近標處衝過頭——量度並記錄，唔當失敗（係 gait 引擎存在嘅原因）。
        {
            ColorTrackPlanner.Params pu = params();
            Result ru = runScenario(false, true, 0.9, 0.0, 0.0, 7, pu);
            ColorTrackPlanner.Params pg = params();
            Result rg = runScenario(true, true, 0.9, 0.0, 0.0, 7, pg);
            System.out.println(String.format(java.util.Locale.US,
                    "  [sim] target 0.9 m ahead: ubx minDist=%.2f m, gait minDist=%.2f m", ru.minDist, rg.minDist));
            ok(rg.minDist >= ru.minDist, "sim.gaitNotWorseThanUbxOvershoot");
        }

        // 頭追蹤關閉（headFollow=false）：bearing=dx，照樣收斂。
        for (long seed = 1; seed <= 3; seed++) {
            Result r = runScenario(true, false, 1.8 * Math.cos(Math.toRadians(-20)), 1.8 * Math.sin(Math.toRadians(-20)), 0.0, seed, params());
            ok(r.reached && r.violations == 0, "sim.gait.noHeadFollow.seed" + seed);
        }

        // 左右唔對稱轉向（左轉較弱，LEFT_WEAKNESS）：左邊起始亦收斂（上面 left70 已含；呢度加 seed 壓力）。
        for (long seed = 10; seed < 20; seed++) {
            Result r = runScenario(true, true, 1.6 * Math.cos(Math.toRadians(55)), 1.6 * Math.sin(Math.toRadians(55)), 0.0, seed, params());
            ok(r.reached && r.violations == 0 && r.turns <= 10, "sim.gait.asymLeft.seed" + seed);
        }

        // 後退（backCoverage=0.25）：目標太近（d=0.3 m，cov=0.33）要退返出嚟，而且唔可以越退越近。
        {
            ColorTrackPlanner.Params pb = params();
            pb.backCoverage = 0.25f;
            Sim s = new Sim(true, true, pb, 3, 0.30, 0.0, 0.0);
            Result r = s.run(200, 3.0);
            ok(s.dist() > 0.33 && r.holdStarts > 0, "sim.backOff.tooClose(d=" + String.format(java.util.Locale.US, "%.2f", s.dist()) + ")");
            ok(r.violations == 0, "sim.backOff.noViolations");
        }

        // 行緊途中目標消失：gait 必須喺 2.5 s 內收步，而且收步期間唔播 .ubx、唔再起步。
        {
            Sim s = new Sim(true, true, params(), 5, 2.2, 0.0, 0.0);
            long now = 1000;
            boolean removed = false;
            long removedAt = 0;
            long stoppedAt = -1;
            int ubxAfterRemoval = 0;
            int holdsAfterRemoval = 0;
            int turnsBefore = 0;
            for (int k = 0; k < 400; k++) {
                s.plant(now, 150);
                int holdsBefore = s.r.holdStarts;
                int stepsBefore = s.r.turns + s.r.sides + s.r.ubxSteps;
                s.frame(now);
                if (!removed && s.gaitState == 1 && now - s.gaitStartT > 4000) {
                    s.targetVisibleToWorld = false;
                    removed = true;
                    removedAt = now;
                    turnsBefore = s.r.turns;
                }
                if (removed) {
                    if (s.gaitState != 1 && stoppedAt < 0) stoppedAt = now;
                    if (s.r.turns + s.r.sides + s.r.ubxSteps > stepsBefore) ubxAfterRemoval++;
                    if (s.r.holdStarts > holdsBefore) holdsAfterRemoval++;
                }
                now += 150;
            }
            ok(removed, "sim.lost.setup");
            ok(stoppedAt > 0 && stoppedAt - removedAt <= 2500, "sim.lost.stopsWithin2.5s(" + (stoppedAt - removedAt) + "ms)");
            ok(ubxAfterRemoval == 0 && holdsAfterRemoval == 0, "sim.lost.noBlindMotion(" + ubxAfterRemoval + "," + holdsAfterRemoval + ")");
            ok(s.r.violations == 0, "sim.lost.noViolations");
        }

        // 從未見過目標（身後 180°）：冇任何動作（唔盲轉、唔盲行）。
        {
            Sim s = new Sim(true, true, params(), 9, -2.0, 0.0, 0.0);
            Result r = s.run(60, 3.0);
            ok(r.holdStarts == 0 && r.turns == 0 && r.sides == 0 && r.ubxSteps == 0, "sim.neverSeen.noMotion");
        }

        // 搜索：先見過目標，之後被擋走（目標移到身體左側 100°，視野外）→ 開咗 searchSteps 會朝左轉搵返。
        {
            ColorTrackPlanner.Params ps = params();
            ps.searchSteps = 4;
            Sim s = new Sim(true, true, ps, 11, 2.0, 0.0, 0.0);
            // 先跑幾幀令 planner 見過目標（偏左少少令 lastSide=-1）。
            s.ty = 0.6;
            long now = 1000;
            for (int k = 0; k < 4; k++) { s.plant(now, 150); s.frame(now); now += 150; }
            // 突然機身被推轉：目標而家喺身後左 120°（視野外）。
            s.th = -Math.toRadians(120);
            boolean found = false;
            for (int k = 0; k < 600 && !found; k++) {
                s.plant(now, 150);
                s.frame(now);
                now += 150;
                double dxc = Math.toDegrees(s.bearingRightRad()) - s.psiDeg;
                if (Math.abs(dxc) <= 30.0 && s.dist() > 0.15) found = true;
            }
            ok(s.r.violations == 0, "sim.search.noViolations");
            ok(found, "sim.search.refound");
            ok(s.r.turns >= 1 && s.r.turns <= 8, "sim.search.boundedTurns(" + s.r.turns + ")");
        }
    }
}
