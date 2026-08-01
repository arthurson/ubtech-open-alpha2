// AIDL for the robot's chest / head serial-port services
// (com.ubtechinc.services.AlphaSerialPortServices and AlphaSerialPortHeaderServices).
// Method declaration order defines the Binder transaction ids - must match the on-robot
// service exactly.
//
// Re-verified against this app's IAlpha2SerialPortService$Stub.onTransact() directly:
// the sparse-switch there has exactly 5 cases (transaction ids 1-5). Methods 4 and 5
// have no recoverable original names (interface/implementation are proguard-obfuscated
// down to single letters and generic dispatch code), so they're named descriptively
// below based on their confirmed parameter/return signatures - do not treat those two
// names as authoritative, only the signatures and ordering are.
//
// The previous version of this file additionally declared a 5-mic LED block
// (stop5MicEyeLED, stop5MicEarLED, ledSetEye5Mic, ledSetHead5Mic) and a
// getRobotSerialNumber() at ids 5-9. None of that exists in this build's Stub -
// onTransact's switch stops at id 5, and id 5 itself takes (String, int) and returns
// boolean, not a no-arg String getter. Removed.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;

interface IAlpha2SerialPortService {
    int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
    boolean sendRawData(in byte[] data, int nLen);
    // signature-confirmed, name not recoverable (obfuscated) - id 5
    boolean sendCommandString(String cmd, int nLen);
}
