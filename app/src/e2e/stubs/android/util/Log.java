package android.util;

/** 測試用 stub：預設靜音；E2E_LOG=1 先打印。見 app/src/e2e/README.md。 */
public final class Log {
    private static final boolean VERBOSE = System.getenv("E2E_LOG") != null;

    private Log() {}

    private static int out(String lvl, String tag, String msg, Throwable t) {
        if (VERBOSE) {
            System.out.println(lvl + "/" + tag + ": " + msg);
            if (t != null) t.printStackTrace(System.out);
        }
        return 0;
    }

    public static int v(String tag, String msg) { return out("V", tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return out("V", tag, msg, t); }
    public static int d(String tag, String msg) { return out("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable t) { return out("D", tag, msg, t); }
    public static int i(String tag, String msg) { return out("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return out("I", tag, msg, t); }
    public static int w(String tag, String msg) { return out("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return out("W", tag, msg, t); }
    public static int e(String tag, String msg) { return out("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return out("E", tag, msg, t); }
}
