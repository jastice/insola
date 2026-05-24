package com.insola.uv.dose

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DoseIntegratorTest {

    private val origin = Instant.parse("2026-06-21T06:00:00Z")
    private val location = GeoPoint(0.0, 0.0)

    private fun forecast(curve: List<Double>): UvForecast {
        val samples = curve.mapIndexed { i, uv ->
            UvSample(origin + i.hours, uv)
        }
        return UvForecast(location, samples)
    }

    @Test
    fun flatUv3Over2Hours_isSix() {
        val f = forecast(listOf(3.0, 3.0, 3.0))
        val dose = DoseIntegrator.integrate(f, origin, origin + 2.hours, 1.0)
        assertEquals(6.0, dose, 1e-9)
    }

    @Test
    fun nighttimeZero() {
        val f = forecast(listOf(0.0, 0.0, 0.0, 0.0))
        val dose = DoseIntegrator.integrate(f, origin, origin + 3.hours, 1.0)
        assertEquals(0.0, dose, 1e-9)
    }

    @Test
    fun risingAndFalling_haveSameArea() {
        val rising = forecast(listOf(0.0, 2.0, 4.0, 6.0))
        val falling = forecast(listOf(6.0, 4.0, 2.0, 0.0))
        val end = origin + 3.hours
        val rDose = DoseIntegrator.integrate(rising, origin, end, 1.0)
        val fDose = DoseIntegrator.integrate(falling, origin, end, 1.0)
        assertEquals(rDose, fDose, 1e-9)
        assertEquals(9.0, rDose, 1e-9)
    }

    @Test
    fun exposureFactorScalesLinearly() {
        val f = forecast(listOf(4.0, 4.0))
        val full = DoseIntegrator.integrate(f, origin, origin + 1.hours, 1.0)
        val half = DoseIntegrator.integrate(f, origin, origin + 1.hours, 0.5)
        assertEquals(full * 0.5, half, 1e-9)
    }

    @Test
    fun partialSegmentClipsCorrectly() {
        val f = forecast(listOf(2.0, 2.0))
        val half = DoseIntegrator.integrate(
            f,
            origin + 15.minutes,
            origin + 45.minutes,
            1.0,
        )
        assertEquals(1.0, half, 1e-9)
    }

    @Test
    fun outOfRangeWindowIsZero() {
        val f = forecast(listOf(3.0, 3.0))
        val before = DoseIntegrator.integrate(
            f,
            origin - 2.hours,
            origin - 1.hours,
            1.0,
        )
        assertEquals(0.0, before, 1e-9)
    }

    @Test
    fun integrationIsMonotonicallyIncreasing() {
        val f = forecast(listOf(1.0, 3.0, 5.0, 4.0, 2.0))
        var prev = 0.0
        for (h in 1..4) {
            val dose = DoseIntegrator.integrate(f, origin, origin + h.hours, 1.0)
            assertTrue(dose >= prev, "dose at hour $h should not decrease")
            prev = dose
        }
    }
}
