// Open Alpha2 — Blockly 直譯執行引擎。
//
// 設計原則：
//  1. 唔用 Blockly 內建 code generator 生成一段 JS 再 eval — 改用「行樹」直譯
//     (interpretBlock), 咁樣可以喺 wait/repeat 中途逐格 highlight, 亦可以隨時
//     `running = false` 安全中斷, 唔會有半行 JS 卡死喺 eval 入面嘅問題。
//  2. 每個「動作類」block (播放動作/TTS/LED/伺服...) 對應現有已驗證嘅 /api/* 端點,
//     直接 fetch, 唔重新定義呢層 API — 呼叫嘅係 index.html 已經有嘅 api() helper
//     (由 app-core.js 提供), 保證同「面板」分頁行為完全一致。
//  3. 事件 block (alpha_event_accel_threshold / alpha_event_sonar_triggered) 唔喺
//     主程式流程之內執行, 而係喺 workspace load 嗰陣就註冊做 WebSocket listener,
//     常駐監聽 — 呢個係事件驅動模型, 同「按 ▶ 執行」嗰個線性 program 係兩回事,
//     可以同時存在。

(function () {

  // ------------------------------------------------------------------
  // 執行狀態
  // ------------------------------------------------------------------
  let running = false;
  let stopRequested = false;
  let workspace = null;
  const variables = new Map(); // 變數名稱 -> 值 (直譯器自己嘅 scope, 唔用 Blockly 內建 code-gen 嘅變數系統)
  const accelHandlers = []; // { axis, cmp, threshold, varName, bodyBlock, hatBlock }
  const sonarHandlers = []; // { varName, bodyBlock, hatBlock, wasTriggered }
  const pirHandlers = []; // { varName, bodyBlock, hatBlock, wasTriggered } — 同 sonarHandlers 一樣嘅邊緣觸發形狀

  function logLine(text, cls) {
    const out = document.getElementById('runLog');
    if (!out) return;
    const div = document.createElement('div');
    div.className = 'run-log-line' + (cls ? ' ' + cls : '');
    const time = new Date().toLocaleTimeString('zh-HK', { hour12: false });
    div.textContent = '[' + time + '] ' + text;
    out.appendChild(div);
    if (document.getElementById('runAutoScroll').checked) {
      out.scrollTop = out.scrollHeight;
    }
  }

  function setRunningUI(isRunning) {
    document.getElementById('runBtn').disabled = isRunning;
    document.getElementById('stopBtn').disabled = !isRunning;
    document.getElementById('runStatusDot').className = 'run-status-dot ' + (isRunning ? 'running' : 'idle');
    document.getElementById('runStatusText').textContent = isRunning ? t('run_status_running') : t('run_status_idle');
  }

  function sleep(ms) {
    return new Promise(function (resolve) {
      // 用短間隔輪詢 stopRequested, 咁樣「停止」掣先可以喺 wait 中途即時生效,
      // 而唔使等成個 setTimeout 完先檢查。
      const step = 50;
      let elapsed = 0;
      const timer = setInterval(function () {
        elapsed += step;
        if (stopRequested || elapsed >= ms) {
          clearInterval(timer);
          resolve();
        }
      }, step);
    });
  }

  function highlight(blockId) {
    if (workspace && blockId) {
      workspace.highlightBlock(blockId);
    }
  }

  // ------------------------------------------------------------------
  // Backend adapter：Alpha2 同 Lynx 兩個 backend 嘅 /api/* endpoint 名/參數形狀
  // 好多時唔一樣 (例如 Alpha2 嘅 servo/one 對應 Lynx 嘅 motor/move_absolute,
  // 參數名都唔同), 但兩邊 block 定義 (blockly-blocks.js) 同 toolbox 係共用一份,
  // 唔想因為 backend 唔同而要起兩份幾乎一樣嘅 block。所以喺呢一層執行引擎度,
  // 按 window.BLOCKLY_BACKEND (由 blockly-page.js 提供, 見該檔開頭註解) 分支,
  // 將同一粒 block 翻譯做啱嗰個 backend 嘅真正 API 呼叫。
  //
  // isLynx() 淨係讀 window.BLOCKLY_BACKEND, 唔喺呢個檔案自己再存一份 —— 保證
  // 用家用 header 個 Alpha2/Lynx 掣切換 (會刷新頁面) 之後, 呢層一定同步。
  function isLynx() {
    return window.BLOCKLY_BACKEND === 'lynx';
  }

  // 部分 block 喺 Lynx 完全冇對應 AIDL 功能 (例如電話/通知鈴聲、聲控喚醒麥克風
  // 擁有權切換、超聲波感應 sonar —— Lynx 用 PIR 人體感應取代 sonar, 唔係同一種
  // 硬件), 呢種情況唔應該砌一個假 endpoint 送出去等佢 404, 而係喺執行紀錄度
  // 清楚寫低「呢粒 block 喺 Lynx 唔支援」, 然後當呢個 statement 冇做過, continue
  // 落去下一粒 block, 等用家自己決定係咪要拎走呢粒積木。
  function logUnsupportedOnLynx(blockDescriptionZh) {
    logLine(t('run_unsupported_on_lynx', { block: blockDescriptionZh }), 'warn');
  }

  // logUnsupportedOnLynx() 嘅鏡像 —— PIR 感應器係 Lynx 專屬硬件 (機身廣播,
  // 唔經 AIDL), Alpha2 完全冇對應概念, 所以要有反過來嘅一個版本, 用喺
  // alpha_sensor_pir_toggle/alpha_event_pir_triggered 呢類淨係 Lynx 先用得嘅
  // block, 喺 Alpha2 backend 執行時清楚話俾用家知。
  function logUnsupportedOnAlpha2(blockDescriptionZh) {
    logLine(t('run_unsupported_on_alpha2', { block: blockDescriptionZh }), 'warn');
  }

  // ---- 動作播放：兩邊 action/list 嘅 JSON 形狀 (id/type/nameCn/nameEn) 完全一致
  // (LynxController#actionList() 刻意跟 Alpha2 對齊, 見 java 註解), action/play
  // 同 action/stop 嘅參數/回應形狀都一樣, 淨係 window.api() 果層 prefix 分別
  // 已經夠, 呢類 block 唔使特別映射。

  // ---- 伺服 / 舵機 ----
  // Alpha2: servo/one { id, angle, time }, servo/all { angles: "a,b,c,..", time }
  // Lynx:   motor/move_absolute { id, angle, time } (完全同名同形狀, 直接轉 prefix
  //         就得), 但冇「set_all CSV」嘅一次過寫法喺呢個 block 用到嘅 alpha_servo_all
  //         /alpha_servo_home 度 (Lynx 個 motor/set_all 係 "id:angle,id:angle" 格式,
  //         唔係 Alpha2 嗰種按位置排嘅 "a,b,c" CSV) —— 呢度逐個 motor 拆開來,
  //         用 motor/move_absolute 逐隻叫, 保證兩邊行為 (每隻馬達用同一個 time
  //         一齊擺去新角度) 睇落一致, 唔使用家理解多一種 CSV 格式。
  async function servoOneAdapter(id, angle, time) {
    if (isLynx()) {
      await window.api('motor/move_absolute', { id: id, angle: angle, time: time });
    } else {
      await window.api('servo/one', { id: id, angle: angle, time: time });
    }
  }
  async function servoAllAdapter(angles, time) {
    // angles: 長度 20 嘅 number 陣列, index 0 對應 servo id 1。
    if (isLynx()) {
      for (let i = 0; i < angles.length && !stopRequested; i++) {
        await window.api('motor/move_absolute', { id: i + 1, angle: angles[i], time: time });
      }
    } else {
      await window.api('servo/all', { angles: angles.join(','), time: time });
    }
  }

  // ---- LED ----
  // Alpha2 嘅 led/head/set 同 led/eye/set 用「preset (long/flash/breathe/chase/dual/
  // stop) + color + brightness」一個 endpoint 包晒所有效果; Lynx 冇呢種 preset
  // 包裝, 對應嘅係逐個效果一個 endpoint (led/head/on、led/head/flash、
  // led/head/breath、led/head/off, 冇 chase/dual 呢兩種)。顏色代碼 (1紅 2綠 3藍
  // 4黃 5紫 6青 7白) 兩邊一致 (見 LedColor.java 註解), 唔使remap。
  //
  // p2 (speed)/p3 (hold) 呢兩個 flash/breath 專用參數, Alpha2 個 preset 冇獨立
  // 開俾用家調, 一律用 blockly-blocks.js 現有嘅 BRIGHT 欄位當 brightness、
  // 用一個保守嘅預設 speed/hold 頂住 (同 app-lynx.js 手動面板嘅預設一致,
  // 見 LYNX_LED_HOLD 常數)。
  const LYNX_LED_DEFAULT_SPEED = 500;
  const LYNX_LED_HOLD = 2147483647; // Integer.MAX_VALUE，同 app-lynx.js 一致：持續到手動關為止

  async function ledHeadAdapter(preset, color, brightness) {
    if (!isLynx()) {
      const params = { preset: preset };
      if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
      await window.api('led/head/set', params);
      return;
    }
    switch (preset) {
      case 'stop':
        await window.api('led/head/off');
        return;
      case 'flash':
        await window.api('led/head/flash', { p0: color, p1: brightness, p2: LYNX_LED_DEFAULT_SPEED, p3: LYNX_LED_HOLD });
        return;
      case 'breathe':
        await window.api('led/head/breath', { p0: color, p1: brightness, p2: LYNX_LED_DEFAULT_SPEED, p3: LYNX_LED_HOLD });
        return;
      case 'chase':
      case 'dual':
        // Lynx 冇呢兩款效果對應 —— 降級做 solid-on, 好過完全冇反應。
        logUnsupportedOnLynx(t('run_led_preset_name_' + preset));
        await window.api('led/head/on', { p0: color, p1: brightness });
        return;
      case 'long':
      default:
        await window.api('led/head/on', { p0: color, p1: brightness });
        return;
    }
  }
  async function ledEyeAdapter(preset, color, brightness) {
    if (!isLynx()) {
      const params = { preset: preset };
      if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
      await window.api('led/eye/set', params);
      return;
    }
    switch (preset) {
      case 'stop':
        await window.api('led/eye/off');
        return;
      case 'flash':
        await window.api('led/eye/flash', { p0: color, p1: brightness, p2: LYNX_LED_DEFAULT_SPEED, p3: LYNX_LED_HOLD });
        return;
      case 'chase':
        await window.api('led/eye/marquee', { p0: color, p1: brightness, p2: LYNX_LED_DEFAULT_SPEED, p3: LYNX_LED_HOLD });
        return;
      case 'dual':
      case 'breathe':
        // Lynx 眼部冇 breathe/dual 對應效果 —— 降級做 solid-on。
        logUnsupportedOnLynx(t('run_led_preset_name_' + preset));
        await window.api('led/eye/on', { color: color });
        return;
      case 'long':
      default:
        await window.api('led/eye/on', { color: color });
        return;
    }
  }
  // 嘴部 LED：Alpha2 淨係得「off / breathe(speed)」兩態 (冇 solid-on), Lynx 個
  // led/mouth/on 都係得 p0=brightness 一個參數, 冚唔到 breathe 嘅 speed 語意 ——
  // 用 led/mouth/breath 先啱 (p0 brightness/p1 speed/p2 hold)。
  async function ledMouthAdapter(mode, speed) {
    if (!isLynx()) {
      if (mode === 'off') await window.api('led/mouth/set', { preset: 'off' });
      else await window.api('led/mouth/set', { speed: speed });
      return;
    }
    if (mode === 'off') {
      await window.api('led/mouth/off');
    } else {
      await window.api('led/mouth/breath', { p0: LYNX_LED_DEFAULT_SPEED, p1: speed, p2: LYNX_LED_HOLD });
    }
  }

  // ---- 語音 TTS ----
  // Alpha2 嘅 speech/tts 支援揀引擎 (engine: android/nuance/iflytek) + voice；
  // Lynx 淨係得 Android 內建 TTS 一條路 (見 LynxController java 註解), 參數係
  // { text, lang } (BCP-47 tag, 例如 "zh-HK"), 冇 engine/voice 呢兩個概念。
  // Alpha_speech_tts block 嘅 ENGINE/VOICE field 喺 Lynx 度冧唔到 —— 保留
  // text 照送, engine/voice 揀咗嘅話喺紀錄度提一提用家呢兩個字段喺 Lynx 冇效果。
  async function speechTtsAdapter(text, engine, voice) {
    if (!isLynx()) {
      const params = { text: text, engine: engine };
      if (voice) params.voice = voice;
      await window.api('speech/tts', params);
      return;
    }
    if (engine || voice) {
      logLine(t('run_tts_lynx_engine_ignored'), 'warn');
    }
    await window.api('speech/tts', { text: text });
  }

  // ------------------------------------------------------------------
  // 值運算：攞一個 value-input block 嘅結果 (數字/文字/布林/物件皆可)
  // ------------------------------------------------------------------
  async function evalValue(block) {
    if (!block) return null;
    switch (block.type) {
      case 'math_number': return Number(block.getFieldValue('NUM'));
      case 'text': return block.getFieldValue('TEXT');
      case 'logic_boolean': return block.getFieldValue('BOOL') === 'TRUE';
      case 'logic_null': return null;
      case 'logic_negate': return !(await evalValue(block.getInputTargetBlock('BOOL')));
      case 'logic_compare': {
        const a = await evalValue(block.getInputTargetBlock('A'));
        const b = await evalValue(block.getInputTargetBlock('B'));
        const op = block.getFieldValue('OP');
        switch (op) {
          case 'EQ': return a == b;
          case 'NEQ': return a != b;
          case 'LT': return a < b;
          case 'LTE': return a <= b;
          case 'GT': return a > b;
          case 'GTE': return a >= b;
        }
        return false;
      }
      case 'logic_operation': {
        const a = await evalValue(block.getInputTargetBlock('A'));
        const op = block.getFieldValue('OP');
        if (op === 'AND') return a && (await evalValue(block.getInputTargetBlock('B')));
        return a || (await evalValue(block.getInputTargetBlock('B')));
      }
      case 'logic_ternary': {
        const cond = await evalValue(block.getInputTargetBlock('IF'));
        return cond ? await evalValue(block.getInputTargetBlock('THEN')) : await evalValue(block.getInputTargetBlock('ELSE'));
      }
      case 'math_arithmetic': {
        const a = Number(await evalValue(block.getInputTargetBlock('A')));
        const b = Number(await evalValue(block.getInputTargetBlock('B')));
        switch (block.getFieldValue('OP')) {
          case 'ADD': return a + b;
          case 'MINUS': return a - b;
          case 'MULTIPLY': return a * b;
          case 'DIVIDE': return a / b;
          case 'POWER': return Math.pow(a, b);
        }
        return NaN;
      }
      case 'math_single': {
        const a = Number(await evalValue(block.getInputTargetBlock('NUM')));
        switch (block.getFieldValue('OP')) {
          case 'ROOT': return Math.sqrt(a);
          case 'ABS': return Math.abs(a);
          case 'NEG': return -a;
          case 'LN': return Math.log(a);
          case 'LOG10': return Math.log10(a);
          case 'EXP': return Math.exp(a);
          case 'POW10': return Math.pow(10, a);
        }
        return NaN;
      }
      case 'math_round': {
        const a = Number(await evalValue(block.getInputTargetBlock('NUM')));
        switch (block.getFieldValue('OP')) {
          case 'ROUND': return Math.round(a);
          case 'ROUNDUP': return Math.ceil(a);
          case 'ROUNDDOWN': return Math.floor(a);
        }
        return a;
      }
      case 'math_modulo': {
        const a = Number(await evalValue(block.getInputTargetBlock('DIVIDEND')));
        const b = Number(await evalValue(block.getInputTargetBlock('DIVISOR')));
        return a % b;
      }
      case 'math_random_int': {
        const from = Math.round(Number(await evalValue(block.getInputTargetBlock('FROM'))));
        const to = Math.round(Number(await evalValue(block.getInputTargetBlock('TO'))));
        const lo = Math.min(from, to), hi = Math.max(from, to);
        return lo + Math.floor(Math.random() * (hi - lo + 1));
      }
      case 'text_join': {
        let result = '';
        const count = block.itemCount_ !== undefined ? block.itemCount_ : 2;
        for (let i = 0; i < count; i++) {
          const v = await evalValue(block.getInputTargetBlock('ADD' + i));
          result += (v === null || v === undefined) ? '' : String(v);
        }
        return result;
      }
      case 'text_length': {
        const v = await evalValue(block.getInputTargetBlock('VALUE'));
        return v ? String(v).length : 0;
      }
      case 'text_isEmpty': {
        const v = await evalValue(block.getInputTargetBlock('VALUE'));
        return !v || String(v).length === 0;
      }
      case 'text_indexOf': {
        const haystack = String(await evalValue(block.getInputTargetBlock('VALUE')) || '');
        const needle = String(await evalValue(block.getInputTargetBlock('FIND')) || '');
        const idx = block.getFieldValue('END') === 'FIRST' ? haystack.indexOf(needle) : haystack.lastIndexOf(needle);
        return idx + 1; // Blockly 文字積木用 1-based index
      }
      case 'text_charAt': {
        const v = String(await evalValue(block.getInputTargetBlock('VALUE')) || '');
        const where = block.getFieldValue('WHERE') || 'FROM_START';
        if (where === 'FIRST') return v.charAt(0);
        if (where === 'LAST') return v.charAt(v.length - 1);
        if (where === 'RANDOM') return v.charAt(Math.floor(Math.random() * v.length));
        const at = Math.round(Number(await evalValue(block.getInputTargetBlock('AT')) || 1));
        return where === 'FROM_END' ? v.charAt(v.length - at) : v.charAt(at - 1);
      }
      case 'variables_get':
        return variables.has(block.getFieldValue('VAR')) ? variables.get(block.getFieldValue('VAR')) : null;
      case 'alpha_servo_all_helper': {
        const vals = [];
        for (let i = 1; i <= 20; i++) {
          const v = await evalValue(block.getInputTargetBlock('A' + i));
          vals.push(v === null || v === undefined || v === '' ? 120 : Math.round(Number(v)));
        }
        return vals.join(',');
      }
      default:
        // procedures_callreturn 同其他未特別支援嘅 value block: 嘗試用變數 getter 邏輯行為
        if (block.type === 'variables_get_reporter') {
          return variables.has(block.getFieldValue('VAR')) ? variables.get(block.getFieldValue('VAR')) : null;
        }
        logLine(t('run_unsupported_value_block', { type: block.type }), 'warn');
        return null;
    }
  }

  // ------------------------------------------------------------------
  // 播放動作 + (可選) 等待完成。
  //
  // 關鍵次序: 一定要「先掛好 action_stop 嘅 listener, 先至真正送出 /api/action/play」。
  // 如果反過來 (先送 API 先掛 listener), 遇到一個好快播完嘅動作 (甚至比 HTTP round-trip
  // 仲快), 個 action_stop event 可能喺 listener 掛好之前就已經到咗 WebSocket, 咁就會
  // 執漏, 卡到個 timeout 先放行 —— 呢個係經典嘅 setup-before-fire race condition。
  //
  // 另外用 evt.data.name 同送出嘅 name 做精準匹配, 而唔係「隨便收到一個 action_stop
  // 就當自己嗰個播完」—— 如果程式入面有第二條並行嘅事件驅動 block 喺呢段時間都觸發咗
  // 另一個動作, 盲目匹配就會提早誤判「完成」。
  //
  // 2026-08 bugfix (用家回報「整動作會 crash」): 「等待完成」揀「唔等」嗰個分支
  // 之前完全冇任何序列化 —— 一路排落嚟嘅 alpha_action_play block (或者 loop 入面
  // 逐格都揀「唔等」), 個個都係「送出去就即刻放行落下一格」, 令幾個 action/play
  // request 可以喺幾 ms 之內連環送到 LynxController#handle()。而
  // LynxRobotApi#action_playAction() 入面嘅 svc.playAction(...) 係一個會真正
  // block 住等 UBTECH 機身側 IActionService 回應嘅 AIDL Binder call —— 呢個
  // service 底層對應嘅係實體馬達, 冇可能同一時間執行多過一個動作。用 logcat 追查
  // 過一次真實個案 (見對話紀錄): 6 個 action/play request 喺 15ms 內連環送到,
  // 之後成個 app 嘅 HttpServer 就再冇任何回應 log, 一直到用家自己強制關 app 為止
  // —— 即係其中一個 svc.playAction() 卡死咗冇返, 掗住咗個共用嘅 Binder thread,
  // 連累埋之後所有 AIDL 呼叫 (唔止 action, 連 servo/LED 都一齊唔郁得), 用家會
  // 睇落好似「成個 app 死咗」。
  //
  // 修法: 唔理 WAIT 揀「等」定「唔等」, 一律用呢個 module-level 嘅
  // actionBusyPromise 做序列化閘 —— 保證同一時間淨係得一個 action/play 在途。
  //
  // 2026-08 第二次 bugfix (見對話紀錄嘅 logcat: 兩個 action/play 連環撞到
  // server 個 "API_ERROR_BUSY"): 呢個閘原本嘅設計淨係等到 HTTP round-trip
  // 本身返嚟就放行 (「唔等」個 block 唔應該等成個動作播完先郁落一格, 呢個
  // 諗法本身冇錯)。但配合 LynxController.java 個 actionInFlight guard (見
  // 該檔案嘅大段註解) 之後, server 端嘅「busy」定義已經變咗做「呢個動作真正
  // 播緊, 直到 onStopActionResult() 先解鎖」, 同呢度「HTTP 一 round-trip
  // 完就當唔 busy」唔一致 —— 結果就係第二個 action/play 嘅 HTTP request 一早
  // 過咗第一個嘅 HTTP round-trip, 但機身實際仲喺度播緊第一個動作, server
  // 就會拒絕。
  //
  // 而家改做: client 端嘅「busy」都要對齊做「等到呢個動作真正完成 (收到
  // action_stop event) 先算」—— 唔理個 block 本身係咪揀咗「唔等」, gate 內部
  // 都會靜靜地等 action_stop (或者一個保守嘅 safety timeout, 見下面), 先至
  // 放行俾下一個排隊嘅 action/play 送出。呢個唔改變「唔等」個 block 本身嘅
  // 用家可見行為 (playActionAndMaybeWait 嘅 !wait 分支依然係送咗就即刻
  // return, 唔會令使用者要多等), 淨係令幾個 action/play 之間唔會再打交。
  let actionBusyPromise = Promise.resolve();

  // 保守嘅 safety timeout —— 如果 action_stop event 因為某啲原因冚唔到 (例如
  // WebSocket 斷咗、或者呢個動作嘅 callback 本身有 bug 冇 fire), gate 都唔可以
  // 永久卡死, 否則之後成個程式所有 action/play 都會停晒。20 秒已經比
  // playActionAndMaybeWait() 自己個 15 秒 timeout 仲長, 保證真正需要嘅等待
  // 一定會由嗰邊先放行, 呢個純粹係最後一道保險。
  const ACTION_GATE_SAFETY_TIMEOUT_MS = 20000;

  function waitForActionStopOrTimeout() {
    return new Promise(function (resolve) {
      const onEvt = function (evt) {
        if (evt.type === 'action_stop') { cleanup(); resolve(); }
      };
      const timer = setTimeout(function () { cleanup(); resolve(); }, ACTION_GATE_SAFETY_TIMEOUT_MS);
      function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
      window.__alphaOnEvent(onEvt);
    });
  }

  function sendActionPlay(name) {
    const gated = actionBusyPromise.then(function () {
      return window.api('action/play', { name: name });
    });
    // 下一個排隊嘅 action/play 要等「呢個動作真正播完」先可以送出, 唔係淨係
    // 等 HTTP round-trip ——見上面大段註解。無論今次 API 呼叫成功/失敗/收到
    // action_stop/等到 timeout, 都一定要放行 (.catch 吞晒錯誤), 否則一次
    // 失敗就會永久卡死成條隊。
    actionBusyPromise = gated
      .then(function (r) {
        // API 本身都送唔出 (network fail 或者 server 話 busy) 就冇必要再等
        // action_stop, 佢根本唔會嚟 —— 即刻放行, 等下一個 block 有機會送出
        // (可能上一個「busy」其實係一場誤會, 例如網絡短暫唔穩)。
        if (!r || !r.ok) return;
        return waitForActionStopOrTimeout();
      })
      .catch(function () { /* 見上面註解: 吞錯誤, 淨係用嚟放行 */ });
    return gated;
  }

  async function playActionAndMaybeWait(name, wait, timeoutSeconds) {
    if (!wait) {
      logLine(t('run_action_play_nowait', { name: name }));
      await sendActionPlay(name);
      return;
    }
    logLine(t('run_action_play_wait', { name: name, timeout: timeoutSeconds }));
    const donePromise = new Promise(function (resolve) {
      const onEvt = function (evt) {
        if (evt.type === 'action_stop' && (!evt.data || evt.data.name === name || !evt.data.name)) {
          cleanup(); resolve('done');
        }
      };
      const timer = setTimeout(function () { cleanup(); resolve('timeout'); }, timeoutSeconds * 1000);
      function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
      window.__alphaOnEvent(onEvt);
      // listener 掛好之後先送出真正嘅 API request。
      // .catch(...) 唔可以少: window.api() 內部理論上已經接住曬 fetch 嘅 network error,
      // 但為咗唔靠呢一層假設, 呢度都要有自己嘅 .catch, 否則萬一有意外拋出, 呢個
      // .then() 冇接住嘅 rejection 會逸出做 unhandled promise rejection, 喺頁面度
      // 彈紅色 error banner, 但個動作播放狀態 (donePromise) 就會卡住唔鬱 (冇 resolve)。
      sendActionPlay(name).then(function (r) {
        if (!r || !r.ok) { cleanup(); resolve('api_failed'); }
      }).catch(function (err) {
        cleanup();
        logLine(t('run_action_play_exception', { err: (err && err.message ? err.message : String(err)) }), 'err');
        resolve('api_failed');
      });
    });
    const result = await donePromise;
    if (result === 'timeout') logLine(t('run_action_wait_timeout', { name: name }), 'warn');
    else if (result === 'api_failed') logLine(t('run_action_api_failed', { name: name }), 'err');
    else logLine(t('run_action_play_done', { name: name }));
  }

  // ------------------------------------------------------------------
  // 語句執行：行一粒 statement block, 回傳「下一粒」由 caller (runSequence) 處理
  // ------------------------------------------------------------------
  async function execStatement(block) {
    highlight(block.id);
    const blockType = block.type;

    switch (blockType) {
      // ---------------- 動作 ----------------
      case 'alpha_action_play': {
        const name = block.getFieldValue('NAME');
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      // 2026-08 更新: alpha_action_play_builtin 拆咗做獨立 block (基本/跳舞
      // /故事/瑜伽/其他), 但每粒嘅 field 結構 (NAME/WAIT/TIMEOUT) 完全一樣,
      // 唔使分開寫 case, 一個 case 蓋晒所有 type 就夠。
      case 'alpha_action_play_basic':
      case 'alpha_action_play_dance':
      case 'alpha_action_play_story':
      case 'alpha_action_play_yoga':
      case 'alpha_action_play_others': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine(t('run_no_action_selected'), 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_play_dropdown': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine(t('run_no_action_selected_live'), 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_stop':
        logLine(t('run_action_stop'));
        await window.api('action/stop');
        return;
      case 'alpha_action_wait_done': {
        const timeout = Number(block.getFieldValue('TIMEOUT')) * 1000;
        logLine(t('run_action_wait_extra', { timeout: block.getFieldValue('TIMEOUT') }));
        await new Promise(function (resolve) {
          const onEvt = function (evt) {
            if (evt.type === 'action_stop') { cleanup(); resolve(); }
          };
          const timer = setTimeout(function () { cleanup(); resolve(); }, timeout);
          function cleanup() { clearTimeout(timer); window.__alphaOffEvent(onEvt); }
          window.__alphaOnEvent(onEvt);
        });
        if (stopRequested) return;
        return;
      }

      // ---------------- 語音 ----------------
      case 'alpha_speech_tts': {
        const text = await evalValue(block.getInputTargetBlock('TEXT'));
        const engine = block.getFieldValue('ENGINE');
        const voice = block.getFieldValue('VOICE');
        logLine(t('run_tts', { engine: engine, text: text }));
        await speechTtsAdapter(String(text == null ? '' : text), engine, voice);
        return;
      }
      case 'alpha_speech_stop':
        logLine(t('run_tts_stop'));
        await window.api('speech/stop');
        return;
      case 'alpha_speech_set_mic':
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_set_mic')); return; }
        logLine(t('run_mic_ownership', { owner: (block.getFieldValue('WAKE') === 'true' ? t('run_mic_owner_robot') : t('run_mic_owner_app')) }));
        await window.api('speech/set_mic', { wake: block.getFieldValue('WAKE') });
        return;
      case 'alpha_speech_start_asr':
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_start_asr')); return; }
        logLine(t('run_start_listening'));
        await window.api('speech/start_asr');
        return;
      case 'alpha_speech_set_voice':
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_set_voice')); return; }
        logLine(t('run_set_voice', { name: block.getFieldValue('NAME') }));
        await window.api('speech/set_voice', { name: block.getFieldValue('NAME') });
        return;
      case 'alpha_speech_set_language':
        if (isLynx()) {
          // Lynx 冇獨立「揀語言」endpoint —— 個語言係跟住 speech/tts 每次
          // call 自己帶嘅 lang 參數走 (見 speechTtsAdapter), 唔係一個要
          // 事先設定嘅全域狀態, 所以呢粒 block 喺 Lynx 度冧唔到亦唔需要。
          logUnsupportedOnLynx(t('run_block_name_set_language'));
          return;
        }
        logLine(t('run_set_lang', { lang: block.getFieldValue('LANG') }));
        await window.api('speech/set_language', { lang: block.getFieldValue('LANG') });
        return;
      case 'alpha_speech_self_interrupt':
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_self_interrupt')); return; }
        logLine(t('run_self_interrupt', { on: block.getFieldValue('ON') }));
        await window.api('speech/self_interrupt', { on: block.getFieldValue('ON') });
        return;
      // 2026-08 更新: 電話鈴聲 / 通知鈴聲已經拆做兩粒獨立 block (以前係一粒
      // alpha_speech_ringtone + TYPE dropdown), type 依家寫死喺呢兩個 case。
      // 播放依家送 title (唔再送 index), call 新增嘅 /api/audio/ringtones/play_by_title
      // (見 blockly-blocks.js makeRingtoneBlock() 註解: title 對應機制同相機分頁
      // 快門聲一樣穩陣, 唔使理 RingtoneManager cursor index 排序)。
      //
      // 2026-08 再更新: 加咗 DURATION 呢個 value input —— 之前個 block 播完成首
      // 系統鈴聲先返 (無得控制播幾耐, 亦都係之前「不斷重覆聽落唔停」問題嘅源頭
      // 之一), 而家播放之後, 若果 duration > 0, 就 sleep 夠鐘再主動 call
      // /api/audio/ringtones/stop 停佢 (跟 MainActivity.playRingtoneUri() 依家
      // 有 currentRingtonePlayer 呢個 field 追蹤緊播緊嗰個, stop 可以隨時安全咁
      // 打斷)。填 0 = 唔設自動停止時間, 播到成個音效檔案自然完為止 (舊行為)。
      // toolbox 入面電話鈴聲預設 10 秒, 通知鈴聲預設 5 秒。
      case 'alpha_speech_ringtone_phone':
      case 'alpha_speech_ringtone_notification': {
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_ringtone')); return; }
        const type = (block.type === 'alpha_speech_ringtone_notification') ? 'notification' : 'ringtone';
        const title = block.getFieldValue('TITLE');
        if (!title) {
          logLine(t('run_no_ringtone_selected'), 'warn');
          return;
        }
        const duration = Number(await evalValue(block.getInputTargetBlock('DURATION'))) || 0;
        logLine(t('run_ringtone_play', {
          type: (type === 'notification' ? t('run_ringtone_type_notification') : t('run_ringtone_type_phone')),
          title: title,
          durationNote: (duration > 0 ? t('run_ringtone_duration_note', { duration: duration }) : t('run_ringtone_duration_full')),
        }));
        await window.api('audio/ringtones/play_by_title', { type: type, title: title });
        if (duration > 0) {
          await sleep(duration * 1000);
          // 就算 stopRequested (用家撳咗「停止程式」), 都要停返個鈴聲,
          // 唔係佢會繼續喺機械人度播落去 (Java 個 MediaPlayer 唔會因為
          // 呢個網頁 loop 停咗就自動停)。
          await window.api('audio/ringtones/stop', {});
        }
        return;
      }
      case 'alpha_speech_ringtone_stop':
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_ringtone')); return; }
        logLine(t('run_ringtone_stop'));
        await window.api('audio/ringtones/stop', {});
        return;

      // ---------------- 伺服 ----------------
      // 2026-08 更新: alpha_servo_one 拆咗做 5 粒獨立 block (頭/右手/左手/右腳
      // /左腳), 但五粒嘅 field 結構 (ID/ANGLE/TIME) 完全一樣, 唔使分開寫 case,
      // 一個 case 蓋晒 5 個 type 就夠。
      case 'alpha_servo_one_head':
      case 'alpha_servo_one_right_arm':
      case 'alpha_servo_one_left_arm':
      case 'alpha_servo_one_right_leg':
      case 'alpha_servo_one_left_leg': {
        const id = block.getFieldValue('ID');
        let angle = Number(block.getFieldValue('ANGLE'));
        // 執行層再 clamp 多一次做保險 — field validator 已經喺 UI 層擋咗大部分
        // 情況, 但透過「匯入 .xml」載入嘅程式可能繞過咗 UI (XML 入面隨便打
        // 一個超出範圍嘅數值), 所以真正送出 API 之前一定要再夾一次。
        if (window.ALPHA_SERVO_CLAMP) {
          const clamped = window.ALPHA_SERVO_CLAMP(id, angle);
          if (clamped !== angle) {
            logLine(t('run_servo_clamped', { id: id, angle: angle, clamped: clamped }), 'warn');
            angle = clamped;
          }
        }
        const time = block.getFieldValue('TIME');
        logLine(t('run_servo_one', { id: id, angle: angle, time: time }));
        await servoOneAdapter(id, angle, time);
        return;
      }
      case 'alpha_servo_all': {
        const anglesVal = await evalValue(block.getInputTargetBlock('ANGLES'));
        const time = block.getFieldValue('TIME');
        // CSV 字串入面 20 個數值逐粒對應 #1~#20 校準表 clamp 一次, 唔理呢串
        // CSV 嚟自邊粒 block (helper 組合定係直接打字/變數), 送出前都要係安全值。
        let angles = String(anglesVal == null ? '' : anglesVal).split(',').map(function (s) { return Number(s.trim()); });
        if (window.ALPHA_SERVO_CLAMP) {
          angles = angles.map(function (v, idx) { return window.ALPHA_SERVO_CLAMP(idx + 1, v); });
        }
        logLine(t('run_servo_all', { csv: angles.join(','), time: time }));
        await servoAllAdapter(angles, time);
        return;
      }
      case 'alpha_servo_home': {
        // Home 值一律由共用校準表 (window.ALPHA_SERVO_CALIBRATION, 同「伺服部位」
        // block 及「伺服」分頁三處共用同一份資料, 見 blockly-servo-data.js) 讀出,
        // 唔再喺呢度另外 hardcode 一份可能會走數嘅副本。
        const cal = window.ALPHA_SERVO_CALIBRATION;
        const home = [];
        for (let i = 1; i <= 20; i++) home.push(cal && cal[i] ? cal[i].home : 120);
        const time = block.getFieldValue('TIME');
        logLine(t('run_servo_home', { time: time }));
        await servoAllAdapter(home, time);
        return;
      }
      case 'alpha_servo_sonar':
        // Lynx 呢部機冇超聲波測距硬件 (見 LynxController.java, 冇 servo/sonar
        // 對應 endpoint) —— 相近功能係人體紅外線感應 (PIR, /api/lynx/sys/pir),
        // 但感應原理/回傳形狀完全唔一樣, 唔可以直接映射過去。
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_sonar')); return; }
        logLine(t('run_sonar_distance', { dist: block.getFieldValue('DIST') }));
        await window.api('servo/sonar', { distance: block.getFieldValue('DIST') });
        return;

      // ---------------- LED ----------------
      case 'alpha_led_head': {
        const preset = block.getFieldValue('PRESET');
        logLine(t('run_led_head', { preset: preset }));
        const color = block.getFieldValue('COLOR');
        const brightness = block.getFieldValue('BRIGHT');
        await ledHeadAdapter(preset, color, brightness);
        return;
      }
      case 'alpha_led_eye': {
        const preset = block.getFieldValue('PRESET');
        logLine(t('run_led_eye', { preset: preset }));
        const color = block.getFieldValue('COLOR');
        const brightness = block.getFieldValue('BRIGHT');
        await ledEyeAdapter(preset, color, brightness);
        return;
      }
      case 'alpha_led_mouth': {
        const mode = block.getFieldValue('MODE');
        if (mode === 'off') {
          logLine(t('run_led_mouth_off'));
        } else {
          logLine(t('run_led_mouth_breathe', { speed: block.getFieldValue('SPEED') }));
        }
        await ledMouthAdapter(mode, block.getFieldValue('SPEED'));
        return;
      }

      // ---------------- 感應/裝置 ----------------
      // accelerometer/ 係 shared-hardware path (見 LynxController#isSharedHardwarePath()),
      // 兩個 backend 行為完全一樣, 唔使映射, window.api() prefix 轉咗就夠。
      case 'alpha_sensor_accel_toggle':
        logLine(t('run_accel_toggle', { on: block.getFieldValue('ON') }));
        await window.api('accelerometer/set', { on: block.getFieldValue('ON') });
        return;
      case 'alpha_sensor_sonar_toggle': {
        // 同 alpha_servo_sonar 一樣, Lynx 冇超聲波硬件 —— 呢粒 block 淨係
        // Alpha2 可用。
        if (isLynx()) { logUnsupportedOnLynx(t('run_block_name_sonar')); return; }
        const on = block.getFieldValue('ON') === 'true';
        const dist = on ? block.getFieldValue('DIST') : '0';
        logLine(t('run_sonar_toggle', { on: block.getFieldValue('ON'), thresholdNote: (on ? t('run_sonar_toggle_threshold', { dist: dist }) : '') }));
        await window.api('servo/sonar', { distance: dist });
        return;
      }
      case 'alpha_sensor_pir_toggle': {
        // 同 alpha_sensor_sonar_toggle 鏡像: PIR 係 Lynx 專屬硬件, Alpha2 冇
        // 呢種感應器 (見 blockly-blocks.js alpha_sensor_pir_toggle 一帶嘅
        // 註解), 所以呢粒 block 反過來淨係 Lynx 先做嘢, Alpha2 就跳過。
        if (!isLynx()) { logUnsupportedOnAlpha2(t('run_block_name_pir')); return; }
        logLine(t('run_pir_toggle', { on: block.getFieldValue('ON') }));
        await window.api('sys/pir', { on: block.getFieldValue('ON') });
        return;
      }

      // ---------------- 流程控制 ----------------
      case 'alpha_wait_seconds': {
        const secs = Number(await evalValue(block.getInputTargetBlock('SECONDS'))) || 0;
        logLine(t('run_wait_seconds', { secs: secs }));
        await sleep(secs * 1000);
        return;
      }
      case 'alpha_log': {
        const msg = await evalValue(block.getInputTargetBlock('MSG'));
        logLine('📝 ' + (msg === null || msg === undefined ? '' : String(msg)), 'user');
        return;
      }
      case 'alpha_stop_program':
        logLine(t('run_stop_program'), 'warn');
        stopRequested = true;
        return;

      // ---------------- Blockly 標準：if / repeat / while / for ----------------
      case 'controls_if': {
        let i = 0;
        while (block.getInput('IF' + i)) {
          const cond = await evalValue(block.getInputTargetBlock('IF' + i));
          if (cond) {
            await runSequence(block.getInputTargetBlock('DO' + i));
            return;
          }
          i++;
        }
        if (block.getInput('ELSE')) {
          await runSequence(block.getInputTargetBlock('ELSE'));
        }
        return;
      }
      case 'controls_repeat_ext': {
        const times = Math.round(Number(await evalValue(block.getInputTargetBlock('TIMES'))) || 0);
        for (let i = 0; i < times && !stopRequested; i++) {
          const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
          if (flow === 'BREAK') break;
          // flow === 'CONTINUE' 或 null: 直接入下一輪, 冇特別動作要做
        }
        return;
      }
      case 'controls_whileUntil': {
        const mode = block.getFieldValue('MODE');
        let guard = 0;
        while (!stopRequested && guard++ < 100000) {
          const cond = await evalValue(block.getInputTargetBlock('BOOL'));
          const shouldRun = mode === 'WHILE' ? cond : !cond;
          if (!shouldRun) break;
          const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
          if (flow === 'BREAK') break;
        }
        return;
      }
      case 'controls_for': {
        const varName = block.getFieldValue('VAR');
        const from = Number(await evalValue(block.getInputTargetBlock('FROM'))) || 0;
        const to = Number(await evalValue(block.getInputTargetBlock('TO'))) || 0;
        const by = Number(await evalValue(block.getInputTargetBlock('BY'))) || 1;
        if (by > 0) {
          for (let i = from; i <= to && !stopRequested; i += by) {
            variables.set(varName, i);
            const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
            if (flow === 'BREAK') break;
          }
        } else if (by < 0) {
          for (let i = from; i >= to && !stopRequested; i += by) {
            variables.set(varName, i);
            const flow = await runSequenceGuarded(block.getInputTargetBlock('DO'));
            if (flow === 'BREAK') break;
          }
        }
        return;
      }
      case 'controls_flow_statements':
        // break/continue: 用簡化模型, 直接拋出特殊訊號俾 runSequence/loop 接住。
        throw { __alphaFlow: block.getFieldValue('FLOW') };

      case 'variables_set': {
        const val = await evalValue(block.getInputTargetBlock('VALUE'));
        variables.set(block.getFieldValue('VAR'), val);
        return;
      }
      case 'math_change': {
        const varName = block.getFieldValue('VAR');
        const delta = Number(await evalValue(block.getInputTargetBlock('DELTA'))) || 0;
        const cur = Number(variables.get(varName)) || 0;
        variables.set(varName, cur + delta);
        return;
      }
      case 'text_print': {
        const v = await evalValue(block.getInputTargetBlock('TEXT'));
        logLine('🖨 ' + (v === null || v === undefined ? '' : String(v)), 'user');
        return;
      }

      default:
        logLine(t('run_unsupported_block', { type: blockType }), 'warn');
        return;
    }
  }

  async function runSequence(startBlock) {
    let block = startBlock;
    while (block && !stopRequested) {
      try {
        await execStatement(block);
      } catch (e) {
        if (e && e.__alphaFlow) {
          // break/continue 冒出去俾最近嘅迴圈接住;呢度用最簡單方式 — 直接向上拋,
          // runProgram() 頂層接唔到就當停止, 每個 loop-case 分支已經隱含用返
          // runSequence 嘅呼叫堆疊, 冒出一層即係跳出嗰層 loop 嘅 body。
          throw e;
        }
        logLine(t('run_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
      }
      block = block.getNextBlock();
    }
  }

  // controls_repeat_ext / whileUntil / for 用嘅 runSequence 需要接住 break/continue,
  // 包一層 try/catch 令佢哋喺該迴圈中斷,而唔係成個程式炸咗。
  const _rawRunSequence = runSequence;
  async function runSequenceGuarded(startBlock) {
    try {
      await _rawRunSequence(startBlock);
      return null;
    } catch (e) {
      if (e && e.__alphaFlow) return e.__alphaFlow;
      throw e;
    }
  }

  async function runProgram() {
    if (running) return;
    // 2026-08 bugfix: rewireEventHandlers() 平時靠 workspace change listener
    // debounce 300ms 先執行 (見 AlphaBlockly.init), 目的係避免一連串拖拽/
    // undo/redo 觸發太多次掃描。但如果用家啱啱先拖低一粒 accel/sonar 事件
    // block, 跟住手快即刻撳「執行」, 個 300ms debounce 未到, accelHandlers/
    // sonarHandlers 就仲係之前(冇呢粒新 block)嘅舊狀態 —— 結果個 hat block
    // 完全冇被註冊到, 「執行紀錄」會見到「已註冊 0 個」, 一直要等到下一次
    // workspace 有改動 (例如用家郁一下個 block) 先會補做一次 rewire。
    // 而家喺執行程式之前, 強制同步做多一次 rewireEventHandlers(), 確保
    // accel/sonar handler 一定反映緊畫布上最新狀態, 唔使靠彩。
    rewireEventHandlers();
    const EVENT_HAT_TYPES = ['alpha_event_accel_threshold', 'alpha_event_sonar_triggered', 'alpha_event_pir_triggered'];
    const topBlocks = workspace.getTopBlocks(true).filter(function (b) {
      return EVENT_HAT_TYPES.indexOf(b.type) === -1 && !b.isCollapsed() && !b.disabled;
    });
    if (!topBlocks.length) {
      logLine(t('run_no_executable_blocks'), 'warn');
      return;
    }
    running = true;
    stopRequested = false;
    setRunningUI(true);
    logLine(t('run_program_start', { count: topBlocks.length }), 'sys');
    try {
      for (const top of topBlocks) {
        if (stopRequested) break;
        await runSequence(top);
      }
    } catch (e) {
      if (!(e && e.__alphaFlow)) {
        logLine(t('run_runtime_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
      }
    }
    running = false;
    const wasStopped = stopRequested;
    stopRequested = false;
    setRunningUI(false);
    workspace.highlightBlock(null);
    logLine(wasStopped ? t('run_program_stopped') : t('run_program_finished'), 'sys');
  }

  function stopProgram() {
    stopRequested = true;
    logLine(t('run_user_pressed_stop'), 'sys');
  }

  // ------------------------------------------------------------------
  // 事件驅動：掃描 workspace 入面所有 alpha_event_accel_threshold /
  // alpha_event_sonar_triggered, 註冊做 WS listener
  // ------------------------------------------------------------------
  function rewireEventHandlers() {
    accelHandlers.length = 0;
    sonarHandlers.length = 0;
    pirHandlers.length = 0;
    if (!workspace) return;
    workspace.getTopBlocks(true).forEach(function (b) {
      if (b.type === 'alpha_event_accel_threshold' && !b.disabled) {
        accelHandlers.push({
          axis: b.getFieldValue('AXIS'),
          cmp: b.getFieldValue('CMP'),
          threshold: Number(b.getFieldValue('THRESHOLD')),
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          running: false, // re-entrancy guard: 呢個 handler 嘅 DO 序列係咪跑緊
        });
      } else if (b.type === 'alpha_event_sonar_triggered' && !b.disabled) {
        sonarHandlers.push({
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: false, // 邊緣觸發用: 上次收到嘅 triggered 狀態
          running: false, // re-entrancy guard: 呢個 handler 嘅 DO 序列係咪跑緊
        });
      } else if (b.type === 'alpha_event_pir_triggered' && !b.disabled) {
        pirHandlers.push({
          when: b.getFieldValue('WHEN'), // 'detected' (冇人→有人) 或 'lost' (有人→冇人)
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: false, // 同 sonarHandlers 一樣嘅邊緣觸發用法
          running: false, // re-entrancy guard: 呢個 handler 嘅 DO 序列係咪跑緊
        });
      }
    });
    logLine(t('run_handlers_registered', { accel: accelHandlers.length, sonar: sonarHandlers.length, pir: pirHandlers.length }), 'sys');
    // Lynx 冇超聲波硬件 (同 alpha_servo_sonar/alpha_sensor_sonar_toggle 一樣嘅
    // 原因), 所以永遠都唔會有 sonar_obstacle 呢隻 WebSocket 事件送到 —— 呢啲
    // block 唔會報錯, 淨係靜靜地永遠唔觸發, 對用家嚟講好易誤會「係咪冇裝好」,
    // 所以喺 workspace 有呢類 block 兼且而家用緊 Lynx 嗰陣, 主動响 log 度提一提。
    if (isLynx() && sonarHandlers.length > 0) {
      logUnsupportedOnLynx(t('run_block_name_sonar_event'));
    }
    // 鏡像檢查: PIR 事件 block 淨係 Lynx 先會收到 pir_state —— Alpha2 冇呢種
    // 廣播, 用家如果喺 Alpha2 backend 拖低咗呢粒 block, 一樣要提一提。
    if (!isLynx() && pirHandlers.length > 0) {
      logUnsupportedOnAlpha2(t('run_block_name_pir_event'));
    }
  }

  async function onWsEvent(evt) {
    window.__alphaFireEvent(evt); // 俾 alpha_action_wait_done 等 ad-hoc listener 用
    // 加速度計：每次收到 accel 事件, 逐個已註冊嘅門檻 block 檢查一次, 讀數
    // (含重力分量, 見 MainActivity onSensorChanged 註解) 嘅絕對值過咗門檻就
    // 觸發。
    //
    // 2026-08 bugfix: 之前呢度冇任何防抖/re-entrancy guard —— accel 事件本身
    // 高頻 (~150-250ms 一次), 而 DO 入面隨便一個 servo 動作就成 1 秒以上。
    // 結果一個 body 仲喺度 await runSequence() 跑緊, 下一個/幾個 accel 事件
    // 又已經到咗, 而個 for-loop 冇擋住, 會即刻再 call 多次 runSequence(),
    // 變成同一個 hatBlock 有多個 sequence 同時間疊住跑, 睇落就好似「動作會
    // 不斷重複」。呢個唔關「冇邊緣觸發」事 (accel 本身就係「持續超過門檻就
    // 反覆觸發」嘅語意, 唔似 sonar 有明確嘅「啱啱先偵測到」一次性語意) ——
    // 真正問題係冇擋住重疊執行。跟返 Scratch runtime 個標準做法: 同一個
    // hat block 同一時間淨係俾佢跑緊一份 sequence, 上一輪未完之前, 新嚟嘅
    // 觸發事件直接跳過 (skip), 唔會 queue 起或者疊加。
    if (evt.type === 'accel' && evt.data) {
      for (const h of accelHandlers) {
        if (!h.bodyBlock) continue;
        if (h.running) continue; // 上一輪 DO 仲未跑完, 呢次觸發直接跳過
        const v = Math.abs(Number(evt.data[h.axis]));
        const hit = h.cmp === 'gt' ? v > h.threshold : v < h.threshold;
        if (!hit) continue;
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_accel_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
    // 聲納：sonar_obstacle 事件本身喺 MainActivity 側已經 debounce 過 (連續同
    // 狀態嘅 frame 唔會重複 publish LED 開關, 但事件本身可能仍然逐 frame 送),
    // 呢度用 wasTriggered 做「由未觸發變觸發」嘅邊緣偵測, 令「偵測到障礙」
    // 呢個語意係「啱啱先偵測到」, 唔會物件持續喺門檻範圍入面就不斷重複執行。
    //
    // 2026-08 bugfix: 單靠邊緣偵測唔夠 —— 如果物件反覆進出門檻距離 (例如喺
    // 門檻邊緣徘徊), wasTriggered 會反覆 false→true, 每次都係一個新嘅
    // 「edge」, 一樣會喺上一輪 DO 未跑完之前又觸發多次, 造成同一個 hatBlock
    // 有多個 sequence 疊住跑。加返同 accel 一樣嘅 running guard。
    if (evt.type === 'sonar_obstacle' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of sonarHandlers) {
        if (!h.bodyBlock) continue;
        const edge = triggeredNow && !h.wasTriggered;
        h.wasTriggered = triggeredNow;
        if (!edge) continue;
        if (h.running) continue; // 上一輪 DO 仲未跑完, 呢次觸發直接跳過
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_sonar_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
    // PIR：MainActivity.java 嘅 registerPirAlertListener() 發嘅 "pir_state" 事件,
    // data 形狀 { triggered: boolean } 同 sonar_obstacle 一模一樣, 所以呢度直接
    // 複製返 sonar 嗰段嘅「邊緣觸發 + re-entrancy guard」寫法, 唔使診多套邏輯。
    //
    // 2026-08: 加咗 h.when 分支 —— 'detected' (預設, 同之前行為一致) 淨係喺
    // wasTriggered false→true 嗰一刻觸發; 'lost' 就反過嚟, 淨係喺
    // true→false 嗰一刻觸發。兩個方向嘅 handler 可以同時存在 (用家可以喺
    // workspace 度同時擺一粒「偵測到人」同一粒「冇偵測到人」嘅 hat block),
    // 各自獨立追蹤住自己嘅 wasTriggered, 互不影響。
    if (evt.type === 'pir_state' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of pirHandlers) {
        if (!h.bodyBlock) continue;
        const edge = h.when === 'lost'
          ? (!triggeredNow && h.wasTriggered)
          : (triggeredNow && !h.wasTriggered);
        h.wasTriggered = triggeredNow;
        if (!edge) continue;
        if (h.running) continue; // 上一輪 DO 仲未跑完, 呢次觸發直接跳過
        variables.set(h.varName, evt.data);
        highlight(h.hatBlock.id);
        h.running = true;
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine(t('run_pir_trigger_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
          }
        } finally {
          h.running = false;
        }
      }
    }
  }

  // 簡易 pub/sub, 俾 wait_done 呢類 ad-hoc 一次性 listener 用, 唔使全部塞入 accelHandlers/sonarHandlers。
  const adHocListeners = new Set();
  window.__alphaOnEvent = function (fn) { adHocListeners.add(fn); };
  window.__alphaOffEvent = function (fn) { adHocListeners.delete(fn); };
  window.__alphaFireEvent = function (evt) { adHocListeners.forEach(function (fn) { fn(evt); }); };

  // ------------------------------------------------------------------
  // Save / Load — workspace 存做 XML, 支援 localStorage 快存 + 匯出/匯入 .xml 檔案
  // ------------------------------------------------------------------
  const STORAGE_KEY = 'alpha_blockly_workspace_v1';
  const STORAGE_KEY_LIST = 'alpha_blockly_saved_programs_v1';

  function workspaceToXmlText() {
    const xmlDom = Blockly.Xml.workspaceToDom(workspace);
    return Blockly.Xml.domToPrettyText(xmlDom);
  }

  function loadXmlText(xmlText) {
    workspace.clear();
    const xmlDom = Blockly.utils.xml.textToDom(xmlText);
    Blockly.Xml.domToWorkspace(xmlDom, workspace);
    rewireEventHandlers();
  }

  function autoSaveToLocalStorage() {
    try {
      localStorage.setItem(STORAGE_KEY, workspaceToXmlText());
    } catch (e) {
      console.error('autosave failed', e);
    }
  }

  function restoreFromLocalStorage() {
    try {
      const xml = localStorage.getItem(STORAGE_KEY);
      if (xml) {
        loadXmlText(xml);
        logLine(t('run_restored_autosave'), 'sys');
        return true;
      }
    } catch (e) {
      console.error('restore failed', e);
    }
    return false;
  }

  function getSavedProgramList() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY_LIST) || '{}');
    } catch (e) {
      return {};
    }
  }

  function saveNamedProgram(name) {
    const list = getSavedProgramList();
    list[name] = { xml: workspaceToXmlText(), savedAt: new Date().toISOString() };
    localStorage.setItem(STORAGE_KEY_LIST, JSON.stringify(list));
    refreshSavedProgramDropdown();
    logLine(t('run_saved_as', { name: name }), 'sys');
  }

  function loadNamedProgram(name) {
    const list = getSavedProgramList();
    if (list[name]) {
      loadXmlText(list[name].xml);
      logLine(t('run_loaded', { name: name }), 'sys');
    }
  }

  function deleteNamedProgram(name) {
    const list = getSavedProgramList();
    delete list[name];
    localStorage.setItem(STORAGE_KEY_LIST, JSON.stringify(list));
    refreshSavedProgramDropdown();
    logLine(t('run_deleted', { name: name }), 'sys');
  }

  function refreshSavedProgramDropdown() {
    const sel = document.getElementById('savedProgramSelect');
    if (!sel) return;
    const list = getSavedProgramList();
    const names = Object.keys(list).sort();
    sel.innerHTML = '<option value="">' + t('run_saved_program_placeholder') + '</option>' +
      names.map(function (n) { return '<option value="' + n.replace(/"/g, '&quot;') + '">' + n + '</option>'; }).join('');
  }

  function exportXmlFile() {
    const xml = workspaceToXmlText();
    const blob = new Blob([xml], { type: 'application/xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'alpha2-program-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.xml';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    logLine(t('run_exported'), 'sys');
  }

  function importXmlFile(file) {
    const reader = new FileReader();
    reader.onload = function () {
      try {
        loadXmlText(String(reader.result));
        logLine(t('run_imported', { file: file.name }), 'sys');
      } catch (e) {
        logLine(t('run_import_failed', { err: e.message }), 'err');
      }
    };
    reader.readAsText(file);
  }

  // ------------------------------------------------------------------
  // 動作清單下拉：由 /api/action/list 抓返嚟, 填入 alpha_action_play_dropdown
  //
  // 分類前綴用 action id 去查 window.ALPHA_ACTION_CATEGORY_OF (定義喺
  // blockly-actions-data.js, 由 action_classified.txt 嘅 id -> main 對照表建構)。
  //
  // 注意: 呢度一定要用 a.id, 唔可以用 a.type —— 機械人 /api/action/list 回傳嘅
  // "type" 係機身 firmware 自己嘅內部動作類型編號 (跟 IAlphaActionListListener
  // 個 4 欄 id/type/nameCn/nameEn 對應), 同我哋喺 action_classified.txt 人手分類
  // 出嚟嘅 4 大分類 (basic/dance/story/yoga) 係完全獨立、冇關係嘅兩套編號,
  // 撞得啱純屬巧合。之前舊版靠 type 數字做白名單推斷分類, 表面上部分「啱」都係
  // 因為兩套編號剛巧喺某啲數值範圍重疊, 唔可靠。
  // ------------------------------------------------------------------
  async function refreshActionDropdown() {
    logLine(t('run_fetching_action_list'), 'sys');
    const r = await window.api('action/list');
    if (r && r.ok && Array.isArray(r.actions)) {
      window.__alphaActionOptions = r.actions.map(function (a) {
        const catKey = window.ALPHA_ACTION_CATEGORY_OF ? window.ALPHA_ACTION_CATEGORY_OF(a.id) : '';
        const catInfo = (window.ALPHA_ACTION_CATEGORIES || []).filter(function (c) { return c.key === catKey; })[0];
        const prefix = catInfo ? ('[' + catInfo.label + '] ') : '';
        const label = prefix + (a.nameCn || a.nameEn || a.id) + '  [' + a.id + ']';
        return [label, a.id];
      });
      if (!window.__alphaActionOptions.length) {
        window.__alphaActionOptions = [[t('run_action_list_empty'), '']];
      }
      logLine(t('run_action_list_loaded', { count: r.actions.length }), 'sys');
    } else {
      logLine(t('run_action_list_failed'), 'err');
      window.__alphaActionOptions = [[t('run_action_list_load_failed_option'), '']];
    }
    // 強制所有現存嘅 dropdown block 重新渲染,顯示新選項。
    //
    // ⚠ 呢度一定要連 setValue 埋一齊做, 唔可以淨係 getOptions()+forceRerender():
    // 個 field 之前 (未攞清單前) 存住嘅值好可能係 fallback 選項嘅 '' (即係
    // [['(未載入...)', '']] 嗰個 value), 而依家攞到嘅新清單入面已經冇呢個 ''
    // value 呢一項——變成「field 目前存住嘅值,喺 field 自己個 menuGenerator
    // 而家會返嘅 options 入面搵唔到」呢種 value/options 唔一致嘅狀態。呢個狀態
    // 底下 Blockly 嘅 dropdown 會撳極都冇反應 (曾經實測出現嘅 bug), 睇落成個
    // dropdown 壞咗一樣。所以攞到新清單之後, 如果個 field 現存嘅值唔喺新
    // options 入面, 要主動 setValue 去新清單第一項, 令個 field 隨時都處於
    // 「目前值 ∈ 目前 options」呢個一致狀態。
    if (workspace) {
      workspace.getBlocksByType('alpha_action_play_dropdown', false).forEach(function (b) {
        const field = b.getField('NAME');
        if (field && field.getOptions) {
          field.getOptions(false);
          const opts = field.getOptions(false);
          const curVal = field.getValue();
          const stillValid = opts.some(function (pair) { return pair[1] === curVal; });
          if (!stillValid && opts.length) field.setValue(opts[0][1]);
          field.forceRerender();
        }
      });
    }
  }

  // 2026-08 更新: 系統鈴聲清單依家靜態內嵌喺 blockly-ringtone-data.js (由實機
  // adb 抓一次, 見該檔頭註解), 唔再喺呢度即時查 /api/audio/ringtones/list —
  // refreshRingtoneDropdown() 呢個 function 同工具箱嗰粒「攞鈴聲清單」按鈕已經
  // 一齊移除, 見 blockly.html。

  // ------------------------------------------------------------------
  // 剪貼/復原/收埋側欄呢兩組掣 —— 抄自 NuwaRobotics Code Lab, 用 Blockly 官方嘅
  // Blockly.ComponentManager + Blockly.uiPosition (IPositionable 介面) 重寫,
  // 同垃圾桶 (Trashcan) / 縮放掣 (ZoomControls) 用返完全同一套定位管線：
  // WorkspaceSvg 內部每次 resize 都會攞晒所有已註冊嘅 POSITIONABLE component,
  // 按 weight 由細到大逐個 call .position(uiMetrics, alreadyPositionedRects),
  // 每個 call 完之後攞返佢 .getBoundingRectangle() 加入 alreadyPositionedRects
  // 度, 等下一個 (weight 更大嘅) component 定位嗰陣可以自動避開佢 —— 呢個
  // 就係點解垃圾桶/縮放掣之間永遠唔會疊埋嘅原因, 而家我哋自己嘅掣都用返
  // 呢一套, 所以永遠都會跟實佢哋, 唔會再走位。
  //
  // 之前試過兩次用獨立 HTML <div> + CSS position:absolute + JS 度
  // getBoundingClientRect() 量度 Blockly 垃圾桶而家喺邊嚟追 —— 但 Blockly
  // 內部個位置公式 (uiPosition.getStartPositionRect/bumpPositionRect) 本身
  // 都有唔少因素 (scrollbar 有冇、toolbox 響邊、RTL) 會影響實際數值, 追極都會
  // 慢半拍或者算錯, 依家改用返 Blockly 官方機制先係真正治本嘅做法。
  //
  // Blockly.utils.dom.createSvgElement(tag, attrs, parent) 呢個 helper 同
  // Blockly 自己 Trashcan/ZoomControls 起 DOM 用緊嘅係同一個 function
  // (喺 minified source 度確認過, 對應 Blockly.utils.dom.createSvgElement)。

  const EDIT_FAB_ICON_PATHS = {
    // 每個 icon 用 20x20 嘅 viewBox 座標系統畫, 用 <path> stroke 勾線 (唔用
    // 實心 fill), 對齊返 Code Lab 個線條風格 (undo/redo 箭頭、剪刀、複製兩個
    // 疊埋嘅方格、貼上剪貼板形狀、垃圾桶)。
    undo: 'M6 6 L6 3 M6 6 L9 6 M6 6 C6 6 15 4 15 11 C15 15.5 11.5 17 8.5 17 C6.5 17 5 16.3 4 15.3',
    redo: 'M14 6 L14 3 M14 6 L11 6 M14 6 C14 6 5 4 5 11 C5 15.5 8.5 17 11.5 17 C13.5 17 15 16.3 16 15.3',
    cut: 'M6 5 L14 15 M14 5 L10 9 M6 15 L8.5 12.5 M6 5 A1.6 1.6 0 1 0 6 8.2 A1.6 1.6 0 1 0 6 5 Z M6 11.8 A1.6 1.6 0 1 0 6 15 A1.6 1.6 0 1 0 6 11.8 Z',
    copy: 'M8 3 H15 V12 H8 Z M5 6 H12 V15 H5 Z',
    paste: 'M7 3 H13 V5 H7 Z M5 4 H15 V17 H5 Z M8 9 H12 M8 12 H12',
    delete: 'M5 6 H15 M8 6 V4 H12 V6 M7 6 L7.7 17 H12.3 L13 6 M9 9 V14 M11 9 V14',
  };

  // ------------------------------------------------------------------
  // EditFabControls — 一個 IPositionable component, 內部包住六粒小掣
  // (復原/取消復原/剪下/複製/貼上/刪除), 成組一齊定位, 行為好似 Blockly 個
  // ZoomControls 咁 (裡面雖然有幾粒掣, 但對 ComponentManager 嚟講係一個
  // component, 一次 getBoundingRectangle() covers 晒成組)。
  // ------------------------------------------------------------------
  class EditFabControls {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaEditFabControls';
      this.top = 0;
      this.left = 0;
      // 版面: 圓形掣直徑 28px, 分隔線 6px, 兩個分隔線分 3 組 (復原/取消復原 ｜
      // 剪/copy/貼 ｜ 刪除), 橫向排晒一行, 抄 Code Lab 個排位 (一行, 唔係
      // 分兩行/直排)。
      this.BUTTON_SIZE = 28;
      this.GAP = 4;
      this.SEP_WIDTH = 9; // 分隔線本身 1px + 兩邊留白
      this.MARGIN_HORIZONTAL = 12;
      this.MARGIN_VERTICAL = 12;
      this.buttons = [
        { action: 'undo', icon: 'undo', titleKey: 'page_edit_undo_title' },
        { action: 'redo', icon: 'redo', titleKey: 'page_edit_redo_title' },
        { sep: true },
        { action: 'cut', icon: 'cut', titleKey: 'page_edit_cut_title' },
        { action: 'copy', icon: 'copy', titleKey: 'page_edit_copy_title' },
        { action: 'paste', icon: 'paste', titleKey: 'page_edit_paste_title' },
        { sep: true },
        { action: 'delete', icon: 'delete', titleKey: 'page_edit_delete_title', danger: true },
      ];
      this.buttonEls = {}; // action -> { group, circle, titleEl }
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 3, // Trashcan 通常係 weight 2, ZoomControls weight 1 —— 擺
                   // 喺佢哋之後 (數值愈大愈遲定位), 等呢兩個 Blockly 自己嘅
                   // component 先取得佢哋慣常嘅角落位置, 我哋成組先至喺
                   // bumpPositionRect() 嗰陣自動被推去再上少少, 唔會疊埋。
      });
      this.workspace.resizeContents();
    }

    getGroupWidth() {
      // 直接模擬返 createDom() 入面個 x 累加邏輯, 唔用獨立公式計 (兩者之前試過
      // 對唔實, 因為分隔線嘅 GAP 計算方式好易手民之誤), 保證呢度攞到嘅闊度
      // 同真正畫出嚟嘅闊度完全一致 —— 呢個闊度會直接影響
      // getBoundingRectangle(), 錯咗會令 bumpPositionRect() 嘅避讓計算唔準。
      let x = 0;
      let lastButtonEnd = 0;
      for (const b of this.buttons) {
        if (b.sep) {
          x += this.SEP_WIDTH + this.GAP;
        } else {
          lastButtonEnd = x + this.BUTTON_SIZE;
          x += this.BUTTON_SIZE + this.GAP;
        }
      }
      return lastButtonEnd;
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', { class: 'bk-svg-fab-bar' }, null);
      // 成組掣底下鋪一塊圓角背景 (等視覺上似返之前 HTML 版嗰個「浮動 pill」,
      // 抄 Code Lab 個做法), 闊度/高度要喺全部掣起晒之後先知, 所以呢度先起
      // 個 placeholder, 尾段先補返啱嘅 width/height (見底下 svg.appendChild
      // 之前嗰段)。
      this.bgRect = Blockly.utils.dom.createSvgElement('rect', {
        class: 'bk-svg-fab-bg', x: -8, y: -6, rx: 20, ry: 20, height: this.BUTTON_SIZE + 12,
      }, this.svgGroup);
      let x = 0;
      for (const b of this.buttons) {
        if (b.sep) {
          Blockly.utils.dom.createSvgElement('line', {
            class: 'bk-svg-fab-sep',
            x1: x + this.SEP_WIDTH / 2, x2: x + this.SEP_WIDTH / 2,
            y1: 3, y2: this.BUTTON_SIZE - 3,
          }, this.svgGroup);
          x += this.SEP_WIDTH + this.GAP;
          continue;
        }
        const group = Blockly.utils.dom.createSvgElement('g', {
          class: 'bk-svg-fab-group', transform: `translate(${x}, 0)`,
        }, this.svgGroup);
        const circle = Blockly.utils.dom.createSvgElement('circle', {
          class: 'bk-svg-fab-circle' + (b.danger ? ' bk-svg-fab-circle-danger' : ''),
          cx: this.BUTTON_SIZE / 2, cy: this.BUTTON_SIZE / 2, r: this.BUTTON_SIZE / 2 - 1,
        }, group);
        const iconGroup = Blockly.utils.dom.createSvgElement('g', {
          transform: `translate(${(this.BUTTON_SIZE - 20) / 2}, ${(this.BUTTON_SIZE - 20) / 2}) scale(0.72)`,
        }, group);
        Blockly.utils.dom.createSvgElement('path', {
          class: 'bk-svg-fab-icon' + (b.danger ? ' bk-svg-fab-icon-danger' : ''),
          d: EDIT_FAB_ICON_PATHS[b.icon],
        }, iconGroup);
        const titleEl = Blockly.utils.dom.createSvgElement('title', {}, group);
        titleEl.textContent = t(b.titleKey);
        group.addEventListener('pointerdown', (evt) => {
          evt.preventDefault();
          evt.stopPropagation();
        });
        group.addEventListener('click', (evt) => {
          evt.preventDefault();
          evt.stopPropagation();
          if (group.classList.contains('bk-svg-fab-disabled')) return;
          editAction(b.action);
        });
        this.buttonEls[b.action] = { group, circle, titleEl };
        x += this.BUTTON_SIZE + this.GAP;
      }
      // 而家知道晒成組掣真正用咗幾闊, 補返個背景 pill 嘅闊度 (x 呢個時候已經
      // 行到最尾一個掣嘅右邊, 扣返最後嗰個 GAP 先係實際內容闊度, 再加返兩邊
      // 嘅內邊距)。
      const contentWidth = x - this.GAP;
      this.bgRect.setAttribute('width', contentWidth + 16);
      // 背景 pill 一定要係呢個 group 入面第一個子元素 (SVG 冇 z-index, 靠 DOM
      // 順序決定邊個喺上面), 先可以擺喺全部掣個底層唔會擋住佢哋嘅 click ——
      // 起嘅時候已經係第一個 appendChild (bgRect 響 loop 之前起), 呢度唔使
      // 再郁佢個順序。
      svg.appendChild(this.svgGroup);
    }

    // 更新複製/剪下/刪除三粒掣嘅 disabled 狀態 (同之前 HTML 版一樣邏輯: 冇
    // 揀緊 block 就 disable 呢三粒, 復原/取消復原一路留低俾用家自己試)。
    updateButtonStates() {
      const hasSelection = !!currentSelectedBlock();
      for (const action of ['cut', 'copy', 'delete']) {
        const el = this.buttonEls[action];
        if (el) el.group.classList.toggle('bk-svg-fab-disabled', !hasSelection);
      }
    }

    // 語言切換後, title (SVG <title> tooltip) 要跟住換 —— 同 blockly-i18n.js
    // 嘅 applyUiTextLocale() 對應嘅 HTML 版做法一致, 呢度用嚟俾佢 call。
    updateI18n() {
      for (const b of this.buttons) {
        if (b.sep) continue;
        const el = this.buttonEls[b.action];
        if (el) el.titleEl.textContent = t(b.titleKey);
      }
    }

    getBoundingRectangle() {
      const width = this.getGroupWidth();
      const height = this.BUTTON_SIZE;
      return new Blockly.utils.Rect(this.top, this.top + height, this.left, this.left + width);
    }

    position(uiMetrics, savedPositions) {
      const width = this.getGroupWidth();
      const size = new Blockly.utils.Size(width, this.BUTTON_SIZE);
      const corner = Blockly.uiPosition.getCornerOppositeToolbox(this.workspace, uiMetrics);
      let rect = Blockly.uiPosition.getStartPositionRect(
        corner, size, this.MARGIN_HORIZONTAL, this.MARGIN_VERTICAL, uiMetrics, this.workspace);
      const bumpDir = corner.vertical === Blockly.uiPosition.verticalPosition.TOP
        ? Blockly.uiPosition.bumpDirection.DOWN
        : Blockly.uiPosition.bumpDirection.UP;
      rect = Blockly.uiPosition.bumpPositionRect(rect, this.MARGIN_VERTICAL, bumpDir, savedPositions);
      this.top = rect.top;
      this.left = rect.left;
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  // ------------------------------------------------------------------
  // SidePanelToggleControl — 同上面一樣機制嘅另一個 IPositionable component,
  // 「收埋/展開執行紀錄面板」嗰粒 ›/‹ 掣。獨立成一個 component (唔係塞入
  // EditFabControls 度) 係因為佢嘅 weight/擺位邏輯唔同 —— 呢粒掣要貼住
  // .bk-side 個左邊界, 唔係跟 Blockly 慣常嘅「畫布角落」定位, 所以 position()
  // 入面唔用 uiPosition 嗰套, 改為直接讀 .bk-side 嘅實際 DOM 閂位置。
  // ------------------------------------------------------------------
  class SidePanelToggleControl {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaSidePanelToggle';
      this.top = 14;
      this.left = 0;
      this.WIDTH = 18;
      this.HEIGHT = 36;
      this.collapsed = false;
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 10, // 呢粒掣位置獨立計算, 唔使理其他 component bump 佢, 擺
                    // 喺最後 (weight 最大) 就得。
      });
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', {
        class: 'bk-svg-side-toggle-group',
      }, svg);
      Blockly.utils.dom.createSvgElement('rect', {
        class: 'bk-svg-side-toggle-bg',
        x: 0, y: 0, width: this.WIDTH, height: this.HEIGHT, rx: 6,
      }, this.svgGroup);
      this.arrowEl = Blockly.utils.dom.createSvgElement('path', {
        class: 'bk-svg-side-toggle-arrow',
        d: this.arrowPath(false),
      }, this.svgGroup);
      this.titleEl = Blockly.utils.dom.createSvgElement('title', {}, this.svgGroup);
      this.titleEl.textContent = t('page_side_toggle_title');
      this.svgGroup.addEventListener('pointerdown', (evt) => {
        evt.preventDefault();
        evt.stopPropagation();
      });
      this.svgGroup.addEventListener('click', (evt) => {
        evt.preventDefault();
        evt.stopPropagation();
        toggleSidePanel();
      });
    }

    // 未收埋顯示 › (指緊右, 即係「撳咗會收埋去右邊」), 收埋咗顯示 ‹ (指緊左,
    // 即係「撳咗會展開返嚟」) —— 同 Code Lab 個箭頭方向邏輯一致。
    arrowPath(collapsed) {
      const cx = this.WIDTH / 2, cy = this.HEIGHT / 2;
      return collapsed
        ? `M${cx + 3} ${cy - 6} L${cx - 3} ${cy} L${cx + 3} ${cy + 6}`
        : `M${cx - 3} ${cy - 6} L${cx + 3} ${cy} L${cx - 3} ${cy + 6}`;
    }

    setCollapsed(collapsed) {
      this.collapsed = collapsed;
      this.arrowEl.setAttribute('d', this.arrowPath(collapsed));
    }

    updateI18n() {
      this.titleEl.textContent = t('page_side_toggle_title');
    }

    getBoundingRectangle() {
      return new Blockly.utils.Rect(this.top, this.top + this.HEIGHT, this.left, this.left + this.WIDTH);
    }

    // 呢粒掣要半浮喺「畫布/側欄交界」—— 唔跟 Blockly 慣常嘅四角定位, 直接讀
    // .bk-side 個 DOM 元素實際企喺邊 (getBoundingClientRect()), 減返
    // .bk-canvas 個 SVG 原點嘅螢幕座標, 就攞到啱嘅 SVG 內部座標。側欄收埋咗
    // 嗰陣 (.bk-side flex-basis 變 0) 佢個 left 都會自動變做 canvas 右邊緣,
    // 掣就自然跟住郁埋去右邊界, 唔使額外邏輯。
    position(uiMetrics, savedPositions) {
      const svg = this.workspace.getParentSvg();
      const svgRect = svg.getBoundingClientRect();
      const sideEl = document.getElementById('bkSide');
      const sideRect = sideEl ? sideEl.getBoundingClientRect() : null;
      const boundaryX = sideRect ? (sideRect.left - svgRect.left) : (svgRect.width);
      this.left = boundaryX - this.WIDTH / 2;
      this.top = 14;
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  let editFabControls = null;
  let sidePanelToggleControl = null;

  function currentSelectedBlock() {
    return (Blockly.common && Blockly.common.getSelected) ? Blockly.common.getSelected() : null;
  }

  function editAction(action) {
    if (!workspace) return;
    switch (action) {
      case 'undo':
        workspace.undo(false);
        break;
      case 'redo':
        workspace.redo();
        break;
      case 'copy': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable() && Blockly.clipboard && Blockly.clipboard.copy) {
          Blockly.clipboard.copy(b);
        }
        break;
      }
      case 'cut': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable() && b.isMovable() && Blockly.clipboard && Blockly.clipboard.copy) {
          Blockly.clipboard.copy(b);
          b.checkAndDelete();
        }
        break;
      }
      case 'paste':
        if (Blockly.clipboard && Blockly.clipboard.paste) {
          Blockly.clipboard.paste(workspace);
        }
        break;
      case 'delete': {
        const b = currentSelectedBlock();
        if (b && b.isDeletable()) {
          b.checkAndDelete();
        }
        break;
      }
      default:
        return;
    }
    if (editFabControls) editFabControls.updateButtonStates();
  }

  // ------------------------------------------------------------------
  // 對外掛出
  // ------------------------------------------------------------------
  window.AlphaBlockly = {
    init: function (ws) {
      workspace = ws;
      restoreFromLocalStorage();
      rewireEventHandlers();
      refreshSavedProgramDropdown();
      // rewire + autosave 都用 debounce: 一連串 block 改動 (例如拖拽、程式化建立、
      // undo/redo) 會喺短時間內觸發好多個 non-UI change event, 逐個即時處理既浪費
      // 又會令 log 洗版, debounce 到「呢輪改動停咗」先做一次就夠。
      let rewireTimer = null;
      let saveTimer = null;
      workspace.addChangeListener(function (e) {
        if (e.isUiEvent) return;
        clearTimeout(rewireTimer);
        rewireTimer = setTimeout(rewireEventHandlers, 300);
        clearTimeout(saveTimer);
        saveTimer = setTimeout(autoSaveToLocalStorage, 800);
      });
      // 揀/取消揀 block 都係 UI event (isUiEvent === true, 上面嗰個 listener
      // 特登 return 咗唔理), 所以剪貼掣嘅 enable/disable 狀態要獨立一個
      // listener 專門聽 SELECTED 事件先追得到。
      workspace.addChangeListener(function (e) {
        if (e.type === Blockly.Events.SELECTED || e.type === Blockly.Events.FINISHED_LOADING) {
          if (editFabControls) editFabControls.updateButtonStates();
        }
      });
      // 起返兩組 IPositionable component (詳見上面 EditFabControls/
      // SidePanelToggleControl 呢兩個 class 嘅大段註解) —— 一定要喺 workspace
      // inject 咗、有真正嘅 SVG root 之後先可以起, 所以擺喺 init() 呢度做,
      // 唔係喺 module load 嗰陣就起。
      editFabControls = new EditFabControls(workspace);
      sidePanelToggleControl = new SidePanelToggleControl(workspace);
      editFabControls.updateButtonStates();
    },
    run: runProgram,
    stop: stopProgram,
    onWsEvent: onWsEvent,
    saveNamed: saveNamedProgram,
    loadNamed: loadNamedProgram,
    deleteNamed: deleteNamedProgram,
    exportXmlFile: exportXmlFile,
    importXmlFile: importXmlFile,
    refreshActionDropdown: refreshActionDropdown,
    refreshSavedProgramDropdown: refreshSavedProgramDropdown, // 俾 blockly-i18n.js 切語言嗰陣攞返嚟用, 令 "-- 已儲存嘅程式 --" placeholder 跟住重新 render
    editAction: editAction,
    // 語言切換後 (blockly-i18n.js setUiLanguage()) 要跟住換返呢兩組 SVG
    // component 嘅 <title> tooltip 文字, HTML 版 data-i18n 呢套機制淨係識
    // 揾 DOM 元素, 執行唔到我哋自己起嘅 SVG UI, 要俾 blockly-i18n.js 專登
    // call 呢個 method。
    refreshEditControlsI18n: function () {
      if (editFabControls) editFabControls.updateI18n();
      if (sidePanelToggleControl) sidePanelToggleControl.updateI18n();
    },
    toggleSidePanel: function () {
      const main = document.querySelector('.bk-main');
      if (!main) return;
      const collapsed = !main.classList.contains('bk-side-collapsed');
      main.classList.toggle('bk-side-collapsed', collapsed);
      if (sidePanelToggleControl) sidePanelToggleControl.setCollapsed(collapsed);
      try { localStorage.setItem('blocklySideCollapsed', collapsed ? '1' : '0'); } catch (e) { /* 唔緊要, 冇記錄低就下次預設展開 */ }
      // .bk-side flex-basis 有 CSS transition (0.18s), 畫布闊度同
      // SidePanelToggleControl 個位置都要跟住個過渡動畫慢慢郁, resize
      // 幾次涵蓋成個過程 (Blockly.svgResize 會觸發 ComponentManager
      // 重新 position 一次, 所以呢度淨係要負責喺啱嘅時間點 call 佢)。
      if (workspace) {
        Blockly.svgResize(workspace);
        setTimeout(function () { Blockly.svgResize(workspace); }, 100);
        setTimeout(function () { Blockly.svgResize(workspace); }, 200);
      }
    },
    setSidePanelCollapsedInitial: function (collapsed) {
      // 頁面啱啱 load, 讀返 localStorage 記住嘅上次收/展開狀態嗰陣用 —— 唔想
      // 用 toggleSidePanel() (佢帶埋 0.18s transition 嘅 setTimeout 級聯),
      // 淨係要直接set 好個初始狀態, 唔使播動畫。
      const main = document.querySelector('.bk-main');
      if (!main) return;
      main.classList.toggle('bk-side-collapsed', collapsed);
      if (sidePanelToggleControl) sidePanelToggleControl.setCollapsed(collapsed);
    },
    clearWorkspace: function () {
      if (workspace) workspace.clear();
    },
    getVariablesSnapshot: function () {
      return Object.fromEntries(variables);
    },
  };
})();
