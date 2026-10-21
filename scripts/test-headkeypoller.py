#!/usr/bin/env python3
"""
HeadKeyPoller 單元測試執行器 (零依賴，不用 JUnit/gradle/SDK)。

同 test-serialcodec.py 同一格局（唔經 gradle test task，免得整斷 `--offline`
build）。分別：HeadKeyPoller/HeadKeyMgr 掂到 android.util.Log +
android.os.SystemClock——SDK 嘅 android.jar 唔用得（runtime 一 call 就拋
Stub!），所以呢度臨時生成兩粒 no-op stub 再編譯執行。SystemClock stub
另提供可寫嘅 SystemClock.now，等測試控制 0x5e/66 嘅 1 秒去重窗。

流程：生成 stub → javac HeadKeyPoller + HeadKeyMgr + HeadKeyPollerTest
→ 跑 main() → 非零 exit = 失敗 (啱 CI 用)。

用法: python scripts/test-headkeypoller.py
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

LOG_STUB = """package android.util;

/** test-only stub（見 scripts/test-headkeypoller.py） */
public final class Log {
    private Log() {}
    public static int v(String tag, String msg) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
}
"""

CLOCK_STUB = """package android.os;

/** test-only stub：now 可寫，等測試控時（見 scripts/test-headkeypoller.py） */
public final class SystemClock {
    private SystemClock() {}
    public static volatile long now = 0;
    public static long uptimeMillis() { return now; }
}
"""


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("搵唔到 javac/java，請裝 JDK 11+ 並加落 PATH")

    base = ROOT / "sdk-module" / "hardware-direct" / "src"
    hw = base / "main" / "java" / "com" / "ubtechinc" / "alpha" / "hardware"
    hk = base / "main" / "java" / "com" / "ubtechinc" / "alpha" / "jni" / "headkey"
    native = base / "main" / "java" / "com" / "example" / "t01"
    test = base / "test" / "java" / "com" / "ubtechinc" / "alpha" / "hardware" / "HeadKeyPollerTest.java"
    assert (hw / "HeadKeyPoller.java").exists(), f"源檔不見了: {hw / 'HeadKeyPoller.java'}"
    assert (hw / "HardwareVersion.java").exists(), f"源檔不見了: {hw / 'HardwareVersion.java'}"
    assert (hk / "HeadKeyMgr.java").exists(), f"源檔不見了: {hk / 'HeadKeyMgr.java'}"
    assert (native / "nativeClass.java").exists(), f"源檔不見了: {native / 'nativeClass.java'}"
    assert test.exists(), f"測試檔不見了: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="headkeypoller-test-"))
    (tmp / "stub" / "android" / "util").mkdir(parents=True)
    (tmp / "stub" / "android" / "os").mkdir(parents=True)
    (tmp / "stub" / "android" / "util" / "Log.java").write_text(LOG_STUB, encoding="utf-8")
    (tmp / "stub" / "android" / "os" / "SystemClock.java").write_text(CLOCK_STUB, encoding="utf-8")

    files = [
        str(hw / "HeadKeyPoller.java"),
        str(hw / "HardwareVersion.java"),
        str(hk / "HeadKeyMgr.java"),
        str(native / "nativeClass.java"),
        str(tmp / "stub" / "android" / "util" / "Log.java"),
        str(tmp / "stub" / "android" / "os" / "SystemClock.java"),
        str(test),
    ]
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp / "classes")] + files
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        shutil.rmtree(tmp, ignore_errors=True)
        raise SystemExit("編譯失敗")

    r = subprocess.run(
        [java, "-cp", str(tmp / "classes"), "com.ubtechinc.alpha.hardware.HeadKeyPollerTest"],
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
