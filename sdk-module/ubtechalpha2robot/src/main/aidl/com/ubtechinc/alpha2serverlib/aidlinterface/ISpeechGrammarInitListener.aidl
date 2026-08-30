// AIDL callback for grammar-initialisation completion.
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechGrammarInitListener {
    void speechGrammarInitCallback(String grammarID, int nErrorCode);
}
