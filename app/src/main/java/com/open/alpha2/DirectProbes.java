package com.open.alpha2;

import android.content.Context;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;

/**
 * 直驅串口就緒探針。
 *
 * 之前 {@code directChestReady()}/{@code directHeaderReady()}/{@code headerReady()}/
 * {@code chestReady()} 喺 ApiDispatcher / UbxApi / LedCenter / DeviceStatus /
 * SonarCenter / ChestQuery / XiaozhiBridge 各自內聯複製同一段
 * {@code HardwareDirectManager.get(ctx).chest()/head().isAvailable()} try/catch。
 * 收斂到呢度，行為不變（拋錯即 false）。
 */
public final class DirectProbes {
    private DirectProbes() {}

    public static boolean isChestReady(Context ctx) {
        try {
            return HardwareDirectManager.get(ctx.getApplicationContext()).chest().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHeadReady(Context ctx) {
        try {
            return HardwareDirectManager.get(ctx.getApplicationContext()).head().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
