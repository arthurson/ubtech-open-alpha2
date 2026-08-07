// AIDL for the robot's speech (ASR/TTS/wake-up/PCM) service. Method declaration order
// defines the Binder transaction ids and must match the on-robot service exactly.
// Confirmed against com.ubtechinc.alpha2services_base.3.002.apk by decompiling
// ISpeechInterface$Stub.onTransact():
//   TRANSACTION_registerPcmListener = 1
//   TRANSACTION_unregisterPcmListener = 2
//   TRANSACTION_registerWakeUpCallbackListener = 3
//   TRANSACTION_unregisterWakeUpCallbackListener = 4
//   TRANSACTION_onPlayCallback = 5
//   TRANSACTION_onStopPlay = 6
//   TRANSACTION_setVoiceName = 7
//   TRANSACTION_setTtsSpeed = 8
//   TRANSACTION_getTtsSpeed = 9
//   TRANSACTION_setTtsVolume = 10
//   TRANSACTION_getTtsVolume = 11
//   TRANSACTION_startSpeechAsr = 12
//   TRANSACTION_stopSpeechAsr = 13
//   TRANSACTION_getSpeechVoices = 14
//   TRANSACTION_getCurSpeechVoices = 15
//   TRANSACTION_initSpeechGrammar = 16
//   TRANSACTION_switchSpeechCore = 17
//   TRANSACTION_switchWakeup = 18
//   TRANSACTION_startLocalFunction = 19
//   TRANSACTION_isSpeechGrammar = 20
//   TRANSACTION_isSpeechIat = 21
//   TRANSACTION_setSpeechMode = 22
//   TRANSACTION_stopRecording = 23
//   TRANSACTION_startRecording = 24
package com.ubtechinc.alpha.serverlibutil.aidl;

import com.ubtechinc.alpha.serverlibutil.aidl.IPcmListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechWakeUpListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ITtsCallBackListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechAsrListener;
import com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechGrammarInitListener;

interface ISpeechInterface {
    int registerPcmListener(String p0, IPcmListener p1);
    int unregisterPcmListener(String p0);
    int registerWakeUpCallbackListener(String p0, ISpeechWakeUpListener p1);
    int unregisterWakeUpCallbackListener(String p0);
    int onPlayCallback(String p0, String p1, ITtsCallBackListener p2);
    void onStopPlay();
    void setVoiceName(String p0);
    void setTtsSpeed(String p0);
    String getTtsSpeed();
    void setTtsVolume(String p0);
    String getTtsVolume();
    void startSpeechAsr(String p0, int p1, ISpeechAsrListener p2);
    void stopSpeechAsr();
    List getSpeechVoices();
    SpeechVoice getCurSpeechVoices();
    void initSpeechGrammar(String p0, ISpeechGrammarInitListener p1);
    void switchSpeechCore(String p0);
    void switchWakeup(boolean p0);
    void startLocalFunction(String p0);
    boolean isSpeechGrammar();
    boolean isSpeechIat();
    void setSpeechMode(int p0);
    void stopRecording();
    void startRecording();
}
