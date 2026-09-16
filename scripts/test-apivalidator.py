#!/usr/bin/env python3
"""
ApiValidator 單元測試執行器 (零依賴，不用 JUnit/gradle)。

原因見 app/src/test/java/com/open/alpha2/ApiValidatorTest.java 檔頭：
gradle cache 無 junit，唔想整斷 `--offline` build。

流程：搵 android.jar (SDK) + javac → 編譯 ApiValidator/HttpServer/Test
→ 跑 main() → 非零 exit = 失敗 (啱 CI 用)。

用法: python scripts/test-apivalidator.py
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def find_sdk() -> pathlib.Path:
    cands = []
    # 1. local.properties (本機，唔入 repo)
    lp = ROOT / "local.properties"
    if lp.exists():
        m = re.search(r"sdk\.dir\s*=\s*(.+)", lp.read_text(encoding="utf-8"))
        if m:
            cands.append(pathlib.Path(m.group(1).strip()))
    # 2. 環境變數 (CI 用呢個)
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            cands.append(pathlib.Path(os.environ[env]))
    # 3. 常見位置
    home = pathlib.Path.home()
    cands += [
        home / "AppData" / "Local" / "Android" / "Sdk",
        home / "Android" / "Sdk",
        pathlib.Path("/usr/lib/android-sdk"),
        pathlib.Path("/opt/android-sdk"),
    ]
    for c in cands:
        if c and (c / "platforms").is_dir():
            return c
    raise SystemExit("找不到 Android SDK (試過 local.properties / ANDROID_SDK_ROOT / 常見路徑)")


def find_android_jar(sdk: pathlib.Path) -> pathlib.Path:
    # 跟 app/build.gradle compileSdkVersion，搵唔到就用最高版 (stub 嚟，邊版都得)
    want = "android-25"
    gradle = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    m = re.search(r"compileSdkVersion\s+(\d+)", gradle)
    if m:
        want = "android-" + m.group(1)
    plats = sorted(
        (p for p in (sdk / "platforms").iterdir() if (p / "android.jar").exists()),
        key=lambda p: p.name,
    )
    if not plats:
        raise SystemExit(f"{sdk}/platforms 入面無 android.jar")
    for p in plats:
        if p.name == want:
            return p / "android.jar"
    print(f"注意：無 {want}，用 {plats[-1].name} 代替 (只係 stub，无影响)");
    return plats[-1] / "android.jar"


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("搵唔到 javac/java，請裝 JDK 11+ 並加落 PATH")
    sdk = find_sdk()
    jar = find_android_jar(sdk)
    print(f"SDK: {sdk}  android.jar: {jar.name}")

    src = ROOT / "app" / "src" / "main" / "java" / "com" / "open" / "alpha2"
    test = ROOT / "app" / "src" / "test" / "java" / "com" / "open" / "alpha2" / "ApiValidatorTest.java"
    assert test.exists(), f"測試檔唔見咗: {test}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="apivalidator-test-"))
    # ApiValidator → HttpServer → WebSocketServer → EventBus 呢條 closure
    # 淨係掂到 android.util/Log + java.*，一齊編即可 (唔使成個 app)。
    # 2026-09 橫切簡化後 HttpServer/EventBus 共用 JsonUtil/IOUtil，一齊編。
    files = [
        str(src / "ApiValidator.java"),
        str(src / "JsonUtil.java"),
        str(src / "IOUtil.java"),
        str(src / "HttpServer.java"),
        str(src / "WebSocketServer.java"),
        str(src / "EventBus.java"),
        str(test),
    ]
    cp = [javac, "-encoding", "UTF-8", "-nowarn", "-cp", str(jar), "-d", str(tmp)] + files
    r = subprocess.run(cp, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗")

    r = subprocess.run(
        [java, "-cp", str(tmp) + os.pathsep + str(jar), "com.open.alpha2.ApiValidatorTest"],
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
