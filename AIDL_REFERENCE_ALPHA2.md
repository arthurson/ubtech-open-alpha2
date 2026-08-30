# Alpha2Services AIDL 參考手冊

`com.ubtechinc.alpha2serverlib.aidlinterface` 這個 package 底下的 **17 個 AIDL interface**（+1 個自訂 Parcelable：`ASRRecord`）的完整用法，專門對應機身實際安裝的 **Alpha2Services v1.1.7.3.20（20170918171435-5mic）**。全部方法順序和簽名已經對照這個版本反編譯出來的 `Stub.onTransact()` 驗證過。當中 `disableActionPlay`、`isActioning`、整個 `IAlpha2BlueToothSerialPortService`、`ISpeechInterface` 裡面的 `setStartEarLed`／`startSpeechNoWakeup`／`disableTTSPause`／`startLocalFunction`／`registerReplayContentListener` 這幾個是 v1.1.7.3.20 專屬方法（下文個別標註 ⭐）。

> **AIDL 基本規則**：方法在 `.aidl` 裡面的**宣告順序**決定了 Binder transaction id（由 0 開始逐個編號）。順序錯了、少了、或者參數個數錯了，call 不會立刻報錯，而是會**靜靜地讀錯位**（讀多了/少了 bytes），讓之後所有欄位都讀錯——這是最危險的 bug 類型，所以千萬不要自己憑感覺改順序或者刪方法。

---

## 目錄

按用途分成 7 組：

1. [語音（Speech）](#1-語音-speech) —— `ISpeechInterface` 和它的 6 個 callback/data interface
2. [動作（Action）](#2-動作-action) —— `IAlphaActionService` 和它的 2 個 callback interface
3. [序列埠 / LED / 藍牙](#3-序列埠--led--藍牙) —— `IAlpha2SerialPortService`、`IAlpha2BlueToothSerialPortService`、`IAlpha2SerialPortRcvClient`
4. [自訂訊息（XMPP）](#4-自訂訊息-xmpp) —— `IAlpha2XmppListener`、`IAlpha2XmppCallBack`
5. [`sendCommand` 序列埠 Command Byte 完整表](#5-sendcommand-序列埠-command-byte-完整表) —— 胸口／頭部 MCU 用的底層 command byte，`sendCommand()` 這個 AIDL method 之下再一層的 wire protocol
6. [`Alpha2RobotApi` 高層 wrapper 行為備忘](#6-alpha2robotapi-高層-wrapper-行為備忘) —— error code 語意、bind 時序、engine 切換的已知後果等，call 這份 SDK 之前要知道的實戰細節
7. [相關但不是 AIDL 的普通 Broadcast Intent](#7-相關但不是-aidl-的普通-broadcast-intent) —— PIR／sonar／mute 鍵等事件實際上是經 `Context.sendBroadcast()`／`registerReceiver()`，不是 Binder call，看這裡才不會和 AIDL 混淆


---

## 1. 語音（Speech）

### 1.1 `ISpeechInterface` —— 主介面

**Bind action**（`Intent` + `setPackage("com.ubtechinc.alpha2services")`）：

| Action 常數 | 字串值 | 用途 |
|---|---|---|
| `Alpha2Intent.ALPHA_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.SpeechServices` | 通用別名，由機身韌體決定實際綁去邊個引擎 |
| `Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.NuanceSpeeckServices` | 直接綁 Nuance 引擎（英文） |
| `Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER` | `com.ubtechinc.services.IflytekSpeeckServices` | 直接綁 iFlytek（訊飛）引擎（中文） |

SDK 裡面 `Alpha2SpeechMainServiceUtil` 已經包裝了這個 bind 流程，一般不用自己叫 `bindService`。

**完整方法清單**（依 `.aidl` 宣告順序 = transaction id 順序）：

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSpeechCallBackListener` | `ISpeechCallBackListener callBack` | 註冊接收 ASR 結果／TTS 播放完畢通知的 listener。回傳 session id（`int`） |
| 1 | `unRegisterSpeechCallBackListener` | `ISpeechCallBackListener callBack` | 取消註冊 |
| 2 | `onSpeech` | `listener, String text` | ⚠️ **2026-08 logcat 覆核推翻舊結論**：之前以為「文字直接注入到語意理解層，不經真正麥克風／ASR」，但實測（`logcat_2026-08-01_13-19-46.txt`，經 `Alpha2RobotApi.speech_startRecognized()` 呼叫）顯示 `SpeechServiceImpl.onSpeech()` 在這台機的實際行為是**直接將傳入的 `text` 用 TTS 讀出來**——logcat 見到 call 完立刻 `SpeechManager` 印 `ttsIsSpeaking:false language: text = stand up`，接著 `start tts play` → `IflytekTTS` 真的讀出「stand up」這句話，全程沒有任何辨識/語意理解相關的 callback 或 event 觸發。也就是說這個 method 在這個 firmware 版本上根本不是「假裝聽到某句話」的辨識模擬入口，而是一個側門 TTS：**傳入的文字會被讀出來，而不是被拿去辨識**。SDK 標這個 method `@Deprecated` 很可能就是因為這個名不副實的行為。想測試「辨識」相關功能（ASR/NLU/grammar），這個 method 不可靠，應該用 `initSpeechGrammar`/`startSpeechGrammar`（第 9、10 項，要切去 iFlytek binding）或者 `onTextUnderstand`（第 8 項，已知打不通，見下） |
| 3 | `onStopSpeech` | `listener` | 停止上面第 2 項 `onSpeech` 觸發的東西（實測是停止 TTS 讀出，不是停止「模擬輸入辨識」——見第 2 項 2026-08 覆核） |
| 4 | `onPlay` | `listener, String text, String strVoiceName, String language, int priority` | 播 TTS，**低優先級**（不會打斷正在播放的東西）。`priority` **恆用 `0`**——`Alpha2SpeechMainServiceUtil.onPlay()` 內部無論 `isTip` 是 true/false 都寫死傳 `0`，這是機身側 `SpeechServiceImpl` 自己內部 boolean-flag call 傳過來那個預設值，未見過任何 caller 傳其他數值，可以當它是固定安全值，不用自己另外試 |
| 5 | `onPlayHigh` | 同 `onPlay` | 播 TTS，**高優先級**（會打斷正在播放的東西）。`priority` 同上，恆用 `0` |
| 6 | `onStopPlay` | `listener` | 停止 TTS 播放 |
| 7 | `setWakeState` | `boolean onWake` | ⚠️ **不是「開始聆聽」**！這個只是將 mic 擁有權在 app 和 alpha2services 之間轉手：`true` = **app 拿回 mic**（釋放給 app），`false` = **交還給機械人**。內部行為是將 ASR 引擎設為 idle 和 destroy 現有 recognition session，**不會主動觸發辨識**（詳見下面「重要行為」） |
| 8 | `onTextUnderstand` | `String strText, IAlphaTextUnderstandListener listener` | 對一段文字做語意理解（NLU），不經語音 |
| 9 | `initSpeechGrammar` | `String strGrammar, ISpeechGrammarInitListener listener` | 初始化語法式（受限詞彙）辨識規則。**引擎分野**：`NuanceServiceImpl.initSpeechGrammar()`／`startSpeechGrammar()` 兩個都是完全未實作的空 stub（method body 只有一句 `return-void`），Nuance binding 之下這兩個 call **必然靜靜地打不通**；`IflytekServiceImpl` 的同名 method 有真身實作，會真正建立 `com.iflytek.cloud.SpeechRecognizer`。要試這組 API 必須先用 `speech_switchEngine()` 切去 iFlytek binding，否則注定打不通。`strGrammar` 的期望格式（JSON？純文字詞彙表？逗號分隔？）在 `IflytekServiceImpl` 反編譯出來的 code 見到只是被存成一個字串再傳入 `SpeechRecognizer` 初始化流程，確實語法格式未反查到 |
| 10 | `startSpeechGrammar` | `ISpeechGrammarListener listern` | 開始語法式辨識（注意：原廠 `.aidl` 這個參數名就是打錯字 `listern`，不要自己「修正」它，會撞到 codegen）。同上，Nuance binding 底下是空 stub。SDK 側 `Alpha2SpeechMainServiceUtil.startSpeechGrammar()` 實際傳 `null` 做 AIDL listener，結果改由 grammar 結果轉發經 `registerSpeechCallBackListener` 那條 `onCallBack` callback 送回（見 1.2 `onCallBack` 的 `type` 用途說明），不是直接用這個方法傳入的 listener |
| 11 | `stopSpeechGrammar` | — | 停止語法式辨識 |
| 12 | `stopSpeechAndEnterIdleMode` | — | 停止所有語音活動，入 idle |
| 13 | `setRecognizedLanguage` | `String strLanguage` | 設定辨識語言，`"zh_cn"` / `"en_us"`。⚠️ **這是 advisory-only**：在這台機的 firmware 上，不會真正切換 active engine（見下面「引擎選擇」段落）——`zh_cn`/`en_us` 只是傳給 active engine 的語言提示，不保證 active engine 本身是不是 iFlytek/Nuance |
| 14 | `setVoiceName` | `String strVoiceName` | 設定 TTS 聲音（只有 iFlytek 命名聲音才有效） |
| 15 | `onEnglishUnderstand` | `IAlphaEnglishUnderstandListener listener` | 註冊英文語意理解 listener |
| 16 | `setEnglishOfflineListener` | `IAlphaEnglishOfflineUnderstandListener listener` | 註冊離線英文語意理解 listener |
| 17 | `setSelfInterrupt` | `boolean isInterrupt` | 開關「自我打斷」（機械人說話中途被人講話打斷，中文限定） |
| 18 | `setStartEarLed` | — | ⭐ **1.1.7.3.20 專屬方法**——觸發耳朵 LED 的 wake 效果（無參數） |
| 19 | **`startSpeechNoWakeup`** | `ISpeechCallBackListener listener` | ⭐ **1.1.7.3.20 專屬方法**。⚠️ **名不副實**：實測（`logcat_2026-07-02_13-38-32.txt`）顯示這個只是將 speech engine 撥入內部 `SPEECH_STATE_WAKEUP` 狀態（`SpeechManager "what:3"`, `IflytekWakeUp5mic.startRecording`），**真正的辨識**（`IflyteckASR5mic "startSpeechASR type:0"`, `"Listening..."`）**還要等硬體 `MicArray wakeup` 獨立觸發，實測相隔 ~20 秒**。結果經傳入的 `listener`（一般就傳回 `registerSpeechCallBackListener` 那個）的 `onCallBack` 送回來。這個測試是在 Nuance binding 底下做的，iFlytek binding 底下行為未同步驗證 |
| 20 | `disableTTSPause` | `boolean disable` | ⭐ **1.1.7.3.20 專屬方法**——控制 TTS 播放中途暫停行為 |
| 21 | `startLocalFunction` | `String strFunction` | ⭐ **1.1.7.3.20 專屬方法**——觸發機身本地功能（具體接受什麼字串未反查） |
| 22 | `registerReplayContentListener` | `IReplaySpeechCallback listener` | ⭐ **1.1.7.3.20 專屬方法**——註冊接收「回放」語音記錄（`ASRRecord`），見 [1.7](#17-ireplayspeechcallback--asrrecord) |

**重要行為**：

`speech_SetMIC(true)`（對應 `setWakeState(true)`）**不會觸發辨識**——它只是轉 mic 擁有權，不會讓機械人自己開始聆聽。用 `startSpeechNoWakeup` 可以更早將 engine 撥入 wake-word-receptive 狀態，但都不是「立刻聽到」（見上面 #19）。

`setWakeState` 的 `onWake` 參數方向（實測 logcat 驗證，非憑參數名推斷）：app 自己開 `AudioRecord`/`AudioTrack` 之前一定要先 call `setWakeState(true)`，不然就會撞到 `status -38`（`AudioPolicyManager: "startInput failed: other input already started"`）和 `AudioTrack` `STATE_UNINITIALIZED`——也就是說 `alpha2services` 那時還握著 mic/audio hardware，`setWakeState(true)` 才是將硬體**釋放給 app** 的 call；`false` 才是交還給機械人。

**引擎選擇（Nuance／iFlytek）**：

這台機透過通用別名 `ALPHA_SPEECH_MAIN_SERVER`（由機身韌體自己決定路由）綁定的時候，預設走到 Nuance。`Alpha2SpeechMainServiceUtil` 提供一個 5 參數 constructor，可以直接傳 `Alpha2Intent.ALPHA_NUANCE_SPEECH_MAIN_SERVER` 或 `Alpha2Intent.ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`，強制綁到指定引擎，繞過通用別名的自動路由。`Alpha2RobotApi` 對應暴露 `initSpeechApi(..., String speechServiceAction)` 這個 overload、和 `speech_switchEngine(...)`（runtime 重新綁定），App 側有 `speech/set_asr_engine` API 和 UI「切換引擎」鍵，可以直接綁 iFlytek 觸發中文辨識。

**⚠️ Direct-engine binding 會讓 TTS session 壞死**：`speech_switchEngine()` 直接綁 `ALPHA_NUANCE_SPEECH_MAIN_SERVER`／`ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`，**一 rebind 就會讓機身系統進程（`com.ubtechinc.alpha2services`）裡面的 TTS session 啞掉**——`onPlay`/`onPlayHigh` 仍然回 `API_ERROR_SUCCEED`（Binder call 本身沒拋 `RemoteException`），但完全看不到後續的 `SpeechServiceImpl`/`IflytekTTS`/`onTTsStart` log，也就是說這個 call 打到一個已經死掉的 session, 一直要重開機才能回到正常。

因為這個原因，`Alpha2RobotApi` 將 TTS 和 ASR 分開兩條**獨立**的 binding：TTS 永遠只用 `mSpeechServiceUtil`（一開機用通用 `ALPHA_SPEECH_MAIN_SERVER` 綁一次，永遠不再 release/rebind）；`speech_switchEngine()` 用另一個獨立欄位 `mAsrServiceUtil` 做 direct-engine binding，`startSpeechNoWakeup`/`setRecognizedLanguage`/grammar 三類 ASR 專屬 call 會跟著 `currentAsrTarget()`（未切換過用 `mSpeechServiceUtil`，切換了就用 `mAsrServiceUtil`）自動選。**引擎切換之後，TTS 不會啞**（因為 TTS binding 一早已經和 ASR binding 分開）；`mAsrServiceUtil` 這條 binding 本身如果撞到同一種病灶，只會影響到 ASR 這邊的 call，不會連累到 TTS。

`speech_resetToIdle()`（call AIDL transaction #12 `stopSpeechAndEnterIdleMode`，用回沒壞過的 `mSpeechServiceUtil` binding 去 call）是試驗性的重置入口，想看看叫不叫得回那個死掉的 session、不用整台機重開機——**這個方法本身未在真機驗證過是否真的解決得到問題**，如果沒效就還要重開機。

**典型用法（觸發一次手動辨識）**：

```java
// 1. 揀語言／引擎
robot.speech_setRecognizedLanguage("zh_cn");   // iFlytek，或 "en_us" 用 Nuance

// 2. 直接開始聆聽（不用等 wake word）
robot.speech_startSpeechNoWakeup();

// 3. 結果經 IAlpha2SpeechClientListener.onServerCallBack(String text) 送回來
//    （這個已經在 Alpha2RobotApi.initSpeechApi() 那時 wire 好）
```

---

### 1.2 `ISpeechCallBackListener` —— ASR／TTS 結果 callback

由 app 實作，傳給 `registerSpeechCallBackListener` / `startSpeechNoWakeup`。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onCallBack` | `int type, String text` | 辨識結果／中間狀態回報，`text` 是辨識出來的文字。`type` 的用途：`Alpha2SpeechMainServiceUtil.SpeechCallBackListenerImpl.onCallBack()` 裡面，如果目前有一個 grammar listener 已註冊（也就是剛剛 call 過 `startSpeechGrammar`），這個 callback 會直接當**語法辨識結果**處理，將 `type`／`text` 轉發成 `onSpeechGrammarResult(strResultType, strResult)` 的兩個對應參數（`type`→`strResultType`、`text`→`strResult`）；沒有 grammar listener 時才當普通 ASR 結果，`text` 轉發去 `onServerCallBack`。也就是說 `onCallBack` 這一個 callback 同時擔任「普通 ASR 結果」和「grammar 辨識結果」兩種用途，由呼叫端有沒有註冊著 grammar listener 決定怎麼解讀，不是由 `type` 數值本身決定 |
| `onPlayEnd` | `boolean isEnd` | TTS 播放完畢通知 |

---

### 1.3 `IAlpha2SpeechClientListener` —— app 對外暴露的 speech listener

SDK 裡面 `Alpha2RobotApi.initSpeechApi()` 接受這個 listener，將 `ISpeechCallBackListener.onCallBack` 轉發過來。

| 方法 | 參數 | 用途 |
|---|---|---|
| `onServerCallBack` | `String text` | ASR 辨識結果文字（最終落到你手上那個） |
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

配合 `ISpeechInterface.registerReplayContentListener()` 用——⭐ 1.1.7.3.20 專屬（`.aidl` + `ASRRecord.java`）。用途看名字應該是接收機械人內部記錄下的語音辨識歷史記錄（「回放」）。

**`IReplaySpeechCallback`**：

| 方法 | 參數 | 用途 |
|---|---|---|
| `onRelpayContent` | `in ASRRecord record`（注意方法名本身是原廠打錯字 `Relpay`，不是 `Replay`，不要自己改） | 送一筆歷史語音記錄過來 |

**`ASRRecord`**（自訂 Parcelable，7 個欄位，順序和型別已由 `writeToParcel`/`readFromParcel` bytecode 驗證）：

| 欄位（getter/setter） | 型別 | 語意來源 |
|---|---|---|
| `getExtra1()` / `setExtra1()` | `String` | ⚠️ 沒找到語意來源，字面順序上是第一個欄位 |
| `getRecordId()` / `setRecordId()` | `String` | 對應機身內部 `ReplaySpeechRcord.getRecordId()` |
| `getMsgLanguage()` / `setMsgLanguage()` | `String` | 對應 `ReplaySpeechRcord.getMsgLanguage()` |
| `getContent()` / `setContent()` | `String` | 對應 `ReplaySpeechRcord.getContent()`——這個應該就是辨識出來的文字內容 |
| `getContentLinks()` / `setContentLinks()` | `String` | 對應 `ReplaySpeechRcord.getContentLinks()` |
| `getLabelId()` / `setLabelId()` | `int` | 對應 `ReplaySpeechRcord.getLabelId()` |
| `getExtra2()` / `setExtra2()` | `String` | ⚠️ 沒找到語意來源，字面順序上是最後一個欄位 |

> `extra1`／`extra2` 這兩個名是中性佔位命名，不是原廠語意。Wire format（順序／型別）保證和機身一致，但這兩個欄位實際裝著什麼要留意實測。

---

## 2. 動作（Action）

### 2.1 `IAlphaActionService` —— 主介面

**Bind action**：`Alpha2Intent.ALPHA_ACTION_SERVER` = `com.ubtechinc.services.AlphaActionServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerActionClient` | `IAlphaActionClient client` | 註冊接收「動作播放完畢」通知的 listener，回傳 session id |
| 1 | `unRegisterActionClient` | `client` | 取消註冊 |
| 2 | `playActionFile` | `String strActionFile` | 播放一個動作檔案（`.act` 之類，看檔案路徑） |
| 3 | `playActionName` | `String strActionName` | 用名稱播放一個內建動作 |
| 4 | `stopActionPlay` | — | 停止當前動作播放 |
| 5 | `onEventHandlerTrigger` | `int nEventType, in byte[] param` | 觸發某個事件處理（具體事件類型未反查） |
| 6 | `isCompleted` | — | 查詢動作是否播放完畢 |
| 7 | `getActionList` | `IAlphaActionListListener listener` | 取回機身內建動作清單（透過 callback 送回） |
| 8 | `disableActionPlay` | `boolean disable` | ⭐ **1.1.7.3.20 專屬方法**——開關動作播放功能 |
| 9 | `isActioning` | — | ⭐ **1.1.7.3.20 專屬方法**——查詢機械人是否正在做動作 |

---

### 2.2 `IAlphaActionClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onActionStop` | `String strActionFileName` | 通知某個動作檔案播放完畢／停止 |

### 2.3 `IAlphaActionListListener`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onGetActionList` | `String list` | 動作清單（單一編碼字串——格式見下） |

**Wire format**：整個 `list` 用 `"##"` 分隔成一串扁平的欄位，**每 4 個欄位一組**，對應一個動作：

```
id##type##中文名##英文名##id##type##中文名##英文名## ...
```

也就是 `fields.length % 4 == 0`，第 `i` 個動作的四個欄位在 `fields[4*i]`（id）、`fields[4*i+1]`（type）、`fields[4*i+2]`（中文名）、`fields[4*i+3]`（英文名）。這個 parser 已經在 `AlphaActionServiceUtil.ActionListListenerImpl.onGetActionList()` 實作了（`.split("##")` + 逐 4 個一組讀），`app_client` 側的 `IAlpha2ActionListListener.onGetActionList(ArrayList<ArrayList<String>>)` 拿到的已經是拆好的結果，不用自己再 parse 一次。如果 `fields.length % 4 != 0`（也就是收到的字串損壞或者格式不符預期），這個 parser 會直接回傳一個空 list，不會拋例外，但也不會硬解讀部分資料。

---

## 3. 序列埠 / LED / 藍牙

### 3.1 `IAlpha2SerialPortService` —— 胸口／頭部序列埠（含 5-mic LED）

**Bind action**：
- 胸口：`Alpha2Intent.ALPHA_SERIAL_SERVER` = `com.ubtechinc.services.AlphaSerialPortServices`
- 頭部：`Alpha2Intent.ALPHA_SERIAL_HEADER_SERVER` = `com.ubtechinc.services.AlphaSerialPortHeaderServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSerialPortRcvListener` | `IAlpha2SerialPortRcvClient cb` | 註冊接收序列埠回傳資料的 listener |
| 1 | `unRegisterSerialPortRcvListener` | `cb` | 取消註冊 |
| 2 | `sendCommand` | `byte nSessionID, byte nCmd, in byte[] nParam, int nLen` | 送一個封裝好的序列埠命令幀。這個方法之下的 cmd byte 完整表見 [第 5 節](#5-sendcommand-序列埠-command-byte-完整表) |
| 3 | `sendRawData` | `in byte[] data, int nLen` | 送原始 byte data（不經 `sendCommand` 的封裝） |
| 4 | `stop5MicEyeLED` | — | 熄眼部 5-mic LED |
| 5 | `stop5MicEarLED` | — | 熄耳部 5-mic LED |
| 6 | `ledSetEye5Mic` | `int p1..p8` | 設定眼部 5-mic LED（8 個 int 參數，語意見下面「5-mic LED 參數表」） |
| 7 | `ledSetHead5Mic` | `int p1..p8` | 設定頭部 5-mic LED（同上） |
| 8 | `getRobotSerialNumber` | — | 取機身序號 |

**5-mic LED 存在原因**：`Alpha2SerialHeaderServiceUtil` 的 comment 確認，在 5mic 頭板（1.1.7.3 這一代）上，`sendCommand` 傳統的 `LED_EAR`／`LED_EYE` command byte（見第 5 節）雖然可以送到頭部序列埠（不會拋 `RemoteException`，`bindReady` 都是 `true`），但**頭部 5mic MCU 已經不再回應這些舊 command**——只有 `ledSetEye5Mic`／`ledSetHead5Mic` 這兩個獨立 AIDL 方法才有效。這兩個方法**不經 `sendCommand` 的序列幀封裝**，直接到機身側 `com.ubtechinc.mic5.LedControl` 原生驅動。

**5-mic LED 參數表**（`p1`..`p8`，在這台機真機測試確認）：

| 位置 | 意義 | 已知數值 |
|---|---|---|
| `p1` | 顏色 (colorType) | 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白，其他數值無效 |
| `p2` | 亮度 (brightness) | 1=最暗 .. 9=最光，其他數值無效 |
| `p3`／`p4` | 右／左 LED 選擇器 | 這個 app 沒有獨立分開兩邊控制，一律一起送同一個值 |
| `p5` | upTime (ms) | 配合所選模式塑造這個循環時序 |
| `p6` | downTime (ms) | 同上 |
| `p7` | runTime (ms) | 效果維持多久；`Integer.MAX_VALUE` = 長開（一直維持） |
| `p8` | 模式 (mode) | **頭／耳和眼睛用的數值範圍不一樣**：頭／耳 0=閃(flash) 1=呼吸燈(breathing) 3=跑馬燈(chase) 5=雙色燈(dual-colour)；眼 0=閃(flash) 1=跑馬燈(chase) 3=雙色燈(dual-colour)（沒有呼吸燈模式） |

已確認的 `(p5,p6)` preset 組合：長開 `p5=MAX,p6=0`；跑馬燈 `p5=100,p6=0`；閃 `p5=100,p6=100`；呼吸燈（只限頭／耳）`p5=5,p6=20`；雙色燈 `p5=500,p6=0`。

**⚠️ 兩個獨立、不矛盾的真機結果，看清楚哪個是哪個**：

1. `led/head/set`／`led/eye/set`（主控制面板 UI 用那組固定 preset）——**在這台機已經真機確認生效**，用的正正就是上面這張參數表。
2. `applyObstacleIndicator()`（sonar／PIR 觸發的指示燈，另一個獨立 code path，一樣是 call `header_ledSetHead5Mic`／`header_ledSetEye5Mic`，但用的不是 UI 那組 preset 參數）——**在這台機實測全部 preset 都回 `API_ERROR_FAILED`**（`bindReady:true`，也就是不是還沒 ready，是機身這一刻真的不接受）。

兩者用的是**同一組 AIDL 方法**，在**同一台機**上，結果卻不一樣——目前未查到確實原因（可能和觸發時機、參數組合細節、或者當刻機身內部狀態有關），但已經排除了「這台機頭板不支援 5-mic LED」這個猜測（因為第 1 點已經證實生效）。`applyObstacleIndicator()` 失敗時不會拋例外中斷流程，同時會 fallback 去閃 Mouth LED（見下面 Mouth LED 獨立段落）。

另外要留意：alpha2services 內部 wake-word／mic 邏輯本身有一個**持續運作的「假熄燈」循環**——`AlphaMainSeviceImpl` 每隔約 0.8~2 秒左右就會自己 call 一次 `header_ledSetHead5Mic(color=3,brightness=2,...,p5=400,p6=9000,p8=2)` 這組固定參數來做「熄燈」效果（本質是設一個很暗的圖案，不是真正斷電）。這個內部循環會和任何自己想長期維持的頭／眼 LED 狀態相撞，肉眼看到就是「亮一下又熄」。這個 app 的做法是開一條低延遲（80ms 間隔）的背景 thread 持續補發自己想要的顏色，蓋過這個內部循環的效果，不是徹底停止它。

### 3.2 `IAlpha2SerialPortRcvClient`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onListenSerialPortRcvData` | `in byte[] bytes, int len` | 收到序列埠回傳原始資料 |

### 3.3 `IAlpha2BlueToothSerialPortService` ⭐ 1.1.7.3.20 專屬 interface

**Bind action**：`Alpha2Intent.ALPHA_BLUETOOTHSERIAL_SERVER` = `com.ubtechinc.services.AlphaBlueToothSerialPortServices`

和 `IAlpha2SerialPortService` 結構很相似，但是走藍牙而不是機身內部序列埠，**沒有** 5-mic LED 那部分：

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerSerialPortRcvListener` | `IAlpha2SerialPortRcvClient cb` | 註冊 listener（和 3.1 共用同一個 `IAlpha2SerialPortRcvClient`） |
| 1 | `unRegisterSerialPortRcvListener` | `cb` | 取消註冊 |
| 2 | `sendCommand` | `byte nSessionID, byte nCmd, in byte[] nParam, int nLen` | 送封裝好的藍牙序列命令幀 |
| 3 | `sendATCMD` | `String cmd` | 送 AT 指令（藍牙模組常見控制方式） |

---

## 4. 自訂訊息（XMPP）

### 4.1 `IAlpha2XmppListener` —— 主介面

**Bind action**：`Alpha2Intent.ALPHA_XMPP_SERVER` = `com.ubtechinc.services.Alpha2XmppServices`

| # | 方法 | 參數 | 用途 |
|---|---|---|---|
| 0 | `registerXmppCallBackListener` | `String appID, IAlpha2XmppCallBack callBack` | 用 app 識別碼註冊接收訊息的 listener，回傳 session id |
| 1 | `unRegisterXmppCallBackListener` | `callBack` | 取消註冊 |
| 2 | `sendCustomXmppMessage` | `int type, String appID, String message` | 送一個自訂訊息（`type` 用途未反查，估計係訊息分類） |

### 4.2 `IAlpha2XmppCallBack`

| 方法 | 參數 | 用途 |
|---|---|---|
| `onReceiveMessage` | `String message` | 收到自訂訊息 |

---

## 5. `sendCommand` 序列埠 Command Byte 完整表

`IAlpha2SerialPortService.sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen)`（第 3.1 節 transaction #2）本身只是一個通用的信封——真正做事的是 `nCmd` 這個 byte，決定了胸口／頭部微控制器（MCU）實際執行哪個動作。這張表來自 `com.ubtechinc.constant.StaticValue` 這個常數類（和 SDK 一起派發，並非反編譯估計），是胸口／頭部 MCU 全部已知 command byte 的完整清單。標示「✅ 已用」的是這個 App 已經實際 call 著、有實測結果的；只列在表裡但沒標示的，是 SDK 派發但這個 App 沒用過、未經驗證的常數。

**胸口 MCU（chest，經 `Alpha2Intent.ALPHA_SERIAL_SERVER` bind）**：

| Byte（十進位） | 常數名 | 用途 | 狀態 |
|---:|---|---|---|
| 1 | `CHEST_CMD_START` | 開始 | 未用過 |
| 2 | `CHEST_CMD_STOP` | 停止 | 未用過 |
| 3 | `CHEST_CMD_SENDMOTOR` | 送馬達指令 | 未用過 |
| 4 | `CHEST_CMD_SETTING` | 設定（見下）| ✅ 已用——`chest_configureSonar()` 用來設定聲納，sub-command byte 見下面說明 |
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
| 54 | `CHEST_WRITE_SID_EEPROM` | 寫伺服 ID EEPROM | 未用過（危險，會改伺服硬體 ID） |
| 55 | `CHEST_READ_SID_EEPROM` | 讀伺服 ID EEPROM | 未用過 |
| 56 | `CHEST_SQUAT` | 蹲下 | 未用過 |
| 64 | `CHEST_POWER_SAVE` | 省電模式 | 未用過 |
| 72 | *(沒有對應常數名)* | PIR 感應器開關 | ⚠️ **未在這台機實測撞中過**——`chest_setPirSensorEnabled()` 用著這個 cmd 值，但兩份提供了的 logcat 裡面，胸口收到的 cmd 只見過 `-109`/`-111`/`-115`，從未見過 `72`。送出去之後要看 logcat／`onListenSerialPortRcvData` 的 ack 幀才知道機身有沒有反應，在實測撞中之前當「未證實的假設」看待 |
| -128 | `CHEST_SEND_POWER` | 電量回報（接收方向） | 未用過 |
| -127 | `CHES_SEND_OBSTACLE` | 障礙物／聲納（接收方向）| ⚠️ 見下面「聲納讀數不走這條路」段落——理論上是聲納 raw 幀的 cmd byte，但實測聲納讀數根本不會經這個 AIDL rcv callback 送到，真正生效的路徑是獨立 broadcast `SONAR_DISTANCE_ACTION`（見第 7 節） |
| -126 | `CHES_SEND_ANGLEINFO` | 角度回報（接收方向） | 未用過 |
| -125 | `CHES_SEND_SHUTDWON` | 關機通知（接收方向） | 未用過 |
| -121 | `CHES_SEND_ALARM` | 鬧鐘通知（接收方向） | 未用過 |
| -119 | `CHEST_TOUCH_BOARD` | 觸摸板（接收方向） | 未用過 |
| -118 | `CHES_DC_STATE` | 直流電狀態（接收方向） | 未用過 |
| -115 | *(沒有對應常數名)* | *(未知，實測見過)* | ⚠️ logcat 見過胸口 broadcast 收到這個值，但 `StaticValue` 沒有對應常數名，用途未反查 |
| -111 | *(沒有對應常數名，0x91)* | 心口 mute 鍵按下 | ✅ **已在真機確認會實際觸發**（logcat 見到 `ches cmd = -111`，raw wire frame `f8 8f 08 00 00 91 01 9a ed` / `f8 8f 08 00 00 91 00 99 ed`）。這個不是經 `IAlpha2SerialPortService` 的 AIDL rcv callback 收到，是經 `CHEST_ACTION`（`"com.ubtechinc.services.chest"`）這個普通 broadcast 的 `"value"` extra（`byte[]`）裡面找到，詳見第 7 節 |
| -110 | `CHES_SEND_TEMPBEYOND` | 過熱通知（接收方向） | 未用過 |
| -109 | *(沒有對應常數名，0x93)* | PIR 感應器觸發（"PIR HUMON DETECT"） | ✅ **已在真機確認會觸發**（同上，經 `CHEST_ACTION` broadcast 的 `"value"` extra 讀到，不經 AIDL rcv callback）。詳見第 7 節 |
| -106 | `CHES_SEND_TRANSFORM` | 姿態轉換通知（接收方向） | 未用過 |
| -105 | `CHES_SEND_FALLDOWN` | 跌倒通知（接收方向） | 未用過 |

**`chest_setPirSensorEnabled()` 送出的 payload**：`sendCommand(72, [enabled ? 1 : 0], 1)`——單一 byte，1=開 0=關。

**`chest_configureSonar()` 送出的 payload（cmd=4／`CHEST_CMD_SETTING`）**：`sendCommand(4, [10, distanceCm], 2)`——第一個 byte 是 sub-command（`10`），第二個 byte 是觸發距離（cm）。這是讓 `SONAR_DISTANCE_ACTION`（見第 7 節）開始有讀數的正確 config command。

**聲納讀數不走 AIDL rcv 這條路**：直覺上會估計 `chest_configureSonar()` 之後的聲納讀數會經 `IAlpha2SerialPortService.onListenSerialPortRcvData()`（transaction #0 註冊那個 callback）以 `-127`／`CHES_SEND_OBSTACLE` 做 cmd byte 送回來——**這個假設實測完全沒撞中**：`onListenSerialPortRcvData()` 只有收到 `chest_configureSonar()` 這個 config command 自己那個 2-byte ack（`"04 00"`），從未見過 `-127` 開頭的幀。真正的聲納讀數是經完全獨立、不屬於這個 AIDL interface 的 broadcast `SONAR_DISTANCE_ACTION` 送出，見第 7 節。

**頭部 MCU（head，經 `Alpha2Intent.ALPHA_SERIAL_HEADER_SERVER` bind）**：

| Byte（十進位） | 常數名 | 用途 | 狀態 |
|---:|---|---|---|
| 1 | `LED_EAR` | 傳統耳朵 LED | ✅ 已用——`header_startEarLED()`，但**在 5mic 頭板（這台機）已知打不通 5mic MCU**，要改用 `ledSetEye5Mic`／`ledSetHead5Mic`（見第 3.1 節） |
| 2 | `LED_EYE` | 傳統眼睛 LED | ✅ 已用——`header_startEyeLED()`，同上，5mic 頭板要改用 5-mic 專用方法 |
| 3 | `LED_MOUTH` | 嘴部 LED | ⚠️ **這個常數存在，但這個 App 的 Mouth LED 功能（`MouthLedData`）完全沒有用這條路**——實際上是直接 JNI call `com.ubtechinc.mic5.LedControl`（`libhead_led.so`），完全不經 `sendCommand`／AIDL，見下面「Mouth LED 不屬於 AIDL」段落 |
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
| 33 | `HEADER_SYSTEM_REBOOT` | 頭部重開機 | 未用過（這個 App 的「重開機」功能走 `PowerManager` 讓整台機重開，不是經這個 cmd） |
| -128 | `HEADER_SEND_OBSTACLE` | 障礙物（接收方向） | 未用過 |
| -127 | `HEADER_SEND_KEY` | 按鍵（接收方向） | 未用過 |
| -126 | `HEADER_SOUND_DIRECTION` | 聲源方向（接收方向） | 未用過 |
| -125 | `HEADER_FALL_DERECTION` | 跌倒方向（接收方向） | 未用過（拼字 `DERECTION` 是原廠錯字，不要自己改） |
| -124 | `HEADER_HIGHT_TEMP` | 過熱（接收方向） | 未用過（拼字 `HIGHT` 是原廠錯字） |
| -106 | `HEADER_SEND_TRANSFORM` | 姿態轉換（接收方向） | 未用過 |

**Mouth LED 不屬於 AIDL**：這個 App 的嘴部 LED 功能（`MouthLedData` class）雖然看起來好像和 `LED_MOUTH`（cmd byte 3）有關，但**實際上完全不走 AIDL／`sendCommand` 這條路**——它直接 JNI call `com.ubtechinc.mic5.LedControl.ledSetMouth(int,int,int,int,int)`（原生方法，在 `libhead_led.so` 裡面實作），這個 class 來自另一個獨立的官方 demo APK（`alpha2demo`），不是 `com.ubtechinc.alpha2serverlib.aidlinterface` package 底下的任何 interface。實測發現全部 5 個參數裡面，只有 2 個有確認效果（`breatheSpeedMs`、`offDurationMs`），其餘意義未明。詳細參數語意見 `MouthLedData.java` 的 class javadoc。

---

## 6. `Alpha2RobotApi` 高層 wrapper 行為備忘

`Alpha2RobotApi` 是包住整整 17 個 AIDL interface 的單一 facade，真正用這份 SDK 的時候通常不會直接碰 AIDL Stub，而是用這層 wrapper。以下是幾個對正確使用這層 wrapper 很關鍵、但純看方法簽名看不出的行為。

**Error code 語意**（`UbxErrorCode.API_ERROR_CODE`）：

| 值 | 意義 |
|---|---|
| `API_ERROR_SUCCEED` | Call 已被接受／轉發給機械人（不代表機械人實際做了事——`sendCommand` 這類 fire-and-forget call 只代表送出成功） |
| `API_ERROR_NOT_INIT` | 對應的 service 未初始化（沒 call 過對應的 `init*Api`）或者 binder 未連接好 |
| `API_ERROR_APPID_NOT_ACTIVE` / `API_ERROR_AUTHORIZE_ERROR` | 舊有 store 授權失敗的遺留 code——這個 open SDK 不再做授權門檻，正常運作下不會見到 |
| `API_ERROR_FAILED` | Binder 連接好、call 都送到了，但底層 AIDL 方法本身回 `false`（例如 5mic LED 那組方法，它們不像 `sendCommand` 那樣 fire-and-forget，會真正回報 native driver 的執行結果） |

**Bind 是 async，不可以以為 constructor 一 return 就立刻可以用**：`init*Api()` 只是 call `bindService()`，`ServiceConnection.onServiceConnected()` 什麼時候觸發完全看 Android 排程，不保證 constructor return 那一刻就已經連接好。`isChestAvailable()`／`isHeaderAvailable()`／`isBlueToothSerialAvailable()` 這類方法**只是 check 這個 `*ServiceUtil` object 有沒有被建構過，不是 check 這個 bind 真的完成了**——在 bind 完成之前送出的 command 會用一個未設定／預設值的 session id，而且 `sendCommand()` 的 boolean 結果在不少高層 wrapper 裡面（例如 `header_start*`/`chest_Send*` 這類）**沒被檢查**（只 catch `RemoteException`），所以太早 call 可能會在機身側靜靜地沒反應，但 app 這層仍然會見到 `API_ERROR_SUCCEED`。要避免這個 race，在 `initChestSerialApi()`／`initHeaderSerialApi()`／`initBlueToothSerialApi()` 之後、送第一個 servo／LED 指令之前，應該在背景 thread 先 call `waitChestReady(timeoutMs)`／`waitHeaderReady(timeoutMs)`／`waitBlueToothSerialReady(timeoutMs)`（這幾個 wait 方法在主 thread 上 call 會立刻 return，不會 block；`timeoutMs` 現在已經真正生效，不會再被忽略）。

**TTS 和 ASR 引擎綁定已經分開了，不要將兩者混為一談**：`Alpha2RobotApi` 內部有兩個獨立的 `Alpha2SpeechMainServiceUtil` 欄位——`mSpeechServiceUtil`（一開機用通用 `ALPHA_SPEECH_MAIN_SERVER` 別名綁定一次，TTS 永遠只用這個，不會再 release/rebind）和 `mAsrServiceUtil`（`speech_switchEngine()` 專用，可以直接綁到 `ALPHA_NUANCE_SPEECH_MAIN_SERVER`／`ALPHA_IFLYTEK_SPEECH_MAIN_SERVER`）。這個分野的原因、和 direct-engine binding 已知會讓 TTS session 壞死的真機證據，見第 1.1 節「引擎選擇」段落，不在這裡重複。`startSpeechNoWakeup`／`setRecognizedLanguage`／grammar 三類 ASR call 會經內部 `currentAsrTarget()` 自動選用著哪個 binding，call 方不用自己判斷。

**動作清單的 blocking wait 有 5 秒上限**：`Alpha2RobotApi.action_getActionList()` 本身是 async（結果經 `IAlphaActionListListener` callback 送回），但這個 App 的 HTTP `action/list` endpoint 用了一個 `CountDownLatch` 將它包成 blocking、最多等 5 秒。如果機械人服務初始化很慢，第一次取列表可能會 timeout 回空列表——可以再取一次。

**藍牙／XMPP 沒有對應的高層驗證方法**：`bluetooth_sendCommand()` 的 `nCmd` 值未經任何真機驗證（不像 chest/header serial 那樣有對應的 `chest_configureSonar()`／`header_setNoise()` 這類已知安全的固定值封裝），暴露成一個薄薄的 passthrough，用的人要自己試。`Alpha2XmppServiceUtil` 是 process-wide singleton（`getInstance()`），`sendCustomXmppMessage` 的 `type` 參數用途未反查，保留純粹做 API 完整性，在這台機的韌體未經驗證。

**`getServerVersion()` 是寫死字串，不是真正查詢**：`Alpha2RobotApi.getServerVersion()` 底層 call `AlphaMainServiceUtil.getVersion()`，而這個方法**直接回一個寫死在 code 裡面的常數字串 `"2.0.0.1"`**，不會 bind service 去問機械人實際版本，不可以當它是機身即時狀態的可靠來源。

---

## 7. 相關但不是 AIDL 的普通 Broadcast Intent

以下這些**不是 Binder call**，是普通 Android `Context.sendBroadcast()`／`registerReceiver()`——也就是說打不通都不會影響任何 AIDL binder 連接，也不受 transaction id 順序規則管，但因為和上面幾個 AIDL 方法（尤其是 `chest_configureSonar()`／`chest_setPirSensorEnabled()`）關係密切、容易混淆，在這裡一併記錄，讓人不會誤將這些當做 AIDL 的一部分。

| Broadcast action | Extra | 意義 | 狀態 |
|---|---|---|---|
| `com.ubtechinc.services.chest`（`StaticValue.CHEST_ACTION`）| `"value"`（`byte[]`）| 胸口 MCU raw command byte 的全域轉發，**心口 mute 鍵（`-111`）和 PIR 觸發（`-109`）兩個都經這條路送出**——不是經第 3.1 節那個 `IAlpha2SerialPortService.onListenSerialPortRcvData()` AIDL rcv callback | ✅ `-111`（心口 mute 鍵）和 `-109`（PIR）已在真機確認會觸發，用「掃描整個 `byte[]` 陣列找目標值」的做法讀取，不靠固定 index（因為 SDK 有沒有拆掉 wire frame header 未 100% 確認）。**PIR 觸發的穩定性有保留**：官方 `AlphaMainSeviceImpl` 自己的 log 顯示它持續、密集地收到 `-109`，但這個 App 自己的 `RobotEventReceiver` 實測有一次一直只收到過一次（還是 `-115` 不是 `-109`）——也就是說這個 broadcast 本身可能有 gate／rate-limit 讓不是每次都轉發給第三方 app，不是一個保證穩定的事件源 |
| `com.ubtechinc.sonar.distance`（`StaticValue.SONAR_DISTANCE_ACTION`）| `"sonar_distance"`（`int`，`StaticValue.SONAR_DISTANCE_EXTRA`）| 聲納實際距離讀數（cm，未 100% 確認單位，但 config command 的 threshold byte 看起來單位一致）| ✅ 這台機確認是聲納讀數的真正來源，要先 call `chest_configureSonar()`（cmd=4, sub-cmd=10）才會開始收到 |
| `com.ubtech.securityCamera.pirStatus` | `"pirStatus"`（`byte`，1=有人進入 0=無人離開）| PIR 通知（和 `CHEST_ACTION` 那條不同的獨立 action，經官方 `SecurityCameraUtil.isMonitoringEnabled()` gate 才會轉發）| ⚠️ **未經真機驗證，這台機上這個 gate 一直沒 fire 過**——反編譯確認 `SecurityCameraUtil` 這個 class 在這台機的韌體版本根本不存在，理論上這個 broadcast 不會被送出。保留純粹做向後相容，等將來換了支援的韌體版本時兩條路都餵去同一個前端事件 |
| `com.ubtechinc.key` | `"key"`（`Byte`）| 頭部按鍵 | ⚠️ 反編譯 `alpha2services_base` 3.0.0.2 整個 APK 找不到任何 `sendBroadcast("com.ubtechinc.key")` 出處——在這個韌體版本實際上是死 code，永遠不會觸發，保留純粹向後相容 |
| `come.ubt.alpha2.gesture` | `"getstureDirection"`（拼字是原廠錯字）| 手勢方向 | ⚠️ 文件講這個是 `String`，但**實測在真機上是 `Integer`**，用 `getStringExtra()` 會拋 `ClassCastException` 靜靜地弄壞整個事件——已改用不會拋錯的 `Bundle.get()` 讀 |
| `com.ubtechinc.services.Action.ACTION_STOP` | 沒有 extra | 機身側動作播放被外部打斷停止（全域，不限於自己 call 著那個 session）| 反編譯確認 `AlphaUtils.sendActionStopIntent()` 發出 |
| `com.ubtechinc.services.Action.ROBOT_INTERRUPTED` | 沒有 extra | 機械人整體被打斷（通常和 TTS／動作一起停）| 反編譯確認 `AlphaUtils.sendInterruptIntent()` 發出 |
| `com.ubtechinc.services.ABOUT_TTS`、`ALPHA_SOCKET_ASR_OK`、`SPEECH_ANGLE_5MIC`、`LED_ACTION`、`IFLY_OFFLINE_CMD`、`NUANCE_OFFLINE_CMD`、`POWER_SAVE`、`ALPHA_NOTIFY_POWER` | 未定 | 由 `SpeechServiceImpl`／`SpeechManager`／`AlphaMainSeviceImpl` 發出，看名字和 TTS／ASR／mic／電源狀態有關 | ⚠️ 純粹反編譯 bytecode 看不到「什麼時候會實際觸發」，只知道 action 字串和 `putExtra()` key 名／型別。全部先 register 著、統一經 `mic_broadcast_debug` 事件轉送去 log，等收集到實際觸發的 payload 之後才決定哪幾個要拆成獨立事件 |

**`LED_ACTION`（`com.ubtechinc.services.LED_ACTION`）這個 broadcast 值得特別留意**：`setWakeState(true)`（第 1.1 節 transaction #7）觸發機身拿回 mic 那一刻，`alpha2services` 內部會自己發這個 broadcast 去停耳朵 LED，作為側面效應。如果自己的 app 剛好在這一刻也想設 LED 狀態，會和這個內部 broadcast 相撞（見第 3.1 節「持續運作的假熄燈循環」段落的類似問題）。解法是將自己的 LED 指令延後到 `releaseMicForAudioIo()`（和它那 300ms 等待）完成之後才送，或者像頭部 5mic LED 那樣做持續補發。

---

## 附錄：全部 Bind Action 一覽（`Alpha2Intent.java`）

這些字串（包括打錯字的 `"Speeck"`）是機械人實際註冊的 action name，是 wire contract 的一部分，不可以「修正」錯字：

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

`ALPHA_SOCKET_SERVER`（`Alpha2SocketServices`）和 `ALPHA_MAIN_SERVER`（`MainService`）這兩個 action 有註冊常數，但它們對應的 AIDL interface **未在這次 17 個之內**，也就是說這兩個 service 綁了之後用哪個 interface 溝通還沒反查。如果後續要用這兩個 service，要再對照 APK 找回它們各自的 `Stub` class。

---

## 反編譯方法

這份文件全部數據都來自用 `androguard` 直接讀取 `alpha2services` v1.1.7.3.20 這隻 APK 的 `ISomeInterface$Stub.onTransact()` bytecode，抽取那個 `sparse-switch` 裡面實際 call 著哪個方法、幾個參數、什麼型別——這是 transaction id 的 ground truth，比起靠 class/method 名或者 `TRANSACTION_*` 常數的名字來推斷更可靠（後者順序未必反映真實 declaration order）。
