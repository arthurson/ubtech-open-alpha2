package com.ubtechinc.alpha.sdk.led;

/**
 * Enum ordinal-to-code mapping and helper decode method confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling LedColor.class:
 *   RED=1, GREEN=2, BLUE=3, YELLOW=4, MAGENTA=5, CYAN=6, WHITE=7, BLACK=8
 * (fromCode() defaults to RED for any unrecognized code, matching the original.)
 */
public enum LedColor {
    RED(1),
    GREEN(2),
    BLUE(3),
    YELLOW(4),
    MAGENTA(5),
    CYAN(6),
    WHITE(7),
    BLACK(8);

    public final int code;

    LedColor(int code) {
        this.code = code;
    }

    public static LedColor fromCode(int code) {
        switch (code) {
            case 1: return RED;
            case 2: return GREEN;
            case 3: return BLUE;
            case 4: return YELLOW;
            case 5: return MAGENTA;
            case 6: return CYAN;
            case 7: return WHITE;
            case 8: return BLACK;
            default: return RED;
        }
    }
}
