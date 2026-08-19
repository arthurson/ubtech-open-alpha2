package com.ubtechinc.developer;

/**
 * Builds the payload for a head ear-LED command (head serial command {@code LED_EAR}).
 * The byte layout - three single-byte fields followed by three big-endian 16-bit times -
 * is the format the head microcontroller expects.
 */
public class DeveloperEarLedData {
   private int mRunTime;
   private int mLeftLed;
   private int mRightLed;
   private int mBright;
   private int mLedUpTime;
   private int mLedDownTime;

   public int getmRunTime() {
      return this.mRunTime;
   }

   public void setmRunTime(int mRunTime) {
      this.mRunTime = mRunTime;
   }

   public int getmLeftLed() {
      return this.mLeftLed;
   }

   public void setmLeftLed(int mLeftLed) {
      this.mLeftLed = mLeftLed;
   }

   public int getmRightLed() {
      return this.mRightLed;
   }

   public void setmRightLed(int mRightLed) {
      this.mRightLed = mRightLed;
   }

   public int getmBright() {
      return this.mBright;
   }

   public void setmBright(int mBright) {
      this.mBright = mBright;
   }

   public int getmLedUpTime() {
      return this.mLedUpTime;
   }

   public void setmLedUpTime(int mLedUpTime) {
      this.mLedUpTime = mLedUpTime;
   }

   public int getmLedDownTime() {
      return this.mLedDownTime;
   }

   public void setmLedDownTime(int mLedDownTime) {
      this.mLedDownTime = mLedDownTime;
   }

   public byte[] getPlayData() {
      DeveloperPacketData packetData = new DeveloperPacketData(9);
      packetData.putByte((byte) (this.mLeftLed & 0xFF));
      packetData.putByte((byte) (this.mRightLed & 0xFF));
      packetData.putByte((byte) (this.mBright & 0xFF));
      packetData.putShort_((short) (this.mLedUpTime & 0xFFFF));
      packetData.putShort_((short) (this.mLedDownTime & 0xFFFF));
      packetData.putShort_((short) this.mRunTime);
      return packetData.getBuffer();
   }
}
