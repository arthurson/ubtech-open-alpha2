package com.ubtechinc.alpha2serverlib.util;

import android.content.Context;
import android.content.Intent;

/** Starts / stops the robot's top-level MainService and reports its version string. */
public class AlphaMainServiceUtil {
   private static final String ACTION = "com.ubtechinc.services.MainService";
   private static final String SERVICE_PACKAGE = "com.ubtechinc.alpha2services";
   private static final String VERSION = "2.0.0.1";

   private final Context mContext;

   public AlphaMainServiceUtil(Context context) {
      this.mContext = context;
   }

   public static String getVersion() {
      return VERSION;
   }

   // 2026-08 修正: startService()/stopService() 之前用的是 implicit Intent (只有
   // Intent(ACTION), 沒有 setPackage()) - targetSdkVersion 22 底下, Android 5.0+
   // (API 21+) 對 startService() 已經會拋 IllegalArgumentException("Service Intent
   // must be explicit"), 和這個 project 其他六個 *ServiceUtil class (全部
   // bindService() 都有 setPackage(SERVICE_PACKAGE)) 應該一致, 但這個 class 因為
   // 用的是 startService()/stopService() 不是 bindService(), 之前漏掉沒加。
   public void startService() {
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      this.mContext.startService(intent);
   }

   public void stopService() {
      Intent intent = new Intent(ACTION);
      intent.setPackage(SERVICE_PACKAGE);
      this.mContext.stopService(intent);
   }
}
