// AIDL for the robot's Bluetooth serial-port service - a Bluetooth-backed counterpart
// to IAlpha2SerialPortService (chest/head serial). Method declaration order defines the
// Binder transaction ids and must match the on-robot service exactly.
//
// This file did not exist anywhere in this SDK before. Added by decompiling
// IAlpha2BlueToothSerialPortService$Stub.onTransact() from Alpha2Services v1.1.7.3.20
// (20170918171435-5mic) - all 4 methods and their order/signatures below are taken
// directly from that switch, not guessed at. Unlike IAlpha2SerialPortService, this
// interface has no 5-mic LED transaction ids - it stops at sendATCMD.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;

interface IAlpha2BlueToothSerialPortService {
    int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
    boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
    void sendATCMD(String cmd);
}
