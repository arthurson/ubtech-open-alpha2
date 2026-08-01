// Callback AIDL for (online) English semantic-understanding results.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaEnglishUnderstandListener {
    void onAlpha2EnglishUnderstandResult(String strResult);
}
