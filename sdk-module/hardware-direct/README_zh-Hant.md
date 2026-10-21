# hardware-direct

> 語言：[English](README.md) | 繁體中文（本檔）

Android library 模組：連接應用程式與 Alpha2 胸板／頭板的硬件直驅層，
擁有串口、LED／頭鍵 JNI 橋接、線協議常數及 `.ubx` 動作檔解析／播放。
零項目依賴（`versionName "1.0-direct"`）。

## 裝置接線

- 胸板 MCU：`/dev/ttyS1`；頭板 MCU：`/dev/ttyS3`；波特率 115200。
- 幀格式（編解碼在 `SerialFrameCodec`，尾 `ED`）：長式
  `F8 8F LEN ID INDEX CMD PARAM SUM ED`（校驗和計 LEN＋ID＋INDEX＋CMD＋PARAM）；
  舊短式 `F8 8F LEN CMD PARAM SUM ED` 照解（fallback）。
- 頭頂＋／－鍵：`/dev/input/event0`，經 `HeadKeyPoller` 消費。
- 頭／眼／嘴 LED：經 `LedControl` 調用 `libhead_led.so`。
- 裝置節點全域可讀寫，普通 user app 即可直開（無需 system app 或平台簽名）。

## 內容

串口（`com.ubtechinc.alpha.hardware`）：

- `DirectSerialConfig`——裝置路徑對照（胸／頭）及波特率。
- `DirectSerialPort`——經 `SerialPortFile` JNI（`libserial_port.so`）開串口，
  失敗回退純檔案 I／O。
- `SerialFrameCodec`——幀編解碼及校驗和。
- `RobotWire`／`DeveloperPacketData`——線協議常數及封包構造。
- `DirectChestController`——單顆／全組舵機、聲納、PIR、韌體升級。
- `DirectHeadController`——頭板指令（如降噪）。
- `DirectLedController`／`MouthLedData`——LED 驅動及嘴燈幀數據。
- JNI 墊片：`jni/SerialPortFile`、`jni/LedControl`、
  `jni/headkey/HeadKeyMgr`。
- `HeadKeyPoller`——頭鍵按下／長按／放手事件。
- `ServoPoseTracker`——追蹤 22 軸指令位姿（收舊 20 軸或全 22 軸幀都得；
  20 軸只更新前 20 軸）。
- `HardwareDirectManager`／`LocalAlpha2Services`——單一入口，App 在後台
  線程啟動，並回報直驅是否生效。

`.ubx` 播放（`...hardware/ubx`）：

- `UbxFile`／`UbxParser`——動作檔解析。
- `UbxPlayer`——無漂移節拍播放，支援 0.5–2.0 倍速（舵機同 mp3 配樂速度一齊跟住變）；
  `stop()` 停喺末位姿唔郁（App 層截停會再播回位動作）。
- `VoiceStream`——動作配樂，與同一時鐘鎖定。

Native libs（`src/main/jniLibs/armeabi-v7a`）：`libhead_led.so`、
`libhead_key_mgr.so`、`libserial_port.so`。

## 接入

`settings.gradle` 已包含本模組；`app/build.gradle` 以
`implementation project(':sdk-module:hardware-direct')` 依賴。App 在後台
線程啟動一次即可：

```java
LocalAlpha2Services local = new LocalAlpha2Services(this);
boolean direct = local.start();
```

以普通 user app 安裝，然後檢查裝置節點：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ls -l /dev/ttyS1 /dev/ttyS3   # expect crwxrwxrwx (777)
```
