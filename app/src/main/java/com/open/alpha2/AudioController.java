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
    // 2026-08 改返 16000 (由 8000 升返上): 當初落去 8000 淨係為咗同已經永久停用
    // 咗嘅 walkie-talkie (startTalk() 而家已經無條件直接 return, 見 app-mic.js)
    // 上傳嗰條路對齊 sample rate - 而家嗰個理由已經唔存在, 用戶指定連埋
    // walkie-talkie 嗰兩個檔案 (AudioPlaybackController.java, app-mic.js 嘅
    // TALK_TARGET_SAMPLE_RATE) 都一齊拉返上 16000, 保持三腳一致, 即使
    // walkie-talkie 依家實際用唔到。
    //
    // 注意: 8000 嗰陣仲有第二個理由 - decodeAudioData() 喺呢部機 (RK3288) CPU
    // 嘅逐 chunk decode 負擔, 同「越聽越慢」個歷史 bug 有關 (見
    // MIC_MAX_PENDING_CHUNKS/micDrainLoop 嗰輪修法)。16000 嘅 bytes/sec 係
    // 8000 嘅兩倍, decodeAudioData() 要處理嘅 payload 都大咗兩倍 - 如果日後
    // 喺呢部機實測又見到聽聲越聽越慢/斷斷續續, 呢個係第一個要懷疑嘅方向,
    // 到時可以諗縮短 CHUNK_MS 嚟抵消 (細啲嘅 chunk, 但更頻密嘅 decode 調用),
    // 或者退返做 8000。(2026-08 後續: 而家已經冧咗成套 decodeAudioData()
    // 播放機制, 改用 ScriptProcessorNode, 呢個顧慮已經唔再適用 - 見
    // app-mic.js 開頭嗰段 comment。)
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
        // 2026-08 修正: 之前呢度淨係 check audioRecord != null 就當「已經開緊」return
        // ok - 但 stopIfIdle()/shutdown() 一開始就即刻 recording=false, audioRecord
        // 要等 readLoop() 嗰個 blocking read() (最耐等成個 CHUNK_MS) 完成先會真正
        // release 做返 null。喺呢段「recording 已經 false, 但 audioRecord 仲未
        // null」嘅窗口, 如果新一輪 handleMicStream() 撞正嚟 call start(), 呢度會
        // 見到 audioRecord != null 就即刻假裝成功 - 但實際上冇再開新一輪
        // readLoop(), 舊嗰個 readLoop() 好快就會發現 recording==false 自行完結、
        // release 咗個 audioRecord。結果新嘅 HTTP client 雖然 subscribe() 咗
        // listener, 但永遠冇 onChunk() 被 call, handleMicStream() 嗰個
        // queue.take() 就會永久阻塞 - 前端表現為撳咗聽但完全靜音、都唔會有任何
        // error/reconnect (因為 HTTP response 一早已經 200 OK 咗)。
        //
        // 加返 recording 呢個 flag 一齊 check: 淨係兩者都仲成立先當「已經開緊,
        // 唔使再開一次」, 否則就算 audioRecord 仲未 null 都應該行落去 - 落面
        // audioHandler.post() 嗰個 Runnable 會排喺 stopIfIdle()/shutdown() 早前
        // post 落嚟嗰個 release Runnable 之後先執行 (同一條 HandlerThread, FIFO
        // queue), 所以到真正執行嗰陣舊嘅 audioRecord 實際上一定已經 release 晒,
        // 唔會撞到「同一時間兩個 AudioRecord 並存」。
        if (audioRecord != null && recording) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startAudioThreadIfNeeded();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                // 呢度要再驗一次 recording (唔淨係 audioRecord != null) - 原因同
                // start() 頭段個 comment 一樣: 呢個 Runnable 排喺 audioHandler 個
                // queue 尾, 前面可能仲有 stopIfIdle()/shutdown() 早前 post 落嚟嗰個
                // 「等 readLoop 完結」嘅 Runnable 未執行完 (readLoop() 本身都係喺
                // 呢條 thread 度行緊 blocking read(), 要等佢完成先會執行到後面排緊
                // 隊嘅 Runnable)。行到呢一刻如果 audioRecord 仲未 null 但
                // recording 已經係 false, 即係代表舊嘅 recording session 快將完結
                // 但仲未真正 release - 呢個時候唔應該假裝「已經開緊」就走人, 否則
                // 好快 audioRecord 就會被舊 readLoop() release 做 null, 而呢一次
                // start() 嘅 caller 完全唔知道, 冇再開新一輪 readLoop() 之下,
                // subscribe() 咗嘅 listener 就永遠收唔到任何 chunk。應該落去重新
                // 開一個新嘅 AudioRecord + readLoop()。
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
     * 2026-08 修正: 之前呢度冇同步等待 readLoop() 真正 release 咗 audioRecord 就即刻
     * return - 同 shutdown() 之前嗰個 bug 一模一樣, 但 shutdown() 早前已經修正咗,
     * 呢個 method 執漏咗。readLoop() 本身喺 audioHandler 嗰條 background thread 度
     * 阻塞式行緊 audioRecord.read(...), 呢個係一個 blocking call, 會等到有下一個
     * audio buffer 先返 (睇 CHUNK_MS, 有排). recording=false 之後, readLoop() 要
     * 等嗰次 read() 完成先會發現、跟住先 audioRecord.release()/audioRecord=null。
     *
     * 呢段「等緊 read() 完成」嘅時間窗口, 加埋前端 app-mic.js 嘅 auto-reconnect
     * (10 秒靜音逾時 -> HTTP stream 斷開 -> 1 秒後重連) 令問題實際可見: 如果新一輪
     * handleMicStream() (新 HTTP thread) 撞正呢個窗口 call audioController.start(),
     * start() 見到 audioRecord 仲未係 null 就即刻 "return StartResult.ok()" 假裝
     * 開咗新一輪 recording, 但實際上冇 subscribe 到新一輪 read loop - 舊嗰個
     * readLoop() 好快就會 release 咗個 audioRecord, 令個新 HTTP stream 之後完全
     * 收唔到任何 chunk, 觸發下一次 10 秒逾時, 令 mic 好似「反反覆覆被 alpha2
     * 自己攞返」。跟 shutdown() 嘅做法睇齊: 用一個 post 落 audioHandler 嘅
     * Runnable + CountDownLatch, 等實際 release 完成先返。
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
                // readLoop() 本身跑緊喺呢條 audio thread, 佢個 while 循環一見到
                // recording=false 就會自然完成同做埋 release() —— 呢個 Runnable
                // post 落同一條 handler 嘅 queue, 保證喺 readLoop() 嗰個 Runnable
                // 之後先執行, 所以行到呢度嗰陣 audioRecord 一定已經 release 咗。
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
        // 2026-08 修正: 之前呢度冇同步等待 readLoop() 收尾就即刻 quitSafely()。
        // recording=false 之後, readLoop() 要行多一個 loop iteration 先會發現、
        // 跟住先做 audioRecord.release() —— 呢個 release 本身係喺 audioHandler
        // 嗰條 audio thread 度做嘅 posted Runnable 入面行緊, quitSafely() 唔會
        // 中斷佢, 但如果 shutdown() 之後好快又有人 start(), 新一輪
        // startAudioThreadIfNeeded() 會開一條新 HandlerThread, 有機會同舊嗰條
        // 仲喺度做緊 release() 嘅 audio thread 短暫並行, 兩邊都摞住
        // AudioRecord/audioRecord 呢個共享狀態。跟 CameraController.shutdown()/
        // forceStopAndWait() 嘅做法睇齊: 用一個 post 落 audioHandler 嘅
        // Runnable + CountDownLatch, 等實際 release 完成先返, 等 caller 唔使自己
        // 記得留返時間差。
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
                // readLoop() 本身跑緊喺呢條 audio thread, 佢個 while 循環一見到
                // recording=false 就會自然完成同做埋 release() —— 呢個 Runnable
                // post 落同一條 handler 嘅 queue, 保證喺 readLoop() 嗰個 Runnable
                // 之後先執行, 所以行到呢度嗰陣 audioRecord 一定已經 release 咗。
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
