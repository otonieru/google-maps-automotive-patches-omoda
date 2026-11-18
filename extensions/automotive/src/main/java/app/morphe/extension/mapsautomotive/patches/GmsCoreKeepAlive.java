package app.morphe.extension.mapsautomotive.patches;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MicroG-RE keep-alive for head units that kill MicroG-RE in the background (Chery/Omoda and others).
 * Ported from otonieru/gmaps-patches-omoda (phone Maps) to Maps Car.
 * <p>
 * When Maps' main process starts, this wakes MicroG-RE (acquiring its GServices provider starts its process)
 * and holds a BIND_IMPORTANT binding to MicroG-RE's location service for as long as Maps lives, so MicroG-RE
 * gets Maps' process priority, including the foreground priority of Maps' navigation service, instead of
 * being treated as an idle background app.
 * <p>
 * Additions over the phone patch: main process only, and a heartbeat every {@link #HEARTBEAT_MS} that wakes the
 * provider again and re-binds if the binding was lost (a force-stop by the head unit's app killer kills the
 * binding; this restarts MicroG-RE within a minute at most, or within seconds through onBindingDied).
 * Everything runs on its own thread; the binder callbacks only schedule work. Failures are logged and ignored.
 */
@SuppressWarnings("unused")
public final class GmsCoreKeepAlive {
    private static final String TAG = "MorpheGmsKeepAlive";
    private static final String PROVIDER_PACKAGE = "app.revanced.android.gms";
    private static final String GSERVICES_AUTHORITY = "app.revanced.android.gsf.gservices";
    // The same actions LocationServicePatch gives Maps, in the same order.
    private static final String[] BIND_ACTIONS = {
            "com.google.android.location.internal.GoogleLocationManagerService.START",
            "app.revanced.android.location.internal.GoogleLocationManagerService.START",
    };
    private static final long HEARTBEAT_MS = 60_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile Context appContext;
    private static volatile Handler worker;
    private static volatile boolean bound;
    private static volatile boolean connected;
    private static int failures;

    private GmsCoreKeepAlive() {
    }

    /** Injection point: the first instruction of EmbeddedGoogleMapsApplication.onCreate. */
    public static void onApplicationCreate(Context context) {
        try {
            String process = Application.getProcessName();
            if (process == null || !process.equals(context.getPackageName())) return; // main process only
            if (!started.compareAndSet(false, true)) return;
            Context app = context.getApplicationContext();
            appContext = app != null ? app : context;
            HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(GmsCoreKeepAlive::wakeAndBind);
            worker.postDelayed(GmsCoreKeepAlive::heartbeat, HEARTBEAT_MS);
        } catch (Throwable error) {
            ReportLog.w(TAG, "not started", error);
        }
    }

    private static void heartbeat() {
        try {
            if (!connected) ReportLog.i(TAG, "heartbeat: MicroG-RE not connected, waking it");
            wakeAndBind();
        } catch (Throwable error) {
            ReportLog.w(TAG, "heartbeat failed", error);
        } finally {
            Handler handler = worker;
            if (handler != null) handler.postDelayed(GmsCoreKeepAlive::heartbeat, HEARTBEAT_MS);
        }
    }

    private static void wakeAndBind() {
        wake();
        if (!bound) bind();
    }

    /** Acquiring the provider forces the MicroG-RE process to start if the system allows it. */
    private static void wake() {
        try (ContentProviderClient client = appContext.getContentResolver()
                .acquireUnstableContentProviderClient(GSERVICES_AUTHORITY)) {
            if (client == null) ReportLog.w(TAG, "MicroG-RE provider unavailable");
        } catch (Throwable error) {
            ReportLog.w(TAG, "wake failed", error);
        }
    }

    private static void bind() {
        Intent intent = resolveBindIntent();
        if (intent == null) {
            ReportLog.w(TAG, "no bindable MicroG-RE location service found");
            scheduleRetry();
            return;
        }
        try {
            bound = appContext.bindService(intent, CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            ReportLog.i(TAG, "bind " + (bound ? "requested" : "refused") + ": " + intent.getAction());
            if (!bound) scheduleRetry();
        } catch (Throwable error) {
            ReportLog.w(TAG, "bind failed", error);
            scheduleRetry();
        }
    }

    private static Intent resolveBindIntent() {
        PackageManager packages = appContext.getPackageManager();
        for (String action : BIND_ACTIONS) {
            Intent intent = new Intent(action).setPackage(PROVIDER_PACKAGE);
            ResolveInfo info = packages.resolveService(intent, 0);
            if (info != null && info.serviceInfo != null && info.serviceInfo.exported
                    && PROVIDER_PACKAGE.equals(info.serviceInfo.packageName)) {
                return intent;
            }
        }
        return null;
    }

    private static void scheduleRetry() {
        Handler handler = worker;
        if (handler == null) return;
        long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(failures++, 5));
        handler.postDelayed(GmsCoreKeepAlive::wakeAndBind, delay);
    }

    private static void unbindQuietly() {
        try {
            appContext.unbindService(CONNECTION);
        } catch (Throwable ignored) {
        }
        bound = false;
        connected = false;
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            failures = 0;
            connected = true;
            ReportLog.i(TAG, "connected: " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // MicroG-RE's process died; the binding stays and the system restarts it. Nudge it anyway.
            connected = false;
            ReportLog.w(TAG, "disconnected: " + name.flattenToShortString());
            Handler handler = worker;
            if (handler != null) handler.postDelayed(GmsCoreKeepAlive::wake, 1000);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Typically a force-stop by the head unit's app killer: the binding is gone for good.
            ReportLog.w(TAG, "binding died: " + name.flattenToShortString());
            Handler handler = worker;
            if (handler != null) handler.post(() -> {
                unbindQuietly();
                scheduleRetry();
            });
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // The service returned no binder. The binding itself still keeps MicroG-RE's process at Maps'
            // priority, which is all this needs, so it is kept (unbinding would make the heartbeat re-bind).
            connected = true;
            ReportLog.i(TAG, "null binding kept: " + name.flattenToShortString());
        }
    };
}
