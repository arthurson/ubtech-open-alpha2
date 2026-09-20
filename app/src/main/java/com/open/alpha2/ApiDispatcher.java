package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.DirectLedController;
import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.LocalAlpha2Services;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;

/**
 * /api/alpha2/* dispatcher：薄 delegate switch，body 在各 center。
 *
 * 跨域 core 經下面 Host 縫調回：
 * - speech/tts、speech/stop 由 SpeechCenter 直實現（MainActivity 不再 implements Host）
 * （speech/set_mic、set_mic_keep_held 經下面 micCenter 直調。）
 * （speech/offline_auto_switch、get_default_grammar 經下面 grammarCenter 直調，Host 不經手。）
 * （servo/sonar 經下面 sonarCenter 直調——discover 的
 * sensors 三值繼續經 sensorState 照讀，正在轉交同一個 SonarCenter。）
 * directChestReady/directHeaderReady 是內聯副本
 * （同 UbxApi/LedCenter/ChestQuery/XiaozhiBridge 一樣做法）。
 * walkie testtone/diagnose/play 經下面 micCenter 直調。
 * handleSystemApi（discover＋music/*，要 UbxPlayer pose＋MusicController＋deviceStatus.getWifiIp()）同
 * handleDirectApi（servo/led/sonar/ubx 直驅，要 LocalAlpha2Services＋
 * UbxPlayer notePose）。
 */
public final class ApiDispatcher {

    /**
     * 宿主縫：speech/tts、speech/stop 由 SpeechCenter 直實現。
     */
    public interface Host {
        HttpServer.ApiResponse handleSpeechTts(Map<String, String> query);
        HttpServer.ApiResponse handleSpeechStop();
    }

    private final Context appContext;
    private final Host host;
    private final XiaozhiBridge.HostState sensorState;
    private final ActionDirect actionDirect;
    private final UbxApi ubxApi;
    private final ChestQuery chestQuery;
    private final ChestUpgrade chestUpgrade;
    private final TtsCenter ttsCenter;
    private final VoskApi voskApi;
    private final LedCenter ledCenter;
    private final SemanticCenter semanticCenter;
    private final DeviceStatus deviceStatus;
    private final CameraApi cameraApi;
    private final AudioCenter audioCenter;
    private final RingtoneCenter ringtoneCenter;
    private final MicCenter micCenter;
    private final RobotStub robot;
    private final GrammarCenter grammarCenter;
    private final UbxPlayer ubxPlayer;
    private final MusicController musicController;
    private final LocalAlpha2Services localServices;
    // servo/sonar 直調這裡 (放最尾，慣例)。
    private final SonarCenter sonarCenter;
    private final ActionsPackController actionsPackController;

    public ApiDispatcher(Context context, Host host, XiaozhiBridge.HostState sensorState,
            ActionDirect actionDirect, UbxApi ubxApi, ChestQuery chestQuery,
            ChestUpgrade chestUpgrade, TtsCenter ttsCenter, VoskApi voskApi,
            LedCenter ledCenter, SemanticCenter semanticCenter, DeviceStatus deviceStatus,
            CameraApi cameraApi, AudioCenter audioCenter, RingtoneCenter ringtoneCenter,
            MicCenter micCenter, RobotStub robot,
            GrammarCenter grammarCenter, UbxPlayer ubxPlayer, MusicController musicController,
            LocalAlpha2Services localServices, SonarCenter sonarCenter,
            ActionsPackController actionsPackController) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.sensorState = sensorState;
        this.actionDirect = actionDirect;
        this.ubxApi = ubxApi;
        this.chestQuery = chestQuery;
        this.chestUpgrade = chestUpgrade;
        this.ttsCenter = ttsCenter;
        this.voskApi = voskApi;
        this.ledCenter = ledCenter;
        this.semanticCenter = semanticCenter;
        this.deviceStatus = deviceStatus;
        this.cameraApi = cameraApi;
        this.audioCenter = audioCenter;
        this.ringtoneCenter = ringtoneCenter;
        this.micCenter = micCenter;
        this.robot = robot;
        this.grammarCenter = grammarCenter;
        this.ubxPlayer = ubxPlayer;
        this.musicController = musicController;
        this.localServices = localServices;
        this.sonarCenter = sonarCenter;
        this.actionsPackController = actionsPackController;
    }

    // directChestReady() 內聯：經 appContext 不用 Activity（各 center 自帶副本）。
    private boolean directChestReady() {
        return directReady(true);
    }

    // directHeaderReady() 內聯：同上（handleSystemApi discover＋handleDirectApi status 用）。
    private boolean directHeaderReady() {
        return directReady(false);
    }

    /** 胸／頭串口就緒二合一（實現見 {@link DirectProbes}，這裡僅留薄 delegate 保 call site 不變）。 */
    private boolean directReady(boolean chest) {
        return chest ? DirectProbes.isChestReady(appContext) : DirectProbes.isHeadReady(appContext);
    }

    /** music/* 六連發共用形（err != null 即 500，否則 {"ok":true}）。 */
    private static HttpServer.ApiResponse musicOp(MusicOp op) {
        String err = op.run();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.okTrue();
    }

    private interface MusicOp { String run(); }

    /** 三個 404 共用形（訊息前綴不變，免 drift／前端字串比對斷）。 */
    private static HttpServer.ApiResponse unknown(String kind, String path) {
        String prefix = kind == null || kind.isEmpty() ? "unknown endpoint: "
                : "unknown " + kind + " endpoint: ";
        return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                "{\"ok\":false,\"error\":\"" + prefix + path + "\"}");
    }

    /**
     * Routes "/api/<name>" calls to the matching Alpha2RobotApi method. Runs on an
     * HttpServer worker thread (not the main thread) - every SDK call used here is safe
     * to invoke off the main thread (the *ServiceUtil classes only marshal Binder calls),
     * matching how the SDK's own AGENTS.md describes bind/call safety.
     */

    public HttpServer.ApiResponse handleApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            // -- 健康狀態聚合 (body 在 DeviceStatus；薄 delegate，不要在這裡加 logic) --
            case "status":
                return deviceStatus.statusResponse();

            case "chest/version": {
                // 只回 chest MCU 真實韌體版本 (sendCommand 51)
                long timeoutMs = ApiValidator.optionalLong(query, "timeout", 1500L);
                String v = chestQuery.queryFirmwareVersion(timeoutMs);
                if (v != null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + MainActivity.jsonSafe(v) + "\"}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"version\":\"not found\"}");
                }
            }
            case "chest/upgrade": {
                // 觸發胸口升級：讀 /sdcard/AlphaII_CHEST_kernel.bin 經 48/49/50 協議升級
                String err = chestUpgrade.startChestUpgrade();
                if (err == null) {
                    return HttpServer.ApiResponse.ok("{\"ok\":true,\"started\":true}");
                } else {
                    return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(err) + "\"}");
                }
            }
            case "chest/upgrade/status": {
                return HttpServer.ApiResponse.ok("{\"ok\":true," + chestUpgrade.getChestUpgradeStatusJson().substring(1));
            }
            case "chest/upgrade/resume": {
                int from = ApiValidator.optionalInt(query, "from", 0);
                String err = chestUpgrade.startChestUpgradeFrom(from);
                if (err == null) return HttpServer.ApiResponse.ok("{\"ok\":true,\"resumed\":true,\"from\":"+from+"}");
                else return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(err) + "\"}");
            }
            case "chest/upgrade/abort": {
                chestUpgrade.abort();
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"aborted\":true}");
            }
            // -- 升級鏡像讀頁 (body 在 ChestUpgrade；薄 delegate，不要在這裡加 logic) --
            case "chest/page":
                return chestUpgrade.chestPageResponse(query);

            // -- Actions (pure-direct: actionInfo.txt + UbxPlayer，机身已无 alpha2services，
            // 旧 AIDL action_* 一律 NOT_INIT，此处不再经过 RobotStub) --------------
            case "action/list":
                return actionDirect.actionListDirect();
            case "action/play":
                return actionDirect.actionPlayDirect(ApiValidator.require(query, "name"));
            case "action/stop": {
                // 用戶要求「停止」要連帶做「蹲下站起」回位動作：与手势总停/MCP 共用
                // stopActionWithRecovery()，回位播不播到不影響停止本身回 true。
                return MainActivity.codeResponse(actionDirect.stopActionWithRecovery());
            }
            // -- 動作包下載＋替換（實驗 tab 動作包卡用；body 在 ActionsPackController，
            // 薄 delegate，不要在這裡加 logic）--
            // 固定 URL（見 ActionsPackController.PACK_URL），後端直落 zip＋自動
            // unzip 到 sdcard，舊 /sdcard/actions 改名 /sdcard/actions-backup。
            case "action/pack/download": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                String err = actionsPackController.startDownload();
                if (err != null) {
                    // 正在下載中都回現狀等前端跟進度（同 vosk/download 一致）。
                    if (err.startsWith("already downloading")) {
                        return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
                    }
                    return HttpServer.ApiResponse.error(err);
                }
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }
            case "action/pack/status": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }
            case "action/pack/cancel": {
                if (actionsPackController == null) {
                    return HttpServer.ApiResponse.error("actions pack not initialised");
                }
                String err = actionsPackController.cancelDownload();
                if (err != null) return HttpServer.ApiResponse.error(err);
                return HttpServer.ApiResponse.ok(actionsPackController.statusJson());
            }

            // -- Ubx 直播（供前端动作 tab：api() 只发 /api/alpha2/*，故在此挂一份；
            // /api/direct/ubx/* 那份调同一 helper，行为一致；/api/ubx/* 裸路径经
            // fallthrough 亦到此）--------------
            case "ubx/list":
                return ubxApi.ubxListResponse();
            case "ubx/play":
                return ubxApi.ubxPlayResponse(ApiValidator.optionalNullable(query, "name"),
                        ApiValidator.optionalNullable(query, "path"));
            case "ubx/stop":
                return ubxApi.ubxStopResponse();
            case "ubx/status":
                return ubxApi.ubxStatusResponse();
            case "ubx/speed":
                // 枚舉校驗在 ubxSpeedResponse 內經 ApiValidator.parseUbxSpeedValue 統一做,
                // 這裡只保證必填 (缺席即 400), 避免兩次 parse。
                return ubxApi.ubxSpeedResponse(ApiValidator.require(query, "value"));

            // -- Speech / TTS -----------------------------------------------------------
            // engine: android only (only engine that speaks). nuance/iflytek
            // 已死 (alpha2services binder 不存在，robot.speech_startTTS 恆回 NOT_INIT)：
            // ApiValidator 直接拒收非 "android" 值。
            case "speech/tts":
                return host.handleSpeechTts(query);
            case "speech/stop":
                return host.handleSpeechStop();

            // Android TTS 語言選擇 - engine 現在只有 android，lang 由 speech/tts_languages
            // 提供的清單選。ui_lang ("zh"/"en") 控制的是 displayName 用邊種語言顯示。
            case "speech/tts_languages":
                return ttsCenter.ttsLanguages(query);

            // Android TTS 引擎選擇 - 機身可能裝了不只一個系統 TTS 引擎 (例如出廠
            // 內建 + Google TTS + SVOX Pico), 這三個 endpoint 供 speech tab 選擇
            // speech/tts 實際用哪個發音。
            case "speech/tts_engines":
                return ttsCenter.ttsEngines();

            case "speech/set_tts_engine":
                return ttsCenter.setTtsEngine(query);

            case "speech/cur_tts_engine":
                return ttsCenter.curTtsEngine();

            // TTS 卡語言選擇的後端 pref (BCP-47 tag，空=沿用引擎
            // 目前語言)。前端 setAndroidTtsLang() 同步寫入；對話管線
            // speakAndroidTts() 優先讀它——一選即時跟。
            case "speech/set_tts_lang":
                return ttsCenter.setTtsLang(query);

            case "speech/cur_tts_lang":
                return ttsCenter.curTtsLang();

            // TTS 卡聲音選擇 (Google TTS 每個語言多把聲)。
            // tts_voices?lang=<BCP-47> 列該語言把聲 (name/locale/network/quality)；
            // set/cur_tts_voice 讀寫選擇 (空=該語言預設聲)。轉引擎/轉語言會清
            // 舊聲。speech/tts 帶 voice 參數即用該聲讀；對話管線自動跟 pref。
            case "speech/tts_voices":
                return ttsCenter.ttsVoices(query);

            case "speech/set_tts_voice":
                return ttsCenter.setTtsVoice(query);

            case "speech/cur_tts_voice":
                return ttsCenter.curTtsVoice();

            case "speech/set_mic":
                return micCenter.setMic(query);
            case "speech/set_mic_keep_held":
                return micCenter.setMicKeepHeld(query);
            // 已移除（死 binder）：speech/reset、speech/start_asr、speech/set_voice、
            // speech/set_language、speech/self_interrupt、speech/inject。舊 .xml
            // 程式有用過這幾粒的話，匯入那顆會 load 不到，要手動刪了它。
            // -- 語義模擬 (body 在 SemanticCenter；薄 delegate，不要在這裡加 logic) --
            case "speech/semantic_simulate":
                return semanticCenter.semanticSimulateResponse(query);
            // 已移除（死 binder）：speech/stop_inject。
            // 已移除（死 binder）：speech/init_grammar、speech/start_grammar、
            // speech/stop_grammar 三個 endpoint（機身已無 iFlytek 引擎，
            // 恒回 NOT_INIT）。內部 doInitGrammar/doStartGrammar/doStopGrammar
            // 保留（離線自動切換內部流程還正在用），get_default_grammar 照讀本地 asset。
            case "speech/get_default_grammar":
                return grammarCenter.getDefaultGrammar();
            case "speech/offline_auto_switch":
                return grammarCenter.offlineAutoSwitch(query);
            // -- Vosk 離線 ASR (body 在 VoskApi；薄 delegate，不要在這裡加 logic) --
            case "vosk/models":
                return voskApi.voskModels();
            case "vosk/load":
                return voskApi.voskLoad(query);
            case "vosk/status":
                return voskApi.voskStatus();
            case "vosk/start":
                return voskApi.voskStart();
            case "vosk/stop":
                return voskApi.voskStop();
            case "vosk/unload":
                return voskApi.voskUnload();
            case "vosk/download":
                return voskApi.voskDownload(query);
            case "vosk/download_status":
                return voskApi.voskDownloadStatus();
            case "vosk/download_cancel":
                return voskApi.voskDownloadCancel();
            case "vosk/catalog":
                return voskApi.voskCatalog();
            case "vosk/hallu":
                return voskApi.voskHallu();
            case "vosk/hallu_set":
                return voskApi.voskHalluSet(query);
            // -- Servos -----------------------------------------------------------------
            case "servo/one":
                return ubxApi.servoOneResponse(query);
            case "servo/all":
                return ubxApi.servoAllResponse(query);
            // threshold state 經 sonarCenter 直調。
            case "servo/sonar":
                return sonarCenter.servoSonarResponse(query);
            case "servo/read":
                return ubxApi.servoReadResponse(query);
            case "servo/read-all":
                return ubxApi.servoReadAllResponse();
            // 絕對角度實際讀取 (cmd 6，同 servo/one 同單位；read/read-all 那個是 trim/偏差)。
            case "servo/angle":
                return ubxApi.servoAngleResponse(query);
            case "servo/angle-restore":
                return ubxApi.servoAngleRestoreResponse(query);
            case "servo/angle-all":
                return ubxApi.servoAngleAllResponse();

            // -- PIR (body 在 LedCenter；薄 delegate，不要在這裡加 logic) --
            case "pir/set":
                return ledCenter.pirSetResponse(query);
            case "pir/alert_enabled":
                return ledCenter.pirAlertEnabledResponse(query);

            // -- LEDs (5-mic hardware only path - server-side preset mapping) --------------
            // Colour/brightness/mode values are user-confirmed on real 5-mic hardware:
            //   color: 1=紅 2=綠 3=藍 4=黃 5=紫 6=青 7=白
            //   brightness: 1 (dimmest) .. 9 (brightest)
            //   preset -> (p5 upTime, p6 downTime, p7 runTime, p8 mode) mapping below.
            //   mode codes differ between head and eye - see Alpha2RobotApi javadoc.
            case "led/head/set":
                return ledCenter.ledHeadSet(query);
            case "led/eye/set":
                return ledCenter.ledEyeSet(query);
            case "led/mouth/set":
                return ledCenter.ledMouthSet(query);
            case "debug/jni/led":
                return ledCenter.debugJniLed(query);
            case "debug/serial/send":
                return ledCenter.debugSerialSend(query);

            // -- Head / misc ---------------------------------------------------------------
            case "head/noise": {
                boolean on = ApiValidator.requireBoolean(query, "on");
                boolean sent = HardwareDirectManager.get(appContext).head().setNoiseReduction(on);
                return MainActivity.codeResponse(MainActivity.directCode(sent));
            }
            // -- UUID (body 在 ChestQuery；薄 delegate，不要在這裡加 logic) --
            case "misc/request_uuid":
                return chestQuery.requestUuidResponse();
            case "misc/set_uuid":
                return chestQuery.setUuidResponse(query);

            // -- Camera: standard Android legacy Camera API, not SDK-gated (see
            // CameraController for the front/back index quirk on this hardware). The
            // live feed itself is served at GET /stream/camera (see handleStream()) as
            // MJPEG, not through this JSON api/ path - a continuous multipart response
            // doesn't fit the single-JSON-body ApiResponse shape. This single-frame
            // snapshot endpoint just starts the camera (if it isn't already streaming)
            // and returns whatever the most recent preview frame is, for callers that
            // want one still image rather than opening the stream. -----------------------
            case "camera/snapshot":
                return cameraApi.snapshot();
            case "camera/snapshot_save":
                return cameraApi.snapshotSave(query);
            case "camera/take_photo_save":
                return cameraApi.takePhotoSave(query);
            // Plays the "Sirrah" shutter cue out of the robot's own speaker (see
            // playShutterCue() javadoc) - called by the browser right after a
            // successful camera/snapshot, instead of synthesizing a click sound in
            // the browser itself.
            case "camera/shutter_sound":
                return cameraApi.shutterSound();
            case "camera/info":
                return cameraApi.info();
            case "camera/fps":
                return cameraApi.fps();
            case "camera/supported_sizes":
                return cameraApi.supportedSizes();
            case "camera/resolution":
                return cameraApi.resolution(query);
            case "camera/zoom":
                return cameraApi.zoom(query);
            // -- Walkie-talkie (body 在 MicCenter；薄 delegate，不要在這裡加 logic) --
            case "audio/testtone":
                return micCenter.testTone();
            case "audio/diagnose":
                return micCenter.diagnoseAudio();
            case "audio/play/start":
                return micCenter.playStart();
            case "audio/play/stop":
                return micCenter.playStop();

            // -- System ringtones/notification sounds: exposes every ringtone Android
            // knows about (via RingtoneManager, same mechanism findRingtoneByTitle()
            // above already uses to look up "Proxima"/"Sirrah" by name) as a numbered
            // list, so the Blockly page can offer a dropdown without hardcoding titles
            // that vary by OEM/Android version. "list" returns titles+type; "play"
            // takes the numbered index back and plays it through the same STREAM_MUSIC
            // MediaPlayer path as playRingtoneUri() (so it follows the media volume
            // slider, not the separate ringer/notification volume). -------------------
            case "audio/ringtones/list":
                return ringtoneCenter.ringtonesList(query);
            case "audio/ringtones/play":
                return ringtoneCenter.ringtonesPlay(query);

            // 用 title 查找鈴聲, 不再用 audio/ringtones/list 的 numbered
            // index (見上面 findRingtoneByTitle() 的 javadoc: cursor position 不保證
            // 跨機一致, 因為 RingtoneManager 內部排序邏輯不一定和 adb content query
            // 手動加 --sort 那個排序一樣)。Blockly 頁面現在內嵌一份靜態 title 清單
            // (由實機 adb content query 執行一次抓回來, 見 blockly-actions-data.js
            // 旁邊的 blockly-ringtone-data.js), 選了 title 直接送這個 API, 沿用
            // findRingtoneByTitle() 這個已經被 playStopCue()/playShutterCue() 使用、
            // 驗證過穩健的「查 title 轉 Uri」機制, 完全不用理會 index 排序這個問題。
            case "audio/ringtones/play_by_title":
                return ringtoneCenter.ringtonesPlayByTitle(query);

            // 停止目前正在播放的系統鈴聲/通知聲 (play / play_by_title 兩個
            // endpoint 播放的那個), 對應 Blockly「範例 5」的「停止播放」按鈕。
            case "audio/ringtones/stop":
                return ringtoneCenter.ringtonesStop();

            // -- Local music (/mnt/internal_sd/music/): 用戶自己放在機身的音樂檔,
            // 和上面 audio/ringtones/* 那些系統鈴聲是兩回事, 各自獨立一套 endpoint/
            // MediaPlayer, 詳見 listLocalMusicFiles()/playLocalMusicFile() 的
            // javadoc。"list" 沒有 index (檔案清單會隨用戶自己增減歌曲而變, 不像
            // ringtone 那些系統清單那麼穩定), "play" 直接用檔名 (含副檔名) 選取。
            case "audio/local_music/list":
                return audioCenter.localMusicList();
            case "audio/local_music/play":
                return audioCenter.localMusicPlay(query);
            case "audio/local_music/stop":
                return audioCenter.localMusicStop();
            case "audio/local_music/status":
                return audioCenter.localMusicStatus();
            case "audio/local_music/seek":
                return audioCenter.localMusicSeek(query);
            case "audio/local_music/volume":
                return audioCenter.localMusicVolume(query);
            case "audio/local_music/spectrum":
                return audioCenter.localMusicSpectrum();
            case "audio/local_music/pause":
                return audioCenter.localMusicPause();
            case "audio/local_music/resume":
                return audioCenter.localMusicResume();

            case "audio/local_music/filler_action/get":
                return audioCenter.fillerActionGet();
            case "audio/local_music/filler_action/set":
                return audioCenter.fillerActionSet(query);

            // -- FM/網絡電台 (經 Radio Browser API, radio-browser.info, 動態搜全
            // 世界公開電台 - 見 searchRadioStations()/resolveRadioStation() 的
            // javadoc, 這台機器不再內建任何寫死的電台清單) - "search" 對應
            // self.media.search_radio, "play" 用 resolveRadioStation() 做人類
            // 語言名比對 (先比對 lastRadioSearchResults, 比對不到就直接當新搜尋詞打
            // API)。多加一個 "status" 供前端面板顯示「目前正在播哪個台」用 (電台沒有
            // 檔名那麼直觀, 用戶自己按「轉台」之後有需要知道結果)。這兩個 endpoint
            // 內部會打網路, 和 MCP tool 那邊不同 (那邊有外層 try/catch(Exception)
            // 包住整個 switch), handleApi() 沒有, 所以這裡自己要包一層 try/catch
            // 把 IOException/JSONException 轉成正常的 {"ok":false,...} 回應,
            // 不可以讓 exception 直接飛出 handleApi()。
            case "audio/radio/search":
                return audioCenter.radioSearch(query);
            case "audio/radio/play":
                return audioCenter.radioPlay(query);
            case "audio/radio/play_url":
                return audioCenter.radioPlayUrl(query);
            case "audio/radio/stop":
                return audioCenter.radioStop();
            case "audio/radio/status":
                return audioCenter.radioStatus();

            // -- Media volume (body 在 AudioCenter；薄 delegate，不要在這裡加 logic) --
            case "audio/volume/get":
                return audioCenter.systemVolumeGet();
            case "audio/volume/set":
                return audioCenter.systemVolumeSet(query);

            // -- Battery (body 在 DeviceStatus；薄 delegate，不要在這裡加 logic) --
            case "battery/status":
                return deviceStatus.batteryStatus();

            // -- Wi-Fi / Bluetooth: standard Android framework, not SDK-gated. -----------
            case "wifi/status":
                return deviceStatus.wifiStatus();
            case "bt/status":
                return deviceStatus.btStatus();

            // -- Robot-service broadcasts with simple boolean extras. --------------------
            // 已移除（假活）：misc/power_save（見下）與 misc/charge_play ——
            // 純粹發 broadcast 給已不存在的 alpha2services, 回 ok:true 但實際
            // 無效。

            // -- Accelerometer (body 在 DeviceStatus；薄 delegate，不要在這裡加 logic) --
            case "accelerometer/set":
                return deviceStatus.accelerometerSet(query);
            case "accelerometer/get":
                return deviceStatus.accelerometerGet();

            // 已移除：service_config/get|set（alpha2services 專用 config，機身已無此
            // 服務，對 open alpha2 無用）。reboot 亦已
            // 移除：App 無 REBOOT 權限 (uid 無 grant，實機 verified 永遠
            // SecurityException)，改做手動重開機提示。

            default:
                return unknown("", path);
        }
    }

    // -- /api/system/* + /api/direct/* --

    /**
     * Small namespace ("/api/system/...") for things not tied to the robot AIDL
     * surface itself.
     */
    public HttpServer.ApiResponse handleSystemApi(String path, Map<String, String> query, String method, String body) {
        switch (path) {
            // 一次搜齊機器資料（lynx 年代 sys/* 七連發的 pure-direct 版，一個回包齊完，
            // 慢 query 各 1.5s 上限）。電池版本字串本機胸固件無此命令，如實缺席；
            // 電量/充電走 Android 系統廣播。
            case "discover": {
                String appVer = "?";
                try {
                    appVer = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                String chestFw;
                try {
                    chestFw = chestQuery.queryFirmwareVersion(1500);
                } catch (Exception e) {
                    chestFw = null;
                }
                String chestUuid;
                try {
                    chestUuid = chestQuery.queryRobotUuid(1500);
                } catch (Exception e) {
                    chestUuid = null;
                }
                int[] pose = ubxPlayer.pose();
                StringBuilder sb = new StringBuilder("{\"ok\":true,");
                sb.append("\"app\":{\"package\":\"").append(MainActivity.jsonSafe(appContext.getPackageName())).append("\",")
                        .append("\"version\":\"").append(MainActivity.jsonSafe(appVer)).append("\",")
                        .append("\"panel\":\"http://").append(MainActivity.jsonSafe(deviceStatus.getWifiIp())).append(":")
                        .append(HttpServer.PORT).append("/\"},");
                sb.append("\"robot\":{\"chestFw\":").append(chestFw != null ? "\"" + MainActivity.jsonSafe(chestFw) + "\"" : "null").append(",")
                        .append("\"chestUuid\":").append(chestUuid != null ? "\"" + MainActivity.jsonSafe(chestUuid) + "\"" : "null").append(",")
                        .append("\"chestAvailable\":").append(directChestReady()).append(",")
                        .append("\"headerAvailable\":").append(directHeaderReady()).append("},");
                sb.append("\"power\":{\"level\":").append(deviceStatus.getBatteryLevel()).append(",")
                        .append("\"scale\":").append(deviceStatus.getBatteryScale()).append(",")
                        .append("\"charging\":").append(deviceStatus.isBatteryCharging()).append(",")
                        .append("\"status\":\"").append(MainActivity.jsonSafe(deviceStatus.getBatteryStatus())).append("\"},");
                sb.append("\"sensors\":{\"sonarCm\":").append(sensorState.getSonarDistanceCm()).append(",")
                        .append("\"sonarThresholdCm\":").append(sensorState.getSonarThreshold()).append(",")
                        .append("\"pir\":").append(sensorState.getPirTriggeredState()).append("},");
                sb.append("\"servo\":{\"poseKnown\":").append(pose != null);
                if (pose != null) {
                    sb.append(",\"angles\":[");
                    for (int i = 0; i < 20; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(pose[i]);
                    }
                    sb.append("]");
                }
                sb.append("}}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            // -- 實驗 tab 面板 token 認證（見 PanelAuth：opt-in，預設關＝全開；
            // status/verify 永遠開放，set/clear 啟用中要 current 舊值） --
            case "auth/status":
                return PanelAuth.status(appContext);
            case "auth/set":
                return PanelAuth.set(appContext, query);
            case "auth/clear":
                return PanelAuth.clear(appContext, query);
            case "auth/verify":
                return PanelAuth.verify(appContext, query);
            // ---------------- 本地音樂播放 ----------------
            // "/api/system/music/..." - 播放機身 SD 卡裡面 (/sdcard/Music 等) 已有的
            // 音樂檔, 經由 MusicController (standard android.media.MediaPlayer,
            // STREAM_MUSIC 由機器人喇叭輸出) 播放, 和 AIDL 機器人 API 完全無關,
            // 所以放在 system 這個 namespace 底下, 和 camera/audio-testtone 那類
            // 純硬體功能看齊。

            case "music/list": {
                java.util.List<MusicController.Track> tracks = musicController.listTracks();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,\"tracks\":[");
                for (int i = 0; i < tracks.size(); i++) {
                    if (i > 0) sb.append(",");
                    MusicController.Track t = tracks.get(i);
                    sb.append("{\"path\":\"").append(MainActivity.jsonSafe(t.path)).append("\",")
                      .append("\"name\":\"").append(MainActivity.jsonSafe(t.name)).append("\",")
                      .append("\"sizeBytes\":").append(t.sizeBytes).append("}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            case "music/play": {
                final String p = ApiValidator.require(query, "path");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.play(p); } });
            }

            case "music/pause": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.pause(); } });
            }

            case "music/resume": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.resume(); } });
            }

            case "music/stop": {
                return musicOp(new MusicOp() { @Override public String run() { return musicController.stop(); } });
            }

            case "music/seek": {
                final int ms = ApiValidator.requireInt(query, "ms");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.seekTo(ms); } });
            }

            case "music/volume": {
                final int pct = ApiValidator.requireVolumePercent(query, "percent");
                return musicOp(new MusicOp() { @Override public String run() { return musicController.setVolume(pct); } });
            }

            case "music/status": {
                MusicController.Status s = musicController.status();
                return HttpServer.ApiResponse.ok("{\"ok\":true,"
                        + "\"hasTrack\":" + s.hasTrack + ","
                        + "\"playing\":" + s.playing + ","
                        + "\"prepared\":" + s.prepared + ","
                        + "\"path\":" + (s.path != null ? "\"" + MainActivity.jsonSafe(s.path) + "\"" : "null") + ","
                        + "\"positionMs\":" + s.positionMs + ","
                        + "\"durationMs\":" + s.durationMs + "}");
            }

            default:
                return unknown("system", path);
        }
    }

    public HttpServer.ApiResponse handleDirectApi(String path, Map<String, String> query, String method, String body) {
        if (localServices == null) {
            return HttpServer.ApiResponse.error("direct not initialized");
        }
        switch (path) {
            case "status": {
                boolean direct = localServices.isDirectActive();
                boolean chest = false, head = false;
                try { chest = localServices.isDirectActive() && HardwareDirectManager.get(appContext).chest().isAvailable(); } catch (Exception ignore) {}
                try { head = HardwareDirectManager.get(appContext).head().isAvailable(); } catch (Exception ignore) {}
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"direct\":" + direct + ",\"chest\":" + chest + ",\"head\":" + head + "}");
            }
            case "servo/one": {
                int id = ApiValidator.requireIntRange(query, "id", ApiValidator.SERVO_ID_MIN, ApiValidator.SERVO_ID_MAX);
                int angle = ApiValidator.requireIntRange(query, "angle", ApiValidator.SERVO_ANGLE_MIN, ApiValidator.SERVO_ANGLE_MAX);
                int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 500);
                // cmd05 單發（官方 tuner 實測本機可動），見 servoSendOneCode。
                return ubxApi.servoSendOne(id, angle, time);
            }
            case "servo/all": {
                int[] arr = ApiValidator.requireAngles20(query);
                int time = ApiValidator.optionalIntRange(query, "time", ApiValidator.SERVO_TIME_MIN_MS, ApiValidator.SERVO_TIME_MAX_MS, 500);
                // setAllServos 内部已转 cmd03（cmd52 有 ACK 无动作）。
                boolean sent = HardwareDirectManager.get(appContext).chest().setAllServos(arr, (short) time);
                if (!sent) return HttpServer.ApiResponse.error("direct not ready");
                ubxPlayer.notePose(arr);
                return HttpServer.ApiResponse.okTrue();
            }
            case "sonar/config": {
                int cm = ApiValidator.requireIntRange(query, "distance", 0, 100);
                boolean ok = HardwareDirectManager.get(appContext).chest().configureSonar(cm);
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/head": {
                int color = ApiValidator.optionalInt(query, "color", 3);
                Integer modeOpt = ApiValidator.optionalInteger(query, "mode");
                int mode = modeOpt != null ? modeOpt.intValue() : 0;
                boolean ok = localServices.ledHead(color);
                // also try direct with mode
                if (modeOpt != null) ok = DirectLedController.setHead5Mic(color, 9, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, mode);
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/off": {
                boolean ok = localServices.ledOff();
                return HttpServer.ApiResponse.okBool(ok);
            }
            case "led/mouth": {
                int sp = ApiValidator.optionalInt(query, "breathe", 500);
                boolean ok = localServices.ledMouthBreathe(sp);
                return HttpServer.ApiResponse.okBool(ok);
            }
            // Ubx 直播（与 /api/alpha2/ubx/* 同 helper；抢占式：播新自动停旧）。
            case "ubx/list":
                return ubxApi.ubxListResponse();
            case "ubx/play":
                return ubxApi.ubxPlayResponse(ApiValidator.optionalNullable(query, "name"),
                        ApiValidator.optionalNullable(query, "path"));
            case "ubx/speed":
                return ubxApi.ubxSpeedResponse(ApiValidator.require(query, "value"));
            case "ubx/stop":
                return ubxApi.ubxStopResponse();
            case "ubx/status":
                return ubxApi.ubxStatusResponse();
            default:
                return unknown("direct", path);
        }
    }

}



