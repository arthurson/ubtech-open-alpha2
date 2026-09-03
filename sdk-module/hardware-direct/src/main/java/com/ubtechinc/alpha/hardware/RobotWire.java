package com.ubtechinc.alpha.hardware;

/**
 * 2026-09: 由 sdk-module/ubtechalpha2robot 的 StaticValue 搬入（脫離
 * Alpha2OpenSdk 的一部分）。只收錄 pure-direct 下仲有用嘅 wire 值，
 * 其他（全部為已不存在嘅 alpha2services binder/broadcast 設計嘅常數）
 * 隨舊 module 一齊刪除。
 *
 * <p>每一個值都係同機身 MCU／係統 broadcast 嘅 wire contract，必須逐 byte
 * 一致，唔可以改。
 */
public final class RobotWire {
    private RobotWire() {
    }

    // -- Broadcast action strings (第三方 app 接收用) --------------------------
    public static final String CHEST_ACTION = "com.ubtechinc.services.chest";
    public static final String SONAR_DISTANCE_ACTION = "com.ubtechinc.sonar.distance";
    public static final String SONAR_DISTANCE_EXTRA = "sonar_distance";
    public static final String ALPHA_BT_CONNECTION = "com.ubtechinc.services.bluetooth";
    public static final String ALPHA_QR_CODE = "com.ubt.alpha2.qr_code";
    public static final String ALPHA_WIFI_RESULT = "com.ubt.alpha2.wifiresult";
    public static final String ALPHA_SET_CHARGE_PLAY = "com.ubtechinc.services.SET_CHARGE_PLAY";

    // -- Chest microcontroller serial command bytes ---------------------------
    public static final byte CHES_CMD_MOTORANGLE = 5;
    public static final byte CHEST_CMD_SETTING = 4;
    public static final byte CHES_CMD_START_UPDATE = 48;
    public static final byte CHES_CMD_UPDATE_PAGE = 49;
    public static final byte CHES_CMD_UPDATE_END = 50;
    public static final byte CHEST_READ_VERSION = 51;
    public static final byte CHEST_SET_ALL_ANGLE = 52;
    /** 群舵機播放幀：a.m 私有發送原文 cmd 3 [20軸byte + short time]（smali 實證，勿與 52 混用）。 */
    public static final byte CHEST_CMD_SENDMOTOR = 3;
    public static final byte CHEST_READ_SID_EEPROM = 55;
    public static final byte CHEST_POWER_SAVE = 64;

    // -- Head microcontroller serial command bytes ----------------------------
    public static final byte LED_EAR = 1;
    public static final byte STOP_CMD = 8;
    public static final byte HEAD_CONTROL_BYPASS = 39;
    public static final byte HEADER_READ_VERSION = 51;
}
