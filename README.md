# lynx.open.sdk

一個 Android library module（`com.open.lynx`），封裝咗同 UBTech Alpha2 機械人上面
`com.ubtechinc.alpha2services` 呢個 system app 溝通所需嘅全部 AIDL interface 同
一層薄薄嘅 Java wrapper (`Alpha2RobotApi` 呢個 façade + 6 個 `*ServiceUtil` class)。

呢份 README 淨係講呢個 SDK module 本身，唔包含任何示範 app、UI，或者其他組件。

## 呢個 SDK 係點確認返嚟

呢 17 個 AIDL interface 嘅每一個 method（transaction id、參數類型、方向、返回值）
都係直接反編譯 `com.ubtechinc.alpha2services` 呢個 APK，逐個攞返 `Xxx$Stub.onTransact()`
嘅 sparse-switch disassembly 同 `Xxx$Stub$Proxy` 嘅 marshalling code 對比核實，
唔係憑記憶或者估估吓寫。核實方法：

- Binder 嘅 transaction id 由 method 喺 `.aidl` 入面嘅**宣告順序**決定（第一個
  method 係 id 1，如此類推），所以 `onTransact()` 個 switch 有幾多個 case、每個
  case 讀寫 `Parcel` 嘅順序，就係最可靠嘅 wire-format 證據——呢個對唔上，輕則
  RemoteException，重則靜靜雞讀壞另一個 method 嘅參數。
- 每個 interface 都逐個攞晒 `Stub.onTransact()` 嘅完整 disassembly，同（如果有
  client 端 Proxy 嘅話）`Proxy` 入面每個 method 點樣 `writeInterfaceToken` /
  `writeXxx` / `transact(id, ...)` / `readException` / `readXxx`，兩邊對照過先落實
  最終簽名。
- 有啲 method 嘅原始名喺呢個 build 已經俾 proguard 縮到得返單一字母
  (`a`/`b`/`c`...)，冇得還原返原名；呢啲情況會喺對應嘅 `.aidl` 檔案頭部用註解
  講明「簽名已確認，名係推斷」，唔會當成同其他已知名字一樣嘅確定事實。

## Module 結構

```
lynx.open.sdk/                     (rootProject.name)
├── app/                            (test panel app, applicationId: com.open.lynx)
│   ├── build.gradle
│   ├── debug.keystore
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/open/lynx/MainActivity.java
└── lynx-open-sdk/                 (Android library module, namespace: com.open.lynx)
    ├── build.gradle
    ├── consumer-rules.pro
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── aidl/com/ubtechinc/alpha2serverlib/aidlinterface/   ← 17 個 .aidl（見下）
        └── java/
            ├── com/ubtechinc/alpha2robot/
            │   ├── Alpha2RobotApi.java        主 façade，包裝晒下面 6 個 util
            │   └── constant/                  UbxErrorCode, AlphaConstant
            ├── com/ubtechinc/alpha2serverlib/
            │   ├── util/                      6 個 *ServiceUtil：實際 bind AIDL service 嘅地方
            │   ├── interfaces/                SDK 對外嘅 callback interface（非 AIDL，係俾用家implement）
            │   ├── authority/                 Alpha2Authority
            │   └── constvalue/                Alpha2Intent
            ├── com/ubtechinc/constant/         ActionType, LanguageType, CustomLanguage 等常數
            └── com/ubtechinc/developer/        Developer 模式相關嘅資料 class
```

**注意**：AIDL 檔案同入面全部 interface 嘅 package 保持 `com.ubtechinc.alpha2serverlib.aidlinterface`
唔變——呢個 package name 本身就係同機械人 `alpha2services` 溝通嘅 wire-format
(`enforceInterface`/`writeInterfaceToken` 用嘅 descriptor string)，改咗個 SDK 就
連唔到真機械人。`com.open.lynx` 套用喺 `app` module 嘅 `applicationId` 同
`lynx-open-sdk` module 嘅 `namespace`（即係 module 自己新寫嗰啲 Java class 嘅
R-class/資源命名空間），唔涉及 AIDL。

## 測試面板 App（`app` module）

一個純代碼、冇 XML layout 嘅單 Activity，每個 `Alpha2RobotApi` public method 對應
一個掣，掣下面有個共用嘅 scrolling log 顯示每次呼叫嘅結果同 callback。冇 HTTP／
WebSocket server，冇 camera，冇錄音——純粹用嚟喺機械人／模擬器螢幕上面逐個掣
試哂個 SDK 嘅方法。

裝落機械人（`adb install`）之後開 app，會即場：
1. 用 `ClientAuthorizeListener` 建構 `Alpha2RobotApi`（開放版本一定 authorize 成功）
2. 分頁按鈕分別覆蓋：init（action/chest/header/speech）、Action、Chest/Head
   free-angle motor、LED、Speech（TTS/ASR/grammar/text understand）、Custom
   message (XMPP)、Misc（`requestRobotUUID`、`isChestAvailable`、`isHeaderAvailable`）

## AIDL Interface 一覽（17 個）

每個 interface 下面列晒：作用、Binder service 綁定用嘅 Action（如適用）、以及

**method 名 + 完整簽名，順序就係 transaction id 順序**（好緊要，唔可以打亂）。

### 服務端 interface（機械人提供、SDK 呼叫）

#### 1. `IAlpha2BlueToothSerialPortService`
藍牙序列埠通訊 service。呢個 build 冇 client 端 Proxy（Stub-only，冇任何組件
喺呢個 APK 入面 cross-process 呼叫佢）。
```java
int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
void sendATCMD(String cmd);
```

#### 2. `IAlpha2SerialPortService`
機械人胸口/頭部嘅序列埠通訊 service（`AlphaSerialPortServices` /
`AlphaSerialPortHeaderServices`）。
```java
int registerSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
int unRegisterSerialPortRcvListener(IAlpha2SerialPortRcvClient cb);
boolean sendCommand(byte nSessionID, byte nCmd, in byte[] nParam, int nLen);
boolean sendRawData(in byte[] data, int nLen);
boolean sendCommandString(String cmd, int nLen);   // 簽名已確認，名係推斷（見上）
```

#### 3. `IAlphaActionService`
機械人動作播放 service（`AlphaActionServices`）。Action：
`com.ubtechinc.services.AlphaActionServices`。
```java
int registerActionClient(IAlphaActionClient client);
void unRegisterActionClient(IAlphaActionClient client);
boolean playActionFile(String strActionFile);
boolean playActionName(String strActionName);
void stopActionPlay();
void onEventHandlerTrigger(int nEventType, in byte[] param);
boolean isCompleted();
void getActionList(IAlphaActionListListener listener);
void disableActionPlay(boolean disable);
```

#### 4. `IAlpha2XmppListener`
機械人 XMPP 訊息 service。呢個 interface 嘅 method 名喺呢個 build 冇被
proguard（直接喺 smali 見到真名，唔係推斷）。
```java
int registerXmppCallBackListener(String appID, IAlpha2XmppCallBack callBack);
int unRegisterXmppCallBackListener(IAlpha2XmppCallBack callBack);
void sendCustomXmppMessage(int type, String appID, String message);
```

#### 5. `ISpeechInterface`
機械人語音 service（`SpeechServices`）：TTS 播放、語音辨識、文法辨識、語意理解，
係 17 個入面 method 最多嘅一個（20個）。
```java
int registerSpeechCallBackListener(ISpeechCallBackListener callBack);
int unRegisterSpeechCallBackListener(ISpeechCallBackListener callBack);
void onSpeech(ISpeechCallBackListener listener, String text);
void onStopSpeech(ISpeechCallBackListener listener);
void onPlay(ISpeechCallBackListener listener, String text, String strVoiceName, String language);
void onPlayHigh(ISpeechCallBackListener listener, String text, String strVoiceName, String language);
void onStopPlay(ISpeechCallBackListener listener);
void setWakeState(boolean onWake);
void onTextUnderstand(String strText, IAlphaTextUnderstandListener listener);
void initSpeechGrammar(String strGrammar, ISpeechGrammarInitListener listener);
void startSpeechGrammar(ISpeechGrammarListener listern);
void stopSpeechGrammar();
void stopSpeechAndEnterIdleMode();
void setRecognizedLanguage(String strLanguage);
void setVoiceName(String strVoiceName);
void onEnglishUnderstand(IAlphaEnglishUnderstandListener listener);
void setEnglishOfflineListener(IAlphaEnglishOfflineUnderstandListener listener);
void setSelfInterrupt(boolean isInterrupt);
void setStartEarLed();
void startSpeechNoWakeup(ISpeechCallBackListener listener);
```
`onPlay`/`onPlayHigh` 淨係 4 個參數（冇額外嘅 `int priority`）——呢個係經常俾人加錯
嘅一點，加咗嗰個多餘 int 會令 Parcel 讀寫位移，累到之後所有欄位都讀錯。

#### 6. `IAppMonitor`
接收另一個 component 傳過嚟嘅 `IBinder`（`MainService` 用）。冇 client 端
Proxy（Stub-only）。方法名係推斷（原名已被 proguard 縮寫）。
```java
void onAppBinderReceived(IBinder binder);
```

### Callback interface（SDK 實作、機械人反向呼叫）

#### 7. `IAlpha2SerialPortRcvClient`
```java
void onListenSerialPortRcvData(in byte[] bytes, int len);
```

#### 8. `IAlpha2SpeechClientListener`（舊版語音 client 路徑，Stub-only）
```java
void onServerCallBack(String text);
void onServerPlayEnd(boolean isEnd);
```

#### 9. `IAlpha2XmppCallBack`
```java
void onReceiveMessage(String message);
```

#### 10. `IAlphaActionClient`
```java
void onActionStop(String strActionFileName);
```

#### 11. `IAlphaActionListListener`
```java
void onGetActionList(String list);   // "##" 分隔、每 4 個一組 [id, type, cn-name, en-name]
```

#### 12. `IAlphaEnglishOfflineUnderstandListener`
```java
void onAlpha2EnglishOfflineUnderstandResult(String strResult);
```

#### 13. `IAlphaEnglishUnderstandListener`
```java
void onAlpha2EnglishUnderstandResult(String strResult);
```

#### 14. `IAlphaTextUnderstandListener`
```java
void onAlpha2UnderStandError(int nErrorCode);
void onAlpha2UnderStandTextResult(String strResult);
```

#### 15. `ISpeechCallBackListener`
```java
void onCallBack(int type, String text);
void onPlayEnd(boolean isEnd);
```

#### 16. `ISpeechGrammarInitListener`
```java
void speechGrammarInitCallback(String grammarID, int nErrorCode);
```

#### 17. `ISpeechGrammarListener`
```java
void onSpeechGrammarResult(String strResultType, String strResult);   // 順序：結果在前
void onSpeechGrammarError(int nErrorCode);                             // 錯誤在後
```
（留意呢個順序同 `IAlphaTextUnderstandListener` 相反——嗰個係錯誤先、結果後。
兩個都係逐個對照返 `onTransact` 個 switch 出嚟嘅真實順序，冇對調錯。）

## 未用到嘅 Parcelable

`ActionInfoList` 同 `AlphaActionList`（兩者都只係包住一個 `List` 嘅簡單
`Parcelable`）喺呢個 APK 版本入面**冇任何一個 AIDL method 用到**，亦都冇任何
Java class 引用佢哋。佢哋喺反編譯出嚟嘅 dex 入面確實存在（唔係捏造），但屬於
死碼／留俾未來擴充，唔係現行 wire contract 嘅一部分，SDK 冇為佢哋提供對應
`.aidl` 定義。

## Java Wrapper 層

`Alpha2RobotApi` 係主要對外 façade，內部靠以下 6 個 `*ServiceUtil` 分別
`bindService()` 對應嘅 AIDL service，並且喺 `ServiceConnection` callback 入面
`Xxx.Stub.asInterface(binder)`：

| Util class | 綁定嘅 AIDL | 對應機械人 Service |
|---|---|---|
| `AlphaActionServiceUtil` | `IAlphaActionService` | `com.ubtechinc.services.AlphaActionServices` |
| `Alpha2SerialServiceUtil` | `IAlpha2SerialPortService` | 胸口序列埠 service |
| `Alpha2SerialHeaderServiceUtil` | `IAlpha2SerialPortService` | 頭部序列埠 service |
| `Alpha2SpeechMainServiceUtil` | `ISpeechInterface` | `com.ubtechinc.services.SpeechServices` |
| `Alpha2XmppServiceUtil` | `IAlpha2XmppListener` | XMPP service |
| `AlphaMainServiceUtil` | （內部整合多個 util，`initSpeechApi` 等入口點） | — |

呢層 wrapper 全部都跟返上面嘅 AIDL 簽名一致（包括之前發現、已經改正嘅
`onPlay`/`onPlayHigh` 參數個數、5-mic LED 呢類喺呢個 build 根本唔存在嘅
method 已經全部移除，唔會再有編譯錯誤或者 runtime 呼叫失敗）。

## Build

```bash
# 淨係 build SDK 本身 (.aar)
./gradlew :lynx-open-sdk:assembleRelease

# build 埋測試面板 apk（連埋 SDK 一齊）
./gradlew :app:assembleDebug
```

呢個 project 已經包埋標準嘅 Gradle wrapper（`gradlew`、`gradlew.bat`、
`gradle/wrapper/gradle-wrapper.{jar,properties}`），對應 **Gradle 7.0**（同
AGP 4.2.2 相容），唔使自己另外裝 Gradle。

`lynx-open-sdk` module 輸出係一個 `.aar`；`app` module 輸出係一個可以直接
`adb install` 落機械人嘅 `.apk`（`applicationId com.open.lynx`，已經用committed
嘅 `debug.keystore` 簽咗名，唔靠 AGP 自動生成嗰個 `~/.android/debug.keystore`）。

兩個 module 都係 `compileSdkVersion 25`、`minSdkVersion 19`（Alpha2 機械人
最舊韌體係 Android 4.4）、`sourceCompatibility`/`targetCompatibility` 都係 Java 8。

呢個 module 淨係依賴 Android framework 本身，冇任何第三方 library。

## 適用範圍

呢份 SDK 對應嘅係 `com.ubtechinc.alpha2services` 呢一份特定 APK 抽出嚟嘅 AIDL
wire contract。唔同韌體版本（例如 3.02/base3）嘅 AIDL 介面可以完全唔同，用之前
請先確認機械人韌體版本，同呢個 SDK 對應嘅 APK 版本一致。
