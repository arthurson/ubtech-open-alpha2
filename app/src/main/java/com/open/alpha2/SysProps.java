package com.open.alpha2;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Android system property 讀取（例：ro.hardware.version）。
 *
 * 正常路行反射 android.os.SystemProperties（hidden API；Android 5.1 無
 * greylist 攔截）；反射唔得先 fallback 落 getprop（同原廠
 * com.ubtechinc.mic5.a 做法一樣）。
 */
public final class SysProps {
    private SysProps() {
    }

    /** 讀 property；讀唔到回 ""（同 getprop 行為一致）。 */
    public static String get(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Object v = cls.getMethod("get", String.class, String.class).invoke(null, key, "");
            if (v != null) {
                return (String) v;
            }
        } catch (Throwable ignored) {
            // fall through to getprop
        }
        return execGetprop(key);
    }

    private static String execGetprop(String key) {
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "getprop", key });
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

    /**
     * ro.hardware.version：alpha2_10005（Lynx 5MIC，CX20810 硬件）或
     * alpha2_10002（舊款，ZL380 硬件）。
     *
     * 原廠叫呢個做「MIC 固件版本」（com.ubtechinc.alpha.utils.Constants.
     * ALPHA_MIC_HARDWARE_VERSION；SysUtils.getMicVersion()／AIDL
     * ISysService.getMICVersion()／系統信息畫面都係讀佢；is5Mic() 比對
     * "alpha2_10005"）。
     */
    public static String hardwareVersion() {
        return get("ro.hardware.version");
    }
}
