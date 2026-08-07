package com.ubtechinc.alpha2serverlib.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SpeechClientListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechCallBackListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.ISpeechInterface;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.constant.CustomLanguage;

/**
 * Binds the robot's speech service and exposes TTS playback, dictation, grammar
 * recognition and text understanding. Recognition results and TTS-end notifications are
 * forwarded to the client listener supplied at construction.
 */
public class Alpha2SpeechMainServiceUtil implements ServiceConnection {
   private static final String TAG = "Alpha2SpeechMainServiceUtil";
   private static final String ACTION = "com.ubtechinc.services.SpeechServices";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";

   /** Signalled once the speech service binder is connected and ready. */
   public interface ISpeechInitInterface {
      void initOver();
   }

   private final Context mContext;
   private final IAlpha2SpeechClientListener mClientListener;
   private final ISpeechInitInterface mSpeechInitListener;
   private final ISpeechCallBackListener.Stub mListener;
   private ISpeechInterface mService;
   private byte mSessionID;
   private boolean mBound;
   private IAlpha2SpeechGrammarListener mGrammarListener;
   private IAlphaTextUnderstandListener mTextUnderstanderListener;

   public Alpha2SpeechMainServiceUtil(Context context, IAlpha2SpeechClientListener clientListener,
         ISpeechInitInterface speechInitListener, CustomLanguage specifyLanguage) {
      this(context, clientListener, speechInitListener, specifyLanguage, ACTION);
   }

   /**
    * @param action the service action to bind - defaults to the generic
    *               "com.ubtechinc.services.SpeechServices" alias (whichever concrete
    *               engine the firmware has registered under it) when the 4-arg
    *               constructor is used. Pass {@link com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent#ALPHA_NUANCE_SPEECH_MAIN_SERVER}
    *               or {@link com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent#ALPHA_IFLYTEK_SPEECH_MAIN_SERVER}
    *               to bind a specific engine directly, bypassing that alias.
    */
   public Alpha2SpeechMainServiceUtil(Context context, IAlpha2SpeechClientListener clientListener,
         ISpeechInitInterface speechInitListener, CustomLanguage specifyLanguage, String action) {
      // specifyLanguage is advisory: the active offline engine uses its own grammar.
      this.mContext = context;
      this.mClientListener = clientListener;
      this.mSpeechInitListener = speechInitListener;
      this.mListener = new SpeechCallBackListenerImpl();
      Intent intent = new Intent(action);
      intent.setPackage(SERVICE_PACKAGE);
      this.mBound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
      Log.i(TAG, "bindService " + action);
   }

   public boolean isInitCompleted() {
      return this.mService != null;
   }

   public void setRecognizedLanguage(String strLanguage) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mGrammarListener = null;
         this.mService.setRecognizedLanguage(strLanguage);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void onSpeech(String text) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.onSpeech(this.mListener, text);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void onStopSpeech() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.onStopSpeech(this.mListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void setVoiceName(String strVoiceName) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.setVoiceName(strVoiceName);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   /**
    * Speak {@code text}. When {@code isTip} is true the utterance is low priority (it will
    * not interrupt speech already playing); when false it is high priority and interrupts.
    *
    * <p>The trailing {@code 0} is a priority/id parameter the on-robot AIDL (v1.1.7.3.20)
    * actually requires on both onPlay and onPlayHigh - confirmed by decompiling
    * ISpeechInterface$Stub.onTransact(), which reads listener/text/voiceName/language then
    * an extra int before dispatching. 0 matches what SpeechServiceImpl.onPlay's own internal
    * boolean-flag call passes through as a safe default; no observed on-robot caller uses a
    * different value.
    */
   public void onPlay(String text, String voiceName, String language, boolean isTip) {
      if (this.mService == null) {
         return;
      }
      try {
         if (isTip) {
            this.mService.onPlay(this.mListener, text, voiceName, language, 0);
         } else {
            this.mService.onPlayHigh(this.mListener, text, voiceName, language, 0);
         }
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
      }
   }

   public void onStopPlay() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.onStopPlay(this.mListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void setWakeState(boolean onWake) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.setWakeState(onWake);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   /**
    * 2026-08 新增: 對應 AIDL transaction #12 stopSpeechAndEnterIdleMode()。
    * <p>
    * 實測發現: speech_switchEngine() 一 rebind 去 direct-engine action
    * (ALPHA_NUANCE_SPEECH_MAIN_SERVER / ALPHA_IFLYTEK_SPEECH_MAIN_SERVER) 之後,
    * 機身系統進程 (com.ubtechinc.alpha2services) 入面嘅 TTS session 就會啞
    * (onPlay/onPlayHigh 都仲會回 API_ERROR_SUCCEED, 但完全冇後續嘅
    * SpeechServiceImpl/IflytekTTS/onTTsStart log, 即係話個 binder call 冧咗去
    * 一個已經壞死嘅 session, 唔會拋 RemoteException), 一直要重開機先返到正常。
    * <p>
    * 呢個方法叫機身韌體將 speech engine 拉返去 idle 狀態, 理論上可以喺唔使重開機
    * 嘅情況下清返個壞咗嘅 session。要留意呢個 call 應該搵返 generic alias
    * binding (mSpeechServiceUtil, 一開機用 ALPHA_SPEECH_MAIN_SERVER bind 嗰個)
    * 嚟做, 唔係嗰個已經死咗嘅 direct-engine binding, 因為後者本身可能已經連呢個
    * call 都冧唔到。未喺真機驗證過呢個方法係咪真係解決到問題, 純粹跟 AIDL 方法名
    * 同用途做嘅合理推測。
    */
   public void stopSpeechAndEnterIdleMode() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.stopSpeechAndEnterIdleMode();
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void textUnderstand(String strText, IAlphaTextUnderstandListener listener) {
      this.mTextUnderstanderListener = listener;
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.onTextUnderstand(strText, new IAlphaTextUnderstandListener.Stub() {
            @Override
            public void onAlpha2UnderStandError(int nErrorCode) throws RemoteException {
               if (Alpha2SpeechMainServiceUtil.this.mTextUnderstanderListener != null) {
                  Alpha2SpeechMainServiceUtil.this.mTextUnderstanderListener.onAlpha2UnderStandError(nErrorCode);
               }
            }

            @Override
            public void onAlpha2UnderStandTextResult(String strResult) throws RemoteException {
               if (Alpha2SpeechMainServiceUtil.this.mTextUnderstanderListener != null) {
                  Alpha2SpeechMainServiceUtil.this.mTextUnderstanderListener.onAlpha2UnderStandTextResult(strResult);
               }
            }
         });
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void initSpeechGrammar(String strGrammar, ISpeechGrammarInitListener listener) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.initSpeechGrammar(strGrammar, listener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void startSpeechGrammar(IAlpha2SpeechGrammarListener listener) {
      if (this.mService == null) {
         return;
      }
      try {
         // Results are routed back through the registered speech callback below, so a
         // null AIDL listener is passed to the service (matching its expected use).
         this.mGrammarListener = listener;
         this.mService.startSpeechGrammar((ISpeechGrammarListener) null);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void stopSpeechGrammar() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mGrammarListener = null;
         this.mService.stopSpeechGrammar();
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void setSelfInterrupt(boolean isInterrupt) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.setSelfInterrupt(isInterrupt);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void setStartEarLed() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.setStartEarLed();
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   /**
    * Starts speech recognition directly, without requiring the mic-array hardware to
    * detect its own wake word first (see setWakeState's limitation: that call only
    * releases/claims mic ownership, it never itself starts listening - actual wake
    * detection normally comes from a hardware callback with no app-triggerable path).
    * Results arrive the same way as any other recognition: through the registered
    * ISpeechCallBackListener's onCallBack, forwarded here to onServerCallBack.
    */
   public void startSpeechNoWakeup() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.startSpeechNoWakeup(this.mListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void disableTTSPause(boolean disable) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.disableTTSPause(disable);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void startLocalFunction(String strFunction) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.startLocalFunction(strFunction);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public boolean ReleaseConnection() {
      if (this.mService != null) {
         try {
            this.mService.unRegisterSpeechCallBackListener(this.mListener);
         } catch (RemoteException e) {
            e.printStackTrace();
         }
      }
      if (this.mBound) {
         this.mBound = false;
         try {
            this.mContext.unbindService(this);
         } catch (IllegalArgumentException e) {
            e.printStackTrace();
         }
      }
      this.mService = null;
      return true;
   }

   @Override
   public void onServiceConnected(ComponentName name, IBinder service) {
      this.mService = ISpeechInterface.Stub.asInterface(service);
      Log.i(TAG, "onServiceConnected");
      if (this.mSpeechInitListener != null) {
         this.mSpeechInitListener.initOver();
      }
      try {
         this.mSessionID = (byte) this.mService.registerSpeechCallBackListener(this.mListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceDisconnected(ComponentName name) {
      try {
         if (this.mService != null) {
            this.mService.unRegisterSpeechCallBackListener(this.mListener);
         }
      } catch (RemoteException e) {
         // See AlphaActionServiceUtil.onServiceDisconnected() for why this is expected
         // (remote process already gone by the time this fires) rather than a real error.
         Log.v(TAG, "unRegisterSpeechCallBackListener failed during disconnect (expected if the "
                 + "remote service already died): " + e);
      } finally {
         this.mService = null;
      }
   }

   /**
    * Receives raw speech-service callbacks. Grammar results are routed to the grammar
    * listener when one is active; otherwise recognition text and TTS-end go to the client.
    */
   private final class SpeechCallBackListenerImpl extends ISpeechCallBackListener.Stub {
      @Override
      public void onCallBack(int type, String text) throws RemoteException {
         if (Alpha2SpeechMainServiceUtil.this.mGrammarListener != null) {
            Alpha2SpeechMainServiceUtil.this.mGrammarListener.onSpeechGrammarResult(type, text);
         } else if (Alpha2SpeechMainServiceUtil.this.mClientListener != null) {
            Alpha2SpeechMainServiceUtil.this.mClientListener.onServerCallBack(text);
         }
      }

      @Override
      public void onPlayEnd(boolean isEnd) throws RemoteException {
         if (Alpha2SpeechMainServiceUtil.this.mClientListener != null) {
            Alpha2SpeechMainServiceUtil.this.mClientListener.onServerPlayEnd(isEnd);
         }
      }
   }
}
