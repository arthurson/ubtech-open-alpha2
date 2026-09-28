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
// 雙色模式三色選擇（實機 dual 出呢三組）：藍綠＝p1 藍，紅綠＝p1 黃，紅藍＝p1 紫。
// 點解唔直接 7 色：其他 p1 出嚟都係藍綠，無得分，唔擺出嚟阻位。
const DUAL_COLORS = [
  { code: 3, name: "藍綠", hex: "linear-gradient(135deg, #3b6bff 50%, #3bff5c 50%)" },
  { code: 4, name: "紅綠", hex: "linear-gradient(135deg, #ff3b3b 50%, #3bff5c 50%)" },
  { code: 5, name: "紅藍", hex: "linear-gradient(135deg, #ff3b3b 50%, #3b6bff 50%)" },
];
let prevHeadColor = null; // 入 dual 之前揀緊咩色，出返嚟還原
let prevEyeColor = null;
function dualValid(code) { return code === 3 || code === 4 || code === 5; }

function buildColorPicker(wrapId, getSelected, setSelected, onPick, palette) {
  // 找不到 element 就跳過這個 picker，不阻其他初始化。
  const wrap = document.getElementById(wrapId);
  if (!wrap) {
    console.error("buildColorPicker: element #" + wrapId + " not found, skipping");
    return;
  }
  const colors = palette || LED_COLORS;
  wrap.innerHTML = "";
  colors.forEach(function (c) {
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
    headLedApply,
    perledHeadMode === "dual" ? DUAL_COLORS : LED_COLORS);
}

function buildEyeColorPicker() {
  buildColorPicker("eyeColorPicker",
    function () { return selectedEyeColor; },
    function (code) { selectedEyeColor = code; },
    eyeLedApply,
    perledEyeMode === "dual" ? DUAL_COLORS : LED_COLORS);
}

/** headLedPreset()/eyeLedPreset() 共用邏輯 - 兩者結構完全一樣 (stop 直接送、
 *  其他 preset 組合 color+brightness)，僅 api path/顏色/亮度來源不同。
 *  preset 會帶埋而家 mirror 緊嘅 mask（逐粒撳剩一半嗰陣，閃／跑馬等淨係郁嗰一半；
 *  未 sync 過／server 未打過（-1）就唔帶，server 照舊全開）。
 *  @param apiPath    "led/head/set" / "led/eye/set"
 *  @param preset     要送的 preset 字串
 *  @param color      當前選了的顏色 code
 *  @param brightnessElId 亮度滑桿的 id
 *  @param curMasks   {p3, p4} 而家著緊嘅 mask（逐粒 mirror state）
 */
function ledPresetApply(apiPath, preset, color, brightnessElId, curMasks, speedElId) {
  const brightness = document.getElementById(brightnessElId).value;
  const full = apiPath === "led/head/set" ? 31 : 255;
  const zone = apiPath === "led/head/set" ? "head" : "eye";
  let p;
  // 對應 openapi /api/led/head/set /api/led/eye/set — apiPath 動態故用條件分流
  if (preset === "stop") {
    p = apiPath === "led/head/set" ? Alpha2Api.ledHeadSet({preset: "stop"}) : Alpha2Api.ledEyeSet({preset: "stop"});
    perledNoteSent(apiPath, 0, 0, 0, 0);
  } else {
    const params = {preset: preset, color: color, brightness: brightness};
    const speedEl = speedElId ? document.getElementById(speedElId) : null;
    if (speedEl) params.speed = String(Math.max(50, Math.min(500, parseInt(speedEl.value, 10) || 100)));
    // chase 例外：硬件唔理 mask 照全掃（熄哂都掃），所以唔帶 mask＝全開，
    // mirror 先對得上。其他 preset：淨係 mirror 有嘢著緊先帶；
    // 全熄（stop 完）嗰陣撳即全開，唔帶。
    if (preset !== "chase" && curMasks) {
      const pm = perledPartialMasks(zone, curMasks);
      if (pm) {
        params.p3 = String(pm.p3);
        params.p4 = String(pm.p4);
        perledNoteSent(apiPath, pm.p3, pm.p4, Number(color), Number(brightness));
      } else {
        // 人哋台／未知：server 會用全 mask，記低佢（連色＋光度一齊記，
        // disco 眼同 preset 一樣全開，淨係色唔同——淨對 mask 對唔到眼！）。
        perledNoteSent(apiPath, full, full, Number(color), Number(brightness));
      }
    } else {
      // chase：硬件唔理 mask 照全掃，唔帶 mask＝全開，mirror 先對得上。
      perledNoteSent(apiPath, full, full, Number(color), Number(brightness));
    }
    p = apiPath === "led/head/set" ? Alpha2Api.ledHeadSet(params) : Alpha2Api.ledEyeSet(params);
  }
  return p;
}

// 記低今次送出去嘅 mask（樂觀值，同本地 paint 一致）：下次 poll 發現
// server 唔係呢組（disco／其他嘢郁過），動畫還原靜態、照 mask 直出。
// stop 嗰陣記 0,0。未送過＝null（唔檢查）。
// ours 旗：true＝而家顯示緊我哋自己嘅嘢（mask 可信，可以跟住改）；
// false＝人哋台（disco 等）／未知——preset／toggle 一律由全開起手，
// 唔好攞人哋個 pattern 當自己嘢改（之前眼就係咁中招：disco mask 當咗自己嘅）。
let perledHeadSent = null;
let perledEyeSent = null;
let perledHeadOurs = false;
let perledEyeOurs = false;
function perledNoteSent(apiPath, p3, p4, color, brightness) {
  const rec = { p3: p3, p4: p4, color: color | 0, brightness: brightness | 0 };
  // 新 intent：還原記憶＋計數清零＋認領（由呢組 mask 起重新對）。
  if (apiPath === "led/head/set") {
    perledHeadSent = rec;
    perledHeadMismatch = 0;
    perledHeadDivergedFrom = null;
    perledHeadOurs = true;
  } else {
    perledEyeSent = rec;
    perledEyeMismatch = 0;
    perledEyeDivergedFrom = null;
    perledEyeOurs = true;
  }
}

// 統一出 mask 規矩（preset 樂觀＋實際送行同一套，唔好兩邊各寫一套走散）：
// 回 {p3,p4} 用局部 ／ null 用全開（唔帶）。
// chase 永遠全開（硬件照掃）；唔係自己台／未知一律全開；
// 自己台＋知＋全熄（逐粒熄晒）就照送 0（唔好變返全開！）。
function perledPartialMasks(zone, st) {
  const ours = zone === "head" ? perledHeadOurs : perledEyeOurs;
  if (!ours) return null;
  if (!(st.p3 >= 0 && st.p4 >= 0)) return null;
  if ((st.p3 | st.p4) === 0) return { p3: 0, p4: 0 };
  const max = zone === "head" ? 31 : 255;
  return { p3: Math.min(max, Math.max(0, st.p3 | 0)),
           p4: Math.min(max, Math.max(0, st.p4 | 0)) };
}

// Re-sends whatever preset was last active, using the current colour/brightness.
// Called on every colour click and every brightness drag so changes apply live.
function headLedApply() {
  return headLedPreset(lastHeadPreset);
}
function eyeLedApply() {
  return eyeLedPreset(lastEyePreset);
}

// preset 預設速度（ms）：撳 preset 先將 slider 撥返去經典值再送；
 // 之後拖 slider 即時重送（同光度一樣 live）。long/stop 無視速度。
const PERLED_HEAD_SPEED_DEFAULT = { flash: 150, chase: 100, dual: 150, breathe: 100 };
const PERLED_EYE_SPEED_DEFAULT = { flash: 150, chase: 100, dual: 150 };
function perledSetSpeed(sliderId, valId, value) {
  const slider = document.getElementById(sliderId);
  const val = document.getElementById(valId);
  if (slider) slider.value = String(value);
  if (val) val.textContent = String(value);
}

function headLedPreset(preset) {
  // 轉色/光度重送（headLedApply 經呢度）唔 reset 速度；淨係撳新 preset 先撥返經典值。
  const changed = preset !== lastHeadPreset;
  const wasStop = lastHeadPreset === "stop"; // stop 完再撳＝全開（唔用舊 0,0）
  // 入 dual 記低舊色（唔係三色之一即轉藍）、出 dual 還原；picker 三色/七色對應轉。
  const wasDual = lastHeadPreset === "dual";
  const willDual = preset === "dual";
  if (willDual && !wasDual) {
    prevHeadColor = selectedHeadColor;
    if (!dualValid(selectedHeadColor)) selectedHeadColor = 3;
  }
  if (!willDual && wasDual && prevHeadColor != null) {
    selectedHeadColor = prevHeadColor;
    prevHeadColor = null;
  }
  lastHeadPreset = preset;
  perledHeadMode = (preset === "flash" || preset === "chase" || preset === "breathe" || preset === "dual") ? preset : "static";
  if (changed && PERLED_HEAD_SPEED_DEFAULT[preset] != null) perledSetSpeed("headSpeed", "headSpeedVal", PERLED_HEAD_SPEED_DEFAULT[preset]);
  if (changed && (wasDual || willDual) && typeof buildHeadColorPicker === "function") buildHeadColorPicker();
  // 樂觀本地更新：mirror 即換（同送出去嘅一致，行同一個 perledPartialMasks，
  // 唔等 poll——斷線都 work，poll 到返嚟自動校準。光度一齊記，顯示跟光暗。
  // stop 完再撳非 stop（wasStop）即全開，唔好用返舊 0,0。
  const headBright = perledReadBrightness("headBrightness");
  if (preset === "stop") {
    perledHead = { p3: 0, p4: 0, color: 0, brightness: 0 };
  } else {
    const hm = (preset === "chase" || wasStop) ? null : perledPartialMasks("head", perledHead);
    if (hm) perledHead = { p3: hm.p3, p4: hm.p4, color: selectedHeadColor, brightness: headBright };
    else perledHead = { p3: 31, p4: 31, color: selectedHeadColor, brightness: headBright };
  }
  perledRender();
  return ledPresetApply("led/head/set", preset, selectedHeadColor, "headBrightness", perledHead, "headSpeed");
}

function perledReadBrightness(elId) {
  const el = document.getElementById(elId);
  const v = el ? parseInt(el.value, 10) : 9;
  return (v >= 1 && v <= 9) ? v : 9;
}

function eyeLedPreset(preset) {
  const changed = preset !== lastEyePreset;
  const wasStop = lastEyePreset === "stop"; // stop 完再撳＝全開
  lastEyeWasCountdown = false; // 掣一撳，速度 slider 歸 preset 管，唔再跟倒數
  const wasDual = lastEyePreset === "dual";
  const willDual = preset === "dual";
  if (willDual && !wasDual) {
    prevEyeColor = selectedEyeColor;
    if (!dualValid(selectedEyeColor)) selectedEyeColor = 3;
  }
  if (!willDual && wasDual && prevEyeColor != null) {
    selectedEyeColor = prevEyeColor;
    prevEyeColor = null;
  }
  lastEyePreset = preset;
  perledEyeMode = (preset === "flash" || preset === "chase" || preset === "dual") ? preset : "static";
  if (changed && PERLED_EYE_SPEED_DEFAULT[preset] != null) perledSetSpeed("eyeSpeed", "eyeSpeedVal", PERLED_EYE_SPEED_DEFAULT[preset]);
  if (changed && (wasDual || willDual) && typeof buildEyeColorPicker === "function") buildEyeColorPicker();
  const eyeBright = perledReadBrightness("eyeBrightness");
  if (preset === "stop") {
    perledEye = { p3: 0, p4: 0, color: 0, brightness: 0 };
  } else {
    const em = (preset === "chase" || wasStop) ? null : perledPartialMasks("eye", perledEye);
    if (em) perledEye = { p3: em.p3, p4: em.p4, color: selectedEyeColor, brightness: eyeBright };
    else perledEye = { p3: 255, p4: 255, color: selectedEyeColor, brightness: eyeBright };
  }
  perledRender();
  return ledPresetApply("led/eye/set", preset, selectedEyeColor, "eyeBrightness", perledEye, "eyeSpeed");
}

function perledZonePace(zone) {
  const el = document.getElementById(zone === "head" ? "headSpeed" : "eyeSpeed");
  const v = el ? parseInt(el.value, 10) : 100;
  return Math.max(50, Math.min(500, isNaN(v) ? 100 : v));
}

// 眼速度 slider 專用：倒數模式嗰陣歸倒數管——拖緊唔送（等放手先用新速度
// 重跑一次），唔係嘅話一郁就跳去上次個 preset（之前話「改唔到速度」就係咁嚟）。
function eyeSpeedInput(value) {
  const valEl = document.getElementById("eyeSpeedVal");
  if (valEl) valEl.textContent = value;
  // 倒數模式：拖緊唔送，等放手（onchange）先用新速度重跑；
  // 其他模式照舊即送（同光度一樣 live）。
  if (lastEyeWasCountdown) return;
  eyeLedApply();
}
function eyeSpeedCommit() {
  // 放手：倒數模式＋無行緊＝用新速度重跑；其他補送一次準數。
  if (lastEyeWasCountdown && !eyeCountdownRunning) {
    eyeCountdown3s(false);
  } else if (!lastEyeWasCountdown) {
    eyeLedApply();
  }
}

// mirror 外來態：wifi 燈（red/blue/off，server 記）管頭燈第 5 粒；
// 咀燈著熄（server mouth mode）掣顯示用；mute 本地撳掣態（server 唔記）。
let wifiMirror = "off";
let mouthLitMirror = false;
let muteLitLocal = false;
let sysPadLast = null; // 上次 pad 態（render 嗰陣重畫掣，中英切換即跟，唔使等 poll）

// 眼部「3秒倒數」：雙眼齊漸滿 3 圈（綠→黃→紅，每圈約 1 秒）→ 白燈 3 秒 → 熄。
// 行 ledEyeSet（long＋raw mask，同 debugJniLed 送落硬件嘅嘢一模一樣——
// p5/p6/p7/p8 全部 MAX,0,MAX,0），但經記帳 endpoint：每格 server 有 record，
// poll 永遠對得上。中途斷尾（某格炒咗）硬件停喺半路都唔怕，state 係啱嘅，
// 下次撳 preset／poll 照常接得返。之前行 debugJniLed 直透唔記帳，
// 斷尾嗰陣 server／mirror／硬件三邊各說各話，眼就係咁凍結——頭無呢條路。
let eyeCountdownRunning = false;
let eyeCountdownStartMs = 0;
// 倒數行緊嗰陣眼速度 slider 歸倒數管：拖緊唔送，等放手先用新速度重跑一次
// （見 eyeSpeedInput/eyeSpeedCommit）；其他 preset／逐粒撳即清返呢個旗。
let lastEyeWasCountdown = false;
function eyeCountdown3s(resetSpeed) {
  if (eyeCountdownRunning) return Promise.resolve();
  eyeCountdownRunning = true;
  eyeCountdownStartMs = Date.now();
  lastEyeWasCountdown = true;
  if (resetSpeed !== false) perledSetSpeed("eyeSpeed", "eyeSpeedVal", 100); // 倒數預設 100ms 一步（跟節奏）
  perledEyeMode = "static";
  const masks = [16, 24, 28, 30, 31, 159, 223, 255];
  const colors = ["2", "4", "1"]; // 綠 黃 紅
  function sleep(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  function step(color, mask) {
    const t0 = Date.now();
    const pace = perledZonePace("eye"); // 倒數步速跟眼速度 slider（最少 50ms，唔好 hammer 驅動）
    return Alpha2Api.ledEyeSet({ preset: "long", color: String(color), brightness: "9",
      p3: String(mask), p4: String(mask) }).then(function () {
      perledEye = { p3: mask, p4: mask, color: Number(color) };
      perledEyeOurs = true; // 倒數逐格都係我哋嘅（雖然唔經 NoteSent）
      perledRender();
      const dt = Date.now() - t0;
      if (dt < pace) return sleep(pace - dt);
    });
  }
  let p = Promise.resolve();
  colors.forEach(function (color) {
    masks.forEach(function (mask) {
      p = p.then(function () { return step(color, mask); });
    });
  });
  function done() { eyeCountdownRunning = false; }
  return p.then(function () {
    return step("7", 255); // 白燈全開
  }).then(function () {
    return sleep(3000);
  }).then(function () {
    return eyeLedPreset("stop"); // 經 preset 行（sent／mode／picker 一齊更新，唔留手尾）
  }).then(done, done);
}

// ---------------- 系統指示燈 ----------------
// Wi-Fi 燈（紅/藍/關）：fire-and-forget，網路狀態變更時系統會自動恢復。
// 音量鍵燈（14/16）：按一下開、再按一下關（server 端記錄狀態，
// 熄滅為全域熄滅，頭部與眼部會一併熄滅，Wi-Fi 燈會自動恢復）。
// 靜音燈：僅測試用途，小智連線狀態或實體按鍵會將其覆寫。
function sysWifiLed(color) {
  // 樂觀：mirror 即轉（poll 會校準；server 熄／自動恢復都會經 poll 返嚟）。
  // wifi 掣本身已刪（淨返頭燈第 5 粒撳即轉），唔使 paint 掣。
  wifiMirror = color;
  return Alpha2Api.ledWifiSet({ color: color });
}

// wifi 顯示燈（頭第 5 粒）撳即紅藍互轉：而家紅就轉藍，其他一律轉紅
// （off／未知當藍著，實機只會出紅藍）。
function sysWifiCycle() {
  return sysWifiLed(wifiEffective() === "red" ? "blue" : "red");
}

function wifiEffective() {
  return (wifiMirror === "red" || wifiMirror === "blue") ? wifiMirror : "blue";
}

function sysPadToggle(key) {
  return Alpha2Api.ledPadSet({ key: key }).then(function (res) {
    if (res && res.ok) sysPadRender(res);
    return res;
  });
}

function sysPadRender(res) {
  const minus = document.getElementById("padMinusBtn");
  const plus = document.getElementById("padPlusBtn");
  const m = !!(res && res.minus);
  const p = !!(res && res.plus);
  // 三角掣：入面無字，淨係轉色（著黃／熄灰）＋title 已經寫明係邊粒。
  // 中英切換唔使理（無字）。
  if (minus) {
    minus.classList.remove("on");
    if (m) minus.classList.add("on");
  }
  if (plus) {
    plus.classList.remove("on");
    if (p) plus.classList.add("on");
  }
}

function sysMuteToggle() {
  muteLitLocal = !muteLitLocal;
  muteRender();
  return Alpha2Api.ledMuteSet({ on: String(muteLitLocal) });
}

function muteRender() {
  // 咪 icon 掣：入面無字，淨轉色（著紅／熄灰）。
  const b = document.getElementById("muteBtn");
  if (!b) return;
  b.className = muteLitLocal ? "sys-toggle-on-red" : "sys-toggle-off";
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "嘴部 LED" section for what was tried and ruled out).
// 單掣 toggle：著＝紅，熄＝灰，態跟 server mirror（mouth mode）。
// 樂觀本地更新：撳即轉，poll 會校準（斷線都 work）。
function mouthToggle() {
  if (mouthLitMirror) {
    mouthLitMirror = false;
    mouthRender();
    return mouthLedOff().then(function (json) { perledPollOnce(); return json; });
  }
  mouthLitMirror = true;
  mouthRender();
  return mouthLedApply().then(function (json) { perledPollOnce(); return json; });
}

function mouthSpeedInput(value) {
  const valEl = document.getElementById("mouthSpeedVal");
  if (valEl) valEl.textContent = value;
  // 著緊先即時重送（跟新速度）；熄緊拖 slider 唔開燈。
  if (mouthLitMirror) mouthLedApply();
}

function mouthRender() {
  // 咀掣（開關鍵）已移除，淨返個碗（perledMouthArc）做顯示＋開關。
  const arc = document.getElementById("perledMouthArc");
  if (arc) {
    arc.classList.remove("on");
    if (mouthLitMirror) {
      arc.classList.add("on");
      const sEl = document.getElementById("mouthSpeed");
      const sv = sEl ? parseInt(sEl.value, 10) : 100;
      const cyc = Math.max(100, Math.min(1000, isNaN(sv) ? 100 : sv) * 2);
      arc.style.animationDuration = String(cyc) + "ms";
    } else {
      arc.style.animationDuration = "";
    }
  }
}

// Mouth LED - breathing effect only (confirmed the one usable effect on this
// hardware; see README "嘴部 LED" section for what was tried and ruled out).
function mouthLedApply() {
  const speed = document.getElementById("mouthSpeed").value;
  return Alpha2Api.ledMouthSet( { speed: speed });
}

function mouthLedOff() {
  return Alpha2Api.ledMouthSet( { preset: "off" });
}

// ---------------- 逐粒試燈 ----------------
// bit 表（實機試位確認，見 LedCenter 註解）：
// 頭左 P4 正序 [1,2,4,8,16]，頭右 P3 反序 [16,8,4,2,1]，最上一粒（5 號）係 wifi 位；
// 眼每邊 8 粒 ring，順時針由 12 點起 [16,8,4,2,1,128,64,32]。
// 硬件 write-only 讀唔返——顯示靠 /api/led/state/get（server 記低最後一轉）。
// 撳掣送 led/head/raw 或 led/eye/raw，用上面揀嘅色＋光度。
// preset 動畫（flash/chase/breathe/dual）硬件自己跑，server 唔知幀——
// 前端按已知時序喺 mirror 度模擬返，長開/停止/raw 照 mask 靜態顯示。
const PERLED_HEAD_L_BITS = [1, 2, 4, 8, 16];
const PERLED_HEAD_R_BITS = [16, 8, 4, 2, 1];
const PERLED_EYE_BITS = [16, 8, 4, 2, 1, 128, 64, 32];
let perledHead = { p3: -1, p4: -1, color: -1, brightness: -1 }; // -1＝server 未打過（unknown）
let perledEye = { p3: -1, p4: -1, color: -1, brightness: -1 };
let perledHeadMode = "static";
let perledEyeMode = "static";
let perledBuilt = false;
let perledPollTimer = null;
let perledAnimTimer = null;

// 雙色燈（實機回報，照字面）：
// 紅/綠/藍/青/白＝藍↔綠，黃＝紅↔綠，紫＝紅↔藍（從來唔出揀嗰隻色本身）。
function perledDualHex(selected, phase) {
  if (selected === 4) return perledColorHex(phase % 2 === 0 ? 1 : 2); // 黃：紅↔綠
  if (selected === 5) return perledColorHex(phase % 2 === 0 ? 1 : 3); // 紫：紅↔藍
  return perledColorHex(phase % 2 === 0 ? 3 : 2); // 其餘：藍↔綠
}

function perledColorHex(code) {
  for (let i = 0; i < LED_COLORS.length; i++) {
    if (LED_COLORS[i].code === code) return LED_COLORS[i].hex;
  }
  return "#9aa0a6";
}

// mask 清洗：unknown（-1/NaN）→0，夾實上限——client assert 唔會再爆。
function perledCleanMask(v, max) {
  v = Number(v);
  if (!(v >= 0)) return 0;
  return Math.min(max, v | 0);
}

function perledMakeDot(num, wifi) {
  const dot = document.createElement("button");
  dot.type = "button";
  dot.className = "perled-dot" + (wifi ? " perled-wifi" : "");
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
      if (i === 4) {
        // 第 5 粒係 wifi 位：撳即紅藍互轉（唔經 mask，硬件唔跟嗰套）。
        dot.onclick = function () { sysWifiCycle(); };
      } else {
        (function (mask, bit) {
          dot.onclick = function () { perledToggle("head", mask, bit); };
        })(cfg[1], cfg[2][i]);
      }
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
  // 動畫 tick（100ms：flash 剛好 5Hz，同硬件 p5/p6=100 一致）。
  if (!perledAnimTimer) {
    perledAnimTimer = setInterval(function () {
      const tab = document.getElementById("tab-led");
      if (tab && tab.classList.contains("active")) perledRender();
    }, 100);
  }
}

function perledRender() {
  // paint：mask 靜態著＋preset 動畫模擬。
  // 相位全部由 wall clock＋速度 slider 計（唔用 render 次數——poll＋動畫＋撳掣
  // 都會 render，計次數會時快時慢）：flash 週期 2×速度、跑馬每步＝速度、
  // 雙色每轉＝速度、呼吸一圈＝速度×12（最少 300ms）。
  // flash=齊閃 chase=單點掃（眼逆時針，硬件熄哂照掃所以唔理 mask）
  // breathe=成區統一呼吸 dual=見 perledDualHex（實機回報配對）。
  const now = Date.now();
  function zonePhase(zone) {
    const pace = Math.max(50, perledZonePace(zone));
    const cyc = Math.max(300, pace * 12);
    const ph = (now % cyc) / cyc;
    return {
      flash: Math.floor(now / pace) % 2 === 0,
      chaseStep: Math.floor(now / pace),
      dual: Math.floor(now / pace),
      breathe: 0.35 + 0.65 * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph))
    };
  }
  const headPh = zonePhase("head");
  const eyePh = zonePhase("eye");
  function paint(wrapId, bits, mask, colorCode, brightness, mode, side, zone, count, ph, wifi) {
    const wrap = document.getElementById(wrapId);
    if (!wrap) return;
    const dots = wrap.querySelectorAll(".perled-dot");
    // 頭 strip DOM 順序係 5→1（同 build 一致），眼 ring 係 1→8。
    const order = wrapId.indexOf("Head") >= 0 ? [4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];
    const baseHex = perledColorHex(colorCode);
    // 光度 1-9 照跟顯示（opacity）：9 實色，1 最淡。呼吸就乘埋一齊。
    const b = (brightness >= 1 && brightness <= 9) ? brightness : 9;
    const brightAlpha = 0.3 + 0.7 * (b / 9);
    // 眼跑馬逆時針：由 1 號行去 8、7、6…；頭直排由 1 號向上掃。
    const chaseK = zone === "eye" ? (count - (ph.chaseStep % count)) % count : (ph.chaseStep % count);
    // -1＝server 未打過，當全熄畫（唔好將 -1 當 mask 用，bit 位會亂）。
    const m = mask < 0 ? 0 : mask;
    // 頭燈第 5 粒（bi===4）係 wifi 位：跟實際態（紅/藍，off/未知當藍——
    // 實機只會出紅藍，總有一個著）；其他位跟 mask。硬件就係咁，唔呃人。
    const WIFI_HEX = { red: "#ff3b3b", blue: "#3b6bff" };
    const wifiEff = zone === "head" ? wifiEffective() : null;
    order.forEach(function (bi, di) {
      const dot = dots[di];
      if (!dot) return;
      let on;
      let hex = baseHex;
      if (bi === 4 && wifiEff) {
        on = true;
        hex = WIFI_HEX[wifiEff];
      } else {
        const base = (m & bits[bi]) !== 0;
        on = base;
        if (mode === "flash") {
          on = base && ph.flash;
        } else if (mode === "chase") {
          on = (bi === chaseK);
        } else if (mode === "dual") {
          on = base;
          if (base) hex = perledDualHex(colorCode, ph.dual);
        }
      }
      dot.classList.toggle("perled-on", on);
      dot.style.background = on ? hex : "";
      // 全部色一律黑邊（白喺淺色底會隱形）；熄還原 CSS 預設邊。
      dot.style.borderColor = on ? "#222" : "";
      // 光度 opacity（wifi 位唔跟：調光度唔郁 wifi 燈）。
      // 呼吸係成區同一個 opacity（×光度）；其他清返。
      // wifi 位唔跟呼吸（佢係獨立燈），全程實色。
      const isWifiDot = bi === 4 && wifiEff;
      let alpha = "";
      if (on) {
        alpha = isWifiDot ? "1" : String(brightAlpha);
        if (mode === "breathe" && !isWifiDot) alpha = String(ph.breathe * brightAlpha);
      }
      dot.style.opacity = alpha;
    });
  }
  paint("perledHeadL", PERLED_HEAD_L_BITS, perledHead.p4, perledHead.color, perledHead.brightness, perledHeadMode, "L", "head", 5, headPh, wifiMirror);
  paint("perledHeadR", PERLED_HEAD_R_BITS, perledHead.p3, perledHead.color, perledHead.brightness, perledHeadMode, "R", "head", 5, headPh, wifiMirror);
  paint("perledEyeL", PERLED_EYE_BITS, perledEye.p3, perledEye.color, perledEye.brightness, perledEyeMode, "L", "eye", 8, eyePh, null);
  paint("perledEyeR", PERLED_EYE_BITS, perledEye.p4, perledEye.color, perledEye.brightness, perledEyeMode, "R", "eye", 8, eyePh, null);
  // 掣文字中英即跟（render 每 100ms 行一次，轉語言唔使等下次 poll／撳掣）。
  if (typeof sysPadRender === "function") sysPadRender(sysPadLast);
  if (typeof muteRender === "function") muteRender();
  if (typeof mouthRender === "function") mouthRender();
}

function perledToggle(zone, mask, bit) {
  // 逐粒撳唔殺效果：行緊閃／跑馬等嗰陣，轉送同一個 preset＋新 mask，
  // 效果繼續、顯示即換新（stop 緊嗰陣撳即轉長開）。色＋光度一齊記，顯示即跟。
  // base 規矩：自己台跟而家；人哋台（disco 等）由全開啟手，唔攞人哋個
  // pattern 嚟改；從來未 sync 過由零起，逐粒點（之前眼就係衰喺呢度：
  // disco mask 當咗自己嘅，送返出去錯晒）。
  const max = zone === "head" ? 31 : 255;
  const full = max;
  if (zone === "head") {
    const st = perledHead;
    const base = perledHeadOurs ? st[mask]
      : ((st.p3 >= 0 && st.p4 >= 0) ? full : 0);
    perledHeadOurs = true; // 撳即認領：之後送出去嗰組就係我哋嘅
    perledHead[mask] = (perledCleanMask(base, max) ^ bit) & max;
    perledHead.color = selectedHeadColor;
    perledHead.brightness = perledReadBrightness("headBrightness");
    perledRender();
    if (lastHeadPreset === "stop") { lastHeadPreset = "long"; perledHeadMode = "static"; }
    ledPresetApply("led/head/set", lastHeadPreset, selectedHeadColor, "headBrightness", perledHead, "headSpeed")
      .then(function () { perledPollOnce(); });
  } else {
    const st = perledEye;
    const base = perledEyeOurs ? st[mask]
      : ((st.p3 >= 0 && st.p4 >= 0) ? full : 0);
    perledEyeOurs = true; // 撳即認領
    perledEye[mask] = (perledCleanMask(base, max) ^ bit) & max;
    perledEye.color = selectedEyeColor;
    perledEye.brightness = perledReadBrightness("eyeBrightness");
    perledRender();
    lastEyeWasCountdown = false; // 逐粒撳即歸 preset 管
    if (lastEyePreset === "stop") { lastEyePreset = "long"; perledEyeMode = "static"; }
    ledPresetApply("led/eye/set", lastEyePreset, selectedEyeColor, "eyeBrightness", perledEye, "eyeSpeed")
      .then(function () { perledPollOnce(); });
  }
}

// 即時 mirror：100ms poll 一次 server 最後燈態（淨係 LED tab 開緊嗰陣先打；
// rhythm LED 100ms 推一次，1 秒會 miss 晒）。-1＝server 未打過，原樣保留
// （render 當熄畫，送 preset 嗰陣唔帶 mask＝全開）。
// 注意：呢條係 background mirror——失敗唔彈 toast（唔係嘅話 10 次/秒洗版），
// 唔重疊（等緊上一次先唔打新嘅），失敗自動 backoff 到 2 秒，掂返即回 100ms。
let perledPollInFlight = false;
let perledPollInterval = 100; // 節奏燈/顯示燈 100ms（用家要求；失敗 backoff 到 2 秒）
let perledPollSeq = 0; // cache-bust 計數（舊 response 唔好畀任何 cache 食咗）
function quietStateGet() {
  // 靜音通道：爛 response 唔彈 toast，寫 console（F12 先睇到；分頁已經無狀態行）。
  try {
    const merged = (typeof withPanelToken === "function") ? withPanelToken() : null;
    let qs = "_=" + (perledPollSeq++);
    if (merged) qs += "&" + new URLSearchParams(merged).toString();
    qs = "?" + qs;
    return fetch(API + "alpha2/led/state/get" + qs).then(function (res) {
      return res.text().then(function (txt) {
        if (!txt) {
          console.warn("[perled] state/get 空 body，status=" + res.status);
          return null;
        }
        try {
          // BOM 容錯：頭一個係 U+FEFF 即劈走先 parse（唔知邊度塞入嚟，劈咗先算）。
          if (txt.charCodeAt(0) === 0xFEFF) txt = txt.slice(1);
          return JSON.parse(txt);
        } catch (e) {
          // 記埋長度＋頭尾＋頭尾字碼——分得出截斷、中間爛、BOM、怪 byte。
          console.warn("[perled] state/get 爛 JSON，status=" + res.status + " 長" + txt.length + "： " + txt.slice(0, 120));
          return null;
        }
      });
    }).catch(function (e) {
      console.warn("[perled] state/get 網絡炒：" + e);
      return null;
    });
  } catch (e) {
    return Promise.resolve(null);
  }
}
function perledApplyState(res) {
    if (res.head) perledHead = { p3: numOrNeg1(res.head.p3), p4: numOrNeg1(res.head.p4), color: numOrNeg1(res.head.color), brightness: numOrNeg1(res.head.brightness) };
    // 倒數行緊嗰陣眼唔跟 poll（debugJniLed 唔記 state，跟咗會冚咗真 timeline）。
    if (res.eye && !eyeCountdownRunning) perledEye = { p3: numOrNeg1(res.eye.p3), p4: numOrNeg1(res.eye.p4), color: numOrNeg1(res.eye.color), brightness: numOrNeg1(res.eye.brightness) };
  if (res.pad && typeof sysPadRender === "function") {
    sysPadLast = res.pad;
    sysPadRender(res.pad);
  }
  if (typeof res.wifi === "string") {
    wifiMirror = res.wifi;
  }
  if (res.mouth) {
    mouthLitMirror = res.mouth.mode === 1;
    if (typeof mouthRender === "function") mouthRender();
  }
  perledCheckDiverged(res);
  perledRender();
}

// 有人喺我哋背後改過燈（disco 最常見）：server 嘅 mask 同我哋上次送出去
// 唔一致——即時還原靜態、照 mask 直出（跟實機，唔好用舊動畫呃人）。
// send／poll 撞車嗰 100ms 誤殺唔怕：還原之前記低個 mode，下次對返
// 即著返（來回最多閃一閃）；撳新掣會清咗佢（見 perledNoteSent）。
let perledHeadMismatch = 0;
let perledEyeMismatch = 0;
let perledHeadDivergedFrom = null;
let perledEyeDivergedFrom = null;
function perledSameState(a, b) {
  // mask＋色＋光度全中先算同一個態：disco 眼同 preset 一樣全開，
  // 淨係色唔同——淨對 mask 的話眼永遠對得上，還原唔到！
  return a && b && a.p3 === b.p3 && a.p4 === b.p4
    && (a.color | 0) === (b.color | 0) && (a.brightness | 0) === (b.brightness | 0);
}
function perledCheckDiverged(res) {
  if (res.head && perledHeadSent) {
    if (!perledSameState(res.head, perledHeadSent)) {
      perledHeadMismatch++;
      perledHeadOurs = false; // 人哋郁過：呢台唔再係我哋嘅
      if (perledHeadMode !== "static") {
        perledHeadDivergedFrom = perledHeadMode;
        perledHeadMode = "static";
      }
    } else {
      perledHeadMismatch = 0;
      perledHeadOurs = true; // 對返：係我哋嘅
      if (perledHeadDivergedFrom && perledHeadMode === "static") {
        perledHeadMode = perledHeadDivergedFrom;
        perledHeadDivergedFrom = null;
      }
    }
  }
  if (res.eye && !eyeCountdownRunning && perledEyeSent) {
    if (!perledSameState(res.eye, perledEyeSent)) {
      perledEyeMismatch++;
      perledEyeOurs = false;
      if (perledEyeMode !== "static") {
        perledEyeDivergedFrom = perledEyeMode;
        perledEyeMode = "static";
      }
    } else {
      perledEyeMismatch = 0;
      perledEyeOurs = true;
      if (perledEyeDivergedFrom && perledEyeMode === "static") {
        perledEyeMode = perledEyeDivergedFrom;
        perledEyeDivergedFrom = null;
      }
    }
  }
}

function perledPollOnce() {
  // 撳掣跟手即刷一次——行靜音通道（set 本身失敗會彈，呢度唔再開聲），
  // 同 loop 共用同一個 tick＋inFlight，永遠唔會自己撞自己。
  perledPollInterval = 100;
  if (perledPollTimer) {
    clearTimeout(perledPollTimer);
    perledPollTimer = null;
  }
  perledTickOnce();
}

function numOrNeg1(v) {
  v = Number(v);
  return (v >= 0) ? (v | 0) : -1;
}

function perledTickOnce() {
  perledPollTimer = null;
  // 看門狗：倒數正常最多 ~15 秒完（24 格×500ms＋白燈 3 秒）。超過 30 秒
  // eyeCountdownRunning 仲係 true，即係條鏈斷咗尾（舊版無 done-on-reject
  // 之類），眼會永遠唔跟 poll——強制還原，唔好 jam 住。
  if (typeof eyeCountdownRunning !== "undefined" && eyeCountdownRunning
      && typeof eyeCountdownStartMs !== "undefined"
      && Date.now() - eyeCountdownStartMs > 30000) {
    eyeCountdownRunning = false;
    lastEyeWasCountdown = false;
    if (typeof console !== "undefined" && console.warn) {
      console.warn("[perled] eyeCountdownRunning jammed, force reset");
    }
  }
  const tab = document.getElementById("tab-led");
  if (tab && tab.classList.contains("active") && !perledPollInFlight) {
    perledPollInFlight = true;
    quietStateGet().then(function (res) {
      perledPollInFlight = false;
      if (res && res.ok) {
        perledPollInterval = 100;
        perledApplyState(res);
      } else {
        // 回到嚟但 ok=false／爛 response／網絡炒——backoff，掂返自動回 100ms。
        perledPollInterval = Math.min(2000, perledPollInterval * 2);
      }
      perledSchedule();
    });
  } else {
    perledSchedule();
  }
}

function perledSchedule() {
  if (perledPollTimer) clearTimeout(perledPollTimer);
  perledPollTimer = setTimeout(perledTickOnce, perledPollInterval);
}

function perledPollLoop() {
  perledSchedule();
}

