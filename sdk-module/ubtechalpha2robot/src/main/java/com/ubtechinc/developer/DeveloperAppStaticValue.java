package com.ubtechinc.developer;

/**
 * Broadcast actions and serial sub-command bytes used by the developer-app
 * config / button / robot-uuid features. String and byte values are the robot's wire
 * values and must not change.
 */
public final class DeveloperAppStaticValue {
   public static final byte HEADER_SEND_NOISE = 39;
   public static final byte HEADER_SEND_AMP = 25;
   public static final String APP_EXIT = "com.ubtechinc.closeapp";
   public static final String APP_CONFIG = "com.ubtechinc.config";
   public static final String APP_CONFIG_BACK = "com.ubtechinc.config.back";
   public static final String APP_CONFIG_SAVE = "com.ubtechinc.config.save";
   public static final String APP_BUTTON_EVENT = "com.ubtechinc.button.read";
   public static final String APP_BUTOON_EVENT_BACK = "com.ubtechinc.button.back";
   public static final String APP_BUTOON_EVENT_CLICK = "com.ubtechinc.button.click";
   public static final String APP_ROBOT_UUID_REQUEST = "com.ubtechinc.robot_uuid.request";
   public static final String APP_ROBOT_UUID_INFO = "com.ubtechinc.robot_uuid.info";

   private DeveloperAppStaticValue() {
   }
}
