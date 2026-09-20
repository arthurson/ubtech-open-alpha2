package com.open.alpha2;

/**
 * 單一下載通道閘（實驗 tab「資源下載」卡用）。
 *
 * <p>Vosk 模型同動作包共用同一條下載通道，每次只准一樣下載緊——後端在這裡
 * 強制互斥（搶唔到閘直接回錯誤，前端按鈕聯動只係裝飾，唔靠佢擋）。
 * 同一 owner 重入攞閘照批（各自 controller 內部「already downloading」
 * 先擋咗，唔會行到呢度）；完成／失敗／取消嗰陣各自 release。
 * 全部 static synchronized，邊條 thread call 都得。
 */
public final class DownloadGate {
    private DownloadGate() {}

    /** 邊種下載揸住條通道："vosk"／"actions"，null＝閒置。 */
    private static String owner = null;
    /** 當前下載明細（vosk 就係 model id，actions 就係 actions.zip），錯誤訊息用。 */
    private static String detail = null;

    /** 攞閘：閒置／自己揸住就批，別人揸住就 false。 */
    public static synchronized boolean tryAcquire(String o, String d) {
        if (owner != null && !owner.equals(o)) return false;
        owner = o;
        detail = d;
        return true;
    }

    /** 放閘：只放得自己揸住嗰個，唔係自己就 no-op（唔好放錯人）。 */
    public static synchronized void release(String o) {
        if (o != null && o.equals(owner)) {
            owner = null;
            detail = null;
        }
    }

    /** 當前狀態短描述（"idle"／"vosk: xxx"／"actions: actions.zip"，錯誤訊息用）。 */
    public static synchronized String describe() {
        if (owner == null) return "idle";
        return detail != null ? owner + ": " + detail : owner;
    }
}
