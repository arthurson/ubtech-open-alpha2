package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import java.util.Map;

/**
 * 系統鈴聲層：RingtoneManager 查表 + STREAM_MUSIC MediaPlayer 播放。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第七刀)：停止/快門/PIR 三粒
 * 提示音、共用播放器、RingtoneManager 快取、title 查表，原本全部係
 * MainActivity 私有成員，搬過嚟邏輯不變。只需要 Context
 * (RingtoneManager + MediaPlayer setDataSource)，唔掂其他硬件。
 */
public final class RingtoneCenter {
    private static final String TAG = "RingtoneCenter";

    private final Context appContext;

    private static final String STOP_CUE_RINGTONE_TITLE = "Proxima";
    private android.net.Uri stopCueUri; // resolved lazily, cached after the first lookup
    private boolean stopCueLookupDone = false;
    // Camera shutter cue (played on the robot's own speaker, not the browser) - see
    // takePhoto()/HttpServer "camera/shutter_sound". "Sirrah" is a built-in Android
    // system ringtone title, matched the same lazy/cached-by-title way as the
    // Proxima stop cue above.
    private static final String SHUTTER_CUE_RINGTONE_TITLE = "Sirrah";
    private android.net.Uri shutterCueUri;
    private boolean shutterCueLookupDone = false;

    // PIR alert cue - "Heaven" 是 Android 內建系統鈴聲標題, 和 STOP_CUE/SHUTTER_CUE
    // 一樣做法 (lazy lookup by title, cache 住那個 content:// Uri)。播放時機見
    // MainActivity.registerAlpha2PirAlertListener() - alpha2_pir_state broadcast
    // (RobotEventReceiver.java) 一到 triggered=true 就立刻播, triggered=false 立刻停
    // (跟 sonar 的 purple LED 一樣, 不等整首歌播完)。
    private static final String PIR_ALERT_RINGTONE_TITLE = "Heaven";
    private android.net.Uri pirAlertUri;
    private boolean pirAlertLookupDone = false;

    public RingtoneCenter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Plays the "Proxima" system ringtone as the "stop" cue, on STREAM_MUSIC so its
     * loudness tracks the same media volume that +/- control - not the notification/
     * ring volume a plain Ringtone.play() would follow instead.
     *
     * Ringtone/RingtoneManager.getRingtone() always plays on the ringtone's own stream
     * type (TYPE_NOTIFICATION -> STREAM_NOTIFICATION), which can't be overridden - so
     * this resolves "Proxima" to a content:// Uri via RingtoneManager (matching by
     * title, since that's the only stable way to name a specific built-in system sound),
     * cached after the first lookup, and plays that Uri through a plain MediaPlayer with
     * setAudioStreamType(STREAM_MUSIC) instead, which does follow the stream we set.
     */
    public void playStopCue() {
        if (!stopCueLookupDone) {
            stopCueUri = findRingtoneByTitle(STOP_CUE_RINGTONE_TITLE);
            stopCueLookupDone = true;
            if (stopCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + STOP_CUE_RINGTONE_TITLE
                        + "\" - stop cue will be skipped");
            }
        }
        playRingtoneUri(stopCueUri);
    }

    /**
     * Plays the "Sirrah" system ringtone as the camera shutter cue, out of the robot's
     * own speaker (this Activity runs on the robot's onboard Android system, not the
     * phone/browser controlling it - see robotpanel README) rather than synthesizing a
     * sound in the browser. Same lazy-lookup-by-title-then-cache approach as
     * playStopCue()/STOP_CUE_RINGTONE_TITLE above - title is the only stable way to
     * name a specific built-in system sound across devices/Android versions.
     */
    public void playShutterCue() {
        if (!shutterCueLookupDone) {
            shutterCueUri = findRingtoneByTitle(SHUTTER_CUE_RINGTONE_TITLE);
            shutterCueLookupDone = true;
            if (shutterCueUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + SHUTTER_CUE_RINGTONE_TITLE
                        + "\" - shutter cue will be skipped");
            }
        }
        playRingtoneUri(shutterCueUri);
    }

    /** Plays the "Heaven" system ringtone as the PIR trigger alert - same lazy
     *  lookup-by-title-then-cache approach as playStopCue()/playShutterCue() (see
     *  playStopCue()'s javadoc for why title lookup + STREAM_MUSIC via playRingtoneUri()
     *  instead of a plain Ringtone.play()). */
    public void playPirAlertCue() {
        if (!pirAlertLookupDone) {
            pirAlertUri = findRingtoneByTitle(PIR_ALERT_RINGTONE_TITLE);
            pirAlertLookupDone = true;
            if (pirAlertUri == null) {
                Log.w(TAG, "Could not find a system ringtone titled \"" + PIR_ALERT_RINGTONE_TITLE
                        + "\" - PIR alert cue will be skipped");
            }
        }
        playRingtoneUri(pirAlertUri);
    }

    // 2026-08 新增 (修 bug): 之前 playRingtoneUri() 每次都開一個全新、完全沒有留下
    // reference 的 MediaPlayer, fire-and-forget, 播完/出錯後自己 release —— 這個
    // 做法有兩個問題: (1) 使用者在鈴聲還沒播完之前多次按下「播放」(或者 Blockly
    // 的「範例 5」多次執行), 就會有多個 MediaPlayer 同時各自播放, 聲音疊在
    // 一起, 聽起來像是「停不下來一直響」; (2) 完全沒有任何方法可以從外部 (前端「停止播放」
    // 按鈕) 中斷它, 一定要等整首歌/鈴聲自然播完。修法: 用這個 field 記住「目前正在播放
    // 的那個」MediaPlayer, 每次開新的之前先停掉舊的, 並且加入
    // audio/ringtones/stop 這個 endpoint 讓前端隨時可以中斷。
    private android.media.MediaPlayer currentRingtonePlayer;

    /** Shared playback: STREAM_MUSIC (see playStopCue()'s javadoc for why not a plain
     *  Ringtone.play()). Stops/releases whatever ringtone was previously playing before
     *  starting the new one, and keeps a reference so audio/ringtones/stop (or the next
     *  call to this method) can interrupt it early instead of only ever letting it run
     *  to completion. No-ops silently if uri is null (title lookup found nothing on this
     *  device). */
    public synchronized void playRingtoneUri(android.net.Uri uri) {
        stopRingtonePlaybackLocked();
        if (uri == null) {
            return;
        }
        try {
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(appContext, uri);
            player.setOnPreparedListener(android.media.MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                synchronized (RingtoneCenter.this) {
                    mp.release();
                    if (currentRingtonePlayer == mp) {
                        currentRingtonePlayer = null;
                    }
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (RingtoneCenter.this) {
                    mp.release();
                    if (currentRingtonePlayer == mp) {
                        currentRingtonePlayer = null;
                    }
                }
                return true;
            });
            currentRingtonePlayer = player;
            player.prepareAsync(); // don't block the main thread; starts once ready
        } catch (Exception e) {
            Log.w(TAG, "Failed to play ringtone cue " + uri, e);
        }
    }

    /** Stops whatever ringtone/notification-sound MediaPlayer is currently playing (if
     *  any) and releases it. Safe to call when nothing is playing - simply no-ops.
     *  Must hold the same lock as playRingtoneUri() so a stop() can never race a
     *  concurrent start(); callers already inside a `synchronized(this)` block (i.e.
     *  playRingtoneUri() itself) should call the *Locked variant instead of re-entering. */
    public synchronized void stopRingtonePlayback() {
        stopRingtonePlaybackLocked();
    }

    private void stopRingtonePlaybackLocked() {
        if (currentRingtonePlayer != null) {
            try {
                currentRingtonePlayer.stop();
            } catch (Exception e) {
                // MediaPlayer.stop() throws IllegalStateException if called from certain
                // states (e.g. still in the middle of prepareAsync()'s Prepared callback
                // race) - release()  still happens below either way, so this is safe to
                // swallow.
            }
            try {
                currentRingtonePlayer.release();
            } catch (Exception e) {
                // already released/invalid - ignore
            }
            currentRingtonePlayer = null;
        }
    }

    // 2026-08 更新 (修 bug): findRingtoneByTitle() 之前每次呼叫都 `new
    // RingtoneManager(this)`, 用完立刻拋棄那個 object, 但 Android 官方文件明確說明
    // RingtoneManager.getCursor() 每次取得的是*同一個*底層 cursor, 不應該由
    // 使用者自己 close() —— 它的生命週期本身是跟著 RingtoneManager instance
    // 走的, 如果沒有用 RingtoneManager(Activity) 這個會自動與 activity 生命週期綁定
    // 的 constructor (這裡用的是 RingtoneManager(Context), 沒有自動綁定), 就要自己
    // 保住這個 RingtoneManager instance, 不要用完即丟, 否則底層的 cursor 沒人釋放,
    // 一直洩漏 (實測 logcat 看到 CursorWindowAllocationException, # Open Cursors
    // 累積到 991 個, 就是這個 bug 導致的)。修法: 用 rmType (TYPE_RINGTONE /
    // TYPE_NOTIFICATION) 做 key, 快取住那兩個 RingtoneManager instance,
    // 整個 app 生命週期裡只 new 一次, 之後所有呼叫都取快取的那個來重用
    // (RingtoneManager.getCursor() 內部自己會 requery(), 不需要我們手動 refresh)。
    private final java.util.Map<Integer, android.media.RingtoneManager> ringtoneManagerCache = new java.util.HashMap<>();

    private synchronized android.media.RingtoneManager getCachedRingtoneManager(int rmType) {
        android.media.RingtoneManager cached = ringtoneManagerCache.get(rmType);
        if (cached != null) return cached;
        android.media.RingtoneManager manager = new android.media.RingtoneManager(appContext);
        manager.setType(rmType);
        ringtoneManagerCache.put(rmType, manager);
        return manager;
    }

    /** Scans every ringtone RingtoneManager knows about (notifications + ringtones)
     *  for one whose title matches exactly (case-insensitive), returning its Uri, or
     *  null if none match. Title is the only stable way to name a specific built-in
     *  system sound - resource IDs/file paths vary by OEM and Android version. */
    private android.net.Uri findRingtoneByTitle(String title) {
        return findRingtoneByTitle(title, android.media.RingtoneManager.TYPE_ALL);
    }

    /** Same as findRingtoneByTitle(String) but restricted to a single RingtoneManager
     *  type (TYPE_RINGTONE / TYPE_NOTIFICATION) - used by "audio/ringtones/play_by_title"
     *  so a phone-ringtone lookup can never accidentally match a notification sound (or
     *  vice versa) that happens to share the same title. Uses getCachedRingtoneManager()
     *  (see its javadoc) instead of `new RingtoneManager(this)` per call - the previous
     *  per-call instantiation leaked a Cursor every time this ran, since nothing ever
     *  released it (Android's RingtoneManager has no close()/release() of its own to call). */
    private android.net.Uri findRingtoneByTitle(String title, int rmType) {
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.database.Cursor cursor = manager.getCursor();
        int position = 0;
        while (cursor.moveToNext()) {
            String candidateTitle = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
            if (title.equalsIgnoreCase(candidateTitle)) {
                // getRingtoneUri() takes the cursor POSITION (0-based row index within
                // this RingtoneManager's result set), not a raw content-provider id -
                // Cursor has no getUri(); this is the correct API for it.
                return manager.getRingtoneUri(position);
            }
            position++;
        }
        return null;
    }

    // -- HTTP endpoints (handleApi 轉調，參數校驗照舊經 ApiValidator) ----------

    // -- System ringtones/notification sounds: exposes every ringtone Android
    // knows about (via RingtoneManager, same mechanism findRingtoneByTitle()
    // above already uses to look up "Proxima"/"Sirrah" by name) as a numbered
    // list, so the Blockly page can offer a dropdown without hardcoding titles
    // that vary by OEM/Android version. "list" returns titles+type; "play"
    // takes the numbered index back and plays it through the same STREAM_MUSIC
    // MediaPlayer path as playRingtoneUri() (so it follows the media volume
    // slider, not the separate ringer/notification volume). -------------------
    public HttpServer.ApiResponse ringtonesList(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        // 2026-08 更新 (修 bug): 改用 getCachedRingtoneManager() 不再每次
        // new RingtoneManager 用完即丟 —— 見 findRingtoneByTitle() 上面
        // 那個 cache function 的 javadoc, 這裡是同一種 cursor 洩漏, 一起修。
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.database.Cursor cursor = manager.getCursor();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"type\":\"" + MainActivity.jsonSafe(type) + "\",\"sounds\":[");
        int position = 0;
        boolean first = true;
        while (cursor.moveToNext()) {
            String title = cursor.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX);
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"index\":").append(position).append(",\"title\":\"")
                    .append(MainActivity.jsonSafe(title == null ? "" : title)).append("\"}");
            position++;
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse ringtonesPlay(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        int index = ApiValidator.requireInt(query, "index");
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        // 2026-08 更新 (修 bug): 同上, 改用 cached manager。
        android.media.RingtoneManager manager = getCachedRingtoneManager(rmType);
        android.net.Uri uri;
        try {
            uri = manager.getRingtoneUri(index);
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"invalid index\"}");
        }
        if (uri == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
        }
        playRingtoneUri(uri);
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-08 新增: 用 title 查找鈴聲, 不再用 audio/ringtones/list 的 numbered
    // index (見上面 findRingtoneByTitle() 的 javadoc: cursor position 不保證
    // 跨機一致, 因為 RingtoneManager 內部排序邏輯不一定和 adb content query
    // 手動加 --sort 那個排序一樣)。Blockly 頁面現在內嵌一份靜態 title 清單
    // (由實機 adb content query 執行一次抓回來, 見 blockly-actions-data.js
    // 旁邊的 blockly-ringtone-data.js), 選了 title 直接送這個 API, 沿用
    // findRingtoneByTitle() 這個已經被 playStopCue()/playShutterCue() 使用、
    // 驗證過穩健的「查 title 轉 Uri」機制, 完全不用理會 index 排序這個問題。
    public HttpServer.ApiResponse ringtonesPlayByTitle(Map<String, String> query) {
        String type = ApiValidator.optionalRingtoneType(query);
        String title = ApiValidator.require(query, "title");
        int rmType = "notification".equals(type)
                ? android.media.RingtoneManager.TYPE_NOTIFICATION
                : android.media.RingtoneManager.TYPE_RINGTONE;
        android.net.Uri uri = findRingtoneByTitle(title, rmType);
        if (uri == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"sound not found\"}");
        }
        playRingtoneUri(uri);
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-08 新增: 停止目前正在播放的系統鈴聲/通知聲 (play / play_by_title 兩個
    // endpoint 播放的那個), 對應 Blockly「範例 5」的「停止播放」按鈕。
    public HttpServer.ApiResponse ringtonesStop() {
        stopRingtonePlayback();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }
}
