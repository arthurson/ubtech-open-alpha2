// Open Alpha2 — client logic (app-core.js)
// 內容: 全局狀態、UI 語言字典、servo 校準表、api()/hwApi() 呢啲所有其他 app-*.js 都要用嘅核心 helper。呢個檔案要第一個 load。
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
// see displayNameOf() below). There's only ever one language switch
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
  nav_speech:             { zh: "🗣️ 語音",          en: "🗣️ Speech" },
  nav_led:                { zh: "💡 LED",           en: "💡 LED" },
  nav_camera:             { zh: "📷 相機",          en: "📷 Camera" },
  nav_blockly:            { zh: "🧩 積木編程 ↗",   en: "🧩 Blockly ↗" },
  nav_blockly_title:      { zh: "在新分頁開啟 Blockly 積木編程", en: "Open Blockly visual programming in a new tab" },

  // -- status tab --
  lang_switch_label:     { zh: "語言：", en: "Language:" },
  device_info_heading:   { zh: "裝置資訊",        en: "Device Info" },
  device_battery:        { zh: "🔋 電池",         en: "🔋 Battery" },
  device_wifi:           { zh: "📶 WiFi",         en: "📶 WiFi" },
  device_bluetooth:      { zh: "🔷 藍牙",         en: "🔷 Bluetooth" },
  device_uuid:           { zh: "🤖 機械人 UUID",  en: "🤖 Robot UUID" },
  device_chest_fw:       { zh: "🧠 胸板固件",    en: "🧠 Chest FW" },
  uuid_query_btn:        { zh: "查詢",            en: "Query" },
  uuid_random_btn:       { zh: "🎲 隨機碼",       en: "🎲 Random" },
  sonar_heading:         { zh: "聲納",            en: "Sonar" },
  accel_heading:         { zh: "加速度計",        en: "Accelerometer" },
  tilt_led_toggle_label: { zh: "4角度傾側點亮頭/眼LED", en: "4-direction tilt lights up head/eye LED" },
  tilt_led_direction_prefix: { zh: "目前傾側方向: ", en: "Current tilt direction: " },
  tilt_led_monitoring_hint: { zh: "監測中… (需先開啟上面的「加速度計」開關才會收到讀數)",
                               en: "Monitoring… (turn on the \u201cAccelerometer\u201d switch above first to receive readings)" },
  accel_turning_on_hint: { zh: "開啟中…", en: "Turning on…" },
  accel_turn_on_failed_hint: { zh: "開啟失敗", en: "Failed to turn on" },
  accel_move_hint:       { zh: "移動／傾斜機身查看數據變化", en: "Move / tilt the robot to see the readings change" },
  uuid_querying_hint:    { zh: "查詢中…", en: "Querying…" },
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
  uuid_write_restart_hint:{ zh: "已寫入 EEPROM，請手動重開機後再查詢，新 ID 先會生效",
                           en: "Written to EEPROM — reboot manually, then query again to see it" },
  uuid_manual_reboot_hint:{ zh: "寫入後請手動重開機（長按機身電源鍵關機再開），新 ID 先會生效。App 沒有系統權限自動重開機。",
                           en: "After writing, reboot manually (long-press the power button) for the new ID to take effect. The app has no system permission to reboot itself." },

  // -- actions tab --
  actions_heading:       { zh: "動作 (Actions)",  en: "Actions" },
  actions_load_btn:      { zh: "取得動作列表",      en: "Load Action List" },
  action_name_placeholder:{ zh: "動作名稱 e.g. ACT0", en: "Action name e.g. ACT0" },
  action_play_btn:       { zh: "播放",            en: "Play" },
  action_speed_label:    { zh: "變速",            en: "Speed" },        
  action_stop_btn:       { zh: "停止",            en: "Stop" },

  // -- servo tab --
  servo_heading:         { zh: "舵機 (Servos, 1–20)", en: "Servos (1–20)" },
  servo_hint:            { zh: "拖動滑桿, 放手即送出, 自動限制在安全範圍內。", en: "Drag a slider and release to send — values are auto-clamped to a safe range." },
  servo_time_label:      { zh: "時間(ms)：",       en: "Time (ms):" },
  servo_reset_btn:       { zh: "全部回到原位",     en: "Reset All to Home" },
  servo_read_all_btn:    { zh: "📖 讀取全部",       en: "📖 Read all" },
  servo_read_hint:       { zh: "讀指定一顆或一次過讀全部舵機即時角度（讀完即寫回上力），顯示在對應行右邊；讀不到顯示 -。全部約 6 秒，讀取時請固定好機械人。",
                           en: "Reads one or all live servo angles (written straight back to restore torque) into their rows; unreadable joints show -. All takes ~6s; keep the robot secured." },
  servo_reading_hint:    { zh: "讀取中…",             en: "Reading…" },
  servo_read_done:       { zh: "✅ 已更新",            en: "✅ Updated" },
  servo_read_failed_prefix: { zh: "❌ 讀取失敗：",     en: "❌ Read failed: " },
  servo_tuner_heading:   { zh: "🔧 舵機角度調整", en: "🔧 Servo Angle Tuner" },
  servo_tuner_hint:      { zh: "獨立於舵機分頁的進階微調器 — 直接修改 20 顆 servo 角度，±1° 精調，一鍵讀取全部目前角度。與原廠 1.0.0.4 校準工具相同佈局（1-20 對應肩/肘/髖/膝/踝/手/頭）。長按 ±1 連發時已禁用系統複製選單。", en: "Standalone tuner — tune 20 servos directly, ±1° fine-tune, one-click read. Matches factory 1.0.0.4 layout (1-20: shoulder/elbow/hip/knee/ankle/hand/head). Long-press ±1 repeats, copy menu disabled." },
  servo_tuner_standby:   { zh: "復位+掃描",         en: "reset+scan" },
  servo_tuner_calibrate: { zh: "校准",               en: "Calibrate" },
  servo_tuner_angle:     { zh: "角度",               en: "Angle" },
  servo_tuner_offset:    { zh: "偏移",               en: "Offset" },
  servo_tuner_range:     { zh: "範圍",               en: "Range" },
  servo_tuner_disabled_hint: { zh: "開啟後才顯示 20ch 微調器（長按 ±1 連發，offset 即時計算，已禁用長按選單）", en: "Turn on to show 20ch tuner (long-press ±1 repeats, offset live, copy menu disabled)" },
  servo_tuner_backup:        { zh: "備份 offset",       en: "Backup offsets" },
  servo_tuner_restore:       { zh: "還原 offset",       en: "Restore offsets" },
  servo_tuner_backup_done:   { zh: "已備份 offset",       en: "Backed up offsets" },
  servo_tuner_restore_done:  { zh: "已還原 offset 備份", en: "Offsets restored" },
  servo_tuner_backup_need_scan: { zh: "備份前自動掃描 offset（否則沒有結果）…", en: "Auto-scanning offsets before backup…" },
  servo_tuner_restore_fail:  { zh: "還原失敗",           en: "Restore failed" },
  servo_tuner_restore_empty: { zh: "：無有效數據",       en: ": no valid data" },
  servo_tuner_restore_readerr: { zh: "：讀檔失敗",       en: ": file read error" },
  servo_tuner_restore_detail: { zh: "（offset {off}；逐一在輸入格按 Enter 才會送到舵機）", en: "({off} offsets; press Enter per row to send to servos)" },
  servo_tuner_backup_fail:   { zh: "備份失敗",           en: "Backup failed" },
  servo_tuner_unscanned:     { zh: "（{n}/20 未讀）…",   en: "({n}/20 unread)…" },

  // adv tuner 動態狀態字。
  servo_tuner_ready:         { zh: "tuner {v} 就緒（加減/輸入僅變動角度；校准先成組寫 EEPROM）", en: "tuner {v} ready (+/- and input move only; Calibrate writes EEPROM)" },
  servo_tuner_read_all:      { zh: "一鍵實際讀取全部 trim 1-20（約幾秒）…", en: "Reading all 20 trims (a few seconds)…" },
  servo_tuner_read_done:     { zh: "實際讀取完成 {ok}/20",   en: "Read done {ok}/20" },
  servo_tuner_read_fails:    { zh: "，無回授：{ids}",   en: ", no feedback: {ids}" },
  servo_tuner_read_fail:     { zh: "#{id} 讀失敗",       en: "#{id} read failed" },
  servo_tuner_read_err:      { zh: "讀取錯誤：{e}",      en: "Read error: {e}" },
  servo_tuner_nudge_ok:      { zh: "微調 #{id} → {v}（trim 未儲存，要儲存請按「校准」）", en: "Tuned #{id} → {v} (trim not saved — press Calibrate)" },
  servo_tuner_nudge_fail:    { zh: "微調 #{id} 失敗：{e}", en: "Tune #{id} failed: {e}" },
  servo_tuner_nudge_err:     { zh: "微調 #{id} 錯誤：{e}", en: "Tune #{id} error: {e}" },
  servo_tuner_cal_none:      { zh: "校准：無有效 trim（先復位+掃描）", en: "Calibrate: no valid trims (reset+scan first)" },
  servo_tuner_cal_progress:  { zh: "校准中 {i}/{n}（#{id} {v}）…", en: "Calibrating {i}/{n} (#{id} {v})…" },
  servo_tuner_cal_done:      { zh: "校准完成 {ok}/{n}",  en: "Calibrated {ok}/{n}" },
  servo_tuner_cal_unknown:   { zh: "，未確認：{ids}（重新掃描驗證）", en: ", unconfirmed: {ids} (rescan to verify)" },
  servo_tuner_cal_failed:    { zh: "，失敗：{ids}",     en: ", failed: {ids}" },
  servo_tuner_cal_skipped:   { zh: "（跳過未掃描：{ids}）", en: "(skipped unscanned: {ids})" },
  servo_tuner_reset_sending: { zh: "送復位姿勢中…",      en: "Sending reset pose…" },
  servo_tuner_reset_fail:    { zh: "復位失敗：{e}",      en: "Reset failed: {e}" },
  servo_tuner_reset_done:    { zh: "已復位，1.5 秒後重新掃描 trim…", en: "Reset done, rescanning trims in 1.5s…" },
  servo_tuner_no_feedback:   { zh: "讀失敗",             en: "no feedback" },
  servo_tuner_live_suffix:   { zh: " (即時)",            en: " (live)" },
  servo_tuner_err_nofeedback: { zh: "無回授（超時或壞舵機）", en: "no feedback (timeout or faulty servo)" },

  // -- speech tab --
  // 對話界面 (全抄小智 tab 做法, 見 app-speech.js 個
  // appendSpeechChatLine()/sendSpeechChatText() 頂部 comment)。
  speech_chat_heading:        { zh: "💬 對話界面",   en: "💬 Conversation" },
  speech_chat_text_placeholder: { zh: "打字模擬 ASR 辨識結果…", en: "Type to simulate an ASR result…" },
  speech_chat_send_btn:       { zh: "送出",           en: "Send" },
  speech_chat_clear_btn:      { zh: "清空",           en: "Clear" },
  // -- speech tab: Vosk ASR --
  vosk_heading:             { zh: "🎙️ 語音辨識 (Vosk 離線)", en: "🎙️ Speech Recognition (Vosk offline)" },
  vosk_hint:               { zh: "模型放 sdcard 頂層（如 vosk-model-small-cn-0.22），開啟頁面自動偵測，不跟 App。載入需幾秒＋約 200MB 記憶體，一次一粒。",
                             en: "Put models at sdcard top level (e.g. vosk-model-small-cn-0.22), auto-detected on page load, not bundled. Loading takes seconds + ~200MB RAM, one at a time." },
  vosk_model_label:        { zh: "模型：", en: "Model:" },
  vosk_no_model_hint:      { zh: "❌ 在 sdcard 找不到 Vosk 模型（頂層目錄要有 am/final.mdl 或 final.mdl）", en: "❌ No Vosk model found on sdcard (top-level dir must contain am/final.mdl or final.mdl)" },
  vosk_loading_hint:       { zh: "載入中…", en: "Loading…" },
  vosk_switching_hint:     { zh: "切換中（停舊開新）…", en: "Switching (stopping old, starting new)…" },
  vosk_load_fail_prefix:   { zh: "❌ 載入失敗：", en: "❌ Load failed: " },
  vosk_start_fail_prefix:  { zh: "❌ 開始失敗：", en: "❌ Start failed: " },
  // 語音頁狀態行跟面板語言（voskRenderStatus 用，唔識嘅 state 跌返原文）。
  vosk_state_idle:         { zh: "閒置", en: "Idle" },
  vosk_state_loading:      { zh: "載入中", en: "Loading" },
  vosk_state_ready:        { zh: "就緒", en: "Ready" },
  vosk_state_listening:    { zh: "聆聽中", en: "Listening" },
  vosk_state_error:        { zh: "錯誤", en: "Error" },
  vosk_dl_heading:         { zh: "⬇ Vosk 模型下載", en: "⬇ Vosk Model Download" },
  vosk_dl_disabled_hint:   { zh: "開啟後才會顯示可下載模型", en: "Turn on to show downloadable models" },
  vosk_dl_hint:            { zh: "點擊下載官方模型並自動解壓到 sdcard（完成自動載入，語音頁即多一個按鍵）。下面分有對話／無對話兩區。",
                             en: "Tap to download an official model and auto-extract it to sdcard (auto-loads when done, a new button appears in the Speech tab). Split into dialogue / no-dialogue below." },
  vosk_dl_supported_heading: { zh: "✓ 有對話（有 matcher，可對答）", en: "✓ Dialogue-ready (has matcher)" },
  vosk_dl_unsupported_heading: { zh: "無對話（只聽寫）", en: "No dialogue (transcribe only)" },
  vosk_dl_transcribe_only: { zh: "只聽寫", en: "transcribe only" },
  vosk_dl_none_hint:       { zh: "尚未下載任何模型", en: "No models downloaded yet" },
  vosk_dl_all_done:        { zh: "全部已下載，去語音頁選用", en: "All downloaded — pick one in the Speech tab" },
  vosk_download_cancel_btn: { zh: "取消下載", en: "Cancel download" },
  vosk_downloading_prefix: { zh: "下載中", en: "Downloading" },
  vosk_unzipping_hint:     { zh: "解壓中…", en: "Extracting…" },
  vosk_download_done:      { zh: "✅ 下載＋解壓完成，已自動載入", en: "✅ Downloaded + extracted, auto-loaded" },
  vosk_download_fail_prefix: { zh: "❌ 下載失敗：", en: "❌ Download failed: " },
  vosk_download_cancelled: { zh: "已取消下載", en: "Download cancelled" },
  tts_heading:           { zh: "語音 / TTS",       en: "Speech / TTS" },
  tts_text_placeholder:  { zh: "要說的文字",       en: "Text to speak" },
  tts_speak_btn:         { zh: "說話 (TTS)",       en: "Speak (TTS)" },
  tts_stop_btn:          { zh: "停止 TTS",         en: "Stop TTS" },
  volume_hint:           { zh: "控制機械人喇叭的媒體音量 (STREAM_MUSIC)，和實體 +/- 按鈕共用同一個音量。",
                            en: "Controls the robot speaker's media volume (STREAM_MUSIC) — shares the same level as the physical +/- buttons." },

  // -- LED tab (Alpha2) --
  led_head_heading:      { zh: "頭部 LED",         en: "Head LED" },
  led_eye_heading:       { zh: "眼睛 LED",         en: "Eye LED" },
  led_mouth_heading:     { zh: "嘴部 LED",         en: "Mouth LED" },
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
  // 真機已確認 PIR 觸發正常。
  alpha2_pir_heading:       { zh: "PIR 感應器", en: "PIR Sensor" },
  alpha2_pir_switch_label:  { zh: "感應器開關", en: "Sensor Switch" },
  alpha2_pir_alert_label:   { zh: "警示反應 (LED+鈴聲)", en: "Alert Reaction (LED + Chime)" },

  // -- Alpha2 speech tab (TTS engine/voice buttons) --
  // tts_engine_android_btn 保留 (小智頁 Android 掣仲用緊)。
  tts_engine_android_btn: { zh: "Android 預設", en: "Android Default" },
  // tts_engine_*_btn 保留 (小智 tab 仲用緊)。
  tts_android_engine_label: { zh: "TTS 引擎：", en: "TTS Engine:" },
  tts_android_lang_label: { zh: "語言：", en: "Language:" },
  tts_android_lang_keep_option: { zh: "（沿用引擎目前語言）", en: "(Keep engine's current language)" },
  // Google TTS 每個語言多把聲，揀完語言再揀聲（無具體語言就成行收埋）。
  // 顯示跟足 Google 系統設定：「語音 I、II、III…」編號（英文面板就 "Voice I…"）。
  tts_android_voice_label: { zh: "聲音：", en: "Voice:" },
  tts_android_voice_name: { zh: "語音", en: "Voice" },
  tts_android_voice_keep_option: { zh: "（預設聲）", en: "(Default voice)" },
  tts_android_voice_loading: { zh: "載入聲音中…", en: "Loading voices…" },

  // reboot_confirm/rebooting/
  // reboot_ok/reboot_failed_prefix/suffix 保留 (app-accel.js UUID 卡個獨立
  // 重開機掣仲用緊)。

  // asr_reset_failed_unknown 保留
  // (app-accel.js/app-speech.js 仲用緊做通用「未知錯誤」)。
  asr_reset_failed_unknown: { zh: "未知錯誤", en: "Unknown error" },

  // -- Alpha2 speech tab (對話界面 speech/semantic_simulate 動態字串) --
  speech_chat_simulate_error_prefix: { zh: "配對失敗：", en: "Match failed: " },
  speech_chat_simulate_no_match: { zh: "（1000 條問法裡面找不到對應，沒有回應）",
                                    en: "(No match found among the 1000 phrases — no response)" },
  speech_chat_simulate_action_prefix: { zh: "已觸發動作 ", en: "Triggered action " },

  // -- Alpha2 speech tab (service config + 3-in-1 test dynamic strings) --
  speech_test_enter_text_alert: { zh: "請輸入文字", en: "Please enter some text" },

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
  xiaozhi_activation_modal_dismiss: { zh: "知道了", en: "Got it" },
  xiaozhi_session_toggle_label: { zh: "🤖 小智（開＝連線並隨時語音對話，關＝斷開）", en: "🤖 XiaoZhi (on = connect & voice chat anytime, off = disconnect)" },
  // -- 開機語音模式三選一（實驗 tab 卡，後端 boot_voice/get|set） --
  boot_voice_heading:        { zh: "🔌 開機語音模式", en: "🔌 Voice mode on boot" },
  boot_voice_disabled_hint:  { zh: "開啟後才會顯示開機語音設定", en: "Turn on to show the boot voice settings" },
  boot_voice_hint:           { zh: "請選擇其中一個：會在下次開啟 App 時自動執行。現在選擇只會儲存設定，不會立刻連線或立刻開始聆聽。",
                               en: "Pick one: runs automatically next time the app starts. Saving only — won't connect or listen right now." },
  boot_voice_opt_off:        { zh: "💤 全部手動", en: "💤 Start nothing automatically" },
  boot_voice_opt_off_desc:   { zh: "開啟 App 後不自動執行任何項目，需要時再手動開啟。", en: "Do nothing on boot; start things manually when needed." },
  boot_voice_opt_xiaozhi:    { zh: "🤖 開機自動連接小智", en: "🤖 Auto-connect XiaoZhi on boot" },
  boot_voice_opt_xiaozhi_desc: { zh: "開啟 App 約 15 秒後自動連接小智，可直接對話（需要網路連線，且已完成配對）。",
                                 en: "Auto-connects XiaoZhi ~15s after app start, ready to chat (needs internet + pairing)." },
  boot_voice_opt_vosk:       { zh: "🗣️ 開機自動啟用 Vosk", en: "🗣️ Auto-start Vosk on boot" },
  boot_voice_opt_vosk_desc:  { zh: "開啟 App 並載入模型後，自動開始離線聆聽，不需要網路（須先在語音頁面載入模型）。",
                               en: "Auto-starts offline listening once the model loads, no internet needed (load a model in the Speech tab first)." },
  boot_voice_saved:          { zh: "✅ 已儲存，將在下次開啟 App 時生效", en: "✅ Saved, takes effect on next app start" },
  boot_voice_failed_prefix:  { zh: "❌ 儲存失敗：", en: "❌ Failed to save: " },
  xiaozhi_status_disconnected:{ zh: "未連接",               en: "Disconnected" },
  xiaozhi_status_checking:    { zh: "檢查中…",              en: "Checking…" },
  xiaozhi_status_awaiting_code: { zh: "等待配對…",          en: "Awaiting pairing…" },
  xiaozhi_status_connecting:  { zh: "連接中…",              en: "Connecting…" },
  xiaozhi_status_connected:   { zh: "已連接",               en: "Connected" },
  xiaozhi_status_error:       { zh: "連接失敗",             en: "Connection failed" },
  xiaozhi_mic_held:           { zh: "🎤 麥克風：已取得（語音對話中）", en: "🎤 Mic: acquired (voice chat active)" },
  xiaozhi_mic_released:       { zh: "🎤 麥克風：已釋放",            en: "🎤 Mic: released" },
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
  music_list_empty:             { zh: "未找到音樂檔案",         en: "No music files found" },
  music_list_loading:           { zh: "載入中…",               en: "Loading…" },
  music_now_playing_heading:    { zh: "播放中",                en: "Now Playing" },
  music_now_playing_none:       { zh: "未選擇歌曲",            en: "No track selected" },
  music_stop_btn:               { zh: "⏹ 全部停止",            en: "⏹ Stop All" },
  music_random_btn:             { zh: "🔀 隨機",               en: "🔀 Random" },
  music_playall_btn:            { zh: "▶ 全部",                en: "▶ All" },
  music_play_btn_title:         { zh: "播放",                  en: "Play" },
  music_pause_btn_title:        { zh: "暫停",                  en: "Pause" },
  music_filler_heading:         { zh: "💃 隨機動作",            en: "💃 Random Movement" },
  music_filler_on:              { zh: "開",                    en: "On" },
  music_filler_off:              { zh: "關",                    en: "Off" },
  music_upload_uploading:       { zh: "上載中…",               en: "Uploading…" },
  music_upload_done:            { zh: "上載完成",              en: "Upload complete" },
  music_upload_failed:          { zh: "上載失敗",              en: "Upload failed" },
  // -- radio (radio-browser.info) --
  radio_heading:                { zh: "📻 網絡電台",           en: "📻 Internet Radio" },
  radio_browser_hint:          { zh: "全球公開電台：搜尋即列出 50 個隨機結果，點擊名稱即播", en: "Worldwide public stations: search lists 50 random results, tap a name to play" },
  radio_search_placeholder:     { zh: "搜尋電台名稱、國家、標籤…", en: "Search station name, country or tag…" },
  radio_search_btn:             { zh: "🔍 搜尋",               en: "🔍 Search" },
  radio_search_empty_hint:      { zh: "請先輸入關鍵字",         en: "Enter a keyword first" },
  radio_search_loading:         { zh: "搜尋中…",               en: "Searching…" },
  radio_search_no_result:       { zh: "未找到相關電台，試試其他關鍵字", en: "No stations found — try another keyword" },
  radio_search_found_prefix:    { zh: "找到 ",                  en: "Found " },
  radio_search_found_suffix:    { zh: " 個電台，點擊即播",      en: " stations — tap to play" },
  radio_list_empty_hint:        { zh: "輸入關鍵字後按搜尋",      en: "Enter a keyword and hit Search" },
  radio_now_playing_none:       { zh: "未播放電台",             en: "No radio playing" },
  radio_playing_prefix:         { zh: "正在連接：",             en: "Connecting: " },
  radio_play_ok_prefix:         { zh: "已開始播放：",           en: "Playing: " },
  radio_stopped:                { zh: "已停止電台",             en: "Radio stopped" },
  arc_heading:                 { zh: "🌐 網上點歌",             en: "🌐 Pick Online Music" },
  arc_hint:                    { zh: "Internet Archive 免費音樂（現場／獨立／舊錄音），無需登入；搜尋經你的瀏覽器，播放經機械人。", en: "Free music from Internet Archive (live/indie/vintage), no login; search runs in your browser, playback on the robot." },
  arc_search_placeholder:      { zh: "搜尋歌名、歌手、樂隊…",    en: "Search song, artist or band…" },
  arc_search_btn:              { zh: "🔍 搜尋",                en: "🔍 Search" },
  arc_search_no_result:        { zh: "未找到，試試其他關鍵字",    en: "Nothing found — try another keyword" },
  arc_search_found_suffix:     { zh: " 張專輯，點擊進入選歌",        en: " albums — tap one to pick a track" },
  arc_no_mp3:                  { zh: "此專輯無 MP3，請選擇另一張",   en: "No MP3 in this album — pick another" },
  arc_play_fail_prefix:        { zh: "❌ 播放失敗（機械人可能無法上網）：", en: "❌ Play failed (robot may be offline): " },
  xiaozhi_text_placeholder:   { zh: "打字與小智聊天…",       en: "Type a message to XiaoZhi…" },
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

  // -- chest firmware --
  chest_title:           { zh: "胸板固件",         en: "Chest Firmware" },
  chest_hint_disabled:   { zh: "開啟後顯示胸板固件版本與升級", en: "Enable to show chest firmware version and upgrade" },
  chest_current_version: { zh: "當前版本：",       en: "Current version:" },
  chest_btn_current:     { zh: "當前版本",         en: "Current Version" },
  chest_hint_upload:     { zh: "選擇 256KB 的 ALPHA2Q-CHEST-*.bin，先上傳到 /sdcard/AlphaII_CHEST_kernel.bin，再按升級：", en: "Select 256KB ALPHA2Q-CHEST-*.bin, upload to /sdcard/AlphaII_CHEST_kernel.bin, then upgrade:" },
  chest_btn_upload:      { zh: "上傳",             en: "Upload" },
  chest_btn_upgrade:     { zh: "開始升級",         en: "Start Upgrade" },

  // -- 實驗 tab 面板 token 認證（見 PanelAuth.java，後端 system/auth/*） --
  panel_auth_heading:    { zh: "🔐 面板認證",      en: "🔐 Panel Token" },
  panel_auth_card_disabled_hint: { zh: "開啟先會顯示面板認證設定", en: "Turn on to show panel token settings" },
  panel_auth_hint:       { zh: "啟用後，整個面板（全部 API＋上載，含其他分頁）要帶 token。預設關閉（全開）。未啟用時輸入新值按儲存即啟用；已啟用時輸入現有值按儲存即解鎖（如需更換新值，先清除再儲存）。Token 記在瀏覽器，同一個面板地址跨 tab 共用。",
                           en: "When enabled, the whole panel (all APIs + uploads, incl. other tabs) needs the token. Off by default (fully open). Type a new value + Save to enable; type the current value + Save to unlock (to change it, Clear first then Save). Stored in this browser, shared across tabs of the same panel URL." },
  panel_auth_token_placeholder: { zh: "Token（8–64 字元英數/-/_）", en: "Token (8–64 chars A-Za-z0-9/-/_)" },
  panel_auth_set_btn:    { zh: "儲存",             en: "Save" },
  panel_auth_clear_btn:  { zh: "清除",             en: "Clear" },
  panel_auth_disabled:   { zh: "未啟用（同網段任何人可直接操作）", en: "Disabled (anyone on the LAN can operate directly)" },
  panel_auth_enabled_locked: { zh: "已啟用，未解鎖（面板操作會回 401）", en: "Enabled, locked (panel ops return 401)" },
  panel_auth_enabled_unlocked: { zh: "已啟用，已解鎖（成個面板會自動帶 token）", en: "Enabled, unlocked (whole panel auto-attaches the token)" },
  panel_auth_need_token: { zh: "請先輸入 Token",   en: "Enter a token first" },
  panel_auth_set_ok:     { zh: "✅ 已儲存（已記住，成個面板可直接用）", en: "✅ Saved (remembered, whole panel now works)" },
  panel_auth_clear_ok:   { zh: "✅ 已清除（認證已關閉）", en: "✅ Cleared (auth disabled)" },
  panel_auth_failed_prefix: { zh: "❌ 失敗：",     en: "❌ Failed: " },
  panel_lock_fail:       { zh: "❌ Token 不正確，請再試", en: "❌ Wrong token, try again" },
  panel_pw_show:         { zh: "顯示輸入",         en: "Show input" },
  panel_pw_hide:         { zh: "隱藏輸入",         en: "Hide input" },

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
function t(key, params) {
  const entry = I18N[key];
  // 缺 key 即 warn。
  if (!entry) {
    if (typeof console !== "undefined" && console.warn) console.warn("i18n missing: " + key);
    return key;
  }
  let s = entry[uiLang] || entry.zh;
  // {name} 參數替換（舊調用 t(key) 不受影響）。
  if (params) {
    for (const k in params) {
      s = s.split("{" + k + "}").join(String(params[k]));
    }
  }
  return s;
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
  // Vosk 模型鍵＋下載鍵＋兩條狀態行都係動態起（名跟 uiLang），重畫一次就轉埋語言。
  try {
    if (typeof voskApplyUiLanguage === "function") voskApplyUiLanguage();
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
// currentBackend 保留：app-camera.js/app-mic.js 有 "if (currentBackend !== 'alpha2') return;" guard，留常數等佢哋照樣 work。
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
// Home-point values confirmed directly against the robot by the user.
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
  // 鎖屏中：後面 init 嘅 API 401 係預期之內，唔洗版（浮層已經講明要解鎖）。
  if (typeof window !== "undefined" && window.__panelLocked) return;
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
  const merged = withPanelToken(params);
  const qs = merged ? "?" + new URLSearchParams(merged).toString() : "";
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

// Namespaced fetch helpers - 同 api() 一樣形狀, 淨係前綴唔同, 對應
// MainActivity 三個獨立路由 (見開頭 dispatch): sysApi() -> /api/system/*
// (handleSystemApi), directApi() -> /api/direct/* (handleDirectApi)。
// xiaozhiApi() 住喺 app-xiaozhi.js (佢要跟小智 UI 狀態, 唔放呢度)。
// api-client.js (Alpha2Api.*) 會按 OpenAPI path 自動揀啱嘅一個, 直接用
// api('system/...') 會打去 /api/alpha2/system/... 而 404, 唔好咁做。
function sysApi(path, params) {
  clearError();
  const merged = withPanelToken(params);
  const qs = merged ? "?" + new URLSearchParams(merged).toString() : "";
  return fetch(API + "system/" + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /system/" + path, new Error(json.error || json.code || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError("Network error calling /system/" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

function directApi(path, params) {
  clearError();
  const merged = withPanelToken(params);
  const qs = merged ? "?" + new URLSearchParams(merged).toString() : "";
  return fetch(API + "direct/" + path + qs).then(function (res) {
    return res.json().catch(function (e) {
      return { ok: false, error: "invalid response (status " + res.status + ")" };
    }).then(function (json) {
      if (!json.ok) {
        showError("API /direct/" + path, new Error(json.error || json.code || "request failed"));
      }
      return json;
    });
  }).catch(function (networkErr) {
    showError("Network error calling /direct/" + path, networkErr);
    return { ok: false, error: String(networkErr) };
  });
}

// ---------------- Panel token（實驗 tab 認證，見 PanelAuth.java） ----------------
//
// 範圍：opt-in，預設關＝全開。啟用後成個面板上鎖（全部 /api/*＋/upload/* 要帶
// token；淨 auth/*＋靜態頁＋ws/stream 開放），其他 tab／Blockly 照跟同一粒
// token（下面自動帶）。token 放 localStorage（同一個 browser＋同一個面板地址
// 跨 tab 共用，閂 browser 都仲記得；要忘記就撳「清除」）。
// 傳遞 key 用 panel_token（唔用 token：xiaozhi ota_config/set 個 token 係另一樣嘢，
// 同名會撞；受保護 endpoint 另收 token 別名方便 curl，見 PanelAuth）。
function panelTokenGet() {
  try { return localStorage.getItem("panel_token") || ""; } catch (e) { return ""; }
}

function panelTokenSet(t) {
  try {
    if (t) localStorage.setItem("panel_token", t);
    else localStorage.removeItem("panel_token");
  } catch (e) { /* private mode 等寫唔入就當無記住，唔阻操作 */ }
}

/** 將記住嘅 token 混入 params（無 token 即原樣；已有 panel_token 唔覆寫）。 */
function withPanelToken(params) {
  const tok = panelTokenGet();
  if (!tok) return params;
  const out = {};
  if (params) { for (const k in params) { out[k] = params[k]; } }
  if (out.panel_token == null) out.panel_token = tok;
  return out;
}

// ---------------- 認證卡開關（同 UUID 卡 uuidCardToggle 一致寫法） ----------------
// 預設收埋詳情，用戶揭開先睇到／用到；唔記狀態，每次入頁預設關。
function panelAuthCardToggle() {
  const enabled = document.getElementById("panelAuthCardEnabled");
  const body = document.getElementById("panelAuthCardBody");
  const hint = document.getElementById("panelAuthDisabledHint");
  const on = !!(enabled && enabled.checked);
  if (body) body.style.display = on ? "block" : "none";
  if (hint) hint.style.display = on ? "none" : "block";
}

function panelAuthElements() {
  return {
    status: document.getElementById("panelAuthStatus"),
    input: document.getElementById("panelAuthInput"),
    msg: document.getElementById("panelAuthMsg"),
  };
}

function panelAuthSay(key, extra) {
  const els = panelAuthElements();
  if (els.msg) els.msg.textContent = t(key) + (extra ? " " + extra : "");
}

/** Page load／操作後刷新狀態行（未啟用／已啟用未解鎖／已啟用已解鎖）。 */
function panelAuthRefreshStatus() {
  const els = panelAuthElements();
  if (!els.status) return;
  Alpha2Api.systemAuthStatus({}).then(function (res) {
    if (!res || !res.ok) return;
    if (!res.enabled) {
      els.status.textContent = t("panel_auth_disabled");
      return;
    }
    const has = panelTokenGet();
    if (!has) {
      els.status.textContent = t("panel_auth_enabled_locked");
      return;
    }
    // 有記住 token 都要 verify（可能喺另一 tab 清咗／改咗）。
    Alpha2Api.systemAuthVerify({}).then(function (v) {
      els.status.textContent = t(v && v.ok && v.valid
        ? "panel_auth_enabled_unlocked" : "panel_auth_enabled_locked");
    });
  });
}

/** 儲存（單一輸入框，一兼三職）。
 * 送 token（新值）＋current（舊值證明，同一個值）：未啟用→後端忽略 current，
 * 直接啟用；已啟用＋打啱現有值→後端當無改（set 同值）回 ok，前端記住＝解鎖；
 * 已啟用＋打錯→401。要換新值就先清除再儲存。成功即記住（localStorage）。 */
function panelAuthSet() {
  const els = panelAuthElements();
  const token = els.input ? (els.input.value || "").trim() : "";
  if (!token) { panelAuthSay("panel_auth_need_token"); return; }
  Alpha2Api.systemAuthSet({ token: token, current: token }).then(function (res) {
    if (res && res.ok) {
      panelTokenSet(token);
      panelAuthSay("panel_auth_set_ok");
    } else {
      panelAuthSay("panel_auth_failed_prefix", res && res.error ? res.error : "?");
    }
    panelAuthRefreshStatus();
  });
}

/** 清除（停用）。單一輸入框：打現有值證明擁有權（防同網段人亂清）。 */
function panelAuthClear() {
  const els = panelAuthElements();
  const current = els.input ? (els.input.value || "").trim() : "";
  Alpha2Api.systemAuthClear(current ? { current: current } : {}).then(function (res) {
    if (res && res.ok) {
      panelTokenSet(null);
      if (els.input) els.input.value = "";
      panelAuthSay("panel_auth_clear_ok");
    } else {
      panelAuthSay("panel_auth_failed_prefix", res && res.error ? res.error : "?");
    }
    panelAuthRefreshStatus();
  });
}

/** 中英雙語（鎖屏浮層＋眼仔 title 用：語言掣收埋喺面版後面，唔知睇緊邊種文，兩種一次過 show）。 */
function tBoth(key) {
  const entry = (typeof I18N !== "undefined" && I18N[key]) || null;
  if (!entry) return key;
  if (!entry.en || entry.en === entry.zh) return entry.zh;
  return entry.zh + " / " + entry.en;
}

/** 密碼框「眼仔」開關：撳一下睇到打緊咩，再撳收返（token 輸入框用，見 index.html）。 */
function togglePwVisibility(inputId, btn) {
  const el = document.getElementById(inputId);
  if (!el) return;
  const show = el.type !== "text";
  el.type = show ? "text" : "password";
  if (btn) {
    btn.textContent = show ? "🙈" : "👁️";
    btn.title = tBoth(show ? "panel_pw_hide" : "panel_pw_show");
  }
}

// ---------------- 面板鎖屏浮層（見 index.html #panelLockOverlay） ----------------
// auth 啟用＋記住嘅 token 驗唔過→開浮層蓋住成個面版唔 show 內容。浮層解鎖同儲存
// 同一個語義（token＋current 同值），得即記住＋reload，成頁用正常流程重行。
// 注意：HTML／JS 靜態檔本身擋唔住下載（瀏覽器要載入先行到），真正敏感數據靠後端
// 全面板閘口（未解鎖 API 一律 401）；浮層只係唔 show 操作面。
function panelLockCheck() {
  if (typeof Alpha2Api === "undefined" || !Alpha2Api.systemAuthStatus) return;
  Alpha2Api.systemAuthStatus({}).then(function (res) {
    if (!res || !res.ok || !res.enabled) return;  // 停用＝全開，乜都唔做
    const open = function () {
      window.__panelLocked = true;  // 壓住 showError：後面 init 嘅 401 唔洗版
      const ov = document.getElementById("panelLockOverlay");
      if (ov && ov.classList) ov.classList.add("open");
    };
    if (!panelTokenGet()) { open(); return; }  // 記住都無，直接鎖
    // 有記住都要 verify（可能喺另一 tab 清咗／改咗）。
    Alpha2Api.systemAuthVerify({}).then(function (v) {
      if (!(v && v.ok && v.valid)) open();
    });
  });
}

/** 浮層解鎖掣（＋Enter）：得即記住＋reload；唔得留喺鎖屏 show 錯。 */
function panelLockUnlock() {
  const input = document.getElementById("panelLockInput");
  const msg = document.getElementById("panelLockMsg");
  const say = function (key, extra) {
    if (msg) msg.textContent = tBoth(key) + (extra ? " " + extra : "");
  };
  const token = input ? (input.value || "").trim() : "";
  if (!token) { say("panel_auth_need_token"); return; }
  Alpha2Api.systemAuthSet({ token: token, current: token }).then(function (res) {
    if (res && res.ok) {
      panelTokenSet(token);
      location.reload();
    } else {
      say("panel_lock_fail");
    }
  });
}

