// AIDL callback for offline English semantic-understanding results.
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaEnglishOfflineUnderstandListener {
    void onAlpha2EnglishOfflineUnderstandResult(String strResult);
}
