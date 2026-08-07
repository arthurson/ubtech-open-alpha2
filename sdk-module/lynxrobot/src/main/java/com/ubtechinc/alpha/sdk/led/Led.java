package com.ubtechinc.alpha.sdk.led;

/**
 * Identifies which LED group on the robot a {@link com.ubtechinc.alpha.serverlibutil.aidl.LedInfo}
 * / LED command applies to.
 *
 * Enum ordinal-to-code mapping and helper decode method confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling Led.class:
 *   HEAD=1, EYE=2, MOUTH=3, EAR=4, CHEST=5
 */
public enum Led {
    HEAD(1),
    EYE(2),
    MOUTH(3),
    EAR(4),
    CHEST(5);

    public final int code;

    Led(int code) {
        this.code = code;
    }

    public static Led fromCode(int code) {
        switch (code) {
            case 1: return HEAD;
            case 2: return EYE;
            case 3: return MOUTH;
            case 4: return EAR;
            case 5: return CHEST;
            default: return null;
        }
    }
}
