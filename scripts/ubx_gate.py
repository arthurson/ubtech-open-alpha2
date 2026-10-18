#!/usr/bin/env python3
"""官方分發模擬（Alpha2Services 1.1.7.3）：逐條 d/a 葉計 block 內置 f 表 gate
（row[a==leaf.d].d：0/3 播、1 中斷、2 跳過、其餘死路）＋ block.f 分派
（0 servo／1 跳 track／2 Loop／4 TTS）；c==-2 則 STOP(trk0) 或跨 track 續接。
（舊版錯用 track 級 fkeys 做 gate，已修正為 block 內置表。）"""
import os
import sys

ARGS = sys.argv[1:]
sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read()
     .split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"
for raw in ARGS:
    path = raw
    if not os.path.exists(path):
        if not path.endswith(".ubx"):
            path += ".ubx"
        if not os.path.isabs(path):
            path = os.path.join(ACT, path)
    print("##### " + path.split("\\")[-1])
    m = parse_ubx(path)
    d = m["raw"]
    tidmap = {}
    for t in m["tracks"]:
        leaves, blocks = track_detail(d, t)
        tidmap[t["id"]] = (leaves, blocks)
    for t in m["tracks"]:
        leaves, blocks = tidmap[t["id"]]
        print("== trk%d leaves=%d blocks=%d" % (t["id"], len(leaves), len(blocks)))
        for bi, blk in enumerate(blocks):
            kind = {0: "servo", 1: "jump", 2: "loop", 4: "tts"}.get(blk["f"],
                                                                   "f%d" % blk["f"])
            nf = len(blk["windows"])
            ticks = sum(fr["s"] + fr["e"] for fr in blk["windows"])
            extra = ""
            if blk["f"] == 2 and blk["loop"]:
                extra = " f=%d g=%d h=%d" % (blk["loop"]["f"],
                                             blk["loop"]["g"],
                                             blk["loop"]["h"])
            if blk["f"] == 1:
                extra = " →trk%d" % blk["g"]
            print("   blk[%d] e=%d f=%d(%s%s) h=%d tb=%d n=%d (%dt)"
                  % (bi, blk["e"], blk["f"], kind, extra, blk["h"],
                     blk["tb"] or 50, nf, ticks))
        for lf in leaves:
            base = "   leaf(a=%d,b=%d,c=%d,d=%d)" % (lf["a"], lf["b"],
                                                     lf["c"], lf["d"])
            if lf["c"] == -2:
                if t["id"] == 0:
                    print(base + ": c==-2 → STOP（全劇完）")
                else:
                    hit = None
                    for tr in m["tracks"]:
                        for blk in tidmap[tr["id"]][1]:
                            if blk["g"] == t["id"]:
                                hit = tr["id"]
                    print(base + ": c==-2 → CROSS → trk%s" % hit)
                continue
            blk = None
            for b2 in blocks:
                if b2["e"] == lf["c"]:
                    blk = b2
                    break
            if blk is None:
                print(base + ": 無 block(e=%d) → 死路" % lf["c"])
                continue
            row = None
            for r in blk["rows"]:
                if r["a"] == lf["d"]:
                    row = r
                    break
            iC = row["d"] if row else -1
            if iC not in (0, 3):
                tag = {1: "中斷分支", 2: "跳過"}.get(iC, "死路")
                print(base + ": gate(row.d)=%d → %s" % (iC, tag))
                continue
            if blk["f"] == 0:
                ticks = sum(fr["s"] + fr["e"] for fr in blk["windows"])
                print(base + ": gate=%d → PLAY servo e=%d %df %dt (tb=%d)"
                      % (iC, blk["e"], len(blk["windows"]), ticks,
                         blk["tb"] or 50))
            elif blk["f"] == 1:
                print(base + ": gate=%d → JUMP f1 → trk%d" % (iC, blk["g"]))
            elif blk["f"] == 2 and blk["sub"] == 2:
                lp = blk["loop"] or {}
                print(base + ": gate=%d → LOOP i/g/h=%s/%s/%s"
                      % (iC, lp.get("f"), lp.get("g"), lp.get("h")))
            elif blk["f"] == 4:
                print(base + ": gate=%d → TTS" % iC)
            else:
                print(base + ": gate=%d → f%d(sub=%d)" % (iC, blk["f"], blk["sub"]))
