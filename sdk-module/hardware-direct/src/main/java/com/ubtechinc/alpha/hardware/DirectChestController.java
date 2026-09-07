package com.ubtechinc.alpha.hardware;

// 2026-09: StaticValue/DeveloperPacketData 已由 ubtechalpha2robot module 搬入
// 本 package (RobotWire/DeveloperPacketData)，不再依賴 SDK。

/**
 * 胸板 MCU 的直驅封裝：舵機、聲納、PIR、固件升級。
 * 協議來自 1.1.7.3.20 的 StaticValue + AIDL_REFERENCE 第5章，
 * 但走 DirectSerialPort 而不是 Alpha2SerialServiceUtil 的 binder。
 * 正常中文注釋：便於後續維護。
 */
public final class DirectChestController {
    private final DirectSerialPort port;

    public DirectChestController(DirectSerialPort port) { this.port = port; }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** 單舵機：用本 package 的 DeveloperPacketData 打包，保持與原廠完全一致 */
    public boolean setSingleServo(byte id, int angle, short time) {
        if (id < 1 || id > 20) return false;
        if (time < 20) time = 20;
        DeveloperPacketData p = new DeveloperPacketData(5);
        p.putByte(id);
        p.putByte((byte) ((angle >> 8) & 0xFF));
        p.putByte((byte) (angle & 0xFF));
        p.putShort_(time);
        return port.send(RobotWire.CHES_CMD_MOTORANGLE, p.getBuffer());
    }

    /**
     * 群舵機播放幀（跳舞/.ubx）：cmd 3 [20軸byte + short time]，a.m 原文。
     * 注意：setAllServos 的 cmd 52 係另一用途，保持不動，播放一律走此方法。
     */
    public boolean playAllServos(int[] angles20, short time) {
        if (angles20 == null || angles20.length != 20) return false;
        if (time < 20) time = 20;
        DeveloperPacketData p = new DeveloperPacketData(22);
        for (int a : angles20) p.putByte((byte) a);
        p.putShort_(time);
        return port.send(RobotWire.CHEST_CMD_SENDMOTOR, p.getBuffer());
    }

    /**
     * 全舵機 20 個角度。注意：cmd 52 有 ACK 但舵机不动（旧实测），故播放/全组
     * 位姿一律走 cmd 3（dance 通道，a.m 私有发送原文）。單舵機微調則走 cmd 5
     *（setSingleServo，2026-09-06 官方 PC tuner 實測可郁，用戶目視確認）。
     * 位姿追踪见 {@link ServoPoseTracker}。
     */
    public boolean setAllServos(int[] angles20, short time) {
        return playAllServos(angles20, time);
    }

    /** 聲納配置：1.1.7.3 正確值為 subCmd=10, 距離 cm */
    public boolean configureSonar(int distanceCm) {
        return port.send(RobotWire.CHEST_CMD_SETTING, new byte[]{10, (byte) distanceCm});
    }

    /** PIR 使能：cmd=72，實測 1.1.7.3 固件有效 */
    public boolean setPirEnabled(boolean enabled) {
        return port.send((byte) 72, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /** 省電模式 */
    public boolean setPowerSave(boolean enable) {
        return port.send(RobotWire.CHEST_POWER_SAVE, new byte[]{(byte) (enable ? 1 : 0)});
    }

    /** 讀舵機角度 (cmd 13) - 回幀走 OnFrameListener 解析 */
    public boolean readServo(byte servoId) {
        return port.send((byte) 13, new byte[]{servoId});
    }

    /**
     * 寫舵機 trim/偏差 (cmd 12, param [id][BE16 signed])。2026-09-06 官方 PC
     * tuner 實測還原：editor 約每分鐘自動全組寫一次，值≈3×(命令角度−home)；
     * 回覆 {@code 09 05 00 0c 00 <id>} = OK，{@code 01 <id>} = 該軸無回授。
     * 寫入掉電保持（官方寫入值橫跨多次重啟照讀返，見 2026-09-06 分析），即
     * chest EEPROM——亂寫會改出廠校準，調用方須經用戶明確寫入動作先好 call。
     */
    public boolean writeServoTrim(byte servoId, int trim) {
        if (servoId < 1 || servoId > 20) return false;
        if (trim < -32768) trim = -32768;
        if (trim > 32767) trim = 32767;
        byte[] p = new byte[]{(byte) servoId, (byte) ((trim >> 8) & 0xFF), (byte) (trim & 0xFF)};
        return port.send((byte) 12, p);
    }

    /** 讀胸板固件版本 cmd 51 */
    public boolean readVersion() {
        return port.send(RobotWire.CHEST_READ_VERSION, null);
    }

    /** 讀機器人 SN/UUID (chest EEPROM, cmd 55 CHEST_READ_SID_EEPROM)。
     *  pure-direct 下取代經 alpha2services broadcast 查詢 (機身已無此 APK,
     *  robot.requestRobotUUID() 發出的 broadcast 永遠無人回覆, 見 misc/request_uuid)。
     *  無參數: 編碼後 wire 幀為 F8 8F 07 00 00 37 3E ED。回覆經 OnFrameListener
     *  以 cmd=55 (0x37) 幀送回, 由 MainActivity.queryChestRobotUuid() 等待/解析。 */
    public boolean readSidEeprom() {
        return port.send(RobotWire.CHEST_READ_SID_EEPROM, null);
    }

    /** 開始升級 chest：cmd 48 + 4B fileLen 大端 */
    public boolean startUpdate(int fileLen) {
        byte[] p = new byte[4];
        p[0] = (byte) ((fileLen >> 24) & 0xFF);
        p[1] = (byte) ((fileLen >> 16) & 0xFF);
        p[2] = (byte) ((fileLen >> 8) & 0xFF);
        p[3] = (byte) (fileLen & 0xFF);
        return port.send(RobotWire.CHES_CMD_START_UPDATE, p);
    }

    public boolean updatePage(byte[] pageData, int pageLen) {
        byte[] p = new byte[pageLen + 2];
        p[0] = (byte) ((pageLen >> 8) & 0xFF);
        p[1] = (byte) (pageLen & 0xFF);
        System.arraycopy(pageData, 0, p, 2, pageLen);
        return port.send(RobotWire.CHES_CMD_UPDATE_PAGE, p);
    }

    public boolean endUpdate(byte[] md5_16) {
        if (md5_16 == null || md5_16.length != 16) return false;
        return port.send(RobotWire.CHES_CMD_UPDATE_END, md5_16);
    }

    /** 原始幀透傳（debug/serial/send、set_uuid、version fallback 用）。pure-direct 下取代 robot.chest_sendRawData。 */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
