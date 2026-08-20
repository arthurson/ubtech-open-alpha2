package com.ubtechinc.alpha2serverlib.interfaces;

/**
 * Primary speech callback. {@code onServerCallBack} delivers recognition results (for
 * built-in commands, a {@code Local_Result:rule:... action:... tag:...} string);
 * {@code onServerPlayEnd} fires when a TTS utterance finishes.
 */
public interface IAlpha2RobotClientListener {
   void onServerCallBack(String text);

   void onServerPlayEnd(boolean isEnd);
}
