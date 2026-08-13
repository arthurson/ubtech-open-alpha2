// Open Alpha2 — Blockly 頁面語言切換 (中文/英文)。
//
// 呢個 Blockly 頁面 (blockly.html) 設計上可以獨立喺新分頁開, 唔一定要靠
// index.html (app-*.js) 已經 run 緊 (見 blockly-page.js 開頭註解) —— 所以呢度
// 自己有一套完整嘅語言切換, 唔靠 app-core.js 嗰套。但兩邊共用同一個
// localStorage key ("ui_lang"), 所以喺主控制面板切咗語言之後, 開返/切去
// Blockly 呢個分頁都會自動跟返嗰個揀擇, 唔使兩邊分開揀。
//
// 呢度負責切換嘅嘢分開兩層, 兩層都會跟住切:
//
//  1. Blockly 官方內建字串 (標準 block 好似 controls_if/logic_compare/
//     math_arithmetic, 亦包括 workspace 右鍵選單 "刪除 3 個 block"、
//     undo/redo tooltip 呢類) —— 呢層由 blockly_msg_zh-hant.js /
//     blockly_msg_en.js 呢兩個官方 message 檔提供, 兩個都已經預先 load 咗
//     (見 blockly.html), load 嗰陣分別影低一份快照
//     (window.__ALPHA_BLOCKLY_MSG_ZH / __ALPHA_BLOCKLY_MSG_EN), 切換語言
//     時逐個 key 寫返入 Blockly.Msg。
//
//  2. 呢個 app 自己寫嘅 custom block (alpha_action_play/alpha_speech_tts/
//     alpha_servo_one_head 等等, 定義喺 blockly-blocks.js) 同埋 toolbox 分類名
//     (blockly-toolbox.js) —— 呢層由 blockly-blocks-i18n-data.js 嘅
//     window.ALPHA_BLOCK_I18N 字典 + window.t(key) 提供, block 嘅 init() 入面
//     直接 call t() 攞返顯示字串。但 t() 係「call 嗰一刻」計值, 唔係
//     reactive binding, 所以淨係轉 uiLang 唔會令已經起好嘅 block 或者已經
//     build 好嘅 toolbox object 自動變 —— 要靠底下 rebuildWorkspaceForLocale()
//     逼 workspace 上面啲 block 重新 init(), 同埋重新 call
//     window.buildAlphaToolbox() 整返份新語言嘅 toolbox 先算數。
//
//  例外 (刻意唔跟語言切換, 見 blockly-blocks.js 對應位置嘅註解):
//   - alpha_event_accel_threshold / alpha_event_sonar_triggered 嘅
//     FieldLabelSerializable 變數名 ("加速度計讀數"/"聲納資料") —— 呢個值
//     同時係已存 XML 程式入面嘅實際變數 key, 跟語言變會令舊程式讀唔到變數。
//   - 動作/鈴聲 block 嘅 SUBCATEGORY 值 —— 呢啲係
//     blockly-actions-data.js/blockly-ringtone-data.js 真實資料分類嘅 key
//     (跟機身韌體實測分類名), 唔係呢頁自己嘅顯示文字。
//   - TTS 聲音名 (小峯 xiaofeng / 小欣 xiaoyan 等) —— 人名/聲音 ID, 唔係 UI
//     描述文字。

(function () {
  let uiLang = localStorage.getItem('ui_lang') || 'zh';

  function msgTableFor(lang) {
    return lang === 'en' ? window.__ALPHA_BLOCKLY_MSG_EN : window.__ALPHA_BLOCKLY_MSG_ZH;
  }

  function applyBlocklyMsgTable(lang) {
    const table = msgTableFor(lang);
    if (!table) return;
    for (const key in table) {
      if (Object.prototype.hasOwnProperty.call(table, key)) {
        Blockly.Msg[key] = table[key];
      }
    }
  }

  // 已經擺落畫布嘅 block, 佢哋嘅 field 文字 (包括內建 block 用到嘅
  // Blockly.Msg 字串) 喺 block 起嗰刻已經 render 咗做 SVG text node, 淨係
  // 更新 Blockly.Msg 本身唔會令已存在嘅 block 自動重畫。用 XML 序列化再
  // 反序列化一次嚟強制成個 workspace 重新起晒啲 block (呢個做法穩陣過逐粒
  // block 手動搵邊個 field 要重新生成文字), undo 歷史會因為
  // clearWorkspace() 而清空, 但呢個係語言切換呢種低頻操作可以接受嘅代價。
  function rebuildWorkspaceForLocale() {
    const workspace = window.__alphaBlocklyWorkspace;
    if (!workspace) return;
    let xml;
    try {
      xml = Blockly.Xml.workspaceToDom(workspace);
    } catch (e) {
      console.warn('workspaceToDom failed, skip rebuild', e);
      return;
    }
    workspace.clear();
    try {
      Blockly.Xml.domToWorkspace(xml, workspace);
    } catch (e) {
      console.warn('domToWorkspace failed after locale switch', e);
    }
  }

  function updateToggleButtons() {
    document.querySelectorAll('[data-ui-lang-btn]').forEach(function (btn) {
      btn.classList.toggle('active', btn.dataset.uiLangBtn === uiLang);
    });
  }

  // 套用去成個頁面外框 (header/工具列/側邊面板) 嘅靜態 UI 文字 —— 同
  // app-core.js 個 applyUiLanguage() 一樣嘅 [data-i18n] pattern, 兩邊刻意保持
  // 一致寫法, 純粹呢度用 window.ALPHA_BLOCK_I18N/window.t() 做字典, 唔係
  // app-core.js 嗰份獨立 I18N object (呢個頁面獨立於 index.html 存在, 唔應該
  // 反過去倚賴 app-core.js 先至有字典)。
  function applyUiTextLocale() {
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      const key = el.dataset.i18n;
      if (!window.ALPHA_BLOCK_I18N || !window.ALPHA_BLOCK_I18N[key]) return;
      const text = window.t(key);
      const attr = el.dataset.i18nAttr;
      if (attr) {
        el.setAttribute(attr, text);
      } else {
        el.textContent = text;
      }
    });
    if (window.ALPHA_BLOCK_I18N && window.ALPHA_BLOCK_I18N.page_title) {
      document.title = window.t('page_title');
    }
  }

  // toolbox 分類名/範例入面嘅文字係 window.buildAlphaToolbox() 喺 call 嗰一刻
  // 用 t() 計死落個 object 度, 所以要重新 call 一次先攞到新語言嘅版本, 再靠
  // updateToolbox() 塞返落 workspace (連埋官方分類, 例如邏輯/迴圈, 都會一齊
  // 跟住新語言重新整)。
  function refreshToolbox() {
    const workspace = window.__alphaBlocklyWorkspace;
    if (!workspace || !window.buildAlphaToolbox) return;
    try {
      window.ALPHA_TOOLBOX = window.buildAlphaToolbox();
      workspace.updateToolbox(window.ALPHA_TOOLBOX);
    } catch (e) {
      console.warn('updateToolbox failed after locale switch', e);
    }
  }

  window.setUiLanguage = function (lang) {
    if (lang !== 'zh' && lang !== 'en') return;
    uiLang = lang;
    localStorage.setItem('ui_lang', lang);
    applyBlocklyMsgTable(lang);
    updateToggleButtons();
    applyUiTextLocale();
    // applyUiTextLocale() 啱啱樣樣 data-i18n 元素 (包括 <h1>) 都重新用純 i18n
    // 字串蓋過晒 —— 呢個會抹走 blockly-page.js updateBackendButtonsUI() 加落去
    // 個 "[Alpha2]"/"[Lynx]" 字尾, 所以呢度要再叫一次補返。
    if (typeof window.updateBackendButtonsUI === 'function') window.updateBackendButtonsUI();
    // ⚠️ 次序好重要: 動作分類/選項呢啲 data table 一定要喺 workspace/toolbox
    // rebuild *之前* 就更新好, 因為 rebuildWorkspaceForLocale()
    // (XML roundtrip 令 block 重新 init()) 同 refreshToolbox() 都會即場讀
    // window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY 等表格嚟起 dropdown —— 如果
    // 掉轉次序, block/toolbox 就會用返舊語言嘅選項文字重新起一次, 要再切多
    // 一次先會執正。
    if (window.rebuildAlphaActionCategories) {
      window.rebuildAlphaActionCategories();
    }
    if (window.rebuildAlphaActionOptionTables) {
      window.rebuildAlphaActionOptionTables();
    }
    rebuildWorkspaceForLocale();
    refreshToolbox();
    // "-- 已儲存嘅程式 --" dropdown placeholder 唔喺 [data-i18n] 掃描範圍
    // 之內 (由 blockly-run.js 動態組 <option> HTML), 要主動 call 先會跟語言
    // 一齊重新 render。
    if (window.AlphaBlockly && window.AlphaBlockly.refreshSavedProgramDropdown) {
      window.AlphaBlockly.refreshSavedProgramDropdown();
    }
    // 復原/剪貼掣列 + 側欄收埋掣而家係 SVG UI component (見 blockly-run.js
    // 嘅 EditFabControls/SidePanelToggleControl), 唔係普通 HTML <button>,
    // 唔喺 applyUiTextLocale() 嘅 [data-i18n] 掃描範圍之內 (佢淨係識揾
    // document.querySelectorAll('[data-i18n]') 嗰批 DOM 元素) —— 要主動 call
    // 先會令佢哋嘅 <title> tooltip 文字跟住轉語言。
    if (window.AlphaBlockly && window.AlphaBlockly.refreshEditControlsI18n) {
      window.AlphaBlockly.refreshEditControlsI18n();
    }
  };

  window.getUiLanguage = function () { return uiLang; };

  // 起 workspace 之前就要揀好語言 (Blockly.inject 嗰陣就會讀緊 Blockly.Msg
  // 起 toolbox flyout), 所以喺 DOMContentLoaded 一早套用返 localStorage
  // 揀嘅語言, 唔使等用家自己撳一次先啱。頁面外框文字 (data-i18n) 要等
  // DOMContentLoaded 先套用得 (查詢緊嘅 DOM 元素要 parse 咗先存在), 但
  // Blockly.Msg 呢層冇呢個限制, 可以即刻套用 (Blockly.inject 起 workspace
  // 嗰陣先會讀用, 而嗰個亦係喺 DOMContentLoaded callback 入面先 call)。
  applyBlocklyMsgTable(uiLang);
  document.addEventListener('DOMContentLoaded', function () {
    updateToggleButtons();
    applyUiTextLocale();
  });
})();
