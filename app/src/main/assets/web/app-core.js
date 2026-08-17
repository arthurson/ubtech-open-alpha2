// OpenLynx — client logic (app-core.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 全局狀態、UI 語言字典、servo 校準表、lynxApi()/hwApi() 呢啲所有其他 app-*.js 都要用嘅核心 helper。呢個檔案要第一個 load。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// OpenLynx — client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// ---------------- App / project metadata ----------------
// APP_VERSION/APP_REPO_URL 呢兩個常數而家冇再喺 HTML 網頁 (index.html) 度用 -
// 版本號改咗擺喺機身/手機原生畫面 (MainActivity.java 個 onCreate() 起嗰個
// TextView UI, 顯示緊 http://<ip>:8888/ 嗰版), 唔喺呢個 web panel 度顯示。留低
// 呢兩個常數純粹係俾第日想加返 HTML 版顯示時有一個現成嘅 single source of
// truth, 唔使周圍搵散落嘅字串。
const APP_VERSION = "beta2";
const APP_REPO_URL = "https://github.com/arthurson/ubtech-open-lynx";

// ---------------- Action categories (shared data/DOM helpers) ----------------
// 動作分類機制 - Alpha2/Lynx 兩邊都用緊, 因為 action_classification.json 嘅 key
// 係機身 action 服務嘅 id (兩邊 ActionInfo.getId() 都係同一份), 分類表可以直接
// 共用, 唔使機身額外提供多一份 Lynx 專用嘅分類 json。呢個 SDK 淨係支援 Lynx
// backend, 但呢組 helper 本身冇 backend 之分, 擺喺 app-core.js (第一個 load
// 嘅檔案) 等 app-lynx.js 用得到。

// Categories as returned by the robot's own action list (row[1] = type). The
// robot's static action-info file uses numeric types (1/2/3/4); some runtime
// builds report the same categories as text instead. Either way each of
// basic/dance/story/yoga gets its own sub-tab with a distinct theme colour;
// anything not in this whitelist (regardless of what the raw type string
// actually says) falls back to a shared "others" sub-tab - the whitelist
// approach means no unknown category value ever needs to be spelled out
// literally here.
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

// 動作 ID -> {main, sub} 嘅子分類對照表, 由 action_classification.json 讀入 (見
// action_classified.txt 嘅整理來源)。呢個 mapping 淨係喺 asset 檔案有出現嘅動作先會有
// sub 分類 - 冇出現嘅動作仍然跟返 categoryOf() 嗰個大分類, 但喺嗰個大分類入面冇
// 子分類 tab 可以揀 (即係直接混喺主列表, 冚方向上等於落咗嗰個大分類嘅"其他")。
// 呢個表故意唔喺 code 度寫死: 下次要再分類就淨係改/換份 json, 唔使動 app-core.js。
let actionClassification = {}; // id -> {main, sub}

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

/** 建立第二層子分類 tab (例如 基本 之下嘅 移動類/手勢類/頭部類/...) 嘅共用邏輯。
 *  action_classification.json 嘅 key 係機身 action 服務嘅 id, 所以呢份分類表
 *  可以畀唔同 caller (目前即係 app-lynx.js 嘅 buildLynxActionSubSubTabs())
 *  共用, 唔使複製多一份。只有 action_classification.json 對呢個大分類有出現嘅
 *  子分類先會出 tab - 冇資料就唔顯示呢層 tab bar, 直接顯示嗰個大分類入面成個
 *  flat 清單。An always-present「全部」tab 清空子分類篩選。
 *  @param barElId       子分類 tab bar 容器嘅 id
 *  @param actions       完整動作陣列 (lynxAllActions)
 *  @param mainCategory  目前揀咗嘅大分類 key (activeLynxActionCategory)
 *  @param getActiveSub  () => 目前揀咗嘅子分類 (null = 全部)
 *  @param setActiveSub  (sub) => void, 寫返去嗰個 caller 自己嘅 activeXxxSubCategory 變數
 *  @param onChange      揀咗新子分類之後要做嘅嘢 (重畫 tab bar + 重畫動作清單)
 */
function buildActionSubSubTabsShared(barElId, actions, mainCategory, getActiveSub, setActiveSub, onChange) {
  const bar = document.getElementById(barElId);
  if (!bar) return;
  bar.innerHTML = "";

  // Sub-category labels, in the order first encountered in the classification file,
  // restricted to actions that belong to the currently active main category.
  const subsInOrder = [];
  actions.forEach(function (a) {
    if (categoryOf(a.type) !== mainCategory) return;
    const sub = subCategoryOf(a);
    if (sub && subsInOrder.indexOf(sub) === -1) subsInOrder.push(sub);
  });

  if (subsInOrder.length === 0) {
    setActiveSub(null);
    return; // 呢個大分類冇任何子分類資料 - 唔顯示呢層 tab bar
  }

  const allBtn = document.createElement("button");
  allBtn.className = "sub-tab-btn" + (getActiveSub() === null ? " active" : "");
  allBtn.textContent = uiLang === "en" ? "All" : "全部";
  allBtn.onclick = function () {
    setActiveSub(null);
    onChange();
  };
  bar.appendChild(allBtn);

  subsInOrder.forEach(function (sub, i) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (sub === getActiveSub() ? " active" : "");
    btn.style.setProperty("--sub-tab-color", SUB_CATEGORY_COLORS[i % SUB_CATEGORY_COLORS.length]);
    const count = actions.filter(function (a) {
      return categoryOf(a.type) === mainCategory && subCategoryOf(a) === sub;
    }).length;
    btn.textContent = subCategoryDisplayName(sub) + " (" + count + ")";
    btn.onclick = function () {
      setActiveSub(sub);
      onChange();
    };
    bar.appendChild(btn);
  });
}

/** 建動作 chip 清單嘅共用邏輯 - 邊個 list element、邊批經 filter 嘅動作、點樣攞
 *  顯示名、撳落去做咩、清單為空顯示咩字, 全部由 caller 決定。 */
function renderActionChips(listElId, filtered, nameFn, onPick, emptyText) {
  const listEl = document.getElementById(listElId);
  if (!listEl) return;
  if (filtered.length === 0) {
    listEl.textContent = emptyText;
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = nameFn(a);
    chip.onclick = function () { onPick(a); };
    listEl.appendChild(chip);
  });
}


// 用喺 Lynx LED tab 嘅眼/頭色點揀色器 (lynxBuildEyeColorPicker()/
// lynxBuildHeadColorPicker(), 見 app-lynx.js)。呢個 helper 本身冇 Lynx-only
// 定 Alpha2-only 之分, 純粹係一個共用 DOM utility, 所以擺喺 app-core.js (第一
// 個 load 嘅檔案) 等所有其他 app-*.js 都用得到。
const LED_COLORS = [
  { code: 1, name: "紅", hex: "#ff3b3b" },
  { code: 2, name: "綠", hex: "#3bff5c" },
  { code: 3, name: "藍", hex: "#3b6bff" },
  { code: 4, name: "黃", hex: "#ffe93b" },
  { code: 5, name: "紫", hex: "#a83bff" },
  { code: 6, name: "青", hex: "#3bfff0" },
  { code: 7, name: "白", hex: "#ffffff" },
];

function buildColorPicker(wrapId, getSelected, setSelected, onPick) {
  // 之前試過冇 null check, 一旦 wrapId 打錯或者 index.html 個對應 element 被
  // 誤刪, document.getElementById() 會返 null, wrap.innerHTML 即刻
  // TypeError —— 而呢個 function 兩個 call site
  // (lynxBuildEyeColorPicker/lynxBuildHeadColorPicker) 都喺頁面初始化
  // (DOMContentLoaded 果條 call chain) 連續執行, 其中一個掉低就會拋出未捕獲
  // 例外, 中斷埋後面幾行初始化 (連累 lynxRefreshStatus()/lynxRefreshSys()/
  // connectWs() 都行唔到), 令狀態頁睇落一片空白、讀唔到機身 data, 卻冇任何
  // 錯誤提示喺 UI 度 (window.onerror 會 log 落 console/logcat, 但畫面本身
  // 一片空白)。加返 guard: 揾唔到就靜靜哋跳過呢一個 color picker, 唔阻住
  // 其他初始化步驟。
  const wrap = document.getElementById(wrapId);
  if (!wrap) {
    console.error("buildColorPicker: element #" + wrapId + " not found, skipping");
    return;
  }
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
  nav_motor:              { zh: "⚙️ 舵機",          en: "⚙️ Servo" },
  nav_speech:             { zh: "🗣️ 語音",          en: "🗣️ Speech" },
  nav_led:                { zh: "💡 LED",           en: "💡 LED" },
  nav_camera:             { zh: "📷 相機",          en: "📷 Camera" },

  // -- status tab --
  lang_switch_label:     { zh: "語言：", en: "Language:" },
  accel_heading:         { zh: "加速度計",        en: "Accelerometer" },
  tilt_led_toggle_label: { zh: "4角度傾側著頭/眼LED", en: "4-direction tilt lights up head/eye LED" },
  accel_turning_on_hint: { zh: "開啟中…", en: "Turning on…" },
  accel_turn_on_failed_hint: { zh: "開啟失敗", en: "Failed to turn on" },
  accel_move_hint:       { zh: "鬱動 / 傾斜機身睇下數據變化", en: "Move / tilt the robot to see the readings change" },

  // -- actions tab --
  lynx_actions_heading:  { zh: "動作 (Actions)",  en: "Actions" },
  actions_load_btn:      { zh: "攞動作列表",      en: "Load Action List" },
  action_play_btn:       { zh: "播放",            en: "Play" },
  action_stop_btn:       { zh: "停止",            en: "Stop" },
  lynx_action_id_placeholder: { zh: "動作 ID（撳上面嘅 chip 自動填入並播放，或自行輸入例如 wave01）", en: "Action ID (tap a chip above to auto-fill and play, or type e.g. wave01)" },
  lynx_action_id_hint:   { zh: "播放要用動作 ID（.ubx 檔名），唔係顯示名稱 — 撳上面嘅 chip 會即刻播放 (撳新嘅動作會自動停咗個舊嘅先播)",
                            en: "Playback uses the action ID (.ubx filename), not the display name — tapping a chip above plays it immediately (tapping a new one auto-stops the previous)" },

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
  // update them - re-run the builder if the action list is already loaded.
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

// ---------------- HTTP helpers ----------------

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

/** 建立 servo grid 嘅共用邏輯 - 邊個 caller 用邊個 wrap element id / slider id
 *  prefix / 送出去 robot 嘅 call, 全部由參數決定。
 *  @param wrapId       外層容器嘅 id ("lynxServoGroups")
 *  @param sliderPrefix slider input 嘅 id prefix ("lynxServoSlider_")
 *  @param valPrefix    數值顯示 span 嘅 id prefix ("lynxServoSliderVal_")
 *  @param sendFn       (id, angle) => void, 拖完手之後實際送出去robot嘅call
 *                       (motor/move_absolute, 見 caller)
 *  @param readPrefix   (可選) 「讀取所有角度」結果顯示 span 嘅 id prefix - 冇傳嘅話
 *                       唔會加呢欄, 版面同以前一樣。
 */
function buildServoGridInto(wrapId, sliderPrefix, valPrefix, sendFn, readPrefix) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) {
    console.error("buildServoGridInto: #" + wrapId + " not found, skipping");
    return;
  }
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
      row.className = "servo-slider-row" + (readPrefix ? " has-readout" : "");
      row.innerHTML =
          "<span class=\"servo-slider-label\">#" + id + " " + servoNameOf(id) + "</span>" +
          "<input type=\"range\" id=\"" + sliderPrefix + id + "\" min=\"" + cal.min + "\" max=\"" + cal.max + "\" value=\"" + cal.home + "\">" +
          "<span class=\"servo-slider-value\" id=\"" + valPrefix + id + "\">" + cal.home + "</span>" +
          (readPrefix ? "<span class=\"servo-slider-readout\" id=\"" + readPrefix + id + "\">-</span>" : "");
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
        sendFn(id, clamped);
      });

      groupEl.appendChild(row);
    });

    wrap.appendChild(groupEl);
  });
}

// ---------------- Global error surface ----------------
// Any uncaught JS error used to fail silently (a button's onclick handler would just
// stop executing with nothing visible in the page). Both a global handler and every
// hwApi()/lynxApi() call now route failures through here so the UI always shows
// *something*.

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

// This app only supports the Lynx backend now - hwApi() always hits the same
// no-prefix "/api/..." endpoints (handleSharedHardwareApi() server-side) that
// lynxApi() doesn't cover. Kept as its own function (rather than inlining
// fetch() everywhere) so hardware endpoints and Lynx AIDL endpoints stay visually
// distinct at each call site, matching the rest of this file's naming.
function hwApi(path, params) {
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

// Every Lynx (3.0.0.2) AIDL-backed endpoint goes through here ("/api/lynx/...").
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

