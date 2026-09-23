// Open Alpha2 — Toolbox 定義 (Blockly JSON 格式)。
// 分了 12 個分類：控制流程放最頂 (最常用), 之後跟 API 分組, 最後是 Blockly 標準
// 邏輯/迴圈/數學/文字/變數/自訂函式。
//
// 包做 function (而不是一次性 object literal) 是給語言切換用: 分類名/範例裡面
// 的文字都經 t() 正在讀 window.ALPHA_BLOCK_I18N, 但 t() 的結果在這個檔案 load
// 那一刻就已經計死了 (JS 沒有 lazy evaluation) —— 如果僅 assign 一次做靜態
// object, 之後切語言就算 updateToolbox() 都是取回那份舊語言的快照。改用
// window.buildAlphaToolbox() 令 blockly-i18n.js 可以在切換語言當時重新 call
// 一次, 拿到用回新語言的版本。window.ALPHA_TOOLBOX 保留做「最近一次 build
// 出來的版本」, 等 initWorkspace() (blockly-page.js) 首次注入 workspace 當時
// 不用改呼叫方式。
window.buildAlphaToolbox = function () {
  return {
  kind: 'categoryToolbox',
  contents: [
    {
      kind: 'category', name: t('toolbox_cat_flow'), colour: '120',
      contents: [
        { kind: 'block', type: 'alpha_wait_seconds', inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1 } } } } },
        { kind: 'block', type: 'alpha_log', inputs: { MSG: { shadow: { type: 'text', fields: { TEXT: t('toolbox_default_msg') } } } } },
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
      kind: 'category', name: t('toolbox_cat_events'), colour: '0',
      contents: [
        { kind: 'block', type: 'alpha_sensor_accel_toggle', fields: { ON: 'true' } },
        { kind: 'block', type: 'alpha_sensor_sonar_toggle', fields: { ON: 'true' } },
        { kind: 'block', type: 'alpha_sensor_pir_toggle', fields: { ON: 'true' } },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_event_accel_threshold' },
        { kind: 'block', type: 'alpha_event_sonar_triggered' },
        { kind: 'block', type: 'alpha_event_pir_triggered' },
      ]
    },
    {
      // 2026-08 更新: 動作 block 由一粒 alpha_action_play_builtin (帶 CATEGORY
      // dropdown) 拆了做獨立 block (基本/跳舞/故事/瑜伽/其他), 遵循電話/通知
      // 鈴聲個模式 (見 blockly-blocks.js makeActionCategoryBlock())。分類本身
      // 依然一隻色 (colour: '20', 同 clr.action 一致), 一按就見完所有動作
      // block, 不用逐層展開。
      kind: 'category', name: t('toolbox_cat_action'), colour: '20',
      contents: [
        { kind: 'block', type: 'alpha_action_play_basic' },
        { kind: 'block', type: 'alpha_action_play_dance' },
        { kind: 'block', type: 'alpha_action_play_story' },
        { kind: 'block', type: 'alpha_action_play_yoga' },
        { kind: 'block', type: 'alpha_action_play_others' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_action_play' },
        { kind: 'block', type: 'alpha_action_play_dropdown' },
        { kind: 'block', type: 'alpha_action_stop' },
        { kind: 'block', type: 'alpha_action_wait_done' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_speech'), colour: '160',
      contents: [
        { kind: 'block', type: 'alpha_speech_tts', inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_tts_default_shadow') } } } } },
        { kind: 'block', type: 'alpha_speech_stop' },
        { kind: 'block', type: 'alpha_speech_set_mic' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_speech_ringtone_phone', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 10 } } } } },
        { kind: 'block', type: 'alpha_speech_ringtone_notification', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 5 } } } } },
        { kind: 'block', type: 'alpha_speech_ringtone_stop' },
        { kind: 'sep' },
        // 2026-09 新增: 本地音樂 (同 Music 分頁同一套 /api/audio/local_music/*)。
        // 曲目 dropdown 是 live list, 開頁自動抓 (見 blockly-run.js
        // refreshMusicDropdown), 頂欄「🔄 取得音樂清單」可以重抓。
        { kind: 'block', type: 'alpha_music_play' },
        { kind: 'block', type: 'alpha_music_stop' },
        { kind: 'block', type: 'alpha_music_pause' },
        { kind: 'block', type: 'alpha_music_resume' },
      ]
    },
    {
      // 2026-08 更新: 伺服 block 由一粒 alpha_servo_one (帶 GROUP dropdown) 拆了
      // 做 5 粒獨立 block (頭/右手/左手/右腳/左腳), 遵循電話/通知鈴聲、動作分類
      // 個模式 (見 blockly-blocks.js makeServoGroupBlock())。分類本身依然一隻色
      // (colour: '230', 同 clr.servo 一致), 一按就見完所有伺服 block, 不用逐層
      // 展開。
      kind: 'category', name: t('toolbox_cat_servo'), colour: '230',
      contents: [
        { kind: 'block', type: 'alpha_servo_one_head' },
        { kind: 'block', type: 'alpha_servo_one_right_arm' },
        { kind: 'block', type: 'alpha_servo_one_left_arm' },
        { kind: 'block', type: 'alpha_servo_one_right_leg' },
        { kind: 'block', type: 'alpha_servo_one_left_leg' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_servo_home' },
        { kind: 'block', type: 'alpha_servo_all', inputs: { ANGLES: { shadow: { type: 'text', fields: { TEXT: '120,120,120,120,120,120,120,65,145,140,120,120,175,95,100,120,120,120,120,120' } } } } },
        { kind: 'block', type: 'alpha_servo_all_helper' },
        { kind: 'block', type: 'alpha_servo_sonar' },
      ]
    },
    {
      kind: 'category', name: t('toolbox_cat_led'), colour: '290',
      contents: [
        { kind: 'block', type: 'alpha_led_head' },
        { kind: 'block', type: 'alpha_led_eye' },
        { kind: 'block', type: 'alpha_led_mouth' },
      ]
    },
    // 2026-08 更新: 「相機 / 音效」同「裝置資訊」這兩個分類已經整個移除, block
    // 定義同 interpreter case 都一齊刪了, 不再保留 (見 blockly-blocks.js /
    // blockly-run.js)。感應器 (sonar/accel) 開關同事件維持在上面「🔔 事件」
    // 分類。
    {
      kind: 'category', name: t('toolbox_cat_logic'), colour: '%{BKY_LOGIC_HUE}',
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
      kind: 'category', name: t('toolbox_cat_math'), colour: '%{BKY_MATH_HUE}',
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
      kind: 'category', name: t('toolbox_cat_text'), colour: '%{BKY_TEXTS_HUE}',
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
      kind: 'category', name: t('toolbox_cat_variables'), colour: '%{BKY_VARIABLES_HUE}', custom: 'VARIABLE'
    },
    {
      kind: 'category', name: t('toolbox_cat_procedures'), colour: '%{BKY_PROCEDURES_HUE}', custom: 'PROCEDURE'
    },
    {
      // 2026-08 新增:「範例」分類, 放已經組合好的 block 組合, 用家由 toolbox
      // 拖出來就已經是一串裝好完的 next-chain (不用自己逐顆拼), 可以即刻試跑或者
      // 當起點再修改。用 toolbox JSON 的巢狀寫法: 第一粒 block 底下用
      // "next": { "block": {...} } 一路掛下去, 對應 Blockly 內部 next-connection
      // statement chain, 這個是 toolbox 官方支援的寫法, 不用自己額外寫 XML。
      //
      // 2026-08 更新: 原本這裡有 5 個手寫的簡短示範, 現在改做用家提供的 2 個
      // 實機測試過的完整程式 (alpha2-program-2026-08-04-04-02-26.xml /
      // alpha2-program-2026-08-04-05-43-58.xml, 經 workspace 匯出), 轉做
      // toolbox JSON 格式後直接放這裡。轉換時 <value><shadow>...</shadow></value>
      // 對應做 inputs.{NAME}.shadow, <next><block>...</block></next> 對應
      // 巢狀 next.block, 僅去了 XML 專屬的 id/x/y 定位屬性 (toolbox flyout
      // 不需要這些, 拖出來到 workspace 會由 Blockly 自己重新分配)。
      //
      // 注意: fields.SUBCATEGORY 這個值 ('表情 / 互動類' 等) 一定要維持中文,
      // 因為它是 blockly-actions-data.js 真實子分類清單的其中一個 key (跟機身
      // 韌體實測分類名, 不是這頁自己的顯示文字), 不跟 uiLang 轉——換了英文個
      // dropdown 就找不到這個分類。fields.TITLE (鈴聲名, 例如 'World'/'Antares'/
      // 'On The Hunt') 同理: 對應 blockly-ringtone-data.js 的實際鈴聲標題, 是
      // 資料值不是顯示文字, 一樣要保持原文。
      kind: 'category', name: t('toolbox_cat_examples'), colour: '15',
      contents: (function () {
        // 例子 1 的 TTS engine：2026-09 一律 android——機身已無 alpha2services，
        // nuance／iflytek 經 speech_startTTS 恒回 NOT_INIT 全程靜音，舊那套
        // 「中文用 iflytek、英文用 nuance」AIDL 語言配對已死。VOICE 只對
        // iflytek 有義，android 無視，例子留空。
        const ttsEngine = 'android';
        return [
        // 例子 1: 來電效果 —— 電話鈴聲 → 打招呼 → 播動作 (不等) → 再說話 →
        // 等 7 秒 → 手動停止鈴聲。示範: 鈴聲 + 語音 + 動作如何夾在一起玩,
        // 「等待完成」選 false 令個動作同後面的語音幾乎同一時間發生。
        {
          kind: 'block', type: 'alpha_speech_ringtone_phone',
          fields: { TITLE: 'World' },
          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
          next: { block: {
            kind: 'block', type: 'alpha_speech_tts',
            fields: { ENGINE: ttsEngine, VOICE: '' },
            inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_example1_hello') } } } },
            next: { block: {
              kind: 'block', type: 'alpha_action_play_basic',
              fields: { SUBCATEGORY: '表情 / 互動類', NAME: '1464835936026', WAIT: 'false', TIMEOUT: 15 },
              next: { block: {
                kind: 'block', type: 'alpha_speech_tts',
                fields: { ENGINE: ttsEngine, VOICE: '' },
                inputs: { TEXT: { shadow: { type: 'text', fields: { TEXT: t('toolbox_example1_happy') } } } },
                next: { block: {
                  kind: 'block', type: 'alpha_wait_seconds',
                  inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 7 } } } },
                  next: { block: {
                    kind: 'block', type: 'alpha_speech_ringtone_stop'
                  } }
                } }
              } }
            } }
          } }
        },
        // 例子 2: 通知鈴聲 + 伺服擺位串連 —— 頭部轉動 → 通知鈴聲 → 右手三段
        // 擺位 → 通知鈴聲 → 左手三段擺位 → 通知鈴聲 → 播放收尾動作。示範:
        // 多個伺服 block 同鈴聲 block 如何組合做一個帶節奏感的擺動組合,
        // 每個動作之間用「等待」+ 鈴聲做節拍提示。
        {
          kind: 'block', type: 'alpha_speech_ringtone_notification',
          fields: { TITLE: 'Antares' },
          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
          next: { block: {
            kind: 'block', type: 'alpha_servo_one_head',
            fields: { ID: 19, ANGLE: 90, TIME: 1000 },
            next: { block: {
              kind: 'block', type: 'alpha_servo_one_head',
              fields: { ID: 20, ANGLE: 105, TIME: 1000 },
              next: { block: {
                kind: 'block', type: 'alpha_wait_seconds',
                inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 0.5 } } } },
                next: { block: {
                  kind: 'block', type: 'alpha_speech_ringtone_notification',
                  fields: { TITLE: 'On The Hunt' },
                  inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                  next: { block: {
                    kind: 'block', type: 'alpha_servo_one_right_arm',
                    fields: { ID: 1, ANGLE: 90, TIME: 1000 },
                    next: { block: {
                      kind: 'block', type: 'alpha_servo_one_right_arm',
                      fields: { ID: 2, ANGLE: 90, TIME: 1000 },
                      next: { block: {
                        kind: 'block', type: 'alpha_servo_one_right_arm',
                        fields: { ID: 3, ANGLE: 90, TIME: 1000 },
                        next: { block: {
                          kind: 'block', type: 'alpha_servo_one_right_arm',
                          fields: { ID: 17, ANGLE: 95, TIME: 1000 },
                          next: { block: {
                            kind: 'block', type: 'alpha_wait_seconds',
                            inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1 } } } },
                            next: { block: {
                              kind: 'block', type: 'alpha_speech_ringtone_notification',
                              fields: { TITLE: 'Polaris' },
                              inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                              next: { block: {
                                kind: 'block', type: 'alpha_servo_one_left_arm',
                                fields: { ID: 4, ANGLE: 90, TIME: 1000 },
                                next: { block: {
                                  kind: 'block', type: 'alpha_servo_one_left_arm',
                                  fields: { ID: 5, ANGLE: 90, TIME: 1000 },
                                  next: { block: {
                                    kind: 'block', type: 'alpha_servo_one_left_arm',
                                    fields: { ID: 6, ANGLE: 90, TIME: 1000 },
                                    next: { block: {
                                      kind: 'block', type: 'alpha_servo_one_left_arm',
                                      fields: { ID: 18, ANGLE: 95, TIME: 1000 },
                                      next: { block: {
                                        kind: 'block', type: 'alpha_wait_seconds',
                                        inputs: { SECONDS: { shadow: { type: 'math_number', fields: { NUM: 1.5 } } } },
                                        next: { block: {
                                          kind: 'block', type: 'alpha_speech_ringtone_notification',
                                          fields: { TITLE: 'Tinkerbell' },
                                          inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 0 } } } },
                                          next: { block: {
                                            kind: 'block', type: 'alpha_action_play_basic',
                                            fields: { SUBCATEGORY: '全身 / 其他動作', NAME: '1464835936001', WAIT: 'true', TIMEOUT: 15 }
                                          } }
                                        } }
                                      } }
                                    } }
                                  } }
                                } }
                              } }
                            } }
                          } }
                        } }
                      } }
                    } }
                  } }
                } }
              } }
            } }
          } }
        },
      ];
      })()
    },
  ]
  };
};

// 首次 load 這一刻先 build 一次, 給 blockly-page.js 的 initWorkspace() 可以
// 照舊直接讀 window.ALPHA_TOOLBOX 用 (不用改那邊的呼叫方式)。之後語言切換
// 由 blockly-i18n.js 負責重新 call window.buildAlphaToolbox() 再更新這個
// reference。
window.ALPHA_TOOLBOX = window.buildAlphaToolbox();

