#!/usr/bin/env python3
"""官方 f/b.f() 調度模擬：逐條 d/d 葉計 v7 gate + d/a 選擇。
v7 = fkey[a()==leaf.d].d；da = d/a[e_field==leaf.c]；inner 分支看 da.f_flag。
v7==0/3 -> 播（inner：f==0 servo / 1 f/e / 2 e/d / 4 voice）；否則 skip/return。"""
import struct
import sys

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def L(d, p):
    return struct.unpack_from("<i", d, p)[0]


def load(fid):
    if fid.endswith(".ubx"):
        fid = fid[:-4]
    dd = open(ACT + fid + ".ubx", "rb").read()
    ms = 21
    p = ms + 8
    tracks = []
    for _ in range(L(dd, ms + 4)):
        item_len = L(dd, p)
        q = p + 8
        tid = L(dd, q)
        q += 4
        f_len = L(dd, q)
        q += 4
        fkeys = []
        if f_len >= 8:
            cnt = L(dd, q + 4)
            fp = q + 8
            for _ in range(cnt):
                l1 = L(dd, fp)
                fp += 4
                if l1 <= 0:
                    continue
                o = fp
                assert L(dd, o) == l1
                fkeys.append((L(dd, o + 4), L(dd, o + 13)))
                fp += l1
        q += f_len
        a_len = L(dd, q)
        q += 4
        leaves = []
        if a_len >= 8:
            cnt = L(dd, q + 4)
            ap = q + 8
            for _ in range(cnt):
                l1 = L(dd, ap)
                ap += 4
                if l1 <= 0:
                    continue
                leaves.append((L(dd, ap + 4), L(dd, ap + 8),
                               L(dd, ap + 12), L(dd, ap + 16)))
                ap += l1
        q += a_len
        b_len = L(dd, q)
        q += 4
        bo = q
        nda = L(dd, bo + 4)
        bp = bo + 8
        das = []
        for _ in range(nda):
            l1 = L(dd, bp)
            bp += 4
            fp = bp
            ftl = L(dd, fp + 4)
            base = fp + 8 + ftl
            das.append((L(dd, base + 208), L(dd, base + 212)))
            bp += l1
        q += b_len
        tracks.append((tid, fkeys, leaves, das))
        p += 4 + item_len
    return tracks


BR = {0: "servo f/i", 1: "f/e", 2: "e/d", 4: "voice/b"}


for fid in sys.argv[1:]:
    print("##### " + fid)
    for (tid, fkeys, leaves, das) in load(fid):
        print("== track id=" + str(tid) + " fkeys(a->d):" + str(dict(fkeys)))
        print("== track id=" + str(tid) + " fkeys(a->d):" + str(dict(fkeys)))
        emap = {}
        for idx, (e, f) in enumerate(das):
            emap.setdefault(e, idx)
        print("   das e->idx:" + str(emap) + " fflags:" + str([f for (e, f) in das]))
        for (a, b, c, dd) in leaves:
            v7 = dict(fkeys).get(dd, -1)
            dai = emap.get(c, None)
            if dai is None:
                print("   leaf(a=%d,c=%d,d=%d): v7=%d da=MISSING -> 官方播不到！" % (a, c, dd, v7))
            else:
                ff = das[dai][1]
                play = (v7 in (0, 3)) and (ff in BR)
                print("   leaf(a=%d,c=%d,d=%d): v7=%d da[%d](f=%d) -> %s" %
                      (a, c, dd, v7, dai, ff,
                       ("PLAY " + BR[ff]) if play else "SKIP"))
