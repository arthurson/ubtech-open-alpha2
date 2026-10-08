#!/usr/bin/env python3
"""ubx -> traj.csv (player語義: 每格slot=(s+e)*timeBase, 發送move=s*timeBase).

用法: python scripts/ubx_to_traj.py <in.ubx> <out.csv>
CSV欄: t_ms,s1..s20,move_ms (每行一格servo幀起點; 多track首尾相接)
"""
import struct, sys, csv

def le(d, p):
    return struct.unpack_from("<i", d, p)[0]

def parse_ubx(path):
    d = open(path, "rb").read()
    assert le(d, 0) == len(d), "total mismatch"
    assert d[4:13] == b"ubx-alpha", "bad magic"
    e_len = le(d, 17)
    ms = 21
    assert le(d, ms) == e_len
    count = le(d, ms + 4)
    p = ms + 8
    tracks = []
    for _ in range(count):
        item_len = le(d, p)
        assert le(d, p + 4) == item_len
        tend = p + 4 + item_len
        q = p + 8
        tid = le(d, q); q += 4
        f_len = le(d, q); q += 4 + f_len
        a_len = le(d, q); q += 4 + a_len
        b_len = le(d, q); q += 4
        blist_off = q
        blist = d[q:q + b_len]
        q += b_len
        assert q == tend, "track slack"
        tracks.append({"id": tid, "blist_off": blist_off, "blist": blist})
        p += 4 + item_len
    return d, tracks

def parse_blist(d, bo):
    nda = le(d, bo + 4)
    bp = bo + 8
    das = []
    for _ in range(nda):
        l1 = le(d, bp); bp += 4
        fp = bp
        assert le(d, fp) == l1
        f_tab_len = le(d, fp + 4)
        base = fp + 8 + f_tab_len
        fb = le(d, base + 4)
        ff = le(d, base + 212)
        fh = le(d, base + 220)
        ep = base + 224
        das.append({"f": ff, "fh": fh, "chain": d[ep:ep + fh] if fh else b""})
        bp += l1
    return das

def parse_chain(chain, fh):
    if fh == 0: return []
    assert le(chain, 0) == fh
    cnt = le(chain, 4); pp = 8; out = []
    for _ in range(cnt):
        ty = le(chain, pp); ln = le(chain, pp + 4)
        out.append((ty, chain[pp + 8:pp + 8 + ln]))
        pp += 8 + ln
    return out

def parse_trackh(data):
    assert le(data, 0) == len(data)
    nblk = le(data, 4); hp = 8; blks = []
    for _ in range(nblk):
        bl1 = le(data, hp); hp += 4; bo = hp
        assert le(data, bo) == bl1
        nfrm = le(data, bo + 76); fp2 = bo + 80; frms = []
        for _ in range(nfrm):
            fl1 = le(data, fp2); fp2 += 4; fo = fp2
            assert le(data, fo) == fl1
            s = le(data, fo + 8); e = le(data, fo + 12)
            ffb = le(data, fo + 76); epos = fo + 80
            angs = [data[epos + gi * 8 + 8] for gi in range(20)
                    if epos + gi * 8 + 8 < epos + ffb]
            while len(angs) < 20: angs.append(0)
            frms.append((s, e, angs))
            fp2 += fl1
        blks.append(frms)
        hp += bl1
    return blks

def export_ubx(path):
    d, tracks = parse_ubx(path)
    rows = []  # (t_ms, angles20, move_ms)
    t = 0
    for tr in tracks:
        tb = 0
        for da in parse_blist(d, tr["blist_off"]):
            if da["f"] != 0 or not da["chain"]:
                continue
            for ty, data in parse_chain(da["chain"], da["fh"]):
                if ty == 0 and len(data) == 8 and tb == 0:
                    tb = le(data, 0)
                elif ty == 1:
                    assert tb > 0, "timebase missing"
                    for blk in parse_trackh(data):
                        for s, e, angs in blk:
                            move = s * tb
                            rows.append((t, angs, move))
                            t += (s + e) * tb
    return rows

if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    rows = export_ubx(src)
    with open(dst, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["t_ms"] + [f"s{i}" for i in range(1, 21)] + ["move_ms"])
        for t, angs, mv in rows:
            w.writerow([t] + angs + [mv])
    dur = rows[-1][0] if rows else 0
    print(f"{src}: {len(rows)} frames, {dur}ms")
