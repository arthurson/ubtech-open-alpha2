package com.ubtechinc.alpha.hardware;

import java.util.Arrays;

/**
 * SerialFrameCodec 單元測試 (零依賴，不用 JUnit)。
 *
 * 點解唔用 JUnit：同 app/src/test 的 ApiValidatorTest 一樣——本專案離線編譯
 * (`--offline`)，gradle cache 入面冇 junit，加 `testImplementation` 會整斷
 * 每個人嘅 offline build。呢個 file 係純 Java + `main()` runner，經
 * `scripts/test-serialcodec.py` 用 `javac`/`java` 直接編譯執行 (CI 同本機都得，
 * 連 android.jar 都唔使——SerialFrameCodec 只掂 java.*)，`./gradlew
 * assembleDebug` 完全唔受影響 (唔喺 main sourceSet)。
 *
 * 點解要測：SerialFrameCodec (嚴格 SUM/ED 校验) 同 MainActivity 側
 * stripSerialFrame() (無校验硬切) 係雙解析器分叉，收斂之前呢份 test 將
 * 651 幀實證過嘅線格式釘死——serial 嗰邊一改錯即紅。注意呢度只測 codec
 * 本身；同 stripSerialFrame 嘅 differential test 要成個 app classpath
 * 先編到 MainActivity，留待收斂嗰陣先做。
 *
 * 向量來源：全部出自 code 註解／實機 logcat 捉到嘅真幀，唔係現編嘅。
 *
 * 執行：`python scripts/test-serialcodec.py`
 */
public final class SerialFrameCodecTest {
    private static int passed = 0;
    private static int failed = 0;

    private SerialFrameCodecTest() {}

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(int expected, int actual, String name) {
        eq(Integer.valueOf(expected), Integer.valueOf(actual), name);
    }

    private static void eqBytes(byte[] expected, byte[] actual, String name) {
        boolean ok = Arrays.equals(expected, actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected=" + SerialFrameCodec.toHex(expected)
                    + " actual=" + SerialFrameCodec.toHex(actual));
        }
    }

    private static void isNull(Object actual, String name) {
        eq(null, actual, name);
    }

    public static void main(String[] args) {
        // -- encode：官方實證向量 (javadoc §真實線格式) --
        // 單舵機官方幀：12+5+0+5+3+0+123+0+100=248=0xF8 (計埋 SRC 先啱)
        eqBytes(hex("f8 8f 0c 05 00 05 03 00 7b 00 64 f8 ed"),
                SerialFrameCodec.encode((byte) 0x05, hex("03 00 7b 00 64")),
                "encode single-servo official");
        // 讀回覆：9+5+0+5+0+3=22=0x16
        eqBytes(hex("f8 8f 09 05 00 05 00 03 16 ed"),
                SerialFrameCodec.encode((byte) 0x05, hex("00 03")),
                "encode read-reply official");
        // chest/version query 實機 TX 捉到：f8 8f 07 05 00 33 3f ed
        // (7+5+0+0x33=63=0x3F)
        eqBytes(hex("f8 8f 07 05 00 33 3f ed"),
                SerialFrameCodec.encode((byte) 0x33, null),
                "encode version query null-param");
        // 空 param 同 null 同形
        eqBytes(SerialFrameCodec.encode((byte) 0x33, null),
                SerialFrameCodec.encode((byte) 0x33, new byte[0]),
                "encode null==empty");
        // mute 燈 cmd68[01]：8+5+0+0x44+1=82=0x52
        eqBytes(hex("f8 8f 08 05 00 44 01 52 ed"),
                SerialFrameCodec.encode((byte) 0x44, hex("01")),
                "encode mute-led");

        // -- decode round-trip (encode 出嚟嘅嘢自己解得返) --
        byte[] enc = SerialFrameCodec.encode((byte) 0x44, hex("01"));
        SerialFrameCodec.DecodeResult rt = SerialFrameCodec.tryDecode(enc, 0, enc.length);
        eqBytes(enc, rt.frame, "roundtrip frame");
        eq(enc.length, rt.consumed, "roundtrip consumed");

        // -- decode 00 00 事件式真幀 (mute 鍵按下，MainActivity 註解實幀) --
        // 8+0+0+0x91+1=154=0x9A
        byte[] mute = hex("f8 8f 08 00 00 91 01 9a ed");
        SerialFrameCodec.DecodeResult m = SerialFrameCodec.tryDecode(mute, 0, mute.length);
        eqBytes(mute, m.frame, "decode 00-head event");
        eq(9, m.consumed, "decode 00-head consumed");

        // -- decode 跳過前面垃圾 --
        byte[] noisy = hex("00 55 f8 8f 08 00 00 91 01 9a ed");
        SerialFrameCodec.DecodeResult n = SerialFrameCodec.tryDecode(noisy, 0, noisy.length);
        eqBytes(mute, n.frame, "decode skip garbage");
        eq(11, n.consumed, "decode skip consumed");

        // -- decode offset>0 --
        byte[] pad = hex("aa f8 8f 08 00 00 91 01 9a ed bb");
        SerialFrameCodec.DecodeResult o = SerialFrameCodec.tryDecode(pad, 1, 9);
        eqBytes(mute, o.frame, "decode offset frame");
        eq(9, o.consumed, "decode offset consumed");

        // -- decode 唔夠 bytes：回 null 等齊 --
        SerialFrameCodec.DecodeResult t = SerialFrameCodec.tryDecode(
                hex("f8 8f 08 00 00"), 0, 5);
        isNull(t, "decode truncated");

        // -- decode SUM 錯：跳 1 byte (frame null) --
        byte[] badSum = hex("f8 8f 08 00 00 91 01 9b ed");
        SerialFrameCodec.DecodeResult bs = SerialFrameCodec.tryDecode(badSum, 0, badSum.length);
        isNull(bs.frame, "decode bad-sum frame");
        eq(1, bs.consumed, "decode bad-sum consumed");

        // -- decode tail 錯：跳 1 byte --
        byte[] badTail = hex("f8 8f 08 00 00 91 01 9a ee");
        SerialFrameCodec.DecodeResult bt = SerialFrameCodec.tryDecode(badTail, 0, badTail.length);
        isNull(bt.frame, "decode bad-tail frame");
        eq(1, bt.consumed, "decode bad-tail consumed");

        // -- decode 短式 (F8 8F LEN CMD PAYLOAD SUM ED)：2+4+0=6 --
        byte[] shortFrame = hex("f8 8f 02 04 00 06 ed");
        SerialFrameCodec.DecodeResult s = SerialFrameCodec.tryDecode(shortFrame, 0, shortFrame.length);
        eqBytes(shortFrame, s.frame, "decode short form");
        eq(7, s.consumed, "decode short consumed");

        // -- decode 完全冇幀頭 / 太短 --
        isNull(SerialFrameCodec.tryDecode(hex("00 11 22 33 44 55"), 0, 6), "decode no-head");
        isNull(SerialFrameCodec.tryDecode(hex("f8 8f 08"), 0, 3), "decode too-short");

        // -- toHex --
        eq("f8 8f", SerialFrameCodec.toHex(hex("f8 8f")), "toHex basic");
        eq("null", SerialFrameCodec.toHex(null), "toHex null");
        eq("", SerialFrameCodec.toHex(new byte[0]), "toHex empty");

        System.out.println("SerialFrameCodecTest: passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
