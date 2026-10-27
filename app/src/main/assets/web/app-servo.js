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
// Dragging sends throttled live servo/one commands (about 8 Hz) so the joint
// follows the slider immediately; releasing sends one final command using the
// time field. Per-slider queues coalesce intermediate input ticks, so a drag
// doesn't flood the AIDL bridge with dozens of calls per second.

// 拖動時即時跟隨：最快每 120ms 送一次（約 8Hz），每步 180ms 行完。
const SERVO_LIVE_INTERVAL_MS = 120;
const SERVO_LIVE_TIME_MS = 180;
// 「全部回到原位」固定 1200ms（唔跟時間欄，避免同 IK 回 boot pose 唔一致）。
const SERVO_RESET_TIME_MS = 1200;

function servoTime() {
  const el = document.getElementById("servoAllTime");
  return el ? el.value : "";
}

/** 時間欄的值轉成有效 ms；空白／無效用 1000，並鉗在 API 接受範圍。 */
function servoMoveTime() {
  const v = parseInt(servoTime(), 10);
  return isNaN(v) ? 1000 : Math.max(20, Math.min(32767, v));
}

/** 建立 servo grid 的共用邏輯, 抽出來做獨立 function 方便日後有第二個 grid 要建
 *  當時不用複製貼上一份幾乎一樣的 code。
 *  @param wrapId       外層容器的 id ("servoGroups")
 *  @param sliderPrefix slider input 的 id prefix ("servoSlider_")
 *  @param valPrefix    數值顯示 span 的 id prefix ("servoSliderVal_")
 *  @param sendFn       (id, angle, timeMs) => Promise, 實際送出去robot的call (servo/one)
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
  // 瀏覽器 reload 會還原表單 slider 值，部分瀏覽器會連帶發 input/change。
  // 如果照樣送，上一次 IK 留低嘅 20 軸 pose 就會喺開機時被當成新指令，
  // 機械人會「自己硬做返之前既動作」。只接受真正用家互動（pointer／touch／
  // key）之後嘅事件；程式設定 value（IK preview／reset）本身唔會發事件。
  let gridEngaged = false;
  function markGridEngaged() { gridEngaged = true; }

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

      // 每條 slider 自己一個 live queue：多手指同時拉兩條都唔會互相蓋位。
      let liveBusy = false;
      let livePending = null; // {angle, time, final}
      let liveLastAt = 0;
      let liveLastInputAt = 0;
      let liveTimer = null;

      function liveClamp() {
        const raw = parseInt(slider.value, 10);
        const clamped = clampServoAngle(id, isNaN(raw) ? cal.home : raw);
        if (clamped !== raw) slider.value = clamped;
        valueLabel.textContent = clamped;
        return clamped;
      }

      function liveStart(job) {
        liveBusy = true;
        liveLastAt = Date.now();
        let p;
        try {
          p = sendFn(id, job.angle, job.time);
        } catch (e) {
          p = Promise.reject(e);
        }
        Promise.resolve(p).then(function () {
          liveBusy = false;
          livePump();
        }, function () {
          liveBusy = false;
          livePump();
        });
      }

      function liveSchedule() {
        if (liveTimer) return;
        let wait = 0;
        if (livePending && !livePending.final) {
          wait = Math.max(0, SERVO_LIVE_INTERVAL_MS - (Date.now() - liveLastAt));
        }
        liveTimer = setTimeout(function () {
          liveTimer = null;
          livePump();
        }, wait);
      }

      function livePump() {
        if (liveBusy || !livePending) return;
        const job = livePending;
        livePending = null;
        if (!job.final) {
          const wait = SERVO_LIVE_INTERVAL_MS - (Date.now() - liveLastAt);
          if (wait > 0) {
            livePending = job;
            liveSchedule();
            return;
          }
        }
        liveStart(job);
      }

      function liveEnqueue(angle, time, final) {
        const now = Date.now();
        // 新一拖（停咗 250ms 再拉）或者放手 final：唔等節流，即刻送。
        if (final || now - liveLastInputAt > 250) liveLastAt = 0;
        liveLastInputAt = now;
        // final 一定贏；排緊 final 時，live 唔好蓋過佢。
        if (!final && livePending && livePending.final) return;
        livePending = { angle: angle, time: time, final: !!final };
        if (final && liveTimer) {
          clearTimeout(liveTimer);
          liveTimer = null;
        }
        if (liveBusy) {
          liveSchedule();
        } else {
          livePump();
        }
      }

      // 拖動時即時跟隨（節流），放手送最後準確值。
      // 只認用家互動後嘅事件；reload 還原表單值嘅 input/change 唔好送。
      slider.addEventListener("pointerdown", markGridEngaged);
      slider.addEventListener("touchstart", markGridEngaged, { passive: true });
      slider.addEventListener("mousedown", markGridEngaged);
      slider.addEventListener("keydown", markGridEngaged);
      slider.addEventListener("input", function (ev) {
        if (ev && ev.isTrusted && !gridEngaged) return;
        liveEnqueue(liveClamp(), SERVO_LIVE_TIME_MS, false);
      });
      slider.addEventListener("change", function (ev) {
        if (ev && ev.isTrusted && !gridEngaged) return;
        liveEnqueue(liveClamp(), servoMoveTime(), true);
      });

      groupEl.appendChild(row);
    });

    wrap.appendChild(groupEl);
  });
}

function buildServoGrid() {
  buildServoGridInto("servoGroups", "servoSlider_", "servoSliderVal_", function (id, angle, timeMs) {
    return Alpha2Api.servoOne({id: id, angle: angle, time: timeMs});
  }, "servoAngleVal_");
}

/** 一次過讀全部 22 軸即時角度（servo/back-angle-all：cmd 0x17 純讀，
 *  唔等唔寫回，約半秒），填入每行右邊讀數格。讀不到那顆顯示 -。 */
function servoReadAllAngles() {
  const rows = document.querySelectorAll("#tab-servo [onclick^='servoRead']");
  const status = document.getElementById("servoReadStatus");
  rows.forEach(function (b) { b.disabled = true; });
  for (let i = 1; i <= 22; i++) {
    const cell = document.getElementById("servoAngleVal_" + i);
    if (cell) cell.textContent = "…";
  }
  if (status) status.textContent = t("servo_reading_hint");
  return Alpha2Api.servoBackAngleAll().then(function (res) {
    rows.forEach(function (b) { b.disabled = false; });
    if (!res || !res.ok || !res.angles) {
      if (status) status.textContent = t("servo_read_failed_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const failed = res.failed || [];
    for (let i = 1; i <= 22; i++) {
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
function resetServoGrid() {
  const homes = [];
  for (let i = 1; i <= 22; i++) {
    const cal = SERVO_CALIBRATION[i];
    homes.push(cal.home);
    const slider = document.getElementById("servoSlider_" + i);
    const label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
    const advInp = document.getElementById("advServoVal_" + i);
    if (advInp) advInp.value = cal.home;
  }
  // IK 卡跟住歸零；記住 reset 後嘅 home pose，下次 IK 由 home 均速過渡。
  // IK 嘅 0% 仍然固定係開機企姿，唔跟舵機格嘅 home 走。
  if (typeof ikResetSlidersOnly === "function") ikResetSlidersOnly(homes);
  // 直接寫入原廠截圖角度（1:120 2:120 3:120 4:120 5:120 6:120 7:120 8:65 9:145 10:140 11:120 12:120 13:175 14:95 15:100 16:120 17:120 18:120 19:120 20:120 21:120 22:120），而非播放動作
  return Alpha2Api.servoAll({angles: homes.join(","), time: SERVO_RESET_TIME_MS});
}

function servoAll() {
  const angles = [];
  for (let i = 1; i <= 22; i++) {
    const slider = document.getElementById("servoSlider_" + i);
    const cal = SERVO_CALIBRATION[i];
    const raw = slider ? parseInt(slider.value, 10) : cal.home;
    const clamped = clampServoAngle(i, isNaN(raw) ? cal.home : raw);
    angles.push(clamped);
  }
  return Alpha2Api.servoAll( { angles: angles.join(","), time: servoMoveTime() });
}

// ---------------- Sonar (chest ultrasonic obstacle sensor) ----------------
//
// distance is assumed to be centimetres directly (unverified on real hardware - see
// the card's own hint text and Alpha2RobotApi#chest_configureSonar's javadoc).
// Slider drag updates the live cm readout only; the actual API call fires on release
// (onchange). Sonar stays release-only even though the servo sliders follow live.
function onSonarSliderInput(v) {
  document.getElementById("sonarDistVal").textContent = v + " cm";
}

function configureSonar(v) {
  const distance = v !== undefined ? v : document.getElementById("sonarDist").value;
  document.getElementById("sonarDistVal").textContent = distance + " cm";
  sonarThresholdCm = Number(distance);
  drawSonarChart();
  return Alpha2Api.servoSonar({distance: distance}).then(function (json) {
    if (!json || json.ok === false) return syncSonarUi();
    return json;
  }).catch(function () { return syncSonarUi(); });
}

// Toggle switch: off sends distance=0 (sonar disabled); on re-sends whatever the
// slider is currently set to, so flipping back on restores the last distance rather
// than requiring the person to re-drag the slider.
function toggleSonar() {
  const on = document.getElementById("sonarToggle").checked;
  const distance = on ? document.getElementById("sonarDist").value : "0";
  return Alpha2Api.servoSonar( { distance: distance }).then(function (json) {
    if (!json || json.ok === false) return syncSonarUi();
    return json;
  }).catch(function () { return syncSonarUi(); });
}

// 入頁同步：門檻讀返嚟擺 slider＋開關（0＝關；mapping toggle／別頁改過都會對返）。
function syncSonarUi() {
  return Alpha2Api.servoSonarGet().then(function (json) {
    if (!json || !json.ok) return json;
    let th = Number(json.thresholdCm);
    if (!isFinite(th)) return json;
    th = Math.max(0, Math.min(100, th));
    const slider = document.getElementById("sonarDist");
    const val = document.getElementById("sonarDistVal");
    const toggle = document.getElementById("sonarToggle");
    if (slider) slider.value = String(th);
    if (val) val.textContent = th + " cm";
    if (toggle) toggle.checked = th > 0;
    try { sonarThresholdCm = th; } catch (e) {}
    try { if (typeof drawSonarChart === "function") drawSonarChart(); } catch (e) {}
    return json;
  }).catch(function () { return null; });
}

// 真機已確認 cmd=72 開關生效，PIR 觸發正常。按 toggle 即送；結果看 API response ok/error，indicator 等 "alpha2_pir_state" WS event 先轉燈色。
function alpha2SetPir() {
  const on = document.getElementById("alpha2Pir").checked;
  return Alpha2Api.pirSet({on: on}).then(function (json) {
    if (!json || json.ok === false) return syncPirUi();
    return json;
  }).catch(function () { return syncPirUi(); });
}

// 獨立於 alpha2SetPir() 硬體開關 — 純開/關「偵測到人就閃紅燈/響鈴」警示反應，對應 MainActivity#setPirAlertEnabledAlpha2()。
function alpha2SetPirAlertEnabled() {
  const on = document.getElementById("alpha2PirAlertEnabled").checked;
  return Alpha2Api.pirAlertEnabled( { on: on }).then(function (json) {
    if (!json || json.ok === false) return syncPirUi();
    return json;
  }).catch(function () { return syncPirUi(); });
}

// 入頁同步：硬件開關自記態＋警示開關一次過擺返兩個掣（mapping toggle 改過都會對返）。
function syncPirUi() {
  // alpha2_10002 冇 PIR（卡已收埋），唔好再問 pir/get。
  if (window.alpha2Is2Mic) return Promise.resolve(null);
  return Alpha2Api.pirGet().then(function (json) {
    if (!json || !json.ok) return json;
    const hw = document.getElementById("alpha2Pir");
    const al = document.getElementById("alpha2PirAlertEnabled");
    if (hw) hw.checked = !!json.enabled;
    if (al) al.checked = !!json.alertEnabled;
    return json;
  }).catch(function () { return null; });
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
    {key:'left-leg', label:'左腳', labelEn:'L Leg', icon:'🦵', ids:[12,13,14,15,16]},
    {key:'extra', label:'擴展', labelEn:'Extra', icon:'🧩', ids:[21,22]}
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
  if (statusEl) statusEl.textContent = t("servo_tuner_ready", { v: "1021b" });
}
// 校准按鈕（官方 ServoCalibration 1.0.0.4 流程）：
//   一次過送全部有效 cmd12 trim → 後端等 EEPROM（約 7 秒）→ cmd05 全部回 home。
// 唔可以逐粒 servoOne(angle,trim)：官方係 trim 先、home 後；用調校角度再送
// 05 會令 offset 再加一次（double offset）。格內無數／未掃描 skip 並報告。
function advCalibrateAll() {
  const statusEl = document.getElementById("advTunerStatus");
  if (advScanInProgress) {
    if (statusEl) statusEl.textContent = t("servo_tuner_scanning_wait");
    return Promise.resolve();
  }
  const trims = new Array(22).fill("");
  const intended = {};
  const skipped = [];
  let n = 0;
  for (let i = 1; i <= 22; i++) {
    const el = document.getElementById("advServoOff_" + i);
    const v = el ? parseInt(el.textContent, 10) : NaN;
    if (isNaN(v) || !advTrimScanned[i]) { skipped.push(i); continue; }
    trims[i - 1] = String(v);
    intended[i] = v;
    n++;
  }
  if (n === 0) {
    if (statusEl) statusEl.textContent = t("servo_tuner_cal_none");
    return Promise.resolve();
  }
  if (statusEl) statusEl.textContent = t("servo_tuner_cal_writing", { n: n });
  return Alpha2Api.servoCalibrate({ trims: trims.join(","), time: 2000 }).then(function(json){
    if (!json || !json.ok) {
      if (statusEl) statusEl.textContent = t("servo_tuner_cal_home_fail", {
        e: (json && (json.error || json.code)) || "?"
      });
      return json;
    }
    // 官方流程完成後一定係 home 位；UI 同步返 home，等重掃唔會用舊角度/舊 offset。
    for (let i = 1; i <= 22; i++) {
      const cal = SERVO_CALIBRATION[i];
      const inp = document.getElementById("advServoVal_" + i);
      if (inp) inp.value = cal.home;
      advPrevAngle[i] = cal.home;
      const cell = document.getElementById("advServoOff_" + i);
      if (cell) cell.textContent = "…";
      delete advTrimScanned[i];
    }
    if (statusEl) statusEl.textContent = t("servo_tuner_cal_verify");
    // 等 home 動作行完（time=2000）再重掃，確認 EEPROM 真係生效。
    return new Promise(function(resolve) { setTimeout(resolve, 3000); }).then(function() {
      return advTunerReadAll();
    }).then(function(res) {
      if (!res || !res.trims) {
        if (statusEl) statusEl.textContent = t("servo_tuner_cal_verify_fail");
        return res;
      }
      let okCount = 0;
      const bad = [], unread = [];
      Object.keys(intended).forEach(function(k) {
        const i = Number(k);
        const got = res.trims[i - 1];
        if (got === null || got === undefined) unread.push(i);
        else if (Number(got) === intended[i]) okCount++;
        else bad.push(i);
      });
      let msg = t("servo_tuner_cal_verify_done", { ok: okCount, n: n });
      if (bad.length) msg += t("servo_tuner_cal_verify_bad", { ids: "#" + bad.join(",#") });
      if (unread.length) msg += t("servo_tuner_cal_verify_unread", { ids: "#" + unread.join(",#") });
      if (skipped.length) msg += t("servo_tuner_cal_skipped", { ids: "#" + skipped.join(",#") });
      if (statusEl) statusEl.textContent = msg;
      return res;
    });
  }).catch(function(e) {
    if (statusEl) statusEl.textContent = t("servo_tuner_cal_send_fail", { e: (e && e.message ? e.message : e) });
  });
}
let advPrevAngle = {};
// 本 session 掃描／還原過 trim 的 id：Enter 寫入只跟這些去存 EEPROM，
// 不會用新頁計出來的數覆蓋出廠 trim（官方工具連線即讀，永遠有 baseline）。
let advTrimScanned = {};
let advScanInProgress = false;
function advTunerToggle(){
  const en = document.getElementById("advTunerEnabled").checked;
  document.getElementById("advTunerBody").style.display = en ? "block" : "none";
  document.getElementById("advTunerDisabledHint").style.display = en ? "none" : "block";
  if (en) {
    buildAdvTuner();
    // 官方工具一連線就讀全部 trim 做 baseline。呢度開 tuner 即自動掃，
    // 避免用戶直接 +/- 之後校準，因為未掃描而被 skip 或失去出廠 baseline。
    advTunerReadAll();
  }
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
  const statusEl0 = document.getElementById("advTunerStatus");
  if (advScanInProgress) {
    if (statusEl0) statusEl0.textContent = t("servo_tuner_scanning_wait");
    return Promise.resolve();
  }
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
  if (advScanInProgress) return Promise.resolve(null);
  advScanInProgress = true;
  statusEl.textContent = t("servo_tuner_read_all");
  function finish(res) {
    advScanInProgress = false;
    return res;
  }
  return Alpha2Api.servoReadAll().then(function(json){
    if (!json.ok || !json.live || !json.trims || json.trims.length !== 22) {
      statusEl.textContent = t("servo_tuner_read_fail", { id: "1-22" }) + advErrSuffix(json.error);
      return finish(json);
    }
    let okCount = 0;
    for (let i = 1; i <= 22; i++) {
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
    return finish(json);
  }).catch(function(e){
    statusEl.textContent = t("servo_tuner_read_err", { e: e.message });
    return finish(null);
  });
}
function advTunerReset() {
  // 復位 = 成組回home，動完自動重新掃描 trim（chest 存值不受動角度影響）。格設「…」等掃，不填計出來的數；兼清 scanned 旗 — 填 0 會寫入 EEPROM 覆蓋掉出廠 trim。
  if (advScanInProgress) {
    const st = document.getElementById("advTunerStatus");
    if (st) st.textContent = t("servo_tuner_scanning_wait");
    return Promise.resolve();
  }
  const homes = [];
  for (let i = 1; i <= 22; i++) {
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
  // 同官方 reset 一樣用 2000ms 行去標準姿勢；行完先重掃，避免讀到移動中舊值。
  return Alpha2Api.servoAll( { angles: homes.join(","), time: 2000 }).then(function(json){
    if (!json.ok) {
      document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_fail", { e: (json.error || "") });
      return json;
    }
    document.getElementById("advTunerStatus").textContent = t("servo_tuner_reset_done");
    return new Promise(function(resolve) {
      setTimeout(function() { resolve(advTunerReadAll()); }, 3000);
    });
  });
}
function _advPerformBackupNow() {
  // 僅備份 offsets（角度格是即時目標，寫下 file 無用）。讀失敗記 null；有效性經 advTrimScanned 判。
  const offsets = {};
  for (let i = 1; i <= 22; i++) {
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
  for (let i = 1; i <= 22; i++) {
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
      for (let i = 1; i <= 22; i++) {
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
    if (statusEl) status.textContent = t("servo_tuner_restore_fail") + t("servo_tuner_restore_readerr");
    try { input.value = ""; } catch(e) {}
  };
  reader.readAsText(file);
}

// ---------------- Change servo ID card (tab-advanced) ----------------
// Chest cmd 10 MODIFY_ID [old][new] (servo code 205), servo EEPROM persistent.
// Same collapsible pattern as uuidCardToggle/chestToggle: default collapsed,
// no localStorage, reopened collapsed on every page load.
function servoIdCardToggle() {
  const enabled = document.getElementById("servoIdCardEnabled");
  const body = document.getElementById("servoIdCardBody");
  const hint = document.getElementById("servoIdCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) servoIdBusUpdate();
}

// Chest servo bus sides (user-confirmed wiring: wrong side = unrecognised).
// Right port: head 19,20 / right arm 1,2,3,17 / right leg 7-11 / 21.
// Left port: left arm 4,5,6,18 / left leg 12-16 / 22.
const SERVO_BUS_RIGHT = [1, 2, 3, 7, 8, 9, 10, 11, 17, 19, 20, 21];
const SERVO_BUS_LEFT = [4, 5, 6, 12, 13, 14, 15, 16, 18, 22];
function servoBusSideOf(id) {
  if (SERVO_BUS_RIGHT.indexOf(id) !== -1) return "right";
  if (SERVO_BUS_LEFT.indexOf(id) !== -1) return "left";
  return "";
}
function servoBusSideName(side) {
  if (side === "right") return t("servo_id_bus_right");
  if (side === "left") return t("servo_id_bus_left");
  return "?";
}
function servoIdBusUpdate() {
  const info = document.getElementById("servoIdBusInfo");
  if (!info) return;
  const id = parseInt((document.getElementById("servoIdOld") || {}).value, 10);
  const newId = parseInt((document.getElementById("servoIdNew") || {}).value, 10);
  const sOld = servoBusSideOf(id), sNew = servoBusSideOf(newId);
  if (!sOld || !sNew) { info.textContent = ""; return; }
  let msg = t("servo_id_bus_info", { sideOld: servoBusSideName(sOld), sideNew: servoBusSideName(sNew) });
  if (sOld !== sNew) msg += " \u2014 " + t("servo_id_bus_cross", { sideNew: servoBusSideName(sNew) });
  info.textContent = msg;
}

function servoModifyId() {
  const oldEl = document.getElementById("servoIdOld");
  const newEl = document.getElementById("servoIdNew");
  const status = document.getElementById("servoIdStatus");
  const id = oldEl ? parseInt(oldEl.value, 10) : NaN;
  const newId = newEl ? parseInt(newEl.value, 10) : NaN;
  if (isNaN(id) || isNaN(newId) || id < 1 || id > 22 || newId < 1 || newId > 22) {
    if (status) status.textContent = t("servo_id_range_error");
    return Promise.resolve();
  }
  if (id === newId) {
    if (status) status.textContent = t("servo_id_same_error");
    return Promise.resolve();
  }
  const sOld = servoBusSideOf(id), sNew = servoBusSideOf(newId);
  let confirmMsg = t("servo_id_confirm", { id: id, newId: newId });
  if (sOld && sNew && sOld !== sNew) confirmMsg += "\n" + t("servo_id_bus_cross", { sideNew: servoBusSideName(sNew) });
  if (!confirm(confirmMsg)) return Promise.resolve();
  if (status) status.textContent = t("servo_id_sending", { id: id, newId: newId });
  return Alpha2Api.servoModifyId({ id: id, newId: newId }).then(function (json) {
    if (json && json.ok) {
      if (status) status.textContent = t("servo_id_done", { id: id, newId: newId });
    } else {
      if (status) status.textContent = t("servo_id_fail_prefix") + ((json && (json.error || JSON.stringify(json))) || "?");
    }
    return json;
  }).catch(function (e) {
    if (status) status.textContent = t("servo_id_fail_prefix") + (e && e.message ? e.message : e);
  });
}

// ---------------- Servo firmware version card (tab-advanced) ----------------
// Chest cmd 14 READ_MOTOR_VERSION, pure read. Reply layout UNVERIFIED:
// rows show parsed version (may be missing) plus raw hex (authoritative).
// Same collapsible pattern as servoIdCardToggle: default collapsed.
function servoFwCardToggle() {
  const enabled = document.getElementById("servoFwCardEnabled");
  const body = document.getElementById("servoFwCardBody");
  const hint = document.getElementById("servoFwCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) { servoFwBuildGrid(); servoUpFileInfo(); }
}

// Servo torque groups (user-confirmed hardware fact:
// 8kg = [2,3,5,6,17,18,19,20], 20kg = [1,4,7-16], 21/22 = extra/unknown).
// Colours: 8kg blue, 20kg orange, unknown default grey.
const SERVO_TORQUE_8KG = [2, 3, 5, 6, 17, 18, 19, 20];
const SERVO_TORQUE_20KG = [1, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
function servoTorqueOf(id) {
  if (SERVO_TORQUE_8KG.indexOf(id) !== -1) return "8kg";
  if (SERVO_TORQUE_20KG.indexOf(id) !== -1) return "20kg";
  return "";
}
function servoTorqueColor(id) {
  const m = servoTorqueOf(id);
  if (m === "8kg") return "#2563eb";
  if (m === "20kg") return "#d97706";
  return "#6b7280";
}

function servoFwBuildGrid() {
  const grid = document.getElementById("servoFwGrid");
  if (!grid) return;
  if (grid.children.length === 22) return;
  grid.innerHTML = "";
  for (let i = 1; i <= 22; i++) {
    const cell = document.createElement("div");
    cell.style.cssText = "border:1px solid " + servoTorqueColor(i) + ";border-radius:8px;padding:8px;background:" + servoTorqueColor(i) + ";color:#fff";
    cell.innerHTML = '<div style="display:flex;justify-content:space-between;align-items:baseline;gap:12px;font-weight:600;font-size:13px;color:#fff"><span>#' + i + '</span><span id="servoFwVer_' + i + '"></span></div>';
    grid.appendChild(cell);
  }
}

function servoFwSetRow(i, ver) {
  const vEl = document.getElementById("servoFwVer_" + i);
  if (vEl) vEl.textContent = (ver === null || ver === undefined || ver === "") ? t("servo_fw_unreadable") : ver;
}

function servoFwReadAll() {
  const status = document.getElementById("servoFwStatus");
  servoFwBuildGrid();
  for (let i = 1; i <= 22; i++) {
    const vEl = document.getElementById("servoFwVer_" + i);
    if (vEl) vEl.textContent = "…";
  }
  if (status) status.textContent = t("servo_fw_reading");
  return Alpha2Api.servoVersionAll().then(function (res) {
    if (!res || !res.ok || !res.versions) {
      if (status) status.textContent = t("servo_fw_fail_prefix") + (res && res.error ? res.error : "?");
      return res;
    }
    const failed = res.failed || [];
    for (let i = 1; i <= 22; i++) {
      servoFwSetRow(i, res.versions[i - 1]);
    }
    if (status) {
      status.textContent = failed.length
        ? t("servo_fw_done") + (uiLang === "en"
            ? " (unreadable: " + failed.join(",") + ")"
            : "（無法讀取：" + failed.join(",") + "）")
        : t("servo_fw_done");
    }
    return res;
  }).catch(function (e) {
    if (status) status.textContent = t("servo_fw_fail_prefix") + (e && e.message ? e.message : e);
  });
}

// ---------------- Servo firmware upgrade card (tab-advanced) ----------------
// Flow: pick 7168B .bin -> upload to /upload/servo-fw -> double confirm ->
// servo/upgrade?id= -> poll servo/upgrade/status. Same collapsible pattern,
// default collapsed. File gate (exactly 7168B) enforced client AND server.

function servoUpFileInfo() {
  const status = document.getElementById("servoUpFileStatus");
  return Alpha2Api.servoUpgradeStatus().then(function (s) {
    if (!s || s.ok === false) return false;
    if (!s.fileExists) {
      if (status) status.textContent = t("servo_up_file_missing");
      return false;
    }
    if (Number(s.fileSizeBytes) !== 7168) {
      if (status) status.textContent = t("servo_up_file_bad", { n: s.fileSizeBytes });
      return false;
    }
    if (status) status.textContent = t("servo_up_file_ok", { n: s.fileSizeBytes });
    return true;
  }).catch(function () { return false; });
}

function servoUpUpload() {
  const input = document.getElementById("servoUpFile");
  const status = document.getElementById("servoUpFileStatus");
  if (!input || !input.files || input.files.length === 0) {
    if (status) status.textContent = t("servo_up_no_file");
    return;
  }
  const file = input.files[0];
  if (file.size !== 7168) {
    if (status) status.textContent = t("servo_up_bad_size", { n: file.size });
    return;
  }
  if (status) status.textContent = t("servo_up_uploading");
  const reader = new FileReader();
  reader.onload = function (e) {
    const bytes = e.target.result;
    let upHeaders = {};
    try {
      if (typeof panelAuthHeaders === "function") upHeaders = panelAuthHeaders();
    } catch (ign) {}
    fetch("/upload/servo-fw?name=" + encodeURIComponent(file.name), {
      method: "POST",
      body: bytes,
      headers: Object.assign({ "Content-Type": "application/octet-stream" }, upHeaders)
    }).then(function (res) { return res.json(); }).then(function (json) {
      if (json.ok) {
        servoUpFileInfo();
      } else {
        if (status) status.textContent = t("servo_up_fail_prefix") + (json.error || JSON.stringify(json));
      }
    }).catch(function (err) {
      if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
    });
  };
  reader.onerror = function () { if (status) status.textContent = t("servo_up_fail_prefix") + "read file"; };
  reader.readAsArrayBuffer(file);
}

function servoUpStart() {
  const status = document.getElementById("servoUpStatus");
  const idEl = document.getElementById("servoUpId");
  const id = idEl ? parseInt(idEl.value, 10) : NaN;
  if (isNaN(id) || id < 1 || id > 22) {
    if (status) status.textContent = t("servo_id_range_error");
    return Promise.resolve();
  }
  servoUpFileInfo().then(function (ok) {
    if (!ok) return;
    if (!confirm(t("servo_up_confirm1", { id: id }))) return;
    if (!confirm(t("servo_up_confirm2", { id: id }))) return;
    if (status) status.textContent = t("servo_up_started", { id: id });
    const wrap = document.getElementById("servoUpProgressWrap");
    const bar = document.getElementById("servoUpProgressBar");
    const txt = document.getElementById("servoUpProgressText");
    if (wrap) wrap.style.display = "block";
    if (bar) bar.style.width = "0%";
    if (txt) txt.textContent = "0%";
    Alpha2Api.servoUpgrade({ id: id }).then(function (json) {
      if (!json.ok) {
        if (status) status.textContent = t("servo_up_fail_prefix") + (json.error || "start failed");
        return;
      }
      const timer = setInterval(function () {
        Alpha2Api.servoUpgradeStatus().then(function (s) {
          const p = s.progress || 0;
          if (bar) bar.style.width = p + "%";
          if (txt) txt.textContent = p + "% (" + (s.currentPage || 0) + "/" + (s.totalPages || 0) + ") " + (s.status || "");
          if (!s.inProgress) {
            clearInterval(timer);
            if (s.status && s.status.indexOf("success") !== -1) {
              if (status) status.textContent = t("servo_up_done_ok", { id: id });
            } else {
              if (status) status.textContent = t("servo_up_fail_prefix") + (s.status || "");
            }
          }
        }).catch(function () {});
      }, 500);
    }).catch(function (err) {
      if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
    });
  });
}

function servoUpAbort() {
  const status = document.getElementById("servoUpStatus");
  return Alpha2Api.servoUpgradeAbort().then(function (json) {
    if (status) status.textContent = t("servo_up_abort_btn") + (json.ok ? " OK" : ": " + (json.error || ""));
    return json;
  }).catch(function (err) {
    if (status) status.textContent = t("servo_up_fail_prefix") + err.message;
  });
}
