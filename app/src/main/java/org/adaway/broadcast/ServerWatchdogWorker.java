package org.adaway.broadcast;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.adaway.helper.PreferenceHelper;
import org.adaway.util.WebServerUtils;

import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Self-healing check: verifies the web server is still running and restarts it
 * when it is not.
 *
 * <p>The native web server is a detached (setsid) process, but on some devices
 * it is still killed (aggressive OEM memory managers, SELinux policies, a
 * crashed launcher shell, ...) - that is what "the web server often stops"
 * looks like. Two safety nets:</p>
 * <ul>
 *     <li>WorkManager periodic work (minimum interval 15 min) as the backstop;</li>
 *     <li>a self-rescheduling one-time work every ~5 minutes, which is what
 *     actually keeps the downtime short.</li>
 * </ul>
 *
 * <p>In the "hijack" blocking mode the iptables rules redirect the whole
 * 80/443 traffic into the server, so a dead server would black-hole the device:
 * the rules are removed as soon as a restart fails and put back once the server
 * is up again.</p>
 */
public class ServerWatchdogWorker extends Worker {

    /** Unique name used when scheduling the periodic work. */
    public static final String UNIQUE_PERIODIC = "adblock-server-watchdog";

    /** Unique name of the self-rescheduling follow-up check. */
    public static final String UNIQUE_FOLLOW_UP = "adblock-server-watchdog-next";

    /** Unique name of the immediate check scheduled when the app starts. */
    public static final String UNIQUE_IMMEDIATE = "adblock-server-watchdog-now";

    /** Delay between two self-scheduled checks. */
    public static final long FOLLOW_UP_DELAY_MINUTES = 5L;

    /** How long to wait for the server to come back up after a restart. */
    private static final long START_VERIFY_MS = 3_000L;

    public ServerWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        Context context = getApplicationContext();
        // Chain the next check first: it must be scheduled even if this run
        // throws (a lost chain would mean up to 15 minutes of downtime).
        scheduleFollowUp(context);
        if (!PreferenceHelper.getWebServerEnabled(context)) {
            return Result.success();
        }
        boolean running = WebServerUtils.isWebServerRunning()
                || WebServerUtils.isWebServerReachable(context);
        if (!running) {
            Timber.w("ServerWatchdog: web server not running - restarting it.");
            WebServerUtils.startWebServer(context);
            sleep(START_VERIFY_MS);
            running = WebServerUtils.isWebServerRunning()
                    || WebServerUtils.isWebServerReachable(context);
            if (running) {
                Timber.i("ServerWatchdog: web server restarted successfully.");
            } else {
                Timber.w("ServerWatchdog: restart attempt did not come up (next check in %d min)",
                        FOLLOW_UP_DELAY_MINUTES);
            }
        }
        /*
         * Hijack mode safety: with the iptables redirect in place a dead server
         * means "no internet at all" on ports 80/443, which is far worse than
         * not filtering. Drop the rules while the server is down, restore them
         * when it is back.
         */
        if (org.adaway.util.BlockMode.current(context) == org.adaway.util.BlockMode.HIJACK) {
            if (running) {
                org.adaway.model.root.HijackModel.enable(context);
            } else {
                Timber.w("ServerWatchdog: removing the hijack rules while the server is down");
                org.adaway.model.root.HijackModel.disable();
            }
        }
        return Result.success();
    }

    /**
     * Schedule the next self-check.
     *
     * @param context The application context.
     */
    public static void scheduleFollowUp(Context context) {
        OneTimeWorkRequest next = new OneTimeWorkRequest.Builder(ServerWatchdogWorker.class)
                .setInitialDelay(FOLLOW_UP_DELAY_MINUTES, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOLLOW_UP, ExistingWorkPolicy.REPLACE, next);
    }

    /**
     * Run one check almost immediately (used when the app is opened, which is
     * exactly the moment a user notices that the server is gone).
     *
     * @param context The application context.
     */
    public static void scheduleImmediateCheck(Context context) {
        OneTimeWorkRequest now = new OneTimeWorkRequest.Builder(ServerWatchdogWorker.class)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE, ExistingWorkPolicy.REPLACE, now);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
