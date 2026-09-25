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

// ---------------- 逐粒試燈 ----------------
// bit 表（實機試位確認，見 LedCenter 註解）：
// 頭左 P4 正序 [1,2,4,8,16]，頭右 P3 反序 [16,8,4,2,1]，第 5 粒係 wifi 位；
// 眼每邊 8 粒 ring，順時針由 12 點起 [16,8,4,2,1,128,64,32]。
// 硬件 write-only 讀唔返——顯示靠 /api/led/state/get（server 記低最後一轉），
// 撳掣送 led/head/raw 或 led/eye/raw，用上面揀嘅色＋光度。
const PERLED_HEAD_L_BITS = [1, 2, 4, 8, 16];
const PERLED_HEAD_R_BITS = [16, 8, 4, 2, 1];
const PERLED_EYE_BITS = [16, 8, 4, 2, 1, 128, 64, 32];
let perledHead = { p3: 0, p4: 0, color: 0 };
let perledEye = { p3: 0, p4: 0, color: 0 };
let perledBuilt = false;
let perledPollTimer = null;

function perledColorHex(code) {
  for (let i = 0; i < LED_COLORS.length; i++) {
    if (LED_COLORS[i].code === code) return LED_COLORS[i].hex;
  }
  return "#9aa0a6";
}

function perledMakeDot(num, wifi) {
  const dot = document.createElement("button");
  dot.type = "button";
  dot.className = "perled-dot" + (wifi ? " perled-wifi" : "");
  dot.textContent = num;
  dot.title = wifi ? "#5 wifi 位（熄未必熄到）" : "#" + num;
  return dot;
}

function perledBuild() {
  if (perledBuilt) return;
  perledBuilt = true;
  // 頭：左右各一條 5 粒直排，上面係 5 號、下面係 1 號（似 VU 錶由下數起）。
  const strips = [["perledHeadL", "p4", PERLED_HEAD_L_BITS], ["perledHeadR", "p3", PERLED_HEAD_R_BITS]];
  strips.forEach(function (cfg) {
    const wrap = document.getElementById(cfg[0]);
    if (!wrap) return;
    wrap.innerHTML = "";
    for (let i = 4; i >= 0; i--) {
      const dot = perledMakeDot(i + 1, i === 4);
      (function (mask, bit) {
        dot.onclick = function () { perledToggle("head", mask, bit); };
      })(cfg[1], cfg[2][i]);
      wrap.appendChild(dot);
    }
  });
  // 眼：每邊 8 粒圍圈，1 號喺 12 點，順時針。
  const rings = [["perledEyeL", "p3"], ["perledEyeR", "p4"]];
  rings.forEach(function (cfg) {
    const wrap = document.getElementById(cfg[0]);
    if (!wrap) return;
    wrap.innerHTML = "";
    for (let i = 0; i < 8; i++) {
      const dot = perledMakeDot(i + 1, false);
      const ang = (-90 + i * 45) * Math.PI / 180;
      dot.style.left = String(Math.round(54 + 44 * Math.cos(ang))) + "px";
      dot.style.top = String(Math.round(54 + 44 * Math.sin(ang))) + "px";
      (function (mask, bit) {
        dot.onclick = function () { perledToggle("eye", mask, bit); };
      })(cfg[1], PERLED_EYE_BITS[i]);
      wrap.appendChild(dot);
    }
  });
}

function perledRender() {
  function paint(wrapId, bits, mask, hex) {
    const wrap = document.getElementById(wrapId);
    if (!wrap) return;
    const dots = wrap.querySelectorAll(".perled-dot");
    // 頭 strip 由 5 號畫到 1 號（同 build 順序一致），眼 ring 由 1 號順時針。
    const order = wrapId.indexOf("Head") >= 0 ? [4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];
    order.forEach(function (bi, di) {
      const dot = dots[di];
      if (!dot) return;
      const on = (mask & bits[bi]) !== 0;
      dot.classList.toggle("perled-on", on);
      dot.style.background = on ? hex : "";
    });
  }
  paint("perledHeadL", PERLED_HEAD_L_BITS, perledHead.p4, perledColorHex(perledHead.color));
  paint("perledHeadR", PERLED_HEAD_R_BITS, perledHead.p3, perledColorHex(perledHead.color));
  paint("perledEyeL", PERLED_EYE_BITS, perledEye.p3, perledColorHex(perledEye.color));
  paint("perledEyeR", PERLED_EYE_BITS, perledEye.p4, perledColorHex(perledEye.color));
}

function perledToggle(zone, mask, bit) {
  if (zone === "head") {
    perledHead[mask] = (perledHead[mask] ^ bit) & 31;
    perledRender();
    const bEl = document.getElementById("headBrightness");
    Alpha2Api.ledHeadRaw({ color: String(selectedHeadColor),
      brightness: String(bEl ? bEl.value : 9),
      p3: String(perledHead.p3), p4: String(perledHead.p4) }).then(function () { perledPollOnce(); });
  } else {
    perledEye[mask] = (perledEye[mask] ^ bit) & 255;
    perledRender();
    const bEl = document.getElementById("eyeBrightness");
    Alpha2Api.ledEyeRaw({ color: String(selectedEyeColor),
      brightness: String(bEl ? bEl.value : 9),
      p3: String(perledEye.p3), p4: String(perledEye.p4) }).then(function () { perledPollOnce(); });
  }
}

function perledAllOn() {
  perledHead = { p3: 31, p4: 31, color: selectedHeadColor };
  perledEye = { p3: 255, p4: 255, color: selectedEyeColor };
  perledRender();
  const hb = document.getElementById("headBrightness");
  const eb = document.getElementById("eyeBrightness");
  Alpha2Api.ledHeadRaw({ color: String(selectedHeadColor), brightness: String(hb ? hb.value : 9),
    p3: "31", p4: "31" });
  Alpha2Api.ledEyeRaw({ color: String(selectedEyeColor), brightness: String(eb ? eb.value : 9),
    p3: "255", p4: "255" }).then(function () { perledPollOnce(); });
}

function perledAllOff() {
  perledHead = { p3: 0, p4: 0, color: 0 };
  perledEye = { p3: 0, p4: 0, color: 0 };
  perledRender();
  // 經 preset stop 送（同 LED tab 個 ⏹ 掣同一條路，連 wifi 燈一齊清）。
  Alpha2Api.ledHeadSet({ preset: "stop" });
  Alpha2Api.ledEyeSet({ preset: "stop" }).then(function () { perledPollOnce(); });
}

// 即時 mirror：1 秒 poll 一次 server 最後燈態（淨係 LED tab 開緊嗰陣先打）。
function perledPollOnce() {
  return Alpha2Api.ledStateGet().then(function (res) {
    if (!res || !res.ok) return res;
    if (res.head) perledHead = { p3: res.head.p3 || 0, p4: res.head.p4 || 0, color: res.head.color || 0 };
    if (res.eye) perledEye = { p3: res.eye.p3 || 0, p4: res.eye.p4 || 0, color: res.eye.color || 0 };
    perledRender();
    return res;
  });
}

function perledPollLoop() {
  if (perledPollTimer) clearTimeout(perledPollTimer);
  perledPollTimer = null;
  function tick() {
    perledPollTimer = null;
    const tab = document.getElementById("tab-led");
    if (tab && tab.classList.contains("active")) {
      perledPollOnce().then(schedule, schedule);
    } else {
      schedule();
    }
  }
  function schedule() {
    perledPollTimer = setTimeout(tick, 1000);
  }
  schedule();
}

