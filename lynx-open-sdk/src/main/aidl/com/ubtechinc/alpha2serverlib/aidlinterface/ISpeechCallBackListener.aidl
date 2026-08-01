// Callback AIDL used across most of ISpeechInterface's methods (ASR results and
// TTS end-of-playback notifications).
// Re-verified against this APK's Stub: exactly 2 transactions (ids 1-2).
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechCallBackListener {
    void onCallBack(int type, String text);
    void onPlayEnd(boolean isEnd);
}
