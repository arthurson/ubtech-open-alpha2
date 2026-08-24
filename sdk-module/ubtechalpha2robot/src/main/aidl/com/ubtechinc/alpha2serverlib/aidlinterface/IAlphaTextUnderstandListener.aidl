// AIDL callback for text (NLU) understanding results and errors.
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaTextUnderstandListener {
    void onAlpha2UnderStandError(int nErrorCode);
    void onAlpha2UnderStandTextResult(String strResult);
}
