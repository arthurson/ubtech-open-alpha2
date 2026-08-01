// AIDL for the robot's app-monitor callback, used by com.ubtechinc.services.MainService
// to receive a Binder handle from another component.
//
// This file did not exist in this SDK's AIDL set. Added by decompiling
// IAppMonitor$Stub.onTransact() from this APK - the single method and its signature
// below are taken directly from that switch (case 1 of 1). The interface/method are
// proguard-obfuscated (class "m", method "a") so the original method name is not
// recoverable; named descriptively based on the confirmed (IBinder) -> void signature
// and its use in MainService - do not treat the name as authoritative, only the
// signature is confirmed.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAppMonitor {
    void onAppBinderReceived(IBinder binder);
}
