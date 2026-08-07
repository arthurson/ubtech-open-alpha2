package com.ubtechinc.alpha2serverlib.interfaces;

/** Results of a text (NLU) understanding request. */
public interface IAlpha2RobotTextUnderstandListener {
   void onAlpha2UnderStandError(int errorCode);

   void onAlpha2UnderStandTextResult(String result);
}
