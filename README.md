# Open Alpha2

`com.open.alpha2` — an Android app that runs on the UBTECH Alpha2 robot
(`versionName "beta 5"` / `versionCode 5`) and turns it into a remotely
controllable device. On boot the app starts an HTTP + WebSocket server on port
`8888`; opening `http://<robot-ip>:8888/` from any browser on the same Wi-Fi
gives you the full control panel. A Blockly programming page lives at
`/blockly.html`.

`com.open.alpha2` —— 裝在 UBTECH Alpha2 機械人上的 Android 應用程式
（`versionName "beta 5"`／`versionCode 5`），讓機械人可以被遙距操控。
開機後會自動啟動 HTTP＋WebSocket 伺服器（port `8888`）；在同一 Wi-Fi
下的任何瀏覽器開啟 `http://<機械人IP>:8888/`，即可使用完整控制面板。
Blockly 編程頁面位於 `/blockly.html`。

## Requirements / 前提條件

- Robot: RK3288 (`armeabi-v7a` only), Android 5.1.1 (API 22).
  The APK declares `minSdkVersion 19`, `targetSdkVersion 22` (deliberate, no
  Play Store release — it is installed with `adb install`).
- Hardware wired up by the app: chest MCU on `/dev/ttyS1`, head MCU on
  `/dev/ttyS3` (115200 baud); head +/- touch pads on `/dev/input/event0`;
  head/eye/mouth LEDs via `libhead_led.so` (JNI).
- Robot and browser must be on the same Wi-Fi. The server is plain HTTP.
- ⚠️ **Trusted LAN only**: by default there is no auth — anyone on the
  network can play actions, view the camera, listen to the mic, or upload
  firmware. Do not bridge it to the internet or run it on a shared LAN.
  The Experiment tab offers an opt-in panel token (`system/auth/*`): once
  enabled, every `/api/*` (including reads) and `/upload/*` call needs a
  `panel_token` (401 otherwise); only `system/auth/*`, static pages, `/ws`
  and `/stream/*` stay open.

- 機械人：RK3288（僅 `armeabi-v7a`），Android 5.1.1（API 22）。
  APK 宣告 `minSdkVersion 19`、`targetSdkVersion 22`（刻意如此，不上架
  Play Store——以 `adb install` 直接安裝）。
- 應用程式接管的硬件：胸板 MCU（`/dev/ttyS1`）、頭板 MCU（`/dev/ttyS3`，
  波特率 115200）；頭頂＋/－觸控鍵（`/dev/input/event0`）；頭／眼／嘴
  LED（經 `libhead_led.so` JNI）。
- 機械人與瀏覽器裝置必須在同一 Wi-Fi。伺服器只用純 HTTP。
- ⚠️ **僅限可信內網**：預設沒有認證——同網段任何人都可以播放動作、查看
  相機、收聽咪高峰或上載韌體。切勿橋接至互聯網，亦不要放在共用大內網。
  實驗分頁提供自選面板口令（`system/auth/*`）：啟用後，所有 `/api/*`
  （包括讀取操作）及 `/upload/*` 都必須帶上 `panel_token`（否則回 401）；
  只有 `system/auth/*`、靜態頁、`/ws` 及 `/stream/*` 保持開放。

## Build / install / 編譯／安裝

Toolchain: Temurin JDK 11 + Gradle 7.0 + Android SDK (`ANDROID_SDK_ROOT`
pointing at your SDK; `local.properties` is yours, never committed).

工具鏈：Temurin JDK 11＋Gradle 7.0＋Android SDK（`ANDROID_SDK_ROOT`
指向你的 SDK；`local.properties` 只屬本機，切勿提交）。

```bash
./gradlew assembleDebug --offline
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.open.alpha2/.MainActivity
adb -s <serial> forward tcp:8888 tcp:8888
```

`app/debug.keystore` is a committed, non-secret debug key (password
`android`); uninstall first if a signature mismatch blocks reinstall. The app
auto-starts on boot (`BootReceiver`), so the panel is reachable without
touching the robot. CI (`build-apk.yml`, JDK 11) builds the same APK and
publishes it as `open-alpha2-beta5.apk`. Prebuilt `.so` files
(`head_led`/`head_key_mgr`/`serial_port`) live in
`sdk-module/hardware-direct/src/main/jniLibs`; `libeasyopus.so` is compiled
from `app/src/main/cpp` via CMake.

`app/debug.keystore` 是已提交的公開測試鑰匙（密碼 `android`）；如因簽名
不符無法覆蓋安裝，請先解除安裝。應用程式會在開機時自動啟動
（`BootReceiver`），無需觸碰機械人即可連上控制面板。CI
（`build-apk.yml`，JDK 11）會編出同一個 APK，並以
`open-alpha2-beta5.apk` 之名發佈。預編 `.so` 檔
（`head_led`／`head_key_mgr`／`serial_port`）放在
`sdk-module/hardware-direct/src/main/jniLibs`；`libeasyopus.so` 由
`app/src/main/cpp` 經 CMake 即時編譯。

## Control panel / 控制面板 (`app/src/main/assets/web/`)

Bilingual (EN/繁中), live updates over WebSocket, no refresh needed:

中英雙語（EN／繁中），經 WebSocket 即時更新，無需重新整理：

- Status — system/device info, battery, Wi-Fi/Bluetooth, sonar, PIR,
  accelerometer.
- 狀態——系統／裝置資訊、電池、Wi-Fi／藍牙、聲納、PIR、加速度計。
- Actions — action list with categories, play/stop, playback speed
  (0.5/0.67/1/1.5/2x), action-pack download.
- 動作——動作清單及分類、播放／停止、變速（0.5／0.67／1／1.5／2 倍）、
  動作包下載。
- Servo — 20 joints individually or all at once, live command-pose display,
  single-servo read-back via `servo/angle`.
- 舵機——20 軸逐顆或全組控制、即時指令位姿顯示、經 `servo/angle`
  讀回單顆舵機角度。
- Speech — conversation UI, TTS (voice/lang/engine pickers), Vosk model
  management, semantic-simulate test box.
- 語音——對話界面、TTS（聲線／語言／引擎選擇）、Vosk 模型管理、語意模擬
  測試框。
- Music — local music (`/mnt/internal_sd/music`) with spectrum display and
  dance-along random motions, plus internet radio search/play.
- 音樂——本地音樂（`/mnt/internal_sd/music`，附頻譜顯示及隨歌伴舞動作）、
  網絡電台搜尋／播放。
- LED — head/eye/mouth presets.
- LED——頭／眼／嘴燈預設效果。
- Camera — MJPEG stream, preview snapshot, hi-res photo capture, digital
  zoom, pan-tilt joystick (drives head servos), shutter cue.
- 相機——MJPEG 串流、預覽快照、高清拍照、數碼變焦、雲台搖桿（帶動頭部
  舵機）、快門提示音。
- Xiaozhi — AI voice-chat bridge: connect/disconnect, mic control, OTA and
  TTS config, text chat, auto mode, boot greeting.
- 小智——AI 語音對話橋接：連接／斷線、咪高峰控制、OTA 及 TTS 設定、文字
  對話、自動模式、開機問候語。
- Advanced — UUID, chest firmware upgrade, mic stream, auth token, event log.
- 進階——UUID、胸板韌體升級、咪高峰串流、認證口令、事件紀錄。
- Blockly (`blockly.html`, standalone page) — visual programming with
  action/servo/ringtone blocks.
- Blockly（`blockly.html`，獨立頁面）——以動作／舵機／鈴聲積木做可視化
  編程。

## Voice interaction / 語音互動

- Offline speech recognition via Vosk (models live on the sdcard and are
  auto-detected; Vosk needs API 21+, so it stays off on older devices).
- 離線語音辨識用 Vosk（模型放在 sdcard 並自動偵測；Vosk 需要 API 21＋，
  較舊裝置會自動停用）。
- Spoken replies use the Android system TTS (`speech/tts`, `speech/stop`).
- 回答語音使用 Android 系統 TTS（`speech/tts`、`speech/stop`）。
- Local intent matching in 10 dialogue languages (zh/en/es/fr/ja/de/it/pt/
  ko/ru, `app/src/main/assets/semantic/`). Each language ships 85 action
  intents, 13 function intents and 200 chat intents (~298 total).
- 十種對話語言的本地意圖配對（中英西法日德意葡韓俄，
  `app/src/main/assets/semantic/`）。每種語言各有 85 組動作意圖、13 組
  功能意圖及 200 組閒聊意圖（約 298 組）。
  - Action intents speak a reply and play a robot motion.
  - 動作意圖會說出回覆並播放機械人動作。
  - Function intents really act: stop-everything, volume step up/down,
    Bluetooth on/off, Wi-Fi on/off, photo capture, local-music
    play/next/previous. Power-off/reboot intents only speak a
    press-the-button prompt — a sideloaded app has no `REBOOT` permission.
  - 功能意圖會真正執行：全部停止、音量加／減一格、藍牙開／關、Wi-Fi
    開／關、拍照、本地音樂播放／上／下一首。關機／重啟意圖只會說出
    「請按電源鍵」的提示——側載應用程式沒有 `REBOOT` 權限。
  - Anything unmatched gets a fallback reply with a random filler motion.
  - 配對不中的說話會得到一句候補回覆，外加一個隨機填充動作。
  - Type-to-test without a microphone: `speech/semantic_simulate`.
  - 不用咪高峰也可打字測試：`speech/semantic_simulate`。
- Head pads: +/- step the media volume (hold to repeat); pressing both
  together stops action + speech + music + radio, same as the voice
  stop-everything command.
- 頭頂按鍵：＋／－逐格調校媒體音量（長按連調）；兩鍵齊按即停止動作＋
  語音＋音樂＋電台，等同語音全部停止指令。

## HTTP API / HTTP 接口

Single source of truth: `openapi/open-alpha2-openapi.yml` (~140 paths).
Namespaces:

單一真相源：`openapi/open-alpha2-openapi.yml`（約 140 條路徑）。
命名空間：

- `/api/alpha2/*` — actions, `.ubx` playback, servos, speech, LEDs, audio,
  camera, battery, Wi-Fi/BT, sonar/PIR, chest, misc.
- `/api/alpha2/*`——動作、`.ubx` 播放、舵機、語音、LED、音頻、相機、電池、
  Wi-Fi／藍牙、聲納／PIR、胸板、雜項。
- `/api/system/*` — discovery, music player, panel auth.
- `/api/system/*`——裝置探索、音樂播放器、面板認證。
- `/api/direct/*` — low-level passthrough (ubx/servo/LED/sonar).
- `/api/direct/*`——底層直調（ubx／舵機／LED／聲納）。
- `/api/xiaozhi/*` — AI bridge control, OTA/MCP/TTS config.
- `/api/xiaozhi/*`——AI 橋接控制、OTA／MCP／TTS 設定。
- `/upload/*` — audio, music and chest-firmware uploads.
- `/upload/*`——音頻、音樂及胸板韌體上載。
- `/stream/camera` (MJPEG), `/stream/mic`, `/ws` (RFC 6455 event feed,
  described by `openapi/asyncapi.yml`), `/` and `/blockly.html`.
- `/stream/camera`（MJPEG）、`/stream/mic`、`/ws`（RFC 6455 事件推送，
  見 `openapi/asyncapi.yml`）、`/` 及 `/blockly.html`。

The JS client (`assets/web/api-client.js`, `Alpha2Api.*`) is generated from
the spec — never hand-edit it:

JS 客戶端（`assets/web/api-client.js`，`Alpha2Api.*`）由 spec 自動生成——
切勿手改：

```bash
python scripts/generate-api-client.py
python scripts/generate-api-client.py --check   # CI drift gate
```

`scripts/check-openapi-drift.py` keeps code and spec aligned; the MCP tool
table (`McpToolsGenerated.java`, 22 tools) is generated from
`openapi/mcp-openapi-sync.yml` via `scripts/generate-mcp-tools.py`.

`scripts/check-openapi-drift.py` 負責代碼與 spec 對齊；MCP 工具表
（`McpToolsGenerated.java`，22 個工具）由 `openapi/mcp-openapi-sync.yml`
經 `scripts/generate-mcp-tools.py` 生成。

## Xiaozhi AI bridge + MCP / 小智 AI 橋接＋MCP

The app can connect the robot to a Xiaozhi AI backend for open-ended voice
chat (connect/disconnect, mic start/stop, auto mode, OTA config, per-voice
TTS config, typed `send_text`). Over MCP it exposes 22 tools the model can
call: `self.robot.*` (list/play/stop/random actions, single/all servos,
head/eye/mouth LEDs, speak), `self.sensors.*` (PIR/sonar),
`self.camera.*` (take photo, image Q&A) and `self.media.*` (local
music/radio).

應用程式可將機械人接上小智 AI 後端做開放式語音對話（連接／斷線、咪高峰
開／關、自動模式、OTA 設定、逐聲線 TTS 設定、手動 `send_text`）。經 MCP
向模型開放 22 個工具：`self.robot.*`（列出／播放／停止／隨機動作、單顆／
全組舵機、頭／眼／嘴燈、說話）、`self.sensors.*`（PIR／聲納）、
`self.camera.*`（拍照、看圖問答）及 `self.media.*`（本地音樂／電台）。

## On-device data / 機身數據

- Actions: `/sdcard/actions/` — `<id>.ubx` files plus per-action music and
  `actionInfo.txt`.
- 動作：`/sdcard/actions/`——`<id>.ubx` 檔、外加每個動作的配樂及
  `actionInfo.txt`。
- Local music: `/mnt/internal_sd/music` (mp3/wav/ogg/m4a/flac, filename
  order = track order; voice next/previous wraps around).
- 本地音樂：`/mnt/internal_sd/music`（mp3／wav／ogg／m4a／flac，檔名排序
  即曲目順序；語音上／下一首會循環）。
- Photos: `/sdcard/DCIM/Alpha2/`.
- 照片：`/sdcard/DCIM/Alpha2/`。
- Vosk models: auto-detected on the sdcard (not bundled, ~65 MB each).
- Vosk 模型：在 sdcard 自動偵測（不隨 App 附送，每個約 65 MB）。

## Project layout / 項目結構

```
open-alpha2/
├── app/                            ← com.open.alpha2 (UI server, API, all centers)
│   ├── src/main/java/com/open/alpha2/  ← MainActivity wiring + centers
│   │   (ActionDirect, ApiDispatcher, AudioCenter, CameraApi, DeviceStatus,
│   │    GestureCenter, LedCenter, SemanticCenter + 10 matchers, SpeechCenter,
│   │    TtsCenter, VoskController/Api, XiaozhiBridge/Client, …)
│   ├── src/main/assets/web/        ← control panel + Blockly + generated api-client.js
│   ├── src/main/assets/semantic/   ← 10 intent libraries + action category pools
│   ├── src/main/cpp/               ← easyopus JNI (CMake)
│   └── build.gradle                ← versionName "beta 5", armeabi-v7a only
├── sdk-module/hardware-direct/     ← serial ports, LED/pad JNI, wire protocol,
│                                      .ubx parse/play/speed-shifted music
├── openapi/                        ← open-alpha2-openapi.yml / asyncapi.yml /
│                                      mcp-openapi-sync.yml + README
├── scripts/                        ← generators, drift checks, ubx inspect tools
└── .github/workflows/              ← build-apk.yml, check-openapi.yml
```

## CI (runs on push) / 持續整合（push 即跑）

- `build-apk`: JDK 11 → strip web comments → `assembleDebug` → upload APK.
- `build-apk`：JDK 11 → 剝離網頁註解 → `assembleDebug` → 上載 APK。
- `check-openapi`: spec/code drift, client freshness, route prefixes, MCP
  freshness, YAML validity, `ApiValidator`/serial-codec unit tests, web spec
  copies in sync, `versionName` == spec `info.version`.
- `check-openapi`：spec／代碼漂移、客戶端新鮮度、路由前綴、MCP 新鮮度、
  YAML 合法性、`ApiValidator`／串口編解碼單元測試、網頁 spec 副本同步、
  `versionName` == spec `info.version`。

## Known limitations / 已知限制

- Plain HTTP only, so browser-microphone talk-back (needs a secure context)
  is unavailable; listening to the robot mic in the browser works.
- 只有純 HTTP，瀏覽器咪高峰對講（需要安全上下文）用不到；在瀏覽器收聽
  機械人咪高峰則正常。
- No reboot/shutdown endpoint or voice action (no `REBOOT` permission);
  both only explain how to press the power button.
- 沒有重啟／關機接口或語音動作（沒有 `REBOOT` 權限）；兩者只會說明如何
  按電源鍵。
- No servo current feedback; the Servo tab shows command pose, with
  single-servo live verification via `servo/angle`.
- 沒有舵機電流回授；舵機分頁顯示指令位姿，另有 `servo/angle` 做單顆舵機
  即時驗證。
- Spoken conversation follows the original "speak, wait 200 ms, move"
  timing; loud sound-effect actions skip the spoken reply so the two don't
  fight over the speaker.
- 語音對話沿用「先說話、等 200 毫秒、再動作」的時序；帶大音效的動作會
  跳過語音回覆，以免兩者搶喇叭。
- Expected logcat noise, not bugs: `linker: ... unused DT entry` warnings
  for the prebuilt native libraries (`head_led`/`head_key_mgr`/`serial_port`,
  Vosk, JNA) and occasional `Atlas: ... not in getPreloadedDrawables?`
  lines at startup. The Android 5.1 linker simply doesn't understand newer
  GNU ELF entries (`DT_VERNEED`, `DT_GNU_HASH`), and the Atlas line comes
  from framework graphics code — the libraries load fine (no
  `UnsatisfiedLinkError`, verified). Do not "fix" by hacking the `.so`
  files or disabling hardware acceleration.
- 預期會見到嘅 logcat 噪音，並非 bug：預編 native 庫（`head_led`／
  `head_key_mgr`／`serial_port`、Vosk、JNA）嘅 `linker: ... unused DT
  entry` 警告，以及開機時偶爾嘅 `Atlas: ... not in
  getPreloadedDrawables?`。Android 5.1 linker 本來就不認識新式 GNU ELF
  項目（`DT_VERNEED`、`DT_GNU_HASH`），Atlas 那句來自框架圖形代碼——庫
  照常載入（無 `UnsatisfiedLinkError`，已驗證）。切勿為此改 `.so` 檔或關
  硬件加速。

License: GPL-3.0-only. / 授權：GPL-3.0-only。
