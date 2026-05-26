package com.insola.uv.dose

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class VitaminDModelTest {

    @Test
    fun arcticWinter_negligibleVitaminD() {
        // Reykjavik on the winter solstice: sun clears the horizon for only ~3h, peaking at
        // ~2.4°. Use a realistic UV peak of 0.2; the VitD/erythemal ratio collapses near the
        // horizon, so the accumulated score stays at Trace or below.
        val reykjavik = GeoPoint(64.13, -21.94)
        val start = Instant.parse("2026-12-21T08:00:00Z")
        val end = Instant.parse("2026-12-21T16:00:00Z")
        val samples = (0..8).map { UvSample(start + it.hours, 0.2) }
        val forecast = UvForecast(reykjavik, samples)
        val score = VitaminDModel.accumulate(forecast, start, end, skinExposedFraction = 0.5)
        assertTrue(
            VitaminDModel.bucket(score, SkinSensitivity.III) <= VitaminDModel.Bucket.Trace,
            "expected at most Trace, got ${VitaminDModel.bucket(score, SkinSensitivity.III)} (score=$score)",
        )
    }

    @Test
    fun equatorialNoon_producesSomeVitaminD() {
        val equator = GeoPoint(0.0, 0.0)
        val start = Instant.parse("2026-03-20T10:00:00Z")
        val end = Instant.parse("2026-03-20T14:00:00Z")
        val samples = (0..4).map { UvSample(start + it.hours, 10.0) }
        val forecast = UvForecast(equator, samples)
        val score = VitaminDModel.accumulate(forecast, start, end, skinExposedFraction = 0.5)
        assertTrue(score > 0.0, "expected positive vit-D score, got $score")
        assertTrue(VitaminDModel.bucket(score, SkinSensitivity.III) != VitaminDModel.Bucket.None)
    }

    @Test
    fun skinExposureScalesLinearly() {
        val equator = GeoPoint(0.0, 0.0)
        val start = Instant.parse("2026-03-20T10:00:00Z")
        val end = Instant.parse("2026-03-20T13:00:00Z")
        val samples = (0..3).map { UvSample(start + it.hours, 8.0) }
        val forecast = UvForecast(equator, samples)
        val full = VitaminDModel.accumulate(forecast, start, end, 1.0)
        val half = VitaminDModel.accumulate(forecast, start, end, 0.5)
        assertEquals(full * 0.5, half, 1e-6)
    }
}
