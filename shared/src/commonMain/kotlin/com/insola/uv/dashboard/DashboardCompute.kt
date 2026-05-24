package com.insola.uv.dashboard

import com.insola.uv.dev.Scenario
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
 */
object DashboardCompute {
    fun compute(
        scenario: Scenario,
        hourOfDay: Double,
        sensitivity: SkinSensitivity,
    ): DashboardState {
        val now = scenario.dayStart + (hourOfDay * 60).minutes
        val forecast = scenario.forecast
        val currentUv = forecast.uvAt(now)
        val elevation = SolarGeometry.solarElevationDegrees(scenario.location, now)
        val accumulated = DoseIntegrator.integrate(
            forecast = forecast,
            from = scenario.dayStart,
            to = now,
            exposureFactor = 1.0,
        )
        val budgetPercent = accumulated / sensitivity.medThresholdUvIndexHours * 100.0
        val timeToBurn = BurnModel.timeToThreshold(
            now = now,
            forecast = forecast,
            sensitivity = sensitivity,
            assumedFactor = 1.0,
            alreadyAccumulated = accumulated,
        )
        val vitDBucket = VitaminDModel.bucketForToday(
            forecast = forecast,
            from = scenario.dayStart,
            to = now,
            skinExposedFraction = 0.25,
        )
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
        )
    }
}
