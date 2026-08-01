package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Receives every documented Alpha2 sensor/event broadcast (see docs/sensors-and-events.md
 * and docs/capabilities.md in the Alpha2OpenSdk repo) and forwards a JSON-ish line to the
 * shared {@link EventBus}, which both the WebSocket log and any local listeners consume.
 *
 * Registered dynamically from MainActivity.onCreate(). (It used to ALSO be declared as a
 * static &lt;receiver&gt; in AndroidManifest.xml "in addition to" this - that duplicate
 * registration meant every broadcast fired both instances and every event was published
 * to {@link EventBus} twice, showing up twice in the Event Log. Removed; see the
 * manifest's comment at the same spot.)
 *
 * IMPORTANT lesson from a real device: docs/capabilities.md documents
 * "getstureDirection" as a String extra, but on real hardware it arrives as an Integer,
 * and Intent.getStringExtra() throws ClassCastException on a type mismatch rather than
 * returning null. That exception was silently swallowing the whole gesture event. Every
 * extra read below now goes through {@link #readAny}, which tries the extra as every
 * primitive Bundle type Android supports and never throws - so a future doc/reality
 * mismatch degrades to an unlabeled raw value instead of dropping the event.
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
                    // Extra "key" is a Byte, not an int - see gotchas-and-naming.md.
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
                case "com.ubt.alpha2.qr_code": {
                    Object result = readAny(intent, "uncode_result");
                    Object flag = readAny(intent, "flag");
                    EventBus.get().publish("qr_code", "{\"result\":" + jsonValue(result)
                            + ",\"flag\":" + jsonValue(flag) + "}");
                    break;
                }
                case "com.ubt.alpha2.wifiresult": {
                    // Payload shape isn't pinned down in docs; forward every extra name
                    // present so nothing is silently dropped.
                    EventBus.get().publish("wifi_result", bundleToJson(intent.getExtras()));
                    break;
                }
                case "com.ubtechinc.services.bluetooth": {
                    Object btFlag = readAny(intent, "BT_FLAG");
                    EventBus.get().publish("bt_connection", "{\"btFlag\":" + jsonValue(btFlag) + "}");
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
