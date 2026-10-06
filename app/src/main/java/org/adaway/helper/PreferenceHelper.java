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

package org.adaway.helper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import org.adaway.R;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.util.Constants;

import java.util.Collections;
import java.util.Set;

public final class PreferenceHelper {
    /**
     * Preference key for the optional keep-alive foreground service. Kept as a
     * plain constant: it is internal, never shown in a settings screen, so it
     * does not need a string resource.
     */
    private static final String PREF_KEEPALIVE_ENABLED = "keepalive_enabled";

    /** Preference key of "light mode" (fewest resident resources). */
    private static final String PREF_LIGHT_MODE = "light_mode";

    private PreferenceHelper() {

    }

    public static int getDarkThemeMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        String pref = prefs.getString(
                context.getString(R.string.pref_dark_theme_mode_key),
                context.getResources().getString(R.string.pref_dark_theme_mode_def)
        );
        switch (pref) {
            case "MODE_NIGHT_NO":
                return AppCompatDelegate.MODE_NIGHT_NO;
            case "MODE_NIGHT_YES":
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    public static boolean getUpdateCheck(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_check_key),
                context.getResources().getBoolean(R.bool.pref_update_check_def)
        );
    }

    public static boolean getNeverReboot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_never_reboot_key),
                context.getResources().getBoolean(R.bool.pref_never_reboot_def)
        );
    }

    public static void setNeverReboot(Context context, boolean value) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(context.getString(R.string.pref_never_reboot_key), value);
        editor.apply();
    }

    public static boolean getEnableIpv6(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_enable_ipv6_key),
                context.getResources().getBoolean(R.bool.pref_enable_ipv6_def)
        );
    }

    public static boolean getUpdateCheckAppStartup(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_check_app_startup_key),
                context.getResources().getBoolean(R.bool.pref_update_check_app_startup_def)
        );
    }

    public static boolean getUpdateCheckAppDaily(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_check_app_daily_key),
                context.getResources().getBoolean(R.bool.pref_update_check_app_daily_def)
        );
    }

    public static boolean getIncludeBetaReleases(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_include_beta_releases_key),
                context.getResources().getBoolean(R.bool.pref_update_include_beta_releases_def)
        );
    }

    public static boolean getUpdateCheckHostsDaily(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_check_hosts_daily_key),
                context.getResources().getBoolean(R.bool.pref_update_check_hosts_daily_def)
        );
    }

    public static boolean getAutomaticUpdateDaily(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_automatic_update_daily_key),
                context.getResources().getBoolean(R.bool.pref_automatic_update_daily_def)
        );
    }

    public static boolean getUpdateOnlyOnWifi(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_update_only_on_wifi_key),
                context.getResources().getBoolean(R.bool.pref_update_only_on_wifi_def)
        );
    }

    public static String getRedirectionIpv4(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getString(
                context.getString(R.string.pref_redirection_ipv4_key),
                context.getString(R.string.pref_redirection_ipv4_def)
        );
    }

    public static String getRedirectionIpv6(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getString(
                context.getString(R.string.pref_redirection_ipv6_key),
                context.getString(R.string.pref_redirection_ipv6_def)
        );
    }

    public static boolean getWebServerEnabled(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(
                context.getString(R.string.pref_webserver_enabled_key),
                context.getResources().getBoolean(R.bool.pref_webserver_enabled_def)
        );
    }

    /**
     * Whether the optional keep-alive foreground service should run.
     *
     * <p>Off by default: it buys a much faster restart of the intercept server
     * (seconds instead of the WorkManager minimum of many minutes) at the cost
     * of a permanent notification, so the user opts in explicitly.</p>
     *
     * @param context The application context.
     * @return {@code true} when the keep-alive service is wanted.
     */
    public static boolean getKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(PREF_KEEPALIVE_ENABLED, false);
    }

    /**
     * Store whether the keep-alive foreground service should run.
     *
     * @param context The application context.
     * @param enabled Whether the service should run.
     */
    public static void setKeepAliveEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        prefs.edit()
                .putBoolean(PREF_KEEPALIVE_ENABLED, enabled)
                .apply();
    }

    /**
     * Whether "light mode" is on (the default: keep the resident footprint as
     * small as possible).
     *
     * <p>The intercept server is a detached native process, so the app process
     * itself is dead weight once the boot work is done - it measured ~180&nbsp;MB
     * RSS on the reference device, which is the single biggest thing this app
     * keeps in RAM. Light mode therefore has two effects:</p>
     * <ul>
     *     <li>after a <b>boot</b> start confirmed the server is up, the app
     *     process exits once it is in the background (never while an activity is
     *     visible, and never when the opt-in keep-alive service is on);</li>
     *     <li>the watchdog drops from a self-rescheduling 5 minute chain (each
     *     run may spawn a root shell) to a single 6 hour periodic check - except
     *     in hijack mode, where a dead server would mean "no internet at all"
     *     and the fast chain has to stay.</li>
     * </ul>
     *
     * @param context The application context.
     * @return {@code true} when light mode is enabled.
     */
    public static boolean getLightMode(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return prefs.getBoolean(PREF_LIGHT_MODE, true);
    }

    /**
     * Store whether "light mode" is on.
     *
     * @param context The application context.
     * @param enabled Whether light mode should be enabled.
     */
    public static void setLightMode(Context context, boolean enabled) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        prefs.edit()
                .putBoolean(PREF_LIGHT_MODE, enabled)
                .apply();
    }

    public static AdBlockMethod getAdBlockMethod(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        return AdBlockMethod.fromCode(prefs.getInt(
                context.getString(R.string.pref_ad_block_method_key),
                context.getResources().getInteger(R.integer.pref_ad_block_method_key_def)
        ));
    }

    public static void setAbBlockMethod(Context context, AdBlockMethod method) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(context.getString(R.string.pref_ad_block_method_key), method.toCode());
        editor.apply();
    }

    public static boolean getDebugEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(
                context.getString(R.string.pref_enable_debug_key),
                context.getResources().getBoolean(R.bool.pref_enable_debug_def)
        );
    }

    public static boolean getTelemetryEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(
                context.getString(R.string.pref_enable_telemetry_key),
                context.getResources().getBoolean(R.bool.pref_enable_telemetry_def)
        );
    }

    public static void setTelemetryEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(context.getString(R.string.pref_enable_telemetry_key), enabled);
        editor.apply();
    }

    public static boolean getDisplayTelemetryConsent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(
                context.getString(R.string.pref_display_telemetry_consent_key),
                context.getResources().getBoolean(R.bool.pref_display_telemetry_consent_def)
        );
    }

    public static void setDisplayTelemetryConsent(Context context, boolean display) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(context.getString(R.string.pref_display_telemetry_consent_key), display);
        editor.apply();
    }

    /**
     * Turn the background web server on or off. This is the flag
     * {@link org.adaway.broadcast.BootReceiver} and the watchdog worker check
     * before starting it, so switching it off also disables the automatic start
     * after a reboot.
     *
     * @param context The application context.
     * @param enabled Whether the web server should run in the background.
     */
    public static void setWebServerEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        prefs.edit()
                .putBoolean(context.getString(R.string.pref_webserver_enabled_key), enabled)
                .apply();
    }

    /*
     * ── "start after reboot" diagnostics ─────────────────────────────
     *
     * "The server sometimes does not start after a reboot" cannot be
     * diagnosed from a logcat dump on a phone, so the boot receiver and the
     * WorkManager worker record what happened and the settings screen shows it:
     * which boot broadcast arrived, when, and what the start attempt ended up
     * doing. The keys are literals on purpose - they are debug data, not user
     * settings, and must not be renamed by translation.
     */

    /** Intent action of the last boot/update broadcast ("" when never seen). */
    private static final String PREF_BOOT_ACTION = "boot_diag_last_action";

    /** Wall clock time of that broadcast (0 when never seen). */
    private static final String PREF_BOOT_TIME = "boot_diag_last_time";

    /** Human readable outcome of the start attempt. */
    private static final String PREF_BOOT_RESULT = "boot_diag_last_result";

    /** Whether the last recorded attempt ended with a running server. */
    private static final String PREF_BOOT_OK = "boot_diag_last_ok";

    /** Which mechanism performed the last attempt (前台服务 / 后台任务 / …). */
    private static final String PREF_BOOT_MECHANISM = "boot_diag_last_mechanism";

    /**
     * Ring of the last {@link #BOOT_RING_MAX} attempts, one
     * "time|action|mechanism|result|ok" line each. "The server did not start at
     * boot" cannot be diagnosed from a single line: which mechanism ran first
     * and what the fallbacks replied is exactly what tells the two failure
     * classes apart (broadcast never delivered vs. work deferred by the ROM).
     */
    private static final String PREF_BOOT_RING = "boot_diag_ring";

    /** How many attempts the diagnostics view shows. */
    private static final int BOOT_RING_MAX = 8;

    /**
     * Record one boot start attempt. Must be called from the receiver/worker
     * threads; {@code apply()} keeps it off the critical path.
     *
     * @param context The application context.
     * @param action The broadcast action that triggered the attempt.
     * @param result A short outcome text (already localised).
     * @param started Whether the server was confirmed running afterwards.
     */
    public static void recordBootEvent(Context context, String action, String result, boolean started) {
        recordBootEvent(context, action, "", result, started);
    }

    /**
     * Record one boot start attempt together with the mechanism that ran it.
     *
     * @param context The application context.
     * @param action The broadcast action ("" when not triggered by one).
     * @param mechanism Which path performed the attempt.
     * @param result A short outcome text (already localised).
     * @param started Whether the server was confirmed running afterwards.
     */
    public static void recordBootEvent(Context context, String action, String mechanism,
                                       String result, boolean started) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        long now = System.currentTimeMillis();
        String line = now + "|" + cleanField(action) + "|" + cleanField(mechanism) + "|"
                + cleanField(result) + "|" + (started ? "1" : "0");
        String ring = prefs.getString(PREF_BOOT_RING, "");
        StringBuilder next = new StringBuilder();
        if (ring != null && !ring.isEmpty()) {
            String[] lines = ring.split("\n");
            int keep = Math.min(lines.length, BOOT_RING_MAX - 1);
            for (int i = lines.length - keep; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    next.append(lines[i]).append('\n');
                }
            }
        }
        next.append(line);
        prefs.edit()
                .putString(PREF_BOOT_ACTION, action == null ? "" : action)
                .putString(PREF_BOOT_MECHANISM, mechanism == null ? "" : mechanism)
                .putString(PREF_BOOT_RESULT, result == null ? "" : result)
                .putBoolean(PREF_BOOT_OK, started)
                .putLong(PREF_BOOT_TIME, now)
                .putString(PREF_BOOT_RING, next.toString())
                .apply();
    }

    /** One ring field: the separators must not appear inside a value. */
    private static String cleanField(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * @return The mechanism of the last attempt ("" when unknown/old record).
     */
    public static String getLastBootMechanism(Context context) {
        return prefsOf(context).getString(PREF_BOOT_MECHANISM, "");
    }

    /**
     * @return The recorded attempts, oldest first, as raw
     *         "time|action|mechanism|result|ok" lines (may be empty).
     */
    public static java.util.List<String> getBootEventRing(Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String ring = prefsOf(context).getString(PREF_BOOT_RING, "");
        if (ring != null) {
            for (String line : ring.split("\n")) {
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /**
     * @return The action of the last boot broadcast, or an empty string.
     */
    public static String getLastBootAction(Context context) {
        return prefsOf(context).getString(PREF_BOOT_ACTION, "");
    }

    /**
     * @return The recorded outcome of the last boot start attempt.
     */
    public static String getLastBootResult(Context context) {
        return prefsOf(context).getString(PREF_BOOT_RESULT, "");
    }

    /**
     * @return When the last boot broadcast was handled (0 = never).
     */
    public static long getLastBootTime(Context context) {
        return prefsOf(context).getLong(PREF_BOOT_TIME, 0L);
    }

    /**
     * @return Whether the last recorded boot start ended with the server up.
     */
    public static boolean isLastBootStartOk(Context context) {
        return prefsOf(context).getBoolean(PREF_BOOT_OK, false);
    }

    private static SharedPreferences prefsOf(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                Constants.PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

}
