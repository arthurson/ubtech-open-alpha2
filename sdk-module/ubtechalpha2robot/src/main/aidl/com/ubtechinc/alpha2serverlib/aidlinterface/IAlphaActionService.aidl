// AIDL for the robot's action-playback service (com.ubtechinc.services.AlphaActionServices).
// Method declaration order defines the Binder transaction ids and must match the
// on-robot service exactly.
//
// Verified against Alpha2Services v1.1.7.3.20 (20170918171435-5mic) by decompiling
// IAlphaActionService$Stub.onTransact() - two trailing methods were missing from this
// file entirely: disableActionPlay and isActioning, both appended below in their
// confirmed on-robot order (after getActionList).
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionClient;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionListListener;

interface IAlphaActionService {
    int registerActionClient(IAlphaActionClient client);
    void unRegisterActionClient(IAlphaActionClient client);
    boolean playActionFile(String strActionFile);
    boolean playActionName(String strActionName);
    void stopActionPlay();
    void onEventHandlerTrigger(int nEventType, in byte[] param);
    boolean isCompleted();
    void getActionList(IAlphaActionListListener listener);
    void disableActionPlay(boolean disable);
    boolean isActioning();
}
