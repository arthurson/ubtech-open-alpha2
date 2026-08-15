package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;


/**
 * Receives sensor/event broadcasts and forwards a JSON-ish line to the shared
 * {@link EventBus}, which both the WebSocket log and any local listeners consume.
 *
 * Registered dynamically from MainActivity.onCreate().
 *
 * IMPORTANT: docs may document extras as one type but real hardware delivers another.
 * Every extra read below goes through {@link #readAny}, which tries the extra as every
 * primitive Bundle type Android supports and never throws - so a type mismatch degrades
 * to an unlabeled raw value instead of dropping the event.
 */
public class RobotEventReceiver extends BroadcastReceiver {
    private static final String TAG = "RobotEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        try {
            switch (action) {
                case "com.ubtechinc.key": {
                    Object key = readAny(intent, "key");
                    EventBus.get().publish("head_key", "{\"keyId\":" + jsonValue(key) + "}");
                    break;
                }
                case "com.ubtechinc.services.SPEECH_DIRECTION": {
                    Object angle = readAny(intent, "absoluteAngle");
                    int unsigned = toUnsignedByteInt(angle);
                    EventBus.get().publish("speech_direction", "{\"absoluteAngle\":" + unsigned + "}");
                    break;
                }
                case "com.ubtechinc.robot.tts_hint_wakeup": {
                    Object hint = readAny(intent, "hint_event");
                    EventBus.get().publish("wakeup", "{\"hintEvent\":" + jsonValue(hint) + "}");
                    break;
                }
                case "come.ubt.alpha2.gesture": {
                    // Documented as String; observed as Integer on real hardware - see
                    // class javadoc. readAny() handles either without throwing.
                    Object direction = readAny(intent, "getstureDirection");
                    EventBus.get().publish("gesture", "{\"direction\":" + jsonValue(direction) + "}");
                    break;
                }
                case "com.ubtechinc.robot_uuid.info": {
                    Object uuid = readAny(intent, "robot_uuid");
                    EventBus.get().publish("robot_uuid", "{\"uuid\":" + jsonValue(uuid) + "}");
                    break;
                }
                case "com.ubtechinc.alpha_qrcode": {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case "com.ubtechinc.alpha_wifi_result": {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case "com.ubtechinc.alpha_bt_connection": {
                    Object btFlag = readAny(intent, "BT_FLAG");
                    EventBus.get().publish("bt_connection", "{\"btFlag\":" + jsonValue(btFlag) + "}");
                    break;
                }
                case "com.ubtechinc.services.Action.PIR_STATE": {
                    // Lynx PIR: extra "pirState" is a boolean.
                    Object pirState = readAny(intent, "pirState");
                    boolean triggered = Boolean.TRUE.equals(pirState);
                    EventBus.get().publish("pir_state", "{\"triggered\":" + triggered + "}");
                    break;
                }
                case "com.ubtechinc.services.header": {
                    // Lynx header keys: "value" is int (4 = volume+1, 5 = volume x0.5).
                    Object value = readAny(intent, "value");
                    EventBus.get().publish("header_key", "{\"value\":" + jsonValue(value) + "}");
                    break;
                }
                case "com.ubtechinc.services.Action.ACTION_STOP": {
                    // Body action playback stopped externally.
                    EventBus.get().publish("action_stop", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.ROBOT_INTERRUPTED": {
                    // Robot interrupted (usually TTS + action stop together).
                    EventBus.get().publish("robot_interrupted", "{}");
                    break;
                }
                case "com.ubtechinc.services.Action.CHEST_ACTION": {
                    // Chest broadcast - forwards raw extras for debug; mute key (-111)
                    // scanned from raw "value" byte array.
                    EventBus.get().publish("chest_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");

                    Object rawValue = readAny(intent, "value");
                    if (rawValue instanceof byte[]) {
                        byte[] arr = (byte[]) rawValue;
                        for (byte b : arr) {
                            if (b == (byte) -111) {
                                EventBus.get().publish("chest_mute_key", "{}");
                                break;
                            }
                        }
                    }
                    break;
                }
                case "com.ubtechinc.services.Action.SONAR_DISTANCE": {
                    // Sonar distance: extra "sonar_distance" is int (cm), 0/negative = out of range.
                    int distanceCm = intent.getIntExtra("sonar_distance", -1);
                    boolean triggered = distanceCm > 0 && distanceCm <= 30;
                    EventBus.get().publish("sonar_obstacle",
                            "{\"distanceCm\":" + distanceCm
                                    + ",\"thresholdCm\":" + 30
                                    + ",\"triggered\":" + triggered + "}");
                    break;
                }
                case "com.ubtechinc.services.stoptts": {
                    // Lynx: stop-TTS signal from physical button press.
                    EventBus.get().publish("stop_tts", "{}");
                    break;
                }
                // Debug events: forward raw action + extras for speech/mic related broadcasts.
                case "com.ubtechinc.services.ABOUT_TTS":
                case "com.ubtechinc.services.ALPHA_SOCKET_ASR_OK":
                case "com.ubtechinc.services.SPEECH_ANGLE_5MIC":
                case "com.ubtechinc.services.LED_ACTION":
                case "com.ubtechinc.services.IFLY_OFFLINE_CMD":
                case "com.ubtechinc.services.NUANCE_OFFLINE_CMD":
                case "com.ubtechinc.services.POWER_SAVE":
                case "com.ubtechinc.services.ALPHA_NOTIFY_POWER": {
                    EventBus.get().publish("mic_broadcast_debug",
                            "{\"action\":\"" + action + "\",\"extras\":" + bundleToJson(intent.getExtras()) + "}");
                    break;
                }
                default:
                    Log.d(TAG, "Unhandled action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error for " + action, e);
        }
    }

    /**
     * Reads a Bundle extra without knowing its real type ahead of time. Tries the common
     * primitive wrapper types Android's Bundle supports for a single extra key, in an
     * order that costs nothing on a miss (Bundle.get() itself never throws - it's the
     * *typed* getters like getStringExtra() that throw ClassCastException on a mismatch).
     * Falls back to Bundle.get() (returns Object, works for any type) if a caller needs
     * something outside that set.
     */
    private static Object readAny(Intent intent, String key) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        return extras.get(key); // Bundle.get() is untyped and never throws ClassCastException.
    }

    private static int toUnsignedByteInt(Object value) {
        if (value instanceof Byte) {
            int v = (Byte) value;
            return v < 0 ? v + 256 : v;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    /** Renders any extra value as a JSON literal: quoted string, bare number/boolean, or null. */
    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte || value instanceof Double
                || value instanceof Float) {
            return String.valueOf(value);
        }
        // byte[] fallback: print each byte as signed decimal in [] brackets.
        if (value instanceof byte[]) {
            byte[] arr = (byte[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(jsonValue(arr[i]));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + safe(String.valueOf(value)) + "\"";
    }

    /** Dumps every extra in a Bundle as a flat JSON object of stringified values, for
     *  broadcasts whose exact payload shape isn't pinned down upstream. */
    private static String bundleToJson(Bundle extras) {
        if (extras == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : extras.keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(safe(key)).append("\":").append(jsonValue(extras.get(key)));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
