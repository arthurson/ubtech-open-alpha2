package com.open.alpha2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Launches {@link MainActivity} automatically when the device finishes booting, so the
 * control panel's HttpServer is already up and reachable at http://&lt;robot-ip&gt;:8888/
 * without anyone touching the robot's screen/launcher.
 *
 * Must be a static &lt;receiver&gt; in AndroidManifest.xml (see the comment there) - there
 * is no running process to hold a dynamic registration until this fires at least once.
 *
 * FLAG_ACTIVITY_NEW_TASK is required here: a BroadcastReceiver's Context has no existing
 * task/back-stack to place an Activity into, so starting one without NEW_TASK throws
 * "startActivity() must be called from a Context" on some OS versions and silently
 * fails to actually raise a window on others.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "Boot completed - launching MainActivity");
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            // Some OEM/launcher configurations restrict starting activities from the
            // background even at boot; log and give up rather than crash the receiver.
            Log.e(TAG, "Failed to auto-start MainActivity on boot", e);
        }
    }
}
