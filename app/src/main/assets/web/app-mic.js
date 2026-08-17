// OpenLynx — client logic (app-mic.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: walkie-talkie 咪掣 no-op
// guard（瀏覽器 mic -> 機械人喇叭, 已永久停用）、相機全螢幕。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Walkie-talkie: browser mic -> robot speaker (已永久停用) ----------------
//
// 呢個功能（連同錄音/downsample/播放嘅成套實作）已經永久停用同移除，詳見
// README.md「已知限制」一節。`talkFab`（🎤 掣）留喺 index.html 純粹做 UI 佔位，
// 一開頁就俾 disableTalkFabIfInsecureContext()（見 app-log.js）強制 disabled，
// 唔會再觸發任何 pointer/keyboard 事件。startTalk() 保留做一個無條件 no-op guard，
// 以防萬一將來有第啲入口（例如新增嘅 keyboard shortcut）漏咗檢查 talkFab 嘅
// disabled 狀態就直接 call 到呢個 function。

async function startTalk() {
  // 講嘢 (🎤 walkie-talkie 咪) 功能已經永久停用 - 唔止喺 http:// (非安全來源) 先停用,
  // 而係無條件、任何情況都無反應。掣本身喺 disableTalkFabIfInsecureContext() (依家
  // 改咗做無條件 disable, 見下面) 已經 disabled 兼移除曬 pointerdown/keydown 嘅觸發
  // 途徑, 呢度加多一層 guard 係以防萬一有第啲入口(例如 keyboard shortcut)漏咗冇
  // check 就直接 call 到呢個 function。
  return;
}

/** Double-click/double-tap on the viewport toggles native fullscreen on that
 *  element, so the video (well - photo sequence) fills the whole screen. */
function toggleCameraFullscreen() {
  const viewport = cameraElements().viewport;
  const fsElement = document.fullscreenElement || document.webkitFullscreenElement;
  if (fsElement) {
    (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  } else {
    const request = viewport.requestFullscreen || viewport.webkitRequestFullscreen;
    if (request) {
      request.call(viewport);
    } else {
      showError("全螢幕", new Error("此瀏覽器不支援 Fullscreen API"));
    }
  }
}
