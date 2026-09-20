// Open Alpha2 — client logic (app-actions-pack.js)
// 實驗 tab 資源下載卡入面嘅動作包區：固定 actions.zip，後端直落＋自動 unzip
// 到 sdcard，舊 /sdcard/actions 改名 /sdcard/actions-backup。
// 後端見 ActionsPackController＋action/pack/download|status|cancel；
// 同 Vosk 共用單一下載通道（見 DownloadGate），每次只准一邊郁。
// 全部檔案共用 window/global scope（沒有用 ES module），載入順序由 index.html 的
// <script src="..."> 順序決定。

// 下載輪詢句柄。
var actionsPackDlTimer = null;
// 最近一次 status（語言切換當時重畫狀態行用，不用再問後端）。
var actionsPackLastStatus = null;

// 動作包落緊／解緊：Vosk 嗰邊撳鍵要一齊鎖（後端閘先係真擋）。
function actionsPackActive() {
  const st = actionsPackLastStatus && actionsPackLastStatus.state;
  return st === "downloading" || st === "unzipping";
}

// 全局語言切換（setUiLanguage）當時重畫狀態行，不用再問後端。
function actionsPackApplyUiLanguage() {
  if (actionsPackLastStatus) actionsPackRenderStatus(actionsPackLastStatus);
}

function actionsPackRefreshStatus() {
  return Alpha2Api.actionPackStatus().then(function (res) {
    if (!res || !res.ok) return res;
    actionsPackRenderStatus(res);
    // 轉頁 reload 當時正在下載：悄悄跟進。
    if (res.state === "downloading" || res.state === "unzipping") {
      actionsPackPollDownload();
    }
    return res;
  });
}

// 開始下載固定 actions.zip（舊 actions 自動改名 backup）。
function actionsPackDownload() {
  const out = document.getElementById("actionsPackStatusOut");
  return Alpha2Api.actionPackDownload().then(function (res) {
    if (!res || !res.ok) {
      if (out) out.textContent = t("actions_pack_fail_prefix") + (res && (res.message || res.error) ? (res.message || res.error) : "?");
      return res;
    }
    actionsPackRenderStatus(res);
    actionsPackPollDownload();
    return res;
  });
}

function actionsPackCancel() {
  return Alpha2Api.actionPackCancel().then(function (res) {
    return actionsPackPollDownloadOnce();
  });
}

// 1 秒 poll 一次 status，直到 done/error/cancelled。
function actionsPackPollDownload() {
  if (actionsPackDlTimer) return;
  actionsPackDlTimer = setInterval(actionsPackPollDownloadOnce, 1000);
}

function actionsPackPollDownloadOnce() {
  return Alpha2Api.actionPackStatus().then(function (res) {
    if (!res || !res.ok) return res;
    actionsPackRenderStatus(res);
    if (res.state === "done") {
      if (actionsPackDlTimer) { clearInterval(actionsPackDlTimer); actionsPackDlTimer = null; }
      const out = document.getElementById("actionsPackStatusOut");
      if (out) out.textContent = t("actions_pack_done");
      return res;
    }
    if (res.state === "error" || res.state === "cancelled" || res.state === "idle") {
      if (actionsPackDlTimer) { clearInterval(actionsPackDlTimer); actionsPackDlTimer = null; }
    }
    return res;
  });
}

// actions_pack event（後端 ActionsPackController 主動推）＋上面 poll 共用渲染：
// 進度出在實驗 tab 資源下載卡。done/error/cancelled 交 poll 收尾（停 timer）。
function actionsPackRenderStatus(res) {
  if (!res) return;
  actionsPackLastStatus = res;
  const out = document.getElementById("actionsPackStatusOut");
  const active = res.state === "downloading" || res.state === "unzipping";
  if (out) {
    if (res.state === "unzipping") {
      out.textContent = t("actions_pack_unzipping_hint");
    } else if (res.state === "downloading") {
      const pct = (res.progress !== undefined && res.progress >= 0) ? " " + res.progress + "%" : "";
      out.textContent = t("actions_pack_downloading_prefix") + pct;
    } else if (res.state === "done") {
      out.textContent = t("actions_pack_done");
    } else if (res.state === "error") {
      out.textContent = t("actions_pack_fail_prefix") + (res.message || "?");
    } else if (res.state === "cancelled") {
      out.textContent = t("actions_pack_cancelled");
    } else {
      out.textContent = "—";
    }
  }
  const cancelBtn = document.getElementById("actionsPackCancelBtn");
  if (cancelBtn) cancelBtn.style.display = active ? "" : "none";
  // 跨鎖 Vosk 撳鍵（單通道，見 syncResourceDownloadButtons＋DownloadGate）。
  try {
    if (typeof syncResourceDownloadButtons === "function") syncResourceDownloadButtons();
  } catch (e) {}
}
