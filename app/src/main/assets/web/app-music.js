// Open Alpha2 — client logic (app-music.js)
// 本地音樂 tab: 對接 MainActivity.java 已有的 "audio/local_music/*" 這一套
// endpoint (本身給小智語音/AI tool call 用, 現在加回一層瀏覽器 UI)。真正播放
// (STREAM_MUSIC MediaPlayer)、隨機動作全部在 server 端做, 這個檔案
// 純粹是 UI + 定時 poll 狀態來更新進度條, 沒有任何音訊 byte 經過瀏覽器 (同
// app-mic.js 那種即時串流完全不同)。
// 全部函數共用 window/global scope (沒有用 ES module), load 順序見 index.html
// 的 <script src="..."> 排列 - 要在 app-core.js (api()/t() 這些 helper) 之後。

// ---------------- state ----------------

let musicTracks = [];            // 上次 musicRefreshList() 取回來的清單
let musicCurrentName = null;     // 目前揀選/正在播放那首歌的檔名
let musicStatusPollTimer = null;
let musicSeekDragging = false;   // 用戶正在拖進度條當時, 不要給 poll 覆蓋個位置
let musicPlayAllMode = false;    // 「▶ 全部」模式 - 一首播完自動接落一首 (見
                                 // musicPollStatusLoop() 的 hasTrack=false 分支)
let musicLastPlayedName = null;  // 上次播過的歌名 - stop 當時 musicCurrentName 會
                                 // 清除, 但之後按「▶」應該重播剛才那首, 不是
                                 // 沒有反應
let musicHasLoadedTrack = false; // server 端 currentMusicPlayer 還 正在load東西嗎 -
                                 // 正在暫停都是 true; 全部停止/播完先變 false。
                                 // 給 musicTogglePlayPause() 分「resume」還是
                                 // 「由頭 play」用
let musicSpectrumTimer = null;   // spectrum 輪詢 timer (setTimeout 鏈)
let musicSpectrumAnimTimer = null; // 平滑動畫 timer (~33ms 重畫)
let musicSpectrumTargets = [];   // 最近一次 server 取回來的目標值
let musicSpectrumSmooth = [];    // 平滑化後用來畫的值
let sharedActiveSource = null;   // "local" 或 "radio"，記錄最後一次播放來源，用於共用上一首/下一首/隨機分流

// ---------------- disco LED ----------------
// 🪩 Disco：播本地音樂當時，眼/頭 7 色 LED 跟住頻譜能量轉色。
// 獨立開關（同隨機動作一樣，localStorage 本機記住），前端跑：
// 頻譜幀（~33ms）計低/高頻能量，onset（能量爆發）先轉色，兼做 450ms
// 節流，唔會洗版後端。低音勁行暖色（紅/黃/紫），高音勁行冷色
// （藍/青/綠/白），頭同眼用差 3 格嘅對比色。
// 熄開關／停歌就停手，燈留喺最後隻色（唔自動還原，用 LED 頁再較）。
let musicDiscoEnabled = false;
let musicDiscoPrevLevel = 0;
let musicDiscoBeatCount = 0;
let musicDiscoLastLedAt = 0;
const DISCO_SILENCE_THR = 22;   // 整體能量低過呢個當靜音，唔轉色
const DISCO_BEAT_THR = 60;      // 大聲就當一拍
const DISCO_ONSET_DELTA = 12;   // 細聲時要能量突然爆發先當一拍
const DISCO_MIN_INTERVAL_MS = 300; // 兩次轉色最密間隔
const DISCO_WARM = [1, 4, 5];   // 紅 黃 紫
const DISCO_COLD = [3, 6, 2, 7]; // 藍 青 綠 白
const DISCO_ALL = [1, 2, 3, 4, 5, 6, 7];

// ---------------- audio spectrum ----------------
// server 端 Visualizer FFT -> audio/local_music/spectrum 每條
// band 一個 0-255 值。輪詢 100ms 更新目標值, 另外有條 ~33ms 的動畫 timer 用
// 「快上慢落」(attack 即刻, release 指數衰減) 插值, bar 才會順滑不會一跳一跳。

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
  musicDrawSpectrum(null); // 收工重畫全平
}

function musicSpectrumLoop() {
  Alpha2Api.audioLocalMusicSpectrum().then(function (res) {
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
    // attack: 目標高過現值即刻跟上 (不會滯後); release: 每幀衰減 18%,
    // 跌下來順滑自然, 不會彈吓彈吓。
    musicSpectrumSmooth[i] = target >= prev ? target : Math.max(target, prev * 0.82);
  }
  musicDrawSpectrum(musicSpectrumSmooth);
  musicDiscoTick(musicSpectrumSmooth);
}

/** Disco 拍點：由平滑後頻譜揀色，夠格先送 LED（節流＋靜音閘內置）。 */
function musicDiscoTick(bands) {
  if (!musicDiscoEnabled || !bands || bands.length === 0) return;
  // 淨係本地音樂播緊先閃：電台／暫停／停咗就唔好亂閃。
  if (typeof isRadioActive === "function" && isRadioActive()) return;
  const btn = document.getElementById("musicPlayPauseBtn");
  if (!btn || btn.textContent.trim() !== "⏸") return;
  const n = bands.length;
  const third = Math.max(1, Math.floor(n / 3));
  let bass = 0, mid = 0, treb = 0;
  for (let i = 0; i < n; i++) {
    const v = bands[i] || 0;
    if (i < third) bass += v;
    else if (i < third * 2) mid += v;
    else treb += v;
  }
  bass /= third;
  mid /= third;
  treb /= Math.max(1, n - third * 2);
  const level = Math.max(bass, mid, treb);
  const delta = level - musicDiscoPrevLevel;
  musicDiscoPrevLevel = level;
  if (level < DISCO_SILENCE_THR) return; // 靜音唔轉
  if (level < DISCO_BEAT_THR && delta < DISCO_ONSET_DELTA) return; // 未起拍唔轉
  const now = Date.now();
  if (now - musicDiscoLastLedAt < DISCO_MIN_INTERVAL_MS) return; // 節流
  // 能量揀色：低音勁暖色，高音勁冷色，唔係就全盤輪。
  let pool;
  if (bass >= treb && bass >= mid) pool = DISCO_WARM;
  else if (treb >= bass && treb >= mid) pool = DISCO_COLD;
  else pool = DISCO_ALL;
  const pick = pool[musicDiscoBeatCount % pool.length];
  musicDiscoBeatCount++;
  musicDiscoLastLedAt = now;
  // 頭眼對比色（差 3 格），先有 disco 味。
  const eye = ((pick - 1 + 3) % 7) + 1;
  const headB = document.getElementById("headBrightness");
  const eyeB = document.getElementById("eyeBrightness");
  const hb = headB ? headB.value : 9;
  const eb = eyeB ? eyeB.value : 9;
  try {
    Alpha2Api.ledHeadSet({ preset: "long", color: pick, brightness: hb }).catch(function () {});
    Alpha2Api.ledEyeSet({ preset: "long", color: eye, brightness: eb }).catch(function () {});
  } catch (e) {}
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
    // 由綠到紅的漸變 (低頻綠、高頻紅), 頂部加少少亮色。
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
  musicRefreshFillerToggle();
  musicRefreshDiscoToggle();
  refreshSharedVolume();
  // 頻譜共用：無論本地或電台，同一 canvas 同一輪詢
  // 若本地有播放即啟動，否則由 radioInit 觸發
}

// ---------------- track list ----------------

function musicRefreshList() {
  const container = document.getElementById("musicListContainer");
  if (!container) return Promise.resolve();
  container.textContent = t("music_list_loading");
  return Alpha2Api.audioLocalMusicList().then(function (res) {
    if (!res.ok) return;
    // 清單直接來自 server 端 listLocalMusicFiles() 單一 LOCAL_MUSIC_DIR；這裡多一層去重 (跟檔名) 保險，不應該實際命中。
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

    // 成行 click 就播 (user-select:none + cursor:pointer 在 style.css 度)。
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
  Alpha2Api.audioLocalMusicPlay( { name: name }).then(function (res) {
    if (!res.ok) return;
    musicRefreshStatus();
    musicStartStatusPolling();
  });
}

// ---------------- prev / next / random / play-all ----------------
// 全部 client 端排歌 — server audio/local_music/* 沒有 playlist 概念，僅「播這個檔」。這幾個 function 在 musicTracks 計下一首，再 call musicPlay()。

/** 目前正在播那首在 musicTracks 裡面的 index, 找不到 (清單變了/沒有播) 回 -1。 */
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

/** 「▶ 全部」模式底下找下一首 - 由現在那首開始向後找, 到了最尾繞回頭。 */
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
    Alpha2Api.audioLocalMusicPause().then(musicRefreshStatus);
  } else if (musicCurrentName || musicLastPlayedName) {
    // 有 track load 了 (就算停了機都未 release, 例如正在暫停/播完) → resume;
    // 真正沒有 → fallback 由頭播上次那首。
    const hasLoadedTrack = musicHasLoadedTrack;
    const name = musicCurrentName || musicLastPlayedName;
    if (hasLoadedTrack) {
      Alpha2Api.audioLocalMusicResume().then(function (res) {
        if (res.ok && res.hasTrack !== false) {
          musicRefreshStatus();
          return;
        }
        musicPlay(name); // resume 失敗 (例如已經被 stop 清除) - 由頭播過
      });
    } else {
      musicPlay(name);
    }
  } else if (musicTracks.length > 0) {
    musicPlay(musicTracks[0].name);
  }
}

/**
 * 音樂 tab「⏹ 全部停止」— 同語音 tab 總停鍵
 * (xiaozhiStopAll(), 見 app-xiaozhi.js) 看齊: action/stop + speech/stop +
 * audio/local_music/stop + audio/radio/stop 四樣一齊停, 另加上自己個
 * 「▶ 全部」自動接歌模式。直接重用 xiaozhiStopAll() 不另寫一套, 保證兩邊
 * 行為永遠一致; 它裡面 Promise.all 已經包了單一 endpoint 失敗不影響其餘。
 */
function musicStopAll() {
  musicPlayAllMode = false;
  musicHasLoadedTrack = false;
  sharedActiveSource = null;
  xiaozhiResetTtsQueue();
  Promise.all([
    Alpha2Api.actionStop(),
    Alpha2Api.speechStop(),
    Alpha2Api.audioLocalMusicStop(),
    Alpha2Api.audioRadioStop(),
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
  Alpha2Api.audioLocalMusicSeek( { ms: String(Math.round(Number(value))) }).then(function () {
    musicSeekDragging = false;
    musicRefreshStatus();
  });
}

// 共用音量（系統 STREAM_MUSIC，同時影響本地與電台）— 前端共用滑桿
function onSharedVolumeInput(value) {
  const valEl = document.getElementById("sharedVolumeVal");
  if (valEl) valEl.textContent = value;
}
function setSharedVolume(value) {
  const v = Math.max(0, Math.min(15, parseInt(value, 10) || 0));
  Alpha2Api.audioVolumeSet( { level: String(v) }).then(function (res) {
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
  Alpha2Api.audioLocalMusicVolume( { percent: "100" });
}
function refreshSharedVolume() {
  Alpha2Api.audioVolumeGet().then(function (res) {
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
// request 拿到結果先至排下一次, 網絡慢當時不會越疊越多。僅在實際有東西
// 正在播 (hasTrack=true) 先繼續 poll。
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
  Alpha2Api.audioLocalMusicStatus().then(function (res) {
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
      // 共用頻譜：本地停了但電台還正在播，保留頻譜
      if (!radioCurrentName) {
        musicStopSpectrumLoop();
      }
    }
  });
}

function musicRefreshStatus() {
  return Alpha2Api.audioLocalMusicStatus().then(function (res) {
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

// ---------------- random filler action toggle ----------------

function musicRefreshFillerToggle() {
  Alpha2Api.audioLocalMusicFillerActionGet().then(function (res) {
    if (!res.ok) return;
    musicApplyFillerToggleUi(res.enabled);
  });
}

function musicSetFillerActionEnabled(enabled) {
  Alpha2Api.audioLocalMusicFillerActionSet( { enabled: enabled ? "true" : "false" }).then(function (res) {
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

// ---------------- disco LED toggle ----------------
// 同隨機動作一樣獨立開關，不過只存本瀏覽器 localStorage（前端跑，
// 後端唔使加嘢）。預設關，開咗播本地音樂先會閃。

function musicDiscoGet(def) {
  try {
    const v = localStorage.getItem("musicDisco");
    if (v === null || v === undefined) return (def === undefined) ? false : !!def;
    return v === "1";
  } catch (e) {
    return (def === undefined) ? false : !!def;
  }
}

function musicRefreshDiscoToggle() {
  musicDiscoEnabled = musicDiscoGet(false);
  musicApplyDiscoToggleUi(musicDiscoEnabled);
}

function musicSetDiscoEnabled(enabled) {
  musicDiscoEnabled = !!enabled;
  try {
    localStorage.setItem("musicDisco", musicDiscoEnabled ? "1" : "0");
  } catch (e) {}
  musicApplyDiscoToggleUi(musicDiscoEnabled);
}

function musicApplyDiscoToggleUi(enabled) {
  const checkbox = document.getElementById("musicDiscoToggle");
  const label = document.getElementById("musicDiscoStateLabel");
  if (checkbox) checkbox.checked = !!enabled;
  if (label) label.textContent = enabled ? t("music_disco_on") : t("music_disco_off");
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
  // 清除 input 個 value, 等用戶下次選回同一個檔名都會再 fire change 事件
  document.getElementById("musicFileInput").value = "";
}

/** 逐個上載 (不平行) - 每次上載都是一整條 HTTP POST body (整個檔案的內容),
 *  平行推多個大檔上載對這部機 (RK3288, ARMv7, 1.1.7.3.20) 的記憶體/網絡都
 *  無謂這麼大壓力, 逐個來簡單又夠用。*/
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
    // 面板 token：啟用中 /upload/music 要驗 panel_token（見 PanelAuth，整個面板上鎖），
    // 同 api() 系列一樣由 localStorage 拿（withPanelToken，全局，見 app-core.js）。
    const q = (typeof withPanelToken === "function") ? withPanelToken({ name: file.name }) : { name: file.name };
    fetch("/upload/music?" + new URLSearchParams(q).toString(), {
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

