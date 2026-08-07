package com.open.alpha2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays audio sent from the browser out through the robot's speaker, using a
 * standard AudioTrack on STREAM_MUSIC. This is the browser-mic -> robot-speaker half
 * of the walkie-talkie feature (the other half, robot-mic -> browser, is
 * AudioController).
 *
 * IMPORTANT / UNVERIFIED: whether the robot's physical speaker is actually reachable
 * through a plain AudioTrack, as opposed to being reserved for a dedicated TTS/audio
 * pipeline that bypasses the standard Android audio mixer, has NOT been confirmed by
 * static analysis - docs/hardware.md only documents a dedicated audio DSP node
 * (/dev/zl380tw) on the MIC array side for echo cancellation, and says nothing about
 * the speaker output path. playTestTone() exists specifically so this can be checked
 * on the physical unit before building anything on top of it.
 *
 * Same open-once, keep-open-for-the-session pattern as AudioController/
 * CameraController, using a dedicated HandlerThread so playback setup/teardown never
 * runs on whatever thread happens to call start()/shutdown() (e.g. an HTTP worker
 * thread).
 */
public class AudioPlaybackController {
    private static final String TAG = "AudioPlaybackController";

    // Must match whatever sample rate the browser-side encoder uses when it sends PCM
    // Playback sample rate. Confirmed compatible via logcat: alpha2services' own TTS
    // engine (IflytekTTS) successfully opens an AudioTrack at sampleRate=16000 (its
    // Lowered from 16000 to 8000 by request, alongside the same change in
    // AudioController.java and app.js's TALK_TARGET_SAMPLE_RATE - all three must agree.
    // Halves bytes/sec, which doubles how much playback time bufBytes/
    // JITTER_BUFFER_CAP_BYTES represent for the same byte count - directly increasing
    // headroom against the mid-session network jitter that caused the underrun seen in
    // logcat_2026-07-30_08-43-18.txt, on top of (not instead of) the *4->*8 bufBytes
    // widening below. 8kHz is telephone-grade voice quality, an acceptable tradeoff
    // here.
    private static final int SAMPLE_RATE_HZ = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final long MAX_SESSION_MS = 5 * 60 * 1000; // same safety rationale as
                                                                 // AudioController's cap

    // Jitter-buffer cap: bytes/sec at 8kHz mono 16-bit = 16000 (halved from 32000 when
    // SAMPLE_RATE_HZ was lowered from 16000 to 8000 above - this formula must track
    // that change, or the cap's actual duration would silently double instead of
    // staying at the intended ~600ms). Each browser chunk (ScriptProcessorNode
    // bufferSize=4096 at the browser's native rate, downsampled to 8kHz - see app.js)
    // arrives roughly every 50-160ms in practice, but HTTP upload timing over a LAN is
    // bursty (each /upload/audio POST is its own short-lived TCP connection -
    // "Connection: close" - not a steady stream), so chunks can arrive in clusters
    // rather than evenly spaced. Without a cap, pcmQueue would grow without bound
    // during any such burst and writeLoop() (which just drains it as fast as
    // AudioTrack.write() allows) would keep playing everything queued - meaning once a
    // backlog builds up, played audio permanently lags further and further behind what
    // was actually just spoken (this is the "audio delayed by ~3s" symptom: the queue
    // silently accumulated ~3s of backlog and was faithfully playing all of it, just
    // increasingly late). Capping at ~600ms of audio and dropping the OLDEST buffered
    // chunk when over cap keeps latency bounded - preferring a small skip forward over
    // an ever-growing lag, which is the right tradeoff for live speech.
    private static final int JITTER_BUFFER_CAP_BYTES = 16000 * 600 / 1000; // ~600ms = 9600 bytes

    // Small startup prebuffer before writeLoop() begins writing to AudioTrack - without
    // this, playback starts on the very first chunk to arrive, so any bursty gap between
    // chunk 1 and chunk 2 immediately starves AudioTrack's internal buffer and triggers
    // the "disabled due to previous underrun, restarting" cycle seen in logcat (this is
    // the "choppy/stuttering" symptom). Waiting for a couple of chunks to arrive first
    // gives AudioTrack's internal buffer some cushion against normal upload jitter.
    // Widened from 2 to 3 alongside the bufBytes *4->*8 change below, for the same
    // reason (more headroom against ordinary network/scheduling jitter).
    private static final int PREBUFFER_CHUNKS = 3;

    // Mid-session idle handling (added after logcat_2026-07-02_09-37-40.txt): every
    // "disabled due to previous underrun, restarting" in that capture followed an
    // unusually large gap between /upload/audio POSTs - not just tens/low-hundreds of
    // milliseconds of ordinary network jitter (which JITTER_BUFFER_CAP_BYTES/bufBytes
    // already handle), but real multi-second pauses (569ms, 1.4s, 1.9s, 4.0s, 4.3s were
    // all observed) with zero other HTTP activity during them - most likely the browser
    // tab's onaudioprocess callback itself being throttled/backgrounded, or the person
    // talking genuinely pausing mid-sentence that long. No buffer size can absorb a
    // multi-second real gap without adding multi-second latency, which would defeat the
    // point of live speech. So instead of only reacting to underrun after AudioTrack's
    // native buffer has already run dry (audible as a harsh click/glitch), writeLoop()
    // proactively calls pause() once the queue has been empty for IDLE_PAUSE_MS, then
    // re-enters the same wait-for-PREBUFFER_CHUNKS-then-play() sequence used at startup
    // once new audio arrives - trading the glitchy underrun sound for clean silence
    // during a real gap, which is what actually happened anyway from the listener's
    // perspective.
    private static final int IDLE_POLL_MS = 10;
    private static final int IDLE_PAUSE_MS = 300;

    private HandlerThread playbackThread;
    private Handler playbackHandler;
    private volatile AudioTrack audioTrack;
    private volatile boolean playing = false;
    private volatile long sessionStart = 0;
    // PCM chunks queued from HTTP handler threads (browser POSTs), drained by the
    // playback thread's write loop. Bounded by JITTER_BUFFER_CAP_BYTES (see above) -
    // enqueuePcm() drops the oldest chunk(s) once the queue holds more than that much
    // audio, so a network/GC hiccup causes a small forward skip instead of unbounded
    // growing lag.
    private final ConcurrentLinkedQueue<byte[]> pcmQueue = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger queuedBytes =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static final class StartResult {
        public final String error;
        private StartResult(String error) { this.error = error; }
        static StartResult ok() { return new StartResult(null); }
        static StartResult fail(String error) { return new StartResult(error); }
    }

    private void startThreadIfNeeded() {
        if (playbackThread == null) {
            playbackThread = new HandlerThread("AudioPlaybackControllerThread");
            playbackThread.start();
            playbackHandler = new Handler(playbackThread.getLooper());
        }
    }

    /** Opens the AudioTrack (if not already open) and starts its write loop. Safe to
     *  call repeatedly - a no-op if already playing. Blocks the calling thread (must
     *  NOT be the main thread) until playback has either started or failed. */
    public StartResult start(long timeoutMs) {
        if (audioTrack != null) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startThreadIfNeeded();
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                if (audioTrack != null) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioTrack.getMinBufferSize() returned " + minBufBytes
                            + " - sample rate/format not supported on this hardware");
                    latch.countDown();
                    return;
                }
                // Widened from *4 to *8 after logcat_2026-07-30_08-43-18.txt showed
                // "disabled due to previous underrun, restarting" happening MID-SESSION
                // (i.e. after playback had already been running smoothly for a while,
                // not just at startup - the startup case was already fixed by
                // PREBUFFER_CHUNKS/deferred play() above). A mid-session underrun means
                // AudioTrack's own native buffer ran dry momentarily even though the
                // jitter-buffer-capped queue logic was working as designed - a ~240ms
                // gap between two /upload/audio POSTs (ordinary HTTP/network jitter,
                // nothing pathological) was enough to starve the old *4 buffer
                // (~240ms of headroom) with essentially no margin left over. *8 doubles
                // that headroom so an ordinary jitter gap like this no longer empties
                // the native buffer entirely.
                int bufBytes = minBufBytes * 8;
                Log.i(TAG, "About to construct AudioTrack: STREAM_MUSIC, "
                        + SAMPLE_RATE_HZ + "Hz, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT, "
                        + "minBufBytes=" + minBufBytes + ", bufBytes=" + bufBytes
                        + ", MODE_STREAM");
                try {
                    // Legacy constructor (not AudioAttributes/AudioFormat.Builder) -
                    // the newer API failed AudioTrack initialization outright on this
                    // hardware (state=0/STATE_UNINITIALIZED, i.e. construction itself
                    // failed, not just playback). STREAM_MUSIC is the old API's closest
                    // equivalent to USAGE_VOICE_COMMUNICATION/CONTENT_TYPE_SPEECH.
                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE_HZ,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufBytes,
                            AudioTrack.MODE_STREAM);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        track.release();
                        error.set("AudioTrack failed to initialize (state="
                                + track.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    // NOTE: play() is deliberately NOT called here - see writeLoop()'s
                    // javadoc. Calling play() this early was the actual root cause of
                    // the near-instant "disabled due to previous underrun, restarting"
                    // seen in logcat: play() immediately flips the track into PLAYING
                    // state and it starts consuming from its (still-empty) internal
                    // buffer right away, so by the time the first real chunk arrives
                    // from the browser - anywhere from ~700ms to several seconds later,
                    // per logcat timing - the track has already run dry and Android has
                    // already disabled/restarted it. play() is now called from
                    // writeLoop() itself, only once actual PCM data has been written.
                    audioTrack = track;
                    playing = true;
                    sessionStart = System.currentTimeMillis();
                    Log.i(TAG, "AudioTrack constructed (not yet playing): " + SAMPLE_RATE_HZ
                            + "Hz mono 16-bit, buffer=" + bufBytes + " bytes, STREAM_MUSIC");
                } catch (Exception e) {
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
                return StartResult.fail("Timed out starting AudioTrack");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting AudioTrack");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Runs on the playback thread: waits for the first chunk(s) to arrive, writes them
     *  into the (not-yet-playing) AudioTrack, THEN calls play() - only after that does
     *  it settle into draining pcmQueue as further chunks arrive, until stop() is
     *  called or MAX_SESSION_MS is hit.
     *
     *  IMPORTANT: play() must not be called until real PCM data has actually been
     *  written. Calling play() immediately on construction (the previous approach) was
     *  the actual root cause of the near-instant "disabled due to previous underrun,
     *  restarting" seen in logcat: play() immediately flips AudioTrack into PLAYING
     *  state and it starts consuming from its (still-empty) internal buffer right away.
     *  Logcat timing showed the gap between AudioTrack construction and the first real
     *  chunk arriving from the browser varies wildly - anywhere from ~700ms to 5+
     *  seconds (getUserMedia()/permission prompt timing, tab focus, network setup, etc.
     *  are all outside this app's control) - so there is no safe fixed timeout to
     *  "give up prebuffering and play anyway": doing that just moves the underrun from
     *  "immediately" to "whenever the timeout fires instead of real audio arriving".
     *  Waiting indefinitely for real data before ever calling play() is the only
     *  correct fix; stop()/MAX_SESSION_MS still bound how long a session can wait or
     *  run overall.
     *
     *  MID-SESSION idle handling (added after logcat_2026-07-02_09-37-40.txt):
     *  every "disabled due to previous underrun, restarting" in that capture followed
     *  an unusually large gap between /upload/audio POSTs - not just tens/low-hundreds
     *  of milliseconds of ordinary network jitter (which JITTER_BUFFER_CAP_BYTES/the
     *  widened bufBytes already handle), but real multi-second pauses (569ms, 1.4s,
     *  1.9s, 4.0s, 4.3s were all observed) with zero other HTTP activity during them.
     *  The most likely cause is the browser tab's onaudioprocess callback itself being
     *  throttled or pausing - e.g. the tab losing focus, or the person talking just
     *  actually pausing mid-sentence for that long - not anything this Android-side
     *  code can prevent. No buffer size can absorb a multi-second real gap without
     *  adding multi-second latency, which would defeat the point of live speech. So
     *  instead of only ever reacting to underrun after AudioTrack's native buffer has
     *  already run dry (audible as harsh clicking/glitching), IDLE_PAUSE_MS below
     *  proactively calls pause() once the queue has been empty that long, and re-enters
     *  the same wait-for-PREBUFFER_CHUNKS-then-play() sequence used at startup once new
     *  audio does arrive. This trades the glitchy underrun sound for clean silence
     *  during a real gap, which is what actually happened anyway from the listener's
     *  perspective - the audio just paused because there was nothing being said. */
    private void writeLoop() {
        if (!prebufferThenPlay()) {
            finishAndReleaseTrack();
            return;
        }

        int idleMs = 0;
        while (playing && audioTrack != null) {
            if (System.currentTimeMillis() - sessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "Playback session hit MAX_SESSION_MS - stopping");
                playing = false;
                break;
            }
            byte[] pcm = pcmQueue.poll();
            if (pcm == null) {
                idleMs += IDLE_POLL_MS;
                if (idleMs >= IDLE_PAUSE_MS) {
                    Log.i(TAG, "No audio for " + idleMs + "ms - pausing AudioTrack "
                            + "proactively (avoids an audible underrun click/glitch) "
                            + "and waiting for a fresh prebuffer before resuming");
                    try {
                        audioTrack.pause();
                    } catch (Exception ignored) {
                        // Track may already be in a bad state; prebufferThenPlay()'s
                        // own play() call below will surface any real problem.
                    }
                    if (!prebufferThenPlay()) {
                        finishAndReleaseTrack();
                        return;
                    }
                    idleMs = 0;
                    continue;
                }
                try {
                    Thread.sleep(IDLE_POLL_MS); // brief idle wait rather than a busy-spin
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            idleMs = 0;
            queuedBytes.addAndGet(-pcm.length);
            // AudioTrack.write() blocks until its internal buffer has room - this is
            // the desired backpressure (don't decode/queue faster than the hardware
            // can actually play), not a bug, as long as playing can still be flipped
            // false promptly (write() returns once its buffer accepts data, so the
            // loop re-checks playing at least once per chunk).
            audioTrack.write(pcm, 0, pcm.length);
        }

        finishAndReleaseTrack();
    }

    /** Waits for and writes PREBUFFER_CHUNKS worth of real data into audioTrack WITHOUT
     *  playing yet (MODE_STREAM allows write() before play() - the data just queues in
     *  AudioTrack's internal buffer), then calls play() once that much is buffered.
     *  Used both at session startup and after writeLoop()'s mid-session idle detection
     *  pauses AudioTrack and needs to refill before resuming - see writeLoop()'s javadoc
     *  for why both cases need the same "don't play until real data is queued" handling.
     *
     *  @return true if playback should continue (either successfully (re)started, or
     *          stopped for a normal reason the caller should just return on); false is
     *          never actually distinct from true here in terms of caller action - both
     *          paths funnel into finishAndReleaseTrack() in writeLoop() when playing/
     *          audioTrack become false/null, so the boolean mainly exists to make
     *          writeLoop()'s call sites read clearly as "did this step complete". */
    private boolean prebufferThenPlay() {
        int chunksWritten = 0;
        while (playing && audioTrack != null && chunksWritten < PREBUFFER_CHUNKS) {
            if (System.currentTimeMillis() - sessionStart > MAX_SESSION_MS) {
                Log.i(TAG, "Playback session hit MAX_SESSION_MS while (re)prebuffering - stopping");
                playing = false;
                return false;
            }
            byte[] pcm = pcmQueue.poll();
            if (pcm == null) {
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            queuedBytes.addAndGet(-pcm.length);
            audioTrack.write(pcm, 0, pcm.length);
            chunksWritten++;
        }
        if (playing && audioTrack != null && chunksWritten > 0) {
            audioTrack.play();
            Log.i(TAG, "AudioTrack play() called after prebuffering " + chunksWritten + " chunk(s)");
        }
        return playing && audioTrack != null;
    }

    /** Common teardown shared by every writeLoop() exit path - stops/releases
     *  audioTrack and clears the queue. Extracted so the mid-session idle-pause path
     *  (which can now return out of writeLoop() from inside the main loop, not just at
     *  the bottom) doesn't need this duplicated at each return site. */
    private void finishAndReleaseTrack() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        pcmQueue.clear();
        queuedBytes.set(0);
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Queues raw PCM (8kHz mono 16-bit, matching SAMPLE_RATE_HZ/CHANNEL_CONFIG/
     *  AUDIO_FORMAT above) for playback. No-op if playback isn't running - callers
     *  should have called start() first and checked its result.
     *
     *  Bounded by JITTER_BUFFER_CAP_BYTES: if the queue is already holding more than
     *  that much audio (an upload burst arrived faster than writeLoop() could drain it
     *  - see the field javadoc above), the OLDEST queued chunk(s) are dropped first so
     *  the new chunk still gets added without the backlog growing further. This trades
     *  a small forward skip for keeping playback latency bounded, rather than every
     *  chunk being dutifully played increasingly late forever. */
    public void enqueuePcm(byte[] pcm) {
        if (!playing) {
            return;
        }
        pcmQueue.add(pcm);
        int total = queuedBytes.addAndGet(pcm.length);
        while (total > JITTER_BUFFER_CAP_BYTES) {
            byte[] dropped = pcmQueue.poll();
            if (dropped == null) {
                break; // writeLoop() drained concurrently - queue is already catching up
            }
            total = queuedBytes.addAndGet(-dropped.length);
        }
    }

    /** Stops playback and releases the AudioTrack. Unlike AudioController's
     *  stopIfIdle() (which only closes once every subscriber is gone, since multiple
     *  browser tabs might be watching the camera/mic at once), playback is inherently
     *  single-session/push-to-talk, so any explicit stop just stops it outright. */
    public void stop() {
        playing = false; // writeLoop() notices and releases on its own thread
    }

    public void shutdown() {
        playing = false;
        if (playbackThread != null) {
            playbackThread.quitSafely();
        }
    }

    /**
     * Sweeps multiple AudioTrack configurations (sample rate x mode) in one call,
     * logging and returning every combination's actual getState() result. Exists
     * because start()/playTestTone() each try exactly one fixed configuration and
     * every configuration tried so far (16kHz/MODE_STREAM, 16kHz/MODE_STATIC,
     * 44100Hz/MODE_STREAM) has failed with state=0 while alpha2services' own TTS
     * engine opens a 16kHz AudioTrack successfully in the same process space - this
     * sweep exists to find whether ANY third-party-app-constructible AudioTrack
     * configuration works on this hardware at all, rather than guessing one
     * hypothesis per logcat round trip.
     */
    public String diagnoseAudioTrack(long timeoutMs) {
        final StringBuilder results = new StringBuilder();
        final CountDownLatch latch = new CountDownLatch(1);
        startThreadIfNeeded();
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                int[] ratesToTry = {8000, 11025, 16000, 22050, 44100};
                for (int rate : ratesToTry) {
                    for (int mode : new int[]{AudioTrack.MODE_STATIC, AudioTrack.MODE_STREAM}) {
                        String modeLabel = (mode == AudioTrack.MODE_STATIC) ? "STATIC" : "STREAM";
                        String label = rate + "Hz/" + modeLabel;
                        try {
                            int minBuf = AudioTrack.getMinBufferSize(
                                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                            if (minBuf <= 0) {
                                String line = label + ": getMinBufferSize=" + minBuf + " (unsupported)";
                                Log.i(TAG, "diagnoseAudioTrack: " + line);
                                results.append(line).append("\n");
                                continue;
                            }
                            int bufSize = (mode == AudioTrack.MODE_STATIC) ? minBuf : minBuf * 2;
                            AudioTrack track = new AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    rate,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    bufSize,
                                    mode);
                            String line = label + ": minBuf=" + minBuf + ", state="
                                    + track.getState()
                                    + (track.getState() == AudioTrack.STATE_INITIALIZED
                                            ? " (OK)" : " (FAILED)");
                            Log.i(TAG, "diagnoseAudioTrack: " + line);
                            results.append(line).append("\n");
                            track.release();
                        } catch (Exception e) {
                            String line = label + ": threw " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage();
                            Log.i(TAG, "diagnoseAudioTrack: " + line);
                            results.append(line).append("\n");
                        }
                    }
                }
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return results.toString();
    }

    /**
     * Plays a short test tone directly - NOT part of the walkie-talkie data path, this
     * exists purely to answer the open question in this class's javadoc: does a plain
     * AudioTrack actually reach the robot's physical speaker? Generates 1 second of a
     * 440Hz sine wave and plays it via AudioTrack.MODE_STREAM. Originally used
     * MODE_STATIC (simpler for a single short clip in principle), but
     * diagnoseAudioTrack()'s sweep found MODE_STATIC fails outright
     * (state=2/STATE_NO_STATIC_DATA) at every sample rate on this hardware while
     * MODE_STREAM succeeds at every rate - this is what was actually causing "test
     * tone failed" while the walkie-talkie path (which already used MODE_STREAM in
     * start()) worked fine. For a one-shot clip, MODE_STREAM just means writing the
     * whole buffer once and calling play() - it drains on its own without needing
     * continuous refeeding.
     */
    public StartResult playTestTone(long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        startThreadIfNeeded();
        playbackHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    int durationMs = 1000;
                    int sampleCount = SAMPLE_RATE_HZ * durationMs / 1000;
                    short[] samples = new short[sampleCount];
                    double freqHz = 440.0;
                    for (int i = 0; i < sampleCount; i++) {
                        double t = i / (double) SAMPLE_RATE_HZ;
                        samples[i] = (short) (Short.MAX_VALUE * 0.5
                                * Math.sin(2 * Math.PI * freqHz * t));
                    }
                    byte[] pcm = new byte[sampleCount * 2];
                    for (int i = 0; i < sampleCount; i++) {
                        pcm[i * 2] = (byte) (samples[i] & 0xFF);
                        pcm[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
                    }

                    int minBufBytes = AudioTrack.getMinBufferSize(
                            SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
                    // MODE_STREAM's buffer just needs to be at least the driver's
                    // minimum - unlike MODE_STATIC, it doesn't need to exactly match
                    // the clip length, since write() feeds data into it rather than
                    // the buffer holding the entire clip at once.
                    int bufBytes = Math.max(minBufBytes, pcm.length);
                    Log.i(TAG, "About to construct AudioTrack (test tone): STREAM_MUSIC, "
                            + SAMPLE_RATE_HZ + "Hz, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT, "
                            + "bufferSize=" + bufBytes + " bytes, MODE_STREAM");
                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE_HZ,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufBytes,
                            AudioTrack.MODE_STREAM);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        track.release();
                        error.set("AudioTrack failed to initialize (state="
                                + track.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    track.write(pcm, 0, pcm.length);
                    track.play();
                    Log.i(TAG, "Test tone playing: 440Hz, " + durationMs + "ms");
                    // Release shortly after the clip finishes rather than immediately -
                    // releasing while audio is still draining can cut it off.
                    new Handler(playbackThread.getLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                track.stop();
                            } catch (Exception ignored) {
                            }
                            try {
                                track.release();
                            } catch (Exception ignored) {
                            }
                        }
                    }, durationMs + 300);
                } catch (Exception e) {
                    error.set("Test tone playback failed: " + e.getMessage());
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out playing test tone");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while playing test tone");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }
}
