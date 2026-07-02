package com.insola.uv.dashboard

import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.UvDay
import com.insola.uv.domain.Spf
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Hypothetical "minutes outside at a given UV level" readout for the Skin tab.
 *
 * Tells the user, for their current [SkinProfile] and a chosen UV index, how long continuous
 * unprotected exposure would take to hit each meaningful boundary. [compute] anchors the level
 * at the day's strongest UV; the Skin-tab slider then explores other levels via [atUvLevel].
 * The same numbers position the ticks on [SkinSummaryChart].
 *
 * [peakSolarElevationDeg] (the day's peak sun angle, which drives the vitamin-D weighting) and
 * the skin constants are held fixed across [atUvLevel] — only the UV index varies — so the
 * slider reads as "the same sky, brighter or dimmer".
 *
 * Times are returned as [Double] minutes (not [kotlin.time.Duration]) because the chart
 * needs them as floats for axis math, and downstream UI is happy to format the value.
 * A `null` tick means that boundary is unreachable at this UV level (e.g., polar winter).
 */
data class SkinSummary(
    /** UV index this summary is evaluated at (the day's peak by default; the slider overrides). */
    val peakUv: Double,
    val peakSolarElevationDeg: Double,
    val effectiveMedUvIndexHours: Double,
    val sddUvIndexHours: Double,
    /** Capped tan multiplier — kept so [atUvLevel] can re-derive the vit-D rate. */
    val effectiveAcclimatizationFactor: Double,
    /** Minutes to 1 MED (first reddening) at this UV level. */
    val minutesToFirstReddening: Double?,
    /** Minutes to 2 MED (felt sunburn) at peak UV. */
    val minutesToSunburn: Double?,
    /** Minutes to ¼ SDD (Trace → Low). */
    val minutesToTraceVitD: Double?,
    /** Minutes to ½ SDD (Low → Adequate). */
    val minutesToLowVitD: Double?,
    /** Minutes to 1 SDD (Adequate → Sufficient). */
    val minutesToAdequateVitD: Double?,
) {
    /**
     * The largest tick worth plotting, in minutes, clamped so very-low-UV days don't blow the
     * scale out to infinity. The sundial currently uses its own fixed dial spans; this remains
     * the model-side invariant the tests pin.
     */
    val maxRelevantMinutes: Double
        get() = listOfNotNull(minutesToSunburn, minutesToAdequateVitD)
            .maxOrNull()
            ?.coerceAtMost(MAX_AXIS_MINUTES)
            ?: MAX_AXIS_MINUTES

    /**
     * Re-evaluate every boundary at a different [uvIndex], holding the sun angle and skin
     * constants fixed. Powers the Skin-tab UV slider. Returns `this` unchanged when [uvIndex]
     * already matches [peakUv].
     */
    fun atUvLevel(uvIndex: Double): SkinSummary {
        val uv = uvIndex.coerceAtLeast(0.0)
        if (uv == peakUv) return this
        return build(uv, peakSolarElevationDeg, effectiveMedUvIndexHours, sddUvIndexHours, effectiveAcclimatizationFactor)
    }

    /**
     * Decay-aware minutes to accumulate [meds] MED at this UV level, assuming the user freshly
     * applies [spf] now and stays out continuously. Integrates a single decaying
     * [AttenuationTimeline.Patch] (the production decay curve — thickness derate + exponential
     * fade toward bare skin) against the constant peak-UV dose rate. The answer therefore
     * reflects protection fading with wear, so the gain is well *under* a flat ×SPF.
     *
     * [Spf.Off] returns the bare-skin linear value (matches [minutesToFirstReddening] at `meds=1`).
     * Returns null if peak UV is zero (the threshold is never reached) or if it is somehow not
     * reached within [DECAY_PROJECTION_CAP_MINUTES] (it always is for positive UV, since the
     * patch decays back to bare skin).
     */
    fun protectedMinutesToBurn(spf: Spf, meds: Double): Double? {
        if (peakUv <= 0.0) return null
        val target = effectiveMedUvIndexHours * meds
        if (spf == Spf.Off) return target / peakUv * 60.0
        return decayAwareBurnMinutes(peakUv, target, spf)
    }

    /**
     * Numerically integrate the constant-UV dose `peakUv · T(t)` of a freshly-applied [spf]
     * patch until it reaches [target], returning the crossing time in minutes (interpolated
     * within the final step). An imperative accumulate-until-crossing loop: it needs both early
     * termination and the running dose to interpolate, which neither `takeWhile` nor `fold`
     * express cleanly.
     */
    private fun decayAwareBurnMinutes(peakUv: Double, target: Double, spf: Spf): Double? {
        val patch = AttenuationTimeline.Patch(
            appliedAt = DECAY_EPOCH,
            labelTransmittance = spf.transmittance,
        )
        val stepMin = 0.5
        val stepHours = stepMin / 60.0
        var dose = 0.0
        var minutes = 0.0
        while (minutes < DECAY_PROJECTION_CAP_MINUTES) {
            val mid = patch.appliedAt + (minutes + stepMin / 2.0).minutes
            val increment = peakUv * patch.transmittanceAt(mid) * stepHours
            if (dose + increment >= target) {
                val frac = if (increment > 0.0) (target - dose) / increment else 0.0
                return minutes + frac * stepMin
            }
            dose += increment
            minutes += stepMin
        }
        return null
    }

    companion object {
        /** Hard upper bound on the chart X axis: 6 hours of continuous peak-UV exposure. */
        const val MAX_AXIS_MINUTES: Double = 360.0

        /** Body fraction matches what [DashboardCompute] passes to [VitaminDModel.accumulate]. */
        private const val EXPOSED_BODY_FRACTION: Double = 0.25

        /** Wall-clock dummy origin for the hypothetical decaying patch — only elapsed time matters. */
        private val DECAY_EPOCH: Instant = Instant.fromEpochSeconds(0)

        /** Safety bound on the decay integrator (24 h). Positive UV always crosses well before this. */
        private const val DECAY_PROJECTION_CAP_MINUTES: Double = 24.0 * 60.0

        fun compute(
            day: UvDay,
            profile: SkinProfile,
        ): SkinSummary {
            val peakSample = day.forecast.samples.maxByOrNull { it.uvIndex }
            val peakUv = peakSample?.uvIndex ?: 0.0
            val peakElevation = peakSample
                ?.let { SolarGeometry.solarElevationDegrees(day.location, it.time) }
                ?: 0.0

            return build(
                uvIndex = peakUv,
                elevationDeg = peakElevation,
                effectiveMed = profile.effectiveMedUvIndexHours,
                sddBaseline = profile.phototype.medThresholdUvIndexHours / 16.0,
                acclimatizationFactor = profile.effectiveAcclimatizationFactor,
            )
        }

        /**
         * Assemble a summary from already-resolved skin constants at a given [uvIndex] and sun
         * angle. Bare-skin rates — the SPF what-if is layered on top in the UI via
         * [protectedMinutesToBurn]. Burn and vit-D ride the same erythemal weighting, keeping
         * the time-to-X formulas linear in UV (which is why only the rates, not the elevation,
         * change as the slider moves).
         */
        private fun build(
            uvIndex: Double,
            elevationDeg: Double,
            effectiveMed: Double,
            sddBaseline: Double,
            acclimatizationFactor: Double,
        ): SkinSummary {
            val burnRatePerHour = uvIndex
            val vitDRatio = VitaminDModel.vitDRatio(elevationDeg)
            val effectiveVitDRatePerHour = uvIndex * vitDRatio * EXPOSED_BODY_FRACTION / acclimatizationFactor

            fun minutesToBurn(meds: Double): Double? =
                if (burnRatePerHour > 0.0) (meds * effectiveMed) / burnRatePerHour * 60.0 else null
            fun minutesToVitD(sdds: Double): Double? =
                if (effectiveVitDRatePerHour > 0.0)
                    (sdds * sddBaseline) / effectiveVitDRatePerHour * 60.0
                else null

            return SkinSummary(
                peakUv = uvIndex,
                peakSolarElevationDeg = elevationDeg,
                effectiveMedUvIndexHours = effectiveMed,
                sddUvIndexHours = sddBaseline,
                effectiveAcclimatizationFactor = acclimatizationFactor,
                minutesToFirstReddening = minutesToBurn(1.0),
                minutesToSunburn = minutesToBurn(2.0),
                minutesToTraceVitD = minutesToVitD(0.25),
                minutesToLowVitD = minutesToVitD(0.5),
                minutesToAdequateVitD = minutesToVitD(1.0),
            )
        }
    }
}
