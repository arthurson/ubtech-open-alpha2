package com.ubtechinc.alpha.hardware.ubx;

import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectChestController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * .ubx 动作播放器（pure-direct，clean-room）。
 *
 * <p>播放语义按 1.1.7.3 smali 原文：
 * <pre>
 * f/i$a.run：每轮 sleep(i) 后 a/d.c(1) 前进 1 tick，其中 i = a/p.f() = static
 *   timeBase（type-0 块第一个 int，单位 ms；a/p.b 只是循环 cap 上限，非周期）。
 * a/m.a(I)：tick 累加入 b；b &gt;= c 时切下一帧并即时发送。帧 i 槽位 = (b_i + c_i)
 *   tick（b()=start 为动作时长部，c()=end 为保持部）；发送 time 实参 = b_i * timeBase。
 * 发送包：胸 cmd 3 [20轴byte + short time]，angles[i] = LE32(e[i*8+8..+4]) &amp; 0xFF。
 * 例 tiny.ubx：槽 (20+10)+(20+40)+(20+10)=120 tick × 50ms = 6s。
 * 配乐（a/d type==4 → a/j/a/o，与舵机同一 unit clock 并行）：
 *   每 voice 帧槽位起：打断上一首，起新 o$a 线程播 path = ubx去扩展名/music名；
 *   播至多 b*timeBase（自停），切帧/停止时打断；pause 连歌同停。
 * </pre>
 * v1 简化：
 * <ul>
 *   <li>只播 a.d 链定时帧（servo 看 groupBytes&gt;0；d.f 关键帧表不发送）。</li>
 *   <li>帧序按解析序；多 track 首尾相接；a.h/a.j 窗口偏移忽略。</li>
 *   <li>单调 deadline 调度，累计漂移消除；stop() 中断双线，舵机保持末位姿。</li>
 *   <li>变速（0.5/0.67/1/1.5/2，黏性）：舵机槽位与 move 时长同除以倍率；
 *       配乐 1x 走 MediaPlayer（即时起播），非 1x 走 decode+线性重采样“磁带式”
 *       变速（不变调，0.5x 低沉、2x 尖细，机身 API 22 无 PlaybackParams）。
 *       播緊途中改速由调用方停旧重播即時生效（player 内快照隔离进行中计划）。</li>
 * </ul>
 * </p>
 */
public final class UbxPlayer {
    private static final String TAG = "UbxPlayer";
    private static final int MIN_TIME_MS = 20;
    private static final int MAX_TIME_MS = 30000;

    private Thread playThread;
    private Thread voiceThread;
    private volatile boolean stopReq;
    private volatile boolean voiceStopReq;
    private volatile String currentName = "";
    private volatile int framesSent;
    private volatile int framesTotal;
    private volatile String lastError = "";
    private volatile String voiceSong = "";
    private android.media.MediaPlayer voicePlayer;
    private volatile VoiceStream curStream;

    /** 流播路径用：标记当前歌名（供 status），与 startVoiceLocked 对等。 */
    private synchronized void setVoiceSong(String s) { voiceSong = s != null ? s : ""; }
    /** 变速倍率（黏性，作用于下次播放；播緊途中改只影響下一次）。 */
    private volatile float speed = 1.0f;

    private static final float[] SPEEDS = new float[]{0.5f, 0.67f, 1.0f, 1.5f, 2.0f};

    /** 设变速（仅 0.5/0.67/1/1.5/2 合法）；播緊時由调用方重播即時生效，返回是否接受。 */
    public synchronized boolean setSpeed(float s) {
        for (float v : SPEEDS) {
            if (Math.abs(v - s) < 0.001f) {
                speed = v;
                Log.i(TAG, "speed set " + v + "x (next play)");
                return true;
            }
        }
        return false;
    }

    public float getSpeed() { return speed; }

    /** 一帧的播放项：沿用解析序，槽位与发送时长按 a.m 原文分别计算。 */
    private static final class Slot {
        final UbxFile.UbxServoFrame sf;
        final long slotMs;  // (start+end) * T：本帧槽位
        final int moveMs;   // start * timeBase：cmd3 time 实参（解析器已预计算 moveTimeHintMs）
        Slot(UbxFile.UbxServoFrame sf, long slotMs, int moveMs) {
            this.sf = sf; this.slotMs = slotMs; this.moveMs = moveMs;
        }
    }

    /** voice 播放项：同 clock 下并行 schedule，起播/自停按 a/o 原文。 */
    private static final class VSong {
        final long startMs; // 本槽起点（共享 clock 下偏移）
        final long boundMs; // b*timeBase：mp3 自停上限
        final File file;    // 已解析出的本地 mp3（null 表缺文件，槽照走）
        final String name;
        VSong(long startMs, long boundMs, File file, String name) {
            this.startMs = startMs; this.boundMs = boundMs; this.file = file; this.name = name;
        }
    }

    /** T 回退：track.timeBaseMs &gt; 0 用它；否则用文件 timeBase；再否则 50。 */
    static int resolveTickMs(UbxFile ubx, UbxFile.UbxTrack t) {
        int base = t != null ? t.timeBaseMs : -1;
        if (base <= 0 && ubx != null) base = ubx.timeBaseMs;
        return base > 0 ? base : 50;
    }

    /**
     * 配乐文件解析（a/o.b(I) 原文）：path = ubx去扩展名/music名。
     * music 精确命中优先，搵唔到退回目录下第一首 *.mp3；无音乐返回 null（槽照走）。
     */
    static File resolveVoiceFile(File ubxFile, String base, String music) {
        if (ubxFile == null || music == null || music.isEmpty()) return null;
        File parent = ubxFile.getParentFile();
        if (parent == null) return null;
        File dir = base != null && !base.isEmpty() ? new File(parent, base) : parent;
        if (dir.isDirectory()) {
            File hit = new File(dir, music);
            if (hit.isFile()) return hit;
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".mp3")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** 开始播放（已在播则返回 false）。ubxFile 供配乐寻址（null 则无声）。 */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest, File ubxFile) {
        if ((playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive())) {
            lastError = "already playing " + currentName;
            return false;
        }
        final float sp = speed; // 本次播放快照；途中改速不影响正在播的
        List<Slot> seq = new ArrayList<>();
        List<VSong> vsongs = new ArrayList<>();
        String base = name != null && name.endsWith(".ubx") ? name.substring(0, name.length() - 4) : name;
        if (ubx != null) {
            long voiceElapsed = 0;
            for (UbxFile.UbxTrack t : ubx.tracks) {
                int tickMs = resolveTickMs(ubx, t);
                for (UbxFile.UbxServoFrame sf : t.frames) {
                    if (sf.voice) {
                        if (ubxFile == null) continue;
                        String music = sf.music != null ? sf.music : t.musicName;
                        File mf = resolveVoiceFile(ubxFile, base, music);
                        // 配乐自停上限不钳 30000（那是胸协议 move 的钳位；歌可长达数分钟，
                        // 钳了会提前腰斩——0.67x 下 69s bound 曾被误杀成 30s）。
                        long boundMs = scaleTime((long) sf.moveTimeHintMs, sp);
                        long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                        vsongs.add(new VSong(voiceElapsed, boundMs, mf,
                                mf != null ? mf.getName() : (music != null ? music : "")));
                        voiceElapsed += slotMs;
                        continue;
                    }
                    if (sf.groupBytes <= 0) continue; // d.f 关键帧表叶：只作位姿参考不发送
                    int moveMs = scaleTime(sf.moveTimeHintMs, sp);
                    long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                    seq.add(new Slot(sf, slotMs, moveMs));
                }
            }
        }
        if (seq.isEmpty() && vsongs.isEmpty()) {
            lastError = "no timed frames";
            return false;
        }
        if (!seq.isEmpty() && (chest == null || !chest.isAvailable())) {
            lastError = "chest not available";
            return false;
        }
        stopReq = false;
        voiceStopReq = false;
        framesSent = 0;
        framesTotal = seq.size();
        currentName = name != null ? name : "";
        lastError = "";
        voiceSong = "";
        final List<Slot> plan = seq;
        final List<VSong> vplan = vsongs;
        final DirectChestController c = chest;
        if (!plan.isEmpty()) {
            playThread = new Thread(new Runnable() {
                @Override public void run() { loop(plan, c); }
            }, "ubx-play");
            playThread.start();
        }
        if (!vplan.isEmpty()) {
            voiceThread = new Thread(new Runnable() {
                @Override public void run() { voiceLoop(vplan); }
            }, "ubx-voice");
            voiceThread.start();
        }
        long totalMs = 0;
        for (Slot s : plan) totalMs += s.slotMs;
        Log.i(TAG, "play start " + currentName + " frames=" + framesTotal
                + " vsongs=" + vplan.size() + " totalMs=" + totalMs + " speed=" + sp + "x");
        return true;
    }

    /** 变速缩放（仅舵机 move 用：钳 [20,30000] 保胸协议；配乐 bound/slot 用 long 版不钳）。 */
    private static int scaleTime(int ms, float sp) {
        long v = Math.round(ms / (double) sp);
        if (v < MIN_TIME_MS) v = MIN_TIME_MS;
        if (v > MAX_TIME_MS) v = MAX_TIME_MS;
        return (int) v;
    }

    private static long scaleTime(long ms, float sp) {
        long v = Math.round(ms / (double) sp);
        return v < 0 ? 0 : v;
    }

    /** 兼容旧三参（无声版，配乐禁用）。 */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest) {
        return play(ubx, name, chest, null);
    }

    private void loop(List<Slot> plan, DirectChestController chest) {
        long monoStart = System.nanoTime() / 1000000L;
        long elapsed = 0;
        try {
            for (Slot s : plan) {
                if (stopReq || Thread.currentThread().isInterrupted()) break;
                int[] angles = new int[20];
                for (int i = 0; i < 20; i++) angles[i] = s.sf.angles20[i] & 0xFF;
                try {
                    chest.playAllServos(angles, (short) s.moveMs);
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e.getMessage());
                }
                framesSent++;
                elapsed += s.slotMs; // 下一槽边界（单调，不随发送耗时漂移）
                long wait = monoStart + elapsed - System.nanoTime() / 1000000L;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } finally {
            Log.i(TAG, "play end " + currentName + " sent=" + framesSent + "/" + framesTotal);
        }
    }

    private void voiceLoop(List<VSong> plan) {
        final float sp = speed;
        long monoStart = System.nanoTime() / 1000000L;
        try {
            for (int i = 0; i < plan.size(); i++) {
                if (voiceStopReq || Thread.currentThread().isInterrupted()) break;
                VSong v = plan.get(i);
                long wait = monoStart + v.startMs - System.nanoTime() / 1000000L;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (voiceStopReq || Thread.currentThread().isInterrupted()) break;
                long nextStart = (i + 1 < plan.size()) ? plan.get(i + 1).startMs : Long.MAX_VALUE;
                long playUntil = Math.min(v.startMs + v.boundMs, nextStart); // 自停或被下首打断
                if (v.file == null || !v.file.isFile()) {
                    Log.w(TAG, "voice missing: " + v.name);
                } else if (sp == 1.0f) {
                    startVoiceLocked(v);
                } else {
                    VoiceStream vs = new VoiceStream();
                    curStream = vs;
                    setVoiceSong(v.name);
                    Log.i(TAG, "voice stream " + v.name + " " + sp + "x");
                    try {
                        vs.play(v.file, sp, monoStart + playUntil, new VoiceStream.StopFlag() {
                            @Override public boolean isStopped() {
                                return voiceStopReq || Thread.currentThread().isInterrupted();
                            }
                        });
                    } finally {
                        curStream = null;
                        setVoiceSong("");
                    }
                    continue; // 流播内已处理自停/打断，直接下首
                }
                long wait2 = monoStart + playUntil - System.nanoTime() / 1000000L;
                if (wait2 > 0) {
                    try {
                        Thread.sleep(wait2);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                stopVoiceLocked();
            }
        } finally {
            stopVoiceLocked();
            Log.i(TAG, "voice end " + currentName);
        }
    }

    /** 起一首（打断上一首；prepare 同步，无 looper 要求，与 o$a 一致）。 */
    private synchronized void startVoiceLocked(VSong v) {
        stopVoiceLocked();
        voiceSong = "";
        if (v.file == null || !v.file.isFile()) {
            Log.w(TAG, "voice missing: " + v.name);
            return;
        }
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(v.file.getAbsolutePath());
            final android.media.MediaPlayer self = mp;
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mpc) {
                    synchronized (UbxPlayer.this) {
                        try { self.release(); } catch (Exception ignore) {
                            // ignore
                        }
                        if (voicePlayer == self) {
                            voicePlayer = null;
                            voiceSong = "";
                        }
                    }
                }
            });
            mp.prepare();
            mp.start();
            voicePlayer = mp;
            voiceSong = v.name;
            Log.i(TAG, "voice start " + v.name);
        } catch (Exception e) {
            Log.w(TAG, "voice failed " + v.name + ": " + e.getMessage());
        }
    }

    private synchronized void stopVoiceLocked() {
        VoiceStream vs = curStream;
        curStream = null;
        if (vs != null) vs.abort();
        voiceSong = "";
        if (voicePlayer != null) {
            try {
                voicePlayer.stop();
            } catch (Exception ignored) {
                // 中途状态 race，照 release，吞掉。
            }
            try {
                voicePlayer.release();
            } catch (Exception ignored) {
                // ignore
            }
            voicePlayer = null;
        }
    }

    /** 停止播放（中断双线，停歌，舵机保持末位姿）。 */
    public synchronized void stop() {
        stopReq = true;
        voiceStopReq = true;
        // 先 abort 流（AudioTrack.write 阻塞时 interrupt 叫不醒，须 track.stop 松绑），再 join。
        if (playThread != null) playThread.interrupt();
        if (voiceThread != null) voiceThread.interrupt();
        stopVoiceLocked();
        if (playThread != null) {
            try {
                playThread.join(2000);
            } catch (InterruptedException ignore) {
                // ignore
            }
            playThread = null;
        }
        if (voiceThread != null) {
            try {
                voiceThread.join(2000);
            } catch (InterruptedException ignore) {
                // ignore
            }
            voiceThread = null;
        }
        stopVoiceLocked();
    }

    /** 只停配乐（本地音乐等抢声场景），舵机继续。 */
    public synchronized void stopVoice() {
        voiceStopReq = true;
        if (voiceThread != null) voiceThread.interrupt();
        stopVoiceLocked();
        if (voiceThread != null) {
            try {
                voiceThread.join(2000);
            } catch (InterruptedException ignore) {
                // ignore
            }
            voiceThread = null;
        }
        stopVoiceLocked();
    }

    public synchronized boolean isPlaying() {
        return (playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive());
    }

    public int sent() { return framesSent; }

    public int total() { return framesTotal; }

    public String name() { return currentName; }

    public String voice() { return voiceSong; }

    public String lastError() { return lastError; }

    /** /api/ubx/status 直接用的 JSON（调用方包一层 ok 即成）。 */
    public synchronized String statusJson() {
        boolean playing = (playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive());
        return "{\"ok\":true,\"playing\":" + playing
                + ",\"name\":\"" + esc(currentName) + "\""
                + ",\"sent\":" + framesSent + ",\"total\":" + framesTotal
                + ",\"voice\":\"" + esc(voiceSong) + "\""
                + ",\"speed\":" + speed
                + ",\"lastError\":\"" + esc(lastError) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
