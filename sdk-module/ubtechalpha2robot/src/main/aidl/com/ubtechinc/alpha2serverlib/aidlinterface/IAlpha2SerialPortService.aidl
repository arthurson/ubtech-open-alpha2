// AIDL for the robot's chest / head serial-port services
// (com.ubtechinc.services.AlphaSerialPortServices and AlphaSerialPortHeaderServices).
// Method order defines the Binder transaction ids - must match the on-robot service.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;

interface IAlpha2SerialPortService {
    int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
    boolean sendRawData(in byte[] data, int nLen);

    // -- 5-mic hardware LED path (bypasses sendCommand's serial frame entirely -
    // goes straight to com.ubtechinc.mic5.LedControl native calls on the robot side).
    // Confirmed via onTransact's sparse-switch on the 1.1.7.3 (5mic) alpha2services
    // build: transaction ids 5-9, in this exact order. Order here MUST match the
    // on-robot service or calls will hit the wrong native method.
    boolean stop5MicEyeLED();                                                        // id 5
    boolean stop5MicEarLED();                                                        // id 6
    boolean ledSetEye5Mic(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8); // id 7
    boolean ledSetHead5Mic(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8); // id 8
    String getRobotSerialNumber();                                                   // id 9
}
