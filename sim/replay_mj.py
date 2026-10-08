#!/usr/bin/env python3
"""sim replay: CSV軌跡 -> MuJoCo PD追蹤(模擬舵機) -> 對軌報告.

用法: python sim/replay_mj.py sim/traj_demo.csv [--out sim/replay_log.npz]
servo->rad: (servo-INIT)*pi/180, CAL鉗位 (同bridge.py一致, SIGN全+1)
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
D2R = math.pi / 180.0
# SIGN: +1預設; j2/j5肩roll反轉(否則手臂向內掃穿torso, non-adjacent contact反車).
# 真機方向待硬件+10步校準, 校完同步改bridge.py.
SIGN = {i: (-1 if i in (1,2,3,5,7,8,9,10,12) else 1) for i in range(1, 21)}
# 每摩打PD+力矩上限(真機: 腳20kg.cm=2.0Nm, 臂~1.2Nm, 頭手8kg.cm=0.8Nm, 指0.3Nm)
EFFORT = {i: (2.0 if 7 <= i <= 16 else 1.2 if i <= 6 else 0.8 if i >= 19 else 0.3) for i in range(1, 21)}
KP = {i: (10.0 if 7 <= i <= 16 else 3.0 if i <= 6 else 1.0 if i >= 19 else 0.3) for i in range(1, 21)}
KD = {i: (1.0 if 7 <= i <= 16 else 0.3 if i <= 6 else 0.1 if i >= 19 else 0.03) for i in range(1, 21)}

def clamp(i, v):
    lo, hi = CAL[i]
    return max(lo, min(hi, int(round(v))))

def servo_to_rad(i, s):
    return (clamp(i, s) - INIT[i - 1]) * D2R * SIGN[i]

def load_traj(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            angs = [int(r[f"s{i}"]) for i in range(1, 21)]
            rows.append((int(r["t_ms"]), angs, int(r["move_ms"])))
    return rows

def main():
    traj_path = sys.argv[1]
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    mjcf = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\alpha2_real_mjcf.xml'
    m = mujoco.MjModel.from_xml_path(mjcf)
    # motor id -> actuator index (actuator order == URDF doc order == URDF_JOINTS? verify by transmission target)
    jnt_id = {}
    for n in URDF_JOINTS:
        jid = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n)
        assert jid >= 0, n
        jnt_id[n] = jid
    # actuator i drives joint trnjoint? actuator order follows transmission order; assume same as URDF doc order
    act_jnt = [int(m.actuator_trnid[i][0]) for i in range(m.nu)]
    assert m.nu == 20
    # motor i -> actuator idx: find actuator whose joint name maps to motor i
    jid_of_motor = {}
    for n, mid in ID_OF.items():
        jid_of_motor[mid] = jnt_id[n]
    jid_to_act = {j: a for a, j in enumerate(act_jnt)}
    act_of_motor = {mid: jid_to_act[jid_of_motor[mid]] for mid in range(1, 21)}
    dof_of_motor = {mid: int(m.jnt_dofadr[jid_of_motor[mid]]) for mid in range(1, 21)}
    qpos_of_motor = {mid: int(m.jnt_qposadr[jid_of_motor[mid]]) for mid in range(1, 21)}

    rows = load_traj(traj_path)
    first = [float(x) for x in rows[0][1]]
    seq = []
    for k in range(41):
        seq.append((k * 50, [INIT[i] + (first[i] - INIT[i]) * k / 40 for i in range(20)]))
    for t_ms, angs, mv in rows:
        seq.append((t_ms + 2000, [float(x) for x in angs]))
    # 轉rad + 逐段線性插值餵PD
    qdes = {mid: servo_to_rad(mid, INIT[mid - 1]) for mid in range(1, 21)}
    d = mujoco.MjData(m)
    mujoco.mj_resetData(m, d)
    free_adr = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, "floating_base")
    d.qpos[m.jnt_qposadr[free_adr] + 2] = 0.25
    dt = m.opt.timestep
    log_t, log_des, log_q, log_base = [], [], [], []
    log_quat, log_ncon = [], []
    si = 0
    t_end = seq[-1][0] / 1000.0 + 1.0
    steps = int(t_end / dt)
    seg_idx = 0
    for st in range(steps):
        t = st * dt * 1000.0
        while seg_idx + 1 < len(seq) and seq[seg_idx + 1][0] <= t:
            seg_idx += 1
        # 線性插值到下一格(用move時間語義簡化: 按格間直線走)
        if seg_idx + 1 < len(seq):
            t0, a0 = seq[seg_idx]; t1, a1 = seq[seg_idx + 1]
            al = 0.0 if t1 == t0 else min(1.0, max(0.0, (t - t0) / (t1 - t0)))
        else:
            a0 = a1 = seq[seg_idx][1]; al = 1.0
        for mid in range(1, 21):
            sv = a0[mid - 1] + (a1[mid - 1] - a0[mid - 1]) * al
            qd = servo_to_rad(mid, sv)
            qdes[mid] = qd
            a = act_of_motor[mid]
            dof = dof_of_motor[mid]
            q = d.qpos[qpos_of_motor[mid]]
            qd_ = d.qvel[dof]
            tau = KP[mid] * (qd - q) - KD[mid] * qd_
            d.ctrl[a] = max(-EFFORT[mid], min(EFFORT[mid], tau))
        mujoco.mj_step(m, d)
        if st % 5 == 0:
            log_t.append(t / 1000.0)
            log_des.append([qdes[mid] for mid in range(1, 21)])
            log_q.append([d.qpos[qpos_of_motor[mid]] for mid in range(1, 21)])
            log_base.append(d.qpos[m.jnt_qposadr[free_adr] + 2])
            log_quat.append([d.qpos[m.jnt_qposadr[free_adr] + k] for k in range(3, 7)])
            log_ncon.append(d.ncon)
    log_t = np.array(log_t); log_des = np.array(log_des); log_q = np.array(log_q)
    err = np.abs(log_des - log_q)
    print(f"frames={len(rows)} sim={t_end:.1f}s steps={steps}")
    print(f"track err: max={err.max():.4f}rad mean={err.mean():.5f}rad")
    for grp, mids in [("arms", [1,2,3,4,5,6]), ("legs", list(range(7,17))),
                      ("fingers", [17,18]), ("head", [19,20])]:
        e = err[:, [mid - 1 for mid in mids]]
        print(f"  {grp:8s} max={e.max():.4f} mean={e.mean():.5f}")
    print(f"base_z: min={np.min(log_base):.3f} final={log_base[-1]:.3f} "
          f"{'FELL' if np.min(log_base) < 0.18 else 'STANDING'}")
    if out:
        np.savez(out, t=log_t, des=log_des, q=log_q, base=np.array(log_base),
                 quat=np.array(log_quat), ncon=np.array(log_ncon))
        print("saved", out)

if __name__ == "__main__":
    main()
