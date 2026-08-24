package com.ubtechinc.alpha2serverlib.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortService;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortOnRcvListener;

/**
 * Binds the robot's chest serial-port service and provides {@code sendCommand} plus a
 * receive callback for frames coming back from the chest microcontroller.
 */
public class Alpha2SerialServiceUtil implements ServiceConnection {
   private static final String TAG = "Alpha2SerialServiceUtil";
   private static final String ACTION = "com.ubtechinc.services.AlphaSerialPortServices";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";
   private static final int WAIT_TICKS = 300;
   private static final long WAIT_TICK_MS = 10L;

   private final Context mContext;
   private final Alpha2SerialPortOnRcvListener mClientListener;
   private final IAlpha2SerialPortRcvClient.Stub mRcvListener;
   private IAlpha2SerialPortService mService;
   private byte mSessionID;
   private boolean mBound;

   public Alpha2SerialServiceUtil(Context context, Alpha2SerialPortOnRcvListener listener) {
      this.mContext = context;
      this.mClientListener = listener;
      this.mRcvListener = new SerialPortRcvClientImpl();
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      // Remember whether the bind took so ReleaseConnection() can unbind safely.
      // Binding is asynchronous and intentionally NOT awaited here: the
      // ServiceConnection callback is delivered on the main thread, so blocking the
      // caller (typically the main thread) would deadlock the very bind we want. Callers
      // check readiness with isInitCompleted() and treat not-yet-ready as transient.
      this.mBound = this.mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
   }

   public boolean isInitCompleted() {
      return this.mService != null;
   }

   /**
    * Optionally block (up to ~3s) for the asynchronous bind to complete. Only useful from
    * a background thread: the {@link ServiceConnection} callback is delivered on the main
    * thread, so on the main thread this returns immediately rather than spinning (which
    * would block the very bind it is waiting for).
    */
   public void waitForInitComplete() {
      waitForInitComplete(WAIT_TICKS * WAIT_TICK_MS);
   }

   /**
    * 2026-08 新增: 見 Alpha2SerialHeaderServiceUtil.waitForInitComplete(long) 嘅
    * comment - 加返呢個 overload 等 timeoutMs 可以真正生效, 唔會再被無視。
    *
    * @param timeoutMs 最多等幾多 ms (負數當 0 處理)。
    */
   public void waitForInitComplete(long timeoutMs) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
         return;
      }
      long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
      while (!this.isInitCompleted() && SystemClock.elapsedRealtime() < deadline) {
         SystemClock.sleep(WAIT_TICK_MS);
      }
   }

   public boolean sendCommand(byte nCmd, byte[] nParam, int nLen) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.sendCommand(this.mSessionID, nCmd, nParam, nLen);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   /** Sends raw bytes over the chest serial link, bypassing sendCommand's frame
    *  encapsulation entirely (IAlpha2SerialPortService transaction #3). */
   public boolean sendRawData(byte[] data, int nLen) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.sendRawData(data, nLen);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   public boolean ReleaseConnection() {
      if (this.mService != null) {
         try {
            this.mService.unRegisterSerialPortRcvListener(this.mRcvListener);
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
      Log.v(TAG, "onServiceConnected");
      this.mService = IAlpha2SerialPortService.Stub.asInterface(service);
      try {
         this.mSessionID = (byte) this.mService.registerSerialPortRcvListener(this.mRcvListener);
      } catch (RemoteException e) {
         e.printStackTrace();
      }
   }

   @Override
   public void onServiceDisconnected(ComponentName name) {
      Log.v(TAG, "onServiceDisconnected");
      try {
         if (this.mService != null) {
            this.mService.unRegisterSerialPortRcvListener(this.mRcvListener);
         }
      } catch (RemoteException e) {
         // See AlphaActionServiceUtil.onServiceDisconnected() for why this is expected
         // (remote process already gone by the time this fires) rather than a real error.
         Log.v(TAG, "unRegisterSerialPortRcvListener failed during disconnect (expected if the "
                 + "remote service already died): " + e);
      } finally {
         this.mService = null;
      }
   }

   private final class SerialPortRcvClientImpl extends IAlpha2SerialPortRcvClient.Stub {
      @Override
      public void onListenSerialPortRcvData(byte[] bytes, int len) throws RemoteException {
         if (Alpha2SerialServiceUtil.this.mClientListener != null) {
            Alpha2SerialServiceUtil.this.mClientListener.onListenSerialPortRcvData(bytes, len);
         }
      }
   }
}
