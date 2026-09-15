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

/** 統一嘅語意配對 + TTS + 動作觸發函數 — 各種輸入方法（文字輸入等）都用呢個
 *  方法處理，同 sendSpeechChatText() 嘅後半段邏輯一致。2026-09: iFlytek/Nuance
 *  asr_result/grammar_result 呢兩種輸入方法已經隨住機身已永久唔再用嘅 binder
 *  引擎一齊移除。 */
function triggerSemanticSimulate(text) {
  if (!text) return;
  Alpha2Api.speechSemanticSimulate( { text: text }).then(function (res) {
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
    // 2026-09-09：type 白名單入 className（之前直拼，server 控字串；
    // time/data 照 escapeHtml）。
    const safeType = /^[A-Za-z0-9_-]+$/.test(msg.type || "") ? msg.type : "raw";
    line.className = "log-line log-type-" + safeType;
    const dataStr = typeof msg.data === "object" ? JSON.stringify(msg.data) : msg.data;
    line.innerHTML = "<span class=\"log-time\">[" + escapeHtml(msg.time) + "]</span> <b>" + escapeHtml(msg.type) + "</b> " + escapeHtml(dataStr);
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
  if (msg.type === "robot_uuid" && msg.data) {
    const el = document.getElementById("uuidOut");
    // 2026-09: 後端讀唔到 (timeout/空) 會 publish {"uuid":null}, 之前個
    // `msg.data.uuid` truthy check 會成個 event 跳過, UI 永久停喺「查詢中」。
    // 而家 uuid:null 就顯示讀取失敗, 有 uuid 先走原本的清洗+顯示流程。
    if (!msg.data.uuid) {
      if (el) el.textContent = "❌ 讀取失敗 (chest cmd55 無回覆, 見 logcat)";
      return;
    }
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
  // 2026-09 移除: mic_state handler - MIC 卡已拎走, 指示燈元素唔存在;
  // event 本身仲會喺 Event Log 照常顯示, 唔影響。
  if (msg.type === "asr_result" && msg.data) {
    // 對話界面: 辨識到嘅嘢顯示做 user 氣泡 (2026-08: 經 cleanChatText 過濾,
    // JSON 碎片唔會出現喺對話流度)
    const cleanAsr = cleanChatText(msg.data.text);
    if (cleanAsr && typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-user", cleanAsr);
      triggerSemanticSimulate(cleanAsr);
    }
  }
  // 2026-09: Vosk 即時 partial - 淨係顯示喺語音頁 Vosk 卡狀態行，唔入對話流
  // (成句先經下面 asr_result 入氣泡＋管線)。
  if (msg.type === "asr_partial" && msg.data && msg.data.text) {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = msg.data.text;
  }
  if (msg.type === "vosk_state" && msg.data) {
    if (typeof voskRenderStatus === "function") voskRenderStatus(msg.data);
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
  // 2026-09 移除: grammar_init/grammar_result/offline_mode 三組 handler -
  // 離線文法卡已拎走 (見 index.html), 呢啲 event 唔會再有後端發出; 指示燈
  // (asrModeDot) 同狀態行 (grammarStatusOut) 元素都已刪除。asr_result 同
  // iflytek_match 上面兩個 handler 保留 (dead-safe: 有 event 先顯示, 無就無)。
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

// 2026-09 移除: updateAsrModeIndicator/refreshAsrModeIndicator - 離線/雲端
// 模式指示燈 (asrModeDot) 已隨離線文法卡一齊拎走 (見 index.html)。

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
  setTtsEngine("android"); // 2026-09: 得返 Android 內置 TTS, 載入引擎/語言清單
  // 2026-09: Vosk 卡初始化 (model 掃描＋狀態同步，有卡先做)。
  if (document.getElementById("voskModelSelect") && typeof voskRefreshModels === "function") {
    voskRefreshModels();
  }
  // 2026-09 移除: MIC 指示燈初始化 (卡已拎走, 見 index.html)。
  // 2026-09: 系統狀態 JSON card 已移除 (refreshStatus 一併刪)；裝置資訊 UUID＋
  // 胸板固件改入頁自動直顯 (唔使再撳掣)。
  refreshDeviceInfo();
  requestUuid();
  refreshChestFw();
  applyUiLanguage();
  refreshVolume();
  // 2026-09 刪除: disableTalkFabIfInsecureContext() (walkie-talkie #talkFab
  // 掣一併拎走，唔使再 disable)。
  // 2026-09 移除: 離線文法/模式指示燈初始化 (卡已拎走, 見 index.html)。
  connectWs();
  musicInit();
  if (typeof radioInit === "function") radioInit();
  if (typeof refreshSupportedSizes === "function") refreshSupportedSizes();
  if (typeof buildCameraPhoto9Grid === "function") buildCameraPhoto9Grid();
});

  // 2026-09 刪除: disableTalkFabIfInsecureContext() 全個 (walkie-talkie
  // #talkFab 掣一併拎走，唔使再 disable)。
