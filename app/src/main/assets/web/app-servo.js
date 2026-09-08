// Open Alpha2 — client logic (app-servo.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 媒體音量、servo grid (共用 buildServoGridInto())、聲納。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

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

/** 建立 servo grid 嘅共用邏輯, 抽出嚟做獨立 function 方便日後有第二個 grid 要建
 *  嗰陣唔使複製貼上一份幾乎一樣嘅 code。
 *  @param wrapId       外層容器嘅 id ("servoGroups")
 *  @param sliderPrefix slider input 嘅 id prefix ("servoSlider_")
 *  @param valPrefix    數值顯示 span 嘅 id prefix ("servoSliderVal_")
 *  @param sendFn       (id, angle) => void, 拖完手之後實際送出去robot嘅call (servo/one)
 *  @param readPrefix   (可選) 「讀取所有角度」結果顯示 span 嘅 id prefix - 冇傳嘅話
 *                       (Alpha2 而家仲係咁) 唔會加呢欄, 版面同以前一樣。
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

/** 一次過讀全部 20 軸即時角度（servo/angle-all：後端逐粒讀即寫回，約 6 秒），
 *  填入每行右邊讀數格。讀不到嗰粒顯示 -。讀取時請固定好機械人。讀數中掣
 *  disable 防連撳。 */
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

/** 2026-08 修正: 用戶要求「全部回到原位」唔再係逐粒 servo 拉去佢個 calibration
 *  表嘅 home 度數 (之前呢個做法), 改為播放「蹲下站起」呢個內建動作 (id 見
 *  STOP_RECOVERY_ACTION_ID_HINT 底下嘅 comment, 同 MainActivity.java 嘅
 *  STOP_RECOVERY_ACTION_ID 呼應) - 用返 action/play, 唔再逐粒 servo/one。UI
 *  sliders 淨係做返視覺提示噉將顯示值歸返做 home 度數 (方便用戶睇到「已經
 *  reset 咗」), 唔再另外逐粒 send servo/one - 實際擺位由播放緊嘅動作本身
 *  決定, sliders 嘅顯示值同動作播完之後嘅真實角度可能唔完全一致, 但呢個
 *  card 一路都純粹係手動微調用途, 唔係位置嘅 single source of truth。 */
// STOP_RECOVERY_ACTION_ID_HINT: 「蹲下站起」(id 1510818174706) - 同
// MainActivity.java 嘅 STOP_RECOVERY_ACTION_ID 一致, 兩處各自維護一份常量
// (前端 JS 同後端 Java 冇共用常量嘅機制), 改嗰陣要兩邊一齊改。
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

// 2026-08-15 更新: 真機已確認 cmd=72 開關生效, PIR 觸發正常 (見
// RobotEventReceiver/MainActivity 嘅 comment)。撳 toggle 就送出去, 冇 optimistic
// UI 假設一定成功 - 結果淨係睇 API response 嘅 ok/error, alpha2PirIndicator 本身要等
// "alpha2_pir_state" WebSocket event 先會轉燈色 (見 app-log.js/onAlpha2PirState())。
function alpha2SetPir() {
  const on = document.getElementById("alpha2Pir").checked;
  return Alpha2Api.pirSet({on: on});
}

// 2026-08-15 新增: 獨立於 alpha2SetPir() 感應器硬體開關本身 - 純粹開/關「偵測到人
// 就閃紅燈/響鈴」這個警示反應, 對應 MainActivity#setPirAlertEnabledAlpha2()。
function alpha2SetPirAlertEnabled() {
  const on = document.getElementById("alpha2PirAlertEnabled").checked;
  return Alpha2Api.pirAlertEnabled( { on: on });
}

// ---------------- Servo Calibration ----------------
//
// 實作 UBTech 風格的校準流程:
// 1. 將機械人重置為參考姿勢 (站直, 手臂水平)
// 2. 選擇要校準的舵機
// 3. 使用 +/- 微調直到姿勢正確
// 4. 讀取目前角度/偏移
// 5. 將校準儲存到舵機 EEPROM

let calibCurrentServo = 1;
let calibCurrentAngle = 120;
let calibCurrentOffset = 0;

function calibInitSelect() {
  const select = document.getElementById("calibServoSelect");
  if (!select) return;
  select.innerHTML = "";
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    const name = servoNameOf(i);
    const opt = document.createElement("option");
    opt.value = i;
    opt.textContent = "#" + i + " " + name + " (home=" + cal.home + ")";
    select.appendChild(opt);
  }
  select.value = calibCurrentServo;
}

function calibSelectServo() {
  const select = document.getElementById("calibServoSelect");
  if (!select) return;
  calibCurrentServo = parseInt(select.value, 10);
  const cal = SERVO_CALIBRATION[calibCurrentServo];
  calibCurrentAngle = cal.home;
  document.getElementById("calibCurrentVal").textContent = calibCurrentAngle;
  document.getElementById("calibOffsetVal").textContent = "0";
  document.getElementById("calibStatus").textContent = t("servo_calib_selected", { id: calibCurrentServo });
}

function calibReset() {
  document.getElementById("calibStatus").textContent = t("servo_calib_resetting");
  return resetServoGrid().then(function() {
    setTimeout(function() {
      document.getElementById("calibStatus").textContent = t("servo_calib_reset_done");
      calibSelectServo();
    }, 2000);
  });
}

function calibReadCurrent() {
  const statusEl = document.getElementById("calibStatus");
  statusEl.textContent = t("servo_calib_reading");
  return Alpha2Api.servoRead({id: calibCurrentServo}).then(function(json) {
    // 2026-09-06: 後端回 chest trim 原值，即 offset 本體，照 show。
    if (json.ok && json.live && json.trim !== undefined && json.trim !== null) {
      calibCurrentOffset = json.trim;
      document.getElementById("calibOffsetVal").textContent = advFmtOff(calibCurrentOffset);
      statusEl.textContent = t("servo_calib_read_ok", { angle: calibCurrentAngle, offset: advFmtOff(calibCurrentOffset) });
    } else {
      statusEl.textContent = t("servo_calib_read_fail", { error: advErrText(json.error) || "unknown" });
    }
    return json;
  }).catch(function(err) {
    statusEl.textContent = t("servo_calib_read_err", { err: err.message });
  });
}

function updateCalibOffsetReal() {
  const cal = SERVO_CALIBRATION[calibCurrentServo];
  if (!cal) return;
  const off = calibCurrentAngle - cal.home;
  const el = document.getElementById("calibOffsetVal");
  if (el) el.textContent = (off > 0 ? "+" + off : "" + off) + t("servo_tuner_live_suffix");
}
function calibInc() {
  const cal = SERVO_CALIBRATION[calibCurrentServo];
  calibCurrentAngle = Math.min(cal.max, calibCurrentAngle + 1);
  document.getElementById("calibCurrentVal").textContent = calibCurrentAngle;
  updateCalibOffsetReal();
  return Alpha2Api.servoOne( { id: calibCurrentServo, angle: calibCurrentAngle, time: servoTime() });
}

function calibDec() {
  const cal = SERVO_CALIBRATION[calibCurrentServo];
  calibCurrentAngle = Math.max(cal.min, calibCurrentAngle - 1);
  document.getElementById("calibCurrentVal").textContent = calibCurrentAngle;
  updateCalibOffsetReal();
  return Alpha2Api.servoOne( { id: calibCurrentServo, angle: calibCurrentAngle, time: servoTime() });
}
let calibHoldTimer = null, calibHoldInt = null;
function calibHoldStart(dir) {
  calibHoldStop();
  calibHoldTimer = setTimeout(function(){
    calibHoldInt = setInterval(function(){ if(dir>0) calibInc(); else calibDec(); }, 120);
  }, 400);
}
function calibHoldStop(){ clearTimeout(calibHoldTimer); clearInterval(calibHoldInt); }

function calibReadAll() {
  const statusEl = document.getElementById("calibStatus");
  statusEl.textContent = t("servo_calib_reading_all");
  let idx = 1;
  function next() {
    if (idx > 20) {
      statusEl.textContent = t("servo_calib_read_all_done");
      return Promise.resolve();
    }
    const sid = idx;
    return Alpha2Api.servoRead( { id: sid }).then(function(json) {
      if (json.ok && json.live && json.trim !== undefined && json.trim !== null) {
        if (sid === calibCurrentServo) {
          calibCurrentOffset = json.trim;
          document.getElementById("calibOffsetVal").textContent = advFmtOff(calibCurrentOffset);
        }
      }
      statusEl.textContent = t("servo_calib_read_all_progress", { cur: sid, total: 20 });
      idx++;
      return new Promise(function(resolve) { setTimeout(function() { resolve(next()); }, 500); });
    }).catch(function(err) {
      statusEl.textContent = t("servo_calib_read_err", { err: err.message });
      idx++;
      return new Promise(function(resolve) { setTimeout(function() { resolve(next()); }, 500); });
    });
  }
  return next();
}

function calibSave() {
  const statusEl = document.getElementById("calibStatus");
  statusEl.textContent = t("servo_calib_saving");
  statusEl.textContent = t("servo_calib_save_todo", { id: calibCurrentServo, offset: calibCurrentOffset });
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
  // 2026-09-06 晚：init 格留 "-"（未知），唔扮計出嚟嘅 0——舊版開頁即填
  // "0 (即時)"，加埋 stale flag 導致出廠 trim 被計出嚟嘅 0 碌走，已出事。
  advTrimScanned = {};
  // 版本標記：驗瀏覽器有無食舊 JS（見唔到呢行即係 cache 緊舊版，做 Ctrl+F5）。
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = t("servo_tuner_ready", { v: "0906l" });
}
// 2026-09-06 深夜加：校准掣（官方「校准」同款）——將 20 格有效 trim 順序寫入
// chest EEPROM（掉電保持）。格內無數／未掃描過嗰粒自動 skip 並報告；
// 每粒附帶送多次本身角度（time=100，本身已喺位，唔會郁）。
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
// 本 session 掃描／還原過 trim 的 id：Enter 寫入只跟呢啲去存 EEPROM，
// 唔會用新頁計出嚟嘅數覆蓋出廠 trim（官方工具連線即讀，永遠有 baseline）。
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
// 2026-09-06: offset 雙來源（同官方 tuner 一致，官方角:偏 = 1:3）。
// - 即時值：格內現值 + angle變化×3（掃返嚟嘅 trim 做底：-33 再 +1 即 -30）。
//   格內無數（-/讀失敗）先至用 3×(angle−home) 起步。
// - 實讀值：掃描經 cmd13 讀 chest 存住的 trim 原值。寫入（cmd05）唔改 chest
//   trim，所以唔會自動變返存值，下次掃描先對。
function advFmtOff(v) {
  return (v > 0 ? "+" + v : "" + v);
}
// 後端 servo 讀數錯誤字串中英對照（後端 Java 無 i18n，前端認住譯；
// 認唔到就原文直出）。
function advErrText(e) {
  if (!e) return "";
  if (String(e).indexOf("no feedback") >= 0) return t("servo_tuner_err_nofeedback");
  return String(e);
}
function advErrSuffix(e) {
  const m = advErrText(e);
  return m ? ((typeof uiLang !== "undefined" && uiLang === "en" ? ": " : "：") + m) : "";
}
// 例外物件後綴（中英冒號；訊息本身係瀏覽器/系統英文，原樣跟）。
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
  // 2026-09-06 晚（跟足官方 ServoCalibration）：加減/輸入格 Enter 一律淨送
  // 角度 cmd05（Enter 500ms glide，加減 100ms 貼手跟）。trim 只經「校准」掣
  // 成組寫入，呢度永不掂 EEPROM。
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
function advTunerRead(id) {
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = t("servo_tuner_read_one", { id: id });
  return Alpha2Api.servoRead( { id: id }).then(function(json) {
    // 2026-09-06: 後端回的是 chest trim 原值（官方單位），offset 格照 show，
    // angle 輸入格唔郁（trim 唔係絕對角度）。讀唔到顯示無回授。
    if (json.ok && json.live && json.trim !== undefined && json.trim !== null) {
      document.getElementById("advServoOff_" + id).textContent = advFmtOff(json.trim);
      advTrimScanned[id] = true;
      statusEl.textContent = t("servo_tuner_read_one_ok", { id: id, v: advFmtOff(json.trim) });
    } else {
      document.getElementById("advServoOff_" + id).textContent = t("servo_tuner_no_feedback");
      statusEl.textContent = t("servo_tuner_read_fail", { id: id }) + advErrSuffix(json.error);
    }
  }).catch(function(e){ statusEl.textContent = t("servo_tuner_read_err", { e: e.message }); });
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
  // 2026-09-06: 復位 = 成組返 home，鬱完自動重掃 trim（chest 存值唔受郁角度
  // 影響，掃返嚟先對得上）。2026-09-06 晚：取消確認框（用戶要求）。
  // 2026-09-06 深夜修：格設「…」等掃，唔再即填計出嚟嘅數；兼清 scanned 旗——
  // 舊版即填 0 加 stale 旗，之後 Enter/存偏差會將計出嚟嘅 0 寫入 EEPROM
  // 碌走出廠 trim（已出事，多粒中招）。
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
  // 2026-09-06 晚改：淨備份 offsets（角度輸入格係即時目標，永遠唔會變，
  // 寫落 file 無用）。讀失敗記 null；有效性經 advTrimScanned 判，唔再聞格仔字。
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
  // 必須先掃一次 offset，否則交白卷：檢測仍顯示 "-" 的格子
  let needScan = false;
  let unscanned = 0;
  for (let i = 1; i <= 20; i++) {
    const offEl = document.getElementById("advServoOff_" + i);
    const txt = offEl ? offEl.textContent.trim() : "-";
    // "-"（新頁）/"…"（準備後等掃）先自動掃；數字（pending 或存值）同「讀失敗」
    // 唔掃——唔好碌走用戶 tune 緊嘅數。
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
      // 2026-09-06 晚改：淨還原 offsets（v1 舊檔有 angles 都唔理，角度格唔郁）。
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
