package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * 離線文法包：本地 BNF 文法生命週期 (doInit/doStart/doStop)＋
 * connectivity 觸發自動切換 (probe/apply/cooldown)＋wakeup probe＋
 * speech/offline_auto_switch、get_default_grammar 兩個 endpoint＋
 * CONNECTIVITY_ACTION receiver。
 *
 * 注意：機身已無 alpha2services，robot.speech_*Grammar() 全部即時回 NOT_INIT
 * （見 RobotStub），listener 永遠唔會 callback——state 機照行（latch/gate
 * 照守），只係永遠唔會真正入離線模式。
 * 擁有關係：
 * - MainActivity 只留：triggerWakeupProbe static 薄 shim（RobotEventReceiver
 *   經佢入）、接線（onCreate 建構＋register、onDestroy unregister）。
 * - ApiDispatcher 經下面 offlineAutoSwitch/getDefaultGrammar 直調。
 */
public final class GrammarCenter {
    private static final String TAG = "GrammarCenter";

    private final Context appContext;
    private final RobotStub robot;
    private final XiaozhiBridge xiaozhiBridge;

    public GrammarCenter(Context context, RobotStub robot, XiaozhiBridge xiaozhiBridge) {
        this.appContext = context.getApplicationContext();
        this.robot = robot;
        this.xiaozhiBridge = xiaozhiBridge;
        // 「自動跟網絡切換」偏好 (預設開)。
        offlineGrammarAutoSwitch = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_OFFLINE_AUTO, true);
    }

    /** onDestroy 共用：guard (IllegalArgumentException)。 */
    public void unregisterConnectivityReceiver() {
        try {
            appContext.unregisterReceiver(connectivityReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** 離線文法辨識 (本地 BNF grammar) 模式現在開不開。
     *  開了之後, 機身 alpha2services 會用 engine_type=local + APK 裡面的
     *  assets/asr/common.jet 離線資源做本地文法辨識 (完全不用上網), 辨識結果
     *  經 grammar listener 這條路徑回來。同時 onServerCallBack() 那條正常聽寫
     *  路徑會被 gate 住 - 因為 mSpeechServiceUtil 和 mAsrServiceUtil 是兩個
     *  獨立 binding, firmware 有機會將同一句結果派給兩邊, 如果兩邊都各自
     *  觸發語意配對 + TTS, 就會重複答兩次。只有 grammar listener 一條路徑會觸發回應。 */
    private volatile boolean offlineGrammarActive = false;

    /** 最後一次 speech/init_grammar 的機身構建結果 - errorCode==0
     *  才算成功。speech/start_grammar 會用它做 gate: 文法未構建成功就開始辨識,
     *  機身會因為沒有本地 grammar 而將所有語音跌落雲端聽寫 fallback, 離線時變成
     *  「說什麼都是網路錯誤」(實測 logcat: 10114/20002), 所以這裡早一步擋住。 */
    private volatile boolean lastGrammarBuildOk = false;

    /** 「自動跟網路切換」開關 - 開了的話, 沒網路時自動入離線文法
     *  模式, 有網路時自動退出來走回雲端聽寫。偏好存 SharedPreferences (共用
     *  頂頭那個 PREFS_NAME), 預設開。 */
    public static final String PREF_OFFLINE_AUTO = "offline_grammar_auto";
    private volatile boolean offlineGrammarAutoSwitch = true;
    /** 離線文法構建中/剛構建完, 等著自動開始辨識的 pending flag - 由
     *  grammar init callback 成功之後接手做 start。 */
    private volatile boolean pendingOfflineEnable = false;
    /** init_grammar 進行中的防重入鎖 - 開機那時 speech_ready
     *  和 connectivity_change 兩個觸發可以幾乎同時到達, 疊兩次 buildGrammar
     *  會讓 firmware destroyASR 再重建, 打壞剛起好的辨識 session。 */
    private volatile boolean grammarInitInFlight = false;
    /** 最後一次模式切換時間 (ms) - 防止網路飄忽讓模式不停翻轉 (每次翻轉都
     *  會 stop/start 文法, 中間那段說話是沒反應的)。 */
    private volatile long lastModeSwitchMs = 0;
    private static final long MODE_SWITCH_MIN_INTERVAL_MS = 15000;
    /** 監察網路連線狀態 - CONNECTIVITY_ACTION 在 API 22 (這台機器) 仍是標準做法。
     *  收到廣播就在背景 thread 做真正網路探測再 applyConnectivityMode() - 探測
     *  是 blocking call (TCP connect), 不可以放到 main thread。 */
    private final android.content.BroadcastReceiver connectivityReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean online = hasRealInternet();
                            lastProbeOnline = online;
                            applyConnectivityMode(online, "connectivity_change");
                            if (online) xiaozhiBridge.maybeAutoConnect("connectivity");
                        }
                    }, "conn-probe").start();
                }
            };

    public void registerConnectivityReceiver() {
        android.content.IntentFilter filter =
                new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        appContext.registerReceiver(connectivityReceiver, filter);
    }
    /** 「從第一句對答就知道是否離線」- 喚醒詞觸發的當下 (用戶開口)
     *  立即探測一次雲端連通性。單次結果即時生效 - 用戶實際開口那一刻的證據
     *  最可信, 而且探測 (~1-7s) 和講話+辨識並行, 機器人回答時模式已經和現實
     *  一致。由 RobotEventReceiver 的 tts_hint_wakeup case 叫。
     *
     *  用即開即走嘅 plain thread
     *  (同 speech/offline_auto_switch toggle 嗰個 probeThread 同一 pattern)。
     *  一定要背景 thread (hasRealInternet() 會 block；Main thread 會彈
     *  NetworkOnMainThreadException)。只喺 auto-switch 開住先做。 */
    public void triggerWakeupProbe() {
        if (!offlineGrammarAutoSwitch) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean online = hasRealInternet();
                    if (online != lastProbeOnline) {
                        Log.i(TAG, "wakeup probe: internet " + (online ? "UP" : "DOWN")
                                + " -> switching mode now");
                        lastProbeOnline = online;
                        applyConnectivityMode(online, "wakeup");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "wakeup probe error: " + e.getMessage());
                }
            }
        }, "wakeup-probe").start();
    }
    public HttpServer.ApiResponse offlineAutoSwitch(Map<String, String> query) {
        // 自動跟網路切換開關。沒有 on 參數 = 查詢現狀;
        // 有 on=true/false = 設定 (寫入 SharedPreferences, 重啟 App 都記得),
        // 設定完即刻按目前網絡狀態套用一次。
        Boolean onOpt = ApiValidator.optionalBooleanObject(query, "on");
        if (onOpt != null) {
            boolean on = onOpt.booleanValue();
            offlineGrammarAutoSwitch = on;
            appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_OFFLINE_AUTO, on).commit();
            // 立即在背景 probe 一次並套用 - 不用等下一個 30 秒週期。
            // join 最多 10 秒等探測完才回應, 讓回應的 connected/offlineActive
            // 是新鮮結果而不是上一輪的殘值。
            Thread probeThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean online = hasRealInternet();
                    lastProbeOnline = online;
                    applyConnectivityMode(online, "toggle");
                }
            }, "conn-probe-toggle");
            probeThread.start();
            try {
                probeThread.join(10000);
            } catch (InterruptedException ignored) {
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"auto\":"
                + offlineGrammarAutoSwitch + ",\"connected\":" + lastProbeOnline
                + ",\"offlineActive\":" + offlineGrammarActive + "}");
    }
    /** onServerCallBack() 收到的 raw 字串, 在「語法識別」(grammar,
     *  logcat type:1) 路徑底下是一個未解析的本地文法 JSON, 例如
     *  {"text":"你的爸爸是谁啊","rc":4}, 而不是純文字 (純文字是「聽寫識別」
     *  dictation, type:0, 那條路徑才有的格式)。這個 method 判斷輸入是否這種
     *  JSON 格式, 是的話就抽出 text field, 不是 (或 parse 失敗/text field
     *  不存在) 就原封不動退回原字串, 使 type:0 路徑和 "Local_Result:..." 路徑
     *  完全不受影響。*/
    private static String extractGrammarResultText(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return raw; // 不是 JSON 格式 (例如 "Local_Result:..." 或純文字聽寫結果), 原樣返回
        }
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("text")) {
                return obj.getString("text");
            }
            // 離線本地文法 (engine_type=local buildGrammar bnf) 的
            // 結果格式沒有 top-level text field! 實測 payload (WS capture):
            //   {"sn":1,"ls":true,"ws":[{"slot":"<phrase>","cw":[{"w":"你叫什么名字",
            //    "id":65535,"sc":0,"gm":0}]}],"sc":51}
            // 識別到的字在 ws[].cw[].w 裡 (cw 是候選, 第一個是最高分)。逐個 ws 取
            // 第一個非空的 cw[0].w 直接串接 (中文不加空格), 使對話界面/語意配對
            // 取得乾淨文字。
            org.json.JSONArray wsArr = obj.optJSONArray("ws");
            if (wsArr != null && wsArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < wsArr.length(); i++) {
                    org.json.JSONObject wsItem = wsArr.getJSONObject(i);
                    org.json.JSONArray cw = wsItem.optJSONArray("cw");
                    if (cw == null || cw.length() == 0) continue;
                    String word = cw.getJSONObject(0).optString("w", "");
                    if (word != null && !word.isEmpty()) {
                        sb.append(word);
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (JSONException e) {
            // parse 不到就當它不是這種格式, 原樣返回 - 避免因為格式猜錯而搞壞
            // 其他沒問題的 ASR 路徑
        }
        return raw;
    }

    /** 預設文法是一份預先在 PC 上做好的靜態檔案
     *  (assets/semantic/default_grammar.bnf: 中文 1212 句 (q0-q12) + greet
     *  slot 裡的 hello/hi 兩個英文字, 全繁體, 無重複, 已剔除乘數表)。來源 =
     *  語意庫 + 悠聊原裝 BNF 合併轉換, App 不再做任何運行時生成/解析/
     *  簡繁轉換, 淨係讀檔。
     *
     *  離線文法只支援中文普通話（訊飛文檔＋common.jet 無英文音素，已確認），不含英文詞；離線英文用其他引擎。 */
    private String readDefaultGrammarAsset() {
        try {
            java.io.InputStream in = appContext.getAssets().open("semantic/default_grammar.bnf");
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "readDefaultGrammarAsset failed: " + e.getMessage());
            return null;
        }
    }

    /** 將預設 BNF 文法原樣 (JSON string) 回傳給前端, 讓 textarea
     *  有內容可顯示、用戶可以直接改完再 init_grammar。 */
    public HttpServer.ApiResponse getDefaultGrammar() {
        String bnf = readDefaultGrammarAsset();
        if (bnf == null) {
            return HttpServer.ApiResponse.error("assets/semantic/default_grammar.bnf unreadable");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"bnf\":\"" + MainActivity.jsonSafe(bnf) + "\"}");
    }
    // -- 離線文法模式: 共用內部方法 + 自動跟網絡切換 ---------------------------------
    //
    // doInitGrammar/doStartGrammar/doStopGrammar 供
    // 「自動跟網路切換」和 HTTP endpoint 兩邊共用。自動切換規則 (開啟
    // offlineGrammarAutoSwitch 才生效):
    //   沒網路 → （舊 iFlytek binding 路已死，見 stub）文法未構建就先構建 → 構建成功立即 start
    //   有網路 → 離線模式開著的話就 stop, 回到雲端聽寫 (自由講話)
    // 狀態變化會 publish "offline_mode" event 供前端 UI 更新。

    /** 真正的「雲端聽寫能不能用」探測。唔可以用 WiFi link 狀態
     *  代替 - 連著一個沒有後備網路的手機 hotspot 時照樣回報
     *  connected, 但實際上不了網。而且單純「有網際網路」也不夠: 如果網路
     *  封鎖了訊飛伺服器, 雲端聽寫照樣全部網路錯誤 (實測 logcat: 10114/20002)
     *  - 這種情況對語音來說應該當成離線走本地文法。
     *
     *  探測目標是反編譯 alpha2services 找到的、機身 MSC 實際使用的雲端主域:
     *  SpeechUtility init 字串 "appid=56652373" +
     *  "server_url=http://ubtek.openspeech.cn/index.htm", 另加 openspeech 主域
     *  和舊版 voicecloud.cn 做 fallback。任一 TCP handshake 通過 = 當作 online。
     *  Blocking call (最長 ~7.5s), 只供背景 thread 呼叫。 */
    private static boolean hasRealInternet() {
        // 第一個目標用反編譯找到的 server_url host; 另外加上 IP 直連 fallback -
        // 手機數據底下 DNS 有時慢/斷斷續續, hostname 解析失敗不代表這條路真的不通。
        String[][] targets = {
                {"ubtek.openspeech.cn", "80"},
                {"openspeech.cn", "80"},
                {"voicecloud.cn", "443"},
                {"121.37.220.137", "80"} // ubtek.openspeech.cn 的 IP (2026-08 實測), 免 DNS
        };
        for (String[] t : targets) {
            try {
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(t[0], Integer.parseInt(t[1])), 2500);
                s.close();
                return true;
            } catch (Exception e) {
                android.util.Log.d(TAG, "probe " + t[0] + ":" + t[1] + " fail: "
                        + e.getClass().getSimpleName());
            }
        }
        return false;
    }

    /** 最近一次探測結果 - 開機預設樂觀當有網, 第一次 probe 之後就會校正。 */
    private volatile boolean lastProbeOnline = true;
    // 探測入口得兩個：speech/offline_auto_switch toggle 即時 probe 同下面 triggerWakeupProbe()。
    // (注意：probe 一定要背景 thread，Main thread 會彈 NetworkOnMainThreadException。)
    /** 自動切換的入口 - 網路狀態變化或 App 啟動 (speech_ready 之後) 都會執行。
     *
     *  網路飄忽處理:
     *  - 轉「離線」即時生效 (挽救講不了話的情況, 代價低)
     *  - 轉「雲端」要 MODE_SWITCH_MIN_INTERVAL_MS 內沒有再翻轉才執行, 避免
     *    stop/start 文法循環使中間那段時間講話完全沒反應
     *  - 只有 lastGrammarBuildOk==false 時才重新構建; 已經構建過就直接
     *    startGrammar, 不要無謂地 destroyASR。 */
    private void applyConnectivityMode(boolean connected, String reason) {
        if (!offlineGrammarAutoSwitch) {
            return;
        }
        Log.i(TAG, "applyConnectivityMode(" + connected + ", " + reason + ")"
                + " offlineActive=" + offlineGrammarActive
                + " lastGrammarBuildOk=" + lastGrammarBuildOk);
        long now = android.os.SystemClock.elapsedRealtime();
        if (!connected) {
            if (offlineGrammarActive || grammarInitInFlight) {
                return; // 已經在離線模式/已經構建中, 不用重複開啟
            }
            // 確保 ASR binding 走 zh_cn（舊 iFlytek 路已死，stub 即回；這個 call 對已綁定的情況無害）
            try {
                robot.speech_setRecognizedLanguage("zh_cn");
            } catch (Exception e) {
                Log.w(TAG, "setRecognizedLanguage failed during auto switch: " + e.getMessage());
            }
            if (!lastGrammarBuildOk) {
                // 未構建過/上次失敗 - 用預設文法構建, 成功之後 callback 會接手 start
                pendingOfflineEnable = true;
                String bnf = readDefaultGrammarAsset();
                if (bnf != null) {
                    UbxErrorCode.API_ERROR_CODE code = doInitGrammar(bnf);
                    Log.i(TAG, "auto init grammar -> " + code);
                } else {
                    pendingOfflineEnable = false;
                    Log.w(TAG, "auto init grammar: default asset unreadable");
                }
            } else {
                UbxErrorCode.API_ERROR_CODE code = doStartGrammar();
                Log.i(TAG, "auto start grammar -> " + code);
                if (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                    lastModeSwitchMs = now;
                    publishOfflineMode(true, reason);
                }
                // start 失敗: 不要立即重構建 - 等下個 watchdog 週期再試, 避免疊 build
            }
        } else {
            // 轉雲端: 加冷卻期 - 如果 15 秒內剛切換過模式, 很可能是網路
            // 飄忽, 不要跟著翻轉 (stop/start 文法成本高, 講什麼都沒反應更糟)
            if (offlineGrammarActive && now - lastModeSwitchMs < MODE_SWITCH_MIN_INTERVAL_MS) {
                Log.i(TAG, "online but within cooldown (" + (now - lastModeSwitchMs)
                        + "ms) - keeping offline grammar mode");
                return;
            }
            pendingOfflineEnable = false;
            if (offlineGrammarActive) {
                UbxErrorCode.API_ERROR_CODE code = doStopGrammar();
                Log.i(TAG, "auto stop grammar -> " + code);
                lastModeSwitchMs = now;
                publishOfflineMode(false, reason);
            }
        }
    }

    private void publishOfflineMode(boolean active, String reason) {
        EventBus.get().publish("offline_mode",
                "{\"active\":" + active
                        + ",\"connected\":" + lastProbeOnline
                        + ",\"reason\":\"" + MainActivity.jsonSafe(reason) + "\"}");
    }
    /** 初始化 (構建) 本地文法。結果係 async - grammar_init event/callback 收貨,
     *  errorCode==0 先算數 (lastGrammarBuildOk)。
     *  防重入鎖: 構建進行中再叫呢個 method 會直接略過 - firmware
     *  每次都 destroyASR 重建, 疊 build 會打壞剛建好的辨識 session。 */
    private UbxErrorCode.API_ERROR_CODE doInitGrammar(final String bnf) {
        if (grammarInitInFlight) {
            Log.i(TAG, "doInitGrammar skipped - already in flight");
            return UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED;
        }
        grammarInitInFlight = true;
        lastGrammarBuildOk = false;
        // stub 即時回 NOT_INIT 且永遠唔 callback，即時清 flag，否則下次會誤判
        // "already in flight"。
        UbxErrorCode.API_ERROR_CODE initCode = robot.speech_initGrammar(bnf,
                new RobotStub.IAlpha2SpeechGrammarInitListener() {
                    @Override
                    public void speechGrammarInitCallback(String grammarId, int errorCode) {
                        Log.i(TAG, "initGrammar callback: grammarId=" + grammarId
                                + " errorCode=" + errorCode);
                        if (errorCode == 0) {
                            lastGrammarBuildOk = true;
                        }
                        EventBus.get().publish("grammar_init",
                                "{\"grammarId\":\"" + MainActivity.jsonSafe(grammarId == null ? "" : grammarId)
                                        + "\",\"errorCode\":" + errorCode + "}");
                        // 自動切換: 構建成功而又有 pending start 就接手開始辨識
                        if (errorCode == 0 && pendingOfflineEnable && offlineGrammarAutoSwitch) {
                            pendingOfflineEnable = false;
                            UbxErrorCode.API_ERROR_CODE startCode = doStartGrammar();
                            Log.i(TAG, "pending auto start grammar -> " + startCode);
                            if (startCode == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                                lastModeSwitchMs = android.os.SystemClock.elapsedRealtime();
                                publishOfflineMode(true, "auto");
                            }
                        }
                        grammarInitInFlight = false;
                    }
                });
        if (initCode != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            // 即時失敗（例如 stub NOT_INIT）唔會有 callback 嚟清 flag，呢度即刻清，
            // 否則下次會誤判 "already in flight"。
            grammarInitInFlight = false;
        }
        return initCode;
    }

    private UbxErrorCode.API_ERROR_CODE doStartGrammar() {
        offlineGrammarActive = true;
        UbxErrorCode.API_ERROR_CODE startCode = robot.speech_startGrammar(
                new RobotStub.IAlpha2SpeechGrammarListener() {
                    @Override
                    public void onSpeechGrammarResult(int type, String result) {
                        // type: firmware SpeechManager d.a(int,String) 那邊
                        // "语法识别成功:<result> type:<n>" 的同一個 int -
                        // type=1 是辨識文字結果 (本地文法 JSON {"text":..,"rc":..}),
                        // 其他 type 是 focus/state 類訊號, 原樣轉發給前端查看。
                        String text = extractGrammarResultText(result);
                        EventBus.get().publish("grammar_result",
                                "{\"type\":" + type
                                        + ",\"raw\":\"" + MainActivity.jsonSafe(result == null ? "" : result)
                                        + "\",\"text\":\"" + MainActivity.jsonSafe(text == null ? "" : text) + "\"}");
                    }

                    @Override
                    public void onSpeechGrammarError(int errorCode) {
                        Log.w(TAG, "startGrammar onError: " + errorCode);
                        EventBus.get().publish("grammar_error",
                                "{\"errorCode\":" + errorCode + "}");
                    }
                });
        if (startCode != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            // SDK 層面立即失敗 (例如未 bind) 就不要進入離線模式, 等 asr_result
            // 路徑照常運作。
            offlineGrammarActive = false;
        }
        return startCode;
    }

    private UbxErrorCode.API_ERROR_CODE doStopGrammar() {
        offlineGrammarActive = false;
        pendingOfflineEnable = false;
        return robot.speech_stopGrammar();
    }
}
