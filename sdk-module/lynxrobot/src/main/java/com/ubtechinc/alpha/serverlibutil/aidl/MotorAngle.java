package com.ubtechinc.alpha.serverlibutil.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A single (motor id, target angle) pair, used by
 * {@link IMotorInterface#SetAllMotorAbsoluteAngle} to move several motors at once.
 *
 * Field layout and Parcel read/write order confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling MotorAngle.class.
 */
public final class MotorAngle implements Parcelable {

    private int mId;
    private int angle;

    public static final Parcelable.Creator<MotorAngle> CREATOR = new Parcelable.Creator<MotorAngle>() {
        @Override
        public MotorAngle createFromParcel(Parcel in) {
            return new MotorAngle(in);
        }

        @Override
        public MotorAngle[] newArray(int size) {
            return new MotorAngle[size];
        }
    };

    public MotorAngle() {
    }

    public MotorAngle(int id, int angle) {
        this.mId = id;
        this.angle = angle;
    }

    protected MotorAngle(Parcel in) {
        this.mId = in.readInt();
        this.angle = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.mId);
        dest.writeInt(this.angle);
    }

    public int getId() {
        return this.mId;
    }

    public void setId(int id) {
        this.mId = id;
    }

    public int getAngle() {
        return this.angle;
    }

    public void setAngle(int angle) {
        this.angle = angle;
    }
}
