// Open Alpha2 — Blockly 頁面初始化。
// 提供同 app.js 完全一致嘅 api()/連 WebSocket 邏輯 (獨立一份, 等呢版頁面可以自己
// 開一個分頁使用, 唔一定要靠 app.js 已經執行緊), 再初始化 Blockly workspace、
// 綁定工具列按鈕、接駁 blockly-run.js 嘅事件系統。

const API = '/api/';

function showError(context, err) {
  const banner = document.getElementById('errorBanner');
  const msg = (err && err.message) ? err.message : String(err);
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

// 統一嘅 api() helper — 同 app.js 個版本行為一致 (GET + query string, 回傳 parsed JSON)。
window.api = function (path, params) {
  clearError();
  const qs = params ? '?' + new URLSearchParams(params).toString() : '';
  return fetch(API + path + qs).then(function (res) {
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
  document.getElementById('wsStatusText').textContent = 'WebSocket: ' + (connected ? '已連接' : '未連接');
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  try {
    ws = new WebSocket(proto + '//' + location.host + '/ws');
  } catch (e) {
    showError('WebSocket 連線失敗', e);
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

function initWorkspace() {
  workspace = Blockly.inject('blocklyDiv', {
    toolbox: window.ALPHA_TOOLBOX,
    grid: { spacing: 24, length: 2, colour: '#e2e6ec', snap: true },
    zoom: { controls: true, wheel: true, startScale: 0.9, maxScale: 3, minScale: 0.3, scaleSpeed: 1.1 },
    trashcan: true,
    move: { scrollbars: true, drag: true, wheel: false },
    theme: buildAlphaTheme(),
  });
  window.AlphaBlockly.init(workspace);

  // 視窗 resize 時重新計算 Blockly 畫布大小。
  function resizeBlockly() { Blockly.svgResize(workspace); }
  window.addEventListener('resize', resizeBlockly);
  resizeBlockly();
}

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
    showError('儲存程式', new Error('請先輸入程式名稱'));
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
  if (confirm('確定要刪除「' + sel.value + '」？')) {
    AlphaBlockly.deleteNamed(sel.value);
  }
}
function onImportFile(input) {
  const file = input.files && input.files[0];
  if (file) AlphaBlockly.importXmlFile(file);
  input.value = '';
}
function onClearWorkspace() {
  if (confirm('確定要清空成個畫布？呢個動作唔可以復原 (但係自動儲存已存低嘅版本仍然可以用「載入」攞返)。')) {
    AlphaBlockly.clearWorkspace();
  }
}

// ---------------- 啟動 ----------------
document.addEventListener('DOMContentLoaded', function () {
  initWorkspace();
  connectWs();
});
