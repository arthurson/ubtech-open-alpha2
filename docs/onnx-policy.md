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
3. **Run**: *dry-run* is on by default (servos untouched); untick it for a real run. Period < 200 ms
   policies need the *fast* box. Live steps / tilt / timing / overruns
   / end reason are shown; **Stop** is a hard stop. Uploads made from the panel have no `selftest.json`,
   so the panel shows "not cross-checked against onnxruntime" and **real runs are refused** (dry-run still works) — use `policy_tool.py pack` to generate a `selftest.json`.

API: `POST /upload/policy`, `policy/{list,load,unload,switch,start,beat,stop,status,delete}`.

## Preload several policies and hot-swap (Microduck-style)

`policy/load` **adds** a policy to a preload bank instead of replacing the previous one (max 8 policies /
16 MB of `.onnx` in total — a limit chosen for this robot, Microduck publishes no hard cap). Only one
policy drives the robot at any time; `policy/switch?name=` changes which one, **while running**:

```bash
for n in stand walk-5hz kick; do curl -X POST "http://<robot>:8888/api/policy/load?name=$n"; done
curl -X POST 'http://<robot>:8888/api/policy/start?name=stand&dry=false'   # starts on "stand"
curl -X POST 'http://<robot>:8888/api/policy/switch?name=walk-5hz'          # takes effect at the next step
curl -X POST 'http://<robot>:8888/api/policy/switch?name=stand'
curl -X POST 'http://<robot>:8888/api/policy/unload?name=kick'              # files stay on disk
```

What happens at a switch (all inside `PolicyRunner`, at a step boundary — a step in flight is never cut):

* The new policy brings its **own** `control_hz`/period, move time, `act_scale`, speed envelope and obs
  type (43 stand / 65 walk), so stand and walk policies can be mixed even though their obs differ.
* A walk policy's `ref` restarts from its start segment (frame 0), same as starting it from standing.
* `prev action` (part of the obs) is **converted**: `(last_target_q − new_base) / new_act_scale`, clipped
  to [−1, 1], so the new policy sees the same "last target" the old one produced instead of a stale value
  in the wrong action units.
* Lease, `max_run_s`, overrun and fall-guard settings stay those of the policy the run started with;
  `--speed`/`tilt_limit_deg` overrides apply to every policy in the run.
* Real runs only preload **eligible** policies (hash + a present, passing `selftest.json`; period < 200 ms only with
  `fast=true`). Others stay in the bank but `switch` to them is refused with the reason. Dry runs
  preload everything.
* `policy/load`, `upload/policy` and `policy/delete` work during a run for policies that are *not*
  active/pending; the active one can't be replaced, unloaded or deleted until you switch away.
* `status` has `bank[]` (per policy: `selected`, `active`, `inRun`, `needsFast`, …), `active`,
  `pendingSwitch`, `switches` and `switchLog`.

Switching is only as good as the policies are compatible: a stand policy that was never trained to start
from mid-stride will see an out-of-distribution pose if you cut in from walking. For clean transitions
train a transition/"recover" policy (what Microduck's runtime does) rather than relying on the speed
envelope alone.

## Get-up policies (lying → standing)

`obs: "getup"` runs policies trained by `GetUpEnv` in `ubtech-alpha2-urdf` (`task` = `getup_back` or
`getup_front`, i.e. lying on the back / on the front). Contract, identical to `rl/alpha2_env.py`:

* obs (65) = stand obs (43) + `(ref_q − q0)` (20) + `[min(t/T, 1), 1 once t ≥ T]`; `t` = reference frame, `T` = number of frames.
* target = `ref_q(t) + act_scale·a` (`act_scale` 0.2). The reference does **not** loop: after the last frame its
  final pose is held. `ref.json` needs `q` and `dt` only (no `loop`).
* Before the policy starts the robot first moves to reference frame 0 (≥ 1 s, like the training `SETTLE`),
  zero residual, through the speed envelope.
* The fall guard is **suspended while the reference plays** (the robot starts on the floor; training never
  terminates on a fall). After the reference ends the robot must hold `hold_s` (default 3 s = training `HOLD`);
  if the tilt is still above `tilt_limit_deg` for several steps the run ends with `fall` — i.e. the get-up failed.
  Otherwise it ends `done` (servos hold the standing pose) or, if the manifest has `"then": "<policy>"`, hands
  over to that preloaded policy (e.g. a stand policy) and keeps running.
* **Real runs refuse to start unless the robot is lying on the right side** (`not_lying`, servos untouched):
  gravity in the body frame must be ≈ (−1,0,0) for `getup_back`, ≈ (+1,0,0) for `getup_front` (|g_x| ≥ 0.8).
  Dry runs skip this so you can rehearse standing up. This relies on the accelerometer axis mapping
  (`accel_axes`, default `-y+x+z`: the sensor's y axis is front/back on the real robot) — check `gravity` in the diagnostics first.
* There is deliberately **no automatic "fell → get up"** trigger: a false fall detection (wrong axis mapping)
  would start a get-up from standing. Start it yourself with `policy/start?name=getup-back-v1&dry=false&fast=true`
  once the robot is really lying down; with `then` set, one start takes it all the way to a stand policy.

```bash
python3 scripts/policy_tool.py pack --obs getup --control-hz 50 --name getup-back-v1 \
    --onnx getup_back/<run>/policy.onnx --ref getup_back/<run>/ref.json --out getup-back-v1
# optional: --then stand-5hz   (hand over to a preloaded stand policy when finished)
```

`pack` reads `getup` (back/front) and `hold` from `ref.json`, and raises `max_speed_dps` automatically: the
official BackStand/FrontStand references reach ~250 °/s at joint level, so the default 90 °/s envelope would
cut the trajectory short and the robot would not get up. Like every policy trained with `DECIM=10`, these are
**50 Hz** policies, so real runs need `fast=true` (experimental, not validated on hardware).

## Changing the decision period (50–500 ms) to find what is still stable

A policy is trained at one decision rate (`control_hz`); the robot's serial bus only takes a step every
≥200 ms reliably. To find out how slow (or fast) a given policy still behaves, override the period instead
of re-packing it:

```bash
curl -X POST 'http://<robot>:8888/api/policy/start?name=walk-50hz&dry=true&period_ms=100'   # start at 100 ms
curl -X POST 'http://<robot>:8888/api/policy/period?ms=200'                                    # change it live
curl -X POST 'http://<robot>:8888/api/policy/period?ms=0'                                      # back to the policy's own
```

The panel has the same: a *Decision period* field (applied on Start) plus preset buttons 50 … 500 that change
it instantly while running.

What changes and what does not:

* The reference (walk/get-up) still advances in **real time** (`period / dt` frames per step; the position is
  accumulated, so changing the period mid-run does not make the trajectory jump). The wall-clock speed of the
  motion is unchanged; only *how often the policy decides* changes.
* Move time (`period × move_overlap`), the speed envelope per step (°/step) and the overrun detection follow the
  new period. The override applies to every policy in the run (also after a `policy/switch`).
* Real runs (non-dry): below 200 ms still needs `fast=true`; a non-fast run can't be pushed below 200 ms live and
  can't clear the override if that would drop a preloaded 50 Hz policy back to its own 20 ms.
* It does **not** retrain anything: the policy still expects `prev action`, `q − q0` etc. at its training rate, so
  slower than that is out-of-distribution. Step it up gradually (e.g. 20 → 50 → 100 → 200 → 300 …), watch `tilt`,
  `maxAct` and `overruns` in the status/diagnostics, and keep `dry=true` first. Where it stops being stable is the
  period you should retrain at (set `DECIM` accordingly in `alpha2_env.py`).

## Roles: "switch to walk" without remembering names

A policy's manifest `task` is its role: `stand`, `walk`, `getup_back`, `getup_front` (`obs: stand|walk` imply
the task of the same name; get-up policies must say which side). `status.roles` shows which preloaded policy
currently answers each role: an explicit binding if you made one, otherwise the **last loaded** policy with that
task.

```bash
curl -X POST 'http://<robot>:8888/api/policy/start?role=stand&dry=false'     # same as name=<whatever stand policy>
curl -X POST 'http://<robot>:8888/api/policy/switch?role=walk'               # hot-swap by role
curl -X POST 'http://<robot>:8888/api/policy/role?role=walk&name=walk-5hz'  # pin a role to one policy
curl -X POST 'http://<robot>:8888/api/policy/role?role=walk'                 # unpin
```

`name` and `role` are alternatives (giving both is an error). Unloading a policy drops its bindings. The panel
has one button per role under the preload list.

## Auto get-up after a fall (opt-in)

`policy/start?...&auto_recover=true` (panel: *Auto get-up after a fall*) lets the runner react to a fall while a
non-get-up policy is running:

1. The tilt guard trips as before (several consecutive steps over `tilt_limit_deg`). Instead of ending the run
   the robot goes **fallen**: no policy inference, no servo frames — the servos just hold the last pose.
2. If gravity then stays clearly on the back or front (|g_x| ≥ 0.8) for `recover_dwell_ms` (default 1500 ms,
   500–10000 — this filters the transient poses while falling), the runner switches to the preloaded
   `getup_back` / `getup_front` policy for that side (resolved by role at start) through the normal switch path.
3. The get-up runs as described above and then follows its manifest `then` (typically your stand policy), so the
   run carries on. `recover_max` (default 2, 1–5) bounds attempts per run; a get-up that ends still tilted counts
   as a failed attempt and retries until the budget is used.
4. It ends with `fall` exactly as before when: no get-up policy exists for that side, the budget is used up, the
   robot is lying on its side / not settled within ~10 s, or it was **already tilted at the first step** (that is
   not a fall — it started on the floor; use the get-up policy explicitly).

It is off by default because it is only as reliable as the accelerometer axis mapping (still unverified on
hardware): a false "fallen" reading while standing would start a get-up from standing. Real runs need the
get-up policies to be eligible too (50 Hz get-up policies need `fast=true`). Status: `autoRecover`, `recoveries`,
`fallen`.

## Standalone fall watchdog (`fall/*`, app level, actions)

The policy runner's `auto_recover` only exists inside a `policy/start` session. The Status
tab also has an app-level watchdog that runs even with no policy session (while a built-in
action is playing, while walking, or while idle). It does **not** use ONNX policies: it
plays the built-in `BackStand` (lying on its back) / `FrontStand` (face down) action.

* `fall/set?on=true&dwell_ms=1500&max_attempts=2&axes=...` enables it and saves the setting
  in `SharedPreferences` (it survives app/robot restarts). It keeps the accelerometer
  registered while enabled. `axes` blank means `-y+x+z` (the accelerometer's y axis is front/back: +y = face down → `FrontStand`, -y = on its back → `BackStand`).
* The state machine (`FallRecoveryDetector`, pure Java) starts **DISARMED** and only arms
  after 2 s upright. A fall is a tilt ≥ 55° for 300 ms; after that the robot must lie
  clearly on its back or front (`|g_x| ≥ 0.8`) for `dwell_ms` before the matching action
  is played. Side-lying (or not settling) gives up after 10 s without moving the servos.
  The actions must exist in `/sdcard/actions`; `fall/status` reports
  `backStandAvailable` / `frontStandAvailable`.
* While the action is playing the watchdog waits for it to finish; once the robot is
  upright for 800 ms it re-arms. If the action ends while the robot is still down, it
  waits `dwell_ms` and retries up to `max_attempts`; an action that cannot be started
  fails immediately.
* It stands down (`DISARMED`) while any other policy is running, so it cannot fight the
  runner's own `auto_recover`. A policy started during a get-up aborts that get-up.
* `fall/recover?side=back|front` plays the action manually (works even when the watchdog
  is off); `fall/status` reports `state` (`DISARMED`/`ARMED`/`FALLEN`/`RECOVERING`/
  `FAILED`), `side`, `tiltDeg`, attempts, recoveries, action availability and the last
  error; `fall/reset` clears `FAILED`.
* **Caveats**: the accelerometer axis mapping and the Neuron BackStand/FrontStand actions
  are unverified on hardware. Keep the robot supported/harnessed. Off by default.

## Unified format (obs 68, `action` + `delta`, velocity command) — `obs: "unified"`

Policies trained by the current `ubtech-alpha2-urdf` `rl/alpha2_env.py` (all tasks share one layout, control
step `CTRL_DT` = 0.1 s). The runtime builds exactly the vector the web player builds:

| obs | meaning |
|---|---|
| `[0:20]` | joint position − q_default (rad) |
| `[20:40]` | previous **action** output (−1..1) |
| `[40:43]` | gravity direction in the base frame |
| `[43:46]` | velocity command (vx m/s forward, vy m/s left, wz rad/s CCW); zeros unless task `walk` |
| `[46:48]` | phase: `stand`/`walk` = sin, cos of a free-running clock (`gait_period_s`, default 1 s); `walk_ref` = sin, cos of the reference loop (0, 0 before the loop starts); get-up = `[min(t/T,1), 1 once t ≥ T]` |
| `[48:68]` | base target − q_default (0 for stand/walk; reference − q_default for `walk_ref`/get-up) |

The ONNX has **two outputs**: `action` (goes back into obs) and `delta` (= `SCALE`·action, rad). Target =
`q_default + obs[48:68] + delta` (clamped to the servo limits). Tasks (`task` in the manifest, also the roles):
`stand`, `walk` (velocity command), `walk_ref`, `getup_back`, `getup_front`. Get-up keeps the behaviour described
above (lying check, settle, `then`, fall guard suspended while the reference plays); `T = ceil((len−1)·dt/CTRL_DT)+1`
control steps.

```bash
python3 scripts/policy_tool.py pack --obs unified --meta <run>/meta.json --onnx <run>/policy.onnx \
    --name walk-v1 --out walk-v1                          # task/ctrl_dt/act_scale/cmd_max come from meta.json
# walk_ref and get-up also need  --ref <run>/ref.json ;  get-up optionally  --then <stand policy>
curl -X POST 'http://<robot>:8888/api/policy/start?role=walk&dry=true'
curl -X POST 'http://<robot>:8888/api/policy/command?vx=0.05&vy=0&wz=0.2'   # keep refreshing, see below
```

* **Velocity command**: `policy/command` (panel: sliders under the preload list). Each axis is clamped to the
  policy's `cmd_max` (taken from `meta.json` `cfg.cmd_vx/vy/wz`), and the command **decays to zero if it is not
  refreshed within `commandTimeoutMs` (1500 ms)** — a dead client stops the robot. The panel resends every 0.6 s.
  Other tasks ignore it.
* **Selftest** checks both outputs against onnxruntime; the on-device inference supports multi-output graphs
  (the output named `action` is primary).
* **Speed envelope**: a full-scale step is `SCALE` rad per 100 ms (stand 0.3 → ~170°/s, walk 0.5 → ~270°/s), so
  `pack` raises `max_speed_dps` for unified stand/walk policies (cap 300) instead of the 90°/s default, which
  would clip steps the policy was trained to make. If it prints a warning, the policy asks for more than the
  protocol allows.
* **Timing**: 100 ms is below the 200 ms stable floor, so a real run needs `fast=true` (experimental) — or run
  with `period_ms=200` (out-of-distribution, but it needs no `fast`). With a period override the free-running
  gait clock and the reference advance in **real time**, only the decision rate changes.
* Legacy 43/65 formats (`obs: stand|walk|getup`) keep working side by side, including in the same preload bank.

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
3. **Accelerometer axes.** The sensor axes are not the head axes: on the real robot the sensor y axis
   is front/back (+y = face down, -y = on its back) and x is left/right, so the default is
   `accel_axes=-y+x+z`. (Old manifests that stored the former default `+x+y+z` are read as the new
   default.) Verify with dry-run: tilt the robot and watch `tiltDeg` in `policy/status`.
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

The suite also covers multi-policy hot-swap: slot validation, switching at step boundaries, per-slot
period/move time, `ref` restart, `prev action` conversion (a continuity test with an "echo" policy proves
the target does not jump across a switch), and the in-use guards for replace/remove.

Get-up coverage: obs/ref contract (`[progress, over]`), settle phase, fall guard suspended while the reference plays
and active afterwards, `then` hand-over, the lying-side precondition (both wrong-side and upright refused), and manifest rules.


Period override coverage: validation (20..1000 floor/cap in the runner, 50..500 in the API), override before start and live,
ref continuity across a live change (ends exactly at the last pose with progress=1/over=1), and move time following the period.

Roles/fall hook coverage: enable-time validation, fall without the hook still stops, back/front side selection, no servo
frames while waiting, side-lying and exhausted attempts end with `fall`, started-lying refused, `then` hand-back, and the
PolicyCenter role resolution/binding and `auto_recover` start rules. The standalone watchdog adds `FallRecoveryDetector`
tests: arm-only-after-upright, fall confirmation/dwell, back/front action mapping (BackStand/FrontStand), side-lying
give-up, start-failure and attempt-budget limits, policy hand-over disarming, and config validation.

Unified coverage: multi-output `OnnxMlp`, slot validation, `target = q0 + delta`, command wiring + clamp + expiry + non-walk
ignore, phase clock / loop phase, get-up progress in control steps, legacy ↔ unified switching; PolicyCenter e2e (pack → load →
selftest of both outputs → roles → command API → get-up hand-over).
