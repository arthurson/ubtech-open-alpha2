#!/usr/bin/env python3
"""viewer播ubx軌跡: python sim/watch_mj.py sim/traj_upper.csv [--pd] [--loop] [--speed 1.0]
預設kinematic(郁關節唔跌, 睇舞步); --pd行真PD+重力(會仆, 睇平衡).
--seconds N播N秒自動閂(測試用).
"""
import csv, sys, math, time
import numpy as np
import mujoco
import mujoco.viewer

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

def clamp(i, v):
    lo, hi = CAL[i]
    return max(lo, min(hi, int(round(v))))

def load_traj(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_ms"]), [int(r[f"s{i}"]) for i in range(1, 21)]))
    return rows

def build_seq(rows):
    first = [float(x) for x in rows[0][1]]
    seq = []
    for k in range(41):
        seq.append((k * 50, [INIT[i] + (first[i] - INIT[i]) * k / 40 for i in range(20)]))
    for t_ms, angs in rows:
        seq.append((t_ms + 2000, [float(x) for x in angs]))
    return seq

def seq_at(seq, t):
    i = 0
    while i + 1 < len(seq) and seq[i + 1][0] <= t:
        i += 1
    if i + 1 < len(seq):
        t0, a0 = seq[i]; t1, a1 = seq[i + 1]
        al = 0.0 if t1 == t0 else min(1.0, max(0.0, (t - t0) / (t1 - t0)))
    else:
        a0 = a1 = seq[i][1]; al = 1.0
    return [a0[k] + (a1[k] - a0[k]) * al for k in range(20)]

def main():
    args = sys.argv[1:]
    path = args[0]
    pd = "--pd" in args
    loop = "--loop" in args
    speed = float(args[args.index("--speed") + 1]) if "--speed" in args else 1.0
    secs = float(args[args.index("--seconds") + 1]) if "--seconds" in args else 0
    rows = load_traj(path)
    seq = build_seq(rows)
    total = seq[-1][0] / 1000.0
    m = mujoco.MjModel.from_xml_path(MJCF)
    d = mujoco.MjData(m)
    mujoco.mj_resetData(m, d)
    fb = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, "floating_base")
    fa = m.jnt_qposadr[fb]
    qadr = {}
    for n in URDF_JOINTS:
        j = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n)
        qadr[ID_OF[n]] = int(m.jnt_qposadr[j])
    KP = {i: (10.0 if 7 <= i <= 16 else 3.0 if i <= 6 else 1.0 if i >= 19 else 0.3) for i in range(1, 21)}
    KD = {i: (1.0 if 7 <= i <= 16 else 0.3 if i <= 6 else 0.1 if i >= 19 else 0.03) for i in range(1, 21)}
    EFF = {i: (2.0 if 7 <= i <= 16 else 1.2 if i <= 6 else 0.8 if i >= 19 else 0.3) for i in range(1, 21)}
    act_jnt = [int(m.actuator_trnid[i][0]) for i in range(m.nu)]
    jid2act = {}
    for n in URDF_JOINTS:
        j = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n)
        jid2act[j] = [a for a, jj in enumerate(act_jnt) if jj == j][0]
    jmap = {ID_OF[n]: mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n) for n in URDF_JOINTS}
    dof = {mid: int(m.jnt_dofadr[jmap[mid]]) for mid in range(1, 21)}
    with mujoco.viewer.launch_passive(m, d) as v:
        t0wall = time.time()
        while v.is_running():
            el = (time.time() - t0wall)
            if secs and el > secs:
                break
            simt = (el * speed) % total if loop else min(el * speed, total)
            sv = seq_at(seq, simt * 1000.0)
            qd = {mid: (clamp(mid, sv[mid - 1]) - INIT[mid - 1]) * D2R * SIGN[mid] for mid in range(1, 21)}
            if pd:
                for mid in range(1, 21):
                    a = jid2act[jmap[mid]]
                    tau = KP[mid] * (qd[mid] - d.qpos[qadr[mid]]) - KD[mid] * d.qvel[dof[mid]]
                    d.ctrl[a] = max(-EFF[mid], min(EFF[mid], tau))
                mujoco.mj_step(m, d)
            else:
                for mid in range(1, 21):
                    d.qpos[qadr[mid]] = qd[mid]
                d.qpos[fa + 2] = 0.2255
                mujoco.mj_forward(m, d)
            v.sync()

if __name__ == "__main__":
    main()
