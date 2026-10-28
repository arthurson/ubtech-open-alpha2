# Open Alpha2

> 語言：[English](README.md) | 繁體中文（本檔）

`com.open.alpha2` —— 裝在 UBTECH Alpha2 機械人上的 Android 應用程式
（`versionName "beta 8"`／`versionCode 8`），讓機械人可以被遙距操控。
開機後會自動啟動 HTTP＋WebSocket 伺服器（port `8888`）；在同一 Wi-Fi
下的任何瀏覽器開啟 `http://<機械人IP>:8888/`，即可使用完整控制面板。
Blockly 編程頁面位於 `/blockly.html`；同一 port 亦提供 OpenAPI／AsyncAPI
spec 及 Swagger UI（見 [HTTP 接口](#http-接口)）。

## 前提條件

- 機械人：`alpha2_10005` 5-mic（RK3288，Android 5.1.1／API 22）或
  `alpha2_10002` 2-mic（Android 4.4.2／API 19）；僅 `armeabi-v7a`。
  應用程式開機讀 `ro.hardware.version`，自動選返對應嘅串口／JNI 路徑。
  APK 宣告 `minSdkVersion 19`、`targetSdkVersion 22`（刻意如此，不上架
  Play Store——以 `adb install` 直接安裝）。
- 應用程式接管的硬件：
  - `alpha2_10005`：胸板 MCU（`/dev/ttyS1`）、頭板 MCU（`/dev/ttyS3`，
    波特率 115200）；頭／眼／嘴 LED 經 `libhead_led.so`（JNI）；頭頂鍵經
    `libhead_key_mgr.so`。
  - `alpha2_10002`：胸板 MCU（`/dev/ttySAC0`）、頭板 MCU（`/dev/ttySAC1`，
    波特率 115200；失敗 fallback `ttyS1`／`ttyS3`）；頭／眼 LED 經頭板 MCU
    串口（`0x01`／`0x02`／`0x08`）；頭頂鍵經 1.1.7.3 `libNativeClassJni.so`。
    網頁面板唔會顯示 PIR 卡同胸板固件升級卡。
  - 頭頂＋/－觸控鍵（`/dev/input/event0`）。
- 機械人與瀏覽器裝置必須在同一 Wi-Fi。伺服器只用純 HTTP。
- ⚠️ **僅限可信內網**：預設沒有認證——同網段任何人都可以播放動作、查看
  相機、收聽咪高峰或上載韌體。切勿橋接至互聯網，亦不要放在共用大內網。
  實驗分頁提供自選面板口令（`system/auth/*`）：啟用後，所有 `/api/*`
  （包括讀取操作）、`/upload/*`、`/stream/*` 及 `/ws` 都必須帶上
  `Authorization: Bearer <token>`（否則回 401＋`WWW-Authenticate`）；
  只有 `system/auth/*` 及靜態頁保持開放。設不到 header 的 `<img>`／WebSocket
  可用 `?panel_token=` 代替。舊 `?token=` 別名已移除（同小智 OTA `token` 撞名）。

## 編譯／安裝

工具鏈：Temurin JDK 11＋Gradle 7.0（wrapper 已 pin SHA-256）＋AGP 4.2.2＋
Android SDK（`ANDROID_SDK_ROOT` 指向你的 SDK；`local.properties` 只屬本機，
切勿提交）。native 側 pin 死 NDK `21.4.7075529`＋CMake `3.10.2.4988404`，
確保任何機器編出嚟嘅 `libeasyopus.so` 一致。

```bash
./gradlew assembleDebug --offline
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.open.alpha2/.MainActivity
adb -s <serial> forward tcp:8888 tcp:8888
```

`app/debug.keystore` 是已提交的公開測試鑰匙（密碼 `android`）；如因簽名
不符無法覆蓋安裝，請先解除安裝。應用程式會在開機時自動啟動
（`BootReceiver`），無需觸碰機械人即可連上控制面板。APK 大細：clean
編出嚟約 5.5 MB——普通增量編譯會留低廢棄 entries 而脹大
（運行無影響）；出機前跑一次 `./gradlew clean assembleDebug --offline`
即可。CI（`build-apk.yml`，Temurin JDK 11＋Node 20）會先剝離網頁註解再編出
同一個 APK，以 artifact `open-alpha2-beta8-apk` 發佈（檔名
`open-alpha2-beta8.apk`，保留 7 日，只留最近 3 個）。預編 `.so` 檔
（`head_led`／`head_key_mgr`／`serial_port`）放在
`sdk-module/hardware-direct/src/main/jniLibs`；`libeasyopus.so` 由
`app/src/main/cpp` 經 CMake 即時編譯。Vosk 同 JNA 的 AAR 已放喺
`app/libs`（只留 armeabi-v7a），`--offline` 編譯毋須連 Maven。

## 控制面板 (`app/src/main/assets/web/`)

中英雙語（EN／繁中），10 個分頁，經 WebSocket 即時更新，無需重新整理：

- 狀態 (📊)——語言切換、媒體音量、系統／裝置資訊（電池、Wi-Fi／藍牙、機械人
  UUID、胸板韌體）、聲納圖表、PIR 感應器（可選警示：紅燈＋鈴聲）、加速度
  計即時曲線，以及可選的四方向傾側 → 頭／眼 LED。
- 動作 (🕺)——動作清單及分類分頁、播放／停止、變速 slider（0.5–2.0 倍，
  舵機同 mp3 配樂速度一齊跟住變）。動作名粒粒跟分類轉色（先跟子分類色，
  無就跟大分類色）。（動作包下載在實驗分頁。）
- 舵機 (⚙️)——22 軸按頭／手／腳／擴展分組，逐顆或全組控制，附即時指令位姿顯示。
  拖動逐顆 slider 會即時跟隨（約 8Hz），放手完成。即時角度回讀：一鍵 `servo/back-angle-all`（胸板 cmd `0x17` 純讀，22 軸全讀，
  包括 21／22），另有單顆讀取——`servo/angle`（胸板 cmd6 即時查詢）、
  `servo/back-angle`（cmd `0x17`）、`servo/read`（經 cmd13 讀 trim／偏差）及
  `servo/angle-restore`（cmd6 讀完即寫回同一位置，關節唔會鬆）。擴展卡：
  逐顆固件版本（`servo/version`，胸板 cmd14）及硬件資訊（`servo/hardware`，
  cmd33）、舵機 ID 變更（`servo/modify-id`，胸板 cmd10——跟足胸板左右 bus
  接線），以及單顆舵機韌體燒錄（`servo/upgrade`，7168-byte `.bin` 閘）。
  IK 動作卡：蹲下／蹲式（官方 1509004868066 最深格）／左右一字馬／
  左前一字馬／右前一字馬五條 0–100 slider，由開機企姿（robotStandup；
  #19 頭左右用 120）開始；拖動設定目標，機械人每 120ms 行 10%
  （0→100 約 1200ms 均速），五條互斥。「直接轉移」開關控制切換
  方式：閂（預設）先均速回開機企姿再去新動作；開就由目前 A pose 直接
  均速過渡去 B pose（每 120ms 一格，唔經企姿）。開關會記住。直接模式
  會線性混合 20 個舵機角度，中間 pose 未必係合法 IK，建議先由低百分比試。
  做緊 IK 時改舵機格，IK 百分比會保留；下次拉 IK 會由現時 pose 以同樣
  每步 120ms 均速過渡，唔會跳返舊百分比。IK 動作本身唔會郁嘅軸（例如
  蹲下時嘅頭左右）會保持手動值。蹲下只會控制腳 7–16 共 10 顆舵機；
  蹲式／左右一字馬控制腳 7–16 加肩 roll 2/5（目標分別 180/60 同
  140/100）；左前／右前一字馬控制腳 7–16 加肩 pitch 1/4（左前 90/70、
  右前 170/150，雙臂前後擺）。頭 19/20、手 17/18、肘 3/6 完全唔會俾
  IK 郁，會一直保持目前／手動值。轉動作時新動作唔控制嘅軸會保持
  目前值（直接轉移閂、先回企姿嗰段亦只郁新動作嘅軸），雙手唔會跌落。
  開機安全：企姿同 START 握手完成之前，API 會拒絕會令
  舵機郁嘅指令（回 `ok:false, "booting"`）；握手後會再送企姿；面板未有用家
  真正拖過之前，會忽略瀏覽器還原表單 slider 值嘅事件；WebSocket 斷線或
  `servo/all` 超過 2 秒冇回應，IK 會即刻停。咁樣死機／reboot 後，機械人
  唔會自己重播上一次 IK pose。
- 手柄 (🎮)——藍牙手柄／鍵盤直連機器人、手柄鍵盤映射分開（`gamepad/*`，同名鍵獨立
  值）：頁面開藍牙／只列 HID 的掃描／配對／主動連接／解除綁定，離線可用；
  撳鍵即執行，右搖桿轉頭 19／20 號；滑鼠類直吞；直連手柄／直連鍵盤開關分開，
  熄邊只記唔做。手柄圖／全鍵盤圖即時亮燈，映射表分兩區；`gamepad/key`／
  `gamepad/axis` 後端保留畀脚本直調。動作值支援 22 軸 `servo1-22±` 步進
  （撳住連走、放手停；跟 servo tab 同一套校準大小值）、腳部 IK 動作
  （`ik:squat±`／`ik:ysquat±`／`ik:lr±`／`ik:fsplit±` 左前／
  `ik:fsplit2±` 右前；舊 `ik:fb±` 當左前；撳住每步 10%／120ms，
  由實際 pose 均速行到 0%／100%；轉動作先以同一速度回舊動作 0%，新動作
  唔控制嘅軸保持原位；面板每次撳鍵帶 seq，快速輕按保證只行一格，就算 up
  request 快過慢嘅 down request 都唔會變長按）、本地音樂功能鍵
  （含隨機歌）／隨機瑜伽／系統鈴聲（名／預設電話鈴）／頭眼咀 LED／三燈全熄／
  影相＋人臉顏色追蹤開關＋203 動作（基本／舞蹈／故事／瑜伽／其他 5 分組）；
  mapping `stop` 係全部停止（動作回位＋TTS＋音樂電台＋鈴聲＋三燈）；`taken`
  照真執行结果，面板 `✓`／`✗只記錄唔執行`一眼分清。
- 語音 (🗣️)——對話界面（可打字測試 `speech/semantic_simulate`）、Vosk 離線
  語音辨識卡（載入／聆聽；模型下載在實驗分頁）、TTS（引擎／語言／聲音
  選擇，可選自動跟 Vosk 語言包走）。
- 音樂 (🎵)——本地音樂（`/mnt/internal_sd/music`，支援拖放上載、附頻譜顯示）、
  網絡電台（radio-browser.info）、Internet Archive 網上點歌（瀏覽器搜尋、
  機械人播放）、共用播放器；隨歌伴舞開關及 Disco LED 開關在「播放中」
  同一行；另有 5-band 等化器（preset＋每 band 垂直推桿，低綠→高紅，
  設定記喺機械人）。
- LED (💡)——頭／眼／嘴燈預設效果（長開／閃燈／呼吸／跑馬燈／雙色、眼 3 秒
  倒數、嘴呼吸速度）。靜音掣跟實機胸口燈（小智連線 OR 播歌指示）；pad／
  Wi-Fi／頭第 5 粒經 100ms `led/state/get` 輪詢跟機。
- 相機 (📷)——MJPEG 串流、預覽快照、高清拍照（9 檔畫質）、機端 mp4 錄影（🎥，
  640x480 H264，存 `/sdcard/DCIM/Alpha2/alpha2_vid_*.mp4`）、數碼變焦、雲台
  搖桿（帶動頭部舵機）、快門提示音、咪高峰收聽鍵（瀏覽器播放 `/stream/mic`）、
  全螢幕，另有兩種追頭（互斥，用 19／20 號舵機）：內置人臉追蹤
  （`android.media.FaceDetector`，零依賴）及純 Java 顏色追蹤（HSV＋最大
  色塊，7 隻 LED 色可揀，可選行路跟——經內建 `.ubx` 步態轉向／向前追）。
- 小智 (🤖)——AI 語音對話橋接：配對碼、連接／斷線、咪高峰狀態、文字對話、語音
  輸出選擇（小智 opus／Android 內置）、自架 server／OTA 覆寫、內置 MCP
  工具清單（逐項開關）、小智控制台連結。
- 實驗（進階，🧪）——面板認證口令、開機語音模式（全部手動／開機自動連小智／開機
  自動啟用 Vosk）、資源下載（Vosk 模型目錄、202 招動作包、Google TTS APK——經
  `DownloadGate` 一次只准一樣）、22ch 舵機角度微調器（offset 備份／還原；校准依
  原廠流程：批次寫 trim → 等 EEPROM → 回標準姿勢 → 重掃驗證）、
  機械人 ID 變更器（查詢／複製／QR 預覽／寫入 EEPROM）、胸板韌體升級
  （先上載後燒錄；升級一定要今次開頁 session 內重新上載 `.bin`——機上舊檔
  永不自動沿用）、舵機固件版本／燒錄卡、版本連結。事件 Log 係分頁下方常駐卡片。
- Blockly（`blockly.html`，獨立頁面）——可視化編程，12 個工具箱分類：控制
  流程、事件（加速度／聲納／PIR）、動作、語音／TTS／鈴聲／本地音樂、舵機、
  LED，另有標準邏輯／數學／文字／變數／自訂函式及現成範例；積木介面支援
  EN＋繁中。

## 語音互動

- 離線語音辨識用 Vosk（模型放在 sdcard 並自動偵測；Vosk 需要 API 21＋，
  較舊裝置會自動停用）。模型亦可由實驗分頁下載（官方 alphacephei.com
  白名單，每個約 30–100 MB，自動解壓）。
- 回答語音使用 Android 系統 TTS（`speech/tts`、`speech/stop`），可選自動
  跟隨已載入 Vosk 模型嘅語言。
- 十種對話語言的本地意圖配對（中英西法日德意葡韓俄，
  `app/src/main/assets/semantic/`）。每種語言各有 86 組動作意圖、13 組
  功能意圖及 200 組閒聊意圖（共 299 組）。
  - 動作意圖會說出回覆並播放機械人動作。
  - 功能意圖會真正執行：全部停止、音量加／減一格、藍牙開／關、Wi-Fi
    開／關、拍照、本地音樂播放／上／下一首。關機／重啟意圖只會說出
    「請按電源鍵」的提示——側載應用程式沒有 `REBOOT` 權限。
  - 配對不中的說話會得到一句候補回覆，外加一個隨機填充動作。
  - 不用咪高峰也可打字測試：`speech/semantic_simulate`。
- 頭頂按鍵：＋／－逐格調校媒體音量（長按連調）；兩鍵齊按即停止動作＋
  語音＋音樂＋電台，等同語音全部停止指令。
- 開機語音模式（實驗分頁）：揀全部手動／開機自動連小智／開機自動啟用
  Vosk——儲存後下次開 App 先生效，唔會即刻郁。

## HTTP 接口

單一真相源：`openapi/open-alpha2-openapi.yml`（**208 條路徑**，每條一個
operation）。spec 入面嘅命名空間（括號內為 `/api/*` 條數）：

- `/api/*`——主機器 API，共 199 條：`accelerometer`（2）、`action`（6）、
  `apk`（3）、`audio`（33）、`battery`（1）、`bt`（1）、`camera`（12）、
  `chest`（6）、`color`（4）、`debug`（2）、`face`（5）、`gamepad`（12）、
  `head`（1）、`led`（9）、`misc`（2）、`pir`（3）、`servo`（19）、
  `speech`（18）、`status`（1）、`ubx`（5）、`vosk`（10）、`wifi`（1）——另加
  直調三族 `direct`（12）、`system`（13）、`xiaozhi`（18）。
  瀏覽器客戶端經 **`/api/alpha2/*`** 前綴呼叫（`app-core.js` 嘅 `api()`；
  其餘三族用 `sysApi()`／`directApi()`／`xiaozhiApi()`）；幾種寫法都會去到
  同一個 dispatcher（`handleApi`），而 OpenAPI file 文件化嘅正係冇
  `alpha2/` 嗰種。
- `/api/system/*`——裝置探索、音樂播放器、面板認證（`system/auth/*`）。
- `/api/direct/*`——底層直調（ubx／舵機／LED／聲納）。
- `/api/xiaozhi/*`——AI 橋接控制、OTA／MCP／TTS 設定、開機語音模式。
- `/upload/*`——原始二進制上載：走廊對講 PCM（`audio`）、音樂（`music`）、
  胸板韌體（`chest`）、舵機韌體（`servo-fw`）。
- `/stream/camera`（MJPEG）、`/stream/mic`（WAV 分片）、`/ws`（RFC 6455
  事件推送，見 `openapi/asyncapi.yml`）、`/` 及 `/blockly.html`。

機械人上嘅自我描述端點（同一 port）：

- `/docs`——Swagger UI（走 CDN；離線時回退顯示原始 YAML）。
- `/openapi.yml`、`/asyncapi.yml`——spec 副本（同步自 `openapi/`）。
- `/.well-known/openapi.json`（另有 `.yml`，以及冇點版 `well-known/`
  fallback——aapt 會剝走點開頭檔名）——機器可讀嘅 API 發現。
- `/apis.yml`——apis.json 風格目錄條目；`/llms.txt`——畀 LLM 嘅簡短指引。

JS 客戶端（`assets/web/api-client.js`，`Alpha2Api.*`，**199 個 typed
wrapper**）由 spec 自動生成——切勿手改：

```bash
python scripts/generate-api-client.py
python scripts/generate-api-client.py --check   # CI drift gate
```

`scripts/check-openapi-drift.py` 負責代碼與 spec 對齊（六大家族全掃：
`handleApi`／`handleSystemApi`／`handleDirectApi`／`handleXiaozhiApi`／
`handleUpload`／`handleStream`）；
`scripts/check-api-client-routes.py` 確認每個 wrapper 用嘅 caller 前綴同
後端路由一致；`scripts/check-spec-copies.py` 確保網頁 spec 副本同步
（`--fix` 可重拷 yml 檔）。MCP 工具表（`McpToolsGenerated.java`，22 個工具）由
`openapi/mcp-openapi-sync.yml` 經 `scripts/generate-mcp-tools.py` 生成。

## 小智 AI 橋接＋MCP

應用程式可將機械人接上小智 AI 後端做開放式語音對話（配對碼、連接／斷線、
咪高峰開／關、手動 `send_text`、自架 OTA／WebSocket server 覆寫、語音輸出
揀小智 opus 或本地 Android TTS）。經 MCP 向模型開放 22 個工具：
`self.robot.*`（列出／播放／停止／隨機動作、單顆／全組舵機、頭／眼／嘴燈、
說話）、`self.sensors.*`（PIR／聲納讀取及設定）、`self.camera.*`（拍照、
看圖問答）及 `self.media.*`（本地音樂／電台搜尋－播放－停止）。逐項
enable/disable 喺小智分頁；開機自動連線係實驗分頁嘅開機語音模式。

## 脚本 (`scripts/`)

- 生成器及閘門：`generate-api-client.py`、`generate-mcp-tools.py`、
  `check-openapi-drift.py`、`check-api-client-routes.py`、
  `check-spec-copies.py`（見上面 HTTP 接口）。
- 單元測試跑手（零依賴，經 `javac`／`java` 即編即跑）：
  `test-apivalidator.py`（`ApiValidator`＋顏色追蹤＋EQ 三套）、
  `test-serialcodec.py`（`SerialFrameCodec`）。
- 離線 `.ubx` 分析工具：`ubx_scan.py`（關鍵幀／軌掃描）、`ubx_anatomy.py`
  （邊啲舵機郁）、`ubx_phase.py`（逐關節步態相位）、`ubx_gaitdiff.py`／
  `ubx_armcheck.py`／`ubx_tail.py`（版本對比）、`ubx_gate.py`（官方分發模擬）、
  `ubx_to_traj.py`（`.ubx` 轉 `traj.csv`）、`ubx_walk_humanize.py`（步態去頓挫）、
  `ubx_checklist.py`（在線動作清單檢查）、`ubx_corpus_order.py`（語料順序驗證）。

## 機身數據

- 動作：`/sdcard/actions/`——`<id>.ubx` 檔、外加每個動作的配樂及
  `actionInfo.txt`。可選動作包下載（202 招，來自專案 GitHub release 嘅
  `actions.zip`）會整棵替換（舊樹改名 `/sdcard/actions-backup`）。
- 本地音樂：`/mnt/internal_sd/music`（mp3／wav／ogg／m4a／flac，檔名排序
  即曲目順序；語音上／下一首會循環；音樂分頁亦可拖放上載）。
- 相片及錄影：`/sdcard/DCIM/Alpha2/`（快照＋機端 `alpha2_vid_*.mp4` 錄影）。
- 胸板韌體暫存檔：`/sdcard/AlphaII_CHEST_kernel.bin`（app 小鏡像，
  1..114688 byte、4-byte 對齊；由實驗分頁上載，經 `chest/upgrade` 燒錄）。
- Vosk 模型：在 sdcard 頂層自動偵測（不隨 App 附送；實驗分頁可下載，
  每個約 30–100 MB）。

## 項目結構

```
open-alpha2/
├── app/                            ← com.open.alpha2 (UI server, API, all centers)
│   ├── src/main/java/com/open/alpha2/  ← MainActivity wiring + ~69 classes:
│   │   Action/Speech/Audio/Music centers, ApiDispatcher/ApiValidator,
│   │   UbxApi/ChestQuery/ChestUpgrade/ServoUpgrade, Camera/FaceTrack/
│   │   ColorTrack centers, Led/Sonar/DeviceStatus, Gamepad/Gesture,
│   │   SemanticCenter + 10 matchers, Vosk/TTS/Ringtone, XiaozhiBridge/
│   │   Client/OTA/MCP, PanelAuth, EventBus, WebSocketServer, HttpServer, …
│   ├── src/main/assets/web/        ← control panel (10 tabs) + Blockly +
│   │                                  generated api-client.js (199 wrappers)
│   │                                  (CI strips web comments before build)
│   ├── src/main/assets/semantic/   ← 10 intent libraries (299 groups each:
│   │                                  86 action + 13 function + 200 chat)
│   │                                  + action category pools
│   ├── src/main/cpp/               ← easyopus JNI (CMake)
│   ├── src/test/java/…             ← ApiValidatorTest + ColorTrackTest + MusicEqTest
│   │                                  (run via scripts/test-apivalidator.py)
│   ├── libs/                       ← vendored vosk-android + JNA AARs (v7a)
│   └── build.gradle                ← versionName "beta 8", armeabi-v7a only
├── sdk-module/hardware-direct/     ← serial ports (/dev/ttyS1+S3), LED/pad JNI,
│                                      wire protocol, .ubx parse/play/speed-shifted music
│   └── src/test/java/…             ← SerialFrameCodecTest (scripts/test-serialcodec.py)
├── openapi/                        ← open-alpha2-openapi.yml (208 paths) /
│                                      asyncapi.yml / mcp-openapi-sync.yml + README
├── scripts/                        ← generators, drift/route/copy checks, unit-test
│                                      runners, ubx_* offline analysis tools
├── docs/legacy-beta3/              ← archaeology only (AIDL / iFlytek era)
└── .github/workflows/              ← build-apk.yml, check-openapi.yml
```

## 持續整合（push 即跑）

- `build-apk`（push 上 main／master＋PR＋手動）：JDK 11 → 裝
  `platforms;android-25`＋`build-tools;30.0.3` → 剝離網頁註解 →
  `assembleDebug` → 上載 APK artifact `open-alpha2-beta8-apk`
  （檔名 `open-alpha2-beta8.apk`，保留 7 日，只留最近 3 個）。
- `check-openapi`（push／PR 掂到 spec、代碼、客戶端、scripts 或網頁 spec
  副本即跑）：spec／代碼漂移、客戶端新鮮度（`--check` ×2：api-client＋
  MCP）、客戶端→路由前綴一致、YAML 合法性、單元測試
  （`test-apivalidator.py`＋`test-serialcodec.py`）、網頁 spec 副本同步、
  `versionName` == spec `info.version`。

## 已知限制

- 只有純 HTTP，瀏覽器咪高峰對講（需要安全上下文）用不到；在瀏覽器收聽
  機械人咪高峰則正常。
- 沒有重啟／關機接口或語音動作（沒有 `REBOOT` 權限）；兩者只會說明如何
  按電源鍵。
- 沒有舵機電流回授；舵機分頁顯示指令位姿，另有 `servo/back-angle-all`
  （22 軸）及單顆讀取做即時角度驗證。
- 胸板 `cmd6` 即時讀取會令該舵機瞬間鬆力（firmware 行為）；要保持上力請用
  `servo/angle-restore`（後端一次過讀＋寫回）。胸板韌體升級僅會燒錄本次上載的
  鏡像；燒錄完成後須關機再重新開機以寫入固件（電源鍵指示燈將依序顯示藍色→紅色→綠色，
  綠燈持續亮起即表示更新完成，方可再次關機並重新開機；僅重新啟動系統無效）。
- 語音對話沿用「先說話、等 200 毫秒、再動作」的時序；帶大音效的動作會
  跳過語音回覆，以免兩者搶喇叭。
- 預期會見到嘅 logcat 噪音，並非 bug：預編 native 庫（`head_led`／
  `head_key_mgr`／`serial_port`、Vosk、JNA）嘅 `linker: ... unused DT
  entry` 警告，以及開機時偶爾嘅 `Atlas: ... not in
  getPreloadedDrawables?`。Android 5.1 linker 本來就不認識新式 GNU ELF
  項目（`DT_VERNEED`、`DT_GNU_HASH`），Atlas 那句來自框架圖形代碼——庫
  照常載入（無 `UnsatisfiedLinkError`，已驗證）。切勿為此改 `.so` 檔或關
  硬件加速。

授權：GPL-3.0-only.
