package com.ubtechinc.alpha.hardware;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HeadKeyPoller 單元測試 (零依賴，不用 JUnit)。
 *
 * 點解唔用 JUnit：同 app/src/test 的 ApiValidatorTest／SerialFrameCodecTest
 * 一樣——本專案離線編譯，gradle cache 入面冇 junit，加 testImplementation 會
 * 整斷每個人嘅 offline build。呢個 file 係純 Java + main() runner，經
 * scripts/test-headkeypoller.py 執行——script 會臨時生成
 * android.util.Log / android.os.SystemClock 兩粒 no-op stub 再 javac/java
 * 直接跑（HeadKeyPoller/HeadKeyMgr 掂到呢兩個 class；SDK 嘅 android.jar
 * runtime 一 call 就拋 Stub!，唔用得）。SystemClock stub 提供可寫嘅
 * SystemClock.now，等呢度控制 0x5e/66 嘅 1 秒去重窗。
 *
 * 測咩：
 *   1. Lynx 制 0x5a..0x5f 收斂狀態機回歸（press/release/both 全鏈）。
 *   2. 舊制 60/61 click 合成（即行 press、release 由 Timer 隔 150ms 放）；
 *      64/65 唔映射。
 *   3. 舊制 66（interrupt）＝總停，同 0x5e01 共用 1 秒去重窗。
 *   4. 舊制 66 假旗俾後續 Lynx press 正常清理（唔會吃單）。
 *   5. 低 byte 0 照收（driver 兩款編碼）；unknown code 全吞。
 *   6. 真 Timer 煙霧測試（唔覆寫 scheduleClickRelease，等 150ms 收 release）。
 *
 * 碼值／語意來源：3.0.0.2 官方 HeadKeyMgr 常數 MIC5_KEY_*（兩代並存）、
 * 1.1.7.3 AlphaMainSeviceImpl.handleHeadKeyEvent 轉換表（60→5、61→4、
 * 66→6、64→7）、同 HeadKeyPoller.onNativeKey 現行註釋。
 *
 * 執行：python scripts/test-headkeypoller.py
 */
public final class HeadKeyPollerTest {
    private static int passed = 0;
    private static int failed = 0;

    private static final List<Integer> gestures = new ArrayList<Integer>();
    private static final List<Integer> natives = new ArrayList<Integer>();

    private HeadKeyPollerTest() {}

    /** 捕捉舊制 click 排程，唔等真 Timer（見 test doc #6 另有真 Timer 測試）。 */
    private static final class TestPoller extends HeadKeyPoller {
        final List<Integer> pendingReleases = new ArrayList<Integer>();

        @Override
        void scheduleClickRelease(int eventCode) {
            pendingReleases.add(eventCode);
        }
    }

    public static void main(String[] args) {
        TestPoller poller = new TestPoller();
        poller.setListener(new HeadKeyPoller.Listener() {
            @Override public void onGesture(int eventCode) {
                gestures.add(eventCode);
            }
            @Override public void onHeadKeyNative(int code) {
                natives.add(code);
            }
        });

        // 去重窗以 uptimeMillis 計；測試自己控時（stub SystemClock.now）。
        SystemClock.now = 1000;

        // ---- 1) Lynx 制回歸：0x5a..0x5f ----
        feed(poller, 0x5A01);
        eq(g(0x5a), gestures, "Lynx 5A01 -> [5a]");
        eq(g(0x5A01), natives, "Lynx 5A01 raw published");

        feed(poller, 0x5B01);
        eq(g(0x5b), gestures, "Lynx 5B01 -> [5b]");

        feed(poller, 0x5C01);
        eq(g(0x5c), gestures, "Lynx 5C01 -> [5c]");

        feed(poller, 0x5D01);
        eq(g(0x5d), gestures, "Lynx 5D01 -> [5d]");

        feed(poller, 0x5E01);
        eq(g(0x5e), gestures, "Lynx 5E01 -> [5e]");

        feed(poller, 0x5F01);
        eq(g(0x5f), gestures, "Lynx 5F01 -> [5f]");

        // 清完旗再行一次單鍵（回歸：唔好被上一輪 both 殘留吃掉）。
        feed(poller, 0x5A01);
        eq(g(0x5a), gestures, "Lynx second 5A01 -> [5a]");
        feed(poller, 0x5B01);
        eq(g(0x5b), gestures, "Lynx second 5B01 -> [5b]");

        // 低 byte 0 版本照收（官方 newConvert 唔理低 byte）。
        feed(poller, 0x5A00);
        eq(g(0x5a), gestures, "Lynx 5A00 (low byte 0) -> [5a]");
        feed(poller, 0x5B00);
        eq(g(0x5b), gestures, "Lynx 5B00 (low byte 0) -> [5b]");

        // ---- 2) 舊制 60/61 click：即 press，release 由 Timer 隔 150ms 放 ----
        feed(poller, 0x3C01);
        eq(g(0x5a), gestures, "old 60 -> press [5a]");
        eq(g(0x3C01), natives, "old 60 raw published");
        eq(g(0x5b), poller.pendingReleases, "old 60 schedules release [5b]");
        poller.pendingReleases.clear();

        feed(poller, 0x3D01);
        eq(g(0x5c), gestures, "old 61 -> press [5c]");
        eq(g(0x5d), poller.pendingReleases, "old 61 schedules release [5d]");
        poller.pendingReleases.clear();

        // 64/65（長按）官方語意唔啱本 app（QR 模式 toggle／未處理）→ 唔映射。
        feed(poller, 0x4001);
        eq(g(), gestures, "old 64 unmapped -> nothing");
        eq(g(), natives, "old 64 not published");
        eq(g(), poller.pendingReleases, "old 64 schedules no release");

        feed(poller, 0x4101);
        eq(g(), gestures, "old 65 unmapped -> nothing");

        // ---- 3) 舊制 66 interrupt＝總停，共用 1 秒去重窗 ----
        SystemClock.now = 5000;
        feed(poller, 0x4201);
        eq(g(0x5e), gestures, "old 66 -> [5e]");
        eq(g(0x4201), natives, "old 66 raw published");

        feed(poller, 0x4201);
        eq(g(), gestures, "old 66 again within 1s -> deduped");
        eq(g(0x4201), natives, "old 66 echo still raw-published");

        SystemClock.now = 9000;
        feed(poller, 0x4201);
        eq(g(0x5e), gestures, "old 66 after 4s -> fires again");

        // ---- 4) 66 之後舊 click 唔吞、Lynx press 清假旗 ----
        feed(poller, 0x3C01);
        eq(g(0x5a), gestures, "old click after 66 -> not swallowed (press)");
        eq(g(0x5b), poller.pendingReleases, "old click after 66 -> schedules release [5b]");
        poller.pendingReleases.clear();

        feed(poller, 0x5A01);
        eq(g(0x5a), gestures, "Lynx press after 66 -> [5a] (fake flags cleared)");
        feed(poller, 0x5B01);
        eq(g(0x5b), gestures, "Lynx release -> [5b]");

        // ---- 5) 低 byte 0 嘅 66 照收；unknown code 全吞 ----
        SystemClock.now = 20000;
        feed(poller, 0x4200);
        eq(g(0x5e), gestures, "old 66 low byte 0 -> [5e]");

        SystemClock.now = 30000;
        feed(poller, 0x9999);
        eq(g(), gestures, "unknown 0x9999 -> nothing");
        eq(g(), natives, "unknown 0x9999 -> not published");

        feed(poller, 7);
        eq(g(), gestures, "bare 7 -> nothing (no high byte)");

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void feed(HeadKeyPoller poller, int code) {
        gestures.clear();
        natives.clear();
        poller.onNativeKey(code);
    }

    private static List<Integer> g(Integer... v) {
        return Arrays.asList(v);
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }
}
