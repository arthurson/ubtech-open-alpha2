#!/usr/bin/env python3
"""ubx -> traj.csv（官方 Alpha2Services 1.1.7.3 播放語意）：
入口 leaf 鏈＋block gate＋Loop／跳 track／跨 track 續接全跟官方；
timebase 逐 block 後蓋前（static 預設 50）。

用法: python scripts/ubx_to_traj.py <in.ubx> <out.csv>
CSV欄: t_ms,s1..s20,move_ms（每行一格 servo 幀起點，按官方播放序；
move_ms＝s*timeBase，slot＝(s+e)*timeBase）
"""
import csv
import sys

ARGS = sys.argv[1:]
sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read()
     .split("if __name__")[0])

if len(ARGS) < 2:
    print(__doc__)
    raise SystemExit(2)

src, dst = ARGS[0], ARGS[1]
m = parse_ubx(src)
segs, voices, total, logs = official_plan(m)

rows = []  # (t_ms, angles20, move_ms)
t = 0
for sg in segs:
    for fr in sg["frames"]:
        rows.append((t, angles_of_eblob_template(fr["raw"]), fr["s"] * sg["tb"]))
        t += (fr["s"] + fr["e"]) * sg["tb"]
assert t == total, (t, total)

with open(dst, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["t_ms"] + ["s%d" % i for i in range(1, 21)] + ["move_ms"])
    for t_ms, angs, mv in rows:
        w.writerow([t_ms] + angs + [mv])
print("%s: %d frames, %dms" % (src, len(rows), total))
