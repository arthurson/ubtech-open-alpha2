// OpenLynx — client logic (app-log.js)
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
  if (msg.type === "battery" && msg.data) {
    const el = document.getElementById("batteryOut");
    if (el) el.textContent = msg.data.level + "/" + msg.data.scale + " " + (msg.data.charging ? "⚡充電中" : "") + " (" + msg.data.status + ")";
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
  lynxBuildServoGrid();
  lynxBuildEyeColorPicker();
  lynxBuildHeadColorPicker();
  applyUiLanguage();
  lynxRefreshStatus();
  lynxRefreshSys();
  connectWs();
});
