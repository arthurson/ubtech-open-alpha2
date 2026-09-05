package com.open.alpha2;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

/**
 * 裝置狀態直讀：WiFi／藍牙（標準 Android framework，唔經機身服務）。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object)：wifiStatus/btStatus 原本
 * 係 MainActivity 私有成員，搬過嚟邏輯不改。只需要 Context。
 */
public final class DeviceStatus {
    private final Context appContext;

    public DeviceStatus(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public HttpServer.ApiResponse wifiStatus() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            boolean enabled = wm.isWifiEnabled();
            String ssid = "";
            int ipInt = 0;
            if (wm.getConnectionInfo() != null) {
                ssid = wm.getConnectionInfo().getSSID();
                ipInt = wm.getConnectionInfo().getIpAddress();
            }
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + enabled
                    + ",\"ssid\":\"" + MainActivity.jsonSafe(ssid) + "\",\"ip\":\""
                    + Formatter.formatIpAddress(ipInt) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }

    public HttpServer.ApiResponse btStatus() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":false}");
            }
            boolean enabled = adapter.isEnabled();
            String name = adapter.getName();
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"available\":true,\"enabled\":" + enabled
                    + ",\"name\":\"" + MainActivity.jsonSafe(name) + "\"}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.error(String.valueOf(e.getMessage()));
        }
    }
}
