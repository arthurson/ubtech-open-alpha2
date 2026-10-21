package com.ubtechinc.alpha.hardware;

import android.util.Log;

// 2026-09: StaticValue 已由 ubtechalpha2robot module 搬入本 package (RobotWire)。

/**
 * 頭板 MCU 直驅：降噪、2-mic LED（MCU 串口命令），以及 5-mic LED 的 JNI 直驅分支。
 *
 * <p>1.1.7.3 這一代頭板分兩種：
 * <ul>
 *   <li>5-mic（alpha2_10005）：LED 必須走 LedControl JNI (libhead_led.so)，
 *       sendCommand 的 LED_EAR/LED_EYE (1/2) 已失效。</li>
 *   <li>2-mic（alpha2_10002）：官方 Alpha2RobotApi 反過來用 LED_EAR (0x01)、
 *       LED_EYE (0x02) 同 STOP_CMD (0x08) 直接經頭板 MCU 控制，見
 *       {@link #setEarLed2Mic} / {@link #setEyeLed2Mic}。</li>
 * </ul>
 */
public final class DirectHeadController {
    private static final String TAG = "DirectHeadController";

    private final DirectSerialPort port;

    public DirectHeadController(DirectSerialPort port) { this.port = port; }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** 降噪：cmd 39, 0=開 1=關 (和直覺相反) */
    public boolean setNoiseReduction(boolean open) {
        return port.send(RobotWire.HEAD_CONTROL_BYPASS, new byte[]{(byte) (open ? 0 : 1)});
    }

    /** 直接送一條頭板命令（LAP 幀，SRC=05 DST=00）。 */
    public boolean send(byte cmd, byte[] param) {
        return port.send(cmd, param);
    }

    /**
     * 2-mic 開機頭板初始化：官方 Alpha2Services HeadStartThread 只喺
     * sAlpha2HardwareVertion==0 時跑，串口一 init 完就送 cmd 5（無 payload）
     * 再送 cmd 0x20 [1]。5-mic 唔會行呢段。
     */
    public boolean init2Mic() {
        boolean a = port.send(RobotWire.HEAD_CMD_INIT_2MIC, null);
        boolean b = port.send(RobotWire.HEAD_CMD_READY_2MIC, new byte[]{1});
        Log.i(TAG, "2mic head init: cmd5=" + a + " cmd20[1]=" + b);
        return a && b;
    }

    /**
     * 2-mic 耳燈：官方 {@code Alpha2RobotApi.startEarLED} 2-mic 分支原樣：
     * left/right=-1（兩邊）、bright、up/down/run 時序；color/mode 唔支援
     * （耳燈冇 color 欄位，bright 官方寫死 9，呢度照傳入值）。
     * payload = [0xFF, 0xFF, bright, upHi, upLo, downHi, downLo, runHi, runLo]。
     */
    public boolean setEarLed2Mic(int bright, int upMs, int downMs, int runMs) {
        DeveloperPacketData p = new DeveloperPacketData(9);
        p.putByte((byte) 0xFF);
        p.putByte((byte) 0xFF);
        // 1.1.7.3 官方 2-mic 分支寫死 bright=9（唔理 caller 傳乜）。
        p.putByte((byte) 0x09);
        p.putShort_((short) clamp16(upMs));
        p.putShort_((short) clamp16(downMs));
        p.putShort_((short) clamp16(runMs));
        return port.send(RobotWire.LED_EAR, p.getBuffer());
    }

    /**
     * 2-mic 眼燈：官方 {@code Alpha2RobotApi.startEyeLED} 2-mic 分支原樣：
     * left/right=-1、bright、color (p1)、up/down/run 時序。
     * payload = [0xFF, 0xFF, bright, color, upHi, upLo, downHi, downLo, runHi, runLo]。
     */
    public boolean setEyeLed2Mic(int color, int bright, int upMs, int downMs, int runMs) {
        DeveloperPacketData p = new DeveloperPacketData(10);
        p.putByte((byte) 0xFF);
        p.putByte((byte) 0xFF);
        // 1.1.7.3 官方 2-mic 分支寫死 bright=9。
        p.putByte((byte) 0x09);
        p.putByte((byte) color);
        p.putShort_((short) clamp16(upMs));
        p.putShort_((short) clamp16(downMs));
        p.putShort_((short) clamp16(runMs));
        return port.send(RobotWire.LED_EYE, p.getBuffer());
    }

    /** 2-mic 停耳燈：官方 stopEarLED 送 cmd 0x08 payload [1]。 */
    public boolean stopEarLed2Mic() {
        return port.send(RobotWire.STOP_CMD, new byte[]{1});
    }

    /** 2-mic 停眼燈：官方 stopEyeLED 送 cmd 0x08 payload [0]。 */
    public boolean stopEyeLed2Mic() {
        return port.send(RobotWire.STOP_CMD, new byte[]{0});
    }

    private static int clamp16(int v) {
        if (v < 0) return 0;
        if (v > 0xFFFF) return 0xFFFF;
        return v;
    }

    /** 原始幀透傳（debug/serial/send 用）。pure-direct 下取代 robot.header_sendRawData。 */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
