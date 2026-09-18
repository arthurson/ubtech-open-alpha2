package com.open.alpha2;

/**
 * MediaPlayer 收尾共用形。
 *
 * 之前 stop()+release()／reset()+release() 各自 try/catch 吞錯的 8 行模板在
 * RingtoneCenter / AudioCenter（本地＋電台）/ MusicController 各複製一份。
 * 收斂到這裡，行為不變：
 * - stop()/reset() 擲錯照吞（prepareAsync 中途 race 的 IllegalStateException 屬正常），
 *   release() 一樣照做；
 * - null 即 no-op，調用方不用再寫 if (player != null) 包住。
 *
 * 不做 synchronized——調用方各自持有自己把鎖（*Locked／synchronized method），
 * 這裡僅做事，不碰鎖。
 */
public final class MediaPlayerUtil {
    private MediaPlayerUtil() {}

    /** stop()＋release()（Ringtone／本地音樂／電台用）。 */
    public static void stopRelease(android.media.MediaPlayer p) {
        if (p == null) return;
        try {
            p.stop();
        } catch (Exception ignored) {
            // prepareAsync 中途 race 可能 IllegalStateException，release 照做。
        }
        try {
            p.release();
        } catch (Exception ignored) {
            // already released/invalid - ignore
        }
    }

    /** reset()＋release()（MusicController 用：它部 player 會重用，不 stop）。 */
    public static void resetRelease(android.media.MediaPlayer p) {
        if (p == null) return;
        try {
            p.reset();
        } catch (Exception ignored) {
        }
        try {
            p.release();
        } catch (Exception ignored) {
        }
    }
}

