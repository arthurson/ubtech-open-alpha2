// Open Alpha2 — client logic (app-fall.js)
// 跌倒自己起身卡：顯示／設定 app 層 FallRecoveryCenter（fall/status|set|recover|reset）。
// 看門狗喺機械人度自己跑，唔需要開住呢個頁；呢度只係設定同狀態顯示。

let fallPollTimer = null;
let fallLast = null;
let fallApiError = "";

function fallEl(id) { return document.getElementById(id); }

function fallInit() {
  if (!fallEl("fallEnabled")) return;
  fallPoll();
  if (fallPollTimer) clearInterval(fallPollTimer);
  // 每秒問一次狀態（API 純讀，唔會續租約／改狀態）；分頁隱藏時停問，慳機。
  fallPollTimer = setInterval(function () {
    if (!document.hidden) fallPoll();
  }, 1000);
}

function fallPoll() {
  return Alpha2Api.fallStatus().then(function (res) {
    fallApplyStatus(res, false);
    return res;
  }).catch(function () { return null; });
}

function fallSetInput(id, value) {
  const el = fallEl(id);
  if (!el || document.activeElement === el) return; // 用戶打緊字唔好搶
  if (el.type === "checkbox") {
    if (!!el.checked !== !!value) el.checked = !!value;
  } else if (String(el.value) !== String(value)) {
    el.value = value;
  }
}

function fallSideText(side) {
  if (side === "back") return t("fall_side_back");
  if (side === "front") return t("fall_side_front");
  return "";
}

function fallStateText(state) {
  const key = "fall_state_" + state;
  const txt = t(key);
  return txt === key ? state : txt;
}

/** 由 API 回應／WebSocket fall_recovery event 更新卡。fromEvent=true 時唔覆寫用戶輸入。 */
function fallApplyStatus(res, fromEvent) {
  if (!res || !res.ok) return;
  fallLast = res;
  if (!fromEvent) {
    fallSetInput("fallEnabled", !!res.enabled);
    fallSetInput("fallDwell", res.dwellMs != null ? res.dwellMs : 1500);
    fallSetInput("fallAttempts", res.maxAttempts != null ? res.maxAttempts : 2);
    fallSetInput("fallFast", !!res.fast);
    fallSetInput("fallAxes", res.axesOverride || "");
  }

  const bits = [];
  bits.push(t("fall_status") + ": " + fallStateText(res.state || "DISARMED"));
  const side = fallSideText(res.side || "");
  if (side) bits.push(side);
  if (res.tiltDeg != null) bits.push(t("fall_tilt") + " " + Number(res.tiltDeg).toFixed(1) + "°");
  bits.push(t("fall_recoveries") + " " + (res.recoveries || 0));
  if (res.attempts != null) bits.push(t("fall_attempts_used") + " " + res.attempts + "/" + (res.maxAttempts || "?"));
  if (res.policyRunning) bits.push("▶ " + t("fall_policy_running"));

  const warns = [];
  if (res.accelAvailable === false) warns.push(t("fall_accel_missing"));
  else if (res.accelEnabled === false) warns.push(t("fall_accel_off"));
  if (!res.getupBack && !res.getupFront) warns.push(t("fall_no_getup"));
  else {
    const sideNeedsFast = (res.side === "back" && res.getupBackNeedsFast)
      || (res.side === "front" && res.getupFrontNeedsFast)
      || (!res.side && (res.getupBackNeedsFast || res.getupFrontNeedsFast));
    if (sideNeedsFast && !res.fast) warns.push(t("fall_needs_fast"));
  }
  if (res.lastError) warns.push("⚠️ " + res.lastError);

  const out = bits.join(" · ") + (warns.length ? "\n" + warns.join("\n") : "");
  const el = fallEl("fallStatusOut");
  if (el) el.textContent = fallApiError ? (fallApiError + "\n" + out) : out;
}

function fallToggle() {
  const on = !!fallEl("fallEnabled").checked;
  fallApiError = "";
  return Alpha2Api.fallSet({ on: String(on) }).then(function (res) {
    if (!res || !res.ok) {
      // 開唔到（例如冇加速度計）：checkbox 彈返正確狀態。
      const el = fallEl("fallEnabled");
      if (el) el.checked = !on;
      fallApiError = "❌ " + ((res && (res.error || res.message)) || "?");
      if (fallLast) fallApplyStatus(fallLast, true);
      else { const out = fallEl("fallStatusOut"); if (out) out.textContent = fallApiError; }
      return res;
    }
    fallApplyStatus(res, false);
    return res;
  });
}

function fallApply() {
  const dwell = Number(fallEl("fallDwell").value || 1500);
  const attempts = Number(fallEl("fallAttempts").value || 2);
  const axes = (fallEl("fallAxes").value || "").trim();
  if (!(dwell >= 500 && dwell <= 10000)) {
    fallApiError = "❌ dwell_ms 500–10000";
    if (fallLast) fallApplyStatus(fallLast, true);
    return Promise.resolve(null);
  }
  if (!(attempts >= 1 && attempts <= 5)) {
    fallApiError = "❌ max_attempts 1–5";
    if (fallLast) fallApplyStatus(fallLast, true);
    return Promise.resolve(null);
  }
  if (axes && !/^[+-][xyzXYZ][+-][xyzXYZ][+-][xyzXYZ]$/.test(axes)) {
    fallApiError = "❌ axes 格式：+x+y+z";
    if (fallLast) fallApplyStatus(fallLast, true);
    return Promise.resolve(null);
  }
  fallApiError = "";
  return Alpha2Api.fallSet({
    on: String(!!fallEl("fallEnabled").checked),
    dwell_ms: String(Math.round(dwell)),
    max_attempts: String(Math.round(attempts)),
    fast: String(!!fallEl("fallFast").checked),
    axes: axes
  }).then(function (res) {
    if (!res || !res.ok) {
      fallApiError = "❌ " + ((res && (res.error || res.message)) || "?");
      if (fallLast) fallApplyStatus(fallLast, true);
      return res;
    }
    fallApplyStatus(res, false);
    return res;
  });
}

function fallRecoverNow() {
  fallApiError = "";
  return Alpha2Api.fallRecover({}).then(function (res) {
    if (!res || !res.ok) {
      fallApiError = "❌ " + ((res && (res.error || res.message)) || "?");
      if (fallLast) fallApplyStatus(fallLast, true);
      return res;
    }
    fallApplyStatus(res, false);
    return res;
  });
}

function fallReset() {
  fallApiError = "";
  return Alpha2Api.fallReset({}).then(function (res) {
    if (res && res.ok) fallApplyStatus(res, false);
    return res;
  });
}
