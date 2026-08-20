// Callback AIDL for the robot's OTA/firmware update service. Delivered to whoever
// registers via IUpdataBussiness.registerClientListener(IClientListener).
//
// This is a SEPARATE AIDL package/service from com.ubtechinc.alpha2serverlib.aidlinterface
// (the 17-interface set documented in this SDK's README) - it lives under
// com.ubt.lynxupdate and is bound to via a plain Intent (see
// Alpha2UpdataServiceUtil.bindUpdateService()), not through Alpha2RobotApi's usual
// initXxxApi() entry points.
//
// Re-verified directly against this APK's IClientListener$Stub.onTransact(): exactly 2
// transactions (ids 1-2). The interface itself was NOT proguard-obfuscated in this
// build (enforceInterface/writeInterfaceToken carry the real descriptor string
// "com.ubt.lynxupdate.IClientListener", and both method names are intact in the smali),
// so nothing here is inferred.
package com.ubt.lynxupdate;

interface IClientListener {
    void onError(int errorCode);
    // Name matches the real APK exactly, including its original typo
    // ("Reonse" for "Response") - kept as-is, this is the actual wire name.
    void onReonseForUpdate(boolean success);
}
