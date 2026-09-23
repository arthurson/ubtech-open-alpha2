// Open Alpha2 — client logic (app-led.js)
// 內容: 頭/眼/咀 LED 顏色揀選、preset (共用 ledPresetApply())。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。


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
  // 找不到 element 就跳過這個 picker，不阻其他初始化。
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

/** headLedPreset()/eyeLedPreset() 共用邏輯 - 兩者結構完全一樣 (stop 直接送、
 *  其他 preset 組合 color+brightness), 僅 api path/顏色/亮度來源不同。
 *  @param apiPath    "led/head/set" / "led/eye/set"
 *  @param preset     要送的 preset 字串
 *  @param color      當前選了的顏色 code
 *  @param brightnessElId 亮度滑桿的 id
 */
function ledPresetApply(apiPath, preset, color, brightnessElId) {
  const brightness = document.getElementById(brightnessElId).value;
  // 對應 openapi /api/led/head/set /api/led/eye/set — apiPath 動態故用條件分流
  if (preset === "stop") {
    return apiPath === "led/head/set" ? Alpha2Api.ledHeadSet({preset: "stop"}) : Alpha2Api.ledEyeSet({preset: "stop"});
  }
  return apiPath === "led/head/set" ? Alpha2Api.ledHeadSet({preset: preset, color: color, brightness: brightness}) : Alpha2Api.ledEyeSet({preset: preset, color: color, brightness: brightness});
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
  return ledPresetApply("led/head/set", preset, selectedHeadColor, "headBrightness");
}

function eyeLedPreset(preset) {
  lastEyePreset = preset;
  return ledPresetApply("led/eye/set", preset, selectedEyeColor, "eyeBrightness");
}

// 眼部「3秒倒數」：雙眼齊漸滿 3 圈（綠→黃→紅，每圈約 1 秒）→ 白燈 3 秒 → 熄。
// 經 debug/jni/led 直透 raw（p3/p4 正式 endpoint 無呢啲參數）。
// 連撳唔重入，行緊嗰陣再撳唔理。
let eyeCountdownRunning = false;
function eyeCountdown3s() {
  if (eyeCountdownRunning) return Promise.resolve();
  eyeCountdownRunning = true;
  const masks = [16, 24, 28, 30, 31, 159, 223, 255];
  const colors = ["2", "4", "1"]; // 綠 黃 紅
  const MAX = "2147483647"; // long 常亮同 ledEyeSet 一致
  function sleep(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  function step(color, mask) {
    return Alpha2Api.debugJniLed({ func: "eye", a1: color, a2: "9",
      a3: String(mask), a4: String(mask), a5: MAX, a6: "0", a7: MAX, a8: "0" });
  }
  let p = Promise.resolve();
  colors.forEach(function (color) {
    masks.forEach(function (mask) {
      p = p.then(function () {
        const t0 = Date.now();
        return step(color, mask).then(function () {
          const dt = Date.now() - t0;
          if (dt < 125) return sleep(125 - dt); // 每格約 125ms，一圈約 1 秒
        });
      });
    });
  });
  function done() { eyeCountdownRunning = false; }
  return p.then(function () {
    return step("7", 255); // 白燈全開
  }).then(function () {
    return sleep(3000);
  }).then(function () {
    return Alpha2Api.ledEyeSet({ preset: "stop" });
  }).then(done, done);
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "嘴部 LED" section for what was tried and ruled out).
function mouthLedApply() {
  const speed = document.getElementById("mouthSpeed").value;
  return Alpha2Api.ledMouthSet( { speed: speed }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}

function mouthLedOff() {
  return Alpha2Api.ledMouthSet( { preset: "off" }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true (off)" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}

