// AIDL for the robot's custom-message service (com.ubtechinc.services.Alpha2XmppServices).
// Method order defines the Binder transaction ids - must match the on-robot service.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2XmppCallBack;

interface IAlpha2XmppListener {
    int registerXmppCallBackListener(String appID, IAlpha2XmppCallBack callBack);
    int unRegisterXmppCallBackListener(IAlpha2XmppCallBack callBack);
    void sendCustomXmppMessage(int type, String appID, String message);
}
