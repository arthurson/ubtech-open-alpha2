// AIDL for the robot's speech service (com.ubtechinc.services.SpeechServices):
// TTS playback, dictation, grammar recognition and semantic understanding.
// Method declaration order defines the Binder transaction ids and must match the
// on-robot service exactly - do not reorder, rename or change signatures.
//
// Re-verified against this app's ISpeechInterface$Stub.onTransact() directly: the
// sparse-switch there has exactly 20 cases (transaction ids 1-20), which is the ground
// truth for both ordering and real parameter lists, since transaction ids are assigned
// by declaration order and mismatches silently corrupt Parcel reads rather than failing
// loudly. Two corrections vs. the previous version of this file:
//   1. onPlay/onPlayHigh (ids 5-6) take exactly 4 params on-robot: listener, text,
//      voiceName, language. There is no trailing int priority - the previous file's
//      extra int param doesn't exist in this build's Stub.
//   2. This build's Stub stops at id 20 (startSpeechNoWakeup). setStartEarLed (id 19,
//      no-arg) and startSpeechNoWakeup (id 20, listener-arg) are both real and kept.
//      disableTTSPause, startLocalFunction and registerReplayContentListener - along
//      with the IReplaySpeechCallback/ASRRecord pair the last one depended on - do not
//      exist anywhere in this build; there is no id 21+ in the switch. Removed.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechCallBackListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishOfflineUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarListener;

interface ISpeechInterface {
    int registerSpeechCallBackListener(ISpeechCallBackListener callBack);
    int unRegisterSpeechCallBackListener(ISpeechCallBackListener callBack);
    void onSpeech(ISpeechCallBackListener listener, String text);
    void onStopSpeech(ISpeechCallBackListener listener);
    void onPlay(ISpeechCallBackListener listener, String text, String strVoiceName, String language);
    void onPlayHigh(ISpeechCallBackListener listener, String text, String strVoiceName, String language);
    void onStopPlay(ISpeechCallBackListener listener);
    void setWakeState(boolean onWake);
    void onTextUnderstand(String strText, IAlphaTextUnderstandListener listener);
    void initSpeechGrammar(String strGrammar, ISpeechGrammarInitListener listener);
    void startSpeechGrammar(ISpeechGrammarListener listern);
    void stopSpeechGrammar();
    void stopSpeechAndEnterIdleMode();
    void setRecognizedLanguage(String strLanguage);
    void setVoiceName(String strVoiceName);
    void onEnglishUnderstand(IAlphaEnglishUnderstandListener listener);
    void setEnglishOfflineListener(IAlphaEnglishOfflineUnderstandListener listener);
    void setSelfInterrupt(boolean isInterrupt);
    void setStartEarLed();
    void startSpeechNoWakeup(ISpeechCallBackListener listener);
}
