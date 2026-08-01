// Callback AIDL for speech-grammar recognition results and errors.
// Re-verified against this APK's Stub: exactly 2 transactions (ids 1-2), in this
// order - result first, then error (note: reversed relative to
// IAlphaTextUnderstandListener's error-then-result order; kept as-is per Stub).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechGrammarListener {
    void onSpeechGrammarResult(String strResultType, String strResult);
    void onSpeechGrammarError(int nErrorCode);
}
