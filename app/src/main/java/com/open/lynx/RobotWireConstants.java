package com.open.lynx;

/**
 * 機身底層 broadcast action string / extra key 常量表 - 呢啲值屬於機身韌體本身嘅
 * wire protocol (broadcast/serial 層面), 唔經任何 AIDL SDK, 所以 Alpha2 AIDL SDK
 * (Alpha2RobotApi 及成個 ubtechalpha2robot module) 移除之後依然要保留。
 *
 * 原本呢張表存喺 ubtechalpha2robot module 嘅
 * com.ubtechinc.constant.StaticValue (171 行, 涵蓋大量 Alpha2 AIDL SDK 專用嘅
 * action/extra), 而家淨係抽返 RobotEventReceiver/MainActivity 實際用到、同
 * AIDL SDK 完全無關嘅幾個 wire-level 常量。每個值都係機身實際發送嘅
 * action/extra 名, 一定要保持 byte-identical, 唔可以「修正」。
 */
final class RobotWireConstants {
    private RobotWireConstants() {
    }

    /** 心口 (chest) MCU 全域 broadcast - 心口 mute 鍵 (-111)、PIR raw 觸發 (-109)
     *  都經呢條 action 送出, 見 RobotEventReceiver 個 CHEST_ACTION case 嘅 comment。
     *  (Lynx 冇心口超聲波感應硬件, 呢條 action 之前一度被誤以為同時帶住 sonar
     *  讀數, 已經證實唔係 - 相關 code 喺 2026-08 死 code 清理移除。) */
    static final String CHEST_ACTION = "com.ubtechinc.services.chest";

    /** QR code 掃描結果 broadcast。 */
    static final String ALPHA_QR_CODE = "com.ubt.alpha2.qr_code";

    /** Wi-Fi 連線結果 broadcast。 */
    static final String ALPHA_WIFI_RESULT = "com.ubt.alpha2.wifiresult";

    /** 藍牙連線狀態 broadcast。 */
    static final String ALPHA_BT_CONNECTION = "com.ubtechinc.services.bluetooth";
}
