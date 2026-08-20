// AIDL for the robot's OTA/firmware update service (com.ubt.lynxupdate.services.
// UpdateAidlService, per this APK's dex string pool). Bound to directly via
// Alpha2UpdataServiceUtil, a separate path from Alpha2RobotApi's usual AIDL set - see
// IClientListener.aidl for why.
//
// Re-verified directly against this APK's IUpdataBussiness$Stub.onTransact(): exactly
// 5 transactions (ids 1-5). Method names a/b/c below are proguard-obfuscated in this
// build and not recoverable; named descriptively based on how
// Alpha2UpdataServiceUtil actually calls them (confirmed by decompiling that class):
//   - checkingUpdate() calls id 1 with no args      -> checkUpdate()
//   - executorUpdata() calls id 2 with no args      -> executeUpdate()
//   - onServiceConnected(...) calls id 3 right after bind, passing the just-registered
//     IClientListener, and checks the returned boolean -> registerClientListener(...)
//   - ReleaseConnection() calls id 4 with no args before unbindService() -> unregister/
//     release, kept here as unregisterClientListener()
//   - triggleLynxDownload()/triggleLynxUpdate() both call id 5 with a single int -
//     confirmed to pass 1 for download and 2 for update respectively (decompiled the
//     literal const/4 operands directly) -> triggerUpdate(int mode)
// Only the signatures and call semantics are confirmed; the method names themselves
// are inferred, unlike IClientListener's (which were not obfuscated).
package com.ubt.lynxupdate;

import com.ubt.lynxupdate.IClientListener;

interface IUpdataBussiness {
    void checkUpdate();
    void executeUpdate();
    boolean registerClientListener(IClientListener listener);
    boolean unregisterClientListener();
    void triggerUpdate(int mode);
}
