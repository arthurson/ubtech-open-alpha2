package com.ubtechinc.alpha.hardware.ubx;

import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectChestController;
import com.ubtechinc.alpha.hardware.ServoPoseTracker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * .ubx 動作播放器（pure-direct，clean-room）。
 *
 * <p>播放語義按 1.1.7.3 smali 原文：
 * <pre>
 * f/i$a.run：每輪 sleep(i) 後 a/d.c(1) 前進 1 tick，其中 i = a/p.f() = static
 *   timeBase（type-0 塊第一個 int，單位 ms；a/p.b 只是循環 cap 上限，非周期）。
 * a/m.a(I)：tick 累加入 b；b &gt;= c 時切下一幀並即時發送。幀 i 槽位 = (b_i + c_i)
 *   tick（b()=start 為動作時長部，c()=end 為保持部）；發送 time 實參 = b_i * timeBase。
 * 發送包：胸 cmd 3 [20軸byte + short time]，angles[i] = LE32(e[i*8+8..+4]) &amp; 0xFF。
 * 例 tiny.ubx：槽 (20+10)+(20+40)+(20+10)=120 tick × 50ms = 6s。
 * 配樂（a/d type==4 → a/j/a/o，與舵機同一 unit clock 並行）：
 *   每 voice 幀槽位起：打斷上一首，起新 o$a 線程播 path = ubx去擴展名/music名；
 *   播至多 b*timeBase（自停），切幀/停止時打斷；pause 連歌同停。
 * </pre>
 * v1 簡化：
 * <ul>
 *   <li>只播 a.d 鏈定時幀（servo 看 groupBytes&gt;0；d.f 關鍵幀表不發送）。</li>
 *   <li>幀序按官方鏈式次序（smali f/b.f＋f/c.a 實證：入口 (-1,0) 葉起，
 *       leaf.c 對 d/a.e 選段，v7 gate＋c==-2 終止，段完按出索引跟鏈；
 *       見 UbxParser.computePlayOrder）。撳唔出序先回退解析序。
 *       鏈終止（c==-2）即全動作完，後續 track 唔播；多 track 首尾相接；
 *       a.h/a.j 視窗偏移忽略。</li>
 *   <li>自然播完停喺尾格：唔加送任何修正幀，播乜就係乜（stop() 中斷同樣
 *       保持末位姿）。</li>
 *   <li>單調 deadline 調度，累計漂移消除；stop() 中斷雙線，舵機保持末位姿。</li>
 *   <li>變速（0.5/0.67/1/1.5/2，黏性）：舵機槽位與 move 時長同除以倍率；
 *       配樂 1x 走 MediaPlayer（即時起播），非 1x 走 decode+線性重採樣“磁帶式”
 *       變速（不變調，0.5x 低沉、2x 尖細，機身 API 22 無 PlaybackParams）。
 *       播緊途中改速由調用方停舊重播即時生效（player 內快照隔離進行中計劃）。</li>
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
    /** 命令位姿（最后一次成功 cmd3 的 20 轴；开机未动过则未知）。 */
    private final ServoPoseTracker poseTracker = new ServoPoseTracker();

    /** 当前命令位姿拷贝；未知返回 null（调用方须如实报 unknown）。 */
    public int[] pose() { return poseTracker.snapshot(); }

    public boolean poseKnown() { return poseTracker.isKnown(); }

    /** 外部 cmd3 发送成功后同步位姿（如 servo/all 直发）。 */
    public void notePose(int[] angles20) { poseTracker.update(angles20); }

    /** 外部 cmd05 单发成功后同步嗰一軸位姿（如 servo/one 直发）。 */
    public void notePoseOne(int id, int angle) { poseTracker.updateOne(id, angle); }

    /** 逐軸版位姿（未知嗰軸 null；servoAngleAllResponse 命令位姿榜用）。 */
    public Integer[] poseBoxed() { return poseTracker.snapshotBoxed(); }

    /** 流播路徑用：標記當前歌名（供 status），與 startVoiceLocked 對等。 */
    private synchronized void setVoiceSong(String s) { voiceSong = s != null ? s : ""; }
    /** 變速倍率（黏性，作用於下次播放；播緊途中改只影響下一次）。 */
    private volatile float speed = 1.0f;

    private static final float[] SPEEDS = new float[]{0.5f, 0.67f, 1.0f, 1.5f, 2.0f};

    /** 設變速（僅 0.5/0.67/1/1.5/2 合法）；播緊時由調用方重播即時生效，返回是否接受。 */
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

    /** 一幀的播放項：沿用官方 leaf-order 幀列，槽位與發送時長按 a.m 原文分別計算。 */
    private static final class Slot {
        final UbxFile.UbxServoFrame sf;
        final long slotMs;  // (start+end) * T：本幀槽位
        final int moveMs;   // start * timeBase：cmd3 time 實參（解析器已預計算 moveTimeHintMs）
        Slot(UbxFile.UbxServoFrame sf, long slotMs, int moveMs) {
            this.sf = sf; this.slotMs = slotMs; this.moveMs = moveMs;
        }
    }

    /** voice 播放項：同 clock 下並行 schedule，起播/自停按 a/o 原文。 */
    private static final class VSong {
        final long startMs; // 本槽起點（共享 clock 下偏移）
        final long boundMs; // b*timeBase：mp3 自停上限
        final File file;    // 已解析出的本地 mp3（null 表缺文件，槽照走）
        final String name;
        VSong(long startMs, long boundMs, File file, String name) {
            this.startMs = startMs; this.boundMs = boundMs; this.file = file; this.name = name;
        }
    }

    /** T 回退：track.timeBaseMs &gt; 0 用它；否則用文件 timeBase；再否則 50。 */
    static int resolveTickMs(UbxFile ubx, UbxFile.UbxTrack t) {
        int base = t != null ? t.timeBaseMs : -1;
        if (base <= 0 && ubx != null) base = ubx.timeBaseMs;
        return base > 0 ? base : 50;
    }

    /**
     * 官方 leaf-order 幀列（smali f/b.f 實證，見 UbxParser.computePlayOrder）。
     * playOrder==null（無葉列）先回退解析序，行為同舊版一致；
     * 空表＝官方指明唔播，跟播零格。
     */
    static List<UbxFile.UbxServoFrame> orderedFrames(UbxFile.UbxTrack t) {
        if (t == null) return new ArrayList<UbxFile.UbxServoFrame>();
        if (t.playOrder == null) return t.frames;
        List<UbxFile.UbxServoFrame> out = new ArrayList<>();
        for (int idx : t.playOrder) {
            if (idx < 0 || idx >= t.daSpans.size()) continue;
            int[] span = t.daSpans.get(idx);
            if (span == null || span.length < 2) continue;
            int s = Math.max(0, span[0]);
            int e = Math.min(t.frames.size(), span[1]);
            for (int i = s; i < e; i++) out.add(t.frames.get(i));
        }
        return out;
    }

    /**
     * 配樂文件解析（a/o.b(I) 原文）：path = ubx去擴展名/music名。
     * music 精確命中優先，搵唔到退回目錄下第一首 *.mp3；無音樂返回 null（槽照走）。
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

    /** 開始播放（已在播則返回 false）。ubxFile 供配樂尋址（null 則無聲）。 */
    public synchronized boolean play(UbxFile ubx, String name, DirectChestController chest, File ubxFile) {
        if ((playThread != null && playThread.isAlive())
                || (voiceThread != null && voiceThread.isAlive())) {
            lastError = "already playing " + currentName;
            return false;
        }
        final float sp = speed; // 本次播放快照；途中改速不影響正在播的
        List<Slot> seq = new ArrayList<>();
        List<VSong> vsongs = new ArrayList<>();
        String base = name != null && name.endsWith(".ubx") ? name.substring(0, name.length() - 4) : name;
        if (ubx != null) {
            long voiceElapsed = 0;
            for (UbxFile.UbxTrack t : ubx.tracks) {
                int tickMs = resolveTickMs(ubx, t);
                for (UbxFile.UbxServoFrame sf : orderedFrames(t)) {
                    if (sf.voice) {
                        if (ubxFile == null) continue;
                        String music = sf.music != null ? sf.music : t.musicName;
                        File mf = resolveVoiceFile(ubxFile, base, music);
                        // 配樂自停上限不鉗 30000（那是胸協議 move 的鉗位；歌可長達數分鐘，
                        // 鉗了會提前腰斬——0.67x 下 69s bound 曾被誤殺成 30s）。
                        long boundMs = scaleTime((long) sf.moveTimeHintMs, sp);
                        long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                        vsongs.add(new VSong(voiceElapsed, boundMs, mf,
                                mf != null ? mf.getName() : (music != null ? music : "")));
                        voiceElapsed += slotMs;
                        continue;
                    }
                    if (sf.groupBytes <= 0) continue; // d.f 關鍵幀表葉：只作位姿參考不發送
                    int moveMs = scaleTime(sf.moveTimeHintMs, sp);
                    long slotMs = scaleTime((long) (sf.start + sf.end) * (long) tickMs, sp);
                    seq.add(new Slot(sf, slotMs, moveMs));
                }
                // 鏈終止（c==-2，smali f/c.a completed 分支）：全動作完，
                // 後續 track 唔播——前進/後退收步段就係咁被 skip（真機片實證）。
                if (t.chainTerminated) {
                    Log.i(TAG, "play " + name + " chain terminated after track "
                            + t.id + " (skipping later tracks)");
                    break;
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
            final float vsp = sp; // 配樂同舵機用同一個快照：播音起線前改速唔好聲畫分裂
            voiceThread = new Thread(new Runnable() {
                @Override public void run() { voiceLoop(vplan, vsp); }
            }, "ubx-voice");
            voiceThread.start();
        }
        long totalMs = 0;
        for (Slot s : plan) totalMs += s.slotMs;
        Log.i(TAG, "play start " + currentName + " frames=" + framesTotal
                + " vsongs=" + vplan.size() + " totalMs=" + totalMs + " speed=" + sp + "x");
        return true;
    }

    /** 變速縮放：long 核心（配樂 bound/slot 用，不鉗）；int 版加鉗 [20,30000] 保胸協議。 */
    private static long scaleTime(long ms, float sp) {
        long v = Math.round(ms / (double) sp);
        return v < 0 ? 0 : v;
    }

    private static int scaleTime(int ms, float sp) {
        long v = scaleTime((long) ms, sp);
        if (v < MIN_TIME_MS) v = MIN_TIME_MS;
        if (v > MAX_TIME_MS) v = MAX_TIME_MS;
        return (int) v;
    }

    /** 兼容舊三參（無聲版，配樂禁用）。 */
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
                    if (chest.playAllServos(angles, (short) s.moveMs)) {
                        poseTracker.update(angles); // 命令位姿追踪（成功发送才记）
                    }
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e.getMessage());
                }
                framesSent++;
                elapsed += s.slotMs; // 下一槽邊界（單調，不隨發送耗時漂移）
                long wait = monoStart + elapsed - System.nanoTime() / 1000000L;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            // 自然播完唔加送任何額外位姿：停喺 .ubx 尾格，播乜就係乜
            //（曾試過補送 HOME 收尾，真機實證反而整出十字手，故成段唔要；
            // 要回企直就手動再播一次蹲下站起 / SERVO 全組回中）。
        } finally {
            Log.i(TAG, "play end " + currentName + " sent=" + framesSent + "/" + framesTotal);
        }
    }

    private void voiceLoop(List<VSong> plan, float sp) {
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
                long playUntil = Math.min(v.startMs + v.boundMs, nextStart); // 自停或被下首打斷
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
                    continue; // 流播內已處理自停/打斷，直接下首
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

    /** 起一首（打斷上一首；prepare 同步，無 looper 要求，與 o$a 一致）。 */
    private synchronized void startVoiceLocked(VSong v) {
        stopVoiceLocked();
        voiceSong = "";
        if (v.file == null || !v.file.isFile()) {
            Log.w(TAG, "voice missing: " + v.name);
            return;
        }
        android.media.MediaPlayer mp = null;
        try {
            mp = new android.media.MediaPlayer();
            mp.setDataSource(v.file.getAbsolutePath());
            final android.media.MediaPlayer self = mp;
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mpc) {
                    synchronized (UbxPlayer.this) {
                        // 只 release 當前仍在用嗰個；舊歌已被 stopVoiceLocked
                        // release 過就唔再掂（防雙 release native crash），
                        // 已被新歌取代就唔好誤殺新 player。
                        if (voicePlayer == self) {
                            try { self.release(); } catch (Exception ignore) {
                                // ignore
                            }
                            voicePlayer = null;
                            voiceSong = "";
                        }
                    }
                }
            });
            mp.prepare();
            mp.start();
            voicePlayer = mp;
            mp = null; // 已交接，catch 唔再 release
            voiceSong = v.name;
            Log.i(TAG, "voice start " + v.name);
        } catch (Exception e) {
            Log.w(TAG, "voice failed " + v.name + ": " + e.getMessage());
            if (mp != null) {
                try { mp.release(); } catch (Exception ignore) {
                    // prepare/start 炸咗唔 release 會漏 native mediaplayer fd
                }
            }
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
                // 中途狀態 race，照 release，吞掉。
            }
            try {
                voicePlayer.release();
            } catch (Exception ignored) {
                // ignore
            }
            voicePlayer = null;
        }
    }

    /** join 唔可以喺鎖內做：voiceLoop 尾 finally 同 OnCompletion 都要同一把鎖，
     * 揸住鎖 join 會同收尾線程互等（帶聲 stop 卡足 2s）。先快照、解鎖 join、再返嚟清。 */
    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException ignore) {
            // ignore
        }
    }

    /** 停止播放（中斷雙線，停歌，舵機保持末位姿）。 */
    public void stop() {
        final Thread pt;
        final Thread vt;
        synchronized (this) {
            stopReq = true;
            voiceStopReq = true;
            // 先 abort 流（AudioTrack.write 阻塞時 interrupt 叫不醒，須 track.stop 鬆綁），再 join。
            if (playThread != null) playThread.interrupt();
            if (voiceThread != null) voiceThread.interrupt();
            stopVoiceLocked();
            pt = playThread;
            vt = voiceThread;
        }
        joinQuietly(pt);
        joinQuietly(vt);
        synchronized (this) {
            if (playThread == pt) playThread = null;
            if (voiceThread == vt) voiceThread = null;
            stopVoiceLocked();
        }
    }

    /** 只停配樂（本地音樂等搶聲場景），舵機繼續。join 同樣唔可以喺鎖內（見 stop）。 */
    public void stopVoice() {
        final Thread vt;
        synchronized (this) {
            voiceStopReq = true;
            if (voiceThread != null) voiceThread.interrupt();
            stopVoiceLocked();
            vt = voiceThread;
        }
        joinQuietly(vt);
        synchronized (this) {
            if (voiceThread == vt) voiceThread = null;
            stopVoiceLocked();
        }
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

    /** /api/ubx/status 直接用的 JSON（調用方包一層 ok 即成）。 */
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
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
