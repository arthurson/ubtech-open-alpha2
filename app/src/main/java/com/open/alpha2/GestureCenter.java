package com.open.alpha2;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HeadKeyPoller;

import java.util.Map;

/**
 * 頭頂 +/- pad 手勢包：pad 事件接線、press/hold 音量連發、雙鍵總停。
 *
 * 2026-09 由 MainActivity 整包搬出（物理手勢本體；之前凍結因為要人手驗，
 * sendevent 直打 /dev/input/event0 已驗明：0x5a/0x5b 音量落/停、
 * 0x5e 總停（停歌）/0x5f 無嘢，見下面 onGestureCode）。
 * 邏輯一字不改搬過嚟（機械改寫只限：audioManager 經 appContext、
 * stopAllSpeechPlayback 經下面 Host）。
 * 擁有關係：
 * - MainActivity 只留：接線（onCreate 建構＋start、onDestroy shutdown()）
 *   同 Host 實現（0x5e 總停鍵轉交 SpeechCenter，TTS orchestration 已搬）。
 * - Pad 燈本身早已喺 LedCenter；提示音喺 RingtoneCenter；動作/音樂/電台
 *   經傳入嘅同一個 ActionDirect/AudioCenter。
 */
public final class GestureCenter {
    private static final String TAG = "GestureCenter";

    /**
     * 宿主縫：0x5e 雙鍵總停鍵要停埋 TTS（正本喺 SpeechCenter，MainActivity 轉交）。
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

    /** onDestroy 共用：停連發＋落嚟個 poller（原 onDestroy 三行，顺序提前咗幾行，無行為差別）。 */
    public void shutdown() {
        stopVolumeRepeat();
        headKeyPoller.setListener(null);
        try { headKeyPoller.stop(); } catch (Throwable ignored) {}
    }

    private final HeadKeyPoller headKeyPoller = new HeadKeyPoller();
    private Runnable volumeRepeater;
    private AudioManager audioManager;
    private static final long VOLUME_REPEAT_INTERVAL_MS = 300;
    // HeadKeyPoller 直讀 /dev/input/event0（原 onCreate 起嗰段一併搬入）。
    public void start() {
        // HeadKeyPoller 已搬入 hardware-direct module：经 Listener 直连，
        // 不再绕 EventBus "gesture" 事件（旧 direction 解析一并删除）。
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
            case 0x5a: // "-" pressed: start repeating volume-down
                ledCenter.setPadMinusHeld(true);
                ledCenter.padLedUpdate();
                startVolumeRepeat(false);
                break;
            case 0x5b: // "-" released
                ledCenter.setPadMinusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5c: // "+" pressed: start repeating volume-up
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                startVolumeRepeat(true);
                break;
            case 0x5d: // "+" released
                ledCenter.setPadPlusHeld(false);
                stopVolumeRepeat();
                ledCenter.padLedUpdate();
                break;
            case 0x5e: // both pressed (raw gesture code 94, decimal) - 全部停止:
                       // 用戶要求將總停鍵的效果搬到這顆實體鍵上, 之前這裡只有
                       // action_StopAction(), 現在跟小智面板那顆「⏹ 全部停止」
                       // 按鈕 (xiaozhiStopAll(), 見 app-xiaozhi.js) 看齊, 一次
                       // 停止動作/小智說話/本地音樂/電台這四樣東西。
                ledCenter.setPadMinusHeld(true);
                ledCenter.setPadPlusHeld(true);
                ledCenter.padLedUpdate();
                stopVolumeRepeat(); // in case one pad was already held down
                ringtoneCenter.playStopCue(); // distinct "stop" cue - must track STREAM_MUSIC volume
                // pure-direct：一键全停（动作截停+蹲下站起回位，含拍头双 pad 触发），
                // 与 HTTP action/stop 同语义。旧 robot.action_* 已无服务承载。
                actionDirect.stopActionWithRecovery();
                host.stopAllSpeech();
                audioCenter.stopLocalMusicPlayback();
                audioCenter.stopRadioPlayback();
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
    // (本地音樂停止/電台播放器搬咗去 AudioCenter。)
    // (查表快取搬咗去 RingtoneCenter，連上面成段 cursor 洩漏註解一齊。)
    /**
     * Starts (or restarts) a repeating volume step every VOLUME_REPEAT_INTERVAL_MS,
     * simulating press-and-hold behaviour on top of AudioManager's single-step API.
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
                }
                mainHandler.postDelayed(this, VOLUME_REPEAT_INTERVAL_MS);
            }
        };
        mainHandler.post(volumeRepeater);
    }
    private void stopVolumeRepeat() {
        if (volumeRepeater != null) {
            mainHandler.removeCallbacks(volumeRepeater);
            volumeRepeater = null;
        }
    }
}
