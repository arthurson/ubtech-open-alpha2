// Open Alpha2 — client logic (app-camera.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 相機直播、影相、錄影、拖拽準星頭部瞄準。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。


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
    // "featureEnabled" is the single master checkbox that now gates all three
    // overlay features together (head-aim joystick pad, mic-listen headphone FAB,
    // talk FAB) - kept under the name crosshairToggle here since all the existing
    // crosshair drag-to-aim code below already reads els.crosshairToggle.
    crosshairToggle: document.getElementById("featureEnabled"),
    fabRow: document.getElementById("fabRow"),
    micListenFab: document.getElementById("micListenFab"),
    talkFab: document.getElementById("talkFab"),
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
  hint.textContent = "設定解像度…";
  await hwApi("camera/resolution", { w: w, h: h });

  cameraLiveRunning = true;
  btn.textContent = "⏸";
  badge.classList.add("on");
  if (placeholder) placeholder.style.display = "none";
  hint.textContent = "連接緊鏡頭串流…";

  connectCameraStream();
  setupCrosshairIfNeeded();
  updateCrosshairVisibility();
}

function stopCameraLive() {
  const els = cameraElements();
  const badge = els.badge, btn = els.btn, placeholder = els.placeholder, viewport = els.viewport;
  cameraLiveRunning = false;
  if (mediaRecorder && mediaRecorder.state === "recording") {
    stopRecording(); // avoid recording against an <img> that's about to be removed
  }
  const img = viewport.querySelector("img");
  if (img) {
    img.src = ""; // stop the browser holding the multipart connection open
    img.remove();
  }
  btn.textContent = "▶";
  badge.classList.remove("on");
  if (placeholder) {
    placeholder.style.display = "";
    placeholder.textContent = "";
  }
  els.hint.textContent = "";
  updateCrosshairVisibility();
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
  link.textContent = kind === "photo" ? "⬇ 下載相片" : "⬇ 下載影片";
  item.appendChild(link);
  list.insertBefore(item, list.firstChild);
}

async function takePhoto() {
  const hint = document.getElementById("cameraStatusHint");
  hint.textContent = "影緊相…";
  try {
    const json = await hwApi("camera/snapshot");
    if (!json.ok) {
      hint.textContent = "影相失敗：" + (json.error || "未知錯誤");
      return;
    }
    // 快門聲由機械人本身出 (見 MainActivity#playShutterCue - 播 "Sirrah" 呢個系統
    // 鈴聲), 唔係喺瀏覽器度合成音效。
    api("camera/shutter_sound");
    flashCaptureLed();
    const byteChars = atob(json.jpegBase64);
    const bytes = new Uint8Array(byteChars.length);
    for (let i = 0; i < byteChars.length; i++) bytes[i] = byteChars.charCodeAt(i);
    const blob = new Blob([bytes], { type: "image/jpeg" });
    const url = URL.createObjectURL(blob);
    const filename = "alpha2-photo-" + Date.now() + ".jpg";
    addCaptureItem("photo", url, filename);
    hint.textContent = "已影相 ✓";
  } catch (e) {
    showError("影相", e);
    hint.textContent = "";
  }
}

/** 影相一刻頭/眼 LED 白燈閃半秒。"flash" preset 本身會不斷循環閃落去唔會自動停
 *  (見 led/head/set 個 p5/p6/p7 timing), 所以要自己計時, 500ms 後主動送返
 *  stop / 或者還原返錄影中/聽聲中嗰個長開色 (見 restoreBaseLed()) - 唔係淨係盲目
 *  stop, 否則影相嗰刻如果啱啱好錄緊影/聽緊聲, 個燈會俾呢下閃燈永久蓋走底層長開色。 */
function flashCaptureLed() {
  const headBrightness = document.getElementById("headBrightness").value;
  const eyeBrightness = document.getElementById("eyeBrightness").value;
  api("led/head/set", { preset: "flash", color: 7, brightness: headBrightness });
  api("led/eye/set", { preset: "flash", color: 7, brightness: eyeBrightness });
  setTimeout(restoreBaseLed, 500);
}

/** 攞返而家「底層」應該長開嘅 LED 狀態 - 錄影中(紅) > 聽機械人中(綠) > 冇(熄)。
 *  影相閃燈完之後、或者其他一次性效果完咗之後, 用嚟還原返正確嘅長開狀態。 */
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
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
  } else {
    api("led/head/set", { preset: "long", color: color, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: color, brightness: eyeBrightness });
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
    hint.textContent = "請先開啟鏡頭先可以錄影";
    return;
  }
  if (!window.MediaRecorder) {
    hint.textContent = "此瀏覽器不支援錄影 (MediaRecorder)";
    return;
  }
  const img = cameraElements().viewport.querySelector("img");
  if (!img) {
    hint.textContent = "搵唔到鏡頭畫面";
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
    showError("錄影", e);
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
    document.getElementById("cameraStatusHint").textContent = "已停止錄影 ✓";
  };
  mediaRecorder.start();
  document.getElementById("recordFab").classList.add("recording");
  hint.textContent = "錄影中…";
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
    api("led/head/set", { preset: "long", color: 1, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: 1, brightness: eyeBrightness });
  } else {
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
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
  const shouldShow = cameraLiveRunning && els.crosshairToggle && els.crosshairToggle.checked;
  if (els.crosshairPad) {
    els.crosshairPad.classList.toggle("active", shouldShow);
  }
  if (els.fabRow) {
    els.fabRow.classList.toggle("active", shouldShow);
  }
  // Unticking the master checkbox (or stopping the camera) must not leave mic-listen
  // or push-to-talk silently running with their FABs hidden - force both off so the
  // control state always matches what's actually visible on screen.
  if (!shouldShow) {
    if (micListening) stopMicListen();
    if (talkActive) stopTalk();
  }
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
    // Uses the same move-time setting as the Servo tab (servoTime()), matching how
    // this worked before - back to the person's original setting rather than a
    // hardcoded joystick-only value.
    const time = servoTime();
    if (panAngle !== null) api("servo/one", { id: 19, angle: panAngle, time: time });
    if (tiltAngle !== null) api("servo/one", { id: 20, angle: tiltAngle, time: time });
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
    cameraElements().crosshairMark.classList.add("dragging");
    pad.setPointerCapture(evt.pointerId);
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
    cameraElements().crosshairMark.classList.remove("dragging");
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
    setKnobPosition(0, 0); // self-centering, like a physical joystick
    sendServoForAxis(0, 0); // return the head to home immediately, not throttled
  }

  pad.addEventListener("pointerdown", onPointerDown);
  pad.addEventListener("pointermove", onPointerMove);
  pad.addEventListener("pointerup", onPointerUp);
  pad.addEventListener("pointercancel", onPointerUp);

  els.crosshairToggle.addEventListener("change", updateCrosshairVisibility);

  // ---- Keyboard control: arrow keys -> servo 19/20 (pan/tilt), held Space -> talk ----
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
  let arrowRepeatTimer = null;

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
    if (evt.key === " " || evt.code === "Space") {
      evt.preventDefault(); // stop Space from also activating a focused button/etc.
      if (!evt.repeat) startTalk(); // ignore the browser's own key-repeat firing, since
                                      // startTalk() is idempotent (talkActive guard) but
                                      // there's no need to call it repeatedly anyway
    }
  });

  els.viewport.addEventListener("keyup", function (evt) {
    if (ARROW_KEYS.indexOf(evt.key) !== -1) {
      heldArrowKeys.delete(evt.key);
      applyArrowKeyState(); // recompute with this key removed - may now be back to (0,0)
      if (heldArrowKeys.size === 0) {
        setKnobPosition(0, 0);
        sendServoForAxis(0, 0); // snap home immediately, matching pointerup's behavior
      }
      return;
    }
    if (evt.key === " " || evt.code === "Space") {
      stopTalk();
    }
  });

  // If the viewport loses keyboard focus entirely (Tab away, click elsewhere) while a
  // key was physically still held down, the corresponding keyup event never reaches
  // this listener - without this, the head or mic could get stuck "on" until some
  // other event happened to reset it.
  els.viewport.addEventListener("blur", function () {
    if (heldArrowKeys.size > 0) {
      heldArrowKeys.clear();
      setKnobPosition(0, 0);
      sendServoForAxis(0, 0);
    }
    if (talkActive) stopTalk();
  });

  // ---- Talk FAB: press-and-hold (mouse/touch), mirroring a physical walkie-talkie's
  // call button - replaces the old dedicated #talkBtn's inline onmousedown/ontouchstart
  // attributes now that the button is generated inside the viewport rather than in the
  // static toolbar row. ----
  if (els.talkFab) {
    els.talkFab.addEventListener("pointerdown", function (evt) {
      evt.preventDefault();
      // Capture the pointer so pointerup still fires on this element even if the
      // finger/mouse drags off the FAB before releasing - without this, dragging off
      // while still pressed would leave talkActive stuck "on" until pointerleave
      // (which covers mouse hover-out, but not always a moved touch-point reliably).
      try { els.talkFab.setPointerCapture(evt.pointerId); } catch (e) { /* ignore */ }
      startTalk();
    });
    els.talkFab.addEventListener("pointerup", function () { stopTalk(); });
    els.talkFab.addEventListener("pointerleave", function () { stopTalk(); });
    els.talkFab.addEventListener("pointercancel", function () { stopTalk(); });
  }
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
    // <img> - that gesture directly conflicts with the talk FAB's press-and-hold
    // (long-pressing near the image could trigger the save menu instead of/alongside
    // starting to talk) and with drag-to-aim on the crosshair pad. draggable=false
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
      hwApi("camera/info", {}).then(function (resp) {
        if (resp && resp.previewWidth) {
          hint.textContent = "實際解像度: " + resp.previewWidth + "x" + resp.previewHeight;
        } else {
          hint.textContent = "";
        }
      }).catch(function () {
        hint.textContent = "";
      });
    };
    img.onerror = function () {
      if (!cameraLiveRunning) return;
      hint.textContent = "鏡頭串流連接失敗,3 秒後重試…";
      setTimeout(function () {
        if (cameraLiveRunning) connectCameraStream();
      }, 3000);
    };
    viewport.appendChild(img);
  }
  img.src = "/stream/camera?t=" + Date.now();
}

