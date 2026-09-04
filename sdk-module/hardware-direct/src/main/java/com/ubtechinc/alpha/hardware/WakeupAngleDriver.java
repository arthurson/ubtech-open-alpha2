package com.ubtechinc.alpha.hardware;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.iflytek.cae.jni.CAEJni;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 唤醒词声源定向驱动（1.1.7.3 原装 CAE 链的 clean-room 复刻）。
 *
 * <p>链路：App AudioRecord（16k mono，先验证整链；多通道阵列为 stage 2）
 * → {@code CAEAudioWrite} → native 唤醒回调 {@code ivwCb(angle, beam, keyword,
 * power, score)} → {@link Listener#onWakeup}。角度经调用方（如 MainActivity）
 * 以旧格式 {@code {"absoluteAngle":N}} 发 EventBus，走既有 servo19 管道，
 * 本类不直接碰舵机。</p>
 *
 * <p>bringup 按原装语义：{@code CAENew(resPath, ...)} 取首个返回正 handle 的
 * 参数组合（逐个试并打 log，成功即停）；{@code setCAEWParam} 等调优留空，
 * 用引擎默认值。{@code .jet} 资源由调用方备好传文件路径进来。</p>
 */
public final class WakeupAngleDriver {
    private static final String TAG = "WakeupAngle";

    /** 唤醒回调（angle 单位与取值范围以实测 log 为准，初版原样透传）。 */
    public interface Listener {
        void onWakeup(int angle, int beam, String keyword, float power, int score);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread feedThread;
    private volatile int handle = -1;
    private volatile Listener listener;
    private volatile long fedBytes;
    private volatile long callbackCount;

    /**
     * native 回寫引擎 handle 用（CAENew 內 SetStaticIntField，名同類型必須逐字啱；
     * 值為負亦正常——native 指针轉 int）。
     */
    @SuppressWarnings("unused")
    public static int caeHandle = 0;

    /** native 回调对象：方法名+签名必须与 .so 查找的一致（见 CAEJni javadoc）。 */
    @SuppressWarnings("unused")
    public void ivwCb(int angle, int beam, float power, int score, int ch, char[] keyword) {
        callbackCount++;
        String kw = keyword != null ? new String(keyword).trim() : "";
        Log.i(TAG, "wakeup angle=" + angle + " beam=" + beam + " kw=" + kw
                + " power=" + power + " score=" + score + " ch=" + ch);
        Listener l = listener;
        if (l != null) {
            try {
                l.onWakeup(angle, beam, kw, power, score);
            } catch (Throwable t) {
                Log.w(TAG, "wakeup listener threw", t);
            }
        }
    }

    /** 5 参变体（原装 CAEEngine 同时声明；native 若查它亦能接住）。 */
    @SuppressWarnings("unused")
    public void ivwCb(int angle, int beam, float power, int score, int ch) {
        ivwCb(angle, beam, power, score, ch, null);
    }

    /** native 音频回调（只计数，不处理）。 */
    @SuppressWarnings("unused")
    public void audioCb(byte[] data, int a, int b, int c) {
        callbackCount++;
    }

    /**
     * 启动。resFile 为已解压的 {@code ivw_resource_three_cn.jet}。
     * @return true=引擎已起且喂数线程已跑；false 见 log（.so 缺失/CAENew 全败/mic 打不开）。
     */
    public synchronized boolean start(File resFile, Listener l) {
        if (running.get()) return true;
        if (!CAEJni.isLoaded()) {
            Log.w(TAG, "three_wakeup_cae.so not loaded");
            return false;
        }
        if (resFile == null || !resFile.isFile()) {
            Log.w(TAG, "res missing: " + resFile);
            return false;
        }
        try {
            CAEJni.DebugLog(true);
        } catch (Throwable t) {
            Log.w(TAG, "DebugLog threw", t);
        }
        String resPath = resFile.getAbsolutePath();
        // 注意：native 只有一個 C 實現（短符號名），按 5 參佈局讀參；
        // 6 參 overload 的 int 會被當成 jstring（0 即 null）直接 abort，
        // 故只調 5 參版。s2/s3 是 native 在 userdata 上查找的回调方法名
        // （log 作证：“CAENew | ivwCb:%s”，空字串即 NoSuchMethodError 炒），
        // s4 語義未明，先空字串。
        int h = -1;
        try {
            h = CAEJni.CAENew(resPath, "ivwCb", "audioCb", "", this);
            Log.i(TAG, "CAENew5 -> " + h);
        } catch (Throwable t) {
            Log.w(TAG, "CAENew5 threw: " + t.getMessage());
            return false;
        }
        if (h == 0) {
            Log.w(TAG, "CAENew returned 0 for " + resPath);
            return false;
        }
        handle = h;
        listener = l;
        running.set(true);
        fedBytes = 0;
        feedThread = new Thread(new Runnable() {
            @Override public void run() { feedLoop(); }
        }, "WakeupFeed");
        feedThread.setDaemon(true);
        feedThread.start();
        Log.i(TAG, "started handle=" + h + " res=" + resPath);
        return true;
    }

    public synchronized void stop() {
        running.set(false);
        Thread t = feedThread;
        feedThread = null;
        if (t != null) {
            try { t.interrupt(); t.join(1000); } catch (InterruptedException ignore) {}
        }
        int h = handle;
        handle = -1;
        if (h != 0) {
            try {
                CAEJni.CAEDestroy(h);
            } catch (Throwable th) {
                Log.w(TAG, "CAEDestroy threw", th);
            }
        }
        Log.i(TAG, "stopped fedBytes=" + fedBytes + " callbacks=" + callbackCount);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** v1 喂数：App 侧 AudioRecord 16k mono（stage 1：先验证整链通不通）。 */
    private void feedLoop() {
        AudioRecord rec = null;
        try {
            int rate = 16000;
            int ch = AudioFormat.CHANNEL_IN_MONO;
            int fmt = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(rate, ch, fmt);
            if (minBuf <= 0) {
                Log.w(TAG, "bad minBuf " + minBuf);
                return;
            }
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, ch, fmt, minBuf * 4);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized");
                return;
            }
            rec.startRecording();
            byte[] buf = new byte[4096];
            while (running.get()) {
                int n;
                try {
                    n = rec.read(buf, 0, buf.length);
                } catch (Throwable t) {
                    Log.w(TAG, "mic read threw", t);
                    break;
                }
                if (n <= 0) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                    continue;
                }
                fedBytes += n;
                int h = handle;
                if (h != 0) {
                    try {
                        int rc = CAEJni.CAEAudioWrite(h, buf, n);
                        if (rc != 0 && (fedBytes < 200000)) {
                            Log.d(TAG, "CAEAudioWrite -> " + rc);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "CAEAudioWrite threw", t);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "feedLoop ended", t);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignore) {}
                try { rec.release(); } catch (Throwable ignore) {}
            }
            running.set(false);
        }
    }
}
