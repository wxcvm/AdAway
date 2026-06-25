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
import static org.adaway.model.adblocking.AdBlockMethod.ROOT;
import static org.adaway.model.adblocking.AdBlockMethod.VPN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.util.WebServerUtils;
import org.adaway.vpn.VpnServiceControls;

import timber.log.Timber;

/**
 * This broadcast receiver is executed after boot.
 *
 * <p>All work runs on a background thread via {@link #goAsync()} so that
 * blocking root-shell operations cannot trigger the 10-second
 * BroadcastReceiver timeout that would cause the process to be killed
 * before the web server command is even dispatched.
 *
 * <p>After the boot work is done the Java process terminates itself.
 * The web server ({@code libwebserver_exec.so}) is a detached native
 * process that survives independently, so no memory is wasted keeping
 * the Java runtime alive just to maintain something that is already
 * running in its own process.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Timber.d("BootReceiver invoked.");

        // goAsync() tells Android not to kill this process when onReceive()
        // returns, giving the background thread time to finish its work.
        final PendingResult pendingResult = goAsync();

        new Thread(() -> {
            try {
                doBootWork(context);
            } catch (Exception e) {
                Timber.e(e, "BootReceiver: unexpected error during boot initialisation.");
            } finally {
                // Allow Android to clean up the broadcast state.
                pendingResult.finish();

                // Give the native server process 2 seconds to fully start
                // (write its cert, bind its ports) before we exit, just in
                // case the shell needs the parent process alive momentarily.
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                // Terminate the Java process. The web server is a separate
                // native process and is unaffected by this. Any future user
                // interaction will restart the Java process normally.
                Timber.d("BootReceiver: boot work done, releasing Java process.");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "boot-init").start();
    }

    private void doBootWork(Context context) {
        AdBlockMethod adBlockMethod = PreferenceHelper.getAdBlockMethod(context);

        if (adBlockMethod == ROOT && PreferenceHelper.getWebServerEnabled(context)) {
            Timber.d("BootReceiver: starting web server.");
            WebServerUtils.startWebServer(context);
        }

        if (adBlockMethod == VPN && PreferenceHelper.getVpnServiceOnBoot(context)) {
            Timber.d("BootReceiver: starting VPN service.");
            Intent prepareIntent = android.net.VpnService.prepare(context);
            if (prepareIntent != null) {
                // VPN needs user interaction to be prepared; can't do that
                // at boot time without a UI, so skip.
                Timber.w("BootReceiver: VPN not prepared, skipping auto-start.");
                return;
            }
            VpnServiceControls.start(context);
        }
    }
}
