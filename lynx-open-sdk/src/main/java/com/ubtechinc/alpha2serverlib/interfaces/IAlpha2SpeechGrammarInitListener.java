package com.ubtechinc.alpha2serverlib.interfaces;

/** Notified when grammar initialisation completes, with the grammar id and an error code. */
public interface IAlpha2SpeechGrammarInitListener {
   void speechGrammarInitCallback(String grammarId, int errorCode);
}
