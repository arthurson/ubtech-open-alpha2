# OpenLynx

UBTECH Lynx 機械人嘅網頁控制面板。App 喺機身跑一個內嵌 HTTP + WebSocket
server，將一個純 HTML/JS 前端橋接去機身嘅 AIDL 服務（動作、舵機、語音、LED、
系統資訊）同 Android 硬件（相機）。同一個 WiFi 網絡入面用手機/電腦嘅瀏覽器開
個網址就用得，唔使裝額外 app。

## 支援嘅機械人 / 前提

- UBTECH Lynx（機身固件 3.0.0.2）。呢個 App 淨係支援 Lynx，唔支援 Alpha2。
- 機身要有正常運作嘅 WiFi，同呢個 App 裝喺同一部機身上面（App 本身喺機身度
  跑，唔係遙控第二部機）。
- 用嚟開網頁嘅裝置（手機/電腦）要同機身喺同一個區網。
- `minSdkVersion 19`（Android 4.4）、`targetSdkVersion 22`，淨係支援
  `armeabi-v7a`（RK3288，32-bit ARM）。

## Build 方法

```
./gradlew assembleDebug
```

APK 喺 `app/build/outputs/apk/debug/app-debug.apk`。用 `adb install` 裝落機身，
或者直接複製個 APK 檔案去機身上面安裝。

`applicationId` 係 `com.open.lynx`，App 名 OpenLynx。

## 用法

裝好個 APK、喺機身度打開個 App，開機畫面會顯示一個網址（`http://<機身IP>:8888/`），
喺同一個 WiFi 嘅手機/電腦瀏覽器輸入呢個網址就會見到控制面板。面板分 6 個分頁：

- **📊 狀態**：一個簡單嘅後端連線確認（顯示 `{"ok":true,"backend":"lynx"}`）、
  裝置資訊（SID、電池版本、電量、充電狀態、MIC/頭部/胸部版本）、PIR 人體
  感應器（開關 + 警示 LED/鈴聲 + 即時指示燈）、加速度計（即時 X/Y/Z 讀數 +
  圖表，可以加開「4角度傾側著頭/眼LED」）、中文/English 語言切換。
- **🕺 動作**：撳「攞動作列表」讀取機身內建嘅全部動作，按 5 大分類（基本/
  跳舞/故事/瑜伽/其他）+ 子分類分頁瀏覽，撳個動作即刻播放（撳新嘅會自動停低
  舊嘅先播），亦可以自行輸入動作 ID 播放。
- **⚙️ 舵機**：20 顆舵機獨立滑桿控制角度，拖動放手即送出；「讀取所有角度」
  一鍵掣會逐顆舵機讀返實際角度顯示喺滑桿旁邊（機身 AIDL 冇批量讀取方法，呢個
  掣係逐顆錯開發送 request 做到嘅效果）；「全部回到中位」一鍵掣重設晒去校準
  中心點；有省電開關。
- **🗣️ 語音**：文字轉語音（Android 內置 TTS），可以揀 TTS 引擎（如果機身裝咗
  多於一個）同語言（列表反映機身實際裝咗嘅嘢，唔係寫死清單）。
- **💡 LED**：頭部/眼睛/咀部/WiFi 燈四組獨立控制。頭部、眼睛有顏色、光度、
  速度調校，preset 分別係「長開/閃燈/呼吸燈/跑馬燈/停止」（頭部）同「長開/
  眨眼/閃燈/跑馬燈/停止」（眼睛）；咀部單色，有光暗/速度/OffTime 三個滑桿，
  preset 係「長開/呼吸燈/停止」；WiFi 燈得返紅/藍兩粒色掣。
- **📷 相機**：即時串流、拍照、錄影，解像度可以喺 320×240 到 2064×1548 之間
  切換；一個可拖曳嘅頭部瞄準搖桿（同鍵盤方向鍵）控制頭部 pan/tilt。

底部有一個常駐嘅「即時事件 Log」面板（跨分頁都見到），顯示 WebSocket 送嚟嘅
即時事件（動作播放進度、舵機讀值、PIR 觸發、加速度讀數等）。

## 檔案結構

```
open-lynx/
├── app/
│   └── src/main/
│       ├── java/com/open/lynx/
│       │   ├── MainActivity.java            — App 生命週期 + shared-hardware API 路由
│       │   ├── LynxController.java          — Lynx AIDL API 路由
│       │   ├── HttpServer.java              — 零依賴 HTTP server（純 HTTP）
│       │   ├── WebSocketServer.java         — 手寫 RFC 6455 WebSocket
│       │   ├── EventBus.java                — pub/sub 事件中樞
│       │   ├── RobotEventReceiver.java      — 接收機械人 broadcast
│       │   ├── RobotWireConstants.java      — 機身底層 broadcast action/extra 常量
│       │   ├── CameraController.java        — 相機串流/拍照/錄影
│       │   ├── BootReceiver.java            — 開機自動啟動
│       │   └── MouthLedData.java            — 咀部 LED preset 資料
│       └── assets/web/
│           ├── index.html                   — 主頁面（全部 6 個分頁 + 事件 log）
│           ├── style.css
│           ├── app-core.js                  — 全局狀態、i18n 字典、servo 校準表、
│           │                                    lynxApi()/hwApi() 核心 API helper
│           ├── app-lynx.js                  — 狀態/動作/舵機/語音/LED 全部邏輯
│           ├── app-accel.js                 — 加速度計圖表
│           ├── app-camera.js                — 相機串流/拍照/錄影/頭部瞄準
│           ├── app-mic.js                   — 相機全螢幕切換
│           ├── app-status.js                — 分頁切換
│           ├── app-log.js                   — WebSocket 事件 log、頁面初始化
│           └── action_classification.json   — 動作分類表
├── sdk-module/lynxrobot/                    — UBTECH 官方 Lynx AIDL SDK（27 個
│                                               .aidl 介面），唔屬於呢個 App 本身
├── AIDL_GUIDE_LYNX.md                       — AIDL 介面用法/已驗證得失參考
└── README.md
```

## 已知限制

- 冇伺服角度/電流嘅**持續**回授（機身冇呢類 push 機制）——但舵機分頁嘅
  「讀取所有角度」一鍵掣可以隨時主動讀返全部 20 顆嘅實際角度（見上面「用法」）。
- `action/list` 用咗一個最多等 5 秒嘅 blocking wait（AIDL callback 本質係 async），
  如果機器人服務初始化好慢，第一次攞列表可能會 timeout 返空列表——可以再按一次。
- 冇麥克風/walkie-talkie 功能（機身麥克風收聽、瀏覽器 mic 對講）。Lynx AIDL SDK
  冇對應嘅 mic 釋放/收返方法，呢類功能喺呢個 Lynx-only 版本冇得做。
- 純 plain HTTP，冇 HTTPS（機身瀏覽器對自簽憑證唔穩定支援，索性唔用 TLS）。
- 狀態分頁嘅加速度計卡片附近，程式碼入面有一套完整嘅「聲納避障」（sonar
  obstacle）事件監聽同紫色 LED 觸發指示邏輯，源自機身確實會發送
  `com.ubtechinc.sonar.distance` broadcast；但對應嘅設定/圖表 UI 未接駁（前端
  冇觸發距離設定介面，圖表 canvas 都未加入頁面），實際運作狀況未完整驗證。

## AIDL 參考

`AIDL_GUIDE_LYNX.md` 有齊 Lynx AIDL SDK（`sdk-module/lynxrobot`）每個介面/
方法嘅用法示範，連同喺真機驗證過嘅已知得失（邊啲方法可靠、邊啲唔可靠、有咩
要注意嘅坑）。
