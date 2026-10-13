// Open Alpha2 — client logic (app-chest.js)
// 這個檔案負責胸板固件升級 (Chest 17/18) 的前端邏輯，對應 index.html 裡面的「胸板固件升級」card。
// 全部檔案共用 window/global scope (沒有使用 ES module)，載入順序由 index.html 的 <script src> 順序決定。

function chestToggle() {
  const enabled = document.getElementById("chestCardEnabled");
  const body = document.getElementById("chestCardBody");
  const hint = document.getElementById("chestCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  // 開啟卡片即顯示版本與設備固件文件狀態，一眼即可確認是否可以升級。
  if (on) { chestCheck(); chestFileInfo(); }
}

function chestUpload() {
  const input = document.getElementById("chestBinFile");
  const status = document.getElementById("chestUploadStatus");
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = "請先選擇一個 .bin 文件";
    return;
  }
  const file = input.files[0];
  // 上傳前先檢查：僅接受 1..114688 且 4 字節對齊的 app 小鏡像（與 ChestUpgrade.isAcceptedFirmwareSize 一致；
  // 後端與升級啟動時會再次驗證，避免浪費時間上傳後才收到 400 錯誤）。
  if (!chestSizeOk(file.size, null)) {
    if (status) status.textContent = t("chest_upload_bad_size", { n: file.size });
    return;
  }
  if (status) status.textContent = "上傳中 " + file.name + " (" + file.size + " 字節)…";
  const reader = new FileReader();
  reader.onload = function(e) {
    const bytes = e.target.result;
    // 實驗 tab 面板 token：啟用中 /upload/chest 要驗（Bearer header 主，見 PanelAuth），
    // 同 api() 系列一樣由 localStorage 拿，不用經 Alpha2Api（upload 行 raw fetch）。
    let url = "/upload/chest?name=" + encodeURIComponent(file.name);
    let upHeaders = {};
    try {
      if (typeof panelAuthHeaders === "function") upHeaders = panelAuthHeaders();
    } catch (ign) {}
    fetch(url, {
      method: "POST",
      body: bytes,
      headers: Object.assign({ "Content-Type": "application/octet-stream" }, upHeaders)
    }).then(function(res) { return res.json(); }).then(function(json) {
      if (json.ok) {
        // 上傳完成後即檢查設備上的文件（是否存在＋大小是否合法），通過後才允許升級；結果直接寫入狀態行。
        chestFileInfo();
      } else {
        if (status) status.textContent = "❌ 上傳失敗：" + (json.error || JSON.stringify(json));
      }
    }).catch(function(err) {
      if (status) status.textContent = "❌ 上傳錯誤：" + err.message;
    });
  };
  reader.onerror = function() { if (status) status.textContent = "讀取文件失敗"; };
  reader.readAsArrayBuffer(file);
}

function chestCheck() {
  const ver = document.getElementById("chestVersionText");
  if (ver) ver.textContent = "查詢中…";
  Alpha2Api.chestVersion().then(function(json) {
    if (ver) ver.textContent = json.version || (json.ok ? "" : "not found");
  }).catch(function(err) {
    if (ver) ver.textContent = "錯誤: " + err.message;
  });
}

// 固件大小閘（同 ChestUpgrade.isAcceptedFirmwareSize 一致）：只收 app 小鏡像
// 1..小鏡像上限且 4 byte 對齊；256KB 全鏡像含 bootloader，app 更新食唔到，唔收。
// 限值跟 status 行嚟（舊後端無 smallMaxBytes 就跌回 114688）。
function chestSizeOk(n, s) {
  const smallMax = (s && Number(s.smallMaxBytes)) || 114688;
  return n > 0 && n <= smallMax && n % 4 === 0;
}

// 查詢設備上的固件文件（是否存在＋大小是否合法），結果寫入狀態行；返回 true 表示可以升級。
function chestFileInfo() {
  const status = document.getElementById("chestUploadStatus");
  return Alpha2Api.chestUpgradeStatus().then(function(s) {
    if (!s || s.ok === false) return false;
    if (!s.fileExists) {
      if (status) status.textContent = t("chest_file_missing");
      return false;
    }
    if (!chestSizeOk(Number(s.fileSizeBytes), s)) {
      if (status) status.textContent = t("chest_file_bad", { n: s.fileSizeBytes });
      return false;
    }
    if (status) status.textContent = t("chest_file_ok", { n: s.fileSizeBytes, p: s.filePages });
    return true;
  }).catch(function() { return false; });
}

function chestUpgrade() {
  const status = document.getElementById("chestUploadStatus");
  const wrap = document.getElementById("chestProgressWrap");
  const bar = document.getElementById("chestProgressBar");
  const txt = document.getElementById("chestProgressText");
  // 升級前先檢查設備上的文件，不符合要求（原因已顯示於狀態行）則停止；通過後再二次確認（變磚警告）才開始燒錄。
  chestFileInfo().then(function(ok) {
    if (!ok) return;
    if (!confirm(t("chest_upgrade_confirm"))) return;
    if (status) status.textContent = "開始升級…";
    if (wrap) wrap.style.display = "block";
    if (bar) bar.style.width = "0%";
    if (txt) txt.textContent = "0%";
    startChestUpgradePoll();
  });
}

function startChestUpgradePoll() {
  const status = document.getElementById("chestUploadStatus");
  const bar = document.getElementById("chestProgressBar");
  const txt = document.getElementById("chestProgressText");
  Alpha2Api.chestUpgrade().then(function(json) {
    if (!json.ok) { if (status) status.textContent = "❌ " + (json.error || "啟動失敗"); return; }
    if (status) status.textContent = "升級中…請勿斷電（約需 1–2 分鐘）";
    const timer = setInterval(function() {
      Alpha2Api.chestUpgradeStatus().then(function(s) {
        const p = s.progress || 0;
        if (bar) bar.style.width = p + "%";
        if (txt) txt.textContent = p + "% (" + (s.currentPage||0) + "/" + (s.totalPages||0) + ") " + (s.status||"");
        if (!s.inProgress) {
          clearInterval(timer);
          if (s.status && s.status.indexOf("success") !== -1) {
            if (status) status.textContent = t("chest_upgrade_done_power");
          } else if (p === 100) {
            if (status) status.textContent = "✅ 完成";
          } else {
            if (status) status.textContent = "❌ " + (s.status || "失敗");
          }
          chestCheck();
        }
      }).catch(function(){});
    }, 500);
  }).catch(function(err){ if (status) status.textContent = "❌ " + err.message; });
}

// 自動刷新進度（若升級在後台進行，事件推送）
if (typeof EventBus !== "undefined" && EventBus.subscribe) {
  // 兼容舊版 EventBus：若支援則訂閱，否則靠輪詢
  try {
    EventBus.subscribe(function(evt){
      if (evt && evt.type === "chest_upgrade_progress") {
        try {
          const d = JSON.parse(evt.data);
          const bar = document.getElementById("chestProgressBar");
          const txt = document.getElementById("chestProgressText");
          if (bar && d.progress!=null) bar.style.width = d.progress+"%";
          if (txt) txt.textContent = (d.progress||0)+"%";
        } catch(e){}
      }
    });
  } catch(e){}
}

