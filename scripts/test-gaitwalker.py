#!/usr/bin/env python3
"""
GaitProgram / GaitWalker 單元測試執行器 (零依賴，不用 JUnit/gradle/SDK)。

同 scripts/test-serialcodec.py 同一格局：GaitWalker 只掂 java.*，
僅要 PATH 有 javac/java。非零 exit = 失敗 (啱 CI 用)。

另外順手跑 `ubx_to_gait.py --check`：確保 assets/gait/walk_fwd.json 同
scripts/gait-src/*.ubx 重新生成嘅結果一致（冇人手改 JSON／忘記重新生成）。

用法: python scripts/test-gaitwalker.py
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("搵唔到 javac/java，請裝 JDK 11+ 並加落 PATH")

    base = ROOT / "sdk-module" / "hardware-direct" / "src"
    src = base / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "ubx"
    test = base / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "ubx" / "GaitWalkerTest.java"
    files = [src / "GaitProgram.java", src / "GaitWalker.java", test]
    for f in files:
        assert f.exists(), f"源檔不見了: {f}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="gaitwalker-test-"))
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + [str(f) for f in files]
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗")

    r = subprocess.run(
        [java, "-cp", str(tmp), "com.ubtechinc.alpha.hardware.ubx.GaitWalkerTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    shutil.rmtree(tmp, ignore_errors=True)
    rc = r.returncode

    g = subprocess.run([sys.executable, str(ROOT / "scripts" / "ubx_to_gait.py"), "--check"],
                       capture_output=True, text=True)
    print(g.stdout, end="")
    if g.stderr:
        print(g.stderr, end="", file=sys.stderr)
    rc = rc or g.returncode

    print("PASS" if rc == 0 else "FAIL")
    return rc


if __name__ == "__main__":
    sys.exit(main())
