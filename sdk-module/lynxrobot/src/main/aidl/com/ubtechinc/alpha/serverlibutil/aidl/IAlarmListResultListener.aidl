// Confirmed against com.ubtechinc.alpha2services_base.3.002.apk.
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo;

interface IAlarmListResultListener {
    void onQueryAlarmList(in AlarmInfo[] p0);
}
