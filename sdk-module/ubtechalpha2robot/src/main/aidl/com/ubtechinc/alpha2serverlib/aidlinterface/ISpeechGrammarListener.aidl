// AIDL callback for grammar-recognition results and errors.
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechGrammarListener {
    void onSpeechGrammarResult(String strResultType, String strResult);
    void onSpeechGrammarError(int nErrorCode);
}
