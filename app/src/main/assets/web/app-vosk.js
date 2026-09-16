// Open Alpha2 — client logic (app-vosk.js)
// 2026-09 新增: Vosk 離線 ASR (語音 tab)。機身已無 iFlytek/Nuance，用 Vosk 頂上。
// Model 放 sdcard (VoskController.scanModels 自動偵測)，一次一粒；成句結果沿用
// asr_result event（見 app-log.js —— user 氣泡＋語意配對＋TTS 全自動），呢度淨係
// 處理 model 按鍵 (一撳即載入＋自動開聽)＋開始停止 + partial 即時顯示。
// 全部檔案共用 window/global scope (冇用 ES module)，載入順序由 index.html 嘅
// <script src="..."> 順序決定。

// 下載輪詢句柄。
var voskDlTimer = null;
// 上次掃到嘅 models（voskStatus 攞唔到列表，highlight 用緊嗰粒靠呢個）。
var voskCachedModels = [];
// 上次掃到嘅 catalog 全表（含已下載，前端過濾顯示；語言切換重畫唔使再問後端）。
var voskCachedCatalog = [];
// 最近一次 status／download（語言切換嗰陣重畫狀態行用，唔使再問後端）。
var voskLastStatus = null;
var voskLastDownload = null;
// 一撳即用嗰粒等緊 ready（load 要幾秒，ready 即自動開聽）。
var voskPendingModel = null;

// 後端同時俾中文名 (lang)＋英文名 (langEn)，跟面板語言揀顯示邊個；舊版冇 langEn 就跌返 lang。
function voskLangName(m) {
  if (!m) return "";
  if (typeof uiLang !== "undefined" && uiLang === "en") {
    return m.langEn || m.lang || m.id || "";
  }
  return m.lang || m.langEn || m.id || "";
}

// 全局語言切換（setUiLanguage）嗰陣重畫：模型鍵＋下載鍵＋兩條狀態行，唔使再問後端。
function voskApplyUiLanguage() {
  voskRenderModelBtns(voskCachedModels, voskLastStatus && voskLastStatus.model);
  voskRenderCatalogBtns();
  if (voskLastStatus) voskRenderStatus(voskLastStatus);
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

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
    if (!res || !res.ok) return res;
    voskCachedModels = res.models || [];
    voskRenderModelBtns(voskCachedModels, null);
    // 轉頁 reload 嗰陣下載緊：靜靜跟返進度（開頁唔再自動彈下載提示）。
    if (res.download && (res.download.state === "downloading" || res.download.state === "unzipping")) {
      voskRenderDownload(res.download);
      voskPollDownload();
    }
    return voskStatus();
  });
}

// 有咩 model 出咩鍵：名跟面板語言（中文／English），一次淨一粒有效——
// 有效嗰粒藍色 (active)，其餘灰色；切換緊嗰粒閃爍＋成排鎖住防連撳。
// 撳即 voskUseModel：一撳即載入＋ready 即自動開聽。
function voskRenderModelBtns(models, activeId) {
  const box = document.getElementById("voskModelBtns");
  if (!box) return;
  box.innerHTML = "";
  (models || []).forEach(function (m) {
    const btn = document.createElement("button");
    btn.textContent = "🎙️ " + voskLangName(m);
    btn.title = m.id;
    btn.className = "vosk-model-btn" + (m.id === activeId ? " active" : "")
        + (m.id === voskPendingModel ? " switching" : "");
    btn.onclick = (function (id) {
      return function () { voskUseModel(id); };
    })(m.id);
    box.appendChild(btn);
  });
  if (!models || !models.length) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_no_model_hint");
    box.appendChild(hint);
  }
  voskSyncModelBtns();
}

// 成排模型鍵 enable／active／switching 一次過同步：下載緊或者切換緊就鎖晒
//（inline onclick 唔擋，disabled 先真係撳唔到）；active 淨一粒（藍），其餘灰。
function voskSyncModelBtns() {
  const box = document.getElementById("voskModelBtns");
  if (!box || !voskCachedModels.length) return;
  const d = voskLastDownload;
  const dlActive = !!d && (d.state === "downloading" || d.state === "unzipping");
  const locked = dlActive || !!voskPendingModel;
  const activeId = voskPendingModel
      || (voskLastStatus && (voskLastStatus.state === "listening"
          || voskLastStatus.state === "ready") ? voskLastStatus.model : null);
  const btns = box.querySelectorAll("button");
  for (let i = 0; i < btns.length && i < voskCachedModels.length; i++) {
    btns[i].disabled = locked;
    btns[i].classList.toggle("active", voskCachedModels[i].id === activeId && !voskPendingModel);
    btns[i].classList.toggle("switching", voskCachedModels[i].id === voskPendingModel);
  }
}

// 舊名保留（之前 voskRenderStatus 經呢個轉 highlight）：而家直接經
// voskLastStatus＋voskSyncModelBtns，傳入嘅 activeId 唔再用。
function voskMarkActiveModel(activeId) {
  voskSyncModelBtns();
}

// 實驗 tab 下載卡開關（同 panelAuthCardToggle 一致寫法）：預設收埋，唔記狀態。
function voskDlCardToggle() {
  const enabled = document.getElementById("voskDlCardEnabled");
  const body = document.getElementById("voskDlCardBody");
  const hint = document.getElementById("voskDlCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) voskRefreshCatalog();
}

// 實驗 tab 下載卡：列出全部可下載（已下載唔顯示），一粒一粒撳即落。
function voskRefreshCatalog() {
  const list = document.getElementById("voskCatalogList");
  if (!list) return Promise.resolve();
  return Alpha2Api.voskCatalog().then(function (res) {
    if (!res || !res.ok) return res;
    voskCachedCatalog = res.catalog || [];
    voskRenderCatalogBtns();
    return Alpha2Api.voskDownloadStatus().then(function (st) {
      if (st && st.ok) voskRenderDownload(st);
      return res;
    });
  });
}

// 下載鍵名跟面板語言；已下載唔顯示；全部落齊就出提示唔留白。
function voskRenderCatalogBtns() {
  const list = document.getElementById("voskCatalogList");
  if (!list) return;
  list.innerHTML = "";
  let shown = 0;
  (voskCachedCatalog || []).forEach(function (c) {
    if (c.downloaded) return; // 已下載唔顯示（去語音頁撳就用得）
    const btn = document.createElement("button");
    btn.className = "secondary vosk-dl-btn";
    btn.textContent = "⬇ " + voskLangName(c) + " (~" + c.sizeMb + "MB)";
    btn.title = c.id;
    btn.onclick = (function (id) {
      return function () { voskCatalogDownload(id); };
    })(c.id);
    list.appendChild(btn);
    shown++;
  });
  if (!shown) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_dl_all_done");
    list.appendChild(hint);
  }
  // 下載緊鎖住（voskRenderDownload 都會再鎖，呢度補語言切換重畫嗰陣）。
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

// 下載中顯示個名都跟面板語言（catalog 有先譯到，否則跌返 id）。
function voskDownloadName(id) {
  if (!id) return "";
  for (let i = 0; i < voskCachedCatalog.length; i++) {
    if (voskCachedCatalog[i].id === id) return voskLangName(voskCachedCatalog[i]);
  }
  for (let i = 0; i < voskCachedModels.length; i++) {
    if (voskCachedModels[i].id === id) return voskLangName(voskCachedModels[i]);
  }
  return id;
}

// 開始下載指定 model（目錄名，見 catalog）：後端落官方 zip＋自動 unzip＋自動 load。
function voskCatalogDownload(id) {
  const out = document.getElementById("voskCatStatusOut");
  return Alpha2Api.voskDownload({ model: id }).then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("vosk_download_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    // 後端「already exists / already downloading」都回 ok＋現狀：前者係 idle
    //（另一邊已落好），直接 refresh 唔使 poll；否則跟進度。
    if (res.state !== "downloading" && res.state !== "unzipping" && res.state !== "done") {
      return voskRefreshCatalog().then(function () { return voskRefreshModels(); });
    }
    voskRenderDownload(res);
    voskPollDownload();
    return res;
  });
}

function voskCancelDownload() {
  return Alpha2Api.voskDownloadCancel().then(function (res) {
    return voskPollDownloadOnce();
  });
}

// 1 秒 poll 一次 download_status，直到 done/error/cancelled。
function voskPollDownload() {
  if (voskDlTimer) return;
  voskDlTimer = setInterval(voskPollDownloadOnce, 1000);
}

function voskPollDownloadOnce() {
  return Alpha2Api.voskDownloadStatus().then(function (res) {
    if (!res || !res.ok) return res;
    voskRenderDownload(res);
    if (res.state === "done") {
      if (voskDlTimer) { clearInterval(voskDlTimer); voskDlTimer = null; }
      const out = document.getElementById("voskCatStatusOut");
      if (out) out.textContent = t("vosk_download_done");
      // 後端已自動 load：下載卡剔走已落好嗰粒，語音頁即多一粒鍵＋highlight。
      return voskRefreshCatalog().then(function () {
        return voskRefreshModels().then(function () { return voskStatus(); });
      });
    }
    if (res.state === "error" || res.state === "cancelled" || res.state === "idle") {
      if (voskDlTimer) { clearInterval(voskDlTimer); voskDlTimer = null; }
    }
    return res;
  });
}

// vosk_download event（後端 VoskController 主動推）＋上面 poll 共用渲染：
// 進度出喺實驗 tab 下載卡。done/error/cancelled 交 poll 收尾（停 timer＋refresh）。
function voskRenderDownload(res) {
  if (!res) return;
  voskLastDownload = res;
  const out = document.getElementById("voskCatStatusOut");
  const active = res.state === "downloading" || res.state === "unzipping";
  if (out && active) {
    if (res.state === "unzipping") {
      out.textContent = t("vosk_unzipping_hint") + (res.model ? " (" + voskDownloadName(res.model) + ")" : "");
    } else {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("vosk_downloading_prefix") + pct + (res.model ? " (" + voskDownloadName(res.model) + ")" : "");
    }
  } else if (out && res.state === "error") {
    out.textContent = t("vosk_download_fail_prefix") + (res.message || "?");
  } else if (out && res.state === "cancelled") {
    out.textContent = t("vosk_download_cancelled");
  }
  // 下載緊鎖晒 catalog 掣＋語音頁模型鍵（模型未齊，解完會 refresh 重開）。
  const list = document.getElementById("voskCatalogList");
  if (list) {
    const btns = list.querySelectorAll("button");
    for (let i = 0; i < btns.length; i++) btns[i].disabled = !!active;
  }
  const cancelBtn = document.getElementById("voskCatCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // 語音頁模型鍵同步鎖／解（voskSyncModelBtns 識睇 voskLastDownload）。
  voskSyncModelBtns();
}

// 一撳即用：先停舊（用緊中文嗰陣撳英文，後端一次淨駐留一粒，
// load 會頂走舊嘅；呢度先顯式 stop，等舊聆聽即刻停＋partial 清走，
// 再載入指定 model，ready 即自動開始聆聽（唔使再撳開始掣）。
// 撳緊同一粒（聽緊／就緒）就唔當一回事，唔重載。
function voskUseModel(id) {
  if (!id || voskPendingModel === id) return Promise.resolve();
  if (voskLastStatus && voskLastStatus.model === id
      && (voskLastStatus.state === "listening" || voskLastStatus.state === "ready"
        || !!voskLastStatus.listening)) {
    return Promise.resolve(); // 已經係呢粒，唔使郁
  }
  voskPendingModel = id;
  voskSyncModelBtns(); // 即刻灰晒＋切換緊嗰粒閃，唔使用戶估
  const out = document.getElementById("voskStatusOut");
  const switching = !!(voskLastStatus && voskLastStatus.model && voskLastStatus.model !== id
      && (voskLastStatus.state === "listening" || voskLastStatus.state === "ready"
        || !!voskLastStatus.listening));
  if (out) out.textContent = t(switching ? "vosk_switching_hint" : "vosk_loading_hint");
  // 先停舊（閒置嗰陣 stop 係無害 no-op），停完先 load，保證同一時間淨一粒有效。
  return Alpha2Api.voskStop().then(function () {
    if (voskPendingModel !== id) return null; // 停嗰陣用戶撳咗停止掣，收手
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = "";
    return Alpha2Api.voskLoad({ model: id });
  }).then(function (res) {
    if (!res) return voskStatus(); // 上面已收手
    if (!res.ok) {
      voskPendingModel = null;
      if (out) out.textContent = t("vosk_load_fail_prefix") + (res.error ? res.error : "?");
      voskSyncModelBtns();
      return voskStatus();
    }
    return voskWaitReady(id, 0);
  });
}

// load 要幾秒：半秒 poll 一次 status，ready 即自動開聽；30 秒都唔 ready 就收手。
function voskWaitReady(id, tries) {
  if (voskPendingModel !== id) return Promise.resolve();
  if (tries > 60) {
    voskPendingModel = null;
    return voskStatus();
  }
  return Alpha2Api.voskStatus().then(function (res) {
    if (voskPendingModel !== id) return res;
    if (!res || !res.ok) {
      return new Promise(function (resolve) {
        setTimeout(function () { resolve(voskWaitReady(id, tries + 1)); }, 500);
      });
    }
    voskRenderStatus(res);
    if (res.state === "ready") {
      voskPendingModel = null;
      voskSyncModelBtns();
      return voskStart(); // ready 即開聽，一撳即用得
    }
    if (res.state === "listening" || res.state === "error") {
      voskPendingModel = null;
      voskSyncModelBtns();
      return res;
    }
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(voskWaitReady(id, tries + 1)); }, 500);
    });
  });
}

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
  // 手動撳停＝唔要自動開聽，取消一撳即用嘅等待。
  voskPendingModel = null;
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
// 共用呢個渲染：狀態行（跟面板語言）＋停止掣 enable/disable＋模型鍵單活高亮。
function voskRenderStatus(res) {
  if (!res) return;
  voskLastStatus = res;
  const out = document.getElementById("voskStatusOut");
  if (out) {
    const st = (res.state || "idle").toLowerCase();
    const key = "vosk_state_" + st;
    // t() 唔識嘅 key 會回 raw key：未知 state 照出原文，唔洗版。
    let stateName = t(key);
    if (stateName === key) stateName = res.state || "-";
    let modelName = "";
    if (res.model) {
      let found = null;
      for (let i = 0; i < voskCachedModels.length; i++) {
        if (voskCachedModels[i].id === res.model) { found = voskCachedModels[i]; break; }
      }
      modelName = found ? voskLangName(found) : res.model;
    }
    out.textContent = stateName
        + (modelName ? " (" + modelName + ")" : "")
        + (res.message ? " — " + res.message : "");
  }
  const listening = res.state === "listening" || !!res.listening;
  const dot = document.getElementById("voskStateDot");
  if (dot) {
    dot.classList.toggle("vosk-state-dot-on", listening);
    dot.classList.toggle("vosk-state-dot-off", !listening);
  }
  // 開始掣已移除（一撳語言鍵即自動開聽）：剩停止掣，聽緊先 enable。
  const stopBtn = document.getElementById("voskStopBtn");
  if (stopBtn) stopBtn.disabled = !listening;
  // 用緊嗰粒藍色 (active)，其餘灰色；切換緊／下載緊成排鎖住（見 voskSyncModelBtns）。
  if (listening || res.state === "ready") {
    voskMarkActiveModel(res.model || null);
  } else if (res.state === "idle") {
    voskMarkActiveModel(null);
  } else {
    voskSyncModelBtns();
  }
}
