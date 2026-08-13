// Open Alpha2 — client logic (app-led.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 頭/眼/咀 LED 顏色揀選、preset (共用 ledPresetApply())。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。


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
  // 2026-08 修正: 之前呢度冇 null check, 一旦 wrapId 打錯或者 index.html 個
  // 對應 element 被誤刪, document.getElementById() 會返 null,
  // wrap.innerHTML 即刻 TypeError —— 而呢個 function 四個 call site
  // (buildHeadColorPicker/buildEyeColorPicker/lynxBuildEyeColorPicker/
  // lynxBuildHeadColorPicker) 全部都喺頁面初始化 (DOMContentLoaded 果條 call
  // chain) 連續執行, 其中一個掉低就會拋出未捕獲例外, 中斷埋後面幾行初始化
  // (見 initializePanel() 附近), 令成個 panel 睇落完全打唔開, 卻冇任何錯誤
  // 提示喺 UI 度 (window.onerror 會 log 落 console/logcat, 但畫面本身一片空白)。
  // 加返 guard: 揾唔到就靜靜哋跳過呢一個 color picker, 唔阻住其他初始化步驟。
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
 *  其他 preset 夾埋 color+brightness), 淨係 api path/顏色/亮度嚟源唔同。
 *  @param apiPath    "led/head/set" / "led/eye/set"
 *  @param preset     要送嘅 preset 字串
 *  @param color      當前揀咗嘅顏色 code
 *  @param brightnessElId 亮度滑桿嘅 id
 */
function ledPresetApply(apiPath, preset, color, brightnessElId) {
  if (preset === "stop") {
    return api(apiPath, { preset: "stop" });
  }
  const brightness = document.getElementById(brightnessElId).value;
  return api(apiPath, { preset: preset, color: color, brightness: brightness });
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

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "咀部 LED" section for what was tried and ruled out).
function mouthLedApply() {
  const speed = document.getElementById("mouthSpeed").value;
  return api("led/mouth/set", { speed: speed }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}

function mouthLedOff() {
  return api("led/mouth/set", { preset: "off" }).then(function (json) {
    document.getElementById("mouthLedResult").textContent =
      json.ok ? "ok=true (off)" : "ok=false" + (json.error ? " (" + json.error + ")" : "");
    return json;
  });
}
