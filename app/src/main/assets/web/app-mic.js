// OpenLynx — client logic (app-mic.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份，內容：相機全螢幕。
// walkie-talkie（瀏覽器 mic -> 機械人喇叭）功能已經連同前端/後端成套實作一齊
// 移除，唔喺呢個 project 存在，見 README.md「已知限制」一節。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

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
