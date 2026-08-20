package com.ubtechinc.alpha2serverlib.constvalue;

/**
 * Implicit-intent action strings for the robot's on-device services. Each is bound with
 * an explicit target package of {@code com.ubtechinc.alpha2services}. These are the
 * robot's wire values and must not change (including the "Speeck" misspellings, which
 * are the actual action names the robot registers).
 */
public final class Alpha2Intent {
   public static final String ALPHA_SERIAL_SERVER = "com.ubtechinc.services.AlphaSerialPortServices";
   public static final String ALPHA_SERIAL_HEADER_SERVER = "com.ubtechinc.services.AlphaSerialPortHeaderServices";
   public static final String ALPHA_SOCKET_SERVER = "com.ubtechinc.services.Alpha2SocketServices";
   public static final String ALPHA_ACTION_SERVER = "com.ubtechinc.services.AlphaActionServices";
   public static final String ALPHA_BLUETOOTHSERIAL_SERVER = "com.ubtechinc.services.AlphaBlueToothSerialPortServices";
   public static final String ALPHA_MAIN_SERVER = "com.ubtechinc.services.MainService";
   public static final String ALPHA_NUANCE_SPEECH_MAIN_SERVER = "com.ubtechinc.services.NuanceSpeeckServices";
   public static final String ALPHA_IFLYTEK_SPEECH_MAIN_SERVER = "com.ubtechinc.services.IflytekSpeeckServices";
   public static final String ALPHA_SPEECH_MAIN_SERVER = "com.ubtechinc.services.SpeechServices";
   public static final String ALPHA_XMPP_SERVER = "com.ubtechinc.services.Alpha2XmppServices";

   private Alpha2Intent() {
   }
}
