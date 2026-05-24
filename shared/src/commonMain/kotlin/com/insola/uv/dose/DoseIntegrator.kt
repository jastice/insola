package com.insola.uv.dose

import com.insola.uv.domain.ExposureInterval
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import kotlinx.datetime.Instant
import kotlin.math.max
import kotlin.math.min

object DoseIntegrator {

    fun integrate(
        forecast: UvForecast,
        from: Instant,
        to: Instant,
        exposureFactor: Double,
    ): Double {
        if (to <= from || forecast.samples.size < 2 || exposureFactor <= 0.0) return 0.0
        return forecast.samples
            .zipWithNext()
            .fold(0.0) { acc, (a, b) -> acc + segmentDose(a, b, from, to) } * exposureFactor
    }

    private fun segmentDose(a: UvSample, b: UvSample, from: Instant, to: Instant): Double {
        if (b.time <= from || a.time >= to) return 0.0
        val segStart = maxOf(a.time, from)
        val segEnd = minOf(b.time, to)
        if (segEnd <= segStart) return 0.0
        val uvA = interpolate(a, b, segStart)
        val uvB = interpolate(a, b, segEnd)
        val hours = (segEnd - segStart).inWholeMilliseconds / 3_600_000.0
        return 0.5 * (uvA + uvB) * hours
    }

    fun integrateOverIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
    ): Double = intervals.sumOf { interval ->
        integrate(forecast, interval.start, interval.end, interval.exposureFactor)
    }

    private fun interpolate(a: UvSample, b: UvSample, at: Instant): Double {
        if (b.time == a.time) return a.uvIndex
        val span = (b.time - a.time).inWholeMilliseconds.toDouble()
        val t = (at - a.time).inWholeMilliseconds.toDouble() / span
        val clamped = min(1.0, max(0.0, t))
        return a.uvIndex + (b.uvIndex - a.uvIndex) * clamped
    }
}
