package com.open.alpha2;

import com.ubtechinc.mic5.LedControl;

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
 * KNOWN RISK - separate control path: LedControl.open()/close() talk directly to
 * libhead_led.so via JNI, bypassing alpha2serverlib/alpha2services entirely. This is a
 * DIFFERENT path from header_ledSetHead5Mic/header_ledSetEye5Mic, which go through the
 * AIDL-bound alpha2services process. Whether the two paths can safely be used
 * concurrently (e.g. do they contend for the same underlying serial/LED device) has NOT
 * been verified against real hardware. If head/eye LEDs stop responding correctly after
 * a mouth LED call, that's this contention risk manifesting.
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
     * UI (slider range 0-5000, default 0); playDurationMs is always Integer.MAX_VALUE
     * (longest available play duration) since callers now start/stop this manually
     * (e.g. around TTS start/end) rather than relying on a fixed timed duration.
     * offDurationMs defaults to 0 per confirmed testing.
     */
    public static MouthLedData breathing(int breatheSpeedMs) {
        return new MouthLedData(Integer.MAX_VALUE, breatheSpeedMs, 0, Integer.MAX_VALUE, 1);
    }

    /**
     * Issues the native open() -> ledSetMouth(...) -> close() sequence. Each call opens
     * and closes the device handle around itself (matching the demo app's usage
     * exactly) rather than holding it open across calls, to minimise how long this
     * process holds whatever native lock open() acquires - shrinking, though not
     * eliminating, the window where it could contend with the AIDL-based header/eye
     * LED path.
     *
     * IMPORTANT - ledSetMouth's return value is INVERTED relative to normal JNI boolean
     * convention, confirmed by disassembling libhead_led.so (arm-linux-gnueabihf-objdump,
     * checked against ledSetEye/ledSetHead which share the identical pattern):
     * internally it calls ioctl(fd, cmd, &struct) and returns 0 (JNI false) when ioctl
     * succeeds, and a nonzero value (0xF2/242, JNI true) only when ioctl fails. A
     * Java-side `!LedControl.ledSetMouth(...)` on the raw result reads as the actual
     * hardware outcome. This does NOT apply to LedControl.open(), which uses a
     * different, non-inverted internal convention - open()'s result is used as-is
     * below.
     *
     * @return true if ledSetMouth's underlying ioctl call actually succeeded; false if
     *         LedControl.open() failed, ledSetMouth's ioctl itself failed, or either
     *         threw (e.g. UnsatisfiedLinkError if libhead_led.so isn't loadable).
     */
    public boolean apply() {
        try {
            if (!LedControl.open()) {
                return false;
            }
            boolean rawResult;
            try {
                rawResult = LedControl.ledSetMouth(runTime, breatheSpeedMs, offDurationMs, playDurationMs, effectMode); // positions match LedControl's (now aligned) parameter names
            } finally {
                LedControl.close();
            }
            // Inverted on purpose - see javadoc above.
            return !rawResult;
        } catch (Throwable t) {
            // Native/JNI failures (e.g. UnsatisfiedLinkError if the .so is missing or
            // fails to load) surface here rather than crashing the HTTP handler thread -
            // same defensive posture as the rest of this codebase's SDK call wrappers.
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
