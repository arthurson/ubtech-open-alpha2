// Open Alpha2 — client logic (app-vosk.js)
// 2026-09 新增: Vosk 離線 ASR (語音 tab)。機身已無 iFlytek/Nuance，用 Vosk 頂上。
// Model 放 sdcard (VoskController.scanModels 自動偵測)，一次一粒；成句結果沿用
// asr_result event（見 app-log.js —— user 氣泡＋語意配對＋TTS 全自動），呢度淨係
// 處理 model 揀擇 (揀即自動載入)＋開始停止 + partial 即時顯示。
// 全部檔案共用 window/global scope (冇用 ES module)，載入順序由 index.html 嘅
// <script src="..."> 順序決定。

function voskRefreshModels() {
  // API 19 熔斷：部機太舊就成張卡收埋（後端 voskOrError 會擋，但唔好晒位）。
  return Alpha2Api.status().then(function (st) {
    if (st && st.ok && st.apiLevel && st.apiLevel < 21) {
      const card = document.getElementById("voskCard");
      if (card) card.style.display = "none";
      return st;
    }
    return Alpha2Api.voskModels();
  }).then(function (res) {
    const card = document.getElementById("voskCard");
    if (card && card.style.display === "none") return res; // API<21 已收埋，唔好再打擾
    const select = document.getElementById("voskModelSelect");
    if (!select || !res || !res.ok) return res;
    select.innerHTML = "";
    // 佔位選項逼用戶主動揀一次 (onchange 先會火)；後端開機自動載返上次嗰粒嗰陣，
    // 下面 voskStatus() 會將 select 指返去已載入嗰粒。
    const holder = document.createElement("option");
    holder.value = "";
    holder.textContent = t("vosk_model_placeholder");
    select.appendChild(holder);
    (res.models || []).forEach(function (m) {
      const opt = document.createElement("option");
      opt.value = m.id;
      opt.textContent = m.lang; // 2026-09: 簡潔起見淨出語言 (中/英各一粒，唔會撞)
      select.appendChild(opt);
    });
    if (!res.models || !res.models.length) {
      const out = document.getElementById("voskStatusOut");
      if (out) out.textContent = t("vosk_no_model_hint");
    }
    return voskStatus();
  });
}

function voskLoad() {
  const select = document.getElementById("voskModelSelect");
  const id = select ? select.value : "";
  const out = document.getElementById("voskStatusOut");
  if (!id) return Promise.resolve();
  if (out) out.textContent = t("vosk_loading_hint");
  return Alpha2Api.voskLoad( { model: id }).then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_load_fail_prefix") + (res && res.error ? res.error : "?");
    }
    return voskStatus();
  });
}

// 2026-09 簡化: 卸載掣已移除 (要換 model 直接揀另一粒，load 會自動頂走舊嘅)。
function voskStart() {
  const out = document.getElementById("voskStatusOut");
  return Alpha2Api.voskStart().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_start_fail_prefix") + (res && res.error ? res.error : "?");
    }
    return voskStatus();
  });
}

function voskStop() {
  return Alpha2Api.voskStop().then(function () {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = "";
    return voskStatus();
  });
}

// 咪測試：1 秒錄音計 RMS/Peak，後端回 dB 數，前端按閾值判夠唔夠大聲。
function voskMicTest() {
  const out = document.getElementById("voskMicOut");
  if (out) out.textContent = "…";
  return Alpha2Api.voskMicTest().then(function (res) {
    if (!out) return res;
    if (!res || !res.ok) {
      out.textContent = t("vosk_mic_fail_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const verdict = res.rmsDb >= -30 ? t("vosk_mic_loud")
        : res.rmsDb >= -50 ? t("vosk_mic_quiet") : t("vosk_mic_silent");
    out.textContent = "RMS " + res.rmsDb + "dB / Peak " + res.peakDb + "dB — " + verdict;
    return res;
  });
}

// 收音延遲套用：mode + 尾音秒 (t_end)；t_start/t_max 罕用，留 HTTP。
// 空即跟預設。
function voskSetEndpointer() {
  const modeEl = document.getElementById("voskEpMode");
  const teEl = document.getElementById("voskEpTEnd");
  const params = {};
  if (modeEl && modeEl.value !== "") params.mode = modeEl.value;
  if (teEl && teEl.value !== "") params.t_end = teEl.value;
  const out = document.getElementById("voskStatusOut");
  return Alpha2Api.voskEndpointer(params).then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_ep_fail_prefix") + (res && res.error ? res.error : "?");
    }
    return voskStatus();
  });
}

function voskStatus() {
  return Alpha2Api.voskStatus().then(function (res) {
    if (!res || !res.ok) return res;
    const select = document.getElementById("voskModelSelect");
    if (select && res.model) select.value = res.model;
    // 後端現值 sync 返個延遲 UI (唔冚用戶打緊字嗰格)。
    const epMode = document.getElementById("voskEpMode");
    if (epMode && res.epMode !== undefined) epMode.value = String(res.epMode);
    const epT = document.getElementById("voskEpTEnd");
    if (epT && document.activeElement !== epT) {
      epT.value = (res.epTEnd === undefined || res.epTEnd === null) ? "" : res.epTEnd;
    }
    voskRenderStatus(res);
    return res;
  });
}

// vosk_state event (後端 VoskController 主動推) 同上面 voskStatus() HTTP 輪詢
// 共用呢個渲染：狀態行 + 開始/停止掣 enable/disable。
function voskRenderStatus(res) {
  const out = document.getElementById("voskStatusOut");
  if (out) {
    out.textContent = (res.state || "-")
        + (res.model ? " (" + res.model + ")" : "")
        + (res.message ? " — " + res.message : "");
  }
  const listening = res.state === "listening" || !!res.listening;
  const ready = res.state === "ready";
  const dot = document.getElementById("voskStateDot");
  if (dot) {
    dot.classList.toggle("vosk-state-dot-on", listening);
    dot.classList.toggle("vosk-state-dot-off", !listening);
  }
  const startBtn = document.getElementById("voskStartBtn");
  const stopBtn = document.getElementById("voskStopBtn");
  if (startBtn) startBtn.disabled = !ready;
  if (stopBtn) stopBtn.disabled = !listening;
}
