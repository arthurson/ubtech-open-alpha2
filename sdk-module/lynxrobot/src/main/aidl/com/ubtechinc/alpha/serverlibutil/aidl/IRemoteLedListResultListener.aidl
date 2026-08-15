// Confirmed against com.ubtechinc.alpha2services_base.3.002.apk.
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.LedInfo;

interface IRemoteLedListResultListener {
    void onGetLedList(int p0, int p1, in List<LedInfo> p2);
}
