package com.insola.uv.dashboard

import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.Spf
import com.insola.uv.dose.VitaminDModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Drives the same compute the UI sees. Sessions are explicit — tests that need "continuous outdoor"
 * semantics inject a [fullDaySession]; tests that exercise the log itself build sessions directly.
 */
class DashboardComputeTest {

    private fun fullDaySession(scenario: Scenario): OutdoorSession =
        OutdoorSession(start = scenario.dayStart, end = scenario.dayStart + 24.hours)

    private fun stateAt(
        scenarioId: String,
        hour: Double,
        skin: SkinSensitivity,
        acclimatization: Acclimatization = Acclimatization.None,
        sessions: List<OutdoorSession>? = null,
        attenuation: AttenuationTimeline = AttenuationTimeline.Empty,
    ): DashboardState {
        val scenario = Fixtures.byId(scenarioId)
        return DashboardCompute.compute(
            scenario = scenario,
            hourOfDay = hour,
            profile = SkinProfile(skin, acclimatization),
            sessions = sessions ?: listOf(fullDaySession(scenario)),
            attenuation = attenuation,
        )
    }

    @Test
    fun equatorialNoon_hasShortTimeToBurn_andStrongVitD() {
        val s = stateAt("equator", hour = 12.0, skin = SkinSensitivity.III)
        assertTrue(s.currentUv > 8.0, "noon UV should be high, got ${s.currentUv}")
        val ttb = s.timeToFirstReddening
        assertNotNull(ttb)
        assertTrue(ttb.inWholeMinutes < 60, "should burn quickly at peak equatorial UV, got $ttb")
        assertTrue(
            s.vitaminDBucket >= VitaminDModel.Bucket.Adequate,
            "expected adequate+ vit-D, got ${s.vitaminDBucket}",
        )
    }

    @Test
    fun reykjavikWinter_neverBurns_andNegligibleVitD() {
        // Sun only clears the horizon for a few hours, peaking near 2°. The VitD/erythemal
        // weighting collapses that low, so even a full-day session produces at most a
        // Trace amount — never enough to register as meaningful vitamin D.
        val s = stateAt("reykjavik", hour = 23.5, skin = SkinSensitivity.II)
        assertNull(s.timeToFirstReddening, "should never burn in arctic winter")
        assertTrue(
            s.vitaminDBucket <= VitaminDModel.Bucket.Trace,
            "expected at most Trace vit-D in arctic winter, got ${s.vitaminDBucket}",
        )
    }

    @Test
    fun accumulatedDose_climbsMonotonically_acrossDay() {
        val scenario = Fixtures.byId("berlin")
        val session = fullDaySession(scenario)
        val doses = (0..24).map { hour ->
            DashboardCompute.compute(
                scenario = scenario,
                hourOfDay = hour.toDouble(),
                profile = SkinProfile(SkinSensitivity.III),
                sessions = listOf(session),
            ).accumulatedDose
        }
        doses.zipWithNext().forEachIndexed { idx, (a, b) ->
            assertTrue(b >= a - 1e-9, "dose decreased between hour $idx and ${idx + 1}: $a → $b")
        }
        assertTrue(doses.last() > doses.first(), "EOD dose should exceed dawn dose")
    }

    @Test
    fun mirroredCurves_haveSameEndOfDayDose_butDifferentMiddayAccumulation() {
        val risingEod = stateAt("rising", hour = 24.0, skin = SkinSensitivity.III)
        val fallingEod = stateAt("falling", hour = 24.0, skin = SkinSensitivity.III)
        assertEquals(risingEod.accumulatedDose, fallingEod.accumulatedDose, 1e-9)

        val risingMid = stateAt("rising", hour = 12.0, skin = SkinSensitivity.III).accumulatedDose
        val fallingMid = stateAt("falling", hour = 12.0, skin = SkinSensitivity.III).accumulatedDose
        assertTrue(
            fallingMid > risingMid * 2,
            "falling curve should have piled most of its dose by noon (rising=$risingMid, falling=$fallingMid)",
        )
    }

    @Test
    fun changingSensitivity_rescalesBudgetCoherently() {
        val typeI = stateAt("berlin", hour = 7.0, skin = SkinSensitivity.I)
        val typeVI = stateAt("berlin", hour = 7.0, skin = SkinSensitivity.VI)
        assertEquals(typeI.accumulatedDose, typeVI.accumulatedDose, 1e-9)
        assertTrue(
            typeI.budgetPercent > typeVI.budgetPercent,
            "Type I should use a higher % of budget than Type VI for the same dose " +
                "(typeI=${typeI.budgetPercent}%, typeVI=${typeVI.budgetPercent}%)",
        )
        val ttbI = typeI.timeToFirstReddening
        val ttbVI = typeVI.timeToFirstReddening
        assertNotNull(ttbI)
        assertNotNull(ttbVI)
        assertTrue(ttbVI > ttbI, "Type VI should take longer to burn than Type I (I=$ttbI, VI=$ttbVI)")
    }

    @Test
    fun acclimatization_stretchesBurnBudget_andAttenuatesVitDYieldSymmetrically() {
        // Same scenario + same logged exposure, two profiles differing only in tan state.
        // Burn budget% must scale by the inverse of the acclimatization factor (effective MED
        // grows, accumulated stays put). Raw vit-D score is also unchanged — tan attenuation
        // is applied at bucket time via [VitaminDModel.effectiveYield], not by re-integrating.
        val untanned = stateAt("berlin", hour = 14.0, skin = SkinSensitivity.III)
        val tanned = stateAt(
            "berlin", hour = 14.0, skin = SkinSensitivity.III, acclimatization = Acclimatization.Moderate,
        )
        assertEquals(untanned.accumulatedDose, tanned.accumulatedDose, 1e-9)
        assertTrue(
            untanned.budgetPercent > tanned.budgetPercent * 2.0,
            "Moderate tan (×2.2) should drop budget% by roughly the same factor " +
                "(untanned=${untanned.budgetPercent}%, tanned=${tanned.budgetPercent}%)",
        )
        // Raw vit-D score is identical (integrator doesn't see the profile)…
        assertEquals(untanned.vitaminDScore, tanned.vitaminDScore, 1e-9)
        // …but tanned skin yields fewer effective UV-D photons per unit raw score, so a
        // bucket move can never go *upward*. The relationship is monotonic: tanned ≤ untanned.
        assertTrue(
            tanned.vitaminDBucket <= untanned.vitaminDBucket,
            "tan should attenuate (or not move) the vit-D bucket, never improve it " +
                "(untanned=${untanned.vitaminDBucket}, tanned=${tanned.vitaminDBucket})",
        )
    }


    @Test
    fun emptyLog_meansNoAccumulatedDose_andNoVitD() {
        val s = stateAt("equator", hour = 18.0, skin = SkinSensitivity.III, sessions = emptyList())
        assertEquals(0.0, s.accumulatedDose, 1e-9, "no logged outdoor time → no dose")
        assertEquals(VitaminDModel.Bucket.None, s.vitaminDBucket)
        // But time-to-burn still answers "if you went outside now". Equator at hour 18 — sun is
        // already low, but the projection forward into the evening + next morning should still
        // eventually burn at MED for Type III. Don't pin the exact value, just sanity-check it
        // resolves to *something* finite given the day ahead is still bright at the integration
        // window's end (compute integrates from now within the forecast bounds).
        // Acceptable: null or finite — we just don't want a crash. Compute returned cleanly.
    }

    @Test
    fun openSession_accruesDoseUntilNow() {
        val scenario = Fixtures.byId("equator")
        // Session opened at hour 10, never closed. At hour 12, dose should reflect 10..12 only.
        val openAt10 = OutdoorSession(start = scenario.dayStart + 10.hours, end = null)
        val s = DashboardCompute.compute(
            scenario = scenario,
            hourOfDay = 12.0,
            profile = SkinProfile(SkinSensitivity.III),
            sessions = listOf(openAt10),
        )
        assertTrue(s.isCurrentlyOutside, "should be flagged as outside while session is open")
        assertTrue(s.accumulatedDose > 0.0, "open session should contribute dose")

        // Same scenario, same hour, but closed session over the same window.
        val closed = openAt10.copy(end = scenario.dayStart + 12.hours)
        val sClosed = DashboardCompute.compute(
            scenario = scenario,
            hourOfDay = 12.0,
            profile = SkinProfile(SkinSensitivity.III),
            sessions = listOf(closed),
        )
        assertEquals(
            s.accumulatedDose, sClosed.accumulatedDose, 1e-9,
            "open session ending at now should match equivalent closed session",
        )
        assertTrue(!sClosed.isCurrentlyOutside, "closed session should not be flagged as outside")
    }

    @Test
    fun shorterSession_yieldsLessDose() {
        val scenario = Fixtures.byId("equator")
        val short = OutdoorSession(
            start = scenario.dayStart + 11.hours,
            end = scenario.dayStart + 12.hours,
        )
        val long = OutdoorSession(
            start = scenario.dayStart + 9.hours,
            end = scenario.dayStart + 13.hours,
        )
        val shortState = DashboardCompute.compute(scenario, 23.0, SkinProfile(SkinSensitivity.III), listOf(short))
        val longState = DashboardCompute.compute(scenario, 23.0, SkinProfile(SkinSensitivity.III), listOf(long))
        assertTrue(
            longState.accumulatedDose > shortState.accumulatedDose,
            "4h session should accumulate more than 1h session " +
                "(short=${shortState.accumulatedDose}, long=${longState.accumulatedDose})",
        )
    }

    @Test
    fun freshSunscreenApplication_setsForwardTransmittance() {
        // No baseline; user applies SPF 50 right now. The forward burn integrator must pick up
        // the application's transmittance off bare skin. A short session at hour 10 leaves room
        // before MED on a Berlin-strength curve so the *forward* projection is non-trivial.
        // `applicationThickness = 1.0` isolates the override behavior from the realistic
        // thickness derate (covered separately).
        val scenario = Fixtures.byId("berlin")
        val session = OutdoorSession(
            start = scenario.dayStart + 10.hours,
            end = scenario.dayStart + 10.hours + 15.minutes,
        )
        val now = scenario.hourToInstant(11.0)
        val bare = DashboardCompute.compute(scenario, 11.0, SkinProfile(SkinSensitivity.III), listOf(session))
        val boosted = DashboardCompute.compute(
            scenario, 11.0, SkinProfile(SkinSensitivity.III), listOf(session),
            attenuation = AttenuationTimeline(listOf(
                AttenuationTimeline.Patch(now, Spf.Spf50.transmittance, applicationThickness = 1.0),
            )),
        )
        // Past dose unchanged — the application doesn't retroactively undo accumulation.
        assertEquals(bare.accumulatedDose, boosted.accumulatedDose, 1e-9)
        assertEquals(Spf.Spf50.transmittance, boosted.effectiveTransmittance, 1e-12)
        // SPF 50 forward should burn much later (or not within the forecast at all).
        val bareBurn = bare.timeToFirstReddening
        assertNotNull(bareBurn, "test setup expects bare-skin to burn within the forecast")
        val boostedBurn = boosted.timeToFirstReddening
        if (boostedBurn != null) {
            assertTrue(
                boostedBurn > bareBurn * 10,
                "fresh SPF 50 should radically extend forward burn time (bare=$bareBurn, boosted=$boostedBurn)",
            )
        }
    }

    @Test
    fun freshApplication_reducesBurnMeter_butDoesNotRetroactivelyUndoPastDose() {
        // Berlin scenario, two-hour session 10:00–12:00, "now" is 12:00. Applying SPF 50 at the
        // halfway point (11:00) must:
        //   1. leave the first hour's dose untouched (no retroactive undo)
        //   2. attenuate the second hour by ~1/50 → accumulated burn meter drops noticeably
        // `applicationThickness = 1.0` keeps the labeled SPF effective so the ~½ ratio bounds
        // below are tight; realistic thickness derates are covered in a dedicated test.
        val scenario = Fixtures.byId("berlin")
        val session = OutdoorSession(
            start = scenario.dayStart + 10.hours,
            end = scenario.dayStart + 12.hours,
        )
        val appliedHalfway = AttenuationTimeline.Patch(
            appliedAt = scenario.dayStart + 11.hours,
            labelTransmittance = Spf.Spf50.transmittance,
            applicationThickness = 1.0,
        )

        val bare = DashboardCompute.compute(
            scenario, 12.0, SkinProfile(SkinSensitivity.III), listOf(session),
        )
        val boosted = DashboardCompute.compute(
            scenario, 12.0, SkinProfile(SkinSensitivity.III), listOf(session),
            attenuation = AttenuationTimeline(listOf(appliedHalfway)),
        )
        // First hour was unprotected in both runs → boosted dose can't drop below ~½ bare.
        // Second hour transmits 1/50 → boosted dose can't be much more than ½ bare.
        assertTrue(
            boosted.accumulatedDose < bare.accumulatedDose,
            "applying SPF mid-session must reduce accumulated dose (bare=${bare.accumulatedDose}, boosted=${boosted.accumulatedDose})",
        )
        assertTrue(
            boosted.accumulatedDose > bare.accumulatedDose * 0.4,
            "first hour pre-apply must remain unattenuated (bare=${bare.accumulatedDose}, boosted=${boosted.accumulatedDose})",
        )
        assertTrue(
            boosted.accumulatedDose < bare.accumulatedDose * 0.6,
            "second hour post-apply should be attenuated ~50× (bare=${bare.accumulatedDose}, boosted=${boosted.accumulatedDose})",
        )
        assertTrue(
            boosted.budgetPercent < bare.budgetPercent,
            "burn-meter % must mirror the dose drop",
        )
    }

    @Test
    fun reapply_doesNotStripCoverageFromPreviouslyCoveredMoments() {
        // The "fill-the-burn-budget" regression: previously the latest application replaced any
        // earlier one in storage, so re-applying at 15:30 retroactively un-protected the
        // 14:00–15:30 window that the 14:00 application had covered. The integrator now takes
        // the strongest patch active at each instant across *all* applications, so coverage
        // can only extend. Re-apply lands exactly at session end, so the two runs must
        // integrate identically over the session window (reApply contributes nothing inside).
        val scenario = Fixtures.byId("berlin")
        val session = OutdoorSession(
            start = scenario.dayStart + 14.hours,
            end = scenario.dayStart + 15.hours + 30.minutes,
        )
        val firstApply = AttenuationTimeline.Patch(
            appliedAt = scenario.dayStart + 14.hours,
            labelTransmittance = Spf.Spf50.transmittance,
        )
        val reApply = AttenuationTimeline.Patch(
            appliedAt = scenario.dayStart + 15.hours + 30.minutes,
            labelTransmittance = Spf.Spf50.transmittance,
        )

        val onlyFirst = DashboardCompute.compute(
            scenario, 15.5, SkinProfile(SkinSensitivity.III), listOf(session),
            attenuation = AttenuationTimeline(listOf(firstApply)),
        )
        val afterReapply = DashboardCompute.compute(
            scenario, 15.5, SkinProfile(SkinSensitivity.III), listOf(session),
            attenuation = AttenuationTimeline(listOf(firstApply, reApply)),
        )
        assertEquals(onlyFirst.accumulatedDose, afterReapply.accumulatedDose, 1e-9)
        assertEquals(onlyFirst.budgetPercent, afterReapply.budgetPercent, 1e-9)
    }

    @Test
    fun decayedApplication_fadesTowardBareSkin() {
        // Application 3 h old. With default thickness 0.5 and half-life 2 h the patch has decayed
        // well below its fresh strength. There's no always-on baseline, so the effective
        // transmittance is purely the (decayed) patch value — weaker than fresh, but still some
        // residual protection short of bare skin.
        val scenario = Fixtures.byId("equator")
        val now = scenario.hourToInstant(13.0)
        val patch = AttenuationTimeline.Patch(now - 3.hours, Spf.Spf50.transmittance)
        val s = DashboardCompute.compute(
            scenario, 13.0,
            SkinProfile(SkinSensitivity.III),
            sessions = emptyList(),
            attenuation = AttenuationTimeline(listOf(patch)),
        )
        assertEquals(patch.transmittanceAt(now), s.effectiveTransmittance, 1e-12)
        assertTrue(
            s.effectiveTransmittance > patch.initialTransmittance,
            "3 h-old patch must have decayed below fresh strength",
        )
        assertTrue(s.effectiveTransmittance < 1.0, "some residual protection should remain")
    }

    @Test
    fun realisticApplicationThickness_deratesLabeledSpf() {
        // Default thickness 0.5 (Petersen & Wulf 2014 field median) with the linear thickness
        // law (Diffey 1997) `S_eff = 1 + (S_label − 1) · thickness`: labeled SPF 50 should
        // deliver effective SPF 25.5 at the moment of application, *not* the labeled 1/50
        // transmittance the bottle promises.
        val scenario = Fixtures.byId("equator")
        val now = scenario.hourToInstant(13.0)
        val s = DashboardCompute.compute(
            scenario, 13.0,
            SkinProfile(SkinSensitivity.III),
            sessions = emptyList(),
            attenuation = AttenuationTimeline(listOf(
                AttenuationTimeline.Patch(now, Spf.Spf50.transmittance),
            )),
        )
        val expectedInitialSpf = 1.0 + (50.0 - 1.0) * 0.5
        assertEquals(1.0 / expectedInitialSpf, s.effectiveTransmittance, 1e-9)
    }

    @Test
    fun exponentialDecay_transmittanceGrowsMonotonicallyWithElapsedTime() {
        // The whole point of swapping the step model for exponential decay: protection
        // gradually fades rather than cliffing off at 2 h. Sampled across the modelling
        // horizon, transmittance must be strictly increasing (toward 1.0 = bare).
        val scenario = Fixtures.byId("equator")
        val applied = scenario.hourToInstant(10.0)
        val patch = AttenuationTimeline.Patch(applied, Spf.Spf50.transmittance, applicationThickness = 1.0)
        val samples = listOf(0.0, 0.5, 1.0, 2.0, 4.0, 8.0).map { dt ->
            patch.transmittanceAt(applied + dt.hours)
        }
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(b > a, "transmittance must grow with elapsed time: $a → $b")
        }
        assertTrue(samples.first() < 0.03, "fresh SPF 50 (thickness=1) transmits ≈ 1/50")
        // At 2 half-lives the excess factor (S − 1) has dropped to ¼ — for SPF 50 that's an
        // effective ≈ SPF 13. The "reapply soon" advice kicks in well before the curve flattens.
        assertEquals(2.0, patch.effectiveHalfLifeHours, 1e-12)
        assertTrue(samples.last() > samples.first() * 10, "after 8 h transmittance ≫ initial")
    }

    @Test
    fun wearMultiplier_acceleratesDecay() {
        // Water immersion / heavy sweat roughly halves the half-life (Stokes & Diffey 1999).
        // At equal elapsed time, the wet patch must transmit more UV than the dry one.
        val scenario = Fixtures.byId("equator")
        val applied = scenario.hourToInstant(10.0)
        val dry = AttenuationTimeline.Patch(
            applied, Spf.Spf50.transmittance, applicationThickness = 1.0, wearMultiplier = 1.0,
        )
        val wet = AttenuationTimeline.Patch(
            applied, Spf.Spf50.transmittance, applicationThickness = 1.0, wearMultiplier = 0.5,
        )
        val t = applied + 1.hours
        assertTrue(
            wet.transmittanceAt(t) > dry.transmittanceAt(t),
            "wet patch should have decayed further by t=1h (dry=${dry.transmittanceAt(t)}, wet=${wet.transmittanceAt(t)})",
        )
    }

    @Test
    fun futureSession_doesNotContribute() {
        val scenario = Fixtures.byId("equator")
        // Session starts at hour 14 but now is hour 10.
        val future = OutdoorSession(
            start = scenario.dayStart + 14.hours,
            end = scenario.dayStart + 16.hours,
        )
        val s = DashboardCompute.compute(scenario, 10.0, SkinProfile(SkinSensitivity.III), listOf(future))
        assertEquals(0.0, s.accumulatedDose, 1e-9, "a session in the future should not have contributed yet")
        assertTrue(!s.isCurrentlyOutside)
    }
}
