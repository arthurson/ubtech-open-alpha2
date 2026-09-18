#!/usr/bin/env python3
"""
SerialFrameCodec 單元測試執行器 (零依賴，不用 JUnit/gradle/SDK)。

同 scripts/test-apivalidator.py 同一格局：不想整斷 `--offline` build，
所以不經 gradle `test` task。分別：SerialFrameCodec 只掂 java.*，
連 android.jar stub 都不用找——僅要 PATH 有 javac/java。

流程：javac 編 SerialFrameCodec + SerialFrameCodecTest → 跑 main() →
非零 exit = 失敗 (啱 CI 用)。

用法: python scripts/test-serialcodec.py
"""
import os
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

    src = ROOT / "sdk-module" / "hardware-direct" / "src" / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    test = ROOT / "sdk-module" / "hardware-direct" / "src" / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "SerialFrameCodecTest.java"
    assert (src / "SerialFrameCodec.java").exists(), f"源檔不見了: {src}"
    assert test.exists(), f"測試檔不見了: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="serialcodec-test-"))
    # SerialFrameCodec 純 java.* (java.nio/java.util)，不用任何 classpath。
    files = [
        str(src / "SerialFrameCodec.java"),
        str(test),
    ]
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗")

    r = subprocess.run(
        [java, "-cp", str(tmp), "com.ubtechinc.alpha.hardware.SerialFrameCodecTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    shutil.rmtree(tmp, ignore_errors=True)
    print("PASS" if r.returncode == 0 else "FAIL")
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
