package com.ubtechinc.constant;

/**
 * Recognition / TTS language codes accepted by the robot's speech service.
 *
 * <p>The string values ("zh_cn", "en_us") are the wire codes the robot expects and
 * must not change. (The type was spelled {@code LauguageType} in the original SDK;
 * the corrected name is used here.)
 */
public final class LanguageType {
   public static final String LAU_CHINESE = "zh_cn";
   public static final String LAU_ENGLISH = "en_us";

   private LanguageType() {
   }
}
