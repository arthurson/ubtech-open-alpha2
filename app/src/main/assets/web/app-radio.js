// Open Alpha2 — client logic (app-radio.js)
// 網絡電台 tab: 對接 MainActivity.java 已有的 "audio/radio/*" 這一套 endpoint
// (本身經 Radio Browser API radio-browser.info 動態搜全世界公開電台, 見
// MainActivity.java searchRadioStations()/resolveRadioStation() javadoc)。
// 真正串流 (MediaPlayer setDataSource url_resolved, STREAM_MUSIC) 在 server 端做,
// 這個檔案純粹是 UI — 搜尋、列表、播放/停止、狀態顯示。
// 全部函數共用 window/global scope (沒有用 ES module), load 順序見 index.html.

let radioStations = [];          // 上次 radioSearch() 取回來的清單 {name, country, stationuuid, url, url_resolved}
let radioCurrentName = null;     // 目前正在播放的電台名 (由 status 或 play 回來)
let radioCurrentId = null;
let radioFavorites = [];         // 收藏清單（機身持久化，經 audio/radio/favorites/*），項形狀同 radioStations

function radioInit() {
  radioRefreshStatus();
  radioLoadFavorites();
  // 共用頻譜/音量/EQ/隨機動作已在 shared 卡，無需額外初始化；狀態由 radioRefreshStatus 同步至 sharedNowPlaying
}

// ── 收藏 ─────────────────────────────────────────────
// 後端為準（多瀏覽器共用同一份機身收藏）；本機不另做 localStorage 鏡像
// （連到機械人＝連到後端，離線根本開唔到面板）。
function radioLoadFavorites() {
  if (!Alpha2Api.audioRadioFavoritesList) return;
  Alpha2Api.audioRadioFavoritesList().then(function (res) {
    if (!res || !res.ok) return;
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

function radioIsFavorite(st) {
  if (!st) return false;
  const nm = (st.name || "").trim().toLowerCase();
  const uuid = (st.stationuuid || st.id || "").trim();
  const uuidTemp = uuid.indexOf("frontend-") === 0;
  for (let i = 0; i < radioFavorites.length; i++) {
    const f = radioFavorites[i];
    const fn = (f.name || "").trim().toLowerCase();
    const fu = (f.stationuuid || "").trim();
    const fuTemp = fu.indexOf("frontend-") === 0;
    if (uuid && fu && !uuidTemp && !fuTemp && uuid === fu) return true;
    if (nm && nm === fn) return true;
  }
  return false;
}

function radioToggleFavorite(st, ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  if (!st || !st.name) return;
  if (radioIsFavorite(st)) radioRemoveFavorite(st);
  else radioAddFavorite(st);
}

function radioAddFavorite(st) {
  const params = { name: st.name };
  if (st.country) params.country = st.country;
  if (st.stationuuid) params.stationuuid = st.stationuuid;
  if (st.url) params.url = st.url;
  if (st.url_resolved) params.url_resolved = st.url_resolved;
  Alpha2Api.audioRadioFavoritesAdd(params).then(function (res) {
    if (!res || !res.ok) return;
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

function radioRemoveFavorite(st) {
  const params = {};
  // uuid 夠穩就帶埋（frontend- 臨時 uuid 後端會退回 name 比對，帶咗無妨）。
  if (st.stationuuid) params.stationuuid = st.stationuuid;
  if (st.name) params.name = st.name;
  Alpha2Api.audioRadioFavoritesRemove(params).then(function (res) {
    if (!res || !res.ok) {
      // 後端話搵唔到（例如大小寫／臨時 uuid 唔啱）：照 reload 一次對齊。
      radioLoadFavorites();
      return;
    }
    radioFavorites = res.favorites || [];
    radioRenderFavorites();
    radioRenderList();
  }).catch(function () {});
}

// 收藏直播：有 url_resolved 即經 play_url 免搜尋（機械人無外網搜唔到都播到 http 台）；
// 無位址先退回按名搜播。
function radioPlayFavorite(fav) {
  if (!fav) return;
  const url = fav.url_resolved || fav.url;
  if (url) {
    clearError();
    if (typeof sharedActiveSource !== "undefined") sharedActiveSource = "radio";
    if (typeof updateSharedNowPlaying === "function") updateSharedNowPlaying(fav.name || url, true);
    if (typeof musicStartSpectrumLoop === "function") musicStartSpectrumLoop();
    Alpha2Api.audioRadioPlayUrl({ url: url, name: fav.name || url }).then(function (res) {
      if (!res || !res.ok) {
        radioPlay(fav.name);
        return;
      }
      radioCurrentName = res.playing || fav.name;
      radioRefreshStatus();
    }).catch(function () { radioPlay(fav.name); });
    return;
  }
  radioPlay(fav.name);
}

function radioRenderFavorites() {
  const container = document.getElementById("radioFavContainer");
  const countEl = document.getElementById("radioFavCount");
  if (!container) return;
  container.innerHTML = "";
  if (countEl) {
    countEl.textContent = radioFavorites.length === 0
      ? ""
      : t("radio_search_found_prefix") + radioFavorites.length + t("radio_fav_count_suffix");
  }
  if (radioFavorites.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = t("radio_fav_empty_hint");
    container.appendChild(p);
    return;
  }
  radioFavorites.forEach(function (fav) {
    const row = document.createElement("div");
    row.className = "radio-row" + (fav.name === radioCurrentName ? " active" : "");
    const playIcon = document.createElement("span");
    playIcon.className = "radio-play-icon";
    playIcon.textContent = fav.name === radioCurrentName ? "🔊" : "▶";
    const nameSpan = document.createElement("span");
    nameSpan.className = "radio-name";
    nameSpan.textContent = fav.name;
    nameSpan.title = fav.name;
    const countrySpan = document.createElement("span");
    countrySpan.className = "radio-country";
    countrySpan.textContent = fav.country || "";
    const rmBtn = document.createElement("button");
    rmBtn.className = "radio-fav-btn radio-fav-remove";
    rmBtn.textContent = "✕";
    rmBtn.title = t("radio_fav_remove_btn");
    rmBtn.onclick = function (ev) { radioRemoveFavorite(fav); if (ev && ev.stopPropagation) ev.stopPropagation(); };
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.appendChild(rmBtn);
    row.onclick = function () { radioPlayFavorite(fav); };
    container.appendChild(row);
  });
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
  Alpha2Api.audioRadioSearch( { query: q }).then(function (res) {
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
    // 後端搜尋失敗（多數是機械人無外網/DNS 解析不到 de1.api.radio-browser.info，見 MainActivity.java:4385 註解）
    // → 前端直接 fetch radio-browser.info 試試（用瀏覽器本身的網絡，不經機械人）
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
      if (statusEl) statusEl.textContent = t("radio_search_no_result") + t("radio_no_internet_suffix");
      if (container) {
        container.innerHTML = "";
        const p = document.createElement("p");
        p.className = "hint";
        p.textContent = t("radio_dns_fail");
        container.appendChild(p);
      }
      return;
    }
    const base = mirrors[mirrorIdx++];
    const url = base + "/json/stations/search?name=" + encodeURIComponent(q) + "&order=random&reverse=true&hidebroken=true&limit=50";
    // 對外留痕（同後端 NetLog 同形）：瀏覽器直連哪個 mirror，event log 看到。
    if (typeof appendLog === "function" && typeof nowTimeStr === "function") {
      appendLog({ type: "net_connect", time: nowTimeStr(), data: { purpose: "radio-search", url: base } });
    }
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
      // 試試一個 mirror
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
    const favBtn = document.createElement("button");
    const isFav = radioIsFavorite(st);
    favBtn.className = "radio-fav-btn" + (isFav ? " active" : "");
    favBtn.textContent = isFav ? "★" : "☆";
    favBtn.title = isFav ? t("radio_fav_remove_btn") : t("radio_fav_add_btn");
    favBtn.onclick = function (ev) { radioToggleFavorite(st, ev); };
    row.appendChild(playIcon);
    row.appendChild(nameSpan);
    row.appendChild(countrySpan);
    row.appendChild(favBtn);
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
    Alpha2Api.audioRadioPlayUrl( { url: frontendStation.url_resolved, name: frontendStation.name }).then(function (res) {
      if (res.ok) {
        radioCurrentName = res.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
        return;
      }
      Alpha2Api.audioRadioPlay( { name: cleanName }).then(function (res2) {
        if (!res2.ok) return;
        radioCurrentName = res2.playing || cleanName;
        radioRefreshStatus();
        if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res2.playing || cleanName);
      });
    });
    return;
  }

  Alpha2Api.audioRadioPlay( { name: cleanName }).then(function (res) {
    if (!res.ok) return;
    radioCurrentName = res.playing || cleanName;
    radioRefreshStatus();
    if (statusEl) statusEl.textContent = t("radio_play_ok_prefix") + (res.playing || cleanName);
  });
}

function radioStop() {
  clearError();
  if (typeof sharedActiveSource !== "undefined" && sharedActiveSource === "radio") sharedActiveSource = null;
  Alpha2Api.audioRadioStop().then(function (res) {
    if (!res.ok) return;
    radioCurrentName = null;
    radioCurrentId = null;
    radioRefreshStatus();
    radioRenderList();
    if (typeof radioRenderFavorites === "function") radioRenderFavorites();
    if (typeof musicRenderList === "function") musicRenderList();
    const statusEl = document.getElementById("radioSearchStatus");
    if (statusEl) statusEl.textContent = t("radio_stopped");
    if (typeof updateSharedNowPlaying === "function" && !musicCurrentName) {
      updateSharedNowPlaying(t("music_now_playing_none"), false);
    }
    // 若本地也沒在播，停頻譜
    Alpha2Api.audioLocalMusicStatus().then(function (r) {
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
  return Alpha2Api.audioRadioStatus().then(function (res) {
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
    if (typeof radioRenderFavorites === "function") radioRenderFavorites();
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

