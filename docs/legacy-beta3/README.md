# Legacy docs（只適合 beta3，beta4 起已無用）

呢個目錄收埋咗 beta3 時代嘅逆向文件，beta4（pure-direct）之後已經唔再適用，留低只供考古：

- `AIDL_REFERENCE_ALPHA2.md` —— 17 個 AIDL interface + transaction id。beta4 機身已無
  `alpha2services`，全部 binder 路徑一律 `NOT_INIT`；胸/頭硬件改行
  `sdk-module/hardware-direct`（`DirectChestController` / `DirectHeadController` /
  `RobotWire`）直驅，唔再經任何 AIDL。
- `iFlytek_Complete_Workflow_and_Offline_Architecture.md` /
  `iFlytek_完整工作流程與離線工作原理.md` —— 訊飛離線語音（`libmsc.so` / `Msc.jar` /
  `com.iflytek.cloud`）全套流程。beta4 已移除 MSC（`app/libs`、`libmsc.so`、
  `iflytektest/` 全部刪除），語音改用 `XiaozhiClient + Android TTS`，呢份文件
  講嘅離線引擎喺 beta4 上唔存在。

Code 入面仲有大量 `見 AIDL_REFERENCE …` 註解，全部指緊呢度（路徑已由 root 搬入本目錄）。
