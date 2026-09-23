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

    /** onDestroy 共用：停連發＋下來個 poller。 */
    public void shutdown() {
        stopVolumeRepeat();
        headKeyPoller.setListener(null);
        try { headKeyPoller.stop(); } catch (Throwable ignored) {}
    }

    private final HeadKeyPoller headKeyPoller = new HeadKeyPoller();
    private Runnable volumeRepeater;
    private AudioManager audioManager;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
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
                mainHandler.post(() -> onGestureCode(eventCode));
            }
            @Override public void onHeadKey(int code, int value) {
                EventBus.get().publish("head_key", "{\"code\":" + code + ",\"value\":" + value + "}");
            }
            @Override public void onHeadKeyNative(int code) {
                EventBus.get().publish("head_key_native", "{\"code\":" + code + "}");
            }
        });
        try { headKeyPoller.start(); } catch (Throwable t) { Log.w(TAG, "headKeyPoller start failed", t); }
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
     * radio - see stopAllSpeechPlayback()/onGestureCode()'s 0x5e case), matching the
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
    /** 雙鍵總停（僅 synthetic 0x5e＋raw 雙按狀態先到）。 */
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
        // click 模型沒有放手 signal 熄燈，1.5s 後自動熄滅（不會卡死）。
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
            }
        }, 1500);
    }

    /** 即行一格音量（click 模型：tap 靠這一下，不靠 repeat）。行完即更頭燈綠色音量計。 */
    private void stepVolume(boolean up) {
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
            ledCenter.showVolumeMeter();
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
     * FLAG_PLAY_SOUND makes Android play its own built-in volume-change sound on each
     * real step - the same sound a hardware volume key produces - so there's no need
     * for a separately synthesized beep here; it only actually sounds on ticks where
     * the stream truly moved (Android itself no-ops silently once at min/max).
     */
    private void startVolumeRepeat(boolean up) {
        stopVolumeRepeat();
        volumeRepeater = new Runnable() {
            @Override
            public void run() {
                if (audioManager != null) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                    ledCenter.showVolumeMeter();
                }
                mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(volumeRepeater, VOLUME_REPEAT_INTERVAL_MS);
    }
    private void stopVolumeRepeat() {
        if (volumeRepeater != null) {
            mainHandler.removeCallbacks(volumeRepeater);
            volumeRepeater = null;
        }
    }
}



