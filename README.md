# Open Alpha2

`com.open.alpha2` —— 裝喺 UBTECH Alpha2 / Lynx（QRobot）機械人本機嘅一個 Android
App。開機自動喺機械人度起一個 HTTP + WebSocket server（port `8888`），提供一份
網頁控制面板，喺同一個 WiFi 網絡任何裝置嘅瀏覽器就可以連過去，測試/操作機械人
兩個唔同世代 AIDL SDK 暴露嘅幾乎全部功能，另外仲有一個獨立嘅 Blockly 積木編程頁
可以砌程式俾機械人行。

## 支援嘅機械人 / 前提

呢個 App 入面 bundle 咗**兩個獨立嘅 AIDL SDK module**，對應兩代唔同嘅機身韌體：

| Backend | SDK module | 對應機身韌體 | HTTP 路由前綴 | 控制面板 nav |
|---|---|---|---|---|
| **Alpha2** | `sdk-module/ubtechalpha2robot`（package `com.ubtechinc.alpha2robot`） | `alpha2services` v1.1.7.3.20 | `/api/alpha2/...` | 狀態/動作/伺服/語音/LED |
| **Lynx / QRobot** | `sdk-module/lynxrobot`（package `com.ubtechinc.lynxrobot`） | `alpha2services_base` 3.0.0.2 | `/api/lynx/...` | 狀態/動作/馬達/語音/LED |

兩代韌體嘅 AIDL 介面完全唔同（method 簽名、transaction 順序都唔一樣），兩個
module 各自對應一套獨立嘅 `.aidl` 定義，唔可以混用。**一部實體機同一時間只會
跑緊其中一代韌體**，呢個 App 唔會自動偵測機身係邊一代——控制面板開頭要人手揀一次
「Alpha2」定「Lynx」，之後個揀擇會存喺瀏覽器（`localStorage`）同 App
（`/api/system/backend/get`、`/api/system/backend/set`），淨係俾網頁記得住你上次
揀邊個，方便下次開返同一個 tab 有個合理預設；實際會唔會真係傾到偈，取決於你部
機本身跑緊邊代韌體。

其餘前提：
- 機械人同你個瀏覽器裝置要喺同一個 WiFi 網絡。
- 機身跑 Android，App 本身 `targetSdkVersion 22`、`minSdkVersion 19`——冇用任何
  API 19 之後先出現嘅 method（見 `app/build.gradle` comment）。

Alpha2 呢邊 17 個 AIDL interface（連埋原本 SDK 缺失、事後補返嘅
`disableActionPlay`、`isActioning`、`IAlpha2BlueToothSerialPortService`）嘅
完整方法清單、transaction id、參數語意，見 `AIDL_REFERENCE_ALPHA2.md`。

Lynx 呢邊 21 個 AIDL interface（`ServiceFetcher`/`LynxRobotApi` 用嗰套，
`com.ubtechinc.alpha.serverlibutil.aidl`）嘅完整方法清單同反編譯確認嘅韌體
行為（包括 `speech` service 喺 `alpha2services_base` 3.0.0.2 冇被登記、未接收
嘅 broadcast 等已知限制），見 `AIDL_GUIDE_LYNX.md`。

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

## 用法

1. 裝好 APK 開啟個 App——會自動起 server，機身螢幕會顯示個網址（如果 TLS 憑證
   起到就係 `https://<機械人IP>:8888/`，起唔到就 fallback 落 `http://`，見下面
   「HTTPS / 麥克風」一節）。撳個網址或者「複製」掣可以將個網址複製落剪貼簿，
   方便打去另一部裝置嘅瀏覽器。
2. 喺同一 WiFi 網絡任何裝置嘅瀏覽器開嗰個網址。頁頂 nav bar 會跟你上次揀嘅
   backend（Alpha2 / Lynx）顯示對應嗰組分頁，要跟返你部機實際跑緊嘅韌體代數。
3. 控制面板分咗幾個分頁（有幾個係兩個 backend 各自獨立、有幾個係共用）：

**Alpha2 / Lynx 各自獨立嘅分頁**（跟返上面表所示嘅 HTTP 前綴）：
- **狀態**：SDK 版本、系統狀態 JSON dump、電池/WiFi/藍牙/UUID 查詢、頭部降噪、
  聲納開關同即時距離圖表（Lynx 呢邊仲有 PIR、電量/版本查詢）
- **動作**：攞返機身內建動作列表、播放、停止
- **伺服馬達 / 馬達**：Alpha2 20 顆伺服逐一/一齊控制（含 servo 19/20 頭部
  pan/tilt）；Lynx 呢邊係 `motor/*`（read/move_absolute/move_ref/set_all/
  power_save）
- **語音**：ASR 辨識結果/意圖分類即時顯示、TTS（多引擎/聲線）、麥克風釋放/交回、
  自我打斷、機身 wake word 語言 preset、一個「語音輸入三合一測試」card（同一句
  輸入同時試 `onSpeech` 模擬講嘢／`initSpeechGrammar`+`startSpeechGrammar`
  語法式辨識／`onTextUnderstand` 文字語意理解三條唔同 AIDL 路徑，方便逐一對比
  邊個真係有反應——語法式辨識喺 Nuance binding 底下係空 stub，要自己先手動
  切去 iFlytek engine 先會有反應，呢個測試唔會幫你自動切）
- **LED**：頭部/眼睛/咀部（Alpha2）；頭部/眼睛/咀部/胸口/WiFi 燈（Lynx，款式
  更多：flash/breath/marquee 等效果）

**共用分頁**（同揀緊邊個 backend 無關，一份 UI 兩邊共用）：
- **相機**：即時串流、拍照、錄影、可調解像度（320×240 到 2064×1548）、耳筒
  聽聲（walkie-talkie 收）、講嘢（walkie-talkie 送，要 HTTPS 先有 mic
  權限）、一個自居中嘅拖曳搖桿（同鍵盤方向鍵）可以直接拖動控制頭部 pan/tilt
- **加速度計**：純 `SensorManager` 讀數（唔屬於任何一個 AIDL backend，同一粒
  實體 IMU），即時 X/Y/Z 折線圖，仲有一個「4角度傾側著頭/眼LED」小功能（Alpha2
  限定）
- **積木編程**（`blockly.html`，喺新分頁開）：Blockly 視覺化編程介面，Alpha2
  限定。分咗流程/事件/動作/語音/伺服/LED 幾個自訂分類，另加 Blockly 標準嘅
  邏輯/數學/文字/變數/自訂函式；支援中英雙語即時切換、程式命名儲存/載入/刪除、
  匯出入 `.xml`、有內建範例程式。事件驅動用加速度計門檻／聲納觸發嘅 hat block。

即時事件（頭部觸摸、手勢、ASR 結果/意圖、電池、跌落方向、聲源角度、喚醒詞、
藍牙連線、WiFi 查詢結果等）全部經 WebSocket 即時推送到「即時事件 Log」，控制
面板唔使手動 refresh。

## 檔案結構

```
open-alpha2/
├── AIDL_REFERENCE_ALPHA2.md                   ← Alpha2 17 個 AIDL interface 完整參考
├── AIDL_GUIDE_LYNX.md                  ← Lynx 21 個 AIDL interface 完整指南
├── sdk-module/
│   ├── ubtechalpha2robot/              ← Alpha2 SDK（package com.ubtechinc.alpha2robot）
│   └── lynxrobot/                      ← Lynx/QRobot SDK（package com.ubtechinc.lynxrobot）
├── app/
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/open/alpha2/
│       │   ├── MainActivity.java            — App 生命週期 + Alpha2 API 路由 + backend 分派
│       │   ├── LynxController.java          — Lynx API 路由
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP，TLS 已移除，見下）
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast
│       │   ├── AudioController.java         — 耳筒錄音（聽聲）
│       │   ├── AudioPlaybackController.java — Walkie-talkie 播放（講嘢）
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── BootReceiver.java            — 開機自動啟動
│       │   └── MouthLedData.java            — 咀部 LED preset 資料
│       └── assets/web/
│           ├── index.html / style.css             ← 主控制面板 (HTML/CSS)
│           ├── app-core.js                        ← 主控制面板核心 (api()/lynxApi()/
│           │                                          hwApi()、servo 校準表、I18N 字典 —
│           │                                          要第一個 load)
│           ├── app-status.js / app-actions.js /
│           │   app-speech.js / app-servo.js /
│           │   app-led.js / app-camera.js /
│           │   app-mic.js / app-accel.js /
│           │   app-lynx.js                        ← 主控制面板各分頁邏輯 (2026-08 由
│           │                                          單一 app.js 拆出, 見 app-core.js
│           │                                          檔頭註解)
│           ├── app-log.js                         ← WebSocket log + 頁面初始化
│           │                                          (DOMContentLoaded — 要最後 load)
│           └── blockly*.{html,js,css}              ← 積木編程頁（獨立於主面板）
```

## HTTPS / 麥克風（walkie-talkie）

「用瀏覽器 mic 向機械人發聲」呢個功能（控制面板嘅「講嘢」按鈕）用到瀏覽器嘅
`getUserMedia()` API。**瀏覽器規範規定呢個 API 只喺「安全來源」（secure context）
先出現**——即係 `https://` 或者 `http://localhost`。之前用 `http://<機器人IP>:8888/`
開嘅話，`navigator.mediaDevices` 本身就係 `undefined`，呢個係瀏覽器政策，JS
層面完全繞唔過，唔關 APK 本身邏輯事。

依家 App 開機時會：
1. 用 `SelfSignedCert.java`（純 `java.security.*`，手寫 ASN.1/X.509v3 DER
   encoding，冇用任何第三方 crypto library）產生一張自簽 RSA 2048 憑證，CN 用機器人
   本身嘅 WiFi IP，有效期 20 年（避免裝置時鐘唔準）。
2. 用 `TlsSupport.java` 將呢張憑證/私鑰放入一個只喺記憶體嘅 `PKCS12 KeyStore`，起
   一個 `SSLContext`/`SSLServerSocketFactory`。
3. `HttpServer` 用呢個 factory 起 `SSLServerSocket`（唔係普通 `ServerSocket`），
   accept loop / handleClient 邏輯完全冇變——`SSLSocket` 本身都係 `Socket`
   嘅 subtype。
4. 如果因為任何原因 TLS 起唔到（例如某啲裝置嘅 crypto provider 有問題），會自動
   fallback 用返 plain HTTP，唔會累到成個 panel 用唔到（只係麥克風呢一個功能冧）。

**用法：**
- Activity 開機畫面會顯示 `https://<機器人IP>:8888/`（如果 TLS 起到嘅話）。
- 第一次用瀏覽器開會見到「連線不是私人連線 / 不安全」嘅警告——呢個係**必然**嘅，
  因為呢張憑證冇真正 CA 簽發（LAN IP 本身冇資格攞正式憑證）。撳
  **「進階」→「繼續前往 <IP>（不安全）」** 就得，之後嗰個 session
  `getUserMedia()` 就會正常出現，麥克風功能即可使用。
- 呢個警告手機（Android/iOS Chrome/Safari）同桌面都要各自click 過一次
  （每個瀏覽器獨立記錄，換瀏覽器/換裝置要再click一次）。
- 已知：Safari 對自簽憑證嘅提示流程同 Chrome 唔完全一樣，但原理一致，撳
  「顯示詳細資料 → 前往這個網站」等效嘅選項就得。

**憑證會跨 app 重啟保持不變**：`SelfSignedCert`/`TlsSupport` 會將生成咗嘅私鑰/憑證
存落 app 私有儲存（`getFilesDir()`），下次開機如果 IP 冇變就直接讀返舊嗰張用，唔會
每次都生成一張全新嘅。呢個好重要，因為瀏覽器嘅「已接受此憑證」記錄係綁定住憑證
本身（fingerprint），唔淨係網址；如果每次 app 開機都係一張新憑證（之前嘅行為），
即使用家啱啱先撳過「進階 → 繼續前往」，下一次開 app（甚至同一個瀏覽器分頁）都會
再顯示一次警告——呢個正正係 `logcat_2026-07-03_12-46-07.txt` 反映嘅「每次都要重新
撳一次」現象嘅根因。如果機械人 IP 變咗（換 WiFi、DHCP 重新分配），會自動偵測到
CN 唔匹配並生成一張新嘅（唔會將舊 IP 嘅憑證錯誤咁沿用落去）。

**驗證狀態**：呢部分（`SelfSignedCert`/`TlsSupport`）嘅 ASN.1 encoding、自簽名驗證、
`KeyStore`/`SSLContext`/`SSLServerSocket` 建立、一次完整 TLS handshake（包括
`HttpsURLConnection` 完整 hostname verification 同 `openssl s_client` 交叉驗證）、
以及**憑證持久化**（同一個 cacheDir + 同一個 IP 兩次 call `loadOrGenerate()`，確認
serial number / 憑證 raw bytes / 私鑰 raw bytes 完全一致；IP 唔同時正確生成新憑證），
已經喺沙盒用 standalone JDK 21 harness 測試通過。**未驗證**：喺 Android 5.1
（API 22）實機上嘅實際行為——雖然呢度用到嘅全部 API（`javax.net.ssl.*`,
`java.security.*`, `KeyManagerFactory`, `SSLContext.getInstance("TLS")`,
`context.getFilesDir()`）由 API 1/9 開始已經存在，理論上冇兼容性問題，但最終都要
你喺機械人度實機確認。

## Walkie-talkie 音質修正（斷斷續續 + 延遲累積）

實機 logcat（`logcat_2026-06-30_14-08-34.txt`）反映咗兩個症狀：聲音斷斷續續、
播出嚟嘅聲慢咗大約 3 秒。分析後確認係同一個根本問題嘅兩個表徵——
`AudioPlaybackController.pcmQueue` 原本冇上限：

- 瀏覽器 `onaudioprocess` 大約每 50-160ms 送一個 chunk，但每次上載都係獨立一個
  `POST /upload/audio`（`Connection: close`，每次都開新 TCP 連線），LAN 上嘅上載
  時序其實係「一舊舊」（bursty）到達，唔係均勻嘅串流。
- **斷斷續續**：一旦兩個 chunk 之間有少少延遲，`AudioTrack` 個 native buffer 食
  晒，logcat 見到 `releaseBuffer() ... disabled due to previous underrun,
  restarting`——Android 自動摞咗條 track 重新開始，就係你聽到嗰啲斷續聲。
- **慢咗 3 秒**：因為 queue 冇上限，一旦上載爆發式咁一次過到好多 chunk，
  `writeLoop()` 逐個 `write()`（追唔切），呢啲 chunk 唔會被跳過，只會逐個逐個
  被忠實咁播晒——結果就係播放進度同實際講嘢嘅時間點愈拉愈遠，聽落好似個聲「慢咗」。

**修正**（`AudioPlaybackController.java`）：
1. 加咗一個 jitter buffer 上限 `JITTER_BUFFER_CAP_BYTES`（~600ms 音頻）。
   `enqueuePcm()` 而家如果發現 queue 已經囤積咗超過呢個上限嘅音頻，會將**最舊**
   嗰啲 chunk 掉咗，先至加入新嚟嗰個——寧願跳前少少都好過個延遲不斷累積。
2. 加咗一個細細嘅 prebuffer（`PREBUFFER_CHUNKS = 2`，最多等 300ms）：
   `writeLoop()` 而家會等夠 2 個 chunk 先開始寫入 `AudioTrack`，等佢個 native
   buffer 有少少緩衝去食開頭嗰下 jitter，減少啱啱開始就 underrun 嘅機會。

已用 standalone JDK harness 模擬「一次過爆發 10 個 chunk」同「持續 2000 次循環、
consumer 追唔切 producer」兩個場景，確認 `queuedBytes` 喺兩種情況下都封頂喺 cap
之內、唔會無限增長（即係延遲唔會再累積）。未驗證：實機上嘅主觀聽感——呢個要你
裝返個新 APK 再測試先知實際斷續/延遲有幾大改善，如果 600ms cap 太保守/太寬鬆
（跳得太密 vs 追唔切延遲），可以再調 `JITTER_BUFFER_CAP_BYTES`。

### 第二輪修正：`play()` 叫得太早（真正主因）

裝咗上面個版本之後嘅新 logcat（`logcat_2026-06-30_14-26-58.txt`）顯示 underrun
**依然發生**，而且每次都係喺「第一個 audio chunk 到達之後幾毫秒內」即刻發生
（例如 session 開始後 703ms/5081ms/5154ms/27494ms/2500ms 先收到第一個
chunk，underrun 就跟住嗰下即刻發生）。

追查落去發現真正原因：`start()` 入面 `track.play()` 係喺 `AudioTrack` 一 construct
完就即刻叫（喺 `writeLoop()`/任何 prebuffer 邏輯執行之前）。`play()` 一叫，track
即刻變成 PLAYING 狀態，開始由內部 buffer 度攞數據播——但呢個時候個 buffer 仲係
得返初始嘅靜音內容，好快播完，即時觸發 underrun。而第一個 chunk 由瀏覽器嗰邊
（`getUserMedia()` 權限對話框、tab focus、網絡設定等）到達嘅時間差異好大
（logcat 見過由 700ms 到 27秒都有），舊版 300ms 嘅 prebuffer timeout 完全唔夠。

**修正**：
- `track.play()` 由 `start()` 移咗去 `writeLoop()` 入面，改做**喺真正寫咗夠
  `PREBUFFER_CHUNKS` 個 chunk 落 `AudioTrack`（`MODE_STREAM` 容許 `play()` 之前
  就 `write()`）之後先叫**——唔再假設第一個 chunk 會喺任何固定時限內到達，
  無論等幾耐都唔會提早 `play()`。
- 已用 standalone harness 模擬 logcat 入面實際見到嘅 5秒、27秒延遲場景，確認
  `play()` 都正確咁延遲到真正有數據先觸發；正常快速情況（~180ms）都冇被
  拖慢。

呢個先係根本修正，上面嘅 jitter buffer cap 依然有效同必要（防止持續 overload
情況下延遲累積），但單靠佢哋解決唔到「一開始就 underrun」嘅問題——真正主因
係 `play()` 時序，而唔係 buffer 容量。

## 相機頁面 UI 重整（浮動掣 + Keyboard 控制）

移除咗「測試喇叭」「音頻診斷」「按住講嘢」呢幾個獨立按鈕（診斷用途已完成任務），
相機頁面依家嘅操作方式：

- **功能鍵** checkbox（原本叫「頭部搖桿」）：一次過控制 3 樣嘢嘅顯示——頭部搖桿
  （joystick pad）、耳筒 icon（聽機械人）、咪 icon（講嘢）。唔 tick 就三樣都隱藏,
  同時強制停低聽機械人/講嘢（唔會留低隱形運行緊嘅狀態）。
- **耳筒 icon**（🎧，viewport 右下角）：撳一下 toggle,灰色=關,綠色=開（聽機械人）。
- **咪 icon**（🎤，耳筒隔籬）：長按=講嘢（送出瀏覽器 mic 去機械人），灰色=關,
  撳住嗰陣變紅色 + 有 pulse 動畫,鬆手即停。滑鼠/觸控拖出個掣範圍外都會正確停止
  （用咗 `setPointerCapture`）。
- 兩個 icon 都係半透明浮動喺 viewport 右下角,唔會擋住鏡頭畫面。

**Keyboard 支援**（新增）：撳一下相機畫面令佢攞到 focus（會見到藍色 focus
outline + 右上角提示文字），之後：
- **↑↓←→**：控制頭部 servo 19（pan）/20（tilt），撳住會用返同滑鼠拖拉一樣嘅
  throttle/re-home 邏輯（放手自動返返去中心）。
- **長按 Space**：等同撳住咪 icon 講嘢，鬆手即停。
- 如果撳住方向鍵/Space 期間 focus 意外跳走（例如撳咗 Tab）,會自動清返所有狀態,
  唔會出現「頭部/咪卡住唔停」嘅情況。

呢啲改動已經用 jsdom（headless DOM 環境,`npm install jsdom` 跑落 sandbox）做完整
場景測試：checkbox tick/untick 嘅顯示邏輯、方向鍵 keydown/keyup 觸發嘅 `servo/one`
API 調用（確認真係打去 servo 19）、Space 鍵唔會拋錯、耳筒掣正確綁定、舊有 4 個
掣確實由 DOM 中移除,全部通過。未驗證：實機瀏覽器嘅實際觸感/手感（keyboard focus
outline 喺唔同瀏覽器嘅視覺表現、觸控裝置嘅 pointer capture 行為），呢啲要你裝
返個新 APK 實測。

## 用電腦 mic 講嘢有回音（Echo）修正

根據 logcat（`logcat_2026-07-01_03-26-09.txt`）確認：講嘢（`AudioTrack` 播放）同
聽機械人（`AudioController` 用 `AudioRecord` 錄音）可以同時開喺度。呢部機嘅
mic 同揚聲器冇 acoustic isolation（`AudioHardwareTiny` 呢類簡化 audio HAL 通常
唔會做 AEC），所以形成一個回音迴路：你講嘢 → 機械人揚聲器播出 → 機械人自己個
mic 即刻收返 → 經耳筒串流返上你電腦播返出嚟 → 你聽到自己把聲嘅回音。

**修正**（`app.js`）：加咗一個 `micMuted` flag，做「半雙工」(half-duplex) 效果,
好似真正對講機咁——同一時間淨係得一個方向有聲:
- `startTalk()`（撳咪講嘢）開始嗰刻,`micMuted` 變 `true`。
- `playWavChunk()`（播放耳筒收到嘅機械人 mic 音頻）見到 `micMuted` 為 `true`
  就唔會實際播出嚟(但仍然照常 decode 同推進時序記錄,等鬆手之後唔會爆一大堆
  積壓緊嘅聲)。
- `stopTalk()`（鬆手）嗰刻,`micMuted` 變返 `false`,耳筒播放立即恢復正常。

呢個做法特登唔郁 Android 側嘅 `AudioController`/`AudioRecord`——即係話講嘢期間
「聽機械人」個 HTTP 串流連線同錄音全部維持運作,純粹淨係喺瀏覽器呢一層唔播出嚟,
避免重新觸發之前處理過嘅 mic race condition(`startInput ... already started`)。

已用 jsdom 驗證:`startTalk()` 期間 `playWavChunk()` 確實唔會建立/播放
`BufferSource`(即冇聲),`stopTalk()` 之後播放正常恢復。未驗證:實機上主觀聽感
(半雙工手感、静音/恢復嘅時間點準唔準)——呢個要你裝返新 APK 實測先知。

## 講嘢 crash 之後永久卡死（無法再發射）修正

反映:講嘢中途 crash 咗一次之後,之後點撳咪掣都冇反應,瀏覽器 console 見到
`Unhandled promise rejection: Failed to execute 'createMediaStreamSource' on
'AudioContext': parameter 1 is not of type 'MediaStream'`。

**根因**:`startTalk()` 入面,`getUserMedia()` 嗰步有 `try/catch` 包住,但
**之後**嘅初始化(`api("audio/play/start")`、建立 `AudioContext`、
`createMediaStreamSource`、`createScriptProcessor` 等)完全冇任何錯誤處理。一旦
呢幾步入面任何一步拋錯(例如 `getUserMedia` resolve 到一個非預期/唔合法嘅值,
令 `createMediaStreamSource` 嘥錯),成個 `startTalk()` 就會中途死咗——但之前
已經行咗嘅 `talkActive = true`/`micMuted = true` 冧唔返,永久停留喺呢個狀態。
之後撳咪掣,`startTalk()` 開頭嘅 `if (talkActive) return;` 就即刻 return,
睇落個掣「著咗」但完全冇反應,就係「之後就再無辦法發射」嘅成因。

**額外搵到嘅相關 crash**:`Cannot read properties of null (reading
'sampleRate')`——`onaudioprocess` 呢個由瀏覽器 audio thread 排程觸發嘅
callback,喺 `stopTalk()` 已經將 `talkAudioContext` 清做 `null` 之後,仍然有
機會執行多一次已經排咗隊嘅 invocation(`disconnect()`/清走 handler 唔保證
即時取消一個已經開始/排咗隊嘅 callback),入面讀 `talkAudioContext.sampleRate`
就會撞到 null。

**修正**(`app.js`):
1. `startTalk()` 入面,`getUserMedia()` 成功之後嘅全部初始化步驟,而家都包咗
   喺同一個 `try/catch`——任何一步拋錯,都會顯示解釋性 alert,並且完整咁借用
   `stopTalk()` 個清理邏輯做回滾(`talkActive`/`micMuted` 重設、FAB 樣式重設、
   已經部分建立嘅 audio node 清走),唔會再永久卡死,可以即刻再撳掣重試。
2. `onaudioprocess` handler 加咗一個 `talkAudioContext` 嘅 null-check(唔淨係
   靠 `talkActive`),就算有 stale callback 喺清理之後先執行,都唔會再拋錯。

已用 jsdom 模擬完全一樣嘅錯誤(`createMediaStreamSource` 拋
`TypeError: ...not of type 'MediaStream'`),確認:狀態正確回滾、FAB 樣式
正確重設、有清晰嘅中文 alert 解釋、失敗之後可以即刻重試而唔會再卡死;亦模擬咗
stale `onaudioprocess` callback 嘅 race,確認唔會再拋 null reference 錯誤。
未驗證:實機上實際觸發呢個 crash 嘅根本原因(`getUserMedia` 點解會 resolve 到
一個非法值)——呢個可能同瀏覽器/裝置本身有關,如果之後再撞到,麻煩提供嗰陣嘅
瀏覽器 console 完整錯誤同 logcat,方便進一步追查。

## Logcat 診斷:大量 SSLHandshakeException（噪音,非功能性 bug）

新 logcat（`logcat_2026-07-29_04-57-55.txt`）顯示 `HttpServer.handleClient` 喺
約 4 分鐘內記錄咗 **481 次** `SSLHandshakeException`(`sslv3 alert certificate
unknown`)。追查結果:

- **唔係 crash,亦冇資源洩漏**——呢個 exception 一路都完整咁俾 `handleClient()`
  嘅 `catch (Exception e)` 捕捉咗,`finally` block 保證 socket 一定會 close,
  用嘅係 cached thread pool(短命執行緒用完即棄)。
- **同一時間其他連線運作正常**——`/api/speech/tts` 喺呢段期間持續每隔幾秒
  成功一次,證明個 HTTPS server 本身冇壞,亦冇因為呢啲失敗連線而累到其他
  request。
- **根本成因**:`certificate unknown` TLS alert 代表有個 client 主動拒絕咗
  自簽憑證(即係話有人/有嘢不斷開新連線去呢個 HTTPS server,但**未曾接受過
  「進階 → 繼續前往」呢個安全警告**——見返 README 嘅 HTTPS 章節)。由於
  `SSLHandshakeException` 喺 `socket.getInputStream()`(即 TLS handshake 本身)
  嗰步就已經拋出,喺 headers/request line 都未讀到之前,所以呢份 logcat
  冇辦法知道究竟係邊個瀏覽器分頁/裝置/程式響咁密集咁重試。

**已做嘅改動**:`handleClient()` 嘅 catch block 而家會夾埋記錄
`socket.getRemoteSocketAddress()`(來源 IP:port),下次如果再有呢個情況,
logcat 就可以話你知邊部裝置/邊個 IP 響不斷重試,方便進一步追查(例如係咪
你手機瀏覽器有個背景分頁一直未撳過「繼續前往」)。呢次純粹係加強 log,
冇改動任何 TLS/連線處理邏輯,因為現有證據顯示 handshake 本身冇問題,係
client 一方冇信任個憑證。

## 「聽聲」（耳筒）仍然慢 3 秒 + 取消搶咪最長時間

### 聽聲延遲累積嘅真正根源(唔喺播放層,而係 decode 並行)

之前修過「講嘢」（`AudioPlaybackController`，機械人講嘢俾你聽）嘅延遲累積問題,
但你反映「聽聲」(耳筒,即係聽機械人四周環境聲, `AudioController` + `app.js`
嘅 `runMicStreamLoop`/`playWavChunk`)依然會越聽越慢,累積到大約 3 秒。呢個係
一條完全獨立嘅 code path,之前冇改過。

追查發現:`runMicStreamLoop()` 讀到每個音頻 chunk 之後,直接 fire-and-forget
咁 call `playWavChunk(chunk)`(冇 `await`)。`playWavChunk()` 入面嘅
`micAudioContext.decodeAudioData()` 本身係一個 async 操作、要花時間,如果
decode 速度追唔切 chunk 到達速度(呢個唔止喺網絡卡頓先會發生,喺資源緊張嘅
裝置上可能係持續性嘅),就會有愈嚟愈多個 `decodeAudioData()` promise 同一時間
跑緊,每一個嘅完成時間都會逐漸推遲——呢個延遲完全發生喺「仲未 decode 完」嗰個
階段,之前加嘅 `MIC_MAX_SCHEDULED_LAG_SEC` 播放排程上限對呢部分完全冇制約
(佢淨係限制緊「已經 decode 完、等緊播」嗰批)。並行嘅 decode 仲有埋一個
額外風險:完成次序可能同開始次序唔一致,有機會令聲音播出次序都亂咗。

**修正**(`app.js`):
- 加咗 `micPendingChunks`(一個 array queue)同 `micDrainLoop()`——`
  runMicStreamLoop()` 而家淨係將 chunk push 落 queue,由 `micDrainLoop()`
  逐個、**順序**處理(`await playWavChunk()` 完全行完先攞下一個),保證
  decode 永遠唔會並行,亦保證播放次序。
- `micPendingChunks` 加咗上限(`MIC_MAX_PENDING_CHUNKS = 3`)——如果 decode
  真係追唔切,queue 太長就會掉走最舊、仲未 decode 嘅 chunk,寧願跳走都好過
  播一啲已經係幾秒前嘅聲。

已用 jsdom 模擬「decode 耗時 30ms、但 chunk 每 10ms 到達一個」(即刻意令
decode 追唔切嘅場景),確認:decode 永遠冇並行過、queue 冇超過上限、實際播出嚟
嘅聲音次序保持遞增冇亂序。未驗證:實機上實際延遲改善咗幾多秒——呢個要你
裝返新 APK 實測先知。

### 取消搶咪最長時間

移除咗 `AudioController.java` 嘅 `MAX_SESSION_MS`(原本 5 分鐘)硬性上限
——之前設計呢個係做「雙重保險」,喺 `stopIfIdle()`(client 斷線偵測)之外
加多一重防呆,以防某啲斷線情況冇觸發到 `stopIfIdle()`,令 mic 永久俾呢個
App 揸住唔放。跟你要求已經移除,依家聽幾耐都唔會被強制斷開。

**取捨**:`stopIfIdle()` 依然係主要嘅釋放機制,冇被呢次改動影響;但如果之後
真係撞到一種「client 斷線但完全冇觸發 `stopIfIdle()`」嘅罕見情況,機械人
自己嘅語音喚醒功能可能會被無限期揸住冇得用,要重啟 App 先解決。目前 logcat
未見過呢類情況發生。

## 音質降至 8kHz + 播放 buffer 加大（應對「聽聲慢」+「播聲斷續」）

根據新 logcat（`logcat_2026-07-30_08-43-18.txt`）,播聲（機械人講嘢俾你聽）
**喺 session 中途**（唔係之前修過嗰個「session 開始就 underrun」）都出現
`releaseBuffer() ... disabled due to previous underrun, restarting`,前一個
upload gap 只係 234ms,已經足以榨乾當時嘅 buffer(舊設定 `bufBytes = minBufBytes
* 4` ≈ 240ms,幾乎冇 headroom)。

**修正一:播放 buffer 加大**(`AudioPlaybackController.java`):
- `bufBytes` multiplier 由 `*4` 加到 `*8`(≈240ms → 480ms native buffer
  headroom)
- `PREBUFFER_CHUNKS` 由 2 加到 3

**修正二:三邊音質統一由 16kHz 降至 8kHz**(應你要求,同時針對「聽聲慢」呢個
之前確認過根源喺瀏覽器 `decodeAudioData()` 追唔切到達速度嘅問題):
- `AudioController.java`(耳筒錄音)、`AudioPlaybackController.java`(講嘢/
  TTS 播放)、`app.js` 嘅 `TALK_TARGET_SAMPLE_RATE`(講嘢上載downsample 目標)
  三處由 `16000` 一齊改做 `8000`——三者必須一致,否則會出現速度/音調唔對嘅
  情況。
- `JITTER_BUFFER_CAP_BYTES` 嘅 bytes/sec 公式(原本寫死 `32000`,即 16kHz 嘅
  bytes/sec)已同步改做 `16000`,確保呢個 cap 依然對應返原本設計嘅 ~600ms,
  唔會因為 sample rate 改變而意外變成雙倍。

**預期效果**:
- 8kHz 令所有 buffer(播放/上載)喺相同 bytes 下代表雙倍時長,直接加大晒
  對抗網絡 jitter 嘅 headroom(同修正一疊加,唔係互相取代)。
- 每個音頻 chunk 嘅實際數據量減半,`decodeAudioData()` 要處理嘅 bytes 都
  跟住減半,如果之前嘅延遲累積係源於呢部機(RK3288)CPU decode 追唔切到達
  速度,應該有直接改善。
- 代價:音質降為電話級語音(少咗高頻),人聲會悶少少但仍然清晰可辨。

已用 jsdom 確認:downsample 運算喺 8kHz target 底下正確(48kHz 輸入 1 秒
產生啱好 8000 個 sample),同埋每個典型 chunk 嘅 bytes 確實減半(1364 bytes
對比之前 2730 bytes)。未驗證:實機上實際嘅斷續/延遲改善程度,同 8kHz
音質喺你把聲上聽落嘅主觀效果——呢啲要你裝返新 APK 實測先知。如果你想微調,
`AudioController.java`/`AudioPlaybackController.java`/`app.js` 三處嘅
sample rate 常數已經清楚標明,可以一齊改返其他數值再試(記住三處要保持一致)。

## 播聲 session 中途長時間停頓（4秒+）嘅主動偵測 + 補齊過程紀錄

### Idle-detection 完整實現

根據 `logcat_2026-07-02_09-37-40.txt` 進一步分析,`AudioPlaybackController`
嘅 underrun 前面每次都跟住一個**遠超網絡 jitter 級數**嘅 upload gap
(569ms、1.4s、1.9s、4.0s、4.3s),期間完全冇任何其他 HTTP 活動——呢個唔係
「buffer 太細」,而係用戶講嘢中途真係有長時間停頓(可能係瀏覽器分頁失去
focus 令 `onaudioprocess` 被節流,或者純粹講嘢中途唞氣好耐)。任何合理大細
嘅 buffer 都吸收唔到呢種級數嘅斷層。

**修正**(`AudioPlaybackController.java`):`writeLoop()` 重構做兩個方法:
- `prebufferThenPlay()` — 抽出原本 session 開始時嘅「等夠 `PREBUFFER_CHUNKS`
  先 `play()`」邏輯,做成可重複調用嘅方法
- 主 drain loop 加入 idle 計時(`IDLE_POLL_MS`/`IDLE_PAUSE_MS = 300ms`):
  如果連續 300ms 攞唔到新 chunk,就**主動** `audioTrack.pause()`(換嚟乾淨
  嘅靜默,而唔係任由佢 underrun 產生刺耳嘅雜音/爆音),然後重新行
  `prebufferThenPlay()` 等新數據到嚟先恢復播放
- `finishAndReleaseTrack()` — 抽出共用嘅收尾清理邏輯,避免依家有多個 return
  出口都要重複同一段 stop/release code

### 呢次嘅補齊過程

因為對話中途你上傳咗一個新 zip(`Alpha2TestPanel_asr_fix2.zip`,包含你哋
自己開發嘅 ASR 新功能),而果個 zip 嘅 base 版本早過上面呢幾輪音頻修正,所以
呢個 README 同對應嘅六個檔案(`AudioController.java`、
`AudioPlaybackController.java`、`HttpServer.java`、`app.js`、`README.md`,
另加 `index.html`/`MainActivity.java` 純屬 ASR 新增、未郁)已經重新逐一比對
`diff` 並補返以上全部音頻/HTTPS 相關修正,ASR 相關內容完全冇改動。

**呢次額外做咗嘅驗證**(相比之前幾輪,呢次首次成功用 `jdk.compiler` module
+ 手寫嘅 Android API stub class,喺冇 Android SDK 嘅沙盒環境入面完整**編譯**
咗 `AudioController.java`、`AudioPlaybackController.java`、`HttpServer.java`
三個檔案,confirm 咗語法完全正確、`prebufferThenPlay()`/
`finishAndReleaseTrack()` 呢兩個新方法嘅所有調用/定義一致),加上一直有做嘅
`node --check`(`app.js` 語法)同 jsdom 完整場景測試(8kHz 常數、
`micPendingChunks`/`micDrainLoop` 冇並行 decode、echo fix 嘅 `micMuted`、
crash fix 嘅 try/catch 回滾)。

## 已知限制

- 兩代機身韌體（Alpha2 / Lynx）唔會自動偵測，要手動揀 backend；一部機同一時間
  淨係跑緊其中一代，揀錯個 tab 會全部 API call 失敗。
- 冇自訂語音詞彙——Nuance 呢邊用寫死嘅 VoCon 語法（`initSpeechGrammar`/
  `startSpeechGrammar` 喺 Nuance binding 之下係空 stub），要試 grammar 式辨識要
  自己先手動切去 iFlytek engine（「語音輸入三合一測試」唔會幫你自動切）。
- 冇伺服角度/電流回授、Alpha2 呢邊冇聲納實際距離數值（只有 boolean 觸發）。
- `action/list` 用咗一個最多等 5 秒嘅 blocking wait（AIDL callback 本質係 async），
  如果機器人服務初始化好慢，第一次攞列表可能會 timeout 返空列表——可以再按一次。
- Blockly 積木編程頁淨係支援 Alpha2 backend。
