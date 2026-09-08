// Open Alpha2 — client logic (app-status.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: Tab 切換、裝置資訊
// (電量/WiFi/藍牙/UUID直顯/胸板固件)。
// (2026-09: 系統狀態 JSON card 已移除 refreshStatus() 一併刪；省電開關已移除。)
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Tabs ----------------

function switchTab(tabId) {
  document.querySelectorAll(".tab-page").forEach(function (el) { el.classList.remove("active"); });
  document.querySelectorAll(".tab-btn").forEach(function (el) { el.classList.remove("active"); });
  document.getElementById(tabId).classList.add("active");
  document.querySelector(".tab-btn[data-tab=\"" + tabId + "\"]").classList.add("active");
}

// ---------------- Device info: battery / WiFi / Bluetooth ----------------
// (2026-09 刪除 refreshStatus(): 系統狀態 JSON card 已移除。
// UUID 直顯見 app-accel.js requestUuid()，胸板固件見下面 refreshChestFw()。)

function refreshDeviceInfo() {
  return Alpha2Api.batteryStatus().then(function (battery) {
    document.getElementById("batteryOut").textContent = battery.ok
      ? (battery.level + "/" + battery.scale + " " + (battery.charging ? "⚡充電中" : "") + " (" + battery.status + ")")
      : "讀取失敗";

    return Alpha2Api.wifiStatus();
  }).then(function (wifi) {
    document.getElementById("wifiOut").textContent = wifi.ok
      ? (wifi.enabled ? ((wifi.ssid || "(已連接)") + " — " + wifi.ip) : "已關閉")
      : "讀取失敗";

    return Alpha2Api.btStatus();
  }).then(function (bt) {
    document.getElementById("btOut").textContent = bt.ok
      ? (bt.available ? ((bt.name || "(未命名)") + " — " + (bt.enabled ? "已開啟" : "已關閉")) : "不支援")
      : "讀取失敗";
  });
}

// ---------------- 胸板固件直顯 (2026-09 新增) ----------------
// 同 ADVANCED 卡 chestCheck() 讀同一個 chest/version，呢度寫自己格 (chestFwOut)。
// 入頁自動查一次；胸 MCU 唔覆會顯示 not found（同 ADVANCED 卡一致，唔係 bug）。
function refreshChestFw() {
  const out = document.getElementById("chestFwOut");
  if (out) out.textContent = t("uuid_querying_hint");
  return Alpha2Api.chestVersion().then(function (json) {
    if (out) out.textContent = (json && json.version) || "not found";
  }).catch(function (err) {
    if (out) out.textContent = "錯誤: " + err.message;
  });
}

