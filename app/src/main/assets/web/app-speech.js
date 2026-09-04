// Open Alpha2 — client logic (app-speech.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: TTS/ASR/自我打斷/service_config preset/語音三路輸入測試。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Speech / TTS (Android 內置 only) ----------------
//
// 2026-09: 機身已無 alpha2services, Nuance/iFlytek 兩個機身引擎唔存在,
// 語音 tab 得返 Android 系統 TTS。之前個三引擎按鈕組 + iFlytek 聲音揀擇已
// 移除 (見 index.html), 呢度 currentTtsEngine 恆等於 "android", setTtsEngine()
// 只做 Android 引擎/語言列載入 (開頁初始化用, 保留個名唔改, 免得 app-log.js
// 個 init call 要一齊改名)。
let currentTtsEngine = "android";

// ---------------- Speech / 對話界面 (全抄小智 tab 做法) ----------------
//
// 2026-08 新增: 對照 app-xiaozhi.js 嘅 xiaozhiAppendChatLine()/xiaozhiSendText() —
// 呢度淨係「顯示層」, 將現有嘅 asr_result (辨識結果) 同 speakTts() (TTS 講嘅嘢) 兩條
// 資料流分別渲染做 user/assistant 對話氣泡, 唔改任何底層 API。CSS class 直接沿用
// style.css 已有嘅 xiaozhi-msg / xiaozhi-msg-user / xiaozhi-msg-assistant /
// xiaozhi-msg-system (見 xiaozhiAppendChatLine 個 comment), 兩個 tab 樣式完全一致。
//
// appendSpeechChatLine() 同 xiaozhiAppendChatLine() 幾乎一模一樣 (時間戳、
// MAX_LOG_LINES 上限、scrollTop 自動捲到底), 冇抽做共用 function 嘅原因: 兩個
// tab 各自獨立操作自己嗰個 chat log DOM 元素 (speechChatLog vs xiaozhiChatLog),
// 抽出嚟反而要多傳一個 elementId 參數, 增加嘅間接層對可讀性冇乜著數。
function appendSpeechChatLine(roleClass, text) {
  const log = document.getElementById("speechChatLog");
  if (!log) return;
  const line = document.createElement("div");
  line.className = "log-line xiaozhi-msg " + roleClass;
  const bodyHtml = escapeHtml(text);
  const metaHtml = (roleClass === "xiaozhi-msg-assistant" || roleClass === "xiaozhi-msg-user")
      ? "<div class=\"xiaozhi-msg-meta\">" + nowTimeStr() + "</div>"
      : "";
  line.innerHTML = roleClass === "xiaozhi-msg-system"
      ? "<span class=\"log-time\">[" + nowTimeStr() + "]</span> " + bodyHtml
      : bodyHtml + metaHtml;
  log.appendChild(line);
  while (log.childElementCount > MAX_LOG_LINES) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

function clearSpeechChatLog() {
  const log = document.getElementById("speechChatLog");
  if (log) log.innerHTML = "";
}

/** 文字輸入框「送出」— 打字入嘅文字當做已經辨識完嘅結果 (2026-09: 之前寫
 *  「iFlytek 引擎已經辨識完」, 依家機身已無 iFlytek, 去掉個引擎名 — 純粹本地
 *  文字配對, 同任何機身引擎無關), 直接送去問法配對引擎 (中英文各 1000 條,
 *  IflytekSemanticMatcher/IflytekSemanticMatcherEn, 按輸入有冇漢字自動判斷用邊份),
 *  命中就即時做 TTS + (可能有嘅) 動作 - 唔使真係郁把口, 都可以測到「聽到 -> 講嘢/
 *  做動作」成條 pipeline。
 *
 *  同舊版 (speech/inject) 唔同: 呢條路徑唔經任何機身 AIDL 辨識, 純粹本地文字配對,
 *  所以唔會觸發 asr_result WebSocket event - user 氣泡要喺呢度發送嗰刻自己樂觀
 *  顯示 (同 speakTts() 顯示 assistant 氣泡嗰種做法一致, 唔算「送出即顯示 + server
 *  echo 又顯示多一次」, 因為呢條路徑根本冇 server echo 會返嚟)。assistant 氣泡
 *  (配對到嘅回覆句) 就用 response 嘅 answer 顯示, 配埋 type/operation 一齊, 等你
 *  睇到配對咗邊條問法、有冇觸發動作。搵唔到就顯示一句 system 提示, 唔扮有回應。 */
function sendSpeechChatText() {
  const input = document.getElementById("speechChatTextInput");
  const text = input ? (input.value || "").trim() : "";
  if (!text) return;
  const btn = document.getElementById("speechChatSendBtn");
  if (btn) btn.disabled = true;
  appendSpeechChatLine("xiaozhi-msg-user", text);
  return Alpha2Api.speechIflytekSimulate( { text: text }).then(function (res) {
    if (input) input.value = "";
    if (!res || !res.ok) {
      appendSpeechChatLine("xiaozhi-msg-system",
          t("speech_chat_simulate_error_prefix") + (res && res.error ? res.error : t("asr_reset_failed_unknown")));
      return;
    }
    if (!res.matched) {
      appendSpeechChatLine("xiaozhi-msg-system", t("speech_chat_simulate_no_match"));
      return;
    }
    if (res.answer) {
      appendSpeechChatLine("xiaozhi-msg-assistant", res.answer);
    }
    const detail = "[" + res.type + (res.operation ? " " + res.operation : "") + "]"
        + (res.actionId ? " " + t("speech_chat_simulate_action_prefix") + res.actionId : "");
    appendSpeechChatLine("xiaozhi-msg-system", detail);
  }).finally(function () {
    if (btn) btn.disabled = false;
  });
}

function setTtsEngine(engine) {
  // 2026-09: 得返 "android" 一個引擎, 參數照收 (開頁 init 會傳 "android" 入嚟),
  // 傳其他值都當 android 處理。直接載入 Android 引擎/語言清單。
  currentTtsEngine = "android";
  loadAndroidTtsEngines();
  loadAndroidTtsLanguages();
}

/** 揀 Android TTS 引擎 (speech/tts engine=android 分支實際講嘢用嗰個系統
 *  TTS, 唔係 Nuance/iFlytek) - 由 speech/set_tts_engine 切換, 呢個 switch
 *  本身係 async (後端拆舊起新一個 TextToSpeech instance), 所以完成之後短暫
 *  delay 先重新讀返 speech/cur_tts_engine 確認, 對照後端 MainActivity 個
 *  initAndroidTts() javadoc 講嘅「唔即刻 ready」。切換咗引擎, 舊引擎個語言
 *  清單已經唔啱用, 要重新載入。 */
function setAndroidTtsEngine() {
  const select = document.getElementById("ttsAndroidEngineSelect");
  const enginePkg = select ? select.value : "";
  if (!enginePkg) return;
  // 2026-09: 轉咗引擎, 舊語言選擇未必啱用, 前後端一齊重置 (後端 pref 都清，
  // 等對話管線 TTS 跌返自動判斷)。
  currentAndroidTtsLang = "";
  Alpha2Api.speechSetTtsLang( { lang: "" });
  Alpha2Api.speechSetTtsEngine( { engine: enginePkg }).then(function () {
    setTimeout(function () {
      loadCurAndroidTtsEngine();
      loadAndroidTtsLanguages();
    }, 800);
  });
}

/** 載入機身裝咗嘅全部 Android TTS 引擎, 填入 <select>, 再讀返而家實際揀緊
 *  邊個, 揀返佢做已選項。 */
function loadAndroidTtsEngines() {
  Alpha2Api.speechTtsEngines().then(function (res) {
    const select = document.getElementById("ttsAndroidEngineSelect");
    if (!select || !res || !res.ok || !res.engines) return;
    select.innerHTML = "";
    res.engines.forEach(function (pkg) {
      const opt = document.createElement("option");
      opt.value = pkg;
      opt.textContent = pkg;
      select.appendChild(opt);
    });
    loadCurAndroidTtsEngine();
  });
}

function loadCurAndroidTtsEngine() {
  Alpha2Api.speechCurTtsEngine().then(function (res) {
    const select = document.getElementById("ttsAndroidEngineSelect");
    if (!select || !res || !res.ok || !res.engine) return;
    select.value = res.engine;
  });
}

// 而家揀緊嘅 Android TTS 語言 BCP-47 tag - 空字串代表沿用 engine 而家已經
// 生效嗰個語言, 唔強行切換 (見後端 speech/tts 個 android 分支 comment)。
// speakTts() 會帶埋呢個值；對話管線 (後端 speakAndroidTts) 讀同一個後端 pref，
// 所以呢度一揀，對話 TTS 即時跟 (見 setAndroidTtsLang)。
let currentAndroidTtsLang = "";

/** 載入而家揀緊嗰個 Android TTS 引擎識嘅全部語言 (server 端經
 *  TextToSpeech.getVoices() 攞, 見 MainActivity#listAndroidTtsLanguages()
 *  javadoc), displayName 已經係 server 揀好 ui_lang 嗰種語言嘅顯示名, 前端
 *  唔使自己維護 tag->name 對照表。 */
function loadAndroidTtsLanguages() {
  Alpha2Api.speechTtsLanguages( { ui_lang: uiLang }).then(function (res) {
    const select = document.getElementById("ttsAndroidLangSelect");
    if (!select || !res || !res.ok || !res.languages) return;
    select.innerHTML = "";
    // 「沿用引擎目前語言」呢個選項排第一, value 留空 - 對應後端 lang 參數
    // 留空/null 嗰個分支 (唔強行 setLanguage())。
    const keepOpt = document.createElement("option");
    keepOpt.value = "";
    keepOpt.textContent = t("tts_android_lang_keep_option");
    select.appendChild(keepOpt);
    res.languages.forEach(function (lang) {
      const opt = document.createElement("option");
      opt.value = lang.tag;
      opt.textContent = lang.name;
      select.appendChild(opt);
    });
    select.value = currentAndroidTtsLang;
    // 後端 pref 可能有上次記低嘅選擇 (重啟後前端 var 會丟失)，sync 返。
    Alpha2Api.speechCurTtsLang().then(function (cur) {
      if (cur && cur.ok && cur.lang !== undefined && cur.lang !== currentAndroidTtsLang) {
        currentAndroidTtsLang = cur.lang || "";
        select.value = currentAndroidTtsLang;
      }
    });
  });
}

function setAndroidTtsLang() {
  const select = document.getElementById("ttsAndroidLangSelect");
  currentAndroidTtsLang = select ? select.value : "";
  // 同步寫返後端 pref —— 對話管線 TTS 即時跟呢個選擇 (見 MainActivity.speakAndroidTts)。
  Alpha2Api.speechSetTtsLang( { lang: currentAndroidTtsLang });
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) return alert(t("speech_test_enter_text_alert"));
  // 2026-09: 恆行 Android TTS。lang 有揀先帶 (空字串=沿用引擎目前語言)。
  const params = { text: text, engine: "android" };
  // 空字串=沿用引擎目前語言 (見後端 speech/tts 個 android 分支 comment)。
  if (currentAndroidTtsLang) {
    params.lang = currentAndroidTtsLang;
  }
  // 對話界面: 機械人「講嘢」即刻顯示做 assistant 氣泡 — 呢度同小智唔同嘅係
  // TTS request 本身冇對應嘅非同步 event 會將講咗嘅文字送返嚟 (唔似 asr_result
  // 咁), 所以直接喺呢度用發送嗰刻嘅文字 append, 唔使等 server 回應。
  appendSpeechChatLine("xiaozhi-msg-assistant", text);
  // 播新嘢之前先停低舊嗰句, 唔係就兩句 TTS 可能撞埋一齊播 (講到一半嗰句仲未
  // 完, 個新 request 已經開始講, 聽落會疊聲/含糊)。stopTts() 失敗都照樣繼續
  // 播放新嘅 (例如冧巴一次冇嘢正播緊, stop 本身可能會 error/no-op, 唔應該
  // 因為咁就唔畀用家繼續講嘢)。
  return stopTts().catch(function () {}).then(function () {
    return Alpha2Api.speechTts( params);
  });
}

function stopTts() {
  return Alpha2Api.speechStop();
}

// 2026-09 移除: MIC 控制成組 (updateMicStateUi/setMic/setMicKeepHeld) - 卡已
// 拎走 (見 index.html)。Backend speech/set_mic* endpoint 保留唔郁。
// 2026-09 移除: ASR 引擎切換 (switchAsrEngine + speechReadyForAsr) - 機身已無
// iFlytek/Nuance, speech/set_language 只會回 NOT_INIT, 成張 ASR 卡已拎走
// (見 index.html)。對應 backend endpoint 本身保留 (其他 caller 照舊收到誠實
// 錯誤, 唔靜默改語義)。

// 2026-08 清理: 原本呢度有 startAsr()/stopAsr()/resetSpeech() 三個 function,
// 交叉核對成個 index.html 搵唔到任何按鈕/入口綁住呢三個 function, 亦冇任何
// 其他 JS 檔案 call 過佢哋 - 純粹係之前語音 tab 改版 (UI 整合做四張卡) 拎走
// 咗對應按鈕之後, function 本身冇跟手一齊刪嘅殘留死 code, 已刪走。對應嘅
// backend endpoint (speech/start_asr, speech/reset) 已經喺 2026-09 一齊移除
// (死 binder), 前端早已無入口再 call 佢哋。

// 2026-09 移除: 離線對話設定成組 (setServiceConfigPreset/rebootRobot) - 卡已
// 拎走 (見 index.html)。注意 app-accel.js advancedRebootRobot() 係另一粒獨立
// 掣 (UUID 卡用), 唔受影響。service_config/get|set 已刪除，reboot 保留。

// 2026-09 移除: 離線文法辨識成組 function (grammarLoadDefault/grammarInit/
// grammarStart/grammarStop/setOfflineAutoSwitch/refreshOfflineAutoSwitch) -
// 卡已拎走 (見 index.html), 背後 iFlytek 本地引擎唔存在；backend
// init/start/stop_grammar endpoint 一併刪除，get_default_grammar 照讀本地 asset。
/** 喚醒轉頭開關讀寫（後端 speech/wakeup_track/get|set，開頁同步一次）。 */
function loadWakeupTrack() {
  Alpha2Api.speechWakeupTrackGet().then(function (res) {
    const toggle = document.getElementById("wakeupTrackToggle");
    const state = document.getElementById("wakeupTrackState");
    if (toggle) toggle.checked = !!(res && res.ok && res.enabled);
    if (state) state.textContent = (res && res.ok && res.running) ? "RUN" : "";
  });
}

function setWakeupTrack(checked) {
  Alpha2Api.speechWakeupTrackSet({ enabled: checked ? "true" : "false" }).then(function (res) {
    const toggle = document.getElementById("wakeupTrackToggle");
    const state = document.getElementById("wakeupTrackState");
    if (toggle) toggle.checked = !!(res && res.ok && res.enabled);
    if (state) state.textContent = (res && res.ok && res.running) ? "RUN" : "";
  });
}
