package com.open.alpha2.iflytektest;

import android.content.Context;
import android.util.Log;

import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechUtility;

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
 *   - 未做過任何實機測試 —— 呢份code本身編唔編到、行唔行到，都仲未驗證過。
 */
public final class IflytekOfflineTest {

    private static final String TAG = "IflytekOfflineTest";

    private static SpeechRecognizer sRecognizer;
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
        } catch (Throwable t) {
            log("init() threw: " + t);
        }
    }

    /** 開始聽。結果/錯誤全部經 RecognizerListener 記落 log。 */
    public static synchronized void startListening(Context context) {
        if (sRecognizer == null) {
            log("startListening() called but recognizer is null — call init() first");
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
