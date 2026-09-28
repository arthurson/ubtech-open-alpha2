package com.ubtechinc.alpha.hardware;

// 2026-09: StaticValue 已由 ubtechalpha2robot module 搬入本 package (RobotWire)。

/**
 * 頭板 MCU 直驅：降噪、LED(通過 MCU 命令)、以及 5-mic LED 的 JNI 直驅分支。
 * 1.1.7.3 這一代頭板 5-mic 必須走 LedControl JNI (libhead_led.so)，
 * sendCommand 的 LED_EAR/LED_EYE (1/2) 在 5-mic 上已失效，見 AIDL_REFERENCE 3.1。
 */
public final class DirectHeadController {
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

    /** 原始幀透傳（debug/serial/send 用）。pure-direct 下取代 robot.header_sendRawData。 */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
