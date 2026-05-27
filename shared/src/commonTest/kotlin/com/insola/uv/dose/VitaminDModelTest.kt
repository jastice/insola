package com.insola.uv.dose

import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.SkinProfile
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
            VitaminDModel.bucket(score, SkinProfile(SkinSensitivity.III)) <= VitaminDModel.Bucket.Trace,
            "expected at most Trace, got ${VitaminDModel.bucket(score, SkinProfile(SkinSensitivity.III))} (score=$score)",
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
        assertTrue(VitaminDModel.bucket(score, SkinProfile(SkinSensitivity.III)) != VitaminDModel.Bucket.None)
    }

    @Test
    fun effectiveYield_dividesByAcclimatizationFactor() {
        // Melanin (constitutive + acclimatized) competes with 7-DHC for UV-B photons (Webb 2010,
        // Bogh 2010). The model represents this as a divide-by-factor on the raw score before
        // bucket comparison. Phototype-side pigmentation is implicit in the SDD denominator, so
        // effectiveYield is parameterised on acclimatization only.
        val score = 1.0
        val none = SkinProfile(SkinSensitivity.III, Acclimatization.None)
        val moderate = SkinProfile(SkinSensitivity.III, Acclimatization.Moderate)
        val deep = SkinProfile(SkinSensitivity.III, Acclimatization.Deep)
        assertEquals(1.0, VitaminDModel.effectiveYield(score, none), 1e-9)
        assertEquals(1.0 / 2.2, VitaminDModel.effectiveYield(score, moderate), 1e-9)
        // III cap is 2.5×, so Deep (3.0) clips to 2.5.
        assertEquals(1.0 / 2.5, VitaminDModel.effectiveYield(score, deep), 1e-9)
    }

    @Test
    fun bucketBoundary_movesDownwardWithTan() {
        // Construct a raw score that sits exactly in the middle of the Adequate band for the
        // untanned profile: between ½ SDD and 1 SDD. Applying Acclimatization.Moderate (×2.2)
        // pushes the *effective* yield below ½ SDD, so the bucket drops from Adequate to Low.
        val phototype = SkinSensitivity.III
        val sdd = phototype.medThresholdUvIndexHours / 16.0
        val score = 0.75 * sdd
        val untanned = VitaminDModel.bucket(score, SkinProfile(phototype, Acclimatization.None))
        val tanned = VitaminDModel.bucket(score, SkinProfile(phototype, Acclimatization.Moderate))
        assertEquals(VitaminDModel.Bucket.Adequate, untanned)
        assertEquals(VitaminDModel.Bucket.Low, tanned)
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
