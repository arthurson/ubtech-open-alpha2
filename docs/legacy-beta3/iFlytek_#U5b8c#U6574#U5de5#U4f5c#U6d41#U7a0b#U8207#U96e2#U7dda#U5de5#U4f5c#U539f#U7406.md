> ⚠ ARCHIVED：此文件只適合 beta3；beta4（pure-direct）起已無用，詳見同目錄 README.md。
>
# iFlytek 完整工作流程與離線工作原理

> 基於 Open Alpha2 專案（UBTECH Alpha2 機械人，firmware v1.1.7.3.20）之實際反編譯結果、logcat 實測與代碼整理。
> 本文件內所有結論均來自機身實測（errorCode/logcat 佐證），非推測。

---

## 1. 整體架構總覽

```
┌───────────────────────────────────────────────────────────────────┐
│                         Alpha2 機械人系統                          │
├───────────────────────────────────┬─────────────────────────────┤
│  alpha2services（韌體，closed src）│   Open Alpha2 App（用戶 App） │
├───────────────────────────────────┼─────────────────────────────┤
│ • 5-mic 陣列 + IVW 喚醒詞引擎      │ • HttpServer（port 8888）    │
│ • iFlytek MSC（中文 ASR/TTS）      │ • WebSocketServer            │
│ • Nuance VoCon（內建英文 ASR/TTS） │ • EventBus 事件分派           │
│ • Alpha2RobotApi（AIDL 對外接口）  │ • IflytekSemanticMatcher      │
└───────────────────────────────────┴─────────────────────────────┘
```

**核心數據流：**

```
用戶語音 → 5-mic 陣列（硬體層，不經 AudioFlinger）
        → 喚醒詞觸發（IVW，中："你好阿爾法" / 英："hello alpha"）
        → App 即時做一次網路探測（決定用哪條 ASR 路徑）
        → 有網：iFlytek 雲端聽寫（type:0，任意句子）
        → 沒網：iFlytek 本地 BNF 文法識別（type:1，僅限已寫死的句子/字）
        → 語意匹配（IflytekSemanticMatcher / …En）
        → TTS 回答 + 動作執行
```

---

## 2. 兩個 ASR 引擎，各自負責一種語言

這台機同時內建兩個完全獨立的語音辨識引擎，並非「一個引擎、兩種語言」：

| | iFlytek MSC | Nuance VoCon |
|---|---|---|
| 負責語言 | **中文普通話** | **英文** |
| 離線能力 | ✅ 但要靠 BNF 文法（見第 3 節） | ✅ 內建於原生代碼，開箱即用 |
| 詞彙可否擴充 | ✅ 自訂 BNF，可加句子/單字 | ❌ `initGrammar` 是空 stub，永遠加不了字 |
| 雲端聽寫 | ✅ 任意句子（`openspeech.cn`） | ☠️ 雲端伺服器已停運，低於信心門檻（約 4500 分）的結果一律失敗、有去無回 |
| 切換方式 | `speech/set_language?lang=zh_cn` | `speech/set_language?lang=en_us` |

**關鍵反編譯結論**：iFlytek 官方文檔（訊飛開放平台）明確寫明：

> 「離線命令詞識別是否支持英文？答：離線命令詞只支持中文普通話，暫不支持英文。」

也就是說 `assets/asr/common.jet`（機身用的那個聲學模型，6.8MB）**只有普通話音素**，並非文法寫法問題——後面第 6 節有完整實驗證明這一點。

---

## 3. 離線中文語法識別（iFlytek BNF）— 主力方案

### 3.1 文法格式（實測唯一可用格式）

機身接受的不是網上常見的 IAMVERSION 1.1.0 格式，而是悠聊原廠 APK（`UbtechIflytekMix`）裡面 `call.bnf` 用的 **`#BNF+IAT 1.0` 格式**：

```
#BNF+IAT 1.0 UTF-8;
!grammar call;
!start <start>;
<start>: 你好 | 再見 | 跳舞 | ...;
```

**已確認的語法規則（血淚實測）：**

| 規則 | 說明 |
|---|---|
| 第一行必須是 `#BNF+IAT 1.0 UTF-8;` | 格式頭，不可以用 IAMVERSION 版本 |
| `!grammar` 名必須是 `call` | 和機身 `grammarId=call` 對應 |
| **所有句子裡不准有空格** | 中文沒問題；英文多字短句（如 `how old`）**必定 23300 失敗**，只有單字可以 |
| **rule 之間不支援串接引用** | 中文/英文串接（`<a><b>` 沒空格）一樣 23300，不是 IAMVERSION 引擎不兼容這種寫法 |
| **未使用的 `!slot` 聲明會觸發 23300** | 有聲明就一定要用，不用就不要聲明 |
| 句子裡面不可以含 `:` 或 `;` | 這兩個字元是 parser 保留字元，混入 alternatives 會弄壞這個 rule 邊界（見第 7 節事故） |
| 每條 rule 結尾要有 `;` | 漏了會讓下一條 rule 的內容被吞併 |

### 3.2 完整運行時流程

```
App 啟動 → MainActivity.onCreate() → bindService(Alpha2SpeechMainService)
    → robot.speech_initGrammar(bnfString, ISpeechGrammarInitListener)
        → 回調 speechGrammarInitCallback(grammarId, errorCode)
            errorCode == 0 → lastGrammarBuildOk = true
    → （已構建成功後）robot.speech_startGrammar(IAlpha2SpeechGrammarListener)
        → 機身進入 SPEECH_STATE_GRAMMAR，等待喚醒詞

用戶說話：
    喚醒詞觸發（MicArray wakeup）→ 韌體用 BNF 語法網路做本地解碼
    → onSpeechGrammarResult(type=1, result)
        result 是 **原始 JSON 字串**（不是純文字！）：
        {"text":"你好","rc":4}          ← 簡單格式
        {"ws":[{"cw":[{"w":"你叫什么名字"}]}]}  ← 部分固件版本的完整格式
    → extractGrammarResultText() 解析出乾淨文字
    → EventBus.publish("asr_result", text)
    → IflytekSemanticMatcher.match(text)
    → TTS 回答 + 動作執行
```

**重要陷阱**：離線文法結果的 JSON 結構和雲端聽寫（type:0）不一樣——雲端是純文字，離線文法有時字藏在 `ws[].cw[].w` 裡面，而不是頂層 `text` 欄位。沒處理這個結構會讓對話界面顯示不出東西、或者顯示一堆 JSON 碎片（實測踩過這個坑，見第 7 節）。

### 3.3 語意庫自動生成 BNF

文法不是手寫，而是由語意庫 JSON（`iflytek_semantic_zh.json`）在 App 啟動時自動生成：

```
iflytek_semantic_zh.json（946 條問答）
    ↓ 每條問法（q 欄位）轉成一個 BNF alternative
    ↓ 過濾：含 `:` / `;` 的句子跳過（防止 parser 事故）
    ↓ 簡轉繁（SimplifiedToTraditional），讓悠聊簡體詞條和語意庫繁體詞條自動合併去重
default_grammar.bnf（生成後的完整文法字串，經 speech_initGrammar 送入機身）
```

**最終穩定版本（已驗證 errorCode=0，離線對答完整跑通）：**

| 項目 | 數字 |
|---|---|
| 中文句子 | **1211 句**（全繁體、零完全重複） |
| 英文單字 | 3 個（Goodbye / Bye / Thanks） |
| 合計送入機身 | **1214 句** |
| 語意庫（JSON） | 946 條（由原本 1000 條剔除 54 句「你有沒有特別/最喜歡/最想…」模板填充句） |

> 此數字經歷多輪迭代（1863 → 1618 → 1388 → 1268 → 1265 → 1211），每次都是為了清掉簡繁重複、rule 名污染（`app`／`happy`／`power` 這些悠聊 rule 名混入詞庫）、模板填充句而縮減，並非功能倒退。

---

## 4. 自動在線/離線切換機制

### 4.1 核心邏輯

```java
private void applyConnectivityMode(boolean connected, String reason) {
    if (!offlineGrammarAutoSwitch || !speechReady) return;

    if (!connected) {                          // 判定為離線
        if (offlineGrammarActive) return;       // 已經在離線模式，不用再切
        robot.speech_setRecognizedLanguage("zh_cn");
        if (!lastGrammarBuildOk) {
            pendingOfflineEnable = true;
            doInitGrammar(defaultBnf);           // 沒構建過先構建
        } else {
            doStartGrammar();                    // 已構建直接啟動
        }
    } else {                                   // 判定為在線
        if (offlineGrammarActive &&
            now - lastModeSwitchMs < 15000) return;  // 15 秒冷卻期
        pendingOfflineEnable = false;
        if (offlineGrammarActive) doStopGrammar();
    }
}
```

### 4.2 網路探測 —「連了 WiFi」≠「有網」

這是最初版本的盲點：Android 的 WiFi `connected` 狀態只代表連了熱點，不代表熱點本身有數據。手機熱點沒流量時，舊邏輯會誤判為「在線」，導致語音死了都還在用雲端模式。

**修正後的探測邏輯：**

```java
private static boolean hasRealInternet() {
    String[][] targets = {
        {"ubtek.openspeech.cn", "80"},   // 機身實際連著的專屬伺服器（由 smali 反編譯確認）
        {"openspeech.cn", "80"},          // 訊飛官方域名
        {"voicecloud.cn", "443"}          // 備用
    };
    // TCP connect，timeout 2.5 秒，任一通即判定有網
}
```

**反編譯確認**：機身實際連的是 `ubtek.openspeech.cn`（`server_url=http://ubtek.openspeech.cn/...`），這是 UBTECH 自己的訊飛轉發伺服器，不是訊飛公版域名——所以探測目標必須包含這個域名，單靠公共 DNS（8.8.8.8）通不通完全不能反映 iFlytek 雲端服務本身可不可用。

### 4.3 防抖動設計

| 機制 | 參數 | 原因 |
|---|---|---|
| **冷卻期** | 15 秒 | 防止網路忽有忽無時反覆切換，讓文法引擎不斷 destroy/rebuild |
| **喚醒即測** | 每次喚醒詞觸發時即時 probe 一次 | 「從第一句對答就應該知道是不是離線」——不等 30 秒週期，第一句就用最新狀態回答 |
| **後台 watchdog** | 每 30 秒背景探測一次 | 用戶沒說話期間都持續監察網路變化 |
| **NetworkOnMainThreadException 修正** | probe 移到背景線程 | 早期版本 probe 在 main thread 跑，會直接拋異常導致探測結果恆定失敗 |
| **構建防重入鎖** | 同一時間只准一個 initGrammar 在跑 | 網路忽通忽斷時，自動切換高頻觸發 → 文法引擎被反覆拆重裝 → 完全沒反應（已修正的實際事故） |

---

## 5. 對話界面顯示過濾

**問題**：離線文法結果（`grammar_result`）的原始 payload 會夾雜大量技術性內容（`grammar init id=call`、`[ACTION WELCOME] 動作ID:1509…`、JSON 碎片），直接顯示在對話氣泡裡會混亂到不像人類對話。

**修正方向**：對話界面只顯示乾淨的中/英文辨識結果，同一過濾邏輯套用到雲端聽寫（`asr_result`）和離線文法（`grammar_result`）兩種事件，技術性 metadata 只留在 WebSocket 原始日誌／debug console，不進對話氣泡。

> 此修正經歷過一次回歸事故：中途改壞了讓離線對白完全消失、機械人沒反應，根源是文法引擎防重入鎖和對話過濾邏輯改動疊加出來的副作用，最終靠即時掛 WebSocket 監聽器直接對比實際 payload 才定位到問題（詳見第 7 節）。

---

## 6. 英文離線辨識——完整實驗記錄與結論

這部分經歷了長達十幾輪的實測，結論明確但過程值得完整記下，以免日後重複走冤枉路。

### 6.1 已證實行不通的方法

| 嘗試 | 結果 | 原因 |
|---|---|---|
| BNF 寫多字英文短句（`how old`） | ❌ 23300 | 這個 BNF 方言不准任何空格 |
| 引號包裹短句 | ❌ 連回執都沒有 | parser 直接卡死 |
| rule 串接（`<a><b>` 沒空格） | ❌ 23300 | 引擎連中文串接都不支援，非語言問題 |
| 連字符寫法（`i-am-a-boy`） | ⚠️ 構建成功（errorCode=0）但辨識無反應 | parser 接受寫法，但聲學模型完全不認得這些音 |
| GitHub 搜索其他人的 BNF 英文方案 | ❌ 沒有任何方法 | 訊飛官方文檔一錘定音：離線命令詞只支持普通話 |

### 6.2 意外發現：單字有時認得到

實測發現離線狀態下說「**hello**」，機身**確實認得到**（單字，非短句），重開機後重測依然穩定認得到。這證明 `common.jet`（中文聲學模型）不是完全對英文音免疫——某些英文單字的音素組合剛好和中文音素路徑撞得上，屬於「湊巧可行」而非官方支援。

基於這個發現，逐步擴展英文單字詞庫：

| 階段 | 單字數 | 備註 |
|---|---|---|
| 第一批 | 18 個 | dance / hello / happy 等，動作類配真實動作、QA 類配固定答案 |
| 第二批 | +6 個 | happy / upset / dinner / welcome / car / food |
| 每字多答案 | 24 字，每字 4 個隨機答案 | 原本每字只有 1 個固定答案，說三次就會聽到重複；仿照中文庫慣例補齊 |
| 大規模擴展 | **3000 個常用英文單字**（google-10000-english 詞表前 3000） | 見 6.3 |

**已知限制（架構性，非 bug）：** 文法裡面只有單字「happy」，用戶說成句「are you happy」不會被完整命中——只有 matcher 的模糊匹配邏輯（輸入包含詞庫問法）可能撈到個別單字。

### 6.3 3000 字大規模擴展（最新一輪，測試未完成）

**流程：**
1. 拿 GitHub `google-10000-english` 常用字表前 3000 字
2. 生成腳本：每字配 5 句隨機答案 + 動作映射
3. 過濾成人字詞（`sex` / `porn` / `fuck` 等，兒童機械人不應回應），連漏網的 `ass` 都補刀濾掉
4. 最終英文庫：**4207 句總計**（含動作映射和多答案）
5. 送入機身構建：**大文法構建成功，errorCode=0**（含 3000 英文字 + 原有 1211 中文句，共 4211 句）

**⚠️ 未完成事項**：這一輪構建成功之後，只安排了 5 個字（apple / water / music / computer / happy）做初步認中率實測，**session 結束時測試結果仍未取得**。3000 字的實際離線認中率屬未知數，官方文檔已表明這批屬於「hello 級數的運氣」——結構上可以認，不代表音素層面真的穩定。**下次開工應先完成這個實測，再決定 3000 字方案是否值得保留**（過大的文法也可能拖慢構建時間、增加語意衝突機率）。

### 6.4 已否決的方案：APK 資源手術（危險，勿重試）

機身 `alpha2services` APK 裡面同時藏有兩份聲學模型：

```
assets/asr/common.jet      ← 6.8MB 中文（機身實際使用）
assets/asr/common_en.jet   ← 14MB 英文（韌體從未載入過，但確實存在）
```

反編譯證實兩份資源**內部容器格式完全一致**（同版本 `v5pp`，同樣內嵌 `grm.irf`），技術上引擎理論上吃得下。曾經構思「換皮方案」：用 `jar` 工具將 `common.jet` 內容替換為 `common_en.jet`，實現一鍵中英文模式切換（機身出廠自帶 root adb，任何原裝機都可執行，且可逆）。

**實測結果：徹底失敗。**

```
Failed to parse: Failed reading assets/asr/common.jet in StrictJarFile
Skipping PackageSetting com.ubtechinc.alpha2services due to missing metadata
```

Android 5.1 的 `StrictJarFile` 在開機掃描階段直接拒絕接受被通用 `jar` 工具改寫過的 zip entry（要求 byte-exact 的 local header、STORED 對齊、無 data descriptor），導致整個 `alpha2services` 系統服務被跳過、面板完全失聯。已即時將原裝 APK 推回機身、重啟後完全還原正常。

**結論：這條路正式判死。** 要做到「byte-exact 符合老版 zip 驗證器」需要自行寫底層 zip 工具，即使做到，每次切換都要接 USB、開機時仍有被 PM 跳過的風險，且換了英文模型中文離線就會失效——「任何原裝機即裝即用」和「離線英文句子」在不自帶第三方引擎的前提下數學上不可兼得。

### 6.5 Nuance 英文引擎——被忽略的現成選項

反編譯證實 **Nuance VoCon 本身就是一個離線引擎，而且一直在運行著**，不需要任何額外設定：

| Nuance 組成部分 | 狀態 |
|---|---|
| VoCon 離線辨識引擎 | ✅ 內建於 `alpha2services` 原生代碼，完全不用網 |
| 內建英文文法（寫死） | ✅ 動作指令（如 wave the left hand）+ 一批固定 QA 問答 |
| 自訂詞彙（`initGrammar`） | ❌ 空 stub，永遠加不了字 |
| 雲端聽寫 | ☠️ 伺服器已停運，信心分數低於約 4500 的結果一律失敗，無法回退 |

**使用方式**：面板切換引擎為 Nuance（`set_language=en_us`）→ 說喚醒詞 → 說機身內建的英文指令，要求發音清晰、距離較近。

**和 iFlytek BNF 方案比較：**

| | iFlytek BNF（中文，主力） | Nuance 內建（英文） |
|---|---|---|
| 詞彙 | 1211 句自訂、可擴充 | 寫死一小撮，永遠加不了字 |
| 對答豐富度 | ✅ 完整語意庫 | 有限 QA |
| 離線 | ✅ | ✅ |

Nuance 和 iFlytek 兩個引擎並存沒衝突，屬於「額外可用」而非取代關係——英文動作指令平時已經有了，不需要靠 3000 字 iFlytek 實驗才有英文離線能力。

### 6.6 未來如需真正的英文離線句子辨識

以上所有嘗試已經證明：在不自帶第三方引擎的前提下，iFlytek 引擎做不到真正的英文離線句子識別。若日後需要（而不只是單字），唯一可行路徑是在 Open Alpha2 自身 APK 打包獨立離線引擎（例如 Vosk 或 PocketSphinx，en-us 小模型約 40MB），任何機裝上 App 即可用，不依賴機身韌體資源。此路線工程量中等，但技術上完全可控，不受官方語言限制。

---

## 7. 已知事故與根因記錄

| 事故 | 根因 | 修正 |
|---|---|---|
| 語法構建持續 23300 | 剔除乘法表時刪掉了某條 rule 收尾的 `;`，讓下一條 rule 邊界錯位，生成了 `laugh:大笑`（漏 header）和帶分號的句子 | 重新由悠聊 APK 抽取，加防禦：生成時跳過含 `:`／`;` 的 alternatives |
| 靜態文法檔案生成後仍 23300 | 二分排查揭發真正元兇：**未使用的 `!slot` 聲明** | 移除未使用 slot；順帶驗證 CRLF 換行不是問題 |
| 對話界面顯示大量技術代碼 | `grammar_result` 原始 payload 未過濾就直出界面 | 加乾淨文字過濾，套用到 `asr_result` 和 `grammar_result` |
| 修過濾後離線對白完全消失 | 網路忽通忽斷時，自動切換每次翻轉都 destroy 再重建 ASR，文法引擎被不斷拆重裝 | 加構建防重入鎖，確保同時間只有一個 initGrammar 在途 |
| WebSocket log 看得到 grammar_result，但界面不顯示 | 離線文法結果的文字藏在 `ws[].cw[].w`，不是頂層 `text` 欄位——和雲端聽寫格式不一樣 | 用即時 WebSocket 監聽器捕捉實際 payload，針對性解析出正確欄位 |
| PowerShell 生成英文詞庫後 JSON 全爛，`dance` 等舊條目消失 | PowerShell 5.1 的 `ConvertTo-Json` 將陣列包了一層 `{"value":[...]}`，matcher 解析不到 | 改用手寫 JSON 序列化，避開 PS 原生序列化器的怪癖 |
| 對照表只轉到 200/1200 個簡繁字 | 抽取用的 regex 只捉到 Java 字串用 `+` 接駁的第一截 | 改用精準錨點重新抽取全表（2712 對字） |
| 簡繁對照表齊全但轉換沒生效 | debug 函數受 PowerShell pipeline 特性影響，`Write-Output` 污染了回傳值 | 修正函數回傳邏輯 |
| APK 資源手術後 `alpha2services` 消失、面板 502 | Android 5.1 `StrictJarFile` 拒絕接受通用 `jar` 工具改寫過的 zip entry | 立即還原原裝 APK；判定此路徑不可行（詳見 6.4） |

---

## 8. 錯誤碼速查

| 錯誤碼 | 含義 | 常見成因 |
|---|---|---|
| **23300** | 本地引擎錯誤（構建失敗） | BNF 語法問題：空格、rule 串接、未使用 slot、含 `:`/`;` 的句子、rule 邊界錯位 |
| **20002** | 網路連接超時 | 雲端聽寫嘗試連線失敗（常見於「有 WiFi 但無真實數據」狀態） |
| **10114** | 自由聽寫離線不可用 | 已確認：語法約束辨識（grammar）可完全離線；自由聽寫（dictation）必須依賴雲端 |

---

## 9. 關鍵函數/組件速查表

| 功能 | 位置 | 說明 |
|---|---|---|
| 語法構建 | `robot.speech_initGrammar(bnf, listener)` | 對應 AIDL `Alpha2SpeechMainServiceUtil` |
| 語法回調 | `ISpeechGrammarInitListener.speechGrammarInitCallback(grammarId, errorCode)` | `errorCode==0` 才可啟動辨識 |
| 啟動離線辨識 | `robot.speech_startGrammar(listener)` | 需先構建成功 |
| 離線結果解析 | `extractGrammarResultText()` | 解析 `{"text":..}` 或 `ws[].cw[].w` 兩種格式 |
| 語言切換 | `speech/set_language?lang=zh_cn` / `en_us` | 對應 `speech_setRecognizedLanguage()` |
| 自動切換核心 | `MainActivity.applyConnectivityMode(boolean, String)` | 見第 4 節 |
| 網路探測 | `hasRealInternet()` | TCP connect `ubtek.openspeech.cn:80` 等，非 ping 8.8.8.8 |
| 語意匹配（中） | `IflytekSemanticMatcher.match(text)` | 精確 → 問法包含輸入 → 輸入包含問法（容錯 ASR 漏字）→ fallback |
| 語意匹配（英） | `IflytekSemanticMatcherEn.match(text)` | 同上邏輯，但只支援單字命中 |

---

## 10. 最終狀態總結（截至本文件撰寫時）

```
用戶語音
    ▼
5-mic 陣列喚醒（IVW）
    ▼
即時網路探測（TCP connect ubtek.openspeech.cn / openspeech.cn）
    │
    ├─ 有網 → iFlytek 雲端聽寫（任意中文句子）→ 語意匹配 → TTS + 動作
    │
    └─ 無網 → 本地 BNF 文法識別
              │
              ├─ 中文：1211 句自訂文法，語意庫驅動，errorCode=0 已驗證穩定
              │
              └─ 英文：24 個單字已驗證可靠（4 答案隨機）
                       + 3000 字擴展已構建成功（errorCode=0），
                         但實際離線認中率**尚未完成實測**
```

**已徹底解決：**
- ✅ 中文離線文法辨識，1211 句穩定運行，語意庫自動生成、去重、簡轉繁
- ✅ 自動在線/離線切換（真實網路探測 + 冷卻期防抖 + 喚醒即測）
- ✅ 對話界面乾淨顯示（純中英文，不混技術代碼）
- ✅ 英文單字離線指令（24 字，動作 + 多答案）

**已確認不可行：**
- ❌ 英文離線句子辨識（BNF 空格限制 + 聲學模型沒英文音素，官方文檔證實）
- ❌ APK 資源手術換模型（老版 `StrictJarFile` 拒絕接受改寫過的 zip）

**待完成：**
- ⏳ 3000 英文單字實際認中率實測（構建已成功，識別測試中斷）
- ⏳ 若需要真正英文離線對話：需自帶第三方引擎（Vosk / PocketSphinx），未開工

---

> 文檔版本：v2.0（重寫版，取代 v1.0）
> 適用固件：Alpha2Services v1.1.7.3.20 / Open Alpha2 App
> 資料來源：logcat 實測、smali 反編譯（jadx/baksmali）、悠聊原廠 APK 對照
