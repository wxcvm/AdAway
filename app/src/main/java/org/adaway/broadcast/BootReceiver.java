/*
 * Copyright (C) 2011-2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 *
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.broadcast;

import static android.content.Intent.ACTION_BOOT_COMPLETED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.adaway.helper.PreferenceHelper;
import org.adaway.util.WebServerUtils;

import timber.log.Timber;

/**
 * Receives BOOT_COMPLETED (and vendor-specific quick-boot equivalents) and
 * starts the web server (ROOT mode) as configured.
 *
 * <h3>Why goAsync() + background thread?</h3>
 * BroadcastReceiver.onReceive() runs on the main thread and has a hard ~10 s
 * deadline. Shell.cmd().exec() (the root shell call inside startWebServer) is
 * blocking and can easily exceed that limit, especially when the root shell
 * itself is being initialised for the first time in the boot context. Moving
 * all work off the main thread via goAsync() avoids that timeout.
 *
 * <h3>Why the initial delay?</h3>
 * BOOT_COMPLETED fires before Magisk's root daemon (magiskd) has finished
 * initialising on many devices. Attempting a root shell too early silently
 * fails. A 15-second delay gives magiskd time to become available; if the
 * first attempt still fails the receiver retries twice more at 20-second
 * intervals before giving up (~55 s total, well under Android's 60 s ANR
 * limit for async broadcast receivers).
 *
 * <h3>Why self-terminate?</h3>
 * The web server (libwebserver_exec.so) is a separate native process that
 * calls setsid() on startup and survives independently of the Java runtime.
 * The Java process is no longer needed once boot work is done; killing it
 * frees ~30–50 MB of RAM.
 */
public class BootReceiver extends BroadcastReceiver {

    /** Wait this long before the first attempt (ms). Gives magiskd time to start. */
    private static final long INITIAL_DELAY_MS = 15_000L;

    /** Wait this long between retry attempts (ms). */
    private static final long RETRY_DELAY_MS = 20_000L;

    /** How many times to try starting the server (first attempt + retries). */
    private static final int MAX_ATTEMPTS = 3;

    /** How long to wait after a start attempt before checking if the server is up (ms). */
    private static final long START_VERIFY_MS = 3_000L;

    /** How long to linger after confirming the server is running before killing the process (ms). */
    private static final long EXIT_GRACE_MS = 3_000L;

    /** All boot-completed actions this receiver recognises. */
    private static final java.util.Set<String> BOOT_ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
    ));

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null || !BOOT_ACTIONS.contains(action)) {
            return;
        }
        Timber.d("BootReceiver: %s received.", action);

        if (!PreferenceHelper.getWebServerEnabled(context)) {
            Timber.d("BootReceiver: web server not enabled, skipping.");
            return;
        }

        final PendingResult pendingResult = goAsync();

        new Thread(() -> {
            try {
                startWebServerReliably(context);
            } catch (Exception e) {
                Timber.e(e, "BootReceiver: unexpected error.");
            } finally {
                pendingResult.finish();
                sleep(EXIT_GRACE_MS);
                // The native web server binary calls setsid() on startup, so it
                // is a detached process that outlives the Java runtime.
                // Killing the Java process here is safe and frees ~30–50 MB of RAM.
                Timber.d("BootReceiver: boot work done, terminating Java process.");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "boot-init").start();
    }

    /**
     * Tries to start the web server up to {@link #MAX_ATTEMPTS} times.
     *
     * <p>The first attempt is preceded by {@link #INITIAL_DELAY_MS} to allow
     * Magisk's root daemon to finish initialising. Subsequent attempts wait
     * {@link #RETRY_DELAY_MS} between them. After each launch the receiver
     * waits {@link #START_VERIFY_MS} and then checks whether the server
     * process is actually running.
     */
    private void startWebServerReliably(Context context) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt == 1) {
                Timber.d("BootReceiver: waiting %d ms for Magisk root to be ready…",
                        INITIAL_DELAY_MS);
                sleep(INITIAL_DELAY_MS);
            } else {
                Timber.d("BootReceiver: retry %d/%d in %d ms…",
                        attempt, MAX_ATTEMPTS, RETRY_DELAY_MS);
                sleep(RETRY_DELAY_MS);
            }

            Timber.d("BootReceiver: starting web server (attempt %d/%d).",
                    attempt, MAX_ATTEMPTS);
            WebServerUtils.startWebServer(context);

            sleep(START_VERIFY_MS);

            if (WebServerUtils.isWebServerRunning()) {
                Timber.d("BootReceiver: web server confirmed running after attempt %d.", attempt);
                return;
            }
            Timber.w("BootReceiver: web server not running after attempt %d.", attempt);
        }
        Timber.e("BootReceiver: web server failed to start after %d attempts.", MAX_ATTEMPTS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
