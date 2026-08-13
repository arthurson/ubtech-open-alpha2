// Open Alpha2 — client logic (app-xiaozhi.js)
// 小智 (XiaoZhi) AI 對話 tab - 連出去 xiaozhi.me 嘅 client-side WebSocket (XiaozhiClient.java
// 喺 server 端做), 呢個檔案負責: 連接/斷開掣、麥克風開始/停止掣、狀態顯示、將
// EventBus 送過嚟嘅 xiaozhi_* WebSocket event 渲染做對話氣泡。PHASE 2: 文字 + Opus
// 語音都已經接通 - 見 XiaozhiClient.java / XiaozhiAudioController.java class javadoc。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。
//
// 依賴 app-log.js 嘅 escapeHtml()/nowTimeStr()/MAX_LOG_LINES (雖然 app-log.js 喺
// index.html 入面排喺呢個檔案之後 load) - 安全嘅原因: 呢度所有用到呢幾個
// identifier 嘅地方都喺 function body 入面 (xiaozhiAppendChatLine()), 唔係 module
// top-level 直接執行, 實際 call 到嗰陣全部 <script> 都已經 load 晒 (DOMContentLoaded
// 之後先有用戶操作/WebSocket event 觸發呢啲 function)。如果之後要喺呢個檔案嘅
// top-level (即係函數外面) 直接用呢幾個 identifier, 就必須將 <script src="app-log.js">
// 搬到呢個檔案之前, 否則會 ReferenceError。

// api()/hwApi() 都係 alpha2/lynx 專屬 (加 "alpha2/"/"lynx/" 前綴) - 小智呢個
// namespace 喺 server 端係 backend-agnostic ("/api/xiaozhi/...", 見
// MainActivity#handleXiaozhiApi), 所以呢度自己起一個, 唔跟 api()/hwApi() 嗰種
// backend 前綴邏輯。
function xiaozhiApi(path, params) {
  clearError();
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  return fetch(API + "xiaozhi/" + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /xiaozhi/" + path, new Error(json.error || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError("Network error calling /xiaozhi/" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

// 全域 flag, 由 xiaozhiCheckSupport() 喺 page load 設定一次 - xiaozhiSetConnectedUi()
// 要用嚟決定 mic 掣係咪應該 enable (連接咗仲未夠, 呢部機仲要支援 Opus 先得)。
let xiaozhiAudioSupported = false;
let xiaozhiMicActive = false;

function xiaozhiElements() {
  return {
    urlInput: document.getElementById("xiaozhiWsUrl"),
    tokenInput: document.getElementById("xiaozhiToken"),
    connectBtn: document.getElementById("xiaozhiConnectBtn"),
    disconnectBtn: document.getElementById("xiaozhiDisconnectBtn"),
    statusBadge: document.getElementById("xiaozhiStatusBadge"),
    chatLog: document.getElementById("xiaozhiChatLog"),
    unsupportedNotice: document.getElementById("xiaozhiUnsupportedNotice"),
    micBtn: document.getElementById("xiaozhiMicBtn"),
    micStatusBadge: document.getElementById("xiaozhiMicStatusBadge"),
  };
}

function xiaozhiSetStatus(stateKey, extraText) {
  const el = xiaozhiElements().statusBadge;
  if (!el) return;
  el.textContent = t(stateKey) + (extraText ? " " + extraText : "");
}

function xiaozhiSetMicStatus(stateKey) {
  const el = xiaozhiElements().micStatusBadge;
  if (el) el.textContent = t(stateKey);
}

function xiaozhiSetConnectedUi(connected) {
  const els = xiaozhiElements();
  if (els.connectBtn) els.connectBtn.disabled = connected;
  if (els.disconnectBtn) els.disconnectBtn.disabled = !connected;
  if (els.urlInput) els.urlInput.disabled = connected;
  if (els.tokenInput) els.tokenInput.disabled = connected;
  // Mic 掣要連接咗 *同時* 呢部機支援 Opus (xiaozhiAudioSupported) 先 enable - 兩個
  // 條件缺一不可, 見 xiaozhiCheckSupport()。斷開連線時順便將 mic UI reset 返做
  // 「未開始」, 因為 server 端 xiaozhi/disconnect 本身已經會停埋 capture/playback
  // (見 MainActivity#handleXiaozhiApi 嘅 "disconnect" case)。
  if (els.micBtn) els.micBtn.disabled = !(connected && xiaozhiAudioSupported);
  if (!connected) {
    xiaozhiMicActive = false;
    xiaozhiSetMicStatus("xiaozhi_mic_idle");
    if (els.micBtn) els.micBtn.textContent = t("xiaozhi_mic_start");
  }
}

function xiaozhiConnect() {
  const els = xiaozhiElements();
  const url = (els.urlInput.value || "").trim();
  const token = (els.tokenInput.value || "").trim();
  if (!url || !token) {
    showError("小智", new Error(t("xiaozhi_missing_url_or_token")));
    return;
  }
  xiaozhiSetStatus("xiaozhi_status_connecting");
  xiaozhiApi("connect", { url: url, token: token }).then(function (res) {
    if (res.ok) {
      xiaozhiSetStatus("xiaozhi_status_connected", "(" + res.sessionId + ")");
      xiaozhiSetConnectedUi(true);
    } else {
      xiaozhiSetStatus("xiaozhi_status_error");
      xiaozhiSetConnectedUi(false);
    }
  });
}

function xiaozhiDisconnect() {
  xiaozhiApi("disconnect", {}).then(function () {
    xiaozhiSetStatus("xiaozhi_status_disconnected");
    xiaozhiSetConnectedUi(false);
  });
}

/** Toggles the mic on/off - calls "xiaozhi/mic/start" or "xiaozhi/mic/stop"
 *  depending on xiaozhiMicActive. Both endpoints are idempotent/safe to call from a
 *  clean state (see MainActivity#handleXiaozhiApi), so this doesn't need to guard
 *  against double-clicks beyond disabling the button while a request is in flight. */
function xiaozhiToggleMic() {
  const els = xiaozhiElements();
  if (els.micBtn) els.micBtn.disabled = true;
  if (xiaozhiMicActive) {
    xiaozhiApi("mic/stop", {}).then(function () {
      xiaozhiMicActive = false;
      xiaozhiSetMicStatus("xiaozhi_mic_idle");
      if (els.micBtn) {
        els.micBtn.textContent = t("xiaozhi_mic_start");
        els.micBtn.disabled = false;
      }
    });
  } else {
    xiaozhiApi("mic/start", {}).then(function (res) {
      if (res.ok) {
        xiaozhiMicActive = true;
        xiaozhiSetMicStatus("xiaozhi_mic_active");
        if (els.micBtn) els.micBtn.textContent = t("xiaozhi_mic_stop");
      } else {
        xiaozhiSetMicStatus("xiaozhi_mic_error");
      }
      if (els.micBtn) els.micBtn.disabled = false;
    });
  }
}

/** Appends one chat-log line. roleClass drives the bubble's CSS styling
 *  (xiaozhi-msg-user/xiaozhi-msg-assistant/xiaozhi-msg-system - see style.css). */
function xiaozhiAppendChatLine(roleClass, text) {
  const log = xiaozhiElements().chatLog;
  if (!log) return;
  const line = document.createElement("div");
  line.className = "log-line xiaozhi-msg " + roleClass;
  line.innerHTML = "<span class=\"log-time\">[" + nowTimeStr() + "]</span> " + escapeHtml(text);
  log.appendChild(line);
  while (log.childElementCount > MAX_LOG_LINES) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

/** Called from appendLog() in app-log.js for every xiaozhi_* WebSocket event -
 *  kept as a single entry point (rather than each xiaozhi_* type having its own
 *  "if (msg.type === ...)" block inline in app-log.js) so all the XiaoZhi-specific
 *  rendering logic lives in this file instead of being scattered across app-log.js. */
function xiaozhiHandleEvent(type, data) {
  switch (type) {
    case "xiaozhi_state":
      if (data.state === "connecting") {
        xiaozhiSetStatus("xiaozhi_status_connecting");
      } else if (data.state === "connected") {
        xiaozhiSetStatus("xiaozhi_status_connected", data.sessionId ? "(" + data.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
      } else if (data.state === "disconnected") {
        xiaozhiSetStatus("xiaozhi_status_disconnected");
        xiaozhiSetConnectedUi(false);
        if (data.reason) {
          xiaozhiAppendChatLine("xiaozhi-msg-system", data.reason);
        }
      } else if (data.state === "error") {
        xiaozhiSetStatus("xiaozhi_status_error");
        xiaozhiSetConnectedUi(false);
        xiaozhiAppendChatLine("xiaozhi-msg-system", data.message || "error");
      }
      break;
    case "xiaozhi_stt":
      if (data.text) xiaozhiAppendChatLine("xiaozhi-msg-user", data.text);
      break;
    case "xiaozhi_llm":
      // "llm" messages in the protocol are emotion/expression hints (see
      // websocket.md 4.2 #3), not the assistant's spoken reply text - that arrives
      // via "tts" sentence_start messages instead (see xiaozhi_tts below). Shown
      // here only when there's actual text (some emotion-only pushes carry just an
      // emoji in "text", which is still worth surfacing).
      if (data.text) xiaozhiAppendChatLine("xiaozhi-msg-system", "😶 " + data.text);
      break;
    case "xiaozhi_tts":
      if (data.state === "sentence_start" && data.text) {
        xiaozhiAppendChatLine("xiaozhi-msg-assistant", data.text);
      }
      break;
    case "xiaozhi_mcp":
      // Verbose/technical - tool discovery and calls. Shown in the chat log as a
      // compact system line rather than a full JSON dump, so the conversation view
      // doesn't get dominated by protocol plumbing; the full payload is still
      // visible in the main event log (eventLog) since appendLog() in app-log.js
      // logs every WebSocket message there regardless of type.
      if (data.payload && data.payload.method) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", "🔧 " + data.payload.method);
      }
      break;
    case "xiaozhi_system":
      xiaozhiAppendChatLine("xiaozhi-msg-system", "⚙ " + data.command);
      break;
    case "xiaozhi_alert":
      xiaozhiAppendChatLine("xiaozhi-msg-system", "⚠ " + data.status + ": " + data.message);
      break;
    default:
      break;
  }
}

/** Runs once at page load: asks the robot whether this Android version can support
 *  the Opus audio path (see XiaozhiClient.isAudioSupported() / handleXiaozhiApi
 *  "supported" endpoint), stores the result in xiaozhiAudioSupported for
 *  xiaozhiSetConnectedUi() to gate the mic button on, and shows/hides the
 *  unsupported-hint text accordingly. */
function xiaozhiCheckSupport() {
  xiaozhiApi("supported", {}).then(function (res) {
    xiaozhiAudioSupported = !!(res.ok && res.audioSupported);
    const notice = xiaozhiElements().unsupportedNotice;
    if (notice) {
      notice.style.display = xiaozhiAudioSupported ? "none" : "block";
    }
    // Re-apply gating in case xiaozhiRefreshStatus() already ran and enabled/disabled
    // the connect-dependent buttons before this (async) support check resolved -
    // whichever of the two finishes last should reflect both conditions correctly.
    const els = xiaozhiElements();
    const alreadyConnected = els.disconnectBtn && !els.disconnectBtn.disabled;
    xiaozhiSetConnectedUi(!!alreadyConnected);
  });
}

/** Runs once at page load: reflects whatever connection state the robot is
 *  already in (e.g. browser tab was reloaded while a XiaoZhi session was still
 *  open from before) rather than always starting the UI in "disconnected". */
function xiaozhiRefreshStatus() {
  xiaozhiApi("status", {}).then(function (res) {
    if (!res.ok) return;
    if (res.connected) {
      xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
      xiaozhiSetConnectedUi(true);
    } else {
      xiaozhiSetStatus("xiaozhi_status_disconnected");
      xiaozhiSetConnectedUi(false);
    }
  });
}

window.addEventListener("DOMContentLoaded", function () {
  xiaozhiCheckSupport();
  xiaozhiRefreshStatus();
});
