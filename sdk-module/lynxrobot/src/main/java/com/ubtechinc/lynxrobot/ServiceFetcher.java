package com.ubtechinc.lynxrobot;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.ubtechinc.alpha.serverlibutil.aidl.IServiceFetcher;

/**
 * Client-side singleton that obtains the robot's {@link IServiceFetcher} binder broker
 * and uses it to fetch each individual AIDL service by name.
 *
 * <p>Binding mechanism confirmed against com.ubtechinc.alpha2services_base.3.002.apk by
 * decompiling the app's internal service-locator class ("ru"): the robot's system app
 * exposes the root {@code IServiceFetcher} binder through a {@link android.content.ContentProvider}
 * at authority {@code alpha2.service.BinderProvider}, called with method {@code "@"};
 * the returned {@link Bundle} carries the binder under the key {@code "fetchBinder"}.
 * From there, {@link IServiceFetcher#getService(String)} hands out each sub-service's
 * binder by name. Confirmed service-name keys (from decompiling the callers of each
 * interface's {@code Stub.asInterface}):
 *
 * <pre>
 *   "action"  -&gt; IActionService
 *   "motor"   -&gt; IMotorInterface
 *   "led"     -&gt; ILedInterface
 *   "sysinfo" -&gt; ISysService
 *   "speech"  -&gt; ISpeechInterface
 * </pre>
 */
public final class ServiceFetcher {

    private static final String TAG = "ServiceFetcher";
    private static final String PROVIDER_URI = "content://alpha2.service.BinderProvider";
    private static final String PROVIDER_METHOD = "@";
    private static final String BINDER_BUNDLE_KEY = "fetchBinder";

    public static final String SERVICE_ACTION = "action";
    public static final String SERVICE_MOTOR = "motor";
    public static final String SERVICE_LED = "led";
    public static final String SERVICE_SYSINFO = "sysinfo";
    public static final String SERVICE_SPEECH = "speech";

    private static volatile ServiceFetcher instance;

    private final Context context;
    private IServiceFetcher serviceFetcher;

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "IServiceFetcher binder died, will re-fetch on next use");
            serviceFetcher = null;
        }
    };

    private ServiceFetcher(Context context) {
        this.context = context.getApplicationContext();
        connect();
    }

    public static ServiceFetcher get(Context context) {
        if (instance == null) {
            synchronized (ServiceFetcher.class) {
                if (instance == null) {
                    instance = new ServiceFetcher(context);
                }
            }
        }
        return instance;
    }

    private void connect() {
        try {
            Bundle result = context.getContentResolver()
                    .call(Uri.parse(PROVIDER_URI), PROVIDER_METHOD, null, null);
            if (result != null) {
                IBinder binder = result.getBinder(BINDER_BUNDLE_KEY);
                if (binder != null) {
                    this.serviceFetcher = IServiceFetcher.Stub.asInterface(binder);
                    try {
                        binder.linkToDeath(deathRecipient, 0);
                    } catch (Exception e) {
                        Log.w(TAG, "linkToDeath failed", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to alpha2.service.BinderProvider", e);
        }
    }

    /**
     * Returns the raw {@link IBinder} for the named service (e.g. {@link #SERVICE_ACTION}),
     * or {@code null} if the robot's service isn't available. Wrap the result with the
     * matching {@code IXxx.Stub.asInterface(...)}.
     */
    public IBinder getServiceBinder(String name) {
        if (serviceFetcher == null) {
            connect();
        }
        if (serviceFetcher == null) {
            return null;
        }
        try {
            return serviceFetcher.getService(name);
        } catch (Exception e) {
            Log.e(TAG, "getService(" + name + ") failed", e);
            return null;
        }
    }

    public boolean isConnected() {
        return serviceFetcher != null;
    }
}
