package org.adaway;

import android.app.Application;

import org.adaway.broadcast.ServerWatchdogWorker;
import org.adaway.helper.NotificationHelper;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.source.SourceModel;
import org.adaway.model.update.UpdateModel;
import org.adaway.util.log.ApplicationLog;

import timber.log.Timber;

/**
 * This class is a custom {@link Application} for AdAway app.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class AdAwayApplication extends Application {
    /**
     * The common source model for the whole application.
     */
    private SourceModel sourceModel;
    /**
     * The common ad block model for the whole application.
     */
    private volatile AdBlockModel adBlockModel;
    /**
     * The common update model for the whole application.
     */
    private UpdateModel updateModel;

    @Override
    public void onCreate() {
        // Delegate application creation
        super.onCreate();
        // Initialize logging
        ApplicationLog.init(this);
        // Sync web server port cache (getStats has no Context).
        org.adaway.util.WebServerUtils.initPortCache(this);
        // Create notification channels
        NotificationHelper.createNotificationChannels(this);
        // Web server self-healing watchdog: every ~15 min verify the native
        // web server is still running and restart it if it is gone (covers
        // "web server often not started" - OEM kill / boot-race / crashes).
        androidx.work.PeriodicWorkRequest watchdog =
                new androidx.work.PeriodicWorkRequest.Builder(
                        ServerWatchdogWorker.class,
                        15,
                        java.util.concurrent.TimeUnit.MINUTES)
                        .build();
        androidx.work.WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                        ServerWatchdogWorker.UNIQUE_PERIODIC,
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        watchdog);
        // Create models
        this.sourceModel = new SourceModel(this);
        this.updateModel = new UpdateModel(this);
        // Hijack-mode safety net: when the app was killed while the iptables
        // redirect rules were installed, the device's 80/443 traffic would be
        // pointed at a server that may be gone. Remove the rules unless the
        // hijack mode is still selected and the server is alive. The check is
        // gated on the "rules installed" marker so a device without root is
        // never asked for it.
        if (org.adaway.model.root.HijackModel.wasEnabled(this)) {
            new Thread(() -> {
                try {
                    boolean stillHijacking =
                            org.adaway.util.BlockMode.current(this) == org.adaway.util.BlockMode.HIJACK
                                    && org.adaway.util.WebServerUtils.isWebServerRunning();
                    if (!stillHijacking) {
                        org.adaway.model.root.HijackModel.disable();
                        Timber.i("Stale hijack rules removed at startup");
                    }
                } catch (Throwable throwable) {
                    Timber.w(throwable, "Hijack cleanup failed");
                }
            }, "hijack-cleanup").start();
        }
        // Crash diagnostics: persist any uncaught exception to filesDir/crash.log
        // so a "it just closed" report can be diagnosed afterwards
        // (readable via root/adb even if logcat buffers have rotated).
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File logFile = new java.io.File(getFilesDir(), "crash.log");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
                     java.io.PrintWriter pw = new java.io.PrintWriter(fos)) {
                    pw.println("=== crash at " + new java.util.Date()
                            + " thread=" + (thread != null ? thread.getName() : "?") + " ===");
                    throwable.printStackTrace(pw);
                    pw.flush();
                }
            } catch (Throwable ignored) {
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        // Light mode semantics (v2): the web server is a detached native
        // process (setsid) that must keep running regardless of app
        // foreground/background state. "Light mode" only means the app
        // process does not need to stay resident after boot work is done
        // (BootReceiver self-terminates). No lifecycle-driven start/stop
        // of the web server is performed here anymore.
    }


    /**
     * Get the source model.
     *
     * @return The common source model for the whole application.
     */
    public SourceModel getSourceModel() {
        return this.sourceModel;
    }

    /**
     * Get the ad block model.
     *
     * @return The common ad block model for the whole application.
     */
    public AdBlockModel getAdBlockModel() {
        // Check cached model (double-checked locking: this getter is
        // called from multiple threads - UI, quick-settings tile,
        // broadcast receiver - so building two RootModel instances would
        // both kick off duplicate work such as starting the web server).
        AdBlockMethod method = PreferenceHelper.getAdBlockMethod(this);
        AdBlockModel model = this.adBlockModel;
        if (model == null || model.getMethod() != method) {
            synchronized (this) {
                model = this.adBlockModel;
                if (model == null || model.getMethod() != method) {
                    model = AdBlockModel.build(this, method);
                    this.adBlockModel = model;
                }
            }
        }
        return model;
    }

    /**
     * Get the update model.
     *
     * @return Teh common update model for the whole application.
     */
    public UpdateModel getUpdateModel() {
        return this.updateModel;
    }
}
