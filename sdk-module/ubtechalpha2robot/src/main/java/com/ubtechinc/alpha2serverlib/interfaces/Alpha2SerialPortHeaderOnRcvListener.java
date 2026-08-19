package com.ubtechinc.alpha2serverlib.interfaces;

/** Receives raw frames read from the head serial link. */
public interface Alpha2SerialPortHeaderOnRcvListener {
   void onListenSerialPortHeaderRcvData(byte[] bytes, int len);
}
