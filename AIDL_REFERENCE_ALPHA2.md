# Alpha2Services AIDL 參考手冊

依家 SDK 入面 `com.ubtechinc.alpha2serverlib.aidlinterface` 呢個 package 底下嘅 **17 個 AIDL interface**（+1 個自訂 Parcelable：`ASRRecord`）嘅完整用法。全部已經對照 **Alpha2Services v1.1.7.3.20（20170918171435-5mic）** 反編譯出嚟嘅 `Stub.onTransact()` 驗證過方法順序同簽名。

> **AIDL 基本規則**：方法喺 `.aidl` 入面嘅**宣告順序**決定咗 Binder transaction id（由 0 開始逐個編號）。順序錯咗、少咗、或者參數個數錯咗，call 唔會即刻報錯，而係會**靜靜哋讀錯位**（讀多咗/少咗 bytes），令之後所有欄位都讀錯——呢個係最危險嘅 bug 類型，所以千祈唔好自己憑感覺改順序或者刪方法。

---

## 目錄

按用途分咗 4 組：

1. [語音（Speech）](#1-語音-speech) —— `ISpeechInterface` 同佢嘅 6 個 callback/data interface
2. [動作（Action）](#2-動作-action) —— `IAlphaActionService` 同佢嘅 2 個 callback interface
3. [序列埠 / LED / 藍牙](#3-序列埠--led--藍牙) —— `IAlpha2SerialPortService`、`IAlpha2BlueToothSerialPortService`、`IAlpha2SerialPortRcvClient`
4. [自訂訊息（XMPP）](#4-自訂訊息-xmpp) —— `IAlpha2XmppListener`、`IAlpha2XmppCallBack`

---

## 1. 語音（Speech）

### 1.1 `ISpeechInterface` —— 主介面

**Bind action**（`Intent` + `setPackage("com.ubtechinc.alpha2services")`）：

| Action 常數 | 字串值 | 用途 |
|---|---|---|
| `Alpha2Intent.ALPHA_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.SpeechServices` | 通用別名，由機身韌體決定實際綁去邊個引擎 |
| `Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.NuanceSpeeckServices` | 直接綁 Nuance 引擎（英文） |
| `Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.IflytekSpeeckServices` | 直接綁 iFlytek（訊飛）引擎（中文） |

SDK 入面 `Alpha2SpeechMainServiceUtil` 已經包裝咗呢個 bind 流程，一般唔使自己叫 `bindService`。

**完整方法清單**（依 `.aidl` 宣告順序 = transaction id 順序）：

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSpeechCallBackListener` | `ISpeechCallBackListener callBack` | 註冊接收 ASR 結果／TTS 播放完畢通知嘅 listener。回傳 session id（`int`） |
| 1 | `unRegisterSpeechCallBackListener` | `ISpeechCallBackListener callBack` | 取消註冊 |
| 2 | `onSpeech` | `listener, String text` | 「假裝聽到」某段文字——文字直接注入去語意理解層，唔經真正麥克風／ASR。用嚟做 dictation 模擬輸入 |
| 3 | `onStopSpeech` | `listener` | 停止上面嘅模擬輸入 |
| 4 | `onPlay` | `listener, String text, String strVoiceName, String language, int priority` | 播 TTS，**低優先級**（唔會打斷正在播緊嘅嘢）。`priority` 用 `0` 做安全預設 |
| 5 | `onPlayHigh` | 同 `onPlay` | 播 TTS，**高優先級**（會打斷正在播緊嘅嘢） |
| 6 | `onStopPlay` | `listener` | 停止 TTS 播放 |
| 7 | `setWakeState` | `boolean onWake` | ⚠️ **唔係「開始聆聽」**！呢個淨係將 mic 擁有權喺 app 同 alpha2services 之間轉手。**方向已用 logcat 實測修正**（見下面說明）：`true` = **app 攞返 mic**（釋放俾 app），`false` = **交返俾機械人**。內部行為係將 ASR 引擎設做 idle 同 destroy 現有 recognition session，**唔會主動觸發辨識** |
| 8 | `onTextUnderstand` | `String strText, IAlphaTextUnderstandListener listener` | 對一段文字做語意理解（NLU），唔經語音 |
| 9 | `initSpeechGrammar` | `String strGrammar, ISpeechGrammarInitListener listener` | 初始化語法式（受限詞彙）辨識規則 |
| 10 | `startSpeechGrammar` | `ISpeechGrammarListener listern` | 開始語法式辨識（注意：原廠 `.aidl` 個參數名就係打錯字 `listern`，唔好自己「修正」佢，會撞到 codegen） |
| 11 | `stopSpeechGrammar` | — | 停止語法式辨識 |
| 12 | `stopSpeechAndEnterIdleMode` | — | 停止所有語音活動，入 idle |
| 13 | `setRecognizedLanguage` | `String strLanguage` | 設定辨識語言，`"zh_cn"` / `"en_us"`。⚠️ **呢個係 advisory-only**：喺呢部機嘅 firmware 上，唔會真正切換 active engine（見下面「引擎選擇」段落）——`zh_cn`/`en_us` 淨係傳俾 active engine 嘅語言提示，唔保證 active engine 本身係咪 iFlytek/Nuance |
| 14 | `setVoiceName` | `String strVoiceName` | 設定 TTS 聲音（淨係 iFlytek 命名聲音先有效） |
| 15 | `onEnglishUnderstand` | `IAlphaEnglishUnderstandListener listener` | 註冊英文語意理解 listener |
| 16 | `setEnglishOfflineListener` | `IAlphaEnglishOfflineUnderstandListener listener` | 註冊離線英文語意理解 listener |
| 17 | `setSelfInterrupt` | `boolean isInterrupt` | 開關「自我打斷」（機械人講嘢中途畀人講嘢打斷，中文限定） |
| 18 | `setStartEarLed` | — | 觸發耳朵 LED 嘅 wake 效果（無參數） |
| 19 | **`startSpeechNoWakeup`** | `ISpeechCallBackListener listener` | ⚠️ **名不副實**：實測（`logcat_2026-07-02_13-38-32.txt`）顯示呢個淨係將 speech engine 撥入內部 `SPEECH_STATE_WAKEUP` 狀態（`SpeechManager "what:3"`, `IflytekWakeUp5mic.startRecording`），**真正嘅辨識**（`IflyteckASR5mic "startSpeechASR type:0"`, `"Listening..."`）**仲要等硬件 `MicArray wakeup` 獨立觸發，實測相隔 ~20 秒**——唔係文件名同下面舊描述講嘅「直接開始辨識」。結果經傳入嘅 `listener`（一般就傳返 `registerSpeechCallBackListener` 嗰個）嘅 `onCallBack` 送返嚟。呢個測試係喺 Nuance binding 底下做嘅，iFlytek binding 底下行為未同步驗證 |
| 20 | `disableTTSPause` | `boolean disable` | 控制 TTS 播放中途暫停行為 |
| 21 | `startLocalFunction` | `String strFunction` | 觸發機身本地功能（具體接受咩字串未反查） |
| 22 | `registerReplayContentListener` | `IReplaySpeechCallback listener` | 註冊接收「回放」語音記錄（`ASRRecord`），見 [1.7](#17-ireplayspeechcallback--asrrecord) |

**⚠️ 重要行為（原本 SDK 缺失、已補）**：

`speech_SetMIC(true)`（對應 `setWakeState(true)`）**唔會觸發辨識**——佢淨係轉 mic 擁有權，唔會令機械人自己開始聆聽。用 `startSpeechNoWakeup` 可以更早將 engine 撥入 wake-word-receptive 狀態，但都唔係「即刻聽到」（見上面 #19 嘅修正）。

**⚠️ 方向修正（2026-08）**：上面表格 #7 早前寫「`true` = 釋放俾機械人」，係憑 `onWake` 呢個參數名字面意思估嘅，**方向估錯咗**。實測（logcat）證實相反：app 自己開 `AudioRecord`/`AudioTrack` 之前一定要先 call `setWakeState(true)`，唔係就會撞到 `status -38`（`AudioPolicyManager: "startInput failed: other input already started"`）同 `AudioTrack` `STATE_UNINITIALIZED`——即係話 `alpha2services` 嗰陣仲揸住 mic/audio hardware，`setWakeState(true)` 先係將硬件**釋放俾 app** 嘅 call；`false` 先係交返俾機械人。表格已經改正。呢次教訓：`androguard` 反查 bytecode 淨係證明到方法簽名同 transaction id 順序，證明唔到 boolean 參數嘅實際語意方向——語意方向一定要用 logcat 實測驗證，唔可以淨憑參數名估。

**⚠️ 引擎選擇（Nuance／iFlytek）——之前結論已修正**：

之前呢份文件（同 app 內部 comment）一路假設「呢部機淨係 Nuance work，iFlytek 唔係 active engine」。呢個結論**係喺冇用中文/iFlytek 專屬 grammar 測試過嘅情況下得出**（原本測試者唔識中文），只可以證明「用英文/`setRecognizedLanguage` advisory hint 呢種方式試唔到 iFlytek」，**唔可以推翻「iFlytek 呢條 binding path 完全用唔到」**。

`Alpha2SpeechMainServiceUtil` 底層其實一早已經有一個 5 參數 constructor，俾人直接傳 `Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER` 或 `Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`，強制綁去指定引擎，完全繞過個「通用別名」`ALPHA_SPEECH_MAIN_SERVER`（由機身韌體自己決定路由）。**之前呢條 direct-binding 路徑淨係喺 TTS 側試過（引致 playback 完全壞晒所以撤回），ASR 側從未試過。**

而家 `Alpha2RobotApi` 已加返 `initSpeechApi(..., String speechServiceAction)` 呢個 overload、同 `speech_switchEngine(...)`（喺 runtime unbind 現有連接、重新用指定 action bind），App 側加咗 `speech/set_asr_engine` API 同 UI 「切換引擎」掣，可以喺 ASR 呢邊獨立測試直接綁 iFlytek 嘅效果。**用中文對住機械人講嘢，配合切咗去 iFlytek 嘅 binding，先係一個未試過、值得驗證嘅新路徑**——之前冇反應唔代表 iFlytek 硬件路徑本身壞咗，反而好可能係之前用嘅測試方法（`setRecognizedLanguage` advisory hint、英文母語測試者）本身就唔會觸發到 iFlytek 嗰套 wake word/grammar。

**典型用法（觸發一次手動辨識）**：

```java
// 1. 揀語言／引擎
robot.speech_setRecognizedLanguage("zh_cn");   // iFlytek，或 "en_us" 用 Nuance

// 2. 直接開始聆聽（唔使等 wake word）
robot.speech_startSpeechNoWakeup();

// 3. 結果經 IAlpha2SpeechClientListener.onServerCallBack(String text) 送返嚟
//    （呢個已經喺 Alpha2RobotApi.initSpeechApi() 嗰陣 wire 好）
```

---

### 1.2 `ISpeechCallBackListener` —— ASR／TTS 結果 callback

由 app 實作，傳俾 `registerSpeechCallBackListener` / `startSpeechNoWakeup`。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onCallBack` | `int type, String text` | 辨識結果／中間狀態回報。`type` 用途未完全反查，`text` 係辨識出嚟嘅文字 |
| `onPlayEnd` | `boolean isEnd` | TTS 播放完畢通知 |

---

### 1.3 `IAlpha2SpeechClientListener` —— app 對外暴露嘅 speech listener

SDK 入面 `Alpha2RobotApi.initSpeechApi()` 接受呢個 listener，將 `ISpeechCallBackListener.onCallBack` 轉發過嚟。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onServerCallBack` | `String text` | ASR 辨識結果文字（最終落到你手嗰個） |
| `onServerPlayEnd` | `boolean isEnd` | TTS 播放完畢 |

**用法**：

```java
robot.initSpeechApi(new IAlpha2RobotClientListener() {
    @Override
    public void onServerCallBack(String text) {
        // 呢度攞到辨識文字
    }
    @Override
    public void onServerPlayEnd(boolean isEnd) { ... }
});
```

---

### 1.4 `IAlphaTextUnderstandListener` —— 文字語意理解結果

配合 `ISpeechInterface.onTextUnderstand()` 用。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onAlpha2UnderStandError` | `int nErrorCode` | 理解失敗 |
| `onAlpha2UnderStandTextResult` | `String strResult` | 理解結果（通常係 JSON） |

---

### 1.5 `IAlphaEnglishUnderstandListener` / `IAlphaEnglishOfflineUnderstandListener`

配合 `onEnglishUnderstand()` / `setEnglishOfflineListener()` 用，兩個 interface 結構一樣，分別對應線上／離線英文理解引擎。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onAlpha2EnglishUnderstandResult` / `onAlpha2EnglishOfflineUnderstandResult` | `String strResult` | 理解結果 |

---

### 1.6 `ISpeechGrammarInitListener` / `ISpeechGrammarListener`

配合語法式（受限詞彙）辨識用（`initSpeechGrammar` / `startSpeechGrammar`）。

**`ISpeechGrammarInitListener`**：

| 方法 | 參數 | 用途 |
|---|---|---|
| `speechGrammarInitCallback` | `String grammarID, int nErrorCode` | 語法初始化完成回報 |

**`ISpeechGrammarListener`**：

| 方法 | 參數 | 用途 |
|---|---|---|
| `onSpeechGrammarResult` | `String strResultType, String strResult` | 語法辨識命中結果 |
| `onSpeechGrammarError` | `int nErrorCode` | 辨識錯誤 |

---

### 1.7 `IReplaySpeechCallback` + `ASRRecord`

配合 `ISpeechInterface.registerReplayContentListener()` 用——呢個 SDK 原本完全冇呢個檔案，係呢次新加返嘅。用途睇個名應該係接收機械人內部記錄低嘅語音辨識歷史記錄（「回放」）。

**`IReplaySpeechCallback`**：

| 方法 | 參數 | 用途 |
|---|---|---|
| `onRelpayContent` | `in ASRRecord record`（注意方法名本身係原廠打錯字 `Relpay`，唔係 `Replay`，唔好自己改） | 送一筆歷史語音記錄過嚟 |

**`ASRRecord`**（自訂 Parcelable，7 個欄位，順序同型別已由 `writeToParcel`/`readFromParcel` bytecode 驗證）：

| 欄位（getter/setter） | 型別 | 語意來源 |
|---|---|---|
| `getExtra1()` / `setExtra1()` | `String` | ⚠️ 冇搵到語意來源，字面順序上係第一個欄位 |
| `getRecordId()` / `setRecordId()` | `String` | 對應機身內部 `ReplaySpeechRcord.getRecordId()` |
| `getMsgLanguage()` / `setMsgLanguage()` | `String` | 對應 `ReplaySpeechRcord.getMsgLanguage()` |
| `getContent()` / `setContent()` | `String` | 對應 `ReplaySpeechRcord.getContent()`——呢個應該就係辨識出嚟嘅文字內容 |
| `getContentLinks()` / `setContentLinks()` | `String` | 對應 `ReplaySpeechRcord.getContentLinks()` |
| `getLabelId()` / `setLabelId()` | `int` | 對應 `ReplaySpeechRcord.getLabelId()` |
| `getExtra2()` / `setExtra2()` | `String` | ⚠️ 冇搵到語意來源，字面順序上係最後一個欄位 |

> `extra1`／`extra2` 呢兩個名係中性佔位命名，唔係原廠語意。Wire format（順序／型別）保證同機身一致，但呢兩個欄位實際裝住咩要留意實測。

---

## 2. 動作（Action）

### 2.1 `IAlphaActionService` —— 主介面

**Bind action**：`Alpha2Intent.ALPHA_ACTION_SERVER` = `com.ubtechinc.services.AlphaActionServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerActionClient` | `IAlphaActionClient client` | 註冊接收「動作播放完畢」通知嘅 listener，回傳 session id |
| 1 | `unRegisterActionClient` | `client` | 取消註冊 |
| 2 | `playActionFile` | `String strActionFile` | 播放一個動作檔案（`.act` 之類，睇檔案路徑） |
| 3 | `playActionName` | `String strActionName` | 用名稱播放一個內建動作 |
| 4 | `stopActionPlay` | — | 停止當前動作播放 |
| 5 | `onEventHandlerTrigger` | `int nEventType, in byte[] param` | 觸發某個事件處理（具體事件類型未反查） |
| 6 | `isCompleted` | — | 查詢動作是否播放完畢 |
| 7 | `getActionList` | `IAlphaActionListListener listener` | 攞返機身內建動作清單（透過 callback 送返） |
| 8 | `disableActionPlay` | `boolean disable` | ⭐ **原本 SDK 缺失、已補**——開關動作播放功能 |
| 9 | `isActioning` | — | ⭐ **原本 SDK 缺失、已補**——查詢機械人是否正喺度做緊動作 |

---

### 2.2 `IAlphaActionClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onActionStop` | `String strActionFileName` | 通知某個動作檔案播放完畢／停止 |

### 2.3 `IAlphaActionListListener`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onGetActionList` | `String list` | 動作清單（單一編碼字串，格式未反查，估計係逗號／換行分隔） |

---

## 3. 序列埠 / LED / 藍牙

### 3.1 `IAlpha2SerialPortService` —— 胸口／頭部序列埠（含 5-mic LED）

**Bind action**：
- 胸口：`Alpha2Intent.ALPHA_SERIAL_SERVER` = `com.ubtechinc.services.AlphaSerialPortServices`
- 頭部：`Alpha2Intent.ALPHA_SERIAL_HEADER_SERVER` = `com.ubtechinc.services.AlphaSerialPortHeaderServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSerialPortRcvListener` | `IAlpha2SerialPortRcvClient cb` | 註冊接收序列埠回傳資料嘅 listener |
| 1 | `unRegisterSerialPortRcvListener` | `cb` | 取消註冊 |
| 2 | `sendCommand` | `byte nSessionID, byte nCmd, in byte[] nParam, int nLen` | 送一個封裝好嘅序列埠命令幀 |
| 3 | `sendRawData` | `in byte[] data, int nLen` | 送原始 byte data（唔經 `sendCommand` 嘅封裝） |
| 4 | `stop5MicEyeLED` | — | 熄眼部 5-mic LED |
| 5 | `stop5MicEarLED` | — | 熄耳部 5-mic LED |
| 6 | `ledSetEye5Mic` | `int p1..p8` | 設定眼部 5-mic LED（8 個 int 參數，具體對應顏色／位置未完全反查，直接去 `com.ubtechinc.mic5.LedControl` 原生方法，唔經 `sendCommand` 幀） |
| 7 | `ledSetHead5Mic` | `int p1..p8` | 設定頭部 5-mic LED（同上） |
| 8 | `getRobotSerialNumber` | — | 攞機身序號 |

### 3.2 `IAlpha2SerialPortRcvClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onListenSerialPortRcvData` | `in byte[] bytes, int len` | 收到序列埠回傳原始資料 |

### 3.3 `IAlpha2BlueToothSerialPortService` ⭐ 原本 SDK 完全冇，已新加

**Bind action**：`Alpha2Intent.ALPHA_BLUETOOTHSERIAL_SERVER` = `com.ubtechinc.services.AlphaBlueToothSerialPortServices`

同 `IAlpha2SerialPortService` 結構好相似，但係走藍牙而唔係機身內部序列埠，**冇** 5-mic LED 嗰部分：

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSerialPortRcvListener` | `IAlpha2SerialPortRcvClient cb` | 註冊 listener（同 3.1 共用同一個 `IAlpha2SerialPortRcvClient`） |
| 1 | `unRegisterSerialPortRcvListener` | `cb` | 取消註冊 |
| 2 | `sendCommand` | `byte nSessionID, byte nCmd, in byte[] nParam, int nLen` | 送封裝好嘅藍牙序列命令幀 |
| 3 | `sendATCMD` | `String cmd` | 送 AT 指令（藍牙模組常見控制方式） |

---

## 4. 自訂訊息（XMPP）

### 4.1 `IAlpha2XmppListener` —— 主介面

**Bind action**：`Alpha2Intent.ALPHA_XMPP_SERVER` = `com.ubtechinc.services.Alpha2XmppServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerXmppCallBackListener` | `String appID, IAlpha2XmppCallBack callBack` | 用 app 識別碼註冊接收訊息嘅 listener，回傳 session id |
| 1 | `unRegisterXmppCallBackListener` | `callBack` | 取消註冊 |
| 2 | `sendCustomXmppMessage` | `int type, String appID, String message` | 送一個自訂訊息（`type` 用途未反查，估計係訊息分類） |

### 4.2 `IAlpha2XmppCallBack`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onReceiveMessage` | `String message` | 收到自訂訊息 |

---

## 附錄：全部 Bind Action 一覽（`Alpha2Intent.java`）

呢啲字串（包括打錯字嘅 `"Speeck"`）係機械人實際註冊嘅 action name，係 wire contract 嘅一部分，唔可以「修正」錯字：

```java
ALPHA_SERIAL_SERVER            = "com.ubtechinc.services.AlphaSerialPortServices"
ALPHA_SERIAL_HEADER_SERVER     = "com.ubtechinc.services.AlphaSerialPortHeaderServices"
ALPHA_SOCKET_SERVER            = "com.ubtechinc.services.Alpha2SocketServices"      // 未有對應.aidl
ALPHA_ACTION_SERVER            = "com.ubtechinc.services.AlphaActionServices"
ALPHA_BLUETOOTHSERIAL_SERVER   = "com.ubtechinc.services.AlphaBlueToothSerialPortServices"
ALPHA_MAIN_SERVER              = "com.ubtechinc.services.MainService"               // 未有對應.aidl
ALPHA_NUANCE_SPEECH_MAIN_SERVER  = "com.ubtechinc.services.NuanceSpeeckServices"
ALPHA_IFLYTEK_SPEECH_MAIN_SERVER = "com.ubtechinc.services.IflytekSpeeckServices"
ALPHA_SPEECH_MAIN_SERVER       = "com.ubtechinc.services.SpeechServices"
ALPHA_XMPP_SERVER              = "com.ubtechinc.services.Alpha2XmppServices"
```

`ALPHA_SOCKET_SERVER`（`Alpha2SocketServices`）同 `ALPHA_MAIN_SERVER`（`MainService`）呢兩個 action 有註冊常數，但佢哋對應嘅 AIDL interface **未喺呢次 17 個之內**，即係話呢兩個 service 綁咗之後用邊個 interface 溝通仲未反查。如果後續要用呢兩個 service，要再對照 APK 搵返佢哋各自嘅 `Stub` class。

---

## 反編譯方法（如果之後要再對其他版本）

呢份文件全部數據都嚟自用 `androguard` 直接讀取 APK 嘅 `ISomeInterface$Stub.onTransact()` bytecode，抽取個 `sparse-switch` 入面實際 call 緊邊個方法、幾多個參數、咩型別——呢個係 transaction id 嘅 ground truth，比起靠 class/method 名或者 `TRANSACTION_*` 常數個名嚟推斷更可靠（後者順序未必反映真實 declaration order）。如果要對另一個版本嘅 `alpha2services` APK 重做呢個分析，流程係：

1. `pip install androguard`
2. 用 `AnalyzeAPK()` load APK
3. 對每個 `I*$Stub` class 搵 `onTransact` method
4. Dump 佢嘅 bytecode instructions，揀出 `invoke-*` 去返個 outer interface 嘅方法（跳過 `asInterface`/`enforceInterface` 呢啲 helper call）
5. 呢個順序就係 `.aidl` 要跟嘅順序；每個 call 嘅參數個數同型別就係方法簽名
