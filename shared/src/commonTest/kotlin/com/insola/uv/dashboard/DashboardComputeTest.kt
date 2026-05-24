package com.insola.uv.dashboard

import com.insola.uv.dev.Fixtures
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.dose.VitaminDModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the same compute the UI sees — verifies the Checkpoint 1.5 claims from
 * `plans/sunlight_mvp_implementation_plan.md` without needing to drive the slider by hand.
 */
class DashboardComputeTest {

    private fun stateAt(scenarioId: String, hour: Double, skin: SkinSensitivity) =
        DashboardCompute.compute(Fixtures.byId(scenarioId), hour, skin)

    @Test
    fun equatorialNoon_hasShortTimeToBurn_andStrongVitD() {
        val s = stateAt("equator", hour = 12.0, skin = SkinSensitivity.III)
        assertTrue(s.currentUv > 8.0, "noon UV should be high, got ${s.currentUv}")
        val ttb = s.timeToBurn
        assertNotNull(ttb)
        assertTrue(ttb.inWholeMinutes < 60, "should burn quickly at peak equatorial UV, got $ttb")
        assertTrue(
            s.vitaminDBucket >= VitaminDModel.Bucket.Adequate,
            "expected adequate+ vit-D, got ${s.vitaminDBucket}",
        )
    }

    @Test
    fun reykjavikWinter_neverBurns_andNoVitD() {
        val s = stateAt("reykjavik", hour = 23.5, skin = SkinSensitivity.II)
        assertNull(s.timeToBurn, "should never burn in arctic winter")
        assertEquals(VitaminDModel.Bucket.None, s.vitaminDBucket)
    }

    @Test
    fun accumulatedDose_climbsMonotonically_acrossDay() {
        val doses = (0..24).map { hour ->
            DashboardCompute.compute(
                Fixtures.byId("berlin"),
                hour.toDouble(),
                SkinSensitivity.III,
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

        // At hour 12 the falling curve has already delivered most of its dose; the rising curve
        // has barely started. That divergence is the proof that time-integration is doing real
        // work — the time-to-burn check is the *consequence*, but the dose split is the cleanest
        // test because both EOD totals are large enough to saturate every Fitzpatrick threshold.
        val risingMid = stateAt("rising", hour = 12.0, skin = SkinSensitivity.III).accumulatedDose
        val fallingMid = stateAt("falling", hour = 12.0, skin = SkinSensitivity.III).accumulatedDose
        assertTrue(
            fallingMid > risingMid * 2,
            "falling curve should have piled most of its dose by noon (rising=$risingMid, falling=$fallingMid)",
        )
    }

    @Test
    fun changingSensitivity_rescalesBudgetCoherently() {
        // Hour 7 in Berlin: accumulated ≈ 1.8 UV-idx·h, below Type I's 2.2 and Type VI's 11.1
        // so both still have a non-null time-to-burn we can compare.
        val typeI = stateAt("berlin", hour = 7.0, skin = SkinSensitivity.I)
        val typeVI = stateAt("berlin", hour = 7.0, skin = SkinSensitivity.VI)
        assertEquals(typeI.accumulatedDose, typeVI.accumulatedDose, 1e-9)
        assertTrue(
            typeI.budgetPercent > typeVI.budgetPercent,
            "Type I should use a higher % of budget than Type VI for the same dose " +
                "(typeI=${typeI.budgetPercent}%, typeVI=${typeVI.budgetPercent}%)",
        )
        val ttbI = typeI.timeToBurn
        val ttbVI = typeVI.timeToBurn
        assertNotNull(ttbI)
        assertNotNull(ttbVI)
        assertTrue(ttbVI > ttbI, "Type VI should take longer to burn than Type I (I=$ttbI, VI=$ttbVI)")
    }
}
