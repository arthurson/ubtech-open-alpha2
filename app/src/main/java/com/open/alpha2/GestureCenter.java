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
    // 純 Java click 模型（HeadKeyPoller 直讀 /dev/input/event0）：撳落去即送
    // 成個 raw (down,up) click（相隔 <10ms），放手冇 signal、hold 睇唔到。
    // tap 照行一格音量；hold/repeat 冇（driver 冇報）；雙鍵經 600ms 配對窗
    // 合成（見 HeadKeyPoller.DOUBLE_WINDOW_MS），唔用雙擊窗
    // 延遲單鍵——交替撳-/+ 微調音量撞入窗會誤觸總停，窗大小喺該檔調。
    // HeadKeyPoller 經 Listener 直連做動作（音量／燈／總停），唔等 EventBus；
    // 同時照舊格式轉送 EventBus "gesture"（compound direction=(code<<8)|0x01，
    // 即 23041/23297/…，同已移除嘅 alpha2services 發嘅 broadcast 同值），
    // 供 WebSocket log／出面監聽者分辨 90撳落／91放開（唔係同一個 code 出 0/1）。
    public void start() {
        // head_key 照旧转送 EventBus，供 WebSocket log 备查。
        headKeyPoller.setListener(new HeadKeyPoller.Listener() {
            @Override public void onGesture(int eventCode) {
                EventBus.get().publish("gesture",
                        "{\"direction\":" + ((eventCode << 8) | 0x01) + "}");
                mainHandler.post(() -> onGestureCode(eventCode));
            }
            @Override public void onHeadKey(int code, int value) {
                EventBus.get().publish("head_key", "{\"code\":" + code + ",\"value\":" + value + "}");
            }
        });
        try { headKeyPoller.start(); } catch (Throwable t) { Log.w(TAG, "headKeyPoller start failed", t); }
    }
    /**
     * Reacts to the head touch-pad "gestures" from {@link HeadKeyPoller}
     * (pure-Java /dev/input/event0 poll; the old stock
     * {@code come.ubt.alpha2.gesture} broadcast carried the same values and is
     * gone with alpha2services, so we re-emit the identical compound
     * {@code (eventCode << 8) | 0x01} on the EventBus "gesture" topic).
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
            case 0x5a: // "-" pressed：即行一格先，repeat 跟著排（click 模型
                // 無 hold，repeat 多數即刻被放手停，僅行到這一格）。
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
                       // 純 Java 由 HeadKeyPoller 600ms 配對窗合成（兩粒先後腳
                       // 齊撳；單撳交替太密會誤觸，窗大小喺該檔調）。
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
    /** 雙鍵總停（HeadKeyPoller 配對窗合成嘅 0x5e 先到）。 */
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
        // 正常經 0x5f 雙放開熄燈；1.5s 後自動熄滅係 failsafe（唔會卡死）。
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                ledCenter.setPadMinusHeld(false);
                ledCenter.setPadPlusHeld(false);
                ledCenter.padLedUpdate();
            }
        }, 1500);
    }

    /** 即行一格音量（tap 靠這一下，不靠 repeat）。行完即更頭燈綠色音量計。 */
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
     * 這裡僅排之後的 repeat（driver 無 hold，多數即刻被放手停；留下是為了
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



