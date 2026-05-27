package com.insola.uv.dashboard

import com.insola.uv.dev.Fixtures
import com.insola.uv.dev.Scenario
import com.insola.uv.domain.Acclimatization
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.dose.VitaminDModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

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
    ): DashboardState {
        val scenario = Fixtures.byId(scenarioId)
        return DashboardCompute.compute(
            scenario = scenario,
            hourOfDay = hour,
            profile = SkinProfile(skin, acclimatization),
            sessions = sessions ?: listOf(fullDaySession(scenario)),
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
