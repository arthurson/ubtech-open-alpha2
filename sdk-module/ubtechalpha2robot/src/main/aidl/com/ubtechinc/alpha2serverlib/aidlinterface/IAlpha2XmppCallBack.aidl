// AIDL callback for inbound custom (XMPP-style) messages routed by the robot.
// Wire contract with the on-robot message service - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2XmppCallBack {
    void onReceiveMessage(String message);
}
