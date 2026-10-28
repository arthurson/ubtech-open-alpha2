#!/usr/bin/env python3
"""
.ubx 步態 → 「按住前行」步態 JSON（零依賴，沿用 ubx_walk_humanize.py 嘅 parser）。

背景：UbxPlayer 播 .ubx 係一次過「起步 → N 步 → 收步」。按住連行要拆開三段，
放手先可以隨時由任何相位收返企定：

  leadIn  起步段：官方播序第一個 servo 段（由 home 沉入起步姿勢）。
          頭幾格同 home 差 <= LEAD_STATIC_DEV 單位（近乎不動，只係 ease-in 嘅尾巴），
          直接跳過，免得按下去要乾等幾秒先郁。
  cycle   步態循環：播序最長 servo 段頭一個週期（自動搵最小週期 p，
          要求 f[i] ≈ f[i+p]，容差 CYCLE_TOL），首尾可無縫接返。
  home    企定姿勢：播序最後一格（原檔收步終點）。放手就由當前姿勢線性收返呢個姿勢。

standFrames  著地收步格：循環入面腿部最近 home 嘅局部最低點（見 find_stand_frames）。
          放手後引擎會繼續行到最近一格先收步，避免單腳懸空時就收（由姿勢推斷，未經實機驗證）。

輸出 app/src/main/assets/gait/walk_fwd.json，Java 側 WalkCenter 讀入後交畀
GaitWalker。所有角度都會 clamp 入 SERVO_CALIBRATION（同 ubx_walk_humanize.CAL
一致），超出即報錯唔寫檔。

向後行（walk_back.json）：由前行步態「時間反轉」而來——同一組循環姿勢倒序播放
（每格時長跟住倒轉：由 P[k-1] 去 P[k] 嘅時間＝原檔由 P[k] 去 P[k-1] 嘅時間），
起步段 leadIn 同 home 完全沿用前行嘅，所以進入／離開步態嘅姿勢都係已知安全嘅。
呢個係運動學上嘅近似（慢速準靜態步態適用）；未經實機驗證，第一次請托住機身試。

用法:
  python scripts/ubx_to_gait.py <in.ubx> [out.json]
  （預設 out = app/src/main/assets/gait/walk_fwd.json；
    同時寫同目錄嘅 walk_back.json——out 檔名含 fwd 就換成 back，否則加 _back 後綴）
  python scripts/ubx_to_gait.py --check
  （CI：用 scripts/gait-src/ 內嘅 .ubx 重新生成，同已 commit 嘅前行／後行 JSON 比對；唔一致即 exit 1）
"""
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "gait", "walk_fwd.json")
DEFAULT_OUT_BACK = os.path.join(os.path.dirname(DEFAULT_OUT), "walk_back.json")

LEAD_STATIC_DEV = 6   # 起步段頭幾格同 home 最大差 <= 此值視為靜止，跳過
CYCLE_TOL = 6         # 週期偵測：f[i] 同 f[i+p] 每軸最大差容差
MIN_PERIOD = 4
LEG_SERVOS = range(5, 16)   # servo 6..16（0-based 索引 5..15）：兩條腿

# 收步／起步限速：單位 = 每 servo 單位 ms（原檔最慢收步 ≈ 31 ms/unit，起步 ≈ 28）。
BLEND_MS_PER_UNIT = 31
BLEND_MIN_MS = 800
BLEND_MAX_MS = 3200
ENTRY_MS_PER_UNIT = 30   # 起步第一格：由實際姿勢過去，最少按此限速（防急扯）
MIN_MS_PER_UNIT = 5      # 步態每格最快限速（≈200 單位/秒；2x 時最差格 ≈7）


def load_helpers():
    spec = importlib.util.spec_from_file_location(
        "ubx_walk_humanize", os.path.join(HERE, "ubx_walk_humanize.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def seg_frames(h, seg):
    """一個播序段 → [(angles20, slot_ms)]。slot = (s+e)*tb（同 UbxPlayer 一致）。"""
    out = []
    for f in seg["frames"]:
        a = list(h.frame_angles(f))[:20]
        slot = (int(f["s"]) + int(f["e"])) * int(seg["tb"])
        out.append((a, slot))
    return out


def clamp_cal(h, frames, what):
    for i, (a, _) in enumerate(frames):
        for j, v in enumerate(a):
            lo, hi = h.CAL[j + 1]
            if not (lo <= v <= hi):
                raise SystemExit("%s 第 %d 格 servo %d = %d 超出校準範圍 [%d,%d]"
                                 % (what, i, j + 1, v, lo, hi))


def match_len(frames, p):
    """由頭起連續幾格 f[i] ≈ f[i+p]（每軸差 <= CYCLE_TOL）。"""
    n = len(frames)
    k = 0
    while k + p < n and max(abs(frames[k][j] - frames[k + p][j]) for j in range(20)) <= CYCLE_TOL:
        k += 1
    return k


def find_period(frames):
    """最小週期 p：至少 60% 個週期長度已被實測重覆（尾段收步會偏離，故唔要求全程吻合）。"""
    for p in range(MIN_PERIOD, len(frames)):
        if match_len(frames, p) * 5 >= p * 3:
            return p
    return None


def find_stand_frames(cycle_angles, home):
    """著地收步格：循環入面「腿部離企定姿勢最近」嘅局部最低點（循環首尾相接）。

    量度＝腿部舵機同 home 嘅絕對差總和。局部最低點＝兩邊都唔比佢近，即步態經過
    「雙腳近乎企直」嗰刻；放手後行到呢啲格先收步，收步行程最短、重心最穩。
    （由姿勢數據推斷，唔係原 .ubx 標明；未經實機驗證。）
    """
    n = len(cycle_angles)
    dev = [sum(abs(a[j] - home[j]) for j in LEG_SERVOS) for a in cycle_angles]
    out = []
    for i in range(n):
        if dev[i] <= dev[i - 1] and dev[i] < dev[(i + 1) % n]:
            out.append(i)
    return out


def build(path):
    h = load_helpers()
    m = h.parse_ubx(path)
    segs, _voices, _total, _logs = h.official_plan(m)
    servo = [seg_frames(h, s) for s in segs]
    if len(servo) < 2:
        raise SystemExit("官方播序少於 2 個 servo 段，唔似步態檔")

    home = servo[-1][-1][0]                       # 播序最後一格
    main_i = max(range(len(servo)), key=lambda i: len(servo[i]))
    main = servo[main_i]
    lead = servo[0] if main_i != 0 else []

    # 起步段：跳過頭幾格近乎靜止嘅
    skip = 0
    for a, _ms in lead:
        if max(abs(a[j] - home[j]) for j in range(20)) <= LEAD_STATIC_DEV:
            skip += 1
        else:
            break
    lead = lead[skip:]

    angles = [a for a, _ in main]
    p = find_period(angles)
    if p is None:
        raise SystemExit("搵唔到步態週期（容差 %d）" % CYCLE_TOL)
    cycle = main[:p]

    clamp_cal(h, lead, "leadIn")
    clamp_cal(h, cycle, "cycle")
    clamp_cal(h, [(home, 0)], "home")

    wrap = max(abs(main[p][0][j] - cycle[0][0][j]) for j in range(20)) if p < len(main) else 0
    doc = {
        "source": os.path.basename(path),
        "note": "由 scripts/ubx_to_gait.py 生成，勿手改；ms 為 1.0x 時每格時長",
        "direction": "fwd",
        "home": home,
        "standFrames": find_stand_frames([a for a, _ in cycle], home),
        "leadIn": {"ms": [ms for _, ms in lead], "frames": [a for a, _ in lead]},
        "cycle": {"ms": [ms for _, ms in cycle], "frames": [a for a, _ in cycle]},
        "blend": {"msPerUnit": BLEND_MS_PER_UNIT, "minMs": BLEND_MIN_MS, "maxMs": BLEND_MAX_MS},
        "limits": {"entryMsPerUnit": ENTRY_MS_PER_UNIT, "minMsPerUnit": MIN_MS_PER_UNIT},
    }
    info = {
        "skippedStaticLeadFrames": skip,
        "leadInFrames": len(lead),
        "cycleFrames": p,
        "cycleMs": sum(ms for _, ms in cycle),
        "wrapMaxDiff": wrap,
    }
    return doc, info


def reverse_gait(doc):
    """前行步態 → 後行步態（時間反轉）。

    循環 c[0..n-1]（ms[i]＝由 c[i-1] 去 c[i] 嘅時間，c[0] 係由 c[n-1] 回頭接）倒序播：
      P[k]  = c[(n-k) % n]            → [c0, c(n-1), c(n-2), ..., c1]
      D[k]  = ms[(n+1-k) % n]         → 由 P[k-1] 去 P[k]（＝原檔 c[i] 去 c[i-1]）嘅時間
    P[0]＝c0，同前行一樣由 leadIn 末姿勢接入，所以 leadIn／home 全部沿用。
    """
    cyc = doc["cycle"]
    n = len(cyc["frames"])
    frames = [list(cyc["frames"][(n - k) % n]) for k in range(n)]
    ms = [cyc["ms"][(n + 1 - k) % n] for k in range(n)]
    back = dict(doc)
    back["note"] = "由 scripts/ubx_to_gait.py 生成（前行步態時間反轉），勿手改；ms 為 1.0x 時每格時長"
    back["direction"] = "back"
    back["cycle"] = {"ms": ms, "frames": frames}
    # 著地格同循環一齊倒序：P[k] = c[(n-k)%n]，所以前行格 s 對應後行格 (n-s)%n。
    back["standFrames"] = sorted((n - sf) % n for sf in doc.get("standFrames", []))
    return back


def verify_reverse(fwd, back):
    """結構檢查：後行循環真係前行循環嘅時間反轉（姿勢集合／相鄰關係／每格時長）。"""
    f, b = fwd["cycle"], back["cycle"]
    n = len(f["frames"])
    if len(b["frames"]) != n or len(b["ms"]) != n:
        return "格數唔同"
    if sorted(map(tuple, b["frames"])) != sorted(map(tuple, f["frames"])):
        return "姿勢集合唔同"
    if sum(b["ms"]) != sum(f["ms"]):
        return "週期總時長唔同"
    for k in range(n):
        # 後行 P[k-1]→P[k]（耗時 D[k]）必須等於前行某格 c[i]→c[i-1]（耗時 ms[i]）
        i = (n + 1 - k) % n
        if b["frames"][k - 1] != f["frames"][i] or b["frames"][k] != f["frames"][i - 1]:
            return "第 %d 格相鄰關係唔係反轉" % k
        if b["ms"][k] != f["ms"][i]:
            return "第 %d 格時長唔啱" % k
    for key in ("home", "leadIn", "blend", "limits"):
        if back[key] != fwd[key]:
            return "%s 同前行唔一致" % key
    fs, bs = fwd.get("standFrames", []), back.get("standFrames", [])
    if len(fs) != len(bs):
        return "standFrames 數量唔同"
    for sf in fs:
        k = (n - sf) % n
        if k not in bs or b["frames"][k] != f["frames"][sf]:
            return "standFrames 鏡像唔啱（前行第 %d 格）" % sf
    return None


def back_path_for(out):
    d, name = os.path.split(out)
    if "fwd" in name:
        return os.path.join(d, name.replace("fwd", "back"))
    stem, ext = os.path.splitext(name)
    return os.path.join(d, stem + "_back" + ext)


def dump(doc):
    return json.dumps(doc, ensure_ascii=False, indent=1) + "\n"


GAIT_SRC = os.path.join(HERE, "gait-src", "open-1508999860568-8step-smooth6.ubx")


def check():
    doc, info = build(GAIT_SRC)
    back = reverse_gait(doc)
    rc = 0
    bad = verify_reverse(doc, back)
    if bad:
        print("❌ 後行步態唔係前行嘅時間反轉：" + bad)
        rc = 1
    for label, path, want_doc in (("walk_fwd.json", DEFAULT_OUT, doc),
                                  ("walk_back.json", DEFAULT_OUT_BACK, back)):
        want = dump(want_doc)
        try:
            have = open(path, encoding="utf-8").read()
        except OSError:
            print("❌ 缺少", path)
            rc = 1
            continue
        if have != want:
            print("❌ %s 同 .ubx 重新生成嘅結果不一致，請跑 python scripts/ubx_to_gait.py %s"
                  % (label, GAIT_SRC))
            rc = 1
            continue
        print("✅ %s 同 %s 一致（cycle %d 格 / %d ms）"
              % (label, os.path.basename(GAIT_SRC), info["cycleFrames"], info["cycleMs"]))
    return rc


def main(argv):
    if len(argv) >= 2 and argv[1] == "--check":
        return check()
    if len(argv) < 2:
        print(__doc__)
        return 2
    out = argv[2] if len(argv) > 2 else DEFAULT_OUT
    doc, info = build(argv[1])
    os.makedirs(os.path.dirname(out), exist_ok=True)
    back_out = back_path_for(out)
    for path, d in ((out, doc), (back_out, reverse_gait(doc))):
        with open(path, "w", encoding="utf-8") as f:
            f.write(dump(d))
        print("wrote", path)
    for k, v in info.items():
        print("  %s = %s" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
