// Open Alpha2 — Blockly 自訂 block 定義。
// 每個 block 對應 app.js / MainActivity.handleApi() 入面已驗證嘅一個 /api/* 端點,
// 或者係一個流程控制 block (repeat/if/wait/variable/event)。
//
// 呢個檔案淨係定義「個 block 生埋嚟長咩樣、有咩輸入」——實際「行呢個 block 會做咩」
// 喺 blockly-run.js 嘅 interpreter 入面,唔喺呢度用 Blockly 內建嘅 code-generator。
// 用直譯 (walk the block tree) 而唔用「生成 JS code 再 eval」,係因為咁樣先可以喺
// wait/repeat 中途睇到「而家行緊邊個 block」嘅即時 highlight,同埋可以隨時安全中斷。

(function () {
  const clr = {
    action: 20,     // 橙 - 動作
    speech: 160,     // 綠 - 語音
    servo: 230,     // 藍 - 伺服
    led: 290,     // 紫 - LED
    sensor: 0,       // 紅 - 感應/事件
    camera: 65,      // 黃綠 - 相機
    audio: 200,     // 靛藍 - 音效
    device: 330,     // 粉紅 - 裝置資訊
    flow: 120,     // 草綠 - 流程控制 (跟 Blockly 慣例其實通常用啡, 但呢度統一用自訂色系)
    logic: 210,
    loop: 120,
    math: 230,
    text: 160,
    variable: 330,
    procedure: 290,
  };

  // ---------------------------------------------------------------------
  // 動作 Actions
  // ---------------------------------------------------------------------
  //
  // 設計取捨: 「播放動作」呢粒 block 預設會喺送出 API request 之後, 一路等到機械人
  // 用 action_stop 事件回報「呢個動作真係播完咗」先放行落下一粒 block。呢個係刻意
  // 揀嘅安全預設 —— 動作播放本身有長有短 (幾百 ms 到幾秒), 如果唔等就即刻行下一格,
  // 好容易撞到兩個動作互相打斷, 睇落好突兀甚至傷伺服馬達。想要「發咗就走、唔等」
  // 嘅快速連續動作效果, 可以將下面嘅「等待完成」揀做「唔等」。

  Blockly.Blocks['alpha_action_play'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('播放動作')
        .appendField(new Blockly.FieldTextInput('ACT0'), 'NAME');
      this.appendDummyInput()
        .appendField('等待完成')
        .appendField(new Blockly.FieldDropdown([
          ['✅ 等 (播完先做下一個, 建議)', 'true'],
          ['⚡ 唔等 (即刻做下一個)', 'false'],
        ]), 'WAIT')
        .appendField('逾時(秒)')
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip('播放一個內建動作 (/api/action/play?name=...)。名稱可以直接打動作 id, 或者用下面「播放內建動作」block 揀。預設會等機械人回報呢個動作真係播完先行落去下一粒 block, 避免兩個動作撞埋一齊播。');
      this.setHelpUrl('');
    }
  };

  Blockly.Blocks['alpha_action_play_builtin'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('播放內建動作')
        .appendField(new Blockly.FieldDropdown(
          (window.ALPHA_ACTION_OPTIONS_ALL && window.ALPHA_ACTION_OPTIONS_ALL.length)
            ? window.ALPHA_ACTION_OPTIONS_ALL
            : [['(動作清單未載入)', '']]
        ), 'NAME');
      this.appendDummyInput()
        .appendField('等待完成')
        .appendField(new Blockly.FieldDropdown([
          ['✅ 等 (播完先做下一個, 建議)', 'true'],
          ['⚡ 唔等 (即刻做下一個)', 'false'],
        ]), 'WAIT')
        .appendField('逾時(秒)')
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip('由機械人韌體隨附嘅動作清單 (89個: 基本/跳舞/故事/瑜伽 分類) 揀一個播放, 唔使打字。清單內嵌喺呢個網頁入面, 唔使連機都睇到晒選項。');
    }
  };

  Blockly.Blocks['alpha_action_play_dropdown'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('播放動作 (即時清單)')
        .appendField(new Blockly.FieldDropdown(function () {
          return (window.__alphaActionOptions && window.__alphaActionOptions.length)
            ? window.__alphaActionOptions
            : [['(未載入 - 先按「攞動作列表」)', '']];
        }), 'NAME');
      this.appendDummyInput()
        .appendField('等待完成')
        .appendField(new Blockly.FieldDropdown([
          ['✅ 等 (播完先做下一個, 建議)', 'true'],
          ['⚡ 唔等 (即刻做下一個)', 'false'],
        ]), 'WAIT')
        .appendField('逾時(秒)')
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip('由機械人「即時」回傳嘅動作清單揀一個播放 (即係向機械人實時查詢, 唔係用內嵌靜態清單) — 用嚟核對機身實際版本嘅動作清單同內嵌清單有冇出入。先要喺工具箱右上角按「攞動作列表」抓一次。');
    }
  };

  Blockly.Blocks['alpha_action_stop'] = {
    init: function () {
      this.appendDummyInput().appendField('停止動作播放');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip('停止目前正在播放嘅動作 (/api/action/stop)。');
    }
  };

  Blockly.Blocks['alpha_action_wait_done'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('額外等待：目前動作播放完畢 (最多')
        .appendField(new Blockly.FieldNumber(15, 0, 120), 'TIMEOUT')
        .appendField('秒)');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip('一般唔需要用呢粒 —— 「播放動作」block 已經內建咗「等待完成」選項。呢粒係俾特殊情況用: 例如用「播放動作(即時清單)」之後想額外多等一次, 或者透過序列埠/第三方方式觸發咗動作、想喺 Blockly 度等佢播完。');
    }
  };



  // ---------------------------------------------------------------------
  // 語音 Speech / TTS / ASR
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_speech_tts'] = {
    init: function () {
      this.appendValueInput('TEXT')
        .setCheck('String')
        .appendField('講嘢 (TTS)')
        .appendField(new Blockly.FieldDropdown([
          ['Nuance (英文)', 'nuance'],
          ['iFlytek 讯飞 (中文)', 'iflytek'],
          ['Android 預設', 'android'],
        ]), 'ENGINE');
      this.appendDummyInput()
        .appendField('聲音(淨iFlytek有效)')
        .appendField(new Blockly.FieldDropdown([
          ['預設', ''],
          ['catherine', 'catherine'],
          ['john', 'john'],
          ['小峰 xiaofeng', 'xiaofeng'],
          ['小欣 xiaoyan', 'xiaoyan'],
        ]), 'VOICE');
      this.setInputsInline(false);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('播放一段文字轉語音 (/api/speech/tts)。引擎其實由機身韌體決定實際用邊個, 呢度嘅選擇主要影響語言／聲音提示。');
    }
  };

  Blockly.Blocks['alpha_speech_stop'] = {
    init: function () {
      this.appendDummyInput().appendField('停止 TTS 播放');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('/api/speech/stop');
    }
  };

  Blockly.Blocks['alpha_speech_set_mic'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('麥克風擁有權：')
        .appendField(new Blockly.FieldDropdown([
          ['釋放俾機械人 (機械人可以自己聽)', 'true'],
          ['App 攞返 (機械人唔會聽)', 'false'],
        ]), 'WAKE');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('⚠️ 呢個唔係「開始聆聽」！淨係轉手 mic 擁有權, 唔會觸發辨識, 亦唔會主動開始聽。想即刻開始聽用「開始聆聽 (即時辨識)」嗰粒 block。(/api/speech/set_mic)');
    }
  };

  Blockly.Blocks['alpha_speech_start_asr'] = {
    init: function () {
      this.appendDummyInput().appendField('開始聆聽 (即時辨識, 唔使等 wake word)');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('直接開始 ASR 辨識, 唔使等機械人硬件偵測到 wake word。結果會經「當收到 語音辨識結果」事件送返嚟。(/api/speech/start_asr)');
    }
  };

  Blockly.Blocks['alpha_speech_set_voice'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('設定 TTS 聲音')
        .appendField(new Blockly.FieldDropdown([
          ['catherine', 'catherine'],
          ['john', 'john'],
          ['小峰 xiaofeng', 'xiaofeng'],
          ['小欣 xiaoyan', 'xiaoyan'],
        ]), 'NAME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('設定 TTS 聲音, 淨係 iFlytek 命名聲音先有效。(/api/speech/set_voice)');
    }
  };

  Blockly.Blocks['alpha_speech_set_language'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('設定辨識語言')
        .appendField(new Blockly.FieldDropdown([
          ['中文 zh_cn (iFlytek)', 'zh_cn'],
          ['英文 en_us (Nuance)', 'en_us'],
        ]), 'LANG');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('(/api/speech/set_language)');
    }
  };

  Blockly.Blocks['alpha_speech_self_interrupt'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('自我打斷 (中文限定)：')
        .appendField(new Blockly.FieldDropdown([['開啟', 'true'], ['關閉', 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('開關「機械人講嘢中途畀人講嘢打斷」。(/api/speech/self_interrupt)');
    }
  };

  // ---------------------------------------------------------------------
  // 伺服 Servo (20 顆) + Sonar
  // ---------------------------------------------------------------------

  const SERVO_NAMES = {
    1: '右肩上下', 2: '右肩左右', 3: '右肘',
    4: '左肩上下', 5: '左肩左右', 6: '左肘',
    7: '右股左右', 8: '右股上下', 9: '右膝', 10: '右腳掌上下', 11: '右腳掌左右',
    12: '左股左右', 13: '左股上下', 14: '左膝', 15: '左腳掌上下', 16: '左腳掌左右',
    17: '右指', 18: '左指',
    19: '頭左右', 20: '頭上下',
  };
  const SERVO_DROPDOWN = Object.keys(SERVO_NAMES).map(function (id) {
    return ['#' + id + ' ' + SERVO_NAMES[id], id];
  });

  Blockly.Blocks['alpha_servo_one'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('伺服馬達')
        .appendField(new Blockly.FieldDropdown(SERVO_DROPDOWN), 'ID')
        .appendField('角度')
        .appendField(new Blockly.FieldNumber(120, 0, 255, 1), 'ANGLE')
        .appendField('時間(ms)')
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip('移動單一伺服馬達到指定角度。角度範圍同校準值因機而異, 送出前請先喺「伺服」分頁核對安全範圍。(/api/servo/one)');
    }
  };

  Blockly.Blocks['alpha_servo_all'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('全部 20 顆伺服馬達 角度(CSV, 用逗號分隔20個數值)')
      this.appendValueInput('ANGLES').setCheck('String');
      this.appendDummyInput()
        .appendField('時間(ms)')
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip('一次過送出全部 20 顆伺服馬達嘅角度 (逗號分隔嘅 20 個整數, 依 #1~#20 次序)。(/api/servo/all)');
    }
  };

  Blockly.Blocks['alpha_servo_all_helper'] = {
    // 方便組 20 個數值成一個 CSV string, 免得用家自己打逗號
    init: function () {
      this.appendDummyInput().appendField('組合 20 顆角度 →');
      for (let i = 1; i <= 20; i++) {
        if ((i - 1) % 4 === 0) this.appendDummyInput();
        this.appendValueInput('A' + i).setCheck('Number')
          .appendField('#' + i);
      }
      this.setInputsInline(true);
      this.setOutput(true, 'String');
      this.setColour(clr.servo);
      this.setTooltip('將 20 個數值 block 組合成「全部伺服馬達」block 需要嘅 CSV 字串。可以插數字 block 或變數。');
    }
  };

  Blockly.Blocks['alpha_servo_home'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('全部伺服回到中位 (home)')
        .appendField('時間(ms)')
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip('用內建校準表嘅 home 值, 一次過將 20 顆伺服送返中位。');
    }
  };

  Blockly.Blocks['alpha_servo_sonar'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('聲納觸發距離')
        .appendField(new Blockly.FieldNumber(30, 0, 999, 1), 'DIST')
        .appendField('(0 = 關閉)');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip('/api/servo/sonar - 聲納係獨立感應硬件, 同伺服馬達冇關係。');
    }
  };

  // ---------------------------------------------------------------------
  // LED
  // ---------------------------------------------------------------------

  const LED_COLOURS = [
    ['紅', '1'], ['綠', '2'], ['藍', '3'], ['黃', '4'],
    ['紫', '5'], ['青', '6'], ['白', '7'],
  ];
  const LED_PRESETS_HEAD = [
    ['💡 長開', 'long'], ['⚡ 閃燈', 'flash'], ['🫧 呼吸燈', 'breathe'],
    ['🏃 跑馬燈', 'chase'], ['🎨 雙色燈', 'dual'], ['⏹ 停止', 'stop'],
  ];
  const LED_PRESETS_EYE = [
    ['💡 長開', 'long'], ['⚡ 閃燈', 'flash'],
    ['🏃 跑馬燈', 'chase'], ['🎨 雙色燈', 'dual'], ['⏹ 停止', 'stop'],
  ];

  Blockly.Blocks['alpha_led_head'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('頭部 LED')
        .appendField(new Blockly.FieldDropdown(LED_PRESETS_HEAD), 'PRESET')
        .appendField('顏色')
        .appendField(new Blockly.FieldDropdown(LED_COLOURS), 'COLOR')
        .appendField('亮度(1-9)')
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip('揀「停止」時顏色/亮度會被忽略。(/api/led/head/set)');
    }
  };

  Blockly.Blocks['alpha_led_eye'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('眼睛 LED')
        .appendField(new Blockly.FieldDropdown(LED_PRESETS_EYE), 'PRESET')
        .appendField('顏色')
        .appendField(new Blockly.FieldDropdown(LED_COLOURS), 'COLOR')
        .appendField('亮度(1-9)')
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip('揀「停止」時顏色/亮度會被忽略。(/api/led/eye/set)');
    }
  };

  // ---------------------------------------------------------------------
  // 頭部 / 感應 / 其他
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_head_noise'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('頭部降噪：')
        .appendField(new Blockly.FieldDropdown([['開啟', 'true'], ['關閉', 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip('(/api/head/noise)');
    }
  };

  Blockly.Blocks['alpha_misc_request_uuid'] = {
    init: function () {
      this.appendDummyInput().appendField('查詢機械人 UUID');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.device);
      this.setTooltip('結果經「當收到 機械人UUID」事件送返嚟。(/api/misc/request_uuid)');
    }
  };

  Blockly.Blocks['alpha_misc_charge_play'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('充電時允許郁動：')
        .appendField(new Blockly.FieldDropdown([['允許', 'true'], ['唔允許', 'false']]), 'OPEN');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.device);
      this.setTooltip('(/api/misc/charge_play)');
    }
  };

  Blockly.Blocks['alpha_misc_power_save'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('省電模式：')
        .appendField(new Blockly.FieldDropdown([['開啟', 'true'], ['關閉', 'false']]), 'SAVE');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.device);
      this.setTooltip('(/api/misc/power_save)');
    }
  };

  // ---------------------------------------------------------------------
  // 相機
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_camera_snapshot'] = {
    init: function () {
      this.appendDummyInput().appendField('拍一張快照 → 變數');
      this.appendDummyInput()
        .appendField('存入')
        .appendField(new Blockly.FieldVariable('jpeg圖片'), 'VAR');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.camera);
      this.setTooltip('拍一張快照, 將 base64 JPEG 存入變數。(/api/camera/snapshot)');
    }
  };

  Blockly.Blocks['alpha_camera_resolution'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('相機解像度')
        .appendField(new Blockly.FieldDropdown([
          ['320x240', '320x240'], ['640x480', '640x480'], ['800x600', '800x600'],
          ['1280x720 (720p)', '1280x720'], ['1920x1080 (1080p)', '1920x1080'],
          ['2064x1548 (實測上限)', '2064x1548'],
        ]), 'RES');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.camera);
      this.setTooltip('(/api/camera/resolution)');
    }
  };

  // ---------------------------------------------------------------------
  // 音效
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_audio_testtone'] = {
    init: function () {
      this.appendDummyInput().appendField('播放測試音 (3秒)');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.audio);
      this.setTooltip('(/api/audio/testtone)');
    }
  };

  Blockly.Blocks['alpha_audio_play'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('喇叭串流：')
        .appendField(new Blockly.FieldDropdown([['開始', 'start'], ['停止', 'stop']]), 'ACT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.audio);
      this.setTooltip('(/api/audio/play/start, /api/audio/play/stop)');
    }
  };

  // ---------------------------------------------------------------------
  // 讀取裝置資訊（回傳值 block）
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_get_battery_level'] = {
    init: function () {
      this.appendDummyInput().appendField('電池電量 %');
      this.setOutput(true, 'Number');
      this.setColour(clr.device);
      this.setTooltip('(/api/battery/status → level/scale)');
    }
  };

  Blockly.Blocks['alpha_get_battery_charging'] = {
    init: function () {
      this.appendDummyInput().appendField('正在充電？');
      this.setOutput(true, 'Boolean');
      this.setColour(clr.device);
      this.setTooltip('(/api/battery/status → charging)');
    }
  };

  Blockly.Blocks['alpha_get_wifi_ip'] = {
    init: function () {
      this.appendDummyInput().appendField('WiFi IP 位址');
      this.setOutput(true, 'String');
      this.setColour(clr.device);
      this.setTooltip('(/api/wifi/status → ip)');
    }
  };

  Blockly.Blocks['alpha_get_status_field'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('系統狀態')
        .appendField(new Blockly.FieldDropdown([
          ['SDK 版本', 'sdkVersion'],
          ['胸口服務可用', 'chestAvailable'],
          ['頭部服務可用', 'headerAvailable'],
          ['語音就緒', 'speechReady'],
        ]), 'FIELD');
      this.setOutput(true, null);
      this.setColour(clr.device);
      this.setTooltip('(/api/status)');
    }
  };

  // ---------------------------------------------------------------------
  // 事件 (WebSocket) — hat blocks, 用嚟起一個「當 XXX 事件發生」嘅事件驅動流程
  // ---------------------------------------------------------------------

  const EVENT_TYPES = [
    ['語音辨識結果 (asr_result)', 'asr_result'],
    ['TTS 播放完畢 (tts_end)', 'tts_end'],
    ['語音服務就緒 (speech_ready)', 'speech_ready'],
    ['動作播放完畢 (action_stop)', 'action_stop'],
    ['頭部按鍵 (head_key)', 'head_key'],
    ['聲源方向 (speech_direction)', 'speech_direction'],
    ['喚醒 (wakeup)', 'wakeup'],
    ['手勢 (gesture)', 'gesture'],
    ['機械人UUID回覆 (robot_uuid)', 'robot_uuid'],
    ['QR code 掃描結果 (qr_code)', 'qr_code'],
    ['WiFi 結果 (wifi_result)', 'wifi_result'],
    ['藍牙連接狀態 (bt_connection)', 'bt_connection'],
    ['電池廣播 (battery)', 'battery'],
    ['授權狀態 (authorize)', 'authorize'],
    ['頭部序列埠回傳 (head_rcv)', 'head_rcv'],
    ['胸口序列埠回傳 (chest_rcv)', 'chest_rcv'],
  ];

  Blockly.Blocks['alpha_event_when'] = {
    init: function () {
      this.appendDummyInput()
        .appendField('🔔 當事件')
        .appendField(new Blockly.FieldDropdown(EVENT_TYPES), 'EVENT')
        .appendField('發生')
        .appendField('存資料入')
        .appendField(new Blockly.FieldVariable('事件資料'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip('事件驅動 hat block: 每次 WebSocket 收到呢種 type 嘅事件, 就會執行下面嘅 block, 並且將完整事件 JSON 存入指定變數 (可以用「事件資料的欄位」讀取入面嘅內容)。呢個 block 唔喺 program 主流程之內, 係獨立、常駐監聽嘅。');
    }
  };

  Blockly.Blocks['alpha_event_field'] = {
    init: function () {
      this.appendValueInput('OBJ').setCheck(null)
        .appendField('讀取');
      this.appendDummyInput()
        .appendField('的欄位')
        .appendField(new Blockly.FieldTextInput('text'), 'KEY');
      this.setInputsInline(true);
      this.setOutput(true, null);
      this.setColour(clr.sensor);
      this.setTooltip('由事件資料(或者任何物件)入面攞出指定欄位嘅值, 例如 asr_result 事件入面嘅 "text"。');
    }
  };

  // ---------------------------------------------------------------------
  // 流程控制：等待 / 印出訊息(log) / 重複次數上限保護
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_wait_seconds'] = {
    init: function () {
      this.appendValueInput('SECONDS').setCheck('Number')
        .appendField('等待');
      this.appendDummyInput().appendField('秒');
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip('暫停程式執行指定秒數, 唔會阻塞事件監聽。');
    }
  };

  Blockly.Blocks['alpha_log'] = {
    init: function () {
      this.appendValueInput('MSG').setCheck(null)
        .appendField('📝 記錄訊息');
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip('喺右邊「執行紀錄」面板印一行訊息, 方便除錯, 唔會送任何 API request。');
    }
  };

  Blockly.Blocks['alpha_stop_program'] = {
    init: function () {
      this.appendDummyInput().appendField('⏹ 停止整個程式');
      this.setPreviousStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip('立即停止程式執行 (同按右上角「停止」掣一樣)。');
    }
  };

})();
