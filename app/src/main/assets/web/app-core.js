// Open Alpha2 — client logic (app-core.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 全局狀態、UI 語言字典、servo 校準表、api()/hwApi() 呢啲所有其他 app-*.js 都要用嘅核心 helper。呢個檔案要第一個 load。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// Open Alpha2 — client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// ---------------- App / project metadata ----------------
// APP_VERSION/APP_REPO_URL/XIAOZHI_CONSOLE_URL 呢三個常數而家冇再喺 HTML 網頁
// (index.html) 度用 - 小智／原始碼連結、版本號改咗擺喺機身/手機原生畫面
// (MainActivity.java 個 onCreate() 起嗰個 TextView UI, 顯示緊 http://<ip>:8888/
// 嗰版), 唔喺呢個 web panel 度顯示。留低呢三個常數純粹係俾第日想加返
// HTML 版顯示時有一個現成嘅 single source of truth, 唔使周圍搵散落嘅字串。
const APP_VERSION = "beta2";
const APP_REPO_URL = "https://github.com/arthurson/ubtech-open-alpha2";
const XIAOZHI_CONSOLE_URL = "https://xiaozhi.me/";

// ---------------- UI language (whole-panel zh/en translation) ----------------
//
// Single source of truth for language across the whole panel - this drives both the
// surrounding UI chrome (headings, button labels, static hints) via [data-i18n]-tagged
// elements, AND which language action names display as (chips in the Actions tab -
// see displayNameOf() below). There used to be a separate per-tab action-name-language
// toggle (activeActionLang); it was removed so there's only ever one language switch
// in the whole app - see README.
let uiLang = localStorage.getItem("ui_lang") || "zh";

// key -> {zh, en}. Applied to any element carrying data-i18n="key" via textContent,
// except elements also carrying data-i18n-attr (see applyUiLanguage) which are
// translated via an attribute (title/placeholder) instead.
const I18N = {
  // -- nav bar --
  nav_status:             { zh: "📊 狀態",          en: "📊 Status" },
  nav_actions:            { zh: "🕺 動作",          en: "🕺 Actions" },
  nav_servo:              { zh: "⚙️ 舵機",          en: "⚙️ Servo" },
  nav_motor:              { zh: "⚙️ 舵機",          en: "⚙️ Servo" },
  nav_speech:             { zh: "🗣️ 語音",          en: "🗣️ Speech" },
  nav_led:                { zh: "💡 LED",           en: "💡 LED" },
  nav_camera:             { zh: "📷 相機",          en: "📷 Camera" },
  nav_advanced:           { zh: "🧪 進階",          en: "🧪 Advanced" },
  nav_blockly:            { zh: "🧩 積木編程 ↗",   en: "🧩 Blockly ↗" },
  nav_blockly_title:      { zh: "喺新分頁開 Blockly 積木編程", en: "Open Blockly visual programming in a new tab" },

  // -- status tab --
  status_system_label:   { zh: "系統：", en: "System:" },
  lang_switch_label:     { zh: "語言：", en: "Language:" },
  status_heading:        { zh: "系統狀態",        en: "Status" },
  device_info_heading:   { zh: "裝置資訊",        en: "Device Info" },
  device_battery:        { zh: "🔋 電池",         en: "🔋 Battery" },
  device_wifi:           { zh: "📶 WiFi",         en: "📶 WiFi" },
  device_bluetooth:      { zh: "🔷 藍牙",         en: "🔷 Bluetooth" },
  device_uuid:           { zh: "🤖 機械人 UUID",  en: "🤖 Robot UUID" },
  uuid_query_btn:        { zh: "查詢",            en: "Query" },
  charge_play_label:     { zh: "充電同時播放",     en: "Play While Charging" },
  gesture_label:         { zh: "🖐️ 最近手勢辨識",  en: "🖐️ Last Gesture" },
  head_noise_heading:    { zh: "頭部降噪",        en: "Head Noise Reduction" },
  sonar_heading:         { zh: "聲納",            en: "Sonar" },
  accel_heading:         { zh: "加速度計",        en: "Accelerometer" },
  tilt_led_toggle_label: { zh: "4角度傾側著頭/眼LED", en: "4-direction tilt lights up head/eye LED" },
  tilt_led_direction_prefix: { zh: "目前傾側方向: ", en: "Current tilt direction: " },
  tilt_led_monitoring_hint: { zh: "監測中… (要先開返上面個「加速度計」開關先會收到讀數)",
                               en: "Monitoring… (turn on the \u201cAccelerometer\u201d switch above first to receive readings)" },
  accel_turning_on_hint: { zh: "開啟中…", en: "Turning on…" },
  accel_turn_on_failed_hint: { zh: "開啟失敗", en: "Failed to turn on" },
  accel_move_hint:       { zh: "鬱動 / 傾斜機身睇下數據變化", en: "Move / tilt the robot to see the readings change" },
  uuid_querying_hint:    { zh: "查詢中…", en: "Querying…" },

  // -- actions tab --
  actions_heading:       { zh: "動作 (Actions)",  en: "Actions" },
  actions_load_btn:      { zh: "攞動作列表",      en: "Load Action List" },
  action_name_placeholder:{ zh: "動作名稱 e.g. ACT0", en: "Action name e.g. ACT0" },
  action_play_btn:       { zh: "播放",            en: "Play" },
  action_stop_btn:       { zh: "停止",            en: "Stop" },

  // -- servo tab --
  servo_heading:         { zh: "舵機 (Servos, 1–20)", en: "Servos (1–20)" },
  servo_hint:            { zh: "拖動滑桿, 放手即送出, 自動夾喺安全範圍內。", en: "Drag a slider and release to send — values are auto-clamped to a safe range." },
  servo_time_label:      { zh: "時間(ms)：",       en: "Time (ms):" },
  servo_reset_btn:       { zh: "全部回到中位",     en: "Reset All to Center" },
  servo_power_save:      { zh: "省電",             en: "Power Save" },

  // -- speech tab --
  asr_heading:           { zh: "ASR (語音辨識)",   en: "ASR (Speech Recognition)" },
  engine_label:          { zh: "引擎：",           en: "Engine:" },
  asr_start_btn:         { zh: "開始聆聽",         en: "Start Listening" },
  asr_stop_btn:          { zh: "停止聆聽",         en: "Stop Listening" },
  tts_heading:           { zh: "語音 / TTS",       en: "Speech / TTS" },
  tts_text_placeholder:  { zh: "要講嘅文字",       en: "Text to speak" },
  tts_speak_btn:         { zh: "講嘢 (TTS)",       en: "Speak (TTS)" },
  tts_stop_btn:          { zh: "停止 TTS",         en: "Stop TTS" },
  mic_card_heading:      { zh: "🎙️ 麥克風擁有權", en: "🎙️ Microphone Ownership" },
  mic_card_hint:         { zh: "控制邊個持有麥克風 - App (畀 TTS/Mic Listen 用) 定係機械人自己 (畀 wake word / ASR 用)。同一時間淨係得一方可以用。",
                            en: "Controls who holds the microphone - the app (for TTS/Mic Listen) or the robot itself (for wake word / ASR). Only one side can hold it at a time." },
  mic_release_btn:       { zh: "釋放麥克風俾 App", en: "Release Mic to App" },
  mic_return_btn:        { zh: "交返麥克風俾機器人", en: "Return Mic to Robot" },
  mic_state_on:          { zh: "App 持有中", en: "Held by app" },
  mic_state_off:         { zh: "已交返俾機械人", en: "Returned to robot" },
  mic_keep_held_label:   { zh: "持續搶 Mic (唔俾機械人自動攞返)", en: "Keep holding mic (auto re-take from robot)" },
  mic_keep_held_hint:    { zh: "開咗之後, 就算機械人韌體內部側面攞返 mic (例如 wake-word 引擎自己觸發), app 都會每幾秒自動搶返 - 直到你自己撳「交返麥克風俾機器人」或者閂返呢個掣為止。",
                            en: "When on, even if the robot's firmware internally re-takes the mic on its own (e.g. the wake-word engine triggering it), the app will automatically re-take it every few seconds - until you either return it manually or turn this off." },
  self_interrupt_label:  { zh: "自我打斷",         en: "Self-Interrupt" },
  volume_heading:        { zh: "媒體音量",         en: "Media Volume" },
  volume_hint:           { zh: "控制機械人喇叭嘅媒體音量 (STREAM_MUSIC)，同實體 +/- 按鈕共用同一個音量。",
                            en: "Controls the robot speaker's media volume (STREAM_MUSIC) — shares the same level as the physical +/- buttons." },

  // -- LED tab (Alpha2) --
  led_head_heading:      { zh: "頭部 LED",         en: "Head LED" },
  led_eye_heading:       { zh: "眼睛 LED",         en: "Eye LED" },
  led_mouth_heading:     { zh: "咀部 LED",         en: "Mouth LED" },
  led_color_label:       { zh: "顏色：",           en: "Color:" },
  led_brightness_label:  { zh: "亮度 (1–9)：",     en: "Brightness (1–9):" },
  led_mouth_speed_label: { zh: "速度 (0–5000)：",  en: "Speed (0–5000):" },
  led_preset_long:       { zh: "💡 長開",          en: "💡 On" },
  led_preset_flash:      { zh: "⚡ 閃燈",          en: "⚡ Flash" },
  led_preset_breathe:    { zh: "🫧 呼吸燈",        en: "🫧 Breathe" },
  led_preset_breathe_mouth: { zh: "🫁 呼吸燈",     en: "🫁 Breathe" },
  led_preset_chase:      { zh: "🏃 跑馬燈",        en: "🏃 Chase" },
  led_preset_dual:       { zh: "🎨 雙色燈",        en: "🎨 Dual Color" },
  led_preset_stop:       { zh: "⏹ 停止",          en: "⏹ Stop" },

  // -- camera tab --
  camera_heading:        { zh: "相機",             en: "Camera" },
  camera_feature_key:    { zh: "功能鍵",           en: "Feature Key" },

  // -- Alpha2 版 PIR card (見 index.html/app-servo.js/app-accel.js 嘅 comment) --
  // 2026-08-15 更新: 真機已確認 PIR 觸發正常, 移除 "未經真機驗證" 個 hint。
  alpha2_pir_heading:       { zh: "PIR 感應器", en: "PIR Sensor" },
  alpha2_pir_switch_label:  { zh: "感應器開關", en: "Sensor Switch" },
  alpha2_pir_alert_label:   { zh: "警示反應 (LED+鈴聲)", en: "Alert Reaction (LED + Chime)" },

  // -- Alpha2 speech tab (ASR card) --
  asr_reset_btn:          { zh: "🔄 重置語音 (實驗)", en: "🔄 Reset Speech (Experimental)" },
  asr_reset_btn_title:    { zh: "試驗性: 切換引擎後 TTS 失聲時試下呢個, 睇下用唔用得返, 唔使重開機",
                             en: "Experimental: if TTS goes silent after switching engines, try this before rebooting" },
  asr_current_engine_unswitched: { zh: "目前引擎：未切換", en: "Current engine: not switched" },
  asr_procedure_warning:  { zh: "⚠️ <b>要有反應必須跟足呢個次序：</b>\n        (0) 撳「中文 (iFlytek)」或「英文 (Nuance)」——會即刻重新綁定 speech service, 等\n        speech_ready event 返嚟 (見上面「🔀 ASR 引擎已切換去」log) 先算完成;\n        (1) 撳「開始聆聽」——呢個淨係將 speech engine 撥入接收 wake word 嘅狀態,\n        唔係即刻開始錄音;\n        (2) 對住機械人講 wake word（實測確認：機身 config 寫死 <code>CN_WAKEUP_NIHAO_ALPHA</code>\n        = <b>「你好，Alpha」</b>，唔可以自訂/唔可以跳過）觸發硬件 mic array 偵測——呢個 wake word\n        偵測本身一直用緊 iFlytek 嘅硬件 CAE 引擎，唔受呢度揀 Nuance/iFlytek 影響;\n        (3) 偵測到之後先真正開始錄音辨識, 呢陣先可以講指令。\n        <br>\n        ⚠️ <b>2026-08 logcat 覆核發現（推翻上面舊結論）：</b>\n        機身韌體自己 (<code>AlphaMainSeviceImpl</code>) 開機時已經自己 bind 咗一份獨立嘅\n        speech service, wake word 偵測 + TTS 提示全部行呢條獨立路徑，<b>完全唔受呢度\n        「切引擎」呢個 app 側 API 影響</b>——即係話呢個掣淨係改緊你自己個 app 主動\n        call `speech/tts` 或者 `speech/start_asr` 果條路徑，唔會令你聽到嘅 wake word\n        回答變聲、變語言。想改開機 wake word 用邊種語言，要用下面「⚙️ 機身語言設定」\n        個 preset (需要重開機)。\n        <br>\n        ⚠️ <b>Confidence 門檻</b>（反編譯 <code>Alpha2Services-v1.1.7.3.20</code> 證實）：\n        自由辨識（wake word 之後嗰段，唔係下面嘅語法式辨識）由頭到尾都行 <b>Nuance</b>\n        （<code>NuanceASRImpl</code>）。\n        Local recognition confidence 要 ≥4500 先會直接接受；低於此分數會嘗試等雲端補完，\n        但 Nuance 雲端伺服器已停用，所以低分結果實際上會全部失敗。<b>企近部機、慢慢講、\n        咬字清楚</b>可以提高 confidence。已確認用英文完整短句得（例如 \"wave the left hand\"，\n        唔好淨講單字），見下面已知指令參考。\n        <br>\n        想試真正嘅 iFlytek 辨識（唔經 wake word 嘅自由語音，而係 grammar 限定詞彙）：\n        先撳「中文 (iFlytek)」切換引擎，再用下面「語音輸入三合一測試」card 嘅「語法式辨識」測試。",
                             en: "⚠️ <b>For a response you must follow this exact sequence:</b>\n        (0) Press \u201cChinese (iFlytek)\u201d or \u201cEnglish (Nuance)\u201d — this immediately re-binds the speech\n        service; wait for the speech_ready event (see the \u201c🔀 ASR engine switched to\u201d log above) before\n        continuing;\n        (1) Press \u201cStart Listening\u201d — this only puts the speech engine into wake-word-receiving\n        state, it does not start recording immediately;\n        (2) Say the wake word to the robot (confirmed by testing: the on-device config hardcodes\n        <code>CN_WAKEUP_NIHAO_ALPHA</code>\n        = <b>\u201c你好，Alpha\u201d (\u201cHello, Alpha\u201d)</b>, which cannot be customized or skipped) to trigger the hardware mic array\n        detection — this wake-word detection always runs on iFlytek's hardware CAE engine, regardless of the\n        Nuance/iFlytek choice here;\n        (3) Only after detection does it actually start recording/recognition — this is when you can speak a command.\n        <br>\n        ⚠️ <b>2026-08 logcat review finding (overturns the conclusion above):</b>\n        The robot's own firmware (<code>AlphaMainSeviceImpl</code>) already binds its own separate\n        speech service at boot; wake-word detection + TTS prompts all run through that independent path,\n        <b>completely unaffected by this app-side \u201cswitch engine\u201d API</b> — meaning this button only\n        changes the path your own app actively calls via `speech/tts` or `speech/start_asr`; it will not\n        change the voice or language of the wake-word response you hear. To change which language the\n        boot-time wake word uses, use the preset in \u201c⚙️ On-Device Language Settings\u201d below (requires a reboot).\n        <br>\n        ⚠️ <b>Confidence threshold</b> (confirmed by decompiling <code>Alpha2Services-v1.1.7.3.20</code>):\n        free recognition (the part after the wake word, not the grammar-based recognition below) runs\n        entirely on <b>Nuance</b> (<code>NuanceASRImpl</code>).\n        Local recognition confidence needs to be ≥4500 to be accepted directly; below that it tries to wait\n        for cloud completion, but the Nuance cloud server is permanently offline, so low-confidence results\n        effectively all fail. <b>Standing close to the robot, speaking slowly, and enunciating clearly</b> can\n        raise the confidence score. Confirmed to work with full English sentences (e.g. \"wave the left hand\",\n        not single words) — see the known-command reference below.\n        <br>\n        To try genuine iFlytek recognition (free speech without a wake word, but limited to grammar-defined\n        vocabulary): press \u201cChinese (iFlytek)\u201d above to switch engines first, then use \u201cGrammar-based\n        Recognition\u201d in the \u201cSpeech Input 3-in-1 Test\u201d card below." },
  asr_result_label:       { zh: "📝 辨識結果", en: "📝 Recognition Result" },
  asr_intent_label:       { zh: "🎯 意圖分類 (rule/action)", en: "🎯 Intent Classification (rule/action)" },
  asr_known_commands_summary: { zh: "📖 已知內建指令參考（Nuance offline grammar，喺 Nuance binding 之下用）",
                                 en: "📖 Known Built-in Command Reference (Nuance offline grammar, used under Nuance binding)" },
  asr_known_commands_intro: { zh: "機身有兩個 speech engine：<b>Nuance VoCon</b>（offline，內建喺\n          <code>alpha2services</code>）同 <b>iFlytek</b>。經反編譯 <code>Alpha2Services-v1.1.7.3.20</code>\n          證實：Nuance 呢邊 <code>speech_initGrammar</code>／<code>startSpeechGrammar</code>\n          係完全未實作嘅空 stub（method body 得一句 <code>return-void</code>），自訂詞彙一律唔會生效；\n          但呢邊有一個獨立、寫死喺 native code 嘅內建 grammar（下面呢個表），單靠 local recognition\n          就會 work，唔使雲端（Nuance 雲端伺服器已停用，但呢個內建 grammar 唔靠佢）。\n          單字容易 mis-parse（例如淨係講\"wave\"可能會 match 錯做 QA），建議用完整短句\n          （例如「wave your left hand」）。\n          <br><br>\n          ⚠️ <b>Confidence 門檻</b>：反編譯證實 local recognition 嘅 confidence 分數要 ≥4500\n          先會直接接受（唔使等雲端）；低於呢個門檻會判 invalid、等雲端補完——但雲端已停用，\n          所以低於 4500 分嘅結果實際上會全部有去無回。想提高成功率：<b>企近部機、慢慢講、\n          咬字清楚、減少背景噪音</b>，呢啲都會直接影響 confidence 分數。\n          <br><br>\n          想試 iFlytek（中文，有真身 grammar 實作，未反查完整語法格式）：先用 ASR card 嘅\n          「中文 (iFlytek)」切換引擎，再用下面「語音輸入三合一測試」。",
                                 en: "The robot has two speech engines: <b>Nuance VoCon</b> (offline, built into\n          <code>alpha2services</code>) and <b>iFlytek</b>. Confirmed by decompiling <code>Alpha2Services-v1.1.7.3.20</code>:\n          on the Nuance side, <code>speech_initGrammar</code>/<code>startSpeechGrammar</code>\n          is a completely unimplemented empty stub (method body is just <code>return-void</code>), so custom vocabulary\n          never takes effect; but there's a separate built-in grammar hardcoded in native code (the table below) that\n          works purely via local recognition without needing the cloud (the Nuance cloud server is offline, but this\n          built-in grammar doesn't depend on it).\n          Single words are prone to mis-parsing (e.g. saying just \"wave\" might mis-match as QA); full sentences are\n          recommended (e.g. \u201cwave your left hand\u201d).\n          <br><br>\n          ⚠️ <b>Confidence threshold</b>: decompiling confirms local recognition's confidence score needs to be ≥4500\n          to be accepted directly (without waiting for the cloud); below this threshold it's judged invalid and waits\n          for cloud completion — but the cloud is offline, so results below 4500 effectively go nowhere. To improve\n          success rate: <b>stand close to the robot, speak slowly, enunciate clearly, and reduce background\n          noise</b> — these all directly affect the confidence score.\n          <br><br>\n          To try iFlytek (Chinese, has a real grammar implementation, exact grammar format not fully reverse-engineered):\n          first switch engines using \u201cChinese (iFlytek)\u201d on the ASR card, then use \u201cSpeech Input 3-in-1 Test\u201d below." },
  asr_cmd_action_heading: { zh: "Action_Performance（動作，機械人會自己鬱，可能搶咗你自訂嘅action）：",
                             en: "Action_Performance (actions — the robot moves on its own, may override your custom actions):" },
  asr_cmd_qa_heading:     { zh: "QA（問答，機械人淨係用把口答，唔會鬱——最穩陣測試呢組）：",
                             en: "QA (questions & answers — the robot only replies verbally, doesn't move — safest group to test):" },
  asr_cmd_system_heading: { zh: "系統／裝置：", en: "System / Device:" },
  asr_cmd_move_label:     { zh: "移動：", en: "Move: " },
  asr_cmd_arm_label:      { zh: "手臂：", en: "Arm: " },
  asr_cmd_leg_label:      { zh: "腿/姿勢：", en: "Leg/Pose: " },
  asr_cmd_head_label:     { zh: "頭：", en: "Head: " },
  asr_cmd_expression_label: { zh: "表情：", en: "Expression: " },
  asr_cmd_sit_down:       { zh: "sit down（squat）", en: "sit down (squat)" },
  asr_cmd_self_check:     { zh: "self check（連線／電量／電源／儲存／音量／WiFi 狀態）",
                             en: "self check (connection / battery / power / storage / volume / WiFi status)" },

  // -- Alpha2 speech tab (3-in-1 test card) --
  speech_test_heading:    { zh: "語音輸入三合一測試（同一句嘢，睇邊個功能有反應）",
                             en: "Speech Input 3-in-1 Test (same phrase, see which feature responds)" },
  speech_test_intro:      { zh: "呢度3條係唔同嘅AIDL method，用**同一句輸入**一齊送出，方便你直接對比邊個先真係work：",
                             en: "These are 3 different AIDL methods, sent together with **the same input**, so you can directly compare which one actually works:" },
  speech_test_li_inject_label: { zh: "模擬講嘢", en: "Simulated Speech" },
  speech_test_li_grammar_label: { zh: "語法式辨識", en: "Grammar-based Recognition" },
  speech_test_li_nlu_label: { zh: "文字語意理解", en: "Text Understanding" },
  speech_test_li_inject:  { zh: "（<code>onSpeech</code> dictation，未測試，SDK標咗 <code>@Deprecated</code> 但搵唔到原因）——\n          如果work，結果會經返「ASR」card嘅「辨識結果」／「意圖分類」顯示（同真正講嘢一樣嘅path）",
                             en: " (<code>onSpeech</code> dictation, untested, SDK marks it <code>@Deprecated</code> but no reason found) —\n          if it works, the result shows via the \u201cASR\u201d card's \u201cRecognition Result\u201d/\u201cIntent Classification\u201d (same path as actually speaking)" },
  speech_test_li_grammar: { zh: "（<code>initSpeechGrammar</code> → <code>startSpeechGrammar</code>）——\n          ⚠️ 已反編譯確認：Nuance binding 之下係空 stub，一定失敗；要撞到 iFlytek\n          嘅真身實作（<code>com.iflytek.cloud.SpeechRecognizer</code>），要自己先用上面\n          「中文 (iFlytek)」掣切換，未切嘅話呢個測試會直接回傳 backend 個 guard error。\n          <code>strGrammar</code> 確實語法格式仍未反查完整，會將你打嘅嘢原封不動送去",
                             en: " (<code>initSpeechGrammar</code> → <code>startSpeechGrammar</code>) —\n          ⚠️ confirmed by decompiling: it's an empty stub under Nuance binding and will always fail; to reach iFlytek's\n          real implementation (<code>com.iflytek.cloud.SpeechRecognizer</code>), switch engines using \u201cChinese\n          (iFlytek)\u201d above first — without switching, this test just returns the backend's guard error.\n          The exact <code>strGrammar</code> format hasn't been fully reverse-engineered yet; whatever you type is sent as-is" },
  speech_test_li_nlu:     { zh: "（<code>onTextUnderstand</code>，⚠️ 已confirm冧咗）——約1ms就返、\n          冇觸發任何callback，需要雲端但雲端伺服器已停用，留低純粹做對比基準",
                             en: " (<code>onTextUnderstand</code>, ⚠️ confirmed broken) — returns in about 1ms,\n          no callback fires, needs the cloud which is offline, kept purely as a comparison baseline" },
  speech_test_input_placeholder: { zh: "輸入任何一句話，例如：stand up / how old are you",
                                    en: "Type any sentence, e.g.: stand up / how old are you" },
  speech_test_run_all_btn: { zh: "一齊試曬 ▶", en: "Test All ▶" },
  speech_test_inject_label: { zh: "🗣️ 模擬講嘢 (inject)", en: "🗣️ Simulated Speech (inject)" },
  speech_test_inject_sent: { zh: "已送出，睇上面ASR card", en: "Sent, check the ASR card above" },
  speech_test_grammar_label: { zh: "📖 語法式辨識 (grammar)", en: "📖 Grammar-based Recognition (grammar)" },
  speech_test_nlu_label:  { zh: "🧠 文字語意理解 (NLU)", en: "🧠 Text Understanding (NLU)" },
  speech_test_stop_inject_btn: { zh: "停止模擬講嘢", en: "Stop Simulated Speech" },
  speech_test_stop_grammar_btn: { zh: "停止語法辨識", en: "Stop Grammar Recognition" },
  speech_test_grammar_result_label: { zh: "🎯 語法辨識結果 (start之後先有)", en: "🎯 Grammar Recognition Result (only after start)" },

  // -- Alpha2 speech tab (TTS engine/voice buttons) --
  tts_engine_iflytek_btn: { zh: "iFlytek (訊飛)", en: "iFlytek" },
  tts_engine_android_btn: { zh: "Android 預設", en: "Android Default" },
  tts_voice_label:        { zh: "聲音：", en: "Voice:" },
  tts_voice_default_btn:  { zh: "南南", en: "Nannan (default)" },

  // -- Alpha2 speech tab (on-device language settings) --
  service_config_heading: { zh: "⚙️ 機身語言設定", en: "⚙️ On-Device Language Settings" },
  service_config_hint:    { zh: "切換機身 wake word／ASR 語言（跟原廠設定），寫入後要重開機先生效。",
                             en: "Switches the robot's wake-word/ASR language (following the factory settings) — takes effect after a reboot." },
  service_config_cn_btn:  { zh: "🇨🇳 中文（你好 阿爾法）", en: "🇨🇳 Chinese (你好 阿爾法)" },
  service_config_en_btn:  { zh: "🇺🇸 英文（Hello Alpha）", en: "🇺🇸 English (Hello Alpha)" },
  service_config_reboot_btn: { zh: "🔁 立即重開機", en: "🔁 Reboot Now" },

  // -- Alpha2 speech tab (ASR engine switch buttons + dynamic status strings) --
  asr_switch_zh_btn:      { zh: "中文 (iFlytek)", en: "Chinese (iFlytek)" },
  asr_switch_en_btn:      { zh: "英文 (Nuance)", en: "English (Nuance)" },
  asr_switching_to:       { zh: "切緊去 {label} 引擎 (重新綁定 speech service 中)…",
                             en: "Switching to {label} engine (re-binding speech service)…" },
  asr_current_engine_switching: { zh: "目前引擎：切換中… ({label})", en: "Current engine: switching… ({label})" },
  asr_switch_failed_prefix: { zh: "切換引擎失敗: ", en: "Failed to switch engine: " },
  asr_current_engine_switch_failed: { zh: "目前引擎：切換失敗", en: "Current engine: switch failed" },
  asr_pick_engine_first:  { zh: "請先揀「中文 (iFlytek)」或「英文 (Nuance)」",
                             en: "Please choose \u201cChinese (iFlytek)\u201d or \u201cEnglish (Nuance)\u201d first" },
  asr_rebinding_wait:     { zh: "引擎重新綁定緊，請等 speech_ready 之後再試",
                             en: "Engine is re-binding, please wait for speech_ready and try again" },
  asr_preparing_listen:   { zh: "準備聆聽中 — 而家講 \"hello alpha\" 觸發硬件 wake word 偵測，先會真正開始錄音…",
                             en: "Preparing to listen — say \"hello alpha\" now to trigger hardware wake-word detection, then recording actually starts…" },
  asr_stopped:            { zh: "已停止", en: "Stopped" },
  asr_resetting:          { zh: "重置緊…", en: "Resetting…" },
  asr_reset_sent_ok:      { zh: "已送出重置指令，試下撳返「播放 TTS」有冇聲返嚟",
                             en: "Reset command sent — try pressing \u201cPlay TTS\u201d again to see if sound comes back" },
  asr_reset_failed_prefix: { zh: "重置失敗：", en: "Reset failed: " },
  asr_reset_failed_unknown: { zh: "未知錯誤", en: "Unknown error" },

  // -- Alpha2 speech tab (service config + 3-in-1 test dynamic strings) --
  service_config_writing:  { zh: "寫入緊…", en: "Writing…" },
  service_config_write_ok: { zh: "✅ 已寫入，記得重開機先會生效。", en: "✅ Written — remember to reboot for it to take effect." },
  service_config_write_failed_prefix: { zh: "❌ 失敗：", en: "❌ Failed: " },
  service_config_reboot_confirm: { zh: "確定要立即重開機？", en: "Reboot now?" },
  service_config_rebooting: { zh: "重開機緊…", en: "Rebooting…" },
  service_config_reboot_ok: { zh: "✅ 重開機緊…", en: "✅ Rebooting…" },
  service_config_reboot_failed_prefix: { zh: "❌ 重開機失敗：", en: "❌ Reboot failed: " },
  service_config_reboot_failed_suffix: { zh: "（請手動 power-cycle）", en: " (please power-cycle manually)" },
  speech_test_enter_text_alert: { zh: "請輸入文字", en: "Please enter some text" },
  speech_test_grammar_init:  { zh: "初始化緊…", en: "Initializing…" },
  speech_test_nlu_analyzing: { zh: "分析緊…", en: "Analyzing…" },
  speech_test_grammar_init_failed: { zh: "初始化失敗", en: "Initialization failed" },
  asr_engine_ready_hint:  { zh: "引擎已就緒，可以撳「開始聆聽」", en: "Engine ready — you can press \u201cStart Listening\u201d" },
  asr_engine_switched_log_prefix: { zh: "🔀 ASR 引擎已切換去: ", en: "🔀 ASR engine switched to: " },
  asr_current_engine_prefix: { zh: "目前引擎：", en: "Current engine: " },
  log_error_code_prefix:  { zh: "錯誤 (code=", en: "Error (code=" },

  // -- advanced tab (Alpha2 only; raw AIDL passthrough, unverified-hardware methods) --
  adv_actions_heading:        { zh: "動作：進階",              en: "Actions: Advanced" },
  adv_action_file_placeholder:{ zh: "動作檔案路徑",            en: "Action file path" },
  adv_action_file_play_btn:   { zh: "用檔案播放",              en: "Play by File" },
  adv_action_disable_btn:     { zh: "停用動作播放",            en: "Disable Action Playback" },
  adv_action_enable_btn:      { zh: "啟用動作播放",            en: "Enable Action Playback" },
  adv_action_query_btn:       { zh: "查詢是否播放緊",          en: "Query Is Actioning" },
  adv_event_param_placeholder:{ zh: "param (base64, 可留空)",  en: "param (base64, optional)" },
  adv_event_trigger_btn:      { zh: "觸發事件",                en: "Trigger Event" },
  adv_action_hint:            { zh: "event_type / param 嘅實際意義未經真機驗證。",
                                  en: "The real meaning of event_type / param is unverified against real hardware." },
  adv_speech_heading:         { zh: "語音：英文理解 / ASR 回放", en: "Speech: English Understand / ASR Replay" },
  adv_english_understand_btn: { zh: "註冊英文理解 (線上)",     en: "Register English Understand (Online)" },
  adv_english_offline_btn:    { zh: "註冊英文理解 (離線)",     en: "Register English Understand (Offline)" },
  adv_replay_register_btn:    { zh: "註冊 ASR 歷史回放",       en: "Register ASR History Replay" },
  adv_speech_hint:             { zh: "呢三個 API 具體喺咩情況下觸發回呼未經真機驗證，結果會顯示喺呢度同事件 Log。",
                                  en: "Exactly when these three APIs fire a callback is unverified against real hardware — results show here and in the event log." },
  adv_serial_heading:         { zh: "序列埠：原始資料",        en: "Serial: Raw Data" },
  adv_serial_target_label:    { zh: "目標：",                  en: "Target:" },
  adv_serial_data_placeholder:{ zh: "data (base64)",           en: "data (base64)" },
  adv_serial_send_btn:        { zh: "送出",                    en: "Send" },
  adv_serial_number_btn:      { zh: "攞機身序號",              en: "Get Serial Number" },
  adv_bt_heading:              { zh: "藍牙序列埠",              en: "Bluetooth Serial" },
  adv_bt_send_cmd_btn:        { zh: "送出命令",                en: "Send Command" },
  adv_bt_at_placeholder:      { zh: "AT 指令",                 en: "AT command" },
  adv_bt_send_at_btn:         { zh: "送出 AT 指令",            en: "Send AT Command" },
  adv_bt_hint:                 { zh: "呢個係機身藍牙序列埠連線，同上面「裝置資訊」嘅系統藍牙開關係兩回事。收到嘅資料會顯示喺事件 Log (bt_rcv)。",
                                  en: "This is the robot's Bluetooth serial link, separate from the system Bluetooth toggle in Device Info above. Received data appears in the event log (bt_rcv)." },
  adv_about_heading:           { zh: "關於", en: "About" },
  adv_about_version_label:     { zh: "版本：", en: "Version: " },
  adv_about_repo_label:        { zh: "源碼：", en: "Source: " },

  // -- xiaozhi (小智 AI 對話) --
  nav_xiaozhi:                { zh: "🤖 小智",              en: "🤖 XiaoZhi" },
  xiaozhi_heading:            { zh: "🤖 小智 AI 對話",       en: "🤖 XiaoZhi AI Chat" },
  xiaozhi_phase4_hint:        { zh: "開關開＝配對／連線／隨時語音對話，關＝斷開。首次要喺 xiaozhi.me 輸入機械人讀出嘅配對碼。",
                                 en: "Switch on = pair/connect/voice chat anytime, off = disconnect. First time, enter the pairing code the robot speaks at xiaozhi.me." },
  xiaozhi_unsupported_hint:   { zh: "呢部機嘅 Android 版本過舊，語音對話功能將會停用（純文字對話不受影響）。",
                                 en: "This device's Android version is too old for voice chat - it will be disabled (text-only chat is unaffected)." },
  xiaozhi_activation_prompt:  { zh: "請喺 <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a> 輸入以下配對碼：",
                                 en: "Please enter the pairing code below at <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a>:" },
  xiaozhi_activation_code_chat_prefix: { zh: "🔑 配對碼：", en: "🔑 Pairing code:" },
  xiaozhi_activation_modal_prompt: { zh: "請喺 <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a> 輸入以下配對碼：",
                                 en: "Please enter the pairing code below at <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a>:" },
  xiaozhi_activation_modal_dismiss: { zh: "知道喇", en: "Got it" },
  xiaozhi_session_toggle_label: { zh: "🤖 小智（開＝連線並隨時語音對話，關＝斷開）", en: "🤖 XiaoZhi (on = connect & voice chat anytime, off = disconnect)" },
  xiaozhi_status_disconnected:{ zh: "未連接",               en: "Disconnected" },
  xiaozhi_status_checking:    { zh: "檢查中…",              en: "Checking…" },
  xiaozhi_status_awaiting_code: { zh: "等待配對…",          en: "Awaiting pairing…" },
  xiaozhi_status_connecting:  { zh: "連接中…",              en: "Connecting…" },
  xiaozhi_status_connected:   { zh: "已連接",               en: "Connected" },
  xiaozhi_status_error:       { zh: "連接失敗",             en: "Connection failed" },
  xiaozhi_mic_held:           { zh: "🎤 麥克風：已攞到（語音對話中）", en: "🎤 Mic: acquired (voice chat active)" },
  xiaozhi_mic_released:       { zh: "🎤 麥克風：已放低",            en: "🎤 Mic: released" },
  xiaozhi_ota_custom_heading: { zh: "⚙️ 自訂小智 server",     en: "⚙️ Custom XiaoZhi server" },
  xiaozhi_ota_custom_label:   { zh: "開＝自架 server，關＝官方 xiaozhi.me",
                                 en: "On = self-hosted server, off = official xiaozhi.me" },
  xiaozhi_ota_custom_hint:    { zh: "淨係 OTA 位址係必填；其餘留空會自動攞返嚟，連接住期間唔可以更改。",
                                 en: "Only the OTA URL is required; leave the rest blank to fetch automatically. Cannot be changed while connected." },
  xiaozhi_ota_field_ota:      { zh: "OTA 位址",             en: "OTA URL" },
  xiaozhi_ota_field_ws:       { zh: "WebSocket 位址",        en: "WebSocket URL" },
  xiaozhi_ota_field_mac:      { zh: "MAC (Device-Id)",      en: "MAC (Device-Id)" },
  xiaozhi_ota_field_token:    { zh: "Token",                en: "Token" },
  xiaozhi_ota_custom_save:    { zh: "儲存",                  en: "Save" },
  xiaozhi_ota_custom_saved:   { zh: "✅ 已儲存自訂 server 設定",     en: "✅ Custom server settings saved" },
  xiaozhi_ota_custom_error:   { zh: "❌ 儲存失敗",              en: "❌ Failed to save" },
  xiaozhi_ota_custom_url_required: { zh: "請先填寫 OTA 位址",       en: "Please enter an OTA URL first" },
  xiaozhi_mcp_tools_heading:  { zh: "🔧 內置MCP功能列表",       en: "🔧 Built-in MCP Tools" },
  xiaozhi_mcp_tools_hint:     { zh: "控制呢部機向小智暴露邊啲工具。同 xiaozhi.me console 嘅「MCP接入點」係兩回事。",
                                 en: "Control which tools this device exposes to XiaoZhi. Not the same as xiaozhi.me console's \"MCP access point\"." },
  xiaozhi_mcp_tools_expand_label: { zh: "展開",              en: "Expand" },
  xiaozhi_mcp_tools_refresh:  { zh: "重新整理",              en: "Refresh" },
  xiaozhi_mcp_tools_loading:  { zh: "載入中…",               en: "Loading…" },
  xiaozhi_mcp_tools_empty:    { zh: "未有可用工具",          en: "No tools available" },
  xiaozhi_mcp_tools_error:    { zh: "❌ 讀取失敗",              en: "❌ Failed to load" },
  xiaozhi_mcp_call_label:     { zh: "工具呼叫",             en: "Tool call" },
  xiaozhi_console_heading:    { zh: "🌐 小智控制台",          en: "🌐 XiaoZhi Console" },
  xiaozhi_console_open_btn:   { zh: "前往 xiaozhi.me",       en: "Open xiaozhi.me" },
  xiaozhi_text_placeholder:   { zh: "打字同小智傾偈…",       en: "Type a message to XiaoZhi…" },
  xiaozhi_send_text:          { zh: "送出",                 en: "Send" },
  xiaozhi_send_text_error:    { zh: "送出失敗",             en: "Failed to send" },
  xiaozhi_stop_all_btn:       { zh: "⏹ 全部停止",           en: "⏹ Stop All" },

  // -- event log --
  event_log_heading:     { zh: "即時事件 Log (WebSocket)", en: "Live Event Log (WebSocket)" },
  clear_log_btn:         { zh: "清空 Log",         en: "Clear Log" },
  auto_scroll_label:     { zh: "自動捲動",         en: "Auto-scroll" },
};

/** Looks up a translated string for the current uiLang, falling back to zh if
 *  the key or language is missing. Used for dynamic (JS-set) text that isn't
 *  a static [data-i18n] element - e.g. status messages written via
 *  el.textContent = "..." from event handlers. See applyUiLanguage() below for
 *  the static-element equivalent. */
function t(key) {
  const entry = I18N[key];
  if (!entry) return key;
  return entry[uiLang] || entry.zh;
}

/** Applies uiLang to every [data-i18n]-tagged element currently in the DOM.
 *  Elements tagged data-i18n-attr="placeholder" (etc) get the translation written to
 *  that attribute instead of textContent - needed for <input placeholder="...">
 *  where the text isn't a child text node. data-i18n-attr="html" is a special case:
 *  writes to innerHTML instead of an attribute, for the handful of long hint blocks
 *  that contain inline <b>/<code> markup which must survive translation (the I18N
 *  entry's zh/en values contain literal HTML in that case, not plain text). */
function applyUiLanguage() {
  document.querySelectorAll("[data-i18n]").forEach(function (el) {
    const entry = I18N[el.dataset.i18n];
    if (!entry) return;
    const text = entry[uiLang] || entry.zh;
    const attr = el.dataset.i18nAttr;
    if (attr === "html") {
      el.innerHTML = text;
    } else if (attr) {
      el.setAttribute(attr, text);
    } else {
      el.textContent = text;
    }
  });
  document.querySelectorAll("[data-ui-lang-btn]").forEach(function (btn) {
    btn.classList.toggle("active", btn.dataset.uiLangBtn === uiLang);
  });
}

function setUiLanguage(lang) {
  if (lang !== "zh" && lang !== "en") return;
  uiLang = lang;
  localStorage.setItem("ui_lang", lang);
  applyUiLanguage();
  // Category tab labels (基本/跳舞/... and 全部) are built dynamically from
  // ACTION_CATEGORIES, not tagged with data-i18n, so applyUiLanguage() alone won't
  // update them - re-run the builders if the action lists are already loaded.
  if (allActions.length > 0) {
    buildActionSubTabs();
    renderActionList();
  }
  // Servo group/slider labels are also built dynamically (not [data-i18n]-tagged) -
  // relabel in place rather than calling buildServoGrid() again, since a full rebuild
  // would reset every slider back to its calibrated home position and lose whatever
  // angle the person currently has dialled in.
  relabelServoGrid();
}

/** Re-applies servo group headings and #id-name slider labels for the current
 *  uiLang, without touching slider positions/values (see setUiLanguage() above for
 *  why not to rebuild). */
function relabelServoGrid() {
  document.querySelectorAll(".servo-group").forEach(function (groupEl, idx) {
    const group = SERVO_GROUPS[idx % SERVO_GROUPS.length];
    if (!group) return;
    const title = groupEl.querySelector(".servo-group-title");
    if (title) {
      title.innerHTML = "<span class=\"servo-group-icon\">" + group.icon + "</span>" + (uiLang === "en" ? group.labelEn : group.label);
    }
    groupEl.querySelectorAll(".servo-slider-label").forEach(function (labelEl) {
      const m = /^#(\d+)/.exec(labelEl.textContent);
      if (!m) return;
      const id = parseInt(m[1], 10);
      labelEl.textContent = "#" + id + " " + servoNameOf(id);
    });
  });
}

// ---------------- Backend ----------------
// currentBackend 保留呢個變數 (而唔係將所有 currentBackend === "alpha2" 嘅
// guard 全部拆晒) 純粹係為咗減少呢次改動嘅範圍 - app-camera.js/app-mic.js
// 呢類檔案有唔少 "if (currentBackend !== 'alpha2') return;" 呢類 guard, 留低
// 呢個常數等佢哋原封不動照樣 work。
const currentBackend = "alpha2";

// Surface any uncaught JS exception to console.error, which MainActivity's
// WebChromeClient.onConsoleMessage() forwards to logcat (tag "WebViewConsole"). Without
// this, an exception thrown during page init - e.g. connectWs() failing on an older
// WebView's WebSocket implementation - fails completely silently with no trace anywhere.
window.onerror = function (message, source, lineno, colno, error) {
  console.error("Uncaught: " + message + " at " + source + ":" + lineno + ":" + colno);
  return false; // still let the browser's own default error handling happen too
};

// ---------------- Servo calibration ----------------
// Measured on the physical robot (home point via the official "二代舵機校準軟件
// 1.0.0.4", min/max travel via on-robot testing). These are NOT protocol defaults
// from the SDK - they're per-unit hardware calibration and could differ on another
// robot. Used to clamp input so a typo or an out-of-range value can't be sent to a
// servo and force it against its mechanical limit.
// Home-point values confirmed directly against the robot by the user (2026-07) after an
// initial column-misalignment was caught by a min<=home<=max consistency check.
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

/** Clamps a value into [min,max] for the given servo id; returns the raw value unchanged
 *  if the id isn't in the calibration table (shouldn't happen for 1-20). */
function clampServoAngle(id, value) {
  const cal = SERVO_CALIBRATION[id];
  if (!cal) return value;
  return Math.max(cal.min, Math.min(cal.max, value));
}

// Official servo names, per the user's mapping.
const SERVO_NAMES = {
  1:  { zh: "右肩上下", en: "R Shoulder Pitch" },
  2:  { zh: "右肩左右", en: "R Shoulder Roll" },
  3:  { zh: "右肘",     en: "R Elbow" },
  4:  { zh: "左肩上下", en: "L Shoulder Pitch" },
  5:  { zh: "左肩左右", en: "L Shoulder Roll" },
  6:  { zh: "左肘",     en: "L Elbow" },
  7:  { zh: "右股左右", en: "R Hip Roll" },
  8:  { zh: "右股上下", en: "R Hip Pitch" },
  9:  { zh: "右膝",     en: "R Knee" },
  10: { zh: "右腳掌上下", en: "R Ankle Pitch" },
  11: { zh: "右腳掌左右", en: "R Ankle Roll" },
  12: { zh: "左股左右", en: "L Hip Roll" },
  13: { zh: "左股上下", en: "L Hip Pitch" },
  14: { zh: "左膝",     en: "L Knee" },
  15: { zh: "左腳掌上下", en: "L Ankle Pitch" },
  16: { zh: "左腳掌左右", en: "L Ankle Roll" },
  17: { zh: "右指",     en: "R Hand" },
  18: { zh: "左指",     en: "L Hand" },
  19: { zh: "頭左右",   en: "Head Yaw" },
  20: { zh: "頭上下",   en: "Head Pitch" },
};

/** 攞返一個 servo 嘅顯示名, 跟主 UI 語言 (uiLang)。 */
function servoNameOf(id) {
  const entry = SERVO_NAMES[id];
  if (!entry) return String(id);
  return uiLang === "en" ? (entry.en || entry.zh) : (entry.zh || entry.en);
}

// Body-part grouping for the servo panel, per the user's mapping:
// head 19/20, right arm 1/2/3/17, left arm 4/5/6/18,
// right leg 7/8/9/10/11, left leg 12/13/14/15/16.
const SERVO_GROUPS = [
  { key: "head",       label: "頭",  labelEn: "Head",      icon: "🧠", ids: [19, 20] },
  { key: "right-arm",  label: "右手", labelEn: "R Arm",     icon: "💪", ids: [1, 2, 3, 17] },
  { key: "left-arm",   label: "左手", labelEn: "L Arm",     icon: "💪", ids: [4, 5, 6, 18] },
  { key: "right-leg",  label: "右腳", labelEn: "R Leg",     icon: "🦵", ids: [7, 8, 9, 10, 11] },
  { key: "left-leg",   label: "左腳", labelEn: "L Leg",     icon: "🦵", ids: [12, 13, 14, 15, 16] },
];

// ---------------- Global error surface ----------------
// Any uncaught JS error used to fail silently (a button's onclick handler would just
// stop executing with nothing visible in the page). Both a global handler and every
// api() call now route failures through here so the UI always shows *something*.

function showError(context, err) {
  const banner = document.getElementById("errorBanner");
  const msg = (err && err.message) ? err.message : String(err);
  banner.textContent = "⚠ " + context + ": " + msg;
  banner.style.display = "block";
  console.error(context, err);
}

function clearError() {
  const banner = document.getElementById("errorBanner");
  banner.style.display = "none";
  banner.textContent = "";
}

window.addEventListener("error", function (e) {
  showError("JavaScript error", e.error || e.message);
});
window.addEventListener("unhandledrejection", function (e) {
  showError("Unhandled promise rejection", e.reason);
});

function api(path, params) {
  clearError();
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  return fetch(API + "alpha2/" + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /" + path, new Error(json.error || json.code || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    // fetch() itself throws on network failure (robot unreachable, CORS, etc). Before
    // this catch existed, this would silently abort the calling function and the button
    // would appear completely unresponsive.
    showError("Network error calling /" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

// Camera and audio-testtone/volume/play are plain Android hardware access, not
// implemented by the AIDL backend itself - same physical camera/mic/speaker
// regardless of robot SDK version, so this is really just an alias for api().
function hwApi(path, params) {
  return api(path, params);
}

