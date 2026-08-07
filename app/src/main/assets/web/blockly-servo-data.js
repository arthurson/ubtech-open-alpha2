// Open Alpha2 — Blockly 專用伺服馬達分組/校準資料。
//
// 呢份資料刻意同 app.js 嘅 SERVO_CALIBRATION / SERVO_NAMES / SERVO_GROUPS 保持
// 完全一致 (見 app.js 「Servo calibration」段落嘅註解: min/max/home 值嚟自實機
// 用「二代舵機校準軟件 1.0.0.4」量出嚟嘅硬件校準, 唔係 SDK 協定原生值, 換過機
// 就要重新量)。獨立開一份而唔直接 include app.js, 係因為 blockly.html 唔想拉埋
// 成個控制面板嘅 DOM/事件邏輯做依賴 —— 呢個分頁本身就設計成可以獨立開嚟用
// (見 blockly-page.js 檔頭註解)。
//
// ⚠ 如果之後喺「伺服」分頁 (app.js) 重新量過校準值, 呢度都要跟住手動同步一次。

(function () {
  const SERVO_CALIBRATION = {
    1:  { min: 5,   max: 235, home: 120 },
    2:  { min: 50,  max: 210, home: 120 },
    3:  { min: 55,  max: 185, home: 120 },
    4:  { min: 5,   max: 235, home: 120 },
    5:  { min: 30,  max: 190, home: 120 },
    6:  { min: 55,  max: 185, home: 120 },
    7:  { min: 100, max: 200, home: 120 },
    8:  { min: 20,  max: 220, home: 65  },
    9:  { min: 35,  max: 230, home: 145 },
    10: { min: 35,  max: 215, home: 140 },
    11: { min: 100, max: 190, home: 120 },
    12: { min: 40,  max: 140, home: 120 },
    13: { min: 20,  max: 220, home: 175 },
    14: { min: 10,  max: 205, home: 95  },
    15: { min: 25,  max: 205, home: 100 },
    16: { min: 50,  max: 140, home: 120 },
    17: { min: 95,  max: 125, home: 120 },
    18: { min: 95,  max: 125, home: 120 },
    19: { min: 75,  max: 165, home: 120 },
    20: { min: 105, max: 155, home: 120 },
  };

  // 20 顆伺服馬達嘅顯示名, 中英對照直接跟 app.js 嘅 SERVO_NAMES (主控制面板
  // 「伺服」分頁用緊嗰份) 保持一致, 唔重新譯一次, 避免兩邊用詞唔夾。
  const SERVO_NAMES = {
    1:  { zh: '右肩上下', en: 'R Shoulder Pitch' },
    2:  { zh: '右肩左右', en: 'R Shoulder Roll' },
    3:  { zh: '右肘',     en: 'R Elbow' },
    4:  { zh: '左肩上下', en: 'L Shoulder Pitch' },
    5:  { zh: '左肩左右', en: 'L Shoulder Roll' },
    6:  { zh: '左肘',     en: 'L Elbow' },
    7:  { zh: '右股左右', en: 'R Hip Roll' },
    8:  { zh: '右股上下', en: 'R Hip Pitch' },
    9:  { zh: '右膝',     en: 'R Knee' },
    10: { zh: '右腳掌上下', en: 'R Ankle Pitch' },
    11: { zh: '右腳掌左右', en: 'R Ankle Roll' },
    12: { zh: '左股左右', en: 'L Hip Roll' },
    13: { zh: '左股上下', en: 'L Hip Pitch' },
    14: { zh: '左膝',     en: 'L Knee' },
    15: { zh: '左腳掌上下', en: 'L Ankle Pitch' },
    16: { zh: '左腳掌左右', en: 'L Ankle Roll' },
    17: { zh: '右指',     en: 'R Hand' },
    18: { zh: '左指',     en: 'L Hand' },
    19: { zh: '頭左右',   en: 'Head Yaw' },
    20: { zh: '頭上下',   en: 'Head Pitch' },
  };
  // 顯示用: 攞返當前語言嘅馬達名。t() call time 計值, 每次 call 都會跟住
  // window.getUiLanguage() 攞返最新語言 (唔係 module-level 計死一次) ——
  // 同 blockly-blocks.js 個 LED_COLOURS/LED_PRESETS_HEAD 之前中招嘅陷阱一樣,
  // 呢個 helper 本身唔 cache 結果, 淨係喺實際攞名嗰一刻先讀字典。
  function servoDisplayName(id) {
    const entry = SERVO_NAMES[id];
    if (!entry) return (window.t ? window.t('servo_name_fallback', { id: id }) : ('Servo ' + id));
    const lang = (window.getUiLanguage && window.getUiLanguage()) || 'zh';
    return lang === 'zh' ? entry.zh : entry.en;
  }

  // 分組: 頭 / 右手 / 左手 / 右腳 / 左腳 (跟 app.js SERVO_GROUPS 一致), 每組一個
  // 顏色, 令 Blockly 分類、以及個 block 本身嘅顏色都可以直接跟返呢個分組。
  // ⚠ label 呢個 field 淨係內部參考用, 冇任何地方讀佢嚟做顯示 (搜索過成個
  // blockly-*.js 確認) —— 顯示用嘅分組名由 blockly-blocks-i18n-data.js 嘅
  // servo_group_head/right_arm/left_arm/right_leg/left_leg 呢幾個 key 提供
  // (跟 t() 做 i18n), 唔係呢度嘅 label。保留 label 純粹方便睇 code 對得上邊組。
  const SERVO_GROUPS = [
    { key: 'head',      label: '頭',  icon: '🧠', ids: [19, 20],             colour: '#7c3aed' },
    { key: 'right-arm', label: '右手', icon: '💪', ids: [1, 2, 3, 17],       colour: '#2563eb' },
    { key: 'left-arm',  label: '左手', icon: '💪', ids: [4, 5, 6, 18],       colour: '#0891b2' },
    { key: 'right-leg', label: '右腳', icon: '🦵', ids: [7, 8, 9, 10, 11],   colour: '#ea580c' },
    { key: 'left-leg',  label: '左腳', icon: '🦵', ids: [12, 13, 14, 15, 16], colour: '#16a34a' },
  ];

  function groupOfServoId(id) {
    const found = SERVO_GROUPS.filter(function (g) { return g.ids.indexOf(Number(id)) !== -1; })[0];
    return found ? found.key : null;
  }

  function servoDropdownForGroup(groupKey) {
    const g = SERVO_GROUPS.filter(function (x) { return x.key === groupKey; })[0];
    const ids = g ? g.ids : [];
    return ids.map(function (id) {
      return ['#' + id + ' ' + servoDisplayName(id), String(id)];
    });
  }

  /** Clamp a value into [min,max] for the given servo id (string or number).
   *  Mirrors app.js clampServoAngle() exactly — this is what guarantees a
   *  Blockly-issued "set angle" can never exceed the calibrated safe range. */
  function clampServoAngle(id, value) {
    const cal = SERVO_CALIBRATION[Number(id)];
    if (!cal) return value;
    return Math.max(cal.min, Math.min(cal.max, value));
  }

  window.ALPHA_SERVO_CALIBRATION = SERVO_CALIBRATION;
  window.ALPHA_SERVO_NAMES = SERVO_NAMES;
  window.ALPHA_SERVO_GROUPS = SERVO_GROUPS;
  window.ALPHA_SERVO_GROUP_OF = groupOfServoId;
  window.ALPHA_SERVO_DROPDOWN_FOR_GROUP = servoDropdownForGroup;
  window.ALPHA_SERVO_CLAMP = clampServoAngle;
})();
