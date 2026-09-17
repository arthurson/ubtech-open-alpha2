package com.open.alpha2;

import java.util.Map;

/**
 * Vosk 離線 ASR endpoint 層：models/load/status/start/stop/unload/mic_test/endpointer。
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

    /** vosk/* endpoint 熔斷：vosk 係 null（API 19 機唔起 controller，或者
     *  21+ 機 init 失敗）就回清晰錯誤，唔好逐個 case 寫 if。
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
    // API 19 熔斷：除 models（純檔案掃描，static，邊個 API 都得）之外，
    // 其他經 voskOrError() 回清晰錯誤，唔好逐個 case 寫 if。
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
            // downloadStatusJson 係 {"ok":true,...}，剝走 ok 層剩內文方便嵌。
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
        // 直接打 model 鍵換咗 model 都一併轉埋配對語言（認唔到語言唔郁）。
        // load 係背景做，呢度樂觀同步——同前端即刻轉掣行為一致。
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
        float epTEnd = vosk.getEpTEnd();
        return HttpServer.ApiResponse.ok("{\"ok\":true"
                + ",\"state\":\"" + vosk.getState().name().toLowerCase(java.util.Locale.US) + "\""
                + ",\"model\":" + (mid == null ? "null" : "\"" + MainActivity.jsonSafe(mid) + "\"")
                + ",\"lang\":\"" + vosk.getDialogueLang() + "\""
                + ",\"listening\":" + vosk.isListening()
                + ",\"epMode\":" + vosk.getEpMode()
                + ",\"epTEnd\":" + (Float.isNaN(epTEnd) ? "null"
                        : String.format(java.util.Locale.US, "%.2f", epTEnd))
                + (msg == null ? "" : ",\"message\":\"" + MainActivity.jsonSafe(msg) + "\"")
                + "}");
    }

    public HttpServer.ApiResponse voskStart() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        // 後開者得 mic：小智開緊就成條 session 踢斷（斷線＋熄 mute 燈，同
        // "disconnect" case 同順序），先開 recorder (同 startXiaozhiMic
        // 停 vosk 對稱；唔自動幫小智重開——對稱嗰邊都唔自動重開 vosk)。
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

    // 咪測試——開 1 秒錄音計 RMS/Peak (dBFS)，幫用戶判斷
    // 係唔係收得細。聽緊嗰陣唔做 (單 input HAL)，先㩒停止。
    public HttpServer.ApiResponse voskMicTest() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        // 單 input HAL：小智拎緊 mic 就唔開第二個 recorder（撞 HAL；同 voskStart
        // 未 yieldMicToVosk 之前開 recorder 炒 FATAL 同一類）。唔似 voskStart 咁
        // 踢斷小智——1 秒測試唔值得，直接叫用戶先停小智 mic（同 micTestJson 擋
        // 自己 listen 緊對稱；spec 話「聽緊嗰陣唔做」）。
        if (xiaozhiBridge.isMicCapturing()) {
            return HttpServer.ApiResponse.error("xiaozhi holds the mic - stop xiaozhi mic first (mic/stop)");
        }
        // micTestJson 自帶 {"ok":...}，直接透傳。
        return HttpServer.ApiResponse.ok(vosk.micTestJson());
    }

    // 模型下載＋自動 unzip（實驗 tab 下載卡用）。
    // model＝目錄名（見 vosk/catalog，固定官方 URL allowlist，唔收任意 URL）。
    // 背景落 zip 再自己 unzip 到 sdcard 頂層，完咗自動 load。進度經
    // vosk_download event＋download_status 查，唔使輪詢 models。
    public HttpServer.ApiResponse voskDownload(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        if (!VoskController.isDownloadable(id)) {
            return HttpServer.ApiResponse.error("unknown downloadable model: " + id);
        }
        String err = vosk.startDownload(id);
        if (err != null) {
            // 已經有 model 就唔當錯（前端問完先嚟，可能另一邊已落好）：回 ok+exists
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
    // 純檔案掃描 static，API 19 都用得，同 models 一樣唔經 voskOrError 熔斷）。
    public HttpServer.ApiResponse voskCatalog() {
        return HttpServer.ApiResponse.ok(VoskController.catalogJson());
    }

    // 收音延遲調校。mode -1/省略=跟預設，0=標準 1=短
    // 2=長 3=很長；t_start/t_end/t_max 三個一齊俾先有效 (秒，見 vosk_api.h，
    // t_end 係講完幾耐靜音先 finalize，0.5-1.0 左右）。在聽緊即時生效，
    // 並 persist 跨重開；status 會帶返現值。
    public HttpServer.ApiResponse voskEndpointer(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        int mode = ApiValidator.optionalVoskEndpointerMode(query);
        float tStart = ApiValidator.optionalFloat(query, "t_start", Float.NaN);
        float tEnd = ApiValidator.optionalFloat(query, "t_end", Float.NaN);
        float tMax = ApiValidator.optionalFloat(query, "t_max", Float.NaN);
        String err = vosk.setEndpointer(mode, tStart, tEnd, tMax);
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.okTrue();
    }
}
