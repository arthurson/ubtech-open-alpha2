#!/usr/bin/env python3
"""步相檢查：一個步態循環內，各關節極值出現的相位（cycle fraction）。
原版 6 格 vs V4 38 格/循環 —— 相位移位即步姿散（跛行感）。"""
import sys

sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read().split("if __name__")[0])

ACT = "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\"


def cycle(path):
    """回主步態段（timeBase==20）第一個完整循環 pose 列（原版 6 格；V4 38 格）"""
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
    if len(best) >= 38:
        frms = best[:38]
    elif len(best) >= 6:
        frms = best[:6]
    else:
        return []
    return [angles_of_eblob_template(f["raw"]) for f in frms]


EVENTS = {
    "R髖前max": (8, max), "R髖後min": (8, min),
    "L髖前max": (13, max), "L髖後min": (13, min),
    "R膝曲max": (9, min), "L膝曲max": (14, max),
}


def show(path, label):
    cyc = cycle(path)
    n = len(cyc)
    print("##### %s n=%d" % (label, n))
    for name, (servo, fn) in EVENTS.items():
        vals = [p[servo - 1] for p in cyc]
        target = fn(vals)
        idx = vals.index(target)
        print("  %s: value=%d at %.2f cycle" % (name, target, idx / n))


show(ACT + "1508999860568.ubx", "ORIG")
show(ACT + "open-1508999860568-8step-smooth2.ubx", "V4")
