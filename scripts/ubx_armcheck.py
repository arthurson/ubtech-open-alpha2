#!/usr/bin/env python3
"""手臂擺幅對比：原版 vs V4 主步態（Rroll servo2 / Lroll servo5 / 肘）。
超標（overshoot）即磨滑整彎咗，要減。"""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def gait(path):
    m = parse_ubx(path)
    d = m["raw"]
    best = []
    for t in m["tracks"]:
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            tb20 = False
            frms = []
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 0 and len(data) == 8 and le(data, 0) == 20:
                    tb20 = True
                if ty == 1:
                    for blk in parse_trackh(data):
                        frms.extend(blk["frames"])
            if tb20 and len(frms) > len(best):
                best = frms
    return [angles_of_eblob_template(f["raw"]) for f in best]


for path, label in [(ACT + "1508999860568.ubx", "ORIG"),
                    (ACT + "open-1508999860568-8step-smooth3.ubx", "V4")]:
    g = gait(path)
    print("##### %s n=%d" % (label, len(g)))
    for a, nm in [(2, "Rroll"), (5, "Lroll"), (3, "Relb"), (6, "Lelb")]:
        vals = [p[a - 1] for p in g]
        print("  %s: min=%d max=%d" % (nm, min(vals), max(vals)))
