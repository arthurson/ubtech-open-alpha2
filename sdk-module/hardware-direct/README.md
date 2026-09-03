# hardware-direct — 1.1.7.3 这一代硬件的最小直驱层

本模块是从 `com.ubtechinc.alpha2services_base.3.002.apk` / `2.004` 拆解后，**只保留 1.1.7.3 这一代真正需要的硬件直驱代码**的可移植子集。

## 为什么不是整包搬运

* `3.002` 整包 9991 类，`sharedUserId=android.uid.system`，硬编码 `/dev/ttySAC0/1` (Exynos)，而 RK3288 实机是 `/dev/ttyS0/1`，直接搬会 `open failed`。
* `1.1.7.3.20` 的 17 个 AIDL + 命令表已成歷史 (`AIDL_REFERENCE_ALPHA2.md` 留做逆向參考)；
  2026-09 起直驅要用的 wire 常數收喺本 module 嘅 `RobotWire` /
  `DeveloperPacketData`，唔再依賴任何 SDK module。
* 真正需要的是 `libserial_port.so + SerialPortFile` 的 `open("/dev/ttyS*")` 能力，这部分只有 2 个 Java + 1 个 so。

## 本模块包含

* `DirectSerialConfig.java` — 设备路径映射 (`/dev/ttyS0` 主 + `/dev/ttySAC0` 兼容) 和波特率
* `SerialFrameCodec.java` — `F8 8F len cmd param checksum ED` 编解码 (来源 `AIDL_REFERENCE` 第5章)
* `DirectSerialPort.java` — 优先走 `libserial_port.so` 的 `SerialPortFile` JNI，失败回退到纯 `File`，自动处理 `ttySAC` vs `ttyS` 双路径；必须以 system app + 平台签名安装到 `/system/priv-app` 才能打开设备
* `DirectChestController.java` — 单舵机/全舵机/声纳/PIR/升级 (复用 `DeveloperPacketData`)
* `DirectHeadController.java` — 降噪等头 MCU 命令
* `DirectLedController.java` — 5-mic LED 的 `libhead_led.so` JNI 直驱 (已在 `MouthLedData` 验证)
* `HardwareDirectManager.java` / `LocalAlpha2Services.java` — 对上层 `MainActivity` 的无感切换入口

## 集成步骤 (POC)

1. `settings.gradle` 已加入 `include ':sdk-module:hardware-direct'`
2. `app/build.gradle` 加 `implementation project(':sdk-module:hardware-direct')`
3. `MainActivity.onCreate` 后台线程：
   ```java
   LocalAlpha2Services local = new LocalAlpha2Services(this);
   boolean direct = local.start(); // 内部 tryEnableDirect()
   // 后续所有 chest/head 调用先判 direct.isDirectActive()
   ```
4. 以 system app 安装 (需平台签名)：
   ```bash
   adb root; adb remount
   adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/OpenAlpha2/OpenAlpha2.apk
   adb shell chmod 644 /system/priv-app/OpenAlpha2/OpenAlpha2.apk
   adb shell chcon u:object_r:system_file:s0 /system/priv-app/OpenAlpha2/OpenAlpha2.apk
   adb reboot
   adb shell dumpsys package com.open.alpha2 | grep flags # 应含 SYSTEM|PRIVILEGED
   adb shell ls -Z /dev/ttyS0 # 应为 crw-rw---- system
   ```

## 关于 so 的选择

* `jniLibs/armeabi-v7a/libhead_led.so` 保留的是 `1.1.7.3` 实机同款 (13516b)，不是 `3.002` 的 17824b 版本
* `libserial_port.so` 来自 `3.002` 仅作占位，1.1.7.3 真机上建议直接 `adb pull /system/lib/libserial_port.so` 替换，确保是 `ttyS0` 版本

## 不搬的部分

语音 `libcae.so`、腾讯 `QAL/XG` 推送、`Cm*` protobuf 全不搬，`open-alpha2` 已用 `XiaozhiClient + Android TTS` 替代。
