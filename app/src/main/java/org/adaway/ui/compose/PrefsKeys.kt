package org.adaway.ui.compose

/*
 * 偏好键常量集中管理（架构优化）：
 * 所有 SharedPreferences 键名只在这里定义一次，
 * 避免散落各处导致拼写错误与维护困难。
 */
object PrefsKeys {
    // ── 偏好文件 ──
    const val PREFS_GENERAL = "compose_general"
    const val PREFS_MONITOR = "compose_app_monitor"
    const val PREFS_WEBSERVER = "compose_webserver"

    // ── 通用设置 ──
    /*
     * 这里原本有 LOG_LIMIT / LOG_RETENTION 两个键（注释写着"日志条数上限（默认500）"与
     * "日志保留小时数（0=永久）"）。但全工程没有任何地方读取它们：既没有设置界面，也没有
     * 传到原生服务端 —— 属于"看起来可配置、实际不生效"的死配置，因此删除，避免下一个人
     * 以为改这里就能控制日志留存。
     *
     * 当前真实行为（值取自 webserver/jni/webserver.c 里的宏，改这里不生效）：
     *   内存查询日志环：QLOG_MAX = 10000 条
     *   落盘保留：QLOG_PERSIST_MAX = 4096 条
     * 若将来要实现可配置的条数与保留时长，需要同时改造这两处宏、原生端读取方式与设置界面。
     */
    const val THEME_MODE = "theme_mode"        // 0=跟随系统 1=浅色 2=深色

    // ── 图表设置 ──
    const val CHART_STYLE = "chart_style"      // 0=折线 1=面积 2=柱状
    const val CHART_RANGE = "chart_range"      // 0=24h 1=7d 2=30d 3=永久
    const val CHART_LIFETIME = "chart_lifetime"
    const val CHART_RATE = "chart_rate"
    const val CHART_DONUT = "chart_donut"
    const val CHART_TREND = "chart_trend"
    const val CHART_CONN = "chart_conn"
    const val CHART_APPS = "chart_apps"
    const val CHART_CERTS = "chart_certs"
    const val CHART_QLOG = "chart_qlog"

    // ── Web 服务器 ──
    const val WS_BIND_ALL = "bind_all"
    const val WS_HTTP_PORT = "http_port"
    const val WS_HTTPS_PORT = "https_port"
    const val WS_CERT_HASH = "cert_hash"

    // ── 应用监控 ──
    const val MONITOR_PREFIX = "monitor_"      // monitor_<uid> -> Boolean
}
