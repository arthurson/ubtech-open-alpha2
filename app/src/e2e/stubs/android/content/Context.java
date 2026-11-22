package android.content;

/** 測試用 stub：只含 PanelAuth 用到嘅方法。 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract SharedPreferences getSharedPreferences(String name, int mode);

    public Context getApplicationContext() {
        return this;
    }
}
