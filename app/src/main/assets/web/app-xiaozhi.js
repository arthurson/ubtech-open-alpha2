// Open Alpha2 — client logic (app-xiaozhi.js)
// 小智 (XiaoZhi) AI 對話 tab - 連出去 xiaozhi.me 的 client-side WebSocket (XiaozhiClient.java
// 在 server 端做), 這個檔案負責: 單一開關 (連接/斷開/隨時語音對話三合一)、文字輸入、
// 狀態顯示、將 EventBus 送過來的 xiaozhi_* WebSocket event 渲染做對話氣泡。
//
// UI 設計: 僅一個開關 (xiaozhiSessionToggle) - 開 = 連接 + 自動開始聽, 隨時語音
// 對話; 關 = 斷開、釋放 mic。按開那刻觸發的是一個 OTA/device-activation flow
// (check_version -> 讀出配對碼 -> poll -> 先至真正連 WebSocket), 不用用戶自己填
// WS URL/token - 這些改由 server 經 xiaozhi.me 官方 OTA endpoint 取回。見
// XiaozhiOtaClient.java / MainActivity#runXiaozhiActivationFlow() class javadoc。
// 連接完成之後自動一併開啟 server 端的 auto_mode (見 xiaozhiPollActivationStatus() 的
// "connected" case), 之後每次 TTS 播完都自動再聽一次 (由 server 端
// XiaozhiClient.TtsStateListener 驅動, 這個檔案不用自己 poll TTS 狀態)。文字輸入用
// listen state:detect 帶 text (見 XiaozhiClient#sendListenDetectText() 的 javadoc,
// 這個是借用 wake-word-detected 的 message shape, 未 100% 官方保證行得通, 實測為準)。
//
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。
//
// 依賴 app-log.js 的 escapeHtml()/nowTimeStr()/MAX_LOG_LINES (雖然 app-log.js 在
// index.html 裡面排在這個檔案之後 load) - 安全的原因: 這裡所有用到這幾個
// identifier 的地方都在 function body 裡面 (xiaozhiAppendChatLine()), 不是 module
// top-level 直接執行, 實際 call 到當時全部 <script> 都已經 load 完 (DOMContentLoaded
// 之後先有用戶操作/WebSocket event 觸發這些 function)。如果之後要在這個檔案的
// top-level (就是函數外面) 直接用這幾個 identifier, 就必須將 <script src="app-log.js">
// 搬到這個檔案之前, 否則會 ReferenceError。

// api()/hwApi() 都是 alpha2/lynx 專屬 (加 "alpha2/"/"lynx/" 前綴) - 小智這個
// namespace 在 server 端是 backend-agnostic ("/api/xiaozhi/...", 見
// MainActivity#handleXiaozhiApi), 所以這裡自己起一個, 不跟 api()/hwApi() 那種
// backend 前綴邏輯。
function xiaozhiApi(path, params) {
  clearError();
  // 實驗 tab 面板 token：同 api()/sysApi() 一樣自動帶（withPanelToken 住 app-core.js；
  // boot_voice/set 啟用中要驗，不帶即 401）。ota_config/set 個 token 是小智那邊的，
  // 正在用同一個 withPanelToken 但 key 是 panel_token，不會撞（見 PanelAuth）。
  const merged = (typeof withPanelToken === "function") ? withPanelToken(params) : params;
  const qs = merged ? "?" + new URLSearchParams(merged).toString() : "";
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

// 全域 flag, 由 xiaozhiCheckSupport() 在 page load 設定一次 - 連接了還未夠, 這部機
// 還要支援 Opus 先真正可以語音對話 (不支援的話 unsupportedNotice 會顯示提示,
// 但單一開關本身不會因為這個而 disable, 純文字對話仍然用得)。
let xiaozhiAudioSupported = false;
let xiaozhiMicActive = false;
let xiaozhiAutoModeOn = false;
// TTS 輸出引擎選擇 - "xiaozhi" (預設) = 原本行為, server 送 opus 聲, 由後端
// XiaozhiAudioController 解碼播放; 選 "iflytek"/"nuance"/"android" 就後端會
// 靜音那段 opus (見 MainActivity.xiaozhiTtsEngine field javadoc), 這裡改用
// xiaozhiTtsQueue 排隊, 逐句在 xiaozhi_tts 的 "sentence_start" 到那刻 call 本地
// speech/tts API (同 app-speech.js 個 speakTts() 正在用那個 API 一樣) 讀出。
// 頁面載入時由 xiaozhiLoadTtsConfig() 讀回後端存低的上次選擇, 同步這個變數同
// 按鈕 active 狀態。
// Poll timer handle for the activation flow (checking/awaiting_code/polling/
// connecting stages) - cleared once CONNECTED or ERROR is reached. Kept as a module
// -level variable (not a closure-local) so xiaozhiDisconnect()/a page reload mid
// -activation can't leave a stray setTimeout still firing after the fact.
let xiaozhiActivationPollTimer = null;

// 本地 TTS 引擎讀小智回覆的句子隊列 - 見 xiaozhiHandleEvent() 個 "xiaozhi_tts"
// case comment。一段回應可能連續觸發多次 "sentence_start" (相隔可能得百幾
// ms), 如果一到就即刻 stop 上一句再讀新一句, 上一句還未讀完就會被人腰斬
// (「iFlytek 只能講到頭幾隻字」, nuance/android 沒有事純粹是剛巧個 gap 未撞到)。
// 現在改做排隊: 新句入隊尾, 僅在沒有東西正正在播當時先即刻讀; 正在讀當時新句僅
// 入隊, 等 tts_end event (後端 robot-side TTS 的 onServerPlayEnd / Android
// TTS 的 UtteranceProgressListener 都會 publish, 見 MainActivity 兩處
// comment) 話這句讀完先讀下一句。
let xiaozhiTtsQueue = [];
let xiaozhiTtsSpeaking = false;

/** 選了本地 TTS 引擎當時, 將一句小智回覆入隊 - 沒有東西正正在播就即刻讀, 否則排在
 *  隊尾等 xiaozhiProcessTtsQueue() (由 tts_end event 觸發) 輪到它。 */
function xiaozhiEnqueueTts(text) {
  xiaozhiTtsQueue.push(text);
  if (!xiaozhiTtsSpeaking) {
    xiaozhiProcessTtsQueue();
  }
}

/** 讀隊頭一句 (如果有) - xiaozhiTtsSpeaking 在這裡設做 true, 等對應那句的
 *  tts_end event 回來先清除做 false 再讀下一句 (見 xiaozhiHandleEvent() 個
 *  "tts_end" case)。 */
function xiaozhiProcessTtsQueue() {
  if (xiaozhiTtsQueue.length === 0) {
    xiaozhiTtsSpeaking = false;
    return;
  }
  xiaozhiTtsSpeaking = true;
  const text = xiaozhiTtsQueue.shift();
  Alpha2Api.speechTts( { text: text, engine: xiaozhiTtsEngine });
}

/** 引擎切換 (xiaozhiSetTtsEngine())/斷線都要清空隊列 - 不是就換掉engine之後,
 *  隊列裡面舊引擎正在排的句子會用新引擎來讀, 對不上用戶看到的切換時機。 */
function xiaozhiResetTtsQueue() {
  xiaozhiTtsQueue = [];
  xiaozhiTtsSpeaking = false;
}

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

/** 反映 mic 擁有權燈號 - 綠色 = 這個 app 現在拿住 mic (releaseMicForAudioIo() 已生效,
 *  語音對話正在用), 灰色 = 已釋放給機械人自己的 wake-word 引擎。同 xiaozhiMicActive
 *  這個純 UI flag 不同 - 這個燈號反映的是 server 端 xiaozhiMicHeld 的真實狀態
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

// 一個開關代表整個 session 的狀態: 開 = 已連接 (連線 + auto_mode/mic 隨時語音對話),
// 關 = 未連接。這個 function 僅反映開關本身同文字輸入的 enable 狀態, 不再有獨立
// mic 按鈕/badge - 語音對話狀態靠 statusBadge 反映就夠。
function xiaozhiSetConnectedUi(connected) {
  const els = xiaozhiElements();
  if (els.sessionToggle) els.sessionToggle.checked = connected;
  // 文字輸入不經 mic/Opus, 純文字 message, 僅要連接了就得 - 見
  // MainActivity#handleXiaozhiApi 的 "send_text" case 的 comment。
  if (els.sendTextBtn) els.sendTextBtn.disabled = !connected;
  if (!connected) {
    xiaozhiMicActive = false;
    xiaozhiSetMicLed(false);
  }
}

let xiaozhiLastShownActivationCode = null;

/** 配對碼 append 落 xiaozhiChatLog；用 xiaozhiLastShownActivationCode 防同一 code 重複插入 (activation_status 輪詢會不斷拿到同一 code)。els.activationBox 保持 display:none。 */
function xiaozhiShowActivationCode(code) {
  const els = xiaozhiElements();
  if (els.activationCode) els.activationCode.textContent = code || "";
  if (code && code !== xiaozhiLastShownActivationCode) {
    xiaozhiLastShownActivationCode = code;
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_activation_code_chat_prefix") + " " + code);
  }
}

/** 配對碼只經對話界面顯示；留兩個 no-op stub 防舊快取頁面 ReferenceError。 */

function xiaozhiShowActivationModal() { /* 配對碼只經對話界面顯示 */ }
function xiaozhiHideActivationModal() { /* 見上 */ }

function xiaozhiHideActivationCode() {
  const els = xiaozhiElements();
  if (els.activationBox) els.activationBox.style.display = "none";
  if (els.activationCode) els.activationCode.textContent = "";
  xiaozhiLastShownActivationCode = null;
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
  Alpha2Api.xiaozhiConnect({}).then(function (res) {
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
  Alpha2Api.xiaozhiActivationStatus({}).then(function (res) {
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
        // 單一開關的設計: 一連接好就即刻一併開啟 auto_mode, 等於自動搶 mic、隨時語音
        // 對話, 不用用戶再按多一下 - 見 index.html 個開關 label。
        xiaozhiAutoModeOn = true;
        Alpha2Api.xiaozhiAutoMode({ enabled: "true" });
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
  // 斷線之後隊列裡面正在排的句子已經沒有意義 (小智已經斷了, 不會再有新對話接
  // 下去), 一齊清空, 不留下啲舊句子等下次連接先再讀。
  xiaozhiResetTtsQueue();
  Alpha2Api.xiaozhiDisconnect({}).then(function () {
    xiaozhiSetStatus("xiaozhi_status_disconnected");
    xiaozhiSetConnectedUi(false);
  });
}

/** 單一開關: 開 = 連接 (內部觸發 OTA/activation flow, 完成後自動開 auto_mode 搶
 *  mic, 隨時語音對話), 關 = 斷開 (auto_mode 同 mic 一齊停)。用戶僅要理解「開就用得, 關就不用」。
 *  開關本身即時反映用戶操作的意圖; 真正的連接狀態由 xiaozhiPollActivationStatus()/
 *  xiaozhiHandleEvent() 的 xiaozhi_state 事件驅動, 如果連接失敗會經
 *  xiaozhiSetConnectedUi(false) 將開關撥回去。 */
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
 *  Server echo (stt message) 是 chat log 唯一來源 — 這個 function 只清 input，不自己 append。
function xiaozhiSendText() {
  const els = xiaozhiElements();
  const text = els.textInput ? (els.textInput.value || "").trim() : "";
  if (!text) return;
  // 防禦性檢查：真機 WebView autofill 可能將 placeholder 誤填入 .value；若文字同 placeholder 翻譯一致，當非用戶輸入，不送出並提示重打。
  if (text === t("xiaozhi_text_placeholder")) {
    xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_send_text_error"));
    if (els.textInput) els.textInput.value = "";
    return;
  }
  if (els.sendTextBtn) els.sendTextBtn.disabled = true;
  Alpha2Api.xiaozhiSendText({ text: text }).then(function (res) {
    if (res.ok) {
      if (els.textInput) els.textInput.value = "";
    } else {
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_send_text_error"));
    }
    if (els.sendTextBtn) els.sendTextBtn.disabled = !xiaozhiClientIsConnected();
  });
}

/** 總停鍵 — 一次過中斷動作播放 (action/stop)、TTS (speech/stop，見 handleApi() "speech/stop" case)、本地音樂
 *  (audio/local_music/stop)。用 api() 不是 xiaozhiApi() — 這些 endpoint 屬於
 *  alpha2/lynx backend-specific namespace (見 handleApi())，不是
 *  handleXiaozhiApi() backend-agnostic "xiaozhi/" namespace。
 *
 *  三個 request 用 Promise.all 同時發出 (不是逐個 await), 理由: (1) 這三件事本身
 *  互不相干, 垮一個不應該延遲另外兩個開始執行的時間; (2) 用戶按這個按鈕通常是想
 *  「即刻閉嘴」, 反應時間敏感, 逐個 sequential 送會令總體延遲變成三個 request
 *  時間之和。單一 request 失敗 (例如沒有某個 backend 支援) 不應該影響其餘兩個 -
 *  api() 本身已經在 network/non-ok response 個 case 自己處理了 showError(), 這裡
 *  不用額外再包一層 try/catch。 */
function xiaozhiStopAll() {
  // 按了總停鍵就是用戶想即刻靜完 - 隊列裡面正在排的句子不應該之後又自己彈出來
  // 讀, 一齊清空。
  xiaozhiResetTtsQueue();
  Promise.all([
    Alpha2Api.actionStop(),
    Alpha2Api.speechStop(),
    Alpha2Api.audioLocalMusicStop(),
    Alpha2Api.audioRadioStop(), // FM/網絡電台都是「播放中」一種，跟本地音樂一齊納入總停鍵。
    Alpha2Api.ledHeadSet({ preset: "stop" }), // 總停埋頭燈（嘴燈跟 TTS 停，見 speech/stop）。
    Alpha2Api.ledEyeSet({ preset: "stop" }), // 眼燈同上。
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
 *  時間戳保留 (每句底下細字顯示發送時間)。 */
function clearXiaozhiChatLog() {
  const log = xiaozhiElements().chatLog;
  if (log) log.innerHTML = "";
}

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

/** MCP 工具調用卡片 — 跟 xiaozhi.me console「歷史對話」樣式，可展開「🔧 工具呼叫」卡，顯示 tool＋參數。
 *  耗時不跟：request/response 是獨立 event 無共同 id (見 EVT_MCP)，計耗時要額外對應，價值不成正比。 */
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
    // 對話畫面僅顯示真正的對話內容 (用戶講/打字 + 小智回覆), 靠右/靠左分色 - 見
    // xiaozhiAppendChatLine() 同 style.css 的 .xiaozhi-msg-user/.xiaozhi-msg-assistant。
    // MCP 工具調用 (xiaozhi_mcp)、emotion hint (xiaozhi_llm)、system 指令
    // (xiaozhi_system) 這些協議層雜訊不會再入對話 log - 完整內容仍然正在入主 event
    // log (eventLog, 見 app-log.js 的 appendLog()), 僅不顯示在這個對話畫面。
    // xiaozhi_alert 例外: 這個是伺服器主動推送的警示 (例如電量不足), 用戶應該
    // 在對話畫面見到, 所以保留。
    case "xiaozhi_stt":
      if (data.text) xiaozhiAppendChatLine("xiaozhi-msg-user", data.text);
      break;
    case "xiaozhi_tts":
      // 小智實際回覆的文字是這個 event 的 "sentence_start" (對話氣泡本身都是
      // 用這個顯示) - xiaozhi_llm 個 data.text 其實是表情 emoji (例如 "😆"),
      // 不是對話內容, 不可以用來讀。
      if (data.state === "sentence_start" && data.text) {
        xiaozhiAppendChatLine("xiaozhi-msg-assistant", data.text);
        if (xiaozhiTtsEngine !== "xiaozhi") {
          xiaozhiEnqueueTts(data.text);
        }
      }
      // "stop" drives auto-continue server-side (XiaozhiClient.TtsStateListener) -
      // mic automatically re-starts there, nothing to do here beyond the chat line
      // above.
      if (data.state === "stop" && xiaozhiAutoModeOn) {
        xiaozhiMicActive = true;
      }
      break;
    // 本地 TTS 引擎讀完一句 (後端 robot-side onServerPlayEnd /
    // Android TTS UtteranceProgressListener 都會 publish 這個 event, 見
    // MainActivity 兩處 comment) - 觸發隊列讀下一句。這個 event 不止小智 tab
    // 觸發的 speech/tts 會收到, speech tab 手動測試的 speakTts() 都會收到,
    // 但那邊沒有隊列邏輯, 不受影響。event type 不帶 "xiaozhi_" 前綴 (tts_end 是
    // 全域 TTS 完成訊號, 不止小智專用) - dispatch 在 app-log.js 個 appendLog()
    // 獨立一句轉過來, 不行 "xiaozhi_" 前綴 catch-all 那條路, 見那邊 comment。
    case "tts_end":
      xiaozhiProcessTtsQueue();
      break;
    case "xiaozhi_alert":
      xiaozhiAppendChatLine("xiaozhi-msg-system", "⚠ " + data.status + ": " + data.message);
      break;
    // 僅選 direction:"in" 且 method:"tools/call" payload (server 要求執行工具那刻，不是 initialize/tools/list 雜訊，亦不是 response)，插「工具呼叫」卡。
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
  Alpha2Api.xiaozhiSupported({}).then(function (res) {
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
  Alpha2Api.xiaozhiStatus({}).then(function (res) {
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
    Alpha2Api.xiaozhiActivationStatus({}).then(function (actRes) {
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

/** 背景常駐輪詢，每 8 秒 check 一次 — 背景自動重連 (xiaozhiScheduleReconnect() -> runXiaozhiActivationFlow()) 不會主動通知前端，
 *  若重連要重新拿配對碼 (token 失效／server 取消綁定)，這裡持續留意狀態轉變，不用手動刷新。同短間隔輪詢不衝突：共用 xiaozhiStopActivationPolling() 清 timer，不會重複輪詢。 */
function xiaozhiBackgroundStatusWatch() {
  // 已經有一個 activation polling loop 在這裡正在行 (xiaozhiActivationPollTimer 不是
  // null), 就是說用戶剛剛手動按了連接或者已經响 awaiting_code/polling/connecting
  // 度 - 不用這裡的慢速輪詢再插一腳。
  if (!xiaozhiActivationPollTimer) {
    Alpha2Api.xiaozhiStatus({}).then(function (res) {
      if (!res.ok) return;
      if (res.connected) {
        xiaozhiSetStatus("xiaozhi_status_connected", res.sessionId ? "(" + res.sessionId + ")" : "");
        xiaozhiSetConnectedUi(true);
        xiaozhiAutoModeOn = !!res.autoMode;
        xiaozhiMicActive = !!res.micActive;
        xiaozhiSetMicLed(!!res.micHeld);
      } else {
        Alpha2Api.xiaozhiActivationStatus({}).then(function (actRes) {
          if (actRes.ok && (actRes.stage === "checking" || actRes.stage === "awaiting_code"
              || actRes.stage === "polling" || actRes.stage === "connecting")) {
            // 找到一個背景先至開始了的 activation attempt (通常是自動重連觸發)
            // - 交回給原本那套短間隔輪詢, 等個配對碼/狀態即時反映出來。
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

/** 讀回家陣自訂 server 設定 (見 MainActivity 的 "ota_config/get" endpoint), 反映落
 *  個開關同輸入框 - page load 就要 call, 等用戶見到之前選了的設定, 不會每次開個
 *  panel 都變回做未設定過咁。 */
function xiaozhiLoadOtaConfig() {
  Alpha2Api.xiaozhiOtaConfigGet({}).then(function (res) {
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

/** 開關切換 - 開就顯示輸入框等用戶填 URL (未即刻儲存, 要按「儲存」先真正生效,
 *  見 xiaozhiSaveOtaCustom()); 關就即刻儲存 (遵循官方 xiaozhi.me, 不用等用戶
 *  額外按東西) 並隱藏輸入框。 */
function xiaozhiToggleOtaCustom() {
  const toggle = document.getElementById("xiaozhiOtaCustomToggle");
  const box = document.getElementById("xiaozhiOtaCustomBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (!wantOn) {
    Alpha2Api.xiaozhiOtaConfigSet({ enabled: "false" }).then(function (res) {
      if (!res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
      }
    });
  }
  // wantOn=true 當時僅顯示個輸入框, 不即刻儲存 - 等用戶真正填了 url 按「儲存」
  // 先送出去, 避免用戶僅按一下開關 (url 還是空) 就已經觸發 ota_config/set
  // 令下次連接沒有 url 可用。
}

/** 「儲存」按鈕 - 送出 OTA URL 同三個可選 override (WebSocket 位址/MAC/Token,
 *  全部留空 = 遵循自動流程, 見 MainActivity 的 "ota_config/set" endpoint
 *  comment)、enabled=true。那個 endpoint 本身會拒絕在已連接狀態下更改同驗證
 *  格式, 所以這裡的錯誤處理主要是將 server 已經做了的驗證結果告訴用戶知, 而不是
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
  Alpha2Api.xiaozhiOtaConfigSet({ enabled: "true", url: url, wsUrl: wsUrl, deviceId: deviceId, token: token })
    .then(function (res) {
      if (res.ok) {
        xiaozhiAppendChatLine("xiaozhi-msg-system", t("xiaozhi_ota_custom_saved"));
      } else {
        xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_ota_custom_error"));
        // Server 拒絕了 (例如連正在接、url 格式錯) - 將開關撥回做 checked 状态保留
        // (用戶看到個輸入框還在這裡可以改), 不用自動關掉個開關嚇到人, 僅告訴它知
        // 原因, 等它自己決定是否要改個 url 或者先斷開連接。
      }
    });
}

/** 內置MCP功能列表 card - 頂部一個「展開」開關 (跟自訂 server card 那個
 *  toggle-switch 樣式), 開＝展開整個工具清單 (連逐項 enable/disable 按鈕一齊
 *  顯示), 關＝整個 box 收起完, 什麼都看不到。清單內容讀寫經 MainActivity 的
 *  "mcp_config/set"/"mcp_tools/list" (mcp_tools/list 拿完整清單連逐項 enabled
 *  狀態, mcp_config/set 寫單一 tool 的 enabled 狀態)。首次展開先去拿清單, 之後
 *  收回就僅隱藏, 不會清除已載入的資料, 這樣再展開不用等重新載入。 */
function xiaozhiToggleMcpExpandAll() {
  const toggle = document.getElementById("xiaozhiMcpExpandToggle");
  const box = document.getElementById("xiaozhiMcpToolsBox");
  const wantOn = !!(toggle && toggle.checked);
  if (box) box.style.display = wantOn ? "" : "none";
  if (wantOn) xiaozhiLoadMcpTools();
}

function xiaozhiToggleMcpTool(toolName, checkbox) {
  const wantOn = !!(checkbox && checkbox.checked);
  Alpha2Api.xiaozhiMcpConfigSet({ tool: toolName, enabled: wantOn ? "true" : "false" }).then(function (res) {
    if (!res.ok) {
      // Server 拒絕了 - 撥回個 checkbox 做之前個狀態, 不要留下一個「看來改了
      // 但其實沒有生效」的假象。
      if (checkbox) checkbox.checked = !wantOn;
      xiaozhiAppendChatLine("xiaozhi-msg-system", res.error || t("xiaozhi_mcp_tools_error"));
    }
  });
}

function xiaozhiLoadMcpTools() {
  const box = document.getElementById("xiaozhiMcpToolsList");
  if (!box) return;
  box.textContent = t("xiaozhi_mcp_tools_loading");
  Alpha2Api.xiaozhiMcpToolsList({}).then(function (res) {
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

/** Page load 當時僅初始化開關狀態 (預設收起, 不打 API) - 展開清單要用戶自己
 *  按開個 toggle 先觸發 xiaozhiLoadMcpTools()。 */
function xiaozhiLoadMcpConfig() {
  // 沒有要讀的 persisted 狀態 - 個 toggle 純粹控制展開/收起的 UI, 每次開啟頁面都預設
  // 收起 (同自訂 server card 一致), 不記住上次狀態。
}

// TTS 輸出引擎選擇 - 見 xiaozhiTtsEngine 變數 javadoc。
let xiaozhiTtsEngine = "xiaozhi";

function xiaozhiSetTtsEngineUi(engine) {
  // 只剩下 "xiaozhi"/"android" — 舊設定 (iflytek/nuance) 一律當 "xiaozhi" 顯示。
  if (engine !== "xiaozhi" && engine !== "android") engine = "xiaozhi";
  xiaozhiTtsEngine = engine;
  document.getElementById("xiaozhiTtsEngineXiaozhiBtn").classList.toggle("active", engine === "xiaozhi");
  document.getElementById("xiaozhiTtsEngineAndroidBtn").classList.toggle("active", engine === "android");
  // 切換引擎 (或者 page load 讀回上次選擇) 都要清空舊隊列 - 不是就換掉
  // engine 之後, 隊列裡面舊引擎正在排的句子會用新引擎來讀, 對不上用戶看到的
  // 切換時機, 亦可能因為換了 engine 令 xiaozhiTtsSpeaking 卡在 true (上一個
  // engine 那句不會再有 tts_end 回來)。
  xiaozhiResetTtsQueue();
}

/** 按鈕按落 - 寫回落後端 (xiaozhi/tts_config/set, 順便令 MainActivity 的
 *  onIncomingOpusFrame sink 即時開始/停止靜音 opus, 不用重連), 成功先更新
 *  按鈕 active 狀態; 失敗就不改, 等用戶見到個按鈕沒有跳、可以再按一次。 */
function xiaozhiSetTtsEngine(engine) {
  Alpha2Api.xiaozhiTtsConfigSet({ engine: engine }).then(function (res) {
    if (res && res.ok) {
      xiaozhiSetTtsEngineUi(engine);
    }
  });
}

/** 開機語音卡開關（實驗 tab）——僅揭開／收起內容，同 UUID 卡同一個做法，
 *  每次入頁預設收起，不記住。 */
function bootVoiceCardToggle() {
  const on = document.getElementById("bootVoiceCardEnabled");
  const body = document.getElementById("bootVoiceCardBody");
  const hint = document.getElementById("bootVoiceCardDisabledHint");
  const show = !!(on && on.checked);
  if (body) body.style.display = show ? "" : "none";
  if (hint) hint.style.display = show ? "none" : "";
  if (show) bootVoiceLoad();
}

/** 將三粒 radio 指去指定模式；傳 null/未知值就三粒都不剔。 */
function bootVoiceUi(mode) {
  const radios = document.querySelectorAll('input[name="bootVoiceMode"]');
  radios.forEach(function (r) { r.checked = (r.value === mode); });
}

/** Page load／開卡讀回開機語音模式。API 19 舊機沒有 Vosk，就鎖起 Vosk 那顆。 */
function bootVoiceLoad() {
  Alpha2Api.xiaozhiBootVoiceGet({}).then(function (res) {
    bootVoiceUi(res && res.ok ? res.mode : null);
  });
  Alpha2Api.status().then(function (st) {
    if (st && st.ok && st.apiLevel && st.apiLevel < 21) {
      const radio = document.querySelector('input[name="bootVoiceMode"][value="vosk"]');
      if (radio) radio.disabled = true;
    }
  });
}

/** 三選一：選即儲存，下次開 App 生效（不會即刻連線／即刻起聽）。 */
function bootVoiceSet(mode) {
  const out = document.getElementById("bootVoiceStatus");
  Alpha2Api.xiaozhiBootVoiceSet({ mode: mode }).then(function (res) {
    if (res && res.ok) {
      bootVoiceUi(res.mode);
      if (out) out.textContent = t("boot_voice_saved");
    } else {
      if (out) out.textContent = t("boot_voice_failed_prefix") + (res && res.error ? res.error : "?");
      bootVoiceLoad();
    }
  });
}

/** Page load 讀回上次選低的 TTS 引擎, 同步按鈕 active 狀態 - 對照
 *  xiaozhiLoadOtaConfig() 的做法。 */
function xiaozhiLoadTtsConfig() {
  Alpha2Api.xiaozhiTtsConfigGet().then(function (res) {
    if (res && res.ok && res.engine) {
      xiaozhiSetTtsEngineUi(res.engine);
    }
  });
}

window.addEventListener("DOMContentLoaded", function () {
  xiaozhiCheckSupport();
  xiaozhiRefreshStatus();
  xiaozhiLoadOtaConfig();
  xiaozhiLoadMcpConfig();
  xiaozhiLoadTtsConfig();
  setTimeout(xiaozhiBackgroundStatusWatch, 8000);
});

