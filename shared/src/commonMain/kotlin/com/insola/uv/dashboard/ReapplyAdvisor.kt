package com.insola.uv.dashboard

import com.insola.uv.domain.UvForecast
import com.insola.uv.dose.DoseIntegrator
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Forward-looking "you'll need to top up soon" advisor. Frames the nudge as a real safety
 * statement: at a given future instant `t`, what SPF would be just barely enough to keep the
 * user's remaining burn budget alive for the next [horizon] of *continuous outdoor* exposure?
 *
 *   `S_threshold(t) = ∫UV dτ over [t, t + horizon]  /  safeDose`
 *
 * The numerator comes straight from the forecast (trapezoidal integration, same as the dose
 * integrator). [safeDose] is the remaining UV-index-hour headroom before first reddening
 * (`MED − already-accumulated`).
 *
 * The chart paints a translucent band from SPF 1 up to this curve, so the threshold tracks the
 * UV forecast (low in the evening, high mid-day) and the user's already-burned headroom.
 * The Apply button glows when current effective SPF is below the threshold at `now`.
 */
data class ReapplyAdvisor(
    val forecast: UvForecast,
    val safeDose: Double,
    val horizon: Duration = DEFAULT_HORIZON,
) {
    /**
     * Minimum effective SPF needed *at* [t] so that, if you stayed outside continuously through
     * `t + horizon` at that constant SPF, you'd land exactly at the remaining-budget line.
     * Returns +∞ if the budget is already spent — the chart caps it visually.
     */
    fun thresholdSpfAt(t: Instant): Double {
        if (safeDose <= 0.0) return Double.POSITIVE_INFINITY
        val uvIntegral = DoseIntegrator.integrate(forecast, t, t + horizon, exposureFactor = 1.0)
        if (uvIntegral <= 0.0) return MIN_THRESHOLD_SPF
        return (uvIntegral / safeDose).coerceAtLeast(MIN_THRESHOLD_SPF)
    }

    /** True when [currentEffectiveSpf] sits below the threshold at [t]. */
    fun needsTopUp(currentEffectiveSpf: Double, t: Instant): Boolean =
        currentEffectiveSpf < thresholdSpfAt(t)

    companion object {
        /** 30 min is short enough to be a near-term safety horizon, long enough to be actionable. */
        val DEFAULT_HORIZON: Duration = 30.minutes

        /** Threshold floor — the chart never draws a red band below SPF 1 (bare skin). */
        const val MIN_THRESHOLD_SPF: Double = 1.0
    }
}
