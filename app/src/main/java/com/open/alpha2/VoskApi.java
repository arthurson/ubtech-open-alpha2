package com.open.alpha2;

import java.util.Map;

/**
 * Vosk 離線 ASR endpoint 層：models/load/status/start/stop/unload/mic_test/endpointer。
 *
 * 2026-09 由 MainActivity 抽出 (dispatcher Phase 1 第一刀)：voskOrError 熔斷 +
 * 8 個 case body，邏輯一字不改搬過嚟。硬件經傳入嘅同一個 VoskController
 * (可 null：API 19 機唔起 controller，經 voskOrError() 回清晰錯誤——同 TtsCenter
 * 一樣，null 唔係 bug，唔好喺度加 null 以外嘅狀態)。
 */
public final class VoskApi {
    private final VoskController vosk;

    public VoskApi(VoskController vosk) {
        this.vosk = vosk;
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

    // -- Vosk 離線 ASR (2026-09 新增) --------------------------------------
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
              .append("\",\"sizeMb\":").append(m.sizeBytes / 1048576).append('}');
        }
        return HttpServer.ApiResponse.ok(sb.append("]}").toString());
    }

    public HttpServer.ApiResponse voskLoad(Map<String, String> query) {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        String id = ApiValidator.require(query, "model");
        String err = vosk.loadModel(id);
        if (err != null) return HttpServer.ApiResponse.error(err);
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
        String err = vosk.startListening();
        if (err != null) return HttpServer.ApiResponse.error(err);
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse voskStop() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.stopListening();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse voskUnload() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        vosk.unload();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    // 2026-09 新增: 咪測試——開 1 秒錄音計 RMS/Peak (dBFS)，幫用戶判斷
    // 係唔係收得細。聽緊嗰陣唔做 (單 input HAL)，先㩒停止。
    public HttpServer.ApiResponse voskMicTest() {
        HttpServer.ApiResponse need = voskOrError();
        if (need != null) return need;
        // micTestJson 自帶 {"ok":...}，直接透傳。
        return HttpServer.ApiResponse.ok(vosk.micTestJson());
    }

    // 2026-09 新增: 收音延遲調校。mode -1/省略=跟預設，0=標準 1=短
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
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }
}
