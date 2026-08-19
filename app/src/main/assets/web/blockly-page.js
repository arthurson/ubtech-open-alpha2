// Open Alpha2 — Blockly 頁面初始化。
// 提供同 index.html (app-core.js) 完全一致嘅 api()/連 WebSocket 邏輯 (獨立一份, 等呢版
// 頁面可以自己開一個分頁使用, 唔一定要靠 index.html 嗰邊已經執行緊), 再初始化 Blockly
// workspace、綁定工具列按鈕、接駁 blockly-run.js 嘅事件系統。

const API = '/api/';

function showError(context, err) {
  const banner = document.getElementById('errorBanner');
  let msg = (err && err.message) ? err.message : String(err);
  banner.textContent = '⚠ ' + context + ': ' + msg;
  banner.style.display = 'block';
  console.error(context, err);
}
function clearError() {
  const banner = document.getElementById('errorBanner');
  banner.style.display = 'none';
  banner.textContent = '';
}
window.addEventListener('error', function (e) { showError('JavaScript error', e.error || e.message); });
window.addEventListener('unhandledrejection', function (e) { showError('Unhandled promise rejection', e.reason); });

// 統一嘅 api() helper — 同 app-core.js 個版本行為一致 (GET + query string, 回傳 parsed JSON)。
window.api = function (path, params) {
  clearError();
  const qs = params ? '?' + new URLSearchParams(params).toString() : '';
  return fetch(API + 'alpha2/' + path + qs).then(function (res) {
    return res.json().catch(function () {
      return { ok: false, error: 'invalid response (status ' + res.status + ')' };
    }).then(function (json) {
      if (!json.ok) {
        showError('API /' + path, new Error(json.error || json.code || 'request failed'));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError('Network error calling /' + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
};

// ---------------- WebSocket：接駁事件驅動 blocks ----------------
let ws = null;
let wsReconnectTimer = null;

function setWsStatus(connected) {
  document.getElementById('wsStatusDot').className = 'run-status-dot ' + (connected ? 'running' : 'idle');
  document.getElementById('wsStatusText').textContent = 'WebSocket: ' + (connected ? t('page_ws_connected') : t('page_ws_disconnected_word'));
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  try {
    ws = new WebSocket(proto + '//' + location.host + '/ws');
  } catch (e) {
    showError(t('page_ws_connect_failed'), e);
    scheduleReconnect();
    return;
  }
  ws.onopen = function () { setWsStatus(true); };
  ws.onclose = function () { setWsStatus(false); scheduleReconnect(); };
  ws.onerror = function () { setWsStatus(false); };
  ws.onmessage = function (evt) {
    let parsed;
    try {
      parsed = JSON.parse(evt.data);
    } catch (e) {
      return;
    }
    if (parsed && parsed.type && parsed.type !== 'connected' && window.AlphaBlockly) {
      window.AlphaBlockly.onWsEvent(parsed);
    }
  };
}
function scheduleReconnect() {
  clearTimeout(wsReconnectTimer);
  wsReconnectTimer = setTimeout(connectWs, 2000);
}

// ---------------- Blockly workspace 初始化 ----------------
let workspace = null;

// 喺頁面頂部 header 顯示實際載入緊嘅 Blockly 版本 —— 直接讀 Blockly.VERSION
// (Blockly library 內建常數, 喺 blockly_compressed.js 入面已經寫死), 唔係手動
// 打一個數字落 HTML, 咁樣升級/替換 blockly_compressed.js 之後個顯示會自動跟
// 返實際檔案版本, 唔會走漏眼漏更新。
function showBlocklyVersionBadge() {
  const badge = document.getElementById('blocklyVersionBadge');
  if (!badge) return;
  const ver = (typeof Blockly !== 'undefined' && Blockly.VERSION) ? Blockly.VERSION : t('page_blockly_version_unknown');
  badge.textContent = 'Blockly v' + ver;
}

function initWorkspace() {
  showBlocklyVersionBadge();
  workspace = Blockly.inject('blocklyDiv', {
    toolbox: window.ALPHA_TOOLBOX,
    // 呢個 Blockly 版本嘅預設 pathToMedia 係 "https://static.blockly.com/media/"
    // (外部 CDN) —— 喺呢個 app 嘅 WebView 環境入面攞唔到, 令
    // 還原/放大/縮細/垃圾桶 (undo/redo/zoom-in/zoom-out/zoom-reset/trashcan)
    // 嗰批 icon 全部壞曬 (SVG sprite 攞唔到)。改用本機 media/ 資料夾 (已經
    // copy 咗 Blockly 官方 npm package 嘅 media 檔案落嚟), 全部 offline 可用。
    media: 'media/',
    grid: { spacing: 24, length: 2, colour: '#e2e6ec', snap: true },
    zoom: { controls: true, wheel: true, startScale: 0.9, maxScale: 3, minScale: 0.3, scaleSpeed: 1.1 },
    trashcan: true,
    move: { scrollbars: true, drag: true, wheel: false },
    theme: buildAlphaTheme(),
    sounds: false,
  });
  window.__alphaBlocklyWorkspace = workspace; // 俾 blockly-i18n.js 切語言嗰陣攞返嚟用
  // AlphaBlockly.init() 入面而家連埋起返「復原/剪貼掣列」同「側欄收埋掣」呢兩組
  // Blockly IPositionable component (詳見 blockly-run.js 嘅 EditFabControls/
  // SidePanelToggleControl 大段註解) —— 佢哋同垃圾桶/zoom controls 用返完全
  // 同一套 Blockly 官方定位管線, 一定要喺 workspace inject 咗之後先可以起。
  window.AlphaBlockly.init(workspace);

  // 視窗 resize 時重新計算 Blockly 畫布大小。
  window.addEventListener('resize', resizeBlockly);
  resizeBlockly();

  // 抄自 Code Lab (見對話紀錄嘅截圖): 側欄 (執行紀錄面板) 收埋/展開狀態,
  // 記喺 localStorage, 等用家下次開返呢個分頁都記得住上次揀嘅收/開。用
  // setSidePanelCollapsedInitial() 唔係 toggleSidePanel(), 因為呢個係
  // 「頁面啱啱 load 就要已經係咁」, 唔應該播 0.18s 嘅收埋動畫。
  try {
    if (localStorage.getItem('blocklySideCollapsed') === '1') {
      window.AlphaBlockly.setSidePanelCollapsedInitial(true);
      resizeBlockly();
    }
  } catch (e) { /* localStorage 喺部分 WebView 環境可能唔可用, 冇記錄就預設展開, 唔緊要 */ }
}

// 視窗 resize / 側欄收/展開之後都要重新計算 Blockly 畫布大小 —— Blockly.svgResize()
// 一 call, 內部會自動連埋 ComponentManager 嗰批 POSITIONABLE component (垃圾桶/
// zoom controls/我哋自己嘅 EditFabControls/SidePanelToggleControl) 一齊重新
// 定位, 唔使各自另外再郁佢哋。拆做獨立 function 等 window resize listener 同
// AlphaBlockly.toggleSidePanel() 可以共用。
function resizeBlockly() {
  if (workspace) Blockly.svgResize(workspace);
}

function toggleSidePanel() {
  if (window.AlphaBlockly && window.AlphaBlockly.toggleSidePanel) {
    window.AlphaBlockly.toggleSidePanel();
  }
}
window.toggleSidePanel = toggleSidePanel;

function buildAlphaTheme() {
  // 用返 style.css 個淡藍/白色系 (--bg #f5f7fa / --accent #3b7dff),
  // 等成個頁面 (工具列/積木畫布) 睇落係同一個產品,唔係外掛一份第三方風格。
  try {
    return Blockly.Theme.defineTheme('alphaTheme', {
      base: Blockly.Themes.Classic,
      componentStyles: {
        workspaceBackgroundColour: '#f5f7fa',
        toolboxBackgroundColour: '#ffffff',
        toolboxForegroundColour: '#1c2126',
        flyoutBackgroundColour: '#eef1f6',
        flyoutForegroundColour: '#1c2126',
        flyoutOpacity: 1,
        scrollbarColour: '#c6ccd6',
        insertionMarkerColour: '#3b7dff',
        insertionMarkerOpacity: 0.4,
        markerColour: '#3b7dff',
        cursorColour: '#3b7dff',
      },
    });
  } catch (e) {
    console.warn('theme build failed, fallback to classic', e);
    return Blockly.Themes.Classic;
  }
}

// ---------------- 工具列按鈕 wiring ----------------
function doSaveNamed() {
  const input = document.getElementById('saveNameInput');
  const name = input.value.trim();
  if (!name) {
    showError(t('page_save_program_ctx'), new Error(t('page_save_program_need_name')));
    return;
  }
  AlphaBlockly.saveNamed(name);
}
function doLoadNamed() {
  const sel = document.getElementById('savedProgramSelect');
  if (!sel.value) return;
  AlphaBlockly.loadNamed(sel.value);
  document.getElementById('saveNameInput').value = sel.value;
}
function doDeleteNamed() {
  const sel = document.getElementById('savedProgramSelect');
  if (!sel.value) return;
  if (confirm(t('page_confirm_delete', { name: sel.value }))) {
    AlphaBlockly.deleteNamed(sel.value);
  }
}
function onImportFile(input) {
  const file = input.files && input.files[0];
  if (file) AlphaBlockly.importXmlFile(file);
  input.value = '';
}
function onClearWorkspace() {
  if (confirm(t('page_confirm_clear_workspace'))) {
    AlphaBlockly.clearWorkspace();
  }
}

// ---------------- 啟動 ----------------
document.addEventListener('DOMContentLoaded', function () {
  initWorkspace();
  connectWs();
});
