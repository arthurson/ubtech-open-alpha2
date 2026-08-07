package com.ubtechinc.alpha2robot.constant;

/**
 * Result codes returned by {@link com.ubtechinc.alpha2robot.Alpha2RobotApi} methods.
 *
 * <ul>
 *   <li>{@code API_ERROR_SUCCEED} - the call was accepted / forwarded to the robot.</li>
 *   <li>{@code API_ERROR_NOT_INIT} - the relevant service was not initialised (call the
 *       matching {@code init*Api} first) or its binder is not yet connected.</li>
 *   <li>{@code API_ERROR_APPID_NOT_ACTIVE} / {@code API_ERROR_AUTHORIZE_ERROR} - legacy
 *       store-authorisation failures; this open SDK no longer gates on authorisation, so
 *       these are not returned in normal operation.</li>
 *   <li>{@code API_ERROR_FAILED} - the binder was connected and the call went through,
 *       but the underlying AIDL method itself returned false (e.g. the 5mic LED calls,
 *       which unlike sendCommand's fire-and-forget style report the native driver's
 *       actual result).</li>
 * </ul>
 */
public final class UbxErrorCode {
   private UbxErrorCode() {
   }

   public enum API_ERROR_CODE {
      API_ERROR_NOT_INIT,
      API_ERROR_SUCCEED,
      API_ERROR_APPID_NOT_ACTIVE,
      API_ERROR_AUTHORIZE_ERROR,
      API_ERROR_FAILED
   }
}
