// Open Alpha2 — client logic (app-accel.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 加速度計/聲納圖表、頭部降噪、UUID 查詢。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

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
// RobotEventReceiver.java 嘅 CHEST_ACTION case)。淨係反映最新一個 broadcast 嘅
// 狀態 (紅/綠燈), 唔理獨立嘅「警示反應」(LED+鈴聲) 開關而家開唔開 (見
// MainActivity#alpha2SetPirAlertEnabled()), 等你就算警示反應閂咗都睇得到
// broadcast 有冇到。
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
  return hwApi("accelerometer/set", { on: String(on) }).then(function (json) {
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
// "傾側控制頭/眼LED" example and index.html's fall-detection sample script for the
// two behaviours that used to live in this function.
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

function headNoise(on) {
  return api("head/noise", { on: String(on) });
}
function requestUuid() {
  document.getElementById("uuidOut").innerHTML = t("uuid_querying_hint");
  return api("misc/request_uuid");
  // Result arrives asynchronously via the "robot_uuid" WebSocket event (see appendLog's
  // companion handler below) rather than in this HTTP response.
}

