#!/usr/bin/env python3
"""原版 vs 改版步態曲線對比：抽主步態段每軸角度列，印相位對齊抽樣值。
睇勻速重採樣/磨滑有冇改步形（峰值、相位、對稱）。"""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def gait_frames(path):
    """回主步態段 (timeBase==20) 全部 servo pose（檔案序）"""
    m = parse_ubx(path)
    d = m["raw"]
    out = []
    for t in m["tracks"]:
        for da in parse_blist_frames(d, t["blist_off"]):
            if da["fh"] == 0 or da["f"] != 0:
                continue
            for (ty, data) in parse_chain(da["chain"], da["fh"]):
                if ty == 0 and len(data) == 8 and le(data, 0) != 20:
                    break
                if ty == 1:
                    for blk in parse_trackh(data):
                        for f in blk["frames"]:
                            out.append(angles_of_eblob_template(f["raw"]))
            else:
                continue
            break
    return out


JOINTS = {1: "R肩上下", 4: "L肩上下", 8: "R髖上下", 13: "L髖上下",
          9: "R膝", 14: "L膝", 10: "R踝", 15: "L踝"}


def show(path, label):
    g = gait_frames(path)
    print("##### %s frames=%d" % (label, len(g)))
    idx = [0, len(g) // 4, len(g) // 2, 3 * len(g) // 4, len(g) - 1]
    for a in sorted(JOINTS):
        vals = [g[i][a - 1] for i in idx]
        print("  #%02d%s: %s (min=%d max=%d)" %
              (a, JOINTS[a], vals, min(p[a - 1] for p in g), max(p[a - 1] for p in g)))


show(ACT + "1508999860568.ubx", "ORIG")
show(ACT + "open-1508999860568-8step-smooth2.ubx", "V4-8STEP-SMOOTH2")
