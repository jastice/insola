package com.insola.uv.domain

import kotlinx.datetime.Instant

/**
 * Linear interpolation between two timestamped scalar samples. Clamps to `[v0, v1]` outside
 * `[t0, t1]`. Returns `v0` when the two timestamps coincide.
 */
fun lerp(t0: Instant, v0: Double, t1: Instant, v1: Double, at: Instant): Double {
    if (t1 == t0) return v0
    val span = (t1 - t0).inWholeMilliseconds.toDouble()
    val t = ((at - t0).inWholeMilliseconds.toDouble() / span).coerceIn(0.0, 1.0)
    return v0 + (v1 - v0) * t
}

/**
 * Linearly interpolated UV index at [time]. Returns 0.0 outside the forecast window.
 */
fun UvForecast.uvAt(time: Instant): Double {
    if (samples.isEmpty()) return 0.0
    if (time <= samples.first().time) return samples.first().uvIndex
    if (time >= samples.last().time) return samples.last().uvIndex
    val (a, b) = samples.zipWithNext().first { (a, b) -> time in a.time..b.time }
    return lerp(a.time, a.uvIndex, b.time, b.uvIndex, time)
}
