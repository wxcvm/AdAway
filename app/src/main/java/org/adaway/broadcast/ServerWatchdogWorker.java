package org.adaway.broadcast;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.adaway.helper.PreferenceHelper;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * Periodic self-healing check: verifies the web server is still running
 * and restarts it when it is not.
 *
 * <p>The native web server is a detached (setsid) process, but on some
 * devices it can still be killed (aggressive OEM memory managers, SELinux
 * policies, a crashed launcher shell, ...), which is what reports of
 * "the web server often is not running" look like. This WorkManager
 * periodic task (minimum interval 15 min) is a safety net: whenever the
 * server is gone it is restarted through the same root launch path used
 * by the boot receiver, so blocking simply resumes.
 */
public class ServerWatchdogWorker extends Worker {

    /** Unique name used when scheduling the periodic work. */
    public static final String UNIQUE_PERIODIC = "adblock-server-watchdog";

    /** How long to wait for the server to come back up after a restart. */
    private static final long START_VERIFY_MS = 3_000L;

    public ServerWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        Context context = getApplicationContext();
        if (!PreferenceHelper.getWebServerEnabled(context)) {
            return Result.success();
        }
        if (WebServerUtils.isWebServerRunning()) {
            Timber.d("ServerWatchdog: web server is running, nothing to do.");
            return Result.success();
        }
        Timber.w("ServerWatchdog: web server not running - restarting it.");
        WebServerUtils.startWebServer(context);
        sleep(START_VERIFY_MS);
        if (WebServerUtils.isWebServerRunning()) {
            Timber.i("ServerWatchdog: web server restarted successfully.");
        } else {
            Timber.w("ServerWatchdog: restart attempt did not come up (will try again next cycle)");
        }
        return Result.success();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
