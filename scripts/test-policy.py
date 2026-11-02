#!/usr/bin/env python3
"""
ONNX policy 單元測試執行器（OnnxMlp／Alpha2PolicyIo／PolicyManifest／PolicyRunner）。

同 scripts/test-gaitwalker.py 同一格局：純 Java 部分只要 javac/java；另需 python 套件
onnx/onnxruntime/numpy（scripts/requirements.txt）生成 fixtures，並以真 onnxruntime 做
parity 對照。非零 exit = 失敗。

另外做兩個交叉檢查：
  1. Alpha2PolicyIo 嘅 Q_INIT_DEG／SERVO_AT_Q0 常量 vs ubtech-alpha2-urdf 嘅 config/servo_map.yaml
     （--urdf-repo <path> 或環境變數 ALPHA2_URDF_REPO；冇提供就 SKIP 並提示，唔當通過）。
  2. Alpha2PolicyIo 嘅 LO/HI 測試範圍 vs ApiValidator 嘅 SERVO_CAL_MIN/MAX（同 repo，必查）。

用法: python scripts/test-policy.py [--urdf-repo ../ubtech-alpha2-urdf]
"""
import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "sdk-module/hardware-direct/src"
PKG = "com/ubtechinc/alpha/hardware/policy"


def java_array(text, name):
    m = re.search(name + r"\s*=\s*\{(.*?)\};", text, re.S)
    if not m:
        raise SystemExit(f"搵唔到陣列 {name}")
    return [int(x) for x in re.findall(r"-?\d+", m.group(1))]


def cross_check(urdf_repo):
    io = (SRC / "main/java" / PKG / "Alpha2PolicyIo.java").read_text(encoding="utf-8")
    q_init, at_q0 = java_array(io, "Q_INIT_DEG"), java_array(io, "SERVO_AT_Q0")
    av = (ROOT / "app/src/main/java/com/open/alpha2/ApiValidator.java").read_text(encoding="utf-8")
    cal_min, cal_max = java_array(av, "SERVO_CAL_MIN")[:20], java_array(av, "SERVO_CAL_MAX")[:20]
    test = (SRC / "test/java" / PKG / "PolicyRunnerTest.java").read_text(encoding="utf-8")
    lo, hi = java_array(test, "LO"), java_array(test, "HI")
    ok = True
    if lo != cal_min or hi != cal_max:
        print("FAIL: PolicyRunnerTest 嘅 LO/HI 同 ApiValidator.SERVO_CAL_MIN/MAX 唔一致")
        ok = False
    if urdf_repo:
        ymap = pathlib.Path(urdf_repo) / "config/servo_map.yaml"
        rows = {}
        for line in ymap.read_text(encoding="utf-8").splitlines():
            m = re.search(r"servo_id: (\d+).*?servo_at_q0: (-?\d+), q_init_deg: (-?\d+)", line)
            if m:
                rows[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
            m2 = re.search(r"servo_id: (\d+).*?servo_min: (\d+), servo_max: (\d+)", line)
            if m2:
                i = int(m2.group(1))
                if (cal_min[i - 1], cal_max[i - 1]) != (int(m2.group(2)), int(m2.group(3))):
                    print(f"FAIL: servo {i} 校準範圍 yaml≠ApiValidator")
                    ok = False
        if sorted(rows) != list(range(1, 21)):
            print("FAIL: servo_map.yaml 解析唔到 20 軸")
            ok = False
        else:
            if [rows[i][0] for i in range(1, 21)] != at_q0 or [rows[i][1] for i in range(1, 21)] != q_init:
                print("FAIL: Alpha2PolicyIo 常量同 servo_map.yaml 唔一致")
                ok = False
        print("servo_map.yaml cross-check:", "OK" if ok else "FAILED")
    else:
        print("SKIP servo_map.yaml cross-check（冇提供 --urdf-repo／ALPHA2_URDF_REPO）")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--urdf-repo", default=os.environ.get("ALPHA2_URDF_REPO"))
    a = ap.parse_args()
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("搵唔到 javac/java，請裝 JDK 11+ 並加落 PATH")
    ok = cross_check(a.urdf_repo)

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="policy-test-"))
    try:
        fx = tmp / "fx"
        r = subprocess.run([sys.executable, str(ROOT / "scripts/policy_tool.py"), "make-fixtures", "--out", str(fx)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout, r.stderr)
            raise SystemExit("生成 fixtures 失敗（需要 pip install -r scripts/requirements.txt）")
        files = [f for d in ("main", "test") for f in sorted((SRC / d / "java" / PKG).glob("*.java"))]
        out = tmp / "classes"
        out.mkdir()
        r = subprocess.run([javac, "-encoding", "UTF-8", "-nowarn", "-d", str(out)] + [str(f) for f in files],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout, r.stderr)
            raise SystemExit("編譯失敗")
        r = subprocess.run([java, "-cp", str(out), "com.ubtechinc.alpha.hardware.policy.PolicyRunnerTest", str(fx)],
                           capture_output=True, text=True)
        print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        ok = ok and r.returncode == 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
