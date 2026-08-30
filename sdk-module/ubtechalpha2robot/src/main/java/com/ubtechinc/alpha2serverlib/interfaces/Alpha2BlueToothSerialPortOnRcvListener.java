package com.ubtechinc.alpha2serverlib.interfaces;

/** Receives raw frames read from the Bluetooth serial link. */
public interface Alpha2BlueToothSerialPortOnRcvListener {
   void onListenBlueToothSerialPortRcvData(byte[] bytes, int len);
}
