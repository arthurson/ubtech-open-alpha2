// AIDL client-facing speech listener (recognition text + TTS end).
// Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlpha2SpeechClientListener {
    void onServerCallBack(String text);
    void onServerPlayEnd(boolean isEnd);
}
