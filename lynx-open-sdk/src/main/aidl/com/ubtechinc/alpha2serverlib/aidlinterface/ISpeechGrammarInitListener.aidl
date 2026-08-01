// Callback AIDL for speech-grammar initialization results.
// Re-verified against this APK's Stub: exactly 1 transaction (id 1).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechGrammarInitListener {
    void speechGrammarInitCallback(String grammarID, int nErrorCode);
}
