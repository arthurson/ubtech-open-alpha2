package com.ubtechinc.alpha.serverlibutil.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import com.ubtechinc.alpha.sdk.led.Led;
import com.ubtechinc.alpha.sdk.led.LedColor;
import com.ubtechinc.alpha.sdk.led.LedEffect;

import java.util.Set;
import java.util.TreeSet;

/**
 * Describes one LED group's capabilities (which colors/effects it supports), as
 * returned by {@link ILedInterface#getLedList} / {@link IRemoteLedListResultListener}.
 *
 * This is a hand-marshalled Parcelable (not AIDL-generated field-by-field), matching
 * com.ubtechinc.alpha2services_base.3.002.apk exactly: the LED type is written/read as
 * its int code, and colors/effects are each serialized as a single comma-separated
 * String of enum names (with a trailing comma), decoded back via valueOf() - including
 * the original's off-by-one loop bound (length - 1), which silently drops the very
 * last comma-separated entry on read. That quirk is preserved here for wire
 * compatibility with the on-robot service.
 */
public final class LedInfo implements Parcelable {

    private static final String SPLIT = ",";

    private Led led;
    private Set<LedColor> colors;
    private Set<LedEffect> effects;

    public static final Parcelable.Creator<LedInfo> CREATOR = new Parcelable.Creator<LedInfo>() {
        @Override
        public LedInfo createFromParcel(Parcel in) {
            return new LedInfo(in);
        }

        @Override
        public LedInfo[] newArray(int size) {
            return new LedInfo[size];
        }
    };

    public LedInfo() {
        this.colors = new TreeSet<>();
        this.effects = new TreeSet<>();
    }

    public LedInfo(Parcel in) {
        this.colors = new TreeSet<>();
        this.effects = new TreeSet<>();
        readFromParcel(in);
    }

    public void addColor(LedColor color) {
        if (color != null) {
            this.colors.add(color);
        }
    }

    public void addEffect(LedEffect effect) {
        if (effect != null) {
            this.effects.add(effect);
        }
    }

    public Led getLedType() {
        return this.led;
    }

    public void setLedType(Led led) {
        this.led = led;
    }

    public LedColor[] getSupportColors() {
        return this.colors.toArray(new LedColor[this.colors.size()]);
    }

    public LedEffect[] getSupportModes() {
        return this.effects.toArray(new LedEffect[this.effects.size()]);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    protected void readFromParcel(Parcel in) {
        this.led = Led.fromCode(in.readInt());

        String[] colorTokens = in.readString().split(SPLIT);
        for (int i = 0; i < colorTokens.length - 1; i++) {
            LedColor color = LedColor.valueOf(colorTokens[i]);
            if (color != null) {
                this.colors.add(color);
            }
        }

        String[] effectTokens = in.readString().split(SPLIT);
        for (int i = 0; i < effectTokens.length - 1; i++) {
            LedEffect effect = LedEffect.valueOf(effectTokens[i]);
            if (effect != null) {
                this.effects.add(effect);
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.led.code);

        StringBuilder colorBuf = new StringBuilder();
        for (LedColor color : this.colors) {
            colorBuf.append(color.name()).append(SPLIT);
        }
        dest.writeString(colorBuf.toString());

        StringBuilder effectBuf = new StringBuilder();
        for (LedEffect effect : this.effects) {
            effectBuf.append(effect.name()).append(SPLIT);
        }
        dest.writeString(effectBuf.toString());
    }

    @Override
    public String toString() {
        return "LedInfo [led:" + this.led + "," + "colors:" + this.colors + ",effects:" + this.effects + "]";
    }
}
