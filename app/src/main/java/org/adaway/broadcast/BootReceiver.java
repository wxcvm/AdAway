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

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ServerStartWorker.class)
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                ServerStartWorker.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request);
        Timber.d("BootReceiver: web server start scheduled via WorkManager.");
    }
}
