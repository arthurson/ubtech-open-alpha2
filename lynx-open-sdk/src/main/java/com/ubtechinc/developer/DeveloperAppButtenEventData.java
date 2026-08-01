package com.ubtechinc.developer;

import java.io.Serializable;

/**
 * Serializable payload carried inside the developer-app button broadcast
 * ({@code com.ubtechinc.button.back}).
 *
 * <p>The class name ({@code Butten} - a spelling from the original SDK), field names,
 * types and {@code serialVersionUID} are the Java serialization contract shared with the
 * robot's software and must not change: the fully-qualified class name is embedded in the
 * serialized stream the robot deserializes.
 */
public class DeveloperAppButtenEventData implements Serializable {
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
