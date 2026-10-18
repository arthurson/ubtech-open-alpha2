#!/usr/bin/env python3
"""全量 .ubx 官方播序驗證（純 Python，無需 Java classpath）：
parse 零失敗＋官方播序 vs 解析序分類（線性／重排）＋Loop/Jump/Cross 統計。
用法: python scripts/ubx_corpus_order.py [actions_dir]
（預設 C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions）"""
import glob
import os
import sys

ARGS = sys.argv[1:]
sys.argv = ["x"]
exec(open("scripts/ubx_walk_humanize.py", encoding="utf-8").read()
     .split("if __name__")[0])

DIR = (ARGS[0] if ARGS
       else "C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions")
files = sorted(glob.glob(os.path.join(DIR, "*.ubx")))
print("files:", len(files))

n_same = n_diff = 0
fails = []
diffs = []
loops = []
jumps = []
crosses = []
for path in files:
    name = os.path.basename(path)
    try:
        m = parse_ubx(path)
        segs, voices, total, logs = official_plan(m)
    except Exception as ex:
        fails.append(name)
        print("PARSE-FAIL " + name + " " + str(ex))
        continue
    d = m["raw"]
    pl = []
    for t in m["tracks"]:
        for bi, blk in enumerate(track_detail(d, t)[1]):
            if blk["f"] == 0 and blk["h"] > 0:
                pl.append((t["id"], bi))
    ol = [(s["track"], s["idx"]) for s in segs]
    if ol == pl:
        n_same += 1
    else:
        n_diff += 1
        nfr = sum(len(s["frames"]) for s in segs)
        diffs.append((name, pl, ol, total, nfr))
    if any(x.startswith("LOOP") for x in logs):
        loops.append(name)
    if any(x.startswith("JUMP") for x in logs):
        jumps.append(name)
    if any(x.startswith("CROSS") for x in logs):
        crosses.append(name)

print("parsed: %d failed: %d" % (len(files) - len(fails), len(fails)))
print("official-order == parse-order: %d" % n_same)
print("reordered: %d" % n_diff)
for name, pl, ol, total, nfr in diffs:
    print("  DIFF %s parse=%s official=%s total=%dms frames=%d"
          % (name, pl, ol, total, nfr))
print("loops=%d %s" % (len(loops), loops))
print("jumps=%d %s" % (len(jumps), jumps))
print("cross-track=%d %s" % (len(crosses), crosses))
