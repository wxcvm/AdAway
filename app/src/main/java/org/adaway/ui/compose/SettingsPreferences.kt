package org.adaway.ui.compose

import android.content.Context
import timber.log.Timber

/** Shared preference contract; no composition or navigation state lives here. */
internal const val PREFS_MONITOR = "compose_app_monitor"
internal const val PREFS_GENERAL = "compose_general"

/** Whether the active-app list displays uid. Native counters are unaffected. */
internal fun isAppMonitored(context: Context, uid: Int): Boolean {
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .getBoolean("monitor_$uid", true)
}

internal fun setAppMonitored(context: Context, uid: Int, monitored: Boolean) {
    context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .edit().putBoolean("monitor_$uid", monitored).apply()
}

/* ── Per-app allowlist: bypass blocking for this uid (allow_<uid>) ── */

internal fun isAppAllowed(context: Context, uid: Int): Boolean {
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .getBoolean("allow_$uid", false)
}

/** The caller serializes writes and executes on an I/O dispatcher. */
@Synchronized
internal fun setAppAllowed(context: Context, uid: Int, allowed: Boolean): Boolean {
    val prefs = context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
    val defaults = defaultAllowlistUids(context).toSet()
    if (uid <= 0 || uid in defaults) return false
    val uids = prefs.all.filterValues { it == true }.keys
        .filter { it.startsWith("allow_") }
        .mapNotNull { it.removePrefix("allow_").toIntOrNull() }.toSet()
    return publishAppExemption(uids, defaults, uid, allowed,
        publish = { org.adaway.util.WebServerUtils.writeAllowlistFile(context, it) },
        remember = { prefs.edit().putBoolean("allow_$uid", allowed).apply() })
}

/** Publish before updating the UI preference. A failed native write must leave the preference unchanged. */
internal fun publishAppExemption(
    current: Set<Int>, defaults: Set<Int>, uid: Int, allowed: Boolean,
    publish: (String) -> Unit, remember: () -> Unit,
): Boolean {
    if (uid <= 0 || uid in defaults) return false
    val next = if (allowed) current + uid else current - uid
    return try {
        publish((next + defaults).filter { it > 0 }.sorted().joinToString("\n"))
        remember()
        true
    } catch (failure: Exception) {
        Timber.w(failure, "Failed to publish app exemption")
        false
    }
}

/* ── Allowlist inspection (default + user entries) ───────────────── */

/** Read all uid entries from the webserver's allowlist.txt. */
internal fun readAllowlistUids(context: Context): List<Int> {
    return try {
        val f = java.io.File(context.filesDir, "webserver/allowlist.txt")
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
    } catch (e: Exception) {
        Timber.w(e, "Failed to read allowlist")
        emptyList()
    }
}

/**
 * Default allowlist entries (captive portal login packages).
 * These are written automatically on every web server start and are
 * required for the system "Sign in to network" page to work.
 */
internal fun defaultAllowlistUids(context: Context): List<Int> {
    return try {
        val pm = context.packageManager
        org.adaway.util.WebServerUtils.CAPTIVE_PORTAL_PACKAGES.mapNotNull { pkg ->
            try {
                pm.getPackageUid(pkg, 0)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Failed to resolve captive portal uids")
        emptyList()
    }
}

/** User-allowed uids (allow_<uid> = true), excluding default entries. */
internal fun userAllowedUids(context: Context): List<Int> {
    val defaults = defaultAllowlistUids(context).toSet()
    return context.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        .all
        .filterKeys { it.startsWith("allow_") }
        .filterValues { it == true }
        .keys
        .mapNotNull { it.removePrefix("allow_").toIntOrNull() }
        .filter { it > 0 && it !in defaults }
        .sorted()
}

/* ── General settings (logs + chart options) ──────────────────── */


/** Whether a statistics card is enabled. Keys: chart_donut, chart_trend,
 *  chart_apps, chart_certs. All default true.
 *  Bar/line/area is NOT a separate toggle any more: it is the chart_style value
 *  (CHART_STYLE_LINE / AREA / BARS), so the old chart_bars key and its labels are gone. */
internal fun isChartEnabled(context: Context, key: String): Boolean {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getBoolean(key, true)
}

internal fun setChartEnabled(context: Context, key: String, enabled: Boolean) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putBoolean(key, enabled).apply()
}

/*
 * 趋势图样式的唯一映射 —— 设置页与趋势图共用（此前设置页的排列是
 * 柱状=0/堆叠=1/面积=2，而 StatisticsScreen 按 0=折线/1=面积/2=柱状绘制，
 * 于是用户选"柱状图"实际看到的是折线图）。
 */
internal const val CHART_STYLE_LINE = 0
internal const val CHART_STYLE_AREA = 1
internal const val CHART_STYLE_BARS = 2

internal fun chartStyle(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("chart_style", 0)
}

internal fun setChartStyle(context: Context, style: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("chart_style", style).apply()
}

/* The server retains 24 hourly / 30 daily buckets; legacy all-time maps to 30d. */

internal fun chartRange(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("chart_range", 0).coerceIn(0, 2)
}

internal fun setChartRange(context: Context, range: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("chart_range", range.coerceIn(0, 2)).apply()
}

/* ── Real-time WebSocket push toggle ──────────────────────────── */

internal fun isRealtimeEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getBoolean("realtime_enabled", false)
}

internal fun setRealtimeEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putBoolean("realtime_enabled", enabled).apply()
}

/* ── Theme mode: 0 = follow system, 1 = light, 2 = dark ────────── */

internal fun themeMode(context: Context): Int {
    return context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .getInt("theme_mode", 0)
}

internal fun setThemeMode(context: Context, mode: Int) {
    context.getSharedPreferences(PREFS_GENERAL, Context.MODE_PRIVATE)
        .edit().putInt("theme_mode", mode).apply()
}

/* ── Light mode: release the app's RAM once the boot work is done ──
   Single source of truth is PreferenceHelper: the toggle used to write its own
   "light_mode" key in a different preference file, and no code ever read it -
   which is why the switch did nothing. */

internal fun isLightMode(context: Context): Boolean =
    org.adaway.helper.PreferenceHelper.getLightMode(context)

internal fun setLightMode(context: Context, enabled: Boolean) =
    org.adaway.helper.PreferenceHelper.setLightMode(context, enabled)
