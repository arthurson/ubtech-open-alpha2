// client logic (app-speech.js)
// 內容: 麥克風擁有權 UI 更新。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Speech ----------------
//
// 麥克風擁有權指示燈 + 「持續搶 mic」card。
//
// updateMicStateUi() 同時處理兩個 UI 更新入口: 1) 用戶自己撳掣 (setMic()/
// setMicKeepHeld() 嘅 .then()), 2) server 端 mic_state event 推送過嚟 (見
// app-log.js 嘅 appendLog()) - 兩者都經過呢個 function, 保證指示燈、hint
// 文字、keep-held checkbox 三者永遠同步, 唔會因為淨係更新其中一個入口就走樣。
function updateMicStateUi(held, keepHeld) {
  const dot = document.getElementById("micStateDot");
  const label = document.getElementById("micStateLabel");
  const keepCheckbox = document.getElementById("micKeepHeld");
  if (dot) {
    dot.classList.toggle("mic-state-dot-on", !!held);
    dot.classList.toggle("mic-state-dot-off", !held);
  }
  if (label) {
    label.textContent = t(held ? "mic_state_on" : "mic_state_off");
  }
  if (keepCheckbox) {
    keepCheckbox.checked = !!keepHeld;
  }
}

function setMic(wake) {
  return lynxApi("speech/set_mic", { wake: String(wake) }).then(function (res) {
    updateMicStateUi(res.held, res.keepHeld);
    return res;
  });
}

function setMicKeepHeld(keep) {
  return lynxApi("speech/set_mic_keep_held", { keep: String(keep) }).then(function (res) {
    updateMicStateUi(res.held, res.keepHeld);
    return res;
  });
}

