package com.insola.uv.dashboard

import com.insola.uv.dev.Scenario
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinSensitivity
import com.insola.uv.domain.uvAt
import com.insola.uv.dose.BurnModel
import com.insola.uv.dose.DoseIntegrator
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.solar.SolarGeometry
import kotlin.time.Duration.Companion.minutes

/**
 * Pure derivation of [DashboardState] from the user-controlled inputs. Kept out of the ViewModel
 * so the math is testable without spinning up a coroutine scope.
 *
 * Accumulated dose and vitamin-D both integrate strictly over the logged [OutdoorSession]s
 * (clipped to `[scenario.dayStart, now]`). Time-to-burn stays a hypothetical "if you were outside
 * continuously from now" projection — useful regardless of whether the user is currently outside.
 */
object DashboardCompute {
    fun compute(
        scenario: Scenario,
        hourOfDay: Double,
        sensitivity: SkinSensitivity,
        sessions: List<OutdoorSession> = emptyList(),
    ): DashboardState {
        val now = scenario.dayStart + (hourOfDay * 60).minutes
        val forecast = scenario.forecast
        val currentUv = forecast.uvAt(now)
        val elevation = SolarGeometry.solarElevationDegrees(scenario.location, now)

        val effectiveIntervals = sessions
            .map { it.toInterval(now) }
            .filter { it.end > it.start && it.start < now }
            .map { it.copy(end = minOf(it.end, now)) }

        val accumulated = DoseIntegrator.integrateOverIntervals(forecast, effectiveIntervals)
        val budgetPercent = accumulated / sensitivity.medThresholdUvIndexHours * 100.0
        val timeToBurn = BurnModel.timeToThreshold(
            now = now,
            forecast = forecast,
            sensitivity = sensitivity,
            assumedFactor = 1.0,
            alreadyAccumulated = accumulated,
        )
        val vitDBucket = VitaminDModel.bucketForIntervals(
            forecast = forecast,
            intervals = effectiveIntervals,
            sensitivity = sensitivity,
            skinExposedFraction = 0.25,
        )
        val isCurrentlyOutside = sessions.lastOrNull()?.let { it.isOpen && it.start <= now } == true
        return DashboardState(
            scenario = scenario,
            hourOfDay = hourOfDay,
            now = now,
            sensitivity = sensitivity,
            currentUv = currentUv,
            solarElevationDeg = elevation,
            accumulatedDose = accumulated,
            budgetPercent = budgetPercent,
            timeToBurn = timeToBurn,
            vitaminDBucket = vitDBucket,
            sessions = sessions,
            isCurrentlyOutside = isCurrentlyOutside,
        )
    }
}
