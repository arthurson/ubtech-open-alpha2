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
    if (el) el.textContent = msg.data.uuid;
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
  // 手勢辨識 (ALPHA_GESTURE_ACTION, 唔係 Blockly 動作庫嗰啲「手勢類」預錄動作 -
  // 呢個係機身自己偵測人類手勢輸入嘅事件) - direction 嘅實際數值代表邊個方向未經
  // 真機文檔證實 (RobotEventReceiver 個 class javadoc 提過官方文檔寫緊 String,
  // 但真機實測係 Integer), 所以呢度淨係如實顯示個 raw value, 唔加自己估嘅方向
  // 名稱標籤, 等用戶做手勢測試時自己記低邊個動作對應邊個數值。
  if (msg.type === "gesture" && msg.data) {
    const el = document.getElementById("gestureOut");
    if (el) el.textContent = String(msg.data.direction);
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
  renderAboutBox();
  connectWs();
});

// 進階 tab 最尾嘅「關於」box - 純靜態顯示 APP_VERSION/APP_REPO_URL (定義喺
// app-core.js 頂部, 一直留低做 single source of truth), 冇涉及任何 API call,
// 所以淨係喺 DOMContentLoaded 行一次就夠, 唔使跟 refreshStatus() 嗰啲定時更新。
function renderAboutBox() {
  const versionEl = document.getElementById("advAboutVersion");
  if (versionEl) versionEl.textContent = APP_VERSION;
  const repoLink = document.getElementById("advAboutRepoLink");
  if (repoLink) {
    repoLink.href = APP_REPO_URL;
    repoLink.textContent = APP_REPO_URL;
  }
}

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
