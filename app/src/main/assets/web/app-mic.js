// Open Alpha2 — client logic (app-mic.js)
// 內容: 聽機械人麥克風 (WAV chunk 串流播放)、相機全螢幕。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

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
// ctx.sampleRate，將 16kHz 來源 PCM resample 做嗰個 rate 先入 buffer。
//
// 最終方案: ScriptProcessorNode。雖然官方已經 deprecate (建議用
// AudioWorklet), 但呢個 API 喺 Web Audio API 推出初期 (2011年) 已經存在,
// Chromium 39 呢類老舊 WebView 廣泛支援 - 呢個project 嘅目標裝置係 Android 5.1
// 定死咗嘅老 WebView, 冇得指望日後升級, 呢度嘅 "deprecated" 對呢部機嚟講唔
// 適用。ScriptProcessorNode 行喺主 thread (冇 AudioWorklet 嗰種獨立 audio
// thread 嘅實時保證), 但完全冇 decodeAudioData() 嗰種 async decode 步驟。

let micListening = false;
let micAbortController = null;
let micAudioContext = null;
let micScriptNode = null;
// micMuted 恆 false（mic-listen 永遠唔 mute）。
let micMuted = false;

// Ring buffer 本身 (Float32, 已經 resample 做 AudioContext 實際 sample rate),
// 由 feedPcmToBuffer() 寫入、micAudioProcessCallback() 讀出。
let micRingBuffer = null;
let micRingCapacity = 0;
let micRingWriteIdx = 0;
let micRingReadIdx = 0;
let micRingAvailable = 0; // 幾多個未播放嘅 sample 喺 buffer 度
// Resample 用嘅 fractional position - 由於來源 rate (16kHz) 同目標 rate (native,
// 例如 44100) 通常唔係整數倍數, 用一個跨越多次 feedPcmToBuffer() call 都會保留
// 落嚟嘅 fractional position, 令連續幾個 chunk 之間嘅 resample 唔會因為除唔盡而
// 產生 累積誤差/接口爆音。
let micResampleFracPos = 0;

// WAV header 固定 44 bytes (PCM, mono, 16-bit - 同 AudioController.java 送出嚟嘅
// 格式一致), 用嚟由每個 chunk 度分開 header 同真正嘅 PCM data。
const WAV_HEADER_BYTES = 44;

// Server 端 (AudioController.java) 送出嚟嘅 PCM 嘅實際 sample rate - 呢個係
// "來源" rate, 唔係 AudioContext 實際運作嘅 rate (見上面成段 comment解釋點解
// 兩者可能唔一樣)。要同 AudioController.SAMPLE_RATE_HZ 一致。
const MIC_SOURCE_SAMPLE_RATE = 16000;

// Ring buffer 容量上限 (samples, 以 AudioContext 實際 sample rate 計) - 呢個係
// buffer array 本身嘅 array size (絕對唔可以俾 write 溢出), 用 2 秒咁大隻係為咗
// 應付突發嘅 network burst, 唔代表想俾實際聽到嘅 delay 去到咁耐 - 見
// MIC_MAX_LATENCY_SEC 先至係「想聽到幾耐延遲」嘅目標。
const MIC_RING_BUFFER_SEC = 2;

// 想聽到嘅最大延遲 - 每次寫入新 chunk 之後, 主動將 buffer 水位削返落嚟呢個
// 目標之下 (dropping 最舊嘅 sample), 令實際聽到嘅 delay 長期都企喺呢個水平,
// 唔會等到 MIC_RING_BUFFER_SEC (array size 上限) 先被動咁頂住。
//
// 呢個數值一定要大過單個 server chunk 嘅時長 (CHUNK_MS=500ms, 見
// AudioController.java) - 否則一個 chunk 正常噉一次過湧入 buffer 就已經令
// 水位衝過閾值, 逢 chunk 到達都會誤觸發削減, 表現為斷斷續續 (實測驗證過:
// 0.3 秒會逢 chunk 必削、每秒斷幾次)。用 1.2 秒 (CHUNK_MS 嘅 2.4 倍), 留返夠
// 緩衝俾正常嘅 chunk-to-chunk 到達節奏波動, 代價係聽到嘅 delay 都跟住有
// 1.2 秒左右 (加埋起始 mic 初始化嗰 ~0.7 秒一次性 delay, 總延遲大約 1.8-2.2
// 秒) - 呢個係喺「唔斷」同「delay 短」之間嘅取捨, 實測證實過細嘅閾值會斷,
// 如果想再減 delay, 需要諗過另一套機制 (例如喺消耗端而唔係寫入端 check 水位),
// 唔應該淨係再細調呢個數值。
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
  // 唔傳 {sampleRate: ...} option - 呢個 option 喺目標 WebView (Chromium 39)
  // 完全冇效, 傳咗都只係徒添一個誤導人嘅假象。用返瀏覽器/裝置嘅 native sample
  // rate, 落面 feedPcmToBuffer() 會自己 resample 去就佢。
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  micAudioContext = ctx;

  const ringCapacity = Math.ceil(ctx.sampleRate * MIC_RING_BUFFER_SEC);
  micRingBuffer = new Float32Array(ringCapacity);
  micRingCapacity = ringCapacity;
  micRingWriteIdx = 0;
  micRingReadIdx = 0;
  micRingAvailable = 0;
  micResampleFracPos = 0;

  // bufferSize 4096: 大到唔會令主 thread 太頻密咁被 onaudioprocess 中斷, 細到
  // 唔會令延遲太明顯。0 個 input channel (純播放, 唔錄音), 1 個 output
  // channel (mono, 同 AudioController.java 送出嚟嘅格式一致)。
  micScriptNode = ctx.createScriptProcessor(4096, 0, 1);
  micScriptNode.onaudioprocess = micAudioProcessCallback;
  micScriptNode.connect(ctx.destination);

  micAbortController = new AbortController();
  runMicStreamLoop(micAbortController.signal);
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
  // 呢度冇 race 問題 (冇再觸發 setWakeState), 照舊由前端主動熄燈 - server 端
  // handleMicStream() 嘅 finally 區塊都有一個保底 stop (應付連線中斷冇經呢個掣嘅情況)。
  setListenLed(false);
}

/** 聽機械人(🎧)完結時頭/眼 LED 熄返 - alpha2-only, 同 setRecordingLed()/tilt LED/
 *  flashCaptureLed() 一致嘅做法。開燈喺 server 端做 (見上面 comment)。 */
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
  const ratio = MIC_SOURCE_SAMPLE_RATE / ctx.sampleRate; // 幾多個來源 sample 相當於 1 個輸出 sample
  // 用返上次跨 chunk 保留落嚟嘅 fractional position (見 micResampleFracPos 宣告
  // 嗰段 comment), 由 -ratio 度開始, 保證第一個輸出 sample 都經過同一套邏輯計算,
  // 唔使獨立寫一次「第一個 sample 點計」嘅特殊case。
  let srcPos = micResampleFracPos;
  const outSamples = [];
  while (srcPos < sampleCount) {
    const idx = Math.floor(srcPos);
    // Nearest-neighbor (取整數位置嗰個 sample, 唔做 linear interpolation) -
    // 對語音嚟講已經夠用, 遠比 windowed-sinc resampler 少 code、少運算。
    const clampedIdx = Math.min(idx, sampleCount - 1);
    const int16 = view.getInt16(clampedIdx * 2, true); // true = little-endian
    outSamples.push(int16 / 32768);
    srcPos += ratio;
  }
  micResampleFracPos = srcPos - sampleCount; // 帶去下一個 chunk 用

  for (let i = 0; i < outSamples.length; i++) {
    if (micRingAvailable >= micRingCapacity) {
      // Ring buffer array 本身爆晒 (絕對唔應該發生 - 落面主動削減邏輯應該老早
      // 已經頂住個水位, 呢度純粹係最後一度防線) - 犧牲最舊嗰個 sample, 保持
      // array 唔會 index 溢出。
      micRingReadIdx = (micRingReadIdx + 1) % micRingCapacity;
      micRingAvailable--;
    }
    micRingBuffer[micRingWriteIdx] = outSamples[i];
    micRingWriteIdx = (micRingWriteIdx + 1) % micRingCapacity;
    micRingAvailable++;
  }

  // 主動削減 backlog: 如果水位已經超過 MIC_MAX_LATENCY_SEC 想要嘅目標, 即刻
  // drop 最舊嗰批 sample 落返去目標水位, 而唔係等佢慢慢爬到 MIC_RING_BUFFER_SEC
  // (array size 上限) 先俾動咁頂住。呢度先係真正決定用家實際聽到幾耐延遲嘅
  // 機制 - 見 MIC_MAX_LATENCY_SEC 宣告嗰段解釋 (包括點解閾值一定要大過
  // CHUNK_MS, 同呢個取捨嘅實測依據)。
  const maxLatencySamples = Math.floor(ctx.sampleRate * MIC_MAX_LATENCY_SEC);
  if (micRingAvailable > maxLatencySamples) {
    const toDrop = micRingAvailable - maxLatencySamples;
    micRingReadIdx = (micRingReadIdx + toDrop) % micRingCapacity;
    micRingAvailable -= toDrop;
  }
}

/** ScriptProcessorNode 嘅 onaudioprocess callback - 由瀏覽器 audio graph 定時
 *  觸發 (由 bufferSize 決定觸發頻率, 大約每 bufferSize/sampleRate 秒一次,
 *  例如 4096/44100 ≈ 93ms), 喺主 thread 執行。由 ring buffer 度攞夠數嘅
 *  sample 出嚟填 output, buffer 唔夠就輸出靜音 (underrun), 唔會好似
 *  decodeAudioData() 嗰套做法咁「跳前一截」。*/
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

