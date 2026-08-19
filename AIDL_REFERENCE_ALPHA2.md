# Alpha2Services AIDL 參考手冊

`com.ubtechinc.alpha2serverlib.aidlinterface` 呢個 package 底下嘅 **17 個 AIDL interface**（+1 個自訂 Parcelable：`ASRRecord`）嘅完整用法，專門對應機身實際安裝嘅 **Alpha2Services v1.1.7.3.20（20170918171435-5mic）**。全部方法順序同簽名已經對照呢個版本反編譯出嚟嘅 `Stub.onTransact()` 驗證過。當中 `disableActionPlay`、`isActioning`、成個 `IAlpha2BlueToothSerialPortService`、`ISpeechInterface` 入面嘅 `setStartEarLed`／`startSpeechNoWakeup`／`disableTTSPause`／`startLocalFunction`／`registerReplayContentListener` 呢幾個係 v1.1.7.3.20 專屬方法（下文個別標註 ⭐）。

> **AIDL 基本規則**：方法喺 `.aidl` 入面嘅**宣告順序**決定咗 Binder transaction id（由 0 開始逐個編號）。順序錯咗、少咗、或者參數個數錯咗，call 唔會即刻報錯，而係會**靜靜哋讀錯位**（讀多咗/少咗 bytes），令之後所有欄位都讀錯——呢個係最危險嘅 bug 類型，所以千祈唔好自己憑感覺改順序或者刪方法。

---

## 目錄

按用途分咗 7 組：

1. [語音（Speech）](#1-語音-speech) —— `ISpeechInterface` 同佢嘅 6 個 callback/data interface
2. [動作（Action）](#2-動作-action) —— `IAlphaActionService` 同佢嘅 2 個 callback interface
3. [序列埠 / LED / 藍牙](#3-序列埠--led--藍牙) —— `IAlpha2SerialPortService`、`IAlpha2BlueToothSerialPortService`、`IAlpha2SerialPortRcvClient`
4. [自訂訊息（XMPP）](#4-自訂訊息-xmpp) —— `IAlpha2XmppListener`、`IAlpha2XmppCallBack`
5. [`sendCommand` 序列埠 Command Byte 完整表](#5-sendcommand-序列埠-command-byte-完整表) —— 胸口／頭部 MCU 用嘅底層 command byte，`sendCommand()` 呢個 AIDL method 之下再一層嘅 wire protocol
6. [`Alpha2RobotApi` 高層 wrapper 行為備忘](#6-alpha2robotapi-高層-wrapper-行為備忘) —— error code 語意、bind 時序、engine 切換嘅已知後果等，call 呢份 SDK 之前要知道嘅實戰細節
7. [相關但唔係 AIDL 嘅普通 Broadcast Intent](#7-相關但唔係-aidl-嘅普通-broadcast-intent) —— PIR／sonar／mute 鍵等事件實際上係經 `Context.sendBroadcast()`／`registerReceiver()`，唔係 Binder call，見呢度先唔會同 AIDL 混淆

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
| 4 | `onPlay` | `listener, String text, String strVoiceName, String language, int priority` | 播 TTS，**低優先級**（唔會打斷正在播緊嘅嘢）。`priority` **恆用 `0`**——`Alpha2SpeechMainServiceUtil.onPlay()` 內部無論 `isTip` 係 true/false 都寫死傳 `0`，呢個係機身側 `SpeechServiceImpl` 自己內部 boolean-flag call 傳落嚟嗰個預設值，未見過任何 caller 傳其他數值，可以當佢係固定安全值，唔使自己另外試 |
| 5 | `onPlayHigh` | 同 `onPlay` | 播 TTS，**高優先級**（會打斷正在播緊嘅嘢）。`priority` 同上，恆用 `0` |
| 6 | `onStopPlay` | `listener` | 停止 TTS 播放 |
| 7 | `setWakeState` | `boolean onWake` | ⚠️ **唔係「開始聆聽」**！呢個淨係將 mic 擁有權喺 app 同 alpha2services 之間轉手：`true` = **app 攞返 mic**（釋放俾 app），`false` = **交返俾機械人**。內部行為係將 ASR 引擎設做 idle 同 destroy 現有 recognition session，**唔會主動觸發辨識**（詳見下面「重要行為」） |
| 8 | `onTextUnderstand` | `String strText, IAlphaTextUnderstandListener listener` | 對一段文字做語意理解（NLU），唔經語音 |
| 9 | `initSpeechGrammar` | `String strGrammar, ISpeechGrammarInitListener listener` | 初始化語法式（受限詞彙）辨識規則。**引擎分野**：`NuanceServiceImpl.initSpeechGrammar()`／`startSpeechGrammar()` 兩個都係完全未實作嘅空 stub（method body 淨係一句 `return-void`），Nuance binding 之下呢兩個 call **必然靜靜哋冧**；`IflytekServiceImpl` 嘅同名 method 有真身實作，會真正建立 `com.iflytek.cloud.SpeechRecognizer`。要試呢組 API 必須先用 `speech_switchEngine()` 切去 iFlytek binding，否則注定冧。`strGrammar` 嘅期望格式（JSON？純文字詞彙表？逗號分隔？）喺 `IflytekServiceImpl` 反編譯出嚟嘅 code 見到淨係被存做一個字串再傳入 `SpeechRecognizer` 初始化流程，確實語法格式未反查到 |
| 10 | `startSpeechGrammar` | `ISpeechGrammarListener listern` | 開始語法式辨識（注意：原廠 `.aidl` 個參數名就係打錯字 `listern`，唔好自己「修正」佢，會撞到 codegen）。同上，Nuance binding 底下係空 stub。SDK 側 `Alpha2SpeechMainServiceUtil.startSpeechGrammar()` 實際傳 `null` 做 AIDL listener，結果改由 grammar 結果轉發經 `registerSpeechCallBackListener` 嗰條 `onCallBack` callback 送返（見 1.2 `onCallBack` 嘅 `type` 用途說明），唔係直接用呢個方法傳入嘅 listener |
| 11 | `stopSpeechGrammar` | — | 停止語法式辨識 |
| 12 | `stopSpeechAndEnterIdleMode` | — | 停止所有語音活動，入 idle |
| 13 | `setRecognizedLanguage` | `String strLanguage` | 設定辨識語言，`"zh_cn"` / `"en_us"`。⚠️ **呢個係 advisory-only**：喺呢部機嘅 firmware 上，唔會真正切換 active engine（見下面「引擎選擇」段落）——`zh_cn`/`en_us` 淨係傳俾 active engine 嘅語言提示，唔保證 active engine 本身係咪 iFlytek/Nuance |
| 14 | `setVoiceName` | `String strVoiceName` | 設定 TTS 聲音（淨係 iFlytek 命名聲音先有效） |
| 15 | `onEnglishUnderstand` | `IAlphaEnglishUnderstandListener listener` | 註冊英文語意理解 listener |
| 16 | `setEnglishOfflineListener` | `IAlphaEnglishOfflineUnderstandListener listener` | 註冊離線英文語意理解 listener |
| 17 | `setSelfInterrupt` | `boolean isInterrupt` | 開關「自我打斷」（機械人講嘢中途畀人講嘢打斷，中文限定） |
| 18 | `setStartEarLed` | — | ⭐ **1.1.7.3.20 專屬方法**——觸發耳朵 LED 嘅 wake 效果（無參數） |
| 19 | **`startSpeechNoWakeup`** | `ISpeechCallBackListener listener` | ⭐ **1.1.7.3.20 專屬方法**。⚠️ **名不副實**：實測（`logcat_2026-07-02_13-38-32.txt`）顯示呢個淨係將 speech engine 撥入內部 `SPEECH_STATE_WAKEUP` 狀態（`SpeechManager "what:3"`, `IflytekWakeUp5mic.startRecording`），**真正嘅辨識**（`IflyteckASR5mic "startSpeechASR type:0"`, `"Listening..."`）**仲要等硬件 `MicArray wakeup` 獨立觸發，實測相隔 ~20 秒**。結果經傳入嘅 `listener`（一般就傳返 `registerSpeechCallBackListener` 嗰個）嘅 `onCallBack` 送返嚟。呢個測試係喺 Nuance binding 底下做嘅，iFlytek binding 底下行為未同步驗證 |
| 20 | `disableTTSPause` | `boolean disable` | ⭐ **1.1.7.3.20 專屬方法**——控制 TTS 播放中途暫停行為 |
| 21 | `startLocalFunction` | `String strFunction` | ⭐ **1.1.7.3.20 專屬方法**——觸發機身本地功能（具體接受咩字串未反查） |
| 22 | `registerReplayContentListener` | `IReplaySpeechCallback listener` | ⭐ **1.1.7.3.20 專屬方法**——註冊接收「回放」語音記錄（`ASRRecord`），見 [1.7](#17-ireplayspeechcallback--asrrecord) |

**重要行為**：

`speech_SetMIC(true)`（對應 `setWakeState(true)`）**唔會觸發辨識**——佢淨係轉 mic 擁有權，唔會令機械人自己開始聆聽。用 `startSpeechNoWakeup` 可以更早將 engine 撥入 wake-word-receptive 狀態，但都唔係「即刻聽到」（見上面 #19）。

`setWakeState` 嘅 `onWake` 參數方向（實測 logcat 驗證，非憑參數名推斷）：app 自己開 `AudioRecord`/`AudioTrack` 之前一定要先 call `setWakeState(true)`，唔係就會撞到 `status -38`（`AudioPolicyManager: "startInput failed: other input already started"`）同 `AudioTrack` `STATE_UNINITIALIZED`——即係話 `alpha2services` 嗰陣仲揸住 mic/audio hardware，`setWakeState(true)` 先係將硬件**釋放俾 app** 嘅 call；`false` 先係交返俾機械人。

**引擎選擇（Nuance／iFlytek）**：

呢部機透過通用別名 `ALPHA_SPEECH_MAIN_SERVER`（由機身韌體自己決定路由）綁定嗰陣，預設落去 Nuance。`Alpha2SpeechMainServiceUtil` 提供一個 5 參數 constructor，可以直接傳 `Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER` 或 `Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`，強制綁去指定引擎，繞過通用別名嘅自動路由。`Alpha2RobotApi` 對應暴露 `initSpeechApi(..., String speechServiceAction)` 呢個 overload、同 `speech_switchEngine(...)`（runtime 重新綁定），App 側有 `speech/set_asr_engine` API 同 UI「切換引擎」掣，可以直接綁 iFlytek 觸發中文辨識。

**⚠️ Direct-engine binding 會令 TTS session 壞死**：`speech_switchEngine()` 直接綁 `ALPHA_NUANCE_SPEECH_MAIN_SERVER`／`ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`，**一 rebind 就會令機身系統進程（`com.ubtechinc.alpha2services`）入面嘅 TTS session 啞咗**——`onPlay`/`onPlayHigh` 仍然回 `API_ERROR_SUCCEED`（Binder call 本身冇拋 `RemoteException`），但完全睇唔到後續嘅 `SpeechServiceImpl`/`IflytekTTS`/`onTTsStart` log，即係話個 call 落咗去一個已經死咗嘅 session，一直要重開機先返到正常。

因為呢個原因，`Alpha2RobotApi` 將 TTS 同 ASR 分開兩條**獨立**嘅 binding：TTS 永遠淨係用 `mSpeechServiceUtil`（一開機用通用 `ALPHA_SPEECH_MAIN_SERVER` 綁一次，永遠唔再 release/rebind）；`speech_switchEngine()` 用另一個獨立欄位 `mAsrServiceUtil` 做 direct-engine binding，`startSpeechNoWakeup`/`setRecognizedLanguage`/grammar 三類 ASR 專屬 call 會跟住 `currentAsrTarget()`（未切換過用 `mSpeechServiceUtil`，切換咗就用 `mAsrServiceUtil`）自動揀。**引擎切換之後，TTS 唔會啞**（因為 TTS binding 一早已經同 ASR binding 分開）；`mAsrServiceUtil` 呢條 binding 本身如果撞到同一種病灶，只會影響到 ASR 呢邊嘅 call，唔會累到 TTS。

`speech_resetToIdle()`（call AIDL transaction #12 `stopSpeechAndEnterIdleMode`，用返冇壞過嘅 `mSpeechServiceUtil` binding 去 call）係試驗性嘅重置入口，想睇下叫唔叫得返個死咗嘅 session、唔使成部機重開機——**呢個方法本身未喺真機驗證過係咪真係解決到問題**，如果冇效就仲要重開機。

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
| `onCallBack` | `int type, String text` | 辨識結果／中間狀態回報，`text` 係辨識出嚟嘅文字。`type` 嘅用途：`Alpha2SpeechMainServiceUtil.SpeechCallBackListenerImpl.onCallBack()` 入面，如果目前有一個 grammar listener 已註冊（即係啱啱 call 過 `startSpeechGrammar`），呢個 callback 會直接當**語法辨識結果**處理，將 `type`／`text` 轉發做 `onSpeechGrammarResult(strResultType, strResult)` 嘅兩個對應參數（`type`→`strResultType`、`text`→`strResult`）；冇 grammar listener 時先當普通 ASR 結果，`text` 轉發去 `onServerCallBack`。即係話 `onCallBack` 呢一個 callback 同時擔當「普通 ASR 結果」同「grammar 辨識結果」兩種用途，由呼叫端有冇註冊緊 grammar listener 決定點解讀，唔係由 `type` 數值本身決定 |
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

配合 `ISpeechInterface.registerReplayContentListener()` 用——⭐ 1.1.7.3.20 專屬（`.aidl` + `ASRRecord.java`）。用途睇個名應該係接收機械人內部記錄低嘅語音辨識歷史記錄（「回放」）。

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
| 8 | `disableActionPlay` | `boolean disable` | ⭐ **1.1.7.3.20 專屬方法**——開關動作播放功能 |
| 9 | `isActioning` | — | ⭐ **1.1.7.3.20 專屬方法**——查詢機械人是否正喺度做緊動作 |

---

### 2.2 `IAlphaActionClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onActionStop` | `String strActionFileName` | 通知某個動作檔案播放完畢／停止 |

### 2.3 `IAlphaActionListListener`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onGetActionList` | `String list` | 動作清單（單一編碼字串——格式見下） |

**Wire format**：整個 `list` 用 `"##"` 分隔成一串扁平嘅欄位，**每 4 個欄位一組**，對應一個動作：

```
id##type##中文名##英文名##id##type##中文名##英文名## ...
```

即係 `fields.length % 4 == 0`，第 `i` 個動作嘅四個欄位喺 `fields[4*i]`（id）、`fields[4*i+1]`（type）、`fields[4*i+2]`（中文名）、`fields[4*i+3]`（英文名）。呢個 parser 已經喺 `AlphaActionServiceUtil.ActionListListenerImpl.onGetActionList()` 實作咗（`.split("##")` + 逐 4 個一組讀），`app_client` 側嘅 `IAlpha2ActionListListener.onGetActionList(ArrayList<ArrayList<String>>)` 攞到嘅已經係拆好嘅結果，唔使自己再 parse 一次。如果 `fields.length % 4 != 0`（即係收到嘅字串損壞或者格式唔啱預期），呢個 parser 會直接回傳一個空 list，唔會拋例外，但都唔會夾硬解讀部分資料。

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
| 2 | `sendCommand` | `byte nSessionID, byte nCmd, in byte[] nParam, int nLen` | 送一個封裝好嘅序列埠命令幀。呢個方法之下嘅 cmd byte 完整表見 [第 5 節](#5-sendcommand-序列埠-command-byte-完整表) |
| 3 | `sendRawData` | `in byte[] data, int nLen` | 送原始 byte data（唔經 `sendCommand` 嘅封裝） |
| 4 | `stop5MicEyeLED` | — | 熄眼部 5-mic LED |
| 5 | `stop5MicEarLED` | — | 熄耳部 5-mic LED |
| 6 | `ledSetEye5Mic` | `int p1..p8` | 設定眼部 5-mic LED（8 個 int 參數，語意見下面「5-mic LED 參數表」） |
| 7 | `ledSetHead5Mic` | `int p1..p8` | 設定頭部 5-mic LED（同上） |
| 8 | `getRobotSerialNumber` | — | 攞機身序號 |

**5-mic LED 存在原因**：`Alpha2SerialHeaderServiceUtil` 嘅 comment 確認，喺 5mic 頭板（1.1.7.3 呢代）上，`sendCommand` 傳統嘅 `LED_EAR`／`LED_EYE` command byte（見第 5 節）雖然可以送到頭部序列埠（唔會拋 `RemoteException`，`bindReady` 都係 `true`），但**頭部 5mic MCU 已經唔再回應呢啲舊 command**——只有 `ledSetEye5Mic`／`ledSetHead5Mic` 呢兩個獨立 AIDL 方法先有效。呢兩個方法**唔經 `sendCommand` 嘅序列幀封裝**，直接去機身側 `com.ubtechinc.mic5.LedControl` 原生驅動。

**5-mic LED 參數表**（`p1`..`p8`，喺呢部機真機測試確認）：

| 位置 | 意義 | 已知數值 |
|---|---|---|
| `p1` | 顏色 (colorType) | 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白，其他數值無效 |
| `p2` | 亮度 (brightness) | 1=最暗 .. 9=最光，其他數值無效 |
| `p3`／`p4` | 右／左 LED 選擇器 | 呢個 app 冇獨立分開兩邊控制，成日一齊送同一個值 |
| `p5` | upTime (ms) | 配合所揀模式塑造個循環時序 |
| `p6` | downTime (ms) | 同上 |
| `p7` | runTime (ms) | 效果維持幾耐；`Integer.MAX_VALUE` = 長開（一直維持） |
| `p8` | 模式 (mode) | **頭／耳同眼睛用嘅數值範圍唔一樣**：頭／耳 0=閃(flash) 1=呼吸燈(breathing) 3=跑馬燈(chase) 5=雙色燈(dual-colour)；眼 0=閃(flash) 1=跑馬燈(chase) 3=雙色燈(dual-colour)（冇呼吸燈模式） |

已確認嘅 `(p5,p6)` preset 組合：長開 `p5=MAX,p6=0`；跑馬燈 `p5=100,p6=0`；閃 `p5=100,p6=100`；呼吸燈（只限頭／耳）`p5=5,p6=20`；雙色燈 `p5=500,p6=0`。

**⚠️ 兩個獨立、唔矛盾嘅真機結果，睇清楚邊個係邊個**：

1. `led/head/set`／`led/eye/set`（主控制面板 UI 用嗰組固定 preset）——**喺呢部機已經真機確認生效**，用嘅正正就係上面呢張參數表。
2. `applyObstacleIndicator()`（sonar／PIR 觸發嘅指示燈，另一個獨立 code path，一樣係 call `header_ledSetHead5Mic`／`header_ledSetEye5Mic`，但用嘅唔係 UI 嗰組 preset 參數）——**喺呢部機實測全部 preset 都回 `API_ERROR_FAILED`**（`bindReady:true`，即係唔係未 ready，係機身呢一刻真係唔接受）。

兩者用嘅係**同一組 AIDL 方法**，喺**同一部機**上，結果卻唔一樣——目前未查到確實原因（可能同觸發時機、參數組合細節、或者當刻機身內部狀態有關），但已經排除咗「呢部機頭板唔支援 5-mic LED」呢個猜測（因為第 1 點已經證實生效）。`applyObstacleIndicator()` 失敗時唔會拋例外中斷流程，同時會 fallback 去閃 Mouth LED（見下面 Mouth LED 獨立段落）。

另外要留意：alpha2services 內部 wake-word／mic 邏輯本身有一個**持續運作嘅「假熄燈」循環**——`AlphaMainSeviceImpl` 每隔約 0.8~2 秒左右就會自己 call 一次 `header_ledSetHead5Mic(color=3,brightness=2,...,p5=400,p6=9000,p8=2)` 呢組固定參數嚟做「熄燈」效果（本質係設一個好暗嘅圖案，唔係真正斷電）。呢個內部循環會同任何自己想長期維持嘅頭／眼 LED 狀態相撞，肉眼睇到就係「著一陣又熄」。呢個 app 嘅做法係開一條低延遲（80ms 間隔）嘅背景 thread 持續補發自己想要嘅顏色，蓋過呢個內部循環嘅效果，唔係徹底停止到佢。

### 3.2 `IAlpha2SerialPortRcvClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onListenSerialPortRcvData` | `in byte[] bytes, int len` | 收到序列埠回傳原始資料 |

### 3.3 `IAlpha2BlueToothSerialPortService` ⭐ 1.1.7.3.20 專屬 interface

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

## 5. `sendCommand` 序列埠 Command Byte 完整表

`IAlpha2SerialPortService.sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen)`（第 3.1 節 transaction #2）本身淨係一個通用嘅信封——真正做嘢嘅係 `nCmd` 呢個 byte，決定咗胸口／頭部微控制器（MCU）實際執行邊個動作。呢張表嚟自 `com.ubtechinc.constant.StaticValue` 呢個常數類（同 SDK 一齊派發，並非反編譯估計），係胸口／頭部 MCU 全部已知 command byte 嘅完整清單。標示「✅ 已用」嘅係呢個 App 已經實際 call 緊、有實測結果嘅；淨係列喺表入面但冇標示嘅，係 SDK 派發但呢個 App 未用過、未經驗證嘅常數。

**胸口 MCU（chest，經 `Alpha2Intent.ALPHA_SERIAL_SERVER` bind）**：

| Byte（十進位） | 常數名 | 用途 | 狀態 |
|---:|---|---|---|
| 1 | `CHEST_CMD_START` | 開始 | 未用過 |
| 2 | `CHEST_CMD_STOP` | 停止 | 未用過 |
| 3 | `CHEST_CMD_SENDMOTOR` | 送馬達指令 | 未用過 |
| 4 | `CHEST_CMD_SETTING` | 設定（見下）| ✅ 已用——`chest_configureSonar()` 用嚟設定聲納，sub-command byte 見下面說明 |
| 5 | `CHES_CMD_MOTORANGLE` | 單一伺服角度 | ✅ 已用——`chest_SendOneFreeAngle(id, angle, time)`，payload = `[id, angle高位byte, angle低位byte, time(2 bytes)]` |
| 6 | `CHES_CMD_READANGLE` | 讀伺服角度 | 未用過 |
| 7 | `CHES_CMD_PAUSE` | 暫停 | 未用過 |
| 9 | `CHES_CMD_STOP` | 停止 | 未用過 |
| 20 | `CHES_CMD_WIFISTATUS` | WiFi 狀態 | 未用過 |
| 21 | `CHES_CMD_ACTION` | 動作 | 未用過 |
| 22 | `CHES_CMD_BTSTATUS` | 藍牙狀態 | 未用過 |
| 25 | `CHES_CMD_MOTOR_POWER` | 馬達電源 | 未用過 |
| 26 | `CHES_CMD_SAVE_ALARM` | 儲存鬧鐘 | 未用過 |
| 27 | `CHES_CMD_END_ALARM` | 結束鬧鐘 | 未用過 |
| 28 | `CHES_CMD_AJUST_TIME` | 調校時間 | 未用過 |
| 29 | `CHES_CMD_READ_TIME` | 讀取時間 | 未用過 |
| 30 | `CHES_CMD_READ_ALARM` | 讀取鬧鐘 | 未用過 |
| 48 | `CHES_CMD_START_UPDATE` | 開始韌體更新 | 未用過（危險，勿亂試） |
| 49 | `CHES_CMD_UPDATE_PAGE` | 更新分頁 | 未用過（危險） |
| 50 | `CHES_CMD_UPDATE_END` | 更新結束 | 未用過（危險） |
| 51 | `CHEST_READ_VERSION` | 讀取版本 | 未用過 |
| 52 | `CHEST_SET_ALL_ANGLE` | 全部 20 顆伺服角度 | ✅ 已用——`chest_SendFreeAngle(int[20], time)`，payload = `[20 個 angle byte, time(2 bytes)]` |
| 53 | `CHEST_CHANGE_PLAY` | 切換播放 | 未用過 |
| 54 | `CHEST_WRITE_SID_EEPROM` | 寫伺服 ID EEPROM | 未用過（危險，會改伺服硬件 ID） |
| 55 | `CHEST_READ_SID_EEPROM` | 讀伺服 ID EEPROM | 未用過 |
| 56 | `CHEST_SQUAT` | 蹲下 | 未用過 |
| 64 | `CHEST_POWER_SAVE` | 省電模式 | 未用過 |
| 72 | *(冇對應常數名)* | PIR 感應器開關 | ⚠️ **未喺呢部機實測撞中過**——`chest_setPirSensorEnabled()` 用緊呢個 cmd 值，但兩份提供咗嘅 logcat 入面，胸口收到嘅 cmd 淨係見過 `-109`/`-111`/`-115`，從未見過 `72`。送出去之後要睇 logcat／`onListenSerialPortRcvData` 嘅 ack 幀先知機身有冇反應，喺實測撞中之前當「未證實嘅假設」睇待 |
| -128 | `CHEST_SEND_POWER` | 電量回報（接收方向） | 未用過 |
| -127 | `CHES_SEND_OBSTACLE` | 障礙物／聲納（接收方向）| ⚠️ 見下面「聲納讀數唔行呢條路」段落——理論上係聲納 raw 幀嘅 cmd byte，但實測聲納讀數根本唔會經呢個 AIDL rcv callback 送到，真正生效嘅路徑係獨立 broadcast `SONAR_DISTANCE_ACTION`（見第 7 節） |
| -126 | `CHES_SEND_ANGLEINFO` | 角度回報（接收方向） | 未用過 |
| -125 | `CHES_SEND_SHUTDWON` | 關機通知（接收方向） | 未用過 |
| -121 | `CHES_SEND_ALARM` | 鬧鐘通知（接收方向） | 未用過 |
| -119 | `CHEST_TOUCH_BOARD` | 觸摸板（接收方向） | 未用過 |
| -118 | `CHES_DC_STATE` | 直流電狀態（接收方向） | 未用過 |
| -115 | *(冇對應常數名)* | *(未知，實測見過)* | ⚠️ logcat 見過胸口 broadcast 收到呢個值，但 `StaticValue` 冇對應常數名，用途未反查 |
| -111 | *(冇對應常數名，0x91)* | 心口 mute 鍵按下 | ✅ **已喺真機確認會實際觸發**（logcat 見到 `ches cmd = -111`，raw wire frame `f8 8f 08 00 00 91 01 9a ed` / `f8 8f 08 00 00 91 00 99 ed`）。呢個唔係經 `IAlpha2SerialPortService` 嘅 AIDL rcv callback 收到，係經 `CHEST_ACTION`（`"com.ubtechinc.services.chest"`）呢個普通 broadcast 嘅 `"value"` extra（`byte[]`）入面搵到，詳見第 7 節 |
| -110 | `CHES_SEND_TEMPBEYOND` | 過熱通知（接收方向） | 未用過 |
| -109 | *(冇對應常數名，0x93)* | PIR 感應器觸發（"PIR HUMON DETECT"） | ✅ **已喺真機確認會觸發**（同上，經 `CHEST_ACTION` broadcast 嘅 `"value"` extra 讀到，唔經 AIDL rcv callback）。詳見第 7 節 |
| -106 | `CHES_SEND_TRANSFORM` | 姿態轉換通知（接收方向） | 未用過 |
| -105 | `CHES_SEND_FALLDOWN` | 跌倒通知（接收方向） | 未用過 |

**`chest_setPirSensorEnabled()` 送出嘅 payload**：`sendCommand(72, [enabled ? 1 : 0], 1)`——單一 byte，1=開 0=閂。

**`chest_configureSonar()` 送出嘅 payload（cmd=4／`CHEST_CMD_SETTING`）**：`sendCommand(4, [10, distanceCm], 2)`——第一個 byte 係 sub-command（`10`），第二個 byte 係觸發距離（cm）。呢個係令 `SONAR_DISTANCE_ACTION`（見第 7 節）開始有讀數嘅正確 config command。

**聲納讀數唔行 AIDL rcv 呢條路**：直覺上會估計 `chest_configureSonar()` 之後嘅聲納讀數會經 `IAlpha2SerialPortService.onListenSerialPortRcvData()`（transaction #0 註冊嗰個 callback）以 `-127`／`CHES_SEND_OBSTACLE` 做 cmd byte 送返嚟——**呢個假設實測完全冇撞中**：`onListenSerialPortRcvData()` 淨係收到 `chest_configureSonar()` 呢個 config command 自己嗰個 2-byte ack（`"04 00"`），從未見過 `-127` 開頭嘅幀。真正嘅聲納讀數係經完全獨立、唔屬於呢個 AIDL interface 嘅 broadcast `SONAR_DISTANCE_ACTION` 送出，見第 7 節。

**頭部 MCU（head，經 `Alpha2Intent.ALPHA_SERIAL_HEADER_SERVER` bind）**：

| Byte（十進位） | 常數名 | 用途 | 狀態 |
|---:|---|---|---|
| 1 | `LED_EAR` | 傳統耳朵 LED | ✅ 已用——`header_startEarLED()`，但**喺 5mic 頭板（呢部機）已知冧唔到 5mic MCU**，要改用 `ledSetEye5Mic`／`ledSetHead5Mic`（見第 3.1 節） |
| 2 | `LED_EYE` | 傳統眼睛 LED | ✅ 已用——`header_startEyeLED()`，同上，5mic 頭板要改用 5-mic 專用方法 |
| 3 | `LED_MOUTH` | 咀部 LED | ⚠️ **呢個常數存在，但呢個 App 嘅 Mouth LED 功能（`MouthLedData`）完全冇用呢條路**——實際上係直接 JNI call `com.ubtechinc.mic5.LedControl`（`libhead_led.so`），完全唔經 `sendCommand`／AIDL，見下面「Mouth LED 唔屬於 AIDL」段落 |
| 4 | `INFRARED_SETTING` | 紅外線設定 | 未用過 |
| 5 | `STARTUP_CMD` | 開機指令 | 未用過 |
| 6 | `HEAD_PAUSE_CMD` | 暫停 | 未用過 |
| 7 | `HEAD_CONTINUE_CMD` | 繼續 | 未用過 |
| 8 | `STOP_CMD` | 停止 LED | ✅ 已用——`header_stopEarLED()`／`header_stopEyeLED()`，payload 單一 byte（1=耳朵／0=眼睛，區分停邊個） |
| 21 | `HEAD_ACTION` | 頭部動作 | 未用過 |
| 22 | `HEAD_SHUTDOWN` | 頭部關機 | 未用過 |
| 25 | `SOUND_CONTROL`／`HEADER_SEND_AMP` | 聲音控制／音量放大 | 未用過 |
| 32 | `HEAD_BLUETOOTH_OPEN` | 開藍牙 | 未用過 |
| 39 | `HEAD_CONTROL_BYPASS`／`HEADER_SEND_NOISE` | 頭部降噪開關 | ✅ 已用——`header_setNoise(isOpen)`，payload 單一 byte（`isOpen ? 0 : 1`，注意呢個 mapping 同直覺相反：`0`=開降噪、`1`=閂） |
| 48 | `HEAD_CMD_START_UPDATE` | 開始韌體更新 | 未用過（危險） |
| 49 | `HEAD_CMD_UPDATE_PAGE` | 更新分頁 | 未用過（危險） |
| 50 | `HEAD_CMD_UPDATE_END` | 更新結束 | 未用過（危險） |
| 51 | `HEADER_READ_VERSION` | 讀取版本 | 未用過 |
| 33 | `HEADER_SYSTEM_REBOOT` | 頭部重開機 | 未用過（呢個 App 嘅「重開機」功能行 `PowerManager` 令成部機重開，唔係經呢個 cmd） |
| -128 | `HEADER_SEND_OBSTACLE` | 障礙物（接收方向） | 未用過 |
| -127 | `HEADER_SEND_KEY` | 按鍵（接收方向） | 未用過 |
| -126 | `HEADER_SOUND_DIRECTION` | 聲源方向（接收方向） | 未用過 |
| -125 | `HEADER_FALL_DERECTION` | 跌倒方向（接收方向） | 未用過（拼字 `DERECTION` 係原廠錯字，唔好自己改） |
| -124 | `HEADER_HIGHT_TEMP` | 過熱（接收方向） | 未用過（拼字 `HIGHT` 係原廠錯字） |
| -106 | `HEADER_SEND_TRANSFORM` | 姿態轉換（接收方向） | 未用過 |

**Mouth LED 唔屬於 AIDL**：呢個 App 嘅咀部 LED 功能（`MouthLedData` class）雖然睇落好似同 `LED_MOUTH`（cmd byte 3）有關，但**實際上完全唔行 AIDL／`sendCommand` 呢條路**——佢直接 JNI call `com.ubtechinc.mic5.LedControl.ledSetMouth(int,int,int,int,int)`（原生方法，喺 `libhead_led.so` 入面實作），呢個 class 嚟自另一個獨立嘅官方 demo APK（`alpha2demo`），唔係 `com.ubtechinc.alpha2serverlib.aidlinterface` package 底下嘅任何 interface。實測發現全部 5 個參數入面，得返 2 個有確認效果（`breatheSpeedMs`、`offDurationMs`），其餘意義未明。詳細參數語意見 `MouthLedData.java` 嘅 class javadoc。

---

## 6. `Alpha2RobotApi` 高層 wrapper 行為備忘

`Alpha2RobotApi` 係包住成 17 個 AIDL interface 嘅單一 facade，真正用呢個 SDK 嗰陣通常唔會直接掂 AIDL Stub，而係用呢層 wrapper。以下係幾個對正確使用呢層 wrapper 好關鍵、但純睇方法簽名睇唔出嘅行為。

**Error code 語意**（`UbxErrorCode.API_ERROR_CODE`）：

| 值 | 意義 |
|---|---|
| `API_ERROR_SUCCEED` | Call 已被接受／轉發俾機械人（唔代表機械人實際做咗嘢——`sendCommand` 呢類 fire-and-forget call 淨係代表送出成功） |
| `API_ERROR_NOT_INIT` | 對應嘅 service 未初始化（未 call 過對應嘅 `init*Api`）或者 binder 未連接好 |
| `API_ERROR_APPID_NOT_ACTIVE` / `API_ERROR_AUTHORIZE_ERROR` | 舊有 store 授權失敗嘅遺留 code——呢個 open SDK 唔再做授權門檻，正常運作下唔會見到 |
| `API_ERROR_FAILED` | Binder 連接好、call 都送到咗，但底層 AIDL 方法本身回 `false`（例如 5mic LED 嗰組方法，佢哋唔似 `sendCommand` 咁 fire-and-forget，會真正回報 native driver 嘅執行結果） |

**Bind 係 async，唔可以以為 constructor 一 return 就即刻可以用**：`init*Api()` 只係 call `bindService()`，`ServiceConnection.onServiceConnected()` 幾時觸發完全睇 Android 排程，唔保證 constructor return 嗰一刻就已經連接好。`isChestAvailable()`／`isHeaderAvailable()`／`isBlueToothSerialAvailable()` 呢類方法**淨係check個 `*ServiceUtil` object 有冇被建構過，唔係check 個 bind 真係完成咗**——喺 bind 完成之前送出嘅 command 會用一個未設定／預設值嘅 session id，而且 `sendCommand()` 嘅 boolean 結果喺唔少高層 wrapper 入面（例如 `header_start*`/`chest_Send*` 呢類）**冇被檢查**（淨係 catch `RemoteException`），所以太早 call 可能會喺機身側靜靜哋冇反應，但 app 呢層仍然會見到 `API_ERROR_SUCCEED`。要避免呢個 race，喺 `initChestSerialApi()`／`initHeaderSerialApi()`／`initBlueToothSerialApi()` 之後、送第一個 servo／LED 指令之前，應該喺背景 thread 先 call `waitChestReady(timeoutMs)`／`waitHeaderReady(timeoutMs)`／`waitBlueToothSerialReady(timeoutMs)`（呢幾個 wait 方法喺主 thread 上 call 會即刻 return，唔會 block；`timeoutMs` 而家已經真正生效，唔會再被無視）。

**TTS 同 ASR 引擎綁定已經分咗開，唔好將兩者混為一談**：`Alpha2RobotApi` 內部有兩個獨立嘅 `Alpha2SpeechMainServiceUtil` 欄位——`mSpeechServiceUtil`（一開機用通用 `ALPHA_SPEECH_MAIN_SERVER` 別名綁定一次，TTS 永遠淨係用呢個，唔會再 release/rebind）同 `mAsrServiceUtil`（`speech_switchEngine()` 專用，可以直接綁去 `ALPHA_NUANCE_SPEECH_MAIN_SERVER`／`ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`）。呢個分野嘅原因、同 direct-engine binding 已知會令 TTS session 壞死嘅真機證據，見第 1.1 節「引擎選擇」段落，唔喺呢度重複。`startSpeechNoWakeup`／`setRecognizedLanguage`／grammar 三類 ASR call 會經內部 `currentAsrTarget()` 自動揀用緊邊個 binding，call 方唔使自己判斷。

**動作清單嘅 blocking wait 有 5 秒上限**：`Alpha2RobotApi.action_getActionList()` 本身係 async（結果經 `IAlphaActionListListener` callback 送返），但呢個 App 嘅 HTTP `action/list` endpoint 用咗一個 `CountDownLatch` 將佢包成 blocking、最多等 5 秒。如果機械人服務初始化好慢，第一次攞列表可能會 timeout 返空列表——可以再攞一次。

**藍牙／XMPP 冇對應嘅高層驗證方法**：`bluetooth_sendCommand()` 嘅 `nCmd` 值未經任何真機驗證（唔似 chest/header serial 咁有對應嘅 `chest_configureSonar()`／`header_setNoise()` 呢類已知安全嘅固定值封裝），暴露做一個薄薄嘅 passthrough，用嘅人要自己試。`Alpha2XmppServiceUtil` 係 process-wide singleton（`getInstance()`），`sendCustomXmppMessage` 嘅 `type` 參數用途未反查，保留純粹做 API 完整性，喺呢部機嘅韌體未經驗證。

**`getServerVersion()` 係寫死字串，唔係真正查詢**：`Alpha2RobotApi.getServerVersion()` 底層 call `AlphaMainServiceUtil.getVersion()`，而呢個方法**直接回一個寫死喺 code 入面嘅常數字串 `"2.0.0.1"`**，唔會 bind service 去問機械人實際版本，唔可以當佢係機身即時狀態嘅可靠來源。

---

## 7. 相關但唔係 AIDL 嘅普通 Broadcast Intent

以下呢啲**唔係 Binder call**，係普通 Android `Context.sendBroadcast()`／`registerReceiver()`——即係話冧咗都唔會影響任何 AIDL binder 連接，亦都唔受 transaction id 順序規則管，但因為同上面幾個 AIDL 方法（尤其係 `chest_configureSonar()`／`chest_setPirSensorEnabled()`）關係密切、容易混淆，喺度一併記錄，等人唔會誤將呢啲當做 AIDL 嘅一部分。

| Broadcast action | Extra | 意義 | 狀態 |
|---|---|---|---|
| `com.ubtechinc.services.chest`（`StaticValue.CHEST_ACTION`）| `"value"`（`byte[]`）| 胸口 MCU raw command byte 嘅全域轉發，**心口 mute 鍵（`-111`）同 PIR 觸發（`-109`）兩個都經呢條路送出**——唔係經第 3.1 節嗰個 `IAlpha2SerialPortService.onListenSerialPortRcvData()` AIDL rcv callback | ✅ `-111`（心口 mute 鍵）同 `-109`（PIR）已喺真機確認會觸發，用「掃描成個 `byte[]` 陣列搵目標值」嘅做法讀取，唔靠固定 index（因為 SDK 有冇拆走 wire frame header 未 100% 確認）。**PIR 觸發嘅穩定性有保留**：官方 `AlphaMainSeviceImpl` 自己嘅 log 顯示佢持續、密集咁收到 `-109`，但呢個 App 自己個 `RobotEventReceiver` 實測有一次成日淨係收到過一次（仲要係 `-115` 唔係 `-109`）——即係話呢個 broadcast 本身可能有 gate／rate-limit 令唔係次次都轉發俾第三方 app，唔係一個保證穩定嘅事件源 |
| `com.ubtechinc.sonar.distance`（`StaticValue.SONAR_DISTANCE_ACTION`）| `"sonar_distance"`（`int`，`StaticValue.SONAR_DISTANCE_EXTRA`）| 聲納實際距離讀數（cm，未 100% 確認單位，但 config command 嘅 threshold byte 睇落單位一致）| ✅ 呢部機確認係聲納讀數嘅真正來源，要先 call `chest_configureSonar()`（cmd=4, sub-cmd=10）先會開始收到 |
| `com.ubtech.securityCamera.pirStatus` | `"pirStatus"`（`byte`，1=有人進入 0=無人離開）| PIR 通知（同 `CHEST_ACTION` 嗰條唔同嘅獨立 action，經官方 `SecurityCameraUtil.isMonitoringEnabled()` gate 先會轉發）| ⚠️ **未經真機驗證，呢部機上呢個 gate 一直未 fire 過**——反編譯確認 `SecurityCameraUtil` 呢個 class 喺呢部機嘅韌體版本根本唔存在，理論上呢個 broadcast 唔會被送出。保留純粹做向後相容，等將來換咗支援嘅韌體版本時兩條路都餵去同一個前端事件 |
| `com.ubtechinc.key` | `"key"`（`Byte`）| 頭部按鍵 | ⚠️ 反編譯 `alpha2services_base` 3.0.0.2 全個 APK 搵唔到任何 `sendBroadcast("com.ubtechinc.key")` 出處——喺呢個韌體版本實際上係死 code，永遠唔會觸發，保留純粹向後相容 |
| `come.ubt.alpha2.gesture` | `"getstureDirection"`（拼字係原廠錯字）| 手勢方向 | ⚠️ 文件講呢個係 `String`，但**實測喺真機上係 `Integer`**，用 `getStringExtra()` 會拋 `ClassCastException` 靜靜哋整個事件——已改用唔會拋錯嘅 `Bundle.get()` 讀 |
| `com.ubtechinc.services.Action.ACTION_STOP` | 冇 extra | 機身側動作播放被外部打斷停止（全域，唔限於自己 call 緊嗰個 session）| 反編譯確認 `AlphaUtils.sendActionStopIntent()` 發出 |
| `com.ubtechinc.services.Action.ROBOT_INTERRUPTED` | 冇 extra | 機械人整體被打斷（通常同 TTS／動作一齊停）| 反編譯確認 `AlphaUtils.sendInterruptIntent()` 發出 |
| `com.ubtechinc.services.ABOUT_TTS`、`ALPHA_SOCKET_ASR_OK`、`SPEECH_ANGLE_5MIC`、`LED_ACTION`、`IFLY_OFFLINE_CMD`、`NUANCE_OFFLINE_CMD`、`POWER_SAVE`、`ALPHA_NOTIFY_POWER` | 未定 | 由 `SpeechServiceImpl`／`SpeechManager`／`AlphaMainSeviceImpl` 發出，睇個名同 TTS／ASR／mic／電源狀態有關 | ⚠️ 純粹反編譯 bytecode 睇唔到「幾時會實際觸發」，只知 action 字串同 `putExtra()` key 名／型別。全部先 register 埋、統一經 `mic_broadcast_debug` 事件轉送去 log，等收集到實際觸發嘅 payload 之後先決定邊幾個要拆做獨立事件 |

**`LED_ACTION`（`com.ubtechinc.services.LED_ACTION`）呢個 broadcast 值得特別留意**：`setWakeState(true)`（第 1.1 節 transaction #7）觸發機身攞返 mic 嗰一刻，`alpha2services` 內部會自己發呢個 broadcast 去停耳朵 LED，做為側面效應。如果自己個 app 啱啱好喺呢一刻都想設 LED 狀態，會同呢個內部 broadcast 相撞（見第 3.1 節「持續運作嘅假熄燈循環」段落嘅類似問題）。解法係將自己嘅 LED 指令延後到 `releaseMicForAudioIo()`（同佢嗰 300ms 等待）完成之後先送，或者好似頭部 5mic LED 咁做持續補發。

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

## 反編譯方法

呢份文件全部數據都嚟自用 `androguard` 直接讀取 `alpha2services` v1.1.7.3.20 呢隻 APK 嘅 `ISomeInterface$Stub.onTransact()` bytecode，抽取個 `sparse-switch` 入面實際 call 緊邊個方法、幾多個參數、咩型別——呢個係 transaction id 嘅 ground truth，比起靠 class/method 名或者 `TRANSACTION_*` 常數個名嚟推斷更可靠（後者順序未必反映真實 declaration order）。
