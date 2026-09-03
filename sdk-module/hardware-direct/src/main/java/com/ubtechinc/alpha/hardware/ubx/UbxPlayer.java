package com.ubtechinc.alpha.hardware.ubx;

import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectChestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * .ubx 动作播放器（pure-direct，clean-room）。
 *
 * <p>播放语义按 1.1.7.3 {@code a.m} 私有发送原文：
 * <pre>
 * 每帧：angles[i] = LE32(e[i*8+8..+4]) & 0xFF（groups = f()/8，上限 20；
 * groups&lt;20 时 angles[18] = angles[19] = 0，此处解析器已预抽取为 angles20）；
 * 胸串口：cmd 3 [20轴byte + short time]，time 实参 = b * timeBase。
 * </pre>
 * v1 简化（与原厂 f.b.f 编排等价性说明）：
 * <ul>
 *   <li>只播 a.d 链定时帧（{@code track.frames}，d.f 位姿参考已分流至
 *       {@code track.keyframes} 不在此列；另以 {@code groupBytes &gt; 0} 双保险），
 *       按 {@code (start, end)} 排序后顺序发送；d.f 叶原厂亦不经 f.i/a.d 播放，
 *       只作位姿参考，此处跳过不发送（兼避全零位姿抖舵机）。</li>
 *   <li>逐帧发送后 sleep 该帧时长（动作用时 = Σ 各帧 time），stop() 中断即停，舵机保持末位姿，
 *       不碰省电/使能位。</li>
 * </ul>
 * </p>
 */
public final class UbxPlayer {
    private static final String TAG = "UbxPlayer";
    private static final int MIN_TIME_MS = 20;
    private static final int MAX_TIME_MS = 30000;

    private Thread playThread;
    private volatile boolean stopReq;
    private volatile String currentName = "";
    private volatile int framesSent;
    private volatile int framesTotal;
    private volatile String lastError = "";

    /** 开始播放（已在播则返回 false）。解析好的 UbxFile 由调用方传入，失败原因见 {@link #lastError()}。 */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest) {
        if (playThread != null && playThread.isAlive()) {
            lastError = "already playing " + currentName;
            return false;
        }
        List<UbxFile.UbxServoFrame> timed = new ArrayList<>();
        if (ubx != null) {
            for (UbxFile.UbxTrack t : ubx.tracks) {
                for (UbxFile.UbxServoFrame sf : t.frames) {
                    if (sf.groupBytes > 0) timed.add(sf);
                }
            }
        }
        Collections.sort(timed, new Comparator<UbxFile.UbxServoFrame>() {
            @Override public int compare(UbxFile.UbxServoFrame a, UbxFile.UbxServoFrame b) {
                int d = a.start - b.start;
                return d != 0 ? d : a.end - b.end;
            }
        });
        if (timed.isEmpty()) {
            lastError = "no timed servo frames";
            return false;
        }
        if (chest == null || !chest.isAvailable()) {
            lastError = "chest not available";
            return false;
        }
        stopReq = false;
        framesSent = 0;
        framesTotal = timed.size();
        currentName = name != null ? name : "";
        lastError = "";
        final List<UbxFile.UbxServoFrame> seq = timed;
        final DirectChestController c = chest;
        playThread = new Thread(new Runnable() {
            @Override public void run() { loop(seq, c); }
        }, "ubx-play");
        playThread.start();
        Log.i(TAG, "play start " + currentName + " frames=" + framesTotal);
        return true;
    }

    private void loop(List<UbxFile.UbxServoFrame> seq, DirectChestController chest) {
        try {
            for (UbxFile.UbxServoFrame sf : seq) {
                if (stopReq || Thread.currentThread().isInterrupted()) break;
                int[] angles = new int[20];
                for (int i = 0; i < 20; i++) angles[i] = sf.angles20[i] & 0xFF;
                int ms = sf.moveTimeHintMs;
                if (ms < MIN_TIME_MS) ms = MIN_TIME_MS;
                if (ms > MAX_TIME_MS) ms = MAX_TIME_MS;
                try {
                    chest.playAllServos(angles, (short) ms);
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e.getMessage());
                }
                framesSent++;
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            Log.i(TAG, "play end " + currentName + " sent=" + framesSent + "/" + framesTotal);
        }
    }

    /** 停止播放（中断发送线程，舵机保持末位姿）。 */
    public synchronized void stop() {
        stopReq = true;
        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(2000);
            } catch (InterruptedException ignore) {
                // ignore
            }
            playThread = null;
        }
    }

    public synchronized boolean isPlaying() {
        return playThread != null && playThread.isAlive();
    }

    public int sent() { return framesSent; }

    public int total() { return framesTotal; }

    public String name() { return currentName; }

    public String lastError() { return lastError; }

    /** /api/ubx/status 直接用的 JSON（调用方包一层 ok 即成）。 */
    public synchronized String statusJson() {
        boolean playing = playThread != null && playThread.isAlive();
        return "{\"ok\":true,\"playing\":" + playing
                + ",\"name\":\"" + esc(currentName) + "\""
                + ",\"sent\":" + framesSent + ",\"total\":" + framesTotal
                + ",\"lastError\":\"" + esc(lastError) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
