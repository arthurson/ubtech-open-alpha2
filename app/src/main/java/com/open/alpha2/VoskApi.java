package com.open.alpha2;

import android.util.Log;

import java.util.Map;

/**
 * Vosk 離線 ASR endpoint 層：models/load/status/start/stop/unload/download/catalog。
 */
public final class VoskApi {
    private static final String TAG = "VoskApi";

    private final VoskController vosk;
    private final XiaozhiBridge xiaozhiBridge;
    private final SemanticCenter semanticCenter;

    public VoskApi(VoskController vosk, XiaozhiBridge xiaozhiBridge, SemanticCenter semanticCenter) {
        this.vosk = vosk;
        this.xiaozhiBridge = xiaozhiBridge;
        this.semanticCenter = semanticCenter;
    }

    /** vosk/* endpoint 熔斷：vosk 是 null（API 19 機不起 controller，或者
     *  21+ 機 init 失敗）就回清晰錯誤，不要逐個 case 寫 if。
     *  return null = 可用，照行。 */
    private HttpServer.ApiResponse voskOrError() {
        if (vosk != null) return null;
        if (android.os.Build.VERSION.SDK_INT < 21) {
            return HttpServer.ApiResponse.error("Vosk needs Android 5.0+ (this device is API "
                    + android.os.Build.VERSION.SDK_INT + ")");
        }
        return HttpServer.ApiResponse.error("vosk not initialised");
    }

    // -- Vosk 離線 ASR --------------------------------------
    // Model 放 sdcard 自動偵測 (見 VoskController.scanModels)，一次一粒。
    // 成句結果沿用 asr_result event（前端同打字模擬同一條管線：氣泡＋
    // 語意配對＋Android TTS＋direct 動作）。
    // API 19 熔斷：除 models（純檔案掃描，static，哪個 API 都得）之外，
    // 其他經 voskOrError() 回清晰錯誤，不要逐個 case 寫 if。
    public HttpServer.ApiResponse voskModels() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"models\":[");
        boolean first = true;
        for (VoskController.VoskModelInfo m : VoskController.scanModels()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(MainActivity.jsonSafe(m.id))
              .append("\",\"lang\":\"").append(MainActivity.jsonSafe(m.langHint))
              .append("\",\"langEn\":\"").append(MainActivity.jsonSafe(m.langEn))
              .append("\",\"sizeMb\":").append(m.sizeBytes / 1048576).append('}');
        }
        sb.append("],\"download\":").append(downloadJsonInner());
        sb.append('}');
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    private String downloadJsonInner() {
        if (vosk == null) return "null";
        try {
            // downloadStatusJson 是 {"ok":true,...}，去除 ok 層剩內文方便嵌。
            String full = vosk.downloadStatusJson();
            int i = full.indexOf("\"state\"");
            if (i < 0) return "null";
            return "{" + full.substring(i);
        } catch (Throwable e) {
            return "null";
        }
    }

    public HttpServer.ApiResponse voskLoad(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        String err = vosk.loadModel(id);
        if (err != null) return HttpServer.ApiResponse.error(err);
        // 直接打 model 鍵換了 model 都一併一併轉換配對語言（認不到語言不動）。
        // load 是背景做，這裡樂觀同步——同前端即刻切換行為一致。
        String mapped = VoskController.langOfModelId(id);
        if (mapped != null) semanticCenter.setDialogueLang(mapped);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"loading\":\""
                + MainActivity.jsonSafe(id) + "\"}");
    }

    public HttpServer.ApiResponse voskStatus() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String mid = vosk.getModelId();
        String msg = vosk.getLastError();
        return HttpServer.ApiResponse.ok("{\"ok\":true"
                + ",\"state\":\"" + vosk.getState().name().toLowerCase(java.util.Locale.US) + "\""
                + ",\"model\":" + (mid == null ? "null" : "\"" + MainActivity.jsonSafe(mid) + "\"")
                + ",\"lang\":\"" + vosk.getDialogueLang() + "\""
                + ",\"listening\":" + vosk.isListening()
                + (msg == null ? "" : ",\"message\":\"" + MainActivity.jsonSafe(msg) + "\"")
                + "}");
    }

    public HttpServer.ApiResponse voskStart() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        // 後開者得 mic：小智正在開就整個 session 踢斷（斷線＋熄 mute 燈，同
        // "disconnect" case 同順序），先開 recorder (同 startXiaozhiMic
        // 停 vosk 對稱；不自動幫小智重開——對稱那邊都不自動重開 vosk)。
        xiaozhiBridge.yieldMicToVosk();
        String err = vosk.startListening();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.okTrue();
    }

    public HttpServer.ApiResponse voskStop() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.stopListening();
        return HttpServer.ApiResponse.okTrue();
    }

    /** 切包等待世代（新撳即 supersede 舊等緊嗰條，唔好堆二三十條 thread
     *  一齊等把鎖，見 logcat Long monitor contention waiters=28）。 */
    private final java.util.concurrent.atomic.AtomicInteger switchGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 調用方係咪 main thread（mapping 物理掣係，HTTP worker 唔係）。 */
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    /** GamepadCenter 映射共用：Vosk 監聽單鍵 toggle（聽緊即停，閒置即搶咪開聽）。
     *  main thread 經物理掣入：一律放背景，連 isListening() 讀鎖都唔掂
     *  （等 background op 即 ANR 死，見 traces）；回 true＝已停／已接受。
     *  HTTP worker 照行同步版（回值照實）。背景失敗只記 log＋status 睇到。 */
    public boolean toggleListening() {
        if (voskOrError() != null) return false;
        if (onMainThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    toggleListeningSync();
                }
            }, "VoskToggle").start();
            return true;
        }
        return toggleListeningSync();
    }

    private boolean toggleListeningSync() {
        try {
            boolean listening = false;
            try {
                listening = vosk.isListening();
            } catch (Throwable ignored) {
            }
            if (listening) {
                try {
                    vosk.stopListening();
                } catch (Throwable t) {
                    return false;
                }
                return true;
            }
            startListeningAsync();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 背景開聽（yield mic＋start；失敗 Log＋唔炸調用方）。 */
    private void startListeningAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    xiaozhiBridge.yieldMicToVosk();
                    String err = vosk.startListening();
                    if (err != null) Log.w(TAG, "async startListening failed: " + err);
                } catch (Throwable t) {
                    Log.w(TAG, "async startListening failed", t);
                }
            }
        }, "VoskStart").start();
    }

    /** GamepadCenter 映射共用：切指定語言包（聽緊同包即停；否則載入＋載好即開聽）。
     *  main thread 經物理掣入：一律放背景，連 getModelId() 讀鎖都唔掂
     *  （等 background op 即 ANR 死，見 traces）；回 true＝已停／已接受切包。
     *  HTTP worker 照行同步版（回值照實）。 */
    public boolean switchModel(String id) {
        if (voskOrError() != null || id == null || id.trim().isEmpty()) return false;
        final String tid = id.trim();
        if (onMainThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    switchModelSync(tid);
                }
            }, "VoskSwitchMap").start();
            return true;
        }
        return switchModelSync(tid);
    }

    private boolean switchModelSync(String tid) {
        try {
            String cur = null;
            boolean listening = false;
            try {
                cur = vosk.getModelId();
            } catch (Throwable ignored) {
            }
            try {
                listening = vosk.isListening();
            } catch (Throwable ignored) {
            }
            if (listening && tid.equals(cur)) {
                try {
                    vosk.stopListening();
                } catch (Throwable t) {
                    return false;
                }
                return true;
            }
            final String err = vosk.loadModel(tid);
            if (err != null) {
                // 載入緊嗰陣轉軚：打斷舊載入再載新嘅（唔係成堆 false 冇反應；
                // unload 會令舊 bg thread 自己收，見 loadModel）。
                boolean loading = false;
                try {
                    loading = vosk.getState() == VoskController.State.LOADING;
                } catch (Throwable ignored) {
                }
                if (loading) {
                    try {
                        vosk.unload();
                    } catch (Throwable ignored) {
                    }
                    String err2 = null;
                    try {
                        err2 = vosk.loadModel(tid);
                    } catch (Throwable t) {
                        return false;
                    }
                    if (err2 != null) return false;
                } else {
                    return false;
                }
            }
            String mapped = VoskController.langOfModelId(tid);
            if (mapped != null) {
                try {
                    semanticCenter.setDialogueLang(mapped);
                } catch (Throwable ignored) {
                }
            }
            final int gen = switchGen.incrementAndGet();
            new Thread(new Runnable() {
                @Override public void run() {
                    // 等載好先開聽（load 幾秒到幾十秒；每秒望一次，最多等 60 秒；
                    // 新撳 supersede 即走，唔堆 thread 等把鎖）。
                    for (int i = 0; i < 60; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (gen != switchGen.get()) return;
                        boolean ready = false;
                        try {
                            ready = !vosk.isListening()
                                    && tid.equals(vosk.getModelId())
                                    && vosk.getState() == VoskController.State.READY;
                        } catch (Throwable ignored) {
                        }
                        if (ready) break;
                        if (i == 59) return;
                    }
                    // 瞓醒再驗世代：期間有人撳過新包即收手，唔開舊嘢。
                    if (gen != switchGen.get()) return;
                    try {
                        xiaozhiBridge.yieldMicToVosk();
                        String e2 = vosk.startListening();
                        if (e2 != null) Log.w(TAG, "async switch start failed: " + e2);
                    } catch (Throwable t) {
                        Log.w(TAG, "async switch start failed", t);
                    }
                }
            }, "VoskSwitch").start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public HttpServer.ApiResponse voskUnload() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.unload();
        return HttpServer.ApiResponse.okTrue();
    }

    // 模型下載＋自動 unzip（實驗 tab 下載卡用）。
    // model＝目錄名（見 vosk/catalog，固定官方 URL allowlist，不收任意 URL）。
    // 背景落 zip 再自己 unzip 到 sdcard 頂層，完了自動 load。進度經
    // vosk_download event＋download_status 查，不用輪詢 models。
    public HttpServer.ApiResponse voskDownload(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        if (!VoskController.isDownloadable(id)) {
            return HttpServer.ApiResponse.error("unknown downloadable model: " + id);
        }
        String err = vosk.startDownload(id);
        if (err != null) {
            // 已經有 model 就不當錯（前端問完先來，可能另一邊已落好）：回 ok+exists
            // 等前端直接 refresh。already downloading 都一樣回狀態等前端跟進度。
            if (err.startsWith("already exists") || err.startsWith("already downloading")) {
                return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
            }
            return HttpServer.ApiResponse.error(err);
        }
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    public HttpServer.ApiResponse voskDownloadStatus() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    public HttpServer.ApiResponse voskDownloadCancel() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String err = vosk.cancelDownload();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.ok(vosk.downloadStatusJson());
    }

    // 全部可下載模型 catalog（id/lang/sizeMb/downloaded，
    // 純檔案掃描 static，API 19 都用得，同 models 一樣不經 voskOrError 熔斷）。
    public HttpServer.ApiResponse voskCatalog() {
        return HttpServer.ApiResponse.ok(VoskController.catalogJson());
    }
}


