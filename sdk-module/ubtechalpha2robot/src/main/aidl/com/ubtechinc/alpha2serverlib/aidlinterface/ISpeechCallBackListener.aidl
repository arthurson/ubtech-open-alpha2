// AIDL callback the robot's speech service uses for recognition results and
// TTS-playback-end notifications. Wire contract - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface ISpeechCallBackListener {
    void onCallBack(int type, String text);
    void onPlayEnd(boolean isEnd);
}
