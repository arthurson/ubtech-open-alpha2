# hardware-direct — 1.1.7.3 這一代硬件的最小直驅層

本模塊是從 `com.ubtechinc.alpha2services_base.3.002.apk` / `2.004` 拆解後，**只保留 1.1.7.3 這一代真正需要的硬件直驅代碼**的可移植子集。

## 為什麼不是整包搬運

* `3.002` 整包 9991 類，`sharedUserId=android.uid.system`，硬編碼 `/dev/ttySAC0/1` (Exynos)，而 RK3288 實機胸板是 `/dev/ttyS1`、頭板是 `/dev/ttyS3`（`ttyS0` 係藍牙），直接搬會 `open failed`。
* `1.1.7.3.20` 的 17 個 AIDL + 命令表已成歷史 (`AIDL_REFERENCE_ALPHA2.md` 留做逆向參考)；
  2026-09 起直驅要用的 wire 常數收喺本 module 嘅 `RobotWire` /
  `DeveloperPacketData`，唔再依賴任何 SDK module。
* 真正需要的是 `libserial_port.so + SerialPortFile` 的 `open("/dev/ttyS*")` 能力，這部分只有 2 個 Java + 1 個 so。

## 本模塊包含

* `DirectSerialConfig.java` — 設備路徑映射（胸 `/dev/ttyS1` 主、頭 `/dev/ttyS3` 主，`/dev/ttySAC*`/`ttyS0` 兼容）和波特率
* `SerialFrameCodec.java` — `F8 8F len cmd param checksum ED` 編解碼 (來源 `AIDL_REFERENCE` 第5章)
* `DirectSerialPort.java` — 優先走 `libserial_port.so` 的 `SerialPortFile` JNI，失敗回退到純 `File`，自動處理 `ttySAC` vs `ttyS` 雙路徑；普通 user app 即可（實機 `/dev/ttyS1`、`/dev/ttyS3` 係 777 且 SELinux 關閉，已驗證唔使 system app/平台簽名）
* `DirectChestController.java` — 單舵機/全舵機/聲納/PIR/升級 (複用 `DeveloperPacketData`)
* `DirectHeadController.java` — 降噪等頭 MCU 命令
* `DirectLedController.java` — 5-mic LED 的 `libhead_led.so` JNI 直驅 (已在 `MouthLedData` 驗證)
* `HardwareDirectManager.java` / `LocalAlpha2Services.java` — 對上層 `MainActivity` 的無感切換入口

## 集成步驟 (POC)

1. `settings.gradle` 已加入 `include ':sdk-module:hardware-direct'`
2. `app/build.gradle` 加 `implementation project(':sdk-module:hardware-direct')`
3. `MainActivity.onCreate` 後臺線程：
   ```java
   LocalAlpha2Services local = new LocalAlpha2Services(this);
   boolean direct = local.start(); // 内部 tryEnableDirect()
   // 后续所有 chest/head 调用先判 direct.isDirectActive()
   ```
4. 普通 user app 安裝即可（唔使 system app/平臺簽名）：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell dumpsys package com.open.alpha2 | grep codePath # /data/app/…即係 user app
   adb shell ls -l /dev/ttyS1 /dev/ttyS3 # 應為 crwxrwxrwx（777），唔係先至要處理權限
   ```

## 關於 so 的選擇

* `jniLibs/armeabi-v7a/libhead_led.so` 用的是 `3.002` 版 (17824b，`com.ubtechinc.alpha.jni.LedControl`
  接口；ioctl 號與 1.1.7.3 版逐個相同，反匯編核對過)，`libhead_key_mgr.so` 同樣來自 `3.002`
  （`com.ubtechinc.alpha.jni.headkey.HeadKeyMgr`，經本模塊 `HeadKeyPoller` 消費；
  頭頂 pad 輪詢與嘴燈 `MouthLedData` 亦已由 app 遷入本模塊，經 Listener/直接調用對接）
* `libserial_port.so` 來自 `3.002` 僅作佔位，1.1.7.3 真機上建議直接 `adb pull /system/lib/libserial_port.so` 替換，確保是 `ttyS0` 版本

## 不搬的部分

語音 `libcae.so`、騰訊 `QAL/XG` 推送、`Cm*` protobuf 全不搬，`open-alpha2` 已用 `XiaozhiClient + Android TTS` 替代。
