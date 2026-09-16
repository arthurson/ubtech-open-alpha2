package com.ubtechinc.alpha.hardware.ubx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * .ubx clean-room 解析器（absolute-offset 版）。
 *
 * <p>格式以原裝 .ubx 實測為準（UBX/actions 全量 202 個 + Alpha2_pc 例檔，
 * motion 尾精確對齊，無 slack；此前“4B slack / 空 marker / X 字段”均為誤讀，已按實物修正）：
 * <pre>
 * 頂層 (f.k.setRawData): [total][ubx-alpha 9B][version≤2][eLen][motion eLen B][cLen][副段skip]
 *   （eLen 與 motion 首字雙寫相等；副段實測恆 cLen=8）
 * motion (d.c): [len][count][item]*，item=[outer len][echo==len][id][fLen…]，
 *   len 只計 echo 起（不含 outer 本身），耗 {@code 4 + len} 對準下一項，尾精確接 motion 尾。
 * track (d.b): [outer][echo][id][fLen outer][f→d.f/d.e位姿參考]
 *              [aLen outer][a→d.d葉列][bLen outer][b→d.a複幀列]（無 X 字段）。
 *   d.d 葉列（私有 a）：[echo==len][count][L1 outer][echo==L1 + 20B頭 + 尾]*
 *     d.d 葉頭：[echo][a][b][c][d]（值多為 -1/-2 sentinel，全量保留）。
 *   d.a 複幀列（私有 b）：[echo==len][count][L1 outer][L1內容→d.a 複幀]*，
 *     嚴格不出本段（舊版以文件尾為界，過鬆）。
 * d.a 複幀（pos 指向 L2'，len 即 L1'）。布局：
 * [len][fLen][f表][a 編排][b 編排][c[50]][d[50]][e][f][g][h][h字節→i]。
 *   是否 servo 看 f（f==0 走 a.d servo 鏈；b 實測 72~229 恆非零，非判據）。
 *   servo 的 i blob + h 長喂 a.d 多軌分發。
 * a.d 分發：[len==self][count][type][len][data]*
 *   type==0 且 len==8 → 時間基 [a][b]（static，a.p 語義；記入本 track，out.timeBaseMs 取首個）
 *   type==1 → data → a.h → a.c 容器 [len==self][count][L1][內容]*
 *     → a.b block [L1 outer][echo][a][b=start][c=end][d[30]][count][a.a]*
 *       → a.a servo 幀 [L1 outer][echo][a][b=start][c=end][d[30]][f][e(f字節)]
 *   type==2/3/4…（燈/音樂等）原樣存 {@code track.blocks} 並提取音樂名/路徑，不丟棄。
 * a.a 幀角度（a.m 私有發送原文）：groups=f()/8（上限20）；
 * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF；不足 20 組餘軸自然為 0。
 * 發送 time 實參 = b*timeBase（f.b.f() 原文 {@code send(f, f.b()*p.a)}，以同 track 時間基計）。
 * 胸串口發送：cmd 3 [20軸byte + short time]（a.m 原文，與 StaticValue.CHEST_CMD_SENDMOTOR 一致）。
 * </pre>
 * </p>
 */
public final class UbxParser {
    private UbxParser() {}

    public static final class UbxParseException extends Exception {
        UbxParseException(String m) { super(m); }
    }

    public static UbxFile parseFile(File f) throws IOException, UbxParseException {
        long flen = f.length();
        // 先擋：空檔/超大檔唔好 new byte[]（>2GB 轉 int 變負數即 NegativeArraySize
        // Error，調用方 catch (Exception) 接唔住會成條 worker 線程死）。
        // 註：下面各段 p+len 檢查係 int 加法，惡意 len 理論上 wrap 得到，
        // 但 Java array 存取全部有 bounds check + le() overrun 一律掟
        // UbxParseException（調用方當 parse failed 回 500，唔會 corrupt），
        // 所以唔逐個改 long，擋 OOM 呢個真係會死 process 嘅就夠。
        if (flen <= 0) throw new UbxParseException("empty file: " + f.getName());
        if (flen > 64L * 1024L * 1024L) throw new UbxParseException("file too large: " + flen);
        byte[] data = new byte[(int) flen];
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
        // 回填：同 track 時間基可能在 servo 幀之後才出現（多 d.a），統一按終態重算。
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
     * 長度語義與全文件統一：每項 [outer len][echo==len][body]，len 不含 outer 本身，
     * 前進 {@code 4 + len}；尾必須精確接住 motion 尾（原裝 202+1 全量如此）。
     * len&lt;=0 的空 marker 僅前向兼容保留（原裝實測無真實空位）。
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
            parseTrack(d, p, itemLen, out); // off 對準 outer（含 echo 校驗，d.b 原文從 L1 起複製）
            p += 4 + itemLen;
        }
        if (p != off + len) throw new UbxParseException("motion slack " + (off + len - p));
    }

    /**
     * track = d.b：[outer][echo==len][id][fLen outer][f→d.f/d.e位姿參考]
     * [aLen outer][a→d.d葉列][bLen outer][b→d.a複幀列]。
     * 原裝並無 X 字段（舊版將 echo 誤作 id、真 id 誤作 X；單 track 時 id 恆 0，
     * 多 track 如 1464835936031 的 0/2/3/4/1）。
     * 各段內容首 echo 即自校驗 len（雙寫慣例）；段尾必須精確接上下一段。
     */
    private static void parseTrack(byte[] t, int off, int declaredLen, UbxFile out) throws UbxParseException {        if (le(t, off) != declaredLen) throw new UbxParseException("track len check");
        if (le(t, off + 4) != declaredLen) throw new UbxParseException("track echo");
        UbxFile.UbxTrack track = new UbxFile.UbxTrack();
        int p = off + 8;
        track.id = le(t, p); p += 4;
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
        track.playOrder = computePlayOrder(track);
        out.tracks.add(track);
    }

    /**
     * 官方鏈式 servo 播放序（smali f/b.f＋f/c.a＋e/c 實證）。
     * 入口＝(a==-1,b==0) 葉（f/e.play 用 track.b() 同款過濾）；逐葉跟鏈：
     * leaf.c 對 d/a.e 選段（d/b.a(I) 搜 e==I），v7＝keyframe[a==leaf.d]
     * 的 d 值、非 0/3 即 skip，c==-2 鏈終止（f/c.a completed 分支）。
     * 段播完按出索引搵下一批 (a==e,b==outIdx) 葉：servo(f==0)→2、
     * f/e(f==1)→0、voice(f==4)→2（鏈續行假設，corpus 內罕見）、
     * e/d(f==2)→True/False 具名 keyframe 的 a 值——全量 217 檔零具名，
     * 一律 -1 斷鏈（跟官方在機上行為一致）。
     * 回 null＝無鏈頭（調用方回退解析序，行為同舊版一字不差）；
     * 回空表＝鏈指明唔播（跟官方播零格）。
     */
    static List<Integer> computePlayOrder(UbxFile.UbxTrack track) {
        List<int[]> entry = new ArrayList<>();
        for (int[] leaf : track.leafRows) {
            if (leaf.length >= 4 && leaf[0] == -1 && leaf[1] == 0) entry.add(leaf);
        }
        if (entry.isEmpty()) return null; // 無鏈頭可跟，回退舊序
        Map<Integer, Integer> keyD = new HashMap<>();
        for (UbxFile.UbxServoFrame kf : track.keyframes) {
            keyD.put(kf.leafA, kf.leafD);
        }
        boolean hasKeys = !keyD.isEmpty();
        List<Integer> order = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<int[]> pending = new ArrayList<>(entry);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 1024) {
            int[] leaf = pending.remove(0);
            String k = leaf[0] + "," + leaf[1] + "," + leaf[2] + "," + leaf[3];
            if (!seen.add(k)) continue; // 防環（官方會 hang 機，呢度斷鏈保命）
            int c = leaf[2];
            int d = leaf[3];
            // c==-2：鏈終止（f/c.a completed 分支：全動作完，後續 track 唔播；
            // 前進 track1、後退 track0（鏈尾）實證，真機片對過）。
            if (c == -2) { track.chainTerminated = true; break; }
            if (hasKeys) {
                Integer v7 = keyD.get(d);
                if (v7 == null || (v7 != 0 && v7 != 3)) continue; // v7 gate
            }
            int dai = -1;
            for (int i = 0; i < track.daAll.size(); i++) {
                if (track.daAll.get(i)[0] == c) { dai = i; break; }
            }
            if (dai < 0) continue; // 官方 return
            int ff = track.daAll.get(dai)[1];
            int outIdx;
            if (ff == 0) {
                order.add(dai); // 官方逐葉獨立 f/b run，重複引用即重播
                outIdx = 2; // f/i.getOutPutIndex
            } else if (ff == 1) {
                outIdx = 0; // f/e.getOutPutIndex
            } else if (ff == 4) {
                outIdx = 2; // voice 鏈續行假設（見上）
            } else if (ff == 2) {
                outIdx = -1; // e/d：零具名 keyframe，斷鏈（見上）
            } else {
                continue;
            }
            int de = track.daAll.get(dai)[0];
            for (int[] lf : track.leafRows) {
                if (lf.length >= 4 && lf[0] == de && lf[1] == outIdx) pending.add(lf);
            }
        }
        return order;
    }

    /**
     * d.d 葉列（d.b 私有 a）：[echo==len][count][L1 outer][echo==L1 + 20B頭 + 尾]*。
     * 葉頭 20B [echo][a][b][c][d]（原裝值多為 -1/-2 sentinel）；全量保留，
     * leafA~D 沿用末項以兼容。
     */
    private static void parseFrameDList(byte[] b, int off, int len, UbxFile.UbxTrack track) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("dlist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // 空 marker：原廠 if>0 同語義
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
     * d.a 複幀列（d.b 私有 b，b-section）：[echo==len][count][L1 outer][L1內容→d.a 複幀]*。
     * 內容首 echo == L1（雙寫）；嚴格不出本段。
     */
    private static void parseFrameAList(byte[] b, int off, int len, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(b, off) != len) throw new UbxParseException("alist len check");
        int count = le(b, off + 4);
        int p = off + 8;
        for (int i = 0; i < count; i++) {
            int l1 = le(b, p); p += 4;
            if (l1 <= 0) { track.skipped++; continue; } // 空 marker：原廠 if>0 同語義
            if (le(b, p) != l1) throw new UbxParseException("aframe echo");
            if (p + l1 > off + len) throw new UbxParseException("bad aframe len " + l1);
            parseFrameA(b, p, l1, track, out);
            p += l1;
        }
        if (p != off + len) throw new UbxParseException("alist slack " + (off + len - p));
    }

    /**
 * d.a 複幀（pos 指向 L2'，len 即 L1'）。布局：
 * [len][fLen][f表][a 編排][b 編排][c[50]][d[50]][e][f][g][h][h字節→i]。
 * 只看 f（f.b.f 分發原文）：f==0 走 f.i/a.d servo 鏈；f==4 走 voice/b（聯網 TTS，
 * 不作解析）；b 實測恆非零，非判據。配樂 mp3 來自 servo 鏈內 type==4 塊（a/d 原文
 * 按 type 建 handler：1→a/h servo，2→a/f，3→a/e，4→a/j/mp3，5→a/i），a/j 內為
 * 同形 a/c→a/b→a.a，幀 e-blob 去頭尾 8B 即 GBK 歌名（a/o.a() 原文）。
     */
    private static void parseFrameA(byte[] f, int off, int declaredLen, UbxFile.UbxTrack track, UbxFile out) throws UbxParseException {
        if (le(f, off) != declaredLen) throw new UbxParseException("aframe len check");
        int itemEnd = off + declaredLen;
        int p = off + 4;
        int fLen = le(f, p); p += 4;
        if (fLen < 0 || p + fLen > itemEnd) throw new UbxParseException("bad aframe ftable");
        p += fLen;
        if (p + 216 > itemEnd) throw new UbxParseException("bad aframe head");
        p += 4; // a（編排字段）
        int fb = le(f, p); p += 4; // b（編排字段；非 servo 判據，原裝實測 72~229）
        p += 100; // c[50]
        p += 100; // d[50]
        int fe = le(f, p); p += 4; // e＝d/a.a()：官方 leaf.c 選段鍵（f/b.f 實證）
        int ff = le(f, p); p += 4; // f：==0 即 servo（f.b.f→f.i 原文）
        p += 4; // g
        int fh = le(f, p); p += 4; // h＝i blob 長
        if (fh < 0 || p + fh > itemEnd) throw new UbxParseException("bad aframe iblob " + fh);
        if (p + fh != itemEnd) throw new UbxParseException("aframe slack " + (itemEnd - p - fh));
        track.framesA++;
        track.frameBValues.add(fb);
        // 全段記 (e,f)（鏈式排序用；非 servo 段 span 記 null）
        track.daAll.add(new int[]{fe, ff});
        if (fh == 0) { track.daSpans.add(null); return; }
        if (ff != 0) { track.nonServoFrames++; track.daSpans.add(null); return; }
        int spanStart = track.frames.size();
        try {
            parseServoChain(f, p, fh, out, track, false);
            track.servoGroups++;
        } catch (UbxParseException e) {
            track.nonServoFrames++;
        }
        // servo d.a 段記 span（鏈式排序＋player 取幀用；半截鏈都記，播嗰陣照出）
        track.daSpans.add(new int[]{spanStart, track.frames.size()});
    }

    /**
     * servo/voice 執行鏈（a.d 多軌分發）：[len==self][count][type][len][data]*（pos 指向 len）。
     * type==0 且 len==8 → 時間基 [a][b]（static，a.p 語義；記入本 track，out 取首個）；
     * type==1 → data → a.h servo 幀；
     * type==4 → data → a/j 配樂鏈（同形 a/c→a/b→a.a，幀標 voice 供配樂調度，不進舵機）；
     * 其餘 type（2/3/5…）原樣保留並提取音樂名/路徑（回退）。
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
                // a/j 配樂鏈：內容即 a/c 容器（失敗則回退到原樣保留，不影響 servo）。
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
     * 從音樂/燈 block 二進制中提取 {@code *.mp3} 文件名與絕對路徑。
     * 原裝如 {@code rap.mp3} 以 UTF-16LE 存名、以 ASCII 存 {@code D:\...\rap.mp3} 全路徑；
     * 此處雙編碼掃描，只讀不改。
     */
    private static void supplementMusic(byte[] data, UbxFile.UbxTrack track) {
        // ASCII/GBK 路徑：按字節回溯（除 NUL/控制符外全收，中文目錄為 GBK 編碼），再按 GBK 解碼。
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
        // 保留盤符/斜槓，basename 另作 musicName
        int slash = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        if (slash >= 0 || t.indexOf(':') >= 0) {
            if (track.musicPath == null) track.musicPath = t;
            String base = t.substring(slash + 1);
            if (base.length() >= 5 && track.musicName == null) track.musicName = base;
        } else if (t.length() >= 5 && track.musicName == null) {
            track.musicName = t;
        }
    }

    /** a.h → a.c 容器：[echo==len][count][L1 outer][內容]*（內容從 echo 起讀）。 */
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
     * [L1 outer][L1內容→a.a]*。
     * a.a servo 幀（off 指向 echo）：[echo][a][b=start][c=end][d[30]][f][e(f字節)]。
     */
    private static void parseBlockB(byte[] bl, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(bl, off) != len) throw new UbxParseException("bblock len check");
        int p = off + 4;
        p += 4; // a
        p += 4; // b＝start（塊級，幀級 start 為準）
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
     * a.a 幀（pos 指向 echo，len 即 L1'）。
     * servo：角度（a.m 私有發送原文）groups=f()/8（上限20）；
     * angles[i]=LE32(e[i*8+8..+4])&amp;0xFF；不足 20 組餘軸保持 0。
     * voice（a/o 原文）：e-blob 為 [8B頭][GBK路徑][8B尾]，取中段解歌名，不提角度。
     * 發送 time = b*timeBase（以同 track 時間基計，解析後統一回填）。
     */
    private static void parseServoFrame(byte[] f, int off, int len, UbxFile out, UbxFile.UbxTrack track, boolean voice) throws UbxParseException {
        if (le(f, off) != len) throw new UbxParseException("sframe len check");
        int p = off + 4;
        p += 4; // a
        int start = le(f, p); p += 4; // b＝mStartTime
        int end = le(f, p); p += 4; // c＝mEndTime
        p += 60; // d[30]
        int ff = le(f, p); p += 4; // f＝組數字節數（voice 時為歌名 blob 長）
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
     * voice 幀歌名（a/o.a() 原文）：e-blob 從第 8B 起取到尾（arraycopy(e,8,dst,0,len-8)，
     * 只去頭不去尾），GBK 解碼 trim，取最後一個 {@code \} 或 {@code /} 之後為 basename。
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
     * d.f 關鍵幀表：[echo==len][count][L1 outer][L1內容→d.e 葉]*。
     * d.e 葉為位姿參考（原裝全零常見），存 {@code track.keyframes}，不進 {@code track.frames}。
     * 葉絕對偏移：[0..3]=echo, [4..7]=a, [8]=b, [9..12]=c, [13..16]=d,
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
            sf.groupBytes = 0; // 位姿參考：player 只發 groupBytes>0 的定時幀，此處恆 0 以示區別
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

    /** 小端游標（歷史遺留：新代碼一律用絕對偏移 le()，此cursor僅 parse() 頂層用）。 */
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
