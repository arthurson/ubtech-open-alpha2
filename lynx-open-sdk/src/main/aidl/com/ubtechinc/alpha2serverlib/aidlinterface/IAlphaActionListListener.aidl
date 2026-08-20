// Callback AIDL delivering the robot's action list as a single serialized String.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaActionListListener {
    void onGetActionList(String list);
}
