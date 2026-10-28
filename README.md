# Open Alpha2

> Language: English (this file) | [繁體中文](README_zh-Hant.md)

`com.open.alpha2` — an Android app that runs on the UBTECH Alpha2 robot
(`versionName "beta 8"` / `versionCode 8`) and turns it into a remotely
controllable device. On boot the app starts an HTTP + WebSocket server on port
`8888`; opening `http://<robot-ip>:8888/` from any browser on the same Wi-Fi
gives you the full control panel. A Blockly programming page lives at
`/blockly.html`, and the OpenAPI/AsyncAPI specs plus a Swagger UI are served
from the same port (see [HTTP API](#http-api)).

## Requirements

- Robot: `alpha2_10005` 5-mic (RK3288, Android 5.1.1/API 22) or `alpha2_10002`
  2-mic (Android 4.4.2/API 19); `armeabi-v7a` only. The app reads
  `ro.hardware.version` at startup and selects the matching serial/JNI paths.
  The APK declares `minSdkVersion 19`, `targetSdkVersion 22` (deliberate, no
  Play Store release — it is installed with `adb install`).
- Hardware wired up by the app:
  - `alpha2_10005`: chest MCU `/dev/ttyS1`, head MCU `/dev/ttyS3` (115200 baud);
    head/eye/mouth LEDs via `libhead_led.so` (JNI); head keys via
    `libhead_key_mgr.so`.
  - `alpha2_10002`: chest MCU `/dev/ttySAC0`, head MCU `/dev/ttySAC1`
    (115200 baud; fallback `ttyS1`/`ttyS3`); head/eye LEDs via head-MCU serial
    (`0x01`/`0x02`/`0x08`); head keys via 1.1.7.3 `libNativeClassJni.so`.
    The web panel hides the PIR card and the chest-firmware upgrade card.
  - Head +/- touch pads on `/dev/input/event0`.
- Robot and browser must be on the same Wi-Fi. The server is plain HTTP.
- ⚠️ **Trusted LAN only**: by default there is no auth — anyone on the
  network can play actions, view the camera, listen to the mic, or upload
  firmware. Do not bridge it to the internet or run it on a shared LAN.
  The Experiment tab offers an opt-in panel token (`system/auth/*`): once
  enabled, every `/api/*` (including reads), `/upload/*`, `/stream/*` and
  `/ws` call needs `Authorization: Bearer <token>` (401 + `WWW-Authenticate`
  otherwise); only `system/auth/*` and static pages stay open. `<img>`/WebSocket
  clients that cannot set headers may use `?panel_token=` instead. The old
  `?token=` alias is removed (it collided with the Xiaozhi OTA `token`).

## Build / install

Toolchain: Temurin JDK 11 + Gradle 7.0 (wrapper, SHA-256 pinned) + AGP
4.2.2 + Android SDK (`ANDROID_SDK_ROOT` pointing at your SDK;
`local.properties` is yours, never committed). Native side is pinned to NDK
`21.4.7075529` + CMake `3.10.2.4988404` so every machine builds the same
`libeasyopus.so`.

```bash
./gradlew assembleDebug --offline
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.open.alpha2/.MainActivity
adb -s <serial> forward tcp:8888 tcp:8888
```

`app/debug.keystore` is a committed, non-secret debug key (password
`android`); uninstall first if a signature mismatch blocks reinstall. The app
auto-starts on boot (`BootReceiver`), so the panel is reachable without
touching the robot. APK size: a clean build is ~5.5 MB — plain incremental
rebuilds leave stale entries behind and bloat it (harmless at runtime);
run `./gradlew clean assembleDebug --offline` before shipping.
CI (`build-apk.yml`, Temurin JDK 11 + Node 20) strips web comments, builds
the same APK and publishes it as artifact `open-alpha2-beta8-apk`
(file `open-alpha2-beta8.apk`, 7-day retention, latest 3 kept). Prebuilt `.so` files
(`head_led`/`head_key_mgr`/`serial_port`) live in
`sdk-module/hardware-direct/src/main/jniLibs`; `libeasyopus.so` is compiled
from `app/src/main/cpp` via CMake. Vosk + JNA AARs are vendored in
`app/libs` (armeabi-v7a only) so `--offline` builds need no Maven.

## Control panel (`app/src/main/assets/web/`)

Bilingual (EN/Traditional Chinese), 10 tabs, live updates over WebSocket, no refresh needed:

- Status (📊) — language switch, media volume, system/device info (battery,
  Wi-Fi/Bluetooth, robot UUID, chest firmware), sonar chart, PIR sensor with
  optional alert (red LED + ringtone), accelerometer live chart and optional
  4-direction tilt → head/eye LED.
- Actions (🕺) — action list with category sub-tabs, play/stop, playback speed
  slider (0.5–2.0x; servo and mp3 playback both follow the speed). Action
  chips are coloured by category (sub-category colour first, main-category
  fallback). (Pack download lives in the Experiment tab.)
- Servo (⚙️) — 22 joints in head/arm/leg/extra groups, individually or all at
  once, with live command-pose display. Dragging a joint slider follows live
  (~8 Hz); release finishes the move. Live angle read-back: one-click
  `servo/back-angle-all` (chest cmd `0x17`, pure read, all 22 axes incl.
  21/22) plus single-servo reads — `servo/angle` (chest cmd6 live query),
  `servo/back-angle` (cmd `0x17`), `servo/read` (trim/offset via cmd13) and
  `servo/angle-restore` (cmd6 read + immediate write-back so the joint
  never goes limp). Extra cards: per-servo firmware version (`servo/version`,
  chest cmd14) and hardware info (`servo/hardware`, cmd33), servo ID changer
  (`servo/modify-id`, chest cmd10 — honours the chest left/right bus wiring),
  and single-servo firmware flash (`servo/upgrade`, 7168-byte `.bin` gate).
  IK action card: squat / squat 2 (official “蹲式” deep-squat pose) /
  left-right split / left-forward split / right-forward split on five 0–100
  sliders, starting from the boot stand-up pose (robotStandup; servo 19 head
  yaw at 120); dragging sets the target and the robot moves 10% every 120 ms
  at constant speed (0→100 in ~1200 ms, five sliders mutually exclusive).
  A "Direct transition" switch controls action changes: off (default) returns
  to the boot pose at the same speed before moving to the new action; on blends
  the 20 servo angles straight from the current A pose to the B pose at the
  same 10%-per-120 ms rate (no boot pose in between). The choice is remembered.
  Direct mode linearly blends the 20 servo angles, so intermediate poses may
  not be valid IK — test at low percentages first.
  If a servo-grid slider is changed during an IK action, the IK percentage is
  kept; the next IK move blends from the current pose at the same 120 ms/step
  rate instead of snapping back to the old percentage. Axes the action does
  not drive keep the manual value (e.g. head yaw during a squat). Squat drives
  only the 10 leg servos 7–16; squat 2 and left-right split drive the legs plus
  shoulder roll 2/5 (targets 180/60 and 140/100 respectively); the two forward
  splits drive the legs plus shoulder pitch 1/4 (left-forward 90/70,
  right-forward 170/150). Head 19/20, hands 17/18 and elbows 3/6 are never
  touched by IK, so they keep their current/manual values throughout. Switching
  between actions leaves axes the new action does not drive at their current
  values (the off-mode return to boot only moves the new action's axes), so the
  arms do not drop.
  Boot safety: until the stand-up pose and START handshake finish, the API
  rejects motion commands (`ok:false, "booting"`), the stand-up is re-sent
  after the handshake, the panel ignores browser-restored slider values until
  the user actually drags them, and IK stops on WebSocket loss or a 2 s
  `servo/all` timeout. A crash/reboot therefore cannot make the robot replay
  the last IK pose by itself.
- Gamepad (🎮) — BT gamepad/keyboard in two modes sharing one key mapping
  (`gamepad/*`): A = HID paired straight to the robot (BT on/scan/pair from
  the page, offline OK, keys via `dispatchKeyEvent`, right stick drives head
  19/20); B = pad/keyboard on the browser (Gamepad API poll + key listener,
  injected via `gamepad/key`/`gamepad/axis`). The mapping table has two
  sections; `gamepad/key`/`gamepad/axis` stay available for direct scripting.
  Action values support 22-axis `servo1-22±` stepping (hold to repeat,
  release to stop; same calibration limits as the servo tab), leg IK poses
  (`ik:squat±` / `ik:ysquat±` / `ik:lr±` / `ik:fsplit±` (left-forward) /
  `ik:fsplit2±` (right-forward); `ik:fb±` is a left-forward compatibility
  alias; hold to move in 10% steps / 120 ms from the actual pose until
  0%/100%; switching actions first returns the old action to 0% at the same
  speed, and axes the new action does not drive stay put; panel taps carry a
  per-press seq, so a quick tap stays one step even if the HTTP up request
  reaches the server before the slow down request), local-music function keys
  (incl. random track) / random yoga / system ringtones (named /
  default call tone) / head-eye-mouth LEDs / all-lights-off / photo + face &
  colour tracking toggles + the 203 actions (5 groups: basic / dance / story /
  yoga / misc); mapping `stop` means stop-everything (action re-centre + TTS +
  music + radio + ringtone + all LEDs); `taken` reflects the real execution
  result, so the panel shows `✓` vs `✗ logged but not executed` at a glance.
- Walk forward / backward (hold) — Gamepad tab card (two hold buttons) + mapping
  values `walk:fwd` (default D-pad up) and `walk:back`: hold to walk, release and
  the robot eases back to the standing pose and stops. The gait is taken from
  `open-1508999860568-8step-smooth6.ubx` (`scripts/ubx_to_gait.py` →
  `assets/gait/walk_fwd.json`: start-up frames, a 19-frame loop, stand pose).
  The backward gait (`walk_back.json`) is the same loop played in reverse
  (time-reversed; same start-up and stand poses) — a kinematic approximation,
  not yet validated on hardware, so support the robot by hand on the first try.
  `walk/start?dir=fwd|back` selects the direction; asking for the opposite
  direction while walking hard-stops first, then restarts from the current pose;
  holding both directions at once cancels out and stops. Speed 0.5–2.0x
  (1.0x = the .ubx's own speed), live: map `walk:fwd` / `walk:back` onto a stick
  direction and the deflection is the speed (0.5x→2.0x), or keep it on a button
  and add `walk:fast` / `walk:slow` (stick deflection or full button). A 700 ms lease (`walk/start` heartbeat, key-hold
  beat) stops the robot if the page dies, the key-up is lost or the pad
  disconnects; any normal action/`ubx/stop` pre-empts the walk.
- Speech (🗣️) — conversation UI (type-to-test `speech/semantic_simulate`), Vosk
  offline ASR card (load/listen, model download in Experiment), TTS
  (engine/lang/voice pickers + optional auto-follow-Vosk).
- Music (🎵) — local music (`/mnt/internal_sd/music`, drag-and-drop upload,
  spectrum display), internet radio (radio-browser.info), Internet Archive
  online tracks (search in browser, play on robot), shared transport with
  random-motion filler and music-reactive Disco LED toggles in the
  now-playing row, plus a 5-band equalizer (presets + per-band vertical
  faders, colour-coded green→red, persisted server-side).
- LED (💡) — head/eye/mouth presets (long/flash/breathe/chase/dual, eye 3s
  countdown, mouth breathing speed). The mute button mirrors the real
  chest lamp (Xiaozhi-connected OR music-indicator); pad/Wi-Fi/head-5th-dot
  already follow via the 100 ms `led/state/get` poll.
- Camera (📷) — MJPEG stream, preview snapshot, hi-res photo capture (9 quality
  grades), robot-side mp4 video recording (🎥, 640x480 H264 to
  `/sdcard/DCIM/Alpha2/alpha2_vid_*.mp4`), digital zoom, pan-tilt joystick
  (drives head servos), shutter cue, mic-listen FAB (plays `/stream/mic` in
  the browser), fullscreen, plus two head trackers (mutually exclusive,
  servos 19/20): built-in face tracking (`android.media.FaceDetector`, zero
  dependencies) and pure-Java colour tracking (HSV + largest blob, 7 LED
  colours to pick from, optional walk-follow that steps/turns the robot
  toward the target via built-in `.ubx` gait actions).
- Xiaozhi (🤖) — AI voice-chat bridge: pairing code, connect/disconnect, mic
  state, text chat, TTS output choice (Xiaozhi opus vs Android TTS), custom
  OTA/server override, in-app MCP tool list with per-tool enable/disable,
  link to xiaozhi.me console.
- Experiment (Advanced, 🧪) — panel auth token, boot-voice mode (off /
  auto-connect Xiaozhi / auto-listen Vosk), resource downloads (Vosk model
  catalog, 202-action pack, Google TTS APK — one at a time via
  `DownloadGate`), 22ch servo angle tuner with offset backup/restore and
  factory-style calibration (batch trim write → EEPROM settle → home → rescan), robot
  ID changer (query/copy/QR preview/EEPROM write), chest firmware upgrade
  (upload-then-flash; upgrade requires a fresh `.bin` upload in the current
  page session — a stale file left on the robot is never reused), servo
  firmware version/upgrade cards, version link. Event log is a permanent card
  below the tabs.
- Blockly (`blockly.html`, standalone page) — visual programming with 12
  toolbox categories: flow, events, actions, speech/TTS/ringtones/local music,
  servos, LEDs, plus standard logic/math/text/variables/functions and
  ready-made examples; EN + Traditional Chinese block messages.

## Voice interaction

- Offline speech recognition via Vosk (models live on the sdcard and are
  auto-detected; Vosk needs API 21+, so it stays off on older devices).
  Models can also be downloaded from the Experiment tab (official
  alphacephei.com allowlist, ~30–100 MB each, auto-unzipped).
- Spoken replies use the Android system TTS (`speech/tts`, `speech/stop`),
  with optional auto language follow of the loaded Vosk model.
- Local intent matching in 10 dialogue languages (zh/en/es/fr/ja/de/it/pt/
  ko/ru, `app/src/main/assets/semantic/`). Each language ships 86 action
  intents, 13 function intents and 200 chat intents (299 groups total).
  - Action intents speak a reply and play a robot motion.
  - Function intents really act: stop-everything, volume step up/down,
    Bluetooth on/off, Wi-Fi on/off, photo capture, local-music
    play/next/previous. Power-off/reboot intents only speak a
    press-the-button prompt — a sideloaded app has no `REBOOT` permission.
  - Anything unmatched gets a fallback reply with a random filler motion.
  - Type-to-test without a microphone: `speech/semantic_simulate`.
- Head pads: +/- step the media volume (hold to repeat); pressing both
  together stops action + speech + music + radio, same as the voice
  stop-everything command.
- Boot voice mode (Experiment tab): choose off / auto-connect Xiaozhi /
  auto-start Vosk listening on next app start — saving only, no immediate
  action.

## HTTP API

Single source of truth: `openapi/open-alpha2-openapi.yml` (**208 paths**,
one operation each). Namespaces as spelled in the spec (`/api/*` count in
brackets):

- `/api/*` — main robot API, 199 paths: `accelerometer` (2), `action` (6),
  `apk` (3), `audio` (33), `battery` (1), `bt` (1), `camera` (12), `chest`
  (6), `color` (4), `debug` (2), `face` (5), `gamepad` (12), `head` (1),
  `led` (9), `misc` (2), `pir` (3), `servo` (19), `speech` (18), `status`
  (1), `ubx` (5), `vosk` (10), `wifi` (1) — plus the passthrough families
  `direct` (12), `system` (13) and `xiaozhi` (18). The browser client calls
  these under the **`/api/alpha2/*`** prefix (`api()` in `app-core.js`;
  `sysApi()`/`directApi()`/`xiaozhiApi()` for the other three families);
  all spellings reach the same dispatcher (`handleApi`), and the bare
  `/api/*` form is what the OpenAPI file documents.
- `/api/system/*` — discovery, music player, panel auth (`system/auth/*`).
- `/api/direct/*` — low-level passthrough (ubx/servo/LED/sonar).
- `/api/xiaozhi/*` — AI bridge control, OTA/MCP/TTS config, boot voice.
- `/upload/*` — raw binary uploads: walkie-talkie PCM (`audio`), music,
  chest firmware (`chest`), servo firmware (`servo-fw`).
- `/stream/camera` (MJPEG), `/stream/mic` (WAV chunks), `/ws` (RFC 6455
  event feed, described by `openapi/asyncapi.yml`), `/` and
  `/blockly.html`.

Self-describing endpoints on the robot (same port):

- `/docs` — Swagger UI (CDN; falls back to raw YAML offline).
- `/openapi.yml`, `/asyncapi.yml` — specs mirrored from `openapi/`.
- `/.well-known/openapi.json` (+ `.yml`, and non-dot `well-known/` fallback
  because aapt strips dotfiles) — machine-readable discovery.
- `/apis.yml` — apis.json-style directory entry; `/llms.txt` — short LLM
  pointer.

The JS client (`assets/web/api-client.js`, `Alpha2Api.*`, **199 typed
wrappers**) is generated from the spec — never hand-edit it:

```bash
python scripts/generate-api-client.py
python scripts/generate-api-client.py --check   # CI drift gate
```

`scripts/check-openapi-drift.py` keeps code and spec aligned (all six
handler families: `handleApi`/`handleSystemApi`/`handleDirectApi`/
`handleXiaozhiApi`/`handleUpload`/`handleStream`);
`scripts/check-api-client-routes.py` verifies each wrapper's caller prefix
matches the backend route; `scripts/check-spec-copies.py` keeps the web
mirrors in sync (`--fix` re-copies the yml files). The MCP tool table
(`McpToolsGenerated.java`, 22 tools) is generated from
`openapi/mcp-openapi-sync.yml` via `scripts/generate-mcp-tools.py`.

## Xiaozhi AI bridge + MCP

The app can connect the robot to a Xiaozhi AI backend for open-ended voice
chat (pairing code, connect/disconnect, mic start/stop, typed `send_text`,
custom OTA/WebSocket server override, TTS output as Xiaozhi opus or local
Android TTS). Over MCP it exposes 22 tools the model can call:
`self.robot.*` (list/play/stop/random actions, single/all servos,
head/eye/mouth LEDs, speak), `self.sensors.*` (PIR/sonar get + configure),
`self.camera.*` (take photo, image Q&A) and `self.media.*` (local
music/radio search-play-stop). Per-tool enable/disable is in the Xiaozhi
tab; boot auto-connect is the Experiment tab's boot-voice mode.

## Scripts (`scripts/`)

- Generators & gates: `generate-api-client.py`, `generate-mcp-tools.py`,
  `check-openapi-drift.py`, `check-api-client-routes.py`,
  `check-spec-copies.py` (see HTTP API above).
- Unit-test runners (zero-dependency, compile + run via `javac`/`java`):
  `test-apivalidator.py` (`ApiValidator` + colour-track + EQ suites),
  `test-serialcodec.py` (`SerialFrameCodec` suite).
- Offline `.ubx` analysis tools: `ubx_scan.py` (keyframe/track scan),
  `ubx_anatomy.py` (which servos move), `ubx_phase.py` (per-joint gait
  phase), `ubx_gaitdiff.py` / `ubx_armcheck.py` / `ubx_tail.py` (version
  diffs), `ubx_gate.py` (official dispatch simulation), `ubx_to_traj.py`
  (`.ubx` → `traj.csv`), `ubx_walk_humanize.py` (gait de-jerk),
  `ubx_checklist.py` (live action-list check), `ubx_corpus_order.py`
  (corpus order verification), `ubx_to_gait.py` (`.ubx` → hold-to-walk
  `walk_fwd.json` + the time-reversed `walk_back.json`, `--check` is a CI gate; engine tests: `test-gaitwalker.py`).

## On-device data

- Actions: `/sdcard/actions/` — `<id>.ubx` files plus per-action music and
  `actionInfo.txt`. The optional pack download (202 actions, from the
  project's GitHub release `actions.zip`) replaces this tree (old one
  renamed to `/sdcard/actions-backup`).
- Local music: `/mnt/internal_sd/music` (mp3/wav/ogg/m4a/flac, filename
  order = track order; voice next/previous wraps around; also uploadable
  from the Music tab via drag-and-drop).
- Photos & video: `/sdcard/DCIM/Alpha2/` (snapshots + `alpha2_vid_*.mp4`
  robot-side recordings).
- Chest firmware staging file: `/sdcard/AlphaII_CHEST_kernel.bin`
  (app-only small image, 1..114688 bytes, 4-byte aligned; uploaded from the
  Experiment tab, flashed by `chest/upgrade`).
- Vosk models: auto-detected on the sdcard top level (not bundled;
  download from the Experiment tab, ~30–100 MB each).

## Project layout

```
open-alpha2/
├── app/                            ← com.open.alpha2 (UI server, API, all centers)
│   ├── src/main/java/com/open/alpha2/  ← MainActivity wiring + ~69 classes:
│   │   Action/Speech/Audio/Music centers, ApiDispatcher/ApiValidator,
│   │   UbxApi/ChestQuery/ChestUpgrade/ServoUpgrade, Camera/FaceTrack/
│   │   ColorTrack centers, Led/Sonar/DeviceStatus, Gamepad/Gesture,
│   │   SemanticCenter + 10 matchers, Vosk/TTS/Ringtone, XiaozhiBridge/
│   │   Client/OTA/MCP, PanelAuth, EventBus, WebSocketServer, HttpServer, …
│   ├── src/main/assets/web/        ← control panel (10 tabs) + Blockly +
│   │                                  generated api-client.js (199 wrappers)
│   │                                  (CI strips web comments before build)
│   ├── src/main/assets/semantic/   ← 10 intent libraries (299 groups each:
│   │                                  86 action + 13 function + 200 chat)
│   │                                  + action category pools
│   ├── src/main/cpp/               ← easyopus JNI (CMake)
│   ├── src/test/java/…             ← ApiValidatorTest + ColorTrackTest + MusicEqTest
│   │                                  (run via scripts/test-apivalidator.py)
│   ├── libs/                       ← vendored vosk-android + JNA AARs (v7a)
│   └── build.gradle                ← versionName "beta 8", armeabi-v7a only
├── sdk-module/hardware-direct/     ← serial ports (/dev/ttyS1+S3), LED/pad JNI,
│                                      wire protocol, .ubx parse/play/speed-shifted music
│   └── src/test/java/…             ← SerialFrameCodecTest (scripts/test-serialcodec.py)
├── openapi/                        ← open-alpha2-openapi.yml (208 paths) /
│                                      asyncapi.yml / mcp-openapi-sync.yml + README
├── scripts/                        ← generators, drift/route/copy checks, unit-test
│                                      runners, ubx_* offline analysis tools
├── docs/legacy-beta3/              ← archaeology only (AIDL / iFlytek era)
└── .github/workflows/              ← build-apk.yml, check-openapi.yml
```

## CI (runs on push)

- `build-apk` (push to main/master + PR + manual): JDK 11 → install
  `platforms;android-25` + `build-tools;30.0.3` → strip web comments →
  `assembleDebug` → upload APK artifact `open-alpha2-beta8-apk`
  (file `open-alpha2-beta8.apk`, 7-day retention, latest 3 kept).
- `check-openapi` (push/PR touching spec, code, client, scripts or web spec
  copies): spec/code drift, client freshness (`--check` ×2: api-client +
  MCP), client→route prefix match, YAML validity, unit tests
  (`test-apivalidator.py` + `test-serialcodec.py`), web spec copies in sync,
  `versionName` == spec `info.version`.

## Known limitations

- Plain HTTP only, so browser-microphone talk-back (needs a secure context)
  is unavailable; listening to the robot mic in the browser works.
- No reboot/shutdown endpoint or voice action (no `REBOOT` permission);
  both only explain how to press the power button.
- No servo current feedback; the Servo tab shows command pose, with
  live angle verification via `servo/back-angle-all` (22 axes) and the
  single-servo reads.
- Chest `cmd6` live reads momentarily relax that servo (firmware behaviour);
  use `servo/angle-restore` (read + write-back in one backend call) when the
  joint must stay loaded. Chest firmware upgrade only flashes the session's
  freshly uploaded image. After flashing, power off and power on again to
  start writing the firmware: the power button LED changes blue → red →
  green in sequence, and a steady green means the update is complete, after
  which you may power off and power on again (rebooting Android alone is
  not enough).
- Spoken conversation follows the original "speak, wait 200 ms, move"
  timing; loud sound-effect actions skip the spoken reply so the two don't
  fight over the speaker.
- Expected logcat noise, not bugs: `linker: ... unused DT entry` warnings
  for the prebuilt native libraries (`head_led`/`head_key_mgr`/`serial_port`,
  Vosk, JNA) and occasional `Atlas: ... not in getPreloadedDrawables?`
  lines at startup. The Android 5.1 linker simply doesn't understand newer
  GNU ELF entries (`DT_VERNEED`, `DT_GNU_HASH`), and the Atlas line comes
  from framework graphics code — the libraries load fine (no
  `UnsatisfiedLinkError`, verified). Do not "fix" by hacking the `.so`
  files or disabling hardware acceleration.

License: GPL-3.0-only.
