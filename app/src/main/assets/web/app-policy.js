// Open Alpha2 — client logic (app-policy.js)
// 實驗 tab「ONNX Policy」卡：上載 policy（zip，或 .onnx＋ref.json 由瀏覽器自動打包成 zip）→ 載入 →
// 排練（dry-run）／真機運行（要明確取消 dry；50Hz 要 fast）→ 即時狀態。
// 後端見 PolicyCenter＋/upload/policy＋policy/*；契約同限制見 docs/onnx-policy.md。
// 全部檔案共用 window/global scope（沒有用 ES module），載入順序由 index.html 決定。

var polTimer = null;        // 運行中狀態輪詢
var polLast = null;         // 最近一次 PolicyStatus（轉語言重畫用）
var polBusy = false;        // 上載中
var polStartAt = 0;         // 最近一次成功按「開始」嘅時間（ms）：之後幾秒內即使狀態未顯示 running 都照樣輪詢＋心跳

function polEl(id) { return document.getElementById(id); }

// ── 純前端 zip（stored，無壓縮）：.onnx 通常壓唔細，免咗帶 zip 庫 ───────────────
var polCrcTable = null;
function polCrc32(u8) {
  if (!polCrcTable) {
    polCrcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      polCrcTable[n] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < u8.length; i++) crc = polCrcTable[(crc ^ u8[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

// entries: [{ name: "policy.onnx", data: Uint8Array }]（name 只用 ASCII）
function polBuildZip(entries) {
  const enc = new TextEncoder();
  const parts = [];
  const central = [];
  let offset = 0;
  entries.forEach(function (e) {
    const nm = enc.encode(e.name);
    const crc = polCrc32(e.data);
    const lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true); lh.setUint16(4, 20, true); lh.setUint16(6, 0, true);
    lh.setUint16(8, 0, true); lh.setUint16(10, 0, true); lh.setUint16(12, 0x21, true);
    lh.setUint32(14, crc, true); lh.setUint32(18, e.data.length, true); lh.setUint32(22, e.data.length, true);
    lh.setUint16(26, nm.length, true); lh.setUint16(28, 0, true);
    parts.push(new Uint8Array(lh.buffer), nm, e.data);
    const ch = new DataView(new ArrayBuffer(46));
    ch.setUint32(0, 0x02014b50, true); ch.setUint16(4, 20, true); ch.setUint16(6, 20, true);
    ch.setUint16(8, 0, true); ch.setUint16(10, 0, true); ch.setUint16(12, 0, true); ch.setUint16(14, 0x21, true);
    ch.setUint32(16, crc, true); ch.setUint32(20, e.data.length, true); ch.setUint32(24, e.data.length, true);
    ch.setUint16(28, nm.length, true); ch.setUint16(30, 0, true); ch.setUint16(32, 0, true);
    ch.setUint16(34, 0, true); ch.setUint16(36, 0, true); ch.setUint32(38, 0, true); ch.setUint32(42, offset, true);
    central.push(new Uint8Array(ch.buffer), nm);
    offset += 30 + nm.length + e.data.length;
  });
  let cdSize = 0;
  central.forEach(function (p) { cdSize += p.length; });
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
  end.setUint32(12, cdSize, true); end.setUint32(16, offset, true);
  return new Blob(parts.concat(central, [new Uint8Array(end.buffer)]), { type: "application/zip" });
}

function polReadFile(file) {
  return new Promise(function (resolve, reject) {
    const r = new FileReader();
    r.onload = function () { resolve(new Uint8Array(r.result)); };
    r.onerror = function () { reject(r.error || new Error("read failed")); };
    r.readAsArrayBuffer(file);
  });
}

function polBase(name) { return String(name).split(/[\\/]/).pop().toLowerCase(); }

// 由檔名建議 policy 名（去副檔名，只留合法字元）。
function polSuggestName(fileName) {
  let n = String(fileName).replace(/\.[^.]*$/, "").replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^[^A-Za-z0-9]+/, "");
  n = n.replace(/\.\.+/g, ".").slice(0, 48);
  return n || "policy";
}

function polFilesChanged() {
  const inp = polEl("polFile");
  const nameEl = polEl("polName");
  if (!inp || !inp.files || !inp.files.length) return;
  if (nameEl && !nameEl.value) {
    // 優先用 .onnx／.zip 嘅檔名。
    let pick = inp.files[0];
    for (let i = 0; i < inp.files.length; i++) {
      if (/\.(onnx|zip)$/i.test(inp.files[i].name)) { pick = inp.files[i]; break; }
    }
    nameEl.value = polSuggestName(pick.name);
  }
  polSetOut("pol_files_picked", { n: inp.files.length });
}

function polSetOut(key, params) {
  const out = polEl("polUploadOut");
  if (out) out.textContent = t(key, params);
}

function polSetOutRaw(text) {
  const out = polEl("polUploadOut");
  if (out) out.textContent = text;
}

// ── 上載 ────────────────────────────────────────────────────────────────────
function polUpload() {
  if (polBusy) return Promise.resolve();
  const inp = polEl("polFile");
  const name = (polEl("polName").value || "").trim();
  if (!inp || !inp.files || !inp.files.length) { polSetOut("pol_err_nofile"); return Promise.resolve(); }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$/.test(name) || name.indexOf("..") >= 0) { polSetOut("pol_err_name"); return Promise.resolve(); }
  const files = Array.prototype.slice.call(inp.files);
  const zips = files.filter(function (f) { return /\.zip$/i.test(f.name); });
  const onnx = files.filter(function (f) { return /\.onnx$/i.test(f.name); });
  if (zips.length > 1 || (zips.length === 1 && files.length > 1)) { polSetOut("pol_err_mix"); return Promise.resolve(); }
  if (!zips.length && onnx.length !== 1) { polSetOut("pol_err_onnx_count"); return Promise.resolve(); }

  const q = { name: name };
  const obs = polEl("polObs").value;
  if (obs && obs !== "auto") q.obs = obs;
  const hz = polEl("polHz").value, sp = polEl("polSpeed0").value, sc = polEl("polScale").value, ax = (polEl("polAxes").value || "").trim();
  if (hz) q.control_hz = hz;
  if (sp) q.max_speed_dps = sp;
  if (sc) q.act_scale = sc;
  if (ax) q.accel_axes = ax;

  polBusy = true;
  polSetOut("pol_uploading");
  let bodyP;
  if (zips.length === 1) {
    q.kind = "zip";
    bodyP = Promise.resolve(zips[0]);
  } else {
    const others = files.filter(function (f) { return /^(ref|manifest|selftest)\.json$/.test(polBase(f.name)); });
    if (!others.length) {
      q.kind = "onnx";
      bodyP = Promise.resolve(onnx[0]);
    } else {
      q.kind = "zip";
      const wanted = [{ f: onnx[0], name: "policy.onnx" }].concat(others.map(function (f) { return { f: f, name: polBase(f.name) }; }));
      bodyP = Promise.all(wanted.map(function (w) {
        return polReadFile(w.f).then(function (u8) { return { name: w.name, data: u8 }; });
      })).then(polBuildZip);
    }
  }
  const hdrs = (typeof panelAuthHeaders === "function") ? panelAuthHeaders() : {};
  return bodyP.then(function (body) {
    return fetch("/upload/policy?" + new URLSearchParams(q).toString(), { method: "POST", body: body, headers: hdrs });
  }).then(function (res) {
    return res.json().catch(function () { return { ok: false, error: "invalid response (status " + res.status + ")" }; });
  }).then(function (json) {
    polBusy = false;
    if (!json || !json.ok) {
      polSetOutRaw(t("pol_upload_fail") + ((json && (json.error || json.message)) || "?"));
      return json;
    }
    polSetOut("pol_upload_ok");
    inp.value = "";
    polApplyStatus(json, true);
    return polRefreshList(name);
  }).catch(function (e) {
    polBusy = false;
    polSetOutRaw(t("pol_upload_fail") + (e && e.message ? e.message : e));
  });
}

// ── 名單／載入／刪除 ─────────────────────────────────────────────────────────
function polRefreshList(selectName) {
  return Alpha2Api.policyList().then(function (res) {
    const sel = polEl("polSelect");
    if (!sel || !res || !res.ok) return res;
    const keep = selectName || sel.value || (polLast && polLast.name) || "";
    sel.innerHTML = "";
    (res.policies || []).forEach(function (n) {
      const o = document.createElement("option");
      o.value = n; o.textContent = n;
      sel.appendChild(o);
    });
    if (keep) sel.value = keep;
    const dirEl = polEl("polDirOut");
    if (dirEl) dirEl.textContent = res.dir || "";
    return res;
  });
}

function polLoad() {
  const sel = polEl("polSelect");
  if (!sel || !sel.value) { polSetOut("pol_err_nosel"); return Promise.resolve(); }
  return Alpha2Api.policyLoad({ name: sel.value }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_load_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOut("pol_load_ok");
    polApplyStatus(res, true);
    return res;
  });
}

function polDelete() {
  const sel = polEl("polSelect");
  if (!sel || !sel.value) { polSetOut("pol_err_nosel"); return Promise.resolve(); }
  if (!confirm(t("pol_confirm_delete", { name: sel.value }))) return Promise.resolve();
  return Alpha2Api.policyDelete({ name: sel.value }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_delete_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOut("pol_delete_ok");
    return polRefreshAll();
  });
}

// ── 預載組：切換／卸載 ───────────────────────────────────────────────────────
function polSwitch(name) {
  return Alpha2Api.policySwitch({ name: name }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_switch_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOutRaw("");
    polApplyStatus(res, false);
    return res;
  });
}

function polUnload(name) {
  return Alpha2Api.policyUnload({ name: name }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_unload_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOutRaw("");
    polApplyStatus(res, false);
    return res;
  });
}

// 預載組列表：每個 policy 一行（名＋規格＋標籤＋切換／卸載）。名只含 [A-Za-z0-9._-]，仍用 textContent。
// 速度指令（統一格式 walk）：滑桿範圍＝該 policy 嘅 cmd_max；持續重送（runtime 過 timeout 會歸零）。
let polCmdSentAt = 0;
let polCmdFor = "";
function polCmdVals() {
  return { vx: Number(polEl("polVx").value) || 0, vy: Number(polEl("polVy").value) || 0, wz: Number(polEl("polWz").value) || 0 };
}
function polCmdShow() {
  const v = polCmdVals();
  polEl("polVxV").textContent = v.vx.toFixed(3);
  polEl("polVyV").textContent = v.vy.toFixed(3);
  polEl("polWzV").textContent = v.wz.toFixed(2);
}
function polSendCmd() {
  polCmdSentAt = Date.now();
  return Alpha2Api.policyCommand(polCmdVals()).then(function (res) {
    if (res && res.ok) polApplyStatus(res, false);
    else polSetOutRaw(t("pol_cmd_fail") + ((res && (res.error || res.message)) || "?"));
    return res;
  });
}
function polCmdInput() { polCmdShow(); return polSendCmd(); }
function polCmdStop() {
  ["polVx", "polVy", "polWz"].forEach(function (id) { polEl(id).value = 0; });
  polCmdShow();
  return polSendCmd();
}
function polRenderCmd(res) {
  const box = polEl("polCmdBox");
  if (!box) return;
  const act = (res && res.running) ? (res.bank || []).filter(function (b) { return b.active && b.cmdMax && b.task === "walk"; })[0] : null;
  box.style.display = act ? "" : "none";
  if (!act) { polCmdFor = ""; return; }
  if (polCmdFor !== act.name) {                       // 換咗 policy：重設範圍同歸零
    polCmdFor = act.name;
    [["polVx", act.cmdMax[0]], ["polVy", act.cmdMax[1]], ["polWz", act.cmdMax[2]]].forEach(function (p) {
      const el = polEl(p[0]); el.min = -p[1]; el.max = p[1]; el.value = 0;
    });
    polCmdShow();
  }
  const v = polCmdVals();
  if ((v.vx || v.vy || v.wz) && Date.now() - polCmdSentAt > 600) polSendCmd();
}

// 角色快速切換：stand／walk／walk_ref／getup_back／getup_front → 而家對應嘅 policy（status.roles）
function polSwitchRole(role) {
  return Alpha2Api.policySwitch({ role: role }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_switch_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOutRaw("");
    polApplyStatus(res, false);
    return res;
  });
}

function polRenderRoles(res) {
  const box = polEl("polRoles");
  if (!box) return;
  box.textContent = "";
  const roles = (res && res.roles) || {};
  const lab = document.createElement("span");
  lab.textContent = t("pol_roles_label");
  box.appendChild(lab);
  ["stand", "walk", "walk_ref", "getup_back", "getup_front"].forEach(function (role) {
    const b = document.createElement("button");
    b.className = "secondary";
    const name = roles[role] || "";
    b.textContent = role;
    b.title = name || t("pol_role_none");
    const inRun = (res.bank || []).some(function (x) { return x.name === name && (!res.running || x.inRun); });
    b.disabled = !name || !inRun || !!(res.running && res.active === name);
    b.onclick = function () { polSwitchRole(role); };
    box.appendChild(b);
  });
}

function polRenderBank(res) {
  polInitPeriodPresets();
  polRenderRoles(res);
  polRenderCmd(res);
  const box = polEl("polBank");
  if (!box) return;
  box.textContent = "";
  const bank = (res && res.bank) || [];
  if (!bank.length) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("pol_bank_empty", { max: (res && res.bankMax) || 8 });
    box.appendChild(p);
    return;
  }
  bank.forEach(function (b) {
    const row = document.createElement("div");
    row.className = "row";
    row.style.alignItems = "center";
    const label = document.createElement("span");
    label.style.flex = "1";
    const tags = [];
    if (b.active) tags.push("▶ " + t("pol_tag_active"));
    else if (b.selected) tags.push("● " + t("pol_tag_selected"));
    if (res.running && res.pendingSwitch === b.name) tags.push("⏳ " + t("pol_tag_pending"));
    if (res.running && !b.inRun) tags.push("⛔ " + t("pol_tag_not_in_run"));
    if (b.needsFast) tags.push("⚡");
    label.textContent = b.name + " · " + (b.task && b.task !== b.obs ? b.task : b.obs) + " · " + b.controlHz + " Hz" + (tags.length ? "  " + tags.join(" ") : "");
    row.appendChild(label);
    const sw = document.createElement("button");
    sw.className = "secondary";
    sw.textContent = res.running ? t("pol_switch_btn") : t("pol_select_btn");
    sw.disabled = !!(res.running ? (b.active || !b.inRun) : b.selected);
    sw.onclick = function () { polSwitch(b.name); };
    row.appendChild(sw);
    const un = document.createElement("button");
    un.className = "danger-btn";
    un.textContent = t("pol_unload_btn");
    un.disabled = !!(res.running && (b.active || res.pendingSwitch === b.name));
    un.onclick = function () { polUnload(b.name); };
    row.appendChild(un);
    box.appendChild(row);
  });
}

// ── 運行 ────────────────────────────────────────────────────────────────────
function polStart() {
  const dry = !!polEl("polDry").checked;
  const fast = !!polEl("polFast").checked;
  const p = { dry: dry ? "true" : "false", fast: fast ? "true" : "false" };
  const sp = polEl("polSpeed").value;
  if (sp) p.max_speed_dps = sp;
  const tl = polEl("polTilt").value;
  if (tl) p.tilt_limit_deg = tl;
  const pr = polEl("polPeriod").value;
  if (pr) p.period_ms = pr;
  if (polEl("polAutoRecover").checked) p.auto_recover = "true";
  if (!dry && !confirm(t("pol_confirm_real"))) return Promise.resolve();
  return Alpha2Api.policyStart(p).then(function (res) {
    if (!res || !res.ok) {
      polSetOutRaw(t("pol_start_fail") + ((res && (res.error || res.message)) || "?"));
      return res;
    }
    polSetOutRaw("");
    polStartAt = Date.now();
    polApplyStatus(res, false);
    polEnsurePolling(true);            // 唔靠回應嘅 running 欄位（可能追唔上），一定開輪詢＋心跳
    return res;
  });
}

// 決策週期：運行中即時改（下一格生效）；未運行只記低（polStart 會帶 period_ms）。
function polApplyPeriod(v) {
  const inp = polEl("polPeriod");
  if (v != null) inp.value = v;
  const ms = inp.value === "" ? 0 : Number(inp.value);
  if (!(ms === 0 || (ms >= 50 && ms <= 500))) { polSetOutRaw(t("pol_period_fail") + "50–500"); return Promise.resolve(); }
  if (!polLast || !polLast.running) { polSetOutRaw(t("pol_period_next")); return Promise.resolve(); }
  return Alpha2Api.policyPeriod({ ms: ms }).then(function (res) {
    if (!res || !res.ok) { polSetOutRaw(t("pol_period_fail") + ((res && (res.error || res.message)) || "?")); return res; }
    polSetOutRaw("");
    polApplyStatus(res, false);
    return res;
  });
}

function polInitPeriodPresets() {
  const box = polEl("polPeriodPresets");
  if (!box || box.firstChild) return;
  [50, 100, 150, 200, 300, 400, 500].forEach(function (v) {
    const b = document.createElement("button");
    b.className = "secondary";
    b.textContent = String(v);
    b.style.minWidth = "3em";
    b.onclick = function () { polApplyPeriod(v); };
    box.appendChild(b);
  });
}

function polStop() {
  polStartAt = 0;
  return Alpha2Api.policyStop().then(function (res) {
    polApplyStatus(res, false);
    return res;
  });
}

// 運行中每 500ms 叫一次 policy/start＝續租約（後端：已經跑緊就只續租約並回狀態，唔會重開）。
// 淨係叫 policy/status 唔會續租約，租約（預設 5 秒）一到 policy 就以 lease 停低——
// 呢個係「行咗半步就停」嘅成因。分頁入咗背景／瀏覽器關閉／斷網 ⇒ 冇心跳 ⇒ 自動停（保護機制，故意）。
function polRecentlyStarted() { return Date.now() - polStartAt < 3000; }

function polPollOnce() {
  // 運行中（或剛按開始、狀態未追上）：policy/beat＝續租約＋回狀態；唔會啟動，冇運行就等同 status。
  const beat = (polLast && polLast.running) || polRecentlyStarted();
  const p = beat ? Alpha2Api.policyBeat() : Alpha2Api.policyStatus();
  return p.then(function (res) {
    polApplyStatus(res, false);
    return res;
  });
}

function polEnsurePolling(running) {
  running = running || polRecentlyStarted();
  if (running && !polTimer) polTimer = setInterval(polPollOnce, 500);
  if (!running && polTimer) { clearInterval(polTimer); polTimer = null; }
}

// ── 顯示 ────────────────────────────────────────────────────────────────────
function polApplyStatus(res, justLoaded) {
  if (!res || !res.ok) return;
  polLast = res;
  if (justLoaded && res.loaded) {
    // 載入／上載後：dry 預設開；fast 按 policy 需要預設。
    polEl("polDry").checked = true;
    polEl("polFast").checked = !!res.needsFast;
  }
  polRender();
  polEnsurePolling(!!res.running);
}

function polEndReasonText(r) {
  if (!r) return "";
  const key = "pol_end_" + String(r).replace(/:.*$/, "");
  const txt = t(key);
  return txt === key ? r : txt;
}

function polRender() {
  const res = polLast;
  const info = polEl("polInfo"), live = polEl("polLive");
  const startBtn = polEl("polStartBtn");
  if (!info || !live) return;
  polRenderBank(res);
  if (!res || !res.loaded) {
    info.textContent = t("pol_none_loaded");
    live.textContent = "";
    if (startBtn) startBtn.disabled = true;
    return;
  }
  const lines = [];
  lines.push(res.name + " · " + res.obs + " (" + res.obsDim + ") · " + res.controlHz + " Hz (" + res.periodMs + " ms)");
  lines.push((res.hashVerified ? "✅ " : "⚠️ ") + t("pol_hash") + " · " +
    (res.selftestPresent ? (res.selftestOk ? "✅ " : "❌ ") + t("pol_selftest") + " " + res.selftest : "⚠️ " + t("pol_no_selftest")));
  if (!res.realRunAllowed) lines.push("⛔ " + t("pol_real_blocked") + res.realRunBlock);
  else if (res.needsFast) lines.push("⚡ " + t("pol_needs_fast"));
  else lines.push("✅ " + t("pol_real_ok"));
  if (!res.accelAvailable) lines.push("⚠️ " + t("pol_no_accel"));
  info.textContent = lines.join("\n");
  if (startBtn) startBtn.disabled = !!res.running;

  const l2 = [];
  l2.push((res.running ? "▶ " + t("pol_running") : "■ " + t("pol_idle")) + (res.running ? (res.dry ? " (dry)" : "") + (res.fast ? " [fast]" : "") : ""));
  if (typeof res.steps === "number") {
    l2.push(t("pol_steps") + " " + res.steps + " · " + t("pol_tilt") + " " + res.tiltDeg + "° · " + t("pol_period") + " " + res.periodMsEff + " ms" +
      (res.active ? " · ▶ " + res.active : "") + (res.periodOverrideMs ? " · ⏱ " + res.periodOverrideMs + " ms" : "") +
      (res.autoRecover ? " · 🛟 " + t("pol_recoveries") + " " + res.recoveries : "") + (res.switches ? " · " + t("pol_switch_count") + " " + res.switches : ""));
    l2.push(t("pol_infer") + " " + res.inferUs + " µs · " + t("pol_maxstep") + " " + res.maxStepUs + " µs · " + t("pol_maxsend") + " " + res.maxSendUs + " µs · " + t("pol_overruns") + " " + res.overruns);
  }
  if (res.running && res.fallen) l2.push(t("pol_fallen"));
  if (!res.running && res.endReason) l2.push(t("pol_end") + " " + polEndReasonText(res.endReason));
  if (res.error) l2.push("⚠️ " + res.error);
  live.textContent = l2.join("\n");
  polRenderTrace(res);
}

// 停低後顯示最近 12 步診斷（欄位：step tilt° g(xyz) 加速度(xyz) 最大|action| 最大servo變化° 單格ms）。
function polRenderTrace(res) {
  const pre = polEl("polTrace"), row = polEl("polDiagRow");
  if (!pre || !row) return;
  if (res.running || !res.trace || !res.trace.length) {
    pre.style.display = "none"; row.style.display = "none";
    return;
  }
  const f = function (v, d) { return v === null || v === undefined ? "  -  " : Number(v).toFixed(d); };
  const lines = [res.traceCols.join("\t")];
  res.trace.slice(-12).forEach(function (r) {
    lines.push([r[0], f(r[1], 1), f(r[2], 2), f(r[3], 2), f(r[4], 2), f(r[5], 1), f(r[6], 1), f(r[7], 1), f(r[8], 2), f(r[9], 0), f(r[10], 1)].join("\t"));
  });
  pre.textContent = lines.join("\n");
  pre.style.display = "block";
  row.style.display = "flex";
}

// 複製診斷（http 區網冇 navigator.clipboard，用 textarea＋execCommand；失敗就留低 textarea 俾人手動複製）。
function polCopyDiag() {
  const ta = polEl("polDiag");
  if (!ta || !polLast) return;
  ta.value = JSON.stringify(polLast);
  ta.style.display = "block";
  ta.focus(); ta.select();
  let ok = false;
  try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
  polSetOutRaw(ok ? t("pol_copied") : t("pol_copy_manual"));
}

function polApplyUiLanguage() {
  if (polLast) polRender();
}

function polRefreshAll() {
  return polRefreshList().then(function () { return polPollOnce(); });
}

// 卡片總開關（同其他實驗卡一致：預設收起，每次入頁預設關閉）。
function polCardToggle() {
  const enabled = polEl("polCardEnabled");
  const body = polEl("polCardBody");
  const hint = polEl("polCardDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
  if (on) polRefreshAll();
  else polEnsurePolling(false);
}
