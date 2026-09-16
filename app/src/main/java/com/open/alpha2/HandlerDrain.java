package com.open.alpha2;

import android.os.Handler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shared "drain and wait" idiom used by AudioController, AudioPlaybackController and
 * XiaozhiAudioController's stop/shutdown methods: post a no-op Runnable onto a
 * background HandlerThread's Handler and block (up to a timeout) until it runs. Because
 * a Handler's queue is FIFO, this Runnable is guaranteed to run only after any earlier
 * posted work (the read/write/capture loop noticing its stop flag and releasing the
 * underlying AudioRecord/AudioTrack/encoder) has already completed - so by the time
 * this returns, that release has genuinely finished, and it's safe for the caller to
 * immediately start a new session or quitSafely() the thread.
 *
 * Extracted from four near-identical copies (each controller's mic/speaker stop path)
 * during a 2026-09 duplication cleanup - behavior unchanged, this is a straight lift.
 */
final class HandlerDrain {
    private HandlerDrain() { }

    /** 預設排空超時（之前 5 處各自寫裸 2000，收斂到呢度）。 */
    static final long DEFAULT_TIMEOUT_MS = 2000;

    /** Blocks up to {@code timeoutMs} for any work already queued on {@code handler}
     *  to finish. Swallows InterruptedException by re-setting the interrupt flag,
     *  matching the original call sites. No-op (returns false + Log.w) if handler is null.
     * @return true = 排空成功，false = 超時／被打斷／handler null */
    static boolean awaitQueueDrain(Handler handler, long timeoutMs) {
        if (handler == null) {
            android.util.Log.w("HandlerDrain", "awaitQueueDrain with null handler");
            return false;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
