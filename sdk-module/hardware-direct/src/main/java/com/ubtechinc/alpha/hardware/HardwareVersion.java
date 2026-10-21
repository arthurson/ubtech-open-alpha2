package com.ubtechinc.alpha.hardware;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 硬件型號偵測：{@code ro.hardware.version}。
 *
 * <p>原廠 Alpha2Services 同一個 property 叫「MIC 固件版本」：
 * {@code alpha2_10005} = Lynx 5-mic（CX20810，LED 經 libhead_led.so，
 * 頭頂鍵經 libhead_key_mgr.so，胸/頭串口 /dev/ttyS1、/dev/ttyS3），
 * {@code alpha2_10002} = 舊款 2-mic（ZL380，LED 經頭板 MCU 串口
 * 0x01/0x02/0x08，頭頂鍵經 1.1.7.3 libNativeClassJni.so，
 * 胸/頭串口 /dev/ttySAC0、/dev/ttySAC1；失敗 fallback ttyS1/ttyS3）。</p>
 *
 * <p>讀取方式同 app 層 {@code com.open.alpha2.SysProps} 一致：先反射
 * {@code android.os.SystemProperties}，失敗再 fallback {@code getprop}。
 * property 係唯讀，讀到就 cache。</p>
 *
 * <p>未知值（例如開發機冇呢個 property）一律當 5-mic，保留現有行為；
 * 只有明確 {@code alpha2_10002} 先行 2-mic 分支。</p>
 */
public final class HardwareVersion {
    public static final String VERSION_5MIC = "alpha2_10005";
    public static final String VERSION_2MIC = "alpha2_10002";

    private static volatile String sVersion;

    private HardwareVersion() {
    }

    /** 原始 property 值；讀唔到回 ""。 */
    public static String get() {
        String v = sVersion;
        if (v == null) {
            v = getProperty("ro.hardware.version", "");
            // 空值唔 cache：極少數早期開機時序未 set，留待下次再讀。
            if (!v.isEmpty()) {
                sVersion = v;
            }
        }
        return v;
    }

    /** 讀 Android system property，空值回 {@code def}（2-mic 串口路徑用）。 */
    public static String getProperty(String key, String def) {
        String v = read(key);
        return v.isEmpty() ? def : v;
    }

    /** 明確 alpha2_10002。 */
    public static boolean is2Mic() {
        return VERSION_2MIC.equals(get());
    }

    /** 非 2-mic（包括 alpha2_10005 同未知值）行原有 5-mic 路徑。 */
    public static boolean is5Mic() {
        return !is2Mic();
    }

    private static String read(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Object v = cls.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            if (v != null) {
                return (String) v;
            }
        } catch (Throwable ignored) {
            // fall through to getprop
        }
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
