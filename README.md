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

## HTTPS / 麥克風（已移除）

> **2026-08 更新**：呢個 App 曾經有一套自簽憑證 + `SSLServerSocket` 嘅 TLS
> 支援（`SelfSignedCert.java`/`TlsSupport.java`），原意係俾瀏覽器嘅
> `getUserMedia()` API（walkie-talkie 用瀏覽器 mic 錄音必須嘅安全來源
> secure context）可以喺 `https://<機械人IP>:8888/` 用到。依家已經**整套
> 刪除**——裝置上嘅瀏覽器對呢個自簽憑證持續 reject 新 TLS 連線
> （`SSLHandshakeException: certificate unknown`，見 `HttpServer.java`
> constructor 個 comment），而 TLS 存在嘅唯一原因（walkie-talkie）本身已經
> 永久喺 UI 停用咗（見下面「已知限制」），所以索性連 TLS 呢層都一齊拆走，
> 唔再保留做死 code。依家 App 淨係用 plain HTTP（`http://<機械人IP>:8888/`），
> 開機畫面顯示嘅網址永遠係 `http://` scheme，冇任何憑證警告要處理。

## Walkie-talkie 音質修正（斷斷續續 + 延遲累積）—— 歷史記錄

> **2026-08 更新**：本節同下面幾節（「Walkie-talkie 音質修正」「用電腦 mic
> 講嘢有回音修正」「講嘢 crash 之後永久卡死修正」）記錄嘅係瀏覽器 mic → 機械人
> 喇叭呢個方向嘅語音功能（`startTalk()`/`app-mic.js` 嘅 push-to-talk 咪掣）
> **喺修過呢啲問題之後、之後嘅版本又整套永久停用咗**——`startTalk()` 而家係
> 一個無條件 `return;` 嘅 no-op，UI 個咪掣一開頁就強制 `disabled`（見
> `disableTalkFabIfInsecureContext()`），原本做嘢嗰個版本改咗名做
> `startTalkDisabled_unused()` 留底但唔會再被 call 到。**OpenLynx 依家冇
> walkie-talkie 功能**，以下內容純粹保留做歷史 debugging 記錄（呢啲修正本身
> 都仲啱，只係之後成個功能被上層決定停用咗），唔反映依家嘅實際行為，詳見
> 「已知限制」一節。

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

## 相機頁面 UI 重整（浮動掣 + Keyboard 控制）—— 歷史記錄

> **2026-08 更新**：本節記錄嘅耳筒 icon（🎧 聽機械人）同咪 icon（🎤 講嘢）依家
> 都已經唔存在——聽機械人（`AudioController.java`）已經連檔案帶死 code 一齊
> 刪走；咪 icon/講嘢功能已經永久停用，`app-mic.js` 淨低一個 `startTalk()`
> no-op guard（防止萬一有第啲入口漏 check），成套錄音/downsample/
> `stopTalk()` 邏輯同埋 `app-camera.js` 綁住 `talkFab`/Space鍵嘅死 event
> listener都已經清走（見上面「Walkie-talkie 音質修正」節頭嘅 notice）。
> 相機分頁依家得返鏡頭串流、拍照、錄影、解像度切換、頭部
> pan/tilt 拖曳搖桿/keyboard 控制,冇耳筒/咪呢兩粒浮動 icon。以下內容純粹保留
> 做歷史記錄。

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

## 用電腦 mic 講嘢有回音（Echo）修正 —— 歷史記錄

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

## 講嘢 crash 之後永久卡死（無法再發射）修正 —— 歷史記錄

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

> **2026-08 更新**：本節記錄嘅「聽機械人麥克風」（耳筒收聽環境聲）功能已經喺
> Lynx-only 修訂移除（見「已知限制」一節解釋原因）。以下內容純粹保留做歷史
> debugging 記錄，唔再反映依家嘅程式行為。

### 聽聲延遲累積嘅真正根源(唔喺播放層,而係 decode 並行)

之前修過 walkie-talkie 播放端（`AudioPlaybackController`，播放瀏覽器 mic
錄音落嚟嘅聲音,經機械人喇叭放出）嘅延遲累積問題,
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

## 音質降至 8kHz + 播放 buffer 加大（應對「聽聲慢」+「播聲斷續」）—— 歷史記錄

> **2026-08 更新**：本節同下面「播聲 session 中途長時間停頓」節記錄嘅
> `AudioPlaybackController`（播放瀏覽器 mic 錄音落嚟嘅聲音，經機械人喇叭
> 放出——即係 walkie-talkie 嘅**播放/收音端**，同 TTS 播放係兩件完全獨立嘅
> 嘢，TTS 用嘅係另一套 `AndroidTtsHandler`/Android 系統 TTS engine，唔經
> `AudioPlaybackController`）依家已經永久停用（見「已知限制」）。以下內容
> 純粹保留做歷史 debugging 記錄。

根據新 logcat（`logcat_2026-07-30_08-43-18.txt`）,播聲（機械人播放瀏覽器 mic
錄音）**喺 session 中途**（唔係之前修過嗰個「session 開始就 underrun」）都出現
`releaseBuffer() ... disabled due to previous underrun, restarting`,前一個
upload gap 只係 234ms,已經足以榨乾當時嘅 buffer(舊設定 `bufBytes = minBufBytes
* 4` ≈ 240ms,幾乎冇 headroom)。

**修正一:播放 buffer 加大**(`AudioPlaybackController.java`):
- `bufBytes` multiplier 由 `*4` 加到 `*8`(≈240ms → 480ms native buffer
  headroom)
- `PREBUFFER_CHUNKS` 由 2 加到 3

**修正二:三邊音質統一由 16kHz 降至 8kHz**(應你要求,同時針對「聽聲慢」呢個
之前確認過根源喺瀏覽器 `decodeAudioData()` 追唔切到達速度嘅問題):
- `AudioController.java`(耳筒錄音)、`AudioPlaybackController.java`(walkie-talkie
  播放)、`app.js` 嘅 `TALK_TARGET_SAMPLE_RATE`(講嘢上載downsample 目標)
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
  stopTalk();` cleanup guard——全部都係已停用 walkie-talkie 嘅死觸發入口，
  刪走之後 `app-mic.js` 淨係留低一個 `startTalk()` no-op guard（見上面
  「Walkie-talkie 音質修正」節頭嘅 notice）
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
  instantiate/`shutdown()` 嘅引用一齊刪走（2026-08 死 code 清理）。
- 「聽機械人麥克風」（耳筒收聽環境聲）功能已經喺呢個修訂移除——原本呢個功能靠
  Alpha2 專屬嘅 `speech_SetMIC()` AIDL call 去釋放/收返機械人自己嘅 mic，Lynx
  AIDL SDK 冇對應方法，所以呢個功能喺 Lynx-only 版本冇得保留。對應嘅
  `AudioController.java` 呢個 class 檔案已經連埋 `MainActivity.java` 入面
  instantiate/`shutdown()` 嘅引用一齊刪走（2026-08 死 code 清理）。
- **冇 walkie-talkie 功能**（瀏覽器 mic → 機械人喇叭）——**成套實作已經徹底
  刪走，唔止 UI 停用**。呢個功能之前試過修過幾輪音質/回音/crash 問題（見
  上面幾個歷史記錄章節），之後嘅版本先永久停用；**2026-08 完整清理**（分兩輪
  做）：
  - 前端 `app-mic.js`：`startTalkDisabled_unused()`、`stopTalk()`、
    `downsampleToInt16()`、所有錄音相關全域變數/常量（`talkStream`/
    `talkAudioContext`/`talkProcessorNode`/`talkSourceNode`/
    `TALK_TARGET_SAMPLE_RATE`）、以及**最後淨低嘅 `startTalk()` no-op guard
    本身**，全部刪走，成個檔案而家淨係得 `toggleCameraFullscreen()`。
  - 前端 `app-camera.js`：綁住 `talkFab` 嘅 4 個 pointer event listener、
    Space鍵 keydown/keyup 分支、3 處 `if (talkActive) stopTalk();` cleanup
    guard、`talkFab` 嘅 element lookup，全部刪走。
  - 前端 `app-log.js`：`disableTalkFabIfInsecureContext()` 刪走（`talkFab`
    元素本身已經唔存在，冇嘢可以 disable）。
  - 前端 `index.html`：`talkFab`（🎤）呢粒 UI 掣本身刪走。
  - 後端 `MainActivity.java`：`audio/testtone`、`audio/diagnose`、
    `audio/play/start`、`audio/play/stop` 四個 API endpoint、
    `/upload/audio` handler（`handleUpload()`）、`releaseMicForAudioIo()`
    helper，全部刪走；`HttpServer` constructor 嘅 `RawUploadHandler` 改傳
    `null`（`HttpServer` 本身對 `null` 有 guard）。
  - 後端：**`AudioPlaybackController.java` 成個檔案刪走**——上一輪清理曾經
    以為呢個 class 嘅 HTTP endpoint 係「獨立可用嘅 API surface」而保留低，
    但成個 project 冇任何文檔/客戶端會咁樣用，實際上都係死 code，呢輪一併
    清走。
  - 後端 `LynxController.java`：`isSharedHardwarePath()` 入面殘留緊
    `audio/testtone`/`audio/diagnose`/`audio/play/` 嘅路由判斷（前一輪清理
    漏咗嘅一處）都已經刪走。
  - 連帶支援呢個功能嘅 HTTPS/TLS（`SelfSignedCert.java`/`TlsSupport.java`）
    之前已經整套刪走（見上面「HTTPS / 麥克風」一節）。
  - 成套清理之後，`app-mic.js`/`app-camera.js`/`app-log.js`/`index.html`/
    `MainActivity.java`/`LynxController.java`/`HttpServer.java` 已經重新用
    `node --check`（JS）同去除 comment/string 之後嘅大括號/括號平衡驗證
    （Java）確認，加埋一個自動化交叉比對腳本（top-level function/變數定義
    vs 全 project 引用次數）掃描確認冇再殘留任何死引用。
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
