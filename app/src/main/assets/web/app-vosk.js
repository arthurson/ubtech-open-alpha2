// Open Alpha2 — client logic (app-vosk.js)
// Vosk 離線 ASR (語音 tab)。
// Model 放 sdcard (VoskController.scanModels 自動偵測)，一次一粒；成句結果沿用
// asr_result event（見 app-log.js —— user 氣泡＋語意配對＋TTS 全自動），這裡僅
// 處理 model 按鍵 (一按即載入＋自動開聽)＋開始停止 + partial 即時顯示。
// 全部檔案共用 window/global scope (沒有用 ES module)，載入順序由 index.html 的
// <script src="..."> 順序決定。

// 下載輪詢句柄。
var voskDlTimer = null;
// 上次掃到的 models（voskStatus 拿不到列表，highlight 正在用那顆靠這個）。
var voskCachedModels = [];
// 上次掃到的 catalog 全表（含已下載，前端過濾顯示；語言切換重畫不用再問後端）。
var voskCachedCatalog = [];
// 最近一次 status／download（語言切換當時重畫狀態行用，不用再問後端）。
var voskLastStatus = null;
var voskLastDownload = null;
// 一按即用那顆正在等 ready（load 要幾秒，ready 即自動開聽）。
var voskPendingModel = null;

// 後端同時給中文名 (lang)＋英文名 (langEn)，跟面板語言選顯示哪個；舊版沒有 langEn 就跌回 lang。
function voskLangName(m) {
  if (!m) return "";
  if (typeof uiLang !== "undefined" && uiLang === "en") {
    return m.langEn || m.lang || m.id || "";
  }
  return m.lang || m.langEn || m.id || "";
}

// 全局語言切換（setUiLanguage）當時重畫：模型鍵＋下載鍵＋兩條狀態行，不用再問後端。
function voskApplyUiLanguage() {
  voskRenderModelBtns(voskCachedModels, voskLastStatus && voskLastStatus.model);
  voskRenderCatalogBtns();
  if (voskLastStatus) voskRenderStatus(voskLastStatus);
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

function voskRefreshModels() {
  // API 19 熔斷：部機太舊就成張卡收起（後端 voskOrError 會擋，但不要完位）。
  return Alpha2Api.status().then(function (st) {
    if (st && st.ok && st.apiLevel && st.apiLevel < 21) {
      const card = document.getElementById("voskCard");
      if (card) card.style.display = "none";
      // 無 Vosk 就無跟隨對象，TTS 卡個跟隨行一齊收起。
      try {
        if (typeof ttsFollowVoskSyncVisibility === "function") ttsFollowVoskSyncVisibility();
      } catch (e) {}
      return st;
    }
    return Alpha2Api.voskModels();
  }).then(function (res) {
    const card = document.getElementById("voskCard");
    if (card && card.style.display === "none") return res; // API<21 已收起，不要再打擾
    if (!res || !res.ok) return res;
    voskCachedModels = res.models || [];
    voskRenderModelBtns(voskCachedModels, null);
    // 轉頁 reload 當時正在下載：悄悄跟進（開啟頁面不再自動彈下載提示）。
    if (res.download && (res.download.state === "downloading" || res.download.state === "unzipping")) {
      voskRenderDownload(res.download);
      voskPollDownload();
    }
    return voskStatus();
  });
}

// 有什麼 model 出什麼鍵：名跟面板語言（中文／English），一次僅一粒有效——
// 有效那顆藍色 (active)，其餘灰色；切正在換那顆閃爍＋成排鎖住防連按。
// 按即 voskUseModel：一按即載入＋ready 即自動開聽。
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

// 成排模型鍵 enable／active／switching 一次過同步：切正在換先鎖完
//（inline onclick 不擋，disabled 先真正按不到）。正在下載不鎖——下載行背景
// thread、寫不同目錄，按鍵載入／開聽照行（後端完成自動 load 撞正正在聽會讓路）。
// active（藍）僅正在聽那顆——
// 同狀態燈（voskStateDot）一致：stop 之後就緒都是灰，不是藍。
function voskSyncModelBtns() {
  const box = document.getElementById("voskModelBtns");
  if (!box || !voskCachedModels.length) return;
  const locked = !!voskPendingModel;
  const listening = !!(voskLastStatus && (voskLastStatus.state === "listening"
      || !!voskLastStatus.listening));
  const activeId = voskPendingModel
      || (listening ? voskLastStatus.model : null);
  const btns = box.querySelectorAll("button");
  for (let i = 0; i < btns.length && i < voskCachedModels.length; i++) {
    btns[i].disabled = locked;
    btns[i].classList.toggle("active", voskCachedModels[i].id === activeId && !voskPendingModel);
    btns[i].classList.toggle("switching", voskCachedModels[i].id === voskPendingModel);
  }
}

// 實驗 tab 資源下載卡開關（同 panelAuthCardToggle 一致寫法）：預設收起，不記狀態。
// 開嗰陣三邊一齊 refresh（Vosk catalog＋動作包 status＋APK status；轉頁撞正郁緊會跟進度）。
function resDlCardToggle() {
  const enabled = document.getElementById("resDlCardEnabled");
  const body = document.getElementById("resDlCardBody");
  const hint = document.getElementById("resDlCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) {
    voskRefreshCatalog();
    if (typeof actionsPackRefreshStatus === "function") actionsPackRefreshStatus();
    if (typeof apkRefreshStatus === "function") apkRefreshStatus();
  }
}

// 無對話（只聽寫）區摺疊鍵：撳先展開，預設收起。
function voskUnsupportedToggle() {
  const wrap = document.getElementById("voskUnsupportedWrap");
  if (!wrap) return;
  wrap.style.display = (wrap.style.display === "none" || !wrap.style.display) ? "block" : "none";
  voskSyncUnsupportedBtn();
}

function voskSyncUnsupportedBtn() {
  const btn = document.getElementById("voskUnsupportedToggleBtn");
  const wrap = document.getElementById("voskUnsupportedWrap");
  if (!btn) return;
  // wrap 初值 style="display:none"：block 先算展開。
  const isOpen = !!(wrap && wrap.style.display === "block");
  btn.textContent = t(isOpen ? "res_dl_hide_unsupported" : "res_dl_show_unsupported");
}

// 資源下載通道忙碌中（Vosk 落緊／解緊）：動作包嗰邊撳鍵要一齊鎖（後端閘先係真擋）。
function voskDownloadActive() {
  const st = voskLastDownload && voskLastDownload.state;
  return st === "downloading" || st === "unzipping";
}

// 兩邊下載按鈕跨鎖：邊一邊郁緊，另一邊撳鍵都鎖埋（各 cancel 鍵照各自 render 話事）。
function syncResourceDownloadButtons() {
  let busy = false;
  try {
    if (typeof voskDownloadActive === "function" && voskDownloadActive()) busy = true;
  } catch (e) {}
  try {
    if (typeof actionsPackActive === "function" && actionsPackActive()) busy = true;
  } catch (e) {}
  try {
    if (typeof apkActive === "function" && apkActive()) busy = true;
  } catch (e) {}
  ["voskCatalogList", "voskCatalogSupported"].forEach(function (boxId) {
    const list = document.getElementById(boxId);
    if (!list) return;
    const btns = list.querySelectorAll("button");
    for (let i = 0; i < btns.length; i++) btns[i].disabled = busy;
  });
  const packBtn = document.getElementById("actionsPackDlBtn");
  if (packBtn) packBtn.disabled = busy;
  const apkBtn = document.getElementById("apkDlBtn");
  if (apkBtn) apkBtn.disabled = busy;
}

// 實驗 tab 下載卡：列出全部可下載（已下載不顯示），一粒一粒按即下載。
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

// 下載鍵名跟面板語言；已下載不顯示；全部落齊就出提示不留白。
function voskRenderCatalogBtns() {
  const list = document.getElementById("voskCatalogList");
  if (!list) return;
  list.innerHTML = "";
  let shown = 0;
  (voskCachedCatalog || []).forEach(function (c) {
    if (c.dialogue) return; // 有對話那區顯示，這裡僅沒有對話
    if (c.downloaded) {
      const chip = document.createElement("span");
      chip.className = "hint";
      chip.textContent = "✓ " + voskLangName(c) + "（" + t("vosk_dl_transcribe_only") + ")";
      chip.title = c.id;
      list.appendChild(chip);
      shown++;
      return;
    }
    list.appendChild(voskDlButton(c));
    shown++;
  });
  if (!shown) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_dl_all_done");
    list.appendChild(hint);
  }
  voskRenderCatalogSupported();
  voskSyncUnsupportedBtn();
  // 正在下載鎖住（voskRenderDownload 都會再鎖，這裡補語言切換重畫當時）。
  if (voskLastDownload) voskRenderDownload(voskLastDownload);
}

// 下載按鈕共用（兩區一樣）：名跟面板語言＋MB＋id 做 title。
function voskDlButton(c) {
  const btn = document.createElement("button");
  btn.className = "secondary vosk-dl-btn";
  btn.textContent = "⬇ " + voskLangName(c) + " (~" + c.sizeMb + "MB)";
  btn.title = c.id;
  btn.onclick = (function (id) {
    return function () { voskCatalogDownload(id); };
  })(c.id);
  return btn;
}

// 有對話區：有 matcher、可對答（十語：中英西法日德意葡韓俄，後五語骨架等內容）。落了 ✓ 顯示（去語音頁點擊使用，
// 這裡僅顯示不操作）；未落就出下載按鈕。
function voskRenderCatalogSupported() {
  const box = document.getElementById("voskCatalogSupported");
  if (!box) return;
  box.innerHTML = "";
  let shown = 0;
  (voskCachedCatalog || []).forEach(function (c) {
    if (!c.dialogue) return;
    if (c.downloaded) {
      const chip = document.createElement("span");
      chip.className = "hint";
      chip.textContent = "✓ " + voskLangName(c) + " (~" + c.sizeMb + "MB)";
      chip.title = c.id;
      box.appendChild(chip);
      shown++;
      return;
    }
    box.appendChild(voskDlButton(c));
    shown++;
  });
  if (!shown) {
    const hint = document.createElement("span");
    hint.className = "hint";
    hint.textContent = t("vosk_dl_none_hint");
    box.appendChild(hint);
  }
}

// 下載中顯示個名都跟面板語言（catalog 有先譯到，否則跌回 id）。
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
    // 後端「already exists / already downloading」都回 ok＋現狀：前者是 idle
    //（另一邊已落好），直接 refresh 不用 poll；否則跟進度。
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
      // 後端已自動 load：下載卡剔除已落好那顆，語音頁即多一粒鍵＋highlight。
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
// 進度出在實驗 tab 下載卡。done/error/cancelled 交 poll 收尾（停 timer＋refresh）。
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
  // 正在下載僅鎖兩區下載按鈕（後端一次僅落一粒）；語音頁模型鍵照用——
  // 下載不碰聽東西，解完 refresh 會加回新鍵。
  // 跨鎖動作包撳鍵（單通道，見 syncResourceDownloadButtons＋DownloadGate）。
  ["voskCatalogList", "voskCatalogSupported"].forEach(function (boxId) {
    const list = document.getElementById(boxId);
    if (!list) return;
    const btns = list.querySelectorAll("button");
    for (let i = 0; i < btns.length; i++) btns[i].disabled = !!active;
  });
  const cancelBtn = document.getElementById("voskCatCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // 語音頁模型鍵同步鎖／解（voskSyncModelBtns 懂得看 voskLastDownload）。
  voskSyncModelBtns();
  syncResourceDownloadButtons();
}

// 一按即用：正在聽那顆再按即停（變灰）；不同粒就先停舊再載入＋ready 自動開聽。
// 就緒（停了）那顆再按就直接開聽不重載。
function voskUseModel(id) {
  if (!id || voskPendingModel === id) return Promise.resolve();
  const st = voskLastStatus;
  const sameModel = !!(st && st.model === id);
  const active = !!(st && (st.state === "listening" || !!st.listening));
  if (sameModel && active) return voskStop(); // 藍色那顆再按＝停，變回灰
  if (sameModel && st.state === "ready") return voskStart(); // 就緒，直接開聽不重載
  if (sameModel && st.state === "loading") {
    // 後端正在載入這粒（例如轉頁 reload 撞正）：跟進，ready 即開聽。
    voskPendingModel = id;
    voskSyncModelBtns();
    return voskWaitReady(id, 0);
  }
  voskPendingModel = id;
  voskSyncModelBtns(); // 即刻灰完＋切正在換那顆閃，不用用戶估
  const out = document.getElementById("voskStatusOut");
  const switching = !!(voskLastStatus && voskLastStatus.model && voskLastStatus.model !== id
      && (voskLastStatus.state === "listening" || voskLastStatus.state === "ready"
        || !!voskLastStatus.listening));
  if (out) out.textContent = t(switching ? "vosk_switching_hint" : "vosk_loading_hint");
  // 先停舊（閒置當時 stop 是無害 no-op），停完先 load，保證同一時間僅一粒有效。
  return Alpha2Api.voskStop().then(function () {
    if (voskPendingModel !== id) return null; // 停當時用戶按了停止按鈕，收手
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

// load 要幾秒：半秒 poll 一次 status，ready 即自動開聽；30 秒都不 ready 就收手。
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
      return voskStart(); // ready 即開聽，一按即用得
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
  // 手動按停＝不要自動開聽，取消一按即用的等待。
  voskPendingModel = null;
  return Alpha2Api.voskStop().then(function () {
    const partial = document.getElementById("voskPartialOut");
    if (partial) partial.textContent = "";
    return voskStatus();
  });
}

function voskStatus() {
  return Alpha2Api.voskStatus().then(function (res) {
    if (!res || !res.ok) return res;
    voskRenderStatus(res);
    return res;
  });
}

// vosk_state event (後端 VoskController 主動推) 同上面 voskStatus() HTTP 輪詢
// 共用這個渲染：狀態行（跟面板語言）＋停止按鈕 enable/disable＋模型鍵單活高亮。
function voskRenderStatus(res) {
  if (!res) return;
  voskLastStatus = res;
  const out = document.getElementById("voskStatusOut");
  if (out) {
    const st = (res.state || "idle").toLowerCase();
    const key = "vosk_state_" + st;
    // t() 不懂的 key 會回 raw key：未知 state 照出原文，不洗版。
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
  // 模型鍵高亮經 voskSyncModelBtns 統一：僅正在聽那顆藍（再按即停），其餘灰。
  voskSyncModelBtns();
  // TTS 跟隨 Vosk 開嗰陣，模型轉咗要 refresh 跟隨顯示（未開就淨係記低唔問後端）。
  try {
    if (typeof ttsFollowVoskOnModelChange === "function") ttsFollowVoskOnModelChange(res.model || null);
  } catch (e) {}
}



