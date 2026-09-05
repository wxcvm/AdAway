package org.adaway.broadcast;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.adaway.helper.PreferenceHelper;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * Starts the web server after boot.
 *
 * <p>Replaces the previous {@link BootReceiver} approach which used
 * {@code goAsync()} and then slept/retried for up to ~55&nbsp;s on a
 * background thread: an async broadcast receiver only gets about
 * 10&nbsp;s before the system considers it an ANR and may kill the
 * process, so on devices with a slow root (magiskd) initialisation the
 * boot start was silently aborted &mdash; the "web server did not start
 * after reboot" reports. WorkManager runs this outside the broadcast
 * deadline with its own retry/backoff, so the start attempt keeps
 * running for as long as root needs to become ready. The native
 * web server is a detached (setsid) process, so once it is running it
 * survives independently of this process and of WorkManager.
 */
public class ServerStartWorker extends Worker {

    /** Unique name used by {@link BootReceiver} when enqueueing. */
    public static final String UNIQUE_WORK = "adblock-server-boot-start";

    /** Wait this long when probing for a usable root shell (ms). */
    private static final long PROBE_WINDOW_MS = 30_000L;

    /** How long to wait after a start attempt before verifying (ms). */
    private static final long START_VERIFY_MS = 3_000L;

    public ServerStartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        Context context = getApplicationContext();
        if (!PreferenceHelper.getWebServerEnabled(context)) {
            Timber.d("ServerStartWorker: web server not enabled, done.");
            return Result.success();
        }
        Timber.d("ServerStartWorker: probing root and starting the web server...");
        if (startWebServerReliably(context)) {
            Timber.i("ServerStartWorker: web server confirmed running after boot.");
            return Result.success();
        }
        Timber.w("ServerStartWorker: web server not running after this attempt - will retry.");
        return Result.retry();
    }

    /**
     * Probes for a usable root shell, then starts the web server and
     * verifies it came up. Root (Magisk) initialisation can be slow
     * right after boot, so the probe window is generous - WorkManager
     * has no broadcast deadline to worry about here.
     */
    private static boolean startWebServerReliably(Context context) {
        if (!waitForRootReady(PROBE_WINDOW_MS)) {
            Timber.w("ServerStartWorker: root not ready within probe window.");
            return false;
        }
        WebServerUtils.startWebServer(context);
        sleep(START_VERIFY_MS);
        return WebServerUtils.isWebServerRunning();
    }

    /**
     * Polls for a usable root shell until the timeout elapses.
     *
     * @return {@code true} as soon as {@code su -c true} exits successfully.
     */
    private static boolean waitForRootReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Process p = new ProcessBuilder("su", "-c", "true").start();
                boolean ok = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        && p.exitValue() == 0;
                p.destroy();
                if (ok) {
                    return true;
                }
            } catch (Exception ignored) {
                // su binary missing / busy: keep polling
            }
            sleep(1_000L);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
