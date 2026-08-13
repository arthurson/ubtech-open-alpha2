package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.serverlibutil.aidl.ActionInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.AlarmInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IActionResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorMoveAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorReadAngleListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IMotorSetAllAngleResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedListResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemoteLedOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.IRemotePIRSensorOperationResultListener;
import com.ubtechinc.alpha.serverlibutil.aidl.LedInfo;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorAngle;
import com.ubtechinc.alpha.serverlibutil.aidl.MotorInfo;
import com.ubtechinc.lynxrobot.LynxRobotApi;
import com.ubtechinc.lynxrobot.constant.UbxErrorCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Answers every "/api/lynx/..." HTTP call by invoking the matching {@link LynxRobotApi}
 * method (Lynx 3.0.0.2 firmware, {@code com.ubtechinc.alpha.serverlibutil.aidl}).
 *
 * Mirrors {@link MainActivity}'s handleApi() dispatch style exactly (same
 * require()/queryOrDefault()/jsonSafe() helpers, same ApiResponse shape) so both
 * backends look identical from the browser's point of view - only the URL prefix
 * ("alpha2/" vs "lynx/") and the set of available endpoints differ.
 *
 * Unlike Alpha2RobotApi, LynxRobotApi needs no explicit initXxxApi()/bind step - every
 * subsystem binder is fetched lazily (and re-fetched if the robot process restarts) the
 * first time it's actually used, via ServiceFetcher. So this class can simply be
 * constructed and used immediately; there is no equivalent of Alpha2's "speechReady"
 * flag to wait for.
 *
 * Async results (action list, motor list, LED list, ASR, ...) are pushed onto the same
 * EventBus MainActivity already uses for Alpha2, so the browser's single WebSocket log
 * shows both backends' events without any client-side change.
 */
public class LynxController {

    private static final String TAG = "LynxController";

    private final LynxRobotApi robot;

    /** Handles endpoints that are plain Android hardware access (camera, mic/speaker
     *  test tone) and go through neither Alpha2RobotApi nor LynxRobotApi - the same
     *  physical camera/mic/speaker exist on this hardware regardless of which AIDL
     *  backend is selected, so those calls fall through to MainActivity's existing
     *  implementation instead of being duplicated here. See MainActivity's
     *  SharedHardwareHandler wiring in onCreate(). */
    public interface SharedHardwareHandler {
        HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body);
    }

    /** "sys/pir_alert_enabled" defers to MainActivity's setPirAlertEnabled() - the LED
     *  flash/ringtone reaction to the PIR_STATE broadcast lives there (alongside the
     *  independent LynxRobotApi instance it uses for the LED calls), not here, since
     *  RobotEventReceiver/EventBus subscription is already wired up in MainActivity's
     *  onCreate(). This just needs a way to flip that on/off from an HTTP request. */
    public interface PirAlertHandler {
        void setEnabled(boolean enabled);
    }

    // -- action/play concurrency guard --------------------------------------------------
    // 2026-08 bugfix (用家回報 Blockly 播動作會令成個 app 卡死): svc.playAction(...)
    // (LynxRobotApi#action_playAction() 入面) 係一個真正 block 住等 UBTECH 機身側
    // IActionService 回應嘅 AIDL Binder call, 而呢個 service 底層對應嘅實體馬達冇
    // 可能同一時間執行多過一個動作。用 logcat 追查過一次真實個案: 幾個 action/play
    // request 喺幾 ms 之內連環送到, 之後成個 app 嘅 HttpServer 就再冇任何回應
    // log —— 即係其中一個 svc.playAction() 卡死咗冇返, 掗住咗個共用嘅 Binder
    // thread, 連累埋之後所有 AIDL 呼叫 (唔止 action, 連 servo/LED 都一齊唔郁得)。
    //
    // blockly-run.js 呢邊已經加咗一個 client-side 嘅序列化閘
    // (actionBusyPromise/sendActionPlay(), 保證 Blockly 唔會自己連環送) - 但呢度
    // 都加多一層 server-side 嘅保險, 唔淨係靠前端自律: 如果 action/play 已經有
    // 一個在途 (未收到 onPlayActionResult/onStopActionResult), 新嚟嘅 request 直接
    // 拒絕 (回 409-style 嘅 ok:false, 唔會再送多一個 svc.playAction() 落去), 等
    // 前端可以即刻見到清晰嘅錯誤, 而唔係成個 app 靜雞雞卡死。呢個保護唔止顧到
    // Blockly, 連第二個瀏覽器分頁/未來新增嘅呼叫來源都受惠。
    //
    // 2026-08 第二次 bugfix (用家提供嘅 logcat: 一個 action/play 之後 14 秒都
    // 冇再見到任何 action 相關 log, 之後嘅每個 action/play 全部俾呢個 guard 話
    // "action already in flight"): 用真機 log 追查證實, UBTECH 機身側
    // IActionService 對某啲動作 (或者某啲情況) 完全冇 call
    // onStopActionResult() —— 呢個 callback 係咪一定會嚟, 完全係人哋閉源
    // service 嘅行為, 我哋控制唔到, 亦唔應該假設佢一定可靠。之前個做法 (淨係
    // 靠 onStopActionResult() 嚟 actionInFlight.set(false)) 就會喺呢種情況永久
    // 卡死 —— 一個動作嘅 callback 冧咗, 就令之後成個 app 嘅所有 action/play
    // 都俾呢個 guard 永久拒絕。加一個 safety-release timer: 送出
    // svc.playAction() 之後最多等 SAFETY_RELEASE_MS, 如果之前都仲未收到
    // onStopActionResult(), 就自己強制放行 (但唔會扮個動作「成功完成」— 只係
    // 唔再阻住之後嘅 action/play)。呢個時間要夠長, 唔可以令一個正常慢動作
    // (例如跳舞) 都俾誤判做「卡咗」而提早放行, 但又要夠短, 唔可以等到用家覺得
    // 個 app 死咗——20 秒經驗上比大部分內建動作嘅播放時間長, 亦同
    // blockly-run.js 個 ACTION_GATE_SAFETY_TIMEOUT_MS 一致 (兩層保險用返同一個
    // 數值, 行為易理解)。
    private static final long ACTION_SAFETY_RELEASE_MS = 20000;
    private final AtomicBoolean actionInFlight = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> actionSafetyRelease = new AtomicReference<>();
    private final ScheduledExecutorService actionSafetyExecutor = Executors.newSingleThreadScheduledExecutor();

    /** 送出 action/play 之前叫呢個, 排定一個「最多等 ACTION_SAFETY_RELEASE_MS」
     *  嘅保險放行。如果之後真係收到 onStopActionResult() (或者 action/stop 主動
     *  停咗), 要叫 cancelActionSafetyRelease() 取消呢個排程, 唔使白等成 20 秒
     *  先至可以再播下一個動作。 */
    private void scheduleActionSafetyRelease() {
        ScheduledFuture<?> future = actionSafetyExecutor.schedule(() -> {
            if (actionInFlight.compareAndSet(true, false)) {
                Log.w(TAG, "action/play safety-release fired after " + ACTION_SAFETY_RELEASE_MS
                        + "ms without onStopActionResult() - UBTECH IActionService likely didn't call back");
            }
        }, ACTION_SAFETY_RELEASE_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = actionSafetyRelease.getAndSet(future);
        if (previous != null) previous.cancel(false);
    }

    private void cancelActionSafetyRelease() {
        ScheduledFuture<?> future = actionSafetyRelease.getAndSet(null);
        if (future != null) future.cancel(false);
    }

    /** A TTS language option: langTag is what gets sent back to speak(text, langTag) /
     *  setLanguage(), displayName is what the UI shows the user - built server-side via
     *  Locale.getDisplayName() (see MainActivity's listLanguages() impl) so the browser
     *  never has to maintain its own tag->name lookup table, which is exactly the kind
     *  of thing that silently misses languages the hand-maintained table wasn't updated
     *  for (see git history: a JS-side Chinese-name lookup table here previously
     *  covered maybe 40 languages and left everything else showing as a raw tag). */
    public static final class TtsLanguageOption {
        public final String langTag;
        public final String displayName;
        public TtsLanguageOption(String langTag, String displayName) {
            this.langTag = langTag;
            this.displayName = displayName;
        }
    }

    /** Lynx's UI only exposes Android's own system TTS (no robot-side Nuance/iFlytek
     *  engine picker like Alpha2's tab has) - speak()/stop()/listEngines()/setEngine()/
     *  listLanguages() defer to MainActivity's existing androidTts instance (same one
     *  Alpha2's "android" engine option uses) instead of calling
     *  robot.speech_onPlayCallback(), so both tabs share one TTS setup rather than
     *  duplicating TextToSpeech init here. Picking order matches how Android itself
     *  frames it (Settings > text-to-speech: pick engine, then pick a language that
     *  engine supports) - engine first, then listLanguages() reflects whatever engine
     *  is currently selected. */
    public interface AndroidTtsHandler {
        /** @param langTag BCP-47 tag (e.g. "zh-HK", "en-US") or null/empty to leave the
         *                 engine's current language as-is. Returns false if the tag
         *                 isn't installed/supported - caller should surface that as an
         *                 error rather than silently speaking in the wrong language. */
        boolean speak(String text, String langTag);
        void stop();
        /** Every language the *currently selected* engine has installed, sorted by
         *  display name. Empty (not null) if the engine isn't ready yet or reports
         *  none. @param uiLang "zh" or "en" - controls which language displayName
         *  comes back in (matches the Lynx UI's own language toggle, so the TTS
         *  language picker reads in whichever language the rest of the page is in). */
        List<TtsLanguageOption> listLanguages(String uiLang);
        /** Android package names of every TTS engine installed on this device (e.g.
         *  "com.google.android.tts"), sorted. Empty (not null) if none installed. */
        List<String> listEngines();
        /** Switches to the named engine package (from listEngines()) - tears down and
         *  rebuilds the TextToSpeech instance bound to that engine, since Android has no
         *  "just switch engine on the existing instance" API. Returns false if the
         *  switch itself couldn't even be kicked off (engine package invalid); the
         *  actual ready/not-ready result arrives asynchronously the same way the very
         *  first androidTtsReady flip does - callers should treat a subsequent speak()
         *  failing as "still switching, try again shortly" rather than a hard error. */
        boolean setEngine(String enginePackage);
        /** Package name of the engine currently in use, or "" if androidTts was never
         *  successfully initialised yet (e.g. still switching, or no engine at all). */
        String currentEngine();
    }

    private final SharedHardwareHandler sharedHardware;
    private final AndroidTtsHandler androidTts;
    private final PirAlertHandler pirAlert;

    public LynxController(Context context, SharedHardwareHandler sharedHardware, AndroidTtsHandler androidTts, PirAlertHandler pirAlert) {
        this.robot = new LynxRobotApi(context.getApplicationContext());
        this.sharedHardware = sharedHardware;
        this.androidTts = androidTts;
        this.pirAlert = pirAlert;
    }

    /** Endpoints that are pure Android hardware access, identical regardless of which
     *  AIDL backend the browser has selected - not implemented in LynxRobotApi/AlphaRobotApi
     *  at all, so unconditionally deferred to sharedHardware (see its javadoc). */
    private static boolean isSharedHardwarePath(String path) {
        return path.startsWith("camera/") || path.startsWith("audio/testtone")
                || path.startsWith("audio/diagnose") || path.startsWith("audio/play/")
                || path.startsWith("audio/volume/") || path.startsWith("accelerometer/");
    }

    /** Routes "/api/lynx/<name>" calls. Runs on an HttpServer worker thread. */
    public HttpServer.ApiResponse handle(String path, Map<String, String> query, String method, String body) {
        if (isSharedHardwarePath(path) && sharedHardware != null) {
            return sharedHardware.handle(path, query, method, body);
        }
        switch (path) {
            case "status":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"backend\":\"lynx\"}");

            // -- Action -----------------------------------------------------------------
            case "action/list":
                return actionList();
            case "action/play": {
                if (!actionInFlight.compareAndSet(false, true)) {
                    // 已經有一個 action/play 未收到 callback (onPlayActionResult /
                    // onStopActionResult) 就返嚟緊 —— 唔再送多一個 svc.playAction()
                    // 落去卡住個 Binder thread, 直接拒絕, 等前端即刻見到錯誤。
                    return HttpServer.ApiResponse.ok(
                            "{\"ok\":false,\"code\":\"API_ERROR_BUSY\",\"reason\":\"action already in flight\"}");
                }
                UbxErrorCode.API_ERROR_CODE code = robot.action_playAction(require(query, "name"), new IActionResultListener.Stub() {
                    @Override
                    public void onPlayActionResult(int code, int progress) {
                        EventBus.get().publish("lynx_action_progress",
                                "{\"code\":" + code + ",\"progress\":" + progress + "}");
                    }

                    @Override
                    public void onStopActionResult(int code) {
                        actionInFlight.set(false);
                        cancelActionSafetyRelease(); // 真正收到咗, 唔使再靠 20 秒保險放行
                        // 2026-08 bugfix: 之前呢度淨係發 "lynx_action_stop", 但
                        // blockly-run.js 嘅 playActionAndMaybeWait() (「等待完成」
                        // 呢個功能) 一直淨係監聽緊 "action_stop" (同 Alpha2 嗰邊
                        // RobotEventReceiver 發嘅 channel 名一致) —— 兩個名唔一樣,
                        // 令「等待完成」喺 Lynx 度永遠等唔到呢個 event, 一路等到
                        // 15 秒 timeout 先放行。用家嘅 log 就會見到個動作「好似」
                        // 卡咗成 15 秒先繼續落一個 block, 而如果程式好快再送多一個
                        // action/play (例如上面呢個 timeout 都未到, 用家手動撳咗
                        // 「執行」第二次), 就會撞到上面 actionInFlight 個 guard 話
                        // "action already in flight" —— 兩個 bug 其實同一個根源。
                        //
                        // 修法: 兩個 channel 名都發 (唔係淨係改名) —— "lynx_action_stop"
                        // 呢個 app-log.js 主控制面板嘅 Lynx 動作分頁 (#lynxActionStatus
                        // 狀態文字) 仲用緊, 唔可以淨係改名令佢冇晒訊號; "action_stop"
                        // 就係新加嘅, 等 Blockly 嗰段「等待完成」邏輯兩個 backend 都
                        // 真正 work 得到, 同 Alpha2 用緊嘅 channel 名對齊。
                        EventBus.get().publish("lynx_action_stop", "{\"code\":" + code + "}");
                        EventBus.get().publish("action_stop", "{\"code\":" + code + "}");
                    }
                });
                if (isOk(code)) {
                    // 送出成功先至排「保險放行」—— 如果 svc.playAction() 本身就已經
                    // 送唔出 (見下面 !isOk(code) 分支), 就唔會再有任何 callback,
                    // 冇必要排一個 20 秒之後先執行、屆時已經冇意義嘅 timer。
                    scheduleActionSafetyRelease();
                } else {
                    actionInFlight.set(false);
                }
                return codeResponse(code);
            }
            case "action/stop":
                return codeResponse(robot.action_stopAction(new IActionResultListener.Stub() {
                    @Override
                    public void onPlayActionResult(int code, int progress) {
                        EventBus.get().publish("lynx_action_progress",
                                "{\"code\":" + code + ",\"progress\":" + progress + "}");
                    }

                    @Override
                    public void onStopActionResult(int code) {
                        // 主動 stop 都算「呢個 action slot 用完」, 一樣要放行, 否則
                        // 用家撳咗停但個 guard 仲當緊有嘢喺度播緊, 下一個 action/play
                        // 會被誤拒。兩個 channel 都發, 理由同上面 case "action/play"
                        // 一樣 (app-log.js 主控制面板仲用緊 "lynx_action_stop",
                        // 唔可以淨係改名; "action_stop" 就係俾 Blockly 用嘅新增)。
                        actionInFlight.set(false);
                        cancelActionSafetyRelease(); // 真正收到咗, 唔使再靠 20 秒保險放行
                        EventBus.get().publish("lynx_action_stop", "{\"code\":" + code + "}");
                        EventBus.get().publish("action_stop", "{\"code\":" + code + "}");
                    }
                }));

            // -- Motor (Lynx's equivalent of Alpha2's chest/header servo panel) --------
            case "motor/list":
                return motorList();
            case "motor/move_absolute": {
                final int id = Integer.parseInt(require(query, "id"));
                int angle = Integer.parseInt(require(query, "angle"));
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                return codeResponse(robot.motor_moveToAbsoluteAngle(id, angle, timeMs, new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int motorId, int finalAngle, int code) {
                        // Publishes the requested id, not the callback's own motorId
                        // echo - see motor/read's onReadMotorAngle() below for why
                        // (user-confirmed the echoed id can come back as garbage under
                        // load; same AIDL callback shape here, so same precaution
                        // applies even though this particular endpoint isn't currently
                        // fired in a tight loop the way motor/read is from
                        // lynxReadAllServoAngles()).
                        EventBus.get().publish("lynx_motor_move",
                                "{\"id\":" + id + ",\"angle\":" + finalAngle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/move_ref": {
                final int id = Integer.parseInt(require(query, "id"));
                int delta = Integer.parseInt(require(query, "delta"));
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                return codeResponse(robot.motor_moveRefAngle(id, delta, timeMs, new IMotorMoveAngleResultListener.Stub() {
                    @Override
                    public void onMoveAngle(int motorId, int finalAngle, int code) {
                        // Same precaution as motor/move_absolute above.
                        EventBus.get().publish("lynx_motor_move",
                                "{\"id\":" + id + ",\"angle\":" + finalAngle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/read": {
                final int id = Integer.parseInt(require(query, "id"));
                boolean fromHardware = Boolean.parseBoolean(queryOrDefault(query, "hardware", "true"));
                return codeResponse(robot.motor_readAbsoluteAngle(id, fromHardware, new IMotorReadAngleListener.Stub() {
                    @Override
                    public void onReadMotorAngle(int motorId, int angle, int code) {
                        // Publishes the REQUESTED id (captured above), not the callback's
                        // own motorId echo - user-confirmed on real hardware: firing many
                        // motor/read calls back-to-back (see lynxReadAllServoAngles(), 20
                        // in quick succession) makes onReadMotorAngle()'s motorId param
                        // come back as garbage (observed values like 274-293, nowhere
                        // near the requested 1-20 range, alongside nonsense code values
                        // like 120/144/-1) - this looks like a firmware-side bug in how
                        // it tracks which read a given callback belongs to under load,
                        // not anything under this SDK's control. Since each HTTP request
                        // here only ever asks about one motor, "id" is already known with
                        // certainty from the request itself - no reason to trust a value
                        // the robot is demonstrably capable of getting wrong when busy.
                        EventBus.get().publish("lynx_motor_angle",
                                "{\"id\":" + id + ",\"angle\":" + angle + ",\"code\":" + code + "}");
                    }
                }));
            }
            case "motor/set_all": {
                // "id:angle,id:angle,..." e.g. "1:90,2:120"
                String csv = require(query, "angles");
                long timeMs = Long.parseLong(queryOrDefault(query, "time", "1000"));
                String[] parts = csv.split(",");
                MotorAngle[] angles = new MotorAngle[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    String[] kv = parts[i].trim().split(":");
                    if (kv.length != 2) {
                        throw new IllegalArgumentException(
                                "invalid angles entry (expected id:angle): \"" + parts[i].trim() + "\"");
                    }
                    angles[i] = new MotorAngle(Integer.parseInt(kv[0].trim()), Integer.parseInt(kv[1].trim()));
                }
                return codeResponse(robot.motor_setAllMotorAbsoluteAngle(angles, timeMs, new IMotorSetAllAngleResultListener.Stub() {
                    @Override
                    public void onSetAllAngle(int code, int extra) {
                        EventBus.get().publish("lynx_motor_set_all", "{\"code\":" + code + ",\"extra\":" + extra + "}");
                    }
                }));
            }
            case "motor/power_save":
                return codeResponse(robot.motor_setPowerSaveMode(Boolean.parseBoolean(require(query, "on"))));

            // -- LED --------------------------------------------------------------------
            case "led/list":
                return ledList();
            case "led/eye/on":
                return codeResponse(robot.led_turnOnEye(Integer.parseInt(queryOrDefault(query, "color", "1")), ledOpListener("eye_on")));
            case "led/eye/off":
                return codeResponse(robot.led_turnOffEye(ledOpListener("eye_off")));
            case "led/eye/blink":
                return codeResponse(robot.led_turnOnEyeBlink(ledOpListener("eye_blink")));
            case "led/eye/flash":
                return codeResponse(robot.led_turnOnEyeFlash(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("eye_flash")));
            case "led/eye/marquee":
                return codeResponse(robot.led_turnOnEyeMarquee(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("eye_marquee")));
            case "led/head/on":
                return codeResponse(robot.led_turnOnHead(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        ledOpListener("head_on")));
            case "led/head/off":
                return codeResponse(robot.led_turnOffHead(ledOpListener("head_off")));
            case "led/head/flash":
                return codeResponse(robot.led_turnOnHeadFlash(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_flash")));
            case "led/head/marquee":
                return codeResponse(robot.led_turnOnHeadMarquee(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_marquee")));
            case "led/head/breath":
                return codeResponse(robot.led_turnOnHeadBreath(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        Integer.parseInt(queryOrDefault(query, "p3", "0")),
                        ledOpListener("head_breath")));
            case "led/mouth/on":
                return codeResponse(robot.led_turnOnMouth(Integer.parseInt(queryOrDefault(query, "p0", "0")), ledOpListener("mouth_on")));
            case "led/mouth/off":
                return codeResponse(robot.led_turnOffMouth(ledOpListener("mouth_off")));
            case "led/mouth/breath":
                return codeResponse(robot.led_turnOnMouthBreath(
                        Integer.parseInt(queryOrDefault(query, "p0", "0")),
                        Integer.parseInt(queryOrDefault(query, "p1", "0")),
                        Integer.parseInt(queryOrDefault(query, "p2", "0")),
                        ledOpListener("mouth_breath")));
            case "led/wifi/on":
                return codeResponse(robot.led_turnOnWifi(Integer.parseInt(queryOrDefault(query, "p0", "0")), ledOpListener("wifi_on")));
            case "led/wifi/off":
                return codeResponse(robot.led_turnOffWifi(ledOpListener("wifi_off")));
            case "led/chest/on":
                return codeResponse(robot.led_turnOnChestLed(ledOpListener("chest_on")));
            case "led/chest/off":
                return codeResponse(robot.led_turnOffChestLed(ledOpListener("chest_off")));

            // -- Speech / TTS -------------------------------------------------------------
            // Lynx UI uses Android's built-in system TTS exclusively (same androidTts
            // instance MainActivity's Alpha2 "android" engine option uses) instead of the
            // robot's own speech_onPlayCallback() AIDL path - pick engine first (this
            // device may have more than one TTS engine installed), then a language that
            // engine supports (BCP-47 tag, e.g. "zh-HK"/"en-US", from speech/tts_languages,
            // which reflects whichever engine is currently selected). ASR is not exposed
            // on this tab.
            case "speech/tts": {
                String text = require(query, "text");
                String lang = query.get("lang"); // null/empty = leave engine's current language
                if (androidTts == null || !androidTts.speak(text, lang)) {
                    return HttpServer.ApiResponse.error("Android TTS not ready or language not supported");
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "speech/stop":
                if (androidTts != null) {
                    androidTts.stop();
                }
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            case "speech/tts_languages": {
                // ui_lang ("zh"/"en") controls which language the display names come
                // back in - see AndroidTtsHandler#listLanguages(String uiLang).
                boolean english = "en".equals(query.get("ui_lang"));
                List<TtsLanguageOption> langs = androidTts != null ? androidTts.listLanguages(english ? "en" : "zh") : java.util.Collections.<TtsLanguageOption>emptyList();
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"languages\":[");
                for (int i = 0; i < langs.size(); i++) {
                    if (i > 0) sb.append(',');
                    TtsLanguageOption opt = langs.get(i);
                    sb.append("{\"tag\":\"").append(jsonSafe(opt.langTag))
                      .append("\",\"name\":\"").append(jsonSafe(opt.displayName)).append("\"}");
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            case "speech/tts_engines": {
                List<String> engines = androidTts != null ? androidTts.listEngines() : java.util.Collections.<String>emptyList();
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"engines\":[");
                for (int i = 0; i < engines.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(jsonSafe(engines.get(i))).append('"');
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }
            case "speech/set_tts_engine": {
                String enginePkg = require(query, "engine");
                if (androidTts == null || !androidTts.setEngine(enginePkg)) {
                    return HttpServer.ApiResponse.error("failed to switch TTS engine");
                }
                // Switch itself is async (new TextToSpeech instance re-binds to the
                // target engine's Service) - see AndroidTtsHandler#setEngine() javadoc -
                // so this "ok" only means the switch was kicked off, not that it's ready
                // yet. Browser side should re-poll speech/cur_tts_engine / re-load
                // speech/tts_languages after a short delay rather than assume it's
                // instant.
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "speech/cur_tts_engine": {
                String cur = androidTts != null ? androidTts.currentEngine() : "";
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"engine\":\"" + jsonSafe(cur) + "\"}");
            }

            // 2026-08 新增: startRecording()/stopRecording() 係 ISpeechInterface 入面
            // 反編譯確認過真身有效嘅兩個 method (唔似 startSpeechAsr()/onPlayCallback()
            // 咁全部落地喺 SpeechServicesImpl$1 空 stub) —— SpeechServiceProxy$BinderStub
            // 呢兩個 method 直接掛住機身自己嗰個 sl (TencentAIAudioRecognizer) singleton,
            // 開機後幾秒左右由 AlphaApplication 自動 requestAudioFocus/開始錄音, 為機身
            // 自己嘅 wake-word/語音功能長開住 system mic。純粹用嚟測試呢兩個 call 會唔會
            // 令機身側釋放/重新攞返個 mic session (例如令 androidTts 或者呢個 App 自己
            // 開嘅 AudioRecord 唔再撞板) —— 唔係 ASR, 唔會有任何辨識結果送返嚟。詳見
            // docs/AIDL_GUIDE_LYNX.md「4. Speech」章節。
            case "speech/start_recording":
                return codeResponse(robot.speech_startRecording());
            case "speech/stop_recording":
                return codeResponse(robot.speech_stopRecording());

            // -- Sys ----------------------------------------------------------------------
            case "sys/sid":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"sid\":\"" + jsonSafe(robot.sys_getSid()) + "\"}");
            case "sys/mic_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getMICVersion()) + "\"}");
            case "sys/chest_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getChestVersion()) + "\"}");
            case "sys/head_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getHeadVersion()) + "\"}");
            case "sys/battery_version":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"version\":\"" + jsonSafe(robot.sys_getBatteryVersion()) + "\"}");
            case "sys/is_charging":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"charging\":" + robot.sys_isPowerCharging() + "}");
            case "sys/power_value":
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"value\":" + robot.sys_getPowerValue() + "}");
            case "sys/pir": {
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                return codeResponse(robot.sys_setPIRSensor(enabled, new IRemotePIRSensorOperationResultListener.Stub() {
                    @Override
                    public void onPIRSensorOpResult(int code) {
                        EventBus.get().publish("lynx_pir", "{\"code\":" + code + "}");
                    }
                }));
            }
            // 獨立於 sys/pir 呢個感應器硬件開關 (見 sys/pir 個 comment) - 呢個淨係開關
            // MainActivity 收到 PIR_STATE broadcast 之後會唔會閃紅燈/播 Heaven 鈴聲。
            case "sys/pir_alert_enabled": {
                boolean enabled = Boolean.parseBoolean(require(query, "on"));
                pirAlert.setEnabled(enabled);
                return HttpServer.ApiResponse.ok("{\"ok\":true}");
            }
            case "sys/alarms": {
                String key = queryOrDefault(query, "key", "webpanel");
                AlarmInfo[] alarms = robot.sys_queryAllAlarm(key);
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"alarms\":[");
                if (alarms != null) {
                    for (int i = 0; i < alarms.length; i++) {
                        if (i > 0) sb.append(',');
                        AlarmInfo a = alarms[i];
                        sb.append("{\"id\":").append(a.id).append(",\"hh\":").append(a.hh)
                                .append(",\"mm\":").append(a.mm).append(",\"label\":\"").append(jsonSafe(a.label)).append("\"}");
                    }
                }
                sb.append("]}");
                return HttpServer.ApiResponse.ok(sb.toString());
            }

            default:
                return new HttpServer.ApiResponse(404, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"unknown lynx endpoint: " + path + "\"}");
        }
    }

    // ------------------------------------------------------------------

    private HttpServer.ApiResponse actionList() {
        UbxErrorCode.API_ERROR_CODE result = robot.action_getActionList(new IActionListResultListener.Stub() {
            @Override
            public void onGetActionList(int code, int total, ActionInfo[] actions) {
                // Field names (id/type/nameCn/nameEn) match Alpha2's actionList() JSON
                // contract exactly (see MainActivity#actionList()) so the browser's
                // category/language logic (ACTION_CATEGORIES, displayNameOf(), etc.) can
                // be shared verbatim between both backends - same hardware, same action
                // categories, just a different AIDL surface underneath.
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"actions\":[");
                if (actions != null) {
                    for (int i = 0; i < actions.length; i++) {
                        if (i > 0) sb.append(',');
                        ActionInfo a = actions[i];
                        sb.append("{\"id\":\"").append(jsonSafe(a.getId())).append("\",")
                                .append("\"type\":\"").append(jsonSafe(a.getType())).append("\",")
                                .append("\"nameCn\":\"").append(jsonSafe(a.getCnName())).append("\",")
                                .append("\"nameEn\":\"").append(jsonSafe(a.getName())).append("\"}");
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_action_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private HttpServer.ApiResponse motorList() {
        UbxErrorCode.API_ERROR_CODE result = robot.motor_getMotorList(new IMotorListResultListener.Stub() {
            @Override
            public void onGetMotorList(int code, int total, MotorInfo[] motors) {
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"motors\":[");
                if (motors != null) {
                    for (int i = 0; i < motors.length; i++) {
                        if (i > 0) sb.append(',');
                        MotorInfo m = motors[i];
                        sb.append("{\"id\":").append(m.getId())
                                .append(",\"lower\":").append(m.getLowerLimitAngle())
                                .append(",\"upper\":").append(m.getUpperLimitAngle()).append('}');
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_motor_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private HttpServer.ApiResponse ledList() {
        UbxErrorCode.API_ERROR_CODE result = robot.led_getLedList(new IRemoteLedListResultListener.Stub() {
            @Override
            public void onGetLedList(int code, int total, List<LedInfo> leds) {
                StringBuilder sb = new StringBuilder("{\"code\":" + code + ",\"total\":" + total + ",\"leds\":[");
                if (leds != null) {
                    for (int i = 0; i < leds.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"').append(jsonSafe(String.valueOf(leds.get(i)))).append('"');
                    }
                }
                sb.append("]}");
                EventBus.get().publish("lynx_led_list", sb.toString());
            }
        });
        return codeResponse(result);
    }

    private IRemoteLedOperationResultListener.Stub ledOpListener(String tag) {
        return new IRemoteLedOperationResultListener.Stub() {
            @Override
            public void onLedOpResult(int code, int extra) {
                EventBus.get().publish("lynx_led_" + tag, "{\"code\":" + code + ",\"extra\":" + extra + "}");
            }
        };
    }

    // ------------------------------------------------------------------
    // Helpers - deliberately identical contract to MainActivity's private copies.
    // ------------------------------------------------------------------

    private static String require(Map<String, String> query, String key) {
        String v = query.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required parameter: " + key);
        }
        return v;
    }

    private static String queryOrDefault(Map<String, String> query, String key, String defaultValue) {
        String v = query.get(key);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    private static boolean isOk(UbxErrorCode.API_ERROR_CODE code) {
        return code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
    }

    private static HttpServer.ApiResponse codeResponse(UbxErrorCode.API_ERROR_CODE code) {
        return HttpServer.ApiResponse.ok("{\"ok\":" + isOk(code) + ",\"code\":\"" + code + "\"}");
    }

    private static String jsonSafe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
