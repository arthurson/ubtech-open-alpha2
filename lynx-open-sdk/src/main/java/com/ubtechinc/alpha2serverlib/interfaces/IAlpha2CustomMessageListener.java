package com.ubtechinc.alpha2serverlib.interfaces;

/** Receives an inbound custom message routed to this app by the robot. */
public interface IAlpha2CustomMessageListener {
   void onReceiveMessage(byte[] message);
}
