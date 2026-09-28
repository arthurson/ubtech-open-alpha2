package com.ubtechinc.alpha.hardware;

/**
 * Thin value-object wrapper around {@link LedControl#ledSetMouth(int, int, int, int, int)}.
 *
 * UNVERIFIED PROVENANCE: unlike header_ledSetHead5Mic/header_ledSetEye5Mic (real AIDL
 * methods on Alpha2RobotApi, confirmed against SDK source), this class does NOT wrap any
 * AIDL/alpha2serverlib method - there is no such thing as "mouth LED" in the AIDL
 * interface this project otherwise uses. It wraps com.ubtechinc.mic5.LedControl, a
 * native JNI class (backed by libhead_led.so) found in a separate demo APK
 * (alpha2demo).
 *
 * FIELD SEMANTICS - final, confirmed by hand testing on real hardware (see
 * logcat_2026-07-03_01-03-06.txt and the manual sweep that followed it, covering every
 * field individually). This went through two earlier, wrong guesses before landing
 * here - the Java parameter order itself never changed (confirmed by ARM disassembly of
 * libhead_led.so to match the native method's declared signature), only what each
 * position was believed to mean:
 *
 *   1st arg (runTime)        - no confirmed effect on this hardware across every value
 *                               tried. Kept only because it's the native method's 1st
 *                               parameter; true purpose unknown.
 *   2nd arg (breatheSpeedMs) - breathing/fade speed. 500 -> ~500ms fade-in + ~500ms
 *                              fade-out, i.e. a ~1 second breathing cycle. This is what
 *                              the simplified "breathing" preset's slider controls.
 *   3rd arg (offDurationMs) - off-duration between blinks/cycles. 1000 -> roughly a
 *                              1-second pause per cycle (one blink, one pause, repeat).
 *                              Defaults to 0 (no pause) in the breathing() preset.
 *   4th arg (playDurationMs)- total play/run duration for the whole effect. 10000 plays
 *                              for ~10 seconds. The breathing() preset always passes
 *                              Integer.MAX_VALUE here and instead relies on callers
 *                              explicitly starting/stopping the effect (e.g. bracketing
 *                              TTS start/end) rather than a fixed timed duration - see
 *                              MainActivity's speech/tts handling.
 *   5th arg (effectMode)    - CONFIRMED CRITICAL: must be exactly 1 or the LED produces
 *                              no visible light at all, regardless of every other
 *                              field's value. Values 0, 2, and 9 were tried and produced
 *                              none. Whether other untried values do anything different
 *                              (e.g. matching header_ledSetEye5Mic's own "dual"=3) is
 *                              unconfirmed - only 1 is known to work.
 *
 * 控制路径说明：全部嘴燈經 {@link DirectLedController#setMouth} 排隊
 * （DRIVER_LOCK 全局驅動鎖），同頭/眼/pad/wifi 燈排同一條隊，一次一個
 * open→ioctl→close。絕對唔可以直接 LedControl.open() 另起爐灶——舊驅動
 * 頂唔順併發 open，disco 每秒 refresh 個嘴撞正頭/眼 10Hz 已經試過硬 hang
 * 成部機（無 ANR、adb 齊死，幾日後隨時發作）。
 */
public final class MouthLedData {
    /** No confirmed effect on this hardware; true purpose unknown. */
    public final int runTime;

    /** Breathing/fade speed in ms. 500 -> ~500ms fade-in + ~500ms fade-out (a ~1s
     *  breathing cycle). This is what the breathing preset's slider controls. */
    public final int breatheSpeedMs;

    /** Off-duration between blinks/cycles in ms. 1000 -> roughly a 1-second pause per
     *  cycle. */
    public final int offDurationMs;

    /** Total play/run duration in ms for the whole effect. 10000 -> ~10 seconds;
     *  unset/default here plays for the longest available duration. */
    public final int playDurationMs;

    /** Must be exactly 1 or the LED produces no visible light at all. Confirmed
     *  critical - see class javadoc. */
    public final int effectMode;

    public MouthLedData(int runTime, int breatheSpeedMs, int offDurationMs, int playDurationMs, int effectMode) {
        this.runTime = runTime;
        this.breatheSpeedMs = breatheSpeedMs;
        this.offDurationMs = offDurationMs;
        this.playDurationMs = playDurationMs;
        this.effectMode = effectMode;
    }

    /**
     * Breathing-LED preset. breatheSpeedMs is the only dial exposed in the simplified
     * UI (slider range 100-1000, default 150); playDurationMs is always Integer.MAX_VALUE
     * (longest available play duration) since callers now start/stop this manually
     * (e.g. around TTS start/end) rather than relying on a fixed timed duration.
     * offDurationMs defaults to 0 per confirmed testing.
     */
    public static MouthLedData breathing(int breatheSpeedMs) {
        return new MouthLedData(Integer.MAX_VALUE, breatheSpeedMs, 0, Integer.MAX_VALUE, 1);
    }

    /**
     * Issues the mouth effect through {@link DirectLedController#setMouth}, i.e.
     * under the same DRIVER_LOCK as every head/eye/pad/wifi LED call (open →
     * ledSetMouth(...) → close, one at a time). Never open() the driver directly
     * here - concurrent open() wedges /dev/led_eye hard (no ANR, adb dead).
     *
     * Return-value note (unchanged, now via DirectLedController): ledSetMouth's
     * raw result is INVERTED relative to normal JNI boolean convention, confirmed
     * by disassembling libhead_led.so (arm-linux-gnueabihf-objdump, checked
     * against ledSetEye/ledSetHead which share the identical pattern):
     * internally it calls ioctl(fd, cmd, &struct) and returns 0 (JNI false) when
     * ioctl succeeds, and a nonzero value (0xF2/242, JNI true) only when ioctl
     * fails.
     *
     * @return true if ledSetMouth's underlying ioctl call actually succeeded; false if
     *         the driver was busy/unavailable, ledSetMouth's ioctl itself failed, or
     *         either threw (e.g. UnsatisfiedLinkError if libhead_led.so isn't loadable).
     */
    public boolean apply() {
        try {
            return DirectLedController.setMouth(runTime, breatheSpeedMs, offDurationMs, playDurationMs, effectMode);
        } catch (Throwable t) {
            // Native/JNI failures surface here rather than crashing the caller
            // thread - same defensive posture as the rest of this codebase's
            // SDK call wrappers.
            return false;
        }
    }

    /** Turns the mouth LED off. Confirmed working: effectMode=0 falls into the same
     *  "no light" bucket every non-1 value produced in testing. Not an official "off"
     *  command - no such constant exists in LedControl or the demo app - it works
     *  because 0 happens to be one of the many values that produce no light, same as
     *  2 and 9 did. */
    public static MouthLedData off() {
        return new MouthLedData(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0);
    }
}
