#!/usr/bin/env python3
"""三格定真相: INIT企直(3s) -> A姿(4s) -> INIT(2s) -> B姿(4s) -> loop.
用法: python sim/demo_pose.py knees   (屈膝正負)
      python sim/demo_pose.py arms    (手臂外展正負)
      python sim/demo_pose.py hips    (大腿前後正負)
 joints用URDF名, 角度=弧度, 正負即SIGN+1/-1兩個候選.
"""
import sys, math, time
import numpy as np
import mujoco
import mujoco.viewer

MJCF = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\alpha2_real_mjcf.xml'
POSES = {
    "knees": ([], ["j9_r_knee", "j14_l_knee"], +0.35),
    "arms": ([], ["j2_r_shoulder_roll", "j5_l_shoulder_roll"], +1.0),
    "hips": ([], ["j8_r_hip_pitch", "j13_l_hip_pitch"], +0.4),
}
m = mujoco.MjModel.from_xml_path(MJCF)
d = mujoco.MjData(m)
mujoco.mj_resetData(m, d)
fb = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, "floating_base")
fa = m.jnt_qposadr[fb]
allj = []
for i in range(m.njnt):
    nm = mujoco.mj_id2name(m, mujoco.mjtObj.mjOBJ_JOINT, i)
    if nm and nm != "floating_base":
        allj.append(nm)
qadr = {n: int(m.jnt_qposadr[mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, n)]) for n in allj}
key = sys.argv[1] if len(sys.argv) > 1 else "knees"
_, joints, mag = POSES[key]
seq = [(0.0, {}), (3.0, {}),
       (4.0, {j: +mag for j in joints}), (8.0, {j: +mag for j in joints}),
       (10.0, {}), (11.0, {j: -mag for j in joints}), (15.0, {j: -mag for j in joints}),
       (17.0, {})]
total = 19.0
with mujoco.viewer.launch_passive(m, d) as v:
    t0 = time.time()
    while v.is_running():
        el = (time.time() - t0) % total
        i = 0
        while i + 1 < len(seq) and seq[i + 1][0] <= el:
            i += 1
        (t0s, a0), (t1s, a1) = seq[i], seq[min(i + 1, len(seq) - 1)]
        al = 0.0 if t1s == t0s else min(1.0, max(0.0, (el - t0s) / (t1s - t0s)))
        tgt = {}
        for n in allj:
            v0 = a0.get(n, 0.0); v1 = a1.get(n, 0.0)
            tgt[n] = v0 + (v1 - v0) * al
        for n in allj:
            d.qpos[qadr[n]] = tgt[n]
        d.qpos[fa + 2] = 0.2255
        mujoco.mj_forward(m, d)
        v.sync()
