// Open Alpha2 — client logic (app-log.js)
// 內容: WebSocket event log、頁面初始化 (DOMContentLoaded)。呢個檔案要最後 load, 因為 init() 要用晒其他所有 app-*.js 定義嘅 build*()/refresh*() function。
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

/** 統一嘅語意配對＋TTS＋動作觸發函數 — 各種輸入方法（文字輸入等）都用呢個
 *  方法處理，同 sendSpeechChatText() 後半段邏輯一致。 */
function triggerSemanticSimulate(text) {
  if (!text) return;
  Alpha2Api.speechSemanticSimulate( { text: text }).then(function (res) {
    if (!res || !res.ok) return;
    if (!res.matched) {
      appendSpeechChatLine("xiaozhi-msg-system", t("speech_chat_simulate_no_match"));
      return;
    }
    // 對話界面淨顯示中英文對白 — [TYPE operation] 動作ID detail 行唔喺對話流顯示 (Event Log 有同樣資訊)。
    if (res.answer) {
      appendSpeechChatLine("xiaozhi-msg-assistant", res.answer);
    }
  });
}

/** 對話界面文字過濾：如果辨識結果仲係 JSON 字串
 * （例如 {"text":"你好","rc":4}），抽返個 text 出嚟；抽唔到返空字串，
 *  user 氣泡唔會出現大括號／引號等「代碼」。 */
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
    // type 白名單入 className（server 控字串）；time/data 照 escapeHtml。
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
    // 後端讀唔到 (timeout/空) 會 publish {"uuid":null}；uuid:null 顯示讀取失敗，有 uuid 先走清洗+顯示流程。
    if (!msg.data.uuid) {
      if (el) el.textContent = "❌ 讀取失敗 (chest cmd55 無回覆, 見 logcat)";
      return;
    }
    // SN 欄位可能帶 \0 padding — 剝走控制字元先顯示。
    // EEPROM 尾段殘留可能係非零垃圾 byte，顯示會出方塊/亂碼 (Java 端 RobotEventReceiver 已有白名單過濾，呢度第二重保障)。SN 合法字元集只有英數/-/_。
    const clean = String(msg.data.uuid).replace(/[^A-Za-z0-9\-_]/g, "").trim();
    if (el) el.textContent = clean;
    uuidUpdateCard(clean);
  }
  // 配對碼經 EventBus publish "xiaozhi_activation" type，除咗印上面通用 #eventLog，亦觸發 xiaozhiShowActivationCode()
  // (用 typeof 檢查，因 app-log.js 可能喺 app-xiaozhi.js 之前執行，或 build 無小智功能)。xiaozhiShowActivationCode() 已有防重複顯示，呢度唔再擋。
  if (msg.type === "xiaozhi_activation" && msg.data && msg.data.code) {
    if (typeof xiaozhiShowActivationCode === "function") {
      xiaozhiShowActivationCode(msg.data.code);
    }
  }
  if (msg.type === "battery" && msg.data) {
    const el = document.getElementById("batteryOut");
    if (el) el.textContent = msg.data.level + "/" + msg.data.scale + " " + (msg.data.charging ? "⚡充電中" : "") + " (" + msg.data.status + ")";
  }
  if (msg.type === "asr_result" && msg.data) {
    // 對話界面：辨識結果顯示做 user 氣泡 (經 cleanChatText 過濾，JSON 碎片唔出現喺對話流)。
    const cleanAsr = cleanChatText(msg.data.text);
    if (cleanAsr && typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-user", cleanAsr);
      triggerSemanticSimulate(cleanAsr);
    }
  }
  // Vosk 即時 partial — 淨顯示喺語音頁 Vosk 卡狀態行，唔入對話流 (成句先經 asr_result 入氣泡＋管線)。
  if (msg.type === "asr_partial" && msg.data && msg.data.text) {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = msg.data.text;
  }
  if (msg.type === "vosk_state" && msg.data) {
    if (typeof voskRenderStatus === "function") voskRenderStatus(msg.data);
  }
  // Vosk 模型下載進度（後端 VoskController.publishDl 主動推，downloading/unzipping 每 ~0.5s 一個，done/error/cancelled 收尾一個）。
  if (msg.type === "vosk_download" && msg.data) {
    if (typeof voskRenderDownload === "function") voskRenderDownload(msg.data);
    // event 推 done 都要行返 poll 收尾嗰截（停 timer＋refresh＋自動 load 狀態），
    // 唔係淨渲染 % 會停喺 100% 唔識跳去 ready。
    if (msg.data.state === "done" || msg.data.state === "error" || msg.data.state === "cancelled") {
      if (typeof voskPollDownloadOnce === "function") voskPollDownloadOnce();
    } else if (typeof voskPollDownload === "function") {
      voskPollDownload();
    }
  }
  // 真正 online iFlytek ASR 語意配對結果 (由 MainActivity handleIflytekSemanticText() publish) — 真人講嘢/打字模擬兩條路徑喺對話界面出一致 assistant 氣泡。
  if (msg.type === "iflytek_match" && msg.data) {
    // 對話界面淨出 assistant 答案氣泡；[TYPE operation] 動作ID detail 行唔顯示 — 技術代碼喺 Event Log 睇得到。
    if (msg.data.answer && typeof appendSpeechChatLine === "function") {
      appendSpeechChatLine("xiaozhi-msg-assistant", msg.data.answer);
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

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- init ----------------

window.addEventListener("DOMContentLoaded", function () {
  // 鎖屏檢查行先：啟用＋未解鎖即開浮層蓋住面版＋壓住後面 init 嘅 401 誤報（見 app-core.js）。
  if (typeof panelLockCheck === "function") panelLockCheck();
  buildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  setTtsEngine("android"); // Android 內置 TTS，載入引擎/語言清單
  // Vosk 卡初始化 (model 掃描＋狀態同步，有卡先做)。
  if (document.getElementById("voskModelBtns") && typeof voskRefreshModels === "function") {
    voskRefreshModels();
  }
  // 裝置資訊 UUID＋胸板固件改入頁自動直顯 (唔使再撳掣)。
  refreshDeviceInfo();
  requestUuid();
  refreshChestFw();
  // 實驗 tab 面板 token 狀態行（有卡先做；auth/status 永遠開放，唔使驚 401）。
  if (typeof panelAuthRefreshStatus === "function") panelAuthRefreshStatus();
  applyUiLanguage();
  refreshVolume();
  connectWs();
  musicInit();
  if (typeof radioInit === "function") radioInit();
  if (typeof refreshSupportedSizes === "function") refreshSupportedSizes();
  if (typeof buildCameraPhoto9Grid === "function") buildCameraPhoto9Grid();
});
