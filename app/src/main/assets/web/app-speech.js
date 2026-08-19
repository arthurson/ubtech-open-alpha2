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
// 2026-08 更新: 之前呢度淨係 call speech/set_language, 呢個對 active engine
// 嚟講只係 advisory hint, 唔會真正切去 iFlytek —— 之前得出「iFlytek 唔係
// active engine」嘅結論, 其實係喺冇用中文/iFlytek 專屬 grammar 測試過嘅情況
// 下做嘅 (SDK 原作者唔識中文, 冇試過用中文觸發), 唔代表 iFlytek 呢條路徑本身
// 用唔到。而家改用新加嘅 speech/set_asr_engine, 佢會真正 unbind 現有連接、
// 用指定 engine (ALPHA_NUANCE_SPEECH_MAIN_SERVER 或
// ALPHA_IFLYTEK_SPEECH_MAIN_SERVER) 重新 bind, 而唔係淨係傳個語言提示。
// 呢個 rebind 係 async (等 onServiceConnected), 所以撳「開始聆聽」之前要等
// speech_ready event 返嚟先，UI 度用 speechReadyForAsr 呢個 flag 擋住。
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
let speechReadyForAsr = true; // set false while switchAsrEngine() 嘅 rebind 進行緊
let currentAsrEngine = null; // "iflytek" | "nuance" | null (未撳過任何一個掣之前)

function switchAsrEngine(engine) {
  speechReadyForAsr = false;
  const label = engine === "iflytek" ? "iFlytek" : "Nuance";
  document.getElementById("asrOut").textContent = t("asr_switching_to").replace("{label}", label);
  document.getElementById("asrOut").dataset.state = "switching";
  document.getElementById("asrCurrentEngineHint").textContent = t("asr_current_engine_switching").replace("{label}", label);
  document.getElementById("asrSwitchZhBtn").classList.toggle("active", engine === "iflytek");
  document.getElementById("asrSwitchEnBtn").classList.toggle("active", engine === "nuance");
  return api("speech/set_asr_engine", { engine: engine }).then(function () {
    currentAsrEngine = engine;
  }).catch(function (err) {
    speechReadyForAsr = true; // 綁定請求本身都失敗, 唔使等 speech_ready, 解返個 lock
    document.getElementById("asrOut").textContent = t("asr_switch_failed_prefix") + (err && err.message ? err.message : err);
    document.getElementById("asrCurrentEngineHint").textContent = t("asr_current_engine_switch_failed");
  });
}

function startAsr() {
  if (!currentAsrEngine) {
    document.getElementById("asrOut").textContent = t("asr_pick_engine_first");
    return Promise.resolve();
  }
  if (!speechReadyForAsr) {
    document.getElementById("asrOut").textContent = t("asr_rebinding_wait");
    return Promise.resolve();
  }
  const lang = currentAsrEngine === "iflytek" ? "zh_cn" : "en_us";
  document.getElementById("asrOut").textContent = t("asr_preparing_listen");
  return api("speech/set_language", { lang: lang }).then(function () {
    return api("speech/start_asr", {});
  });
}

function stopAsr() {
  document.getElementById("asrOut").textContent = t("asr_stopped");
  return setMic(false);
}

// 2026-08 新增: 實驗性「重置語音」——見 MainActivity.java speech/reset 個 comment。
// 撳咗上面「中文/英文」切換引擎之後, 機身系統進程嘅 TTS session 有機會啞咗
// (call speech/tts 都回 200, 但完全冇聲), 一直要重開機先返到正常。呢個掣試下
// call stopSpeechAndEnterIdleMode() 睇吓叫唔叫得返個 session, 唔使重開機。
// 未證實一定有效, 純粹試驗。
function resetSpeech() {
  document.getElementById("asrResetHint").textContent = t("asr_resetting");
  return api("speech/reset", {}).then(function (res) {
    document.getElementById("asrResetHint").textContent = res && res.ok
      ? t("asr_reset_sent_ok")
      : t("asr_reset_failed_prefix") + (res && res.error ? res.error : t("asr_reset_failed_unknown"));
  }).catch(function (err) {
    document.getElementById("asrResetHint").textContent = t("asr_reset_failed_prefix") + (err && err.message ? err.message : err);
  });
}

function setSelfInterrupt() {
  const on = document.getElementById("selfInterrupt").checked;
  return api("speech/self_interrupt", { on: String(on) });
}

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

// ---------------- Speech / NLU (text understanding, no mic involved) ----------------
//
// speech_understandText() sends the string straight to the robot's semantic engine,
// bypassing ASR entirely. Like ASR, the result doesn't come back in this call's own
// response - it arrives later as a "text_understand" WebSocket event.
// ---------------- Speech input three-way test (same text, three AIDL paths) ---------
//
// Fires the same text at speech/inject, speech/init_grammar (which we auto-chain into
// speech/start_grammar once init reports back), and speech/understand simultaneously,
// so you can compare which of the three actually produces a result for identical input.
// - inject: no dedicated output tile here - result (if any) shows up on the ASR card's
//   existing "辨識結果"/"意圖分類" tiles, same as real speech.
// - grammar: init result shows in grammarInitOut; if init succeeds we chain into
//   start_grammar automatically, whose result/error lands in grammarResultOut via the
//   "grammar_result" WebSocket event.
// - understand (NLU): result/error shows in nluOut via the "text_understand" event.
//
// 2026-08 更新: 反編譯 Alpha2Services-v1.1.7.3.20 證實咗 grammar 呢組 API
// (initSpeechGrammar/startSpeechGrammar) 喺 Nuance binding 之下係完全未實作
// 嘅空 stub (method body 得一句 return-void), 淨係喺 iFlytek binding 之下先
// 有真身實作 (會建立 com.iflytek.cloud.SpeechRecognizer)。backend 喺
// speech/init_grammar 有 guard, 如果而家未切去 iFlytek 會直接回傳 error。
// inject/understand 呢兩條路徑同 engine 揀邊個冇關 (佢哋唔靠 grammar 呢組
// API), 照舊即刻送出唔使等。
//
// 2026-08 取消自動切換: 之前呢度如果而家仲係 Nuance, 會自動幫手切去
// iFlytek 先至真正發送 grammar 測試 —— 依家取消返呢個行為, 唔會再喺用家
// 冇要求嘅情況下自己切 engine。而家如果而家唔係 iFlytek, 直接送出去撞
// backend 個 guard, 將佢個 error message 原封不動顯示喺 grammarInitOut,
// 由用家自己決定要唔要手動 speech/set_asr_engine?engine=iflytek。
function setSpeechTestText(text) {
  document.getElementById("speechTestText").value = text;
  return testAllSpeechInputs();
}

function testAllSpeechInputs() {
  const text = document.getElementById("speechTestText").value.trim();
  if (!text) return alert(t("speech_test_enter_text_alert"));

  document.getElementById("injectOut").textContent = t("speech_test_inject_sent");
  document.getElementById("grammarInitOut").textContent = t("speech_test_grammar_init");
  document.getElementById("grammarResultOut").textContent = "-";
  document.getElementById("nluOut").textContent = t("speech_test_nlu_analyzing");

  api("speech/inject", { text: text });
  api("speech/understand", { text: text });
  runGrammarTest(text);
}

function runGrammarTest(text) {
  return api("speech/init_grammar", { grammar: text }).then(function (res) {
    if (res && res.ok) {
      // Chain into start_grammar so the grammar path gets a real end-to-end
      // attempt, not just init. If init itself failed there is nothing to start.
      api("speech/start_grammar");
    } else {
      document.getElementById("grammarInitOut").textContent =
        (res && res.error) ? res.error : t("speech_test_grammar_init_failed");
    }
  });
}

