// Open Alpha2 — client logic (app-speech.js)
// 內容: TTS/ASR/自我打斷/service_config preset/語音三路輸入測試。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Speech / TTS (Android 內置 only) ----------------
//
// 語音 tab 只剩下 Android 系統 TTS。currentTtsEngine 恆等於 "android", setTtsEngine()
// 只做 Android 引擎/語言列載入 (開啟頁面初始化用, 保留個名不改, 免得 app-log.js
// 個 init call 要一齊改名)。
let currentTtsEngine = "android";

// ---------------- Speech / 對話界面 (全抄小智 tab 做法) ----------------
//
// 對照 app-xiaozhi.js 的 xiaozhiAppendChatLine()/xiaozhiSendText() —
// 這裡僅「顯示層」, 將現有的 asr_result (辨識結果) 同 speakTts() (TTS 講的東西) 兩條
// 資料流分別渲染做 user/assistant 對話氣泡, 不改任何底層 API。CSS class 直接沿用
// style.css 已有的 xiaozhi-msg / xiaozhi-msg-user / xiaozhi-msg-assistant /
// xiaozhi-msg-system (見 xiaozhiAppendChatLine 個 comment), 兩個 tab 樣式完全一致。
//
// appendSpeechChatLine() 同 xiaozhiAppendChatLine() 幾乎一模一樣 (時間戳、
// MAX_LOG_LINES 上限、scrollTop 自動捲到底), 沒有抽做共用 function 的原因: 兩個
// tab 各自獨立操作自己那個 chat log DOM 元素 (speechChatLog vs xiaozhiChatLog),
// 抽出來反而要多傳一個 elementId 參數, 增加的間接層對可讀性沒有什麼好處。
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

/** 文字輸入框「送出」— 打字入的文字當做已經辨識完的結果, 直接送去問法配對引擎
 *  (中英文各 1000 條, SemanticMatcherZh/SemanticMatcherEn, 按輸入有沒有漢字自動
 *  判斷用邊份), 命中就即時做 TTS + (可能有的) 動作 - 不用真正動把口, 都可以測到
 *  「聽到 -> 說話/做動作」整個 pipeline。
 *
 *  這條路徑不經任何機身 AIDL 辨識, 純粹本地文字配對,
 *  所以不會觸發 asr_result WebSocket event - user 氣泡要在這裡發送那刻自己樂觀
 *  顯示 (同 speakTts() 顯示 assistant 氣泡那種做法一致, 不算「送出即顯示 + server
 *  echo 又顯示多一次」, 因為這條路徑根本沒有 server echo 會回來)。assistant 氣泡
 *  (配對到的回覆句) 就用 response 的 answer 顯示, 配埋 type/operation 一齊, 等你
 *  看到配對了邊條問法、有沒有觸發動作。找不到就顯示一句 system 提示, 不扮有回應。 */
function sendSpeechChatText() {
  const input = document.getElementById("speechChatTextInput");
  const text = input ? (input.value || "").trim() : "";
  if (!text) return;
  const btn = document.getElementById("speechChatSendBtn");
  if (btn) btn.disabled = true;
  appendSpeechChatLine("xiaozhi-msg-user", text);
  return Alpha2Api.speechSemanticSimulate( { text: text }).then(function (res) {
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
  // 恆行 Android：參數照收，傳其他值都當 android；直接載入引擎/語言清單。
  currentTtsEngine = "android";
  loadAndroidTtsEngines();
  loadAndroidTtsLanguages();
}

/** 選 Android TTS 引擎 (speech/tts engine=android 分支實際說話用那個系統
 *  TTS) - 由 speech/set_tts_engine 切換, 這個 switch
 *  本身是 async (後端拆舊起新一個 TextToSpeech instance), 所以完成之後短暫
 *  delay 先重新讀回 speech/cur_tts_engine 確認, 對照後端 MainActivity 個
 *  initAndroidTts() javadoc 講的「不即刻 ready」。切換了引擎, 舊引擎個語言
 *  清單已經不合用, 要重新載入。 */
function setAndroidTtsEngine() {
  const select = document.getElementById("ttsAndroidEngineSelect");
  const enginePkg = select ? select.value : "";
  if (!enginePkg) return;
  // 轉引擎：舊語言未必適用，前後端一齊重置 (後端 pref 都清)。聲綁死引擎＋語言，一齊清＋收起聲行。
  currentAndroidTtsLang = "";
  currentAndroidTtsVoice = "";
  hideAndroidTtsVoiceRow();
  Alpha2Api.speechSetTtsLang( { lang: "" });
  Alpha2Api.speechSetTtsEngine( { engine: enginePkg }).then(function () {
    setTimeout(function () {
      loadCurAndroidTtsEngine();
      loadAndroidTtsLanguages();
    }, 800);
  });
}

/** 載入機身裝了的全部 Android TTS 引擎, 填入 <select>, 再讀回現在實際正在選
 *  哪個, 選回它做已選項。 */
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

// 現在正在選的 Android TTS 語言 BCP-47 tag - 空字串代表沿用 engine 現在已經
// 生效那個語言, 不強行切換 (見後端 speech/tts 個 android 分支 comment)。
// speakTts() 會附帶這個值；對話管線 (後端 speakAndroidTts) 讀同一個後端 pref，
// 所以這裡一選，對話 TTS 即時跟 (見 setAndroidTtsLang)。
let currentAndroidTtsLang = "";
// 現在正在選的具體聲音 (Voice.getName()，空=該語言預設聲)。綁死引擎＋語言，
// 轉引擎／轉語言當時一齊清（後端 pref 亦清，見 setAndroidTtsEngine／
// setAndroidTtsLang）。speakTts() 會附帶；對話管線自動跟後端 pref。
let currentAndroidTtsVoice = "";

/** 載入現在正在選那個 Android TTS 引擎識的全部語言 (server 端經
 *  TextToSpeech.getVoices() 拿, 見 MainActivity#listAndroidTtsLanguages()
 *  javadoc), displayName 已經是 server 選好 ui_lang 那種語言的顯示名, 前端
 *  不用自己維護 tag->name 對照表。 */
function loadAndroidTtsLanguages() {
  Alpha2Api.speechTtsLanguages( { ui_lang: uiLang }).then(function (res) {
    const select = document.getElementById("ttsAndroidLangSelect");
    if (!select || !res || !res.ok || !res.languages) return;
    select.innerHTML = "";
    // 「沿用引擎目前語言」這個選項排第一, value 留空 - 對應後端 lang 參數
    // 留空/null 那個分支 (不強行 setLanguage())。
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
    // 後端 pref 可能有上次記下的選擇 (重啟後前端 var 會丟失)，同步。
    Alpha2Api.speechCurTtsLang().then(function (cur) {
      if (cur && cur.ok && cur.lang !== undefined && cur.lang !== currentAndroidTtsLang) {
        currentAndroidTtsLang = cur.lang || "";
        select.value = currentAndroidTtsLang;
      }
      // 語言 sync 完先載入聲音（聲單掛在具體語言下面）。
      loadAndroidTtsVoices();
    });
  });
}

function setAndroidTtsLang() {
  const select = document.getElementById("ttsAndroidLangSelect");
  currentAndroidTtsLang = select ? select.value : "";
  // 轉語言＝舊聲作廢（不同語言不同聲），前後端一齊清，聲行重載。
  currentAndroidTtsVoice = "";
  // 同步寫回後端 pref —— 對話管線 TTS 即時跟這個選擇 (見 MainActivity.speakAndroidTts)。
  Alpha2Api.speechSetTtsLang( { lang: currentAndroidTtsLang }).then(function () {
    loadAndroidTtsVoices();
  });
}

/** 聲行收起＋清空（未選具體語言／轉引擎當時用）。 */
function hideAndroidTtsVoiceRow() {
  const row = document.getElementById("ttsAndroidVoiceRow");
  if (row) row.style.display = "none";
  const select = document.getElementById("ttsAndroidVoiceSelect");
  if (select) select.innerHTML = "";
}

/** 載入正在選那隻語言的全部聲音，填入下拉。未選具體語言（沿用引擎目前語言）
 *  就成行收起——不知什麼語言就不知有什麼聲好選。名跟 uiLang 加本地／網絡後綴。 */
function loadAndroidTtsVoices() {
  const row = document.getElementById("ttsAndroidVoiceRow");
  const select = document.getElementById("ttsAndroidVoiceSelect");
  if (!row || !select) return;
  if (!currentAndroidTtsLang) {
    hideAndroidTtsVoiceRow();
    return;
  }
  row.style.display = "";
  select.innerHTML = "";
  const loading = document.createElement("option");
  loading.value = "";
  loading.textContent = t("tts_android_voice_loading");
  select.appendChild(loading);
  Alpha2Api.speechTtsVoices( { lang: currentAndroidTtsLang }).then(function (res) {
    // 舊 WebView 無 Node.isConnected，re-get 確認個 select 還在這裡先填
    //（轉頁／重建當時回來太遲就不要動）。
    const live = document.getElementById("ttsAndroidVoiceSelect");
    if (!live || live !== select) return;
    select.innerHTML = "";
    const keepOpt = document.createElement("option");
    keepOpt.value = "";
    keepOpt.textContent = t("tts_android_voice_keep_option");
    select.appendChild(keepOpt);
    if (res && res.ok && res.voices) {
      // 同 Google TTS 系統設定一樣：「語音 I、II、III…」順序編號，只列同一個
      // locale 的機內聲（網絡聲要上網，Google 那版都不列；尾缀 "-language"
      // 那顆是偽預設聲，Google 那版都無，不計）。不夠料先跌回同 language。
      const list = googleStyleVoices(res.voices, currentAndroidTtsLang);
      list.forEach(function (v, idx) {
        const opt = document.createElement("option");
        opt.value = v.name;
        opt.textContent = t("tts_android_voice_name") + " " + toRoman(idx + 1);
        opt.title = v.name;
        select.appendChild(opt);
      });
    }
    select.value = currentAndroidTtsVoice;
    // 後端 pref 可能有上次記下的選擇 (轉語言會清，同步先準)。
    Alpha2Api.speechCurTtsVoice().then(function (cur) {
      const live = document.getElementById("ttsAndroidVoiceSelect");
      if (!live || live !== select) return;
      if (cur && cur.ok && cur.voice !== undefined && cur.voice !== currentAndroidTtsVoice) {
        currentAndroidTtsVoice = cur.voice || "";
        select.value = currentAndroidTtsVoice;
      }
    });
  });
}

/** Google TTS 系統設定同款過濾＋排序：同 locale 的機內聲（network＝false），
 *  剔除尾缀 "-language" 偽預設聲，照後端給的名順序出（ jar→yuc→…，同 Google
 *  那版 I、II、III… 對得上）。同 locale 一粒都無，先跌回同 language 的機內聲。 */
function googleStyleVoices(voices, langTag) {
  const norm = function (x) { return String(x || "").toLowerCase(); };
  const isRealLocal = function (v) {
    return v && !v.network && !/-language$/i.test(v.name || "");
  };
  const sameLocale = function (v) { return norm(v.locale) === norm(langTag); };
  const sameLang = function (v) {
    return norm(v.locale).split("-")[0] === norm(langTag).split("-")[0];
  };
  const exact = (voices || []).filter(function (v) { return isRealLocal(v) && sameLocale(v); });
  if (exact.length) return exact;
  return (voices || []).filter(function (v) { return isRealLocal(v) && sameLang(v); });
}

/** 1→I，2→II，3→III…（Google 那版用羅馬數字編聲音）。 */
function toRoman(n) {
  n = parseInt(n, 10);
  if (!(n > 0)) return String(n);
  const table = [[10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  let out = "";
  // Google 那版一隻語言不會超過十把聲，X 夠用；超了就 X-translate 這麼疊上去。
  while (n > 0) {
    for (let i = 0; i < table.length; i++) {
      while (n >= table[i][0]) {
        out += table[i][1];
        n -= table[i][0];
      }
    }
  }
  return out;
}

function setAndroidTtsVoice() {
  const select = document.getElementById("ttsAndroidVoiceSelect");
  currentAndroidTtsVoice = select ? select.value : "";
  // 同步寫回後端 pref —— 對話管線 TTS 即時跟這把聲 (見 TtsCenter.speakAndroidTts)。
  Alpha2Api.speechSetTtsVoice( { voice: currentAndroidTtsVoice });
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) { showError("語音", t("speech_test_enter_text_alert")); return; }
  // 恆行 Android TTS。lang 有選先帶 (空=沿用引擎目前語言)。
  const params = { text: text, engine: "android" };
  // 空字串=沿用引擎目前語言 (見後端 speech/tts 個 android 分支 comment)。
  if (currentAndroidTtsLang) {
    params.lang = currentAndroidTtsLang;
  }
  // 有選具體聲先帶 (空=該語言預設聲；後端找不到會跌回 lang 路)。
  if (currentAndroidTtsVoice) {
    params.voice = currentAndroidTtsVoice;
  }
  // 對話界面: 機械人「說話」即刻顯示做 assistant 氣泡 — 這裡同小智不同的是
  // TTS request 本身沒有對應的非同步 event 會將講了的文字送回來 (不似 asr_result
  // 那樣), 所以直接在這裡用發送那刻的文字 append, 不用等 server 回應。
  appendSpeechChatLine("xiaozhi-msg-assistant", text);
  // 播新東西之前先停下舊那句, 不是就兩句 TTS 可能撞在一起播 (講到一半那句還未
  // 完, 個新 request 已經開始講, 聽起來會疊聲/含糊)。stopTts() 失敗都照樣繼續
  // 播放新的 (例如號碼一次沒有東西正正在播, stop 本身可能會 error/no-op, 不應該
  // 因為這樣就不給用家繼續說話)。
  return stopTts().catch(function () {}).then(function () {
    return Alpha2Api.speechTts( params);
  });
}

function stopTts() {
  return Alpha2Api.speechStop();
}

