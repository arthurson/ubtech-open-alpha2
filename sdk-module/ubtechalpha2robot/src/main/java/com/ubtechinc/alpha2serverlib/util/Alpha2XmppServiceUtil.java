package com.ubtechinc.alpha2serverlib.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2XmppCallBack;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2XmppListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2CustomMessageListener;

/**
 * Binds the robot's custom-message ("XMPP") service so an app can exchange messages with
 * other apps / peers through the robot. Kept for API completeness; unverified on current
 * firmware. Process-wide singleton, matching the original SDK.
 */
public class Alpha2XmppServiceUtil implements ServiceConnection {
   private static final String TAG = "Alpha2XmppServiceUtil";
   private static final String ACTION = "com.ubtechinc.services.Alpha2XmppServices";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";

   private static volatile Alpha2XmppServiceUtil sInstance;

   private final Context mContext;
   private final String mAppId;
   private final IAlpha2CustomMessageListener mCustomMessageListener;
   private final IAlpha2XmppCallBack.Stub mListener;
   private IAlpha2XmppListener mService;
   private boolean mBound;

   public static Alpha2XmppServiceUtil getInstance(Context context, String appID,
         IAlpha2CustomMessageListener listener) {
      if (sInstance == null) {
         synchronized (Alpha2XmppServiceUtil.class) {
            if (sInstance == null) {
               sInstance = new Alpha2XmppServiceUtil(context, appID, listener);
            }
         }
      }
      return sInstance;
   }

   private Alpha2XmppServiceUtil(Context context, String appID, IAlpha2CustomMessageListener listener) {
      this.mContext = context;
      this.mAppId = appID;
      this.mCustomMessageListener = listener;
      this.mListener = new XmppCallBackImpl();
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      this.mBound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
      Log.i(TAG, "bindService " + ACTION);
   }

   public void sendCustomXmppMessage(int type, String appId, String message) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.sendCustomXmppMessage(type, appId, message);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public boolean ReleaseConnection() {
      if (this.mService != null) {
         try {
            this.mService.unRegisterXmppCallBackListener(this.mListener);
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
      sInstance = null;
      return true;
   }

   @Override
   public void onServiceConnected(ComponentName name, IBinder service) {
      this.mService = IAlpha2XmppListener.Stub.asInterface(service);
      try {
         this.mService.registerXmppCallBackListener(this.mAppId, this.mListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceDisconnected(ComponentName name) {
      try {
         if (this.mService != null) {
            this.mService.unRegisterXmppCallBackListener(this.mListener);
         }
      } catch (RemoteException e) {
         e.printStackTrace();
      } finally {
         this.mService = null;
      }
   }

   private final class XmppCallBackImpl extends IAlpha2XmppCallBack.Stub {
      @Override
      public void onReceiveMessage(String message) throws RemoteException {
         if (Alpha2XmppServiceUtil.this.mCustomMessageListener != null) {
            Alpha2XmppServiceUtil.this.mCustomMessageListener.onReceiveMessage(message.getBytes());
         }
      }
   }
}
