package com.insola.uv.domain

import kotlinx.datetime.Instant

/**
 * Linearly interpolated UV index at [time]. Returns 0.0 outside the forecast window.
 */
fun UvForecast.uvAt(time: Instant): Double {
    if (samples.isEmpty()) return 0.0
    if (time <= samples.first().time) return samples.first().uvIndex
    if (time >= samples.last().time) return samples.last().uvIndex
    val pair = samples.zipWithNext().first { (a, b) -> time in a.time..b.time }
    val (a, b) = pair
    val span = (b.time - a.time).inWholeMilliseconds.toDouble()
    if (span == 0.0) return a.uvIndex
    val t = ((time - a.time).inWholeMilliseconds.toDouble() / span).coerceIn(0.0, 1.0)
    return a.uvIndex + (b.uvIndex - a.uvIndex) * t
}
