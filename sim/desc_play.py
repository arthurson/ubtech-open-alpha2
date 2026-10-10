#!/usr/bin/env python3
"""alpha2_description (user's URDF) ubx pipeline, yaml-driven.
q_rad = radians(clamp(servo,0,240) + pc_offset).  zero pose = URDF standing.
用法:
  python sim/desc_play.py --audit sim/traj_108.csv
  python sim/desc_play.py --replay sim/traj_108.csv [--out sim/desc_108.npz]
  python sim/desc_play.py --watch sim/traj_108.csv [--loop] [--pd]
"""
import csv, sys, math, time
import numpy as np
import mujoco, yaml

PKG = r'C:\Users\user\Desktop\alpha2_description'
MJCF = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\alpha2_desc_mjcf.xml'
MP = yaml.safe_load(open(PKG + r'\config\servo_map.yaml'))
J = {e['servo_id']: e for e in MP['joints']}
JNAMES = [e['joint'] for e in MP['joints']]
D2R = math.pi / 180.0
INIT_SV = {e['servo_id']: e['pc_init_angle'] for e in MP['joints']}
SVMIN = {e['servo_id']: e.get('servo_min', 0) for e in MP['joints']}
SVMAX = {e['servo_id']: e.get('servo_max', 240) for e in MP['joints']}

def clamp_sv(v, sid=None):
    if sid is None:
        return max(0, min(240, int(round(v))))
    return max(SVMIN[sid], min(SVMAX[sid], int(round(v))))

def servo_to_q(sid, sv):
    return math.radians(clamp_sv(sv, sid) + J[sid]['pc_offset'])

def load_traj(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_ms"]), [int(r[f"s{i}"]) for i in range(1, 21)]))
    return rows

def build_seq(rows, lead_ms=2000, step=50):
    first = [float(x) for x in rows[0][1]]
    seq = []
    n = max(1, int(lead_ms / step))
    for k in range(n + 1):
        seq.append((k * step, [INIT_SV[i] + (first[i - 1] - INIT_SV[i]) * k / n for i in range(1, 21)]))
    for t_ms, angs in rows:
        seq.append((t_ms + lead_ms, [float(x) for x in angs]))
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

def get_model():
    m = mujoco.MjModel.from_xml_path(MJCF)
    jm = {e['joint']: mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, e['joint']) for e in MP['joints']}
    act = [int(m.actuator_trnid[i][0]) for i in range(m.nu)]
    j2a = {}
    for e in MP['joints']:
        j = jm[e['joint']]
        al = [a for a, jj in enumerate(act) if jj == j]
        assert al, e['joint']
        j2a[j] = al[0]
    fb = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_JOINT, 'floating_base')
    return m, jm, j2a, fb

def do_audit(path):
    m, jm, _, _ = get_model()
    base = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, 'base_link')
    d = mujoco.MjData(m)
    rows = load_traj(path)
    worst = (1e9, 0)
    for t_ms, angs in rows:
        mujoco.mj_resetData(m, d)
        for sid in range(1, 21):
            e = J[sid]
            d.qpos[int(m.jnt_qposadr[jm[e['joint']]])] = math.radians(clamp_sv(angs[sid - 1], sid) + e['pc_offset'])
        mujoco.mj_forward(m, d)
        com = d.subtree_com[base][:2].copy()
        corners = []
        soles = {}
        for link in ['l_ankle_roll_link', 'r_ankle_roll_link']:
            bi = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, link)
            p = d.xpos[bi]
            soles[link] = float(p[2]) - 0.035
        floor = min(soles.values())
        for link in ['l_ankle_roll_link', 'r_ankle_roll_link']:
            if soles[link] - floor > 0.005:
                continue
            bi = mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, link)
            p = d.xpos[bi]
            R = d.xmat[bi].reshape(3, 3)
            for dx, dy in ((0.05, 0.024), (0.05, -0.024), (-0.03, 0.024), (-0.03, -0.024)):
                corners.append(p[:2] + (R[:2, :2] @ np.array([dx, dy])))
        if not corners:
            mg = -999.0
        else:
            corners = np.array(corners)
            cx0, cy0 = corners.min(0); cx1, cy1 = corners.max(0)
            inside = (cx0 <= com[0] <= cx1) and (cy0 <= com[1] <= cy1)
            mg = min(com[0] - cx0, cx1 - com[0], com[1] - cy0, cy1 - com[1])
            if not inside:
                mg = -min(abs(com[0] - cx0), abs(com[0] - cx1)) if not (cx0 <= com[0] <= cx1) else -min(abs(com[1] - cy0), abs(com[1] - cy1))
        if mg < worst[0]:
            worst = (mg, t_ms)
    print(f'{path}: frames={len(rows)} min_margin={worst[0]*1000:+.1f}mm at t={worst[1]}')
    return worst[0] >= 0

def do_replay(path, out=None):
    m, jm, j2a, fb = get_model()
    fa = m.jnt_qposadr[fb]
    rows = load_traj(path)
    seq = build_seq(rows)
    KP = {e['joint']: 8.0 for e in MP['joints']}
    KD = {e['joint']: 0.8 for e in MP['joints']}
    EFF = {}
    for e in MP['joints']:
        n = e['joint']
        EFF[n] = 2.0 if ('hip' in n or 'knee' in n or 'ankle' in n) else 1.2
    qadr = {e['joint']: int(m.jnt_qposadr[jm[e['joint']]]) for e in MP['joints']}
    dof = {e['joint']: int(m.jnt_dofadr[jm[e['joint']]]) for e in MP['joints']}
    qdes = {}
    for e in MP['joints']:
        qdes[e['joint']] = math.radians(e['pc_init_angle'] + e['pc_offset'])
    d = mujoco.MjData(m)
    mujoco.mj_resetData(m, d)
    for n in JNAMES:
        d.qpos[qadr[n]] = qdes[n]
    d.qpos[fa + 2] = 0.03
    dt = m.opt.timestep
    t_end = seq[-1][0] / 1000.0 + 1.0
    log_t, log_des, log_q, log_base = [], [], [], []
    for st in range(int(t_end / dt)):
        t = st * dt * 1000.0
        sv = seq_at(seq, t)
        for sid in range(1, 21):
            n = J[sid]['joint']
            qdes[n] = math.radians(clamp_sv(sv[sid - 1], sid) + J[sid]['pc_offset'])
            a = j2a[jm[n]]
            tau = KP[n] * (qdes[n] - d.qpos[qadr[n]]) - KD[n] * d.qvel[dof[n]]
            d.ctrl[a] = max(-EFF[n], min(EFF[n], tau))
        mujoco.mj_step(m, d)
        if st % 5 == 0:
            log_t.append(t / 1000.0)
            log_des.append([qdes[n] for n in JNAMES])
            log_q.append([d.qpos[qadr[n]] for n in JNAMES])
            log_base.append(float(d.xpos[mujoco.mj_name2id(m, mujoco.mjtObj.mjOBJ_BODY, 'base_link')][2]))
    log_t = np.array(log_t); log_des = np.array(log_des); log_q = np.array(log_q)
    err = np.abs(log_des - log_q)
    base = np.array(log_base)
    print(f'frames={len(rows)} sim={t_end:.1f}s err_max={err.max():.4f} mean={err.mean():.5f}')
    print(f'base min={base.min():.3f} final={base[-1]:.3f} {"STANDING" if base.min() >= 0.17 else "FELL"}')
    if out:
        np.savez(out, t=log_t, des=log_des, q=log_q, base=base)
        print('saved', out)

def do_watch(path, loop=False, pd=False):
    import mujoco.viewer
    m, jm, j2a, fb = get_model()
    fa = m.jnt_qposadr[fb]
    rows = load_traj(path)
    seq = build_seq(rows)
    total = seq[-1][0] / 1000.0
    qadr = {}
    for e in MP['joints']:
        qadr[e['servo_id']] = int(m.jnt_qposadr[jm[e['joint']]])
    dof = {}
    for e in MP['joints']:
        dof[e['servo_id']] = int(m.jnt_dofadr[jm[e['joint']]])
    d = mujoco.MjData(m)
    mujoco.mj_resetData(m, d)
    for sid in range(1, 21):
        d.qpos[qadr[sid]] = math.radians(INIT_SV[sid] + J[sid]['pc_offset'])
    d.qpos[fa + 2] = 0.03
    with mujoco.viewer.launch_passive(m, d) as v:
        t0 = time.time()
        while v.is_running():
            el = time.time() - t0
            simt = el % total if loop else min(el, total)
            sv = seq_at(seq, simt * 1000.0)
            if pd:
                for sid in range(1, 21):
                    n = J[sid]['joint']
                    qd = math.radians(clamp_sv(sv[sid - 1], sid) + J[sid]['pc_offset'])
                    a = j2a[jm[n]]
                    tau = 8.0 * (qd - d.qpos[qadr[sid]]) - 0.8 * d.qvel[dof[sid]]
                    eff = 2.0 if ('hip' in n or 'knee' in n or 'ankle' in n) else 1.2
                    d.ctrl[a] = max(-eff, min(eff, tau))
                mujoco.mj_step(m, d)
            else:
                for sid in range(1, 21):
                    d.qpos[qadr[sid]] = math.radians(clamp_sv(sv[sid - 1], sid) + J[sid]['pc_offset'])
                mujoco.mj_forward(m, d)
            v.sync()

if __name__ == '__main__':
    a = sys.argv[1:]
    if a[0] == '--audit':
        do_audit(a[1])
    elif a[0] == '--replay':
        out = a[a.index('--out') + 1] if '--out' in a else None
        do_replay(a[1], out)
    elif a[0] == '--watch':
        do_watch(a[1], loop='--loop' in a, pd='--pd' in a)
