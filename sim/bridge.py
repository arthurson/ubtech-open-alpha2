#!/usr/bin/env python3
"""sim2real bridge: URDF(rad, 0=InitAngle) <-> Alpha2 servo(0-255) <-> HTTP /api.

換算: rad = (servo - INIT) * pi/180 * SIGN, SIGN預設+1, 真機每軸+10步校方向.
來源: Alpha2_pc/Robot/alpha2.xml (INIT), ubx_walk_humanize.py CAL, DirectChestController cmd3/cmd5.
用法:
  python bridge.py --check                 # 自檢換算+CAL鉗位
  python bridge.py --send 1:120 8:65 ...   # 經HTTP送單幀 (需 --host)
  python bridge.py --replay traj.csv        # 播CSV軌跡 (col=servo1..20,time_ms)
"""
import math, sys, time, urllib.request, json

INIT = [120,120,120,120,120,120,120,120,65,145,135,120,120,175,95,105,120,120,120,120]
CAL = {1:(5,235),2:(50,210),3:(55,185),4:(5,235),5:(30,190),6:(55,185),7:(100,200),
       8:(20,220),9:(35,230),10:(35,215),11:(100,190),12:(40,140),13:(20,220),
       14:(10,205),15:(25,205),16:(50,140),17:(95,125),18:(95,125),19:(75,165),20:(105,155)}
# SIGN只影響rad顯示/URDF方向, 唔影響--send/--replay(送servo碼本身).
# j2/j5暫定-1(sim證據: +會掃穿torso); 真機每軸+10步核實後再改.
SIGN = {i: (-1 if i in (1,2,3,5,7,8,9,10,12) else 1) for i in range(1, 21)}
D2R = math.pi/180.0
MIN_GAP = 0.15  # 胸板串口最密150ms, 見FaceTrackCenter SERVO_MIN_GAP_MS

URDF_JOINTS = ["j1_r_shoulder_pitch","j2_r_shoulder_roll","j3_r_elbow",
 "j4_l_shoulder_pitch","j5_l_shoulder_roll","j6_l_elbow",
 "j7_r_hip_roll","j8_r_hip_pitch","j9_r_knee","j10_r_ankle_pitch","j11_r_ankle_roll",
 "j12_l_hip_roll","j13_l_hip_pitch","j14_l_knee","j15_l_ankle_pitch","j16_l_ankle_roll",
 "j17_r_finger","j18_l_finger","j19_head_yaw","j20_head_pitch"]
ID_OF = {n: i+1 for i, n in enumerate(URDF_JOINTS)}

def clamp_servo(i, v):
    lo, hi = CAL[i]
    return max(lo, min(hi, int(round(v))))

def servo_to_rad(i, servo):
    """i=1..20 -> rad (URDF joint值, 0=INIT)"""
    return (clamp_servo(i, servo) - INIT[i-1]) * D2R * SIGN[i]

def rad_to_servo(i, rad):
    return clamp_servo(i, INIT[i-1] + rad / D2R * SIGN[i])

def urdf_to_servos(q):
    """q: dict {urdf_joint_name: rad} -> [20] servo"""
    out = list(INIT)
    for n, r in q.items():
        i = ID_OF[n]
        out[i-1] = rad_to_servo(i, r)
    return out

def servos_to_urdf(s):
    return {n: servo_to_rad(ID_OF[n], s[ID_OF[n]-1]) for n in URDF_JOINTS}

def send_all(host, servos, move_ms=300):
    body = json.dumps({"angles": [clamp_servo(i+1, v) for i, v in enumerate(servos)],
                       "time": max(20, min(32767, move_ms))}).encode()
    req = urllib.request.Request(f"http://{host}:8888/api/alpha2/servo/all",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status, r.read()[:200]

def self_check():
    assert len(INIT) == 20 and len(URDF_JOINTS) == 20
    for i in range(1, 21):  # INIT必須在CAL內, 往返誤差<1格
        lo, hi = CAL[i]
        assert lo <= INIT[i-1] <= hi, i
        r = servo_to_rad(i, INIT[i-1])
        assert abs(r) < 1e-9, (i, r)
        assert rad_to_servo(i, r) == INIT[i-1], i
    assert servo_to_rad(1, 235) * SIGN[1] > 0 and servo_to_rad(1, 5) * SIGN[1] < 0
    assert rad_to_servo(8, 10.0 * SIGN[8]) == CAL[8][1]  # clamp上界
    assert rad_to_servo(8, -10.0 * SIGN[8]) == CAL[8][0]  # clamp下界
    print("bridge self-check OK: 20軸歸零/CAL鉗位/往返一致")

def replay_csv(host, path, step_ms=200):
    """播traj.csv落真機: servo碼直送(唔經SIGN), 重採樣到step_ms間隔(胸板串口≥150ms).
    開場自動2s lead-in由INIT沉入首格(防直扯reboot)."""
    import csv as _csv
    import time as _time
    rows = []
    with open(path) as f:
        for r in _csv.DictReader(f):
            rows.append((int(r["t_ms"]), [clamp_servo(i, int(r[f"s{i}"])) for i in range(1, 21)],
                         max(20, min(32767, int(r["move_ms"])))) )
    first = rows[0][1]
    n_lead = max(1, int(2000 / step_ms))
    print(f"lead-in {n_lead} steps...", flush=True)
    for k in range(1, n_lead + 1):
        sv = [INIT[i] + (first[i] - INIT[i]) * k // n_lead for i in range(20)]
        send_all(host, sv, step_ms)
        _time.sleep(step_ms / 1000.0)
    # 重採樣: 每step_ms取一格(線性插值servo碼)
    t_end = rows[-1][0]
    seg = 0
    t = 0
    while t <= t_end:
        while seg + 1 < len(rows) and rows[seg + 1][0] <= t:
            seg += 1
        if seg + 1 < len(rows):
            t0, a0, _ = rows[seg]; t1, a1, _m = rows[seg + 1]
            al = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            sv = [int(round(a0[i] + (a1[i] - a0[i]) * al)) for i in range(20)]
            mv = max(step_ms, _m // max(1, int((t1 - t0) / step_ms)))
        else:
            sv, mv = rows[seg][1], step_ms
        send_all(host, sv, mv)
        if int(t) % 2000 < step_ms:
            print(f"  t={int(t)}ms", flush=True)
        _time.sleep(step_ms / 1000.0)
        t += step_ms
    print("replay done")

if __name__ == "__main__":
    if "--check" in sys.argv or len(sys.argv) == 1:
        self_check()
    elif "--send" in sys.argv:
        host = "192.168.1.100"
        if "--host" in sys.argv:
            host = sys.argv[sys.argv.index("--host")+1]
        servos = list(INIT)
        for tok in sys.argv[sys.argv.index("--send")+1:]:
            if ":" not in tok: break
            i, v = tok.split(":"); servos[int(i)-1] = clamp_servo(int(i), int(v))
        print(send_all(host, servos))
    elif "--replay" in sys.argv:
        host = "192.168.1.100"
        if "--host" in sys.argv:
            host = sys.argv[sys.argv.index("--host")+1]
        replay_csv(host, sys.argv[sys.argv.index("--replay")+1])
    else:
        print(__doc__)
