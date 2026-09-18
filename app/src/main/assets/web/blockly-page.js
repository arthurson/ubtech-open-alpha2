// Open Alpha2 — Blockly 頁面初始化。
// 提供同 index.html (app-core.js) 完全一致的 api()/連 WebSocket 邏輯 (獨立一份, 等這版
// 頁面可以自己開一個分頁使用, 不一定要靠 index.html 那邊已經正在執行), 再初始化 Blockly
// workspace、綁定工具列按鈕、接駁 blockly-run.js 的事件系統。

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

// 面板 token（見 PanelAuth.java＋app-core.js）：啟用後整個面板上鎖，Blockly
// 頁都要帶 token。key 同 index.html 共用（localStorage "panel_token"，同一個
// browser＋同一個面板地址跨 tab 共用），在實驗 tab 解鎖一次，這頁即用到。
function panelTokenGet() {
  try { return localStorage.getItem('panel_token') || ''; } catch (e) { return ''; }
}
function withPanelToken(params) {
  const tok = panelTokenGet();
  if (!tok) return params;
  const out = {};
  if (params) { for (const k in params) { out[k] = params[k]; } }
  if (out.panel_token == null) out.panel_token = tok;
  return out;
}

// 統一的 api() helper — 同 app-core.js 個版本行為一致 (GET + query string, 回傳 parsed JSON)。
window.api = function (path, params) {
  clearError();
  const merged = withPanelToken(params);
  const qs = merged ? '?' + new URLSearchParams(merged).toString() : '';
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

// 命名空間 helper — 對應 MainActivity 三個獨立路由, 同 app-core.js /
// app-xiaozhi.js 那套一樣形狀。api-client.js (Alpha2Api.*) 會自動選中的
// 一個: sysApi() -> /api/system/*, directApi() -> /api/direct/*,
// xiaozhiApi() -> /api/xiaozhi/*。hwApi 是 api() 的 alias (給 api-client.js
// 的 legacy hw() 用, 同 index.html 一致)。
function namespacedApi(prefix, label) {
  return function (path, params) {
    clearError();
    const merged = withPanelToken(params);
    const qs = merged ? '?' + new URLSearchParams(merged).toString() : '';
    return fetch(API + prefix + path + qs).then(function (res) {
      return res.json().catch(function () {
        return { ok: false, error: 'invalid response (status ' + res.status + ')' };
      }).then(function (json) {
        if (!json.ok) {
          showError('API /' + label + path, new Error(json.error || json.code || 'request failed'));
        }
        return json;
      });
    }).catch(function (networkErr) {
      showError('Network error calling /' + label + path, networkErr);
      return { ok: false, error: String(networkErr) };
    });
  };
}
window.sysApi = namespacedApi('system/', 'system/');
window.directApi = namespacedApi('direct/', 'direct/');
window.xiaozhiApi = namespacedApi('xiaozhi/', 'xiaozhi/');
window.hwApi = window.api;

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
  // 2026-09-09：error 即 close，行統一 onclose→重連（之前僅 set 燈，
  // error 後無 close 事件就永不重連；同 app-log.js 看齊）。
  ws.onerror = function () { setWsStatus(false); try { ws.close(); } catch (e) {} };
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

// 在頁面頂部 header 顯示實際正在載入的 Blockly 版本 —— 直接讀 Blockly.VERSION
// (Blockly library 內建常數, 在 blockly_compressed.js 裡面已經寫死), 不是手動
// 打一個數字落 HTML, 這樣升級/替換 blockly_compressed.js 之後個顯示會自動跟
// 返實際檔案版本, 不會疏漏漏更新。
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
    // 這個 Blockly 版本的預設 pathToMedia 是 "https://static.blockly.com/media/"
    // (外部 CDN) —— 在這個 app 的 WebView 環境裡面拿不到, 令
    // 還原/放大/縮細/垃圾桶 (undo/redo/zoom-in/zoom-out/zoom-reset/trashcan)
    // 那批 icon 全部壞掉 (SVG sprite 拿不到)。改用本機 media/ 資料夾 (已經
    // copy 了 Blockly 官方 npm package 的 media 檔案下來), 全部 offline 可用。
    media: 'media/',
    grid: { spacing: 24, length: 2, colour: '#c3cad6', snap: true },
    zoom: { controls: true, wheel: true, startScale: 0.9, maxScale: 3, minScale: 0.3, scaleSpeed: 1.1 },
    trashcan: true,
    move: { scrollbars: true, drag: true, wheel: false },
    theme: buildAlphaTheme(),
    sounds: false,
  });
  window.__alphaBlocklyWorkspace = workspace; // 給 blockly-i18n.js 切語言當時取回來用
  // AlphaBlockly.init() 裡面現在一併起返「復原/剪貼按鈕列」同「側欄收起按鈕」這兩組
  // Blockly IPositionable component (詳見 blockly-run.js 的 EditFabControls/
  // SidePanelToggleControl 大段註解) —— 它們同垃圾桶/zoom controls 用回完全
  // 同一套 Blockly 官方定位管線, 一定要在 workspace inject 了之後先可以起。
  window.AlphaBlockly.init(workspace);

  // 視窗 resize 時重新計算 Blockly 畫布大小。
  window.addEventListener('resize', resizeBlockly);
  resizeBlockly();

  // 抄自 Code Lab (見對話紀錄的截圖): 側欄 (執行紀錄面板) 收起/展開狀態,
  // 記在 localStorage, 等用家下次重新開啟這個分頁都記得住上次選的收/開。用
  // setSidePanelCollapsedInitial() 不是 toggleSidePanel(), 因為這個是
  // 「頁面剛剛 load 就要已經是這樣」, 不應該播 0.18s 的收起動畫。
  try {
    if (localStorage.getItem('blocklySideCollapsed') === '1') {
      window.AlphaBlockly.setSidePanelCollapsedInitial(true);
      resizeBlockly();
    }
  } catch (e) { /* localStorage 在部分 WebView 環境可能不可用, 沒有記錄就預設展開, 不緊要 */ }
}

// 視窗 resize / 側欄收/展開之後都要重新計算 Blockly 畫布大小 —— Blockly.svgResize()
// 一 call, 內部會自動一併 ComponentManager 那批 POSITIONABLE component (垃圾桶/
// zoom controls/我們自己的 EditFabControls/SidePanelToggleControl) 一齊重新
// 定位, 不用各自另外再動它們。拆做獨立 function 等 window resize listener 同
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
  // 用回 style.css 個淡藍/白色系 (--bg #f5f7fa / --accent #3b7dff),
  // 等整個頁面 (工具列/積木畫布) 看來是同一個產品,不是外掛一份第三方風格。
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

