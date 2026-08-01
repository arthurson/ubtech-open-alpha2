// AIDL for the robot's Bluetooth-based serial-port service.
// Re-verified directly against this APK's IAlpha2BlueToothSerialPortService$Stub -
// exactly 4 transactions (ids 1-4), Stub-only (no client-side Proxy in this build,
// i.e. nothing in this APK calls it cross-process - it's likely bound only from
// within the same process or by a component not present here).
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;

interface IAlpha2BlueToothSerialPortService {
    int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
    void sendATCMD(String cmd);
}
