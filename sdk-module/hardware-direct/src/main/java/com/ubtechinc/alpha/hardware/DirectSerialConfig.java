package com.ubtechinc.alpha.hardware;

/**
 * 1.1.7.3 這一代硬件的串口設備路徑。
 * 3.002 / 2.004 的 APK 硬編碼是 /dev/ttySAC0/1 (三星 Exynos)，
 * 而 RK3288 實機 (Android 5.1.1, 1.1.7.3.20) 用的是 /dev/ttyS0/1。
 * 抽 3.002 的 libserial_port.so 直接用會 open 失敗，所以這裡做映射修正。
 *
 * <p>來源：androguard 掃 1.1.7.3.20 的 AIDL_REFERENCE + 實機 logcat_2026-07-02
 * + 3.002/2.004 的字符串表對比。</p>
 */
public final class DirectSerialConfig {
    private DirectSerialConfig() {}

    // RK3288 (1.1.7.3.20 實機) - dmesg 驗證：ff180000=ttyS0(藍牙), ff190000=ttyS1(胸板 front), ff1b0000=ttyS3(頭板)
    // ro.front.serial=/dev/ttyS1 (getprop 實測 3GKL2EJRDH)
    public static final String CHEST_TTY = "/dev/ttyS1";
    public static final String HEAD_TTY  = "/dev/ttyS3";

    // 兼容 fallback - 3.002 用的 Exynos 路徑 + 舊 RK 的 ttyS0
    public static final String CHEST_TTY_ALT = "/dev/ttySAC0";
    public static final String HEAD_TTY_ALT  = "/dev/ttySAC1";
    public static final String CHEST_TTY_ALT2 = "/dev/ttyS0";
    public static final String HEAD_TTY_ALT2  = "/dev/ttyS0";

    public static final int BAUDRATE = 115200;

    // 2026-09: wire 命令字已收喺本 package 嘅 RobotWire，唔再引用舊 SDK 常數表。
}
