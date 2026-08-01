package com.ubtechinc.developer;

/**
 * Builds the payload for a head eye-LED command (head serial command {@code LED_EYE}).
 * The byte layout - four single-byte fields followed by three big-endian 16-bit times -
 * is the format the head microcontroller expects.
 */
public class DeveloperEyesLedData {
   private byte mLeftLed;
   private byte mRightLed;
   private byte mBright;
   private byte mColor;
   private int nLightUpTime;
   private int nLightDownTime;
   private int mRunTime;

   public byte getmLeftLed() {
      return this.mLeftLed;
   }

   public void setmLeftLed(byte mLeftLed) {
      this.mLeftLed = mLeftLed;
   }

   public byte getmRightLed() {
      return this.mRightLed;
   }

   public void setmRightLed(byte mRightLed) {
      this.mRightLed = mRightLed;
   }

   public byte getmBright() {
      return this.mBright;
   }

   public void setmBright(byte mBright) {
      this.mBright = mBright;
   }

   public byte getmColor() {
      return this.mColor;
   }

   public void setmColor(byte mColor) {
      this.mColor = mColor;
   }

   public int getnLightUpTime() {
      return this.nLightUpTime;
   }

   public void setnLightUpTime(int nLightUpTime) {
      this.nLightUpTime = nLightUpTime;
   }

   public int getnLightDownTime() {
      return this.nLightDownTime;
   }

   public void setnLightDownTime(int nLightDownTime) {
      this.nLightDownTime = nLightDownTime;
   }

   public int getmRunTime() {
      return this.mRunTime;
   }

   public void setmRunTime(int mRunTime) {
      this.mRunTime = mRunTime;
   }

   public byte[] getPlayData() {
      DeveloperPacketData packetData = new DeveloperPacketData(10);
      packetData.putByte(this.mLeftLed);
      packetData.putByte(this.mRightLed);
      packetData.putByte(this.mBright);
      packetData.putByte(this.mColor);
      packetData.putShort_((short) (this.nLightUpTime & 0xFFFF));
      packetData.putShort_((short) (this.nLightDownTime & 0xFFFF));
      packetData.putShort_((short) this.mRunTime);
      return packetData.getBuffer();
   }
}
