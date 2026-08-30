# iFlytek Complete Workflow and Offline Architecture

> Based on the Open Alpha2 project (UBTECH Alpha2 robot, firmware v1.1.7.3.20) — compiled from actual decompilation results, logcat evidence, and source code.
> All conclusions in this document are backed by on-device testing (errorCode / logcat evidence), not speculation.

---

## 1. Architecture Overview

```
┌───────────────────────────────────────────────────────────────────┐
│                       Alpha2 Robot System                          │
├───────────────────────────────────┬─────────────────────────────┤
│  alpha2services (firmware, closed) │   Open Alpha2 App (userland)  │
├───────────────────────────────────┼─────────────────────────────┤
│ • 5-mic array + IVW wake engine    │ • HttpServer (port 8888)     │
│ • iFlytek MSC (Chinese ASR/TTS)    │ • WebSocketServer            │
│ • Nuance VoCon (built-in Eng ASR)  │ • EventBus dispatch          │
│ • Alpha2RobotApi (AIDL interface)  │ • IflytekSemanticMatcher      │
└───────────────────────────────────┴─────────────────────────────┘
```

**Core data flow:**

```
User speech → 5-mic array (hardware layer, bypasses AudioFlinger)
           → Wake word triggered (CN: "你好阿爾法" / EN: "hello alpha")
           → App runs a live network probe (decides which ASR path to use)
           → Online: iFlytek cloud dictation (type:0, arbitrary sentences)
           → Offline: iFlytek local BNF grammar recognition (type:1, fixed vocabulary only)
           → Semantic matching (IflytekSemanticMatcher / …En)
           → TTS response + action execution
```

---

## 2. Two ASR Engines, One Language Each

The robot ships with two fully independent speech recognition engines — this is not "one engine, two languages":

| | iFlytek MSC | Nuance VoCon |
|---|---|---|
| Language handled | **Mandarin Chinese** | **English** |
| Offline capable | ✅ but requires BNF grammar (see Section 3) | ✅ built into native code, works out of the box |
| Vocabulary extensible | ✅ custom BNF, sentences/words can be added | ❌ `initGrammar` is an empty stub — vocabulary can never be added |
| Cloud dictation | ✅ arbitrary sentences (`openspeech.cn`) | ☠️ cloud servers are permanently offline; any result below the confidence threshold (~4500) fails with no fallback |
| How to switch | `speech/set_language?lang=zh_cn` | `speech/set_language?lang=en_us` |

**Decompilation finding**: iFlytek's official documentation (訊飛開放平台) states explicitly:

> "Does offline command-word recognition support English? Answer: Offline command-word recognition only supports Mandarin Chinese; English is not currently supported."

This means `assets/asr/common.jet` (the acoustic model actually used on-device, 6.8MB) **only contains Mandarin phonemes**. This is not a grammar-syntax problem — Section 6 documents the full experimental proof.

---

## 3. Offline Chinese Grammar Recognition (iFlytek BNF) — Primary Solution

### 3.1 Grammar Format (the only format confirmed to work)

The device does not accept the commonly documented IAMVERSION 1.1.0 format online. It requires the **`#BNF+IAT 1.0` format** used in `call.bnf` from the original UBTECH factory APK (`UbtechIflytekMix`):

```
#BNF+IAT 1.0 UTF-8;
!grammar call;
!start <start>;
<start>: 你好 | 再見 | 跳舞 | ...;
```

**Confirmed grammar rules (learned the hard way through testing):**

| Rule | Notes |
|---|---|
| First line must be `#BNF+IAT 1.0 UTF-8;` | Format header — the IAMVERSION variant is not accepted |
| `!grammar` name must be `call` | Must match `grammarId=call` used by the firmware |
| **No spaces allowed inside any sentence** | Fine for Chinese; multi-word English phrases (e.g. `how old`) **reliably fail with error 23300** — only single English words work |
| **Rule chaining/reference is not supported** | Concatenating rules without a space (`<a><b>`) fails with 23300 for both Chinese and English — this is an engine limitation, not a language limitation |
| **Declaring an unused `!slot` triggers 23300** | If a slot is declared it must be used; otherwise, don't declare it |
| Sentences must not contain `:` or `;` | These are reserved parser characters — mixing them into alternatives corrupts rule boundaries (see the incident in Section 7) |
| Every rule must end with `;` | Omitting it causes the following rule's content to be swallowed into the previous one |

### 3.2 Full Runtime Flow

```
App startup → MainActivity.onCreate() → bindService(Alpha2SpeechMainService)
    → robot.speech_initGrammar(bnfString, ISpeechGrammarInitListener)
        → callback speechGrammarInitCallback(grammarId, errorCode)
            errorCode == 0 → lastGrammarBuildOk = true
    → (once built successfully) robot.speech_startGrammar(IAlpha2SpeechGrammarListener)
        → firmware enters SPEECH_STATE_GRAMMAR, waits for wake word

When the user speaks:
    Wake word triggers (MicArray wakeup) → firmware decodes locally against the BNF grammar network
    → onSpeechGrammarResult(type=1, result)
        result is a **raw JSON string** (not plain text!):
        {"text":"你好","rc":4}                     ← simple format
        {"ws":[{"cw":[{"w":"你叫什么名字"}]}]}      ← full format seen on some firmware builds
    → extractGrammarResultText() parses out the clean text
    → EventBus.publish("asr_result", text)
    → IflytekSemanticMatcher.match(text)
    → TTS response + action execution
```

**Important pitfall**: the JSON structure of offline grammar results differs from cloud dictation (type:0), which is plain text. Offline grammar results sometimes carry the recognized word inside `ws[].cw[].w` rather than the top-level `text` field. Failing to handle this structure causes the chat UI to show nothing, or to dump raw JSON fragments (an actual bug encountered — see Section 7).

### 3.3 Auto-generating BNF from the Semantic Library

The grammar is not hand-written; it is auto-generated at App startup from the semantic library JSON (`iflytek_semantic_zh.json`):

```
iflytek_semantic_zh.json (946 Q&A entries)
    ↓ each question phrase (the `q` field) becomes a BNF alternative
    ↓ filter: sentences containing `:` or `;` are skipped (prevents parser corruption)
    ↓ Simplified→Traditional conversion (SimplifiedToTraditional), so factory (simplified)
      and semantic-library (traditional) entries automatically de-duplicate
default_grammar.bnf (the resulting grammar string, sent to the device via speech_initGrammar)
```

**Final stable version (verified errorCode=0, complete offline conversation confirmed working):**

| Item | Count |
|---|---|
| Chinese sentences | **1211** (all Traditional Chinese, zero exact duplicates) |
| English words | 3 (Goodbye / Bye / Thanks) |
| Total sent to device | **1214** |
| Semantic library (JSON) | 946 entries (reduced from an original 1000 by removing 54 templated filler questions such as "你有沒有特別/最喜歡/最想…") |

> This number went through several iterations (1863 → 1618 → 1388 → 1268 → 1265 → 1211), each round removing simplified/traditional duplicates, "rule-name" pollution (factory rule names like `app` / `happy` / `power` accidentally leaking into the vocabulary), and templated filler sentences. These reductions are cleanup, not feature regressions.

---

## 4. Automatic Online/Offline Switching

### 4.1 Core Logic

```java
private void applyConnectivityMode(boolean connected, String reason) {
    if (!offlineGrammarAutoSwitch || !speechReady) return;

    if (!connected) {                          // determined to be offline
        if (offlineGrammarActive) return;       // already in offline mode, no action needed
        robot.speech_setRecognizedLanguage("zh_cn");
        if (!lastGrammarBuildOk) {
            pendingOfflineEnable = true;
            doInitGrammar(defaultBnf);           // build grammar if not built yet
        } else {
            doStartGrammar();                    // already built, start directly
        }
    } else {                                   // determined to be online
        if (offlineGrammarActive &&
            now - lastModeSwitchMs < 15000) return;  // 15-second cooldown
        pendingOfflineEnable = false;
        if (offlineGrammarActive) doStopGrammar();
    }
}
```

### 4.2 Network Probing — "Connected to WiFi" ≠ "Has Internet"

This was the original blind spot: Android's WiFi `connected` state only means the device is associated with a hotspot — it says nothing about whether that hotspot actually has data. When a phone hotspot had no data, the original logic misjudged the state as "online," causing voice to fail silently while still using cloud mode.

**Corrected probing logic:**

```java
private static boolean hasRealInternet() {
    String[][] targets = {
        {"ubtek.openspeech.cn", "80"},   // the device's actual dedicated server (confirmed via smali decompilation)
        {"openspeech.cn", "80"},          // iFlytek's official domain
        {"voicecloud.cn", "443"}          // fallback
    };
    // TCP connect, 2.5s timeout, any successful connection counts as "online"
}
```

**Decompilation finding**: the firmware actually connects to `ubtek.openspeech.cn` (`server_url=http://ubtek.openspeech.cn/...`), UBTECH's own iFlytek relay server — not iFlytek's public domain. This means probing must target this specific domain; reachability of a public DNS server (8.8.8.8) alone says nothing about whether the iFlytek cloud service is actually usable.

### 4.3 Anti-flicker Design

| Mechanism | Parameter | Reason |
|---|---|---|
| **Cooldown period** | 15 seconds | Prevents rapid mode-flipping during unstable connectivity, which would repeatedly destroy/rebuild the grammar engine |
| **Probe-on-wake** | Live probe on every wake-word trigger | "The very first response should already reflect online/offline status" — doesn't wait for the 30-second cycle; the first response uses freshly-probed state |
| **Background watchdog** | Background probe every 30 seconds | Keeps monitoring connectivity changes even when the user isn't speaking |
| **NetworkOnMainThreadException fix** | Probe moved to a background thread | Early versions ran the probe on the main thread, which threw immediately and made probing permanently fail |
| **Build re-entrancy lock** | Only one `initGrammar` call in flight at a time | During flapping connectivity, high-frequency auto-switching would repeatedly tear down and rebuild the grammar engine — resulting in total unresponsiveness (an actual incident, now fixed) |

---

## 5. Chat UI Display Filtering

**Problem**: raw offline grammar payloads (`grammar_result`) mix in large amounts of technical content (`grammar init id=call`, `[ACTION WELCOME] 動作ID:1509…`, JSON fragments). Displaying this directly in chat bubbles produces output too noisy to read as a normal conversation.

**Fix direction**: the chat UI only shows clean recognized text (Chinese/English). The same filtering logic applies to both cloud dictation (`asr_result`) and offline grammar (`grammar_result`) events — technical metadata stays in the raw WebSocket log/debug console and never reaches the chat bubbles.

> This fix went through one regression incident where offline dialogue disappeared entirely and the robot stopped responding. The root cause was an interaction between the grammar re-entrancy lock and the chat-filter change — it was ultimately diagnosed by attaching a live WebSocket listener and comparing against the actual raw payload (see Section 7).

---

## 6. English Offline Recognition — Full Experimental Record and Conclusions

This section went through more than a dozen rounds of live testing. The conclusion is clear, but the process is worth recording in full to avoid repeating dead ends in the future.

### 6.1 Approaches Confirmed Not to Work

| Attempt | Result | Reason |
|---|---|---|
| Multi-word English phrase in BNF (`how old`) | ❌ 23300 | This BNF dialect disallows any spaces |
| Wrapping the phrase in quotes | ❌ not even a response | Parser hangs entirely |
| Rule chaining (`<a><b>` without a space) | ❌ 23300 | The engine doesn't support rule chaining at all, even in Chinese — not a language issue |
| Hyphenated form (`i-am-a-boy`) | ⚠️ builds successfully (errorCode=0) but no recognition response | Parser accepts the syntax, but the acoustic model doesn't recognize the sounds at all |
| Searching GitHub for other people's English BNF solutions | ❌ no working method found | iFlytek's official documentation settles it: offline command-word recognition only supports Mandarin |

### 6.2 Unexpected Finding: Single Words Sometimes Recognized

Testing found that saying "**hello**" offline was **actually recognized** (as a single word, not a phrase), and remained reliably recognized even after a reboot. This proves `common.jet` (the Chinese acoustic model) is not completely blind to English audio — certain English word phoneme combinations happen to align with valid Mandarin phoneme paths. This is "works by coincidence," not official support.

Based on this finding, the English word vocabulary was progressively expanded:

| Phase | Word count | Notes |
|---|---|---|
| First batch | 18 words | dance / hello / happy etc.; action words mapped to real actions, QA words to fixed answers |
| Second batch | +6 words | happy / upset / dinner / welcome / car / food |
| Multi-answer pass | 24 words, 4 randomized answers each | Originally each word had 1 fixed answer, causing repetition after 3 tries; matched the convention already used in the Chinese library |
| Large-scale expansion | **3,000 common English words** (top 3,000 from the google-10000-english wordlist) | See 6.3 |

**Known limitation (architectural, not a bug):** since the grammar only contains the isolated word "happy," a full sentence like "are you happy" will not be matched as a whole — at best, the matcher's fuzzy-matching logic (input contains a known word) might catch the individual word.

### 6.3 3,000-word Large-scale Expansion (most recent round — testing incomplete)

**Process:**
1. Took the top 3,000 words from the GitHub `google-10000-english` wordlist
2. Generation script: 5 randomized answers plus an action mapping per word
3. Filtered adult/profane words (`sex`, `porn`, `fuck`, etc. — inappropriate for a children's robot), including catching a missed instance of `ass` afterward
4. Final English library: **4,207 total entries** (including action mappings and multiple answers)
5. Sent to the device for a build test: **large-grammar build succeeded, errorCode=0** (3,000 English words + the existing 1,211 Chinese sentences, 4,211 sentences total)

**⚠️ Unfinished item**: after this successful build, only 5 words (apple / water / music / computer / happy) were queued for a preliminary recognition-accuracy test, and **the session ended before those results came back**. The actual offline recognition accuracy of the 3,000-word set remains unknown. The official documentation already indicates this batch is "hello-level luck" at best — being structurally acceptable does not mean it is phonetically reliable. **This test should be completed before deciding whether the 3,000-word set is worth keeping** — an oversized grammar could also slow down build time and increase the chance of semantic collisions.

### 6.4 Rejected Approach: APK Asset Swap Surgery (dangerous, do not retry)

The `alpha2services` APK on the device contains two acoustic models simultaneously:

```
assets/asr/common.jet      ← 6.8MB Chinese (actually used by the device)
assets/asr/common_en.jet   ← 14MB English (never loaded by the firmware, but genuinely present)
```

Decompilation confirmed both assets share **an identical internal container format** (same `v5pp` version, both embedding `grm.irf`), so in principle the engine could load either. A "model swap" scheme was designed: use the `jar` tool to overwrite the contents of `common.jet` with `common_en.jet`, enabling a one-tap Chinese/English mode switch (the device ships with root adb by default, this could work on any unmodified unit, and the swap is reversible).

**Result: complete failure.**

```
Failed to parse: Failed reading assets/asr/common.jet in StrictJarFile
Skipping PackageSetting com.ubtechinc.alpha2services due to missing metadata
```

Android 5.1's `StrictJarFile` outright rejects a zip entry rewritten by a general-purpose `jar` tool during the boot-time package scan (it requires a byte-exact local header, STORED alignment, and no data descriptor). This caused the entire `alpha2services` system service to be skipped and the control panel to lose all connectivity. The original APK was immediately reflashed, and after a reboot the service was fully restored to normal.

**Conclusion: this path is dead.** Achieving byte-exact compliance with the old zip validator would require writing a custom low-level zip tool from scratch. Even then, every switch would require a USB connection, there is still a risk of the system skipping the package on boot, and swapping in the English model disables Chinese offline recognition entirely. "Works on any unmodified device out of the box" and "offline English sentence recognition" are mutually exclusive without bundling a third-party engine.

### 6.5 Nuance English Engine — an Overlooked Existing Option

Decompilation confirmed that **Nuance VoCon is itself already a fully offline engine, and it is already running**, requiring no additional setup:

| Nuance component | Status |
|---|---|
| VoCon offline recognition engine | ✅ built into `alpha2services` native code, fully offline |
| Built-in English grammar (hardcoded) | ✅ action commands (e.g. "wave the left hand") + a set of fixed QA pairs |
| Custom vocabulary (`initGrammar`) | ❌ empty stub, vocabulary can never be added |
| Cloud dictation | ☠️ servers permanently offline; results below a confidence score of roughly 4500 fail with no fallback |

**How to use it**: switch the engine to Nuance in the panel (`set_language=en_us`) → say the wake word → speak one of the device's built-in English commands, clearly and at close range.

**Comparison against the iFlytek BNF approach:**

| | iFlytek BNF (Chinese, primary) | Nuance built-in (English) |
|---|---|---|
| Vocabulary | 1,211 custom sentences, extensible | Fixed small set, cannot be extended |
| Conversational richness | ✅ full semantic library | Limited QA |
| Offline | ✅ | ✅ |

Nuance and iFlytek coexist without conflict — this is an "additional capability" rather than a replacement. Basic English action commands were already available before the 3,000-word iFlytek experiment; that experiment adds nothing that wasn't already possible via Nuance for simple commands.

### 6.6 If True English Offline Sentence Recognition Is Needed in the Future

All the attempts above prove that, without bundling a third-party engine, the iFlytek engine cannot achieve genuine English offline sentence recognition. If this is ever needed (beyond isolated words), the only viable path is to bundle an independent offline engine (such as Vosk or PocketSphinx, with an en-US small model of roughly 40MB) directly inside the Open Alpha2 APK itself. This would work on any device the app is installed on, without relying on firmware-side resources. This route is a moderate amount of engineering work, but is technically fully controllable and not subject to the official language restriction.

---

## 7. Known Incidents and Root Causes

| Incident | Root Cause | Fix |
|---|---|---|
| Grammar build kept failing with 23300 | Removing the multiplication-table entries accidentally deleted a rule's trailing `;`, shifting subsequent rule boundaries and producing entries like `laugh:大笑` (missing header) and a sentence with an embedded semicolon | Re-extracted cleanly from the factory APK; added a filter that skips any alternative containing `:` or `;` at generation time |
| Static grammar file still failed with 23300 after regeneration | Binary-search isolation revealed the true culprit: an **unused `!slot` declaration** | Removed the unused slot; also confirmed CRLF line endings were not the cause |
| Chat UI showing large amounts of raw technical code | Raw `grammar_result` payload was displayed directly without filtering | Added clean-text filtering, applied to both `asr_result` and `grammar_result` |
| Offline dialogue disappeared entirely after the filter fix | During flapping connectivity, every auto-switch flip destroyed and rebuilt the ASR grammar engine, so it was constantly torn down and reconstructed | Added a build re-entrancy lock ensuring only one `initGrammar` call is in flight at a time |
| WebSocket log showed `grammar_result` but the chat UI showed nothing | The offline grammar result's text was buried in `ws[].cw[].w`, not the top-level `text` field — a different structure from cloud dictation | Attached a live WebSocket listener to capture the actual payload, then parsed the correct field specifically |
| PowerShell-generated English vocabulary JSON became corrupted; entries like `dance` disappeared | PowerShell 5.1's `ConvertTo-Json` wrapped the array in an extra `{"value":[...]}` layer that the matcher couldn't parse | Switched to hand-written JSON serialization, bypassing PowerShell's native serializer quirks |
| Simplified→Traditional conversion table only captured 200 of 1,200 character pairs | The extraction regex only matched the first segment of a Java string built with `+` concatenation | Re-extracted the full table (2,712 character pairs) using precise anchors |
| Conversion table was complete but conversion still had no effect | A debug function's return value was polluted by a stray `Write-Output` — a PowerShell pipeline quirk | Fixed the function's return logic |
| APK asset-swap surgery caused `alpha2services` to disappear and the panel to return 502 | Android 5.1's `StrictJarFile` rejected a zip entry rewritten by a general-purpose `jar` tool | Immediately reflashed the original APK; this approach was ruled out entirely (see Section 6.4) |

---

## 8. Error Code Quick Reference

| Code | Meaning | Common Causes |
|---|---|---|
| **23300** | Local engine error (build failed) | BNF syntax issues: spaces, rule chaining, unused slots, sentences containing `:`/`;`, corrupted rule boundaries |
| **20002** | Network connection timeout | Cloud dictation attempt failed to connect (common in "WiFi connected but no real data" states) |
| **10114** | Free dictation unavailable offline | Confirmed: grammar-constrained recognition can be fully offline; free dictation always requires the cloud |

---

## 9. Key Functions/Components Reference

| Function | Location | Notes |
|---|---|---|
| Build grammar | `robot.speech_initGrammar(bnf, listener)` | Maps to the AIDL `Alpha2SpeechMainServiceUtil` |
| Build callback | `ISpeechGrammarInitListener.speechGrammarInitCallback(grammarId, errorCode)` | Only `errorCode==0` allows recognition to start |
| Start offline recognition | `robot.speech_startGrammar(listener)` | Requires a successful build first |
| Offline result parsing | `extractGrammarResultText()` | Parses either `{"text":..}` or the `ws[].cw[].w` format |
| Language switch | `speech/set_language?lang=zh_cn` / `en_us` | Maps to `speech_setRecognizedLanguage()` |
| Auto-switch core | `MainActivity.applyConnectivityMode(boolean, String)` | See Section 4 |
| Network probing | `hasRealInternet()` | TCP connect to `ubtek.openspeech.cn:80` etc., not a ping to 8.8.8.8 |
| Semantic matching (CN) | `IflytekSemanticMatcher.match(text)` | exact match → question phrase contains input → input contains question phrase (tolerates ASR dropped characters) → fallback |
| Semantic matching (EN) | `IflytekSemanticMatcherEn.match(text)` | Same logic, but effectively only single-word matches are reachable offline |

---

## 10. Final Status Summary (as of this document's writing)

```
User speech
    ▼
5-mic array wake (IVW)
    ▼
Live network probe (TCP connect to ubtek.openspeech.cn / openspeech.cn)
    │
    ├─ Online → iFlytek cloud dictation (any Chinese sentence) → semantic match → TTS + action
    │
    └─ Offline → local BNF grammar recognition
              │
              ├─ Chinese: 1,211 custom sentences, semantic-library driven, errorCode=0 verified stable
              │
              └─ English: 24 words verified reliable (4 randomized answers each)
                          + 3,000-word expansion built successfully (errorCode=0),
                            but actual offline recognition accuracy **has not yet been tested**
```

**Fully resolved:**
- ✅ Offline Chinese grammar recognition, 1,211 sentences running stably, auto-generated/de-duplicated/converted to Traditional from the semantic library
- ✅ Automatic online/offline switching (real network probing + cooldown anti-flicker + probe-on-wake)
- ✅ Clean chat UI display (plain Chinese/English text only, no technical code)
- ✅ English offline word commands (24 words, actions + multiple randomized answers)

**Confirmed not feasible:**
- ❌ English offline sentence recognition (BNF space restriction + acoustic model has no English phonemes, confirmed by official documentation)
- ❌ APK asset-swap model replacement (the old `StrictJarFile` rejects rewritten zip entries)

**Outstanding:**
- ⏳ Actual recognition accuracy test for the 3,000-word English set (build succeeded, recognition test interrupted)
- ⏳ If genuine English offline conversation is required: bundling a third-party engine (Vosk / PocketSphinx) is needed — not yet started

---

> Document version: v2.0 (rewritten version, supersedes v1.0)
> Applicable firmware: Alpha2Services v1.1.7.3.20 / Open Alpha2 App
> Sources: logcat testing, smali decompilation (jadx/baksmali), comparison against the original factory APK
