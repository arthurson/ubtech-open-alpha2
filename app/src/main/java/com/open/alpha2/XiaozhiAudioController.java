package com.open.alpha2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.theeasiestway.opus.Constants;
import com.theeasiestway.opus.Opus;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PHASE 2: mic-capture-and-encode + decode-and-playback halves of the XiaoZhi (小智)
 * audio path, using theeasiestway/android-opus-codec (opus.aar in app/libs/) as the
 * Opus JNI layer. Deliberately a separate controller from AudioController/
 * AudioPlaybackController (the walkie-talkie feature's mic/speaker classes) rather than
 * reusing them - three concrete reasons:
 *
 *  1. Different sample rate: the XiaoZhi WebSocket protocol's hello handshake commits
 *     to 16kHz mono, 60ms frames (see XiaozhiClient.buildHelloMessage()); the
 *     walkie-talkie pair runs at 8kHz to keep decodeAudioData() load down in the
 *     browser (see AudioController.java's SAMPLE_RATE_HZ comment) - these can't share
 *     one AudioRecord/AudioTrack instance without one side silently mis-resampling.
 *  2. Different wire format: walkie-talkie sends/receives raw/WAV-wrapped PCM over
 *     HTTP; this sends/receives Opus-encoded frames over the already-open XiaozhiClient
 *     WebSocket.
 *  3. Independent lifecycle: a XiaoZhi voice session and a walkie-talkie session are
 *     conceptually unrelated features that happen to both want the mic - see
 *     micGuard()-style exclusivity note on start() below for how a clash is prevented,
 *     rather than silently allowing two AudioRecord opens to fight over the hardware.
 *
 * Runtime-gated: every entry point that touches this class must first check
 * XiaozhiClient.isAudioSupported() (API 21+) - see MainActivity#handleXiaozhiApi's
 * "mic/start" endpoint. This class itself does not re-check that gate internally, to
 * keep the check in exactly one place.
 *
 * Same open-once-per-session, dedicated-HandlerThread pattern as AudioController/
 * AudioPlaybackController, for the same reasons (AudioRecord/AudioTrack/Opus JNI calls
 * should never run on whatever thread happens to call start()/stop(), and start()/
 * stop() must block synchronously so callers get a definite ready/failed result).
 */
public class XiaozhiAudioController {
    private static final String TAG = "XiaozhiAudioController";

    // Must match the audio_params sent in XiaozhiClient's hello message exactly - the
    // server negotiates its own encoder/decoder against whatever this device declared,
    // so drifting these two locations apart would silently corrupt audio in both
    // directions.
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_DURATION_MS = 60;
    // 16000 samples/sec * 0.060 sec = 960 samples/frame - a supported Opus frame size
    // at 16kHz (Opus frame sizes are 2.5/5/10/20/40/60ms; 60ms is the largest standard
    // size and keeps per-frame protocol/JNI overhead low for a voice-assistant use case
    // where latency in the tens-of-ms range is acceptable). Kept as a plain int for PCM
    // buffer sizing (pcmBuf/pmcQueue math below) even though Opus.encode()/decode()
    // themselves take a Constants.FrameSize object, not this int directly - see
    // FRAME_SIZE below. (Correction from an earlier revision of this file: the
    // (short[], int) overloads of encode()/decode() turned out to be *private* in the
    // compiled opus.aar - see FRAME_SIZE's javadoc.)
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000;
    // The only public encode()/decode() overloads that take a frame-size parameter
    // require a Constants.FrameSize object (via its Companion, since Kotlin companion
    // members aren't static from Java's perspective) - confirmed against the compiled
    // opus.aar's method access flags (javap/constant-pool inspection showed the
    // (short[], int) overloads are marked private, likely an internal helper the
    // library's own public (short[], FrameSize) overload delegates to). _960()
    // matches SAMPLES_PER_FRAME's value (960) exactly.
    private static final Constants.FrameSize FRAME_SIZE = Constants.FrameSize.Companion._960();

    private static final long MAX_SESSION_MS = 5 * 60 * 1000; // same safety cap
                                                                 // rationale as
                                                                 // AudioController/
                                                                 // AudioPlaybackController

    // Jitter buffer for incoming (server->device) decoded PCM, same rationale as
    // AudioPlaybackController.JITTER_BUFFER_CAP_BYTES: bound how much backlog can build
    // up during a network hiccup so playback lag stays small instead of growing
    // unbounded. 16000 bytes/sec (16kHz mono 16-bit) * 0.6s = 9600 bytes.
    private static final int JITTER_BUFFER_CAP_BYTES = SAMPLE_RATE_HZ * 2 * 600 / 1000;
    private static final int PREBUFFER_FRAMES = 3; // same startup-underrun rationale as
                                                      // AudioPlaybackController.PREBUFFER_CHUNKS
    private static final int IDLE_POLL_MS = 10;
    private static final int IDLE_PAUSE_MS = 300;

    /** Implemented by XiaozhiClient - decouples this controller from knowing anything
     *  about the WebSocket connection itself. Called on this controller's own capture
     *  thread; must not block. */
    public interface EncodedFrameSink {
        void onEncodedFrame(byte[] opusData) throws IOException;
    }

    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile AudioRecord audioRecord;
    private volatile boolean capturing = false;
    private volatile long captureSessionStart = 0;
    private volatile Opus encoder;
    private volatile EncodedFrameSink frameSink;

    private HandlerThread playbackThread;
    private Handler playbackHandler;
    private volatile AudioTrack audioTrack;
    private volatile boolean playing = false;
    private volatile long playbackSessionStart = 0;
    private volatile Opus decoder;
    private final ConcurrentLinkedQueue<byte[]> pcmQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedBytes = new AtomicInteger(0);

    public static final class StartResult {
        public final String error;
        private StartResult(String error) { this.error = error; }
        static StartResult ok() { return new StartResult(null); }
        static StartResult fail(String error) { return new StartResult(error); }
    }

    // ================= Capture (mic -> Opus -> sink) =================

    private void startCaptureThreadIfNeeded() {
        if (captureThread == null) {
            captureThread = new HandlerThread("XiaozhiAudioCaptureThread");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
        }
    }

    /** Opens the mic, starts a dedicated Opus encoder instance, and begins streaming
     *  60ms Opus frames to {@code sink} until stop() is called. Safe to call repeatedly
     *  - a no-op if already capturing. Blocks the calling thread (must NOT be the main
     *  thread) until capture has either started or failed - mirrors AudioController's
     *  start(long) contract. */
    public StartResult startCapture(EncodedFrameSink sink, long timeoutMs) {
        if (audioRecord != null) {
            return StartResult.ok();
        }
        frameSink = sink;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startCaptureThreadIfNeeded();
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                if (audioRecord != null) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioRecord.getMinBufferSize() returned " + minBufBytes
                            + " - 16kHz mono not supported on this hardware");
                    latch.countDown();
                    return;
                }
                int bufBytes = Math.max(minBufBytes * 4, SAMPLES_PER_FRAME * 2 * 4);
                Opus opus;
                try {
                    opus = new Opus();
                    int initResult = opus.encoderInit(Constants.SampleRate.Companion._16000(),
                            Constants.Channels.Companion.mono(), Constants.Application.Companion.voip());
                    if (initResult < 0) {
                        error.set("Opus encoderInit failed, code=" + initResult);
                        latch.countDown();
                        return;
                    }
                } catch (Throwable t) {
                    // Throwable (not Exception) deliberately: a missing/incompatible
                    // native .so surfaces as UnsatisfiedLinkError, which is an Error,
                    // not an Exception - this is exactly the failure mode
                    // XiaozhiClient.isAudioSupported()'s API-level gate exists to avoid,
                    // but this catch is the last line of defense if that gate is ever
                    // bypassed or wrong for a given device.
                    error.set("Opus encoder init threw: " + t.getMessage());
                    latch.countDown();
                    return;
                }
                try {
                    AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ, CHANNEL_IN_CONFIG, AUDIO_FORMAT, bufBytes);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        rec.release();
                        opus.encoderRelease();
                        error.set("AudioRecord failed to initialize (state="
                                + rec.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    rec.startRecording();
                    if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        rec.release();
                        opus.encoderRelease();
                        error.set("AudioRecord.startRecording() did not enter RECORDING state");
                        latch.countDown();
                        return;
                    }
                    audioRecord = rec;
                    encoder = opus;
                    capturing = true;
                    captureSessionStart = System.currentTimeMillis();
                    Log.i(TAG, "XiaoZhi mic capture started: " + SAMPLE_RATE_HZ
                            + "Hz mono 16-bit, " + FRAME_DURATION_MS + "ms frames, Opus VOIP mode");
                } catch (Exception e) {
                    opus.encoderRelease();
                    error.set("AudioRecord open failed: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                latch.countDown();
                captureLoop();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out starting XiaoZhi mic capture");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting XiaoZhi mic capture");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Runs on the capture thread: reads exactly one frame's worth of PCM at a time
     *  (blocking - AudioRecord.read() waits for enough samples), encodes it, and hands
     *  the Opus payload to the sink. One encode call per read, matching the protocol's
     *  fixed 60ms frame_duration - unlike AudioController's WAV-chunk streaming (which
     *  tolerates any chunk size), Opus frame boundaries are fixed by SAMPLES_PER_FRAME and
     *  must not be split/merged. */
    private void captureLoop() {
        short[] pcmBuf = new short[SAMPLES_PER_FRAME];
        // 2026-08 診斷: 用來查「說話完全沒反應」是否因為 mic 實際錄到的是靜音/垃圾
        // 數據 (例如 adev_open_input_stream HAL 層 fallback 開到的 stream 沒真正
        // 接到硬體), 還是聲音本身沒問題、只是協議/server 側的事。每大概 2 秒
        // (約 33 個 60ms frame) print 一次這個 frame 的 RMS 和 peak amplitude,
        // 不會 flood log 但足夠在下次錄音時直接從 log 看到有沒有真正錄到聲 - 如果一直
        // 都是 0 或者接近 0, 就證明是 HAL/mic 層問題; 如果說話的時候數值有明顯升,
        // 就證明錄音正常, 問題在 server/協議層。
        int frameCounter = 0;
        final int LOG_EVERY_N_FRAMES = 33;

        while (capturing && audioRecord != null) {
            if (System.currentTimeMillis() - captureSessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "XiaoZhi capture session hit MAX_SESSION_MS - stopping");
                capturing = false;
                break;
            }
            int totalRead = 0;
            while (totalRead < pcmBuf.length && capturing) {
                int n = audioRecord.read(pcmBuf, totalRead, pcmBuf.length - totalRead);
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read() returned error code " + n);
                    capturing = false;
                    break;
                }
                totalRead += n;
            }
            if (!capturing || totalRead < pcmBuf.length) {
                break; // partial frame at shutdown - not worth encoding/sending
            }
            frameCounter++;
            if (frameCounter % LOG_EVERY_N_FRAMES == 0) {
                long sumSq = 0;
                short peak = 0;
                for (short s : pcmBuf) {
                    sumSq += (long) s * (long) s;
                    short abs = (short) Math.abs((int) s);
                    if (abs > peak) peak = abs;
                }
                double rms = Math.sqrt(sumSq / (double) pcmBuf.length);
                Log.i(TAG, "XiaoZhi capture level check: rms=" + String.format(java.util.Locale.US, "%.1f", rms)
                        + " peak=" + peak + " (silence ~= rms<50)");
            }
            try {
                short[] encoded = encoder.encode(pcmBuf, FRAME_SIZE);
                if (encoded != null && encoded.length > 0) {
                    byte[] opusBytes = shortsToBytes(encoded);
                    EncodedFrameSink sink = frameSink;
                    if (sink != null) {
                        sink.onEncodedFrame(opusBytes);
                    }
                }
            } catch (IOException e) {
                // Sink (XiaozhiClient) couldn't send - most likely the WebSocket
                // dropped mid-session. Stop capturing rather than continuing to
                // encode audio nobody can receive; MainActivity's "mic/stop" endpoint
                // or a fresh "mic/start" after reconnecting will resume it.
                Log.w(TAG, "Encoded frame sink failed, stopping capture: " + e.getMessage());
                capturing = false;
                break;
            } catch (Exception e) {
                Log.w(TAG, "Opus encode failed for one frame, skipping: " + e.getMessage());
                // Skip this frame rather than aborting the whole session - a single
                // bad encode (e.g. a transient JNI hiccup) shouldn't kill an otherwise
                // healthy capture session.
            }
        }

        AudioRecord rec = audioRecord;
        audioRecord = null;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
        }
        Opus enc = encoder;
        encoder = null;
        if (enc != null) {
            try { enc.encoderRelease(); } catch (Exception ignored) { }
        }
    }

    /** Stops capture and releases the mic + encoder. Blocks until fully released,
     *  matching AudioController.shutdown()'s synchronous-teardown rationale (a caller
     *  that immediately does something else mic-related afterwards - e.g. hands the mic
     *  back to alpha2services' wake-word engine - must not race an in-flight release). */
    public void stopCapture() {
        capturing = false;
        frameSink = null;
        if (captureHandler == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isCapturing() {
        return capturing;
    }

    // ================= Playback (Opus <- server -> AudioTrack) =================

    private void startPlaybackThreadIfNeeded() {
        if (playbackThread == null) {
            playbackThread = new HandlerThread("XiaozhiAudioPlaybackThread");
            playbackThread.start();
            playbackHandler = new Handler(playbackThread.getLooper());
        }
    }

    /** Opens the AudioTrack + a dedicated Opus decoder instance and starts the write
     *  loop. Safe to call repeatedly - a no-op if already playing. Blocks the calling
     *  thread (must NOT be the main thread) until playback has either started or
     *  failed. */
    public StartResult startPlayback(long timeoutMs) {
        if (audioTrack != null) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startPlaybackThreadIfNeeded();
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                if (audioTrack != null) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_OUT_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioTrack.getMinBufferSize() returned " + minBufBytes
                            + " - 16kHz mono not supported on this hardware");
                    latch.countDown();
                    return;
                }
                int bufBytes = minBufBytes * 8; // same headroom rationale as
                                                  // AudioPlaybackController's bufBytes
                Opus opus;
                try {
                    opus = new Opus();
                    int initResult = opus.decoderInit(Constants.SampleRate.Companion._16000(),
                            Constants.Channels.Companion.mono());
                    if (initResult < 0) {
                        error.set("Opus decoderInit failed, code=" + initResult);
                        latch.countDown();
                        return;
                    }
                } catch (Throwable t) {
                    error.set("Opus decoder init threw: " + t.getMessage());
                    latch.countDown();
                    return;
                }
                try {
                    // Legacy constructor, matching AudioPlaybackController's own
                    // documented finding that the newer AudioAttributes/AudioFormat
                    // .Builder constructor failed to initialize on this hardware.
                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE_HZ,
                            CHANNEL_OUT_CONFIG,
                            AUDIO_FORMAT,
                            bufBytes,
                            AudioTrack.MODE_STREAM);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        track.release();
                        opus.decoderRelease();
                        error.set("AudioTrack failed to initialize (state="
                                + track.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    // play() deliberately not called yet - see writeLoop()/
                    // prebufferThenPlay(), same underrun-avoidance rationale as
                    // AudioPlaybackController.
                    audioTrack = track;
                    decoder = opus;
                    playing = true;
                    playbackSessionStart = System.currentTimeMillis();
                    pcmQueue.clear();
                    queuedBytes.set(0);
                    Log.i(TAG, "XiaoZhi playback constructed (not yet playing): "
                            + SAMPLE_RATE_HZ + "Hz mono 16-bit, buffer=" + bufBytes + " bytes");
                } catch (Exception e) {
                    opus.decoderRelease();
                    error.set("AudioTrack open failed: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                latch.countDown();
                writeLoop();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out starting XiaoZhi playback");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting XiaoZhi playback");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Called from XiaozhiClient's read loop (a different thread) whenever a binary
     *  (Opus) frame arrives from the server. Decodes immediately (JNI decode is cheap
     *  relative to network I/O) and enqueues the resulting PCM for writeLoop() to drain
     *  - matches AudioPlaybackController's enqueuePcm() pattern of decoding/preparing
     *  audio on the producer thread but only ever writing to the AudioTrack from its
     *  own dedicated thread. Silently drops the frame if playback hasn't been started
     *  (startPlayback() not called yet, or already stopped) rather than buffering
     *  audio nobody will play.
     *
     *  2026-08 修正 (真機證實的 bug, 表現為大量 "E/CodecOpus [decode] error:
     *  corrupted stream", 307 次一個 session): 之前這裡用 bytesToShorts(opusData)
     *  將收到的壓縮 Opus bytes 轉成 short[] 再傳到 decode(short[], FrameSize) 這個
     *  overload。問題在 bytesToShorts() 用 "bytes.length / 2" 來決定輸出
     *  short[] 的長度 - 這假設了 opusData 長度一定是雙數, 但 Opus 是
     *  variable-length codec, 每個 encoded frame 的實際 byte 數會隨音頻內容/
     *  bitrate 浮動, 完全可能是單數。單數長度的時候, 整數除法會靜靜地截斷最後
     *  一個 byte, 讓 decoder 收到一個少了一個 byte、不完整的 compressed
     *  bitstream, 觸發 corrupted stream (Opus 用 range coder, 對 bitstream
     *  完整性很敏感, 少一個 byte 就足以讓整個 frame decode 失敗) - 這也解釋了
     *  為什麼不是每個 frame 都撞到 (取決於那個 frame 剛好是不是單數長度, 機率上
     *  接近一半)。Opus class (見 com.theeasiestway.opus.Opus) 本身其實已經有
     *  真正接受/回傳 byte[] 的 decode(byte[], FrameSize) overload, 完全不用這層
     *  byte[]<->short[] 轉換, 也沒有任何長度奇偶問題 - 這裡改用這個, 連
     *  bytesToShorts() 這個 helper 都不再需要 (shortsToBytes() 仍然用於 encode
     *  那邊, 沒有這個 bug: 轉換方向是 short[]->byte[], 長度由 short[].length*2
     *  決定, 保證是雙數, 沒截斷風險)。 */
    public void onIncomingOpusFrame(byte[] opusData) {
        Opus dec = decoder;
        if (dec == null || !playing) return;
        byte[] pcmBytes;
        try {
            pcmBytes = dec.decode(opusData, FRAME_SIZE);
        } catch (Exception e) {
            Log.w(TAG, "Opus decode failed for one frame, dropping: " + e.getMessage());
            return;
        }
        if (pcmBytes == null || pcmBytes.length == 0) return;
        pcmQueue.add(pcmBytes);
        int total = queuedBytes.addAndGet(pcmBytes.length);
        while (total > JITTER_BUFFER_CAP_BYTES) {
            byte[] dropped = pcmQueue.poll();
            if (dropped == null) break;
            total = queuedBytes.addAndGet(-dropped.length);
        }
    }

    /** Same prebuffer-then-play()/proactive-idle-pause structure as
     *  AudioPlaybackController.writeLoop() - see that class's javadoc for the full
     *  underrun-avoidance rationale, which applies identically here. */
    private void writeLoop() {
        if (!prebufferThenPlay()) {
            finishAndReleasePlayback();
            return;
        }

        int idleMs = 0;
        while (playing && audioTrack != null) {
            if (System.currentTimeMillis() - playbackSessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "XiaoZhi playback session hit MAX_SESSION_MS - stopping");
                playing = false;
                break;
            }
            byte[] pcm = pcmQueue.poll();
            if (pcm == null) {
                idleMs += IDLE_POLL_MS;
                if (idleMs >= IDLE_PAUSE_MS) {
                    try {
                        audioTrack.pause();
                    } catch (Exception ignored) {
                    }
                    if (!prebufferThenPlay()) {
                        finishAndReleasePlayback();
                        return;
                    }
                    idleMs = 0;
                }
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            idleMs = 0;
            queuedBytes.addAndGet(-pcm.length);
            try {
                audioTrack.write(pcm, 0, pcm.length);
            } catch (Exception e) {
                Log.w(TAG, "AudioTrack.write() failed, stopping playback: " + e.getMessage());
                playing = false;
                break;
            }
        }
        finishAndReleasePlayback();
    }

    /** Waits (bounded by a short poll loop, not indefinitely - unlike
     *  AudioPlaybackController's stream, a XiaoZhi voice reply might simply never
     *  arrive if the server has nothing to say, e.g. silence between turns) for
     *  PREBUFFER_FRAMES to accumulate, writes them, then calls play(). Returns false if
     *  playback was stopped while waiting. */
    private boolean prebufferThenPlay() {
        int waited = 0;
        final int MAX_PREBUFFER_WAIT_MS = 3000;
        while (playing && audioTrack != null && pcmQueue.size() < PREBUFFER_FRAMES) {
            if (waited >= MAX_PREBUFFER_WAIT_MS) {
                break; // give up prebuffering, play whatever's queued (possibly zero)
            }
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += IDLE_POLL_MS;
        }
        if (!playing || audioTrack == null) return false;
        byte[] pcm;
        while ((pcm = pcmQueue.poll()) != null) {
            queuedBytes.addAndGet(-pcm.length);
            try {
                audioTrack.write(pcm, 0, pcm.length);
            } catch (Exception e) {
                Log.w(TAG, "AudioTrack.write() failed during prebuffer: " + e.getMessage());
                return false;
            }
        }
        try {
            audioTrack.play();
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack.play() failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void finishAndReleasePlayback() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
        }
        Opus dec = decoder;
        decoder = null;
        if (dec != null) {
            try { dec.decoderRelease(); } catch (Exception ignored) { }
        }
        pcmQueue.clear();
        queuedBytes.set(0);
    }

    /** Stops playback and releases the AudioTrack + decoder. Blocks until fully
     *  released, same rationale as stopCapture(). */
    public void stopPlayback() {
        playing = false;
        if (playbackHandler == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    // ================= Shutdown (both halves) =================

    /** Full teardown of both capture and playback - called from
     *  MainActivity#onDestroy(). Unlike stopCapture()/stopPlayback(), this also quits
     *  the HandlerThreads, matching AudioController.shutdown()'s two-stage pattern
     *  (block for in-flight release, then quitSafely()). */
    public void shutdown() {
        stopCapture();
        stopPlayback();
        if (captureThread != null) {
            captureThread.quitSafely();
        }
        if (playbackThread != null) {
            playbackThread.quitSafely();
        }
    }

    // ================= PCM/Opus <-> byte[] conversion =================

    /** encode() 的 short[] overload 用來編碼 PCM 樣本 (short[] 天生就是 16-bit
     *  audio sample 的原生表達方式, 和 AudioRecord/AudioTrack 一致, 不用額外
     *  byte-order 轉換), 但 encode() 的輸出 (壓縮 Opus payload) 沒有任何 16-bit
     *  sample 語意, 只是借用這個 library 通用的 "array of 16-bit units"
     *  return type - 所以要用這個 helper 轉成扁平 byte[] 才能塞進 WebSocket
     *  binary frame。Little-endian, 和 AudioController.wrapPcmAsWav 用的
     *  ByteOrder.LITTLE_ENDIAN 一致。
     *
     *  這裡的轉換方向 (short[] -> byte[]) 長度一定是 shorts.length*2, 保證是
     *  雙數, 沒截斷風險 - 對稱的反方向 (byte[] -> short[], 曾經叫
     *  bytesToShorts()) 就不是這樣, 因為 Opus 是 variable-length codec, 收到的
     *  壓縮 byte[] 長度可能是單數, "/2" 整數除法會靜靜地截斷最後一個 byte, 導致
     *  真機證實的 "corrupted stream" decode error (307 次一個 session) - 2026-08
     *  已經將 onIncomingOpusFrame() 改用 Opus class 本身提供、真正接受/回傳
     *  byte[] 的 decode(byte[], FrameSize) overload, 完全繞過這個轉換, 所以
     *  bytesToShorts() 這個 helper 已經刪掉, 不留下一個「看起來對但實際有 bug」
     *  的方法引誘之後的人再用它。 */
    private static byte[] shortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }
}
