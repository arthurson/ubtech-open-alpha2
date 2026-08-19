package com.ubtechinc.developer;

/**
 * Clamps servo target angles to each joint's safe travel range before they are sent to
 * the chest board, protecting the servos from being driven past their mechanical limits.
 *
 * <p>The per-joint {min,max} limits are properties of the Alpha2 hardware and must be
 * preserved. They must also stay in sync with the web UI's own copy of the same table
 * (app-core.js's SERVO_CALIBRATION) - see the 2026-08 note on joint 10 below for why this
 * matters even though the web UI is normally the first (and stricter) line of defence.
 * The sentinel value {@code 250} means "hold / do not move this joint" and is passed
 * through unchanged. Joint 19 has an upper limit only (no lower clamp).
 */
public class DeveloperAngle {
   /** Value meaning "do not move this joint". */
   private static final int HOLD = 250;
   private static final int NO_MIN = Integer.MIN_VALUE;

   /** {min, max} per joint index 0..19. */
   private static final int[][] LIMITS = {
      {5, 235},   // 0
      {50, 210},  // 1
      {55, 185},  // 2
      {5, 235},   // 3
      {30, 190},  // 4
      {55, 185},  // 5
      {100, 200}, // 6
      {20, 220},  // 7
      {35, 230},  // 8
      {35, 215},  // 9
      // 2026-08 修正: joint 10 (servo id 11) min 由 10 改做 100, 同前端
      // app-core.js 嘅 SERVO_CALIBRATION[11] 對齊 - 前端嗰個 100 先係啱嘅實測
      // 硬件校準值 (10 係呢度舊有、過時嘅數字)。呢個差異之前唔會造成安全問題
      // (前端已經用緊更保守嘅 100 做下限, clamp 完嘅結果一定都仲喺呢度舊嘅
      // [10,190] 範圍之內), 但 Advanced tab 嘅 raw AIDL passthrough 淨係靠
      // 呢個底層表做防線、唔經前端 clamp, 所以兩份數字理應同步。
      {100, 190}, // 10
      {40, 140},  // 11
      {20, 220},  // 12
      {10, 205},  // 13
      {25, 205},  // 14
      {50, 140},  // 15
      {95, 125},  // 16
      {95, 125},  // 17
      {75, 165},  // 18
      {NO_MIN, 155} // 19 - upper limit only
   };

   private static int clamp(int value, int min, int max) {
      if (value != HOLD) {
         if (min != NO_MIN && value < min) {
            return min;
         }
         if (value > max) {
            return max;
         }
      }
      return value;
   }

   /**
    * Clamp a full 20-joint angle array in place. A null array or one whose length is not
    * exactly 20 is left untouched.
    */
   public void checkData(int[] data) {
      if (data == null || data.length != 20) {
         return;
      }
      for (int i = 0; i < data.length; ++i) {
         data[i] = clamp(data[i], LIMITS[i][0], LIMITS[i][1]);
      }
   }

   /**
    * Clamp a single joint's angle. {@code id} is the 1-based servo number (1..20), matching
    * every caller in this codebase (chest_SendOneFreeAngle, the web UI's SERVO_CALIBRATION,
    * etc). LIMITS is 0-based, so we must index with (id - 1). Ids outside 1..20 are returned
    * unchanged (the chest board rejects them anyway).
    */
   public int checkAngle(byte id, int angle) {
      int index = id - 1;
      if (index >= 0 && index < LIMITS.length) {
         return clamp(angle, LIMITS[index][0], LIMITS[index][1]);
      }
      return angle;
   }
}
