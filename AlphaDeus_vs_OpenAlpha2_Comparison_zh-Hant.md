# Alpha Deus Revival 與 Open Alpha2 全面比較

## 功能、安全性、代碼完整度

**製作日期：** 2026-11-10  
**本地套件：** `C:\Users\user\Desktop\alpha2\Program-Arthur\Program`  
**GitHub 專案：** `https://github.com/arthurson/ubtech-open-alpha2`  
**分析版本：** `main` 分支 commit `484c6e2`，beta 8.1，2026-11-01

> 本文根據本地安裝腳本、APK manifest、PDF 手冊，以及 GitHub 上的公開源碼、README、OpenAPI、CI workflow 與 release metadata 進行靜態分析。並未在實機上做硬件測試。  
> 符號：✅ 有／原生　🟡 部分／未驗證　❌ 未見／沒有　❓ 文件未提／無法確認

---

## 1. 摘要

這兩者**不是同一個專案的兩個版本**，而是用完全不同的方法解決同一個問題。

| | Alpha Deus Revival | Open Alpha2 |
|---|---|---|
| 本質 | Windows 安裝包，會修改原廠 UBTECH Android 系統，並安裝改裝版 `com.ubtechinc.alpha2services` | 獨立開源 Android App `com.open.alpha2`，直接控制機械人硬件 |
| 安裝方式 | Windows 管理員權限 + ADB root + `/system` remount + 修改 `services.jar` | 單一 APK，普通 `adb install` |
| 系統改動 | 有：簽名繞過、system UID、persistent 系統 App | 無：普通 sideload App，免 root、免改 system |
| 控制介面 | 瀏覽器 console，installer 指示 port 8080 | 瀏覽器面板 port 8888，10 個分頁＋Blockly |
| 源碼 | 沒有；只有 binary APK＋閉源 `patcher.exe` | 完整 Gradle 專案、GPL-3.0、CI、測試、OpenAPI |
| 適合 | 保留原廠 UBTECH 語音／行為、Lynx 回復、簡單本地復活 | 安全長期使用、API 自動化、Blockly、小智 AI、2-mic／4.4 支援 |

**結論：** Open Alpha2 在功能、可維護性、安全性與代碼完整度上都明顯較強。Alpha Deus Revival 只在需要保留原廠 UBTECH service stack、系統級關機／重啟，或 Lynx 相容性時才有優勢，而且必須接受 root、改 system 與無源碼的風險。

---

## 2. 分析範圍與證據

### 2.1 本地套件證據

| 項目 | 發現 |
|---|---|
| 安裝器 | `install.bat`，標題為 **ALPHA DEUS REVIVAL - SYSTEM INSTALLER** |
| 安裝流程 | `adb root`、`adb remount`、備份 `/system/framework/services.jar`、apktool 反編譯、`patcher.exe`、重新編譯、推回 patched JAR、卸載 UBTECH 內建 App、推送 actions／Vosk、安裝三個 APK、改寫 Wi-Fi 設定、重啟 |
| 主 APK | `APKs/output.apk` = `com.ubtechinc.alpha2services`，versionName `1.1.7.3`，versionCode `20`，`sharedUserId="android.uid.system"`，`persistent="true"` |
| 其他 APK | `alp.apk` = `com.ubtechinc.alphaenglishchat` 1.0.3；`googletts.apk` |
| Console | `install.bat` 指示開啟 `http://<robot-ip>:8080` |
| Recovery | `Recovery.bat` 可從 `/system/framework/services.jar.bak`、`saved/services.jar` 或 `saved/lynx/services.jar` 還原 |
| 文件 | 8 頁 `Alpha Deus Manual and Release Notes.pdf` |
| 源碼 | 沒有。只有 batch／sh 安裝腳本與預編譯 binary |
| 授權 | 根目錄 `LICENSE` 是 Oracle JDK 17 授權，不是專案授權 |
| 體積 | 約 3,040 個檔案、0.8 GB；包含 JDK 17、platform-tools、apktool、smali／baksmali、d8、patcher、APK、actions、Vosk model |
| 可讀程式碼 | `install.bat`、`Recovery.bat`、`ForceFirstRun.bat`、`wifi_setup.sh` 合共約 566 行 batch／sh |
| 不可讀程式碼 | `tools/patcher.exe` 是 6.3 MB 閉源 binary |

### 2.2 GitHub 證據

| 項目 | 發現 |
|---|---|
| Package | `com.open.alpha2` |
| 版本 | `beta 8.1`，versionCode 9 |
| SDK | minSdk 19、targetSdk 22、armeabi-v7a |
| 授權 | GPL-3.0-only |
| 歷史 | 189 個 commits；release 由 beta 1 到 beta 8.1 |
| Repo 大小 | GitHub 約 57.7 MB；source archive 265 個檔案 |
| Java | 108 個 `.java`，約 52,163 行（含 SDK 與測試）；73 個 app classes |
| Web assets | 68 個檔案、約 3.5 MB；37 個 JS、約 25,290 行 |
| Python | 21 個檔案、約 3,382 行 |
| API spec | 目前 `open-alpha2-openapi.yml` 有 219 條 paths（README 仍寫 208） |
| 測試 | 8 個 Java 測試類 + 4 個 Python test runner |
| CI | `build-apk.yml`、`check-openapi.yml` |
| 文件 | 英文 README、繁中 README、OpenAPI、AsyncAPI、Swagger、`llms.txt` |

---

## 3. 架構

### 3.1 Alpha Deus Revival

```text
┌──────────────────────┐
│ Windows PC           │
│ install.bat          │
│ patcher.exe          │
└──────────┬───────────┘
           │ adb root / remount
           ▼
┌────────────────────────────────────┐
│ /system/framework/services.jar     │
│  + 簽名校驗繞過 patch              │
└──────────┬─────────────────────────┘
           │ 安裝 3 個 APK
           ▼
┌────────────────────────────────────┐
│ com.ubtechinc.alpha2services       │
│ 1.1.7.3                            │
│ system UID / persistent            │
│ + Vosk / OpenCV / 原廠 UBTECH 元件 │
└──────────┬─────────────────────────┘
           │ /dev/ttyS1、/dev/ttyS3
           ▼
      Alpha2 硬件

[Browser :8080] ── HTTP ──> Alpha Deus web console
```

**特點：** 保留原廠 UBTECH stack，patch 它，再在周圍加功能。

### 3.2 Open Alpha2

```text
[Browser :8888] ── HTTP / WebSocket ──> com.open.alpha2
                                           │
                                           ├─ /dev/ttyS1、/dev/ttyS3
                                           ├─ Vosk / TTS
                                           ├─ Xiaozhi / MCP
                                           ├─ Blockly / OpenAPI
                                           ▼
                                      Alpha2 硬件
```

**特點：** 乾淨的替換 App，直接控制硬件，不依賴 UBTECH service。

---

## 4. 功能比較

### 4.1 控制介面、API、自動化

| 功能 | Alpha Deus Revival | Open Alpha2 | 備註 |
|---|---|---|---|
| 瀏覽器控制台 | ✅ 8080 | ✅ 8888，10 個分頁 | 兩者都是 browser-based |
| 即時更新 | ❓ 未文件化 | ✅ WebSocket `/ws` | Open 有事件推送 |
| UI 語言 | 英文／西班牙文（PDF） | 英文＋繁中 | Open 文檔雙語 |
| Blockly 視覺編程 | ❌ 未見 | ✅ `/blockly.html`，12 類 toolbox | Open 獨有 |
| Panel token 認證 | ❌ 未見 | ✅ opt-in，保護 API／upload／stream／ws | 預設關閉 |
| HTTP API 文件 | ❌ 未見 | ✅ OpenAPI 219 paths＋AsyncAPI＋Swagger `/docs` | Open 獨有 |
| Typed JS client | ❌ | ✅ 199 個 wrappers | 由 spec 生成 |
| MCP 工具 | ❌ | ✅ 22 個 tools | 小智 AI 用 |
| 事件 Log | ❓ | ✅ 常駐 Event Log | |
| 下載管理 | ❌ | ✅ `DownloadGate` | Vosk／動作包／TTS APK |
| OTA／自我更新 | ❌ | ❌ 手動 APK／GitHub release | 兩者都沒有真正 OTA |
| 安裝難度 | 🔴 Windows admin＋ADB root＋改 system | 🟢 一個 APK `adb install` | 最大差異之一 |

### 4.2 動作、舵機、LED

| 功能 | Alpha Deus Revival | Open Alpha2 | 備註 |
|---|---|---|---|
| `.ubx` 動作播放 | ✅ | ✅ | 兩者都用 `/sdcard/actions` |
| 動作分類／清單 | ✅ | ✅ 5 大組 | |
| 播放速度控制 | ❓ | ✅ 0.5–2.0x，舵機＋mp3 同步 | |
| 動作包 | ✅ installer 推約 286 個 `.ubx` | ✅ 202 招下載包 | 本地有些重複／未索引 |
| IK 動作 | ❌ 未見 | ✅ 蹲、劈腿、踢、側弓步、半蹲、屈臂、前後一字馬 | Open 獨有 |
| Direct transition | ❌ | ✅ 由目前 pose 直接混合 | |
| Gamepad／鍵盤 | ❌ 未見 | ✅ BT HID＋瀏覽器 Gamepad API | |
| 步行前進／後退 | ✅ 語音指令 | ✅ hold-to-walk，速度 1.0–2.0x，700ms lease | Open 較安全 |
| 轉向／橫移 | ✅ 語音指令 | ❓ 未列 | |
| Emergency stop | ✅ | ✅ | |
| Sleep／Wake | ✅ | ❓ 未列 | |
| Reboot／關機 | ✅ 有 system UID／REBOOT 權限 | ❌ 無 API；manifest 有宣告但 privileged 不可用 | Alpha Deus 優勢 |
| 舵機 slider | ✅ | ✅ 22 軸分組 | |
| 角度回讀 | ❓ | ✅ `servo/back-angle-all`、單顆讀取 | |
| Trim／offset 校准 | ❓ | ✅ 備份／還原／factory-style calibration | |
| 舵機 ID 變更 | ❓ | ✅ | |
| 舵機 firmware | ❓ | ✅ 版本查詢＋單顆燒錄 | |
| Eye／LED 控制 | ✅ Eye Color | ✅ 頭／眼／嘴多種效果 | |
| All lights off | ❓ | ✅ | |
| LED 狀態輪詢／靜音鏡像 | ❌ | ✅ 100ms `led/state/get` | |
| 舵機電流回饋 | ❌ | ❌ | 兩者都沒有；Open 有文檔說明 |

### 4.3 相機、語音、音樂

| 功能 | Alpha Deus Revival | Open Alpha2 | 備註 |
|---|---|---|---|
| 即時相機 | 🟡 最新照片（Live Vision） | ✅ MJPEG 串流 | Open 明顯較強 |
| 拍照／儲存 | ✅ | ✅ 9 檔畫質 | |
| 錄影 | ❌ | ✅ 640×480 H264 mp4 | |
| 數碼變焦／雲台 | ❌ | ✅ | |
| 瀏覽器聽機械人 mic | ❌ | ✅ `/stream/mic` | |
| 人臉追蹤 | ✅ OpenCV | ✅ 內建 FaceDetector | |
| 顏色追蹤 | ❌ | ✅ HSV＋最大色塊，可 walk-follow | |
| 遠端 TTS | ✅ 英文／西班牙文 | ✅ engine／lang／voice，可跟 Vosk | |
| 離線 ASR | ✅ Vosk | ✅ Vosk | 兩者都有 |
| Wake word | ✅ 本地 wake word | 🟡 Vosk 自動監聽；未見專用 wake word | |
| 語音意圖 | ✅ PDF 列出 40+ 英語指令 | ✅ 10 語言 × 299 組 intent | |
| Virtual Input | ✅ 注入假語音指令 | ✅ `speech/semantic_simulate` | |
| Memory Manager | ✅ SQL 學習語句／回覆 | ❌ 未見 | Alpha Deus 獨有 |
| 對話 AI | 🟡 原廠／離線 TFLite | ✅ 可選小智 AI＋MCP 22 tools | |
| 音樂／電台 | ✅ Radio | ✅ 本地音樂／radio-browser／Internet Archive／EQ | |
| 鈴聲 | ❓ | ✅ 系統鈴聲／自訂 | |
| 5-band EQ | ❌ | ✅ | |
| Walkie-talkie 音頻上載 | ❌ | ✅ `/upload/audio` | |

### 4.4 系統、感測、相容性

| 功能 | Alpha Deus Revival | Open Alpha2 | 備註 |
|---|---|---|---|
| Wi-Fi 開關 | ✅ 語音 | ✅ API／語音 | |
| Bluetooth 開關 | ✅ 語音 | ✅ API／gamepad | |
| 電池／裝置資訊 | ❓ | ✅ 電池、Wi-Fi、BT、UUID、胸板 firmware | |
| Firmware 資訊 | ❓ | ✅ | |
| Robot ID 變更 | ❓ | ✅ 查詢／QR／EEPROM 寫入 | |
| 聲納 | 🟡 長按開關感測器 | ✅ 即時圖表 | |
| 加速度計 | 🟡 長按開關 | ✅ 即時圖表＋4 方向傾斜 LED | |
| PIR | ❌ | ✅ 警報（紅燈＋鈴聲） | |
| Boot voice mode | ❌ | ✅ 關／自動連小智／自動 Vosk | |
| Android 5.1 | ✅ 最佳化 | ✅ `alpha2_10005` 5-mic | |
| Android 4.4 | 🟡 PDF 說需手動調整 | ✅ `alpha2_10002` 2-mic | Open 明確支援 |
| Lynx | 🟡 PDF 註明部分功能可能不支援；有 Lynx `services.jar` 備份 | ❌ 未聲明支援 | Alpha Deus 較廣 |
| ABI | ✅ armeabi-v7a | ✅ armeabi-v7a | |
| 需要 root／改 system | 🔴 是 | 🟢 否 | 最大安全差異 |
| 可逆性 | 🟡 `Recovery.bat` 可還原，但有 bootloop 風險 | 🟢 直接 uninstall | |

### 4.5 各範疇功能評分

| 範疇 | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|
| 控制面板／API／自動化 | 4.0 | 10.0 |
| 動作與 motion | 6.0 | 9.5 |
| 舵機與 LED | 5.0 | 9.5 |
| 相機 | 4.0 | 9.5 |
| 語音與 AI | 6.0 | 9.0 |
| 音樂與音頻 | 4.0 | 9.0 |
| 感測器 | 3.0 | 9.0 |
| 硬件相容性 | 6.0 | 8.5 |
| 安全性 | 3.0 | 6.5 |
| 代碼完整度 | 1.0 | 9.0 |

> 以上是主觀評分，根據文件與可發現的功能，不是實驗室 benchmark。

---

## 5. 安全性比較

### 5.1 安全矩陣

| 安全項目 | Alpha Deus Revival | Open Alpha2 | 風險解讀 |
|---|---|---|---|
| 安裝權限 | 🔴 Windows admin＋`adb root`＋`remount` | 🟢 普通 APK 安裝 | 本地安裝本身就有高權限 |
| 改 `/system` | 🔴 改 `services.jar` | 🟢 不改 | 本地可能 bootloop |
| Signature Spoofing | 🔴 installer 自己標明 | 🟢 無 | 放寬 Android 簽名校驗 |
| App 身分 | 🔴 `android.uid.system`、`persistent=true` | 🟢 普通 UID | 本地 App 等於系統權限 |
| 權限數量 | 🔴 40 個 unique permissions | 🟡 16 個 | 本地包括 REBOOT、INSTALL_PACKAGES、DELETE_PACKAGES、INJECT_EVENTS、READ_CONTACTS、SEND_SMS、CALL_PHONE、MOUNT_UNMOUNT_FILESYSTEMS |
| Exported 元件 | 🟠 13 個：6 services、4 providers、2 activities、1 receiver | 🟢 1 個 exported receiver（BootReceiver） | 本地攻擊面大很多 |
| Web 認證預設 | 🔴 未見 token／登入說明 | 🟠 預設關閉（全開），可選 token | 兩者都應只放 trusted LAN |
| 認證實作 | ❓ 無法確認 | 🟢 constant-time 比對、8–64 字元、改 token 要舊值、log 脫敏 | Open 設計不錯；query fallback 仍可能入 URL |
| Panel TLS | ❓ 未見 | 🔴 純 HTTP，無 TLS | 同網段可 sniff token |
| 對外 TLS | ❓ 原廠 libs 不明 | 🟠 `XiaozhiTrustAllSsl` 接受任意憑證，且被 actions／APK／Vosk 下載器共用 | 有 MITM 風險 |
| 相機／mic 暴露 | 🟠 Live Vision；mic 功能未明 | 🟠 MJPEG＋mic stream；token 開啟後受保護 | 預設都可能被同網段存取 |
| Firmware 上載／燒錄 | ❓ 原廠功能未列 | 🟠 chest／servo 上載＋燒錄；token 保護；chest 只燒本次上載 | 惡意 LAN 用戶可攻擊 |
| 雲端／隱私 | 🟠 保留 iflytek／Nuance／XMPP 元件；service config 指 127.0.0.1；SQL memory；assets 有 `pkcs8_rsa_private_key.pem` | 🟡 可選小智雲端；Vosk 離線；mic 可串流 | 本地原廠遺留物較多 |
| 靜態資料 | 🟠 SQL 學習語句、動作、設定，未見加密 | 🟡 相片／影片／SharedPreferences，未見加密 | 兩者都沒有 at-rest encryption |
| 供應鏈 | 🔴 閉源 `patcher.exe`＋prebuilt APK＋原廠 closed libs | 🟡 原始碼公開、CI；但 prebuilt `.so`／AAR、公開 debug keystore、trust-all 下載 | 本地不可審計；Open 可審但非全源碼 |
| 更新機制 | 🔴 無版本歷史／更新通道 | 🟡 GitHub release／CI artifact，手動安裝 | 本地只能重裝 |
| 回復方式 | 🟠 `Recovery.bat`，但可能 bootloop／校準資料風險 | 🟢 uninstall APK | Open 可逆性高 |
| 網絡暴露 | 🟠 Console 8080，預設 LAN 開放 | 🟠 Console 8888，預設 LAN 開放 | 兩者都不要 bridge 到互聯網 |
| 可審計性 | 🔴 只有 binary | 🟢 GPL-3.0 源碼可審 | 最大代碼安全差異 |

### 5.2 安全風險 Top 5

#### Alpha Deus Revival

1. **Signature Spoofing＋system UID。** 改 `services.jar` 放寬簽名校驗，App 又以 `android.uid.system` 運行；一旦被利用，影響是整台 Android，不只是機械人 App。
2. **40 個權限、13 個 exported 元件。** 包括 `INSTALL_PACKAGES`、`DELETE_PACKAGES`、`INJECT_EVENTS`、`READ_CONTACTS`、`SEND_SMS`、`CALL_PHONE`、`MOUNT_UNMOUNT_FILESYSTEMS`，遠超機械人控制所需。
3. **Console 8080 沒有見到認證／TLS 說明。** 同網段任何人都可能直接操作；PDF 也沒有 security model。
4. **閉源 patcher＋prebuilt APK。** 無法確認 patch 具體改了什麼；`patcher.exe` 6.3MB，無源碼。
5. **APK assets 有原廠 private key 檔。** `pkcs8_rsa_private_key.pem` 不應外流；側載散佈時要當成敏感二進位處理。

#### Open Alpha2

1. **Panel token 預設關閉。** 所有 `/api/*`、`/upload/*`、`/stream/*`、`/ws` 預設全開；同網段任何人都可以控制機械人、看相機、聽 mic。
2. **純 HTTP、無 TLS。** token 和畫面內容都可能被同網段 sniff；官方也明說只限 trusted LAN。
3. **`XiaozhiTrustAllSsl`。** Source 顯示不只小智，連 actions pack、APK、Vosk 下載器都用了 trust-all SSL；MITM 風險不是零。
4. **Firmware／servo 上載。** 有 chest／servo 燒錄 endpoint；雖然 token 可保護，但預設關閉時是高價值攻擊面。
5. **Beta＋單一維護者＋debug keystore。** CI 出的是 debug APK，不是 production release；長期安全和供應鏈要自己承擔。

### 5.3 安全強化清單

#### 如果保留 Alpha Deus Revival

- 只放在隔離、可信的 LAN；永遠不要把 8080 expose 到互聯網。
- 保留原始 `services.jar` 備份，並確保 ADB 連線可用。
- 不要安裝不明 APK，也不要跑來源不明的 `patcher.exe`。
- 把整個套件視為含敏感 key material；不要隨便轉發。
- 如果 console 可被其他人存取，避免用它來處理私人對話。

#### 如果使用 Open Alpha2

- 在 Experiment 分頁啟用 panel token。
- 優先使用 `Authorization: Bearer` header；避免把 `?panel_token=` 放在 URL。
- 永遠不要把 8888 expose 到互聯網。
- 只在可信 LAN 使用。
- 避免在不可信網絡使用小智與下載功能，因為有 trust-all SSL。
- 只用官方 GitHub release／CI artifact，安裝前先看 source。
- 不需要相機／mic 時關閉相關功能。

---

## 6. 代碼完整度與工程品質

### 6.1 量化對照

| 指標 | Alpha Deus Revival | Open Alpha2 |
|---|---|---|
| 可取得 App 源碼 | ❌ 沒有 | ✅ 108 個 Java 檔、約 52,163 行；73 個 app classes |
| Web 前端源碼 | ❌ 只有 prebuilt APK | ✅ 68 個 web assets、37 個 JS 檔、約 25,290 行 JS、2,941 行 CSS |
| Build system | ❌ batch＋apktool＋patcher | ✅ Gradle 7.0、AGP 4.2.2、JDK 11、pinned NDK／CMake |
| 可重建 APK | ❌ 不能從 folder 重建 `output.apk` | ✅ `./gradlew assembleDebug --offline`；CI 也可建 |
| API 規格 | ❌ 未見 | ✅ OpenAPI 219 paths、7,469 行 YAML；AsyncAPI；MCP sync |
| 測試 | ❌ 沒有 | ✅ 8 個 Java 測試類＋4 個 Python test runner |
| CI | ❌ 沒有 | ✅ `build-apk`＋`check-openapi` 兩個 workflow |
| 文件 | 🟡 8 頁 PDF 手冊 | ✅ 412 行英文 README＋335 行繁中 README＋OpenAPI＋Swagger＋`llms.txt` |
| 版本控制 | ❌ 無 git／版本歷史 | ✅ GitHub，189 commits，beta1 → beta8.1 |
| 授權 | ❌ 根目錄 LICENSE 是 Oracle JDK，不是專案授權 | ✅ GPL-3.0-only |
| Release artifact | 3 個 APK，合共約 80 MB | 1 個 APK，clean build 約 5.5 MB；GitHub release／CI artifact |
| Binary transparency | 🔴 `patcher.exe`＋prebuilt APK＋closed libs | 🟡 源碼公開，但仍有 prebuilt `.so`、Vosk／JNA AAR、公開 debug keystore |
| 註解／可讀性 | ❌ 沒有源碼可讀 | ✅ 大量註解；class 分工清楚 |
| 文檔準確度 | 🟡 PDF 為主，無 API 文件 | 🟡 README 寫 208 paths，但 current spec 實際 219（輕微 drift） |
| 測試覆蓋率 | ❌ 無 | 🟡 有 unit／static check，但未見 coverage 數字；hardware-in-the-loop 仍靠人手 |
| 硬體驗證 | 🟡 PDF 說 5.1 最佳化、4.4 需調整 | 🟡 beta；backward gait、direct IK 等仍有未驗證項 |
| 維護者／bus factor | ❌ 不明，無公開 repo | 🟡 單一維護者、社群很小（1 star、1 fork、2 open issues） |
| 功能完整度 | 🟡 在其範圍內完整，但功能窄 | ✅ 很廣，但仍 beta，有已知限制 |

### 6.2 代碼完整度評分

| 面向 | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|
| 源碼可取得 | 0 / 5 | 5 / 5 |
| 可重建／可編譯 | 0 / 5 | 5 / 5 |
| 測試 | 0 / 5 | 4 / 5 |
| 文件 | 2 / 5 | 5 / 5 |
| API／規格 | 0 / 5 | 5 / 5 |
| CI／版本控制 | 0 / 5 | 5 / 5 |
| **總分** | **2 / 30** | **29 / 30** |

> Alpha Deus 的 2 分主要是給「安裝腳本可讀、PDF 有基本操作說明」；App 本身仍是不可重建的 binary。

### 6.3 可重建性細節

#### Alpha Deus Revival

- Folder 內沒有 Java／Kotlin 源碼、沒有 Gradle project、沒有 resources、沒有 APK build script。
- `output.apk` 是 prebuilt，只能當作 opaque artifact。
- `patcher.exe` 對 `services.jar` 做什麼無法從源碼審查。
- 唯一可重建的是 installer workflow 本身，因為 batch／sh 可讀。

#### Open Alpha2

- 完整 Android project，包含 `app/` 與 `sdk-module/hardware-direct/`。
- 工具鏈固定：Temurin JDK 11、Gradle 7.0、AGP 4.2.2、NDK `21.4.7075529`、CMake `3.10.2.4988404`。
- 支援 offline build，因為 Vosk 與 JNA AAR 已 vendored 在 `app/libs`。
- Debug keystore 已 commit，屬於非秘密測試 key，所以產出的 APK 是 debug-signed。
- 預編 native libraries（`head_led`、`head_key_mgr`、`serial_port`）仍是 binary；`libeasyopus.so` 由 CMake 從 source 編譯。
- CI 會產出 artifact，但沒有 production signing／provenance pipeline。

---

## 7. 各自獨有優勢

### Alpha Deus Revival

- **Memory Manager：** SQL 學習語句／回覆，可從 web console 查看、編輯、刪除。
- **Virtual Input：** 直接注入假語音命令，適合測試／嘈雜環境。
- **Sleep／Wake、系統關機／重啟：** 因為有 system UID／REBOOT 權限。
- **原廠 UBTECH 語音與行為：** 保留 iflytek／Nuance／XMPP 元件與原廠動作引擎。
- **Lynx 相容性：** PDF 有 Lynx 注意事項，Recovery 也有 Lynx `services.jar`。
- **安裝包自帶完整離線資源：** Vosk 模型、約 286 個 `.ubx`、Google TTS，一次過裝好。

### Open Alpha2

- **完整 HTTP／WebSocket API：** 219 paths、Swagger、AsyncAPI、typed client。
- **Blockly：** 視覺化編程，12 類 toolbox，英文＋繁中。
- **小智 AI＋MCP 22 tools：** 可接雲端 AI，也可本地 Vosk。
- **Gamepad／鍵盤映射：** BT HID＋瀏覽器 Gamepad API。
- **IK 動作＋Direct transition：** 蹲、劈腿、踢、側弓步、半蹲、屈臂、前後一字馬。
- **Hold-to-walk：** 速度控制、lease heartbeat、失去連線自動停。
- **相機完整：** MJPEG、錄影、變焦、雲台、face＋color tracking、walk-follow。
- **音樂完整：** 本地／電台／Internet Archive／EQ／頻譜。
- **Servo 完整：** 22 軸、回讀、trim、ID、firmware、calibration。
- **Panel token：** opt-in 認證，constant-time 比對、log 脫敏。
- **代碼完整、可 build、可審、有 CI。**

---

## 8. 選擇建議

### 選 Open Alpha2，如果你要

- 安全、可逆、可維護：不想 root、不想改 system。
- 完整 API／Blockly／小智 AI／Gamepad／IK／錄影／EQ。
- Android 5.1 5-mic 或 Android 4.4 2-mic 的明確支援。
- 想看源碼、自己 build、自己修。
- 願意自己開 panel token、只放 trusted LAN。

### 選 Alpha Deus Revival，如果你要

- 保留原廠 UBTECH 的語音／行為／Memory Manager。
- 需要 Sleep／Wake、關機／重啟、Virtual Input。
- 有 Lynx，或你已經有這個安裝包而且接受改機風險。
- 不需要 API／Blockly／gamepad／IK／錄影等新功能。
- 願意承擔 bootloop、system patch、簽名繞過、無源碼的風險。

### 重要實操建議

1. **不要兩套同時運行。** 兩者都會直接或間接佔用 `/dev/ttyS1`、`/dev/ttyS3` 等串口，同時跑有指令衝突風險。
2. **轉 Open Alpha2 前：** 備份 `/sdcard/actions`、Vosk 模型；Open Alpha2 可讀 `.ubx`，但 actionInfo／分類未必完全對應。
3. **移除 Alpha Deus 前：** 先確認 `Recovery.bat` 能還原 `services.jar`，並保留 `saved/services.jar`、`saved/lynx/services.jar`。
4. **Open Alpha2 一定要開 panel token**，不要把 8888 expose 到互聯網；小智／下載功能在不可信網絡要避免使用。
5. **Alpha Deus 只放在完全隔離的 LAN**；它的 console 8080 未見認證／TLS 說明，而且系統權限太高。

---

## 9. 最終評分

| 問題 | 答案 |
|---|---|
| 哪個功能多？ | **Open Alpha2** 壓倒性多，尤其 API、Blockly、gamepad、IK、相機、音樂、servo。 |
| 哪個較安全？ | **Open Alpha2**，因為不改 system、不 root、權限少、可 uninstall；但它仍有純 HTTP／預設無認證／trust-all SSL 的缺口。 |
| 哪個代碼完整？ | **Open Alpha2**：108 個 Java 檔、219 API paths、8 個 Java 測試、2 個 CI workflow、GPL-3.0；Alpha Deus 只有 binary＋566 行安裝腳本。 |
| 哪個較適合長期使用？ | **Open Alpha2**，前提是開 panel token、只放 trusted LAN、接受 beta 風險。 |
| 哪個適合保留原廠功能？ | **Alpha Deus Revival**，但要接受改機、system patch、無源碼、較高安全風險。 |
| 哪個適合 Lynx？ | **Alpha Deus Revival** 較有機會；Open Alpha2 未聲明 Lynx 支援。 |

### 加權總分

| 指標 | 權重 | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|---:|
| 功能廣度 | 30% | 6.0 | 9.5 |
| 安全性 | 25% | 3.0 | 6.5 |
| 代碼完整度 | 20% | 1.0 | 9.0 |
| 安裝簡易度 | 10% | 3.0 | 9.0 |
| 硬件相容性 | 10% | 6.0 | 8.5 |
| 可維護性 | 5% | 1.0 | 9.0 |
| **加權總分** | **100%** | **約 3.7 / 10** | **約 8.5 / 10** |

---

## 10. 總結

**Open Alpha2 整體是較強的專案。** 它是一個真正的 Android 應用，有完整 source tree、文檔化 API、自動化 build、測試，以及小得多的系統足跡。它的主要弱點是仍然 beta、panel 認證預設關閉且只有純 HTTP，而且部分下載／小智路徑用了 trust-all SSL。

**Alpha Deus Revival 是一個改機包，不是可維護的應用專案。** 它適合保留原廠 UBTECH 行為、系統級控制或 Lynx 相容性，但需要 root、patch 機械人、以 system UID 運行、放寬簽名校驗，而且沒有源碼。應把它視為高風險的閉源 binary distribution，而不是可長期建構的平台。

---

### 附錄：評分標準

- **功能廣度：** App 公開了多少有文件記載的機械人能力。
- **安全性：** 安裝權限、系統改動、權限範圍、網絡認證、傳輸安全、供應鏈、可逆性。
- **代碼完整度：** 源碼、可重建性、API 規格、測試、CI、文件、版本控制、授權。
- **安裝簡易度：** 安裝步驟數、所需權限、變磚風險。
- **硬件相容性：** 文件記載的 Android 版本、mic 變體、Lynx 支援、ABI 覆蓋。
- **可維護性：** 源碼結構、測試、CI、文件、release cadence、bus factor。

以上評分是比較性、主觀性的，目的是輔助決策，不能取代實機安全審查。
