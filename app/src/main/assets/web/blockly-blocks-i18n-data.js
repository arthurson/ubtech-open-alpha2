// Open Alpha2 — Custom block (blockly-blocks.js) + toolbox (blockly-toolbox.js)
// 全部中文 label/tooltip 嘅英文對照表。
//
// 呢個檔案要喺 blockly-blocks.js / blockly-toolbox.js 之前 load (見 blockly.html)。
// window.t(key) 由 blockly-i18n.js 提供, 讀 window.getUiLanguage() 揀返 zh/en
// 嗰個版本, 冇對應 key 就 fallback 做 zh (再冇就直接印返個 key, 方便發現漏譯)。
//
// Key 命名規則: <block_type_或功能>__<field_或用途>, 全部小寫+底線, 純粹方便
// grep, 冇特別語意編碼。

window.ALPHA_BLOCK_I18N = {
  // -- alpha_action_play --
  action_play__label:        { zh: '播放動作', en: 'Play action' },
  wait_done__label:          { zh: '等待完成', en: 'Wait for completion' },
  wait_done__yes:            { zh: '✅ 等 (播完先做下一個, 建議)', en: '✅ Wait (recommended)' },
  wait_done__no:             { zh: '⚡ 唔等 (即刻做下一個)', en: '⚡ Don\u2019t wait (continue immediately)' },
  timeout_seconds__label:    { zh: '逾時(秒)', en: 'Timeout (s)' },
  action_play__tooltip:      { zh: '播放一個內建動作 (/api/action/play?name=...)。名稱可以直接打動作 id, 或者用下面「播放內建動作」block 揀。預設會等機械人回報呢個動作真係播完先行落去下一粒 block, 避免兩個動作撞埋一齊播。',
                                en: 'Play a built-in action (/api/action/play?name=...). You can type the action id directly, or use the "Play built-in action" blocks below to pick one. By default it waits for the robot to confirm the action has finished before moving to the next block, to avoid two actions overlapping.' },

  // -- makeActionCategoryBlock --
  action_category__prefix:   { zh: '▶ 播放', en: '▶ Play ' },
  action_category__subcat:   { zh: '子分類', en: 'Subcategory' },
  action_category__name:     { zh: '動作', en: 'Action' },
  action_category_tooltip:   { zh: '由機械人韌體隨附嘅「{cat}」類動作揀一個播放 (跟返實測 preset 分類, 見 blockly-actions-data.js)。清單內嵌喺呢個網頁入面, 唔使連機都睇到曬選項。',
                                en: 'Pick and play one of the built-in "{cat}" actions from the robot firmware (matches the real preset categories, see blockly-actions-data.js). The list is embedded in this page, so you can browse it without being connected to the robot.' },
  action_cat_basic:          { zh: '基本', en: 'Basic' },
  action_cat_dance:          { zh: '跳舞', en: 'Dance' },
  action_cat_story:          { zh: '故事', en: 'Story' },
  action_cat_yoga:           { zh: '瑜伽', en: 'Yoga' },
  action_cat_others:         { zh: '其他', en: 'Other' },
  action_no_items:           { zh: '(呢個分類冇動作)', en: '(no actions in this category)' },
  action_all_subcat:         { zh: '(全部)', en: '(all)' },

  // -- alpha_action_play_dropdown --
  action_play_live__label:   { zh: '播放動作 (即時清單)', en: 'Play action (live list)' },
  action_play_live__not_loaded: { zh: '(未載入 - 先按「攞動作列表」)', en: '(not loaded — press "Load Action List" first)' },
  action_play_live__tooltip: { zh: '由機械人「即時」回傳嘅動作清單揀一個播放 (即係向機械人實時查詢, 唔係用內嵌靜態清單) — 用嚟核對機身實際版本嘅動作清單同內嵌清單有冇出入。先要喺工具箱右上角按「攞動作列表」抓一次。',
                                en: 'Pick and play an action from the list fetched "live" from the robot (a real-time query, not the embedded static list) — useful for checking whether the robot\u2019s actual action list differs from the embedded one. You need to press "Load Action List" in the toolbar first.' },

  // -- alpha_action_stop --
  action_stop__label:        { zh: '停止動作播放', en: 'Stop action playback' },
  action_stop__tooltip:      { zh: '停止目前正在播放嘅動作 (/api/action/stop)。', en: 'Stop the action currently playing (/api/action/stop).' },

  // -- alpha_action_wait_done --
  action_wait_done__prefix:  { zh: '額外等待：目前動作播放完畢 (最多', en: 'Extra wait: until current action finishes (max' },
  action_wait_done__suffix:  { zh: '秒)', en: 's)' },
  action_wait_done__tooltip: { zh: '一般唔需要用呢粒 —— 「播放動作」block 已經內建咗「等待完成」選項。呢粒係俾特殊情況用: 例如用「播放動作(即時清單)」之後想額外多等一次, 或者透過序列埠/第三方方式觸發咗動作、想喺 Blockly 度等佢播完。',
                                en: 'You usually don\u2019t need this — the "Play action" block already has a built-in "wait for completion" option. This is for special cases: e.g. waiting again after "Play action (live list)", or waiting in Blockly for an action that was triggered via the serial port or a third-party method.' },

  // -- alpha_speech_tts --
  speech_tts__label:         { zh: '講嘢 (TTS)', en: 'Speak (TTS)' },
  speech_tts__engine_nuance: { zh: 'Nuance (英文)', en: 'Nuance (English)' },
  speech_tts__engine_iflytek:{ zh: 'iFlytek 訊飛 (中文)', en: 'iFlytek (Chinese)' },
  speech_tts__engine_android:{ zh: 'Android 預設', en: 'Android default' },
  speech_tts__voice_label:   { zh: '聲音(淨iFlytek有效)', en: 'Voice (iFlytek only)' },
  speech_tts__voice_default: { zh: '預設', en: 'Default' },
  speech_tts__tooltip:       { zh: '播放一段文字轉語音 (/api/speech/tts)。引擎其實由機身韌體決定實際用邊個, 呢度嘅選擇主要影響語言／聲音提示。',
                                en: 'Speak a piece of text via TTS (/api/speech/tts). The firmware actually decides which engine is used — this choice mainly hints the language/voice.' },

  // -- alpha_speech_stop --
  speech_stop__label:        { zh: '停止 TTS 播放', en: 'Stop TTS playback' },

  // -- alpha_speech_set_mic --
  speech_set_mic__label:     { zh: '麥克風擁有權：', en: 'Mic ownership:' },
  speech_set_mic__release:   { zh: '釋放俾機械人 (機械人可以自己聽)', en: 'Release to robot (robot can listen itself)' },
  speech_set_mic__take:      { zh: 'App 攞返 (機械人唔會聽)', en: 'App takes it back (robot won\u2019t listen)' },
  speech_set_mic__tooltip:   { zh: '⚠️ 呢個唔係「開始聆聽」！淨係轉手 mic 擁有權, 唔會觸發辨識, 亦唔會主動開始聽。想即刻開始聽用「開始聆聽 (即時辨識)」嗰粒 block。(/api/speech/set_mic)',
                                en: '⚠️ This is NOT "start listening"! It only hands over mic ownership — it does not trigger recognition or start listening. To start listening immediately, use the "Start listening (live recognition)" block. (/api/speech/set_mic)' },

  // -- alpha_speech_start_asr --
  speech_start_asr__label:   { zh: '開始聆聽 (即時辨識, 唔使等 wake word)', en: 'Start listening (live recognition, no wake word needed)' },
  speech_start_asr__tooltip: { zh: '直接開始 ASR 辨識, 唔使等機械人硬件偵測到 wake word。結果會經「當收到 語音辨識結果」事件送返嚟。(/api/speech/start_asr)',
                                en: 'Start ASR recognition directly, without waiting for the robot\u2019s hardware to detect a wake word. Results arrive via the "on speech recognition result" event. (/api/speech/start_asr)' },

  // -- alpha_speech_set_voice --
  speech_set_voice__label:   { zh: '設定 TTS 聲音', en: 'Set TTS voice' },
  speech_set_voice__tooltip: { zh: '設定 TTS 聲音, 淨係 iFlytek 命名聲音先有效。(/api/speech/set_voice)', en: 'Set the TTS voice. Only takes effect for named iFlytek voices. (/api/speech/set_voice)' },

  // -- alpha_speech_set_language --
  speech_set_lang__label:    { zh: '設定辨識語言', en: 'Set recognition language' },
  speech_set_lang__zh:       { zh: '中文 zh_cn (iFlytek)', en: 'Chinese zh_cn (iFlytek)' },
  speech_set_lang__en:       { zh: '英文 en_us (Nuance)', en: 'English en_us (Nuance)' },

  // -- alpha_speech_self_interrupt --
  speech_self_interrupt__label: { zh: '自我打斷 (中文限定)：', en: 'Self-interrupt (Chinese only):' },
  toggle_on:                 { zh: '開啟', en: 'On' },
  toggle_off:                { zh: '關閉', en: 'Off' },
  speech_self_interrupt__tooltip: { zh: '開關「機械人講嘢中途畀人講嘢打斷」。(/api/speech/self_interrupt)', en: 'Toggle whether the robot can be interrupted mid-speech by a person talking. (/api/speech/self_interrupt)' },

  // -- makeRingtoneBlock --
  ringtone_phone__label:     { zh: '📞 播放電話鈴聲', en: '📞 Play phone ringtone' },
  ringtone_notification__label: { zh: '🔔 播放通知鈴聲', en: '🔔 Play notification sound' },
  ringtone__sound_label:     { zh: '聲音', en: 'Sound' },
  ringtone__not_loaded:      { zh: '(清單未載入)', en: '(list not loaded)' },
  ringtone__play_label:      { zh: '播放', en: 'Play for' },
  ringtone__seconds_suffix:  { zh: '秒 (0 = 播到完為止)', en: 's (0 = play to the end)' },
  ringtone__tooltip:         { zh: '播放 Android 系統內置嘅{type}鈴聲 (經機械人喇叭播出, 跟隨媒體音量)。到咗指定秒數會自動停止; 填 0 就播到成個音效檔案自然完為止。清單內嵌喺呢個網頁入面, 唔使連機都睇到曬選項。(/api/audio/ringtones/play_by_title)',
                                en: 'Play a built-in Android {type} sound (through the robot\u2019s speaker, follows media volume). Stops automatically after the given number of seconds; 0 plays the whole sound file to the end. The list is embedded in this page, so you can browse it without being connected. (/api/audio/ringtones/play_by_title)' },
  ringtone__type_notification: { zh: '通知', en: 'notification' },
  ringtone__type_phone:      { zh: '電話', en: 'phone' },

  // -- alpha_speech_ringtone_stop --
  ringtone_stop__label:      { zh: '⏹ 停止鈴聲播放', en: '⏹ Stop ringtone playback' },
  ringtone_stop__tooltip:    { zh: '停止依家播緊嘅系統鈴聲/通知聲 (電話鈴聲或通知鈴聲兩個 block 播嗰個)。(/api/audio/ringtones/stop)',
                                en: 'Stop whichever system ringtone/notification sound is currently playing (from either the phone or notification ringtone block). (/api/audio/ringtones/stop)' },

  // -- servo groups --
  servo_group_head:          { zh: '頭', en: 'Head' },
  servo_group_right_arm:     { zh: '右手', en: 'R Arm' },
  servo_group_left_arm:      { zh: '左手', en: 'L Arm' },
  servo_group_right_leg:     { zh: '右腳', en: 'R Leg' },
  servo_group_left_leg:      { zh: '左腳', en: 'L Leg' },
  servo_group__motor_suffix: { zh: '馬達', en: 'servo' },
  servo_group__angle_label:  { zh: '角度', en: 'Angle' },
  servo_group__time_label:   { zh: '時間(ms)', en: 'Time (ms)' },
  servo_group__tooltip:      { zh: '移動{group}嘅其中一粒伺服馬達到指定角度。角度預設為該馬達嘅校準中位 (homepoint), 並且會自動夾喺該馬達嘅安全 min/max 範圍之內, 唔會送出超出校準範圍嘅角度。(/api/servo/one)',
                                en: 'Move one of the {group} servos to a given angle. The angle defaults to that servo\u2019s calibrated home point, and is automatically clamped to that servo\u2019s safe min/max range — it will never send an angle outside the calibrated range. (/api/servo/one)' },
  servo_data_not_loaded:     { zh: '(資料未載入)', en: '(data not loaded)' },
  servo_name_fallback:       { zh: '伺服{id}', en: 'Servo {id}' },

  // -- alpha_servo_all --
  servo_all__label:          { zh: '全部 20 顆伺服馬達 角度(CSV, 用逗號分隔20個數值)', en: 'All 20 servos angles (CSV, 20 comma-separated values)' },
  servo_all__tooltip:        { zh: '一次過送出全部 20 顆伺服馬達嘅角度 (逗號分隔嘅 20 個整數, 依 #1~#20 次序)。⚠ 呢粒 block 唔會逐個 clamp 每粒馬達嘅安全範圍 (CSV 可以打任何數值) —— 想要自動夾喺安全範圍, 用「伺服部位」個別 block, 或者用「組合 20 顆角度」逐粒插數字先夾。(/api/servo/all)',
                                en: 'Send all 20 servo angles at once (20 comma-separated integers, in #1\u2013#20 order). \u26A0 This block does NOT clamp each servo to its safe range (the CSV accepts any values) — to get automatic clamping, use the individual "servo group" blocks, or use "Combine 20 angles" and plug in numbers one at a time. (/api/servo/all)' },

  // -- alpha_servo_all_helper --
  servo_helper__label:       { zh: '組合 20 顆角度 →', en: 'Combine 20 angles \u2192' },
  servo_helper__tooltip:     { zh: '將 20 個數值 block 組合成「全部伺服馬達」block 需要嘅 CSV 字串。可以插數字 block 或變數。呢粒 block 本身唔做 clamp (純粹組字串), 執行時 blockly-run.js 會用校準表逐粒夾好先送出。',
                                en: 'Combine 20 number blocks into the CSV string that the "all servos" block needs. You can plug in number blocks or variables. This block itself does not clamp values (it just builds the string) — the calibration table clamps each one at run time before sending.' },

  // -- alpha_servo_home --
  servo_home__label:         { zh: '🏠 全部伺服回到中位 (home)', en: '🏠 All servos to home position' },
  servo_home__tooltip:       { zh: '用內建校準表 (window.ALPHA_SERVO_CALIBRATION, 同「伺服」分頁及各部位 block 共用同一份) 嘅 home 值, 一次過將 20 顆伺服送返中位。',
                                en: 'Send all 20 servos back to their home position, using the built-in calibration table (window.ALPHA_SERVO_CALIBRATION, shared with the "Servo" tab and the group blocks).' },

  // -- alpha_servo_sonar --
  servo_sonar__label:        { zh: '聲納觸發距離', en: 'Sonar trigger distance' },
  servo_sonar__off_hint:     { zh: '(0 = 關閉)', en: '(0 = off)' },
  servo_sonar__tooltip:      { zh: '/api/servo/sonar - 聲納係獨立感應硬件, 同伺服馬達冇關係。', en: '/api/servo/sonar — sonar is separate sensor hardware, unrelated to the servos.' },

  // -- LED colours --
  led_colour_red:            { zh: '紅', en: 'Red' },
  led_colour_green:          { zh: '綠', en: 'Green' },
  led_colour_blue:           { zh: '藍', en: 'Blue' },
  led_colour_yellow:         { zh: '黃', en: 'Yellow' },
  led_colour_purple:         { zh: '紫', en: 'Purple' },
  led_colour_cyan:           { zh: '青', en: 'Cyan' },
  led_colour_white:          { zh: '白', en: 'White' },
  led_preset_on:             { zh: '💡 長開', en: '💡 On' },
  led_preset_flash:          { zh: '⚡ 閃燈', en: '⚡ Flash' },
  led_preset_breathe:        { zh: '🫧 呼吸燈', en: '🫧 Breathe' },
  led_preset_chase:          { zh: '🏃 跑馬燈', en: '🏃 Chase' },
  led_preset_dual:           { zh: '🎨 雙色燈', en: '🎨 Dual Color' },
  led_preset_stop:           { zh: '⏹ 停止', en: '⏹ Stop' },
  led_preset_breathe_mouth:  { zh: '🫁 呼吸燈 (開)', en: '🫁 Breathe (on)' },
  led_preset_off:            { zh: '⏹ 熄', en: '⏹ Off' },

  // -- alpha_led_head / alpha_led_eye / alpha_led_mouth --
  led_head__label:           { zh: '頭部 LED', en: 'Head LED' },
  led_eye__label:            { zh: '眼睛 LED', en: 'Eye LED' },
  led_mouth__label:          { zh: '咀部 LED', en: 'Mouth LED' },
  led__colour_label:         { zh: '顏色', en: 'Colour' },
  led__brightness_label:     { zh: '亮度(1-9)', en: 'Brightness (1\u20139)' },
  led__speed_label:          { zh: '速度(0-5000, 細=快)', en: 'Speed (0\u20135000, lower = faster)' },
  led_head__tooltip:         { zh: '揀「停止」時顏色/亮度會被忽略。(/api/led/head/set)', en: 'Colour/brightness are ignored when "Stop" is selected. (/api/led/head/set)' },
  led_eye__tooltip:          { zh: '揀「停止」時顏色/亮度會被忽略。(/api/led/eye/set)', en: 'Colour/brightness are ignored when "Stop" is selected. (/api/led/eye/set)' },
  led_mouth__tooltip:        { zh: '咀部 LED 硬件實測淨係「呼吸燈」呢個效果可用 (冇顏色/亮度可調, 得速度)。揀「熄」時速度會被忽略。(/api/led/mouth/set)',
                                en: 'On real hardware, the mouth LED only supports the "breathe" effect (no colour/brightness, only speed). Speed is ignored when "Off" is selected. (/api/led/mouth/set)' },

  // -- sensors --
  sensor_accel_toggle__label: { zh: '📟 加速度計感應器', en: '📟 Accelerometer sensor' },
  sensor_accel_toggle__tooltip: { zh: '開啟或關閉加速度計感應器 (/api/alpha2/accelerometer/set)。要先開咗呢個, WebSocket 先會送 accel 事件, 「當加速度計...觸發」個 block 先會有反應。同主控制面板「感應」分頁嘅開關掣係同一個狀態。',
                                en: 'Turn the accelerometer sensor on or off (/api/alpha2/accelerometer/set). This must be on for the WebSocket to send accel events, otherwise the "when accelerometer... triggers" block won\u2019t fire. Shares the same state as the toggle in the main panel\u2019s "Sensors" tab.' },
  sensor_sonar_toggle__label: { zh: '📟 聲納感應器', en: '📟 Sonar sensor' },
  sensor_sonar_toggle__dist: { zh: '距離(cm)', en: 'Distance (cm)' },
  sensor_sonar_toggle__tooltip: { zh: '開啟或關閉聲納感應器 (/api/alpha2/servo/sonar)。聲納冇獨立嘅開關欄位 —— 「關閉」係送 distance=0, 「開啟」就送右邊揀嘅門檻距離。要先開咗呢個, 「當聲納偵測到障礙」個 block 先會有反應。同主控制面板「感應」分頁嘅開關掣係同一個狀態。',
                                en: 'Turn the sonar sensor on or off (/api/alpha2/servo/sonar). Sonar has no separate on/off field — "Off" sends distance=0, "On" sends the threshold distance selected on the right. This must be on for the "when sonar detects an obstacle" block to fire. Shares the same state as the toggle in the main panel\u2019s "Sensors" tab.' },

  // -- alpha_event_accel_threshold --
  event_accel__label:        { zh: '🔔 當加速度計', en: '🔔 When accelerometer' },
  event_accel__axis_x:       { zh: 'X 軸', en: 'X axis' },
  event_accel__axis_y:       { zh: 'Y 軸', en: 'Y axis' },
  event_accel__axis_z:       { zh: 'Z 軸', en: 'Z axis' },
  event_accel__cmp_gt:       { zh: '絕對值 >', en: 'abs value >' },
  event_accel__cmp_lt:       { zh: '絕對值 <', en: 'abs value <' },
  event_accel__trigger_suffix: { zh: '觸發', en: 'triggers' },
  event_accel__store_prefix: { zh: '存讀數(x/y/z)入', en: 'store reading (x/y/z) in' },
  // 注意: 冇 event_accel__var_label key —— 呢個 FieldLabelSerializable 嘅值
  // ("加速度計讀數") 刻意 hardcode 喺 blockly-blocks.js, 唔跟語言切換
  // (詳見嗰邊嘅註解: 呢個值同時係已存 XML 程式嘅變數 key)。
  event_accel__tooltip:      { zh: '加速度計事件驅動 hat block: 揀一條軸, 讀數嘅絕對值大過/細過門檻先觸發下面嘅 block (單位 m/s², 含重力分量)。要先喺「感應」分頁開咗「加速度計」個掣, WebSocket 先會不斷送 accel 事件過嚟 — 呢粒 block 淨係監聽, 唔會自己開感應器。事件密度高 (約每 150-250ms 一次), 觸發後留意唔好喺 DO 入面做太耗時嘅嘢, 否則會累積住。',
                                en: 'Accelerometer event-driven hat block: pick an axis, and the block below it fires when the absolute reading is above/below the threshold (units m/s\u00B2, includes gravity). The "Accelerometer" toggle on the Sensors tab must be on first for the WebSocket to keep sending accel events — this block only listens, it doesn\u2019t turn the sensor on itself. Events arrive frequently (roughly every 150\u2013250ms), so avoid slow operations inside DO or they will pile up.' },

  // -- alpha_event_sonar_triggered --
  event_sonar__label:        { zh: '🔔 當聲納偵測到障礙', en: '🔔 When sonar detects an obstacle' },
  event_sonar__store_prefix: { zh: '存資料入', en: 'store data in' },
  // 注意: 冇 event_sonar__var_label key, 原因同上 (見 event_accel__var_label 註解)。
  event_sonar__tooltip:      { zh: '聲納事件驅動 hat block: 機械人偵測到障礙物進入設定門檻距離之內先觸發 (即 sonar_obstacle 事件嘅 triggered=true 果一刻, 由遠變近先算, 唔會不斷重複觸發)。門檻距離用「伺服」分類嘅「聲納觸發距離」block 或者「感應」分頁設定。存入變數嘅資料包含 {triggered, thresholdCm}。',
                                en: 'Sonar event-driven hat block: fires when the robot detects an obstacle coming within the configured threshold distance (i.e. the moment the sonar_obstacle event\u2019s triggered=true, going from far to near — it doesn\u2019t fire repeatedly). Set the threshold with the "Sonar trigger distance" block in the Servo category, or on the Sensors tab. The stored data includes {triggered, thresholdCm}.' },

  // -- flow control --
  wait_seconds__label:       { zh: '等待', en: 'Wait' },
  wait_seconds__suffix:      { zh: '秒', en: 'seconds' },
  wait_seconds__tooltip:     { zh: '暫停程式執行指定秒數, 唔會阻塞事件監聽。', en: 'Pause program execution for the given number of seconds, without blocking event listeners.' },
  log__label:                { zh: '📝 記錄訊息', en: '📝 Log message' },
  log__tooltip:              { zh: '喺右邊「執行紀錄」面板印一行訊息, 方便除錯, 唔會送任何 API request。', en: 'Print a line to the "Run log" panel on the right, for debugging — sends no API request.' },
  stop_program__label:       { zh: '⏹ 停止整個程式', en: '⏹ Stop entire program' },
  stop_program__tooltip:     { zh: '立即停止程式執行 (同按右上角「停止」掣一樣)。', en: 'Stop program execution immediately (same as pressing the "Stop" button top-right).' },

  // ==== blockly-toolbox.js ====
  toolbox_cat_flow:          { zh: '▶ 流程控制', en: '▶ Control' },
  toolbox_cat_events:        { zh: '🔔 事件', en: '🔔 Events' },
  toolbox_cat_action:        { zh: '🏃 動作', en: '🏃 Actions' },
  toolbox_cat_speech:        { zh: '💬 語音', en: '💬 Speech' },
  toolbox_cat_servo:         { zh: '🦾 伺服', en: '🦾 Servo' },
  toolbox_cat_led:           { zh: '💡 LED', en: '💡 LED' },
  toolbox_cat_logic:         { zh: '⚖ 邏輯', en: '⚖ Logic' },
  toolbox_cat_math:          { zh: '🔢 數學', en: '🔢 Math' },
  toolbox_cat_text:          { zh: '🔤 文字', en: '🔤 Text' },
  toolbox_cat_variables:     { zh: '📦 變數', en: '📦 Variables' },
  toolbox_cat_procedures:    { zh: '🧩 自訂函式', en: '🧩 Functions' },
  toolbox_cat_examples:      { zh: '⭐ 範例', en: '⭐ Examples' },
  toolbox_default_msg:       { zh: '訊息', en: 'message' },
  toolbox_example1_hello:    { zh: '你好，我係 Alpha2！', en: 'Hello, I\u2019m Alpha2!' },
  toolbox_example1_happy:    { zh: '好開心見到你！', en: 'Happy to see you!' },
  toolbox_tts_default_shadow: { zh: '你好', en: 'Hello' },

  // ==== blockly.html 頁面外框 UI (header/工具列/側邊面板), 用 data-i18n
  // attribute 標記, 由 blockly-i18n.js 嘅 applyUiTextLocale() 套用 ====
  page_title:                { zh: 'Open Alpha2 — Blockly 積木編程', en: 'Open Alpha2 — Blockly Programming' },
  page_back_title:           { zh: '返回控制面板', en: 'Back to control panel' },
  page_h1:                   { zh: '🧩 Alpha2 積木編程', en: '🧩 Alpha2 Blockly' },
  page_ws_disconnected:      { zh: 'WebSocket: 未連接', en: 'WebSocket: disconnected' },
  page_run_btn:              { zh: '▶ 執行', en: '▶ Run' },
  page_stop_btn:             { zh: '⏹ 停止', en: '⏹ Stop' },
  page_run_status_idle:      { zh: '閒置', en: 'Idle' },
  page_refresh_actions_btn:  { zh: '🔄 攞動作列表', en: '🔄 Load Action List' },
  page_save_name_placeholder:{ zh: '程式名稱', en: 'Program name' },
  page_save_btn:             { zh: '💾 儲存', en: '💾 Save' },
  page_load_btn:             { zh: '📂 載入', en: '📂 Load' },
  page_delete_btn:           { zh: '🗑 刪除', en: '🗑 Delete' },
  page_export_btn:           { zh: '⬇ 匯出 .xml', en: '⬇ Export .xml' },
  page_import_btn:           { zh: '⬆ 匯入 .xml', en: '⬆ Import .xml' },
  page_clear_workspace_btn:  { zh: '🧹 清空畫布', en: '🧹 Clear workspace' },
  page_run_log_title:        { zh: '執行紀錄', en: 'Run log' },
  page_clear_log_btn:        { zh: '清空', en: 'Clear' },
  page_auto_scroll_label:    { zh: '自動捲動', en: 'Auto-scroll' },
  page_version_badge_title:  { zh: 'Blockly library 版本 (核心 blockly_compressed.js + 標準 blocks_compressed.js)',
                                en: 'Blockly library version (core blockly_compressed.js + standard blocks_compressed.js)' },

  // ==== blockly-page.js 動態產生嘅文字 (WebSocket 狀態/錯誤訊息/confirm 對話框) ====
  page_ws_connected:         { zh: '已連接', en: 'connected' },
  page_ws_disconnected_word: { zh: '未連接', en: 'disconnected' },
  page_ws_connect_failed:    { zh: 'WebSocket 連線失敗', en: 'WebSocket connection failed' },
  page_blockly_version_unknown: { zh: '未知', en: 'unknown' },
  page_save_program_ctx:     { zh: '儲存程式', en: 'Save program' },
  page_save_program_need_name: { zh: '請先輸入程式名稱', en: 'Please enter a program name first' },
  page_confirm_delete:       { zh: '確定要刪除「{name}」？', en: 'Delete "{name}"?' },
  page_confirm_clear_workspace: { zh: '確定要清空成個畫布？呢個動作唔可以復原 (但係自動儲存已存低嘅版本仍然可以用「載入」攞返)。',
                                en: 'Clear the entire workspace? This cannot be undone (but the auto-saved version can still be recovered via "Load").' },
  page_https_hint:           { zh: '　(如果重新整理都係咁, 可以試下開返首頁 index.html, 喺網址列嗰個 "連線不是私人連線" 警告度撳 進階 → 繼續前往, 接受自簽證書後先再開返呢頁)',
                                en: '\u3000(If reloading doesn\u2019t help, try opening index.html first and, on the browser\u2019s "connection is not private" warning, click Advanced \u2192 Proceed to accept the self-signed certificate, then come back to this page)' },

  // ==== blockly-run.js 「執行紀錄」面板嘅 logLine() 訊息 ====
  run_status_running:        { zh: '執行緊…', en: 'Running…' },
  run_status_idle:           { zh: '閒置', en: 'Idle' },
  run_unsupported_value_block: { zh: '⚠ 未支援嘅數值 block 類型: {type}', en: '⚠ Unsupported value block type: {type}' },
  run_action_play_nowait:    { zh: '▶ 播放動作 (不等待): {name}', en: '▶ Play action (no wait): {name}' },
  run_action_play_wait:      { zh: '▶ 播放動作: {name} (等待完成, 最多 {timeout} 秒)', en: '▶ Play action: {name} (waiting, up to {timeout}s)' },
  run_action_play_exception: { zh: '❌ 播放動作時發生例外: {err}', en: '❌ Exception while playing action: {err}' },
  run_action_wait_timeout:   { zh: '⚠ 等待動作完成逾時: {name}', en: '⚠ Timed out waiting for action to finish: {name}' },
  run_action_api_failed:     { zh: '❌ 播放動作 API 呼叫失敗: {name}', en: '❌ Play action API call failed: {name}' },
  run_action_play_done:      { zh: '✅ 動作播放完成: {name}', en: '✅ Action finished playing: {name}' },
  run_no_action_selected:    { zh: '⚠ 未揀動作', en: '⚠ No action selected' },
  run_no_action_selected_live: { zh: '⚠ 未揀動作 (清單可能未載入)', en: '⚠ No action selected (list may not be loaded)' },
  run_action_stop:           { zh: '⏹ 停止動作', en: '⏹ Stop action' },
  run_action_wait_extra:     { zh: '⏳ 額外等待動作完成 (最多 {timeout} 秒)…', en: '\u23F3 Extra wait for action to finish (up to {timeout}s)\u2026' },
  run_tts:                   { zh: '💬 TTS[{engine}]: {text}', en: '💬 TTS[{engine}]: {text}' },
  run_tts_stop:              { zh: '⏹ 停止 TTS', en: '⏹ Stop TTS' },
  run_mic_ownership:         { zh: '🎙 麥克風擁有權 → {owner}', en: '🎙 Mic ownership \u2192 {owner}' },
  run_mic_owner_robot:       { zh: '機械人', en: 'robot' },
  run_mic_owner_app:         { zh: 'App', en: 'app' },
  run_start_listening:       { zh: '🎙 開始聆聽 (即時辨識)', en: '🎙 Start listening (live recognition)' },
  run_set_voice:             { zh: '🔈 設定 TTS 聲音: {name}', en: '🔈 Set TTS voice: {name}' },
  run_set_lang:              { zh: '🌐 設定辨識語言: {lang}', en: '🌐 Set recognition language: {lang}' },
  run_self_interrupt:        { zh: '✋ 自我打斷: {on}', en: '✋ Self-interrupt: {on}' },
  run_no_ringtone_selected:  { zh: '⚠ 未揀鈴聲', en: '⚠ No ringtone selected' },
  run_ringtone_play:         { zh: '🔔 播放系統鈴聲: {type} {title}{durationNote}', en: '🔔 Play system sound: {type} {title}{durationNote}' },
  run_ringtone_type_notification: { zh: '通知', en: 'notification' },
  run_ringtone_type_phone:   { zh: '電話', en: 'phone' },
  run_ringtone_duration_note: { zh: '（播 {duration} 秒）', en: ' (play for {duration}s)' },
  run_ringtone_duration_full: { zh: '（播到完為止）', en: ' (play to the end)' },
  run_ringtone_stop:         { zh: '⏹ 停止鈴聲播放', en: '⏹ Stop ringtone playback' },
  run_servo_clamped:         { zh: '⚠ 伺服 #{id} 角度 {angle}° 超出校準範圍, 已夾到 {clamped}°', en: '⚠ Servo #{id} angle {angle}\u00B0 out of calibrated range, clamped to {clamped}\u00B0' },
  run_servo_one:             { zh: '🦾 伺服 #{id} → {angle}° ({time}ms)', en: '🦾 Servo #{id} \u2192 {angle}\u00B0 ({time}ms)' },
  run_servo_all:             { zh: '🦾 全部伺服 → [{csv}] ({time}ms)', en: '🦾 All servos \u2192 [{csv}] ({time}ms)' },
  run_servo_home:            { zh: '🏠 全部伺服回中位 ({time}ms)', en: '🏠 All servos to home position ({time}ms)' },
  run_sonar_distance:        { zh: '📡 聲納距離 → {dist}', en: '📡 Sonar distance \u2192 {dist}' },
  run_led_head:               { zh: '💡 頭部LED: {preset}', en: '💡 Head LED: {preset}' },
  run_led_eye:                { zh: '💡 眼睛LED: {preset}', en: '💡 Eye LED: {preset}' },
  run_led_mouth_off:          { zh: '💡 咀部LED: 熄', en: '💡 Mouth LED: off' },
  run_led_mouth_breathe:      { zh: '💡 咀部LED: 呼吸燈 速度={speed}', en: '💡 Mouth LED: breathe, speed={speed}' },
  run_accel_toggle:           { zh: '📟 加速度計感應器: {on}', en: '📟 Accelerometer sensor: {on}' },
  run_sonar_toggle:           { zh: '📟 聲納感應器: {on}{thresholdNote}', en: '📟 Sonar sensor: {on}{thresholdNote}' },
  run_sonar_toggle_threshold: { zh: ' (門檻 {dist}cm)', en: ' (threshold {dist}cm)' },
  run_wait_seconds:           { zh: '⏳ 等待 {secs} 秒', en: '\u23F3 Wait {secs}s' },
  run_stop_program:           { zh: '⏹ 程式主動停止', en: '⏹ Program stopped' },
  run_unsupported_block:      { zh: '⚠ 未支援嘅 block 類型: {type}', en: '⚠ Unsupported block type: {type}' },
  run_error:                  { zh: '❌ 錯誤: {err}', en: '❌ Error: {err}' },
  run_no_executable_blocks:   { zh: '⚠ 冇可執行嘅 block (event block 唔算, 佢哋會自動常駐監聽)', en: '\u26A0 No executable blocks (event blocks don\u2019t count \u2014 they listen automatically in the background)' },
  run_program_start:          { zh: '▶▶▶ 開始執行程式 ({count} 條主線程序)', en: '\u25B6\u25B6\u25B6 Program started ({count} top-level sequence(s))' },
  run_runtime_error:          { zh: '❌ 執行期錯誤: {err}', en: '❌ Runtime error: {err}' },
  run_program_stopped:        { zh: '⏹ 程式已停止', en: '⏹ Program stopped' },
  run_program_finished:       { zh: '✅ 程式執行完畢', en: '✅ Program finished' },
  run_user_pressed_stop:      { zh: '⏹ 使用者按下停止', en: '⏹ User pressed stop' },
  run_handlers_registered:    { zh: '🔗 已註冊 {accel} 個加速度計觸發 + {sonar} 個聲納觸發 block', en: '\u{1F517} Registered {accel} accelerometer trigger(s) + {sonar} sonar trigger(s)' },
  run_accel_trigger_error:    { zh: '❌ 加速度計觸發錯誤: {err}', en: '❌ Accelerometer trigger error: {err}' },
  run_sonar_trigger_error:    { zh: '❌ 聲納觸發錯誤: {err}', en: '❌ Sonar trigger error: {err}' },
  run_restored_autosave:      { zh: '💾 已還原上次自動儲存嘅程式', en: '💾 Restored last auto-saved program' },
  run_saved_as:               { zh: '💾 已儲存做「{name}」', en: '💾 Saved as "{name}"' },
  run_loaded:                 { zh: '📂 已載入「{name}」', en: '📂 Loaded "{name}"' },
  run_deleted:                { zh: '🗑 已刪除「{name}」', en: '🗑 Deleted "{name}"' },
  run_saved_program_placeholder: { zh: '-- 已儲存嘅程式 --', en: '-- Saved programs --' },
  run_exported:                { zh: '⬇ 已匯出成檔案', en: '\u2B07 Exported to file' },
  run_imported:                 { zh: '⬆ 已由檔案匯入: {file}', en: '\u2B06 Imported from file: {file}' },
  run_import_failed:            { zh: '❌ 匯入失敗: {err}', en: '❌ Import failed: {err}' },
  run_fetching_action_list:     { zh: '🔄 正在抓取機械人動作清單…', en: '\u{1F504} Fetching robot action list\u2026' },
  run_action_list_empty:        { zh: '(機械人回傳空清單)', en: '(robot returned an empty list)' },
  run_action_list_loaded:       { zh: '✅ 已載入 {count} 個動作', en: '✅ Loaded {count} action(s)' },
  run_action_list_failed:       { zh: '❌ 抓取動作清單失敗', en: '❌ Failed to fetch action list' },
  run_action_list_load_failed_option: { zh: '(載入失敗)', en: '(load failed)' },

  // ==== blockly-actions-data.js 嘅 15 個子分類顯示名 ====
  // ⚠️ 呢啲 key 純粹用嚟顯示 (dropdown label / 分類前綴), 唔係 sub 本身嘅
  // wire value ——sub 內部值 (例如 "移動類") 保持中文唔變, 因為佢同時係
  // alpha_action_play_basic/dance/story/yoga/others 呢 5 粒 block 嘅
  // SUBCATEGORY field 存入 XML 嗰個值 (見 blockly-blocks.js/blockly-toolbox.js
  // 對應註解), 改咗個內部值會令已存程式讀唔到子分類。呢度加嘅係一層獨立顯示
  // layer, 用 sub_label_* 呢批 key 嚟畀用戶睇嘅版本, 唔影響底層資料。
  sub_label_move:            { zh: '移動類', en: 'Movement' },
  sub_label_gesture:         { zh: '手勢類', en: 'Gestures' },
  sub_label_head:            { zh: '頭部類', en: 'Head' },
  sub_label_expression:      { zh: '表情 / 互動類', en: 'Expressions / Interaction' },
  sub_label_full_body:       { zh: '全身 / 其他動作', en: 'Full body / Other' },
  sub_label_kids_song:       { zh: '兒童歌曲 / 卡通舞蹈', en: 'Kids songs / Cartoon dances' },
  sub_label_pop_dance:       { zh: '流行 / 節奏舞蹈', en: 'Pop / Rhythm dances' },
  sub_label_brand_dance:     { zh: '品牌 / 客製舞蹈', en: 'Brand / Custom dances' },
  sub_label_chinese_fable:   { zh: '中國寓言', en: 'Chinese fables' },
  sub_label_western_fable:   { zh: '西方寓言 / 故事', en: 'Western fables / Stories' },
  sub_label_standing_yoga:   { zh: '站立式 / 平衡式', en: 'Standing / Balance poses' },
  sub_label_stretch_yoga:    { zh: '伸展式', en: 'Stretching poses' },
  sub_label_horse_yoga:      { zh: '騎馬式', en: 'Horse-riding poses' },
  sub_label_kick_yoga:       { zh: '踢腿 / 動態式', en: 'Kicking / Dynamic poses' },
  sub_label_neuron_stand:    { zh: 'Neuron 企身動作', en: 'Neuron get-up actions' },
};

// t(key, vars?) — 攞返 key 對應嘅字串, 跟 window.getUiLanguage() 揀語言,
// 冇對應語言就 fallback 做 zh, key 完全搵唔到就直接印返個 key 出嚟 (方便一眼
// 睇到漏譯咗邊個, 唔會靜靜哋顯示 undefined)。支援 {placeholder} 簡單替換,
// 用喺好似 action_category_tooltip 呢種要插入分類名嘅字串。
window.t = function (key, vars) {
  const entry = window.ALPHA_BLOCK_I18N[key];
  const lang = (window.getUiLanguage && window.getUiLanguage()) || 'zh';
  let text = entry ? (entry[lang] || entry.zh) : key;
  if (vars) {
    for (const k in vars) {
      if (Object.prototype.hasOwnProperty.call(vars, k)) {
        text = text.split('{' + k + '}').join(vars[k]);
      }
    }
  }
  return text;
};
