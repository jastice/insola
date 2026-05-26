package com.insola.uv.dashboard

import kotlin.math.round
import kotlin.time.Duration

internal fun formatNumber(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val factor = listOf(1.0, 10.0, 100.0, 1000.0).getOrElse(decimals) { 1.0 }
    val rounded = round(value * factor) / factor
    return if (decimals == 0) rounded.toLong().toString()
    else {
        val s = rounded.toString()
        val dotIdx = s.indexOf('.')
        if (dotIdx < 0) "$s.${"0".repeat(decimals)}"
        else {
            val frac = s.substring(dotIdx + 1).padEnd(decimals, '0').take(decimals)
            s.substring(0, dotIdx) + "." + frac
        }
    }
}

internal fun formatClock(hourOfDay: Double): String {
    val totalMinutes = (hourOfDay * 60).toInt().coerceIn(0, 24 * 60)
    val hh = (totalMinutes / 60).coerceAtMost(23)
    val mm = totalMinutes % 60
    return hh.toString().padStart(2, '0') + ":" + mm.toString().padStart(2, '0')
}

internal fun formatDuration(d: Duration): String {
    val totalMinutes = d.inWholeMinutes.coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h <= 0 -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
