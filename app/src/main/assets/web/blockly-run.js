// Open Alpha2 — Blockly 直譯執行引擎。
//
// 設計原則：
//  1. 唔用 Blockly 內建 code generator 生成一段 JS 再 eval — 改用「行樹」直譯
//     (interpretBlock), 咁樣可以喺 wait/repeat 中途逐格 highlight, 亦可以隨時
//     `running = false` 安全中斷, 唔會有半行 JS 卡死喺 eval 入面嘅問題。
//  2. 每個「動作類」block (播放動作/TTS/LED/伺服...) 對應現有已驗證嘅 /api/* 端點,
//     直接 fetch, 唔重新定義呢層 API — 呼叫嘅係 index.html 已經有嘅 api() helper
//     (由 app.js 提供), 保證同「面板」分頁行為完全一致。
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
  async function playActionAndMaybeWait(name, wait, timeoutSeconds) {
    if (!wait) {
      logLine(t('run_action_play_nowait', { name: name }));
      await window.api('action/play', { name: name });
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
      // .catch(...) 唔可以少: window.api() 內部理論上已經接住曬 fetch 嘅 network error
      // (例如自簽 HTTPS 證書未被瀏覽器信任時, fetch() 會拋 TypeError: Failed to fetch),
      // 但為咗唔靠呢一層假設, 呢度都要有自己嘅 .catch, 否則萬一有意外拋出, 呢個
      // .then() 冇接住嘅 rejection 會逸出做 unhandled promise rejection, 喺頁面度
      // 彈紅色 error banner, 但個動作播放狀態 (donePromise) 就會卡住唔鬱 (冇 resolve)。
      window.api('action/play', { name: name }).then(function (r) {
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
        const params = { text: String(text == null ? '' : text), engine: engine };
        if (voice) params.voice = voice;
        await window.api('speech/tts', params);
        return;
      }
      case 'alpha_speech_stop':
        logLine(t('run_tts_stop'));
        await window.api('speech/stop');
        return;
      case 'alpha_speech_set_mic':
        logLine(t('run_mic_ownership', { owner: (block.getFieldValue('WAKE') === 'true' ? t('run_mic_owner_robot') : t('run_mic_owner_app')) }));
        await window.api('speech/set_mic', { wake: block.getFieldValue('WAKE') });
        return;
      case 'alpha_speech_start_asr':
        logLine(t('run_start_listening'));
        await window.api('speech/start_asr');
        return;
      case 'alpha_speech_set_voice':
        logLine(t('run_set_voice', { name: block.getFieldValue('NAME') }));
        await window.api('speech/set_voice', { name: block.getFieldValue('NAME') });
        return;
      case 'alpha_speech_set_language':
        logLine(t('run_set_lang', { lang: block.getFieldValue('LANG') }));
        await window.api('speech/set_language', { lang: block.getFieldValue('LANG') });
        return;
      case 'alpha_speech_self_interrupt':
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
        await window.api('servo/one', { id: id, angle: angle, time: time });
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
        const csv = angles.join(',');
        logLine(t('run_servo_all', { csv: csv, time: time }));
        await window.api('servo/all', { angles: csv, time: time });
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
        await window.api('servo/all', { angles: home.join(','), time: time });
        return;
      }
      case 'alpha_servo_sonar':
        logLine(t('run_sonar_distance', { dist: block.getFieldValue('DIST') }));
        await window.api('servo/sonar', { distance: block.getFieldValue('DIST') });
        return;

      // ---------------- LED ----------------
      case 'alpha_led_head': {
        const preset = block.getFieldValue('PRESET');
        logLine(t('run_led_head', { preset: preset }));
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
        logLine(t('run_led_eye', { preset: preset }));
        const params = { preset: preset };
        if (preset !== 'stop') {
          params.color = block.getFieldValue('COLOR');
          params.brightness = block.getFieldValue('BRIGHT');
        }
        await window.api('led/eye/set', params);
        return;
      }
      case 'alpha_led_mouth': {
        const mode = block.getFieldValue('MODE');
        if (mode === 'off') {
          logLine(t('run_led_mouth_off'));
          await window.api('led/mouth/set', { preset: 'off' });
        } else {
          const speed = block.getFieldValue('SPEED');
          logLine(t('run_led_mouth_breathe', { speed: speed }));
          await window.api('led/mouth/set', { speed: speed });
        }
        return;
      }

      // ---------------- 感應/裝置 ----------------
      case 'alpha_sensor_accel_toggle':
        logLine(t('run_accel_toggle', { on: block.getFieldValue('ON') }));
        await window.api('accelerometer/set', { on: block.getFieldValue('ON') });
        return;
      case 'alpha_sensor_sonar_toggle': {
        const on = block.getFieldValue('ON') === 'true';
        const dist = on ? block.getFieldValue('DIST') : '0';
        logLine(t('run_sonar_toggle', { on: block.getFieldValue('ON'), thresholdNote: (on ? t('run_sonar_toggle_threshold', { dist: dist }) : '') }));
        await window.api('servo/sonar', { distance: dist });
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
    const EVENT_HAT_TYPES = ['alpha_event_accel_threshold', 'alpha_event_sonar_triggered'];
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
      }
    });
    logLine(t('run_handlers_registered', { accel: accelHandlers.length, sonar: sonarHandlers.length }), 'sys');
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
    refreshSavedProgramDropdown: refreshSavedProgramDropdown, // 俾 blockly-i18n.js 切語言嗰陣攞返嚟用, 令 "-- 已儲存嘅程式 --" placeholder 跟住重新 render
    clearWorkspace: function () {
      if (workspace) workspace.clear();
    },
    getVariablesSnapshot: function () {
      return Object.fromEntries(variables);
    },
  };
})();
