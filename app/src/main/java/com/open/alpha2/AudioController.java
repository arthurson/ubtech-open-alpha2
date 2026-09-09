package com.open.alpha2;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streams the robot's microphone audio to any number of subscribed HTTP clients, using
 * the same open-once-and-fan-out pattern as CameraController (rather than a
 * capture/release cycle per listener).
 *
 * AudioRecord is opened ONCE and left recording; every ~CHUNK_MS of PCM is wrapped in
 * its own self-contained WAV header and handed to every subscriber. A self-contained
 * header per chunk (rather than one header for the whole stream) costs 44 bytes of
 * overhead per chunk, but means each chunk is independently decodable - the browser
 * side just calls AudioContext.decodeAudioData() per chunk with no need to track a
 * running stream position or handle a header that arrived in a previous fetch.
 *
 * All AudioRecord calls happen on a dedicated background thread with its own Looper,
 * matching CameraController's rationale: callbacks/blocking reads should not run on
 * whatever caller thread happens to invoke start()/stop().
 */
public class AudioController {
    private static final String TAG = "AudioController";
    // 2026-08 改回 16000 (由 8000 升回上): 當初改成 8000 只是為了和已經永久停用
    // 的 walkie-talkie (startTalk() 現在已經無條件直接 return, 見 app-mic.js)
    // 上傳那條路對齊 sample rate - 現在那個理由已經不存在, 用戶指定連
    // walkie-talkie 那兩個檔案 (AudioPlaybackController.java, app-mic.js 的
    // TALK_TARGET_SAMPLE_RATE) 都一起拉回 16000, 保持三方一致, 即使
    // walkie-talkie 現在實際用不到。
    //
    // 注意: 8000 那時還有第二個理由 - decodeAudioData() 在這台機 (RK3288) CPU
    // 的逐 chunk decode 負擔, 和「越聽越慢」的歷史 bug 有關 (見
    // MIC_MAX_PENDING_CHUNKS/micDrainLoop 那輪修法)。16000 的 bytes/sec 是
    // 8000 的兩倍, decodeAudioData() 要處理的 payload 都大了兩倍 - 如果日後
    // 在這台機實測又見到聽聲越聽越慢/斷斷續續, 這是第一個要懷疑的方向,
    // 到時可以考慮縮短 CHUNK_MS 來抵消 (較小的 chunk, 但更頻繁的 decode 調用),
    // 或者退回做 8000。(2026-08 後續: 現在已經拿掉整套 decodeAudioData()
    // 播放機制, 改用 ScriptProcessorNode, 這個顧慮已經不再適用 - 見
    // app-mic.js 開頭那段 comment。)
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_MS = 500; // latency/overhead tradeoff: smaller chunks
                                               // sound more "live" but pay decodeAudioData's
                                               // per-call overhead more often on the browser
                                               // side - at 100ms (10 decodes/sec) that
                                               // overhead alone was enough to fall behind
                                               // arrival rate on this hardware, causing
                                               // creeping playback lag and audible stutter
    // Removed by request: this used to hard-cap any single mic session at 5 minutes
    // regardless of whether a client was still listening, as a second safety net on
    // top of stopIfIdle()'s client-disconnect detection (see readLoop()'s old check).
    // stopIfIdle() - triggered when handleMicStream()'s out.write() hits a broken pipe
    // after the HTTP client disconnects - remains the primary mechanism that releases
    // the mic back to alpha2services' wake-word engine, and is unaffected by this
    // removal. The tradeoff: if a client connection were to vanish in a way that never
    // produces a write failure (no further chunks ever attempted, socket never
    // explicitly closed), the mic could now stay held indefinitely instead of being
    // force-released after 5 minutes. No such case has been observed in logcat so far.

    /** One WAV-wrapped PCM chunk plus a monotonically increasing sequence number,
     *  mirroring CameraController.Frame. */
    public static final class Chunk {
        public final byte[] wav;
        public final long seq;

        Chunk(byte[] wav, long seq) {
            this.wav = wav;
            this.seq = seq;
        }
    }

    /** Subscribes to receive every audio chunk as it's produced (called on the audio
     *  thread - must not block, and must not call back into AudioController). */
    public interface ChunkListener {
        void onChunk(Chunk chunk);
    }

    private HandlerThread audioThread;
    private Handler audioHandler;
    private volatile AudioRecord audioRecord;
    private volatile boolean recording = false;
    private volatile long chunkSeq = 0;
    private final Set<ChunkListener> listeners = new CopyOnWriteArraySet<>();

    /** Result of opening the mic: null means success. */
    public static final class StartResult {
        public final String error;
        private StartResult(String error) { this.error = error; }
        static StartResult ok() { return new StartResult(null); }
        static StartResult fail(String error) { return new StartResult(error); }
    }

    private void startAudioThreadIfNeeded() {
        if (audioThread == null) {
            audioThread = new HandlerThread("AudioControllerThread");
            audioThread.start();
            audioHandler = new Handler(audioThread.getLooper());
        }
    }

    /**
     * Opens the mic (if not already open) and starts continuous recording. Safe to call
     * repeatedly - a no-op if already recording. Blocks the calling thread (must NOT be
     * the main thread) until recording has either started or failed.
     */
    public StartResult start(long timeoutMs) {
        // 2026-08 修正: 之前這裡只 check audioRecord != null 就當「已經開著」return
        // ok - 但 stopIfIdle()/shutdown() 一開始就立刻 recording=false, audioRecord
        // 要等 readLoop() 那個 blocking read() (最久等整個 CHUNK_MS) 完成才會真正
        // release 成 null。在這段「recording 已經 false, 但 audioRecord 還未
        // null」的窗口, 如果新一輪 handleMicStream() 剛好進來 call start(), 這裡會
        // 見到 audioRecord != null 就立刻假裝成功 - 但實際上沒有再開新一輪
        // readLoop(), 舊那個 readLoop() 很快就會發現 recording==false 自行結束、
        // release 掉 audioRecord。結果新的 HTTP client 雖然 subscribe() 了
        // listener, 但永遠沒有 onChunk() 被 call, handleMicStream() 那個
        // queue.take() 就會永久阻塞 - 前端表現為按了聽但完全靜音、都不會有任何
        // error/reconnect (因為 HTTP response 一早已經 200 OK 了)。
        //
        // 加上 recording 這個 flag 一起 check: 只有兩者都還成立才當「已經開著,
        // 不用再開一次」, 否則就算 audioRecord 還未 null 都應該走下去 - 下面
        // audioHandler.post() 那個 Runnable 會排在 stopIfIdle()/shutdown() 早前
        // post 過來那個 release Runnable 之後才執行 (同一條 HandlerThread, FIFO
        // queue), 所以到真正執行的時候舊的 audioRecord 實際上一定已經 release 完,
        // 不會撞到「同一時間兩個 AudioRecord 並存」。
        if (audioRecord != null && recording) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startAudioThreadIfNeeded();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                // 這裡要再驗一次 recording (不只是 audioRecord != null) - 原因和
                // start() 頭段的 comment 一樣: 這個 Runnable 排在 audioHandler 的
                // queue 尾, 前面可能還有 stopIfIdle()/shutdown() 早前 post 過來那個
                // 「等 readLoop 結束」的 Runnable 還沒執行完 (readLoop() 本身也是在
                // 這條 thread 上跑著 blocking read(), 要等它完成才會執行到後面排著
                // 隊的 Runnable)。走到這一刻如果 audioRecord 還未 null 但
                // recording 已經是 false, 就代表舊的 recording session 快要結束
                // 但還沒真正 release - 這時候不應該假裝「已經開著」就走人, 否則
                // 很快 audioRecord 就會被舊 readLoop() release 成 null, 而這一次
                // start() 的 caller 完全不知道, 沒有再開新一輪 readLoop() 之下,
                // subscribe() 了的 listener 就永遠收不到任何 chunk。應該走下去重新
                // 開一個新的 AudioRecord + readLoop()。
                if (audioRecord != null && recording) {
                    latch.countDown();
                    return;
                }
                int minBufBytes = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
                if (minBufBytes <= 0) {
                    error.set("AudioRecord.getMinBufferSize() returned " + minBufBytes
                            + " - sample rate/format not supported on this hardware");
                    latch.countDown();
                    return;
                }
                // A few times the minimum so the recording thread has headroom before
                // the driver's own ring buffer would start overwriting unread audio.
                int bufBytes = minBufBytes * 4;
                try {
                    AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, bufBytes);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        rec.release();
                        error.set("AudioRecord failed to initialize (state="
                                + rec.getState() + ")");
                        latch.countDown();
                        return;
                    }
                    rec.startRecording();
                    if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        rec.release();
                        error.set("AudioRecord.startRecording() did not enter RECORDING state");
                        latch.countDown();
                        return;
                    }
                    audioRecord = rec;
                    recording = true;
                    Log.i(TAG, "AudioRecord started: " + SAMPLE_RATE_HZ + "Hz mono 16-bit, "
                            + "buffer=" + bufBytes + " bytes");
                } catch (Exception e) {
                    error.set("AudioRecord open failed: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                latch.countDown();
                readLoop();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out starting AudioRecord");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while starting AudioRecord");
        }
        if (error.get() != null) {
            return StartResult.fail(error.get());
        }
        return StartResult.ok();
    }

    /** Runs on the audio thread: reads PCM continuously and hands off WAV-wrapped
     *  chunks to every subscriber until recording is stopped. */
    private void readLoop() {
        int bytesPerChunk = (SAMPLE_RATE_HZ * CHUNK_MS / 1000) * 2; // 16-bit = 2 bytes/sample
        byte[] pcmBuf = new byte[bytesPerChunk];

        while (recording && audioRecord != null) {
            int totalRead = 0;
            while (totalRead < pcmBuf.length && recording) {
                int n = audioRecord.read(pcmBuf, totalRead, pcmBuf.length - totalRead);
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read() returned error code " + n);
                    recording = false;
                    break;
                }
                totalRead += n;
            }
            if (!recording || totalRead <= 0) {
                break;
            }
            byte[] wav = wrapPcmAsWav(pcmBuf, totalRead);
            Chunk chunk = new Chunk(wav, ++chunkSeq);
            for (ChunkListener l : listeners) {
                l.onChunk(chunk);
            }
        }

        // Loop exited (recording set false, idle timeout, or a read error) - release
        // here rather than requiring every caller path to remember to.
        AudioRecord rec = audioRecord;
        audioRecord = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Wraps raw PCM in a standard 44-byte WAV header so each chunk is independently
     *  decodable by a browser's AudioContext.decodeAudioData(). */
    private static byte[] wrapPcmAsWav(byte[] pcm, int pcmLength) {
        int byteRate = SAMPLE_RATE_HZ * 2; // mono, 16-bit
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcmLength);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcmLength);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16); // fmt chunk size
        header.putShort((short) 1); // PCM
        header.putShort((short) 1); // mono
        header.putInt(SAMPLE_RATE_HZ);
        header.putInt(byteRate);
        header.putShort((short) 2); // block align (channels * bytes/sample)
        header.putShort((short) 16); // bits per sample
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcmLength);
        out.write(header.array(), 0, 44);
        out.write(pcm, 0, pcmLength);
        return out.toByteArray();
    }

    public void subscribe(ChunkListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(ChunkListener listener) {
        listeners.remove(listener);
    }

    /**
     * Stops recording and releases the mic. Only releases when there are no remaining
     * stream subscribers - call this from a client-disconnect path, not on a fixed
     * schedule, so one browser tab closing doesn't cut the stream out from under
     * another that's still listening.
     *
     * 2026-08 修正: 之前這裡沒有同步等待 readLoop() 真正 release 掉 audioRecord 就立刻
     * return - 和 shutdown() 之前那個 bug 一模一樣, 但 shutdown() 早前已經修正了,
     * 這個 method 漏改了。readLoop() 本身在 audioHandler 那條 background thread 上
     * 阻塞式跑著 audioRecord.read(...), 這是一個 blocking call, 會等到有下一個
     * audio buffer 才返回 (看 CHUNK_MS, 要一陣). recording=false 之後, readLoop() 要
     * 等那次 read() 完成才會發現、接著才 audioRecord.release()/audioRecord=null。
     *
     * 這段「等 read() 完成」的時間窗口, 加上前端 app-mic.js 的 auto-reconnect
     * (10 秒靜音逾時 -> HTTP stream 斷開 -> 1 秒後重連) 讓問題實際可見: 如果新一輪
     * handleMicStream() (新 HTTP thread) 剛好撞到這個窗口 call audioController.start(),
     * start() 見到 audioRecord 還不是 null 就立刻 "return StartResult.ok()" 假裝
     * 開了新一輪 recording, 但實際上沒有 subscribe 到新一輪 read loop - 舊那個
     * readLoop() 很快就會 release 掉 audioRecord, 讓新 HTTP stream 之後完全
     * 收不到任何 chunk, 觸發下一次 10 秒逾時, 讓 mic 好像「反反覆覆被 alpha2
     * 自己拿回」。跟 shutdown() 的做法看齊: 用一個 post 到 audioHandler 的
     * Runnable + CountDownLatch, 等實際 release 完成才返回。
     */
    public void stopIfIdle() {
        if (!listeners.isEmpty()) {
            return;
        }
        recording = false; // readLoop() notices and releases on its own thread
        if (audioHandler == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                // readLoop() 本身跑在這條 audio thread, 它的 while 循環一見到
                // recording=false 就會自然完成並做完 release() —— 這個 Runnable
                // post 到同一條 handler 的 queue, 保證在 readLoop() 那個 Runnable
                // 之後才執行, 所以走到這裡的時候 audioRecord 一定已經 release 了。
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True if no stream client is currently subscribed - i.e. stopIfIdle() will
     *  actually release the mic rather than leave it running for another listener. */
    public boolean hasNoListeners() {
        return listeners.isEmpty();
    }

    public void shutdown() {
        // 2026-08 修正: 之前這裡沒有同步等待 readLoop() 收尾就立刻 quitSafely()。
        // recording=false 之後, readLoop() 要多跑一個 loop iteration 才會發現、
        // 接著才做 audioRecord.release() —— 這個 release 本身是在 audioHandler
        // 那條 audio thread 上做的 posted Runnable 裡跑著, quitSafely() 不會
        // 中斷它, 但如果 shutdown() 之後很快又有人 start(), 新一輪
        // startAudioThreadIfNeeded() 會開一條新 HandlerThread, 有機會和舊那條
        // 還在做 release() 的 audio thread 短暫並行, 兩邊都拿著
        // AudioRecord/audioRecord 這個共享狀態。跟 CameraController.shutdown()/
        // forceStopAndWait() 的做法看齊: 用一個 post 到 audioHandler 的
        // Runnable + CountDownLatch, 等實際 release 完成才返回, 讓 caller 不用自己
        // 記得留時間差。
        if (audioHandler == null) {
            recording = false;
            if (audioThread != null) {
                audioThread.quitSafely();
            }
            return;
        }
        recording = false;
        final CountDownLatch latch = new CountDownLatch(1);
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                // readLoop() 本身跑在這條 audio thread, 它的 while 循環一見到
                // recording=false 就會自然完成並做完 release() —— 這個 Runnable
                // post 到同一條 handler 的 queue, 保證在 readLoop() 那個 Runnable
                // 之後才執行, 所以走到這裡的時候 audioRecord 一定已經 release 了。
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (audioThread != null) {
            audioThread.quitSafely();
        }
    }
}
