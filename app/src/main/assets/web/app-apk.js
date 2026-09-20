// Open Alpha2 — client logic (app-apk.js)
// 實驗 tab 資源下載卡入面嘅 Google TTS 區：固定 APK，後端直落去 sdcard 即完。
// 安裝唔經面板（每部機人手 adb install 一次）。
// 後端見 ApkDownloadController＋apk/download|status|cancel；
// 同 Vosk／動作包共用單一下載通道（見 DownloadGate），每次只准一邊下載緊。
// 全部檔案共用 window/global scope（沒有用 ES module），載入順序由 index.html 的
// <script src="..."> 順序決定。

// 下載輪詢句柄。
var apkDlTimer = null;
// 最近一次 status（語言切換當時重畫狀態行用，不用再問後端）。
var apkLastStatus = null;

// APK 下載緊：Vosk／動作包嗰邊撳鍵要一齊鎖（後端閘先係真擋）。
function apkActive() {
  const st = apkLastStatus && apkLastStatus.state;
  return st === "downloading";
}

// 全局語言切換（setUiLanguage）當時重畫狀態行，不用再問後端。
function apkApplyUiLanguage() {
  if (apkLastStatus) apkRenderStatus(apkLastStatus);
}

function apkRefreshStatus() {
  return Alpha2Api.apkStatus().then(function (res) {
    if (!res || !res.ok) return res;
    apkRenderStatus(res);
    // 轉頁 reload 當時下載緊：悄悄跟進。
    if (res.state === "downloading") {
      apkPollDownload();
    }
    return res;
  });
}

// 開始下載。
function apkDownload() {
  const out = document.getElementById("apkStatusOut");
  return Alpha2Api.apkDownload().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("apk_dl_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    apkRenderStatus(res);
    apkPollDownload();
    return res;
  });
}

function apkCancel() {
  return Alpha2Api.apkCancel().then(function (res) {
    return apkPollDownloadOnce();
  });
}

// 1 秒 poll 一次 status，直到 done/error/cancelled。
function apkPollDownload() {
  if (apkDlTimer) return;
  apkDlTimer = setInterval(apkPollDownloadOnce, 1000);
}

function apkPollDownloadOnce() {
  return Alpha2Api.apkStatus().then(function (res) {
    if (!res || !res.ok) return res;
    apkRenderStatus(res);
    if (res.state === "done" || res.state === "error"
        || res.state === "cancelled" || res.state === "idle") {
      if (apkDlTimer) { clearInterval(apkDlTimer); apkDlTimer = null; }
    }
    return res;
  });
}

// apk event（後端 ApkDownloadController 主動推）＋上面 poll 共用渲染：
// 進度出在實驗 tab 資源下載卡。done/error/cancelled 交 poll 收尾（停 timer）。
function apkRenderStatus(res) {
  if (!res) return;
  apkLastStatus = res;
  const out = document.getElementById("apkStatusOut");
  const active = res.state === "downloading";
  if (out) {
    if (res.state === "downloading") {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("actions_pack_downloading_prefix") + pct;
    } else if (res.state === "done") {
      out.textContent = t("apk_dl_done");
    } else if (res.state === "error") {
      out.textContent = t("apk_dl_fail_prefix") + (res.message || "?");
    } else if (res.state === "cancelled") {
      out.textContent = t("actions_pack_cancelled");
    } else {
      out.textContent = "—";
    }
  }
  const cancelBtn = document.getElementById("apkCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // 跨鎖 Vosk／動作包撳鍵（單通道，見 syncResourceDownloadButtons＋DownloadGate）。
  try {
    if (typeof syncResourceDownloadButtons === "function") syncResourceDownloadButtons();
  } catch (e) {}
}
