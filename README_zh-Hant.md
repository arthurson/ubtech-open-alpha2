# Open Alpha2

> 語言：[English](README.md) | 繁體中文（本檔）

`com.open.alpha2`（`versionName "beta 8.3"`、`versionCode 9`）係裝喺 UBTECH Alpha2
機械人上嘅 Android 應用程式。開機後自動啟動 HTTP＋WebSocket 伺服器（port `8888`）；
同一 Wi-Fi 下用瀏覽器開 `http://<機械人IP>:8888/` 就有完整控制面板。
`/blockly.html` 係積木編程頁，`/docs` 係 OpenAPI 嘅 Swagger UI。

## 前提條件

- 機械人：`alpha2_10005` 5-mic（Android 5.1.1）或 `alpha2_10002` 2-mic
  （Android 4.4.2）；只支援 `armeabi-v7a`。App 開機讀 `ro.hardware.version`，
  自動揀對應串口／JNI 路徑。`minSdk 19`／`targetSdk 22`，用 `adb install`
  安裝（不上架 Play Store）。
- 接線：胸板 MCU＋頭板 MCU 串口 115200 baud（`ttyS1`／`ttyS3`；2-mic 為
  `ttySAC0`／`ttySAC1`），LED 同頭頂鍵經 JNI，頭頂＋/－觸控鍵為
  `/dev/input/event0`。
- 機械人同瀏覽器要喺同一 Wi-Fi。

## 安全

- **只限可信內網**——純 HTTP，口令明文傳送。
- 面板預設受口令保護。首次啟動機械人會喺自己螢幕顯示口令（`XXXXX-XXXXX`），
  瀏覽器輸入一次即可。所有 `/api/*`、`/upload/*`、`/stream/*`、`/ws` 都要
  `Authorization: Bearer <token>`（`<img>`／WebSocket 用 `?panel_token=`）。
  可喺「實驗」tab 更改或停用。亂猜口令會按 IP 節流（HTTP 429＋`Retry-After`）。
  清除 App 資料會重新產生口令。
- 下載（動作包、Google TTS APK、Vosk 模型）用嚴格 TLS（系統 CA＋內置 Mozilla
  CA 列表，用 `scripts/update-cacert.py` 更新），限制重定向，解壓有 zip-slip
  同大小限制。下載報證書錯誤，先檢查機械人時鐘。
- **已知缺口**：小智 OTA＋WebSocket 連線未驗證證書（`XiaozhiTrustAllSsl`）。

## 編譯／安裝

Temurin JDK 11、Gradle 7.0（wrapper）、AGP 4.2.2、Android SDK（`compileSdk 25`）、
NDK `21.4.7075529`、CMake `3.10.2.4988404`。Vosk／JNA AAR 已放入 `app/libs`，
`--offline` 編譯唔使 Maven。

```bash
./gradlew assembleDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.open.alpha2/.MainActivity
adb forward tcp:8888 tcp:8888          # 可選，用 USB 連面板
```

用已入庫嘅 debug key `app/debug.keystore`（密碼 `android`）簽名；簽名衝突就先
uninstall。App 開機自動啟動。出貨前行 `./gradlew clean assembleDebug --offline`
（增量編譯會令 APK 變大）。CI（`build-apk.yml`）會清走 web 註解、編譯並上傳
`open-alpha2-beta8.3.apk`。

## 控制面板（`app/src/main/assets/web/`）

中／英雙語，10 個 tab，經 WebSocket 即時更新。

- **狀態** — 語言、音量、裝置資訊、聲納圖、PIR 警報、加速度計圖（傾斜 → LED）、
  跌倒自救開關。
- **動作** — 按類別嘅動作列表、播放／停止、速度 0.5–2.0x。
- **舵機** — 22 軸即時拖動；實時角度讀回（`servo/back-angle-all`、`servo/angle`、
  `servo/angle-restore`）；舵機固件版本／硬件資訊、改 ID、單舵機固件燒錄；
  IK 姿勢滑桿（蹲／傾、蹲／劈叉、踢腿、側弓步等）。已關閉嘅舵機（見「實驗」）
  喺呢度會隱藏滑桿。
- **手柄** — 藍牙手柄直連機械人，或者瀏覽器端手柄／鍵盤；可編輯按鍵映射
  （舵機、IK、音樂、LED、動作、拍照、追蹤、全部停止）；按住前行／後退。
- **語音** — 對話介面、Vosk 離線語音識別、TTS 選擇。
- **音樂** — 本地音樂（拖放上傳）、網絡電台、Internet Archive 曲目、5 段均衡器、
  隨機動作同 Disco LED 選項。
- **LED** — 頭／眼／嘴預設效果。
- **相機** — MJPEG 串流、預覽截圖、高解像度拍照、mp4 錄影、縮放、雲台搖桿、
  收聽機械人咪高峰、人臉追蹤同顏色追蹤（可選跟行）。
- **小智** — AI 語音對話橋接：配對、連線、文字對話、TTS 輸出選擇、自訂
  OTA／伺服器、逐個 MCP 工具啟用／停用。
- **實驗** — 面板口令、開機語音模式、資源下載（Vosk 模型、動作包、Google TTS
  APK）、**舵機開關**（預設 1–20 開、21–22 關；關＝先令舵機脫力，之後完全唔發
  TX/RX——API、動作、IK、手柄同讀取全部略過；重啟保持）、22 軸角度微調（含
  offset 備份／還原）、機械人 ID 修改、胸板固件升級、ONNX policy 卡、事件日誌。
- **Blockly**（`blockly.html`）— 用同一套 API 嘅積木編程。

實驗性（未經真機驗證）：ONNX policy 播放（`policy/*`，見
[docs/onnx-policy.md](docs/onnx-policy.md)）、跌倒自救（`fall/*`，預設關，需要
`BackStand`／`FrontStand` 動作）、後退行走。

## 語音

- Vosk 離線識別（模型放 sdcard，或喺「實驗」tab 下載；需要 API 21+）。
- 回覆用 Android 系統 TTS。
- 10 種語言本地意圖匹配（zh/en/es/fr/ja/de/it/pt/ko/ru）：每種語言 86 個動作意圖、
  13 個功能意圖（停止、音量、藍牙、Wi-Fi、拍照、音樂）同 200 個閒聊意圖。
  冇匹配就回覆備用句並做隨機動作（閒聊後嘅隨機動作可經面板開關或
  `speech/semantic_random_move/set?enabled=false` 閂咗，明講要做嘅動作唔受影響）。
  冇咪高峰都可測試：`speech/semantic_simulate`。
- 頭頂鍵：＋/－調音量；兩粒同時按＝全部停止。

## HTTP 接口

唯一來源：`openapi/open-alpha2-openapi.yml`（**238 個 path**：`/api/*` 228 個，
另有 `/upload/*`、`/stream/camera`、`/stream/mic`、`/ws`）。`/api/system/*`、
`/api/direct/*`、`/api/xiaozhi/*` 係獨立家族；瀏覽器端其餘接口用
`/api/alpha2/*` 前綴。

機械人本機提供：`/docs`、`/openapi.yml`、`/asyncapi.yml`、
`/.well-known/openapi.json`、`/apis.yml`、`/llms.txt`。

`assets/web/api-client.js`（228 個 typed wrapper）同 `McpToolsGenerated.java`
（22 個 MCP 工具）都係生成檔——唔好手改：

```bash
python scripts/generate-api-client.py          # 加 --check 驗漂移
python scripts/generate-mcp-tools.py           # 加 --check 驗漂移
python scripts/check-openapi-drift.py          # 程式碼 ↔ spec
python scripts/check-api-client-routes.py      # wrapper 前綴 ↔ 路由
python scripts/check-spec-copies.py --fix      # spec 嘅 web 副本
```

## 小智 AI 橋接＋MCP

將機械人接駁小智後端做開放式語音對話。經 MCP 提供 22 個工具：`self.robot.*`
（動作、舵機、LED、說話）、`self.sensors.*`（PIR／聲納）、`self.camera.*`
（拍照、看圖問答）、`self.media.*`（本地音樂／電台）。

## 測試同腳本

- JVM 單元／e2e 測試（唔使 Android）：`scripts/test-apivalidator.py`
  （app 測試＋`app/src/e2e`）、`test-serialcodec.py`、`test-headkeypoller.py`、
  `test-policy.py`、`test-gaitwalker.py`。
- `policy_tool.py` 打包 policy 俾 `policy/load` 用；`ubx_*.py` 分析 `.ubx` 動作檔；
  `ubx_to_gait.py` 生成 `assets/gait/walk_*.json`。
- CI `check-openapi.yml` 跑漂移檢查、YAML 驗證、上面所有測試，同
  `versionName` == spec 版本檢查。

## 機身資料位置

- 動作：`/sdcard/actions/`（`<id>.ubx`＋音樂＋`actionInfo.txt`）。可選動作包下載
  會取代佢（舊嘅改名 `/sdcard/actions-backup`）。
- 音樂：`/mnt/internal_sd/music`。相片／影片：`/sdcard/DCIM/Alpha2/`。
- 胸板固件暫存：`/sdcard/AlphaII_CHEST_kernel.bin`（每次燒錄都要重新上傳）。
  Vosk 模型：sdcard 頂層。

## 專案結構

```
app/                         UI 伺服器、API、所有 center（com.open.alpha2）
  src/main/assets/web/       控制面板＋Blockly＋生成嘅 api-client.js
  src/main/assets/semantic/  10 個意圖庫
  src/main/cpp/              easyopus（Opus JNI）
sdk-module/hardware-direct/  串口、LED／頭頂鍵 JNI、wire protocol、
                             .ubx 播放、步態、ONNX policy 執行
openapi/                     OpenAPI／AsyncAPI／MCP sync spec
scripts/                     生成器、檢查、測試 runner、ubx 工具
docs/                        onnx-policy.md、AlphaDeus 比較、legacy-beta3
```

## 已知限制

- 純 HTTP，所以瀏覽器咪高峰對講用唔到（收聽機械人咪高峰就得）。
- 冇重啟／關機（side-load 嘅 App 冇 `REBOOT` 權限）。
- 冇舵機電流回饋；舵機 tab 顯示指令姿勢，另可按需讀角度。胸板 `cmd6` 讀取會令該
  舵機短暫放鬆——要保持上力就用 `servo/angle-restore`。
- 燒錄胸板固件後要重新開關機；電源燈 藍 → 紅 → 綠，常綠＝完成。

License: GPL-3.0-only.
