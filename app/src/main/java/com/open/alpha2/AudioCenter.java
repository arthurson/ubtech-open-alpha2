package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;

/**
 * 本地音樂 + 網絡電台播放中心。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第六刀)：本地音樂檔尋址/
 * 播放/暫停/進度/音量/頻譜/EQ/隨機動作循環、電台搜尋/播放/狀態、上載，
 * 邏輯一字不改搬過嚟。注意兩個刻意保留的耦合：
 * - 和 UbxPlayer 共用同一個實例 (動作配樂 stopVoice)，由 MainActivity 傳入；
 * - 隨機動作 id 經 Supplier 攞 (x random 短/長池喺 MainActivity 嗰邊，
 *   MCP fuzzy + 語意路徑仲用緊同一份，唔拆散)。
 * 所有 synchronized 鎖由 MainActivity.this 轉做自己 (調用方全部經同一個
 * instance，互斥等價)；排程用傳入嘅 mainHandler (main looper)。
 * 2026-09 dispatcher Phase 1 第四刀加：audio/volume/get、audio/volume/set
 * (systemVolumeGet/Set — STREAM_MUSIC 系統音量，唔係 localMusicVolume
 * 嗰個 per-player 音量)。
 */
public final class AudioCenter {
    private static final String TAG = "AudioCenter";

    private static final String PREF_MUSIC_FILLER_ACTION_ENABLED = "music_filler_action_enabled";
    private static final String PREF_MUSIC_EQ_PRESET = "music_eq_preset";

    private final Context appContext;
    private final UbxPlayer ubxPlayer;
    private final ActionDirect actionDirect;
    private final Handler mainHandler;

    public AudioCenter(Context context, UbxPlayer ubxPlayer, ActionDirect actionDirect,
            Handler mainHandler) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
        this.actionDirect = actionDirect;
        this.mainHandler = mainHandler;
    }

    private android.content.SharedPreferences prefs() {
        return appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // 2026-08 新增: 本地音樂播放 (自訂放在 /mnt/internal_sd/music/ 的音樂檔, 不是
    // RingtoneManager 那些系統鈴聲) - 沿用 currentRingtonePlayer 完全相同的 pattern
    // (獨立一個 field, 不共用 currentRingtonePlayer, 因為兩者應該可以互不影響地
    // 各自停止/播放, 例如播放音樂期間都可以獨立播放一個系統提示音), 同樣用
    // STREAM_MUSIC + prepareAsync() + 播完自動 release()。
    private static final java.io.File LOCAL_MUSIC_DIR = new java.io.File("/mnt/internal_sd/music");
    private static final java.util.Set<String> LOCAL_MUSIC_EXTENSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("mp3", "wav", "ogg", "m4a", "flac"));

    private android.media.MediaPlayer currentMusicPlayer;

    /** 目前正在播放 (或正在 prepare) 的本地音樂檔名 (含副檔名), null = 沒有 -
     *  純粹提供給 audio/local_music/status 這個新 endpoint 顯示用, 不影響播放邏輯
     *  本身。和 currentRadioStationName 一樣的想法 - 播放狀態本身只要看
     *  currentMusicPlayer 就夠了, 這個 field 只是為了讓 UI 不用自己另外記住選了
     *  哪個檔名。*/
    private volatile String currentMusicTrackName;

    /** 播放中的本地音樂用的 equalizer, 綁定 currentMusicPlayer 的 audio session -
     *  跟隨 currentMusicPlayer 的生命週期, 換歌/停歌時都要即時 release() 這個
     *  (見 stopLocalMusicPlaybackLocked()), 不可以留著跨 session 使用, 因為
     *  Equalizer 綁定的 audio session id 一旦 MediaPlayer release() 之後就不再
     *  對應任何東西, 之後的 setEnabled()/usePreset() call 會拋出
     *  IllegalStateException。 */
    private android.media.audiofx.Equalizer musicEqualizer;

    /** 用戶上次選擇的 equalizer preset index (由 SharedPreferences 讀出來, 開機/換歌
     *  時都沿用這個) - -1 = 沒選過/用「無」(flat, 不做任何調整)。*/
    private int musicEqPresetIndex = -1;

    // -- Audio Spectrum (2026-08 v2 新增) --------------------------------------
    // 用 android.media.audiofx.Visualizer 綁定 currentMusicPlayer 的 audio session
    // (和 musicEqualizer 同一條 session), 開啟 FFT 擷取, 將取得的頻譜壓縮成
    // MUSIC_SPECTRUM_BANDS 條 band, 提供給 audio/local_music/spectrum endpoint 輪詢,
    // 前端 canvas 畫 bar。生命週期完全跟隨 MediaPlayer: playLocalMusicFile() prepare
    // 時建立, stop/completion/error 時 release。
    private static final int MUSIC_SPECTRUM_BANDS = 24;
    private android.media.audiofx.Visualizer musicVisualizer;
    /** 最近一次 FFT 算出來的頻譜 (0-255 x MUSIC_SPECTRUM_BANDS 條)。volatile 就夠 -
     *  每個 element 獨立讀寫, 前端拿到稍微過時的一幀完全無所謂。 */
    private final int[] musicSpectrumBands = new int[MUSIC_SPECTRUM_BANDS];
    /** FFT bin -> band 的對照表, 第一次收到 FFT 數據時才建立 (需要知道 samplingRate)。 */
    private int[] musicSpectrumBinMap = null;

    /** FFT raw bytes (re0,im0,re1,im1,... 交錯排列) -> MUSIC_SPECTRUM_BANDS 條
     *  magnitude, 用 log 頻率分佈 (低頻窄高頻闊, 貼近聽感) + 輕微增益補償高頻
     *  (音樂能量天生集中在低頻, 不補償的話只有前幾條會動)。*/
    private void updateMusicSpectrumFromFft(byte[] fft, int samplingRate) {
        if (fft == null || fft.length < 4) return;
        if (musicSpectrumBinMap == null) {
            buildMusicSpectrumBinMap(samplingRate, fft.length / 2);
            if (musicSpectrumBinMap == null) return;
        }
        int bins = fft.length / 2;
        for (int b = 0; b < MUSIC_SPECTRUM_BANDS; b++) {
            int from = musicSpectrumBinMap[b];
            int to = musicSpectrumBinMap[b + 1];
            if (to <= from) { to = from + 1; }
            double peak = 0;
            for (int i = from; i < to && i < bins; i++) {
                double re = fft[2 * i];
                double im = fft[2 * i + 1];
                double mag = Math.sqrt(re * re + im * im);
                if (mag > peak) peak = mag;
            }
            // 高頻補償: 第 b 條 band 乘 (1 + b/BANDS*1.5); clamp 0-255。
            double scaled = peak * (1.0 + 1.5 * b / MUSIC_SPECTRUM_BANDS) * 0.6;
            int v = (int) Math.min(255, scaled);
            musicSpectrumBands[b] = v;
        }
    }

    /** 用 log 刻度起「band index -> FFT bin 範圍」對照表, 範圍大約 40Hz - 12kHz。 */
    private void buildMusicSpectrumBinMap(int samplingRate, int binCount) {
        if (samplingRate <= 0 || binCount <= 0) return;
        double minFreq = 40.0;
        double maxFreq = Math.min(12000.0, samplingRate / 2.0);
        musicSpectrumBinMap = new int[MUSIC_SPECTRUM_BANDS + 1];
        for (int b = 0; b <= MUSIC_SPECTRUM_BANDS; b++) {
            double frac = Math.pow((double) b / MUSIC_SPECTRUM_BANDS, 2.0); // 近似 log 分佈
            double freq = minFreq * Math.pow(maxFreq / minFreq, frac);
            int bin = (int) Math.round(freq / samplingRate * binCount * 2.0);
            musicSpectrumBinMap[b] = Math.max(0, Math.min(binCount - 1, bin));
        }
        // 保證單調遞增, 避免某些 band 沒有 bin 可用。
        for (int b = 1; b <= MUSIC_SPECTRUM_BANDS; b++) {
            if (musicSpectrumBinMap[b] <= musicSpectrumBinMap[b - 1]) {
                musicSpectrumBinMap[b] = musicSpectrumBinMap[b - 1] + 1;
            }
        }
    }

    private void setupMusicVisualizerLocked(android.media.MediaPlayer mp) {
        releaseMusicVisualizerLocked();
        try {
            android.media.audiofx.Visualizer v =
                    new android.media.audiofx.Visualizer(mp.getAudioSessionId());
            int[] range = android.media.audiofx.Visualizer.getCaptureSizeRange();
            v.setCaptureSize(range != null ? range[1] : 1024);
            v.setDataCaptureListener(
                    new android.media.audiofx.Visualizer.OnDataCaptureListener() {
                        @Override
                        public void onWaveFormDataCapture(
                                android.media.audiofx.Visualizer visualizer,
                                byte[] waveform, int samplingRate) {
                            // 不需要 waveform, 只要 FFT。
                        }

                        @Override
                        public void onFftDataCapture(
                                android.media.audiofx.Visualizer visualizer,
                                byte[] fft, int samplingRate) {
                            updateMusicSpectrumFromFft(fft, samplingRate);
                        }
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                    false /* waveform */, true /* fft */);
            v.setEnabled(true);
            musicVisualizer = v;
        } catch (Throwable t) {
            // Visualizer 這個 effect 一樣不保證每台機器都有 - 沒有就沒有 spectrum 顯示,
            // 不要因此拖累整首歌播不了。
            Log.w(TAG, "Visualizer unavailable on this device", t);
            musicVisualizer = null;
        }
    }

    private void releaseMusicVisualizerLocked() {
        if (musicVisualizer != null) {
            try {
                musicVisualizer.setEnabled(false);
                musicVisualizer.release();
            } catch (Exception ignored) {
            }
            musicVisualizer = null;
        }
        java.util.Arrays.fill(musicSpectrumBands, 0);
    }

    /** Lists every playable audio file directly inside LOCAL_MUSIC_DIR (non-recursive -
     *  keeps this predictable for a small hand-managed folder rather than silently
     *  picking up files buried in sub-folders). Filters by extension only (see
     *  LOCAL_MUSIC_EXTENSIONS) since there's no MediaStore index guaranteed for a
     *  manually-copied folder on API 22. Returns an empty list (never null) if the
     *  folder doesn't exist or isn't readable - callers must treat that as a normal
     *  "no music" case, not a bug. Sorted by filename for a stable, predictable order
     *  across calls (directory listing order is otherwise filesystem-dependent). */
    public java.util.List<java.io.File> listLocalMusicFiles() {
        java.util.List<java.io.File> result = new java.util.ArrayList<>();
        java.io.File[] files = LOCAL_MUSIC_DIR.listFiles();
        if (files == null) return result;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) continue;
            String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
            if (LOCAL_MUSIC_EXTENSIONS.contains(ext)) result.add(f);
        }
        java.util.Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    /** Resolves a human-supplied song name/query to an actual file in LOCAL_MUSIC_DIR -
     *  mirrors resolveActionId()'s three-tier match (exact filename incl. extension,
     *  then exact match against the filename without extension, then substring either
     *  direction) so the XiaoZhi LLM can just say a song's (approximate) name instead
     *  of needing to know the exact on-disk filename/extension. Returns null if nothing
     *  matches closely enough, same "don't guess" philosophy as resolveActionId(). */
    public java.io.File resolveLocalMusicFile(String query) {
        java.util.List<java.io.File> files = listLocalMusicFiles();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || files.isEmpty()) return null;

        for (java.io.File f : files) {
            if (q.equals(f.getName())) return f;
        }
        String qLower = q.toLowerCase(java.util.Locale.US);
        for (java.io.File f : files) {
            String base = stripExtension(f.getName());
            if (qLower.equals(base.toLowerCase(java.util.Locale.US))) return f;
        }
        for (java.io.File f : files) {
            String baseLower = stripExtension(f.getName()).toLowerCase(java.util.Locale.US);
            if (baseLower.contains(qLower) || qLower.contains(baseLower)) return f;
        }
        return null;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    /** 2026-08 更新 (用戶要求「本地播歌時, random 動作應該要不停動, 直到整首歌播完」):
     *  之前只有在 onPrepared (真正開始播放的那一刻) 動一次就算, 現在改成用這個固定
     *  間隔不斷重複觸發 triggerRandomFillerAction(), 直到整首歌播完/被叫停為止。
     *  用固定間隔 (而不是「等動作做完再動下一個」) 的原因是: AIDL 沒有提供任何
     *  查詢「一個 action 什麼時候做完」的方法 (見 AIDL_REFERENCE.md, action_PlayActionName
     *  只是 fire-and-forget), 沒辦法準確知道上一個動作多久才做完, 所以選一個
     *  保守的固定 cadence, 對絕大部分動作長度來說都足夠做完那個動作再開始
     *  下一個, 不會不斷打斷上一個尚未做完的動作。 */
    private static final long MUSIC_FILLER_ACTION_INTERVAL_MS = 3500;

    /** 目前正在執行的「播歌隨機動作」循環 Runnable, null = 沒有在執行 - 用來讓
     *  stopLocalMusicPlaybackLocked() 用 mainHandler.removeCallbacks() 準確停止
     *  這個循環, 不用靠猜。 */
    private Runnable musicFillerActionLoop;

    /** 啟動「播歌期間不斷動隨機動作」的循環 - 每 MUSIC_FILLER_ACTION_INTERVAL_MS
     *  觸發一次 triggerRandomFillerAction(), 再重新 schedule 自己, 直到
     *  boundPlayer 不再是 currentMusicPlayer (也就是整首歌已經播完/被叫停/被第二首歌
     *  取代了) 才停止。用 mainHandler (Looper.getMainLooper()) 排程——這個 method
     *  本身只是 postDelayed, 沒有做 blocking call, 不用擔心阻塞 main thread;
     *  真正的動作播放 (在 triggerRandomFillerAction() 裡面) 一直都是開獨立 thread 做。 */
    /** 播歌隨機動作開關 - 讀取 SharedPreferences, 預設 true (保持之前還沒有開關按鈕之前
     *  的行為: 一直都會動)。讓 audio/local_music/filler_action/get、
     *  startMusicFillerActionLoop()、playLocalMusicFile() 一起用同一個讀法,
     *  用戶隨時可以在 UI 上切換, 不用讓正在播放的歌也要重新播放才生效 - 下一個
     *  loop tick (或下一次播歌) 就會反映新設定。*/
    private boolean isMusicFillerActionEnabled() {
        return prefs().getBoolean(PREF_MUSIC_FILLER_ACTION_ENABLED, true);
    }

    private void startMusicFillerActionLoop(final android.media.MediaPlayer boundPlayer) {
        Runnable loop = new Runnable() {
            @Override
            public void run() {
                synchronized (AudioCenter.this) {
                    if (currentMusicPlayer != boundPlayer) {
                        // 整首歌已經播完/被叫停/被第二首歌取代了 - 這個循環
                        // 對應的播放已經不再有效, 不再重新 schedule, 自然結束。
                        return;
                    }
                }
                // 2026-08 新增: 開關 - 用戶隨時可以在音樂 tab 切換「random 動作」
                // 這個開關, 每次 tick 都即時讀取最新值, 不用等下一次播歌才生效。
                // 關閉時只是跳過「動一下」這個動作, loop 本身仍然繼續 schedule
                // 下去 (讓用戶隨時開啟都能立即恢復, 不用 stop/replay 那首歌)。
                if (isMusicFillerActionEnabled()) {
                    triggerRandomFillerAction();
                }
                synchronized (AudioCenter.this) {
                    if (currentMusicPlayer == boundPlayer && musicFillerActionLoop != null) {
                        mainHandler.postDelayed(musicFillerActionLoop, MUSIC_FILLER_ACTION_INTERVAL_MS);
                    }
                }
            }
        };
        musicFillerActionLoop = loop;
        mainHandler.postDelayed(loop, MUSIC_FILLER_ACTION_INTERVAL_MS);
    }

    /** 停止 startMusicFillerActionLoop() 開始的循環 (如果有的話) - 供
     *  stopLocalMusicPlaybackLocked() 呼叫, 也供 onCompletion/onError 這兩個
     *  listener 呼叫 (整首歌自然播完/播壞都應該立即停止動作, 不用等到下一次
     *  loop tick 才發現 currentMusicPlayer 已經不對才罷手)。 */
    private void stopMusicFillerActionLoop() {
        if (musicFillerActionLoop != null) {
            mainHandler.removeCallbacks(musicFillerActionLoop);
            musicFillerActionLoop = null;
        }
        stopSharedFillerLoop();
    }

    // 共用隨機動作循環 — 本地與電台共用同一開關與同一節奏，兩者任一在播即觸發
    private Runnable sharedFillerLoop;
    private synchronized void startSharedFillerLoop() {
        if (sharedFillerLoop != null) return;
        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                boolean hasActivePlayer = false;
                synchronized (AudioCenter.this) {
                    if (currentMusicPlayer != null || currentRadioPlayer != null) hasActivePlayer = true;
                }
                if (hasActivePlayer && isMusicFillerActionEnabled()) {
                    triggerRandomFillerAction();
                }
                synchronized (AudioCenter.this) {
                    // 用 this 而非 loop 變數，避免「variable loop might not have been initialized」編譯錯誤
                    if (sharedFillerLoop == this && hasActivePlayer) {
                        mainHandler.postDelayed(this, MUSIC_FILLER_ACTION_INTERVAL_MS);
                    } else {
                        sharedFillerLoop = null;
                    }
                }
            }
        };
        sharedFillerLoop = loop;
        mainHandler.postDelayed(loop, MUSIC_FILLER_ACTION_INTERVAL_MS);
    }
    private synchronized void stopSharedFillerLoop() {
        if (sharedFillerLoop != null) {
            mainHandler.removeCallbacks(sharedFillerLoop);
            sharedFillerLoop = null;
        }
    }
    private synchronized void stopSharedFillerLoopIfIdle() {
        if (currentMusicPlayer == null && currentRadioPlayer == null) {
            stopSharedFillerLoop();
        }
    }

    /** Plays a local music file - same STREAM_MUSIC/prepareAsync()/auto-release shape as
     *  playRingtoneUri(), kept as a separate method (rather than generalising both into
     *  one) so a future change to one playback path can't accidentally affect the
     *  other. Stops whatever local music track was previously playing first.
     *
     *  2026-08 更新: 在真正開始播放的那一刻 (onPreparedListener 裡面, 而不是
     *  prepareAsync() 的 request 一發出就做) 順便啟動
     *  startMusicFillerActionLoop() - 用戶要求「播歌時要不停動, 直到整首歌播
     *  完」, 見那個 method 的 javadoc。刻意放在 onPrepared 裡面 (真正 start()
     *  之後) 而不是這個 method 一開頭就做: 如果檔案根本播不了 (loss/corrupt,
     *  prepareAsync 觸發 onError), 不應該仍然先動了那個動作, 「動作」應該與
     *  「真的有歌聲」同步, 而不是與「這個 method 被呼叫了」同步。 */
    public synchronized void playLocalMusicFile(java.io.File file) {
        stopLocalMusicPlaybackLocked();
        // 共用播放器：播本地時停掉電台，避免兩路同時出聲；動作配樂亦停，免疊聲
        stopRadioPlaybackLocked();
        ubxPlayer.stopVoice();
        if (file == null || !file.exists()) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                mp.start();
                setupMusicEqualizerLocked(mp);
                setupMusicVisualizerLocked(mp);
                startMusicFillerActionLoop(mp);
                startSharedFillerLoop();
            });
            player.setOnCompletionListener(mp -> {
                synchronized (AudioCenter.this) {
                    stopMusicFillerActionLoop();
                    stopSharedFillerLoopIfIdle();
                    // 若電台仍在播，保留共用 EQ/頻譜給電台
                    if (currentRadioPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                        currentMusicTrackName = null;
                    }
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (AudioCenter.this) {
                    stopMusicFillerActionLoop();
                    stopSharedFillerLoopIfIdle();
                    if (currentRadioPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    mp.release();
                    if (currentMusicPlayer == mp) {
                        currentMusicPlayer = null;
                        currentMusicTrackName = null;
                    }
                }
                return true;
            });
            currentMusicPlayer = player;
            currentMusicTrackName = file.getName();
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to play local music file " + file, e);
        }
    }

    private void setupMusicEqualizerLocked(android.media.MediaPlayer mp) {
        try {
            android.media.audiofx.Equalizer eq = new android.media.audiofx.Equalizer(0, mp.getAudioSessionId());
            eq.setEnabled(true);
            musicEqualizer = eq;
            int savedPreset = prefs().getInt(PREF_MUSIC_EQ_PRESET, -1);
            if (savedPreset >= 0 && savedPreset < eq.getNumberOfPresets()) {
                try {
                    eq.usePreset((short) savedPreset);
                    musicEqPresetIndex = savedPreset;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply saved EQ preset " + savedPreset, e);
                }
            }
        } catch (Exception e) {
            // Equalizer 這個 audio effect 不保證每台機器都有 (視乎廠商有沒有實作對應
            // 的 effect engine) - 建不起來就當作沒有這個功能, 不應該因此拖累整首歌播不了。
            Log.w(TAG, "Equalizer unavailable on this device", e);
            musicEqualizer = null;
        }
    }

    private void releaseMusicEqualizerLocked() {
        if (musicEqualizer != null) {
            try {
                musicEqualizer.release();
            } catch (Exception ignored) {
            }
            musicEqualizer = null;
        }
    }

    public synchronized void stopLocalMusicPlayback() {
        stopLocalMusicPlaybackLocked();
    }

    private void stopLocalMusicPlaybackLocked() {
        stopMusicFillerActionLoop();
        stopSharedFillerLoopIfIdle();
        // 共用 EQ/頻譜：若電台仍在播，保留給電台
        if (currentRadioPlayer == null) {
            releaseMusicEqualizerLocked();
            releaseMusicVisualizerLocked();
        }
        if (currentMusicPlayer != null) {
            try {
                currentMusicPlayer.stop();
            } catch (Exception e) {
                // 見 stopRingtonePlaybackLocked() 的 comment - prepareAsync() 中途
                // race 可能引發 IllegalStateException, release() 一樣照做, 吞掉就好。
            }
            try {
                currentMusicPlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentMusicPlayer = null;
            currentMusicTrackName = null;
        }
    }

    // 2026-08 新增: FM/網路電台播放 (經由 Radio Browser API, radio-browser.info,
    // 動態搜尋全世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 的
    // javadoc) - 獨立一個 field/一套 method, 不和 currentMusicPlayer (本地檔案)
    // 共用, 理由和 currentMusicPlayer 不和 currentRingtonePlayer 共用一樣: 三種播放
    // 應該可以互不影響地各自播放/停止 (例如轉台時不應該連帶讓本地音樂也要停)。和
    // 本地音樂/鈴聲最大的差別: 這裡的 data source 是網路 URL, prepareAsync() 依賴
    // 網路連線, 比本地檔案更容易因為網路問題觸發 onError - 這是
    // playRadioStream() 特意保留 onErrorListener 做事 (清除 currentRadioPlayer)
    // 的原因, 讓下一次「轉台」不會撞到一個已經失效但沒清掉的 reference。
    private android.media.MediaPlayer currentRadioPlayer;

    /** 目前正在播放的電台 Radio Browser stationuuid, null = 沒有播放 - 純粹提供給
     *  audio/radio/status 這個 HTTP endpoint 顯示用, 不影響播放邏輯本身。 */
    private volatile String currentRadioStationId;

    /** 目前正在播放的電台名 (Radio Browser 的 "name") - 和 currentRadioStationId 一起
     *  存, 純粹提供給 audio/radio/status 直接顯示用, 不用為了取得名稱再打一次 API。 */
    private volatile String currentRadioStationName;

    /** 播放一個電台的直播串流 - 和 playLocalMusicFile()/playRingtoneUri() 一樣的
     *  STREAM_MUSIC/prepareAsync()/auto-release 形狀, 但這裡 setDataSource() 收的
     *  是網路 URL (Radio Browser struct 的 "url_resolved" - 官方文件建議使用這個
     *  而不是 "url": url_resolved 已經解析過 playlist/HTTP redirect, 不需要這台機器自己
     *  再會解析 .pls/.m3u, 對一個沒有 yt-dlp 這類工具的 Android 5.1 App 來說很關鍵),
     *  所以 prepareAsync() 要靠網路連線才能讓串流真正開始 buffer - 這個 method
     *  只負責觸發, 不 block caller 等網路, 由 onPreparedListener 在真正取得資料、
     *  可以開始播放的那一刻才 start()。播歌時順便動一下的 triggerRandomFillerAction()
     *  (見 playLocalMusicFile() javadoc) 這裡沒有加 - 電台可以連續播好幾個小時, 不像
     *  一首歌那麼短, 不應該只因為「剛轉了台」就動一次, 和「播放時要看起來
     *  生動」這個原意不搭。 */
    public synchronized void playRadioStream(org.json.JSONObject station) {
        stopRadioPlaybackLocked();
        // 共用播放器：播電台時停掉本地音樂，避免兩路同時出聲；動作配樂亦停
        stopLocalMusicPlaybackLocked();
        ubxPlayer.stopVoice();
        if (station == null) {
            return;
        }
        String url = station.optString("url_resolved", "");
        if (url.isEmpty()) {
            url = station.optString("url", "");
        }
        if (url.isEmpty()) {
            return;
        }
        final String resolvedUrl = url;
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            NetLog.out("radio-play", resolvedUrl);
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                mp.start();
                // 共用 EQ/頻譜/隨機動作 — 與本地音樂同一套
                setupMusicEqualizerLocked(mp);
                setupMusicVisualizerLocked(mp);
                startSharedFillerLoop();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (AudioCenter.this) {
                    mp.release();
                    if (currentRadioPlayer == mp) {
                        currentRadioPlayer = null;
                        currentRadioStationId = null;
                        currentRadioStationName = null;
                    }
                    // 電台出錯時若本地也沒在播，才釋放共用資源
                    if (currentMusicPlayer == null) {
                        releaseMusicEqualizerLocked();
                        releaseMusicVisualizerLocked();
                    }
                    stopSharedFillerLoopIfIdle();
                }
                Log.w(TAG, "Radio stream playback error: what=" + what + " extra=" + extra
                        + " url=" + resolvedUrl);
                return true;
            });
            currentRadioPlayer = player;
            currentRadioStationId = station.optString("stationuuid");
            currentRadioStationName = station.optString("name");
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Failed to play radio stream " + url, e);
        }
    }

    public synchronized void stopRadioPlayback() {
        stopRadioPlaybackLocked();
    }

    private void stopRadioPlaybackLocked() {
        if (currentRadioPlayer != null) {
            try {
                currentRadioPlayer.stop();
            } catch (Exception e) {
            }
            try {
                currentRadioPlayer.release();
            } catch (Exception e) {
            }
            currentRadioPlayer = null;
        }
        currentRadioStationId = null;
        currentRadioStationName = null;
        // 共用 EQ/頻譜/隨機動作：若本地仍在播，保留
        if (currentMusicPlayer == null) {
            releaseMusicEqualizerLocked();
            releaseMusicVisualizerLocked();
        }
        stopSharedFillerLoopIfIdle();
    }

    /** 最近一次電台搜尋結果快取 (self.media.search_radio 存低, play 時先比對，
     *  見 resolveRadioStation javadoc)。volatile 讀寫，任一 thread 都用得。 */
    private volatile java.util.List<org.json.JSONObject> lastRadioSearchResults;

    private static final String RADIO_BROWSER_API_HOST = "http://de1.api.radio-browser.info";

    /** 官方文件要求每個 request 都帶一個有意義的 User-Agent (格式 appname/version),
     *  讓他們知道哪些 app 在用這個服務 - 這裡老實地帶上這個 project 的名字。 */
    private static final String RADIO_BROWSER_USER_AGENT = "OpenAlpha2/1.0";

    /** 用 Radio Browser 的 "Advanced station search" endpoint
     *  (/json/stations/search) 動態搜尋全世界電台 - 這個 API 完全公開、免費、不需要
     *  API key, 資料來自電台自己申報給這個公開 directory 的串流位址 (不是擷取
     *  受保護內容那種), 詳見官方文件 docs.radio-browser.info。
     *
     *  參數選擇 (2026-08 更新, 用戶回報「電台... 只選地方選電台也出現問題,
     *  和格式無關」之後查 logcat 確認、加強):
     *  - order=votes&reverse=true: 最多人投好的電台排在前面, 有助於過濾掉死台/垃圾台
     *  - hidebroken=true: 不顯示 Radio Browser 定期健康檢查已知播不了的台
     *  - codec=MP3: 只要 MP3 - Android 5.1 的 MediaPlayer 對 MP3 支援最穩定,
     *    某些台用的 codec (AAC+ 變種、OGG 等) 在這個 API level 未必個個都播得了
     *  - is_https=false: 只要串流位址本身是 http (不是 https) 的台 - 這個才是
     *    用戶回報問題的真正根源 (見下面 "真正根源" 段落), 和選哪個地方/哪個
     *    電台無關, 每一次 search_radio/play_radio call 都是同一個 exception。
     *
     *  真正根源 (2026-08 用 logcat 確認): 之前用戶回報「收音機要驗證, 用不了」
     *  以為是播放格式問題所以加了 codec=MP3, 但現在憑實際 logcat 看到的
     *  exception 是 java.security.cert.CertPathValidatorException: Trust
     *  anchor for certification path not found - 這是 Android 5.1 (2015 年
     *  出廠) 的系統 CA store 沒有收錄現代 CA/certificate chain, 而且 Android 5.1
     *  無法 OTA 更新系統 CA store, 所以連 https 握手都過不了, 完全和選哪個電台
     *  無關: (1) 這個 API 本身 (RADIO_BROWSER_API_HOST) 已經改用 http 避開了
     *  問題; (2) 但 station 的 "url_resolved" 播放位址本身也可能是 https,
     *  MediaPlayer 播放 https 串流一樣走 Android 系統的 TLS 堆疊
     *  (android.security.net.config.RootTrustManager), 一樣會撞上同一個
     *  trust anchor 問題 - 所以這裡連搜尋結果都要選 is_https=false, 才能讓
     *  「找到的台」和「播得了的台」一致, 而不是搜尋 API 沒中招、實際播放又中招。
     *
     *  HLS (.m3u8 分段串流, 舊版 MediaPlayer 支援不穩定、部分還需要
     *  session/token) 這個特徵沒有直接開放做 API 參數, 在 resolveRadioStation()
     *  裡、播放之前檢查 station 的 "hls" 欄位來過濾掉。
     *
     *  沒有把整套 API filter (country/language/tag 等) 暴露給 LLM, 保持
     *  self.media.search_radio 的 schema 簡單、只要一個 query 就夠 - 這沿用
     *  self.media.play_music 用 fuzzy match 不用一大堆 filter 參數的同一套
     *  「LLM 用自然語言, 不用懂 API 細節」設計原則。query 直接餵給 "name" 這個
     *  參數 (Radio Browser 的 name 搜尋本身就是不分大小寫的 substring, 不用這台機器
     *  自己再做 fuzzy match)。在獨立 thread (由 HttpServer 的
     *  newCachedThreadPool 保證, 每個 HTTP request 已經在自己的 thread) 上執行
     *  blocking HttpURLConnection, 不在 UI thread 做, 安全性和
     *  xiaozhiVisionExplainRequest() 一致。 */
    public java.util.List<org.json.JSONObject> searchRadioStations(String query, int limit)
            throws java.io.IOException, org.json.JSONException {
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String urlStr = RADIO_BROWSER_API_HOST + "/json/stations/search?name=" + encodedQuery
                + "&order=random&reverse=true&hidebroken=true"
                + "&limit=" + limit;

        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            NetLog.out("radio-search", urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", RADIO_BROWSER_USER_AGENT);

            int status = conn.getResponseCode();
            java.io.InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseText = is != null ? MainActivity.readFully(is) : "";
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Radio Browser search returned HTTP " + status);
            }
            org.json.JSONArray arr = new org.json.JSONArray(responseText);
            java.util.List<org.json.JSONObject> result = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getJSONObject(i));
            }
            lastRadioSearchResults = result;
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 尋找一個人類語言的電台名 - 先在 lastRadioSearchResults (最近一次
     *  self.media.search_radio/self.media.play_radio 觸發的搜尋結果) 裡做精確/
     *  substring 比對, 找不到才把這個 query 本身當成一個新的搜尋詞、再打一次
     *  Radio Browser API。這樣設計的原因: (1) LLM 常常會先 search_radio 取得
     *  幾個候選再由用戶或自己選一個名, 這種情況應該從已有的結果裡選,
     *  不應該重新打 API (慢、也可能因為 order=votes 的隨機性選到別的台); (2) 如果
     *  LLM 或用戶直接只說一個電台名 (例如 "播BBC")、之前又沒搜過, 這個
     *  method 也應該自己處理好, 不用逼 LLM 一定要分兩步做。找不到就回傳 null -
     *  和 resolveActionId()/resolveLocalMusicFile() 一致的「信心不足就說找不到,
     *  不亂猜」原則。 */
    public org.json.JSONObject resolveRadioStation(String query) throws java.io.IOException,
            org.json.JSONException {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return null;

        java.util.List<org.json.JSONObject> cached = lastRadioSearchResults;
        if (cached != null) {
            for (org.json.JSONObject s : cached) {
                if (q.equals(s.optString("stationuuid"))) return s;
            }
            for (org.json.JSONObject s : cached) {
                if (q.equalsIgnoreCase(s.optString("name"))) return s;
            }
            String qLower = q.toLowerCase(java.util.Locale.US);
            for (org.json.JSONObject s : cached) {
                String nameLower = s.optString("name").toLowerCase(java.util.Locale.US);
                if (!nameLower.isEmpty()
                        && (qLower.contains(nameLower) || nameLower.contains(qLower))) {
                    return s;
                }
            }
        }

        // Cache 裡找不到 (或者根本沒搜過) - 把這個 query 當成新搜尋詞, 打一次
        // Radio Browser, 選第一個不是 HLS 的結果 (HLS 在舊版 MediaPlayer 支援
        // 不穩定, 直接跳過)。
        java.util.List<org.json.JSONObject> fresh = searchRadioStations(q, 30);
        lastRadioSearchResults = fresh;
        for (org.json.JSONObject s : fresh) {
            if (s.optInt("hls", 0) == 0) {
                return s;
            }
        }
        return fresh.isEmpty() ? null : fresh.get(0);
    }

    /** 在獨立 thread 上選一個隨機動作 (actionDirect.resolveRandomActionId())
     *  並播放, fire-and-forget、不理會成功失敗、不 block caller - 供 TTS "start"
     *  event 和 self.media.play_music 共用, 兩者想要的是完全同一種「動一下讓
     *  機器人看起來生動一點」效果。不在 WebSocket read loop thread/HTTP worker
     *  thread 上直接做 blocking call，一律開獨立 thread。 */
    public void triggerRandomFillerAction() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String randomId = actionDirect.resolveRandomActionId();
                if (randomId != null) {
                    actionDirect.playActionDirect(randomId);
                }
            }
        }, "XiaozhiAutoRandomAction").start();
    }

    /** 2026-08 新增: 本地音樂 tab 的拖放上傳功能 - 瀏覽器把檔案內容原封不動 POST
     *  到這個 endpoint (?name=<原本檔名>), 寫入 LOCAL_MUSIC_DIR。檔名只做
     *  sanitizeUploadFilename() (去掉路徑分隔符/上層目錄嘗試), 不做內容檢查
     *  (例如是否真的是一個有效的音訊檔) - 沿用 listLocalMusicFiles() 一致的原則:
     *  只看副檔名, 真正播不播得了留給 MediaPlayer.prepareAsync() 時自然
     *  onError, 不在這裡重複做判斷。副檔名要在 LOCAL_MUSIC_EXTENSIONS 裡面才
     *  收 (避免用呢個 endpoint 上載任意檔案類型到機身)。如果 LOCAL_MUSIC_DIR
     *  仲未存在 (第一次用呢個功能), 順手 mkdirs()。 */
    public HttpServer.ApiResponse handleMusicUpload(Map<String, String> query, byte[] body) {
        String rawName = query.get("name");
        if (rawName == null || rawName.trim().isEmpty()) {
            return HttpServer.ApiResponse.error("name query parameter is required");
        }
        String safeName = sanitizeUploadFilename(rawName);
        if (safeName.isEmpty()) {
            return HttpServer.ApiResponse.error("invalid file name");
        }
        int dot = safeName.lastIndexOf('.');
        String ext = dot >= 0 && dot < safeName.length() - 1
                ? safeName.substring(dot + 1).toLowerCase(java.util.Locale.US) : "";
        if (!LOCAL_MUSIC_EXTENSIONS.contains(ext)) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"unsupported file type: ."
                    + MainActivity.jsonSafe(ext) + "\"}");
        }
        if (body == null || body.length == 0) {
            return HttpServer.ApiResponse.error("empty file body");
        }
        try {
            if (!LOCAL_MUSIC_DIR.exists() && !LOCAL_MUSIC_DIR.mkdirs()) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"failed to create music folder\"}");
            }
            java.io.File dest = new java.io.File(LOCAL_MUSIC_DIR, safeName);
            // 避免撞名覆蓋另一首已經存在的歌 - 自動加 " (2)"/" (3)" 這類尾綴,
            // 和瀏覽器下載檔案撞名那種做法一致, 用戶預期不會「悄悄蓋掉舊檔」。
            dest = uniqueFileFor(dest);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                fos.write(body);
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"name\":\""
                    + MainActivity.jsonSafe(dest.getName()) + "\",\"sizeBytes\":" + body.length + "}");
        } catch (Exception e) {
            Log.w(TAG, "Music upload failed for " + safeName, e);
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /** 只保留檔名本身的最後一截 (new File(name).getName() 已經剝掉任何
     *  "../"/"/" 這類路徑成分), 再去掉頭尾的空白, 保證寫入 LOCAL_MUSIC_DIR
     *  的結果一定在這個資料夾裡面, 不會因為用戶 (或惡意請求) 在檔名中夾帶
     *  路徑分隔符而寫到第二個資料夾度。
     *  2026-09-09：先將 Windows 式反斜線轉正斜線——Linux 上 getName() 唔識剝
     *  "a\b"，唔轉會成個 "a\b" 當檔名（寫唔出事但怪；轉咗取最後一截先啱）。*/
    private static String sanitizeUploadFilename(String rawName) {
        String base = new java.io.File(rawName.trim().replace('\\', '/')).getName();
        return base.trim();
    }

    /** 如果 candidate 已經存在, 在副檔名前面加 " (2)"、" (3)"... 直到找到一個
     *  未用過的檔名為止, 保證上傳永遠不會覆蓋一首已經存在的歌。*/
    private static java.io.File uniqueFileFor(java.io.File candidate) {
        if (!candidate.exists()) return candidate;
        String name = candidate.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : "";
        java.io.File parent = candidate.getParentFile();
        int n = 2;
        java.io.File next;
        do {
            next = new java.io.File(parent, base + " (" + n + ")" + ext);
            n++;
        } while (next.exists());
        return next;
    }

    // -- HTTP endpoints (handleApi 轉調，參數校驗照舊經 ApiValidator) ----------

    public HttpServer.ApiResponse localMusicList() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
        boolean first = true;
        for (java.io.File f : listLocalMusicFiles()) {
            if (!first) sb.append(",");
            first = false;
            // 2026-08 新增 sizeBytes - 供音樂 tab 的檔案清單顯示檔案大小用,
            // 舊有的語音/小智呼叫路徑 (resolveLocalMusicFile 只看 "name")
            // 不受這個新加欄位影響, 純粹多加一個 key。
            sb.append("{\"name\":\"").append(MainActivity.jsonSafe(f.getName())).append("\",")
                    .append("\"sizeBytes\":").append(f.length()).append("}");
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse localMusicPlay(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        java.io.File resolved = resolveLocalMusicFile(name);
        if (resolved == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"file not found\"}");
        }
        playLocalMusicFile(resolved);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                + MainActivity.jsonSafe(resolved.getName()) + "\"}");
    }

    public HttpServer.ApiResponse localMusicStop() {
        stopLocalMusicPlayback();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-08 新增: 供瀏覽器音樂 tab 用的播放狀態/進度/音量 endpoint -
    // 之前這一套 local_music 純粹供小智語音/AI tool call 使用, 進度
    // 條 UI 用不到。這幾個 endpoint 沒有改動任何播放邏輯本身, 只是供前端
    // 讀/寫 currentMusicPlayer 已有的狀態。
    public HttpServer.ApiResponse localMusicStatus() {
        synchronized (this) {
            android.media.MediaPlayer mp = currentMusicPlayer;
            if (mp == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"hasTrack\":false,"
                        + "\"playing\":false,\"positionMs\":0,\"durationMs\":0,\"name\":null}");
            }
            boolean playing = false;
            int pos = 0;
            int dur = 0;
            try {
                playing = mp.isPlaying();
                pos = mp.getCurrentPosition();
                dur = mp.getDuration();
            } catch (Exception e) {
                // MediaPlayer 在 prepareAsync() 尚未完成的那段窗口呼叫這幾個
                // getter 會拋出 IllegalStateException - 當「尚未準備好」, 退回
                // 使用預設值 0/false, 不算真正錯誤。
            }
            String name = currentMusicTrackName;
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"hasTrack\":true,"
                    + "\"playing\":" + playing + ","
                    + "\"positionMs\":" + pos + ","
                    + "\"durationMs\":" + dur + ","
                    + "\"name\":" + (name != null ? "\"" + MainActivity.jsonSafe(name) + "\"" : "null") + "}");
        }
    }

    public HttpServer.ApiResponse localMusicSeek(Map<String, String> query) {
        int ms = ApiValidator.requireInt(query, "ms");
        synchronized (this) {
            if (currentMusicPlayer == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
            }
            try {
                currentMusicPlayer.seekTo(ms);
            } catch (Exception e) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                        + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse localMusicVolume(Map<String, String> query) {
        int pct = ApiValidator.requireVolumePercent(query);
        float v = pct / 100f;
        synchronized (this) {
            if (currentMusicPlayer == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
            }
            try {
                currentMusicPlayer.setVolume(v, v);
            } catch (Exception e) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                        + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // -- Media volume: STREAM_MUSIC, same stream the +/- gesture buttons and
    // the walkie-talkie/TTS playback all use (see MainActivity
    // registerGestureController()/startVolumeRepeat()) - so this slider and
    // the physical +/- pads stay in sync with each other. (2026-09 dispatcher
    // Phase 1 第四刀由 handleApi 搬入；經 appContext 攞同一個 service。)
    public HttpServer.ApiResponse systemVolumeGet() {
        AudioManager audioManager =
                (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        int max = audioManager != null
                ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 0;
        int cur = audioManager != null
                ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"volume\":" + cur
                + ",\"max\":" + max + "}");
    }

    public HttpServer.ApiResponse systemVolumeSet(Map<String, String> query) {
        AudioManager audioManager =
                (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return HttpServer.ApiResponse.error("AudioManager not available");
        }
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = ApiValidator.requireInt(query, "level");
        vol = Math.max(0, Math.min(max, vol));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"volume\":" + vol + ",\"max\":" + max + "}");
    }
    // 2026-08 v2 新增: audio spectrum - 回傳最近一次 FFT 算出的頻譜
    // (MUSIC_SPECTRUM_BANDS 條, 每條 0-255), 前端 ~100ms 輪詢一次畫 bar。
    // 沒播歌/Visualizer 建不起來就全部回傳 0。
    public HttpServer.ApiResponse localMusicSpectrum() {
        StringBuilder sbSpec = new StringBuilder("{\"ok\":true,\"bands\":[");
        synchronized (this) {
            for (int i = 0; i < MUSIC_SPECTRUM_BANDS; i++) {
                if (i > 0) sbSpec.append(",");
                sbSpec.append(musicSpectrumBands[i]);
            }
        }
        sbSpec.append("]}");
        return HttpServer.ApiResponse.ok(sbSpec.toString());
    }

    // 2026-08 v2 新增: 真・暫停/恢復 - MediaPlayer.pause() 之後個播放位置
    // 一直記住, 之後 start() 就從那裡繼續, 不用從頭播放。之前前端用
    // "stop 當 pause" 的變通法, 恢復時整首歌從頭來, 用戶投訴過這一點。
    // 注意: pause/resume 都不會動到 musicFillerActionLoop - 暫停期間那個 loop
    // 仍在執行 (triggerRandomFillerAction() 有它自己「沒在播就不動」的
    // 判斷), 沿用原本播歌期間的行為。
    public HttpServer.ApiResponse localMusicPause() {
        synchronized (this) {
            if (currentMusicPlayer == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
            }
            try {
                currentMusicPlayer.pause();
            } catch (Exception e) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                        + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse localMusicResume() {
        synchronized (this) {
            if (currentMusicPlayer == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"no track loaded\"}");
            }
            try {
                currentMusicPlayer.start();
            } catch (Exception e) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                        + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-08 新增: Equalizer presets - 用返 android.media.audiofx.Equalizer
    // 自己的 preset 清單 (由裝置/廠商決定有多少個、叫什麼名, 例如 "Normal"、
    // "Classical"、"Rock" 等, 不是這個 app 自己定義的一套), 保證和這台機器
    // 實際安裝的 audio effect engine 一致, 不會出現選了個 UI 名但
    // usePreset() 對不上的情況。沒播歌 (musicEqualizer 尚未建立) 也要給出
    // 清單 (建一個臨時 Equalizer 取得清單再立即放掉), 讓用戶還沒播歌也能看到
    // 有咩 preset 可以揀。
    public HttpServer.ApiResponse localMusicEqPresets() {
        android.media.audiofx.Equalizer temp = null;
        try {
            temp = new android.media.audiofx.Equalizer(0, 0);
            short numPresets = temp.getNumberOfPresets();
            StringBuilder sbEq = new StringBuilder("{\"ok\":true,\"presets\":[");
            for (short i = 0; i < numPresets; i++) {
                if (i > 0) sbEq.append(",");
                sbEq.append("{\"index\":").append(i).append(",\"name\":\"")
                        .append(MainActivity.jsonSafe(temp.getPresetName(i))).append("\"}");
            }
            sbEq.append("],\"current\":").append(musicEqPresetIndex).append("}");
            return HttpServer.ApiResponse.ok(sbEq.toString());
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"presets\":[],\"current\":-1,"
                    + "\"unavailable\":true}");
        } finally {
            if (temp != null) {
                try {
                    temp.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public HttpServer.ApiResponse localMusicEqSet(Map<String, String> query) {
        int idx = ApiValidator.requireInt(query, "index");
        // 存下選擇 (不理會現在是否正在播放), 等下一首歌開始播時
        // setupMusicEqualizerLocked() 都會跟返呢個 preset。
        prefs().edit().putInt(PREF_MUSIC_EQ_PRESET, idx).apply();
        synchronized (this) {
            musicEqPresetIndex = idx;
            if (musicEqualizer != null) {
                try {
                    if (idx >= 0 && idx < musicEqualizer.getNumberOfPresets()) {
                        musicEqualizer.usePreset((short) idx);
                    }
                } catch (Exception e) {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                            + MainActivity.jsonSafe(String.valueOf(e.getMessage())) + "\"}");
                }
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-08 新增: 「播歌隨機動作」開關 - 用戶要求可以自己開關, 之前呢個
    // 行為一直都是跟著有沒有正在播歌自動開/關, 沒有獨立開關按鈕。預設 true
    // (和 isMusicFillerActionEnabled() 尚未讀過設定時的預設值一致, 保持之前
    // 行為)。
    public HttpServer.ApiResponse fillerActionGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":"
                + isMusicFillerActionEnabled() + "}");
    }

    public HttpServer.ApiResponse fillerActionSet(Map<String, String> query) {
        boolean enabled = ApiValidator.requireBoolean(query, "enabled");
        prefs().edit().putBoolean(PREF_MUSIC_FILLER_ACTION_ENABLED, enabled).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled + "}");
    }

    // -- FM/網絡電台 (經 Radio Browser API, radio-browser.info, 動態搜全
    // 世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 的
    // javadoc, 這台機器不再內建任何寫死的電台清單) - "search" 對應
    // self.media.search_radio, "play" 用 resolveRadioStation() 做人類
    // 語言名比對 (先比對 lastRadioSearchResults, 比對不到就直接當新搜尋詞打
    // API)。多加一個 "status" 供前端面板顯示「目前正在播哪個台」用 (電台沒有
    // 檔名那麼直觀, 用戶自己按「轉台」之後有需要知道結果)。這兩個 endpoint
    // 內部會打網路, 和 MCP tool 那邊不同 (那邊有外層 try/catch(Exception)
    // 包住整個 switch), handleApi() 沒有, 所以這裡自己要包一層 try/catch
    // 把 IOException/JSONException 轉成正常的 {"ok":false,...} 回應,
    // 不可以讓 exception 直接飛出 handleApi()。
    public HttpServer.ApiResponse radioSearch(Map<String, String> query) {
        String q = ApiValidator.require(query, "query");
        try {
            java.util.List<org.json.JSONObject> found = searchRadioStations(q, 30);
            lastRadioSearchResults = found;
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"stations\":[");
            boolean first = true;
            for (org.json.JSONObject s : found) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"name\":\"").append(MainActivity.jsonSafe(s.optString("name")))
                        .append("\",\"country\":\"").append(MainActivity.jsonSafe(s.optString("country")))
                        .append("\"}");
            }
            sb.append("]}");
            return HttpServer.ApiResponse.ok(sb.toString());
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe("radio search failed: " + e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse radioPlay(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        try {
            org.json.JSONObject resolved = resolveRadioStation(name);
            if (resolved == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"station not found\"}");
            }
            playRadioStream(resolved);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                    + MainActivity.jsonSafe(resolved.optString("name")) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe("radio search failed: " + e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse radioPlayUrl(Map<String, String> query) {
        String url = ApiValidator.require(query, "url");
        String nameHint = ApiValidator.optionalNullable(query, "name");
        try {
            // 2026-08 新增: 供前端直連 radio-browser.info fallback 用 — 瀏覽器自己
            // fetch 完搜尋結果 (繞過機械人本身 DNS/無外網問題看列表), 再將選中台的
            // url_resolved 直接送來此 endpoint 播放, 不再經 resolveRadioStation()
            // 重新打一次 Radio Browser API (那步在機械人無外網時必定失敗)。
            org.json.JSONObject station = new org.json.JSONObject();
            station.put("url_resolved", url);
            station.put("url", url);
            station.put("name", nameHint != null ? nameHint : url);
            station.put("stationuuid", "frontend-" + System.currentTimeMillis());
            playRadioStream(station);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":\""
                    + MainActivity.jsonSafe(station.optString("name")) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe("radio play_url failed: " + e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse radioStop() {
        stopRadioPlayback();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse radioStatus() {
        String id = currentRadioStationId;
        if (id == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":false}");
        }
        String currentName = currentRadioStationName;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"playing\":true,\"id\":\""
                + MainActivity.jsonSafe(id) + "\",\"name\":\""
                + MainActivity.jsonSafe(currentName == null ? "" : currentName) + "\"}");
    }

    // -- MCP tools (XiaozhiBridge callTool switch 轉調；2026-09 MCP 收斂 Phase 2,
    // case 本體逐字搬入，isError＋resultText 經 SonarCenter.McpResult 帶返出去。
    // self.media.search_radio/play_radio 底層的 searchRadioStations()/
    // resolveRadioStation() 拋出的 IOException/JSONException 保持原樣拋出 -
    // 沿用原本 callTool() 外層 try/catch (Exception e) 接住的做法, 這裡不吞。) --

    /** self.media.list_music 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpListMusic() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (java.io.File f : listLocalMusicFiles()) {
            arr.put(f.getName());
        }
        return SonarCenter.McpResult.ok(arr.toString());
    }

    /** self.media.play_music 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpPlayMusic(org.json.JSONObject arguments) {
        String musicName = arguments.optString("name", "");
        if (musicName.isEmpty()) {
            return SonarCenter.McpResult.err("missing required argument: name");
        }
        java.io.File resolved = resolveLocalMusicFile(musicName);
        if (resolved == null) {
            return SonarCenter.McpResult.err("no music file found matching \"" + musicName
                    + "\" - call self.media.list_music to see available files");
        }
        playLocalMusicFile(resolved);
        return SonarCenter.McpResult.ok("now playing \"" + resolved.getName() + "\"");
    }

    /** self.media.stop_music 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpStopMusic() {
        stopLocalMusicPlayback();
        return SonarCenter.McpResult.ok("ok");
    }

    /** self.media.search_radio 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpSearchRadio(org.json.JSONObject arguments)
            throws java.io.IOException, org.json.JSONException {
        String searchQuery = arguments.optString("query", "");
        if (searchQuery.isEmpty()) {
            return SonarCenter.McpResult.err("missing required argument: query");
        }
        java.util.List<org.json.JSONObject> found = searchRadioStations(searchQuery, 30);
        if (found.isEmpty()) {
            return SonarCenter.McpResult.ok("no radio stations found matching \"" + searchQuery + "\"");
        }
        org.json.JSONArray arr = new org.json.JSONArray();
        for (org.json.JSONObject s : found) {
            String country = s.optString("country");
            String label = s.optString("name")
                    + (country.isEmpty() ? "" : " (" + country + ")");
            arr.put(label);
        }
        return SonarCenter.McpResult.ok(arr.toString());
    }

    /** self.media.play_radio 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpPlayRadio(org.json.JSONObject arguments)
            throws java.io.IOException, org.json.JSONException {
        String stationName = arguments.optString("name", "");
        if (stationName.isEmpty()) {
            return SonarCenter.McpResult.err("missing required argument: name");
        }
        org.json.JSONObject resolvedStation = resolveRadioStation(stationName);
        if (resolvedStation == null) {
            return SonarCenter.McpResult.err("no radio station found matching \"" + stationName + "\"");
        }
        playRadioStream(resolvedStation);
        return SonarCenter.McpResult.ok("now playing \"" + resolvedStation.optString("name") + "\"");
    }

    /** self.media.stop_radio 本體 (XiaozhiBridge 轉調)。 */
    public SonarCenter.McpResult mcpStopRadio() {
        stopRadioPlayback();
        return SonarCenter.McpResult.ok("ok");
    }
}
