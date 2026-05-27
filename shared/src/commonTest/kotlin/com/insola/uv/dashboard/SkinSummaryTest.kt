package com.insola.uv.dashboard

import com.insola.uv.dev.Fixtures
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SkinSummary projects "minutes outside at the day's peak UV" for the Skin-tab chart. It's a
 * separate (pure) computation from the burn/dose integrators so the chart can scale its X
 * axis without simulating the rest of the day.
 */
class SkinSummaryTest {

    @Test
    fun equatorialPeak_firstReddeningBeforeSunburn_andAdequateBeforeFirst() {
        // Singapore peak UV ≈ 12 with overhead sun: vit-D should saturate well before any burn
        // boundary, matching the BurnVitaminDCalibrationTest invariant projected onto SkinSummary.
        val summary = SkinSummary.compute(Fixtures.byId("equator"), SkinProfile(SkinSensitivity.III))
        assertTrue(summary.peakUv > 8.0)
        val tFirst = summary.minutesToFirstReddening
        val tSunburn = summary.minutesToSunburn
        val tAdequate = summary.minutesToAdequateVitD
        assertNotNull(tFirst)
        assertNotNull(tSunburn)
        assertNotNull(tAdequate)
        assertTrue(tSunburn > tFirst, "2 MED must come after 1 MED")
        assertEquals(2.0 * tFirst, tSunburn, 1e-6)
        assertTrue(tAdequate < tFirst, "vit-D should hit 1 SDD before first reddening at high sun")
    }

    @Test
    fun arcticWinter_hasHugeMinutesToBoundaries_clampedByAxis() {
        // Sun barely clears the horizon — peak elevation ≈ 2.4°. Rates are tiny but non-zero,
        // so the projection still produces huge minutes-to-X values; the chart axis must clamp.
        val summary = SkinSummary.compute(Fixtures.byId("reykjavik"), SkinProfile(SkinSensitivity.II))
        assertTrue(summary.peakUv < 1.5)
        // Times-to-threshold are finite but very large (hours).
        val tAdequate = summary.minutesToAdequateVitD
        val tFirst = summary.minutesToFirstReddening
        assertNotNull(tAdequate)
        assertNotNull(tFirst)
        assertTrue(tAdequate > 60.0, "expected very slow vit-D in arctic winter, got $tAdequate min")
        // Chart's X-axis is capped at MAX_AXIS_MINUTES so the bar stays usable.
        assertTrue(summary.maxRelevantMinutes <= SkinSummary.MAX_AXIS_MINUTES + 1e-9)
    }

    @Test
    fun acclimatization_extendsBothBurnAndVitDProportionally() {
        // The two effects compose symmetrically — tan extends burn budget AND extends time to
        // Adequate D, scaled by the same effectiveAcclimatizationFactor.
        val baseline = SkinSummary.compute(
            Fixtures.byId("berlin"), SkinProfile(SkinSensitivity.III, Acclimatization.None),
        )
        val tanned = SkinSummary.compute(
            Fixtures.byId("berlin"), SkinProfile(SkinSensitivity.III, Acclimatization.Moderate),
        )
        val factor = 2.2 // Acclimatization.Moderate, below the III cap of 2.5

        val baseFirst = baseline.minutesToFirstReddening
        val tanFirst = tanned.minutesToFirstReddening
        assertNotNull(baseFirst); assertNotNull(tanFirst)
        assertEquals(baseFirst * factor, tanFirst, 1e-3)

        val baseAdequate = baseline.minutesToAdequateVitD
        val tanAdequate = tanned.minutesToAdequateVitD
        assertNotNull(baseAdequate); assertNotNull(tanAdequate)
        assertEquals(baseAdequate * factor, tanAdequate, 1e-3)
    }

    @Test
    fun phototypeCap_clipsExtremeAcclimatization() {
        // Type I cap is ×1.4. Deep tan (×3.0) must clip to 1.4× on both rows.
        val baseline = SkinSummary.compute(
            Fixtures.byId("berlin"), SkinProfile(SkinSensitivity.I, Acclimatization.None),
        )
        val capped = SkinSummary.compute(
            Fixtures.byId("berlin"), SkinProfile(SkinSensitivity.I, Acclimatization.Deep),
        )
        val baseFirst = baseline.minutesToFirstReddening
        val capFirst = capped.minutesToFirstReddening
        assertNotNull(baseFirst); assertNotNull(capFirst)
        assertEquals(baseFirst * 1.4, capFirst, 1e-3)
    }

    @Test
    fun maxRelevantMinutes_isCapped() {
        // Reykjavík forces extreme burn times (low UV → huge minutes). Axis must clamp so the
        // chart's X axis stays usable.
        val summary = SkinSummary.compute(Fixtures.byId("reykjavik"), SkinProfile(SkinSensitivity.III))
        assertTrue(summary.maxRelevantMinutes <= SkinSummary.MAX_AXIS_MINUTES + 1e-9)
    }

    @Test
    fun tickOrdering_burnBeforeSunburn_quarterBeforeHalfBeforeFull() {
        val summary = SkinSummary.compute(Fixtures.byId("equator"), SkinProfile(SkinSensitivity.III))
        val tFirst = summary.minutesToFirstReddening!!
        val tSunburn = summary.minutesToSunburn!!
        val tQ = summary.minutesToTraceVitD!!
        val tH = summary.minutesToLowVitD!!
        val tF = summary.minutesToAdequateVitD!!
        assertTrue(tFirst < tSunburn)
        assertTrue(tQ < tH)
        assertTrue(tH < tF)
        assertEquals(2.0 * tQ, tH, 1e-6)
        assertEquals(2.0 * tH, tF, 1e-6)
    }
}
