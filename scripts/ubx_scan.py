#!/usr/bin/env python3
"""掃全量 .ubx：(1) 有無具名 keyframe（h 非零）；(2) 每 track 葉 (a,b,c,d)＋段 e/f。"""
import glob
import struct
import sys

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def L(d, p):
    return struct.unpack_from("<i", d, p)[0]


only = sys.argv[1:]
files = sorted(glob.glob(ACT + "*.ubx"))
named_files = []
for path in files:
    fid = path.split("\\")[-1]
    if only and fid not in only and fid[:-4] not in only:
        continue
    dd = open(path, "rb").read()
    ms = 21
    p = ms + 8
    try:
        if only:
            print("##### " + fid)
        for _ in range(L(dd, ms + 4)):
            item_len = L(dd, p)
            q = p + 8
            tid = L(dd, q)
            q += 4
            f_len = L(dd, q)
            q += 4
            named = 0
            if f_len >= 8:
                cnt = L(dd, q + 4)
                fp = q + 8
                for _ in range(cnt):
                    l1 = L(dd, fp)
                    fp += 4
                    if l1 <= 0:
                        continue
                    o = fp
                    ff = L(dd, o + 17)
                    hn = 0
                    for s in range(20):
                        if dd[o + 22 + ff + s * 2] or dd[o + 23 + ff + s * 2]:
                            hn += 1
                    if hn:
                        named += 1
                    fp += l1
            if named:
                named_files.append((fid, tid, named))
            if only:
                print("  trk%d namedkeys=%d" % (tid, named))
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
            if only:
                print("  trk%d leaves(a,b,c,d)=%s" % (tid, leaves))
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
            if only:
                print("  trk%d das(e,f)=%s" % (tid, das))
            q += b_len
            p += 4 + item_len
    except Exception as ex:
        print("PARSE-FAIL " + fid + " " + str(ex))
print("具名 keyframe 檔案數：", len(named_files), named_files[:10])
