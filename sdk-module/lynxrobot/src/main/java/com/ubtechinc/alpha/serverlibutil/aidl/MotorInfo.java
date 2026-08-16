package com.ubtechinc.alpha.serverlibutil.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes a single motor's static characteristics, as returned by
 * {@link IMotorInterface#getMotorList} / {@link IMotorListResultListener}.
 *
 * Field layout and Parcel read/write order confirmed against
 * com.ubtechinc.alpha2services_base.3.002.apk by decompiling MotorInfo.class.
 */
public class MotorInfo implements Parcelable {

    private int mId;
    private int mUpperLimitAngle;
    private int mLowerLimitAngle;
    private int mRotatingSpeed;
    private int mTorque;

    public static final Parcelable.Creator<MotorInfo> CREATOR = new Parcelable.Creator<MotorInfo>() {
        @Override
        public MotorInfo createFromParcel(Parcel in) {
            return new MotorInfo(in);
        }

        @Override
        public MotorInfo[] newArray(int size) {
            return new MotorInfo[size];
        }
    };

    public MotorInfo(int id, int upperLimitAngle, int lowerLimitAngle, int rotatingSpeed, int torque) {
        this.mId = id;
        this.mUpperLimitAngle = upperLimitAngle;
        this.mLowerLimitAngle = lowerLimitAngle;
        this.mRotatingSpeed = rotatingSpeed;
        this.mTorque = torque;
    }

    protected MotorInfo(Parcel in) {
        this.mId = in.readInt();
        this.mUpperLimitAngle = in.readInt();
        this.mLowerLimitAngle = in.readInt();
        this.mRotatingSpeed = in.readInt();
        this.mTorque = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.mId);
        dest.writeInt(this.mUpperLimitAngle);
        dest.writeInt(this.mLowerLimitAngle);
        dest.writeInt(this.mRotatingSpeed);
        dest.writeInt(this.mTorque);
    }

    public int getId() {
        return this.mId;
    }

    public int getUpperLimitAngle() {
        return this.mUpperLimitAngle;
    }

    public int getLowerLimitAngle() {
        return this.mLowerLimitAngle;
    }

    public int getRotatingSpeed() {
        return this.mRotatingSpeed;
    }

    public int getTorque() {
        return this.mTorque;
    }
}
