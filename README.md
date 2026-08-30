# Open Alpha2

`com.open.alpha2` —— 裝在 UBTECH Alpha2 機械人本機的一個 Android App，取代原廠介面。
開機自動起一個 HTTP + WebSocket server（port `8888`），提供一份網頁控制面板，在同一個
WiFi 網路任何裝置的瀏覽器就可以連過去操作機械人；另外還有一個獨立的 Blockly 積木編程頁
可以組程式給機械人跑，和一個 XiaoZhi（小智）AI 語音對話橋接（含 MCP 工具，讓遠端 LLM
直接操作機械人）。

目前版本：**beta 3**。

## 支援的機械人 / 前提

這個 App 用的是 `sdk-module/ubtechalpha2robot`（package `com.ubtechinc.alpha2robot`）
這個 AIDL SDK module，對應機身系統程序 `com.ubtechinc.alpha2services` v1.1.7.3.20
這一代韌體。

其餘前提：
- 機械人和你的瀏覽器裝置要在同一個 WiFi 網路。
- 機身跑 Android 5.1（API 22），App 本身 `targetSdkVersion 22`、`minSdkVersion 19`、
  `compileSdkVersion 25`，`armeabi-v7a` 單一 ABI。
- Server 用純 HTTP（`http://<機械人IP>:8888/`）——瀏覽器端「說話」（用瀏覽器麥克風向
  機械人發聲）功能因為 HTTPS/自簽憑證方案失敗已經永久停用，詳見下面「已知限制」。

`com.ubtechinc.alpha2serverlib.aidlinterface` 底下 17 個 AIDL interface（+1 個自訂
Parcelable `ASRRecord`）的完整方法清單、transaction id、參數語意、`sendCommand`
序列埠 command byte 表，見 `AIDL_REFERENCE_ALPHA2.md`（專門對應 v1.1.7.3.20，已對照
反編譯的 `Stub.onTransact()` 驗證）。

## Build 方法

要在你自己有 Android SDK 的機器（或者裝了 Android Studio）跑：

```bash
cd open-alpha2
./gradlew assembleDebug
```

輸出在 `app/build/outputs/apk/debug/app-debug.apk`。

已經包含一條 `app/debug.keystore`（標準 debug key，密碼 `android`，alias
`androiddebugkey`），簽名用的，不用你自己再生成一條。裝到機械人之前如果之前裝過
簽名不同的舊版本，記得先解除安裝，否則 Android 會因為簽名不匹配拒絕覆蓋安裝。

Opus 音頻編解碼（小智語音對話用）在 `app/src/main/cpp` 用 CMake 自行編譯
`libeasyopus.so`，link 一份 prebuilt 的 `armeabi-v7a/libopus.so`，Java 側對應
`com.theeasiestway.opus.Opus/Constants` 是純 Java 檔案，build 不需要任何 `.aar`。

## 用法

1. 裝好 APK 開啟 App——會自動起 server，機身螢幕會顯示網址
   `http://<機械人IP>:8888/`。按網址或者「複製」鍵可以將網址複製到剪貼簿。
2. 在同一 WiFi 網路任何裝置的瀏覽器開啟那個網址，可切換中/英文介面。
3. 控制面板分成以下分頁：

- **📊 狀態**：語言切換、媒體音量（實體 +/- 鍵共用）、系統狀態 JSON dump、電池
  /WiFi/藍牙/機械人 UUID 查詢、聲納開關與距離門檻拉桿＋即時距離圖表、PIR（人體
  紅外線）感應器開關和獨立的「警示反應（LED+鈴聲）」開關（觸發時頭/眼 LED 長亮 +
  嘴部呼吸燈 + 鈴聲，真機已確認觸發正常）、加速度計即時開關 + X/Y/Z 折線圖 + 一個
  「4 角度傾側著頭/眼 LED」小功能
- **🕺 動作**：取回機身內建動作列表（依分類/子分類分頁顯示）、輸入動作名稱播放、停止
- **⚙️ 舵機**：20 顆伺服逐一/一起拖曳控制（含頭部 pan/tilt）、一鍵全部回到原位、
  省電模式開關
- **🗣️ 語音**：
  - 💬 對話界面——聊天式 UI，打字直接送去內建 1000+ 條中英文問答語料庫做語意配對
    （命中即時 TTS + 動作回應），不用開聲也能測完整「聽到→反應」流程；即時顯示
    目前 iFlytek 模式（離線文法／雲端聽寫）
  - ASR 引擎切換（iFlytek 中文 / Nuance 英文）
  - 📴 離線文法辨識——機身內建 iFlytek BNF 語法資源，完全不用上網做中文指令辨識，
    可自動跟網路狀態切換，也可手動編輯/初始化/開始/停止
  - TTS 引擎（Nuance／iFlytek／Android 系統）與聲線切換（Nuance 支援南南/小峯/小欣/
    catherine/john，Android 系統 TTS 顯示機身已裝的所有引擎與語言）
  - 🎙️ MIC 控制——搶 MIC/還 MIC，和一個「持續搶 MIC」開關
  - ⚙️ 離線對話設定——中/英文喚醒詞 preset 一鍵寫入（含實驗性 Open Alpha2 專用
    離線喚醒 preset）、一鍵重開機
- **🎵 音樂**：本地曲庫（機身 `/mnt/internal_sd/music/`，支援拖放上載）與網絡電台
  （radio-browser.info 搜尋）共用同一套播放器——即時頻譜圖、進度拉桿、播放控制、
  系統音量、等化器（EQ，含已存 preset）、一個播歌/播電台期間機械人自動不定時做
  安全隨機動作的開關（用「隨機短/長」動作池，不會用到帶內建音效的 `DANCE_ANY`）
- **💡 LED**：頭部/眼睛（顏色、亮度 1–9、長開/閃燈/呼吸燈/跑馬燈/雙色燈/停止幾個
  preset）、嘴部（呼吸燈速度 0–5000）
- **📷 相機**：即時串流、拍照（最高 4208×3120）、錄影、可調解析度（640×480 到
  1920×1080，含固定 2064×1548）、雙擊全螢幕、拖曳十字準星直接控制頭部 pan/tilt、
  「聽機械人」（耳機收聲）
- **🤖 小智（XiaoZhi）**：連接官方 xiaozhi.me（或自架相容 server）的 AI 語音助理
  服務，開關開＝配對／連線／隨時語音對話，關＝斷開；首次要在 xiaozhi.me 輸入機械人
  讀出的配對碼；也可打字對話；小智回覆的語音輸出可選擇原生（小智 server 送 opus
  音頻）或改用機身/系統 TTS（iFlytek／Nuance／Android）逐句讀出；一個總停鍵（停
  動作播放/TTS/本地音樂/電台）；一個內建 MCP 工具清單 card（見下面「MCP 工具」
  一節，逐項開關）；一個自訂 server 設定 card（覆寫 OTA/WebSocket/MAC/Token，
  只有 OTA 位址是必填）；一個 xiaozhi.me 控制台快速連結
- **🧪 實驗**（預設收合，開關展開，高風險操作）：
  - Servo Angle Tuner——獨立於舵機分頁的 20 通道 ±1° 精調器，跟原廠校準工具佈局
    一致，可一鍵讀取全部目前角度、備份/還原 offset
  - 機械人 ID 變更器——查詢/複製 UUID、離線生成 QR code、輸入新 ID 即時預覽、
    寫入 chest EEPROM（危險操作）
  - 胸板固件——查詢目前版本、上傳 256KB `ALPHA2Q-CHEST-*.bin`、開始/中止/恢復升級
    並顯示進度
- **🧩 積木編程**（`blockly.html`，在新分頁開啟）：Blockly 視覺化編程介面。分成
  流程/事件/動作/語音/伺服/LED 幾個自訂分類，另加 Blockly 標準的
  邏輯/數學/文字/變數/自訂函式，和一個內建範例分類；支援中英雙語即時切換、
  程式命名儲存/載入/刪除、匯出入 `.xml`。事件驅動用加速度計門檻／聲納觸發的
  hat block。

即時事件（頭部觸摸、手勢、ASR 結果/意圖、電池、跌落方向、聲源角度、喚醒詞、
PIR、藍牙連線、WiFi 查詢結果等）全部經 WebSocket 即時推送到底部常駐的
「即時事件 Log」，控制面板不用手動 refresh。

### MCP 工具（給小智用）

這台機經 XiaoZhi 協議的 MCP 橋接暴露以下工具給遠端 LLM 直接操作機械人（在
「小智」分頁的「內置MCP功能列表」card 展開可以看到全部工具和逐項開關；和
xiaozhi.me console 側「MCP接入點」是完全不同的概念）：

- `self.robot.list_actions` / `play_action`（中英文名 fuzzy match）/
  `stop_action` / `play_random_action`
- `self.robot.servo_set_one` / `servo_set_all`
- `self.robot.led_set_head` / `led_set_eye` / `led_set_mouth`
- `self.sensors.get_pir` / `set_pir_enabled`
- `self.sensors.get_sonar` / `set_sonar_threshold`
- `self.camera.take_photo` / `image_to_text`（拍照後再向 XiaoZhi 視覺服務要一次
  文字描述，走同一張照片的 `vision/explain` 端點）
- `self.robot.speak`（TTS，固定用 Nuance／`en_us`）
- `self.media.list_music` / `play_music` / `stop_music`
- `self.media.list_radio` / `search_radio` / `play_radio` / `stop_radio`

這批工具也可以透過對應的 `/api/audio/local_music/*`、`/api/audio/radio/*`
等 HTTP 端點直接呼叫，但控制面板本身沒有為這幾個做獨立於「音樂」分頁之外的
UI（純粹給小智用，或者自己手動 call API）。

## 檔案結構

```
open-alpha2/
├── AIDL_REFERENCE_ALPHA2.md                   ← Alpha2 17 個 AIDL interface 完整參考
├── iFlytek_Complete_Workflow_and_Offline_Architecture.md
│                                               ← iFlytek 離線/線上工作流程與架構說明
├── sdk-module/
│   └── ubtechalpha2robot/              ← Alpha2 SDK（package com.ubtechinc.alpha2robot）
├── app/
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                            — Opus native 編解碼（CMake, prebuilt libopus.so）
│       ├── java/com/open/alpha2/
│       │   ├── MainActivity.java            — App 生命週期 + Alpha2 API 路由 + MCP tool 定義
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP，PORT 8888）
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket（給控制面板即時事件用）
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast（含 PIR / chest / 心口鍵）
│       │   ├── AudioController.java         — 耳機錄音（「聽機械人」功能）
│       │   ├── AudioPlaybackController.java — 播放器（TTS/local music/radio 音頻播放）
│       │   ├── MusicController.java         — 本地曲庫/電台播放邏輯、EQ、隨機動作
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── IflytekSemanticMatcher.java  — 中文語意配對引擎（對話界面用）
│       │   ├── IflytekSemanticMatcherEn.java— 英文語意配對引擎
│       │   ├── SimplifiedToTraditional.java — 簡轉繁工具
│       │   ├── XiaozhiClient.java           — 小智 WebSocket client（連 xiaozhi.me，STT/LLM/TTS/MCP/system 訊息）
│       │   ├── XiaozhiAudioController.java  — 小智語音對話的 mic 錄音/Opus 編碼、解碼/播放
│       │   ├── XiaozhiOtaClient.java        — 小智 OTA 配對/啟用流程
│       │   ├── XiaozhiActivationStatus.java — 小智配對狀態資料類
│       │   ├── XiaozhiTrustAllSsl.java      — 小智連線用的 SSL 信任設定
│       │   ├── BootReceiver.java            — 開機自動啟動 MainActivity
│       │   └── MouthLedData.java            — 嘴部 LED preset 資料
│       ├── java/com/theeasiestway/opus/     — Opus JNI 純 Java 包裝（配合 cpp/ 的 native lib）
│       ├── java/com/ubtechinc/mic5/         — 頭部 5-mic LED JNI 包裝
│       ├── jniLibs/armeabi-v7a/libhead_led.so
│       └── assets/
│           ├── iflytek/                            ← 離線語音辨識資源
│           │   ├── iflytek_semantic_zh.json        — 中文語意庫（922 條，ACTION/FUNCTION/CHAT）
│           │   ├── iflytek_semantic_en.json        — 英文語意庫
│           │   ├── action_category_pools.json      — 動作分類隨機池定義
│           │   ├── default_grammar.bnf             — 預設離線 BNF 文法
│           │   └── yuliao_call.bnf
│           └── web/
│               ├── index.html / style.css              ← 主控制面板 (HTML/CSS)
│               ├── app-core.js                         ← 核心 (api()/hwApi()、
│               │                                           servo 校準表、I18N 字典 — 要第一個 load)
│               ├── app-status.js / app-actions.js /
│               │   app-speech.js / app-servo.js /
│               │   app-led.js / app-camera.js /
│               │   app-xiaozhi.js / app-music.js /
│               │   app-radio.js / app-mic.js /
│               │   app-accel.js / app-qr.js /
│               │   app-chest.js                        ← 主控制面板各分頁邏輯
│               ├── app-log.js                          ← WebSocket log + 頁面初始化（最後 load）
│               └── blockly*.{html,js,css}               ← 積木編程頁（獨立於主面板）
```

## 已知限制

- **HTTPS / 瀏覽器麥克風（walkie-talkie 說話功能）已永久停用**：之前試過用手寫
  自簽憑證起 HTTPS 去解鎖瀏覽器 `getUserMedia()`（因為瀏覽器規範限定這個
  API 只在 secure context 才出現），但實測發現裝置的瀏覽器會不斷拒絕自簽
  憑證觸發大量 `SSLHandshakeException`，最終決定連 TLS 一起移除，改回純
  HTTP-only。控制面板「說話」這個功能（用瀏覽器麥克風直接向機械人發聲）已經
  無條件停用，UI 鍵仍在但完全沒反應。「聽機械人」（耳機收聲，方向相反）不受
  影響，正常運作。
- **ASR 固定用 iFlytek**：中英文辨識都走 iFlytek engine，不再用 Nuance 做 ASR。
  Nuance 雲端補完伺服器（`ubtech-mix-engusa-ssl.nuancemobility.net`）已永久
  離線，local confidence 不夠雲端確認的結果會被直接丟棄。Nuance 仍然是可選的
  TTS 引擎之一（和 iFlytek、Android 系統 TTS 並列），不受此限制影響。
- `startSpeechNoWakeup()` 在這個韌體版本（v1.1.7.3.20）已確認是個 stub
  （回傳 `API_ERROR_SUCCEED` 但機身內部零動靜），真正能觸發辨識的路徑是
  `speech/inject`（對應 `speech_startRecognized()`）。
- 沒有伺服角度/電流回授（「實驗」分頁的 Servo Angle Tuner 只能寫入，不能讀回
  真實角度，靠一鍵「讀取全部」查詢目前設定值）。
- 「實驗」分頁三張 card（Servo Tuner、機械人 ID 變更器、胸板固件）都是高風險
  操作（直接寫伺服角度/chest EEPROM/胸板韌體），預設收合，需要手動展開開關
  才會顯示內容，每次重新整頁都會回到收合狀態。
- PIR 感應器和警示反應（LED+鈴聲）已在真機確認正常觸發；但獨立的心口 mute
  鍵、和聲納 obstacle 觸發，各自嘗試直接觸發 5-mic 頭/眼 LED 那段，在這台機
  頭板上一直撞到 `API_ERROR_FAILED`——PIR 警示反應改用「頭/眼 LED 長亮 +
  嘴部呼吸燈 + 鈴聲」的組合繞開這個限制，不代表 LED 分頁本身的 preset 有問題
  （LED 分頁本身的所有 preset 已在真機確認生效）。
- `action/list` 用了一個最多等 5 秒的 blocking wait（AIDL callback 本質是
  async），如果機器人服務初始化很慢，第一次取列表可能會 timeout 回空
  列表——可以再按一次。
- `self.camera.image_to_text` MCP 工具的請求格式（重用同一張照片的 uuid 再次
  呼叫 `vision/explain`）是根據 XiaoZhi server 文字說明推測出來的實作，並非
  官方文件記載的格式，如果格式猜錯，log 會完整記錄原始 response 方便排查。
