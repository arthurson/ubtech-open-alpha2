#!/usr/bin/env python3
"""解剖檢查：睇指定 .ubx 郁邊幾粒舵機（同 home 差 >=15 即列出）。"""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

HOME = [120, 120, 120, 120, 120, 120, 120, 65, 145, 140,
        120, 120, 175, 95, 100, 120, 120, 120, 120, 120]
NAMES = {1: "R肩上下", 2: "R肩左右", 3: "R肘", 4: "L肩上下", 5: "L肩左右",
         6: "L肘", 7: "R髖左右", 8: "R髖上下", 9: "R膝", 10: "R踝上下",
         11: "R踝左右", 12: "L髖左右", 13: "L髖上下", 14: "L膝", 15: "L踝上下",
         16: "L踝左右", 17: "R指", 18: "L指", 19: "頭左右", 20: "頭上下"}

for fid in ["1464835936017", "1510818174706", "1508999860568"]:
    m = parse_ubx("C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\" + fid + ".ubx")
    d = m["raw"]
    print("===== " + fid)
    for t in m["tracks"]:
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 1:
                    for blk in parse_trackh(data):
                        for fi, f in enumerate(blk["frames"][:3]):
                            a = angles_of_eblob_template(f["raw"])
                            dev = []
                            for i, v in enumerate(a):
                                if abs(v - HOME[i]) >= 15:
                                    dev.append(str(i + 1) + NAMES[i + 1] + str(v))
                            print("trk" + str(t["id"]) + " f" + str(fi) +
                                  " 郁:" + ",".join(dev))
