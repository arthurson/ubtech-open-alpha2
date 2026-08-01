// Open Alpha2 — 機械人內建動作清單 (靜態資料)。
//
// 資料來源: actionInfo.txt (隨機械人韌體 Alpha2Services v1.1.7.3.20 附帶嘅動作定義檔)。
// 呢個檔案獨立於 blockly-blocks.js, 方便日後單獨更新動作清單 (例如換咗機身韌體、
// 有新動作), 唔使觸碰 block 定義本身。
//
// 格式核對: 檔案每行係 id##中文名##英文名##type, 用 ## 分隔, 4 個一組, type 係數字。
// ⚠ 注意: SDK (AlphaActionServiceUtil.java) 嘅 code comment 寫住 runtime 透過
// onGetActionList() 拎到嘅字串次序係 [id, type, cn-name, en-name] —— 同呢份
// 靜態檔案嘅次序 [id, cn-name, en-name, type] 唔一致。呢度依從檔案嘅實際內容
// (已核對過嘅資料) 嚟解讀。如果實測發現播出嚟嘅動作同揀嘅唔一樣, 可以用
// 「動作」分頁嘅「即時清單」dropdown (向機械人即時查詢) 做交叉核對, 兩者用嘅
// 都係同一個 /api/action/play?name=<id> 端點, 傳嘅係 id 呢個值。
//
// 分類邏輯: 呢份靜態檔案用數字 type (1/2/3/4)。控制面板嗰邊 (即時向機械人查詢
// 嘅 /api/action/list) 用嘅係文字 type, 兩者未必一致, 所以分類表用「白名單」方式
// 寫: 淨係列出已知會對應去邊個分類嘅值, 凡係冇喺呢個表出現嘅值 (包括檔案有齊全
// 4 個數字之外嘅任何字串), 一律歸類做「其他」—— 呢個 fallback 唔係靠列舉具體
// 字串嚟排除, 而係靠白名單冇覆蓋就自動落去 others, 所以呢度唔需要 (亦都刻意冇)
// 寫死任何未知分類嘅實際字面名。

(function () {
  // 分類白名單: 數字/文字 type 值 -> 內部分類 key。
  // basic=基本動作, dance=舞蹈, story=故事, yoga=瑜伽/健體類, others=白名單冇覆蓋到嘅一律歸呢度。
  var ACTION_CATEGORY_MAP = { '1': 'basic', '2': 'dance', '3': 'story', '4': 'yoga' };

  var ACTION_CATEGORIES = [
    { key: 'basic',  label: '基本', color: '#3b7dff' },
    { key: 'dance',  label: '跳舞', color: '#db2777' },
    { key: 'story',  label: '故事', color: '#d97706' },
    { key: 'yoga',   label: '瑜伽', color: '#16a34a' },
    { key: 'others', label: '其他', color: '#6b7280' },
  ];
  window.ALPHA_ACTION_CATEGORIES = ACTION_CATEGORIES;

  // 由白名單 mapping 到分類 key, 冇覆蓋到嘅一律 'others' (唔理會原始 type 個字面值係咩)。
  function categoryOf(rawType) {
    return ACTION_CATEGORY_MAP[rawType] || 'others';
  }
  window.ALPHA_ACTION_CATEGORY_OF = categoryOf;

  window.ALPHA_ACTIONS = [
    { id: '1464835936001', nameCn: 'ACT0', nameEn: 'ACT0', type: '1' },
    { id: '1464835936002', nameCn: 'ACT1', nameEn: 'ACT1', type: '1' },
    { id: '1464835936003', nameCn: 'ACT2', nameEn: 'ACT2', type: '1' },
    { id: '1464835936004', nameCn: 'ACT3', nameEn: 'ACT3', type: '1' },
    { id: '1464835936005', nameCn: 'ACT4', nameEn: 'ACT4', type: '1' },
    { id: '1464835936006', nameCn: 'ACT5', nameEn: 'ACT5', type: '1' },
    { id: '1464835936007', nameCn: 'ACT6', nameEn: 'ACT6', type: '1' },
    { id: '1464835936008', nameCn: 'ACT7', nameEn: 'ACT7', type: '1' },
    { id: '1464835936009', nameCn: 'ACT8', nameEn: 'ACT8', type: '1' },
    { id: '1464835936010', nameCn: 'ACT9', nameEn: 'ACT9', type: '1' },
    { id: '1464835936011', nameCn: '打功夫', nameEn: 'Play kungfu', type: '1' },
    { id: '1464835936012', nameCn: '低头', nameEn: 'Lower head', type: '1' },
    { id: '1464835936013', nameCn: '否定', nameEn: 'Shake head', type: '1' },
    { id: '1464835936014', nameCn: '鼓掌', nameEn: 'Applaud', type: '1' },
    { id: '1464835936015', nameCn: '后退', nameEn: 'Move backward', type: '1' },
    { id: '1464835936016', nameCn: '欢迎', nameEn: 'Greeting', type: '1' },
    { id: '1464835936017', nameCn: '挥右手', nameEn: 'Wave the right hand', type: '1' },
    { id: '1464835936018', nameCn: '挥左手', nameEn: 'Wave the left hand', type: '1' },
    { id: '1464835936019', nameCn: '举双手', nameEn: 'Raise both hands', type: '1' },
    { id: '1464835936020', nameCn: '举右手', nameEn: 'Raise the right hand', type: '1' },
    { id: '1464835936021', nameCn: '举左手', nameEn: 'Raise the left hand', type: '1' },
    { id: '1464835936022', nameCn: '开心', nameEn: 'Happy', type: '1' },
    { id: '1464835936023', nameCn: '卖萌', nameEn: 'Act cute', type: '1' },
    { id: '1464835936024', nameCn: '前进', nameEn: 'Move forward', type: '1' },
    { id: '1464835936025', nameCn: '伤心', nameEn: 'Sad', type: '1' },
    { id: '1464835936026', nameCn: '思考', nameEn: 'Thinking', type: '1' },
    { id: '1464835936027', nameCn: '抬右手', nameEn: 'Lift the right hand', type: '1' },
    { id: '1464835936028', nameCn: '抬左手', nameEn: 'Lift the left hand', type: '1' },
    { id: '1464835936029', nameCn: '头转正', nameEn: 'Face forward', type: '1' },
    { id: '1464835936030', nameCn: '无聊', nameEn: 'Boring', type: '1' },
    { id: '1464835936031', nameCn: '向后走', nameEn: 'Go backward', type: '1' },
    { id: '1464835936032', nameCn: '向右转头', nameEn: 'Turn head rightward', type: '1' },
    { id: '1464835936033', nameCn: '向右走', nameEn: 'Turn right and walk', type: '1' },
    { id: '1464835936034', nameCn: '向左转头', nameEn: 'Turn head leftward', type: '1' },
    { id: '1464835936035', nameCn: '向左走', nameEn: 'Turn left and walk', type: '1' },
    { id: '1464835936036', nameCn: '笑', nameEn: 'Laugh', type: '1' },
    { id: '1464835936037', nameCn: '摇头', nameEn: 'Deny', type: '1' },
    { id: '1464835936038', nameCn: '右击拳', nameEn: 'Right punch', type: '1' },
    { id: '1464835936039', nameCn: '右踢腿', nameEn: 'Right leg kick', type: '1' },
    { id: '1464835936040', nameCn: '右移', nameEn: 'Move rightward', type: '1' },
    { id: '1464835936041', nameCn: '右转', nameEn: 'Turn right', type: '1' },
    { id: '1464835936042', nameCn: '赞同', nameEn: 'Agree', type: '1' },
    { id: '1464835936043', nameCn: '眨眼', nameEn: 'Blink', type: '1' },
    { id: '1464835936044', nameCn: '左击拳', nameEn: 'Left punch', type: '1' },
    { id: '1464835936045', nameCn: '左踢腿', nameEn: 'Left leg kick', type: '1' },
    { id: '1464835936046', nameCn: '左移', nameEn: 'Move leftward', type: '1' },
    { id: '1464835936047', nameCn: '左转', nameEn: 'Turn left', type: '1' },
    { id: '1464835936048', nameCn: '舞蹈', nameEn: 'Dance', type: '1' },
    { id: '1464835936087', nameCn: '点头', nameEn: 'Nod', type: '1' },
    { id: '1464835936088', nameCn: '抬头', nameEn: 'Raise head', type: '1' },
    { id: '1464835936089', nameCn: '弯腰', nameEn: 'Bow', type: '1' },
    { id: '1464835936090', nameCn: '右抬头', nameEn: 'Raise head rightward', type: '1' },
    { id: '1464835936091', nameCn: '右抬腿', nameEn: 'Right leg lift', type: '1' },
    { id: '1464835936092', nameCn: '左抬头', nameEn: 'Raise head leftward', type: '1' },
    { id: '1464835936093', nameCn: '左抬腿', nameEn: 'Left leg lift', type: '1' },
    { id: '1464835936094', nameCn: '握手', nameEn: 'Shake hands', type: '1' },
    { id: '1464835936103', nameCn: '蹲下站起', nameEn: 'Squat and stand up', type: '1' },
    { id: '1464835936105', nameCn: '抬双手', nameEn: 'Lift both hands', type: '1' },
    { id: '1464835936160', nameCn: '蹲下', nameEn: 'Squat', type: '1' },
    { id: '1464835936120', nameCn: 'The deer and the lion', nameEn: 'The deer and the lion', type: '3' },
    { id: '1464835936121', nameCn: 'The deer in the cowshed', nameEn: 'The deer in the cowshed', type: '3' },
    { id: '1464835936122', nameCn: 'The donkey and the wolf', nameEn: 'The donkey and the wolf', type: '3' },
    { id: '1464835936123', nameCn: 'The farmer girl', nameEn: 'The farmer girl', type: '3' },
    { id: '1464835936124', nameCn: 'The fisherman and the fish', nameEn: 'The fisherman and the fish', type: '3' },
    { id: '1464835936125', nameCn: 'The fox without a tail', nameEn: 'The fox without a tail', type: '3' },
    { id: '1464835936126', nameCn: 'The mice and the cat', nameEn: 'The mice and the cat', type: '3' },
    { id: '1464835936127', nameCn: 'The wolf and the lamb', nameEn: 'The wolf and the lamb', type: '3' },
    { id: '1464835936128', nameCn: 'The mouse, the frog, and the eagle', nameEn: 'The mouse, the frog, and the eagle', type: '3' },
    { id: '1464835936129', nameCn: 'Trojan horse', nameEn: 'Trojan horse', type: '3' },
    { id: '1466392694001', nameCn: '童趣', nameEn: 'childish fun', type: '2' },
    { id: '1466392694002', nameCn: '过来玩吧', nameEn: 'come and play', type: '2' },
    { id: '1466392694003', nameCn: '发现', nameEn: 'discovery', type: '2' },
    { id: '1466392694004', nameCn: '做得更好', nameEn: 'do it better', type: '2' },
    { id: '1466392694005', nameCn: '电子地带', nameEn: 'electric zone', type: '2' },
    { id: '1466392694006', nameCn: '拂来星', nameEn: 'flexin', type: '2' },
    { id: '1466392694007', nameCn: '尽情娱乐', nameEn: 'full playtime', type: '2' },
    { id: '1466392694008', nameCn: '路边秋千', nameEn: 'funny swing on the road', type: '2' },
    { id: '1466392694009', nameCn: '快乐的大脚', nameEn: 'happy feet', type: '2' },
    { id: '1466392694010', nameCn: '独立摇滚手', nameEn: 'indie rockers', type: '2' },
    { id: '1466392694011', nameCn: '铃儿响叮当', nameEn: 'jingle bells', type: '2' },
    { id: '1466392694012', nameCn: '我们去约会', nameEn: 'lets go', type: '2' },
    { id: '1466392694013', nameCn: '伦敦大桥垮下来', nameEn: 'london bridge is falling down', type: '2' },
    { id: '1466392694014', nameCn: '一击滑落', nameEn: 'punch and slide', type: '2' },
    { id: '1466392694015', nameCn: '微笑摇摆', nameEn: 'smile swing', type: '2' },
    { id: '1466392694016', nameCn: '西班牙说唱', nameEn: 'spanish rumble', type: '2' },
    { id: '1466392694017', nameCn: '阿拉伯半岛的苏丹', nameEn: 'sultan of arabia', type: '2' },
    { id: '1466392694019', nameCn: '当哈姆莱特遇到布鲁克林', nameEn: 'when harlem met brooklyn', type: '2' },
    { id: '1466392694020', nameCn: '胜利之歌', nameEn: 'yankee doodle dandy', type: '2' },
    { id: '1466392694021', nameCn: '奥运之歌', nameEn: 'The Flame', type: '4' },
  ];

  // 分組: 每個分類一組 [[label, value], ...], 俾分類 dropdown block 用。
  window.ALPHA_ACTION_OPTIONS_BY_CATEGORY = {};
  ACTION_CATEGORIES.forEach(function (c) {
    window.ALPHA_ACTION_OPTIONS_BY_CATEGORY[c.key] = window.ALPHA_ACTIONS
      .filter(function (a) { return categoryOf(a.type) === c.key; })
      .map(function (a) {
        var label = a.nameCn && a.nameCn !== a.nameEn ? (a.nameCn + ' / ' + a.nameEn) : a.nameEn;
        return [label, a.id];
      });
  });

  // 全部合併做一個 dropdown 用嘅選項 (加返分類前綴方便搵)。
  window.ALPHA_ACTION_OPTIONS_ALL = window.ALPHA_ACTIONS.map(function (a) {
    var label = a.nameCn && a.nameCn !== a.nameEn ? (a.nameCn + ' / ' + a.nameEn) : a.nameEn;
    var cat = ACTION_CATEGORIES.filter(function (c) { return c.key === categoryOf(a.type); })[0];
    var prefix = cat ? ('[' + cat.label + '] ') : '';
    return [prefix + label, a.id];
  });
})();
