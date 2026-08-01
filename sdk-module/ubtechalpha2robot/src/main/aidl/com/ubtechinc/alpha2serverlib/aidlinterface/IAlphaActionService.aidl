// AIDL for the robot's action-playback service (com.ubtechinc.services.AlphaActionServices).
// Method declaration order defines the Binder transaction ids and must match the
// on-robot service exactly.
//
// Re-verified against this app's IAlphaActionService$Stub.onTransact() directly: the
// sparse-switch there has exactly 9 cases (transaction ids 1-9). The previous version
// of this file appended two trailing methods (disableActionPlay, isActioning) at ids
// 9-10; only one exists here. Id 9 takes a single boolean and its implementation just
// stores it into a boolean field (confirmed by decompiling the implementation), which
// matches a "disable" flag setter, not a query - kept as disableActionPlay and
// isActioning removed since there's no 10th transaction in this build's Stub at all.
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
}
