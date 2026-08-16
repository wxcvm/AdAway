package org.adaway;

import android.app.Application;

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
        // Create models
        this.sourceModel = new SourceModel(this);
        this.updateModel = new UpdateModel(this);
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
        // Light mode: when enabled, the web server only runs while the app
        // is in the foreground (minimum process footprint). Stop it on
        // background, restart it on foreground.
        androidx.lifecycle.ProcessLifecycleOwner.get().getLifecycle().addObserver(
                (androidx.lifecycle.LifecycleEventObserver) (owner, event) -> {
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                        if (org.adaway.ui.compose.SettingsScreenKt.isLightMode(getApplicationContext())) {
                            Timber.d("Light mode: app backgrounded, stopping web server.");
                            org.adaway.util.WebServerUtils.stopWebServer();
                        }
                    } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                        if (org.adaway.ui.compose.SettingsScreenKt.isLightMode(getApplicationContext())
                                && PreferenceHelper.getWebServerEnabled(getApplicationContext())) {
                            Timber.d("Light mode: app foregrounded, starting web server.");
                            org.adaway.util.WebServerUtils.startWebServer(getApplicationContext());
                        }
                    }
                });
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
