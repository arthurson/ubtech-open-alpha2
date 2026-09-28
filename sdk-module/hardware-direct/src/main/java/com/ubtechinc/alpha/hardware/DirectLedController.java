package com.ubtechinc.alpha.hardware;

import android.util.Log;

/**
 * 5-mic 頭/眼/嘴 LED 的 JNI 直驅。
 * 完全繞過 alpha2services 的 AIDL，直接走 libhead_led.so 的 ioctl。
 * 3.002 版（/dev/led_eye，ioctl 號與 1.1.7.3 逐個相同：On=0x4c00/OFF=0x4c01/
 * Eye=0x4c02/Head=0x4c03/Mouth=0x4c04，反匯編核對過）。
 * 已在 open-alpha2 的 MouthLedData 驗證過。
 *
 * <p>為避免 sdk-module 硬件層與 app 層的循環依賴，這裡用反射調用
 * {@code com.ubtechinc.alpha.jni.LedControl}（3.002 原裝包名；舊
 * {@code com.ubtechinc.mic5.LedControl} 保留文件但不再引用）。
 * 這樣 hardware-direct 編譯期不依賴 app，執行時通過自身 dex 找到類。</p>
 */
public final class DirectLedController {
    private static final String TAG = "DirectLedController";
    private static final String LED_CTRL = "com.ubtechinc.alpha.jni.LedControl";

    private DirectLedController() {}

    // 複用 AIDL_REFERENCE 3.1 的已驗證參數表
    // p1 color 1紅2綠3藍4黄5紫6青7白, p2 亮度1-9, p5/p6 時序, p7 runTime, p8 mode
    // p3/p4 用全 mask（31/31，跟上面講嘅原 binder 行為；之前誤傳 color，
    // 搞到同一隻色比 LedCenter preset 暗一截）。
    public static boolean setHead5Mic(int color, int bright, int p5, int p6, int runTime, int mode) {
        boolean ok = callLedReflect("ledSetHead",
                new Object[]{color, bright, 31, 31, p5, p6, runTime, mode}, "head color=" + color + " mode=" + mode);
        if (ok) recordHead(color, bright, 31, 31);
        return ok;
    }

    public static boolean setMouth(int runTime, int breathe, int off, int play, int mode) {
        boolean ok = callLedReflect("ledSetMouth",
                new Object[]{runTime, breathe, off, play, mode},
                "mouth mode=" + mode + " breathe=" + breathe + "ms off=" + off + "ms");
        if (ok) recordMouth(runTime, breathe, off, play, mode);
        return ok;
    }

    public static boolean setOff() {
        // 3.002 簽名 ledSetOFF(I)：反匯編證實該 int 只進 log（mov r3,r4 → __android_log_print），不落硬件，傳 0。
        // 注意：呢個 ioctl 號本身會連 wifi 12/13 一起清——硬件上頭+眼係一齊熄，
        // 所以成功嗰陣兩邊 mirror 一齊記 off。咀通道一樣會死，即刻補返（之前呼吸緊先補）。
        boolean ok = callLedReflect("ledSetOFF", new Object[]{0}, "ledSetOFF");
        if (ok) {
            recordHeadOff();
            recordEyeOff();
            restoreMouthAfterOff();
        }
        return ok;
    }

    // 反射快取：之前每次打燈都 Class.forName + getMethod 全套（disco 高頻下
    // 每秒十幾次，又跑喺 Visualizer callback thread）。而家 resolve 一次留用。
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean sInitDone = false;
    private static volatile boolean sInitOk = false;
    private static Class<?> sCls = null;
    private static java.lang.reflect.Method sOpen = null;
    private static java.lang.reflect.Method sClose = null;
    private static java.lang.reflect.Method sHead = null;
    private static java.lang.reflect.Method sEye = null;
    private static java.lang.reflect.Method sMouth = null;
    private static java.lang.reflect.Method sOn = null;
    private static java.lang.reflect.Method sOff = null;

    private static boolean ensureInit() {
        if (sInitDone) return sInitOk;
        synchronized (INIT_LOCK) {
            if (sInitDone) return sInitOk;
            boolean ok = false;
            try {
                sCls = Class.forName(LED_CTRL);
                sOpen = sCls.getMethod("open");
                try {
                    sClose = sCls.getMethod("close");
                } catch (NoSuchMethodException e) {
                    // close 唔存在＝下面直接拒絕，唔好每次 open 漏一個 fd——
                    // 高頻打燈會拖死成部機，大聲 log 出嚟等 logcat 睇到。
                    Log.e(TAG, "LedControl.close() NOT FOUND - refusing LED calls to avoid leaking an fd per open()");
                    sClose = null;
                }
                Class<?>[] i8 = new Class[]{int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class};
                Class<?>[] i5 = new Class[]{int.class, int.class, int.class, int.class, int.class};
                Class<?>[] i1 = new Class[]{int.class};
                sHead = sCls.getMethod("ledSetHead", i8);
                sEye = sCls.getMethod("ledSetEye", i8);
                sMouth = sCls.getMethod("ledSetMouth", i5);
                sOn = sCls.getMethod("ledSetOn", i1);
                sOff = sCls.getMethod("ledSetOFF", i1);
                // 核心三件齊＋close 在先至算得；mouth/on/off 缺唔致命
                // （對應功能單獨回 false），唔好拖累頭/眼。
                ok = sOpen != null && sHead != null && sEye != null && sClose != null;
            } catch (Throwable t) {
                Log.w(TAG, "LED reflect init failed: " + t.getMessage());
            }
            // 衰咗唔 latch——遲載入嘅 .so 下次再試（之前 latch 死要等 reboot）。
            sInitOk = ok;
            sInitDone = ok;
            return ok;
        }
    }

    private static java.lang.reflect.Method methodFor(String method) {
        if ("ledSetHead".equals(method)) return sHead;
        if ("ledSetEye".equals(method)) return sEye;
        if ("ledSetMouth".equals(method)) return sMouth;
        if ("ledSetOn".equals(method)) return sOn;
        if ("ledSetOFF".equals(method)) return sOff;
        return null;
    }

    // 全局驅動鎖：disco 線程／pad 燈線程／HTTP 線程／wifi 燈／嘴燈全部經呢度打
    // /dev/led_eye，舊驅動疑似頂唔順併發 open（幾次硬 hang 無 ANR、adb 齊死
    // 都係 disco 高頻期）。一齊排隊，一次一個，慢幾 ms 好過死機。
    // 注意：任何直接掂 LedControl（open→ioctl→close）嘅路都要經呢個鎖，
    // 唔可以自己另起爐灶，否則同排緊隊嘅打燈撞 open 即有機會 wedge 成部機。
    private static final Object DRIVER_LOCK = new Object();

    /** 把一段直接掂 LedControl（open→ioctl→close）嘅 code 包入全局驅動鎖，
     *  同所有經 callLedReflect 嘅打燈排同一條隊。鎖淨係包 ioctl 本身，
     *  重試 sleep 留喺出面，唔好塞住其他燈。r 掟出嚟嘅 Throwable 照 propagate。 */
    public static void runExclusive(Runnable r) {
        synchronized (DRIVER_LOCK) {
            r.run();
        }
    }

    private static boolean callLedReflect(String method, Object[] args, String desc) {
        synchronized (DRIVER_LOCK) {
            return callLedReflectLocked(method, args, desc);
        }
    }

    // -- 批量 ioctl ---------------------------------------------------------
    // 一次 open → N 個 ioctl → 一次 close（同一個 DRIVER_LOCK 內）。
    // 慳 fd 開關＋反射，成組燈一次過換，中間無黑場——pad 獨立熄（OFF＋補燈）、
    // disco 每轉多燈齊推用，後者仲順手減輕驅動負載。全部成功先回 true；
    // 衰一步即停（後面唔做，唔 hammer wedged 緊嘅驅動），回 false。
    // state mirror 照逐個記（最後一個贏，同逐個打一致）。
    public static final class LedOp {
        final String method;
        final Object[] args;
        final String desc;
        private LedOp(String method, Object[] args, String desc) {
            this.method = method;
            this.args = args;
            this.desc = desc;
        }
        public static LedOp off() {
            return new LedOp("ledSetOFF", new Object[]{0}, "ledSetOFF");
        }
        public static LedOp head(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
            return new LedOp("ledSetHead",
                    new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
        }
        public static LedOp eye(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
            return new LedOp("ledSetEye",
                    new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
        }
        public static LedOp mouth(int runTime, int breathe, int off, int play, int mode) {
            return new LedOp("ledSetMouth",
                    new Object[]{runTime, breathe, off, play, mode},
                    "mouth mode=" + mode + " breathe=" + breathe + "ms");
        }
        public static LedOp on(int index) {
            return new LedOp("ledSetOn", new Object[]{index}, "ledSetOn " + index);
        }
    }

    public static boolean runBatch(java.util.List<LedOp> ops) {
        synchronized (DRIVER_LOCK) {
            try {
                if (!ensureInit()) return false;
                Object openRes = sOpen.invoke(null);
                if (openRes instanceof Boolean && !(Boolean) openRes) {
                    Log.w(TAG, "LedControl.open() failed for batch x" + ops.size());
                    return false;
                }
                boolean allOk = true;
                try {
                    for (int oi = 0; oi < ops.size(); oi++) {
                        LedOp op = ops.get(oi);
                        java.lang.reflect.Method m = methodFor(op.method);
                        if (m == null) {
                            Log.w(TAG, "LED method not found: " + op.method);
                            allOk = false;
                            break;
                        }
                        Object r = m.invoke(null, op.args);
                        boolean raw = r instanceof Boolean ? (Boolean) r : false;
                        // 同 callLedReflectLocked 一樣：返回值係反嘅（成功＝0/false）。
                        boolean ok = !raw;
                        Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + op.desc + " raw=" + raw);
                        if (!ok) {
                            allOk = false;
                            break;
                        }
                        recordOp(op);
                        // OFF 會連咀通道一齊清——即刻同一個 open 入面補返
                        // （之前呼吸緊先補；後面有顯式 mouth op 嗰陣唔使，佢會覆寫）。
                        // 唔補嘅話個咀永久黑（disco 變先推、TTS 一次過，唔會自己返嚟）。
                        if ("ledSetOFF".equals(op.method) && !hasMouthOpLater(ops, oi)) {
                            restoreMouthLocked();
                        }
                    }
                } finally {
                    if (sClose != null) {
                        try { sClose.invoke(null); } catch (Exception ignore) {}
                    }
                }
                return allOk;
            } catch (Throwable t) {
                Log.w(TAG, "LED batch exception: " + t.getMessage());
                return false;
            }
        }
    }

    /** batch 入面 OFF 後面仲有冇顯式 mouth op（有就唔使自動補）。 */
    private static boolean hasMouthOpLater(java.util.List<LedOp> ops, int idx) {
        for (int i = idx + 1; i < ops.size(); i++) {
            if ("ledSetMouth".equals(ops.get(i).method)) return true;
        }
        return false;
    }

    /** 同一個 open 入面補返個咀（之前呼吸緊先做；record 唔郁）。
     *  出面（burst／setOff）嗰啲自己 open／close，行下面個 public 版。 */
    private static void restoreMouthLocked() {
        if (sMouth == null || sMouthMode != 1) return;
        try {
            Object r = sMouth.invoke(null,
                    (Object[]) new Object[]{sMouthRun, sMouthBreathe, sMouthOffDur, sMouthPlay, sMouthMode});
            boolean raw = r instanceof Boolean ? (Boolean) r : false;
            Log.d(TAG, (!raw ? "LED OK: " : "LED FAIL: ") + "mouth restore mode=1 raw=" + raw);
        } catch (Throwable t) {
            Log.w(TAG, "LED mouth restore exception: " + t.getMessage());
        }
    }

    /** 出面用：全局 OFF 之後補返個咀（之前呼吸緊先做）。自己開關一次。 */
    public static boolean restoreMouthAfterOff() {
        synchronized (DRIVER_LOCK) {
            try {
                if (!ensureInit()) return false;
                Object openRes = sOpen.invoke(null);
                if (openRes instanceof Boolean && !(Boolean) openRes) return false;
                try {
                    restoreMouthLocked();
                    return true;
                } finally {
                    if (sClose != null) {
                        try { sClose.invoke(null); } catch (Exception ignore) {}
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "LED mouth restore exception: " + t.getMessage());
                return false;
            }
        }
    }

    /** batch 成功嗰陣逐個記 mirror（同逐個打一致；OFF 清頭+眼，唔掂咀 record）。 */
    private static void recordOp(LedOp op) {
        if ("ledSetOFF".equals(op.method)) {
            recordHeadOff();
            recordEyeOff();
        } else if ("ledSetHead".equals(op.method)) {
            recordHead((Integer) op.args[0], (Integer) op.args[1],
                    (Integer) op.args[2], (Integer) op.args[3]);
        } else if ("ledSetEye".equals(op.method)) {
            recordEye((Integer) op.args[0], (Integer) op.args[1],
                    (Integer) op.args[2], (Integer) op.args[3]);
        } else if ("ledSetMouth".equals(op.method)) {
            recordMouth((Integer) op.args[0], (Integer) op.args[1], (Integer) op.args[2],
                    (Integer) op.args[3], (Integer) op.args[4]);
        }
    }

    private static boolean callLedReflectLocked(String method, Object[] args, String desc) {
        try {
            if (!ensureInit()) return false;
            java.lang.reflect.Method m = methodFor(method);
            if (m == null) {
                Log.w(TAG, "LED method not found: " + method);
                return false;
            }
            Object openRes = sOpen.invoke(null);
            if (openRes instanceof Boolean && !(Boolean) openRes) {
                Log.w(TAG, "LedControl.open() failed for " + desc);
                return false;
            }
            boolean raw = false;
            try {
                Object r = m.invoke(null, args);
                raw = r instanceof Boolean ? (Boolean) r : false;
            } finally {
                if (sClose != null) {
                    try { sClose.invoke(null); } catch (Exception ignore) {}
                }
            }
            // libhead_led.so 的返回值是反的：ioctl 成功返回 0(false)，失敗返回 0xF2(true)，見 MouthLedData:100
            boolean ok = !raw;
            Log.d(TAG, (ok ? "LED OK: " : "LED FAIL: ") + desc + " raw=" + raw);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "LED exception " + desc + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * 全參數直驅（p1..p8 原樣透傳）。MainActivity 原來經 binder 調
     * header_ledSetHead5Mic(color, brightness, 31, 31, p5, p6, MAX, p8) /
     * header_ledSetEye5Mic(color, brightness, 255, 255, p5, p6, MAX, p8)，
     * pure-direct 下 p3/p4 保持原值，行為不變。
     */
    public static boolean setHead5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        boolean ok = callLedReflect("ledSetHead",
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "head raw p1=" + p1 + " p8=" + p8);
        if (ok) recordHead(p1, p2, p3, p4);
        return ok;
    }

    public static boolean setEye5MicRaw(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        boolean ok = callLedReflect("ledSetEye",
                new Object[]{p1, p2, p3, p4, p5, p6, p7, p8}, "eye raw p1=" + p1 + " p8=" + p8);
        if (ok) recordEye(p1, p2, p3, p4);
        return ok;
    }

    // 預設：與 MainActivity 的 translateColor 等價
    public static boolean headSolid(int color) {
        return setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
    }
    public static boolean clear() { return setOff(); }

    /**
     * pure-direct 下取代 AIDL stop5MicEarLED / stop5MicEyeLED。
     * 獨立停：空 mask（p3/p4=0）寫返同一個 zone——淨熄自己，
     * 眼／頭／wifi／pad 互不影響。以前行 ledSetOFF（一鑊清晒），
     * 而家要成組熄先用 clear()/setOff()。
     */
    public static boolean stopHead5Mic() {
        boolean ok = setHead5MicRaw(7, 9, 0, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        if (ok) recordHeadOff();
        return ok;
    }
    public static boolean stopEye5Mic() {
        boolean ok = setEye5MicRaw(7, 9, 0, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
        if (ok) recordEyeOff();
        return ok;
    }

    // -- 最後燈態 mirror --------------------------------------------------
    // 硬件 write-only，讀唔返——呢度記低經呢個 class 打出去嘅最後一轉參數，
    // 畀 /api/led/state/get 回前端畫 mirror（逐粒撳試燈用）。淨係成功嗰陣記；
    // -1＝開機以嚟未打過。注意：debugJniLed 直透 raw 唔經呢度，嗰段時間
    // mirror 會滯後（佢收尾多數會送返個 stop，嗰下就會同步返）。
    private static volatile int sHeadP1 = -1;
    private static volatile int sHeadP2 = -1;
    private static volatile int sHeadP3 = -1;
    private static volatile int sHeadP4 = -1;
    private static volatile int sEyeP1 = -1;
    private static volatile int sEyeP2 = -1;
    private static volatile int sEyeP3 = -1;
    private static volatile int sEyeP4 = -1;
    private static volatile int sMouthRun = -1;
    private static volatile int sMouthBreathe = -1;
    private static volatile int sMouthOffDur = -1;
    private static volatile int sMouthPlay = -1;
    private static volatile int sMouthMode = -1;

    private static void recordHead(int p1, int p2, int p3, int p4) {
        sHeadP1 = p1; sHeadP2 = p2; sHeadP3 = p3; sHeadP4 = p4;
    }
    private static void recordEye(int p1, int p2, int p3, int p4) {
        sEyeP1 = p1; sEyeP2 = p2; sEyeP3 = p3; sEyeP4 = p4;
    }
    private static void recordMouth(int runTime, int breathe, int off, int play, int mode) {
        sMouthRun = runTime; sMouthBreathe = breathe; sMouthOffDur = off;
        sMouthPlay = play; sMouthMode = mode;
    }
    private static void recordHeadOff() {
        sHeadP1 = 0; sHeadP2 = 0; sHeadP3 = 0; sHeadP4 = 0;
    }
    private static void recordEyeOff() {
        sEyeP1 = 0; sEyeP2 = 0; sEyeP3 = 0; sEyeP4 = 0;
    }

    /** 最後頭/眼參數（p1-p4）——ledPadSet 獨立熄之後補燈用。
     *  未打過（-1）／熄咗（0）回 null，唔好補。 */
    public static int[] lastHeadParams() {
        if (sHeadP1 <= 0) return null;
        return new int[]{sHeadP1, sHeadP2, sHeadP3, sHeadP4};
    }
    public static int[] lastEyeParams() {
        if (sEyeP1 <= 0) return null;
        return new int[]{sEyeP1, sEyeP2, sEyeP3, sEyeP4};
    }
    /** 最後燈態 JSON（全部 int，無需 escape）：未打過＝-1，熄咗＝0。 */
    public static String lastStateJson() {
        return "{\"ok\":true,\"head\":{\"color\":" + sHeadP1
                + ",\"brightness\":" + sHeadP2
                + ",\"p3\":" + sHeadP3 + ",\"p4\":" + sHeadP4 + "}"
                + ",\"eye\":{\"color\":" + sEyeP1
                + ",\"brightness\":" + sEyeP2
                + ",\"p3\":" + sEyeP3 + ",\"p4\":" + sEyeP4 + "}"
                + ",\"mouth\":{\"runTime\":" + sMouthRun
                + ",\"breatheMs\":" + sMouthBreathe
                + ",\"offMs\":" + sMouthOffDur
                + ",\"playMs\":" + sMouthPlay
                + ",\"mode\":" + sMouthMode + "}}";
    }
}
