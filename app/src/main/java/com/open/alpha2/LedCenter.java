package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.MouthLedData;
import com.ubtechinc.alpha.jni.LedControl;

import java.util.Map;

/**
 * LED 層：5-mic 頭/眼、嘴部、底層 JNI/serial 除錯端點。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object 第八刀)：5 個 endpoint
 * case body (led/head/set、led/eye/set、led/mouth/set、debug/jni/led、
 * debug/serial/send) + TTS 嘴燈 bracket + hex 解析，邏輯一字不改搬過嚟。
 * Pad 燈 (手勢用，物理先驗到)、wifi 燈 (要切 wifi 先驗到)、PIR/避障指示
 * 留喺 MainActivity —— 遠程驗唔到嘅唔郁。只需要 Context
 * (頭串口 ready check)；嘴燈／5-mic／JNI 本身全部 static 直驅。
 */
public final class LedCenter {
    private static final String TAG = "LedCenter";

    private final Context appContext;

    public LedCenter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    private boolean headerReady() {
        try {
            return HardwareDirectManager.get(appContext).head().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    // Speed used for the mouth LED breathing effect auto-triggered around TTS speech
    // (see startMouthLedForTts()/stopMouthLedForTts()) - matches the web UI slider's
    // default (0-5000 range, default 0).
    private static final int TTS_MOUTH_LED_SPEED = 0;

    /**
     * Starts the mouth LED breathing effect for the duration of a TTS utterance. Called
     * right after kicking off speech (both robot-side speech_startTTS and Android
     * system TTS), paired with stopMouthLedForTts() called when that speech actually
     * finishes (onServerPlayEnd for robot TTS; UtteranceProgressListener.onDone/onError
     * for Android TTS - see androidTts setup in MainActivity.onCreate).
     *
     * Note this can't be timed to the utterance's real length in advance: neither
     * speech_startTTS nor Android TextToSpeech.speak() reports how long the resulting
     * audio will be before/while it's produced (the robot's TTS engine synthesizes and
     * plays it internally; length depends on synthesis the caller doesn't control), so
     * "flash the mouth for exactly N seconds" is implemented as bracket-and-release
     * around the actual speech rather than a precomputed fixed duration -
     * MouthLedData.breathing() is left running (playDurationMs=MAX) until the
     * corresponding stop call arrives from whichever completion signal fires.
     */
    public static void startMouthLedForTts() {
        MouthLedData.breathing(TTS_MOUTH_LED_SPEED).apply();
    }

    public static void stopMouthLedForTts() {
        MouthLedData.off().apply();
    }

    // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
    // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
    //   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
    //   brightness: 1 (dimmest) .. 9 (brightest)
    //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
    //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
    public HttpServer.ApiResponse ledHeadSet(Map<String, String> query) {
        // pure-direct: 5-mic 经 libhead_led.so JNI 直驱（DirectLedController），不再经 binder。
        String preset = ApiValidator.requireLedHeadPreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopHead5Mic();
            return MainActivity.codeResponseReady(MainActivity.directCode(stopped), headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p5, p6, p8;
        switch (preset) {
            case "flash":   p5 = 100; p6 = 100; p8 = 0; break;
            case "breathe": p5 = 5;   p6 = 20;  p8 = 1; break;
            case "chase":   p5 = 100; p6 = 0;   p8 = 3; break;
            case "dual":    p5 = 500; p6 = 0;   p8 = 5; break;
            case "long":
            default:        p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        boolean sent = DirectLedController.setHead5MicRaw(color, brightness, 31, 31, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), headerReady());
    }

    public HttpServer.ApiResponse ledEyeSet(Map<String, String> query) {
        String preset = ApiValidator.requireLedEyePreset(query);
        if ("stop".equals(preset)) {
            boolean stopped = DirectLedController.stopEye5Mic();
            return MainActivity.codeResponseReady(MainActivity.directCode(stopped), headerReady());
        }
        int color = ApiValidator.requireColor(query);
        int brightness = ApiValidator.requireBrightness(query);
        int p5, p6, p8;
        switch (preset) {
            case "flash": p5 = 100; p6 = 100; p8 = 0; break;
            case "chase": p5 = 100; p6 = 0;   p8 = 1; break;
            case "dual":  p5 = 500; p6 = 0;   p8 = 3; break;
            case "long":
            default:      p5 = Integer.MAX_VALUE; p6 = 0; p8 = 0; break;
        }
        boolean sent = DirectLedController.setEye5MicRaw(color, brightness, 255, 255, p5, p6, Integer.MAX_VALUE, p8);
        return MainActivity.codeResponseReady(MainActivity.directCode(sent), headerReady());
    }

    // NOTE: unlike led/head/set and led/eye/set above, this does NOT go through
    // Alpha2RobotApi/AIDL at all - there is no AIDL "mouth LED" method. It calls
    // com.ubtechinc.alpha.jni.LedControl directly (a native JNI class backed by
    // libhead_led.so 3.002), a completely separate control path found in a different
    // demo app, not gated by isHeaderReady()/waitHeaderReady() since it has
    // nothing to do with the header serial AIDL bind. See MouthLedData's
    // javadoc for the confirmed field semantics and the same-device-contention
    // caveat before relying on this alongside led/head/set or led/eye/set.
    //
    // Simplified to the two effects confirmed usable on this hardware: a
    // breathing effect (speed adjustable, 0-5000ms) and off. effectMode values
    // other than 1 produced no light in testing, so there's no third "always
    // solid, no breathing" preset here - see README for what was tried. Also
    // triggered automatically around TTS start/end - see startMouthLedForTts()/
    // stopMouthLedForTts() above and their call sites in speech/tts,
    // onServerPlayEnd, and the Android TTS UtteranceProgressListener.
    public HttpServer.ApiResponse ledMouthSet(Map<String, String> query) {
        String mouthPreset = ApiValidator.requireMouthPreset(query);
        if ("off".equals(mouthPreset)) {
            boolean ok = MouthLedData.off().apply();
            return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
        }
        int speed = ApiValidator.requireMouthSpeed(query);
        boolean ok = MouthLedData.breathing(speed).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":" + ok + "}");
    }

    public HttpServer.ApiResponse debugJniLed(Map<String, String> query) {
        // 2026-08-25 新增: 直接試 /dev/led_eye 這個 JNI driver 的各個 native
        // function - 這塊 5-mic 板上眼/頭/嘴部 LED 全部走這條路, 兩顆 pad 燈
        // 很可能也是同一個 driver 另一個 ioctl (例如尚未用過的 ledSetOn(i))。
        // func=on&i=N -> ledSetOn(N); func=eye/head&a1..a8 -> 對應 setter。
        String func = ApiValidator.requireDebugLedFunc(query);
        if ("off".equals(func)) {
            boolean openOk = LedControl.open();
            boolean r = LedControl.ledSetOFF(0);
            LedControl.close();
            Log.i(TAG, "ledSetOFF open=" + openOk + " raw=" + r);
            return HttpServer.ApiResponse.ok(
                    "{\"open\":" + openOk + ",\"raw\":" + r + "}");
        }
        boolean openOk = LedControl.open();
        try {
            if ("on".equals(func)) {
                int i = ApiValidator.optionalInt(query, "i", 0);
                boolean r = LedControl.ledSetOn(i);
                Log.i(TAG, "ledSetOn(" + i + ") open=" + openOk + " raw=" + r);
                return HttpServer.ApiResponse.ok(
                        "{\"open\":" + openOk + ",\"raw\":" + r + "}");
            }
            int[] a = new int[8];
            for (int k = 0; k < 8; k++) {
                a[k] = ApiValidator.optionalInt(query, "a" + (k + 1), 0);
            }
            boolean r;
            if ("eye".equals(func)) {
                r = LedControl.ledSetEye(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            } else {
                r = LedControl.ledSetHead(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
            }
            Log.i(TAG, "ledSet" + func + " open=" + openOk
                    + " raw=" + r + " args=" + java.util.Arrays.toString(a));
            return HttpServer.ApiResponse.ok("{\"open\":" + openOk
                    + ",\"raw\":" + r + ",\"args\":"
                    + java.util.Arrays.toString(a).replace(" ", "") + "}");
        } finally {
            LedControl.close();
        }
    }

    public HttpServer.ApiResponse debugSerialSend(Map<String, String> query) {
        // 2026-08-25 新增: raw serial 發送測試端點, 用來反推音量鍵 LED 和
        // 胸口 mute 鍵 LED 的控制指令 (headboard v1.1 上 alpha2services v1.0
        // 協議不合, 只要它一動作 MCU 就不再自動點燈, 要自己 app 補上)。port=head
        // 走 header_sendRawData (ttyS3), port=chest 走 chest_sendRawData
        // (ttyS1); hex 是完整 wire frame (f8 ... ed), 我們在 PC 側組好再送出。
        String port = ApiValidator.optionalSerialPort(query);
        byte[] data = parseHexBytes(ApiValidator.require(query, "hex"));
        // pure-direct: 经 DirectSerialPort.sendRaw 透传完整 wire 帧。
        boolean sent = "chest".equals(port)
                ? HardwareDirectManager.get(appContext).chest().sendRaw(data)
                : HardwareDirectManager.get(appContext).head().sendRaw(data);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        Log.i(TAG, "debug/serial/send port=" + port + " hex=" + MainActivity.toHex(data, data.length)
                + " -> " + code.name());
        return HttpServer.ApiResponse.ok("{\"ok\":"
                + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + ",\"code\":\""
                + code.name() + "\"}");
    }

    /** Parses "f8 8f 08 ..." style hex (spaces/colons optional, case-insensitive) back
     *  into raw bytes for the debug/serial/send endpoint. Returns empty array on junk. */
    private static byte[] parseHexBytes(String hex) {
        String cleaned = hex.replaceAll("[^0-9a-fA-F]", "");
        int n = cleaned.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
