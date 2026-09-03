package com.ubtechinc.alpha.hardware.ubx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * .ubx clean-room 解析器（absolute-offset 版）。
 *
 * <p>格式以原装 .ubx 实测为准（UBX/actions 全量 202 个 + Alpha2_pc 例档，
 * motion 尾精确对齐，无 slack；此前“4B slack / 空 marker / X 字段”均为误读，已按实物修正）：
 * <pre>
 * 顶层 (f.k.setRawData): [total][ubx-alpha 9B][version≤2][eLen][motion eLen B][cLen][副段skip]
 *   （eLen 与 motion 首字双写相等；副段实测恒 cLen=8）
 * motion (d.c): [len][count][item]*，item=[outer len][echo==len][id][fLen…]，
 *   len 只计 echo 起（不含 outer 本身），耗 {@code 4 + len} 对准下一项，尾精确接 motion 尾。
 * track (d.b): [outer][echo][id][fLen outer][f→d.f/d.e位姿参考]
 *              [aLen outer][a→d.d叶列][bLen outer][b→d.a复帧列]（无 X 字段）。
 *   d.d 叶列（私有 a）：[echo==len][count][L1 outer][echo==L1 + 20B头 + 尾]*
 *     d.d 叶头：[echo][a][b][c][d]（值多为 -1/-2 sentinel，全量保留）。
 *   d.a 复帧列（私有 b）：[echo==len][count][L1 outer][L1内容→d.a 复帧]*，
 *     严格不出本段（旧版以文件尾为界，過鬆）。
 * d.a 复帧（pos 指向 L2'，len 即 L1'）。布局：
 * [len][fLen][f表][a 编排][b 编排][c[50]][d[50]][e][f][g][h][h字节→i]。
 *   是否 servo 看 f（f==0 走 a.d servo 链；b 实测 72~229 恒非零，非判据）。
 *   servo 的 i blob + h 长喂 a.d 多轨分发。
 * a.d 分发：[len==self][count][type][len][data]*
 *   type==0 且 len==8 → 时间基 [a][b]（static，a.p 语义；记入本 track，out.timeBaseMs 取首个）
 *   type==1 → data → a.h → a.c 容器 [len==self][count][L1][内容]*
 *     → a.b block [L1 outer][echo][a][b=start][c=end][d[30]][count][a.a]*
 *       → a.a servo 帧 [L1 outer][echo][a][b=start][c=end][d[30]][f][e(f字节)]
 *   type==2/3/4…（灯/音乐等）原样存 {@code track.blocks} 并提取音乐名/路径，不丢弃。
 * a.a 帧角度（a.m 私有发送原文）：groups=f()/8（上限20）；
 * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF；不足 20 组余轴自然为 0。
 * 发送 time 实参 = b*timeBase（f.b.f() 原文 {@code send(f, f.b()*p.a)}，以同 track 时间基计）。
 * 胸串口发送：cmd 3 [20轴byte + short time]（a.m 原文，与 StaticValue.CHEST_CMD_SENDMOTOR 一致）。
 * </pre>
 * </p>
 */
public final class UbxParser {
    private UbxParser() {}

    public static final class UbxParseException extends Exception {
        UbxParseException(String m) { super(m); }
    }

    public static UbxFile parseFile(File f) throws IOException, UbxParseException {
        byte[] data = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != data.length) throw new UbxParseException("short read " + off + "/" + data.length);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
        return parse(data);
    }

    public static UbxFile parse(byte[] data) throws UbxParseException {
        Cursor r = new Cursor(data, 0, data.length);
        int total = r.i32();
        if (total != data.length) throw new UbxParseException("total " + total + "!=" + data.length);
        byte[] magic = r.bytes(9);
        String ms;
        try {
            ms = new String(magic, "GB2312");
        } catch (Exception e) {
            throw new UbxParseException("gb2312 missing");
        }
        if (!"ubx-alpha".equals(ms)) throw new UbxParseException("bad magic " + ms);
        UbxFile out = new UbxFile();
        out.version = r.i32();
        if (out.version > 2) throw new UbxParseException("version " + out.version);
        int eLen = r.i32();
        int motionStart = r.pos;
        if (eLen < 0 || motionStart + eLen > data.length) throw new UbxParseException("bad motion len " + eLen);
        parseMotion(data, motionStart, eLen, out);
        // 回填：同 track 时间基可能在 servo 帧之后才出现（多 d.a），统一按终态重算。
        for (UbxFile.UbxTrack t : out.tracks) {
            int base = t.timeBaseMs > 0 ? t.timeBaseMs
                    : (out.timeBaseMs > 0 ? out.timeBaseMs : 0);
            if (base <= 0) continue;
            for (UbxFile.UbxServoFrame sf : t.frames) {
                sf.moveTimeHintMs = sf.start * base;
            }
        }
        int p = motionStart + eLen;
        if (data.length - p >= 4) {
            int cLen = le(data, p);
            p += 4;
            if (cLen < 0 || p + cLen > data.length) throw new UbxParseException("bad aux len " + cLen);
            p += cLen;
        }
        if (p != data.length) throw new UbxParseException("top slack " + (data.length - p));
        return out;
    }

    /**
     * motion 段 = d.c：[len][count][item]*。
     * 长度语义与全文件统一：每项 [outer len][echo==len][body]，len 不含 outer 本身，
     * 前进 {@code 4 + len}；尾必须精确接住 motion 尾（原装 202+1 全量如此）。
     * len&lt;=0 的空 marker 仅前向兼容保留（原装实测无真实空位）。
     */
    private static void parseMotion(byte[] d, int off, int len, UbxFile out) throws UbxParseException {
        if (le(d, off) != len) throw new UbxParseException("motion len check");
        int count = le(d, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int itemLen = le(d, p);
            if (itemLen <= 0) {
                out.skipped++;
                p += 4;
                continue;
            }
            if (le(d, p + 4) != itemLen) throw new UbxParseException("track echo");
            if (p + 4 + itemLen > off + len) throw new UbxParseException("bad track len " + itemLen);
            parseTrack(d, p, itemLen, out); // off 对准 outer（含 echo 校验，d.b 原文从 L1 起复制）
            p += 4 + itemLen;
        }
        if (p != off + len) throw new UbxParseException("motion slack " + (off + len - p));
    }

    /**
     * track = d.b：[outer][echo==len][id][fLen outer][f→d.f/d.e位姿参考]
     * [aLen outer][a→d.d叶列][bLen outer][b→d.a复帧列]。
     * 原装并无 X 字段（旧版将 echo 误作 id、真 id 误作 X；单 track 时 id 恒 0，
     * 多 track 如 1464835936031 的 0/2/3/4/1）。
     * 各段内容首 echo 即自校验 len（双写惯例）；段尾必须精确接上下一段。
     */
    private static void parseTrack(byte[] t, int off, int declaredLen, UbxFile out) throws UbxParseException {        if (le(t, off) != declaredLen) throw new UbxParseException("track len check");
        if (le(t, off + 4) != declaredLen) throw new UbxParseException("track echo");
        UbxFile.UbxTrack track = new UbxFile.UbxTrack();
        int p = off + 8;
        track.id = le(t, p); p += 4;
        track.xfield = track.id; // 兼容旧字段：恒与 id 相同
        int fLen = le(t, p); p += 4;
        int trackEnd = off + 4 + declaredLen;
        if (fLen < 0 || p + fLen > trackEnd) throw new UbxParseException("bad ftable " + fLen);
        if (fLen > 0) parseFrameTable(t, p, fLen, out, track);
        p += fLen;
        int aLen = le(t, p); p += 4;
        if (aLen < 0 || p + aLen > trackEnd) throw new UbxParseException("bad aleaflist " + aLen);
        parseFrameDList(t, p, aLen, track);
        p += aLen;
        int bLen = le(t, p); p += 4;
        if (bLen < 0 || p + bLen > trackEnd) throw new UbxParseException("bad aframelist " + bLen);
        parseFrameAList(t, p, bLen, track, out);
        p += bLen;
        if (p != trackEnd) throw new UbxParseException("track slack " + (trackEnd - p));
        out.tracks.add(track);
    }

    /**
     * d.d 叶列（d.b 私有 a）：[echo==len][count][L1 outer][echo==L1 + 20B头 + 尾]*。
     * 叶头 20B [echo][a][b][c][d]（原装值多为 -1/-2 sentinel）；全量保留，
     * leafA~D 沿用末项以兼容。
     */
    private static void parseFrameDList(byte[] b, int off, int len, UbxFile.UbxTrack track) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("dlist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // 空 marker：原厂 if>0 同语义
            if (p + l1 > off + len) throw new UbxParseException("bad dleaf len " + l1);
            if (le(b, p) != l1) throw new UbxParseException("dleaf len check");
            int a = le(b, p + 4);
            int bb = le(b, p + 8);
            int c = le(b, p + 12);
            int dd = le(b, p + 16);
            track.leafRows.add(new int[]{a, bb, c, dd});
            track.leafA = a;
            track.leafB = bb;
            track.leafC = c;
            track.leafD = dd;
            track.leafCount++;
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("dlist slack " + (off + len - p));
    }

    /**
     * d.a 复帧列（d.b 私有 b，b-section）：[echo==len][count][L1 outer][L1内容→d.a 复帧]*。
     * 内容首 echo == L1（双写）；严格不出本段。
     */
    private static void parseFrameAList(byte[] b, int off, int len, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("alist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // 空 marker：原厂 if>0 同语义
            if (le(b, p) != l1) throw new UbxParseException("aframe echo");
            if (p + l1 > off + len) throw new UbxParseException("bad aframe len " + l1);
            parseFrameA(b, p, l1, track, out);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("alist slack " + (off + len - p));
    }

    /**
 * d.a 复帧（pos 指向 L2'，len 即 L1'）。布局：
 * [len][fLen][f表][a 编排][b 编排][c[50]][d[50]][e][f][g][h][h字节→i]。
 * 只看 f（f.b.f 分发原文）：f==0 走 f.i/a.d servo 链；f==4 走 voice/b（联网 TTS，
 * 不作解析）；b 实测恒非零，非判据。配乐 mp3 来自 servo 链内 type==4 块（a/d 原文
 * 按 type 建 handler：1→a/h servo，2→a/f，3→a/e，4→a/j/mp3，5→a/i），a/j 内为
 * 同形 a/c→a/b→a.a，帧 e-blob 去头尾 8B 即 GBK 歌名（a/o.a() 原文）。
     */
    private static void parseFrameA(byte[] f, int off, int declaredLen, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(f, off) != declaredLen) throw new UbxParseException("aframe len check");
        int itemEnd = off + declaredLen;
        int p = off + 4;
        int fLen = le(f, p); p += 4;
        if (fLen < 0 || p + fLen > itemEnd) throw new UbxParseException("bad aframe ftable");
        p += fLen;
        if (p + 216 > itemEnd) throw new UbxParseException("bad aframe head");
        p += 4; // a（编排字段）
        int fb = le(f, p); p += 4; // b（编排字段；非 servo 判据，原装实测 72~229）
        p += 100; // c[50]
        p += 100; // d[50]
        p += 4; // e（原装实测 1）
        int ff = le(f, p); p += 4; // f：==0 即 servo（f.b.f→f.i 原文）
        p += 4; // g
        int fh = le(f, p); p += 4; // h＝i blob 长
        if (fh < 0 || p + fh > itemEnd) throw new UbxParseException("bad aframe iblob " + fh);
        if (p + fh != itemEnd) throw new UbxParseException("aframe slack " + (itemEnd - p - fh));
        track.framesA++;
        track.frameBValues.add(fb);
        if (fh == 0) return;
        if (ff != 0) { track.nonServoFrames++; return; } // f.e/e.d/voice-b（联网TTS）分支，不作 a.d 解析
        try {
            parseServoChain(f, p, fh, out, track, false);
            track.servoGroups++;
        } catch (UbxParseException e) {
            track.nonServoFrames++;
        }
    }

    /**
     * servo/voice 执行链（a.d 多轨分发）：[len==self][count][type][len][data]*（pos 指向 len）。
     * type==0 且 len==8 → 时间基 [a][b]（static，a.p 语义；记入本 track，out 取首个）；
     * type==1 → data → a.h servo 帧；
     * type==4 → data → a/j 配乐链（同形 a/c→a/b→a.a，帧标 voice 供配乐调度，不进舵机）；
     * 其余 type（2/3/5…）原样保留并提取音乐名/路径（回退）。
     */
    static void parseServoChain(byte[] eBytes, int off, int eLen, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(eBytes, off) != eLen) throw new UbxParseException("adispatch len check");
        int count = le(eBytes, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int type = le(eBytes, p); p += 4;
            int len = le(eBytes, p); p += 4;
            if (len <= 0) { track.skipped++; continue; }
            if (p + len > off + eLen) throw new UbxParseException("bad block len");
            if (type == 0 && len == 8) {
                int base = le(eBytes, p);
                track.timeBaseMs = base;
                track.timeBaseB = le(eBytes, p + 4);
                if (out.timeBaseMs <= 0) out.timeBaseMs = base;
            } else if (type == 1) {
                parseTrackH(eBytes, p, len, out, track, voice);
            } else if (type == 4) {
                // a/j 配乐链：内容即 a/c 容器（失败则回退到原样保留，不影响 servo）。
                try {
                    parseTrackH(eBytes, p, len, out, track, true);
                    track.voiceGroups++;
                } catch (UbxParseException e) {
                    track.nonServoFrames++;
                }
                UbxFile.UbxBlock blk = new UbxFile.UbxBlock();
                blk.type = type;
                blk.data = new byte[len];
                System.arraycopy(eBytes, p, blk.data, 0, len);
                track.blocks.add(blk);
                supplementMusic(blk.data, track);
            } else {
                UbxFile.UbxBlock blk = new UbxFile.UbxBlock();
                blk.type = type;
                blk.data = new byte[len];
                System.arraycopy(eBytes, p, blk.data, 0, len);
                track.blocks.add(blk);
                supplementMusic(blk.data, track);
            }
            p += len;
        }
        if (p != off + eLen) throw new UbxParseException("adispatch slack " + (off + eLen - p));
    }

    /**
     * 从音乐/灯 block 二进制中提取 {@code *.mp3} 文件名与绝对路径。
     * 原装如 {@code rap.mp3} 以 UTF-16LE 存名、以 ASCII 存 {@code D:\...\rap.mp3} 全路径；
     * 此处双编码扫描，只读不改。
     */
    private static void supplementMusic(byte[] data, UbxFile.UbxTrack track) {
        // ASCII/GBK 路径：按字节回溯（除 NUL/控制符外全收，中文目录为 GBK 编码），再按 GBK 解码。
        String lowerRaw = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(java.util.Locale.US);
        int idx = lowerRaw.indexOf(".mp3");
        while (idx >= 0) {
            int e = idx + 4;
            int s = idx;
            while (s > 0) {
                int bb = data[s - 1] & 0xFF;
                if (bb == 0 || bb < 0x20) break;
                s--;
            }
            if (e - s >= 5 && e - s <= 260) {
                byte[] tok = new byte[e - s];
                System.arraycopy(data, s, tok, 0, e - s);
                String token;
                try {
                    token = new String(tok, "GBK");
                } catch (Exception ex) {
                    token = new String(tok, java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                token = token.trim();
                if (token.length() >= 5) {
                    if (token.indexOf('\\') >= 0 || token.indexOf('/') >= 0 || token.indexOf(':') >= 0) {
                        if (track.musicPath == null) track.musicPath = token;
                        int slash = Math.max(token.lastIndexOf('\\'), token.lastIndexOf('/'));
                        String base = token.substring(slash + 1).trim();
                        if (base.length() >= 5 && track.musicName == null) track.musicName = base;
                    } else if (track.musicName == null) {
                        track.musicName = token;
                    }
                }
            }
            idx = lowerRaw.indexOf(".mp3", e);
        }
        // UTF-16LE（名段）：形如 r\0a\0p\0.\0m\0p\03\0
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i + 1 < data.length; i += 2) {
            int lo = data[i] & 0xFF;
            int hi = data[i + 1] & 0xFF;
            char c;
            if (hi == 0 && lo >= 0x20 && lo < 0x7F) {
                c = (char) lo;
            } else if (lo == 0 && hi == 0) {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
                continue;
            } else {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
            if (cur.length() > 260) {
                finishMusicToken(cur.toString(), track);
                cur.setLength(0);
            }
        }
        finishMusicToken(cur.toString(), track);
    }

    private static void finishMusicToken(String token, UbxFile.UbxTrack track) {
        String t = token.trim();
        int n = t.toLowerCase(java.util.Locale.US).indexOf(".mp3");
        if (n < 0) return;
        t = t.substring(0, n + 4);
        int cut = Math.max(t.lastIndexOf('\0'), t.lastIndexOf(' '));
        // 保留盘符/斜杠，basename 另作 musicName
        int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        if (slash >= 0 || t.indexOf(':') >= 0) {
            if (track.musicPath == null) track.musicPath = t;
            String base = t.substring(slash + 1);
            if (base.length() >= 5 && track.musicName == null) track.musicName = base;
        } else if (t.length() >= 5 && track.musicName == null) {
            track.musicName = t;
        }
    }

    /** a.h → a.c 容器：[echo==len][count][L1 outer][内容]*（内容从 echo 起读）。 */
    private static void parseTrackH(byte[] h, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(h, off) != len) throw new UbxParseException("ablock len check");
        int count = le(h, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(h, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (le(h, p) != l1) throw new UbxParseException("ablock echo");
            if (p + l1 > off + len) throw new UbxParseException("bad ablock item " + l1);
            parseBlockB(h, p, l1, out, track, voice);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("ablock slack " + (off + len - p));
    }

    /**
     * a.b block（off 指向 echo）：[echo][a][b=start][c=end][d[30]][count]
     * [L1 outer][L1内容→a.a]*。
     * a.a servo 帧（off 指向 echo）：[echo][a][b=start][c=end][d[30]][f][e(f字节)]。
     */
    private static void parseBlockB(byte[] bl, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(bl, off) != len) throw new UbxParseException("bblock len check");
        int p = off + 4;
        p += 4; // a
        p += 4; // b＝start（块级，帧级 start 为准）
        p += 4; // c＝end
        p += 60; // d[30]
        int count = le(bl, p); p += 4;
        for (int i = 0; i < count; i++) {
            int l1 = le(bl, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (le(bl, p) != l1) throw new UbxParseException("sframe echo");
            if (p + l1 > off + len) throw new UbxParseException("bad aframe2 len " + l1);
            parseServoFrame(bl, p, l1, out, track, voice);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("bblock slack " + (off + len - p));
    }

    /**
     * a.a 帧（pos 指向 echo，len 即 L1'）。
     * servo：角度（a.m 私有发送原文）groups=f()/8（上限20）；
     * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF；不足 20 组余轴保持 0。
     * voice（a/o 原文）：e-blob 为 [8B头][GBK路径][8B尾]，取中段解歌名，不提角度。
     * 发送 time = b*timeBase（以同 track 时间基计，解析后统一回填）。
     */
    private static void parseServoFrame(byte[] f, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(f, off) != len) throw new UbxParseException("sframe len check");
        int p = off + 4;
        p += 4; // a
        int start = le(f, p); p += 4; // b＝mStartTime
        int end = le(f, p); p += 4; // c＝mEndTime
        p += 60; // d[30]
        int ff = le(f, p); p += 4; // f＝组数字节数（voice 时为歌名 blob 长）
        if (ff < 0 || p + ff > off + len) throw new UbxParseException("bad sframe eblob " + ff);
        if (p + ff != off + len) throw new UbxParseException("sframe slack " + (off + len - p - ff));
        int epos = p;
        UbxFile.UbxServoFrame sf = new UbxFile.UbxServoFrame();
        sf.start = start;
        sf.end = end;
        sf.baseB = start;
        sf.baseC = end;
        if (voice) {
            sf.voice = true;
            sf.groupBytes = ff;
            sf.music = extractVoiceMusic(f, epos, ff);
        } else {
            int groups = Math.min(ff / 8, 20);
            for (int i = 0; i < groups; i++) {
                int o = epos + i * 8 + 8;
                if (o + 4 > epos + ff) break;
                sf.angles20[i] = (byte) (f[o] & 0xFF);
            }
            sf.groupBytes = ff;
        }
        int base = track.timeBaseMs > 0 ? track.timeBaseMs
                : (out.timeBaseMs > 0 ? out.timeBaseMs : 0);
        sf.moveTimeHintMs = base > 0 ? start * base : start;
        track.frames.add(sf);
    }

    /**
     * voice 帧歌名（a/o.a() 原文）：e-blob 从第 8B 起取到尾（arraycopy(e,8,dst,0,len-8)，
     * 只去头不去尾），GBK 解码 trim，取最后一个 {@code \} 或 {@code /} 之后为 basename。
     */
    private static String extractVoiceMusic(byte[] f, int epos, int eLen) {
        if (eLen <= 8) return null;
        try {
            String s = new String(f, epos + 8, eLen - 8, "GBK").trim();
            if (s.isEmpty()) return null;
            int slash = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
            String base = (slash >= 0 ? s.substring(slash + 1) : s).trim();
            return base.isEmpty() ? null : base;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * d.f 关键帧表：[echo==len][count][L1 outer][L1内容→d.e 叶]*。
     * d.e 叶为位姿参考（原装全零常见），存 {@code track.keyframes}，不进 {@code track.frames}。
     * 叶绝对偏移：[0..3]=echo, [4..7]=a, [8]=b, [9..12]=c, [13..16]=d,
     * [17..20]=f, [21..21+f)=e, [21+f]=g, [22+f..61+f)=h＝20×LE int16。
     */
    private static void parseFrameTable(byte[] ft, int off, int len, UbxFile out, UbxFile.UbxTrack track) throws UbxParseException {
        if (le(ft, off) != len) throw new UbxParseException("ftable len check");
        int count = le(ft, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(ft, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; }
            if (l1 < 62 || p + l1 > off + len) throw new UbxParseException("bad ftable item " + l1);
            byte[] it = ft;
            int q = p;
            if (le(it, q) != l1) throw new UbxParseException("fleaf len check");
            UbxFile.UbxServoFrame sf = new UbxFile.UbxServoFrame();
            sf.leafA = le(it, q + 4);
            sf.leafB = it[q + 8] & 0xFF;
            sf.leafC = le(it, q + 9);
            sf.leafD = le(it, q + 13);
            int ff = le(it, q + 17);
            if (ff < 0 || 62 + ff > l1) throw new UbxParseException("bad fleaf blob " + ff);
            sf.leafF = ff;
            sf.leafG = it[q + 21 + ff] & 0xFF;
            for (int s = 0; s < 20; s++) {
                int o = q + 22 + ff + s * 2;
                int v = (it[o] & 0xFF) | (it[o + 1] << 8);
                sf.angles20[s] = (byte) (v & 0xFF);
            }
            sf.start = sf.leafC;
            sf.end = sf.leafD;
            sf.groupBytes = 0; // 位姿参考：player 只发 groupBytes>0 的定时帧，此处恒 0 以示区别
            track.keyframes.add(sf);
            track.leafCount++;
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("ftable slack " + (off + len - p));
    }

    private static int le(byte[] b, int p) throws UbxParseException {
        if (p < 0 || p + 4 > b.length) throw new UbxParseException("overrun at " + p);
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
    }

    /** 小端游标（历史遗留：新代码一律用绝对偏移 le()，此cursor仅 parse() 顶层用）。 */
    static final class Cursor {
        final byte[] d;
        int pos;
        Cursor(byte[] d) { this(d, 0, 0); }
        Cursor(byte[] d, int pos, int ignored) { this.d = d; this.pos = pos; }
        int i32() throws UbxParseException {
            int v = le(d, pos);
            pos += 4;
            return v;
        }
        byte[] bytes(int n) throws UbxParseException {
            if (n < 0 || pos + n > d.length) throw new UbxParseException("overrun bytes " + n + " at " + pos);
            byte[] o = new byte[n];
            System.arraycopy(d, pos, o, 0, n);
            pos += n;
            return o;
        }
        void skip(int n) throws UbxParseException {
            if (n < 0 || pos + n > d.length) throw new UbxParseException("overrun skip " + n + " at " + pos);
            pos += n;
        }
    }
}
