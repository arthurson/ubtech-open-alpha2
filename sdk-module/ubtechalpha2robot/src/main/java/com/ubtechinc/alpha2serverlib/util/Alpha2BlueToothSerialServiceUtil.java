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
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2BlueToothSerialPortService;
import com.ubtechinc.alpha2serverlib.aidlinterface.IAlpha2SerialPortRcvClient;
import com.ubtechinc.alpha2serverlib.constvalue.Alpha2Intent;
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2BlueToothSerialPortOnRcvListener;

/**
 * Binds the robot's Bluetooth serial-port service (IAlpha2BlueToothSerialPortService) -
 * a Bluetooth-backed counterpart to Alpha2SerialServiceUtil's chest serial link.
 * Mirrors Alpha2SerialServiceUtil's structure, but uses its own
 * {@link Alpha2BlueToothSerialPortOnRcvListener} (rather than reusing
 * Alpha2SerialPortOnRcvListener) so a caller implementing both chest and Bluetooth
 * serial can tell the two receive paths apart instead of both landing on the same
 * override.
 *
 * This service and its AIDL interface did not exist anywhere in this SDK before - see
 * IAlpha2BlueToothSerialPortService.aidl's header comment for the decompilation source.
 * Only 4 methods, no 5-mic LED transaction ids (unlike the chest/head serial service).
 */
public class Alpha2BlueToothSerialServiceUtil implements ServiceConnection {
   private static final String TAG = "Alpha2BlueToothSerialServiceUtil";
   private static final String ACTION = Alpha2Intent.ALPHA_BLUETOOTHSERIAL_SERVER;
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";
   private static final int WAIT_TICKS = 300;
   private static final long WAIT_TICK_MS = 10L;

   private final Context mContext;
   private final Alpha2BlueToothSerialPortOnRcvListener mClientListener;
   private final IAlpha2SerialPortRcvClient.Stub mRcvListener;
   private IAlpha2BlueToothSerialPortService mService;
   private byte mSessionID;
   private boolean mBound;

   public Alpha2BlueToothSerialServiceUtil(Context context, Alpha2BlueToothSerialPortOnRcvListener listener) {
      this.mContext = context;
      this.mClientListener = listener;
      this.mRcvListener = new SerialPortRcvClientImpl();
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      // See Alpha2SerialServiceUtil's constructor javadoc: binding is asynchronous and
      // intentionally not awaited here to avoid deadlocking the main thread against its
      // own ServiceConnection callback. Callers poll isInitCompleted() or use
      // waitForInitComplete() from a background thread.
      this.mBound = this.mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
   }

   public boolean isInitCompleted() {
      return this.mService != null;
   }

   /** See Alpha2SerialServiceUtil.waitForInitComplete() - same main-thread guard. */
   public void waitForInitComplete() {
      waitForInitComplete(WAIT_TICKS * WAIT_TICK_MS);
   }

   /** See Alpha2SerialHeaderServiceUtil.waitForInitComplete(long) - lets timeoutMs
    *  actually take effect instead of always being silently ignored. */
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

   /** Sends an AT command string over the Bluetooth serial link (transaction #3). */
   public void sendATCMD(String cmd) {
      if (this.mService == null) {
         return;
      }
      try {
         this.mService.sendATCMD(cmd);
      } catch (RemoteException e) {
         e.printStackTrace();
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
      this.mService = IAlpha2BlueToothSerialPortService.Stub.asInterface(service);
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
         if (Alpha2BlueToothSerialServiceUtil.this.mClientListener != null) {
            Alpha2BlueToothSerialServiceUtil.this.mClientListener.onListenBlueToothSerialPortRcvData(bytes, len);
         }
      }
   }
}
