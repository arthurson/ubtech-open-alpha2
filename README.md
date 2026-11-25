# Open Alpha2

> Language: English (this file) | [繁體中文](README_zh-Hant.md)

`com.open.alpha2` (`versionName "beta 8.4"`, `versionCode 9`) is an Android app
for the UBTECH Alpha2 robot. On boot it starts an HTTP + WebSocket server on
port `8888`; open `http://<robot-ip>:8888/` from a browser on the same Wi-Fi for
the full control panel. `/blockly.html` is a block-programming page; `/docs` is
Swagger UI for the OpenAPI spec.

## Requirements

- Robot: `alpha2_10005` 5-mic (Android 5.1.1) or `alpha2_10002` 2-mic
  (Android 4.4.2); `armeabi-v7a` only. The app reads `ro.hardware.version` and
  picks the matching serial/JNI paths. `minSdk 19` / `targetSdk 22`, installed
  with `adb install` (no Play Store).
- Wiring: chest MCU + head MCU over serial at 115200 baud (`ttyS1`/`ttyS3`;
  2-mic: `ttySAC0`/`ttySAC1`), LEDs and head keys via JNI, head +/- pads on
  `/dev/input/event0`.
- Robot and browser on the same Wi-Fi.

## Security

- **Trusted LAN only** — plain HTTP, the token travels in clear text.
- The panel is password-protected by default. On first launch the robot shows a
  token (`XXXXX-XXXXX`) on its screen; the browser asks once. Every `/api/*`,
  `/upload/*`, `/stream/*` and `/ws` call needs `Authorization: Bearer <token>`
  (`?panel_token=` for `<img>`/WebSocket clients). Change or disable it in the
  Experiment tab. Guessing is throttled per IP (HTTP 429 + `Retry-After`).
  Clearing app data generates a new token.
- Downloads (actions pack, Google TTS APK, Vosk models) use strict TLS (system
  CAs + bundled Mozilla CA list, refresh with `scripts/update-cacert.py`),
  restricted redirects and zip-slip / size-limited extraction. If a download
  fails with a certificate error, check the robot's clock.
- **Known gap**: Xiaozhi OTA + WebSocket connections skip certificate
  verification (`XiaozhiTrustAllSsl`).

## Build / install

Temurin JDK 11, Gradle 7.0 (wrapper), AGP 4.2.2, Android SDK (`compileSdk 25`),
NDK `21.4.7075529`, CMake `3.10.2.4988404`. Vosk/JNA AARs are vendored in
`app/libs`, so `--offline` builds need no Maven.

```bash
./gradlew assembleDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.open.alpha2/.MainActivity
adb forward tcp:8888 tcp:8888          # optional, to reach the panel over USB
```

Signed with the committed debug key `app/debug.keystore` (password `android`);
uninstall first on a signature mismatch. The app auto-starts on boot. Run
`./gradlew clean assembleDebug --offline` before shipping (incremental builds
bloat the APK). CI (`build-apk.yml`) strips web comments, builds the APK and
uploads `open-alpha2-beta8.4.apk`.

## Control panel (`app/src/main/assets/web/`)

English / Traditional Chinese, 10 tabs, live updates over WebSocket.

- **Status** — language, volume, device info, sonar chart, PIR alert,
  accelerometer chart with tilt → LED, fall self-recovery switch.
- **Actions** — action list by category, play/stop, speed 0.5–2.0x.
- **Servo** — 22 joints with live drag; live angle read-back
  (`servo/back-angle-all`, `servo/angle`, `servo/angle-restore`); servo
  firmware version/hardware info, ID changer, single-servo firmware flash; IK
  pose sliders (squat/lean/split, kick, side lunge, …). Servos switched off (see
  Experiment) have their sliders hidden here.
- **Gamepad** — Bluetooth pad paired to the robot, or pad/keyboard on the
  browser; editable key mapping (servos, IK, music, LEDs, actions, photo,
  tracking, stop-everything); hold-to-walk forward/back.
- **Speech** — conversation UI, Vosk offline ASR, TTS pickers.
- **Music** — local music (drag-and-drop upload), internet radio, Internet
  Archive tracks, 5-band equalizer, random-motion and disco-LED options.
- **LED** — head / eye / mouth presets.
- **Camera** — MJPEG stream, snapshot, hi-res photo, mp4 recording, zoom,
  pan-tilt joystick, mic listening, face tracking, colour tracking and object tracking (box a target in the preview; with
  optional walk-follow).
- **Xiaozhi** — AI voice-chat bridge: pairing, connect, text chat, TTS output
  choice, custom OTA/server, per-tool MCP enable/disable.
- **Experiment** — panel token, boot-voice mode, resource downloads (Vosk
  models, actions pack, Google TTS APK), **servo on/off switch** (default
  1–20 on, 21–22 off; off = servo released first, then no TX/RX at all —
  API, actions, IK, gamepad and reads all skip it; saved across restarts),
  22-axis servo angle tuner with offset backup/restore, robot ID changer,
  chest firmware upgrade, ONNX policy card, event log.
- **Blockly** (`blockly.html`) — visual programming over the same API.

Experimental (not yet verified on hardware): ONNX policy playback
(`policy/*`, see [docs/onnx-policy.md](docs/onnx-policy.md)), fall
self-recovery (`fall/*`, off by default, needs the `BackStand`/`FrontStand`
actions), and backward walking.

## Voice

- Offline recognition with Vosk (models on the sdcard or downloaded from the
  Experiment tab; needs API 21+).
- Replies via Android system TTS.
- Local intent matching in 10 languages (zh/en/es/fr/ja/de/it/pt/ko/ru): per
  language 86 action intents, 13 function intents (stop, volume, Bluetooth,
  Wi-Fi, photo, music) and 200 chat intents. Unmatched input gets a fallback
  reply with a random motion (toggle it off via the panel switch or
  `speech/semantic_random_move/set?enabled=false`; explicitly requested actions
  are unaffected). Test without a mic: `speech/semantic_simulate`.
- Head pads: +/- change volume; both together stop everything.

## HTTP API

Single source of truth: `openapi/open-alpha2-openapi.yml` (**238 paths**:
228 under `/api/*`, plus `/upload/*`, `/stream/camera`, `/stream/mic`, `/ws`).
`/api/system/*`, `/api/direct/*` and `/api/xiaozhi/*` are separate families;
the browser calls the rest under `/api/alpha2/*`.

Served on the robot: `/docs`, `/openapi.yml`, `/asyncapi.yml`,
`/.well-known/openapi.json`, `/apis.yml`, `/llms.txt`.

`assets/web/api-client.js` (228 typed wrappers) and `McpToolsGenerated.java`
(22 MCP tools) are generated — never hand-edit:

```bash
python scripts/generate-api-client.py          # add --check for drift
python scripts/generate-mcp-tools.py           # add --check for drift
python scripts/check-openapi-drift.py          # code ↔ spec
python scripts/check-api-client-routes.py      # wrapper prefix ↔ route
python scripts/check-spec-copies.py --fix      # web mirrors of the spec
```

## Xiaozhi AI bridge + MCP

Connects the robot to a Xiaozhi backend for open-ended voice chat. Over MCP it
exposes 22 tools: `self.robot.*` (actions, servos, LEDs, speak),
`self.sensors.*` (PIR/sonar), `self.camera.*` (photo, image Q&A) and
`self.media.*` (local music / radio).

## Tests and scripts

- JVM unit/e2e tests (no Android needed): `scripts/test-apivalidator.py`
  (app tests + `app/src/e2e`), `test-serialcodec.py`, `test-headkeypoller.py`,
  `test-policy.py`, `test-gaitwalker.py`.
- `policy_tool.py` packs a policy for `policy/load`; `ubx_*.py` analyse `.ubx`
  action files; `ubx_to_gait.py` builds `assets/gait/walk_*.json`.
- CI `check-openapi.yml` runs the drift checks, YAML validation, all tests above
  and a `versionName` == spec version check.

## On-device data

- Actions: `/sdcard/actions/` (`<id>.ubx` + music + `actionInfo.txt`). The
  optional pack download replaces it (old one kept as `/sdcard/actions-backup`).
- Music: `/mnt/internal_sd/music`. Photos/video: `/sdcard/DCIM/Alpha2/`.
- Chest firmware staging: `/sdcard/AlphaII_CHEST_kernel.bin` (fresh upload
  required for every flash). Vosk models: sdcard top level.

## Layout

```
app/                         UI server, API, all centers (com.open.alpha2)
  src/main/assets/web/       control panel + Blockly + generated api-client.js
  src/main/assets/semantic/  10 intent libraries
  src/main/cpp/              easyopus (Opus JNI)
sdk-module/hardware-direct/  serial ports, LED/head-key JNI, wire protocol,
                             .ubx player, gait walker, ONNX policy runner
openapi/                     OpenAPI / AsyncAPI / MCP sync specs
scripts/                     generators, checks, test runners, ubx tools
docs/                        onnx-policy.md, AlphaDeus comparison, legacy-beta3
```

## Known limitations

- Plain HTTP, so browser-microphone talk-back is unavailable (listening to the
  robot mic works).
- No reboot/shutdown (no `REBOOT` permission for a sideloaded app).
- No servo current feedback; the Servo tab shows the command pose plus
  on-demand angle reads. Chest `cmd6` reads briefly relax that servo — use
  `servo/angle-restore` to keep it loaded.
- After a chest firmware flash, power-cycle the robot; the power LED goes
  blue → red → green, and steady green means done.

License: GPL-3.0-only.
