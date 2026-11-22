#!/usr/bin/env python3
"""
純 Java 單元測試執行器 (零依賴，不用 JUnit/gradle)。

原因見 app/src/test/java/com/open/alpha2/ApiValidatorTest.java 檔頭：
gradle cache 無 junit，不想整斷 `--offline` build。

流程：找 android.jar (SDK) + javac → 編譯被測類 + Test
→ 跑 main() → 非零 exit = 失敗 (啱 CI 用)。
跑十四套：ApiValidatorTest (ApiValidator closure)＋IkPoseTest (IkPose，
期望值由 app-ik.js 出)＋IkTransitionTest (Gamepad IK 均速過渡數學)＋
ColorTrackTest (ColorTrackLogic 單檔)＋MusicEqTest (MusicEq 單檔)＋
DownloadVerifyTest (DownloadVerify 單檔)＋IOUtilTest (IOUtil 單檔)＋PanelTokenTest (PanelToken＋ApiValidator closure)＋TlsPolicyTest (BundledCaSsl／SecureDownload，真 TLS 握手)＋
SafeUnzipTest (解壓限制)＋AuthThrottleTest (token 猜測節流)；
其中 ColorTrack／MusicEq／DownloadVerify／IOUtil 連 android.jar 都唔掂。

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
    # 1. local.properties (本機，不入 repo)
    lp = ROOT / "local.properties"
    if lp.exists():
        m = re.search(r"sdk\.dir\s*=\s*(.+)", lp.read_text(encoding="utf-8"))
        if m:
            cands.append(pathlib.Path(m.group(1).strip()))
    # 2. 環境變數 (CI 用這個)
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
    # 跟 app/build.gradle compileSdkVersion，找不到就用最高版 (stub 來，邊版都得)
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
    print(f"注意：無 {want}，用 {plats[-1].name} 代替 (只是 stub，无影响)");
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
    testdir = ROOT / "app" / "src" / "test" / "java" / "com" / "open" / "alpha2"
    test = testdir / "ApiValidatorTest.java"
    iktest = testdir / "IkPoseTest.java"
    iktrans = testdir / "IkTransitionTest.java"
    assert test.exists(), f"測試檔不見了: {test}"
    assert iktest.exists(), f"測試檔不見了: {iktest}"
    assert iktrans.exists(), f"測試檔不見了: {iktrans}"

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="apivalidator-test-"))
    # ApiValidator → HttpServer → WebSocketServer → EventBus 這條 closure
    # 僅掂到 android.util/Log + java.*，一齊編即可 (不用整個 app)。
    # 2026-09 橫切簡化後 HttpServer/EventBus 共用 JsonUtil/IOUtil，一齊編。
    files = [
        str(src / "ApiValidator.java"),
        str(src / "JsonUtil.java"),
        str(src / "IOUtil.java"),
        str(src / "HttpServer.java"),
        str(src / "WebSocketServer.java"),
        str(src / "EventBus.java"),
        str(src / "IkPose.java"),
        str(src / "IkTransition.java"),
        str(test),
        str(iktest),
        str(iktrans),
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
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第一套加碼：IkPoseTest（同 ApiValidator 一齊編，期望值由 app-ik.js 出）。
    r = subprocess.run(
        [java, "-cp", str(tmp) + os.pathsep + str(jar), "com.open.alpha2.IkPoseTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第一套再加碼：IkTransitionTest（Gamepad IK 均速過渡數學）。
    r = subprocess.run(
        [java, "-cp", str(tmp) + os.pathsep + str(jar), "com.open.alpha2.IkTransitionTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第二套：ColorTrackTest (ColorTrackLogic 純 java.*，唔經 android stub)。
    files2 = [
        str(src / "ColorTrackLogic.java"),
        str(testdir / "ColorTrackTest.java"),
    ]
    cp2 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files2
    r = subprocess.run(cp2, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (ColorTrackTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.ColorTrackTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第三套：MusicEqTest (MusicEq 純 java.*，唔經 android stub)。
    files3 = [
        str(src / "MusicEq.java"),
        str(testdir / "MusicEqTest.java"),
    ]
    cp3 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files3
    r = subprocess.run(cp3, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (MusicEqTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.MusicEqTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第四套：DownloadVerifyTest (DownloadVerify 純 java.*，唔經 android stub)。
    files4 = [
        str(src / "DownloadVerify.java"),
        str(testdir / "DownloadVerifyTest.java"),
    ]
    cp4 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files4
    r = subprocess.run(cp4, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (DownloadVerifyTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.DownloadVerifyTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第五套：IOUtilTest (IOUtil.readExactly 單檔，純 java.*，唔經 android stub)。
    files5 = [
        str(src / "IOUtil.java"),
        str(testdir / "IOUtilTest.java"),
    ]
    cp5 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files5
    r = subprocess.run(cp5, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (IOUtilTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.IOUtilTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第六套：PanelTokenTest (PanelToken 純 java.*，但要引用 ApiValidator 驗證 token 格式，
    # 所以同第一套一樣帶 android.jar 編 ApiValidator closure)。
    files6 = [
        str(src / "ApiValidator.java"),
        str(src / "JsonUtil.java"),
        str(src / "IOUtil.java"),
        str(src / "HttpServer.java"),
        str(src / "WebSocketServer.java"),
        str(src / "EventBus.java"),
        str(src / "PanelToken.java"),
        str(testdir / "PanelTokenTest.java"),
    ]
    cp6 = [javac, "-encoding", "UTF-8", "-nowarn", "-cp", str(jar), "-d", str(tmp)] + files6
    r = subprocess.run(cp6, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (PanelTokenTest)")
    r = subprocess.run(
        [java, "-cp", str(tmp), "com.open.alpha2.PanelTokenTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第七套：TlsPolicyTest (BundledCaSsl／SecureDownload，純 java.*，唔經 android stub)。
    # 用 JDK 內置 HttpsServer＋測試證書做真 TLS 握手；classpath 要加 resources
    # (內建 CA bundle ＋ 測試證書 fixtures，見 scripts/make-tls-fixtures.py)。
    files7 = [
        str(src / "BundledCaSsl.java"),
        str(src / "SecureDownload.java"),
        str(testdir / "TlsPolicyTest.java"),
    ]
    cp7 = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] + files7
    r = subprocess.run(cp7, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        raise SystemExit("編譯失敗 (TlsPolicyTest)")
    res_main = ROOT / "app" / "src" / "main" / "resources"
    res_test = ROOT / "app" / "src" / "test" / "resources"
    r = subprocess.run(
        [java, "-cp", os.pathsep.join([str(tmp), str(res_main), str(res_test)]),
         "com.open.alpha2.TlsPolicyTest"],
        capture_output=True, text=True,
    )
    print(r.stdout, end="")
    if r.stderr:
        print(r.stderr, end="", file=sys.stderr)
    if r.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        print("FAIL")
        return r.returncode

    # 第八、九套：SafeUnzipTest (解壓限制／壓縮炸彈)、AuthThrottleTest (token 猜測節流)，
    # 兩者都係純 java.*，單檔編譯，唔經 android stub。
    for cls, files in (
        ("SafeUnzipTest", ["SafeUnzip.java"]),
        ("AuthThrottleTest", ["AuthThrottle.java"]),
        ("RandomMovePolicyTest", ["RandomMovePolicy.java"]),
    ):
        cmd = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(tmp)] \
            + [str(src / f) for f in files] + [str(testdir / (cls + ".java"))]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout)
            print(r.stderr)
            raise SystemExit("編譯失敗 (" + cls + ")")
        r = subprocess.run([java, "-cp", str(tmp), "com.open.alpha2." + cls],
                           capture_output=True, text=True)
        print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        if r.returncode != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            print("FAIL")
            return r.returncode

    # 第十二至十四套：app/src/e2e 整合測試（見 app/src/e2e/README.md）。
    # 用最小 android stub（排喺正式 source 前面，唔帶真 android.jar：佢嘅方法一 call 就拋 "Stub!"）
    # 編譯真正嘅 HttpServer／PanelAuth 等，對真 socket 跑：HttpServer 行為、記憶體洪水（-Xmx64m）、
    # PanelAuth 預設啟用＋token 節流。每個測試獨立輸出目錄同 JVM，順序執行（HttpServer 固定 port）。
    e2e = ROOT / "app" / "src" / "e2e"
    sourcepath = os.pathsep.join([str(e2e / "stubs"), str(ROOT / "app" / "src" / "main" / "java")])
    for cls, jvm_args in (
        ("HttpServerE2ETest", []),
        ("HttpFloodE2ETest", ["-Xmx64m"]),
        ("PanelAuthE2ETest", []),
    ):
        out = tmp / ("e2e_" + cls)
        out.mkdir()
        cmd = [javac, "-proc:none", "-nowarn", "-implicit:class", "-encoding", "UTF-8",
               "-d", str(out), "-sourcepath", sourcepath,
               str(e2e / "java" / "com" / "open" / "alpha2" / (cls + ".java"))]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout)
            print(r.stderr)
            raise SystemExit("編譯失敗 (" + cls + ")")
        try:
            r = subprocess.run([java] + jvm_args + ["-cp", str(out), "com.open.alpha2." + cls],
                               capture_output=True, text=True, timeout=300)
        except subprocess.TimeoutExpired:
            shutil.rmtree(tmp, ignore_errors=True)
            print("FAIL (timeout) " + cls)
            return 1
        print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        if r.returncode != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            print("FAIL")
            return r.returncode

    shutil.rmtree(tmp, ignore_errors=True)
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
