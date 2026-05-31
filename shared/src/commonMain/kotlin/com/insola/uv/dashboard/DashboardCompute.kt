package com.insola.uv.dashboard

import com.insola.uv.dev.Scenario
import com.insola.uv.domain.AttenuationTimeline
import com.insola.uv.domain.ExposureInterval
import com.insola.uv.domain.OutdoorSession
import com.insola.uv.domain.SkinProfile
import com.insola.uv.domain.uvAt
import com.insola.uv.dose.BurnModel
import com.insola.uv.dose.BurnTier
import com.insola.uv.dose.DoseIntegrator
import com.insola.uv.dose.VitaminDModel
import com.insola.uv.solar.SolarGeometry
import kotlinx.datetime.Instant
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
        attenuation: AttenuationTimeline = AttenuationTimeline.Empty,
    ): DashboardState {
        val now = scenario.hourToInstant(hourOfDay)
        val forecast = scenario.forecast
        val currentUv = forecast.uvAt(now)
        val elevation = SolarGeometry.solarElevationDegrees(scenario.location, now)

        // The effective UV transmittance at any instant is whatever the [attenuation] timeline
        // says — bare skin (1.0) where no patch covers it. Past-looking integrals slice each
        // exposure interval at the timeline's step boundaries so a single transmittance applies
        // per slice. Forward-looking projections (time-to-burn) use the transmittance at `now`.
        val effectiveTransmittanceNow = attenuation.transmittanceAt(now)
        val activeAttenuation = attenuation.activeAt(now)

        val effectiveIntervals = sessions
            .map { it.toInterval(now) }
            .filter { it.end > it.start && it.start < now }
            .map { it.copy(end = minOf(it.end, now)) }
            .flatMap { splitByAttenuation(it, attenuation) }
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
            assumedFactor = effectiveTransmittanceNow,
            alreadyAccumulated = accumulated,
            thresholdMultiplier = 1.0,
        )
        val timeToSunburn = BurnModel.timeToThreshold(
            now = now,
            forecast = forecast,
            profile = profile,
            assumedFactor = effectiveTransmittanceNow,
            alreadyAccumulated = accumulated,
            thresholdMultiplier = 2.0,
        )
        val vitDScore = VitaminDModel.accumulateOverIntervals(
            forecast = forecast,
            intervals = effectiveIntervals,
            skinExposedFraction = 0.25,
        )
        val vitDBucket = VitaminDModel.bucket(vitDScore, profile)
        // The Skin-tab sundial is a "your bare skin at today's peak UV" reference; the SPF
        // what-if preview is layered on top in the UI, so the summary itself stays bare.
        val skinSummary = SkinSummary.compute(scenario, profile)
        val isCurrentlyOutside = sessions.lastOrNull()?.let { it.isOpen && it.start <= now } == true
        val reapplyAdvisor = ReapplyAdvisor(
            forecast = forecast,
            safeDose = (medThreshold - accumulated).coerceAtLeast(0.0),
        )
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
            attenuation = attenuation,
            activeAttenuation = activeAttenuation,
            effectiveTransmittance = effectiveTransmittanceNow,
            reapplyAdvisor = reapplyAdvisor,
        )
    }

    /**
     * Slice [interval] at the timeline's sampling grid so each sub-interval integrates against
     * a single transmittance read from the timeline at the slice midpoint (bare skin where no
     * patch covers it). Evaluating at the midpoint makes the piecewise-constant approximation
     * second-order accurate against the smooth decay curve, so 5-min steps stay within < 0.5 %
     * of the exact integral for typical SPF values.
     */
    private fun splitByAttenuation(
        interval: ExposureInterval,
        attenuation: AttenuationTimeline,
    ): List<ExposureInterval> {
        if (attenuation.patches.isEmpty()) return listOf(interval)
        val cuts = (sequenceOf(interval.start, interval.end) + attenuation.criticalTimes().asSequence())
            .map { it.coerceIn(interval.start, interval.end) }
            .distinct()
            .sorted()
            .toList()
        return cuts.zipWithNext { a, b ->
            val mid = a + (b - a) / 2
            val t = attenuation.transmittanceAt(mid)
            interval.copy(start = a, end = b, exposureFactor = interval.exposureFactor * t)
        }.filter { it.end > it.start }
    }
}
