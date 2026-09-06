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

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 裝置狀態直讀：WiFi／藍牙（標準 Android framework，唔經機身服務）。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object)：wifiStatus/btStatus 原本
 * 係 MainActivity 私有成員，搬過嚟邏輯不改。只需要 Context。
 * 2026-09 dispatcher Phase 1 第五刀加：電池 receiver（含低電量自動蹲下）+
 * battery/status、accelerometer set/get + SensorEventListener，連註解搬入。
 * 低電量蹲下經傳入嘅同一個 ActionDirect；synchronized 鎖由 MainActivity.this
 * 轉做自己 (調用方全部經同一個 instance，互斥等價；見 AudioCenter 同例)。
 * 2026-09 dispatcher Phase 1 第七刀加：handleApi status 健康聚合 +
 * service_config/reboot (statusResponse/rebootResponse)；TtsCenter
 * readiness 經傳入嘅同一個 instance 讀。
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
        // 原 registerGestureController() 嗰兩行搬入：sensorManager 經 appContext
        // 攞，同 Activity 嗰個係同一個 service (audioManager 唔郁，留喺手勢嗰邊)。
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
                // 係 sticky broadcast，註冊即刻有一次，latch 防重複；充緊電/回升過 12% 重置。
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

    // -- 健康狀態聚合 (2026-09 dispatcher Phase 1 第七刀由 handleApi status 搬入) --
    // chest/header readiness 內聯：同 MainActivity 版一字不差，經 appContext 唔使 Activity。
    private boolean directChestReady() {
        try { return HardwareDirectManager.get(appContext).chest().isAvailable(); }
        catch (Exception e) { return false; }
    }

    private boolean directHeaderReady() {
        try { return HardwareDirectManager.get(appContext).head().isAvailable(); }
        catch (Exception e) { return false; }
    }

    public HttpServer.ApiResponse statusResponse() {
        String appVer = "?";
        try {
            appVer = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        // pure-direct: chest/header 可用性改由直驱串口报告，不再经 binder。
        // 2026-09: speechReady key 已移除 (無 ASR，舊 binder service 永遠唔會 ready)。
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"appVersion\":\"" + appVer + "\","
                + "\"apiLevel\":" + android.os.Build.VERSION.SDK_INT + ","
                + "\"chestAvailable\":" + directChestReady() + ","
                + "\"headerAvailable\":" + directHeaderReady() + ","
                + "\"androidTtsReady\":" + ttsCenter.isReady() + "}");
    }

    /** 觸發機身重開機（UUID 卡重開機掣用，經 PowerManager）。獨立 endpoint，用戶隨時手動重開機。 */
    public HttpServer.ApiResponse rebootResponse() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return HttpServer.ApiResponse.error("PowerManager unavailable");
            }
            pm.reboot("robotpanel_service_config_change");
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"rebooting\":true}");
        } catch (SecurityException e) {
            // REBOOT permission 在很多機身/ROM 只給 system app 用, 第三方 app (即使
            // 有 manifest 聲明) 都可能在這裡被 SecurityException 拒絕 - 這是
            // 意料之內的失敗模式, 不是 bug, 前端應該提示用戶手動長按電源鍵重開機。
            return HttpServer.ApiResponse.error(
                    "REBOOT permission denied by system (common on locked-down firmware) - "
                            + "please power-cycle the robot manually for the config change to take effect: "
                            + e.getMessage());
        }
    }
}
