package com.ubtechinc.constant;

/**
 * Hardware self-test categories used by the robot's diagnostics broadcasts.
 * Values are the codes used by the robot and must not change.
 */
public final class HardwareTestValue {
   public static final int SOUND = 1;
   public static final int FALL = 2;
   public static final int TOUCH_BOARD = 3;
   public static final int PRESSURE_SENSOR = 4;
   public static final int SONAR = 5;

   private HardwareTestValue() {
   }
}
