#!/usr/bin/env python3
"""
Alpha2 ONNX policy 打包／驗證工具（對應 Microduck 嘅 `publish`）。

  pack           驗證 policy.onnx 符合 Alpha2 契約，寫出一個可 adb push 嘅 policy 目錄：
                   policy.onnx  manifest.json  selftest.json  [ref.json]
  make-fixtures  （測試用）生成單元測試用嘅 ONNX 與 onnxruntime 對照向量。

契約見 docs/onnx-policy.md；obs/action 佈局同 ubtech-alpha2-urdf 嘅 rl/alpha2_env.py 一致：
  stand: obs 43 -> action 20；walk: obs 65 -> action 20（殘差，需要 ref.json）。

用法:
  python scripts/policy_tool.py pack --onnx policy.onnx --name alpha2-stand-v1 \
      --obs stand --control-hz 2.5 --out out/alpha2-stand-v1
  adb push out/alpha2-stand-v1 /sdcard/Android/data/com.open.alpha2/files/policies/alpha2-stand-v1

需要: pip install -r scripts/requirements.txt  (onnx, onnxruntime, numpy)
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
RUNNER_SRC = (ROOT / "sdk-module/hardware-direct/src/main/java/com/ubtechinc/alpha/hardware/policy/OnnxMlp.java")
MIN_PERIOD_MS = 200          # 同 PolicyManifest.MIN_PERIOD_MS 一致（一般真機運行下限；50Hz 實機測試會亂震）
FAST_MIN_PERIOD_MS = 20      # 同 PolicyManifest.FAST_MIN_PERIOD_MS 一致（chest move 時間下限）
SELFTEST_TOL = 2e-5          # 同 Microduck parity 容差同級
OBS_DIM = {"stand": 43, "walk": 65}


def supported_ops():
    """由 OnnxMlp.java 嘅 ops 陣列抽出，避免兩邊漂移。"""
    t = RUNNER_SRC.read_text(encoding="utf-8")
    m = re.search(r"String\[\] ops = \{(.*?)\};", t, re.S)
    if not m:
        raise SystemExit("OnnxMlp.java 結構變咗：搵唔到 ops 陣列，請更新 policy_tool.py")
    return set(re.findall(r'"([A-Za-z]+)"', m.group(1)))


def validate_onnx(path, obs):
    import onnx
    m = onnx.load(str(path))
    onnx.checker.check_model(m)
    inits = {i.name for i in m.graph.initializer}
    ins = [i for i in m.graph.input if i.name not in inits]
    if len(ins) != 1:
        raise SystemExit(f"policy 必須得 1 個 runtime input（recurrent/stateful 未支援），而家有: {[i.name for i in ins]}")
    if len(m.graph.output) != 1:
        raise SystemExit("policy 必須得 1 個 output")
    dims = lambda v: [d.dim_value for d in v.type.tensor_type.shape.dim]
    din, dout = dims(ins[0])[-1], dims(m.graph.output[0])[-1]
    if din != OBS_DIM[obs]:
        raise SystemExit(f"obs={obs} 要求 input 維度 {OBS_DIM[obs]}，ONNX 係 {din}")
    if dout != 20:
        raise SystemExit(f"output 維度要係 20，ONNX 係 {dout}")
    sup = supported_ops()
    bad = sorted({n.op_type for n in m.graph.node} - sup)
    if bad:
        raise SystemExit(f"機上推論器唔支援嘅算子: {bad}（支援: {sorted(sup)}）")
    for t in m.graph.initializer:
        if t.data_location == onnx.TensorProto.EXTERNAL:
            raise SystemExit("唔支援 external data 權重")
    return ins[0].name, din


def selftest_cases(path, iname, din, n=8, seed=0):
    import numpy as np
    import onnxruntime as ort
    rng = np.random.default_rng(seed)
    s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    cases = []
    for _ in range(n):
        x = (rng.normal(0, 0.3, (1, din))).astype(np.float32)
        y = s.run(None, {iname: x})[0][0]
        cases.append({"obs": [float(v) for v in x[0]], "action": [float(v) for v in y]})
    return {"tol": SELFTEST_TOL, "cases": cases}


def cmd_pack(a):
    onnx_path = pathlib.Path(a.onnx)
    iname, din = validate_onnx(onnx_path, a.obs)
    hz = a.control_hz
    period = round(1000.0 / hz)
    if period < FAST_MIN_PERIOD_MS:
        raise SystemExit(f"control_hz={hz} → period {period} ms < {FAST_MIN_PERIOD_MS} ms：chest 協議做唔到")
    if period < MIN_PERIOD_MS:
        print(f"注意: control_hz={hz} → period {period} ms < {MIN_PERIOD_MS} ms：真機運行要 policy/start?fast=true"
              f"（實驗；實機穩定性未驗證），預設只可 dry-run。", file=sys.stderr)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(onnx_path, out / "policy.onnx")
    man = {
        "schema": 1, "name": a.name, "obs": a.obs, "obs_dim": din, "action_dim": 20,
        "act_scale": a.act_scale if a.act_scale is not None else (0.2 if a.obs == "walk" else 0.3),
        "control_hz": hz, "move_overlap": 1.05, "accel_axes": a.accel_axes,
        "max_speed_dps": a.max_speed_dps, "max_run_s": 60, "lease_ms": 5000, "tilt_limit_deg": 40,
        "onnx_sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(), "notes": a.notes,
    }
    if a.obs == "walk":
        if not a.ref:
            raise SystemExit("walk policy 需要 --ref ref.json")
        ref = json.load(open(a.ref))
        q, loop = ref["q"], ref["loop"]
        if not q or len(q[0]) != 20 or not (0 <= loop[0] < loop[1] <= len(q)):
            raise SystemExit("ref.json 格式錯（需要 q: Tx20 rad，loop: [a,b]）")
        shutil.copyfile(a.ref, out / "ref.json")
        man["ref"] = "ref.json"
    (out / "manifest.json").write_text(json.dumps(man, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "selftest.json").write_text(json.dumps(selftest_cases(onnx_path, iname, din)) + "\n", encoding="utf-8")
    print(f"OK -> {out}  (obs {din} -> action 20, period {period} ms, sha256 {man['onnx_sha256'][:12]}…)")


def cmd_make_fixtures(a):
    import numpy as np
    import onnx
    import onnxruntime as ort
    from onnx import TensorProto, helper, numpy_helper
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(0)

    def dump(name, path, dim):
        s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        iname = s.get_inputs()[0].name
        X = (rng.normal(0, 1, (64, dim)) * rng.uniform(0.1, 3, (1, dim))).astype(np.float32)
        Y = np.concatenate([s.run(None, {iname: X[i:i + 1]})[0] for i in range(64)])
        with open(out / f"{name}.txt", "w") as f:
            f.write(f"{dim} {Y.shape[1]} 64\n")
            for i in range(64):
                f.write(" ".join(repr(float(v)) for v in X[i]) + "\n" + " ".join(repr(float(v)) for v in Y[i]) + "\n")

    I = numpy_helper.from_array

    def save(name, nodes, inits, din, dout, opset=17):
        g = helper.make_graph(nodes, "g", [helper.make_tensor_value_info("obs", TensorProto.FLOAT, [1, din])],
                              [helper.make_tensor_value_info("action", TensorProto.FLOAT, [1, dout])], inits)
        m = helper.make_model(g, opset_imports=[helper.make_opsetid("", opset)])
        m.ir_version = 8
        onnx.checker.check_model(m)
        onnx.save(m, str(out / name))

    # 1) 合成模型：normalizer(Sub/Div) -> Gemm(transB) -> Elu -> MatMul -> Add -> Relu -> Gemm(alpha,beta) -> Clip
    D, H, A = 12, 16, 5
    r = lambda *s: rng.normal(0, .5, s).astype(np.float32)
    W1, b1, W2, b2, W3, b3 = r(H, D), r(H) * .4, r(H, H), r(H) * .4, r(H, A), r(A) * .4
    mu, sd = rng.normal(0, 1, D).astype(np.float32), rng.uniform(.5, 2, D).astype(np.float32)
    nodes = [helper.make_node("Sub", ["obs", "mu"], ["n0"]), helper.make_node("Div", ["n0", "sd"], ["n1"]),
             helper.make_node("Gemm", ["n1", "W1", "b1"], ["h1"], transB=1), helper.make_node("Elu", ["h1"], ["e1"], alpha=1.0),
             helper.make_node("MatMul", ["e1", "W2t"], ["m2"]), helper.make_node("Add", ["m2", "b2"], ["a2"]),
             helper.make_node("Relu", ["a2"], ["r2"]), helper.make_node("Gemm", ["r2", "W3", "b3"], ["o"], alpha=0.7, beta=1.3),
             helper.make_node("Clip", ["o", "lo", "hi"], ["action"])]
    save("syn.onnx", nodes, [I(x, n) for n, x in [("mu", mu), ("sd", sd), ("W1", W1), ("b1", b1), ("W2t", W2.T.copy()),
                                                    ("b2", b2), ("W3", W3), ("b3", b3)]]
         + [I(np.array(-0.8, np.float32), "lo"), I(np.array(0.9, np.float32), "hi")], D, A)
    dump("syn", out / "syn.onnx", D)

    # 2) 常量 action 43->20（W=0，bias=B）：測 servo 映射、速度包絡、安全停
    B = (0.5 * np.sin(np.arange(20))).astype(np.float32)

    def const_model(name, bias):
        save(name, [helper.make_node("Gemm", ["obs", "W", "B"], ["action"], transB=1)],
             [I(np.zeros((20, 43), np.float32), "W"), I(bias, "B")], 43, 20)

    const_model("const43.onnx", B)
    const_model("zero43.onnx", np.zeros(20, np.float32))
    nanb = B.copy(); nanb[3] = np.nan
    const_model("nan43.onnx", nanb)
    big = np.full(20, 5.0, np.float32)           # 超出 [-1,1]，驗 runtime clip
    const_model("big43.onnx", big)
    (out / "const43.txt").write_text(" ".join(repr(float(v)) for v in B) + "\n")
    # 3) 唔支援算子
    save("bad_op.onnx", [helper.make_node("Softmax", ["obs"], ["action"], axis=1)], [], 20, 20)
    # 4) walk 用 65->20 常量（需要 ref）
    save("walkconst65.onnx", [helper.make_node("Gemm", ["obs", "W", "B"], ["action"], transB=1)],
         [I(np.zeros((20, 65), np.float32), "W"), I(np.zeros(20, np.float32), "B")], 65, 20)
    # 5) 真實 SB3 匯出嘅 walk policy（URDF repo 嘅 walk_forward.onnx，已複製入 test resources）
    wf = ROOT / "sdk-module/hardware-direct/src/test/resources/policy/walk_forward.onnx"
    shutil.copyfile(wf, out / "walk_forward.onnx")
    dump("walk", wf, 65)
    print(f"fixtures -> {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("pack")
    p.add_argument("--onnx", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--obs", choices=list(OBS_DIM), default="stand")
    p.add_argument("--control-hz", type=float, required=True, help="policy 訓練時嘅決策頻率（<=3.33 可穩定真機；更高要 fast=true；<=50 且 period>=20ms）")
    p.add_argument("--max-speed-dps", type=float, default=90, help="速度包絡（°/s，<=300）；50Hz policy 通常要加大，亦可喺 policy/start 覆寫")
    p.add_argument("--act-scale", type=float, default=None)
    p.add_argument("--accel-axes", default="+x+y+z", help="加速度計軸→頭部座標，例如 -y+x+z（未實機驗證，預設同 URDF 假設）")
    p.add_argument("--ref")
    p.add_argument("--notes", default="")
    p.add_argument("--out", required=True)
    p.set_defaults(fn=cmd_pack)
    f = sp.add_parser("make-fixtures")
    f.add_argument("--out", required=True)
    f.set_defaults(fn=cmd_make_fixtures)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
