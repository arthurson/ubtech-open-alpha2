package com.open.alpha2;

/**
 * 對外連接留痕：app 每次主動連出面網站（收音機搜尋／串流、小智雲 WS／vision／OTA、
 * 前端見 app-radio.js 同形）都經 EventBus publish 一行 {@code net_connect}
 *（{purpose, url}），等面板個 WebSocket event log 睇到部機連咗咩站；同時照印
 * logcat。記嘅係「連咗邊度」——token 全部行 header 唔入 URL，URL 原樣保留
 *（搜尋 query 睇到先有用）；過長截 512 字。
 *
 * Android framework + JDK only，無第三方。
 */
public final class NetLog {
    private NetLog() {}

    private static final String TAG = "NetLog";
    private static final int MAX_URL_LEN = 512;
    /** EventBus type（集中一處，免字面散）。 */
    public static final String TYPE = "net_connect";

    /**
     * 對外連一次即記一行。擺喺 openConnection／setDataSource／new Socket
     * 之前——連唔連到都記，DNS 死一樣睇到「想連邊度」。
     */
    public static void out(String purpose, String url) {
        String u = url == null ? "?" : url;
        u = JsonUtil.truncate(u, MAX_URL_LEN);
        android.util.Log.i(TAG, "outbound " + purpose + ": " + u);
        EventBus.get().publish(TYPE,
                "{\"purpose\":" + JsonUtil.quote(String.valueOf(purpose))
                        + ",\"url\":" + JsonUtil.quote(u) + "}");
    }
}
