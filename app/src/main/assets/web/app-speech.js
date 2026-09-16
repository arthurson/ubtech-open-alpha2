// Open Alpha2 — client logic (app-speech.js)
// 內容: TTS/ASR/自我打斷/service_config preset/語音三路輸入測試。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Speech / TTS (Android 內置 only) ----------------
//
// 語音 tab 得返 Android 系統 TTS。currentTtsEngine 恆等於 "android", setTtsEngine()
// 只做 Android 引擎/語言列載入 (開頁初始化用, 保留個名唔改, 免得 app-log.js
// 個 init call 要一齊改名)。
let currentTtsEngine = "android";

// ---------------- Speech / 對話界面 (全抄小智 tab 做法) ----------------
//
// 對照 app-xiaozhi.js 嘅 xiaozhiAppendChatLine()/xiaozhiSendText() —
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

/** 文字輸入框「送出」— 打字入嘅文字當做已經辨識完嘅結果, 直接送去問法配對引擎
 *  (中英文各 1000 條, SemanticMatcherZh/SemanticMatcherEn, 按輸入有冇漢字自動
 *  判斷用邊份), 命中就即時做 TTS + (可能有嘅) 動作 - 唔使真係郁把口, 都可以測到
 *  「聽到 -> 講嘢/做動作」成條 pipeline。
 *
 *  呢條路徑唔經任何機身 AIDL 辨識, 純粹本地文字配對,
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

/** 揀 Android TTS 引擎 (speech/tts engine=android 分支實際講嘢用嗰個系統
 *  TTS) - 由 speech/set_tts_engine 切換, 呢個 switch
 *  本身係 async (後端拆舊起新一個 TextToSpeech instance), 所以完成之後短暫
 *  delay 先重新讀返 speech/cur_tts_engine 確認, 對照後端 MainActivity 個
 *  initAndroidTts() javadoc 講嘅「唔即刻 ready」。切換咗引擎, 舊引擎個語言
 *  清單已經唔啱用, 要重新載入。 */
function setAndroidTtsEngine() {
  const select = document.getElementById("ttsAndroidEngineSelect");
  const enginePkg = select ? select.value : "";
  if (!enginePkg) return;
  // 轉引擎：舊語言未必啱用，前後端一齊重置 (後端 pref 都清)。聲綁死引擎＋語言，一齊清＋收埋聲行。
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
// 而家揀緊嘅具體聲音 (Voice.getName()，空=該語言預設聲)。綁死引擎＋語言，
// 轉引擎／轉語言嗰陣一齊清（後端 pref 亦清，見 setAndroidTtsEngine／
// setAndroidTtsLang）。speakTts() 會帶埋；對話管線自動跟後端 pref。
let currentAndroidTtsVoice = "";

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
      // 語言 sync 完先載入聲音（聲單掛喺具體語言下面）。
      loadAndroidTtsVoices();
    });
  });
}

function setAndroidTtsLang() {
  const select = document.getElementById("ttsAndroidLangSelect");
  currentAndroidTtsLang = select ? select.value : "";
  // 轉語言＝舊聲作廢（唔同語言唔同聲），前後端一齊清，聲行重載。
  currentAndroidTtsVoice = "";
  // 同步寫返後端 pref —— 對話管線 TTS 即時跟呢個選擇 (見 MainActivity.speakAndroidTts)。
  Alpha2Api.speechSetTtsLang( { lang: currentAndroidTtsLang }).then(function () {
    loadAndroidTtsVoices();
  });
}

/** 聲行收埋＋清空（未揀具體語言／轉引擎嗰陣用）。 */
function hideAndroidTtsVoiceRow() {
  const row = document.getElementById("ttsAndroidVoiceRow");
  if (row) row.style.display = "none";
  const select = document.getElementById("ttsAndroidVoiceSelect");
  if (select) select.innerHTML = "";
}

/** 載入揀緊嗰隻語言嘅全部聲音，填入下拉。未揀具體語言（沿用引擎目前語言）
 *  就成行收埋——唔知咩語言就唔知有咩聲好揀。名跟 uiLang 加本地／網絡後綴。 */
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
    // 舊 WebView 無 Node.isConnected，re-get 確認個 select 仲喺度先填
    //（轉頁／重建嗰陣回嚟太遲就唔好郁）。
    const live = document.getElementById("ttsAndroidVoiceSelect");
    if (!live || live !== select) return;
    select.innerHTML = "";
    const keepOpt = document.createElement("option");
    keepOpt.value = "";
    keepOpt.textContent = t("tts_android_voice_keep_option");
    select.appendChild(keepOpt);
    if (res && res.ok && res.voices) {
      // 同 Google TTS 系統設定一樣：「語音 I、II、III…」順序編號，只列同一個
      // locale 嘅機內聲（網絡聲要上網，Google 嗰版都唔列；尾缀 "-language"
      // 嗰粒係偽預設聲，Google 嗰版都無，唔計）。唔夠料先跌返同 language。
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
    // 後端 pref 可能有上次記低嘅選擇 (轉語言會清，sync 返先準)。
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

/** Google TTS 系統設定同款過濾＋排序：同 locale 嘅機內聲（network＝false），
 *  剔走尾缀 "-language" 偽預設聲，照後端俾嘅名順序出（ jar→yuc→…，同 Google
 *  嗰版 I、II、III… 對得上）。同 locale 一粒都無，先跌返同 language 嘅機內聲。 */
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

/** 1→I，2→II，3→III…（Google 嗰版用羅馬數字編聲音）。 */
function toRoman(n) {
  n = parseInt(n, 10);
  if (!(n > 0)) return String(n);
  const table = [[10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  let out = "";
  // Google 嗰版一隻語言唔會超過十把聲，X 夠用；超咗就 X-translate 咁疊上去。
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
  // 同步寫返後端 pref —— 對話管線 TTS 即時跟呢把聲 (見 TtsCenter.speakAndroidTts)。
  Alpha2Api.speechSetTtsVoice( { voice: currentAndroidTtsVoice });
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) { showError("語音", t("speech_test_enter_text_alert")); return; }
  // 恆行 Android TTS。lang 有揀先帶 (空=沿用引擎目前語言)。
  const params = { text: text, engine: "android" };
  // 空字串=沿用引擎目前語言 (見後端 speech/tts 個 android 分支 comment)。
  if (currentAndroidTtsLang) {
    params.lang = currentAndroidTtsLang;
  }
  // 有揀具體聲先帶 (空=該語言預設聲；後端搵唔到會跌返 lang 路)。
  if (currentAndroidTtsVoice) {
    params.voice = currentAndroidTtsVoice;
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
