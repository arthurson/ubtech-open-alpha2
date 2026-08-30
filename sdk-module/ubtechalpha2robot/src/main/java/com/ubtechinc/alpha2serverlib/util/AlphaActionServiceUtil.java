package com.ubtechinc.alpha2serverlib.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionClient;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionListListener;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlphaActionService;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import java.util.ArrayList;

/**
 * Binds the robot's action-playback service and forwards play / stop / list requests to
 * it over Binder. (The original SDK spelled this {@code AlphaActonServiceUtil}.)
 */
public class AlphaActionServiceUtil implements ServiceConnection {
   private static final String TAG = "AlphaActionServiceUtil";
   private static final String ACTION = "com.ubtechinc.services.AlphaActionServices";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";

   private final Context mContext;
   private final IAlphaActionClient mClientListener;
   private final IAlphaActionClient.Stub mActionClient;
   private IAlpha2ActionListListener mActionListListener;
   private IAlphaActionService mService;
   private boolean mBound;

   public AlphaActionServiceUtil(Context context, IAlphaActionClient client) {
      this.mContext = context;
      this.mClientListener = client;
      this.mActionClient = new ActionClientImpl();
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      // Remember whether the bind actually took: ReleaseConnection() must not unbind a
      // binding that never succeeded (unbindService throws IllegalArgumentException).
      this.mBound = this.mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
   }

   public boolean isInitCompleted() {
      return this.mService != null;
   }

   public boolean playActionName(String strActionName) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.playActionName(strActionName);
      } catch (RemoteException e) {
         e.printStackTrace();
         return false;
      }
   }

   public boolean playActionFile(String strActionFile) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.playActionFile(strActionFile);
      } catch (RemoteException e) {
         e.printStackTrace();
         return false;
      }
   }

   /**
    * Triggers an event handler on the robot side. Wire-contract param names only
    * (nEventType/param) - AIDL_REFERENCE_ALPHA2.md notes the specific event type values are
    * unverified against real hardware, so no interpretation is added here.
    */
   public void onEventHandlerTrigger(int nEventType, byte[] param) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.onEventHandlerTrigger(nEventType, param);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void disableActionPlay(boolean disable) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.disableActionPlay(disable);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public boolean isActioning() {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.isActioning();
      } catch (RemoteException e) {
         e.printStackTrace();
         return false;
      }
   }

   public boolean isCompleted() {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.isCompleted();
      } catch (RemoteException e) {
         return false;
      }
   }

   public void stopActionPlay() {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.stopActionPlay();
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public void getActionList(IAlpha2ActionListListener listener) {
      this.mActionListListener = listener;
      if (this.mService == null) {
         // The service binds asynchronously; a null binder means the caller invoked
         // before the bind completed. Return rather than NPE (only RemoteException is
         // handled below).
         return;
      }
      try {
         this.mService.getActionList(new ActionListListenerImpl());
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   public boolean ReleaseConnection() {
      // The binder may already be gone (onServiceDisconnected nulls mService), so
      // null-guard the unregister; an NPE here would otherwise skip unbindService and
      // leak the ServiceConnection.
      if (this.mService != null) {
         try {
            this.mService.unRegisterActionClient(this.mActionClient);
         } catch (RemoteException e) {
            e.printStackTrace();
         }
      }
      // Unbind only if the bind took, and never twice.
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
      Log.v(TAG, "onServiceConnected");
      this.mService = IAlphaActionService.Stub.asInterface(service);
      try {
         this.mService.registerActionClient(this.mActionClient);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceDisconnected(ComponentName name) {
      Log.v(TAG, "onServiceDisconnected");
      try {
         if (this.mService != null) {
            this.mService.unRegisterActionClient(this.mActionClient);
         }
      } catch (RemoteException e) {
         // The remote process (Alpha2Services) has already died by the time
         // onServiceDisconnected() fires, so unregistering against it is inherently
         // racy - a DeadObjectException here just means "too late, it's already gone",
         // not a real failure worth a full stack trace in logcat (printStackTrace()
         // was previously used here, which spammed several lines per occurrence and
         // made genuine errors harder to spot amongst routine disconnect noise).
         Log.v(TAG, "unRegisterActionClient failed during disconnect (expected if the "
                 + "remote service already died): " + e);
      } finally {
         // Clear the binder regardless so isInitCompleted() reflects the loss.
         this.mService = null;
      }
   }

   /** Forwards the robot's action-stop callback to the client listener. */
   private final class ActionClientImpl extends IAlphaActionClient.Stub {
      @Override
      public void onActionStop(String strActionFileName) throws RemoteException {
         if (AlphaActionServiceUtil.this.mClientListener != null) {
            AlphaActionServiceUtil.this.mClientListener.onActionStop(strActionFileName);
         }
      }
   }

   /**
    * Parses the robot's action-list response - a single string of "##"-separated fields
    * grouped four at a time ([id, type, cn-name, en-name]) - into a list of actions.
    */
   private final class ActionListListenerImpl extends IAlphaActionListListener.Stub {
      @Override
      public void onGetActionList(String list) throws RemoteException {
         ArrayList<ArrayList<String>> actionList = new ArrayList<>();
         if (list != null) {
            String[] fields = list.split("##");
            if (fields.length % 4 == 0) {
               for (int i = 0; i < fields.length / 4; ++i) {
                  int base = 4 * i;
                  ArrayList<String> action = new ArrayList<>();
                  action.add(fields[base]);
                  action.add(fields[base + 1]);
                  action.add(fields[base + 2]);
                  action.add(fields[base + 3]);
                  actionList.add(action);
               }
            }
         }
         if (AlphaActionServiceUtil.this.mActionListListener != null) {
            AlphaActionServiceUtil.this.mActionListListener.onGetActionList(actionList);
         }
      }
   }
}
