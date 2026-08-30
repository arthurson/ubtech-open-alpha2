package com.open.alpha2;

/**
 * Immutable snapshot of where the XiaoZhi (小智) OTA/device-activation flow currently
 * is - see XiaozhiOtaClient's class javadoc for what this flow actually does and why.
 * MainActivity's background activation thread publishes a new snapshot at each stage
 * transition; the browser polls "xiaozhi/activation_status" to reflect it (there's no
 * push channel for this - EventBus/WebSocket is deliberately not used here since the
 * activation flow happens *before* any XiaoZhi WebSocket session exists to push
 * through, and reusing the general-purpose robot-event WebSocket for this would tangle
 * an unrelated concern into it).
 */
public final class XiaozhiActivationStatus {
    public enum Stage {
        IDLE,             // no activation attempt in progress or completed yet
        CHECKING,         // checkVersion() HTTP call in flight
        AWAITING_CODE,    // device has spoken/displayed the code, waiting for the
                           // person to enter it on xiaozhi.me
        POLLING,          // (same as AWAITING_CODE from the person's perspective -
                           // kept as a distinct stage only for log/debug clarity, both
                           // render identically in the UI)
        CONNECTING,        // activation confirmed, now opening the XiaozhiClient WebSocket
        CONNECTED,         // fully connected and ready for chat
        ERROR
    }

    public final Stage stage;
    public final String activationCode;   // set during AWAITING_CODE/POLLING
    public final String activationMessage; // server-provided human prompt, if any
    public final String errorMessage;      // set during ERROR
    public final String sessionId;         // set during CONNECTED

    private XiaozhiActivationStatus(Stage stage, String activationCode, String activationMessage,
                                     String errorMessage, String sessionId) {
        this.stage = stage;
        this.activationCode = activationCode;
        this.activationMessage = activationMessage;
        this.errorMessage = errorMessage;
        this.sessionId = sessionId;
    }

    public static XiaozhiActivationStatus idle() {
        return new XiaozhiActivationStatus(Stage.IDLE, null, null, null, null);
    }

    public static XiaozhiActivationStatus checking() {
        return new XiaozhiActivationStatus(Stage.CHECKING, null, null, null, null);
    }

    public static XiaozhiActivationStatus awaitingCode(String code, String message) {
        return new XiaozhiActivationStatus(Stage.AWAITING_CODE, code, message, null, null);
    }

    public static XiaozhiActivationStatus polling(String code, String message) {
        return new XiaozhiActivationStatus(Stage.POLLING, code, message, null, null);
    }

    public static XiaozhiActivationStatus connecting() {
        return new XiaozhiActivationStatus(Stage.CONNECTING, null, null, null, null);
    }

    public static XiaozhiActivationStatus connected(String sessionId) {
        return new XiaozhiActivationStatus(Stage.CONNECTED, null, null, null, sessionId);
    }

    public static XiaozhiActivationStatus error(String message) {
        return new XiaozhiActivationStatus(Stage.ERROR, null, null, message, null);
    }

    /** Lowercase wire-format name for the JSON "stage" field the browser reads. */
    public String stageJson() {
        return stage.name().toLowerCase(java.util.Locale.US);
    }
}
