// Open Alpha2 — client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// Surface any uncaught JS exception to console.error, which MainActivity's
// WebChromeClient.onConsoleMessage() forwards to logcat (tag "WebViewConsole"). Without
// this, an exception thrown during page init - e.g. connectWs() failing on an older
// WebView's WebSocket implementation - fails completely silently with no trace anywhere.
window.onerror = function (message, source, lineno, colno, error) {
  console.error("Uncaught: " + message + " at " + source + ":" + lineno + ":" + colno);
  return false; // still let the browser's own default error handling happen too
};

// ---------------- Servo calibration ----------------
// Measured on the physical robot (home point via the official "二代舵機校準軟件
// 1.0.0.4", min/max travel via on-robot testing). These are NOT protocol defaults
// from the SDK - they're per-unit hardware calibration and could differ on another
// robot. Used to clamp input so a typo or an out-of-range value can't be sent to a
// servo and force it against its mechanical limit.
// Home-point values confirmed directly against the robot by the user (2026-07) after an
// initial column-misalignment was caught by a min<=home<=max consistency check.
const SERVO_CALIBRATION = {
  1:  { min: 5,   max: 235, home: 120 },
  2:  { min: 50,  max: 210, home: 120 },
  3:  { min: 55,  max: 185, home: 120 },
  4:  { min: 5,   max: 235, home: 120 },
  5:  { min: 30,  max: 190, home: 120 },
  6:  { min: 55,  max: 185, home: 120 },
  7:  { min: 100, max: 200, home: 120 },
  8:  { min: 20,  max: 220, home: 65  },
  9:  { min: 35,  max: 230, home: 145 },
  10: { min: 35,  max: 215, home: 140 },
  11: { min: 100, max: 190, home: 120 },
  12: { min: 40,  max: 140, home: 120 },
  13: { min: 20,  max: 220, home: 175 },
  14: { min: 10,  max: 205, home: 95  },
  15: { min: 25,  max: 205, home: 100 },
  16: { min: 50,  max: 140, home: 120 },
  17: { min: 95,  max: 125, home: 120 },
  18: { min: 95,  max: 125, home: 120 },
  19: { min: 75,  max: 165, home: 120 },
  20: { min: 105, max: 155, home: 120 },
};

/** Clamps a value into [min,max] for the given servo id; returns the raw value unchanged
 *  if the id isn't in the calibration table (shouldn't happen for 1-20). */
function clampServoAngle(id, value) {
  const cal = SERVO_CALIBRATION[id];
  if (!cal) return value;
  return Math.max(cal.min, Math.min(cal.max, value));
}

// Official servo names, per the user's mapping.
const SERVO_NAMES = {
  1: "右肩上下", 2: "右肩左右", 3: "右肘",
  4: "左肩上下", 5: "左肩左右", 6: "左肘",
  7: "右股左右", 8: "右股上下", 9: "右膝", 10: "右腳掌上下", 11: "右腳掌左右",
  12: "左股左右", 13: "左股上下", 14: "左膝", 15: "左腳掌上下", 16: "左腳掌左右",
  17: "右指", 18: "左指",
  19: "頭左右", 20: "頭上下",
};

// Body-part grouping for the servo panel, per the user's mapping:
// head 19/20, right arm 1/2/3/17, left arm 4/5/6/18,
// right leg 7/8/9/10/11, left leg 12/13/14/15/16.
const SERVO_GROUPS = [
  { key: "head",       label: "頭",  icon: "🧠", ids: [19, 20] },
  { key: "right-arm",  label: "右手", icon: "💪", ids: [1, 2, 3, 17] },
  { key: "left-arm",   label: "左手", icon: "💪", ids: [4, 5, 6, 18] },
  { key: "right-leg",  label: "右腳", icon: "🦵", ids: [7, 8, 9, 10, 11] },
  { key: "left-leg",   label: "左腳", icon: "🦵", ids: [12, 13, 14, 15, 16] },
];

// ---------------- Global error surface ----------------
// Any uncaught JS error used to fail silently (a button's onclick handler would just
// stop executing with nothing visible in the page). Both a global handler and every
// api() call now route failures through here so the UI always shows *something*.

function showError(context, err) {
  const banner = document.getElementById("errorBanner");
  const msg = (err && err.message) ? err.message : String(err);
  banner.textContent = "⚠ " + context + ": " + msg;
  banner.style.display = "block";
  console.error(context, err);
}

function clearError() {
  const banner = document.getElementById("errorBanner");
  banner.style.display = "none";
  banner.textContent = "";
}

window.addEventListener("error", function (e) {
  showError("JavaScript error", e.error || e.message);
});
window.addEventListener("unhandledrejection", function (e) {
  showError("Unhandled promise rejection", e.reason);
});

function api(path, params) {
  clearError();
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  return fetch(API + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /" + path, new Error(json.error || json.code || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    // fetch() itself throws on network failure (robot unreachable, CORS, etc). Before
    // this catch existed, this would silently abort the calling function and the button
    // would appear completely unresponsive.
    showError("Network error calling /" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

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

function setChargePlay() {
  const open = document.getElementById("chargePlay").checked;
  return api("misc/charge_play", { open: String(open) });
}

function setPowerSave() {
  const save = document.getElementById("powerSave").checked;
  return api("misc/power_save", { save: String(save) });
}

// ---------------- Actions ----------------

// Categories as returned by the robot's own action list (Alpha2RobotApi row[1] = type).
// The robot's static action-info file uses numeric types (1/2/3/4); some runtime builds
// report the same categories as text instead. Either way each of basic/dance/story/yoga
// gets its own sub-tab with a distinct theme colour; anything not in this whitelist
// (regardless of what the raw type string actually says) falls back to a shared
// "others" sub-tab - the whitelist approach means no unknown category value ever needs
// to be spelled out literally here.
const ACTION_CATEGORIES = [
  { key: "basic",  label: "基本", color: "#3b7dff" },
  { key: "dance",  label: "跳舞", color: "#db2777" },
  { key: "story",  label: "故事", color: "#d97706" },
  { key: "yoga",   label: "瑜伽", color: "#16a34a" },
  { key: "others", label: "其他", color: "#6b7280" },
];
// 白名單: 數字 type (actionInfo.txt 靜態格式) 同文字 type (部分機身 runtime 格式) 都對應埋。
const ACTION_CATEGORY_MAP = {
  "1": "basic", "2": "dance", "3": "story", "4": "yoga",
  basic: "basic", dance: "dance", story: "story", yoga: "yoga",
};
let allActions = [];
let activeActionCategory = "basic";

function categoryOf(rawType) {
  return ACTION_CATEGORY_MAP[rawType] || "others";
}

function loadActionList() {
  const listEl = document.getElementById("actionList");
  listEl.textContent = "載入中…";
  return api("action/list").then(function (data) {
    if (!data.ok) {
      listEl.textContent = "錯誤: " + (data.error || data.code);
      return;
    }
    allActions = data.actions || [];
    buildActionSubTabs();
    renderActionList();
  });
}

/** Builds the 5 category sub-tabs (basic/dance/story/yoga/others), each themed with
 *  its own accent colour via a CSS custom property set inline on the button. */
function buildActionSubTabs() {
  const bar = document.getElementById("actionSubTabBar");
  bar.innerHTML = "";
  ACTION_CATEGORIES.forEach(function (c) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (c.key === activeActionCategory ? " active" : "");
    btn.style.setProperty("--sub-tab-color", c.color);
    const count = allActions.filter(function (a) { return categoryOf(a.type) === c.key; }).length;
    btn.textContent = c.label + " (" + count + ")";
    btn.onclick = function () {
      activeActionCategory = c.key;
      buildActionSubTabs();
      renderActionList();
    };
    bar.appendChild(btn);
  });
}

function renderActionList() {
  const listEl = document.getElementById("actionList");
  const filtered = allActions.filter(function (a) { return categoryOf(a.type) === activeActionCategory; });

  if (filtered.length === 0) {
    listEl.textContent = "(冇動作 / 服務未初始化)";
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = a.nameEn || a.nameCn;
    chip.onclick = function () {
      document.getElementById("actionName").value = a.nameEn || a.nameCn;
      playAction();
    };
    listEl.appendChild(chip);
  });
}

function typeLabel(t) {
  const cat = ACTION_CATEGORIES.filter(function (c) { return c.key === categoryOf(t); })[0];
  return cat ? cat.label : t;
}

function playAction() {
  const name = document.getElementById("actionName").value.trim();
  if (!name) return alert("請輸入動作名稱");
  return api("action/play", { name: name });
}

function stopAction() {
  return api("action/stop");
}

// ---------------- Speech / TTS ----------------
//
// The robot runs two distinct on-device services - com.ubtechinc.services.
// NuanceSpeeckServices and .IflytekSpeeckServices (see Alpha2Intent.java in the SDK) -
// both genuinely functional. Alpha2RobotApi itself has no separate "engine" parameter
// though: engine selection happens implicitly through which language code you send
// (en_us / zh_cn). Nuance only has an English grammar/voice set on this firmware;
// iFlytek covers both. So "engine" here is a UI-level grouping that filters which
// language options make sense, not a value sent to the robot on its own - only the
// Voice selection only applies to iFlytek's named voices - Nuance and Android's
// system TTS each use their own single default voice with no picker.
function onEngineChange() {
  const engine = document.getElementById("ttsEngine").value;
  const voiceSelect = document.getElementById("ttsVoice");
  voiceSelect.disabled = (engine !== "iflytek");
  if (engine !== "iflytek") {
    voiceSelect.value = "";
  }
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) return alert("請輸入文字");
  const engine = document.getElementById("ttsEngine").value;
  const params = { text: text, engine: engine };
  if (engine === "iflytek") {
    const voice = document.getElementById("ttsVoice").value;
    if (voice) params.voice = voice;
  }
  return api("speech/tts", params);
}

function stopTts() {
  return api("speech/stop");
}

function setMic(wake) {
  return api("speech/set_mic", { wake: String(wake) });
}

// ---------------- Speech / ASR (manual) ----------------
//
// "Engine" here just picks the recognition language the same way onEngineChange()
// does for TTS (see that function's comment) - en_us -> Nuance, zh_cn -> iFlytek.
// Results don't come back from this call itself: they arrive later, asynchronously,
// as an "asr_result" WebSocket event (published from MainActivity's onServerCallBack)
// and are shown by appendLog() below. set_language must land before start_asr, or the
// engine that ends up listening may not match what's selected here.
//
// start_asr (speech_startSpeechNoWakeup) was added to trigger recognition without
// waiting for the mic-array hardware's own wake word - see logcat_2026-07-30_07-53-50.txt
// for why set_mic(true) alone couldn't do that. But logcat_2026-07-02_13-38-32.txt (a
// later on-robot test of start_asr itself) shows it only moves the speech engine into
// SPEECH_STATE_WAKEUP internally (SpeechManager "what:3", IflytekWakeUp5mic.
// startRecording) - actual recognition (IflyteckASR5mic "startSpeechASR type:0",
// "Listening...") still didn't begin until a hardware "MicArray wakeup" fired
// independently, ~20s later. So start_asr does put the robot in a more
// wake-word-receptive state than doing nothing, but it is not the direct trigger this
// button's label implies - hence the phrasing below.
function startAsr() {
  const engine = document.getElementById("asrEngine").value;
  const lang = engine === "iflytek" ? "zh_cn" : "en_us";
  document.getElementById("asrOut").textContent = "準備聆聽中（仲要機械人偵測到 wake word 先真正開始錄音）…";
  return api("speech/set_language", { lang: lang }).then(function () {
    return api("speech/start_asr", {});
  });
}

function stopAsr() {
  document.getElementById("asrOut").textContent = "已停止";
  return setMic(false);
}

function setSelfInterrupt() {
  const on = document.getElementById("selfInterrupt").checked;
  return api("speech/self_interrupt", { on: String(on) });
}

// ---------------- Servos: grouped sliders (head / arms / legs) ----------------
//
// Each servo is now a single <input type="range"> instead of a plain number box.
// Dragging the slider updates the live value readout immediately, but the actual
// robot command is only sent on "change" (i.e. when the user releases / lifts the
// finger) - not on every "input" tick - so a drag doesn't flood the AIDL bridge
// with dozens of intermediate servo/one calls per second.

function servoTime() {
  return document.getElementById("servoAllTime").value;
}

function buildServoGrid() {
  const wrap = document.getElementById("servoGroups");
  wrap.innerHTML = "";
  SERVO_GROUPS.forEach(function (group) {
    const groupEl = document.createElement("div");
    groupEl.className = "servo-group";

    const title = document.createElement("div");
    title.className = "servo-group-title";
    title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + group.label;
    groupEl.appendChild(title);

    group.ids.forEach(function (id) {
      const cal = SERVO_CALIBRATION[id];
      const row = document.createElement("div");
      row.className = "servo-slider-row";
      row.innerHTML =
          "<span class=\"servo-slider-label\">#" + id + " " + SERVO_NAMES[id] + "</span>" +
          "<input type=\"range\" id=\"servoSlider_" + id + "\" min=\"" + cal.min + "\" max=\"" + cal.max + "\" value=\"" + cal.home + "\">" +
          "<span class=\"servo-slider-value\" id=\"servoSliderVal_" + id + "\">" + cal.home + "</span>";
      const slider = row.querySelector("input");
      const valueLabel = row.querySelector(".servo-slider-value");

      // Live readout while dragging - no network call yet.
      slider.addEventListener("input", function () {
        valueLabel.textContent = slider.value;
      });
      // Actually move the servo once the drag ends.
      slider.addEventListener("change", function () {
        const raw = parseInt(slider.value, 10);
        const clamped = clampServoAngle(id, isNaN(raw) ? cal.home : raw);
        if (clamped !== raw) {
          slider.value = clamped;
          valueLabel.textContent = clamped;
        }
        api("servo/one", { id: id, angle: clamped, time: servoTime() });
      });

      groupEl.appendChild(row);
    });

    wrap.appendChild(groupEl);
  });
}

/** Resets every slider to its calibrated home position and sends all 20 at once. */
function resetServoGrid() {
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    const slider = document.getElementById("servoSlider_" + i);
    const label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
  }
  servoAll();
}

function servoAll() {
  const angles = [];
  for (let i = 1; i <= 20; i++) {
    const slider = document.getElementById("servoSlider_" + i);
    const cal = SERVO_CALIBRATION[i];
    const raw = slider ? parseInt(slider.value, 10) : cal.home;
    const clamped = clampServoAngle(i, isNaN(raw) ? cal.home : raw);
    angles.push(clamped);
  }
  return api("servo/all", { angles: angles.join(","), time: servoTime() });
}

function configureSonar() {
  const distance = document.getElementById("sonarDist").value;
  return api("servo/sonar", { distance: distance });
}

function disableSonar() {
  return api("servo/sonar", { distance: "0" });
}

// ---------------- LEDs ----------------
// Colour/brightness/preset values are user-confirmed on real 5-mic hardware:
//   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
//   brightness: 1 (最暗) .. 9 (最光)
//   preset (p5 upTime / p6 downTime / p7 runTime / p8 mode, set server-side in
//   MainActivity.java): 長開 p5=MAX,p6=0,p8=0 · 閃燈 p5=100,p6=100,p8=0 · 跑馬燈
//   p5=100,p6=0 · 呼吸燈(頭部限定) p5=5,p6=20 · 雙色燈 p5=500,p6=0 · 停止 (no LED
//   params, calls the stop endpoint)
// Every control (colour dot, brightness slider, preset button) sends immediately -
// there's no separate "開始" button. Picking a colour or dragging brightness re-sends
// whatever preset was last used, so the robot updates live as you adjust either one.

const LED_COLORS = [
  { code: 1, name: "紅", hex: "#ff3b3b" },
  { code: 2, name: "綠", hex: "#3bff5c" },
  { code: 3, name: "藍", hex: "#3b6bff" },
  { code: 4, name: "黃", hex: "#ffe93b" },
  { code: 5, name: "紫", hex: "#a83bff" },
  { code: 6, name: "青", hex: "#3bfff0" },
  { code: 7, name: "白", hex: "#ffffff" },
];

let selectedHeadColor = 5; // 紫 - matches Alpha2Connection.beginLedEffect()'s confirmed-working payload
let selectedEyeColor = 7;  // 白
let lastHeadPreset = "long";
let lastEyePreset = "long";

function buildColorPicker(wrapId, getSelected, setSelected, onPick) {
  const wrap = document.getElementById(wrapId);
  wrap.innerHTML = "";
  LED_COLORS.forEach(function (c) {
    const dot = document.createElement("button");
    dot.type = "button";
    dot.className = "color-dot" + (c.code === getSelected() ? " selected" : "");
    dot.style.background = c.hex;
    dot.title = c.name;
    dot.onclick = function () {
      setSelected(c.code);
      wrap.querySelectorAll(".color-dot").forEach(function (d) { d.classList.remove("selected"); });
      dot.classList.add("selected");
      onPick();
    };
    wrap.appendChild(dot);
  });
}

function buildHeadColorPicker() {
  buildColorPicker("headColorPicker",
    function () { return selectedHeadColor; },
    function (code) { selectedHeadColor = code; },
    headLedApply);
}

function buildEyeColorPicker() {
  buildColorPicker("eyeColorPicker",
    function () { return selectedEyeColor; },
    function (code) { selectedEyeColor = code; },
    eyeLedApply);
}

// Re-sends whatever preset was last active, using the current colour/brightness.
// Called on every colour click and every brightness drag so changes apply live.
function headLedApply() {
  return headLedPreset(lastHeadPreset);
}
function eyeLedApply() {
  return eyeLedPreset(lastEyePreset);
}

function headLedPreset(preset) {
  lastHeadPreset = preset;
  if (preset === "stop") {
    return api("led/head/set", { preset: "stop" });
  }
  const color = selectedHeadColor;
  const brightness = document.getElementById("headBrightness").value;
  return api("led/head/set", { preset: preset, color: color, brightness: brightness });
}

function eyeLedPreset(preset) {
  lastEyePreset = preset;
  if (preset === "stop") {
    return api("led/eye/set", { preset: "stop" });
  }
  const color = selectedEyeColor;
  const brightness = document.getElementById("eyeBrightness").value;
  return api("led/eye/set", { preset: preset, color: color, brightness: brightness });
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "咀部 LED" section for what was tried and ruled out).
function mouthLedApply() {
  const speed = document.getElementById("mouthSpeed").value;
  return api("led/mouth/set", { speed: speed }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}

function mouthLedOff() {
  return api("led/mouth/set", { preset: "off" }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true (off)" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}

// ---------------- Camera: MJPEG live stream ----------------
//
// The server keeps the camera open continuously (CameraController) and pushes preview
// frames out as multipart/x-mixed-replace MJPEG at GET /stream/camera - a browser
// <img> tag renders that natively as a live feed with no JS polling loop needed, at
// whatever frame rate the camera driver actually delivers (real preview frames, not a
// capture()/release() cycle per frame, so this reaches the driver's native ~30fps
// instead of being capped by an open/close round trip).
//
// Reconnection relies solely on the <img>'s onerror event, which the browser does fire
// when a multipart connection actually breaks (server restarted, camera error, network
// drop). There is deliberately no separate "stall" timer here: onload only fires once,
// for the initial connection, and is NOT re-fired per MJPEG part in any mainstream
// browser - a timer built on "time since last onload" would flag every healthy,
// still-streaming connection as stalled a few seconds in, and force a reconnect that
// re-opens the camera each time, capping the effective frame rate right back down to
// what a full open/close cycle costs.

let cameraLiveRunning = false;

function cameraElements() {
  return {
    viewport: document.getElementById("cameraViewport"),
    placeholder: document.getElementById("cameraPlaceholder"),
    badge: document.getElementById("cameraLiveBadge"),
    btn: document.getElementById("cameraToggleBtn"),
    hint: document.getElementById("cameraStatusHint"),
    resolution: document.getElementById("cameraResolution"),
    crosshairPad: document.getElementById("crosshairPad"),
    crosshairMark: document.getElementById("crosshairMark"),
    // "featureEnabled" is the single master checkbox that now gates all three
    // overlay features together (head-aim joystick pad, mic-listen headphone FAB,
    // talk FAB) - kept under the name crosshairToggle here since all the existing
    // crosshair drag-to-aim code below already reads els.crosshairToggle.
    crosshairToggle: document.getElementById("featureEnabled"),
    fabRow: document.getElementById("fabRow"),
    micListenFab: document.getElementById("micListenFab"),
    talkFab: document.getElementById("talkFab"),
  };
}

/** Fired when the resolution dropdown changes. If the stream is already running,
 *  restarts it so the new size actually takes effect (Camera can't resize mid-stream).
 *  If not running, there's nothing to do yet - startCameraLive() reads the dropdown's
 *  current value when the user next opens the camera. */
function onResolutionChanged() {
  if (cameraLiveRunning) {
    stopCameraLive();
    startCameraLive();
  }
}

function toggleCameraLive() {
  if (cameraLiveRunning) {
    stopCameraLive();
  } else {
    startCameraLive();
  }
}

/** Applies the dropdown's selected "WxH" resolution server-side, then (re)starts the
 *  stream. Camera can't change preview size mid-stream, so this always goes through
 *  stop -> set resolution -> start, even if a stream is already running. */
async function startCameraLive() {
  const els = cameraElements();
  const placeholder = els.placeholder, badge = els.badge, btn = els.btn, hint = els.hint;

  const [w, h] = (els.resolution ? els.resolution.value : "640x480").split("x").map(Number);
  hint.textContent = "設定解像度…";
  await api("camera/resolution", { w: w, h: h });

  cameraLiveRunning = true;
  btn.textContent = "⏸ 關閉鏡頭";
  badge.classList.add("on");
  if (placeholder) placeholder.style.display = "none";
  hint.textContent = "連接緊鏡頭串流…";

  connectCameraStream();
  setupCrosshairIfNeeded();
  updateCrosshairVisibility();
}

function stopCameraLive() {
  const els = cameraElements();
  const badge = els.badge, btn = els.btn, placeholder = els.placeholder, viewport = els.viewport;
  cameraLiveRunning = false;
  const img = viewport.querySelector("img");
  if (img) {
    img.src = ""; // stop the browser holding the multipart connection open
    img.remove();
  }
  btn.textContent = "▶ 開啟鏡頭";
  badge.classList.remove("on");
  if (placeholder) {
    placeholder.style.display = "";
    placeholder.textContent = "鏡頭未開啟";
  }
  els.hint.textContent = "";
  updateCrosshairVisibility();
}

// ---------------- Camera crosshair: drag-to-aim head control (servo 19 pan / 20 tilt) ----
//
// The crosshair mark's position within the viewport maps linearly to the servo 19/20
// The pad's center = each servo's home position; dragging the knob to the pad's edge
// in any direction reaches that servo's min or max. Only the drag's *end* (pointerup)
// sends servo/one - dragging continuously updates the knob's on-screen position for
// visual feedback, but would flood the AIDL bridge with a servo command on every
// pointermove tick otherwise. Releasing snaps the knob back to center, matching how a
// physical self-centering joystick behaves.

let crosshairDragging = false;
let crosshairSetupDone = false;

/** axisValue in [-1, 1]: -1 = servo min, 0 = servo home, +1 = servo max. */
function crosshairAxisToAngle(id, axisValue) {
  const cal = SERVO_CALIBRATION[id];
  if (!cal) return null;
  const span = axisValue >= 0 ? (cal.max - cal.home) : (cal.home - cal.min);
  return clampServoAngle(id, Math.round(cal.home + axisValue * span));
}

function setKnobPosition(nx, ny) {
  const mark = cameraElements().crosshairMark;
  if (!mark) return;
  const clampedNx = Math.max(-1, Math.min(1, nx));
  const clampedNy = Math.max(-1, Math.min(1, ny));
  mark.style.left = (50 + clampedNx * 35) + "%";
  mark.style.top = (50 + clampedNy * 35) + "%";
}

function updateCrosshairVisibility() {
  const els = cameraElements();
  const shouldShow = cameraLiveRunning && els.crosshairToggle && els.crosshairToggle.checked;
  if (els.crosshairPad) {
    els.crosshairPad.classList.toggle("active", shouldShow);
  }
  if (els.fabRow) {
    els.fabRow.classList.toggle("active", shouldShow);
  }
  // Unticking the master checkbox (or stopping the camera) must not leave mic-listen
  // or push-to-talk silently running with their FABs hidden - force both off so the
  // control state always matches what's actually visible on screen.
  if (!shouldShow) {
    if (micListening) stopMicListen();
    if (talkActive) stopTalk();
  }
}

function setupCrosshairIfNeeded() {
  if (crosshairSetupDone) return;
  crosshairSetupDone = true;

  const els = cameraElements();
  const pad = els.crosshairPad;
  if (!pad) return;

  // Throttles how often a drag actually sends servo/one while the pointer is moving -
  // pointermove can fire far faster than the AIDL bridge (and the servo hardware
  // itself) can usefully keep up with. lastSendTime/pendingTimer ensure the most recent
  // position is never silently dropped: if a move arrives during the cooldown window, a
  // trailing call is scheduled for right when the cooldown ends, rather than only
  // sending on the next move event (which may never come if the user holds still).
  const SERVO_LIVE_THROTTLE_MS = 120;
  let lastSendTime = 0;
  let pendingTimer = null;

  function sendServoForAxis(nx, ny) {
    const panAngle = crosshairAxisToAngle(19, nx);
    const tiltAngle = crosshairAxisToAngle(20, ny);
    const time = servoTime();
    if (panAngle !== null) api("servo/one", { id: 19, angle: panAngle, time: time });
    if (tiltAngle !== null) api("servo/one", { id: 20, angle: tiltAngle, time: time });
  }

  function sendServoThrottled(nx, ny) {
    const now = Date.now();
    const elapsed = now - lastSendTime;
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
    if (elapsed >= SERVO_LIVE_THROTTLE_MS) {
      lastSendTime = now;
      sendServoForAxis(nx, ny);
    } else {
      pendingTimer = setTimeout(function () {
        pendingTimer = null;
        lastSendTime = Date.now();
        sendServoForAxis(nx, ny);
      }, SERVO_LIVE_THROTTLE_MS - elapsed);
    }
  }

  function axisFromEvent(evt) {
    const rect = pad.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const radius = rect.width / 2;
    const nx = (evt.clientX - cx) / radius;
    const ny = (evt.clientY - cy) / radius;
    return { nx: Math.max(-1, Math.min(1, nx)), ny: Math.max(-1, Math.min(1, ny)) };
  }

  function onPointerDown(evt) {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    crosshairDragging = true;
    cameraElements().crosshairMark.classList.add("dragging");
    pad.setPointerCapture(evt.pointerId);
    const { nx, ny } = axisFromEvent(evt);
    setKnobPosition(nx, ny);
    lastSendTime = Date.now();
    sendServoForAxis(nx, ny); // send immediately on touch-down, not throttled
    evt.preventDefault();
  }

  function onPointerMove(evt) {
    if (!crosshairDragging) return;
    const { nx, ny } = axisFromEvent(evt);
    setKnobPosition(nx, ny);
    sendServoThrottled(nx, ny);
    evt.preventDefault();
  }

  function onPointerUp(evt) {
    if (!crosshairDragging) return;
    crosshairDragging = false;
    cameraElements().crosshairMark.classList.remove("dragging");
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
    setKnobPosition(0, 0); // self-centering, like a physical joystick
    sendServoForAxis(0, 0); // return the head to home immediately, not throttled
  }

  pad.addEventListener("pointerdown", onPointerDown);
  pad.addEventListener("pointermove", onPointerMove);
  pad.addEventListener("pointerup", onPointerUp);
  pad.addEventListener("pointercancel", onPointerUp);

  els.crosshairToggle.addEventListener("change", updateCrosshairVisibility);

  // ---- Keyboard control: arrow keys -> servo 19/20 (pan/tilt), held Space -> talk ----
  //
  // Reuses the same axisToAngle/throttle/knob-position plumbing as pointer-drag above,
  // so keyboard and mouse/touch control feel identical and never fight each other -
  // holding an arrow key is just another way of "dragging the knob" to that key's edge
  // of the pad, at whatever axis value ARROW_KEY_AXIS represents.
  const ARROW_KEY_AXIS = 0.6; // partial deflection, not full min/max, per key press -
                                // a single key only drives one direction at a time
                                // (unlike a drag, which can reach any diagonal), so a
                                // deliberately moderate value avoids the head snapping
                                // to a hard endstop on every tap.
  const heldArrowKeys = new Set();
  let arrowRepeatTimer = null;

  function arrowKeysToAxis() {
    let nx = 0, ny = 0;
    if (heldArrowKeys.has("ArrowLeft")) nx -= 1;
    if (heldArrowKeys.has("ArrowRight")) nx += 1;
    if (heldArrowKeys.has("ArrowUp")) ny -= 1;
    if (heldArrowKeys.has("ArrowDown")) ny += 1;
    return { nx: nx * ARROW_KEY_AXIS, ny: ny * ARROW_KEY_AXIS };
  }

  function applyArrowKeyState() {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    const { nx, ny } = arrowKeysToAxis();
    setKnobPosition(nx, ny);
    sendServoThrottled(nx, ny);
  }

  const ARROW_KEYS = ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"];

  els.viewport.addEventListener("keydown", function (evt) {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    if (ARROW_KEYS.indexOf(evt.key) !== -1) {
      evt.preventDefault(); // stop the page itself from scrolling on arrow keys
      if (!heldArrowKeys.has(evt.key)) {
        heldArrowKeys.add(evt.key);
        applyArrowKeyState();
      }
      return;
    }
    if (evt.key === " " || evt.code === "Space") {
      evt.preventDefault(); // stop Space from also activating a focused button/etc.
      if (!evt.repeat) startTalk(); // ignore the browser's own key-repeat firing, since
                                      // startTalk() is idempotent (talkActive guard) but
                                      // there's no need to call it repeatedly anyway
    }
  });

  els.viewport.addEventListener("keyup", function (evt) {
    if (ARROW_KEYS.indexOf(evt.key) !== -1) {
      heldArrowKeys.delete(evt.key);
      applyArrowKeyState(); // recompute with this key removed - may now be back to (0,0)
      if (heldArrowKeys.size === 0) {
        setKnobPosition(0, 0);
        sendServoForAxis(0, 0); // snap home immediately, matching pointerup's behavior
      }
      return;
    }
    if (evt.key === " " || evt.code === "Space") {
      stopTalk();
    }
  });

  // If the viewport loses keyboard focus entirely (Tab away, click elsewhere) while a
  // key was physically still held down, the corresponding keyup event never reaches
  // this listener - without this, the head or mic could get stuck "on" until some
  // other event happened to reset it.
  els.viewport.addEventListener("blur", function () {
    if (heldArrowKeys.size > 0) {
      heldArrowKeys.clear();
      setKnobPosition(0, 0);
      sendServoForAxis(0, 0);
    }
    if (talkActive) stopTalk();
  });

  // ---- Talk FAB: press-and-hold (mouse/touch), mirroring a physical walkie-talkie's
  // call button - replaces the old dedicated #talkBtn's inline onmousedown/ontouchstart
  // attributes now that the button is generated inside the viewport rather than in the
  // static toolbar row. ----
  if (els.talkFab) {
    els.talkFab.addEventListener("pointerdown", function (evt) {
      evt.preventDefault();
      // Capture the pointer so pointerup still fires on this element even if the
      // finger/mouse drags off the FAB before releasing - without this, dragging off
      // while still pressed would leave talkActive stuck "on" until pointerleave
      // (which covers mouse hover-out, but not always a moved touch-point reliably).
      try { els.talkFab.setPointerCapture(evt.pointerId); } catch (e) { /* ignore */ }
      startTalk();
    });
    els.talkFab.addEventListener("pointerup", function () { stopTalk(); });
    els.talkFab.addEventListener("pointerleave", function () { stopTalk(); });
    els.talkFab.addEventListener("pointercancel", function () { stopTalk(); });
  }
}

/** (Re)points the viewport's <img> at a fresh /stream/camera connection. A query-string
 *  cache-buster forces the browser to open a genuinely new multipart connection instead
 *  of reusing a possibly-dead one it still thinks is "loading". */
function connectCameraStream() {
  const els = cameraElements();
  const viewport = els.viewport, hint = els.hint;
  let img = viewport.querySelector("img");
  if (!img) {
    img = document.createElement("img");
    // Prevent the browser's native "Save image" / "Copy image" context menu, which a
    // long-press (touch) or right-click (desktop) would otherwise show on top of this
    // <img> - that gesture directly conflicts with the talk FAB's press-and-hold
    // (long-pressing near the image could trigger the save menu instead of/alongside
    // starting to talk) and with drag-to-aim on the crosshair pad. draggable=false
    // additionally stops a click-and-drag on the image itself from starting an
    // OS-level "drag this image out" operation, which has the same effect of hijacking
    // what should have been a joystick-pad drag if the pointer happens to be over the
    // image (the pad and image overlap the same viewport area).
    img.oncontextmenu = function () { return false; };
    img.draggable = false;
    img.onload = function () {
      // Fires once when the connection is first established - just used here to show
      // the actual resolution in use. Not fired again per MJPEG part in mainstream
      // browsers, so it is not used as an ongoing liveness signal (see the block
      // comment above).
      api("camera/info", {}).then(function (resp) {
        if (resp && resp.previewWidth) {
          hint.textContent = "實際解像度: " + resp.previewWidth + "x" + resp.previewHeight;
        } else {
          hint.textContent = "";
        }
      }).catch(function () {
        hint.textContent = "";
      });
    };
    img.onerror = function () {
      if (!cameraLiveRunning) return;
      hint.textContent = "鏡頭串流連接失敗,3 秒後重試…";
      setTimeout(function () {
        if (cameraLiveRunning) connectCameraStream();
      }, 3000);
    };
    viewport.appendChild(img);
  }
  img.src = "/stream/camera?t=" + Date.now();
}

// ---------------- Mic: listen to the robot's microphone ----------------
//
// Unlike the camera's <img src="/stream/camera">, which lets the browser handle
// multipart/x-mixed-replace natively, audio has no equivalent built-in tag/MIME
// handling - so this reads the /stream/mic response manually via fetch()+
// ReadableStream, splits on the "--boundary" markers itself, and decodes each
// self-contained WAV chunk with the Web Audio API. Chunks are scheduled to play
// back-to-back using an AudioContext's own clock (nextPlayTime) rather than "play
// immediately on decode" - decodeAudioData is asynchronous and chunks can finish
// decoding slightly out of step with network arrival, so playing on a shared timeline
// is what keeps chunks gapless and in order instead of overlapping or stuttering.

let micListening = false;
let micAbortController = null;
let micAudioContext = null;
let micNextPlayTime = 0;
// Muted (not stopped) while push-to-talk is active - see startTalk()/stopTalk(). The
// /stream/mic connection and AudioController on the Android side keep running exactly
// as before; only the browser-side *playback* of incoming mic chunks is suppressed.
// This is what actually breaks the echo loop reported in logcat_2026-07-01_03-26-09:
// talking sends audio to the robot's AudioTrack/speaker, which then gets picked straight
// back up by the robot's own AudioRecord/mic (no acoustic isolation between the
// speaker and mic on this hardware) and streamed back to the browser as "the robot
// talking" - which is really just an instant echo of what you just said. Muting
// playback while transmitting, mirroring a real half-duplex walkie-talkie (only one
// direction of audio "counts" at a time), removes that loop entirely rather than
// trying to cancel it after the fact.
let micMuted = false;

// Pending raw WAV chunks waiting to be decoded+played, processed strictly one at a
// time by micDrainLoop() (see startMicListen()). runMicStreamLoop()'s read loop used
// to call playWavChunk() directly without awaiting it - since decodeAudioData() is
// itself async and can take a non-trivial amount of time, that let multiple decode
// calls run concurrently. If decoding falls even slightly behind how fast chunks
// arrive (a very plausible steady-state on constrained hardware, not just a transient
// glitch), the number of in-flight decodes only grows over time and each one finishes
// later and later relative to when its audio was actually captured - this is what was
// producing the reported "越聽越慢" (progressively growing ~3s lag): the growing delay
// lived entirely in the decode pipeline, which MIC_MAX_SCHEDULED_LAG_SEC's snap-forward
// logic in playWavChunk() never saw, since that logic only bounds the *scheduled
// playback* of chunks that have ALREADY finished decoding. Concurrent decodes could
// also complete out of their original order, which would have played chunks
// out-of-sequence on top of the growing lag.
const micPendingChunks = [];
// Cap on how many not-yet-decoded chunks are allowed to queue up - if the queue grows
// past this (decode+playback is falling behind arrival), the OLDEST pending chunk(s)
// are dropped so the queue can never silently grow the end-to-end lag without bound;
// a chunk this old is more useful skipped than dutifully played several seconds late.
const MIC_MAX_PENDING_CHUNKS = 3;

function micElements() {
  return {
    btn: document.getElementById("micListenFab"),
  };
}

function toggleMicListen() {
  if (micListening) {
    stopMicListen();
  } else {
    startMicListen();
  }
}

function startMicListen() {
  micListening = true;
  const btn = micElements().btn;
  if (btn) btn.classList.add("listening");
  micAudioContext = new (window.AudioContext || window.webkitAudioContext)();
  micNextPlayTime = 0;
  micPendingChunks.length = 0;
  micAbortController = new AbortController();
  runMicStreamLoop(micAbortController.signal);
  micDrainLoop(micAbortController.signal);
}

function stopMicListen() {
  micListening = false;
  const btn = micElements().btn;
  if (btn) btn.classList.remove("listening");
  micPendingChunks.length = 0;
  if (micAbortController) {
    micAbortController.abort();
    micAbortController = null;
  }
  if (micAudioContext) {
    micAudioContext.close();
    micAudioContext = null;
  }
}

/** Reads /stream/mic and plays each WAV chunk as it arrives; reconnects automatically
 *  (matching the camera stream's own reconnect-on-error behavior) unless the user has
 *  since stopped listening. */
async function runMicStreamLoop(signal) {
  const boundaryMarker = "--opensdktestpanelaudio";
  try {
    const resp = await fetch("/stream/mic?t=" + Date.now(), { signal: signal });
    if (!resp.ok || !resp.body) {
      throw new Error("stream request failed: " + resp.status);
    }
    const reader = resp.body.getReader();
    let buffer = new Uint8Array(0);

    while (micListening) {
      const { value, done } = await reader.read();
      if (done) break;

      const combined = new Uint8Array(buffer.length + value.length);
      combined.set(buffer, 0);
      combined.set(value, buffer.length);
      buffer = combined;

      // Extract every complete part currently in the buffer; a part is
      // "--boundary\r\nheaders\r\n\r\n<wav bytes>\r\n" - find each header/body split by
      // the blank-line marker, and each part's end by the next boundary marker.
      while (true) {
        const text = bytesToLatin1String(buffer);
        const boundaryIdx = text.indexOf(boundaryMarker);
        if (boundaryIdx < 0) break;
        const headerEnd = text.indexOf("\r\n\r\n", boundaryIdx);
        if (headerEnd < 0) break; // headers not fully arrived yet
        const bodyStart = headerEnd + 4;
        const nextBoundaryIdx = text.indexOf(boundaryMarker, bodyStart);
        if (nextBoundaryIdx < 0) break; // body not fully arrived yet

        // Body ends 2 bytes before the next boundary marker (trailing "\r\n").
        const bodyEnd = nextBoundaryIdx - 2;
        const wavBytes = buffer.slice(bodyStart, Math.max(bodyStart, bodyEnd));
        buffer = buffer.slice(nextBoundaryIdx);

        if (wavBytes.length > 44) { // must have at least a WAV header
          const chunkBuf = wavBytes.buffer.slice(wavBytes.byteOffset,
              wavBytes.byteOffset + wavBytes.byteLength);
          micPendingChunks.push(chunkBuf);
          while (micPendingChunks.length > MIC_MAX_PENDING_CHUNKS) {
            micPendingChunks.shift(); // drop the oldest not-yet-decoded chunk
          }
        }
      }
    }
  } catch (e) {
    if (signal.aborted) return; // user stopped listening - not an error
    console.warn("Mic stream ended: " + e.message);
  }
  if (micListening) {
    // Unexpected disconnect while the user still wants to listen - reconnect.
    setTimeout(function () {
      if (micListening) runMicStreamLoop(signal);
    }, 1000);
  }
}

function bytesToLatin1String(bytes) {
  // Latin-1 (not UTF-8) decoding: this is only used to *locate* the ASCII boundary
  // markers and header text by byte offset, not to interpret the binary WAV payload
  // as text - UTF-8 decoding could merge/split multi-byte sequences and throw off the
  // byte offsets used to slice the original buffer.
  let s = "";
  for (let i = 0; i < bytes.length; i++) {
    s += String.fromCharCode(bytes[i]);
  }
  return s;
}

// If the playback schedule has drifted this far ahead of real time, skip the backlog
// instead of playing it out - see playWavChunk()'s comment for why this is what
// actually bounds end-to-end delay, rather than just the server-side queue capacity.
const MIC_MAX_SCHEDULED_LAG_SEC = 0.3;

/** Drains micPendingChunks one at a time - awaits each playWavChunk() fully before
 *  starting the next, so decodeAudioData() calls never run concurrently (see
 *  micPendingChunks' declaration for why that matters: concurrent decodes were the
 *  actual source of the growing multi-second lag, not anything in the playback
 *  scheduling itself). Runs for as long as micListening is true, polling briefly when
 *  the queue is momentarily empty rather than busy-spinning. */
async function micDrainLoop(signal) {
  while (micListening) {
    if (signal.aborted) return;
    const chunk = micPendingChunks.shift();
    if (!chunk) {
      await new Promise(function (resolve) { setTimeout(resolve, 10); });
      continue;
    }
    await playWavChunk(chunk);
  }
}

async function playWavChunk(arrayBuffer) {
  if (!micAudioContext) return;
  let audioBuffer;
  try {
    audioBuffer = await micAudioContext.decodeAudioData(arrayBuffer);
  } catch (e) {
    console.warn("Failed to decode audio chunk: " + e.message);
    return;
  }
  if (!micAudioContext || !micListening) return; // stopped while decoding

  if (micMuted) {
    // Still advance the schedule as if this chunk had played, so that when talking
    // stops and playback resumes, MIC_MAX_SCHEDULED_LAG_SEC's snap-forward logic below
    // finds a huge backlog "in the past" and immediately snaps to "now" - rather than
    // trying to dutifully play out several seconds of muted-period audio all at once.
    const now = micAudioContext.currentTime;
    micNextPlayTime = Math.max(now, micNextPlayTime) + audioBuffer.duration;
    return;
  }

  const source = micAudioContext.createBufferSource();
  source.buffer = audioBuffer;
  source.connect(micAudioContext.destination);

  const now = micAudioContext.currentTime;
  if (micNextPlayTime - now > MIC_MAX_SCHEDULED_LAG_SEC) {
    // The schedule has built up more buffered-ahead audio than we want to tolerate as
    // latency (e.g. this chunk decoded unusually fast after a brief earlier stall, or
    // several chunks landed back-to-back) - snap forward to "now" rather than making
    // this chunk wait out the backlog. This trades a small audible skip for keeping
    // the conversation actually live.
    micNextPlayTime = now;
  }
  const startAt = Math.max(now, micNextPlayTime);
  source.start(startAt);
  micNextPlayTime = startAt + audioBuffer.duration;
}

// ---------------- Walkie-talkie: browser mic -> robot speaker ----------------
//
// AudioPlaybackController's javadoc flags this as unverified: whether the robot's
// speaker is reachable through a plain AudioTrack, as opposed to being reserved for a
// dedicated TTS/audio pipeline, isn't known from static analysis alone. playTestTone()
// exists purely to answer that on the physical unit - press it and listen for a 440Hz
// beep from the robot before relying on push-to-talk actually being audible.
//
// Push-to-talk capture uses ScriptProcessorNode rather than AudioWorkletNode - it's
// deprecated but has far broader browser support, which matters more here than using
// the newer API, since this panel's audience is "whatever browser happens to be on
// hand on the local network" rather than a controlled deployment target.
//
// getUserMedia() is requested at whatever sample rate the browser/OS default mic
// gives (typically 44.1kHz or 48kHz) and then downsampled in JS to 8000Hz mono to
// match AudioPlaybackController's expected format - the robot's AudioTrack is
// configured for a fixed sample rate (see AudioPlaybackController.SAMPLE_RATE_HZ) and
// resampling server-side would be considerably more code than doing it once in the
// browser. Lowered from 16000 to 8000 by request, alongside the same change in
// AudioController.java/AudioPlaybackController.java - all three legs of the audio
// pipeline must agree on sample rate or one side effectively resamples by mismatch
// (pitch-shifted/sped-up audio). Halves the uploaded bytes/sec, reducing both network
// load and the size of each /upload/audio POST.

const TALK_TARGET_SAMPLE_RATE = 8000;
let talkStream = null;
let talkAudioContext = null;
let talkProcessorNode = null;
let talkSourceNode = null;
let talkActive = false;

async function playTestTone() {
  const btn = document.getElementById("testToneBtn");
  const original = btn.textContent;
  btn.textContent = "🔔 播放緊…";
  btn.disabled = true;
  try {
    const resp = await api("audio/testtone", {});
    if (!resp || resp.ok === false) {
      alert("測試喇叭失敗: " + (resp && resp.error ? resp.error : "未知錯誤"));
    }
  } finally {
    setTimeout(function () {
      btn.textContent = original;
      btn.disabled = false;
    }, 1200);
  }
}

async function runAudioDiagnose() {
  const btn = document.getElementById("audioDiagBtn");
  const original = btn.textContent;
  btn.textContent = "🔍 測試緊…";
  btn.disabled = true;
  try {
    const resp = await api("audio/diagnose", {});
    if (resp && resp.results) {
      alert("音頻參數掃描結果:\n\n" + resp.results);
    } else {
      alert("音頻診斷失敗,冧唔到結果");
    }
  } finally {
    btn.textContent = original;
    btn.disabled = false;
  }
}

async function startTalk() {
  if (talkActive) return;

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    // Browsers only expose getUserMedia on secure contexts (HTTPS or localhost) -
    // navigator.mediaDevices itself is simply undefined on a plain http://<lan-ip>/
    // origin like this panel's. There is no way to work around this in JS; the fix
    // has to be at the transport level (e.g. accessing this page via a tunnel/port
    // forward that presents as localhost to the browser, or serving over HTTPS).
    alert("呢個瀏覽器唔俾用麥克風功能,因為呢版面用緊 http:// (非安全來源)。"
        + "瀏覽器安全限制:麥克風/攝像頭錄音 API 只喺 https:// 或者 localhost 先開放,"
        + "呢個係瀏覽器本身嘅政策,呢個頁面做極都繞唔過。");
    return;
  }

  talkActive = true;
  const fab = document.getElementById("talkFab");
  if (fab) fab.classList.add("talking");
  // Mute incoming mic playback for the duration of talking - see micMuted's
  // declaration above for why (breaks the speaker->mic acoustic echo loop). Muting
  // happens even if mic-listen isn't currently on, which is harmless (playWavChunk()
  // simply isn't called at all in that case).
  micMuted = true;

  try {
    talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    alert("攞唔到麥克風權限: " + e.message);
    talkActive = false;
    micMuted = false;
    if (fab) fab.classList.remove("talking");
    return;
  }

  // Everything below this point (AudioContext/ScriptProcessor setup) is wrapped in its
  // own try/catch too - this used to be unguarded, and a failure here (observed in
  // practice as "Failed to execute 'createMediaStreamSource' on 'AudioContext': ...is
  // not of type 'MediaStream'" - an unhandled promise rejection) left talkActive/
  // micMuted stuck at true forever with no cleanup, since the function just died
  // mid-way through. That meant the talk FAB looked "stuck on" and pressing it again
  // did nothing (startTalk() immediately returns early via the "if (talkActive) return"
  // guard at the top) - "之後就再無辦法發射" - and mic-listen stayed silently muted
  // too. Any failure here now falls through to the same full cleanup stopTalk() does,
  // so the FAB/state always recovers to a normal "off" state instead of wedging.
  try {
    await api("audio/play/start", {});

    talkAudioContext = new (window.AudioContext || window.webkitAudioContext)();
    talkSourceNode = talkAudioContext.createMediaStreamSource(talkStream);
    // bufferSize 4096 at the browser's native rate (~44.1/48kHz) is a common choice
    // that balances latency against how often onaudioprocess fires - small enough for
    // push-to-talk to feel responsive, large enough not to flood /upload/audio with
    // requests every few milliseconds.
    talkProcessorNode = talkAudioContext.createScriptProcessor(4096, 1, 1);

    talkProcessorNode.onaudioprocess = function (evt) {
      // Guard against BOTH talkActive and talkAudioContext directly (not just
      // talkActive) - disconnect()/onaudioprocess=null in stopTalk() don't guarantee
      // an in-flight callback invocation is cancelled before it runs, so a callback
      // that was already scheduled can still fire after stopTalk() has already set
      // talkAudioContext to null (observed in practice as "Cannot read properties of
      // null (reading 'sampleRate')" - an unhandled error from exactly this line
      // reading talkAudioContext.sampleRate after it had been nulled out).
      if (!talkActive || !talkAudioContext) return;
      const inputData = evt.inputBuffer.getChannelData(0); // Float32, -1..1
      const nativeSampleRate = talkAudioContext.sampleRate;
      const pcm16 = downsampleToInt16(inputData, nativeSampleRate, TALK_TARGET_SAMPLE_RATE);
      if (pcm16.length > 0) {
        // Fire-and-forget: push-to-talk audio is latency-sensitive, and awaiting each
        // upload here would serialize network round-trips behind onaudioprocess's own
        // timing, adding lag chunk after chunk.
        fetch("/upload/audio", { method: "POST", body: pcm16.buffer }).catch(function (e) {
          console.warn("Audio upload failed: " + e.message);
        });
      }
    };

    talkSourceNode.connect(talkProcessorNode);
    // ScriptProcessorNode requires being connected to a destination to fire
    // onaudioprocess at all, even though the actual output is discarded via gain 0 -
    // the mic audio must not also play back out of this browser's own speakers.
    const silentGain = talkAudioContext.createGain();
    silentGain.gain.value = 0;
    talkProcessorNode.connect(silentGain);
    silentGain.connect(talkAudioContext.destination);
  } catch (e) {
    console.error("startTalk() setup failed after getUserMedia: " + e.message, e);
    alert("開始講嘢失敗: " + e.message + "。已經自動重設,可以再撳一次咪掣試多次。");
    stopTalk(); // full cleanup - same teardown as a normal stop, safe even if some
                 // pieces (talkProcessorNode/talkSourceNode/talkAudioContext) never
                 // got created before the failure, since stopTalk() null-checks each.
  }
}

function stopTalk() {
  if (!talkActive) return;
  talkActive = false;
  const fab = document.getElementById("talkFab");
  if (fab) fab.classList.remove("talking");
  micMuted = false; // resume mic-listen playback now that we've stopped transmitting

  if (talkProcessorNode) {
    talkProcessorNode.disconnect();
    talkProcessorNode.onaudioprocess = null;
    talkProcessorNode = null;
  }
  if (talkSourceNode) {
    talkSourceNode.disconnect();
    talkSourceNode = null;
  }
  if (talkAudioContext) {
    talkAudioContext.close();
    talkAudioContext = null;
  }
  if (talkStream) {
    talkStream.getTracks().forEach(function (t) { t.stop(); });
    talkStream = null;
  }
  api("audio/play/stop", {});
}

/** Downsamples Float32 PCM from the browser's native mic sample rate to
 *  targetRate (8kHz), converting to Int16 in the same pass to match
 *  AudioPlaybackController's expected wire format. Simple nearest-neighbor
 *  decimation rather than a proper resampling filter - adequate for voice at these
 *  rates, and far less code than a windowed-sinc resampler for a push-to-talk feature
 *  where perfect audio fidelity isn't the goal. */
function downsampleToInt16(float32Data, nativeRate, targetRate) {
  if (targetRate >= nativeRate) {
    // Shouldn't happen (native mic rates are always >= 8kHz in practice), but guard
    // against a divide producing a zero/negative step.
    const out = new Int16Array(float32Data.length);
    for (let i = 0; i < float32Data.length; i++) {
      out[i] = Math.max(-32768, Math.min(32767, Math.round(float32Data[i] * 32767)));
    }
    return out;
  }
  const ratio = nativeRate / targetRate;
  const outLength = Math.floor(float32Data.length / ratio);
  const out = new Int16Array(outLength);
  for (let i = 0; i < outLength; i++) {
    const sample = float32Data[Math.floor(i * ratio)];
    out[i] = Math.max(-32768, Math.min(32767, Math.round(sample * 32767)));
  }
  return out;
}

/** Double-click/double-tap on the viewport toggles native fullscreen on that
 *  element, so the video (well - photo sequence) fills the whole screen. */
function toggleCameraFullscreen() {
  const viewport = cameraElements().viewport;
  const fsElement = document.fullscreenElement || document.webkitFullscreenElement;
  if (fsElement) {
    (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  } else {
    const request = viewport.requestFullscreen || viewport.webkitRequestFullscreen;
    if (request) {
      request.call(viewport);
    } else {
      showError("全螢幕", new Error("此瀏覽器不支援 Fullscreen API"));
    }
  }
}

// ---------------- Head / misc ----------------

function headNoise(on) {
  return api("head/noise", { on: String(on) });
}
function requestUuid() {
  document.getElementById("uuidOut").innerHTML = "查詢中…";
  return api("misc/request_uuid");
  // Result arrives asynchronously via the "robot_uuid" WebSocket event (see appendLog's
  // companion handler below) rather than in this HTTP response.
}

// ---------------- WebSocket event log ----------------

let ws;
function connectWs() {
  const proto = location.protocol === "https:" ? "wss://" : "ws://";
  ws = new WebSocket(proto + location.host + "/ws");

  ws.onopen = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "已連接 (WebSocket live)" });
  };
  ws.onclose = function () {
    appendLog({ type: "connection", time: nowTimeStr(), data: "已斷線，3秒後重連…" });
    setTimeout(connectWs, 3000);
  };
  ws.onerror = function () { ws.close(); };
  ws.onmessage = function (evt) {
    try {
      const msg = JSON.parse(evt.data);
      appendLog(msg);
    } catch (e) {
      appendLog({ type: "raw", time: "", data: evt.data });
    }
  };
}

function nowTimeStr() {
  return new Date().toLocaleTimeString("zh-HK", { hour12: false });
}

function appendLog(msg) {
  const log = document.getElementById("eventLog");
  const line = document.createElement("div");
  line.className = "log-line log-type-" + msg.type;
  const dataStr = typeof msg.data === "object" ? JSON.stringify(msg.data) : msg.data;
  line.innerHTML = "<span class=\"log-time\">[" + msg.time + "]</span> <b>" + msg.type + "</b> " + escapeHtml(dataStr);
  log.appendChild(line);
  if (document.getElementById("autoScroll").checked) {
    log.scrollTop = log.scrollHeight;
  }

  // A couple of event types also update a dedicated tile, not just the scrolling log,
  // since the HTTP call that triggered them (requestRobotUUID(), the battery receiver)
  // doesn't carry the actual result back in its own response.
  if (msg.type === "robot_uuid" && msg.data && msg.data.uuid) {
    const el = document.getElementById("uuidOut");
    if (el) el.textContent = msg.data.uuid;
  }
  if (msg.type === "battery" && msg.data) {
    const el = document.getElementById("batteryOut");
    if (el) el.textContent = msg.data.level + "/" + msg.data.scale + " " + (msg.data.charging ? "⚡充電中" : "") + " (" + msg.data.status + ")";
  }
  if (msg.type === "asr_result" && msg.data) {
    const el = document.getElementById("asrOut");
    if (el) el.textContent = msg.data.text;
  }
}

function clearLog() {
  document.getElementById("eventLog").innerHTML = "";
}

function escapeHtml(s) {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- init ----------------

window.addEventListener("DOMContentLoaded", function () {
  buildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  onEngineChange(); // disable voice picker for the default-selected engine (nuance)
  refreshStatus();
  refreshDeviceInfo();
  connectWs();
});
