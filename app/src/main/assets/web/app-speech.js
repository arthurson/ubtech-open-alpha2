// Open Alpha2 — client logic (app-speech.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: TTS/ASR/自我打斷/service_config preset/語音三路輸入測試。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Speech / TTS ----------------
//
// The robot runs two distinct on-device services - com.ubtechinc.services.
// NuanceSpeeckServices and .IflytekSpeeckServices (see Alpha2Intent.java in the SDK) -
// both genuinely functional. Alpha2RobotApi itself has no separate "engine" parameter
// though: engine selection happens implicitly through which language code you send
// (en_us / zh_cn). Nuance only has an English grammar/voice set on this firmware;
// iFlytek covers both. So "engine" here is a UI-level grouping that filters which
// language options make sense, not a value sent to the robot on its own - only the
// Voice selection only applies to iFlytek's named voices - Nuance and Android's
// system TTS each use their own single default voice with no picker.
//
// 2026-08 更新: 引擎/聲音由 <select> 改做按鈕組 (同 switchAsrEngine 嗰邊嘅
// .lang-toggle 樣式一致) —— 3 個引擎鍵常駐, 聲音嗰 5 個鍵獨立一行, 淨係
// currentTtsEngine === "iflytek" 先顯示 (揀 Nuance/Android 預設嗰行會完全
// 消失, 唔淨係 disable)。用返 currentTtsEngine/currentTtsVoice 呢兩個模組
// 層變數記住目前揀緊乜, 唔再靠 <select>.value 讀。
let currentTtsEngine = "nuance"; // 預設同舊 <select> 個第一個 option 一致
let currentTtsVoice = "";

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

/** 文字輸入框「送出」— 2026-08 改用 speech/iflytek_simulate: 打字入嘅文字當做
 *  「iFlytek 引擎已經辨識完嘅結果」直接送去 1000 條問法配對引擎 (中英文各 1000 條,
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
  return api("speech/iflytek_simulate", { text: text }).then(function (res) {
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
  currentTtsEngine = engine;
  document.getElementById("ttsEngineNuanceBtn").classList.toggle("active", engine === "nuance");
  document.getElementById("ttsEngineIflytekBtn").classList.toggle("active", engine === "iflytek");
  document.getElementById("ttsEngineAndroidBtn").classList.toggle("active", engine === "android");

  const voiceRow = document.getElementById("ttsVoiceRow");
  if (engine === "iflytek") {
    voiceRow.style.display = "";
  } else {
    voiceRow.style.display = "none";
    currentTtsVoice = "";
    setTtsVoice("");
  }

  // Android TTS 引擎揀擇/語言揀擇 - 淨係 engine === "android" 先顯示同載入
  // 清單, 對照上面 voiceRow (iflytek 專屬) 嘅做法。
  const androidEngineRow = document.getElementById("ttsAndroidEngineRow");
  const androidLangRow = document.getElementById("ttsAndroidLangRow");
  if (engine === "android") {
    androidEngineRow.style.display = "";
    androidLangRow.style.display = "";
    loadAndroidTtsEngines();
    loadAndroidTtsLanguages();
  } else {
    androidEngineRow.style.display = "none";
    androidLangRow.style.display = "none";
    currentAndroidTtsLang = "";
  }
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
  api("speech/set_tts_engine", { engine: enginePkg }).then(function () {
    setTimeout(function () {
      loadCurAndroidTtsEngine();
      loadAndroidTtsLanguages();
    }, 800);
  });
}

/** 載入機身裝咗嘅全部 Android TTS 引擎, 填入 <select>, 再讀返而家實際揀緊
 *  邊個, 揀返佢做已選項。 */
function loadAndroidTtsEngines() {
  api("speech/tts_engines").then(function (res) {
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
  api("speech/cur_tts_engine").then(function (res) {
    const select = document.getElementById("ttsAndroidEngineSelect");
    if (!select || !res || !res.ok || !res.engine) return;
    select.value = res.engine;
  });
}

// 而家揀緊嘅 Android TTS 語言 BCP-47 tag - 空字串代表沿用 engine 而家已經
// 生效嗰個語言, 唔強行切換 (見後端 speech/tts 個 android 分支 comment)。
// speakTts() 揀 engine=android 嗰陣會帶埋呢個值。
let currentAndroidTtsLang = "";

/** 載入而家揀緊嗰個 Android TTS 引擎識嘅全部語言 (server 端經
 *  TextToSpeech.getVoices() 攞, 見 MainActivity#listAndroidTtsLanguages()
 *  javadoc), displayName 已經係 server 揀好 ui_lang 嗰種語言嘅顯示名, 前端
 *  唔使自己維護 tag->name 對照表。 */
function loadAndroidTtsLanguages() {
  api("speech/tts_languages", { ui_lang: uiLang }).then(function (res) {
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
  });
}

function setAndroidTtsLang() {
  const select = document.getElementById("ttsAndroidLangSelect");
  currentAndroidTtsLang = select ? select.value : "";
}

function setTtsVoice(voice) {
  currentTtsVoice = voice;
  document.getElementById("ttsVoiceDefaultBtn").classList.toggle("active", voice === "");
  document.getElementById("ttsVoiceCatherineBtn").classList.toggle("active", voice === "catherine");
  document.getElementById("ttsVoiceJohnBtn").classList.toggle("active", voice === "john");
  document.getElementById("ttsVoiceXiaofengBtn").classList.toggle("active", voice === "xiaofeng");
  document.getElementById("ttsVoiceXiaoyanBtn").classList.toggle("active", voice === "xiaoyan");
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) return alert(t("speech_test_enter_text_alert"));
  const params = { text: text, engine: currentTtsEngine };
  if (currentTtsEngine === "iflytek" && currentTtsVoice) {
    params.voice = currentTtsVoice;
  }
  // Android TTS 語言揀擇 - 空字串代表沿用引擎目前語言, 唔帶 lang 參數
  // (見後端 speech/tts 個 android 分支 comment)。
  if (currentTtsEngine === "android" && currentAndroidTtsLang) {
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
    return api("speech/tts", params);
  });
}

function stopTts() {
  return api("speech/stop");
}

// 麥克風擁有權指示燈 + 「持續搶 mic」card - 由原本 TTS card 入面嗰兩粒掣同
// micStateHint 抽出嚟做獨立 section (見 index.html), 加返 mic_state WebSocket
// event 令狀態可以即時反映, 唔淨係靠呢度手動 call 完 api() 先更新一次。
//
// updateMicStateUi() 同時處理兩個 UI 更新入口: 1) 用戶自己撳掣 (setMic()/
// setMicKeepHeld() 嘅 .then()), 2) server 端 mic_state event 推送過嚟 (見
// app-log.js 嘅 appendLog()) - 兩者都經過呢個 function, 保證指示燈、hint
// 文字、keep-held checkbox 三者永遠同步, 唔會因為淨係更新其中一個入口就走樣。
function updateMicStateUi(held, keepHeld) {
  const dot = document.getElementById("micStateDot");
  const label = document.getElementById("micStateLabel");
  const keepCheckbox = document.getElementById("micKeepHeld");
  if (dot) {
    dot.classList.toggle("mic-state-dot-on", !!held);
    dot.classList.toggle("mic-state-dot-off", !held);
  }
  if (label) {
    label.textContent = t(held ? "mic_state_on" : "mic_state_off");
  }
  if (keepCheckbox) {
    keepCheckbox.checked = !!keepHeld;
  }
}

function setMic(wake) {
  return api("speech/set_mic", { wake: String(wake) }).then(function (res) {
    updateMicStateUi(res.held, res.keepHeld);
    return res;
  });
}

function setMicKeepHeld(keep) {
  return api("speech/set_mic_keep_held", { keep: String(keep) }).then(function (res) {
    updateMicStateUi(res.held, res.keepHeld);
    return res;
  });
}

// ---------------- Speech / ASR (manual) ----------------
//
// 2026-08-22 更新: logcat 證實 setRecognizedLanguage() 本身就觸發引擎切換:
// - setRecognizedLanguage("en_us") → SpeechManager 切換語音引擎到 nuance
// - setRecognizedLanguage("zh_cn") 或空值 → SpeechManager 切換語音引擎到 iflytek
// 呢個方法唔需要 unbind/rebind, 所以唔會破壞 TTS session。曾經用過另一個
// (已經拎走嘅) speech/set_asr_engine endpoint 做真正 unbind/rebind 式切換,
// 但已經確認會整死 TTS session (要重開機先返到正常), 而家統一改用
// speech/set_language API 觸發切換, 效果同 logcat 完全一致。
//
// Results don't come back from this call itself: they arrive later, asynchronously,
// as an "asr_result" WebSocket event (published from MainActivity's onServerCallBack)
// and are shown by appendLog() below.
//
// start_asr (speech_startSpeechNoWakeup) was added to trigger recognition without
// waiting for the mic-array hardware's own wake word - see logcat_2026-07-30_07-53-50.txt
// for why set_mic(true) alone couldn't do that. But logcat_2026-07-02_13-38-32.txt (a
// later on-robot test of start_asr itself, done against the Nuance binding) shows it only
// moves the speech engine into SPEECH_STATE_WAKEUP internally (SpeechManager "what:3",
// IflytekWakeUp5mic.startRecording) - actual recognition (IflyteckASR5mic
// "startSpeechASR type:0", "Listening...") still didn't begin until a hardware "MicArray
// wakeup" fired independently, ~20s later. So start_asr does put the robot in a more
// wake-word-receptive state than doing nothing, but it is not the direct trigger this
// button's label implies - hence the phrasing below. This was tested against Nuance;
// whether iFlytek's own wake-word path behaves the same way is still unconfirmed.
let speechReadyForAsr = true; // set false while switchAsrEngine() 嘅切換進行緊

function switchAsrEngine(engine) {
  const label = engine === "iflytek" ? "iFlytek" : "Nuance";
  const lang = engine === "iflytek" ? "zh_cn" : "en_us";
  document.getElementById("asrSwitchZhBtn").classList.toggle("active", engine === "iflytek");
  document.getElementById("asrSwitchEnBtn").classList.toggle("active", engine === "nuance");
  document.getElementById("asrCurrentEngineHint").textContent = t("asr_current_engine_switching").replace("{label}", label);
  appendSpeechChatLine("xiaozhi-msg-system", t("asr_switching_to").replace("{label}", label));
  // 用 setRecognizedLanguage 觸發引擎切換, 同 logcat 觀察到嘅行為一致:
  // - "en_us" → 切換到 Nuance
  // - "zh_cn" → 切換到 iFlytek
  // 呢個方法唔需要 unbind/rebind, TTS session 繼續正常運作。
  // 同時切換 TTS 引擎, 令 ASR 同 TTS 同步:
  // - nuance asr = nuance tts
  // - iflytek asr = iflytek tts
  // 切換前端 TTS 引擎狀態
  setTtsEngine(engine);
  // 通知後端真正切換 TTS 引擎 (唔係淨係改前端變量) - 呢個 endpoint 喺
  // xiaozhi/ namespace 底下 (見 MainActivity#handleXiaozhiApi 嘅
  // "tts_config/set" case), 唔係 alpha2/ - 之前錯用咗 api() (會打去
  // alpha2/speech/tts_config/set, 個 404 喺 logcat_2026-07-27 見到), 要用
  // xiaozhiApi() 先打得中真正個 endpoint。
  xiaozhiApi("tts_config/set", { engine: engine });
  return api("speech/set_language", { lang: lang }).then(function () {
    speechReadyForAsr = true;
    document.getElementById("asrCurrentEngineHint").textContent = t("asr_current_engine_is").replace("{label}", label);
    appendSpeechChatLine("xiaozhi-msg-system", t("asr_engine_ready_hint"));
    // 播放一句對白確認切換成功, 用對應嘅 TTS 引擎講
    var confirmText = engine === "iflytek"
        ? "我們一同玩吧"
        : "let's play together";
    api("speech/tts", { text: confirmText, engine: engine });
  }).catch(function (err) {
    document.getElementById("asrCurrentEngineHint").textContent = t("asr_current_engine_switch_failed");
    appendSpeechChatLine("xiaozhi-msg-system", t("asr_switch_failed_prefix") + (err && err.message ? err.message : err));
  });
}

// 2026-08 清理: 原本呢度有 startAsr()/stopAsr()/resetSpeech() 三個 function,
// 交叉核對成個 index.html 搵唔到任何按鈕/入口綁住呢三個 function, 亦冇任何
// 其他 JS 檔案 call 過佢哋 - 純粹係之前語音 tab 改版 (UI 整合做四張卡) 拎走
// 咗對應按鈕之後, function 本身冇跟手一齊刪嘅殘留死 code, 已刪走。對應嘅
// backend endpoint (speech/start_asr, speech/reset) 本身冇改, setMic(false)
// (stopAsr 舊實現) 依然可以直接用返下面嘅 setMic() 掣。

// ---------------- Service config (/sdcard/actions/service_config.json) -------------
//
// 呢個檔案控制機身開機時嘅 wake word / ASR 語言 / 預設對話 app。實測確認：改咗呢個
// 檔案、重開機之後，wake word 真係會跟住轉。中文／英文兩個 preset 都係機身出廠
// 內置嘅原裝 default config，一字不改。寫入唔會自動重開機，要用家自己撳「立即
// 重開機」，避免手滑撳咗個 preset 掣就即刻累機身重開。
//
// 2026-08 更新: 撳「中文/英文」即寫，唔再彈 confirm —— 呢個掣本身淨係寫入
// config 檔, 唔會即刻令機身重開機 (要另外撳「立即重開機」先真正生效/累機),
// 屬於低風險、可以隨時再撳另一個 preset 覆蓋返嘅操作, 冇必要加多一重確認。
function setServiceConfigPreset(preset) {
  document.getElementById("serviceConfigResult").textContent = t("service_config_writing");
  return api("service_config/set", { preset: preset }).then(function (res) {
    const el = document.getElementById("serviceConfigResult");
    el.textContent = res && res.ok
      ? t("service_config_write_ok")
      : t("service_config_write_failed_prefix") + (res && res.error ? res.error : t("asr_reset_failed_unknown"));
  });
}

function rebootRobot() {
  if (!confirm(t("service_config_reboot_confirm"))) return Promise.resolve();
  document.getElementById("serviceConfigResult").textContent = t("service_config_rebooting");
  return api("service_config/reboot").then(function (res) {
    if (res && res.ok) {
      document.getElementById("serviceConfigResult").textContent = t("service_config_reboot_ok");
    } else {
      document.getElementById("serviceConfigResult").textContent =
        t("service_config_reboot_failed_prefix") + (res && res.error ? res.error : t("asr_reset_failed_unknown")) + t("service_config_reboot_failed_suffix");
    }
  });
}

// ---------------- 離線文法辨識 (iFlytek local BNF grammar) ----------------
//
// 2026-08 新增。三步流程:
//   (1) 先撳 ASR card 嘅「iFlytek」切引擎 (speech/set_language zh_cn, 令
//       currentAsrTarget() 綁去 iFlytek 嗰邊);
//   (2) 「初始化文法」— 將 textarea 入面嘅 BNF (#BNF+IAMVERSION 格式) 送俾
//       speech/init_grammar, 機身用 engine_type=local + APK 自帶嘅
//       assets/asr/common.jet 行 buildGrammar("bnf",...), 構建結果經
//       grammar_init WebSocket event 返嚟;
//   (3) 「開始辨識」— speech/start_grammar, mix 模式啟動, 離線自動行本地文法。
//       講中嘅句子經 grammar_result event 返到嚟, 觸發語意配對 + TTS + 動作,
//       全程唔使上網。「停止」還原返聽寫模式 (asr_result 路徑重新生效)。
function grammarLoadDefault() {
  const out = document.getElementById("grammarStatusOut");
  out.textContent = t("offline_grammar_loading");
  return api("speech/get_default_grammar").then(function (res) {
    if (res && res.ok && res.bnf) {
      document.getElementById("grammarBnf").value = res.bnf;
      out.textContent = "✓";
    } else {
      out.textContent = t("offline_grammar_init_fail") + (res && res.error ? res.error : "?");
    }
  });
}

function grammarInit() {
  const out = document.getElementById("grammarStatusOut");
  const bnf = document.getElementById("grammarBnf").value;
  api("speech/init_grammar", { bnf: bnf }).then(function (res) {
    out.textContent = res && res.ok
      ? t("offline_grammar_init_ok")
      : t("offline_grammar_init_fail") + (res && res.error ? res.error : "?");
  });
}

function grammarStart() {
  const out = document.getElementById("grammarStatusOut");
  return api("speech/start_grammar").then(function (res) {
    out.textContent = res && res.ok ? t("offline_grammar_start_ok")
      : t("offline_grammar_init_fail") + (res && res.error ? res.error : "?");
  });
}

function grammarStop() {
  const out = document.getElementById("grammarStatusOut");
  return api("speech/stop_grammar").then(function (res) {
    out.textContent = t("offline_grammar_stop_ok");
  });
}

// 「自動跟網絡切換」開關 - 冇網絡自動入離線文法模式, 有網絡自動退返雲端
// 聽寫。狀態存喺 server 側 SharedPreferences, 重啟 App 都記得。頁面載入嗰陣
// 由 speech/offline_auto_switch (冇參數 = 查詢) 攞返現狀同步個 checkbox。
function setOfflineAutoSwitch(on) {
  const out = document.getElementById("grammarStatusOut");
  return api("speech/offline_auto_switch", { on: String(on) }).then(function (res) {
    if (res && res.ok) {
      out.textContent = res.auto ? "🌐 auto-switch ON" : "📴 auto-switch OFF";
    }
  });
}

function refreshOfflineAutoSwitch() {
  api("speech/offline_auto_switch").then(function (res) {
    if (res && res.ok) {
      const cb = document.getElementById("offlineAutoSwitch");
      if (cb) cb.checked = !!res.auto;
    }
  });
}