package com.ubtechinc.alpha2robot;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import com.ubtechinc.alpha2ctrlapp.network.action.ClientAuthorizeListener;
import com.ubtechinc.alpha2robot.constant.UbxErrorCode;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SpeechClientListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionClient;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortHeaderOnRcvListener;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortOnRcvListener;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2CustomMessageListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.util.Alpha2SerialHeaderServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2SerialServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2XmppServiceUtil;
import com.ubtechinc.alpha2serverlib.util.AlphaActionServiceUtil;
import com.ubtechinc.alpha2serverlib.util.AlphaMainServiceUtil;
import com.ubtechinc.constant.CustomLanguage;
import com.ubtechinc.developer.DeveloperAngle;
import com.ubtechinc.developer.DeveloperAppButtenEventData;
import com.ubtechinc.developer.DeveloperAppConfigData;
import com.ubtechinc.developer.DeveloperAppData;
import com.ubtechinc.developer.DeveloperEarLedData;
import com.ubtechinc.developer.DeveloperEyesLedData;
import com.ubtechinc.developer.DeveloperPacketData;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Single entry point of the Alpha2 open SDK. Wraps the robot's on-device services
 * (action playback, chest / head serial links, speech, custom messaging) behind one
 * facade.
 *
 * <p>Typical use: construct with an app key and a {@link ClientAuthorizeListener}, call
 * the relevant {@code init*Api} methods, then drive the robot. This open build performs no
 * store authorisation - construction always reports success and no call is gated on it.
 *
 * <p>Most methods return an {@link UbxErrorCode.API_ERROR_CODE}: {@code API_ERROR_SUCCEED}
 * when the request was forwarded, {@code API_ERROR_NOT_INIT} when the matching service is
 * not ready.
 */
public class Alpha2RobotApi implements Alpha2SerialPortOnRcvListener, Alpha2SerialPortHeaderOnRcvListener {
   private static final String TAG = "Alpha2RobotApi";
   private static final String SDK_VERSION = "3.0.0.1";
   private static final String AUTHORITY_INFO = "have offline authority";
   private static final int CUSTOM_CMD = 0;
   private static final int CUSTOM_RESP = 1;

   private final Context mContext;
   private final String mAppID;

   private AlphaActionServiceUtil mActionServiceUtil;
   private Alpha2SerialServiceUtil mChestSerialServiceUtil;
   private Alpha2SerialHeaderServiceUtil mHeaderSerialServiceUtil;
   private Alpha2SpeechMainServiceUtil mSpeechServiceUtil;
   private Alpha2XmppServiceUtil mXmppServiceUtil;

   private IAlpha2RobotClientListener mRobotClient;
   private IAlpha2RobotTextUnderstandListener mRobotTextListener;
   private IAlpha2SpeechGrammarInitListener mSpeechGrammarInitListener;
   private AlphaActionClientListener mActionListener;

   private boolean isAuthorize = true;

   public Alpha2RobotApi(Context context, String appKey, ClientAuthorizeListener listener) {
      this.mContext = context;
      this.mAppID = appKey;
      // Open build: authorisation always succeeds. Preserve the historical result shape.
      this.isAuthorize = true;
      if (listener != null) {
         listener.onResult(1, AUTHORITY_INFO);
      }
   }

   public static String getSdkVersion() {
      return SDK_VERSION;
   }

   public static String getServerVersion() {
      return AlphaMainServiceUtil.getVersion();
   }

   public void releaseApi() {
      if (this.mActionServiceUtil != null) {
         this.mActionServiceUtil.ReleaseConnection();
         this.mActionServiceUtil = null;
      }
      if (this.mSpeechServiceUtil != null) {
         this.mSpeechServiceUtil.ReleaseConnection();
         this.mSpeechServiceUtil = null;
      }
      if (this.mChestSerialServiceUtil != null) {
         this.mChestSerialServiceUtil.ReleaseConnection();
         this.mChestSerialServiceUtil = null;
      }
      if (this.mHeaderSerialServiceUtil != null) {
         this.mHeaderSerialServiceUtil.ReleaseConnection();
         this.mHeaderSerialServiceUtil = null;
      }
      if (this.mXmppServiceUtil != null) {
         this.mXmppServiceUtil.ReleaseConnection();
         this.mXmppServiceUtil = null;
      }
   }

   // -- Initialisation -------------------------------------------------------

   public boolean initActionApi(AlphaActionClientListener listener) {
      this.mActionListener = listener;
      if (this.mActionServiceUtil == null) {
         this.mActionServiceUtil = new AlphaActionServiceUtil(this.mContext, new ActionClientListener());
      }
      return true;
   }

   public boolean initChestSerialApi() {
      if (this.mChestSerialServiceUtil == null) {
         this.mChestSerialServiceUtil = new Alpha2SerialServiceUtil(this.mContext, this);
      }
      return true;
   }

   public boolean initHeaderSerialApi() {
      if (this.mHeaderSerialServiceUtil == null) {
         this.mHeaderSerialServiceUtil = new Alpha2SerialHeaderServiceUtil(this.mContext, this);
      }
      return true;
   }

   /**
    * Blocks (off the main thread only - see Alpha2SerialServiceUtil.waitForInitComplete())
    * until the chest serial AIDL bind completes, up to ~3s.
    *
    * isChestAvailable()/isHeaderAvailable() only check that the *ServiceUtil object was
    * constructed, not that its ServiceConnection.onServiceConnected() callback has fired
    * yet - bindService() is asynchronous. A command sent before that callback runs uses
    * an unset/default session id, and mService.sendCommand()'s boolean result is not
    * checked by the header_start.../chest_Send... methods (only RemoteException is caught),
    * so a too-early call can silently no-op on the robot while still returning
    * API_ERROR_SUCCEED to the caller. Call this (from a background thread) before the
    * very first servo/LED command after initChestSerialApi()/initHeaderSerialApi() to
    * avoid that race.
    */
   public void waitChestReady(long timeoutMs) {
      if (this.mChestSerialServiceUtil != null) {
         this.mChestSerialServiceUtil.waitForInitComplete();
      }
   }

   /** See {@link #waitChestReady(long)} - same race, header/LED side. */
   public void waitHeaderReady(long timeoutMs) {
      if (this.mHeaderSerialServiceUtil != null) {
         this.mHeaderSerialServiceUtil.waitForInitComplete();
      }
   }

   public boolean isChestReady() {
      return this.mChestSerialServiceUtil != null && this.mChestSerialServiceUtil.isInitCompleted();
   }

   public boolean isHeaderReady() {
      return this.mHeaderSerialServiceUtil != null && this.mHeaderSerialServiceUtil.isInitCompleted();
   }

   public boolean initSpeechApi(IAlpha2RobotClientListener robotClient,
         Alpha2SpeechMainServiceUtil.ISpeechInitInterface speechInitListener) {
      return this.initSpeechApi(robotClient, speechInitListener, CustomLanguage.DEFAULT_LANGUAGE);
   }

   public boolean initSpeechApi(IAlpha2RobotClientListener robotClient,
         Alpha2SpeechMainServiceUtil.ISpeechInitInterface speechInitListener, CustomLanguage specifyLanguage) {
      if (this.mSpeechServiceUtil == null) {
         this.mRobotClient = robotClient;
         IAlpha2SpeechClientListener.Stub clientListener = new SpeechClientImpl();
         this.mSpeechServiceUtil = new Alpha2SpeechMainServiceUtil(
               this.mContext, clientListener, speechInitListener, specifyLanguage);
      }
      return true;
   }

   public boolean initCustomMessageApi(IAlpha2CustomMessageListener listener) {
      if (this.mXmppServiceUtil == null) {
         this.mXmppServiceUtil = Alpha2XmppServiceUtil.getInstance(this.mContext, this.mAppID, listener);
      }
      return true;
   }

   // -- Actions --------------------------------------------------------------

   public UbxErrorCode.API_ERROR_CODE action_getActionList(IAlpha2ActionListListener listener) {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.getActionList(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE action_PlayActionName(String actionName) {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.playActionName(actionName);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE action_StopAction() {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.stopActionPlay();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   // -- Speech / TTS ---------------------------------------------------------

   public boolean speech_SetMIC(boolean isWake) {
      if (this.mSpeechServiceUtil != null) {
         this.mSpeechServiceUtil.setWakeState(isWake);
      }
      return true;
   }

   /**
    * Starts speech recognition directly, without waiting for the mic-array hardware to
    * detect its own wake word first. Unlike {@link #speech_SetMIC}, which only claims or
    * releases mic ownership (setWakeState never itself starts listening - see that
    * method's own history/comments for the hardware-callback-only wake path this works
    * around), this is on-robot's actual "begin listening now" entry point. Confirmed
    * against Alpha2Services v1.1.7.3.20 by decompiling ISpeechInterface$Stub.onTransact -
    * it wasn't exposed anywhere in this SDK before. Results still arrive the normal way,
    * via the registered speech listener's onServerCallBack.
    */
   public UbxErrorCode.API_ERROR_CODE speech_startSpeechNoWakeup() {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.startSpeechNoWakeup();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_setVoiceName(String strVoiceName) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.setVoiceName(strVoiceName);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_startTTS(String text, String strVoiceName) {
      return this.speech_startTTS(null, text, strVoiceName);
   }

   public UbxErrorCode.API_ERROR_CODE speech_StartTTS(String text) {
      return this.speech_startTTS(null, text, null);
   }

   public UbxErrorCode.API_ERROR_CODE speech_startTTS(String language, String text, String strVoiceName) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.onPlay(text, strVoiceName, language, true);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_StopTTS() {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.onStopPlay();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_setRecognizedLanguage(String strLanguage) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.setRecognizedLanguage(strLanguage);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** @deprecated dictation is not the primary recognition path on this firmware. */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE speech_startRecognized(String text) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.onSpeech(text);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** @deprecated */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE speech_stopRecognized() {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.onStopSpeech();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** @deprecated text understanding relied on cloud services that are no longer reachable. */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE speech_understandText(String strText,
         IAlpha2RobotTextUnderstandListener textListener) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mRobotTextListener = textListener;
      this.mSpeechServiceUtil.textUnderstand(strText, new SpeechTextUnderstand());
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_initGrammar(String strGrammar,
         IAlpha2SpeechGrammarInitListener listener) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechGrammarInitListener = listener;
      this.mSpeechServiceUtil.initSpeechGrammar(strGrammar, new ISpeechGrammarInitListener.Stub() {
         @Override
         public void speechGrammarInitCallback(String grammarId, int errorCode) throws RemoteException {
            if (Alpha2RobotApi.this.mSpeechGrammarInitListener != null) {
               Alpha2RobotApi.this.mSpeechGrammarInitListener.speechGrammarInitCallback(grammarId, errorCode);
            }
         }
      });
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** Corrected name; the original SDK spelled this {@code speeh_startGrammar}. */
   public UbxErrorCode.API_ERROR_CODE speech_startGrammar(IAlpha2SpeechGrammarListener listener) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.startSpeechGrammar(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** Corrected name; the original SDK spelled this {@code speech_stopGrammaer}. */
   public UbxErrorCode.API_ERROR_CODE speech_stopGrammar() {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.stopSpeechGrammar();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE speech_setSelfInterrupt(boolean isInterrupt) {
      if (this.mSpeechServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechServiceUtil.setSelfInterrupt(isInterrupt);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   // -- Chest servos ---------------------------------------------------------

   public UbxErrorCode.API_ERROR_CODE isChestAvailable() {
      if (this.mChestSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   private void sendFreeAngle(int[] data, short time) {
      DeveloperPacketData packetData = new DeveloperPacketData(data.length + 2);
      for (int value : data) {
         packetData.putByte((byte) value);
      }
      if (time < 20) {
         time = 20;
      }
      packetData.putShort_(time);
      this.mChestSerialServiceUtil.sendCommand((byte) 52, packetData.getBuffer(), packetData.getBuffer().length);
   }

   /** @deprecated use {@link #chest_SendFreeAngle(int[], short)}. */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE head_SendFreeAngle(int[] data, short time) {
      return this.chest_SendFreeAngle(data, time);
   }

   public UbxErrorCode.API_ERROR_CODE chest_SendFreeAngle(int[] data, short time) {
      new DeveloperAngle().checkData(data);
      if (this.mChestSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.sendFreeAngle(data, time);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   private void setOneFreeAngle(byte id, int angle, short time) {
      DeveloperPacketData packetData = new DeveloperPacketData(5);
      packetData.putByte(id);
      packetData.putByte((byte) ((angle >> 8) & 0xFF));
      packetData.putByte((byte) (angle & 0xFF));
      packetData.putShort_(time);
      this.mChestSerialServiceUtil.sendCommand((byte) 5, packetData.getBuffer(), packetData.getBuffer().length);
   }

   /** @deprecated use {@link #chest_SendOneFreeAngle(byte, int, short)}. */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE head_SendOneFreeAngle(byte id, int angle, short time) {
      angle = new DeveloperAngle().checkAngle(id, angle);
      if (this.mChestSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      if (id >= 1 && id <= 20) {
         if (time < 0) {
            time = 20;
         }
         this.setOneFreeAngle(id, angle, time);
      }
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE chest_SendOneFreeAngle(byte id, int angle, short time) {
      angle = new DeveloperAngle().checkAngle(id, angle);
      if (this.mChestSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      if (id >= 1 && id <= 20) {
         this.setOneFreeAngle(id, angle, time);
      }
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE chest_configureSonar(int distance) {
      // The chest ultrasonic (sonar) does not stream obstacle events by default. This
      // sends the chest board's sonar-configure frame: command 4 (CHEST_CMD_SETTING),
      // sub-command 2, then a trigger-distance byte - after which an obstacle is reported
      // on the chest link as command CHES_SEND_OBSTACLE (-127), param[0] != 0 = present.
      if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      byte[] data = {2, (byte) distance};
      boolean sent = this.mChestSerialServiceUtil.sendCommand((byte) 4, data, data.length);
      return sent
            ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
            : UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
   }

   // -- Head: noise gate + LEDs ---------------------------------------------

   public UbxErrorCode.API_ERROR_CODE isHeaderAvailable() {
      if (this.mHeaderSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE header_setNoise(boolean isOpen) {
      if (this.mHeaderSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      byte[] param = {(byte) (isOpen ? 0 : 1)};
      this.mHeaderSerialServiceUtil.sendCommand((byte) 39, param, param.length);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE header_startEarLED(short upTime, short downTime, short runTime) {
      return header_startEarLED(upTime, downTime, runTime, (byte) 9);
   }

   /**
    * Overload exposing the ear-LED brightness byte, which the upstream SDK hardcodes to
    * 9. DeveloperEarLedData.mBright is a real field in the wire payload (see the class's
    * getPlayData() byte layout) - this was simply never surfaced by Alpha2RobotApi.
    * Valid range is protocol-defined, not documented upstream; 0-9 matches the hardcoded
    * default and is the safe range to expose in a UI.
    */
   public UbxErrorCode.API_ERROR_CODE header_startEarLED(short upTime, short downTime, short runTime, byte brightness) {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      DeveloperEarLedData earLed = new DeveloperEarLedData();
      earLed.setmLeftLed(-1);
      earLed.setmRightLed(-1);
      earLed.setmBright(brightness);
      earLed.setmLedUpTime(upTime);
      earLed.setmLedDownTime(downTime);
      earLed.setmRunTime(runTime);
      byte[] rawData = earLed.getPlayData();
      this.mHeaderSerialServiceUtil.sendCommand((byte) 1, rawData, rawData.length);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE header_stopEarLED() {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      byte[] param = {1};
      this.mHeaderSerialServiceUtil.sendCommand((byte) 8, param, param.length);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE header_startEyeLED(int colorType, short upTime, short downTime, short runTime) {
      return header_startEyeLED(colorType, upTime, downTime, runTime, (byte) 9);
   }

   /**
    * Overload exposing the eye-LED brightness byte (see header_startEarLED(byte) above
    * for why this wasn't in the upstream signature).
    */
   public UbxErrorCode.API_ERROR_CODE header_startEyeLED(int colorType, short upTime, short downTime, short runTime, byte brightness) {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      DeveloperEyesLedData eyesLed = new DeveloperEyesLedData();
      eyesLed.setmLeftLed((byte) -1);
      eyesLed.setmRightLed((byte) -1);
      eyesLed.setmBright(brightness);
      eyesLed.setmColor((byte) colorType);
      eyesLed.setnLightUpTime(upTime);
      eyesLed.setnLightDownTime(downTime);
      eyesLed.setmRunTime(runTime);
      byte[] rawData = eyesLed.getPlayData();
      this.mHeaderSerialServiceUtil.sendCommand((byte) 2, rawData, rawData.length);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE header_stopEyeLED() {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      byte[] param = {0};
      this.mHeaderSerialServiceUtil.sendCommand((byte) 8, param, param.length);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   // -- 5-mic hardware LED path (see Alpha2SerialHeaderServiceUtil for why this
   // exists separately from header_start/stopEarLED / header_start/stopEyeLED). ------
   //
   // Parameter meaning, user-confirmed on real 5-mic hardware:
   //   p1 (colorType): 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白 - other values invalid
   //   p2 (brightness): 1=最暗 .. 9=最光 - other values invalid
   //   p3/p4: right/left LED selector (not independently varied by the UI - both sides
   //          are always driven together)
   //   p5 (upTime, ms) / p6 (downTime, ms): timing that shapes the chosen mode's cycle
   //   p7 (runTime, ms): how long the effect keeps running; Integer.MAX_VALUE = stays on
   //          indefinitely ("長開" / long-on)
   //   p8 (mode) - NOT the same scale between head and eye:
   //     head/ear: 0=閃(flash) 1=呼吸燈(breathing) 3=跑馬燈(chase) 5=雙色燈(dual-colour)
   //     eye:      0=閃(flash) 1=跑馬燈(chase) 3=雙色燈(dual-colour) (no breathing mode)
   //   Confirmed preset (p5,p6) pairs per mode: 長開 p5=MAX,p6=0 · 跑馬燈 p5=100,p6=0 ·
   //   閃 p5=100,p6=100 · 呼吸燈(head only) p5=5,p6=20 · 雙色燈 p5=500,p6=0

   public UbxErrorCode.API_ERROR_CODE header_stop5MicEyeLED() {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mHeaderSerialServiceUtil.stop5MicEyeLED();
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   public UbxErrorCode.API_ERROR_CODE header_stop5MicEarLED() {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mHeaderSerialServiceUtil.stop5MicEarLED();
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   /** Raw 8-int passthrough. See the parameter-meaning block above this section for
    *  what each position does - all values below are user-confirmed on real hardware. */
   public UbxErrorCode.API_ERROR_CODE header_ledSetEye5Mic(int p1, int p2, int p3, int p4,
         int p5, int p6, int p7, int p8) {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mHeaderSerialServiceUtil.ledSetEye5Mic(p1, p2, p3, p4, p5, p6, p7, p8);
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   /** See header_ledSetEye5Mic() note on parameter meaning. */
   public UbxErrorCode.API_ERROR_CODE header_ledSetHead5Mic(int p1, int p2, int p3, int p4,
         int p5, int p6, int p7, int p8) {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mHeaderSerialServiceUtil.ledSetHead5Mic(p1, p2, p3, p4, p5, p6, p7, p8);
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   // -- Misc -----------------------------------------------------------------

   public void requestRobotUUID() {
      this.mContext.sendBroadcast(new Intent("com.ubtechinc.robot_uuid.request"));
   }

   public boolean isSystemApp(PackageInfo pInfo) {
      return (pInfo.applicationInfo.flags & 1) != 0;
   }

   @Override
   public void onListenSerialPortRcvData(byte[] bytes, int len) {
   }

   @Override
   public void onListenSerialPortHeaderRcvData(byte[] bytes, int len) {
   }

   // -- Custom messaging -----------------------------------------------------

   public UbxErrorCode.API_ERROR_CODE sendCustomMessageRequest(String appID, byte[] message) {
      if (this.mXmppServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mXmppServiceUtil.sendCustomXmppMessage(CUSTOM_CMD, appID, new String(message));
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE sendCustomMessageResp(String appID, byte[] message) {
      if (this.mXmppServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mXmppServiceUtil.sendCustomXmppMessage(CUSTOM_RESP, appID, new String(message));
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   // -- Developer-app config / button plumbing -------------------------------

   public String[] readConfig(String code) {
      String configData = this.readAppFile(code);
      String configTag = this.readAssetsFile("config.json", code);
      return new String[] {configTag == null ? "" : configTag, configData == null ? "" : configData};
   }

   public void writeConfig(Intent intent) {
      Bundle bundle = intent.getExtras();
      DeveloperAppData appData = (DeveloperAppData) bundle.getSerializable("appdata");
      byte[] data = removeMessyCode(appData.getDatas());
      String path = this.mContext.getFilesDir().getParent();
      File file = new File(path + "/files/config.json");
      try (FileOutputStream fs = new FileOutputStream(file.getAbsolutePath())) {
         fs.write(data);
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public boolean sendConfig2Server(Intent intent, String packageName, String code) {
      Bundle bundle = intent.getExtras();
      DeveloperAppData appData = (DeveloperAppData) bundle.getSerializable("appdata");
      String[] json = this.readConfig(code);
      DeveloperAppConfigData appConfig = new DeveloperAppConfigData();
      appConfig.setCmd(appData.getCmd());
      appConfig.setTags(json[0].getBytes());
      appConfig.setDatas(json[1].getBytes());
      appConfig.setPackageName(packageName);
      Intent back = new Intent("com.ubtechinc.config.back");
      Bundle out = new Bundle();
      out.putSerializable("appconfig", appConfig);
      back.putExtras(out);
      this.mContext.sendBroadcast(back);
      return true;
   }

   public boolean sendButtonEvent2Server(Intent intent, String packageName, String code) {
      String json = this.readAssetsFile("button.json", code);
      DeveloperAppButtenEventData appEvent = new DeveloperAppButtenEventData();
      appEvent.setDatas((json == null ? "" : json).getBytes());
      appEvent.setPackageName(packageName);
      Intent back = new Intent("com.ubtechinc.button.back");
      Bundle out = new Bundle();
      out.putSerializable("appbutton", appEvent);
      back.putExtras(out);
      this.mContext.sendBroadcast(back);
      return true;
   }

   public String parseClickEvent(Intent intent, String packageName) {
      Bundle bundle = intent.getExtras();
      DeveloperAppData appData = (DeveloperAppData) bundle.getSerializable("appclick");
      return new String(appData.getDatas());
   }

   private byte[] removeMessyCode(byte[] data) {
      int start = new String(data).indexOf("{");
      if (start > 0) {
         byte[] trimmed = new byte[data.length - start];
         System.arraycopy(data, start, trimmed, 0, trimmed.length);
         return trimmed;
      }
      return data.clone();
   }

   private String readAppFile(String code) {
      String path = this.mContext.getFilesDir().getParent();
      File file = new File(path + "/files/config.json");
      if (!file.exists()) {
         return null;
      }
      try (FileInputStream fIn = new FileInputStream(file)) {
         return readAll(fIn, code);
      } catch (Exception e) {
         e.getMessage();
         return "";
      }
   }

   private String readAssetsFile(String assetName, String code) {
      try (InputStream fIn = this.mContext.getResources().getAssets().open(assetName)) {
         return readAll(fIn, code);
      } catch (Exception e) {
         e.getMessage();
         return null;
      }
   }

   private static String readAll(InputStream in, String code) throws Exception {
      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, code))) {
         String line;
         while ((line = reader.readLine()) != null) {
            sb.append(line);
         }
      }
      return sb.toString();
   }

   // -- Inner callback adapters ----------------------------------------------

   private final class ActionClientListener extends IAlphaActionClient.Stub {
      @Override
      public void onActionStop(String strActionFileName) throws RemoteException {
         if (Alpha2RobotApi.this.mActionListener != null) {
            Alpha2RobotApi.this.mActionListener.onActionStop(strActionFileName);
         }
      }
   }

   /** @deprecated text understanding relied on cloud services that are no longer reachable. */
   @Deprecated
   private final class SpeechTextUnderstand extends IAlphaTextUnderstandListener.Stub {
      @Override
      public void onAlpha2UnderStandError(int errorCode) throws RemoteException {
         if (Alpha2RobotApi.this.mRobotTextListener != null) {
            Alpha2RobotApi.this.mRobotTextListener.onAlpha2UnderStandError(errorCode);
         }
      }

      @Override
      public void onAlpha2UnderStandTextResult(String result) throws RemoteException {
         if (Alpha2RobotApi.this.mRobotTextListener != null) {
            Alpha2RobotApi.this.mRobotTextListener.onAlpha2UnderStandTextResult(result);
         }
      }
   }

   private final class SpeechClientImpl extends IAlpha2SpeechClientListener.Stub {
      @Override
      public void onServerCallBack(String text) {
         if (Alpha2RobotApi.this.mRobotClient != null) {
            Alpha2RobotApi.this.mRobotClient.onServerCallBack(text);
         }
      }

      @Override
      public void onServerPlayEnd(boolean isEnd) {
         if (Alpha2RobotApi.this.mRobotClient != null) {
            Alpha2RobotApi.this.mRobotClient.onServerPlayEnd(isEnd);
         }
      }
   }
}
