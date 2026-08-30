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
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishOfflineUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaEnglishUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IReplaySpeechCallback;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortHeaderOnRcvListener;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2BlueToothSerialPortOnRcvListener;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortOnRcvListener;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2CustomMessageListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.util.Alpha2BlueToothSerialServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2SerialHeaderServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2SerialServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.alpha2serverlib.util.Alpha2XmppServiceUtil;
import com.ubtechinc.alpha2serverlib.util.AlphaActionServiceUtil;
import com.ubtechinc.alpha2serverlib.util.AlphaMainServiceUtil;
import com.ubtechinc.constant.CustomLanguage;
import com.ubtechinc.constant.StaticValue;
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
public class Alpha2RobotApi implements Alpha2SerialPortOnRcvListener, Alpha2SerialPortHeaderOnRcvListener,
      Alpha2BlueToothSerialPortOnRcvListener {
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
   private Alpha2BlueToothSerialServiceUtil mBlueToothSerialServiceUtil;
   private Alpha2SpeechMainServiceUtil mSpeechServiceUtil;
   /**
    * 2026-08 新增: 獨立的 ASR-only binding。
    * <p>
    * 之前 {@link #speech_switchEngine} 會 release 掉 {@link #mSpeechServiceUtil}
    * (一開機用 generic alias {@link Alpha2Intent#ALPHA_SPEECH_MAIN_SERVER} bind
    * 那個), 再用 direct-engine action (ALPHA_NUANCE_SPEECH_MAIN_SERVER /
    * ALPHA_IFLYTEK_SPEECH_MAIN_SERVER) rebind 到**同一個**欄位。但 MainActivity.java
    * speech/tts 的 comment 已經證實: direct-engine binding 會讓 playback 完全壞掉
    * (包括本身用 generic binding 好好的那個引擎都會壞掉)。結果就是「切完 ASR
    * engine 之後, wake word 觸發的 TTS 變成無聲」——TTS 一直都是經
    * mSpeechServiceUtil.onPlay() 送出, 但這個 binding 已經被 switchEngine 換成了
    * 壞的那種。
    * <p>
    * 修正: TTS 永遠只走 {@link #mSpeechServiceUtil} (generic alias, 一開機
    * bind 一次之後永遠不再 release/rebind)。ASR engine 切換改用這個獨立的
    * {@code mAsrServiceUtil} 欄位, 由它自己 direct-bind 去指定引擎, 壞得再徹底都不會
    * 連累到 TTS 那個 binding。ASR 專屬的 call (startSpeechNoWakeup /
    * setRecognizedLanguage / grammar 三個方法) 跟著 {@link #currentAsrTarget()}
    * 選用哪個 util: 未切換過 engine 之前用回 mSpeechServiceUtil (和以前行為
    * 一致), 切換了之後就用 mAsrServiceUtil。
    */
   private Alpha2SpeechMainServiceUtil mAsrServiceUtil;
   private Alpha2XmppServiceUtil mXmppServiceUtil;

    private IAlpha2RobotClientListener mRobotClient;
    private IAlpha2RobotTextUnderstandListener mRobotTextListener;
    private IAlpha2SpeechGrammarInitListener mSpeechGrammarInitListener;
    private AlphaActionClientListener mActionListener;

    /** Callback for async servo read responses (command 0x0D). status 0x00=ok, 0x01=offline/busy */
    public interface OnServoDataReadListener {
        void onServoDataRead(byte servoId, int angle, int offset);
        default void onServoDataReadError(byte servoId, int status) {
            // default: treat as read with offset 0 for backward compat
            onServoDataRead(servoId, 0, 0);
        }
    }

    private OnServoDataReadListener mServoReadListener;

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
      if (this.mAsrServiceUtil != null) {
         this.mAsrServiceUtil.ReleaseConnection();
         this.mAsrServiceUtil = null;
      }
      if (this.mChestSerialServiceUtil != null) {
         this.mChestSerialServiceUtil.ReleaseConnection();
         this.mChestSerialServiceUtil = null;
      }
      if (this.mHeaderSerialServiceUtil != null) {
         this.mHeaderSerialServiceUtil.ReleaseConnection();
         this.mHeaderSerialServiceUtil = null;
      }
      if (this.mBlueToothSerialServiceUtil != null) {
         this.mBlueToothSerialServiceUtil.ReleaseConnection();
         this.mBlueToothSerialServiceUtil = null;
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

   public boolean initBlueToothSerialApi() {
      if (this.mBlueToothSerialServiceUtil == null) {
         this.mBlueToothSerialServiceUtil = new Alpha2BlueToothSerialServiceUtil(this.mContext, this);
      }
      return true;
   }

   /**
    * Blocks (off the main thread only - see Alpha2SerialServiceUtil.waitForInitComplete())
    * until the chest serial AIDL bind completes, up to {@code timeoutMs}.
    *
    * <p>2026-08 修正: 之前這個 timeoutMs 參數其實沒傳到底層
    * {@code Alpha2SerialServiceUtil.waitForInitComplete()}, 那個底層 method 只有
    * 沒參數版本, 永遠硬吃 ~3s (300 ticks x 10ms), 讓傳入的 timeoutMs 完全被忽略 -
    * 例如 caller 傳 1000 (期望 1s 封頂) 實際會等到 3s。現在底層加了
    * {@code waitForInitComplete(long)} overload, timeoutMs 會真正生效。
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
         this.mChestSerialServiceUtil.waitForInitComplete(timeoutMs);
      }
   }

   /** See {@link #waitChestReady(long)} - same race, header/LED side. */
   public void waitHeaderReady(long timeoutMs) {
      if (this.mHeaderSerialServiceUtil != null) {
         this.mHeaderSerialServiceUtil.waitForInitComplete(timeoutMs);
      }
   }

   /** See {@link #waitChestReady(long)} - same race, Bluetooth serial side. */
   public void waitBlueToothSerialReady(long timeoutMs) {
      if (this.mBlueToothSerialServiceUtil != null) {
         this.mBlueToothSerialServiceUtil.waitForInitComplete(timeoutMs);
      }
   }

   public boolean isChestReady() {
      return this.mChestSerialServiceUtil != null && this.mChestSerialServiceUtil.isInitCompleted();
   }

   public boolean isHeaderReady() {
      return this.mHeaderSerialServiceUtil != null && this.mHeaderSerialServiceUtil.isInitCompleted();
   }

   public boolean isBlueToothSerialReady() {
      return this.mBlueToothSerialServiceUtil != null && this.mBlueToothSerialServiceUtil.isInitCompleted();
   }

   /** See isChestAvailable()/isHeaderAvailable() - same "object constructed" guard, not
    *  "bind actually completed" (use waitBlueToothSerialReady() for that). */
   public UbxErrorCode.API_ERROR_CODE isBlueToothSerialAvailable() {
      if (this.mBlueToothSerialServiceUtil == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * Sends a raw command frame over the Bluetooth serial link (IAlpha2BlueToothSerialPortService
    * transaction #2). Unlike chest/header serial's sendCommand (wrapped internally by
    * higher-level methods with fixed, confirmed nCmd values - see e.g. chest_SendFreeAngle),
    * this service's nCmd values are unverified against real hardware (see AIDL_REFERENCE_ALPHA2.md
    * 3.3), so this is exposed as a thin, general-purpose passthrough rather than
    * wrapped into named higher-level calls.
    */
   public UbxErrorCode.API_ERROR_CODE bluetooth_sendCommand(byte nCmd, byte[] nParam) {
      UbxErrorCode.API_ERROR_CODE available = this.isBlueToothSerialAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mBlueToothSerialServiceUtil.sendCommand(nCmd, nParam, nParam == null ? 0 : nParam.length);
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   /** Sends an AT command string over the Bluetooth serial link (transaction #3). */
   public UbxErrorCode.API_ERROR_CODE bluetooth_sendATCMD(String cmd) {
      UbxErrorCode.API_ERROR_CODE available = this.isBlueToothSerialAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      this.mBlueToothSerialServiceUtil.sendATCMD(cmd);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
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

   /**
    * 2026-08 新增: 之前 initSpeechApi() 只有三個 arg 的 overload, 永遠用
    * Alpha2SpeechMainServiceUtil 的 4-arg constructor, 也就是永遠綁到
    * {@link Alpha2Intent#ALPHA_SPEECH_MAIN_SERVER} 這個通用別名, 由機身韌體自己
    * 決定實際 route 去哪個引擎 (Nuance 還是 iFlytek), app 這一層沒得指定。
    * <p>
    * SDK 底層 (Alpha2SpeechMainServiceUtil) 其實一早已經有五參數的 constructor
    * 給人直接傳 {@link Alpha2Intent#ALPHA_NUANCE_SPEECH_MAIN_SERVER} 或
    * {@link Alpha2Intent#ALPHA_IFLYTEK_SPEECH_MAIN_SERVER}, 強制綁到指定引擎,
    * 完全繞過那個「通用別名」——之前這個路徑在 TTS 側試過直接 binding, 引致
    * playback 完全壞掉所以撤回了 (見 MainActivity.java speech/tts 的 comment),
    * 但那個結論是 TTS-only 的測試結果, ASR (initSpeechApi/registerSpeechCallBackListener
    * 這條 receive 路徑) 從未用這個 direct-binding 方式試過。現在加回這個
    * overload, 讓 app 層可以在 ASR 這邊獨立試: 直接綁 iFlytek 看 wake
    * word/辨識會不會用回 iFlytek 自己那套 (而不是一直假設只有 Nuance 能用)。
    */
   public boolean initSpeechApi(IAlpha2RobotClientListener robotClient,
         Alpha2SpeechMainServiceUtil.ISpeechInitInterface speechInitListener, CustomLanguage specifyLanguage,
         String speechServiceAction) {
      if (this.mSpeechServiceUtil == null) {
         this.mRobotClient = robotClient;
         IAlpha2SpeechClientListener.Stub clientListener = new SpeechClientImpl();
         this.mSpeechServiceUtil = new Alpha2SpeechMainServiceUtil(
               this.mContext, clientListener, speechInitListener, specifyLanguage, speechServiceAction);
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

   public UbxErrorCode.API_ERROR_CODE action_PlayActionFile(String actionFile) {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.playActionFile(actionFile);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * Triggers an event handler on the robot side. See AlphaActionServiceUtil's
    * onEventHandlerTrigger javadoc - the accepted nEventType values and param
    * payload shape are unverified against real hardware.
    */
   public UbxErrorCode.API_ERROR_CODE action_TriggerEventHandler(int eventType, byte[] param) {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.onEventHandlerTrigger(eventType, param);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   public UbxErrorCode.API_ERROR_CODE action_DisableActionPlay(boolean disable) {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mActionServiceUtil.disableActionPlay(disable);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** @return true if the robot is currently playing an action; false if not, or if
    *          the action service isn't bound yet. */
   public boolean action_IsActioning() {
      if (this.mActionServiceUtil == null || !this.mActionServiceUtil.isInitCompleted()) {
         return false;
      }
      return this.mActionServiceUtil.isActioning();
   }

   // -- Speech / TTS ---------------------------------------------------------

   public boolean speech_SetMIC(boolean isWake) {
      if (this.mSpeechServiceUtil != null) {
         this.mSpeechServiceUtil.setWakeState(isWake);
      }
      return true;
   }

   /**
    * 2026-08 新增: 用來在 runtime 切換到指定的 speech engine action
    * (見 {@link Alpha2Intent#ALPHA_NUANCE_SPEECH_MAIN_SERVER} /
    * {@link Alpha2Intent#ALPHA_IFLYTEK_SPEECH_MAIN_SERVER})。
    * <p>
    * 之前 initSpeechApi() 裡面有 `if (mSpeechServiceUtil == null)` 這樣的 guard,
    * 也就是一旦 app 開機初始化過一次之後, 之後怎麼 call initSpeechApi() 都會被
    * 跳過, 沒得再重新綁到第二個 action —— 一開機 bind 了哪個引擎就一輩子只有
    * 那個。要真正做到「手動切換 Nuance/iFlytek」, 不能只加多個
    * overload, 還要有一個明確的「release 掉舊的連接、再用新 action 重新
    * bind」的入口, 才可以在不重開 app 的情況下切換。
    * <p>
    * 這個方法會: (1) 用回 {@link Alpha2SpeechMainServiceUtil#ReleaseConnection()}
    * 將現有連接 unregister listener + unbind service, (2) 用新的
    * speechServiceAction 建立一個新的 Alpha2SpeechMainServiceUtil 重新綁定。
    * mRobotClient (callback 收結果那個 listener) 會沿用之前 initSpeechApi()
    * 傳入的那個, 不用再傳一次。
    *
    * @param speechInitListener 新連接完成時的 callback (bind 是 async, 看
    *                            onServiceConnected 什麼時候到)
    * @param specifyLanguage    advisory 語言提示, 意義和 initSpeechApi() 那個
    *                            參數一樣 (見 Alpha2SpeechMainServiceUtil 那句
    *                            "specifyLanguage is advisory" comment)
    * @param speechServiceAction 想切到哪個 action, 例如
    *                            Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER
    * @return false 如果之前沒 call 過 initSpeechApi() (沒 mRobotClient 可以沿用)
    */
   public boolean speech_switchEngine(Alpha2SpeechMainServiceUtil.ISpeechInitInterface speechInitListener,
         CustomLanguage specifyLanguage, String speechServiceAction) {
      if (this.mRobotClient == null) {
         // 沒 initSpeechApi() 過, 沒 client listener 可以沿用, 不應該在這個
         // 方法裡面重新要求傳一個 —— 這個方法設計是給「已經 init 了、想切
         // engine」這個情境用, 沒 init 過應該直接用 initSpeechApi()。
         return false;
      }
      // 2026-08 修正: 之前這裡 release + rebind 的是 mSpeechServiceUtil ——也就是
      // TTS (speech_startTTS/speech_StopTTS/speech_SetMIC) 一直走著那條 generic
      // alias binding。Direct-engine binding 已知會弄壞 playback (見
      // mAsrServiceUtil 欄位頂部的 comment), 所以這裡改用獨立的
      // mAsrServiceUtil, 不再動 mSpeechServiceUtil, TTS 那個 binding 永遠維持在
      // generic alias, 不會因為切 ASR engine 而變成無聲。
      if (this.mAsrServiceUtil != null) {
         this.mAsrServiceUtil.ReleaseConnection();
         this.mAsrServiceUtil = null;
      }
      IAlpha2SpeechClientListener.Stub clientListener = new SpeechClientImpl();
      this.mAsrServiceUtil = new Alpha2SpeechMainServiceUtil(
            this.mContext, clientListener, speechInitListener, specifyLanguage, speechServiceAction);
      return true;
   }

   /**
    * ASR 專屬 call (startSpeechNoWakeup / setRecognizedLanguage / grammar 三個
    * 方法) 應該用的 util: 未曾 speech_switchEngine() 過就跟回以前行為, 用
    * initSpeechApi() 那個 generic alias binding (mSpeechServiceUtil); 切換了
    * engine 之後就轉用那個獨立 direct-bind 的 mAsrServiceUtil, 不再影響 TTS。
    */
   private Alpha2SpeechMainServiceUtil currentAsrTarget() {
      return this.mAsrServiceUtil != null ? this.mAsrServiceUtil : this.mSpeechServiceUtil;
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
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.startSpeechNoWakeup();
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
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.setRecognizedLanguage(strLanguage);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * @deprecated dictation is not the primary recognition path on this firmware.
    * <p>
    * 2026-08 修正: 之前這裡和 speech_stopRecognized() 一樣寫死了
    * {@link #mSpeechServiceUtil}, 沒跟 {@link #currentAsrTarget()} —— 也就是說
    * 就算已經 speech_switchEngine() 切到 iFlytek (mAsrServiceUtil), speech/inject
    * 送的文字仍然會走錯到舊那個通用 alias binding (mSpeechServiceUtil, 這台機
    * 實測下來預設 route 到已經死掉的 Nuance), 和 currentAsrEngine 顯示的狀態
    * 不一致。現在改用 currentAsrTarget(), 和 speech_setRecognizedLanguage() /
    * speech_startSpeechNoWakeup() 等其他 method 看齊。
    * <p>
    * 注意: 這裡只修正了 target 選錯的問題, onSpeech() 本身是否真的會觸發到
    * local_semantic fuzzy matcher 那條生效中的 pipeline, 未經 logcat 驗證過,
    * 要實測才知道。
    */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE speech_startRecognized(String text) {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.onSpeech(text);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * @deprecated dictation is not the primary recognition path on this firmware.
    * <p>
    * 2026-08 修正: 和 speech_startRecognized() 一樣的 target 問題, 改用
    * currentAsrTarget() 讓它跟著實際生效中的 engine binding。
    */
   @Deprecated
   public UbxErrorCode.API_ERROR_CODE speech_stopRecognized() {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.onStopSpeech();
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * 2026-08 新增: 試驗性的「重置」入口, 想看看能不能叫回那個切完 engine 之後
    * 死掉的 TTS session, 不用整台機重開機。見
    * {@link Alpha2SpeechMainServiceUtil#stopSpeechAndEnterIdleMode()} 那句
    * comment 解釋病灶和這個方法的推測原理。
    * <p>
    * 特意用 mSpeechServiceUtil (generic alias binding) 來 call, 不用
    * mAsrServiceUtil (direct-engine binding) —— 因為壞掉那個很可能就是
    * mAsrServiceUtil 這條路, 叫它自己 call 這個 reset 都未必有反應, generic
    * alias 從頭到尾沒被 rebind 過, call 得通的機會大一些。
    *
    * @return false 如果 mSpeechServiceUtil 都還沒 init 過
    */
   public boolean speech_resetToIdle() {
      if (this.mSpeechServiceUtil == null) {
         return false;
      }
      this.mSpeechServiceUtil.stopSpeechAndEnterIdleMode();
      return true;
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
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      this.mSpeechGrammarInitListener = listener;
      target.initSpeechGrammar(strGrammar, new ISpeechGrammarInitListener.Stub() {
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
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.startSpeechGrammar(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * Registers a listener for online English semantic understanding
    * (ISpeechInterface transaction #15). Unverified against real hardware - see
    * Alpha2SpeechMainServiceUtil.onEnglishUnderstand javadoc.
    */
   public UbxErrorCode.API_ERROR_CODE speech_onEnglishUnderstand(IAlphaEnglishUnderstandListener listener) {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.onEnglishUnderstand(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * Registers a listener for offline English semantic understanding
    * (ISpeechInterface transaction #16). Unverified against real hardware - see
    * Alpha2SpeechMainServiceUtil.setEnglishOfflineListener javadoc.
    */
   public UbxErrorCode.API_ERROR_CODE speech_setEnglishOfflineListener(IAlphaEnglishOfflineUnderstandListener listener) {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.setEnglishOfflineListener(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /**
    * Registers a listener to receive replayed ASR history records
    * (ISpeechInterface transaction #22). Unverified against real hardware - see
    * Alpha2SpeechMainServiceUtil.registerReplayContentListener javadoc.
    */
   public UbxErrorCode.API_ERROR_CODE speech_registerReplayContentListener(IReplaySpeechCallback listener) {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.registerReplayContentListener(listener);
      return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
   }

   /** Corrected name; the original SDK spelled this {@code speech_stopGrammaer}. */
   public UbxErrorCode.API_ERROR_CODE speech_stopGrammar() {
      Alpha2SpeechMainServiceUtil target = currentAsrTarget();
      if (target == null) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      target.stopSpeechGrammar();
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
      // 2026-08 修正: sub-command byte 之前是 2, 純粹是憑感覺的未證實假設
      // (下面 comment 一早已經標明 "unverified")。反編譯官方 UBTech
      // alpha2demo.apk (firmware 1.1.1.14) ActionMainActivity.enableSonar()
      // 確認, 官方實測得 sonar 的 sequence 是 sendCommand(4, [10, distance], 2)
      // - sub-command byte 應該是 10, 不是 2。這才是讓 sonar 讀數真正開始
      // 經 SONAR_DISTANCE_ACTION (com.ubtechinc.sonar.distance) 送出來的正確
      // config command。
      //
      // The chest ultrasonic (sonar) does not stream obstacle events by default. This
      // sends the chest board's sonar-configure frame: command 4 (CHEST_CMD_SETTING),
      // sub-command 10, then a trigger-distance byte - after which sonar_distance
      // readings start arriving via a separate broadcast, StaticValue.SONAR_DISTANCE_ACTION
      // ("com.ubtechinc.sonar.distance"), extra key SONAR_DISTANCE_EXTRA ("sonar_distance",
      // int). NOT via this AIDL link's onListenSerialPortRcvData() callback - confirmed on
      // real hardware that channel only ever receives this config command's own 2-byte ack.
      if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      byte[] data = {10, (byte) distance};
      boolean sent = this.mChestSerialServiceUtil.sendCommand((byte) 4, data, data.length);
      return sent
            ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
            : UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
   }

   /** Sends raw bytes over the chest serial link, bypassing sendCommand's frame
    *  encapsulation entirely (IAlpha2SerialPortService transaction #3). */
   public UbxErrorCode.API_ERROR_CODE chest_sendRawData(byte[] data) {
      if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
         return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
      }
      boolean sent = this.mChestSerialServiceUtil.sendRawData(data, data == null ? 0 : data.length);
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   /**
    * ⚠️ 未經真機驗證 (unverified on real hardware) - 2026-08 反編譯官方
    * com.ubtechinc.alpha2services 3.0.0.2 APK 逆出來, 對應 SecurityCameraUtil
    * (com.ubtechinc.k.a) 的 c(boolean)/d(boolean):
    *
    *   Lcom/ubtechinc/alpha2serverlib/c/b;->b()  // 也就是這個 chest serial AIDL 連線
    *     .a(72, new byte[]{on ? 1 : 0}, 1)        // cmd=72 ("PIRENABLE"), param[0]=開關
    *
    * 官方裡面這個 cmd 只在 SecurityCameraUtil.a(boolean, boolean) 一個更大的
    * 「開監控」流程裡面被 call (還會連帶開頭部相機 App), 這裡單獨抽出 cmd=72
    * 這個底層硬體開關本身, 不跟著官方整套「安全監控」邏輯 (錄影/上傳雲端等)。
    *
    * 和 chest_configureSonar() 那個 sub-command byte 一樣, 這個 cmd 值純粹來自
    * bytecode 反編譯, 沒在這台機實測過 (兩份提供過的 logcat 裡面, chest cmd
    * 只出現過 -111/-115, 從未見過 72) - chest_configureSonar() 的 comment 已經
    * 有一次「反編譯出來的 sub-command byte 原來錯了, 要真機 log 才能證實回正確
    * 數值」的前科, 所以這個方法一樣要當「未證實的假設」看待, 送出去之後要看
    * logcat (Alpha2SerialServiceUtil 的 TAG, 或者 onListenSerialPortRcvData
    * 的 ack 幀) 才知道機身有沒有反應。如果真機測試證實這個 cmd 不對, 只有這個
    * method 要改, PIR broadcast 監聽的 code path (RobotEventReceiver 的
    * "com.ubtech.securityCamera.pirStatus" case) 不受影響。
    */
    public UbxErrorCode.API_ERROR_CODE chest_setPirSensorEnabled(boolean enabled) {
       if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       byte[] data = {(byte) (enabled ? 1 : 0)};
       boolean sent = this.mChestSerialServiceUtil.sendCommand((byte) 72, data, data.length);
       return sent
             ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED
             : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    /**
     * 讀取胸口 MCU 韌體版本 — 對應 StaticValue.CHEST_READ_VERSION (51 / 0x33)。
     * <p>
     * 底層經 chest 的 IAlpha2SerialPortService.sendCommand(sessionId, 51, null, 0)
     * 送出，對應 alpha2services 內部的 chest 序列口處理，MCU 回覆的完整 wire frame
     * (F8 8F len 01 00 33 payload sum ED) 會經 {@link #onListenSerialPortRcvData}
     * (AIDL transaction #0) 回調送回。這個方法本身只是 fire-and-forget 的發送，
     * 調用方需配合 {@link #onListenSerialPortRcvData} 去捕捉回覆並解析 payload。
     * <p>
     * 版本 payload 格式為 ASCII 字串 (例如 "V1.18" / "V3.52") 或二進制版本號，
     * 由上層 {@link com.open.alpha2.MainActivity} 的 parseVersionFrame() 統一解析。
     */
    public UbxErrorCode.API_ERROR_CODE chest_readFirmwareVersion() {
       if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       boolean sent = this.mChestSerialServiceUtil.sendCommand(StaticValue.CHEST_READ_VERSION, null, 0);
       return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    /**
     * 讀取頭部 MCU 韌體版本 — 對應 StaticValue.HEADER_READ_VERSION (51 / 0x33)，
     * 經 head 的 IAlpha2SerialPortService (AlphaSerialPortHeaderServices) 送出，
     * 回覆經 {@link #onListenSerialPortHeaderRcvData}。
     */
    public UbxErrorCode.API_ERROR_CODE header_readFirmwareVersion() {
       if (this.mHeaderSerialServiceUtil == null || !this.mHeaderSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       boolean sent = this.mHeaderSerialServiceUtil.sendCommand(StaticValue.HEADER_READ_VERSION, null, 0);
       return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    // -- Chest firmware upgrade (48/49/50) — mirrors alpha2services' com.ubtechinc.h.a.a --------
    // 對應 StaticValue CHES_CMD_START_UPDATE(48)/CHES_CMD_UPDATE_PAGE(49)/CHES_CMD_UPDATE_END(50)
    // 協議見 ag_chess/com/ubtechinc/h/a/a$b.java:63 run() 與 a.java:96 onListen 回調

    public UbxErrorCode.API_ERROR_CODE chest_startUpdate(int fileLen) {
       if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       byte[] p = new byte[4];
       p[0] = (byte) ((fileLen >> 24) & 0xFF);
       p[1] = (byte) ((fileLen >> 16) & 0xFF);
       p[2] = (byte) ((fileLen >> 8) & 0xFF);
       p[3] = (byte) (fileLen & 0xFF);
       boolean sent = this.mChestSerialServiceUtil.sendCommand(StaticValue.CHES_CMD_START_UPDATE, p, p.length);
       return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    public UbxErrorCode.API_ERROR_CODE chest_updatePage(byte[] pageData, int pageLen) {
       if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       // 原廠格式：[lenHi, lenLo, data[0..len-1]]  a$b.java:39
       byte[] p = new byte[pageLen + 2];
       p[0] = (byte) ((pageLen >> 8) & 0xFF);
       p[1] = (byte) (pageLen & 0xFF);
       System.arraycopy(pageData, 0, p, 2, pageLen);
       boolean sent = this.mChestSerialServiceUtil.sendCommand(StaticValue.CHES_CMD_UPDATE_PAGE, p, p.length);
       return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    public UbxErrorCode.API_ERROR_CODE chest_endUpdate(byte[] md5) {
       if (this.mChestSerialServiceUtil == null || !this.mChestSerialServiceUtil.isInitCompleted()) {
          return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
       }
       if (md5 == null || md5.length != 16) return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
       boolean sent = this.mChestSerialServiceUtil.sendCommand(StaticValue.CHES_CMD_UPDATE_END, md5, md5.length);
       return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
    }

    public void chest_resetSerialForUpgrade() {
       try {
           if (this.mChestSerialServiceUtil != null) {
               this.mChestSerialServiceUtil.ReleaseConnection();
               this.mChestSerialServiceUtil = null;
           }
       } catch (Exception ignored) {}
       try { Thread.sleep(500); } catch (InterruptedException ignored) {}
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

   /** Sends raw bytes over the head serial link, bypassing sendCommand's frame
    *  encapsulation entirely (IAlpha2SerialPortService transaction #3). */
   public UbxErrorCode.API_ERROR_CODE header_sendRawData(byte[] data) {
      UbxErrorCode.API_ERROR_CODE available = this.isHeaderAvailable();
      if (available != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
         return available;
      }
      boolean sent = this.mHeaderSerialServiceUtil.sendRawData(data, data == null ? 0 : data.length);
      return sent ? UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED : UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
   }

   /** Returns the robot's serial number (IAlpha2SerialPortService transaction #8),
    *  or null if the header serial service isn't bound yet or the call fails. */
   public String header_getRobotSerialNumber() {
      if (this.mHeaderSerialServiceUtil == null) {
         return null;
      }
      return this.mHeaderSerialServiceUtil.getRobotSerialNumber();
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

   @Override
   public void onListenBlueToothSerialPortRcvData(byte[] bytes, int len) {
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
