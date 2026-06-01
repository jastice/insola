package com.insola.uv.dashboard

import com.insola.uv.dev.Fixtures
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.Spf
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
        val summary = SkinSummary.compute(Fixtures.byId("equator").day, SkinProfile(SkinSensitivity.III))
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
        val summary = SkinSummary.compute(Fixtures.byId("reykjavik").day, SkinProfile(SkinSensitivity.II))
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
            Fixtures.byId("berlin").day, SkinProfile(SkinSensitivity.III, Acclimatization.None),
        )
        val tanned = SkinSummary.compute(
            Fixtures.byId("berlin").day, SkinProfile(SkinSensitivity.III, Acclimatization.Moderate),
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
            Fixtures.byId("berlin").day, SkinProfile(SkinSensitivity.I, Acclimatization.None),
        )
        val capped = SkinSummary.compute(
            Fixtures.byId("berlin").day, SkinProfile(SkinSensitivity.I, Acclimatization.Deep),
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
        val summary = SkinSummary.compute(Fixtures.byId("reykjavik").day, SkinProfile(SkinSensitivity.III))
        assertTrue(summary.maxRelevantMinutes <= SkinSummary.MAX_AXIS_MINUTES + 1e-9)
    }

    @Test
    fun previewSpf_extendsBurnTime_butDecayKeepsItUnderFlatFactor() {
        // The SPF what-if integrates a single *decaying* patch against constant peak UV, so a
        // chosen SPF must delay reddening — monotonically by strength — yet buy far less than a
        // flat ×SPF would, because protection fades back toward bare skin as you wear it.
        val summary = SkinSummary.compute(Fixtures.byId("equator").day, SkinProfile(SkinSensitivity.III))
        val bare = summary.minutesToFirstReddening
        assertNotNull(bare)

        // `Off` reproduces the bare-skin number exactly.
        val off = summary.protectedMinutesToBurn(Spf.Off, 1.0)
        assertNotNull(off)
        assertEquals(bare, off, 1e-6)

        val spf15 = summary.protectedMinutesToBurn(Spf.Spf15, 1.0)
        val spf30 = summary.protectedMinutesToBurn(Spf.Spf30, 1.0)
        val spf50 = summary.protectedMinutesToBurn(Spf.Spf50, 1.0)
        assertNotNull(spf15); assertNotNull(spf30); assertNotNull(spf50)

        // Each step up delays reddening further...
        assertTrue(bare < spf15, "any SPF should push reddening later than bare (bare=$bare, spf15=$spf15)")
        assertTrue(spf15 < spf30, "stronger SPF should delay further (spf15=$spf15, spf30=$spf30)")
        assertTrue(spf30 < spf50, "stronger SPF should delay further (spf30=$spf30, spf50=$spf50)")

        // ...but the decaying patch buys well under a flat ×factor of extra time.
        assertTrue(
            spf30 < bare * 30.0,
            "decay must keep the gain well under ×30 (ratio=${spf30 / bare})",
        )
    }

    @Test
    fun atUvLevel_rescalesBoundariesInverselyWithUv_andMatchesComputeAtThatUv() {
        // The slider recomputes the card at an arbitrary UV, holding the day's sun angle fixed.
        // Bare times scale inversely with UV, and the result equals computing fresh at that UV.
        val summary = SkinSummary.compute(Fixtures.byId("equator").day, SkinProfile(SkinSensitivity.III))
        val peak = summary.peakUv
        assertTrue(peak > 8.0)

        // Identity at the peak.
        assertEquals(summary, summary.atUvLevel(peak))

        // Halving UV doubles every bare boundary (rates are linear in UV, threshold fixed).
        val half = summary.atUvLevel(peak / 2.0)
        assertEquals(peak / 2.0, half.peakUv, 1e-9)
        assertEquals(summary.minutesToFirstReddening!! * 2.0, half.minutesToFirstReddening!!, 1e-6)
        assertEquals(summary.minutesToAdequateVitD!! * 2.0, half.minutesToAdequateVitD!!, 1e-6)
        // Sun angle (and thus the vit-D weighting) is unchanged by the slider.
        assertEquals(summary.peakSolarElevationDeg, half.peakSolarElevationDeg, 1e-12)

        // A stronger UV makes the decay-aware SPF preview burn sooner than at the weaker level.
        val bright = summary.atUvLevel(peak)
        val dim = summary.atUvLevel(peak / 3.0)
        assertTrue(bright.protectedMinutesToBurn(Spf.Spf30, 1.0)!! < dim.protectedMinutesToBurn(Spf.Spf30, 1.0)!!)

        // UV 0 → nothing is ever reached.
        assertEquals(null, summary.atUvLevel(0.0).minutesToFirstReddening)
    }

    @Test
    fun tickOrdering_burnBeforeSunburn_quarterBeforeHalfBeforeFull() {
        val summary = SkinSummary.compute(Fixtures.byId("equator").day, SkinProfile(SkinSensitivity.III))
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
