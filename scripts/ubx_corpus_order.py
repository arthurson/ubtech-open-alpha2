#!/usr/bin/env python3
"""全量 .ubx leaf-order 驗證：parse 零失敗＋order 統計＋列出被重排的 track。"""
import glob
import subprocess

CLS = "C:\\Users\\user\\AppData\\Local\\Temp\\ubxverify\\classes"
files = sorted(glob.glob("C:\\Users\\user\\Desktop\\alpha2\\UBX\\actions\\*.ubx"))
print("files:", len(files))
r = subprocess.run(["java", "-cp", CLS, "VerifyOrder"] + files,
                   capture_output=True, text=True)
if r.stderr.strip():
    print("STDERR:", r.stderr.strip()[-300:])
lines = [x for x in r.stdout.splitlines() if x.strip()]
print("parsed:", len(lines), "failed:", len(files) - len(lines))
n_null = n_empty = n_reord = n_same = 0
for line in lines:
    parts = line.split("|trk")
    name = parts[0].strip()
    for p in parts[1:]:
        frags = dict(kv.split("=") for kv in p.strip().split(" ") if "=" in kv)
        order = frags.get("order", "")
        da = int(frags.get("da", "0"))
        if order in ("null", "None"):
            n_null += 1
            print("NULL-ORDER " + name + " trk" + p.strip())
        elif order == "[]":
            n_empty += 1
            if da > 0:
                print("EMPTY-ORDER(da=%d) %s trk%s" % (da, name, p.strip()))
        else:
            idxs = [int(x) for x in order.strip("[]").split(",") if x.strip() != ""]
            if idxs == list(range(da)):
                n_same += 1
            else:
                n_reord += 1
                print("REORDER " + name + " trk" + p.strip())
print("null=%d empty=%d same-as-parse=%d reordered=%d"
      % (n_null, n_empty, n_same, n_reord))
