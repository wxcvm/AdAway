package org.adaway.broadcast;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
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

    /** Periodic interval in the default (fast) cadence. */
    private static final long PERIOD_MINUTES = 15L;

    /**
     * Periodic interval when light mode asks for the smallest footprint.
     *
     * <p>WorkManager persists periodic work across reboots, so this keeps the
     * "server died in the middle of the day" repair alive without waking the app
     * (and possibly spawning a root shell) every few minutes.</p>
     */
    private static final long LIGHT_PERIOD_HOURS = 6L;

    /**
     * Preference remembering which cadence is currently installed.
     *
     * <p>Without it, {@link #ensureScheduled(Context)} had to cancel and
     * re-enqueue on every call to be sure the interval was the wanted one - and
     * it is called on every app start, every boot broadcast and inside every
     * worker run. That churn is what the battery stats showed as thousands of
     * job executions and hundreds of cancellations for this package.</p>
     */
    private static final String PREF_CADENCE = "watchdog_cadence";

    /** Value of {@link #PREF_CADENCE} for the light-mode cadence. */
    private static final String CADENCE_LIGHT = "light";

    /** Value of {@link #PREF_CADENCE} for the default cadence. */
    private static final String CADENCE_FAST = "fast";

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
        ensureScheduled(context);
        scheduleFollowUp(context);
        if (!PreferenceHelper.getWebServerEnabled(context)) {
            return Result.success();
        }
        // Reachability first: it is a single socket connect to the management
        // port, while the process check spawns a root shell (CPU/battery).
        boolean running = WebServerUtils.isWebServerReachable(context)
                || WebServerUtils.isWebServerRunning();
        if (!running) {
            Timber.w("ServerWatchdog: web server not running - restarting it.");
            WebServerUtils.startWebServer(context);
            sleep(START_VERIFY_MS);
            running = WebServerUtils.isWebServerReachable(context)
                    || WebServerUtils.isWebServerRunning();
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
     * Make sure BOTH safety nets exist.
     *
     * <p>The periodic work was declared (UNIQUE_PERIODIC) but never actually
     * enqueued - only the self-rescheduling 5 minute one-shot was. So whenever
     * that chain broke (the app was force-stopped, the system cancelled the
     * pending work, an OEM cleaner removed it) nothing ever restarted the
     * server again until the user opened the app by hand: exactly the "the web
     * server is not started at boot until I touch the app" report.</p>
     *
     * <p>ExistingWorkPolicy.KEEP makes this idempotent: calling it from the app
     * start, the boot receiver and the worker itself never resets a running
     * schedule, it only re-creates one that is gone. WorkManager persists the
     * period across reboots, so the backstop survives everything except the
     * user force-stopping the app.</p>
     *
     * @param context The application context.
     */
    public static void ensureScheduled(Context context) {
        boolean light = lightCadence(context);
        android.content.SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(org.adaway.util.Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String want = light ? CADENCE_LIGHT : CADENCE_FAST;
        if (want.equals(prefs.getString(PREF_CADENCE, ""))) {
            /*
             * Already scheduled with the wanted cadence - do nothing.
             *
             * This method runs on every app start, on every boot broadcast and
             * inside every worker run. The previous version cancelled and
             * re-enqueued the periodic work on each of those calls, which reset
             * the period and made JobScheduler churn: the device battery stats
             * showed ~5000 executions of this package's job and 315
             * cancellations. Scheduling work is not free - each cancel/enqueue
             * is a binder call plus a persisted write.
             */
            return;
        }
        /* First run, or the cadence changed: replace whatever was scheduled. */
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_FOLLOW_UP);
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC);
        PeriodicWorkRequest request = light
                ? new PeriodicWorkRequest.Builder(ServerWatchdogWorker.class,
                        LIGHT_PERIOD_HOURS, TimeUnit.HOURS).build()
                : new PeriodicWorkRequest.Builder(ServerWatchdogWorker.class,
                        PERIOD_MINUTES, TimeUnit.MINUTES).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
        prefs.edit().putString(PREF_CADENCE, want).apply();
        /*
         * Verify, do not trust: the system (or an OEM cleaner) can drop the
         * periodic work. When that happened, clear the sentinel so the next call
         * installs it again - otherwise the sentinel would hide a missing
         * watchdog. The listener runs after the (already started) query, so
         * reading it there does not block anything.
         */
        final com.google.common.util.concurrent.ListenableFuture<java.util.List<androidx.work.WorkInfo>> pending =
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_PERIODIC);
        pending.addListener(() -> {
            try {
                if (pending.get().isEmpty()) {
                    prefs.edit().remove(PREF_CADENCE).apply();
                    Timber.w("ServerWatchdog: periodic work is missing - will re-install on the next call");
                }
            } catch (Throwable ignored) {
                /* keep the sentinel; the next call checks again */
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context));
        if (!light) {
            scheduleFollowUp(context);
        }
    }

    /**
     * @param context The application context.
     * @return {@code true} when the cheap cadence applies: light mode is on and
     *         the blocking mode is not hijack.
     *
     *         <p>Hijack mode redirects all 80/443 traffic into the server, so a
     *         dead server means "no internet at all" there - that mode always
     *         keeps the fast chain, whatever light mode says.</p>
     */
    private static boolean lightCadence(Context context) {
        return org.adaway.helper.PreferenceHelper.getLightMode(context)
                && org.adaway.util.BlockMode.current(context) != org.adaway.util.BlockMode.HIJACK;
    }

    /**
     * Schedule the next self-check.
     *
     * @param context The application context.
     */
    public static void scheduleFollowUp(Context context) {
        if (lightCadence(context)) {
            /* Light mode: no 5 minute chain (see ensureScheduled). */
            return;
        }
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
        // Opening the app is also the moment to repair a missing backstop.
        ensureScheduled(context);
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
