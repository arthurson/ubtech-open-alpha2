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
import com.ubtechinc.alpha2serverlib.interfaces.Alpha2SerialPortHeaderOnRcvListener;

/**
 * Binds the robot's head serial-port service and provides {@code sendCommand} plus a
 * receive callback for frames coming back from the head microcontroller.
 */
public class Alpha2SerialHeaderServiceUtil implements ServiceConnection {
   private static final String TAG = "Alpha2SerialHeaderServiceUtil";
   private static final String ACTION = "com.ubtechinc.services.AlphaSerialPortHeaderServices";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";
   private static final int WAIT_TICKS = 300;
   private static final long WAIT_TICK_MS = 10L;

   private final Context mContext;
   private final Alpha2SerialPortHeaderOnRcvListener mClientListener;
   private final IAlpha2SerialPortRcvClient.Stub mRcvListener;
   private IAlpha2SerialPortService mService;
   private byte mSessionID;
   private boolean mBound;

   public Alpha2SerialHeaderServiceUtil(Context context, Alpha2SerialPortHeaderOnRcvListener listener) {
      this.mContext = context;
      this.mClientListener = listener;
      this.mRcvListener = new SerialPortRcvClientImpl();
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      // Binding is asynchronous and intentionally NOT awaited here (see
      // Alpha2SerialServiceUtil): blocking the main-thread caller would deadlock the
      // ServiceConnection callback. Callers check readiness with isInitCompleted().
      this.mBound = this.mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
   }

   public boolean isInitCompleted() {
      return this.mService != null;
   }

   /**
    * Optionally block (up to ~3s) for the async bind to complete. Returns immediately on
    * the main thread (the {@link ServiceConnection} callback is delivered there, so
    * spinning would block the bind it waits for).
    */
   public void waitForInitComplete() {
      waitForInitComplete(WAIT_TICKS * WAIT_TICK_MS);
   }

   /**
    * 2026-08 新增: 加返呢個 overload 等 caller 可以指定實際想等幾耐, 而唔係永遠
    * 硬食 ~3s。之前 Alpha2RobotApi.waitHeaderReady(long timeoutMs) 個 timeoutMs
    * 參數其實冇畀落嚟呢層, 靜靜哋被無視, 令傳入嘅值完全冇作用。
    *
    * @param timeoutMs 最多等幾多 ms (負數當 0 處理)。喺主 thread 呼叫照舊即刻
    *                  return (見上面 no-arg 版本嘅原因)。
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

   /** Sends raw bytes over the head serial link, bypassing sendCommand's frame
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

   // -- 5-mic hardware LED path -----------------------------------------------------
   // These bypass sendCommand's serial-frame protocol and call straight into the
   // robot's com.ubtechinc.mic5.LedControl native driver. Needed because on 5mic
   // hardware (confirmed on v1.1.7.3), sendCommand's traditional LED_EAR/LED_EYE
   // command bytes reach the head serial port fine (no RemoteException, bindReady
   // stays true) but the 5mic head MCU no longer acts on them - only these dedicated
   // AIDL methods do.

   public boolean stop5MicEyeLED() {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.stop5MicEyeLED();
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   public boolean stop5MicEarLED() {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.stop5MicEarLED();
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   /** Parameter meaning is not fully confirmed from static analysis alone - see the
    *  "5Mic LED Test" tab, which exposes all 8 raw ints for on-robot experimentation. */
   public boolean ledSetEye5Mic(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.ledSetEye5Mic(p1, p2, p3, p4, p5, p6, p7, p8);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   /** See ledSetEye5Mic() note on parameter meaning. */
   public boolean ledSetHead5Mic(int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
      if (this.mService == null) {
         return false;
      }
      try {
         return this.mService.ledSetHead5Mic(p1, p2, p3, p4, p5, p6, p7, p8);
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return false;
      }
   }

   public String getRobotSerialNumber() {
      if (this.mService == null) {
         return null;
      }
      try {
         return this.mService.getRobotSerialNumber();
      } catch (RemoteException | RuntimeException e) {
         e.printStackTrace();
         return null;
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
         if (Alpha2SerialHeaderServiceUtil.this.mClientListener != null) {
            Alpha2SerialHeaderServiceUtil.this.mClientListener.onListenSerialPortHeaderRcvData(bytes, len);
         }
      }
   }
}
