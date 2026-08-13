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

const MAX_LOG_LINES = 200;

function appendLog(msg) {
  // accel fires at high frequency (every ~150-250ms once enabled) and is purely a
  // live readout, not something worth scrolling through in the event log - skip the
  // log DOM entirely for it (still update its tile/chart below) rather than relying
  // on MAX_LOG_LINES trimming to keep up with a flood of these every second.
  if (msg.type !== "accel") {
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
    if (el) el.textContent = msg.data.uuid;
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
    const el = document.getElementById("asrOut");
    if (el) el.textContent = msg.data.text;
  }
  if (msg.type === "speech_ready" && msg.data) {
    // speech/set_asr_engine() 觸發嘅 rebind 完成 (或者一開機嘅初始 bind 完成)
    // 都會經呢個 event 返嚟。ready=false 期間 (rebind 進行緊) 擋住撳「開始
    // 聆聽」, 避免喺 speech service 重新綁定緊嗰陣撞 race condition。
    speechReadyForAsr = !!msg.data.ready;
    if (speechReadyForAsr) {
      const el = document.getElementById("asrOut");
      if (el && el.dataset.state === "switching") {
        el.textContent = t("asr_engine_ready_hint");
        el.dataset.state = "";
      }
    }
  }
  if (msg.type === "asr_engine_switched" && msg.data) {
    appendLog(t("asr_engine_switched_log_prefix") + msg.data.engine);
    currentAsrEngine = msg.data.engine;
    const hintEl = document.getElementById("asrCurrentEngineHint");
    if (hintEl) {
      hintEl.textContent = t("asr_current_engine_prefix") + (msg.data.engine === "iflytek" ? t("asr_switch_zh_btn") : t("asr_switch_en_btn"));
    }
    const zhBtn = document.getElementById("asrSwitchZhBtn");
    const enBtn = document.getElementById("asrSwitchEnBtn");
    if (zhBtn) zhBtn.classList.toggle("active", msg.data.engine === "iflytek");
    if (enBtn) enBtn.classList.toggle("active", msg.data.engine === "nuance");
  }
  if (msg.type === "asr_intent" && msg.data) {
    const el = document.getElementById("asrIntentOut");
    if (el) {
      const rule = msg.data.rule || "-";
      const action = msg.data.action || "-";
      el.textContent = "rule=" + rule + " / action=" + action;
    }
  }
  if (msg.type === "text_understand" && msg.data) {
    const el = document.getElementById("nluOut");
    if (el) el.textContent = msg.data.ok ? msg.data.result : (t("log_error_code_prefix") + msg.data.errorCode + ")");
  }
  if (msg.type === "grammar_init" && msg.data) {
    const el = document.getElementById("grammarInitOut");
    if (el) el.textContent = "grammarId=" + msg.data.grammarId + " / errorCode=" + msg.data.errorCode;
  }
  if (msg.type === "grammar_result" && msg.data) {
    const el = document.getElementById("grammarResultOut");
    if (el) {
      el.textContent = msg.data.ok
        ? ("type=" + msg.data.type + " / " + msg.data.result)
        : (t("log_error_code_prefix") + msg.data.errorCode + ")");
    }
  }
  if (msg.type === "english_understand" && msg.data) {
    const el = document.getElementById("advEnglishOut");
    if (el) el.textContent = "(online) " + msg.data.result;
  }
  if (msg.type === "english_understand_offline" && msg.data) {
    const el = document.getElementById("advEnglishOut");
    if (el) el.textContent = "(offline) " + msg.data.result;
  }
  if (msg.type === "asr_replay" && msg.data) {
    const el = document.getElementById("advReplayOut");
    if (el) {
      el.textContent = "[" + msg.data.recordId + "] (" + msg.data.msgLanguage + ") " + msg.data.content;
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
  if (msg.type === "pir_state" && msg.data) {
    onPirState(msg.data);
  }
  if (msg.type === "lynx_motor_angle" && msg.data) {
    // 寫入嗰隻 servo 自己嘅 readout span, 20 個結果可能唔跟發出順序返嚟,
    // 逐個 id 揾返自己個位寫。淨係顯示 code。
    const el = document.getElementById("lynxServoRead_" + msg.data.id);
    if (el) el.textContent = String(msg.data.code);
  }
  if (msg.type === "accel" && msg.data) {
    onAccelSample(msg.data);
  }
  if (msg.type === "lynx_action_list" && msg.data) {
    lynxAllActions = msg.data.actions || [];
    // buildLynxActionSubTabs() 入面尾段已經會 call buildLynxActionSubSubTabs() (跟
    // buildActionSubTabs()/buildActionSubSubTabs() 嗰種大分類建完即刻建子分類嘅做法
    // 一致), 唔使呢度再多call一次。
    buildLynxActionSubTabs();
    lynxRenderActionList();
  }
  if (msg.type === "lynx_action_progress" && msg.data) {
    const el = document.getElementById("lynxActionStatus");
    if (el) el.textContent = "播放中 (code=" + msg.data.code + ", progress=" + msg.data.progress + ")";
  }
  if (msg.type === "lynx_action_stop" && msg.data) {
    const el = document.getElementById("lynxActionStatus");
    if (el) el.textContent = "已停止 (code=" + msg.data.code + ")";
  }
  // 小智 (XiaoZhi) AI 對話 - 全部 xiaozhi_* event type 交俾 app-xiaozhi.js 嘅
  // xiaozhiHandleEvent() 統一處理 (state/stt/llm/tts/mcp/system/alert), 呢度淨係
  // 做 dispatch, 唔喺呢個檔案重複寫渲染邏輯。app-xiaozhi.js 雖然喺 index.html
  // 排喺呢個檔案之前 load, 但 appendLog() 本身要等 WebSocket message 先會 call
  // 到, 所以 xiaozhiHandleEvent 呢個時候實質上一定已經定義咗。
  if (msg.type.indexOf("xiaozhi_") === 0 && msg.data) {
    xiaozhiHandleEvent(msg.type, msg.data);
  }
}

function clearLog() {
  document.getElementById("eventLog").innerHTML = "";
}

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- init ----------------

window.addEventListener("DOMContentLoaded", function () {
  buildServoGrid();
  lynxBuildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  lynxBuildEyeColorPicker();
  lynxBuildHeadColorPicker();
  setTtsEngine("nuance"); // 初始化引擎/聲音按鈕嘅 active 狀態、隱藏聲音嗰行 (預設 nuance)
  // 麥克風指示燈初始狀態 - app 啱啱起身嗰陣 MainActivity 嘅 micHeldByApp/
  // micHoldEnforced 兩個 field 都係預設 false (機械人持有 mic), 呢度令 UI
  // 一開始就同 server 端一致, 唔使等第一個 mic_state event 先顯示啱嘅狀態。
  // 如果之前個 session 已經攞咗 mic (例如撳完掣之後 reload 個頁), 之後嗰句
  // speech/set_mic 或者 mic_state event 一樣會即時更新返嚟。
  updateMicStateUi(false, false);
  switchBackend(currentBackend); // apply saved/default backend choice (also refreshes status)
  applyUiLanguage();
  refreshVolume();
  disableTalkFabIfInsecureContext();
  connectWs();
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
