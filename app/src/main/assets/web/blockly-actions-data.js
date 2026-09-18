// Open Alpha2 — 機械人內建動作清單 (靜態資料)。
//
// ⚠️ 2026-08 更新: 之前這裡拿了 260 個動作 (action_classified.txt 人手分類版本),
// 但實測發現機械人實機 preset 只有 199 個 (見同目錄外用家提供的 actionInfo.txt,
// 即機身實際回傳的 action list dump) —— 之前那 260 個裡面, 有 49 個是「舊 id
// 重複」(同一個動作機身後期換了新 id, 但舊版資料兩個 id 都留下了, 令 dropdown
// 見到兩份一樣的動作), 還有 12 個是真正完全沒有對應 (連新舊 id 都找不到, 應該是
// 部分機型/韌體版本先有的擴充動作, 不是全部機都有)。現在這份清單改為完全跟
// actionInfo.txt 的 id 做 preset (不理個 id 新舊, 正在僅隨機清單為準),
// main/sub 分類就沿用回舊版 action_classified.txt 已經人手核對好的對照表 (用 id
// 查回那個動作原本屬於哪個 main/sub, 全部都在舊表裡面找得到, 沒有再重新分類一次
// 的需要)。
//
// ⚠️ 2026-08 補充更新: 用家提供了一份完整版 actionInfo.txt (200 個 id, 比第一次
// 那 199 個多了 1464835936008 / ACT7, 之前漏了沒有收錄), 並且證實了 nameEn 全部
// 已經是機身原廠英文名 (不是之前為了趕住做 i18n 而人手估譯那批)。已經用這份做
// 權威來源, 補回 ACT7, 同埋將 18 個之前人手估譯錯了/風格不一致的 nameEn
// (主要是故事類同幾隻情緒/動作類) 訂正做機身原廠寫法, 包括原廠慣用的細楷開頭
// 風格 (例如 'the deer and the lion' 不是 'The Deer and the Lion') —— 保持
// 同其餘 199 個動作一致的大小寫風格, 不要僅那 18 個變完大楷開頭。現在全部
// 200 個 id 都經核對, 對得上機身 actionInfo.txt 的原廠 nameEn。
//
// 分類邏輯: 5 個 main (basic/dance/story/yoga/others) 之下再分 15 個 sub, 對照
// 如下 (main -> [sub,...]):
//   basic: 移動類, 手勢類, 頭部類, 表情 / 互動類, 全身 / 其他動作
//   dance: 兒童歌曲 / 卡通舞蹈, 流行 / 節奏舞蹈, 品牌 / 客製舞蹈
//   story: 中國寓言, 西方寓言 / 故事
//   yoga: 站立式 / 平衡式, 伸展式, 騎馬式, 踢腿 / 動態式
//   others: Neuron 起身動作 (BackStand/FrontStand, 來自 Neuron 專案, 未經
//     機身實測確認, 見底下 ALPHA_ACTIONS 對應 entry 的註解)
//
// UI 結構: 播放內建動作 block 現在是「主分類 -> 子分類 -> 動作」3 層 dropdown
// (對應 blockly-blocks.js 的 alpha_action_play_builtin), 子分類轉了要重新過濾
// 動作清單, 主分類轉了要重新過濾子分類清單, 兩層都要重設落第一個選項。

(function () {
  // label 用 window.t() 讀回 blockly-blocks-i18n-data.js 已經有的
  // action_cat_basic/dance/story/yoga key (同 makeActionCategoryBlock() 用的
  // 分類名一致, 沒有必要為這個下拉前綴另開一份新字典)。同 blockly-toolbox.js
  // 一樣的原因, t() 是 call 那一刻計值, 所以要包做 function, 等
  // blockly-i18n.js 切語言當時可以重新 call 拿新版本。
  function buildActionCategories() {
    return [
      { key: 'basic',  label: t('action_cat_basic'), color: '#3b7dff' },
      { key: 'dance',  label: t('action_cat_dance'), color: '#db2777' },
      { key: 'story',  label: t('action_cat_story'), color: '#d97706' },
      { key: 'yoga',   label: t('action_cat_yoga'), color: '#16a34a' },
      { key: 'others', label: t('action_cat_others'), color: '#6b7280' },
    ];
  }
  var ACTION_CATEGORIES = buildActionCategories();
  window.ALPHA_ACTION_CATEGORIES = ACTION_CATEGORIES;
  window.rebuildAlphaActionCategories = function () {
    ACTION_CATEGORIES.length = 0;
    ACTION_CATEGORIES.push.apply(ACTION_CATEGORIES, buildActionCategories());
  };

  // 子分類次序: 跟 action_classified.txt 裡面出現的次序 (每個 main 之下)。
  var ACTION_SUBCATEGORIES = {
    basic: ['移動類', '手勢類', '頭部類', '表情 / 互動類', '全身 / 其他動作'],
    dance: ['兒童歌曲 / 卡通舞蹈', '流行 / 節奏舞蹈', '品牌 / 客製舞蹈'],
    story: ['中國寓言', '西方寓言 / 故事'],
    yoga: ['站立式 / 平衡式', '伸展式', '騎馬式', '踢腿 / 動態式'],
    others: ['Neuron 企身動作'],
  };
  window.ALPHA_ACTION_SUBCATEGORIES = ACTION_SUBCATEGORIES;

  // sub 的內部值 (中文, 上面 ACTION_SUBCATEGORIES 那些字串) 同時是
  // alpha_action_play_basic/dance/story/yoga/others 的 SUBCATEGORY field 存入
  // XML 那個值, 不可以改 (詳見 blockly-blocks-i18n-data.js 開頭 sub_label_*
  // 那段註解)。這個對照表僅用來將內部值 map 去顯示用的 i18n key, 不改內部值
  // 本身。
  var SUB_LABEL_KEY = {
    '移動類': 'sub_label_move',
    '手勢類': 'sub_label_gesture',
    '頭部類': 'sub_label_head',
    '表情 / 互動類': 'sub_label_expression',
    '全身 / 其他動作': 'sub_label_full_body',
    '兒童歌曲 / 卡通舞蹈': 'sub_label_kids_song',
    '流行 / 節奏舞蹈': 'sub_label_pop_dance',
    '品牌 / 客製舞蹈': 'sub_label_brand_dance',
    '中國寓言': 'sub_label_chinese_fable',
    '西方寓言 / 故事': 'sub_label_western_fable',
    '站立式 / 平衡式': 'sub_label_standing_yoga',
    '伸展式': 'sub_label_stretch_yoga',
    '騎馬式': 'sub_label_horse_yoga',
    '踢腿 / 動態式': 'sub_label_kick_yoga',
    'Neuron 企身動作': 'sub_label_neuron_stand',
  };
  // 顯示用: 將內部 sub 值轉做當前語言的顯示字串; 找不到對應 key 就原樣退回
  // (保險, 理論上 15 個都齊)。
  function subLabel(sub) {
    var key = SUB_LABEL_KEY[sub];
    return key ? t(key) : sub;
  }
  window.ALPHA_SUB_LABEL = subLabel;

  window.ALPHA_ACTIONS = [
    { id: '1464835936031', nameCn: '向後走', nameEn: 'Go backward', main: 'basic', sub: '移動類' },
    { id: '1464835936033', nameCn: '向右走', nameEn: 'Turn right and walk', main: 'basic', sub: '移動類' },
    { id: '1464835936035', nameCn: '向左走', nameEn: 'Turn left and walk', main: 'basic', sub: '移動類' },
    { id: '1464835936040', nameCn: '右移', nameEn: 'Move rightward', main: 'basic', sub: '移動類' },
    { id: '1464835936041', nameCn: '右轉', nameEn: 'Turn right', main: 'basic', sub: '移動類' },
    { id: '1464835936046', nameCn: '左移', nameEn: 'Move leftward', main: 'basic', sub: '移動類' },
    { id: '1464835936047', nameCn: '左轉', nameEn: 'Turn left', main: 'basic', sub: '移動類' },
    { id: '1508999477476', nameCn: '向左轉', nameEn: 'turn left', main: 'basic', sub: '移動類' },
    { id: '1508999547240', nameCn: '向左移動', nameEn: 'move left', main: 'basic', sub: '移動類' },
    { id: '1508999731737', nameCn: '右轉', nameEn: 'turn right', main: 'basic', sub: '移動類' },
    { id: '1508999801028', nameCn: '右移', nameEn: 'move right', main: 'basic', sub: '移動類' },
    { id: '1508999860568', nameCn: '前進', nameEn: 'forward', main: 'basic', sub: '移動類' },
    { id: '1510818256622', nameCn: '後退', nameEn: 'move backward', main: 'basic', sub: '移動類' },
    { id: '1464835936017', nameCn: '揮右手', nameEn: 'Wave the right hand', main: 'basic', sub: '手勢類' },
    { id: '1464835936018', nameCn: '揮左手', nameEn: 'Wave the left hand', main: 'basic', sub: '手勢類' },
    { id: '1464835936027', nameCn: '抬右手', nameEn: 'Lift the right hand', main: 'basic', sub: '手勢類' },
    { id: '1464835936028', nameCn: '抬左手', nameEn: 'Lift the left hand', main: 'basic', sub: '手勢類' },
    { id: '1464835936091', nameCn: '右抬腿', nameEn: 'Right leg lift', main: 'basic', sub: '手勢類' },
    { id: '1464835936093', nameCn: '左抬腿', nameEn: 'Left leg lift', main: 'basic', sub: '手勢類' },
    { id: '1464835936105', nameCn: '雙手提', nameEn: 'Lift both hands', main: 'basic', sub: '手勢類' },
    { id: '1508999590337', nameCn: '左踢腿', nameEn: 'left kick', main: 'basic', sub: '手勢類' },
    { id: '1508999831918', nameCn: '右踢', nameEn: 'right kick', main: 'basic', sub: '手勢類' },
    { id: '1508999975370', nameCn: '舉雙手', nameEn: 'hands up', main: 'basic', sub: '手勢類' },
    { id: '1509000200961', nameCn: '打功夫', nameEn: 'kung fu', main: 'basic', sub: '手勢類' },
    { id: '1509000232878', nameCn: '鼓掌', nameEn: 'handclap', main: 'basic', sub: '手勢類' },
    { id: '1509000433134', nameCn: '握手', nameEn: 'handshake', main: 'basic', sub: '手勢類' },
    { id: '1509007823124', nameCn: '右鍵拳', nameEn: 'right fists', main: 'basic', sub: '手勢類' },
    { id: '1510818054178', nameCn: '左擊拳', nameEn: 'left fists', main: 'basic', sub: '手勢類' },
    { id: '1510818336833', nameCn: '舉右手', nameEn: 'take right hand', main: 'basic', sub: '手勢類' },
    { id: '1510818386847', nameCn: '舉左手', nameEn: 'take left hand', main: 'basic', sub: '手勢類' },
    { id: '1464835936012', nameCn: '低頭', nameEn: 'Lower head', main: 'basic', sub: '頭部類' },
    { id: '1464835936013', nameCn: '否定', nameEn: 'Shake head', main: 'basic', sub: '頭部類' },
    { id: '1464835936029', nameCn: '頭轉正', nameEn: 'Face forward', main: 'basic', sub: '頭部類' },
    { id: '1464835936032', nameCn: '向右轉頭', nameEn: 'Turn head rightward', main: 'basic', sub: '頭部類' },
    { id: '1464835936034', nameCn: '向左轉頭', nameEn: 'Turn head leftward', main: 'basic', sub: '頭部類' },
    { id: '1464835936043', nameCn: '眨眼', nameEn: 'Blink', main: 'basic', sub: '頭部類' },
    { id: '1464835936087', nameCn: '點頭', nameEn: 'Nod', main: 'basic', sub: '頭部類' },
    { id: '1464835936088', nameCn: '抬頭', nameEn: 'Raise head', main: 'basic', sub: '頭部類' },
    { id: '1464835936090', nameCn: '右抬頭', nameEn: 'Raise head rightward', main: 'basic', sub: '頭部類' },
    { id: '1464835936092', nameCn: '左抬頭', nameEn: 'Raise head leftward', main: 'basic', sub: '頭部類' },
    { id: '1509000411078', nameCn: '搖頭', nameEn: 'reject', main: 'basic', sub: '頭部類' },
    { id: '1464835936026', nameCn: '思考', nameEn: 'Thinking', main: 'basic', sub: '表情 / 互動類' },
    { id: '1464835936042', nameCn: '贊同', nameEn: 'Agree', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000256376', nameCn: '歡迎', nameEn: 'welcome', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000284732', nameCn: '開心', nameEn: 'happy', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000313549', nameCn: '賣萌', nameEn: 'acting cute', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000338636', nameCn: '傷心', nameEn: 'grieved', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000360914', nameCn: '無聊', nameEn: 'bored', main: 'basic', sub: '表情 / 互動類' },
    { id: '1509000383562', nameCn: '大笑', nameEn: 'risus', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487230160190', nameCn: '大笑', nameEn: 'risus1', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487233706977', nameCn: '感覺身體被掏空', nameEn: 'body being hollowed out', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487234282378', nameCn: '恭喜發財', nameEn: 'happy new year', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487234521742', nameCn: '寶寶心裡苦', nameEn: 'baby heart broken', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487234946100', nameCn: '哼', nameEn: 'shit', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487235353183', nameCn: '你這麼厲害', nameEn: 'you are so powerful', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487236451234', nameCn: '金星完美', nameEn: 'perfect', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045421', nameCn: '飛升上神', nameEn: 'ascends to godhood', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045422', nameCn: '仙子友善', nameEn: 'immortal friends', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045423', nameCn: '第一美機器人', nameEn: 'most beautiful', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045424', nameCn: '我要主人', nameEn: 'i want you only', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045425', nameCn: '理我', nameEn: 'dont ignore me', main: 'basic', sub: '表情 / 互動類' },
    { id: '1487404045426', nameCn: '好委屈', nameEn: 'so wronged', main: 'basic', sub: '表情 / 互動類' },
    { id: '1464835936001', nameCn: 'ACT0', nameEn: 'ACT0', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936002', nameCn: 'ACT1', nameEn: 'ACT1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936003', nameCn: 'ACT2', nameEn: 'ACT2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936004', nameCn: 'ACT3', nameEn: 'ACT3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936005', nameCn: 'ACT4', nameEn: 'ACT4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936006', nameCn: 'ACT5', nameEn: 'ACT5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936007', nameCn: 'ACT6', nameEn: 'ACT6', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936008', nameCn: 'ACT7', nameEn: 'ACT7', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936009', nameCn: 'ACT8', nameEn: 'ACT8', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936010', nameCn: 'ACT9', nameEn: 'ACT9', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936089', nameCn: '彎腰', nameEn: 'Bow', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1464835936160', nameCn: '蹲下', nameEn: 'Squat', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686001', nameCn: 'ACT2-1', nameEn: 'ACT2-1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686002', nameCn: 'ACT2-2', nameEn: 'ACT2-2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686003', nameCn: 'ACT2-3', nameEn: 'ACT2-3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686004', nameCn: 'ACT2-4', nameEn: 'ACT2-4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686005', nameCn: 'ACT2-5', nameEn: 'ACT2-5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686006', nameCn: 'ACT4-1', nameEn: 'ACT4-1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686007', nameCn: 'ACT4-2', nameEn: 'ACT4-2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686008', nameCn: 'ACT4-3', nameEn: 'ACT4-3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686009', nameCn: 'ACT4-4', nameEn: 'ACT4-4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686010', nameCn: 'ACT4-5', nameEn: 'ACT4-5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686011', nameCn: 'ACT8-1', nameEn: 'ACT8-1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686012', nameCn: 'ACT8-2', nameEn: 'ACT8-2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686013', nameCn: 'ACT8-3', nameEn: 'ACT8-3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686014', nameCn: 'ACT13-1', nameEn: 'ACT13-1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686015', nameCn: 'ACT13-2', nameEn: 'ACT13-2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1482310201686016', nameCn: 'ACT13-3', nameEn: 'ACT13-3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1484033233040', nameCn: '拍照', nameEn: 'take photo', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1509005411859', nameCn: '鞠躬', nameEn: 'bow', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1509005528569', nameCn: '再見', nameEn: 'bye', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1510818174706', nameCn: '蹲下站起', nameEn: 'squat down up', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1514366181328', nameCn: '蹲下', nameEn: 'squat', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479848166', nameCn: '隨機長10', nameEn: 'random long10', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479876101', nameCn: '隨機長9', nameEn: 'random long9', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479896839', nameCn: '隨機長8', nameEn: 'random long8', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479915951', nameCn: '隨機長7', nameEn: 'random long7', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479950220', nameCn: '隨機長6', nameEn: 'random long6', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479965587', nameCn: '隨機長5', nameEn: 'random long5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479988488', nameCn: '隨機長4', nameEn: 'random long4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522479999388', nameCn: '隨機長3', nameEn: 'random long3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480037857', nameCn: '隨機長2', nameEn: 'random long2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480050956', nameCn: '隨機長1', nameEn: 'random long1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480070959', nameCn: '隨機短10', nameEn: 'random short10', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480088479', nameCn: '隨機短9', nameEn: 'random short9', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480099029', nameCn: '隨機短8', nameEn: 'random short8', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480108198', nameCn: '隨機短7', nameEn: 'random short7', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480117007', nameCn: '隨機短6', nameEn: 'random short6', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480127360', nameCn: '隨機短5', nameEn: 'random short5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480150315', nameCn: '隨機短3', nameEn: 'random short3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480159227', nameCn: '隨機短2', nameEn: 'random short2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522480168163', nameCn: '隨機短1', nameEn: 'random short1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1522483068437', nameCn: '隨機短4', nameEn: 'random short4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329396239', nameCn: '隨機短1', nameEn: 'random short1', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329412633', nameCn: '隨機短2', nameEn: 'random short2', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329426487', nameCn: '隨機短3', nameEn: 'random short3', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329473712', nameCn: '隨機短4', nameEn: 'random short4', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329497317', nameCn: '隨機短5', nameEn: 'random short5', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329507587', nameCn: '隨機短6', nameEn: 'random short6', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329519562', nameCn: '隨機短7', nameEn: 'random short7', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329527544', nameCn: '隨機短8', nameEn: 'random short8', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329536919', nameCn: '隨機短9', nameEn: 'random short9', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1523329552063', nameCn: '隨機短10', nameEn: 'random short10', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1524572711082', nameCn: '聯通介紹語1', nameEn: 'unicom tts01', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1524572829281', nameCn: '聯通介紹語3', nameEn: 'unicom tts03', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1524573159187', nameCn: '聯通介紹語2', nameEn: 'unicom tts02', main: 'basic', sub: '全身 / 其他動作' },
    { id: '1466392694011', nameCn: '鈴兒響叮噹', nameEn: 'jingle bells', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1466392694013', nameCn: '倫敦大橋垮下來', nameEn: 'london bridge is falling down', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1466392694018', nameCn: '一閃閃亮晶晶', nameEn: 'twinkle twinkle little star', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000679142', nameCn: '叮叮鐺', nameEn: 'jingle bells', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000749494', nameCn: '童趣', nameEn: 'childish fun', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000823380', nameCn: '過來玩Sony玩吧', nameEn: 'come and play', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000873942', nameCn: '發現', nameEn: 'discovery', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000925598', nameCn: '做得更好', nameEn: 'do it better', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509000967649', nameCn: '電子地帶', nameEn: 'electric zone', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001004210', nameCn: '拍星星', nameEn: 'flexin', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001038579', nameCn: '盡情娛樂', nameEn: 'full playtime', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001083348', nameCn: '路邊鞦韆', nameEn: 'funny swing on the road', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001132466', nameCn: '快樂的大腳', nameEn: 'happy feet', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001176320', nameCn: '獨立搖滾手', nameEn: 'indie rockers', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001327218', nameCn: '我們去約會', nameEn: 'lets go', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001368824', nameCn: '倫敦大橋垮下來', nameEn: 'london bridge is falling down', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001404283', nameCn: '一擊滑落', nameEn: 'punch and slide', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001488632', nameCn: '西班牙饒舌', nameEn: 'spanish rumble', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001526297', nameCn: '阿拉伯半島的蘇丹', nameEn: 'sultan of arabia', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001595235', nameCn: '當哈姆萊特遇到布魯克林', nameEn: 'when harlem met brooklyn', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001630772', nameCn: '勝利之歌', nameEn: 'yankee doodle dandy', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1509001441250', nameCn: '微笑搖擺', nameEn: 'smile swing', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1487236251311', nameCn: '左手右手慢動作', nameEn: 'left right hand', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1487236640811', nameCn: '一起搖擺片段', nameEn: 'swing together', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496217022040', nameCn: '大頭兒子和小頭爸爸', nameEn: 'big head son and small head dad', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496218328201', nameCn: '猴哥', nameEn: 'monkey brother', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496218474326', nameCn: '葫蘆娃娃', nameEn: 'calabash baby', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496218581505', nameCn: '喜羊羊與灰太狼', nameEn: 'pleasant goat and big wolf', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496222666158', nameCn: '黑貓警長', nameEn: 'black cat sheriff', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1496223873017', nameCn: '聰明的一休', nameEn: 'smart ikkyu', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1497596360378', nameCn: '好爸爸壞爸爸', nameEn: 'good dad and bad dad', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1497601531838', nameCn: '爸爸去哪裡(動作版）', nameEn: 'where is dad', main: 'dance', sub: '兒童歌曲 / 卡通舞蹈' },
    { id: '1464835936048', nameCn: '舞蹈', nameEn: 'Dance', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936108', nameCn: '秘密生活', nameEn: 'A secret life', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936109', nameCn: '起床', nameEn: 'get up', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936110', nameCn: '天狼星', nameEn: 'Sirius', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936111', nameCn: '酸甜苦辣', nameEn: 'sweet and sour', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936112', nameCn: '我們要起飛了', nameEn: 'we are taking off', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1464835936113', nameCn: '給他們', nameEn: 'Give Them', main: 'dance', sub: '流行 / 節奏舞蹈' },
    { id: '1510818517939', nameCn: '奧運之歌', nameEn: 'the flame', main: 'dance', sub: '品牌 / 客製舞蹈' },
    { id: '1524573025741', nameCn: '聯通饒舌', nameEn: 'unicom rap01', main: 'dance', sub: '品牌 / 客製舞蹈' },
    { id: '1509006885787', nameCn: '狐狸與葡萄', nameEn: 'huliyuputao', main: 'story', sub: '中國寓言' },
    { id: '1509006924534', nameCn: '刻舟求劍', nameEn: 'kezhouqiujian', main: 'story', sub: '中國寓言' },
    { id: '1509006974154', nameCn: '木馬屠城', nameEn: 'mumatucheng', main: 'story', sub: '中國寓言' },
    { id: '1509007070001', nameCn: '農夫與蛇', nameEn: 'nongfuyushe', main: 'story', sub: '中國寓言' },
    { id: '1509007097697', nameCn: '勤奮的蝸牛', nameEn: 'qinfendewoniu', main: 'story', sub: '中國寓言' },
    { id: '1509007137500', nameCn: '運鹽的驢子', nameEn: 'yunyandelv', main: 'story', sub: '中國寓言' },
    { id: '1464835936120', nameCn: '鹿與獅子', nameEn: 'The deer and the lion', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936121', nameCn: '牛棚裡的鹿', nameEn: 'The deer in the cowshed', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936122', nameCn: '驢子和狼', nameEn: 'The donkey and the wolf', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936123', nameCn: '農家女孩', nameEn: 'The farmer girl', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936124', nameCn: '漁夫和魚', nameEn: 'The fisherman and the fish', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936125', nameCn: '沒有尾巴的狐狸', nameEn: 'The fox without a tail', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936126', nameCn: '老鼠和貓', nameEn: 'The mice and the cat', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936127', nameCn: '狼與羔羊', nameEn: 'The wolf and the lamb', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936128', nameCn: '老鼠、青蛙和老鷹', nameEn: 'The mouse, the frog, and the eagle', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1464835936129', nameCn: '特洛伊木馬', nameEn: 'Trojan horse', main: 'story', sub: '西方寓言 / 故事' },
    { id: '1509004671917', nameCn: '金雞獨立', nameEn: 'one foot standing', main: 'yoga', sub: '站立式 / 平衡式' },
    { id: '1509004843153', nameCn: '敬禮式', nameEn: 'salute', main: 'yoga', sub: '站立式 / 平衡式' },
    { id: '1509004868066', nameCn: '蹲式', nameEn: 'squat pose', main: 'yoga', sub: '站立式 / 平衡式' },
    { id: '1509004509884', nameCn: '展臂式', nameEn: 'arm stretch', main: 'yoga', sub: '伸展式' },
    { id: '1509004595409', nameCn: '左腿伸展式', nameEn: 'left leg stretch', main: 'yoga', sub: '伸展式' },
    { id: '1509004646036', nameCn: '左腰伸展式', nameEn: 'left waist stretch', main: 'yoga', sub: '伸展式' },
    { id: '1509004756578', nameCn: '右腿伸展式', nameEn: 'right leg stretch', main: 'yoga', sub: '伸展式' },
    { id: '1509004810606', nameCn: '右腰伸展式', nameEn: 'right waist stretch', main: 'yoga', sub: '伸展式' },
    { id: '1509004889922', nameCn: '前伸展式', nameEn: 'stretch forward', main: 'yoga', sub: '伸展式' },
    { id: '1509004572101', nameCn: '左腿騎馬式', nameEn: 'left leg forward horseriding', main: 'yoga', sub: '騎馬式' },
    { id: '1509004727034', nameCn: '右腿騎馬式', nameEn: 'right leg forward horseriding', main: 'yoga', sub: '騎馬式' },
    { id: '1509004621437', nameCn: '左側腰踢腿式', nameEn: 'left side bow with right leg kick', main: 'yoga', sub: '踢腿 / 動態式' },
    { id: '1509004782446', nameCn: '右側腰踢腿式', nameEn: 'right side bow with left leg kick', main: 'yoga', sub: '踢腿 / 動態式' },
    { id: '1511247440171', nameCn: '右踢瑜珈', nameEn: 'right kick yoga', main: 'yoga', sub: '踢腿 / 動態式' },
    { id: '1511247506520', nameCn: '左踢瑜伽', nameEn: 'left kick yoga', main: 'yoga', sub: '踢腿 / 動態式' },
    // "其他" 分類 —— 來自 Neuron 專案 (alpha2.uk.neuron/MainActivity#
    // onSensorChanged()) 用的摔倒起身動作名, 未有在 actionInfo.txt 實機清單
    // 出現過, 就是未經這個 project 自己的機身實測確認, 但它是 Neuron 官方
    // SDK example 用過的內建動作名, 理應是機身通用的內建動作。如果播不到,
    // 用「動作」分頁的即時動作清單 (alpha_action_play_dropdown) 核對一下
    // 你部機到底有沒有這兩個 id。
    { id: 'BackStand', nameCn: '仰躺站立', nameEn: 'BackStand', main: 'others', sub: 'Neuron 企身動作' },
    { id: 'FrontStand', nameCn: '趴著站立', nameEn: 'FrontStand', main: 'others', sub: 'Neuron 企身動作' },
  ];


  // actionLabel() 決定 dropdown 度顯示哪個名: 中文模式顯示 "中文 / English"
  // (方便用家兩種名都對得上), 英文模式僅顯示英文, 不再夾雜中文——這個同
  // 之前 (2026-08 之前) 不理語言、成日都顯示 "中文 / English" 的寫法不同。
  function actionLabel(a) {
    var lang = (window.getUiLanguage && window.getUiLanguage()) || 'zh';
    if (lang === 'zh') {
      return (a.nameCn && a.nameCn !== a.nameEn) ? (a.nameCn + ' / ' + a.nameEn) : a.nameEn;
    }
    return a.nameEn;
  }

  // 下面這 3 個 dropdown option table (BY_SUBCATEGORY/BY_CATEGORY/ALL) 全部
  // 用到 actionLabel()/subLabel()/分類 label, 三個都是 t() call time 計值,
  // 所以要包做一個 rebuild function, 等 blockly-i18n.js 切語言當時可以重新
  // call 一次, 取回新語言的版本 (同 buildAlphaToolbox()/
  // rebuildAlphaActionCategories() 一樣的做法)。
  function rebuildActionOptionTables() {
    // 分組: 每個 main -> 每個 sub 一組 [[label, value], ...], 給「動作」dropdown 用
    // (跟著已選的 main + sub 一齊篩選)。key 用回 sub 的內部值 (中文, wire value),
    // 不跟語言變 —— 僅 [label, ...] 裡面第一格的顯示字串會跟語言變。
    window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY = {};
    ACTION_CATEGORIES.forEach(function (c) {
      window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY[c.key] = {};
      (ACTION_SUBCATEGORIES[c.key] || []).forEach(function (sub) {
        window.ALPHA_ACTION_OPTIONS_BY_SUBCATEGORY[c.key][sub] = window.ALPHA_ACTIONS
          .filter(function (a) { return a.main === c.key && a.sub === sub; })
          .map(function (a) { return [actionLabel(a), a.id]; });
      });
    });

    // 分組: 每個 main 一組 (不理 sub), 保留給舊版/其他可能還正在用這個 key 的地方相容。
    window.ALPHA_ACTION_OPTIONS_BY_CATEGORY = {};
    ACTION_CATEGORIES.forEach(function (c) {
      window.ALPHA_ACTION_OPTIONS_BY_CATEGORY[c.key] = window.ALPHA_ACTIONS
        .filter(function (a) { return a.main === c.key; })
        .map(function (a) { return [actionLabel(a), a.id]; });
    });

    // 全部合併做一個 dropdown 用的選項 (加回分類前綴方便找)。分類前綴同 sub
    // 前綴都經 t()/subLabel() 取回顯示字串, 跟語言變。
    window.ALPHA_ACTION_OPTIONS_ALL = window.ALPHA_ACTIONS.map(function (a) {
      var cat = ACTION_CATEGORIES.filter(function (c) { return c.key === a.main; })[0];
      var prefix = cat ? ('[' + cat.label + ' · ' + subLabel(a.sub) + '] ') : '';
      return [prefix + actionLabel(a), a.id];
    });
  }
  rebuildActionOptionTables();
  window.rebuildAlphaActionOptionTables = rebuildActionOptionTables;

  // id -> main category 對照表, 給 blockly-run.js 的「即時動作清單」(refreshActionDropdown,
  // 抓 /api/action/list 機械人即時回傳的清單) 用來幫每個項目掛回分類前綴。
  //
  // 舊版這個 function 僅得個名, 實際是靠機械人回傳的原始 type 數字 (1/2/3/4) 做
  // 白名單推斷分類, 完全不理個 id 本身。新版資料來自 actionInfo.txt 實機清單 (見
  // 檔頭 2026-08 更新註解), 對照表本身就是「id -> {main, sub}」, 更準確亦更簡單
  // 直接查表, 不用再靠 type 數字猜測。機械人即時回傳的 action 裡面, 凡是 id 剛好
  // 在這個已知清單 (200 個實機 id + 底下額外加的 2 個 Neuron 動作) 裡面的, 就
  // 會拿到正確分類前綴; 不在清單裡面的 (例如用家自己上載的自訂動作, 或者部分
  // 機型先有的擴充動作) 就沒有前綴, 屬正常現象, 不是 bug。
  var ACTION_MAIN_BY_ID = {};
  window.ALPHA_ACTIONS.forEach(function (a) { ACTION_MAIN_BY_ID[a.id] = a.main; });
  window.ALPHA_ACTION_CATEGORY_OF = function (idOrType) {
    return ACTION_MAIN_BY_ID[idOrType] || '';
  };
})();

