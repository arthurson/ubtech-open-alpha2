// Open Alpha2 — client logic (app-log.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: WebSocket event log、頁面初始化 (DOMContentLoaded)。呢個檔案要最後 load, 因為 init() 要用晒其他所有 app-*.js 定義嘅 build*()/refresh*() function。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- WebSocket event log ----------------

let ws;
function connectWs() {
  const proto = location.protocol === "https:" ? "wss://" : "ws://";
  ws = new WebSocket(proto + location.host + "/ws");

  ws.onopen = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "已連接 (WebSocket live)" });
  };
  ws.onclose = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "已斷線，3秒後重連…" });
    setTimeout(connectWs, 3000);
  };
  ws.onerror = function () { ws.close(); };
  ws.onmessage = function (evt) {
    try {
      const msg = JSON.parse(evt.data);
      appendLog(msg);
    } catch (e) {
      appendLog({ type: "raw", time: "", data: evt.data });
    }
  };
}

function nowTimeStr() {
  return new Date().toLocaleTimeString("zh-HK", { hour12: false });
}

/** 統一嘅語意配對 + TTS + 動作觸發函數 — 所有 5 種輸入方法
 *  （文字輸入、iFlytek asr_result、iFlytek grammar_result、
 *  Nuance asr_result、Nuance grammar_result）都用呢個方法處理，
 *  同 sendSpeechChatText() 嘅後半段邏輯一致。 */
function triggerIflytekSimulate(text) {
  if (!text) return;
  api("speech/iflytek_simulate", { text: text }).then(function (res) {
    if (!res || !res.ok) return;
    if (!res.matched) {
      appendSpeechChatLine("xiaozhi-msg-system", t("speech_chat_simulate_no_match"));
      return;
    }
    // 2026-08 清理: 對話界面淨係顯示中英文對白 - 條 [TYPE operation] 動作ID
    // detail 行已經搬走 (Event Log 有齊同樣資訊, 唔使喺對話流度重複)。
    if (res.answer) {
      appendSpeechChatLine("xiaozhi-msg-assistant", res.answer);
    }
  });
}

/** 2026-08 新增: 對話界面用嘅文字過濾 - 如果辨識結果其實仲係 JSON 字串
 *  (例如 {"text":"你好","rc":4}), 抽返個 text 出嚟; 抽唔到就返回空字串,
 *  咁樣 user 氣泡永遠唔會出現大括號/引號呢啲「代碼」。 */
function cleanChatText(s) {
  if (!s) return "";
  const t = String(s).trim();
  if (t.indexOf("{") === 0 && t.indexOf("}") > 0) {
    const m = t.match(/"text"\s*:\s*"([^"]*)"/);
    return m ? m[1] : "";
  }
  return t;
}

const MAX_LOG_LINES = 200;

// chest_broadcast_debug / mic_broadcast_debug 呢兩個 event type 純粹係
// RobotEventReceiver.java 度收集「未知 payload」用嘅診斷 event (見
// RobotEventReceiver 個 CHEST_ACTION 同嗰 8 個 mic-related case 嘅 comment) -
// 冇對應嘅 UI tile/chart, 印落 Event Log 淨係洗版, 冇實質資訊價值 (真正想睇
// payload 要靠 logcat 嘅 Log.i, 唔係靠呢個 WebSocket event)。同 accel 一樣,
// 跳過 log DOM, 但唔阻住呢個 event 本身經 EventBus 繼續 publish - 呢度淨係
// 前端唔顯示, RobotEventReceiver.java 嗰邊嘅收集機制完全冇改。
const SILENCED_LOG_TYPES = ["accel", "chest_broadcast_debug", "mic_broadcast_debug"];

function appendLog(msg) {
  if (SILENCED_LOG_TYPES.indexOf(msg.type) === -1) {
    const log = document.getElementById("eventLog");
    const line = document.createElement("div");
    line.className = "log-line log-type-" + msg.type;
    const dataStr = typeof msg.data === "object" ? JSON.stringify(msg.data) : msg.data;
    line.innerHTML = "<span class=\"log-time\">[" + msg.time + "]</span> <b>" + msg.type + "</b> " + escapeHtml(dataStr);
    log.appendChild(line);
    // Cap the number of DOM nodes kept around for any other, lower-frequency event
    // type too, as a safety net against unbounded growth over a long session.
    while (log.childElementCount > MAX_LOG_LINES) {
      log.removeChild(log.firstChild);
    }
    if (document.getElementById("autoScroll").checked) {
      log.scrollTop = log.scrollHeight;
    }
  }

  // A couple of event types also update a dedicated tile, not just the scrolling log,
  // since the HTTP call that triggered them (requestRobotUUID(), the battery receiver)
  // doesn't carry the actual result back in its own response.
  if (msg.type === "robot_uuid" && msg.data && msg.data.uuid) {
    const el = document.getElementById("uuidOut");
    // 2026-08 v2: SN 欄位可能帶 \0 padding — 剝走控制字元先顯示。
    // 2026-08 v4: 單淨 \x00-\x1f 唔夠 — EEPROM 尾段殘留可能係非零垃圾 byte,
    // 令顯示出現方塊/亂碼字元 (Java 端 RobotEventReceiver 已經加咗白名單過濾,
    // 呢度係第二重保障, 以防萬一)。SN 合法字元集只有英數/-/_。
    const clean = String(msg.data.uuid).replace(/[^A-Za-z0-9\-_]/g, "").trim();
    if (el) el.textContent = clean;
    uuidUpdateCard(clean);
  }
  // 2026-08 新增: 配對碼之前淨係經 xiaozhi/activation_status HTTP polling
  // 傳去前端 (見 app-xiaozhi.js xiaozhiPollActivationStatus()), 完全冇經
  // WebSocket event log 呢條路徑 - 用戶反映「淨係得聲音, 連 websocket 都無
  // 顯示」。MainActivity 而家喺攞到配對碼嗰刻都會經 EventBus publish 呢個
  // "xiaozhi_activation" type, 令佢除咗印落上面通用嘅 #eventLog 之外, 都
  // 順便觸發 app-xiaozhi.js 嗰個 xiaozhiShowActivationCode() (若果小智嗰個
  // tab 都已經 load 咗 - 用 typeof 檢查, 因為 app-log.js 有可能喺
  // app-xiaozhi.js 之前執行到呢一行, 或者呢個 build 完全冇夾埋小智功能)。
  // xiaozhiShowActivationCode() 本身已經有 xiaozhiLastShownActivationCode
  // 防重複顯示, 呢度唔使自己再擋一次。
  if (msg.type === "xiaozhi_activation" && msg.data && msg.data.code) {
    if (typeof xiaozhiShowActivationCode === "function") {
      xiaozhiShowActivationCode(msg.data.code);
    }
  }
  if (msg.type === "battery" && msg.data) {
    const el = document.getElementById("batteryOut");
    if (el) el.textContent = msg.data.level + "/" + msg.data.scale + " " + (msg.data.charging ? "⚡充電中" : "") + " (" + msg.data.status + ")";
  }
  // mic_state 由 server 端喺 speech/set_mic 同 speech/set_mic_keep_held 兩個
  // endpoint 都會 publish (見 MainActivity), 令指示燈可以即時反映最新狀態,
  // 唔使靠前端自己記住個 boolean - 例如用戶開咗「持續搶 mic」之後 enforcer
  // thread 自動搶返 mic, 呢個變化都會經呢條 event 反映返上 UI, 唔會停留喺
  // 舊狀態。
  if (msg.type === "mic_state" && msg.data) {
    updateMicStateUi(msg.data.held, msg.data.keepHeld);
  }
  if (msg.type === "asr_result" && msg.data) {
    // 對話界面: 辨識到嘅嘢顯示做 user 氣泡 (2026-08: 經 cleanChatText 過濾,
    // JSON 碎片唔會出現喺對話流度)
    const cleanAsr = cleanChatText(msg.data.text);
    if (cleanAsr && typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-user", cleanAsr);
      triggerIflytekSimulate(cleanAsr);
    }
  }
  if (msg.type === "speech_ready" && msg.data) {
    // 2026-08 更正: 之前呢度講「speech/set_asr_engine() 觸發嘅 rebind」——
    // set_asr_engine 呢個 endpoint 已經確認會整死 TTS session, 已經改用
    // speech/set_language (call speech_setRecognizedLanguage(), 唔需要
    // unbind/rebind) 嚟切換引擎。呢個 event 而家係 speech/set_language 完成
    // (或者一開機嘅初始 bind 完成) 都會經呢度返嚟。ready=false 期間 (切換
    // 進行緊) 擋住撳「開始聆聽」, 避免喺 speech service 狀態未穩定嗰陣撞
    // race condition。
    // 2026-08 清理: 之前呢度仲有 document.getElementById("asrOut") 嘅
    // dataset.state 更新邏輯, 對應嘅 "asrOut" element 已經喺 index.html
    // 完全移除, 屬於死 code, 已刪走。
    speechReadyForAsr = !!msg.data.ready;
    if (speechReadyForAsr) {
      if (typeof appendSpeechChatLine === "function") {
        appendSpeechChatLine("xiaozhi-msg-system", t("asr_engine_ready_hint"));
      }
    }
  }
  // 真正 online iFlytek ASR 認到之後嘅語意配對結果 (由 MainActivity
  // handleIflytekSemanticText() publish) — 之前淨係 speech/iflytek_simulate
  // (打字模擬) 嗰條路徑先會喺 sendSpeechChatText() 度即時攞 HTTP response
  // 顯示 assistant 氣泡, 真正 ASR 嗰邊冇對應 WebSocket handler, 所以「聽到
  // -> 配對 -> 回答/動作」呢一截喺對話界面完全睇唔到 (只有 asr_result 嗰句
  // user 氣泡會顯示)。呢度補返, 令兩條路徑 (真人講嘢 / 打字模擬) 喺對話
  // 界面出返一致嘅 assistant 氣泡 + detail 提示。
  if (msg.type === "iflytek_match" && msg.data) {
    // 2026-08 清理: 對話界面淨係出 assistant 答案氣泡; 條 [TYPE operation]
    // 動作ID detail 行已經移除 - 呢啲技術代碼喺 Event Log 度睇得到。
    if (msg.data.answer && typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-assistant", msg.data.answer);
    }
  }
  // 2026-08 重新加入 grammar 系列 event (離線文法辨識):
  // - grammar_init: 機身 buildGrammar 完成回執 (grammarId + errorCode)
  // - grammar_result: 辨識結果 (type=1 係文字, text field 已抽好; raw 原樣)
  //   type=1 嗰陣當成一句 user 輸入, 行同一條 triggerIflytekSimulate() 語意
  //   配對 + TTS + 動作流程 (asr_result 喺離線模式下已經被後端 gate 住,
  //   唔會雙重觸發)。
  // - grammar_error: 機身回報嘅辨識錯誤碼
  //
  // 2026-08 清理: 對話界面淨係顯示中英文對白 - grammar_init/error 嘅技術
  // 資訊淨係更新下面個狀態行同 Event Log, 唔再塞入對話流度; user 氣泡經
  // cleanChatText() 過濾, JSON 碎片唔會再出現。
  if (msg.type === "grammar_init" && msg.data) {
    const ok = msg.data.errorCode === 0;
    if (!ok) {
      const el = document.getElementById("grammarStatusOut");
      if (el) el.textContent = t("offline_grammar_init_fail") + "errorCode " + msg.data.errorCode;
    }
  }
  if (msg.type === "grammar_result" && msg.data) {
    // 對話界面: 辨識到嘅嘢顯示做 user 氣泡 + 觸發語意配對 (type=1 先係文字結果)
    if (msg.data.type === 1 && typeof appendSpeechChatLine === "function") {
      const clean = cleanChatText(msg.data.text);
      if (clean) {
        appendSpeechChatLine("xiaozhi-msg-user", clean);
        triggerIflytekSimulate(clean);
      }
    }
  }
  // 2026-08 新增: 自動跟網絡切換嘅模式變化通知 - 冇網自動入離線文法、有網
  // 自動退返雲端聽寫, 兩邊都喺呢度話俾用戶知而家行緊邊個模式, 順便更新
  // 對話界面標題隔離個指示燈 (灰=離線, 綠=雲端)。
  if (msg.type === "offline_mode" && msg.data) {
    updateAsrModeIndicator(!!msg.data.active);
    if (typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-system",
          msg.data.active ? t("offline_mode_on") : t("offline_mode_off"));
    }
  }
  if (msg.type === "sonar_obstacle" && msg.data) {
    sonarThresholdCm = msg.data.thresholdCm;
    sonarHistory.push({ triggered: !!msg.data.triggered });
    if (sonarHistory.length > SONAR_HISTORY_LEN) {
      sonarHistory.shift();
    }
    drawSonarChart();
  }
  if (msg.type === "alpha2_pir_state" && msg.data) {
    onAlpha2PirState(msg.data);
  }
  if (msg.type === "accel" && msg.data) {
    onAccelSample(msg.data);
  }
  // 小智 (XiaoZhi) AI 對話 - 全部 xiaozhi_* event type 交俾 app-xiaozhi.js 嘅
  // xiaozhiHandleEvent() 統一處理 (state/stt/llm/tts/mcp/system/alert), 呢度淨係
  // 做 dispatch, 唔喺呢個檔案重複寫渲染邏輯。app-xiaozhi.js 雖然喺 index.html
  // 排喺呢個檔案之前 load, 但 appendLog() 本身要等 WebSocket message 先會 call
  // 到, 所以 xiaozhiHandleEvent 呢個時候實質上一定已經定義咗。
  if (msg.type.indexOf("xiaozhi_") === 0 && msg.data) {
    xiaozhiHandleEvent(msg.type, msg.data);
  }
  // tts_end 唔帶 "xiaozhi_" 前綴 (機身/Android 本地 TTS 讀完一句嘅全域訊號,
  // 唔止小智專用, 見 MainActivity 個 onServerPlayEnd/
  // UtteranceProgressListener.onDone() 兩處 comment), 所以行唔到上面嗰條
  // catch-all - 呢度獨立轉一句去 xiaozhiHandleEvent(), 等小智 tab 嘅本地
  // TTS 隊列 (xiaozhiTtsQueue) 知道可以讀下一句 (見 xiaozhiHandleEvent() 個
  // "tts_end" case)。
  if (msg.type === "tts_end") {
    xiaozhiHandleEvent(msg.type, msg.data);
  }
}

function clearLog() {
  document.getElementById("eventLog").innerHTML = "";
}

// 2026-08 新增: 對話界面 iFlytek 模式指示燈 - offline=true 灰燈 (離線文法),
// false 綠燈 (雲端聽寫)。offline_mode event 同開頁初始化都行呢度。
function updateAsrModeIndicator(offline) {
  const dot = document.getElementById("asrModeDot");
  const label = document.getElementById("asrModeLabel");
  if (dot) {
    dot.classList.toggle("asr-mode-offline", offline);
    dot.classList.toggle("asr-mode-online", !offline);
  }
  if (label) {
    label.textContent = offline ? t("asr_mode_label_offline") : t("asr_mode_label_online");
  }
}

function refreshAsrModeIndicator() {
  api("speech/offline_auto_switch").then(function (res) {
    if (res && res.ok && typeof res.offlineActive !== "undefined") {
      updateAsrModeIndicator(!!res.offlineActive);
    }
  });
}

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- init ----------------

window.addEventListener("DOMContentLoaded", function () {
  buildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  setTtsEngine("nuance"); // 初始化引擎/聲音按鈕嘅 active 狀態、隱藏聲音嗰行 (預設 nuance)
  // 麥克風指示燈初始狀態 - app 啱啱起身嗰陣 MainActivity 嘅 micHeldByApp/
  // micHoldEnforced 兩個 field 都係預設 false (機械人持有 mic), 呢度令 UI
  // 一開始就同 server 端一致, 唔使等第一個 mic_state event 先顯示啱嘅狀態。
  // 如果之前個 session 已經攞咗 mic (例如撳完掣之後 reload 個頁), 之後嗰句
  // speech/set_mic 或者 mic_state event 一樣會即時更新返嚟。
  updateMicStateUi(false, false);
  refreshStatus();
  refreshDeviceInfo();
  applyUiLanguage();
  refreshVolume();
  disableTalkFabIfInsecureContext();
  // 離線文法辨識: 開頁嗰陣預載預設 BNF 入個 textarea (攞唔到就留空, 用戶
  // 撳「載入預設」再試), 同步埋「自動跟網絡切換」checkbox 現狀
  if (document.getElementById("grammarBnf")) {
    grammarLoadDefault();
  }
  if (document.getElementById("offlineAutoSwitch") && typeof refreshOfflineAutoSwitch === "function") {
    refreshOfflineAutoSwitch();
  }
  // 對話界面指示燈: 開頁即刻查返而家係雲端定離線模式
  if (document.getElementById("asrModeDot") && typeof refreshAsrModeIndicator === "function") {
    refreshAsrModeIndicator();
  }
  connectWs();
  musicInit();
  if (typeof radioInit === "function") radioInit();
  if (typeof refreshSupportedSizes === "function") refreshSupportedSizes();
  if (typeof buildCameraPhoto9Grid === "function") buildCameraPhoto9Grid();
});

/**
 * 講嘢 (🎤 walkie-talkie 咪) 功能已經永久停用 - 唔止喺 http:// (非安全來源) 先停用,
 * 而係一律 disable, 唔理 secure context 定唔係。掣本身 disable 咗之後瀏覽器唔會再
 * fire pointerdown/click 呢啲事件 (見 startTalk() 頂部個 no-op guard 做多一層保險)。
 */
function disableTalkFabIfInsecureContext() {
  const fab = document.getElementById("talkFab");
  if (!fab) return;
  fab.disabled = true;
  fab.classList.add("disabled");
  fab.title = "講嘢功能已停用";
}
