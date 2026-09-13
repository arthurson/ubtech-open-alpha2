#!/usr/bin/env python3
"""對比各版 track1 尾格手臂（Rroll servo2 / Lroll servo5）。"""
import os
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def lasttrack1(path):
    m = parse_ubx(path)
    d = m["raw"]
    for t in m["tracks"]:
        if t["id"] != 1:
            continue
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 1:
                    frms = [f for blk in parse_trackh(data) for f in blk["frames"]]
                    a = angles_of_eblob_template(frms[-1]["raw"])
                    print("%s n=%d last Rroll=%d Lroll=%d" %
                          (os.path.basename(path), len(frms), a[1], a[4]))


for f in ["1508999860568.ubx",
          "open-1508999860568-8step-smooth2.ubx",
          "open-1508999860568-8step-smooth3.ubx"]:
    lasttrack1(ACT + f)
