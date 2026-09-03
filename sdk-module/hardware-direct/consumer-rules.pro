# keep JNI bridge names - native .so looks them up by exact Java_... symbol
-keep class com.ubtechinc.alpha.hardware.** { *; }
-keep class com.ubtechinc.alpha.jni.** { *; }
