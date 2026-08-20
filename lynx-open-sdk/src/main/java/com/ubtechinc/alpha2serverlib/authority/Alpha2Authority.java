package com.ubtechinc.alpha2serverlib.authority;

import android.content.Context;

/**
 * Static holder for the application context and app id captured at construction time.
 * Retained for API compatibility with the original SDK.
 */
public final class Alpha2Authority {
   public static Context mContext;
   public static String mAppid;

   private Alpha2Authority() {
   }

   public static void createAuthority(Context context, String appid) {
      mContext = context;
      mAppid = appid;
   }
}
