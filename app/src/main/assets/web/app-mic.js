// Open Alpha2 — client logic (app-mic.js)
// 內容: 聽機械人麥克風 (WAV chunk 串流播放)、相機全螢幕。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Mic: listen to the robot's microphone ----------------
//
// Unlike the camera's <img src="/stream/camera">, which lets the browser handle
// multipart/x-mixed-replace natively, audio has no equivalent built-in tag/MIME
// handling - so this reads the /stream/mic response manually via fetch()+
// ReadableStream, splits on the "--boundary" markers itself to recover each
// self-contained WAV chunk, strips the 44-byte WAV header off each one (see
// WAV_HEADER_BYTES below), resamples the raw 16kHz PCM samples to whatever sample
// rate this AudioContext actually runs at (see MIC_SOURCE_SAMPLE_RATE below), and
// feeds them into a ScriptProcessorNode's ring buffer for continuous playback.
//
// 目標 WebView (RK3288/Android 5.1, Chromium 39) 無 AudioWorklet (Chrome 66 先有)，
// AudioContext {sampleRate} option 亦無效 (Chromium 39 無視，用 native rate)，故 runtime 讀
// ctx.sampleRate，將 16kHz 來源 PCM resample 做那個 rate 先入 buffer。
//
// 最終方案: ScriptProcessorNode。雖然官方已經 deprecate (建議用
// AudioWorklet), 但這個 API 在 Web Audio API 推出初期 (2011年) 已經存在,
// Chromium 39 這類老舊 WebView 廣泛支援 - 這個project 的目標裝置是 Android 5.1
// 固定了的老 WebView, 沒有得指望日後升級, 這裡的 "deprecated" 對這部機來講不
// 適用。ScriptProcessorNode 行在主 thread (沒有 AudioWorklet 那種獨立 audio
// thread 的實時保證), 但完全沒有 decodeAudioData() 那種 async decode 步驟。

let micListening = false;
let micAbortController = null;
let micAudioContext = null;
let micScriptNode = null;
// micMuted 恆 false（mic-listen 永遠不 mute）。
let micMuted = false;

// Ring buffer 本身 (Float32, 已經 resample 做 AudioContext 實際 sample rate),
// 由 feedPcmToBuffer() 寫入、micAudioProcessCallback() 讀出。
let micRingBuffer = null;
let micRingCapacity = 0;
let micRingWriteIdx = 0;
let micRingReadIdx = 0;
let micRingAvailable = 0; // 多少個未播放的 sample 在 buffer 裡
// Resample 用的 fractional position - 由於來源 rate (16kHz) 同目標 rate (native,
// 例如 44100) 通常不是整數倍數, 用一個跨越多次 feedPcmToBuffer() call 都會保留
// 下來的 fractional position, 令連續幾個 chunk 之間的 resample 不會因為除不盡而
// 產生 累積誤差/接口爆音。
let micResampleFracPos = 0;

// WAV header 固定 44 bytes (PCM, mono, 16-bit - 同 AudioController.java 送出來的
// 格式一致), 用來由每個 chunk 裡分開 header 同真正的 PCM data。
const WAV_HEADER_BYTES = 44;

// Server 端 (AudioController.java) 送出來的 PCM 的實際 sample rate - 這個是
// "來源" rate, 不是 AudioContext 實際運作的 rate (見上面成段 comment解釋為什麼
// 兩者可能不一樣)。要同 AudioController.SAMPLE_RATE_HZ 一致。
const MIC_SOURCE_SAMPLE_RATE = 16000;

// Ring buffer 容量上限 (samples, 以 AudioContext 實際 sample rate 計) - 這個是
// buffer array 本身的 array size (絕對不可以給 write 溢出), 用 2 秒這麼大隻是為了
// 應付突發的 network burst, 不代表想給實際聽到的 delay 去到這麼久 - 見
// MIC_MAX_LATENCY_SEC 才是「想聽到多久延遲」的目標。
const MIC_RING_BUFFER_SEC = 2;

// 想聽到的最大延遲 - 每次寫入新 chunk 之後, 主動將 buffer 水位削下來這個
// 目標之下 (dropping 最舊的 sample), 令實際聽到的 delay 長期都站在這個水平,
// 不會等到 MIC_RING_BUFFER_SEC (array size 上限) 先被動地頂住。
//
// 這個數值一定要大過單個 server chunk 的時長 (CHUNK_MS=500ms, 見
// AudioController.java) - 否則一個 chunk 正常噉一次過湧入 buffer 就已經令
// 水位衝過閾值, 逢 chunk 到達都會誤觸發削減, 表現為斷斷續續 (實測驗證過:
// 0.3 秒會逢 chunk 必削、每秒斷幾次)。用 1.2 秒 (CHUNK_MS 的 2.4 倍), 保留夠
// 緩衝給正常的 chunk-to-chunk 到達節奏波動, 代價是聽到的 delay 都跟著有
// 1.2 秒左右 (加上起始 mic 初始化那 ~0.7 秒一次性 delay, 總延遲大約 1.8-2.2
// 秒) - 這個是在「不斷」同「delay 短」之間的取捨, 實測證實過細的閾值會斷,
// 如果想再減 delay, 需要想過另一套機制 (例如在消耗端而不是寫入端 check 水位),
// 不應該僅再細調這個數值。
const MIC_MAX_LATENCY_SEC = 1.2;

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
  // 不傳 {sampleRate: ...} option - 這個 option 在目標 WebView (Chromium 39)
  // 完全沒有效, 傳了都只是徒添一個誤導人的假象。用回瀏覽器/裝置的 native sample
  // rate, 下面 feedPcmToBuffer() 會自己 resample 遷就它。
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  micAudioContext = ctx;

  const ringCapacity = Math.ceil(ctx.sampleRate * MIC_RING_BUFFER_SEC);
  micRingBuffer = new Float32Array(ringCapacity);
  micRingCapacity = ringCapacity;
  micRingWriteIdx = 0;
  micRingReadIdx = 0;
  micRingAvailable = 0;
  micResampleFracPos = 0;

  // bufferSize 4096: 大到不會令主 thread 太頻密這麼被 onaudioprocess 中斷, 細到
  // 不會令延遲太明顯。0 個 input channel (純播放, 不錄音), 1 個 output
  // channel (mono, 同 AudioController.java 送出來的格式一致)。
  micScriptNode = ctx.createScriptProcessor(4096, 0, 1);
  micScriptNode.onaudioprocess = micAudioProcessCallback;
  micScriptNode.connect(ctx.destination);

  micAbortController = new AbortController();
  runMicStreamLoop(micAbortController.signal);
  // 頭/眼 LED 綠燈長開改了在 server 端做 (見 MainActivity#handleMicStream/
  // releaseMicForAudioIo 的 javadoc) - 一定要等機身自己 speech_SetMIC(true) 的
  // 300ms release 流程完了先送 LED 命令, 否則會同機身自己的 setWakeState 副作用
  // (自動熄耳朵 LED 的 LED_ACTION 廣播) 有 race, 導致「有時著,有時不著」。
  // 如果在這裡(前端)一開始就送, 個時序就同機身那個廣播回頭爭, 重現個問題。
}

function stopMicListen() {
  micListening = false;
  const btn = micElements().btn;
  if (btn) btn.classList.remove("listening");
  if (micAbortController) {
    micAbortController.abort();
    micAbortController = null;
  }
  if (micScriptNode) {
    micScriptNode.disconnect();
    micScriptNode.onaudioprocess = null;
    micScriptNode = null;
  }
  if (micAudioContext) {
    micAudioContext.close();
    micAudioContext = null;
  }
  micRingBuffer = null;
  // 這裡沒有 race 問題 (沒有再觸發 setWakeState), 照舊由前端主動熄燈 - server 端
  // handleMicStream() 的 finally 區塊都有一個保底 stop (應付連線中斷沒有經這個按鈕的情況)。
  setListenLed(false);
}

/** 聽機械人(🎧)完結時頭/眼 LED 熄返 - alpha2-only, 同 setRecordingLed()/tilt LED/
 *  flashCaptureLed() 一致的做法。開燈在 server 端做 (見上面 comment)。 */
function setListenLed(on) {
  if (currentBackend !== "alpha2") return;
  if (on) {
    const headBrightness = document.getElementById("headBrightness").value;
    const eyeBrightness = document.getElementById("eyeBrightness").value;
    Alpha2Api.ledHeadSet( { preset: "long", color: 2, brightness: headBrightness });
    Alpha2Api.ledEyeSet( { preset: "long", color: 2, brightness: eyeBrightness });
  } else {
    Alpha2Api.ledHeadSet( { preset: "stop" });
    Alpha2Api.ledEyeSet( { preset: "stop" });
  }
}

/** Reads /stream/mic and plays each WAV chunk as it arrives; reconnects automatically
 *  (matching the camera stream's own reconnect-on-error behavior) unless the user has
 *  since stopped listening. */
/** Reads /stream/mic and feeds each chunk's raw PCM samples into the ring buffer for
 *  playback; reconnects automatically (matching the camera stream's own
 *  reconnect-on-error behavior) unless the user has since stopped listening. */
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

        if (wavBytes.length > WAV_HEADER_BYTES) {
          feedPcmToBuffer(wavBytes);
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

/** Strips the 44-byte WAV header off a chunk, converts its 16-bit signed PCM samples
 *  to Float32 (Web Audio's native sample format, range -1..1), resamples from
 *  MIC_SOURCE_SAMPLE_RATE to the AudioContext's actual sampleRate (see the big comment
 *  at the top of this section for why that resample is necessary), and writes the
 *  result into the ring buffer for micAudioProcessCallback() to play out. Runs
 *  synchronously and cheaply (no decoding, just linear scans) - unlike
 *  decodeAudioData(), there's no async step here for timing to drift around. */
function feedPcmToBuffer(wavBytes) {
  if (!micRingBuffer || micMuted) return;
  const pcmBytes = wavBytes.subarray(WAV_HEADER_BYTES);
  // pcmBytes.byteOffset is relative to the underlying ArrayBuffer, which may not be
  // 2-byte aligned after the .slice()/.subarray() calls above - DataView (unlike the
  // Int16Array constructor) works at any byte offset, so it's used here instead of
  // risking a RangeError/misaligned read on some browsers.
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength);
  const sampleCount = Math.floor(pcmBytes.byteLength / 2);
  if (sampleCount === 0) return;

  const ctx = micAudioContext;
  if (!ctx) return;
  const ratio = MIC_SOURCE_SAMPLE_RATE / ctx.sampleRate; // 多少個來源 sample 相當於 1 個輸出 sample
  // 用回上次跨 chunk 保留下來的 fractional position (見 micResampleFracPos 宣告
  // 那段 comment), 由 -ratio 裡開始, 保證第一個輸出 sample 都經過同一套邏輯計算,
  // 不用獨立寫一次「第一個 sample 如何計算」的特殊case。
  let srcPos = micResampleFracPos;
  const outSamples = [];
  while (srcPos < sampleCount) {
    const idx = Math.floor(srcPos);
    // Nearest-neighbor (取整數位置那個 sample, 不做 linear interpolation) -
    // 對語音來講已經夠用, 遠比 windowed-sinc resampler 少 code、少運算。
    const clampedIdx = Math.min(idx, sampleCount - 1);
    const int16 = view.getInt16(clampedIdx * 2, true); // true = little-endian
    outSamples.push(int16 / 32768);
    srcPos += ratio;
  }
  micResampleFracPos = srcPos - sampleCount; // 帶去下一個 chunk 用

  for (let i = 0; i < outSamples.length; i++) {
    if (micRingAvailable >= micRingCapacity) {
      // Ring buffer array 本身爆完 (絕對不應該發生 - 下面主動削減邏輯應該老早
      // 已經頂住個水位, 這裡純粹是最後一道防線) - 犧牲最舊那個 sample, 保持
      // array 不會 index 溢出。
      micRingReadIdx = (micRingReadIdx + 1) % micRingCapacity;
      micRingAvailable--;
    }
    micRingBuffer[micRingWriteIdx] = outSamples[i];
    micRingWriteIdx = (micRingWriteIdx + 1) % micRingCapacity;
    micRingAvailable++;
  }

  // 主動削減 backlog: 如果水位已經超過 MIC_MAX_LATENCY_SEC 想要的目標, 即刻
  // drop 最舊那批 sample 回到目標水位, 而不是等它慢慢爬到 MIC_RING_BUFFER_SEC
  // (array size 上限) 先被動地頂住。這裡才是真正決定用家實際聽到多久延遲的
  // 機制 - 見 MIC_MAX_LATENCY_SEC 宣告那段解釋 (包括為什麼閾值一定要大過
  // CHUNK_MS, 同這個取捨的實測依據)。
  const maxLatencySamples = Math.floor(ctx.sampleRate * MIC_MAX_LATENCY_SEC);
  if (micRingAvailable > maxLatencySamples) {
    const toDrop = micRingAvailable - maxLatencySamples;
    micRingReadIdx = (micRingReadIdx + toDrop) % micRingCapacity;
    micRingAvailable -= toDrop;
  }
}

/** ScriptProcessorNode 的 onaudioprocess callback - 由瀏覽器 audio graph 定時
 *  觸發 (由 bufferSize 決定觸發頻率, 大約每 bufferSize/sampleRate 秒一次,
 *  例如 4096/44100 ≈ 93ms), 在主 thread 執行。由 ring buffer 裡拿夠數的
 *  sample 出來填 output, buffer 不夠就輸出靜音 (underrun), 不會好似
 *  decodeAudioData() 那套做法那樣「跳前一截」。*/
function micAudioProcessCallback(event) {
  const output = event.outputBuffer.getChannelData(0); // mono, single channel
  for (let i = 0; i < output.length; i++) {
    if (micRingAvailable > 0) {
      output[i] = micRingBuffer[micRingReadIdx];
      micRingReadIdx = (micRingReadIdx + 1) % micRingCapacity;
      micRingAvailable--;
    } else {
      output[i] = 0; // underrun: 播靜音, 好過噉播返舊/垃圾 data
    }
  }
}

/** Double-click/double-tap on the viewport toggles native fullscreen on that
 *  element, so the video fills the whole screen. */
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


