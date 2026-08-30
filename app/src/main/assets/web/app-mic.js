// Open Alpha2 — client logic (app-mic.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 聽機械人麥克風 (WAV chunk 串流播放)、walkie-talkie (已永久停用)、downsample、相機全螢幕。
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
// 2026-08 歷史: 曾經試過用 decodeAudioData()+AudioBufferSourceNode 逐個 chunk
// 排程播放, decode 時間唔平均導致播放時間軸間中「跳前」, 表現為冇 error log 但
// 聽感上斷斷續續。之後改用 AudioWorklet, 但用家部機 (RK3288/Android 5.1) 嘅
// system WebView 版本停留喺 Chromium 39, 遠早過 AudioWorklet 面世嘅 Chrome 66,
// ctx.audioWorklet 喺呢部機根本係 undefined, 令 mic 完全打唔開 (連
// /stream/mic 嘅 request 都未發出過)。同時發現 AudioContext 構造函數嘅
// `{sampleRate: ...}` option 都係後期先加入 (Chromium 73062, 大約 2018 年先
// 真正生效), Chromium 39 會直接無視呢個 option, 用返 device native rate 建立
// context - 所以呢度唔假設 AudioContext 會跑喺 16kHz, 而係喺 runtime 讀
// ctx.sampleRate, 將 16kHz 嘅來源 PCM resample 做嗰個 rate 先入 buffer。
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
  // 唔會令延遲太明顯 - 同呢個 project 其他地方 (walkie-talkie 上傳) 用開嘅
  // ScriptProcessorNode bufferSize 一致 (見 downsampleToInt16() 附近嘅
  // startTalk() 舊 code)。0 個 input channel (純播放, 唔錄音), 1 個 output
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
    // 呢個 project 一路都用緊 nearest-neighbor 做 downsample (見
    // downsampleToInt16() javadoc), 呢度保持一致嘅取捨: 對語音嚟講已經夠用,
    // 遠比 windowed-sinc resampler 少 code、少運算。
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
// gives (typically 44.1kHz or 48kHz) and then downsampled in JS to 16000Hz mono to
// match AudioPlaybackController's expected format - the robot's AudioTrack is
// configured for a fixed sample rate (see AudioPlaybackController.SAMPLE_RATE_HZ) and
// resampling server-side would be considerably more code than doing it once in the
// browser. 2026-08 改返 16000 (由 8000 升返上): 當初落 8000 淨係為咗同
// AudioController.java/AudioPlaybackController.java 對齊 - 但 walkie-talkie
// (startTalk()) 已經永久停用 (見下面 startTalk() 直接 return), 用戶指定三個檔案
// 一齊拉返上 16000 保持一致, 即使 walkie-talkie 依家實際用唔到。三腳 (mic-listen
// playback, talk upload, 呢個 recording) 必須繼續同一 sample rate, 唔係就會一邊
// 有效變成 mismatch resample (變音)。

const TALK_TARGET_SAMPLE_RATE = 16000;
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
      alert("音訊診斷失敗,得不到結果");
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
    alert("這個瀏覽器不允許使用麥克風功能,因為這個頁面用的是 http:// (非安全來源)。"
        + "瀏覽器安全限制:麥克風/攝影機錄音 API 只有在 https:// 或者 localhost 才開放,"
        + "這是瀏覽器本身的政策,這個頁面怎麼做都繞不過。");
    return;
  }

  talkActive = true;
  const fab = document.getElementById("talkFab");
  if (fab) fab.classList.add("talking");
  // Mute incoming mic playback for the duration of talking - see micMuted's
  // declaration above for why (breaks the speaker->mic acoustic echo loop). Muting
  // happens even if mic-listen isn't currently on, which is harmless (feedPcmToBuffer()
  // simply isn't called at all in that case).
  micMuted = true;

  try {
    talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    alert("取得不到麥克風權限: " + e.message);
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

