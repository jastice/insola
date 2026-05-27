package com.insola.uv.dose

import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.domain.lerp
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object BurnModel {

    private data class EvaluatedSegment(
        val segStart: Instant,
        val uvA: Double,
        val uvB: Double,
        val hours: Double,
        val dose: Double,
    )

    /**
     * Forward-integrates from [now] over the [forecast]; returns the duration after which the
     * accumulated dose crosses [thresholdMultiplier] × the effective MED for [profile],
     * assuming continuous [assumedFactor] exposure. Pass `1.0` for first reddening (one MED),
     * `2.0` for the "felt sunburn" line. The effective MED already folds in the user's
     * acclimatization (tan) multiplier; pure phototype baseline can be passed via
     * `SkinProfile(phototype)` with the default `Acclimatization.None`. Returns null if the
     * threshold is never crossed within the forecast.
     */
    fun timeToThreshold(
        now: Instant,
        forecast: UvForecast,
        profile: SkinProfile,
        assumedFactor: Double,
        alreadyAccumulated: Double = 0.0,
        thresholdMultiplier: Double = 1.0,
    ): Duration? {
        val threshold = profile.effectiveMedUvIndexHours * thresholdMultiplier
        if (assumedFactor <= 0.0) return null
        if (alreadyAccumulated >= threshold) return Duration.ZERO
        if (forecast.samples.size < 2) return null

        val segments = forecast.samples
            .zipWithNext()
            .mapNotNull { (a, b) -> evaluateSegment(a, b, now, assumedFactor) }

        // cumulative[k] = dose accumulated BEFORE segment k (cumulative[0] == alreadyAccumulated).
        val cumulative = segments.runningFold(alreadyAccumulated) { acc, seg -> acc + seg.dose }
        val crossIdx = cumulative.drop(1).indexOfFirst { it >= threshold }
        if (crossIdx < 0) return null

        val seg = segments[crossIdx]
        val needed = threshold - cumulative[crossIdx]
        val crossingHours = solveCrossingHours(seg.uvA, seg.uvB, seg.hours, assumedFactor, needed)
        val crossingMillis = (crossingHours * 3_600_000.0).toLong()
        return (seg.segStart - now) + crossingMillis.milliseconds
    }

    private fun evaluateSegment(
        a: UvSample,
        b: UvSample,
        now: Instant,
        factor: Double,
    ): EvaluatedSegment? {
        if (b.time <= now) return null
        val segStart = maxOf(a.time, now)
        val uvA = lerp(a.time, a.uvIndex, b.time, b.uvIndex, segStart)
        val hours = (b.time - segStart).inWholeMilliseconds / 3_600_000.0
        val dose = 0.5 * (uvA + b.uvIndex) * hours * factor
        return EvaluatedSegment(segStart, uvA, b.uvIndex, hours, dose)
    }

    /**
     * Within a segment whose UV ramps linearly from [uvA] to [uvB] over [hours], find the time
     * (in hours from segment start) at which the integrated `factor*uv` reaches [needed].
     *
     * Dose(t) = factor * (uvA*t + 0.5*(uvB-uvA)/hours * t^2). Solve quadratic.
     */
    private fun solveCrossingHours(
        uvA: Double,
        uvB: Double,
        hours: Double,
        factor: Double,
        needed: Double,
    ): Double {
        if (hours <= 0.0) return 0.0
        val slope = (uvB - uvA) / hours
        if (slope == 0.0) {
            // constant UV: dose = factor * uvA * t
            if (uvA <= 0.0) return hours
            return (needed / (factor * uvA)).coerceIn(0.0, hours)
        }
        // factor * (uvA * t + 0.5 * slope * t^2) = needed
        val a = 0.5 * slope * factor
        val b = uvA * factor
        val c = -needed
        val disc = b * b - 4 * a * c
        if (disc < 0) return hours
        val sqrt = kotlin.math.sqrt(disc)
        val t1 = (-b + sqrt) / (2 * a)
        val t2 = (-b - sqrt) / (2 * a)
        val candidates = listOf(t1, t2).filter { it in 0.0..hours }
        return candidates.minOrNull() ?: hours
    }
}
