package com.insola.uv.dose

import com.insola.uv.dev.Fixtures
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.UvDay
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Cross-model calibration check.
 *
 * The two integrators share the same UV-index input but apply different action-spectrum weights
 * (CIE erythema for [BurnModel], CIE previtamin-D3 for [VitaminDModel]). The well-established
 * physiological claim is that vit-D synthesis saturates *before* erythema sets in — Holick's rule
 * pins one Standard Vitamin D Dose at roughly ¹⁄₁₆ MED in the units we accumulate. If a future
 * change to either integrator (or to the [Fixtures] UV curves) breaks that ordering, this test
 * catches it.
 *
 * Excludes Reykjavík: at sun elevations <3° the vit-D/erythemal ratio physically collapses, so
 * the "vit-D saturates first" rule of thumb doesn't hold there. That's correct behavior, not a
 * regression.
 */
class BurnVitaminDCalibrationTest {

    @Test
    fun vitaminDReachesAdequateByTheTimeBurnReachesOneMED() {
        val profile = SkinProfile(SkinSensitivity.III)
        listOf("equator", "berlin", "cloudy").forEach { id ->
            val day = Fixtures.byId(id).day
            val noon = solarNoonInstant(day)
            val ttb = BurnModel.timeToThreshold(
                now = noon,
                forecast = day.forecast,
                profile = profile,
                assumedFactor = 1.0,
            )
            assertNotNull(ttb, "$id: should reach 1 MED with continuous exposure from solar noon")

            val score = VitaminDModel.accumulate(
                forecast = day.forecast,
                from = noon,
                to = noon + ttb,
                skinExposedFraction = 0.25,
            )
            val bucket = VitaminDModel.bucket(score, profile)
            assertTrue(
                bucket >= VitaminDModel.Bucket.Adequate,
                "$id: vit-D should reach at least Adequate by time burn hits 1 MED, " +
                    "got $bucket (score=$score, ttb=$ttb)",
            )
        }
    }

    private fun solarNoonInstant(day: UvDay): Instant {
        val noonMinute = (0..1440).maxByOrNull { mins ->
            SolarGeometry.solarElevationDegrees(day.location, day.dayStart + mins.minutes)
        }!!
        return day.dayStart + noonMinute.minutes
    }
}
