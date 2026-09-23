// Open Alpha2 — Blockly 直譯執行引擎。
//
// 設計原則：
//  1. 不用 Blockly 內建 code generator 生成一段 JS 再 eval — 改用「行樹」直譯
//     (interpretBlock), 這樣可以在 wait/repeat 中途逐格 highlight, 亦可以隨時
//     `running = false` 安全中斷, 不會有半行 JS 卡死在 eval 裡面的問題。
//  2. 每個「動作類」block (播放動作/TTS/LED/伺服...) 對應現有已驗證的 /api/* 端點,
//     經型別化 Alpha2Api.* wrapper 呼叫 (api-client.js, 內建 assertEnum/assertRange,
//     同後端 ApiValidator 同一套規則), 保證同「面板」分頁行為完全一致。
//  3. 事件 block (alpha_event_accel_threshold / alpha_event_sonar_triggered /
//     alpha_event_pir_triggered) 不在主程式流程之內執行, 而是在 workspace
//     load 當時就註冊做 WebSocket listener,
//     常駐監聽 — 這個是事件驅動模型, 同「按 ▶ 執行」那個線性 program 是兩回事,
//     可以同時存在。

(function () {

  // ------------------------------------------------------------------
  // 執行狀態
  // ------------------------------------------------------------------
  let running = false;
  let stopRequested = false;
  let workspace = null;
  const variables = new Map(); // 變數名稱 -> 值 (直譯器自己的 scope, 不用 Blockly 內建 code-gen 的變數系統)
  const accelHandlers = []; // { axis, cmp, threshold, varName, bodyBlock, hatBlock }
  const sonarHandlers = []; // { varName, bodyBlock, hatBlock, wasTriggered }
  const pirHandlers = []; // { state: 'detected'|'cleared', varName, bodyBlock, hatBlock, wasTriggered }

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
      // 用短間隔輪詢 stopRequested, 這樣「停止」按鈕先可以在 wait 中途即時生效,
      // 而不用等整個 setTimeout 完先檢查。
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

  // ---- 動作播放 ----

  // ---- 伺服 / 舵機 ----
  // Alpha2: servo/one { id, angle, time }, servo/all { angles: "a,b,c,..", time }
  async function servoOneAdapter(id, angle, time) {
    await Alpha2Api.servoOne({ id: id, angle: angle, time: time });
  }
  async function servoAllAdapter(angles, time) {
    // angles: 長度 20 的 number 陣列, index 0 對應 servo id 1。
    await Alpha2Api.servoAll({ angles: angles.join(','), time: time });
  }

  // ---- LED ----
  // led/head/set 同 led/eye/set 用「preset (long/flash/breathe/chase/dual/stop)
  // + color + brightness」一個 endpoint 包完所有效果。
  async function ledHeadAdapter(preset, color, brightness) {
    const params = { preset: preset };
    if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
    await Alpha2Api.ledHeadSet(params);
  }
  async function ledEyeAdapter(preset, color, brightness) {
    const params = { preset: preset };
    if (preset !== 'stop') { params.color = color; params.brightness = brightness; }
    await Alpha2Api.ledEyeSet(params);
  }
  // 嘴部 LED：僅得「off / breathe(speed)」兩態 (沒有 solid-on)。
  async function ledMouthAdapter(mode, speed) {
    if (mode === 'off') await Alpha2Api.ledMouthSet({ preset: 'off' });
    else await Alpha2Api.ledMouthSet({ speed: speed });
  }

  // ---- 語音 TTS ----
  // speech/tts 得 android 才會響：nuance/iflytek 經機身 speech_startTTS
  // 恒回 NOT_INIT（無 alpha2services，見 RobotStub）。舊存檔可能還帶住
  // 那兩個值，這裡一律轉 android＋warn，不靜默吞（VOICE 照傳，後端 android
  // 分支無視）。
  async function speechTtsAdapter(text, engine, voice) {
    if (engine !== 'android') {
      console.warn('[blockly] TTS engine "' + engine + '" 已死，轉用 android');
      engine = 'android';
    }
    const params = { text: text, engine: engine };
    if (voice) params.voice = voice;
    await Alpha2Api.speechTts(params);
  }

  // ------------------------------------------------------------------
  // 值運算：拿一個 value-input block 的結果 (數字/文字/布林/物件皆可)
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
        // procedures_callreturn 同其他未特別支援的 value block: 嘗試用變數 getter 邏輯行為
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
  // 關鍵次序: 一定要「先掛好 action_stop 的 listener, 先至真正送出 /api/action/play」。
  // 如果反過來 (先送 API 先掛 listener), 遇到一個好快播完的動作 (甚至比 HTTP round-trip
  // 還快), 個 action_stop event 可能在 listener 掛好之前就已經到了 WebSocket, 這樣就會
  // 遺漏, 卡到個 timeout 先放行 —— 這個是經典的 setup-before-fire race condition。
  //
  // 另外用 evt.data.name 同送出的 name 做精準匹配, 而不是「隨便收到一個 action_stop
  // 就當自己那個播完」—— 如果程式裡面有第二條並行的事件驅動 block 在這段時間都觸發了
  // 另一個動作, 盲目匹配就會提早誤判「完成」。
  //
  // 2026-08 bugfix (用家回報「整動作會 crash」): 「等待完成」選「不等」那個分支
  // 之前完全沒有任何序列化 —— 一路排下來的 alpha_action_play block (或者 loop 裡面
  // 逐格都選「不等」), 個個都是「送出遷就即刻放行落下一格」, 令幾個 action/play
  // request 可以在幾 ms 之內連環送到 MainActivity#handleApi()。而
  // AlphaActionServiceUtil.playActionName() 裡面的 AIDL call 是一個會真正
  // block 住等 UBTECH 機身側 IActionService 回應的 Binder call —— 這個
  // service 底層對應的是實體馬達, 沒有可能同一時間執行多過一個動作。用 logcat 追查
  // 過一次真實個案 (見對話紀錄): 6 個 action/play request 在 15ms 內連環送到,
  // 之後整個 app 的 HttpServer 就再沒有任何回應 log, 一直到用家自己強制關 app 為止
  // —— 就是其中一個 AIDL call 卡死了沒有返, 卡住了個共用的 Binder thread,
  // 連累埋之後所有 AIDL 呼叫 (不止 action, 連 servo/LED 都一齊不動得), 用家會
  // 看來好似「整個 app 死了」。
  //
  // 修法: 不理 WAIT 選「等」定「不等」, 一律用這個 module-level 的
  // actionBusyPromise 做序列化閘 —— 保證同一時間僅得一個 action/play 在途。
  //
  // 2026-08 第二次 bugfix (見對話紀錄的 logcat: 兩個 action/play 連環撞到
  // server 個 "API_ERROR_BUSY"): 這個閘原本的設計僅等到 HTTP round-trip
  // 本身回來就放行 (「不等」個 block 不應該等整個動作播完先動落一格, 這個
  // 想法本身沒有錯)。但 server 端的「busy」定義已經變了做「這個動作真正
  // 正在播, 直到 onStopActionResult() 先解鎖」, 同這裡「HTTP 一 round-trip
  // 完就當不 busy」不一致 —— 結果就是第二個 action/play 的 HTTP request 一早
  // 過了第一個的 HTTP round-trip, 但機身實際還在這裡正在播第一個動作, server
  // 就會拒絕。
  //
  // 現在改做: client 端的「busy」都要對齊做「等到這個動作真正完成 (收到
  // action_stop event) 先算」—— 不理個 block 本身是否選了「不等」, gate 內部
  // 都會悄悄地等 action_stop (或者一個保守的 safety timeout, 見下面), 先至
  // 放行給下一個排隊的 action/play 送出。這個不改變「不等」個 block 本身的
  // 用家可見行為 (playActionAndMaybeWait 的 !wait 分支依然是送了就即刻
  // return, 不會令使用者要多等), 僅令幾個 action/play 之間不會再打交。
  let actionBusyPromise = Promise.resolve();

  // 保守的 safety timeout —— 如果 action_stop event 因為某啲原因冚不到 (例如
  // WebSocket 斷了、或者這個動作的 callback 本身有 bug 沒有 fire), gate 都不可以
  // 永久卡死, 否則之後整個程式所有 action/play 都會停完。20 秒已經比
  // playActionAndMaybeWait() 自己個 15 秒 timeout 還長, 保證真正需要的等待
  // 一定會由那邊先放行, 這個純粹是最後一道保險。
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
      return Alpha2Api.actionPlay({ name: name });
    });
    // 下一個排隊的 action/play 要等「這個動作真正播完」先可以送出, 不是僅
    // 等 HTTP round-trip ——見上面大段註解。無論今次 API 呼叫成功/失敗/收到
    // action_stop/等到 timeout, 都一定要放行 (.catch 吞完錯誤), 否則一次
    // 失敗就會永久卡死整個隊。
    actionBusyPromise = gated
      .then(function (r) {
        // API 本身都送不出 (network fail 或者 server 話 busy) 就沒有必要再等
        // action_stop, 它根本不會來 —— 即刻放行, 等下一個 block 有機會送出
        // (可能上一個「busy」其實是一場誤會, 例如網絡短暫不穩)。
        if (!r || !r.ok) return;
        return waitForActionStopOrTimeout();
      })
      .catch(function () { /* 見上面註解: 吞錯誤, 僅用來放行 */ });
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
      // listener 掛好之後先送出真正的 API request。
      // .catch(...) 不可以少: window.api() 內部理論上已經接住曬 fetch 的 network error,
      // 但為了不靠這一層假設, 這裡都要有自己的 .catch, 否則萬一有意外拋出, 這個
      // .then() 沒有接住的 rejection 會逸出做 unhandled promise rejection, 在頁面度
      // 彈紅色 error banner, 但個動作播放狀態 (donePromise) 就會卡住不動 (沒有 resolve)。
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
      // 2026-08 更新: alpha_action_play_builtin 拆了做獨立 block (基本/跳舞
      // /故事/瑜伽/其他), 但每粒的 field 結構 (NAME/WAIT/TIMEOUT) 完全一樣,
      // 不用分開寫 case, 一個 case 蓋完所有 type 就夠。
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
        await Alpha2Api.actionStop();
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
        await Alpha2Api.speechStop();
        return;
      case 'alpha_speech_set_mic':
        logLine(t('run_mic_ownership', { owner: (block.getFieldValue('WAKE') === 'true' ? t('run_mic_owner_robot') : t('run_mic_owner_app')) }));
        await Alpha2Api.speechSetMic({ wake: block.getFieldValue('WAKE') });
        return;
      // 2026-09 移除: alpha_speech_start_asr / alpha_speech_set_voice /
      // alpha_speech_set_language / alpha_speech_self_interrupt —— 對應後端
      // endpoint 已經不存在 (404)，block 定義、toolbox、i18n 一齊拿走。
      // 2026-08 更新: 電話鈴聲 / 通知鈴聲已經拆做兩粒獨立 block (以前是一粒
      // alpha_speech_ringtone + TYPE dropdown), type 現在寫死在這兩個 case。
      // 播放現在送 title (不再送 index), call 新增的 /api/audio/ringtones/play_by_title
      // (見 blockly-blocks.js makeRingtoneBlock() 註解: title 對應機制同相機分頁
      // 快門聲一樣穩陣, 不用理 RingtoneManager cursor index 排序)。
      //
      // 2026-08 再更新: 加了 DURATION 這個 value input —— 之前個 block 播完成首
      // 系統鈴聲先返回 (無得控制播多久, 亦都是之前「不斷重覆聽起來不停」問題的源頭
      // 之一), 現在播放之後, 如果 duration > 0, 就 sleep 夠鐘再主動 call
      // /api/audio/ringtones/stop 停它 (跟 MainActivity.playRingtoneUri() 現在
      // 有 currentRingtonePlayer 這個 field 正在追蹤正在播那個, stop 可以隨時安全咁
      // 打斷)。填 0 = 不設自動停止時間, 播到整個音效檔案自然完為止 (舊行為)。
      // toolbox 裡面電話鈴聲預設 10 秒, 通知鈴聲預設 5 秒。
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
        await Alpha2Api.audioRingtonesPlayByTitle({ type: type, title: title });
        if (duration > 0) {
          await sleep(duration * 1000);
          // 就算 stopRequested (用家按了「停止程式」), 都要停掉個鈴聲,
          // 不是它會繼續在機械人度播下去 (Java 個 MediaPlayer 不會因為
          // 這個網頁 loop 停了就自動停)。
          await Alpha2Api.audioRingtonesStop();
        }
        return;
      }
      case 'alpha_speech_ringtone_stop':
        logLine(t('run_ringtone_stop'));
        await Alpha2Api.audioRingtonesStop();
        return;

      // ---------------- 伺服 ----------------
      // 2026-08 更新: alpha_servo_one 拆了做 5 粒獨立 block (頭/右手/左手/右腳
      // /左腳), 但五粒的 field 結構 (ID/ANGLE/TIME) 完全一樣, 不用分開寫 case,
      // 一個 case 蓋完 5 個 type 就夠。
      case 'alpha_servo_one_head':
      case 'alpha_servo_one_right_arm':
      case 'alpha_servo_one_left_arm':
      case 'alpha_servo_one_right_leg':
      case 'alpha_servo_one_left_leg': {
        const id = block.getFieldValue('ID');
        let angle = Number(block.getFieldValue('ANGLE'));
        // 執行層再 clamp 多一次做保險 — field validator 已經在 UI 層擋了大部分
        // 情況, 但透過「匯入 .xml」載入的程式可能繞過了 UI (XML 裡面隨便打
        // 一個超出範圍的數值), 所以真正送出 API 之前一定要再夾一次。
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
        // CSV 字串裡面 20 個數值逐顆對應 #1~#20 校準表 clamp 一次, 不理這串
        // CSV 來自哪粒 block (helper 組合還是直接打字/變數), 送出前都要是安全值。
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
        // 不再在這裡另外 hardcode 一份可能會出錯的副本。
        const cal = window.ALPHA_SERVO_CALIBRATION;
        const home = [];
        for (let i = 1; i <= 20; i++) home.push(cal && cal[i] ? cal[i].home : 120);
        const time = block.getFieldValue('TIME');
        logLine(t('run_servo_home', { time: time }));
        await servoAllAdapter(home, time);
        return;
      }
      case 'alpha_servo_sonar':
        logLine(t('run_sonar_distance', { dist: block.getFieldValue('DIST') }));
        await Alpha2Api.servoSonar({ distance: block.getFieldValue('DIST') });
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
      case 'alpha_sensor_accel_toggle':
        logLine(t('run_accel_toggle', { on: block.getFieldValue('ON') }));
        await Alpha2Api.accelerometerSet({ on: block.getFieldValue('ON') });
        return;
      case 'alpha_sensor_sonar_toggle': {
        const on = block.getFieldValue('ON') === 'true';
        const dist = on ? block.getFieldValue('DIST') : '0';
        logLine(t('run_sonar_toggle', { on: block.getFieldValue('ON'), thresholdNote: (on ? t('run_sonar_toggle_threshold', { dist: dist }) : '') }));
        await Alpha2Api.servoSonar({ distance: dist });
        return;
      }
      case 'alpha_sensor_pir_toggle': {
        const on = block.getFieldValue('ON');
        logLine(t('run_pir_toggle', { on: on }));
        await Alpha2Api.pirSet({ on: on });
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
          // flow === 'CONTINUE' 或 null: 直接入下一輪, 沒有特別動作要做
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
        // break/continue: 用簡化模型, 直接拋出特殊訊號給 runSequence/loop 接住。
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
          // break/continue 冒出去給最近的迴圈接住;這裡用最簡單方式 — 直接向上拋,
          // runProgram() 頂層接不到就當停止, 每個 loop-case 分支已經隱含用回
          // runSequence 的呼叫堆疊, 冒出一層就是跳出那層 loop 的 body。
          throw e;
        }
        logLine(t('run_error', { err: (e && e.message ? e.message : String(e)) }), 'err');
      }
      block = block.getNextBlock();
    }
  }

  // controls_repeat_ext / whileUntil / for 用的 runSequence 需要接住 break/continue,
  // 包一層 try/catch 令它們在該迴圈中斷,而不是整個程式炸了。
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
    // debounce 300ms 先執行 (見 AlphaBlockly.init), 目的是避免一連串拖拽/
    // undo/redo 觸發太多次掃描。但如果用家剛剛先拖低一粒 accel/sonar 事件
    // block, 跟著手快即刻按「執行」, 個 300ms debounce 未到, accelHandlers/
    // sonarHandlers 就還是之前(沒有這粒新 block)的舊狀態 —— 結果個 hat block
    // 完全沒有被註冊到, 「執行紀錄」會見到「已註冊 0 個」, 一直要等到下一次
    // workspace 有改動 (例如用家動一下個 block) 才會補做一次 rewire。
    // 現在在執行程式之前, 強制同步做多一次 rewireEventHandlers(), 確保
    // accel/sonar handler 一定反正在映畫布上最新狀態, 不用靠彩。
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
  // 事件驅動：掃描 workspace 裡面所有 alpha_event_accel_threshold /
  // alpha_event_sonar_triggered / alpha_event_pir_triggered, 註冊做 WS listener
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
          running: false, // re-entrancy guard: 這個 handler 的 DO 序列是否正在跑
        });
      } else if (b.type === 'alpha_event_sonar_triggered' && !b.disabled) {
        sonarHandlers.push({
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: false, // 邊緣觸發用: 上次收到的 triggered 狀態
          running: false, // re-entrancy guard: 這個 handler 的 DO 序列是否正在跑
        });
      } else if (b.type === 'alpha_event_pir_triggered' && !b.disabled) {
        pirHandlers.push({
          state: b.getFieldValue('STATE'), // 'detected' 或 'cleared' —— 用戶選哪個方向先觸發
          varName: b.getFieldValue('VAR'),
          bodyBlock: b.getInputTargetBlock('DO'),
          hatBlock: b,
          wasTriggered: null, // 邊緣觸發用: 上次收到的 triggered 狀態; null=未收過任何 PIR 事件, 不算邊緣
          running: false, // re-entrancy guard: 這個 handler 的 DO 序列是否正在跑
        });
      }
    });
    logLine(t('run_handlers_registered', { accel: accelHandlers.length, sonar: sonarHandlers.length, pir: pirHandlers.length }), 'sys');
  }

  async function onWsEvent(evt) {
    window.__alphaFireEvent(evt); // 給 alpha_action_wait_done 等 ad-hoc listener 用
    // 加速度計：每次收到 accel 事件, 逐個已註冊的門檻 block 檢查一次, 讀數
    // (含重力分量, 見 MainActivity onSensorChanged 註解) 的絕對值過了門檻就
    // 觸發。
    //
    // 2026-08 bugfix: 之前這裡沒有任何防抖/re-entrancy guard —— accel 事件本身
    // 高頻 (~150-250ms 一次), 而 DO 裡面隨便一個 servo 動作就成 1 秒以上。
    // 結果一個 body 還在這裡 await runSequence() 正在跑, 下一個/幾個 accel 事件
    // 又已經到了, 而個 for-loop 沒有擋住, 會即刻再 call 多次 runSequence(),
    // 變成同一個 hatBlock 有多個 sequence 同時間疊住跑, 看來就好似「動作會
    // 不斷重複」。這個不關「沒有邊緣觸發」事 (accel 本身就是「持續超過門檻就
    // 反覆觸發」的語意, 不似 sonar 有明確的「剛剛先偵測到」一次性語意) ——
    // 真正問題是沒有擋住重疊執行。遵循 Scratch runtime 個標準做法: 同一個
    // hat block 同一時間僅給它正在跑一份 sequence, 上一輪未完之前, 新來的
    // 觸發事件直接跳過 (skip), 不會 queue 起或者疊加。
    if (evt.type === 'accel' && evt.data) {
      for (const h of accelHandlers) {
        if (!h.bodyBlock) continue;
        if (h.running) continue; // 上一輪 DO 還未跑完, 這次觸發直接跳過
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
    // 聲納：sonar_obstacle 事件本身在 MainActivity 側已經 debounce 過 (連續同
    // 狀態的 frame 不會重複 publish LED 開關, 但事件本身可能仍然逐 frame 送),
    // 這裡用 wasTriggered 做「由未觸發變觸發」的邊緣偵測, 令「偵測到障礙」
    // 這個語意是「剛剛先偵測到」, 不會物件持續在門檻範圍裡面就不斷重複執行。
    //
    // 2026-08 bugfix: 單靠邊緣偵測不夠 —— 如果物件反覆進出門檻距離 (例如在
    // 門檻邊緣徘徊), wasTriggered 會反覆 false→true, 每次都是一個新的
    // 「edge」, 一樣會在上一輪 DO 未跑完之前又觸發多次, 造成同一個 hatBlock
    // 有多個 sequence 疊住跑。加回同 accel 一樣的 running guard。
    if (evt.type === 'sonar_obstacle' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of sonarHandlers) {
        if (!h.bodyBlock) continue;
        const edge = triggeredNow && !h.wasTriggered;
        h.wasTriggered = triggeredNow;
        if (!edge) continue;
        if (h.running) continue; // 上一輪 DO 還未跑完, 這次觸發直接跳過
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
    // PIR 人體感應器: alpha2_pir_state 事件 payload 僅 {triggered: true/false}
    // (true=偵測到人, false=偵測不到人/離開), 見 RobotEventReceiver 個
    // registerAlpha2PirAlertListener 附近 comment。同 sonar 一樣用邊緣偵測,
    // 但用戶要求「偵測到/偵測不到」兩個方向都要給用家獨立選 (STATE 欄位),
    // 不似 sonar 僅「由遠變近」一個方向 —— 所以這裡要分開看 detected
    // (false→true 的邊) 還是 cleared (true→false 的邊) 才對那顆 hat block
    // 自己選的方向。
    //
    // wasTriggered 初始值用 null (不是 false), 用來分辨「這個 handler 剛剛
    // 先註冊, 還未收過任何 PIR 事件」同「上次收到的是『沒有人』狀態」—— 如果
    // 不這麼做, 第一個收到的事件假如剛剛好是 triggered=false, 會被誤判做一次
    // 「由 undefined 變 false」的 cleared 邊緣, 一開始執行就無啦啦觸發一次
    // 「偵測不到人」個 block, 用家會覺得莫名其妙。
    if (evt.type === 'alpha2_pir_state' && evt.data) {
      const triggeredNow = !!evt.data.triggered;
      for (const h of pirHandlers) {
        if (!h.bodyBlock) continue;
        const hadPrior = h.wasTriggered !== null;
        const edgeDetected = h.state === 'detected' && triggeredNow && hadPrior && !h.wasTriggered;
        const edgeCleared = h.state === 'cleared' && !triggeredNow && hadPrior && h.wasTriggered;
        h.wasTriggered = triggeredNow;
        if (!edgeDetected && !edgeCleared) continue;
        if (h.running) continue; // 上一輪 DO 還未跑完, 這次觸發直接跳過
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

  // 簡易 pub/sub, 給 wait_done 這類 ad-hoc 一次性 listener 用, 不用全部塞入 accelHandlers/sonarHandlers。
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
    // 2026-09-09：逐顆 createElement＋textContent（之前字串拼 innerHTML，
    // 程式名來自 localStorage/匯入檔，stored XSS）。
    while (sel.firstChild) sel.removeChild(sel.firstChild);
    const ph = document.createElement('option');
    ph.value = "";
    ph.textContent = t('run_saved_program_placeholder');
    sel.appendChild(ph);
    names.forEach(function (n) {
      const opt = document.createElement('option');
      opt.value = n;
      opt.textContent = n;
      sel.appendChild(opt);
    });
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
  // 動作清單下拉：由 /api/action/list 抓回來, 填入 alpha_action_play_dropdown
  //
  // 分類前綴用 action id 去查 window.ALPHA_ACTION_CATEGORY_OF (定義在
  // blockly-actions-data.js, 由 action_classified.txt 的 id -> main 對照表建構)。
  //
  // 注意: 這裡一定要用 a.id, 不可以用 a.type —— 機械人 /api/action/list 回傳的
  // "type" 是機身 firmware 自己的內部動作類型編號 (跟 IAlphaActionListListener
  // 個 4 欄 id/type/nameCn/nameEn 對應), 和我們在 action_classified.txt 人手分類
  // 出來的 4 大分類 (basic/dance/story/yoga) 是完全獨立、沒有關係的兩套編號,
  // 碰巧純屬巧合。之前舊版靠 type 數字做白名單推斷分類, 表面上部分「啱」都是
  // 因為兩套編號剛巧在某啲數值範圍重疊, 不可靠。
  // ------------------------------------------------------------------
  async function refreshActionDropdown() {
    logLine(t('run_fetching_action_list'), 'sys');
    const r = await Alpha2Api.actionList();
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
    // 強制所有現存的 dropdown block 重新渲染,顯示新選項。
    //
    // ⚠ 這裡一定要連 setValue 在一起做, 不可以僅 getOptions()+forceRerender():
    // 個 field 之前 (未拿清單前) 存住的值好可能是 fallback 選項的 '' (就是
    // [['(未載入...)', '']] 那個 value), 而現在拿到的新清單裡面已經沒有這個 ''
    // value 這一項——變成「field 目前存住的值,在 field 自己個 menuGenerator
    // 現在會返的 options 裡面找不到」這種 value/options 不一致的狀態。這個狀態
    // 底下 Blockly 的 dropdown 會怎麼按都沒有反應 (曾經實測出現的 bug), 看來整個
    // dropdown 壞了一樣。所以拿到新清單之後, 如果個 field 現存的值不在新
    // options 裡面, 要主動 setValue 去新清單第一項, 令個 field 隨時都處於
    // 「目前值 ∈ 目前 options」這個一致狀態。
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

  // 2026-08 更新: 系統鈴聲清單現在靜態內嵌在 blockly-ringtone-data.js (由實機
  // adb 抓一次, 見該檔頭註解), 不再在這裡即時查 /api/audio/ringtones/list —
  // refreshRingtoneDropdown() 這個 function 同工具箱那顆「拿鈴聲清單」按鈕已經
  // 一齊移除, 見 blockly.html。

  // ------------------------------------------------------------------
  // 剪貼/復原/收起側欄這兩組按鈕 —— 抄自 NuwaRobotics Code Lab, 用 Blockly 官方的
  // Blockly.ComponentManager + Blockly.uiPosition (IPositionable 介面) 重寫,
  // 同垃圾桶 (Trashcan) / 縮放按鈕 (ZoomControls) 用回完全同一套定位管線：
  // WorkspaceSvg 內部每次 resize 都會拿完所有已註冊的 POSITIONABLE component,
  // 按 weight 由細到大逐個 call .position(uiMetrics, alreadyPositionedRects),
  // 每個 call 完之後取回它 .getBoundingRectangle() 加入 alreadyPositionedRects
  // 度, 等下一個 (weight 更大的) component 定位當時可以自動避開它 —— 這個
  // 就是為什麼垃圾桶/縮放按鈕之間永遠不會重疊的原因, 現在我們自己的按鈕都用回
  // 這一套, 所以永遠都正在會隨它們, 不會再移位。
  //
  // 之前試過兩次用獨立 HTML <div> + CSS position:absolute + JS 度
  // getBoundingClientRect() 量度 Blockly 垃圾桶現在在哪裡追 —— 但 Blockly
  // 內部個位置公式 (uiPosition.getStartPositionRect/bumpPositionRect) 本身
  // 都有不少因素 (scrollbar 有沒有、toolbox 在哪裡、RTL) 會影響實際數值, 怎麼追都都會
  // 慢半拍或者算錯, 現在改用回 Blockly 官方機制才是真正治本的做法。
  //
  // Blockly.utils.dom.createSvgElement(tag, attrs, parent) 這個 helper 同
  // Blockly 自己 Trashcan/ZoomControls 起 DOM 正在用的是同一個 function
  // (在 minified source 裡確認過, 對應 Blockly.utils.dom.createSvgElement)。

  // 2026-09: 對齊 Code Lab 同款實心 fill icon —— path data 由
  // codelab.nuwarobotics.com 抽出 (index bundle 內嵌 SVG sprite + chunk-843ccf1e):
  // undo/redo 是 16x16 viewBox, cut/copy/paste/center/zoom 是 25x25 viewBox。
  // 每個 icon 記低自己原本的 viewBox 尺寸 (vb), 畫出來當時一律縮放到
  // FAB_ICON_BOX (25px, 同 Code Lab 在 42px 圓裡面畫 25px icon 一致) 再置中,
  // 所以不同 viewBox 的 icon 睇落一樣大。paste/center/zoom-in/zoom-out 原本是
  // 兩個 subpath (主體 + 細節/圓環), 串埋一條 d 畫, 配 fill-rule:evenodd 保證
  // 圓環這類鏤空形狀正確 (nonzero 會填實變實心餅)。
  const FAB_ICON_BOX = 25;
  const EDIT_FAB_ICONS = {
    undo: { vb: 16, d: 'M11.758 5.345H2.484l3.121-3.11a.721.721 0 0 0 0-1.023.729.729 0 0 0-1.028 0L.214 5.557a.722.722 0 0 0 .03 1.053l4.364 3.863a.73.73 0 0 0 1.027-.061.722.722 0 0 0-.061-1.023L2.641 6.793h9.117c1.537 0 2.787 1.516 2.787 3.38 0 1.863-1.25 3.379-2.787 3.379H8.263a.726.726 0 0 0-.727.724c0 .4.325.724.727.724h3.495C14.098 15 16 12.834 16 10.172c0-2.662-1.903-4.827-4.242-4.827' },
    redo: { vb: 16, d: 'M15.787 5.557l-4.364-4.345a.73.73 0 0 0-1.028 0 .722.722 0 0 0 0 1.025l3.122 3.108H4.243C1.903 5.345 0 7.51 0 10.172S1.903 15 4.243 15h3.494a.726.726 0 0 0 .728-.724c0-.4-.326-.724-.728-.724H4.243c-1.538 0-2.788-1.516-2.788-3.38 0-1.862 1.25-3.379 2.788-3.379h9.117L10.427 9.39a.72.72 0 0 0-.062 1.022.726.726 0 0 0 1.027.06l4.364-3.862a.72.72 0 0 0 .031-1.053' },
    cut: { vb: 25, d: 'M12.695 20.404c-.211.8-.668 1.484-1.19 1.788-.234.133-.45.174-.64.124-.591-.162-.996-1.308-.655-2.604.212-.8.667-1.485 1.191-1.787a.945.945 0 0 1 .474-.145c.057 0 .112.006.165.02.59.162.996 1.31.655 2.604zm-6.63-7.814c-.302.522-.987.979-1.788 1.19-1.295.339-2.442-.064-2.602-.652-.052-.193-.01-.408.125-.64.301-.525.987-.981 1.788-1.192.327-.086.645-.125.94-.125.871 0 1.542.337 1.661.778.052.192.011.408-.124.64zm16.03-1.829l1.894-4.203-8.79 2.234L17.433 0l-4.204 1.896-1.344 7.74-4.477 1.136c-.78-1.072-2.46-1.545-4.242-1.075C1.937 10.022.89 10.756.37 11.66c-.36.62-.458 1.277-.288 1.901.338 1.242 1.653 2 3.24 2A5.42 5.42 0 0 0 4.7 15.38c1.23-.325 2.275-1.059 2.798-1.963.198-.342.307-.695.347-1.048l4.512-1.147.263.152.15.262-1.146 4.512a2.666 2.666 0 0 0-1.048.346c-.905.523-1.639 1.568-1.963 2.799-.57 2.157.23 4.186 1.819 4.618.197.053.398.08.6.08a2.59 2.59 0 0 0 1.3-.368c.905-.523 1.638-1.567 1.963-2.797.47-1.783-.005-3.464-1.077-4.242l1.139-4.476 7.739-1.346z' },
    copy: { vb: 25, d: 'M20.5 18H10c-1.654 0-3-1.346-3-3V4.5c0-1.654 1.346-3 3-3h7.5v5.25c0 .414.336.75.75.75h5.25V15c0 1.654-1.346 3-3 3m-6.185 5.5H5.967A4.472 4.472 0 0 1 1.5 19.032v-8.347c0-2.304 1.758-4.183 4-4.42V15c0 2.481 2.019 4.5 4.5 4.5h8.735c-.237 2.241-2.117 4-4.42 4M24.993 6.751a.742.742 0 0 0-.213-.531l-.596-.596L19.108.236A.746.746 0 0 0 18.563 0H10a4.505 4.505 0 0 0-4.5 4.5v.241C2.428 4.98 0 7.552 0 10.685v8.347A5.974 5.974 0 0 0 5.967 25h8.348c3.133 0 5.704-2.428 5.943-5.5h.242c2.481 0 4.5-2.019 4.5-4.5V6.787c0-.013-.006-.024-.007-.036' },
    paste: { vb: 25, d: 'M21.765 14.139a3.633 3.633 0 0 1-3.63 3.63h-6.441a.812.812 0 0 0 0 1.623h4.955c-.189 1.934-1.805 3.456-3.786 3.456H5.446a3.828 3.828 0 0 1-3.822-3.823V11.61c0-1.983 1.521-3.599 3.455-3.787v4.422a.812.812 0 0 0 1.624 0V6.336a3.634 3.634 0 0 1 3.63-3.63h7.803a3.633 3.633 0 0 1 3.63 3.63v7.803zm-3.63-13.056h-7.801c-2.85 0-5.165 2.283-5.24 5.114C2.257 6.384 0 8.726 0 11.61v7.416a5.452 5.452 0 0 0 5.446 5.446h7.417c2.882 0 5.225-2.257 5.41-5.092 2.832-.076 5.116-2.39 5.116-5.24V6.336a5.26 5.26 0 0 0-5.253-5.253z M15.026 13.254v-3.81h-3.808l1.143 1.144-6.656 6.656 1.524 1.523 6.654-6.656z' },
    center: { vb: 25, d: 'M16.5498,12.5286538 C16.5498,14.6526923 14.7588,16.3748077 12.5498,16.3748077 C10.3408,16.3748077 8.5498,14.6526923 8.5498,12.5286538 C8.5498,10.4046154 10.3408,8.6825 12.5498,8.6825 C14.7588,8.6825 16.5498,10.4046154 16.5498,12.5286538 M13.5498,20.1542308 L13.5498,19.2311538 C13.5498,18.6994231 13.1018,18.2696154 12.5498,18.2696154 C11.9978,18.2696154 11.5498,18.6994231 11.5498,19.2311538 L11.5498,20.1542308 C7.9378,19.7176923 5.0728,16.9628846 4.6188,13.4898077 L5.9998,13.4898077 C6.5518,13.4898077 6.9998,13.06 6.9998,12.5282692 C6.9998,11.9975 6.5518,11.5667308 5.9998,11.5667308 L4.6188,11.5667308 C5.0728,8.09461538 7.9378,5.33884615 11.5498,4.90326923 L11.5498,5.76961538 C11.5498,6.30038462 11.9978,6.73115385 12.5498,6.73115385 C13.1018,6.73115385 13.5498,6.30038462 13.5498,5.76961538 L13.5498,4.90326923 C17.1618,5.33884615 20.0268,8.09461538 20.4808,11.5667308 L18.9998,11.5667308 C18.4478,11.5667308 17.9998,11.9975 17.9998,12.5282692 C17.9998,13.06 18.4478,13.4898077 18.9998,13.4898077 L20.4808,13.4898077 C20.0268,16.9628846 17.1618,19.7176923 13.5498,20.1542308 M23.9998,11.5667308 L22.4988,11.5667308 C22.0288,7.03115385 18.2668,3.41480769 13.5498,2.96192308 L13.5498,0.961923077 C13.5498,0.430192308 13.1018,0.000384615385 12.5498,0.000384615385 C11.9978,0.000384615385 11.5498,0.430192308 11.5498,0.961923077 L11.5498,2.96192308 C6.8328,3.41480769 3.0708,7.03115385 2.6008,11.5667308 L0.9998,11.5667308 C0.4478,11.5667308 -0.0002,11.9975 -0.0002,12.5282692 C-0.0002,13.06 0.4478,13.4898077 0.9998,13.4898077 L2.6008,13.4898077 C3.0708,18.0263462 6.8328,21.6426923 11.5498,22.0955769 L11.5498,24.0388462 C11.5498,24.5696154 11.9978,25.0003846 12.5498,25.0003846 C13.1018,25.0003846 13.5498,24.5696154 13.5498,24.0388462 L13.5498,22.0955769 C18.2668,21.6426923 22.0288,18.0263462 22.4988,13.4898077 L23.9998,13.4898077 C24.5518,13.4898077 24.9998,13.06 24.9998,12.5282692 C24.9998,11.9975 24.5518,11.5667308 23.9998,11.5667308' },
    zoom_in: { vb: 25, d: 'M19,11.5 L13.5,11.5 L13.5,6 C13.5,5.448 13.052,5 12.5,5 C11.948,5 11.5,5.448 11.5,6 L11.5,11.5 L6,11.5 C5.448,11.5 5,11.948 5,12.5 C5,13.052 5.448,13.5 6,13.5 L11.5,13.5 L11.5,19 C11.5,19.552 11.948,20 12.5,20 C13.052,20 13.5,19.552 13.5,19 L13.5,13.5 L19,13.5 C19.552,13.5 20,13.052 20,12.5 C20,11.948 19.552,11.5 19,11.5 M12.5,2 C6.71,2 2,6.71 2,12.5 C2,18.29 6.71,23 12.5,23 C18.29,23 23,18.29 23,12.5 C23,6.71 18.29,2 12.5,2 M12.5,25 C5.607,25 0,19.393 0,12.5 C0,5.607 5.607,0 12.5,0 C19.393,0 25,5.607 25,12.5 C25,19.393 19.393,25 12.5,25' },
    zoom_out: { vb: 25, d: 'M19 13.5H6a1 1 0 0 1 0-2h13a1 1 0 0 1 0 2 M12.5 2C6.71 2 2 6.71 2 12.5S6.71 23 12.5 23 23 18.29 23 12.5 18.29 2 12.5 2m0 23C5.607 25 0 19.393 0 12.5S5.607 0 12.5 0 25 5.607 25 12.5 19.393 25 12.5 25' },
    // delete 按鈕沒有獨立渲染 (刪除由垃圾桶負責), 這個 path 純粹留低備用。
    delete: { vb: 20, d: 'M5 6 H15 M8 6 V4 H12 V6 M7 6 L7.7 17 H12.3 L13 6 M9 9 V14 M11 9 V14' },
  };

  // EditFabControls 同 ZoomFabControls 共用的起按鈕 helper: 一粒深色圓 +
  // 置中實心 icon + <title> tooltip, 回傳 { group, titleEl }。
  function appendFabButton(parentG, iconDef, x, y, size, titleText, onClick) {
    const group = Blockly.utils.dom.createSvgElement('g', {
      class: 'bk-svg-fab-group', transform: `translate(${x}, ${y})`,
    }, parentG);
    Blockly.utils.dom.createSvgElement('circle', {
      class: 'bk-svg-fab-circle',
      cx: size / 2, cy: size / 2, r: size / 2 - 1,
    }, group);
    const scale = FAB_ICON_BOX / iconDef.vb;
    const off = (size - FAB_ICON_BOX) / 2;
    const iconG = Blockly.utils.dom.createSvgElement('g', {
      transform: `translate(${off}, ${off}) scale(${scale})`,
    }, group);
    Blockly.utils.dom.createSvgElement('path', {
      class: 'bk-svg-fab-icon',
      d: iconDef.d,
      'fill-rule': 'evenodd',
    }, iconG);
    const titleEl = Blockly.utils.dom.createSvgElement('title', {}, group);
    titleEl.textContent = titleText;
    group.addEventListener('pointerdown', (evt) => {
      evt.preventDefault();
      evt.stopPropagation();
    });
    group.addEventListener('click', (evt) => {
      evt.preventDefault();
      evt.stopPropagation();
      onClick();
    });
    return { group, titleEl };
  }

  // ------------------------------------------------------------------
  // 垃圾桶改裝 — 對齊 Code Lab 同款深色圓形。
  //
  // Code Lab (chunk-843ccf1e webpack module a073) 成個
  // Trashcan.prototype.createDom 換走: 60px #333 深色圓底 + 自畫 vector
  // 蓋/身 (fill #A8A8AC, 打開變 #0ED3FF, 蓋有 700ms 開合動畫)。
  //
  // 這裡唔成個換走, 只做「換皮」: 照 call 原本 Blockly 13.2.0 的 createDom
  // (a11y role/label、focus ring、sprite clippath、pointerdown/up 綁定、
  // flyout 還原、拖放刪除 hit-test 全部保留), 之後:
  //   1. 墊一粒深色圓底 (塞去第一個 child);
  //   2. 抌走兩個 sprite <image>, 換上 Code Lab 同款 vector 蓋/身 path
  //      (由 codelab.nuwarobotics.com 抽出, 原 ~24 單位空間, scale 1.4
  //      同 Code Lab 一樣)。
  // 視覺 footprint 維持 47x60 (同 getBoundingRectangle/position/getClientRect
  // 的假設一致), 所以 EditFabControls/ZoomFabControls 的避讓計算唔使改。
  // 開合變色靠 blockly.css .blocklyTrash.blocklyTrashOpen 規則 (Blockly 13
  // setLidOpen() 本身就是 toggle 這個 class)。
  //
  // 執行時機: 這個 IIFE 在 script load 當時即行 (blockly-page.js
  // initWorkspace()/Blockly.inject() 在 DOMContentLoaded 先行), 所以 patch
  // 一定趕得切在 addTrashcan()->createDom() 之前生效。
  // ------------------------------------------------------------------
  (function patchAlphaTrashcan() {
    if (typeof Blockly === 'undefined' || !Blockly.Trashcan || !Blockly.Trashcan.prototype) return;
    const proto = Blockly.Trashcan.prototype;
    if (proto.__alphaTrashPatched) return;
    proto.__alphaTrashPatched = true;
    const origCreateDom = proto.createDom;
    // Code Lab 垃圾桶蓋 (lid bar + handle) 同身 (body + 兩條坑紋)。
    const TRASH_LID_PATHS = [
      'M21.192 2.265H1a1 1 0 0 1 0-2h20.192a1 1 0 0 1 0 2',
      'M15.17 2H8.848a1 1 0 0 1 0-2h6.324a1 1 0 0 1 0 2',
    ];
    const TRASH_BODY_PATHS = [
      'M18.358 24.62H5.534c-1.556 0-2.823-1.371-2.823-3.058V7.175a1 1 0 0 1 2 0v14.387c0 .574.377 1.059.823 1.059h12.824c.446 0 .823-.485.823-1.06V7.176a1 1 0 0 1 2 0v14.387c0 1.687-1.266 3.059-2.823 3.059',
      'M8.847 20.034a1 1 0 0 1-1-1V8.76a1 1 0 1 1 2 0v10.274a1 1 0 0 1-1 1M15.17 20.034a1 1 0 0 1-1-1V8.578a1 1 0 0 1 2 0v10.456a1 1 0 0 1-1 1',
    ];
    proto.createDom = function () {
      const g = origCreateDom.call(this);
      const NS = 'http://www.w3.org/2000/svg';
      // 1. 深色圓底: 47x60 rect 之內最大圓 (圓心 23.5,30, r=23)。
      const disc = document.createElementNS(NS, 'circle');
      disc.setAttribute('cx', '23.5');
      disc.setAttribute('cy', '30');
      disc.setAttribute('r', '23');
      disc.setAttribute('class', 'trash-bg');
      g.insertBefore(disc, g.firstChild);
      // 2. 抌走 sprite <image> (兩個: body 同 lid 各一)。
      const imgs = g.querySelectorAll('image');
      for (let i = 0; i < imgs.length; i++) {
        const im = imgs[i];
        if (im.parentNode) im.parentNode.removeChild(im);
      }
      // 3. Code Lab icon art (約 22x24.6, scale 1.4 -> 30.8x34.4)。
      // 身體照舊擺喺主 group; 蓋一定要擺入原生 g.blocklyTrashLid wrapper
      // 裡面 —— Blockly 13 的開合動畫是 CSS rotate 這個 wrapper
      // (有嘢 5deg / 打開 45deg), 之前將蓋放自己個 group 就令個動畫打去
      // 空殼 (呢個就係「動畫無左」的原因)。兩邊用同一個
      // translate(8.1 12.8) scale(1.4) 定位, 等蓋同身對返齊 (蓋 x 9.5..37.8,
      // y ~13.2..16, 同之前個版位完全一樣)。
      const ICON_TRANSFORM = 'translate(8.1 12.8) scale(1.4)';
      const iconG = Blockly.utils.dom.createSvgElement('g', {
        class: 'trashcan-icon', transform: ICON_TRANSFORM,
      }, g);
      const bodyG = Blockly.utils.dom.createSvgElement('g', { class: 'trashcan-body' }, iconG);
      TRASH_BODY_PATHS.forEach(function (d) {
        Blockly.utils.dom.createSvgElement('path', { d: d }, bodyG);
      });
      const lidWrap = g.querySelector('.blocklyTrashLid');
      if (lidWrap) {
        // 清走裡面個空 svg viewport (image 已經抌走, 留低都 render 唔到嘢)。
        const oldSvg = lidWrap.querySelector('svg');
        if (oldSvg && oldSvg.parentNode) oldSvg.parentNode.removeChild(oldSvg);
      }
      const lidParent = lidWrap || iconG; // 萬一將來 Blockly 改 DOM, 跌回主 group 保底
      const lidPos = Blockly.utils.dom.createSvgElement('g', { transform: ICON_TRANSFORM }, lidParent);
      const capG = Blockly.utils.dom.createSvgElement('g', { class: 'trashcan-cap' }, lidPos);
      TRASH_LID_PATHS.forEach(function (d) {
        Blockly.utils.dom.createSvgElement('path', { d: d }, capG);
      });
      return g;
    };
  })();

  // ------------------------------------------------------------------
  // EditFabControls — 一個 IPositionable component, 內部包住五粒小按鈕
  // (復原/取消復原/剪下/複製/貼上), 成組一齊定位, 行為好似 Blockly 個
  // ZoomControls 咁 (裡面雖然有幾粒按鈕, 但對 ComponentManager 來講是一個
  // component, 一次 getBoundingRectangle() covers 完成組)。
  // 注意: editAction() 下面還有一個 'delete' case (checkAndDelete()) 沒有被
  // 這裡任何按鈕觸發 —— 刪除功能刻意沒有獨立按鈕, 由 Blockly 內建垃圾桶負責 (見
  // 下面垃圾桶改裝 patch), 個 case 純粹留下方便將來想加回一個
  // 獨立按鈕當時可以直接用, 不是漏刪的死 code。
  //
  // 2026-09: 對齊 Code Lab quick-edit —— 5 粒均勻橫排、無分隔、42px 深色圓、
  // 實心 icon (見上面 EDIT_FAB_ICONS)、無 disabled 狀態 (永遠可按, 無選中時
  // cut/copy/paste 靜默 no-op, 同 Code Lab 一致)。
  // ------------------------------------------------------------------
  class EditFabControls {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaEditFabControls';
      this.top = 0;
      this.left = 0;
      // 版面: 5 粒獨立深色圓形按鈕 (直徑 42px, Code Lab 同款), 自己一個圓圈
      // 背景, 按鈕與按鈕之間僅用均勻間距 (GAP=15, Code Lab 同款) 分隔, 橫向
      // 排完一行, 沒有分組、沒有分隔線 (之前個 { sep:true } + GROUP_GAP 已經
      // 刪走, 因為 Code Lab 係 5 粒均勻、undo/redo 同 cut/copy/paste 之間無
      // 特別分隔)。
      this.BUTTON_SIZE = 42;
      this.GAP = 15;
      this.MARGIN_HORIZONTAL = 12;
      this.MARGIN_VERTICAL = 12;
      this.buttons = [
        { action: 'undo', icon: 'undo', titleKey: 'page_edit_undo_title' },
        { action: 'redo', icon: 'redo', titleKey: 'page_edit_redo_title' },
        { action: 'cut', icon: 'cut', titleKey: 'page_edit_cut_title' },
        { action: 'copy', icon: 'copy', titleKey: 'page_edit_copy_title' },
        { action: 'paste', icon: 'paste', titleKey: 'page_edit_paste_title' },
      ];
      this.buttonEls = {}; // action -> { group, circle, titleEl }
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 3, // Trashcan 通常是 weight 2, ZoomControls weight 1 —— 擺
                   // 在它們之後 (數值愈大愈遲定位), 等這兩個 Blockly 自己的
                   // component 先取得它們慣常的角落位置, 我們成組先至在
                   // bumpPositionRect() 當時自動被推去再上少少, 不會重疊。
      });
      this.workspace.resizeContents();
    }

    getGroupWidth() {
      // 5 粒均勻橫排: n 粒圓 + (n-1) 個間距。
      const n = this.buttons.length;
      return n * this.BUTTON_SIZE + (n - 1) * this.GAP;
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', { class: 'bk-svg-fab-bar' }, null);
      // 沒有一併一條的背景 pill —— 每粒按鈕自己的 circle 就是它個背景 (獨立深色
      // 圓形按鈕, Code Lab 同款), 按鈕與按鈕之間均勻留白, 無分組無分隔線。
      let x = 0;
      for (const b of this.buttons) {
        const made = appendFabButton(this.svgGroup, EDIT_FAB_ICONS[b.icon], x, 0,
          this.BUTTON_SIZE, t(b.titleKey), () => editAction(b.action));
        this.buttonEls[b.action] = made;
        x += this.BUTTON_SIZE + this.GAP;
      }
      svg.appendChild(this.svgGroup);
    }

    // 2026-09: 對齊 Code Lab —— 浮動按鈕無 disabled 狀態, 5 粒永遠可按。
    // 無選中 block 時撳 cut/copy/paste 只是靜默 no-op (見 editAction 守衛),
    // 所以這個 method 變 no-op, 保留個名等舊 call site 不用改 (實際下面已經
    // 沒有 call 它, 留低僅做文件作用)。
    updateButtonStates() { /* no-op: 對齊 Code Lab, 永遠全部可按 */ }

    // 語言切換後, title (SVG <title> tooltip) 要跟著換 —— 同 blockly-i18n.js
    // 的 applyUiTextLocale() 對應的 HTML 版做法一致, 這裡用來給它 call。
    updateI18n() {
      for (const b of this.buttons) {
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
      const trashcan = this.workspace.trashcan;
      if (trashcan && typeof trashcan.getBoundingRectangle === 'function') {
        // 直接貼住垃圾桶個左邊, 垂直同垃圾桶中心對齊 —— 這個才是 Code Lab
        // 個排位 (按鈕組同垃圾桶並排一行, 不是分開在畫布另一角)。之前用
        // getCornerOppositeToolbox + bumpPositionRect 那套「自動避讓」邏輯,
        // 在這個 toolbox 不在角落的 layout 度計錯了位, 令成組按鈕跑出畫布外
        // 完全沒有顯示, 所以改用回最直接可靠的做法: 讀垃圾桶自己的
        // getBoundingRectangle() 來計。
        const tRect = trashcan.getBoundingRectangle();
        const tHeight = tRect.bottom - tRect.top;
        this.left = tRect.left - this.MARGIN_HORIZONTAL - width;
        this.top = tRect.top + (tHeight - this.BUTTON_SIZE) / 2;
      } else {
        // fallback: 垃圾桶未起好 (理論上不應該發生, addTrashcan() 一定早過
        // 這個 component 註冊), 保留原本的角落定位邏輯做保險。
        const corner = Blockly.uiPosition.getCornerOppositeToolbox(this.workspace, uiMetrics);
        let rect = Blockly.uiPosition.getStartPositionRect(
          corner, size, this.MARGIN_HORIZONTAL, this.MARGIN_VERTICAL, uiMetrics, this.workspace);
        const bumpDir = corner.vertical === Blockly.uiPosition.verticalPosition.TOP
          ? Blockly.uiPosition.bumpDirection.DOWN
          : Blockly.uiPosition.bumpDirection.UP;
        rect = Blockly.uiPosition.bumpPositionRect(rect, this.MARGIN_VERTICAL, bumpDir, savedPositions);
        this.top = rect.top;
        this.left = rect.left;
      }
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  // ------------------------------------------------------------------
  // ZoomFabControls — 對齊 Code Lab editor-tool: 右下角垂直 3 粒同款深色圓
  // (歸中 / 放大 / 縮細), 疊在垃圾桶正上方。同 EditFabControls 一樣是
  // IPositionable component, 共用 appendFabButton() 起按鈕, 所以視覺同 edit
  // 橫排完全同一個系列。Blockly 內建 zoom controls 已經關掉
  // (見 blockly-page.js zoom.controls:false), 由這組取代。
  // ------------------------------------------------------------------
  class ZoomFabControls {
    constructor(ws) {
      this.workspace = ws;
      this.id = 'alphaZoomFabControls';
      this.top = 0;
      this.left = 0;
      this.BUTTON_SIZE = 42; // 同 EditFabControls 一樣大, 同一個系列
      this.GAP = 15;
      this.MARGIN_ABOVE_TRASH = 16; // 同垃圾桶頂的距離 (Code Lab editor-tool 同垃圾桶之間約 18px)
      this.buttons = [
        { action: 'center', icon: 'center', titleKey: 'page_edit_center_title' },
        { action: 'zoom_in', icon: 'zoom_in', titleKey: 'page_edit_zoom_in_title' },
        { action: 'zoom_out', icon: 'zoom_out', titleKey: 'page_edit_zoom_out_title' },
      ];
      this.buttonEls = {}; // action -> { group, titleEl }
      this.createDom();
      ws.getComponentManager().addComponent({
        component: this,
        capabilities: [Blockly.ComponentManager.Capability.POSITIONABLE],
        weight: 4, // 排在 EditFabControls (3) 之後定位, 避免兩組撞位
      });
      this.workspace.resizeContents();
    }

    getGroupHeight() {
      const n = this.buttons.length;
      return n * this.BUTTON_SIZE + (n - 1) * this.GAP;
    }

    createDom() {
      const svg = this.workspace.getParentSvg();
      this.svgGroup = Blockly.utils.dom.createSvgElement('g', { class: 'bk-svg-fab-bar' }, null);
      let y = 0;
      for (const b of this.buttons) {
        const made = appendFabButton(this.svgGroup, EDIT_FAB_ICONS[b.icon], 0, y,
          this.BUTTON_SIZE, t(b.titleKey), () => editAction(b.action));
        this.buttonEls[b.action] = made;
        y += this.BUTTON_SIZE + this.GAP;
      }
      svg.appendChild(this.svgGroup);
    }

    updateI18n() {
      for (const b of this.buttons) {
        const el = this.buttonEls[b.action];
        if (el) el.titleEl.textContent = t(b.titleKey);
      }
    }

    getBoundingRectangle() {
      const height = this.getGroupHeight();
      return new Blockly.utils.Rect(this.top, this.top + height, this.left, this.left + this.BUTTON_SIZE);
    }

    position(uiMetrics, savedPositions) {
      const height = this.getGroupHeight();
      const trashcan = this.workspace.trashcan;
      if (trashcan && typeof trashcan.getBoundingRectangle === 'function') {
        // 擺喺垃圾桶正上方、水平同垃圾桶中心對齊 —— Code Lab editor-tool 個位
        // (bottom:110 right:29, 即垃圾桶上方)。讀垃圾桶自己的 rect 來計, 同
        // EditFabControls 一樣直接可靠, 不經 uiPosition 避讓管線。
        const tRect = trashcan.getBoundingRectangle();
        const tWidth = tRect.right - tRect.left;
        this.left = tRect.left + (tWidth - this.BUTTON_SIZE) / 2;
        this.top = tRect.top - this.MARGIN_ABOVE_TRASH - height;
      } else {
        // fallback: 垃圾桶未起好, 用角落定位做保險。
        const size = new Blockly.utils.Size(this.BUTTON_SIZE, height);
        const corner = Blockly.uiPosition.getCornerOppositeToolbox(this.workspace, uiMetrics);
        let rect = Blockly.uiPosition.getStartPositionRect(
          corner, size, 12, 12, uiMetrics, this.workspace);
        const bumpDir = corner.vertical === Blockly.uiPosition.verticalPosition.TOP
          ? Blockly.uiPosition.bumpDirection.DOWN
          : Blockly.uiPosition.bumpDirection.UP;
        rect = Blockly.uiPosition.bumpPositionRect(rect, 12, bumpDir, savedPositions);
        this.top = rect.top;
        this.left = rect.left;
      }
      this.svgGroup.setAttribute('transform', `translate(${this.left}, ${this.top})`);
    }

    dispose() {
      this.workspace.getComponentManager().removeComponent(this.id);
      if (this.svgGroup && this.svgGroup.parentNode) this.svgGroup.parentNode.removeChild(this.svgGroup);
    }
  }

  // ------------------------------------------------------------------
  // SidePanelToggleControl — 同上面一樣機制的另一個 IPositionable component,
  // 「收起/展開執行紀錄面板」那顆 ›/‹ 按鈕。獨立成一個 component (不是塞入
  // EditFabControls 度) 是因為它的 weight/擺位邏輯不同 —— 這粒按鈕要貼住
  // .bk-side 個左邊界, 不是跟 Blockly 慣常的「畫布角落」定位, 所以 position()
  // 裡面不用 uiPosition 那套, 改為直接讀 .bk-side 的實際 DOM 緊貼位置。
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
        weight: 10, // 這粒按鈕位置獨立計算, 不用理其他 component bump 它, 擺
                    // 在最後 (weight 最大) 就得。
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

    // 未收起顯示 › (正在指右, 就是「按了會收起去右邊」), 收起了顯示 ‹ (正在指左,
    // 就是「按了會展開回來」) —— 同 Code Lab 個箭頭方向邏輯一致。
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

    // 這粒按鈕要半浮在「畫布/側欄交界」—— 不跟 Blockly 慣常的四角定位, 直接讀
    // .bk-side 個 DOM 元素實際站在哪裡 (getBoundingClientRect()), 減返
    // .bk-canvas 個 SVG 原點的螢幕座標, 就拿到正確的 SVG 內部座標。側欄收起了
    // 當時 (.bk-side flex-basis 變 0) 它個 left 都會自動變做 canvas 右邊緣,
    // 按鈕就自然跟著動埋去右邊界, 不用額外邏輯。
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
  let zoomFabControls = null;
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
      // 2026-09: ZoomFabControls 垂直組 (對齊 Code Lab editor-tool)。
      case 'center': {
        // 回到預設視角: 縮放重設做 inject 當時的 startScale, 再置中畫布。
        // (Code Lab 用 setScale(1), 因為佢 startScale 本來就是 1; 這裡讀返
        // 自己 workspace 的 startScale, 語意一樣。)
        const zs = workspace.options && workspace.options.zoomOptions;
        workspace.setScale(zs && zs.startScale ? zs.startScale : 1);
        workspace.scrollCenter();
        break;
      }
      case 'zoom_in':
        workspace.zoomCenter(1);
        break;
      case 'zoom_out':
        workspace.zoomCenter(-1);
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
    // 2026-09: 對齊 Code Lab, 浮動按鈕無 disabled 狀態, 做完 action 不用再
    // refresh 按鈕狀態 (之前這裡會 call editFabControls.updateButtonStates(),
    // 個 method 而家是 no-op, 直接刪走個 call)。
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
      // undo/redo) 會在短時間內觸發好多個 non-UI change event, 逐個即時處理既浪費
      // 又會令 log 洗版, debounce 到「這輪改動停了」先做一次就夠。
      let rewireTimer = null;
      let saveTimer = null;
      workspace.addChangeListener(function (e) {
        if (e.isUiEvent) return;
        clearTimeout(rewireTimer);
        rewireTimer = setTimeout(rewireEventHandlers, 300);
        clearTimeout(saveTimer);
        saveTimer = setTimeout(autoSaveToLocalStorage, 800);
      });
      // 選/取消選 block 都是 UI event —— 2026-09 對齊 Code Lab 之後浮動按鈕
      // 無 disabled 狀態, 不再需要獨立 listener 追 SELECTED 來 toggle
      // cut/copy, 成段刪走。
      // 起返三組 IPositionable component (詳見上面 EditFabControls/
      // ZoomFabControls/SidePanelToggleControl 這三個 class 的大段註解) —— 一定要在 workspace
      // inject 了、有真正的 SVG root 之後先可以起, 所以擺在 init() 這裡做,
      // 不是在 module load 當時就起。
      editFabControls = new EditFabControls(workspace);
      zoomFabControls = new ZoomFabControls(workspace);
      sidePanelToggleControl = new SidePanelToggleControl(workspace);
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
    refreshSavedProgramDropdown: refreshSavedProgramDropdown, // 給 blockly-i18n.js 切語言當時取回來用, 令 "-- 已儲存的程式 --" placeholder 跟著重新 render
    editAction: editAction,
    // 語言切換後 (blockly-i18n.js setUiLanguage()) 要跟著換返這三組 SVG
    // component 的 <title> tooltip 文字, HTML 版 data-i18n 這套機制僅支援
    // 找 DOM 元素, 執行不到我們自己起的 SVG UI, 要給 blockly-i18n.js 專登
    // call 這個 method。
    refreshEditControlsI18n: function () {
      if (editFabControls) editFabControls.updateI18n();
      if (zoomFabControls) zoomFabControls.updateI18n();
      if (sidePanelToggleControl) sidePanelToggleControl.updateI18n();
    },
    toggleSidePanel: function () {
      const main = document.querySelector('.bk-main');
      if (!main) return;
      const collapsed = !main.classList.contains('bk-side-collapsed');
      main.classList.toggle('bk-side-collapsed', collapsed);
      if (sidePanelToggleControl) sidePanelToggleControl.setCollapsed(collapsed);
      try { localStorage.setItem('blocklySideCollapsed', collapsed ? '1' : '0'); } catch (e) { /* 不緊要, 沒有記錄低就下次預設展開 */ }
      // .bk-side flex-basis 有 CSS transition (0.18s), 畫布闊度同
      // SidePanelToggleControl 個位置都要跟著個過渡動畫慢慢動, resize
      // 幾次涵蓋整個過程 (Blockly.svgResize 會觸發 ComponentManager
      // 重新 position 一次, 所以這裡僅要負責在正確的時間點 call 它)。
      if (workspace) {
        Blockly.svgResize(workspace);
        setTimeout(function () { Blockly.svgResize(workspace); }, 100);
        setTimeout(function () { Blockly.svgResize(workspace); }, 200);
      }
    },
    setSidePanelCollapsedInitial: function (collapsed) {
      // 頁面剛剛 load, 讀回 localStorage 記住的上次收/展開狀態當時用 —— 不想
      // 用 toggleSidePanel() (它附帶 0.18s transition 的 setTimeout 級聯),
      // 僅要直接set 好個初始狀態, 不用播動畫。
      const main = document.querySelector('.bk-main');
      if (!main) return;
      main.classList.toggle('bk-side-collapsed', collapsed);
      if (sidePanelToggleControl) sidePanelToggleControl.setCollapsed(collapsed);
    },
    clearWorkspace: function () {
      if (workspace) workspace.clear();
    }
  };
})();


