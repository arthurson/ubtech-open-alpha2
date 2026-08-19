// Open Alpha2 — Toolbox 定義 (Blockly JSON 格式)。
// 分咗 11 個分類：控制流程放最頂 (最常用), 之後跟 API 分組, 最後係 Blockly 標準
// 邏輯/迴圈/數學/文字/變數/自訂函式。
//
// 包做 function (而唔係一次性 object literal) 係俾語言切換用: 分類名/範例入面
// 嘅文字都經 t() 讀緊 window.ALPHA_BLOCK_I18N, 但 t() 嘅結果喺呢個檔案 load
// 嗰一刻就已經計死咗 (JS 冇 lazy evaluation) —— 如果淨係 assign 一次做靜態
// object, 之後切語言就算 updateToolbox() 都係攞返嗰份舊語言嘅快照。改用
// window.buildAlphaToolbox() 令 blockly-i18n.js 可以喺切換語言嗰陣重新 call
// 一次, 攞到用返新語言嘅版本。window.ALPHA_TOOLBOX 保留做「最近一次 build
// 出嚟嘅版本」, 等 initWorkspace() (blockly-page.js) 首次注入 workspace 嗰陣
// 唔使改呼叫方式。
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
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_event_accel_threshold' },
        { kind: 'block', type: 'alpha_event_sonar_triggered' },
      ]
    },
    {
      // 2026-08 更新: 動作 block 由一粒 alpha_action_play_builtin (帶 CATEGORY
      // dropdown) 拆咗做獨立 block (基本/跳舞/故事/瑜伽/其他), 跟返電話/通知
      // 鈴聲個模式 (見 blockly-blocks.js makeActionCategoryBlock())。分類本身
      // 依然一隻色 (colour: '20', 同 clr.action 一致), 一按就見晒所有動作
      // block, 唔使逐層展開。
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
        { kind: 'block', type: 'alpha_speech_start_asr' },
        { kind: 'block', type: 'alpha_speech_set_voice' },
        { kind: 'block', type: 'alpha_speech_set_language' },
        { kind: 'block', type: 'alpha_speech_self_interrupt' },
        { kind: 'sep' },
        { kind: 'block', type: 'alpha_speech_ringtone_phone', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 10 } } } } },
        { kind: 'block', type: 'alpha_speech_ringtone_notification', inputs: { DURATION: { shadow: { type: 'math_number', fields: { NUM: 5 } } } } },
        { kind: 'block', type: 'alpha_speech_ringtone_stop' },
      ]
    },
    {
      // 2026-08 更新: 伺服 block 由一粒 alpha_servo_one (帶 GROUP dropdown) 拆咗
      // 做 5 粒獨立 block (頭/右手/左手/右腳/左腳), 跟返電話/通知鈴聲、動作分類
      // 個模式 (見 blockly-blocks.js makeServoGroupBlock())。分類本身依然一隻色
      // (colour: '230', 同 clr.servo 一致), 一按就見晒所有伺服 block, 唔使逐層
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
    // 2026-08 更新: 「相機 / 音效」同「裝置資訊」呢兩個分類已經整個移除, block
    // 定義同 interpreter case 都一齊刪咗, 唔再保留 (見 blockly-blocks.js /
    // blockly-run.js)。感應器 (sonar/accel) 開關同事件維持喺上面「🔔 事件」
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
      // 2026-08 新增:「範例」分類, 放已經組合好嘅 block 組合, 用家由 toolbox
      // 拖出嚟就已經係一串裝好晒嘅 next-chain (唔使自己逐粒拼), 可以即刻試跑或者
      // 當起點再修改。用 toolbox JSON 嘅巢狀寫法: 第一粒 block 底下用
      // "next": { "block": {...} } 一路掛落去, 對應 Blockly 內部 next-connection
      // statement chain, 呢個係 toolbox 官方支援嘅寫法, 唔使自己額外寫 XML。
      //
      // 2026-08 更新: 原本呢度有 5 個手寫嘅簡短示範, 依家改做用家提供嘅 2 個
      // 實機測試過嘅完整程式 (alpha2-program-2026-08-04-04-02-26.xml /
      // alpha2-program-2026-08-04-05-43-58.xml, 經 workspace 匯出), 轉做
      // toolbox JSON 格式後直接放呢度。轉換時 <value><shadow>...</shadow></value>
      // 對應做 inputs.{NAME}.shadow, <next><block>...</block></next> 對應
      // 巢狀 next.block, 淨係去咗 XML 專屬嘅 id/x/y 定位屬性 (toolbox flyout
      // 唔需要呢啲, 拖出嚟落 workspace 會由 Blockly 自己重新分配)。
      //
      // 注意: fields.SUBCATEGORY 呢個值 ('表情 / 互動類' 等) 一定要維持中文,
      // 因為佢係 blockly-actions-data.js 真實子分類清單嘅其中一個 key (跟機身
      // 韌體實測分類名, 唔係呢頁自己嘅顯示文字), 唔跟 uiLang 轉——換咗英文個
      // dropdown 就搵唔返呢個分類。fields.TITLE (鈴聲名, 例如 'World'/'Antares'/
      // 'On The Hunt') 同理: 對應 blockly-ringtone-data.js 嘅實際鈴聲標題, 係
      // 資料值唔係顯示文字, 一樣要保持原文。
      kind: 'category', name: t('toolbox_cat_examples'), colour: '15',
      contents: (function () {
        // 例子 1 嘅 TTS engine 要跟住顯示語言變: 中文用 iflytek, 英文用
        // nuance (對照 AIDL_REFERENCE_ALPHA2.md 1.1 節: iFlytek 對應 zh_cn, Nuance
        // 對應 en_us, 呢個係機身引擎本身嘅語言限制, 唔係隨便揀)。原始 XML
        // (用家喺 workspace 度手動組出嚟嘅) 淨係得一個固定語言版本, 兩句
        // TTS 都係 iflytek —— 但依家個示範文字本身已經跟 uiLang 切換
        // (toolbox_example1_hello/happy), 如果 ENGINE 唔跟住變, 英文模式就會
        // 變成「用中文引擎讀緊英文字」呢種錯配, 所以呢度用 function 動態決定,
        // 唔係好似 SUBCATEGORY/TITLE 咁固定唔變 (嗰啲係機身查表用嘅 data key,
        // 呢度嘅 ENGINE 係「示範用嘅參數」, 性質唔同)。
        const ttsEngine = ((window.getUiLanguage && window.getUiLanguage()) || 'zh') === 'zh' ? 'iflytek' : 'nuance';
        return [
        // 例子 1: 來電效果 —— 電話鈴聲 → 打招呼 → 播動作 (唔等) → 再講嘢 →
        // 等 7 秒 → 手動停止鈴聲。示範: 鈴聲 + 語音 + 動作點樣夾埋一齊玩,
        // 「等待完成」揀 false 令個動作同後面嘅語音幾乎同一時間發生。
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
        // 多個伺服 block 同鈴聲 block 點樣夾埋做一個帶節奏感嘅擺動組合,
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

// 首次 load 呢一刻先 build 一次, 俾 blockly-page.js 嘅 initWorkspace() 可以
// 照舊直接讀 window.ALPHA_TOOLBOX 用 (唔使改嗰邊嘅呼叫方式)。之後語言切換
// 由 blockly-i18n.js 負責重新 call window.buildAlphaToolbox() 再更新呢個
// reference。
window.ALPHA_TOOLBOX = window.buildAlphaToolbox();
