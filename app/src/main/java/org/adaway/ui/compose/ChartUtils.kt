package org.adaway.ui.compose

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 图表通用工具：数字格式化、时间标签、颜色映射。
 * 集中管理避免在各 Screen 中重复实现（架构优化：去重）。
 */
object ChartUtils {

    /**
     * 将大数字格式化为紧凑形式：1_234 → "1.2k"，3_456_789 → "3.5M"。
     * 用于图表 Y 轴刻度，避免超长数字溢出绘图区域。
     */
    fun compactNumber(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000f)
        else -> "$value"
    }

    /**
     * 根据图表模式返回 X 轴时间标签：
     *  - hourly（24h）：HH:mm
     *  - daily（7d/30d/all）：MM/dd
     */
    fun axisLabel(tsEpochSeconds: Long, hourly: Boolean): String {
        val fmt = if (hourly) "HH:mm" else "MM/dd"
        return SimpleDateFormat(fmt, Locale.US).format(Date(tsEpochSeconds * 1000L))
    }

    /** X 轴标签步进：数据点越多步进越大，避免标签重叠。 */
    fun labelStep(pointCount: Int): Int = when {
        pointCount <= 6 -> 1
        pointCount <= 12 -> 2
        pointCount <= 24 -> 4
        else -> pointCount / 6
    }

    /** 千分位显示（KPI 卡、统计卡数值）。 */
    fun groupedNumber(value: Long): String = String.format(Locale.US, "%,d", value)
}
