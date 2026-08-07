package com.ubtechinc.alpha2robot.constant;

/**
 * Broadcast actions / extras used by the robot for app lifecycle and head-key events.
 *
 * <p>The string values are the robot's wire values and must not change. In particular
 * the head-key broadcast delivers its key id as a {@code Byte} extra named
 * {@link #UBTEK_KEY_VALUE} - read it with {@code getByteExtra}, not {@code getIntExtra}.
 * (The type was spelled {@code AlphaContant} in the original SDK; corrected here.)
 */
public final class AlphaConstant {
   public static final String UBTEK_APP_EXIT = "com.ubtechinc.closeapp";
   public static final String UBTEK_KEY_BROADCAST = "com.ubtechinc.key";
   public static final String UBTEK_KEY_VALUE = "key";

   private AlphaConstant() {
   }
}
