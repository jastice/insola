package com.insola.uv.dashboard

import com.insola.uv.dev.Scenario
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.uvAt
import com.insola.uv.dose.BurnModel
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.DoseIntegrator
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.solar.SolarGeometry
import kotlin.time.Duration.Companion.milliseconds

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
        profile: SkinProfile,
        sessions: List<OutdoorSession> = emptyList(),
    ): DashboardState {
        val now = scenario.hourToInstant(hourOfDay)
        val forecast = scenario.forecast
        val currentUv = forecast.uvAt(now)
        val elevation = SolarGeometry.solarElevationDegrees(scenario.location, now)

        val effectiveIntervals = sessions
            .map { it.toInterval(now) }
            .filter { it.end > it.start && it.start < now }
            .map { it.copy(end = minOf(it.end, now)) }
        val timeOutside = effectiveIntervals
            .sumOf { (it.end - it.start).inWholeMilliseconds }
            .milliseconds
        val daylight = SolarGeometry.daylightWindow(scenario.location, scenario.dayStart)

        val accumulated = DoseIntegrator.integrateOverIntervals(forecast, effectiveIntervals)
        val medThreshold = profile.effectiveMedUvIndexHours
        val budgetPercent = accumulated / medThreshold * 100.0
        val burnTier = BurnTier.forFractionOfMed(accumulated / medThreshold)
        val timeToFirstReddening = BurnModel.timeToThreshold(
            now = now,
            forecast = forecast,
            profile = profile,
            assumedFactor = 1.0,
            alreadyAccumulated = accumulated,
            thresholdMultiplier = 1.0,
        )
        val timeToSunburn = BurnModel.timeToThreshold(
            now = now,
            forecast = forecast,
            profile = profile,
            assumedFactor = 1.0,
            alreadyAccumulated = accumulated,
            thresholdMultiplier = 2.0,
        )
        val vitDScore = VitaminDModel.accumulateOverIntervals(
            forecast = forecast,
            intervals = effectiveIntervals,
            skinExposedFraction = 0.25,
        )
        val vitDBucket = VitaminDModel.bucket(vitDScore, profile)
        val skinSummary = SkinSummary.compute(scenario, profile)
        val isCurrentlyOutside = sessions.lastOrNull()?.let { it.isOpen && it.start <= now } == true
        return DashboardState(
            scenario = scenario,
            hourOfDay = hourOfDay,
            now = now,
            profile = profile,
            skinSummary = skinSummary,
            currentUv = currentUv,
            solarElevationDeg = elevation,
            sunriseHour = daylight.sunriseHour,
            sunsetHour = daylight.sunsetHour,
            accumulatedDose = accumulated,
            budgetPercent = budgetPercent,
            burnTier = burnTier,
            timeToFirstReddening = timeToFirstReddening,
            timeToSunburn = timeToSunburn,
            vitaminDScore = vitDScore,
            vitaminDBucket = vitDBucket,
            sessions = sessions,
            timeOutside = timeOutside,
            isCurrentlyOutside = isCurrentlyOutside,
        )
    }
}
