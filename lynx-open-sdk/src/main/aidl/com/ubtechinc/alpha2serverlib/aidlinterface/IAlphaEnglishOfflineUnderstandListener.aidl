// Callback AIDL for offline (on-device) English semantic-understanding results.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaEnglishOfflineUnderstandListener {
    void onAlpha2EnglishOfflineUnderstandResult(String strResult);
}
