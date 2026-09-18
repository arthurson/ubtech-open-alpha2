// Open Alpha2 — client logic (app-accel.js)
// 內容: 加速度計/聲納圖表、頭部降噪、UUID 查詢。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Accelerometer: toggle + live X/Y/Z chart ----------------
//
// Readings arrive as "accel" WebSocket events (published from MainActivity's
// onSensorChanged - see appendLog()'s companion handling below), not as a response to
// any API call here - accelerometer/set only turns the feed on/off server-side.
// ACCEL_HISTORY_LEN samples are kept client-side and redrawn on a plain 2D canvas
// (no charting library) each time a new sample arrives.

const ACCEL_HISTORY_LEN = 150;
const ACCEL_RANGE = 12; // ±12 m/s^2 covers gravity (±9.8) plus headroom for motion
let accelHistory = []; // [{x,y,z}, ...], oldest first

// Sonar obstacle history, driven by the "sonar_obstacle" WebSocket event (fired by
// MainActivity#handleChestObstacleFrame whenever a CHES_SEND_OBSTACLE frame arrives).
// Plotted as a step chart: 1 = triggered, 0 = clear, alongside the current threshold
// as a reference line so you can visually confirm the reading matches what the slider
// requested.
const SONAR_HISTORY_LEN = 150;
let sonarHistory = []; // [{triggered: bool}, ...], oldest first
let sonarThresholdCm = 30; // mirrors the slider; kept as its own var since configureSonar()
                            // updates it eagerly on release, ahead of any server round-trip

// Alpha2 PIR 感應器指示燈, 由 "alpha2_pir_state" WebSocket event 驅動 (見
// RobotEventReceiver.java 的 CHEST_ACTION case)。僅反映最新一個 broadcast 的
// 狀態 (紅/綠燈), 不理獨立的「警示反應」(LED+鈴聲) 開關現在開不開 (見
// MainActivity#alpha2SetPirAlertEnabled()), 等你就算警示反應關了都看得到
// broadcast 有沒有到。
function onAlpha2PirState(data) {
  const triggered = !!data.triggered;
  const indicator = document.getElementById("alpha2PirIndicator");
  if (indicator) {
    indicator.className = "pir-indicator " + (triggered ? "pir-indicator-triggered" : "pir-indicator-clear");
  }
}

function toggleAccelerometer() {
  const on = document.getElementById("accelToggle").checked;
  const hint = document.getElementById("accelHint");
  hint.textContent = on ? t("accel_turning_on_hint") : "";
  // Plain Android SensorManager, not implemented by the AIDL backend itself - same
  // single physical IMU regardless of robot SDK version.
  return Alpha2Api.accelerometerSet( { on: String(on) }).then(function (json) {
    if (!json.ok) {
      document.getElementById("accelToggle").checked = false;
      hint.textContent = json.error || t("accel_turn_on_failed_hint");
      return json;
    }
    if (!on) {
      accelHistory = [];
      drawAccelChart();
      document.getElementById("accelXVal").textContent = "-";
      document.getElementById("accelYVal").textContent = "-";
      document.getElementById("accelZVal").textContent = "-";
      hint.textContent = "";
    } else {
      hint.textContent = t("accel_move_hint");
    }
    return json;
  });
}

// Called from appendLog() whenever an "accel" WebSocket event arrives. Kept to plain
// readout + chart duties only - anything that *reacts* to accelerometer data (LED
// colours, triggering actions, etc) is left to Blockly programs / index.html samples
// built on top of this data rather than hardcoded here. See blockly-toolbox.js's
// "傾側控制頭/眼LED" example and index.html's fall-detection sample script.
function onAccelSample(data) {
  document.getElementById("accelXVal").textContent = data.x.toFixed(2);
  document.getElementById("accelYVal").textContent = data.y.toFixed(2);
  document.getElementById("accelZVal").textContent = data.z.toFixed(2);
  accelHistory.push(data);
  if (accelHistory.length > ACCEL_HISTORY_LEN) {
    accelHistory.shift();
  }
  drawAccelChart();
}

function drawSonarChart() {
  const canvas = document.getElementById("sonarChart");
  if (!canvas) return;
  const cssWidth = canvas.clientWidth || 900;
  const cssHeight = canvas.clientHeight || 160;
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== cssWidth * dpr || canvas.height !== cssHeight * dpr) {
    canvas.width = cssWidth * dpr;
    canvas.height = cssHeight * dpr;
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const w = cssWidth, h = cssHeight;
  ctx.clearRect(0, 0, w, h);

  // Step chart: y=0 (bottom) = clear, y=1 (top) = triggered.
  const topY = h * 0.15, bottomY = h * 0.85;

  // Gridlines at clear/triggered levels for reference.
  ctx.strokeStyle = "#e2e6ec";
  ctx.lineWidth = 1;
  [topY, bottomY].forEach(function (py) {
    ctx.beginPath();
    ctx.moveTo(0, py);
    ctx.lineTo(w, py);
    ctx.stroke();
  });

  // Threshold reference line (dashed), labelled with the current cm setting - purely
  // informational since the actual trigger/clear line comes back from the sensor
  // itself, not computed client-side.
  ctx.save();
  ctx.strokeStyle = "#a855f7";
  ctx.setLineDash([4, 4]);
  ctx.lineWidth = 1.5;
  const midY = (topY + bottomY) / 2;
  ctx.beginPath();
  ctx.moveTo(0, midY);
  ctx.lineTo(w, midY);
  ctx.stroke();
  ctx.restore();
  ctx.fillStyle = "#a855f7";
  ctx.font = "11px sans-serif";
  ctx.fillText("門檻 " + sonarThresholdCm + " cm", 6, midY - 6);

  if (sonarHistory.length < 2) return;

  ctx.strokeStyle = "#db2777";
  ctx.lineWidth = 1.8;
  ctx.beginPath();
  sonarHistory.forEach(function (sample, i) {
    const px = (i / (SONAR_HISTORY_LEN - 1)) * w;
    const py = sample.triggered ? topY : bottomY;
    if (i === 0) {
      ctx.moveTo(px, py);
    } else {
      // Step (not diagonal) transitions: draw the horizontal segment at the previous
      // level up to this sample's x, then jump vertically if the state changed.
      const prevPy = sonarHistory[i - 1].triggered ? topY : bottomY;
      ctx.lineTo(px, prevPy);
      ctx.lineTo(px, py);
    }
  });
  ctx.stroke();
}

function drawAccelChart() {
  const canvas = document.getElementById("accelChart");
  if (!canvas) return;
  // Match the canvas's drawing-buffer size to its actual on-screen CSS size (which
  // varies with the responsive layout - see style.css's @media rule), otherwise the
  // chart is blurry/mis-scaled on narrow screens where CSS shrinks a fixed-attribute
  // canvas down.
  const cssWidth = canvas.clientWidth || 900;
  const cssHeight = canvas.clientHeight || 180;
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== cssWidth * dpr || canvas.height !== cssHeight * dpr) {
    canvas.width = cssWidth * dpr;
    canvas.height = cssHeight * dpr;
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  const w = cssWidth, h = cssHeight;
  ctx.clearRect(0, 0, w, h);

  // Zero-line + a couple of gridlines for scale reference.
  ctx.strokeStyle = "#e2e6ec";
  ctx.lineWidth = 1;
  [-ACCEL_RANGE / 2, 0, ACCEL_RANGE / 2].forEach(function (gy) {
    const py = h / 2 - (gy / ACCEL_RANGE) * h;
    ctx.beginPath();
    ctx.moveTo(0, py);
    ctx.lineTo(w, py);
    ctx.stroke();
  });

  if (accelHistory.length < 2) return;

  function plot(key, color) {
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    accelHistory.forEach(function (sample, i) {
      const px = (i / (ACCEL_HISTORY_LEN - 1)) * w;
      const clamped = Math.max(-ACCEL_RANGE, Math.min(ACCEL_RANGE, sample[key]));
      const py = h / 2 - (clamped / ACCEL_RANGE) * (h / 2);
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    });
    ctx.stroke();
  }
  plot("x", "#dc2626");
  plot("y", "#16a34a");
  plot("z", "#3b7dff");
}

// ---------------- Head / misc ----------------

function requestUuid() {
  document.getElementById("uuidOut").innerHTML = t("uuid_querying_hint");
  // 後端 misc/request_uuid 經 chest cmd55 直讀, HTTP response
  // 會順手附帶 {"ok":true,"uuid":"..."} (見 MainActivity)。這裡兩個都接: HTTP 有 uuid 就即刻顯示 (不用等 WS);
  // WS event 照舊經 appendLog() -> uuidUpdateCard() 更新一次 (同一個值, 冪等)。
  // HTTP ok:false 就顯示錯誤, 不再永久停在「查詢中」。
  return Alpha2Api.miscRequestUuid().then(function (res) {
    if (res && res.uuid) {
      var clean = String(res.uuid).replace(/[^A-Za-z0-9\-_]/g, "").trim();
      if (clean) {
        document.getElementById("uuidOut").textContent = clean;
        if (typeof uuidUpdateCard === "function") uuidUpdateCard(clean);
        return res;
      }
    }
    if (res && res.ok === false && res.error) {
      document.getElementById("uuidOut").textContent = "❌ " + res.error;
    }
    // ok:true 但沒有 uuid (舊版後端) / 還正在等 WS event: 保持「查詢中」,
    // WS event 到了 appendLog 會更新。
    return res;
  });
}

// ---------------- UUID card 開關 ---------------------------
// 高風險操作 (直接寫 chest EEPROM), 預設收起內容, 用戶要自己揭開先看到/用到。
// 沒有用 localStorage 記住狀態 — 每次進入這個 tab / 重新整頁都預設關閉, 避免
// 手快快留了開著沒有為意。

function uuidCardToggle() {
  const enabled = document.getElementById("uuidCardEnabled");
  const body = document.getElementById("uuidCardBody");
  const hint = document.getElementById("uuidCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
}

// ---------------- UUID card -----------------------
// 顯示 UUID + QR code (離線生成, app-qr.js) + 更改 ID (cmd54 寫入 chest EEPROM,
// server 端 misc/set_uuid)。QR 內容就是 robotSeq=<ID>, 同官方 app 個 bind QR
// 一致。
//
// 輸入框常駐, 打字時 (oninput) 即時在同一個 uuidQrCanvas 換上
// 新 QR 做預覽 (未寫入 EEPROM); 按「寫入 EEPROM」先真正發送cmd54。輸入框留空
// 時, canvas 顯示現有已知的 UUID (uuidCardLast)。

let uuidCardLast = null;

function uuidUpdateCard(uuid) {
  uuidCardLast = uuid;
  const val = document.getElementById("uuidCardValue");
  if (val) val.textContent = uuid || "-";
  const status = document.getElementById("uuidWriteStatus");
  if (status && uuid) status.textContent = t("uuid_write_done_prefix") + uuid;
  // 輸入框有內容時代表用戶正正在打新 ID 做預覽 — 不要用查詢回來的舊值覆蓋個
  // 預覽 QR; 輸入框空白先顯示現有 UUID 個 QR。
  const input = document.getElementById("uuidNewInput");
  if (!input || !input.value.trim()) {
    uuidDrawQr(uuid);
  }
}

// 純畫 QR, 不改 uuidCardLast/status — 給 uuidUpdateCard() 同
// uuidOnInputChange() 共用。
function uuidDrawQr(uuid) {
  const canvas = document.getElementById("uuidQrCanvas");
  if (canvas && uuid) {
    try {
      // QR 內容是 robotSeq=<ID> 不是僅 ID (用戶實測官方格式)。
      qrDrawToCanvas(canvas, "robotSeq=" + uuid);
    } catch (e) {
      showError("QR", e);
    }
  }
}

// 打新 ID 時即時在同一個 uuidQrCanvas 預覽新 QR。純前端運算, 未寫入 EEPROM — 落 EEPROM 要另外按
// 「寫入 EEPROM」(uuidWriteNew())。輸入清空返顯示現有 UUID。
function uuidOnInputChange() {
  const input = document.getElementById("uuidNewInput");
  const hint = document.getElementById("uuidQrHint");
  const v = input ? input.value.trim() : "";
  if (!v) {
    uuidDrawQr(uuidCardLast);
    if (hint) hint.setAttribute("data-i18n", "uuid_qr_hint"), hint.textContent = t("uuid_qr_hint");
    return;
  }
  if (!/^[A-Za-z0-9\-_]{1,31}$/.test(v)) {
    if (hint) hint.removeAttribute("data-i18n"), hint.textContent = t("uuid_preview_invalid");
    return;
  }
  uuidDrawQr(v);
  if (hint) hint.removeAttribute("data-i18n"), hint.textContent = t("uuid_preview_hint_short");
}

// 隨機碼產生器。在已知合法的編號範圍
// BAF006UBT10000001 ~ BAF006UBT10000504 之間隨機選一個, 填入輸入框並觸發
// QR 預覽 — 只填好輸入框, 不會自動寫入, 用戶要自己按「寫入 EEPROM」
// 確認才會真正寫入 EEPROM。
function uuidGenerateRandom() {
  const min = 10000001;
  const max = 10000504;
  const n = min + Math.floor(Math.random() * (max - min + 1));
  const id = "BAF006UBT" + String(n);
  const input = document.getElementById("uuidNewInput");
  if (input) {
    input.value = id;
    uuidOnInputChange();
  }
}

function uuidWriteNew() {
  const input = document.getElementById("uuidNewInput");
  const status = document.getElementById("uuidWriteStatus");
  const v = input ? input.value.trim() : "";
  if (!/^[A-Za-z0-9\-_]{1,31}$/.test(v)) {
    if (status) status.textContent = t("uuid_write_invalid");
    return;
  }
  if (!confirm(t("uuid_write_confirm") + "\n\n" + v)) return;
  if (status) status.textContent = t("uuid_write_writing");
  Alpha2Api.miscSetUuid( { value: v }).then(function (res) {
    if (!res.ok) {
      if (status) status.textContent = t("uuid_write_failed") + ": " + (res.error || "?");
      return;
    }
    if (status) status.textContent = t("uuid_write_wrote") + v +
        " — " + t("uuid_write_restart_hint");
    // 寫入成功即刻將輸入框清空, 主顯示/QR 轉回做「已寫入的新值」— 等用戶看到
    // 個 flow 已經去到下一步 (reboot), 而不是還停在「正在預覽」的狀態。
    if (input) input.value = "";
    const hint = document.getElementById("uuidQrHint");
    if (hint) hint.setAttribute("data-i18n", "uuid_qr_hint"), hint.textContent = t("uuid_qr_hint");
    uuidCardLast = v;
    const val = document.getElementById("uuidCardValue");
    if (val) val.textContent = v;
    uuidDrawQr(v);
    // 注意: alpha2services 會 cache 開機時讀到的 SN, 即刻 request_uuid 可能還
    // 顯示舊值 — 要重啟 alpha2services (或者重開機) 才會由 EEPROM 重新讀。
  });
}

function uuidCopyId() {
  const v = uuidCardLast || "";
  const status = document.getElementById("uuidWriteStatus");
  if (!v) return;
  function done() {
    if (status) {
      status.textContent = t("uuid_copied");
      setTimeout(function () {
        if (status) status.textContent = t("uuid_write_done_prefix") + uuidCardLast;
      }, 1500);
    }
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(v).then(done).catch(function () { uuidCopyFallback(v, done); });
  } else {
    uuidCopyFallback(v, done);
  }
}

function uuidCopyFallback(text, done) {
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand("copy");
    done();
  } catch (e) {
    showError("copy", e);
  }
  document.body.removeChild(ta);
}

