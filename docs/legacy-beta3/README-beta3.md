> ARCHIVED：此文件只適合 beta3；beta4 起請睇 root README.md。
>
# Open Alpha2

`com.open.alpha2` —— 裝在 UBTECH Alpha2 機械人本機的一個 Android
App。開機自動起一個 HTTP + WebSocket server（port `8888`），提供一份網頁控制
面板，在同一個 WiFi 網路任何裝置的瀏覽器就可以連過去操作機械人；另外還有一個
獨立的 Blockly 積木編程頁可以組程式給機械人跑，和一個 XiaoZhi（小智）AI
語音對話橋接。

## 支援的機械人 / 前提

2026-09 起同 Alpha2OpenSdk 脫鉤：`sdk-module/ubtechalpha2robot`（AIDL binder
module）已刪除——部機根本無 `alpha2services.apk`，全部 binder 調用註定失敗。
取代方案：`sdk-module/hardware-direct`（胸/頭串口直驅＋JNI LED＋wire 常數
`RobotWire`/`DeveloperPacketData`）＋ app 內 `RobotStub`（no-op facade，誠實
失敗）＋ `UbxErrorCode`（已搬入 app）。`AIDL_REFERENCE_ALPHA2.md`
留做歷史逆向參考文件。

其餘前提：
- 機械人和你的瀏覽器裝置要在同一個 WiFi 網路。
- 機身跑 Android 5.1.1（API 22），App 本身 `targetSdkVersion 22`、
  `minSdkVersion 19`，`armeabi-v7a` 單一 ABI。
- Server 用純 HTTP（`http://<機械人IP>:8888/`）——之前試過的自簽憑證 HTTPS
  方案已經完全移除（見下面「已知限制」）。

17 個 AIDL interface（含原本 SDK 缺失、事後補回的 `disableActionPlay`、
`isActioning`、`IAlpha2BlueToothSerialPortService`）的完整方法清單、
transaction id、參數語意，見 `AIDL_REFERENCE_ALPHA2.md`。

## Build 方法

這個沙盒環境沒有 Android SDK/aapt2/d8，沒辦法在這裡直接組出 `.apk`。要 build 的話，
在你自己有 Android SDK 的機器（或者裝了 Android Studio）跑：

```bash
cd open-alpha2
./gradlew assembleDebug
```

輸出在 `app/build/outputs/apk/debug/app-debug.apk`。

已經包含一條 `app/debug.keystore`（標準 debug key，密碼 `android`，alias
`androiddebugkey`），簽名用的，不用你自己再生成一條。裝到機械人之前如果之前裝過
簽名不同的舊版本，記得先解除安裝，否則 Android 會因為簽名不匹配拒絕覆蓋安裝。

Opus 音頻編解碼（小智語音對話用）現在是在 `app/src/main/cpp` 用 CMake
自行編譯 `libeasyopus.so`，link 一份 prebuilt 的 `armeabi-v7a/libopus.so`
（1.5.x 系，帶 DRED），Java 側對應 `com.theeasiestway.opus.Opus/Constants`
是純 Java 檔案——build 不再需要任何 `.aar`。

## API 文件 (OpenAPI / AsyncAPI)

本專案已改用 OpenAPI 作為單一真相源（1+2 輕量方案）：

- **OpenAPI 3.0**：`openapi/open-alpha2-openapi.yml`（124 paths, beta 4），涵蓋 `/api/*`、`/upload/*`、`assets/web` 靜態頁
- **AsyncAPI 2.6**：`openapi/asyncapi.yml` 描述 `ws://<ip>:8888/ws` 全部即時事件（`asr_result`/`sonar_distance`/`pir_state` 等）
- **線上文件**：機械人運行時瀏覽 `http://<ip>:8888/docs/`（Swagger UI，離線 fallback 顯 raw YAML）或 `http://<ip>:8888/openapi.yml`
- **Typed JS Client**：`app/src/main/assets/web/api-client.js` 由 `scripts/generate-api-client.py` 自 `openapi` 自動生成，提供 `Alpha2Api.servoOne({id,angle,time})` 等 116 個帶 `assertEnum/assertRange` 校驗的 wrapper（鏡像 `ApiValidator.java`）
- **校驗層**：`app/src/main/java/com/open/alpha2/ApiValidator.java` 集中 `require/requireIntRange/requireEnum`，參數錯誤回 `400 {ok:false,error}` 而非 `500`
- **MCP 對齊**：`openapi/mcp-openapi-sync.yml` 確保 `self.robot.*` 21個工具與 OpenAPI 參數一致
- **發現**：`http://<ip>:8888/apis.yml`、`/.well-known/openapi.json`、`llms.txt`

再生 / 檢查：

```bash
python scripts/generate-api-client.py
python scripts/check-openapi-drift.py
```

詳見 `openapi/README.md`。

## 用法

1. 裝好 APK 開啟 App——會自動起 server，機身螢幕會顯示網址
   `http://<機械人IP>:8888/`。按網址或者「複製」鍵可以將網址複製到剪貼簿，
   方便打到另一台裝置的瀏覽器。
2. 在同一 WiFi 網路任何裝置的瀏覽器開啟那個網址。
3. 控制面板分成幾個分頁：

- **狀態**：媒體音量、SDK 版本/系統狀態 JSON dump、電池/WiFi/藍牙/UUID
  查詢、充電同時播放開關、最近手勢辨識、頭部降噪、聲納開關和即時距離圖表、
  PIR（人體紅外線）感應器開關和警示反應（觸發時眼/頭 5-mic LED 長亮紅燈 +
  播鈴聲，真機已確認）、加速度計即時 X/Y/Z 折線圖和一個「4角度傾側著頭/眼
  LED」小功能
- **動作**：取回機身內建動作列表（分子分類）、播放、停止
- **舵機**：20 顆伺服逐一/一起拖曳控制（含頭部 pan/tilt）、省電模式
- **語音**：ASR 引擎切換（中文 iFlytek / 英文 Nuance）、辨識結果和意圖分類
  即時顯示、一個「語音輸入三合一測試」card（同一句輸入同時試模擬說話／語法式
  辨識／文字語意理解三條不同 AIDL 路徑，方便逐一對比哪個有反應）、TTS（多引擎/
  聲線）、麥克風擁有權釋放/交回（含「持續搶 Mic」開關）、機身 wake word
  語言 preset（中/英，寫入後要重開機）和一鍵重開機鍵
- **LED**：頭部/眼睛（顏色、亮度、常亮/閃燈/呼吸燈/跑馬燈/雙色燈幾個 preset）、
  嘴部（呼吸燈速度）
- **相機**：即時串流、拍照、錄影、可調解析度（320×240 到 2064×1548）、
  「聽機械人」（耳機收聲）、一個自居中的拖曳搖桿（和鍵盤方向鍵）可以直接拖動
  控制頭部 pan/tilt
- **小智（XiaoZhi）**：連接官方 xiaozhi.me（或者自架相容 server）的 AI 語音
  助理服務，開關開＝配對／連線／隨時語音對話，關＝斷開；首次要在
  xiaozhi.me 輸入機械人讀出的配對碼；也可打字對話；有一個內建 MCP 工具清單
  card（見下面「MCP 工具」一節），和一個自訂 server 設定 card（覆寫
  OTA/WebSocket/MAC/Token）
- **進階**（raw AIDL passthrough，未經真機完整驗證的方法）：用檔案播放動作、
  停用/啟用動作播放、查詢是否正在播放、觸發自訂事件、註冊英文理解（線上/
  離線）、註冊 ASR 歷史回放、序列埠（心口/頭部）原始資料收發、取得機身序號、
  藍牙序列埠命令/AT 指令
- **積木編程**（`blockly.html`，在新分頁開啟）：Blockly 視覺化編程介面。分成
  流程/事件/動作/語音/伺服/LED 幾個自訂分類，另加 Blockly 標準的
  邏輯/數學/文字/變數/自訂函式，和一個內建範例分類；支援中英雙語即時切換、
  程式命名儲存/載入/刪除、匯出入 `.xml`。事件驅動用加速度計門檻／聲納觸發的
  hat block。

即時事件（頭部觸摸、手勢、ASR 結果/意圖、電池、跌落方向、聲源角度、喚醒詞、
PIR、藍牙連線、WiFi 查詢結果等）全部經 WebSocket 即時推送到「即時事件 Log」，
控制面板不用手動 refresh。

### MCP 工具（給小智用）

這台機經 XiaoZhi 協議的 MCP 橋接暴露以下工具給遠端 LLM 直接操作機械人（在
「小智」分頁的「內建MCP功能列表」card 可以看到全部工具和逐項開關）：

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

這批工具也可以透過對應的 `/api/audio/local_music/*`、`/api/audio/radio/*`
等 HTTP 端點直接呼叫，但控制面板本身沒有為這幾個做獨立 UI 分頁（純粹給小智用，
或者自己手動 call API）。

## 檔案結構

```
open-alpha2/
├── AIDL_REFERENCE_ALPHA2.md                   ← Alpha2 17 個 AIDL interface 完整參考
├── openapi/
│   ├── open-alpha2-openapi.yml         ← OpenAPI 3.0 (124 paths, beta 4)
│   ├── asyncapi.yml                    ← AsyncAPI 2.6 (WebSocket /ws 事件)
│   ├── mcp-openapi-sync.yml            ← MCP 21工具 ↔ OpenAPI 參數對齊表
│   └── README.md                       ← 1+2 方案說明
├── scripts/
│   ├── generate-api-client.py          ← 由 openapi 生成 api-client.js
│   └── check-openapi-drift.py          ← 檢查 code ↔ spec 漂移
├── sdk-module/
│   └── hardware-direct/               ← 胸/頭串口直驅＋JNI LED＋wire 常數（2026-09 起唯一 module）
├── app/
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                            — Opus native 編解碼（CMake, easyopus-jni-src + prebuilt libopus.so）
│       ├── java/com/open/alpha2/
│       │   ├── MainActivity.java            — App 生命週期 + Alpha2 API 路由 + MCP tool 定義
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP，PORT 8888，400/500 分流）
│       │   ├── ApiValidator.java            — OpenAPI 驅動的參數校驗層
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket（給控制面板即時事件用）
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast（含 PIR / 心口 mute 鍵）
│       │   ├── AudioController.java         — 耳機錄音（「聽機械人」功能）
│       │   ├── AudioPlaybackController.java — 播放器（TTS/local music/radio 音頻播放）
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── XiaozhiClient.java           — 小智 WebSocket client（連 xiaozhi.me，STT/LLM/TTS/MCP/system 訊息）
│       │   ├── XiaozhiAudioController.java  — 小智語音對話的 mic 錄音/Opus 編碼、解碼/播放
│       │   ├── XiaozhiOtaClient.java        — 小智 OTA 配對/啟用流程
│       │   ├── XiaozhiActivationStatus.java — 小智配對狀態資料類
│       │   ├── BootReceiver.java            — 開機自動啟動 MainActivity
│       │   └── MouthLedData.java            — 嘴部 LED preset 資料
│       ├── java/com/theeasiestway/opus/     — Opus JNI 純 Java 包裝（配合 cpp/ 的 native lib）
│       ├── java/com/ubtechinc/mic5/         — 頭部 5-mic LED JNI 包裝
│       └── assets/web/
│           ├── index.html / style.css              ← 主控制面板 (HTML/CSS)
│           ├── openapi.yml / asyncapi.yml          ← 文件靜態檔（由 openapi/ 複製）
│           ├── docs/index.html                     ← Swagger UI
│           ├── apis.yml / llms.txt / .well-known/  ← 發現文件
│           ├── api-client.js                       ← Typed JS Client (自動生成)
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

- **HTTPS / 瀏覽器麥克風（walkie-talkie 說話功能）已永久停用**：之前試過用手寫
  自簽憑證起 HTTPS 去解鎖瀏覽器 `getUserMedia()`（因為瀏覽器規範限定這個
  API 只在 secure context 才出現），但實測發現裝置的瀏覽器會不斷拒絕自簽
  憑證觸發大量 `SSLHandshakeException`，最終決定連 TLS 一起移除，改回純
  HTTP-only。控制面板「說話」這個功能（用瀏覽器麥克風直接向機械人發聲）已經
  無條件停用（不只在 http:// 才停用），UI 鍵仍在但完全沒反應。「聽機械人」
  （耳機收聲，方向相反）不受影響，正常運作。
- **沒有自訂語音詞彙**——Nuance 這邊用寫死的 VoCon 語法（`initSpeechGrammar`/
  `startSpeechGrammar` 在 Nuance binding 之下是空 stub），要試 grammar 式
  辨識要自己先手動切去 iFlytek engine（「語音輸入三合一測試」不會幫你自動
  切）。自由辨識（wake word 之後）走 Nuance，local confidence 要 ≥4500 分
  才接受，雲端補完伺服器已停用。
- 沒有伺服角度/電流回授。聲納方面：後端和小智 MCP 工具（`self.sensors.get_sonar`）
  已經有真正的 `distanceCm` 讀數（經 `com.ubtechinc.sonar.distance`
  broadcast，真機已確認），但主控制面板「狀態」分頁那個聲納圖表、和 Blockly
  的聲納事件 block，暫時都還只是畫/存 `triggered`（有沒有觸發門檻）這個
  boolean，還沒將實際 cm 讀數畫出來。
- 「進階」分頁的方法（英文理解、ASR 歷史回放、觸發自訂事件、原始序列埠）
  未經完整真機驗證，僅供測試/探索用。
- `action/list` 用了一個最多等 5 秒的 blocking wait（AIDL callback 本質是
  async），如果機器人服務初始化很慢，第一次取列表可能會 timeout 回空
  列表——可以再按一次。
- PIR 感應器和警示反應（LED+鈴聲）已在真機確認正常觸發；心口 mute 鍵目前
  只是一個測試信號（按一下亮紫燈、再按一下熄燈），未接到任何實際功能。
- LED 分頁的頭部/眼睛 5-mic preset（顏色/亮度/常亮/閃燈/呼吸燈/跑馬燈/
  雙色燈）色碼和參數組合已在真機確認生效；但另外兩條獨立、不經這些 preset
  的硬體觸發路徑——聲納 obstacle 和心口 mute 鍵各自嘗試直接觸發 5-mic
  頭/眼 LED 那段——在這台機頭板上一直撞到 `API_ERROR_FAILED`，不代表 LED
  分頁本身壞了，純粹是不同參數組合／不同觸發時機的分別。
