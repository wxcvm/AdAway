package org.adaway.broadcast;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.adaway.helper.PreferenceHelper;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * "Light mode": stop holding RAM once the boot work is done.
 *
 * <p>The intercept server is a detached (setsid) native process; the app process
 * is only needed to start it and to run the watchdog. On the reference device
 * that process costs ~180&nbsp;MB RSS, by far the largest thing this app keeps
 * resident - and the user's stated goal is the smallest possible footprint, not
 * "stay alive in the background".</p>
 *
 * <p>Exit conditions are deliberately conservative, so the process is never
 * killed out from under the user:</p>
 * <ul>
 *     <li>light mode is on <b>and</b> the opt-in keep-alive service is off (they
 *     contradict each other, the user's explicit keep-alive wins);</li>
 *     <li>the server answers on its management port (otherwise the watchdog
 *     still has work to do - exiting then would leave blocking broken);</li>
 *     <li>no activity is visible, re-checked when the timer fires;</li>
 *     <li>armed only from boot paths - opening the app never schedules an
 *     exit.</li>
 * </ul>
 */
public final class LightMode {

    /**
     * How long to wait before the exit check.
     *
     * <p>The boot broadcast also wakes the UI-less process for other receivers,
     * and the delayed WorkManager retry may still be about to run; the grace
     * period keeps the exit from racing that work.</p>
     */
    private static final long GRACE_MS = 45_000L;

    /** Mechanism name recorded in the boot diagnostics. */
    private static final String MECHANISM = "轻量模式";

    /** Only one pending exit check per process. */
    private static boolean armed;

    private LightMode() {
    }

    /**
     * Schedule the "exit when idle" check if light mode asks for it.
     *
     * <p>Called after a boot start attempt (broadcast, boot service, worker).
     * Idempotent: repeated calls in the same process arm a single check.</p>
     *
     * @param context Any context; the application context is used.
     * @param reason  Short description for the boot diagnostics.
     */
    public static void armIfEnabled(Context context, String reason) {
        final Context app = context.getApplicationContext();
        if (armed || !isEnabled(app)) {
            return;
        }
        armed = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            armed = false;
            try {
                if (!isEnabled(app)) {
                    return;
                }
                if (!isInBackground(app)) {
                    Timber.d("LightMode: an activity is visible, staying resident (%s)", reason);
                    return;
                }
                if (!WebServerUtils.isWebServerReachable(app)) {
                    // Blocking is not up: the watchdog must keep the process
                    // around to fix that first.
                    Timber.d("LightMode: server not reachable, staying resident (%s)", reason);
                    return;
                }
                PreferenceHelper.recordBootEvent(app, PreferenceHelper.getLastBootAction(app),
                        MECHANISM, "服务器已就绪，退出应用进程以节省内存", true);
                Timber.i("LightMode: exiting the app process to free ~180 MB (%s)", reason);
                Process.killProcess(Process.myPid());
            } catch (Throwable throwable) {
                Timber.w(throwable, "LightMode: exit check failed");
            }
        }, GRACE_MS);
    }

    /**
     * @param context The application context.
     * @return {@code true} when light mode is on and the keep-alive service (the
     *         user's explicit "stay resident" choice) is off.
     */
    private static boolean isEnabled(Context context) {
        return PreferenceHelper.getLightMode(context)
                && !PreferenceHelper.getKeepAliveEnabled(context);
    }

    /**
     * @param context The application context.
     * @return {@code true} when no activity of this app is visible.
     */
    private static boolean isInBackground(Context context) {
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                && state.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }
}
