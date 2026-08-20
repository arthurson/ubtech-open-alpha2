package com.ubtechinc.updatemanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.ubt.lynxupdate.IClientListener;
import com.ubt.lynxupdate.IUpdataBussiness;

/**
 * Binds the robot's OTA/firmware update service.
 *
 * <p>This is a genuinely separate integration point from the rest of this SDK: every
 * other {@code *ServiceUtil} class binds a component inside {@code com.ubtechinc.
 * alpha2services} (the same APK this SDK's AIDL was reverse engineered from). This one
 * targets a different installed package entirely - {@code com.ubt.lynxupdate} - via
 * {@code Intent("com.ubt.lynxupdate.services.UpdateAidlService").setPackage(
 * "com.ubt.lynxupdate")}. If that package isn't installed on the device, {@link
 * #bindUpdateService()} returns {@code false} and no callback ever fires - there is no
 * error callback for "service not found", only for errors reported after a successful
 * bind (see {@link IClientListener#onError(int)}).
 *
 * <p>Both AIDL interfaces here were confirmed by decompiling this APK's {@code
 * IClientListener$Stub}/{@code IUpdataBussiness$Stub}.onTransact() directly - see
 * IClientListener.aidl / IUpdataBussiness.aidl for the transaction-id-level detail on
 * what's confirmed verbatim (IClientListener's method names) versus inferred from call
 * sites (IUpdataBussiness's, which are proguard-obfuscated to a/b/c in this build).
 */
public class LynxUpdateServiceUtil implements ServiceConnection {
   private static final String TAG = "LynxUpdateServiceUtil";
   private static final String ACTION = "com.ubt.lynxupdate.services.UpdateAidlService";
   private static final String SERVICE_PACKAGE = "com.ubt.lynxupdate";

   /** Passed to {@link #triggerUpdate(int)}: start downloading the update package. */
   public static final int MODE_DOWNLOAD = 1;
   /** Passed to {@link #triggerUpdate(int)}: execute/install an already-downloaded update. */
   public static final int MODE_EXECUTE_UPDATE = 2;

   private final Context mContext;
   private final IClientListener mClientListener;
   private IUpdataBussiness mService;
   private boolean mBound;

   public LynxUpdateServiceUtil(Context context, IClientListener listener) {
      this.mContext = context.getApplicationContext();
      this.mClientListener = listener;
   }

   /**
    * Binds the update service and registers {@code listener} (from the constructor) once
    * connected. Confirmed against this APK: the real client always constructs the Intent
    * exactly as below, then immediately registers its listener from
    * {@link #onServiceConnected} - there is no separate "wait until ready" callback.
    */
   public boolean bindUpdateService() {
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      this.mBound = this.mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
      return this.mBound;
   }

   public void releaseConnection() {
      if (this.mService != null) {
         try {
            this.mService.unregisterClientListener();
         } catch (RemoteException | RuntimeException e) {
            e.printStackTrace();
         }
      }
      if (this.mBound) {
         this.mContext.unbindService(this);
         this.mBound = false;
      }
      this.mService = null;
   }

   public void checkUpdate() {
      if (this.mService == null) {
         Log.w(TAG, "checkUpdate: mService is null - call bindUpdateService() first");
         return;
      }
      try {
         this.mService.checkUpdate();
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
      }
   }

   public void executeUpdate() {
      if (this.mService == null) {
         Log.w(TAG, "executeUpdate: mService is null - call bindUpdateService() first");
         return;
      }
      try {
         this.mService.executeUpdate();
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
      }
   }

   /**
    * @param mode {@link #MODE_DOWNLOAD} or {@link #MODE_EXECUTE_UPDATE}. Confirmed against
    *             this APK's own caller: it passes 1 to start a download and 2 to execute an
    *             update, matching these two constants exactly.
    */
   public void triggerUpdate(int mode) {
      if (this.mService == null) {
         Log.w(TAG, "triggerUpdate: mService is null - call bindUpdateService() first");
         return;
      }
      try {
         this.mService.triggerUpdate(mode);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceConnected(ComponentName name, IBinder binder) {
      this.mService = IUpdataBussiness.Stub.asInterface(binder);
      try {
         this.mService.registerClientListener(this.mClientListener);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceDisconnected(ComponentName name) {
      this.mService = null;
   }
}
