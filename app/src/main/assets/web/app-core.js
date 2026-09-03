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
  // 2026-09: servo_power_save 已移除 (開關 + endpoint 一齊拎走)。
  servo_tuner_heading:   { zh: "🔧 舵機角度調整", en: "🔧 Servo Angle Tuner" },
  servo_tuner_hint:      { zh: "獨立於舵機分頁的進階微調器 — 直接修改 20 顆 servo 角度，±1° 精調，一鍵讀取全部目前角度。與原廠 1.0.0.4 校準工具相同佈局（1-20 對應肩/肘/髖/膝/踝/手/頭）。長按 ±1 連發時已禁用系統複製選單。", en: "Standalone tuner — tune 20 servos directly, ±1° fine-tune, one-click read. Matches factory 1.0.0.4 layout (1-20: shoulder/elbow/hip/knee/ankle/hand/head). Long-press ±1 repeats, copy menu disabled." },
  servo_tuner_standby:   { zh: "準備",               en: "standby" },
  servo_tuner_write_all:{ zh: "全部寫入",           en: "Write All" },
  servo_tuner_angle:     { zh: "角度",               en: "Angle" },
  servo_tuner_offset:    { zh: "偏移",               en: "Offset" },
  servo_tuner_range:     { zh: "範圍",               en: "Range" },
  servo_tuner_disabled_hint: { zh: "開啟後才顯示 20ch 微調器（長按 ±1 連發，offset 即時計算，已禁用長按選單）", en: "Turn on to show 20ch tuner (long-press ±1 repeats, offset live, copy menu disabled)" },
  servo_tuner_backup:        { zh: "備份 offset",       en: "Backup offsets" },
  servo_tuner_restore:       { zh: "還原 offset",       en: "Restore offsets" },
  servo_tuner_backup_done:   { zh: "已備份 offset + 角度", en: "Backed up offsets + angles" },
  servo_tuner_restore_done:  { zh: "已還原 offset 備份", en: "Offsets restored" },
  servo_tuner_scan:          { zh: "掃描 offset",        en: "Scan offsets" },
  servo_tuner_scan_done:     { zh: "掃描完成",           en: "Scan done" },
  servo_tuner_backup_need_scan: { zh: "備份前自動掃描 offset（否則交白卷）…", en: "Auto-scanning offsets before backup…" },
  servo_tuner_restore_fail:  { zh: "還原失敗",           en: "Restore failed" },

  // -- speech tab --
  // 2026-08 新增: 對話界面 (全抄小智 tab 做法, 見 app-speech.js 個
  // appendSpeechChatLine()/sendSpeechChatText() 頂部 comment)。
  speech_chat_heading:        { zh: "💬 對話界面",   en: "💬 Conversation" },
  speech_chat_text_placeholder: { zh: "打字模擬 ASR 辨識結果…", en: "Type to simulate an ASR result…" },
  speech_chat_send_btn:       { zh: "送出",           en: "Send" },
  speech_chat_clear_btn:      { zh: "清空",           en: "Clear" },
  // 2026-09: asr_heading/asr_start_btn/asr_stop_btn/engine_label 已移除 -
  // ASR 卡拎走 (見 index.html), 無其他引用。
  tts_heading:           { zh: "語音 / TTS",       en: "Speech / TTS" },
  tts_text_placeholder:  { zh: "要說的文字",       en: "Text to speak" },
  tts_speak_btn:         { zh: "講嘢 (TTS)",       en: "Speak (TTS)" },
  tts_stop_btn:          { zh: "停止 TTS",         en: "Stop TTS" },
  // 2026-09 移除: MIC 卡成組 i18n (卡已拎走, 見 index.html)。
  // (原 mic_card_heading/mic_release_btn/mic_return_btn/
  // mic_state_on/mic_state_off/mic_keep_held_label)
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

  // 2026-09 移除: Alpha2 speech tab (ASR card) 成組 - 卡已拎走, 無其他引用。
  // (原 asr_reset_btn/asr_procedure_warning/asr_result_label/
  // asr_known_commands_*/asr_cmd_*/asr_current_engine_unswitched)

  // -- Alpha2 speech tab (TTS engine/voice buttons) --
  // 2026-09: tts_engine_iflytek_btn 已移除 (小智頁 iFlytek 掣一齊拎走);
  // tts_engine_android_btn 保留 (小智頁 Android 掣仲用緊)。
  tts_engine_android_btn: { zh: "Android 預設", en: "Android Default" },
  // 2026-09: tts_voice_label/tts_voice_default_btn 已移除 (iFlytek 聲音揀擇
  // 唔存在); tts_engine_*_btn 保留 (小智 tab 仲用緊)。
  tts_android_engine_label: { zh: "TTS 引擎：", en: "TTS Engine:" },
  tts_android_lang_label: { zh: "語言：", en: "Language:" },
  tts_android_lang_keep_option: { zh: "（沿用引擎目前語言）", en: "(Keep engine's current language)" },

  // 2026-09 移除: 離線對話設定卡 i18n (卡已拎走)。reboot_confirm/rebooting/
  // reboot_ok/reboot_failed_prefix/suffix 保留 (app-accel.js UUID 卡個獨立
  // 重開機掣仲用緊); service_config_reboot_btn (卡上面粒掣個 label) 已移除。

  // 2026-09 移除: ASR 引擎切換掣 + 狀態字串 (卡已拎走)。asr_reset_failed_unknown
  // 保留 (app-accel.js/app-speech.js 仲用緊做通用「未知錯誤」)。
  asr_reset_failed_unknown: { zh: "未知錯誤", en: "Unknown error" },

  // -- Alpha2 speech tab (對話界面 speech/iflytek_simulate 動態字串) --
  speech_chat_simulate_error_prefix: { zh: "配對失敗：", en: "Match failed: " },
  speech_chat_simulate_no_match: { zh: "（1000 條問法裡面找不到對應，沒有回應）",
                                    en: "(No match found among the 1000 phrases — no response)" },
  speech_chat_simulate_action_prefix: { zh: "已觸發動作 ", en: "Triggered action " },

  // -- Alpha2 speech tab (service config + 3-in-1 test dynamic strings) --
  // 2026-09 移除: service_config_writing/write_ok/write_failed_prefix
  // (preset 掣已拎走); 下面 reboot_* 保留 (UUID 卡用緊)。
  service_config_reboot_confirm: { zh: "確定要立即重開機？", en: "Reboot now?" },
  service_config_rebooting: { zh: "重開機緊…", en: "Rebooting…" },
  service_config_reboot_ok: { zh: "✅ 重開機緊…", en: "✅ Rebooting…" },
  service_config_reboot_failed_prefix: { zh: "❌ 重開機失敗：", en: "❌ Reboot failed: " },
  service_config_reboot_failed_suffix: { zh: "（請手動 power-cycle）", en: " (please power-cycle manually)" },
  speech_test_enter_text_alert: { zh: "請輸入文字", en: "Please enter some text" },
  // 2026-09 移除: asr_engine_ready_hint/asr_current_engine_prefix/
  // asr_current_engine_is (ASR 卡同 speech_ready handler 一齊拎走)。
  log_error_code_prefix:  { zh: "錯誤 (code=", en: "Error (code=" },

  // 2026-09 移除: offline grammar 成組 (卡已拎走, 見 index.html)。
  // (原 offline_grammar_heading/hint/load_default/init_btn/start_btn/stop_btn/
  // loading/init_ok/init_fail/start_ok/stop_ok/auto_label + offline_mode_on/off
  // + asr_mode_label_offline/online)

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

