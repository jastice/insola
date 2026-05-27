package com.insola.uv.dashboard

import com.insola.uv.dev.Scenario
import com.insola.uv.domain.SkinProfile
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.solar.SolarGeometry

/**
 * Hypothetical "minutes outside at the scenario's peak UV" readout for the Skin tab.
 *
 * Tells the user, for their current [SkinProfile] and the day's strongest UV, how long
 * continuous unprotected exposure would take to hit each meaningful boundary. The same
 * numbers are used to position ticks on [SkinSummaryChart].
 *
 * Times are returned as [Double] minutes (not [kotlin.time.Duration]) because the chart
 * needs them as floats for axis math, and downstream UI is happy to format the value.
 * A `null` ticks means that boundary is unreachable at this peak UV (e.g., polar winter).
 */
data class SkinSummary(
    val peakUv: Double,
    val peakSolarElevationDeg: Double,
    val effectiveMedUvIndexHours: Double,
    val sddUvIndexHours: Double,
    /**
     * UV transmittance folded into the per-hour rates below — `1.0` is bare skin, smaller
     * values mean stronger protection (e.g. `1.0 / 30.0` for an effective SPF 30).
     */
    val effectiveTransmittance: Double,
    /** Minutes to 1 MED (first reddening) at peak UV. */
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
     * The largest tick we'd plot, in minutes. Used by the chart to scale its X axis. Clamped
     * so very-low-UV days don't blow the axis out to infinity.
     */
    val maxRelevantMinutes: Double
        get() = listOfNotNull(minutesToSunburn, minutesToAdequateVitD)
            .maxOrNull()
            ?.coerceAtMost(MAX_AXIS_MINUTES)
            ?: MAX_AXIS_MINUTES

    companion object {
        /** Hard upper bound on the chart X axis: 6 hours of continuous peak-UV exposure. */
        const val MAX_AXIS_MINUTES: Double = 360.0

        /** Body fraction matches what [DashboardCompute] passes to [VitaminDModel.accumulate]. */
        private const val EXPOSED_BODY_FRACTION: Double = 0.25

        fun compute(
            scenario: Scenario,
            profile: SkinProfile,
            effectiveTransmittance: Double = 1.0,
        ): SkinSummary {
            val peakSample = scenario.forecast.samples.maxByOrNull { it.uvIndex }
            val peakUv = peakSample?.uvIndex ?: 0.0
            val peakElevation = peakSample
                ?.let { SolarGeometry.solarElevationDegrees(scenario.location, it.time) }
                ?: 0.0

            val effectiveMed = profile.effectiveMedUvIndexHours
            val sddBaseline = profile.phototype.medThresholdUvIndexHours / 16.0

            // Transmittance is symmetric on burn and vit-D — both ride the same erythemal-
            // weighted UV index — so a single scalar folds into both rates and the time-to-X
            // formulas below stay linear.
            val burnRatePerHour = peakUv * effectiveTransmittance
            val vitDRatio = VitaminDModel.vitDRatio(peakElevation)
            val rawVitDRatePerHour = peakUv * vitDRatio * EXPOSED_BODY_FRACTION * effectiveTransmittance
            val effectiveVitDRatePerHour = rawVitDRatePerHour / profile.effectiveAcclimatizationFactor

            fun minutesToBurn(meds: Double): Double? =
                if (burnRatePerHour > 0.0) (meds * effectiveMed) / burnRatePerHour * 60.0 else null
            fun minutesToVitD(sdds: Double): Double? =
                if (effectiveVitDRatePerHour > 0.0)
                    (sdds * sddBaseline) / effectiveVitDRatePerHour * 60.0
                else null

            return SkinSummary(
                peakUv = peakUv,
                peakSolarElevationDeg = peakElevation,
                effectiveMedUvIndexHours = effectiveMed,
                sddUvIndexHours = sddBaseline,
                effectiveTransmittance = effectiveTransmittance,
                minutesToFirstReddening = minutesToBurn(1.0),
                minutesToSunburn = minutesToBurn(2.0),
                minutesToTraceVitD = minutesToVitD(0.25),
                minutesToLowVitD = minutesToVitD(0.5),
                minutesToAdequateVitD = minutesToVitD(1.0),
            )
        }
    }
}
