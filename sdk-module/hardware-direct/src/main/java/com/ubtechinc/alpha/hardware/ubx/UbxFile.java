package com.ubtechinc.alpha.hardware.ubx;

import java.util.ArrayList;
import java.util.List;

/**
 * .ubx 解析結果（clean-room 實現，結構按原裝 .ubx 實測為準，
 * 經 UBX/actions 全量 202 個 + Alpha2_pc 例檔逐字節對齊驗證）。
 *
 * <p>文件 = 頂層頭 + motion 段（多 track，每 track 若干 servo 幀）+ 副段（cLen，常 8）。
 * 長度語義：每段均為 [outer len][echo==len][body]，len 只計 echo 起（含 echo，
 * 不含 outer 本身）；消費一段後以前進 {@code 4 + len} 對準下一段。</p>
 */
public final class UbxFile {
    public int version;
    public final List<UbxTrack> tracks = new ArrayList<>();
    /**
     * tick 時間基（ms），來自首個含 type-0 block 的 track；
     * 多 track 時各 track 以自身 {@link UbxTrack#timeBaseMs} 為準，此處僅作單 track 快捷。
     * 文件無此 block 時 &lt;=0（未知）。
     */
    public int timeBaseMs = -1;
    /**
     * 寬容跳過的空 marker 數。原裝 202+1 個文件實測 motion 段嚴絲合縫、
     * 無真實空 marker（此前計出的空位係步進少算 4B 的幻影）；此計數器僅作
     * 前向兼容保留，正常文件恆為 0。
     */
    public int skipped;

    /** 單個 track（對應 util.d.b）。servo 幀只從 f==0 的 d.a 經 a.d 鏈收進 frames。 */
    public static final class UbxTrack {
        /** track id（頭 echo 後 4B；單動作恆 0，多動作如 0/2/3/4/1）。 */
        public int id;
        public int servoGroups;
        /** voice d.a（f==4 → a/j/a/o 鏈）成功拆出的次數（配樂幀見 frames 內 voice 項）。 */
        public int voiceGroups;
        public int framesA;
        public int nonServoFrames;
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafCount;
        /** d.d 葉全量（每項 [a][b][c][d]；leafA~D 保留末項以兼容）。 */
        public final List<int[]> leafRows = new ArrayList<>();
        /** d.f 關鍵幀表全量（位姿參考，不進 frames；舊版曾誤並入 frames）。 */
        public final List<UbxServoFrame> keyframes = new ArrayList<>();
        /** 本 track 時間基（ms，type-0 的 a）；無則 &lt;=0，回落 {@link UbxFile#timeBaseMs}。 */
        public int timeBaseMs = -1;
        public int timeBaseB;
        public final List<Integer> frameBValues = new ArrayList<>();
        /** a.d 非 0/1 block 原樣（type2/3 燈等、type4 音樂 meta；舊版直接丟棄）。 */
        public final List<UbxBlock> blocks = new ArrayList<>();
        /** 音樂文件名（如 rap.mp3，無則 null；UTF-16 段中提取）。 */
        public String musicName;
        /** 音樂原絕對路徑（如 D:\work\...\rap.mp3，無則 null，僅供溯源）。 */
        public String musicPath;
        /** 寬容跳過的空 marker 數（原廠 `if (L1>0)` 同語義）。 */
        public int skipped;
        public final List<UbxServoFrame> frames = new ArrayList<>();
        /**
         * 全 d.a 段 [e-field, f-flag]（檔案序；d/a.a() 即 e，d/a.b() 即 f，
         * smali f/b.f 選段分支用）。f==0 servo，1→f/e，2→e/d，4→voice。
         */
        public final List<int[]> daAll = new ArrayList<>();
        /**
         * 與 daAll 平行：各段在 {@link #frames} 內的 [start,end)；
         * 非 servo 段記 null（走 f/e、e/d 側通道，唔進舵機序）。
         */
        public final List<int[]> daSpans = new ArrayList<>();
        /**
         * 官方鏈式 servo 播放序：daAll 下標列（smali f/b.f＋f/c.a 實證：
         * 入口＝(a==-1,b==0) 葉；逐葉跟鏈，leaf.c 對 d/a.e 選段，
         * v7 gate（keyframe a==leaf.d 的 d 值）非 0/3 即 skip，
         * c==-2 鏈終止；段播完按出索引（servo 2、f/e 0、e/d 按 True/False
         * keyframe，無名即 -1 斷鏈）搵下一批 (a==e,b==outIdx) 葉。
         * f!=0 段唔進舵機序）。null＝無鏈頭，回退解析序；
         * 空表＝鏈指明唔播（跟官方播零格）。
         */
        public List<Integer> playOrder;
        /**
         * 鏈行到 c==-2 終止符（smali f/c.a completed 分支：全動作完，不再起後續
         * track；前進 track1、後退 track0 實證——到此為止，後面 track 唔播）。
         */
        public boolean chainTerminated;
    }

    /**
     * a.d 非 servo block 原樣（type + data 全拷貝）。
     */
    public static final class UbxBlock {
        public int type;
        public byte[] data = new byte[0];
    }

    /**
     * 單個 servo 關鍵幀（對應 util.a.a）。
     * start/end 與播放 tick 同單位（ticks，乘 timeBaseMs 得 ms；原裝 b/c 常見 start&gt;end 如 20/0，語義待胸 dump 定，播放以 start 為準）。
     * angles20 為 20 軸目標值（0-255，按 a.m 規則“每8字節取1int→byte”預抽取；
     * groups 不足 20 時餘軸自然為 0，不另改寫）。
     * moveTimeHintMs = 原廠 send time 參數（a.a.b() * 同 track timeBase），供 player 參考。
     */
    public static final class UbxServoFrame {
        public int start;
        public int end;
        /** voice 幀（d.a f==4 → a/j/a/o 鏈）：只供配樂調度，舵機發送跳過。music 為 e-blob 尾 GBK 名。 */
        public boolean voice;
        public String music;
        public final byte[] angles20 = new byte[20];
        public int baseB;
        public int baseC;
        public int groupBytes;
        public int moveTimeHintMs;
        // d.e 葉原始字段（timing 語義待 dump 定）
        public int leafA;
        public int leafB;
        public int leafC;
        public int leafD;
        public int leafF;
        public int leafG;
    }
}
