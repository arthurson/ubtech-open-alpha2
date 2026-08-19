# Open Alpha2

`com.open.alpha2` —— 裝喺 UBTECH Alpha2 機械人本機嘅一個 Android
App。開機自動起一個 HTTP + WebSocket server（port `8888`），提供一份網頁控制
面板，喺同一個 WiFi 網絡任何裝置嘅瀏覽器就可以連過去操作機械人；另外仲有一個
獨立嘅 Blockly 積木編程頁可以砌程式俾機械人行，同一個 XiaoZhi（小智）AI
語音對話橋接。

## 支援嘅機械人 / 前提

呢個 App 用嘅係 `sdk-module/ubtechalpha2robot`（package `com.ubtechinc.alpha2robot`）
呢個 AIDL SDK module，對應 `alpha2services` v1.1.7.3.20 呢代機身韌體。

其餘前提：
- 機械人同你個瀏覽器裝置要喺同一個 WiFi 網絡。
- 機身跑 Android 5.1.1（API 22），App 本身 `targetSdkVersion 22`、
  `minSdkVersion 19`，`armeabi-v7a` 單一 ABI。
- Server 用純 HTTP（`http://<機械人IP>:8888/`）——之前試過嘅自簽憑證 HTTPS
  方案已經完全移除（見下面「已知限制」）。

17 個 AIDL interface（連埋原本 SDK 缺失、事後補返嘅 `disableActionPlay`、
`isActioning`、`IAlpha2BlueToothSerialPortService`）嘅完整方法清單、
transaction id、參數語意，見 `AIDL_REFERENCE_ALPHA2.md`。

## Build 方法

呢個沙盒環境冇 Android SDK/aapt2/d8，冇辦法喺度直接砌出 `.apk`。要 build 嘅話，
喺你自己有 Android SDK 嘅機器（或者裝咗 Android Studio）跑：

```bash
cd open-alpha2
./gradlew assembleDebug
```

輸出喺 `app/build/outputs/apk/debug/app-debug.apk`。

已經包含一條 `app/debug.keystore`（標準 debug key，密碼 `android`，alias
`androiddebugkey`），簽名用嘅，唔使你自己再生成一條。裝落機械人之前如果之前裝過
簽名唔同嘅舊版本，記得先解除安裝，否則 Android 會因為簽名唔匹配拒絕覆蓋安裝。

Opus 音頻編解碼（小智語音對話用）而家係喺 `app/src/main/cpp` 用 CMake
自行編譯 `libeasyopus.so`，link 一份 prebuilt 嘅 `armeabi-v7a/libopus.so`
（1.5.x 系，帶 DRED），Java 側對應 `com.theeasiestway.opus.Opus/Constants`
係純 Java 檔案——build 唔再需要任何 `.aar`。

## 用法

1. 裝好 APK 開啟個 App——會自動起 server，機身螢幕會顯示網址
   `http://<機械人IP>:8888/`。撳個網址或者「複製」掣可以將個網址複製落剪貼簿，
   方便打去另一部裝置嘅瀏覽器。
2. 喺同一 WiFi 網絡任何裝置嘅瀏覽器開嗰個網址。
3. 控制面板分咗幾個分頁：

- **狀態**：媒體音量、SDK 版本/系統狀態 JSON dump、電池/WiFi/藍牙/UUID
  查詢、充電同時播放開關、最近手勢辨識、頭部降噪、聲納開關同即時距離圖表、
  PIR（人體紅外線）感應器開關同警示反應（觸發時眼/頭 5-mic LED 長著紅燈 +
  播鈴聲，真機已確認）、加速度計即時 X/Y/Z 折線圖同一個「4角度傾側著頭/眼
  LED」小功能
- **動作**：攞返機身內建動作列表（分子分類）、播放、停止
- **舵機**：20 顆伺服逐一/一齊拖曳控制（含頭部 pan/tilt）、省電模式
- **語音**：ASR 引擎切換（中文 iFlytek / 英文 Nuance）、辨識結果同意圖分類
  即時顯示、一個「語音輸入三合一測試」card（同一句輸入同時試模擬講嘢／語法式
  辨識／文字語意理解三條唔同 AIDL 路徑，方便逐一對比邊個有反應）、TTS（多引擎/
  聲線）、麥克風擁有權釋放/交回（含「持續搶 Mic」開關）、機身 wake word
  語言 preset（中/英，寫入後要重開機）同一鍵重開機掣
- **LED**：頭部/眼睛（顏色、亮度、長開/閃燈/呼吸燈/跑馬燈/雙色燈幾個 preset）、
  咀部（呼吸燈速度）
- **相機**：即時串流、拍照、錄影、可調解像度（320×240 到 2064×1548）、
  「聽機械人」（耳筒收聲）、一個自居中嘅拖曳搖桿（同鍵盤方向鍵）可以直接拖動
  控制頭部 pan/tilt
- **小智（XiaoZhi）**：連接官方 xiaozhi.me（或者自架相容 server）嘅 AI 語音
  助理服務，開關開＝配對／連線／隨時語音對話，關＝斷開；首次要喺
  xiaozhi.me 輸入機械人讀出嘅配對碼；亦可打字對話；有一個內置 MCP 工具清單
  card（見下面「MCP 工具」一節），同一個自訂 server 設定 card（覆寫
  OTA/WebSocket/MAC/Token）
- **進階**（raw AIDL passthrough，未經真機完整驗證嘅方法）：用檔案播放動作、
  停用/啟用動作播放、查詢是否播放緊、觸發自訂事件、註冊英文理解（線上/
  離線）、註冊 ASR 歷史回放、序列埠（心口/頭部）原始資料收發、攞機身序號、
  藍牙序列埠命令/AT 指令
- **積木編程**（`blockly.html`，喺新分頁開）：Blockly 視覺化編程介面。分咗
  流程/事件/動作/語音/伺服/LED 幾個自訂分類，另加 Blockly 標準嘅
  邏輯/數學/文字/變數/自訂函式，同一個內建範例分類；支援中英雙語即時切換、
  程式命名儲存/載入/刪除、匯出入 `.xml`。事件驅動用加速度計門檻／聲納觸發嘅
  hat block。

即時事件（頭部觸摸、手勢、ASR 結果/意圖、電池、跌落方向、聲源角度、喚醒詞、
PIR、藍牙連線、WiFi 查詢結果等）全部經 WebSocket 即時推送到「即時事件 Log」，
控制面板唔使手動 refresh。

### MCP 工具（俾小智用）

呢部機經 XiaoZhi 協議嘅 MCP 橋接暴露以下工具俾遠端 LLM 直接操作機械人（喺
「小智」分頁嘅「內置MCP功能列表」card 可以睇齊全部工具同逐項開關）：

- `self.robot.list_actions` / `play_action`（中英文名 fuzzy match）/
  `stop_action` / `play_random_action`
- `self.robot.servo_set_one` / `servo_set_all`
- `self.robot.led_set_head` / `led_set_eye` / `led_set_mouth`
- `self.sensors.get_pir` / `set_pir_enabled`
- `self.sensors.get_sonar` / `set_sonar_threshold`
- `self.camera.take_photo`
- `self.robot.speak`（TTS）
- `self.media.list_music` / `play_music` / `stop_music`
- `self.media.search_radio` / `play_radio` / `stop_radio`

呢批工具亦可以透過對應嘅 `/api/audio/local_music/*`、`/api/audio/radio/*`
等 HTTP 端點直接呼叫，但控制面板本身冇為呢幾個做獨立 UI 分頁（純粹畀小智用，
或者自己手動 call API）。

## 檔案結構

```
open-alpha2/
├── AIDL_REFERENCE_ALPHA2.md                   ← Alpha2 17 個 AIDL interface 完整參考
├── sdk-module/
│   └── ubtechalpha2robot/              ← Alpha2 SDK（package com.ubtechinc.alpha2robot）
├── app/
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                            — Opus native 編解碼（CMake, easyopus-jni-src + prebuilt libopus.so）
│       ├── java/com/open/alpha2/
│       │   ├── MainActivity.java            — App 生命週期 + Alpha2 API 路由 + MCP tool 定義
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP，PORT 8888）
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket（俾控制面板即時事件用）
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast（含 PIR / 心口 mute 鍵）
│       │   ├── AudioController.java         — 耳筒錄音（「聽機械人」功能）
│       │   ├── AudioPlaybackController.java — 播放器（TTS/local music/radio 音頻播放）
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── XiaozhiClient.java           — 小智 WebSocket client（連 xiaozhi.me，STT/LLM/TTS/MCP/system 訊息）
│       │   ├── XiaozhiAudioController.java  — 小智語音對話嘅 mic 錄音/Opus 編碼、解碼/播放
│       │   ├── XiaozhiOtaClient.java        — 小智 OTA 配對/啟用流程
│       │   ├── XiaozhiActivationStatus.java — 小智配對狀態資料類
│       │   ├── BootReceiver.java            — 開機自動啟動 MainActivity
│       │   └── MouthLedData.java            — 咀部 LED preset 資料
│       ├── java/com/theeasiestway/opus/     — Opus JNI 純 Java 包裝（配合 cpp/ 嘅 native lib）
│       ├── java/com/ubtechinc/mic5/         — 頭部 5-mic LED JNI 包裝
│       └── assets/web/
│           ├── index.html / style.css              ← 主控制面板 (HTML/CSS)
│           ├── app-core.js                         ← 主控制面板核心 (api()/hwApi()、
│           │                                           servo 校準表、I18N 字典 — 要第一個 load)
│           ├── app-status.js / app-actions.js /
│           │   app-speech.js / app-servo.js /
│           │   app-led.js / app-camera.js /
│           │   app-mic.js / app-accel.js /
│           │   app-xiaozhi.js                      ← 主控制面板各分頁邏輯
│           ├── app-log.js                          ← WebSocket log + 頁面初始化
│           └── blockly*.{html,js,css}               ← 積木編程頁（獨立於主面板）
```

## 已知限制

- **HTTPS / 瀏覽器咪（walkie-talkie 講嘢功能）已永久停用**：之前試過用手寫
  自簽憑證起 HTTPS 去解鎖瀏覽器 `getUserMedia()`（因為瀏覽器規範限定呢個
  API 只喺 secure context 先出現），但實測發現裝置嘅瀏覽器會不斷拒絕自簽
  憑證觸發大量 `SSLHandshakeException`，最終決定連 TLS 一齊移除，改返純
  HTTP-only。控制面板「講嘢」呢個功能（用瀏覽器咪直接向機械人發聲）已經
  無條件停用（唔止喺 http:// 先停用），UI 掣仍在但完全冇反應。「聽機械人」
  （耳筒收聲，方向相反）不受影響，正常運作。
- **冇自訂語音詞彙**——Nuance 呢邊用寫死嘅 VoCon 語法（`initSpeechGrammar`/
  `startSpeechGrammar` 喺 Nuance binding 之下係空 stub），要試 grammar 式
  辨識要自己先手動切去 iFlytek engine（「語音輸入三合一測試」唔會幫你自動
  切）。自由辨識（wake word 之後）行 Nuance，local confidence 要 ≥4500 分
  先接受，雲端補完伺服器已停用。
- 冇伺服角度/電流回授。聲納方面：後端同小智 MCP 工具（`self.sensors.get_sonar`）
  已經有真正嘅 `distanceCm` 讀數（經 `com.ubtechinc.sonar.distance`
  broadcast，真機已確認），但主控制面板「狀態」分頁嗰個聲納圖表、同 Blockly
  嘅聲納事件 block，暫時都仲淨係畫/存 `triggered`（有冇觸發門檻）呢個
  boolean，未有將實際 cm 讀數畫出嚟。
- 「進階」分頁嘅方法（英文理解、ASR 歷史回放、觸發自訂事件、原始序列埠）
  未經完整真機驗證，僅供測試/探索用。
- `action/list` 用咗一個最多等 5 秒嘅 blocking wait（AIDL callback 本質係
  async），如果機器人服務初始化好慢，第一次攞列表可能會 timeout 返空
  列表——可以再按一次。
- PIR 感應器同警示反應（LED+鈴聲）已喺真機確認正常觸發；心口 mute 鍵目前
  淨係一個測試信號（撳一下著紫燈、再撳一下熄燈），未接去任何實際功能。
- LED 分頁嘅頭部/眼睛 5-mic preset（顏色/亮度/長開/閃燈/呼吸燈/跑馬燈/
  雙色燈）色碼同參數組合已喺真機確認生效；但另外兩條獨立、唔經呢啲 preset
  嘅硬件觸發路徑——聲納 obstacle 同心口 mute 鍵各自嘗試直接觸發 5-mic
  頭/眼 LED 嗰段——喺呢部機頭板上一直撞到 `API_ERROR_FAILED`，唔代表 LED
  分頁本身壞咗，純粹係唔同參數組合／唔同觸發時機嘅分別。
