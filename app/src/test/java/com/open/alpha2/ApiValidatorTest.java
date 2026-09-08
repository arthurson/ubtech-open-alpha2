package com.open.alpha2;

import java.util.HashMap;
import java.util.Map;

/**
 * ApiValidator 單元測試 (零依賴，不用 JUnit)。
 *
 * 點解唔用 JUnit：本專案離線編譯 (`--offline`)，gradle cache 入面冇 junit，
 * 加 `testImplementation 'junit:...'` 會整斷每個人嘅 offline build。
 * 呢個 file 係純 Java + `main()` runner，經 `scripts/test-apivalidator.py`
 * 用 `javac`/`java` + SDK 嘅 `android.jar` 直接編譯執行 (CI 同本機都得)，
 * `./gradlew assembleDebug` 完全唔受影響 (唔喺 main sourceSet)。
 *
 * 執行：`python scripts/test-apivalidator.py`
 */
public final class ApiValidatorTest {
    private static int passed = 0;
    private static int failed = 0;

    private ApiValidatorTest() {}

    private static Map<String, String> q(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(float expected, float actual, String name) {
        boolean ok = (Float.isNaN(expected) && Float.isNaN(actual))
                || Math.abs(expected - actual) < 0.0001f;
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        eq(Integer.valueOf(expected), Integer.valueOf(actual), name);
    }

    private static void eq(boolean expected, boolean actual, String name) {
        eq(Boolean.valueOf(expected), Boolean.valueOf(actual), name);
    }

    private static void throwsIllegal(Runnable r, String name) {
        try {
            r.run();
            failed++;
            System.out.println("FAIL " + name + ": expected IllegalArgumentException, none thrown");
        } catch (IllegalArgumentException e) {
            passed++;
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + ": wrong exception " + t);
        }
    }

    public static void main(String[] args) {
        // ── 基礎 ──
        eq("v", ApiValidator.require(q("k", "v"), "k"), "require.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.require(q(), "k"); } }, "require.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.require(q("k", ""), "k"); } }, "require.empty");
        eq("v", ApiValidator.optional(q("k", "v"), "k", "d"), "optional.present");
        eq("d", ApiValidator.optional(q(), "k", "d"), "optional.missing");
        eq("d", ApiValidator.optional(q("k", ""), "k", "d"), "optional.empty");

        // ── 整數 ──
        eq(42, ApiValidator.requireInt(q("k", "42"), "k"), "requireInt.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireInt(q("k", "abc"), "k"); } }, "requireInt.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireInt(q(), "k"); } }, "requireInt.missing");
        eq(7, ApiValidator.optionalInt(q("k", "7"), "k", 9), "optionalInt.present");
        eq(9, ApiValidator.optionalInt(q(), "k", 9), "optionalInt.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalInt(q("k", "x"), "k", 9); } }, "optionalInt.bad");
        eq(5, ApiValidator.requireIntRange(q("k", "5"), "k", 1, 20), "requireIntRange.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireIntRange(q("k", "21"), "k", 1, 20); } }, "requireIntRange.high");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireIntRange(q("k", "0"), "k", 1, 20); } }, "requireIntRange.low");
        eq(3, ApiValidator.optionalIntRange(q("k", "3"), "k", 1, 20, 1), "optionalIntRange.present");
        eq(1, ApiValidator.optionalIntRange(q(), "k", 1, 20, 1), "optionalIntRange.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalIntRange(q("k", "99"), "k", 1, 20, 1); } }, "optionalIntRange.high");

        // ── 長整數 ──
        eq(Long.valueOf(1500L), Long.valueOf(ApiValidator.requireLong(q("k", "1500"), "k")), "requireLong.ok");
        eq(Long.valueOf(1500L), Long.valueOf(ApiValidator.optionalLong(q(), "k", 1500L)), "optionalLong.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalLong(q("k", "1.5"), "k", 0L); } }, "optionalLong.bad");

        // ── 浮點 ──
        eq(0.5f, ApiValidator.requireFloat(q("k", "0.5"), "k"), "requireFloat.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireFloat(q("k", "abc"), "k"); } }, "requireFloat.bad");
        eq(Float.NaN, ApiValidator.optionalFloat(q(), "k", Float.NaN), "optionalFloat.nanDefault");
        eq(1.5f, ApiValidator.optionalFloat(q("k", "1.5"), "k", Float.NaN), "optionalFloat.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalFloat(q("k", "abc"), "k", Float.NaN); } }, "optionalFloat.bad");
        eq(Double.valueOf(2.5d), Double.valueOf(ApiValidator.requireDouble(q("k", "2.5"), "k")), "requireDouble.ok");
        eq(Double.valueOf(3.0d), Double.valueOf(ApiValidator.optionalDouble(q(), "k", 3.0d)), "optionalDouble.missing");

        // ── Nullable ──
        eq(null, ApiValidator.optionalInteger(q(), "k"), "optionalInteger.missing");
        eq(null, ApiValidator.optionalInteger(q("k", ""), "k"), "optionalInteger.empty");
        eq(Integer.valueOf(4), ApiValidator.optionalInteger(q("k", "4"), "k"), "optionalInteger.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalInteger(q("k", "z"), "k"); } }, "optionalInteger.bad");
        eq(null, ApiValidator.optionalBooleanObject(q(), "k"), "optionalBooleanObject.missing");
        eq(Boolean.TRUE, ApiValidator.optionalBooleanObject(q("k", "true"), "k"), "optionalBooleanObject.true");
        eq(Boolean.FALSE, ApiValidator.optionalBooleanObject(q("k", "0"), "k"), "optionalBooleanObject.zero");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalBooleanObject(q("k", "maybe"), "k"); } }, "optionalBooleanObject.bad");
        eq(null, ApiValidator.optionalNullable(q(), "k"), "optionalNullable.missing");
        eq("x", ApiValidator.optionalNullable(q("k", "x"), "k"), "optionalNullable.present");

        // ── 枚舉 ──
        final String[] ab = new String[]{"a", "b"};
        eq("a", ApiValidator.requireEnum(q("k", "a"), "k", ab), "requireEnum.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireEnum(q("k", "c"), "k", ab); } }, "requireEnum.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireEnum(q(), "k", ab); } }, "requireEnum.missing");
        eq("b", ApiValidator.optionalEnum(q("k", "b"), "k", ab, "a"), "optionalEnum.present");
        eq("a", ApiValidator.optionalEnum(q(), "k", ab, "a"), "optionalEnum.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalEnum(q("k", "c"), "k", ab, "a"); } }, "optionalEnum.bad");
        eq(null, ApiValidator.optionalEnumNullable(q(), "k", ab), "optionalEnumNullable.missing");
        eq("b", ApiValidator.optionalEnumNullable(q("k", "b"), "k", ab), "optionalEnumNullable.present");

        // ── Boolean ──
        eq(true, ApiValidator.requireBoolean(q("k", "true"), "k"), "requireBoolean.true");
        eq(true, ApiValidator.requireBoolean(q("k", "1"), "k"), "requireBoolean.one");
        eq(false, ApiValidator.requireBoolean(q("k", "FALSE"), "k"), "requireBoolean.falseUpper");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBoolean(q("k", "yes"), "k"); } }, "requireBoolean.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBoolean(q(), "k"); } }, "requireBoolean.missing");
        eq(true, ApiValidator.optionalBoolean(q(), "k", true), "optionalBoolean.default");

        // ── 常用組合 ──
        eq(3, ApiValidator.requireColor(q("color", "3")), "color.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireColor(q("color", "8")); } }, "color.high");
        eq(9, ApiValidator.requireBrightness(q("brightness", "9")), "brightness.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBrightness(q("brightness", "0")); } }, "brightness.low");
        eq("long", ApiValidator.requireLedHeadPreset(q()), "ledHead.default");
        eq("breathe", ApiValidator.requireLedHeadPreset(q("preset", "breathe")), "ledHead.breathe");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireLedHeadPreset(q("preset", "breathe2")); } }, "ledHead.bad");
        eq("long", ApiValidator.requireLedEyePreset(q()), "ledEye.default");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireLedEyePreset(q("preset", "breathe")); } }, "ledEye.noBreathe");
        eq("breathing", ApiValidator.requireMouthPreset(q()), "mouth.default");
        eq(0, ApiValidator.requireMouthSpeed(q()), "mouthSpeed.default");
        eq(150, ApiValidator.requireMouthSpeed(q("speed", "150")), "mouthSpeed.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireMouthSpeed(q("speed", "5001")); } }, "mouthSpeed.high");
        // P0 行為：speech/tts 預設引擎係 android (唯一會出聲)。
        eq("android", ApiValidator.requireSpeechEngine(q()), "speechEngine.defaultAndroid");
        eq("iflytek", ApiValidator.requireSpeechEngine(q("engine", "iflytek")), "speechEngine.iflytek");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireSpeechEngine(q("engine", "bogus")); } }, "speechEngine.bad");
        eq("ringtone", ApiValidator.optionalRingtoneType(q()), "ringtone.default");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalRingtoneType(q("type", "alarm")); } }, "ringtone.bad");
        eq("head", ApiValidator.optionalSerialPort(q()), "serialPort.default");
        eq("chest", ApiValidator.optionalSerialPort(q("port", "chest")), "serialPort.chest");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalSerialPort(q("port", "usb")); } }, "serialPort.bad");
        eq("zh", ApiValidator.optionalUiLang(q()), "uiLang.default");
        eq("en", ApiValidator.optionalUiLang(q("ui_lang", "en")), "uiLang.en");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalUiLang(q("ui_lang", "fr")); } }, "uiLang.bad");
        eq("on", ApiValidator.requireDebugLedFunc(q("func", "on")), "debugFunc.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireDebugLedFunc(q()); } }, "debugFunc.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireDebugLedFunc(q("func", "blink")); } }, "debugFunc.bad");
        eq("xiaozhi", ApiValidator.requireXiaozhiTtsEngine(q("engine", "xiaozhi")), "xiaozhiTts.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireXiaozhiTtsEngine(q("engine", "nuance")); } }, "xiaozhiTts.bad");
        eq("off", ApiValidator.requireBootVoiceMode(q("mode", "off")), "bootVoice.off");
        eq("xiaozhi", ApiValidator.requireBootVoiceMode(q("mode", "xiaozhi")), "bootVoice.xiaozhi");
        eq("vosk", ApiValidator.requireBootVoiceMode(q("mode", "vosk")), "bootVoice.vosk");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBootVoiceMode(q("mode", "bad")); } }, "bootVoice.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireBootVoiceMode(q()); } }, "bootVoice.missing");

        // ── ubx speed ──
        eq(1.0f, ApiValidator.requireUbxSpeed(q("value", "1")), "ubxSpeed.one");
        eq(0.67f, ApiValidator.requireUbxSpeed(q("value", "0.67")), "ubxSpeed.067");
        eq(2.0f, ApiValidator.parseUbxSpeedValue("2"), "ubxSpeed.parse");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUbxSpeed(q("value", "3")); } }, "ubxSpeed.bad");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUbxSpeed(q()); } }, "ubxSpeed.missing");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.parseUbxSpeedValue(null); } }, "ubxSpeed.null");

        // ── uuid / volume / endpointer mode / angles ──
        eq("ABC123", ApiValidator.requireUuidValue(q("value", "ABC123")), "uuid.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q("value", "01234567890123456789012345678901")); } }, "uuid.tooLong");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q("value", "ABC\u00e4")); } }, "uuid.nonAscii");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireUuidValue(q()); } }, "uuid.missing");
        eq(50, ApiValidator.requireVolumePercent(q("percent", "50")), "volume.ok");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireVolumePercent(q("percent", "101")); } }, "volume.high");
        eq(50, ApiValidator.requireVolumePercent(q("percent", "50"), "percent"), "volume.keyed");
        eq(-1, ApiValidator.optionalVoskEndpointerMode(q()), "epMode.default");
        eq(2, ApiValidator.optionalVoskEndpointerMode(q("mode", "2")), "epMode.present");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.optionalVoskEndpointerMode(q("mode", "4")); } }, "epMode.high");
        int[] angles = ApiValidator.requireAngles20(q("angles", "90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90,90"));
        eq(20, angles.length, "angles20.len");
        eq(90, angles[0], "angles20.first");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireAngles20(q("angles", "1,2,3")); } }, "angles20.short");
        throwsIllegal(new Runnable() { public void run() { ApiValidator.requireAngles20(q()); } }, "angles20.missing");

        // ── 回應 helpers ──
        eq(true, ApiValidator.okTrue().body.contains("\"ok\":true"), "okTrue.body");
        eq(200, ApiValidator.ok("{\"ok\":true}").status, "ok.status");
        eq(true, ApiValidator.error("boom").body.contains("boom"), "error.body");

        System.out.println("ApiValidatorTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
