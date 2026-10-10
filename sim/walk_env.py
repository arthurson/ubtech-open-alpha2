#!/usr/bin/env python3
"""Alpha2練行路gym環境: 下盤10軸PD + 上身鎖q_init, 速度追蹤獎勵.
obs: base高/角速度/重力投影 + 20關節角/角速度 + 指令速度 (47dim)
act: 10個下盤關節目標(rad, 以q_init為中心, +-0.5rad)
"""
import math
import numpy as np
import mujoco
import yaml

try:
    import gymnasium as gym
    from gymnasium import spaces
except ImportError:
    gym, spaces = None, None

PKG = r'C:\Users\user\Desktop\alpha2_description'
MJCF = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\alpha2_desc_mjcf.xml'
MP = yaml.safe_load(open(PKG + r'\config\servo_map.yaml'))
J = {e['joint']: e for e in MP['joints']}
JNAMES = [e['joint'] for e in MP['joints']]
LEG = ['r_hip_roll_joint', 'r_hip_pitch_joint', 'r_knee_joint',
       'r_ankle_pitch_joint', 'r_ankle_roll_joint',
       'l_hip_roll_joint', 'l_hip_pitch_joint', 'l_knee_joint',
       'l_ankle_pitch_joint', 'l_ankle_roll_joint']
QINIT = {e['joint']: math.radians(e['pc_init_angle'] + e['pc_offset']) for e in MP['joints']}
KP = 12.0
KD = 1.0

class WalkEnv(gym.Env if gym else object):
    metadata = {'render_modes': []}

    def __init__(self, cmd_speed=0.3, max_steps=1000):
        self.m = mujoco.MjModel.from_xml_path(MJCF)
        # effort上限: MJCF joint range對應limit->用URDF effort
        self.jm = {n: mujoco.mj_name2id(self.m, mujoco.mjtObj.mjOBJ_JOINT, n) for n in JNAMES}
        act = [int(self.m.actuator_trnid[i][0]) for i in range(self.m.nu)]
        self.j2a = {}
        for n in JNAMES:
            al = [a for a, jj in enumerate(act) if jj == self.jm[n]]
            assert al, n
            self.j2a[n] = al[0]
        self.fb = mujoco.mj_name2id(self.m, mujoco.mjtObj.mjOBJ_JOINT, 'floating_base')
        self.fa = self.m.jnt_qposadr[self.fb]
        self.qadr = {n: int(self.m.jnt_qposadr[self.jm[n]]) for n in JNAMES}
        self.dof = {n: int(self.m.jnt_dofadr[self.jm[n]]) for n in JNAMES}
        lim = {}
        for n in JNAMES:
            j = self.jm[n]
            lim[n] = (float(self.m.jnt_range[j][0]), float(self.m.jnt_range[j][1]))
        self.lim = lim
        self.cmd_speed = cmd_speed
        self.max_steps = max_steps
        self.action_space = spaces.Box(-0.5, 0.5, shape=(len(LEG),), dtype=np.float32)
        self.observation_space = spaces.Box(-np.inf, np.inf, shape=(46,), dtype=np.float32)
        self.d = None
        self.step_n = 0

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        self.d = mujoco.MjData(self.m)
        mujoco.mj_resetData(self.m, self.d)
        for n in JNAMES:
            self.d.qpos[self.qadr[n]] = QINIT[n]
        self.d.qpos[self.fa + 2] = 0.25
        mujoco.mj_forward(self.m, self.d)
        self.step_n = 0
        return self._obs(), {}

    def _obs(self):
        d = self.d
        quat = d.qpos[self.fa + 3:self.fa + 7].copy()
        # 重力投影到body
        import math as _m
        w, x, y, z = quat
        gx = 2 * (x * z - w * y)
        gy = 2 * (y * z + w * x)
        gz = 1 - 2 * (x * x + y * y)
        q = np.array([d.qpos[self.qadr[n]] for n in JNAMES])
        qv = np.array([d.qvel[self.dof[n]] for n in JNAMES])
        vx = d.qvel[self.m.jnt_dofadr[self.fb]]
        return np.concatenate([[d.qpos[self.fa + 2], gx, gy, gz, vx, self.cmd_speed], q, qv]).astype(np.float32)

    def step(self, action):
        d = self.d
        tgt = {}
        for k, n in enumerate(LEG):
            lo, hi = self.lim[n]
            v = QINIT[n] + float(np.clip(action[k], -0.5, 0.5))
            tgt[n] = min(hi, max(lo, v))
        for n in JNAMES:
            des = tgt.get(n, QINIT[n])
            a = self.j2a[n]
            tau = KP * (des - d.qpos[self.qadr[n]]) - KD * d.qvel[self.dof[n]]
            eff = 2.0 if ('hip' in n or 'knee' in n or 'ankle' in n) else 1.2
            d.ctrl[a] = max(-eff, min(eff, tau))
        mujoco.mj_step(self.m, d)
        self.step_n += 1
        vx = d.qvel[self.m.jnt_dofadr[self.fb]]
        h = d.qpos[self.fa + 2]
        w, x, y, z = d.qpos[self.fa + 3:self.fa + 7]
        up = 1 - 2 * (x * x + y * y)
        alive = h > 0.12 and up > 0.4 and self.step_n < self.max_steps
        r_vel = math.exp(-4.0 * (vx - self.cmd_speed) ** 2)
        r_up = up
        e = sum(float(abs(d.ctrl[self.j2a[n]])) for n in LEG) / len(LEG)
        rew = 1.0 * r_vel + 0.5 * r_up - 0.05 * e + 0.1
        return self._obs(), float(rew), (not alive), False, {}

if __name__ == '__main__':
    e = WalkEnv()
    o, _ = e.reset()
    for _ in range(200):
        o, r, term, trunc, _ = e.step(e.action_space.sample())
        if term:
            break
    print('smoke ok, obs', o.shape, 'last rew', round(r, 3))
