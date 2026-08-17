# OpenLynx

`com.open.lynx` —— 裝喺 UBTECH Lynx（QRobot）機械人本機嘅一個 Android
App。開機自動喺機械人度起一個 HTTP + WebSocket server（port `8888`），提供一份
網頁控制面板，喺同一個 WiFi 網絡任何裝置嘅瀏覽器就可以連過去，測試/操作機械人
Lynx AIDL SDK 暴露嘅幾乎全部功能。

## 支援嘅機械人 / 前提

呢個 App bundle 咗一個 AIDL SDK module，對應 Lynx 機身韌體：

| SDK module | 對應機身韌體 | HTTP 路由前綴 |
|---|---|---|
| `sdk-module/lynxrobot`（package `com.ubtechinc.lynxrobot`） | `alpha2services_base` 3.0.0.2 | `/api/lynx/...` |

Camera/音效測試/音量/鈴聲/WiFi 狀態/藍牙狀態/加速度計/咀部 LED 呢類純硬件功能唔經
呢個 AIDL SDK，直接用 Android 自身 API（見下面「檔案結構」），HTTP 路由冇任何前綴。

其餘前提：
- 機械人同你個瀏覽器裝置要喺同一個 WiFi 網絡。
- 機身跑 Android，App 本身 `targetSdkVersion 22`、`minSdkVersion 19`——冇用任何
  API 19 之後先出現嘅 method（見 `app/build.gradle` comment）。

Lynx 21 個 AIDL interface（`ServiceFetcher`/`LynxRobotApi` 用嗰套，
`com.ubtechinc.alpha.serverlibutil.aidl`）嘅完整方法清單同反編譯確認嘅韌體
行為（包括 `speech` service 喺 `alpha2services_base` 3.0.0.2 冇被登記、未接收
嘅 broadcast 等已知限制），見 `AIDL_GUIDE_LYNX.md`。

## Build 方法

呢個沙盒環境冇 Android SDK/aapt2/d8，冇辦法喺度直接砌出 `.apk`。要 build 嘅話，
喺你自己有 Android SDK 嘅機器（或者裝咗 Android Studio）跑：

```bash
cd open-lynx
./gradlew assembleDebug
```

輸出喺 `app/build/outputs/apk/debug/app-debug.apk`。

已經包含一條 `app/debug.keystore`（標準 debug key，密碼 `android`，alias
`androiddebugkey`），簽名用嘅，唔使你自己再生成一條。裝落機械人之前如果之前裝過
簽名唔同嘅舊版本，記得先解除安裝，否則 Android 會因為簽名唔匹配拒絕覆蓋安裝。

## 用法

1. 裝好 APK 開啟個 App——會自動起 server，機身螢幕會顯示個網址（如果 TLS 憑證
   起到就係 `https://<機械人IP>:8888/`，起唔到就 fallback 落 `http://`，見下面
   「HTTPS / 麥克風」一節）。撳個網址或者「複製」掣可以將個網址複製落剪貼簿，
   方便打去另一部裝置嘅瀏覽器。
2. 喺同一 WiFi 網絡任何裝置嘅瀏覽器開嗰個網址。
3. 控制面板分咗幾個分頁：

- **狀態**：SDK 版本、電量/版本查詢、PIR 開關同即時觸發指示、加速度計（唔屬於
  AIDL SDK，同一粒實體 IMU 讀數，即時 X/Y/Z 折線圖），仲有一個「4角度傾側著
  頭/眼LED」小功能
- **動作**：攞返機身內建動作列表、播放、停止
- **舵機**：`motor/*`（read/move_absolute/move_ref/set_all/power_save）
- **語音**：TTS（Android 系統引擎，Cantonese `yue` fallback）
- **LED**：頭部/眼睛/咀部/胸口/WiFi 燈（flash/breath/marquee 等效果）
- **相機**（純硬件，唔經 AIDL）：即時串流、拍照、錄影、可調解像度（320×240 到
  2064×1548）、一個自居中嘅拖曳搖桿（同鍵盤方向鍵）可以直接拖動控制頭部
  pan/tilt。**冇 walkie-talkie**（瀏覽器 mic → 機械人喇叭呢個方向嘅語音功能
  已經永久停用，見下面「已知限制」）

即時事件（PIR、聲納距離、電池等）全部經 WebSocket 即時推送到「即時事件 Log」，
控制面板唔使手動 refresh。

## 檔案結構

```
open-lynx/
├── AIDL_GUIDE_LYNX.md                  ← Lynx 21 個 AIDL interface 完整指南
├── sdk-module/
│   └── lynxrobot/                      ← Lynx/QRobot SDK（package com.ubtechinc.lynxrobot）
├── app/
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/open/lynx/
│       │   ├── MainActivity.java            — App 生命週期 + shared-hardware API 路由
│       │   ├── LynxController.java          — Lynx AIDL API 路由
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP，TLS 已移除，見下）
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast
│       │   ├── RobotWireConstants.java      — 機身底層 broadcast action/extra 常量
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── BootReceiver.java            — 開機自動啟動
│       │   └── MouthLedData.java            — 咀部 LED preset 資料
│       └── assets/web/
│           ├── index.html / style.css             ← 主控制面板 (HTML/CSS)
│           ├── app-core.js                        ← 主控制面板核心 (lynxApi()/
│           │                                          hwApi()、servo 校準表、I18N 字典 —
│           │                                          要第一個 load)
│           ├── app-status.js / app-camera.js /
│           │   app-mic.js / app-accel.js /
│           │   app-lynx.js                        ← 主控制面板各分頁邏輯
│           └── app-log.js                         ← WebSocket log + 頁面初始化
│                                                      (DOMContentLoaded — 要最後 load)
```

## HTTPS（未使用，純 plain HTTP）

呢個 App 淨係用 plain HTTP（`http://<機械人IP>:8888/`），開機畫面顯示嘅網址
永遠係 `http://` scheme。曾經試過自簽憑證 + TLS，但裝置上嘅瀏覽器對自簽憑證
持續 reject 新 TLS 連線（`SSLHandshakeException: certificate unknown`），
而支援 TLS 嘅唯一原因（一個需要安全來源 `getUserMedia()` 嘅功能）本身已經
唔存在，所以索性移除，冇再保留低任何 TLS 相關 code。

## 相機分頁操作方式（浮動掣 + Keyboard 控制）

相機分頁嘅操作方式：

- **功能鍵** checkbox：控制頭部搖桿（joystick pad）嘅顯示/隱藏。
- 拍照（📷）、錄影（⏺）兩粒浮動掣半透明浮喺 viewport 右下角，唔會擋住鏡頭畫面。

**Keyboard 支援**：撳一下相機畫面令佢攞到 focus（會見到藍色 focus outline +
右上角提示文字），之後**↑↓←→**可以控制頭部 servo 19（pan）/20（tilt），撳住
會用返同滑鼠拖拉一樣嘅 throttle/re-home 邏輯（放手自動返返去中心）；如果撳住
方向鍵期間 focus 意外跳走（例如撳咗 Tab），會自動清返所有狀態，唔會出現
「頭部卡住唔停」嘅情況。

## 中文/English 切換掣 JS error 修正 + 死 code 清理

> **2026-08 修正**：「語音」分頁撳「中文/English」切換掣（`setUiLanguage()`）
> 會拋 `ReferenceError: allActions is not defined`，令個切換掣完全失效。

**根源**：`app-core.js` 嘅 `setUiLanguage()` 入面有一段引用住 `allActions`、
`buildActionSubTabs()`、`renderActionList()` 嘅 if block——呢三樣嘢喺成個
project 都**從未定義過**。追查歷史改動，確認呢個係之前 Alpha2 → Lynx-only
嘅 refactor 遺留低嘅殘餘：舊版 Alpha2 UI 曾經有一套獨立嘅「全部動作」子分頁
邏輯（用 `allActions`/`buildActionSubTabs`/`renderActionList` 呢組名），
Lynx 版本已經有自己完整獨立嘅一套（`lynxAllActions`/
`buildLynxActionSubTabs()`/`lynxRenderActionList()`，喺同一個 function 下面
果幾行），但舊嗰組 Alpha2 專屬嘅 if block 冇跟住刪，變成一段引用緊三個唔存在
嘅名嘅死 code——平時撳個切換掣冇問題係因為 JS 會由頭至尾行晒成個 function
body，直到行到呢句先拋 `ReferenceError`（`allActions` 呢個名連宣告都冇，唔係
得個空值），中斷咗成個 `setUiLanguage()`，連累埋落面本來應該執行嘅
`buildLynxActionSubTabs()`/`lynxRenderActionList()` 都冇行到。

**修正**：刪走成段引用 `allActions`/`buildActionSubTabs`/`renderActionList`
嘅死 if block，`setUiLanguage()` 淨低 Lynx 版本嗰段（`typeof lynxAllActions
!== "undefined"` 個 guard 保留，因為呢個 function 有可能喺 `lynxAllActions`
都仲未載入嘅頁面初始階段被 call 到）。

**順帶做嘅死 code 清理**（同一輪一齊做，範圍見下面「已知限制」對應各項嘅
更新）：
- `app-camera.js`：`restoreBaseLed()`——完全冇任何 call site 嘅空殼 function
- `app-mic.js`：`playTestTone()`/`runAudioDiagnose()`——UI 冇任何按鈕綁住嘅
  audio/testtone、audio/diagnose 測試掣邏輯；`startTalkDisabled_unused()`、
  `stopTalk()`、`downsampleToInt16()`，同埋淨係俾呢幾個 function 用嘅全域
  變數/常量（`talkStream`/`talkAudioContext`/`talkProcessorNode`/
  `talkSourceNode`/`TALK_TARGET_SAMPLE_RATE`）——已永久停用嘅 walkie-talkie
  子系統嘅殘餘實作
- `app-camera.js`：綁住 `talkFab` 嘅 4 個 pointer event listener、keydown/
  keyup 入面嘅 Space 鍵分支、3 處已經永遠冧唔到嘅 `if (talkActive)
  stopTalk();` cleanup guard——全部都係已停用 walkie-talkie 嘅死觸發入口
  （呢個階段 walkie-talkie 仍然留低一個 `startTalk()` no-op guard 未刪，
  第二輪先連埋佢一齊清走，見下面）
- `AudioController.java`：成個檔案（356行）連同 `MainActivity.java` 入面
  嘅 instantiate/`shutdown()` 引用——冇任何 API endpoint 連住呢個 class,
  純粹係死 code

**驗證**：全部改動後重新用 `node --check` 驗證晒 7 個 JS 檔案語法、去除
comment/string 之後嘅大括號/括號平衡驗證晒全部 Java 檔案，兩者皆通過。仲用
一個自動化交叉比對腳本（top-level function/變數定義 vs 全 project 引用次數）
掃描咗成個 `assets/web` 目錄，確認清理完之後冇再殘留任何完全冇被引用嘅
function/變數。

### 第二輪：walkie-talkie 徹底清走（唔止死 code，成套功能都刪）

上面第一輪清理判斷得太保守——當時將 `AudioPlaybackController.java` 同佢嘅
`audio/testtone`/`audio/diagnose`/`audio/play/*`、`/upload/audio` 呢批
endpoint 當做「完整、獨立可用嘅 HTTP API surface」而保留低，理由係「可以
用 curl 直接 call」。但呢個理由站唔住腳：成個 project 冇任何文檔/客戶端
會咁樣用，一個 endpoint 冇任何呼叫者，就係死 code，唔係「保留咗嘅 API」。

`AudioPlaybackController` 本身淨係做一件事——播放瀏覽器 mic 錄音經機械人
喇叭放出，佢由頭到尾都係 walkie-talkie 呢個功能嘅後半段，唔係一個獨立、
通用嘅播放服務。**成個 project 冇 walkie-talkie 功能，呢個 class 同佢嘅
endpoint 就冇存在意義**，所以第二輪一次過清晒：

- `AudioPlaybackController.java` 成個檔案刪走
- `MainActivity.java`：`audio/testtone`/`audio/diagnose`/`audio/play/start`/
  `audio/play/stop` 四個 API case、`/upload/audio` 嘅 `handleUpload()`
  dispatcher、`releaseMicForAudioIo()` helper 全部刪走；`HttpServer`
  constructor 嘅 `RawUploadHandler` 參數改傳 `null`
- `LynxController.java`：`isSharedHardwarePath()` 入面殘留緊嘅
  `audio/testtone`/`audio/diagnose`/`audio/play/` 路由判斷——第一輪清理
  漏咗嘅一處，第二輪搜尋先搵到
- 前端 `app-mic.js`：連第一輪特登保留低嘅 `startTalk()` no-op guard 都刪埋
- 前端 `app-camera.js`：`talkFab` element lookup 刪走
- 前端 `app-log.js`：`disableTalkFabIfInsecureContext()` 刪走（`talkFab`
  已經唔存在，冇嘢可以 disable）
- 前端 `index.html`：`talkFab`（🎤）呢粒 UI 掣本身刪走

**驗證**：同第一輪一樣，重新跑晒 `node --check`（JS）、去除 comment/string
之後嘅大括號/括號平衡驗證（Java）、以及死引用交叉比對腳本，三者皆通過。

## 已知限制

- 冇伺服角度/電流回授。
- `action/list` 用咗一個最多等 5 秒嘅 blocking wait（AIDL callback 本質係 async），
  如果機器人服務初始化好慢，第一次攞列表可能會 timeout 返空列表——可以再按一次。
- 「聽機械人麥克風」（耳筒收聽環境聲）功能已經喺呢個修訂移除——原本呢個功能靠
  Alpha2 專屬嘅 `speech_SetMIC()` AIDL call 去釋放/收返機械人自己嘅 mic，Lynx
  AIDL SDK 冇對應方法，所以呢個功能喺 Lynx-only 版本冇得保留。對應嘅
  `AudioController.java` 呢個 class 檔案已經連埋 `MainActivity.java` 入面
  instantiate/`shutdown()` 嘅引用一齊刪走。
- **冇 walkie-talkie 功能**（瀏覽器 mic → 機械人喇叭）——成套實作已經徹底
  刪走，包括：
  - 前端：`app-mic.js` 已經冇任何錄音/播放/downsample 相關邏輯，成個檔案
    淨係得 `toggleCameraFullscreen()`；`app-camera.js`/`app-log.js` 冇再
    綁住任何咪掣 event listener；`index.html` 冇咪掣（🎤）呢粒 UI 元素。
  - 後端：`AudioPlaybackController.java`/`AudioController.java` 兩個檔案
    已刪；`audio/testtone`、`audio/diagnose`、`audio/play/start`、
    `audio/play/stop` 四個 API endpoint、`/upload/audio` handler 已刪；
    `HttpServer` constructor 嘅 `RawUploadHandler` 改傳 `null`。
  - 連帶支援呢個功能嘅 HTTPS/TLS（`SelfSignedCert.java`/`TlsSupport.java`）
    亦已經整套刪走（見上面「HTTPS」一節）——本身係為咗俾瀏覽器
    `getUserMedia()` 有安全來源而加，而家已經冇需要。
- 「語音」分頁之前有過一組「Mic 擁有權測試」實驗性掣（`speech/start_recording`/
  `speech/stop_recording`，對應 `ISpeechInterface.startRecording()`/
  `stopRecording()`），連同 `MainActivity.registerDynamicReceiver()` 用嚟收集
  `mic_broadcast_debug` payload 嘅 8 個試探性 broadcast filter（`ABOUT_TTS`、
  `ALPHA_SOCKET_ASR_OK`、`SPEECH_ANGLE_5MIC`、`LED_ACTION`、`IFLY_OFFLINE_CMD`、
  `NUANCE_OFFLINE_CMD`、`POWER_SAVE`、`ALPHA_NOTIFY_POWER`），純粹用嚟查
  「攞返 mic 會唔會有 broadcast 通知」呢條問題。現已整組移除（UI、
  `app-lynx.js`/`app-core.js` 對應 function/i18n、`LynxController.java` 嘅 API
  case、`MainActivity.java`/`RobotEventReceiver.java` 嘅 broadcast filter/case）。
  `LynxRobotApi.speech_startRecording()`/`speech_stopRecording()` 呢兩個 SDK
  method 本身冇改動，仍然存在於 `sdk-module/lynxrobot`，淨係呢個 App 自己加嘅
  測試 UI/API endpoint 被移除。
- 「語音」分頁嘅 TTS 播放**唔經 AIDL `speech` service**（見
  `AIDL_GUIDE_LYNX.md`「4. Speech」一節開頭嘅警告——呢部機嘅 `speech`
  service key 根本攞唔到 binder），而係用獨立嘅 Android 系統 TTS engine
  （`TextToSpeech` API）。呢部分幾個已喺真機驗證嘅得失：
  - 呢部機**冇 Google Play Store**，令 Google TTS 嘅
    `ACTION_CHECK_TTS_DATA`（`EXTRA_AVAILABLE_VOICES`）淨係答到出廠內建嗰
    一個國家變體（例如中文淨係 `zh-TW`，英文淨係 `en-US`），睇唔到實際已
    安裝嘅完整語言/聲線列表。**解法**：主要改用 `getVoices()`（API 21+，
    直接問 engine 自己嘅完整 voice metadata，唔受呢個限制），`ACTION_
    CHECK_TTS_DATA` 淨係做 fallback（畀冇 `getVoices()` 嘅 API 19/20 裝置，
    或者 `getVoices()` 本身都回空清單嗰陣用）。
  - 部分 TTS engine（**已確認 SVOX Pico** 屬於呢類）嘅
    `getAvailableLanguages()`/`isLanguageAvailable()` 唔可靠、會回空/唔完整
    嘅結果，但同一部機嘅 `ACTION_CHECK_TTS_DATA` 喺呢啲 engine 度反而用得，
    所以兩條路徑都保留（`getVoices()` 為主、`ACTION_CHECK_TTS_DATA` 為
    engine-specific fallback），唔可以淨係靠其中一條。
  - ISO 3166-1 alpha-3 → alpha-2 國家碼轉換**冧咗用
    `Locale.getAvailableLocales()` 反查**（呢個做法喺實機唔可靠，未必涵蓋
    晒 engine 報返嚟嘅每一個 alpha-3 碼），改用一個寫死嘅完整對照表
    （249 組 mapping）。
