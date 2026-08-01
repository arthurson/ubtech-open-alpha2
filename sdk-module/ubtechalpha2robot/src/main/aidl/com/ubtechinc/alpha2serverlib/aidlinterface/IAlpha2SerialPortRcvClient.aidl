// Callback AIDL for serial-port received data, used by both
// IAlpha2BlueToothSerialPortService and IAlpha2SerialPortService.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2SerialPortRcvClient {
    void onListenSerialPortRcvData(in byte[] bytes, int len);
}
