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

    /** Blocks up to {@code timeoutMs} for any work already queued on {@code handler}
     *  to finish. Swallows InterruptedException by re-setting the interrupt flag,
     *  matching the original call sites. No-op if handler is null. */
    static void awaitQueueDrain(Handler handler, long timeoutMs) {
        if (handler == null) return;
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
