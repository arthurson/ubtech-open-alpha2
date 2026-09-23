// Open Alpha2 — Blockly 頁面語言切換 (中文/英文)。
//
// 這個 Blockly 頁面 (blockly.html) 設計上可以獨立在新分頁開, 不一定要依賴
// index.html (app-*.js) 正在執行 (見 blockly-page.js 開頭註解) —— 所以這裡
// 自己有一套完整的語言切換, 不靠 app-core.js 那套。但兩邊共用同一個
// localStorage key ("ui_lang"), 所以在主控制面板切了語言之後, 重開/切去
// Blockly 這個分頁都會自動跟回那個選擇, 不用兩邊分開選。
//
// 這裡負責切換的東西分開兩層, 兩層都會跟著切:
//
//  1. Blockly 官方內建字串 (標準 block 好似 controls_if/logic_compare/
//     math_arithmetic, 亦包括 workspace 右鍵選單 "刪除 3 個 block"、
//     undo/redo tooltip 這類) —— 這層由 blockly_msg_zh-hant.js /
//     blockly_msg_en.js 這兩個官方 message 檔提供, 兩個都已經預先 load 了
//     (見 blockly.html), load 的時候分別保存一份快照
//     (window.__ALPHA_BLOCKLY_MSG_ZH / __ALPHA_BLOCKLY_MSG_EN), 切換語言
//     時逐個 key 寫回 Blockly.Msg。
//
//  2. 這個 app 自己寫的 custom block (alpha_action_play/alpha_speech_tts/
//     alpha_servo_one_head 等等, 定義在 blockly-blocks.js) 同埋 toolbox 分類名
//     (blockly-toolbox.js) —— 這層由 blockly-blocks-i18n-data.js 的
//     window.ALPHA_BLOCK_I18N 字典 + window.t(key) 提供, block 的 init() 裡面
//     直接 call t() 拿回顯示字串。但 t() 是「call 的那一刻」計值, 不是
//     reactive binding, 所以只轉 uiLang 不會令已經建立好的 block 或者已經
//     build 好的 toolbox object 自動變 —— 要靠底下 rebuildWorkspaceForLocale()
//     逼 workspace 上面的 block 重新 init(), 同埋重新 call
//     window.buildAlphaToolbox() 重新產生一份新語言的 toolbox 才算數。
//
//  例外 (刻意不跟語言切換, 見 blockly-blocks.js 對應位置的註解):
//   - alpha_event_accel_threshold / alpha_event_sonar_triggered 的
//     FieldLabelSerializable 變數名 ("加速度計讀數"/"聲納資料") —— 這個值
//     同時是已存 XML 程式裡面的實際變數 key, 跟語言變會令舊程式讀不到變數。
//   - 動作/鈴聲 block 的 SUBCATEGORY 值 —— 這些是
//     blockly-actions-data.js/blockly-ringtone-data.js 真實資料分類的 key
//     (跟機身韌體實測分類名), 不是這頁自己的顯示文字。
//   - TTS 聲音名 (小峯 xiaofeng / 小欣 xiaoyan 等) —— 人名/聲音 ID, 不是 UI
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

  // 已經擺上畫布的 block, 它們的 field 文字 (包括內建 block 用到的
  // Blockly.Msg 字串) 在 block 建立的那刻已經 render 成 SVG text node, 只
  // 更新 Blockly.Msg 本身不會令已存在的 block 自動重畫。用 XML 序列化再
  // 反序列化一次來強制整個 workspace 重新建立這些 block (這個做法穩陣過逐個
  // block 手動找哪個 field 要重新生成文字), undo 歷史會因為
  // clearWorkspace() 而清空, 但這個是語言切換這種低頻操作可以接受的代價。
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

  // 套用去整個頁面外框 (header/工具列/側邊面板) 的靜態 UI 文字 —— 同
  // app-core.js 的 applyUiLanguage() 一樣的 [data-i18n] pattern, 兩邊刻意保持
  // 一致寫法, 純粹這裡用 window.ALPHA_BLOCK_I18N/window.t() 做字典, 不是
  // app-core.js 那份獨立 I18N object (這個頁面獨立於 index.html 存在, 不應該
  // 反過去依賴 app-core.js 才有字典)。
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

  // toolbox 分類名/範例裡面的文字是 window.buildAlphaToolbox() 在 call 的那一刻
  // 用 t() 計死在 object 裡, 所以要重新 call 一次先拿到新語言的版本, 再靠
  // updateToolbox() 塞回 workspace (連同官方分類, 例如邏輯/迴圈, 都會一起
  // 跟著新語言重新產生)。
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
  // ⚠️ 次序很重要: 動作分類/選項這些 data table 一定要在 workspace/toolbox
  // rebuild *之前* 就更新好, 因為 rebuildWorkspaceForLocale()
  // (XML roundtrip 令 block 重新 init()) 同 refreshToolbox() 都會即場讀
  // window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY 等表格來起 dropdown —— 如果
  // 掉轉次序, block/toolbox 就會用回舊語言的選項文字重新起一次, 要再切多
  // 一次才會修正。
    if (window.rebuildAlphaActionCategories) {
      window.rebuildAlphaActionCategories();
    }
    if (window.rebuildAlphaActionOptionTables) {
      window.rebuildAlphaActionOptionTables();
    }
    rebuildWorkspaceForLocale();
    refreshToolbox();
    // "-- 已儲存的程式 --" dropdown placeholder 不在 [data-i18n] 掃描範圍
    // 之內 (由 blockly-run.js 動態組 <option> HTML), 要主動 call 才會跟語言
    // 一起重新 render。
    if (window.AlphaBlockly && window.AlphaBlockly.refreshSavedProgramDropdown) {
      window.AlphaBlockly.refreshSavedProgramDropdown();
    }
    // 復原/剪貼/縮放按鈕列現在是 SVG UI component (見 blockly-run.js
    // 的 EditFabControls/ZoomFabControls), 不是普通 HTML <button>,
    // 不在 applyUiTextLocale() 的 [data-i18n] 掃描範圍之內 (它只會找
    // document.querySelectorAll('[data-i18n]') 那批 DOM 元素) —— 要主動 call
    // 才會令它們的 <title> tooltip 文字跟著轉語言。(側欄收起掣是普通 HTML,
    // 經 data-i18n-attr 自動跟, 唔使經這裡。)
    if (window.AlphaBlockly && window.AlphaBlockly.refreshEditControlsI18n) {
      window.AlphaBlockly.refreshEditControlsI18n();
    }
  };

  window.getUiLanguage = function () { return uiLang; };

  // 起 workspace 之前就要選好語言 (Blockly.inject 的時候就會讀取 Blockly.Msg
  // 建立 toolbox flyout), 所以在 DOMContentLoaded 一早套用回 localStorage
  // 選擇的語言, 不用等用家自己按一次才對。頁面外框文字 (data-i18n) 要等
  // DOMContentLoaded 先套用得 (查詢中的 DOM 元素要 parse 了才存在), 但
  // Blockly.Msg 這層沒有這個限制, 可以即刻套用 (Blockly.inject 起 workspace
  // 的時候才會讀用, 而那個亦是在 DOMContentLoaded callback 裡面先 call)。
  applyBlocklyMsgTable(uiLang);
  document.addEventListener('DOMContentLoaded', function () {
    updateToggleButtons();
    applyUiTextLocale();
  });
})();
