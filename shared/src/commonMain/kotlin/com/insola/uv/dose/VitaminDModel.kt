package com.insola.uv.dose

import com.insola.uv.domain.ExposureInterval
import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.math.PI
import kotlin.math.cos

/**
 * Vitamin-D dose model.
 *
 * The forecast's `uvIndex` is the CIE-erythemally-weighted UV that [BurnModel] integrates
 * against the user's MED. Vitamin-D synthesis is driven by a *different* action spectrum
 * (CIE previtamin-D3) sitting in a narrow UV-B window, so its atmospheric attenuation
 * with solar zenith angle is much steeper. The VitD/erythemal irradiance ratio is itself
 * a function of solar elevation — ~2.0 with the sun overhead, ~1.0 near SZA 55°, ~0.5
 * near SZA 75°, 0 at the horizon (McKenzie 2009; Webb & Engelsen 2006 / FastRT).
 *
 * Integration shape mirrors [DoseIntegrator] / [BurnModel] — UV-index over time — but
 * reweighted by [vitDRatio]. Accumulated score has units of *vitamin-D-weighted
 * UV-index-hours*, so buckets are fractions of the user's MED. Webb & Engelsen's
 * "Standard Vitamin D Dose" (≈1000 IU for skin type II at ~25% body exposure) lands near
 * ¼ MED of vitamin-D-weighted dose.
 *
 * Caveats:
 *  - The lumisterol/tachysterol back-reaction is not modelled; saturation is a bucket
 *    ceiling, not a curve flattening.
 *  - Ozone column is assumed typical; cloud/aerosol effects ride along inside the
 *    forecast UVI.
 *  - The CIE previtamin-D3 action spectrum was revised in 2021 (PNAS) with a ~5 nm blue
 *    shift; this model still tracks the original CIE curve underpinning McKenzie 2009.
 */
object VitaminDModel {

    enum class Bucket { None, Trace, Low, Adequate, Likely }

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

    fun accumulateOverIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
        skinExposedFraction: Double,
    ): Double = intervals.sumOf { i ->
        accumulate(forecast, i.start, i.end, skinExposedFraction * i.exposureFactor)
    }

    fun bucket(score: Double, sensitivity: SkinSensitivity): Bucket {
        val med = sensitivity.medThresholdUvIndexHours
        return when {
            score <= 0.0          -> Bucket.None
            score < 0.0625 * med  -> Bucket.Trace     // < ¼ SDD
            score < 0.125 * med   -> Bucket.Low       // ¼ – ½ SDD
            score < 0.25 * med    -> Bucket.Adequate  // ½ – 1 SDD
            else                  -> Bucket.Likely    // ≥ 1 SDD ≈ ¼ MED of vit-D-weighted dose
        }
    }

    fun bucketForIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
        sensitivity: SkinSensitivity,
        skinExposedFraction: Double,
    ): Bucket = bucket(
        accumulateOverIntervals(forecast, intervals, skinExposedFraction),
        sensitivity,
    )

    /**
     * Ratio of vitamin-D-weighted to erythemally-weighted UV at the given solar elevation.
     * Linear-in-cosine fit to McKenzie 2009 / FastRT tables: ~2.0 at zenith (SZA 0°),
     * ~1.0 near SZA 60°, ~0.5 near SZA 75°, 0 at the horizon.
     */
    fun vitDRatio(elevationDeg: Double): Double {
        if (elevationDeg <= 0.0) return 0.0
        return 2.0 * cos((90.0 - elevationDeg) * PI / 180.0)
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
        val ratio = vitDRatio(SolarGeometry.solarElevationDegrees(location, mid))
        val uvMid = interpolate(a.time, a.uvIndex, b.time, b.uvIndex, mid)
        val hours = (segEnd - segStart).inWholeMilliseconds / 3_600_000.0
        return uvMid * ratio * hours
    }

    private fun interpolate(t0: Instant, v0: Double, t1: Instant, v1: Double, at: Instant): Double {
        if (t1 == t0) return v0
        val span = (t1 - t0).inWholeMilliseconds.toDouble()
        val t = ((at - t0).inWholeMilliseconds.toDouble() / span).coerceIn(0.0, 1.0)
        return v0 + (v1 - v0) * t
    }
}
