// Open Alpha2 — client logic (app-lynx.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: Lynx (3.0.0.2) backend 全部功能 - 狀態/動作/servo/馬達/語音/LED。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

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
    document.getElementById("lynxChargingOut").textContent = j.ok ? (j.charging ? t("lynx_charging_yes") : t("lynx_charging_no")) : "-";
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

function lynxSetPirAlertEnabled() {
  const on = document.getElementById("lynxPirAlertEnabled").checked;
  lynxApi("sys/pir_alert_enabled", { on: on });
}

// -- Action --
// Reuses Alpha2's ACTION_CATEGORIES/ACTION_CATEGORY_MAP/categoryOf() (same 5 categories,
// same hardware) - only the underlying action list/state and API prefix are separate,
// since Lynx's action set is its own data, independent from whatever Alpha2 last loaded.
// 跟返 Alpha2 個 tab-actions 一樣, 除咗 5 大分類 (ACTION_CATEGORIES) 之外, 入面再有
// 細分類 (子分類 tab, 例如 基本 之下嘅 移動類/手勢類/...) - 用返 app-actions.js 嘅
// action_classification.json + subCategoryOf()/subCategoryDisplayName()/
// buildActionSubSubTabsShared() 呢組共用邏輯 (見嗰邊 buildActionSubSubTabsShared()
// 嘅 javadoc: 呢份分類表係跟機身 action id 嚟, Alpha2/Lynx 兩邊 ActionInfo.getId()
// 都係同一份, 所以可以直接共用, 唔使機身額外提供多一份 Lynx 專用嘅分類 json)。
let lynxAllActions = [];
let activeLynxActionCategory = "basic";
let activeLynxActionSubCategory = null; // null = 顯示嗰個大分類入面全部動作 (未揀子分類)

function lynxDisplayNameOf(action) {
  if (uiLang === "en") {
    return action.nameEn || action.nameCn;
  }
  return action.nameCn || action.nameEn;
}

function lynxLoadActionList() {
  const listEl = document.getElementById("lynxActionList");
  listEl.textContent = "載入中…";
  // action_classification.json 已經俾 Alpha2 個 tab 用緊, loadActionClassification()
  // 冪等 (每次淨係覆寫返 actionClassification 呢個 module-level 變數), 呢度再攞多次
  // 確保就算用戶今次一開機淨係揀咗 Lynx tab (未曾入過 Alpha2 個 tab 觸發過讀取),
  // 分類表都會有資料。
  Promise.all([lynxApi("action/list"), loadActionClassification()]);
  // action/list 嘅結果唔喺呢個 HTTP response 度返 (見下面 "Result 到達" 段), 淨係
  // 用嚟確保 loadActionClassification() 呢邊完成先, 避免第一次載入時子分類 tab 因為
  // action_classification.json 仲未讀完而唔顯示。
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
      activeLynxActionSubCategory = null; // 轉咗大分類, 子分類篩選重置返做「全部」
      buildLynxActionSubTabs();
      buildLynxActionSubSubTabs();
      lynxRenderActionList();
    };
    bar.appendChild(btn);
  });
  buildLynxActionSubSubTabs();
}

/** 第二層子分類 tab (移動類/手勢類/...) - 跟 app-actions.js 嘅
 *  buildActionSubSubTabs() 做法一致, 淨係轉用 lynxActionSubSubTabBar/
 *  lynxAllActions/activeLynxActionCategory/activeLynxActionSubCategory, 共用邏輯
 *  抽在 buildActionSubSubTabsShared() (見該處 javadoc)。 */
function buildLynxActionSubSubTabs() {
  buildActionSubSubTabsShared(
    "lynxActionSubSubTabBar",
    lynxAllActions,
    activeLynxActionCategory,
    function () { return activeLynxActionSubCategory; },
    function (sub) { activeLynxActionSubCategory = sub; },
    function () { buildLynxActionSubSubTabs(); lynxRenderActionList(); }
  );
}

function lynxRenderActionList() {
  const filtered = lynxAllActions.filter(function (a) {
    if (categoryOf(a.type) !== activeLynxActionCategory) return false;
    if (activeLynxActionSubCategory !== null && subCategoryOf(a) !== activeLynxActionSubCategory) return false;
    return true;
  });
  // Label shows the human-readable name for the person to recognise the action, but
  // playback must use a.id (the robot's actual action identifier / .ubx filename) -
  // a.nameEn/a.nameCn are just display labels, the robot's playAction() does not
  // resolve either of them to a file (see LynxController#actionList() for where id
  // comes from).
  renderActionChips("lynxActionList", filtered, lynxDisplayNameOf, function (a) {
    document.getElementById("lynxActionName").value = a.id;
    lynxPlayAction();
  }, "(冇動作 / 服務未初始化)");
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
 *  just lynxApi("motor/move_absolute") instead of api("servo/one"). Passes the extra
 *  readPrefix arg (Alpha2's buildServoGrid() doesn't) so each row gets its own
 *  hardware-readout span, filled in by lynxReadAllServoAngles() below. */
function lynxBuildServoGrid() {
  buildServoGridInto("lynxServoGroups", "lynxServoSlider_", "lynxServoSliderVal_", function (id, angle) {
    lynxApi("motor/move_absolute", { id: id, angle: angle, time: lynxServoTime() });
  }, "lynxServoRead_");
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

/** Fires one motor/read per servo (1-20) - there is no batch-read AIDL method on this
 *  firmware (see LynxRobotApi#motor_readAbsoluteAngle(), single motorId only), so "read
 *  all" here just means looping the single-motor call the same way lynxServoAll() loops
 *  the single-motor write. Each result arrives later as its own "lynx_motor_angle"
 *  WebSocket event (id included), and app-log.js writes it straight into that servo's
 *  own "lynxServoRead_<id>" span next to its slider - not into a single shared field,
 *  since 20 results can come back interleaved/out of order.
 *
 * hardware=false (NOT the single-motor endpoint's own default of true) - user-confirmed
 * on real hardware: reading all 20 with hardware=true made every servo go limp/lose
 * holding torque the instant the button was pressed. The exact mechanism inside the
 * robot's motor service isn't decompiled/confirmed (readAbsoluteAngle()'s fromHardware
 * param has no documented semantics anywhere in this codebase), but the observed
 * correlation is clear enough to avoid it by default for a 20-motor sweep specifically -
 * hardware=false reads back the service's last-known/cached position instead, which
 * doesn't touch the servos physically. If you need genuine hardware=true confirmation
 * for a *single* servo, motor/read itself still defaults to hardware=true unchanged.
 *
 * Staggered with LYNX_READ_ALL_DELAY_MS between each request, NOT fired all 20 at once
 * - user-confirmed on real hardware: firing all 20 back-to-back with zero delay made the
 * robot's own AIDL callback (onReadMotorAngle()) return garbage motorId/code values
 * (ids like 274-293 instead of the requested 1-20, nonsense code values, occasional
 * angle=-1003/code=-1) - looks like the firmware's own read-callback tracking can't keep
 * up with concurrent reads. (LynxController#handle()'s motor/read case now publishes the
 * REQUESTED id rather than trusting that echoed motorId regardless, since it's
 * demonstrably unreliable under load - but avoiding the load in the first place, via this
 * stagger, is the more direct fix for the garbled code/angle values that fix alone can't
 * correct, since those aren't an id-mislabelling problem.) */
const LYNX_READ_ALL_DELAY_MS = 120;

function lynxReadAllServoAngles() {
  for (let i = 1; i <= 20; i++) {
    const out = document.getElementById("lynxServoRead_" + i);
    if (out) out.textContent = "…";
  }
  for (let i = 1; i <= 20; i++) {
    setTimeout(function (id) {
      return function () { lynxApi("motor/read", { id: id, hardware: false }); };
    }(i), (i - 1) * LYNX_READ_ALL_DELAY_MS);
  }
}

function lynxMotorPowerSave() {
  const on = document.getElementById("lynxPowerSave").checked;
  lynxApi("motor/power_save", { on: on });
}

// -- Speech --
// Android 內置 TTS 專用 - 冇 ASR。流程: 先揀 engine (呢部機可能裝咗多過一個 TTS
// app), 揀好之後先攞返嗰個 engine 有嘅語言列表 (見 speech/tts_engines /
// speech/tts_languages, LynxController.AndroidTtsHandler#listEngines()/
// listLanguages()) - 兩個都反映機身實際裝咗嘅嘢, 唔係寫死嘅清單。落單自帶
// 「載入即生效」- 攞到列表就自動揀第一項並真係轉去嗰個 engine/語言, 唔會有
// 「唔轉/預設」呢個選項令人以為揀咗但其實乜都冇做。
//
// 語言嘅顯示名 (j.languages[i].name) 由後端用 Locale.getDisplayName() 生成
// (見 MainActivity#checkTtsDataSync()), 呢度唔再自己維護一個 tag->中文名對應表 -
// user-confirmed 手打嘅表 Google TTS 60+ 種得覆蓋到 40 種左右, 冇對應到嘅就漏晒
// 出返個原始 code (ne/si/sk 之類)。改由後端用 Java 內建嘅 Locale 資料生成, 冇
// 表要維護, 亦唔會有語言漏低。
let lynxSelectedTtsEngine = "";
let lynxSelectedTtsLang = "";

function lynxLoadTtsEngines() {
  const sel = document.getElementById("lynxTtsEngine");
  if (!sel) return;
  sel.innerHTML = "<option value=\"\">" + t("lynx_tts_engine_loading") + "</option>";
  lynxApi("speech/tts_engines").then(function (j) {
    sel.innerHTML = "";
    if (!j.ok || !j.engines || !j.engines.length) {
      const emptyOpt = document.createElement("option");
      emptyOpt.value = "";
      emptyOpt.textContent = t("lynx_tts_engine_load_empty");
      emptyOpt.disabled = true;
      sel.appendChild(emptyOpt);
      return;
    }
    j.engines.forEach(function (pkg) {
      const opt = document.createElement("option");
      opt.value = pkg;
      opt.textContent = pkg;
      sel.appendChild(opt);
    });
    // 冇「唔轉」選項, 所以列表一到就要揀返之前揀開嗰個 (如果仲存在), 否則揀第一個
    // 並且真係送出轉 engine request - 唔會齋顯示個 dropdown 但後端仲用緊舊 engine。
    if (lynxSelectedTtsEngine && j.engines.indexOf(lynxSelectedTtsEngine) !== -1) {
      sel.value = lynxSelectedTtsEngine;
    } else {
      sel.value = j.engines[0];
    }
    lynxSetTtsEngine();
  });
}

function lynxSetTtsEngine() {
  const pkg = document.getElementById("lynxTtsEngine").value;
  if (!pkg) return;
  lynxSelectedTtsEngine = pkg;
  // 轉咗 engine, 舊嘅語言選擇喺新 engine 之下未必仲存在 - 清埋, 逼佢用
  // lynxLoadTtsLanguages() 喺新 engine 度重新揀第一個可用語言, 唔會靜靜雞用返
  // 舊 engine 嗰個語言 tag 撞去新 engine 度。
  lynxSelectedTtsLang = "";
  const langSel = document.getElementById("lynxTtsLang");
  if (langSel) langSel.innerHTML = "<option value=\"\">" + t("lynx_tts_switching_engine") + "</option>";
  // 轉 engine 本身係 async (LynxController.AndroidTtsHandler#setEngine() 要重新起
  // TextToSpeech instance, 等緊 onInit) - 送出轉 engine request 之後, 稍等一陣先
  // 攞語言列表, 唔係一送就即刻攞 (嗰陣好可能仲未切換完成, 攞返嚟嘅可能仲係舊
  // engine 嘅語言)。
  return lynxApi("speech/set_tts_engine", { engine: pkg }).then(function () {
    return new Promise(function (resolve) { setTimeout(resolve, 800); });
  }).then(lynxLoadTtsLanguages);
}

function lynxLoadTtsLanguages() {
  const sel = document.getElementById("lynxTtsLang");
  if (!sel) return;
  sel.innerHTML = "<option value=\"\">" + t("lynx_tts_engine_loading") + "</option>";
  lynxApi("speech/tts_languages", { ui_lang: uiLang }).then(function (j) {
    sel.innerHTML = "";
    if (!j.ok || !j.languages || !j.languages.length) {
      const emptyOpt = document.createElement("option");
      emptyOpt.value = "";
      emptyOpt.textContent = t("lynx_tts_lang_load_empty");
      emptyOpt.disabled = true;
      sel.appendChild(emptyOpt);
      return;
    }
    // j.languages[i] = {tag, name} - name 已經係 Locale.getDisplayName() 生成好嘅
    // 中文名 (例如 "中文 (中國)"), 直接顯示, 唔使再自己砌。
    j.languages.forEach(function (lang) {
      const opt = document.createElement("option");
      opt.value = lang.tag;
      opt.textContent = lang.name;
      sel.appendChild(opt);
    });
    const tags = j.languages.map(function (lang) { return lang.tag; });
    if (lynxSelectedTtsLang && tags.indexOf(lynxSelectedTtsLang) !== -1) {
      sel.value = lynxSelectedTtsLang;
    } else {
      sel.value = tags[0];
    }
    lynxSetTtsLang();
  });
}

function lynxSetTtsLang() {
  const tag = document.getElementById("lynxTtsLang").value;
  if (!tag) return;
  lynxSelectedTtsLang = tag;
}

function lynxSpeak() {
  const text = document.getElementById("lynxTtsText").value.trim();
  if (!text) return;
  const params = { text: text };
  if (lynxSelectedTtsLang) params.lang = lynxSelectedTtsLang;
  // 失敗 (engine 未 ready / 語言未裝) 由 lynxApi() 本身嘅 showError() 顯示,
  // 唔使呢度再彈多次 alert。
  return lynxApi("speech/tts", params);
}

function lynxStopSpeak() {
  lynxApi("speech/stop");
}

// 2026-08 新增: 「搶 mic」/「放 mic」測試掣, 對應 ISpeechInterface 嘅
// startRecording()/stopRecording() —— 呢兩個 method 反編譯確認同
// startSpeechAsr()/onPlayCallback() 唔同層, 唔係空 stub, 真身會掛住機身
// 自己嗰個語音 mic singleton (sl)。詳見 index.html 呢個 section 頭嘅
// comment 同 docs/AIDL_GUIDE_LYNX.md「4. Speech」章節。純粹測試 mic 擁有權
// 開關, 唔係 ASR, 唔會有辨識結果。
function lynxGrabMic() {
  const statusEl = document.getElementById("lynxMicGrabStatus");
  if (statusEl) statusEl.textContent = t("lynx_mic_grabbing");
  lynxApi("speech/start_recording").then(function (j) {
    if (!statusEl) return;
    statusEl.textContent = j.ok ? t("lynx_mic_grabbed_ok") : t("lynx_mic_failed_prefix") + j.code;
  });
}

function lynxReleaseMic() {
  const statusEl = document.getElementById("lynxMicGrabStatus");
  if (statusEl) statusEl.textContent = t("lynx_mic_releasing");
  lynxApi("speech/stop_recording").then(function (j) {
    if (!statusEl) return;
    statusEl.textContent = j.ok ? t("lynx_mic_released_ok") : t("lynx_mic_failed_prefix") + j.code;
  });
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
// 眼部/頭部各自有一組共享嘅 顏色/光度/速度 控制 (見 index.html)，撳邊個 preset
// 掣就用嗰組當時嘅值去組 p0-p3：p0=顏色(1-7), p1=光度(1-9), p2=速度(對應
// 閃燈/跑馬燈嘅閃爍週期，已實測), p3=65535 (令效果持續到撳「停止」為止，已
// 實測confirm)。呼吸燈唔使呢組「速度」slider (p1/p3 已知固定填 0，p2=閃爍
// 用返個 500ms 嘅已測數值，唔開放用戶自己調)。
//
// 一按即有反應: 撳邊粒 preset 掣就即刻送出、同時記住做 lastXxxPreset；之後
// 拖色點/光度/速度 slider (oninput) 都會即刻用返呢個記住咗嘅 preset 重送一次，
// 跟返 Alpha2 個 headLedApply()/eyeLedApply() 做法一致，唔使拖完先撳多次掣。
let selectedLynxEyeColor = 7; // 白
let selectedLynxHeadColor = 7; // 白
const LYNX_LED_HOLD = 65535; // p3: 未知確實意思，但已實測填呢個值會令效果持續到手動停止

let lastLynxHeadPreset = "on";  // "on" | "flash" | "breath" | "marquee"
let lastLynxEyePreset = "on";   // "on" | "flash" | "marquee" ("blink" 唔記, 冇色/光度/速度可調)

function lynxBuildEyeColorPicker() {
  buildColorPicker("lynxEyeColorPicker",
    function () { return selectedLynxEyeColor; },
    function (code) { selectedLynxEyeColor = code; },
    lynxEyeApply);
}

function lynxBuildHeadColorPicker() {
  buildColorPicker("lynxHeadColorPicker",
    function () { return selectedLynxHeadColor; },
    function (code) { selectedLynxHeadColor = code; },
    lynxHeadApply);
}

// 重送上次揀過嘅 preset - 畀色點/光度/速度 slider 嘅 oninput 用
function lynxHeadApply() {
  switch (lastLynxHeadPreset) {
    case "flash": return lynxHeadFlash();
    case "breath": return lynxHeadBreath();
    case "marquee": return lynxHeadMarquee();
    default: return lynxHeadOn();
  }
}
function lynxEyeApply() {
  switch (lastLynxEyePreset) {
    case "flash": return lynxEyeFlash();
    case "marquee": return lynxEyeMarquee();
    default: return lynxEyeOn();
  }
}

function lynxEyeOn() {
  lastLynxEyePreset = "on";
  lynxApi("led/eye/on", { color: selectedLynxEyeColor });
}
function lynxEyeOff() { lynxApi("led/eye/off"); }
function lynxEyeBlink() { lynxApi("led/eye/blink"); }

function lynxEyeFlash() {
  lastLynxEyePreset = "flash";
  const brightness = document.getElementById("lynxEyeBrightness").value;
  const speed = document.getElementById("lynxEyeSpeed").value;
  lynxApi("led/eye/flash", { p0: selectedLynxEyeColor, p1: brightness, p2: speed, p3: LYNX_LED_HOLD });
}
function lynxEyeMarquee() {
  lastLynxEyePreset = "marquee";
  const brightness = document.getElementById("lynxEyeBrightness").value;
  const speed = document.getElementById("lynxEyeSpeed").value;
  lynxApi("led/eye/marquee", { p0: selectedLynxEyeColor, p1: brightness, p2: speed, p3: LYNX_LED_HOLD });
}

function lynxHeadOn() {
  lastLynxHeadPreset = "on";
  lynxApi("led/head/on", {
    p0: selectedLynxHeadColor,
    p1: document.getElementById("lynxHeadBrightness").value,
  });
}
function lynxHeadOff() { lynxApi("led/head/off"); }
function lynxHeadFlash() {
  lastLynxHeadPreset = "flash";
  const brightness = document.getElementById("lynxHeadBrightness").value;
  const speed = document.getElementById("lynxHeadSpeed").value;
  lynxApi("led/head/flash", { p0: selectedLynxHeadColor, p1: brightness, p2: speed, p3: LYNX_LED_HOLD });
}
function lynxHeadMarquee() {
  lastLynxHeadPreset = "marquee";
  const brightness = document.getElementById("lynxHeadBrightness").value;
  const speed = document.getElementById("lynxHeadSpeed").value;
  lynxApi("led/head/marquee", { p0: selectedLynxHeadColor, p1: brightness, p2: speed, p3: LYNX_LED_HOLD });
}
function lynxHeadBreath() {
  lastLynxHeadPreset = "breath";
  // p1/p3 已知固定填 0，p2=閃爍時間用返實測 confirm 嘅 500ms，唔開放用戶自己調
  lynxApi("led/head/breath", { p0: selectedLynxHeadColor, p1: 0, p2: 500, p3: 0 });
}

// 咀部: 同頭/眼一樣, 記住上次揀嘅 preset ("on"/"breath"), 光度/閃爍時間/OffTime
// 呢幾條 slider 一拖就即刻用返嗰個 preset 重送, 唔使再撳多次效果掣。
let lastLynxMouthPreset = "on";

function lynxMouthApply() {
  switch (lastLynxMouthPreset) {
    case "breath": return lynxMouthBreath();
    default: return lynxMouthOn();
  }
}

function lynxMouthOn() {
  lastLynxMouthPreset = "on";
  lynxApi("led/mouth/on", { p0: document.getElementById("lynxMouthBrightness").value });
}
function lynxMouthOff() { lynxApi("led/mouth/off"); }
function lynxMouthBreath() {
  lastLynxMouthPreset = "breath";
  lynxApi("led/mouth/breath", {
    p0: document.getElementById("lynxMouthFlickerTime").value,
    p1: document.getElementById("lynxMouthOffTime").value,
    p2: 2147483647, // 冇獨立嘅著燈時間輸入,用 int 上限令個呼吸燈持續到你撳「停止」為止
  });
}

function lynxWifiSet(colorCode) {
  lynxApi("led/wifi/on", { p0: colorCode });
}

