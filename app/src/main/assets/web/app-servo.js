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
      document.getElementById("calibOffsetVal").textContent = advFmtOff(calibCurrentOffset) + " (回讀)";
      statusEl.textContent = t("servo_calib_read_ok", { angle: calibCurrentAngle, offset: advFmtOff(calibCurrentOffset) });
    } else if (json.ok && json.sent) {
      statusEl.textContent = "已發送 0x0D 查詢 #" + calibCurrentServo + "，請查看下方「即時事件 Log」中的 chest_rcv / servo_read hex 回包（3秒內）";
    } else {
      statusEl.textContent = t("servo_calib_read_fail", { error: json.error || "unknown" });
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
  if (el) el.textContent = (off > 0 ? "+" + off : "" + off) + " (即時)";
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
      statusEl.textContent = "已發送全部 1-20 查詢，請查看下方 chest_rcv / servo_read 回包";
      return Promise.resolve();
    }
    const sid = idx;
    return Alpha2Api.servoRead( { id: sid }).then(function(json) {
      if (json.ok && json.live && json.trim !== undefined && json.trim !== null) {
        if (sid === calibCurrentServo) {
          calibCurrentOffset = json.trim;
          document.getElementById("calibOffsetVal").textContent = advFmtOff(calibCurrentOffset) + " (回讀)";
        }
      }
      statusEl.textContent = t("servo_calib_read_all_progress", { cur: sid, total: 20 }) || ("讀取中 " + sid + "/20 已發送");
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
  for (let i = 1; i <= 20; i++) advRefreshOffComputed(i);
  // 版本標記：驗瀏覽器有無食舊 JS（見唔到呢行即係 cache 緊舊版，做 Ctrl+F5）。
  const statusEl = document.getElementById("advTunerStatus");
  if (statusEl) statusEl.textContent = "tuner 0906e 就緒（offset 即時 = 格內值±3×角度變化，掃描讀 chest 存值）";
}
let advPrevAngle = {};
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
function advHomeOf(id) {
  const cal = (typeof SERVO_CALIBRATION !== 'undefined' && SERVO_CALIBRATION[id]) ? SERVO_CALIBRATION[id] : null;
  return cal ? cal.home : 120;
}
function advRefreshOffComputed(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = inp ? parseInt(inp.value, 10) : advHomeOf(id);
  if (isNaN(v)) v = advHomeOf(id);
  const el = document.getElementById("advServoOff_" + id);
  if (el) el.textContent = advFmtOff(3 * (v - advHomeOf(id))) + " (即時)";
}
function advNudgeOff(id, deltaAngle) {
  const el = document.getElementById("advServoOff_" + id);
  if (!el) return;
  const cur = parseInt(el.textContent, 10);
  if (isNaN(cur)) { advRefreshOffComputed(id); return; }
  el.textContent = advFmtOff(cur + deltaAngle * 3) + " (即時)";
}
function advTunerSend(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10);
  v = clampServoAngle(id, isNaN(v) ? SERVO_CALIBRATION[id].home : v);
  const prev = advPrevAngle[id] !== undefined ? advPrevAngle[id] : v;
  const delta = v - prev;
  advPrevAngle[id] = v;
  inp.value = v;
  if (delta !== 0) advNudgeOff(id, delta);
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = "寫入 #" + id + " -> " + v + "...";
  // 2026-09-06 晚：連 offset 格個 trim 一齊寫入 chest EEPROM（官方 tuner 同款；
  // 格內無數就淨寫角度）。掉電保持，寫之前最好先備份。
  let trim = null;
  const offEl = document.getElementById("advServoOff_" + id);
  if (offEl) {
    const t = parseInt(offEl.textContent, 10);
    if (!isNaN(t)) trim = t;
  }
  const params = { id: id, angle: v, time: advServoTime() };
  if (trim !== null) params.trim = trim;
  return Alpha2Api.servoOne(params).then(function(json){
    if (json.ok) {
      let msg = "寫入 #" + id + " 成功 (" + v + ")";
      if (trim !== null) msg += json.trimWritten ? "，trim " + advFmtOff(trim) + " 已存" : "，trim 寫入失敗";
      statusEl.textContent = msg;
    }
    else statusEl.textContent = "寫入 #" + id + " 失敗: " + (json.error || JSON.stringify(json));
    return json;
  }).catch(function(e){ statusEl.textContent = "寫入 #" + id + " 錯誤: " + e.message; });
}
function advTunerInc(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v + 1);
  inp.value = v;
  return advTunerSend(id);
}
function advTunerDec(id) {
  const inp = document.getElementById("advServoVal_" + id);
  let v = parseInt(inp.value, 10) || SERVO_CALIBRATION[id].home;
  v = clampServoAngle(id, v - 1);
  inp.value = v;
  return advTunerSend(id);
}
function advTunerRead(id) {
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = "讀取 #" + id + " trim...";
  return Alpha2Api.servoRead( { id: id }).then(function(json) {
    // 2026-09-06: 後端回的是 chest trim 原值（官方單位），offset 格照 show，
    // angle 輸入格唔郁（trim 唔係絕對角度）。讀唔到顯示讀失敗。
    if (json.ok && json.live && json.trim !== undefined && json.trim !== null) {
      document.getElementById("advServoOff_" + id).textContent = advFmtOff(json.trim) + " (實讀)";
      statusEl.textContent = "#" + id + " trim " + advFmtOff(json.trim);
    } else {
      document.getElementById("advServoOff_" + id).textContent = "讀失敗";
      statusEl.textContent = "#" + id + " 讀失敗" + (json.error ? "：" + json.error : "");
    }
  }).catch(function(e){ statusEl.textContent = "讀取錯誤: " + e.message; });
}
function advTunerReadAll() {
  const statusEl = document.getElementById("advTunerStatus");
  statusEl.textContent = "一鍵實讀全部 trim 1-20（約幾秒）...";
  return Alpha2Api.servoReadAll().then(function(json){
    if (!json.ok || !json.live || !json.trims || json.trims.length !== 20) {
      statusEl.textContent = "讀取失敗" + (json.error ? "：" + json.error : "");
      return json;
    }
    let okCount = 0;
    for (let i = 1; i <= 20; i++) {
      const v = json.trims[i - 1];
      if (v === null || v === undefined) {
        document.getElementById("advServoOff_" + i).textContent = "讀失敗";
      } else {
        okCount++;
        document.getElementById("advServoOff_" + i).textContent = advFmtOff(v) + " (實讀)";
      }
    }
    const fails = (json.failed && json.failed.length) ? "，無回授: #" + json.failed.join(",#") : "";
    statusEl.textContent = "實讀完成 " + okCount + "/20" + fails;
    return json;
  }).catch(function(e){ statusEl.textContent = "讀取錯誤: " + e.message; });
}
function advTunerReset() {
  // 2026-09-06: 準備 = 成組返 home，鬱完自動重掃 trim（chest 存值唔受郁角度
  // 影響，掃返嚟先對得上）。2026-09-06 晚：取消確認框（用戶要求）。
  const homes = [];
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const inp = document.getElementById("advServoVal_" + i);
    if (inp) inp.value = cal.home;
    advPrevAngle[i] = cal.home;
    advRefreshOffComputed(i);
  }
  document.getElementById("advTunerStatus").textContent = "送 standby 姿勢中...";
  return Alpha2Api.servoAll( { angles: homes.join(","), time: 500 }).then(function(json){
    if (!json.ok) {
      document.getElementById("advTunerStatus").textContent = "重置失敗: " + (json.error || "");
      return json;
    }
    document.getElementById("advTunerStatus").textContent = "已重置為 standby 姿勢，1.5 秒後重掃 trim...";
    return new Promise(function(resolve) {
      setTimeout(function() { resolve(advTunerReadAll()); }, 1500);
    });
  });
}
function advTunerSetAll() {
  // 2026-09-06 晚：取消確認框（用戶要求）。注意：呢個掣一次郁 20 粒，
  // 輸入值同部機實際唔啱會成組扯位；驚就逐粒寫入。
  const vals = [];
  for (let i = 1; i <= 20; i++) {
    const inp = document.getElementById("advServoVal_" + i);
    let v = parseInt(inp ? inp.value : SERVO_CALIBRATION[i].home, 10);
    v = clampServoAngle(i, isNaN(v) ? SERVO_CALIBRATION[i].home : v);
    vals.push(v);
  }
  return Alpha2Api.servoAll( { angles: vals.join(","), time: 500 }).then(function(json){
    document.getElementById("advTunerStatus").textContent = json.ok ? "全部寫入成功" : "寫入失敗: " + (json.error || "");
    return json;
  });
}
function _advPerformBackupNow() {
  const offsets = {};
  const angles = {};
  for (let i = 1; i <= 20; i++) {
    const offEl = document.getElementById("advServoOff_" + i);
    const rawTxt = offEl ? offEl.textContent.trim() : "0";
    // 2026-09-06: 讀失敗（壞舵機/無回授）記 null，唔好扮 0 誤導還原。
    let num = null;
    if (rawTxt !== "讀失敗" && rawTxt !== "-" && rawTxt !== "") {
      const parsed = parseInt(rawTxt, 10);
      num = isNaN(parsed) ? null : parsed;
    }
    offsets[i] = num;
    const inp = document.getElementById("advServoVal_" + i);
    let v = inp ? parseInt(inp.value, 10) : SERVO_CALIBRATION[i].home;
    if (isNaN(v)) v = SERVO_CALIBRATION[i].home;
    v = clampServoAngle(i, v);
    angles[i] = v;
  }
  const payload = {
    version: 1,
    type: "servo_tuner_backup",
    exportedAt: new Date().toISOString(),
    offsets: offsets,
    angles: angles
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
    if (txt === "-" || txt === "" ) { needScan = true; unscanned++; }
  }
  if (needScan) {
    if (statusEl) statusEl.textContent = t("servo_tuner_backup_need_scan") + " (" + unscanned + "/20 未讀)…";
    // 自動先掃一次，再備份
    return advTunerReadAll().then(function() {
      // advTunerReadAll 已將 status 設為「全部讀取完成」，緊接覆蓋為備份完成
      _advPerformBackupNow();
    }).catch(function(err){
      if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + ": 掃描失敗 " + (err && err.message ? err.message : err);
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
      let offsets = null, angles = null;
      if (data && typeof data === "object" && (data.offsets || data.angles)) {
        offsets = data.offsets || {};
        angles = data.angles || null;
      } else {
        offsets = data;
      }
      let countOff = 0, countAng = 0;
      for (let i = 1; i <= 20; i++) {
        const k = String(i);
        // angles: restore input + advPrevAngle
        if (angles && angles[k] !== undefined) {
          let v = parseInt(angles[k], 10);
          if (!isNaN(v)) {
            v = clampServoAngle(i, v);
            const inp = document.getElementById("advServoVal_" + i);
            if (inp) { inp.value = v; countAng++; }
            advPrevAngle[i] = v;
          }
        } else if (angles && angles[i] !== undefined) {
          let v = parseInt(angles[i], 10);
          if (!isNaN(v)) {
            v = clampServoAngle(i, v);
            const inp = document.getElementById("advServoVal_" + i);
            if (inp) { inp.value = v; countAng++; }
            advPrevAngle[i] = v;
          }
        }
        // offsets: restore display
        let raw = null;
        if (offsets && offsets[k] !== undefined) raw = offsets[k];
        else if (offsets && offsets[i] !== undefined) raw = offsets[i];
        if (raw !== null && raw !== undefined) {
          let num = typeof raw === "number" ? raw : parseInt(String(raw).trim(), 10);
          if (isNaN(num)) num = 0;
          const el = document.getElementById("advServoOff_" + i);
          if (el) {
            el.textContent = (num > 0 ? "+" + num : String(num));
            countOff++;
          }
          // if angles not supplied, sync prevAngle to current input so後續 delta 正確
          if (!angles || (angles[k] === undefined && angles[i] === undefined)) {
            const inp = document.getElementById("advServoVal_" + i);
            if (inp) {
              let cur = parseInt(inp.value, 10);
              if (!isNaN(cur)) advPrevAngle[i] = cur;
            }
          }
        }
      }
      if (statusEl) {
        if (countOff === 0 && countAng === 0) {
          statusEl.textContent = t("servo_tuner_restore_fail") + ": 無有效數據";
        } else {
          let msg = t("servo_tuner_restore_done");
          if (countAng > 0) msg += " (角度 " + countAng + " / offset " + countOff + ")";
          else msg += " (offset " + countOff + ")";
          msg += " — 按「全部寫入」才會送到舵機";
          statusEl.textContent = msg;
        }
      }
    } catch (err) {
      if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + ": " + err.message;
    } finally {
      // allow re-selecting same file
      try { input.value = ""; } catch(e) {}
    }
  };
  reader.onerror = function() {
    if (statusEl) statusEl.textContent = t("servo_tuner_restore_fail") + ": 讀檔失敗";
    try { input.value = ""; } catch(e) {}
  };
  reader.readAsText(file);
}
