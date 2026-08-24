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
    api("servo/one", { id: id, angle: angle, time: servoTime() });
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
  for (let i = 1; i <= 20; i++) {
    const cal = SERVO_CALIBRATION[i];
    const slider = document.getElementById("servoSlider_" + i);
    const label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = cal.home;
    if (label) label.textContent = cal.home;
  }
  return api("action/play", { name: SERVO_RESET_ACTION_ID });
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

// 2026-08-15 更新: 真機已確認 cmd=72 開關生效, PIR 觸發正常 (見
// RobotEventReceiver/MainActivity 嘅 comment)。撳 toggle 就送出去, 冇 optimistic
// UI 假設一定成功 - 結果淨係睇 API response 嘅 ok/error, alpha2PirIndicator 本身要等
// "alpha2_pir_state" WebSocket event 先會轉燈色 (見 app-log.js/onAlpha2PirState())。
function alpha2SetPir() {
  const on = document.getElementById("alpha2Pir").checked;
  return api("pir/set", { on: on });
}

// 2026-08-15 新增: 獨立於 alpha2SetPir() 感應器硬件開關本身 - 純粹開/關「偵測到人
// 就閃紅燈/響鈴」呢個警示反應, 對應 MainActivity#setPirAlertEnabledAlpha2()。
function alpha2SetPirAlertEnabled() {
  const on = document.getElementById("alpha2PirAlertEnabled").checked;
  return api("pir/alert_enabled", { on: on });
}
