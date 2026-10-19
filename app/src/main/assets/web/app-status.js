// Open Alpha2 — client logic (app-status.js)
// 內容: Tab 切換、裝置資訊
// (電量/WiFi/藍牙/UUID直顯/胸板固件)。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Tabs ----------------

function switchTab(tabId) {
  document.querySelectorAll(".tab-page").forEach(function (el) { el.classList.remove("active"); });
  document.querySelectorAll(".tab-btn").forEach(function (el) { el.classList.remove("active"); });
  document.getElementById(tabId).classList.add("active");
  document.querySelector(".tab-btn[data-tab=\"" + tabId + "\"]").classList.add("active");
}

// ---------------- Device info: battery / Bluetooth ----------------
// UUID 直顯見 app-accel.js requestUuid()，胸板固件見下面 refreshChestFw()。
// （WiFi 顯示已移除；wifi/status API 照喺度。）

function refreshDeviceInfo() {
  return Alpha2Api.batteryStatus().then(function (battery) {
    document.getElementById("batteryOut").textContent = battery.ok
      ? (battery.level + "/" + battery.scale + " " + (battery.charging ? "⚡充電中" : "") + " (" + battery.status + ")")
      : "讀取失敗";

    return Alpha2Api.btStatus();
  }).then(function (bt) {
    document.getElementById("btOut").textContent = bt.ok
      ? (bt.available ? ((bt.name || "(未命名)") + " — " + (bt.enabled ? "已開啟" : "已關閉")) : "不支援")
      : "讀取失敗";

    return refreshMicFw().then(function () {
      return refreshBatteryFw();
    });
  });
}

// ---------------- MIC 固件直顯 ----------------
// 原廠「系統信息」嘅 MIC固件欄位 = ro.hardware.version（alpha2_10005 = Lynx
// 5mic、alpha2_10002 = 舊款）。純讀 property，即回；寫自己格 (micFwOut)。
function refreshMicFw() {
  const out = document.getElementById("micFwOut");
  if (out) out.textContent = t("uuid_querying_hint");
  return Alpha2Api.micVersion().then(function (json) {
    if (out) out.textContent = (json && json.version) || "not found";
  }).catch(function (err) {
    if (out) out.textContent = t("err_prefix", { msg: err.message });
  });
}

// ---------------- 電池固件直顯 ----------------
// 原廠「系統信息」嘅電池固件欄位：胸 cmd 57 [3] POWER_INFO（智能電池
// UART 協議，V352 新命令），同官方 VersionCollector 同款問法。
// 智能電池閒置會瞓：後端一問內部 poke 重試；前端 ok:false 就隔 2.5s
// 再試，最多補問 2 次（瞓醒後多數一問即回）；三次都唔得先 not found。
function refreshBatteryFw() {
  const out = document.getElementById("batteryFwOut");
  if (out) out.textContent = t("uuid_querying_hint");
  function attempt(retriesLeft) {
    return Alpha2Api.batteryVersion().then(function (json) {
      if (json && json.ok) {
        if (out) out.textContent = json.version || "not found";
        return;
      }
      if (retriesLeft > 0) {
        return new Promise(function (resolve) { setTimeout(resolve, 2500); }).then(function () {
          return attempt(retriesLeft - 1);
        });
      }
      if (out) out.textContent = "not found";
    });
  }
  return attempt(2).catch(function (err) {
    if (out) out.textContent = t("err_prefix", { msg: err.message });
  });
}

// ---------------- 胸板固件直顯 ----------------
// 同 ADVANCED 卡 chestCheck() 讀同一個 chest/version，這裡寫自己格 (chestFwOut)。
// 入頁自動查一次；胸 MCU 不覆會顯示 not found（同 ADVANCED 卡一致，不是 bug）。
function refreshChestFw() {
  const out = document.getElementById("chestFwOut");
  if (out) out.textContent = t("uuid_querying_hint");
  return Alpha2Api.chestVersion().then(function (json) {
    if (out) out.textContent = (json && json.version) || "not found";
  }).catch(function (err) {
    if (out) out.textContent = t("err_prefix", { msg: err.message });
  });
}


