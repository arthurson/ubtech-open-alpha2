package com.ubtechinc.alpha.serverlibutil.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a single available TTS voice, as returned by
 * {@link ISpeechInterface#getSpeechVoices} / {@link ISpeechInterface#getCurSpeechVoices}.
 *
 * Field layout and Parcel read/write order confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling SpeechVoice.class.
 * Note the original class only reads/writes name/sex/adult in
 * readFromParcel/writeToParcel - "language" is set in memory but never marshalled,
 * matching the on-device behavior exactly.
 */
public class SpeechVoice implements Parcelable {

    private String name;
    private int sex;
    private int adult;
    private String language;

    public static final Parcelable.Creator<SpeechVoice> CREATOR = new Parcelable.Creator<SpeechVoice>() {
        @Override
        public SpeechVoice createFromParcel(Parcel in) {
            return new SpeechVoice(in);
        }

        @Override
        public SpeechVoice[] newArray(int size) {
            return new SpeechVoice[size];
        }
    };

    public SpeechVoice() {
        this.name = "";
        this.language = "";
    }

    private SpeechVoice(Parcel in) {
        this.name = "";
        this.language = "";
        readFromParcel(in);
    }

    public void readFromParcel(Parcel in) {
        this.name = in.readString();
        this.sex = in.readInt();
        this.adult = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeInt(this.sex);
        dest.writeInt(this.adult);
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSex() {
        return this.sex;
    }

    public void setSex(int sex) {
        this.sex = sex;
    }

    public int getAdult() {
        return this.adult;
    }

    public void setAdult(int adult) {
        this.adult = adult;
    }

    public String getLanguage() {
        return this.language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @Override
    public String toString() {
        return "speechvoice[name:" + this.name + ";sex:" + this.sex + ";adult:" + this.adult + ";language:" + this.language + "]";
    }
}
