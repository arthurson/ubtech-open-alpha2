package com.ubtechinc.alpha2ctrlapp.network.action;

/**
 * Callback delivered once when an {@link com.ubtechinc.alpha2robot.Alpha2RobotApi} is
 * constructed. On this open SDK authorisation always succeeds:
 * {@code onResult(1, "have offline authority")}.
 */
public interface ClientAuthorizeListener {
   void onResult(int code, String info);
}
