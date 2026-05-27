package com.insola.uv.dose

import com.insola.uv.domain.ExposureInterval
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import com.insola.uv.domain.lerp
import kotlinx.datetime.Instant

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
        val uvA = lerp(a.time, a.uvIndex, b.time, b.uvIndex, segStart)
        val uvB = lerp(a.time, a.uvIndex, b.time, b.uvIndex, segEnd)
        val hours = (segEnd - segStart).inWholeMilliseconds / 3_600_000.0
        return 0.5 * (uvA + uvB) * hours
    }

    fun integrateOverIntervals(
        forecast: UvForecast,
        intervals: List<ExposureInterval>,
    ): Double = intervals.sumOf { interval ->
        integrate(forecast, interval.start, interval.end, interval.exposureFactor)
    }
}
