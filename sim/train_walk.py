#!/usr/bin/env python3
"""練行路: python sim/train_walk.py [--steps N] [--out sim/walk_ppo]
預設20k步smoke(幾分鐘, 驗pipeline); 真練要2M+步(幾粒鐘, 通宵跑).
"""
import sys
from stable_baselines3 import PPO
from stable_baselines3.common.vec_env import DummyVecEnv
from walk_env import WalkEnv

steps = int(sys.argv[sys.argv.index('--steps') + 1]) if '--steps' in sys.argv else 20000
out = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim\walk_ppo'
env = DummyVecEnv([lambda: WalkEnv()])
model = PPO('MlpPolicy', env, verbose=1, n_steps=2048, batch_size=256,
            learning_rate=3e-4, gamma=0.99, gae_lambda=0.95, clip_range=0.2)
model.learn(total_timesteps=steps)
model.save(out)
print('saved', out)
