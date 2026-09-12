package org.adaway.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.adaway.R;

/**
 * The hosts redirection mode, i.e. the address every blocked host is mapped to.
 *
 * <ul>
 *     <li>{@link #LOCALHOST}: blocked hosts point at 127.0.0.1 / ::1, where the
 *     bundled web server answers with the block page and placeholder images.
 *     This is what makes per-app blocking statistics possible, and it needs the
 *     80 / 443 ports.</li>
 *     <li>{@link #NULL_ROUTE}: blocked hosts point at 0.0.0.0 / ::, so the
 *     connection simply fails. No port is used, which lets the app run next to
 *     AdGuard (or any other proxy/filter) without fighting for 80/443.</li>
 *     <li>{@link #HIJACK}: same hosts targets as {@link #LOCALHOST}, plus the
 *     whole TCP 80/443 traffic of the device is redirected into the bundled
 *     server (root + iptables, see HijackModel), which then forwards
 *     non-blocked hosts to the real origin and filters the answer -
 *     AdGuard-like content filtering, including HTTPS (MITM with the local CA).</li>
 *     <li>{@link #CUSTOM}: any other user defined address.</li>
 * </ul>
 *
 * @author ADBlock
 */
public final class BlockMode {
    /**
     * Block by redirecting to the local web server (127.0.0.1 / ::1).
     */
    public static final int LOCALHOST = 0;
    /**
     * Block by null routing (0.0.0.0 / ::).
     */
    public static final int NULL_ROUTE = 1;
    /**
     * Any other address configured by the user.
     */
    public static final int CUSTOM = 2;
    /**
     * Hijack the whole device traffic and filter it like AdGuard does.
     */
    public static final int HIJACK = 3;

    /**
     * The IPv6 null route address.
     */
    public static final String NULL_ROUTE_IPV6 = "::";

    /**
     * Preference remembering that the iptables hijack rules are installed.
     */
    public static final String PREF_HIJACK_ENABLED = "hijack_enabled";

    private BlockMode() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String ipv4Key(Context context) {
        return context.getString(R.string.pref_redirection_ipv4_key);
    }

    private static String ipv6Key(Context context) {
        return context.getString(R.string.pref_redirection_ipv6_key);
    }

    /**
     * Apply a mode by writing the legacy redirection preferences used to
     * generate the hosts file.
     *
     * @param context The application context.
     * @param mode    One of {@link #LOCALHOST}, {@link #NULL_ROUTE}, {@link #HIJACK}.
     */
    public static void apply(Context context, int mode) {
        String ipv4;
        String ipv6;
        switch (mode) {
            case NULL_ROUTE:
                ipv4 = Constants.BOGUS_IPV4;
                ipv6 = NULL_ROUTE_IPV6;
                break;
            case LOCALHOST:
            case HIJACK:
            default:
                ipv4 = Constants.LOCALHOST_IPV4;
                ipv6 = Constants.LOCALHOST_IPV6;
                break;
        }
        prefs(context).edit()
                .putString(ipv4Key(context), ipv4)
                .putString(ipv6Key(context), ipv6)
                .putBoolean(PREF_HIJACK_ENABLED, mode == HIJACK)
                .apply();
    }

    /**
     * Get the currently configured mode.
     *
     * @param context The application context.
     * @return One of {@link #LOCALHOST}, {@link #NULL_ROUTE}, {@link #HIJACK} or {@link #CUSTOM}.
     */
    public static int current(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(PREF_HIJACK_ENABLED, false)) {
            return HIJACK;
        }
        String ipv4 = prefs.getString(ipv4Key(context), context.getString(R.string.pref_redirection_ipv4_def));
        String ipv6 = prefs.getString(ipv6Key(context), context.getString(R.string.pref_redirection_ipv6_def));
        if (Constants.BOGUS_IPV4.equals(ipv4)) {
            return NULL_ROUTE;
        }
        if (Constants.LOCALHOST_IPV4.equals(ipv4) && Constants.LOCALHOST_IPV6.equals(ipv6)) {
            return LOCALHOST;
        }
        return CUSTOM;
    }

    /**
     * Whether the bundled web server is required to block.
     *
     * @param context The application context.
     * @return {@code true} when the web server is part of the blocking path.
     */
    public static boolean needsWebServer(Context context) {
        return current(context) != NULL_ROUTE;
    }
}
