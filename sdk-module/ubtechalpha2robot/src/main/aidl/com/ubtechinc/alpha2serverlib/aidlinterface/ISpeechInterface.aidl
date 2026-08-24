// AIDL for the robot's speech service (com.ubtechinc.services.SpeechServices):
// TTS playback, dictation, grammar recognition and semantic understanding.
// Method declaration order defines the Binder transaction ids and must match the
// on-robot service exactly - do not reorder, rename or change signatures.
//
// Verified against Alpha2Services v1.1.7.3.20 (20170918171435-5mic) by decompiling
// ISpeechInterface$Stub.onTransact() directly - the transaction-id switch there is
// the ground truth for both the ordering and the real parameter lists, since AIDL
// transaction ids are assigned by declaration order and mismatches silently corrupt
// Parcel reads rather than failing loudly. Two things that earlier version of this
// file got wrong:
//   1. onPlay/onPlayHigh actually take a trailing int (5 params, not 4) on-robot -
//      decompiled Stub.onTransact reads listener, text, voiceName, language, then an
//      extra readInt() before dispatching. Omitting it wouldn't break these two calls
//      themselves, but every other transaction after them in the switch is reached by
//      a hardcoded case number carried over from the AIDL compiler's declaration-order
//      count - as long as the method *count* and *order* up to this point match
//      on-robot, that numbering still lines up even with the wrong param list here, so
//      this fixes an over-read the Parcel does after this call, not a downstream shift.
//   2. Five methods on-robot don't exist in this file at all: setStartEarLed,
//      startSpeechNoWakeup, disableTTSPause, startLocalFunction and
//      registerReplayContentListener. All five are appended below in their confirmed
//      on-robot order/signatures. registerReplayContentListener needed its own new
//      AIDL/Parcelable pair (IReplaySpeechCallback.aidl, ASRRecord.aidl + ASRRecord.java)
//      which didn't exist in this SDK either - added alongside this file, field order/
//      types confirmed the same way via ASRRecord's own writeToParcel/readFromParcel.
//
// startSpeechNoWakeup is the one that matters most here: it starts speech recognition
// directly without requiring the mic-array hardware to detect its own wake word first
// (see setWakeState's doc comment on that hardware-level limitation) - passing it an
// ISpeechCallBackListener should let it drive the same registerSpeechCallBackListener
// callback path that onServerCallBack already listens on.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechCallBackListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishOfflineUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IReplaySpeechCallback;

interface ISpeechInterface {
    int registerSpeechCallBackListener(ISpeechCallBackListener callBack);
    int unRegisterSpeechCallBackListener(ISpeechCallBackListener callBack);
    void onSpeech(ISpeechCallBackListener listener, String text);
    void onStopSpeech(ISpeechCallBackListener listener);
    void onPlay(ISpeechCallBackListener listener, String text, String strVoiceName, String language, int priority);
    void onPlayHigh(ISpeechCallBackListener listener, String text, String strVoiceName, String language, int priority);
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
    void disableTTSPause(boolean disable);
    void startLocalFunction(String strFunction);
    void registerReplayContentListener(IReplaySpeechCallback listener);
}
