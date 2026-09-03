package com.ubtechinc.alpha.hardware;

// 2026-09: StaticValue 已由 ubtechalpha2robot module 搬入本 package (RobotWire)。

/**
 * 头板 MCU 直驱：降噪、LED(通过 MCU 命令)、以及 5-mic LED 的 JNI 直驱分支。
 * 1.1.7.3 这一代头板 5-mic 必须走 LedControl JNI (libhead_led.so)，
 * sendCommand 的 LED_EAR/LED_EYE (1/2) 在 5-mic 上已失效，见 AIDL_REFERENCE 3.1。
 */
public final class DirectHeadController {
    private final DirectSerialPort port;

    public DirectHeadController(DirectSerialPort port) { this.port = port; }

    public boolean open() { return port.open(); }
    public void close() { port.close(); }
    public boolean isAvailable() { return port.isAvailable(); }
    public void setFrameListener(DirectSerialPort.OnFrameListener l) { port.addListener(l); }

    /** 降噪：cmd 39, 0=开 1=关 (和直觉相反) */
    public boolean setNoiseReduction(boolean open) {
        return port.send(RobotWire.HEAD_CONTROL_BYPASS, new byte[]{(byte) (open ? 0 : 1)});
    }

    /** 传统耳 LED (非 5-mic 板才有效) */
    public boolean startEarLed(short up, short down, short run) {
        // DeveloperEarLedData 的字节布局复用原 SDK，这里简化透传
        // 1.1.7.3 上 5-mic 板会静默失败，应走 DirectLedController 的 JNI 路径
        byte[] param = new byte[]{(byte)0xFF, (byte)0xFF, 9, (byte)(up>>8),(byte)up, (byte)(down>>8),(byte)down, (byte)(run>>8),(byte)run};
        return port.send(RobotWire.LED_EAR, param);
    }

    public boolean stopEarLed() { return port.send(RobotWire.STOP_CMD, new byte[]{1}); }
    public boolean stopEyeLed() { return port.send(RobotWire.STOP_CMD, new byte[]{0}); }

    public boolean readVersion() { return port.send(RobotWire.HEADER_READ_VERSION, null); }

    /** 原始帧透传（debug/serial/send 用）。pure-direct 下取代 robot.header_sendRawData。 */
    public boolean sendRaw(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length == 0) return false;
        return port.sendRaw(rawFrame);
    }
}
