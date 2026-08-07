package com.ubtechinc.lynxrobot;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.ubtechinc.alpha.serverlibutil.aidl.ActionInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorMoveAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorReadAngleListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorSetAllAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IPcmListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemotePIRSensorOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechAsrListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechWakeUpListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ITtsCallBackListener;
import com.ubtechinc.alpha.serverlibutil.aidl.LedInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorAngle;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice;
import com.ubtechinc.lynxrobot.constant.UbxErrorCode;

import java.util.List;

/**
 * Standalone, self-contained reference for every subsystem exposed by
 * {@link LynxRobotApi}. This class is not wired into any Activity lifecycle - copy
 * whichever method body you need into your own app. Every method below corresponds to
 * a section in {@code docs/AIDL_GUIDE.md}; read that file for the full explanation of
 * each parameter and callback.
 *
 * <p>None of these calls block: each one returns almost immediately (after handing the
 * request to the robot's Binder), and the actual result/data arrives later via the
 * listener you pass in - usually on a Binder thread, not your calling thread. If you
 * need to touch UI from a listener callback, dispatch back to the main thread yourself
 * (e.g. {@code Activity.runOnUiThread(...)} or a {@code Handler} bound to the main
 * {@code Looper}).
 */
public final class SampleUsage {

    private static final String TAG = "SampleUsage";

    private final LynxRobotApi robot;

    public SampleUsage(Context context) {
        this.robot = new LynxRobotApi(context);
    }

    // ==================================================================
    // Action ("action")
    // ==================================================================

    /** Fetches the robot's action list and logs each entry. */
    public void demoGetActionList() {
        UbxErrorCode.API_ERROR_CODE dispatchResult = robot.action_getActionList(
                new IActionListResultListener.Stub() {
                    @Override
                    public void onGetActionList(int code, int total, ActionInfo[] actions) {
                        Log.i(TAG, "getActionList code=" + code + " total=" + total);
                        if (actions != null) {
                            for (ActionInfo a : actions) {
                                Log.i(TAG, "  action id=" + a.getId()
                                        + " name=" + a.getName()
                                        + " cnName=" + a.getCnName()
                                        + " durationMs=" + a.getDuration());
                            }
                        }
                    }
                });
        Log.i(TAG, "action_getActionList dispatch -> " + dispatchResult);
    }

    /** Plays a named action and logs progress/stop callbacks. */
    public void demoPlayAction(String actionName) {
        robot.action_playAction(actionName, new IActionResultListener.Stub() {
            @Override
            public void onPlayActionResult(int code, int progress) {
                Log.i(TAG, "playAction progress: code=" + code + " progress=" + progress);
            }

            @Override
            public void onStopActionResult(int code) {
                Log.i(TAG, "playAction stopped: code=" + code);
            }
        });
    }

    /** Stops whatever action is currently playing. */
    public void demoStopAction() {
        robot.action_stopAction(new IActionResultListener.Stub() {
            @Override
            public void onPlayActionResult(int code, int progress) {
                // Not expected on this listener when only stopping, but the AIDL
                // interface requires both methods to be implemented.
            }

            @Override
            public void onStopActionResult(int code) {
                Log.i(TAG, "stopAction confirmed: code=" + code);
            }
        });
    }

    // ==================================================================
    // Motor ("motor")
    // ==================================================================

    /** Fetches every motor's static specs (id, angle range, speed, torque). */
    public void demoGetMotorList() {
        robot.motor_getMotorList(new IMotorListResultListener.Stub() {
            @Override
            public void onGetMotorList(int code, int total, MotorInfo[] motors) {
                Log.i(TAG, "getMotorList code=" + code + " total=" + total);
                if (motors != null) {
                    for (MotorInfo m : motors) {
                        Log.i(TAG, "  motor id=" + m.getId()
                                + " range=[" + m.getLowerLimitAngle() + "," + m.getUpperLimitAngle() + "]"
                                + " speed=" + m.getRotatingSpeed()
                                + " torque=" + m.getTorque());
                    }
                }
            }
        });
    }

    /** Moves a single motor to an absolute target angle over {@code timeMs} milliseconds. */
    public void demoMoveToAbsoluteAngle(int motorId, int targetAngle, long timeMs) {
        robot.motor_moveToAbsoluteAngle(motorId, targetAngle, timeMs,
                new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int movedMotorId, int finalAngle, int code) {
                        Log.i(TAG, "motor " + movedMotorId + " reached " + finalAngle + " code=" + code);
                    }
                });
    }

    /** Moves a single motor by a relative offset from its current angle. */
    public void demoMoveRefAngle(int motorId, int deltaAngle, long timeMs) {
        robot.motor_moveRefAngle(motorId, deltaAngle, timeMs,
                new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int movedMotorId, int finalAngle, int code) {
                        Log.i(TAG, "motor " + movedMotorId + " moved by offset, now at "
                                + finalAngle + " code=" + code);
                    }
                });
    }

    /** Reads back a motor's current angle, optionally forcing a live hardware read. */
    public void demoReadAbsoluteAngle(int motorId, boolean fromHardware) {
        robot.motor_readAbsoluteAngle(motorId, fromHardware, new IMotorReadAngleListener.Stub() {
            @Override
            public void onReadMotorAngle(int readMotorId, int angle, int code) {
                Log.i(TAG, "motor " + readMotorId + " currently at " + angle + " code=" + code);
            }
        });
    }

    /** Moves several motors at once, all finishing together after {@code timeMs}. */
    public void demoSetAllMotorAbsoluteAngle() {
        MotorAngle[] targets = new MotorAngle[]{
                new MotorAngle(1, 90),
                new MotorAngle(2, 45),
                new MotorAngle(19, 0),  // head pan, per the robot's motor numbering
        };
        robot.motor_setAllMotorAbsoluteAngle(targets, 1000L,
                new IMotorSetAllAngleResultListener.Stub() {
                    @Override
                    public void onSetAllAngle(int code, int total) {
                        Log.i(TAG, "moved " + total + " motors together, code=" + code);
                    }
                });
    }

    /** Toggles the motors' power-save mode. No listener - fire and forget. */
    public void demoSetPowerSaveMode(boolean enabled) {
        robot.motor_setPowerSaveMode(enabled);
    }

    // ==================================================================
    // LED ("led")
    // ==================================================================

    private IRemoteLedOperationResultListener loggingLedListener(String label) {
        return new IRemoteLedOperationResultListener.Stub() {
            @Override
            public void onLedOpResult(int code, int extra) {
                Log.i(TAG, label + " code=" + code + " extra=" + extra);
            }
        };
    }

    /** Fetches every LED group's supported colors/effects. */
    public void demoGetLedList() {
        robot.led_getLedList(new IRemoteLedListResultListener.Stub() {
            @Override
            public void onGetLedList(int code, int total, List<LedInfo> leds) {
                Log.i(TAG, "getLedList code=" + code + " total=" + total);
                if (leds != null) {
                    for (LedInfo led : leds) {
                        Log.i(TAG, "  " + led);
                    }
                }
            }
        });
    }

    /** Turns the eye LEDs on with a solid color (1=RED, 2=GREEN, 3=BLUE, ...). */
    public void demoEyeSolidColor(int colorCode) {
        robot.led_turnOnEye(colorCode, loggingLedListener("turnOnEye"));
    }

    public void demoEyeOff() {
        robot.led_turnOffEye(loggingLedListener("turnOffEye"));
    }

    public void demoEyeBlink() {
        robot.led_turnOnEyeBlink(loggingLedListener("turnOnEyeBlink"));
    }

    /** Flash effect: exact meaning of the 4 int params is timing/color-dependent - see AIDL_GUIDE.md. */
    public void demoEyeFlash(int p0, int p1, int p2, int p3) {
        robot.led_turnOnEyeFlash(p0, p1, p2, p3, loggingLedListener("turnOnEyeFlash"));
    }

    public void demoHeadSolidColor(int colorCode, int brightness) {
        robot.led_turnOnHead(colorCode, brightness, loggingLedListener("turnOnHead"));
    }

    public void demoHeadOff() {
        robot.led_turnOffHead(loggingLedListener("turnOffHead"));
    }

    public void demoHeadBreath(int p0, int p1, int p2, int p3) {
        robot.led_turnOnHeadBreath(p0, p1, p2, p3, loggingLedListener("turnOnHeadBreath"));
    }

    public void demoMouthSolidColor(int colorCode) {
        robot.led_turnOnMouth(colorCode, loggingLedListener("turnOnMouth"));
    }

    public void demoMouthOff() {
        robot.led_turnOffMouth(loggingLedListener("turnOffMouth"));
    }

    public void demoWifiSolidColor(int colorCode) {
        robot.led_turnOnWifi(colorCode, loggingLedListener("turnOnWifi"));
    }

    public void demoWifiOff() {
        robot.led_turnOffWifi(loggingLedListener("turnOffWifi"));
    }

    public void demoChestOn() {
        robot.led_turnOnChestLed(loggingLedListener("turnOnChestLed"));
    }

    public void demoChestOff() {
        robot.led_turnOffChestLed(loggingLedListener("turnOffChestLed"));
    }

    // ==================================================================
    // Speech ("speech")
    // ==================================================================

    /** Registers to receive raw microphone PCM data. Call the matching unregister when done. */
    public void demoRegisterPcmListener(String key) {
        int result = robot.speech_registerPcmListener(key, new IPcmListener.Stub() {
            @Override
            public void onPcmData(byte[] data, int length) {
                // The first `length` bytes of `data` are raw PCM audio - forward it
                // to your own decoder/recorder here. Avoid heavy work on this callback thread.
            }
        });
        Log.i(TAG, "registerPcmListener -> " + result);
    }

    public void demoUnregisterPcmListener(String key) {
        int result = robot.speech_unregisterPcmListener(key);
        Log.i(TAG, "unregisterPcmListener -> " + result);
    }

    /** Registers to be notified when the robot's wake word is detected. */
    public void demoRegisterWakeUpListener(String key) {
        robot.speech_registerWakeUpCallbackListener(key, new ISpeechWakeUpListener.Stub() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "wake word detected");
            }

            @Override
            public void onError(int code, String message) {
                Log.e(TAG, "wake detection error: " + message + " (code=" + code + ")");
            }
        });
    }

    public void demoUnregisterWakeUpListener(String key) {
        robot.speech_unregisterWakeUpCallbackListener(key);
    }

    /** Plays a piece of text as TTS using the given voice name. */
    public void demoSpeak(String text, String voiceName) {
        robot.speech_onPlayCallback(text, voiceName, new ITtsCallBackListener.Stub() {
            @Override
            public void onBegin() {
                Log.i(TAG, "TTS started");
            }

            @Override
            public void onEnd() {
                Log.i(TAG, "TTS finished");
            }
        });
    }

    public void demoStopSpeaking() {
        robot.speech_onStopPlay();
    }

    public void demoSetVoiceName(String voiceName) {
        robot.speech_setVoiceName(voiceName);
    }

    public void demoSetTtsSpeedAndVolume(String speed, String volume) {
        robot.speech_setTtsSpeed(speed);
        robot.speech_setTtsVolume(volume);
        Log.i(TAG, "speed now " + robot.speech_getTtsSpeed() + ", volume now " + robot.speech_getTtsVolume());
    }

    /** Starts ASR (speech-to-text) and logs the recognised text. */
    public void demoStartSpeechAsr(String key) {
        robot.speech_startSpeechAsr(key, 0, new ISpeechAsrListener.Stub() {
            @Override
            public void onBegin() {
                Log.i(TAG, "asr listening...");
            }

            @Override
            public void onEnd() {
                Log.i(TAG, "asr done listening");
            }

            @Override
            public void onResult(String text) {
                Log.i(TAG, "asr heard: " + text);
            }

            @Override
            public void onError(int code) {
                Log.e(TAG, "asr error " + code);
            }
        });
    }

    public void demoStopSpeechAsr() {
        robot.speech_stopSpeechAsr();
    }

    /** Lists every installed TTS voice and logs the currently active one. */
    public void demoListVoices() {
        List<SpeechVoice> voices = robot.speech_getSpeechVoices();
        if (voices != null) {
            for (SpeechVoice v : voices) {
                Log.i(TAG, "voice: " + v);
            }
        }
        SpeechVoice current = robot.speech_getCurSpeechVoices();
        Log.i(TAG, "current voice: " + current);
    }

    public void demoToggleWakeup(boolean enabled) {
        robot.speech_switchWakeup(enabled);
    }

    public void demoRecording() {
        robot.speech_startRecording();
        // ... do something while the robot records ...
        robot.speech_stopRecording();
    }

    // ==================================================================
    // Sys ("sysinfo")
    // ==================================================================

    /** Logs every synchronous system/version getter in one go. */
    public void demoSysInfo() {
        Log.i(TAG, "sid=" + robot.sys_getSid());
        Log.i(TAG, "micVersion=" + robot.sys_getMICVersion());
        Log.i(TAG, "chestVersion=" + robot.sys_getChestVersion());
        Log.i(TAG, "headVersion=" + robot.sys_getHeadVersion());
        Log.i(TAG, "batteryVersion=" + robot.sys_getBatteryVersion());
        Log.i(TAG, "isCharging=" + robot.sys_isPowerCharging());
        Log.i(TAG, "powerValue=" + robot.sys_getPowerValue());
    }

    /** Reads every stored alarm and logs it. */
    public void demoQueryAllAlarms() {
        AlarmInfo[] alarms = robot.sys_queryAllAlarm("");
        if (alarms != null) {
            for (AlarmInfo a : alarms) {
                Log.i(TAG, "alarm at " + a.hh + ":" + a.mm
                        + " enabled=" + a.isUseAble
                        + " startAction=" + a.actionStartName);
            }
        }
    }

    /** Inserts a new alarm. AlarmInfo's fields are all public - set them directly. */
    public void demoInsertAlarm() {
        AlarmInfo alarm = new AlarmInfo();
        alarm.hh = 7;
        alarm.mm = 30;
        alarm.ss = 0;
        alarm.isUseAble = true;
        alarm.actionStartName = "wake_up_dance";
        alarm.label = "Morning alarm";
        Integer result = robot.sys_insertAlarm(alarm);
        Log.i(TAG, "insertAlarm -> " + result);
    }

    public void demoUpgradeMode() {
        robot.sys_enterUpgradeMode();
        // ... perform upgrade-related work ...
        robot.sys_exitUpgradeMode();
    }

    public void demoStartApp(String contentUri) {
        robot.sys_startApp(Uri.parse(contentUri));
    }

    /** Toggles the PIR (human-presence) sensor. */
    public void demoSetPirSensor(boolean enabled) {
        robot.sys_setPIRSensor(enabled, new IRemotePIRSensorOperationResultListener.Stub() {
            @Override
            public void onPIRSensorOpResult(int code) {
                Log.i(TAG, "PIR sensor toggled, code=" + code);
            }
        });
    }
}
