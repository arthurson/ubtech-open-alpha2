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
}

function chestUpload() {
  const input = document.getElementById("chestBinFile");
  const status = document.getElementById("chestUploadStatus");
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = "請先選擇一個 .bin 檔案";
    return;
  }
  const file = input.files[0];
  if (file.size !== 256 * 1024) {
    if (status) status.textContent = "檔案大小不正確，應該是 256KB (目前 " + file.size + " bytes)";
  }
  if (status) status.textContent = "上載中 " + file.name + " (" + file.size + " bytes)…";
  const reader = new FileReader();
  reader.onload = function(e) {
    const bytes = e.target.result;
    fetch("/upload/chest?name=" + encodeURIComponent(file.name), {
      method: "POST",
      body: bytes,
      headers: { "Content-Type": "application/octet-stream" }
    }).then(function(res) { return res.json(); }).then(function(json) {
      if (json.ok) {
        if (status) status.textContent = "✅ 已上載到 " + (json.path || "/sdcard/AlphaII_CHEST_kernel.bin") + "，可按「開始升級」";
      } else {
        if (status) status.textContent = "❌ 上載失敗: " + (json.error || JSON.stringify(json));
      }
    }).catch(function(err) {
      if (status) status.textContent = "❌ 上載錯誤: " + err.message;
    });
  };
  reader.onerror = function() { if (status) status.textContent = "讀取檔案失敗"; };
  reader.readAsArrayBuffer(file);
}

function chestCheck() {
  const ver = document.getElementById("chestVersionText");
  if (ver) ver.textContent = "查詢中…";
  api("chest/version").then(function(json) {
    if (ver) ver.textContent = json.version || (json.ok ? "" : "not found");
  }).catch(function(err) {
    if (ver) ver.textContent = "錯誤: " + err.message;
  });
}

function chestUpgrade() {
  const status = document.getElementById("chestUploadStatus");
  const wrap = document.getElementById("chestProgressWrap");
  const bar = document.getElementById("chestProgressBar");
  const txt = document.getElementById("chestProgressText");
  if (status) status.textContent = "開始升級…";
  if (wrap) wrap.style.display = "block";
  if (bar) bar.style.width = "0%";
  if (txt) txt.textContent = "0%";
  api("chest/upgrade").then(function(json) {
    if (!json.ok) { if (status) status.textContent = "❌ " + (json.error || "啟動失敗"); return; }
    if (status) status.textContent = "升級中…請勿斷電 (需 ~2 分鐘)";
    const timer = setInterval(function() {
      api("chest/upgrade/status").then(function(s) {
        const p = s.progress || 0;
        if (bar) bar.style.width = p + "%";
        if (txt) txt.textContent = p + "% (" + (s.currentPage||0) + "/" + (s.totalPages||0) + ") " + (s.status||"");
        if (!s.inProgress) {
          clearInterval(timer);
          if (s.status && s.status.indexOf("success") !== -1) {
            if (status) status.textContent = "✅ 升級完成，請重啟機械人";
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
