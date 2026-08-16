// OpenLynx — client logic (app-status.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 現時淨低內容: Tab 切換
// (switchTab, 俾成個 app 嘅所有 tab 用)。狀態頁/裝置資訊/省電開關嘅 Lynx 版本
// 喺 app-lynx.js (lynxRefreshStatus()/lynxRefreshSys()), 用緊自己獨立嘅
// lynxStatusOut/lynxSidOut 等 element id, 唔靠呢個檔案。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Tabs ----------------

function switchTab(tabId) {
  document.querySelectorAll(".tab-page").forEach(function (el) { el.classList.remove("active"); });
  document.querySelectorAll(".tab-btn").forEach(function (el) { el.classList.remove("active"); });
  document.getElementById(tabId).classList.add("active");
  document.querySelector(".tab-btn[data-tab=\"" + tabId + "\"]").classList.add("active");
}

