package com.theeasiestway.opus;

/**
 * 取代 theeasiestway/android-opus-codec 嘅 opus.aar 入面, 由 Kotlin 編譯出嚟嘅
 * com.theeasiestway.opus.Constants class - 純 Java 版, 冇任何 Kotlin runtime
 * 依賴 (原本 aar 版本嘅 SampleRate/Channels/Application/FrameSize 等都係
 * Kotlin inline value class, 內部用一個 int 字段 "v" 包住, 呢度直接攤平做
 * plain int, 效果完全一樣但唔使 kotlin-stdlib)。
 *
 * 2026-08: 呢個 project 徹底移除咗 opus.aar, 改用 easyopus-jni-src 源碼
 * 自己 CMake 編譯 (見 app/src/main/cpp/CMakeLists.txt)。原本 aar 嘅
 * classes.jar 冇源碼, 只有編譯過嘅 bytecode, 所以呢個 class 係根據
 * classes.jar 嘅 constant-pool / method signature 逆出嚟, 再對照
 * XiaozhiAudioController.java 實際用到嘅 5 個 factory call
 * (SampleRate._16000() / Channels.mono() / Application.voip() /
 * FrameSize._960()) 校對過。
 *
 * 各個值嘅來源:
 *   - SampleRate: opus_encoder_init()/opus_decoder_init() 直接收嘅 raw Hz
 *     值 (opus.h: 必須係 8000/12000/16000/24000/48000 之一)。
 *   - Channels: 1 = mono, 2 = stereo (opus.h 標準)。
 *   - Application: OPUS_APPLICATION_* 宏值, 嚟自官方 opus_defines.h
 *     (VOIP=2048, AUDIO=2049, RESTRICTED_LOWDELAY=2051)。
 *   - FrameSize: samples per frame (非 ms), 由 XiaozhiAudioController 嘅
 *     SAMPLES_PER_FRAME = 16000Hz * 60ms / 1000 = 960 反推。
 *
 * 呢個 project 只用到 SampleRate/Channels/Application/FrameSize 四種, 冇用
 * Bitrate/Complexity 嘅 factory (XiaozhiAudioController 冇 call
 * encoderSetBitrate()/encoderSetComplexity()), 但為咗保持同原 API 形狀
 * 一致, 呢兩個都補齊。
 */
public final class Constants {

    private Constants() {}

    // ================= SampleRate =================

    public static final class SampleRate {
        public final int v;
        private SampleRate(int v) { this.v = v; }

        public static SampleRate _8000() { return new SampleRate(8000); }
        public static SampleRate _12000() { return new SampleRate(12000); }
        public static SampleRate _16000() { return new SampleRate(16000); }
        public static SampleRate _24000() { return new SampleRate(24000); }
        public static SampleRate _48000() { return new SampleRate(48000); }

        /** 保留 ".Companion" 呢層, 為咗同舊 call site
         *  (Constants.SampleRate.Companion._16000()) 兼容, 唔使改
         *  XiaozhiAudioController.java 嘅 call 寫法。 */
        public static final class Companion {
            private Companion() {}
            public static SampleRate _8000() { return SampleRate._8000(); }
            public static SampleRate _12000() { return SampleRate._12000(); }
            public static SampleRate _16000() { return SampleRate._16000(); }
            public static SampleRate _24000() { return SampleRate._24000(); }
            public static SampleRate _48000() { return SampleRate._48000(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Channels =================

    public static final class Channels {
        public final int v;
        private Channels(int v) { this.v = v; }

        public static Channels mono() { return new Channels(1); }
        public static Channels stereo() { return new Channels(2); }

        public static final class Companion {
            private Companion() {}
            public static Channels mono() { return Channels.mono(); }
            public static Channels stereo() { return Channels.stereo(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Application =================

    public static final class Application {
        public final int v;
        private Application(int v) { this.v = v; }

        /** OPUS_APPLICATION_VOIP - 語音優化, 呢個 project (小智語音助理)
         *  用緊呢個。 */
        public static Application voip() { return new Application(2048); }
        /** OPUS_APPLICATION_AUDIO - 音樂/一般音訊優化。 */
        public static Application audio() { return new Application(2049); }
        /** OPUS_APPLICATION_RESTRICTED_LOWDELAY - 最低延遲, 犧牲語音優化。 */
        public static Application restrictedLowdelay() { return new Application(2051); }

        public static final class Companion {
            private Companion() {}
            public static Application voip() { return Application.voip(); }
            public static Application audio() { return Application.audio(); }
            public static Application restrictedLowdelay() { return Application.restrictedLowdelay(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= FrameSize =================
    // 值係 samples per frame (單聲道每 channel 嘅 sample 數), 唔係 ms。
    // 呢啲係 60ms 分別喺唔同 sample rate 下嘅換算, 亦包括常見嘅 2.5/5/10/
    // 20/40ms 檔位 (opus.h 定義嘅合法 frame duration)。呢個 project 淨係
    // 用 _960() (16kHz * 60ms), 其餘為咗保持 API 形狀完整而補上。

    public static final class FrameSize {
        public final int v;
        private FrameSize(int v) { this.v = v; }

        public static FrameSize _120() { return new FrameSize(120); }
        public static FrameSize _160() { return new FrameSize(160); }
        public static FrameSize _240() { return new FrameSize(240); }
        public static FrameSize _320() { return new FrameSize(320); }
        public static FrameSize _480() { return new FrameSize(480); }
        public static FrameSize _640() { return new FrameSize(640); }
        public static FrameSize _960() { return new FrameSize(960); }
        public static FrameSize _1920() { return new FrameSize(1920); }
        public static FrameSize _2880() { return new FrameSize(2880); }

        public static final class Companion {
            private Companion() {}
            public static FrameSize _120() { return FrameSize._120(); }
            public static FrameSize _160() { return FrameSize._160(); }
            public static FrameSize _240() { return FrameSize._240(); }
            public static FrameSize _320() { return FrameSize._320(); }
            public static FrameSize _480() { return FrameSize._480(); }
            public static FrameSize _640() { return FrameSize._640(); }
            public static FrameSize _960() { return FrameSize._960(); }
            public static FrameSize _1920() { return FrameSize._1920(); }
            public static FrameSize _2880() { return FrameSize._2880(); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Bitrate =================
    // 呢個 project 冇用到 (XiaozhiAudioController 冇 call
    // encoderSetBitrate()), 補齊淨係為咗 API 形狀完整。

    public static final class Bitrate {
        public final int v;
        private Bitrate(int v) { this.v = v; }

        /** OPUS_AUTO */
        public static Bitrate auto() { return new Bitrate(-1000); }
        /** OPUS_BITRATE_MAX */
        public static Bitrate max() { return new Bitrate(-1); }
        public static Bitrate instance(int bps) { return new Bitrate(bps); }

        public static final class Companion {
            private Companion() {}
            public static Bitrate auto() { return Bitrate.auto(); }
            public static Bitrate max() { return Bitrate.max(); }
            public static Bitrate instance(int bps) { return Bitrate.instance(bps); }
        }
        public static final Companion Companion = new Companion();
    }

    // ================= Complexity =================
    // 呢個 project 冇用到, 補齊淨係為咗 API 形狀完整。

    public static final class Complexity {
        public final int v;
        private Complexity(int v) { this.v = v; }

        public static Complexity instance(int level) { return new Complexity(level); }

        public static final class Companion {
            private Companion() {}
            public static Complexity instance(int level) { return Complexity.instance(level); }
        }
        public static final Companion Companion = new Companion();
    }
}
