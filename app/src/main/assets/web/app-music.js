// Open Alpha2 — client logic (app-music.js)
// 本地音樂 tab: 對接 MainActivity.java 已有嘅 "audio/local_music/*" 呢一套
// endpoint (本身俾小智語音/AI tool call 用, 而家加返一層瀏覽器 UI)。真正播放
// (STREAM_MUSIC MediaPlayer)、equalizer、隨機動作全部喺 server 端做, 呢個檔案
// 純粹係 UI + 定時 poll 狀態嚟更新進度條, 冇任何音訊 byte 經過瀏覽器 (同
// app-mic.js 嗰種即時串流完全唔同)。
// 全部函數共用 window/global scope (冇用 ES module), load 順序見 index.html
// 嘅 <script src="..."> 排列 - 要喺 app-core.js (api()/t() 呢啲 helper) 之後。

// ---------------- state ----------------

let musicTracks = [];            // 上次 musicRefreshList() 攞返嚟嘅清單
let musicCurrentName = null;     // 目前揀選/播放緊嗰首歌嘅檔名
let musicStatusPollTimer = null;
let musicSeekDragging = false;   // 用戶拖緊進度條嗰陣, 唔好俾 poll 蓋走個位置
let musicEqPresets = [];         // 上次 musicRefreshEqPresets() 攞返嚟嘅 preset 清單
let musicPlayAllMode = false;    // 「▶ 全部」模式 - 一首播完自動接落一首 (見
                                 // musicPollStatusLoop() 嘅 hasTrack=false 分支)
let musicLastPlayedName = null;  // 上次播過嘅歌名 - stop 嗰陣 musicCurrentName 會
                                 // 清走, 但之後撳「▶」應該重播返啱先嗰首, 唔係
                                 // 冇反應 (2026-08 v2 修正)
let musicHasLoadedTrack = false; // server 端 currentMusicPlayer 仲 load 緊嘢嗎 -
                                 // 暫停緊都係 true; 全部停止/播完先變 false。
                                 // 俾 musicTogglePlayPause() 分「resume」定
                                 // 「由頭 play」用 (2026-08 v2)
let musicSpectrumTimer = null;   // spectrum 輪詢 timer (setTimeout 鏈)
let musicSpectrumAnimTimer = null; // 平滑動畫 timer (~33ms 重畫)
let musicSpectrumTargets = [];   // 最近一次 server 攞返嚟嘅目標值
let musicSpectrumSmooth = [];    // 平滑化後用嚟畫嘅值
let sharedActiveSource = null;   // "local" 或 "radio"，記錄最後一次播放來源，用於共用上一首/下一首/隨機分流

// ---------------- audio spectrum ----------------
// 2026-08 v2 新增: server 端 Visualizer FFT -> audio/local_music/spectrum 每條
// band 一個 0-255 值。輪詢 100ms 更新目標值, 另外有條 ~33ms 嘅動畫 timer 用
// 「快上慢落」(attack 即刻, release 指數衰減) 插值, bar 先會順滑唔會一跳一跳。

function musicStartSpectrumLoop() {
  if (!musicSpectrumTimer) {
    musicSpectrumLoop();
  }
  if (!musicSpectrumAnimTimer) {
    musicSpectrumAnimTimer = setInterval(musicRenderSpectrumFrame, 33);
  }
}

function musicStopSpectrumLoop() {
  if (musicSpectrumTimer) {
    clearTimeout(musicSpectrumTimer);
    musicSpectrumTimer = null;
  }
  if (musicSpectrumAnimTimer) {
    clearInterval(musicSpectrumAnimTimer);
    musicSpectrumAnimTimer = null;
  }
  musicSpectrumTargets = [];
  musicSpectrumSmooth = [];
  musicDrawSpectrum(null); // 收工畫返全平
}

function musicSpectrumLoop() {
  api("audio/local_music/spectrum").then(function (res) {
    if (!res.ok) return;
    musicSpectrumTargets = res.bands || [];
    musicSpectrumTimer = setTimeout(musicSpectrumLoop, 100);
  }).catch(function () {
    musicSpectrumTimer = setTimeout(musicSpectrumLoop, 300); // 斷線慢啲再試
  });
}

/** 動畫幀: 每條 band 向目標值「即刻追上、慢慢跌」, 然後畫。 */
function musicRenderSpectrumFrame() {
  const n = musicSpectrumTargets.length || 24;
  if (musicSpectrumSmooth.length !== n) {
    musicSpectrumSmooth = new Array(n).fill(0);
  }
  for (let i = 0; i < n; i++) {
    const target = musicSpectrumTargets[i] || 0;
    const prev = musicSpectrumSmooth[i];
    // attack: 目標高過現值即刻跟上 (唔會滯後); release: 每幀衰減 18%,
    // 跌落嚟順滑自然, 唔會彈吓彈吓。
    musicSpectrumSmooth[i] = target >= prev ? target : Math.max(target, prev * 0.82);
  }
  musicDrawSpectrum(musicSpectrumSmooth);
}

/** 畫一幀 spectrum - bands=null 就畫全平 (停止狀態)。 */
function musicDrawSpectrum(bands) {
  const canvas = document.getElementById("musicSpectrumCanvas");
  if (!canvas || !canvas.getContext) return;
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);
  const n = bands ? bands.length : 24;
  if (n === 0) return;
  const gap = 3;
  const barW = Math.max(2, Math.floor((w - gap * (n + 1)) / n));
  for (let i = 0; i < n; i++) {
    const v = bands ? Math.max(0, Math.min(255, bands[i] | 0)) : 0;
    const barH = Math.max(2, Math.round(v / 255 * (h - 6)));
    const x = gap + i * (barW + gap);
    const y = h - 3 - barH;
    // 由綠到紅嘅漸變 (低頻綠、高頻紅), 頂部加少少亮色。
    const hue = 120 - Math.round(120 * i / n);
    const grad = ctx.createLinearGradient(0, y, 0, h - 3);
    grad.addColorStop(0, "hsl(" + hue + ",95%,65%)");
    grad.addColorStop(1, "hsl(" + hue + ",85%,38%)");
    ctx.fillStyle = v > 0 ? grad : "#232a36";
    ctx.fillRect(x, y, barW, barH);
  }
}

function musicFormatTime(ms) {
  const totalSec = Math.max(0, Math.floor((ms || 0) / 1000));
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return min + ":" + (sec < 10 ? "0" : "") + sec;
}

function musicFormatSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + " KB";
  return bytes + " B";
}

function musicInit() {
  musicRefreshList();
  musicRefreshStatus();
  musicRefreshEqPresets();
  musicRefreshFillerToggle();
  refreshSharedVolume();
  // 頻譜共用：無論本地或電台，同一 canvas 同一輪詢
  // 若本地有播放即啟動，否則由 radioInit 觸發
}

// ---------------- track list ----------------

function musicRefreshList() {
  const container = document.getElementById("musicListContainer");
  if (!container) return Promise.resolve();
  container.textContent = t("music_list_loading");
  return api("audio/local_music/list").then(function (res) {
    if (!res.ok) return;
    // 2026-08 註: 之前一個版本嘅音樂 tab (掃兩個大小寫唔同但實際係同一個
    // 資料夾嘅路徑) 令每首歌顯示兩次 - 而家呢個清單直接嚟自 server 端
    // listLocalMusicFiles() 單一個 LOCAL_MUSIC_DIR, 冇呢個問題; 呢度仍然
    // 做多一層以防萬一嘅去重 (跟檔名), 純粹係保險, 唔應該實際命中。
    const seen = new Set();
    musicTracks = (res.files || []).filter(function (f) {
      if (seen.has(f.name)) return false;
      seen.add(f.name);
      return true;
    });
    musicRenderList();
  });
}

function musicRenderList() {
  const container = document.getElementById("musicListContainer");
  if (!container) return;
  container.innerHTML = "";
  if (musicTracks.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("music_list_empty");
    container.appendChild(p);
    return;
  }
  musicTracks.forEach(function (track) {
    const row = document.createElement("div");
    row.className = "music-track-row" + (track.name === musicCurrentName ? " active" : "");

    const nameSpan = document.createElement("span");
    nameSpan.className = "music-track-name";
    nameSpan.textContent = track.name;

    const sizeSpan = document.createElement("span");
    sizeSpan.className = "music-track-size";
    sizeSpan.textContent = musicFormatSize(track.sizeBytes || 0);

    // 2026-08 v2: 取消咗原本每行一粒「▶」掣 - 成行 click 就播 (user-select:none
    // + cursor:pointer 喺 style.css 度), 歌多嗰陣少一半掣, 清爽啲。
    row.onclick = function () { musicPlay(track.name); };

    row.appendChild(nameSpan);
    row.appendChild(sizeSpan);
    container.appendChild(row);
  });
}

// ---------------- playback controls ----------------

function musicPlay(name) {
  musicCurrentName = name;
  musicLastPlayedName = name;
  sharedActiveSource = "local";
  if (typeof radioCurrentName !== "undefined") radioCurrentName = null;
  if (typeof radioCurrentId !== "undefined") radioCurrentId = null;
  musicRenderList();
  if (typeof radioRenderList === "function") radioRenderList();
  if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(name, false);
  api("audio/local_music/play", { name: name }).then(function (res) {
    if (!res.ok) return;
    musicRefreshStatus();
    musicStartStatusPolling();
  });
}

// ---------------- prev / next / random / play-all ----------------
// 2026-08 v2 新增: 全部 client 端排歌 - server 嘅 audio/local_music/* 冇 playlist
// 概念, 淨係「播呢個檔」。呢幾個 function 只係喺 musicTracks 陣列入面計下一條
// 應該播邊個, 再 call 返 musicPlay()。

/** 目前播緊嗰首喺 musicTracks 入面嘅 index, 搵唔到 (清單變咗/冇播) 回 -1。 */
function musicCurrentIndex() {
  if (!musicCurrentName) return -1;
  for (let i = 0; i < musicTracks.length; i++) {
    if (musicTracks[i].name === musicCurrentName) return i;
  }
  return -1;
}

function isRadioActive() {
  if (sharedActiveSource === "radio") return true;
  if (sharedActiveSource === "local") return false;
  // 未有明確來源時，以當前是否有電台正在播作判斷
  return typeof radioCurrentName !== "undefined" && radioCurrentName && typeof radioStations !== "undefined" && radioStations.length > 0;
}
function musicPlayPrev() {
  if (isRadioActive() && typeof radioPlayPrev === "function") { radioPlayPrev(); return; }
  if (musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const target = idx <= 0 ? musicTracks.length - 1 : idx - 1;
  musicPlay(musicTracks[target].name);
}
function musicPlayNext() {
  if (isRadioActive() && typeof radioPlayNext === "function") { radioPlayNext(); return; }
  if (musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const target = idx < 0 || idx >= musicTracks.length - 1 ? 0 : idx + 1;
  musicPlay(musicTracks[target].name);
}
function musicPlayRandom() {
  if (isRadioActive() && typeof radioPlayRandom === "function") { radioPlayRandom(); return; }
  if (musicTracks.length === 0) return;
  let idx = Math.floor(Math.random() * musicTracks.length);
  if (musicTracks.length > 1) {
    while (musicTracks[idx].name === musicCurrentName) {
      idx = Math.floor(Math.random() * musicTracks.length);
    }
  }
  musicPlay(musicTracks[idx].name);
}
function musicPlayAll() {
  if (isRadioActive()) return; // 電台為直播，無「全部」概念
  if (musicTracks.length === 0) return;
  musicPlayAllMode = true;
  const idx = musicCurrentIndex();
  if (idx < 0) {
    musicPlay(musicTracks[0].name);
  } else {
    musicStartStatusPolling();
  }
}

/** 「▶ 全部」模式底下搵下一首 - 由而家嗰首開始向後搵, 到咗最尾繞返頭。 */
function musicAdvancePlayAll() {
  if (!musicPlayAllMode || musicTracks.length === 0) return;
  const idx = musicCurrentIndex();
  const next = idx < 0 ? 0 : (idx + 1) % musicTracks.length;
  musicPlay(musicTracks[next].name);
}

function musicTogglePlayPause() {
  if (isRadioActive()) {
    // 電台無暫停，▶/⏸ 切換視為 停止/重播當前電台
    const btn = document.getElementById("musicPlayPauseBtn");
    const isPlaying = btn && btn.textContent.trim() === "⏸";
    if (isPlaying && typeof radioStop === "function") { radioStop(); }
    else if (radioCurrentName) { radioPlay(radioCurrentName); }
    else if (typeof radioPlayRandom === "function") { radioPlayRandom(); }
    return;
  }
  const btn = document.getElementById("musicPlayPauseBtn");
  const isPlaying = btn && btn.textContent.trim() === "⏸";
  if (isPlaying) {
    api("audio/local_music/pause").then(musicRefreshStatus);
  } else if (musicCurrentName || musicLastPlayedName) {
    // 有 track load 咗 (就算停咗機都未 release, 例如暫停緊/播完) → resume;
    // 真係冇 → fallback 由頭播上次嗰首。
    const hasLoadedTrack = musicHasLoadedTrack;
    const name = musicCurrentName || musicLastPlayedName;
    if (hasLoadedTrack) {
      api("audio/local_music/resume").then(function (res) {
        if (res.ok && res.hasTrack !== false) {
          musicRefreshStatus();
          return;
        }
        musicPlay(name); // resume 失敗 (例如已經被 stop 清走) - 由頭播過
      });
    } else {
      musicPlay(name);
    }
  } else if (musicTracks.length > 0) {
    musicPlay(musicTracks[0].name);
  }
}

function musicStop() {
  musicPlayAllMode = false;
  musicHasLoadedTrack = false;
  api("audio/local_music/stop").then(function (res) {
    if (!res.ok) return;
    musicStopStatusPolling();
    musicCurrentName = null;
    musicRenderList();
    musicApplyStatus({ ok: true, hasTrack: false, playing: false,
      positionMs: 0, durationMs: 0, name: null });
  });
}

/**
 * 2026-08 v2 新增: 音樂 tab 嘅「⏹ 全部停止」- 同語音 tab 嗰粒總停鍵
 * (xiaozhiStopAll(), 見 app-xiaozhi.js) 睇齊: action/stop + speech/stop +
 * audio/local_music/stop + audio/radio/stop 四樣一齊停, 另加埋自己個
 * 「▶ 全部」自動接歌模式。直接重用 xiaozhiStopAll() 唔另寫一套, 保證兩邊
 * 行為永遠一致; 佢入面 Promise.all 已經包咗單一 endpoint 失敗唔影響其餘。
 */
function musicStopAll() {
  musicPlayAllMode = false;
  musicHasLoadedTrack = false;
  sharedActiveSource = null;
  xiaozhiResetTtsQueue();
  Promise.all([
    api("action/stop"),
    api("speech/stop"),
    api("audio/local_music/stop"),
    api("audio/radio/stop"),
  ]).then(function () {
    musicStopStatusPolling();
    musicCurrentName = null;
    musicLastPlayedName = null;
    if (typeof radioCurrentName !== "undefined") radioCurrentName = null;
    if (typeof radioCurrentId !== "undefined") radioCurrentId = null;
    musicRenderList();
    if (typeof radioRenderList === "function") radioRenderList();
    musicApplyStatus({ ok: true, hasTrack: false, playing: false,
      positionMs: 0, durationMs: 0, name: null });
    if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(t("music_now_playing_none"), false);
    musicStopSpectrumLoop();
  });
}

function musicOnSeekInput(value) {
  musicSeekDragging = true;
  document.getElementById("musicPositionLabel").textContent = musicFormatTime(Number(value));
}

function musicSeekTo(value) {
  api("audio/local_music/seek", { ms: String(Math.round(Number(value))) }).then(function () {
    musicSeekDragging = false;
    musicRefreshStatus();
  });
}

function musicOnVolumeInput(value) {
}

function musicSetVolume(value) {
  api("audio/local_music/volume", { percent: String(Math.round(Number(value))) });
}

// 共用音量（系統 STREAM_MUSIC，同時影響本地與電台）— 前端共用滑桿
function onSharedVolumeInput(value) {
  const valEl = document.getElementById("sharedVolumeVal");
  if (valEl) valEl.textContent = value;
}
function setSharedVolume(value) {
  const v = Math.max(0, Math.min(15, parseInt(value, 10) || 0));
  api("audio/volume/set", { level: String(v) }).then(function (res) {
    if (res.ok) {
      const valEl = document.getElementById("sharedVolumeVal");
      if (valEl) valEl.textContent = String(res.volume != null ? res.volume : v);
      const slider = document.getElementById("sharedVolumeSlider");
      if (slider) slider.value = String(res.volume != null ? res.volume : v);
      // 同步狀態頁的 slider
      const statusSlider = document.getElementById("volumeSlider");
      const statusVal = document.getElementById("volumeVal");
      if (statusSlider) statusSlider.value = String(res.volume != null ? res.volume : v);
      if (statusVal) statusVal.textContent = String(res.volume != null ? res.volume : v);
    }
  });
  // 同時將本地 per-track 音量設為 100%，避免兩級音量疊加導致偏細聲
  api("audio/local_music/volume", { percent: "100" });
}
function refreshSharedVolume() {
  api("audio/volume/get").then(function (res) {
    if (!res.ok) return;
    const slider = document.getElementById("sharedVolumeSlider");
    const valEl = document.getElementById("sharedVolumeVal");
    if (slider) { slider.max = String(res.max || 15); slider.value = String(res.volume); }
    if (valEl) valEl.textContent = String(res.volume);
  });
}

// ---------------- status polling ----------------
//
// 用 setTimeout 鏈 (同 xiaozhiPollActivationStatus() 一致), 保證上一次
// request 攞到結果先至排下一次, 網絡慢嗰陣唔會越疊越多。淨係喺實際有嘢
// 播緊 (hasTrack=true) 先繼續 poll。
function musicStartStatusPolling() {
  musicStopStatusPolling();
  musicPollStatusLoop();
}

function musicStopStatusPolling() {
  if (musicStatusPollTimer) {
    clearTimeout(musicStatusPollTimer);
    musicStatusPollTimer = null;
  }
  musicStopSpectrumLoop();
}

function musicPollStatusLoop() {
  api("audio/local_music/status").then(function (res) {
    if (!res.ok) return;
    if (!res.hasTrack && musicPlayAllMode) {
      musicAdvancePlayAll();
      return;
    }
    musicApplyStatus(res);
    if (res.hasTrack) {
      musicStartSpectrumLoop();
      musicStatusPollTimer = setTimeout(musicPollStatusLoop, 1000);
    } else {
      musicPlayAllMode = false;
      // 共用頻譜：本地停咗但電台仲播緊，保留頻譜
      if (!radioCurrentName) {
        musicStopSpectrumLoop();
      }
    }
  });
}

function musicRefreshStatus() {
  return api("audio/local_music/status").then(function (res) {
    if (!res.ok) return;
    musicApplyStatus(res);
    if (res.hasTrack) musicStartStatusPolling();
  });
}

function updateSharedNowPlaying(text, isRadio) {
  const shared = document.getElementById("sharedNowPlaying");
  const progressRow = document.getElementById("sharedProgressRow");
  if (shared) {
    shared.textContent = text || t("music_now_playing_none");
    shared.classList.toggle("radio-playing", !!isRadio);
  }
  if (progressRow) {
    // 電台為直播無進度，隱藏進度條；本地有進度則顯示
    progressRow.style.display = isRadio ? "none" : "flex";
  }
}

function musicApplyStatus(res) {
  const nowPlaying = document.getElementById("musicNowPlaying");
  const sharedNow = document.getElementById("sharedNowPlaying");
  const playPauseBtn = document.getElementById("musicPlayPauseBtn");
  const seekBar = document.getElementById("musicSeekBar");
  const posLabel = document.getElementById("musicPositionLabel");
  const durLabel = document.getElementById("musicDurationLabel");
  if (!nowPlaying || !playPauseBtn || !seekBar) return;

  if (!res.hasTrack) {
    musicHasLoadedTrack = false;
    nowPlaying.textContent = t("music_now_playing_none");
    if (sharedNow && !radioCurrentName) updateSharedNowPlaying(t("music_now_playing_none"), false);
    playPauseBtn.textContent = "▶";
    seekBar.max = "0";
    seekBar.value = "0";
    posLabel.textContent = "0:00";
    durLabel.textContent = "0:00";
    return;
  }

  musicHasLoadedTrack = true;
  musicCurrentName = res.name;
  if (res.name) musicLastPlayedName = res.name;
  nowPlaying.textContent = res.name || "";
  if (sharedNow) updateSharedNowPlaying(res.name || "", false);
  playPauseBtn.textContent = res.playing ? "⏸" : "▶";
  playPauseBtn.title = res.playing ? t("music_pause_btn_title") : t("music_play_btn_title");

  if (res.durationMs > 0) {
    seekBar.max = String(res.durationMs);
    durLabel.textContent = musicFormatTime(res.durationMs);
  }
  if (!musicSeekDragging) {
    seekBar.value = String(res.positionMs);
    posLabel.textContent = musicFormatTime(res.positionMs);
  }
  musicRenderList();
}

// ---------------- equalizer ----------------

function musicRefreshEqPresets() {
  const container = document.getElementById("musicEqPresetContainer");
  if (!container) return;
  api("audio/local_music/eq/presets").then(function (res) {
    if (!res.ok) return;
    musicEqPresets = res.presets || [];
    musicRenderEqPresets(res.current);
    if (res.unavailable) {
      const p = document.createElement("p");
      p.className = "hint";
      p.textContent = t("music_eq_unavailable");
      container.parentElement.appendChild(p);
    }
  });
}

function musicRenderEqPresets(currentIndex) {
  const container = document.getElementById("musicEqPresetContainer");
  if (!container) return;
  container.innerHTML = "";

  // "無 (Flat)" - 對應 index -1, 唔套用任何 preset (見 MainActivity.java
  // musicEqPresetIndex 嘅預設值/javadoc)。
  const noneBtn = document.createElement("button");
  noneBtn.className = "secondary" + (currentIndex === -1 || currentIndex == null ? " active" : "");
  noneBtn.textContent = t("music_eq_none");
  noneBtn.onclick = function () { musicSetEqPreset(-1); };
  container.appendChild(noneBtn);

  musicEqPresets.forEach(function (preset) {
    const btn = document.createElement("button");
    btn.className = "secondary" + (preset.index === currentIndex ? " active" : "");
    btn.textContent = preset.name;
    btn.onclick = function () { musicSetEqPreset(preset.index); };
    container.appendChild(btn);
  });
}

function musicSetEqPreset(index) {
  api("audio/local_music/eq/set", { index: String(index) }).then(function (res) {
    if (!res.ok) return;
    musicRenderEqPresets(index);
  });
}

// ---------------- random filler action toggle ----------------

function musicRefreshFillerToggle() {
  api("audio/local_music/filler_action/get").then(function (res) {
    if (!res.ok) return;
    musicApplyFillerToggleUi(res.enabled);
  });
}

function musicSetFillerActionEnabled(enabled) {
  api("audio/local_music/filler_action/set", { enabled: enabled ? "true" : "false" }).then(function (res) {
    if (!res.ok) return;
    musicApplyFillerToggleUi(res.enabled);
  });
}

function musicApplyFillerToggleUi(enabled) {
  const checkbox = document.getElementById("musicFillerToggle");
  const label = document.getElementById("musicFillerStateLabel");
  if (checkbox) checkbox.checked = !!enabled;
  if (label) label.textContent = enabled ? t("music_filler_on") : t("music_filler_off");
}

// ---------------- drag & drop import ----------------

function musicOnDragOver(evt) {
  evt.preventDefault();
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.add("dragover");
}

function musicOnDragLeave(evt) {
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.remove("dragover");
}

function musicOnDrop(evt) {
  evt.preventDefault();
  const zone = document.getElementById("musicDropZone");
  if (zone) zone.classList.remove("dragover");
  const files = evt.dataTransfer && evt.dataTransfer.files;
  if (files && files.length > 0) musicUploadFiles(files);
}

function musicOnFileInputChange(files) {
  if (files && files.length > 0) musicUploadFiles(files);
  // 清返 input 個 value, 等用戶下次揀返同一個檔名都會再 fire change 事件
  document.getElementById("musicFileInput").value = "";
}

/** 逐個上載 (唔平行) - 每次上載都係一整條 HTTP POST body (成個檔案嘅內容),
 *  平行推多個大檔上載對呢部機 (RK3288, ARMv7, 1.1.7.3.20) 嘅記憶體/網絡都
 *  無謂咁大壓力, 逐個嚟簡單又夠用。*/
function musicUploadFiles(fileList) {
  const files = Array.prototype.slice.call(fileList);
  const statusEl = document.getElementById("musicUploadStatus");
  let index = 0;

  function uploadNext() {
    if (index >= files.length) {
      if (statusEl) statusEl.textContent = t("music_upload_done");
      musicRefreshList();
      return;
    }
    const file = files[index];
    index++;
    if (statusEl) {
      statusEl.textContent = t("music_upload_uploading") + " (" + index + "/" + files.length + ") " + file.name;
    }
    clearError();
    fetch("/upload/music?" + new URLSearchParams({ name: file.name }).toString(), {
      method: "POST",
      body: file,
    }).then(function (res) {
      return res.json().catch(function () {
        return { ok: false, error: "invalid response (status " + res.status + ")" };
      });
    }).then(function (json) {
      if (!json.ok) {
        showError("Upload " + file.name, new Error(json.error || "upload failed"));
      }
      uploadNext();
    }).catch(function (networkErr) {
      showError("Network error uploading " + file.name, networkErr);
      if (statusEl) statusEl.textContent = t("music_upload_failed") + ": " + file.name;
      uploadNext();
    });
  }

  uploadNext();
}
