// AIDL for the robot's motor/servo control service. Method declaration order defines
// the Binder transaction ids and must match the on-robot service exactly. Confirmed
// against com.ubtechinc.alpha2services_base.3.002.apk by decompiling
// IMotorInterface$Stub.onTransact():
//   TRANSACTION_getMotorList = 1
//   TRANSACTION_moveToAbsoluteAngle = 2
//   TRANSACTION_moveRefAngle = 3
//   TRANSACTION_readAbsoluteAngle = 4
//   TRANSACTION_SetAllMotorAbsoluteAngle = 5
//   TRANSACTION_setPowerSaveMode = 6
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.MotorAngle;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorMoveAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorReadAngleListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorSetAllAngleResultListener;

interface IMotorInterface {
    void getMotorList(IMotorListResultListener p0);
    void moveToAbsoluteAngle(int p0, int p1, long p2, IMotorMoveAngleResultListener p3);
    void moveRefAngle(int p0, int p1, long p2, IMotorMoveAngleResultListener p3);
    void readAbsoluteAngle(int p0, boolean p1, IMotorReadAngleListener p2);
    void SetAllMotorAbsoluteAngle(in MotorAngle[] p0, long p1, IMotorSetAllAngleResultListener p2);
    void setPowerSaveMode(boolean p0);
}
