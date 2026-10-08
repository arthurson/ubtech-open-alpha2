#!/usr/bin/env python3
"""靜態平衡審計: traj.csv逐格FK -> COM投影 vs 雙腳支撐多邊形 -> margin.
用法: python sim/audit_traj.py sim/traj_demo.csv [--corpus dir] [--plot out.png]
margin<0 該格開環企唔穩. exit code: 0全過 / 1有唔穩格.
"""
import csv, sys, math
import numpy as np
import mujoco

INIT = [120,120,120,120,120,120,120,120,65,145,135,120,120,175,95,105,120,120,120,120]
CAL = {1:(5,235),2:(50,210),3:(55,185),4:(5,235),5:(30,190),6:(55,185),7:(100,200),
       8:(20,220),9:(35,230),10:(35,215),11:(100,190),12:(40,140),13:(20,220),
       14:(10,205),15:(25,205),16:(50,140),17:(95,125),18:(95,125),19:(75,165),20:(105,155)}
URDF_JOINTS = ["j1_r_shoulder_pitch","j2_r_shoulder_roll","j3_r_elbow",
 "j4_l_shoulder_pitch","j5_l_shoulder_roll","j6_l_elbow",
 "j7_r_hip_roll","j8_r_hip_pitch","j9_r_knee","j10_r_ankle_pitch","j11_r_ankle_roll",
 "j12_l_hip_roll","j13_l_hip_pitch","j14_l_knee","j15_l_ankle_pitch","j16_l_ankle_roll",
 "j17_r_finger","j18_l_finger","j19_head_yaw","j20_head_pitch"]
ID_OF = {n: i + 1 for i, n in enumerate(URDF_JOINTS)}
SIGN = {i: (-1 if i in (1,2,3,5,7,8,9,10,12) else 1) for i in range(1, 21)}
D2R = math.pi / 180.0

MJCF = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\alpha2_real_mjcf.xml'

def audit_rows(rows):
    m = mujoco.MjModel.from_xml_path(MJCF)
    jid = {n: mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n) for n in URDF_JOINTS}
    base = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, 'base_link')
    # 腳掌框(URDF量度, m): R/L
    feet = {}
    for side, link in (('R', 'r_foot_link'), ('L', 'l_foot_link')):
        feet[side] = link
    d = mujoco.MjData(m)
    mujoco.mj_resetData(m, d)
    out = []
    for t_ms, angs, mv in rows:
        for n in URDF_JOINTS:
            mid = ID_OF[n]
            lo, hi = CAL[mid]
            sv = max(lo, min(hi, angs[mid - 1]))
            d.qpos[int(m.jnt_qposadr[jid[n]])] = (sv - INIT[mid - 1]) * D2R * SIGN[mid]
        mujoco.mj_forward(m, d)
        com = d.subtree_com[base][:2].copy()
        # 雙腳角點(世界); 懸空腳(sole離地>5mm)不計入支撐
        corners = []
        soles = {}
        for link in feet.values():
            bi = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, link)
            p = d.xpos[bi]
            R = d.xmat[bi].reshape(3, 3)
            sole = p[2] + (R @ np.array([0.0159, 0.0, -0.0238]))[2]
            soles[link] = sole
        floor = min(soles.values())
        for link in feet.values():
            if soles[link] - floor > 0.005:
                continue
            bi = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, link)
            p = d.xpos[bi]
            R = d.xmat[bi].reshape(3, 3)
            for dx, dy in ((0.058, 0.024), (0.058, -0.024), (-0.027, 0.024), (-0.027, -0.024)):
                c = p[:2] + (R[:2, :2] @ np.array([dx, dy]))
                corners.append(c)
        corners = np.array(corners)
        # margin: 點到凸包邊最短距離(內正外負, 簡化: 到8點框邊界)
        cx0, cy0 = corners.min(0); cx1, cy1 = corners.max(0)
        inside = (cx0 <= com[0] <= cx1) and (cy0 <= com[1] <= cy1)
        margin = min(com[0] - cx0, cx1 - com[0], com[1] - cy0, cy1 - com[1])
        if not inside:
            margin = -min(abs(com[0] - cx0), abs(com[0] - cx1)) if not (cx0 <= com[0] <= cx1) else -min(abs(com[1] - cy0), abs(com[1] - cy1))
        out.append((t_ms, float(margin), float(com[0]), float(com[1])))
    return out

def load_traj(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_ms"]), [int(r[f"s{i}"]) for i in range(1, 21)], int(r["move_ms"])))
    return rows

if __name__ == "__main__":
    import glob, os
    if "--corpus" in sys.argv:
        d = sys.argv[sys.argv.index("--corpus") + 1]
        sys.path.insert(0, r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\scripts')
        from ubx_to_traj import export_ubx
        print(f"{'file':28s} {'frames':>6s} {'min_margin_mm':>13s} worst_t")
        for fn in sorted(glob.glob(os.path.join(d, "*.ubx"))):
            try:
                rows = export_ubx(fn)
            except Exception:
                continue
            if not rows:
                continue
            res = audit_rows(rows)
            mm = min(r[1] for r in res) * 1000
            wt = res[int(np.argmin([r[1] for r in res]))][0]
            flag = "OK " if mm >= 5 else ("RISKY" if mm >= 0 else "FALL ")
            print(f"{flag} {os.path.basename(fn):24s} {len(rows):6d} {mm:13.1f} {wt}")
    else:
        rows = load_traj(sys.argv[1])
        res = audit_rows(rows)
        for t_ms, mg, cx, cy in res[:10]:
            print(f"t={t_ms:6d} margin={mg*1000:+6.1f}mm com=({cx:+.3f},{cy:+.3f})")
        print("...")
        mm = min(r[1] for r in res)
        print(f"min margin {mm*1000:+.1f}mm over {len(res)} frames")
        sys.exit(0 if mm >= 0 else 1)
