// Open Alpha2 — Toolbox 定義 (Blockly JSON 格式)。
// 分咗 11 個分類：控制流程放最頂 (最常用), 之後跟 API 分組, 最後係 Blockly 標準
// 邏輯/迴圈/數學/文字/變數/自訂函式。

window.ALPHA_TOOLBOX = {
  kind: 'categoryToolbox',
  contents: [
    {
      kind: 'category', name: '▶ 流程控制', colour: '120',
      contents: [
        { kind: 'block', type: 'alpha_wait_seconds', inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1 } } } } },
        { kind: 'block', type: 'alpha_log', inputs: { MSG: { shadow: { type: 'text', fields: { TEXT: '訊息' } } } } },
        { kind: 'block', type: 'alpha_stop_program' },
        { kind: 'sep' },
        { kind: 'block', type: 'controls_if' },
        { kind: 'block', type: 'controls_repeat_ext', inputs: { TIMES: { shadow: { type: 'math_number', fields: { NUM: 5 } } } } },
        { kind: 'block', type: 'controls_whileUntil' },
        { kind: 'block', type: 'controls_for', inputs: {
          FROM: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
          TO: { shadow: { type: 'math_number', fields: { NUM: 10 } } },
          BY: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
        } },
        { kind: 'block', type: 'controls_flow_statements' },
      ]
    },
    {
      kind: 'category', name: '🔔 事件', colour: '0',
      contents: [
        { kind: 'block', type: 'alpha_event_when' },
        { kind: 'block', type: 'alpha_event_field' },
      ]
    },
    {
      kind: 'category', name: '🏃 動作', colour: '20',
      contents: [
        { kind: 'block', type: 'alpha_action_play_builtin' },
        { kind: 'block', type: 'alpha_action_play' },
        { kind: 'block', type: 'alpha_action_play_dropdown' },
        { kind: 'block', type: 'alpha_action_stop' },
        { kind: 'block', type: 'alpha_action_wait_done' },
      ]
    },
    {
      kind: 'category', name: '💬 語音', colour: '160',
      contents: [
        { kind: 'block', type: 'alpha_speech_tts', inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: '你好' } } } } },
        { kind: 'block', type: 'alpha_speech_stop' },
        { kind: 'block', type: 'alpha_speech_set_mic' },
        { kind: 'block', type: 'alpha_speech_start_asr' },
        { kind: 'block', type: 'alpha_speech_set_voice' },
        { kind: 'block', type: 'alpha_speech_set_language' },
        { kind: 'block', type: 'alpha_speech_self_interrupt' },
      ]
    },
    {
      kind: 'category', name: '🦾 伺服馬達', colour: '230',
      contents: [
        { kind: 'block', type: 'alpha_servo_one' },
        { kind: 'block', type: 'alpha_servo_home' },
        { kind: 'block', type: 'alpha_servo_all', inputs: { ANGLES: { shadow: { type: 'text', fields: { TEXT: '120,120,120,120,120,120,120,65,145,140,120,120,175,95,100,120,120,120,120,120' } } } } },
        { kind: 'block', type: 'alpha_servo_all_helper' },
        { kind: 'block', type: 'alpha_servo_sonar' },
      ]
    },
    {
      kind: 'category', name: '💡 LED', colour: '290',
      contents: [
        { kind: 'block', type: 'alpha_led_head' },
        { kind: 'block', type: 'alpha_led_eye' },
      ]
    },
    {
      kind: 'category', name: '📷 相機 / 🔊 音效', colour: '65',
      contents: [
        { kind: 'block', type: 'alpha_camera_snapshot' },
        { kind: 'block', type: 'alpha_camera_resolution' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_audio_testtone' },
        { kind: 'block', type: 'alpha_audio_play' },
      ]
    },
    {
      kind: 'category', name: '📟 感應 / 裝置', colour: '330',
      contents: [
        { kind: 'block', type: 'alpha_head_noise' },
        { kind: 'block', type: 'alpha_misc_request_uuid' },
        { kind: 'block', type: 'alpha_misc_charge_play' },
        { kind: 'block', type: 'alpha_misc_power_save' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_get_battery_level' },
        { kind: 'block', type: 'alpha_get_battery_charging' },
        { kind: 'block', type: 'alpha_get_wifi_ip' },
        { kind: 'block', type: 'alpha_get_status_field' },
      ]
    },
    {
      kind: 'category', name: '⚖ 邏輯', colour: '%{BKY_LOGIC_HUE}',
      contents: [
        { kind: 'block', type: 'logic_compare' },
        { kind: 'block', type: 'logic_operation' },
        { kind: 'block', type: 'logic_negate' },
        { kind: 'block', type: 'logic_boolean' },
        { kind: 'block', type: 'logic_null' },
        { kind: 'block', type: 'logic_ternary' },
      ]
    },
    {
      kind: 'category', name: '🔢 數學', colour: '%{BKY_MATH_HUE}',
      contents: [
        { kind: 'block', type: 'math_number', fields: { NUM: 0 } },
        { kind: 'block', type: 'math_arithmetic' },
        { kind: 'block', type: 'math_single' },
        { kind: 'block', type: 'math_random_int', inputs: {
          FROM: { shadow: { type: 'math_number', fields: { NUM: 1 } } },
          TO: { shadow: { type: 'math_number', fields: { NUM: 100 } } },
        } },
        { kind: 'block', type: 'math_modulo' },
        { kind: 'block', type: 'math_round' },
      ]
    },
    {
      kind: 'category', name: '🔤 文字', colour: '%{BKY_TEXTS_HUE}',
      contents: [
        { kind: 'block', type: 'text', fields: { TEXT: '' } },
        { kind: 'block', type: 'text_join' },
        { kind: 'block', type: 'text_length' },
        { kind: 'block', type: 'text_isEmpty' },
        { kind: 'block', type: 'text_indexOf' },
        { kind: 'block', type: 'text_charAt' },
        { kind: 'block', type: 'text_print' },
      ]
    },
    {
      kind: 'category', name: '📦 變數', colour: '%{BKY_VARIABLES_HUE}', custom: 'VARIABLE'
    },
    {
      kind: 'category', name: '🧩 自訂函式', colour: '%{BKY_PROCEDURES_HUE}', custom: 'PROCEDURE'
    },
  ]
};
