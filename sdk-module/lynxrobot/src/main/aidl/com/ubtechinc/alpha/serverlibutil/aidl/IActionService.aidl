// AIDL for the robot's action-playback service. Method declaration order defines the
// Binder transaction ids and must match the on-robot service exactly. Confirmed against
// com.ubtechinc.alpha2services_base.3.002.apk by decompiling IActionService$Stub.onTransact():
//   TRANSACTION_getActionList = 1
//   TRANSACTION_playAction = 2
//   TRANSACTION_playActionFile = 3
//   TRANSACTION_stopAction = 4
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.IActionListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener;

interface IActionService {
    void getActionList(IActionListResultListener p0);
    void playAction(String p0, IActionResultListener p1);
    void playActionFile(String p0, IActionResultListener p1);
    void stopAction(IActionResultListener p0);
}
