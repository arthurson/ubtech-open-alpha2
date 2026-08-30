// Open Alpha2 — client logic (app-radio.js)
// 網絡電台 tab: 對接 MainActivity.java 已有嘅 "audio/radio/*" 呢一套 endpoint
// (本身經 Radio Browser API radio-browser.info 動態搜全世界公開電台, 見
// MainActivity.java searchRadioStations()/resolveRadioStation() javadoc)。
// 真正串流 (MediaPlayer setDataSource url_resolved, STREAM_MUSIC) 喺 server 端做,
// 呢個檔案純粹係 UI — 搜尋、列表、播放/停止、狀態顯示。
// 全部函數共用 window/global scope (冇用 ES module), load 順序見 index.html.

let radioStations = [];          // 上次 radioSearch() 攞返嚟嘅清單 {name, country}
let radioCurrentName = null;     // 目前播放緊嘅電台名 (由 status 或 play 回來)
let radioCurrentId = null;

function radioInit() {
  radioRefreshStatus();
  // 共用頻譜/音量/EQ/隨機動作已在 shared 卡，無需額外初始化；狀態由 radioRefreshStatus 同步至 sharedNowPlaying
}

function radioSearch() {
  const input = document.getElementById("radioSearchInput");
  const container = document.getElementById("radioListContainer");
  const statusEl = document.getElementById("radioSearchStatus");
  if (!input || !container) return;
  const q = (input.value || "").trim();
  if (!q) {
    if (statusEl) statusEl.textContent = t("radio_search_empty_hint");
    return;
  }
  container.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "hint";
  loading.textContent = t("radio_search_loading");
  container.appendChild(loading);
  if (statusEl) statusEl.textContent = "";
  clearError();
  api("audio/radio/search", { query: q }).then(function (res) {
    if (res.ok) {
      radioStations = res.stations || [];
      if (statusEl) {
        statusEl.textContent = radioStations.length === 0
          ? t("radio_search_no_result")
          : t("radio_search_found_prefix") + radioStations.length + t("radio_search_found_suffix");
      }
      radioRenderList();
      return;
    }
    // 後端搜尋失敗（多數係機械人無外網/DNS 解析唔到 de1.api.radio-browser.info，見 MainActivity.java:4385 註解）
    // → 前端直接 fetch radio-browser.info 試下（用瀏覽器本身嘅網絡，唔經機械人）
    radioSearchFrontendFallback(q);
  }).catch(function () {
    radioSearchFrontendFallback(q);
  });
}

// 前端直連 fallback — 用瀏覽器網絡直接問 radio-browser.info，繞過機械人 DNS/無外網問題
function radioSearchFrontendFallback(q) {
  const container = document.getElementById("radioListContainer");
  const statusEl = document.getElementById("radioSearchStatus");
  // 依次嘗試幾個 mirror，de1 → all → de2
  const mirrors = [
    "https://de1.api.radio-browser.info",
    "https://all.api.radio-browser.info",
    "https://de2.api.radio-browser.info"
  ];
  let mirrorIdx = 0;

  function tryNextMirror() {
    if (mirrorIdx >= mirrors.length) {
      if (statusEl) statusEl.textContent = t("radio_search_no_result") + "（機械人無外網，前端亦無法連接 radio-browser.info）";
      if (container) {
        container.innerHTML = "";
        const p = document.createElement("p");
        p.className = "hint";
        p.textContent = "⚠️ 機械人目前無法上網（DNS 失敗），請檢查 WiFi 是否有互聯網。已嘗試瀏覽器直連亦失敗。";
        container.appendChild(p);
      }
      return;
    }
    const base = mirrors[mirrorIdx++];
    const url = base + "/json/stations/search?name=" + encodeURIComponent(q) + "&order=random&reverse=true&hidebroken=true&limit=30";
    fetch(url).then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    }).then(function (arr) {
      // 過濾 hls，優先非 https（跟後端一致）
      const filtered = (arr || []).filter(function (s) { return s.hls !== 1; });
      const list = filtered.length > 0 ? filtered : (arr || []);
      radioStations = list.map(function (s) {
        return {
          name: s.name || "",
          country: s.country || "",
          url_resolved: s.url_resolved || s.url || "",
          url: s.url || "",
          stationuuid: s.stationuuid || "",
          hls: s.hls || 0
        };
      });
      if (statusEl) {
        statusEl.textContent = radioStations.length === 0
          ? t("radio_search_no_result")
          : t("radio_search_found_prefix") + radioStations.length + t("radio_search_found_suffix") + "（經瀏覽器直連）";
      }
      radioRenderList();
    }).catch(function (err) {
      // 試下一個 mirror
      tryNextMirror();
    });
  }
  tryNextMirror();
}

function radioQuickSearch(term) {
  const input = document.getElementById("radioSearchInput");
  if (input) input.value = term;
  radioSearch();
}

function radioRenderList() {
  const container = document.getElementById("radioListContainer");
  if (!container) return;
  container.innerHTML = "";
  if (radioStations.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("radio_list_empty_hint");
    container.appendChild(p);
    return;
  }
  radioStations.forEach(function (st) {
    const row = document.createElement("div");
    row.className = "radio-row" + (st.name === radioCurrentName ? " active" : "");
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = st.name;
    nameSpan.title = st.name;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = st.country || "";
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = st.name === radioCurrentName ? "🔊" : "▶";
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.onclick = function () { radioPlay(st.name); };
    container.appendChild(row);
  });
}

function radioPlay(name) {
  const targetName = name || (document.getElementById("radioSearchInput") || {}).value;
  if (!targetName || !targetName.trim()) return;
  const cleanName = targetName.trim();
  clearError();
  const statusEl = document.getElementById("radioSearchStatus");
  if (statusEl) statusEl.textContent = t("radio_playing_prefix") + cleanName + "…";
  if (typeof sharedActiveSource !== "undefined") sharedActiveSource = "radio";
  if (typeof musicCurrentName !== "undefined") { musicCurrentName = null; }
  if (typeof musicHasLoadedTrack !== "undefined") musicHasLoadedTrack = false;
  if (typeof musicRenderList === "function") musicRenderList();
  if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(cleanName, true);
  if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();

  let frontendStation = null;
  for (let i = 0; i < radioStations.length; i++) {
    if (radioStations[i].name === cleanName && radioStations[i].url_resolved) {
      frontendStation = radioStations[i];
      break;
    }
  }
  if (frontendStation && frontendStation.url_resolved) {
    api("audio/radio/play_url", { url: frontendStation.url_resolved, name: frontendStation.name }).then(function (res) {
      if (res.ok) {
        radioCurrentName = res.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
        return;
      }
      api("audio/radio/play", { name: cleanName }).then(function (res2) {
        if (!res2.ok) return;
        radioCurrentName = res2.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res2.playing || cleanName);
      });
    });
    return;
  }

  api("audio/radio/play", { name: cleanName }).then(function (res) {
    if (!res.ok) return;
    radioCurrentName = res.playing || cleanName;
    radioRefreshStatus();
    if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
  });
}

function radioStop() {
  clearError();
  if (typeof sharedActiveSource !== "undefined" && sharedActiveSource === "radio") sharedActiveSource = null;
  api("audio/radio/stop").then(function (res) {
    if (!res.ok) return;
    radioCurrentName = null;
    radioCurrentId = null;
    radioRefreshStatus();
    radioRenderList();
    if (typeof musicRenderList === "function") musicRenderList();
    const statusEl = document.getElementById("radioSearchStatus");
    if (statusEl) statusEl.textContent = t("radio_stopped");
    if (typeof updateSharedNowPlaying === "function" && !musicCurrentName) {
      updateSharedNowPlaying(t("music_now_playing_none"), false);
    }
    // 若本地也沒在播，停頻譜
    api("audio/local_music/status").then(function (r) {
      if (!r.ok || !r.hasTrack) {
        if (typeof musicStopSpectrumLoop === "function") musicStopSpectrumLoop();
      }
    });
  });
}

function radioRefreshStatus() {
  const nowPlaying = document.getElementById("radioNowPlaying");
  const sharedNow = document.getElementById("sharedNowPlaying");
  const playPauseBtn = document.getElementById("musicPlayPauseBtn");
  if (!nowPlaying) return;
  return api("audio/radio/status").then(function (res) {
    if (!res.ok) return;
    if (!res.playing) {
      radioCurrentName = null;
      radioCurrentId = null;
      nowPlaying.textContent = t("radio_now_playing_none");
      nowPlaying.classList.remove("radio-playing");
      if (sharedNow && !musicCurrentName) {
        if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(t("music_now_playing_none"), false);
      }
      if (playPauseBtn && !musicHasLoadedTrack) playPauseBtn.textContent = "▶";
    } else {
      radioCurrentName = res.name || radioCurrentName;
      radioCurrentId = res.id || null;
      nowPlaying.textContent = "🔊 " + (res.name || radioCurrentName || "");
      nowPlaying.classList.add("radio-playing");
      if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(res.name || radioCurrentName || "", true);
      if (playPauseBtn) playPauseBtn.textContent = "⏸";
      if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();
    }
    radioRenderList();
  });
}

function radioCurrentIndex() {
  if (!radioCurrentName) return -1;
  for (let i = 0; i < radioStations.length; i++) {
    if (radioStations[i].name === radioCurrentName) return i;
  }
  return -1;
}
function radioPlayPrev() {
  if (radioStations.length === 0) return;
  const idx = radioCurrentIndex();
  const target = idx <= 0 ? radioStations.length - 1 : idx - 1;
  radioPlay(radioStations[target].name);
}
function radioPlayNext() {
  if (radioStations.length === 0) return;
  const idx = radioCurrentIndex();
  const target = idx < 0 || idx >= radioStations.length - 1 ? 0 : idx + 1;
  radioPlay(radioStations[target].name);
}
function radioPlayRandom() {
  if (radioStations.length === 0) return;
  let idx = Math.floor(Math.random() * radioStations.length);
  if (radioStations.length > 1) {
    while (radioStations[idx].name === radioCurrentName) {
      idx = Math.floor(Math.random() * radioStations.length);
    }
  }
  radioPlay(radioStations[idx].name);
}

function radioOnSearchKeydown(e) {
  if (e.key === "Enter") radioSearch();
}
