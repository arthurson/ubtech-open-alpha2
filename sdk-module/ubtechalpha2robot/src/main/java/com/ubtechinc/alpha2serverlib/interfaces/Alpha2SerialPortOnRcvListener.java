package com.ubtechinc.alpha2serverlib.interfaces;

/** Receives raw frames read from the chest serial link. */
public interface Alpha2SerialPortOnRcvListener {
   void onListenSerialPortRcvData(byte[] bytes, int len);
}
