// AIDL for the robot's system-level service (versions, battery, alarms, PIR sensor,
// upgrade mode). Method declaration order defines the Binder transaction ids and must
// match the on-robot service exactly. Confirmed against
// com.ubtechinc.alpha2services_base.3.002.apk by decompiling ISysService$Stub.onTransact():
//   TRANSACTION_getSid = 1
//   TRANSACTION_getMICVersion = 2
//   TRANSACTION_queryAllAlarm = 3
//   TRANSACTION_insertAlarm = 4
//   TRANSACTION_startApp = 5
//   TRANSACTION_enterUpgradeMode = 6
//   TRANSACTION_exitUpgradeMode = 7
//   TRANSACTION_getChestVersion = 8
//   TRANSACTION_getHeadVersion = 9
//   TRANSACTION_getBatteryVersion = 10
//   TRANSACTION_isPowerCharging = 11
//   TRANSACTION_getPowerValue = 12
//   TRANSACTION_setPIRSensor = 13
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemotePIRSensorOperationResultListener;

interface ISysService {
    String getSid();
    String getMICVersion();
    AlarmInfo[] queryAllAlarm(String p0);
    int insertAlarm(in AlarmInfo p0);
    void startApp(in Uri p0);
    void enterUpgradeMode();
    void exitUpgradeMode();
    String getChestVersion();
    String getHeadVersion();
    String getBatteryVersion();
    boolean isPowerCharging();
    int getPowerValue();
    void setPIRSensor(boolean p0, IRemotePIRSensorOperationResultListener p1);
}
