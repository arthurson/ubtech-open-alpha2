# Open Alpha2 — beta4 (pure-direct)

`com.open.alpha2` —— 裝喺 UBTECH Alpha2 機械人本機嘅 Android App（`versionName "beta 4"`
/ `versionCode 4`）。開機自動起一個 HTTP + WebSocket server（port `8888`），同一
WiFi 任何瀏覽器開 `http://<機械人IP>:8888/` 就係成部機嘅控制面板；另有 Blockly
積木編程頁同小智 AI 語音對話橋接。

beta4 同 beta3 最大分別：**機身根本無 `alpha2services.apk`，成套 AIDL 已經死咗**——
全部舊 binder 調用註定失敗，一律唔再經 `RobotStub` 以外嘅路；硬件改行直驅，
訊飛離線引擎成套移除。beta3 時代嘅文件收咗喺 `docs/legacy-beta3/`，只供考古。

## 前提（beta4 實測組合）

- 機械人：RK3288（`armeabi-v7a` 單一 ABI）、Android 5.1.1（API 22）；App
  `minSdkVersion 19`、`targetSdkVersion 22`（刻意唔升，唔上架 Play Store）。
- 胸板 MCU：`/dev/ttyS1`；頭板 MCU：`/dev/ttyS3`；波特率 `115200`；幀
  `F8 8F LEN CMD PARAM SUM ED`。頭頂 +/- pad：`/dev/input/event0`
 （rk29-keypad）；眼/頭/嘴燈：`libhead_led.so` JNI。
- 機械人同瀏覽器裝置要喺同一個 WiFi。Server 純 HTTP（自簽 HTTPS 方案已永久移除）。
- ⚠️ **只用可信 LAN**：無 auth，同網段任何人可播動作、睇相機/聽 mic、上傳固件；
  唔好橋接上網、唔好放公用/宿舍大 LAN，亦唔好經 port-forward 對外網。
- 動作檔喺機身 `/sdcard/actions/`：`<id>.ubx`＋同名目錄 `<id>/xxx.mp3`＋
  `actionInfo.txt`（GBK，`<fileId>##中文名##英文名##type`；機身檔版本眾多，
  「202 個」係指 `actionInfo.txt` 行數口徑，實機 `.ubx` 檔數／前端 preset
  數（199→200）會因版本差一兩個，以機身 `actionInfo.txt` 為準）。

## 控制面板（`app/src/main/assets/web/`）

分頁（`index.html`，TAB 註記）：STATUS（系統狀態/裝置資訊/聲納/PIR/加速度計）·
ACTIONS（動作列表＋分類＋播放/停止＋**變速 0.5/0.67/1/1.5/2**）· SERVO（20 軸逐粒/
全組＋Angle Tuner）· SPEECH（對話界面/TTS/引擎）· LED（頭/眼/嘴 preset）· ADVANCED
（UUID/胸板固件升級/事件 Log）＋相機（串流/拍照/錄影/pan-tilt 搖桿）＋小智＋
本地音樂＋網絡電台＋Blockly（`blockly.html`，獨立頁）。中英雙語
（`app-core.js` 字典），WebSocket 即時事件唔使 refresh。

## .ubx 播放（`sdk-module/hardware-direct/.../ubx/`）

1.1.7.3 反編譯還原、203 個機身檔全量驗證、真機毫秒級對過 pace：

- 結構：track → `d.a` 復幀列 → `a.d` 分發 → `a.h` → `a.c` → `a.b` → `a.a` 幀；
  每段 `[outer][echo]` 雙寫長度，嚴絲合縫。
- 舵機：`f==0` 幀經胸 **`cmd 3 [20 軸 byte + short time]`**，`time = b×timeBase`；
  tick 週期 `T = timeBase`（type-0 第一個 int），幀槽位 `(b+c)×T`，單調 deadline
  無漂移（`UbxPlayer`）。
- 配樂：servo 鏈內 `type==4` 塊走 `a/j/a/o`，同 clock 並行——槽位起播、播至多
  `b×timeBase` 自停、切幀打斷；路徑 `ubx去扩展名/music名`（`UbxPlayer` + `VoiceStream`）。
- 變速：舵機槽位/move 同縮放；歌 1x 行 MediaPlayer，非 1x 行 decode+線性重採樣
  「磁帶式」變速（API 22 無 PlaybackParams，不變調刻意唔做）。播緊轉速自動由頭重播。
- 一鍵全停：播緊撳第二個動作抢占；頭頂雙 pad（`0x5e` 拍頭）、MCP、HTTP 共用
  `stopActionWithRecovery()`（截停＋蹲下站起回位）。

## API

- `/api/alpha2/*`（前端用）：`action/list|play|stop`（`actionInfo.txt` 直讀）、
  `ubx/*`、`servo/*`、`speech/*`、`led/*`、`audio/*`（本地音樂/電台）、`chest/*`、
  `system/*`、`xiaozhi/*`；另有 `/api/direct/*`（底層直調）、`/upload/*`、
  `/stream/*`、`/ws`（RFC6455 即時事件）。
- OpenAPI 3.0（`openapi/open-alpha2-openapi.yml`，142 paths＝134 條 API＋
  5 upload/stream＋`/、blockly.html、/ws` 3 個）係單一真相源；
  `app/.../web/api-client.js`（`Alpha2Api.*`，134 個 wrapper）由
  `scripts/generate-api-client.py` 生成，改 spec 必重 gen；
  `scripts/check-openapi-drift.py` 保 code↔spec 對齊；另有 AsyncAPI
  （`/ws` 事件）同 MCP 聲明表（`mcp-openapi-sync.yml` v2）。
- 小智 MCP 工具（22 個，全部自動生成）：`self.robot.*`（list/play/stop/random 動作、單/全舵機、
  頭/眼/嘴燈、speak）、`self.sensors.*`（PIR/聲納）、`self.camera.*`
  （take_photo/image_to_text）、`self.media.*`（本地音樂/電台）——
  `inputSchema` 由 `scripts/generate-mcp-tools.py` 讀 spec＋聲明表生成
  `McpToolsGenerated.java`，`XiaozhiBridge.listTools()` 只做 enable/disable 過濾。

## Build / 裝機

> ⚠️ **JAVA_HOME 必須指 Temurin JDK 11**（唔係 JDK 17/21：AGP 4.2.2 嘅 manifest
> merger 會死，見下「地雷」）。CI 同本地同一個組合先編到一樣嘅嘢。

組合：Temurin JDK 11 + Gradle 7.0 + Android SDK（`ANDROID_SDK_ROOT` 指向 SDK；
`local.properties` 只放你自己部機，唔入 repo）：

```bash
./gradlew assembleDebug --offline
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.open.alpha2/.MainActivity
adb -s <serial> forward tcp:8888 tcp:8888
```

輸出 `app-debug.apk`（CI 會改名 `open-alpha2-beta4.apk` 做 artifact）。
`app/debug.keystore` 係確定性 debug key（密碼 `android`），簽名唔同要先解除安裝。
呢條 key 視為公開（標準 Android debug key，入咗 repo 正常）；release 另用正式 key，唔好靠簽名做權限隔離。
prebuilt `.so`（`head_led/head_key_mgr/serial_port`）一律喺
`sdk-module/hardware-direct/src/main/jniLibs`；`libeasyopus.so` 由
`app/src/main/cpp` CMake 即編。

### 本地工具鏈地雷（2026-09 實測）

- 本機 JDK 21 + AGP 4.2.2：manifest merger 用咗 JDK 16+ 已封嘅反射——平時
  incremental build 無事（manifest UP-TO-DATE 就唔跑）；**唔 clean、唔
  `--rerun-tasks`**，任何逼 `processDebugMainManifest` 重跑嘅操作會死
  （`File.path accessible: module java.base does not open java.io`）。
  萬一逼死咗：直接再跑一次普通 `assembleDebug --offline`，等佢慢慢行完就返綠。
- 唔 `adb kill-server`；壞 build 唔好短 loop 重試（前人經驗：1.5s loop 會炒）。
- 出 APK 必 `dexdump` **方法級**驗（類表唔夠；`dexdump -d classes.dex`，APK 直 dump 會 mmap 死，先 unzip）。
- `ApiResponse.error()` 天生回 500；`Get-Content` 量行數試過唔準（以實測為準）。
- 寫路徑齋打缺參 400，唔打真值：`misc/set_uuid`、`pir/set`、`servo/*`、
  `vosk/endpointer`。
- 每輪必做：compile＋`test-apivalidator.py`（104）＋routes＋drift＋dexdump＋
  裝機＋端點＋logcat `FATAL EXCEPTION`。
- `C:/Users/user/AppData/Local/Temp/opencode`（即 `%TEMP%\opencode`）有舊 session 幾百 MB log，唔好理。

## 檔案結構

```
open-alpha2/
├── docs/legacy-beta3/            ← beta3 專用舊文件（AIDL/訊飛/README-beta3），已無用
├── openapi/                      ← open-alpha2-openapi.yml / asyncapi.yml / mcp 對齊表 / README
├── scripts/                      ← generate-api-client.py / check-openapi-drift.py / strip-web-comments.js
├── .github/workflows/            ← build-apk.yml / check-openapi.yml
├── sdk-module/hardware-direct/   ← 唯一 SDK module：胸/頭串口、LED/HeadKey JNI、
│   │                                wire 常數、ubx 解析+播放+變速配樂
│   └── src/main/{java/{hardware/{Direct*,HardwareDirectManager,LocalAlpha2Services,
│   │                       HeadKeyPoller,MouthLedData,ubx/{UbxParser,UbxPlayer,UbxFile,
│   │                       VoiceStream},jni/{SerialPortFile,LedControl,headkey/}},
│   │               jniLibs/armeabi-v7a/, AndroidManifest.xml}
└── app/                          ← com.open.alpha2（MainActivity 路由+MCP、HttpServer、
    │                               WebSocketServer、EventBus、TTS/音樂/電台/相機/小智…）
    ├── src/main/{cpp/ (easyopus), assets/web/ (面板+Blockly), jniLibs/ (已清空，見上)}
    └── build.gradle (versionName "beta 4") / debug.keystore
```

## CI（push 即跑）

- `build-apk`：JDK 11 Temurin → Node strip web 註解 → `assembleDebug` → 上傳 APK。
- `check-openapi`：drift（code case 必入 spec）→ client 重 gen 比對 → yaml 合法 →
  `versionName` 對 `info.version`。改親 `MainActivity`/`ApiValidator`/spec 會觸發。

## 已知限制

- HTTPS / 瀏覽器麥克風（walkie-talkie）永久停用；「聽機械人」正常。
- 舊 AIDL passthrough（進階分頁大部份）回 `NOT_INIT`；`RobotStub` 只係誠實失敗嘅 facade。
- 無舵機電流回授；聲納圖表等部份 UI 仍只畫 triggered。角度方面：單粒 cmd6
  實讀已證實可用（`06 [00] [id] [hi] [lo]`，同 `servo/one` 同單位，跟位誤差約
  1°），但硬件連讀會整冧全機出力（15ms／100ms 兩種節奏都試過，Lynx 嗰邊
  同樣結論），故 SERVO 分頁個榜顯示命令位姿（零 wire：跳舞逐幀＋servo/one
  逐粒追踪，開機伸展後即已知），單粒即時驗證行 `servo/angle`。
- 複合編排動作嘅多 block 窗口偏移忽略；歌尾可以長過舵機（跟官方）。
- 跳大舞嗰陣 USB 易震甩（實測多次），長驗證建議先固定條線或用 `adb logcat` 內錄。
