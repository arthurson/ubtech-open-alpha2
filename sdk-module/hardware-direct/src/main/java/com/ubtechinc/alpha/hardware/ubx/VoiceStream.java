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
 * 變速配樂流播（decode + 線性重採樣 + AudioTrack，API 19 可用 API 面）。
 *
 * <p>背景：機身 Android 5.1（API 22），{@code MediaPlayer.setPlaybackParams}
 * 要 API 23+，無變速能力。這裡按官方配樂語義（槽位起播、播至多 b*timeBase、
 * 切幀打斷）自行實現變速：MediaExtractor + MediaCodec 解 mp3 → PCM，
 * 按步進 {@code speed} 線性插值重採樣（變速不變調是 Sonic/WSOLA 量級的工作，
 * 此處與速度鎖定 pitch，即“磁帶式”變速，特此說明），
 * 經 AudioTrack(STREAM_MUSIC) 播出。</p>
 *
 * <p>流式環形輸入（只留約 0.4s 解碼幀），不整曲進記憶體；輸出按小塊寫入，
 * stop 延遲不超過一塊（約 50ms）。所用 MediaCodec API（getInputBuffers 等）
 * 均為 API 16+，在 minSdk 19 上安全。</p>
 */
final class VoiceStream {
    private static final String TAG = "VoiceStream";
    private static final int RING_FRAMES = 16384;
    private static final int OUT_CHUNK_FRAMES = 2048;

    /** 另一線程可見的停止請求（UbxPlayer.stop/stopVoice 置位）。 */
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
     * 播 file，變速 speed（=輸出幀對應的輸入步進，2x 取每 2 幀其一），
     * 至自然播完 / 到達 deadlineMonoMs / stop 三者之一即收尾（stop+release 全關）。
     * 阻塞調用，設計跑在 UbxPlayer 的 voice 線程。
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
            // 畸形 mp3（sampleRate 0/離譜值）唔好落到 new AudioTrack 先炸，早拒。
            if (sampleRate < 8000 || sampleRate > 48000) {
                Log.w(TAG, "bad sample rate " + sampleRate + ": " + file.getName());
                return;
            }
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

    /** @return 結束原因：eos（自然播完）/bound（b*timeBase 自停）/stopped（打斷）/track-dead. */
    private String pump(MediaExtractor ex, MediaCodec codec, AudioTrack track, int channels,
                        float speed, long deadlineMonoMs, StopFlag stop) {
        ByteBuffer[] inBufs = codec.getInputBuffers();
        ByteBuffer[] outBufs = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] ring = new short[RING_FRAMES * channels];
        int ringStart = 0; // 已消費絕對幀數（只加不減；array 內數據已前移，寫位用 ringAvail 相對下標）
        int ringAvail = 0; // 環內有效輸入幀數（相對下標 0..ringAvail-1）
        boolean inputEos = false;
        boolean outEosSeen = false;
        double pos = 0; // 當前輸出幀對應的輸入幀位置（分數）
        short[] out = new short[OUT_CHUNK_FRAMES * channels];
        int stallRounds = 0; // 連續無進展計數：壞歌唔好空轉到 bound（可以幾分鐘）

        while (true) {
            if (abort || stop.isStopped()) return "stopped";
            if (System.nanoTime() / 1000000L >= deadlineMonoMs) return "bound"; // b*timeBase 自停
            // 喂輸入（環將滿時停喂，免慢速下溢出丟尾；空位只看 ringAvail，
            // ringStart 係絕對已消費數，唔可以計入下標）
            if (!inputEos && (RING_FRAMES - ringAvail) > 4096) {
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
            // 收輸出 → 環
            boolean progressed = false;
            for (int k = 0; k < 4; k++) {
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    progressed = true;
                    if (info.size > 0) {
                        ByteBuffer ob = outBufs[oi];
                        int frames = info.size / (2 * channels);
                        int space = RING_FRAMES - ringAvail;
                        if (frames > space) frames = space; // 環滿則丟尾（極端慢速才發生）
                        ob.position(info.offset);
                        for (int f = 0; f < frames; f++) {
                            for (int c = 0; c < channels; c++) {
                                ring[ringAvail * channels + c] = ob.getShort();
                            }
                            ringAvail++;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEosSeen = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = codec.getOutputBuffers();
                } else {
                    break; // TRY_AGAIN_LATER 或 FORMAT_CHANGED（mp3 全程同參，沿用初值）
                }
            }
            // 消費：線性插值產生一塊輸出
            int produced = 0;
            while (produced < OUT_CHUNK_FRAMES) {
                int need = (int) Math.floor(pos) + 2 - ringStart; // 相對環首
                if (need > ringAvail) {
                    if (outEosSeen && ringAvail > 0 && pos < ringStart + ringAvail) {
                        // 尾幀：用末幀頂住
                    } else if (outEosSeen) {
                        break;
                    } else {
                        break; // 等更多輸入
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
                // 整理環：消費過半則前移
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
                stallRounds = 0; // 有出聲即係有進展，清計數
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
                // 輸入未 EOS 但幾百輪都冇進展（decoder 卡死/壞檔）：早退，唔空轉。
                // 正常播實有進展（每輪最多等 4×10ms dequeue + 5ms sleep），
                // 400 輪 ≈ 20s，遠超正常抖動。
                if (++stallRounds > 400) return "stalled";
                try { Thread.sleep(5); } catch (InterruptedException e) { return "stopped"; }
            } else {
                stallRounds = 0;
            }
        }
    }
}
