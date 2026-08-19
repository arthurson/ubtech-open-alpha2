package com.ubtechinc.developer;

import java.io.Serializable;

/**
 * Serializable payload carried inside developer-app config / click broadcasts.
 *
 * <p>The class name, field names, types and {@code serialVersionUID} are the Java
 * serialization contract shared with the robot's software, so they are preserved exactly
 * (do not rename fields or the class even though the name is terse).
 */
public class DeveloperAppData implements Serializable {
   private static final long serialVersionUID = 1L;
   private int cmd;
   private byte[] datas;
   private String packageName;

   public int getCmd() {
      return this.cmd;
   }

   public void setCmd(int cmd) {
      this.cmd = cmd;
   }

   public byte[] getDatas() {
      return this.datas;
   }

   public void setDatas(byte[] datas) {
      this.datas = datas;
   }

   public String getPackageName() {
      return this.packageName;
   }

   public void setPackageName(String packageName) {
      this.packageName = packageName;
   }
}
