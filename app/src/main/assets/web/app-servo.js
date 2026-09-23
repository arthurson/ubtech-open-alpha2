// Open Alpha2 — client logic (app-servo.js)
// 內容: 媒體音量、servo grid (共用 buildServoGridInto())、聲納。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Media volume (STREAM_MUSIC) ----------------
//
// Backed by the robot's standard Android STREAM_MUSIC - the same stream the physical
// +/- gesture buttons and TTS/walkie-talkie playback all use, so this slider always
// reflects the robot's real current volume, not just whatever this browser tab last
// set. refreshVolume() is called on page load; the slider's max attribute is set from
// the device's actual getStreamMaxVolume() rather than assumed, since that can vary.

let servoVolLastPushAt = 0;
function onVolumeSliderInput(value) {
  // Live-update the numeric readout while dragging; 同音樂頁一樣，拖曳嗰陣
  // 150ms 節流即刻推（唔等放手），放手嗰下 onchange 再送一次準數。
  document.getElementById("volumeVal").textContent = value;
  markVolLocalInput(); // 反向同步 guard：拖緊呢 400ms 唔好畀 WS echo 郁返轉頭
  const now = Date.now();
  if (now - servoVolLastPushAt < 150) return;
  servoVolLastPushAt = now;
  Alpha2Api.audioVolumeSet( { level: String(value) }).then(function (json) {
    if (json.ok) {
      document.getElementById("volumeSlider").value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) shSlider.value = json.volume;
      if (shVal) shVal.textContent = json.volume;
    }
  });
}

function setVolume(value) {
  markVolLocalInput(); // 反向同步 guard（見上面 onVolumeChangedEvent）
  return Alpha2Api.audioVolumeSet( { level: String(value) }).then(function (json) {
    if (json.ok) {
      document.getElementById("volumeSlider").value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      // 同步共享播放器的音量滑桿
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) { shSlider.max = document.getElementById("volumeSlider").max; shSlider.value = json.volume; }
      if (shVal) shVal.textContent = json.volume;
    }
    return json;
  });
}

function refreshVolume() {
  return Alpha2Api.audioVolumeGet().then(function (json) {
    if (json.ok) {
      const slider = document.getElementById("volumeSlider");
      slider.max = json.max;
      slider.value = json.volume;
      document.getElementById("volumeVal").textContent = json.volume;
      const shSlider = document.getElementById("sharedVolumeSlider");
      const shVal = document.getElementById("sharedVolumeVal");
      if (shSlider) { shSlider.max = json.max; shSlider.value = json.volume; }
      if (shVal) shVal.textContent = json.volume;
    }
    return json;
  });
}

// 反向同步：機械人嗰邊乜路改音量（頭頂 +/- 鍵／語音大細聲／另一個分頁條 slider）
// 都會經 LedCenter 個 VOLUME_CHANGED receiver 播 volume_changed WS event
// （見 app-log.js appendLog dispatch）。收到就將兩邊 slider＋讀數撥返去新值。
// 為免同用家正在拖緊嗰粒爭（server echo 慢半拍，會將拖緊嗰粒扯返去舊值），
// 本地郁過 slider 400ms 內嘅 event 一律 skip —— 時間戳記喺下面幾個本地
// push 位＋app-music.js 那邊一齊寫。
function markVolLocalInput() {
  try { window.__volLastLocalAt = Date.now(); } catch (e) { /* ignore */ }
}
function onVolumeChangedEvent(data) {
  if (!data) return;
  const v = Number(data.volume);
  if (!isFinite(v)) return;
  if (Date.now() - (window.__volLastLocalAt || 0) < 400) return;
  const max = Number(data.max);
  const pairs = [["volumeSlider", "volumeVal"], ["sharedVolumeSlider", "sharedVolumeVal"]];
  pairs.forEach(function (pair) {
    const slider = document.getElementById(pair[0]);
    if (!slider) return;
    if (isFinite(max)) slider.max = String(max);
    slider.value = String(v);
    const val = document.getElementById(pair[1]);
    if (val) val.textContent = String(v);
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

/** 建立 servo grid 的共用邏輯, 抽出來做獨立 function 方便日後有第二個 grid 要建
 *  當時不用複製貼上一份幾乎一樣的 code。
 *  @param wrapId       外層容器的 id ("servoGroups")
 *  @param sliderPrefix slider input 的 id prefix ("servoSlider_")
 *  @param valPrefix    數值顯示 span 的 id prefix ("servoSliderVal_")
 *  @param sendFn       (id, angle) => void, 拖完手之後實際送出去robot的call (servo/one)
 *  @param readPrefix   (可選) 「讀取所有角度」結果顯示 span 的 id prefix - 沒有傳的話
 *                       (Alpha2 現在還是這樣) 不會加這欄, 版面同以前一樣。
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

function buildServoGrid() {
  buildServoGridInto("servoGroups", "servoSlider_", "servoSliderVal_", function (id, angle) {
    return Alpha2Api.servoOne({id: id, angle: angle, time: servoTime()});
  }, "servoAngleVal_");
}

/** 一次過讀全部 20 軸即時角度（servo/angle-all：後端逐顆讀即寫回，約 6 秒），
 *  填入每行右邊讀數格。讀不到那顆顯示 -。讀取時請固定好機械人。讀數中按鈕
 *  disable 防連按。 */
function servoReadAllAngles() {
  const rows = document.querySelectorAll("#tab-servo [onclick^='servoRead']");
  const status = document.getElementById("servoReadStatus");
  rows.forEach(function (b) { b.disabled = true; });
  for (let i = 1; i <= 20; i++) {
    const cell = document.getElementById("servoAngleVal_" + i);
    if (cell) cell.textContent = "…";
  }
  if (status) status.textContent = t("servo_reading_hint");
  return Alpha2Api.servoAngleAll().then(function (res) {
    rows.forEach(function (b) { b.disabled = false; });
    if (!res || !res.ok || !res.angles) {
      if (status) status.textContent = t("servo_read_failed_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const failed = res.failed || [];
    for (let i = 1; i <= 20; i++) {
      const cell = document.getElementById("servoAngleVal_" + i);
      if (!cell) continue;
      const v = res.angles[i - 1];
      cell.textContent = (v === null || v === undefined) ? "-" : v;
    }
    if (status) {
      status.textContent = failed.length
        ? t("servo_read_done") + (uiLang === "en"
            ? " (unreadable: " + failed.join(",") + ")"
            : "（讀不到：" + failed.join(",") + "）")
        : t("servo_read_done");
    }
    return res;
  }).catch(function (e) {
    rows.forEach(function (b) { b.disabled = false; });
    if (status) status.textContent = t("servo_read_failed_prefix") + e;
  });
}

/** 「全部回到原位」直接寫入原廠角度 (servoAll)，sliders 顯示值歸 home；實際擺位以寫入為準，
 *  這個 card 純手動微調，不是位置 single source of truth。 */
// STOP_RECOVERY_ACTION_ID_HINT: 「蹲下站起」(id 1510818174706) - 同
// MainActivity.java 的 STOP_RECOVERY_ACTION_ID 一致, 兩處各自維護一份常量
// (前端 JS 同後端 Java 沒有共用常量的機制), 改當時要兩邊一齊改。
const SERVO_RESET_ACTION_ID = "1510818174706";

function resetServoGrid() {
  const homes = [];
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const slider = document.getElementById("servoSlider_" + i);
    const label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
    const advInp = document.getElementById("advServoVal_" + i);
    if (advInp) advInp.value = cal.home;
  }
  // 直接寫入原廠截圖角度（1:120 2:120 3:120 4:120 5:120 6:120 7:120 8:65 9:145 10:140 11:120 12:120 13:175 14:95 15:100 16:120 17:120 18:120 19:120 20:120），而非播放動作
  return Alpha2Api.servoAll({angles: homes.join(","), time: servoTime()});
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
  return Alpha2Api.servoAll( { angles: angles.join(","), time: servoTime() });
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
  return Alpha2Api.servoSonar({distance: distance});
}

// Toggle switch: off sends distance=0 (sonar disabled); on re-sends whatever the
// slider is currently set to, so flipping back on restores the last distance rather
// than requiring the person to re-drag the slider.
function toggleSonar() {
  const on = document.getElementById("sonarToggle").checked;
  const distance = on ? document.getElementById("sonarDist").value : "0";
  return Alpha2Api.servoSonar( { distance: distance });
}

// 真機已確認 cmd=72 開關生效，PIR 觸發正常。按 toggle 即送；結果看 API response ok/error，indicator 等 "alpha2_pir_state" WS event 先轉燈色。
function alpha2SetPir() {
  const on = document.getElementById("alpha2Pir").checked;
  return Alpha2Api.pirSet({on: on});
}

// 獨立於 alpha2SetPir() 硬體開關 — 純開/關「偵測到人就閃紅燈/響鈴」警示反應，對應 MainActivity#setPirAlertEnabledAlpha2()。
function alpha2SetPirAlertEnabled() {
  const on = document.getElementById("alpha2PirAlertEnabled").checked;
  return Alpha2Api.pirAlertEnabled( { on: on });
}

// ---------------- Advanced Servo Angle Tuner (tab-advanced) ----------------
function buildAdvTuner() {
  const grid = document.getElementById("advServoTunerGrid");
  if (!grid) return;
  grid.innerHTML = "";
  grid.style.display = "block";
  grid.style.gridTemplateColumns = "none";
  const groups = (typeof SERVO_GROUPS !== 'undefined' && SERVO_GROUPS) ? SERVO_GROUPS : [
    {key:'head', label:'頭', labelEn:'Head', icon:'🧠', ids:[19,20]},
    {key:'right-arm', label:'右手', labelEn:'R Arm', icon:'💪', ids:[1,2,3,17]},
    {key:'left-arm', label:'左手', labelEn:'L Arm', icon:'💪', ids:[4,5,6,18]},
    {key:'right-leg', label:'右腳', labelEn:'R Leg', icon:'🦵', ids:[7,8,9,10,11]},
    {key:'left-leg', label:'左腳', labelEn:'L Leg', icon:'🦵', ids:[12,13,14,15,16]}
  ];
  groups.forEach(function(g){
    const groupEl = document.createElement("div");
    groupEl.style.cssText = "margin:10px 0;border:1px solid var(--border);border-radius:10px;padding:8px;background:var(--card-bg)";
    const title = document.createElement("div");
    title.style.cssText = "font-weight:700;margin-bottom:6px";
    title.textContent = g.icon + " " + (typeof uiLang !== 'undefined' && uiLang === 'en' ? g.labelEn : g.label) + " (" + g.ids.join(",") + ")";
    groupEl.appendChild(title);
    const subGrid = document.createElement("div");
    subGrid.style.cssText = "display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px";
    g.ids.forEach(function(id){
      const cal = SERVO_CALIBRATION[id];
      const name = servoNameOf(id);
      const cell = document.createElement("div");
      cell.className = "servo-tuner-cell";
      cell.style.cssText = "border:1px solid var(--border);border-radius:8px;padding:8px;background:var(--card-bg)";
    cell.innerHTML =
      '<div style="font-weight:600;font-size:12px">#' + id + ' ' + name + '</div>' +
      '<div style="font-size:11px;color:var(--muted)">' + t('servo_tuner_range') + ' ' + cal.min + '-' + cal.max + ' home ' + cal.home + '</div>' +
      '<div style="display:flex;justify-content:space-between;align-items:center;margin:6px 0;font-size:12px">' +
        '<span>' + t('servo_tuner_angle') + ' <input type="number" id="advServoVal_' + id + '" value="' + cal.home + '" min="' + cal.min + '" max="' + cal.max + '" style="width:60px" onkeydown="if(event.key===\'Enter\'){advTunerSend(' + id + ');}"></span>' +
        '<span>' + t('servo_tuner_offset') + ' <span id="advServoOff_' + id + '" style="font-weight:600">-</span></span>' +
      '</div>' +
        '<div style="margin:4px 0"><button onmousedown="advHoldStart(' + id + ',1)" onmouseup="advHoldStop()" onmouseleave="advHoldStop()" ontouchstart="advHoldStart(' + id + ',1)" ontouchend="advHoldStop()" onclick="advTunerInc(' + id + ')" style="width:100%;padding:6px;background:#16a34a;color:white;border:none;border-radius:6px;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none">+1</button></div>' +
        '<div style="margin:4px 0"><button onmousedown="advHoldStart(' + id + ',-1)" onmouseup="advHoldStop()" onmouseleave="advHoldStop()" ontouchstart="advHoldStart(' + id + ',-1)" ontouchend="advHoldStop()" onclick="advTunerDec(' + id + ')" style="width:100%;padding:6px;background:#2563eb;color:white;border:none;border-radius:6px;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none">-1</button></div>';
      advPrevAngle[id] = cal.home;
      subGrid.appendChild(cell);
    });
    groupEl.appendChild(subGrid);
    grid.appendChild(groupEl);
  });
  grid.addEventListener('contextmenu', function(e){ e.preventDefault(); });
  grid.addEventListener('selectstart', function(e){ e.preventDefault(); });
  // init 格留 "-"（未知），不填計出來的 0 — 填 0 會覆蓋掉出廠 trim。
  advTrimScanned = {};
  // 版本標記：驗瀏覽器有無用舊 JS（見不到這行就是 正在cache舊版，做 Ctrl+F5）。
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = t("servo_tuner_ready", { v: "0906l" });
}
// 校准按鈕（官方「校准」同款）——將 20 格有效 trim 順序寫入 chest EEPROM（掉電保持）。格內無數／未掃描 skip 並報告；每粒附帶送本身角度（time=100，已在位不會動）。
function advCalibrateAll() {
  const statusEl = document.getElementById("advTunerStatus");
  const jobs = [];
  const skipped = [];
  for (let i = 1; i <= 20; i++) {
    const el = document.getElementById("advServoOff_" + i);
    const t = el ? parseInt(el.textContent, 10) : NaN;
    if (isNaN(t) || !advTrimScanned[i]) { skipped.push(i); continue; }
    const inp = document.getElementById("advServoVal_" + i);
    let v = inp ? parseInt(inp.value, 10) : SERVO_CALIBRATION[i].home;
    if (isNaN(v)) v = SERVO_CALIBRATION[i].home;
    jobs.push({ id: i, angle: clampServoAngle(i, v), trim: t });
  }
  if (!jobs.length) {
    statusEl.textContent = t("servo_tuner_cal_none");
    return Promise.resolve();
  }
  let idx = 0, okCount = 0;
  const failed = [], unknown = [];
  function next() {
    if (idx >= jobs.length) {
      let msg = t("servo_tuner_cal_done", { ok: okCount, n: jobs.length });
      if (unknown.length) msg += t("servo_tuner_cal_unknown", { ids: "#" + unknown.join(",#") });
      if (failed.length) msg += t("servo_tuner_cal_failed", { ids: "#" + failed.join(",#") });
      if (skipped.length) msg += t("servo_tuner_cal_skipped", { ids: "#" + skipped.join(",#") });
      statusEl.textContent = msg;
      return Promise.resolve();
    }
    const j = jobs[idx++];
    statusEl.textContent = t("servo_tuner_cal_progress", { i: idx, n: jobs.length, id: j.id, v: advFmtOff(j.trim) });
    return Alpha2Api.servoOne({ id: j.id, angle: j.angle, time: 100, trim: j.trim }).then(function(json){
      if (json.ok && json.trimWritten) okCount++;
      else if (json.ok && json.trimUnknown) unknown.push(j.id);
      else failed.push(j.id);
      return next();
    }).catch(function(){ failed.push(j.id); return next(); });
  }
  return next();
}
let advPrevAngle = {};
// 本 session 掃描／還原過 trim 的 id：Enter 寫入只跟這些去存 EEPROM，
// 不會用新頁計出來的數覆蓋出廠 trim（官方工具連線即讀，永遠有 baseline）。
let advTrimScanned = {};
function advTunerToggle(){
  const en = document.getElementById("advTunerEnabled").checked;
  document.getElementById("advTunerBody").style.display = en ? "block" : "none";
  document.getElementById("advTunerDisabledHint").style.display = en ? "none" : "block";
  if (en) buildAdvTuner();
}
let advHoldTimer=null, advHoldInt=null;
function advHoldStart(id, dir){
  advHoldStop();
  advHoldTimer=setTimeout(function(){ advHoldInt=setInterval(function(){ if(dir>0) advTunerInc(id); else advTunerDec(id); }, 120); }, 400);
}
function advHoldStop(){ clearTimeout(advHoldTimer); clearInterval(advHoldInt); }
document.addEventListener('mouseup', advHoldStop);
document.addEventListener('touchend', advHoldStop);
document.addEventListener('touchcancel', advHoldStop);
document.addEventListener('mouseleave', function(e){ if(e.target && e.target.closest && e.target.closest('.servo-tuner-cell')) advHoldStop(); });
function advServoTime() {
  return 500;
}
// offset 雙來源（同官方 tuner 一致，角:偏 = 1:3）。
// - 即時值：格內現值 + angle變化×3（掃回來的 trim 做底：-33 再 +1 即 -30）。
//   格內無數（-/讀失敗）先至用 3×(angle−home) 起步。
// - 實際讀取值：掃描經 cmd13 讀 chest 存住的 trim 原值。寫入（cmd05）不改 chest
//   trim，所以不會自動變回存值，下次掃描先對。
function advFmtOff(v) {
  return (v > 0 ? "+" + v : "" + v);
}
// 後端 servo 讀數錯誤字串中英對照（後端 Java 無 i18n，前端認住譯；
// 認不到就原文直出）。
function advErrText(e) {
  if (!e) return "";
  if (String(e).indexOf("no feedback") >= 0) return t("servo_tuner_err_nofeedback");
  return String(e);
}
function advErrSuffix(e) {
  const m = advErrText(e);
  return m ? ((typeof uiLang !== "undefined" && uiLang === "en" ? ": " : "：") + m) : "";
}
// 例外物件後綴（中英冒號；訊息本身是瀏覽器/系統英文，原樣跟）。
function advEx(e) {
  if (e === undefined || e === null || e === "") return "";
  const m = (e && e.message !== undefined) ? e.message : String(e);
  if (!m) return "";
  return ((typeof uiLang !== "undefined" && uiLang === "en" ? ": " : "：") + m);
}
function advHomeOf(id) {
  const cal = (typeof SERVO_CALIBRATION !== 'undefined' && SERVO_CALIBRATION[id]) ? SERVO_CALIBRATION[id] : null;
  return cal ? cal.home : 120;
}
function advRefreshOffComputed(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = inp ? parseInt(inp.value, 10) : advHomeOf(id);
  if (isNaN(v)) v = advHomeOf(id);
  const el = document.getElementById("advServoOff_" + id);
  if (el) el.textContent = advFmtOff(3 * (v - advHomeOf(id))) + t("servo_tuner_live_suffix");
}
function advNudgeOff(id, deltaAngle) {
  const el = document.getElementById("advServoOff_" + id);
  if (!el) return;
  const cur = parseInt(el.textContent, 10);
  if (isNaN(cur)) { advRefreshOffComputed(id); return; }
  el.textContent = advFmtOff(cur + deltaAngle * 3) + t("servo_tuner_live_suffix");
}
function advTunerSend(id, timeMs) {
  // 加減/輸入格 Enter 一律僅送角度 cmd05（Enter 500ms，加減 100ms）。trim 只經「校准」按鈕成組寫入，這裡不碰 EEPROM。
  if (timeMs === undefined || timeMs === null) timeMs = advServoTime();
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10);
  v = clampServoAngle(id, isNaN(v) ? SERVO_CALIBRATION[id].home : v);
  const prev = advPrevAngle[id] !== undefined ? advPrevAngle[id] : v;
  const delta = v - prev;
  advPrevAngle[id] = v;
  inp.value = v;
  if (delta !== 0) advNudgeOff(id, delta);
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = t("servo_tuner_nudge_ok", { id: id, v: v });
  return Alpha2Api.servoOne({ id: id, angle: v, time: timeMs }).then(function(json){
    if (!json.ok) statusEl.textContent = t("servo_tuner_nudge_fail", { id: id, e: (json.error || JSON.stringify(json)) });
    return json;
  }).catch(function(e){ statusEl.textContent = t("servo_tuner_nudge_err", { id: id, e: e.message }); });
}
function advTunerInc(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v + 1);
  inp.value = v;
  return advTunerSend(id, 100);
}
function advTunerDec(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v - 1);
  inp.value = v;
  return advTunerSend(id, 100);
}
function advTunerReadAll() {
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = t("servo_tuner_read_all");
  return Alpha2Api.servoReadAll().then(function(json){
    if (!json.ok || !json.live || !json.trims || json.trims.length !== 20) {
      statusEl.textContent = t("servo_tuner_read_fail", { id: "1-20" }) + advErrSuffix(json.error);
      return json;
    }
    let okCount = 0;
    for (let i = 1; i <= 20; i++) {
      const v = json.trims[i - 1];
      if (v === null || v === undefined) {
        document.getElementById("advServoOff_" + i).textContent = t("servo_tuner_no_feedback");
      } else {
        okCount++;
        document.getElementById("advServoOff_" + i).textContent = advFmtOff(v);
        advTrimScanned[i] = true;
      }
    }
    const fails = (json.failed && json.failed.length) ? t("servo_tuner_read_fails", { ids: "#" + json.failed.join(",#") }) : "";
    statusEl.textContent = t("servo_tuner_read_done", { ok: okCount }) + fails;
    return json;
  }).catch(function(e){ statusEl.textContent = t("servo_tuner_read_err", { e: e.message }); });
}
function advTunerReset() {
  // 復位 = 成組回home，動完自動重新掃描 trim（chest 存值不受動角度影響）。格設「…」等掃，不填計出來的數；兼清 scanned 旗 — 填 0 會寫入 EEPROM 覆蓋掉出廠 trim。
  const homes = [];
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const inp = document.getElementById("advServoVal_" + i);
    if (inp) inp.value = cal.home;
    advPrevAngle[i] = cal.home;
    const cell = document.getElementById("advServoOff_" + i);
    if (cell) cell.textContent = "…";
    delete advTrimScanned[i];
  }
  document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_sending");
  return Alpha2Api.servoAll( { angles: homes.join(","), time: 500 }).then(function(json){
    if (!json.ok) {
      document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_fail", { e: (json.error || "") });
      return json;
    }
    document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_done");
    return new Promise(function(resolve) {
      setTimeout(function() { resolve(advTunerReadAll()); }, 1500);
    });
  });
}
function _advPerformBackupNow() {
  // 僅備份 offsets（角度格是即時目標，寫下 file 無用）。讀失敗記 null；有效性經 advTrimScanned 判。
  const offsets = {};
  for (let i = 1; i <= 20; i++) {
    if (!advTrimScanned[i]) { offsets[i] = null; continue; }
    const offEl = document.getElementById("advServoOff_" + i);
    const rawTxt = offEl ? offEl.textContent.trim() : "";
    const parsed = parseInt(rawTxt, 10);
    offsets[i] = isNaN(parsed) ? null : parsed;
  }
  const payload = {
    version: 2,
    type: "servo_tuner_backup",
    exportedAt: new Date().toISOString(),
    offsets: offsets
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], {type: "application/json"});
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  const ts = new Date().toISOString().slice(0,19).replace(/[:T]/g,"-");
  a.download = "servo_offset_backup_" + ts + ".json";
  a.click();
  URL.revokeObjectURL(url);
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = t("servo_tuner_backup_done") + " (" + ts + ")";
}
function advBackup() {
  const statusEl = document.getElementById("advTunerStatus");
  // 必須先掃一次 offset，否則沒有結果：檢測仍顯示 "-" 的格子
  let needScan = false;
  let unscanned = 0;
  for (let i = 1; i <= 20; i++) {
    const offEl = document.getElementById("advServoOff_" + i);
    const txt = offEl ? offEl.textContent.trim() : "-";
    // "-"（新頁）/"…"（準備後等掃）先自動掃；數字（pending 或存值）同「讀失敗」
    // 不掃——不要覆蓋掉用戶 正在tune的數。
    if (txt === "-" || txt === "" || txt === "…") { needScan = true; unscanned++; }
  }
  if (needScan) {
    if (statusEl) statusEl.textContent = t("servo_tuner_backup_need_scan") + " " + t("servo_tuner_unscanned", { n: unscanned });
    // 自動先掃一次，再備份
    return advTunerReadAll().then(function() {
      // advTunerReadAll 已將 status 設為讀取完成，緊接覆蓋為備份完成
      _advPerformBackupNow();
    }).catch(function(err){
      if (statusEl) statusEl.textContent = t("servo_tuner_backup_fail") + advEx(err);
    });
  } else {
    _advPerformBackupNow();
    return Promise.resolve();
  }
}
function advImport(input) {
  const file = input.files[0];
  if (!file) return;
  const statusEl = document.getElementById("advTunerStatus");
  const reader = new FileReader();
  reader.onload = function(e) {
    try {
      const data = JSON.parse(e.target.result);
      // 僅還原 offsets（舊檔有 angles 都不理，角度格不動）。
      let offsets = null;
      if (data && typeof data === "object" && data.offsets) {
        offsets = data.offsets;
      } else if (data && typeof data === "object" && !data.angles) {
        offsets = data;
      }
      let countOff = 0;
      for (let i = 1; i <= 20; i++) {
        const k = String(i);
        // offsets: restore display
        let raw = null;
        if (offsets && offsets[k] !== undefined) raw = offsets[k];
        else if (offsets && offsets[i] !== undefined) raw = offsets[i];
        if (raw !== null && raw !== undefined) {
          let num = typeof raw === "number" ? raw : parseInt(String(raw).trim(), 10);
          if (isNaN(num)) continue;
          const el = document.getElementById("advServoOff_" + i);
          if (el) {
            el.textContent = advFmtOff(num);
            advTrimScanned[i] = true;
            countOff++;
          }
        }
      }
      if (statusEl) {
        if (countOff === 0) {
          statusEl.textContent = t("servo_tuner_restore_fail") + t("servo_tuner_restore_empty");
        } else {
          statusEl.textContent = t("servo_tuner_restore_done") + t("servo_tuner_restore_detail", { off: countOff });
        }
      }
    } catch (err) {
      if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + advEx(err);
    } finally {
      // allow re-selecting same file
      try { input.value = ""; } catch(e) {}
    }
  };
  reader.onerror = function() {
    if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + t("servo_tuner_restore_readerr");
    try { input.value = ""; } catch(e) {}
  };
  reader.readAsText(file);
}

