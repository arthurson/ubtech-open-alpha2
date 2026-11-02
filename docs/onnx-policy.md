# ONNX policy deployment (Microduck-style)

Open Alpha2 can run a learned policy exported as `policy.onnx`, the same shape of workflow as
[Microduck](https://github.com/pollen-robotics/microduck_rl): train in simulation → export ONNX
(normalizer baked into the graph) → copy a small directory to the robot → an on-robot runtime runs it
behind a fixed observation contract. The contract is the one used by
[`ubtech-alpha2-urdf`](https://github.com/arthurson/ubtech-alpha2-urdf) (`rl/alpha2_env.py`), so
policies trained there load here.

> **Status: unverified on hardware.** The runtime is unit-tested (parity against onnxruntime, contract
> math, safety envelope) and the whole app compiles, but no policy has been run on a real Alpha2.
> Start with `dry=true` (the default) and read the section *Limits you must know about*.

## Web panel (no adb needed)

Panel → **實驗 / Experimental** tab → **🧠 ONNX Policy** (turn the card on):

1. Pick one `.zip` (e.g. `policy.onnx` + `ref.json`; `manifest.json` / `selftest.json` optional; other
   files such as `model.zip` / `meta.json` are ignored), or pick a `.onnx` directly (for a walk policy
   also pick `ref.json` — the browser packs them into a zip for you).
2. Name it, fill *control rate Hz* (5 = 200 ms; must equal the rate the policy was trained at) and *speed cap*, press **Upload & load**.
   Without a `manifest.json` the server builds one from these fields (obs inferred from the model:
   43 = stand, 65 = walk) and computes the SHA-256 itself. The upload is validated (supported ops,
   dims, ref) before it replaces an existing policy of the same name; a failed upload leaves nothing behind.
3. **Run**: *dry-run* is on by default (servos untouched); untick it for a real run (a confirmation
   dialog follows). Period < 200 ms policies need the *fast* box. Live steps / tilt / timing / overruns
   / end reason are shown; **Stop** is a hard stop. Uploads made from the panel have no `selftest.json`,
   so the panel shows "not cross-checked against onnxruntime" — use `policy_tool.py pack` if you want that.

API: `POST /upload/policy`, `policy/{list,load,start,beat,stop,status,delete}`.

## Pipeline (command line)

```
ubtech-alpha2-urdf  rl/train.py  →  policy.onnx
open-alpha2         scripts/policy_tool.py pack …   →  policies/<name>/{policy.onnx,manifest.json,selftest.json[,ref.json]}
adb push            → <externalFilesDir>/policies/<name>/     (/sdcard/Android/data/com.open.alpha2/files/policies/<name>)
HTTP                policy/load → policy/start (dry) → policy/start?dry=false
```

```bash
python scripts/policy_tool.py pack --onnx policy.onnx --name alpha2-stand-v1 \
    --obs stand --control-hz 5 --out out/alpha2-stand-v1
# walk policy trained at a 200 ms control step:
python scripts/policy_tool.py pack --onnx policy.onnx --name walk-5hz --obs walk \
    --control-hz 5 --ref ref.json --out out/walk-5hz
adb push out/alpha2-stand-v1 /sdcard/Android/data/com.open.alpha2/files/policies/alpha2-stand-v1
curl -X POST 'http://<robot>:8888/api/policy/load?name=alpha2-stand-v1'
curl -X POST 'http://<robot>:8888/api/policy/start'              # dry-run (default)
curl      'http://<robot>:8888/api/policy/status'                # tilt, action, servo targets
curl -X POST 'http://<robot>:8888/api/policy/start?dry=false'    # real motion (period >= 200 ms)
curl -X POST 'http://<robot>:8888/api/policy/start?dry=false&fast=true&max_speed_dps=60'  # period < 200 ms, experimental
curl -X POST 'http://<robot>:8888/api/policy/stop'
```

`pack` refuses a model whose input is not 43/65, output is not 20, or that uses an op the on-robot
runtime lacks, writes the SHA-256 into the manifest, and records 8 onnxruntime reference cases in
`selftest.json`. On `policy/load` the robot re-runs those cases in its own JVM and checks the SHA-256;
a real run is refused unless both pass.

## Contract (same as `rl/alpha2_env.py`)

| | stand | walk (residual) |
|---|---|---|
| obs | 43 = `q-q0` (20) · previous action (20) · gravity dir in base frame (3) | 65 = stand obs · `ref_q-q0` (20) · `sin,cos(gait phase)` (2) |
| action | 20, clipped to [-1, 1] | 20, clipped to [-1, 1] |
| target | `q0 + act_scale·a` | `ref_q + act_scale·a` |

* Joint order = servo ID 1..20. `servo° = degrees(q) + servo_at_q0`; `q0` = official boot stance.
  Constants live in `Alpha2PolicyIo` and are cross-checked against `config/servo_map.yaml` by
  `scripts/test-policy.py --urdf-repo <path>`.
* Gravity direction: upright = (0,0,−1), derived from the head accelerometer through the head joints
  (`Rz(−yaw)·Ry(pitch)`, as in the URDF).
* Targets are clamped to the calibrated servo range (`ApiValidator.SERVO_CAL_MIN/MAX`).
* Normalization must be inside the ONNX graph; the runtime never normalizes.

`manifest.json` (schema 1): `name, obs, obs_dim, action_dim, act_scale, control_hz, move_overlap,
accel_axes, ref, max_speed_dps, max_run_s, lease_ms, tilt_limit_deg, onnx_sha256, notes`.

## Limits you must know about

1. **Control period floor: 200 ms (a 50 Hz real-robot test was erratic).** Microduck drives servos every
   20 ms; on Alpha2 the command path is a 20-axis integer-degree waypoint plus a move time (the servo
   interpolates it). A real test of a 50 Hz policy made the robot flail, so normal real runs now start
   at a **200 ms** period (5 Hz) — still below the walk engine's own 300 ms gap, and still unverified
   on hardware. `control_hz` in the manifest is the rate the policy was *trained* at; the runtime
   always runs at that rate, so **a 50 Hz-trained policy is not a 4 Hz policy** — slowing it down is
   out-of-distribution, not a conversion (the shipped `walk_forward.onnx`/`alpha2-policy.zip` models
   were trained at a 20 ms step: their `ref.json` has `dt: 0.02`). Training at a 200 ms control step is
   what makes a policy fit this floor. Period < 200 ms stays available as the experimental `fast`
   mode (`policy/start?fast=true`, floor 20 ms, overrun detection) but is discouraged.
   For walk policies the reference clip advances by real time: `period / ref.dt` frames per step
   (10 at 200 ms, linearly interpolated), so a policy trained at the same period sees the same ref.
2. **Sensing is weak.** No IMU: one head accelerometer (smoothed, includes linear acceleration), and
   joint positions are the *commanded* pose (dead reckoning), not measured. Servo resolution is 1°.
3. **Accelerometer axes are unverified.** The URDF assumes sensor axes = head axes; `accel_axes`
   (e.g. `-y+x+z`) remaps them. Verify with dry-run: tilt the robot and watch `tiltDeg` in
   `policy/status`.
4. **Runtime scope.** Pure-Java ONNX (no onnxruntime-android: it needs minSdk 21 and ~15 MB/ABI, the
   APK supports API 19). Supported ops: Gemm, MatMul, Add, Sub, Mul, Div, Neg, Relu, LeakyRelu, Elu,
   Tanh, Sigmoid, Clip, Identity, Flatten, Reshape, Cast(→float), Constant, Concat. Single input,
   float32, batch 1. **Recurrent policies (LSTM, `h_in/c_in`, e.g. some Microduck policies) are not
   supported**; neither are policies that need history stacks beyond what the obs contract carries.

## Safety envelope (all enforced in `PolicyRunner`, the policy output is untrusted)

* dry-run by default; real run needs `dry=false`, boot stand-up done, known servo pose,
  accelerometer data, hash + selftest checks (+ `fast=true` for period < 200 ms).
* Per-step, per-axis change ≤ `max_speed_dps` × move time (default 90°/s; the official gait peaks at
  60°/s), so the first step from the boot pose cannot jump to the policy target.
* Lease: a running policy needs a heartbeat every `lease_ms` (default 5 s): `POST policy/beat` (renews
  and returns the status; never starts anything). The panel sends it every 500 ms while running — plain
  `policy/status` polling does **not** renew. Without heartbeats the run ends with `lease`, so a
  closed/backgrounded tab or lost connection stops the robot.
* Fall guard: tilt must exceed `tilt_limit_deg` (default 40°, override with `policy/start?tilt_limit_deg=`)
  for **3 consecutive steps** (immediately if already over the limit at step 0) — single spikes from
  walking accelerations are ignored. The accelerometer axis mapping is unverified, so a wrong mapping
  looks like a fall.
* Diagnostics: after a run ends, `policy/status` carries `endReason` and the last 64 steps (`trace`:
  step, tilt°, gravity xyz, smoothed accel xyz, max |action|, max servo change°, step ms); the panel
  shows the last 12 and has a *Copy diagnostics* button. Also `adb logcat -s PolicyCenter`.
* Stops (servos hold the last pose): tilt > `tilt_limit_deg` (`fall`), invalid gravity ×3
  (`gravity_invalid`), NaN/Inf action (`bad_action`), 5 send failures (`send_failed`), lease expiry
  (`lease`, renewed by calling `policy/start` again), missed beats (`overrun`), `max_run_s`, `policy/stop`, or any other
  action/stop (play-thread preemption, same as the walk engine).

## Tests

`python scripts/test-policy.py [--urdf-repo ../ubtech-alpha2-urdf]` (needs `pip install -r
scripts/requirements.txt` and a JDK): generates fixtures with onnxruntime, checks the Java runner
against it (< 2e-5; real `walk_forward.onnx` fixture included), the gravity/servo math against an
independent derivation, manifest validation, and runner safety behavior with a fake sender.
