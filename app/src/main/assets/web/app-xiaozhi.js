// Open Alpha2 — client logic (app-xiaozhi.js)
// 小智 (XiaoZhi) AI 對話 tab - 連出去 xiaozhi.me 嘅 client-side WebSocket (XiaozhiClient.java
// 喺 server 端做), 呢個檔案負責: 單一開關 (連接/斷開/隨時語音對話三合一)、文字輸入、
// 狀態顯示、將 EventBus 送過嚟嘅 xiaozhi_* WebSocket event 渲染做對話氣泡。
//
// UI 設計: 淨係一個開關 (xiaozhiSessionToggle) - 開 = 連接 + 自動開始聽, 隨時語音
// 對話; 關 = 斷開、放低 mic。撳開嗰刻觸發嘅係一個 OTA/device-activation flow
// (check_version -> 讀出配對碼 -> poll -> 先至真正連 WebSocket), 唔使用戶自己填
// WS URL/token - 呢啲改由 server 經 xiaozhi.me 官方 OTA endpoint 攞返。見
// XiaozhiOtaClient.java / MainActivity#runXiaozhiActivationFlow() class javadoc。
// 連接完成之後自動開埋 server 端嘅 auto_mode (見 xiaozhiPollActivationStatus() 嘅
// "connected" case), 之後每次 TTS 播完都自動再聽一次 (由 server 端
// XiaozhiClient.TtsStateListener 驅動, 呢個檔案唔使自己 poll TTS 狀態)。文字輸入用
// listen state:detect 帶 text (見 XiaozhiClient#sendListenDetectText() 嘅 javadoc,
// 呢個係借用 wake-word-detected 嘅 message shape, 未 100% 官方保證行得通, 實測為準)。
//
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

// 全域 flag, 由 xiaozhiCheckSupport() 喺 page load 設定一次 - 連接咗仲未夠, 呢部機
// 仲要支援 Opus 先真正可以語音對話 (唔支援嘅話 unsupportedNotice 會顯示提示,
// 但單一開關本身唔會因為呢個而 disable, 純文字對話仍然用得)。
let xiaozhiAudioSupported = false;
let xiaozhiMicActive = false;
let xiaozhiAutoModeOn = false;
// Poll timer handle for the activation flow (checking/awaiting_code/polling/
// connecting stages) - cleared once CONNECTED or ERROR is reached. Kept as a module
// -level variable (not a closure-local) so xiaozhiDisconnect()/a page reload mid
// -activation can't leave a stray setTimeout still firing after the fact.
let xiaozhiActivationPollTimer = null;

function xiaozhiElements() {
  return {
    sessionToggle: document.getElementById("xiaozhiSessionToggle"),
    statusBadge: document.getElementById("xiaozhiStatusBadge"),
    chatLog: document.getElementById("xiaozhiChatLog"),
    unsupportedNotice: document.getElementById("xiaozhiUnsupportedNotice"),
    activationBox: document.getElementById("xiaozhiActivationBox"),
    activationCode: document.getElementById("xiaozhiActivationCode"),
    micLed: document.getElementById("xiaozhiMicLed"),
    micStatusBadge: document.getElementById("xiaozhiMicStatusBadge"),
    textInput: document.getElementById("xiaozhiTextInput"),
    sendTextBtn: document.getElementById("xiaozhiSendTextBtn"),
  };
}

function xiaozhiSetStatus(stateKey, extraText) {
  const el = xiaozhiElements().statusBadge;
  if (!el) return;
  el.textContent = t(stateKey) + (extraText ? " " + extraText : "");
}

/** 反映 mic 擁有權燈號 - 綠色 = 呢個 app 而家攞住 mic (releaseMicForAudioIo() 已生效,
 *  語音對話用緊), 灰色 = 已放低俾機械人自己嘅 wake-word 引擎。同 xiaozhiMicActive
 *  呢個純 UI flag 唔同 - 呢個燈號反映嘅係 server 端 xiaozhiMicHeld 嘅真實狀態
 *  (見 MainActivity#startXiaozhiMic()/stopXiaozhiMic() 同 XIAOZHI_MIC_STATE_EVENT)。 */
function xiaozhiSetMicLed(held) {
  const els = xiaozhiElements();
  if (els.micLed) {
    els.micLed.classList.toggle("led-dot-on", held);
    els.micLed.classList.toggle("led-dot-off", !held);
  }
  if (els.micStatusBadge) {
    els.micStatusBadge.textContent = t(held ? "xiaozhi_mic_held" : "xiaozhi_mic_released");
  }
}

// 一個開關代表成個 session 嘅狀態: 開 = 已連接 (連線 + auto_mode/mic 隨時語音對話),
// 關 = 未連接。呢個 function 淨係反映開關本身同文字輸入嘅 enable 狀態, 唔再有獨立
// mic 掣/badge - 語音對話狀態靠 statusBadge 反映就夠。
function xiaozhiSetConnectedUi(connected) {
  const els = xiaozhiElements();
  if (els.sessionToggle) els.sessionToggle.checked = connected;
  // 文字輸入唔經 mic/Opus, 純文字 message, 淨係要連接咗就得 - 見
  // MainActivity#handleXiaozhiApi 嘅 "send_text" case 嘅 comment。
  if (els.sendTextBtn) els.sendTextBtn.disabled = !connected;
  if (!connected) {
    xiaozhiMicActive = false;
    xiaozhiSetMicLed(false);
  }
}

let xiaozhiLastShownActivationCode = null;

/** 2026-08 新增: 除咗獨立嘅 xiaozhiActivationBox (喺對話 log 之上, 大字顯示), 而家
 *  都會將配對碼 append 一句落 xiaozhiChatLog 本身, 等佢真正出現喺「對話界面」入面
 *  (用戶話之前「淨係 websocket log 出現」, 想要嘅係界面上睇得到) - 用
 *  xiaozhiLastShownActivationCode 呢個 module-level flag 防止同一個 code 被重複
 *  poll 到就重複插入多次 (activation_status 輪詢期間會不斷攞到同一個 code)。
 *
 *  2026-08 再新增: 曾經加多一重全屏彈出視窗 (xiaozhiActivationModalOverlay) 同
 *  window.alert() 做保底, 不理而家開緊邊個 tab 都強制顯示。
 *
 *  2026-08 精簡: 用戶確認配對碼終於 4 個地方都顯示到 (activationBox 小格、
 *  modal 全屏彈窗、window.alert()、對話界面), 但要求淨係留低「對話界面」呢個,
 *  其餘 3 個 (小格 display:block、modal、alert) 已經拎走 - 呢度已經唔再更新
 *  els.activationBox 嘅 display, 個小格會一直保持 CSS 預設嘅 display:none。 */
function xiaozhiShowActivationCode(code) {
  const els = xiaozhiElements();
  if (els.activationCode) els.activationCode.textContent = code || "";
  if (code && code !== xiaozhiLastShownActivationCode) {
    xiaozhiLastShownActivationCode = code;
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_activation_code_chat_prefix") + " " + code);
  }
}

/** 2026-08 更新: 之前呢度有全屏 modal overlay + window.alert() 兩重保底方案
 *  (因為當時 WebView position:fixed quirk 令個 overlay 睇唔到), 但而家配對碼
 *  已經證實會經 EventBus -> WebSocket -> 對話界面 (xiaozhiAppendChatLine) 正常
 *  顯示, 用戶反映淨係想留低對話界面呢個, 其餘 3 個 (進階小格 activationBox、
 *  modal overlay、window.alert()) 已經拿走 - 呢個 function 而家留空, 冚低唔再
 *  用, 純粹保留舊 comment 做紀錄, 避免第日又摷返出嚟。 */

function xiaozhiHideActivationModal() {
  const overlay = document.getElementById("xiaozhiActivationModalOverlay");
  if (overlay) overlay.style.display = "none";
}

function xiaozhiHideActivationCode() {
  const els = xiaozhiElements();
  if (els.activationBox) els.activationBox.style.display = "none";
  if (els.activationCode) els.activationCode.textContent = "";
  xiaozhiLastShownActivationCode = null;
  xiaozhiHideActivationModal();
}

function xiaozhiStopActivationPolling() {
  if (xiaozhiActivationPollTimer) {
    clearTimeout(xiaozhiActivationPollTimer);
    xiaozhiActivationPollTimer = null;
  }
}

/** Kicks off the OTA/device-activation flow (see MainActivity#handleXiaozhiApi's
 *  "connect" case) and starts polling "xiaozhi/activation_status" to follow its
 *  progress through checking -> (optionally) awaiting_code/polling -> connecting ->
 *  connected, updating the status badge and activation-code box at each stage. Once
 *  connected, xiaozhiPollActivationStatus()'s "connected" branch turns auto_mode on
 *  so mic capture starts immediately without a separate button (see single-toggle
 *  design in xiaozhiToggleSession()). */
function xiaozhiConnect() {
  xiaozhiHideActivationCode();
  xiaozhiSetStatus("xiaozhi_status_checking");
  xiaozhiApi("connect", {}).then(function (res) {
    if (!res.ok) {
      xiaozhiSetStatus("xiaozhi_status_error");
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || "connect failed");
      xiaozhiSetConnectedUi(false);
      return;
    }
    xiaozhiPollActivationStatus();
  });
}

function xiaozhiPollActivationStatus() {
  xiaozhiStopActivationPolling();
  xiaozhiApi("activation_status", {}).then(function (res) {
    if (!res.ok) {
      xiaozhiSetStatus("xiaozhi_status_error");
      return;
    }
    switch (res.stage) {
      case "checking":
        xiaozhiSetStatus("xiaozhi_status_checking");
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 1000);
        break;
      case "awaiting_code":
      case "polling":
        xiaozhiSetStatus("xiaozhi_status_awaiting_code");
        xiaozhiShowActivationCode(res.code);
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 2000);
        break;
      case "connecting":
        xiaozhiSetStatus("xiaozhi_status_connecting");
        xiaozhiActivationPollTimer = setTimeout(xiaozhiPollActivationStatus, 1000);
        break;
      case "connected":
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
        // 單一開關嘅設計: 一連接好就即刻開埋 auto_mode, 等於自動搶 mic、隨時語音
        // 對話, 唔使用戶再撳多一下 - 見 index.html 個開關 label。
        xiaozhiAutoModeOn = true;
        xiaozhiApi("auto_mode", { enabled: "true" });
        break;
      case "error":
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_error");
        xiaozhiSetConnectedUi(false);
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || "activation failed");
        break;
      case "idle":
      default:
        // Nothing in progress - stop polling rather than spinning forever; a fresh
        // xiaozhiConnect() call will restart polling from "checking".
        break;
    }
  });
}

function xiaozhiDisconnect() {
  xiaozhiStopActivationPolling();
  xiaozhiHideActivationCode();
  // Reflects server-side behaviour: MainActivity#handleXiaozhiApi's "disconnect" case
  // clears auto_mode itself, so the local flag should reset here too rather than
  // staying true against a session that's about to go away.
  xiaozhiAutoModeOn = false;
  xiaozhiApi("disconnect", {}).then(function () {
    xiaozhiSetStatus("xiaozhi_status_disconnected");
    xiaozhiSetConnectedUi(false);
  });
}

/** 單一開關: 開 = 連接 (內部觸發 OTA/activation flow, 完成後自動開 auto_mode 搶
 *  mic, 隨時語音對話), 關 = 斷開 (auto_mode 同 mic 一齊停)。取代之前分開嘅
 *  連接/斷開/小智常開/開始語音對話四個掣 - 用戶淨係要理解「開就用得, 閂就唔用」。
 *  開關本身即時反映用戶操作嘅意圖; 真正嘅連接狀態由 xiaozhiPollActivationStatus()/
 *  xiaozhiHandleEvent() 嘅 xiaozhi_state 事件驅動, 如果連接失敗會經
 *  xiaozhiSetConnectedUi(false) 將開關撥返轉。 */
function xiaozhiToggleSession() {
  const els = xiaozhiElements();
  const wantOn = !!(els.sessionToggle && els.sessionToggle.checked);
  if (wantOn) {
    xiaozhiConnect();
  } else {
    xiaozhiDisconnect();
  }
}

/** PHASE 4 (text input): sends whatever's typed in the text box as a "detect" message
 *  (see XiaozhiClient#sendListenDetectText()'s javadoc for the protocol caveat this
 *  relies on) rather than through the mic/Opus path - works regardless of
 *  xiaozhiAudioSupported since no audio codec is involved.
 *
 *  Bug fix (2026-08): this used to optimistically append the typed text to the chat
 *  log immediately on a successful send_text response - but the xiaozhi.me server
 *  also echoes the same text back as a "stt" message (see XiaozhiClient's EVT_STT
 *  publish in handleTextMessage()), which xiaozhiHandleEvent's "xiaozhi_stt" case
 *  renders too, so every typed message showed up twice. The server echo is now the
 *  single source of truth for what appears in the chat log - this function only
 *  clears the input box and re-enables the button, it does not append anything
 *  itself. */
function xiaozhiSendText() {
  const els = xiaozhiElements();
  const text = els.textInput ? (els.textInput.value || "").trim() : "";
  if (!text) return;
  // 2026-08 新增防禦性檢查: 真機 logcat 證實撞過一次「送出去嘅 text 竟然係個
  // placeholder hint 文案本身」(xiaozhi_phase4_hint 嗰句長 UI 說明文字), 而唔係
  // 用戶真正打嘅嘢 - 個 input 本身冇任何 JS 會將呢句寫入 .value (全局搜過, 淨係
  // applyUiLanguage() 會寫 placeholder 屬性, 唔會寫 .value), 懷疑係 Android
  // WebView 嘅表單 autofill/記憶機制將 placeholder 誤當建議值填咗入去。已經加
  // autocomplete="off" 落個 input 減少呢個機會, 但呢度加多一重保險: 如果攞到嘅
  // 文字同 placeholder 本身嘅翻譯完全一致, 當呢個唔係用戶主動打嘅內容, 唔送出去,
  // 提示用戶重新打字, 避免將一大段 UI 說明文字誤送去做 XiaoZhi 對話輸入。
  if (text === t("xiaozhi_text_placeholder")) {
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_send_text_error"));
    if (els.textInput) els.textInput.value = "";
    return;
  }
  if (els.sendTextBtn) els.sendTextBtn.disabled = true;
  xiaozhiApi("send_text", { text: text }).then(function (res) {
    if (res.ok) {
      if (els.textInput) els.textInput.value = "";
    } else {
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_send_text_error"));
    }
    if (els.sendTextBtn) els.sendTextBtn.disabled = !xiaozhiClientIsConnected();
  });
}

/** 2026-08 新增: 總停鍵 - 一次過中斷機械人依家可能正在做緊嘅三件事: 動作播放
 *  (action/stop)、TTS (speech/stop, 機身 Nuance/iflytek 同 Android TTS 都包埋喺
 *  呢一個 endpoint 入面, 見 handleApi() 嘅 "speech/stop" case)、本地音樂
 *  (audio/local_music/stop)。用 api() 唔係 xiaozhiApi() - 呢三個 endpoint 屬於
 *  alpha2/lynx 呢個 backend-specific namespace (見 handleApi()), 唔係
 *  handleXiaozhiApi() 嗰個 backend-agnostic "xiaozhi/" namespace, 跟返 app.js 其他
 *  地方叫呢類 endpoint 嘅一致做法。
 *
 *  三個 request 用 Promise.all 同時發出 (唔係逐個 await), 理由: (1) 呢三件事本身
 *  互不相干, 冧一個唔應該延遲另外兩個開始執行嘅時間; (2) 用戶撳呢個掣通常係想
 *  「即刻閂咀」, 反應時間敏感, 逐個 sequential 送會令總體延遲變成三個 request
 *  時間之和。單一 request 失敗 (例如冇某個 backend 支援) 唔應該影響其餘兩個 -
 *  api() 本身已經喺 network/non-ok response 個 case 自己處理咗 showError(), 呢度
 *  唔使額外再包一層 try/catch。 */
function xiaozhiStopAll() {
  Promise.all([
    api("action/stop"),
    api("speech/stop"),
    api("audio/local_music/stop"),
    api("audio/radio/stop"), // 2026-08 新增: FM/網絡電台都係「播放中」嘅一種,
                              // 跟返本地音樂一齊納入呢個總停鍵。
  ]);
}

/** Cheap local read of the last-known connected state via the session toggle's
 *  checked flag (set by xiaozhiSetConnectedUi()) - avoids a redundant round-trip to
 *  "xiaozhi/status" just to decide whether to re-enable the send button after a
 *  send_text call. */
function xiaozhiClientIsConnected() {
  const els = xiaozhiElements();
  return !!(els.sessionToggle && els.sessionToggle.checked);
}

/** Appends one chat-log line. roleClass drives the bubble's CSS styling
 *  (xiaozhi-msg-user/xiaozhi-msg-assistant/xiaozhi-msg-system - see style.css).
 *
 *  2026-08: 時間戳保留 (每句底下細字顯示發送時間), 但「以上內容由 AI 生成」呢句
 *  標註已經拎走 - 用戶話唔要。 */
function xiaozhiAppendChatLine(roleClass, text) {
  const log = xiaozhiElements().chatLog;
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

/** 2026-08 新增: MCP 工具調用卡片 - 跟返用戶提供嘅 xiaozhi.me console「歷史對話」
 *  截圖樣式, 一個可展開嘅「🔧 工具呼叫」卡片, 顯示緊 call 緊邊個 tool、乜嘢參數。
 *  截圖仲有個耗時 (61ms) - 呢度冇跟, 因為 tools/call request 同對應嘅 response
 *  係兩個獨立、冇共同 id 追蹤機制嘅 event (見 XiaozhiClient 個 EVT_MCP publish),
 *  要準確計耗時需要額外一層 id->timestamp 對應邏輯, 複雜度同呢個功能嘅價值唔成
 *  正比, 淨係顯示「呼叫緊邊個工具、乜嘢參數」已經足夠俾用戶睇到發生緊咩事。 */
function xiaozhiAppendMcpToolCallCard(toolName, argsObj) {
  const log = xiaozhiElements().chatLog;
  if (!log) return;
  const card = document.createElement("div");
  card.className = "xiaozhi-mcp-card";
  const header = document.createElement("div");
  header.className = "xiaozhi-mcp-card-header";
  header.textContent = "🔧 " + t("xiaozhi_mcp_call_label");
  const body = document.createElement("div");
  body.className = "xiaozhi-mcp-card-body";
  let argsStr = "{}";
  try { argsStr = JSON.stringify(argsObj || {}); } catch (e) { /* leave default */ }
  body.textContent = toolName + "(" + argsStr + ")";
  card.appendChild(header);
  card.appendChild(body);
  log.appendChild(card);
  while (log.childElementCount > MAX_LOG_LINES) {
    log.removeChild(log.firstChild);
  }
  log.scrollTop = log.scrollHeight;
}

/** Called from appendLog() in app-log.js for every xiaozhi_* WebSocket event -
 *  kept as a single entry point (rather than each xiaozhi_* type having its own
 *  "if (msg.type === ...)" block inline in app-log.js) so all the XiaoZhi-specific
 *  rendering logic lives in this file instead of being scattered across app-log.js.
 *  Note: xiaozhi_state events fire from XiaozhiClient's own WebSocket lifecycle,
 *  which is a *different* signal from the activation_status polling above - both are
 *  kept (rather than replacing polling with purely event-driven updates) because the
 *  activation flow's "checking"/"awaiting_code"/"polling"/"connecting" stages all
 *  happen *before* XiaozhiClient's WebSocket exists to emit any state at all. */
function xiaozhiHandleEvent(type, data) {
  switch (type) {
    case "xiaozhi_state":
      if (data.state === "disconnected") {
        xiaozhiStopActivationPolling();
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_disconnected");
        xiaozhiSetConnectedUi(false);
        if (data.reason) {
          xiaozhiAppendChatLine("xiaozhi-msg-system", data.reason);
        }
      } else if (data.state === "error") {
        xiaozhiStopActivationPolling();
        xiaozhiHideActivationCode();
        xiaozhiSetStatus("xiaozhi_status_error");
        xiaozhiSetConnectedUi(false);
        xiaozhiAppendChatLine("xiaozhi-msg-system", data.message || "error");
      }
      break;
    // 對話畫面淨係顯示真正嘅對話內容 (用戶講/打字 + 小智回覆), 靠右/靠左分色 - 見
    // xiaozhiAppendChatLine() 同 style.css 嘅 .xiaozhi-msg-user/.xiaozhi-msg-assistant。
    // MCP 工具調用 (xiaozhi_mcp)、emotion hint (xiaozhi_llm)、system 指令
    // (xiaozhi_system) 呢啲協議層雜訊唔會再入對話 log - 完整內容仍然入緊主 event
    // log (eventLog, 見 app-log.js 嘅 appendLog()), 淨係唔顯示喺呢個對話畫面。
    // xiaozhi_alert 例外: 呢個係伺服器主動推送嘅警示 (例如電量不足), 用戶應該
    // 喺對話畫面見到, 所以保留。
    case "xiaozhi_stt":
      if (data.text) xiaozhiAppendChatLine("xiaozhi-msg-user", data.text);
      break;
    case "xiaozhi_tts":
      if (data.state === "sentence_start" && data.text) {
        xiaozhiAppendChatLine("xiaozhi-msg-assistant", data.text);
      }
      // "stop" drives auto-continue server-side (XiaozhiClient.TtsStateListener) -
      // mic automatically re-starts there, nothing to do here beyond the chat line
      // above.
      if (data.state === "stop" && xiaozhiAutoModeOn) {
        xiaozhiMicActive = true;
      }
      break;
    case "xiaozhi_alert":
      xiaozhiAppendChatLine("xiaozhi-msg-system", "⚠ " + data.status + ": " + data.message);
      break;
    // 2026-08 新增: 之前呢個 case 完全冇處理, tools/call request 純粹入主
    // event log, 對話畫面睇唔到觸發緊咩工具 - 加返呢個 case, 淨係揀
    // direction:"in" 且 method:"tools/call" 嘅 payload (即係 server 真正要求
    // 執行一個工具嗰刻, 而唔係 initialize/tools/list 呢啲協議雜訊, 亦唔係
    // response), 插入一張「工具呼叫」卡片。
    case "xiaozhi_mcp": {
      const payload = data.payload;
      if (data.direction === "in" && payload && payload.method === "tools/call"
          && payload.params && payload.params.name) {
        xiaozhiAppendMcpToolCallCard(payload.params.name, payload.params.arguments);
      }
      break;
    }
    // Server-side mic-ownership state (see MainActivity#startXiaozhiMic()/
    // stopXiaozhiMic()'s XIAOZHI_MIC_STATE_EVENT publish) - drives the green/grey
    // LED so the person can see whether this app actually holds the mic hardware,
    // separate from xiaozhiMicActive (a UI-only flag) or xiaozhi_tts's auto-continue
    // bookkeeping above.
    case "xiaozhi_mic_state":
      xiaozhiSetMicLed(!!data.held);
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
    xiaozhiSetConnectedUi(xiaozhiClientIsConnected());
  });
}

/** Runs once at page load: reflects whatever connection/auto-mode/mic state the
 *  robot is already in (e.g. browser tab was reloaded while a XiaoZhi session was
 *  already active from before) rather than always starting the UI in "disconnected". */
function xiaozhiRefreshStatus() {
  xiaozhiApi("status", {}).then(function (res) {
    if (!res.ok) return;
    if (res.connected) {
      xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
      xiaozhiSetConnectedUi(true);
      xiaozhiAutoModeOn = !!res.autoMode;
      xiaozhiMicActive = !!res.micActive;
      xiaozhiSetMicLed(!!res.micHeld);
      return;
    }
    // Not connected yet - check whether an activation attempt is still in flight
    // (e.g. the person reloaded the page while waiting to enter the code) and resume
    // polling it rather than showing a plain "disconnected" that would make them
    // think they need to press Connect again mid-pairing.
    xiaozhiApi("activation_status", {}).then(function (actRes) {
      if (actRes.ok && (actRes.stage === "checking" || actRes.stage === "awaiting_code"
          || actRes.stage === "polling" || actRes.stage === "connecting")) {
        xiaozhiPollActivationStatus();
      } else {
        xiaozhiSetStatus("xiaozhi_status_disconnected");
        xiaozhiSetConnectedUi(false);
      }
    });
  });
}

/** 2026-08 新增: 背景常駐輪詢, 每 8 秒 check 一次 - 之前 xiaozhiRefreshStatus()
 *  淨係喺 page load 嗰一刻 call 一次, 冇任何持續機制。問題喺於: MainActivity 個
 *  自動重連 (xiaozhiScheduleReconnect() -> runXiaozhiActivationFlow()) 係完全喺
 *  背景 thread 默默進行, 唔會主動通知前端 - 如果重連個陣 device 已經唔再係已激活
 *  狀態 (例如 token 失效、server 側取消咗綁定), runXiaozhiActivationFlow() 會
 *  重新入返 awaiting_code 攞新配對碼, 但個瀏覽器分頁已經開住、冇再 call
 *  xiaozhiPollActivationStatus(), 完全唔會知道有新配對碼要顯示 - 用戶會見到個
 *  開關顯示緊「開」但實際上已經斷咗線、亦冇任何配對碼提示, 唯一方法係手動刷新
 *  成個頁面。呢個背景輪詢等如喺分頁一直開住嘅情況下都持續留意緊呢個狀態轉變,
 *  唔使用戶自己諗到要刷新。同 xiaozhiPollActivationStatus() 嗰個(連接中/配對中
 *  先會出現嘅) 短間隔輪詢唔衝突: 兩者都用 xiaozhiStopActivationPolling() 嚟
 *  清走舊 timer, xiaozhiPollActivationStatus() 見到自己啱啱先啟動咗一個
 *  polling loop 就會蓋過呢度嘅慢速輪詢, 唔會出現重複輪詢。 */
function xiaozhiBackgroundStatusWatch() {
  // 已經有一個 activation polling loop 喺度行緊 (xiaozhiActivationPollTimer 唔係
  // null), 即係話用戶啱啱手動撳咗連接或者已經响 awaiting_code/polling/connecting
  // 度 - 唔使呢度嘅慢速輪詢再插一腳。
  if (!xiaozhiActivationPollTimer) {
    xiaozhiApi("status", {}).then(function (res) {
      if (!res.ok) return;
      if (res.connected) {
        xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
        xiaozhiAutoModeOn = !!res.autoMode;
        xiaozhiMicActive = !!res.micActive;
        xiaozhiSetMicLed(!!res.micHeld);
      } else {
        xiaozhiApi("activation_status", {}).then(function (actRes) {
          if (actRes.ok && (actRes.stage === "checking" || actRes.stage === "awaiting_code"
              || actRes.stage === "polling" || actRes.stage === "connecting")) {
            // 搵到一個背景先至開始咗嘅 activation attempt (通常係自動重連觸發)
            // - 交返俾原本嗰套短間隔輪詢, 等個配對碼/狀態即時反映出嚟。
            xiaozhiPollActivationStatus();
          } else {
            xiaozhiSetConnectedUi(false);
          }
        });
      }
    });
  }
  setTimeout(xiaozhiBackgroundStatusWatch, 8000);
}

/** 讀返家陣自訂 server 設定 (見 MainActivity 嘅 "ota_config/get" endpoint), 反映落
 *  個開關同輸入框 - page load 就要 call, 等用戶見到之前揀咗嘅設定, 唔會每次開個
 *  panel 都變返做未設定過咁。 */
function xiaozhiLoadOtaConfig() {
  xiaozhiApi("ota_config/get", {}).then(function (res) {
    if (!res.ok) return;
    const toggle = document.getElementById("xiaozhiOtaCustomToggle");
    const box = document.getElementById("xiaozhiOtaCustomBox");
    const urlInput = document.getElementById("xiaozhiOtaCustomUrl");
    const wsInput = document.getElementById("xiaozhiWsUrlOverride");
    const deviceIdInput = document.getElementById("xiaozhiDeviceIdOverride");
    const tokenInput = document.getElementById("xiaozhiTokenOverride");
    if (toggle) toggle.checked = !!res.customEnabled;
    if (box) box.style.display = res.customEnabled ? "" : "none";
    if (urlInput && res.customUrl) urlInput.value = res.customUrl;
    if (wsInput && res.wsUrlOverride) wsInput.value = res.wsUrlOverride;
    if (deviceIdInput && res.deviceIdOverride) deviceIdInput.value = res.deviceIdOverride;
    if (tokenInput && res.tokenOverride) tokenInput.value = res.tokenOverride;
  });
}

/** 開關切換 - 開就顯示輸入框等用戶填 URL (未即刻儲存, 要撳「儲存」先真正生效,
 *  見 xiaozhiSaveOtaCustom()); 關就即刻儲存 (跟返官方 xiaozhi.me, 唔使等用戶
 *  額外撳嘢) 並隱藏輸入框。 */
function xiaozhiToggleOtaCustom() {
  const toggle = document.getElementById("xiaozhiOtaCustomToggle");
  const box = document.getElementById("xiaozhiOtaCustomBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (!wantOn) {
    xiaozhiApi("ota_config/set", { enabled: "false" }).then(function (res) {
      if (!res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
      }
    });
  }
  // wantOn=true 嗰陣淨係顯示個輸入框, 唔即刻儲存 - 等用戶真正填咗 url 撳「儲存」
  // 先送出去, 避免用戶淨係撳一下開關 (url 仲係空) 就已經觸發 ota_config/set
  // 令下次連接冇 url 可用。
}

/** 「儲存」掣 - 送出 OTA URL 同三個可選 override (WebSocket 位址/MAC/Token,
 *  全部留空 = 跟返自動流程, 見 MainActivity 嘅 "ota_config/set" endpoint
 *  comment)、enabled=true。嗰個 endpoint 本身會拒絕喺已連接狀態下更改同驗證
 *  格式, 所以呢度嘅錯誤處理主要係將 server 已經做咗嘅驗證結果話俾用戶知, 而唔係
 *  重複驗證邏輯。 */
function xiaozhiSaveOtaCustom() {
  const urlInput = document.getElementById("xiaozhiOtaCustomUrl");
  const wsInput = document.getElementById("xiaozhiWsUrlOverride");
  const deviceIdInput = document.getElementById("xiaozhiDeviceIdOverride");
  const tokenInput = document.getElementById("xiaozhiTokenOverride");
  const url = urlInput ? (urlInput.value || "").trim() : "";
  const wsUrl = wsInput ? (wsInput.value || "").trim() : "";
  const deviceId = deviceIdInput ? (deviceIdInput.value || "").trim() : "";
  const token = tokenInput ? (tokenInput.value || "").trim() : "";
  if (!url) {
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_ota_custom_url_required"));
    return;
  }
  xiaozhiApi("ota_config/set", { enabled: "true", url: url, wsUrl: wsUrl, deviceId: deviceId, token: token })
    .then(function (res) {
      if (res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_ota_custom_saved"));
      } else {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
        // Server 拒絕咗 (例如連接緊、url 格式錯) - 將開關撥返做 checked 状态保留
        // (用戶睇到個輸入框仲喺度可以改), 唔使自動閂番個開關嚇親人, 淨係話俾佢知
        // 原因, 等佢自己決定係咪要改個 url 或者先斷開連接。
      }
    });
}

/** 內置MCP功能列表 card - 頂部一個「展開」開關 (跟自訂 server card 嗰個
 *  toggle-switch 樣式), 開＝展開成個工具清單 (連逐項 enable/disable 掣一齊
 *  顯示), 關＝成個 box 收埋晒, 乜都睇唔到。清單內容讀寫經 MainActivity 嘅
 *  "mcp_config/set"/"mcp_tools/list" (mcp_tools/list 攞完整清單連逐項 enabled
 *  狀態, mcp_config/set 寫單一 tool 嘅 enabled 狀態)。首次展開先去攞清單, 之後
 *  收返埋就淨係隱藏, 唔會清走已載入嘅資料, 咁樣再展開唔使等重新載入。 */
function xiaozhiToggleMcpExpandAll() {
  const toggle = document.getElementById("xiaozhiMcpExpandToggle");
  const box = document.getElementById("xiaozhiMcpToolsBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (wantOn) xiaozhiLoadMcpTools();
}

function xiaozhiToggleMcpTool(toolName, checkbox) {
  const wantOn = !!(checkbox && checkbox.checked);
  xiaozhiApi("mcp_config/set", { tool: toolName, enabled: wantOn ? "true" : "false" }).then(function (res) {
    if (!res.ok) {
      // Server 拒絕咗 - 撥返個 checkbox 做返之前個狀態, 唔好留低一個「睇落改咗
      // 但其實冇生效」嘅假象。
      if (checkbox) checkbox.checked = !wantOn;
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_mcp_tools_error"));
    }
  });
}

function xiaozhiLoadMcpTools() {
  const box = document.getElementById("xiaozhiMcpToolsList");
  if (!box) return;
  box.textContent = t("xiaozhi_mcp_tools_loading");
  xiaozhiApi("mcp_tools/list", {}).then(function (res) {
    if (!res.tools || !Array.isArray(res.tools) || res.tools.length === 0) {
      box.textContent = t("xiaozhi_mcp_tools_empty");
      return;
    }
    box.innerHTML = "";
    box.removeAttribute("data-i18n");
    res.tools.forEach(function (tool) {
      const wrapper = document.createElement("div");
      wrapper.style.borderBottom = "1px solid var(--border)";
      wrapper.style.padding = "6px 0";

      const item = document.createElement("div");
      item.className = "row";
      item.style.alignItems = "center";

      const name = document.createElement("div");
      name.style.flex = "1";
      name.style.fontWeight = "600";
      name.textContent = tool.name || "";

      const switchLabel = document.createElement("label");
      switchLabel.className = "switch-label";
      const switchSpan = document.createElement("span");
      switchSpan.className = "toggle-switch";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = !!tool.enabled;
      checkbox.onchange = function () { xiaozhiToggleMcpTool(tool.name, checkbox); };
      const track = document.createElement("span");
      track.className = "toggle-track";
      switchSpan.appendChild(checkbox);
      switchSpan.appendChild(track);
      switchLabel.appendChild(switchSpan);

      item.appendChild(name);
      item.appendChild(switchLabel);

      const desc = document.createElement("div");
      desc.className = "hint";
      desc.textContent = tool.description || "";
      desc.style.marginTop = "4px";

      wrapper.appendChild(item);
      wrapper.appendChild(desc);
      box.appendChild(wrapper);
    });
  }).catch(function () {
    box.textContent = t("xiaozhi_mcp_tools_error");
  });
}

/** Page load 嗰陣淨係初始化開關狀態 (預設收埋, 唔打 API) - 展開清單要用戶自己
 *  撳開個 toggle 先觸發 xiaozhiLoadMcpTools()。 */
function xiaozhiLoadMcpConfig() {
  // 冇要讀嘅 persisted 狀態 - 個 toggle 純粹控制展開/收埋嘅 UI, 每次開頁都預設
  // 收埋 (同自訂 server card 一致), 唔記住上次狀態。
}

window.addEventListener("DOMContentLoaded", function () {
  xiaozhiCheckSupport();
  xiaozhiRefreshStatus();
  xiaozhiLoadOtaConfig();
  xiaozhiLoadMcpConfig();
  setTimeout(xiaozhiBackgroundStatusWatch, 8000);
});
