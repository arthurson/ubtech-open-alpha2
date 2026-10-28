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

輸出 app/src/main/assets/gait/walk_fwd.json，Java 側 WalkCenter 讀入後交畀
GaitWalker。所有角度都會 clamp 入 SERVO_CALIBRATION（同 ubx_walk_humanize.CAL
一致），超出即報錯唔寫檔。

用法:
  python scripts/ubx_to_gait.py <in.ubx> [out.json]
  （預設 out = app/src/main/assets/gait/walk_fwd.json）
  python scripts/ubx_to_gait.py --check
  （CI：用 scripts/gait-src/ 內嘅 .ubx 重新生成，同已 commit 嘅 JSON 比對；唔一致即 exit 1）
"""
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "gait", "walk_fwd.json")

LEAD_STATIC_DEV = 6   # 起步段頭幾格同 home 最大差 <= 此值視為靜止，跳過
CYCLE_TOL = 6         # 週期偵測：f[i] 同 f[i+p] 每軸最大差容差
MIN_PERIOD = 4

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
        "home": home,
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


GAIT_SRC = os.path.join(HERE, "gait-src", "open-1508999860568-8step-smooth6.ubx")


def check():
    doc, info = build(GAIT_SRC)
    want = json.dumps(doc, ensure_ascii=False, indent=1) + "\n"
    try:
        have = open(DEFAULT_OUT, encoding="utf-8").read()
    except OSError:
        print("❌ 缺少", DEFAULT_OUT)
        return 1
    if have != want:
        print("❌ walk_fwd.json 同 .ubx 重新生成嘅結果不一致，請跑 python scripts/ubx_to_gait.py", GAIT_SRC)
        return 1
    print("✅ walk_fwd.json 同 %s 一致（cycle %d 格 / %d ms）"
          % (os.path.basename(GAIT_SRC), info["cycleFrames"], info["cycleMs"]))
    return 0


def main(argv):
    if len(argv) >= 2 and argv[1] == "--check":
        return check()
    if len(argv) < 2:
        print(__doc__)
        return 2
    out = argv[2] if len(argv) > 2 else DEFAULT_OUT
    doc, info = build(argv[1])
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", out)
    for k, v in info.items():
        print("  %s = %s" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
