package com.open.alpha2;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本地音樂播放 - 用標準 android.media.MediaPlayer 直接播 /sdcard 裡面的音樂檔,
 * 經 STREAM_MUSIC 出到機器人本身的喇叭 (這條路由 AudioPlaybackController 那個
 * class javadoc 確認過行得通)。
 *
 * 和 AudioController/AudioPlaybackController 不同, 這裡沒有涉及任何 AIDL 或者
 * PCM 網路串流 - 純粹是播放已經存在機身 SD 卡裡面的完整音樂檔案 (mp3/wav/ogg
 * 等), 用 MediaPlayer 自己的 decode + STREAM_MUSIC 輸出就好, 不用自己手動
 * decode 再餵 AudioTrack。
 *
 * 沒用 dedicated HandlerThread (和其他 controller 不同) - MediaPlayer 本身
 * 已經是 async API (prepareAsync + callback), 主要操作只在 HTTP worker
 * thread 裡做, 用 synchronized 保證同一時間只有一個操作在改 player 狀態。
 */
public class MusicController {
    private static final String TAG = "MusicController";

    /** 掃描呢幾個資料夾搵音樂檔, 由上至下, 全部合埋一齊列出。*/
    private static final String[] SCAN_DIRS = {
            "/sdcard/Music",
            "/sdcard/music",
            "/sdcard/Download",
    };

    private static final String[] SUPPORTED_EXT = {
            ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac",
    };

    public static final class Track {
        public final String path;
        public final String name;
        public final long sizeBytes;
        Track(String path, String name, long sizeBytes) {
            this.path = path;
            this.name = name;
            this.sizeBytes = sizeBytes;
        }
    }

    private final Object lock = new Object();
    private MediaPlayer player;
    private String currentPath;
    private boolean prepared;
    private boolean playRequestedOnPrepare;

    /** 列出全部找到的音樂檔, 跨 SCAN_DIRS, 用檔名排序。*/
    public List<Track> listTracks() {
        List<Track> result = new ArrayList<>();
        FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String lower = name.toLowerCase(java.util.Locale.US);
                for (String ext : SUPPORTED_EXT) {
                    if (lower.endsWith(ext)) return true;
                }
                return false;
            }
        };
        for (String dirPath : SCAN_DIRS) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles(filter);
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                result.add(new Track(f.getAbsolutePath(), f.getName(), f.length()));
            }
        }
        Collections_sortByName(result);
        return result;
    }

    private static void Collections_sortByName(List<Track> list) {
        java.util.Collections.sort(list, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    /** 播放指定路徑的檔案 (由 listTracks() 取回來的 path) - 換歌會先停掉舊的。
     *  path 必須是 SCAN_DIRS 裡面找到的其中一個檔案 (由 listTracks() 產生),
     *  不接受任意路徑, 避免經這個 API 讀到 SD 卡上其他不想給人讀的檔案。*/
    public String play(final String path) {
        if (path == null || path.isEmpty()) return "path is required";
        File f = new File(path);
        if (!isAllowedPath(f)) {
            return "path not allowed (must be a scanned music file)";
        }
        if (!f.isFile()) {
            return "file not found: " + path;
        }
        synchronized (lock) {
            releaseLocked();
            currentPath = path;
            prepared = false;
            playRequestedOnPrepare = true;
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try {
                player.setDataSource(path);
            } catch (Exception e) {
                Log.e(TAG, "setDataSource failed for " + path, e);
                releaseLocked();
                return "failed to open file: " + e.getMessage();
            }
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    synchronized (lock) {
                        if (mp != player) return; // 已經被第二個 play()/stop() 換掉了
                        prepared = true;
                        if (playRequestedOnPrepare) {
                            try {
                                mp.start();
                            } catch (Exception e) {
                                Log.e(TAG, "start() after prepare failed", e);
                            }
                        }
                    }
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    // 播完自然停 - 保留 currentPath/prepared 狀態給 status() 可以
                    // 反映「上一首play完喇」, isPlaying() 會自然回傳 false。
                    Log.i(TAG, "Playback completed: " + currentPath);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra + " for " + currentPath);
                    return true; // consumed - 不用再觸發 onCompletion
                }
            });
            try {
                player.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "prepareAsync failed", e);
                releaseLocked();
                return "failed to prepare: " + e.getMessage();
            }
        }
        return null; // null = success
    }

    public String pause() {
        synchronized (lock) {
            if (player == null) return "nothing is playing";
            playRequestedOnPrepare = false;
            try {
                if (prepared && player.isPlaying()) player.pause();
            } catch (Exception e) {
                return "pause failed: " + e.getMessage();
            }
        }
        return null;
    }

    public String resume() {
        synchronized (lock) {
            if (player == null) return "no track loaded";
            playRequestedOnPrepare = true;
            try {
                if (prepared) player.start();
                // 未 prepare 完的話, onPrepared() 見到 playRequestedOnPrepare=true
                // 自然會幫手 start()。
            } catch (Exception e) {
                return "resume failed: " + e.getMessage();
            }
        }
        return null;
    }

    public String stop() {
        synchronized (lock) {
            releaseLocked();
        }
        return null;
    }

    public String seekTo(int ms) {
        synchronized (lock) {
            if (player == null || !prepared) return "no track ready";
            try {
                player.seekTo(ms);
            } catch (Exception e) {
                return "seek failed: " + e.getMessage();
            }
        }
        return null;
    }

    /** 0-100 的百分比音量, 分別set左右聲道 (STREAM_MUSIC 本身的系統音量由用戶
     *  用機身/瀏覽器那個總音量 slider 控制 - 這個是這首歌自己的相對音量)。*/
    public String setVolume(int percent0to100) {
        float v = Math.max(0, Math.min(100, percent0to100)) / 100f;
        synchronized (lock) {
            if (player == null) return "no track loaded";
            try {
                player.setVolume(v, v);
            } catch (Exception e) {
                return "setVolume failed: " + e.getMessage();
            }
        }
        return null;
    }

    public static final class Status {
        public final boolean hasTrack;
        public final boolean playing;
        public final boolean prepared;
        public final String path;
        public final int positionMs;
        public final int durationMs;
        Status(boolean hasTrack, boolean playing, boolean prepared, String path, int positionMs, int durationMs) {
            this.hasTrack = hasTrack;
            this.playing = playing;
            this.prepared = prepared;
            this.path = path;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    public Status status() {
        synchronized (lock) {
            if (player == null) {
                return new Status(false, false, false, null, 0, 0);
            }
            boolean playing = false;
            int pos = 0;
            int dur = 0;
            if (prepared) {
                try {
                    playing = player.isPlaying();
                    pos = player.getCurrentPosition();
                    dur = player.getDuration();
                } catch (Exception e) {
                    // MediaPlayer 在某些狀態下這幾個 getter 會擲 IllegalStateException -
                    // 屬正常, 退回用預設值 0 就好, 不算真正錯誤。
                }
            }
            return new Status(true, playing, prepared, currentPath, pos, dur);
        }
    }

    private void releaseLocked() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        prepared = false;
        playRequestedOnPrepare = false;
        currentPath = null;
    }

    private boolean isAllowedPath(File f) {
        String target;
        try {
            target = f.getCanonicalPath();
        } catch (Exception e) {
            return false;
        }
        for (String dirPath : SCAN_DIRS) {
            File dir = new File(dirPath);
            String canonicalDir;
            try {
                canonicalDir = dir.getCanonicalPath();
            } catch (Exception e) {
                continue;
            }
            if (target.equals(canonicalDir)) continue; // 不可以是資料夾本身
            if (target.startsWith(canonicalDir + File.separator)) {
                String lower = target.toLowerCase(java.util.Locale.US);
                for (String ext : SUPPORTED_EXT) {
                    if (lower.endsWith(ext)) return true;
                }
            }
        }
        return false;
    }

    public void shutdown() {
        synchronized (lock) {
            releaseLocked();
        }
    }
}
