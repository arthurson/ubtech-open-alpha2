// Open Alpha2 — client logic (app-actions.js)
// 內容: Alpha2 動作清單/分類/播放, 以及 Advanced tab 的 raw AIDL passthrough (未完全驗證的底層 method)。
// 全部檔案共用 window/global scope (沒有用 ES module), 載入順序由 index.html 的
// <script src="..."> 順序決定 - 詳見 index.html 頭那段 comment。

// ---------------- Actions ----------------

// Categories as returned by the robot's own action list (Alpha2RobotApi row[1] = type).
// The robot's static action-info file uses numeric types (1/2/3/4); some runtime builds
// report the same categories as text instead. Either way each of basic/dance/story/yoga
// gets its own sub-tab with a distinct theme colour; anything not in this whitelist
// (regardless of what the raw type string actually says) falls back to a shared
// "others" sub-tab - the whitelist approach means no unknown category value ever needs
// to be spelled out literally here.
const ACTION_CATEGORIES = [
  { key: "basic",  label: "基本", labelEn: "Basic", color: "#3b7dff" },
  { key: "dance",  label: "跳舞", labelEn: "Dance", color: "#db2777" },
  { key: "story",  label: "故事", labelEn: "Story", color: "#d97706" },
  { key: "yoga",   label: "瑜伽", labelEn: "Yoga",  color: "#16a34a" },
  { key: "others", label: "其他", labelEn: "Other", color: "#6b7280" },
];
// 白名單: 數字 type (actionInfo.txt 靜態格式) 同文字 type (部分機身 runtime 格式) 都對應埋。
const ACTION_CATEGORY_MAP = {
  "1": "basic", "2": "dance", "3": "story", "4": "yoga",
  basic: "basic", dance: "dance", story: "story", yoga: "yoga",
};
let allActions = [];
let activeActionCategory = "basic";

// 動作 ID -> {main, sub} 的子分類對照表, 由 action_classification.json 讀入 (見
// action_classified.txt 的整理來源)。這個 mapping 僅在 asset 檔案有出現的動作才會有
// sub 分類 - 沒有出現的動作仍然遵循 categoryOf() 那個大分類, 但在那個大分類裡面沒有
// 子分類 tab 可以選 (就是直接混在主列表, 冚方向上等於歸入那個大分類的"其他")。
// 這個表故意不在 code 裡寫死: 下次要再分類就僅改/換份 json, 不用動 app-actions.js。
let actionClassification = {}; // id -> {main, sub}
let activeActionSubCategory = null; // null = 顯示那個大分類裡面全部動作 (未選子分類)

function loadActionClassification() {
  return fetch("action_classification.json").then(function (r) {
    if (!r.ok) throw new Error("http " + r.status);
    return r.json();
  }).then(function (json) {
    actionClassification = json || {};
  }).catch(function (e) {
    // 沒有這個檔案或者讀取失敗都不應該累到整個動作 tab 用不到 - 僅沒有子分類 tab,
    // 主分類(基本/跳舞/故事/瑜伽/其他)照舊運作。
    console.warn("action_classification.json 讀取失敗, 子分類 tab 將不會出現:", e);
    actionClassification = {};
  });
}

function categoryOf(rawType) {
  return ACTION_CATEGORY_MAP[rawType] || "others";
}

/** 取回一個動作的子分類名 (例如「移動類」), 沒有對照到就 null。 */
function subCategoryOf(action) {
  const entry = actionClassification[action.id];
  return entry ? entry.sub : null;
}

/** 取回一個動作應該顯示的名: 跟主 UI 語言 (uiLang), 但如果那個語言沒有資料 (例如英文名同
 *  中文名一樣, 或者其中一邊是空), 就 fallback 去另一個, 不會顯示空白 chip。 */
function displayNameOf(action) {
  if (uiLang === "en") {
    return action.nameEn || action.nameCn;
  }
  return action.nameCn || action.nameEn;
}

function loadActionList() {
  const listEl = document.getElementById("actionList");
  listEl.textContent = "載入中…";
  return Promise.all([Alpha2Api.actionList(), loadActionClassification()]).then(function (results) {
    const data = results[0];
    if (!data.ok) {
      listEl.textContent = "錯誤: " + (data.error || data.code);
      return;
    }
    allActions = data.actions || [];
    buildActionSubTabs();
    renderActionList();
  });
}

/** Builds the 5 category sub-tabs (basic/dance/story/yoga/others), each themed with
 *  its own accent colour via a CSS custom property set inline on the button. */
function buildActionSubTabs() {
  const bar = document.getElementById("actionSubTabBar");
  bar.innerHTML = "";
  ACTION_CATEGORIES.forEach(function (c) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (c.key === activeActionCategory ? " active" : "");
    btn.style.setProperty("--sub-tab-color", c.color);
    const count = allActions.filter(function (a) { return categoryOf(a.type) === c.key; }).length;
    btn.textContent = (uiLang === "en" ? c.labelEn : c.label) + " (" + count + ")";
    btn.onclick = function () {
      activeActionCategory = c.key;
      activeActionSubCategory = null; // 轉了大分類, 子分類篩選重置為「全部」
      buildActionSubTabs();
      buildActionSubSubTabs();
      renderActionList();
    };
    bar.appendChild(btn);
  });
  buildActionSubSubTabs();
}

// Fixed palette for sub-category tabs (移動類/手勢類/...), cycled by index so each
// sub-category gets a distinct, stable colour regardless of which main category it's
// under - deliberately a separate palette from ACTION_CATEGORIES' colours so the two
// tab levels stay visually distinguishable from each other.
const SUB_CATEGORY_COLORS = [
  "#0891b2", "#ca8a04", "#9333ea", "#059669", "#e11d48",
  "#2563eb", "#c2410c", "#4d7c0f", "#be185d", "#0d9488",
];

// English labels for the sub-categories that come from action_classification.json
// (see loadActionClassification() above). The Chinese string is still the filter key
// used everywhere else (subCategoryOf() / activeActionSubCategory) - this table is
// purely for what's shown on the tab when uiLang === "en", so it never needs to touch
// action_classification.json or action_classified.txt (source of truth for the actual
// classification). Add an entry here whenever a new sub value shows up in that file.
const SUB_CATEGORY_LABELS_EN = {
  "移動類":            "Locomotion",
  "手勢類":            "Gestures",
  "頭部類":            "Head",
  "表情 / 互動類":      "Expression / Interaction",
  "全身 / 其他動作":    "Full Body / Other",
  "伸展式":            "Stretch",
  "站立式 / 平衡式":    "Standing / Balance",
  "騎馬式":            "Horse Stance",
  "踢腿 / 動態式":      "Kick / Dynamic",
  "流行 / 節奏舞蹈":    "Pop / Rhythm Dance",
  "兒童歌曲 / 卡通舞蹈": "Kids Songs / Cartoon Dance",
  "品牌 / 客製舞蹈":    "Brand / Custom Dance",
  "中國寓言":          "Chinese Fables",
  "西方寓言 / 故事":    "Western Fables / Stories",
};

/** 取回一個子分類應該顯示的名: 跟主 UI 語言 (uiLang)。內部 filter 仍然用回 `sub`
 *  (中文) 這個 key, 這個 function 僅用在顯示層。沒有對照到就照原文顯示, 不會有
 *  空白 tab。 */
function subCategoryDisplayName(sub) {
  if (uiLang === "en") {
    return SUB_CATEGORY_LABELS_EN[sub] || sub;
  }
  return sub;
}

/** 建立第二層子分類 tab (例如 基本 之下的 移動類/手勢類/頭部類/...) 的邏輯, 抽出來
 *  做獨立 function 方便日後有第二個動作清單要建當時不用複製貼上一份幾乎一樣的
 *  code。僅「哪個 bar element」、「哪個 action 陣列」、「哪個 main category」、
 *  「目前選了哪個子分類 (+如何寫回去)」由 caller 決定。只有
 *  action_classification.json 對這個大分類有出現的子分類才會出 tab - 沒有資料就不
 *  顯示這一層 tab bar, 直接顯示那個大分類裡面整個 flat
 *  清單。An always-present「全部」tab 清空子分類篩選。
 *  @param barElId       子分類 tab bar 容器的 id
 *  @param actions       完整動作陣列 (allActions)
 *  @param mainCategory  目前選了的大分類 key (activeActionCategory)
 *  @param getActiveSub  () => 目前選了的子分類 (null = 全部)
 *  @param setActiveSub  (sub) => void, 寫回去那個 caller 自己的 activeXxxSubCategory 變數
 *  @param onChange      選了新子分類之後要做的東西 (重畫 tab bar + 重畫動作清單)
 */
function buildActionSubSubTabsShared(barElId, actions, mainCategory, getActiveSub, setActiveSub, onChange) {
  const bar = document.getElementById(barElId);
  if (!bar) return;
  bar.innerHTML = "";

  // Sub-category labels, in the order first encountered in the classification file,
  // restricted to actions that belong to the currently active main category.
  const subsInOrder = [];
  actions.forEach(function (a) {
    if (categoryOf(a.type) !== mainCategory) return;
    const sub = subCategoryOf(a);
    if (sub && subsInOrder.indexOf(sub) === -1) subsInOrder.push(sub);
  });

  if (subsInOrder.length === 0) {
    setActiveSub(null);
    return; // 這個大分類沒有任何子分類資料 - 不顯示這一層 tab bar
  }

  const allBtn = document.createElement("button");
  allBtn.className = "sub-tab-btn" + (getActiveSub() === null ? " active" : "");
  allBtn.textContent = uiLang === "en" ? "All" : "全部";
  allBtn.onclick = function () {
    setActiveSub(null);
    onChange();
  };
  bar.appendChild(allBtn);

  subsInOrder.forEach(function (sub, i) {
    const btn = document.createElement("button");
    btn.className = "sub-tab-btn" + (sub === getActiveSub() ? " active" : "");
    btn.style.setProperty("--sub-tab-color", SUB_CATEGORY_COLORS[i % SUB_CATEGORY_COLORS.length]);
    const count = actions.filter(function (a) {
      return categoryOf(a.type) === mainCategory && subCategoryOf(a) === sub;
    }).length;
    btn.textContent = subCategoryDisplayName(sub) + " (" + count + ")";
    btn.onclick = function () {
      setActiveSub(sub);
      onChange();
    };
    bar.appendChild(btn);
  });
}

/** Builds the second-level sub-category tabs (e.g. 移動類/手勢類/頭部類/... within
 *  基本) for whichever main category is currently active. Only sub-categories that
 *  actually appear in action_classification.json for this main category show up as
 *  tabs - if the classification file doesn't cover a main category at all (or none
 *  of its actions matched), no sub-tab bar appears and the flat action list shows,
 *  same as before this feature existed. An always-present "全部" tab clears the
 *  sub-category filter back to showing everything in the main category. */
function buildActionSubSubTabs() {
  buildActionSubSubTabsShared(
    "actionSubSubTabBar",
    allActions,
    activeActionCategory,
    function () { return activeActionSubCategory; },
    function (sub) { activeActionSubCategory = sub; },
    function () { buildActionSubSubTabs(); renderActionList(); }
  );
}

/** 建立動作 chip 清單的邏輯, 抽出來做獨立 function 方便日後複用。
 *  @param listElId    action list 容器的 id
 *  @param filtered    已經篩選好的 action 陣列
 *  @param nameFn      (action) => 顯示名
 *  @param onPick      (action) => void, 按個 chip 之後要做的東西 (填 input + 播放)
 *  @param emptyText   沒有動作時顯示的文字
 */
function renderActionChips(listElId, filtered, nameFn, onPick, emptyText) {
  const listEl = document.getElementById(listElId);
  if (!listEl) return;
  if (filtered.length === 0) {
    listEl.textContent = emptyText;
    return;
  }
  listEl.innerHTML = "";
  filtered.forEach(function (a) {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.textContent = nameFn(a);
    chip.onclick = function () { onPick(a); };
    listEl.appendChild(chip);
  });
}

function renderActionList() {
  const filtered = allActions.filter(function (a) {
    if (categoryOf(a.type) !== activeActionCategory) return false;
    if (activeActionSubCategory !== null && subCategoryOf(a) !== activeActionSubCategory) return false;
    return true;
  });
  renderActionChips("actionList", filtered, displayNameOf, function (a) {
    document.getElementById("actionName").value = a.nameEn || a.nameCn;
    playAction();
  }, "(沒有動作 / 服務未初始化)");
}

function playAction() {
  const name = document.getElementById("actionName").value.trim();
  if (!name) { showError("播放動作", "請輸入動作名稱"); return; }
  return Alpha2Api.actionPlay( { name: name });
}

function stopAction() {
  return Alpha2Api.actionStop();
}

function setActionSpeed() {
  const el = document.getElementById("actionSpeed");
  const v = parseFloat(el.value);
  return Alpha2Api.ubxSpeed({ value: v });
}


