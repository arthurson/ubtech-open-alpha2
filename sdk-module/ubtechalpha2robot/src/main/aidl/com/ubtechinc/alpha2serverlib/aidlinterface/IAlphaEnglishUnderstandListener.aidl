// AIDL callback for online English semantic-understanding results.
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaEnglishUnderstandListener {
    void onAlpha2EnglishUnderstandResult(String strResult);
}
