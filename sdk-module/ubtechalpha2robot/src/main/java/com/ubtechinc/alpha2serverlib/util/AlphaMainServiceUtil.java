package com.ubtechinc.alpha2serverlib.util;

import android.content.Context;
import android.content.Intent;

/** Starts / stops the robot's top-level MainService and reports its version string. */
public class AlphaMainServiceUtil {
   private static final String ACTION = "com.ubtechinc.services.MainService";
   private static final String VERSION = "2.0.0.1";

   private final Context mContext;

   public AlphaMainServiceUtil(Context context) {
      this.mContext = context;
   }

   public static String getVersion() {
      return VERSION;
   }

   public void startService() {
      this.mContext.startService(new Intent(ACTION));
   }

   public void stopService() {
      this.mContext.stopService(new Intent(ACTION));
   }
}
