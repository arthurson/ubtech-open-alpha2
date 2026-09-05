package com.open.alpha2;

import android.content.Context;
import android.content.Intent;

/**
 * 2026-09: 脫離 Alpha2OpenSdk —— 取代 sdk-module/ubtechalpha2robot 的
 * Alpha2RobotApi。機身根本無 com.ubtechinc.alpha2services，所有 binder
 * 調用註定失敗，所以呢個係一個永久嘅 no-op facade：每個方法都即時回一個
 * 誠實嘅失敗值（{@link UbxErrorCode.API_ERROR_CODE#API_ERROR_NOT_INIT}、
 * {@code false}、{@code null}），listener 永遠唔會被 callback，絕不 block、
 * 絕不 throw。行為同之前（util 全 null）一模一樣，純粹唔再需要成個 SDK module。
 *
 * <p>例外（唔經 binder、本來就 work，保留原語義）：
 * <ul>
 *   <li>{@link #requestRobotUUID()} - 照發 broadcast（無人收，純粹向後相容；
 *       真正讀值行 {@code queryChestRobotUuid()} direct 路）。</li>
 *   <li>{@link #speech_SetMIC(boolean)} - 照回 {@code true}（同 SDK 喺 util
 *       null 時一樣，乜都唔做）。</li>
 *   <li>{@link #releaseApi()} - no-op。</li>
 * </ul>
 *
 * <p>真正行硬件全部經 {@code HardwareDirectManager}（胸/頭串口直驅）、
 * {@code DirectLedController}（JNI）、Android 原生 API。
 */
public final class RobotStub {
    private final Context mContext;

    public RobotStub(Context context) {
        this.mContext = context.getApplicationContext();
    }

    /** Grammar-build callback (binder 時代遺留接口，listener 永遠唔會被叫）。 */
    public interface IAlpha2SpeechGrammarInitListener {
        void speechGrammarInitCallback(String grammarId, int errorCode);
    }

    /** Grammar-result callback （同上）。 */
    public interface IAlpha2SpeechGrammarListener {
        void onSpeechGrammarResult(int type, String result);
        void onSpeechGrammarError(int errorCode);
    }

    // 2026-09 移除: action_getActionList / action_PlayActionName /
    // action_StopAction (零調用；動作一律經 UbxPlayer 直驅，見 actionListDirect())。
    // -- Speech / TTS (全死：無 speech service) --------------------------------

    public UbxErrorCode.API_ERROR_CODE speech_startTTS(String language, String text, String strVoiceName) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_StopTTS() {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_setRecognizedLanguage(String strLanguage) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_initGrammar(String strGrammar,
            IAlpha2SpeechGrammarInitListener listener) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_startGrammar(IAlpha2SpeechGrammarListener listener) {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    public UbxErrorCode.API_ERROR_CODE speech_stopGrammar() {
        return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
    }

    // -- Mic (假成功：同 SDK 在 util null 時一樣回 true，乜都唔做) ---------------

    public boolean speech_SetMIC(boolean isWake) {
        return true;
    }

    // -- 非 binder：原語義保留 --------------------------------------------------

    public void requestRobotUUID() {
        mContext.sendBroadcast(new Intent("com.ubtechinc.robot_uuid.request"));
    }

    public void releaseApi() {
    }
}
