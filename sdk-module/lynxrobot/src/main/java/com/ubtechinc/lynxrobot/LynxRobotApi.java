package com.ubtechinc.lynxrobot;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.ubtechinc.alpha.serverlibutil.aidl.IActionListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionService;
import com.ubtechinc.alpha.serverlibutil.aidl.ILedInterface;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorInterface;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorMoveAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorReadAngleListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorSetAllAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechInterface;
import com.ubtechinc.alpha.serverlibutil.aidl.ISysService;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorAngle;
import com.ubtechinc.lynxrobot.constant.UbxErrorCode;

/**
 * Single entry point of the Alpha2 open SDK, rebuilt against
 * com.ubtechinc.alpha2services_base.3.002.apk's actual AIDL surface
 * ({@code com.ubtechinc.alpha.serverlibutil.aidl}).
 *
 * <p>Method names mirror the underlying AIDL method names, grouped by subsystem prefix
 * ({@code action_}, {@code motor_}, {@code led_}, {@code speech_}, {@code sys_}) so the
 * mapping to the on-robot service is always obvious. Each subsystem's binder is fetched
 * lazily (and re-fetched if the robot's process restarts) through {@link ServiceFetcher}.
 *
 * <p>Every method returns {@link UbxErrorCode.API_ERROR_CODE#API_ERROR_SUCCEED} once the
 * call has been forwarded to the robot, {@link UbxErrorCode.API_ERROR_CODE#API_ERROR_NOT_INIT}
 * if the matching service's binder isn't available (robot's system app not running / not
 * yet ready), or {@link UbxErrorCode.API_ERROR_CODE#API_ERROR_FAILED} if the AIDL call
 * itself threw a {@link RemoteException}. Results/data are always delivered
 * asynchronously via the listener you pass in - these calls do not block.
 */
public class LynxRobotApi {

    private static final String TAG = "LynxRobotApi";

    private final Context context;
    private final ServiceFetcher serviceFetcher;

    private IActionService actionService;
    private IMotorInterface motorService;
    private ILedInterface ledService;
    private ISpeechInterface speechService;
    private ISysService sysService;

    public LynxRobotApi(Context context) {
        this.context = context.getApplicationContext();
        this.serviceFetcher = ServiceFetcher.get(this.context);
    }

    // ------------------------------------------------------------------
    // Service binder resolution (lazy, with re-fetch if the binder is gone)
    // ------------------------------------------------------------------

    private IActionService action() {
        if (actionService == null) {
            IBinder b = serviceFetcher.getServiceBinder(ServiceFetcher.SERVICE_ACTION);
            if (b != null) {
                actionService = IActionService.Stub.asInterface(b);
            }
        }
        return actionService;
    }

    private IMotorInterface motor() {
        if (motorService == null) {
            IBinder b = serviceFetcher.getServiceBinder(ServiceFetcher.SERVICE_MOTOR);
            if (b != null) {
                motorService = IMotorInterface.Stub.asInterface(b);
            }
        }
        return motorService;
    }

    private ILedInterface led() {
        if (ledService == null) {
            IBinder b = serviceFetcher.getServiceBinder(ServiceFetcher.SERVICE_LED);
            if (b != null) {
                ledService = ILedInterface.Stub.asInterface(b);
            }
        }
        return ledService;
    }

    private ISpeechInterface speech() {
        if (speechService == null) {
            IBinder b = serviceFetcher.getServiceBinder(ServiceFetcher.SERVICE_SPEECH);
            if (b != null) {
                speechService = ISpeechInterface.Stub.asInterface(b);
            }
        }
        return speechService;
    }

    private ISysService sys() {
        if (sysService == null) {
            IBinder b = serviceFetcher.getServiceBinder(ServiceFetcher.SERVICE_SYSINFO);
            if (b != null) {
                sysService = ISysService.Stub.asInterface(b);
            }
        }
        return sysService;
    }

    // ==================================================================
    // IActionService ("action")
    // ==================================================================

    public UbxErrorCode.API_ERROR_CODE action_getActionList(IActionListResultListener listener) {
        IActionService svc = action();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.getActionList(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "action_getActionList failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE action_playAction(String actionName, IActionResultListener listener) {
        IActionService svc = action();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.playAction(actionName, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "action_playAction failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE action_playActionFile(String filePath, IActionResultListener listener) {
        IActionService svc = action();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.playActionFile(filePath, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "action_playActionFile failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE action_stopAction(IActionResultListener listener) {
        IActionService svc = action();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.stopAction(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "action_stopAction failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    // ==================================================================
    // IMotorInterface ("motor")
    // ==================================================================

    public UbxErrorCode.API_ERROR_CODE motor_getMotorList(IMotorListResultListener listener) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.getMotorList(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_getMotorList failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE motor_moveToAbsoluteAngle(int motorId, int angle, long timeMs, IMotorMoveAngleResultListener listener) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.moveToAbsoluteAngle(motorId, angle, timeMs, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_moveToAbsoluteAngle failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE motor_moveRefAngle(int motorId, int deltaAngle, long timeMs, IMotorMoveAngleResultListener listener) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.moveRefAngle(motorId, deltaAngle, timeMs, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_moveRefAngle failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE motor_readAbsoluteAngle(int motorId, boolean fromHardware, IMotorReadAngleListener listener) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.readAbsoluteAngle(motorId, fromHardware, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_readAbsoluteAngle failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE motor_setAllMotorAbsoluteAngle(MotorAngle[] angles, long timeMs, IMotorSetAllAngleResultListener listener) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.SetAllMotorAbsoluteAngle(angles, timeMs, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_setAllMotorAbsoluteAngle failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE motor_setPowerSaveMode(boolean enabled) {
        IMotorInterface svc = motor();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setPowerSaveMode(enabled);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "motor_setPowerSaveMode failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    // ==================================================================
    // ILedInterface ("led")
    // ==================================================================

    public UbxErrorCode.API_ERROR_CODE led_getLedList(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedListResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.getLedList(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_getLedList failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnEye(int colorCode, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnEye(colorCode, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnEye failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOffEye(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOffEye(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOffEye failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnEyeBlink(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnEyeBlink(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnEyeBlink failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnEyeFlash(int p0, int p1, int p2, int p3, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnEyeFlash(p0, p1, p2, p3, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnEyeFlash failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnEyeMarquee(int p0, int p1, int p2, int p3, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnEyeMarquee(p0, p1, p2, p3, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnEyeMarquee failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnHead(int p0, int p1, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnHead(p0, p1, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnHead failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOffHead(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOffHead(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOffHead failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnHeadFlash(int p0, int p1, int p2, int p3, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnHeadFlash(p0, p1, p2, p3, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnHeadFlash failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnHeadMarquee(int p0, int p1, int p2, int p3, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnHeadMarquee(p0, p1, p2, p3, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnHeadMarquee failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnHeadBreath(int p0, int p1, int p2, int p3, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnHeadBreath(p0, p1, p2, p3, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnHeadBreath failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnMouth(int p0, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnMouth(p0, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnMouth failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOffMouth(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOffMouth(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOffMouth failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnMouthBreath(int p0, int p1, int p2, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnMouthBreath(p0, p1, p2, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnMouthBreath failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnWifi(int p0, com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnWifi(p0, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnWifi failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOffWifi(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOffWifi(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOffWifi failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOnChestLed(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOnChestLed(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOnChestLed failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE led_turnOffChestLed(com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener listener) {
        ILedInterface svc = led();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.turnOffChestLed(listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "led_turnOffChestLed failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    // ==================================================================
    // ISpeechInterface ("speech")
    // ==================================================================

    public int speech_registerPcmListener(String key, com.ubtechinc.alpha.serverlibutil.aidl.IPcmListener listener) {
        ISpeechInterface svc = speech();
        if (svc == null) return -1;
        try {
            return svc.registerPcmListener(key, listener);
        } catch (RemoteException e) {
            Log.e(TAG, "speech_registerPcmListener failed", e);
            return -2;
        }
    }

    public int speech_unregisterPcmListener(String key) {
        ISpeechInterface svc = speech();
        if (svc == null) return -1;
        try {
            return svc.unregisterPcmListener(key);
        } catch (RemoteException e) {
            Log.e(TAG, "speech_unregisterPcmListener failed", e);
            return -2;
        }
    }

    public int speech_registerWakeUpCallbackListener(String key, com.ubtechinc.alpha.serverlibutil.aidl.ISpeechWakeUpListener listener) {
        ISpeechInterface svc = speech();
        if (svc == null) return -1;
        try {
            return svc.registerWakeUpCallbackListener(key, listener);
        } catch (RemoteException e) {
            Log.e(TAG, "speech_registerWakeUpCallbackListener failed", e);
            return -2;
        }
    }

    public int speech_unregisterWakeUpCallbackListener(String key) {
        ISpeechInterface svc = speech();
        if (svc == null) return -1;
        try {
            return svc.unregisterWakeUpCallbackListener(key);
        } catch (RemoteException e) {
            Log.e(TAG, "speech_unregisterWakeUpCallbackListener failed", e);
            return -2;
        }
    }

    public int speech_onPlayCallback(String text, String voiceName, com.ubtechinc.alpha.serverlibutil.aidl.ITtsCallBackListener listener) {
        ISpeechInterface svc = speech();
        if (svc == null) return -1;
        try {
            return svc.onPlayCallback(text, voiceName, listener);
        } catch (RemoteException e) {
            Log.e(TAG, "speech_onPlayCallback failed", e);
            return -2;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_onStopPlay() {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.onStopPlay();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_onStopPlay failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_setVoiceName(String voiceName) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setVoiceName(voiceName);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_setVoiceName failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_setTtsSpeed(String speed) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setTtsSpeed(speed);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_setTtsSpeed failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public String speech_getTtsSpeed() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return svc.getTtsSpeed();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_getTtsSpeed failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_setTtsVolume(String volume) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setTtsVolume(volume);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_setTtsVolume failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public String speech_getTtsVolume() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return svc.getTtsVolume();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_getTtsVolume failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_startSpeechAsr(String key, int mode, com.ubtechinc.alpha.serverlibutil.aidl.ISpeechAsrListener listener) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.startSpeechAsr(key, mode, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_startSpeechAsr failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_stopSpeechAsr() {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.stopSpeechAsr();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_stopSpeechAsr failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    /**
     * The underlying AIDL method returns a raw (untyped) {@code List} - confirmed
     * against com.ubtechinc.alpha2services_base.3.002.apk, where the Stub marshals
     * this with {@code writeList}/{@code readArrayList} rather than the typed-list
     * codegen ({@code writeTypedList}/{@code createTypedArrayList}) that a
     * {@code List<SpeechVoice>} AIDL declaration would produce. Every element the
     * robot puts in this list is in practice a {@link SpeechVoice}, so this method
     * does the unchecked cast for you.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice> speech_getSpeechVoices() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return (java.util.List<com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice>) svc.getSpeechVoices();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_getSpeechVoices failed", e);
            return null;
        }
    }

    public com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice speech_getCurSpeechVoices() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return svc.getCurSpeechVoices();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_getCurSpeechVoices failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_initSpeechGrammar(String grammar, com.ubtechinc.alpha.serverlibutil.aidl.ISpeechGrammarInitListener listener) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.initSpeechGrammar(grammar, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_initSpeechGrammar failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_switchSpeechCore(String core) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.switchSpeechCore(core);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_switchSpeechCore failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_switchWakeup(boolean enabled) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.switchWakeup(enabled);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_switchWakeup failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_startLocalFunction(String function) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.startLocalFunction(function);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_startLocalFunction failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public Boolean speech_isSpeechGrammar() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return svc.isSpeechGrammar();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_isSpeechGrammar failed", e);
            return null;
        }
    }

    public Boolean speech_isSpeechIat() {
        ISpeechInterface svc = speech();
        if (svc == null) return null;
        try {
            return svc.isSpeechIat();
        } catch (RemoteException e) {
            Log.e(TAG, "speech_isSpeechIat failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_setSpeechMode(int mode) {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setSpeechMode(mode);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_setSpeechMode failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_stopRecording() {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.stopRecording();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_stopRecording failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE speech_startRecording() {
        ISpeechInterface svc = speech();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.startRecording();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "speech_startRecording failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    // ==================================================================
    // ISysService ("sysinfo")
    // ==================================================================

    public String sys_getSid() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getSid();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getSid failed", e);
            return null;
        }
    }

    public String sys_getMICVersion() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getMICVersion();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getMICVersion failed", e);
            return null;
        }
    }

    public com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo[] sys_queryAllAlarm(String key) {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.queryAllAlarm(key);
        } catch (RemoteException e) {
            Log.e(TAG, "sys_queryAllAlarm failed", e);
            return null;
        }
    }

    public Integer sys_insertAlarm(com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo alarmInfo) {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.insertAlarm(alarmInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "sys_insertAlarm failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE sys_startApp(android.net.Uri uri) {
        ISysService svc = sys();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.startApp(uri);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "sys_startApp failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE sys_enterUpgradeMode() {
        ISysService svc = sys();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.enterUpgradeMode();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "sys_enterUpgradeMode failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public UbxErrorCode.API_ERROR_CODE sys_exitUpgradeMode() {
        ISysService svc = sys();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.exitUpgradeMode();
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "sys_exitUpgradeMode failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }

    public String sys_getChestVersion() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getChestVersion();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getChestVersion failed", e);
            return null;
        }
    }

    public String sys_getHeadVersion() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getHeadVersion();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getHeadVersion failed", e);
            return null;
        }
    }

    public String sys_getBatteryVersion() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getBatteryVersion();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getBatteryVersion failed", e);
            return null;
        }
    }

    public Boolean sys_isPowerCharging() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.isPowerCharging();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_isPowerCharging failed", e);
            return null;
        }
    }

    public Integer sys_getPowerValue() {
        ISysService svc = sys();
        if (svc == null) return null;
        try {
            return svc.getPowerValue();
        } catch (RemoteException e) {
            Log.e(TAG, "sys_getPowerValue failed", e);
            return null;
        }
    }

    public UbxErrorCode.API_ERROR_CODE sys_setPIRSensor(boolean enabled, com.ubtechinc.alpha.serverlibutil.aidl.IRemotePIRSensorOperationResultListener listener) {
        ISysService svc = sys();
        if (svc == null) return UbxErrorCode.API_ERROR_CODE.API_ERROR_NOT_INIT;
        try {
            svc.setPIRSensor(enabled, listener);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        } catch (RemoteException e) {
            Log.e(TAG, "sys_setPIRSensor failed", e);
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_FAILED;
        }
    }
}
