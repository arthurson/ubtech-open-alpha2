// Callback AIDL, speech-related, used by the older Alpha2 speech client path.
// Re-verified against this APK's Stub: exactly 2 transactions (ids 1-2). Stub-only
// in this build (no Proxy - nothing here calls it cross-process).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2SpeechClientListener {
    void onServerCallBack(String text);
    void onServerPlayEnd(boolean isEnd);
}
