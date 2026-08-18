// OpenLynx — client logic (app-accel.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 加速度計圖表 + PIR 指示燈。
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

// PIR: fed by the "pir_state" WebSocket event, which RobotEventReceiver.java publishes
// from the com.ubtechinc.services.Action.PIR_STATE broadcast (see that file's comment) -
// this is independent of whether the "sys/pir" on/off toggle's own
// onPIRSensorOpResult callback ever fires (it doesn't, on this firmware - see
// docs/AIDL_GUIDE_LYNX.md). Just flips a single indicator light red/green - no chart/history,
// the light always reflects the latest broadcast regardless of whether the separate
// "警示反應" (LED+ringtone) toggle is on (see MainActivity#setPirAlertEnabled()) so you
// can see broadcasts are arriving even with the alert reaction switched off.
function onPirState(data) {
  const triggered = !!data.triggered;
  const indicator = document.getElementById("lynxPirIndicator");
  if (indicator) {
    indicator.className = "pir-indicator " + (triggered ? "pir-indicator-triggered" : "pir-indicator-clear");
  }
}

function toggleAccelerometer() {
  const on = document.getElementById("accelToggle").checked;
  const hint = document.getElementById("accelHint");
  hint.textContent = on ? t("accel_turning_on_hint") : "";
  // Plain Android SensorManager, not implemented by either AIDL backend (see
  // isSharedHardwarePath() in LynxController.java) - same single physical IMU
  // regardless of which robot SDK is selected, so this follows currentBackend the
  // same way hwApi() does for camera/audio.
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
