// Callback AIDL for Chinese/text semantic-understanding results and errors.
// Re-verified against this APK's Stub: exactly 2 transactions (ids 1-2), in this
// order - error first, then result.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaTextUnderstandListener {
    void onAlpha2UnderStandError(int nErrorCode);
    void onAlpha2UnderStandTextResult(String strResult);
}
