// Open Alpha2 — client logic (app-group.js)
// 內容: 群控鏡像——開掣自動搜尋同網段機械人，之後呢部機做乜、跟隨名單上嘅機跟做乜；關掣即停。
// 原理: 每部機都係獨立 HTTP server (:8888，已開 CORS，見 HttpServer.java)，
//       瀏覽器直接 fan-out fetch 去每部機，唔經後端轉發，所以唔使改 Java／OpenAPI／spec。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- 跟隨名單 ----------------
// 存喺 localStorage "group_ips"，每行一部，格式：IP [token]，例如：
//   192.168.1.101
//   192.168.1.102 12345678        (呢部有開面板口令就空格後加 token)
//   http://192.168.1.103:8888
// 無寫 port 預設 8888；token 無寫就用而家呢頁記住的 panel_token。
// 名單由開鏡像嗰陣自動搜尋填入＋手加行補充，平時唔使搞。
function groupParseText(raw) {
  const out = [];
  (raw || "").split("\n").forEach(function (line) {
    const t = line.trim();
    if (!t || t.charAt(0) === "#") return;
    const parts = t.split(/[\s,;]+/).filter(function (s) { return !!s; });
    if (parts.length === 0) return;
    let host = parts[0].replace(/\/+$/, "");
    let base;
    if (/^https?:\/\//i.test(host)) {
      base = host;
    } else if (host.indexOf(":") >= 0) {
      base = "http://" + host;
    } else {
      base = "http://" + host + ":8888";
    }
    out.push({ base: base, label: parts[0], token: parts[1] || "" });
  });
  return out;
}

function groupParseList() {
  return groupParseText(groupGetSavedText());
}

function groupGetSavedText() {
  try { return localStorage.getItem("group_ips") || ""; } catch (e) { return ""; }
}

function groupSetSavedText(t) {
  try { localStorage.setItem("group_ips", t || ""); } catch (e) { /* private mode 寫不入就當無記住 */ }
  try { mirrorRefreshLabel(); } catch (e) {}
}

// 手加行（有口令嘅機掃唔到先用）：一行 "IP token"，併入名單即檢測。
function groupManualAdd() {
  const el = document.getElementById("groupManual");
  const line = el ? (el.value || "").trim() : "";
  if (!line) return;
  const cur = groupGetSavedText().replace(/\s+$/, "");
  groupSetSavedText((cur ? cur + "\n" : "") + line + "\n");
  if (el) el.value = "";
  groupCheckOnline();
}

// ---------------- 單機 fetch ----------------
// 同 app-core.js api()/sysApi() 一樣，只係 base 係目標機械人（跨機，靠後端 CORS * 放行）。
// prefix: alpha2（主 API）/ system（音樂/探索）/ direct（底層直調）/ xiaozhi（AI 橋接）。
function groupFetchPrefix(entry, prefix, apiPath, params) {
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  const url = entry.base + "/api/" + prefix + "/" + apiPath + qs;
  const headers = {};
  const tok = entry.token || ((typeof panelTokenGet === "function") ? panelTokenGet() : "");
  if (tok) headers["Authorization"] = "Bearer " + tok;
  const ctrl = new AbortController();
  const timer = setTimeout(function () { try { ctrl.abort(); } catch (e) {} }, 6000);
  return fetch(url, { headers: headers, signal: ctrl.signal }).then(function (res) {
    clearTimeout(timer);
    return res.json().catch(function () {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      return { base: entry.base, label: entry.label, ok: !!(json && json.ok), data: json };
    });
  }).catch(function (err) {
    clearTimeout(timer);
    const msg = (err && err.name === "AbortError") ? "timeout (6s)" : String(err);
    return { base: entry.base, label: entry.label, ok: false, data: { ok: false, error: msg } };
  });
}

function groupFetchSystem(entry, sysPath) {
  return groupFetchPrefix(entry, "system", sysPath, null);
}

function groupNeedList() {
  const list = groupParseList();
  if (list.length === 0) {
    showError(t("group_heading"), new Error(t("group_empty_list")));
  }
  return list;
}

// ---------------- 鏡像模式 ----------------
// 開掣後，係呢部機面板㩒任何掣（動作／舵機／LED／講嘢／音樂…），名單上其他機都會同步做一次。
// 實現：app-core.js 嘅 api()/sysApi()/directApi()、app-xiaozhi.js 嘅 xiaozhiApi()、
// blockly-page.js 嘅同名 helper，每次呼叫都會順手調 mirrorFanout()（fire-and-forget，
// 回傳照舊只跟本機結果，跟隨失敗靜默略過，絕對唔彈 error banner 阻住本機操作）。
// 群控 tab 自己嘅 fan-out（groupFetchPrefix）唔經嗰幾個 helper，所以唔會遞歸。
function mirrorEnabled() {
  try { return localStorage.getItem("mirror_on") === "1"; } catch (e) { return false; }
}

// 呢次呼叫應唔應該鏡像：淨係 101 個「郁作類」寫入（播／舵機／燈／聲／跟隨／小智對話…），
// 其餘 98 個一律唔跟——查詢輪詢、認證、探索、燒韌體（upgrade）、下載、機械人／舵機 ID 寫入、
// 影相錄影、收藏。規則已對住 api-client.js 全部 199 個 endpoint 逐條驗過
// （101 鏡像／98 跳過）。
function mirrorShouldSend(prefix, path) {
  const full = prefix + "/" + path;
  if (/auth\//.test(full)) return false;                        // 面板認證：永不
  if (/(^|\/)discover$/.test(full)) return false;               // 探索：純讀
  if (/upgrade/.test(full)) return false;                      // 燒韌體（胸板／舵機）：永不，變磚風險
  if (/download/.test(full)) return false;                     // 下載（動作包／APK／Vosk 幾十 MB）：永不
  if (/misc\/set_uuid/.test(full)) return false;               // 機械人 ID：每部必須唯一，跟咗會撞 ID
  if (/servo\/modify-id/.test(full)) return false;             // 舵機 ID：逐部手術，唔跟
  if (/^alpha2\/chest\//.test(full)) return false;             // 胸板升級機械：永不
  if (/^alpha2\/servo\/(angle|back-angle|read|version|hardware)/.test(full)) return false; // 純讀（cmd6 會令舵機瞬間鬆力）
  if (/snapshot|take_photo/.test(full)) return false;          // 影相（二進制回傳，A 機 UI 會亂）
  if (/camera\/video\//.test(full)) return false;              // 錄影（存儲副作用，用萬能指令逐部開）
  if (/favorites\//.test(full)) return false;                  // 收藏：數據管理，唔跟
  if (/\/cur_tts_/.test(full)) return false;                   // TTS 現況查詢
  if (/\/get/.test(full)) return false;                        // /get、/get_xxx、get_default_grammar（純讀；寫入類無一個含 /get，已驗）
  if (/(voices|languages|engines|status|search|sizes|request_uuid)$/.test(full)) return false; // 各類查詢尾
  if (/\/(list|verify|info|catalog|models|supported|fps|spectrum|probe|page)$/.test(full)) return false; // 各類查詢尾
  return true;
}

// 名單入面嗰個係咪自己（開面板呢部）：同 host＋同 port 先算——經 adb forward（localhost）
// 開唔會誤殺 LAN IP；掃機嗰陣重會用 UUID 再剔一次。
function groupIsSelfEntry(entry) {
  try {
    const m = /^https?:\/\/([^\/:]+)(?::(\d+))?/i.exec(entry.base || "");
    if (!m) return false;
    if (m[1].toLowerCase() !== (location.hostname || "").toLowerCase()) return false;
    return (m[2] || "8888") === (location.port || "8888");
  } catch (e) { return false; }
}

function mirrorFanout(prefix, path, params) {
  try {
    if (!mirrorEnabled()) return;
    if (!mirrorShouldSend(prefix, path)) return;
    let saved = "";
    try { saved = localStorage.getItem("group_ips") || ""; } catch (e) {}
    const list = groupParseText(saved).filter(function (e) { return !groupIsSelfEntry(e); });
    if (list.length === 0) return;
    list.forEach(function (e) {
      try { groupFetchPrefix(e, prefix, path, params); } catch (err) {}
    });
  } catch (e) {}
}

function mirrorToggle() {
  const el = document.getElementById("mirrorToggle");
  const on = !!(el && el.checked);
  try { localStorage.setItem("mirror_on", on ? "1" : "0"); } catch (e) {}
  mirrorRefreshLabel();
  // 開掣即自動搜尋同網段機械人，掃完名單即用；關掣即停（mirrorFanout 每次都睇掣）。
  if (on) { try { groupScanSubnet(); } catch (e) {} }
}

// 進階卡總開關（同 panelAuthCardToggle 一致寫法）：預設收起，揭開先顯示內容；
// 不記狀態，每次入頁預設關。入面 Mirror 掣本身狀態獨立記喺 localStorage。
function groupCardToggle() {
  const enabled = document.getElementById("groupCardEnabled");
  const body = document.getElementById("groupCardBody");
  const hint = document.getElementById("groupCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
}

// 語言切換重標（setUiLanguage 經呢度呼叫，同其他卡嘅 *ApplyUiLanguage 一致）。
function groupApplyUiLanguage() {
  try { mirrorRefreshLabel(); } catch (e) {}
}

function mirrorRefreshLabel() {
  const el = document.getElementById("mirrorToggle");
  const st = document.getElementById("mirrorStatus");
  const on = mirrorEnabled();
  if (el) el.checked = on;
  if (st) {
    let n = 0;
    try { n = groupParseText(groupGetSavedText()).length; } catch (e) {}
    st.textContent = on ? t("group_status_on", { n: n }) : t("group_status_off");
  }
}

// ---------------- 子網自動搜機 ----------------
// 由而家開緊嘅面板地址估子網（http://192.168.1.50:8888/ → 192.168.1.），
// 逐個 probe :8888/api/system/discover（1.5s timeout，32 並行一批），
// 搵到嘅自動併入名單（保留原有 token，剔走自己），跟住自動跑一次在線檢測。
function groupDefaultSubnet() {
  try {
    const m = /^(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}$/.exec(location.hostname || "");
    if (m) return m[1] + ".";
  } catch (e) {}
  return "";
}

function groupProbeOne(prefix, i, selfIp, selfUuid) {
  const url = "http://" + prefix + i + ":8888/api/system/discover";
  const headers = {};
  try {
    const tok = (typeof panelTokenGet === "function") ? panelTokenGet() : "";
    if (tok) headers["Authorization"] = "Bearer " + tok;
  } catch (e) {}
  const ctrl = new AbortController();
  const timer = setTimeout(function () { try { ctrl.abort(); } catch (e) {} }, 1500);
  return fetch(url, { headers: headers, signal: ctrl.signal }).then(function (res) {
    clearTimeout(timer);
    return res.json().catch(function () { return null; }).then(function (json) {
      if (!json || !json.ok) return null;
      const ip = prefix + i;
      const uuid = (json.robot && json.robot.chestUuid) || null;
      if (ip === selfIp) return null;                        // 自己唔入名單
      if (uuid && selfUuid && uuid === selfUuid) return null; // adb forward 時 IP 認唔到，用 UUID 認
      return { ip: ip, uuid: uuid };
    });
  }).catch(function () { clearTimeout(timer); return null; });
}

function groupScanSubnet() {
  const input = document.getElementById("groupSubnet");
  let prefix = ((input && input.value) || "").trim() || groupDefaultSubnet();
  if (!/^\d{1,3}\.\d{1,3}\.\d{1,3}\.$/.test(prefix)) {
    showError(t("group_heading"), new Error(t("group_scan_invalid")));
    return;
  }
  if (input) input.value = prefix;
  const st = document.getElementById("groupScanStatus");
  const say = function (t2) { if (st) st.textContent = t2; };
  say(t("group_scanning", { subnet: prefix, done: 0, found: 0 }));
  // 先問自己攞 IP＋UUID，免將自己加入跟隨名單。
  Alpha2Api.systemDiscover().then(function (self) {
    let selfIp = "";
    let selfUuid = null;
    try { selfIp = location.hostname || ""; } catch (e) {}
    try { selfUuid = (self && self.robot && self.robot.chestUuid) || null; } catch (e) {}
    const found = [];
    let done = 0;
    const batch = function (start) {
      const jobs = [];
      for (let i = start; i < start + 32 && i <= 254; i++) {
        jobs.push(groupProbeOne(prefix, i, selfIp, selfUuid).then(function (r) {
          done++;
          if (r) found.push(r);
          say(t("group_scanning", { subnet: prefix, done: done, found: found.length }));
        }));
      }
      return Promise.all(jobs).then(function () {
        if (start + 32 <= 254) return batch(start + 32);
        return null;
      });
    };
    return batch(1).then(function () { return found; });
  }).then(function (found) {
    if (!found) return;
    // 併入名單：已有 IP 保留原行（含手加 token），新搵到嘅 append 裸 IP。
    const cur = groupGetSavedText();
    const have = {};
    groupParseText(cur).forEach(function (e) { have[e.base] = true; });
    const lines = cur ? cur.split("\n") : [];
    found.forEach(function (f) {
      const base = "http://" + f.ip + ":8888";
      if (!have[base]) { lines.push(f.ip); have[base] = true; }
    });
    groupSetSavedText(lines.join("\n"));
    say(t("group_scan_found", { n: found.length }) + (found.length ? ((uiLang === "en" ? ": " : "：") + found.map(function (f) { return f.ip; }).join(uiLang === "en" ? ", " : "、")) : ""));
    groupCheckOnline();
  }).catch(function (err) {
    say(t("group_scan_failed", { err: String((err && err.message) || err) }));
  });
}

// ---------------- 在線檢測 ----------------
function groupCheckOnline() {
  const list = groupNeedList();
  if (list.length === 0) return;
  const out = document.getElementById("groupRobotList");
  if (out) out.textContent = t("group_checking");
  Promise.all(list.map(function (e) { return groupFetchSystem(e, "discover"); })).then(function (results) {
    if (!out) return;
    out.innerHTML = "";
    results.forEach(function (r) {
      const chip = document.createElement("div");
      chip.className = "chip";
      let text = (r.ok ? "✓ " : "✗ ") + r.label;
      if (r.ok && r.data && r.data.robot && r.data.robot.chestUuid) text += " (" + r.data.robot.chestUuid + ")";
      if (!r.ok && r.data && r.data.error) text += " — " + r.data.error;
      chip.textContent = text;
      chip.style.setProperty("--chip-color", r.ok ? "#16a34a" : "#dc2626");
      out.appendChild(chip);
    });
  });
}

// 入頁：子網預設＋鏡像掣狀態；如果上次鏡像開住，即刻重掃一次（DHCP 換 IP 都跟得返）。
document.addEventListener("DOMContentLoaded", function () {
  try {
    const s = document.getElementById("groupSubnet");
    if (s && !s.value) s.value = groupDefaultSubnet();
  } catch (e) {}
  try { mirrorRefreshLabel(); } catch (e) {}
  try { if (mirrorEnabled()) groupScanSubnet(); } catch (e) {}
});
