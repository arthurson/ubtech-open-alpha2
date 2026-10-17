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

// 今次頁面 session 上載過嘅固件（{name, size}；Reload 即清零）。
// 「開始升級」閘：無呢個即使用家撳升級都唔畀過——機上舊檔唔會自動沿用
// （之前個 bug：舊檔留喺機上，唔揀 file 都升到，燒錯舊鏡像）。
var chestFreshUpload = null;
// 最近一次 chestUpgradeStatus 回包（chestFileInfo 存低，升級前對 size 用）。
var chestLastStatus = null;

function chestUpload() {
  const input = document.getElementById("chestBinFile");
  const status = document.getElementById("chestUploadStatus");
  // 揀咗新檔即舊憑證作廢，等呢次上載成功先再簽發。
  chestFreshUpload = null;
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = t("chest_pick_bin");
    return;
  }
  const file = input.files[0];
  // 上傳前先檢查：僅接受 1..114688 且 4 字節對齊的 app 小鏡像（與 ChestUpgrade.isAcceptedFirmwareSize 一致；
  // 後端與升級啟動時會再次驗證，避免浪費時間上傳後才收到 400 錯誤）。
  if (!chestSizeOk(file.size, null)) {
    if (status) status.textContent = t("chest_upload_bad_size", { n: file.size });
    return;
  }
  if (status) status.textContent = t("chest_uploading", { name: file.name, size: file.size });
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
        // 上載成功先簽發今次 session 憑證（記低名＋size，升級前會同機上檔對數），
        // 再檢查設備上的文件；結果直接寫入狀態行。
        chestFreshUpload = { name: file.name, size: file.size };
        chestFileInfo();
      } else {
        if (status) status.textContent = t("chest_upload_fail", { msg: json.error || JSON.stringify(json) });
      }
    }).catch(function(err) {
      if (status) status.textContent = t("chest_upload_err", { msg: err.message });
    });
  };
  reader.onerror = function() { if (status) status.textContent = t("chest_read_file_fail"); };
  reader.readAsArrayBuffer(file);
}

function chestCheck() {
  const ver = document.getElementById("chestVersionText");
  if (ver) ver.textContent = t("chest_querying");
  Alpha2Api.chestVersion().then(function(json) {
    if (ver) ver.textContent = json.version || (json.ok ? "" : "not found");
  }).catch(function(err) {
    if (ver) ver.textContent = t("err_prefix", { msg: err.message });
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
// 機上檔就算合法，無今次 session 上載憑證都當舊檔處理（顯示 stale 唔畀過）。
function chestFileInfo() {
  const status = document.getElementById("chestUploadStatus");
  return Alpha2Api.chestUpgradeStatus().then(function(s) {
    chestLastStatus = s;
    if (!s || s.ok === false) return false;
    if (!s.fileExists) {
      if (status) status.textContent = t("chest_file_missing");
      return false;
    }
    if (!chestSizeOk(Number(s.fileSizeBytes), s)) {
      if (status) status.textContent = t("chest_file_bad", { n: s.fileSizeBytes });
      return false;
    }
    if (!chestFreshUpload) {
      if (status) status.textContent = t("chest_file_stale", { n: s.fileSizeBytes, p: s.filePages });
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
  // 第一閘：今次 session 未上載過即停（唔會自動沿用機上舊檔）；原因寫狀態行。
  if (!chestFreshUpload) {
    if (status) status.textContent = t("chest_need_fresh_upload");
    return;
  }
  // 第二閘：檢查設備上的文件，不符合要求（原因已顯示於狀態行）則停止；
  // 另對 size（另一 tab 覆蓋過機上檔即對唔上）；通過後再二次確認（變磚警告）才開始燒錄。
  chestFileInfo().then(function(ok) {
    if (!ok) return;
    if (chestLastStatus && Number(chestLastStatus.fileSizeBytes) !== Number(chestFreshUpload.size)) {
      if (status) status.textContent = t("chest_file_mismatch", { n: chestLastStatus.fileSizeBytes, m: chestFreshUpload.size });
      return;
    }
    if (!confirm(t("chest_upgrade_confirm"))) return;
    if (status) status.textContent = t("chest_upgrade_starting");
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
    if (!json.ok) { if (status) status.textContent = "❌ " + (json.error || t("chest_start_fail")); return; }
    if (status) status.textContent = t("chest_upgrading");
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
            if (status) status.textContent = t("chest_done");
          } else {
            if (status) status.textContent = "❌ " + (s.status || t("chest_failed"));
          }
          chestCheck();
        }
      }).catch(function(){});
    }, 500);
  }).catch(function(err){ if (status) status.textContent = "❌ " + err.message; });
}

