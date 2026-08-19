// Open Alpha2 — client logic (app-status.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: Tab 切換、狀態頁、裝置資訊 (電量/WiFi/藍牙)、省電開關。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Tabs ----------------

function switchTab(tabId) {
  document.querySelectorAll(".tab-page").forEach(function (el) { el.classList.remove("active"); });
  document.querySelectorAll(".tab-btn").forEach(function (el) { el.classList.remove("active"); });
  document.getElementById(tabId).classList.add("active");
  document.querySelector(".tab-btn[data-tab=\"" + tabId + "\"]").classList.add("active");
}

// ---------------- Status ----------------

function refreshStatus() {
  const out = document.getElementById("statusOut");
  return api("status").then(function (data) {
    out.textContent = JSON.stringify(data, null, 2);
  });
}

// ---------------- Device info: battery / WiFi / Bluetooth / UUID ----------------

function refreshDeviceInfo() {
  return api("battery/status").then(function (battery) {
    document.getElementById("batteryOut").textContent = battery.ok
      ? (battery.level + "/" + battery.scale + " " + (battery.charging ? "⚡充電中" : "") + " (" + battery.status + ")")
      : "讀取失敗";

    return api("wifi/status");
  }).then(function (wifi) {
    document.getElementById("wifiOut").textContent = wifi.ok
      ? (wifi.enabled ? ((wifi.ssid || "(已連接)") + " — " + wifi.ip) : "已關閉")
      : "讀取失敗";

    return api("bt/status");
  }).then(function (bt) {
    document.getElementById("btOut").textContent = bt.ok
      ? (bt.available ? ((bt.name || "(未命名)") + " — " + (bt.enabled ? "已開啟" : "已關閉")) : "不支援")
      : "讀取失敗";
  });
}

function setPowerSave() {
  const save = document.getElementById("powerSave").checked;
  return api("misc/power_save", { save: String(save) });
}

// 充電同時播放 - 預設開 (index.html 個 checkbox 已經 checked), MainActivity
// onCreate() 3 秒後都會自動送一次 open_charge_play=true 做 app 啱啱起身嗰陣嘅
// 預設值; 呢個掣純粹俾用戶之後想自己隨時攞轉 (例如夜晚叫佢靜靜充電唔好郁)。
function setChargePlay() {
  const open = document.getElementById("chargePlay").checked;
  return api("misc/charge_play", { open: String(open) });
}

