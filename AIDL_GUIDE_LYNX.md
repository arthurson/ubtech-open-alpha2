# AIDL 使用指南 — Open Lynx (`com.open.lynx`)

呢份文件詳細講解 `com.ubtechinc.alpha.serverlibutil.aidl` 底下全部 **21 個 AIDL
interface** 同 **6 個 Parcelable**，包括每個 method 嘅參數、回調時機、transaction
id，同埋點樣透過 `Alpha2RobotApi` facade（建議做法）或者直接用 AIDL Stub
（進階做法）去調用。

> 所有資料都係對照 `com.ubtechinc.alpha2services_base.3.002.apk` 反編譯結果確認，
> 詳細方法論見 `README.md` 嘅「反編譯方法論」一節。

---

## 目錄

1. [通用概念](#通用概念)
2. [連線設置：`ServiceFetcher`](#連線設置serviceFetcher)
3. [Action（動作播放）](#1-action動作播放--action)
4. [Motor（伺服馬達）](#2-motor伺服馬達--motor)
5. [LED（燈光控制）](#3-ledled--led)
6. [Speech（語音/TTS/ASR）](#4-speech語音ttsasr--speech)
7. [Sys（系統資訊）](#5-sys系統資訊--sysinfo)
8. [Parcelable 資料結構](#parcelable-資料結構)
9. [完整範例](#完整範例)

---

## 通用概念

### 兩種調用方式

**方式一：`Alpha2RobotApi` facade（建議）**

`sdk-module` 提供咗一個 `Alpha2RobotApi` class，將全部 65 個 AIDL method 包裝成
易用嘅 Java method，自動處理 binder 連線、重試、例外捕獲。Method 命名規則係
`<子系統>_<AIDL method 名>`，例如 `IMotorInterface.moveToAbsoluteAngle()` 對應
`Alpha2RobotApi.motor_moveToAbsoluteAngle()`。

```java
Alpha2RobotApi robot = new Alpha2RobotApi(context);
robot.motor_moveToAbsoluteAngle(1, 90, 1000L, listener);
```

**方式二：直接用 AIDL Stub（進階，唔建議日常用）**

如果你需要完全控制 binder 生命週期，或者要繞過 facade 做啲特殊處理，可以直接攞
返個 binder 再用 `IXxx.Stub.asInterface()`：

```java
IBinder binder = ServiceFetcher.get(context).getServiceBinder(ServiceFetcher.SERVICE_MOTOR);
IMotorInterface motor = IMotorInterface.Stub.asInterface(binder);
motor.moveToAbsoluteAngle(1, 90, 1000L, listener);
```

呢份文件之後嘅每個 method 都會列出**兩種寫法**。

### 錯誤處理慣例

`Alpha2RobotApi` 嘅每個 method 都會捕獲 `RemoteException`，用返
`UbxErrorCode.API_ERROR_CODE` 呢個 enum 表達結果：

| 值 | 意思 |
|---|---|
| `API_ERROR_SUCCEED` | Call 已經成功送去機械人。**呢個唔代表操作本身成功**——實際結果永遠透過你傳入嘅 listener 非同步送返嚟。 |
| `API_ERROR_NOT_INIT` | 對應嘅 AIDL service binder 未攞到（機械人 system app 未 ready，或者 `IServiceFetcher` 未 resolve 到）。下次再 call 會自動重試攞 binder。 |
| `API_ERROR_FAILED` | Binder 已經連接，但 AIDL call 本身拋咗 `RemoteException`（例如機械人 service process 中途死咗）。 |
| `API_ERROR_APPID_NOT_ACTIVE` / `API_ERROR_AUTHORIZE_ERROR` | 保留番做源碼相容性，呢個開源 SDK 唔會實際返呢兩個值。 |

冇 `UbxErrorCode` 返回值嘅 method（即係直接返 `String`/`int`/`boolean`/物件嘅
getter，例如 `sys_getSid()`）會喺 binder 未連接或者拋例外時返 `null`
（objects/String）或者 `null`（`Integer`/`Boolean` wrapper types，方便同「攞到
false/0」分辨出嚟）。

### 非同步本質

**絕大部分 method 都係 fire-and-forget（即刻 return，唔會 block）**。你傳入嘅
listener（`IXxxResultListener`/`IXxxListener`）會喺機械人處理完之後，透過 Binder
callback 送返結果——呢個 callback 通常喺**另一條 Binder thread**執行，如果你
要更新 UI，記得包多層 `runOnUiThread()`（如果係 Activity 入面）。

---

## 連線設置：`ServiceFetcher`

機械人唔係用傳統嘅 `bindService()`/`ServiceConnection` 模式，而係用一個
`ContentProvider`（`content://alpha2.service.BinderProvider`）交出一個
`IServiceFetcher` binder broker，再透過佢嘅 `getService(String)` 逐個攞返每個子
系統嘅 binder。`ServiceFetcher.java` 已經幫你封裝好呢個流程：

```java
public final class ServiceFetcher {
    public static final String SERVICE_ACTION  = "action";
    public static final String SERVICE_MOTOR   = "motor";
    public static final String SERVICE_LED     = "led";
    public static final String SERVICE_SYSINFO = "sysinfo";
    public static final String SERVICE_SPEECH  = "speech";

    public static ServiceFetcher get(Context context);
    public IBinder getServiceBinder(String name);
    public boolean isConnected();
}
```

一般你唔需要直接用呢個 class（`Alpha2RobotApi` 內部已經幫你叫咗），淨係喺你想
直接用 AIDL Stub（方式二）先需要。

---

## 1. Action（動作播放）— `"action"`

對應 AIDL：`IActionService`。控制機械人播放預錄嘅動作（跳舞、揮手等）。

### `getActionList` — 攞返全部可用動作

```aidl
void getActionList(IActionListResultListener p0);
```

- **參數**：`p0` — 結果回調 listener。
- **回調**：`IActionListResultListener.onGetActionList(int code, int total, ActionInfo[] actions)`
  - `code` — 結果碼（`0` = 成功，其餘視乎機械人韌體定義）
  - `total` — 動作總數
  - `actions` — `ActionInfo[]`，每個包含 `id`/`cn_name`（中文名）/`en_name`（英文名）/`desc`/`time`（時長，毫秒）/`type`

**Facade 寫法：**
```java
robot.action_getActionList(new IActionListResultListener.Stub() {
    @Override
    public void onGetActionList(int code, int total, ActionInfo[] actions) {
        for (ActionInfo a : actions) {
            Log.i(TAG, a.getId() + " / " + a.getName() + " (" + a.getDuration() + "ms)");
        }
    }
});
```

**直接 Stub 寫法：**
```java
IActionService action = IActionService.Stub.asInterface(
        ServiceFetcher.get(context).getServiceBinder(ServiceFetcher.SERVICE_ACTION));
action.getActionList(new IActionListResultListener.Stub() { ... });
```

### `playAction` — 播放一個動作（按名稱）

```aidl
void playAction(String p0, IActionResultListener p1);
```

- `p0` — 動作名稱（用 `ActionInfo.getName()`/`getId()` 攞返嚟嗰個字串）
- `p1` — 結果回調

**回調**：`IActionResultListener` 有兩個 method：
- `onPlayActionResult(int code, int progress)` — 播放進度/結果更新，可能會多次觸發
- `onStopActionResult(int code)` — 動作被停止時觸發（同一個 listener 兩個 method 都可能收到）

```java
robot.action_playAction("wave", new IActionResultListener.Stub() {
    @Override
    public void onPlayActionResult(int code, int progress) {
        Log.i(TAG, "playing, progress=" + progress);
    }
    @Override
    public void onStopActionResult(int code) {
        Log.i(TAG, "stopped, code=" + code);
    }
});
```

### `playActionFile` — 播放一個動作檔案（按路徑）

```aidl
void playActionFile(String p0, IActionResultListener p1);
```

同 `playAction` 用法一樣，但 `p0` 係機械人上面嘅動作檔案路徑，唔係已註冊嘅動作
名稱。

### `stopAction` — 停止目前正在播放嘅動作

```aidl
void stopAction(IActionResultListener p0);
```

```java
robot.action_stopAction(new IActionResultListener.Stub() {
    @Override
    public void onPlayActionResult(int code, int progress) { }
    @Override
    public void onStopActionResult(int code) {
        Log.i(TAG, "stop confirmed, code=" + code);
    }
});
```

---

## 2. Motor（伺服馬達）— `"motor"`

對應 AIDL：`IMotorInterface`。控制機械人身上每一顆伺服馬達嘅角度。

### `getMotorList` — 攞返全部馬達嘅規格

```aidl
void getMotorList(IMotorListResultListener p0);
```

**回調**：`onGetMotorList(int code, int total, MotorInfo[] motors)`
- `MotorInfo` 包含：`id`（馬達編號）、`upperLimitAngle`/`lowerLimitAngle`（角度上下限）、
  `rotatingSpeed`（轉速）、`torque`（扭力）

```java
robot.motor_getMotorList(new IMotorListResultListener.Stub() {
    @Override
    public void onGetMotorList(int code, int total, MotorInfo[] motors) {
        for (MotorInfo m : motors) {
            Log.i(TAG, "motor " + m.getId() + " range=[" + m.getLowerLimitAngle()
                    + "," + m.getUpperLimitAngle() + "]");
        }
    }
});
```

### `moveToAbsoluteAngle` — 移到指定嘅絕對角度

```aidl
void moveToAbsoluteAngle(int p0, int p1, long p2, IMotorMoveAngleResultListener p3);
```

- `p0` — 馬達 id
- `p1` — 目標角度（絕對值，唔係相對而家嘅偏移）
- `p2` — 移動時間（毫秒），機械人會用呢個時間做勻速插值
- `p3` — 完成回調

**回調**：`IMotorMoveAngleResultListener.onMoveAngle(int motorId, int finalAngle, int code)`

```java
robot.motor_moveToAbsoluteAngle(1, 90, 1000L, new IMotorMoveAngleResultListener.Stub() {
    @Override
    public void onMoveAngle(int motorId, int finalAngle, int code) {
        Log.i(TAG, "motor " + motorId + " reached " + finalAngle);
    }
});
```

### `moveRefAngle` — 移到相對而家角度嘅偏移

```aidl
void moveRefAngle(int p0, int p1, long p2, IMotorMoveAngleResultListener p3);
```

- `p0` — 馬達 id
- `p1` — 角度**偏移量**（可以係負數，代表向反方向郁）
- `p2` — 移動時間（毫秒）
- `p3` — 完成回調（同 `moveToAbsoluteAngle` 用同一個 listener 介面）

### `readAbsoluteAngle` — 讀返目前角度

```aidl
void readAbsoluteAngle(int p0, boolean p1, IMotorReadAngleListener p2);
```

- `p0` — 馬達 id
- `p1` — 係咪由硬件即時讀取（`true`）定係用返上次快取值（`false`）
- `p2` — 回調

**回調**：`onReadMotorAngle(int motorId, int angle, int code)`

```java
robot.motor_readAbsoluteAngle(1, true, new IMotorReadAngleListener.Stub() {
    @Override
    public void onReadMotorAngle(int motorId, int angle, int code) {
        Log.i(TAG, "motor " + motorId + " currently at " + angle);
    }
});
```

### `SetAllMotorAbsoluteAngle` — 一次過移動多顆馬達

```aidl
void SetAllMotorAbsoluteAngle(in MotorAngle[] p0, long p1, IMotorSetAllAngleResultListener p2);
```

- `p0` — `MotorAngle[]`，每個元素係一組 `(motorId, targetAngle)`
- `p1` — 統一移動時間（毫秒），全部馬達會喺呢個時間內同步完成
- `p2` — 完成回調

**回調**：`onSetAllAngle(int code, int total)`

```java
MotorAngle[] angles = {
    new MotorAngle(1, 90),
    new MotorAngle(2, 45),
    new MotorAngle(19, 0),   // 頭部 pan
};
robot.motor_setAllMotorAbsoluteAngle(angles, 1000L, new IMotorSetAllAngleResultListener.Stub() {
    @Override
    public void onSetAllAngle(int code, int total) {
        Log.i(TAG, "all " + total + " motors moved, code=" + code);
    }
});
```

> **注意**：呢個 method 個名開頭大楷（`SetAllMotorAbsoluteAngle`），唔係
> Java 慣例嘅細楷開頭——呢個係機械人 AIDL 原本就係咁定義，直接用 Stub 嗰種寫法
> 要留意大小寫；用 facade（`motor_setAllMotorAbsoluteAngle`）就冇呢個問題。

### `setPowerSaveMode` — 開關省電模式

```aidl
void setPowerSaveMode(boolean p0);
```

冇 listener，冇回調，純粹 fire-and-forget。

```java
robot.motor_setPowerSaveMode(true);  // 開啟省電模式（馬達可能會鎖力較弱）
```

---

## 3. LED（燈光）— `"led"`

對應 AIDL：`ILedInterface`。控制眼睛、頭部、嘴巴、wifi 指示燈、胸口燈嘅開關同
特效。所有操作 method 都用同一個回調介面 `IRemoteLedOperationResultListener`：

```java
IRemoteLedOperationResultListener listener = new IRemoteLedOperationResultListener.Stub() {
    @Override
    public void onLedOpResult(int code, int extra) {
        Log.i(TAG, "led op result: code=" + code + " extra=" + extra);
    }
};
```

顏色/特效參數用嘅係 `int` code，對應 `com.ubtechinc.alpha.sdk.led` package 底下
嘅 enum（詳見[附錄：顏色/特效編碼表](#附錄顏色特效編碼表)）：

- `LedColor`：`RED=1, GREEN=2, BLUE=3, YELLOW=4, MAGENTA=5, CYAN=6, WHITE=7, BLACK=8`
- `LedEffect`：`LIGHT, BLINK, FLASH, BREATH, MARQUEE`（呢個 enum 冇公開數值，`ILedInterface` 嘅參數用嘅係位置性嘅整數，並非直接嗰個 enum ordinal，實際數值由機械人韌體定義）

### `getLedList` — 攞返每組 LED 支援嘅顏色/特效

```aidl
void getLedList(IRemoteLedListResultListener p0);
```

**回調**：`onGetLedList(int code, int total, List<LedInfo> leds)`

```java
robot.led_getLedList(new IRemoteLedListResultListener.Stub() {
    @Override
    public void onGetLedList(int code, int total, List<LedInfo> leds) {
        for (LedInfo led : leds) {
            Log.i(TAG, led.getLedType() + " colors=" + Arrays.toString(led.getSupportColors())
                    + " effects=" + Arrays.toString(led.getSupportModes()));
        }
    }
});
```

### 眼睛（Eye）

```aidl
void turnOnEye(int p0, IRemoteLedOperationResultListener p1);
void turnOffEye(IRemoteLedOperationResultListener p0);
void turnOnEyeBlink(IRemoteLedOperationResultListener p0);
void turnOnEyeFlash(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
void turnOnEyeMarquee(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
```

| Method | 參數 | 說明 |
|---|---|---|
| `turnOnEye(colorCode, listener)` | `colorCode` — 顏色編碼 | 常亮 |
| `turnOffEye(listener)` | 冇 | 熄燈 |
| `turnOnEyeBlink(listener)` | 冇（用預設顏色/頻率） | 眨眼特效 |
| `turnOnEyeFlash(p0,p1,p2,p3, listener)` | 4 個整數，一般係顏色 + 時序參數（on/off 時長等） | 閃爍特效 |
| `turnOnEyeMarquee(p0,p1,p2,p3, listener)` | 同上 | 跑馬燈特效 |

```java
robot.led_turnOnEye(1 /* RED */, listener);
robot.led_turnOffEye(listener);
robot.led_turnOnEyeFlash(1, 2, 500, 500, listener);  // 例：顏色1↔2, 各亮500ms
```

### 頭部（Head）

```aidl
void turnOnHead(int p0, int p1, IRemoteLedOperationResultListener p2);
void turnOffHead(IRemoteLedOperationResultListener p0);
void turnOnHeadFlash(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
void turnOnHeadMarquee(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
void turnOnHeadBreath(int p0, int p1, int p2, int p3, IRemoteLedOperationResultListener p4);
```

`turnOnHead(p0, p1, listener)` 有兩個整數參數（一般係顏色 + 亮度/位置），其餘
3 個特效 method（flash/marquee/breath）都係 4 個整數參數嘅格式，用法同眼睛
嗰組一樣。

```java
robot.led_turnOnHead(1, 100, listener);        // 顏色1, 亮度100
robot.led_turnOnHeadBreath(1, 2, 1000, 1000, listener);  // 呼吸燈特效
robot.led_turnOffHead(listener);
```

### 嘴巴（Mouth）

```aidl
void turnOnMouth(int p0, IRemoteLedOperationResultListener p1);
void turnOffMouth(IRemoteLedOperationResultListener p0);
void turnOnMouthBreath(int p0, int p1, int p2, IRemoteLedOperationResultListener p3);
```

```java
robot.led_turnOnMouth(1, listener);
robot.led_turnOnMouthBreath(1, 1000, 1000, listener);
robot.led_turnOffMouth(listener);
```

### Wifi 指示燈

```aidl
void turnOnWifi(int p0, IRemoteLedOperationResultListener p1);
void turnOffWifi(IRemoteLedOperationResultListener p0);
```

```java
robot.led_turnOnWifi(2 /* GREEN */, listener);
robot.led_turnOffWifi(listener);
```

### 胸口燈（Chest）

```aidl
void turnOnChestLed(IRemoteLedOperationResultListener p0);
void turnOffChestLed(IRemoteLedOperationResultListener p0);
```

冇額外參數，只有開/關。

```java
robot.led_turnOnChestLed(listener);
robot.led_turnOffChestLed(listener);
```

---

## 4. Speech（語音/TTS/ASR）— `"speech"`

對應 AIDL：`ISpeechInterface`。呢個係最大嘅 interface（24 個 method），涵蓋
TTS 播放、語音辨識（ASR）、喚醒詞、麥克風 PCM 串流、聲音設定。

### PCM 串流（原始音頻）

```aidl
int registerPcmListener(String p0, IPcmListener p1);
int unregisterPcmListener(String p0);
```

- `p0` — 你自己揀嘅識別 key（string），用嚟喺 `unregister` 時指定要停邊個
- `p1` — PCM 數據回調

**回調**：`IPcmListener.onPcmData(byte[] data, int length)` — 每收到一段麥克風
原始音頻數據就會觸發一次。

```java
int result = robot.speech_registerPcmListener("mypanel", new IPcmListener.Stub() {
    @Override
    public void onPcmData(byte[] data, int length) {
        // data 前 length bytes 係原始 PCM 音頻
    }
});
// 用完之後：
robot.speech_unregisterPcmListener("mypanel");
```

### 喚醒詞回調

```aidl
int registerWakeUpCallbackListener(String p0, ISpeechWakeUpListener p1);
int unregisterWakeUpCallbackListener(String p0);
```

**回調**：`ISpeechWakeUpListener`
- `onSuccess()` — 偵測到喚醒詞
- `onError(int code, String message)` — 喚醒偵測出錯

```java
robot.speech_registerWakeUpCallbackListener("mypanel", new ISpeechWakeUpListener.Stub() {
    @Override
    public void onSuccess() {
        Log.i(TAG, "wake word detected!");
    }
    @Override
    public void onError(int code, String message) {
        Log.e(TAG, "wake detection error: " + message);
    }
});
```

### TTS 播放

```aidl
int onPlayCallback(String p0, String p1, ITtsCallBackListener p2);
void onStopPlay();
void setVoiceName(String p0);
void setTtsSpeed(String p0);
String getTtsSpeed();
void setTtsVolume(String p0);
String getTtsVolume();
```

- `onPlayCallback(text, voiceName, listener)` — 播放一段文字轉語音
  - `p0` — 要講嘅文字
  - `p1` — 聲線名稱（用 `getSpeechVoices()`/`getCurSpeechVoices()` 攞返嘅名）
  - `p2` — 播放開始/結束回調：`ITtsCallBackListener.onBegin()`/`onEnd()`
- `onStopPlay()` — 停止目前播放緊嘅 TTS
- `setVoiceName(name)` — 設定預設聲線
- `setTtsSpeed(speed)`/`getTtsSpeed()` — 語速（string 格式，數值範圍由韌體定義）
- `setTtsVolume(volume)`/`getTtsVolume()` — 音量（string 格式）

```java
robot.speech_setVoiceName("xiaoyan");
robot.speech_setTtsSpeed("50");
robot.speech_onPlayCallback("你好，我係機械人", "xiaoyan", new ITtsCallBackListener.Stub() {
    @Override
    public void onBegin() { Log.i(TAG, "TTS started"); }
    @Override
    public void onEnd() { Log.i(TAG, "TTS finished"); }
});
```

### 語音辨識（ASR）

```aidl
void startSpeechAsr(String p0, int p1, ISpeechAsrListener p2);
void stopSpeechAsr();
```

- `p0` — 識別 key（string）
- `p1` — 模式（`int`，具體數值由韌體定義，一般 `0` 代表預設模式）
- `p2` — 結果回調

**回調**：`ISpeechAsrListener`
- `onBegin()` — 開始收音
- `onEnd()` — 收音結束
- `onResult(String text)` — 辨識結果
- `onError(int code)` — 辨識出錯

```java
robot.speech_startSpeechAsr("mypanel", 0, new ISpeechAsrListener.Stub() {
    @Override public void onBegin() { Log.i(TAG, "listening..."); }
    @Override public void onEnd() { Log.i(TAG, "done listening"); }
    @Override public void onResult(String text) { Log.i(TAG, "heard: " + text); }
    @Override public void onError(int code) { Log.e(TAG, "asr error " + code); }
});
// 需要提早停止時：
robot.speech_stopSpeechAsr();
```

### 聲線列表

```aidl
List getSpeechVoices();
SpeechVoice getCurSpeechVoices();
```

> ⚠️ **重要**：`getSpeechVoices()` 喺 AIDL 層面聲明做冇 generic 參數嘅 raw
> `List`（唔係 `List<SpeechVoice>`）——呢個係跟返機械人韌體實際嘅 marshalling
> 方式（`writeList`/`readArrayList`，唔係 typed list 嗰種 `writeTypedList`）。
> 你唔使理呢個底層細節：`Alpha2RobotApi.speech_getSpeechVoices()` 已經幫你做咗
> unchecked cast，直接畀返 `List<SpeechVoice>`。如果你係直接用 Stub（方式二），
> 就要自己做呢個 cast。

```java
// facade 寫法（已經係 List<SpeechVoice>，唔使自己 cast）：
List<SpeechVoice> voices = robot.speech_getSpeechVoices();
for (SpeechVoice v : voices) {
    Log.i(TAG, v.getName() + " sex=" + v.getSex() + " adult=" + v.getAdult());
}

SpeechVoice current = robot.speech_getCurSpeechVoices();
```

### 語法/本地功能/模式設定

```aidl
void initSpeechGrammar(String p0, ISpeechGrammarInitListener p1);
void switchSpeechCore(String p0);
void switchWakeup(boolean p0);
void startLocalFunction(String p0);
boolean isSpeechGrammar();
boolean isSpeechIat();
void setSpeechMode(int p0);
```

- `initSpeechGrammar(grammar, listener)` — 初始化自訂語法（**注意**：機械人用
  嘅係寫死嘅 Nuance VoCon 語法，呢個 method 喺呢個韌體版本實際效果有限——見
  README 已知限制）
  - **回調**：`ISpeechGrammarInitListener.speechGrammarInitCallback(String grammar, int code)`
- `switchSpeechCore(core)` — 切換語音引擎（string 標識，例如唔同語言/廠商引擎）
- `switchWakeup(enabled)` — 開關喚醒詞偵測
- `startLocalFunction(function)` — 觸發機械人本地內建功能（string 標識）
- `isSpeechGrammar()` / `isSpeechIat()` — 查詢目前語音辨識模式狀態
- `setSpeechMode(mode)` — 設定語音模式（`int`，數值由韌體定義）

```java
robot.speech_switchWakeup(true);
robot.speech_setSpeechMode(0);
Boolean grammarMode = robot.speech_isSpeechGrammar();
```

### 錄音控制

```aidl
void stopRecording();
void startRecording();
```

```java
robot.speech_startRecording();
// ...
robot.speech_stopRecording();
```

---

## 5. Sys（系統資訊）— `"sysinfo"`

對應 AIDL：`ISysService`。版本查詢、電量、鬧鐘、PIR 感應器、升級模式。

### 版本/身份資訊

```aidl
String getSid();
String getMICVersion();
String getChestVersion();
String getHeadVersion();
String getBatteryVersion();
```

全部係即時同步返值嘅 getter（唔係非同步 listener 模式）：

```java
String sid = robot.sys_getSid();               // 機械人序號/身份標識
String micVer = robot.sys_getMICVersion();      // 麥克風板韌體版本
String chestVer = robot.sys_getChestVersion();  // 胸口主板韌體版本
String headVer = robot.sys_getHeadVersion();    // 頭部主板韌體版本
String battVer = robot.sys_getBatteryVersion(); // 電池韌體版本
```

### 電量

```aidl
boolean isPowerCharging();
int getPowerValue();
```

```java
Boolean charging = robot.sys_isPowerCharging();  // null = binder 未連接
Integer power = robot.sys_getPowerValue();       // 電量百分比（0-100，具體定義由韌體決定）
```

### 鬧鐘

```aidl
AlarmInfo[] queryAllAlarm(String p0);
int insertAlarm(in AlarmInfo p0);
```

- `queryAllAlarm(key)` — `key` 用途由韌體定義（可能係過濾條件，亦可能未使用，
  傳空字串 `""` 通常安全）
- `insertAlarm(alarmInfo)` — 新增一個鬧鐘，返回值 `int` 一般係新增成功與否嘅
  code（非 listener 模式，係同步返值）

```java
AlarmInfo[] alarms = robot.sys_queryAllAlarm("");
for (AlarmInfo a : alarms) {
    Log.i(TAG, "alarm at " + a.hh + ":" + a.mm + " repeat=" + a.repeat);
}

AlarmInfo newAlarm = new AlarmInfo();
newAlarm.hh = 7;
newAlarm.mm = 30;
newAlarm.isUseAble = true;
newAlarm.actionStartName = "wake_up_dance";
Integer insertResult = robot.sys_insertAlarm(newAlarm);
```

> `AlarmInfo` 全部 field 都係 **public**（唔係用 getter/setter），跟返機械人
> 原本嘅設計——直接讀寫 field 就得。詳見 [Parcelable 資料結構](#alarminfo)。

### 升級模式 / 啟動其他 App

```aidl
void enterUpgradeMode();
void exitUpgradeMode();
void startApp(in Uri p0);
```

```java
robot.sys_enterUpgradeMode();
// ... 完成升級相關操作
robot.sys_exitUpgradeMode();

robot.sys_startApp(Uri.parse("content://com.example/some/target"));
```

### PIR 人體感應器

```aidl
void setPIRSensor(boolean p0, IRemotePIRSensorOperationResultListener p1);
```

**回調**：`onPIRSensorOpResult(int code)`

```java
robot.sys_setPIRSensor(true, new IRemotePIRSensorOperationResultListener.Stub() {
    @Override
    public void onPIRSensorOpResult(int code) {
        Log.i(TAG, "PIR sensor toggled, code=" + code);
    }
});
```

---

## Parcelable 資料結構

### `ActionInfo`

```java
public class ActionInfo {
    String getId();       // 動作 id
    String getName();     // 英文名（對應內部 en_name）
    String getCnName();   // 中文名（額外提供，方便顯示）
    String getDesc();     // 描述
    int getTime();        // 時長（毫秒，int）
    long getDuration();   // 時長（毫秒，long 版本，同 getTime() 數值一樣）
    String getType();     // 動作分類（見 ActionType 常數）
}
```

### `MotorInfo`

```java
public class MotorInfo {
    int getId();               // 馬達編號
    int getUpperLimitAngle();  // 角度上限
    int getLowerLimitAngle();  // 角度下限
    int getRotatingSpeed();    // 轉速
    int getTorque();           // 扭力
}
```

### `MotorAngle`

```java
public final class MotorAngle {
    MotorAngle(int id, int angle);  // 建構子
    int getId();
    int getAngle();
    void setId(int id);
    void setAngle(int angle);
}
```

用喺 `motor_setAllMotorAbsoluteAngle()` 嘅輸入陣列，每個代表一顆馬達嘅目標角度。

### `LedInfo`

```java
public final class LedInfo {
    Led getLedType();                 // HEAD / EYE / MOUTH / EAR / CHEST
    LedColor[] getSupportColors();    // 呢組 LED 支援嘅顏色
    LedEffect[] getSupportModes();    // 呢組 LED 支援嘅特效
    void setLedType(Led led);
    void addColor(LedColor color);
    void addEffect(LedEffect effect);
}
```

`Led`/`LedColor`/`LedEffect` 喺 `com.ubtechinc.alpha.sdk.led` package，見
[附錄：顏色/特效編碼表](#附錄顏色特效編碼表)。

### `AlarmInfo`

全部 field 都係 **public**，直接讀寫：

```java
public class AlarmInfo {
    public int id;
    public int state;
    public int hh, mm, ss;             // 時分秒
    public int repeat;                 // 重複規則（bitmask，由韌體定義）
    public boolean isUseAble;          // 是否啟用
    public String actionStartName;     // 觸發時播放嘅動作
    public String acitonEndName;       // 結束時播放嘅動作（原文拼寫如此，唔係 typo）
    public int actionType;
    public int yy, mo, day, date;      // 年（相對2000）/月/星期/日
    public boolean vibrate;
    public String label;
    public Uri alert;                  // 鬧鐘音效
    public boolean silent;
    public long dtstart;
    public boolean iscomplete;
    public long dttime;
}
```

### `SpeechVoice`

```java
public class SpeechVoice {
    String getName();
    int getSex();
    int getAdult();
    String getLanguage();  // 注意：呢個 field 喺 Parcel 讀寫時唔會被傳輸
                            // （機械人原本行為如此），永遠都係建構時嘅預設值 ""
    void setName(String name);
    void setSex(int sex);
    void setAdult(int adult);
    void setLanguage(String language);
}
```

---

## 完整範例

見 `sdk-module/ubtechalpha2robot/src/main/java/com/ubtechinc/alpha2robot/SampleUsage.java`
——一個獨立、可直接參考嘅 class，逐個子系統示範晒點樣調用，包括正確嘅
listener 寫法、UI thread 處理、資源釋放時機。

亦可以參考 `app/src/main/java/com/open/lynx/MainActivity.java`——實際運行嘅測試
面板，每粒掣對應一個 AIDL call。

---

## 附錄：顏色/特效編碼表

### `Led`（LED 組別，`com.ubtechinc.alpha.sdk.led.Led`）

| 名稱 | 數值 |
|---|---|
| `HEAD` | 1 |
| `EYE` | 2 |
| `MOUTH` | 3 |
| `EAR` | 4 |
| `CHEST` | 5 |

### `LedColor`（顏色，`com.ubtechinc.alpha.sdk.led.LedColor`）

| 名稱 | 數值 |
|---|---|
| `RED` | 1 |
| `GREEN` | 2 |
| `BLUE` | 3 |
| `YELLOW` | 4 |
| `MAGENTA` | 5 |
| `CYAN` | 6 |
| `WHITE` | 7 |
| `BLACK` | 8 |

### `LedEffect`（特效，`com.ubtechinc.alpha.sdk.led.LedEffect`）

| 名稱 | 說明 |
|---|---|
| `LIGHT` | 常亮 |
| `BLINK` | 眨眼 |
| `FLASH` | 閃爍 |
| `BREATH` | 呼吸燈 |
| `MARQUEE` | 跑馬燈 |

> `LedEffect` 冇顯式數值編碼（同 `Led`/`LedColor` 唔同），`ILedInterface` 個別
> method（`turnOnEyeFlash`/`turnOnHeadMarquee` 等）嘅整數參數係時序/顏色組合，
> 唔係直接傳 `LedEffect` ordinal——具體參數意義由對應 method 名決定（例如
> `turnOnEyeFlash(color1, color2, onMs, offMs, listener)`，實際次序未經機械人
> 韌體原始碼確認，建議實機測試唔同數值嘅效果）。

---

## 附錄：Service key 對照表

| Service key | AIDL interface | `ServiceFetcher` 常數 |
|---|---|---|
| `"action"` | `IActionService` | `ServiceFetcher.SERVICE_ACTION` |
| `"motor"` | `IMotorInterface` | `ServiceFetcher.SERVICE_MOTOR` |
| `"led"` | `ILedInterface` | `ServiceFetcher.SERVICE_LED` |
| `"speech"` | `ISpeechInterface` | `ServiceFetcher.SERVICE_SPEECH` |
| `"sysinfo"` | `ISysService` | `ServiceFetcher.SERVICE_SYSINFO` |

（`IServiceFetcher` 本身唔喺呢個表——佢係取得以上 5 個 service 嘅入口，唔係
一個「子系統」。）

---

## 附錄：Lynx 韌體 (`alpha2services_base` 3.0.0.2) 反編譯發現 — 未使用/未接收嘅項目

> **適用範圍**：以下發現全部對照
> `com.ubtechinc.alpha2services_base.3.002.apk`（`open-lynx` 專案實際連緊嘅
> 韌體版本，`android:versionName="v3.0.0.2"`）反編譯確認，**唔一定適用於
> Alpha2 韌體 1.1.7.3**（見文件頭段「錯誤處理慣例」表之後嘅一般假設）。
> `open-lynx` 用嘅 `LynxRobotApi`/`ServiceFetcher` 底層機制同呢份文件描述嘅
> `Alpha2RobotApi`/`ServiceFetcher` 一致，都係經 `IServiceFetcher.getService()`
> 攞 binder，所以以下關於 broker 行為嘅發現原則上兩邊通用；但邊個 service
> key/broadcast action 實際有冇被登記/發出，則完全取決於個別韌體版本嘅
> `MainService` 實作，唔可以假設 Alpha2 韌體都係咁。

### Service registry 機制（`IServiceFetcher.getService()` 底層行為）

反編譯確認 `IServiceFetcher$Stub`（broker 抽象基底，`MainServiceImpl` 實作）嘅
`getService`/`addService`/`removeService` 全部直接轉發去 `Lrz;`——一個**純靜態
`HashMap<String, IBinder>`**，冇任何 lazy-init、重試或者延遲登記邏輯：

```
rz.a(key, binder)   // = addService，寫入
rz.b(key)           // = getService，讀取；冇個 key 就即刻返 null
rz.a(key)           // = removeService（單參數 overload），移除
```

即係話：**如果某個 service key 喺機身開機初始化流程冇被 `addService()` 過，
`getService(key)` 會永遠返 `null`——唔係「未 ready，遲啲再 call 會好返」，而係
「呢個 build 根本冇呢個 service」。**

呢個 registry 喺初始化流程入面只喺一個地方被寫入：
`com.ubtechinc.alpha.service.MainService.onStartOnce()`（呢個私有 method 喺
反編譯結果入面個名叫 `a()`）：

```java
protected void a() {  // onStartOnce
    ...
    rz.a("action",  ActionServiceProxy.a().b());
    rz.a("led",     LedServiceProxy.a().b());
    rz.a("motor",   MotorServiceProxy.a().b());
    rz.a("sysinfo", SysServiceImpl.get(this));
    // 冇 rz.a("speech", ...) —— 呢一行喺呢個 build 完全唔存在
    ...
}
```

搜尋咗成個 APK 所有 `rz.a(String, IBinder)` 嘅 call site，**呢 4 行係唯一寫入
點**，冇任何延遲/背景 thread 補登記 `speech`。

### Service key：`speech` 喺呢個韌體版本攞唔到 binder（已確認，非時序問題）

| Service key | 有冇喺 `onStartOnce()` 登記 | 結果 |
|---|---|---|
| `action` | ✅ | 正常 |
| `led` | ✅ | 正常 |
| `motor` | ✅ | 正常 |
| `sysinfo` | ✅ | 正常 |
| **`speech`** | ❌ 從未登記 | `getService("speech")` 永遠返 `null` → `LynxRobotApi`/`Alpha2RobotApi` 呼叫任何 `speech_*()` method 都會即刻返 `API_ERROR_NOT_INIT`，唔會因為等耐咗、重試就好返 |

**同「4. Speech」章節嘅已知限制係互相獨立、但互相呼應嘅兩層問題**：即使將來
繞過咗 `SpeechServicesImpl$1` 全部 method 係空 stub 呢一層，`getService("speech")`
連 binder 都攞唔返嚟，兩層都要解決先有得用。`speech_startRecording()`/
`speech_stopRecording()`（`open-lynx` 用嚟測試釋放/重攞機身 mic session 嗰兩個
method）喺呢部機呢個韌體版本上**冇得用**，唔使再花時間喺呢個方向嘗試修。

> 機身內部另一個模組（`SpeechMainServiceUtil`，喺 `alpha2services_base`
> process 自己入面）都係用同一個機制（`ru`/`BinderProvider`）去攞
> `"speech"`——即係話呢個 registry 空缺係全域性嘅，唔淨止影響第三方 App，機身
> 自己另一個模組一樣攞唔到。

### 未被接收嘅 broadcast（機身有發，`open-lynx` 而家冇 register）

反編譯搜尋咗成個 APK 所有 `sendBroadcast()`/`sendBroadcastAsUser()` call
site，篩走同 Tencent SDK（XG push、AV chat 等）相關嘅雜訊之後，對照
`open-lynx`（`MainActivity.registerDynamicReceiver()` + `RobotEventReceiver.java`）
現有 `IntentFilter`，搵到以下 4 個機身確實會發、但之前完全冇 register 嘅
action，**已經喺 2026-08 修正加返**：

| Action | Extra | 觸發時機（反編譯來源） |
|---|---|---|
| `com.ubtechinc.services.header` | `"value"`（int）：`4`=連按/音量+1，`5`=長按/音量×0.5 | `HeadkeyManager$2`/`$3`（`com.ubtechinc.alpha.jni.headkey.lynx` package，Lynx 專用）機頭實體掣連按/長按觸發 |
| `com.ubtechinc.services.Action.ACTION_STOP` | 冇 | `AlphaUtils.sendActionStopIntent()`，動作被外部打斷停止（全域廣播，唔限於自己 call 緊嗰個 `playAction()` session，同 `IActionResultListener.onStopActionResult()` 唔同） |
| `com.ubtechinc.services.Action.ROBOT_INTERRUPTED` | 冇 | `AlphaUtils.sendInterruptIntent()`，機械人整體被打斷（通常同 TTS/action 一齊停） |
| `com.ubtechinc.services.stoptts` | 冇 | `HeadkeyManager.backFormKeyOnDown()`，按機頭實體掣其中一粒掣順帶觸發嘅 stop-TTS 信號，獨立於自己 call 嘅 `speech/stop` API |

已經喺 `RobotEventReceiver.java` 加返對應 case，經 `EventBus` 分別 publish 做
`header_key`、`action_stop`、`robot_interrupted`、`stop_tts` 四個 event type
（前端 Event Log 通用機制自動顯示，唔使逐個 type 喺 `app-log.js` 加特殊
case，除非之後想要專屬 UI tile）。

### 假陽性更正：`com.ubtechinc.key` 喺呢個韌體版本係死 code

`RobotEventReceiver.java` 原有處理緊 `com.ubtechinc.key`（Byte extra
`"key"`），但反編譯**成個 APK 搵唔到任何 `sendBroadcast("com.ubtechinc.key")`
出處**。呢個 action 喺呢個韌體版本已經被上面嗰個 `com.ubtechinc.services.header`
（int extra `"value"`）完全取代——命名同 extra 類型都唔同，唔係簡單改咗名，
係整個機制換咗。`com.ubtechinc.key` 個 case 已經加返註解標明「呢部機唔會再
觸發」，但保留低做向後相容（以防其他韌體/舊機仍然用緊呢個 action）。

### 尚未確認嘅項目（證據不足，未落結論）

以下 action string 喺 dex constant pool 入面**確實存在**（同 `com.ubtechinc.key`
嗰種「完全搵唔到」唔同級），但用靜態 xref 分析搵唔到實際 `sendBroadcast()`
call site（可能喺 native/JNI 層發出，或者用 string concatenation 砌，靜態
分析漏咗）——**唔應該假設佢哋壞咗或者好地地**，需要 `adb logcat` 實機驗證先
可以落結論：

- `come.ubt.alpha2.gesture`（`open-lynx` 現有處理緊）
- `com.ubtechinc.services.SPEECH_DIRECTION`（`open-lynx` 現有處理緊）

另外 `com.ubtechinc.robot_uuid.info`（`open-lynx` 現有處理緊）喺呢個 APK 嘅
string pool 入面**完全唔存在**（同 `come.ubt.alpha2.gesture` 呢種「存在但搵
唔到 xref」唔同級——呢個係連字串本身都冇），但因為未搵到佢實際喺邊個模組
被引用（有可能係另一個獨立 APK/組件負責），未落實「呢個韌體版本一定唔會
發」呢個結論，記錄喺度留待之後用 `adb logcat` 或者實機觸發測試核實。

