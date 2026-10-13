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

    // -- Chest microcontroller serial command bytes ---------------------------
    public static final byte CHES_CMD_MOTORANGLE = 5;
    public static final byte CHEST_CMD_SETTING = 4;
    public static final byte CHES_CMD_START_UPDATE = 48;
    public static final byte CHES_CMD_UPDATE_PAGE = 49;
    public static final byte CHES_CMD_UPDATE_END = 50;
    public static final byte CHEST_READ_VERSION = 51;
    /** 已死：播放唔行呢個（行 cmd 3），留低只防同 52 撞名，唔好用。 */
    @Deprecated
    public static final byte CHEST_SET_ALL_ANGLE = 52;
    /** 讀舵機 trim/cmd13（DirectChestController.readServo）。 */
    public static final byte CHEST_CMD_READ_SERVO = 13;
    /** 寫舵機 trim/cmd12（DirectChestController.writeServoTrim，EEPROM 掉電保持）。 */
    public static final byte CHEST_CMD_WRITE_TRIM = 12;
    /** 群舵機播放幀：a.m 私有發送原文 cmd 3 [20軸byte + short time]（smali 實證，勿與 52 混用）。 */
    public static final byte CHEST_CMD_SENDMOTOR = 3;
    /** 修改舵機 ID/cmd10（DirectChestController.modifyServoId，payload [舊id][新id]，servo 碼 205）。 */
    public static final byte CHEST_CMD_MODIFY_SERVO_ID = 10;
    /** 讀舵機韌體版本/cmd14（DirectChestController.readMotorVersion，payload [id]，胸板經 FC CF 轉發 servo 碼 1）。 */
    public static final byte CHEST_CMD_READ_MOTOR_VERSION = 14;
    /** 讀舵機硬件資訊/cmd33（DirectChestController.readMotorHardware，payload [id]，servo 碼 18）。 */
    public static final byte CHEST_CMD_READ_MOTOR_HARDWARE = 33;
    /** 舵機韌體升級開始/cmd16（DirectChestController.startMotorUpdate，payload [id][len BE32]，推斷見 ServoUpgrade）。 */
    public static final byte CHEST_CMD_UPGRADE_MOTOR_START = 16;
    /** 舵機韌體升級資料頁/cmd17（DirectChestController.updateMotorPage，payload [id][0x01][seqHi][seqLo][64B]）。 */
    public static final byte CHEST_CMD_UPGRADE_MOTOR_PAGE = 17;
    /** 舵機韌體升級結果上行碼 134 [status][id]（status 0 = 成功）。 */
    public static final int CHEST_EVENT_MOTOR_UPGRADE = 134;
    public static final byte CHEST_READ_SID_EEPROM = 55;
    public static final byte CHEST_POWER_SAVE = 64;

    // -- Head microcontroller serial command bytes ----------------------------
    public static final byte LED_EAR = 1;
    public static final byte STOP_CMD = 8;
    public static final byte HEAD_CONTROL_BYPASS = 39;
    public static final byte HEADER_READ_VERSION = 51;
}
