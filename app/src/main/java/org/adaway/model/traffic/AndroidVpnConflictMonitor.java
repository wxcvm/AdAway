package org.adaway.model.traffic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import org.adaway.broadcast.ServerWatchdogWorker;
import org.adaway.util.BlockMode;

import timber.log.Timber;

/**
 * Event-driven, read-only foreign-VPN conflict signal.
 *
 * <p>We cannot identify the owner from TRANSPORT_VPN alone and never rewrite
 * AdGuard configuration here. Instead a new VPN transition asks the existing
 * background watchdog to re-check network ownership. Its HijackModel guard
 * removes ONLY our own iptables redirect rules when it confirms an external
 * VPN. If the process is absent, periodic WorkManager checks remain the safety
 * backstop.</p>
 */
public final class AndroidVpnConflictMonitor {
    private AndroidVpnConflictMonitor() {
    }

    /** Register once for the life of the application process. */
    public static void start(Context context) {
        Context appContext = context.getApplicationContext();
        ConnectivityManager manager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            Timber.w("Cannot register VPN conflict monitor: no ConnectivityManager");
            return;
        }
        VpnConflictDebouncer transition = new VpnConflictDebouncer();
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network,
                                                  NetworkCapabilities capabilities) {
                    boolean vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                    boolean selected = BlockMode.current(appContext) == BlockMode.HIJACK;
                    if (transition.onCapabilities(vpn, selected,
                            SystemClock.elapsedRealtime())) {
                        Timber.i("VPN transport became active; scheduling ownership safety check");
                        ServerWatchdogWorker.scheduleImmediateCheck(appContext);
                    }
                }

                @Override
                public void onLost(Network network) {
                    transition.onCapabilities(false, false, SystemClock.elapsedRealtime());
                }
            });
        } catch (SecurityException | IllegalStateException exception) {
            // Registration may fail under vendor network restrictions; the
            // periodic watchdog remains the supported safety fallback.
            Timber.w(exception, "VPN conflict monitor unavailable");
        }
    }
}
