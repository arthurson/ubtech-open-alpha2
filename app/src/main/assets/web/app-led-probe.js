// Open Alpha2 — client logic (app-led-probe.js)
// 實驗 Tab: LED 索引掃描 0-20 單選即亮卡（實測 17-100 無反應，縮範圍）
// 由 wifi 12 / 13、v-14/v+16 暴力掃描驗證衍生：一鍵出 00-20、每次只亮一粒（先 OFF 再 ON）

let ledProbeSelectedIndex = null;

function ledProbeFmt(i) { return i < 10 ? '0' + i : String(i); }

function ledProbeToggle() {
  const cb = document.getElementById('ledProbeEnabled');
  const enabled = cb && cb.checked;
  const hint = document.getElementById('ledProbeDisabledHint');
  const body = document.getElementById('ledProbeBody');
  if (hint) hint.style.display = enabled ? 'none' : 'block';
  if (body) body.style.display = enabled ? 'block' : 'none';
  if (enabled) ledProbeBuildGrid();
  else ledProbeOff();
}

function ledProbeBuildGrid() {
  const grid = document.getElementById('ledProbeGrid');
  if (!grid) return;
  grid.innerHTML = '';
  for (let i = 0; i <= 20; i++) {
    const btn = document.createElement('button');
    btn.type = 'button';
    const label = ledProbeFmt(i);
    btn.textContent = label;
    btn.dataset.idx = String(i);
    btn.className = 'led-probe-btn secondary';
    // 已知映射加強標記（實測：01眨眼 03彩虹 04閂彩虹 05-11長亮各色 12/13 WiFi 14 V- 16 V+，17-20 無反應）
    let known = '';
    if (i === 1) known = '眨眼';
    else if (i === 3) known = '彩虹';
    else if (i === 4) known = '閂彩虹';
    else if (i >= 5 && i <= 11) known = ['紅','綠','藍','黃','紫','青','白'][i - 5];
    else if (i === 12) known = 'WiFi藍';
    else if (i === 13) known = 'WiFi紅';
    else if (i === 14) known = 'V-';
    else if (i === 16) known = 'V+';
    if (known) {
      // 閂彩虹/眨眼等保留原色標記，長亮各色用對應縮寫
      const isColor = (i >= 5 && i <= 11);
      btn.title = known + (isColor ? '長亮' : '') + ' (已驗證, ledSetOn(' + i + '))';
      btn.dataset.known = known;
      btn.innerHTML = label + '<small style="display:block;font-size:9px;line-height:1;color:var(--accent)">' + known + '</small>';
    } else {
      btn.title = 'ledSetOn(' + i + ')';
      if (i >= 17) btn.title += ' - 實測多數無反應';
    }
    btn.onclick = function () { ledProbeSelect(i); };
    grid.appendChild(btn);
  }
  ledProbeUpdateHighlight();
}

function ledProbeUpdateHighlight() {
  const grid = document.getElementById('ledProbeGrid');
  if (!grid) return;
  const hint = document.getElementById('ledProbeSelected');
  const countEl = document.getElementById('ledProbeCount');
  grid.querySelectorAll('.led-probe-btn').forEach(function (b) {
    const idx = parseInt(b.dataset.idx, 10);
    const isSel = idx === ledProbeSelectedIndex;
    const isKnown = b.dataset.known;
    // reset classes
    b.classList.toggle('selected', isSel);
    if (isSel) {
      b.style.background = 'var(--accent)';
      b.style.color = '#fff';
      b.style.borderColor = 'var(--accent)';
      b.style.fontWeight = '700';
    } else {
      b.style.background = isKnown ? '#eef2ff' : '';
      b.style.color = isKnown ? 'var(--accent)' : '';
      b.style.borderColor = isKnown ? 'var(--accent)' : '';
      b.style.fontWeight = isKnown ? '700' : '';
    }
  });
  if (hint) {
    if (ledProbeSelectedIndex === null) {
      hint.textContent = '';
    } else {
      const lbl = ledProbeFmt(ledProbeSelectedIndex);
      const knownMap = {1: '眨眼 ●', 3: '彩虹 ●', 4: '閂彩虹 ●', 5: '紅長亮 ●', 6: '綠長亮 ●', 7: '藍長亮 ●', 8: '黃長亮 ●', 9: '紫長亮 ●', 10: '青長亮 ●', 11: '白長亮 ●', 12: 'WiFi藍 ●', 13: 'WiFi紅 ●', 14: 'V- ●', 16: 'V+ ●'};
      const k = knownMap[ledProbeSelectedIndex];
      hint.textContent = k ? '已選：' + lbl + '（' + k + '）' : '已選：' + lbl;
    }
  }
  if (countEl) {
    countEl.textContent = ledProbeSelectedIndex === null
      ? '未選中'
      : '當前：' + ledProbeFmt(ledProbeSelectedIndex);
  }
}

function ledProbeSetStatus(text, isError) {
  const el = document.getElementById('ledProbeStatus');
  if (!el) return;
  el.textContent = text;
  el.style.color = isError ? 'var(--err)' : 'var(--muted)';
}

function ledProbeOff() {
  ledProbeSelectedIndex = null;
  ledProbeUpdateHighlight();
  ledProbeSetStatus('熄燈中…', false);
  Alpha2Api.debugJniLed({ func: 'off' }).then(function (json) {
    // 後端恒回 {open,raw}，raw=false 即 native 0 成功（見 DirectLedController）
    const ok = json && json.open === true && json.raw === false;
    ledProbeSetStatus(
      (ok ? '✅ 已熄滅（open=' + json.open + ' raw=' + json.raw + '）' : 'ℹ️ 已送 OFF（open=' + json.open + ' raw=' + json.raw + '）') +
      ' — 再撳任意數字即亮該粒',
      false
    );
    return json;
  }).catch(function (e) {
    ledProbeSetStatus('❌ 熄燈失敗：' + e, true);
  });
}

function ledProbeSelect(i) {
  ledProbeSelectedIndex = i;
  ledProbeUpdateHighlight();
  const label = ledProbeFmt(i);
  ledProbeSetStatus('點亮 ' + label + '…（先 OFF 再 ON，單粒模式）', false);
  // 單粒保證：先 OFF 清場（會連 wifi 12/13 一起清，屬正常），再 ON 目標
  Alpha2Api.debugJniLed({ func: 'off' }).then(function () {
    return Alpha2Api.debugJniLed({ func: 'on', i: i });
  }).then(function (json) {
    const isKnown = (i >= 1 && i <= 14) || i === 16;
    // 17-20 已知多數無反應，仍顯示提示
    const rawOk = json && json.raw === false;
    const openOk = json && json.open === true;
    let msg = '已送 ' + label + ' → open=' + json.open + ' raw=' + json.raw;
    if (openOk && rawOk) msg += ' ✅（驅動回 0，硬件已收）';
    else if (!openOk) msg += ' ⚠️ open 失敗（/dev/led_eye 忙？重試一次）';
    else msg += ' ℹ️（留意實體燈，raw 反了：false=成功）';
    if (i === 3) msg += ' — 🌈 彩虹眼已開（需按 04 閂咗先試其他）';
    else if (i === 4) msg += ' — 已閂彩虹，可再試其他';
    else if (i === 1) msg += ' — 眨眼';
    else if (i >= 5 && i <= 11) {
      const col = ['紅','綠','藍','黃','紫','青','白'][i - 5];
      msg += ' — 長亮' + col + '眼';
    } else if (isKnown) {
      const name = i === 12 ? 'WiFi藍' : i === 13 ? 'WiFi紅' : i === 14 ? 'V- 燈' : i === 16 ? 'V+ 燈' : '';
      if (name) msg += ' — ' + name + ' 應已亮';
    } else if (i >= 17) {
      msg += ' — 實測 17-20 多數無反應（預留）';
    } else {
      msg += ' — 若無反應即該索引無接線/無燈';
    }
    ledProbeSetStatus(msg, false);
    return json;
  }).catch(function (e) {
    ledProbeSetStatus('❌ 點亮 ' + label + ' 失敗：' + e, true);
  });
}

// DOM 就緒即建 grid（app-log.js 的 DOMContentLoaded 之後也會再調一次兜底）
document.addEventListener('DOMContentLoaded', function () {
  // 延一幀等 tab-advanced 節點已在 DOM
  setTimeout(ledProbeBuildGrid, 0);
});
// 若已經在 loading 完成後才載入（例如熱重載），立即建
if (document.readyState !== 'loading') {
  setTimeout(ledProbeBuildGrid, 0);
}
