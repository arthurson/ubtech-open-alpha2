package com.ubtechinc.alpha.hardware.ubx;

import java.util.ArrayList;
import java.util.List;

/**
 * .ubx 解析结果（clean-room 实现，结构按原装 .ubx 实测为准，
 * 经 UBX/actions 全量 202 个 + Alpha2_pc 例档逐字节对齐验证）。
 *
 * <p>文件 = 顶层头 + motion 段（多 track，每 track 若干 servo 帧）+ 副段（cLen，常 8）。
 * 长度语义：每段均为 [outer len][echo==len][body]，len 只计 echo 起（含 echo，
 * 不含 outer 本身）；消费一段后以前进 {@code 4 + len} 对准下一段。</p>
 */
public final class UbxFile {
    public int version;
    public final List<UbxTrack> tracks = new ArrayList<>();
    /**
     * tick 时间基（ms），来自首个含 type-0 block 的 track；
     * 多 track 时各 track 以自身 {@link UbxTrack#timeBaseMs} 为准，此处仅作单 track 快捷。
     * 文件无此 block 时 &lt;=0（未知）。
     */
    public int timeBaseMs = -1;
    /**
     * 宽容跳过的空 marker 数。原装 202+1 个文件实测 motion 段严丝合缝、
     * 无真实空 marker（此前计出的空位系步进少算 4B 的幻影）；此计数器仅作
     * 前向兼容保留，正常文件恒为 0。
     */
    public int skipped;

    /** 单个 track（对应 util.d.b）。servo 帧只从 f==0 的 d.a 经 a.d 链收进 frames。 */
    public static final class UbxTrack {
        /** track id（头 echo 后 4B；单动作恒 0，多动作如 0/2/3/4/1）。 */
        public int id;
        /**
         * @deprecated  phantom 字段：原装结构并无此 4B，
         * 旧版误将 echo 当 id、真 id 当 X。为兼容保留，恒与 {@link #id} 相同，请勿再用。
         */
        @Deprecated
        public int xfield;
        public int servoGroups;
        public int framesA;
        public int nonServoFrames;
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafCount;
        /** d.d 叶全量（每项 [a][b][c][d]；leafA~D 保留末项以兼容）。 */
        public final List<int[]> leafRows = new ArrayList<>();
        /** d.f 关键帧表全量（位姿参考，不进 frames；旧版曾误并入 frames）。 */
        public final List<UbxServoFrame> keyframes = new ArrayList<>();
        /** 本 track 时间基（ms，type-0 的 a）；无则 &lt;=0，回落 {@link UbxFile#timeBaseMs}。 */
        public int timeBaseMs = -1;
        public int timeBaseB;
        public final List<Integer> frameBValues = new ArrayList<>();
        /**
         * @deprecated phantom：b-section 实测恒为 d.a 复帧列，从无 20B 引用头；
         * 保留空 list 以兼容，恒为空。
         */
        @Deprecated
        public final List<int[]> refs = new ArrayList<>();
        /** a.d 非 0/1 block 原样（type2/3 灯等、type4 音乐 meta；旧版直接丢弃）。 */
        public final List<UbxBlock> blocks = new ArrayList<>();
        /** 音乐文件名（如 rap.mp3，无则 null；UTF-16 段中提取）。 */
        public String musicName;
        /** 音乐原绝对路径（如 D:\work\...\rap.mp3，無則 null，僅供溯源）。 */
        public String musicPath;
        /** 宽容跳过的空 marker 数（原厂 `if (L1>0)` 同语义）。 */
        public int skipped;
        public final List<UbxServoFrame> frames = new ArrayList<>();
    }

    /**
     * a.d 非 servo block 原样（type + data 全拷贝）。
     */
    public static final class UbxBlock {
        public int type;
        public byte[] data = new byte[0];
    }

    /**
     * 单个 servo 关键帧（对应 util.a.a）。
     * start/end 与播放 tick 同单位（ticks，乘 timeBaseMs 得 ms；原装 b/c 常见 start&gt;end 如 20/0，语义待胸 dump 定，播放以 start 为准）。
     * angles20 为 20 轴目标值（0-255，按 a.m 规则“每8字节取1int→byte”预抽取；
     * groups 不足 20 时余轴自然为 0，不另改写）。
     * moveTimeHintMs = 原厂 send time 参数（a.a.b() * 同 track timeBase），供 player 参考。
     */
    public static final class UbxServoFrame {
        public int start;
        public int end;
        public final byte[] angles20 = new byte[20];
        public int baseB;
        public int baseC;
        public int groupBytes;
        public int moveTimeHintMs;
        // d.e 叶原始字段（timing 语义待 dump 定）
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafF;
        public int leafG;
    }
}
