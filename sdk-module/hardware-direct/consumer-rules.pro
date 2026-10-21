# keep JNI bridge names - native .so looks them up by exact Java_... symbol
-keep class com.ubtechinc.alpha.hardware.** { *; }
-keep class com.ubtechinc.alpha.jni.** { *; }
# 1.1.7.3 2-mic head key JNI: libNativeClassJni.so exports
# Java_com_example_t01_nativeClass_* and calls onNativeCallback by name.
-keep class com.example.t01.** { *; }
# 1.1.7.3 official serial JNI (2-mic): libserial_port_2mic.so exports
# Java_android_serialport_api_SerialPort_open/close.
-keep class android_serialport_api.** { *; }
