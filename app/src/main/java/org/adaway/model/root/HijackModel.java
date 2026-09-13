package org.adaway.model.root;

import android.content.Context;
import android.content.SharedPreferences;

import com.topjohnwu.superuser.Shell;

import org.adaway.util.BlockMode;
import org.adaway.util.Constants;
import org.adaway.util.WebServerUtils;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * System-wide traffic hijack for the "hijack" blocking mode.
 *
 * <p>With root, a dedicated nat chain redirects the device's TCP 80/443
 * traffic into the bundled web server, which then answers blocked hosts with
 * the usual placeholders and forwards every other request to the real origin
 * server, filtering the response on the way back (see <code>--proxy-filter</code>
 * in webserver.c). That is what makes AdGuard-like content filtering possible
 * without a VPN: HTTPS is terminated with a per-domain certificate issued by
 * the local CA, so the CA has to be trusted by the device.</p>
 *
 * <p>Safety: everything lives in the {@code ADBLOCK_HIJACK} chain, so a
 * switch back to any other blocking mode (or the app start watchdog) removes
 * the rules again with a single flush. Traffic of the app itself and of root
 * (which is the server process), loopback and the private/LAN ranges is left
 * untouched, so local devices and the app's own control requests keep working.</p>
 *
 * @author ADBlock
 */
public final class HijackModel {
    /**
     * The dedicated nat chain holding the redirect rules.
     */
    private static final String CHAIN = "ADBLOCK_HIJACK";
    /**
     * Preference remembering that the chain is currently installed.
     */
    private static final String PREF_ACTIVE = "hijack_active";

    private HijackModel() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Whether the redirect rules were installed the last time they were enabled.
     *
     * @param context The application context.
     * @return {@code true} when a cleanup may be needed.
     */
    public static boolean wasEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ACTIVE, false);
    }

    private static List<String> enableCommands(Context context) {
        int httpPort = WebServerUtils.getHttpPort(context);
        int httpsPort = WebServerUtils.getHttpsPort(context);
        int appUid = android.os.Process.myUid();
        List<String> commands = new ArrayList<>();
        commands.add("iptables -t nat -N " + CHAIN + " 2>/dev/null || true");
        commands.add("iptables -t nat -F " + CHAIN);
        // Never touch our own traffic (root runs the server) nor the app's.
        commands.add("iptables -t nat -A " + CHAIN + " -m owner --uid-owner 0 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -m owner --uid-owner " + appUid + " -j RETURN");
        // Leave loopback, LAN and carrier-grade NAT destinations alone.
        commands.add("iptables -t nat -A " + CHAIN + " -d 0.0.0.0/8 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 10.0.0.0/8 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 100.64.0.0/10 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 127.0.0.0/8 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 169.254.0.0/16 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 172.16.0.0/12 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -d 192.168.0.0/16 -j RETURN");
        commands.add("iptables -t nat -A " + CHAIN + " -p tcp --dport 80 -j REDIRECT --to-ports " + httpPort);
        commands.add("iptables -t nat -A " + CHAIN + " -p tcp --dport 443 -j REDIRECT --to-ports " + httpsPort);
        commands.add("iptables -t nat -C OUTPUT -j " + CHAIN + " 2>/dev/null || iptables -t nat -I OUTPUT 1 -j " + CHAIN);
        return commands;
    }

    /**
     * Install the redirect rules.
     *
     * @param context The application context.
     * @return {@code true} when the rules are in place.
     */
    public static boolean enable(Context context) {
        try {
            /*
             * The redirect only makes sense when the server really listens on
             * the configured ports: if something else (AdGuard, the ROM) holds
             * 80/443, redirecting the device traffic there would black-hole it.
             * The caller reports the failure so the user can switch ports.
             */
            if (!isPortListening(WebServerUtils.getHttpPort(context))
                    || !isPortListening(WebServerUtils.getHttpsPort(context))) {
                Timber.w("Hijack aborted: the proxy ports are not listening");
                prefs(context).edit().putBoolean(PREF_ACTIVE, false).apply();
                return false;
            }
            Shell.Result result = Shell.cmd(enableCommands(context).toArray(new String[0])).exec();
            boolean active = Shell.cmd("iptables -t nat -C OUTPUT -j " + CHAIN).exec().isSuccess();
            prefs(context).edit().putBoolean(PREF_ACTIVE, active).apply();
            Timber.i("Hijack rules installed: %s (shell %s)", active, result.isSuccess());
            return active;
        } catch (Exception exception) {
            Timber.w(exception, "Failed to install the hijack rules");
            return false;
        }
    }

    /** Plain TCP connect test: is something listening on this loopback port? */
    private static boolean isPortListening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Remove the redirect rules (idempotent, safe to call at any time).
     *
     * @return {@code true} when no rule is left.
     */
    public static boolean disable() {
        try {
            Shell.Result result = Shell.cmd(
                    "iptables -t nat -D OUTPUT -j " + CHAIN + " 2>/dev/null || true",
                    "iptables -t nat -F " + CHAIN + " 2>/dev/null || true",
                    "iptables -t nat -X " + CHAIN + " 2>/dev/null || true",
                    "iptables -t nat -C OUTPUT -j " + CHAIN + " 2>/dev/null && echo STILL_ACTIVE || echo CLEAN"
            ).exec();
            boolean clean = result.getOut().stream().anyMatch(line -> line.contains("CLEAN"))
                    || !isEnabled();
            Timber.i("Hijack rules removed: %s", clean);
            return clean;
        } catch (Exception exception) {
            Timber.w(exception, "Failed to remove the hijack rules");
            return false;
        }
    }

    /**
     * Clear the "rules installed" marker without touching iptables.
     *
     * @param context The application context.
     */
    public static void clearActiveFlag(Context context) {
        prefs(context).edit().putBoolean(PREF_ACTIVE, false).apply();
    }

    /**
     * @return {@code true} when the redirect chain is currently hooked into OUTPUT.
     */
    public static boolean isEnabled() {
        try {
            return Shell.cmd("iptables -t nat -C OUTPUT -j " + CHAIN).exec().isSuccess();
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Whether the given mode needs the hijack rules.
     *
     * @param context The application context.
     * @return {@code true} for {@link BlockMode#HIJACK}.
     */
    public static boolean needsHijack(Context context) {
        return BlockMode.current(context) == BlockMode.HIJACK;
    }
}
