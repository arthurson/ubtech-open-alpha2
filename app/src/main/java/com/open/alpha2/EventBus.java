package com.open.alpha2;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Minimal in-process pub/sub. Every robot event (head key, speech callback, action
 * lifecycle, wakeup, gesture, ...) is published here as a single JSON-ish line; the
 * WebSocketServer subscribes and fans it out to every connected browser tab.
 *
 * No third-party dependency - just java.util.concurrent, matching the SDK's own
 * "Android framework + JDK only" rule.
 */
public final class EventBus {

    /** Implemented by anything that wants a live feed of events (e.g. each WebSocket connection). */
    public interface Listener {
        void onEvent(String line);
    }

    private static final EventBus INSTANCE = new EventBus();
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private EventBus() {
    }

    public static EventBus get() {
        return INSTANCE;
    }

    public void subscribe(Listener l) {
        listeners.add(l);
    }

    public void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    private volatile long lastListenerCountLogMs = 0;

    /**
     * Publishes {"type":"<type>","time":"<HH:mm:ss.SSS>","data":<dataJson>} to every
     * subscriber. dataJson must already be valid JSON (an object, string, or literal).
     */
    public void publish(String type, String dataJson) {
        String time;
        synchronized (TIME_FMT) {
            time = TIME_FMT.format(new Date());
        }
        // Rate-limited (every ~2s): publish() firing with zero listeners is silent and
        // looks identical to success from the caller's side (no exception, no log) -
        // this is the single most useful line for telling apart "sensor/source isn't
        // firing" from "nothing is subscribed to hear it" (e.g. no WebSocket connection
        // ever actually completed its handshake, even though HTTP API calls on other
        // connections succeeded).
        long now = System.currentTimeMillis();
        if (now - lastListenerCountLogMs > 2000) {
            lastListenerCountLogMs = now;
            android.util.Log.i("EventBus", "publish(" + type + ") - " + listeners.size() + " listener(s) subscribed");
        }
        String line = "{\"type\":\"" + type + "\",\"time\":\"" + time + "\",\"data\":" + dataJson + "}";
        for (Listener l : listeners) {
            try {
                l.onEvent(line);
            } catch (Exception ignored) {
                // A single bad subscriber must not break the others.
            }
        }
    }
}
