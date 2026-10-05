// Open Alpha2 — client logic (app-gamepad.js)
// 內容: BT 手柄／鍵盤分頁（A 端直連；見 GamepadCenter.java）。
//
// 本頁只做 A 端（機器人直連）：BT HID 配對到機器人本機藍牙，物理鍵經
// MainActivity.dispatchKeyEvent／onGenericMotionEvent 截獲，映射在機器人端
// 執行。「已接設備」列的就是這批（InputDevice 列出）。手柄圖／鍵盤圖經
// WebSocket 即時亮燈（A 端按鍵）；映射表分手柄／鍵盤兩區，各配各。
// （B 端瀏覽器遙控已移除：gamepad/key／axis 後端保留畀脚本直調。）
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定。

// 邏輯鍵全集（同 GamepadCenter.PAD_KEYS／KB_KEYS 對應，分組顯示用；名即鍵，無前綴）。
// 手柄邊組（22 鍵）＋鍵盤邊組（105 鍵，同名鍵獨立值，各配各）。
var GAMEPAD_PAD_GROUPS = [
  { title: "dir", keys: ["up", "down", "left", "right"] },
  { title: "pad", keys: ["a", "b", "x", "y", "start", "select",
    "l1", "r1", "l2", "r2"] },
  { title: "stick_l", keys: ["lx_neg", "lx_pos", "ly_neg", "ly_pos"] },
  { title: "stick_r", keys: ["rx_neg", "rx_pos", "ry_neg", "ry_pos"] }
];
var GAMEPAD_KB_GROUPS = [
  { title: "dir", keys: ["up", "down", "left", "right"] },
  { title: "kbletter", keys: ["a", "b", "c", "d", "e", "f", "g", "h", "i",
    "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
    "u", "v", "w", "x", "y", "z"] },
  { title: "kbdigit", keys: ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"] },
  { title: "kbctrl", keys: ["space", "enter", "tab", "backspace", "esc",
    "shift", "ctrl", "alt", "caps", "vol_up", "vol_down",
    "media_play", "media_next", "media_prev", "media_stop", "mute"] },
  { title: "kbsym", keys: ["minus", "equals", "lbracket", "rbracket",
    "backslash", "semicolon", "quote", "comma", "period", "slash", "grave"] },
  { title: "kbnav", keys: ["ins", "del", "home", "end", "pgup", "pgdn",
    "print", "scroll", "pause", "menu"] },
  { title: "kbnum", keys: ["num_7", "num_8", "num_9", "num_div",
    "num_4", "num_5", "num_6", "num_mul",
    "num_1", "num_2", "num_3", "num_sub",
    "num_0", "num_dot", "num_enter", "num_add"] },
  { title: "fn", keys: ["f1", "f2", "f3", "f4", "f5", "f6",
    "f7", "f8", "f9", "f10", "f11", "f12"] }
];

// 動作種類（servo 軸＋方向另有下拉拼出 servo<N>±；music 揀功能（含音量＋-）；
// ring 揀鈴聲（系統預設電話鈴＋鈴聲名）；led 揀部位＋效果＋色；camera 揀影相／追蹤；
// action 由動作下拉選）。
var GAMEPAD_KINDS = ["none", "stop", "servo", "music", "ring", "led", "camera", "action"];

// 相機／追蹤（同後端 isCameraAction 對齊；錄影後端無，唔收）。
var GAMEPAD_CAMERA_FNS = ["camera_photo", "face_start", "face_stop", "color_start", "color_stop"];

// 頭／眼 LED 部位＋效果＋色（同後端 isLedAction 對齊；眼無 breathe；色 1紅2綠3藍4黃5紫6青7白）。
var GAMEPAD_LED_PARTS = ["head", "eye"];
var GAMEPAD_LED_PRESETS = {
  head: ["long", "flash", "breathe", "chase", "dual", "stop"],
  eye: ["long", "flash", "chase", "dual", "stop"]
};
var GAMEPAD_LED_COLORS = [["1", "1紅"], ["2", "2綠"], ["3", "3藍"], ["4", "4黃"],
  ["5", "5紫"], ["6", "6青"], ["7", "7白"]];

// 音樂／音量功能（同後端 MUSIC_ACTIONS＋vol_up／vol_down 對齊；toggle＝播緊停／
// 停緊續／無歌播首首；random＝隨機一首）。
var GAMEPAD_MUSIC_FNS = ["music_play", "music_next", "music_prev", "music_pause", "music_resume",
  "music_toggle", "music_stop", "music_random", "vol_up", "vol_down"];

// 動作 5 大類（sub→類映射；細 sub 名唔再逐個列）。
// basic＝基本（移動／手勢／頭部），dance＝舞蹈，story＝寓言故事，
// yoga＝瑜伽（站立平衡／伸展／踢腿動態），others＝其餘＋未分類。
var GAMEPAD_ACTION_KINDS5 = ["basic", "dance", "story", "yoga", "others"];
var GAMEPAD_ACTION_KIND_NAMES = {
  basic: "基本", dance: "舞蹈", story: "故事", yoga: "瑜伽", others: "其他"
};
var GAMEPAD_ACTION_SUB2KIND = {
  "移動類": "basic", "手勢類": "basic", "頭部類": "basic",
  "兒童歌曲 / 卡通舞蹈": "dance", "流行 / 節奏舞蹈": "dance", "品牌 / 客製舞蹈": "dance",
  "中國寓言": "story", "西方寓言 / 故事": "story",
  "站立式 / 平衡式": "yoga", "伸展式": "yoga", "踢腿 / 動態式": "yoga"
};

// （B 端開關／輪詢已移除：本頁只做 A 端顯示＋映射。）

// ---------------- 管理：狀態＋藍牙開關 ----------------

function gamepadRefresh() {
  return Alpha2Api.gamepadStatus().then(function (json) {
    var st0 = document.getElementById("gamepadScanState");
    if (!json || !json.ok) {
      if (st0) st0.textContent = t("gamepad_read_failed");
      return json;
    }
    var bt = json.bt || {};
    gamepadSyncBtToggle(bt.enabled);
    // 先用已知開關畫一次（init 預設雙開），等 input/get 回嚟再對一次。
    gamepadRenderMapping(json.mapping || null);
    Alpha2Api.gamepadInputGet().then(function (ijson) {
      if (ijson && ijson.ok) {
        gamepadSyncInputToggles(!!ijson.pad, !!ijson.kb);
        gamepadRenderMapping(json.mapping || null);
      }
    });
    gamepadRenderMapping(json.mapping || null);
    gamepadSetLastText(gamepadLastText(json.last || null));
    return json;
  }).catch(function (err) {
    var st9 = document.getElementById("gamepadScanState");
    if (st9) st9.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadBtSet(on) {
  return Alpha2Api.gamepadBt({ on: on ? "true" : "false" }).then(function () {
    return gamepadRefresh();
  });
}

function gamepadBtToggle() {
  var el = document.getElementById("gamepadBtToggle");
  return gamepadBtSet(!!(el && el.checked));
}

// 面板開關狀態跟機器人實際（gamepadRefresh 每次同步；免顯示同實際唔啱）。
function gamepadSyncBtToggle(enabled) {
  var el = document.getElementById("gamepadBtToggle");
  if (el) el.checked = !!enabled;
}

function gamepadInputToggle(side) {
  var el = document.getElementById(side === "kb" ? "gamepadInputKbToggle" : "gamepadInputPadToggle");
  return Alpha2Api.gamepadInputSet({ side: side, on: (el && el.checked) ? "true" : "false" }).then(function () {
    return gamepadRefresh();
  });
}

function gamepadSyncInputToggles(padOn, kbOn) {
  var p = document.getElementById("gamepadInputPadToggle");
  if (p) p.checked = padOn !== false;
  var k = document.getElementById("gamepadInputKbToggle");
  if (k) k.checked = kbOn !== false;
  window.__gpPadOn = padOn !== false;
  window.__gpKbOn = kbOn !== false;
}

// ---------------- A 端配對：掃描＋綁定 ----------------
// 流程：手柄入配對模式 → gamepadScan()（約 12 秒，found 即時經 WS gamepad_bt
// 推，呢度再補一次輪詢等掃完）→ 列表按「配對」→ gamepad/pair → 等 gamepad_bt
// bond 事件（Event Log 見到）→ _done 再掃一次確認 bonded。

function gamepadBondText(bond) {
  if (bond === "bonded") return "✅ " + t("gamepad_bonded");
  if (bond === "bonding") return "… " + t("gamepad_bonding");
  return "";
}

function gamepadRenderFound(found, bonded) {
  var wrap = document.getElementById("gamepadFoundOut");
  if (!wrap) return;
  wrap.innerHTML = "";
  var all = [];
  (bonded || []).forEach(function (d) {
    d._bondedList = true;
    all.push(d);
  });
  (found || []).forEach(function (d) {
    var dup = all.some(function (x) { return x.address === d.address; });
    if (!dup) all.push(d);
  });
  if (!all.length) {
    wrap.textContent = t("gamepad_found_empty");
    return;
  }
  all.forEach(function (d) {
    var row = document.createElement("div");
    row.className = "row";
    row.style.alignItems = "center";
    var label = document.createElement("span");
    label.style.flex = "1";
    var icon = d.kind === "keyboard" ? "⌨️" : "🎮";
    label.textContent = icon + " " + (d.name || "(unknown)") + "  " + d.address + "  " + gamepadBondText(d.bond);
    row.appendChild(label);
    if (d.bond === "bonded") {
      // bonded 未必等於已連（input 列表無佢嗰陣撳連接主動連；已連緊再撳無害）。
      // 唔要嗰部機撳解除綁定（unpair）。
      var cbtn = document.createElement("button");
      cbtn.className = "secondary";
      cbtn.textContent = t("gamepad_connect_btn");
      cbtn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_connecting");
          // 5 秒後果（重查＋轉結果字）在 gamepadConnect 入面做，呢度淨係收錯。
          gamepadConnect(addr).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_connect_btn");
          });
        };
      })(d.address, cbtn);
      row.appendChild(cbtn);
      var ubtn = document.createElement("button");
      ubtn.className = "danger-btn";
      ubtn.textContent = t("gamepad_unpair_btn");
      ubtn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_unpairing");
          gamepadUnpair(addr).then(function () {
            // 解綁即時生效：4 秒後重掃＋刷新已接設備確認。
            setTimeout(function () { gamepadScan(); gamepadRefresh(); }, 4000);
          }).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_unpair_btn");
          });
        };
      })(d.address, ubtn);
      row.appendChild(ubtn);
    }
    if (d.bond !== "bonded") {
      var btn = document.createElement("button");
      btn.textContent = t("gamepad_pair_btn");
      btn.onclick = (function (addr, b) {
        return function () {
          b.disabled = true;
          b.textContent = t("gamepad_pairing");
          gamepadPair(addr).then(function () {
            // 配對要幾秒：6 秒後自動重掃確認（期間 bond 事件會入 Event Log）。
            setTimeout(gamepadScan, 6000);
          }).catch(function () {
            b.disabled = false;
            b.textContent = t("gamepad_pair_btn");
          });
        };
      })(d.address, btn);
      row.appendChild(btn);
    }
    wrap.appendChild(row);
  });
}

function gamepadScan() {
  var st = document.getElementById("gamepadScanState");
  if (st) st.textContent = t("gamepad_scanning");
  return Alpha2Api.gamepadScan().then(function (json) {
    if (json && json.ok) {
      gamepadRenderFound(json.found, json.bonded);
      if (st) st.textContent = json.scanning ? t("gamepad_scanning") : t("gamepad_scan_done");
      // 掃緊（約 12 秒窗）：8 秒後自動補一次，免手撳等漏咗後到的設備。
      if (json.scanning) {
        setTimeout(function () {
          Alpha2Api.gamepadScan().then(function (j2) {
            if (j2 && j2.ok) {
              gamepadRenderFound(j2.found, j2.bonded);
              if (st) st.textContent = j2.scanning ? t("gamepad_scanning") : t("gamepad_scan_done");
            }
          });
        }, 8000);
      }
    } else if (st) {
      st.textContent = (json && json.error) || t("gamepad_read_failed");
    }
    return json;
  }).catch(function (err) {
    if (st) st.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadConnect(address) {
  return Alpha2Api.gamepadConnect({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    var ok = !!(json && json.ok);
    if (st) {
      st.textContent = ok ? t("gamepad_connecting") : t("gamepad_read_failed");
    }
    if (ok) {
      // 連接提示唔可以痴住唔走：5 秒後重查，轉做結果字。
      setTimeout(function () {
        gamepadRefresh();
        var st2 = document.getElementById("gamepadScanState");
        if (st2) st2.textContent = t("gamepad_connected");
      }, 5000);
    }
    return json;
  });
}

function gamepadUnpair(address) {
  return Alpha2Api.gamepadUnpair({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    if (st) {
      st.textContent = (json && json.ok) ? t("gamepad_unpaired") : t("gamepad_read_failed");
    }
    return json;
  });
}

// 最後按鍵一行：key ↓/↑ → action [source] ✓/✗(只記錄)。
// taken=false（機身直連鍵盤 HTML-only、放開事件、播失敗）即打✗，一眼分清
// 「有 action 名」同「真係做咗」係兩回事。
function gamepadLastText(d) {
  if (!d || typeof d !== "object" || !d.key) return "-";
  var k = d.key;
  var arrow = (d.pressed === false) ? " ↑" : " ↓";
  var s = (k === "unmapped" && d.code != null ? (k + "(" + d.code + ")") : k)
    + arrow + " → " + (d.action || "-") + " [" + (d.source || "-") + "]"
    + (d.taken === true ? " ✓" : (d.taken === false ? " " + t("gamepad_taken_no") : ""));
  return s;
}

// 最後按鍵寫兩卡（手柄圖下＋鍵盤圖下同一行字）。
function gamepadSetLastText(s) {
  ["gamepadLastOut", "gamepadKbLastOut"].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.textContent = s;
  });
}

// ---------------- WS 實時：最後按鍵即時刷 ----------------
// app-log.js appendLog() 收到 gamepad_key／gamepad_bt／gamepad_axis 即轉入呢度。
function onGamepadKeyEvent(type, data) {
  if (!data || typeof data !== "object") return;
  if (type === "gamepad_key") {
    // 兩張圖＋映射行跟亮滅（色跟有無映射：WS 事件帶 action）。
    gamepadLightLogical(data.key, data.pressed !== false, data.action);
    // 放開唔刷文字（後端 last 都唔記放開；呢度雙保險，免顯示閃去 ↑✗）。
    if (data.pressed === false) return;
    gamepadSetLastText(gamepadLastText(data));
  } else if (type === "gamepad_axis") {
    // A 端搖桿經呢度郁點。
    gamepadMoveNubs(Number(data.lx || 0), Number(data.ly || 0),
      Number(data.rx || 0), Number(data.ry || 0));
  } else if (type === "gamepad_bt" && data.event) {
    // bond／scan_done 落 Event Log 之餘，順手將管理卡現狀拉新一次。
    if (data.event === "bond" || data.event === "scan_done" || data.event === "unbond") {
      if (typeof gamepadRefresh === "function") {
        setTimeout(gamepadRefresh, 1500);
      }
    }
  }
}

function gamepadPair(address) {
  return Alpha2Api.gamepadPair({ address: address }).then(function (json) {
    var st = document.getElementById("gamepadScanState");
    if (st) {
      st.textContent = (json && json.bonded) ? t("gamepad_bonded")
        : ((json && json.ok) ? t("gamepad_pairing") : t("gamepad_read_failed"));
    }
    return json;
  });
}

// ---------------- 管理：映射表 ----------------

// 動作清單緩存（機身 action/list 活數據優先，唔得先用內嵌 202 catalog）。
// 分組經 action_classification.json（靜態，同目錄 fetch）。
var __gpActionsCache = null;
var __gpActionCatCache = null;

function gamepadActionDisplayName(a) {
  var nm = (uiLang === "en" ? (a.nameEn || a.nameCn) : (a.nameCn || a.nameEn)) || a.id;
  return nm + " (" + a.id + ")";
}

function gamepadLoadActions() {
  if (__gpActionsCache && __gpActionCatCache) return Promise.resolve(__gpActionsCache);
  var acts = null;
  return Alpha2Api.actionList().then(function (json) {
    if (json && json.ok && json.actions && json.actions.length) {
      acts = json.actions;
    } else {
      return fetch("xiaozhi_actions.json").then(function (r) { return r.json(); }).then(function (j) {
        acts = j;
        return fetch("action_classification.json");
      }).then(function (r) { return r.json(); }).then(function (cat) {
        __gpActionCatCache = cat || {};
        __gpActionsCache = acts;
        return acts;
      });
    }
    // 活數據到手：分類表照讀（靜態）。
    return fetch("action_classification.json").then(function (r) { return r.json(); }).then(function (cat) {
      __gpActionCatCache = cat || {};
      __gpActionsCache = acts;
      return acts;
    }).catch(function () {
      __gpActionCatCache = __gpActionCatCache || {};
      __gpActionsCache = acts;
      return acts;
    });
  }).catch(function () {
    __gpActionsCache = __gpActionsCache || [];
    __gpActionCatCache = __gpActionCatCache || {};
    return __gpActionsCache;
  });
}

function gamepadActionGroupOf(id) {
  try {
    var c = (__gpActionCatCache || {})[id];
    if (c && c.sub) return c.sub;
  } catch (e) { /* ignore */ }
  return "其他";
}

// 值拆解：none/stop 直回；music_*/vol_* 歸 music 種；
// ring_phone/ring_stop/ring:<title> 歸 ring 種；head:/eye: 歸 led 種；
// servo<N>± 拆轴向；action:<id> 拆 id。
function gamepadParseValue(v) {
  var out = { kind: "none", servoId: 20, servoDir: "+", musicFn: "music_play",
    ringSel: "ring_phone", ledPart: "head", ledPreset: "flash", ledColor: "1",
    ledSpeed: "500", cameraFn: "camera_photo", actionId: "" };
  if (!v) return out;
  if (v === "none" || v === "stop") {
    out.kind = v;
    return out;
  }
  if (GAMEPAD_CAMERA_FNS.indexOf(v) !== -1) {
    out.kind = "camera";
    out.cameraFn = v;
    return out;
  }
  if (v === "ring_phone" || v === "ring_stop" || v.indexOf("ring:") === 0) {
    out.kind = "ring";
    out.ringSel = v;
    return out;
  }
  if (v === "head:stop" || v === "eye:stop" || v === "mouth:off"
      || v.match(/^(head|eye):[a-z]+:[1-7]$/) || v.match(/^mouth:breathing:\d+$/)) {
    out.kind = "led";
    var lp = v.split(":");
    out.ledPart = lp[0];
    out.ledPreset = (lp[0] === "mouth" && lp[1] === "breathing") ? "breathing" : lp[1];
    out.ledColor = lp[2] || "1";
    if (lp[0] === "mouth" && lp[1] === "breathing") out.ledSpeed = lp[2] || "500";
    return out;
  }
  if (GAMEPAD_MUSIC_FNS.indexOf(v) !== -1) {
    out.kind = "music";
    out.musicFn = v;
    return out;
  }
  var m = v.match(/^servo(\d{1,2})([+-])$/);
  if (m) {
    out.kind = "servo";
    out.servoId = Math.max(1, Math.min(20, parseInt(m[1], 10) || 20));
    out.servoDir = m[2];
    return out;
  }
  if (v.indexOf("action:") === 0) {
    out.kind = "action";
    out.actionId = v.substring(7);
    return out;
  }
  return out;
}

// 建舵機軸下拉（1-20）＋方向下拉（id 按邊分名前綴，兩區同名鍵唔撞）。
function gamepadBuildServoControls(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpServoWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "servo" ? "inline" : "none";
  var axis = document.createElement("select");
  axis.id = "gpServoAxis_" + side + "_" + k;
  for (var i = 1; i <= 20; i++) {
    var o = document.createElement("option");
    o.value = String(i);
    o.textContent = "#" + i;
    if (i === parsed.servoId) o.selected = true;
    axis.appendChild(o);
  }
  var dir = document.createElement("select");
  dir.id = "gpServoDir_" + side + "_" + k;
  ["+", "-"].forEach(function (d) {
    var o2 = document.createElement("option");
    o2.value = d;
    o2.textContent = d;
    if (d === parsed.servoDir) o2.selected = true;
    dir.appendChild(o2);
  });
  wrap.appendChild(axis);
  wrap.appendChild(dir);
  return wrap;
}

// 建 202 動作下拉（optgroup 分組；數據未到先放空，load 完補）。
function gamepadBuildActionSelect(side, k, parsed) {
  var sel = document.createElement("select");
  sel.id = "gpActionSel_" + side + "_" + k;
  sel.style.maxWidth = "220px";
  sel.style.display = parsed.kind === "action" ? "inline" : "none";
  var ph = document.createElement("option");
  ph.value = "";
  ph.textContent = "— " + t("gamepad_pick_action") + " —";
  sel.appendChild(ph);
  gamepadFillActionSelect(sel, parsed.actionId);
  return sel;
}

// 鈴聲名緩存（audio/ringtones/list 活數據；同 action 清單一樣懶載）。
var __gpRingTitlesCache = null;

function gamepadLoadRingTitles() {
  if (__gpRingTitlesCache) return Promise.resolve(__gpRingTitlesCache);
  return Alpha2Api.audioRingtonesList().then(function (json) {
    var out = [];
    if (json && json.ok && json.sounds) {
      json.sounds.forEach(function (s) {
        if (s && s.title) out.push(s.title);
      });
    }
    __gpRingTitlesCache = out;
    return out;
  }).catch(function () {
    __gpRingTitlesCache = __gpRingTitlesCache || [];
    return __gpRingTitlesCache;
  });
}

// 建鈴聲下拉（系統預設電話鈴＋鈴聲停＋名單；名單後到即補）。
function gamepadBuildRingSelect(side, k, parsed) {
  var sel = document.createElement("select");
  sel.id = "gpRingSel_" + side + "_" + k;
  sel.style.maxWidth = "200px";
  sel.style.display = parsed.kind === "ring" ? "inline" : "none";
  var fixed = [["ring_phone", "電話鈴聲"], ["ring_stop", "停鈴聲"]];
  fixed.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f[0];
    o.textContent = f[1];
    if (parsed.ringSel === f[0]) o.selected = true;
    sel.appendChild(o);
  });
  gamepadFillRingSelect(sel, parsed.ringSel);
  return sel;
}

function gamepadFillRingSelect(sel, cur) {
  var titles = __gpRingTitlesCache || [];
  if (!titles.length) {
    // 名單未到：現值係 title 即補一粒等睇到（同 action 那個做法）。
    if (cur && cur.indexOf("ring:") === 0) {
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = cur.substring(5);
      miss.selected = true;
      sel.appendChild(miss);
    }
    return;
  }
  titles.forEach(function (title) {
    var v = "ring:" + title;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = title;
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("ring:") === 0) {
    var found = false;
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === cur) { found = true; break; }
    }
    if (!found) {
      var miss2 = document.createElement("option");
      miss2.value = cur;
      miss2.textContent = cur.substring(5) + " (?)";
      miss2.selected = true;
      sel.appendChild(miss2);
    }
  }
}

// 建 LED 三下拉（部位＋效果＋色；效果跟部位變，色 1紅2綠3藍4黃5紫6青7白）。
function gamepadBuildLedControls(side, k, parsed) {
  var wrap = document.createElement("span");
  wrap.id = "gpLedWrap_" + side + "_" + k;
  wrap.style.display = parsed.kind === "led" ? "inline" : "none";
  var part = document.createElement("select");
  part.id = "gpLedPart_" + side + "_" + k;
  [["head", "頭"], ["eye", "眼"], ["mouth", "咀"]].forEach(function (p) {
    var o = document.createElement("option");
    o.value = p[0];
    o.textContent = p[1];
    if (p[0] === parsed.ledPart) o.selected = true;
    part.appendChild(o);
  });
  var preset = document.createElement("select");
  preset.id = "gpLedPreset_" + side + "_" + k;
  var color = document.createElement("select");
  color.id = "gpLedColor_" + side + "_" + k;
  GAMEPAD_LED_COLORS.forEach(function (c) {
    var o2 = document.createElement("option");
    o2.value = c[0];
    o2.textContent = c[1];
    if (c[0] === parsed.ledColor) o2.selected = true;
    color.appendChild(o2);
  });
  // 咀速（breathing 先用，100-1000ms，後端同驗）。
  var speed = document.createElement("input");
  speed.id = "gpLedSpeed_" + side + "_" + k;
  speed.type = "number";
  speed.min = "100";
  speed.max = "1000";
  speed.step = "50";
  speed.style.width = "76px";
  speed.value = parsed.ledSpeed || "500";
  speed.style.display = "none";
  var refill = function () {
    var isMouth = part.value === "mouth";
    var list = isMouth ? ["breathing", "off"]
      : (part.value === "eye"
        ? ["long", "flash", "chase", "dual", "stop"]
        : ["long", "flash", "breathe", "chase", "dual", "stop"]);
    preset.innerHTML = "";
    list.forEach(function (p) {
      var o3 = document.createElement("option");
      o3.value = p;
      o3.textContent = p;
      if (p === parsed.ledPreset) o3.selected = true;
      preset.appendChild(o3);
    });
    syncLedExtras();
  };
  var syncLedExtras = function () {
    var isMouth = part.value === "mouth";
    var stopping = (!isMouth && preset.value === "stop") || (isMouth && preset.value === "off");
    // 頭眼 stop 唔使色；咀 breathing 先使速，off 乜都唔使。
    color.style.display = (!isMouth && !stopping) ? "inline" : "none";
    speed.style.display = (isMouth && preset.value === "breathing") ? "inline" : "none";
  };
  part.onchange = refill;
  preset.onchange = syncLedExtras;
  refill();
  wrap.appendChild(part);
  wrap.appendChild(preset);
  wrap.appendChild(color);
  wrap.appendChild(speed);
  return wrap;
}

function gamepadFillActionSelect(sel, curId) {
  var acts = __gpActionsCache || [];
  if (!acts.length) return;
  var byGroup = {};
  acts.forEach(function (a) {
    var sub = gamepadActionGroupOf(a.id);
    var g = GAMEPAD_ACTION_SUB2KIND[sub] || "others";
    (byGroup[g] = byGroup[g] || []).push(a);
  });
  GAMEPAD_ACTION_KINDS5.forEach(function (g) {
    var list = byGroup[g];
    if (!list || !list.length) return;
    var og = document.createElement("optgroup");
    og.label = GAMEPAD_ACTION_KIND_NAMES[g] || g;
    list.forEach(function (a) {
      var o = document.createElement("option");
      o.value = a.id;
      o.textContent = gamepadActionDisplayName(a);
      if (a.id === curId) o.selected = true;
      og.appendChild(o);
    });
    sel.appendChild(og);
  });
  // 現值唔喺清單（刪咗的動作）：補一粒等用家睇到。
  if (curId) {
    var found = false;
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === curId) { found = true; break; }
    }
    if (!found) {
      var miss = document.createElement("option");
      miss.value = curId;
      miss.textContent = curId + " (?)";
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
}

// 兩邊緩存（status mapping.pad／kb；舊扁平包兼容當 pad）。
function gamepadCacheSide(side, map) {
  var key = side === "kb" ? "__gpKbCache" : "__gpPadCache";
  try { window[key] = JSON.parse(JSON.stringify(map)); } catch (e) { window[key] = map; }
}
function gamepadCachedSide(side) {
  return window[side === "kb" ? "__gpKbCache" : "__gpPadCache"] || {};
}

// 映射表顯隱：跟各自卡內映射開關（預設閂，記 localStorage；開左先 show）。
// 同執行開關（A 端直連掣）係兩回事。
function gamepadMappingShown(side) {
  try {
    return localStorage.getItem(side === "kb" ? "gamepad_mapping_kb" : "gamepad_mapping_pad") === "1";
  } catch (e) {
    return false;
  }
}

function gamepadMappingToggle(side) {
  var el = document.getElementById(side === "kb" ? "gamepadMappingKbToggle" : "gamepadMappingPadToggle");
  var on = !el || !!el.checked;
  try {
    localStorage.setItem(side === "kb" ? "gamepad_mapping_kb" : "gamepad_mapping_pad", on ? "1" : "0");
  } catch (e) { /* ignore */ }
  gamepadRenderMapping(null);
}

function gamepadSyncMappingToggles() {
  var p = document.getElementById("gamepadMappingPadToggle");
  if (p) p.checked = gamepadMappingShown("pad");
  var k = document.getElementById("gamepadMappingKbToggle");
  if (k) k.checked = gamepadMappingShown("kb");
}

function gamepadRenderMapping(mapping) {
  var padMap = null, kbMap = null;
  if (mapping && mapping.pad) {
    padMap = mapping.pad;
    kbMap = mapping.kb || {};
    gamepadCacheSide("pad", padMap);
    gamepadCacheSide("kb", kbMap);
  } else {
    padMap = gamepadCachedSide("pad");
    kbMap = gamepadCachedSide("kb");
  }
  gamepadRenderSideGrid("pad", GAMEPAD_PAD_GROUPS, padMap);
  gamepadRenderSideGrid("kb", GAMEPAD_KB_GROUPS, kbMap);
  // 動作清單／鈴聲名單後到：先畫骨架（下拉空），load 完再填兩區下拉。
  gamepadLoadActions().then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid) return;
      var sels = grid.querySelectorAll("select[id^='gpActionSel_" + side + "_']");
      for (var i = 0; i < sels.length; i++) {
        var k = sels[i].id.substring(("gpActionSel_" + side + "_").length);
        var cur = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k] || "none");
        // 已有 options（畫嗰陣已有數據）即跳過。
        if (sels[i].options.length <= 1) gamepadFillActionSelect(sels[i], cur.actionId);
      }
    });
  });
  gamepadLoadRingTitles().then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid2 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid2) return;
      var rsels = grid2.querySelectorAll("select[id^='gpRingSel_" + side + "_']");
      for (var j = 0; j < rsels.length; j++) {
        var k2 = rsels[j].id.substring(("gpRingSel_" + side + "_").length);
        var cur2 = gamepadParseValue((side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k2] || "none");
        if (rsels[j].options.length <= 2) gamepadFillRingSelect(rsels[j], cur2.ringSel);
      }
    });
  });
  // 顯隱：恒顯示。
  var pw = document.getElementById("gamepadMappingPadWrap");
  var kw = document.getElementById("gamepadMappingKbWrap");
  if (pw) pw.style.display = gamepadMappingShown("pad") ? "block" : "none";
  if (kw) kw.style.display = gamepadMappingShown("kb") ? "block" : "none";
}

function gamepadRenderSideGrid(side, groups, map) {
  var wrap = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
  if (!wrap) return;
  wrap.innerHTML = "";
  groups.forEach(function (grp) {
    var h = document.createElement("div");
    h.className = "servo-group-title";
    h.textContent = t("gamepad_group_" + grp.title);
    wrap.appendChild(h);
    grp.keys.forEach(function (k) {
      var parsed = gamepadParseValue(map[k] || "none");
      var row = document.createElement("div");
      row.className = "row";
      row.style.alignItems = "center";
      row.setAttribute("data-maprow", k);
      row.setAttribute("data-mapside", side);
      var label = document.createElement("span");
      label.style.minWidth = "90px";
      label.style.fontWeight = "bold";
      label.textContent = gamepadKeyLabel(k);
      var pfx = side + "_";
      var kind = document.createElement("select");
      kind.id = "gpSel_" + pfx + k;
      GAMEPAD_KINDS.forEach(function (c) {
        var opt = document.createElement("option");
        opt.value = c;
        opt.textContent = c === "action" ? "action:…" : (c === "servo" ? "servo#±"
          : (c === "music" ? "music♪" : (c === "ring" ? "ring🔔"
          : (c === "led" ? "led💡" : (c === "camera" ? "camera📷" : c)))));
        if (c === parsed.kind) opt.selected = true;
        kind.appendChild(opt);
      });
      kind.onchange = (function (kk) {
        return function () {
          var v = document.getElementById("gpSel_" + pfx + kk).value;
          var sw = document.getElementById("gpServoWrap_" + pfx + kk);
          var as = document.getElementById("gpActionSel_" + pfx + kk);
          var mu = document.getElementById("gpMusicSel_" + pfx + kk);
          var rg = document.getElementById("gpRingSel_" + pfx + kk);
          var lw = document.getElementById("gpLedWrap_" + pfx + kk);
          var cm = document.getElementById("gpCameraSel_" + pfx + kk);
          if (sw) sw.style.display = v === "servo" ? "inline" : "none";
          if (as) as.style.display = v === "action" ? "inline" : "none";
          if (mu) mu.style.display = v === "music" ? "inline" : "none";
          if (rg) rg.style.display = v === "ring" ? "inline" : "none";
          if (lw) lw.style.display = v === "led" ? "inline" : "none";
          if (cm) cm.style.display = v === "camera" ? "inline" : "none";
        };
      })(k);
      var servoWrap = gamepadBuildServoControls(side, k, parsed);
      var musicSel = document.createElement("select");
      musicSel.id = "gpMusicSel_" + pfx + k;
      musicSel.style.display = parsed.kind === "music" ? "inline" : "none";
      GAMEPAD_MUSIC_FNS.forEach(function (f) {
        var o = document.createElement("option");
        o.value = f;
        // music_ 去頭顯功能名；vol_up/vol_down 原樣（音量＋/−）。
        o.textContent = (f.indexOf("music_") === 0) ? f.substring(6) : f;
        if (f === parsed.musicFn) o.selected = true;
        musicSel.appendChild(o);
      });
      var actionSel = gamepadBuildActionSelect(side, k, parsed);
      var ringSel = gamepadBuildRingSelect(side, k, parsed);
      var ledWrap = gamepadBuildLedControls(side, k, parsed);
      var cameraSel = document.createElement("select");
      cameraSel.id = "gpCameraSel_" + pfx + k;
      cameraSel.style.display = parsed.kind === "camera" ? "inline" : "none";
      [["camera_photo", "影相"], ["face_start", "人臉追開"], ["face_stop", "人臉追停"],
       ["color_start", "顏色追開"], ["color_stop", "顏色追停"]].forEach(function (f) {
        var o = document.createElement("option");
        o.value = f[0];
        o.textContent = f[1];
        if (f[0] === parsed.cameraFn) o.selected = true;
        cameraSel.appendChild(o);
      });
      row.appendChild(label);
      row.appendChild(kind);
      row.appendChild(servoWrap);
      row.appendChild(musicSel);
      row.appendChild(ringSel);
      row.appendChild(ledWrap);
      row.appendChild(cameraSel);
      row.appendChild(actionSel);
      wrap.appendChild(row);
    });
  });
}

// 讀某行現時值（同舊逐行存同一拼法；action 未揀回 null 即成行跳過）。
function gamepadReadRowValue(side, k) {
  var pfx = side + "_";
  var kindEl = document.getElementById("gpSel_" + pfx + k);
  if (!kindEl) return null;
  var kind = kindEl.value;
  if (kind === "servo") {
    var ax = document.getElementById("gpServoAxis_" + pfx + k);
    var dr = document.getElementById("gpServoDir_" + pfx + k);
    return "servo" + (ax ? ax.value : "20") + (dr ? dr.value : "+");
  }
  if (kind === "music") {
    var mu = document.getElementById("gpMusicSel_" + pfx + k);
    return (mu && mu.value) ? mu.value : "music_play";
  }
  if (kind === "camera") {
    var cm = document.getElementById("gpCameraSel_" + pfx + k);
    return (cm && cm.value) ? cm.value : "camera_photo";
  }
  if (kind === "ring") {
    var rg = document.getElementById("gpRingSel_" + pfx + k);
    var rv = rg ? (rg.value || "").trim() : "";
    return rv || null;
  }
  if (kind === "led") {
    var lp = document.getElementById("gpLedPart_" + pfx + k);
    var pr = document.getElementById("gpLedPreset_" + pfx + k);
    var co = document.getElementById("gpLedColor_" + pfx + k);
    var sp = document.getElementById("gpLedSpeed_" + pfx + k);
    var part = lp ? lp.value : "head";
    var preset = pr ? pr.value : "flash";
    if (part === "mouth") {
      if (preset === "off") return "mouth:off";
      var speed = sp ? parseInt(sp.value, 10) : 500;
      if (!isFinite(speed)) speed = 500;
      speed = Math.max(100, Math.min(1000, speed));
      return "mouth:breathing:" + speed;
    }
    if (preset === "stop") return part + ":stop";
    var color = co ? co.value : "1";
    return part + ":" + preset + ":" + color;
  }
  if (kind === "action") {
    var as = document.getElementById("gpActionSel_" + pfx + k);
    var id = as ? (as.value || "").trim() : "";
    return id ? ("action:" + id) : null;
  }
  return kind;
}

// 成區一次過存：只送同緩存唔同的行，一個掣搞掂（取代逐行存）。
function gamepadSaveAll(side) {
  side = side || "pad";
  var groups = side === "kb" ? GAMEPAD_KB_GROUPS : GAMEPAD_PAD_GROUPS;
  var cache = gamepadCachedSide(side);
  var jobs = [];
  groups.forEach(function (grp) {
    grp.keys.forEach(function (k) {
      var v = gamepadReadRowValue(side, k);
      if (v === null || v === cache[k]) return;
      jobs.push({ key: k, action: v });
    });
  });
  var st = document.getElementById("gamepadScanState");
  if (!jobs.length) {
    if (st) st.textContent = t("gamepad_nothing_changed");
    return Promise.resolve();
  }
  if (st) st.textContent = t("gamepad_saving");
  var chain = Promise.resolve();
  jobs.forEach(function (j) {
    chain = chain.then(function () {
      return Alpha2Api.gamepadConfigSet({ side: side, key: j.key, action: j.action });
    });
  });
  return chain.then(function () {
    if (st) st.textContent = "";
    return gamepadRefresh();
  }).catch(function (err) {
    if (st) st.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadResetMapping(side) {
  side = side || "pad";
  return Alpha2Api.gamepadConfigSet({ side: side, reset: "1" }).then(function (json) {
    if (json && json.ok && json.mapping) gamepadCacheSide(side, json.mapping);
    return gamepadRefresh();
  });
}

// （B 端 Gamepad API 輪詢／開關已移除：本頁只做 A 端顯示＋映射。
// 示意圖亮燈＋搖桿點位全權經 WebSocket 沿驅動。）

// （B 端瀏覽器鍵盤監聽／開關已移除：示意圖亮燈全權經 WebSocket 沿驅動。）

// ---------------- 即時示意圖：手柄＋全鍵盤亮燈 ----------------
// 經 WebSocket gamepad_key 沿驅動（A 端物理鍵；gamepad/key HTTP 回聲亦經此）。

// 全鍵盤分區定義（真鍵盤排法：F 行收主區尾齊 backspace；prt/scr/paus 置導航頂）。
// 每格：[顯示字, 邏輯鍵, 闊度class or ""]。
var GAMEPAD_KB_MAIN = [
  [["esc", "esc", ""], ["F1", "f1", ""], ["F2", "f2", ""], ["F3", "f3", ""],
   ["F4", "f4", ""], ["F5", "f5", ""], ["F6", "f6", ""], ["F7", "f7", ""],
   ["F8", "f8", ""], ["F9", "f9", ""], ["F10", "f10", ""], ["F11", "f11", ""],
   ["F12", "f12", ""]],
  [["`", "grave", ""], ["1", "1", ""], ["2", "2", ""], ["3", "3", ""],
   ["4", "4", ""], ["5", "5", ""], ["6", "6", ""], ["7", "7", ""],
   ["8", "8", ""], ["9", "9", ""], ["0", "0", ""], ["-", "minus", ""],
   ["=", "equals", ""], ["←", "backspace", "wider"]],
  [["tab", "tab", "wide"], ["q", "q", ""], ["w", "w", ""], ["e", "e", ""],
   ["r", "r", ""], ["t", "t", ""], ["y", "y", ""], ["u", "u", ""],
   ["i", "i", ""], ["o", "o", ""], ["p", "p", ""], ["[", "lbracket", ""],
   ["]", "rbracket", ""], ["\\", "backslash", ""]],
  [["caps", "caps", "wide"], ["a", "a", ""], ["s", "s", ""], ["d", "d", ""],
   ["f", "f", ""], ["g", "g", ""], ["h", "h", ""], ["j", "j", ""],
   ["k", "k", ""], ["l", "l", ""], [";", "semicolon", ""], ["'", "quote", ""],
   ["enter", "enter", "wider"]],
  [["shift", "shift", "wide"], ["z", "z", ""], ["x", "x", ""], ["c", "c", ""],
   ["v", "v", ""], ["b", "b", ""], ["n", "n", ""], ["m", "m", ""],
   [",", "comma", ""], [".", "period", ""], ["/", "slash", ""],
   ["shift", "shift", "wide"]],
  [["ctrl", "ctrl", ""], ["alt", "alt", ""], ["space", "space", "space"],
   ["alt", "alt", ""], ["menu", "menu", ""], ["ctrl", "ctrl", ""]]
];
var GAMEPAD_KB_NAV = [
  [["prt", "print", ""], ["scr", "scroll", ""], ["paus", "pause", ""]],
  [["ins", "ins", ""], ["home", "home", ""], ["pgup", "pgup", ""]],
  [["del", "del", ""], ["end", "end", ""], ["pgdn", "pgdn", ""]],
  [["◀", "left", ""], ["▲", "up", ""], ["▼", "down", ""], ["▶", "right", ""]]
];
var GAMEPAD_KB_NUM = [
  [["7", "num_7", ""], ["8", "num_8", ""], ["9", "num_9", ""], ["/", "num_div", ""]],
  [["4", "num_4", ""], ["5", "num_5", ""], ["6", "num_6", ""], ["x", "num_mul", ""]],
  [["1", "num_1", ""], ["2", "num_2", ""], ["3", "num_3", ""], ["-", "num_sub", ""]],
  [["0", "num_0", ""], [".", "num_dot", ""], ["⏎", "num_enter", ""], ["+", "num_add", ""]]
];
function gamepadKbRowEl(cells) {
  var r = document.createElement("div");
  r.className = "kb-row";
  cells.forEach(function (cell) {
    if (!cell) {
      var gap = document.createElement("div");
      gap.className = "kb-gap";
      r.appendChild(gap);
      return;
    }
    var k = document.createElement("div");
    k.className = "kb-key" + (cell[2] ? " " + cell[2] : "");
    k.textContent = cell[0];
    k.setAttribute("data-kb", cell[1]);
    r.appendChild(k);
  });
  return r;
}

function gamepadBuildKeyboardDiagram() {
  var board = document.getElementById("kbBoardDiagram");
  if (!board || board.childElementCount > 0) return;
  var zones = document.createElement("div");
  zones.className = "kb-zones";
  var main = document.createElement("div");
  main.className = "kb-zone-main";
  GAMEPAD_KB_MAIN.forEach(function (row) { main.appendChild(gamepadKbRowEl(row)); });
  var nav = document.createElement("div");
  nav.className = "kb-zone-nav";
  GAMEPAD_KB_NAV.forEach(function (row) { nav.appendChild(gamepadKbRowEl(row)); });
  var num = document.createElement("div");
  num.className = "kb-zone-num";
  GAMEPAD_KB_NUM.forEach(function (row) { num.appendChild(gamepadKbRowEl(row)); });
  zones.appendChild(main);
  zones.appendChild(nav);
  zones.appendChild(num);
  board.appendChild(zones);
}

// 標點顯示返符號本身（; / , …），唔係英文名。
var GAMEPAD_KEY_LABELS = {
  minus: "-", equals: "=", lbracket: "[", rbracket: "]", backslash: "\\",
  semicolon: ";", quote: "'", comma: ",", period: ".", slash: "/", grave: "`"
};
function gamepadKeyLabel(k) {
  return GAMEPAD_KEY_LABELS[k] || k;
}

// 三處同亮：手柄圖＋鍵盤圖＋映射表行（a/b/x/y、方向鍵兩邊都有，齊亮合理）。
// 長按嗰行一直著，放手熄——映射表幾十行唔使慢慢搵。
// 顏色：該次事件有映射動作（非 none）藍燈，無映射綠燈；映射表行按自己嗰邊現值計色。
function gamepadLightLogical(logical, on, action) {
  if (!logical) return;
  var lit = !!on;
  var mapped = lit && action && action !== "none";
  var css = mapped ? "lit" : "lit-free";
  var cssRow = mapped ? "gprow-lit" : "gprow-free";
  var g = document.getElementById("gpcap-" + logical);
  if (g) {
    g.classList.remove("lit");
    g.classList.remove("lit-free");
    if (lit) g.classList.add(css);
  }
  var caps = document.querySelectorAll('[data-kb="' + logical + '"]');
  for (var i = 0; i < caps.length; i++) {
    caps[i].classList.remove("lit");
    caps[i].classList.remove("lit-free");
    if (lit) caps[i].classList.add(css);
  }
  var rows = document.querySelectorAll('[data-maprow="' + logical + '"]');
  for (var j = 0; j < rows.length; j++) {
    var side = rows[j].getAttribute("data-mapside");
    var cur = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[logical];
    var rowCls = (lit && cur && cur !== "none") ? "gprow-lit" : "gprow-free";
    rows[j].classList.remove("gprow-lit");
    rows[j].classList.remove("gprow-free");
    if (lit) rows[j].classList.add(rowCls);
  }
}

// 搖桿點位（nub 26px 置 64px 圈，中心 19px，滿偏 ±14px）。
function gamepadMoveNubs(lx, ly, rx, ry) {
  var ln = document.getElementById("gpnub-l");
  var rn = document.getElementById("gpnub-r");
  if (ln) {
    ln.style.left = (19 + Math.max(-1, Math.min(1, lx || 0)) * 14) + "px";
    ln.style.top = (19 + Math.max(-1, Math.min(1, ly || 0)) * 14) + "px";
  }
  if (rn) {
    rn.style.left = (19 + Math.max(-1, Math.min(1, rx || 0)) * 14) + "px";
    rn.style.top = (19 + Math.max(-1, Math.min(1, ry || 0)) * 14) + "px";
  }
}

// ---------------- 初始化（app-log.js init 呼叫） ----------------

function gamepadInitPage() {
  gamepadBuildKeyboardDiagram();
  gamepadSyncMappingToggles();
  return gamepadRefresh();
}
