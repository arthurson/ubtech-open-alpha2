// AIDL for the robot's XMPP messaging service.
// Re-verified against this APK's Stub: exactly 3 transactions (ids 1-3). This
// interface's method names were not proguard-obfuscated in this build, so they're
// taken directly from the Stub/Proxy dispatch code, not inferred.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2XmppCallBack;

interface IAlpha2XmppListener {
    int registerXmppCallBackListener(String appID, IAlpha2XmppCallBack callBack);
    int unRegisterXmppCallBackListener(IAlpha2XmppCallBack callBack);
    void sendCustomXmppMessage(int type, String appID, String message);
}
