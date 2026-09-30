# hardware-direct

Android library module: the direct hardware layer between the app and the
Alpha2 chest/head boards. It owns the serial ports, the LED / head-key JNI
bridges, the wire-protocol constants and the `.ubx` action-file
parser/player. Zero project dependencies (`versionName "1.0-direct"`).

Android library 模組：連接應用程式與 Alpha2 胸板／頭板的硬件直驅層，
擁有串口、LED／頭鍵 JNI 橋接、線協議常數及 `.ubx` 動作檔解析／播放。
零項目依賴（`versionName "1.0-direct"`）。

## Device wiring / 裝置接線

- Chest MCU: `/dev/ttyS1`; head MCU: `/dev/ttyS3`; 115200 baud.
- Frame: `F8 8F LEN CMD PARAM SUM ED` (encode/decode in `SerialFrameCodec`).
- Head +/- pads: `/dev/input/event0`, consumed via `HeadKeyPoller`.
- Head/eye/mouth LEDs: `libhead_led.so` via `LedControl`.
- The device nodes are world-accessible, so a normal user app (no system
  app, no platform signature) can open them.

- 胸板 MCU：`/dev/ttyS1`；頭板 MCU：`/dev/ttyS3`；波特率 115200。
- 幀格式：`F8 8F LEN CMD PARAM SUM ED`（編解碼在 `SerialFrameCodec`）。
- 頭頂＋／－鍵：`/dev/input/event0`，經 `HeadKeyPoller` 消費。
- 頭／眼／嘴 LED：經 `LedControl` 調用 `libhead_led.so`。
- 裝置節點全域可讀寫，普通 user app 即可直開（無需 system app 或平台簽名）。

## Contents / 內容

Serial (`com.ubtechinc.alpha.hardware`):

- `DirectSerialConfig` — device-path map (chest/head) and baud rate.
- `DirectSerialPort` — opens the port via `SerialPortFile` JNI
  (`libserial_port.so`), falls back to plain file I/O.
- `SerialFrameCodec` — frame encode/decode and checksum.
- `RobotWire` / `DeveloperPacketData` — wire-protocol constants and packet
  builders.
- `DirectChestController` — single/all servos, sonar, PIR, firmware upgrade.
- `DirectHeadController` — head-board commands (e.g. noise reduction).
- `DirectLedController` / `MouthLedData` — LED drivers and mouth-LED frames.
- JNI shims: `jni/SerialPortFile`, `jni/LedControl`, `jni/headkey/HeadKeyMgr`.
- `HeadKeyPoller` — head-pad press/hold/release events.
- `ServoPoseTracker` — tracks the 20-joint command pose.
- `HardwareDirectManager` / `LocalAlpha2Services` — single entry point the
  app starts on a background thread; reports whether direct mode is active.

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
- `ServoPoseTracker`——追蹤 20 軸指令位姿。
- `HardwareDirectManager`／`LocalAlpha2Services`——單一入口，App 在後台
  線程啟動，並回報直驅是否生效。

`.ubx` playback (`...hardware/ubx`):

- `UbxFile` / `UbxParser` — action-file parse.
- `UbxPlayer` — drift-free tick playback with 0.5/0.67/1/1.5/2x speed shift
  and stop-with-recovery-pose.
- `VoiceStream` — per-action background music, locked to the same clock.

`.ubx` 播放（`...hardware/ubx`）：

- `UbxFile`／`UbxParser`——動作檔解析。
- `UbxPlayer`——無漂移節拍播放，支援 0.5／0.67／1／1.5／2 倍速及
  截停回位。
- `VoiceStream`——動作配樂，與同一時鐘鎖定。

Native libs (`src/main/jniLibs/armeabi-v7a`): `libhead_led.so`,
`libhead_key_mgr.so`, `libserial_port.so`.

## Integration / 接入

`settings.gradle` already includes the module; `app/build.gradle` depends on
it with `implementation project(':sdk-module:hardware-direct')`. The app
starts it once on a background thread:

`settings.gradle` 已包含本模組；`app/build.gradle` 以
`implementation project(':sdk-module:hardware-direct')` 依賴。App 在後台
線程啟動一次即可：

```java
LocalAlpha2Services local = new LocalAlpha2Services(this);
boolean direct = local.start();
```

Install as a normal user app, then sanity-check the nodes:

以普通 user app 安裝，然後檢查裝置節點：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ls -l /dev/ttyS1 /dev/ttyS3   # expect crwxrwxrwx (777)
```
