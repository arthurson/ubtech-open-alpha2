package com.theeasiestway.opus;

/**
 * 取代 theeasiestway/android-opus-codec 嘅 opus.aar 入面, 由 Kotlin 編譯出嚟嘅
 * com.theeasiestway.opus.Opus class - 純 Java 版, native method 簽名逐個對照
 * app/src/main/cpp/easyopus.cpp 嘅 JNI 函數名/簽名 (Java_com_theeasiestway_
 * opus_Opus_xxx), 一個字都冇改, 保證 JNI binding 對得上。
 *
 * 2026-08: 呢個 project 徹底移除咗 opus.aar, 改用 easyopus-jni-src 源碼自己
 * CMake 編譯出 libeasyopus.so (見 app/src/main/cpp/CMakeLists.txt), 呢個
 * class 就係嗰個 native library 對應嘅 Java 入口。
 *
 * 呢度直接暴露晒兩組 public overload (byte[] 版同 short[] 版) 嘅
 * encode()/decode(), 因為根據呢個 project 之前對 compiled opus.aar 做過
 * 嘅 javap/constant-pool 分析, easyopus.cpp 入面 (short[], int) 呢種
 * "raw int frameSize" 嘅 overload 喺原本 aar 係 private (內部 helper),
 * public API 一定要用 (byte[]/short[], Constants.FrameSize) 呢個簽名。
 *
 * encode() 嘅 native 簽名本身冇 fec 參數, 所以 public encode(...,
 * FrameSize) 就係最終形態。decode() 嘅 native 簽名就有 fec, 但
 * XiaozhiAudioController.java 實際淨係用緊冇傳 fec 嘅兩參數
 * decode(shorts, FRAME_SIZE) 呼叫方式 (見該檔案第 455 行) - 所以下面
 * decode() 補多咗一個 fec 預設 0 嘅兩參數 public overload, 對應返原本
 * aar 一定有嘅呢個簽名 (第一版漏咗呢個 overload, CI build 曾經因為
 * "no suitable method found for decode(short[],FrameSize)" 而失敗,
 * 已喺呢個修訂修正)。
 */
public class Opus {

    private static final String TAG = "Opus";

    static {
        System.loadLibrary("easyopus");
    }

    public Opus() {}

    // ================= Encoding =================

    public int encoderInit(Constants.SampleRate sampleRate, Constants.Channels channels, Constants.Application application) {
        return encoderInit(sampleRate.v, channels.v, application.v);
    }
    private native int encoderInit(int sampleRate, int numChannels, int application);

    public int encoderSetBitrate(Constants.Bitrate bitrate) {
        return encoderSetBitrate(bitrate.v);
    }
    private native int encoderSetBitrate(int bitrate);

    public int encoderSetComplexity(Constants.Complexity complexity) {
        return encoderSetComplexity(complexity.v);
    }
    private native int encoderSetComplexity(int complexity);

    public byte[] encode(byte[] bytes, Constants.FrameSize frameSize) {
        return encode(bytes, frameSize.v);
    }
    private native byte[] encode(byte[] bytes, int frameSize);

    public short[] encode(short[] shorts, Constants.FrameSize frameSize) {
        return encode(shorts, frameSize.v);
    }
    private native short[] encode(short[] shorts, int frameSize);

    public native void encoderRelease();

    // ================= Decoding =================

    public int decoderInit(Constants.SampleRate sampleRate, Constants.Channels channels) {
        return decoderInit(sampleRate.v, channels.v);
    }
    private native int decoderInit(int sampleRate, int numChannels);

    public byte[] decode(byte[] bytes, Constants.FrameSize frameSize, int fec) {
        return decode(bytes, frameSize.v, fec);
    }
    /** fec 預設 0 (唔用 forward error correction) 嘅 public overload -
     *  XiaozhiAudioController.java 一路都係用呢個兩參數版, 之前漏咗導致
     *  javac 揾唔到 method (build 錯誤: "no suitable method found for
     *  decode(short[],FrameSize)")。 */
    public byte[] decode(byte[] bytes, Constants.FrameSize frameSize) {
        return decode(bytes, frameSize.v, 0);
    }
    private native byte[] decode(byte[] bytes, int frameSize, int fec);

    public short[] decode(short[] shorts, Constants.FrameSize frameSize, int fec) {
        return decode(shorts, frameSize.v, fec);
    }
    /** 同上, short[] 版嘅兩參數 overload。 */
    public short[] decode(short[] shorts, Constants.FrameSize frameSize) {
        return decode(shorts, frameSize.v, 0);
    }
    private native short[] decode(short[] shorts, int frameSize, int fec);

    public native void decoderRelease();

    // ================= Utils =================

    public native short[] convert(byte[] bytes);
    public native byte[] convert(short[] shorts);
}
