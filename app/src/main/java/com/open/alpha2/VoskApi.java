package com.open.alpha2;

import java.util.Map;

/**
 * Vosk 離線 ASR endpoint 層：models/load/status/start/stop/unload/download/catalog。
 */
public final class VoskApi {
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

    // 幻聽過濾開關現狀（confGate/confThr/dedup/grammar/unk/ttsPause/resumeDelay，
    // 全部 prefs 持久化，預設全開；前端測試面板讀這個畫開關）。
    public HttpServer.ApiResponse voskHallu() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        return HttpServer.ApiResponse.ok(vosk.halluJson());
    }

    // 設一個幻聽開關：key＝上面七個之一，value＝開關類收 true/1/false/0，
    // confThr 收 0..1 小數。grammar 切換即時重建文法（聽緊就停完重開）。
    // 成功回現狀 JSON（等前端一次 round trip refresh），失敗回 500＋原因。
    public HttpServer.ApiResponse voskHalluSet(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String key = ApiValidator.require(query, "key");
        String value = ApiValidator.require(query, "value");
        String err = vosk.setHallu(key, value);
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.ok(vosk.halluJson());
    }
}


