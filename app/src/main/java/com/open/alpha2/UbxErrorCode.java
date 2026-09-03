package com.open.alpha2;

/**
 * 2026-09: 由 sdk-module/ubtechalpha2robot 搬入（脫離 Alpha2OpenSdk 的一部分）。
 * Result codes for robot-hardware calls. 語義不變：
 *
 * <ul>
 *   <li>{@code API_ERROR_SUCCEED} - the call was accepted / sent to the hardware.</li>
 *   <li>{@code API_ERROR_NOT_INIT} - 機身已無 alpha2services，所有舊 binder 路徑
 *       一律回這個（見 {@link RobotStub}）。</li>
 *   <li>{@code API_ERROR_APPID_NOT_ACTIVE} / {@code API_ERROR_AUTHORIZE_ERROR} -
 *       legacy store-authorisation failures, never returned in normal operation.</li>
 *   <li>{@code API_ERROR_FAILED} - direct serial send failed (port unavailable /
 *       write error), 見 MainActivity.directCode().</li>
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
