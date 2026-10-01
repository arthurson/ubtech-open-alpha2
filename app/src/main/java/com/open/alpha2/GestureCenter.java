package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HeadKeyPoller;


/**
 * 頭頂 +/- pad 手勢包：pad 事件接線、press/hold 音量連發、雙鍵總停。
 *
 * sendevent 直打 /dev/input/event0 已驗明：0x5a/0x5b 音量落/停、
 * 0x5e 總停（停歌）/0x5f 無東西，見下面 onGestureCode。
 * 擁有關係：
 * - MainActivity 只留：接線（onCreate 建構＋start、onDestroy shutdown()）
 *   同 Host 實現（0x5e 總停鍵轉交 SpeechCenter，TTS orchestration 已搬）。
 * - Pad 燈本身早已在 LedCenter；提示音在 RingtoneCenter；動作/音樂/電台
 *   經傳入的同一個 ActionDirect/AudioCenter。
 */
public final class GestureCenter {
    private static final String TAG = "GestureCenter";

    /**
     * 宿主縫：0x5e 雙鍵總停鍵要一併停止 TTS（正本在 SpeechCenter，MainActivity 轉交）。
     */
    public interface Host {
        void stopAllSpeech();
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final LedCenter ledCenter;
    private final RingtoneCenter ringtoneCenter;
    private final ActionDirect actionDirect;
    private final AudioCenter audioCenter;
    private final Host host;

    public GestureCenter(Context context, Handler mainHandler, LedCenter ledCenter,
            RingtoneCenter ringtoneCenter, ActionDirect actionDirect, AudioCenter audioCenter,
            Host host) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.ledCenter = ledCenter;
        this.ringtoneCenter = ringtoneCenter;
        this.actionDirect = actionDirect;
        this.audioCenter = audioCenter;
        this.host = host;
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /** onDestroy 共用：停連發＋放 tick＋下來個 poller。 */
    public void shutdown() {
        stopVolumeRepeat();
        if (tickPool != null) {
            try { tickPool.release(); } catch (Throwable ignore) {}
            tickPool = null;
            tickSoundId = 0;
        }
        headKeyPoller.setListener(null);
        try { headKeyPoller.stop(); } catch (Throwable ignored) {}
    }

    private final HeadKeyPoller headKeyPoller = new HeadKeyPoller();
    private Runnable volumeRepeater;
    private AudioManager audioManager;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
    // 連發世代＋上限：release（0x5b/0x5d）係停連發嘅唯一開關——跌咗（native
    // state stuck 冇報放手／舊 instance 殘留鏈／stop 同 run 緊撞期），條鏈
    // 就會無限跑，要 reboot 先停。世代：每次 start/stop 即+1，舊 runnable
    // 對唔上即收工；上限：300ms 一格，20格＝6秒封頂（正常 hold 鬆手早過
    // 6秒，無感；㩒足6秒唔放，停一停再撳過就得）。兩樣都係main thread
    // 專用（start/stop/run 全經 mainHandler 排）。
    private int volumeRepeatGen = 0;
    private static final int VOLUME_REPEAT_MAX_TICKS = 20;
    // 每格音量 tick 聲：系統 Effect_Tick.ogg（硬件音量掣同一粒聲），經 SoundPool
    //播（低延遲，唔使為咗 80ms 嘅 blip 開成個 MediaPlayer）。跟 STREAM_MUSIC
    // 即時音量比例播——較細聲嗰陣 tick 細聲，靜音嗰陣唔響，同系統行為一致。
    // FLAG_PLAY_SOUND 只係 tick 未 load 好之前嘅後備（第一下可能趕唔切）。
    private android.media.SoundPool tickPool;
    private int tickSoundId = 0;
    private static final String TICK_PATH = "/system/media/audio/ui/Effect_Tick.ogg";
    // native 長命時 hold/release 語意是真（press→hold→release），
    // 下面 press 即行一格＋repeat、release 即停，剛剛好。跌落 Java poll 後備
    // 才是 click 模型（按即整個 down+up、放手沒有聲），當時 tap 照行一格，
    // hold/repeat 沒有得弄（driver 沒有報）。
    // 不用雙擊窗：交替試按鈕會誤觸播 squat。雙鍵總停行面板按鈕／小智按鈕；synthetic 0x5e 到不到都不估。
    // HeadKeyPoller 直讀 /dev/input/event0。
    public void start() {
        // HeadKeyPoller 經 Listener 直連，不經 EventBus "gesture" 事件。
        // head_key/head_key_native 照旧转送 EventBus，供 WebSocket log 备查。
        headKeyPoller.setListener(new HeadKeyPoller.Listener() {
            @Override public void onGesture(int eventCode) {
                // 0x5a-0x5d（音量＋燈）經回調線程直行——全部落手都係
                // volatile 寫／post 去單線程／binder call，線程安全，唔使經
                // main 排隊等塞車；0x5e 總停重（動作＋TTS＋播歌），留喺 main。
                // 0x5f 淨熄燈，直行。
                if (eventCode == 0x5e) mainHandler.post(() -> onGestureCode(eventCode));
                else onGestureCode(eventCode);
            }
            @Override public void onHeadKeyNative(int code) {
                EventBus.get().publish("head_key_native", "{\"code\":" + code + "}");
            }
        });
        try { headKeyPoller.start(); } catch (Throwable t) { Log.w(TAG, "headKeyPoller start failed", t); }
        ensureTickLoaded();
    }
    /**
     * Reacts to the head touch-pad "gestures" broadcast via {@code come.ubt.alpha2.gesture}.
     *
     * These are NOT documented in the SDK (docs/sensors-and-events.md only lists the raw
     * `come.ubt.alpha2.gesture` action/extra name, not what values it carries) - the values
     * below were captured from a real robot's WebSocket event log:
     *
     *   "-" pad pressed  -> 23041 (0x5a01)      "-" pad released -> 23297 (0x5b01)
     *   "+" pad pressed  -> 23553 (0x5c01)      "+" pad released -> 23809 (0x5d01)
     *   both pressed     -> 24065 (0x5e01, high byte 94 decimal)   both released -> 24321 (0x5f01)
     *
     * Every value's low byte is 0x01; the high byte (0x5a-0x5f, 90-95) is a distinct,
     * sequential event code for each of the 6 press/release combinations - i.e. this
     * extra carries a compound (eventCode << 8 | 0x01) value here, not the plain
     * "direction" the field name suggests. Mapped to: "-"/"+" press-and-hold repeats
     * volume down/up every VOLUME_REPEAT_INTERVAL_MS until release; pressing both (high
     * byte 94, decimal) triggers a full stop-everything (action/speech/local music/
     * radio - see stopAllViaPads()/onGestureCode()'s 0x5e case), matching the
     * XiaoZhi panel's "⏹ 全部停止" button; releasing both does nothing extra.
     */
    private void onGestureCode(int code) {
        switch (code) {
            case 0x5a: // "-" pressed：即行一格先，repeat 跟著排；
                // native 長命當時 release 先停（正常 hold）；poll 後備 click
                // 當時 release 8ms 後就到，repeat 即停，僅行到這一格。
                ledCenter.setPadMinusHeld(true);
                ledCenter.padLedUpdate();
                stepVolume(false);
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                ledCenter.setPadMinusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5c: // "+" pressed：同上
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                stepVolume(true);
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                ledCenter.setPadPlusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5e: // both pressed (raw gesture code 94, decimal) - 全部停止:
                       // 跟小智面板那顆「⏹ 全部停止」
                       // 按鈕 (xiaozhiStopAll(), 見 app-xiaozhi.js) 看齊, 一次
                       // 停止動作/小智說話/本地音樂/電台這四樣東西。
                       // （click 模型下這個 case 只靠 synthetic 0x5e＋raw 雙按
                       // 狀態，實測未見過，自己不會亂開火；雙擊窗已刪，見上。）
                stopAllViaPads();
                break;
            case 0x5f: // both released: nothing further to do
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
                break;
            default:
                // Unknown gesture code - not one of the 6 confirmed above; ignore.
                break;
        }
    }
    /** 雙鍵總停（僅 synthetic 0x5e＋raw 雙按狀態先到；重複 0x5e 由 HeadKeyPoller 擋）。 */
    private void stopAllViaPads() {
        ledCenter.setPadMinusHeld(true);
        ledCenter.setPadPlusHeld(true);
        ledCenter.padLedUpdate();
        stopVolumeRepeat(); // in case one pad was already held down
        ringtoneCenter.playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
        // pure-direct：一键全停（动作截停+蹲下站起回位，含拍头双 pad 触发），
        // 与 HTTP action/stop 同语义。
        actionDirect.stopActionWithRecovery();
        host.stopAllSpeech();
        audioCenter.stopLocalMusicPlayback();
        audioCenter.stopRadioPlayback();
        // 冇 0x5f 到就要靠呢個 failsafe 熄燈；但期間有新撳掣（世代郁咗）就
        // 收手——人哋㩒住緊嗰粒唔熄得（epoch 見 LedCenter）。
        final int epoch = ledCenter.getPadHeldEpoch();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (ledCenter.getPadHeldEpoch() != epoch) return;
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
            }
        }, 1500);
    }

    /** 即行一格音量（tap 靠這一下，不靠 repeat）。行完即更頭燈綠色音量計＋tick 一聲。 */
    private void stepVolume(boolean up) {
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI | (tickReady() ? 0 : AudioManager.FLAG_PLAY_SOUND));
            playTick();
            ledCenter.showVolumeMeter();
        }
    }

    /** SoundPool 預載（start() 做一次；load 係非同步，頭幾下趕唔切就靜）。 */
    private void ensureTickLoaded() {
        if (tickPool != null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                tickPool = new android.media.SoundPool.Builder()
                        .setMaxStreams(1)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .build();
            } else {
                tickPool = new android.media.SoundPool(1, AudioManager.STREAM_MUSIC, 0);
            }
            tickSoundId = tickPool.load(TICK_PATH, 1);
        } catch (Throwable t) {
            Log.w(TAG, "tick load failed", t);
            tickPool = null;
            tickSoundId = 0;
        }
    }

    private boolean tickReady() {
        return tickPool != null && tickSoundId != 0;
    }

    /** 跟即時音量比例 tick 一聲；未 load 好／靜音就唔出聲（唔抛）。 */
    private void playTick() {
        try {
            if (!tickReady() || audioManager == null) return;
            int level = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (level <= 0 || max <= 0) return;
            float v = Math.max(0f, Math.min(1f, (float) level / max));
            tickPool.play(tickSoundId, v, v, 1, 0, 1.0f);
        } catch (Throwable ignore) {
        }
    }
    /**
     * Starts (or restarts) a repeating volume step every VOLUME_REPEAT_INTERVAL_MS,
     * simulating press-and-hold behaviour on top of AudioManager's single-step API.
     *
     * click 模型：第一格由 stepVolume() 即行（見 0x5a/0x5c），
     * 這裡僅排之後的 repeat（release 8ms 後就到，多數即刻停；留下是為了
     * 萬一有 firmware 真是報 hold）。
     *
     * FLAG_PLAY_SOUND 只係 tick 未 load 好之前嘅後備（見 stepVolume）；頂格／底格
     * 嗰陣 Android 自己靜默（行唔郁就唔響），唔使另外處理。
     */
    private void startVolumeRepeat(boolean up) {
        stopVolumeRepeat(); // 先停舊鏈（世代+1 殺舊 runnable＋清場）
        final int gen = volumeRepeatGen; // 領養新世代（唔再+1，唔係每次 start 跳兩級）
        final int[] ticks = new int[1];
        volumeRepeater = new Runnable() {
            @Override
            public void run() {
                if (gen != volumeRepeatGen) return; // 舊世代（已停／被新鏈取代）
                if (++ticks[0] > VOLUME_REPEAT_MAX_TICKS) { stopVolumeRepeat(); return; }
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI | (tickReady() ? 0 : AudioManager.FLAG_PLAY_SOUND));
                    playTick();
                    ledCenter.showVolumeMeter();
                }
                if (gen != volumeRepeatGen) return; // 做緊嗰陣 stop 嚟過：唔續排
                mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(volumeRepeater, VOLUME_REPEAT_INTERVAL_MS);
    }
    private void stopVolumeRepeat() {
        volumeRepeatGen++; // 先閂世代：排緊／行緊嘅舊 runnable 下次驗即停
        if (volumeRepeater != null) {
            mainHandler.removeCallbacks(volumeRepeater);
            volumeRepeater = null;
        }
    }
}



