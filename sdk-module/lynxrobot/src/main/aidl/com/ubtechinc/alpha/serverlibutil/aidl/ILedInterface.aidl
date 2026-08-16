// AIDL for the robot's LED control service. Method declaration order defines the
// Binder transaction ids and must match the on-robot service exactly. Confirmed
// against com.ubtechinc.alpha2services_base.3.002.apk by decompiling
// ILedInterface$Stub.onTransact():
//   TRANSACTION_getLedList = 1
//   TRANSACTION_turnOnEye = 2
//   TRANSACTION_turnOffEye = 3
//   TRANSACTION_turnOnEyeBlink = 4
//   TRANSACTION_turnOnEyeFlash = 5
//   TRANSACTION_turnOnEyeMarquee = 6
//   TRANSACTION_turnOnHead = 7
//   TRANSACTION_turnOffHead = 8
//   TRANSACTION_turnOnHeadFlash = 9
//   TRANSACTION_turnOnHeadMarquee = 10
//   TRANSACTION_turnOnHeadBreath = 11
//   TRANSACTION_turnOnMouth = 12
//   TRANSACTION_turnOffMouth = 13
//   TRANSACTION_turnOnMouthBreath = 14
//   TRANSACTION_turnOnWifi = 15
//   TRANSACTION_turnOffWifi = 16
//   TRANSACTION_turnOnChestLed = 17
//   TRANSACTION_turnOffChestLed = 18
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener;

interface ILedInterface {
    void getLedList(IRemoteLedListResultListener p0);
    void turnOnEye(int p0, IRemoteLedOperationResultListener p1);
    void turnOffEye(IRemoteLedOperationResultListener p0);
    void turnOnEyeBlink(IRemoteLedOperationResultListener p0);
    void turnOnEyeFlash(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
    void turnOnEyeMarquee(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
    void turnOnHead(int p0, int p1, IRemoteLedOperationResultListener p2);
    void turnOffHead(IRemoteLedOperationResultListener p0);
    void turnOnHeadFlash(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
    void turnOnHeadMarquee(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
    void turnOnHeadBreath(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
    void turnOnMouth(int p0, IRemoteLedOperationResultListener p1);
    void turnOffMouth(IRemoteLedOperationResultListener p0);
    void turnOnMouthBreath(int p0, int p1, int p2, IRemoteLedOperationResultListener p3);
    void turnOnWifi(int p0, IRemoteLedOperationResultListener p1);
    void turnOffWifi(IRemoteLedOperationResultListener p0);
    void turnOnChestLed(IRemoteLedOperationResultListener p0);
    void turnOffChestLed(IRemoteLedOperationResultListener p0);
}
