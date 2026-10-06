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
            PreferenceHelper.recordBootEvent(context, PreferenceHelper.getLastBootAction(context),
                    "自动启动已关闭（设置 → 网络服务器）", false);
            return Result.success();
        }
        Timber.d("ServerStartWorker: probing root and starting the web server...");
        if (startWebServerReliably(context)) {
            Timber.i("ServerStartWorker: web server confirmed running after boot.");
            PreferenceHelper.recordBootEvent(context, PreferenceHelper.getLastBootAction(context),
                    "后台任务", "后台任务已确认服务器运行", true);
            /* Light mode: the boot work is done, the app process may exit once it
               is in the background (see LightMode). */
            org.adaway.broadcast.LightMode.armIfEnabled(context, "后台任务");
            return Result.success();
        }
        Timber.w("ServerStartWorker: web server not running after this attempt - will retry.");
        PreferenceHelper.recordBootEvent(context, PreferenceHelper.getLastBootAction(context),
                "后台任务", "后台任务启动失败，将自动重试", false);
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
     * <p>Uses the same libsu channel as the rest of the app instead of spawning
     * {@code su} ourselves: a background process started by WorkManager has a
     * minimal environment, and {@code new ProcessBuilder("su","-c","true")}
     * fails there on devices where the su binary is not on PATH or where the
     * MagiskSU daemon expects the app's own shell handshake. That failure was
     * indistinguishable from "root is slow", so the worker kept retrying a
     * mechanism that could never succeed.</p>
     *
     * @return {@code true} as soon as a root shell answers.
     */
    private static boolean waitForRootReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (org.adaway.model.root.ShellUtils.isRootAvailable()) {
                return true;
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
