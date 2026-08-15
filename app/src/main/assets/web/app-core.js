// client logic (app-core.js)
// 內容: 全局狀態、UI 語言字典、servo 校準表、lynxApi() 呢啲所有其他 app-*.js 都要用嘅核心 helper。呢個檔案要第一個 load。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// ---------------- App / project metadata ----------------
const APP_VERSION = "beta2";
const APP_REPO_URL = "https://github.com/arthurson/OpenLynx";

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
  // -- nav bar --
  nav_status:             { zh: "📊 狀態",          en: "📊 Status" },
  nav_actions:            { zh: "🕺 動作",          en: "🕺 Actions" },
  nav_servo:              { zh: "⚙️ 舵機",          en: "⚙️ Servo" },
  nav_motor:              { zh: "⚙️ 舵機",          en: "⚙️ Servo" },
  nav_speech:             { zh: "🗣️ 語音",          en: "🗣️ Speech" },
  nav_led:                { zh: "💡 LED",           en: "💡 LED" },
  nav_camera:             { zh: "📷 相機",          en: "📷 Camera" },
  nav_advanced:           { zh: "🧪 進階",          en: "🧪 Advanced" },


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
  tilt_led_toggle_label: { zh: "4角度傾側著頭/眼LED", en: "4-direction tilt lights up head/eye LED" },
  tilt_led_direction_prefix: { zh: "目前傾側方向: ", en: "Current tilt direction: " },
  tilt_led_monitoring_hint: { zh: "監測中… (要先開返上面個「加速度計」開關先會收到讀數)",
                               en: "Monitoring… (turn on the \u201cAccelerometer\u201d switch above first to receive readings)" },
  accel_turning_on_hint: { zh: "開啟中…", en: "Turning on…" },
  accel_turn_on_failed_hint: { zh: "開啟失敗", en: "Failed to turn on" },
  accel_move_hint:       { zh: "鬱動 / 傾斜機身睇下數據變化", en: "Move / tilt the robot to see the readings change" },
  uuid_querying_hint:    { zh: "查詢中…", en: "Querying…" },

  // -- actions tab --
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
  servo_heading:         { zh: "舵機 (Servos, 1–20)", en: "Servos (1–20)" },
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
  mic_card_heading:      { zh: "🎙️ 麥克風擁有權", en: "🎙️ Microphone Ownership" },
  mic_card_hint:         { zh: "控制邊個持有麥克風 - App (畀 TTS/Mic Listen 用) 定係機械人自己 (畀 wake word / ASR 用)。同一時間淨係得一方可以用。",
                            en: "Controls who holds the microphone - the app (for TTS/Mic Listen) or the robot itself (for wake word / ASR). Only one side can hold it at a time." },
  mic_release_btn:       { zh: "釋放麥克風俾 App", en: "Release Mic to App" },
  mic_return_btn:        { zh: "交返麥克風俾機器人", en: "Return Mic to Robot" },
  mic_state_on:          { zh: "App 持有中", en: "Held by app" },
  mic_state_off:         { zh: "已交返俾機械人", en: "Returned to robot" },
  mic_keep_held_label:   { zh: "持續搶 Mic (唔俾機械人自動攞返)", en: "Keep holding mic (auto re-take from robot)" },
  mic_keep_held_hint:    { zh: "開咗之後, 就算機械人韌體內部側面攞返 mic (例如 wake-word 引擎自己觸發), app 都會每幾秒自動搶返 - 直到你自己撳「交返麥克風俾機器人」或者閂返呢個掣為止。",
                            en: "When on, even if the robot's firmware internally re-takes the mic on its own (e.g. the wake-word engine triggering it), the app will automatically re-take it every few seconds - until you either return it manually or turn this off." },
  self_interrupt_label:  { zh: "自我打斷",         en: "Self-Interrupt" },
  volume_heading:        { zh: "媒體音量",         en: "Media Volume" },
  volume_hint:           { zh: "控制機械人喇叭嘅媒體音量 (STREAM_MUSIC)，同實體 +/- 按鈕共用同一個音量。",
                            en: "Controls the robot speaker's media volume (STREAM_MUSIC) — shares the same level as the physical +/- buttons." },

  // -- camera tab --
  camera_heading:        { zh: "相機",             en: "Camera" },
  camera_feature_key:    { zh: "功能鍵",           en: "Feature Key" },

  // -- Lynx status --
  lynx_status_heading:   { zh: "系統狀態 (Lynx 3.0.0.2)", en: "Status (Lynx 3.0.0.2)" },
  lynx_device_info_heading: { zh: "裝置資訊", en: "Device Info" },
  lynx_device_sid:       { zh: "🆔 SID",          en: "🆔 SID" },
  lynx_device_battery_ver: { zh: "🔋 電池版本",    en: "🔋 Battery Version" },
  lynx_device_power:     { zh: "⚡ 電量",          en: "⚡ Power" },
  lynx_device_charging:  { zh: "🔌 充電中",        en: "🔌 Charging" },
  lynx_device_mic_ver:   { zh: "🎙️ MIC 版本",     en: "🎙️ MIC Version" },
  lynx_device_head_ver:  { zh: "🧠 頭部版本",      en: "🧠 Head Version" },
  lynx_device_chest_ver: { zh: "🫁 胸部版本",      en: "🫁 Chest Version" },
  lynx_charging_yes:     { zh: "是", en: "Yes" },
  lynx_charging_no:      { zh: "否", en: "No" },

  // -- Lynx PIR --
  lynx_pir_heading:      { zh: "PIR 感應器", en: "PIR Sensor" },
  lynx_pir_switch_label: { zh: "感應器開關", en: "Sensor Switch" },
  lynx_pir_alert_label:  { zh: "警示反應 (LED+鈴聲)", en: "Alert Reaction (LED + Chime)" },

  // -- Lynx servo tab --
  lynx_servo_heading:    { zh: "舵機 (Servo, 1–20)", en: "Servos (1–20)" },
  lynx_servo_hint:       { zh: "拖動滑桿放手即送出。讀取結果 (code) 顯示喺滑桿右邊, 淨係撳「讀取所有角度」先會更新。",
                            en: "Drag a slider and release to send. The readout appears next to each slider, and only updates when you press \u201cRead All Angles\u201d." },
  lynx_servo_time_label: { zh: "時間(ms)：", en: "Time (ms):" },
  lynx_servo_reset_btn:  { zh: "全部回到中位", en: "Reset All to Center" },
  lynx_servo_read_all_btn: { zh: "讀取所有角度", en: "Read All Angles" },
  lynx_servo_power_save: { zh: "省電", en: "Power Save" },

  // -- Lynx speech tab --
  lynx_tts_heading:      { zh: "TTS 播放 (Android 內置)", en: "TTS Playback (Built-in Android)" },
  lynx_tts_text_placeholder: { zh: "要講嘅文字", en: "Text to speak" },
  lynx_tts_speak_btn:    { zh: "播放", en: "Speak" },
  lynx_tts_stop_btn:     { zh: "停止", en: "Stop" },
  lynx_tts_engine_label: { zh: "TTS 引擎：", en: "TTS Engine:" },
  lynx_tts_engine_placeholder: { zh: "(撳右邊掣載入)", en: "(press the button on the right to load)" },
  lynx_tts_engine_load_btn: { zh: "攞引擎列表", en: "Load Engine List" },
  lynx_tts_lang_label:   { zh: "語言：", en: "Language:" },
  lynx_tts_lang_placeholder: { zh: "(先揀引擎)", en: "(choose an engine first)" },
  lynx_tts_lang_load_btn: { zh: "攞語言列表", en: "Load Language List" },
  lynx_tts_engine_loading: { zh: "載入緊…", en: "Loading…" },
  lynx_tts_engine_load_empty: { zh: "(讀唔到引擎列表 - 機身冇裝任何 TTS engine？)",
                                 en: "(Couldn\u2019t read the engine list - no TTS engine installed on the robot?)" },
  lynx_tts_switching_engine: { zh: "切緊 engine…", en: "Switching engine…" },
  lynx_tts_lang_load_empty: { zh: "(讀唔到語言列表 - engine 未 ready？)",
                               en: "(Couldn\u2019t read the language list - engine not ready?)" },
  lynx_mic_grab_heading: { zh: "Mic 擁有權測試", en: "Mic Ownership Test" },
  lynx_mic_grab_hint:    { zh: "startRecording()/stopRecording() —— 唔係 ASR, 純粹測試機身自己套語音子系統(sl)會唔會因為呢兩個 call 而釋放/重新攞返部機嘅 system mic。撳掣之後留意 logcat 有冇 mic 相關 log, 或者試下播放 TTS 或者用呢個 App 自己嘅 mic 功能睇下有冇分別。",
                            en: "startRecording()/stopRecording() — not ASR, purely tests whether the robot's own speech subsystem (sl) releases/re-takes the device's system mic because of these two calls. After pressing, check logcat for mic-related logs, or try playing TTS or using this app's own mic feature to see if there's a difference." },
  lynx_mic_grab_btn:     { zh: "🎙️ 搶 Mic", en: "🎙️ Grab Mic" },
  lynx_mic_release_btn:  { zh: "🔓 放 Mic", en: "🔓 Release Mic" },
  lynx_mic_grab_status_label: { zh: "狀態：", en: "Status:" },
  lynx_mic_grabbing:     { zh: "搶緊…", en: "Grabbing…" },
  lynx_mic_grabbed_ok:   { zh: "已搶 (startRecording ok)", en: "Grabbed (startRecording ok)" },
  lynx_mic_failed_prefix: { zh: "失敗: ", en: "Failed: " },
  lynx_mic_releasing:    { zh: "放緊…", en: "Releasing…" },
  lynx_mic_released_ok:  { zh: "已放 (stopRecording ok)", en: "Released (stopRecording ok)" },

  // -- Lynx LED tab --
  lynx_led_heading:      { zh: "LED", en: "LED" },
  lynx_led_head_heading: { zh: "頭部 LED", en: "Head LED" },
  lynx_led_eye_heading:  { zh: "眼睛 LED", en: "Eye LED" },
  lynx_led_mouth_heading:{ zh: "咀部 LED", en: "Mouth LED" },
  lynx_led_wifi_heading: { zh: "WiFi 燈", en: "WiFi Light" },
  lynx_led_color_label:  { zh: "顏色：", en: "Color:" },
  lynx_led_brightness_label: { zh: "光度 (1–9)：", en: "Brightness (1–9):" },
  lynx_led_speed_label:  { zh: "速度 (0–1000)：", en: "Speed (0–1000):" },
  lynx_led_mouth_brightness_label: { zh: "光暗 (1-9)：", en: "Brightness (1-9):" },
  lynx_led_mouth_speed_label: { zh: "速度 (0-1000ms)：", en: "Speed (0-1000ms):" },
  lynx_led_mouth_off_time_label: { zh: "OffTime (1-1000)：", en: "Off Time (1-1000):" },
  lynx_led_preset_long:  { zh: "💡 長開", en: "💡 On" },
  lynx_led_preset_flash: { zh: "⚡ 閃燈", en: "⚡ Flash" },
  lynx_led_preset_breathe: { zh: "🫧 呼吸燈", en: "🫧 Breathe" },
  lynx_led_preset_breathe_mouth: { zh: "🫁 呼吸燈", en: "🫁 Breathe" },
  lynx_led_preset_marquee: { zh: "🏃 跑馬燈", en: "🏃 Chase" },
  lynx_led_preset_blink: { zh: "👁 眨眼", en: "👁 Blink" },
  lynx_led_preset_stop:  { zh: "⏹ 停止", en: "⏹ Stop" },
  lynx_led_mouth_on_btn: { zh: "長開", en: "On" },
  lynx_led_mouth_off_btn:{ zh: "停止", en: "Stop" },
  lynx_led_wifi_red_btn: { zh: "紅色", en: "Red" },
  lynx_led_wifi_blue_btn:{ zh: "藍色", en: "Blue" },

  // -- event log --
  event_log_heading:     { zh: "即時事件 Log (WebSocket)", en: "Live Event Log (WebSocket)" },
  clear_log_btn:         { zh: "清空 Log",         en: "Clear Log" },
  auto_scroll_label:     { zh: "自動捲動",         en: "Auto-scroll" },
};

/** Looks up a translated string for the current uiLang, falling back to zh if
 *  the key or language is missing. Used for dynamic (JS-set) text that isn't
 *  a static [data-i18n] element - e.g. status messages written via
 *  el.textContent = "..." from event handlers. See applyUiLanguage() below for
 *  the static-element equivalent. */
function t(key) {
  const entry = I18N[key];
  if (!entry) return key;
  return entry[uiLang] || entry.zh;
}

/** Applies uiLang to every [data-i18n]-tagged element currently in the DOM.
 *  Elements tagged data-i18n-attr="placeholder" (etc) get the translation written to
 *  that attribute instead of textContent - needed for <input placeholder="...">
 *  where the text isn't a child text node. data-i18n-attr="html" is a special case:
 *  writes to innerHTML instead of an attribute, for the handful of long hint blocks
 *  that contain inline <b>/<code> markup which must survive translation (the I18N
 *  entry's zh/en values contain literal HTML in that case, not plain text). */
function applyUiLanguage() {
  document.querySelectorAll("[data-i18n]").forEach(function (el) {
    const entry = I18N[el.dataset.i18n];
    if (!entry) return;
    const text = entry[uiLang] || entry.zh;
    const attr = el.dataset.i18nAttr;
    if (attr === "html") {
      el.innerHTML = text;
    } else if (attr) {
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
  // relabel in place rather than calling lynxBuildServoGrid() again, since a full
  // rebuild would reset every slider back to its calibrated home position and lose
  // whatever angle the person currently has dialled in.
  relabelServoGrid();
}

/** Re-applies servo group headings and #id-name slider labels for the current
 *  uiLang on the Lynx servo grid, without touching slider positions/values
 *  (see setUiLanguage() above for why not to rebuild). */
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
// lynxApi() call now route failures through here so the UI always shows *something*.

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

