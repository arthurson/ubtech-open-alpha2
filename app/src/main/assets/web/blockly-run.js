// Open Alpha2 — Blockly 直譯執行引擎。
//
// 設計原則：
//  1. 唔用 Blockly 內建 code generator 生成一段 JS 再 eval — 改用「行樹」直譯
//     (interpretBlock), 咁樣可以喺 wait/repeat 中途逐格 highlight, 亦可以隨時
//     `running = false` 安全中斷, 唔會有半行 JS 卡死喺 eval 入面嘅問題。
//  2. 每個「動作類」block (播放動作/TTS/LED/伺服...) 對應現有已驗證嘅 /api/* 端點,
//     直接 fetch, 唔重新定義呢層 API — 呼叫嘅係 index.html 已經有嘅 api() helper
//     (由 app.js 提供), 保證同「面板」分頁行為完全一致。
//  3. 事件 block (alpha_event_when) 唔喺主程式流程之內執行, 而係喺 workspace load
//     嗰陣就註冊做 WebSocket listener, 常駐監聽 — 呢個係事件驅動模型, 同「按 ▶ 執行」
//     嗰個線性 program 係兩回事, 可以同時存在。

(function () {

  // ------------------------------------------------------------------
  // 執行狀態
  // ------------------------------------------------------------------
  let running = false;
  let stopRequested = false;
  let workspace = null;
  const variables = new Map(); // 變數名稱 -> 值 (直譯器自己嘅 scope, 唔用 Blockly 內建 code-gen 嘅變數系統)
  const eventHandlers = []; // { eventType, varName, bodyBlockId }

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
    document.getElementById('runStatusText').textContent = isRunning ? '執行緊…' : '閒置';
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
      case 'alpha_event_field': {
        const obj = await evalValue(block.getInputTargetBlock('OBJ'));
        const key = block.getFieldValue('KEY');
        if (obj && typeof obj === 'object') return obj[key];
        return null;
      }
      case 'alpha_get_battery_level': {
        const r = await window.api('battery/status');
        return r && r.ok ? Math.round((r.level / r.scale) * 100) : null;
      }
      case 'alpha_get_battery_charging': {
        const r = await window.api('battery/status');
        return r && r.ok ? !!r.charging : null;
      }
      case 'alpha_get_wifi_ip': {
        const r = await window.api('wifi/status');
        return r && r.ok ? (r.ip || '') : '';
      }
      case 'alpha_get_status_field': {
        const r = await window.api('status');
        const field = block.getFieldValue('FIELD');
        return r && r.ok ? r[field] : null;
      }
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
        logLine('⚠ 未支援嘅數值 block 類型: ' + block.type, 'warn');
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
  async function playActionAndMaybeWait(name, wait, timeoutSeconds) {
    if (!wait) {
      logLine('▶ 播放動作 (不等待): ' + name);
      await window.api('action/play', { name: name });
      return;
    }
    logLine('▶ 播放動作: ' + name + ' (等待完成, 最多 ' + timeoutSeconds + ' 秒)');
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
      window.api('action/play', { name: name }).then(function (r) {
        if (!r || !r.ok) { cleanup(); resolve('api_failed'); }
      });
    });
    const result = await donePromise;
    if (result === 'timeout') logLine('⚠ 等待動作完成逾時: ' + name, 'warn');
    else if (result === 'api_failed') logLine('❌ 播放動作 API 呼叫失敗: ' + name, 'err');
    else logLine('✅ 動作播放完成: ' + name);
  }

  // ------------------------------------------------------------------
  // 語句執行：行一粒 statement block, 回傳「下一粒」由 caller (runSequence) 處理
  // ------------------------------------------------------------------
  async function execStatement(block) {
    highlight(block.id);
    const t = block.type;

    switch (t) {
      // ---------------- 動作 ----------------
      case 'alpha_action_play': {
        const name = block.getFieldValue('NAME');
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_play_builtin': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine('⚠ 未揀動作', 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_play_dropdown': {
        const name = block.getFieldValue('NAME');
        if (!name) { logLine('⚠ 未揀動作 (清單可能未載入)', 'warn'); return; }
        const wait = block.getFieldValue('WAIT') === 'true';
        const timeout = Number(block.getFieldValue('TIMEOUT'));
        await playActionAndMaybeWait(name, wait, timeout);
        return;
      }
      case 'alpha_action_stop':
        logLine('⏹ 停止動作');
        await window.api('action/stop');
        return;
      case 'alpha_action_wait_done': {
        const timeout = Number(block.getFieldValue('TIMEOUT')) * 1000;
        logLine('⏳ 額外等待動作完成 (最多 ' + block.getFieldValue('TIMEOUT') + ' 秒)…');
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
        logLine('💬 TTS[' + engine + ']: ' + text);
        const params = { text: String(text == null ? '' : text), engine: engine };
        if (voice) params.voice = voice;
        await window.api('speech/tts', params);
        return;
      }
      case 'alpha_speech_stop':
        logLine('⏹ 停止 TTS');
        await window.api('speech/stop');
        return;
      case 'alpha_speech_set_mic':
        logLine('🎙 麥克風擁有權 → ' + (block.getFieldValue('WAKE') === 'true' ? '機械人' : 'App'));
        await window.api('speech/set_mic', { wake: block.getFieldValue('WAKE') });
        return;
      case 'alpha_speech_start_asr':
        logLine('🎙 開始聆聽 (即時辨識)');
        await window.api('speech/start_asr');
        return;
      case 'alpha_speech_set_voice':
        logLine('🔈 設定 TTS 聲音: ' + block.getFieldValue('NAME'));
        await window.api('speech/set_voice', { name: block.getFieldValue('NAME') });
        return;
      case 'alpha_speech_set_language':
        logLine('🌐 設定辨識語言: ' + block.getFieldValue('LANG'));
        await window.api('speech/set_language', { lang: block.getFieldValue('LANG') });
        return;
      case 'alpha_speech_self_interrupt':
        logLine('✋ 自我打斷: ' + block.getFieldValue('ON'));
        await window.api('speech/self_interrupt', { on: block.getFieldValue('ON') });
        return;

      // ---------------- 伺服 ----------------
      case 'alpha_servo_one': {
        const id = block.getFieldValue('ID');
        const angle = block.getFieldValue('ANGLE');
        const time = block.getFieldValue('TIME');
        logLine('🦾 伺服 #' + id + ' → ' + angle + '° (' + time + 'ms)');
        await window.api('servo/one', { id: id, angle: angle, time: time });
        return;
      }
      case 'alpha_servo_all': {
        const anglesVal = await evalValue(block.getInputTargetBlock('ANGLES'));
        const time = block.getFieldValue('TIME');
        logLine('🦾 全部伺服 → [' + anglesVal + '] (' + time + 'ms)');
        await window.api('servo/all', { angles: String(anglesVal), time: time });
        return;
      }
      case 'alpha_servo_home': {
        const home = [120,120,120,120,120,120,120,65,145,140,120,120,175,95,100,120,120,120,120,120];
        const time = block.getFieldValue('TIME');
        logLine('🏠 全部伺服回中位 (' + time + 'ms)');
        await window.api('servo/all', { angles: home.join(','), time: time });
        return;
      }
      case 'alpha_servo_sonar':
        logLine('📡 聲納距離 → ' + block.getFieldValue('DIST'));
        await window.api('servo/sonar', { distance: block.getFieldValue('DIST') });
        return;

      // ---------------- LED ----------------
      case 'alpha_led_head': {
        const preset = block.getFieldValue('PRESET');
        logLine('💡 頭部LED: ' + preset);
        const params = { preset: preset };
        if (preset !== 'stop') {
          params.color = block.getFieldValue('COLOR');
          params.brightness = block.getFieldValue('BRIGHT');
        }
        await window.api('led/head/set', params);
        return;
      }
      case 'alpha_led_eye': {
        const preset = block.getFieldValue('PRESET');
        logLine('💡 眼睛LED: ' + preset);
        const params = { preset: preset };
        if (preset !== 'stop') {
          params.color = block.getFieldValue('COLOR');
          params.brightness = block.getFieldValue('BRIGHT');
        }
        await window.api('led/eye/set', params);
        return;
      }

      // ---------------- 感應/裝置 ----------------
      case 'alpha_head_noise':
        logLine('🔇 頭部降噪: ' + block.getFieldValue('ON'));
        await window.api('head/noise', { on: block.getFieldValue('ON') });
        return;
      case 'alpha_misc_request_uuid':
        logLine('🔎 查詢機械人 UUID');
        await window.api('misc/request_uuid');
        return;
      case 'alpha_misc_charge_play':
        logLine('🔌 充電時允許郁動: ' + block.getFieldValue('OPEN'));
        await window.api('misc/charge_play', { open: block.getFieldValue('OPEN') });
        return;
      case 'alpha_misc_power_save':
        logLine('🔋 省電模式: ' + block.getFieldValue('SAVE'));
        await window.api('misc/power_save', { save: block.getFieldValue('SAVE') });
        return;

      // ---------------- 相機 / 音效 ----------------
      case 'alpha_camera_snapshot': {
        logLine('📷 拍快照…');
        const r = await window.api('camera/snapshot');
        const varName = block.getFieldValue('VAR');
        variables.set(varName, r && r.ok ? r.jpegBase64 : null);
        logLine(r && r.ok ? '📷 已存入變數 ' + varName : '📷 拍攝失敗');
        return;
      }
      case 'alpha_camera_resolution': {
        const res = block.getFieldValue('RES').split('x');
        logLine('📷 設定解像度: ' + block.getFieldValue('RES'));
        await window.api('camera/resolution', { w: res[0], h: res[1] });
        return;
      }
      case 'alpha_audio_testtone':
        logLine('🔊 播放測試音');
        await window.api('audio/testtone');
        return;
      case 'alpha_audio_play':
        logLine('🔊 喇叭串流: ' + block.getFieldValue('ACT'));
        await window.api('audio/play/' + block.getFieldValue('ACT'));
        return;

      // ---------------- 流程控制 ----------------
      case 'alpha_wait_seconds': {
        const secs = Number(await evalValue(block.getInputTargetBlock('SECONDS'))) || 0;
        logLine('⏳ 等待 ' + secs + ' 秒');
        await sleep(secs * 1000);
        return;
      }
      case 'alpha_log': {
        const msg = await evalValue(block.getInputTargetBlock('MSG'));
        logLine('📝 ' + (msg === null || msg === undefined ? '' : String(msg)), 'user');
        return;
      }
      case 'alpha_stop_program':
        logLine('⏹ 程式主動停止', 'warn');
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
        logLine('⚠ 未支援嘅 block 類型: ' + t, 'warn');
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
        logLine('❌ 錯誤: ' + (e && e.message ? e.message : String(e)), 'err');
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
    const topBlocks = workspace.getTopBlocks(true).filter(function (b) {
      return b.type !== 'alpha_event_when' && !b.isCollapsed() && !b.disabled;
    });
    if (!topBlocks.length) {
      logLine('⚠ 冇可執行嘅 block (event block 唔算, 佢哋會自動常駐監聽)', 'warn');
      return;
    }
    running = true;
    stopRequested = false;
    setRunningUI(true);
    logLine('▶▶▶ 開始執行程式 (' + topBlocks.length + ' 條主線程序)', 'sys');
    try {
      for (const top of topBlocks) {
        if (stopRequested) break;
        await runSequence(top);
      }
    } catch (e) {
      if (!(e && e.__alphaFlow)) {
        logLine('❌ 執行期錯誤: ' + (e && e.message ? e.message : String(e)), 'err');
      }
    }
    running = false;
    const wasStopped = stopRequested;
    stopRequested = false;
    setRunningUI(false);
    workspace.highlightBlock(null);
    logLine(wasStopped ? '⏹ 程式已停止' : '✅ 程式執行完畢', 'sys');
  }

  function stopProgram() {
    stopRequested = true;
    logLine('⏹ 使用者按下停止', 'sys');
  }

  // ------------------------------------------------------------------
  // 事件驅動：掃描 workspace 入面所有 alpha_event_when, 註冊做 WS listener
  // ------------------------------------------------------------------
  function rewireEventHandlers() {
    eventHandlers.length = 0;
    if (!workspace) return;
    workspace.getTopBlocks(true).forEach(function (b) {
      if (b.type === 'alpha_event_when' && !b.disabled) {
        eventHandlers.push({
          eventType: b.getFieldValue('EVENT'),
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
        });
      }
    });
    logLine('🔗 已註冊 ' + eventHandlers.length + ' 個事件監聽 block', 'sys');
  }

  async function onWsEvent(evt) {
    window.__alphaFireEvent(evt); // 俾 alpha_action_wait_done 等 ad-hoc listener 用
    for (const h of eventHandlers) {
      if (h.eventType === evt.type && h.bodyBlock) {
        variables.set(h.varName, evt.data || {});
        highlight(h.hatBlock.id);
        try {
          await runSequence(h.bodyBlock);
        } catch (e) {
          if (!(e && e.__alphaFlow)) {
            logLine('❌ 事件處理錯誤 [' + evt.type + ']: ' + (e && e.message ? e.message : String(e)), 'err');
          }
        }
      }
    }
  }

  // 簡易 pub/sub, 俾 wait_done 呢類 ad-hoc 一次性 listener 用, 唔使全部塞入 eventHandlers。
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
        logLine('💾 已還原上次自動儲存嘅程式', 'sys');
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
    logLine('💾 已儲存做「' + name + '」', 'sys');
  }

  function loadNamedProgram(name) {
    const list = getSavedProgramList();
    if (list[name]) {
      loadXmlText(list[name].xml);
      logLine('📂 已載入「' + name + '」', 'sys');
    }
  }

  function deleteNamedProgram(name) {
    const list = getSavedProgramList();
    delete list[name];
    localStorage.setItem(STORAGE_KEY_LIST, JSON.stringify(list));
    refreshSavedProgramDropdown();
    logLine('🗑 已刪除「' + name + '」', 'sys');
  }

  function refreshSavedProgramDropdown() {
    const sel = document.getElementById('savedProgramSelect');
    if (!sel) return;
    const list = getSavedProgramList();
    const names = Object.keys(list).sort();
    sel.innerHTML = '<option value="">-- 已儲存嘅程式 --</option>' +
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
    logLine('⬇ 已匯出成檔案', 'sys');
  }

  function importXmlFile(file) {
    const reader = new FileReader();
    reader.onload = function () {
      try {
        loadXmlText(String(reader.result));
        logLine('⬆ 已由檔案匯入: ' + file.name, 'sys');
      } catch (e) {
        logLine('❌ 匯入失敗: ' + e.message, 'err');
      }
    };
    reader.readAsText(file);
  }

  // ------------------------------------------------------------------
  // 動作清單下拉：由 /api/action/list 抓返嚟, 填入 alpha_action_play_dropdown
  //
  // 分類前綴用返同一套白名單邏輯 (window.ALPHA_ACTION_CATEGORY_OF, 定義喺
  // blockly-actions-data.js), 即係話唔理機械人實際回傳嘅 type 字面值係咩 (數字定
  // 文字都好), 只要唔喺白名單 (1/2/3/4) 之內就自動顯示做「其他」分類, 唔使呢度
  // 認識任何具體嘅字串。
  // ------------------------------------------------------------------
  async function refreshActionDropdown() {
    logLine('🔄 正在抓取機械人動作清單…', 'sys');
    const r = await window.api('action/list');
    if (r && r.ok && Array.isArray(r.actions)) {
      window.__alphaActionOptions = r.actions.map(function (a) {
        const catKey = window.ALPHA_ACTION_CATEGORY_OF ? window.ALPHA_ACTION_CATEGORY_OF(a.type) : '';
        const catInfo = (window.ALPHA_ACTION_CATEGORIES || []).filter(function (c) { return c.key === catKey; })[0];
        const prefix = catInfo ? ('[' + catInfo.label + '] ') : '';
        const label = prefix + (a.nameCn || a.nameEn || a.id) + '  [' + a.id + ']';
        return [label, a.id];
      });
      if (!window.__alphaActionOptions.length) {
        window.__alphaActionOptions = [['(機械人回傳空清單)', '']];
      }
      logLine('✅ 已載入 ' + r.actions.length + ' 個動作', 'sys');
    } else {
      logLine('❌ 抓取動作清單失敗', 'err');
      window.__alphaActionOptions = [['(載入失敗)', '']];
    }
    // 強制所有現存嘅 dropdown block 重新渲染,顯示新選項
    if (workspace) {
      workspace.getBlocksByType('alpha_action_play_dropdown', false).forEach(function (b) {
        const field = b.getField('NAME');
        if (field && field.getOptions) {
          field.getOptions(false);
          field.forceRerender();
        }
      });
    }
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
    clearWorkspace: function () {
      if (workspace) workspace.clear();
    },
    getVariablesSnapshot: function () {
      return Object.fromEntries(variables);
    },
  };
})();
