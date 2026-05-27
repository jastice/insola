package com.insola.uv.dose

import com.insola.uv.domain.GeoPoint
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.UvForecast
import com.insola.uv.domain.UvSample
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class BurnModelTest {

    private val origin = Instant.parse("2026-06-21T06:00:00Z")
    private val location = GeoPoint(0.0, 0.0)

    private fun forecast(curve: List<Double>): UvForecast {
        val samples = curve.mapIndexed { i, uv ->
            UvSample(origin + i.hours, uv)
        }
        return UvForecast(location, samples)
    }

    @Test
    fun flatUv3TypeIII_crossesAtExpectedTime() {
        // Type III MED = 3.9 UV-index-hours. At constant UV 3: 3.9 / 3 h = 78 min.
        val f = forecast(List(8) { 3.0 })
        val ttb = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.III), assumedFactor = 1.0)
        assertNotNull(ttb)
        assertEquals(78L, ttb.inWholeMinutes)
    }

    @Test
    fun nightNeverBurns() {
        val f = forecast(List(8) { 0.0 })
        val ttb = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.II), assumedFactor = 1.0)
        assertNull(ttb)
    }

    @Test
    fun sunscreenDelaysBurn() {
        val f = forecast(List(8) { 5.0 })
        val noSpf = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.II), assumedFactor = 1.0)
        val withSpf30 = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.II), assumedFactor = 1.0 / 30.0)
        assertNotNull(noSpf)
        if (withSpf30 != null) {
            assertTrue(withSpf30 > noSpf)
        }
    }

    @Test
    fun darkerSkinTakesLonger() {
        val f = forecast(List(8) { 4.0 })
        val typeII = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.II), 1.0)
        val typeV = BurnModel.timeToThreshold(origin, f, SkinProfile(SkinSensitivity.V), 1.0)
        assertNotNull(typeII)
        assertNotNull(typeV)
        assertTrue(typeV > typeII)
    }

    @Test
    fun alreadyOverThreshold_returnsZero() {
        val f = forecast(List(4) { 3.0 })
        val ttb = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.III), assumedFactor = 1.0, alreadyAccumulated = 100.0,
        )
        assertEquals(Duration.ZERO, ttb)
    }

    @Test
    fun thresholdMultiplier_pushesCrossingLater() {
        // At constant UV 3 for Type III (MED = 3.9 UV-idx·h):
        //   1.0× MED → 3.9 / 3 = 1.30 h = 78 min
        //   2.0× MED → 7.8 / 3 = 2.60 h = 156 min
        val f = forecast(List(8) { 3.0 })
        val oneMed = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.III), assumedFactor = 1.0, thresholdMultiplier = 1.0,
        )
        val twoMed = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.III), assumedFactor = 1.0, thresholdMultiplier = 2.0,
        )
        assertNotNull(oneMed)
        assertNotNull(twoMed)
        assertEquals(78L, oneMed.inWholeMinutes)
        assertEquals(156L, twoMed.inWholeMinutes)
    }

    @Test
    fun acclimatizationExtendsTime_proportionalToEffectiveMed() {
        // Type III untanned: MED 3.9, UV 3 → 78 min (baseline).
        // Type III with Moderate tan (×2.2, well below the III cap of 2.5): MED = 8.58,
        // 8.58 / 3 = 2.86 h = 171.6 min ≈ 172 min after integer minute truncation.
        val f = forecast(List(8) { 3.0 })
        val baseline = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.III, Acclimatization.None), 1.0,
        )
        val tanned = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.III, Acclimatization.Moderate), 1.0,
        )
        assertNotNull(baseline)
        assertNotNull(tanned)
        assertEquals(78L, baseline.inWholeMinutes)
        assertEquals(171L, tanned.inWholeMinutes)
    }

    @Test
    fun acclimatizationCap_clipsTypeIBelowDeepTanFactor() {
        // Type I cap is 1.4. Picking Acclimatization.Deep (factor 3.0) must clip to 1.4.
        // Type I MED 2.2, capped effective MED 2.2 × 1.4 = 3.08. At UV 3: 3.08 / 3 = 1.0267 h
        // = 61.6 min → 61 min.
        val f = forecast(List(8) { 3.0 })
        val deepTan = BurnModel.timeToThreshold(
            origin, f, SkinProfile(SkinSensitivity.I, Acclimatization.Deep), 1.0,
        )
        assertNotNull(deepTan)
        assertEquals(61L, deepTan.inWholeMinutes)
    }

    @Test
    fun risingVsFalling_giveDifferentTimes() {
        val rising = forecast(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0))
        val falling = forecast(listOf(7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0))
        val tR = BurnModel.timeToThreshold(origin, rising, SkinProfile(SkinSensitivity.III), 1.0)
        val tF = BurnModel.timeToThreshold(origin, falling, SkinProfile(SkinSensitivity.III), 1.0)
        assertNotNull(tR)
        assertNotNull(tF)
        assertTrue(tF < tR)
    }
}
