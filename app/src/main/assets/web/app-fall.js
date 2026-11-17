// Open Alpha2 — client logic (app-fall.js)
// 跌倒後自動起身：加速度計卡片內的單一開關（fall/set?on=）。看門狗在機械人端獨立運行（FallRecoveryCenter），
// 開關狀態保存於機身 SharedPreferences；此處僅負責同步開關狀態，並在開啟失敗時顯示原因。
// 其餘參數（dwell_ms／max_attempts／axes）以及 fall/recover、fall/reset、fall/status 仍以 API 提供。

function fallEl(id) { return document.getElementById(id); }

// 進入頁面時同步：讀取機身實際的開關狀態。
function fallInit() {
  if (!fallEl("fallEnabled")) return Promise.resolve(null);
  return Alpha2Api.fallStatus().then(function (res) {
    fallApplyStatus(res);
    return res;
  }).catch(function () { return null; });
}

// 依據 API 回應或 WebSocket fall_recovery 事件同步開關（以後端實際狀態為準）。
function fallApplyStatus(res) {
  if (!res || !res.ok) return;
  const el = fallEl("fallEnabled");
  if (el && !!el.checked !== !!res.enabled) el.checked = !!res.enabled;
}

function fallToggle() {
  const el = fallEl("fallEnabled");
  const hint = fallEl("fallHint");
  const on = !!el.checked;
  if (hint) hint.textContent = "";
  return Alpha2Api.fallSet({ on: String(on) }).then(function (res) {
    if (!res || !res.ok) {
      // 開啟失敗（例如設備沒有加速度計）：開關還原，並顯示原因。
      el.checked = !on;
      if (hint) hint.textContent = "❌ " + ((res && (res.error || res.message)) || "?");
      return res;
    }
    fallApplyStatus(res);
    // 開啟後會自動開啟加速度計：同步加速度計的開關狀態。
    if (typeof syncAccelToggle === "function") syncAccelToggle();
    return res;
  });
}
