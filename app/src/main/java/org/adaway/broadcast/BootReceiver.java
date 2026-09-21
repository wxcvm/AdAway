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
import android.content.BroadcastReceiver.PendingResult;

import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.adaway.helper.PreferenceHelper;

import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Receives BOOT_COMPLETED (and vendor-specific quick-boot equivalents) and
 * schedules the web server start via {@link ServerStartWorker}.
 *
 * <p>The previous implementation did all the work here with
 * {@code goAsync()} plus a background thread that slept and retried for up
 * to ~55&nbsp;s. Async broadcast receivers only get about 10&nbsp;s before
 * the system flags them as ANR, so on devices where Magisk's root daemon
 * initialises slowly the work was aborted and the web server never started
 * at boot. WorkManager (see {@link ServerStartWorker}) runs outside that
 * deadline with its own retry/backoff, so this receiver only enqueues the
 * job and returns immediately.
 */
public class BootReceiver extends BroadcastReceiver {

    /** Initial delay before the first start attempt (root boot-up time). */
    private static final long INITIAL_DELAY_SECONDS = 10L;

    /** Budget for the immediate attempt inside the receiver's own 10 s window. */
    private static final long IMMEDIATE_BUDGET_MS = 6_000L;

    /** All boot-completed actions this receiver recognises. */
    private static final java.util.Set<String> BOOT_ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // The app update replaces the staged executable's library path and
            // force-stops the app: make sure the server is up again afterwards.
            "android.intent.action.MY_PACKAGE_REPLACED"
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
            PreferenceHelper.recordBootEvent(context, action,
                    "自动启动已关闭（设置 → 网络服务器）", false);
            return;
        }

        /*
         * Immediate attempt, bounded by the receiver's own ~10 s window.
         *
         * WorkManager is the reliable retry path, but several OEM builds defer
         * queued work by minutes after boot - which is exactly the "sometimes
         * the web server does not start after a reboot" report. Root is usually
         * ready within a second or two, so one quick try here covers the common
         * case and the queued worker stays the safety net for slow root
         * initialisation.
         *
         * Every outcome is written to the boot diagnostics (settings screen →
         * network server → auto start), so a failed boot start can be explained
         * afterwards instead of guessed at.
         */
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();
        Thread quickStart = new Thread(() -> {
            String result;
            boolean started = false;
            try {
                if (org.adaway.util.WebServerUtils.isWebServerReachable(appContext)) {
                    Timber.d("BootReceiver: web server already reachable.");
                    PreferenceHelper.recordBootEvent(appContext, action, "服务器已在运行", true);
                    return;
                }
                long deadline = System.currentTimeMillis() + IMMEDIATE_BUDGET_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (org.adaway.model.root.ShellUtils.isRootAvailable()) {
                        org.adaway.util.WebServerUtils.startWebServer(appContext);
                        Timber.i("BootReceiver: immediate start dispatched.");
                        /* Confirm it really came up: "dispatched" is not
                           "running" (the port can be taken, the CA missing). */
                        try {
                            Thread.sleep(1_500L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        started = org.adaway.util.WebServerUtils.isWebServerReachable(appContext);
                        result = started ? "开机后立即启动成功" : "已发出启动命令，正在确认";
                        PreferenceHelper.recordBootEvent(appContext, action, result, started);
                        break;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!started && System.currentTimeMillis() >= deadline) {
                    /* Root was still not answering: the worker below keeps
                       retrying, so this is "pending", not "failed". */
                    PreferenceHelper.recordBootEvent(appContext, action,
                            "root 未就绪，已交给后台任务重试", false);
                }
            } catch (Throwable throwable) {
                Timber.w(throwable, "BootReceiver: immediate start attempt failed.");
                PreferenceHelper.recordBootEvent(appContext, action,
                        "启动出错：" + throwable.getClass().getSimpleName(), false);
            } finally {
                pending.finish();
            }
        }, "adblock-boot-start");
        quickStart.start();

        scheduleStart(appContext, action);
    }

    /**
     * Queue the WorkManager safety net that keeps retrying until root is ready
     * and the server is up. Also used by the settings screen ("重新尝试启动"),
     * which is why it is public and does not need a broadcast.
     *
     * @param context The application context.
     * @param reason The action that triggered the scheduling (diagnostics only).
     */
    public static void scheduleStart(Context context, String reason) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ServerStartWorker.class)
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                ServerStartWorker.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request);
        Timber.d("BootReceiver: web server start scheduled via WorkManager (%s).", reason);
    }
}
