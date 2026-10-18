#!/usr/bin/env python3
"""
.ubx 步態擬人化重建：去一格格頓挫 (零依賴，不用 gradle)。

背景：1508999860568.ubx（前進）主步態是 6 個關鍵幀循環兩次，
每幀槽位 (s+e)*T 長達 500~900ms，move 完還要 hold (e=5) 先跳下幀，
速度三角波、逐格停 → 目視一格格。手腳對擺相位原檔已有，只是被頓挫遮住。

做法（保守、安全第一，不發明新步幅）：
1. 逐軸 Catmull-Rom 插值（步態循環用 ghost 起步＋seam 真鄰居，首尾不斷速；
   起步/收步用 duplicated endpoint，自然 ease-in/out）。
2. 每個原間隔細分為 5-tick 子幀（track1 每格 100ms；track0/2 每格 250ms；
   v2 試過 1 tick/20ms 太密太惡，棄用），s=5、e=0：move 動滿成格，
   格與格之間零停留，總 tick 守恆（不計 lead-in）。
3. 開場 2 秒 lead-in：由站直 home pose 慢慢沉入起步 pose（v2 無這段，
   首格 50ms 直扯 85 格，部機發狂 reboot）。
4. 2 pass 鄰域平均磨走 CR 過關鍵幀那下曲率突變（步態 wrap，起收段釘死首尾）。
5. 全部角度 clamp 入 SERVO_CALIBRATION（同 blockly-servo-data.js 一致），
   防插值 overshoot 整壞舵機。
5. 非舵機字節（燈 type2/3、ftable、d.d 葉、eblob 非角度字節、aux 副段）
   全部原樣保留；只重算各層長度（outer/echo/count）。

用法：
  python scripts/ubx_walk_humanize.py dump <in.ubx>
  python scripts/ubx_walk_humanize.py humanize <in.ubx> <out.ubx>

驗證：重建後用同檔 parser 回讀（長度/echo/幀數/總時長對比），
另可直編 sdk-module/hardware-direct 嘅 UbxParser（Android SDK android.jar＋JDK8
javac）回讀，並用 scripts/ubx_corpus_order.py 對照官方播序。
"""
import struct
import sys

# 同 app/.../blockly-servo-data.js SERVO_CALIBRATION（min/max/home），
# 插值後 clamp 用，不要超出硬件校準範圍。
CAL = {
    1: (5, 235), 2: (50, 210), 3: (55, 185), 4: (5, 235), 5: (30, 190),
    6: (55, 185), 7: (100, 200), 8: (20, 220), 9: (35, 230), 10: (35, 215),
    11: (100, 190), 12: (40, 140), 13: (20, 220), 14: (10, 205), 15: (25, 205),
    16: (50, 140), 17: (95, 125), 18: (95, 125), 19: (75, 165), 20: (105, 155),
}
SUB_TICKS = 5  # 子幀 tick 數（100ms 級：track1 每格 100ms，track0/2 每格 250ms）
SUB_S = 5      # 子幀 move 部
SUB_E = 1      # 子幀 hold 部（預設跟原版節奏；e=0 零停留只限明確版本）
SMOOTH_PASSES = 0  # 鄰域平均 pass 數（預設不磨；要順滑先開）
LEAD_TICKS = 0  # 開場 lead-in（預設沒有；官方原檔是即時起步）
# 開場 lead-in：由站直 home pose 慢速沉入起步 pose K0（只限明確版本，例 LEAD_TICKS=40
# 即 40 ticks x 50ms = 2000ms；教訓：v2 首格由站直 50ms 直扯 K0 致發狂 reboot）。
# 預設 0 ＝跟官方即時起步。
LEAD_TICKS = 0
# 開場第一格 move tick 數（0＝唔用，跟 SUB_S）。原檔開場第一格 s=20（tb50＝1000ms）
# 由現有姿態慢慢入起步 pose；急扯（例：5 tick＝250ms）會扯到胸板 reboot。
# 只喺有 lead-in 嗰段（＝播放序第一段）生效，會延長總時長 (FIRST_MOVE_TICKS-SUB_S)。
FIRST_MOVE_TICKS = 0
# 手臂唔好一字：servo2/5 = 120 係手臂攤平一字（PC 展示姿）；205/35 = 手臂自然垂低。
# 站定／收步／lead-in 一律垂低手（唔好再出現一字手）。
HOME_POSE = [120, 205, 120, 120, 35, 120, 120, 65, 145, 140,
             120, 120, 175, 95, 100, 120, 120, 120, 120, 120]
# 步數＋變速（只限明確版本參數化；預設 N_CYCLES=2 即原循環數，SPEEDUP=1 即原速）。
# 例 8 步：N_CYCLES=4；變速經 timeBase bake 入檔（整數 ms 檔位：20→13，50→33，
# 約 1.53x；App 變速按鈕播這檔時要留在 1x，不要疊加）。
N_CYCLES = 2
SPEEDUP = 1
# 步態段弦長勻速重採樣（原 25/45-tick 交替一衝一停，拉勻做等速；格數時長不變）。
# 預設 0＝不做。
UNIFORM_RESAMPLE = 0
# 收步：尾循環 blend 入站定 STAND（出廠校準 home 位，最穩陣站姿）。
# 背景：步態尾格是風車手（膊頭 204/37），播完凍住＋慢鬆開，看來似無端擘開；
# 收起入站定就站定收尾（真機片實證）。預設 0＝不做（跟原檔尾格）。
# BLEND_ARMS/BLEND_LEGS：手／腳各用尾幾個循環收（手可早過腳開始收）；
# OUTRO_HOME：id==2 收步段單格手臂按返 HOME（原 O0 擘開 200/40，
# 播不播都好，留在這裡是計時炸彈）。
END_BLEND = 0
BLEND_ARMS = 1
BLEND_LEGS = 1
OUTRO_HOME = 0
# OUTRO_STAND：最尾一段（官方播放序最後嘅 servo d.a）壓成企定 STAND
# （保留格數／時長；由上一段尾 pose 平滑埋位再定住）。專治「行完尾段又慢慢
# 擘開雙手 205/35 停死」——前進 1508999860568 嘅收尾段就係噉。預設 0。
OUTRO_STAND = 0
STAND_POSE = [120, 205, 120, 120, 35, 120, 120, 65, 145, 140,
              120, 120, 175, 95, 100, 120, 120, 120, 120, 120]


def le(d, p):
    return struct.unpack_from("<i", d, p)[0]


def w32(v):
    return struct.pack("<i", v)


def parse_ubx(path):
    d = open(path, "rb").read()
    if le(d, 0) != len(d):
        raise ValueError("total mismatch")
    if d[4:13] != b"ubx-alpha":
        raise ValueError("bad magic")
    ver = le(d, 13)
    e_len = le(d, 17)
    ms = 21
    if le(d, ms) != e_len:
        raise ValueError("motion len check")
    count = le(d, ms + 4)
    p = ms + 8
    tracks = []
    for _ in range(count):
        item_len = le(d, p)
        if le(d, p + 4) != item_len:
            raise ValueError("track echo")
        tend = p + 4 + item_len
        q = p + 8
        tid = le(d, q)
        q += 4
        f_len = le(d, q)
        q += 4
        ftab = d[q:q + f_len]
        q += f_len
        a_len = le(d, q)
        q += 4
        aleaf = d[q:q + a_len]
        q += a_len
        b_len = le(d, q)
        q += 4
        blist_off = q
        blist = d[q:q + b_len]
        q += b_len
        if q != tend:
            raise ValueError("track slack")
        tracks.append({"id": tid, "ftab": ftab, "aleaf": aleaf,
                       "blist_off": blist_off, "blist": blist})
        p += 4 + item_len
    if p != ms + e_len:
        raise ValueError("motion slack")
    aux = d[p:]
    return {"raw": d, "ver": ver, "e_len": e_len, "tracks": tracks, "aux": aux}


def parse_blist_frames(d, bo):
    """回 (da_list, timebases)。da_list 每項 {ftab, a, b, chain_raw, blocks}，
    blocks 只拆 type1，其餘 type 留 raw。"""
    nda = le(d, bo + 4)
    bp = bo + 8
    das = []
    for _ in range(nda):
        l1 = le(d, bp)
        bp += 4
        fp = bp
        if le(d, fp) != l1:
            raise ValueError("aframe echo")
        f_tab_len = le(d, fp + 4)
        base = fp + 8 + f_tab_len
        fa = le(d, base)
        fb = le(d, base + 4)
        ff = le(d, base + 212)
        fh = le(d, base + 220)
        ep = base + 224
        chain = d[ep:ep + fh] if fh else b""
        das.append({"L1": l1, "fp": fp, "ftab": d[fp + 8:fp + 8 + f_tab_len],
                    "head224": d[base:base + 224],
                    "a": fa, "b": fb, "f": ff, "fh": fh, "chain": chain})
        bp += l1
    return das


def parse_chain(chain, fh):
    if fh == 0:
        return []
    if le(chain, 0) != fh:
        raise ValueError("adispatch len check")
    cnt = le(chain, 4)
    pp = 8
    blocks = []
    for _ in range(cnt):
        ty = le(chain, pp)
        ln = le(chain, pp + 4)
        data = chain[pp + 8:pp + 8 + ln]
        blocks.append((ty, data))
        pp += 8 + ln
    return blocks


def parse_trackh(data):
    if le(data, 0) != len(data):
        raise ValueError("ablock len check")
    nblk = le(data, 4)
    hp = 8
    blks = []
    for _ in range(nblk):
        bl1 = le(data, hp)
        hp += 4
        bo = hp
        if le(data, bo) != bl1:
            raise ValueError("bblock echo")
        bstart = le(data, bo + 8)
        bend = le(data, bo + 12)
        nfrm = le(data, bo + 76)
        fp2 = bo + 80
        frms = []
        for _ in range(nfrm):
            fl1 = le(data, fp2)
            fp2 += 4
            fo = fp2
            if le(data, fo) != fl1:
                raise ValueError("sframe echo")
            s = le(data, fo + 8)
            e = le(data, fo + 12)
            ffb = le(data, fo + 76)
            epos = fo + 80
            angs = []
            for gi in range(20):
                o = epos + gi * 8 + 8
                if o + 1 > epos + ffb:
                    break
                angs.append(data[o])
            frms.append({"L1": fl1, "fo": fo, "s": s, "e": e, "ff": ffb,
                         "raw": data[fo:fo + fl1]})
            fp2 += fl1
        blks.append({"L1": bl1, "raw": data[bo:bo + bl1], "start": bstart,
                     "end": bend, "frames": frms})
        hp += bl1
    return blks


def frame_angles(frm):
    data = frm["raw"]
    epos = 80  # 相對 fo
    angs = []
    for gi in range(20):
        o = epos + gi * 8 + 8
        angs.append(data[o])
    return angs


def angles_of_eblob_template(raw):
    return [raw[80 + gi * 8 + 8] for gi in range(20)]


# ---------------------------------------------------------------------------
# 官方播放流程引擎（Alpha2Services 1.1.7.3 反編譯語意；同 web/ubx-parse.js
# buildOfficial 及 sdk UbxParser.computeOfficialPlan 逐格對齊，全語料 202 招
# 驗證一致）。入口 (-1,0) 葉 → block（e==leaf.c）內置 f 表 gate（row[a==leaf.d].d：
# 0/3 播、1 中斷、2 跳過、其餘死路）→ 按 block f 分派：
#   f0 servo（timebase static 後蓋前）；f1 跳 track（g＝目標 id，當前鏈完）；
#   f2 subtype2 Loop（(trk,block) 快取 i/g/h，i>=h 走 d==2 行否則 "Loop" 行）；
#   f4 TTS（d==2 行）；c==-2：trk0＝全完，否則跨 track 續接（最後一條有
#   block.g==當前 id 嘅 track，marker row[d==2].a）。
# ---------------------------------------------------------------------------


def lead_int(s):
    """JS parseInt 近似：讀最前連續數字。"""
    n = 0
    seen = False
    for ch in s:
        if ch.isdigit():
            n = n * 10 + int(ch)
            seen = True
        else:
            break
    return n if seen else 0


def parse_row(ftab, pp):
    """block 內置 f 表一行（pp 指住 echo）。class com.ubtechinc.util.d.e。"""
    a = le(ftab, pp + 4)
    b = ftab[pp + 8]
    c = le(ftab, pp + 9)
    dd = le(ftab, pp + 13)
    f = le(ftab, pp + 17)
    goff = pp + 21 + f
    name = ""
    for i in range(20):
        o = goff + 1 + i * 2
        if o + 2 > len(ftab):
            break
        v = struct.unpack_from("<H", ftab, o)[0]
        if v:
            name += chr(v)
    return {"a": a, "b": b, "c": c, "d": dd, "f": f, "name": name.strip()}


def parse_block_rows(ftab):
    rows = []
    if len(ftab) >= 8 and le(ftab, 0) == len(ftab):
        cnt = le(ftab, 4)
        pp = 8
        for _ in range(cnt):
            if pp + 4 > len(ftab):
                break
            l1 = le(ftab, pp)
            pp += 4
            if l1 <= 0:
                continue
            rows.append(parse_row(ftab, pp))
            pp += l1
    return rows


def parse_loop_blob(blob):
    """Loop block（f==2 subtype2）資料：照 com.ubtechinc.util.e.a.setRawData 原文。"""
    if len(blob) < 12 or le(blob, 0) != len(blob) or le(blob, 4) != 2:
        return None
    ib = le(blob, 8)
    i2 = 12
    f = g = h = 0
    if ib > 0 and i2 + ib <= len(blob):
        f = lead_int(blob[i2:i2 + ib].decode("latin1"))
        i2 += ib
    if i2 + 4 <= len(blob) and le(blob, i2) > 0:
        g = lead_int(blob[i2 + 4:i2 + 4 + ib].decode("latin1"))
    i3 = i2 + 4 + ib
    if i3 + 4 <= len(blob):
        ib2 = le(blob, i3)
        if ib2 > 0:
            h = lead_int(blob[i3 + 4:i3 + 4 + ib2].decode("latin1"))
    return {"f": f, "g": g, "h": h}


def voice_music(b):
    if len(b) <= 8:
        return None
    s = b[8:].decode("gbk", errors="ignore").strip()
    if not s:
        return None
    k = max(s.rfind("\\"), s.rfind("/"))
    base = (s[k + 1:] if k >= 0 else s).strip()
    return base or None


def parse_voice_windows(data):
    """type4 視窗（同 parse_trackh 結構，幀 payload 係歌名）。"""
    if le(data, 0) != len(data):
        raise ValueError("voice block len check")
    nblk = le(data, 4)
    hp = 8
    out = []
    for _ in range(nblk):
        bl1 = le(data, hp)
        hp += 4
        bo = hp
        if le(data, bo) != bl1:
            raise ValueError("vbblock echo")
        nfrm = le(data, bo + 76)
        fp = bo + 80
        for _ in range(nfrm):
            fl1 = le(data, fp)
            fp += 4
            fo = fp
            if le(data, fo) != fl1:
                raise ValueError("vsframe echo")
            s = le(data, fo + 8)
            e = le(data, fo + 12)
            ffb = le(data, fo + 76)
            epos = fo + 80
            out.append({"s": s, "e": e, "music": voice_music(data[epos:epos + ffb])})
            fp += fl1
        hp += bl1
    return out


def track_detail(d, t):
    """回 (leaves, blocks)。blocks 各含 e/f/g/h、內置 f 表 rows、tb、windows、
    voice、sub、loop（f==2 subtype2）。"""
    leaves = []
    ad = t["aleaf"]
    if len(ad) >= 8 and le(ad, 0) == len(ad):
        cnt = le(ad, 4)
        ap = 8
        for _ in range(cnt):
            l1 = le(ad, ap)
            ap += 4
            if l1 <= 0:
                continue
            leaves.append({"a": le(ad, ap + 4), "b": le(ad, ap + 8),
                           "c": le(ad, ap + 12), "d": le(ad, ap + 16)})
            ap += l1
    blocks = []
    bo = t["blist_off"]
    bp = bo + 8
    for _ in range(le(d, bo + 4)):
        l1 = le(d, bp)
        bp += 4
        fp = bp
        ftl = le(d, fp + 4)
        base = fp + 8 + ftl
        blk = {"e": le(d, base + 208), "f": le(d, base + 212),
               "g": le(d, base + 216), "h": le(d, base + 220),
               "rows": parse_block_rows(d[fp + 8:fp + 8 + ftl]),
               "tb": 0, "windows": [], "voice": [], "sub": 0, "loop": None}
        if blk["h"] > 0:
            chainb = d[base + 224:base + 224 + blk["h"]]
            if blk["f"] == 0:
                for (ty, data) in parse_chain(chainb, blk["h"]):
                    if ty == 0 and len(data) == 8:
                        blk["tb"] = le(data, 0)  # 後蓋前
                    elif ty == 1:
                        for b2 in parse_trackh(data):
                            blk["windows"].extend(b2["frames"])
                    elif ty == 4:
                        blk["voice"].extend(parse_voice_windows(data))
            elif blk["f"] == 2:
                blk["sub"] = le(d, base + 228)
                if blk["sub"] == 2:
                    blk["loop"] = parse_loop_blob(chainb)
            elif blk["f"] == 4:
                blk["sub"] = le(d, base + 228)
        blocks.append(blk)
        bp += l1
    return leaves, blocks


def official_plan(m):
    """官方流程模擬 → (segs, voices, total_ms, logs)。
    segs：播序 servo 段 [{track, idx, ticks, tb, frames}]；voices：[{t,bound,music}]；
    total_ms＝servo 時間軸總長；logs＝流程註記（LOOP/JUMP/CROSS/SKIP…）。"""
    d = m["raw"]
    tracks = []
    for t in m["tracks"]:
        leaves, blocks = track_detail(d, t)
        tracks.append({"id": t["id"], "leaves": leaves, "blocks": blocks})

    def find_track(tid):
        for tr in tracks:
            if tr["id"] == tid:
                return tr
        return None

    t0 = find_track(0)
    segs = []
    voices = []
    logs = []
    total = 0
    vt = 0
    T = 50
    loop_cache = {}
    queue = []
    if t0:
        for lf in t0["leaves"]:
            if lf["a"] == -1 and lf["b"] == 0:
                queue.append((t0, lf))
    guard = 0
    while queue and guard < 500:
        guard += 1
        track, leaf = queue.pop(0)
        if leaf["c"] == -2:
            if track["id"] == 0:
                logs.append("STOP(-2 id0)")
                break
            nt = None
            nb = None
            for tr in tracks:
                for blk in tr["blocks"]:
                    if blk["g"] == track["id"]:
                        nt, nb = tr, blk
                        break
            if nt is None:
                logs.append("DEAD(-2 no next track)")
                break
            r2 = None
            for row in nb["rows"]:
                if row["d"] == 2:
                    r2 = row
                    break
            av = r2["a"] if r2 else -1
            queue = [(nt, lf2) for lf2 in nt["leaves"]
                     if lf2["a"] == nb["e"] and lf2["b"] == av]
            logs.append("CROSS→trk%d" % nt["id"])
            continue
        blk = None
        idx = -1
        for bi, b3 in enumerate(track["blocks"]):
            if b3["e"] == leaf["c"]:
                blk, idx = b3, bi
                break
        if blk is None:
            logs.append("DEAD(no block e=%d)" % leaf["c"])
            continue
        row = None
        for r in blk["rows"]:
            if r["a"] == leaf["d"]:
                row = r
                break
        iC = row["d"] if row else -1

        def find_row_d2():
            for x in blk["rows"]:
                if x["d"] == 2:
                    return x
            return None

        def push_next(oi):
            for l3 in track["leaves"]:
                if l3["a"] == blk["e"] and l3["b"] == oi:
                    queue.append((track, l3))

        if iC in (0, 3):
            if blk["f"] == 0:
                if blk["tb"]:
                    T = blk["tb"]
                ticks = 0
                for fr in blk["windows"]:
                    ticks += fr["s"] + fr["e"]
                segs.append({"track": track["id"], "idx": idx, "ticks": ticks,
                             "tb": T, "frames": blk["windows"]})
                total += ticks * T
                for vf in blk["voice"]:
                    voices.append({"t": vt, "bound": vf["s"] * T,
                                   "music": vf["music"]})
                    vt += (vf["s"] + vf["e"]) * T
                logs.append("PLAY trk%d e%d" % (track["id"], blk["e"]))
                push_next(2)
            elif blk["f"] == 1:
                nt2 = find_track(blk["g"])
                logs.append("JUMP(f1) trk%d→trk%d" % (track["id"], blk["g"]))
                if nt2:
                    for l4 in nt2["leaves"]:
                        if l4["a"] == -1 and l4["b"] == 0:
                            queue.append((nt2, l4))
            elif blk["f"] == 2:
                if blk["sub"] == 2 and blk["loop"]:
                    key = (track["id"], blk["e"])
                    st = loop_cache.get(key)
                    if st is None:
                        st = {"i": blk["loop"]["f"], "g": blk["loop"]["g"],
                              "h": blk["loop"]["h"]}
                        loop_cache[key] = st
                    if st["i"] >= st["h"]:
                        dr = find_row_d2()
                        push_next(dr["a"] if dr else -1)
                        logs.append("LOOPEND trk%d e%d" % (track["id"], blk["e"]))
                    else:
                        rl = None
                        for x in blk["rows"]:
                            if x["name"] == "Loop":
                                rl = x
                                break
                        push_next(rl["a"] if rl else -1)
                        st["i"] += st["g"]
                        logs.append("LOOP trk%d e%d i=%d/%d"
                                    % (track["id"], blk["e"], st["i"], st["h"]))
                elif blk["sub"] == 1:
                    rt = None
                    for x in blk["rows"]:
                        if x["name"] == "True":
                            rt = x
                            break
                    push_next(rt["a"] if rt else -1)
                    logs.append("COND(True) trk%d e%d" % (track["id"], blk["e"]))
                else:
                    logs.append("F2sub%d（唔支持）" % blk["sub"])
            elif blk["f"] == 4:
                d4 = find_row_d2()
                push_next(d4["a"] if d4 else -1)
                logs.append("TTS trk%d e%d" % (track["id"], blk["e"]))
            else:
                logs.append("F%d（無 dispatch）" % blk["f"])
        elif iC == 1:
            logs.append("GATE1（中斷分支）")
        elif iC == 2:
            logs.append("SKIP(iC2)")
        else:
            logs.append("DEAD-GATE iC=%d e=%d" % (iC, blk["e"]))
    return segs, voices, total, logs


def build_servo_frame(template_raw, s, e, angles):
    """template_raw 為原幀 fo 起 L1 字節；只改 s/e/20 軸角度字節，其餘照抄。"""
    b = bytearray(template_raw)
    struct.pack_into("<i", b, 8, s)
    struct.pack_into("<i", b, 12, e)
    for gi in range(20):
        o = 80 + gi * 8 + 8
        v = max(0, min(255, int(round(angles[gi]))))
        lo, hi = CAL[gi + 1]
        v = max(lo, min(hi, v))
        b[o] = v
    return bytes(b)


def build_block(template_raw, frame_bytes_list, win=None):
    """template_raw 為原 block bo 起 bl1 字節；只換幀列，更新 echo/count。
    win=(start,end) 另計 block 窗口（v4 步數加倍後原窗口不合用；player 忽略，純記帳）。"""
    head = template_raw[:80]  # echo+a+b+c+d[30]+count
    body = b"".join(w32(len(f)) + f for f in frame_bytes_list)
    new_l1 = 80 + len(body)
    out = bytearray()
    out += w32(new_l1)  # echo（调用方再寫 outer）
    out += head[4:76]   # a/b/c/d[30]（72B）
    if win is not None:
        struct.pack_into("<i", out, 8, win[0])   # b=start
        struct.pack_into("<i", out, 12, win[1])  # c=end
    out += w32(len(frame_bytes_list))  # count
    out += body
    assert len(out) == new_l1
    return bytes(out)


def build_trackh(block_contents):
    body = b"".join(w32(len(c)) + c for c in block_contents)
    new_len = 8 + len(body)
    return w32(new_len) + w32(len(block_contents)) + body


def build_chain(items):
    """items: list of (type, data)。"""
    body = b"".join(w32(t) + w32(len(dd)) + dd for t, dd in items)
    new_len = 8 + len(body)
    return w32(new_len) + w32(len(items)) + body


def build_da(template, new_chain):
    """template 為 parse_blist_frames 的 da 項；重建整個 d.a（含 outer+echo）。"""
    ftab = template["ftab"]
    head = bytearray(template["head224"])
    struct.pack_into("<i", head, 220, len(new_chain))  # h = i-blob 長要跟著改
    new_l1 = 8 + len(ftab) + 224 + len(new_chain)
    out = w32(new_l1) + w32(new_l1) + w32(len(ftab)) + ftab + bytes(head) + new_chain
    return out


PATH_HOLDER = [None]


def catmull(p0, p1, p2, p3, t):
    t2 = t * t
    t3 = t2 * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t +
                  (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                  (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def smooth_seq(poses, cyclic, passes=SMOOTH_PASSES):
    """鄰域平均（半徑 1）：CR 保過關鍵幀但曲率突變，這裡磨埋它。
    cyclic 步態 wrap 首尾；起收段首尾兩格釘死（保出入 pose 精確銜接）。"""
    n = len(poses)
    for _ in range(passes):
        out = []
        for i in range(n):
            if not cyclic and (i == 0 or i == n - 1):
                out.append(poses[i])
                continue
            a = poses[(i - 1) % n]
            b = poses[i]
            c = poses[(i + 1) % n]
            out.append([(a[k] + 2 * b[k] + c[k]) / 4.0 for k in range(20)])
        poses = out
    return poses


def smooth_pairs(keys2, ticks, sub_ticks=None):
    """keys2 首為 ghost（上一 pose），ticks[i] 為 keys2[i]→keys2[i+1] 間隔 tick。
    每幀即一次過渡（含首幀 ghost→K0），故 n keys 對 n 間隔，總時長守恆。
    sub_ticks：每子幀 tick（預設 SUB_TICKS；=1 取 1-tick 精細採樣供勻速重參用）。"""
    assert len(keys2) == len(ticks) + 1, (len(keys2), len(ticks))
    st = sub_ticks if sub_ticks else SUB_TICKS
    poses = []
    for i in range(len(ticks)):
        nsub = ticks[i] // st
        assert ticks[i] % st == 0, (ticks, st)
        p0 = keys2[max(0, i - 1)]
        p1 = keys2[i]
        p2 = keys2[i + 1]
        p3 = keys2[min(len(keys2) - 1, i + 2)]
        for k in range(1, nsub + 1):
            t = k / nsub
            poses.append([catmull(p0[a], p1[a], p2[a], p3[a], t)
                          for a in range(20)])
    return poses


def resample_uniform(fine, n_out):
    """按關節空間弦長勻速重採樣：fine 為密採樣 pose 列（含起終點），
    回 n_out 個 pose（不含起點、含終點），總時長不變、每格走等距，
    消滅原 25/45-tick 交替造成的一衝一停節奏。"""
    import math
    cum = [0.0]
    for i in range(1, len(fine)):
        s = 0.0
        for a in range(20):
            dd = fine[i][a] - fine[i - 1][a]
            s += dd * dd
        cum.append(cum[-1] + math.sqrt(s))
    total = cum[-1]
    if total <= 0:
        return [list(fine[-1]) for _ in range(n_out)]
    out = []
    j = 0
    for k in range(1, n_out + 1):
        target = total * k / n_out
        while j + 1 < len(cum) - 1 and cum[j + 1] < target:
            j += 1
        span = cum[j + 1] - cum[j]
        t = 0.0 if span <= 0 else (target - cum[j]) / span
        out.append([fine[j][a] + (fine[j + 1][a] - fine[j][a]) * t
                    for a in range(20)])
    return out


def smooth_cyclic(ckeys, cticks):
    """步態循環：n keys 對 n 間隔，wrap 首尾，節奏不斷。"""
    assert len(ckeys) == len(cticks)
    n = len(ckeys)
    poses = []
    for i in range(n):
        nsub = cticks[i] // SUB_TICKS
        assert cticks[i] % SUB_TICKS == 0, cticks
        p0 = ckeys[(i - 1) % n]
        p1 = ckeys[i]
        p2 = ckeys[(i + 1) % n]
        p3 = ckeys[(i + 2) % n]
        for k in range(1, nsub + 1):
            t = k / nsub
            poses.append([catmull(p0[a], p1[a], p2[a], p3[a], t)
                          for a in range(20)])
    return poses


def dump(path):
    m = parse_ubx(path)
    d = m["raw"]
    print("file:", path, "size:", len(d), "version:", m["ver"])
    print("tracks:", len(m["tracks"]), "aux:", len(m["aux"]), "bytes")
    total_ms = 0
    for t in m["tracks"]:
        das = parse_blist_frames(d, t["blist_off"])
        print(f"-- track id={t['id']} da={len(das)}")
        for di, da in enumerate(das):
            if da["fh"] == 0 or da["f"] != 0:
                print(f"   d.a[{di}] non-servo f={da['f']} hlen={da['fh']}")
                continue
            blks = parse_chain(da["chain"], da["fh"])
            tb = [(le(b[1], 0), le(b[1], 4)) for b in blks if b[0] == 0]
            print(f"   d.a[{di}] servo timeBase={tb} blocks:")
            for b in blks:
                if b[0] != 1:
                    print(f"     type{b[0]} len={len(b[1])}")
                    continue
                for blk in parse_trackh(b[1]):
                    frms = [(f["s"], f["e"]) for f in blk["frames"]]
                    print(f"     blk start={blk['start']} end={blk['end']} "
                          f"n={len(blk['frames'])} se={frms}")


def humanize(in_path, out_path):
    m = parse_ubx(in_path)
    d = m["raw"]
    # 先算播放序上每個 servo d.a 的首/尾 pose，供 ghost 過渡（跨 track 跳步順滑）。
    # 播放序用官方流程（leaf 鏈）計——parse 序≠播序嘅檔（例：前進）ghost 先唔會撈亂。
    segs, voices, total, logs = official_plan(m)
    ti_of = {t["id"]: i for i, t in enumerate(m["tracks"])}
    order = list(dict.fromkeys((ti_of[sg["track"]], sg["idx"]) for sg in segs))
    parsed = {}  # (ti, di) -> da（同一物件，id 穩定；包括唔會播嘅段照重建）
    for ti, t in enumerate(m["tracks"]):
        for di, da in enumerate(parse_blist_frames(d, t["blist_off"])):
            if da["fh"] > 0 and da["f"] == 0:
                parsed[(ti, di)] = da
    order = [k for k in order if k in parsed]
    lasts = {}
    for key in order:
        da = parsed[key]
        for (ty, data) in parse_chain(da["chain"], da["fh"]):
            if ty == 1:
                frms = [f for blk in parse_trackh(data) for f in blk["frames"]]
                lasts[key] = angles_of_eblob_template(frms[-1]["raw"])
    ghost_of = {}
    prev_last = None
    first_key = order[0] if order else None
    for key in order:
        ghost_of[key] = prev_last  # 首個為 None（lead-in 由 HOME 起步）
        prev_last = lasts[key]
    pos_in_order = {key: i for i, key in enumerate(order)}
    outro_key = order[-1] if order else None
    # ghost 用重建後尾 pose（串行跟進）：blend 改尾當時，下一段不可以再用原尾
    # （否則 track1 收 HOME、track2 由舊 W5 起步，中間斷一截——實證捉到）。
    rebuilt_end = {}
    # 按播放序 pre-rebuild（ghost 要跟播序串行；檔案序 ≠ 播序，例：前進嘅
    # 收尾段喺檔案排前但播最後，唔可以早過 gait 重建）。
    rebuilt = {}  # (ti, di) -> (new_type1, new_ticks)
    for key in order:
        ti_k, di_k = key
        da_k = parse_blist_frames(d, m["tracks"][ti_k]["blist_off"])[di_k]
        blks_k = parse_chain(da_k["chain"], da_k["fh"])
        oi = pos_in_order[key]
        if oi > 0 and order[oi - 1] in rebuilt_end:
            ghost = rebuilt_end[order[oi - 1]]
            lead = 0
        else:
            ghost = ghost_of.get(key)
            lead = LEAD_TICKS if key == first_key else 0
        for (ty_k, data_k) in blks_k:
            if ty_k == 1:
                nt1, nt2, last_pose = rebuild_type1(
                    data_k, ghost, lead, m["tracks"][ti_k]["id"],
                    stand_outro=bool(OUTRO_STAND and key == outro_key))
                rebuilt[key] = (nt1, nt2)
                if last_pose is not None:
                    rebuilt_end[key] = last_pose
                break
    new_tracks = []
    for ti, t in enumerate(m["tracks"]):
        das = parse_blist_frames(d, t["blist_off"])
        new_das = []
        for di, da in enumerate(das):
            if da["fh"] == 0 or da["f"] != 0:
                # 非舵機 d.a：原樣保留（無 servo 鏈可改）
                new_das.append(rebuild_da_verbatim(d, da))
                continue
            blks = parse_chain(da["chain"], da["fh"])
            # 原內容 tick（v4 cap 用）：type1 全幀 (s+e) 之和
            orig_ticks = 0
            for (ty0, data0) in blks:
                if ty0 == 1:
                    for blk0 in parse_trackh(data0):
                        for f0 in blk0["frames"]:
                            orig_ticks += f0["s"] + f0["e"]
            new_type1 = None
            new_ticks = orig_ticks
            if (ti, di) in rebuilt:
                new_type1, new_ticks = rebuilt[(ti, di)]
            else:
                # 唔喺播放序（死段）：自我起步重建，保持檔案完整
                for (ty, data) in blks:
                    if ty == 1:
                        new_type1, new_ticks, _ = rebuild_type1(data, None, 0, t["id"])
                        break
            new_items = []
            for (ty, data) in blks:
                if ty == 1:
                    new_items.append((ty, new_type1))
                elif ty == 0 and len(data) == 8:
                    # v4 變速：timeBase 照 SPEEDUP 縮（20→13，50→33）
                    a0 = le(data, 0)
                    # loop-cap 跟內容加大：新 cap＝新內容＋原 margin（原 cap－原內容）。
                    # 不跟會官方播半腰斬（f/i$a 線程 b<cap 停，8 步檔實證）。
                    margin = le(data, 4) - orig_ticks
                    if margin < 0:
                        margin = 0
                    new_cap = new_ticks + margin
                    new_items.append((ty, w32(max(1, round(a0 / SPEEDUP))) + w32(new_cap)))
                else:
                    new_items.append((ty, data))  # type2/3 原樣
            new_chain = build_chain(new_items)
            new_das.append(build_da(da, new_chain))
        # 重建 b-list
        body = b"".join(new_das)
        new_blen = 8 + len(body)
        new_blist = w32(new_blen) + w32(len(new_das)) + body
        # 重建 track（body = echo4+id4+fLen4+ftab+aLen4+aleaf+bLen4+blist）
        ftab, aleaf = t["ftab"], t["aleaf"]
        new_decl = 20 + len(ftab) + len(aleaf) + len(new_blist)
        new_track = (w32(new_decl) + w32(new_decl) + w32(t["id"]) +
                     w32(len(ftab)) + ftab + w32(len(aleaf)) + aleaf +
                     w32(len(new_blist)) + new_blist)
        new_tracks.append(new_track)
    body = b"".join(new_tracks)
    new_elen = 8 + len(body)
    motion = w32(new_elen) + w32(len(new_tracks)) + body
    head = w32(0) + b"ubx-alpha" + w32(m["ver"]) + w32(new_elen)
    aux = m["aux"]
    total = len(head) + len(motion) + len(aux)
    out = w32(total) + head[4:] + motion + aux
    open(out_path, "wb").write(out)
    # 回讀驗證
    m2 = parse_ubx(out_path)
    print("wrote:", out_path, "size:", len(out))
    print("re-parse ok, tracks:", len(m2["tracks"]))


def rebuild_da_verbatim(d, da):
    fp = da["fp"]
    return d[fp - 4:fp + da["L1"]]  # outer+內容


def rebuild_type1(data, ghost_prev, lead_ticks=0, tid=None, stand_outro=False):
    blks = parse_trackh(data)
    # 收集全 d.a 幀序（player 播放序即此序）；每幀即由上一 pose 過渡到本幀。
    seq = []
    for blk in blks:
        for f in blk["frames"]:
            seq.append(f)
    keys = [angles_of_eblob_template(f["raw"]) for f in seq]
    ticks = [f["s"] + f["e"] for f in seq]
    n = len(keys)
    # 步態循環判定：6 格一循環、重複多次（原檔 12 幀 = 2 次）。
    # v4 擴到 N_CYCLES 次：keys2=[ghost]+cycle*N（seam 是內點，鄰居正確不斷速）。
    cyc = None
    if n >= 6 and n % 6 == 0 and all(k == keys[i % 6] for i, k in enumerate(keys)):
        cyc = (keys[:6], ticks[:6])
    fm = 0
    if cyc is not None:
        ck, ct = cyc
        ek = ck * N_CYCLES
        et = ct * N_CYCLES
        new_ticks = sum(et)  # cap 用：擴充後總內容 tick
        g = ghost_prev if ghost_prev is not None else ck[-1]
        keys2 = [g] + ek
        if UNIFORM_RESAMPLE:
            # 密採樣（1-tick）→ 弦長勻速重取樣 → 輕磨：同格數同總時長，速度拉勻
            fine = smooth_seq(smooth_pairs(keys2, et, 1), False)
            n_out = sum(et) // SUB_TICKS
            new_poses = smooth_seq(resample_uniform(fine, n_out), False)
        else:
            new_poses = smooth_seq(smooth_pairs(keys2, et), True)
        if END_BLEND:
            # 收步：手腳分開收（手早腳遲都得）；起點無縫銜接，終點＝STAND。
            cyc_per = len(new_poses) // N_CYCLES
            assert cyc_per * N_CYCLES == len(new_poses), (len(new_poses), N_CYCLES)
            for span_cyc, do_legs in ((BLEND_ARMS, False), (BLEND_LEGS, True)):
                n = min(span_cyc, N_CYCLES) * cyc_per
                base = len(new_poses) - n
                for j in range(n):
                    t = (j + 1) / n
                    te = t * t * (3 - 2 * t)
                    row = new_poses[base + j]
                    if do_legs:
                        new_poses[base + j] = [(1 - te) * a + te * b
                                               for a, b in zip(row, STAND_POSE)]
                    else:
                        new_poses[base + j] = [(1 - te) * a + te * b if k < 6 else a
                                               for k, (a, b) in enumerate(zip(row, STAND_POSE))]
        # 分回原 block 數（tick 均分；窗口按官方格式重計：end=start+ticks，
        # 下一格 start=end+1，原檔例 0-190/191-381；player 忽略窗口，
        # 但 alpha2services 為準就要啱）
        per = len(new_poses) // len(blks)
        groups = [new_poses[i * per:(i + 1) * per] for i in range(len(blks))]
        bt_total = sum(et)
        per_ticks = bt_total // len(blks)
        wins = []
        ws = blks[0]["start"] if blks else 0
        for i in range(len(blks)):
            wins.append((ws, ws + per_ticks))
            ws += per_ticks + 1
        tmpl_frames = [b["frames"] for b in blks]
    else:
        if stand_outro:
            # 收尾段壓成企定：保留格數／時長，全部 key 換做 STAND，
            # 由上一段（gait）尾 pose 平滑埋位；唔再擘開手。
            keys = [list(STAND_POSE) for _ in keys]
        if OUTRO_HOME and tid == 2 and len(keys) == 1:
            # 收步段單格：手臂按返 HOME（原 O0 擘開，播出來就是尾段十字；
            # 腳本來已站定，成格置 HOME 等於站定 1.25 秒，時長不變）
            keys = [list(STAND_POSE)]
        if lead_ticks > 0:
            # 開場：HOME→K0 用 lead_ticks 慢速沉入，取代原首格 slot
            # （v2 血淚：不加這段會發狂 reboot）
            assert ghost_prev is None
            keys2 = [HOME_POSE] + keys
            ticks2 = [lead_ticks] + ticks[1:]
        else:
            ghost = ghost_prev if ghost_prev is not None else keys[0]
            keys2 = [ghost] + keys
            ticks2 = ticks
        new_poses = smooth_seq(smooth_pairs(keys2, ticks2), False)
        # 按每 blk tick 比例分組（lead-in 取代首 blk 首格 slot；總 tick 守恆），
        # 窗口按官方格式重計（end=start+ticks，下一格 start=end+1；
        # 原檔例 blk0 0-20、blk1 21-51）
        groups = []
        wins = []
        pos = 0
        ws = blks[0]["start"] if blks else 0
        for bi, blk in enumerate(blks):
            bt = sum(f["s"] + f["e"] for f in blk["frames"])
            if bi == 0 and lead_ticks > 0:
                bt += lead_ticks - (ticks[0] if ticks else 0)
            cnt = bt // SUB_TICKS
            groups.append(new_poses[pos:pos + cnt])
            pos += cnt
            wins.append((ws, ws + bt))
            ws += bt + 1
        assert pos == len(new_poses), (pos, len(new_poses))
        tmpl_frames = [b["frames"] for b in blks]
        wins = [None] * len(blks)
        new_ticks = sum(ticks2)
        if lead_ticks > 0 and FIRST_MOVE_TICKS > SUB_S:
            fm = FIRST_MOVE_TICKS
            new_ticks += fm - SUB_S
    new_contents = []
    for blk, poses, tfrms, win in zip(blks, groups, tmpl_frames, wins):
        tmpl = tfrms[-1]["raw"]  # 該 blk 尾幀模板（eblob 雜湊字節同構）
        fbytes = []
        for pi, p in enumerate(poses):
            if fm and blk is blks[0] and pi == 0:
                fbytes.append(build_servo_frame(tmpl, fm, 0, p))
            else:
                fbytes.append(build_servo_frame(tmpl, SUB_S, SUB_E, p))
        new_contents.append(build_block(blk["raw"], fbytes, win))
    last_pose = new_poses[-1] if new_poses else None
    return build_trackh(new_contents), new_ticks, last_pose


def chain(path):
    """官方模型模擬播序：全局 leaf 鏈（同 Java computeOfficialPlan／web
    buildOfficial 同規則），印每段 tick＋時長＋全檔總時長。
    原檔 vs 改檔對比用（步數/時長差異一目了然）。"""
    m = parse_ubx(path)
    segs, voices, total, logs = official_plan(m)
    print("chain:", path)
    parts = []
    nframes = 0
    for sg in segs:
        nframes += len(sg["frames"])
        parts.append("trk%d[%d](%dt/%dms/%df)"
                     % (sg["track"], sg["idx"], sg["ticks"],
                        sg["ticks"] * sg["tb"], len(sg["frames"])))
    print("    order: " + (" > ".join(parts) if parts else "( silent )"))
    print("    FILE total: %dms (frames=%d, voice=%d)"
          % (total, nframes, len(voices)))
    jumps = sum(1 for x in logs if x.startswith(("JUMP", "CROSS", "LOOP", "COND")))
    if jumps:
        print("    flow: " + " | ".join(x for x in logs if x.startswith(
            ("JUMP", "CROSS", "LOOP", "COND", "STOP", "TTS"))))


def droptrack(in_path, out_path, keep_ids):
    """抌走指定外 track（試驗用：驗證收步段是否 spread 元兇）。
    只重算 motion 層長度，保留段一字不改；keep_ids 以外全抌。"""
    d = open(in_path, "rb").read()
    ms = 21
    e_len = le(d, 17)
    assert le(d, ms) == e_len
    count = le(d, ms + 4)
    p = ms + 8
    kept = []
    for _ in range(count):
        item_len = le(d, p)
        assert le(d, p + 4) == item_len
        tid = le(d, p + 8)
        if tid in keep_ids:
            kept.append(d[p:p + 4 + item_len])
        p += 4 + item_len
    assert p == ms + e_len, (p, ms + e_len)
    new_elen = 8 + len(b"".join(kept))
    motion = w32(new_elen) + w32(len(kept)) + b"".join(kept)
    aux = d[ms + e_len:]
    head = bytearray(d[:ms])
    struct.pack_into("<i", head, 17, new_elen)
    total = len(head) + len(motion) + len(aux)
    struct.pack_into("<i", head, 0, total)
    open(out_path, "wb").write(bytes(head) + motion + aux)
    m2 = parse_ubx(out_path)
    print("droptrack keep=%s tracks=%d size=%d" %
          (sorted(keep_ids), len(m2["tracks"]), total))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    cmd = sys.argv[1]
    if cmd == "dump":
        dump(sys.argv[2])
    elif cmd == "chain":
        chain(sys.argv[2])
    elif cmd == "droptrack":
        droptrack(sys.argv[2], sys.argv[3],
                  set(int(x) for x in sys.argv[4:]))
    elif cmd == "humanize":
        # humanize <in> <out> [KEY=VAL ...]：覆寫模組參數
        # （例：8 步普通版 N_CYCLES=4 SPEEDUP=1 SUB_S=5 SUB_E=1 SMOOTH_PASSES=0 LEAD_TICKS=0）
        for arg in sys.argv[4:]:
            k, _, v = arg.partition("=")
            try:
                gv = int(v)
            except ValueError:
                try:
                    gv = float(v)
                except ValueError:
                    gv = v
            if k in globals():
                globals()[k] = gv
            else:
                print("unknown param:", k)
                sys.exit(2)
        print("params: N_CYCLES=%s SPEEDUP=%s SUB=%s/%s SMOOTH=%s LEAD=%s"
              % (N_CYCLES, SPEEDUP, SUB_S, SUB_E, SMOOTH_PASSES, LEAD_TICKS))
        humanize(sys.argv[2], sys.argv[3])
    else:
        print("unknown cmd:", cmd)
        sys.exit(2)
