// Open Alpha2 — client logic (app-core.js)
// 呢個檔案係由原本單一嘅 app.js 拆出嚟嘅其中一份, 內容: 全局狀態、UI 語言字典、servo 校準表、api()/hwApi() 呢啲所有其他 app-*.js 都要用嘅核心 helper。呢個檔案要第一個 load。
// 全部檔案共用 window/global scope (冇用 ES module), 載入順序由 index.html 嘅
// <script src="..."> 順序決定 - 詳見 index.html 頭嗰段 comment。

// Open Alpha2 — client logic.
// Talks to the on-robot HTTP server (HttpServer.java) via /api/*, and to the
// WebSocket event log (WebSocketServer.java) via /ws.

const API = "/api/";

// ---------------- App / project metadata ----------------
// XIAOZHI_CONSOLE_URL 冇喺呢個 web panel 度用 - 版本號/原始碼連結改咗擺喺
// 機身/手機原生畫面 (MainActivity.java 個 onCreate() 起嗰個 TextView UI,
// 顯示緊 http://<ip>:8888/ 嗰版)。
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
  nav_blockly:            { zh: "🧩 積木編程 ↗",   en: "🧩 Blockly ↗" },
  nav_blockly_title:      { zh: "在新分頁開啟 Blockly 積木編程", en: "Open Blockly visual programming in a new tab" },

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
  uuid_random_btn:       { zh: "🎲 隨機碼",       en: "🎲 Random" },
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
  uuid_card_heading:     { zh: "🤖 機械人 ID / QR code", en: "🤖 Robot ID / QR code" },
  uuid_qr_hint:          { zh: "掃描 QR code 綁定設備", en: "Scan the QR code to bind equipment" },
  uuid_write_btn:        { zh: "寫入 EEPROM", en: "Write EEPROM" },
  uuid_write_confirm:    { zh: "確定更改機械人 ID 為：", en: "Confirm changing robot ID to:" },
  uuid_write_invalid:    { zh: "無效 ID — 只限英數/橫底線，1-31 字元", en: "Invalid ID — alnum/-/_ only, 1-31 chars" },
  uuid_write_writing:    { zh: "寫入中…", en: "Writing…" },
  uuid_write_failed:     { zh: "寫入失敗", en: "Write failed" },
  uuid_write_wrote:      { zh: "已寫入：", en: "Written: " },
  uuid_write_done_prefix:{ zh: "目前 ID：", en: "Current ID: " },
  uuid_copy_btn:         { zh: "📋 複製 ID", en: "📋 Copy ID" },
  uuid_copied:           { zh: "已複製 ✓", en: "Copied ✓" },
  uuid_write_restart_hint:{ zh: "已寫入 EEPROM，要重啟 alpha2services／重開機先會顯示新 ID",
                           en: "Written to EEPROM — restart alpha2services/reboot to see it" },

  // -- actions tab --
  actions_heading:       { zh: "動作 (Actions)",  en: "Actions" },
  actions_load_btn:      { zh: "攞動作列表",      en: "Load Action List" },
  action_name_placeholder:{ zh: "動作名稱 e.g. ACT0", en: "Action name e.g. ACT0" },
  action_play_btn:       { zh: "播放",            en: "Play" },
  action_stop_btn:       { zh: "停止",            en: "Stop" },

  // -- servo tab --
  servo_heading:         { zh: "舵機 (Servos, 1–20)", en: "Servos (1–20)" },
  servo_hint:            { zh: "拖動滑桿, 放手即送出, 自動限制在安全範圍內。", en: "Drag a slider and release to send — values are auto-clamped to a safe range." },
  servo_time_label:      { zh: "時間(ms)：",       en: "Time (ms):" },
  servo_reset_btn:       { zh: "全部回到原位",     en: "Reset All to Home" },
  servo_power_save:      { zh: "省電",             en: "Power Save" },
  servo_tuner_heading:   { zh: "🔧 舵機角度調整", en: "🔧 Servo Angle Tuner" },
  servo_tuner_hint:      { zh: "獨立於舵機分頁的進階微調器 — 直接修改 20 顆 servo 角度，±1° 精調，一鍵讀取全部目前角度。與原廠 1.0.0.4 校準工具相同佈局（1-20 對應肩/肘/髖/膝/踝/手/頭）。長按 ±1 連發時已禁用系統複製選單。", en: "Standalone tuner — tune 20 servos directly, ±1° fine-tune, one-click read. Matches factory 1.0.0.4 layout (1-20: shoulder/elbow/hip/knee/ankle/hand/head). Long-press ±1 repeats, copy menu disabled." },
  servo_tuner_standby:   { zh: "準備",               en: "standby" },
  servo_tuner_write_all:{ zh: "全部寫入",           en: "Write All" },
  servo_tuner_angle:     { zh: "角度",               en: "Angle" },
  servo_tuner_offset:    { zh: "偏移",               en: "Offset" },
  servo_tuner_range:     { zh: "範圍",               en: "Range" },
  servo_tuner_disabled_hint: { zh: "開啟後才顯示 20ch 微調器（長按 ±1 連發，offset 即時計算，已禁用長按選單）", en: "Turn on to show 20ch tuner (long-press ±1 repeats, offset live, copy menu disabled)" },

  // -- speech tab --
  // 2026-08 新增: 對話界面 (全抄小智 tab 做法, 見 app-speech.js 個
  // appendSpeechChatLine()/sendSpeechChatText() 頂部 comment)。
  speech_chat_heading:        { zh: "💬 對話界面",   en: "💬 Conversation" },
  speech_chat_text_placeholder: { zh: "打字模擬 ASR 辨識結果…", en: "Type to simulate an ASR result…" },
  speech_chat_send_btn:       { zh: "送出",           en: "Send" },
  speech_chat_clear_btn:      { zh: "清空",           en: "Clear" },
  asr_heading:           { zh: "ASR (語音辨識)",   en: "ASR (Speech Recognition)" },
  engine_label:          { zh: "引擎：",           en: "Engine:" },
  asr_start_btn:         { zh: "開始聆聽",         en: "Start Listening" },
  asr_stop_btn:          { zh: "停止聆聽",         en: "Stop Listening" },
  tts_heading:           { zh: "語音 / TTS",       en: "Speech / TTS" },
  tts_text_placeholder:  { zh: "要說的文字",       en: "Text to speak" },
  tts_speak_btn:         { zh: "講嘢 (TTS)",       en: "Speak (TTS)" },
  tts_stop_btn:          { zh: "停止 TTS",         en: "Stop TTS" },
  mic_card_heading:      { zh: "🎙️ MIC 控制", en: "🎙️ Mic Control" },
  mic_release_btn:       { zh: "搶 MIC", en: "Grab Mic" },
  mic_return_btn:        { zh: "還 MIC", en: "Return Mic" },
  mic_state_on:          { zh: "App 持有中", en: "Held by app" },
  mic_state_off:         { zh: "系統持有", en: "Held by system" },
  mic_keep_held_label:   { zh: "持續搶 MIC", en: "Keep grabbing mic" },
  volume_heading:        { zh: "媒體音量",         en: "Media Volume" },
  volume_hint:           { zh: "控制機械人喇叭的媒體音量 (STREAM_MUSIC)，和實體 +/- 按鈕共用同一個音量。",
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
  asr_reset_btn_title:    { zh: "試驗性: 切換引擎後 TTS 失聲時試試這個, 看看能不能用回來, 不用重開機",
                             en: "Experimental: if TTS goes silent after switching engines, try this before rebooting" },
  asr_current_engine_unswitched: { zh: "目前引擎：未切換", en: "Current engine: not switched" },
  asr_procedure_warning:  { zh: "⚠️ <b>要有反應必須照這個順序：</b>\n        (0) 按「iFlytek」或「Nuance」——會立刻重新綁定 speech service, 等\n        speech_ready event 回來 (見上面「🔀 ASR 引擎已切換去」log) 才算完成;\n        (1) 按「開始聆聽」——這個只是將 speech engine 撥入接收 wake word 的狀態,\n        不是立刻開始錄音;\n        (2) 對著機械人說 wake word（實測確認：機身 config 寫死 <code>CN_WAKEUP_NIHAO_ALPHA</code>\n        = <b>「你好，Alpha」</b>，不可以自訂/不可以跳過）觸發硬體 mic array 偵測——這個 wake word\n        偵測本身一直用 iFlytek 的硬體 CAE 引擎，不受這裡選 Nuance/iFlytek 影響;\n        (3) 偵測到之後才真正開始錄音辨識, 這時候才可以說指令。\n        <br>\n        ⚠️ <b>2026-08 logcat 覆核發現（推翻上面舊結論）：</b>\n        機身韌體自己 (<code>AlphaMainSeviceImpl</code>) 開機時已經自己 bind 了一份獨立的\n        speech service, wake word 偵測 + TTS 提示全部走這條獨立路徑，<b>完全不受這裡\n        「切引擎」這個 app 側 API 影響</b>——也就是說這個開關只是改你自己的 app 主動\n        call `speech/tts` 或者 `speech/start_asr` 那條路徑，不會讓你聽到的 wake word\n        回答變聲、變語言。想改開機 wake word 用哪種語言，要用下面「⚙️ 喚醒詞語言設定」\n        個 preset (需要重開機)。\n        <br>\n        ⚠️ <b>Confidence 門檻</b>（反編譯 <code>Alpha2Services-v1.1.7.3.20</code> 證實）：\n        自由辨識（wake word 之後那段，不是下面的語法式辨識）從頭到尾都走 <b>Nuance</b>\n        （<code>NuanceASRImpl</code>）。\n        Local recognition confidence 要 ≥4500 先會直接接受；低於此分數會嘗試等雲端補完，\n        但 Nuance 雲端伺服器已停用，所以低分結果實際上會全部失敗。<b>站近機器、慢慢說、\n        咬字清楚</b>可以提高 confidence。已確認用英文完整短句可以（例如 \"wave the left hand\"，\n        不要只說單字），見下面已知指令參考。",
                             en: "⚠️ <b>For a response you must follow this exact sequence:</b>\n        (0) Press \u201ciFlytek\u201d or \u201cNuance\u201d — this immediately re-binds the speech\n        service; wait for the speech_ready event (see the \u201c🔀 ASR engine switched to\u201d log above) before\n        continuing;\n        (1) Press \u201cStart Listening\u201d — this only puts the speech engine into wake-word-receiving\n        state, it does not start recording immediately;\n        (2) Say the wake word to the robot (confirmed by testing: the on-device config hardcodes\n        <code>CN_WAKEUP_NIHAO_ALPHA</code>\n        = <b>\u201c你好，Alpha\u201d (\u201cHello, Alpha\u201d)</b>, which cannot be customized or skipped) to trigger the hardware mic array\n        detection — this wake-word detection always runs on iFlytek's hardware CAE engine, regardless of the\n        Nuance/iFlytek choice here;\n        (3) Only after detection does it actually start recording/recognition — this is when you can speak a command.\n        <br>\n        ⚠️ <b>2026-08 logcat review finding (overturns the conclusion above):</b>\n        The robot's own firmware (<code>AlphaMainSeviceImpl</code>) already binds its own separate\n        speech service at boot; wake-word detection + TTS prompts all run through that independent path,\n        <b>completely unaffected by this app-side \u201cswitch engine\u201d API</b> — meaning this button only\n        changes the path your own app actively calls via `speech/tts` or `speech/start_asr`; it will not\n        change the voice or language of the wake-word response you hear. To change which language the\n        boot-time wake word uses, use the preset in \u201c⚙️ Wake Word Language Settings\u201d below (requires a reboot).\n        <br>\n        ⚠️ <b>Confidence threshold</b> (confirmed by decompiling <code>Alpha2Services-v1.1.7.3.20</code>):\n        free recognition (the part after the wake word, not the grammar-based recognition below) runs\n        entirely on <b>Nuance</b> (<code>NuanceASRImpl</code>).\n        Local recognition confidence needs to be ≥4500 to be accepted directly; below that it tries to wait\n        for cloud completion, but the Nuance cloud server is permanently offline, so low-confidence results\n        effectively all fail. <b>Standing close to the robot, speaking slowly, and enunciating clearly</b> can\n        raise the confidence score. Confirmed to work with full English sentences (e.g. \"wave the left hand\",\n        not single words) — see the known-command reference below." },
  asr_result_label:       { zh: "📝 辨識結果", en: "📝 Recognition Result" },
  asr_known_commands_summary: { zh: "📖 已知內建指令參考（Nuance offline grammar，在 Nuance binding 之下使用）",
                                 en: "📖 Known Built-in Command Reference (Nuance offline grammar, used under Nuance binding)" },
  asr_known_commands_intro: { zh: "機身有兩個 speech engine：<b>Nuance VoCon</b>（offline，內建在\n          <code>alpha2services</code>）同 <b>iFlytek</b>。經反編譯 <code>Alpha2Services-v1.1.7.3.20</code>\n          證實：Nuance 這邊 <code>speech_initGrammar</code>／<code>startSpeechGrammar</code>\n          是完全未實作的空 stub（method body 只有一句 <code>return-void</code>），自訂詞彙一律不會生效；\n          但這邊有一個獨立、寫死在 native code 的內建 grammar（下面這個表），單靠 local recognition\n          就會 work，不用雲端（Nuance 雲端伺服器已停用，但這個內建 grammar 不靠它）。\n          單字容易 mis-parse（例如只說\"wave\"可能會 match 錯成 QA），建議用完整短句\n          （例如「wave your left hand」）。\n          <br><br>\n          ⚠️ <b>Confidence 門檻</b>：反編譯證實 local recognition 的 confidence 分數要 ≥4500\n          才會直接接受（不用等雲端）；低於這個門檻會判 invalid、等雲端補完——但雲端已停用，\n          所以低於 4500 分的結果實際上會全部有去無回。想提高成功率：<b>站近機器、慢慢說、\n          咬字清楚、減少背景噪音</b>，這些都會直接影響 confidence 分數。\n          <br><br>\n          想試 iFlytek（中文，有真身 grammar 實作，未反查完整語法格式）：先用 ASR card 的\n          「iFlytek」切換引擎再實際說話試試。",
                                 en: "The robot has two speech engines: <b>Nuance VoCon</b> (offline, built into\n          <code>alpha2services</code>) and <b>iFlytek</b>. Confirmed by decompiling <code>Alpha2Services-v1.1.7.3.20</code>:\n          on the Nuance side, <code>speech_initGrammar</code>/<code>startSpeechGrammar</code>\n          is a completely unimplemented empty stub (method body is just <code>return-void</code>), so custom vocabulary\n          never takes effect; but there's a separate built-in grammar hardcoded in native code (the table below) that\n          works purely via local recognition without needing the cloud (the Nuance cloud server is offline, but this\n          built-in grammar doesn't depend on it).\n          Single words are prone to mis-parsing (e.g. saying just \"wave\" might mis-match as QA); full sentences are\n          recommended (e.g. \u201cwave your left hand\u201d).\n          <br><br>\n          ⚠️ <b>Confidence threshold</b>: decompiling confirms local recognition's confidence score needs to be ≥4500\n          to be accepted directly (without waiting for the cloud); below this threshold it's judged invalid and waits\n          for cloud completion — but the cloud is offline, so results below 4500 effectively go nowhere. To improve\n          success rate: <b>stand close to the robot, speak slowly, enunciate clearly, and reduce background\n          noise</b> — these all directly affect the confidence score.\n          <br><br>\n          To try iFlytek (Chinese, has a real grammar implementation, exact grammar format not fully reverse-engineered):\n          switch engines using \u201ciFlytek\u201d on the ASR card, then just speak to the robot." },
  asr_cmd_action_heading: { zh: "Action_Performance（動作，機械人會自己動，可能搶了你自訂的action）：",
                             en: "Action_Performance (actions — the robot moves on its own, may override your custom actions):" },
  asr_cmd_qa_heading:     { zh: "QA（問答，機械人只會用嘴巴回答，不會動——最保險測試這組）：",
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

  // -- Alpha2 speech tab (TTS engine/voice buttons) --
  tts_engine_iflytek_btn: { zh: "iFlytek (訊飛)", en: "iFlytek" },
  tts_engine_android_btn: { zh: "Android 預設", en: "Android Default" },
  tts_voice_label:        { zh: "聲音：", en: "Voice:" },
  tts_android_engine_label: { zh: "TTS 引擎：", en: "TTS Engine:" },
  tts_android_lang_label: { zh: "語言：", en: "Language:" },
  tts_android_lang_keep_option: { zh: "（沿用引擎目前語言）", en: "(Keep engine's current language)" },
  tts_voice_default_btn:  { zh: "南南", en: "Nannan (default)" },

  // -- Alpha2 speech tab (on-device language settings) --
  service_config_heading: { zh: "⚙️ 離線對話設定", en: "⚙️ Offline Dialogue Settings" },
  service_config_cn_btn:  { zh: "🇨🇳 中文備份", en: "🇨🇳 Chinese Backup" },
  service_config_en_btn:  { zh: "🇺🇸 英文備份", en: "🇺🇸 English Backup" },
  service_config_cn_oa2_offline_btn: { zh: "🧪 中文喚醒", en: "🧪 Chinese Wake" },
  service_config_en_oa2_offline_btn: { zh: "🧪 英文喚醒", en: "🧪 English Wake" },
  service_config_reboot_btn: { zh: "🔁 重開機", en: "🔁 Reboot" },

  // -- Alpha2 speech tab (ASR engine switch buttons + dynamic status strings) --
  asr_switch_zh_btn:      { zh: "iFlytek", en: "iFlytek" },
  asr_switch_en_btn:      { zh: "Nuance", en: "Nuance" },
  asr_switching_to:       { zh: "切緊去 {label} 引擎 (重新綁定 speech service 中)…",
                             en: "Switching to {label} engine (re-binding speech service)…" },
  asr_current_engine_switching: { zh: "目前引擎：切換中… ({label})", en: "Current engine: switching… ({label})" },
  asr_switch_failed_prefix: { zh: "切換引擎失敗: ", en: "Failed to switch engine: " },
  asr_current_engine_switch_failed: { zh: "目前引擎：切換失敗", en: "Current engine: switch failed" },
  asr_reset_failed_unknown: { zh: "未知錯誤", en: "Unknown error" },

  // -- Alpha2 speech tab (對話界面 speech/iflytek_simulate 動態字串) --
  speech_chat_simulate_error_prefix: { zh: "配對失敗：", en: "Match failed: " },
  speech_chat_simulate_no_match: { zh: "（1000 條問法裡面找不到對應，沒有回應）",
                                    en: "(No match found among the 1000 phrases — no response)" },
  speech_chat_simulate_action_prefix: { zh: "已觸發動作 ", en: "Triggered action " },

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
  asr_engine_ready_hint:  { zh: "引擎已就緒，可以撳「開始聆聽」", en: "Engine ready — you can press \u201cStart Listening\u201d" },
  asr_current_engine_prefix: { zh: "目前引擎：", en: "Current engine: " },
  asr_current_engine_is:    { zh: "目前引擎：{label}", en: "Current engine: {label}" },
  log_error_code_prefix:  { zh: "錯誤 (code=", en: "Error (code=" },

  // -- offline grammar (離線文法辨識, iFlytek local BNF) --
  offline_grammar_heading:    { zh: "📴 離線文法辨識 (iFlytek 本地)", en: "📴 Offline Grammar ASR (iFlytek local)" },
  offline_grammar_hint:       { zh: "用機身內建離線資源辨識 BNF 文法裡面的指令，不用上網。流程：切去 iFlytek 引擎 → 初始化文法 → 開始辨識 → 對機械人說文法裡面的句子。",
                                 en: "Recognise commands listed in the BNF grammar using the robot's built-in offline resources — no internet needed. Flow: switch to iFlytek → init grammar → start → speak a phrase from the grammar." },
  offline_grammar_load_default: { zh: "載入預設", en: "Load default" },
  offline_grammar_init_btn:   { zh: "初始化文法", en: "Init grammar" },
  offline_grammar_start_btn:  { zh: "開始辨識", en: "Start" },
  offline_grammar_stop_btn:   { zh: "停止", en: "Stop" },
  offline_grammar_loading:    { zh: "載入緊…", en: "Loading…" },
  offline_grammar_init_ok:    { zh: "✅ 文法已送出，等機身構建回執 (grammar_init)…", en: "✅ Grammar submitted — waiting for build callback (grammar_init)…" },
  offline_grammar_init_fail:  { zh: "❌ 初始化失敗：", en: "❌ Init failed: " },
  offline_grammar_start_ok:   { zh: "✅ 離線辨識模式開啟了，說文法裡面的句子吧。", en: "✅ Offline recognition active — speak a phrase from the grammar." },
  offline_grammar_stop_ok:    { zh: "⏹️ 已停止離線辨識。", en: "⏹️ Offline recognition stopped." },
  offline_grammar_auto_label: { zh: "🌐 自動跟網路切換（沒有網路＝離線文法、有網路＝雲端聽寫）", en: "🌐 Auto-switch with network (offline = grammar, online = cloud dictation)" },
  offline_mode_on:            { zh: "📴 沒有網路 - 已自動轉為離線文法模式（只認得文法裡面的指令）", en: "📴 Offline - switched to local grammar mode (grammar commands only)" },
  offline_mode_off:           { zh: "🌐 有網絡 - 已自動轉返雲端聽寫模式（自由講嘢）", en: "🌐 Online - switched back to cloud dictation (free-form speech)" },
  asr_mode_label_offline:     { zh: "離線文法 (iFlytek local)", en: "Offline grammar (iFlytek local)" },
  asr_mode_label_online:      { zh: "雲端聽寫 (iFlytek cloud)", en: "Cloud dictation (iFlytek cloud)" },

  // -- xiaozhi (小智 AI 對話) --
  nav_xiaozhi:                { zh: "🤖 小智",              en: "🤖 XiaoZhi" },
  xiaozhi_heading:            { zh: "🤖 小智 AI 對話",       en: "🤖 XiaoZhi AI Chat" },
  xiaozhi_phase4_hint:        { zh: "開關開＝配對／連線／隨時語音對話，關＝斷開。首次要在 xiaozhi.me 輸入機械人讀出的配對碼。",
                                 en: "Switch on = pair/connect/voice chat anytime, off = disconnect. First time, enter the pairing code the robot speaks at xiaozhi.me." },
  xiaozhi_unsupported_hint:   { zh: "這台機器的 Android 版本過舊，語音對話功能將會停用（純文字對話不受影響）。",
                                 en: "This device's Android version is too old for voice chat - it will be disabled (text-only chat is unaffected)." },
  xiaozhi_activation_prompt:  { zh: "請在 <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a> 輸入以下配對碼：",
                                 en: "Please enter the pairing code below at <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a>:" },
  xiaozhi_activation_code_chat_prefix: { zh: "🔑 配對碼：", en: "🔑 Pairing code:" },
  xiaozhi_activation_modal_prompt: { zh: "請在 <a href=\"https://xiaozhi.me/console/\" target=\"_blank\" rel=\"noopener\">xiaozhi.me</a> 輸入以下配對碼：",
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
  xiaozhi_ota_custom_hint:    { zh: "只有 OTA 位址是必填；其餘留空會自動取回來，連接期間不可以更改。",
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
  xiaozhi_mcp_tools_hint:     { zh: "控制這台機器向小智暴露哪些工具。和 xiaozhi.me console 的「MCP接入點」是兩回事。",
                                 en: "Control which tools this device exposes to XiaoZhi. Not the same as xiaozhi.me console's \"MCP access point\"." },
  xiaozhi_mcp_tools_expand_label: { zh: "展開",              en: "Expand" },
  xiaozhi_mcp_tools_refresh:  { zh: "重新整理",              en: "Refresh" },
  xiaozhi_mcp_tools_loading:  { zh: "載入中…",               en: "Loading…" },
  xiaozhi_mcp_tools_empty:    { zh: "未有可用工具",          en: "No tools available" },
  xiaozhi_mcp_tools_error:    { zh: "❌ 讀取失敗",              en: "❌ Failed to load" },
  xiaozhi_mcp_call_label:     { zh: "工具呼叫",             en: "Tool call" },
  xiaozhi_console_heading:    { zh: "🌐 小智控制台",          en: "🌐 XiaoZhi Console" },
  xiaozhi_console_open_btn:   { zh: "前往 xiaozhi.me",       en: "Open xiaozhi.me" },

  // -- music tab --
  nav_music:                   { zh: "🎵 音樂",                en: "🎵 Music" },
  music_heading:                { zh: "🎵 本地音樂",            en: "🎵 Local Music" },
  music_scan_hint:              { zh: "機身音樂資料夾: /mnt/internal_sd/music/",
                                   en: "Music folder on the robot: /mnt/internal_sd/music/" },
  music_drop_hint:               { zh: "📂 將音樂檔拖過來，或者按這裡選檔案",
                                    en: "📂 Drop music files here, or click to choose" },
  music_refresh_btn:            { zh: "🔄 重新整理清單",        en: "🔄 Refresh list" },
  music_list_empty:             { zh: "未搵到音樂檔案",         en: "No music files found" },
  music_list_loading:           { zh: "載入中…",               en: "Loading…" },
  music_now_playing_heading:    { zh: "播放中",                en: "Now Playing" },
  music_now_playing_none:       { zh: "未選擇歌曲",            en: "No track selected" },
  music_stop_btn:               { zh: "⏹ 全部停止",            en: "⏹ Stop All" },
  music_random_btn:             { zh: "🔀 隨機",               en: "🔀 Random" },
  music_playall_btn:            { zh: "▶ 全部",                en: "▶ All" },
  music_play_btn_title:         { zh: "播放",                  en: "Play" },
  music_pause_btn_title:        { zh: "暫停",                  en: "Pause" },
  music_eq_heading:             { zh: "🎚️ 均衡器 (Equalizer)", en: "🎚️ Equalizer" },
  music_eq_hint:                { zh: "揀一個預設風格，落一首歌開始就會套用",
                                   en: "Pick a preset — applies from the next track you play" },
  music_eq_none:                { zh: "無 (Flat)",             en: "None (Flat)" },
  music_eq_unavailable:         { zh: "這台機器不支援 equalizer", en: "Equalizer not supported on this device" },
  music_filler_heading:         { zh: "💃 隨機動作",            en: "💃 Random Movement" },
  music_filler_hint:            { zh: "開啟的話，播歌期間機械人會不定時自己動，等這首歌播完才停",
                                   en: "When on, the robot moves on its own while music plays, and stops when the track ends" },
  music_filler_on:              { zh: "開",                    en: "On" },
  music_filler_off:              { zh: "關",                    en: "Off" },
  music_upload_uploading:       { zh: "上載緊…",               en: "Uploading…" },
  music_upload_done:            { zh: "上載完成",              en: "Upload complete" },
  music_upload_failed:          { zh: "上載失敗",              en: "Upload failed" },
  // -- radio (radio-browser.info) --
  radio_heading:                { zh: "📻 網絡電台",           en: "📻 Internet Radio" },
  radio_hint:                   { zh: "由 radio-browser.info 提供全球公開電台，輸入關鍵字即搜即播（例如 BBC、Jazz、香港）",
                                   en: "Powered by radio-browser.info — search any keyword and play worldwide stations instantly (e.g. BBC, Jazz, Hong Kong)" },
  radio_search_placeholder:     { zh: "搜尋電台名稱、國家、標籤…", en: "Search station name, country or tag…" },
  radio_search_btn:             { zh: "🔍 搜尋",               en: "🔍 Search" },
  radio_stop_btn:               { zh: "⏹ 停止電台",            en: "⏹ Stop Radio" },
  radio_search_empty_hint:      { zh: "請先輸入關鍵字",         en: "Enter a keyword first" },
  radio_search_loading:         { zh: "搜尋中…",               en: "Searching…" },
  radio_search_no_result:       { zh: "未搵到相關電台，試下其他關鍵字", en: "No stations found — try another keyword" },
  radio_search_found_prefix:    { zh: "搵到 ",                  en: "Found " },
  radio_search_found_suffix:    { zh: " 個電台，點擊即播",      en: " stations — tap to play" },
  radio_list_empty_hint:        { zh: "輸入關鍵字後按搜尋",      en: "Enter a keyword and hit Search" },
  radio_now_playing_label:      { zh: "正在播放：",             en: "Now Playing:" },
  radio_now_playing_none:       { zh: "未播放電台",             en: "No radio playing" },
  radio_playing_prefix:         { zh: "正在連接：",             en: "Connecting: " },
  radio_play_ok_prefix:         { zh: "已開始播放：",           en: "Playing: " },
  radio_stopped:                { zh: "已停止電台",             en: "Radio stopped" },
  radio_status_refresh_btn:     { zh: "🔄 重新整理狀態",        en: "🔄 Refresh Status" },
  xiaozhi_text_placeholder:   { zh: "打字同小智傾偈…",       en: "Type a message to XiaoZhi…" },
  xiaozhi_send_text:          { zh: "送出",                 en: "Send" },
  xiaozhi_clear_btn:          { zh: "清空",                 en: "Clear" },
  xiaozhi_send_text_error:    { zh: "送出失敗",             en: "Failed to send" },
  xiaozhi_stop_all_btn:       { zh: "⏹ 全部停止",           en: "⏹ Stop All" },
  xiaozhi_tts_engine_label:      { zh: "語音輸出：",         en: "Voice output:" },
  xiaozhi_tts_engine_xiaozhi_btn: { zh: "小智",              en: "XiaoZhi" },

  // -- advanced tab --
  nav_advanced:              { zh: "🧪 實驗",              en: "🧪 Experimental" },
  advanced_uuid_heading:     { zh: "🤖 機械人 ID 變更器",   en: "🤖 Robot ID Changer" },
  uuid_card_disabled_hint:   { zh: "開啟先會顯示機械人 ID 變更功能", en: "Turn on to show the robot ID changer" },
  uuid_preview_hint_short:   { zh: "預覽新 QR（未寫入 EEPROM）",
                                en: "Previewing new QR (not yet written to EEPROM)" },
  uuid_preview_invalid:      { zh: "❌ 格式不正確（1-31 字元，英數/-/_）", en: "❌ Invalid format (1-31 chars, alnum/-/_)" },
  advanced_reboot_btn:       { zh: "🔁 立即重開機", en: "🔁 Reboot now" },

  // -- chest firmware --
  chest_title:           { zh: "胸板固件",         en: "Chest Firmware" },
  chest_hint_disabled:   { zh: "开启后显示胸板固件版本与升级", en: "Enable to show chest firmware version and upgrade" },
  chest_current_version: { zh: "当前版本：",       en: "Current version:" },
  chest_btn_current:     { zh: "当前版本",         en: "Current Version" },
  chest_hint_upload:     { zh: "选择 256KB 的 ALPHA2Q-CHEST-*.bin，先上传到 /sdcard/AlphaII_CHEST_kernel.bin，再按升级：", en: "Select 256KB ALPHA2Q-CHEST-*.bin, upload to /sdcard/AlphaII_CHEST_kernel.bin, then upgrade:" },
  chest_btn_upload:      { zh: "上传",             en: "Upload" },
  chest_btn_upgrade:     { zh: "开始升级",         en: "Start Upgrade" },

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
  // 進階 Tuner 同步重建以更新每格中英對照（範圍/角度/偏移）
  try {
    const advEnabled = document.getElementById("advTunerEnabled");
    if (advEnabled && advEnabled.checked && typeof buildAdvTuner === "function") {
      const scrollPos = document.getElementById("advServoTunerGrid").scrollTop;
      buildAdvTuner();
      // 保留捲動位置
      document.getElementById("advServoTunerGrid").scrollTop = scrollPos;
    }
  } catch(e) {}
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
  // 進階 Tuner 同步中英對照
  document.querySelectorAll(".servo-tuner-cell").forEach(function (cell) {
    const label = cell.querySelector("div");
    if (!label) return;
    const m = /^#(\d+)/.exec(label.textContent);
    if (!m) return;
    const id = parseInt(m[1], 10);
    label.textContent = "#" + id + " " + servoNameOf(id);
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

