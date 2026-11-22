package android.content;

/** 測試用 stub：只含 PanelAuth 用到嘅方法。 */
public interface SharedPreferences {
    String getString(String key, String defValue);

    boolean getBoolean(String key, boolean defValue);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putBoolean(String key, boolean value);

        Editor remove(String key);

        boolean commit();

        void apply();
    }
}
