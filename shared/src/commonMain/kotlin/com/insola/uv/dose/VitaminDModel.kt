package com.insola.uv.dose

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.math.PI
import kotlin.math.sin

/**
 * Crude vitamin-D synthesis proxy. The UV-B fraction collapses as the sun drops; below ~30° solar
 * elevation, atmospheric path length blocks most UV-B. Above the cutoff the rate scales with
 * sin(elevation), and there is a saturation ceiling per session.
 */
object VitaminDModel {

    enum class Bucket { None, Trace, Low, Adequate, Likely }

    private const val MIN_ELEVATION_DEG = 30.0
    private const val SATURATION_PROXY = 4.0 // UV-B-weighted index-hours that "feels" like 100%

    fun accumulate(
        forecast: UvForecast,
        from: Instant,
        to: Instant,
        skinExposedFraction: Double,
    ): Double {
        if (to <= from || forecast.samples.size < 2 || skinExposedFraction <= 0.0) return 0.0
        return forecast.samples
            .zipWithNext()
            .sumOf { (a, b) -> segmentScore(a, b, from, to, forecast.location) } * skinExposedFraction
    }

    private fun segmentScore(
        a: UvSample,
        b: UvSample,
        from: Instant,
        to: Instant,
        location: GeoPoint,
    ): Double {
        if (b.time <= from || a.time >= to) return 0.0
        val segStart = maxOf(a.time, from)
        val segEnd = minOf(b.time, to)
        if (segEnd <= segStart) return 0.0
        val mid = segStart + (segEnd - segStart) / 2
        val uvBFactor = uvBFraction(SolarGeometry.solarElevationDegrees(location, mid))
        val uvMid = interpolate(a.time, a.uvIndex, b.time, b.uvIndex, mid)
        val hours = (segEnd - segStart).inWholeMilliseconds / 3_600_000.0
        return uvMid * uvBFactor * hours
    }

    fun bucket(score: Double): Bucket = when {
        score <= 0.0 -> Bucket.None
        score < 0.25 * SATURATION_PROXY -> Bucket.Trace
        score < 0.5 * SATURATION_PROXY -> Bucket.Low
        score < SATURATION_PROXY -> Bucket.Adequate
        else -> Bucket.Likely
    }

    fun bucketForToday(
        forecast: UvForecast,
        from: Instant,
        to: Instant,
        skinExposedFraction: Double,
    ): Bucket = bucket(accumulate(forecast, from, to, skinExposedFraction))

    /** Estimate at the current moment given a point and time (used for UI dial). */
    fun instantaneousUvBFactor(point: GeoPoint, time: Instant): Double {
        val elev = SolarGeometry.solarElevationDegrees(point, time)
        return uvBFraction(elev)
    }

    private fun uvBFraction(elevationDeg: Double): Double {
        if (elevationDeg <= MIN_ELEVATION_DEG) return 0.0
        val norm = ((elevationDeg - MIN_ELEVATION_DEG) / 30.0).coerceAtMost(1.0)
        return sin(norm * PI / 2.0) // 0 at 30°, 1 at 60°+
    }

    private fun interpolate(t0: Instant, v0: Double, t1: Instant, v1: Double, at: Instant): Double {
        if (t1 == t0) return v0
        val span = (t1 - t0).inWholeMilliseconds.toDouble()
        val t = ((at - t0).inWholeMilliseconds.toDouble() / span).coerceIn(0.0, 1.0)
        return v0 + (v1 - v0) * t
    }
}
