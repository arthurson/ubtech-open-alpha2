# hardware-direct

> Language: English (this file) | [繁體中文](README_zh-Hant.md)

Android library module: the direct hardware layer between the app and the
Alpha2 chest/head boards. It owns the serial ports, the LED / head-key JNI
bridges, the wire-protocol constants and the `.ubx` action-file
parser/player. Zero project dependencies (`versionName "1.0-direct"`).

## Device wiring

- 5-mic (`alpha2_10005`): chest MCU `/dev/ttyS1`, head MCU `/dev/ttyS3`.
  2-mic (`alpha2_10002`): `/dev/ttySAC0` / `/dev/ttySAC1` (fallback `ttyS1`/`ttyS3`).
  Both 115200 baud; `HardwareVersion` reads `ro.hardware.version` to choose.
- Frames (encode/decode in `SerialFrameCodec`, tail `ED`): long form
  `F8 8F LEN ID INDEX CMD PARAM SUM ED` (checksum over LEN+ID+INDEX+CMD+PARAM);
  legacy short form `F8 8F LEN CMD PARAM SUM ED` is still decoded as a fallback.
- Head +/- pads: `/dev/input/event0`, consumed via `HeadKeyPoller`.
- Head/eye/mouth LEDs: `libhead_led.so` via `LedControl`.
- The device nodes are world-accessible, so a normal user app (no system
  app, no platform signature) can open them.

## Contents

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
- `ServoEnableMask` — per-servo on/off mask (default 1–20 on, 21–22 off); a
  disabled servo gets no TX/RX from the controller layer.
- `ServoPoseTracker` — tracks the 22-joint command pose (accepts legacy
  20- or full 22-joint frames; 20 only refreshes the first 20 joints).
- `HardwareDirectManager` / `LocalAlpha2Services` — single entry point the
  app starts on a background thread; reports whether direct mode is active.

`.ubx` playback (`...hardware/ubx`):

- `UbxFile` / `UbxParser` — action-file parse.
- `UbxPlayer` — drift-free tick playback with 0.5–2.0x speed shift (servo and
  mp3 music follow the same speed);
  `stop()` halts holding the last pose (the app-level stop plays the recovery
  action on top).
- `VoiceStream` — per-action background music, locked to the same clock.
- `GaitProgram` / `GaitWalker` — hold-to-walk engine (start-up frames, loop,
  stand pose; speed 1.0–2.0x).

ONNX policy (`...hardware/policy`, experimental): `OnnxMlp` (pure-Java MLP
inference), `PolicyManifest`, `PolicyRunner` (safety envelope, lease),
`Alpha2PolicyIo`, `FallRecoveryDetector`. See `docs/onnx-policy.md`.

Native libs (`src/main/jniLibs/armeabi-v7a`): `libhead_led.so`,
`libhead_key_mgr.so`, `libserial_port.so`, `libserial_port_2mic.so`,
`libNativeClassJni.so` (2-mic head keys).

## Integration

`settings.gradle` already includes the module; `app/build.gradle` depends on
it with `implementation project(':sdk-module:hardware-direct')`. The app
starts it once on a background thread:

```java
LocalAlpha2Services local = new LocalAlpha2Services(this);
boolean direct = local.start();
```

Install as a normal user app, then sanity-check the nodes:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ls -l /dev/ttyS1 /dev/ttyS3   # expect crwxrwxrwx (777)
```
