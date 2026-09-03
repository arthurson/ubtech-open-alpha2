package com.ubtechinc.alpha.hardware.ubx;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 变速配乐流播（decode + 线性重采样 + AudioTrack，API 19 可用 API 面）。
 *
 * <p>背景：机身 Android 5.1（API 22），{@code MediaPlayer.setPlaybackParams}
 * 要 API 23+，无变速能力。这里按官方配乐语义（槽位起播、播至多 b*timeBase、
 * 切帧打断）自行实现变速：MediaExtractor + MediaCodec 解 mp3 → PCM，
 * 按步进 {@code speed} 线性插值重采样（变速不变调是 Sonic/WSOLA 量级的工作，
 * 此处与速度锁定 pitch，即“磁带式”变速，特此说明），
 * 经 AudioTrack(STREAM_MUSIC) 播出。</p>
 *
 * <p>流式环形输入（只留约 0.4s 解码帧），不整曲进内存；输出按小块写入，
 * stop 延迟不超过一块（约 50ms）。所用 MediaCodec API（getInputBuffers 等）
 * 均为 API 16+，在 minSdk 19 上安全。</p>
 */
final class VoiceStream {
    private static final String TAG = "VoiceStream";
    private static final int RING_FRAMES = 16384;
    private static final int OUT_CHUNK_FRAMES = 2048;

    /** 另一线程可见的停止请求（UbxPlayer.stop/stopVoice 置位）。 */
    interface StopFlag { boolean isStopped(); }

    private volatile boolean abort;
    private volatile AudioTrack liveTrack;

    void abort() {
        abort = true;
        AudioTrack t = liveTrack;
        if (t != null) {
            try { t.stop(); } catch (Exception ignore) {
                // ignore
            }
        }
    }

    /**
     * 播 file，变速 speed（=输出帧对应的输入步进，2x 取每 2 帧其一），
     * 至自然播完 / 到达 deadlineMonoMs / stop 三者之一即收尾（stop+release 全关）。
     * 阻塞调用，设计跑在 UbxPlayer 的 voice 线程。
     */
    void play(File file, float speed, long deadlineMonoMs, StopFlag stop) {
        abort = false;
        MediaExtractor ex = null;
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(file.getAbsolutePath());
            int audioIdx = -1;
            MediaFormat srcFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioIdx = i;
                    srcFmt = f;
                    break;
                }
            }
            if (audioIdx < 0 || srcFmt == null) {
                Log.w(TAG, "no audio track: " + file.getName());
                return;
            }
            String mime = srcFmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(audioIdx);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(srcFmt, null, null, 0);
            codec.start();
            int sampleRate = srcFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = srcFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (channels < 1) channels = 1;
            if (channels > 2) channels = 2;
            int chCfg = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, 65536);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chCfg,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
            liveTrack = track;
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "audiotrack init failed");
                return;
            }
            track.play();
            Log.i(TAG, "streaming " + file.getName() + " " + speed + "x sr=" + sampleRate + " ch=" + channels);
            String why = pump(ex, codec, track, channels, speed, deadlineMonoMs, stop);
            Log.i(TAG, "stream end " + file.getName() + " " + why);
        } catch (Exception e) {
            Log.w(TAG, "voice stream failed " + file.getName() + ": " + e.getMessage());
        } finally {
            liveTrack = null;
            if (track != null) {
                try { track.stop(); } catch (Exception ignore) {
                    // ignore
                }
                try { track.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignore) {
                    // ignore
                }
                try { codec.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
            if (ex != null) {
                try { ex.release(); } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    /** @return 结束原因：eos（自然播完）/bound（b*timeBase 自停）/stopped（打断）/track-dead. */
    private String pump(MediaExtractor ex, MediaCodec codec, AudioTrack track, int channels,
                        float speed, long deadlineMonoMs, StopFlag stop) {
        ByteBuffer[] inBufs = codec.getInputBuffers();
        ByteBuffer[] outBufs = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] ring = new short[RING_FRAMES * channels];
        int ringStart = 0; // 已消费帧数（相对下标，满半整理）
        int ringAvail = 0; // 环内有效输入帧数
        boolean inputEos = false;
        boolean outEosSeen = false;
        double pos = 0; // 当前输出帧对应的输入帧位置（分数）
        short[] out = new short[OUT_CHUNK_FRAMES * channels];

        while (true) {
            if (abort || stop.isStopped()) return "stopped";
            if (System.nanoTime() / 1000000L >= deadlineMonoMs) return "bound"; // b*timeBase 自停
            // 喂输入（环将满时停喂，免慢速下溢出丢尾）
            if (!inputEos && (RING_FRAMES - (ringStart + ringAvail)) > 4096) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer ib = inBufs[ii];
                    int n = ex.readSampleData(ib, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            // 收输出 → 环
            boolean progressed = false;
            for (int k = 0; k < 4; k++) {
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    progressed = true;
                    if (info.size > 0) {
                        ByteBuffer ob = outBufs[oi];
                        int frames = info.size / (2 * channels);
                        int space = RING_FRAMES - (ringStart + ringAvail);
                        if (frames > space) frames = space; // 环满则丢尾（极端慢速才发生）
                        ob.position(info.offset);
                        for (int f = 0; f < frames; f++) {
                            for (int c = 0; c < channels; c++) {
                                ring[(ringStart + ringAvail) * channels + c] = ob.getShort();
                            }
                            ringAvail++;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEosSeen = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = codec.getOutputBuffers();
                } else {
                    break; // TRY_AGAIN_LATER 或 FORMAT_CHANGED（mp3 全程同参，沿用初值）
                }
            }
            // 消费：线性插值产生一块输出
            int produced = 0;
            while (produced < OUT_CHUNK_FRAMES) {
                int need = (int) Math.floor(pos) + 2 - ringStart; // 相对环首
                if (need > ringAvail) {
                    if (outEosSeen && ringAvail > 0 && pos < ringStart + ringAvail) {
                        // 尾帧：用末帧顶住
                    } else if (outEosSeen) {
                        break;
                    } else {
                        break; // 等更多输入
                    }
                }
                int i0 = (int) Math.floor(pos);
                double frac = pos - i0;
                int r0 = i0 - ringStart;
                int r1 = r0 + 1;
                if (r0 < 0) { r0 = 0; frac = 0; }
                if (r1 >= ringAvail) r1 = ringAvail - 1;
                for (int c = 0; c < channels; c++) {
                    double v = ring[r0 * channels + c] * (1 - frac) + ring[r1 * channels + c] * frac;
                    out[produced * channels + c] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                }
                produced++;
                pos += speed;
                // 整理环：消费过半则前移
                int consumed = (int) Math.floor(pos) - ringStart;
                if (consumed > RING_FRAMES / 2) {
                    int remain = ringAvail - consumed;
                    if (remain > 0) {
                        System.arraycopy(ring, consumed * channels, ring, 0, remain * channels);
                    }
                    ringStart += consumed;
                    ringAvail = Math.max(0, remain);
                }
            }
            if (produced > 0) {
                byte[] bytes = new byte[produced * channels * 2];
                for (int i = 0; i < produced * channels; i++) {
                    bytes[i * 2] = (byte) (out[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((out[i] >> 8) & 0xFF);
                }
                int off = 0;
                while (off < bytes.length) {
                    if (abort || stop.isStopped()) return "stopped";
                    int w = track.write(bytes, off, bytes.length - off);
                    if (w < 0) return "track-dead"; // track 失效
                    if (w == 0) {
                        try { Thread.sleep(5); } catch (InterruptedException e) { return "stopped"; }
                        continue;
                    }
                    off += w;
                }
            } else if (outEosSeen && pos >= ringStart + ringAvail) {
                return "eos"; // 自然播完
            } else if (!progressed) {
                try { Thread.sleep(5); } catch (InterruptedException e) { return "stopped"; }
            }
        }
    }
}
