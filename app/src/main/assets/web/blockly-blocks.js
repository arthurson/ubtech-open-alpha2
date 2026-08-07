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
        .appendField(t('action_play__label'))
        .appendField(new Blockly.FieldTextInput('ACT0'), 'NAME');
      this.appendDummyInput()
        .appendField(t('wait_done__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('wait_done__yes'), 'true'],
          [t('wait_done__no'), 'false'],
        ]), 'WAIT')
        .appendField(t('timeout_seconds__label'))
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_play__tooltip'));
      this.setHelpUrl('');
    }
  };

  // 2026-08 更新: 動作 toolbox 由「總分類 -> 4 個子分類 (各自顏色) 」嘅 nested
  // tree 改做扁平化 —— 一按「動作」呢個分類就即刻見晒所有動作 block, 唔使再逐層
  // 展開先睇到嘢。跟住呢個改動, 動作 block 唔應該再跟住 CATEGORY 轉色 (跟返
  // clr.action 單一顏色就夠, 一眼睇得出「呢粒係動作 block」已經足夠, 唔需要再靠
  // 顏色分辨基本/跳舞/故事/瑜伽), CATEGORY dropdown 亦都要重新喺 block 度顯示返
  // 俾用家自己揀 (以前靠拖邊個 toolbox 子分類嚟預設 CATEGORY 呢招唔再適用, 因為
  // toolbox 已經冇晒果啲子分類)。
  const ACTION_CATEGORY_COLOUR = null; // 保留呢個 const 名做 no-op, 落面唔再用嚟查色

  // 子分類清單: 跟 blockly-actions-data.js 嘅 ALPHA_ACTION_SUBCATEGORIES 一致
  // (每個 main 分類之下嘅 sub 分類, 例如 basic 底下有 移動類/手勢類/頭部類/
  // 表情 / 互動類/全身 / 其他動作)。冇資料就 fallback 做「(全部)」單一分類,
  // 咁樣即使資料未載入都唔會整個 block 壞死。
  function subcategoriesForCategory(catKey) {
    const subs = (window.ALPHA_ACTION_SUBCATEGORIES && window.ALPHA_ACTION_SUBCATEGORIES[catKey]) || [];
    return subs.length ? subs : [t('action_all_subcat')];
  }

  function actionOptionsForSubcategory(catKey, subKey) {
    const bySub = window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY;
    const list = (bySub && bySub[catKey] && bySub[catKey][subKey]) || [];
    if (list.length) return list;
    // Fallback: 資料未載入 (ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY 未有) 就退而求其次
    // 用返冇 sub 分層嘅舊清單, 保證 block 唔會顯示一片空白嘅 dropdown。
    const flat = (window.ALPHA_ACTION_OPTIONS_BY_CATEGORY && window.ALPHA_ACTION_OPTIONS_BY_CATEGORY[catKey]) || [];
    return flat.length ? flat : [[t('action_no_items'), '']];
  }

  // 2026-08 更新: 之前呢度用一粒 block (alpha_action_play_builtin) + CATEGORY
  // dropdown 揀「基本/跳舞/故事/瑜伽」, 依家跟返電話/通知鈴聲個模式, 拆開做
  // 獨立 block ——「播放基本動作」/「播放跳舞動作」/「播放故事動作」/「播放
  // 瑜伽動作」/「播放其他動作」各自一粒, 唔再有 CATEGORY 呢個 field, 淨係留低
  // SUBCATEGORY (子分類) + NAME (動作) 兩層 dropdown。全部都用返 clr.action
  // 同一隻色 (toolbox 攤平嗰陣定嘅做法), CATEGORY 由 block type 本身決定,
  // 寫死喺 interpreter (blockly-run.js) 嗰邊, 唔再靠 field 讀。
  function makeActionCategoryBlock(blockType, catKey, catI18nKey) {
    Blockly.Blocks[blockType] = {
      init: function () {
        const self = this;
        const defaultSubs = subcategoriesForCategory(catKey);
        const defaultSub = defaultSubs[0];
        const catLabel = t(catI18nKey);

        this.appendDummyInput()
          .appendField(t('action_category__prefix') + catLabel)
          .appendField(t('action_category__subcat'))
          .appendField(new Blockly.FieldDropdown(function () {
            return subcategoriesForCategory(catKey).map(function (s) {
              return [(window.ALPHA_SUB_LABEL ? window.ALPHA_SUB_LABEL(s) : s), s];
            });
          }, function (newSub) {
            // 子分類轉咗, 要重新過濾 NAME 個 dropdown options, 揀返嗰個子分類
            // 第一個動作。
            setTimeout(function () {
              const nameField = self.getField('NAME');
              if (nameField) {
                const opts = actionOptionsForSubcategory(catKey, newSub);
                nameField.menuGenerator_ = opts;
                nameField.setValue(opts[0][1]);
              }
            }, 0);
            return newSub;
          }), 'SUBCATEGORY')
          .appendField(t('action_category__name'))
          .appendField(new Blockly.FieldDropdown(function () {
            const block = this.getSourceBlock();
            const sub = block ? block.getFieldValue('SUBCATEGORY') : defaultSub;
            return actionOptionsForSubcategory(catKey, sub || defaultSub);
          }), 'NAME');
        this.appendDummyInput()
          .appendField(t('wait_done__label'))
          .appendField(new Blockly.FieldDropdown([
            [t('wait_done__yes'), 'true'],
            [t('wait_done__no'), 'false'],
          ]), 'WAIT')
          .appendField(t('timeout_seconds__label'))
          .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.action);
        this.setTooltip(t('action_category_tooltip', { cat: catLabel }));
      },
      // Workspace 載入返嚟嗰陣 (XML → block) 要確認 SUBCATEGORY/NAME 依然屬於
      // 呢個 catKey 底下嘅有效值, 唔係就自動修正做第一個有效嘅 sub/動作, 避免
      // 顯示錯配 (道理同舊版 alpha_action_play_builtin 嘅 onchange 一樣, 淨係
      // 唔使再理 CATEGORY, 因為呢度 catKey 係固定嘅)。
      onchange: function (e) {
        if (this.workspace && this.workspace.isDragging && this.workspace.isDragging()) return;
        if (this.__alphaFixingConsistency) return;

        const validSubs = subcategoriesForCategory(catKey);
        const curSub = this.getFieldValue('SUBCATEGORY');
        const subOk = validSubs.indexOf(curSub) !== -1;
        const curName = this.getFieldValue('NAME');
        const wantSub = subOk ? curSub : validSubs[0];
        const validNames = actionOptionsForSubcategory(catKey, wantSub);
        const nameOk = validNames.some(function (pair) { return pair[1] === curName; });

        if (!subOk || !nameOk) {
          this.__alphaFixingConsistency = true;
          try {
            const subField = this.getField('SUBCATEGORY');
            if (subField && !subOk) {
              subField.menuGenerator_ = validSubs.map(function (s) { return [s, s]; });
              subField.setValue(wantSub);
            }
            const nameField = this.getField('NAME');
            if (nameField && !nameOk) {
              nameField.menuGenerator_ = validNames;
              nameField.setValue(validNames.length ? validNames[0][1] : '');
            }
          } finally {
            this.__alphaFixingConsistency = false;
          }
        }
      }
    };
  }
  makeActionCategoryBlock('alpha_action_play_basic', 'basic', 'action_cat_basic');
  makeActionCategoryBlock('alpha_action_play_dance', 'dance', 'action_cat_dance');
  makeActionCategoryBlock('alpha_action_play_story', 'story', 'action_cat_story');
  makeActionCategoryBlock('alpha_action_play_yoga', 'yoga', 'action_cat_yoga');
  makeActionCategoryBlock('alpha_action_play_others', 'others', 'action_cat_others');

  Blockly.Blocks['alpha_action_play_dropdown'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('action_play_live__label'))
        .appendField(new Blockly.FieldDropdown(function () {
          return (window.__alphaActionOptions && window.__alphaActionOptions.length)
            ? window.__alphaActionOptions
            : [[t('action_play_live__not_loaded'), '']];
        }), 'NAME');
      this.appendDummyInput()
        .appendField(t('wait_done__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('wait_done__yes'), 'true'],
          [t('wait_done__no'), 'false'],
        ]), 'WAIT')
        .appendField(t('timeout_seconds__label'))
        .appendField(new Blockly.FieldNumber(15, 1, 120), 'TIMEOUT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_play_live__tooltip'));
    }
  };

  Blockly.Blocks['alpha_action_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('action_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_stop__tooltip'));
    }
  };

  Blockly.Blocks['alpha_action_wait_done'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('action_wait_done__prefix'))
        .appendField(new Blockly.FieldNumber(15, 0, 120), 'TIMEOUT')
        .appendField(t('action_wait_done__suffix'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.action);
      this.setTooltip(t('action_wait_done__tooltip'));
    }
  };



  // ---------------------------------------------------------------------
  // 語音 Speech / TTS / ASR
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_speech_tts'] = {
    init: function () {
      this.appendValueInput('TEXT')
        .setCheck('String')
        .appendField(t('speech_tts__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_tts__engine_nuance'), 'nuance'],
          [t('speech_tts__engine_iflytek'), 'iflytek'],
          [t('speech_tts__engine_android'), 'android'],
        ]), 'ENGINE');
      this.appendDummyInput()
        .appendField(t('speech_tts__voice_label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_tts__voice_default'), ''],
          ['catherine', 'catherine'],
          ['john', 'john'],
          ['小峯 xiaofeng', 'xiaofeng'],
          ['小欣 xiaoyan', 'xiaoyan'],
        ]), 'VOICE');
      this.setInputsInline(false);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_tts__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('speech_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip('/api/speech/stop');
    }
  };

  Blockly.Blocks['alpha_speech_set_mic'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('speech_set_mic__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_set_mic__release'), 'true'],
          [t('speech_set_mic__take'), 'false'],
        ]), 'WAKE');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_set_mic__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_start_asr'] = {
    init: function () {
      this.appendDummyInput().appendField(t('speech_start_asr__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_start_asr__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_set_voice'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('speech_set_voice__label'))
        .appendField(new Blockly.FieldDropdown([
          ['catherine', 'catherine'],
          ['john', 'john'],
          ['小峯 xiaofeng', 'xiaofeng'],
          ['小欣 xiaoyan', 'xiaoyan'],
        ]), 'NAME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_set_voice__tooltip'));
    }
  };

  Blockly.Blocks['alpha_speech_set_language'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('speech_set_lang__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('speech_set_lang__zh'), 'zh_cn'],
          [t('speech_set_lang__en'), 'en_us'],
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
        .appendField(t('speech_self_interrupt__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('speech_self_interrupt__tooltip'));
    }
  };

  // Android 內置電話鈴聲 / 通知鈴聲 —— 清單依家內嵌喺 blockly-ringtone-data.js
  // (由實機 adb 抓一次靜態化, 唔再即時查 /api/audio/ringtones/list)。
  //
  // 2026-08 更新: 之前呢度用一粒 block (alpha_speech_ringtone) + TYPE dropdown
  // 揀「電話/通知」, 依家拆開做兩粒獨立 block ——「播放電話鈴聲」/「播放通知鈴
  // 聲」各自一粒, 唔再有 TYPE 呢個 field。播放依家用 title (唔用 index) 送去
  // /api/audio/ringtones/play_by_title, 由 app 嗰邊用 findRingtoneByTitle() 查
  // 返 Uri (同相機分頁快門聲/停止提示音一樣嘅穩陣機制, 見 MainActivity.java 嘅
  // findRingtoneByTitle() javadoc) —— 完全唔使理 RingtoneManager cursor index
  // 排序係咪跨機一致呢個問題。
  function ringtoneDropdownOptions(fixedType) {
    const titles = (window.ALPHA_RINGTONE_TITLES && window.ALPHA_RINGTONE_TITLES[fixedType]) || [];
    if (!titles.length) return [[t('ringtone__not_loaded'), '']];
    return titles.map(function (title) { return [title, title]; });
  }
  function makeRingtoneBlock(blockType, fixedType, labelKey) {
    Blockly.Blocks[blockType] = {
      init: function () {
        this.appendDummyInput()
          .appendField(t(labelKey))
          .appendField(t('ringtone__sound_label'))
          .appendField(new Blockly.FieldDropdown(function () {
            return ringtoneDropdownOptions(fixedType);
          }), 'TITLE');
        this.appendValueInput('DURATION').setCheck('Number')
          .appendField(t('ringtone__play_label'));
        this.appendDummyInput().appendField(t('ringtone__seconds_suffix'));
        this.setInputsInline(true);
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.speech);
        this.setTooltip(t('ringtone__tooltip', { type: t(fixedType === 'notification' ? 'ringtone__type_notification' : 'ringtone__type_phone') }));
      }
    };
  }
  makeRingtoneBlock('alpha_speech_ringtone_phone', 'ringtone', 'ringtone_phone__label');
  makeRingtoneBlock('alpha_speech_ringtone_notification', 'notification', 'ringtone_notification__label');

  // 2026-08 新增: 停止依家播緊嘅鈴聲/通知聲 (call /api/audio/ringtones/stop)。
  // 兩粒 makeRingtoneBlock() 出品嘅 block 冇 loop, 但用家撳多次「執行」或者個
  // 鈴聲檔本身好長, 之前完全冇辦法喺播完之前打斷佢 —— 呢粒 block 就係俾 Blockly
  // 「例子 5」用嚟做個手動停止掣。
  Blockly.Blocks['alpha_speech_ringtone_stop'] = {
    init: function () {
      this.appendDummyInput().appendField(t('ringtone_stop__label'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.speech);
      this.setTooltip(t('ringtone_stop__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // 伺服 Servo (20 顆) + Sonar
  // ---------------------------------------------------------------------

  // 實際嘅 SERVO_NAMES / SERVO_GROUPS / SERVO_CALIBRATION 資料喺獨立檔案
  // blockly-servo-data.js 定義 (同 app.js 保持一致, 見該檔頭註解), 呢度淨係讀
  // window.ALPHA_SERVO_* 嚟用, 唔重複定義一份會走數嘅副本。
  // 2026-08 更新: 伺服 toolbox 由「總分類 -> 5 個部位子分類 (各自顏色)」嘅 nested
  // tree 改做扁平化 —— 一按「伺服」就即刻見晒所有伺服 block, 唔再逐層展開先見到。
  // 跟住呢個改動, 伺服 block 唔應該再跟部位轉色, 固定用 clr.servo 就夠。
  // servoGroupColour() 淨係留返做 no-op fallback (永遠回傳 clr.servo), 避免要
  // 改晒所有 call 過呢個 function 嘅地方。
  function servoGroups() {
    return (window.ALPHA_SERVO_GROUPS && window.ALPHA_SERVO_GROUPS.length) ? window.ALPHA_SERVO_GROUPS : [
      { key: 'head', label: t('servo_group_head') },
      { key: 'right-arm', label: t('servo_group_right_arm') },
      { key: 'left-arm', label: t('servo_group_left_arm') },
      { key: 'right-leg', label: t('servo_group_right_leg') },
      { key: 'left-leg', label: t('servo_group_left_leg') },
    ];
  }
  function servoGroupColour(key) {
    return clr.servo;
  }
  function servoHomeAngle(id) {
    const cal = window.ALPHA_SERVO_CALIBRATION && window.ALPHA_SERVO_CALIBRATION[Number(id)];
    return cal ? cal.home : 120;
  }
  function servoDropdownForGroup(key) {
    if (window.ALPHA_SERVO_DROPDOWN_FOR_GROUP) return window.ALPHA_SERVO_DROPDOWN_FOR_GROUP(key);
    return [[t('servo_data_not_loaded'), '']];
  }

  // 2026-08 更新: 之前呢度用一粒 block (alpha_servo_one) + GROUP dropdown 揀
  // 「頭/右手/左手/右腳/左腳」, 依家跟返電話/通知鈴聲、動作分類個模式, 拆開做
  // 5 粒獨立 block ——「頭部伺服」/「右手伺服」/「左手伺服」/「右腳伺服」/「左腳
  // 伺服」各自一粒, 唔再有 GROUP 呢個 field, 淨係留低 ID (馬達) + ANGLE + TIME。
  // 五粒都用返 clr.servo 同一隻色, GROUP 由 block type 本身決定, 寫死喺
  // interpreter (blockly-run.js) 嗰邊, 唔再靠 field 讀。
  function makeServoGroupBlock(blockType, groupKey, groupLabelKey, groupIcon) {
    Blockly.Blocks[blockType] = {
      init: function () {
        const self = this;
        const defaultIds = servoDropdownForGroup(groupKey);
        const defaultId = defaultIds.length ? defaultIds[0][1] : '19';
        const groupLabel = t(groupLabelKey);

        // 2026-08 bugfix: 呢個 flag 用嚟分辨「block 啱啱由 init() 建立緊 /
        // 由 XML load 緊 field 值」同「用家真係喺 workspace 度手動揀咗
        // dropdown」。之前冇呢個 guard, 令到 Blockly.Xml.domToBlock() 喺
        // import project 讀返 ID field 個值嗰陣 (即使個值同 default 一樣),
        // 都會觸發落面個 ID dropdown 嘅 validator, 然後個 setTimeout(0)
        // callback 之後會將啱啱先由 XML 讀返嚟、本身已經係啱嘅 ANGLE 值
        // 覆蓋番做 servoHomeAngle(newId) (homepoint) —— 因為 XML loader
        // 對逐個 field setFieldValue() 係 synchronous, 但呢個 auto-set
        // homepoint 嘅 side effect 用咗 setTimeout(0) 變咗 async, 於是喺
        // loader 成串 field 都設完之後先至執行, 結果將 ANGLE 撞返做 120
        // 呢類 homepoint 數值, 令成個 import 落嚟嘅動作全部變晒做企定姿勢。
        // 而家淨係喺呢粒 block 已經 init 完 (self.servoBlockReady_ = true)
        // 之後, 先俾 validator 做呢個 auto-homepoint 嘅 side effect;
        // domToBlock() 入面所有 field 都係喺 init() 跑緊嗰陣或者跑完之後
        // 好快發生, 用呢個 flag 就可以確保「programmatic load」同「用家手
        // 動揀 dropdown」分得開。
        self.servoBlockReady_ = false;

        this.appendDummyInput()
          .appendField((groupIcon ? groupIcon + ' ' : '') + groupLabel + t('servo_group__motor_suffix'))
          .appendField(new Blockly.FieldDropdown(function () {
            return servoDropdownForGroup(groupKey);
          }, function (newId) {
            // 揀咗另一粒馬達 (同一 group 之內) 之後, 要即刻將 ANGLE 校返做
            // 嗰粒馬達自己嘅 homepoint —— 唔同馬達嘅 home 唔一定係 120 (例如
            // 右腳 #8 home=65, #9 home=145, 左腳 #13 home=175 等)。淨係喺
            // block 已經 ready (即係用家手動操作, 唔係 XML import 緊) 先做
            // 呢個 side effect, 否則 import project 嗰陣會將啱啱讀返嚟嘅
            // ANGLE 值覆蓋番做 homepoint。
            if (self.servoBlockReady_) {
              setTimeout(function () {
                const angleField = self.getField('ANGLE');
                if (angleField && newId) angleField.setValue(servoHomeAngle(newId));
              }, 0);
            }
            return newId;
          }), 'ID');
        this.appendDummyInput()
          .appendField(t('servo_group__angle_label'))
          .appendField(new Blockly.FieldNumber(servoHomeAngle(defaultId), 0, 255, 1, function (newVal) {
            // Field-level validator: 揀住邊粒馬達就 clamp 喺嗰粒嘅 min/max 校準
            // 範圍之內, 唔可以送出會撞機械極限嘅角度 (見 app.js clampServoAngle()
            // 嘅同一個安全設計, 呢度喺 UI 層面提早擋, 執行時 blockly-run.js 會再
            // clamp 多一次做保險)。
            const block = this.getSourceBlock();
            const id = block ? block.getFieldValue('ID') : null;
            if (id && window.ALPHA_SERVO_CLAMP) return window.ALPHA_SERVO_CLAMP(id, Math.round(newVal));
            return Math.round(newVal);
          }), 'ANGLE')
          .appendField(t('servo_group__time_label'))
          .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
        this.setPreviousStatement(true, null);
        this.setNextStatement(true, null);
        this.setColour(clr.servo);
        this.setTooltip(t('servo_group__tooltip', { group: groupLabel }));

        // 用 setTimeout(0) 押後至下一個 event loop tick 先將 flag 揭做
        // true —— Blockly.Xml.domToBlock() 對 ID / ANGLE / TIME 呢啲
        // field 嘅 setFieldValue() 全部係喺呢個 init() call 完、返出去
        // domToBlock() 嗰個 synchronous 流程入面即刻發生, 所以一定會喺
        // 呢個 setTimeout(0) callback 之前跑晒。之後如果用家先至係
        // workspace 度真係手郁個 dropdown, 嗰陣 flag 已經係 true, 先會
        // 觸發上面 auto-set homepoint 嘅 side effect。
        setTimeout(function () { self.servoBlockReady_ = true; }, 0);
      }
    };
  }
  makeServoGroupBlock('alpha_servo_one_head', 'head', 'servo_group_head', '🧠');
  makeServoGroupBlock('alpha_servo_one_right_arm', 'right-arm', 'servo_group_right_arm', '💪');
  makeServoGroupBlock('alpha_servo_one_left_arm', 'left-arm', 'servo_group_left_arm', '💪');
  makeServoGroupBlock('alpha_servo_one_right_leg', 'right-leg', 'servo_group_right_leg', '🦵');
  makeServoGroupBlock('alpha_servo_one_left_leg', 'left-leg', 'servo_group_left_leg', '🦵');

  Blockly.Blocks['alpha_servo_all'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_all__label'))
      this.appendValueInput('ANGLES').setCheck('String');
      this.appendDummyInput()
        .appendField(t('servo_group__time_label'))
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_all__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_all_helper'] = {
    // 方便組 20 個數值成一個 CSV string, 免得用家自己打逗號
    init: function () {
      this.appendDummyInput().appendField(t('servo_helper__label'));
      for (let i = 1; i <= 20; i++) {
        if ((i - 1) % 4 === 0) this.appendDummyInput();
        this.appendValueInput('A' + i).setCheck('Number')
          .appendField('#' + i);
      }
      this.setInputsInline(true);
      this.setOutput(true, 'String');
      this.setColour(clr.servo);
      this.setTooltip(t('servo_helper__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_home'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_home__label'))
        .appendField(t('servo_group__time_label'))
        .appendField(new Blockly.FieldNumber(1000, 20, 10000, 1), 'TIME');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_home__tooltip'));
    }
  };

  Blockly.Blocks['alpha_servo_sonar'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('servo_sonar__label'))
        .appendField(new Blockly.FieldNumber(30, 0, 999, 1), 'DIST')
        .appendField(t('servo_sonar__off_hint'));
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.servo);
      this.setTooltip(t('servo_sonar__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // LED
  // ---------------------------------------------------------------------

  // ⚠️ 呢 3 個原本係 module-level const, 淨係喺檔案首次 load 嗰一刻用 t()
  // 計值一次, 之後永遠都係嗰個語言 (同 module-level 嘅
  // ACTION_CATEGORIES/LED_PRESETS 呢類靜態陣列一樣嘅陷阱) —— 語言切換之後
  // 就算靠 XML roundtrip 令 block 重新 init(), init() 入面攞到嘅都仲係嗰份
  // 舊語言嘅陣列引用, 唔會跟住變。改做 function, 每次 init() 入面即時 call
  // 先攞到當前語言嘅版本。
  function ledColours() {
    return [
      [t('led_colour_red'), '1'], [t('led_colour_green'), '2'], [t('led_colour_blue'), '3'], [t('led_colour_yellow'), '4'],
      [t('led_colour_purple'), '5'], [t('led_colour_cyan'), '6'], [t('led_colour_white'), '7'],
    ];
  }
  function ledPresetsHead() {
    return [
      [t('led_preset_on'), 'long'], [t('led_preset_flash'), 'flash'], [t('led_preset_breathe'), 'breathe'],
      [t('led_preset_chase'), 'chase'], [t('led_preset_dual'), 'dual'], [t('led_preset_stop'), 'stop'],
    ];
  }
  function ledPresetsEye() {
    return [
      [t('led_preset_on'), 'long'], [t('led_preset_flash'), 'flash'],
      [t('led_preset_chase'), 'chase'], [t('led_preset_dual'), 'dual'], [t('led_preset_stop'), 'stop'],
    ];
  }

  Blockly.Blocks['alpha_led_head'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_head__label'))
        .appendField(new Blockly.FieldDropdown(ledPresetsHead()), 'PRESET')
        .appendField(t('led__colour_label'))
        .appendField(new Blockly.FieldDropdown(ledColours()), 'COLOR')
        .appendField(t('led__brightness_label'))
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_head__tooltip'));
    }
  };

  Blockly.Blocks['alpha_led_eye'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_eye__label'))
        .appendField(new Blockly.FieldDropdown(ledPresetsEye()), 'PRESET')
        .appendField(t('led__colour_label'))
        .appendField(new Blockly.FieldDropdown(ledColours()), 'COLOR')
        .appendField(t('led__brightness_label'))
        .appendField(new Blockly.FieldNumber(9, 1, 9, 1), 'BRIGHT');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_eye__tooltip'));
    }
  };

  Blockly.Blocks['alpha_led_mouth'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('led_mouth__label'))
        .appendField(new Blockly.FieldDropdown([
          [t('led_preset_breathe_mouth'), 'on'],
          [t('led_preset_off'), 'off'],
        ], function (newMode) {
          const block = this.getSourceBlock();
          if (block) {
            const speedInput = block.getInput('SPEED_ROW');
            if (speedInput) speedInput.setVisible(newMode === 'on');
            if (block.rendered) block.render();
          }
          return newMode;
        }), 'MODE');
      this.appendDummyInput('SPEED_ROW')
        .appendField(t('led__speed_label'))
        .appendField(new Blockly.FieldNumber(1500, 0, 5000, 1), 'SPEED');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.led);
      this.setTooltip(t('led_mouth__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // 2026-08 更新: 「裝置資訊」分類 (電池電量/充電中/WiFi IP/藍牙/UUID 查詢/
  // 系統狀態) 同「相機/音效」分類 (快照/解像度/測試音/喇叭串流) 已經整個移除,
  // block 定義同 interpreter case 都一齊刪咗, 唔再保留 (見對應嘅 blockly-run.js
  // / blockly-toolbox.js)。
  // ---------------------------------------------------------------------


  // ---------------------------------------------------------------------
  // 事件 (WebSocket) — hat blocks, 用嚟起一個「當 XXX 事件發生」嘅事件驅動流程
  // ---------------------------------------------------------------------

  // 感應器開關 —— accel/sonar 呢兩粒「觸發」hat block (下面嘅
  // alpha_event_accel_threshold / alpha_event_sonar_triggered) 淨係監聽,
  // 唔會自己開感應器, 一定要感應器本身已經開咗先會有 WebSocket 事件送到。
  // 之前 blockly 入面完全冇 block 可以開/關 accel (/api/alpha2/accelerometer/set)
  // 或者 sonar (/api/alpha2/servo/sonar, distance=0=關/distance>0=開並設門檻),
  // 要靠用家自己記得先喺主控制面板嘅「感應」分頁手動開好, 呢度補返呢兩粒。
  Blockly.Blocks['alpha_sensor_accel_toggle'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('sensor_accel_toggle__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip(t('sensor_accel_toggle__tooltip'));
    }
  };

  Blockly.Blocks['alpha_sensor_sonar_toggle'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('sensor_sonar_toggle__label'))
        .appendField(new Blockly.FieldDropdown([[t('toggle_on'), 'true'], [t('toggle_off'), 'false']]), 'ON')
        .appendField(t('sensor_sonar_toggle__dist'))
        .appendField(new Blockly.FieldNumber(30, 0, 100, 1), 'DIST');
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.sensor);
      this.setTooltip(t('sensor_sonar_toggle__tooltip'));
    }
  };

  // 加速度計 / 聲納「觸發」專用 hat block —— 直接俾用家設門檻, 貼近「觸發」呢個
  // 語意 (唔係「事件一到就執行」, 而係「事件到咗、但要數值過咗門檻先執行」),
  // 所以邏輯喺 blockly-run.js 用獨立嘅 accelHandlers/sonarHandlers 處理。
  Blockly.Blocks['alpha_event_accel_threshold'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('event_accel__label'))
        .appendField(new Blockly.FieldDropdown([[t('event_accel__axis_x'), 'x'], [t('event_accel__axis_y'), 'y'], [t('event_accel__axis_z'), 'z']]), 'AXIS')
        .appendField(new Blockly.FieldDropdown([[t('event_accel__cmp_gt'), 'gt'], [t('event_accel__cmp_lt'), 'lt']]), 'CMP')
        // 2026-08 修正: min 之前係 0, 但落面 blockly-run.js 嘅比較邏輯係
        // `Math.abs(讀數) 同 threshold 比較`, 用家原意應該可以直接打負數
        // 門檻 (例如想淨係揀 X 軸「向負方向傾側」嗰種語意, 或者純粹想打
        // 一個負數落去做視覺上嘅提示), 但之前 min=0 令個 field 完全打唔到
        // 負號, 打親就即刻俾 FieldNumber 夾返做 0。而家 min 改做 -20, 同
        // max 20 對稱, 用家可以自由打負數落去。
        .appendField(new Blockly.FieldNumber(2, -20, 20, 0.1), 'THRESHOLD')
        .appendField(t('event_accel__trigger_suffix'));
      this.appendDummyInput()
        .appendField(t('event_accel__store_prefix'))
        // 2026-08 更新: 之前用 Blockly.FieldVariable, 令個 field 帶埋 Blockly
        // 內建嘅「重新命名變數/刪除變數」context menu —— 但呢個變數其實只係
        // blockly-run.js 自己嗰個簡易 variables Map 嘅 key (見 getFieldValue
        // ('VAR') 嘅用法), 完全冇經過 Blockly 官方嘅 workspace variable
        // model, 「重新命名/刪除」呢兩個選項對用家嚟講毫無意義 (改極都係得
        // 返呢粒 block 自己用, 唔會有第二個 block 分享緊呢個名), 徒添混亂。
        // 改用 FieldLabelSerializable: 純顯示文字、唔可以編輯, 但個值一樣會
        // 存入 XML (field serialization) 俾 getFieldValue('VAR') 讀到, 落面
        // 邏輯完全唔使改。
        //
        // ⚠️ i18n 刻意跳過: 呢個 field 嘅顯示文字同時係 blockly-run.js
        // variables Map 嘅實際 key (存入 XML 嗰個值), 唔淨係 UI 顯示。如果
        // 跟語言切換變成英文, 已經儲存/匯出咗嘅 .xml 程式入面嘅變數名會同
        // 新開嘅 block 對唔上, 令舊程式載入之後讀唔到呢個變數。所以固定用
        // 中文, 唔跟 uiLang 變, 避免破壞已存在嘅程式/XML。
        .appendField(new Blockly.FieldLabelSerializable('加速度計讀數'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip(t('event_accel__tooltip'));
    }
  };

  Blockly.Blocks['alpha_event_sonar_triggered'] = {
    init: function () {
      this.appendDummyInput()
        .appendField(t('event_sonar__label'))
        .appendField(t('event_sonar__store_prefix'))
        // 同 alpha_event_accel_threshold 一樣道理: 呢個純粹係
        // blockly-run.js 自己 variables Map 嘅 key, 唔使 Blockly 官方變數
        // model 嗰套「重新命名/刪除」UI, 改用 FieldLabelSerializable。
        // ⚠️ i18n 刻意跳過, 原因同上 (呢個值同時係變數 key, 跟語言變會撞
        // 散舊 XML)。
        .appendField(new Blockly.FieldLabelSerializable('聲納資料'), 'VAR');
      this.appendStatementInput('DO');
      this.setColour(clr.sensor);
      this.setTooltip(t('event_sonar__tooltip'));
    }
  };

  // ---------------------------------------------------------------------
  // 流程控制：等待 / 印出訊息(log) / 重複次數上限保護
  // ---------------------------------------------------------------------

  Blockly.Blocks['alpha_wait_seconds'] = {
    init: function () {
      this.appendValueInput('SECONDS').setCheck('Number')
        .appendField(t('wait_seconds__label'));
      this.appendDummyInput().appendField(t('wait_seconds__suffix'));
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('wait_seconds__tooltip'));
    }
  };

  Blockly.Blocks['alpha_log'] = {
    init: function () {
      this.appendValueInput('MSG').setCheck(null)
        .appendField(t('log__label'));
      this.setInputsInline(true);
      this.setPreviousStatement(true, null);
      this.setNextStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('log__tooltip'));
    }
  };

  Blockly.Blocks['alpha_stop_program'] = {
    init: function () {
      this.appendDummyInput().appendField(t('stop_program__label'));
      this.setPreviousStatement(true, null);
      this.setColour(clr.flow);
      this.setTooltip(t('stop_program__tooltip'));
    }
  };

})();
