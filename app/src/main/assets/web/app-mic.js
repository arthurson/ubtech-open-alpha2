// Open Alpha2 — client logic (app-mic.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 聽機械人麥克風 (WAV chunk 串流播放)、walkie-talkie (已永久停用)、downsample、相機全螢幕。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// ---------------- Mic: listen to the robot's microphone ----------------
//
// Unlike the camera's <img src="/stream/camera">, which lets the browser handle
// multipart/x-mixed-replace natively, audio has no equivalent built-in tag/MIME
// handling - so this reads the /stream/mic response manually via fetch()+
// ReadableStream, splits on the "--boundary" markers itself, and decodes each
// self-contained WAV chunk with the Web Audio API. Chunks are scheduled to play
// back-to-back using an AudioContext's own clock (nextPlayTime) rather than "play
// immediately on decode" - decodeAudioData is asynchronous and chunks can finish
// decoding slightly out of step with network arrival, so playing on a shared timeline
// is what keeps chunks gapless and in order instead of overlapping or stuttering.

let micListening = false;
let micAbortController = null;
let micAudioContext = null;
let micNextPlayTime = 0;
// Muted (not stopped) while push-to-talk is active - see startTalk()/stopTalk(). The
// /stream/mic connection and AudioController on the Android side keep running exactly
// as before; only the browser-side *playback* of incoming mic chunks is suppressed.
// This is what actually breaks the echo loop reported in logcat_2026-07-01_03-26-09:
// talking sends audio to the robot's AudioTrack/speaker, which then gets picked straight
// back up by the robot's own AudioRecord/mic (no acoustic isolation between the
// speaker and mic on this hardware) and streamed back to the browser as "the robot
// talking" - which is really just an instant echo of what you just said. Muting
// playback while transmitting, mirroring a real half-duplex walkie-talkie (only one
// direction of audio "counts" at a time), removes that loop entirely rather than
// trying to cancel it after the fact.
let micMuted = false;

// Pending raw WAV chunks waiting to be decoded+played, processed strictly one at a
// time by micDrainLoop() (see startMicListen()). runMicStreamLoop()'s read loop used
// to call playWavChunk() directly without awaiting it - since decodeAudioData() is
// itself async and can take a non-trivial amount of time, that let multiple decode
// calls run concurrently. If decoding falls even slightly behind how fast chunks
// arrive (a very plausible steady-state on constrained hardware, not just a transient
// glitch), the number of in-flight decodes only grows over time and each one finishes
// later and later relative to when its audio was actually captured - this is what was
// producing the reported "越聽越慢" (progressively growing ~3s lag): the growing delay
// lived entirely in the decode pipeline, which MIC_MAX_SCHEDULED_LAG_SEC's snap-forward
// logic in playWavChunk() never saw, since that logic only bounds the *scheduled
// playback* of chunks that have ALREADY finished decoding. Concurrent decodes could
// also complete out of their original order, which would have played chunks
// out-of-sequence on top of the growing lag.
const micPendingChunks = [];
// Cap on how many not-yet-decoded chunks are allowed to queue up - if the queue grows
// past this (decode+playback is falling behind arrival), the OLDEST pending chunk(s)
// are dropped so the queue can never silently grow the end-to-end lag without bound;
// a chunk this old is more useful skipped than dutifully played several seconds late.
const MIC_MAX_PENDING_CHUNKS = 3;

function micElements() {
  return {
    btn: document.getElementById("micListenFab"),
  };
}

function toggleMicListen() {
  if (micListening) {
    stopMicListen();
  } else {
    startMicListen();
  }
}

function startMicListen() {
  micListening = true;
  const btn = micElements().btn;
  if (btn) btn.classList.add("listening");
  micAudioContext = new (window.AudioContext || window.webkitAudioContext)();
  micNextPlayTime = 0;
  micPendingChunks.length = 0;
  micAbortController = new AbortController();
  runMicStreamLoop(micAbortController.signal);
  micDrainLoop(micAbortController.signal);
  // 頭/眼 LED 綠燈長開改咗喺 server 端做 (見 MainActivity#handleMicStream/
  // releaseMicForAudioIo 嘅 javadoc) - 一定要等機身自己 speech_SetMIC(true) 嘅
  // 300ms release 流程完咗先送 LED 命令, 否則會同機身自己嘅 setWakeState 副作用
  // (自動熄耳朵 LED 嘅 LED_ACTION 廣播) 有 race, 導致「有時著,有時唔著」。
  // 如果喺呢度(前端)一開波就送, 個時序就同機身嗰個廣播返轉頭爭, 冧返轉個問題。
}

function stopMicListen() {
  micListening = false;
  const btn = micElements().btn;
  if (btn) btn.classList.remove("listening");
  micPendingChunks.length = 0;
  if (micAbortController) {
    micAbortController.abort();
    micAbortController = null;
  }
  if (micAudioContext) {
    micAudioContext.close();
    micAudioContext = null;
  }
  // 呢度冇 race 問題 (冇再觸發 setWakeState), 照舊由前端主動熄燈 - server 端
  // handleMicStream() 嘅 finally 區塊都有一個保底 stop (應付連線中斷冇經呢個掣嘅情況)。
  setListenLed(false);
}

/** 聽機械人(🎧)完結時頭/眼 LED 熄返 - alpha2-only, 同 setRecordingLed()/tilt LED/
 *  flashCaptureLed() 一致嘅做法。開燈嗰部分已經搬咗去 server 端 (見上面 comment)。 */
function setListenLed(on) {
  if (currentBackend !== "alpha2") return;
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    api("led/head/set", { preset: "long", color: 2, brightness: headBrightness });
    api("led/eye/set", { preset: "long", color: 2, brightness: eyeBrightness });
  } else {
    api("led/head/set", { preset: "stop" });
    api("led/eye/set", { preset: "stop" });
  }
}

/** Reads /stream/mic and plays each WAV chunk as it arrives; reconnects automatically
 *  (matching the camera stream's own reconnect-on-error behavior) unless the user has
 *  since stopped listening. */
async function runMicStreamLoop(signal) {
  const boundaryMarker = "--opensdktestpanelaudio";
  try {
    const resp = await fetch("/stream/mic?t=" + Date.now(), { signal: signal });
    if (!resp.ok || !resp.body) {
      throw new Error("stream request failed: " + resp.status);
    }
    const reader = resp.body.getReader();
    let buffer = new Uint8Array(0);

    while (micListening) {
      const { value, done } = await reader.read();
      if (done) break;

      const combined = new Uint8Array(buffer.length + value.length);
      combined.set(buffer, 0);
      combined.set(value, buffer.length);
      buffer = combined;

      // Extract every complete part currently in the buffer; a part is
      // "--boundary\r\nheaders\r\n\r\n<wav bytes>\r\n" - find each header/body split by
      // the blank-line marker, and each part's end by the next boundary marker.
      while (true) {
        const text = bytesToLatin1String(buffer);
        const boundaryIdx = text.indexOf(boundaryMarker);
        if (boundaryIdx < 0) break;
        const headerEnd = text.indexOf("\r\n\r\n", boundaryIdx);
        if (headerEnd < 0) break; // headers not fully arrived yet
        const bodyStart = headerEnd + 4;
        const nextBoundaryIdx = text.indexOf(boundaryMarker, bodyStart);
        if (nextBoundaryIdx < 0) break; // body not fully arrived yet

        // Body ends 2 bytes before the next boundary marker (trailing "\r\n").
        const bodyEnd = nextBoundaryIdx - 2;
        const wavBytes = buffer.slice(bodyStart, Math.max(bodyStart, bodyEnd));
        buffer = buffer.slice(nextBoundaryIdx);

        if (wavBytes.length > 44) { // must have at least a WAV header
          const chunkBuf = wavBytes.buffer.slice(wavBytes.byteOffset,
              wavBytes.byteOffset + wavBytes.byteLength);
          micPendingChunks.push(chunkBuf);
          while (micPendingChunks.length > MIC_MAX_PENDING_CHUNKS) {
            micPendingChunks.shift(); // drop the oldest not-yet-decoded chunk
          }
        }
      }
    }
  } catch (e) {
    if (signal.aborted) return; // user stopped listening - not an error
    console.warn("Mic stream ended: " + e.message);
  }
  if (micListening) {
    // Unexpected disconnect while the user still wants to listen - reconnect.
    setTimeout(function () {
      if (micListening) runMicStreamLoop(signal);
    }, 1000);
  }
}

function bytesToLatin1String(bytes) {
  // Latin-1 (not UTF-8) decoding: this is only used to *locate* the ASCII boundary
  // markers and header text by byte offset, not to interpret the binary WAV payload
  // as text - UTF-8 decoding could merge/split multi-byte sequences and throw off the
  // byte offsets used to slice the original buffer.
  let s = "";
  for (let i = 0; i < bytes.length; i++) {
    s += String.fromCharCode(bytes[i]);
  }
  return s;
}

// If the playback schedule has drifted this far ahead of real time, skip the backlog
// instead of playing it out - see playWavChunk()'s comment for why this is what
// actually bounds end-to-end delay, rather than just the server-side queue capacity.
const MIC_MAX_SCHEDULED_LAG_SEC = 0.3;

/** Drains micPendingChunks one at a time - awaits each playWavChunk() fully before
 *  starting the next, so decodeAudioData() calls never run concurrently (see
 *  micPendingChunks' declaration for why that matters: concurrent decodes were the
 *  actual source of the growing multi-second lag, not anything in the playback
 *  scheduling itself). Runs for as long as micListening is true, polling briefly when
 *  the queue is momentarily empty rather than busy-spinning. */
async function micDrainLoop(signal) {
  while (micListening) {
    if (signal.aborted) return;
    const chunk = micPendingChunks.shift();
    if (!chunk) {
      await new Promise(function (resolve) { setTimeout(resolve, 10); });
      continue;
    }
    await playWavChunk(chunk);
  }
}

async function playWavChunk(arrayBuffer) {
  if (!micAudioContext) return;
  let audioBuffer;
  try {
    audioBuffer = await micAudioContext.decodeAudioData(arrayBuffer);
  } catch (e) {
    console.warn("Failed to decode audio chunk: " + e.message);
    return;
  }
  if (!micAudioContext || !micListening) return; // stopped while decoding

  if (micMuted) {
    // Still advance the schedule as if this chunk had played, so that when talking
    // stops and playback resumes, MIC_MAX_SCHEDULED_LAG_SEC's snap-forward logic below
    // finds a huge backlog "in the past" and immediately snaps to "now" - rather than
    // trying to dutifully play out several seconds of muted-period audio all at once.
    const now = micAudioContext.currentTime;
    micNextPlayTime = Math.max(now, micNextPlayTime) + audioBuffer.duration;
    return;
  }

  const source = micAudioContext.createBufferSource();
  source.buffer = audioBuffer;
  source.connect(micAudioContext.destination);

  const now = micAudioContext.currentTime;
  if (micNextPlayTime - now > MIC_MAX_SCHEDULED_LAG_SEC) {
    // The schedule has built up more buffered-ahead audio than we want to tolerate as
    // latency (e.g. this chunk decoded unusually fast after a brief earlier stall, or
    // several chunks landed back-to-back) - snap forward to "now" rather than making
    // this chunk wait out the backlog. This trades a small audible skip for keeping
    // the conversation actually live.
    micNextPlayTime = now;
  }
  const startAt = Math.max(now, micNextPlayTime);
  source.start(startAt);
  micNextPlayTime = startAt + audioBuffer.duration;
}

// ---------------- Walkie-talkie: browser mic -> robot speaker ----------------
//
// AudioPlaybackController's javadoc flags this as unverified: whether the robot's
// speaker is reachable through a plain AudioTrack, as opposed to being reserved for a
// dedicated TTS/audio pipeline, isn't known from static analysis alone. playTestTone()
// exists purely to answer that on the physical unit - press it and listen for a 440Hz
// beep from the robot before relying on push-to-talk actually being audible.
//
// Push-to-talk capture uses ScriptProcessorNode rather than AudioWorkletNode - it's
// deprecated but has far broader browser support, which matters more here than using
// the newer API, since this panel's audience is "whatever browser happens to be on
// hand on the local network" rather than a controlled deployment target.
//
// getUserMedia() is requested at whatever sample rate the browser/OS default mic
// gives (typically 44.1kHz or 48kHz) and then downsampled in JS to 8000Hz mono to
// match AudioPlaybackController's expected format - the robot's AudioTrack is
// configured for a fixed sample rate (see AudioPlaybackController.SAMPLE_RATE_HZ) and
// resampling server-side would be considerably more code than doing it once in the
// browser. Lowered from 16000 to 8000 by request, alongside the same change in
// AudioController.java/AudioPlaybackController.java - all three legs of the audio
// pipeline must agree on sample rate or one side effectively resamples by mismatch
// (pitch-shifted/sped-up audio). Halves the uploaded bytes/sec, reducing both network
// load and the size of each /upload/audio POST.

const TALK_TARGET_SAMPLE_RATE = 8000;
let talkStream = null;
let talkAudioContext = null;
let talkProcessorNode = null;
let talkSourceNode = null;
let talkActive = false;

// 2026-08 修正: 呢兩個 function 對應嘅 server 端點 (audio/testtone,
// audio/diagnose - 見 MainActivity.java) 依然實際存在同有效, 但 index.html 冇
// 任何按鈕/入口綁住呢兩個 function (交叉核對成個 index.html 搵唔到
// testToneBtn/audioDiagBtn 呢兩個 id) —— 應該係之前「相機/音效」分類被移除
// (見 blockly-blocks.js/blockly-toolbox.js 對應 comment) 嗰次改動連帶清埋
// 咗按鈕, 但呢兩個 function 本身冇一齊刪。冇入口即係實際上唔會俾人 call 到,
// 但之前完全冇 null check 就直接用 btn.textContent/btn.disabled——一旦將來
// 補返個按鈕但 id 打錯, 或者由 console 手動 call, 就會即刻 TypeError。
// 加返同 toggleMicListen() 一類 function 睇齊嘅 if (btn) guard, 冇按鈕就靜
// 靜哋跳過個 UI 更新, 唔阻住實際 API 呼叫本身。
async function playTestTone() {
  const btn = document.getElementById("testToneBtn");
  const original = btn ? btn.textContent : null;
  if (btn) {
    btn.textContent = "🔔 播放緊…";
    btn.disabled = true;
  }
  try {
    const resp = await hwApi("audio/testtone", {});
    if (!resp || resp.ok === false) {
      alert("測試喇叭失敗: " + (resp && resp.error ? resp.error : "未知錯誤"));
    }
  } finally {
    setTimeout(function () {
      if (btn) {
        btn.textContent = original;
        btn.disabled = false;
      }
    }, 1200);
  }
}

async function runAudioDiagnose() {
  const btn = document.getElementById("audioDiagBtn");
  const original = btn ? btn.textContent : null;
  if (btn) {
    btn.textContent = "🔍 測試緊…";
    btn.disabled = true;
  }
  try {
    const resp = await hwApi("audio/diagnose", {});
    if (resp && resp.results) {
      alert("音頻參數掃描結果:\n\n" + resp.results);
    } else {
      alert("音頻診斷失敗,冧唔到結果");
    }
  } finally {
    if (btn) {
      btn.textContent = original;
      btn.disabled = false;
    }
  }
}

async function startTalk() {
  // 講嘢 (🎤 walkie-talkie 咪) 功能已經永久停用 - 唔止喺 http:// (非安全來源) 先停用,
  // 而係無條件、任何情況都無反應。掣本身喺 disableTalkFabIfInsecureContext() (依家
  // 改咗做無條件 disable, 見下面) 已經 disabled 兼移除曬 pointerdown/keydown 嘅觸發
  // 途徑, 呢度加多一層 guard 係以防萬一有第啲入口(例如 keyboard shortcut)漏咗冇
  // check 就直接 call 到呢個 function。
  return;
}

async function startTalkDisabled_unused() {
  if (talkActive) return;

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    // Browsers only expose getUserMedia on secure contexts (HTTPS or localhost) -
    // navigator.mediaDevices itself is simply undefined on a plain http://<lan-ip>/
    // origin like this panel's. There is no way to work around this in JS; the fix
    // has to be at the transport level (e.g. accessing this page via a tunnel/port
    // forward that presents as localhost to the browser, or serving over HTTPS).
    alert("呢個瀏覽器唔俾用麥克風功能,因為呢版面用緊 http:// (非安全來源)。"
        + "瀏覽器安全限制:麥克風/攝像頭錄音 API 只喺 https:// 或者 localhost 先開放,"
        + "呢個係瀏覽器本身嘅政策,呢個頁面做極都繞唔過。");
    return;
  }

  talkActive = true;
  const fab = document.getElementById("talkFab");
  if (fab) fab.classList.add("talking");
  // Mute incoming mic playback for the duration of talking - see micMuted's
  // declaration above for why (breaks the speaker->mic acoustic echo loop). Muting
  // happens even if mic-listen isn't currently on, which is harmless (playWavChunk()
  // simply isn't called at all in that case).
  micMuted = true;

  try {
    talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    alert("攞唔到麥克風權限: " + e.message);
    talkActive = false;
    micMuted = false;
    if (fab) fab.classList.remove("talking");
    return;
  }

  // Everything below this point (AudioContext/ScriptProcessor setup) is wrapped in its
  // own try/catch too - this used to be unguarded, and a failure here (observed in
  // practice as "Failed to execute 'createMediaStreamSource' on 'AudioContext': ...is
  // not of type 'MediaStream'" - an unhandled promise rejection) left talkActive/
  // micMuted stuck at true forever with no cleanup, since the function just died
  // mid-way through. That meant the talk FAB looked "stuck on" and pressing it again
  // did nothing (startTalk() immediately returns early via the "if (talkActive) return"
  // guard at the top) - "之後就再無辦法發射" - and mic-listen stayed silently muted
  // too. Any failure here now falls through to the same full cleanup stopTalk() does,
  // so the FAB/state always recovers to a normal "off" state instead of wedging.
  try {
    await hwApi("audio/play/start", {});

    talkAudioContext = new (window.AudioContext || window.webkitAudioContext)();
    talkSourceNode = talkAudioContext.createMediaStreamSource(talkStream);
    // bufferSize 4096 at the browser's native rate (~44.1/48kHz) is a common choice
    // that balances latency against how often onaudioprocess fires - small enough for
    // push-to-talk to feel responsive, large enough not to flood /upload/audio with
    // requests every few milliseconds.
    talkProcessorNode = talkAudioContext.createScriptProcessor(4096, 1, 1);

    talkProcessorNode.onaudioprocess = function (evt) {
      // Guard against BOTH talkActive and talkAudioContext directly (not just
      // talkActive) - disconnect()/onaudioprocess=null in stopTalk() don't guarantee
      // an in-flight callback invocation is cancelled before it runs, so a callback
      // that was already scheduled can still fire after stopTalk() has already set
      // talkAudioContext to null (observed in practice as "Cannot read properties of
      // null (reading 'sampleRate')" - an unhandled error from exactly this line
      // reading talkAudioContext.sampleRate after it had been nulled out).
      if (!talkActive || !talkAudioContext) return;
      const inputData = evt.inputBuffer.getChannelData(0); // Float32, -1..1
      const nativeSampleRate = talkAudioContext.sampleRate;
      const pcm16 = downsampleToInt16(inputData, nativeSampleRate, TALK_TARGET_SAMPLE_RATE);
      if (pcm16.length > 0) {
        // Fire-and-forget: push-to-talk audio is latency-sensitive, and awaiting each
        // upload here would serialize network round-trips behind onaudioprocess's own
        // timing, adding lag chunk after chunk.
        fetch("/upload/audio", { method: "POST", body: pcm16.buffer }).catch(function (e) {
          console.warn("Audio upload failed: " + e.message);
        });
      }
    };

    talkSourceNode.connect(talkProcessorNode);
    // ScriptProcessorNode requires being connected to a destination to fire
    // onaudioprocess at all, even though the actual output is discarded via gain 0 -
    // the mic audio must not also play back out of this browser's own speakers.
    const silentGain = talkAudioContext.createGain();
    silentGain.gain.value = 0;
    talkProcessorNode.connect(silentGain);
    silentGain.connect(talkAudioContext.destination);
  } catch (e) {
    console.error("startTalk() setup failed after getUserMedia: " + e.message, e);
    alert("開始講嘢失敗: " + e.message + "。已經自動重設,可以再撳一次咪掣試多次。");
    stopTalk(); // full cleanup - same teardown as a normal stop, safe even if some
                 // pieces (talkProcessorNode/talkSourceNode/talkAudioContext) never
                 // got created before the failure, since stopTalk() null-checks each.
  }
}

function stopTalk() {
  if (!talkActive) return;
  talkActive = false;
  const fab = document.getElementById("talkFab");
  if (fab) fab.classList.remove("talking");
  micMuted = false; // resume mic-listen playback now that we've stopped transmitting

  if (talkProcessorNode) {
    talkProcessorNode.disconnect();
    talkProcessorNode.onaudioprocess = null;
    talkProcessorNode = null;
  }
  if (talkSourceNode) {
    talkSourceNode.disconnect();
    talkSourceNode = null;
  }
  if (talkAudioContext) {
    talkAudioContext.close();
    talkAudioContext = null;
  }
  if (talkStream) {
    talkStream.getTracks().forEach(function (t) { t.stop(); });
    talkStream = null;
  }
  hwApi("audio/play/stop", {});
}

/** Downsamples Float32 PCM from the browser's native mic sample rate to
 *  targetRate (8kHz), converting to Int16 in the same pass to match
 *  AudioPlaybackController's expected wire format. Simple nearest-neighbor
 *  decimation rather than a proper resampling filter - adequate for voice at these
 *  rates, and far less code than a windowed-sinc resampler for a push-to-talk feature
 *  where perfect audio fidelity isn't the goal. */
function downsampleToInt16(float32Data, nativeRate, targetRate) {
  if (targetRate >= nativeRate) {
    // Shouldn't happen (native mic rates are always >= 8kHz in practice), but guard
    // against a divide producing a zero/negative step.
    const out = new Int16Array(float32Data.length);
    for (let i = 0; i < float32Data.length; i++) {
      out[i] = Math.max(-32768, Math.min(32767, Math.round(float32Data[i] * 32767)));
    }
    return out;
  }
  const ratio = nativeRate / targetRate;
  const outLength = Math.floor(float32Data.length / ratio);
  const out = new Int16Array(outLength);
  for (let i = 0; i < outLength; i++) {
    const sample = float32Data[Math.floor(i * ratio)];
    out[i] = Math.max(-32768, Math.min(32767, Math.round(sample * 32767)));
  }
  return out;
}

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

