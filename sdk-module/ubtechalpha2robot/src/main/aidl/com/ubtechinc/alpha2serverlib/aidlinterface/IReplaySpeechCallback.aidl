// AIDL callback delivering replayed speech-recognition records (see ASRRecord). Used
// with ISpeechInterface.registerReplayContentListener.
//
// This file did not exist anywhere in this SDK before. Added by decompiling
// IReplaySpeechCallback$Stub.onTransact() from Alpha2Services v1.1.7.3.20
// (20170918171435-5mic) - the single method and its signature below are taken directly
// from that switch, not guessed at.
package com.ubtechinc.alpha2serverlib.aidlinterface;

import com.ubtechinc.alpha2serverlib.aidlinterface.ASRRecord;

interface IReplaySpeechCallback {
    void onRelpayContent(in ASRRecord record);
}
