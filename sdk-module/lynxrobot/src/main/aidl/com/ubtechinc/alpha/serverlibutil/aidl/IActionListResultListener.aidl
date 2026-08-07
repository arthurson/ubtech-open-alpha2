// AIDL callback delivering the robot's action list. Confirmed against
// com.ubtechinc.alpha2services_base.3.002.apk by decompiling
// IActionListResultListener$Stub.onTransact().
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.ActionInfo;

interface IActionListResultListener {
    void onGetActionList(int p0, int p1, in ActionInfo[] p2);
}
