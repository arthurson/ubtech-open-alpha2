// Open Alpha2 — client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// ---------------- UI language (whole-panel zh/en translation) ----------------
//
// Single source of truth for language across the whole panel - this drives both the
// surrounding UI chrome (headings, button labels, static hints) via [data-i18n]-tagged
// elements, AND which language action names display as (chips in the Actions tabs -
// see displayNameOf()/lynxDisplayNameOf() below). There used to be a separate
// per-tab action-name-language toggle (activeActionLang/activeLynxActionLang); it was
// removed so there's only ever one language switch in the whole app - see README.
let uiLang = localStorage.getItem("ui_lang") || "zh";

// key -> {zh, en}. Applied to any element carrying data-i18n="key" via textContent,
// except elements also carrying data-i18n-attr (see applyUiLanguage) which are
// translated via an attribute (title/placeholder) instead.
const I18N = {
  // -- nav bar (shared by Alpha2 / Lynx) --
  nav_status:             { zh: "狀態",            en: "Status" },
  nav_actions:            { zh: "動作",            en: "Actions" },
  nav_servo:              { zh: "伺服",            en: "Servo" },
  nav_motor:              { zh: "馬達",            en: "Motor" },
  nav_speech:             { zh: "語音",            en: "Speech" },
  nav_led:                { zh: "LED",             en: "LED" },
  nav_camera:             { zh: "相機",            en: "Camera" },
  nav_blockly:            { zh: "🧩 積木編程 ↗",   en: "🧩 Blockly ↗" },
  nav_blockly_title:      { zh: "喺新分頁開 Blockly 積木編程", en: "Open Blockly visual programming in a new tab" },

  // -- status tab --
  status_system_label:   { zh: "系統：", en: "System:" },
  lang_switch_label:     { zh: "語言：", en: "Language:" },
  status_heading:        { zh: "系統狀態",        en: "Status" },
  device_info_heading:   { zh: "裝置資訊",        en: "Device Info" },
  device_battery:        { zh: "🔋 電池",         en: "🔋 Battery" },
  device_wifi:           { zh: "📶 WiFi",         en: "📶 WiFi" },
  device_bluetooth:      { zh: "🔷 藍牙",         en: "🔷 Bluetooth" },
  device_uuid:           { zh: "🤖 機械人 UUID",  en: "🤖 Robot UUID" },
  uuid_query_btn:        { zh: "查詢",            en: "Query" },
  head_noise_heading:    { zh: "頭部降噪",        en: "Head Noise Reduction" },
  sonar_heading:         { zh: "聲納",            en: "Sonar" },
  accel_heading:         { zh: "加速度計",        en: "Accelerometer" },

  // -- actions tab (Alpha2 + Lynx) --
  actions_heading:       { zh: "動作 (Actions)",  en: "Actions" },
  lynx_actions_heading:  { zh: "動作 (Actions)",  en: "Actions" },
  actions_load_btn:      { zh: "攞動作列表",      en: "Load Action List" },
  action_name_placeholder:{ zh: "動作名稱 e.g. ACT0", en: "Action name e.g. ACT0" },
  action_play_btn:       { zh: "播放",            en: "Play" },
  action_stop_btn:       { zh: "停止",            en: "Stop" },
  lynx_action_id_placeholder: { zh: "動作 ID（撳上面嘅 chip 自動填入並播放，或自行輸入例如 wave01）", en: "Action ID (tap a chip above to auto-fill and play, or type e.g. wave01)" },
  lynx_action_id_hint:   { zh: "播放要用動作 ID（.ubx 檔名），唔係顯示名稱 — 撳上面嘅 chip 會即刻播放 (撳新嘅動作會自動停咗個舊嘅先播)",
                            en: "Playback uses the action ID (.ubx filename), not the display name — tapping a chip above plays it immediately (tapping a new one auto-stops the previous)" },

  // -- servo tab --
  servo_heading:         { zh: "伺服馬達 (Servos, 1–20)", en: "Servos (1–20)" },
  servo_hint:            { zh: "拖動滑桿, 放手即送出, 自動夾喺安全範圍內。", en: "Drag a slider and release to send — values are auto-clamped to a safe range." },
  servo_time_label:      { zh: "時間(ms)：",       en: "Time (ms):" },
  servo_reset_btn:       { zh: "全部回到中位",     en: "Reset All to Center" },
  servo_power_save:      { zh: "省電",             en: "Power Save" },

  // -- speech tab --
  asr_heading:           { zh: "ASR (語音辨識)",   en: "ASR (Speech Recognition)" },
  engine_label:          { zh: "引擎：",           en: "Engine:" },
  asr_start_btn:         { zh: "開始聆聽",         en: "Start Listening" },
  asr_stop_btn:          { zh: "停止聆聽",         en: "Stop Listening" },
  tts_heading:           { zh: "語音 / TTS",       en: "Speech / TTS" },
  tts_text_placeholder:  { zh: "要講嘅文字",       en: "Text to speak" },
  tts_speak_btn:         { zh: "講嘢 (TTS)",       en: "Speak (TTS)" },
  tts_stop_btn:          { zh: "停止 TTS",         en: "Stop TTS" },
  mic_release_btn:       { zh: "釋放麥克風俾 App", en: "Release Mic to App" },
  mic_return_btn:        { zh: "交返麥克風俾機器人", en: "Return Mic to Robot" },
  self_interrupt_label:  { zh: "自我打斷",         en: "Self-Interrupt" },
  volume_heading:        { zh: "媒體音量",         en: "Media Volume" },
  volume_hint:           { zh: "控制機械人喇叭嘅媒體音量 (STREAM_MUSIC)，同實體 +/- 按鈕共用同一個音量。",
                            en: "Controls the robot speaker's media volume (STREAM_MUSIC) — shares the same level as the physical +/- buttons." },

  // -- LED tab (Alpha2) --
  led_head_heading:      { zh: "頭部 LED",         en: "Head LED" },
  led_eye_heading:       { zh: "眼睛 LED",         en: "Eye LED" },
  led_mouth_heading:     { zh: "咀部 LED",         en: "Mouth LED" },
  led_color_label:       { zh: "顏色：",           en: "Color:" },
  led_brightness_label:  { zh: "亮度 (1–9)：",     en: "Brightness (1–9):" },
  led_mouth_speed_label: { zh: "速度 (0–5000)：",  en: "Speed (0–5000):" },
  led_preset_long:       { zh: "💡 長開",          en: "💡 On" },
  led_preset_flash:      { zh: "⚡ 閃燈",          en: "⚡ Flash" },
  led_preset_breathe:    { zh: "🫧 呼吸燈",        en: "🫧 Breathe" },
  led_preset_breathe_mouth: { zh: "🫁 呼吸燈",     en: "🫁 Breathe" },
  led_preset_chase:      { zh: "🏃 跑馬燈",        en: "🏃 Chase" },
  led_preset_dual:       { zh: "🎨 雙色燈",        en: "🎨 Dual Color" },
  led_preset_stop:       { zh: "⏹ 停止",          en: "⏹ Stop" },

  // -- camera tab --
  camera_heading:        { zh: "相機",             en: "Camera" },
  camera_feature_key:    { zh: "功能鍵",           en: "Feature Key" },

  // -- Lynx status --
  lynx_status_heading:   { zh: "系統狀態 (Lynx 3.0.0.2)", en: "Status (Lynx 3.0.0.2)" },

  // -- event log --
  event_log_heading:     { zh: "即時事件 Log (WebSocket)", en: "Live Event Log (WebSocket)" },
  clear_log_btn:         { zh: "清空 Log",         en: "Clear Log" },
  auto_scroll_label:     { zh: "自動捲動",         en: "Auto-scroll" },
};

/** Applies uiLang to every [data-i18n]-tagged element currently in the DOM.
 *  Elements tagged data-i18n-attr="placeholder" (etc) get the translation written to
 *  that attribute instead of textContent - needed for <input placeholder="...">
 *  where the text isn't a child text node. */
function applyUiLanguage() {
  document.querySelectorAll("[data-i18n]").forEach(function (el) {
    const entry = I18N[el.dataset.i18n];
    if (!entry) return;
    const text = entry[uiLang] || entry.zh;
    const attr = el.dataset.i18nAttr;
    if (attr) {
      el.setAttribute(attr, text);
    } else {
      el.textContent = text;
    }
  });
  document.querySelectorAll("[data-ui-lang-btn]").forEach(function (btn) {
    btn.classList.toggle("active", btn.dataset.uiLangBtn === uiLang);
  });
}

function setUiLanguage(lang) {
  if (lang !== "zh" && lang !== "en") return;
  uiLang = lang;
  localStorage.setItem("ui_lang", lang);
  applyUiLanguage();
  // Category tab labels (基本/跳舞/... and 全部) are built dynamically from
  // ACTION_CATEGORIES, not tagged with data-i18n, so applyUiLanguage() alone won't
  // update them - re-run the builders if the action lists are already loaded.
  if (allActions.length > 0) {
    buildActionSubTabs();
    renderActionList();
  }
  if (typeof lynxAllActions !== "undefined" && lynxAllActions.length > 0) {
    buildLynxActionSubTabs();
    lynxRenderActionList();
  }
  // Servo group/slider labels are also built dynamically (not [data-i18n]-tagged) -
  // relabel in place rather than calling buildServoGrid()/lynxBuildServoGrid() again,
  // since a full rebuild would reset every slider back to its calibrated home
  // position and lose whatever angle the person currently has dialled in.
  relabelServoGrid();
}

/** Re-applies servo group headings and #id-name slider labels for the current
 *  uiLang, on both the Alpha2 and Lynx servo grids, without touching slider
 *  positions/values (see setUiLanguage() above for why not to rebuild). */
function relabelServoGrid() {
  document.querySelectorAll(".servo-group").forEach(function (groupEl, idx) {
    const group = SERVO_GROUPS[idx % SERVO_GROUPS.length];
    if (!group) return;
    const title = groupEl.querySelector(".servo-group-title");
    if (title) {
      title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + (uiLang === "en" ? group.labelEn : group.label);
    }
    groupEl.querySelectorAll(".servo-slider-label").forEach(function (labelEl) {
      const m = /^#(\d+)/.exec(labelEl.textContent);
      if (!m) return;
      const id = parseInt(m[1], 10);
      labelEl.textContent = "#" + id + " " + servoNameOf(id);
    });
  });
}

// ---------------- Backend switch (Alpha2 1.1.7.3 / Lynx 3.0.0.2) ----------------
// The two AIDL system services never coexist on one robot (see README) - this just
// remembers which one the person told the browser to talk to, and prefixes every
// api() call accordingly ("alpha2/..." or "lynx/..."). Persisted in localStorage so
// a reload / another tab on the same LAN keeps the same choice.
let currentBackend = localStorage.getItem("backend") || "alpha2";

function switchBackend(backend) {
  if (backend !== "alpha2" && backend !== "lynx") return;
  currentBackend = backend;
  localStorage.setItem("backend", backend);
  document.getElementById("backendBtnAlpha2").classList.toggle("active", backend === "alpha2");
  document.getElementById("backendBtnAlpha2FromLynx").classList.toggle("active", backend === "alpha2");
  document.getElementById("backendBtnLynx").classList.toggle("active", backend === "lynx");
  document.getElementById("backendBtnLynxFromAlpha2").classList.toggle("active", backend === "lynx");
  document.getElementById("navAlpha2").style.display = backend === "alpha2" ? "" : "none";
  document.getElementById("navLynx").style.display = backend === "lynx" ? "" : "none";
  document.getElementById("pagesAlpha2").style.display = backend === "alpha2" ? "" : "none";
  document.getElementById("pagesLynx").style.display = backend === "lynx" ? "" : "none";
  // accelCard is one shared DOM node (one canvas, one set of ids - see its own comment
  // in index.html for why it isn't duplicated like the rest of the status tab), so it
  // physically moves between the two status tabs' containers rather than each backend
  // having its own copy. appendChild() on a node that's already in the DOM moves it,
  // it doesn't clone it - Alpha2's tab-status is accelCard's original/default parent.
  const accelCard = document.getElementById("accelCard");
  const accelTarget = backend === "lynx"
      ? document.getElementById("lynxAccelSlot")
      : document.getElementById("tab-status");
  if (accelCard && accelTarget && accelCard.parentElement !== accelTarget) {
    accelTarget.appendChild(accelCard);
  }
  // Tell the robot side what the person picked (purely informational - see
  // handleSystemApi() - every endpoint is always reachable regardless of this value;
  // this just lets a fresh tab default to the last choice made on this robot).
  fetch(API + "system/backend/set?backend=" + backend).catch(function () {});
  if (backend === "alpha2") {
    refreshStatus();
    refreshDeviceInfo();
  } else {
    lynxRefreshStatus();
    lynxRefreshSys();
  }
}

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
  1:  { zh: "右肩上下", en: "R Shoulder Pitch" },
  2:  { zh: "右肩左右", en: "R Shoulder Roll" },
  3:  { zh: "右肘",     en: "R Elbow" },
  4:  { zh: "左肩上下", en: "L Shoulder Pitch" },
  5:  { zh: "左肩左右", en: "L Shoulder Roll" },
  6:  { zh: "左肘",     en: "L Elbow" },
  7:  { zh: "右股左右", en: "R Hip Roll" },
  8:  { zh: "右股上下", en: "R Hip Pitch" },
  9:  { zh: "右膝",     en: "R Knee" },
  10: { zh: "右腳掌上下", en: "R Ankle Pitch" },
  11: { zh: "右腳掌左右", en: "R Ankle Roll" },
  12: { zh: "左股左右", en: "L Hip Roll" },
  13: { zh: "左股上下", en: "L Hip Pitch" },
  14: { zh: "左膝",     en: "L Knee" },
  15: { zh: "左腳掌上下", en: "L Ankle Pitch" },
  16: { zh: "左腳掌左右", en: "L Ankle Roll" },
  17: { zh: "右指",     en: "R Hand" },
  18: { zh: "左指",     en: "L Hand" },
  19: { zh: "頭左右",   en: "Head Yaw" },
  20: { zh: "頭上下",   en: "Head Pitch" },
};

/** 攞返一個 servo 嘅顯示名, 跟主 UI 語言 (uiLang)。 */
function servoNameOf(id) {
  const entry = SERVO_NAMES[id];
  if (!entry) return String(id);
  return uiLang === "en" ? (entry.en || entry.zh) : (entry.zh || entry.en);
}

// Body-part grouping for the servo panel, per the user's mapping:
// head 19/20, right arm 1/2/3/17, left arm 4/5/6/18,
// right leg 7/8/9/10/11, left leg 12/13/14/15/16.
const SERVO_GROUPS = [
  { key: "head",       label: "頭",  labelEn: "Head",      icon: "🧠", ids: [19, 20] },
  { key: "right-arm",  label: "右手", labelEn: "R Arm",     icon: "💪", ids: [1, 2, 3, 17] },
  { key: "left-arm",   label: "左手", labelEn: "L Arm",     icon: "💪", ids: [4, 5, 6, 18] },
  { key: "right-leg",  label: "右腳", labelEn: "R Leg",     icon: "🦵", ids: [7, 8, 9, 10, 11] },
  { key: "left-leg",   label: "左腳", labelEn: "L Leg",     icon: "🦵", ids: [12, 13, 14, 15, 16] },
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
  return fetch(API + "alpha2/" + path + qs).then(function (res) {
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

// Same contract as api() above, but always hits the Lynx (3.0.0.2) backend
// regardless of currentBackend - so Lynx UI code doesn't need to care which
// backend is "selected", it just always calls its own endpoints.
function lynxApi(path, params) {
  clearError();
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  return fetch(API + "lynx/" + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /lynx/" + path, new Error(json.error || json.code || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError("Network error calling /lynx/" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

// Camera and audio-testtone/volume/play are plain Android hardware access, not
// implemented by either AIDL backend (see LynxController#isSharedHardwarePath()) -
// same physical camera/mic/speaker regardless of which robot SDK is selected. Both
// "/api/alpha2/camera/..." and "/api/lynx/camera/..." now resolve to the same Java
// code on the robot side, so this just follows whichever backend is currently
// selected rather than hardcoding "alpha2/" - keeps working no matter which tab
// (Alpha2 or Lynx) the person is on.
function hwApi(path, params) {
  return currentBackend === "lynx" ? lynxApi(path, params) : api(path, params);
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
  { key: "basic",  label: "基本", labelEn: "Basic", color: "#3b7dff" },
  { key: "dance",  label: "跳舞", labelEn: "Dance", color: "#db2777" },
  { key: "story",  label: "故事", labelEn: "Story", color: "#d97706" },
  { key: "yoga",   label: "瑜伽", labelEn: "Yoga",  color: "#16a34a" },
  { key: "others", label: "其他", labelEn: "Other", color: "#6b7280" },
];
// 白名單: 數字 type (actionInfo.txt 靜態格式) 同文字 type (部分機身 runtime 格式) 都對應埋。
const ACTION_CATEGORY_MAP = {
  "1": "basic", "2": "dance", "3": "story", "4": "yoga",
  basic: "basic", dance: "dance", story: "story", yoga: "yoga",
};
let allActions = [];
let activeActionCategory = "basic";

// 動作 ID -> {main, sub} 嘅子分類對照表, 由 action_classification.json 讀入 (見
// action_classified.txt 嘅整理來源)。呢個 mapping 淨係喺 asset 檔案有出現嘅動作先會有
// sub 分類 - 冇出現嘅動作仍然跟返 categoryOf() 嗰個大分類, 但喺嗰個大分類入面冇
// 子分類 tab 可以揀 (即係直接混喺主列表, 冚方向上等於落咗嗰個大分類嘅"其他")。
// 呢個表故意唔喺 code 度寫死: 下次要再分類就淨係改/換份 json, 唔使動 app.js。
let actionClassification = {}; // id -> {main, sub}
let activeActionSubCategory = null; // null = 顯示嗰個大分類入面全部動作 (未揀子分類)

function loadActionClassification() {
  return fetch("action_classification.json").then(function (r) {
    if (!r.ok) throw new Error("http " + r.status);
    return r.json();
  }).then(function (json) {
    actionClassification = json || {};
  }).catch(function (e) {
    // 冇呢個檔案或者讀取失敗都唔應該累到成個動作 tab 用唔到 - 淨係冇子分類 tab,
    // 主分類(基本/跳舞/故事/瑜伽/其他)照舊運作。
    console.warn("action_classification.json 讀取失敗, 子分類 tab 將唔會出現:", e);
    actionClassification = {};
  });
}

function categoryOf(rawType) {
  return ACTION_CATEGORY_MAP[rawType] || "others";
}

/** 攞返一個動作嘅子分類名 (例如「移動類」), 冇對照到就 null。 */
function subCategoryOf(action) {
  const entry = actionClassification[action.id];
  return entry ? entry.sub : null;
}

/** 攞返一個動作應該顯示嘅名: 跟主 UI 語言 (uiLang), 但如果嗰個語言冇資料 (例如英文名同
 *  中文名一樣, 或者其中一邊係空), 就 fallback 去另一個, 唔會顯示空白 chip。 */
function displayNameOf(action) {
  if (uiLang === "en") {
    return action.nameEn || action.nameCn;
  }
  return action.nameCn || action.nameEn;
}

function loadActionList() {
  const listEl = document.getElementById("actionList");
  listEl.textContent = "載入中…";
  return Promise.all([api("action/list"), loadActionClassification()]).then(function (results) {
    const data = results[0];
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
    btn.textContent = (uiLang === "en" ? c.labelEn : c.label) + " (" + count + ")";
    btn.onclick = function () {
      activeActionCategory = c.key;
      activeActionSubCategory = null; // 轉咗大分類, 子分類篩選重置返做「全部」
      buildActionSubTabs();
      buildActionSubSubTabs();
      renderActionList();
    };
    bar.appendChild(btn);
  });
  buildActionSubSubTabs();
}

// Fixed palette for sub-category tabs (移動類/手勢類/...), cycled by index so each
// sub-category gets a distinct, stable colour regardless of which main category it's
// under - deliberately a separate palette from ACTION_CATEGORIES' colours so the two
// tab levels stay visually distinguishable from each other.
const SUB_CATEGORY_COLORS = [
  "#0891b2", "#ca8a04", "#9333ea", "#059669", "#e11d48",
  "#2563eb", "#c2410c", "#4d7c0f", "#be185d", "#0d9488",
];

// English labels for the sub-categories that come from action_classification.json
// (see loadActionClassification() above). The Chinese string is still the filter key
// used everywhere else (subCategoryOf() / activeActionSubCategory) - this table is
// purely for what's shown on the tab when uiLang === "en", so it never needs to touch
// action_classification.json or action_classified.txt (source of truth for the actual
// classification). Add an entry here whenever a new sub value shows up in that file.
const SUB_CATEGORY_LABELS_EN = {
  "移動類":            "Locomotion",
  "手勢類":            "Gestures",
  "頭部類":            "Head",
  "表情 / 互動類":      "Expression / Interaction",
  "全身 / 其他動作":    "Full Body / Other",
  "伸展式":            "Stretch",
  "站立式 / 平衡式":    "Standing / Balance",
  "騎馬式":            "Horse Stance",
  "踢腿 / 動態式":      "Kick / Dynamic",
  "流行 / 節奏舞蹈":    "Pop / Rhythm Dance",
  "兒童歌曲 / 卡通舞蹈": "Kids Songs / Cartoon Dance",
  "品牌 / 客製舞蹈":    "Brand / Custom Dance",
  "中國寓言":          "Chinese Fables",
  "西方寓言 / 故事":    "Western Fables / Stories",
};

/** 攞返一個子分類應該顯示嘅名: 跟主 UI 語言 (uiLang)。內部 filter 仍然用返 `sub`
 *  (中文) 呢個 key, 呢個 function 淨係用喺顯示層。冇對照到就照原文顯示, 唔會有
 *  空白 tab。 */
function subCategoryDisplayName(sub) {
  if (uiLang === "en") {
    return SUB_CATEGORY_LABELS_EN[sub] || sub;
  }
  return sub;
}

/** Builds the second-level sub-category tabs (e.g. 移動類/手勢類/頭部類/... within
 *  基本) for whichever main category is currently active. Only sub-categories that
 *  actually appear in action_classification.json for this main category show up as
 *  tabs - if the classification file doesn't cover a main category at all (or none
 *  of its actions matched), no sub-tab bar appears and the flat action list shows,
 *  same as before this feature existed. An always-present "全部" tab clears the
 *  sub-category filter back to showing everything in the main category. */
function buildActionSubSubTabs() {
  const bar = document.getElementById("actionSubSubTabBar");
  bar.innerHTML = "";

  // Sub-category labels, in the order first encountered in the classification file,
  // restricted to actions that belong to the currently active main category.
  const subsInOrder = [];
  allActions.forEach(function (a) {
    if (categoryOf(a.type) !== activeActionCategory) return;
    const sub = subCategoryOf(a);
    if (sub && subsInOrder.indexOf(sub) === -1) subsInOrder.push(sub);
  });

  if (subsInOrder.length === 0) {
    activeActionSubCategory = null;
    return; // 呢個大分類冇任何子分類資料 - 唔顯示呢層 tab bar
  }

  const allBtn = document.createElement("button");
  allBtn.className = "sub-tab-btn" + (activeActionSubCategory === null ? " active" : "");
  allBtn.textContent = uiLang === "en" ? "All" : "全部";
  allBtn.onclick = function () {
    activeActionSubCategory = null;
    buildActionSubSubTabs();
    renderActionList();
  };
  bar.appendChild(allBtn);

  subsInOrder.forEach(function (sub, i) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (sub === activeActionSubCategory ? " active" : "");
    btn.style.setProperty("--sub-tab-color", SUB_CATEGORY_COLORS[i % SUB_CATEGORY_COLORS.length]);
    const count = allActions.filter(function (a) {
      return categoryOf(a.type) === activeActionCategory && subCategoryOf(a) === sub;
    }).length;
    btn.textContent = subCategoryDisplayName(sub) + " (" + count + ")";
    btn.onclick = function () {
      activeActionSubCategory = sub;
      buildActionSubSubTabs();
      renderActionList();
    };
    bar.appendChild(btn);
  });
}

function renderActionList() {
  const listEl = document.getElementById("actionList");
  const filtered = allActions.filter(function (a) {
    if (categoryOf(a.type) !== activeActionCategory) return false;
    if (activeActionSubCategory !== null && subCategoryOf(a) !== activeActionSubCategory) return false;
    return true;
  });

  if (filtered.length === 0) {
    listEl.textContent = "(冇動作 / 服務未初始化)";
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = displayNameOf(a);
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
//
// 2026-08 更新: 引擎/聲音由 <select> 改做按鈕組 (同 switchAsrEngine 嗰邊嘅
// .lang-toggle 樣式一致) —— 3 個引擎鍵常駐, 聲音嗰 5 個鍵獨立一行, 淨係
// currentTtsEngine === "iflytek" 先顯示 (揀 Nuance/Android 預設嗰行會完全
// 消失, 唔淨係 disable)。用返 currentTtsEngine/currentTtsVoice 呢兩個模組
// 層變數記住目前揀緊乜, 唔再靠 <select>.value 讀。
let currentTtsEngine = "nuance"; // 預設同舊 <select> 個第一個 option 一致
let currentTtsVoice = "";

function setTtsEngine(engine) {
  currentTtsEngine = engine;
  document.getElementById("ttsEngineNuanceBtn").classList.toggle("active", engine === "nuance");
  document.getElementById("ttsEngineIflytekBtn").classList.toggle("active", engine === "iflytek");
  document.getElementById("ttsEngineAndroidBtn").classList.toggle("active", engine === "android");

  const voiceRow = document.getElementById("ttsVoiceRow");
  if (engine === "iflytek") {
    voiceRow.style.display = "";
  } else {
    voiceRow.style.display = "none";
    currentTtsVoice = "";
    setTtsVoice("");
  }
}

function setTtsVoice(voice) {
  currentTtsVoice = voice;
  document.getElementById("ttsVoiceDefaultBtn").classList.toggle("active", voice === "");
  document.getElementById("ttsVoiceCatherineBtn").classList.toggle("active", voice === "catherine");
  document.getElementById("ttsVoiceJohnBtn").classList.toggle("active", voice === "john");
  document.getElementById("ttsVoiceXiaofengBtn").classList.toggle("active", voice === "xiaofeng");
  document.getElementById("ttsVoiceXiaoyanBtn").classList.toggle("active", voice === "xiaoyan");
}

function speakTts() {
  const text = document.getElementById("ttsText").value.trim();
  if (!text) return alert("請輸入文字");
  const params = { text: text, engine: currentTtsEngine };
  if (currentTtsEngine === "iflytek" && currentTtsVoice) {
    params.voice = currentTtsVoice;
  }
  // 播新嘢之前先停低舊嗰句, 唔係就兩句 TTS 可能撞埋一齊播 (講到一半嗰句仲未
  // 完, 個新 request 已經開始講, 聽落會疊聲/含糊)。stopTts() 失敗都照樣繼續
  // 播放新嘅 (例如冧巴一次冇嘢正播緊, stop 本身可能會 error/no-op, 唔應該
  // 因為咁就唔畀用家繼續講嘢)。
  return stopTts().catch(function () {}).then(function () {
    return api("speech/tts", params);
  });
}

function stopTts() {
  return api("speech/stop");
}

function setMic(wake) {
  return api("speech/set_mic", { wake: String(wake) });
}

// ---------------- Speech / ASR (manual) ----------------
//
// 2026-08 更新: 之前呢度淨係 call speech/set_language, 呢個對 active engine
// 嚟講只係 advisory hint, 唔會真正切去 iFlytek —— 之前得出「iFlytek 唔係
// active engine」嘅結論, 其實係喺冇用中文/iFlytek 專屬 grammar 測試過嘅情況
// 下做嘅 (SDK 原作者唔識中文, 冇試過用中文觸發), 唔代表 iFlytek 呢條路徑本身
// 用唔到。而家改用新加嘅 speech/set_asr_engine, 佢會真正 unbind 現有連接、
// 用指定 engine (ALPHA_NUANCE_SPEECH_MAIN_SERVER 或
// ALPHA_IFLYTEK_SPEECH_MAIN_SERVER) 重新 bind, 而唔係淨係傳個語言提示。
// 呢個 rebind 係 async (等 onServiceConnected), 所以撳「開始聆聽」之前要等
// speech_ready event 返嚟先，UI 度用 speechReadyForAsr 呢個 flag 擋住。
//
// Results don't come back from this call itself: they arrive later, asynchronously,
// as an "asr_result" WebSocket event (published from MainActivity's onServerCallBack)
// and are shown by appendLog() below.
//
// start_asr (speech_startSpeechNoWakeup) was added to trigger recognition without
// waiting for the mic-array hardware's own wake word - see logcat_2026-07-30_07-53-50.txt
// for why set_mic(true) alone couldn't do that. But logcat_2026-07-02_13-38-32.txt (a
// later on-robot test of start_asr itself, done against the Nuance binding) shows it only
// moves the speech engine into SPEECH_STATE_WAKEUP internally (SpeechManager "what:3",
// IflytekWakeUp5mic.startRecording) - actual recognition (IflyteckASR5mic
// "startSpeechASR type:0", "Listening...") still didn't begin until a hardware "MicArray
// wakeup" fired independently, ~20s later. So start_asr does put the robot in a more
// wake-word-receptive state than doing nothing, but it is not the direct trigger this
// button's label implies - hence the phrasing below. This was tested against Nuance;
// whether iFlytek's own wake-word path behaves the same way is still unconfirmed.
let speechReadyForAsr = true; // set false while switchAsrEngine() 嘅 rebind 進行緊
let currentAsrEngine = null; // "iflytek" | "nuance" | null (未撳過任何一個掣之前)

function switchAsrEngine(engine) {
  speechReadyForAsr = false;
  const label = engine === "iflytek" ? "iFlytek" : "Nuance";
  document.getElementById("asrOut").textContent =
    "切緊去 " + label + " 引擎 (重新綁定 speech service 中)…";
  document.getElementById("asrCurrentEngineHint").textContent = "目前引擎：切換中… (" + label + ")";
  document.getElementById("asrSwitchZhBtn").classList.toggle("active", engine === "iflytek");
  document.getElementById("asrSwitchEnBtn").classList.toggle("active", engine === "nuance");
  return api("speech/set_asr_engine", { engine: engine }).then(function () {
    currentAsrEngine = engine;
  }).catch(function (err) {
    speechReadyForAsr = true; // 綁定請求本身都失敗, 唔使等 speech_ready, 解返個 lock
    document.getElementById("asrOut").textContent = "切換引擎失敗: " + (err && err.message ? err.message : err);
    document.getElementById("asrCurrentEngineHint").textContent = "目前引擎：切換失敗";
  });
}

function startAsr() {
  if (!currentAsrEngine) {
    document.getElementById("asrOut").textContent = "請先揀「中文 (iFlytek)」或「英文 (Nuance)」";
    return Promise.resolve();
  }
  if (!speechReadyForAsr) {
    document.getElementById("asrOut").textContent = "引擎重新綁定緊，請等 speech_ready 之後再試";
    return Promise.resolve();
  }
  const lang = currentAsrEngine === "iflytek" ? "zh_cn" : "en_us";
  document.getElementById("asrOut").textContent = "準備聆聽中 — 而家講 \"hello alpha\" 觸發硬件 wake word 偵測，先會真正開始錄音…";
  return api("speech/set_language", { lang: lang }).then(function () {
    return api("speech/start_asr", {});
  });
}

function stopAsr() {
  document.getElementById("asrOut").textContent = "已停止";
  return setMic(false);
}

// 2026-08 新增: 實驗性「重置語音」——見 MainActivity.java speech/reset 個 comment。
// 撳咗上面「中文/英文」切換引擎之後, 機身系統進程嘅 TTS session 有機會啞咗
// (call speech/tts 都回 200, 但完全冇聲), 一直要重開機先返到正常。呢個掣試下
// call stopSpeechAndEnterIdleMode() 睇吓叫唔叫得返個 session, 唔使重開機。
// 未證實一定有效, 純粹試驗。
function resetSpeech() {
  document.getElementById("asrResetHint").textContent = "重置緊…";
  return api("speech/reset", {}).then(function (res) {
    document.getElementById("asrResetHint").textContent = res && res.ok
      ? "已送出重置指令，試下撳返「播放 TTS」有冇聲返嚟"
      : "重置失敗：" + (res && res.error ? res.error : "未知錯誤");
  }).catch(function (err) {
    document.getElementById("asrResetHint").textContent = "重置失敗：" + (err && err.message ? err.message : err);
  });
}

function setSelfInterrupt() {
  const on = document.getElementById("selfInterrupt").checked;
  return api("speech/self_interrupt", { on: String(on) });
}

// ---------------- Service config (/sdcard/actions/service_config.json) -------------
//
// 呢個檔案控制機身開機時嘅 wake word / ASR 語言 / 預設對話 app。實測確認：改咗呢個
// 檔案、重開機之後，wake word 真係會跟住轉。中文／英文兩個 preset 都係機身出廠
// 內置嘅原裝 default config，一字不改。寫入唔會自動重開機，要用家自己撳「立即
// 重開機」，避免手滑撳咗個 preset 掣就即刻累機身重開。
//
// 2026-08 更新: 撳「中文/英文」即寫，唔再彈 confirm —— 呢個掣本身淨係寫入
// config 檔, 唔會即刻令機身重開機 (要另外撳「立即重開機」先真正生效/累機),
// 屬於低風險、可以隨時再撳另一個 preset 覆蓋返嘅操作, 冇必要加多一重確認。
function setServiceConfigPreset(preset) {
  document.getElementById("serviceConfigResult").textContent = "寫入緊…";
  return api("service_config/set", { preset: preset }).then(function (res) {
    const el = document.getElementById("serviceConfigResult");
    el.textContent = res && res.ok
      ? "✅ 已寫入，記得重開機先會生效。"
      : "❌ 失敗：" + (res && res.error ? res.error : "未知錯誤");
  });
}

function rebootRobot() {
  if (!confirm("確定要立即重開機？")) return Promise.resolve();
  document.getElementById("serviceConfigResult").textContent = "重開機緊…";
  return api("service_config/reboot").then(function (res) {
    if (res && res.ok) {
      document.getElementById("serviceConfigResult").textContent = "✅ 重開機緊…";
    } else {
      document.getElementById("serviceConfigResult").textContent =
        "❌ 重開機失敗：" + (res && res.error ? res.error : "未知錯誤") + "（請手動 power-cycle）";
    }
  });
}

// ---------------- Speech / NLU (text understanding, no mic involved) ----------------
//
// speech_understandText() sends the string straight to the robot's semantic engine,
// bypassing ASR entirely. Like ASR, the result doesn't come back in this call's own
// response - it arrives later as a "text_understand" WebSocket event.
// ---------------- Speech input three-way test (same text, three AIDL paths) ---------
//
// Fires the same text at speech/inject, speech/init_grammar (which we auto-chain into
// speech/start_grammar once init reports back), and speech/understand simultaneously,
// so you can compare which of the three actually produces a result for identical input.
// - inject: no dedicated output tile here - result (if any) shows up on the ASR card's
//   existing "辨識結果"/"意圖分類" tiles, same as real speech.
// - grammar: init result shows in grammarInitOut; if init succeeds we chain into
//   start_grammar automatically, whose result/error lands in grammarResultOut via the
//   "grammar_result" WebSocket event.
// - understand (NLU): result/error shows in nluOut via the "text_understand" event.
//
// 2026-08 更新: 反編譯 Alpha2Services-v1.1.7.3.20 證實咗 grammar 呢組 API
// (initSpeechGrammar/startSpeechGrammar) 喺 Nuance binding 之下係完全未實作
// 嘅空 stub (method body 得一句 return-void), 淨係喺 iFlytek binding 之下先
// 有真身實作 (會建立 com.iflytek.cloud.SpeechRecognizer)。backend 喺
// speech/init_grammar 有 guard, 如果而家未切去 iFlytek 會直接回傳 error。
// inject/understand 呢兩條路徑同 engine 揀邊個冇關 (佢哋唔靠 grammar 呢組
// API), 照舊即刻送出唔使等。
//
// 2026-08 取消自動切換: 之前呢度如果而家仲係 Nuance, 會自動幫手切去
// iFlytek 先至真正發送 grammar 測試 —— 依家取消返呢個行為, 唔會再喺用家
// 冇要求嘅情況下自己切 engine。而家如果而家唔係 iFlytek, 直接送出去撞
// backend 個 guard, 將佢個 error message 原封不動顯示喺 grammarInitOut,
// 由用家自己決定要唔要手動 speech/set_asr_engine?engine=iflytek。
function setSpeechTestText(text) {
  document.getElementById("speechTestText").value = text;
  return testAllSpeechInputs();
}

function testAllSpeechInputs() {
  const text = document.getElementById("speechTestText").value.trim();
  if (!text) return alert("請輸入文字");

  document.getElementById("injectOut").textContent = "已送出，睇上面ASR card…";
  document.getElementById("grammarInitOut").textContent = "初始化緊…";
  document.getElementById("grammarResultOut").textContent = "-";
  document.getElementById("nluOut").textContent = "分析緊…";

  api("speech/inject", { text: text });
  api("speech/understand", { text: text });
  runGrammarTest(text);
}

function runGrammarTest(text) {
  return api("speech/init_grammar", { grammar: text }).then(function (res) {
    if (res && res.ok) {
      // Chain into start_grammar so the grammar path gets a real end-to-end
      // attempt, not just init. If init itself failed there is nothing to start.
      api("speech/start_grammar");
    } else {
      document.getElementById("grammarInitOut").textContent =
        (res && res.error) ? res.error : "初始化失敗";
    }
  });
}

// ---------------- Media volume (STREAM_MUSIC) ----------------
//
// Backed by the robot's standard Android STREAM_MUSIC - the same stream the physical
// +/- gesture buttons and TTS/walkie-talkie playback all use, so this slider always
// reflects the robot's real current volume, not just whatever this browser tab last
// set. refreshVolume() is called on page load; the slider's max attribute is set from
// the device's actual getStreamMaxVolume() rather than assumed, since that can vary.

function onVolumeSliderInput(value) {
  // Live-update the numeric readout while dragging; the actual API call only fires on
  // "change" (see the oninput/onchange split on the slider itself in index.html),
  // matching the same drag-then-release pattern used by the servo sliders.
  document.getElementById("volumeVal").textContent = value;
}

function setVolume(value) {
  return hwApi("audio/volume/set", { level: String(value) }).then(function (json) {
    if (json.ok) {
      document.getElementById("volumeSlider").value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
    }
    return json;
  });
}

function refreshVolume() {
  return hwApi("audio/volume/get").then(function (json) {
    if (json.ok) {
      const slider = document.getElementById("volumeSlider");
      slider.max = json.max;
      slider.value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
    }
    return json;
  });
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
    title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + (uiLang === "en" ? group.labelEn : group.label);
    groupEl.appendChild(title);

    group.ids.forEach(function (id) {
      const cal = SERVO_CALIBRATION[id];
      const row = document.createElement("div");
      row.className = "servo-slider-row";
      row.innerHTML =
          "<span class=\"servo-slider-label\">#" + id + " " + servoNameOf(id) + "</span>" +
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

// ---------------- Sonar (chest ultrasonic obstacle sensor) ----------------
//
// distance is assumed to be centimetres directly (unverified on real hardware - see
// the card's own hint text and Alpha2RobotApi#chest_configureSonar's javadoc).
// Slider drag updates the live cm readout only; the actual API call fires on release
// (onchange), matching the servo sliders' drag-then-send pattern elsewhere.
function onSonarSliderInput(v) {
  document.getElementById("sonarDistVal").textContent = v + " cm";
}

function configureSonar(v) {
  const distance = v !== undefined ? v : document.getElementById("sonarDist").value;
  document.getElementById("sonarDistVal").textContent = distance + " cm";
  sonarThresholdCm = Number(distance);
  drawSonarChart();
  return api("servo/sonar", { distance: distance });
}

// Toggle switch: off sends distance=0 (sonar disabled); on re-sends whatever the
// slider is currently set to, so flipping back on restores the last distance rather
// than requiring the person to re-drag the slider.
function toggleSonar() {
  const on = document.getElementById("sonarToggle").checked;
  const distance = on ? document.getElementById("sonarDist").value : "0";
  return api("servo/sonar", { distance: distance });
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

  const [w, h] = (els.resolution ? els.resolution.value : "800x600").split("x").map(Number);
  hint.textContent = "設定解像度…";
  await hwApi("camera/resolution", { w: w, h: h });

  cameraLiveRunning = true;
  btn.textContent = "⏸";
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
  if (mediaRecorder && mediaRecorder.state === "recording") {
    stopRecording(); // avoid recording against an <img> that's about to be removed
  }
  const img = viewport.querySelector("img");
  if (img) {
    img.src = ""; // stop the browser holding the multipart connection open
    img.remove();
  }
  btn.textContent = "▶";
  badge.classList.remove("on");
  if (placeholder) {
    placeholder.style.display = "";
    placeholder.textContent = "";
  }
  els.hint.textContent = "";
  updateCrosshairVisibility();
}

// ---------------- Camera: photo capture ----------------
//
// Reuses the existing GET /api/camera/snapshot endpoint (a single JPEG frame,
// base64-encoded) rather than adding a new server-side "save to file" endpoint -
// simplest path, and it already starts the camera if it isn't running yet.

function addCaptureItem(kind, url, filename, thumbSrc) {
  const list = document.getElementById("cameraCaptureList");
  if (!list) return;
  const item = document.createElement("div");
  item.className = "capture-item";
  if (kind === "photo") {
    const img = document.createElement("img");
    img.src = thumbSrc || url;
    item.appendChild(img);
  } else {
    const video = document.createElement("video");
    video.src = url;
    video.controls = true;
    video.muted = true;
    item.appendChild(video);
  }
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.textContent = kind === "photo" ? "⬇ 下載相片" : "⬇ 下載影片";
  item.appendChild(link);
  list.insertBefore(item, list.firstChild);
}

async function takePhoto() {
  const hint = document.getElementById("cameraStatusHint");
  hint.textContent = "影緊相…";
  try {
    const json = await hwApi("camera/snapshot");
    if (!json.ok) {
      hint.textContent = "影相失敗：" + (json.error || "未知錯誤");
      return;
    }
    // 快門聲由機械人本身出 (見 MainActivity#playShutterCue - 播 "Sirrah" 呢個系統
    // 鈴聲), 唔係喺瀏覽器度合成音效 - alpha2-only, 同 tilt LED/錄影 LED 一致。
    if (currentBackend === "alpha2") api("camera/shutter_sound");
    flashCaptureLed();
    const byteChars = atob(json.jpegBase64);
    const bytes = new Uint8Array(byteChars.length);
    for (let i = 0; i < byteChars.length; i++) bytes[i] = byteChars.charCodeAt(i);
    const blob = new Blob([bytes], { type: "image/jpeg" });
    const url = URL.createObjectURL(blob);
    const filename = "alpha2-photo-" + Date.now() + ".jpg";
    addCaptureItem("photo", url, filename);
    hint.textContent = "已影相 ✓";
  } catch (e) {
    showError("影相", e);
    hint.textContent = "";
  }
}

/** 影相一刻頭/眼 LED 白燈閃半秒 - alpha2-only (同 setRecordingLed()/tilt LED 一致)。
 *  "flash" preset 本身會不斷循環閃落去唔會自動停 (見 led/head/set 個 p5/p6/p7
 *  timing), 所以要自己計時, 500ms 後主動送返 stop / 或者還原返錄影中/聽聲中嗰個
 *  長開色 (見 restoreBaseLed()) - 唔係淨係盲目 stop, 否則影相嗰刻如果啱啱好錄緊
 *  影/聽緊聲, 個燈會俾呢下閃燈永久蓋走底層長開色。 */
function flashCaptureLed() {
  if (currentBackend !== "alpha2") return;
  const headBrightness = document.getElementById("headBrightness").value;
  const eyeBrightness = document.getElementById("eyeBrightness").value;
  api("led/head/set", { preset: "flash", color: 7, brightness: headBrightness });
  api("led/eye/set", { preset: "flash", color: 7, brightness: eyeBrightness });
  setTimeout(restoreBaseLed, 500);
}

/** 攞返而家「底層」應該長開嘅 LED 狀態 - 錄影中(紅) > 聽機械人中(綠) > 冇(熄)。
 *  影相閃燈完之後、或者其他一次性效果完咗之後, 用嚟還原返正確嘅長開狀態。 */
function restoreBaseLed() {
  if (currentBackend !== "alpha2") return;
  const headBrightness = document.getElementById("headBrightness").value;
  const eyeBrightness = document.getElementById("eyeBrightness").value;
  let color = null;
  if (mediaRecorder && mediaRecorder.state === "recording") {
    color = 1; // 紅 - 錄影中
  } else if (micListening) {
    color = 2; // 綠 - 聽機械人中
  }
  if (color === null) {
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
  } else {
    api("led/head/set", { preset: "long", color: color, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: color, brightness: eyeBrightness });
  }
}

// ---------------- Camera: video recording (client-side) ----------------
//
// The server's CameraController only ever produces individual JPEG preview frames (see
// its javadoc: "there is no takePicture()/... cycle any more"), with no MediaRecorder/
// Surface-based encoder pipeline behind it - adding one server-side would risk the
// working live-stream path for comparatively little gain. Instead, recording draws the
// already-live MJPEG <img> onto a hidden <canvas> on a timer and feeds
// canvas.captureStream() into a standard browser MediaRecorder, producing a WebM file
// entirely client-side. Requires the live stream to already be running.

let mediaRecorder = null;
let recordChunks = [];
let recordCanvas = null;
let recordDrawTimer = null;

function pickRecorderMimeType() {
  const candidates = ["video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"];
  for (const type of candidates) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(type)) {
      return type;
    }
  }
  return "";
}

function toggleRecording() {
  if (mediaRecorder && mediaRecorder.state === "recording") {
    stopRecording();
  } else {
    startRecording();
  }
}

function startRecording() {
  const hint = document.getElementById("cameraStatusHint");
  if (!cameraLiveRunning) {
    hint.textContent = "請先開啟鏡頭先可以錄影";
    return;
  }
  if (!window.MediaRecorder) {
    hint.textContent = "此瀏覽器不支援錄影 (MediaRecorder)";
    return;
  }
  const img = cameraElements().viewport.querySelector("img");
  if (!img) {
    hint.textContent = "搵唔到鏡頭畫面";
    return;
  }

  recordCanvas = document.createElement("canvas");
  recordCanvas.width = img.naturalWidth || 640;
  recordCanvas.height = img.naturalHeight || 480;
  const ctx = recordCanvas.getContext("2d");

  // Redraw the current MJPEG frame onto the canvas ~15 times/sec - the <img> itself
  // has no "onframe" event, so polling its current bitmap is the only way to get a
  // stream of frames out of it for captureStream() to encode.
  recordDrawTimer = setInterval(function () {
    if (recordCanvas.width !== (img.naturalWidth || recordCanvas.width)) {
      recordCanvas.width = img.naturalWidth || recordCanvas.width;
      recordCanvas.height = img.naturalHeight || recordCanvas.height;
    }
    try {
      ctx.drawImage(img, 0, 0, recordCanvas.width, recordCanvas.height);
    } catch (e) {
      // Ignore transient draws mid-frame-swap; next tick will succeed.
    }
  }, 66);

  const stream = recordCanvas.captureStream(15);
  const mimeType = pickRecorderMimeType();
  recordChunks = [];
  try {
    mediaRecorder = mimeType ? new MediaRecorder(stream, { mimeType: mimeType }) : new MediaRecorder(stream);
  } catch (e) {
    clearInterval(recordDrawTimer);
    showError("錄影", e);
    return;
  }
  mediaRecorder.ondataavailable = function (e) {
    if (e.data && e.data.size > 0) recordChunks.push(e.data);
  };
  mediaRecorder.onstop = function () {
    clearInterval(recordDrawTimer);
    recordDrawTimer = null;
    const blob = new Blob(recordChunks, { type: mediaRecorder.mimeType || "video/webm" });
    const url = URL.createObjectURL(blob);
    const filename = "alpha2-video-" + Date.now() + ".webm";
    addCaptureItem("video", url, filename);
    document.getElementById("cameraStatusHint").textContent = "已停止錄影 ✓";
  };
  mediaRecorder.start();
  document.getElementById("recordFab").classList.add("recording");
  hint.textContent = "錄影中…";
  setRecordingLed(true);
}

function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== "inactive") {
    mediaRecorder.stop();
  }
  document.getElementById("recordFab").classList.remove("recording");
  setRecordingLed(false);
}

/** 錄影中頭/眼 LED 長開紅燈, 停止錄影就熄返 - 只喺 alpha2 backend 生效 (led/head/set,
 *  led/eye/set 呢兩個 endpoint 冇 lynx 版本, 同 tilt LED 果個做法一致)。 */
function setRecordingLed(on) {
  if (currentBackend !== "alpha2") return;
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    api("led/head/set", { preset: "long", color: 1, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: 1, brightness: eyeBrightness });
  } else {
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
  }
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
    // Same hardware, same servo 19/20 pan/tilt calibration regardless of backend (see
    // SERVO_CALIBRATION) - but unlike camera/audio (hwApi()), the endpoint *path* isn't
    // shared: Alpha2 calls it "servo/one", Lynx calls the same operation
    // "motor/move_absolute" with the same id/angle/time params (see
    // LynxController#handle()).
    //
    // Lynx must send both servos in ONE call (motor/set_all -> AIDL
    // motor_setAllMotorAbsoluteAngle, a single binder transaction) rather than two
    // separate motor/move_absolute HTTP requests - sending id 19 and id 20 as two
    // independent fetches let the second request's in-flight move interrupt/override
    // the first's before it finished, so the pad's diagonal drags never actually
    // reached both servos together. One set_all call also halves the network
    // round-trips per drag update.
    //
    // Uses the same move-time setting as the Servo tab (servoTime()/lynxServoTime()),
    // matching how this worked before - back to the person's original setting rather
    // than a hardcoded joystick-only value.
    if (currentBackend === "lynx") {
      const time = lynxServoTime();
      const pairs = [];
      if (panAngle !== null) pairs.push("19:" + panAngle);
      if (tiltAngle !== null) pairs.push("20:" + tiltAngle);
      if (pairs.length) lynxApi("motor/set_all", { angles: pairs.join(","), time: time });
    } else {
      const time = servoTime();
      if (panAngle !== null) api("servo/one", { id: 19, angle: panAngle, time: time });
      if (tiltAngle !== null) api("servo/one", { id: 20, angle: tiltAngle, time: time });
    }
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
      hwApi("camera/info", {}).then(function (resp) {
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
  // 頭/眼 LED 綠燈長開改咗喺 server 端做 (見 MainActivity#handleMicStream/
  // releaseMicForAudioIo 嘅 javadoc) - 一定要等機身自己 speech_SetMIC(true) 嘅
  // 300ms release 流程完咗先送 LED 命令, 否則會同機身自己嘅 setWakeState 副作用
  // (自動熄耳朵 LED 嘅 LED_ACTION 廣播) 有 race, 導致「有時著,有時唔著」。
  // 如果喺呢度(前端)一開波就送, 個時序就同機身嗰個廣播返轉頭爭, 冧返轉個問題。
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
  // 呢度冇 race 問題 (冇再觸發 setWakeState), 照舊由前端主動熄燈 - server 端
  // handleMicStream() 嘅 finally 區塊都有一個保底 stop (應付連線中斷冇經呢個掣嘅情況)。
  setListenLed(false);
}

/** 聽機械人(🎧)完結時頭/眼 LED 熄返 - alpha2-only, 同 setRecordingLed()/tilt LED/
 *  flashCaptureLed() 一致嘅做法。開燈嗰部分已經搬咗去 server 端 (見上面 comment)。 */
function setListenLed(on) {
  if (currentBackend !== "alpha2") return;
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    api("led/head/set", { preset: "long", color: 2, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: 2, brightness: eyeBrightness });
  } else {
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
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
    const resp = await hwApi("audio/testtone", {});
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
    const resp = await hwApi("audio/diagnose", {});
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
  // 講嘢 (🎤 walkie-talkie 咪) 功能已經永久停用 - 唔止喺 http:// (非安全來源) 先停用,
  // 而係無條件、任何情況都無反應。掣本身喺 disableTalkFabIfInsecureContext() (依家
  // 改咗做無條件 disable, 見下面) 已經 disabled 兼移除曬 pointerdown/keydown 嘅觸發
  // 途徑, 呢度加多一層 guard 係以防萬一有第啲入口(例如 keyboard shortcut)漏咗冇
  // check 就直接 call 到呢個 function。
  return;
}

async function startTalkDisabled_unused() {
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
    await hwApi("audio/play/start", {});

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
  hwApi("audio/play/stop", {});
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

function toggleAccelerometer() {
  const on = document.getElementById("accelToggle").checked;
  const hint = document.getElementById("accelHint");
  hint.textContent = on ? "開啟中…" : "";
  // Plain Android SensorManager, not implemented by either AIDL backend (see
  // isSharedHardwarePath() in LynxController.java) - same single physical IMU
  // regardless of which robot SDK is selected, so this follows currentBackend the
  // same way hwApi() does for camera/audio.
  return hwApi("accelerometer/set", { on: String(on) }).then(function (json) {
    if (!json.ok) {
      document.getElementById("accelToggle").checked = false;
      hint.textContent = json.error || "開啟失敗";
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
      hint.textContent = "鬱動 / 傾斜機身睇下數據變化";
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
  document.getElementById("uuidOut").innerHTML = "查詢中…";
  return api("misc/request_uuid");
  // Result arrives asynchronously via the "robot_uuid" WebSocket event (see appendLog's
  // companion handler below) rather than in this HTTP response.
}

// ---------------- Lynx (3.0.0.2) backend ----------------
// Mirrors the shape of the Alpha2 functions above but targets LynxController's
// endpoints via lynxApi(). Async results (action list, motor list, ASR, ...) don't
// come back in the triggering HTTP response - they arrive as "lynx_*" WebSocket
// events (see appendLog()) the same way Alpha2's own async results do.

function lynxRefreshStatus() {
  lynxApi("status").then(function (j) {
    document.getElementById("lynxStatusOut").textContent = JSON.stringify(j, null, 2);
  });
}

function lynxRefreshSys() {
  lynxApi("sys/sid").then(function (j) {
    document.getElementById("lynxSidOut").textContent = j.ok ? j.sid : "-";
  });
  lynxApi("sys/battery_version").then(function (j) {
    document.getElementById("lynxBatteryVerOut").textContent = j.ok ? j.version : "-";
  });
  lynxApi("sys/power_value").then(function (j) {
    document.getElementById("lynxPowerOut").textContent = j.ok ? String(j.value) : "-";
  });
  lynxApi("sys/is_charging").then(function (j) {
    document.getElementById("lynxChargingOut").textContent = j.ok ? (j.charging ? "是" : "否") : "-";
  });
  lynxApi("sys/mic_version").then(function (j) {
    document.getElementById("lynxMicVerOut").textContent = j.ok ? j.version : "-";
  });
  lynxApi("sys/head_version").then(function (j) {
    document.getElementById("lynxHeadVerOut").textContent = j.ok ? j.version : "-";
  });
  lynxApi("sys/chest_version").then(function (j) {
    document.getElementById("lynxChestVerOut").textContent = j.ok ? j.version : "-";
  });
}

function lynxSetPir() {
  const on = document.getElementById("lynxPir").checked;
  lynxApi("sys/pir", { on: on });
}

// -- Action --
// Reuses Alpha2's ACTION_CATEGORIES/ACTION_CATEGORY_MAP/categoryOf() (same 5 categories,
// same hardware) - only the underlying action list/state and API prefix are separate,
// since Lynx's action set is its own data, independent from whatever Alpha2 last loaded.
let lynxAllActions = [];
let activeLynxActionCategory = "basic";

function lynxDisplayNameOf(action) {
  if (uiLang === "en") {
    return action.nameEn || action.nameCn;
  }
  return action.nameCn || action.nameEn;
}

function lynxLoadActionList() {
  const listEl = document.getElementById("lynxActionList");
  listEl.textContent = "載入中…";
  lynxApi("action/list");
  // Result arrives as a "lynx_action_list" WebSocket event (see EventBus in
  // LynxController.java) rather than in this response - handled in the WebSocket
  // handler below, which stores it into lynxAllActions and calls the two functions
  // below.
}

function buildLynxActionSubTabs() {
  const bar = document.getElementById("lynxActionSubTabBar");
  bar.innerHTML = "";
  ACTION_CATEGORIES.forEach(function (c) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (c.key === activeLynxActionCategory ? " active" : "");
    btn.style.setProperty("--sub-tab-color", c.color);
    const count = lynxAllActions.filter(function (a) { return categoryOf(a.type) === c.key; }).length;
    btn.textContent = (uiLang === "en" ? c.labelEn : c.label) + " (" + count + ")";
    btn.onclick = function () {
      activeLynxActionCategory = c.key;
      buildLynxActionSubTabs();
      lynxRenderActionList();
    };
    bar.appendChild(btn);
  });
}

function lynxRenderActionList() {
  const listEl = document.getElementById("lynxActionList");
  const filtered = lynxAllActions.filter(function (a) { return categoryOf(a.type) === activeLynxActionCategory; });

  if (filtered.length === 0) {
    listEl.textContent = "(冇動作 / 服務未初始化)";
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = lynxDisplayNameOf(a);
    // Label shows the human-readable name for the person to recognise the action,
    // but playback must use a.id (the robot's actual action identifier / .ubx
    // filename) - a.nameEn/a.nameCn are just display labels, the robot's
    // playAction() does not resolve either of them to a file (see LynxController
    // #actionList() for where id comes from).
    chip.onclick = function () {
      document.getElementById("lynxActionName").value = a.id;
      lynxPlayAction();
    };
    listEl.appendChild(chip);
  });
}

function lynxPlayAction() {
  const name = document.getElementById("lynxActionName").value.trim();
  if (!name) return;
  // Lynx's on-robot action service appears to ignore a new playAction() while one is
  // still in progress (unlike Alpha2's, which the person confirmed can just be
  // re-triggered directly) - so every play here explicitly stops whatever's currently
  // running first, and only then plays the new one, instead of relying on the robot to
  // accept an overlapping request. The stop call is awaited (not fired in parallel) so
  // the ordering is guaranteed - firing both at once wouldn't reliably stop-before-play.
  return lynxApi("action/stop").then(function () {
    return lynxApi("action/play", { name: name });
  });
}

function lynxStopAction() {
  lynxApi("action/stop");
}

// -- Motor --
function lynxServoTime() {
  return document.getElementById("lynxServoAllTime").value;
}

/** Same hardware as Alpha2 (user-confirmed identical calibration), so this mirrors
 *  buildServoGrid() above exactly - same SERVO_GROUPS/SERVO_NAMES/SERVO_CALIBRATION,
 *  just lynxApi("motor/move_absolute") instead of api("servo/one"). */
function lynxBuildServoGrid() {
  const wrap = document.getElementById("lynxServoGroups");
  wrap.innerHTML = "";
  SERVO_GROUPS.forEach(function (group) {
    const groupEl = document.createElement("div");
    groupEl.className = "servo-group";

    const title = document.createElement("div");
    title.className = "servo-group-title";
    title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + (uiLang === "en" ? group.labelEn : group.label);
    groupEl.appendChild(title);

    group.ids.forEach(function (id) {
      const cal = SERVO_CALIBRATION[id];
      const row = document.createElement("div");
      row.className = "servo-slider-row";
      row.innerHTML =
          "<span class=\"servo-slider-label\">#" + id + " " + servoNameOf(id) + "</span>" +
          "<input type=\"range\" id=\"lynxServoSlider_" + id + "\" min=\"" + cal.min + "\" max=\"" + cal.max + "\" value=\"" + cal.home + "\">" +
          "<span class=\"servo-slider-value\" id=\"lynxServoSliderVal_" + id + "\">" + cal.home + "</span>";
      const slider = row.querySelector("input");
      const valueLabel = row.querySelector(".servo-slider-value");

      slider.addEventListener("input", function () {
        valueLabel.textContent = slider.value;
      });
      slider.addEventListener("change", function () {
        const raw = parseInt(slider.value, 10);
        const clamped = clampServoAngle(id, isNaN(raw) ? cal.home : raw);
        if (clamped !== raw) {
          slider.value = clamped;
          valueLabel.textContent = clamped;
        }
        lynxApi("motor/move_absolute", { id: id, angle: clamped, time: lynxServoTime() });
      });

      groupEl.appendChild(row);
    });

    wrap.appendChild(groupEl);
  });
}

/** Resets every slider to its calibrated home position and sends all 20 at once. */
function lynxResetServoGrid() {
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    const slider = document.getElementById("lynxServoSlider_" + i);
    const label = document.getElementById("lynxServoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
  }
  lynxServoAll();
}

function lynxServoAll() {
  const pairs = [];
  for (let i = 1; i <= 20; i++) {
    const slider = document.getElementById("lynxServoSlider_" + i);
    const cal = SERVO_CALIBRATION[i];
    const raw = slider ? parseInt(slider.value, 10) : cal.home;
    const clamped = clampServoAngle(i, isNaN(raw) ? cal.home : raw);
    pairs.push(i + ":" + clamped);
  }
  // motor/set_all takes "id:angle,id:angle,..." (see LynxController#handle()),
  // unlike Alpha2's servo/all which takes a fixed-order plain angle list.
  return lynxApi("motor/set_all", { angles: pairs.join(","), time: lynxServoTime() });
}

function lynxMotorMoveRef() {
  const id = document.getElementById("lynxMotorId").value;
  const delta = document.getElementById("lynxMotorDelta").value;
  lynxApi("motor/move_ref", { id: id, delta: delta, time: lynxServoTime() });
}

function lynxMotorReadAngle() {
  const id = document.getElementById("lynxMotorId").value;
  lynxApi("motor/read", { id: id, hardware: true });
}

function lynxMotorPowerSave() {
  const on = document.getElementById("lynxPowerSave").checked;
  lynxApi("motor/power_save", { on: on });
}

// -- Speech --
function lynxSpeak() {
  const text = document.getElementById("lynxTtsText").value.trim();
  if (!text) return;
  const voice = document.getElementById("lynxVoiceName").value.trim();
  const params = { text: text };
  if (voice) params.voice = voice;
  lynxApi("speech/tts", params);
}

function lynxStopSpeak() {
  lynxApi("speech/stop");
}

function lynxSetVoice() {
  const name = document.getElementById("lynxVoiceName").value.trim();
  if (!name) return;
  lynxApi("speech/set_voice", { name: name });
}

function lynxLoadVoices() {
  lynxApi("speech/voices").then(function (j) {
    const el = document.getElementById("lynxVoiceList");
    if (!j.ok || !j.voices || !j.voices.length) {
      el.textContent = "(冇資料)";
      return;
    }
    el.innerHTML = "";
    j.voices.forEach(function (v) {
      const chip = document.createElement("span");
      chip.className = "chip";
      chip.textContent = v.name + " (" + v.language + ")";
      chip.onclick = function () {
        document.getElementById("lynxVoiceName").value = v.name;
      };
      el.appendChild(chip);
    });
  });
}

function lynxStartAsr() {
  lynxApi("speech/start_asr", { key: "webpanel", mode: 0 });
}

function lynxStopAsr() {
  lynxApi("speech/stop_asr");
}

// -- LED --
// Lynx's ILedInterface is a different AIDL surface from Alpha2's 5-mic serial-port
// LED protocol (see the big comment on tab-lynx-led in index.html). Confirmed on
// real hardware by the user:
//   頭/head: turnOnHead/Flash/Marquee/Breath - p0=顏色(1-7, LedColor), p1=光暗(1-9,
//     其他值無效)。Flash/Marquee 嘅 p1-p3 未核實, breath 確認 p0=顏色。
//   咀/mouth: turnOnMouth - p0=光暗(1-9, 其他值無效, 長開)。turnOnMouthBreath -
//     p0=閃爍時間(漸光, ms, 例如500), p1=OffTime(熄燈停頓, ms, 例如1000), p2=著燈
//     維持時間(ms, 例如10000)。
//   wifi: turnOnWifi - 只有兩種顏色: p0=2 係藍色, 其他任何值(包括0/1)都係紅色;
//     turnOffWifi 冇反應, 熄唔到。
//   chest: turnOnChestLed/turnOffChestLed 測試冇反應。
// 眼部 flash/marquee 同頭部 flash/marquee 嘅 p1-p3 仲未核實, 保留 raw 輸入畀繼續測試。
let selectedLynxEyeColor = 7; // 白
let selectedLynxHeadColor = 7; // 白

function lynxBuildEyeColorPicker() {
  buildColorPicker("lynxEyeColorPicker",
    function () { return selectedLynxEyeColor; },
    function (code) { selectedLynxEyeColor = code; },
    function () { lynxEyeOn(); }); // 撳色掣即刻開燈,唔使再撳多次「開燈」
}

function lynxBuildHeadColorPicker() {
  buildColorPicker("lynxHeadColorPicker",
    function () { return selectedLynxHeadColor; },
    function (code) { selectedLynxHeadColor = code; },
    function () { lynxHeadOn(); }); // 撳色掣即刻開燈,唔使再撳多次「開燈」
}

function lynxEyeOn() {
  lynxApi("led/eye/on", { color: selectedLynxEyeColor });
}
function lynxEyeOff() { lynxApi("led/eye/off"); }
function lynxEyeBlink() { lynxApi("led/eye/blink"); }

function lynxEyeFlash() {
  lynxApi("led/eye/flash", {
    p0: document.getElementById("lynxEyeFlashP0").value,
    p1: document.getElementById("lynxEyeFlashP1").value,
    p2: document.getElementById("lynxEyeFlashP2").value,
    p3: document.getElementById("lynxEyeFlashP3").value,
  });
}
function lynxEyeMarquee() {
  lynxApi("led/eye/marquee", {
    p0: document.getElementById("lynxEyeMarqueeP0").value,
    p1: document.getElementById("lynxEyeMarqueeP1").value,
    p2: document.getElementById("lynxEyeMarqueeP2").value,
    p3: document.getElementById("lynxEyeMarqueeP3").value,
  });
}

function lynxHeadOn() {
  lynxApi("led/head/on", {
    p0: selectedLynxHeadColor,
    p1: document.getElementById("lynxHeadBrightness").value,
  });
}
function lynxHeadOff() { lynxApi("led/head/off"); }
function lynxHeadFlash() {
  lynxApi("led/head/flash", {
    p0: selectedLynxHeadColor,
    p1: document.getElementById("lynxHeadFlashP1").value,
    p2: document.getElementById("lynxHeadFlashP2").value,
    p3: document.getElementById("lynxHeadFlashP3").value,
  });
}
function lynxHeadMarquee() {
  lynxApi("led/head/marquee", {
    p0: selectedLynxHeadColor,
    p1: document.getElementById("lynxHeadMarqueeP1").value,
    p2: document.getElementById("lynxHeadMarqueeP2").value,
    p3: document.getElementById("lynxHeadMarqueeP3").value,
  });
}
function lynxHeadBreath() {
  lynxApi("led/head/breath", {
    p0: selectedLynxHeadColor,
    p1: document.getElementById("lynxHeadBreathP1").value,
    p2: document.getElementById("lynxHeadBreathP2").value,
    p3: document.getElementById("lynxHeadBreathP3").value,
  });
}

function lynxMouthOn() {
  lynxApi("led/mouth/on", { p0: document.getElementById("lynxMouthBrightness").value });
}
function lynxMouthOff() { lynxApi("led/mouth/off"); }
function lynxMouthFlash() {
  // No dedicated "flash" AIDL method for the mouth LED - reuses turnOnMouthBreath, but
  // with OffTime forced to equal the flicker/fade time, so there's an actual visible
  // "off" pause between each pulse (OffTime=0 means no off-phase at all, i.e. a smooth
  // continuous glow - that's 呼吸燈 below, not 閃爍).
  const flicker = document.getElementById("lynxMouthFlickerTime").value;
  lynxApi("led/mouth/breath", {
    p0: flicker,
    p1: flicker,
    p2: 2147483647, // 冇獨立嘅著燈時間輸入,用 int 上限令個閃爍持續到你撳「停止」為止
  });
}
function lynxMouthBreath() {
  lynxApi("led/mouth/breath", {
    p0: document.getElementById("lynxMouthFlickerTime").value,
    p1: document.getElementById("lynxMouthOffTime").value,
    p2: 2147483647, // 冇獨立嘅著燈時間輸入,用 int 上限令個呼吸燈持續到你撳「停止」為止
  });
}

function lynxWifiSet(colorCode) {
  lynxApi("led/wifi/on", { p0: colorCode });
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

const MAX_LOG_LINES = 200;

function appendLog(msg) {
  // accel fires at high frequency (every ~150-250ms once enabled) and is purely a
  // live readout, not something worth scrolling through in the event log - skip the
  // log DOM entirely for it (still update its tile/chart below) rather than relying
  // on MAX_LOG_LINES trimming to keep up with a flood of these every second.
  if (msg.type !== "accel") {
    const log = document.getElementById("eventLog");
    const line = document.createElement("div");
    line.className = "log-line log-type-" + msg.type;
    const dataStr = typeof msg.data === "object" ? JSON.stringify(msg.data) : msg.data;
    line.innerHTML = "<span class=\"log-time\">[" + msg.time + "]</span> <b>" + msg.type + "</b> " + escapeHtml(dataStr);
    log.appendChild(line);
    // Cap the number of DOM nodes kept around for any other, lower-frequency event
    // type too, as a safety net against unbounded growth over a long session.
    while (log.childElementCount > MAX_LOG_LINES) {
      log.removeChild(log.firstChild);
    }
    if (document.getElementById("autoScroll").checked) {
      log.scrollTop = log.scrollHeight;
    }
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
  if (msg.type === "speech_ready" && msg.data) {
    // speech/set_asr_engine() 觸發嘅 rebind 完成 (或者一開機嘅初始 bind 完成)
    // 都會經呢個 event 返嚟。ready=false 期間 (rebind 進行緊) 擋住撳「開始
    // 聆聽」, 避免喺 speech service 重新綁定緊嗰陣撞 race condition。
    speechReadyForAsr = !!msg.data.ready;
    if (speechReadyForAsr) {
      const el = document.getElementById("asrOut");
      if (el && el.textContent.indexOf("重新綁定") !== -1) {
        el.textContent = "引擎已就緒，可以撳「開始聆聽」";
      }
    }
  }
  if (msg.type === "asr_engine_switched" && msg.data) {
    appendLog("🔀 ASR 引擎已切換去: " + msg.data.engine);
    currentAsrEngine = msg.data.engine;
    const hintEl = document.getElementById("asrCurrentEngineHint");
    if (hintEl) {
      hintEl.textContent = "目前引擎：" + (msg.data.engine === "iflytek" ? "中文 (iFlytek)" : "英文 (Nuance)");
    }
    const zhBtn = document.getElementById("asrSwitchZhBtn");
    const enBtn = document.getElementById("asrSwitchEnBtn");
    if (zhBtn) zhBtn.classList.toggle("active", msg.data.engine === "iflytek");
    if (enBtn) enBtn.classList.toggle("active", msg.data.engine === "nuance");
  }
  if (msg.type === "asr_intent" && msg.data) {
    const el = document.getElementById("asrIntentOut");
    if (el) {
      const rule = msg.data.rule || "-";
      const action = msg.data.action || "-";
      el.textContent = "rule=" + rule + " / action=" + action;
    }
  }
  if (msg.type === "text_understand" && msg.data) {
    const el = document.getElementById("nluOut");
    if (el) el.textContent = msg.data.ok ? msg.data.result : ("錯誤 (code=" + msg.data.errorCode + ")");
  }
  if (msg.type === "grammar_init" && msg.data) {
    const el = document.getElementById("grammarInitOut");
    if (el) el.textContent = "grammarId=" + msg.data.grammarId + " / errorCode=" + msg.data.errorCode;
  }
  if (msg.type === "grammar_result" && msg.data) {
    const el = document.getElementById("grammarResultOut");
    if (el) {
      el.textContent = msg.data.ok
        ? ("type=" + msg.data.type + " / " + msg.data.result)
        : ("錯誤 (code=" + msg.data.errorCode + ")");
    }
  }
  if (msg.type === "sonar_obstacle" && msg.data) {
    sonarThresholdCm = msg.data.thresholdCm;
    sonarHistory.push({ triggered: !!msg.data.triggered });
    if (sonarHistory.length > SONAR_HISTORY_LEN) {
      sonarHistory.shift();
    }
    drawSonarChart();
  }
  if (msg.type === "lynx_asr_begin") {
    const st = document.getElementById("lynxAsrStatus");
    if (st) st.textContent = "聆聽中…";
  }
  if (msg.type === "lynx_asr_end") {
    const st = document.getElementById("lynxAsrStatus");
    if (st) st.textContent = "已結束";
  }
  if (msg.type === "lynx_asr_result" && msg.data) {
    const el = document.getElementById("lynxAsrOut");
    if (el) el.textContent = msg.data.text;
  }
  if (msg.type === "lynx_asr_error" && msg.data) {
    const st = document.getElementById("lynxAsrStatus");
    if (st) st.textContent = "錯誤 (code=" + msg.data.code + ")";
  }
  if (msg.type === "accel" && msg.data) {
    onAccelSample(msg.data);
  }
  if (msg.type === "lynx_action_list" && msg.data) {
    lynxAllActions = msg.data.actions || [];
    buildLynxActionSubTabs();
    lynxRenderActionList();
  }
  if (msg.type === "lynx_action_progress" && msg.data) {
    const el = document.getElementById("lynxActionStatus");
    if (el) el.textContent = "播放中 (code=" + msg.data.code + ", progress=" + msg.data.progress + ")";
  }
  if (msg.type === "lynx_action_stop" && msg.data) {
    const el = document.getElementById("lynxActionStatus");
    if (el) el.textContent = "已停止 (code=" + msg.data.code + ")";
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
  lynxBuildServoGrid();
  buildHeadColorPicker();
  buildEyeColorPicker();
  lynxBuildEyeColorPicker();
  lynxBuildHeadColorPicker();
  setTtsEngine("nuance"); // 初始化引擎/聲音按鈕嘅 active 狀態、隱藏聲音嗰行 (預設 nuance)
  switchBackend(currentBackend); // apply saved/default backend choice (also refreshes status)
  applyUiLanguage();
  refreshVolume();
  disableTalkFabIfInsecureContext();
  connectWs();
});

/**
 * 講嘢 (🎤 walkie-talkie 咪) 功能已經永久停用 - 唔止喺 http:// (非安全來源) 先停用,
 * 而係一律 disable, 唔理 secure context 定唔係。掣本身 disable 咗之後瀏覽器唔會再
 * fire pointerdown/click 呢啲事件 (見 startTalk() 頂部個 no-op guard 做多一層保險)。
 */
function disableTalkFabIfInsecureContext() {
  const fab = document.getElementById("talkFab");
  if (!fab) return;
  fab.disabled = true;
  fab.classList.add("disabled");
  fab.title = "講嘢功能已停用";
}
