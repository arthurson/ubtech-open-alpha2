# easyopus JNI 橋接層 - 原本嚟自 theeasiestway/android-opus-codec 嘅
# easyopus-jni-src, 用嚟將 Java 側 com.theeasiestway.opus.Opus 嘅 native
# method call 轉駁去 libopus 嘅 C API (opus_encoder_*/opus_decoder_* 等)。
#
# 2026-08: 呢個 CMake build 取代咗之前直接用 app/libs/opus.aar (內含預編譯
# 嘅 libeasyopus.so + libopus.so + libopusenc.so) 嘅做法, 改為喺專案入面
# 自己編譯 libeasyopus.so, 淨係 link 一個由 Arthur 提供嘅新版
# prebuilt/armeabi-v7a/libopus.so (opus 1.5.x 系, 帶 DRED, 已驗證有齊
# opus_encoder_create/opus_encode/opus_decoder_create/opus_decode 等
# CodecOpus.cpp 用到嘅全部 symbol - 見 nm -D 輸出記錄)。
#
# 冇再 link opusenc: 原本上游 template 有 add_library(opusenc ...), 但
# CodecOpus.cpp 全程只用 <opus.h> 嘅 raw OpusEncoder/OpusDecoder API,
# 完全冇用到 libopusenc (Ogg-Opus 封裝格式) 嘅任何 ope_* 函數 - 呢個
# project 淨係做 raw frame streaming (小智 WebSocket 協議), 唔涉及
# Ogg 容器, 所以刪走呢個冗餘 dependency。

cmake_minimum_required(VERSION 3.10.2)

project("easyopus")

add_library( # 編譯出嚟嘅 .so 名 -> libeasyopus.so, Java 側
             # System.loadLibrary("easyopus") 會搵呢個名
             easyopus

             SHARED

             codec/CodecOpus.cpp
             utils/SamplesConverter.cpp
             easyopus.cpp)

# CodecOpus.h 寫 #include <opus.h> (冇 opus/ prefix), 所以 include path
# 要指去 include/opus 呢一層, 唔係 include 本身。opus.h/opus_defines.h/
# opus_types.h 三個標準 header 嚟自官方 libopus-dev 1.4-1build1 package
# (Ubuntu archive), C API 簽名長年不變, 同 Arthur 提供嘅新版
# libopus.so (1.5.x 系) ABI 相容。
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/opus)

# --- prebuilt libopus.so (Arthur 提供, armeabi-v7a only) ---
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libopus.so)

find_library(log-lib log)

target_link_libraries( easyopus
                        opus
                        ${log-lib} )
