# Open Alpha2

`com.open.alpha2` — an Android app that runs on the UBTECH Alpha2 robot
(`versionName "beta 5"` / `versionCode 5`) and turns it into a remotely
controllable device. On boot the app starts an HTTP + WebSocket server on port
`8888`; opening `http://<robot-ip>:8888/` from any browser on the same Wi-Fi
gives you the full control panel. A Blockly programming page lives at
`/blockly.html`, and the OpenAPI/AsyncAPI specs plus a Swagger UI are served
from the same port (see [HTTP API](#http-api--http-接口)).

`com.open.alpha2` —— 裝在 UBTECH Alpha2 機械人上的 Android 應用程式
（`versionName "beta 5"`／`versionCode 5`），讓機械人可以被遙距操控。
開機後會自動啟動 HTTP＋WebSocket 伺服器（port `8888`）；在同一 Wi-Fi
下的任何瀏覽器開啟 `http://<機械人IP>:8888/`，即可使用完整控制面板。
Blockly 編程頁面位於 `/blockly.html`；同一 port 亦提供 OpenAPI／AsyncAPI
spec 及 Swagger UI（見 [HTTP API](#http-api--http-接口)）。

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

Toolchain: Temurin JDK 11 + Gradle 7.0 (wrapper, SHA-256 pinned) + AGP
4.2.2 + Android SDK (`ANDROID_SDK_ROOT` pointing at your SDK;
`local.properties` is yours, never committed). Native side is pinned to NDK
`21.4.7075529` + CMake `3.10.2.4988404` so every machine builds the same
`libeasyopus.so`.

工具鏈：Temurin JDK 11＋Gradle 7.0（wrapper 已 pin SHA-256）＋AGP 4.2.2＋
Android SDK（`ANDROID_SDK_ROOT` 指向你的 SDK；`local.properties` 只屬本機，
切勿提交）。native 側 pin 死 NDK `21.4.7075529`＋CMake `3.10.2.4988404`，
確保任何機器編出嘅 `libeasyopus.so` 一致。

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
from `app/src/main/cpp` via CMake. Vosk + JNA AARs are vendored in
`app/libs` (armeabi-v7a only) so `--offline` builds need no Maven.

`app/debug.keystore` 是已提交的公開測試鑰匙（密碼 `android`）；如因簽名
不符無法覆蓋安裝，請先解除安裝。應用程式會在開機時自動啟動
（`BootReceiver`），無需觸碰機械人即可連上控制面板。CI
（`build-apk.yml`，JDK 11）會編出同一個 APK，並以
`open-alpha2-beta5.apk` 之名發佈。預編 `.so` 檔
（`head_led`／`head_key_mgr`／`serial_port`）放在
`sdk-module/hardware-direct/src/main/jniLibs`；`libeasyopus.so` 由
`app/src/main/cpp` 經 CMake 即時編譯。Vosk 同 JNA 的 AAR 已放喺
`app/libs`（只留 armeabi-v7a），`--offline` 編譯毋須連 Maven。

## Control panel / 控制面板 (`app/src/main/assets/web/`)

Bilingual (EN/繁中), live updates over WebSocket, no refresh needed:

中英雙語（EN／繁中），經 WebSocket 即時更新，無需重新整理：

- Status — language switch, media volume, system/device info (battery,
  Wi-Fi/Bluetooth, robot UUID, chest firmware), sonar chart, PIR sensor with
  optional alert (red LED + ringtone), accelerometer live chart and optional
  4-direction tilt → head/eye LED.
- 狀態——語言切換、媒體音量、系統／裝置資訊（電池、Wi-Fi／藍牙、機械人
  UUID、胸板韌體）、聲納圖表、PIR 感應器（可選警示：紅燈＋鈴聲）、加速度
  計即時曲線，以及可選的四方向傾側 → 頭／眼 LED。
- Actions — action list with category sub-tabs, play/stop, playback speed
  (0.5/0.67/1/1.5/2x). (Pack download lives in the Experiment tab.)
- 動作——動作清單及分類分頁、播放／停止、變速（0.5／0.67／1／1.5／2 倍）。
  （動作包下載在實驗分頁。）
- Servo — 20 joints individually or all at once, live command-pose display,
  single-servo and read-all live angle read-back (`servo/angle`,
  `servo/angle-all` / `servo/read-all`).
- 舵機——20 軸逐顆或全組控制、即時指令位姿顯示、經 `servo/angle` 讀回單顆
  舵機角度，另有 `servo/angle-all`／`servo/read-all` 一次過讀全部。
- Speech — conversation UI (type-to-test `speech/semantic_simulate`), Vosk
  offline ASR card (load/listen, model download in Experiment), TTS
  (engine/lang/voice pickers + optional auto-follow-Vosk).
- 語音——對話界面（可打字測試 `speech/semantic_simulate`）、Vosk 離線
  語音辨識卡（載入／聆聽；模型下載在實驗分頁）、TTS（引擎／語言／聲音
  選擇，可選自動跟 Vosk 語言包走）。
- Music — local music (`/mnt/internal_sd/music`, drag-and-drop upload,
  spectrum display), internet radio (radio-browser.info), Internet Archive
  online tracks (search in browser, play on robot), shared transport with
  optional dance-along random motions (“filler”) and music-reactive Disco
  LED (head VU / eye beat / mouth level).
- 音樂——本地音樂（`/mnt/internal_sd/music`，支援拖放上載、附頻譜顯示）、
  網絡電台（radio-browser.info）、Internet Archive 網上點歌（瀏覽器搜尋、
  機械人播放）、共用播放器，可選隨歌伴舞隨機動作（filler）及跟音樂節奏嘅
  Disco LED（頭部水位錶／眼拍子機／嘴跟音量呼吸）。
- LED — head/eye/mouth presets (long/flash/breathe/chase/dual, eye 3s
  countdown, mouth breathing speed).
- LED——頭／眼／嘴燈預設效果（長開／閃燈／呼吸／跑馬燈／雙色、眼 3 秒
  倒數、嘴呼吸速度）。
- Camera — MJPEG stream, preview snapshot, hi-res photo capture, digital
  zoom, pan-tilt joystick (drives head servos), shutter cue, mic-listen FAB
  (plays `/stream/mic` in the browser), fullscreen.
- 相機——MJPEG 串流、預覽快照、高清拍照、數碼變焦、雲台搖桿（帶動頭部
  舵機）、快門提示音、咪高峰收聽鍵（瀏覽器播放 `/stream/mic`）、全螢幕。
- Xiaozhi — AI voice-chat bridge: pairing code, connect/disconnect, mic
  state, text chat, TTS output choice (Xiaozhi opus vs Android TTS), custom
  OTA/server override, in-app MCP tool list with per-tool enable/disable,
  link to xiaozhi.me console.
- 小智——AI 語音對話橋接：配對碼、連接／斷線、咪高峰狀態、文字對話、語音
  輸出選擇（小智 opus／Android 內置）、自架 server／OTA 覆寫、內置 MCP
  工具清單（逐項開關）、小智控制台連結。
- Experiment (Advanced) — panel auth token, boot-voice mode (off /
  auto-connect Xiaozhi / auto-listen Vosk), resource downloads (Vosk model
  catalog, 202-action pack, Google TTS APK — one at a time via
  `DownloadGate`), 20ch servo angle tuner with offset backup/restore, robot
  ID changer (query/copy/QR preview/EEPROM write), chest firmware
  upgrade, version link. Event log is a permanent card below the tabs.
- 實驗（進階）——面板認證口令、開機語音模式（全部手動／開機連小智／開機
  用 Vosk）、資源下載（Vosk 模型目錄、202 招動作包、Google TTS APK——經
  `DownloadGate` 一次只准一樣）、20ch 舵機角度微調器（offset 備份／還原）、
  機械人 ID 變更器（查詢／複製／QR 預覽／寫入 EEPROM）、胸板韌體升級、
  版本連結。事件 Log 係分頁下方常駐卡片。
- Blockly (`blockly.html`, standalone page) — visual programming with 12
  toolbox categories: flow, events (accel/sonar/PIR), actions, speech/TTS/
  ringtones/local music, servos, LEDs, plus standard logic/math/text/
  variables/functions and ready-made examples; EN + 繁中 block messages.
- Blockly（`blockly.html`，獨立頁面）——可視化編程，12 個工具箱分類：控制
  流程、事件（加速度／聲納／PIR）、動作、語音／TTS／鈴聲／本地音樂、舵機、
  LED，另有標準邏輯／數學／文字／變數／自訂函式及現成範例；積木介面支援
  EN＋繁中。

## Voice interaction / 語音互動

- Offline speech recognition via Vosk (models live on the sdcard and are
  auto-detected; Vosk needs API 21+, so it stays off on older devices).
  Models can also be downloaded from the Experiment tab (official
  alphacephei.com allowlist, ~30–100 MB each, auto-unzipped).
- 離線語音辨識用 Vosk（模型放在 sdcard 並自動偵測；Vosk 需要 API 21＋，
  較舊裝置會自動停用）。模型亦可由實驗分頁下載（官方 alphacephei.com
  白名單，每個約 30–100 MB，自動解壓）。
- Spoken replies use the Android system TTS (`speech/tts`, `speech/stop`),
  with optional auto language follow of the loaded Vosk model.
- 回答語音使用 Android 系統 TTS（`speech/tts`、`speech/stop`），可選自動
  跟隨已載入 Vosk 模型嘅語言。
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
- Boot voice mode (Experiment tab): choose off / auto-connect Xiaozhi /
  auto-start Vosk listening on next app start — saving only, no immediate
  action.
- 開機語音模式（實驗分頁）：揀全部手動／開機自動連小智／開機自動啟用
  Vosk——儲存後下次開 App 先生效，唔會即刻郁。

## HTTP API / HTTP 接口

Single source of truth: `openapi/open-alpha2-openapi.yml` (**160 paths**,
one operation each). Namespaces as spelled in the spec:

單一真相源：`openapi/open-alpha2-openapi.yml`（**160 條路徑**，每條一個
operation）。spec 入面嘅命名空間：

- `/api/*` — main robot API (actions, `.ubx` playback, servos, speech, LEDs,
  audio, camera, battery, Wi-Fi/BT, sonar/PIR, chest, Vosk, APK/action-pack
  download, misc). The browser client calls these under the
  **`/api/alpha2/*`** prefix (`api()` in `app-core.js`); both spellings
  reach the same dispatcher (`handleApi`), and the bare `/api/*` form is
  also what the OpenAPI file documents.
- `/api/*`——主機器 API（動作、`.ubx` 播放、舵機、語音、LED、音頻、相機、
  電池、Wi-Fi／藍牙、聲納／PIR、胸板、Vosk、APK／動作包下載、雜項）。
  瀏覽器客戶端經 **`/api/alpha2/*`** 前綴呼叫（`app-core.js` 嘅 `api()`）；
  兩種寫法都會去到同一個 dispatcher（`handleApi`），而 OpenAPI file
  文件化嘅正係冇 `alpha2/` 嗰種。
- `/api/system/*` — discovery, music player, panel auth (`system/auth/*`).
- `/api/system/*`——裝置探索、音樂播放器、面板認證（`system/auth/*`）。
- `/api/direct/*` — low-level passthrough (ubx/servo/LED/sonar).
- `/api/direct/*`——底層直調（ubx／舵機／LED／聲納）。
- `/api/xiaozhi/*` — AI bridge control, OTA/MCP/TTS config, boot voice.
- `/api/xiaozhi/*`——AI 橋接控制、OTA／MCP／TTS 設定、開機語音模式。
- `/upload/*` — raw binary uploads: walkie-talkie PCM (`audio`), music,
  chest firmware.
- `/upload/*`——原始二進制上載：走廊對講 PCM（`audio`）、音樂、胸板韌體。
- `/stream/camera` (MJPEG), `/stream/mic` (WAV chunks), `/ws` (RFC 6455
  event feed, described by `openapi/asyncapi.yml`), `/` and
  `/blockly.html`.
- `/stream/camera`（MJPEG）、`/stream/mic`（WAV 分片）、`/ws`（RFC 6455
  事件推送，見 `openapi/asyncapi.yml`）、`/` 及 `/blockly.html`。

Self-describing endpoints on the robot (same port):

機械人上嘅自我描述端點（同一 port）：

- `/docs` — Swagger UI (CDN; falls back to raw YAML offline).
- `/docs`——Swagger UI（走 CDN；離線時回退顯示原始 YAML）。
- `/openapi.yml`, `/asyncapi.yml` — specs mirrored from `openapi/`.
- `/openapi.yml`、`/asyncapi.yml`——spec 副本（同步自 `openapi/`）。
- `/.well-known/openapi.json` (+ `.yml`, and non-dot `well-known/` fallback
  because aapt strips dotfiles) — machine-readable discovery.
- `/.well-known/openapi.json`（另有 `.yml`，以及冇點版 `well-known/`
  fallback——aapt 會剝走點開頭檔名）——機器可讀嘅 API 發現。
- `/apis.yml` — apis.json-style directory entry; `/llms.txt` — short LLM
  pointer.
- `/apis.yml`——apis.json 風格目錄條目；`/llms.txt`——畀 LLM 嘅簡短指引。

The JS client (`assets/web/api-client.js`, `Alpha2Api.*`, **152 typed
wrappers**) is generated from the spec — never hand-edit it:

JS 客戶端（`assets/web/api-client.js`，`Alpha2Api.*`，**152 個 typed
wrapper**）由 spec 自動生成——切勿手改：

```bash
python scripts/generate-api-client.py
python scripts/generate-api-client.py --check   # CI drift gate
```

`scripts/check-openapi-drift.py` keeps code and spec aligned;
`scripts/check-api-client-routes.py` verifies each wrapper’s caller prefix
matches the backend route; `scripts/check-spec-copies.py` keeps the web
mirrors in sync. The MCP tool table (`McpToolsGenerated.java`, 22 tools) is
generated from `openapi/mcp-openapi-sync.yml` via
`scripts/generate-mcp-tools.py`.

`scripts/check-openapi-drift.py` 負責代碼與 spec 對齊；
`scripts/check-api-client-routes.py` 確認每個 wrapper 用嘅 caller 前綴同
後端路由一致；`scripts/check-spec-copies.py` 確保網頁 spec 副本同步。
MCP 工具表（`McpToolsGenerated.java`，22 個工具）由
`openapi/mcp-openapi-sync.yml` 經 `scripts/generate-mcp-tools.py` 生成。

## Xiaozhi AI bridge + MCP / 小智 AI 橋接＋MCP

The app can connect the robot to a Xiaozhi AI backend for open-ended voice
chat (pairing code, connect/disconnect, mic start/stop, typed `send_text`,
custom OTA/WebSocket server override, TTS output as Xiaozhi opus or local
Android TTS). Over MCP it exposes 22 tools the model can call:
`self.robot.*` (list/play/stop/random actions, single/all servos,
head/eye/mouth LEDs, speak), `self.sensors.*` (PIR/sonar get + configure),
`self.camera.*` (take photo, image Q&A) and `self.media.*` (local
music/radio search-play-stop). Per-tool enable/disable is in the Xiaozhi
tab; boot auto-connect is the Experiment tab’s boot-voice mode.

應用程式可將機械人接上小智 AI 後端做開放式語音對話（配對碼、連接／斷線、
咪高峰開／關、手動 `send_text`、自架 OTA／WebSocket server 覆寫、語音輸出
揀小智 opus 或本地 Android TTS）。經 MCP 向模型開放 22 個工具：
`self.robot.*`（列出／播放／停止／隨機動作、單顆／全組舵機、頭／眼／嘴燈、
說話）、`self.sensors.*`（PIR／聲納讀取及設定）、`self.camera.*`（拍照、
看圖問答）及 `self.media.*`（本地音樂／電台搜尋－播放－停止）。逐項
enable/disable 喺小智分頁；開機自動連線係實驗分頁嘅開機語音模式。

## On-device data / 機身數據

- Actions: `/sdcard/actions/` — `<id>.ubx` files plus per-action music and
  `actionInfo.txt`. The optional pack download (202 actions, from the
  project’s GitHub release `actions.zip`) replaces this tree (old one
  renamed to `/sdcard/actions-backup`).
- 動作：`/sdcard/actions/`——`<id>.ubx` 檔、外加每個動作的配樂及
  `actionInfo.txt`。可選動作包下載（202 招，來自專案 GitHub release 嘅
  `actions.zip`）會整棵替換（舊樹改名 `/sdcard/actions-backup`）。
- Local music: `/mnt/internal_sd/music` (mp3/wav/ogg/m4a/flac, filename
  order = track order; voice next/previous wraps around; also uploadable
  from the Music tab via drag-and-drop).
- 本地音樂：`/mnt/internal_sd/music`（mp3／wav／ogg／m4a／flac，檔名排序
  即曲目順序；語音上／下一首會循環；音樂分頁亦可拖放上載）。
- Photos: `/sdcard/DCIM/Alpha2/`.
- 照片：`/sdcard/DCIM/Alpha2/`。
- Vosk models: auto-detected on the sdcard top level (not bundled;
  download from the Experiment tab, ~30–100 MB each).
- Vosk 模型：在 sdcard 頂層自動偵測（不隨 App 附送；實驗分頁可下載，
  每個約 30–100 MB）。

## Project layout / 項目結構

```
open-alpha2/
├── app/                            ← com.open.alpha2 (UI server, API, all centers)
│   ├── src/main/java/com/open/alpha2/  ← MainActivity wiring + centers
│   │   (ActionDirect, ApiDispatcher, ApiValidator, AudioCenter, CameraApi,
│   │    DeviceStatus, GestureCenter, GrammarCenter, LedCenter, MicCenter,
│   │    PanelAuth, RingtoneCenter, SemanticCenter + 10 matchers,
│   │    SpeechCenter, TtsCenter, VoskController/Api, XiaozhiBridge/Client,
│   │    ActionsPackController, ApkDownloadController, DownloadGate, …)
│   ├── src/main/assets/web/        ← control panel + Blockly + generated api-client.js
│   ├── src/main/assets/semantic/   ← 10 intent libraries + action category pools
│   ├── src/main/cpp/               ← easyopus JNI (CMake)
│   ├── src/test/java/…             ← ApiValidatorTest (run via scripts/test-apivalidator.py)
│   ├── libs/                       ← vendored vosk-android + JNA AARs (v7a)
│   └── build.gradle                ← versionName "beta 5", armeabi-v7a only
├── sdk-module/hardware-direct/     ← serial ports, LED/pad JNI, wire protocol,
│                                      .ubx parse/play/speed-shifted music
│   └── src/test/java/…             ← SerialFrameCodecTest (scripts/test-serialcodec.py)
├── openapi/                        ← open-alpha2-openapi.yml / asyncapi.yml /
│                                      mcp-openapi-sync.yml + README
├── scripts/                        ← generators, drift/route/copy checks, unit-test
│                                      runners, ubx_* offline analysis tools
├── docs/legacy-beta3/              ← archaeology only (AIDL / iFlytek era)
└── .github/workflows/              ← build-apk.yml, check-openapi.yml
```

## CI (runs on push) / 持續整合（push 即跑）

- `build-apk`: JDK 11 → strip web comments → `assembleDebug` → upload APK
  (`open-alpha2-beta5.apk`, 30-day retention).
- `build-apk`：JDK 11 → 剝離網頁註解 → `assembleDebug` → 上載 APK
  （`open-alpha2-beta5.apk`，保留 30 日）。
- `check-openapi`: spec/code drift, client freshness, client→route prefix
  match, MCP freshness, YAML validity, `ApiValidator`/serial-codec unit
  tests, web spec copies in sync, `versionName` == spec `info.version`.
- `check-openapi`：spec／代碼漂移、客戶端新鮮度、客戶端→路由前綴一致、
  MCP 新鮮度、YAML 合法性、`ApiValidator`／串口編解碼單元測試、網頁 spec
  副本同步、`versionName` == spec `info.version`。

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
  live angle verification via `servo/angle` / `servo/angle-all`.
- 沒有舵機電流回授；舵機分頁顯示指令位姿，另有 `servo/angle`／
  `servo/angle-all` 做即時角度驗證。
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
