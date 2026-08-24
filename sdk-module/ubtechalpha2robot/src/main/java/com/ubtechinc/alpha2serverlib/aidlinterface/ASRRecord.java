package com.ubtechinc.alpha2serverlib.aidlinterface;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Carries one replayed speech-recognition record, delivered via
 * {@link IReplaySpeechCallback#onRelpayContent}.
 *
 * <p>Field order, types and the record/msgLanguage/content/contentLinks/labelId names are
 * taken directly from decompiling Alpha2Services v1.1.7.3.20 (20170918171435-5mic):
 * {@code ASRRecord.writeToParcel}/{@code readFromParcel} fix the wire order (7 fields -
 * String, String, String, String, String, int, String), and the on-robot caller that
 * builds one populates it from a {@code ReplaySpeechRcord} whose own getters
 * (getRecordId/getMsgLanguage/getContent/getContentLinks/getLabelId) map onto 5 of those
 * 7 in that exact order, giving recordId/msgLanguage/content/contentLinks/labelId their
 * names here. The first and last wire fields (this class's obfuscated {@code a} and
 * {@code g}) are never set by that caller and had no other call site with a usable name,
 * so they're kept as plain extra1/extra2 rather than guessed at - the wire format is
 * correct either way since Parcelable marshalling only depends on order and type, not on
 * field names.
 */
public class ASRRecord implements Parcelable {
   private String extra1;
   private String recordId;
   private String msgLanguage;
   private String content;
   private String contentLinks;
   private int labelId;
   private String extra2;

   public ASRRecord() {
   }

   protected ASRRecord(Parcel in) {
      this.extra1 = in.readString();
      this.recordId = in.readString();
      this.msgLanguage = in.readString();
      this.content = in.readString();
      this.contentLinks = in.readString();
      this.labelId = in.readInt();
      this.extra2 = in.readString();
   }

   public String getExtra1() {
      return this.extra1;
   }

   public void setExtra1(String extra1) {
      this.extra1 = extra1;
   }

   public String getRecordId() {
      return this.recordId;
   }

   public void setRecordId(String recordId) {
      this.recordId = recordId;
   }

   public String getMsgLanguage() {
      return this.msgLanguage;
   }

   public void setMsgLanguage(String msgLanguage) {
      this.msgLanguage = msgLanguage;
   }

   public String getContent() {
      return this.content;
   }

   public void setContent(String content) {
      this.content = content;
   }

   public String getContentLinks() {
      return this.contentLinks;
   }

   public void setContentLinks(String contentLinks) {
      this.contentLinks = contentLinks;
   }

   public int getLabelId() {
      return this.labelId;
   }

   public void setLabelId(int labelId) {
      this.labelId = labelId;
   }

   public String getExtra2() {
      return this.extra2;
   }

   public void setExtra2(String extra2) {
      this.extra2 = extra2;
   }

   @Override
   public int describeContents() {
      return 0;
   }

   @Override
   public void writeToParcel(Parcel dest, int flags) {
      dest.writeString(this.extra1);
      dest.writeString(this.recordId);
      dest.writeString(this.msgLanguage);
      dest.writeString(this.content);
      dest.writeString(this.contentLinks);
      dest.writeInt(this.labelId);
      dest.writeString(this.extra2);
   }

   public static final Creator<ASRRecord> CREATOR = new Creator<ASRRecord>() {
      @Override
      public ASRRecord createFromParcel(Parcel in) {
         return new ASRRecord(in);
      }

      @Override
      public ASRRecord[] newArray(int size) {
         return new ASRRecord[size];
      }
   };
}
