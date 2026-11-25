// Open Alpha2 — client logic (app-camera.js)
// 內容: 相機直播、影相、錄影、拖拽準星頭部瞄準。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。


// ---------------- Camera: MJPEG live stream ----------------
//
// The server keeps the camera open continuously (CameraController) and pushes preview
// frames out as multipart/x-mixed-replace MJPEG at GET /stream/camera - a browser
// <img> tag renders that natively as a live feed with no JS polling loop needed, at
// whatever frame rate the camera driver actually delivers (real preview frames, not a
// capture()/release() cycle per frame, so this reaches the driver's native ~30fps
// instead of being capped by an open/close round trip).
//
// Reconnection relies solely on the <img>'s onerror event, which the browser does fire
// when a multipart connection actually breaks (server restarted, camera error, network
// drop). There is deliberately no separate "stall" timer here: onload only fires once,
// for the initial connection, and is NOT re-fired per MJPEG part in any mainstream
// browser - a timer built on "time since last onload" would flag every healthy,
// still-streaming connection as stalled a few seconds in, and force a reconnect that
// re-opens the camera each time, capping the effective frame rate right back down to
// what a full open/close cycle costs.

let cameraLiveRunning = false;
let fpsPollTimer = null;
// webcam 數位變焦 x1-x5
let cameraZoom = 1.0;
let cameraZoomHardwareSupported = false;

function cameraElements() {
  return {
    viewport: document.getElementById("cameraViewport"),
    placeholder: document.getElementById("cameraPlaceholder"),
    badge: document.getElementById("cameraLiveBadge"),
    btn: document.getElementById("cameraToggleBtn"),
    hint: document.getElementById("cameraStatusHint"),
    resolution: document.getElementById("cameraResolution"),
    crosshairPad: document.getElementById("crosshairPad"),
    crosshairMark: document.getElementById("crosshairMark"),
    // "featureEnabled" is the single master checkbox that now gates both
    // overlay features together (head-aim joystick pad, mic-listen headphone FAB)
    // - kept under the name crosshairToggle here since all the existing
    // crosshair drag-to-aim code below already reads els.crosshairToggle.
    crosshairToggle: document.getElementById("featureEnabled"),
    fabRow: document.getElementById("fabRow"),
    micListenFab: document.getElementById("micListenFab"),
  };
}

/** Fired when the resolution dropdown changes. If the stream is already running,
 *  restarts it so the new size actually takes effect (Camera can't resize mid-stream).
 *  If not running, there's nothing to do yet - startCameraLive() reads the dropdown's
 *  current value when the user next opens the camera. */
function onResolutionChanged() {
  if (cameraLiveRunning) {
    stopCameraLive();
    startCameraLive();
  }
}

// ── Camera Zoom x1-x5 ──
function cameraZoomElements() {
  return {
    slider: document.getElementById("cameraZoom"),
    val: document.getElementById("cameraZoomVal"),
    hint: document.getElementById("cameraZoomHint"),
  };
}
function applyCameraZoomCss(zoom) {
  const els = cameraElements();
  const img = els.viewport ? els.viewport.querySelector("img") : null;
  if (img) {
    const z = Math.max(1.0, Math.min(5.0, parseFloat(zoom) || 1.0));
    img.style.transform = z > 1.01 ? "scale(" + z.toFixed(1) + ")" : "";
    img.style.transformOrigin = "center center";
    // 讓 viewport 保持 overflow:hidden 以裁切放大後的邊緣（見 style.css .camera-viewport）
    img.style.willChange = z > 1.01 ? "transform" : "";
  }
  const els2 = cameraZoomElements();
  if (els2.val) els2.val.textContent = "x" + (parseFloat(zoom)||1.0).toFixed(1);
  if (els2.slider && Math.abs(parseFloat(els2.slider.value) - parseFloat(zoom)) > 0.05) els2.slider.value = zoom;
}
function onCameraZoomInput(val) {
  const z = parseFloat(val) || 1.0;
  cameraZoom = z;
  applyCameraZoomCss(z);
}
async function setCameraZoom(val) {
  const z = Math.max(1.0, Math.min(5.0, parseFloat(val) || 1.0));
  cameraZoom = z;
  applyCameraZoomCss(z);
  try { localStorage.setItem("cameraZoom", String(z)); } catch(e){}
  try {
    const res = await Alpha2Api.cameraZoom({zoom: z});
    if (res && typeof res.hardwareSupported === "boolean") {
      cameraZoomHardwareSupported = res.hardwareSupported;
      const hint = document.getElementById("cameraZoomHint");
      if (hint) {
        const ratio = res.ratios ? res.ratios[Math.min(Math.round((z-1)/4*(res.ratios.length-1)), res.ratios.length-1)] : "?";
        hint.textContent = res.hardwareSupported
          ? t("camera_zoom_hw", { ratio: ratio })
          : t("camera_zoom_sw", { z: z.toFixed(1) });
      }
    }
  } catch(e) {
    // 後端未就緒時僅前端生效，不報錯
  }
}
async function initCameraZoom() {
  let initZ = 1.0;
  try { const s = localStorage.getItem("cameraZoom"); if (s) initZ = parseFloat(s) || 1.0; } catch(e){}
  try {
    const info = await Alpha2Api.cameraZoom({});
    if (info && typeof info.zoom === "number") {
      initZ = info.zoom;
      cameraZoomHardwareSupported = !!info.hardwareSupported;
    } else if (info && typeof info.zoom === "string") {
      initZ = parseFloat(info.zoom) || initZ;
    }
  } catch(e){}
  cameraZoom = Math.max(1.0, Math.min(5.0, initZ));
  const els = cameraZoomElements();
  if (els.slider) els.slider.value = String(cameraZoom);
  applyCameraZoomCss(cameraZoom);
}

function toggleCameraLive() {
  if (cameraLiveRunning) {
    stopCameraLive();
  } else {
    startCameraLive();
  }
}

/** Applies the dropdown's selected "WxH" resolution server-side, then (re)starts the
 *  stream. Camera can't change preview size mid-stream, so this always goes through
 *  stop -> set resolution -> start, even if a stream is already running. */
async function startCameraLive() {
  const els = cameraElements();
  const placeholder = els.placeholder, badge = els.badge, btn = els.btn, hint = els.hint;

  const [w, h] = (els.resolution ? els.resolution.value : "800x600").split("x").map(Number);
  if (hint) hint.textContent = t("cam_set_res");
  try { await Alpha2Api.cameraResolution( { w: w, h: h }); } catch(e) { if (hint) hint.textContent = t("cam_set_res_fail", { msg: e.message }); return; }

  cameraLiveRunning = true;
  if (btn) btn.textContent = "⏸";
  if (badge) badge.classList.add("on");
  if (placeholder) placeholder.style.display = "none";
  if (hint) hint.textContent = t("cam_connecting");

  connectCameraStream();
  setupCrosshairIfNeeded();
  updateCrosshairVisibility();
  startFpsPolling();
}

function stopCameraLive() {
  const els = cameraElements();
  const badge = els.badge, btn = els.btn, placeholder = els.placeholder, viewport = els.viewport;
  cameraLiveRunning = false;
  if (mediaRecorder && mediaRecorder.state === "recording") {
    stopRecording(); // avoid recording against an <img> that's about to be removed
  }
  const img = viewport ? viewport.querySelector("img") : null;
  if (img) {
    img.src = ""; // stop the browser holding the multipart connection open
    img.remove();
  }
  if (btn) btn.textContent = "▶";
  if (badge) badge.classList.remove("on");
  if (placeholder) {
    placeholder.style.display = "";
    placeholder.textContent = "";
  }
  if (els.hint) els.hint.textContent = "";
  updateCrosshairVisibility();
  stopFpsPolling();
}

// ---------------- Camera: FPS badge (top-right) ----------------
function startFpsPolling() {
  stopFpsPolling();
  updateFpsBadge();
  fpsPollTimer = setInterval(updateFpsBadge, 1000);
}
function stopFpsPolling() {
  if (fpsPollTimer) {
    clearInterval(fpsPollTimer);
    fpsPollTimer = null;
  }
  const badge = document.getElementById("cameraFpsBadge");
  if (badge) badge.classList.remove("on");
}
function updateFpsBadge() {
  const badge = document.getElementById("cameraFpsBadge");
  if (!badge) return;
  if (!cameraLiveRunning) {
    badge.classList.remove("on");
    return;
  }
  const feat = document.getElementById("featureEnabled");
  if ((feat && !feat.checked) || window.cameraPadViewOn) {
    badge.classList.remove("on");
    return;
  }
  Alpha2Api.cameraFps().then(function (json) {
    if (!json.ok || !json.streaming) {
      badge.classList.remove("on");
      return;
    }
    const fps = typeof json.fps === "number" ? json.fps : parseFloat(json.fps) || 0;
    badge.textContent = fps.toFixed(1) + " FPS";
    badge.classList.add("on");
  }).catch(function () {
    badge.classList.remove("on");
  });
}

// ---------------- Camera: enumerate & benchmark all resolutions ----------------
async function refreshSupportedSizes() {
  const hint = document.getElementById("supportedSizesHint");
  const sel = document.getElementById("cameraResolution");
  const EXCLUDED = ["176x144","320x240","352x288"];
  try {
    const res = await Alpha2Api.cameraSupportedSizes();
    if (!res.ok) { if (hint) hint.textContent = t("cam_read_fail", { msg: res.error || "" }); return; }
    if (hint) hint.textContent = "Preview: " + (res.preview||[]).join(", ") + " | Picture: " + (res.picture||[]).join(", ") + " | FPS range: " + (res.fpsRanges||[]).join(", ") + " | " + t("cam_current_label") + ": " + res.current;
    if (res.preview && res.preview.length && sel) {
      const cur = sel.value;
      // 過濾掉已取消的低解像度
      const filtered = res.preview.filter(sz => EXCLUDED.indexOf(sz) === -1);
      const list = filtered.length ? filtered : res.preview;
      sel.innerHTML = "";
      list.forEach(function (sz) {
        const opt = document.createElement("option");
        opt.value = sz; opt.textContent = sz;
        if (sz === cur) opt.selected = true;
        sel.appendChild(opt);
      });
      if (!Array.from(sel.options).some(o=>o.selected)) sel.options[0].selected = true;
      // 若當前值是被排除的，強制切到 1280x720
      if (EXCLUDED.indexOf(cur) !== -1) {
        sel.value = "1280x720";
        // 同步到後端
        const [w,h] = "1280x720".split("x").map(Number);
        Alpha2Api.cameraResolution({w:w,h:h});
      }
    }
  } catch (e) { if (hint) hint.textContent = t("cam_read_err", { msg: e.message }); }
}

// ---------------- Camera: 9 檔影相並存入 Android (/sdcard/DCIM/Alpha2) ----------------
// preview 9 檔用 snapshot_save（快速，基於當前串流幀），picture 5 檔用 take_photo_save（真正單張，高解像經完整 ISP）
async function buildCameraPhoto9Grid() {
  // 已簡化為單一 4208x3120 按鈕，grid 隱藏，無需動態建
  return;
}
async function capturePhotoAtResolution(w,h,btnEl,isPicture) {
  const hint = document.getElementById("cameraPhoto9Hint");
  const origText = btnEl ? btnEl.innerHTML : "";
  if (btnEl) { btnEl.disabled = true; btnEl.textContent = t("cam_shooting"); }
  const mode = isPicture ? t("cam_mode_picture") : t("cam_mode_snapshot");
  if (hint) hint.textContent = t("cam_shoot_status", { w: w, h: h, mode: mode });
  try {
    const res = isPicture
      ? await Alpha2Api.cameraTakePhotoSave({w:w,h:h})
      : await Alpha2Api.cameraSnapshotSave({w:w,h:h});
    if (!res.ok) throw new Error(res.error||"unknown");
    if (hint) hint.textContent = t("cam_saved", { w: w, h: h, path: res.path, bytes: res.bytes || "?" });
    try { Alpha2Api.cameraShutterSound(); } catch(e){}
    try { flashCaptureLed(); } catch(e){}
    const byteChars = atob(res.jpegBase64);
    const bytes = new Uint8Array(byteChars.length);
    for (let i=0;i<byteChars.length;i++) bytes[i]=byteChars.charCodeAt(i);
    const blob = new Blob([bytes],{type:"image/jpeg"});
    const url = URL.createObjectURL(blob);
    const filename = res.path ? res.path.split("/").pop() : ("alpha2_" + w + "x" + h + "_" + Date.now() + ".jpg");
    addCaptureItem("photo", url, filename);
  } catch (e) {
    showError(t("cam_photo_ctx", { w: w, h: h }), e);
    if (hint) hint.textContent = t("cam_shoot_fail", { w: w, h: h, msg: e.message });
  } finally {
    if (btnEl) { btnEl.disabled = false; btnEl.innerHTML = origText; }
  }
}

// ---------------- Camera: photo capture ----------------
//
// Reuses the existing GET /api/camera/snapshot endpoint (a single JPEG frame,
// base64-encoded) rather than adding a new server-side "save to file" endpoint -
// simplest path, and it already starts the camera if it isn't running yet.

function addCaptureItem(kind, url, filename, thumbSrc) {
  const list = document.getElementById("cameraCaptureList");
  if (!list) return;
  const item = document.createElement("div");
  item.className = "capture-item";
  if (kind === "photo") {
    const img = document.createElement("img");
    img.src = thumbSrc || url;
    item.appendChild(img);
  } else {
    const video = document.createElement("video");
    video.src = url;
    video.controls = true;
    video.muted = true;
    item.appendChild(video);
  }
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.textContent = kind === "photo" ? t("cam_dl_photo") : t("cam_dl_video");
  item.appendChild(link);
  list.insertBefore(item, list.firstChild);
}

/** 影相一刻頭/眼 LED 白燈閃半秒。"flash" preset 本身會不斷循環閃下去不會自動停
 *  (見 led/head/set 個 p5/p6/p7 timing), 所以要自己計時, 500ms 後主動送回
 *  stop / 或者還原回錄影中/聽聲中那個長開色 (見 restoreBaseLed()) - 不是僅盲目
 *  stop, 否則影相那刻如果剛剛好正在錄影/正在聽聲, 個燈會給這下閃燈永久覆蓋底層長開色。 */
function flashCaptureLed() {
  const headBrightness = document.getElementById("headBrightness").value;
  const eyeBrightness = document.getElementById("eyeBrightness").value;
  Alpha2Api.ledHeadSet( { preset: "flash", color: 7, brightness: headBrightness });
  Alpha2Api.ledEyeSet( { preset: "flash", color: 7, brightness: eyeBrightness });
  setTimeout(restoreBaseLed, 500);
}

/** 取回現在「底層」應該長開的 LED 狀態 - 錄影中(紅) > 聽機械人中(綠) > 沒有(熄)。
 *  影相閃燈完之後、或者其他一次性效果完了之後, 用來還原回正確的長開狀態。 */
function restoreBaseLed() {
  const headBrightness = document.getElementById("headBrightness").value;
  const eyeBrightness = document.getElementById("eyeBrightness").value;
  let color = null;
  if (mediaRecorder && mediaRecorder.state === "recording") {
    color = 1; // 紅 - 錄影中
  } else if (micListening) {
    color = 2; // 綠 - 聽機械人中
  }
  if (color === null) {
    Alpha2Api.ledHeadSet( { preset: "stop" });
    Alpha2Api.ledEyeSet( { preset: "stop" });
  } else {
    Alpha2Api.ledHeadSet( { preset: "long", color: color, brightness: headBrightness });
    Alpha2Api.ledEyeSet( { preset: "long", color: color, brightness: eyeBrightness });
  }
}

// ---------------- Camera: video recording (client-side) ----------------
//
// The server's CameraController only ever produces individual JPEG preview frames (see
// its javadoc: "there is no takePicture()/... cycle any more"), with no MediaRecorder/
// Surface-based encoder pipeline behind it - adding one server-side would risk the
// working live-stream path for comparatively little gain. Instead, recording draws the
// already-live MJPEG <img> onto a hidden <canvas> on a timer and feeds
// canvas.captureStream() into a standard browser MediaRecorder, producing a WebM file
// entirely client-side. Requires the live stream to already be running.

let mediaRecorder = null;
let recordChunks = [];
let recordCanvas = null;
let recordDrawTimer = null;

function pickRecorderMimeType() {
  const candidates = ["video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"];
  for (const type of candidates) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(type)) {
      return type;
    }
  }
  return "";
}

function toggleRecording() {
  if (mediaRecorder && mediaRecorder.state === "recording") {
    stopRecording();
  } else {
    startRecording();
  }
}

function startRecording() {
  const hint = document.getElementById("cameraStatusHint");
  if (!cameraLiveRunning) {
    hint.textContent = t("cam_need_open");
    return;
  }
  if (!window.MediaRecorder) {
    hint.textContent = t("cam_no_mediarecorder");
    return;
  }
  const img = cameraElements().viewport.querySelector("img");
  if (!img) {
    hint.textContent = t("cam_no_frame");
    return;
  }

  recordCanvas = document.createElement("canvas");
  recordCanvas.width = img.naturalWidth || 640;
  recordCanvas.height = img.naturalHeight || 480;
  const ctx = recordCanvas.getContext("2d");

  // Redraw the current MJPEG frame onto the canvas ~15 times/sec - the <img> itself
  // has no "onframe" event, so polling its current bitmap is the only way to get a
  // stream of frames out of it for captureStream() to encode.
  recordDrawTimer = setInterval(function () {
    if (recordCanvas.width !== (img.naturalWidth || recordCanvas.width)) {
      recordCanvas.width = img.naturalWidth || recordCanvas.width;
      recordCanvas.height = img.naturalHeight || recordCanvas.height;
    }
    try {
      ctx.drawImage(img, 0, 0, recordCanvas.width, recordCanvas.height);
    } catch (e) {
      // Ignore transient draws mid-frame-swap; next tick will succeed.
    }
  }, 66);

  const stream = recordCanvas.captureStream(15);
  const mimeType = pickRecorderMimeType();
  recordChunks = [];
  try {
    mediaRecorder = mimeType ? new MediaRecorder(stream, { mimeType: mimeType }) : new MediaRecorder(stream);
  } catch (e) {
    clearInterval(recordDrawTimer);
    showError(t("cam_record_ctx"), e);
    return;
  }
  mediaRecorder.ondataavailable = function (e) {
    if (e.data && e.data.size > 0) recordChunks.push(e.data);
  };
  mediaRecorder.onstop = function () {
    clearInterval(recordDrawTimer);
    recordDrawTimer = null;
    const blob = new Blob(recordChunks, { type: mediaRecorder.mimeType || "video/webm" });
    const url = URL.createObjectURL(blob);
    const filename = "alpha2-video-" + Date.now() + ".webm";
    addCaptureItem("video", url, filename);
    document.getElementById("cameraStatusHint").textContent = t("cam_rec_stopped");
  };
  mediaRecorder.start();
  document.getElementById("recordFab").classList.add("recording");
  hint.textContent = t("cam_recording");
  setRecordingLed(true);
}

function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== "inactive") {
    mediaRecorder.stop();
  }
  document.getElementById("recordFab").classList.remove("recording");
  setRecordingLed(false);
}

/** 錄影中頭/眼 LED 長開紅燈, 停止錄影就熄返。 */
function setRecordingLed(on) {
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    Alpha2Api.ledHeadSet( { preset: "long", color: 1, brightness: headBrightness });
    Alpha2Api.ledEyeSet( { preset: "long", color: 1, brightness: eyeBrightness });
  } else {
    Alpha2Api.ledHeadSet( { preset: "stop" });
    Alpha2Api.ledEyeSet( { preset: "stop" });
  }
}

// ---------------- Camera: 機端錄影 mp4 ----------------
// 同上面瀏覽器 toggleRecording 唔同：呢個係機器人本機經 MediaRecorder 錄影，
// 檔存 /sdcard/DCIM/Alpha2（相簿即見），經手柄映射 video_toggle 都撳到。
// 錄緊嗰陣拍照／改解像度後端會拒（保編碼器），MJPEG 訂閱者會因無新幀超時自斷。
let robotVideoRecording = false;
let robotVideoBusy = false; // robotVideoToggle 重入保護：連撳兩下唔好發兩個 start/stop

function robotVideoSyncFab() {
  const fab = document.getElementById("robotVideoFab");
  if (fab) fab.classList.toggle("recording", robotVideoRecording);
}

function robotVideoHintDone(json) {
  if (!json || !json.ok) return "";
  const secs = Math.round((json.durationMs || 0) / 1000);
  return t("cam_rrec_done", { path: json.path || "", secs: secs,
    audio: json.hasAudio ? t("cam_audio_yes2") : t("cam_audio_no2") });
}

async function robotVideoToggle() {
  if (robotVideoBusy) return;
  robotVideoBusy = true;
  const hint = document.getElementById("cameraStatusHint");
  try {
    if (robotVideoRecording) {
      const json = await Alpha2Api.cameraVideoStop();
      if (json && json.ok) {
        robotVideoRecording = false;
        robotVideoSyncFab();
        if (hint) hint.textContent = robotVideoHintDone(json);
      } else if (hint) {
        hint.textContent = t("cam_rrec_stop_fail", { msg: (json && json.error) || t("err_unknown") });
      }
    } else {
      const json = await Alpha2Api.cameraVideoStart({});
      if (json && json.ok && json.recording) {
        robotVideoRecording = true;
        robotVideoSyncFab();
        if (hint) hint.textContent = t("cam_rrec_on", { audio: json.hasAudio ? t("cam_audio_yes") : t("cam_audio_no") });
      } else if (hint) {
        hint.textContent = t("cam_rrec_start_fail", { msg: (json && json.error) || t("err_unknown") });
      }
    }
  } catch (e) {
    if (hint) hint.textContent = t("cam_rrec_fail", { msg: e.message });
  } finally {
    robotVideoBusy = false;
  }
}

// ---------------- Camera crosshair: drag-to-aim head control (servo 19 pan / 20 tilt) ----
//
// The crosshair mark's position within the viewport maps linearly to the servo 19/20
// The pad's center = each servo's home position; dragging the knob to the pad's edge
// in any direction reaches that servo's min or max. Only the drag's *end* (pointerup)
// sends servo/one - dragging continuously updates the knob's on-screen position for
// visual feedback, but would flood the AIDL bridge with a servo command on every
// pointermove tick otherwise. Releasing snaps the knob back to center, matching how a
// physical self-centering joystick behaves.

let crosshairDragging = false;
let crosshairSetupDone = false;
// 搖桿自動回中開關（關閉後鬆手保持角度，不回正）
let joystickAutoReturn = true;

/** axisValue in [-1, 1]: -1 = servo min, 0 = servo home, +1 = servo max. */
function crosshairAxisToAngle(id, axisValue) {
  const cal = SERVO_CALIBRATION[id];
  if (!cal) return null;
  const span = axisValue >= 0 ? (cal.max - cal.home) : (cal.home - cal.min);
  return clampServoAngle(id, Math.round(cal.home + axisValue * span));
}

function setKnobPosition(nx, ny) {
  const mark = cameraElements().crosshairMark;
  if (!mark) return;
  const clampedNx = Math.max(-1, Math.min(1, nx));
  const clampedNy = Math.max(-1, Math.min(1, ny));
  mark.style.left = (50 + clampedNx * 35) + "%";
  mark.style.top = (50 + clampedNy * 35) + "%";
}

function updateCrosshairVisibility() {
  const els = cameraElements();
  const shouldShow = cameraLiveRunning && els.crosshairToggle && els.crosshairToggle.checked
    && !window.cameraPadViewOn;
  if (els.crosshairPad) {
    els.crosshairPad.classList.toggle("active", shouldShow);
  }
  if (els.fabRow) {
    els.fabRow.classList.toggle("active", shouldShow);
  }
  // 統一風格 — 解像度(左上)、Zoom(相機鍵上)、回中(joystick右)、FPS(右上) 皆為功能鍵內容
  const resWrap = document.getElementById("cameraResolutionWrap");
  if (resWrap) resWrap.style.display = shouldShow ? "block" : "none";
  const resSel = document.getElementById("cameraResolution");
  if (resSel) resSel.disabled = !shouldShow;
  const zoomV = document.getElementById("cameraZoomVertical");
  if (zoomV) zoomV.style.display = shouldShow ? "flex" : "none";
  const zs = document.getElementById("cameraZoom");
  if (zs) zs.disabled = !shouldShow;
  const joyWrap = document.getElementById("joystickAutoReturnWrap");
  if (joyWrap) joyWrap.style.display = shouldShow ? "flex" : "none";
  const jrCb = document.getElementById("joystickAutoReturn");
  if (jrCb) jrCb.disabled = !shouldShow;
  const fpsBadge = document.getElementById("cameraFpsBadge");
  if (fpsBadge) {
    if (!shouldShow) fpsBadge.classList.remove("on");
    else if (cameraLiveRunning) updateFpsBadge();
  }
  // Unticking the master checkbox (or stopping the camera) must not leave mic-listen
  // silently running with its FAB hidden - force it off so the
  // control state always matches what's actually visible on screen.
  if (!shouldShow) {
    if (micListening) stopMicListen();
  }
}
function joystickAutoReturnToggle() {
  const cb = document.getElementById("joystickAutoReturn");
  joystickAutoReturn = cb ? cb.checked : true;
  try { localStorage.setItem("joystickAutoReturn", joystickAutoReturn ? "1" : "0"); } catch(e){}
  if (joystickAutoReturn) {
    // 開啟自動回中時立即回正（符合「關了再重新開啟會立即回中」預期）
    setKnobPosition(0, 0);
    try {
      const t = (typeof servoTime === 'function' ? servoTime() : 500);
      Alpha2Api.servoOne({id:19, angle: crosshairAxisToAngle(19,0), time: t});
      Alpha2Api.servoOne({id:20, angle: crosshairAxisToAngle(20,0), time: t});
    } catch(e){}
  }
}
function initJoystickAutoReturn() {
  try {
    const v = localStorage.getItem("joystickAutoReturn");
    if (v !== null) joystickAutoReturn = v === "1";
  } catch(e){}
  const cb = document.getElementById("joystickAutoReturn");
  if (cb) cb.checked = joystickAutoReturn;
}

function setupCrosshairIfNeeded() {
  if (crosshairSetupDone) return;
  crosshairSetupDone = true;

  const els = cameraElements();
  const pad = els.crosshairPad;
  if (!pad) return;

  // Throttles how often a drag actually sends servo/one while the pointer is moving -
  // pointermove can fire far faster than the AIDL bridge (and the servo hardware
  // itself) can usefully keep up with. lastSendTime/pendingTimer ensure the most recent
  // position is never silently dropped: if a move arrives during the cooldown window, a
  // trailing call is scheduled for right when the cooldown ends, rather than only
  // sending on the next move event (which may never come if the user holds still).
  const SERVO_LIVE_THROTTLE_MS = 120;
  let lastSendTime = 0;
  let pendingTimer = null;

  function sendServoForAxis(nx, ny) {
    const panAngle = crosshairAxisToAngle(19, nx);
    const tiltAngle = crosshairAxisToAngle(20, ny);
    // Uses the same move-time setting as the Servo tab (servoTime()).
    const time = servoTime();
    if (panAngle !== null) Alpha2Api.servoOne( { id: 19, angle: panAngle, time: time });
    if (tiltAngle !== null) Alpha2Api.servoOne( { id: 20, angle: tiltAngle, time: time });
  }

  function sendServoThrottled(nx, ny) {
    const now = Date.now();
    const elapsed = now - lastSendTime;
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
    if (elapsed >= SERVO_LIVE_THROTTLE_MS) {
      lastSendTime = now;
      sendServoForAxis(nx, ny);
    } else {
      pendingTimer = setTimeout(function () {
        pendingTimer = null;
        lastSendTime = Date.now();
        sendServoForAxis(nx, ny);
      }, SERVO_LIVE_THROTTLE_MS - elapsed);
    }
  }

  function axisFromEvent(evt) {
    const rect = pad.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const radius = rect.width / 2;
    const nx = (evt.clientX - cx) / radius;
    const ny = (evt.clientY - cy) / radius;
    return { nx: Math.max(-1, Math.min(1, nx)), ny: Math.max(-1, Math.min(1, ny)) };
  }

  function onPointerDown(evt) {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    crosshairDragging = true;
    const mk = cameraElements().crosshairMark;
    if (mk) mk.classList.add("dragging");
    try { pad.setPointerCapture(evt.pointerId); } catch(e){}
    const { nx, ny } = axisFromEvent(evt);
    setKnobPosition(nx, ny);
    lastSendTime = Date.now();
    sendServoForAxis(nx, ny); // send immediately on touch-down, not throttled
    evt.preventDefault();
  }

  function onPointerMove(evt) {
    if (!crosshairDragging) return;
    const { nx, ny } = axisFromEvent(evt);
    setKnobPosition(nx, ny);
    sendServoThrottled(nx, ny);
    evt.preventDefault();
  }

  function onPointerUp(evt) {
    if (!crosshairDragging) return;
    crosshairDragging = false;
    const mk2 = cameraElements().crosshairMark;
    if (mk2) mk2.classList.remove("dragging");
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
    if (joystickAutoReturn) {
      setKnobPosition(0, 0); // self-centering, like a physical joystick
      sendServoForAxis(0, 0); // return the head to home immediately, not throttled
    } else {
      // 關閉自動回中：鬆手保持當前角度，僅結束拖拽狀態，不回正
    }
  }

  pad.addEventListener("pointerdown", onPointerDown);
  pad.addEventListener("pointermove", onPointerMove);
  pad.addEventListener("pointerup", onPointerUp);
  pad.addEventListener("pointercancel", onPointerUp);

  els.crosshairToggle.addEventListener("change", updateCrosshairVisibility);

  // ---- Keyboard control: arrow keys -> servo 19/20 (pan/tilt) ----
  //
  // Reuses the same axisToAngle/throttle/knob-position plumbing as pointer-drag above,
  // so keyboard and mouse/touch control feel identical and never fight each other -
  // holding an arrow key is just another way of "dragging the knob" to that key's edge
  // of the pad, at whatever axis value ARROW_KEY_AXIS represents.
  const ARROW_KEY_AXIS = 0.6; // partial deflection, not full min/max, per key press -
                                // a single key only drives one direction at a time
                                // (unlike a drag, which can reach any diagonal), so a
                                // deliberately moderate value avoids the head snapping
                                // to a hard endstop on every tap.
  const heldArrowKeys = new Set();

  function arrowKeysToAxis() {
    let nx = 0, ny = 0;
    if (heldArrowKeys.has("ArrowLeft")) nx -= 1;
    if (heldArrowKeys.has("ArrowRight")) nx += 1;
    if (heldArrowKeys.has("ArrowUp")) ny -= 1;
    if (heldArrowKeys.has("ArrowDown")) ny += 1;
    return { nx: nx * ARROW_KEY_AXIS, ny: ny * ARROW_KEY_AXIS };
  }

  function applyArrowKeyState() {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    const { nx, ny } = arrowKeysToAxis();
    setKnobPosition(nx, ny);
    sendServoThrottled(nx, ny);
  }

  const ARROW_KEYS = ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"];

  els.viewport.addEventListener("keydown", function (evt) {
    if (!els.crosshairToggle || !els.crosshairToggle.checked) return;
    if (ARROW_KEYS.indexOf(evt.key) !== -1) {
      evt.preventDefault(); // stop the page itself from scrolling on arrow keys
      if (!heldArrowKeys.has(evt.key)) {
        heldArrowKeys.add(evt.key);
        applyArrowKeyState();
      }
      return;
    }
  });

  els.viewport.addEventListener("keyup", function (evt) {
    if (ARROW_KEYS.indexOf(evt.key) !== -1) {
      heldArrowKeys.delete(evt.key);
      if (heldArrowKeys.size === 0 && !joystickAutoReturn) {
        // 關閉自動回中時，鬆開最後一鍵保持當前角度，不回正
        // 僅清除 dragging 視覺，保留 knob 位置與 servo 角度
      } else {
        applyArrowKeyState(); // recompute with this key removed - may now be back to (0,0)
        if (heldArrowKeys.size === 0 && joystickAutoReturn) {
          setKnobPosition(0, 0);
          sendServoForAxis(0, 0); // snap home immediately, matching pointerup's behavior
        }
      }
      return;
    }
  });

  // If the viewport loses keyboard focus entirely (Tab away, click elsewhere) while a
  // key was physically still held down, the corresponding keyup event never reaches
  // this listener - without this, the head could get stuck "on" until some
  // other event happened to reset it.
  els.viewport.addEventListener("blur", function () {
    if (heldArrowKeys.size > 0) {
      heldArrowKeys.clear();
      if (joystickAutoReturn) {
        setKnobPosition(0, 0);
        sendServoForAxis(0, 0);
      }
    }
  });

}

/** (Re)points the viewport's <img> at a fresh /stream/camera connection. A query-string
 *  cache-buster forces the browser to open a genuinely new multipart connection instead
 *  of reusing a possibly-dead one it still thinks is "loading". */
function connectCameraStream() {
  const els = cameraElements();
  const viewport = els.viewport, hint = els.hint;
  let img = viewport.querySelector("img");
  if (!img) {
    img = document.createElement("img");
    // Prevent the browser's native "Save image" / "Copy image" context menu, which a
    // long-press (touch) or right-click (desktop) would otherwise show on top of this
    // <img> - that gesture directly conflicts with drag-to-aim on the crosshair pad. draggable=false
    // additionally stops a click-and-drag on the image itself from starting an
    // OS-level "drag this image out" operation, which has the same effect of hijacking
    // what should have been a joystick-pad drag if the pointer happens to be over the
    // image (the pad and image overlap the same viewport area).
    img.oncontextmenu = function () { return false; };
    img.draggable = false;
    img.onload = function () {
      // Fires once when the connection is first established - just used here to show
      // the actual resolution in use. Not fired again per MJPEG part in mainstream
      // browsers, so it is not used as an ongoing liveness signal (see the block
      // comment above).
      Alpha2Api.cameraInfo( {}).then(function (resp) {
        if (resp && resp.previewWidth) {
          hint.textContent = t("cam_actual_res", { w: resp.previewWidth, h: resp.previewHeight });
        } else {
          hint.textContent = "";
        }
      }).catch(function () {
        hint.textContent = "";
      });
    };
    img.onerror = function () {
      if (!cameraLiveRunning) return;
      hint.textContent = t("cam_stream_retry");
      setTimeout(function () {
        if (cameraLiveRunning) connectCameraStream();
      }, 3000);
    };
    viewport.appendChild(img);
  }
  // 每次重建串流都需重套當前變焦（新 <img> 無舊 transform）
  try { applyCameraZoomCss(cameraZoom); } catch(e){}
  // 面板 token：<img> 設不到 header，鎖機時用 ?panel_token= query fallback（見 PanelAuth）。
  let camUrl = "/stream/camera?t=" + Date.now();
  try {
    const pt = (typeof panelTokenGet === "function") ? panelTokenGet() : "";
    if (pt) camUrl += "&panel_token=" + encodeURIComponent(pt);
  } catch (ign) {}
  img.src = camUrl;
}

// DOMContentLoaded 單次初始化變焦與搖桿回中（不依賴 app-log.js 順序，自身亦可獨立起）
function initCameraUiToggles() { try { initCameraZoom(); } catch(e){} try { initJoystickAutoReturn(); } catch(e){} try { updateCrosshairVisibility(); } catch(e){} try { faceTrackRefreshStatus(); } catch(e){} try { colorTrackRefreshStatus(); } catch(e){} try { objectTrackRefreshStatus(); } catch(e){} try { objectTrackSelectInit(); } catch(e){} }
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initCameraUiToggles);
} else {
  initCameraUiToggles();
}

// ---------------- Camera: Android 內置人臉追蹤 ----------------
//
// 後端 FaceTrackCenter（framework FaceDetector，零依賴）：檢到最大一張臉即按
// 偏移步進驅動頭部 19/20（定案預設：步進 17°、死區 0.10、自動間隔 500/100ms，
// 追頭＋ROI 預設開）。前端只剩功能鍵行一個開關＋viewport 綠框 overlay，不做任何
// 影像分析（分析全在機械人端，瀏覽器只畫框）。
// 注意：播動作（UbxPlayer）時請先停追蹤，兩邊同搶頭部舵機會打架。
//
// 卡片已移除（定案收斂），`faceTrackStatusHint` 等設定控件不存在——下面所有
// getElementById 全部有 null-guard，搵唔到即跳過。

let faceTrackPollTimer = null;
let faceTrackRunning = false;
let faceTrackWalkFollow = false;

function faceTrackRenderBox(json) {
  const box = document.getElementById("faceTrackBox");
  const viewport = document.getElementById("cameraViewport");
  if (!box || !viewport) return;
  if (!json || !json.running || !json.faces || json.fx == null || json.fx < 0) {
    box.classList.remove("on");
    return;
  }
  const rect = objectTrackContentRect() || { left: 0, top: 0, width: viewport.clientWidth, height: viewport.clientHeight };
  const fx = Number(json.fx), fy = Number(json.fy);
  const fw = Number(json.fw) || 0.25, fh = Number(json.fh) || 0.3;
  if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) { box.classList.remove("on"); return; }
  // 中心＋寬高（歸一化）轉 viewport px；img 是 object-fit:contain，簡單按 viewport
  // 全區定位（近似框，調試用，不保證與 MJPEG 像素級對齊）。
  const w = Math.max(24, fw * rect.width);
  const h = Math.max(24, fh * rect.height);
  const left = rect.left + Math.max(0, Math.min(rect.width - w, fx * rect.width - w / 2));
  const top = rect.top + Math.max(0, Math.min(rect.height - h, fy * rect.height - h / 2));
  box.style.left = left + "px";
  box.style.top = top + "px";
  box.style.width = w + "px";
  box.style.height = h + "px";
  box.classList.add("on");
}

function faceTrackRenderStatus(json) {
  const toggle = document.getElementById("faceTrackToggle");
  if (!json || !json.ok) return;
  faceTrackRunning = !!json.running;
  if (toggle && toggle.checked !== faceTrackRunning) toggle.checked = faceTrackRunning;
  faceTrackRenderBox(json);
  // 行走跟隨以後端 config echo 為準（舊版無呢個欄位即當關）；共用開關由 trackOptionsUpdate 按當前模式畫。
  faceTrackWalkFollow = !!(json.config && json.config.walkFollow);
  if (json.running) cameraModeEnforce();
  trackOptionsUpdate();
}



async function faceTrackRefreshStatus() {
  try {
    const json = await Alpha2Api.faceTrackStatus();
    faceTrackRenderStatus(json);
    // 開頁時後端已經行緊／其他客戶端（手機、手柄）啟停：按 running 自動開關輪詢，框先會郁、唔會輪詢到永遠。
    if (json && json.ok) {
      if (json.running && !faceTrackPollTimer) faceTrackStartPolling();
      else if (!json.running && faceTrackPollTimer) faceTrackStopPolling();
    }
  } catch (e) {
    showError(t("face_track_label"), e);
  }
}

function faceTrackStartPolling() {
  faceTrackStopPolling();
  faceTrackPollTimer = setInterval(faceTrackRefreshStatus, 1500);
}

function faceTrackStopPolling() {
  if (faceTrackPollTimer) { clearInterval(faceTrackPollTimer); faceTrackPollTimer = null; }
  const box = document.getElementById("faceTrackBox");
  if (box) box.classList.remove("on");
}

async function faceTrackToggleChanged() {
  const toggle = document.getElementById("faceTrackToggle");
  const on = toggle ? toggle.checked : false;
  try {
    if (on) {
      cameraModeBegin(); // 5選1：開追蹤即閂功能鍵／手柄
      // 設定卡已移除：空參數即用後端定案預設（步進 17／死區 0.10／自動 500/100ms，追頭＋ROI 開）。
      const json = await Alpha2Api.faceTrackStart({});
      faceTrackRenderStatus(json);
      if (json && json.ok && json.running) { faceTrackStartPolling(); colorTrackSyncOff(); objectTrackSyncOff(); }
      else if (toggle) toggle.checked = false;
    } else {
      const json = await Alpha2Api.faceTrackStop();
      faceTrackRenderStatus(json);
      faceTrackStopPolling();
      // 停後再讀一次確保框清除
      faceTrackRenderBox({ running: false });
    }
  } catch (e) {
    showError(t("face_track_label"), e);
    if (toggle) toggle.checked = faceTrackRunning;
  }
}

// ---------------- Camera: 純 Java 顏色追蹤 ----------------
//
// 後端 ColorTrackCenter（HSV 閾值＋最大 blob，零依賴）：跟到最大色塊質心即按
// 偏移步進驅動頭部 19/20（定案預設：紅色 hue 335-25°、步進 17°、死區 0.10、
// 自動間隔 200/100ms，追頭預設開）。前端只剩功能鍵行一個開關＋viewport 橙框
// overlay，不做任何影像分析。
// 互斥：後端 start 一邊即停另一邊，前端兩邊 toggle／輪詢亦要同步熄（見
// colorTrackSyncOff／faceTrackSyncOff，由成功 start 嗰邊調用）。
// 注意：播動作（UbxPlayer）時請先停追蹤，兩邊同搶頭部舵機會打架。

let colorTrackPollTimer = null;
let colorTrackRunning = false;

// 追色選擇：同 LED 七色碼對應（見 app-led.js LED_COLORS 1=紅…7=白），hex 照抄嗰邊；
// HSV 範圍係相機實物經驗值（唔係 LED 發光色本身，現場光有偏差，框出唔到先放寬 sMin）。
// 白色靠 sMax 去彩色（後端新加，冇佢白乜都 match）。
const COLOR_TRACK_PRESETS = [
  { code: 1, key: "color_name_red",    hex: "#ff3b3b", hMin: 335, hMax: 25,  sMin: 0.45, sMax: 1.0,  vMin: 0.25, vMax: 1.0 },
  { code: 2, key: "color_name_green",  hex: "#3bff5c", hMin: 85,  hMax: 155, sMin: 0.40, sMax: 1.0,  vMin: 0.25, vMax: 1.0 },
  { code: 3, key: "color_name_blue",   hex: "#3b6bff", hMin: 195, hMax: 255, sMin: 0.40, sMax: 1.0,  vMin: 0.25, vMax: 1.0 },
  { code: 4, key: "color_name_yellow", hex: "#ffe93b", hMin: 38,  hMax: 75,  sMin: 0.45, sMax: 1.0,  vMin: 0.30, vMax: 1.0 },
  { code: 5, key: "color_name_purple", hex: "#a83bff", hMin: 250, hMax: 300, sMin: 0.40, sMax: 1.0,  vMin: 0.25, vMax: 1.0 },
  { code: 6, key: "color_name_cyan",   hex: "#3bfff0", hMin: 155, hMax: 195, sMin: 0.40, sMax: 1.0,  vMin: 0.25, vMax: 1.0 },
  { code: 7, key: "color_name_white",  hex: "#ffffff", hMin: 0,   hMax: 360, sMin: 0,    sMax: 0.25, vMin: 0.60, vMax: 1.0 },
];
let colorTrackPreset = 1; // 預設紅（同後端定案預設一致）
let colorTrackWalkFollow = false;

function colorTrackPresetParams(code) {
  for (let i = 0; i < COLOR_TRACK_PRESETS.length; i++) {
    const p = COLOR_TRACK_PRESETS[i];
    if (p.code === code) return { hMin: p.hMin, hMax: p.hMax, sMin: p.sMin, sMax: p.sMax, vMin: p.vMin, vMax: p.vMax };
  }
  return {};
}

function colorTrackBuildPresets() {
  const wrap = document.getElementById("colorTrackDots");
  if (!wrap || wrap.dataset.built) return;
  COLOR_TRACK_PRESETS.forEach(function (p) {
    const dot = document.createElement("button");
    dot.type = "button";
    dot.className = "color-dot" + (p.code === colorTrackPreset ? " selected" : "");
    dot.style.background = p.hex;
    dot.dataset.code = p.code;
    dot.title = t(p.key);
    dot.onclick = function () { colorTrackPickPreset(p.code); };
    wrap.appendChild(dot);
  });
  wrap.dataset.built = "1";
}

function colorTrackMarkSelected() {
  const wrap = document.getElementById("colorTrackDots");
  if (!wrap) return;
  wrap.querySelectorAll(".color-dot").forEach(function (d) {
    if (Number(d.dataset.code) === colorTrackPreset) d.classList.add("selected");
    else d.classList.remove("selected");
  });
}

function colorTrackUpdatePresetVisibility() {
  const wrap = document.getElementById("colorTrackPresets");
  if (!wrap) return;
  const show = colorTrackRunning;
  if ((wrap.style.display !== "none") === show) return;
  wrap.style.display = show ? "" : "none";
  if (show) { colorTrackBuildPresets(); colorTrackMarkSelected(); }
}

async function colorTrackPickPreset(code) {
  colorTrackPreset = code;
  colorTrackMarkSelected();
  if (!colorTrackRunning) return; // 未行緊：記低選擇，下次 start 用
  try {
    // 行緊：config 即時換色，唔使停 tracking（後端參數 volatile，下幀即用新範圍）。
    const json = await Alpha2Api.colorTrackConfig(colorTrackPresetParams(code));
    colorTrackRenderStatus(json);
  } catch (e) {
    showError(t("color_track_label"), e);
  }
}


function colorTrackRenderBox(json) {
  const box = document.getElementById("colorTrackBox");
  const viewport = document.getElementById("cameraViewport");
  if (!box || !viewport) return;
  if (!json || !json.running || !json.faces || json.fx == null || json.fx < 0) {
    box.classList.remove("on");
    return;
  }
  const rect = objectTrackContentRect() || { left: 0, top: 0, width: viewport.clientWidth, height: viewport.clientHeight };
  const fx = Number(json.fx), fy = Number(json.fy);
  const fw = Number(json.fw) || 0.25, fh = Number(json.fh) || 0.3;
  if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) { box.classList.remove("on"); return; }
  // 中心＋寬高（歸一化）轉 viewport px；同人臉框一樣按 viewport 全區定位（近似框）。
  const w = Math.max(24, fw * rect.width);
  const h = Math.max(24, fh * rect.height);
  const left = rect.left + Math.max(0, Math.min(rect.width - w, fx * rect.width - w / 2));
  const top = rect.top + Math.max(0, Math.min(rect.height - h, fy * rect.height - h / 2));
  box.style.left = left + "px";
  box.style.top = top + "px";
  box.style.width = w + "px";
  box.style.height = h + "px";
  box.classList.add("on");
}

function colorTrackRenderStatus(json) {
  const toggle = document.getElementById("colorTrackToggle");
  if (!json || !json.ok) return;
  colorTrackRunning = !!json.running;
  if (toggle && toggle.checked !== colorTrackRunning) toggle.checked = colorTrackRunning;
  colorTrackRenderBox(json);
  colorTrackUpdatePresetVisibility();
  // 行走跟隨以後端 config echo 為準（舊版無呢個欄位即當關）；共用開關由 trackOptionsUpdate 按當前模式畫。
  colorTrackWalkFollow = !!(json.config && json.config.walkFollow);
  if (json.running) cameraModeEnforce();
  trackOptionsUpdate();
}

async function colorTrackRefreshStatus() {
  try {
    const json = await Alpha2Api.colorTrackStatus();
    colorTrackRenderStatus(json);
    if (json && json.ok) {
      if (json.running && !colorTrackPollTimer) colorTrackStartPolling();
      else if (!json.running && colorTrackPollTimer) colorTrackStopPolling();
    }
  } catch (e) {
    showError(t("color_track_label"), e);
  }
}

function colorTrackStartPolling() {
  colorTrackStopPolling();
  colorTrackPollTimer = setInterval(colorTrackRefreshStatus, 1500);
}

function colorTrackStopPolling() {
  if (colorTrackPollTimer) { clearInterval(colorTrackPollTimer); colorTrackPollTimer = null; }
  const box = document.getElementById("colorTrackBox");
  if (box) box.classList.remove("on");
}

// 對面（人臉）成功 start 後調：後端已停顏色，前端同步熄 toggle＋輪詢＋框＋選色列。
function colorTrackSyncOff() {
  colorTrackRunning = false;
  colorTrackStopPolling();
  const toggle = document.getElementById("colorTrackToggle");
  if (toggle) toggle.checked = false;
  colorTrackUpdatePresetVisibility();
  trackOptionsUpdate();
}

// 人臉側成功 start 後調（上面 faceTrackToggleChanged 已接）：後端已停人臉，前端同步熄。
function faceTrackSyncOff() {
  faceTrackRunning = false;
  faceTrackStopPolling(); // 內已清人臉框 .on
  const toggle = document.getElementById("faceTrackToggle");
  if (toggle) toggle.checked = false;
  trackOptionsUpdate();
}

async function colorTrackToggleChanged() {
  const toggle = document.getElementById("colorTrackToggle");
  const on = toggle ? toggle.checked : false;
  try {
    if (on) {
      cameraModeBegin(); // 5選1：開追蹤即閂功能鍵／手柄
      // 帶當前選色起（預設紅；選色列點過即換 colorTrackPreset，start 即用）。
      const json = await Alpha2Api.colorTrackStart(colorTrackPresetParams(colorTrackPreset));
      colorTrackRenderStatus(json);
      if (json && json.ok && json.running) { colorTrackStartPolling(); faceTrackSyncOff(); objectTrackSyncOff(); }
      else if (toggle) toggle.checked = false;
    } else {
      const json = await Alpha2Api.colorTrackStop();
      colorTrackRenderStatus(json);
      colorTrackStopPolling();
      // 停後再讀一次確保框清除
      colorTrackRenderBox({ running: false });
    }
  } catch (e) {
    showError(t("color_track_label"), e);
    if (toggle) toggle.checked = colorTrackRunning;
  }
}

// ---------------- Camera: 純 Java 物件追蹤 ----------------
//
// 後端 ObjectTrackCenter（灰階模板匹配 ZSAD，零依賴）：用戶喺預覽畫面拖框（或點一下）
// 揀任意物件做目標，之後按偏移步進驅動頭部 19/20，可選行走跟隨（共用 WalkFollower）。
// 開關預設以畫面正中框做目標（將物件對正鏡頭再開）；追蹤中再拖框＝重新揀目標（config 帶 bx/by/bw/bh）。
// 同人臉／顏色追蹤互斥（後端 CameraApi 層 start 一邊即停另外兩邊，前端用 *SyncOff 對齊 UI）。
let objectTrackRunning = false;
let objectTrackPollTimer = null;
let objectTrackWalkFollow = false;
let objectTrackLastStatus = null; // 最近一次 status（提示文字用）
let objectTrackSel = null; // 拖框中：{ x0, y0, x1, y1 }（viewport 局部 px）
// 點一下揀點時用嘅預設框大小（同後端預設正中框一致：寬 25%、高 30%）。
const OBJECT_TRACK_TAP_W = 0.25;
const OBJECT_TRACK_TAP_H = 0.30;
const OBJECT_TRACK_DRAG_MIN_PX = 12;

// 預覽畫面真正顯示嘅範圍（img 係 object-fit:contain，16:9 串流喺 4:3 viewport 會有黑邊）；
// 未有 img／未載入就退返成個 viewport。座標：viewport 局部 px。
function objectTrackContentRect() {
  const viewport = document.getElementById("cameraViewport");
  if (!viewport) return null;
  const r = viewport.getBoundingClientRect();
  const full = { left: 0, top: 0, width: r.width, height: r.height };
  const img = viewport.querySelector("img");
  if (!img || !img.naturalWidth || !img.naturalHeight || !r.width || !r.height) return full;
  const scale = Math.min(r.width / img.naturalWidth, r.height / img.naturalHeight);
  const cw = img.naturalWidth * scale;
  const ch = img.naturalHeight * scale;
  return { left: (r.width - cw) / 2, top: (r.height - ch) / 2, width: cw, height: ch };
}

function objectTrackRenderBox(json) {
  const box = document.getElementById("objectTrackBox");
  if (!box) return;
  const cr = objectTrackContentRect();
  if (!cr || !json || !json.running || json.fx == null || json.fx < 0 || !json.selected) {
    box.classList.remove("on", "lost");
    return;
  }
  const fx = Number(json.fx), fy = Number(json.fy);
  const fw = Number(json.fw) || OBJECT_TRACK_TAP_W, fh = Number(json.fh) || OBJECT_TRACK_TAP_H;
  if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) { box.classList.remove("on", "lost"); return; }
  const w = Math.max(16, fw * cr.width);
  const h = Math.max(16, fh * cr.height);
  const left = cr.left + Math.max(0, Math.min(cr.width - w, fx * cr.width - w / 2));
  const top = cr.top + Math.max(0, Math.min(cr.height - h, fy * cr.height - h / 2));
  box.style.left = left + "px";
  box.style.top = top + "px";
  box.style.width = w + "px";
  box.style.height = h + "px";
  box.classList.add("on");
  // 跟丟：框停喺最後位置、變虛線半透明（faces=0）
  if (json.faces) box.classList.remove("lost"); else box.classList.add("lost");
}

// 物件追蹤狀態對應嘅提示文字（顯示喺共用追蹤選項列左邊）。
function objectTrackHintText() {
  const json = objectTrackLastStatus;
  if (json && json.selected && json.lowTexture) return t("object_track_low_texture");
  if (json && json.selected && !json.faces) return t("object_track_lost");
  if (json && json.error) return json.error;
  return t("object_track_hint");
}

function objectTrackUpdateUi(json) {
  objectTrackLastStatus = json || null;
  const viewport = document.getElementById("cameraViewport");
  if (viewport) viewport.classList.toggle("object-selecting", objectTrackRunning);
  trackOptionsUpdate();
}

function objectTrackRenderStatus(json) {
  const toggle = document.getElementById("objectTrackToggle");
  if (!json || !json.ok) return;
  objectTrackRunning = !!json.running;
  if (toggle && toggle.checked !== objectTrackRunning) toggle.checked = objectTrackRunning;
  objectTrackRenderBox(json);
  // 行走跟隨以後端 config echo 為準；共用開關由 trackOptionsUpdate 按當前模式畫。
  objectTrackWalkFollow = !!(json.config && json.config.walkFollow);
  if (json.running) cameraModeEnforce();
  objectTrackUpdateUi(json);
}

async function objectTrackRefreshStatus() {
  try {
    const json = await Alpha2Api.objectTrackStatus();
    objectTrackRenderStatus(json);
    if (json && json.ok) {
      if (json.running && !objectTrackPollTimer) objectTrackStartPolling();
      else if (!json.running && objectTrackPollTimer) objectTrackStopPolling();
    }
  } catch (e) {
    showError(t("object_track_label"), e);
  }
}

function objectTrackStartPolling() {
  objectTrackStopPolling();
  // 物件會郁，框要跟得上：比顏色／人臉（1.5 s）密，status 係純記憶體讀取。
  objectTrackPollTimer = setInterval(objectTrackRefreshStatus, 600);
}

function objectTrackStopPolling() {
  if (objectTrackPollTimer) { clearInterval(objectTrackPollTimer); objectTrackPollTimer = null; }
  const box = document.getElementById("objectTrackBox");
  if (box) box.classList.remove("on", "lost");
}

// 另一邊（人臉／顏色）成功 start 後調用：只對齊 UI，唔再發 stop（後端已互斥停咗）。
function objectTrackSyncOff() {
  objectTrackRunning = false;
  objectTrackStopPolling();
  const toggle = document.getElementById("objectTrackToggle");
  if (toggle) toggle.checked = false;
  objectTrackUpdateUi(null);
  objectTrackSelectHide();
}

async function objectTrackToggleChanged() {
  const toggle = document.getElementById("objectTrackToggle");
  const on = toggle ? toggle.checked : false;
  try {
    if (on) {
      cameraModeBegin(); // 5選1：開追蹤即閂功能鍵／手柄
      // 空參數＝以畫面正中框做目標；開咗之後拖框／點一下可重新揀。
      const json = await Alpha2Api.objectTrackStart({});
      objectTrackRenderStatus(json);
      if (json && json.ok && json.running) { objectTrackStartPolling(); faceTrackSyncOff(); colorTrackSyncOff(); }
      else if (toggle) toggle.checked = false;
    } else {
      const json = await Alpha2Api.objectTrackStop();
      objectTrackRenderStatus(json);
      objectTrackStopPolling();
      objectTrackRenderBox({ running: false });
      objectTrackSelectHide();
    }
  } catch (e) {
    showError(t("object_track_label"), e);
    if (toggle) toggle.checked = objectTrackRunning;
  }
}


// ── 拖框揀目標（pointer events，掛喺 viewport；只喺物件追蹤行緊、手柄視圖冇開嗰陣先理） ──
const OBJECT_TRACK_IGNORE_SEL =
  "#fabRow, #cameraZoomVertical, #cameraResolutionWrap, #joystickAutoReturnWrap, #crosshairPad, #cameraPadCanvas, select, button, input, label";

function objectTrackSelectHide() {
  objectTrackSel = null;
  const sel = document.getElementById("objectTrackSelBox");
  if (sel) sel.classList.remove("on");
}

function objectTrackPadVisible() {
  const cv = document.getElementById("cameraPadCanvas");
  return !!(cv && cv.style.display !== "none");
}

function objectTrackLocalPoint(e) {
  const viewport = document.getElementById("cameraViewport");
  const r = viewport.getBoundingClientRect();
  return { x: e.clientX - r.left, y: e.clientY - r.top };
}

function objectTrackSelectDraw() {
  const sel = document.getElementById("objectTrackSelBox");
  if (!sel || !objectTrackSel) return;
  const s = objectTrackSel;
  sel.style.left = Math.min(s.x0, s.x1) + "px";
  sel.style.top = Math.min(s.y0, s.y1) + "px";
  sel.style.width = Math.abs(s.x1 - s.x0) + "px";
  sel.style.height = Math.abs(s.y1 - s.y0) + "px";
  sel.classList.add("on");
}

function objectTrackOnDown(e) {
  if (!objectTrackRunning || objectTrackPadVisible()) return;
  if (e.button !== undefined && e.button !== 0) return;
  if (e.target && e.target.closest && e.target.closest(OBJECT_TRACK_IGNORE_SEL)) return;
  const p = objectTrackLocalPoint(e);
  objectTrackSel = { x0: p.x, y0: p.y, x1: p.x, y1: p.y, id: e.pointerId };
  try { e.currentTarget.setPointerCapture(e.pointerId); } catch (err) {}
  e.preventDefault();
}

function objectTrackOnMove(e) {
  if (!objectTrackSel || objectTrackSel.id !== e.pointerId) return;
  const p = objectTrackLocalPoint(e);
  objectTrackSel.x1 = p.x;
  objectTrackSel.y1 = p.y;
  // 太細嘅拖動當係點擊，唔畫框（免閃一下）
  if (Math.abs(p.x - objectTrackSel.x0) >= OBJECT_TRACK_DRAG_MIN_PX ||
      Math.abs(p.y - objectTrackSel.y0) >= OBJECT_TRACK_DRAG_MIN_PX) {
    objectTrackSelectDraw();
  }
}

async function objectTrackOnUp(e) {
  const s = objectTrackSel;
  if (!s || s.id !== e.pointerId) return;
  const p = objectTrackLocalPoint(e);
  s.x1 = p.x;
  s.y1 = p.y;
  objectTrackSelectHide();
  if (e.type === "pointercancel") return;
  const cr = objectTrackContentRect();
  if (!cr || !cr.width || !cr.height) return;
  const dragW = Math.abs(s.x1 - s.x0);
  const dragH = Math.abs(s.y1 - s.y0);
  let nx, ny, nw, nh;
  if (dragW < OBJECT_TRACK_DRAG_MIN_PX && dragH < OBJECT_TRACK_DRAG_MIN_PX) {
    // 點一下＝以該點為中心嘅預設大小框
    nw = OBJECT_TRACK_TAP_W;
    nh = OBJECT_TRACK_TAP_H;
    nx = (s.x1 - cr.left) / cr.width - nw / 2;
    ny = (s.y1 - cr.top) / cr.height - nh / 2;
  } else {
    nx = (Math.min(s.x0, s.x1) - cr.left) / cr.width;
    ny = (Math.min(s.y0, s.y1) - cr.top) / cr.height;
    nw = dragW / cr.width;
    nh = dragH / cr.height;
  }
  // 夾返喺畫面入面（框可以拖出黑邊／畫面外）
  const x0 = Math.max(0, Math.min(1, nx));
  const y0 = Math.max(0, Math.min(1, ny));
  const x1 = Math.max(0, Math.min(1, nx + nw));
  const y1 = Math.max(0, Math.min(1, ny + nh));
  const bw = x1 - x0;
  const bh = y1 - y0;
  if (bw < 0.04 || bh < 0.04) return; // 太細（後端最細約 8 像素），忽略
  try {
    const json = await Alpha2Api.objectTrackConfig({ bx: x0, by: y0, bw: bw, bh: bh });
    objectTrackRenderStatus(json);
  } catch (err) {
    showError(t("object_track_label"), err);
  }
}

function objectTrackSelectInit() {
  const viewport = document.getElementById("cameraViewport");
  if (!viewport || viewport.dataset.objectSelectInit) return;
  viewport.dataset.objectSelectInit = "1";
  viewport.addEventListener("pointerdown", objectTrackOnDown);
  viewport.addEventListener("pointermove", objectTrackOnMove);
  viewport.addEventListener("pointerup", objectTrackOnUp);
  viewport.addEventListener("pointercancel", objectTrackOnUp);
}

// ---------------- 追蹤選項列（共用「行走跟隨」） ----------------
//
// 人臉／顏色／物件三個追蹤共用同一粒「行走跟隨」開關，按當前行緊嘅模式送去對應 config
// （後端三個 Center 共用 WalkFollower，各自一份 walkFollow 狀態；停咗追蹤即復位成 false）。
function trackActiveMode() {
  if (faceTrackRunning) return "face";
  if (colorTrackRunning) return "color";
  if (objectTrackRunning) return "object";
  return null;
}

function trackOptionsUpdate() {
  const mode = trackActiveMode();
  const row = document.getElementById("trackOptionsRow");
  if (row) {
    const show = !!mode;
    if ((row.style.display !== "none") !== show) row.style.display = show ? "" : "none";
  }
  const hint = document.getElementById("trackHint");
  if (hint) {
    let msg = "";
    if (mode === "face") msg = t("face_track_hint");
    else if (mode === "object") msg = objectTrackHintText();
    if (hint.textContent !== msg) hint.textContent = msg;
    const want = msg ? "" : "none";
    if (hint.style.display !== want) hint.style.display = want;
  }
  const tg = document.getElementById("walkFollowToggle");
  if (tg) {
    const on = mode === "face" ? faceTrackWalkFollow
      : mode === "color" ? colorTrackWalkFollow
      : mode === "object" ? objectTrackWalkFollow : false;
    if (tg.checked !== on) tg.checked = on;
  }
}

async function trackWalkFollowChanged() {
  const toggle = document.getElementById("walkFollowToggle");
  const on = toggle ? toggle.checked : false;
  const mode = trackActiveMode();
  if (!mode) { if (toggle) toggle.checked = false; return; }
  const cur = mode === "face" ? faceTrackWalkFollow
    : mode === "color" ? colorTrackWalkFollow : objectTrackWalkFollow;
  // 平地＋清空＋有人睇住先開：開即部機會行，browser confirm 閘一閘（誤掂唔會即刻行）。
  if (on && !cur) {
    const ok = window.confirm
      ? window.confirm(t("walk_follow_confirm", { label: t("walk_follow_label") }))
      : true;
    if (!ok) { if (toggle) toggle.checked = false; return; }
  }
  try {
    if (mode === "face") faceTrackRenderStatus(await Alpha2Api.faceTrackConfig({ walkFollow: on }));
    else if (mode === "color") colorTrackRenderStatus(await Alpha2Api.colorTrackConfig({ walkFollow: on }));
    else objectTrackRenderStatus(await Alpha2Api.objectTrackConfig({ walkFollow: on }));
  } catch (e) {
    showError(t("walk_follow_label"), e);
    if (toggle) toggle.checked = cur; // 失敗還原
  }
}

// ---------------- 相機模式 5選1 ----------------
//
// 功能鍵／手柄／人臉追蹤／顏色追蹤／物件追蹤 互斥：同一時間最多開一個（可以全熄）。
// 開一個即閂晒其餘；追蹤之間後端亦互斥（CameraApi 層 start 一邊即停另外兩邊）。
// 後端／其他客戶端（手機、手柄）起咗追蹤，status 輪詢到 running 亦會即閂功能鍵／手柄
// （cameraModeEnforce），開頁時對齊後端狀態都靠呢個。
let cameraModeQuietUntil = 0; // 呢個時間前唔強制（自己切換中，避免在途輪詢嘅舊 running 打返轉頭）

function cameraModeQuiet(ms) { cameraModeQuietUntil = Date.now() + (ms || 2500); }

// 閂功能鍵＋手柄（各自走返原本嘅開關處理，行為同人手撳一樣）。
function cameraModeLeaveOverlays() {
  const f = document.getElementById("featureEnabled");
  if (f && f.checked) {
    f.checked = false;
    try { cameraFeatureToggle(); } catch (e) {}
  }
  const p = document.getElementById("cameraPadToggle");
  if (p && p.checked) {
    p.checked = false;
    try { cameraPadToggleView(); } catch (e) {}
  }
}

// 追蹤要開：先閂功能鍵／手柄。
function cameraModeBegin() {
  cameraModeQuiet();
  cameraModeLeaveOverlays();
}

// 輪詢／事件見到追蹤行緊：對齊 UI（自己切換中唔做）。
function cameraModeEnforce() {
  if (Date.now() < cameraModeQuietUntil) return;
  cameraModeLeaveOverlays();
}

// 功能鍵／手柄開咗：停晒行緊嘅追蹤（逐個走返各自嘅開關處理＝發 stop，框同輪詢一齊清）。
async function cameraModeStopTrackers() {
  cameraModeQuiet();
  const list = [
    ["faceTrackToggle", function () { return faceTrackRunning; }, faceTrackToggleChanged],
    ["colorTrackToggle", function () { return colorTrackRunning; }, colorTrackToggleChanged],
    ["objectTrackToggle", function () { return objectTrackRunning; }, objectTrackToggleChanged],
  ];
  for (const item of list) {
    const tg = document.getElementById(item[0]);
    if ((tg && tg.checked) || item[1]()) {
      if (tg) tg.checked = false;
      try { await item[2](); } catch (e) {}
    }
  }
  cameraModeQuiet(1500);
}




