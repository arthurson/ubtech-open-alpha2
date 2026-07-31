package com.ubtech.interfaces;

/**
 *
 *
 */
public interface RobotHandle {

	public void start_TTS(String text, boolean isNeedAction);

	public void stop_TTS();

	public void stat_FreeAngle();

	public void stop_FreeAngle();

	public void start_Action(String action);

	public void onCompletion();
	
	public void stop_Recognized();
	
	public void start_Recognized();
	
	public void start_Music();
	
	public void setSelfInterrupt(boolean isInterrupt);

}
