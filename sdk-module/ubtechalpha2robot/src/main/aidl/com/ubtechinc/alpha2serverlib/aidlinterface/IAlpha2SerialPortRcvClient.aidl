// AIDL callback delivering raw bytes received on a robot serial link.
// Wire contract with the on-robot serial services - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2SerialPortRcvClient {
    void onListenSerialPortRcvData(in byte[] bytes, int len);
}
