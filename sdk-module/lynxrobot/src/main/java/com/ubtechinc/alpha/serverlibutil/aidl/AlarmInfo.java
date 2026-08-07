package com.ubtechinc.alpha.serverlibutil.aidl;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a single scheduled robot alarm, as used by {@link ISysService#insertAlarm}
 * and {@link ISysService#queryAllAlarm} / {@link IAlarmListResultListener}.
 *
 * Field layout and Parcel read/write order confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling AlarmInfo.class. All
 * fields are public, matching the original class exactly (no encapsulation was added
 * on-device).
 */
public class AlarmInfo implements Parcelable {

    public int id;
    public int state;
    public int hh;
    public int mm;
    public int repeat;
    public boolean isUseAble;
    public String actionStartName;
    public String acitonEndName;
    public int actionType;
    public int yy;
    public int mo;
    public int day;
    public int date;
    public int ss;
    public boolean vibrate;
    public String label;
    public Uri alert;
    public boolean silent;
    public long dtstart;
    public boolean iscomplete;
    public long dttime;

    public static final Parcelable.Creator<AlarmInfo> CREATOR = new Parcelable.Creator<AlarmInfo>() {
        @Override
        public AlarmInfo createFromParcel(Parcel in) {
            return new AlarmInfo(in);
        }

        @Override
        public AlarmInfo[] newArray(int size) {
            return new AlarmInfo[size];
        }
    };

    public AlarmInfo() {
    }

    protected AlarmInfo(Parcel in) {
        this.id = in.readInt();
        this.state = in.readInt();
        this.hh = in.readInt();
        this.mm = in.readInt();
        this.repeat = in.readInt();
        this.isUseAble = in.readByte() != 0;
        this.actionStartName = in.readString();
        this.acitonEndName = in.readString();
        this.actionType = in.readInt();
        this.yy = in.readInt();
        this.mo = in.readInt();
        this.day = in.readInt();
        this.date = in.readInt();
        this.ss = in.readInt();
        this.vibrate = in.readByte() != 0;
        this.label = in.readString();
        this.alert = in.readParcelable(Uri.class.getClassLoader());
        this.silent = in.readByte() != 0;
        this.dtstart = in.readLong();
        this.iscomplete = in.readByte() != 0;
        this.dttime = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.id);
        dest.writeInt(this.state);
        dest.writeInt(this.hh);
        dest.writeInt(this.mm);
        dest.writeInt(this.repeat);
        dest.writeByte((byte) (this.isUseAble ? 1 : 0));
        dest.writeString(this.actionStartName);
        dest.writeString(this.acitonEndName);
        dest.writeInt(this.actionType);
        dest.writeInt(this.yy);
        dest.writeInt(this.mo);
        dest.writeInt(this.day);
        dest.writeInt(this.date);
        dest.writeInt(this.ss);
        dest.writeByte((byte) (this.vibrate ? 1 : 0));
        dest.writeString(this.label);
        dest.writeParcelable(this.alert, flags);
        dest.writeByte((byte) (this.silent ? 1 : 0));
        dest.writeLong(this.dtstart);
        dest.writeByte((byte) (this.iscomplete ? 1 : 0));
        dest.writeLong(this.dttime);
    }

    @Override
    public String toString() {
        return "AlarmInfo[state=" + this.state
                + ",repeat=" + this.repeat
                + ",actionEndName=" + this.acitonEndName
                + ",anctionStartName=" + this.actionStartName
                + ",isUserAble=" + this.isUseAble
                + ",actionType=" + this.actionType
                + ",yy=" + (this.yy + 2000)
                + ",mo=" + this.mo
                + "day=" + this.day
                + ",date=" + this.date
                + ",hh=" + this.hh
                + ",mm=" + this.mm
                + ",ss=" + this.ss
                + ",vibrate=" + this.vibrate
                + ",label=" + this.label
                + "alert=" + (this.alert != null ? this.alert.toString() : null)
                + ",silent" + this.silent
                + ",dtstart=" + this.dtstart
                + ",iscommplete=" + this.iscomplete
                + "dttime=" + this.dttime
                + "]";
    }
}
