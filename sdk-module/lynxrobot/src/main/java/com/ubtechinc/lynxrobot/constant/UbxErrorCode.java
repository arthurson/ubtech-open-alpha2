package com.ubtechinc.lynxrobot.constant;

/**
 * Result codes returned by {@link com.ubtechinc.lynxrobot.LynxRobotApi} methods.
 *
 * <ul>
 *   <li>{@code API_ERROR_SUCCEED} - the call was accepted / forwarded to the robot.
 *       The actual result of the operation still arrives asynchronously via the
 *       listener you passed in.</li>
 *   <li>{@code API_ERROR_NOT_INIT} - the matching AIDL service's binder isn't
 *       available yet (robot's system app not running, or
 *       {@code alpha2.service.BinderProvider}/{@code IServiceFetcher.getService()}
 *       hasn't resolved it). {@link com.ubtechinc.lynxrobot.LynxRobotApi} retries
 *       fetching the binder automatically on the next call.</li>
 *   <li>{@code API_ERROR_APPID_NOT_ACTIVE} / {@code API_ERROR_AUTHORIZE_ERROR} - kept
 *       for source compatibility with older callers; this open SDK doesn't gate on
 *       app-id authorisation (the underlying AIDL surface has none), so these are not
 *       returned in normal operation.</li>
 *   <li>{@code API_ERROR_FAILED} - the binder was connected but the AIDL call itself
 *       threw a {@link android.os.RemoteException} (e.g. the robot's service process
 *       died mid-call).</li>
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
