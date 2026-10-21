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
var GAMEPAD_MAX_PER_KEY = 6;
var GAMEPAD_KINDS = ["none", "stop", "servo", "music", "radio", "ring", "led", "camera", "sensor", "voice", "action"];
var GAMEPAD_KIND_KEYS = { none: "gamepad_kind_none", stop: "gamepad_kind_stop",
  servo: "gamepad_kind_servo", music: "gamepad_kind_music", radio: "gamepad_kind_radio",
  ring: "gamepad_kind_ring",
  led: "gamepad_kind_led", camera: "gamepad_kind_camera", sensor: "gamepad_kind_sensor",
  voice: "gamepad_kind_voice", action: "gamepad_kind_action" };
var GAMEPAD_VOICE_FIXED = [["xiaozhi_toggle", "gamepad_voice_xiaozhi"],
  ["vosk", "gamepad_voice_vosk"]];

// Multi-action helpers: one key holds up to GAMEPAD_MAX_PER_KEY actions,
// stored newline-separated (same as backend GamepadCenter).
function gamepadSplitMulti(v) {
  if (v == null) return ["none"];
  var parts = String(v).split(/\r\n|\r|\n/);
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var s = parts[i].replace(/^\s+|\s+$/g, "");
    if (s) out.push(s);
  }
  if (!out.length) return ["none"];
  return out;
}
function gamepadJoinMulti(list) {
  var out = [];
  for (var i = 0; i < list.length; i++) {
    var s = list[i] == null ? "" : String(list[i]).replace(/^\s+|\s+$/g, "");
    if (s) out.push(s);
  }
  return out.join("\n");
}
function gamepadSubId(base, side, k, idx) {
  return base + "_" + side + "_" + k + "__" + idx;
}
function gamepadParseSubKeyId(id, prefix) {
  var rest = id.substring(prefix.length);
  var sep = rest.lastIndexOf("__");
  if (sep < 0) return { key: rest, idx: 0 };
  var idx = parseInt(rest.substring(sep + 2), 10);
  if (!isFinite(idx)) idx = 0;
  return { key: rest.substring(0, sep), idx: idx };
}

// 相機（同後端 isCameraAction 對齊；追蹤係 toggle：face_toggle／color_toggle）。
var GAMEPAD_CAMERA_FNS = ["camera_photo", "face_toggle", "color_toggle", "video_toggle"];

// 傳感器 toggle（同後端 isSensorAction 對齊；全部單鍵開關）。
var GAMEPAD_SENSOR_FNS = ["sonar_toggle", "pir_toggle", "accel_toggle"];
var GAMEPAD_SENSOR_FN_KEYS = { sonar_toggle: "gamepad_sen_sonar", pir_toggle: "gamepad_sen_pir",
  accel_toggle: "gamepad_sen_accel" };

// 轉語言原地重標用（值→i18n key；色碼另見 GAMEPAD_LED_COLOR_KEYS／dual 三對）。
var GAMEPAD_CAM_FN_KEYS = { camera_photo: "gamepad_cam_photo", face_toggle: "gamepad_cam_face",
  color_toggle: "gamepad_cam_color", video_toggle: "gamepad_cam_video" };
var GAMEPAD_RING_FIXED_KEYS = { ring_phone: "gamepad_ring_phone", ring_stop: "gamepad_ring_stop",
  ring_random: "gamepad_ring_random" };
var GAMEPAD_RING_TYPE_KEYS = { phone: "gamepad_ring_type_phone",
  notification: "gamepad_ring_type_notification" };
var GAMEPAD_LED_PART_KEYS = { head: "gamepad_led_head", eye: "gamepad_led_eye",
  mouth: "gamepad_led_mouth" };
var GAMEPAD_LED_PRESET_KEYS = { long: "gamepad_led_long", flash: "gamepad_led_flash",
  breathe: "gamepad_led_breathe", chase: "gamepad_led_chase", dual: "gamepad_led_dual",
  stop: "gamepad_led_stop", off: "gamepad_led_off", breathing: "gamepad_led_breathing",
  random: "gamepad_led_random" };
var GAMEPAD_DUAL_KEYS = { 3: "gamepad_dual_bg", 4: "gamepad_dual_rg", 5: "gamepad_dual_rb" };

// LED 色（色 1紅2綠3藍4黃5紫6青7白）。
var GAMEPAD_LED_COLOR_KEYS = [["1", "color_name_red"], ["2", "color_name_green"],
  ["3", "color_name_blue"], ["4", "color_name_yellow"], ["5", "color_name_purple"],
  ["6", "color_name_cyan"], ["7", "color_name_white"]];

// 音樂／音量／燈效功能（同後端 MUSIC_ACTIONS＋vol_up／vol_down 對齊；toggle＝播緊停／
// 停緊續／無歌播首首；random＝隨機一首；disco_toggle＝節奏燈開關；
// filler_toggle＝播歌隨機動作開關；電台獨立成種，唔喺度）。
var GAMEPAD_MUSIC_FNS = ["music_play", "music_next", "music_prev", "music_pause", "music_resume",
  "music_toggle", "music_stop", "music_random", "disco_toggle", "filler_toggle",
  "vol_up", "vol_down"];
var GAMEPAD_MUSIC_FN_KEYS = { music_play: "gamepad_mfn_play", music_next: "gamepad_mfn_next",
  music_prev: "gamepad_mfn_prev", music_pause: "gamepad_mfn_pause", music_resume: "gamepad_mfn_resume",
  music_toggle: "gamepad_mfn_toggle", music_stop: "gamepad_mfn_stop", music_random: "gamepad_mfn_random",
  disco_toggle: "gamepad_mfn_disco", filler_toggle: "gamepad_mfn_filler",
  vol_up: "gamepad_mfn_volup", vol_down: "gamepad_mfn_voldn", "eq:": "gamepad_mfn_eq" };
// 電台種（單 toggle，將來加preset台都喺度加）。
var GAMEPAD_RADIO_FN_KEYS = { radio_toggle: "gamepad_mfn_radio" };

// 收藏電台緩存（audio/radio/favorites/list 活數據：用戶成日加減，每次 render 重抓）。
var __gpRadioFavsCache = null;

function gamepadLoadRadioFavs(force) {
  if (!force && __gpRadioFavsCache) return Promise.resolve(__gpRadioFavsCache);
  return Alpha2Api.audioRadioFavoritesList().then(function (json) {
    var out = [];
    if (json && json.ok && json.favorites) {
      json.favorites.forEach(function (f) {
        if (f && f.name) out.push(f);
      });
    }
    __gpRadioFavsCache = out;
    return out;
  }).catch(function () {
    __gpRadioFavsCache = __gpRadioFavsCache || [];
    return __gpRadioFavsCache;
  });
}

// 建電台下拉（開關＋收藏；收藏單後到即補）。
function gamepadBuildRadioSelect(side, k, idx, parsed) {
  var sel = document.createElement("select");
  sel.id = gamepadSubId("gpRadioSel", side, k, idx);
  sel.style.maxWidth = "220px";
  sel.style.display = parsed.kind === "radio" ? "inline" : "none";
  gamepadFillRadioSelect(sel, parsed.radioFn);
  return sel;
}

// 填收藏（存值 radio:<uuid>；舊值唔喺度即補 (?)；無收藏即得開關）。
function gamepadFillRadioSelect(sel, cur) {
  if (!sel) return;
  for (var i = sel.options.length - 1; i >= 0; i--) {
    if (sel.options[i].value.indexOf("radio:") === 0) sel.remove(i);
  }
  // 舊 radio_toggle 存值：保留返粒（唔再主動提供，免無啦啦洗走人哋個值）。
  if (cur === "radio_toggle") {
    var leg = document.createElement("option");
    leg.value = cur;
    leg.textContent = t("gamepad_mfn_radio");
    leg.selected = true;
    sel.appendChild(leg);
  }
  var favs = __gpRadioFavsCache || [];
  favs.forEach(function (f) {
    var key = f.stationuuid || f.name;
    var v = "radio:" + key;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = f.name;
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("radio:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      // 舊名式 radio:<名>但個名喺收藏入面：唔打 (?)，照播到。
      var bare = cur.substring(6);
      var known = false;
      favs.forEach(function (f) { if (f.name === bare) known = true; });
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = bare + (known ? "" : " (?)");
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
  sel.dataset.radioFilled = favs.length ? "1" : "";
}

// 動作 5 大類（sub→類映射；細 sub 名唔再逐個列）。
// basic＝基本（移動／手勢／頭部），dance＝舞蹈，story＝寓言故事，
// yoga＝瑜伽（站立平衡／伸展／踢腿動態），others＝其餘＋未分類。
var GAMEPAD_ACTION_KINDS5 = ["basic", "dance", "story", "yoga", "others"];
var GAMEPAD_ACTION_KIND_KEYS = { basic: "gamepad_act_basic", dance: "gamepad_act_dance",
  story: "gamepad_act_story", yoga: "gamepad_act_yoga", others: "gamepad_act_others" };
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
  var act = d.action || "-";
  try { act = String(act).split(/\r\n|\r|\n/).join(" + "); } catch (e) {}
  var s = (k === "unmapped" && d.code != null ? (k + "(" + d.code + ")") : k)
    + arrow + " → " + act + " [" + (d.source || "-") + "]"
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
// 快撳閃兩下去重：本地撳落即樂觀著燈、放手即熄，WS 回聲（down／up）遲到就會
// 著多鑊熄多鑊——睇落似撳咗兩下。自己兩張圖／campad 發出嘅回聲（source panel／
// campad）要同本地狀態對：放咗手嘅 down 回聲唔再著（1 秒內舊回聲先吞，
// 免殺咗第二部機／第二隻手指真係撳緊）；撳緊嗰陣嘅 up 回聲唔熄（等自己放手先熄）。
// 實體（robot）／脚本回聲無本地樂觀燈，照舊全收。
// 文字（最後按鍵）唔受影響，照刷。
var GAMEPAD_ECHO_STALE_MS = 1000;
var __gpDiagLastUpAt = {};

function gamepadLocalHeld(logical) {
  if (!logical) return false;
  try {
    if ((__gpDiagHold["pad:" + logical] || 0) > 0) return true;
    if ((__gpDiagHold["kb:" + logical] || 0) > 0) return true;
  } catch (e) {}
  try {
    if (__cameraPadDown[logical]) return true;
  } catch (e2) {}
  return false;
}

function gamepadNoteLocalUp(side, key) {
  try {
    __gpDiagLastUpAt[(side === "kb" ? "kb" : "pad") + ":" + key] = Date.now();
  } catch (e) {}
}

function gamepadEchoStaleDown(logical) {
  var lastUp = 0;
  try {
    lastUp = Math.max(__gpDiagLastUpAt["pad:" + logical] || 0,
      __gpDiagLastUpAt["kb:" + logical] || 0);
  } catch (e) {}
  if (!lastUp) return false;
  try {
    return (Date.now() - lastUp) < GAMEPAD_ECHO_STALE_MS;
  } catch (e2) {
    return false;
  }
}

// app-log.js appendLog() 收到 gamepad_key／gamepad_bt／gamepad_axis 即轉入呢度。
function onGamepadKeyEvent(type, data) {
  if (!data || typeof data !== "object") return;
  if (type === "gamepad_key") {
    var src = "";
    try { src = String(data.source || ""); } catch (e0) {}
    var selfSrc = (src === "panel" || src === "campad");
    var isDown = data.pressed !== false;
    if (selfSrc && isDown && !gamepadLocalHeld(data.key) && gamepadEchoStaleDown(data.key)) {
      // 自己頭先快撳快放嘅遲到 down 回聲：燈唔好再著（文字照刷，等用家睇到撳過）。
      gamepadSetLastText(gamepadLastText(data));
      return;
    }
    if (selfSrc && !isDown && gamepadLocalHeld(data.key)) {
      // 自己／另一隻手指仲撳緊，遲到／別人嘅 up 回聲唔好熄燈。
      return;
    }
    // 兩張圖＋映射行跟亮滅（色跟有無映射：WS 事件帶 action）。
    gamepadLightLogical(data.key, data.pressed !== false, data.action);
    // 放開唔刷文字（後端 last 都唔記放開；呢度雙保險，免顯示閃去 ↑✗）。
    if (data.pressed === false) return;
    gamepadSetLastText(gamepadLastText(data));
  } else if (type === "gamepad_axis") {
    // A 端搖桿經呢度郁點。
    gamepadMoveNubs(Number(data.lx || 0), Number(data.ly || 0),
      Number(data.rx || 0), Number(data.ry || 0));
    try { cameraPadMirrorAxis(data); } catch (e) {}
  } else if (type === "gamepad_state") {
    // 映射 toggle 做咗，機身即推新態：掣即時跟（唔使入版重讀）。
    gamepadApplyToggleState(data);
  } else if (type === "gamepad_bt" && data.event) {
    // bond／scan_done 落 Event Log 之餘，順手將管理卡現狀拉新一次。
    if (data.event === "bond" || data.event === "scan_done" || data.event === "unbond") {
      if (typeof gamepadRefresh === "function") {
        setTimeout(gamepadRefresh, 1500);
      }
    }
  }
}

// 映射 toggle 新態直寫掣（gamepad_state 事件；同各頁入頁 sync 同一結論，唔經佢哋唔使等）。
function gamepadApplyToggleState(d) {
  if (!d || typeof d !== "object") return;
  try {
    if (d.domain === "sonar" && typeof d.thresholdCm === "number") {
      var th = Math.max(0, Math.min(100, d.thresholdCm));
      var slider = document.getElementById("sonarDist");
      var val = document.getElementById("sonarDistVal");
      var tg = document.getElementById("sonarToggle");
      if (slider) slider.value = String(th);
      if (val) val.textContent = th + " cm";
      if (tg) tg.checked = th > 0;
      try { sonarThresholdCm = th; } catch (e) {}
      if (typeof drawSonarChart === "function") { try { drawSonarChart(); } catch (e) {} }
    } else if (d.domain === "pir" && typeof d.enabled === "boolean") {
      var hw = document.getElementById("alpha2Pir");
      if (hw) hw.checked = d.enabled;
    } else if (d.domain === "accel" && typeof d.enabled === "boolean") {
      var ac = document.getElementById("accelToggle");
      if (ac) ac.checked = d.enabled;
    } else if (d.domain === "disco" && typeof d.enabled === "boolean") {
      var dt = document.getElementById("musicDiscoToggle");
      if (dt) dt.checked = d.enabled;
      var dl = document.getElementById("musicDiscoStateLabel");
      if (dl) dl.textContent = t(d.enabled ? "music_disco_on" : "music_disco_off");
      try { musicDiscoEnabled = d.enabled; } catch (e) {}
    } else if (d.domain === "filler" && typeof d.enabled === "boolean") {
      var ft = document.getElementById("musicFillerToggle");
      if (ft) ft.checked = d.enabled;
      var fl = document.getElementById("musicFillerStateLabel");
      if (fl) fl.textContent = t(d.enabled ? "music_filler_on" : "music_filler_off");
    } else if (d.domain === "facetrack" && typeof d.running === "boolean") {
      try { faceTrackRunning = d.running; } catch (e) {}
      var ftg = document.getElementById("faceTrackToggle");
      if (ftg) ftg.checked = d.running;
      if (d.running) {
        try { faceTrackStartPolling(); } catch (e) {}
        try { faceTrackRefreshStatus(); } catch (e) {}
      } else {
        try { faceTrackStopPolling(); } catch (e) {}
        try { faceTrackRenderBox({ running: false }); } catch (e) {}
      }
    } else if (d.domain === "colortrack" && typeof d.running === "boolean") {
      try { colorTrackRunning = d.running; } catch (e) {}
      var ctg = document.getElementById("colorTrackToggle");
      if (ctg) ctg.checked = d.running;
      if (d.running) {
        try { colorTrackStartPolling(); } catch (e) {}
        try { colorTrackRefreshStatus(); } catch (e) {}
      } else {
        try { colorTrackStopPolling(); } catch (e) {}
        try { colorTrackRenderBox({ running: false }); } catch (e) {}
      }
    }
  } catch (e) {}
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

// 動作清單緩存（機身 action/list 活數據優先，唔得先用內嵌 203 catalog）。
// 分組經 action_classification.json（靜態，同目錄 fetch）。
var __gpActionsCache = null;
var __gpActionCatCache = null;
// id→動作對象（轉語言原地重標動作名用；填下拉嗰陣順手記）。
var __gpActionById = {};

function gamepadActionDisplayName(a) {
  // 淨顯中／英文名，數字 id（14xxxxx／15xxxxx）唔顯示；存值照用 id，唔影響。
  return (uiLang === "en" ? (a.nameEn || a.nameCn) : (a.nameCn || a.nameEn)) || a.id;
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
// ring_phone/ring_stop/ring_random/ring:[phone:|notification:]<title> 歸 ring 種；head:/eye: 歸 led 種；
// servo<N>± 拆轴向；action:<id> 拆 id。
function gamepadParseValue(v) {
  var out = { kind: "none", servoId: 20, servoDir: "+", musicFn: "music_play",
    ringSel: "ring_phone", ledPart: "head", ledPreset: "flash", ledColor: "1",
    ledSpeed: "500", cameraFn: "camera_photo", sensorFn: "sonar_toggle",
    voiceFn: "xiaozhi_toggle", radioFn: "radio_toggle", eqPreset: "normal", actionId: "" };
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
  if (GAMEPAD_SENSOR_FNS.indexOf(v) !== -1) {
    out.kind = "sensor";
    out.sensorFn = v;
    return out;
  }
  if (v === "ring_phone" || v === "ring_stop" || v === "ring_random" || v.indexOf("ring:") === 0) {
    out.kind = "ring";
    out.ringSel = v;
    return out;
  }
  if (v === "head:stop" || v === "eye:stop" || v === "mouth:off"
      || v === "head:random" || v === "eye:random"
      || v.match(/^(head|eye):[a-z]+:[1-7]$/) || v.match(/^mouth:breathing:\d+$/)) {
    out.kind = "led";
    var lp = v.split(":");
    out.ledPart = lp[0];
    out.ledPreset = (lp[0] === "mouth" && lp[1] === "breathing") ? "breathing" : lp[1];
    out.ledColor = lp[2] || "1";
    if (lp[0] === "mouth" && lp[1] === "breathing") out.ledSpeed = lp[2] || "500";
    return out;
  }
  if (v === "radio_toggle" || v.indexOf("radio:") === 0) {
    out.kind = "radio";
    out.radioFn = v;
    return out;
  }
  if (GAMEPAD_MUSIC_FNS.indexOf(v) !== -1 || v.indexOf("eq:") === 0) {
    out.kind = "music";
    if (v.indexOf("eq:") === 0) {
      out.musicFn = "eq:";
      out.eqPreset = v.substring(3) || "normal";
    } else {
      out.musicFn = v;
    }
    return out;
  }
  if (v === "xiaozhi_toggle" || v === "vosk_toggle" || v.indexOf("vosk:") === 0) {
    out.kind = "voice";
    out.voiceFn = v;
    return out;
  }
  var m = v.match(/^servo(\d{1,2})([+-])$/);
  if (m) {
    out.kind = "servo";
    out.servoId = Math.max(1, Math.min(22, parseInt(m[1], 10) || 22));
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

// 建舵機單一下拉（#1+／#1-／#2+…#22- 共 44 格；id 按邊分名前綴，兩區同名鍵唔撞）。
function gamepadBuildServoControls(side, k, idx, parsed) {
  var wrap = document.createElement("span");
  wrap.id = gamepadSubId("gpServoWrap", side, k, idx);
  wrap.style.display = parsed.kind === "servo" ? "inline" : "none";
  var sel = document.createElement("select");
  sel.id = gamepadSubId("gpServo", side, k, idx);
  var cur = "servo" + parsed.servoId + parsed.servoDir;
  for (var i = 1; i <= 22; i++) {
    ["+", "-"].forEach(function (d) {
      var o = document.createElement("option");
      o.value = "servo" + i + d;
      o.textContent = "#" + i + d;
      if (o.value === cur) o.selected = true;
      sel.appendChild(o);
    });
  }
  wrap.appendChild(sel);
  return wrap;
}

// 動作所屬大類（同 action tab 同一規則：按動作本身 type 分；緩存未到先用分類表 sub 頂住）。
function gamepadActionKindOf(id) {
  var acts = __gpActionsCache || [];
  for (var i = 0; i < acts.length; i++) {
    if (acts[i] && acts[i].id === id && typeof categoryOf === "function") {
      return categoryOf(acts[i].type);
    }
  }
  var sub = gamepadActionGroupOf(id);
  return GAMEPAD_ACTION_SUB2KIND[sub] || "others";
}

// 建 203 動作級聯下拉（先大類、再入面動作；數據未到先得提示，load 完補）。
function gamepadBuildActionSelect(side, k, idx, parsed) {
  var wrap = document.createElement("span");
  wrap.id = gamepadSubId("gpActionWrap", side, k, idx);
  wrap.style.display = parsed.kind === "action" ? "inline" : "none";
  var kindSel = document.createElement("select");
  kindSel.id = gamepadSubId("gpActionKind", side, k, idx);
  GAMEPAD_ACTION_KINDS5.forEach(function (g) {
    var ko = document.createElement("option");
    ko.value = g;
    ko.textContent = t(GAMEPAD_ACTION_KIND_KEYS[g] || g);
    kindSel.appendChild(ko);
  });
  var sel = document.createElement("select");
  sel.id = gamepadSubId("gpActionSel", side, k, idx);
  sel.style.maxWidth = "220px";
  wrap.appendChild(kindSel);
  wrap.appendChild(sel);
  kindSel.onchange = (function (s, kk, ii) {
    return function () {
      gamepadFillActionRow(s, kk, ii, this.value, "");
    };
  })(side, k, idx);
  gamepadFillActionSelectEl(kindSel, sel, null, parsed.actionId);
  return wrap;
}

// 填某行個動作下拉（cat 空即按現值自動判定大類；curId 唔喺類入面即補 (?) 粒）。
function gamepadFillActionRow(side, k, idx, cat, curId) {
  if (idx == null) idx = 0;
  gamepadFillActionSelectEl(
    document.getElementById(gamepadSubId("gpActionKind", side, k, idx)),
    document.getElementById(gamepadSubId("gpActionSel", side, k, idx)), cat, curId);
}

// element 版（build 嗰陣未入 DOM，唔用 getElementById）。
// dataset.actionFilled＝用有數據嗰次填過（late-fill 唔好數 options：miss 粒都計數）。
function gamepadFillActionSelectEl(kindSel, sel, cat, curId) {
  if (!sel) return;
  if (!cat) cat = (curId ? gamepadActionKindOf(curId) : GAMEPAD_ACTION_KINDS5[0]);
  if (kindSel) kindSel.value = cat;
  sel.innerHTML = "";
  var ph = document.createElement("option");
  ph.value = "";
  ph.textContent = "— " + t("gamepad_pick_action") + " —";
  sel.appendChild(ph);
  var acts = __gpActionsCache || [];
  acts.forEach(function (a) {
    if (a && a.id) __gpActionById[a.id] = a;
    if (gamepadActionKindOf(a.id) !== cat) return;
    var o = document.createElement("option");
    o.value = a.id;
    o.textContent = gamepadActionDisplayName(a);
    if (a.id === curId) o.selected = true;
    sel.appendChild(o);
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
  sel.dataset.actionFilled = acts.length ? "1" : "";
}

// 鈴聲名緩存（分電話／通知兩表；audio/ringtones/list 活數據，同 action 清單一樣懶載）。
var __gpRingTitlesCache = {};

// 值裡抽鈴聲表名（唔係鈴聲即回 phone；舊式 ring: 歸 phone 表搵名）。
function gamepadRingTypeOf(v) {
  if (v && v.indexOf("ring:notification:") === 0) return "notification";
  return "phone";
}

// 值裡抽裸 title（fixed 三粒回 ""；舊式去 ring: 頭）。
function gamepadRingBareTitle(v) {
  if (!v) return "";
  if (v.indexOf("ring:notification:") === 0) return v.substring("ring:notification:".length);
  if (v.indexOf("ring:phone:") === 0) return v.substring("ring:phone:".length);
  if (v.indexOf("ring:") === 0) return v.substring("ring:".length);
  return "";
}

function gamepadLoadRingTitles(type) {
  type = (type === "notification") ? "notification" : "phone";
  if (__gpRingTitlesCache[type]) return Promise.resolve(__gpRingTitlesCache[type]);
  var apiType = (type === "notification") ? "notification" : "ringtone";
  return Alpha2Api.audioRingtonesList({ type: apiType }).then(function (json) {
    var out = [];
    if (json && json.ok && json.sounds) {
      json.sounds.forEach(function (s) {
        if (s && s.title) out.push(s.title);
      });
    }
    __gpRingTitlesCache[type] = out;
    return out;
  }).catch(function () {
    __gpRingTitlesCache[type] = __gpRingTitlesCache[type] || [];
    return __gpRingTitlesCache[type];
  });
}

// 建鈴聲兩下拉（系統預設電話鈴＋鈴聲停＋隨機＋電話／通知表；名單後到即補）。
function gamepadBuildRingSelect(side, k, idx, parsed) {
  var wrap = document.createElement("span");
  wrap.id = gamepadSubId("gpRingWrap", side, k, idx);
  wrap.style.display = parsed.kind === "ring" ? "inline" : "none";
  var typeSel = document.createElement("select");
  typeSel.id = gamepadSubId("gpRingType", side, k, idx);
  [["phone", "gamepad_ring_type_phone"], ["notification", "gamepad_ring_type_notification"]].forEach(function (p) {
    var to = document.createElement("option");
    to.value = p[0];
    to.textContent = t(p[1]);
    if (p[0] === gamepadRingTypeOf(parsed.ringSel)) to.selected = true;
    typeSel.appendChild(to);
  });
  var sel = document.createElement("select");
  sel.id = gamepadSubId("gpRingSel", side, k, idx);
  sel.style.maxWidth = "200px";
  var fixed = [["ring_phone", "gamepad_ring_phone"], ["ring_stop", "gamepad_ring_stop"], ["ring_random", "gamepad_ring_random"]];
  fixed.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f[0];
    o.textContent = t(f[1]);
    if (parsed.ringSel === f[0]) o.selected = true;
    sel.appendChild(o);
  });
  wrap.appendChild(typeSel);
  wrap.appendChild(sel);
  typeSel.onchange = (function (s, kk, ii) {
    return function () {
      var sel2 = document.getElementById(gamepadSubId("gpRingSel", s, kk, ii));
      var curText = "";
      if (sel2 && sel2.selectedIndex >= 0) curText = sel2.options[sel2.selectedIndex].text;
      gamepadFillRingSelect(sel2, this.value, "");
      // 同名優先跟過去新表（兩表撞名常見；fixed 三粒名唔會撞 title）。
      if (sel2 && curText) {
        for (var i = 0; i < sel2.options.length; i++) {
          if (sel2.options[i].text === curText && sel2.options[i].value.indexOf("ring:") === 0) {
            sel2.selectedIndex = i;
            break;
          }
        }
      }
    };
  })(side, k, idx);
  gamepadFillRingSelect(sel, gamepadRingTypeOf(parsed.ringSel), parsed.ringSel);
  return wrap;
}

function gamepadFillRingSelect(sel, type, cur) {
  if (!sel) return;
  type = (type === "notification") ? "notification" : "phone";
  var prefix = (type === "notification") ? "ring:notification:" : "ring:phone:";
  var titles = __gpRingTitlesCache[type] || [];
  // 重填先清舊名單（fixed 三粒無冒號，保留；miss／舊值有冒號，一併清再補）。
  for (var i = sel.options.length - 1; i >= 0; i--) {
    if (sel.options[i].value.indexOf("ring:") === 0) sel.remove(i);
  }
  if (!titles.length) {
    // 名單未到：現值係鈴聲即補一粒等睇到（同 action 那個做法）。
    if (cur && cur.indexOf("ring:") === 0) {
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = gamepadRingBareTitle(cur);
      miss.selected = true;
      sel.appendChild(miss);
    }
    sel.dataset.ringType = type;
    sel.dataset.ringFilled = "";
    return;
  }
  titles.forEach(function (title) {
    var v = prefix + title;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = title;
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("ring:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      // 舊式 ring: 值但個名喺表入面（僅缺前綴）：唔打 (?)，照播到。
      var bare = gamepadRingBareTitle(cur);
      var miss2 = document.createElement("option");
      miss2.value = cur;
      miss2.textContent = bare + (titles.indexOf(bare) === -1 ? " (?)" : "");
      miss2.selected = true;
      sel.appendChild(miss2);
    }
  }
  sel.dataset.ringType = type;
  sel.dataset.ringFilled = "1";
}

// 建 LED 三下拉（部位＋效果＋色；效果跟部位變，dual 換 3 對色單，色名跟語言）。
// LED 色下拉（dual 照 LED tab 同一套：3=藍綠／4=紅綠／5=紅藍，其他 code 出嚟都係藍綠
// 唔擺出嚟，見 app-led.js DUAL_COLORS；現值唔啱位即歸第一粒）。
function gamepadFillLedColors(colorSel, dual, curVal) {
  if (!colorSel) return;
  colorSel.innerHTML = "";
  var list = dual ? [["3", "gamepad_dual_bg"], ["4", "gamepad_dual_rg"], ["5", "gamepad_dual_rb"]]
    : GAMEPAD_LED_COLOR_KEYS;
  var want = String(curVal != null ? curVal : "1");
  var hasWant = false;
  list.forEach(function (c) { if (c[0] === want) hasWant = true; });
  if (!hasWant) want = list[0][0];
  list.forEach(function (c) {
    var o = document.createElement("option");
    o.value = c[0];
    o.textContent = t(c[1]);
    if (c[0] === want) o.selected = true;
    colorSel.appendChild(o);
  });
}

function gamepadBuildLedControls(side, k, idx, parsed) {
  var wrap = document.createElement("span");
  wrap.id = gamepadSubId("gpLedWrap", side, k, idx);
  wrap.style.display = parsed.kind === "led" ? "inline" : "none";
  var part = document.createElement("select");
  part.id = gamepadSubId("gpLedPart", side, k, idx);
  [["head", "gamepad_led_head"], ["eye", "gamepad_led_eye"], ["mouth", "gamepad_led_mouth"]].forEach(function (p) {
    var o = document.createElement("option");
    o.value = p[0];
    o.textContent = t(p[1]);
    if (p[0] === parsed.ledPart) o.selected = true;
    part.appendChild(o);
  });
  var preset = document.createElement("select");
  preset.id = gamepadSubId("gpLedPreset", side, k, idx);
  var color = document.createElement("select");
  color.id = gamepadSubId("gpLedColor", side, k, idx);
  gamepadFillLedColors(color, false, parsed.ledColor);
  // 咀速（breathing 先用，100-1000ms，後端同驗）。
  var speed = document.createElement("input");
  speed.id = gamepadSubId("gpLedSpeed", side, k, idx);
  speed.type = "number";
  speed.min = "100";
  speed.max = "1000";
  speed.step = "50";
  speed.style.width = "76px";
  speed.value = parsed.ledSpeed || "500";
  speed.style.display = "none";
  var refill = function () {
    var isMouth = part.value === "mouth";
    var list = isMouth ? [["breathing", "gamepad_led_breathing"], ["off", "gamepad_led_off"]]
      : (part.value === "eye"
        ? [["long", "gamepad_led_long"], ["flash", "gamepad_led_flash"], ["chase", "gamepad_led_chase"], ["dual", "gamepad_led_dual"], ["stop", "gamepad_led_stop"], ["random", "gamepad_led_random"]]
        : [["long", "gamepad_led_long"], ["flash", "gamepad_led_flash"], ["breathe", "gamepad_led_breathe"], ["chase", "gamepad_led_chase"], ["dual", "gamepad_led_dual"], ["stop", "gamepad_led_stop"], ["random", "gamepad_led_random"]]);
    preset.innerHTML = "";
    list.forEach(function (p) {
      var o3 = document.createElement("option");
      o3.value = p[0];
      o3.textContent = t(p[1]);
      if (p[0] === parsed.ledPreset) o3.selected = true;
      preset.appendChild(o3);
    });
    syncLedExtras();
  };
  var syncLedExtras = function () {
    var isMouth = part.value === "mouth";
    var stopping = (!isMouth && preset.value === "stop") || (isMouth && preset.value === "off");
    var noColor = stopping || (!isMouth && preset.value === "random");
    // dual 換 3 對色單，其餘換返 7 色（現值保留，唔啱位先歸一）。
    gamepadFillLedColors(color, !isMouth && preset.value === "dual", color.value);
    // 頭眼 stop／random 唔使色；咀 breathing 先使速，off 乜都唔使。
    color.style.display = (!isMouth && !noColor) ? "inline" : "none";
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
        var parsed = gamepadParseSubKeyId(sels[i].id, "gpActionSel_" + side + "_");
        var k = parsed.key;
        var stored = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k] || "none";
        var parts = gamepadSplitMulti(stored);
        var cur = gamepadParseValue(parts[parsed.idx] || "none");
        if (!sels[i].dataset.actionFilled) gamepadFillActionRow(side, k, parsed.idx, null, cur.actionId);
      }
    });
  });
  Promise.all([gamepadLoadRingTitles("phone"), gamepadLoadRingTitles("notification")]).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid2 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid2) return;
      var rsels = grid2.querySelectorAll("select[id^='gpRingSel_" + side + "_']");
      for (var j = 0; j < rsels.length; j++) {
        var parsed2 = gamepadParseSubKeyId(rsels[j].id, "gpRingSel_" + side + "_");
        var k2 = parsed2.key;
        var stored2 = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k2] || "none";
        var parts2 = gamepadSplitMulti(stored2);
        var cur2 = gamepadParseValue(parts2[parsed2.idx] || "none");
        var rt = gamepadRingTypeOf(cur2.ringSel);
        // 表啱＋用有數據填過即跳過；表唔啱／未用數據填過即補。
        if (rsels[j].dataset.ringType !== rt || !rsels[j].dataset.ringFilled) {
          gamepadFillRingSelect(rsels[j], rt, cur2.ringSel);
        }
      }
    });
  });
  // 包單部部機唔同，每次 render 重抓（唔用舊緩存）；淨係填得提示一粒嗰啲。
  gamepadLoadVoskPacks(true).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid3 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid3) return;
      var psels = grid3.querySelectorAll("select[id^='gpVoskPackSel_" + side + "_']");
      for (var k3 = 0; k3 < psels.length; k3++) {
        var parsed4 = gamepadParseSubKeyId(psels[k3].id, "gpVoskPackSel_" + side + "_");
        var k4 = parsed4.key;
        var stored3 = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k4] || "none";
        var parts3 = gamepadSplitMulti(stored3);
        var cur3 = gamepadParseValue(parts3[parsed4.idx] || "none");
        if (!psels[k3].dataset.voiceFilled) gamepadFillVoskPacks(psels[k3], cur3.voiceFn);
      }
    });
  });
  // 收藏成日加減，每次 render 重抓；淨係填得開關一粒嗰啲（有多粒即已有嘢／用家啱揀緊）。
  gamepadLoadRadioFavs(true).then(function () {
    ["pad", "kb"].forEach(function (side) {
      var grid4 = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
      if (!grid4) return;
      var fsels = grid4.querySelectorAll("select[id^='gpRadioSel_" + side + "_']");
      for (var k5 = 0; k5 < fsels.length; k5++) {
        var parsed6 = gamepadParseSubKeyId(fsels[k5].id, "gpRadioSel_" + side + "_");
        var k6 = parsed6.key;
        var stored4 = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[k6] || "none";
        var parts4 = gamepadSplitMulti(stored4);
        var cur4 = gamepadParseValue(parts4[parsed6.idx] || "none");
        if (!fsels[k5].dataset.radioFilled) gamepadFillRadioSelect(fsels[k5], cur4.radioFn);
      }
    });
  });
  // 顯隱：恒顯示。
  var pw = document.getElementById("gamepadMappingPadWrap");
  var kw = document.getElementById("gamepadMappingKbWrap");
  if (pw) pw.style.display = gamepadMappingShown("pad") ? "block" : "none";
  if (kw) kw.style.display = gamepadMappingShown("kb") ? "block" : "none";
}

// 轉語言即時重標映射表（原地改字：未存改動保留，唔 rebuild，唔問後端）。
// 鍵名／servo #N±／鈴聲名／(?) 項係語言無關，唔郁。
function gamepadApplyUiLanguage() {
  var grids = [document.getElementById("gamepadMappingPadGrid"),
    document.getElementById("gamepadMappingKbGrid")];
  for (var gi = 0; gi < grids.length; gi++) {
    var grid = grids[gi];
    if (!grid) continue;
    var heads = grid.querySelectorAll("[data-grp]");
    for (var hi = 0; hi < heads.length; hi++) {
      var grp = heads[hi].getAttribute("data-grp");
      if (grp) heads[hi].textContent = t("gamepad_group_" + grp);
    }
    var sels = grid.getElementsByTagName("select");
    for (var si = 0; si < sels.length; si++) {
      gamepadRelabelSelect(sels[si]);
    }
  }
  // 全屏掣兩態唔同 key（data-i18n 已除），轉語言呢度重標。
  try { gamepadSyncFsBtns(); } catch (e) {}
}

function gamepadRelabelSelect(sel) {
  if (!sel || !sel.id) return;
  var id = sel.id;
  var map = null;
  if (id.indexOf("gpSel_") === 0) map = GAMEPAD_KIND_KEYS;
  else if (id.indexOf("gpMusicSel_") === 0) map = GAMEPAD_MUSIC_FN_KEYS;
  else if (id.indexOf("gpRadioSel_") === 0) map = GAMEPAD_RADIO_FN_KEYS;
  else if (id.indexOf("gpEqSel_") === 0) {
    // 存值 eq:<preset>：名跟音樂頁同一組。
    for (var ei = 0; ei < sel.options.length; ei++) {
      var ev = sel.options[ei].value;
      if (ev === "eq:") {
        sel.options[ei].text = t("gamepad_mfn_eq");
      } else if (ev.indexOf("eq:") === 0) {
        sel.options[ei].text = t("music_eq_preset_" + ev.substring(3));
      }
    }
    return;
  }
  else if (id.indexOf("gpRingSel_") === 0) map = GAMEPAD_RING_FIXED_KEYS;
  else if (id.indexOf("gpRingType_") === 0) map = GAMEPAD_RING_TYPE_KEYS;
  else if (id.indexOf("gpLedPart_") === 0) map = GAMEPAD_LED_PART_KEYS;
  else if (id.indexOf("gpLedPreset_") === 0) map = GAMEPAD_LED_PRESET_KEYS;
  else if (id.indexOf("gpCameraSel_") === 0) map = GAMEPAD_CAM_FN_KEYS;
  else if (id.indexOf("gpSensorSel_") === 0) map = GAMEPAD_SENSOR_FN_KEYS;
  else if (id.indexOf("gpActionKind_") === 0) map = GAMEPAD_ACTION_KIND_KEYS;
  if (id.indexOf("gpVoiceSel_") === 0) {
    // 小智／Vosk 組名跟語言。
    for (var vi = 0; vi < sel.options.length; vi++) {
      var vk = gamepadVoiceFixedKey(sel.options[vi].value);
      if (vk) sel.options[vi].text = t(vk);
    }
    return;
  }
  if (id.indexOf("gpVoskPackSel_") === 0) {
    // 提示跟語言；包名就地用緩存重算（(?) 刪檔項唔郁）。
    for (var pi2 = 0; pi2 < sel.options.length; pi2++) {
      var pv = sel.options[pi2].value;
      if (pv === "") {
        sel.options[pi2].text = "— " + t("gamepad_pick_pack") + " —";
        continue;
      }
      if (pv.indexOf("vosk:") === 0) {
        var packs2 = __gpVoskPacksCache || [];
        for (var qi = 0; qi < packs2.length; qi++) {
          if (("vosk:" + packs2[qi].id) === pv) {
            sel.options[pi2].text = gamepadVoskPackName(packs2[qi]);
            break;
          }
        }
      }
    }
    return;
  }
  if (map) {
    for (var i = 0; i < sel.options.length; i++) {
      var key = map[sel.options[i].value];
      if (key) sel.options[i].text = t(key);
    }
    return;
  }
  if (id.indexOf("gpLedColor_") === 0) {
    // 3 粒即 dual 三對，7 粒即普通七色。
    var dual = sel.options.length === 3;
    for (var j = 0; j < sel.options.length; j++) {
      var val = sel.options[j].value;
      var ck = dual ? GAMEPAD_DUAL_KEYS[val] : null;
      if (!ck) {
        for (var c = 0; c < GAMEPAD_LED_COLOR_KEYS.length; c++) {
          if (GAMEPAD_LED_COLOR_KEYS[c][0] === val) {
            ck = GAMEPAD_LED_COLOR_KEYS[c][1];
            break;
          }
        }
      }
      if (ck) sel.options[j].text = t(ck);
    }
    return;
  }
  if (id.indexOf("gpActionSel_") === 0) {
    for (var k = 0; k < sel.options.length; k++) {
      var v = sel.options[k].value;
      if (v === "") {
        sel.options[k].text = "— " + t("gamepad_pick_action") + " —";
      } else {
        var a = __gpActionById[v];
        if (a) sel.options[k].text = gamepadActionDisplayName(a);
      }
    }
  }
  // gpServo_（#N±）唔使理。
}

function gamepadSyncMappingRowBtns(side, k) {
  var cont = document.getElementById("gpSubs_" + side + "_" + k);
  if (!cont) return;
  var rows = cont.querySelectorAll("[data-subrow]");
  var n = rows.length;
  for (var i = 0; i < rows.length; i++) {
    var x = rows[i].querySelector("[data-rmbtn]");
    if (x) x.style.display = "inline";
    var badge = rows[i].querySelector("[data-idxbadge]");
    if (badge) badge.textContent = n > 1 ? ("#" + (i + 1)) : "";
  }
  var add = cont.querySelector("[data-addbtn='" + side + ":" + k + "']");
  if (add) {
    add.disabled = n >= GAMEPAD_MAX_PER_KEY;
    add.title = n >= GAMEPAD_MAX_PER_KEY ? ("max " + GAMEPAD_MAX_PER_KEY) : "+";
  }
}

function gamepadClearSubRowToNone(side, k, idx) {
  var kindEl = document.getElementById(gamepadSubId("gpSel", side, k, idx));
  if (!kindEl) return;
  kindEl.value = "none";
  try {
    if (typeof kindEl.onchange === "function") kindEl.onchange();
  } catch (e) {}
}

function gamepadRemoveMappingRow(side, k, idx) {
  var cont = document.getElementById("gpSubs_" + side + "_" + k);
  if (!cont) return;
  var rows = cont.querySelectorAll("[data-subrow]");
  if (rows.length <= 1) {
    var only = rows.length ? parseInt(rows[0].getAttribute("data-idx"), 10) : idx;
    if (!isFinite(only)) only = 0;
    gamepadClearSubRowToNone(side, k, only);
    return;
  }
  var pos = -1;
  for (var i = 0; i < rows.length; i++) {
    if (String(rows[i].getAttribute("data-idx")) === String(idx)) { pos = i; break; }
  }
  if (pos < 0) return;
  var wasFirst = (pos === 0);
  cont.removeChild(rows[pos]);
  if (wasFirst) {
    var first = cont.querySelector("[data-subrow]");
    if (first) {
      var sp = first.querySelector("[data-spacer]");
      if (sp) {
        var label = document.createElement("span");
        label.style.minWidth = "90px";
        label.style.fontWeight = "bold";
        label.textContent = gamepadKeyLabel(k);
        label.setAttribute("data-label", "1");
        first.replaceChild(label, sp);
      }
      if (!first.querySelector("[data-addbtn]")) {
        var addBtn = document.createElement("button");
        addBtn.className = "secondary";
        addBtn.textContent = "+";
        addBtn.setAttribute("data-addbtn", side + ":" + k);
        addBtn.onclick = (function (s, kk) {
          return function () { gamepadAddMappingRow(s, kk); };
        })(side, k);
        var ph = first.querySelector("[data-addph]");
        if (ph) first.replaceChild(addBtn, ph);
        else {
          var ref = first.querySelector("[data-idxbadge]");
          if (ref) first.insertBefore(addBtn, ref);
          else first.insertBefore(addBtn, first.firstChild ? first.firstChild.nextSibling : null);
        }
      }
    }
  }
  gamepadSyncMappingRowBtns(side, k);
}

function gamepadAddMappingRow(side, k) {
  var cont = document.getElementById("gpSubs_" + side + "_" + k);
  if (!cont) return;
  var rows = cont.querySelectorAll("[data-subrow]");
  if (rows.length >= GAMEPAD_MAX_PER_KEY) return;
  var maxIdx = -1;
  for (var i = 0; i < rows.length; i++) {
    var ii = parseInt(rows[i].getAttribute("data-idx"), 10);
    if (isFinite(ii) && ii > maxIdx) maxIdx = ii;
  }
  var next = maxIdx + 1;
  var parsed = gamepadParseValue("none");
  cont.appendChild(gamepadBuildMappingSubRow(side, k, next, parsed, false));
  gamepadSyncMappingRowBtns(side, k);
}

function gamepadBuildMappingSubRow(side, k, idx, parsed, isFirst) {
  if (isFirst == null) isFirst = (idx === 0);
  var sub = document.createElement("div");
  sub.className = "row gp-subrow";
  sub.style.alignItems = "center";
  sub.setAttribute("data-subrow", "1");
  sub.setAttribute("data-idx", String(idx));
  var lead;
  if (isFirst) {
    lead = document.createElement("span");
    lead.style.minWidth = "90px";
    lead.style.fontWeight = "bold";
    lead.textContent = gamepadKeyLabel(k);
    lead.setAttribute("data-label", "1");
  } else {
    lead = document.createElement("span");
    lead.style.minWidth = "90px";
    lead.setAttribute("data-spacer", "1");
  }
  var badge = document.createElement("span");
  badge.setAttribute("data-idxbadge", "1");
  badge.style.minWidth = "28px";
  badge.style.opacity = "0.6";
  badge.textContent = "";
  var kind = document.createElement("select");
  kind.id = gamepadSubId("gpSel", side, k, idx);
  GAMEPAD_KINDS.forEach(function (c) {
    var opt = document.createElement("option");
    opt.value = c;
    opt.textContent = t(GAMEPAD_KIND_KEYS[c] || c);
    if (c === parsed.kind) opt.selected = true;
    kind.appendChild(opt);
  });
  kind.onchange = (function (s, kk, ii) {
    return function () {
      var v = document.getElementById(gamepadSubId("gpSel", s, kk, ii)).value;
      var sw = document.getElementById(gamepadSubId("gpServoWrap", s, kk, ii));
      var aw = document.getElementById(gamepadSubId("gpActionWrap", s, kk, ii));
      var mu = document.getElementById(gamepadSubId("gpMusicSel", s, kk, ii));
      var ra = document.getElementById(gamepadSubId("gpRadioSel", s, kk, ii));
      var rg = document.getElementById(gamepadSubId("gpRingWrap", s, kk, ii));
      var lw = document.getElementById(gamepadSubId("gpLedWrap", s, kk, ii));
      var cm = document.getElementById(gamepadSubId("gpCameraSel", s, kk, ii));
      var sn = document.getElementById(gamepadSubId("gpSensorSel", s, kk, ii));
      var vc = document.getElementById(gamepadSubId("gpVoiceWrap", s, kk, ii));
      if (sw) sw.style.display = v === "servo" ? "inline" : "none";
      if (aw) aw.style.display = v === "action" ? "inline" : "none";
      if (mu) mu.style.display = v === "music" ? "inline" : "none";
      if (true) gamepadSyncMusicEq(s, kk, ii);
      if (ra) ra.style.display = v === "radio" ? "inline" : "none";
      if (rg) rg.style.display = v === "ring" ? "inline" : "none";
      if (lw) lw.style.display = v === "led" ? "inline" : "none";
      if (cm) cm.style.display = v === "camera" ? "inline" : "none";
      if (sn) sn.style.display = v === "sensor" ? "inline" : "none";
      if (vc) vc.style.display = v === "voice" ? "inline" : "none";
    };
  })(side, k, idx);
  var servoWrap = gamepadBuildServoControls(side, k, idx, parsed);
  var musicSel = document.createElement("select");
  musicSel.id = gamepadSubId("gpMusicSel", side, k, idx);
  musicSel.style.display = parsed.kind === "music" ? "inline" : "none";
  GAMEPAD_MUSIC_FNS.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f;
    o.textContent = t(GAMEPAD_MUSIC_FN_KEYS[f] || f);
    if (f === parsed.musicFn) o.selected = true;
    musicSel.appendChild(o);
  });
  var eqEntry2 = document.createElement("option");
  eqEntry2.value = "eq:";
  eqEntry2.textContent = t("gamepad_mfn_eq");
  if (parsed.musicFn === "eq:") eqEntry2.selected = true;
  musicSel.appendChild(eqEntry2);
  musicSel.onchange = (function (s, kk, ii) {
    return function () {
      gamepadSyncMusicEq(s, kk, ii);
    };
  })(side, k, idx);
  var eqSel = document.createElement("select");
  eqSel.id = gamepadSubId("gpEqSel", side, k, idx);
  eqSel.style.display = (parsed.kind === "music" && parsed.musicFn === "eq:") ? "inline" : "none";
  ["normal", "classical", "dance", "rock", "jazz", "pop", "bass", "treble", "custom"].forEach(function (p) {
    var oe = document.createElement("option");
    oe.value = "eq:" + p;
    oe.textContent = t("music_eq_preset_" + p);
    if (p === parsed.eqPreset) oe.selected = true;
    eqSel.appendChild(oe);
  });
  var actionWrap = gamepadBuildActionSelect(side, k, idx, parsed);
  var ringWrap = gamepadBuildRingSelect(side, k, idx, parsed);
  var ledWrap = gamepadBuildLedControls(side, k, idx, parsed);
  var cameraSel = document.createElement("select");
  cameraSel.id = gamepadSubId("gpCameraSel", side, k, idx);
  cameraSel.style.display = parsed.kind === "camera" ? "inline" : "none";
  [["camera_photo", "gamepad_cam_photo"], ["face_toggle", "gamepad_cam_face"],
   ["color_toggle", "gamepad_cam_color"], ["video_toggle", "gamepad_cam_video"]].forEach(function (f) {
    var o2 = document.createElement("option");
    o2.value = f[0];
    o2.textContent = t(f[1]);
    if (f[0] === parsed.cameraFn) o2.selected = true;
    cameraSel.appendChild(o2);
  });
  var sensorSel = document.createElement("select");
  sensorSel.id = gamepadSubId("gpSensorSel", side, k, idx);
  sensorSel.style.display = parsed.kind === "sensor" ? "inline" : "none";
  GAMEPAD_SENSOR_FNS.forEach(function (f2) {
    // alpha2_10002 冇 PIR：gamepad sensor 下拉都唔顯示 pir_toggle。
    if (window.alpha2Is2Mic && f2 === "pir_toggle") return;
    var o3 = document.createElement("option");
    o3.value = f2;
    o3.textContent = t(GAMEPAD_SENSOR_FN_KEYS[f2] || f2);
    if (f2 === parsed.sensorFn) o3.selected = true;
    sensorSel.appendChild(o3);
  });
  var voiceWrap = gamepadBuildVoiceSelect(side, k, idx, parsed);
  var radioSel = gamepadBuildRadioSelect(side, k, idx, parsed);
  var rm = document.createElement("button");
  rm.className = "secondary";
  rm.setAttribute("data-rmbtn", "1");
  rm.textContent = "x";
  rm.title = "x";
  rm.onclick = (function (s, kk, ii) {
    return function () { gamepadRemoveMappingRow(s, kk, ii); };
  })(side, k, idx);
  sub.appendChild(lead);
  if (isFirst) {
    var addBtn = document.createElement("button");
    addBtn.className = "secondary";
    addBtn.textContent = "+";
    addBtn.setAttribute("data-addbtn", side + ":" + k);
    addBtn.onclick = (function (s, kk) {
      return function () { gamepadAddMappingRow(s, kk); };
    })(side, k);
    sub.appendChild(addBtn);
  } else {
    var addPh = document.createElement("button");
    addPh.className = "secondary";
    addPh.textContent = "+";
    addPh.setAttribute("data-addph", "1");
    addPh.disabled = true;
    addPh.tabIndex = -1;
    addPh.style.visibility = "hidden";
    sub.appendChild(addPh);
  }
  sub.appendChild(badge);
  sub.appendChild(kind);
  sub.appendChild(servoWrap);
  sub.appendChild(musicSel);
  sub.appendChild(eqSel);
  sub.appendChild(radioSel);
  sub.appendChild(ringWrap);
  sub.appendChild(ledWrap);
  sub.appendChild(cameraSel);
  sub.appendChild(sensorSel);
  sub.appendChild(voiceWrap);
  sub.appendChild(actionWrap);
  sub.appendChild(rm);
  return sub;
}

function gamepadRenderSideGrid(side, groups, map) {
  var wrap = document.getElementById(side === "kb" ? "gamepadMappingKbGrid" : "gamepadMappingPadGrid");
  if (!wrap) return;
  wrap.innerHTML = "";
  groups.forEach(function (grp) {
    var h = document.createElement("div");
    h.className = "servo-group-title";
    h.setAttribute("data-grp", grp.title);
    h.textContent = t("gamepad_group_" + grp.title);
    wrap.appendChild(h);
    grp.keys.forEach(function (k) {
      var stored = (map && map[k]) || "none";
      var multiParts = gamepadSplitMulti(stored);
      if (multiParts.length > GAMEPAD_MAX_PER_KEY) multiParts = multiParts.slice(0, GAMEPAD_MAX_PER_KEY);
      var row = document.createElement("div");
      row.className = "gp-keyrow";
      row.setAttribute("data-maprow", k);
      row.setAttribute("data-mapside", side);
      var subs = document.createElement("div");
      subs.id = "gpSubs_" + side + "_" + k;
      row.appendChild(subs);
      wrap.appendChild(row);
      for (var mi = 0; mi < multiParts.length; mi++) {
        var parsed = gamepadParseValue(multiParts[mi]);
        subs.appendChild(gamepadBuildMappingSubRow(side, k, mi, parsed, mi === 0));
      }
      gamepadSyncMappingRowBtns(side, k);
    });
  });
}

// Vosk 語言包緩存（vosk/models 活數據：已裝包；同鈴聲名單一樣懶載）。
var __gpVoskPacksCache = null;

function gamepadLoadVoskPacks(force) {
  if (!force && __gpVoskPacksCache) return Promise.resolve(__gpVoskPacksCache);
  return Alpha2Api.voskModels().then(function (json) {
    var out = [];
    if (json && json.ok && json.models) {
      json.models.forEach(function (m) {
        if (m && m.id) out.push(m);
      });
    }
    __gpVoskPacksCache = out;
    return out;
  }).catch(function () {
    __gpVoskPacksCache = __gpVoskPacksCache || [];
    return __gpVoskPacksCache;
  });
}

function gamepadVoskPackName(m) {
  return (uiLang === "en" ? (m.langEn || m.lang) : (m.lang || m.langEn)) || m.id;
}

// 建語音兩下拉（小智／Vosk 組＋該組語言包；包單後到即補；vosk_toggle 舊值歸 Vosk 組）。
function gamepadBuildVoiceSelect(side, k, idx, parsed) {
  var wrap = document.createElement("span");
  wrap.id = gamepadSubId("gpVoiceWrap", side, k, idx);
  wrap.style.display = parsed.kind === "voice" ? "inline" : "none";
  var sel = document.createElement("select");
  sel.id = gamepadSubId("gpVoiceSel", side, k, idx);
  var isVosk = parsed.voiceFn !== "xiaozhi_toggle";
  GAMEPAD_VOICE_FIXED.forEach(function (f) {
    var o = document.createElement("option");
    o.value = f[0];
    o.textContent = t(f[1]);
    if ((isVosk && f[0] === "vosk") || (!isVosk && f[0] === parsed.voiceFn)) o.selected = true;
    sel.appendChild(o);
  });
  var pack = document.createElement("select");
  pack.id = gamepadSubId("gpVoskPackSel", side, k, idx);
  pack.style.maxWidth = "220px";
  wrap.appendChild(sel);
  wrap.appendChild(pack);
  sel.onchange = (function (s, kk, ii) {
    return function () {
      gamepadSyncVoicePack(s, kk, ii);
    };
  })(side, k, idx);
  gamepadFillVoskPacks(pack, isVosk ? parsed.voiceFn : "");
  // build 嗰陣未入 DOM，直接設（入 DOM 後轉組經上面 onchange＋sync）。
  pack.style.display = isVosk ? "inline" : "none";
  return wrap;
}

// Vosk 組先出包單（小智組收起）。
function gamepadSyncVoicePack(side, k, idx) {
  if (idx == null) idx = 0;
  var sel = document.getElementById(gamepadSubId("gpVoiceSel", side, k, idx));
  var pack = document.getElementById(gamepadSubId("gpVoskPackSel", side, k, idx));
  if (!pack) return;
  var isVosk = !sel || sel.value !== "xiaozhi_toggle";
  pack.style.display = isVosk ? "inline" : "none";
}

// 等化器下拉：music 種揀咗等化器入口先出，其他值一律收起。
function gamepadSyncMusicEq(side, k, idx) {
  if (idx == null) idx = 0;
  var kd = document.getElementById(gamepadSubId("gpSel", side, k, idx));
  var mu = document.getElementById(gamepadSubId("gpMusicSel", side, k, idx));
  var eq = document.getElementById(gamepadSubId("gpEqSel", side, k, idx));
  if (!eq) return;
  var showKind = !kd || kd.value === "music";
  eq.style.display = (showKind && mu && mu.value === "eq:") ? "inline" : "none";
}

// fixed 兩粒重標用（值→i18n key；語言包名就地用緩存重算）。
function gamepadVoiceFixedKey(v) {
  for (var i = 0; i < GAMEPAD_VOICE_FIXED.length; i++) {
    if (GAMEPAD_VOICE_FIXED[i][0] === v) return GAMEPAD_VOICE_FIXED[i][1];
  }
  return null;
}

// 填包單（提示＋已裝包；舊值唔喺度即補 (?)；無包即得提示）。
function gamepadFillVoskPacks(sel, cur) {
  if (!sel) return;
  sel.innerHTML = "";
  var ph = document.createElement("option");
  ph.value = "";
  ph.textContent = "— " + t("gamepad_pick_pack") + " —";
  sel.appendChild(ph);
  var packs = __gpVoskPacksCache || [];
  packs.forEach(function (m) {
    var v = "vosk:" + m.id;
    var o = document.createElement("option");
    o.value = v;
    o.textContent = gamepadVoskPackName(m);
    if (v === cur) o.selected = true;
    sel.appendChild(o);
  });
  if (cur && cur.indexOf("vosk:") === 0) {
    var found = false;
    for (var j = 0; j < sel.options.length; j++) {
      if (sel.options[j].value === cur) { found = true; break; }
    }
    if (!found) {
      var miss = document.createElement("option");
      miss.value = cur;
      miss.textContent = cur.substring(5) + " (?)";
      miss.selected = true;
      sel.appendChild(miss);
    }
  }
  sel.dataset.voiceFilled = packs.length ? "1" : "";
}

// 讀某子行現時值（同舊逐行存同一拼法；action 未揀回 null 即該子行跳過）。
function gamepadReadSubRowValue(side, k, idx) {
  if (idx == null) idx = 0;
  var kindEl = document.getElementById(gamepadSubId("gpSel", side, k, idx));
  if (!kindEl) return null;
  var kind = kindEl.value;
  if (kind === "servo") {
    var sv = document.getElementById(gamepadSubId("gpServo", side, k, idx));
    return (sv && sv.value) ? sv.value : "servo20+";
  }
  if (kind === "music") {
    var mu = document.getElementById(gamepadSubId("gpMusicSel", side, k, idx));
    var muv = (mu && mu.value) ? mu.value : "music_play";
    // 等化器入口：取值隔籬 eq 下拉。
    if (muv === "eq:") {
      var eq = document.getElementById(gamepadSubId("gpEqSel", side, k, idx));
      return (eq && eq.value) ? eq.value : "eq:normal";
    }
    return muv;
  }
  if (kind === "radio") {
    var rd = document.getElementById(gamepadSubId("gpRadioSel", side, k, idx));
    // 收藏都無即空單：回 null 成行跳過，唔亂存。
    return (rd && rd.value) ? rd.value : null;
  }
  if (kind === "camera") {
    var cm = document.getElementById(gamepadSubId("gpCameraSel", side, k, idx));
    return (cm && cm.value) ? cm.value : "camera_photo";
  }
  if (kind === "sensor") {
    var sn = document.getElementById(gamepadSubId("gpSensorSel", side, k, idx));
    return (sn && sn.value) ? sn.value : "sonar_toggle";
  }
  if (kind === "voice") {
    var vc = document.getElementById(gamepadSubId("gpVoiceSel", side, k, idx));
    var vcv = (vc && vc.value) ? vc.value : "xiaozhi_toggle";
    if (vcv !== "vosk") return vcv;
    var pk = document.getElementById(gamepadSubId("gpVoskPackSel", side, k, idx));
    var pkv = (pk && pk.value) ? pk.value.trim() : "";
    return pkv || "vosk_toggle";
  }
  if (kind === "ring") {
    var rg = document.getElementById(gamepadSubId("gpRingSel", side, k, idx));
    var rv = rg ? (rg.value || "").trim() : "";
    return rv || null;
  }
  if (kind === "led") {
    var lp = document.getElementById(gamepadSubId("gpLedPart", side, k, idx));
    var pr = document.getElementById(gamepadSubId("gpLedPreset", side, k, idx));
    var co = document.getElementById(gamepadSubId("gpLedColor", side, k, idx));
    var sp = document.getElementById(gamepadSubId("gpLedSpeed", side, k, idx));
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
    if (preset === "random") return part + ":random";
    var color = co ? co.value : "1";
    return part + ":" + preset + ":" + color;
  }
  if (kind === "action") {
    var as = document.getElementById(gamepadSubId("gpActionSel", side, k, idx));
    var id = as ? (as.value || "").trim() : "";
    return id ? ("action:" + id) : null;
  }
  return kind;
}

// 讀某鍵全量值（一鍵多功能：全部子行換行接起；子行 null 即跳過；
// 全跳過回 null 唔存；全 none 回單個 none）。
function gamepadReadKeyValue(side, k) {
  var cont = document.getElementById("gpSubs_" + side + "_" + k);
  var vals = [];
  if (cont) {
    var rows = cont.querySelectorAll("[data-subrow]");
    for (var i = 0; i < rows.length; i++) {
      var ii = parseInt(rows[i].getAttribute("data-idx"), 10);
      if (!isFinite(ii)) ii = i;
      var v = gamepadReadSubRowValue(side, k, ii);
      if (v !== null) vals.push(v);
    }
  } else {
    var single = gamepadReadSubRowValue(side, k, 0);
    if (single === null) return null;
    vals.push(single);
  }
  if (!vals.length) return null;
  var allNone = true;
  for (var j = 0; j < vals.length; j++) {
    if (vals[j] !== "none") { allNone = false; break; }
  }
  if (allNone) return "none";
  return gamepadJoinMulti(vals);
}

// 成區一次過存：只送同緩存唔同的行，一個掣搞掂（取代逐行存）。
function gamepadSaveAll(side) {
  side = side || "pad";
  var groups = side === "kb" ? GAMEPAD_KB_GROUPS : GAMEPAD_PAD_GROUPS;
  var cache = gamepadCachedSide(side);
  var jobs = [];
  groups.forEach(function (grp) {
    grp.keys.forEach(function (k) {
      var v = gamepadReadKeyValue(side, k);
      var cached = cache[k] || "none";
      var normV = v == null ? null : gamepadJoinMulti(gamepadSplitMulti(v));
      var normC = gamepadJoinMulti(gamepadSplitMulti(cached));
      if (v === null || normV === normC) return;
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
  }).then(function () {
    try {
      if (cameraPadViewOn && typeof cameraPadRefresh === "function") cameraPadRefresh();
    } catch (e) {}
  }).catch(function (err) {
    if (st) st.textContent = t("gamepad_read_failed") + ": " + err.message;
  });
}

function gamepadResetMapping(side) {
  side = side || "pad";
  return Alpha2Api.gamepadConfigSet({ side: side, reset: "1" }).then(function (json) {
    if (json && json.ok && json.mapping) gamepadCacheSide(side, json.mapping);
    return gamepadRefresh();
  }).then(function () {
    try {
      if (side === "pad" && cameraPadViewOn && typeof cameraPadRefresh === "function") cameraPadRefresh();
    } catch (e) {}
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
// 媒體列（7 粒：播／停＋音量，置導航／數字區上面，全闊一行）。
var GAMEPAD_KB_MEDIA = [
  [["⏮", "media_prev", ""], ["▶⏸", "media_play", ""], ["⏭", "media_next", ""],
   ["⏹", "media_stop", ""], ["🔇", "mute", ""], ["🔊", "vol_up", ""], ["🔉", "vol_down", ""]]
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
  // 右欄：媒體列置頂，導航＋數字區並排喺下（同闊對齊，唔壓住主區 F 行）。
  var right = document.createElement("div");
  right.className = "kb-zone-right";
  GAMEPAD_KB_MEDIA.forEach(function (row) { right.appendChild(gamepadKbRowEl(row)); });
  var sub = document.createElement("div");
  sub.className = "kb-zones-sub";
  sub.appendChild(nav);
  sub.appendChild(num);
  right.appendChild(sub);
  zones.appendChild(main);
  zones.appendChild(right);
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
  var mapped = lit && action && gamepadSplitMulti(action).some(function (p) { return p !== "none"; });
  var css = mapped ? "lit" : "lit-free";
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
  try {
    __cameraPadLit[logical] = lit ? true : false;
    if (cameraPadViewOn) cameraPadDraw();
  } catch (e) {}
  var rows = document.querySelectorAll('[data-maprow="' + logical + '"]');
  for (var j = 0; j < rows.length; j++) {
    var side = rows[j].getAttribute("data-mapside");
    var cur = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[logical];
    var curMapped = cur && gamepadSplitMulti(cur).some(function (p) { return p !== "none"; });
    var rowCls = (lit && curMapped) ? "gprow-lit" : "gprow-free";
    rows[j].classList.remove("gprow-lit");
    rows[j].classList.remove("gprow-free");
    if (lit) rows[j].classList.add(rowCls);
  }
}

// 搖桿點位（預設 nub 26px 置 64px 圈，中心 19px，滿偏 ±14px；
// 全屏放大咗圈照跟實際尺寸計，唔寫死）。
function gamepadMoveNubOne(nubId, v) {
  var nub = document.getElementById(nubId);
  if (!nub || !nub.parentNode) return;
  var stickW = 0, nubW = 0;
  try {
    stickW = nub.parentNode.clientWidth || 0;
    nubW = nub.offsetWidth || 0;
  } catch (e) {}
  if (!stickW || !nubW) {
    nub.style.left = (19 + Math.max(-1, Math.min(1, v.x || 0)) * 14) + "px";
    nub.style.top = (19 + Math.max(-1, Math.min(1, v.y || 0)) * 14) + "px";
    return;
  }
  var center = (stickW - nubW) / 2;
  var range = Math.max(0, center - 2);
  nub.style.left = (center + Math.max(-1, Math.min(1, v.x || 0)) * range) + "px";
  nub.style.top = (center + Math.max(-1, Math.min(1, v.y || 0)) * range) + "px";
}
function gamepadMoveNubs(lx, ly, rx, ry) {
  gamepadMoveNubOne("gpnub-l", { x: lx || 0, y: ly || 0 });
  gamepadMoveNubOne("gpnub-r", { x: rx || 0, y: ry || 0 });
}

// ---------------- 示意圖即撳即用：圖上掣可直接撳 ----------------
// 手柄圖 (gpcap-*) → side pad；鍵盤圖 (data-kb) → side kb。
// 經 gamepad/key down/up 注入，同物理掣同一條 executeAction（servo jog 撳住連走、
// 放手停；taken:false 照亮＋最後按鍵寫✗，同物理一致，見 gamepadLastText）。
// WS gamepad_key 回聲會再亮一次，呢度先樂觀亮一次等手感即時（色按該邊緩存現值）。
// 雙搖桿拖曳經 gamepad/axis（同真柄同一條 applyAxis：配咗向行映射，未配右桿 legacy 轉頭）。
// 多點觸控：pointerId→鍵／桿，同鍵兩指撳要兩指齊放先 up。
// 防黐掣（唔留 servo 一直走／搖桿唔回中／撳極唔郁）：
// 1) up／cancel／lostcapture／出窗 move+out 見 buttons=0（sweep）／
//    blur／visibilitychange／pagehide／全屏退出，一律放晒；
// 2) 收掣 up／回中 0 正常一次搞掂（WS 得返應有句數，唔有多餘；HTTP 有回應即係
//    後端已處理，唔使估），得失敗先補（最多兩次），補嗰陣有新撳／新拖即收手；
// 3) 計數唔盲信：每次向 live pointer 表對數（Holders），殘留下次撳自動歸位；
//    同一 pid 未 up 又 down 必係殘留，清舊當新，唔會食咗下撳。
var __gpDiagBound = false;
var __gpDiagPtr = {};
var __gpDiagHold = {};
var __gpDiagStickL = { dx: 0, dy: 0, held: false, ptr: null };
var __gpDiagStickR = { dx: 0, dy: 0, held: false, ptr: null };
var __gpDiagAxisSentAt = 0;
var __gpDiagAxisTimer = null;

function gamepadDiagCachedAction(side, key) {
  try {
    var m = (side === "kb" ? gamepadCachedSide("kb") : gamepadCachedSide("pad"))[key];
    return m != null ? m : "none";
  } catch (e) {
    return "none";
  }
}

// 該鍵而家有幾隻 live pointer 撳緊（唔計 excludePid；pid 數字／字串混用，唔好用 ===）。
// hold 計數唔盲信，一律向呢張 live 表對數——漏過 up 搞殘計數，下次撳即自愈，
// 之後 tap 唔會再變靜音掣。
function gamepadDiagHolders(hk, excludePid) {
  var c = 0;
  for (var pid in __gpDiagPtr) {
    try {
      var h = __gpDiagPtr[pid];
      if (h && pid != excludePid && ((h.side + ":" + h.key) === hk)) c++;
    } catch (e) {}
  }
  return c;
}

// 收掣 up 規矩：正常一次搞掂——撳一下 WS 得返 down＋up 兩句，唔有多餘第 3 句。
// 原理：HTTP 有回應即係後端已 stop＋publish（回應喺處理之後先寫），成功就唔使估。
// 得第一發失敗（ok:false／掟 exception，後端未處理）先補，最多兩次；
// 補嗰陣有新撳住即收手（舊 up 遲到有後端反轉閘兜，唔斬斷人哋）。
function gamepadDiagSendUp(side, key, hk) {
  var tries = 0;
  function sendOnce() {
    var p = null;
    try {
      p = Alpha2Api.gamepadKey({ side: side, key: key, action: "up", source: "panel" });
    } catch (e) {
      scheduleRetry();
      return;
    }
    try { p.catch(function () {}); } catch (e0) {}
    try {
      p.then(function (json) {
        if (!json || !json.ok) scheduleRetry();
      }, function () { scheduleRetry(); });
    } catch (e2) {}
  }
  function scheduleRetry() {
    tries++;
    if (tries > 2) return;
    try {
      setTimeout(function () {
        try {
          if (gamepadDiagHolders(hk, null) > 0) return;
          if ((__gpDiagHold[hk] || 0) > 0) return;
          sendOnce();
        } catch (e3) {}
      }, tries === 1 ? 300 : 700);
    } catch (e4) {}
  }
  sendOnce();
}

function gamepadDiagPress(side, key, down, pid) {
  if (!key) return;
  side = (side === "kb") ? "kb" : "pad";
  var hk = side + ":" + key;
  if (down) {
    // 調用方（PadDown／KbDown）先記 ptr 後叫入嚟，所以 own pid 已在表：
    // 仲有其他手指撳緊同一粒＝真・多指同撳，先靜音（計數跟表行，防殘留污染）。
    var others = (pid == null) ? 0 : gamepadDiagHolders(hk, pid);
    __gpDiagHold[hk] = others + 1;
    if (others > 0) return;
    try { gamepadLightLogical(key, true, gamepadDiagCachedAction(side, key)); } catch (e) {}
    try {
      Alpha2Api.gamepadKey({ side: side, key: key, action: "down", source: "panel" }).catch(function () {
        __gpDiagHold[hk] = 0;
        try { gamepadLightLogical(key, false); } catch (e2) {}
      });
    } catch (e) {}
  } else {
    // 調用方（OnUp）先刪 ptr 後叫入嚟；releaseAll 傳埋 pid 入嚟一視同仁。
    var holders = gamepadDiagHolders(hk, pid);
    __gpDiagHold[hk] = holders;
    if (holders > 0) return;
    try { gamepadLightLogical(key, false); } catch (e) {}
    gamepadNoteLocalUp(side, key);
    gamepadDiagSendUp(side, key, hk);
  }
}

// hold 計數直清（唔經 pointerId：防 ptr 唔齊／全屏退出嗰陣漏 up，唔留 servo 一直走）。
function gamepadDiagFlushHolds() {
  for (var hk in __gpDiagHold) {
    if ((__gpDiagHold[hk] || 0) <= 0) continue;
    __gpDiagHold[hk] = 0;
    var i = hk.indexOf(":");
    var side = i < 0 ? "pad" : hk.substring(0, i);
    var key = i < 0 ? hk : hk.substring(i + 1);
    if (!key) continue;
    try { gamepadLightLogical(key, false); } catch (e) {}
    gamepadNoteLocalUp(side, key);
    gamepadDiagSendUp(side, key, hk);
  }
}

// 回中零位包規矩：同 up 一樣，正常一次搞掂，得失敗先補（最多兩次），
// 有新拖曳即收手。取代之前次次補多句嘅做法。
function gamepadDiagSendZero() {
  var tries = 0;
  function sendOnce() {
    var now = 0;
    try { now = Date.now(); } catch (e0) {}
    __gpDiagAxisSentAt = now;
    var p = null;
    try {
      p = Alpha2Api.gamepadAxis({ lx: 0, ly: 0, rx: 0, ry: 0, source: "panel" });
    } catch (e) {
      scheduleRetry();
      return;
    }
    try { p.catch(function () {}); } catch (e1) {}
    try {
      p.then(function (json) {
        if (!json || !json.ok) scheduleRetry();
      }, function () { scheduleRetry(); });
    } catch (e2) {}
  }
  function scheduleRetry() {
    tries++;
    if (tries > 2) return;
    try {
      setTimeout(function () {
        try {
          if (__gpDiagStickL.held || __gpDiagStickR.held) return;
          sendOnce();
        } catch (e3) {}
      }, tries === 1 ? 250 : 700);
    } catch (e4) {}
  }
  sendOnce();
}

// 放開一支桿：回中＋即送 0（唔經節流）。回 true＝真係放咗。
function gamepadDiagStickRelease(st) {
  if (!st || !st.held) return false;
  st.dx = 0; st.dy = 0; st.held = false; st.ptr = null;
  gamepadDiagSendZero();
  try { gamepadMoveNubs(__gpDiagStickL.dx, __gpDiagStickL.dy, __gpDiagStickR.dx, __gpDiagStickR.dy); } catch (x) {}
  return true;
}

function gamepadDiagReleaseAll() {
  for (var pid in __gpDiagPtr) {
    try {
      var h = __gpDiagPtr[pid];
      if (h) gamepadDiagPress(h.side, h.key, false, pid);
    } catch (e) {}
  }
  __gpDiagPtr = {};
  try { gamepadDiagFlushHolds(); } catch (e) {}
  __gpDiagHold = {};
  try { gamepadDiagStickRelease(__gpDiagStickL); } catch (e) {}
  try { gamepadDiagStickRelease(__gpDiagStickR); } catch (e) {}
}

function gamepadDiagClosest(el, sel) {
  var cur = el;
  while (cur) {
    try {
      if (cur.matches && cur.matches(sel)) return cur;
      if (sel.charAt(0) === "." && cur.classList && cur.classList.contains(sel.substring(1))) return cur;
      if (sel.charAt(0) === "#" && cur.id === sel.substring(1)) return cur;
    } catch (e) {}
    // [id^="gpcap-"]／[data-kb] 兩種屬性選擇器手動認（舊 WebView matches 未必全支援）。
    try {
      if (sel === '[id^="gpcap-"]' && cur.id && cur.id.indexOf("gpcap-") === 0) return cur;
      if (sel === "[data-kb]" && cur.getAttribute && cur.getAttribute("data-kb")) return cur;
      if (sel === ".gp-stick" && cur.classList && cur.classList.contains("gp-stick")) return cur;
    } catch (e2) {}
    cur = cur.parentNode;
    if (cur && cur.nodeType !== 1) return null;
  }
  return null;
}

function gamepadDiagStickOfEl(stickEl) {
  if (!stickEl) return null;
  try {
    if (stickEl.querySelector && stickEl.querySelector("#gpnub-l")) return { st: __gpDiagStickL, id: "L" };
    if (stickEl.querySelector && stickEl.querySelector("#gpnub-r")) return { st: __gpDiagStickR, id: "R" };
  } catch (e) {}
  return null;
}

function gamepadDiagStickSet(st, stickEl, clientX, clientY) {
  var r = null;
  try { r = stickEl.getBoundingClientRect(); } catch (e) { return; }
  if (!r || !r.width) return;
  var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  var R = r.width / 2;
  var dx = (clientX - cx) / R, dy = (clientY - cy) / R;
  var len = Math.sqrt(dx * dx + dy * dy);
  if (len > 1) { dx /= len; dy /= len; }
  st.dx = Math.round(dx * 100) / 100;
  st.dy = Math.round(dy * 100) / 100;
  st.held = true;
  try { gamepadMoveNubs(__gpDiagStickL.dx, __gpDiagStickL.dy, __gpDiagStickR.dx, __gpDiagStickR.dy); } catch (e) {}
  gamepadDiagAxisSend(false);
  gamepadDiagAxisRepeat();
}

function gamepadDiagAxisSend(force) {
  var now = Date.now();
  if (!force && now - __gpDiagAxisSentAt < 80) return;
  __gpDiagAxisSentAt = now;
  try {
    Alpha2Api.gamepadAxis({ lx: __gpDiagStickL.dx, ly: __gpDiagStickL.dy,
      rx: __gpDiagStickR.dx, ry: __gpDiagStickR.dy, source: "panel" }).catch(function () {});
  } catch (e) {}
}

function gamepadDiagAxisRepeat() {
  if (__gpDiagAxisTimer) return;
  __gpDiagAxisTimer = setInterval(function () {
    var moving = __gpDiagStickL.held || __gpDiagStickR.held;
    if (!moving) {
      clearInterval(__gpDiagAxisTimer);
      __gpDiagAxisTimer = null;
      return;
    }
    gamepadDiagAxisSend(false);
  }, 200);
}

function gamepadDiagPadDown(e) {
  if (e && e.button != null && e.button !== 0) return;
  var t = e ? (e.target || e.srcElement) : null;
  if (!t) return;
  // 搖桿優先（範圍大）。
  var stickEl = gamepadDiagClosest(t, ".gp-stick");
  if (stickEl) {
    var hit = gamepadDiagStickOfEl(stickEl);
    if (hit && !hit.st.held) {
      hit.st.ptr = (e.pointerId != null) ? e.pointerId : "mouse";
      try { if (stickEl.setPointerCapture && e.pointerId != null) stickEl.setPointerCapture(e.pointerId); } catch (x) {}
      gamepadDiagStickSet(hit.st, stickEl, e.clientX, e.clientY);
      try { e.preventDefault(); } catch (x) {}
    }
    return;
  }
  var btn = gamepadDiagClosest(t, '[id^="gpcap-"]');
  if (btn && btn.id && btn.id.indexOf("gpcap-") === 0) {
    var key = btn.id.substring(6).toLowerCase();
    if (!key) return;
    var pid = (e.pointerId != null) ? e.pointerId : "mouse";
    // 同一 pointer 未 up 又 down（之前甩咗 up 殘留）：舊嗰筆當放咗先清，
    // 再當新撳——單一 contact 唔可能合法撳兩次，有殘留必係漏。
    var stale = __gpDiagPtr[pid];
    if (stale) {
      try { delete __gpDiagPtr[pid]; gamepadDiagPress(stale.side, stale.key, false, pid); } catch (x0) {}
    }
    __gpDiagPtr[pid] = { side: "pad", key: key };
    try { if (btn.setPointerCapture && e.pointerId != null) btn.setPointerCapture(e.pointerId); } catch (x) {}
    gamepadDiagPress("pad", key, true, pid);
    try { e.preventDefault(); } catch (x) {}
  }
}

function gamepadDiagKbDown(e) {
  if (e && e.button != null && e.button !== 0) return;
  var t = e ? (e.target || e.srcElement) : null;
  if (!t) return;
  var k = gamepadDiagClosest(t, "[data-kb]");
  if (!k) return;
  var key = null;
  try { key = k.getAttribute("data-kb"); } catch (x) {}
  if (!key) return;
  var pid = (e.pointerId != null) ? e.pointerId : "mouse";
  var staleKb = __gpDiagPtr[pid];
  if (staleKb) {
    try { delete __gpDiagPtr[pid]; gamepadDiagPress(staleKb.side, staleKb.key, false, pid); } catch (x0) {}
  }
  __gpDiagPtr[pid] = { side: "kb", key: key };
  try { if (k.setPointerCapture && e.pointerId != null) k.setPointerCapture(e.pointerId); } catch (x) {}
  gamepadDiagPress("kb", key, true, pid);
  try { e.preventDefault(); } catch (x) {}
}

function gamepadDiagOnMove(e) {
  // 先掃蕩：buttons 已經係 0 即之前甩咗 up，呢下補返（滑鼠出窗放手專用）。
  try { gamepadDiagSweep(e); } catch (x0) {}
  var pid = (e && e.pointerId != null) ? e.pointerId : "mouse";
  // 搖桿拖曳跟手指。
  if (__gpDiagStickL.held && __gpDiagStickL.ptr === pid) {
    var pad = document.getElementById("gpPadDiagram");
    var sticks = pad ? pad.querySelectorAll(".gp-stick") : null;
    var lEl = null;
    if (sticks && sticks.length) {
      for (var i = 0; i < sticks.length; i++) {
        try {
          if (sticks[i].querySelector && sticks[i].querySelector("#gpnub-l")) { lEl = sticks[i]; break; }
        } catch (x) {}
      }
    }
    if (lEl) {
      gamepadDiagStickSet(__gpDiagStickL, lEl, e.clientX, e.clientY);
      try { e.preventDefault(); } catch (x) {}
    }
    return;
  }
  if (__gpDiagStickR.held && __gpDiagStickR.ptr === pid) {
    var pad2 = document.getElementById("gpPadDiagram");
    var sticks2 = pad2 ? pad2.querySelectorAll(".gp-stick") : null;
    var rEl = null;
    if (sticks2 && sticks2.length) {
      for (var j = 0; j < sticks2.length; j++) {
        try {
          if (sticks2[j].querySelector && sticks2[j].querySelector("#gpnub-r")) { rEl = sticks2[j]; break; }
        } catch (x) {}
      }
    }
    if (rEl) {
      gamepadDiagStickSet(__gpDiagStickR, rEl, e.clientX, e.clientY);
      try { e.preventDefault(); } catch (x) {}
    }
  }
}

function gamepadDiagOnUp(e) {
  var pid = (e && e.pointerId != null) ? e.pointerId : "mouse";
  // 搖桿放手：係自己嗰支先回中（多指各玩各，唔好郁錯支）。
  if (__gpDiagStickL.held && __gpDiagStickL.ptr === pid) gamepadDiagStickRelease(__gpDiagStickL);
  if (__gpDiagStickR.held && __gpDiagStickR.ptr === pid) gamepadDiagStickRelease(__gpDiagStickR);
  var h = __gpDiagPtr[pid];
  if (h) {
    delete __gpDiagPtr[pid];
    gamepadDiagPress(h.side, h.key, false, pid);
  }
}

// 補 up 掃蕩：滑鼠拖出窗放手／個別瀏覽器吞事件嗰陣，up 會成個唔見。
// move／out 嗰陣見到撳住嗰隻 pointer 已經無掣（buttons===0）即當放咗手。
// 掂緊（touch buttons===1／滑鼠拖緊 buttons===1）唔會誤殺。
function gamepadDiagSweep(e) {
  if (!e) return;
  var pid = (e.pointerId != null) ? e.pointerId : "mouse";
  var b = (e.buttons != null) ? e.buttons : 1;
  if (b !== 0) return;
  if (__gpDiagPtr[pid]) { try { gamepadDiagOnUp(e); } catch (x) {} return; }
  if ((__gpDiagStickL.held && __gpDiagStickL.ptr === pid) ||
      (__gpDiagStickR.held && __gpDiagStickR.ptr === pid)) {
    try { gamepadDiagOnUp(e); } catch (x) {}
  }
}

function gamepadDiagTouchHits(clientX, clientY) {
  try {
    var el = document.elementFromPoint ? document.elementFromPoint(clientX, clientY) : null;
    if (!el) return false;
    if (gamepadDiagClosest(el, ".gp-stick")) return true;
    if (gamepadDiagClosest(el, '[id^="gpcap-"]')) return true;
    if (gamepadDiagClosest(el, "[data-kb]")) return true;
  } catch (e) {}
  return false;
}

function gamepadBindDiagrams() {
  if (__gpDiagBound) return;
  var pad = document.getElementById("gpPadDiagram");
  var kb = document.getElementById("kbBoardDiagram");
  if (!pad || !kb) return;
  __gpDiagBound = true;
  try { gamepadFsEnsureExitBtns(); } catch (e) {}
  try {
    pad.addEventListener("pointerdown", gamepadDiagPadDown);
    kb.addEventListener("pointerdown", gamepadDiagKbDown);
    window.addEventListener("pointermove", gamepadDiagOnMove);
    window.addEventListener("pointerup", gamepadDiagOnUp);
    window.addEventListener("pointercancel", gamepadDiagOnUp);
    try { window.addEventListener("lostpointercapture", gamepadDiagOnUp); } catch (x) {}
    // 出窗／掣間遊走嗰陣 buttons 歸 0 即補 up（connect 漏事件專用，見 gamepadDiagSweep）。
    try { document.addEventListener("pointerout", gamepadDiagSweep); } catch (x2) {}
    window.addEventListener("blur", function () { try { gamepadDiagReleaseAll(); } catch (e) {} });
    // 鎖屏／切頁／來電：手機好多時淨係出 visibility，唔出 blur，兩邊一齊捉。
    try {
      document.addEventListener("visibilitychange", function () {
        try {
          if (document.hidden || document.visibilityState === "hidden") gamepadDiagReleaseAll();
        } catch (e2) {}
      });
    } catch (x3) {}
    try {
      window.addEventListener("pagehide", function () { try { gamepadDiagReleaseAll(); } catch (e4) {} });
    } catch (x5) {}
    pad.addEventListener("contextmenu", function (e) { try { e.preventDefault(); } catch (x) {} });
    kb.addEventListener("contextmenu", function (e) { try { e.preventDefault(); } catch (x) {} });
    // 長按消震：舊 WebView 長按震動＋callout，touchstart 非被動再攔一次——只攔食中掣／桿嘅觸點。
    pad.addEventListener("touchstart", function (e) {
      try {
        var t = e.touches && e.touches[0];
        if (t && gamepadDiagTouchHits(t.clientX, t.clientY)) e.preventDefault();
      } catch (x) {}
    }, { passive: false });
    kb.addEventListener("touchstart", function (e) {
      try {
        var t2 = e.touches && e.touches[0];
        if (t2 && gamepadDiagTouchHits(t2.clientX, t2.clientY)) e.preventDefault();
      } catch (x) {}
    }, { passive: false });
  } catch (e) {}
}


// ---------------- 相機頁手柄視圖（pad 鍵＋雙搖桿，viewport 內 canvas 圖形） ----------------
// 鍵定義跟手柄設定（映射快照 __cameraPadMap；掣面只出鍵名，唔出動作字）。
// 掣：撳落經 gamepad/key down／放手 up（同物理掣同一條 executeAction＋toggle 語義；
// servo jog 撳住後端連走、放手停；taken:false 閃紅＋狀態行寫✗）。
// 搖桿：拖曳偏轉，經 gamepad/axis 注入 lx/ly/rx/ry（同真柄同一條 applyAxis：配咗向
// 行映射出沿＋連走，未配右桿 legacy 轉頭 19/20）；偏轉中 200ms 連發保 legacy 步進，
// 放手回中即送 0 停；下面讀數條即時見偏轉量。
// 版面跟 X360：搖桿左右下（放手自動回中）／十字掣左中（一體 plus 搖桿式，
// 唔使離掣碌臂轉向）／ABXY 右中 A 下 B 右／start-select 中下／
// L1L2 左上 R1R2 右上；多點觸控（pointerId→掣／桿／十字）可兩手齊用。
// 同功能鍵 2選1：兩邊開關互斥，同一時間最多一邊著（見 cameraFeatureToggle）。
// 防黐掣同面板圖同級：up 即送＋300ms 補（殘留成套收）、搖桿 0 即送＋250ms 補、
// move／pointerout 見 buttons=0 補 up、blur／visibility／pagehide／閂視圖放晒、
// 撳住狀態向 live pointer 表對數（殘留下次撳自愈，唔變死掣）。
var cameraPadViewOn = false;
var __cameraPadMap = {};
var __cameraPadShapes = [];
var __cameraPadDown = {};
var __cameraPadLit = {};
var __cameraPadNo = {};
var __cameraPadPtr = {};
var __cameraPadBound = false;
// 搖桿態（dx/dy −1..1，右＋下＋；held＝手指撳緊；本機偏轉經 axis 送，物理柄經 WS 回聲鏡像）。
var __campadStickL = { dx: 0, dy: 0, held: false };
var __campadStickR = { dx: 0, dy: 0, held: false };
var __campadMirror = { lx: 0, ly: 0, rx: 0, ry: 0 };
var __campadAxisSentAt = 0;
var __campadAxisTimer = null;

// 功能鍵開關：開即閂手柄（2選1；閂就由得佢，兩邊可齊熄）。
function cameraFeatureToggle() {
  var f = document.getElementById("featureEnabled");
  if (f && f.checked) {
    var tg = document.getElementById("cameraPadToggle");
    if (tg) tg.checked = false;
    cameraPadViewOn = false;
    var cv = document.getElementById("cameraPadCanvas");
    if (cv) cv.style.display = "none";
    try { cameraPadReleaseAll(); } catch (e) {}
  }
  try { updateCrosshairVisibility(); } catch (e) {}
}

function cameraPadToggleView() {
  var tg = document.getElementById("cameraPadToggle");
  cameraPadViewOn = !!(tg && tg.checked);
  if (cameraPadViewOn) {
    var f = document.getElementById("featureEnabled");
    if (f) f.checked = false;
  }
  var cv = document.getElementById("cameraPadCanvas");
  if (cv) cv.style.display = cameraPadViewOn ? "block" : "none";
  try { updateCrosshairVisibility(); } catch (e) {}
  if (cameraPadViewOn) { cameraPadBind(); cameraPadRefresh(); }
  else {
    // 閂視圖即放晒撳住嘅掣＋回中搖桿（servo jog／轉頭唔好留低一直走）。
    try { cameraPadReleaseAll(); } catch (e) {}
  }
}

// 該鍵有幾隻 live pointer 撳緊（掣 {key}／十字 {dpad,dir} 都計；pid 數字／字串混用，唔好用 ===）。
// campad 用 boolean 記撳住（唔係計數），殘留（漏 up）唔會自己好——靠呢個表對數自愈。
function campadLiveHoldersKey(k, excludePid) {
  var c = 0;
  for (var pid in __cameraPadPtr) {
    try {
      var h = __cameraPadPtr[pid];
      if (!h || pid == excludePid) continue;
      if (h.key && h.key === k) c++;
      else if (h.dpad && h.dir === k) c++;
    } catch (e) {}
  }
  return c;
}

// campad 版收掣 up：規矩同面板一樣——正常一次搞掂，得失敗先補（最多兩次）。
function campadSendUp(k) {
  if (!k) return;
  var tries = 0;
  function sendOnce() {
    var p = null;
    try {
      p = Alpha2Api.gamepadKey({ side: "pad", key: k, action: "up", source: "campad" });
    } catch (e) {
      scheduleRetry();
      return;
    }
    try { p.catch(function () {}); } catch (e0) {}
    try {
      p.then(function (json) {
        if (!json || !json.ok) scheduleRetry();
      }, function () { scheduleRetry(); });
    } catch (e2) {}
  }
  function scheduleRetry() {
    tries++;
    if (tries > 2) return;
    try {
      setTimeout(function () {
        try {
          if (campadLiveHoldersKey(k, null) > 0) return;
          if (__cameraPadDown[k]) return;
          sendOnce();
        } catch (e3) {}
      }, tries === 1 ? 300 : 700);
    } catch (e4) {}
  }
  sendOnce();
}

// 同一 pointer 未 up 又 down（之前甩咗 up 殘留）：舊嗰筆當放咗先清再當新撳。
function campadFlushStalePtr(pid) {
  var old = null;
  try { old = (pid != null) ? __cameraPadPtr[pid] : null; } catch (e) {}
  if (!old) return;
  try { delete __cameraPadPtr[pid]; } catch (e2) {}
  try {
    if (old.stick) {
      campadStickRelease(old.stick.st);
    } else if (old.dpad) {
      if (old.dir) cameraPadPress(old.dir, false, pid);
    } else if (old.key) {
      cameraPadPress(old.key, false, pid);
    }
  } catch (e3) {}
}

// 放開一支桿：回中＋即送 0（唔經節流）。回 true＝真係放咗。
function campadStickRelease(st) {
  if (!st || !st.held) return false;
  st.dx = 0; st.dy = 0; st.held = false;
  campadSendZero();
  cameraPadDraw();
  return true;
}

// campad 版回中零位包：正常一次搞掂，得失敗先補（最多兩次），有新拖曳即收手。
function campadSendZero() {
  var tries = 0;
  function sendOnce() {
    var now = 0;
    try { now = Date.now(); } catch (e0) {}
    __campadAxisSentAt = now;
    var p = null;
    try {
      p = Alpha2Api.gamepadAxis({ lx: 0, ly: 0, rx: 0, ry: 0, source: "campad" });
    } catch (e) {
      scheduleRetry();
      return;
    }
    try { p.catch(function () {}); } catch (e1) {}
    try {
      p.then(function (json) {
        if (!json || !json.ok) scheduleRetry();
      }, function () { scheduleRetry(); });
    } catch (e2) {}
  }
  function scheduleRetry() {
    tries++;
    if (tries > 2) return;
    try {
      setTimeout(function () {
        try {
          if (__campadStickL.held || __campadStickR.held) return;
          sendOnce();
        } catch (e3) {}
      }, tries === 1 ? 250 : 700);
    } catch (e4) {}
  }
  sendOnce();
}

function cameraPadReleaseAll() {
  for (var k in __cameraPadDown) {
    if (__cameraPadDown[k]) {
      __cameraPadDown[k] = false;
      __cameraPadLit[k] = false;
      gamepadNoteLocalUp("pad", k);
      try { campadSendUp(k); } catch (e) {}
    }
  }
  __cameraPadPtr = {};
  campadStickReset(__campadStickL);
  campadStickReset(__campadStickR);
  campadSendZero();
  cameraPadDraw();
}

function cameraPadBind() {
  if (__cameraPadBound) return;
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv || !cv.addEventListener) return;
  __cameraPadBound = true;
  cv.addEventListener("pointerdown", cameraPadOnDown);
  cv.addEventListener("pointermove", cameraPadOnMove);
  cv.addEventListener("pointerup", cameraPadOnUp);
  cv.addEventListener("pointercancel", cameraPadOnUp);
  cv.addEventListener("pointerleave", cameraPadOnUp);
  cv.addEventListener("lostpointercapture", cameraPadOnUp);
  // 出窗遊走補 up（connect 漏事件專用，見 campadSweepDown）。
  try { document.addEventListener("pointerout", campadSweepDown); } catch (x0) {}
  cv.addEventListener("contextmenu", function (e) { try { e.preventDefault(); } catch (x) {} });
  // 長按消震：舊 WebView／部分手機瀏覽器 pointerdown preventDefault 擋唔住長按震動＋
  // callout，touchstart／touchmove 非被動再攔一次——但只攔食中掣／桿／十字嘅觸點，
  // 空白位放行（dblclick 全屏＋一般觸控靠佢；viewport oncontextmenu 本來已擋 menu）。
  try {
    cv.addEventListener("touchstart", function (e) {
      try {
        var t = e.touches && e.touches[0];
        if (t && cameraPadViewOn && campadTouchHits(t.clientX, t.clientY)) e.preventDefault();
      } catch (x) {}
    }, { passive: false });
    cv.addEventListener("touchmove", function (e) { try { e.preventDefault(); } catch (x) {} }, { passive: false });
  } catch (e) {}
  try {
    window.addEventListener("resize", function () {
      if (cameraPadViewOn) cameraPadDraw();
    });
    // 視窗失焦（來電／通知／切 app）即放晒：唔係手指唔喺度，up 事件永遠唔到，
    // servo 會走到 40 格封頂。手機好多時淨出 visibility 唔出 blur，兩邊一齊捉。
    window.addEventListener("blur", function () {
      if (cameraPadViewOn) { try { cameraPadReleaseAll(); } catch (e2) {} }
    });
    try {
      document.addEventListener("visibilitychange", function () {
        try {
          if ((document.hidden || document.visibilityState === "hidden") && cameraPadViewOn) {
            cameraPadReleaseAll();
          }
        } catch (e3) {}
      });
    } catch (x3) {}
    try {
      window.addEventListener("pagehide", function () {
        if (cameraPadViewOn) { try { cameraPadReleaseAll(); } catch (e4) {} }
      });
    } catch (x5) {}
  } catch (e) {}
}

function cameraPadPos(e) {
  var cv = document.getElementById("cameraPadCanvas");
  var r = cv.getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

function cameraPadOnDown(e) {
  if (!cameraPadViewOn) return;
  var p = null;
  try { p = cameraPadPos(e); } catch (x) { return; }
  // 中掣先算數：食中先 preventDefault，食唔中（空白位）放行——dblclick 全屏靠佢
  // （preventDefault 會殺 click 合成，之前一律攔即係雙擊永遠唔到 viewport）。
  var consumed = false;
  // 同一 pointer 未 up 又 down（之前甩咗 up 殘留）：舊嗰筆當放咗先清再當新撳。
  try { campadFlushStalePtr(e.pointerId); } catch (x0) {}
  // 搖桿區優先（範圍大，掣唔會疊佢，見版面 audit）。
  var st = cameraPadStickAt(p.x, p.y);
  if (st) {
    // 第二隻手指撳同一支唔認（認咗先放手會早收，郁緊嗰隻變孤兒；唔認最多佢無反應）。
    if (!st.st.held) {
      try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { stick: st }; } catch (x) {}
      campadStickGrab(st, p.x, p.y);
    }
    consumed = true;
  } else {
    var dir = campadDpadDir(p.x, p.y);
    if (dir) {
      try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { dpad: true, dir: dir }; } catch (x) {}
      cameraPadPress(dir, true, e.pointerId);
      consumed = true;
    } else {
      var k = cameraPadHit(p.x, p.y);
      if (k) {
        try { if (e.pointerId != null) __cameraPadPtr[e.pointerId] = { key: k }; } catch (x) {}
        cameraPadPress(k, true, e.pointerId);
        consumed = true;
      }
    }
  }
  if (consumed && e) {
    try { e.preventDefault(); } catch (x) {}
    // 顯式 capture：手指跣出 canvas／出窗都好，up／cancel 照派返嚟（唔靠隱式，
    // 唔係放手事件唔見，菇＋servo 一齊留喺偏位）。
    try {
      var cv = document.getElementById("cameraPadCanvas");
      if (cv && cv.setPointerCapture && e.pointerId != null) cv.setPointerCapture(e.pointerId);
    } catch (x) {}
  }
}

function cameraPadOnMove(e) {
  if (!cameraPadViewOn) return;
  // 先掃蕩：buttons 已經係 0 即之前甩咗 up，呢下補返。
  try { campadSweep(e); } catch (x0) {}
  var hold = null;
  try { hold = (e && e.pointerId != null) ? __cameraPadPtr[e.pointerId] : null; } catch (x) {}
  if (!hold || (!hold.stick && !hold.dpad)) return;
  if (e) { try { e.preventDefault(); } catch (x) {} }
  var p = null;
  try { p = cameraPadPos(e); } catch (x) { return; }
  if (hold.stick) {
    campadStickGrab(hold.stick, p.x, p.y);
    return;
  }
  // 十字搖桿式：手指唔使離掣，碌去邊臂轉邊臂（舊臂 up、新臂 down）。
  var nd = campadDpadDir(p.x, p.y);
  if (nd !== hold.dir) {
    if (hold.dir) cameraPadPress(hold.dir, false, e.pointerId);
    hold.dir = nd;
    if (nd) cameraPadPress(nd, true, e.pointerId);
  }
}

function cameraPadOnUp(e) {
  var hold = null;
  try {
    if (e && e.pointerId != null && __cameraPadPtr[e.pointerId]) {
      hold = __cameraPadPtr[e.pointerId];
      delete __cameraPadPtr[e.pointerId];
    }
    var cv = document.getElementById("cameraPadCanvas");
    if (cv && cv.releasePointerCapture && e && e.pointerId != null) {
      try { cv.releasePointerCapture(e.pointerId); } catch (x2) {}
    }
  } catch (x) {}
  if (!hold) return;
  // 搖桿放手：回中＋即送 0（force 過節流）＋排補發＋即重畫（唔畫菇視覺留喺偏位，
  // 就係「唔識自己回中」）。
  if (hold.stick) {
    campadStickRelease(hold.stick.st);
  }
  else if (hold.dpad) { if (hold.dir) cameraPadPress(hold.dir, false, e.pointerId); }
  else if (hold.key) cameraPadPress(hold.key, false, e.pointerId);
}

// ---- 搖桿 ----

function campadStickDefs(W, H) {
  var S = Math.min(W * 0.75, H);
  return [
    { id: "L", st: __campadStickL, x: W * 0.10, y: H * 0.80, R: S * 0.115, nub: S * 0.05 },
    { id: "R", st: __campadStickR, x: W * 0.90, y: H * 0.80, R: S * 0.115, nub: S * 0.05 }
  ];
}

function cameraPadStickAt(x, y) {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv) return null;
  var W = cv.clientWidth || 480, H = cv.clientHeight || 360;
  var defs = campadStickDefs(W, H);
  for (var i = 0; i < defs.length; i++) {
    var dx = x - defs[i].x, dy = y - defs[i].y;
    if (dx * dx + dy * dy <= defs[i].R * defs[i].R) return defs[i];
  }
  return null;
}

// 手指位置轉偏轉（出底盤即 clamp 圓邊；即時送 axis）。
function campadStickGrab(def, x, y) {
  var dx = (x - def.x) / def.R, dy = (y - def.y) / def.R;
  var len = Math.sqrt(dx * dx + dy * dy);
  if (len > 1) { dx /= len; dy /= len; }
  def.st.dx = Math.round(dx * 100) / 100;
  def.st.dy = Math.round(dy * 100) / 100;
  def.st.held = true;
  campadAxisSend(false);
  campadAxisRepeat();
  cameraPadDraw();
}

function campadStickReset(st) {
  st.dx = 0; st.dy = 0; st.held = false;
}

// 偏轉中連發（legacy 轉頭逐次步進，唔發即停；200ms 過後端 150ms 節流）。
function campadAxisRepeat() {
  if (__campadAxisTimer) return;
  __campadAxisTimer = setInterval(function () {
    var moving = __campadStickL.held || __campadStickR.held;
    if (!moving || !cameraPadViewOn) {
      clearInterval(__campadAxisTimer);
      __campadAxisTimer = null;
      return;
    }
    campadAxisSend(false);
  }, 200);
}

function campadAxisSend(force) {
  var now = Date.now();
  if (!force && now - __campadAxisSentAt < 80) return;
  __campadAxisSentAt = now;
  try {
    Alpha2Api.gamepadAxis({ lx: __campadStickL.dx, ly: __campadStickL.dy,
      rx: __campadStickR.dx, ry: __campadStickR.dy, source: "campad" }).catch(function () {});
  } catch (e) {}
}

// 物理柄回聲鏡像（無撳緊嗰支先跟；自己注入嘅回聲值一樣，changed 閘擋重畫）。
// 例外：source=campad 嘅回聲一律唔跟——放手送嘅 0 可能撞後端 150ms 節流被
// drop，跟住舊偏轉值回聲遲到會將已回中嘅菇推返出去留喺度（就係間中唔回中）。
// 本機 dx/dy 先係真相；物理柄（source robot 等）照跟。
function cameraPadMirrorAxis(d) {
  if (!cameraPadViewOn || !d) return;
  try { if (d.source === "campad") return; } catch (e) {}
  var changed = false;
  var pairs = [["lx", __campadStickL], ["ly", __campadStickL], ["rx", __campadStickR], ["ry", __campadStickR]];
  for (var i = 0; i < pairs.length; i++) {
    var v = Number(d[pairs[i][0]]);
    if (!isFinite(v)) continue;
    v = Math.max(-1, Math.min(1, Math.round(v * 100) / 100));
    if (!pairs[i][1].held && __campadMirror[pairs[i][0]] !== v) {
      __campadMirror[pairs[i][0]] = v;
      changed = true;
    }
  }
  if (changed) cameraPadDraw();
}

// touchstart 消震用命中測試（掣／桿／十字臂中任一即食中）。
// 補 up 掃蕩（document 層 pointerout）：撳住嗰隻 pointer 已經無掣（buttons===0）
// 即當放咗手。淨睇 campad 自己張表，唔會郁到面板圖嗰邊。
function campadSweep(e) {
  if (!e) return;
  var pid = null;
  try { pid = (e.pointerId != null) ? e.pointerId : null; } catch (x) {}
  if (pid == null) return;
  var b = 1;
  try { b = (e.buttons != null) ? e.buttons : 1; } catch (x2) {}
  if (b !== 0) return;
  var tracked = false;
  try { tracked = !!__cameraPadPtr[pid]; } catch (x3) {}
  if (tracked) { try { cameraPadOnUp(e); } catch (x4) {} }
}
function campadSweepDown(e) { try { campadSweep(e); } catch (x) {} }

function campadTouchHits(cx, cy) {
  try {
    var cv = document.getElementById("cameraPadCanvas");
    if (!cv) return false;
    var r = cv.getBoundingClientRect();
    var x = cx - r.left, y = cy - r.top;
    if (cameraPadStickAt(x, y)) return true;
    if (campadDpadDir(x, y)) return true;
    if (cameraPadHit(x, y)) return true;
  } catch (e) {}
  return false;
}

function cameraPadHit(x, y) {
  for (var i = __cameraPadShapes.length - 1; i >= 0; i--) {
    var sh = __cameraPadShapes[i];
    if (sh.kind === "c") {
      var dx = x - sh.x, dy = y - sh.y;
      if (dx * dx + dy * dy <= sh.r * sh.r) return sh.key;
    } else if (x >= sh.x && x <= sh.x + sh.w && y >= sh.y && y <= sh.y + sh.h) {
      return sh.key;
    }
  }
  return null;
}

// 十字幾何（版面定，draw／hit／slide 共用；中軸死區＝pivot）。
var __campadDpad = { cx: 0, cy: 0, L: 0, r: 0, dead: 0 };

// 版面（X360：搖桿左右下（放手自動回中）／十字掣左中（一體 plus 形，手指可碌臂轉向）／
// ABXY 右中 A 下 B 右／start-select 中下／L1L2 左上 R1R2 右上；全部相對 W／H）。
function cameraPadLayout(W, H) {
  var S = Math.min(W * 0.75, H);
  var dcx = W * 0.135, dcy = H * 0.45, L = S * 0.115, r = S * 0.055;
  __campadDpad = { cx: dcx, cy: dcy, L: L, r: r, dead: r * 0.6 };
  var shapes = [];
  function circle(key, cx, cy, cr) {
    shapes.push({ key: key, kind: "c", x: cx, y: cy, r: cr });
  }
  function rect(key, cx, cy, w, h) {
    shapes.push({ key: key, kind: "r", x: cx - w / 2, y: cy - h / 2, w: w, h: h });
  }
  var bcx = W * 0.865, bcy = H * 0.45;
  circle("y", bcx, bcy - L, r); circle("a", bcx, bcy + L, r);
  circle("x", bcx - L, bcy, r); circle("b", bcx + L, bcy, r);
  var sw = S * 0.12, sh = S * 0.062;
  rect("select", W * 0.43, H * 0.90, sw, sh);
  rect("start", W * 0.57, H * 0.90, sw, sh);
  var tw = S * 0.12, th = S * 0.06;
  rect("l1", W * 0.06, H * 0.075, tw, th);
  rect("l2", W * 0.06, H * 0.185, tw, th);
  rect("r1", W * 0.94, H * 0.075, tw, th);
  rect("r2", W * 0.94, H * 0.185, tw, th);
  return shapes;
}

// 十字解向（臂上先有向：中軸死區／角罅／出界回 null；供 down／slide／消震命中）。
function campadDpadDir(x, y) {
  var g = __campadDpad;
  if (!g || !g.r) return null;
  var dx = x - g.cx, dy = y - g.cy;
  var adx = Math.abs(dx), ady = Math.abs(dy);
  if (Math.max(adx, ady) > g.L + g.r) return null;
  if (Math.min(adx, ady) > g.r) return null;
  if (Math.max(adx, ady) < g.dead) return null;
  return adx > ady ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
}

function campadRoundRect(ctx, x, y, w, h, rad) {
  try {
    if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, rad); return; }
  } catch (e) {}
  ctx.beginPath();
  ctx.rect(x, y, w, h);
}

function campadFmtAxis(v) {
  var r = Math.round(v * 100) / 100;
  return (r < 0 ? "−" : "+") + Math.abs(r).toFixed(2);
}

// 畫：掣（鍵名置中，有映射實色、none dim、撳住藍、taken 閃紅）＋搖桿（底盤＋
// 菇＋L／R 標）＋底讀數條（搖桿 live 偏轉量／撳住 servo 限位 min-max）。
function cameraPadDraw() {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv || !cameraPadViewOn) return;
  var W = cv.clientWidth || 480, H = cv.clientHeight || 360;
  var dpr = 1;
  try { dpr = window.devicePixelRatio || 1; } catch (e) {}
  var pw = Math.max(1, Math.round(W * dpr)), ph = Math.max(1, Math.round(H * dpr));
  if (cv.width !== pw || cv.height !== ph) { cv.width = pw; cv.height = ph; }
  var ctx = null;
  try { ctx = cv.getContext("2d"); } catch (e) { return; }
  if (!ctx) return;
  try { ctx.setTransform(dpr, 0, 0, dpr, 0, 0); } catch (e) {}
  ctx.clearRect(0, 0, W, H);
  __cameraPadShapes = cameraPadLayout(W, H);
  var now = Date.now();
  for (var i = 0; i < __cameraPadShapes.length; i++) {
    drawCampadShape(ctx, __cameraPadShapes[i], now);
  }
  // 十字掣（一體 plus 形；著臂圓形高亮蓋面）。
  drawCampadCross(ctx, W, H, now);
  var defs = campadStickDefs(W, H);
  for (var s = 0; s < defs.length; s++) {
    drawCampadStick(ctx, defs[s]);
  }
  drawCampadReadout(ctx, W, H);
}

// 十字掣本體（12 頂點 plus 形；臂＝圓心±L、半闊 r、梢 L+r，同解向幾何一致）。
function drawCampadCross(ctx, W, H, now) {
  var g = __campadDpad;
  if (!g || !g.r) return;
  var cx = g.cx, cy = g.cy, L = g.L, r = g.r, T = L + r;
  var map = __cameraPadMap;
  function mapped(k) { var v = map[k]; return v && v !== "none"; }
  var anyMapped = mapped("up") || mapped("down") || mapped("left") || mapped("right");
  ctx.beginPath();
  ctx.moveTo(cx - r, cy - T);
  ctx.lineTo(cx + r, cy - T);
  ctx.lineTo(cx + r, cy - r);
  ctx.lineTo(cx + T, cy - r);
  ctx.lineTo(cx + T, cy + r);
  ctx.lineTo(cx + r, cy + r);
  ctx.lineTo(cx + r, cy + T);
  ctx.lineTo(cx - r, cy + T);
  ctx.lineTo(cx - r, cy + r);
  ctx.lineTo(cx - T, cy + r);
  ctx.lineTo(cx - T, cy - r);
  ctx.lineTo(cx - r, cy - r);
  ctx.closePath();
  ctx.fillStyle = anyMapped ? "rgba(20,22,28,0.72)" : "rgba(20,22,28,0.42)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = "rgba(255,255,255,0.45)";
  ctx.stroke();
  // 著臂高亮（藍；taken:false 閃紅）。
  var arms = [["up", cx, cy - L], ["down", cx, cy + L], ["left", cx - L, cy], ["right", cx + L, cy]];
  for (var i = 0; i < arms.length; i++) {
    var k = arms[i][0];
    var lit = !!__cameraPadLit[k];
    var bad = __cameraPadNo[k] && (now - __cameraPadNo[k] < 700);
    if (lit || bad) {
      ctx.beginPath();
      ctx.arc(arms[i][1], arms[i][2], r, 0, Math.PI * 2);
      ctx.fillStyle = bad ? "rgba(220,38,38,0.88)" : "rgba(37,99,235,0.9)";
      ctx.fill();
      ctx.lineWidth = 1.5;
      ctx.strokeStyle = "rgba(255,255,255,0.9)";
      ctx.stroke();
    }
  }
  // 臂名（無映射 dim）。
  var labels = [["up", "▲", cx, cy - L], ["down", "▼", cx, cy + L],
    ["left", "◀", cx - L, cy], ["right", "▶", cx + L, cy]];
  var kfs = Math.max(10, Math.min(19, r * 0.62));
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  try { ctx.font = "700 " + Math.round(kfs) + "px sans-serif"; } catch (e) {}
  for (var j = 0; j < labels.length; j++) {
    ctx.fillStyle = (mapped(labels[j][0]) || __cameraPadLit[labels[j][0]])
      ? "#ffffff" : "rgba(255,255,255,0.55)";
    ctx.fillText(labels[j][1], labels[j][2], labels[j][3]);
  }
}

function drawCampadShape(ctx, sh, now) {
  var v = __cameraPadMap[sh.key] || "none";
  var none = (!v || v === "none");
  var lit = !!__cameraPadLit[sh.key];
  var bad = __cameraPadNo[sh.key] && (now - __cameraPadNo[sh.key] < 700);
  ctx.beginPath();
  var cx, cy;
  if (sh.kind === "c") {
    ctx.arc(sh.x, sh.y, sh.r, 0, Math.PI * 2);
    cx = sh.x; cy = sh.y;
  } else {
    campadRoundRect(ctx, sh.x, sh.y, sh.w, sh.h, Math.min(sh.w, sh.h) * 0.3);
    cx = sh.x + sh.w / 2; cy = sh.y + sh.h / 2;
  }
  ctx.fillStyle = bad ? "rgba(220,38,38,0.88)"
    : lit ? "rgba(37,99,235,0.9)"
    : none ? "rgba(20,22,28,0.42)" : "rgba(20,22,28,0.72)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = (lit || bad) ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.45)";
  ctx.stroke();
  var baseR = sh.kind === "c" ? sh.r : Math.min(sh.w, sh.h) / 2;
  var kfs = Math.max(10, Math.min(19, baseR * 0.62));
  ctx.fillStyle = (none && !lit) ? "rgba(255,255,255,0.55)" : "#ffffff";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  try { ctx.font = "700 " + Math.round(kfs) + "px sans-serif"; } catch (e) {}
  ctx.fillText(cameraPadKeyShort(sh.key), cx, cy);
}

function drawCampadStick(ctx, def) {
  var st = def.st;
  var mx = def.id === "L" ? __campadMirror.lx : __campadMirror.rx;
  var my = def.id === "L" ? __campadMirror.ly : __campadMirror.ry;
  var dx = st.held ? st.dx : mx, dy = st.held ? st.dy : my;
  ctx.beginPath();
  ctx.arc(def.x, def.y, def.R, 0, Math.PI * 2);
  ctx.fillStyle = "rgba(20,22,28,0.55)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = st.held ? "rgba(255,255,255,0.9)" : "rgba(255,255,255,0.45)";
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(def.x - def.R, def.y); ctx.lineTo(def.x + def.R, def.y);
  ctx.moveTo(def.x, def.y - def.R); ctx.lineTo(def.x, def.y + def.R);
  ctx.lineWidth = 1;
  ctx.strokeStyle = "rgba(255,255,255,0.22)";
  ctx.stroke();
  var nx = def.x + dx * (def.R - def.nub), ny = def.y + dy * (def.R - def.nub);
  ctx.beginPath();
  ctx.arc(nx, ny, def.nub, 0, Math.PI * 2);
  ctx.fillStyle = st.held ? "rgba(37,99,235,0.92)" : "rgba(160,165,175,0.85)";
  ctx.fill();
  ctx.lineWidth = 1.5;
  ctx.strokeStyle = "rgba(255,255,255,0.7)";
  ctx.stroke();
  try { ctx.font = "700 11px sans-serif"; } catch (e) {}
  ctx.fillStyle = "rgba(255,255,255,0.85)";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.fillText(def.id, def.x, def.y - def.R - 9);
}

function drawCampadReadout(ctx, W, H) {
  var parts = [];
  if (__campadStickL.held || Math.abs(__campadStickL.dx) > 0.005 || Math.abs(__campadStickL.dy) > 0.005) {
    parts.push("LX " + campadFmtAxis(__campadStickL.dx) + " LY " + campadFmtAxis(__campadStickL.dy));
  }
  if (__campadStickR.held || Math.abs(__campadStickR.dx) > 0.005 || Math.abs(__campadStickR.dy) > 0.005) {
    parts.push("RX " + campadFmtAxis(__campadStickR.dx) + " RY " + campadFmtAxis(__campadStickR.dy));
  }
  for (var k in __cameraPadDown) {
    if (!__cameraPadDown[k]) continue;
    var multi = gamepadSplitMulti(__cameraPadMap[k] || "none");
    for (var mi2 = 0; mi2 < multi.length; mi2++) {
      var m = (multi[mi2] || "").match(/^servo(\d{1,2})([+-])$/);
      if (!m) continue;
      var id = parseInt(m[1], 10);
      var cal = null;
      try { cal = SERVO_CALIBRATION[id]; } catch (e) {}
      parts.push("#" + id + (cal ? " [" + cal.min + "-" + cal.max + "]" : ""));
    }
  }
  if (!parts.length) return;
  var txt = parts.join("   ");
  try { ctx.font = "11px sans-serif"; } catch (e) {}
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  var tw = 0;
  try { tw = ctx.measureText(txt).width; } catch (e) { tw = txt.length * 7; }
  var bx = W / 2 - tw / 2 - 8, by = 8, bw = tw + 16, bh = 18;
  ctx.beginPath();
  if (ctx.roundRect) { try { ctx.roundRect(bx, by, bw, bh, 9); } catch (e) { ctx.rect(bx, by, bw, bh); } }
  else ctx.rect(bx, by, bw, bh);
  ctx.fillStyle = "rgba(0,0,0,0.55)";
  ctx.fill();
  ctx.fillStyle = "#ffffff";
  ctx.fillText(txt, W / 2, by + bh / 2);
}

// canvas 掣短鍵名（▲▼◀▶／大寫）。
function cameraPadKeyShort(k) {
  if (k === "up") return "▲";
  if (k === "down") return "▼";
  if (k === "left") return "◀";
  if (k === "right") return "▶";
  return String(k).toUpperCase();
}

function cameraPadRefresh() {
  var cv = document.getElementById("cameraPadCanvas");
  if (!cv) return Promise.resolve(null);
  return Alpha2Api.gamepadConfigGet({ side: "pad" }).then(function (json) {
    var map = (json && json.mapping) || gamepadCachedSide("pad");
    try { gamepadCacheSide("pad", map); } catch (e) {}
    __cameraPadMap = map;
    cameraPadDraw();
    return map;
  }).catch(function () {
    __cameraPadMap = gamepadCachedSide("pad");
    cameraPadDraw();
    return null;
  });
}

function cameraPadPress(k, isDown, pid) {
  if (!k) return;
  if (isDown) {
    if (__cameraPadDown[k]) {
      // 真・多指同撳先靜音；唔係即係之前漏 up 殘留，清咗當新撳（唔會食咗下撳變死掣）。
      if (campadLiveHoldersKey(k, pid) > 0) return;
      __cameraPadDown[k] = false;
      __cameraPadLit[k] = false;
    }
    __cameraPadDown[k] = true;
    __cameraPadLit[k] = true;
    cameraPadDraw();
    Alpha2Api.gamepadKey({ side: "pad", key: k, action: "down", source: "campad" }).then(function (json) {
      if (json && json.taken === false) {
        __cameraPadNo[k] = Date.now();
        cameraPadDraw();
        var hint = document.getElementById("cameraStatusHint");
        if (hint) hint.textContent = cameraPadKeyShort(k) + " → " + (json.action || "-") + " " + t("gamepad_taken_no");
        setTimeout(function () { cameraPadDraw(); }, 750);
      }
    }).catch(function () {
      // down 失敗先至熄；仲有其他手指撳緊就唔好郁（跟 live 表）。
      try {
        if (campadLiveHoldersKey(k, pid) === 0) {
          __cameraPadDown[k] = false;
          __cameraPadLit[k] = false;
          cameraPadDraw();
        }
      } catch (e2) {}
    });
  } else {
    if (!__cameraPadDown[k]) return;
    // 仲有其他手指撳緊同一粒就唔好郁，齊放先真係 up。
    if (campadLiveHoldersKey(k, pid) > 0) return;
    __cameraPadDown[k] = false;
    __cameraPadLit[k] = false;
    cameraPadDraw();
    gamepadNoteLocalUp("pad", k);
    campadSendUp(k);
  }
}

// ---------------- 兩張圖全屏（淨手柄／鍵盤圖，唔包其他） ----------------
// 全屏對象係張圖本身（gpPadDiagram／kbBoardDiagram）：掣綁喺原元素，
// 全屏唔 clone，撳掣／拖桿照行 gamepad/key／axis；圖貼滿上下左右（見 style.css）。
// 瀏覽器 Esc 自動退出；圖內右上角另有 ✕ 掣（手機無 Esc 照出到，見 gamepadFsEnsureExitBtns）。
// Native Fullscreen API 有即用，無（舊 WebView／iOS）即跌落 CSS fallback
// （.gp-fs-fallback：fixed 蓋版，同樣貼滿放大）。
// 出入全屏一律放晒撳住嘅掣＋回中搖桿（唔留 servo 一直走）。
function gamepadFsEl(side) {
  return document.getElementById(side === "kb" ? "kbBoardDiagram" : "gpPadDiagram");
}

function gamepadFsNativeEl() {
  try {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  } catch (e) {
    return null;
  }
}

function gamepadIsFullscreen(side) {
  var el = gamepadFsEl(side);
  if (!el) return false;
  if (gamepadFsNativeEl() === el) return true;
  try {
    if (el.classList && el.classList.contains("gp-fs-fallback")) return true;
  } catch (e) {}
  return false;
}

function gamepadAnyFullscreen() {
  if (gamepadFsNativeEl()) return true;
  try {
    if (document.querySelector && document.querySelector(".gp-fs-fallback")) return true;
  } catch (e) {}
  return false;
}

function gamepadSyncFsBtns() {
  var pairs = [["pad", "gamepadPadFsBtn"], ["kb", "gamepadKbFsBtn"]];
  for (var i = 0; i < pairs.length; i++) {
    var btn = document.getElementById(pairs[i][1]);
    if (!btn) continue;
    var on = gamepadIsFullscreen(pairs[i][0]);
    var key = on ? "gamepad_exit_fullscreen_btn" : "gamepad_fullscreen_btn";
    try { btn.textContent = t(key); } catch (e) {
      btn.textContent = on ? "✕ 退出全屏" : "⛶ 全屏";
    }
    // data-i18n 唔跟全屏態（兩態唔同 key），轉語言嗰陣唔好俾 applyUiLanguage 寫返轉頭。
    try { btn.removeAttribute("data-i18n"); } catch (e2) {}
  }
}

function gamepadFsFallback(el) {
  if (!el) return;
  try { el.classList.add("gp-fs-fallback"); } catch (e) {}
  try { document.body.classList.add("gp-fs-open"); } catch (e) {}
  gamepadSyncFsBtns();
}

function gamepadExitFullscreen() {
  var fs = gamepadFsNativeEl();
  if (fs) {
    try {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    } catch (e) {}
  }
  ["gpPadDiagram", "kbBoardDiagram"].forEach(function (id) {
    var el = document.getElementById(id);
    if (el) {
      try { el.classList.remove("gp-fs-fallback"); } catch (e) {}
    }
  });
  try { document.body.classList.remove("gp-fs-open"); } catch (e) {}
  try { gamepadDiagReleaseAll(); } catch (e) {}
  gamepadSyncFsBtns();
}

function gamepadToggleFullscreen(side) {
  side = (side === "kb") ? "kb" : "pad";
  if (gamepadIsFullscreen(side)) {
    gamepadExitFullscreen();
    return;
  }
  // 另一張開緊先閂（一次最多一張全屏）。
  if (gamepadAnyFullscreen()) gamepadExitFullscreen();
  var el = gamepadFsEl(side);
  if (!el) return;
  var req = null;
  try { req = el.requestFullscreen || el.webkitRequestFullscreen; } catch (e) { req = null; }
  if (req) {
    try {
      var p = req.call(el);
      if (p && p.catch) {
        p.catch(function () { gamepadFsFallback(el); });
      }
    } catch (e) {
      gamepadFsFallback(el);
      return;
    }
  } else {
    gamepadFsFallback(el);
    return;
  }
  gamepadSyncFsBtns();
}

// 圖內 ✕ 退出掣（全屏先現身，見 style.css .gp-fs-exit；平時 display:none）。
// 掣本身無 gpcap-/data-kb 標記，pointerdown 掠過唔會誤觸發按鍵，原生 click 即退出。
function gamepadFsEnsureExitBtns() {
  [["gpPadDiagram"], ["kbBoardDiagram"]].forEach(function (pair) {
    var host = document.getElementById(pair[0]);
    if (!host) return;
    try {
      if (host.querySelector && host.querySelector(".gp-fs-exit")) return;
    } catch (e) {}
    var b = document.createElement("button");
    b.className = "gp-fs-exit secondary";
    b.textContent = "✕";
    try {
      b.setAttribute("data-i18n", "gamepad_exit_fullscreen_btn");
      b.setAttribute("data-i18n-attr", "title");
      b.setAttribute("title", t("gamepad_exit_fullscreen_btn"));
      b.setAttribute("aria-label", t("gamepad_exit_fullscreen_btn"));
    } catch (e) {}
    b.onclick = function () { gamepadExitFullscreen(); };
    // 掣自己唔好觸發圖上 pointer 綁定（pad／kb down handler 認 gpcap-/data-kb，呢粒無，即掠過）。
    try { host.insertBefore(b, host.firstChild); } catch (e) {
      try { host.appendChild(b); } catch (e2) {}
    }
  });
}

// 全屏版面跟相機手柄 canvas（cameraPadLayout 按 W/H 比例排）同一個諗法：
// 純 CSS 比例單位（vmin／dvh），任何屏幕直／橫都唔超唔碌，唔使 JS 度大細，
// 轉向／resize 瀏覽器自己重排。
function gamepadBindFullscreen() {
  try {
    document.addEventListener("fullscreenchange", function () {
      try { gamepadDiagReleaseAll(); } catch (e) {}
      gamepadSyncFsBtns();
    });
  } catch (e) {}
  try {
    document.addEventListener("webkitfullscreenchange", function () {
      try { gamepadDiagReleaseAll(); } catch (e2) {}
      gamepadSyncFsBtns();
    });
  } catch (e) {}
}

// ---------------- 初始化（app-log.js init 呼叫） ----------------

function gamepadInitPage() {
  gamepadBuildKeyboardDiagram();
  try { gamepadBindDiagrams(); } catch (e) {}
  try { gamepadBindFullscreen(); } catch (e) {}
  gamepadSyncMappingToggles();
  try { gamepadSyncFsBtns(); } catch (e) {}
  return gamepadRefresh();
}
