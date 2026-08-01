// Callback AIDL for incoming XMPP messages.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2XmppCallBack {
    void onReceiveMessage(String message);
}
