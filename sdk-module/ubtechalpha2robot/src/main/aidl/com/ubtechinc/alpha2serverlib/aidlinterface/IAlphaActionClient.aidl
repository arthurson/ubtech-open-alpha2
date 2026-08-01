// Callback AIDL notifying that an action-file playback has stopped.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaActionClient {
    void onActionStop(String strActionFileName);
}
