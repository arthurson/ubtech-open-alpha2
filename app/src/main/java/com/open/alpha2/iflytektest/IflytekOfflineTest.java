package com.open.alpha2.iflytektest;

import android.content.Context;
import android.util.Log;

import com.iflytek.cloud.GrammarListener;
import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechUtility;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 2026-08 新增: 獨立、隔離嘅 iFlytek MSC offline 測試。
 *
 * 目的: 喺 open-alpha2 自己個 process 度，完全唔碰 alpha2services.apk，
 * 獨立起一份 SpeechUtility/SpeechRecognizer instance，用嚟做「斷晒網，
 * 講嘢，睇識別結果係咪空白」呢個測試 —— Arthur 已經確認呢個係佢想要嘅
 * 判準，唔需要再糾纏喺 log tag / engine_mode 判斷式嗰層。
 *
 * 用法 (經 MainActivity 嘅 /api/iflytektest/* 幾個 endpoint 觸發):
 *   1. POST /api/iflytektest/init   -> 起 SpeechUtility + SpeechRecognizer
 *   2. POST /api/iflytektest/start  -> 開始聽 (startListening)
 *   3. GET  /api/iflytektest/log    -> 攞返所有 callback 記錄 (JSON array of string)
 *   4. POST /api/iflytektest/stop   -> 停止/destroy
 *
 * 已知缺口 (未驗證):
 *   - login 用嘅 appid/login 參數呢度冇設 (未抄 com.ubtechinc.iflytek.speech.a.a
 *     嗰組邏輯，嗰個 class 屬於 com.ubtechinc.* package，冇包含喺
 *     iflytek-msc-core.jar 入面)。如果 QMSPLogin 要求特定 appid 先俾行，
 *     呢個測試可能連 SpeechUtility.createUtility() 都會失敗 —— 呢個本身
 *     已經係一個有用嘅資訊 (見 getLog() 會記錄 onInit 嘅 code)。
 *   - engine_type 呢度直接寫死做 "local" + engine_mode="msc"，即係強制
 *     行 MSC (native/local) 分支，唔會 fallback 去 AIDL/cloud —— 如果
 *     MSC.isLoaded()==false (例如 libmsc.so 冇成功 load)，createRecognizer()
 *     會 silently 建立一個冇 local engine 嘅 instance，之後 startListening()
 *     大機會直接攞唔到結果，呢個都會反映喺 log 度。
 *   - [2026-08 已修正] 第一版 jar 淨係抽咗 com/iflytek/{cloud,msc,common,speech}
 *     四個 package，漏咗 com.iflytek.common.a.* 反過嚟依賴嘅 com.b.a
 *     (MobileAgent) + com.a.a.a + com.a.b.{a..g}，實機一 call
 *     createUtility() 就 NoClassDefFoundError: Lcom/b/a; 。已用遞歸依賴
 *     掃描確認閉包完整，見 app/build.gradle 嘅 dependencies 註解。
 *   - [2026-08 已修正] 實機測試證實 engine_type=local 生效、麥克風/VAD 完全
 *     正常 (onBeginOfSpeech -> EVENT_SPEECH_START(22002) ->
 *     EVENT_RECORD_STOP(22003) -> EVENT_SESSION_ID(20001))，但一直
 *     onError(23002, "本地引擎错误")。原因: 淨係 setParameter
 *     ("local_grammar","call")，冇實際 call buildGrammar() 將
 *     assets/call.bnf 編譯成語法。已加返 buildGrammar() 呢一步 (async，
 *     結果經 onBuildFinish() 記落 log，startListening() 而家會檢查
 *     sGrammarReady 先郁)。未實機驗證過呢個修正。
 */
public final class IflytekOfflineTest {

    private static final String TAG = "IflytekOfflineTest";

    private static SpeechRecognizer sRecognizer;
    private static volatile boolean sGrammarReady = false;
    private static volatile String sGrammarId = null;
    private static final List<String> sLog = new ArrayList<>();
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private IflytekOfflineTest() {
    }

    private static synchronized void log(String msg) {
        String line = "[" + TIME_FMT.format(new Date()) + "] " + msg;
        Log.i(TAG, line);
        sLog.add(line);
        // 避免呢個 buffer 無限脹大
        if (sLog.size() > 500) {
            sLog.remove(0);
        }
    }

    /** 攞返成個 log buffer 嘅快照 (由舊到新)。 */
    public static synchronized List<String> getLog() {
        return new ArrayList<>(sLog);
    }

    public static synchronized void clearLog() {
        sLog.clear();
    }

    /**
     * 起 SpeechUtility + SpeechRecognizer，engine_type 強制 local。
     * 呢個 method 本身唔會擋住/處理任何 exception —— 有咩錯就直接記落
     * log 度，等你喺 /api/iflytektest/log 度睇到完整嘅失敗原因，而唔係
     * 靜靜哋吞咗。
     */
    public static synchronized void init(Context context) {
        log("init() called");
        try {
            SpeechUtility utility = SpeechUtility.createUtility(context, "appid=56652373");
            if (utility == null) {
                log("SpeechUtility.createUtility() returned null "
                        + "(most likely SpeechUtility.a(Context) process-check failed, "
                        + "or force_login param missing — see class-level javadoc)");
                return;
            }
            log("SpeechUtility.createUtility() OK");

            sRecognizer = SpeechRecognizer.createRecognizer(context, new InitListener() {
                @Override
                public void onInit(int code) {
                    // 注意: 原廠 com.ubtechinc.iflytek.speech.b 嘅 onInit 完全冇檢查
                    // 呢個 code (見之前拆解)。呢度特登检查, 等你可以喺 log 度直接
                    // 睇到 init 成唔成功, 唔使再靠估。
                    log("InitListener.onInit(" + code + ")"
                            + (code == 0 ? " -- SUCCESS" : " -- FAILURE"));
                }
            });
            if (sRecognizer == null) {
                log("SpeechRecognizer.createRecognizer() returned null");
                return;
            }
            // engine_type=local: 對應原廠 com.ubtechinc.iflytek.speech.b 入面
            // 已經確認嘅 local grammar 比對路徑; engine_mode=msc: 直接跳過
            // com.iflytek.cloud.a.f.d.a("asr", ...) 裁判式嘅所有中間判斷,
            // 強制揀 MSC (native/local), 唔會因為任何原因 fallback 去
            // PLUS (AIDL/cloud) — 見 class-level javadoc 對呢個裁判式嘅
            // 完整拆解。
            sRecognizer.setParameter("engine_type", "local");
            sRecognizer.setParameter("engine_mode", "msc");
            sRecognizer.setParameter("asr_res_path", "assets:///asr/common.jet");
            sRecognizer.setParameter("grm_build_path",
                    context.getFilesDir().getAbsolutePath() + "/grammar");
            sRecognizer.setParameter("local_grammar", "call");
            sRecognizer.setParameter("result_type", "json");
            sRecognizer.setParameter("vad_bos", "4000");
            sRecognizer.setParameter("vad_eos", "1000");
            log("SpeechRecognizer created, engine_type=local, engine_mode=msc");

            // [2026-08 新增，修正 code=23002 "本地引擎错误"] 第一輪實機測試證實
            // engine_type=local 已經生效、麥克風/VAD 都行得完全正常
            // (onBeginOfSpeech -> EVENT_SPEECH_START(22002) -> EVENT_RECORD_STOP(22003)
            // -> EVENT_SESSION_ID(20001))，但每次都跟住即刻 onError(23002)。原因:
            // 之前呢個 init() 淨係 setParameter("local_grammar", "call")，但完全冇
            // 實際 call 過 buildGrammar() —— local grammar 引擎要求你必先用
            // buildGrammar() 將 assets/call.bnf 嘅內容編譯成一個語法 (對應
            // grm_build_path 嗰個路徑)，先俾 startListening() 用得。冇呢一步，
            // native 層攞唔到已編譯嘅語法，就係 "本地引擎錯誤" 嘅直接成因。
            //
            // buildGrammar(grammarType, grammarContent, listener) 嘅 grammarType
            // 呢度用 "abnf" (對應原廠 assets 入面 call.bnf 用嘅 BNF 格式)；
            // grammarContent 唔係路徑，係要求傳入成份 .bnf 檔嘅實際文字內容。
            String grammarContent = readAssetAsString(context, "call.bnf");
            if (grammarContent == null) {
                log("readAssetAsString(call.bnf) failed — cannot buildGrammar, "
                        + "startListening() will not be attempted");
                return;
            }
            int buildRet = sRecognizer.buildGrammar("abnf", grammarContent, new GrammarListener() {
                @Override
                public void onBuildFinish(String grammarId, SpeechError error) {
                    if (error != null) {
                        log("onBuildFinish() FAILED code=" + error.getErrorCode()
                                + " desc=" + error.getErrorDescription());
                        sGrammarReady = false;
                        return;
                    }
                    log("onBuildFinish() OK grammarId=" + grammarId);
                    sGrammarId = grammarId;
                    sGrammarReady = true;
                }
            });
            log("buildGrammar() called, return code=" + buildRet
                    + " (result comes async via onBuildFinish — call log again after a moment)");
        } catch (Throwable t) {
            log("init() threw: " + t);
        }
    }

    /** 由 assets/ 讀一個文字檔做成 String (UTF-8)。讀唔到就記落 log 並返 null。 */
    private static String readAssetAsString(Context context, String assetName) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(assetName), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } catch (Throwable t) {
            log("readAssetAsString(" + assetName + ") threw: " + t);
            return null;
        }
        return sb.toString();
    }

    /** 開始聽。結果/錯誤全部經 RecognizerListener 記落 log。 */
    public static synchronized void startListening(Context context) {
        if (sRecognizer == null) {
            log("startListening() called but recognizer is null — call init() first");
            return;
        }
        if (!sGrammarReady) {
            log("startListening() called but grammar not ready yet "
                    + "(buildGrammar() is async — check log for onBuildFinish() first, "
                    + "or call init() again if you haven't)");
            return;
        }
        try {
            int ret = sRecognizer.startListening(new RecognizerListener() {
                @Override
                public void onBeginOfSpeech() {
                    log("onBeginOfSpeech()");
                }

                @Override
                public void onEndOfSpeech() {
                    log("onEndOfSpeech()");
                }

                @Override
                public void onError(SpeechError error) {
                    log("onError() code=" + error.getErrorCode()
                            + " desc=" + error.getErrorDescription());
                }

                @Override
                public void onEvent(int eventType, int arg1, int arg2, android.os.Bundle bundle) {
                    log("onEvent(eventType=" + eventType + ", arg1=" + arg1 + ", arg2=" + arg2 + ")");
                }

                @Override
                public void onResult(RecognizerResult result, boolean isLast) {
                    String text = result == null ? null : result.getResultString();
                    boolean isEmpty = text == null || text.trim().isEmpty();
                    log("onResult() isLast=" + isLast
                            + " isEmpty=" + isEmpty
                            + " text=" + (text == null ? "null" : text));
                }

                @Override
                public void onVolumeChanged(int volume, byte[] data) {
                    // 太嘈, 唔 log 呢個, 但保留 method 等 interface 齊全
                }
            });
            log("startListening() called, return code=" + ret);
        } catch (Throwable t) {
            log("startListening() threw: " + t);
        }
    }

    public static synchronized void stop() {
        if (sRecognizer == null) {
            log("stop() called but recognizer is null");
            return;
        }
        try {
            sRecognizer.stopListening();
            log("stopListening() called");
        } catch (Throwable t) {
            log("stop() threw: " + t);
        }
    }

    public static synchronized void destroy() {
        if (sRecognizer != null) {
            try {
                sRecognizer.destroy();
                log("destroy() called");
            } catch (Throwable t) {
                log("destroy() threw: " + t);
            }
            sRecognizer = null;
        }
    }
}
