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

    /** 0-255 鉗位（防靜默截 byte wrap 猛扯舵機）。 */
    private static int clampAngle(int v) {
        if (v < 0) { android.util.Log.w("DirectChest", "angle clamp 0 (was " + v + ")"); return 0; }
        if (v > 255) { android.util.Log.w("DirectChest", "angle clamp 255 (was " + v + ")"); return 255; }
        return v;
    }

    /** time 20-32767 鉗位（spec 上限，見 servo/one）。 */
    private static short clampTime(int time) {
        if (time < 20) return 20;
        if (time > 32767) return 32767;
        return (short) time;
    }

    /** 0-255 靜默鉗位（聲納／群舵機用，唔 log）。 */
    private static int clampByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** 單舵機：用本 package 的 DeveloperPacketData 打包，保持與原廠完全一致 */
    public boolean setSingleServo(byte id, int angle, short time) {
        if (id < 1 || id > 22) return false;
        // 角度單位同 servo/one 一致（0-255；MCP schema 同 spec 上限）：唔鉗位直接
        // 截 byte 會 wrap 猛扯。time 照舊下限 20，上限跟 spec 32767。
        angle = clampAngle(angle);
        time = clampTime(time);
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
     * 兼容 20 或 22 軸：22 時前 20 走 cmd3，21/22 各補一發 cmd05（同 time）。
     */
    public boolean playAllServos(int[] angles, short time) {
        if (angles == null || (angles.length != 20 && angles.length != 22)) return false;
        time = clampTime(time);
        int[] first20 = angles.length == 20 ? angles : java.util.Arrays.copyOf(angles, 20);
        DeveloperPacketData p = new DeveloperPacketData(22);
        for (int a : first20) {
            p.putByte((byte) clampByte(a));
        }
        p.putShort_(time);
        boolean ok = port.send(RobotWire.CHEST_CMD_SENDMOTOR, p.getBuffer());
        if (angles.length == 22) {
            boolean ok21 = setSingleServo((byte) 21, angles[20], time);
            boolean ok22 = setSingleServo((byte) 22, angles[21], time);
            return ok && ok21 && ok22;
        }
        return ok;
    }

    /**
     * 全舵機 20/22 個角度。注意：cmd 52 有 ACK 但舵机不动（旧实测），故播放/全組
     * 位姿一律走 cmd 3（dance 通道，a.m 私有发送原文）。單舵機微調則走 cmd 5
     *（setSingleServo，2026-09-06 官方 PC tuner 實測可郁，用戶目視確認）。
     * 位姿追踪见 {@link ServoPoseTracker}。
     */
    public boolean setAllServos(int[] angles, short time) {
        return playAllServos(angles, time);
    }

    /** 聲納配置：1.1.7.3 正確值為 subCmd=10, 距離 cm（byte 範圍，超限鉗 0-255 防 wrap） */
    public boolean configureSonar(int distanceCm) {
        return port.send(RobotWire.CHEST_CMD_SETTING, new byte[]{10, (byte) clampByte(distanceCm)});
    }

    /** PIR 使能：cmd=72，實測 1.1.7.3 固件有效 */
    public boolean setPirEnabled(boolean enabled) {
        return port.send((byte) 72, new byte[]{(byte) (enabled ? 1 : 0)});
    }

    /** 讀舵機角度 (cmd 13) - 回幀走 OnFrameListener 解析 */
    public boolean readServo(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_SERVO, new byte[]{servoId});
    }

    /**
     * 寫舵機 trim/偏差 (cmd 12, param [id][BE16 signed])。2026-09-06 官方 PC
     * tuner 實測還原：editor 約每分鐘自動全組寫一次，值≈3×(命令角度−home)；
     * 回覆 {@code 09 05 00 0c 00 <id>} = OK，{@code 01 <id>} = 該軸無回授。
     * 寫入掉電保持（官方寫入值橫跨多次重啟照讀返，見 2026-09-06 分析），即
     * chest EEPROM——亂寫會改出廠校準，調用方須經用戶明確寫入動作先好 call。
     */
    public boolean writeServoTrim(byte servoId, int trim) {
        if (servoId < 1 || servoId > 22) return false;
        if (trim < -32768) trim = -32768;
        if (trim > 32767) trim = 32767;
        byte[] p = new byte[]{(byte) servoId, (byte) ((trim >> 8) & 0xFF), (byte) (trim & 0xFF)};
        return port.send(RobotWire.CHEST_CMD_WRITE_TRIM, p);
    }

    /**
     * 修改舵機 ID (cmd 10, payload [舊id][新id]，胸板再經 servo 碼 205 轉發落 bus)。
     * 寫入舵機 EEPROM 掉電保持——調用方須經用戶明確寫入動作先好 call。
     * 注意：同一 bus 上唔可以有兩粒同 ID，改之前確保目標新 ID 無被佔用。
     */
    public boolean modifyServoId(byte oldId, byte newId) {
        if (oldId < 1 || oldId > 22) return false;
        if (newId < 1 || newId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_MODIFY_SERVO_ID, new byte[]{oldId, newId});
    }

    /**
     * 讀舵機韌體版本 (cmd 14, payload [id])。胸板經 FC CF 頭轉發 servo 碼 1 落 bus，
     * 回覆經 OnFrameListener 以 cmd=14 幀送回，由 ChestQuery 認領／解析。
     * 純讀，唔寫任何 EEPROM。
     */
    public boolean readMotorVersion(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_MOTOR_VERSION, new byte[]{servoId});
    }

    /**
     * 讀舵機硬件資訊 (cmd 33, payload [id]，servo 碼 18)。
     * 回覆經 OnFrameListener 以 cmd=33 幀送回，由 ChestQuery 認領／解析。
     * 純讀，唔寫任何 EEPROM。
     */
    public boolean readMotorHardware(byte servoId) {
        if (servoId < 1 || servoId > 22) return false;
        return port.send(RobotWire.CHEST_CMD_READ_MOTOR_HARDWARE, new byte[]{servoId});
    }

    /**
     * 開始舵機韌體升級 (cmd 16, payload [id][len BE32])。payload 格式係推斷
     * （見 ServoUpgrade：胸板 cmd48 同款 [len BE32]＋全部舵機命令 id 行頭），
     * 官方 upgrade APK 無舵機升級實現，真機未驗證。
     * 注意：START 之後 chest／servo 可能已入升級態，調用方須成個流程跟到底。
     */
    public boolean startMotorUpdate(byte servoId, int fileLen) {
        if (servoId < 1 || servoId > 22) return false;
        if (fileLen <= 0) return false;
        byte[] p = new byte[5];
        p[0] = servoId;
        p[1] = (byte) ((fileLen >> 24) & 0xFF);
        p[2] = (byte) ((fileLen >> 16) & 0xFF);
        p[3] = (byte) ((fileLen >> 8) & 0xFF);
        p[4] = (byte) (fileLen & 0xFF);
        return port.send(RobotWire.CHEST_CMD_UPGRADE_MOTOR_START, p);
    }

    /**
     * 舵機韌體升級資料頁 (cmd 17, payload [id][0x01][seqHi][seqLo][64B])。
     * 64B 頁對應舵機 bootloader 嘅 64-byte flash 頁（112 頁＝7168B app 區，
     * 白牌 mega8 反彙編實證）；bank 1＝寫頁（2 保留，見 ServoUpgrade）。
     * data 不足 64B 時調用方先補零（doc 話補零至 68 byte）。
     */
    public boolean updateMotorPage(byte servoId, int seq, byte[] data64) {
        if (servoId < 1 || servoId > 22) return false;
        if (data64 == null || data64.length > 64 || seq < 0 || seq > 0xFFFF) return false;
        byte[] p = new byte[68];
        p[0] = servoId;
        p[1] = 0x01;
        p[2] = (byte) ((seq >> 8) & 0xFF);
        p[3] = (byte) (seq & 0xFF);
        System.arraycopy(data64, 0, p, 4, data64.length);
        return port.send(RobotWire.CHEST_CMD_UPGRADE_MOTOR_PAGE, p);
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

    /** 官方升級請求 ID（upgrade APK Alpha2SerialProtocol 请求一律 ID=1；
     *  05 係 LAP 頭，升級誤用會令 MCU 回 NAK——之前假 success 即咁嚟）。 */
    public static final byte UPGRADE_ID = 1;

    /** 開始升級 chest：cmd 48 + 4B fileLen 大端（fileLen 必須 >0，負數編出嚟
     *  MCU 會當巨文件，有變磚風險，直接拒）。index 須 1..127（官方 getUnusedIndex
     *  從不用 0，ChestUpgrade 滾動分配）。 */
    public boolean startUpdate(int fileLen, int index) {
        if (fileLen <= 0) return false;
        byte[] p = new byte[4];
        p[0] = (byte) ((fileLen >> 24) & 0xFF);
        p[1] = (byte) ((fileLen >> 16) & 0xFF);
        p[2] = (byte) ((fileLen >> 8) & 0xFF);
        p[3] = (byte) (fileLen & 0xFF);
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_START_UPDATE, p);
    }

    public boolean updatePage(byte[] pageData, int pageLen, int index) {
        // pageLen 無檢查會 NegativeArraySize/OOM/arraycopy 越界，壞包直落 MCU。
        if (pageData == null || pageLen < 0 || pageLen > pageData.length) return false;
        if (pageLen == 0) return false;
        byte[] p = new byte[pageLen + 2];
        p[0] = (byte) ((pageLen >> 8) & 0xFF);
        p[1] = (byte) (pageLen & 0xFF);
        System.arraycopy(pageData, 0, p, 2, pageLen);
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_UPDATE_PAGE, p);
    }

    public boolean endUpdate(byte[] md5_16, int index) {
        if (md5_16 == null || md5_16.length != 16) return false;
        return port.sendUpgrade(UPGRADE_ID, (byte) index, RobotWire.CHES_CMD_UPDATE_END, md5_16);
    }

    /** 原始幀透傳（debug/serial/send、set_uuid、version fallback 用）。pure-direct 下取代 robot.chest_sendRawData。 */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
