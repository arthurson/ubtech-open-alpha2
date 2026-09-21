package com.open.alpha2;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.Log;

import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 裝置狀態直讀：WiFi／藍牙（標準 Android framework，不經機身服務）、
 * 電池 receiver（含低電量自動蹲下）+ battery/status、accelerometer set/get +
 * SensorEventListener、handleApi status 健康聚合 (statusResponse)。
 * 低電量蹲下經傳入的同一個 ActionDirect；synchronized 鎖用自己
 * (調用方全部經同一個 instance，互斥等價；見 AudioCenter 同例)。
 * reboot 無（App 無 REBOOT 權限，實機 verified 永遠 SecurityException)；
 * TtsCenter readiness 經傳入的同一個 instance 讀。
 */
public final class DeviceStatus implements SensorEventListener {
    private static final String TAG = "DeviceStatus";

    private final Context appContext;
    private final Handler mainHandler;
    private final ActionDirect actionDirect;
    private final UbxPlayer ubxPlayer;
    private final TtsCenter ttsCenter;

    public DeviceStatus(Context context, Handler mainHandler, ActionDirect actionDirect,
            UbxPlayer ubxPlayer, TtsCenter ttsCenter) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.actionDirect = actionDirect;
        this.ubxPlayer = ubxPlayer;
        this.ttsCenter = ttsCenter;
        // 原 registerGestureController() 那兩行搬入：sensorManager 經 appContext
        // 拿到的和 Activity 那個是同一個 service (audioManager 不動，留在手勢那邊)。
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        accelerometerSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
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

    /** 語音 FUNCTION 共用：開/關藍牙（API22 經 BluetoothAdapter.enable()/disable()
     *  仍有效；要 BLUETOOTH_ADMIN，見 Manifest）。回 true=已達目標狀態，
     *  false=無 adapter 或系統拒絕。任意線程可調。 */
    public boolean setBluetoothEnabled(boolean on) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            if (on == adapter.isEnabled()) return true;
            return on ? adapter.enable() : adapter.disable();
        } catch (SecurityException se) {
            Log.w(TAG, "setBluetoothEnabled permission denied", se);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "setBluetoothEnabled failed", e);
            return false;
        }
    }

    /** 語音 FUNCTION 共用：開/關無線網路（經 WifiManager.setWifiEnabled；
     *  要 CHANGE_WIFI_STATE，見 Manifest）。回 true=已達目標狀態或調用成功，
     *  false=無服務或系統拒絕。任意線程可調。 */
    public boolean setWifiEnabled(boolean on) {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            if (on == wm.isWifiEnabled()) return true;
            return wm.setWifiEnabled(on);
        } catch (SecurityException se) {
            Log.w(TAG, "setWifiEnabled permission denied", se);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "setWifiEnabled failed", e);
            return false;
        }
    }

    private BroadcastReceiver batteryReceiver;
    /** 低電量蹲下 latch：10% 播過一次後不再重複，直到充過電或回升過 12% 才重置。 */
    private boolean batteryLowSquatDone = false;

    private volatile int lastBatteryLevel = -1;
    private volatile int lastBatteryScale = -1;
    private volatile boolean lastBatteryCharging = false;
    private volatile String lastBatteryStatus = "unknown";

    /**
     * Battery/charging is NOT available through Alpha2RobotApi (see capabilities.md
     * "Battery and charging") - the chest board does stream it on the serial link
     * (CHEST_SEND_POWER), but the SDK never surfaces a getter for it. The documented,
     * reliable path for an on-robot app is the standard Android battery intent instead.
     * ACTION_BATTERY_CHANGED is a sticky broadcast, so this also fires immediately with
     * the current state upon registration.
     */
    public void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                lastBatteryLevel = level;
                lastBatteryScale = scale;
                lastBatteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING) || plugged != 0;
                lastBatteryStatus = batteryStatusName(status);
                EventBus.get().publish("battery", "{\"level\":" + level + ",\"scale\":" + scale
                        + ",\"charging\":" + lastBatteryCharging + ",\"status\":\"" + lastBatteryStatus + "\"}");
                // 用戶要求：電量跌到 10%（且不在充電）自動蹲下一次。ACTION_BATTERY_CHANGED
                // 是 sticky broadcast，註冊即刻有一次，latch 防重複；正在充電/回升過 12% 重置。
                int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
                if (lastBatteryCharging || pct > 12) {
                    batteryLowSquatDone = false;
                } else if (pct >= 0 && pct <= 10 && !batteryLowSquatDone) {
                    batteryLowSquatDone = true;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            if (actionDirect.playActionDirect(ActionDirect.STOP_RECOVERY_ACTION_ID)
                                    != UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
                                Log.w(TAG, "low-battery squat not started: " + ubxPlayer.lastError());
                            }
                        }
                    }, "LowBatterySquat").start();
                }
            }
        };
        appContext.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /** onDestroy 共用：同 MainActivity 舊寫法一樣 guard (null + IllegalArgumentException)。 */
    public void unregisterBatteryReceiver() {
        if (batteryReceiver != null) {
            try {
                appContext.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static String batteryStatusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            default: return "unknown";
        }
    }

    // -- Battery/charging: NOT in Alpha2RobotApi (see capabilities.md); read via
    // the standard Android BatteryManager broadcast this Activity already listens
    // for and caches. ------------------------------------------------------------
    public HttpServer.ApiResponse batteryStatus() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"level\":" + lastBatteryLevel + ","
                + "\"scale\":" + lastBatteryScale + ","
                + "\"charging\":" + lastBatteryCharging + ","
                + "\"status\":\"" + lastBatteryStatus + "\"}");
    }

    /** system/status power 段用 (原直接讀 MainActivity field)。 */
    public int getBatteryLevel() { return lastBatteryLevel; }
    /** system/status power 段用 (原直接讀 MainActivity field)。 */
    public int getBatteryScale() { return lastBatteryScale; }
    /** system/status power 段用 (原直接讀 MainActivity field)。 */
    public boolean isBatteryCharging() { return lastBatteryCharging; }
    /** system/status power 段用 (原直接讀 MainActivity field)。 */
    public String getBatteryStatus() { return lastBatteryStatus; }

    // -- Accelerometer (IMU): standard Android SensorManager, NOT the UBTECH AIDL SDK -
    // see docs/capabilities.md "IMU / accelerometer" in the Alpha2OpenSdk repo and the
    // HelloAlpha example (examples/HelloAlpha), which reads it the same way. The robot's
    // only real motion sensor; readings are gravity-relative (tilt), not true dynamic
    // acceleration. Off by default - only registered while at least one browser tab has
    // it toggled on via the "accelerator/set" endpoint below, so idle sessions don't pay
    // for sensor callbacks/WebSocket traffic nobody is watching.
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private volatile boolean accelerometerEnabled = false;

    /**
     * Turns the accelerometer feed on/off. Safe to call repeatedly - a no-op if already
     * in the requested state. registerListener()/unregisterListener() must run on a
     * thread with a Looper (per SensorManager's contract) - both are called here on the
     * main thread, matching how the sensorManager used to be set up in
     * MainActivity.registerGestureController() in onCreate().
     */
    public synchronized void setAccelerometerEnabled(boolean enabled) {
        if (sensorManager == null || accelerometerSensor == null) {
            accelerometerEnabled = false;
            return;
        }
        if (enabled == accelerometerEnabled) {
            return;
        }
        if (enabled) {
            // SENSOR_DELAY_NORMAL, not _UI: verified on hardware in the Alpha2OpenSdk
            // HelloAlpha example (see docs/capabilities.md "IMU / accelerometer") - the
            // RK3288's gsensor driver reliably delivers events at this rate. _UI was
            // observed to register successfully but never actually deliver events.
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensorManager.unregisterListener(this, accelerometerSensor);
        }
        accelerometerEnabled = enabled;
    }

    // -- SensorEventListener (accelerometer only - see setAccelerometerEnabled()) -------
    private long lastAccelLogMs = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        // Rate-limited (every ~2s) rather than per-sample: confirms whether the sensor
        // itself is actually delivering events at all, without flooding logcat - a
        // normal accelerometer at SENSOR_DELAY_NORMAL fires far more often than that.
        long now = System.currentTimeMillis();
        if (now - lastAccelLogMs > 2000) {
            lastAccelLogMs = now;
            Log.i(TAG, "onSensorChanged firing: x=" + event.values[0]
                    + " y=" + event.values[1] + " z=" + event.values[2]);
        }
        // Published as-is (m/s^2, gravity-relative - see docs/capabilities.md). The
        // browser-side chart/UI is responsible for any smoothing/scaling it wants.
        EventBus.get().publish("accel", "{\"x\":" + event.values[0]
                + ",\"y\":" + event.values[1]
                + ",\"z\":" + event.values[2] + "}");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action needed - the Alpha2's accelerometer accuracy is not meaningfully
        // actionable here (see docs/capabilities.md).
    }

    // -- Accelerometer (IMU): standard Android SensorManager, not SDK-gated -
    // see setAccelerometerEnabled()/onSensorChanged() above. Readings stream out
    // as "accel" WebSocket events while enabled, not through this JSON response. -
    public HttpServer.ApiResponse accelerometerSet(Map<String, String> query) {
        final boolean on = ApiValidator.requireBoolean(query, "on");
        // registerListener()/unregisterListener() must run on the thread that
        // owns sensorManager's Looper (the main thread here) - this handler
        // itself runs on an HttpServer worker thread, so hop over via mainHandler
        // and wait for it to actually apply before answering.
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                setAccelerometerEnabled(on);
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (on && accelerometerSensor == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"no accelerometer sensor available on this device\"}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled + "}");
    }

    public HttpServer.ApiResponse accelerometerGet() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"enabled\":" + accelerometerEnabled
                + ",\"available\":" + (accelerometerSensor != null) + "}");
    }

    // -- 健康狀態聚合 --
    // chest/header readiness 薄 delegate（實現見 DirectProbes）。
    private boolean directChestReady() {
        return DirectProbes.isChestReady(appContext);
    }

    private boolean directHeaderReady() {
        return DirectProbes.isHeadReady(appContext);
    }

    public HttpServer.ApiResponse statusResponse() {
        String appVer = "?";
        try {
            appVer = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        // pure-direct: chest/header 可用性改由直驱串口报告，不再经 binder。
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"appVersion\":\"" + appVer + "\","
                + "\"apiLevel\":" + android.os.Build.VERSION.SDK_INT + ","
                + "\"chestAvailable\":" + directChestReady() + ","
                + "\"headerAvailable\":" + directHeaderReady() + ","
                + "\"androidTtsReady\":" + ttsCenter.isReady() + "}");
    }

    // 無 reboot：App 是第三方 sideload，無 REBOOT 權限 (signature|system)，實機回
    // "Neither user 10020 nor current process has android.permission.REBOOT"，su
    // 亦在 app context 拿不到 (Permission denied)——UUID 卡改做手動重開機提示。

    // 面板 URL/顯示用本機 IP（updatePanelUrlDisplay/複製連結經這裡讀）。
    public String getWifiIp() {
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            int ipInt = wm.getConnectionInfo().getIpAddress();
            String wifiIp = Formatter.formatIpAddress(ipInt);
            if (wifiIp != null && !wifiIp.equals("0.0.0.0") && !wifiIp.isEmpty()) {
                return wifiIp;
            }
            // 热点 AP 模式或未連接作 STA 時，WifiManager 回 0.0.0.0；改列舉網卡找 site-local
            try {
                java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    java.net.NetworkInterface intf = en.nextElement();
                    java.util.Enumeration<java.net.InetAddress> addrs = intf.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String host = addr.getHostAddress();
                            // 192.168/10./172.16/12 私網先算數；另見 isSiteLocal 註：fd00::/8
                            // IPv6 ULA 這部機用不着，不判）。
                            if (host != null && (host.startsWith("192.168.") || host.startsWith("10.")
                                    || isPrivate172(host))) {
                                return host;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return wifiIp != null ? wifiIp : "<device-ip>";
        } catch (Exception e) {
            return "<device-ip>";
        }
    }

    /** 172.16.0.0–172.31.255.255 私網段（RFC1918 172.16/12）。 */
    private static boolean isPrivate172(String host) {
        if (!host.startsWith("172.")) return false;
        int dot = host.indexOf('.', 4);
        if (dot < 0) return false;
        try {
            int second = Integer.parseInt(host.substring(4, dot));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

