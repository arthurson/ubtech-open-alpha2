package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.serverlibutil.aidl.ActionInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorMoveAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorReadAngleListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorSetAllAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemotePIRSensorOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.ISpeechAsrListener;
import com.ubtechinc.alpha.serverlibutil.aidl.LedInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorAngle;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.SpeechVoice;
import com.ubtechinc.lynxrobot.LynxRobotApi;
import com.ubtechinc.lynxrobot.constant.UbxErrorCode;

import java.util.List;
import java.util.Map;

/**
 * Answers every "/api/lynx/..." HTTP call by invoking the matching {@link LynxRobotApi}
 * method (Lynx 3.0.0.2 firmware, {@code com.ubtechinc.alpha.serverlibutil.aidl}).
 *
 * Mirrors {@link MainActivity}'s handleApi() dispatch style exactly (same
 * require()/queryOrDefault()/jsonSafe() helpers, same ApiResponse shape) so both
 * backends look identical from the browser's point of view - only the URL prefix
 * ("alpha2/" vs "lynx/") and the set of available endpoints differ.
 *
 * Unlike Alpha2RobotApi, LynxRobotApi needs no explicit initXxxApi()/bind step - every
 * subsystem binder is fetched lazily (and re-fetched if the robot process restarts) the
 * first time it's actually used, via ServiceFetcher. So this class can simply be
 * constructed and used immediately; there is no equivalent of Alpha2's "speechReady"
 * flag to wait for.
 *
 * Async results (action list, motor list, LED list, ASR, ...) are pushed onto the same
 * EventBus MainActivity already uses for Alpha2, so the browser's single WebSocket log
 * shows both backends' events without any client-side change.
 */
public class LynxController {

    private static final String TAG = "LynxController";

    private final LynxRobotApi robot;

    /** Handles endpoints that are plain Android hardware access (camera, mic/speaker
     *  test tone) and go through neither Alpha2RobotApi nor LynxRobotApi - the same
     *  physical camera/mic/speaker exist on this hardware regardless of which AIDL
     *  backend is selected, so those calls fall through to MainActivity's existing
     *  implementation instead of being duplicated here. See MainActivity's
     *  SharedHardwareHandler wiring in onCreate(). */
    public interface SharedHardwareHandler {
        HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body);
    }

    private final SharedHardwareHandler sharedHardware;

    public LynxController(Context context, SharedHardwareHandler sharedHardware) {
        this.robot = new LynxRobotApi(context.getApplicationContext());
        this.sharedHardware = sharedHardware;
    }

    /** Endpoints that are pure Android hardware access, identical regardless of which
     *  AIDL backend the browser has selected - not implemented in LynxRobotApi/AlphaRobotApi
     *  at all, so unconditionally deferred to sharedHardware (see its javadoc). */
    private static boolean isSharedHardwarePath(String path) {
        return path.startsWith("camera/") || path.startsWith("audio/testtone")
                || path.startsWith("audio/diagnose") || path.startsWith("audio/play/")
                || path.startsWith("audio/volume/") || path.startsWith("accelerometer/");
    }

    /** Routes "/api/lynx/<name>" calls. Runs on an HttpServer worker thread. */
    public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
        if (isSharedHardwarePath(path) && sharedHardware != null) {
            return sharedHardware.handle(path, query, method, body);
        }
        switch (path) {
            case "status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\"lynx\"}");

            // -- Action -----------------------------------------------------------------
            case "action/list":
                return actionList();
            case "action/play":
                return codeResponse(robot.action_playAction(require(query, "name"), new IActionResultListener.Stub() {
                    @Override
                    public void onPlayActionResult(int code, int progress) {
                        EventBus.get().publish("lynx_action_progress",
                                "{\"code\":" + code + ",\"progress\":" + progress + "}");
                    }

                    @Override
                    public void onStopActionResult(int code) {
                        EventBus.get().publish("lynx_action_stop", "{\"code\":" + code + "}");
                    }
                }));
            case "action/stop":
                return codeResponse(robot.action_stopAction(new IActionResultListener.Stub() {
                    @Override
                    public void onPlayActionResult(int code, int progress) {
                        EventBus.get().publish("lynx_action_progress",
                                "{\"code\":" + code + ",\"progress\":" + progress + "}");
                    }

                    @Override
                    public void onStopActionResult(int code) {
                        EventBus.get().publish("lynx_action_stop", "{\"code\":" + code + "}");
                    }
                }));

            // -- Motor (Lynx's equivalent of Alpha2's chest/header servo panel) --------
            case "motor/list":
                return motorList();
            case "motor/move_absolute": {
                int id = Integer.parseInt(require(query, "id"));
                int angle = Integer.parseInt(require(query, "angle"));
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                return codeResponse(robot.motor_moveToAbsoluteAngle(id, angle, timeMs, new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int motorId, int finalAngle, int code) {
                        EventBus.get().publish("lynx_motor_move",
                                "{\"id\":" + motorId + ",\"angle\":" + finalAngle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/move_ref": {
                int id = Integer.parseInt(require(query, "id"));
                int delta = Integer.parseInt(require(query, "delta"));
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                return codeResponse(robot.motor_moveRefAngle(id, delta, timeMs, new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int motorId, int finalAngle, int code) {
                        EventBus.get().publish("lynx_motor_move",
                                "{\"id\":" + motorId + ",\"angle\":" + finalAngle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/read": {
                int id = Integer.parseInt(require(query, "id"));
                boolean fromHardware = Boolean.parseBoolean(queryOrDefault(query, "hardware", "true"));
                return codeResponse(robot.motor_readAbsoluteAngle(id, fromHardware, new IMotorReadAngleListener.Stub() {
                    @Override
                    public void onReadMotorAngle(int motorId, int angle, int code) {
                        EventBus.get().publish("lynx_motor_angle",
                                "{\"id\":" + motorId + ",\"angle\":" + angle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/set_all": {
                // "id:angle,id:angle,..." e.g. "1:90,2:120"
                String csv = require(query, "angles");
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                String[] parts = csv.split(",");
                MotorAngle[] angles = new MotorAngle[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    String[] kv = parts[i].trim().split(":");
                    angles[i] = new MotorAngle(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
                }
                return codeResponse(robot.motor_setAllMotorAbsoluteAngle(angles, timeMs, new IMotorSetAllAngleResultListener.Stub() {
                    @Override
                    public void onSetAllAngle(int code, int extra) {
                        EventBus.get().publish("lynx_motor_set_all", "{\"code\":" + code + ",\"extra\":" + extra + "}");
                    }
                }));
            }
            case "motor/power_save":
                return codeResponse(robot.motor_setPowerSaveMode(Boolean.parseBoolean(require(query, "on"))));

            // -- LED --------------------------------------------------------------------
            case "led/list":
                return ledList();
            case "led/eye/on":
                return codeResponse(robot.led_turnOnEye(Integer.parseInt(queryOrDefault(query, "color", "1")), ledOpListener("eye_on")));
            case "led/eye/off":
                return codeResponse(robot.led_turnOffEye(ledOpListener("eye_off")));
            case "led/eye/blink":
                return codeResponse(robot.led_turnOnEyeBlink(ledOpListener("eye_blink")));
            case "led/eye/flash":
                return codeResponse(robot.led_turnOnEyeFlash(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("eye_flash")));
            case "led/eye/marquee":
                return codeResponse(robot.led_turnOnEyeMarquee(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("eye_marquee")));
            case "led/head/on":
                return codeResponse(robot.led_turnOnHead(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        ledOpListener("head_on")));
            case "led/head/off":
                return codeResponse(robot.led_turnOffHead(ledOpListener("head_off")));
            case "led/head/flash":
                return codeResponse(robot.led_turnOnHeadFlash(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_flash")));
            case "led/head/marquee":
                return codeResponse(robot.led_turnOnHeadMarquee(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_marquee")));
            case "led/head/breath":
                return codeResponse(robot.led_turnOnHeadBreath(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_breath")));
            case "led/mouth/on":
                return codeResponse(robot.led_turnOnMouth(Integer.parseInt(queryOrDefault(query, "p0", "0")), ledOpListener("mouth_on")));
            case "led/mouth/off":
                return codeResponse(robot.led_turnOffMouth(ledOpListener("mouth_off")));
            case "led/mouth/breath":
                return codeResponse(robot.led_turnOnMouthBreath(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        ledOpListener("mouth_breath")));
            case "led/wifi/on":
                return codeResponse(robot.led_turnOnWifi(Integer.parseInt(queryOrDefault(query, "p0", "0")), ledOpListener("wifi_on")));
            case "led/wifi/off":
                return codeResponse(robot.led_turnOffWifi(ledOpListener("wifi_off")));
            case "led/chest/on":
                return codeResponse(robot.led_turnOnChestLed(ledOpListener("chest_on")));
            case "led/chest/off":
                return codeResponse(robot.led_turnOffChestLed(ledOpListener("chest_off")));

            // -- Speech / TTS / ASR ------------------------------------------------------
            case "speech/tts": {
                String text = require(query, "text");
                String voice = query.get("voice");
                int code = robot.speech_onPlayCallback(text, voice, new com.ubtechinc.alpha.serverlibutil.aidl.ITtsCallBackListener.Stub() {
                    @Override
                    public void onBegin() {
                        EventBus.get().publish("lynx_tts_start", "{}");
                    }

                    @Override
                    public void onEnd() {
                        EventBus.get().publish("lynx_tts_end", "{}");
                    }
                });
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"code\":" + code + "}");
            }
            case "speech/stop":
                return codeResponse(robot.speech_onStopPlay());
            case "speech/set_voice":
                return codeResponse(robot.speech_setVoiceName(require(query, "name")));
            case "speech/set_tts_speed":
                return codeResponse(robot.speech_setTtsSpeed(require(query, "speed")));
            case "speech/get_tts_speed":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"speed\":\"" + jsonSafe(robot.speech_getTtsSpeed()) + "\"}");
            case "speech/set_tts_volume":
                return codeResponse(robot.speech_setTtsVolume(require(query, "volume")));
            case "speech/get_tts_volume":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"volume\":\"" + jsonSafe(robot.speech_getTtsVolume()) + "\"}");
            case "speech/start_asr": {
                String key = queryOrDefault(query, "key", "webpanel");
                int mode = Integer.parseInt(queryOrDefault(query, "mode", "0"));
                return codeResponse(robot.speech_startSpeechAsr(key, mode, new ISpeechAsrListener.Stub() {
                    @Override
                    public void onBegin() {
                        EventBus.get().publish("lynx_asr_begin", "{}");
                    }

                    @Override
                    public void onEnd() {
                        EventBus.get().publish("lynx_asr_end", "{}");
                    }

                    @Override
                    public void onResult(String text) {
                        EventBus.get().publish("lynx_asr_result", "{\"text\":\"" + jsonSafe(text) + "\"}");
                    }

                    @Override
                    public void onError(int errCode) {
                        EventBus.get().publish("lynx_asr_error", "{\"code\":" + errCode + "}");
                    }
                }));
            }
            case "speech/stop_asr":
                return codeResponse(robot.speech_stopSpeechAsr());
            case "speech/voices":
                return speechVoices();
            case "speech/cur_voice": {
                SpeechVoice v = robot.speech_getCurSpeechVoices();
                if (v == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"voice\":null}");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"voice\":" + voiceJson(v) + "}");
            }
            case "speech/switch_core":
                return codeResponse(robot.speech_switchSpeechCore(require(query, "core")));
            case "speech/switch_wakeup":
                return codeResponse(robot.speech_switchWakeup(Boolean.parseBoolean(require(query, "on"))));
            case "speech/start_local_function":
                return codeResponse(robot.speech_startLocalFunction(require(query, "function")));
            case "speech/set_mode":
                return codeResponse(robot.speech_setSpeechMode(Integer.parseInt(require(query, "mode"))));
            case "speech/start_recording":
                return codeResponse(robot.speech_startRecording());
            case "speech/stop_recording":
                return codeResponse(robot.speech_stopRecording());

            // -- Sys ----------------------------------------------------------------------
            case "sys/sid":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"sid\":\"" + jsonSafe(robot.sys_getSid()) + "\"}");
            case "sys/mic_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getMICVersion()) + "\"}");
            case "sys/chest_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getChestVersion()) + "\"}");
            case "sys/head_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getHeadVersion()) + "\"}");
            case "sys/battery_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getBatteryVersion()) + "\"}");
            case "sys/is_charging":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"charging\":" + robot.sys_isPowerCharging() + "}");
            case "sys/power_value":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"value\":" + robot.sys_getPowerValue() + "}");
            case "sys/pir": {
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                return codeResponse(robot.sys_setPIRSensor(enabled, new IRemotePIRSensorOperationResultListener.Stub() {
                    @Override
                    public void onPIRSensorOpResult(int code) {
                        EventBus.get().publish("lynx_pir", "{\"code\":" + code + "}");
                    }
                }));
            }
            case "sys/alarms": {
                String key = queryOrDefault(query, "key", "webpanel");
                AlarmInfo[] alarms = robot.sys_queryAllAlarm(key);
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"alarms\":[");
                if (alarms != null) {
                    for (int i = 0; i < alarms.length; i++) {
                        if (i > 0) sb.append(',');
                        AlarmInfo a = alarms[i];
                        sb.append("{\"id\":").append(a.id).append(",\"hh\":").append(a.hh)
                                .append(",\"mm\":").append(a.mm).append(",\"label\":\"").append(jsonSafe(a.label)).append("\"}");
                    }
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown lynx endpoint: " + path + "\"}");
        }
    }

    // ------------------------------------------------------------------

    private HttpServer.ApiResponse actionList() {
        UbxErrorCode.API_ERROR_CODE result = robot.action_getActionList(new IActionListResultListener.Stub() {
            @Override
            public void onGetActionList(int code, int total, ActionInfo[] actions) {
                // Field names (id/type/nameCn/nameEn) match Alpha2's actionList() JSON
                // contract exactly (see MainActivity#actionList()) so the browser's
                // category/language logic (ACTION_CATEGORIES, displayNameOf(), etc.) can
                // be shared verbatim between both backends - same hardware, same action
                // categories, just a different AIDL surface underneath.
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"actions\":[");
                if (actions != null) {
                    for (int i = 0; i < actions.length; i++) {
                        if (i > 0) sb.append(',');
                        ActionInfo a = actions[i];
                        sb.append("{\"id\":\"").append(jsonSafe(a.getId())).append("\",")
                                .append("\"type\":\"").append(jsonSafe(a.getType())).append("\",")
                                .append("\"nameCn\":\"").append(jsonSafe(a.getCnName())).append("\",")
                                .append("\"nameEn\":\"").append(jsonSafe(a.getName())).append("\"}");
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_action_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private HttpServer.ApiResponse motorList() {
        UbxErrorCode.API_ERROR_CODE result = robot.motor_getMotorList(new IMotorListResultListener.Stub() {
            @Override
            public void onGetMotorList(int code, int total, MotorInfo[] motors) {
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"motors\":[");
                if (motors != null) {
                    for (int i = 0; i < motors.length; i++) {
                        if (i > 0) sb.append(',');
                        MotorInfo m = motors[i];
                        sb.append("{\"id\":").append(m.getId())
                                .append(",\"lower\":").append(m.getLowerLimitAngle())
                                .append(",\"upper\":").append(m.getUpperLimitAngle()).append('}');
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_motor_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private HttpServer.ApiResponse ledList() {
        UbxErrorCode.API_ERROR_CODE result = robot.led_getLedList(new IRemoteLedListResultListener.Stub() {
            @Override
            public void onGetLedList(int code, int total, List<LedInfo> leds) {
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"leds\":[");
                if (leds != null) {
                    for (int i = 0; i < leds.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"').append(jsonSafe(String.valueOf(leds.get(i)))).append('"');
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_led_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private HttpServer.ApiResponse speechVoices() {
        List<SpeechVoice> voices = robot.speech_getSpeechVoices();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"voices\":[");
        if (voices != null) {
            for (int i = 0; i < voices.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(voiceJson(voices.get(i)));
            }
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    private static String voiceJson(SpeechVoice v) {
        return "{\"name\":\"" + jsonSafe(v.getName()) + "\",\"sex\":" + v.getSex()
                + ",\"adult\":" + v.getAdult() + ",\"language\":\"" + jsonSafe(v.getLanguage()) + "\"}";
    }

    private IRemoteLedOperationResultListener.Stub ledOpListener(String tag) {
        return new IRemoteLedOperationResultListener.Stub() {
            @Override
            public void onLedOpResult(int code, int extra) {
                EventBus.get().publish("lynx_led_" + tag, "{\"code\":" + code + ",\"extra\":" + extra + "}");
            }
        };
    }

    // ------------------------------------------------------------------
    // Helpers - deliberately identical contract to MainActivity's private copies.
    // ------------------------------------------------------------------

    private static String require(Map<String, String> query, String key) {
        String v = query.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return v;
    }

    private static String queryOrDefault(Map<String, String> query, String key, String defaultValue) {
        String v = query.get(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    private static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    private static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    private static String jsonSafe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
