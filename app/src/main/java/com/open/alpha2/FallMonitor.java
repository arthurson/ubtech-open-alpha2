package com.open.alpha2;

/**
 * 跌倒看門狗嘅最小介面（純 Java，只用 java.*）：行路跟（{@link WalkFollower}）用佢知機身
 * 有冇跌低、同埋行路期間確保看門狗開住——跌倒之後點起身由 {@link FallRecoveryCenter}
 * （BackStand／FrontStand 內建起身動作）負責，行路跟唔自己播起身動作。
 */
public interface FallMonitor {
    /**
     * @return {@link ColorTrackPlanner#FALL_OK}（看門狗開住、已武裝、加速度計有數據）／
     *         {@link ColorTrackPlanner#FALL_NOT_READY}（未開、未武裝、冇加速度計數據——唔識偵測跌倒）／
     *         {@link ColorTrackPlanner#FALL_DOWN}（跌低、起身中、起身失敗）
     */
    int fallLevel();

    /** 畀人睇嘅狀態：off／disarmed／armed／noAccel（已武裝但加速度計暫時冇數據）／fallen／recovering／failed。 */
    String fallState();

    /**
     * 行路跟期間確保看門狗開住：可重入計數，**唔寫入偏好設定**（全部 release 後還原到原本開關）。
     * @return null＝成功；否則錯誤文字（例如冇加速度計）
     */
    String acquire();

    /** 對應 {@link #acquire()}；最後一個 release 後，如果看門狗只係因為行路跟先開，就關返。 */
    void release();
}
