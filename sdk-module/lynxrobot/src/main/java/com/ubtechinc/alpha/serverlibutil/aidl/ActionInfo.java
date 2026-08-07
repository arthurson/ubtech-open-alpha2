package com.ubtechinc.alpha.serverlibutil.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a single robot action (a motion sequence known by the robot's action
 * service), as returned by {@link IActionService#getActionList}.
 *
 * Field layout and Parcel read/write order confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling ActionInfo.class.
 */
public class ActionInfo implements Parcelable {

    private String id;
    private String cn_name;
    private String en_name;
    private String desc;
    private int time;
    private String type;

    public static final Parcelable.Creator<ActionInfo> CREATOR = new Parcelable.Creator<ActionInfo>() {
        @Override
        public ActionInfo createFromParcel(Parcel in) {
            return new ActionInfo(in);
        }

        @Override
        public ActionInfo[] newArray(int size) {
            return new ActionInfo[size];
        }
    };

    protected ActionInfo(Parcel in) {
        this.id = in.readString();
        this.cn_name = in.readString();
        this.en_name = in.readString();
        this.desc = in.readString();
        this.time = in.readInt();
        this.type = in.readString();
    }

    public ActionInfo(String id, String cn_name, String en_name, String desc, int time, String type) {
        this.id = (id == null) ? "" : id;
        this.cn_name = (cn_name == null) ? "" : cn_name;
        this.en_name = (en_name == null) ? "" : en_name;
        this.desc = (desc == null) ? "" : desc;
        this.time = time;
        this.type = (type == null) ? "" : type;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.id);
        dest.writeString(this.cn_name);
        dest.writeString(this.en_name);
        dest.writeString(this.desc);
        dest.writeInt(this.time);
        dest.writeString(this.type);
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.en_name;
    }

    public String getCnName() {
        return this.cn_name;
    }

    public String getDesc() {
        return this.desc;
    }

    public int getTime() {
        return this.time;
    }

    public long getDuration() {
        return (long) this.time;
    }

    public String getType() {
        return this.type;
    }

    @Override
    public String toString() {
        return "ActionInfo[name:" + this.cn_name + ";desc:" + this.desc + ";duration:" + this.time + "]";
    }
}
