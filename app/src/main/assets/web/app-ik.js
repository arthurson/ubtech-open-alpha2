// Open Alpha2 — Servo tab: IK action card
// (squat / squat 2 / left-right split / front-back split / laugh / kick /
//  Sirius side lunge / Hulu half squat / Hamlet bent-arm reach)
//
// Ported from alpha2_description/web/ubx-player.html (the sliders that were
// tested against the URDF + real robot). The leg FK parameters come from
// alpha2_description/web/fk-params.js (URDF + calibrated q_init offsets).
// The laugh / kick paths and the four single-keyframe poses use the values
// solved by that player; signed actions use its mirror rule.
//
// The controls are mutually exclusive and all start from the boot stand-up
// pose (0 = robotStandup, 100 = the tested IK pose). The front-back split and
// the new kick / Sirius / Hulu / Hamlet sliders run -100…+100:
// negative = left-forward / mirrored / left kick, positive = right-forward /
// original pose / right kick, 0 = boot pose. Dragging sets the target; the
// robot moves 10% every 120 ms at constant speed (0→100 in 1200 ms), previews
// the 20 servo values in the grid, and sends /api/servo/all at each step.
// Releasing does not jump — the same constant-speed ramp continues until the
// target is reached.
//
// Switching actions has a "direct transition" toggle (remembered in
// localStorage). Off: return to the boot pose first, then move to the new
// action. On: blend the 20 servo angles straight from the current A pose to
// the B pose, at the same 10%-per-120ms rate (no boot pose in between).
//
// Only the servos an action actually drives are touched:
//   squat                    : legs 7-16 (10 servos)
//   squat 2 / left-right     : legs 7-16 + shoulder roll 2/5
//                              (targets 180/60 and 140/100)
//   left-forward / right-fwd : legs 7-16 + shoulder pitch 1/4
//                              (left 90/70, right 170/150)
//   laugh / kick             : arms and legs 1-16 (hands/head untouched)
//   Sirius/Hulu/Hamlet : all 20 axes (official single-keyframe poses)
// Head 19/20, hands 17/18 and elbows 3/6 keep their current/manual values for
// the whole IK move except the four single-keyframe poses.
//
// A manual change to a driven servo keeps the IK percentage and freezes the
// action; the next IK move blends from the actual pose at 120 ms/step. A
// manual change to a non-driven servo only updates its held value and the IK
// action keeps running.
//
// Switching actions never touches servos the new action does not drive:
// e.g. squat 2 / left-right -> squat keeps shoulder roll 2/5 at its current
// value, and left-forward / right-forward -> squat keeps shoulder pitch 1/4
// at its current value (including during the off-mode return-to-boot phase),
// so the arms do not drop back to the boot pose.

var IK_LEG_CHAINS = {
  r: [
    { servo: 7,  joint: "r_hip_roll_joint",    origin: [1.52588e-8, -0.0353277, 0.00188553], axis: [-1, 0, 0], offset: -119 },
    { servo: 8,  joint: "r_hip_pitch_joint",   origin: [0.00972791, -0.00802065, -0.0438724], axis: [0, 1, 0],  offset: -100 },
    { servo: 9,  joint: "r_knee_joint",        origin: [-0.0117411, 0, -0.0525105],           axis: [0, -1, 0], offset: -190 },
    { servo: 10, joint: "r_ankle_pitch_joint", origin: [0.00000532532, 0, -0.0591983],        axis: [0, -1, 0], offset: -120 },
    { servo: 11, joint: "r_ankle_roll_joint",  origin: [0.00200788, 0.0168256, -0.0369769],   axis: [1, 0, 0],  offset: -119 }
  ],
  l: [
    { servo: 12, joint: "l_hip_roll_joint",    origin: [7.62939e-9, 0.0353277, 0.00188553],  axis: [-1, 0, 0], offset: -121 },
    { servo: 13, joint: "l_hip_pitch_joint",   origin: [0.00972792, 0.00802064, -0.0438723],  axis: [0, -1, 0], offset: -140 },
    { servo: 14, joint: "l_knee_joint",        origin: [-0.0117411, 1.39965e-9, -0.0525105], axis: [0, 1, 0],  offset: -50 },
    { servo: 15, joint: "l_ankle_pitch_joint", origin: [0.00000535583, -6.38468e-13, -0.0593814], axis: [0, 1, 0], offset: -120 },
    { servo: 16, joint: "l_ankle_roll_joint",  origin: [0.00200786, -0.0168256, -0.0367939],  axis: [1, 0, 0],  offset: -119 }
  ]
};
var IK_SOLE = {
  r: { parent: "r_ankle_roll_link", off: [0.000213, -0.016948, -0.027969] },
  l: { parent: "l_ankle_roll_link", off: [0.000213, 0.016948, -0.027969] }
};

// Effective joint offsets (degrees) used by the ankle-level compensation.
var IK_OFF = { 8: -100, 9: -190, 10: -120, 13: -140, 14: -50, 15: -120 };

var IK_D2R = Math.PI / 180;
var IK_SPLIT_MAX_DEG = 80;

// 拖動時每 120ms 更新一次；由 0% 到 100% 用 1200ms 均速行完，
// 即每 tick 10%（IK_STEP_PCT）。如果中間有 job 被合併，會按實際
// 百分比差距放大 time，保持同一速度。
var IK_LIVE_INTERVAL_MS = 120;
var IK_FULL_TIME_MS = 1200;
var IK_STEP_PCT = 100 * IK_LIVE_INTERVAL_MS / IK_FULL_TIME_MS; // 10
var IK_DIRECT_KEY = "ik_direct_mode"; // localStorage：直接 A→B 過渡開關
var IK_SPLIT_ARM_R = 140;
var IK_SPLIT_ARM_L = 100;
var IK_YSQUAT_ARM_R = 180;
var IK_YSQUAT_ARM_L = 60;
// 每個 IK 動作實際會控制嘅軸（1-based）。唔喺名單嘅軸完全唔郁，
// 保留舵機格目前嘅值（包括手動改過嘅頭／手／肩）。
//   蹲下：腳 7-16（10 顆）
//   蹲式／左右一字馬：腳 7-16 + 肩 roll 2/5（12 顆）
//   前後一字馬（二合一）：腳 7-16 + 肩 pitch 1/4（12 顆）
//   大笑／踢腿：手臂＋腳 1-16（16 顆）
//   側弓步／半蹲／屈臂前伸：全 20 軸
var IK_LEG_AXES = [7, 8, 9, 10, 11, 12, 13, 14, 15, 16];
var IK_ARM_LEG_AXES = [1, 2, 3, 4, 5, 6].concat(IK_LEG_AXES);
var IK_ALL_AXES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20];
var IK_KINDS = ["squat", "ysquat", "lr", "fsplit", "fsplit2",
                "laugh", "kick", "sirius", "hulu", "hamlet"];
var IK_KIND_AXES = {
  squat: IK_LEG_AXES,
  ysquat: [2, 5].concat(IK_LEG_AXES),
  lr: [2, 5].concat(IK_LEG_AXES),
  fsplit: [1, 4].concat(IK_LEG_AXES),
  fsplit2: [1, 4].concat(IK_LEG_AXES),
  laugh: IK_ARM_LEG_AXES,
  kick: IK_ARM_LEG_AXES,
  sirius: IK_ALL_AXES,
  hulu: IK_ALL_AXES,
  hamlet: IK_ALL_AXES
};
var IK_FSPLIT_FWD = 0.078;
var IK_FSPLIT_BACK = 0.087;
var IK_FSPLIT_DZ_F = 0.0695;
var IK_FSPLIT_DZ_B = 0.0365;
var IK_FSPLIT_SEED = { r: [109, 125], l: [191, 10] };
var IK_FSPLIT2_SEED = { r: [49, 230], l: [131, 115] };
var IK_FSPLIT_ARM = [90, 70];    // 左前：servo 1 / servo 4
var IK_FSPLIT2_ARM = [170, 150]; // 右前：servo 1 / servo 4
var IK_SQUAT_SEED = { r: [26, 40], l: [214, 200] };

// 舊 API／已存手柄映射嘅 fb 當左前，避免升級後舊 mapping 失效。
function ikNormalizeKind(kind) {
  return kind === "fb" ? "fsplit" : kind;
}
function ikIsFSplit(kind) {
  kind = ikNormalizeKind(kind);
  return kind === "fsplit" || kind === "fsplit2";
}
// 帶符號動作：slider -100…+100；負＝鏡像／左踢，正＝原姿勢／右踢。
function ikIsSignedKind(kind) {
  kind = ikNormalizeKind(kind);
  return kind === "kick" || kind === "sirius" || kind === "hulu"
      || kind === "hamlet";
}
function ikMinPct(kind) {
  return ikIsSignedKind(kind) ? -100 : 0;
}
function ikMirrorPose(pose) {
  var out = new Array(20);
  for (var j = 1; j <= 20; j++) {
    var x = pose[IK_MIRROR_PAIR[j] - 1];
    out[j - 1] = (j === 17 || j === 18 || j === 20) ? x : 240 - x;
  }
  return out;
}
function ikKickPrep(frames) {
  var c = [0], total = 0;
  for (var i = 0; i < frames.length - 1; i++) {
    var s = 0;
    for (var k = 0; k < 16; k++) s += Math.abs(frames[i + 1][k] - frames[i][k]);
    total += s;
    c.push(total);
  }
  for (var j = 0; j < c.length; j++) c[j] = total ? c[j] / total : 0;
  return { frames: frames, cum: c };
}
// Official "蹲下" final 20-axis pose (tested). IK 只會用入面 7-16 號腳嘅值；
// 3/6 號肘部值唔屬於蹲下動作，會保持目前值。
var IK_SQUAT_POSE = [
  120, 205, 150, 120, 35, 90, 120, 26, 40, 205,
  120, 120, 214, 200, 35, 120, 120, 120, 120, 120
];

// 官方「蹲式」（action 1509004868066 最深格）20 軸 pose：
// 深蹲、雙臂左右張開、腳掌貼地。
// 肩 roll 2/5 目標係 180/60（同左右一字馬 140/100 唔同）。
// IK 只會用 2/5（肩 roll）同 7-16（腳）嘅值，其他軸保持目前值。
var IK_YSQUAT_POSE = [
  120, IK_YSQUAT_ARM_R, 120, 120, IK_YSQUAT_ARM_L, 120, 145, 29, 55, 187,
  145, 88, 216, 182, 55, 89, 120, 120, 120, 120
];

// 開機 robotStandup 20 軸企姿（同 ActionDirect.OFFICIAL_BOOT_STANDUP_POSE 一致）。
// #19 官方原文 250 已改為 120（app 安全範圍）；4 條 IK slider 嘅 0% 都用呢個做 base。
var IK_BOOT_POSE = [
  120, 205, 120, 120, 35, 120, 120, 63, 145, 135,
  120, 120, 177, 95, 105, 120, 120, 120, 120, 120
];

// 官方「大笑」（1487230160190）路徑取樣表：16 軸 × 25 點（每段 8 點）。
// 由 ubx-player.html 嘅 4 個關鍵姿勢做手／腳末端 IK 解出嚟；app 直接內嵌
// 結果，避免將全身 FK／IK 搬過嚟。中間點線性內插，行為同原 player 一致。
var IK_LAUGH_TABLE = [
  [114, 205, 119, 116, 38, 114, 120, 65, 145, 135, 120, 120, 175, 95, 105, 120],
  [109, 204, 123, 123, 39, 113, 120, 68, 142, 139, 120, 120, 172, 99, 100, 120],
  [107, 203, 131, 129, 40, 109, 121, 72, 139, 143, 120, 120, 168, 102, 96, 120],
  [107, 201, 143, 133, 41, 102, 121, 76, 138, 146, 121, 119, 163, 104, 93, 119],
  [107, 200, 157, 136, 42, 92, 121, 81, 138, 148, 121, 119, 158, 104, 90, 119],
  [106, 199, 168, 140, 43, 84, 122, 86, 138, 151, 121, 119, 152, 104, 88, 119],
  [103, 198, 177, 144, 43, 77, 122, 92, 139, 153, 121, 119, 146, 102, 86, 119],
  [99, 197, 183, 151, 42, 74, 123, 98, 141, 154, 122, 118, 139, 100, 85, 118],
  [94, 197, 186, 158, 42, 73, 123, 104, 144, 155, 122, 118, 132, 96, 84, 118],
  [89, 195, 185, 162, 43, 75, 123, 107, 143, 157, 122, 118, 129, 98, 82, 118],
  [84, 194, 183, 167, 44, 77, 123, 109, 142, 159, 122, 118, 127, 99, 80, 118],
  [80, 192, 182, 171, 44, 79, 123, 112, 141, 161, 122, 118, 124, 100, 78, 118],
  [75, 191, 180, 176, 45, 80, 123, 115, 140, 163, 122, 118, 120, 101, 76, 118],
  [70, 189, 179, 180, 45, 82, 123, 118, 140, 164, 122, 117, 117, 101, 74, 118],
  [65, 188, 178, 185, 46, 84, 123, 121, 140, 166, 122, 117, 113, 101, 73, 118],
  [60, 187, 176, 189, 47, 86, 123, 125, 140, 167, 122, 117, 110, 101, 72, 118],
  [55, 186, 175, 194, 47, 88, 123, 128, 141, 168, 122, 117, 106, 100, 71, 118],
  [49, 186, 171, 200, 47, 90, 123, 131, 139, 171, 122, 117, 103, 101, 69, 118],
  [42, 187, 166, 205, 48, 92, 124, 134, 138, 173, 123, 116, 100, 102, 66, 117],
  [35, 187, 162, 211, 48, 94, 124, 138, 137, 175, 123, 116, 96, 103, 64, 117],
  [28, 188, 157, 216, 48, 96, 125, 141, 137, 177, 123, 115, 93, 103, 62, 116],
  [21, 188, 152, 222, 48, 99, 125, 145, 137, 179, 124, 114, 90, 103, 61, 116],
  [14, 189, 147, 228, 48, 101, 126, 149, 137, 180, 124, 113, 86, 103, 59, 115],
  [7, 189, 142, 233, 47, 103, 127, 153, 137, 182, 124, 112, 83, 103, 57, 115],
  [0, 190, 137, 239, 47, 105, 128, 157, 138, 183, 125, 111, 79, 103, 56, 114]
];

// 官方「左踢式」（1511247506520）／「右踢式」（1511247440171）頭 4 格。
// slider -100＝左踢、+100＝右踢、0＝企姿；路徑按 16 軸角度變化量參數化。
var IK_KICK_L_POSE = [
  [120, 205, 120, 120, 35, 115, 120, 65, 145, 135, 120, 120, 175, 95, 105, 120],
  [121, 179, 120, 119, 72, 115, 129, 54, 131, 137, 143, 127, 173, 80, 117, 133],
  [121, 179, 120, 119, 72, 115, 134, 26, 83, 156, 122, 127, 173, 80, 117, 133],
  [120, 120, 120, 120, 120, 120, 163, 47, 129, 141, 124, 127, 175, 84, 115, 137]
];
var IK_KICK_R_POSE = [
  [120, 205, 120, 120, 35, 115, 120, 65, 145, 135, 120, 120, 175, 95, 105, 120],
  [120, 168, 120, 120, 61, 120, 113, 62, 160, 123, 104, 107, 187, 111, 102, 94],
  [120, 168, 120, 120, 61, 120, 113, 62, 160, 123, 104, 106, 214, 158, 84, 118],
  [120, 120, 120, 120, 120, 120, 113, 62, 156, 125, 100, 77, 193, 111, 99, 116]
];

// 官方單關鍵幀姿勢（同 ubx-player.html「姿勢」slider 工廠一致）：
// 正數＝原姿勢、負數＝左右反轉（手腳左右對調再 240-x；手指值不變；
// 頭 #19 240-x、#20 不變）。
var IK_SIRIUS_POSE = [
  129, 98, 170, 123, 128, 120, 171, 92, 178, 129,
  172, 125, 206, 197, 37, 128, 120, 120, 110, 113
];
var IK_HULU_POSE = [
  122, 88, 123, 120, 118, 57, 103, 53, 112, 152,
  107, 98, 204, 162, 75, 85, 120, 120, 173, 97
];
var IK_HAMLET_POSE = [
  71, 164, 150, 166, 34, 90, 144, 57, 139, 133,
  141, 134, 176, 101, 101, 127, 120, 120, 84, 120
];
var IK_MIRROR_PAIR = [0, 4, 5, 6, 1, 2, 3, 12, 13, 14, 15, 16, 7, 8, 9, 10, 11, 18, 17, 19, 20];
// 側弓步／屈臂前伸：正負方向對調（+100＝原本嘅鏡像姿勢、-100＝原本嘅原姿勢）。
// 必須放喺 IK_MIRROR_PAIR 之後；其他動作（踢腿／半蹲）方向不變。
IK_SIRIUS_POSE = ikMirrorPose(IK_SIRIUS_POSE);
IK_HAMLET_POSE = ikMirrorPose(IK_HAMLET_POSE);
var IK_KICK_L = ikKickPrep(IK_KICK_L_POSE);
var IK_KICK_R = ikKickPrep(IK_KICK_R_POSE);

// 左前／右前一字馬嘅 100% 仍然由 IK solver 以 IK_BOOT_POSE 做 base 計出，
// 同 ubx-player.html 測試過嘅算法一致；唔 hardcode 目標，避免改常數時漏改。

// ---- small 3D math (leg chains only) ----
function ikI3() { return [[1, 0, 0], [0, 1, 0], [0, 0, 1]]; }
function ikRotAxis(ax, q) {
  var n = Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]);
  var x = ax[0] / n, y = ax[1] / n, z = ax[2] / n;
  var c = Math.cos(q), s = Math.sin(q), C = 1 - c;
  return [
    [c + x * x * C, x * y * C - z * s, x * z * C + y * s],
    [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
    [z * x * C - y * s, z * y * C + x * s, c + z * z * C]
  ];
}
function ikMDot(R, v) {
  return [
    R[0][0] * v[0] + R[0][1] * v[1] + R[0][2] * v[2],
    R[1][0] * v[0] + R[1][1] * v[1] + R[1][2] * v[2],
    R[2][0] * v[0] + R[2][1] * v[1] + R[2][2] * v[2]
  ];
}
function ikMMul(A, B) {
  var C = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (var i = 0; i < 3; i++) {
    for (var j = 0; j < 3; j++) {
      var s = 0;
      for (var k = 0; k < 3; k++) s += A[i][k] * B[k][j];
      C[i][j] = s;
    }
  }
  return C;
}
function ikVAdd(a, b) { return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]; }
function ikVSub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function ikVNorm(a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }

function ikLinkFrames(pose, side) {
  var F = {};
  var R = ikI3(), p = [0, 0, 0];
  var chain = IK_LEG_CHAINS[side];
  for (var i = 0; i < chain.length; i++) {
    var j = chain[i];
    p = ikVAdd(p, ikMDot(R, j.origin));
    R = ikMMul(R, ikRotAxis(j.axis, (pose[j.servo - 1] + j.offset) * IK_D2R));
    F[j.joint.replace(/_joint$/, "_link")] = {
      p: p.slice(),
      R: R.map(function (r) { return r.slice(); })
    };
  }
  return F;
}
function ikSole(pose, side) {
  var F = ikLinkFrames(pose, side);
  var s = IK_SOLE[side];
  var f = F[s.parent];
  if (!f) return null;
  return ikVAdd(f.p, ikMDot(f.R, s.off));
}

// ---- leg pitch / ankle compensation (same formulas as the reference) ----
function ikLegPitch(a, side) {
  if (side === "r") {
    return a[7] + IK_OFF[8] - a[8] - IK_OFF[9] - a[9] - IK_OFF[10];
  }
  return -(a[12] + IK_OFF[13]) + a[13] + IK_OFF[14] + a[14] + IK_OFF[15];
}
function ikSquatAnkle(a, side, pitch) {
  if (side === "r") {
    return a[7] - a[8] + IK_OFF[8] - IK_OFF[9] - IK_OFF[10] - pitch;
  }
  return a[12] - a[13] + pitch + IK_OFF[13] - IK_OFF[14] - IK_OFF[15];
}
function ikRangeOf(id) {
  var cal = SERVO_CALIBRATION[id];
  return cal ? [cal.min, cal.max] : [0, 255];
}

// ---- damped least-squares IK (same as alpha2_description) ----
function ikSolveCore(a0, target, side, servos, lim, iters, prep) {
  var n = servos.length;
  var a = a0.slice();
  function effAt(arr) {
    var aa = prep ? prep(arr) : arr;
    var p = ikSole(aa, side);
    return [p[0], p[1], p[2]];
  }
  var e = effAt(a);
  var errM = ikVNorm(ikVSub(target, e));
  var lam = 1e-3;
  for (var it = 0; it < (iters || 16) && errM > 0.002; it++) {
    var err = [target[0] - e[0], target[1] - e[1], target[2] - e[2]];
    var J = [], h = 0.01;
    for (var i = 0; i < n; i++) {
      var ap = a.slice();
      ap[servos[i] - 1] += h / IK_D2R;
      var ep = effAt(ap);
      J.push([(ep[0] - e[0]) / h, (ep[1] - e[1]) / h, (ep[2] - e[2]) / h]);
    }
    var A = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
    for (var i2 = 0; i2 < n; i2++) {
      for (var r = 0; r < 3; r++) {
        for (var c = 0; c < 3; c++) A[r][c] += J[i2][r] * J[i2][c];
      }
    }
    A[0][0] += lam; A[1][1] += lam; A[2][2] += lam;
    var det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1])
            - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0])
            + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
    if (!det || !isFinite(det)) break;
    var inv = [
      [(A[1][1] * A[2][2] - A[1][2] * A[2][1]) / det, (A[0][2] * A[2][1] - A[0][1] * A[2][2]) / det, (A[0][1] * A[1][2] - A[0][2] * A[1][1]) / det],
      [(A[1][2] * A[2][0] - A[1][0] * A[2][2]) / det, (A[0][0] * A[2][2] - A[0][2] * A[2][0]) / det, (A[0][2] * A[1][0] - A[0][0] * A[1][2]) / det],
      [(A[1][0] * A[2][1] - A[1][1] * A[2][0]) / det, (A[0][1] * A[2][0] - A[0][0] * A[2][1]) / det, (A[0][0] * A[1][1] - A[0][1] * A[1][0]) / det]
    ];
    var y = [0, 0, 0];
    for (var r2 = 0; r2 < 3; r2++) {
      y[r2] = inv[r2][0] * err[0] + inv[r2][1] * err[1] + inv[r2][2] * err[2];
    }
    var trial = a.slice();
    for (var i3 = 0; i3 < n; i3++) {
      var dq = J[i3][0] * y[0] + J[i3][1] * y[1] + J[i3][2] * y[2];
      if (dq > 0.17) dq = 0.17;
      if (dq < -0.17) dq = -0.17;
      var s = servos[i3];
      var v = a[s - 1] + dq / IK_D2R;
      if (v < lim[s][0]) v = lim[s][0];
      if (v > lim[s][1]) v = lim[s][1];
      trial[s - 1] = v;
    }
    var et = effAt(trial);
    var errT = ikVNorm(ikVSub(target, et));
    if (errT < errM) {
      a = trial; e = et; errM = errT;
      lam = Math.max(1e-5, lam * 0.5);
    } else {
      lam = Math.min(0.2, lam * 4);
    }
  }
  return { a: a, err: errM };
}
function ikSolveChain(start, target, side, servos, iters, prep, noMulti) {
  var lim = {};
  for (var i = 0; i < servos.length; i++) lim[servos[i]] = ikRangeOf(servos[i]);
  var a = start.slice();
  var best = ikSolveCore(a, target, side, servos, lim, iters, prep);
  if (!noMulti && best.err > 0.006) {
    for (var ci = 0; ci < servos.length; ci++) {
      var deltas = [12, -12];
      for (var di = 0; di < deltas.length; di++) {
        var a2 = a.slice();
        var s = servos[ci];
        a2[s - 1] = Math.max(lim[s][0], Math.min(lim[s][1], a2[s - 1] + deltas[di]));
        var r = ikSolveCore(a2, target, side, servos, lim, iters, prep);
        if (r.err < best.err) best = r;
      }
    }
  }
  var out = best.a.slice();
  for (var i5 = 0; i5 < servos.length; i5++) {
    var s2 = servos[i5];
    out[s2 - 1] = Math.round(out[s2 - 1]);
  }
  return out;
}
function ikSquatSolve(a, side, base, target, seed) {
  var sd = seed || IK_SQUAT_SEED[side];
  var servos = side === "r" ? [8, 9] : [13, 14];
  var a2 = a.slice();
  if (side === "r") { a2[7] = sd[0]; a2[8] = sd[1]; }
  else { a2[12] = sd[0]; a2[13] = sd[1]; }
  var prep = function (arr) {
    var o = arr.slice();
    o[side === "r" ? 9 : 14] = ikSquatAnkle(o, side, side === "r" ? base.pitchR : base.pitchL);
    return o;
  };
  a2 = ikSolveChain(a2, target, side, servos, 30, prep);
  var dep = side === "r" ? 10 : 15;
  var rg = ikRangeOf(dep);
  a2[dep - 1] = ikSquatAnkle(a2, side, side === "r" ? base.pitchR : base.pitchL);
  if (a2[dep - 1] < rg[0]) a2[dep - 1] = rg[0];
  if (a2[dep - 1] > rg[1]) a2[dep - 1] = rg[1];
  a2[dep - 1] = Math.round(a2[dep - 1]);
  return a2;
}

// Solve a front/back split end pose from the fixed boot base. fsplit = left
// leg forward, fsplit2 = right leg forward. Same calculation as
// ikFSplitCapture()/ikFSplit2Capture() in ubx-player.html; at 100% the result
// is the tested pose. The two orientations need different IK seeds.
function ikFSplitEnd(base, kind) {
  kind = ikNormalizeKind(kind);
  var rightFwd = kind === "fsplit2";
  var br = ikSole(base, "r"), bl = ikSole(base, "l");
  var b = { pitchR: ikLegPitch(base, "r"), pitchL: ikLegPitch(base, "l") };
  var e = base.slice();
  var seedR = rightFwd ? IK_FSPLIT2_SEED.r : IK_FSPLIT_SEED.r;
  var seedL = rightFwd ? IK_FSPLIT2_SEED.l : IK_FSPLIT_SEED.l;
  var rTarget = rightFwd
    ? [br[0] + IK_FSPLIT_FWD, br[1], br[2] + IK_FSPLIT_DZ_F]
    : [br[0] - IK_FSPLIT_BACK, br[1], br[2] + IK_FSPLIT_DZ_B];
  var lTarget = rightFwd
    ? [bl[0] - IK_FSPLIT_BACK, bl[1], bl[2] + IK_FSPLIT_DZ_B]
    : [bl[0] + IK_FSPLIT_FWD, bl[1], bl[2] + IK_FSPLIT_DZ_F];
  e = ikSquatSolve(e, "r", b, rTarget, seedR);
  e = ikSquatSolve(e, "l", b, lTarget, seedL);
  return {
    s8: e[7], s9: e[8], s13: e[12], s14: e[13],
    pitchR: b.pitchR, pitchL: b.pitchL
  };
}

function ikSplitMaxD(base) {
  var rH = ikRangeOf(7), rA = ikRangeOf(11), lH = ikRangeOf(12), lA = ikRangeOf(16);
  return Math.max(0, Math.min(IK_SPLIT_MAX_DEG,
    rH[1] - base[6], rA[1] - base[10], base[11] - lH[0], base[15] - lA[0]));
}

function ikRoundPose(pose) {
  var out = pose.slice();
  for (var i = 0; i < out.length; i++) {
    out[i] = Math.round(out[i]);
  }
  return out;
}
function ikClampPose(pose, base) {
  var out = pose.slice();
  for (var i = 1; i <= 20; i++) {
    var v = out[i - 1];
    if (!isFinite(v)) v = SERVO_CALIBRATION[i].home;
    var lo = SERVO_CALIBRATION[i].min;
    var hi = SERVO_CALIBRATION[i].max;
    // base 可能有超出 app 手動安全範圍嘅值（例如將來改 boot pose）。
    // 只放寬到 base 本身，唔會再擴大。
    if (base) {
      if (base[i - 1] < lo) lo = base[i - 1];
      if (base[i - 1] > hi) hi = base[i - 1];
    }
    out[i - 1] = Math.max(lo, Math.min(hi, Math.round(v)));
  }
  return out;
}

function ikPoseSquat(base, pct) {
  // 蹲下只控制腳 7-16；官方 pose 入面 3/6 嘅肘部值唔關事，唔好郁。
  var p = pct / 100, out = base.slice();
  for (var i = 6; i < 16; i++) out[i] = base[i] + (IK_SQUAT_POSE[i] - base[i]) * p;
  return ikClampPose(ikRoundPose(out), base);
}
function ikPoseYSquat(base, pct) {
  // 蹲式控制腳 7-16 + 肩 roll 2/5；其他軸保持 base。
  var p = pct / 100, out = base.slice();
  var axes = [1, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
  for (var j = 0; j < axes.length; j++) {
    var i = axes[j];
    out[i] = base[i] + (IK_YSQUAT_POSE[i] - base[i]) * p;
  }
  return ikClampPose(ikRoundPose(out), base);
}
function ikPoseLr(base, pct) {
  var p = pct / 100, out = base.slice();
  var d = ikSplitMaxD(base) * p;
  out[6]  = base[6] + d;                 // #7  right hip roll
  out[10] = base[10] + d;                // #11 right ankle roll
  out[11] = base[11] - d;                // #12 left hip roll
  out[15] = base[15] - d;                // #16 left ankle roll
  out[1]  = base[1] + (IK_SPLIT_ARM_R - base[1]) * p; // #2 right shoulder roll
  out[4]  = base[4] + (IK_SPLIT_ARM_L - base[4]) * p; // #5 left shoulder roll
  return ikClampPose(ikRoundPose(out), base);
}
function ikPoseFb(base, pct, end, kind) {
  kind = ikNormalizeKind(kind);
  var p = pct / 100, out = base.slice();
  var arms = kind === "fsplit2" ? IK_FSPLIT2_ARM : IK_FSPLIT_ARM;
  out[0]  = Math.round(base[0] + (arms[0] - base[0]) * p);  // #1 right shoulder pitch
  out[3]  = Math.round(base[3] + (arms[1] - base[3]) * p);  // #4 left shoulder pitch
  out[7]  = Math.round(base[7] + (end.s8 - base[7]) * p);   // #8  right hip pitch
  out[8]  = Math.round(base[8] + (end.s9 - base[8]) * p);   // #9  right knee
  out[12] = Math.round(base[12] + (end.s13 - base[12]) * p); // #13 left hip pitch
  out[13] = Math.round(base[13] + (end.s14 - base[13]) * p); // #14 left knee
  var r10 = ikSquatAnkle(out, "r", end.pitchR);
  var l15 = ikSquatAnkle(out, "l", end.pitchL);
  var rg10 = ikRangeOf(10), rg15 = ikRangeOf(15);
  out[9]  = Math.round(Math.max(rg10[0], Math.min(rg10[1], r10))); // #10 right ankle pitch
  out[14] = Math.round(Math.max(rg15[0], Math.min(rg15[1], l15))); // #15 left ankle pitch
  return ikClampPose(out, base);
}

// 大笑：25 點路徑表線性內插；頭 8% 由 base 過渡去路徑起點，
// 同 ubx-player.html setIkLaugh 一致。只郁 1-16 號。
function ikLaughTarget(pct) {
  var p = Math.max(0, Math.min(100, pct));
  var n = IK_LAUGH_TABLE.length - 1;
  var x = p / 100 * n;
  var j = Math.min(n - 1, Math.floor(x));
  var f = x - j, a = IK_LAUGH_TABLE[j], b = IK_LAUGH_TABLE[j + 1];
  var out = new Array(16);
  for (var i = 0; i < 16; i++) out[i] = a[i] + (b[i] - a[i]) * f;
  return out;
}
function ikPoseLaugh(base, pct) {
  pct = Math.max(0, Math.min(100, pct));
  var t = ikLaughTarget(pct);
  var w = Math.min(1, pct / 8), out = base.slice();
  for (var i = 0; i < 16; i++) out[i] = base[i] + (t[i] - base[i]) * w;
  return ikClampPose(ikRoundPose(out), base);
}
function ikKickTarget(pct) {
  var K = pct < 0 ? IK_KICK_L : IK_KICK_R;
  var u = Math.max(0, Math.min(1, Math.abs(pct) / 100));
  var s = 0;
  while (s < K.frames.length - 2 && u > K.cum[s + 1]) s++;
  var f = (u - K.cum[s]) / (K.cum[s + 1] - K.cum[s]);
  var a = K.frames[s], b = K.frames[s + 1], out = new Array(16);
  for (var i = 0; i < 16; i++) out[i] = a[i] + (b[i] - a[i]) * f;
  return out;
}
function ikPoseKick(base, pct) {
  pct = Math.max(-100, Math.min(100, pct));
  var t = ikKickTarget(pct);
  var w = Math.min(1, Math.abs(pct) / 8), out = base.slice();
  for (var i = 0; i < 16; i++) out[i] = base[i] + (t[i] - base[i]) * w;
  return ikClampPose(ikRoundPose(out), base);
}
// 單關鍵幀姿勢：正＝原姿勢、負＝左右反轉；由 base 線性過渡，郁晒 20 軸。
function ikPoseFixed(base, pct, pose) {
  var target = pct < 0 ? ikMirrorPose(pose) : pose;
  var w = Math.abs(pct) / 100, out = base.slice();
  for (var i = 0; i < 20; i++) out[i] = base[i] + (target[i] - base[i]) * w;
  return ikClampPose(ikRoundPose(out), base);
}

// ---- UI state ----
var ikBase = IK_BOOT_POSE.slice(); // 固定 0% base：官方開機企姿（唔再從舵機格擷取）
var ikActive = null;     // 其中一個 IK_KINDS（含帶符號動作）
var ikFSplitEndCache = null; // kind -> cached IK end pose for the fixed boot base
var ikPendingPose = null;
var ikPendingKind = null;
var ikPendingPct = 0;
var ikSendBusy = false;
var ikQueued = null;
var ikSendSeq = 0;
var ikPumpTimer = null;
var ikAnimTimer = null;  // 120ms 均速動畫 timer
var ikAnimStartAt = 0;   // 呢次動畫嘅起始時間（用絕對時間排 tick，避免累積誤差）
var ikAnimStepIndex = 0; // 第幾個 120ms tick
var ikTargetPct = 0;     // slider 目前目標（手指位置）
var ikCurrentPct = 0;    // 已經命令去嘅百分比（機器人跟住呢個行）
var ikLastSentPct = 0;   // 上一個真正開始送出嘅百分比（用嚟計 time）
var ikSwitchTo = null;   // 閂直接轉移時：{kind, pct}，等舊動作均速回 0% 再做
var ikTrans = null;      // 開直接轉移時：{fromPose,toPose,...}，A pose 直接均速去 B pose
var ikDirectMode = false; // 直接轉移 toggle（記 localStorage）
var ikLastStatus = null;
var ikManualPose = null; // 手動改舵機格後嘅實際 20 軸 pose（下次 IK 由此均速過渡）
var ikManualHold = null; // 手動值；IK 動作本身唔會動嘅軸保持呢個值
var ikKindMovedCache = {}; // kind -> bool[20]：該動作 100% 會唔會改呢個軸
var ikKeepPose = null;   // 切換動作期間要凍結嘅實際 20 軸 pose
var ikKeepMask = null;   // true = 新動作會控制（唔凍結）；false = 保持 ikKeepPose
var ikSliderEngaged = { squat: false, ysquat: false, lr: false, fsplit: false, fsplit2: false };

function ikSliderId(kind) {
  kind = ikNormalizeKind(kind);
  if (kind === "squat" || kind === "laugh") return "ikLaughSquat"; // 蹲仰：-100＝大笑、+100＝蹲下
  if (kind === "ysquat" || kind === "lr") return "ikYSplit";        // 蹲劈：-100＝蹲式、+100＝左右一字馬
  if (kind === "kick") return "ikKick";
  if (kind === "sirius") return "ikSirius";
  if (kind === "hulu") return "ikHulu";
  if (kind === "hamlet") return "ikHamlet";
  return "ikSplitFB";   // 左前（fsplit）／右前（fsplit2）二合一，-100…+100
}
function ikValId(kind) {
  kind = ikNormalizeKind(kind);
  if (kind === "squat" || kind === "laugh") return "ikLaughSquatVal";
  if (kind === "ysquat" || kind === "lr") return "ikYSplitVal";
  if (kind === "kick") return "ikKickVal";
  if (kind === "sirius") return "ikSiriusVal";
  if (kind === "hulu") return "ikHuluVal";
  if (kind === "hamlet") return "ikHamletVal";
  return "ikSplitFBVal";
}

// 二合一 slider（-100…+100，同 ubx-player.html）：負＝neg 動作、正＝pos 動作、0＝企姿。
//   蹲仰：-100＝大笑、+100＝蹲下；蹲劈：-100＝蹲式、+100＝左右一字馬；前後：-100＝左前、+100＝右前。
var IK_COMBOS = {
  ikLaughSquat: { neg: "laugh",  pos: "squat" },
  ikYSplit:     { neg: "ysquat", pos: "lr" },
  ikSplitFB:    { neg: "fsplit", pos: "fsplit2" }
};
// kind 屬於邊條二合一 slider（回傳 slider id），唔屬於就 null。
function ikComboIdOf(kind) {
  kind = ikNormalizeKind(kind);
  for (var id in IK_COMBOS) {
    if (IK_COMBOS[id].neg === kind || IK_COMBOS[id].pos === kind) return id;
  }
  return null;
}
// 回傳呢個動作 pct 對應嘅 slider 帶符號值；null＝slider 而家顯示緊另一邊嘅值，唔可以覆蓋。
function ikComboSigned(kind, pct) {
  kind = ikNormalizeKind(kind);
  var id = ikComboIdOf(kind);
  var sg = IK_COMBOS[id].pos === kind ? 1 : -1;
  if (pct > 0) return sg * pct;
  var sl = document.getElementById(id);
  var raw = sl ? (parseInt(sl.value, 10) || 0) : 0;
  return (raw !== 0 && (raw > 0) !== (sg > 0)) ? null : 0;
}
// 帶符號值 → 要驅動嘅動作：<0 neg、>0 pos；0 就跟返而家行緊／等緊切換嘅同組動作。
function ikComboKindFor(id, v) {
  var c = IK_COMBOS[id];
  if (v < 0) return c.neg;
  if (v > 0) return c.pos;
  if (ikSwitchTo && ikComboIdOf(ikSwitchTo.kind) === id) return ikSwitchTo.kind;
  if (ikActive && ikComboIdOf(ikActive) === id) return ikActive;
  return c.neg;
}
function ikName(kind) {
  return t("ik_name_" + ikNormalizeKind(kind));
}
function ikSetStatus(key, params) {
  ikLastStatus = { key: key, params: params || {} };
  var el = document.getElementById("ikStatus");
  if (el) el.textContent = t(key, params || {});
}
function ikApplyUiLanguage() {
  if (ikLastStatus) {
    var el = document.getElementById("ikStatus");
    if (el) el.textContent = t(ikLastStatus.key, ikLastStatus.params);
  }
}

function ikPreviewPose(pose) {
  for (var i = 1; i <= 20; i++) {
    var slider = document.getElementById("servoSlider_" + i);
    var label = document.getElementById("servoSliderVal_" + i);
    if (slider) slider.value = pose[i - 1];
    if (label) label.textContent = pose[i - 1];
  }
}
function ikReadGridPose() {
  var out = new Array(20);
  for (var i = 1; i <= 20; i++) {
    var el = document.getElementById("servoSlider_" + i);
    var v = el ? parseInt(el.value, 10) : NaN;
    out[i - 1] = isNaN(v) ? IK_BOOT_POSE[i - 1] : v;
  }
  return ikClampPose(out);
}
function ikSetSliderValue(kind, v) {
  var slider = document.getElementById(ikSliderId(kind));
  var label = document.getElementById(ikValId(kind));
  if (ikComboIdOf(kind)) {
    v = ikComboSigned(kind, v);
    if (v === null) return;
  }
  if (slider) slider.value = v;
  if (label) label.textContent = v;
}
function ikAllZero() {
  for (var i = 0; i < IK_KINDS.length; i++) {
    if (ikSliderValue(IK_KINDS[i]) !== 0) return false;
  }
  return true;
}
function ikSliderValue(kind) {
  var el = document.getElementById(ikSliderId(kind));
  var v = el ? parseInt(el.value, 10) : 0;
  if (isNaN(v)) return 0;
  kind = ikNormalizeKind(kind);
  var cid = ikComboIdOf(kind);                       // 二合一：負＝neg 動作、正＝pos 動作
  if (cid) return Math.max(0, (IK_COMBOS[cid].pos === kind ? 1 : -1) * v);
  return v;
}
function ikEnsureFSplitEnd(kind) {
  kind = ikNormalizeKind(kind);
  if (!ikIsFSplit(kind)) return null;
  if (!ikFSplitEndCache) ikFSplitEndCache = {};
  if (!ikFSplitEndCache[kind]) {
    ikFSplitEndCache[kind] = ikFSplitEnd(ikBase || IK_BOOT_POSE.slice(), kind);
  }
  return ikFSplitEndCache[kind];
}
function ikPoseBase(kind, pct) {
  kind = ikNormalizeKind(kind);
  var base = ikBase || IK_BOOT_POSE.slice();
  ikBase = base;
  if (pct === 0) return base.slice();
  if (kind === "squat") return pct > 0 ? ikPoseSquat(base, pct) : base.slice();
  if (kind === "ysquat") return pct > 0 ? ikPoseYSquat(base, pct) : base.slice();
  if (kind === "lr") return pct > 0 ? ikPoseLr(base, pct) : base.slice();
  if (ikIsFSplit(kind)) return pct > 0 ? ikPoseFb(base, pct, ikEnsureFSplitEnd(kind), kind) : base.slice();
  if (kind === "laugh") return pct > 0 ? ikPoseLaugh(base, pct) : base.slice();
  if (kind === "kick") return ikPoseKick(base, pct);
  if (kind === "sirius") return ikPoseFixed(base, pct, IK_SIRIUS_POSE);
  if (kind === "hulu") return ikPoseFixed(base, pct, IK_HULU_POSE);
  if (kind === "hamlet") return ikPoseFixed(base, pct, IK_HAMLET_POSE);
  return base.slice();
}

// 該 IK 動作實際會控制邊幾個軸？唔控制嘅軸可以保留手動值，
// 例如蹲下唔會郁頭（19/20），手動改咗頭就唔應該被下一格 IK 拉返 boot。
function ikKindMovedAxes(kind) {
  kind = ikNormalizeKind(kind);
  var cached = ikKindMovedCache[kind];
  if (cached) return cached;
  var moved = new Array(20);
  for (var i = 0; i < 20; i++) moved[i] = false;
  var axes = IK_KIND_AXES[kind] || [];
  for (var j = 0; j < axes.length; j++) moved[axes[j] - 1] = true;
  ikKindMovedCache[kind] = moved;
  return moved;
}

function ikUnionMovedAxes(kinds) {
  var out = new Array(20);
  for (var i = 0; i < 20; i++) out[i] = false;
  for (var k = 0; k < kinds.length; k++) {
    if (!kinds[k]) continue;
    var moved = ikKindMovedAxes(kinds[k]);
    for (var j = 0; j < 20; j++) if (moved[j]) out[j] = true;
  }
  return out;
}

// IK 動作唔控制嘅軸：保留手動 hold；如果未 hold，就用開機企姿做安全底。
// 咁樣做緊 IK 時，頭／手／肩等完全唔會因為 IK 而跳去開機企姿。
function ikHoldPose() {
  if (!ikManualHold) ikManualHold = (ikBase || IK_BOOT_POSE).slice();
  return ikManualHold;
}

function ikApplyManualHold(kind, pose) {
  var hold = ikHoldPose();
  var moved = ikKindMovedAxes(kind);
  var out = pose.slice();
  for (var i = 0; i < 20; i++) {
    if (!moved[i]) out[i] = hold[i];
  }
  return ikClampPose(out, ikBase);
}

// 目前實際（或即將送出）嘅 20 軸 pose。切換動作時用嚟記住
// 「新動作唔控制」嘅軸，確保佢哋由頭到尾都唔會被拉去開機企姿。
function ikActualPose() {
  if (ikTrans) return ikClampPose(ikTransPose(ikTrans), ikBase);
  if (ikActive) return ikComputePose(ikActive, ikCurrentPct);
  if (ikManualPose) return ikClampPose(ikManualPose.slice(), ikBase);
  return ikHoldPose().slice();
}

// 將 pose 入面「新動作唔控制」嘅軸寫入 hold：之後任何 ikComputePose
// 都會保持呢啲實際值，唔會用舊 hold／開機企姿。
function ikSyncHoldForKind(kind, pose) {
  var moved = ikKindMovedAxes(kind);
  var hold = ikHoldPose();
  for (var i = 0; i < 20; i++) {
    if (!moved[i]) hold[i] = pose[i];
  }
}

// off mode 切換：舊動作要均速回 0%，但新動作唔控制嘅軸（例如
// 蹲式／左右一字馬 → 蹲下時嘅 2/5 肩 roll，或者左前／右前一字馬
// → 蹲下時嘅 1/4 肩 pitch）要凍結喺目前實際值，
// 唔可以跟住舊動作回企姿。切換途中再改目標時，
// 之前凍結咗嘅軸繼續凍結，避免佢哋突然跳去舊動作嘅插值 pose。
function ikFreezeForSwitch(kind, pose) {
  var actual = pose ? ikClampPose(pose.slice(), ikBase) : ikActualPose();
  var moved = ikKindMovedAxes(kind);
  var prevKeep = ikKeepPose;
  var prevMask = ikKeepMask;
  var keep = new Array(20);
  var mask = new Array(20);
  for (var i = 0; i < 20; i++) {
    var wasFrozen = !!(prevKeep && !prevMask[i]);
    var frozen = wasFrozen || !moved[i];
    keep[i] = frozen ? actual[i] : null;
    mask[i] = !frozen;
  }
  ikKeepPose = keep;
  ikKeepMask = mask;
  ikSyncHoldForKind(kind, actual);
  return actual;
}

function ikClearSwitchKeep() {
  ikKeepPose = null;
  ikKeepMask = null;
}

function ikComputePose(kind, pct) {
  var pose = ikApplyManualHold(kind, ikPoseBase(kind, pct));
  // 切換動作途中：新動作唔控制嘅軸凍結喺切換一刻嘅實際值。
  if (ikKeepPose) {
    for (var i = 0; i < 20; i++) {
      if (!ikKeepMask[i]) pose[i] = ikKeepPose[i];
    }
  }
  return ikClampPose(pose, ikBase);
}

// ---- direct A→B transition ----
// 線性插值 20 個舵機角度：t=0 係 A pose，t=1 係 B pose，唔會經開機企姿。
function ikLerpPose(a, b, t) {
  var out = new Array(20);
  for (var i = 0; i < 20; i++) {
    out[i] = Math.round(a[i] + (b[i] - a[i]) * t);
  }
  return out;
}

function ikTransPose(tr) {
  var t = tr.steps > 0 ? Math.min(1, tr.step / tr.steps) : 1;
  return ikLerpPose(tr.fromPose, tr.toPose, t);
}

// 直接過渡用「較大嘅一邊」決定步數：100% = 10 步（1200ms），
// 50% = 5 步（600ms），每步 120ms，維持同 slider 一樣嘅均速。
function ikTransSteps(fromPct, toPct) {
  var span = Math.max(Math.abs(fromPct || 0), Math.abs(toPct || 0),
                     Math.abs((toPct || 0) - (fromPct || 0)));
  return Math.max(1, Math.ceil(span / IK_STEP_PCT));
}

function ikMaxPoseDelta(a, b, moved) {
  var max = 0;
  for (var i = 0; i < 20; i++) {
    if (moved && !moved[i]) continue;
    var d = Math.abs((b[i] || 0) - (a[i] || 0));
    if (d > max) max = d;
  }
  return max;
}

// 該動作每 10% 大約最大會行幾多度；用嚟決定手動 pose → 目標 pose
// 要分幾多格，確保唔會有一格突然大幅跳動。只計該動作真正控制嘅軸，
// 唔好俾 IK 唔郁嘅軸（例如蹲下嘅肘）影響步數。
function ikSignedStepDeg(kind, sign, mag) {
  var p = Math.max(0, Math.min(100, mag));
  var lo = Math.min(90, Math.floor(p / 10) * 10);
  if (lo === p && p > 0) lo = Math.max(0, p - 10);
  var a = ikPoseBase(kind, sign * lo);
  var b = ikPoseBase(kind, sign * Math.min(100, lo + 10));
  return Math.max(1, ikMaxPoseDelta(a, b, ikKindMovedAxes(kind)));
}
function ikKindStepDeg(kind, pct) {
  kind = ikNormalizeKind(kind);
  var p = Math.max(ikMinPct(kind), Math.min(100, pct || 0));
  if (ikIsSignedKind(kind)) {
    if (p === 0) {
      return Math.max(ikSignedStepDeg(kind, -1, 0), ikSignedStepDeg(kind, 1, 0));
    }
    return ikSignedStepDeg(kind, p < 0 ? -1 : 1, Math.abs(p));
  }
  var lo = Math.min(90, Math.floor(p / 10) * 10);
  if (lo === p && p > 0) lo = Math.max(0, p - 10);
  var a = ikPoseBase(kind, lo);
  var b = ikPoseBase(kind, Math.min(100, lo + 10));
  return Math.max(1, ikMaxPoseDelta(a, b, ikKindMovedAxes(kind)));
}

function ikManualSteps(fromPose, toPose, kind, pct) {
  var maxDelta = ikMaxPoseDelta(fromPose, toPose);
  if (maxDelta <= 0) return 1;
  var ref = ikKindStepDeg(kind, pct);
  return Math.max(1, Math.min(30, Math.ceil(maxDelta / ref)));
}

// 由舊動作 0% 開始新動作之前，如果新動作會控制嘅軸已經被手動改到
// 唔等於 base，就唔可以照正常 0% 動畫（第一格會跳）。改用實際 pose
// 做起點均速過渡。
function ikNeedsManualStart(kind, fromPose) {
  var moved = ikKindMovedAxes(kind);
  var base = ikBase || IK_BOOT_POSE;
  for (var i = 0; i < 20; i++) {
    if (moved[i] && fromPose[i] !== base[i]) return true;
  }
  return false;
}

function ikFinishTrans(tr) {
  ikTrans = null;
  ikAnimStartAt = 0;
  ikAnimStepIndex = 0;
  ikActive = tr.toKind;
  ikCurrentPct = tr.toPct;
  ikTargetPct = tr.toPct;
  ikLastSentPct = tr.toPct;
  // 用過渡自己嘅終點 pose：佢已經保留咗「新動作唔控制」嘅軸，
  // 唔好再經 ikComputePose() 用 hold/base 重算，否則最後一格會跳。
  var pose = ikClampPose(tr.toPose, ikBase);
  ikPreviewPose(pose);
  ikPendingPose = pose;
  ikPendingKind = ikActive;
  ikPendingPct = ikCurrentPct;
  if (tr.thenKind) {
    // off mode 切換：已經均速回到 0%，等最後一格行完（120ms）才開始新動作。
    ikSwitchTo = { kind: tr.thenKind, pct: tr.thenPct };
    ikTargetPct = 0;
    ikSetStatus("ik_status_switching", { name: ikName(tr.thenKind) });
    ikAnimTimer = setTimeout(ikAnimTick, IK_LIVE_INTERVAL_MS);
    return;
  }
  ikClearSwitchKeep();
  if (ikCurrentPct !== 0) {
    ikSetStatus("ik_status_sent", { name: ikName(ikActive), pct: ikCurrentPct });
  } else {
    ikSetStatus("ik_status_base");
    if (ikAllZero()) {
      ikBase = IK_BOOT_POSE.slice();
      ikActive = null;
      ikFSplitEndCache = null;
      ikPendingPose = null;
    }
  }
}

function ikTransTick() {
  ikAnimTimer = null;
  var tr = ikTrans;
  if (!tr) return;
  if (tr.step >= tr.steps) {
    ikFinishTrans(tr);
    return;
  }

  tr.step++;
  var pose = ikClampPose(ikTransPose(tr), ikBase);
  ikPreviewPose(pose);
  ikPendingPose = pose;
  ikPendingKind = tr.toKind;
  ikPendingPct = tr.toPct;
  if (tr.thenKind) {
    ikSetStatus("ik_status_switching", { name: ikName(tr.thenKind) });
  } else {
    ikSetStatus(tr.manual ? "ik_status_manual_move" : "ik_status_direct",
      { name: ikName(tr.toKind), pct: tr.toPct });
  }
  // trans=true：ikStartSend() 唔好再用 slider pct 重算時間；
  // 直接過渡每格固定 120ms，保持均速。
  ikSendPose(pose, IK_LIVE_INTERVAL_MS, tr.toKind, tr.toPct, true, true);

  if (tr.step >= tr.steps) {
    ikFinishTrans(tr);
    return;
  }
  ikAnimTimer = setTimeout(ikAnimTick, IK_LIVE_INTERVAL_MS);
}

function ikStartDirectTrans(toKind, toPct) {
  var fromKind = ikActive;
  var fromPct = fromKind ? ikCurrentPct : 0;
  var fromPose = fromKind ? ikComputePose(fromKind, fromPct) : (ikBase || IK_BOOT_POSE).slice();
  var toPose = ikComputePose(toKind, toPct);
  // 新動作唔控制嘅軸保持 A 目前值，唔好喺 A→B 過渡中途拉去 base。
  var toMoved = ikKindMovedAxes(toKind);
  for (var i = 0; i < 20; i++) {
    if (!toMoved[i]) toPose[i] = fromPose[i];
  }
  // 之後 B 再郁任何百分比都要保持呢啲軸；清走切換期間嘅凍結 mask。
  ikClearSwitchKeep();
  ikSyncHoldForKind(toKind, fromPose);

  ikStopAnim(false);
  ikSwitchTo = null;
  ikTrans = {
    fromPose: fromPose,
    toPose: toPose,
    fromKind: fromKind,
    fromPct: fromPct,
    toKind: toKind,
    toPct: toPct,
    step: 0,
    steps: ikTransSteps(fromPct, toPct)
  };
  ikSetStatus("ik_status_direct", { name: ikName(toKind), pct: toPct });
  ikStartAnim();
}

function ikRetargetDirectTrans(toKind, toPct) {
  var tr = ikTrans;
  if (!tr) return;
  if (tr.toKind === toKind && tr.toPct === toPct) return;
  // off mode 手動切換途中，同一目標嘅 input/change 重複 call 唔好取消 thenKind。
  if (tr.thenKind === toKind && tr.thenPct === toPct) return;

  // 由「目前已經行到嘅插值 pose」重新出發，避免目標一改就跳。
  var fromPose = ikClampPose(ikTransPose(tr), ikBase);
  var toPose = ikComputePose(toKind, toPct);
  var toMoved = ikKindMovedAxes(toKind);
  for (var i = 0; i < 20; i++) {
    if (!toMoved[i]) toPose[i] = fromPose[i];
  }
  ikClearSwitchKeep();
  ikSyncHoldForKind(toKind, fromPose);
  tr.thenKind = null;
  tr.thenPct = 0;
  tr.fromPose = fromPose;
  tr.toPose = toPose;
  tr.toKind = toKind;
  tr.toPct = toPct;
  tr.step = 0;
  tr.steps = tr.manual
    ? ikManualSteps(fromPose, toPose, toKind, toPct)
    : ikTransSteps(tr.fromPct, toPct);
  ikSetStatus(tr.manual ? "ik_status_manual_move" : "ik_status_direct",
    { name: ikName(toKind), pct: toPct });
  ikStartAnim();
}

// off mode：手動改過舵機格之後轉動作，先由實際 pose 均速回 0%
// （企姿 + 手動 hold），再等 120ms 才開始新動作。
function ikStartManualSwitch(kind, pct) {
  var fromPose = ikClampPose(ikManualPose || IK_BOOT_POSE.slice(), ikBase);
  var oldKind = ikActive;
  var fromPct = ikCurrentPct;

  ikStopAnim(true);
  // 新動作唔控制嘅軸（例如蹲式→蹲下嘅 2/5）凍結喺手動實際值，
  // 舊動作回 0% 時唔可以拉佢哋去企姿。
  ikFreezeForSwitch(kind, fromPose);
  var toPose = ikComputePose(oldKind, 0);
  var maxDelta = ikMaxPoseDelta(fromPose, toPose);

  ikManualPose = null;
  ikSetSliderValue(oldKind, 0);
  ikSetSliderValue(kind, pct);
  ikActive = oldKind;
  ikCurrentPct = 0;
  ikTargetPct = 0;
  ikLastSentPct = 0;

  if (maxDelta <= 0) {
    ikSwitchTo = { kind: kind, pct: pct };
    ikSetStatus("ik_status_switching", { name: ikName(kind) });
    ikStartAnim();
    return;
  }

  ikTrans = {
    fromPose: fromPose,
    toPose: toPose,
    fromKind: oldKind,
    fromPct: fromPct,
    toKind: oldKind,
    toPct: 0,
    step: 0,
    steps: ikManualSteps(fromPose, toPose, oldKind, 0),
    manual: true,
    thenKind: kind,
    thenPct: pct
  };
  ikSetStatus("ik_status_switching", { name: ikName(kind) });
  ikStartAnim();
}

// 手動改過舵機格之後，由實際現時 pose 均速過渡去下一個 IK 目標。
// 用角度差決定格數（唔係單靠 slider 百分比），所以就算手動改咗某個
// servo 好多，都唔會第一格 120ms 內突然跳到目標。
function ikStartManualTrans(kind, pct) {
  var fromPose = ikClampPose(ikManualPose || IK_BOOT_POSE.slice(), ikBase);
  var fromKind = ikActive;
  var fromPct = ikCurrentPct;

  ikStopAnim(true);
  // 新動作唔控制嘅軸保持實際值；之後郁呢個動作都唔會拉返 base。
  ikSyncHoldForKind(kind, fromPose);
  var toPose = ikComputePose(kind, pct);
  // 目標動作唔控制嘅軸由實際 pose 保留，唔好被 hold/base 拉走。
  var toMoved = ikKindMovedAxes(kind);
  for (var i = 0; i < 20; i++) {
    if (!toMoved[i]) toPose[i] = fromPose[i];
  }
  var maxDelta = ikMaxPoseDelta(fromPose, toPose);

  ikManualPose = null;
  if (fromKind && fromKind !== kind) ikSetSliderValue(fromKind, 0);
  ikActive = kind;
  ikCurrentPct = pct;
  ikTargetPct = pct;
  ikLastSentPct = pct;

  if (maxDelta <= 0) {
    ikPreviewPose(toPose);
    ikPendingPose = toPose;
    ikPendingKind = kind;
    ikPendingPct = pct;
    if (pct !== 0) {
      ikSetStatus("ik_status_sent", { name: ikName(kind), pct: pct });
    } else {
      ikSetStatus("ik_status_base");
      if (ikAllZero()) {
        ikActive = null;
        ikFSplitEndCache = null;
        ikPendingPose = null;
      }
    }
    return;
  }

  ikTrans = {
    fromPose: fromPose,
    toPose: toPose,
    fromKind: fromKind,
    fromPct: fromPct,
    toKind: kind,
    toPct: pct,
    step: 0,
    steps: ikManualSteps(fromPose, toPose, kind, pct),
    manual: true
  };
  ikSetStatus("ik_status_manual_move", { name: ikName(kind), pct: pct });
  ikStartAnim();
}

function ikReadDirectMode() {
  try { return localStorage.getItem(IK_DIRECT_KEY) === "1"; } catch (e) { return false; }
}

function ikSetDirectMode(on) {
  ikDirectMode = !!on;
  try { localStorage.setItem(IK_DIRECT_KEY, ikDirectMode ? "1" : "0"); } catch (e) {}
  var cb = document.getElementById("ikDirectToggle");
  if (cb) cb.checked = ikDirectMode;

  // 如果 off mode 正喺回 boot 途中，開 direct 就即刻改成由目前 pose
  // 直接過渡去 pending 嘅新動作，唔使再行返 0%。
  if (ikDirectMode && ikSwitchTo && !ikTrans) {
    var sw = ikSwitchTo;
    ikSwitchTo = null;
    ikStartDirectTrans(sw.kind, sw.pct);
  }
  // 手動切換回企姿途中開 direct：即刻由現時 pose 直接去新動作。
  if (ikDirectMode && ikTrans && ikTrans.thenKind) {
    var tk = ikTrans.thenKind;
    var tp = ikTrans.thenPct;
    ikTrans.thenKind = null;
    ikTrans.thenPct = 0;
    ikRetargetDirectTrans(tk, tp);
  }
}

function ikStopAnim(clearQueue) {
  if (ikAnimTimer) {
    clearTimeout(ikAnimTimer);
    ikAnimTimer = null;
  }
  ikAnimStartAt = 0;
  ikAnimStepIndex = 0;
  if (clearQueue) {
    ikSwitchTo = null;
    ikTrans = null;
    ikQueued = null;
    ikClearSwitchKeep();
    if (ikPumpTimer) {
      clearTimeout(ikPumpTimer);
      ikPumpTimer = null;
    }
  }
}

// 由 fromPct 行到 toPct 要幾多 ms：100% = 1200ms，保持均速。
function ikMoveTime(fromPct, toPct) {
  var d = Math.abs(toPct - fromPct);
  return Math.max(IK_LIVE_INTERVAL_MS, Math.round(IK_FULL_TIME_MS * d / 100));
}

// 每 120ms 行 10%（或行到目標為止），所以 0→100 啱啱 1200ms。
function ikAnimTick() {
  ikAnimTimer = null;
  if (ikTrans) {
    ikTransTick();
    return;
  }
  if (!ikActive) return;

  var target = ikTargetPct;
  var current = ikCurrentPct;
  if (current === target) {
    ikAnimStartAt = 0;
    ikAnimStepIndex = 0;

    // 切換動作：舊動作已經均速回到 0%（開機企姿），而家先開始新動作。
    if (ikSwitchTo && current === 0) {
      var sw = ikSwitchTo;
      ikSwitchTo = null;
      var oldKind = ikActive;
      var fromPose = ikComputePose(oldKind, 0);
      // fromPose 已經包含切換期間凍結嘅軸；之後新動作要用自己嘅目標，
      // 所以先清 keep 再計 toPose，否則新動作控制嘅軸會被凍結。
      ikClearSwitchKeep();
      var toPose = ikComputePose(sw.kind, sw.pct);
      // 新動作唔控制嘅軸保持舊動作 0% 嘅值，唔好喺新動作開頭被
      // hold/base 拉走。只有新動作真正控制嘅軸才由 fromPose 去 toPose。
      var toMoved = ikKindMovedAxes(sw.kind);
      for (var mi = 0; mi < 20; mi++) {
        if (!toMoved[mi]) toPose[mi] = fromPose[mi];
      }
      var needsManual = ikNeedsManualStart(sw.kind, fromPose);
      ikActive = sw.kind;
      ikEnsureFSplitEnd(ikActive);
      ikCurrentPct = sw.pct;
      ikLastSentPct = sw.pct;
      ikTargetPct = sw.pct;
      ikTrans = {
        fromPose: fromPose,
        toPose: toPose,
        fromKind: oldKind,
        fromPct: 0,
        toKind: sw.kind,
        toPct: sw.pct,
        step: 0,
        // 正常由 0% 開始：每 120ms 一格，等同 slider 每 10%；
        // 新動作控制嘅軸有手動值時，按角度差決定格數，避免第一格跳。
        steps: needsManual
          ? ikManualSteps(fromPose, toPose, sw.kind, sw.pct)
          : ikTransSteps(0, sw.pct),
        manual: !!needsManual
      };
      ikSetStatus(needsManual ? "ik_status_manual_move" : "ik_status_direct",
        { name: ikName(sw.kind), pct: sw.pct });
      ikStartAnim();
      return;
    }

    var donePose = ikComputePose(ikActive, current);
    ikPreviewPose(donePose);
    ikPendingPose = donePose;
    ikPendingKind = ikActive;
    ikPendingPct = current;
    if (current !== 0) {
      ikSetStatus("ik_status_sent", { name: ikName(ikActive), pct: current });
    } else {
      ikSetStatus("ik_status_base");
      if (ikAllZero()) {
        ikBase = IK_BOOT_POSE.slice();
        ikActive = null;
        ikFSplitEndCache = null;
        ikPendingPose = null;
      }
    }
    return;
  }

  var delta = target - current;
  var next = Math.round(current + Math.max(-IK_STEP_PCT, Math.min(IK_STEP_PCT, delta)));
  if (next === current) next = target;
  ikCurrentPct = next;

  var pose = ikComputePose(ikActive, next);
  ikPreviewPose(pose);
  ikPendingPose = pose;
  ikPendingKind = ikActive;
  ikPendingPct = next;
  if (ikSwitchTo) {
    ikSetStatus("ik_status_switching", { name: ikName(ikSwitchTo.kind) });
  } else if (next !== 0) {
    ikSetStatus("ik_status_live", { name: ikName(ikActive), pct: next });
  } else {
    ikSetStatus("ik_status_live_base");
  }
  ikSendPose(pose, IK_LIVE_INTERVAL_MS, ikActive, next, true);
  ikAnimStepIndex++;
  var nextAt = ikAnimStartAt + ikAnimStepIndex * IK_LIVE_INTERVAL_MS;
  ikAnimTimer = setTimeout(ikAnimTick, Math.max(0, nextAt - Date.now()));
}

function ikStartAnim() {
  // 第一格即刻送（10%），之後每 120ms 一格；最後一格 100% 喺 1080ms 送，
  // 用 120ms 行完，啱啱 0→100 = 1200ms。
  if (!ikAnimTimer) {
    ikAnimStartAt = Date.now();
    ikAnimStepIndex = 0;
    ikAnimTick();
  }
}

function ikApplySliderTarget(kind, value) {
  kind = ikNormalizeKind(kind);
  var pct = Math.max(ikMinPct(kind), Math.min(100, parseInt(value, 10) || 0));
  var label = document.getElementById(ikValId(kind));
  if (label) {
    if (ikComboIdOf(kind)) {
      var shown = ikComboSigned(kind, pct);
      if (shown !== null) label.textContent = shown;
    } else {
      label.textContent = pct;
    }
  }

  // 直接過渡進行中：無論 toggle 而家係開定閂，都先處理呢個 transition，
  // 咁 toggle 喺過渡途中被切換都唔會產生兩個 state 打架。
  if (ikTrans) {
    // 唔係目標動作嘅 slider 拉返 0：通常係誤觸，唔好取消過渡。
    if (pct === 0 && kind !== ikTrans.toKind) return;
    if (kind !== ikTrans.toKind) {
      ikSetSliderValue(ikTrans.toKind, 0);
    }
    ikRetargetDirectTrans(kind, pct);
    return;
  }

  // 舵機格手動改過：下一個 IK 目標由實際現時 pose 均速過渡，
  // 唔好假設機械人仍然喺 slider 嘅 0%／舊百分比。
  if (ikManualPose) {
    if (pct === 0 && ikActive && ikActive !== kind) return;
    if (ikActive === kind && pct === ikCurrentPct && !ikSwitchTo) {
      ikTargetPct = pct;
      return;
    }
    if (!ikDirectMode && ikActive && ikActive !== kind && pct !== 0) {
      ikStartManualSwitch(kind, pct);
      return;
    }
    ikStartManualTrans(kind, pct);
    return;
  }

  if (ikDirectMode) {
    if (ikActive && ikActive !== kind) {
      // 由 A 直接過渡去 B；拉去 0 唔算切換。
      if (pct === 0) return;
      ikSetSliderValue(ikActive, 0);
      ikStartDirectTrans(kind, pct);
      return;
    }

    // 同一個動作（或者未有任何動作）：照常由 0% 開始／更新目標。
    if (!ikActive) {
      ikActive = kind;
      ikCurrentPct = 0;
      ikLastSentPct = 0;
      ikTargetPct = 0;
    }
    ikTargetPct = pct;
    ikEnsureFSplitEnd(kind);
    ikStartAnim();
    return;
  }

  // ---- direct mode off：原有「先均速回 boot pose，再去新動作」----
  // 唔係 active 嗰條 slider 拉返 0（又冇 pending switch）：唔好影響緊嘅 pose。
  if (pct === 0 && ikActive && ikActive !== kind && !ikSwitchTo) return;

  if (ikActive && ikActive !== kind) {
    // 轉動作唔好即刻跳去新 pose：先將舊動作用同一速度行返 0%
    // （開機企姿），到 0 之後 ikAnimTick() 才開始新動作。
    // 新動作唔控制嘅軸（例如蹲式→蹲下嘅 2/5）凍結喺目前值，
    // 唔可以跟住舊動作回企姿。
    ikFreezeForSwitch(kind);
    if (ikSwitchTo && ikSwitchTo.kind !== kind) {
      ikSetSliderValue(ikSwitchTo.kind, 0);
    }
    ikSwitchTo = { kind: kind, pct: pct };
    if (ikCurrentPct !== 0) {
      ikTargetPct = 0;
      ikSetSliderValue(ikActive, 0);
      ikSetStatus("ik_status_switching", { name: ikName(kind) });
    }
    ikStartAnim();
    return;
  }

  // 同一個動作：如果佢係 pending 緊嘅新動作，更新 pending 目標。
  if (ikSwitchTo && ikSwitchTo.kind === kind) {
    ikSwitchTo.pct = pct;
    ikStartAnim();
    return;
  }

  // 用戶拉返而家做緊嘅動作：取消 pending switch，pending slider 歸零。
  if (ikSwitchTo) {
    var canceled = ikSwitchTo;
    ikSwitchTo = null;
    ikSetSliderValue(canceled.kind, 0);
    // 取消切換：由目前實際 pose 均速去返舊動作嘅新目標，避免
    // 切換期間凍結咗嘅軸一取消就跳去舊動作嘅插值 pose。
    ikManualPose = ikActualPose();
    ikStartManualTrans(kind, pct);
    return;
  }

  ikActive = kind;
  ikTargetPct = pct;
  ikEnsureFSplitEnd(kind);
  ikStartAnim();
}

function onIkSlider(kind, value, ev) {
  // reload 還原 slider 值時，部分瀏覽器會發 input/change；未有用家
  // 互動（pointer／touch／key）之前唔好當成指令，否則開機就會自動
  // 做返上一次 IK 動作。程式直接呼叫（測試）唔受影響。
  if (ev && ev.isTrusted && !ikSliderEngaged[kind]) return;
  ikApplySliderTarget(kind, value);
}

function onIkSliderCommit(kind, value, ev) {
  // 放手只更新目標；動畫會用同一速度行完，唔會跳去目標。
  if (ev && ev.isTrusted && !ikSliderEngaged[kind]) return;
  ikApplySliderTarget(kind, value);
}

// 二合一 slider（-100…+100）：負數行 neg 動作、正數行 pos 動作，0 行返企姿。
function onIkComboSlider(id, value, ev) {
  var v = Math.max(-100, Math.min(100, parseInt(value, 10) || 0));
  onIkSlider(ikComboKindFor(id, v), Math.abs(v), ev);
}
function onIkComboSliderCommit(id, value, ev) {
  var v = Math.max(-100, Math.min(100, parseInt(value, 10) || 0));
  onIkSliderCommit(ikComboKindFor(id, v), Math.abs(v), ev);
}
// 舊 API 相容
function onIkFbSlider(value, ev) { onIkComboSlider("ikSplitFB", value, ev); }
function onIkFbSliderCommit(value, ev) { onIkComboSliderCommit("ikSplitFB", value, ev); }

function ikSendPose(pose, timeMs, kind, pct, live, trans) {
  var job = {
    pose: ikClampPose(pose, ikBase),
    time: timeMs,
    kind: kind,
    pct: pct,
    live: !!live,
    trans: !!trans,
    seq: ++ikSendSeq
  };
  ikEnqueueSend(job);
}

function ikEnqueueSend(job) {
  if (ikSendBusy) {
    // 非 live（reset）job 一定贏；排緊 final 時，動畫 live job 唔好蓋過佢。
    if (job.live && ikQueued && !ikQueued.live) return;
    if (!job.live && ikPumpTimer) {
      clearTimeout(ikPumpTimer);
      ikPumpTimer = null;
    }
    ikQueued = job;
    ikSchedulePump();
    return;
  }
  if (job.live && ikQueued && !ikQueued.live) return; // final 已經排緊，live 唔好再插隊
  ikStartSend(job);
}

function ikSchedulePump() {
  if (ikPumpTimer) return;
  ikPumpTimer = setTimeout(function () {
    ikPumpTimer = null;
    ikPumpSend();
  }, 0);
}

function ikPumpSend() {
  if (ikSendBusy || !ikQueued) return;
  var job = ikQueued;
  ikQueued = null;
  ikStartSend(job);
}

// servo/all 加逾時：機械人死機／reboot 時 fetch 可能一直唔返，動畫會
// 繼續排隊，重連後就將舊動作最後一格送去新開機嘅機身。超時即當失敗，
// 停動畫兼清 queue。
function ikSendWithTimeout(p) {
  return new Promise(function (resolve, reject) {
    var done = false;
    var timer = setTimeout(function () {
      if (done) return;
      done = true;
      reject(new Error("timeout"));
    }, 2000);
    Promise.resolve(p).then(function (res) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolve(res);
    }, function (err) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      reject(err);
    });
  });
}

function ikStartSend(job) {
  // 開始一個較新嘅 job 時，掉走排在後面但更舊嘅 job（通常係拖動中嘅 live）。
  if (ikQueued && ikQueued.seq < job.seq) {
    ikQueued = null;
    if (ikPumpTimer) {
      clearTimeout(ikPumpTimer);
      ikPumpTimer = null;
    }
  }
  // 普通 live job 嘅 time 用「上一個真正開始送出嘅 pct」計，就算中間有 job
  // 被合併，實際速度仍然係 100% = 1200ms。直接過渡 job 每格固定 120ms，
  // 唔可以用最終 slider pct 重算（否則去 0% 會變 600ms 一格）。
  if (job.live && !job.trans) job.time = ikMoveTime(ikLastSentPct, job.pct);
  ikSendBusy = true;
  if (!job.trans) ikLastSentPct = job.pct;
  if (!job.live) ikSetStatus("ik_status_sending");
  ikSendWithTimeout(Alpha2Api.servoAll({ angles: job.pose.join(","), time: job.time })).then(function (res) {
    ikSendBusy = false;
    if (res && res.ok) {
      if (!job.live) {
        if (job.pct !== 0) {
          ikSetStatus("ik_status_sent", { name: ikName(job.kind), pct: job.pct });
        } else {
          ikSetStatus("ik_status_base");
        }
      }
    } else {
      var err = (res && (res.error || res.message)) || "?";
      ikStopAnim(true); // 失敗／開機 guard：唔好再連發
      ikSetStatus("ik_status_fail", { err: err });
      try { showError("IK", err); } catch (e) {}
      return;
    }
    ikPumpSend();
  }, function (err) {
    ikSendBusy = false;
    ikStopAnim(true);
    ikSetStatus("ik_status_fail", { err: err && err.message ? err.message : err });
    try { showError("IK", err); } catch (e) {}
  });
}

function ikResetAll() {
  ikStopAnim(true);
  ikManualPose = null;
  ikManualHold = null;
  ikKindMovedCache = {};
  ikTargetPct = 0;
  ikCurrentPct = 0;
  ikLastSentPct = 0;
  var base = IK_BOOT_POSE.slice();
  ikBase = base;
  for (var i = 0; i < IK_KINDS.length; i++) ikSetSliderValue(IK_KINDS[i], 0);
  ikPreviewPose(base);
  ikPendingPose = base.slice();
  ikPendingKind = null;
  ikPendingPct = 0;
  ikSendPose(base, IK_FULL_TIME_MS, "squat", 0, false);
  ikActive = null;
  ikFSplitEndCache = null;
  ikPendingPose = null;
}

// 舵機格「全部回到原位」：IK slider 歸零，並記住 reset 後嘅 home pose，
// 下次 IK 由 home pose 均速過渡（唔會當咗機械人仍然喺 boot pose）。
// 0% base 仍然固定係開機企姿。
function ikResetSlidersOnly(fromPose) {
  ikStopAnim(true);
  ikTargetPct = 0;
  ikCurrentPct = 0;
  ikLastSentPct = 0;
  for (var i = 0; i < IK_KINDS.length; i++) ikSetSliderValue(IK_KINDS[i], 0);
  ikBase = IK_BOOT_POSE.slice();
  ikActive = null;
  ikFSplitEndCache = null;
  ikPendingPose = null;
  ikPendingKind = null;
  ikPendingPct = 0;
  ikKindMovedCache = {};
  if (fromPose) {
    ikManualPose = ikClampPose(fromPose, ikBase);
    ikManualHold = ikManualPose.slice();
  } else {
    ikManualPose = null;
    ikManualHold = null;
  }
  ikSetStatus("ik_status_ready");
}

function ikOnGridChange(ev) {
  var el = ev && ev.target;
  if (!el || !el.id || el.id.indexOf("servoSlider_") !== 0) return;
  var id = parseInt(el.id.substring("servoSlider_".length), 10);
  // IK pose 只送 1-20；改 21/22 唔應該影響 IK 狀態。
  if (!(id >= 1 && id <= 20)) return;
  var idx = id - 1;
  var pose = ikReadGridPose();

  // 由 hold（或開機企姿）砌一個安全嘅實際 pose：只 copy 相關 IK 軸
  // 同今次改嘅軸，其他軸保持 hold，唔會被舵機格嘅舊值拉走。
  function safePose(moved) {
    var out = ikHoldPose().slice();
    for (var i = 0; i < 20; i++) {
      if (i === idx || (moved && moved[i])) out[i] = pose[i];
    }
    return ikClampPose(out, ikBase);
  }

  // 過渡／切換途中改舵機：取消過渡並歸零，保留實際 pose 做下次起點。
  if (ikTrans || ikSwitchTo) {
    var trMoved = ikUnionMovedAxes([
      ikTrans && ikTrans.fromKind,
      ikTrans && ikTrans.toKind,
      ikSwitchTo && ikSwitchTo.kind
    ]);
    ikResetSlidersOnly(safePose(trMoved));
    ikSetStatus("ik_status_manual");
    return;
  }

  if (ikActive) {
    var moved = ikKindMovedAxes(ikActive);
    if (moved[idx]) {
      // 改咗 active IK 動作會控制嘅軸：保留 IK 百分比，停止動畫。
      // 下次拉 IK slider 由實際 pose 均速過渡，唔會第一格跳位。
      // hold 只記呢一粒手動值；其他 controlled 軸下次仍然跟 IK 目標，
      // 唔會將成個舊 pose 帶去下一個動作。
      ikManualPose = safePose(moved);
      ikHoldPose()[idx] = pose[idx];
      ikStopAnim(true);
      ikTargetPct = ikCurrentPct;
      ikLastSentPct = ikCurrentPct;
      ikSetSliderValue(ikActive, ikCurrentPct);
      ikSetStatus("ik_status_manual");
      return;
    }
    // 改咗 IK 動作唔控制嘅軸（頭／手／肩 pitch／肘）：只更新 hold，
    // 唔停 IK 動畫、唔改 IK 百分比。動畫照跑，下一格會帶住新值。
    ikHoldPose()[idx] = pose[idx];
    // 如果之前有手動 controlled 改動留低咗 manualPose，順手更新呢粒，
    // 下次「由實際 pose 過渡」先唔會將佢拉返舊值。
    if (ikManualPose) ikManualPose[idx] = pose[idx];
    if (!ikAnimTimer && ikCurrentPct === ikTargetPct) {
      ikSetStatus("ik_status_manual");
    }
    return;
  }

  // 未有 active 動作：如果改嘅軸日後會被 IK 控制，記住實際 pose，
  // 下次開始 IK 時由呢個 pose 均速過渡；否則只需保留目前值。
  var anyIkAxis = false;
  for (var k = 0; k < IK_KINDS.length && !anyIkAxis; k++) {
    if (ikKindMovedAxes(IK_KINDS[k])[idx]) anyIkAxis = true;
  }
  if (anyIkAxis) {
    var startPose = safePose(null);
    ikManualPose = startPose;
    ikManualHold = startPose.slice();
  } else {
    ikHoldPose()[idx] = pose[idx];
    if (ikManualPose) ikManualPose[idx] = pose[idx];
  }
  ikSetStatus("ik_status_manual");
}

// WebSocket 斷線（機械人死機／reboot）：即刻停 IK 動畫同清 queue，
// 避免重連後將舊動作最後一格送去新開機嘅機身。
function ikOnLinkDown() {
  var busy = !!(ikAnimTimer || ikSendBusy || ikQueued || ikTrans || ikSwitchTo);
  ikStopAnim(true);
  if (busy) ikSetStatus("ik_status_link_lost");
}

function initIkCard() {
  ikStopAnim(true);
  ikDirectMode = ikReadDirectMode();
  var directToggle = document.getElementById("ikDirectToggle");
  if (directToggle) directToggle.checked = ikDirectMode;
  ikManualPose = null;
  ikManualHold = null;
  ikKindMovedCache = {};
  ikSliderEngaged = {};
  IK_KINDS.forEach(function (kind) { ikSliderEngaged[kind] = false; });
  ikTargetPct = 0;
  ikCurrentPct = 0;
  ikLastSentPct = 0;
  ikBase = IK_BOOT_POSE.slice();
  ikFSplitEndCache = null;
  for (var i = 0; i < IK_KINDS.length; i++) ikSetSliderValue(IK_KINDS[i], 0);
  ikSetStatus("ik_status_ready");
  // 只有真正用家互動（拖／touch／鍵盤）之後，slider 事件才算數；
  // reload 還原表單值嘅事件唔會令機械人自動做返上次動作。
  IK_KINDS.forEach(function (kind) {
    var el = document.getElementById(ikSliderId(kind));
    if (!el) return;
    var mark = function () { ikSliderEngaged[kind] = true; };
    el.addEventListener("pointerdown", mark);
    el.addEventListener("touchstart", mark, { passive: true });
    el.addEventListener("mousedown", mark);
    el.addEventListener("keydown", mark);
  });
  // 瀏覽器 reload 有機會喺 DOMContentLoaded 之後才還原表單 slider 值，
  // 令 IK slider 顯示舊百分比（實際 ikCurrentPct 已係 0）。喺 load／pageshow
  // 再清一次；如果用戶已經開始拖（target/active 非 0）就唔好搶。
  function ikClearRestoredSliders() {
    if (ikTargetPct !== 0 || ikCurrentPct !== 0 || ikActive) return;
    IK_KINDS.forEach(function (kind) {
      if (ikSliderValue(kind) !== 0) ikSetSliderValue(kind, 0);
    });
  }
  function ikOnPageShow(ev) {
    // bfcache 還原：頁面凍結期間 WS 可能已經斷咗但 onclose 未跑，
    // 還原即刻停 IK，避免帶住舊動畫再送出。
    if (ev && ev.persisted) ikOnLinkDown();
    ikClearRestoredSliders();
  }
  if (typeof window !== "undefined" && window.addEventListener) {
    window.addEventListener("load", ikClearRestoredSliders);
    window.addEventListener("pageshow", ikOnPageShow);
  }
  var tab = document.getElementById("tab-servo");
  if (tab) {
    // 一掂舵機格 slider 就停 IK 卡嘅 live queue（input 已經會送，唔等放手）。
    tab.addEventListener("input", ikOnGridChange);
    tab.addEventListener("change", ikOnGridChange);
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initIkCard);
} else {
  initIkCard();
}
