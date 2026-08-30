package com.ubtechinc.alpha2serverlib.interfaces;

/** Grammar-recognition results and errors. */
public interface IAlpha2SpeechGrammarListener {
   void onSpeechGrammarResult(int type, String result);

   void onSpeechGrammarError(int errorCode);
}
