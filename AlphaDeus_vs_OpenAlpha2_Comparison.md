# Alpha Deus Revival vs Open Alpha2

## Feature, Security, and Code-Completeness Comparison

**Prepared:** 2026-11-10  
**Local package:** `C:\Users\user\Desktop\alpha2\Program-Arthur\Program`  
**GitHub project:** `https://github.com/arthurson/ubtech-open-alpha2`  
**Analyzed revision:** `main` at commit `484c6e2`, beta 8.1, 2026-11-01

> This is a static analysis based on the local installer, APK manifest, PDF manual, and the public GitHub source, README, OpenAPI spec, CI workflows, and release metadata. No hardware-in-the-loop testing was performed for this comparison.  
> Legend: ✅ present/native · 🟡 partial/unverified · ❌ not found/not present · ❓ not documented/unclear

---

## 1. Executive Summary

These are **not two versions of the same project**. They solve the same problem in completely different ways.

| | Alpha Deus Revival | Open Alpha2 |
|---|---|---|
| What it is | A Windows installer that patches the original UBTECH Android system and installs a modified `com.ubtechinc.alpha2services` app | An independent, open-source Android app (`com.open.alpha2`) that talks directly to the robot hardware |
| Installation | Windows admin + ADB root + `/system` remount + `services.jar` patch | Single APK, ordinary `adb install` |
| System changes | Yes: signature spoofing, system UID, persistent system app | No: normal sideloaded app, no root, no system partition changes |
| Control panel | Browser console on port 8080 (per installer) | Browser console on port 8888, 10 tabs plus Blockly |
| Source code | Not included; binary APK + opaque `patcher.exe` | Full Gradle project, GPL-3.0, CI, tests, OpenAPI |
| Best for | Keeping the original UBTECH voice/behavior stack, Lynx recovery, simple local revival | Safe long-term use, API automation, Blockly, Xiaozhi AI, 2-mic/4.4 support |

**Bottom line:** Open Alpha2 is substantially more complete, more maintainable, and safer to install. Alpha Deus Revival remains useful only when you specifically need the original UBTECH service stack, system-level features such as shutdown/reboot, or Lynx support, and you accept the risks of rooting and patching the system.

---

## 2. Scope and Evidence

### 2.1 Local package evidence

| Item | Finding |
|---|---|
| Installer | `install.bat`, titled **ALPHA DEUS REVIVAL - SYSTEM INSTALLER** |
| Install steps | `adb root`, `adb remount`, backup `/system/framework/services.jar`, apktool decompile, `patcher.exe`, rebuild, push patched JAR, uninstall UBTECH bloatware, push actions/Vosk, install three APKs, rewrite Wi-Fi config, reboot |
| Main APK | `APKs/output.apk` = `com.ubtechinc.alpha2services`, versionName `1.1.7.3`, versionCode `20`, `sharedUserId="android.uid.system"`, `persistent="true"` |
| Other APKs | `alp.apk` = `com.ubtechinc.alphaenglishchat` 1.0.3; `googletts.apk` |
| Console | `install.bat` tells the user to open `http://<robot-ip>:8080` |
| Recovery | `Recovery.bat` restores `services.jar` from `/system/framework/services.jar.bak`, `saved/services.jar`, or `saved/lynx/services.jar` |
| Documentation | 8-page `Alpha Deus Manual and Release Notes.pdf` |
| Source code | None. Only batch/sh installer scripts and prebuilt binaries |
| License | The root `LICENSE` is the Oracle JDK 17 license, not a project license |
| Footprint | About 3,040 files, 0.8 GB; includes JDK 17, platform-tools, apktool, smali/baksmali, d8, patcher, APKs, actions, Vosk model |
| Readable code | About 566 lines of batch/sh across `install.bat`, `Recovery.bat`, `ForceFirstRun.bat`, `wifi_setup.sh` |
| Opaque code | `tools/patcher.exe` is a 6.3 MB closed binary |

### 2.2 GitHub evidence

| Item | Finding |
|---|---|
| Package | `com.open.alpha2` |
| Version | `beta 8.1`, versionCode 9 |
| SDK | minSdk 19, targetSdk 22, armeabi-v7a |
| License | GPL-3.0-only |
| History | 189 commits; releases from beta 1 to beta 8.1 |
| Repository size | About 57.7 MB on GitHub; 265 files in the source archive |
| Java | 108 `.java` files, about 52,163 lines (including SDK and tests); 73 app classes |
| Web assets | 68 files, about 3.5 MB; 37 JS files, about 25,290 lines |
| Python | 21 files, about 3,382 lines |
| API spec | 219 paths in the current `open-alpha2-openapi.yml` (README still says 208) |
| Tests | 8 Java test classes + 4 Python test runners |
| CI | `build-apk.yml`, `check-openapi.yml` |
| Documentation | English README, Traditional Chinese README, OpenAPI, AsyncAPI, Swagger, `llms.txt` |

---

## 3. Architecture

### 3.1 Alpha Deus Revival

```text
┌──────────────────────┐
│ Windows PC           │
│ install.bat          │
│ patcher.exe          │
└──────────┬───────────┘
           │ adb root / remount
           ▼
┌────────────────────────────────────┐
│ /system/framework/services.jar     │
│  + signature-verification patch    │
└──────────┬─────────────────────────┘
           │ install 3 APKs
           ▼
┌────────────────────────────────────┐
│ com.ubtechinc.alpha2services       │
│ 1.1.7.3                            │
│ system UID / persistent            │
│ + Vosk / OpenCV / original UBTECH  │
└──────────┬─────────────────────────┘
           │ /dev/ttyS1, /dev/ttyS3
           ▼
      Alpha2 hardware

[Browser :8080] ── HTTP ──> Alpha Deus web console
```

**Character:** keep the original UBTECH stack, patch it, and add features around it.

### 3.2 Open Alpha2

```text
[Browser :8888] ── HTTP / WebSocket ──> com.open.alpha2
                                           │
                                           ├─ /dev/ttyS1, /dev/ttyS3
                                           ├─ Vosk / TTS
                                           ├─ Xiaozhi / MCP
                                           ├─ Blockly / OpenAPI
                                           ▼
                                      Alpha2 hardware
```

**Character:** a clean replacement app that directly controls the hardware and does not depend on UBTECH services.

---

## 4. Feature Comparison

### 4.1 Control panel, API, automation

| Feature | Alpha Deus Revival | Open Alpha2 | Notes |
|---|---|---|---|
| Browser control panel | ✅ 8080 | ✅ 8888, 10 tabs | Both are browser-based |
| Live updates | ❓ not documented | ✅ WebSocket `/ws` | Open has event push |
| UI languages | English / Spanish (PDF) | English + Traditional Chinese | Open documentation is bilingual |
| Blockly visual programming | ❌ not found | ✅ `/blockly.html`, 12 toolbox categories | Open only |
| Panel token authentication | ❌ not found | ✅ opt-in token for API, upload, stream, ws | Off by default |
| HTTP API documentation | ❌ not found | ✅ OpenAPI 219 paths + AsyncAPI + Swagger `/docs` | Open only |
| Typed JS client | ❌ | ✅ 199 wrappers | Generated from spec |
| MCP tools | ❌ | ✅ 22 tools | For Xiaozhi AI |
| Event log | ❓ | ✅ permanent log card | |
| Download manager | ❌ | ✅ `DownloadGate` | Vosk, action pack, TTS APK |
| OTA / self-update | ❌ | ❌ manual APK / GitHub release | Neither has real OTA |
| Installation difficulty | 🔴 Windows admin + ADB root + system patch | 🟢 single APK `adb install` | Major difference |

### 4.2 Motion, servos, LEDs

| Feature | Alpha Deus Revival | Open Alpha2 | Notes |
|---|---|---|---|
| `.ubx` action playback | ✅ | ✅ | Both use `/sdcard/actions` |
| Action categories / list | ✅ | ✅ five groups | |
| Playback speed | ❓ | ✅ 0.5–2.0x, servos and mp3 follow | |
| Action pack | ✅ installer pushes about 286 `.ubx` | ✅ 202-action download pack | Local set includes duplicates/unindexed files |
| IK actions | ❌ not found | ✅ squat, split, kick, side lunge, half squat, bent-arm reach, front/back split | Open only |
| Direct transition | ❌ | ✅ blend from current pose to target pose | |
| Gamepad / keyboard | ❌ not found | ✅ BT HID + browser Gamepad API | |
| Walk forward/backward | ✅ voice commands | ✅ hold-to-walk, 1.0–2.0x, 700 ms lease | Open has safer stop behavior |
| Turn / strafe | ✅ voice commands | ❓ not listed | |
| Emergency stop | ✅ | ✅ | |
| Sleep / wake | ✅ | ❓ not listed | |
| Reboot / shutdown | ✅ system UID and REBOOT permission | ❌ no API; manifest declares REBOOT but it is privileged and unusable | Alpha Deus advantage |
| Servo sliders | ✅ | ✅ 22 axes grouped by head/arm/leg/extra | |
| Live angle read-back | ❓ | ✅ `servo/back-angle-all`, single-servo reads | |
| Trim / offset calibration | ❓ | ✅ backup/restore and factory-style calibration | |
| Servo ID change | ❓ | ✅ | |
| Servo firmware | ❓ | ✅ version query + single-servo flash | |
| Eye / LED control | ✅ Eye Color | ✅ head/eye/mouth presets and effects | |
| All lights off | ❓ | ✅ | |
| LED state polling / mute mirror | ❌ | ✅ 100 ms `led/state/get` | |
| Servo current feedback | ❌ | ❌ | Neither; Open documents the limitation |

### 4.3 Camera, speech, music

| Feature | Alpha Deus Revival | Open Alpha2 | Notes |
|---|---|---|---|
| Live camera | 🟡 latest photo only (Live Vision) | ✅ MJPEG stream | Open is much stronger |
| Photo capture / save | ✅ | ✅ nine quality levels | |
| Video recording | ❌ | ✅ 640×480 H.264 mp4 | |
| Digital zoom / pan-tilt | ❌ | ✅ | |
| Browser mic listening | ❌ | ✅ `/stream/mic` | |
| Face tracking | ✅ OpenCV | ✅ built-in FaceDetector | |
| Color tracking | ❌ | ✅ HSV + largest blob, optional walk-follow | |
| Remote TTS | ✅ English / Spanish | ✅ engine/language/voice, optional Vosk follow | |
| Offline ASR | ✅ Vosk | ✅ Vosk | Both |
| Wake word | ✅ local wake word | 🟡 Vosk auto-listen; no dedicated wake word documented | |
| Voice intents | ✅ 40+ English commands in PDF | ✅ 10 languages × 299 intent groups | |
| Virtual input | ✅ inject fake voice commands | ✅ `speech/semantic_simulate` | |
| Memory Manager | ✅ SQL learned phrases/responses | ❌ not found | Alpha Deus only |
| Conversational AI | 🟡 original/offline TFLite | ✅ optional Xiaozhi AI + 22 MCP tools | |
| Music / radio | ✅ Radio | ✅ local music, radio-browser, Internet Archive, EQ | |
| Ringtones | ❓ | ✅ system/custom ringtones | |
| 5-band EQ | ❌ | ✅ | |
| Walkie-talkie audio upload | ❌ | ✅ `/upload/audio` | |

### 4.4 System, sensors, compatibility

| Feature | Alpha Deus Revival | Open Alpha2 | Notes |
|---|---|---|---|
| Wi-Fi toggle | ✅ voice | ✅ API / voice | |
| Bluetooth toggle | ✅ voice | ✅ API / gamepad | |
| Battery / device info | ❓ | ✅ battery, Wi-Fi, BT, UUID, chest firmware | |
| Firmware information | ❓ | ✅ | |
| Robot ID change | ❓ | ✅ query / QR / EEPROM write | |
| Sonar | 🟡 long-press sensor toggle | ✅ live chart | |
| Accelerometer | 🟡 long-press sensor toggle | ✅ live chart + 4-direction tilt LED | |
| PIR | ❌ | ✅ alert with red LED and ringtone | |
| Boot voice mode | ❌ | ✅ off / auto Xiaozhi / auto Vosk | |
| Android 5.1 | ✅ optimized | ✅ `alpha2_10005` 5-mic | |
| Android 4.4 | 🟡 PDF says manual adjustments may be needed | ✅ `alpha2_10002` 2-mic | Open explicitly supports |
| Lynx | 🟡 PDF notes partial support; Lynx `services.jar` backup included | ❌ not claimed | Alpha Deus is broader here |
| ABI | ✅ armeabi-v7a | ✅ armeabi-v7a | |
| Root / system patch required | 🔴 yes | 🟢 no | Biggest safety difference |
| Reversibility | 🟡 `Recovery.bat`, but bootloop risk | 🟢 uninstall APK | |

### 4.5 Feature score by area

| Area | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|
| Control panel / API / automation | 4.0 | 10.0 |
| Motion and actions | 6.0 | 9.5 |
| Servo and LED | 5.0 | 9.5 |
| Camera | 4.0 | 9.5 |
| Speech and AI | 6.0 | 9.0 |
| Music and audio | 4.0 | 9.0 |
| Sensors | 3.0 | 9.0 |
| Hardware compatibility | 6.0 | 8.5 |
| Security | 3.0 | 6.5 |
| Code completeness | 1.0 | 9.0 |

> These scores are subjective, based on documented and discoverable features, not laboratory measurements.

---

## 5. Security Comparison

### 5.1 Security matrix

| Security item | Alpha Deus Revival | Open Alpha2 | Risk interpretation |
|---|---|---|---|
| Installation privilege | 🔴 Windows admin + `adb root` + `remount` | 🟢 ordinary APK install | The installer itself runs with high privilege |
| `/system` modification | 🔴 modifies `services.jar` | 🟢 none | Local can bootloop the robot |
| Signature spoofing | 🔴 installer explicitly labels it | 🟢 none | Android signature enforcement is weakened |
| App identity | 🔴 `android.uid.system`, `persistent=true` | 🟢 ordinary UID | Local app effectively has system authority |
| Permission count | 🔴 40 unique permissions | 🟡 16 unique permissions | Local includes REBOOT, INSTALL_PACKAGES, DELETE_PACKAGES, INJECT_EVENTS, READ_CONTACTS, SEND_SMS, CALL_PHONE, MOUNT_UNMOUNT_FILESYSTEMS |
| Exported components | 🟠 13: 6 services, 4 providers, 2 activities, 1 receiver | 🟢 1 exported receiver (`BootReceiver`) | Local attack surface is much larger |
| Web authentication default | 🔴 no token/login documented | 🟠 disabled by default; token optional | Both should stay on a trusted LAN |
| Authentication implementation | ❓ cannot inspect | 🟢 constant-time compare, 8–64 chars, old token required to change, log redaction | Open is well designed; query fallback can still leak in URLs |
| Panel TLS | ❓ not documented | 🔴 plain HTTP, no TLS | Token can be sniffed on the LAN |
| Outbound TLS | ❓ original libraries unknown | 🟠 `XiaozhiTrustAllSsl` accepts any certificate and is reused by action-pack, APK, and Vosk downloaders | MITM risk is non-zero |
| Camera/mic exposure | 🟠 Live Vision; mic behavior unclear | 🟠 MJPEG + mic stream; protected only when token is enabled | Defaults are open on the LAN |
| Firmware upload/flash | ❓ original feature not listed | 🟠 chest/servo upload and flash; token-gated; chest requires fresh upload | High-value target if token is off |
| Cloud/privacy | 🟠 original iflytek/Nuance/XMPP libraries remain; service config points to 127.0.0.1; SQL memory; `pkcs8_rsa_private_key.pem` in assets | 🟡 optional Xiaozhi cloud; Vosk offline; mic streaming | Local carries more legacy material |
| Data at rest | 🟠 SQL learned phrases, actions, settings; no encryption found | 🟡 photos/videos/preferences; no encryption found | Neither provides at-rest encryption |
| Supply chain | 🔴 closed `patcher.exe` + prebuilt APK + closed original libraries | 🟡 source and CI are public, but prebuilt `.so`/AAR files, public debug keystore, trust-all downloads remain | Local is not auditable; Open is mostly auditable |
| Update mechanism | 🔴 no version history or update channel | 🟡 GitHub releases/CI artifact; manual install | Local requires reinstall |
| Recovery | 🟠 `Recovery.bat`, but bootloop/calibration risk | 🟢 uninstall APK | Open is more reversible |
| Network exposure | 🟠 console on 8080, LAN-wide | 🟠 console on 8888, LAN-wide | Never expose either to the internet |
| Auditability | 🔴 binary only | 🟢 GPL-3.0 source | Largest code-security difference |

### 5.2 Top security risks

#### Alpha Deus Revival

1. **Signature spoofing plus system UID.** Patching `services.jar` weakens Android signature verification, while the app runs as `android.uid.system`. A compromise affects the whole Android system, not just the robot app.
2. **40 permissions and 13 exported components.** The app requests far more than robot control needs, including package installation, event injection, contacts, SMS, phone calls, and filesystem mounting.
3. **Console on 8080 with no documented authentication or TLS.** Anyone on the same LAN may be able to control the robot; the PDF has no security model.
4. **Closed patcher and prebuilt APK.** `patcher.exe` is opaque, and `output.apk` cannot be rebuilt from the supplied files.
5. **Private-key material in APK assets.** `pkcs8_rsa_private_key.pem` is present in the APK. Treat the whole package as sensitive binary material.

#### Open Alpha2

1. **Panel token is off by default.** All `/api/*`, `/upload/*`, `/stream/*`, and `/ws` endpoints are open until the user enables a token.
2. **Plain HTTP only.** The control panel has no TLS; tokens and camera/mic streams can be sniffed on the LAN.
3. **Trust-all SSL is reused beyond Xiaozhi.** Source inspection shows `XiaozhiTrustAllSsl` is also used by the action-pack, APK, and Vosk download paths, so MITM protection depends on the network.
4. **Firmware and servo upload endpoints.** These are high-value targets when the token is disabled.
5. **Beta quality and small community.** CI publishes a debug-signed APK; the project has one maintainer and a small user base, so long-term security support is not guaranteed.

### 5.3 Hardening checklist

#### If you keep Alpha Deus Revival

- Keep the robot on an isolated, trusted LAN. Never expose port 8080 to the internet.
- Keep the original `services.jar` backup and a working ADB connection before changing anything.
- Do not install untrusted APKs or run `patcher.exe` from an unknown source.
- Treat the package as containing sensitive key material; do not redistribute it casually.
- Avoid using the robot for private conversations if the microphone/camera console is reachable by others.

#### If you use Open Alpha2

- Enable the panel token in the Experiment tab.
- Prefer the `Authorization: Bearer` header; avoid `?panel_token=` in URLs.
- Never expose port 8888 to the internet.
- Use the robot only on a trusted LAN.
- Avoid Xiaozhi and download operations on hostile networks because of trust-all SSL.
- Use official GitHub releases/CI artifacts and verify the source before installing.
- Disable camera/mic features when not needed.

---

## 6. Code Completeness and Engineering Quality

### 6.1 Quantitative comparison

| Metric | Alpha Deus Revival | Open Alpha2 |
|---|---|---|
| App source code available | ❌ none | ✅ 108 Java files, about 52,163 lines; 73 app classes |
| Web frontend source | ❌ prebuilt APK only | ✅ 68 web assets, 37 JS files, about 25,290 lines JS, 2,941 lines CSS |
| Build system | ❌ batch + apktool + patcher | ✅ Gradle 7.0, AGP 4.2.2, JDK 11, pinned NDK/CMake |
| Reproducible APK build | ❌ cannot rebuild `output.apk` from the folder | ✅ `./gradlew assembleDebug --offline`; CI also builds |
| API specification | ❌ none | ✅ OpenAPI 219 paths, 7,469-line YAML; AsyncAPI; MCP sync |
| Tests | ❌ none | ✅ 8 Java test classes + 4 Python test runners |
| CI | ❌ none | ✅ `build-apk` + `check-openapi` workflows |
| Documentation | 🟡 8-page PDF manual | ✅ 412-line English README + 335-line Chinese README + OpenAPI + Swagger + `llms.txt` |
| Version control | ❌ no git/version history | ✅ GitHub, 189 commits, beta 1 to beta 8.1 |
| License | ❌ root `LICENSE` is the Oracle JDK license | ✅ GPL-3.0-only |
| Release artifact | Three APKs, about 80 MB total | One APK, clean build about 5.5 MB; GitHub releases/CI artifacts |
| Binary transparency | 🔴 opaque `patcher.exe`, prebuilt APK, closed libs | 🟡 source public, but prebuilt `.so`, Vosk/JNA AARs, public debug keystore |
| Comments/readability | ❌ no source to read | ✅ heavily commented, clear class responsibilities |
| Documentation accuracy | 🟡 PDF only, no API docs | 🟡 README says 208 paths; current spec has 219 (minor drift) |
| Test coverage | ❌ none | 🟡 unit/static checks exist; no coverage number; hardware-in-the-loop is manual |
| Hardware validation | 🟡 PDF says 5.1 optimized; 4.4 may need manual work | 🟡 beta; backward gait, direct IK, and similar items remain unverified |
| Maintainer / bus factor | ❌ unknown, no public repo | 🟡 one maintainer, small community (1 star, 1 fork, 2 open issues) |
| Feature completeness | 🟡 complete within its narrow scope | ✅ broad, but still beta with known limitations |

### 6.2 Code-completeness scorecard

| Dimension | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|
| Source available | 0 / 5 | 5 / 5 |
| Reproducible build | 0 / 5 | 5 / 5 |
| Tests | 0 / 5 | 4 / 5 |
| Documentation | 2 / 5 | 5 / 5 |
| API/specification | 0 / 5 | 5 / 5 |
| CI/version control | 0 / 5 | 5 / 5 |
| **Total** | **2 / 30** | **29 / 30** |

> Alpha Deus earns its two points for readable installer scripts and a basic PDF manual. The app itself remains an unreproducible binary.

### 6.3 Reproducibility details

**Alpha Deus Revival**

- The folder contains no Java/Kotlin source, no Gradle project, no resources, and no build scripts for the APK.
- `output.apk` is prebuilt and must be treated as an opaque artifact.
- `patcher.exe` performs an unknown transformation on `services.jar`; its behavior cannot be reviewed from source.
- The only reproducible part is the installer workflow itself, which is readable batch/sh.

**Open Alpha2**

- Full Android project with `app/` and `sdk-module/hardware-direct/`.
- Pinned toolchain: Temurin JDK 11, Gradle 7.0, AGP 4.2.2, NDK `21.4.7075529`, CMake `3.10.2.4988404`.
- Offline build is supported because Vosk and JNA AARs are vendored under `app/libs`.
- The debug keystore is committed and non-secret, so the produced APK is debug-signed.
- Prebuilt native libraries (`head_led`, `head_key_mgr`, `serial_port`) remain binary; `libeasyopus.so` is built from source via CMake.
- CI produces an artifact, but there is no production signing/provenance pipeline.

---

## 7. Unique Strengths

### Alpha Deus Revival

- **Memory Manager**: SQL database of learned phrases and responses, editable from the web console.
- **Virtual Input**: inject fake voice commands for testing or noisy environments.
- **Sleep/wake and system shutdown/reboot**: possible because the app has system UID and REBOOT permission.
- **Original UBTECH voice/behavior stack**: iflytek/Nuance/XMPP components and the original action engine remain present.
- **Lynx compatibility hints**: PDF caveats and a Lynx `services.jar` recovery image.
- **Bundled offline resources**: Vosk model, about 286 `.ubx` files, and Google TTS installed in one pass.

### Open Alpha2

- **Complete HTTP/WebSocket API**: 219 paths, Swagger, AsyncAPI, typed client.
- **Blockly**: visual programming with 12 toolbox categories, English and Traditional Chinese.
- **Xiaozhi AI + 22 MCP tools**: optional cloud AI plus local Vosk.
- **Gamepad/keyboard mapping**: BT HID and browser Gamepad API.
- **IK actions + direct transition**: squat, split, kick, side lunge, half squat, bent-arm reach, front/back split.
- **Hold-to-walk**: speed control, heartbeat lease, automatic stop on lost connection.
- **Complete camera pipeline**: MJPEG, recording, zoom, pan-tilt, face and color tracking, walk-follow.
- **Complete music pipeline**: local music, radio, Internet Archive, EQ, spectrum.
- **Complete servo pipeline**: 22 axes, read-back, trim, ID change, firmware, calibration.
- **Panel token**: opt-in authentication with constant-time comparison and log redaction.
- **Auditable, buildable, testable source**.

---

## 8. Decision Guide

### Choose Open Alpha2 if you want

- Safety and reversibility: no root, no system patch, uninstallable.
- A complete API, Blockly, Xiaozhi AI, gamepad, IK, video recording, and EQ.
- Explicit support for Android 5.1 5-mic and Android 4.4 2-mic.
- Source code you can audit, build, and modify.
- A control panel you can protect with a token and keep on a trusted LAN.

### Choose Alpha Deus Revival if you want

- The original UBTECH voice/behavior stack and Memory Manager.
- Sleep/wake, shutdown/reboot, and Virtual Input.
- Lynx support, or you already have the installer and accept the modification risk.
- A simpler local revival package rather than a full development platform.
- You are willing to accept bootloop, signature-bypass, system-UID, and no-source risks.

### Operational notes

1. **Do not run both at the same time.** Both directly or indirectly use the same serial ports (`/dev/ttyS1`, `/dev/ttyS3`) and can conflict.
2. **Before moving to Open Alpha2**, back up `/sdcard/actions` and the Vosk model. Open Alpha2 can read `.ubx` files, but action metadata and category mapping may not match.
3. **Before removing Alpha Deus**, verify that `Recovery.bat` can restore `services.jar`, and keep `saved/services.jar` and `saved/lynx/services.jar`.
4. **Enable the Open Alpha2 panel token** and never expose port 8888 to the internet.
5. **Keep Alpha Deus on an isolated LAN**; its 8080 console has no documented authentication or TLS.

---

## 9. Final Scorecard

| Question | Answer |
|---|---|
| Which has more features? | **Open Alpha2** by a wide margin, especially API, Blockly, gamepad, IK, camera, music, and servo. |
| Which is safer? | **Open Alpha2**, because it does not modify the system, does not require root, has fewer permissions, and is uninstallable. It still has plain HTTP, default-open authentication, and trust-all SSL caveats. |
| Which has more complete code? | **Open Alpha2**: 108 Java files, 219 API paths, 8 Java test classes, 2 CI workflows, GPL-3.0. Alpha Deus has only binaries plus about 566 lines of installer scripts. |
| Which is better for long-term use? | **Open Alpha2**, provided you enable the panel token, keep it on a trusted LAN, and accept beta risk. |
| Which preserves the original robot behavior? | **Alpha Deus Revival**, but with system-patch, security, and no-source risks. |
| Which is better for Lynx? | **Alpha Deus Revival** is the more plausible option; Open Alpha2 does not claim Lynx support. |

### Weighted summary

| Metric | Weight | Alpha Deus Revival | Open Alpha2 |
|---|---:|---:|---:|
| Feature breadth | 30% | 6.0 | 9.5 |
| Security | 25% | 3.0 | 6.5 |
| Code completeness | 20% | 1.0 | 9.0 |
| Installation ease | 10% | 3.0 | 9.0 |
| Hardware compatibility | 10% | 6.0 | 8.5 |
| Maintainability | 5% | 1.0 | 9.0 |
| **Weighted total** | **100%** | **~3.7 / 10** | **~8.5 / 10** |

---

## 10. Bottom Line

**Open Alpha2 is the stronger project overall.** It is a real Android application with a full source tree, documented API, automated build, tests, and a much smaller system footprint. Its main weaknesses are that it is still beta, its panel authentication is opt-in and plain HTTP, and some download/Xiaozhi paths use trust-all SSL.

**Alpha Deus Revival is a system-modification package, not a maintainable application project.** It can be useful for preserving original UBTECH behavior, system-level controls, or Lynx compatibility, but it requires rooting and patching the robot, runs with system UID, weakens signature verification, and ships without source code. Treat it as a high-risk, closed binary distribution rather than a platform to build on.

---

### Appendix: Scoring rationale

- **Feature breadth** measures how many documented robot capabilities are exposed through the app.
- **Security** considers installation privilege, system modification, permission scope, network authentication, transport security, supply chain, and reversibility.
- **Code completeness** considers source availability, build reproducibility, API specification, tests, CI, documentation, version control, and licensing.
- **Installation ease** measures the number of steps, required privileges, and risk of bricking.
- **Hardware compatibility** considers documented Android versions, microphone variants, Lynx support, and ABI coverage.
- **Maintainability** considers source structure, tests, CI, documentation, release cadence, and bus factor.

These scores are comparative and subjective. They are intended to support a decision, not to replace a hardware security review.
